package org.deluge.midi;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

public class DelugeFileSyncServiceTest {

  @Test
  public void testJsonParsingUtilities() {
    // 1. Test getIntAttr
    String json1 = "{\"fid\": 2, \"size\": 1420, \"err\": 0}";
    assertEquals(2, parseGetIntAttr(json1, "fid"));
    assertEquals(1420, parseGetIntAttr(json1, "size"));
    assertEquals(0, parseGetIntAttr(json1, "err"));
    assertEquals(-1, parseGetIntAttr(json1, "missing"));

    String json2 = "{\"err\":-5}";
    assertEquals(-5, parseGetIntAttr(json2, "err"));
  }

  @Test
  public void testListSongsSuccess() throws Exception {
    DelugeSysExManager mockManager =
        new DelugeSysExManager() {
          @Override
          public void sendRequest(String jsonPayload, SysExCallback callback) {
            assertTrue(jsonPayload.contains("\"dir\""));
            assertTrue(jsonPayload.contains("\"/SONGS\""));
            assertTrue(jsonPayload.contains("\"offset\""), "paginated request carries an offset");
            // Simulate the current firmware ^dir reply (list of {name,attr} entries). Two files,
            // fewer than MAX_DIR_LINES, so the pager treats this as the final page and stops.
            callback.onResponse(
                "{\"^dir\": {\"list\": ["
                    + "{\"name\":\"S1.XML\",\"size\":1,\"date\":0,\"time\":0,\"attr\":32},"
                    + "{\"name\":\"S2.XML\",\"size\":1,\"date\":0,\"time\":0,\"attr\":32}"
                    + "], \"err\": 0}}",
                null);
          }
        };

    DelugeFileSyncService service = new DelugeFileSyncService(mockManager);
    CompletableFuture<List<String>> future = new CompletableFuture<>();

    service.listSongs(
        "/SONGS",
        new DelugeFileSyncService.FileListCallback() {
          @Override
          public void onSuccess(List<String> files) {
            future.complete(files);
          }

          @Override
          public void onFailure(Throwable t) {
            future.completeExceptionally(t);
          }
        });

    List<String> result = future.get();
    assertEquals(2, result.size());
    assertEquals("S1.XML", result.get(0));
    assertEquals("S2.XML", result.get(1));
  }

  @Test
  public void testDownloadFileBlocking() throws Exception {
    // The raw binary we want to receive: 10 bytes
    byte[] expectedData = new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    byte[] packedData = DelugeMidiPacker.pack8to7(expectedData);

    DelugeSysExManager mockManager =
        new DelugeSysExManager() {
          @Override
          public void sendRequest(String jsonPayload, SysExCallback callback) {
            if (jsonPayload.contains("\"open\"")) {
              // Reply open: fid=5, size=10, err=0
              callback.onResponse("{\"^open\": {\"fid\": 5, \"size\": 10, \"err\": 0}}", null);
            } else if (jsonPayload.contains("\"read\"")) {
              assertTrue(jsonPayload.contains("\"fid\": 5"));
              // Reply read block with unpacked binary (mocking the manager's decoders)
              callback.onResponse(
                  "{\"^read\": {\"fid\": 5, \"size\": 10, \"err\": 0}}", expectedData);
            } else if (jsonPayload.contains("\"close\"")) {
              assertTrue(jsonPayload.contains("\"fid\": 5"));
              callback.onResponse("{\"^close\": {\"fid\": 5, \"err\": 0}}", null);
            } else {
              fail("Unexpected request: " + jsonPayload);
            }
          }
        };

    DelugeFileSyncService service = new DelugeFileSyncService(mockManager);

    // We must run blocking operations on a Virtual Thread (like in production)
    CompletableFuture<byte[]> future = new CompletableFuture<>();
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                byte[] result = service.downloadFileBlocking("/SONGS/S1.XML");
                future.complete(result);
              } catch (Exception e) {
                future.completeExceptionally(e);
              }
            });

    byte[] downloaded = future.get();
    assertArrayEquals(expectedData, downloaded);
  }

  @Test
  public void testUploadFileBlocking() throws Exception {
    byte[] uploadContent = new byte[] {10, 20, 30, 40, 50};
    byte[] expectedPacked = DelugeMidiPacker.pack8to7(uploadContent);

    AtomicReference<byte[]> capturedPayload = new AtomicReference<>();

    DelugeSysExManager mockManager =
        new DelugeSysExManager() {
          @Override
          public void sendRequest(String jsonPayload, SysExCallback callback) {
            if (jsonPayload.contains("\"open\"")) {
              callback.onResponse("{\"^open\": {\"fid\": 6, \"err\": 0}}", null);
            } else if (jsonPayload.contains("\"close\"")) {
              callback.onResponse("{\"^close\": {\"fid\": 6, \"err\": 0}}", null);
            } else if (jsonPayload.contains("\"utime\"")) {
              assertTrue(jsonPayload.contains("\"path\": \"/SONGS/NEW.XML\""));
              callback.onResponse("{\"^utime\": {\"err\": 0}}", null);
            } else {
              fail("Unexpected JSON-only request: " + jsonPayload);
            }
          }

          @Override
          public void sendRequest(
              String jsonPayload, byte[] binaryPayload, SysExCallback callback) {
            assertTrue(jsonPayload.contains("\"write\""));
            assertTrue(jsonPayload.contains("\"fid\": 6"));
            capturedPayload.set(binaryPayload);
            callback.onResponse("{\"^write\": {\"fid\": 6, \"bytes\": 5, \"err\": 0}}", null);
          }
        };

    DelugeFileSyncService service = new DelugeFileSyncService(mockManager);
    CompletableFuture<Void> future = new CompletableFuture<>();

    Thread.ofVirtual()
        .start(
            () -> {
              try {
                service.uploadFileBlocking("/SONGS/NEW.XML", uploadContent);
                future.complete(null);
              } catch (Exception e) {
                future.completeExceptionally(e);
              }
            });

    future.get(); // wait for completion
    assertArrayEquals(expectedPacked, capturedPayload.get());
  }

  // Reflective or direct helpers to test private static parser methods
  private int parseGetIntAttr(String json, String attrName) {
    try {
      java.lang.reflect.Method m =
          DelugeFileSyncService.class.getDeclaredMethod("getIntAttr", String.class, String.class);
      m.setAccessible(true);
      return (Integer) m.invoke(null, json, attrName);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void testFatDateTime() {
    // 1. Test encoding/decoding parity (FAT time has 2-second resolution)
    long epochMillis = 1781222400000L; // some specific future epoch
    int[] fat = DelugeFileSyncService.encodeFatDateTime(epochMillis);
    assertTrue(fat[0] > 0);
    assertTrue(fat[1] >= 0);

    long decoded = DelugeFileSyncService.decodeFatDateTime(fat[0], fat[1]);
    // The difference must be <= 2000ms due to FAT's 2-second resolution
    assertTrue(Math.abs(epochMillis - decoded) <= 2000);
  }

  @Test
  public void testParseDirectoryEntries() {
    String json =
        "{\"^dir\": {\"list\": ["
            + "{\"name\":\"SONGS\",\"size\":0,\"date\":23685,\"time\":14336,\"attr\":16}," // 0x10
            // is
            // directory
            + "{\"name\":\"TRACK1.XML\",\"size\":45210,\"date\":23685,\"time\":14352,\"attr\":32}" // 0x20 is archive/file
            + "], \"err\": 0}}";
    List<RemoteFileEntry> entries = DelugeFileSyncService.parseDirectoryEntries(json);
    assertEquals(2, entries.size());

    RemoteFileEntry e1 = entries.get(0);
    assertEquals("SONGS", e1.name());
    assertEquals(0, e1.size());
    assertTrue(e1.isDirectory());
    assertFalse(e1.isReadOnly());

    RemoteFileEntry e2 = entries.get(1);
    assertEquals("TRACK1.XML", e2.name());
    assertEquals(45210, e2.size());
    assertFalse(e2.isDirectory());
    assertFalse(e2.isReadOnly());
    assertTrue(e2.lastModifiedMillis() > 0);
  }

  @Test
  public void testNewFileSystemOperations() throws Exception {
    DelugeSysExManager mockManager =
        new DelugeSysExManager() {
          @Override
          public void sendRequest(String jsonPayload, SysExCallback callback) {
            if (jsonPayload.contains("\"mkdir\"")) {
              assertTrue(jsonPayload.contains("\"path\": \"/SONGS/NEWDIR\""));
              callback.onResponse("{\"^mkdir\": {\"err\": 0}}", null);
            } else if (jsonPayload.contains("\"delete\"")) {
              assertTrue(jsonPayload.contains("\"path\": \"/SONGS/OLD.XML\""));
              callback.onResponse("{\"^delete\": {\"err\": 0}}", null);
            } else if (jsonPayload.contains("\"rename\"")) {
              assertTrue(jsonPayload.contains("\"from\": \"/SONGS/A.XML\""));
              assertTrue(jsonPayload.contains("\"to\": \"/SONGS/B.XML\""));
              callback.onResponse("{\"^rename\": {\"err\": 0}}", null);
            } else if (jsonPayload.contains("\"copy\"")) {
              assertTrue(jsonPayload.contains("\"from\": \"/SONGS/A.XML\""));
              assertTrue(jsonPayload.contains("\"to\": \"/SONGS/C.XML\""));
              callback.onResponse("{\"^copy\": {\"err\": 0}}", null);
            } else if (jsonPayload.contains("\"move\"")) {
              assertTrue(jsonPayload.contains("\"from\": \"/SONGS/A.XML\""));
              assertTrue(jsonPayload.contains("\"to\": \"/SONGS/D.XML\""));
              callback.onResponse("{\"^move\": {\"err\": 0}}", null);
            } else if (jsonPayload.contains("\"utime\"")) {
              assertTrue(jsonPayload.contains("\"path\": \"/SONGS/A.XML\""));
              callback.onResponse("{\"^utime\": {\"err\": 0}}", null);
            } else if (jsonPayload.contains("\"dir\"")) {
              assertTrue(jsonPayload.contains("\"path\": \"/SONGS\""));
              callback.onResponse(
                  "{\"^dir\": {\"list\": [{\"name\":\"S1.XML\",\"size\":123,\"date\":100,\"time\":100,\"attr\":32}], \"err\": 0}}",
                  null);
            } else {
              fail("Unexpected request: " + jsonPayload);
            }
          }
        };

    DelugeFileSyncService service = new DelugeFileSyncService(mockManager);

    // Test listDirectory
    CompletableFuture<List<RemoteFileEntry>> dirFuture = new CompletableFuture<>();
    service.listDirectory(
        "/SONGS",
        new DelugeFileSyncService.DirectoryListCallback() {
          @Override
          public void onSuccess(List<RemoteFileEntry> entries) {
            dirFuture.complete(entries);
          }

          @Override
          public void onFailure(Throwable t) {
            dirFuture.completeExceptionally(t);
          }
        });
    List<RemoteFileEntry> dirResult = dirFuture.get();
    assertEquals(1, dirResult.size());
    assertEquals("S1.XML", dirResult.get(0).name());

    // Run blocking calls on virtual thread
    CompletableFuture<Void> opsFuture = new CompletableFuture<>();
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                assertEquals(
                    0,
                    service.createDirectoryBlocking("/SONGS/NEWDIR", System.currentTimeMillis()));
                assertEquals(0, service.deleteBlocking("/SONGS/OLD.XML"));
                assertEquals(0, service.renameBlocking("/SONGS/A.XML", "/SONGS/B.XML"));
                assertEquals(
                    0,
                    service.copyBlocking(
                        "/SONGS/A.XML", "/SONGS/C.XML", System.currentTimeMillis()));
                assertEquals(
                    0,
                    service.moveBlocking(
                        "/SONGS/A.XML", "/SONGS/D.XML", System.currentTimeMillis()));
                assertEquals(
                    0, service.setTimestampBlocking("/SONGS/A.XML", System.currentTimeMillis()));
                opsFuture.complete(null);
              } catch (Exception e) {
                opsFuture.completeExceptionally(e);
              }
            });

    opsFuture.get(); // block and verify no exceptions
  }
}
