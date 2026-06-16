package org.chuck.deluge.midi;

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

    // 2. Test getStringListAttr
    String json3 = "{\"^dir\": {\"files\": [\"SONG001.XML\", \"PRESET_BASS.XML\"], \"err\": 0}}";
    List<String> files = parseGetStringListAttr(json3, "files");
    assertEquals(2, files.size());
    assertEquals("SONG001.XML", files.get(0));
    assertEquals("PRESET_BASS.XML", files.get(1));

    String json4 = "{\"files\": []}";
    assertTrue(parseGetStringListAttr(json4, "files").isEmpty());
  }

  @Test
  public void testListSongsSuccess() throws Exception {
    DelugeSysExManager mockManager =
        new DelugeSysExManager() {
          @Override
          public void sendRequest(String jsonPayload, SysExCallback callback) {
            assertTrue(jsonPayload.contains("\"dir\""));
            assertTrue(jsonPayload.contains("\"/SONGS\""));
            // Simulate remote response
            callback.onResponse(
                "{\"^dir\": {\"files\": [\"S1.XML\", \"S2.XML\"], \"err\": 0}}", null);
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

  @SuppressWarnings("unchecked")
  private List<String> parseGetStringListAttr(String json, String attrName) {
    try {
      java.lang.reflect.Method m =
          DelugeFileSyncService.class.getDeclaredMethod(
              "getStringListAttr", String.class, String.class);
      m.setAccessible(true);
      return (List<String>) m.invoke(null, json, attrName);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
