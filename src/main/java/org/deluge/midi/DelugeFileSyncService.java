package org.deluge.midi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-performance file synchronization service. Connects to the physical Deluge over USB MIDI via
 * the DelugeSysExManager. Supports directory listing, non-blocking chunked file downloads, and
 * chunked file uploads using Java Virtual Threads.
 */
public class DelugeFileSyncService {

  public interface FileListCallback {
    void onSuccess(List<String> files);

    void onFailure(Throwable t);
  }

  public interface FileDownloadCallback {
    void onSuccess(byte[] content);

    void onFailure(Throwable t);
  }

  public interface FileUploadCallback {
    void onSuccess();

    void onFailure(Throwable t);
  }

  public interface DirectoryListCallback {
    void onSuccess(List<RemoteFileEntry> entries);

    void onFailure(Throwable t);
  }

  public interface FileOpCallback {
    void onSuccess();

    void onFailure(Throwable t);
  }

  /** Reports transfer progress; {@code total} is the file size in bytes (0 if unknown yet). */
  public interface ProgressCallback {
    void onProgress(long done, long total);
  }

  // Reply record for Loom future blocking
  private record Reply(String json, byte[] bin) {}

  private final DelugeSysExManager sysExManager;
  private volatile boolean transferActive = false;

  public boolean isTransferActive() {
    return transferActive;
  }

  public DelugeFileSyncService(DelugeSysExManager sysExManager) {
    this.sysExManager = sysExManager;
  }

  /**
   * Retrieves the list of files in a remote directory on the physical Deluge's SD card.
   *
   * @param remotePath Absolute remote path (e.g. {@code "/SONGS"} or {@code "/SYNTHS"})
   * @param callback Callback for success (file list) or failure
   */
  public void listSongs(String remotePath, FileListCallback callback) {
    Thread.ofVirtual()
        .name("DelugeDirLister-" + remotePath)
        .start(
            () -> {
              boolean prevOled = sysExManager.isOledStreamingEnabled();
              transferActive = true;
              sysExManager.setOledStreamingEnabled(
                  false); // quiet the OLED flood during the listing
              try {
                callback.onSuccess(listOneBlocking(remotePath));
              } catch (Exception e) {
                callback.onFailure(e);
              } finally {
                sysExManager.setOledStreamingEnabled(prevOled);
                transferActive = false;
              }
            });
  }

  /** Per-directory callback for {@link #listDirs}. */
  public interface DirListCallback {
    void onDir(String path, List<String> files);

    void onError(String path, Throwable t);
  }

  /**
   * Lists several remote directories <b>sequentially on a single virtual thread</b>. This is the
   * correct way to refresh the whole remote tree (SONGS + SYNTHS + KITS): the firmware {@code dir}
   * command keeps a <i>single</i> shared cursor ({@code sxDIR}/{@code dirOffsetCounter} in
   * smsysex.cpp), so overlapping/interleaved listings would corrupt each other's pagination.
   * Serializing them here — and toggling the OLED stream + {@code transferActive} once for the
   * whole batch — avoids that and the OLED save/restore races that nesting per-path listings would
   * cause.
   */
  public void listDirs(List<String> paths, DirListCallback callback) {
    Thread.ofVirtual()
        .name("DelugeDirLister-batch")
        .start(
            () -> {
              boolean prevOled = sysExManager.isOledStreamingEnabled();
              transferActive = true;
              sysExManager.setOledStreamingEnabled(false);
              try {
                for (String path : paths) {
                  try {
                    callback.onDir(path, listOneBlocking(path));
                  } catch (Exception e) {
                    callback.onError(path, e);
                  }
                }
              } finally {
                sysExManager.setOledStreamingEnabled(prevOled);
                transferActive = false;
              }
            });
  }

  /**
   * Blocking, paginated, retrying directory listing — the core the async wrappers share. Mirrors
   * the download path because the Deluge {@code dir} command needs BOTH pagination and retry, which
   * the old single fire-and-forget request lacked (which is why the remote tree showed no songs):
   *
   * <ul>
   *   <li><b>Pagination</b>: the firmware (smsysex.cpp getDirEntries) returns at most {@code
   *       MAX_DIR_LINES = 25} entries and tracks an internal offset cursor; to list a full
   *       directory the host must send repeated {@code dir} requests with increasing {@code offset}
   *       until a short page (&lt; linesWanted) marks the end. A single request only ever saw the
   *       first ≤25.
   *   <li><b>Retry</b>: the /SONGS response is a large (~1.5 KB) SysEx and larger messages are
   *       dropped ~half the time (see {@link #sendWithRetry}); with no retry a dropped page left
   *       the tree empty.
   * </ul>
   *
   * <p>Caller is responsible for the OLED-stream pause and {@code transferActive} guard.
   */
  private List<String> listOneBlocking(String remotePath) throws Exception {
    List<String> files = new ArrayList<>();
    final int linesWanted = 25; // MAX_DIR_LINES in firmware smsysex.cpp
    int offset = 0;
    while (true) {
      String req =
          String.format(
              "{\"dir\":{\"path\":\"%s\",\"offset\":%d,\"lines\":%d}}",
              remotePath, offset, linesWanted);
      Reply reply;
      try {
        // Deep pages need a long per-attempt timeout, NOT just more attempts. When a page's reply
        // drops, the firmware's dir cursor has already advanced past `offset`, so every RETRY makes
        // it reopen the directory and re-seek by f_readdir'ing `offset` entries before replying
        // (smsysex.cpp getDirEntries). That re-seek grows with offset and can exceed a short
        // timeout — so with 800ms we'd falsely time out on the re-seek'd reply and retry forever,
        // which is exactly why the 3rd page (@50) failed 100% of the time while @0/@25 didn't. A
        // normal reply still returns in ~15ms, so the wide window only costs time on actual drops.
        reply = sendWithRetry(req, null, 8, 3000, "dir " + remotePath + "@" + offset);
      } catch (Exception e) {
        // A page failed after all retries. If it was the very first page we have nothing useful, so
        // propagate (a genuine connection failure). Otherwise keep what we already fetched rather
        // than discarding the whole directory — better to show the first N entries than none (this
        // is why /SYNTHS showed zero when only its 3rd page kept dropping).
        if (offset == 0) {
          throw e;
        }
        System.err.println(
            "[FileSyncService] "
                + remotePath
                + " listing truncated at offset "
                + offset
                + " ("
                + files.size()
                + " entries so far): "
                + e.getMessage());
        break;
      }
      int err = getIntAttr(reply.json(), "err");
      if (err != 0) {
        if (offset == 0) {
          throw new IOException("Remote dir list failed for " + remotePath + ", err=" + err);
        }
        break; // partial listing: stop on a mid-directory error, keep what we have
      }
      int entryCount = countDirEntries(reply.json()); // all entries incl. directories
      files.addAll(getFileNamesFromList(reply.json())); // non-directory names only
      offset += entryCount;
      if (entryCount < linesWanted) {
        break; // short page => end of directory
      }
    }
    System.out.println(
        "[FileSyncService] listOneBlocking " + remotePath + " -> " + files.size() + " files");
    return files;
  }

  /**
   * Non-blocking asynchronous file downloader. Spawns a lightweight virtual thread to download the
   * file in sequential 512-byte blocks.
   *
   * @param remotePath Absolute remote path to download (e.g. {@code "/SONGS/SONG001.XML"})
   * @param callback Success/failure callback
   */
  public void downloadFileAsync(String remotePath, FileDownloadCallback callback) {
    downloadFileAsync(remotePath, callback, null);
  }

  /** Non-blocking download variant that also reports per-block progress. */
  public void downloadFileAsync(
      String remotePath, FileDownloadCallback callback, ProgressCallback progress) {
    Thread.ofVirtual()
        .name("DelugeFileDownloader-" + remotePath)
        .start(
            () -> {
              try {
                byte[] content = downloadFileBlocking(remotePath, progress);
                callback.onSuccess(content);
              } catch (Exception e) {
                callback.onFailure(e);
              }
            });
  }

  /**
   * Non-blocking asynchronous file uploader. Spawns a lightweight virtual thread to upload the file
   * in sequential 512-byte blocks.
   *
   * @param remotePath Absolute remote path to write to (e.g. {@code "/SONGS/SONG001.XML"})
   * @param content Unpacked 8-bit binary content
   * @param callback Success/failure callback
   */
  public void uploadFileAsync(String remotePath, byte[] content, FileUploadCallback callback) {
    Thread.ofVirtual()
        .name("DelugeFileUploader-" + remotePath)
        .start(
            () -> {
              try {
                uploadFileBlocking(remotePath, content);
                callback.onSuccess();
              } catch (Exception e) {
                callback.onFailure(e);
              }
            });
  }

  /**
   * Sends a JSON (optionally + binary) request and waits for the reply, re-firing on timeout.
   *
   * <p>Larger host&rarr;Deluge SysEx messages are dropped intermittently by the USB-MIDI transport
   * (small messages like ping are 100% reliable; ~57-byte+ requests land roughly half the time),
   * yet {@code snd_seq_event_output_direct} still reports success. A genuine reply returns in
   * ~15ms, so a short per-attempt timeout that elapses means the request was dropped in transit: we
   * simply send it again. This makes the file protocol resilient without changing the transport.
   *
   * @param json the JSON request
   * @param binary optional packed binary payload (e.g. for write blocks), or null
   * @param maxAttempts number of send attempts before giving up
   * @param perAttemptMs per-attempt reply timeout in milliseconds (must comfortably exceed the
   *     ~15ms real reply latency so a timeout reliably means "dropped", not "slow", avoiding
   *     stale-reply aliasing)
   * @param what short description for the error message
   */
  private Reply sendWithRetry(
      String json, byte[] binary, int maxAttempts, long perAttemptMs, String what)
      throws Exception {
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      CompletableFuture<Reply> fut = new CompletableFuture<>();
      DelugeSysExManager.SysExCallback cb = (j, b) -> fut.complete(new Reply(j, b));
      if (binary != null) {
        sysExManager.sendRequest(json, binary, cb);
      } else {
        sysExManager.sendRequest(json, cb);
      }
      try {
        return fut.get(perAttemptMs, TimeUnit.MILLISECONDS);
      } catch (java.util.concurrent.TimeoutException te) {
        if (attempt == maxAttempts) {
          throw new IOException(
              "No reply after " + maxAttempts + " attempts (USB SysEx drops): " + what, te);
        }
        Thread.sleep(40); // brief backoff before re-firing the dropped request
      }
    }
    throw new IllegalStateException("unreachable");
  }

  public byte[] downloadFileBlocking(String remotePath) throws Exception {
    return downloadFileBlocking(remotePath, null);
  }

  public byte[] downloadFileBlocking(String remotePath, ProgressCallback progress)
      throws Exception {
    transferActive = true;
    sysExManager.setOledStreamingEnabled(false);
    try {
      // The Deluge's OLED stream is a self-expiring ~2s window (hid_sysex:
      // midiDisplayUntil = audioSampleTimer + 2*kSampleRate). Pausing our keep-alive only stops
      // refreshing it, so we must wait out the full window before issuing the request, otherwise
      // the
      // reply lands amid an OLED flood and can be lost. Wait > 2s for a quiet channel.
      Thread.sleep(2200);

      // 1. Open remote file
      Reply openReply =
          sendWithRetry(
              String.format("{\"open\": {\"path\": \"%s\", \"write\": 0}}", remotePath),
              null,
              12,
              800,
              "open " + remotePath);
      int fid = getIntAttr(openReply.json(), "fid");
      int size = getIntAttr(openReply.json(), "size");
      int openErr = getIntAttr(openReply.json(), "err");
      if (openErr != 0 || fid == 0) {
        throw new IOException("Failed to open remote file: " + remotePath + ", err=" + openErr);
      }

      byte[] fileData = new byte[size];
      int bytesRead = 0;
      int chunkSize = 512; // Deluge maximum cluster read block size is 1024, 512 is safe and fast
      if (progress != null) {
        progress.onProgress(0, size);
      }

      // 2. Loop block reads
      while (bytesRead < size) {
        // Brief yield to let the microcontroller clear USB endpoints and SD card locks
        Thread.sleep(15);

        int toRead = Math.min(chunkSize, size - bytesRead);
        final int currentOffset = bytesRead;
        Reply readReply =
            sendWithRetry(
                String.format(
                    "{\"read\": {\"fid\": %d, \"addr\": %d, \"size\": %d}}",
                    fid, bytesRead, toRead),
                null,
                30,
                500,
                "read @" + currentOffset);
        int readErr = getIntAttr(readReply.json(), "err");
        byte[] binData = readReply.bin();
        if (readErr != 0 || binData == null || binData.length == 0) {
          throw new IOException(
              "Failed to read remote block at offset " + currentOffset + ", err=" + readErr);
        }

        System.arraycopy(binData, 0, fileData, currentOffset, binData.length);
        bytesRead += binData.length;
        if (progress != null) {
          progress.onProgress(bytesRead, size);
        }
      }

      // Sleep 50ms before closing
      Thread.sleep(50);

      // 3. Close remote file
      sendWithRetry(String.format("{\"close\": {\"fid\": %d}}", fid), null, 4, 800, "close");

      return fileData;
    } finally {
      sysExManager.setOledStreamingEnabled(true);
      sysExManager.startOledStreaming();
      transferActive = false;
    }
  }

  public void uploadFileBlocking(String remotePath, byte[] content) throws Exception {
    uploadFileBlocking(remotePath, content, System.currentTimeMillis());
  }

  public void uploadFileBlocking(String remotePath, byte[] content, long lastModifiedMillis)
      throws Exception {
    transferActive = true;
    sysExManager.setOledStreamingEnabled(false);
    try {
      // The Deluge's OLED stream is a self-expiring ~2s window (hid_sysex:
      // midiDisplayUntil = audioSampleTimer + 2*kSampleRate). Pausing our keep-alive only stops
      // refreshing it, so we must wait out the full window before issuing the request, otherwise
      // the
      // reply lands amid an OLED flood and can be lost. Wait > 2s for a quiet channel.
      Thread.sleep(2200);

      // Convert local epoch millisecond to FAT date/time format
      int[] fatDT = encodeFatDateTime(lastModifiedMillis);
      int fatDate = fatDT[0];
      int fatTime = fatDT[1];

      // 1. Open remote file for writing
      Reply openReply =
          sendWithRetry(
              String.format(
                  "{\"open\": {\"path\": \"%s\", \"write\": 1, \"date\": %d, \"time\": %d}}",
                  remotePath, fatDate, fatTime),
              null,
              12,
              800,
              "open(write) " + remotePath);
      int fid = getIntAttr(openReply.json(), "fid");
      int openErr = getIntAttr(openReply.json(), "err");
      if (openErr != 0 || fid == 0) {
        throw new IOException(
            "Failed to open remote file for writing: " + remotePath + ", err=" + openErr);
      }

      int bytesWritten = 0;
      int size = content.length;
      int chunkSize = 512;

      // 2. Loop block writes
      while (bytesWritten < size) {
        // Brief yield to let the microcontroller clear USB endpoints and SD card locks
        Thread.sleep(15);

        int toWrite = Math.min(chunkSize, size - bytesWritten);
        byte[] chunk = new byte[toWrite];
        System.arraycopy(content, bytesWritten, chunk, 0, toWrite);
        byte[] packedChunk = DelugeMidiPacker.pack8to7(chunk);

        final int currentOffset = bytesWritten;
        // Write requests carry the largest payloads, so they are the most likely to be dropped;
        // give them the most retries and a longer per-attempt window.
        Reply writeReply =
            sendWithRetry(
                String.format("{\"write\": {\"fid\": %d, \"size\": %d}}", fid, toWrite),
                packedChunk,
                30,
                700,
                "write @" + currentOffset);
        int writeErr = getIntAttr(writeReply.json(), "err");
        int written = getIntAttr(writeReply.json(), "bytes");
        if (writeErr != 0 || written == 0) {
          throw new IOException(
              "Failed to write remote block at offset " + currentOffset + ", err=" + writeErr);
        }

        bytesWritten += toWrite;
      }

      // Sleep 50ms before closing
      Thread.sleep(50);

      // 3. Close remote file
      sendWithRetry(String.format("{\"close\": {\"fid\": %d}}", fid), null, 4, 800, "close");

      // 4. Preserve timestamp via utime (best-effort)
      try {
        Thread.sleep(50);
        Reply utimeReply =
            sendWithRetry(
                String.format(
                    "{\"utime\": {\"path\": \"%s\", \"date\": %d, \"time\": %d}}",
                    remotePath, fatDate, fatTime),
                null,
                3,
                800,
                "utime");
        int utimeErr = getIntAttr(utimeReply.json(), "err");
        if (utimeErr != 0) {
          System.err.println("[FileSync] Warning: Failed to set file timestamp, err=" + utimeErr);
        }
      } catch (Exception e) {
        System.err.println("[FileSync] Warning: Error setting file timestamp: " + e.getMessage());
      }

    } finally {
      sysExManager.setOledStreamingEnabled(true);
      sysExManager.startOledStreaming();
      transferActive = false;
    }
  }

  // ── Tiny, Bulletproof JSON Parsing Utilities ──

  private static int getIntAttr(String json, String attrName) {
    Pattern pattern = Pattern.compile("\"" + attrName + "\"\\s*:\\s*(-?\\d+)");
    Matcher matcher = pattern.matcher(json);
    if (matcher.find()) {
      return Integer.parseInt(matcher.group(1));
    }
    return -1;
  }

  /**
   * Counts ALL directory entries in a {@code ^dir} reply (files AND sub-directories), used to drive
   * pagination: a page shorter than {@code linesWanted} means the end of the directory was reached.
   * Distinct from {@link #getFileNamesFromList}, which returns only the non-directory names.
   */
  static int countDirEntries(String json) {
    Matcher m = Pattern.compile("\"name\"\\s*:").matcher(json);
    int n = 0;
    while (m.find()) {
      n++;
    }
    return n;
  }

  static List<String> getFileNamesFromList(String json) {
    List<String> result = new ArrayList<>();
    // Each ^dir entry looks like {"name":"X.XML","size":..,"date":..,"time":..,"attr":N}.
    // The FatFS attribute bit 0x10 (AM_DIR) marks a directory (e.g. a song's "NAME.DATA" folder),
    // which cannot be opened/downloaded as a file (f_open returns FR_NO_FILE / err=4). Pair each
    // name with the attr that follows it and skip directories so they aren't offered as songs.
    Matcher nameM = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
    Matcher attrM = Pattern.compile("\"attr\"\\s*:\\s*(\\d+)").matcher(json);
    while (nameM.find()) {
      String name = nameM.group(1);
      boolean isDir = false;
      if (attrM.find(nameM.end())) {
        isDir = (Integer.parseInt(attrM.group(1)) & 0x10) != 0;
      }
      if (!isDir) {
        result.add(name);
      }
    }
    return result;
  }

  /** Decodes native 16-bit FAT date and 16-bit FAT time into epoch milliseconds. */
  public static long decodeFatDateTime(int fatDate, int fatTime) {
    if (fatDate <= 0) {
      return 0;
    }
    int year = ((fatDate >> 9) & 0x7F) + 1980;
    int month = (fatDate >> 5) & 0x0F;
    int day = fatDate & 0x1F;
    int hour = (fatTime >> 11) & 0x1F;
    int minute = (fatTime >> 5) & 0x3F;
    int second = (fatTime & 0x1F) * 2;
    try {
      java.time.LocalDateTime ldt =
          java.time.LocalDateTime.of(
              Math.max(1980, year),
              Math.max(1, Math.min(12, month)),
              Math.max(1, Math.min(31, day)),
              Math.max(0, Math.min(23, hour)),
              Math.max(0, Math.min(59, minute)),
              Math.max(0, Math.min(59, second)));
      return ldt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    } catch (Exception e) {
      return 0;
    }
  }

  /** Encodes epoch milliseconds into native 16-bit FAT date and time. */
  public static int[] encodeFatDateTime(long epochMillis) {
    java.time.Instant instant = java.time.Instant.ofEpochMilli(epochMillis);
    java.time.LocalDateTime dt =
        java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
    int fatDate = ((dt.getYear() - 1980) << 9) | (dt.getMonthValue() << 5) | dt.getDayOfMonth();
    int fatTime = (dt.getHour() << 11) | (dt.getMinute() << 5) | (dt.getSecond() / 2);
    return new int[] {fatDate, fatTime};
  }

  /** Parses directory entries from native C++ JSON response. */
  public static List<RemoteFileEntry> parseDirectoryEntries(String json) {
    List<RemoteFileEntry> entries = new ArrayList<>();
    // Matches {"name":"...","size":...,"date":...,"time":...,"attr":...}
    Pattern entryPattern =
        Pattern.compile(
            "\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"size\"\\s*:\\s*(\\d+)\\s*,\\s*\"date\"\\s*:\\s*(\\d+)\\s*,\\s*\"time\"\\s*:\\s*(\\d+)\\s*,\\s*\"attr\"\\s*:\\s*(\\d+)\\s*\\}");
    Matcher matcher = entryPattern.matcher(json);
    while (matcher.find()) {
      String name = matcher.group(1);
      long size = Long.parseLong(matcher.group(2));
      int fatDate = Integer.parseInt(matcher.group(3));
      int fatTime = Integer.parseInt(matcher.group(4));
      int attr = Integer.parseInt(matcher.group(5));

      boolean isDirectory = (attr & 0x10) != 0;
      boolean isReadOnly = (attr & 0x01) != 0;
      boolean isHidden = (attr & 0x02) != 0;
      long lastModified = decodeFatDateTime(fatDate, fatTime);

      entries.add(new RemoteFileEntry(name, size, lastModified, isDirectory, isReadOnly, isHidden));
    }
    return entries;
  }

  /**
   * Retrieves the detailed metadata-preserving list of directory entries on the Deluge.
   *
   * @param remotePath Absolute remote path (e.g. {@code "/SONGS"} or {@code "/SYNTHS"})
   * @param callback Callback for success (file entry list) or failure
   */
  public void listDirectory(String remotePath, DirectoryListCallback callback) {
    String req = String.format("{\"dir\": {\"path\": \"%s\", \"lines\": 25}}", remotePath);
    sysExManager.sendRequest(
        req,
        (json, bin) -> {
          int err = getIntAttr(json, "err");
          if (err != 0) {
            callback.onFailure(new IOException("Remote dir list failed, err=" + err));
            return;
          }
          List<RemoteFileEntry> entries = parseDirectoryEntries(json);
          callback.onSuccess(entries);
        });
  }

  // ── New Remote File System Operations ──

  /** Create a remote directory. */
  public int createDirectoryBlocking(String remotePath, long lastModifiedMillis) throws Exception {
    int[] fatDT = encodeFatDateTime(lastModifiedMillis);
    String req =
        String.format(
            "{\"mkdir\": {\"path\": \"%s\", \"date\": %d, \"time\": %d}}",
            remotePath, fatDT[0], fatDT[1]);
    Reply r = sendWithRetry(req, null, 8, 800, "mkdir " + remotePath);
    return getIntAttr(r.json(), "err");
  }

  public void createDirectoryAsync(
      String remotePath, long lastModifiedMillis, FileOpCallback callback) {
    Thread.ofVirtual()
        .name("DelugeDirCreator-" + remotePath)
        .start(
            () -> {
              try {
                int err = createDirectoryBlocking(remotePath, lastModifiedMillis);
                if (err != 0) {
                  callback.onFailure(new IOException("mkdir failed, err=" + err));
                } else {
                  callback.onSuccess();
                }
              } catch (Exception e) {
                callback.onFailure(e);
              }
            });
  }

  /** Delete a remote file or directory. */
  public int deleteBlocking(String remotePath) throws Exception {
    String req = String.format("{\"delete\": {\"path\": \"%s\"}}", remotePath);
    Reply r = sendWithRetry(req, null, 8, 800, "delete " + remotePath);
    return getIntAttr(r.json(), "err");
  }

  public void deleteAsync(String remotePath, FileOpCallback callback) {
    Thread.ofVirtual()
        .name("DelugeDeleter-" + remotePath)
        .start(
            () -> {
              try {
                int err = deleteBlocking(remotePath);
                if (err != 0) {
                  callback.onFailure(new IOException("delete failed, err=" + err));
                } else {
                  callback.onSuccess();
                }
              } catch (Exception e) {
                callback.onFailure(e);
              }
            });
  }

  /** Rename a remote file or directory. */
  public int renameBlocking(String fromPath, String toPath) throws Exception {
    String req =
        String.format("{\"rename\": {\"from\": \"%s\", \"to\": \"%s\"}}", fromPath, toPath);
    Reply r = sendWithRetry(req, null, 8, 800, "rename " + fromPath + " to " + toPath);
    return getIntAttr(r.json(), "err");
  }

  public void renameAsync(String fromPath, String toPath, FileOpCallback callback) {
    Thread.ofVirtual()
        .name("DelugeRenamer-" + fromPath)
        .start(
            () -> {
              try {
                int err = renameBlocking(fromPath, toPath);
                if (err != 0) {
                  callback.onFailure(new IOException("rename failed, err=" + err));
                } else {
                  callback.onSuccess();
                }
              } catch (Exception e) {
                callback.onFailure(e);
              }
            });
  }

  /** Copy a file natively on the Deluge SD card. */
  public int copyBlocking(String fromPath, String toPath, long lastModifiedMillis)
      throws Exception {
    int[] fatDT = encodeFatDateTime(lastModifiedMillis);
    String req =
        String.format(
            "{\"copy\": {\"from\": \"%s\", \"to\": \"%s\", \"date\": %d, \"time\": %d}}",
            fromPath, toPath, fatDT[0], fatDT[1]);
    Reply r = sendWithRetry(req, null, 15, 800, "copy " + fromPath);
    return getIntAttr(r.json(), "err");
  }

  public void copyAsync(
      String fromPath, String toPath, long lastModifiedMillis, FileOpCallback callback) {
    Thread.ofVirtual()
        .name("DelugeCopier-" + fromPath)
        .start(
            () -> {
              try {
                int err = copyBlocking(fromPath, toPath, lastModifiedMillis);
                if (err != 0) {
                  callback.onFailure(new IOException("copy failed, err=" + err));
                } else {
                  callback.onSuccess();
                }
              } catch (Exception e) {
                callback.onFailure(e);
              }
            });
  }

  /** Move a file natively on the Deluge SD card. */
  public int moveBlocking(String fromPath, String toPath, long lastModifiedMillis)
      throws Exception {
    int[] fatDT = encodeFatDateTime(lastModifiedMillis);
    String req =
        String.format(
            "{\"move\": {\"from\": \"%s\", \"to\": \"%s\", \"date\": %d, \"time\": %d}}",
            fromPath, toPath, fatDT[0], fatDT[1]);
    Reply r = sendWithRetry(req, null, 15, 800, "move " + fromPath);
    return getIntAttr(r.json(), "err");
  }

  public void moveAsync(
      String fromPath, String toPath, long lastModifiedMillis, FileOpCallback callback) {
    Thread.ofVirtual()
        .name("DelugeMover-" + fromPath)
        .start(
            () -> {
              try {
                int err = moveBlocking(fromPath, toPath, lastModifiedMillis);
                if (err != 0) {
                  callback.onFailure(new IOException("move failed, err=" + err));
                } else {
                  callback.onSuccess();
                }
              } catch (Exception e) {
                callback.onFailure(e);
              }
            });
  }

  /** Explicitly set a remote file or directory's modification timestamp. */
  public int setTimestampBlocking(String remotePath, long lastModifiedMillis) throws Exception {
    int[] fatDT = encodeFatDateTime(lastModifiedMillis);
    String req =
        String.format(
            "{\"utime\": {\"path\": \"%s\", \"date\": %d, \"time\": %d}}",
            remotePath, fatDT[0], fatDT[1]);
    Reply r = sendWithRetry(req, null, 8, 800, "utime " + remotePath);
    return getIntAttr(r.json(), "err");
  }

  public void setTimestampAsync(
      String remotePath, long lastModifiedMillis, FileOpCallback callback) {
    Thread.ofVirtual()
        .name("DelugeTimestampUpdater-" + remotePath)
        .start(
            () -> {
              try {
                int err = setTimestampBlocking(remotePath, lastModifiedMillis);
                if (err != 0) {
                  callback.onFailure(new IOException("utime failed, err=" + err));
                } else {
                  callback.onSuccess();
                }
              } catch (Exception e) {
                callback.onFailure(e);
              }
            });
  }
}
