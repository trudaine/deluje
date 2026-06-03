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
 * Accuracy test for FM synthesis modulation.
 *
 * <p>Validates that FM modulation (synthMode=1) produces audibly different output from carrier-only
 * (SUBTRACTIVE) operation. The real Deluge firmware uses sine-only modulators with 32-bit wrapping
 * phase shift: carrierPhase += modulatorSample + feedback.
 *
 * <p><b>Firmware reference:</b> In the real firmware (voice.cpp), the FM phase shift is a wrapping
 * 32-bit unsigned addition of the modulator sample into the carrier phase. Feedback is hard-clipped
 * to 22 bits (signed_saturate<22>). Our engine uses wavetable modulators (oscType-driven) vs the
 * firmware's sine-only modulators — this test validates our FM path is at least producing
 * modulation (not just passing carrier through unchanged).
 */
@org.junit.jupiter.api.Tag("slow")
@Disabled("Legacy DelugeEngineDSL engine is unsupported; rebuild on the firmware pure engine. See docs/java-port-review-non-dx7-2026-06-03.md.")
public class SynthFmAccuracyTest {

  private static final int SAMPLE_RATE = 44100;
  private static final int BLOCK_SIZE = 441;

  private static ChuckVM vm;
  private static BridgeContract bridge;
  private static File tempDir;

  @BeforeAll
  static void setUpAll() throws Exception {
    tempDir = new File(System.getProperty("java.io.tmpdir"), "deluge-fm-test");
    tempDir.mkdirs();
    for (File f : tempDir.listFiles()) {
      if (f.getName().startsWith("fm_")) f.delete();
    }
  }

  @AfterAll
  static void tearDownAll() {
    if (tempDir != null) {
      for (File f : tempDir.listFiles()) {
        if (f.getName().startsWith("fm_")) f.delete();
      }
    }
  }

  /**
   * Capture 1s of DAC output from the engine with a single synth track. Returns the captured float
   * array.
   */
  private float[] captureSynth(
      boolean useFm, double fmRatio, double fmAmount, int oscType, int midiNote, String label)
      throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    System.setProperty("deluge.tracks", "128");

    ChuckVM localVm = new ChuckVM(SAMPLE_RATE, 2);
    localVm.setLogLevel(0);
    BridgeContract localBridge = new BridgeContract();
    localBridge.register(localVm);

    // Set up synth track
    localBridge.setTrackType(0, 1);
    localBridge.setMute(0, false);
    localBridge.setTrackLevel(0, 0.8);

    // OSC type
    ChuckArray oscTypeArr = (ChuckArray) localVm.getGlobalObject(BridgeContract.G_OSC_TYPE);
    if (oscTypeArr == null) {
      oscTypeArr = new ChuckArray("int", BridgeContract.TRACKS);
      localVm.setGlobalObject(BridgeContract.G_OSC_TYPE, oscTypeArr);
    }
    oscTypeArr.setInt(0, oscType);

    // Synth mode: SUBTRACTIVE (0) or FM (1)
    ChuckArray synthModeArr = (ChuckArray) localVm.getGlobalObject(BridgeContract.G_SYNTH_MODE);
    if (synthModeArr == null) {
      synthModeArr = new ChuckArray("int", BridgeContract.TRACKS);
      localVm.setGlobalObject(BridgeContract.G_SYNTH_MODE, synthModeArr);
    }
    synthModeArr.setInt(0, useFm ? 1 : 0);

    // FM ratio and amount
    if (useFm) {
      ChuckArray fmRatioArr = (ChuckArray) localVm.getGlobalObject(BridgeContract.G_FM_RATIO);
      if (fmRatioArr == null) {
        fmRatioArr = new ChuckArray("float", BridgeContract.TRACKS);
        localVm.setGlobalObject(BridgeContract.G_FM_RATIO, fmRatioArr);
      }
      fmRatioArr.setFloat(0, (float) fmRatio);

      ChuckArray fmAmtArr = (ChuckArray) localVm.getGlobalObject(BridgeContract.G_FM_AMOUNT);
      if (fmAmtArr == null) {
        fmAmtArr = new ChuckArray("float", BridgeContract.TRACKS);
        localVm.setGlobalObject(BridgeContract.G_FM_AMOUNT, fmAmtArr);
      }
      fmAmtArr.setFloat(0, (float) fmAmount);
    }

    // ADSR
    localBridge.setEnv(0, 0, 0.005, 0.1, 0.9, 0.01);

    // Pattern: all 16 steps active so WvOut2 captures continuous audio.
    // With only step 0 active, the note fires once every 2s (trackLen=16 below)
    // and lasts only ~119ms, leaving 1.88s of silence that WvOut2 would miss.
    for (int s = 0; s < 16; s++) {
      localBridge.setStep(0, s, true);
      localBridge.setVelocity(0, s, 1.0);
      localBridge.setGate(0, s, 0.95);
    }
    localBridge.setTrackLength(0, 16);

    // Ensure env array
    if (localVm.getGlobalObject(BridgeContract.G_ENV) == null) {
      localVm.setGlobalObject(
          BridgeContract.G_ENV,
          new ChuckArray(
              "float",
              BridgeContract.TRACKS * BridgeContract.ENV_COUNT * BridgeContract.ENV_PARAMS));
    }

    // Start engine
    localVm.spork(new DelugeEngineDSL(localVm));
    localVm.advanceTime(BLOCK_SIZE * 10);
    localVm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    localVm.advanceTime(SAMPLE_RATE / 2);

    // Start playback first so the sequencer/looper is running
    localVm.setGlobalFloat(BridgeContract.G_BPM, 120.0);
    localVm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    localVm.advanceTime(SAMPLE_RATE / 5); // 200ms — let envelope reach sustain

    // WvOut2 capture
    File tmpWav = new File(tempDir, "fm_" + label + ".wav");
    float[][] captured = new float[1][];

    localVm.spork(
        () -> {
          // Tap synthBus directly (pre master compressor/limiter) for clean waveform capture
          ChuckUGen synthBus = (ChuckUGen) localVm.getGlobalObject(BridgeContract.G_SYNTH_BUS);
          WvOut2 wv = new WvOut2(SAMPLE_RATE);
          wv.record(1);
          wv.wavWrite(tmpWav.getAbsolutePath());

          synthBus.unchuck(org.chuck.core.ChuckDSL.dac());
          synthBus.chuck(wv);
          wv.chuck(org.chuck.core.ChuckDSL.dac());

          org.chuck.core.ChuckDSL.advance(org.chuck.core.ChuckDSL.samp(SAMPLE_RATE));
          wv.closeFile();
          try {
            captured[0] = AudioAnalyzer.loadWav(tmpWav);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });

    int totalCapture = SAMPLE_RATE * 2;
    int blocks = totalCapture / BLOCK_SIZE;
    for (int b = 0; b < blocks; b++) {
      localVm.advanceTime(BLOCK_SIZE);
    }

    localVm.setGlobalInt(BridgeContract.G_PLAY, 0L);
    localVm.advanceTime(8820);
    localVm.shutdown();

    return captured[0];
  }

  @Test
  void testFmModulationChangesOutput() throws Exception {
    // Capture carrier-only (no FM)
    float[] carrierOnly = captureSynth(false, 0, 0, 0, 60, "carrier_only");
    assertTrue(
        carrierOnly != null && carrierOnly.length > 100,
        "Carrier-only capture failed, len=" + (carrierOnly == null ? 0 : carrierOnly.length));

    double carPeak = AudioAnalyzer.peak(carrierOnly);
    double carRms = AudioAnalyzer.rms(carrierOnly);
    System.out.println("\n=== FM Modulation Test ===");
    System.out.printf(
        "  Carrier-only: peak=%.6f RMS=%.6f len=%d%n", carPeak, carRms, carrierOnly.length);
    assertTrue(carPeak > 0.001, "Carrier-only peak too low: " + carPeak);

    // Capture with FM: ratio=2.0, amount=0.5
    float[] fmOutput = captureSynth(true, 2.0, 0.5, 0, 60, "fm_on");
    assertTrue(
        fmOutput != null && fmOutput.length > 100,
        "FM capture failed, len=" + (fmOutput == null ? 0 : fmOutput.length));

    double fmPeak = AudioAnalyzer.peak(fmOutput);
    double fmRms = AudioAnalyzer.rms(fmOutput);
    double fmCarCorr =
        AudioAnalyzer.correlation(
            trimLength(carrierOnly, Math.min(carrierOnly.length, fmOutput.length)),
            trimLength(fmOutput, Math.min(carrierOnly.length, fmOutput.length)));

    System.out.printf(
        "  FM on:        peak=%.6f RMS=%.6f len=%d%n", fmPeak, fmRms, fmOutput.length);
    System.out.printf("  Carrier vs FM correlation: %.4f%n", fmCarCorr);
    System.out.printf("  RMS ratio: FM/NoFM = %.4f%n", carRms > 0 ? fmRms / carRms : 0);

    // FM should produce noticeably different output from carrier-only.
    // If the correlation is too high (>0.95), the FM path isn't modulating.
    assertTrue(
        fmCarCorr < 0.95,
        "FM output should differ from carrier-only (correlation="
            + String.format("%.4f", fmCarCorr)
            + ")");

    // FM analysis: sideband detection via harmonic profile
    int steadyStart = SAMPLE_RATE / 10;
    int steadyLen = Math.min(fmOutput.length - steadyStart, SAMPLE_RATE);
    if (steadyLen > 0) {
      float[] steady = new float[steadyLen];
      System.arraycopy(fmOutput, steadyStart, steady, 0, steadyLen);

      // Estimate fundamental for diagnostics
      double estFreq = AudioAnalyzer.estimateFrequency(steady, SAMPLE_RATE, 100, 500);
      double knownFundamental = 261.63; // MIDI 60 = C4
      System.out.printf(
          "  FM estimated fundamental: %.2f Hz (known=%.2f)%n", estFreq, knownFundamental);

      // Use known fundamental for harmonic profile (autocorrelation is unreliable
      // for FM-modulated signals with sidebands — the sidebands confuse peak picking).
      double[] harm = AudioAnalyzer.harmonicProfile(steady, knownFundamental, SAMPLE_RATE, 10);
      System.out.print("  FM harmonics:");
      for (int h = 0; h < Math.min(10, harm.length); h++) {
        System.out.printf(" h%d=%.3f", h + 1, harm[h]);
      }
      System.out.println();

      // FM with ratio=2.0 should produce sidebands at fundamental ± n*2*fundamental.
      // Sidebands manifest as energy in odd/even harmonics beyond simple carrier behavior.
      // Sine carrier + FM: expect significant even-order harmonics (unlike pure sine).
      double evenEnergy = 0, oddEnergy = 0;
      for (int h = 1; h < harm.length; h++) {
        if ((h + 1) % 2 == 0) evenEnergy += harm[h] * harm[h];
        else oddEnergy += harm[h] * harm[h];
      }
      System.out.printf(
          "  FM harmonic energy: even=%.4f odd=%.4f ratio=%.4f%n",
          evenEnergy, oddEnergy, evenEnergy > 0 ? oddEnergy / evenEnergy : 0);

      // With FM, there should be measurable energy in harmonics
      double totalHarmonicEnergy = evenEnergy + oddEnergy;
      assertTrue(
          totalHarmonicEnergy > 0.01,
          "FM should produce harmonic sidebands (harmonic energy="
              + String.format("%.4f", totalHarmonicEnergy)
              + ")");
    }

    System.out.println("  Result: PASS");
  }

  @Test
  void testFmRatioChangesSpectrum() throws Exception {
    // Capture FM with ratio=1.0 (carrier/modulator same freq — fundamental emphasis)
    float[] fmRatio1 = captureSynth(true, 1.0, 0.5, 0, 60, "fm_r1");
    assertTrue(fmRatio1 != null && fmRatio1.length > 100, "FM ratio=1 capture failed");

    // Capture FM with ratio=3.0 (modulator at 3× carrier — different sidebands)
    float[] fmRatio3 = captureSynth(true, 3.0, 0.5, 0, 60, "fm_r3");
    assertTrue(fmRatio3 != null && fmRatio3.length > 100, "FM ratio=3 capture failed");

    // Correlation should be significantly different (different spectral content)
    double r1r3Corr =
        AudioAnalyzer.correlation(
            trimLength(fmRatio1, Math.min(fmRatio1.length, fmRatio3.length)),
            trimLength(fmRatio3, Math.min(fmRatio1.length, fmRatio3.length)));

    System.out.println("\n=== FM Ratio Variation Test ===");
    System.out.printf("  FM ratio=1 vs ratio=3 correlation: %.4f%n", r1r3Corr);
    System.out.printf(
        "  FM ratio=1: peak=%.6f RMS=%.6f%n",
        AudioAnalyzer.peak(fmRatio1), AudioAnalyzer.rms(fmRatio1));
    System.out.printf(
        "  FM ratio=3: peak=%.6f RMS=%.6f%n",
        AudioAnalyzer.peak(fmRatio3), AudioAnalyzer.rms(fmRatio3));

    // Different ratios should produce measurably different output
    assertTrue(
        r1r3Corr < 0.98,
        "FM ratio=1 and ratio=3 should produce different output (correlation="
            + String.format("%.4f", r1r3Corr)
            + ")");

    System.out.println("  Result: PASS");
  }

  /** Trim float array to given length. */
  private static float[] trimLength(float[] src, int len) {
    if (src.length <= len) return src;
    float[] out = new float[len];
    System.arraycopy(src, 0, out, 0, len);
    return out;
  }
}
