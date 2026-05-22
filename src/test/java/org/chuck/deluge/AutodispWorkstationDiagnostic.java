package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.oscillators.OscType;
import org.chuck.deluge.firmware.engine.FirmwareSound;
import org.chuck.deluge.firmware.engine.FirmwareVoice;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.util.Q31;
import org.chuck.deluge.model.FilterMode;
import org.junit.jupiter.api.Test;

public class AutodispWorkstationDiagnostic {

  private static final int SAMPLE_RATE = 44100;

  @Test
  public void testPureSineWavePitchAndShapeParity() {
    System.out.println("\n--- DIAGNOSTIC STEP 1: PURE SINE WAVE SHAPE CHECK ---");
    int[] testNotes = {48, 60, 72}; // C3 (130.81Hz), C4 (261.63Hz), C5 (523.25Hz)
    double[] expectedFreqs = {130.81278265, 261.6255653, 523.2511306};

    for (int nIdx = 0; nIdx < testNotes.length; nIdx++) {
      int note = testNotes[nIdx];
      double targetFreq = expectedFreqs[nIdx];

      FirmwareSound sound = new FirmwareSound();
      sound.oscTypes[0] = OscType.SINE;
      sound.oscTypes[1] = OscType.SINE; // Solo single oscillator
      sound.numUnison = 1;
      sound.setLpfMode(null); // Bypass filter set
      sound.osc1RetriggerPhase = 0; // Force exact phase alignment at sample 0!

      // Max volume parameters, bypass envelope modulation by setting sustain to MAX
      sound.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = Q31.ONE;
      sound.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = 0;
      sound.paramNeutralValues[Param.LOCAL_VOLUME] = Q31.ONE;
      sound.paramNeutralValues[Param.LOCAL_ENV_0_ATTACK] = 10000000; // Instant attack!
      sound.paramNeutralValues[Param.LOCAL_ENV_0_SUSTAIN] = Q31.ONE; // Max sustain!

      sound.triggerNote(note, 127);

      // Render 1024 samples of output in loops of 128 block units
      int totalSamples = 1024;
      StereoSample[] buffer = new StereoSample[totalSamples];
      StereoSample[] subBuffer = new StereoSample[128];
      for (int i = 0; i < 128; i++) subBuffer[i] = new StereoSample();

      for (int b = 0; b < totalSamples / 128; b++) {
        for (int i = 0; i < 128; i++) {
          subBuffer[i].l = 0;
          subBuffer[i].r = 0;
        }
        sound.renderOutput(subBuffer, 128, null);
        for (int i = 0; i < 128; i++) {
          buffer[b * 128 + i] = new StereoSample();
          buffer[b * 128 + i].l = subBuffer[i].l;
          buffer[b * 128 + i].r = subBuffer[i].r;
        }
      }

      // Analyze physical waveform properties
      double rmsDiff = 0.0;
      double peak = 0.0;
      int zeroCrossings = 0;

      // Note phase accumulator starting point verification
      // (MPE/Voice initial starting phase is 0 for SINE by default)
      for (int i = 0; i < totalSamples; i++) {
        double actualVal = buffer[i].l / 2147483648.0;
        double referenceVal = Math.sin(2.0 * Math.PI * targetFreq * i / SAMPLE_RATE);

        double diff = actualVal - referenceVal;
        rmsDiff += diff * diff;
        if (Math.abs(actualVal) > peak) peak = Math.abs(actualVal);

        if (i > 0) {
          double prevVal = buffer[i - 1].l / 2147483648.0;
          if (prevVal * actualVal < 0) {
            zeroCrossings++;
          }
        }
      }

      rmsDiff = Math.sqrt(rmsDiff / totalSamples);
      System.out.printf(
          "  Note %d (%s): Peak=%.4f RMS_Diff_To_Math_Sine=%.6f ZeroCrossings=%d\n",
          note, noteName(note), peak, rmsDiff, zeroCrossings);

      // Assertions
      assertTrue(peak > 0.1, "Signal is silent or way too quiet!");
      assertTrue(
          rmsDiff < 0.005,
          "Wave shape does not match a perfect mathematical sine wave! Peak Diff is " + rmsDiff);
    }
    System.out.println(
        "STEP 1 PASSED: Pure sine waves are mathematically pristine and scale correctly!");
  }

  @Test
  public void testPureSawWaveShapeCheck() {
    System.out.println("\n--- DIAGNOSTIC STEP 2: PURE SAW WAVE SHAPE CHECK ---");
    FirmwareSound sound = new FirmwareSound();
    sound.oscTypes[0] = OscType.SAW;
    sound.oscTypes[1] = OscType.SINE;
    sound.numUnison = 1;
    sound.setLpfMode(null);

    sound.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = Q31.ONE;
    sound.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = 0;
    sound.paramNeutralValues[Param.LOCAL_VOLUME] = Q31.ONE;
    sound.paramNeutralValues[Param.LOCAL_ENV_0_SUSTAIN] = Q31.ONE;

    sound.triggerNote(60, 127); // Trigger C4 (261.63Hz)

    int totalSamples = 128;
    StereoSample[] buffer = new StereoSample[totalSamples];
    for (int i = 0; i < totalSamples; i++) buffer[i] = new StereoSample();

    sound.renderOutput(buffer, totalSamples, null);

    // A valid linear or lookup band-limited SAW wave should be a continuous monotonic ramp
    // for local segments, periodically jumping back at the phase reset point.
    // Let's verify that the sequence has a constant derivative direction for standard blocks!
    int directionChanges = 0;
    double lastDiff = 0.0;
    for (int i = 2; i < 40; i++) {
      double prev = buffer[i - 1].l / 2147483648.0;
      double curr = buffer[i].l / 2147483648.0;
      double diff = curr - prev;

      if (i > 2 && diff * lastDiff < 0) {
        directionChanges++;
      }
      lastDiff = diff;
    }

    System.out.println(
        "  Pure SAW (C4) first 40 derivative direction changes: " + directionChanges);
    // Standard middle C saw period at 44.1k is ~168 samples. So over 40 samples there should be
    // ZERO direction changes (a perfect single linear ramp segment!).
    assertEquals(
        0, directionChanges, "SAW wave shape is distorted with sub-sample direction wiggles!");
    System.out.println("STEP 2 PASSED: Pure SAW wave is a clean monotonic ramp segment!");
  }

  @Test
  public void testActiveEnvelopeMappingHeadroom() {
    System.out.println("\n--- DIAGNOSTIC STEP 3: ENVELOPE AMPLITUDE DECAY SCALING ---");
    FirmwareSound sound = new FirmwareSound();
    sound.oscTypes[0] = OscType.SINE;
    sound.oscTypes[1] = OscType.SINE;
    sound.numUnison = 1;
    sound.setLpfMode(null);

    sound.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = Q31.ONE;
    sound.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = 0;
    sound.paramNeutralValues[Param.LOCAL_VOLUME] = Q31.ONE;

    // Set high-speed rate envelope settings (larger values mean faster transition rates!)
    sound.paramNeutralValues[Param.LOCAL_ENV_0_ATTACK] = 1000000; // Instant attack (1 block)
    sound.paramNeutralValues[Param.LOCAL_ENV_0_DECAY] = 80000; // Fast decay
    sound.paramNeutralValues[Param.LOCAL_ENV_0_SUSTAIN] = Q31.ONE / 2;
    sound.paramNeutralValues[Param.LOCAL_ENV_0_RELEASE] = 50000;

    sound.triggerNote(60, 127);

    // Capture dynamic envelope steps at block transitions
    int numBlocks = 20;
    double[] blockPeaks = new double[numBlocks];
    StereoSample[] buffer = new StereoSample[128];
    for (int i = 0; i < 128; i++) buffer[i] = new StereoSample();

    for (int b = 0; b < numBlocks; b++) {
      for (int i = 0; i < 128; i++) {
        buffer[i].l = 0;
        buffer[i].r = 0;
      }
      sound.renderOutput(buffer, 128, null);

      if (b == 0 && !sound.voices.isEmpty()) {
        FirmwareVoice voice = sound.voices.iterator().next();
        System.out.println(
            "  [DIAG] Sound Neutral Attack: " + sound.paramNeutralValues[Param.LOCAL_ENV_0_ATTACK]);
        System.out.println(
            "  [DIAG] Voice Env0 Attack (Active): "
                + voice.paramFinalValues[Param.LOCAL_ENV_0_ATTACK]);
        System.out.println(
            "  [DIAG] Voice Env0 Decay (Active): "
                + voice.paramFinalValues[Param.LOCAL_ENV_0_DECAY]);
        System.out.println(
            "  [DIAG] Voice Env0 Sustain (Active): "
                + voice.paramFinalValues[Param.LOCAL_ENV_0_SUSTAIN]);
      }

      double peak = 0.0;
      for (int i = 0; i < 128; i++) {
        double val = Math.abs(buffer[i].l / 2147483648.0);
        if (val > peak) peak = val;
      }
      blockPeaks[b] = peak;
    }

    System.out.println("  Decay Path Peaks (first 20 blocks):");
    for (int b = 0; b < numBlocks; b++) {
      System.out.printf("    Block[%d]: %.4f\n", b, blockPeaks[b]);
    }

    // Verify envelope scales down nicely from attack peak toward sustain level
    assertTrue(
        blockPeaks[1] > blockPeaks[15],
        "Envelope is stuck! Attack stage does not transition to Decay!");
    assertTrue(
        blockPeaks[15] >= 0.05, "Envelope decayed too fast or fell to absolute silence too early!");
    System.out.println("STEP 3 PASSED: Envelope ADSR scaling path is functional and smooth!");
  }

  @Test
  public void testStepByStepFilterResponseResponse() {
    System.out.println("\n--- DIAGNOSTIC STEP 4: STEP-BY-STEP FILTER RESPONSE (LPF vs HPF) ---");

    // We trigger a high note C6 (1046.50Hz) and feed it through a low-pass filter with cutoff set
    // low at 400Hz.
    // The filter should significantly attenuate the signal amplitude!
    FirmwareSound sound = new FirmwareSound();
    sound.oscTypes[0] = OscType.SINE;
    sound.oscTypes[1] = OscType.SINE;
    sound.numUnison = 1;

    // Set SVF Low-pass Filter mode
    sound.setLpfMode(FilterMode.SVF);

    sound.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = Q31.ONE;
    sound.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = 0;
    sound.paramNeutralValues[Param.LOCAL_VOLUME] = Q31.ONE;
    sound.paramNeutralValues[Param.LOCAL_ENV_0_ATTACK] = 10000000; // Instant attack!
    sound.paramNeutralValues[Param.LOCAL_ENV_0_SUSTAIN] = Q31.ONE;

    // Set filter cutoff extremely low (represented in Q31 as a lower threshold)
    sound.paramNeutralValues[Param.LOCAL_LPF_FREQ] = (int) (0.01 * 2147483647.0); // Very low cutoff
    sound.paramNeutralValues[Param.LOCAL_LPF_RESONANCE] = 0;
    sound.paramNeutralValues[Param.LOCAL_LPF_MORPH] = 0; // Pure LPF mode!

    sound.triggerNote(84, 127); // Note C6 (1046Hz)

    // Render 256 samples of output in loops of 128 block units
    int totalSamples = 256;
    StereoSample[] buffer = new StereoSample[totalSamples];
    StereoSample[] subBuffer = new StereoSample[128];
    for (int i = 0; i < 128; i++) subBuffer[i] = new StereoSample();

    for (int b = 0; b < totalSamples / 128; b++) {
      for (int i = 0; i < 128; i++) {
        subBuffer[i].l = 0;
        subBuffer[i].r = 0;
      }
      sound.renderOutput(subBuffer, 128, null);
      for (int i = 0; i < 128; i++) {
        buffer[b * 128 + i] = new StereoSample();
        buffer[b * 128 + i].l = subBuffer[i].l;
        buffer[b * 128 + i].r = subBuffer[i].r;
      }
    }

    double peak = 0.0;
    for (int i = 0; i < totalSamples; i++) {
      double val = Math.abs(buffer[i].l / 2147483648.0);
      if (val > peak) peak = val;
    }

    System.out.printf(
        "  High Pitch Note (1046Hz) LPF Attenuation: Cutoff=300Hz OutputPeak=%.4f\n", peak);
    // Since the filter is 24dB/octave (4 poles!) and cutoff is more than 1.5 octaves below pitch,
    // the output peak should be heavily attenuated (at least below 0.15 master float headroom
    // range!).
    assertTrue(
        peak < 0.15,
        "Low Pass Filter did not attenuate the high-frequency signal! Peak is too high: " + peak);
    System.out.println("STEP 4 PASSED: Low Pass Filter attenuation slope operates correctly!");
  }

  private String noteName(int note) {
    String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    return names[note % 12] + (note / 12 - 1);
  }
}
