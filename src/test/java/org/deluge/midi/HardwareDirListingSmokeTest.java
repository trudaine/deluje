package org.deluge.midi;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.deluge.shadow.midi.MidiIn;
import org.deluge.shadow.midi.MidiMsg;
import org.deluge.shadow.midi.MidiOut;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage against a real connected Deluge: {@code listDirectory} (folder-table view)
 * and {@code listSongs} (tree view) must both actually return the real /SONGS contents. Added after
 * a "no songs in the remote explorer" report that turned out to be a real hardware round trip that
 * now legitimately takes several seconds (paginated + retried over a lossy USB SysEx link) with no
 * loading indicator, making a slow-but-working listing look identical to a broken one. This test
 * only proves the data path itself is correct; it can't catch a missing UI loading indicator, which
 * is a separate, UI-level concern (see HardwareSidebarTab.loadRemoteFolder).
 */
@Tag("hardware")
class HardwareDirListingSmokeTest {

  @Test
  void listDirectoryAndListSongsBothReturnRealSongs() throws Exception {
    String[] outs = MidiOut.list();
    String[] ins = MidiIn.list();
    int oi = HardwareDspTapTest.findDeluge(outs);
    int ii = HardwareDspTapTest.findDeluge(ins);
    Assumptions.assumeTrue(oi >= 0 && ii >= 0, "no Deluge MIDI port");

    MidiOut out = new MidiOut();
    MidiIn in = new MidiIn();
    out.open(oi);
    in.open(ii);
    in.ignoreTypes(false, false, false);

    DelugeSysExManager mgr = new DelugeSysExManager();
    mgr.setMidiOut(out);

    final boolean[] running = {true};
    Thread pump =
        new Thread(
            () -> {
              MidiMsg m = new MidiMsg();
              java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
              boolean inSx = false;
              while (running[0]) {
                if (in.recv(m)) {
                  byte[] d = m.getData();
                  if (d == null || d.length == 0) continue;
                  for (byte b : d) {
                    if ((b & 0xFF) == 0xF0) {
                      acc.reset();
                      inSx = true;
                    }
                    if (inSx) acc.write(b);
                    if ((b & 0xFF) == 0xF7 && inSx) {
                      inSx = false;
                      mgr.handleIncomingSysEx(acc.toByteArray());
                    }
                  }
                } else {
                  try {
                    Thread.sleep(1);
                  } catch (InterruptedException e) {
                    return;
                  }
                }
              }
            },
            "hw-dir-listing-smoke-pump");
    pump.setDaemon(true);
    pump.start();

    try {
      mgr.negotiateSession("dir-listing-smoke").get(5, TimeUnit.SECONDS);
      DelugeFileSyncService service = new DelugeFileSyncService(mgr);

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
      List<RemoteFileEntry> dirEntries = dirFuture.get(30, TimeUnit.SECONDS);
      assertFalse(dirEntries.isEmpty(), "listDirectory(/SONGS) must return real songs, not none");

      CompletableFuture<List<String>> songsFuture = new CompletableFuture<>();
      service.listSongs(
          "/SONGS",
          new DelugeFileSyncService.FileListCallback() {
            @Override
            public void onSuccess(List<String> files) {
              songsFuture.complete(files);
            }

            @Override
            public void onFailure(Throwable t) {
              songsFuture.completeExceptionally(t);
            }
          });
      List<String> songs = songsFuture.get(30, TimeUnit.SECONDS);
      assertFalse(songs.isEmpty(), "listSongs(/SONGS) must return real songs, not none");
    } finally {
      running[0] = false;
      try {
        in.close();
      } catch (Throwable ignore) {
      }
      try {
        out.close();
      } catch (Throwable ignore) {
      }
    }
  }
}
