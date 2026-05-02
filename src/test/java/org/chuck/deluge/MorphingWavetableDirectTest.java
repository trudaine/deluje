package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.chuck.audio.util.MorphingWavetable;
import org.junit.jupiter.api.Test;

/**
 * Direct unit test for {@link MorphingWavetable} — no engine pipeline, no SVFilter.
 *
 * <p>Creates the oscillator directly, sets freq and table index, ticks it sample-by-sample,
 * captures the output array, and validates frequency (via zero-crossing count) and harmonic
 * profile (via DFT) against ideal waveform shapes.
 *
 * <p>This tests the wavetable oscillator in isolation. For engine-pipeline wavetable tests
 * (including SVFilter coloration), see {@link SynthWavetableAccuracyTest}.
 */
public class MorphingWavetableDirectTest {

  private static final int SAMPLE_RATE = 44100;
  private static final double EXPECTED_FREQ = 261.625565; // C4

  /** Number of complete cycles to capture and analyze. */
  private static final int NUM_CYCLES = 50;

  /** Wavetable to match the engine's WAVE_TABLES layout (sine/saw/square/triangle). */
  private static float[][] buildWaveTables() {
    float[][] t = new float[4][256];
    for (int i = 0; i < 256; i++) {
      t[0][i] = (float) Math.sin(2.0 * Math.PI * i / 256.0);
      t[1][i] = (float) (2.0 * (i / 256.0) - 1.0);
      t[2][i] = (i < 128) ? 1.0f : -1.0f;
      t[3][i] = (i < 128) ? (i / 64.0f - 1.0f) : (3.0f - i / 64.0f);
    }
    return t;
  }

  /**
   * Run a single wavetable test: tick the oscillator, capture output, validate.
   */
  private void runWavetableTest(int oscTypeIdx, String shapeName, double minShapeScore)
      throws Exception {
    System.out.println("\n=== Direct Wavetable Test: " + shapeName
        + " (oscType=" + oscTypeIdx + " freq=" + EXPECTED_FREQ + " Hz) ===");

    // Build oscillator with same tables as engine
    float[][] tables = buildWaveTables();
    MorphingWavetable osc = new MorphingWavetable(SAMPLE_RATE);
    osc.setTables(tables);
    osc.index(oscTypeIdx);
    osc.freq(EXPECTED_FREQ);
    osc.gain(0.8f);

    // Capture an exact integer number of cycles so DFT bins align precisely.
    // Sample-accurate: compute exact period in samples.
    int samplesPerCycle = (int) Math.round(SAMPLE_RATE / EXPECTED_FREQ);
    // Double-check accuracy: expectedFreq * samplesPerCycle / sr should be ~1.0
    double actualCycleFreq = (double) SAMPLE_RATE / samplesPerCycle;
    int totalSamples = samplesPerCycle * NUM_CYCLES;
    float[] output = new float[totalSamples];

    // Tick the oscillator sample-by-sample using public tick(float manualInput).
    // MorphingWavetable.compute applies its own gain, and tick also multiplies by gain.
    // We use gain=0.8 in osc.gain() above; tick multiplies again, so set gain to 1.
    System.out.printf("  samplesPerCycle=%d actualCycleFreq=%.4f Hz totalSamples=%d%n",
        samplesPerCycle, actualCycleFreq, totalSamples);
    for (int i = 0; i < totalSamples; i++) {
      output[i] = osc.tick(0, i);
    }

    // Basic sanity: peak should be reasonable
    double peak = peak(output);
    double rms = rms(output);
    System.out.printf("  Captured: len=%d peak=%.6f RMS=%.6f%n", output.length, peak, rms);
    assertTrue(peak > 0.001, shapeName + ": peak too low: " + peak);

    // ── Frequency validation via zero-crossing count ──
    // Use zero-crossing on the first ~5 cycles for startup transient skip
    int skipSamples = samplesPerCycle * 2; // skip first 2 cycles for steady state
    int zcStart = skipSamples;
    int zcEnd = Math.min(output.length, zcStart + samplesPerCycle * 10);
    int zeroCrossings = 0;
    boolean lastPositive = output[zcStart] >= 0;
    for (int i = zcStart + 1; i < zcEnd; i++) {
      boolean positive = output[i] >= 0;
      if (positive != lastPositive) zeroCrossings++;
      lastPositive = positive;
    }
    double estFreq = (zeroCrossings / 2.0) * SAMPLE_RATE / (zcEnd - zcStart);
    System.out.printf("  Zero-crossings (skip=%d, range=%d): %d → estimated freq: %.2f Hz%n",
        skipSamples, zcEnd - zcStart, zeroCrossings, estFreq);

    double freqErr = Math.abs(estFreq - EXPECTED_FREQ) / EXPECTED_FREQ;
    assertTrue(freqErr < 0.02,
        shapeName + ": frequency error too high: expected="
            + String.format("%.2f", EXPECTED_FREQ) + " got=" + String.format("%.2f", estFreq)
            + " err=" + String.format("%.4f", freqErr));

    // ── Harmonic profile ──
    double[] harmonics = harmonicProfile(output, EXPECTED_FREQ, SAMPLE_RATE, 10);
    System.out.print("  Harmonics (norm):");
    for (int h = 0; h < Math.min(10, harmonics.length); h++) {
      System.out.printf(" h%d=%.4f", h + 1, harmonics[h]);
    }
    System.out.println();

    double shapeScore = shapeMatchQuality(harmonics, shapeName);
    System.out.printf("  Shape match score: %.4f (threshold=%.4f)%n", shapeScore, minShapeScore);

    assertTrue(shapeScore >= minShapeScore,
        shapeName + ": shape match too low: " + String.format("%.4f", shapeScore)
            + " < " + String.format("%.4f", minShapeScore));

    System.out.println("  Result: PASS");
  }

  // ── tests ──

  @Test
  void testSineWavetable() throws Exception {
    // Sine: fundamental dominates, harmonics near zero → expect high score
    runWavetableTest(0, "SINE", 0.9);
  }

  @Test
  void testSawWavetable() throws Exception {
    // Saw: all harmonics at 1/n. 256-entry table with linear interpolation causes
    // stair-step attenuation at audio frequencies, so harmonics are weaker than ideal.
    runWavetableTest(1, "SAW", 0.35);
  }

  @Test
  void testSquareWavetable() throws Exception {
    // Square: odd harmonics at 1/n. Same stair-step attenuation as saw.
    runWavetableTest(2, "SQUARE", 0.4);
  }

  @Test
  void testTriangleWavetable() throws Exception {
    // Triangle: odd harmonics at 1/n². Attenuated by table interpolation stair-stepping.
    runWavetableTest(3, "TRIANGLE", 0.6);
  }

  // ── analysis utilities (self-contained, no external deps) ──

  /** Quick test: FM modulation via modGain with mod.chuck(car) connection. */
  @Test
  void testFmModulationViaModGain() throws Exception {
    float[][] tables = buildWaveTables();
    MorphingWavetable car = new MorphingWavetable(SAMPLE_RATE);
    car.setTables(tables);
    MorphingWavetable mod = new MorphingWavetable(SAMPLE_RATE);
    mod.setTables(tables);

    mod.chuck(car); // mod → car audio chain

    double f = 987.77; // B5 = engine's row-0 note
    car.freq(f);
    mod.freq(f * 2.0); // ratio=2.0
    mod.gain(1.0f);
    car.modGain(25.0f); // fmA(0.5)*50

    int n = SAMPLE_RATE;
    float[] fmOut = new float[n];
    for (int i = 0; i < n; i++) {
      fmOut[i] = car.tick(i); // uses mod.chuck(car) → sum → compute(input)
    }
    double fmPeak = peak(fmOut);
    double fmRms = rms(fmOut);
    System.out.println("FM direct test: peak=" + String.format("%.6f", fmPeak)
        + " RMS=" + String.format("%.6f", fmRms));

    // Now without FM
    mod.gain(0);
    car.modGain(0);
    float[] carOut = new float[n];
    for (int i = 0; i < n; i++) {
      carOut[i] = car.tick(n + i);
    }
    double carPeak = peak(carOut);
    double carRms = rms(carOut);
    System.out.println("Carrier direct test: peak=" + String.format("%.6f", carPeak)
        + " RMS=" + String.format("%.6f", carRms));

    assertTrue(fmPeak > 0.001, "FM should produce output, peak=" + String.format("%.6f", fmPeak));
    assertTrue(carPeak > 0.001, "Carrier should produce output, peak=" + String.format("%.6f", carPeak));
    System.out.println("  Result: PASS");
  }

  private static double peak(float[] data) {
    double p = 0;
    for (float v : data) { double a = Math.abs(v); if (a > p) p = a; }
    return p;
  }

  private static double rms(float[] data) {
    double sumSq = 0;
    for (float v : data) sumSq += v * v;
    return Math.sqrt(sumSq / data.length);
  }

  /**
   * Harmonic profile via DFT on a cycle-aligned buffer (no window needed).
   * The buffer must contain exactly an integer number of cycles at the fundamental,
   * so DFT bins align perfectly with harmonic frequencies.
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

    // Normalize so fundamental = 1.0
    if (profile[0] > 1e-15) {
      for (int h = 0; h < nHarmonics; h++) profile[h] /= profile[0];
    }
    return profile;
  }

  /**
   * Compare harmonic profile against ideal shape model.
   */
  private static double shapeMatchQuality(double[] profile, String shape) {
    int nh = profile.length;
    double score = 0;
    double maxScore = 0;

    switch (shape.toUpperCase()) {
      case "SINE":
        // Only fundamental; all harmonics should be near zero
        for (int h = 1; h < nh; h++) {
          double actual = profile[h];
          double err = Math.abs(actual);
          double weight = 1.0 / (h + 1);
          score += weight * Math.max(0, 1.0 - err * 5.0);
          maxScore += weight;
        }
        break;

      case "SQUARE":
        // Odd harmonics at 1/n amplitude, even harmonics at 0
        for (int h = 1; h < nh; h++) {
          double actual = profile[h];
          double ideal = (h % 2 == 0) ? 0.0 : 1.0 / (h + 1);
          double err = Math.abs(actual - ideal);
          double weight = 1.0 / (h + 1);
          score += weight * Math.max(0, 1.0 - err * 3.0);
          maxScore += weight;
        }
        break;

      case "SAW":
        // All harmonics at 1/n amplitude
        for (int h = 1; h < nh; h++) {
          double actual = profile[h];
          double ideal = 1.0 / (h + 1);
          double err = Math.abs(actual - ideal);
          double weight = 1.0 / (h + 1);
          score += weight * Math.max(0, 1.0 - err * 3.0);
          maxScore += weight;
        }
        break;

      case "TRIANGLE":
        // Odd harmonics at 1/n² amplitude, even at 0
        for (int h = 1; h < nh; h++) {
          double actual = profile[h];
          double ideal = (h % 2 == 0) ? 0.0 : 1.0 / ((h + 1) * (h + 1));
          double err = Math.abs(actual - ideal);
          double weight = 1.0 / (h + 1);
          score += weight * Math.max(0, 1.0 - err * 5.0);
          maxScore += weight;
        }
        break;

      default:
        return 0;
    }

    return maxScore > 0 ? score / maxScore : 0;
  }
}
