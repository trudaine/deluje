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

  // Response records for Loom future blocking
  private record OpenResponse(int fid, int size, int err) {}

  private record ReadResponse(byte[] binData, int err) {}

  private record WriteResponse(int bytes, int err) {}

  private final DelugeSysExManager sysExManager;

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
          List<String> files = getStringListAttr(json, "files");
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
    Thread.ofVirtual()
        .name("DelugeFileDownloader-" + remotePath)
        .start(
            () -> {
              try {
                byte[] content = downloadFileBlocking(remotePath);
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

  /** Core sequential block downloader (blocks the calling Virtual Thread). */
  public byte[] downloadFileBlocking(String remotePath) throws Exception {
    // 1. Open remote file
    CompletableFuture<OpenResponse> openFuture = new CompletableFuture<>();
    String openRequest = String.format("{\"open\": {\"path\": \"%s\", \"write\": 0}}", remotePath);

    sysExManager.sendRequest(
        openRequest,
        (json, bin) -> {
          int fid = getIntAttr(json, "fid");
          int size = getIntAttr(json, "size");
          int err = getIntAttr(json, "err");
          openFuture.complete(new OpenResponse(fid, size, err));
        });

    OpenResponse openRes = openFuture.get(8, TimeUnit.SECONDS);
    if (openRes.err != 0 || openRes.fid == 0) {
      throw new IOException("Failed to open remote file: " + remotePath + ", err=" + openRes.err);
    }

    int fid = openRes.fid;
    int size = openRes.size;
    byte[] fileData = new byte[size];
    int bytesRead = 0;
    int chunkSize = 512; // Deluge maximum cluster read block size is 1024, 512 is safe and fast

    // 2. Loop block reads
    while (bytesRead < size) {
      int toRead = Math.min(chunkSize, size - bytesRead);
      CompletableFuture<ReadResponse> readFuture = new CompletableFuture<>();
      String readRequest =
          String.format(
              "{\"read\": {\"fid\": %d, \"addr\": %d, \"size\": %d}}", fid, bytesRead, toRead);

      final int currentOffset = bytesRead;
      sysExManager.sendRequest(
          readRequest,
          (json, bin) -> {
            int err = getIntAttr(json, "err");
            readFuture.complete(new ReadResponse(bin, err));
          });

      ReadResponse readRes = readFuture.get(8, TimeUnit.SECONDS);
      if (readRes.err != 0 || readRes.binData == null || readRes.binData.length == 0) {
        throw new IOException(
            "Failed to read remote block at offset " + currentOffset + ", err=" + readRes.err);
      }

      System.arraycopy(readRes.binData, 0, fileData, currentOffset, readRes.binData.length);
      bytesRead += readRes.binData.length;
    }

    // 3. Close remote file
    CompletableFuture<Integer> closeFuture = new CompletableFuture<>();
    String closeRequest = String.format("{\"close\": {\"fid\": %d}}", fid);
    sysExManager.sendRequest(
        closeRequest,
        (json, bin) -> {
          int err = getIntAttr(json, "err");
          closeFuture.complete(err);
        });
    closeFuture.get(5, TimeUnit.SECONDS);

    return fileData;
  }

  /** Core sequential block uploader (blocks the calling Virtual Thread). */
  public void uploadFileBlocking(String remotePath, byte[] content) throws Exception {
    // 1. Open remote file for writing (write: 1 creates/overwrites)
    CompletableFuture<OpenResponse> openFuture = new CompletableFuture<>();
    String openRequest = String.format("{\"open\": {\"path\": \"%s\", \"write\": 1}}", remotePath);

    sysExManager.sendRequest(
        openRequest,
        (json, bin) -> {
          int fid = getIntAttr(json, "fid");
          int err = getIntAttr(json, "err");
          openFuture.complete(new OpenResponse(fid, 0, err));
        });

    OpenResponse openRes = openFuture.get(8, TimeUnit.SECONDS);
    if (openRes.err != 0 || openRes.fid == 0) {
      throw new IOException(
          "Failed to open remote file for writing: " + remotePath + ", err=" + openRes.err);
    }

    int fid = openRes.fid;
    int bytesWritten = 0;
    int size = content.length;
    int chunkSize = 512;

    // 2. Loop block writes
    while (bytesWritten < size) {
      int toWrite = Math.min(chunkSize, size - bytesWritten);
      byte[] chunk = new byte[toWrite];
      System.arraycopy(content, bytesWritten, chunk, 0, toWrite);
      byte[] packedChunk = DelugeMidiPacker.pack8to7(chunk);

      CompletableFuture<WriteResponse> writeFuture = new CompletableFuture<>();
      String writeRequest = String.format("{\"write\": {\"fid\": %d, \"size\": %d}}", fid, toWrite);

      final int currentOffset = bytesWritten;
      sysExManager.sendRequest(
          writeRequest,
          packedChunk,
          (json, bin) -> {
            int err = getIntAttr(json, "err");
            int written = getIntAttr(json, "bytes");
            writeFuture.complete(new WriteResponse(written, err));
          });

      WriteResponse writeRes = writeFuture.get(8, TimeUnit.SECONDS);
      if (writeRes.err != 0 || writeRes.bytes == 0) {
        throw new IOException(
            "Failed to write remote block at offset " + currentOffset + ", err=" + writeRes.err);
      }

      bytesWritten += toWrite;
    }

    // 3. Close remote file
    CompletableFuture<Integer> closeFuture = new CompletableFuture<>();
    String closeRequest = String.format("{\"close\": {\"fid\": %d}}", fid);
    sysExManager.sendRequest(
        closeRequest,
        (json, bin) -> {
          int err = getIntAttr(json, "err");
          closeFuture.complete(err);
        });
    closeFuture.get(5, TimeUnit.SECONDS);
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
}
