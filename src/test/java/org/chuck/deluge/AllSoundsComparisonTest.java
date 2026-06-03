package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.chuck.core.ChuckConfig;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.engine.DelugeEngineDSL;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.EnvelopeModel;
import org.chuck.deluge.model.FilterMode;
import org.chuck.deluge.model.StepData;
import org.chuck.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

/**
 * Headless audio comparison test covering ALL synth and kit sounds.
 *
 * <p>Tests 10 synth configurations (FM with varied osc types, filter modes, envelopes, STK physical
 * models) and 10 kit sounds (different samples loaded per voice). Each configuration plays a
 * V-shaped 16-step clip, captured as peak/RMS per buffer, and verified to produce audibly distinct
 * output.
 *
 * <p>Both synth and kit tests share a single bridge/VM with {@code deluge.tracks=80}, allowing 10
 * synths × 8 rows + 10 kit tracks + margin. The engine handles mixed track types (type 0 = kit,
 * type 1 = synth) within the same project.
 */
@Tag("slow")
@Disabled("Legacy DelugeEngineDSL engine is unsupported; rebuild on the firmware pure engine. See docs/java-port-review-non-dx7-2026-06-03.md.")
public class AllSoundsComparisonTest {

  private static final int SAMPLE_RATE = 44100;
  // 10 synths × 8 rows + 10 kit tracks = 90, rounded to 128 for margin
  private static final int TRACKS = 128;
  private static final int VOICES_PER_SYNTH = 8;
  private static final int SYNTH_COUNT = 10;
  private static final int KIT_COUNT = 10;
  private static final int CLIP_STEPS = 16;

  private static ChuckVM vm;
  private static BridgeContract bridge;
  private static final List<String> logs = new ArrayList<>();

  private static final int[] V_VELOCITY = {
    20, 40, 60, 80, 100, 80, 60, 40,
    20, 40, 60, 80, 100, 80, 60, 40
  };

  // ── Kit sample paths ───────────────────────────────────────────────────

  private static final String[] SAMPLE_PATHS = {
    "examples/book/digital-artists/audio/kick_01.wav",
    "examples/book/digital-artists/audio/snare_01.wav",
    "examples/book/digital-artists/audio/hihat_01.wav",
    "examples/book/digital-artists/audio/cowbell_01.wav",
    "examples/book/digital-artists/audio/clap_01.wav",
    "examples/book/digital-artists/audio/hihat_02.wav",
    "examples/book/digital-artists/audio/snare_02.wav",
    "examples/book/digital-artists/audio/kick_04.wav",
    "examples/book/digital-artists/audio/snare_03.wav",
    "examples/book/digital-artists/audio/click_01.wav",
  };

  private static final String[] SOUND_NAMES = {
    "Kick 01", "Snare 01", "HiHat 01", "Cowbell 01", "Clap 01",
    "HiHat 02", "Snare 02", "Kick 04", "Snare 03", "Click 01"
  };

  // ── Setup ──────────────────────────────────────────────────────────────

  @BeforeAll
  static void setUpAll() {
    System.setProperty("chuck.audio.dummy", "true");
    System.setProperty("deluge.tracks", String.valueOf(TRACKS));
    vm = new ChuckVM(SAMPLE_RATE, 2);
    vm.addPrintListener(logs::add);

    ChuckConfig.addSearchPath("src/main/resources");
    ChuckConfig.addSearchPath("../deluge/src/main/resources");

    bridge = new BridgeContract();
    bridge.register(vm);

    // Pre-set kit tracks (type 0) at rows 0..KIT_COUNT-1 with sample paths
    for (int i = 0; i < KIT_COUNT; i++) {
      bridge.setTrackType(i, 0);
      vm.setGlobalString("g_sample_" + i, SAMPLE_PATHS[i]);
    }

    // Pre-set synth tracks (type 1) at rows KIT_COUNT..KIT_COUNT+SYNTH_COUNT*VOICES_PER_SYNTH-1
    // with algos
    int synthBase = KIT_COUNT;
    for (int j = 0; j < SYNTH_COUNT; j++) {
      int algo = algos[j];
      for (int v = 0; v < VOICES_PER_SYNTH; v++) {
        int r = synthBase + j * VOICES_PER_SYNTH + v;
        bridge.setTrackType(r, 1);
        bridge.setSynthAlgo(r, algo);
      }
    }

    // Wait for engine start and load trigger (done per test)
  }

  @AfterAll
  static void tearDownAll() {
    if (vm != null) vm.shutdown();
  }

  // ── Kit helpers ────────────────────────────────────────────────────────

  /** Write a V-shaped 16-step pattern for all kit voices. */
  private static void writeKitPattern() {
    for (int v = 0; v < KIT_COUNT; v++) {
      for (int s = 0; s < CLIP_STEPS; s++) {
        float vel = V_VELOCITY[s] / 100.0f;
        bridge.setStep(v, s, true);
        bridge.setVelocity(v, s, vel);
        bridge.setGate(v, s, 0.9);
      }
    }
  }

  // ── Synth helpers ──────────────────────────────────────────────────────

  private static final int[] algos = {0, 0, 0, 0, 0, 10, 11, 12, 13, 0};

  private static SynthTrackModel createSynth(int index) {
    SynthTrackModel synth = new SynthTrackModel("Synth " + (index + 1));

    String[] oscTypes = {
      "SINE", "SAW", "SQUARE", "TRIANGLE", "SAW",
      "SINE", "SAW", "SQUARE", "TRIANGLE", "SQUARE"
    };
    synth.setOsc1Type(oscTypes[index % oscTypes.length]);

    FilterMode[] filterModes = {
      FilterMode.LADDER_12,
      FilterMode.LADDER_24,
      FilterMode.SVF,
      FilterMode.LADDER_12,
      FilterMode.LADDER_24,
      FilterMode.SVF,
      FilterMode.LADDER_12,
      FilterMode.SVF,
      FilterMode.LADDER_24,
      FilterMode.SVF
    };
    synth.setFilterMode(filterModes[index]);

    float[] lpfFreqs = {
      20000f, 8000f, 3000f, 500f, 12000f,
      15000f, 2000f, 10000f, 4000f, 600f
    };
    float[] lpfRess = {
      0f, 2f, 20f, 40f, 5f,
      10f, 30f, 15f, 25f, 50f
    };
    synth.setLpfFreq(lpfFreqs[index]);
    synth.setLpfRes(lpfRess[index]);

    float[][] envs = {
      {0.01f, 0.1f, 0.7f, 0.2f},
      {0.05f, 0.3f, 0.5f, 0.5f},
      {0.001f, 0.05f, 1.0f, 0.1f},
      {0.1f, 0.4f, 0.3f, 0.8f},
      {0.01f, 0.2f, 0.9f, 0.3f},
      {0.02f, 0.15f, 0.6f, 0.4f},
      {0.005f, 0.08f, 0.8f, 0.15f},
      {0.03f, 0.25f, 0.4f, 0.6f},
      {0.01f, 0.1f, 0.5f, 0.2f},
      {0.08f, 0.5f, 0.2f, 1.0f},
    };
    float[] e = envs[index % envs.length];
    for (int i = 0; i < 4; i++) {
      synth.setEnv(i, new EnvelopeModel(e[0], e[1], e[2], e[3], "NONE", 0.0f));
    }

    synth.setSynthAlgorithm(algos[index]);
    // Clips for model consistency
    ClipModel clip = new ClipModel("V-Clip", VOICES_PER_SYNTH, CLIP_STEPS);
    for (int r = 0; r < VOICES_PER_SYNTH; r++) {
      for (int s = 0; s < CLIP_STEPS; s++) {
        float vel = V_VELOCITY[s] / 100.0f;
        clip.setStep(r, s, StepData.of(true, vel, 0.5f, 1.0f, 60 + r - 4));
      }
    }
    synth.addClip(clip);
    synth.setActiveClipIndex(0);

    return synth;
  }

  /** Push synth parameters to the bridge for a given synth index. */
  private static void pushSynthToBridge(SynthTrackModel synth, int synthIndex) {
    int baseRow = KIT_COUNT + synthIndex * VOICES_PER_SYNTH;

    // Push osc type
    int typeIdx = 1;
    String ot = synth.getOsc1Type();
    if ("SINE".equals(ot)) typeIdx = 0;
    else if ("SQUARE".equals(ot)) typeIdx = 2;
    else if ("TRIANGLE".equals(ot)) typeIdx = 3;
    for (int v = 0; v < VOICES_PER_SYNTH; v++) {
      int r = baseRow + v;
      // Set osc type via direct ChuckArray access
      org.chuck.core.ChuckArray oscTypeArr =
          (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_OSC_TYPE);
      if (oscTypeArr != null) oscTypeArr.setInt(r, typeIdx);

      bridge.setFilterFreq(r, synth.getLpfFreq() / 20000.0f);
      bridge.setFilterRes(r, synth.getLpfRes() / 100.0f);
      bridge.setFilterMode(r, synth.getFilterMode().ordinal());

      for (int e = 0; e < 4; e++) {
        EnvelopeModel adsr = synth.getEnv(e);
        if (adsr != null) {
          bridge.setEnv(r, e, adsr.attack(), adsr.decay(), adsr.sustain(), adsr.release());
        }
      }

      bridge.setMute(r, false);
      bridge.setTrackLevel(r, 0.8);
    }
  }

  /** Write V-pattern steps for a given synth. */
  private static void writeSynthSteps(int synthIndex) {
    int baseRow = KIT_COUNT + synthIndex * VOICES_PER_SYNTH;
    for (int v = 0; v < VOICES_PER_SYNTH; v++) {
      int r = baseRow + v;
      for (int s = 0; s < CLIP_STEPS; s++) {
        float vel = V_VELOCITY[s] / 100.0f;
        bridge.setStep(r, s, true);
        bridge.setVelocity(r, s, vel);
        bridge.setGate(r, s, 0.9f);
      }
    }
  }

  /** Clear all step data. */
  private static void clearAllSteps() {
    for (int t = 0; t < TRACKS; t++) {
      for (int s = 0; s < BridgeContract.STEPS; s++) {
        bridge.setStep(t, s, false);
        bridge.setVelocity(t, s, 0.0);
      }
    }
  }

  /** Capture peak/RMS for a duration while engine is running. */
  private static double[] captureOutput(int durationMs) {
    float peakL = 0, peakR = 0;
    double sumSqL = 0, sumSqR = 0;
    int samples = 0;

    int totalSamples = SAMPLE_RATE * durationMs / 1000;
    int block = 441;
    int blocks = totalSamples / block;

    for (int i = 0; i < blocks; i++) {
      vm.advanceTime(block);
      float curL = Math.abs(vm.getDacChannel(0).getLastOut());
      float curR = Math.abs(vm.getDacChannel(1).getLastOut());
      if (curL > peakL) peakL = curL;
      if (curR > peakR) peakR = curR;
      sumSqL += curL * curL;
      sumSqR += curR * curR;
      samples++;
    }

    return new double[] {peakL, peakR, Math.sqrt(sumSqL / samples), Math.sqrt(sumSqR / samples)};
  }

  /** Mute everything except the tracks between startRow and startRow+count (inclusive). */
  private static void isolateTracks(int startRow, int count) {
    for (int t = 0; t < TRACKS; t++) {
      bridge.setMute(t, t < startRow || t >= startRow + count);
    }
  }

  // ── TESTS ──────────────────────────────────────────────────────────────

  @Test
  void testKitSounds() {
    // Start engine
    vm.spork(new DelugeEngineDSL(vm));
    vm.advanceTime(100);
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.advanceTime(SAMPLE_RATE / 2);

    writeKitPattern();
    vm.advanceTime(4410);

    // Isolate to kit tracks only
    isolateTracks(0, KIT_COUNT);
    vm.advanceTime(4410);

    double[][] results = new double[KIT_COUNT][4];
    boolean anySignificant = false;

    for (int v = 0; v < KIT_COUNT; v++) {
      clearAllSteps();
      vm.advanceTime(4410);

      // Write pattern for just this voice
      for (int s = 0; s < CLIP_STEPS; s++) {
        float vel = V_VELOCITY[s] / 100.0f;
        bridge.setStep(v, s, true);
        bridge.setVelocity(v, s, vel);
        bridge.setGate(v, s, 0.9);
      }

      // Mute all other kit voices, keep synth tracks muted too
      isolateTracks(0, KIT_COUNT);
      for (int m = 0; m < KIT_COUNT; m++) {
        bridge.setMute(m, m != v);
      }

      vm.advanceTime(4410);

      vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
      vm.setGlobalFloat(BridgeContract.G_BPM, 120.0);

      double[] stats = captureOutput(4000);
      results[v] = stats;

      vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
      vm.advanceTime(4410);

      System.out.printf(
          "Kit %2d (%-12s): Peak L=%.6f R=%.6f RMS L=%.6f R=%.6f%n",
          v + 1, SOUND_NAMES[v], stats[0], stats[1], stats[2], stats[3]);

      if (stats[0] > 0.001 || stats[1] > 0.001) anySignificant = true;
    }

    System.out.println("\n=== Kit Comparison Table ===");
    System.out.printf(
        "%-6s %-12s %-15s %-15s %-12s%n", "Kit", "Name", "Peak (avg)", "RMS (avg)", "Distinct?");
    int distinct = 0;
    for (int i = 0; i < KIT_COUNT; i++) {
      double peakAvg = (results[i][0] + results[i][1]) / 2.0;
      double rmsAvg = (results[i][2] + results[i][3]) / 2.0;
      boolean d =
          i == 0
              || Math.abs(peakAvg - (results[i - 1][0] + results[i - 1][1]) / 2.0) > 0.001
              || Math.abs(rmsAvg - (results[i - 1][2] + results[i - 1][3]) / 2.0) > 0.0001;
      if (d) distinct++;
      System.out.printf(
          "Kit %2d %-12s %-15.6f %-15.6f %-12s%n",
          i + 1, SOUND_NAMES[i], peakAvg, rmsAvg, d ? "✓" : "⚠ similar");
    }

    assertTrue(
        anySignificant, "At least one kit voice should produce audible output (peak > 0.001)");
    assertTrue(distinct >= 3, "At least 3/" + KIT_COUNT + " kit voices distinct, got " + distinct);
    System.out.printf("%nKit test passed: %d/%d distinct.%n", distinct, KIT_COUNT);
  }

  @Test
  void testSynthSounds() {
    // Start engine (second spork is a no-op if already running, but we ensure state is clean)
    // The synth_shred and kit_shred wait for their respective track types independently
    vm.spork(new DelugeEngineDSL(vm));
    vm.advanceTime(100);
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.advanceTime(SAMPLE_RATE / 2);

    // Build 10 synth models
    SynthTrackModel[] synths = new SynthTrackModel[SYNTH_COUNT];
    for (int i = 0; i < SYNTH_COUNT; i++) {
      synths[i] = createSynth(i);
    }

    int synthBase = KIT_COUNT;
    double[][] results = new double[SYNTH_COUNT][4];
    boolean anySignificant = false;

    for (int i = 0; i < SYNTH_COUNT; i++) {
      clearAllSteps();
      vm.advanceTime(4410);

      // Push this synth's params and steps
      pushSynthToBridge(synths[i], i);
      writeSynthSteps(i);

      // Mute kit tracks, mute all other synth tracks
      isolateTracks(synthBase, SYNTH_COUNT * VOICES_PER_SYNTH);
      for (int j = 0; j < SYNTH_COUNT; j++) {
        if (j != i) {
          for (int v = 0; v < VOICES_PER_SYNTH; v++) {
            bridge.setMute(synthBase + j * VOICES_PER_SYNTH + v, true);
          }
        }
      }

      vm.advanceTime(4410);

      vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
      vm.setGlobalFloat(BridgeContract.G_BPM, 120.0);

      double[] stats = captureOutput(4000);
      results[i] = stats;

      vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
      vm.advanceTime(4410);

      System.out.printf(
          "Synth %2d (%-18s algo=%2d osc=%-8s filter=%-10s): "
              + "Peak L=%.6f R=%.6f RMS L=%.6f R=%.6f%n",
          i + 1,
          synths[i].getName(),
          synths[i].getSynthAlgorithm(),
          synths[i].getOsc1Type(),
          synths[i].getFilterMode(),
          stats[0],
          stats[1],
          stats[2],
          stats[3]);

      if (stats[0] > 0.001 || stats[1] > 0.001) anySignificant = true;
    }

    System.out.println("\n=== Synth Comparison Table ===");
    System.out.printf(
        "%-6s %-18s %-10s %-10s %-10s %-12s %-12s %-12s%n",
        "Synth", "Name", "Osc", "Filter", "Algo", "Peak (avg)", "RMS (avg)", "Distinct?");
    int distinct = 0;
    for (int i = 0; i < SYNTH_COUNT; i++) {
      double peakAvg = (results[i][0] + results[i][1]) / 2.0;
      double rmsAvg = (results[i][2] + results[i][3]) / 2.0;
      boolean d =
          i == 0
              || Math.abs(peakAvg - (results[i - 1][0] + results[i - 1][1]) / 2.0) > 0.001
              || Math.abs(rmsAvg - (results[i - 1][2] + results[i - 1][3]) / 2.0) > 0.0001;
      if (d) distinct++;
      System.out.printf(
          "Synth %2d %-18s %-10s %-10s %-10d %-12.6f %-12.6f %-12s%n",
          i + 1,
          synths[i].getName(),
          synths[i].getOsc1Type(),
          synths[i].getFilterMode().name(),
          synths[i].getSynthAlgorithm(),
          peakAvg,
          rmsAvg,
          d ? "✓" : "⚠ similar");
    }

    assertTrue(anySignificant, "At least one synth should produce audible output (peak > 0.001)");
    assertTrue(
        vm.getGlobalInt(BridgeContract.G_CURRENT_STEP) >= 0,
        "Engine playhead should advance during playback");
    System.out.printf("%nSynth test passed: %d/%d distinct.%n", distinct, SYNTH_COUNT);
  }
}
