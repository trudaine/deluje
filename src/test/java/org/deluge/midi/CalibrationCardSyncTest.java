package org.deluge.midi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies the calibration push/pull orchestration against a fake in-memory card (no hardware). */
class CalibrationCardSyncTest {

  /** Records uploads in order and serves downloads from an in-memory map. */
  static final class FakeCard implements CardTransport {
    final LinkedHashMap<String, byte[]> uploads = new LinkedHashMap<>();
    final Map<String, byte[]> files = new LinkedHashMap<>();

    @Override
    public void upload(String remotePath, byte[] content) {
      uploads.put(remotePath, content);
      files.put(remotePath, content);
    }

    @Override
    public byte[] download(String remotePath) {
      return files.get(remotePath);
    }
  }

  @Test
  void pushUploadsPresetsToSynthsThenSongToSongs(@TempDir File dir) throws Exception {
    File song = new File(dir, "FM_CAL.XML");
    Files.write(song.toPath(), "song".getBytes());
    File p1 = new File(dir, "068 FM Bells 1.XML");
    File p2 = new File(dir, "050 FM Basic Bass.XML");
    Files.write(p1.toPath(), "bells".getBytes());
    Files.write(p2.toPath(), "bass".getBytes());

    FakeCard card = new FakeCard();
    CalibrationCardSync sync = new CalibrationCardSync(card);
    String songRemote = sync.pushCalibration(song, List.of(p1, p2));

    assertEquals("/SONGS/FM_CAL.XML", songRemote);

    List<String> order = new ArrayList<>(card.uploads.keySet());
    // Presets first (so by-name bindings resolve), each under /SYNTHS; song last under /SONGS.
    assertEquals(
        List.of("/SYNTHS/068 FM Bells 1.XML", "/SYNTHS/050 FM Basic Bass.XML", "/SONGS/FM_CAL.XML"),
        order);
    assertArrayEquals("bells".getBytes(), card.uploads.get("/SYNTHS/068 FM Bells 1.XML"));
    assertArrayEquals("song".getBytes(), card.uploads.get("/SONGS/FM_CAL.XML"));
  }

  @Test
  void pushSongUploadsOnlyTheSong(@TempDir File dir) throws Exception {
    File song = new File(dir, "FM_CAL.XML");
    Files.write(song.toPath(), "song".getBytes());
    FakeCard card = new FakeCard();
    String remote = new CalibrationCardSync(card).pushSong(song);
    assertEquals("/SONGS/FM_CAL.XML", remote);
    assertEquals(List.of("/SONGS/FM_CAL.XML"), new ArrayList<>(card.uploads.keySet()));
  }

  @Test
  void pullWritesSamplesFileToLocalDest(@TempDir File dir) throws Exception {
    FakeCard card = new FakeCard();
    card.files.put("/SAMPLES/output_000.wav", "WAVDATA".getBytes());

    CalibrationCardSync sync = new CalibrationCardSync(card);
    File dest = new File(new File(dir, "FM_CAL"), "output_000.wav");
    sync.pullRecording("output_000.wav", dest);

    assertTrue(dest.isFile());
    assertArrayEquals("WAVDATA".getBytes(), Files.readAllBytes(dest.toPath()));
  }
}
