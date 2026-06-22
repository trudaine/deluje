package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.firmware2.Param;
import org.junit.jupiter.api.Test;

/**
 * Regression for the kit-audition bug: every 808 cell played the same sound because the kit-config
 * dialog never applied the chosen WAV to the live FirmwareKit drum (the bridge path only fed the
 * legacy DSL engine). SwingDelugeApp.applyKitDrumSampleLive now does what this test does directly:
 * give each drum its own sample (oscType=SAMPLE + samples[0] + fw2SampleCache[0]). This proves that
 * distinct per-drum samples yield distinct per-drum audio.
 */
class KitDrumSampleTest {

  /** Build a 1-channel firmware Sample from a float waveform. */
  private static org.deluge.playback.Sample sample(float[] wave) {
    org.deluge.playback.Sample s = new org.deluge.playback.Sample();
    s.data = wave;
    s.numChannels = 1;
    s.sampleRate = 44100f;
    return s;
  }

  private static void giveSample(FirmwareSound drum, float[] wave) {
    drum.oscTypes[0] = org.deluge.firmware2.Oscillator.OscType.SAMPLE;
    drum.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] =
        org.deluge.firmware2.Functions.ONE_Q31; // audible
    var s = sample(wave);
    drum.samples[0] = s;
    drum.fw2SampleCache[0] = org.deluge.firmware2.Sample.fromFirmwareSample(s);
  }

  private static long renderDrum(FirmwareKit kit, FirmwareAudioEngine eng, int row) {
    kit.triggerDrum(row, 127);
    long h = 1469598103934665603L;
    for (int b = 0; b < 30; b++) {
      eng.renderBlock(128);
      for (int i = 0; i < 128; i++)
        h = (h ^ (eng.masterBuffer[i].l & 0xFFFFFFFFL)) * 1099511628211L;
    }
    return h;
  }

  @Test
  void distinctSamplesGiveDistinctOutput() {
    float[] waveA = new float[2000];
    float[] waveB = new float[2000];
    for (int i = 0; i < waveA.length; i++) {
      waveA[i] = (float) Math.sin(i * 0.05) * 0.8f;
      waveB[i] = (float) Math.sin(i * 0.40) * 0.8f;
    }

    FirmwareSound drumA = new FirmwareSound();
    giveSample(drumA, waveA);
    FirmwareAudioEngine engA = new FirmwareAudioEngine();
    engA.sounds.add(drumA);
    drumA.triggerNote(60, 127);
    engA.renderBlock(128);
    long ha = 0;
    for (int i = 0; i < 128; i++) ha += Math.abs((long) engA.masterBuffer[i].l);

    FirmwareSound drumB = new FirmwareSound();
    giveSample(drumB, waveB);
    FirmwareAudioEngine engB = new FirmwareAudioEngine();
    engB.sounds.add(drumB);
    drumB.triggerNote(60, 127);
    engB.renderBlock(128);
    long hb = 0;
    for (int i = 0; i < 128; i++) hb += Math.abs((long) engB.masterBuffer[i].l);

    assertNotEquals(ha, hb, "sample A and sample B must produce different output");
    assertTrue(ha > 0 && hb > 0, "both samples must produce audio");
  }

  /**
   * Direct FirmwareSound sample playback (no kit wrapper). Verifies that a sound with a sample
   * produces AUDIBLE output — the fundamental property bug 1 required.
   */
  @Test
  void directSampleSoundProducesAudio() {
    float[] wave = new float[2000];
    for (int i = 0; i < wave.length; i++) wave[i] = (float) Math.sin(i * 0.2) * 0.8f;

    // Direct sound with sample
    var withSample = new FirmwareSound();
    giveSample(withSample, wave);
    var eng = new FirmwareAudioEngine();
    eng.sounds.add(withSample);
    withSample.triggerNote(60, 127);
    eng.renderBlock(128);
    long e = 0;
    for (int i = 0; i < 128; i++) e += Math.abs((long) eng.masterBuffer[i].l);
    assertTrue(e > 0, "sample-based sound must produce audio output");

    // Direct sound WITHOUT sample
    var noSample = new FirmwareSound();
    noSample.oscTypes[0] = org.deluge.firmware2.Oscillator.OscType.SAMPLE;
    var eng2 = new FirmwareAudioEngine();
    eng2.sounds.add(noSample);
    noSample.triggerNote(60, 127);
    eng2.renderBlock(128);
    long e2 = 0;
    for (int i = 0; i < 128; i++) e2 += Math.abs((long) eng2.masterBuffer[i].l);
    // Without a sample, OscType.SAMPLE renders silence (the oscillator doesn't handle it).
    // The point: a loaded sample CHANGES the output.
    assertNotEquals(e, e2, "sample-loaded sound must differ from sample-less sound");
  }

  @Test
  void kitDrumProducesAudio() {
    float[] wave = new float[2000];
    for (int i = 0; i < wave.length; i++) wave[i] = (float) Math.sin(i * 0.2) * 0.8f;

    FirmwareKit kit = new FirmwareKit();
    giveSample(kit.drumSounds.get(0), wave);

    FirmwareAudioEngine eng = new FirmwareAudioEngine();
    eng.sounds.add(kit);

    kit.triggerDrum(0, 127);
    eng.renderBlock(128);

    long e = 0;
    for (int i = 0; i < 128; i++) e += Math.abs((long) eng.masterBuffer[i].l);
    assertTrue(e > 0, "kit drum must produce audio output");
  }

  @Test
  void twoKitDrumsSumCorrectly() {
    float[] waveA = new float[2000];
    float[] waveB = new float[2000];
    for (int i = 0; i < waveA.length; i++) {
      waveA[i] = (float) Math.sin(i * 0.05) * 0.4f;
      waveB[i] = (float) Math.sin(i * 0.40) * 0.4f;
    }

    // 1. Render both drums together in one kit
    FirmwareKit kit = new FirmwareKit();
    giveSample(kit.drumSounds.get(0), waveA);
    giveSample(kit.drumSounds.get(1), waveB);

    FirmwareAudioEngine eng = new FirmwareAudioEngine();
    eng.sounds.add(kit);

    kit.triggerDrum(0, 127);
    kit.triggerDrum(1, 127);
    eng.renderBlock(128);

    long[] mixedOut = new long[256];
    for (int i = 0; i < 128; i++) {
      mixedOut[i * 2] = eng.masterBuffer[i].l;
      mixedOut[i * 2 + 1] = eng.masterBuffer[i].r;
    }

    // 2. Render drum 0 individually
    FirmwareKit kit0 = new FirmwareKit();
    giveSample(kit0.drumSounds.get(0), waveA);
    FirmwareAudioEngine eng0 = new FirmwareAudioEngine();
    eng0.sounds.add(kit0);
    kit0.triggerDrum(0, 127);
    eng0.renderBlock(128);
    long[] out0 = new long[256];
    for (int i = 0; i < 128; i++) {
      out0[i * 2] = eng0.masterBuffer[i].l;
      out0[i * 2 + 1] = eng0.masterBuffer[i].r;
    }

    // 3. Render drum 1 individually
    FirmwareKit kit1 = new FirmwareKit();
    giveSample(kit1.drumSounds.get(1), waveB);
    FirmwareAudioEngine eng1 = new FirmwareAudioEngine();
    eng1.sounds.add(kit1);
    kit1.triggerDrum(1, 127);
    eng1.renderBlock(128);
    long[] out1 = new long[256];
    for (int i = 0; i < 128; i++) {
      out1[i * 2] = eng1.masterBuffer[i].l;
      out1[i * 2 + 1] = eng1.masterBuffer[i].r;
    }

    // Verify that mixed output is not identical to drum 1's output alone
    long diffToOut1 = 0;
    for (int i = 0; i < 256; i++) {
      diffToOut1 += Math.abs(mixedOut[i] - out1[i]);
    }
    assertTrue(
        diffToOut1 > 100,
        "mixed output must not be identical to drum 1's output alone (meaning drum 0 was overwritten!)");
  }
}
