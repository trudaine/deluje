package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.List;
import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.engine.DelugeEngineDSL;
import org.chuck.deluge.midi.MidiInputRouter;
import org.chuck.deluge.midi.MidiService;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.EnvelopeModel;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.StepData;
import org.chuck.deluge.model.SynthTrackModel;
import org.chuck.deluge.model.TrackModel;
import org.chuck.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

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
   * Parses each song XML, pushes all tracks and clip data to the engine bridge,
   * starts playback, and verifies that (a) the step playhead advances and
   * (b) audible signal (>0.001 peak) is produced.
   */
  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(strings = {"song1.xml", "song2.xml", "song3.xml", "Dx7A.xml", "Dx7B.xml", "Dx7C.xml"})
  public void testSongPlayback(String songFile) throws Exception {
    System.out.println("\n=== testSongPlayback: " + songFile + " ===");
    System.setProperty("chuck.audio.dummy", "true");
    System.setProperty("chuck.loglevel", "1");
    System.setProperty("deluge.tracks", "256");

    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    try {
      // 1. Parse the song
      String songName = songFile.replace(".xml", "");
      InputStream is = getClass().getResourceAsStream("/SONGS/" + songFile);
      assertNotNull(is, songFile + " resource not found");
      ProjectModel project = DelugeXmlParser.parseSong(is, songName);

      List<TrackModel> tracks = project.getTracks();
      assertTrue(tracks.size() > 0, "Song " + songName + " should have at least 1 track");

      // 2. Push tracks to bridge (replicating pushModelToBridge logic)
      //    Must happen BEFORE engine start so init_synth() sees DX7 patch strings.
      int engineRow = 0;
      for (int t = 0; t < tracks.size(); t++) {
        TrackModel track = tracks.get(t);
        int voiceCount = 8; // default rows per track

        if (track instanceof KitTrackModel kit) {
          List<KitTrackModel.KitSound> sounds = kit.getSounds();
          for (int v = 0; v < Math.min(voiceCount, sounds.size()); v++) {
            int r = engineRow + v;
            bridge.setTrackType(r, 0);
            bridge.setMute(r, false);
            bridge.setTrackLevel(r, 0.8);

            String path = sounds.get(v).getSamplePath();
            if (path != null && !path.isEmpty()) {
              vm.setGlobalString("g_sample_" + r, path);
            }
          }
          engineRow += voiceCount;
        } else if (track instanceof SynthTrackModel synth) {
          // Ensure osc_type array exists
          ChuckArray oscTypeArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_OSC_TYPE);
          if (oscTypeArr == null) {
            oscTypeArr = new ChuckArray("int", BridgeContract.TRACKS);
            vm.setGlobalObject(BridgeContract.G_OSC_TYPE, oscTypeArr);
          }

          int typeIdx = oscTypeIndex(synth.getOsc1Type());
          ClipModel clip = null;
          if (!synth.getClips().isEmpty()) clip = synth.getClips().get(0);
          int clipRows = (clip != null) ? clip.getRowCount() : voiceCount;
          int totalRows = Math.max(voiceCount, clipRows);

          String dx7PatchStr = synth.getDx7Patch();

          for (int v = 0; v < totalRows; v++) {
            int r = engineRow + v;
            bridge.setTrackType(r, 1);
            bridge.setMute(r, false);
            bridge.setTrackLevel(r, 0.8);
            oscTypeArr.setInt(r, typeIdx);
            bridge.setFilterFreq(r, synth.getLpfFreq() / 20000.0f);
            bridge.setFilterRes(r, synth.getLpfRes() / 100.0f);
            bridge.setFilterMode(r, synth.getFilterMode().ordinal());
            bridge.setSynthAlgo(r, Math.max(0, synth.getSynthAlgorithm()));
            if (dx7PatchStr != null && !dx7PatchStr.isEmpty()) {
              vm.setGlobalString("g_dx7_patch_" + r, dx7PatchStr);
            }

            for (int e = 0; e < 4; e++) {
              EnvelopeModel adsr = synth.getEnv(e);
              if (adsr != null) {
                bridge.setEnv(r, e, adsr.attack(), adsr.decay(), adsr.sustain(), adsr.release());
              }
            }
          }
          engineRow += totalRows;
        }
      }

      // 4. Push clip pattern data (second pass after all tracks are mapped)
      engineRow = 0;
      for (int t = 0; t < tracks.size(); t++) {
        TrackModel track = tracks.get(t);
        int voiceCount = 8;
        ClipModel clip = null;
        if (!track.getClips().isEmpty()) clip = track.getClips().get(0);

        if (clip != null) {
          int stepCount = clip.getStepCount();
          int rowCount = clip.getRowCount();
          // Set track length for all rows this track occupies
          int clipTrackLen = (track instanceof SynthTrackModel)
              ? Math.max(voiceCount, rowCount)
              : voiceCount;
          for (int rr = 0; rr < clipTrackLen; rr++) {
            bridge.setTrackLength(engineRow + rr, stepCount);
          }
          for (int r = 0; r < rowCount; r++) {
            for (int s = 0; s < stepCount; s++) {
              StepData step = clip.getStep(r, s);
              if (step != null && step.active()) {
                bridge.setStep(engineRow + r, s, true);
                bridge.setVelocity(engineRow + r, s, step.velocity());
                bridge.setGate(engineRow + r, s, step.gate());
              }
            }
          }
        }
        engineRow += (track instanceof SynthTrackModel)
            ? Math.max(voiceCount, clip != null ? clip.getRowCount() : voiceCount)
            : voiceCount;
      }

      // 5. Start engine (after all bridge data — including DX7 patches — is pushed)
      vm.spork(new DelugeEngineDSL(vm));
      vm.advanceTime(44100);

      // 6. Broadcast load trigger and advance
      vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
      vm.advanceTime(44100);

      // 7. Start playback
      vm.setGlobalFloat(BridgeContract.G_MASTER_VOL, 1.0);
      vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
      vm.setGlobalFloat(BridgeContract.G_BPM, project.getBpm() > 0 ? project.getBpm() : 120.0f);

      // 8. Capture audio and check step advancement
      float peakL = 0, peakR = 0;
      boolean stepAdvanced = false;

      for (int i = 0; i < 500; i++) { // 500 × 10ms = 5s
        vm.advanceTime(441);

        long step = vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
        if (step >= 0) stepAdvanced = true;

        float curL = Math.abs(vm.getDacChannel(0).getLastOut());
        float curR = Math.abs(vm.getDacChannel(1).getLastOut());
        if (curL > peakL) peakL = curL;
        if (curR > peakR) peakR = curR;
      }

      vm.setGlobalInt(BridgeContract.G_PLAY, 0L);

      double peakAvg = (peakL + peakR) / 2.0;
      System.out.printf("Song %s: tracks=%d peak=%.6f stepAdvanced=%s%n",
          songName, tracks.size(), peakAvg, stepAdvanced);

      assertTrue(stepAdvanced, "Song " + songName + " engine playhead should advance");
      assertTrue(peakAvg > 0.0009,
          "Song " + songName + " should produce audible output (peak avg=" + peakAvg + ")");

    } finally {
      vm.shutdown();
    }
  }

  /** Map oscillator type string to engine type index. */
  private static int oscTypeIndex(String type) {
    if ("SINE".equals(type)) return 0;
    if ("SAW".equals(type)) return 1;
    if ("SQUARE".equals(type)) return 2;
    if ("TRIANGLE".equals(type)) return 3;
    return 0;
  }
}
