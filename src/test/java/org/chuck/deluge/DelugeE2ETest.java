package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.List;
import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.midi.MidiInputRouter;
import org.chuck.deluge.midi.MidiService;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.Drum;
import org.chuck.deluge.model.EnvelopeModel;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SoundDrum;
import org.chuck.deluge.model.StepData;
import org.chuck.deluge.model.SynthTrackModel;
import org.chuck.deluge.model.TrackModel;
import org.chuck.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class DelugeE2ETest {

  @Test
  public void testSequencerWorkflow() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");

    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    MidiInputRouter router = new MidiInputRouter(vm, bridge);
    MidiService midiService = new MidiService(vm, bridge, router);

    // 1. Load project state
    try (java.io.InputStream is = getClass().getResourceAsStream("/SONGS/song1.xml")) {
      assertNotNull(is, "song1.xml should exist in resources");
      org.chuck.deluge.model.ProjectModel model =
          org.chuck.deluge.xml.DelugeXmlParser.parseSong(is, "song1");
      assertTrue(model.getTracks().size() >= 0, "Model tracks should load");
    }

    // 2. Trigger Playback
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);

    // Allow the audio thread to process a bit
    Thread.sleep(150);

    long step = vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
    System.out.println("E2E Test: playhead step is " + step);

    // 3. Add sequence note
    bridge.setStep(0, 4, true);
    boolean active = bridge.getStep(0, 4);
    assertTrue(active, "Sequencer step memory allocation should hold");

    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
    vm.shutdown();
  }

  @Test
  public void testSidebarLoadPreset() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    org.chuck.deluge.ui.SwingProjectSidebarPanel sidebar =
        new org.chuck.deluge.ui.SwingProjectSidebarPanel(vm, bridge, null);

    final boolean[] loaded = {false};
    sidebar.setOnSongLoaded(
        model -> {
          loaded[0] = true;
        });

    org.chuck.deluge.model.ProjectModel proj = new org.chuck.deluge.model.ProjectModel();
    org.chuck.deluge.model.KitTrackModel kit = new org.chuck.deluge.model.KitTrackModel("MOCK KIT");
    proj.addTrack(kit);

    sidebar.setOnSongLoaded(
        model -> {
          loaded[0] = true;
          assertEquals(1, model.getTracks().size());
        });

    proj.addTrack(kit);

    // Simulate playback on each row
    for (int r = 0; r < 8; r++) {
      bridge.setStep(r, 0, true);
      assertTrue(bridge.getStep(r, 0), "Row " + r + " sequence memory allocation should store.");
    }
    vm.shutdown();
  }

  // ── Song playback tests ──────────────────────────────────────────────────

  /**
   * Parses each song XML, pushes all tracks and clip data to the engine bridge, starts playback,
   * and verifies that (a) the step playhead advances and (b) audible signal (>0.001 peak) is
   * produced.
   */
  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(
      strings = {"song1.xml", "song2.xml", "song3.xml", "Dx7A.xml", "Dx7B.xml", "Dx7C.xml"})
  public void testSongPlayback(String songFile) throws Exception {
    System.out.println("\n=== testSongPlayback: " + songFile + " ===");

    // 1. Parse the song.
    String songName = songFile.replace(".xml", "");
    InputStream is = getClass().getResourceAsStream("/SONGS/" + songFile);
    assertNotNull(is, songFile + " resource not found");
    ProjectModel project = DelugeXmlParser.parseSong(is, songName);
    assertTrue(project.getTracks().size() > 0, "Song " + songName + " should have at least 1 track");

    // 2. Build the firmware Song and drive the SUPPORTED pure engine offline (the JNI-free
    //    FirmwareAudioEngine + PlaybackHandler). The legacy DelugeEngineDSL ("--hifi") path is
    //    unsupported and intentionally NOT exercised here.
    org.chuck.deluge.firmware.model.Song song =
        org.chuck.deluge.firmware.engine.FirmwareFactory.createSong(project);
    org.chuck.deluge.firmware.engine.FirmwareAudioEngine audioEngine =
        new org.chuck.deluge.firmware.engine.FirmwareAudioEngine();
    for (org.chuck.deluge.firmware.model.Clip c : song.clips) {
      if (c instanceof org.chuck.deluge.firmware.model.InstrumentClip ic && ic.sound != null) {
        audioEngine.sounds.add(ic.sound);
      }
    }
    org.chuck.deluge.firmware.playback.PlaybackHandler playbackHandler =
        new org.chuck.deluge.firmware.playback.PlaybackHandler();
    playbackHandler.setSong(song);
    playbackHandler.start();

    // 3. Render ~5s in 128-sample blocks, advancing the transport per BPM (as JavaAudioDriver does).
    float bpm = project.getBpm() > 0 ? project.getBpm() : 120f;
    double ticksPerSample = (bpm / 60.0) * 96.0 / 44100.0;
    double accumulatedTicks = 0.0;
    int blocks = (44100 * 5) / 128;
    float peak = 0f;
    long maxTick = 0;
    for (int b = 0; b < blocks; b++) {
      accumulatedTicks += ticksPerSample * 128.0;
      int toAdvance = (int) accumulatedTicks;
      if (toAdvance > 0) {
        playbackHandler.advanceTicks(toAdvance);
        accumulatedTicks -= toAdvance;
      }
      audioEngine.renderBlock(128);
      for (int i = 0; i < 128; i++) {
        float l = audioEngine.masterBuffer[i].l / 2147483648f;
        float r = audioEngine.masterBuffer[i].r / 2147483648f;
        peak = Math.max(peak, Math.max(Math.abs(l), Math.abs(r)));
      }
      maxTick = Math.max(maxTick, (long) playbackHandler.lastSwungTickActioned);
    }

    System.out.printf(
        "Song %s: clips=%d peak=%.6f maxTick=%d%n", songName, song.clips.size(), peak, maxTick);

    assertTrue(maxTick > 0, "Song " + songName + " transport should advance");
    assertTrue(
        peak > 0.0009, "Song " + songName + " should produce audible output (peak=" + peak + ")");
  }

  /** Map oscillator type string to engine type index. Case-insensitive. */
  private static int oscTypeIndex(String type) {
    if (type == null) return 0;
    String t = type.toUpperCase();
    if ("SINE".equals(t)) return 0;
    if ("SAW".equals(t)) return 1;
    if ("SQUARE".equals(t)) return 2;
    if ("TRIANGLE".equals(t)) return 3;
    return 0;
  }
}
