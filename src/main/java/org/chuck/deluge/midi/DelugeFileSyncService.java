package org.chuck.deluge.midi;

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
    String req = String.format("{\"dir\": {\"path\": \"%s\"}}", remotePath);
    sysExManager.sendRequest(
        req,
        (json, bin) -> {
          int err = getIntAttr(json, "err");
          if (err != 0) {
            callback.onFailure(new IOException("Remote dir list failed, err=" + err));
            return;
          }
          List<String> files = getFileNamesFromList(json);
          if (files.isEmpty()) {
            files = getStringListAttr(json, "files");
          }
          System.out.println(
              "[FileSyncService] listSongs success for path: "
                  + remotePath
                  + ", parsed files count: "
                  + files.size()
                  + ", files: "
                  + files);
          callback.onSuccess(files);
        });
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
      java.time.Instant instant = java.time.Instant.ofEpochMilli(lastModifiedMillis);
      java.time.LocalDateTime dt =
          java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
      int fatDate = ((dt.getYear() - 1980) << 9) | (dt.getMonthValue() << 5) | dt.getDayOfMonth();
      int fatTime = (dt.getHour() << 11) | (dt.getMinute() << 5) | (dt.getSecond() / 2);

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

  private static List<String> getStringListAttr(String json, String attrName) {
    List<String> result = new ArrayList<>();
    Pattern arrayPattern = Pattern.compile("\"" + attrName + "\"\\s*:\\s*\\[([^\\]]*)\\]");
    Matcher arrayMatcher = arrayPattern.matcher(json);
    if (arrayMatcher.find()) {
      String arrayContent = arrayMatcher.group(1);
      Pattern stringPattern = Pattern.compile("\"([^\"]+)\"");
      Matcher stringMatcher = stringPattern.matcher(arrayContent);
      while (stringMatcher.find()) {
        result.add(stringMatcher.group(1));
      }
    }
    return result;
  }

  private static List<String> getFileNamesFromList(String json) {
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
}
