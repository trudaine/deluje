package org.deluge.midi;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for a real-hardware bug: overlapping logical operations (e.g. a background
 * dir refresh racing a user-triggered download -- concretely, HardwareSidebarTab's REFRESH button
 * fires refreshHardwareTree() and loadRemoteFolder() back-to-back with no coordination) caused
 * cascading "No reply after N attempts" failures across unrelated requests. DelugeSysExManager
 * dispatches replies by a single shared, narrow seq counter keyed in one map; interleaved retries
 * from two unrelated operations can land on the same seq and silently overwrite each other's
 * pending callback, orphaning one request until it times out.
 *
 * <p>DelugeFileSyncService now serializes every logical hardware operation through a single lock,
 * so at most one is ever in flight at a time regardless of what the UI does concurrently.
 */
public class DelugeFileSyncConcurrencySafetyTest {

  @Test
  public void testConcurrentDirListingsNeverOverlapOnTheWire() throws Exception {
    AtomicInteger active = new AtomicInteger(0);
    AtomicInteger maxObserved = new AtomicInteger(0);

    DelugeSysExManager mockManager =
        new DelugeSysExManager() {
          @Override
          public void sendRequest(String jsonPayload, SysExCallback callback) {
            int cur = active.incrementAndGet();
            maxObserved.updateAndGet(prev -> Math.max(prev, cur));
            try {
              // Simulate a real hardware round-trip: long enough that two truly concurrent
              // logical operations would overlap here if nothing serialized them.
              Thread.sleep(60);
            } catch (InterruptedException ignored) {
            }
            active.decrementAndGet();
            callback.onResponse(
                "{\"^dir\": {\"list\": [{\"name\":\"S1.XML\",\"size\":1,\"date\":0,\"time\":0,\"attr\":32}], \"err\": 0}}",
                null);
          }
        };

    DelugeFileSyncService service = new DelugeFileSyncService(mockManager);
    CountDownLatch bothDone = new CountDownLatch(2);

    // Two concurrent dir listings (not a download vs. a listing): downloadFileBlocking/
    // uploadFileBlocking wait out a 2200ms OLED-quiet window before their first real request,
    // which would let a same-instant listSongs() finish first regardless of locking and mask the
    // race. Two listSongs() calls for different paths have no such asymmetry and directly match
    // the real trigger (HardwareSidebarTab's REFRESH button firing two dir refreshes at once).
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                CompletableFuture<List<String>> f = new CompletableFuture<>();
                service.listSongs(
                    "/SYNTHS",
                    new DelugeFileSyncService.FileListCallback() {
                      @Override
                      public void onSuccess(List<String> files) {
                        f.complete(files);
                      }

                      @Override
                      public void onFailure(Throwable t) {
                        f.completeExceptionally(t);
                      }
                    });
                f.get(10, TimeUnit.SECONDS);
              } catch (Exception ignored) {
              } finally {
                bothDone.countDown();
              }
            });

    Thread.ofVirtual()
        .start(
            () -> {
              try {
                CompletableFuture<List<String>> f = new CompletableFuture<>();
                service.listSongs(
                    "/SONGS",
                    new DelugeFileSyncService.FileListCallback() {
                      @Override
                      public void onSuccess(List<String> files) {
                        f.complete(files);
                      }

                      @Override
                      public void onFailure(Throwable t) {
                        f.completeExceptionally(t);
                      }
                    });
                f.get(10, TimeUnit.SECONDS);
              } catch (Exception ignored) {
              } finally {
                bothDone.countDown();
              }
            });

    assertTrue(bothDone.await(15, TimeUnit.SECONDS), "both operations must finish");
    assertEquals(
        1,
        maxObserved.get(),
        "at most one logical dir listing may touch the wire at once -- overlap is what causes seq"
            + " collisions and cascading timeouts on real hardware");
  }

  @Test
  public void testListDirectoryRetriesInsteadOfFailingOnFirstDrop() throws Exception {
    AtomicInteger callCount = new AtomicInteger(0);
    DelugeSysExManager mockManager =
        new DelugeSysExManager() {
          @Override
          public void sendRequest(String jsonPayload, SysExCallback callback) {
            if (callCount.incrementAndGet() == 1) {
              // Simulate the first attempt being dropped in transit: never reply at all.
              return;
            }
            callback.onResponse(
                "{\"^dir\": {\"list\": [{\"name\":\"S1.XML\",\"size\":1,\"date\":0,\"time\":0,\"attr\":32}], \"err\": 0}}",
                null);
          }
        };

    DelugeFileSyncService service = new DelugeFileSyncService(mockManager);
    CompletableFuture<List<RemoteFileEntry>> future = new CompletableFuture<>();
    service.listDirectory(
        "/SONGS",
        new DelugeFileSyncService.DirectoryListCallback() {
          @Override
          public void onSuccess(List<RemoteFileEntry> entries) {
            future.complete(entries);
          }

          @Override
          public void onFailure(Throwable t) {
            future.completeExceptionally(t);
          }
        });

    List<RemoteFileEntry> result = future.get(10, TimeUnit.SECONDS);
    assertEquals(1, result.size());
    assertEquals("S1.XML", result.get(0).name());
    assertTrue(callCount.get() >= 2, "must have retried after the first dropped attempt");
  }
}
