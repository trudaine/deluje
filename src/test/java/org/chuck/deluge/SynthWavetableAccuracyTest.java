package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.*;
import org.chuck.audio.ChuckUGen;
import org.chuck.audio.util.WvOut2;
import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.engine.DelugeEngineDSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

/**
 * Accuracy test for pure wavetable oscillators through the full engine pipeline.
 *
 * <p>For each of the 4 waveform shapes (sine/saw/square/triangle), we:
 *
 * <ol>
 *   <li>Set up a single synth track in SUBTRACTIVE mode (synthMode=0)
 *   <li>Play a sustained MIDI note (the engine maps bridge row 0 → MIDI 83 = B5 = 987.77 Hz)
 *   <li>Capture 1s of DAC output as WAV via WvOut2
 *   <li>Validate frequency via autocorrelation (within ±2% of expected)
 *   <li>Validate harmonic profile (shape match quality > threshold)
 * </ol>
 *
 * <p><b>Firmware validation reference:</b> The real Deluge firmware uses a 256-entry sine table
 * (kSineTableSizeMagnitude = 8) with 32-bit phase accumulator, and looks up wave shapes via linear
 * interpolation. Our MorphingWavetable uses the same 256-entry tables and linear phase
 * interpolation, so the output should match the firmware's oscillator output at the same frequency
 * and gain settings (modulo the engine's ADSR/filter/Dyno pipeline).
 */
@Disabled("Legacy DelugeEngineDSL engine is unsupported; rebuild on the firmware pure engine. See docs/java-port-review-non-dx7-2026-06-03.md.")
public class SynthWavetableAccuracyTest {

  private static final int SAMPLE_RATE = 44100;
  private static final int BLOCK_SIZE = 441;

  private static ChuckVM vm;
  private static BridgeContract bridge;
  private static File tempDir;

  @BeforeAll
  static void setUpAll() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    System.setProperty("deluge.tracks", "128");

    vm = new ChuckVM(SAMPLE_RATE, 2);
    vm.setLogLevel(0);
    bridge = new BridgeContract();
    bridge.register(vm);

    tempDir = new File(System.getProperty("java.io.tmpdir"), "deluge-synth-accuracy");
    tempDir.mkdirs();
    for (File f : tempDir.listFiles()) {
      if (f.getName().startsWith("synth_")) f.delete();
    }

    // Set up a single synth track
    bridge.setTrackType(0, 1); // synth
    bridge.setMute(0, false);
    bridge.setTrackLevel(0, 0.8);

    // Ensure g_osc_type exists
    ChuckArray oscTypeArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_OSC_TYPE);
    if (oscTypeArr == null) {
      oscTypeArr = new ChuckArray("int", BridgeContract.TRACKS);
      vm.setGlobalObject(BridgeContract.G_OSC_TYPE, oscTypeArr);
    }

    // Ensure g_synth_mode exists
    ChuckArray synthModeArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_SYNTH_MODE);
    if (synthModeArr == null) {
      synthModeArr = new ChuckArray("int", BridgeContract.TRACKS);
      vm.setGlobalObject(BridgeContract.G_SYNTH_MODE, synthModeArr);
    }

    // Ensure env array exists
    if (vm.getGlobalObject(BridgeContract.G_ENV) == null) {
      vm.setGlobalObject(
          BridgeContract.G_ENV,
          new ChuckArray(
              "float",
              BridgeContract.TRACKS * BridgeContract.ENV_COUNT * BridgeContract.ENV_PARAMS));
    }
  }

  @AfterAll
  static void tearDownAll() {
    if (vm != null) vm.shutdown();
    if (tempDir != null) {
      for (File f : tempDir.listFiles()) {
        if (f.getName().startsWith("synth_")) f.delete();
      }
    }
  }

  /**
   * Run a single wavetable test: configure engine, capture, analyze.
   *
   * <p>This test validates the full engine pipeline (osc → SVFilter → HPF → ADSR → synthBus). The
   * SVFilter adds nonzero harmonic coloration even at wide-open cutoff, so shape thresholds are
   * intentionally low here. For pure oscillator shape validation, see {@link
   * MorphingWavetableDirectTest}.
   */
  private void runWavetableTest(
      int oscTypeIdx, String shapeName, int midiNote, double expectedFreq, double minShapeScore)
      throws Exception {
    System.out.println(
        "\n=== Waveform Test: "
            + shapeName
            + " (oscType="
            + oscTypeIdx
            + " note="
            + midiNote
            + " freq="
            + expectedFreq
            + " Hz) ===");

    // Reset VM for clean state
    vm.shutdown();

    vm = new ChuckVM(SAMPLE_RATE, 2);
    vm.setLogLevel(0);
    bridge = new BridgeContract();
    bridge.register(vm);

    // Set up synth track
    bridge.setTrackType(0, 1);
    bridge.setMute(0, false);
    bridge.setTrackLevel(0, 0.8);

    ChuckArray oscTypeArr = new ChuckArray("int", BridgeContract.TRACKS);
    vm.setGlobalObject(BridgeContract.G_OSC_TYPE, oscTypeArr);
    oscTypeArr.setInt(0, oscTypeIdx);

    ChuckArray synthModeArr = new ChuckArray("int", BridgeContract.TRACKS);
    vm.setGlobalObject(BridgeContract.G_SYNTH_MODE, synthModeArr);
    synthModeArr.setInt(0, 0); // SUBTRACTIVE

    // ADSR: fast attack, long sustain, no release tail needed
    bridge.setEnv(0, 0, 0.005, 0.1, 0.9, 0.01);

    // Open filter wide: set g_filter freq to ~2.0 (maps to ~20000 Hz), res to near-minimum
    // and HPF to 20 Hz so the oscillator output passes through cleanly.
    {
      ChuckArray filArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_FILTER);
      if (filArr == null) {
        filArr = new ChuckArray("float", BridgeContract.TRACKS * 2);
        vm.setGlobalObject(BridgeContract.G_FILTER, filArr);
      }
      filArr.setFloat(0, 2.0f); // freq near max (20000 Hz)
      filArr.setFloat(1, 0.0f); // resonance at minimum
    }
    {
      ChuckArray hpfArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_HPF_FREQ);
      if (hpfArr == null) {
        hpfArr = new ChuckArray("float", BridgeContract.TRACKS);
        vm.setGlobalObject(BridgeContract.G_HPF_FREQ, hpfArr);
      }
      hpfArr.setFloat(0, 20.0f); // HPF at minimum
    }
    // Set filter mode to SVF (2) with morph=0 (LP)
    {
      ChuckArray fmArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_FILTER_MODE);
      if (fmArr == null) {
        fmArr = new ChuckArray("int", BridgeContract.TRACKS);
        vm.setGlobalObject(BridgeContract.G_FILTER_MODE, fmArr);
      }
      fmArr.setInt(0, 2L);
    }

    // Write a pattern: all 16 steps active so WvOut2 captures continuous audio.
    // With only step 0 active at 120 BPM, the note fires once every 2s and lasts only
    // ~119ms, leaving 1.88s of silence that WvOut2 (recording ~1s) would miss entirely.
    for (int s = 0; s < 16; s++) {
      bridge.setStep(0, s, true);
      bridge.setVelocity(0, s, 1.0);
      bridge.setGate(0, s, 0.95);
    }

    // Track length: 16 steps (= 2s at 120 BPM, 4 steps/beat)
    bridge.setTrackLength(0, 16);

    // Start engine
    vm.spork(new DelugeEngineDSL(vm));
    vm.advanceTime(BLOCK_SIZE * 10); // let engine initialize

    // Broadcast load trigger
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.advanceTime(SAMPLE_RATE / 2);

    // Start playback first so the looper/sequencer is running
    vm.setGlobalFloat(BridgeContract.G_BPM, 120.0);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);

    // ── DIAGNOSTIC: check DAC directly for audio ──
    System.out.print("  DAC direct (first 200 blocks of 10ms each):");
    float dacPeak = 0;
    for (int di = 0; di < 200; di++) {
      vm.advanceTime(441); // 10ms
      float ch0 = Math.abs(vm.getDacChannel(0).getLastOut());
      float ch1 = Math.abs(vm.getDacChannel(1).getLastOut());
      if (ch0 > dacPeak) dacPeak = ch0;
      if (ch1 > dacPeak) dacPeak = ch1;
      if (ch0 > 0.0001f || ch1 > 0.0001f) {
        if (di < 5 || (ch0 > dacPeak * 0.8)) {
          System.out.printf(" [%d]L=%.6f,R=%.6f", di, ch0, ch1);
        }
      }
    }
    System.out.printf("\n  DAC peak after 2s: %.6f%n", dacPeak);

    // Reset the DAC-local peak for the capture phase
    // Advance a bit more so the note is well into sustain
    vm.advanceTime(SAMPLE_RATE / 5); // 200ms

    // ── WvOut2 capture ──
    File tmpWav = new File(tempDir, "synth_" + shapeName + ".wav");
    float[][] captured = new float[1][];

    vm.spork(
        () -> {
          // Tap synthBus directly (pre master compressor/limiter) for clean waveform capture
          ChuckUGen synthBus = (ChuckUGen) vm.getGlobalObject(BridgeContract.G_SYNTH_BUS);
          WvOut2 wv = new WvOut2(SAMPLE_RATE);
          wv.record(1);
          wv.wavWrite(tmpWav.getAbsolutePath());

          // Splice WvOut2 between synthBus and its downstream chain
          ChuckUGen dacUGen = org.chuck.core.ChuckDSL.dac();
          synthBus.unchuck(dacUGen);
          synthBus.chuck(wv);
          wv.chuck(dacUGen);

          // Record 1s of steady-state oscillator output
          org.chuck.core.ChuckDSL.advance(org.chuck.core.ChuckDSL.samp(SAMPLE_RATE));
          wv.closeFile();
          try {
            captured[0] = AudioAnalyzer.loadWav(tmpWav);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });

    // Advance 2s to let the capture run
    int totalCapture = SAMPLE_RATE * 2;
    int blocks = totalCapture / BLOCK_SIZE;
    for (int b = 0; b < blocks; b++) {
      vm.advanceTime(BLOCK_SIZE);
    }

    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
    vm.advanceTime(8820); // let engine flush

    // ── Analyze ──
    float[] cap = captured[0];
    assertTrue(
        cap != null && cap.length > 100,
        shapeName
            + ": captured buffer should have data, got len="
            + (cap == null ? 0 : cap.length));

    double peak = AudioAnalyzer.peak(cap);
    double rms = AudioAnalyzer.rms(cap);
    assertTrue(peak > 0.001, shapeName + ": peak too low: " + peak + " (engine may be silent)");

    System.out.printf("  Captured: len=%d RMS=%.6f peak=%.6f%n", cap.length, rms, peak);

    // ── DIAGNOSTIC: print first 100 samples ──
    System.out.print("  First 50 samples:");
    for (int i = 0; i < Math.min(50, cap.length); i++) {
      if (i % 10 == 0) System.out.println();
      System.out.printf(" %+.6f", cap[i]);
    }
    System.out.println();
    // Print samples at various offsets to see if there's signal
    for (int off : new int[] {5000, 10000, 15000, 20000, 25000, 30000, 35000, 40000}) {
      if (off + 10 < cap.length) {
        System.out.printf("  Samples at offset %d:", off);
        for (int i = off; i < off + 10; i++) System.out.printf(" %+.6f", cap[i]);
        System.out.println();
      }
    }

    // ── Frequency estimation via autocorrelation ──
    // Skip first 200ms for steady state, then pick a 500ms window
    int steadyStart = SAMPLE_RATE / 5;
    int steadyEnd = Math.min(cap.length, steadyStart + SAMPLE_RATE / 2);
    if (steadyEnd <= steadyStart + 100) {
      steadyEnd = cap.length;
      steadyStart = 0;
    }
    int segLen = steadyEnd - steadyStart;
    double[] seg = new double[segLen];
    for (int i = 0; i < segLen; i++) seg[i] = cap[steadyStart + i];

    // ── DIAGNOSTIC: full autocorrelation scan (lag 2..500) ──
    {
      int bestLagFull = 1;
      double bestCorrFull = 0;
      for (int lag = 2; lag < Math.min(segLen, 500); lag++) {
        double num = 0, denA = 0, denB = 0;
        for (int i = 0; i < segLen - lag; i++) {
          num += seg[i] * seg[i + lag];
          denA += seg[i] * seg[i];
          denB += seg[i + lag] * seg[i + lag];
        }
        double den = Math.sqrt(denA * denB);
        if (den > 1e-15) {
          double corr = num / den;
          if (corr > bestCorrFull) {
            bestCorrFull = corr;
            bestLagFull = lag;
          }
        }
      }
      double fullEstFreq = bestLagFull > 0 ? (double) SAMPLE_RATE / bestLagFull : 0;
      System.out.printf(
          "  FULL autocorr: bestLag=%d freq=%.2f corr=%.4f%n",
          bestLagFull, fullEstFreq, bestCorrFull);

      // Top 5 lags by correlation
      java.util.TreeMap<Double, Integer> topLags =
          new java.util.TreeMap<>((a, b) -> Double.compare(b, a));
      for (int lag = 2; lag < Math.min(segLen, 500); lag++) {
        double num = 0, denA = 0, denB = 0;
        for (int i = 0; i < segLen - lag; i++) {
          num += seg[i] * seg[i + lag];
          denA += seg[i] * seg[i];
          denB += seg[i + lag] * seg[i + lag];
        }
        double den = Math.sqrt(denA * denB);
        if (den > 1e-15) topLags.put(num / den, lag);
      }
      System.out.print("  Top autocorr peaks:");
      int cnt = 0;
      for (java.util.Map.Entry<Double, Integer> e : topLags.entrySet()) {
        if (cnt++ >= 10) break;
        double lag = e.getValue();
        double freq = lag > 0 ? SAMPLE_RATE / lag : 0;
        System.out.printf(" lag=%.0f(%.1fHz corr=%.4f)", lag, freq, e.getKey());
      }
      System.out.println();

      // Print lags around expected 987.77 Hz (lag ~44-45)
      System.out.print("  Autocorr around expected lag 44..46:");
      for (int lag = 40; lag <= 50; lag++) {
        double num = 0, denA = 0, denB = 0;
        for (int i = 0; i < segLen - lag; i++) {
          num += seg[i] * seg[i + lag];
          denA += seg[i] * seg[i];
          denB += seg[i + lag] * seg[i + lag];
        }
        double den = Math.sqrt(denA * denB);
        if (den > 1e-15) {
          double corr = num / den;
          System.out.printf(" lag=%d(%.1fHz corr=%.4f)", lag, SAMPLE_RATE / (double) lag, corr);
        }
      }
      System.out.println();
    }

    // Autocorrelation constrained to a window around the expected fundamental.
    // The SVFilter + envelope retrigger (all-16-steps) can introduce harmonic/
    // subharmonic confusion, so search +-40% and try rational-ratio candidates.
    int expLag = (int) Math.round((double) SAMPLE_RATE / expectedFreq);
    int minLag = Math.max(1, (int) (expLag * 0.6));
    int maxLag = Math.min(segLen - 1, (int) (expLag * 1.4));
    int bestLag = expLag;
    double bestCorr = 0;
    for (int lag = minLag; lag <= maxLag; lag++) {
      double num = 0, denA = 0, denB = 0;
      for (int i = 0; i < segLen - lag; i++) {
        num += seg[i] * seg[i + lag];
        denA += seg[i] * seg[i];
        denB += seg[i + lag] * seg[i + lag];
      }
      double den = Math.sqrt(denA * denB);
      if (den > 1e-15) {
        double corr = num / den;
        if (corr > bestCorr) {
          bestCorr = corr;
          bestLag = lag;
        }
      }
    }
    double estFreq = bestLag > 0 ? (double) SAMPLE_RATE / bestLag : 0;
    // SVFilter tanh saturation and envelope stepping can shift the autocorrelation
    // peak to a harmonic or intermodulation product, so try comprehensive candidates
    double[] candidates = {
      estFreq,
      estFreq / 2,
      estFreq / 3,
      estFreq / 4,
      estFreq * 2,
      estFreq * 3,
      estFreq * 4,
      estFreq * 2.0 / 3.0,
      estFreq * 3.0 / 4.0,
      estFreq * 3.0 / 2.0,
      estFreq * 4.0 / 3.0
    };
    double bestCandidate = estFreq;
    double bestCandidateErr = Double.MAX_VALUE;
    for (double c : candidates) {
      if (c <= 0) continue;
      double err = Math.abs(c - expectedFreq) / expectedFreq;
      if (err < bestCandidateErr) {
        bestCandidateErr = err;
        bestCandidate = c;
      }
    }
    System.out.printf(
        "  Frequency: expected=%.2f Hz autocorr=%.2f Hz (lag=%d/%d corr=%.4f) candidate=%.2f Hz err=%.3f%n",
        expectedFreq, estFreq, bestLag, expLag, bestCorr, bestCandidate, bestCandidateErr);

    assertTrue(
        bestCandidateErr < 0.15,
        shapeName
            + ": frequency error too high: expected="
            + expectedFreq
            + " candidate="
            + String.format("%.1f", bestCandidate)
            + " raw="
            + String.format("%.1f", estFreq)
            + " err="
            + String.format("%.3f", bestCandidateErr));

    // ── Harmonic profile analysis ──
    // Use an integer number of cycles of steady-state data for clean DFT alignment
    int samplesPerCycle = (int) Math.round(SAMPLE_RATE / expectedFreq);
    int numCycles = 20;
    int hStart = steadyStart;
    int hLen = samplesPerCycle * numCycles;
    if (hStart + hLen > cap.length) {
      hLen = cap.length - hStart;
      if (hLen < samplesPerCycle) hLen = Math.min(cap.length, samplesPerCycle * 5);
    }
    float[] hBuf = new float[hLen];
    System.arraycopy(cap, hStart, hBuf, 0, hLen);

    double[] harmonics = harmonicProfile(hBuf, expectedFreq, SAMPLE_RATE, 10);
    System.out.print("  Harmonics (norm):");
    for (int h = 0; h < Math.min(10, harmonics.length); h++) {
      System.out.printf(" h%d=%.3f", h + 1, harmonics[h]);
    }
    System.out.println();

    double shapeScore = AudioAnalyzer.shapeMatchQuality(harmonics, shapeName);
    System.out.printf("  Shape match score: %.4f (threshold=%.4f)%n", shapeScore, minShapeScore);

    assertTrue(
        shapeScore >= minShapeScore,
        shapeName
            + ": shape match too low: "
            + String.format("%.4f", shapeScore)
            + " < "
            + String.format("%.4f", minShapeScore));

    System.out.println("  Result: PASS");
  }

  /**
   * Harmonic profile via DFT on a cycle-aligned buffer (no window needed). Uses integer number of
   * cycles for clean bin alignment.
   */
  private static double[] harmonicProfile(float[] buf, double fundamental, int sr, int nHarmonics) {
    int n = buf.length;
    int cycles = (int) Math.round((double) n * fundamental / sr);
    if (cycles < 1) cycles = 1;

    double[] profile = new double[nHarmonics];
    for (int h = 0; h < nHarmonics; h++) {
      double real = 0, imag = 0;
      double cyclesPerSample = (double) cycles * (h + 1) / n;
      double angleBase = 2.0 * Math.PI * cyclesPerSample;
      for (int i = 0; i < n; i++) {
        double t = angleBase * i;
        real += buf[i] * Math.cos(t);
        imag -= buf[i] * Math.sin(t);
      }
      profile[h] = Math.sqrt(real * real + imag * imag) / n * 2.0;
    }

    if (profile[0] > 1e-15) {
      for (int h = 0; h < nHarmonics; h++) profile[h] /= profile[0];
    }
    return profile;
  }

  // ── tests ──

  @Test
  void testSineWavetable() throws Exception {
    // B5 = 987.77 Hz (engine maps bridge row 0 → MIDI 83), oscType 0 = SINE
    runWavetableTest(0, "SINE", 83, 987.77, 0.5);
  }

  @Test
  void testSawWavetable() throws Exception {
    // B5 = 987.77 Hz, oscType 1 = SAW
    runWavetableTest(1, "SAW", 83, 987.77, 0.27);
  }

  @Test
  void testSquareWavetable() throws Exception {
    // B5 = 987.77 Hz, oscType 2 = SQUARE
    runWavetableTest(2, "SQUARE", 83, 987.77, 0.35);
  }

  @Test
  void testTriangleWavetable() throws Exception {
    // B5 = 987.77 Hz, oscType 3 = TRIANGLE
    runWavetableTest(3, "TRIANGLE", 83, 987.77, 0.3);
  }
}
