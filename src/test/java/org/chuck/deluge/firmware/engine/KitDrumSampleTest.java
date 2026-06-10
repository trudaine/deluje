package org.chuck.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.util.Q31;
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
  private static org.chuck.deluge.firmware.model.sample.Sample sample(float[] wave) {
    org.chuck.deluge.firmware.model.sample.Sample s =
        new org.chuck.deluge.firmware.model.sample.Sample();
    s.data = wave;
    s.numChannels = 1;
    s.sampleRate = 44100f;
    return s;
  }

  private static void giveSample(FirmwareSound drum, float[] wave) {
    drum.oscTypes[0] = org.chuck.deluge.firmware.dsp.oscillators.OscType.SAMPLE;
    drum.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = Q31.ONE; // audible
    var s = sample(wave);
    drum.samples[0] = s;
    drum.fw2SampleCache[0] = org.chuck.deluge.firmware2.Sample.fromFirmwareSample(s);
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
  void distinctSamplesGiveDistinctDrums() {
    // Two clearly different waveforms.
    float[] waveA = new float[2000];
    float[] waveB = new float[2000];
    for (int i = 0; i < waveA.length; i++) {
      waveA[i] = (float) Math.sin(i * 0.05) * 0.8f; // low tone
      waveB[i] = (float) Math.sin(i * 0.40) * 0.8f; // higher tone
    }

    FirmwareKit kit = new FirmwareKit();
    FirmwareAudioEngine eng = new FirmwareAudioEngine();
    eng.sounds.add(kit);
    giveSample(kit.drumSounds.get(0), waveA);
    giveSample(kit.drumSounds.get(1), waveB);

    long a = renderDrum(kit, eng, 0);
    // fresh engine/kit so the two renders don't bleed into each other
    FirmwareKit kit2 = new FirmwareKit();
    FirmwareAudioEngine eng2 = new FirmwareAudioEngine();
    eng2.sounds.add(kit2);
    giveSample(kit2.drumSounds.get(0), waveA);
    giveSample(kit2.drumSounds.get(1), waveB);
    long b = renderDrum(kit2, eng2, 1);

    assertNotEquals(a, b, "drum 0 (sample A) and drum 1 (sample B) must sound different");
  }

  @Test
  void sampleDrumDiffersFromNoSampleDrum() {
    float[] wave = new float[2000];
    for (int i = 0; i < wave.length; i++) wave[i] = (float) Math.sin(i * 0.2) * 0.8f;

    FirmwareKit withSample = new FirmwareKit();
    FirmwareAudioEngine e1 = new FirmwareAudioEngine();
    e1.sounds.add(withSample);
    giveSample(withSample.drumSounds.get(0), wave);
    long withS = renderDrum(withSample, e1, 0);

    FirmwareKit noSample = new FirmwareKit(); // default: oscType=SAMPLE but no sample loaded
    FirmwareAudioEngine e2 = new FirmwareAudioEngine();
    e2.sounds.add(noSample);
    long without = renderDrum(noSample, e2, 0);

    assertNotEquals(
        withS, without, "a drum with a sample must differ from a sample-less default drum");
    // and the sample drum must actually make sound
    FirmwareKit again = new FirmwareKit();
    FirmwareAudioEngine e3 = new FirmwareAudioEngine();
    e3.sounds.add(again);
    giveSample(again.drumSounds.get(0), wave);
    again.triggerDrum(0, 127);
    long energy = 0;
    for (int b = 0; b < 30; b++) {
      e3.renderBlock(128);
      for (int i = 0; i < 128; i++) energy += Math.abs((long) e3.masterBuffer[i].l);
    }
    assertTrue(energy > 0, "a sample-backed drum must produce audio (energy=" + energy + ")");
  }
}
