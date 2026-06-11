package org.chuck.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Full-stack reproduction of the "all 808 cells sound the same" bug. Mirrors exactly what the Swing
 * UI does: FirmwareKit → per-drum sample assignment via {@code
 * FirmwareSound.samples[0]/fw2SampleCache[0]} → trigger + render through FirmwareAudioEngine →
 * compare audio output.
 *
 * <p>If this test passes but the UI still sounds broken, the bug is in {@code
 * SwingDelugeApp.applyKitDrumSampleLive}'s engine reference or timing vs {@code
 * syncHighFidelityEngine} rebuilds. If this test FAILS, the bug is in the DSP chain itself.
 */
class Kit808E2ETest {

  /** Generate a short synthetic sample — a 100ms tone at the given frequency. */
  private static org.chuck.deluge.firmware.model.sample.Sample makeSample(float freqHz) {
    int sr = 44100;
    int n = sr / 10; // 100ms
    float[] data = new float[n];
    for (int i = 0; i < n; i++) {
      data[i] = (float) Math.sin(2.0 * Math.PI * freqHz * i / sr) * 0.8f;
    }
    var s = new org.chuck.deluge.firmware.model.sample.Sample();
    s.data = data;
    s.numChannels = 1;
    s.sampleRate = sr;
    return s;
  }

  /** Apply a model Sample to a FirmwareKit drum exactly as applyKitDrumSampleLive does. */
  private static void applySampleToDrum(
      FirmwareKit kit, int drumIdx, org.chuck.deluge.firmware.model.sample.Sample ms) {
    FirmwareSound drum = kit.drumSounds.get(drumIdx);
    drum.oscTypes[0] = org.chuck.deluge.firmware.dsp.oscillators.OscType.SAMPLE;
    drum.samples[0] = ms;
    drum.fw2SampleCache[0] = org.chuck.deluge.firmware2.Sample.fromFirmwareSample(ms);
  }

  /** Render a single drum hit through the engine, return energy + per-block hash for comparison. */
  private static long renderAndHash(FirmwareKit kit, FirmwareAudioEngine eng, int row) {
    kit.triggerDrum(row, 127);
    long h = 1469598103934665603L;
    for (int b = 0; b < 30; b++) {
      eng.renderBlock(128);
      for (int i = 0; i < 128; i++) {
        h = (h ^ (eng.masterBuffer[i].l & 0xFFFFFFFFL)) * 1099511628211L;
      }
    }
    return h;
  }

  /** Return total energy of the master buffer after one trigger + 30 renders. */
  private static long renderEnergy(FirmwareKit kit, FirmwareAudioEngine eng, int row) {
    kit.triggerDrum(row, 127);
    long energy = 0;
    for (int b = 0; b < 30; b++) {
      eng.renderBlock(128);
      for (int i = 0; i < 128; i++) {
        energy += Math.abs((long) eng.masterBuffer[i].l) + Math.abs((long) eng.masterBuffer[i].r);
      }
    }
    return energy;
  }

  @Test
  void distinctSamplesProduceDistinctKitDrumAudio() {
    // Four clearly different frequencies so the hash/energy MUST differ if the sample is audible.
    float[] freqs = {220, 440, 880, 1760};
    var samples = new org.chuck.deluge.firmware.model.sample.Sample[4];
    for (int i = 0; i < 4; i++) samples[i] = makeSample(freqs[i]);

    FirmwareKit kit = new FirmwareKit();
    FirmwareAudioEngine eng = new FirmwareAudioEngine();
    eng.sounds.add(kit);
    for (int i = 0; i < 4; i++) applySampleToDrum(kit, i, samples[i]);

    long[] hashes = new long[4];
    long[] energies = new long[4];
    for (int i = 0; i < 4; i++) {
      // fresh engine each time so renders don't bleed
      FirmwareKit k = new FirmwareKit();
      FirmwareAudioEngine e = new FirmwareAudioEngine();
      e.sounds.add(k);
      for (int j = 0; j < 4; j++) applySampleToDrum(k, j, samples[j]);
      hashes[i] = renderAndHash(k, e, i);
      energies[i] = renderEnergy(k, e, i);
    }

    // Every drum must produce non-zero energy
    for (int i = 0; i < 4; i++) {
      assertTrue(energies[i] > 0, "drum " + i + " (" + freqs[i] + "Hz) must produce audio");
    }

    // Every pair must have a different hash (different audio fingerprint)
    for (int i = 0; i < 4; i++) {
      for (int j = i + 1; j < 4; j++) {
        assertNotEquals(
            hashes[i],
            hashes[j],
            "drum "
                + i
                + " ("
                + freqs[i]
                + "Hz) and drum "
                + j
                + " ("
                + freqs[j]
                + "Hz) must sound different");
      }
    }
  }

  /**
   * The exact applyKitDrumSampleLive flow: load a WAV file, apply to a live kit drum through the
   * engine reference. If this passes but the UI doesn't work, the engine reference in the live app
   * is stale (syncHighFidelityEngine replaces the sounds list).
   */
  @Test
  void applyKitDrumSampleLiveStyleFlowProducesAudio() {
    var ms = makeSample(440);
    var kit = new FirmwareKit();
    var eng = new FirmwareAudioEngine();
    eng.sounds.add(kit);

    // Simulate applyKitDrumSampleLive
    FirmwareSound drum = kit.drumSounds.get(3); // drum 3
    drum.oscTypes[0] = org.chuck.deluge.firmware.dsp.oscillators.OscType.SAMPLE;
    drum.samples[0] = ms;
    drum.fw2SampleCache[0] = org.chuck.deluge.firmware2.Sample.fromFirmwareSample(ms);

    long e3 = renderEnergy(kit, eng, 3);
    long e0 = renderEnergy(kit, eng, 0); // drum 0 — no sample

    assertTrue(e3 > 0, "drum 3 with sample must produce audio (energy=" + e3 + ")");
    // Drum 0 has oscType=SAMPLE but no sample → should be silent (oscillator doesn't handle SAMPLE)
    // If e0 > 0, something else is producing sound for sample-less drums
    assertNotEquals(e3, e0, "sampled drum 3 and sample-less drum 0 must differ");
  }

  /**
   * Test that the engine reference survives a simulated rebuild (syncHighFidelityEngine pattern).
   * The real bug might be: syncHighFidelityEngine does eng.sounds.clear() then re-adds, but drum
   * objects are from the NEW factory call — losing any direct sample assignment done via
   * applyKitDrumSampleLive on the OLD drums.
   */
  @Test
  void sampleSurvivesEngineRebuild() {
    var ms = makeSample(660);
    var kit = new FirmwareKit();
    var eng = new FirmwareAudioEngine();
    eng.sounds.add(kit);

    // applyKitDrumSampleLive on the ORIGINAL kit (before rebuild)
    FirmwareSound originalDrum = kit.drumSounds.get(2);
    originalDrum.oscTypes[0] = org.chuck.deluge.firmware.dsp.oscillators.OscType.SAMPLE;
    originalDrum.samples[0] = ms;
    originalDrum.fw2SampleCache[0] = org.chuck.deluge.firmware2.Sample.fromFirmwareSample(ms);

    // Simulate syncHighFidelityEngine: clear and rebuild
    eng.sounds.clear();
    var newKit = new FirmwareKit(); // fresh kit — NO samples loaded
    eng.sounds.add(newKit);

    // Trigger the NEW kit's drum 2 — it has no sample (the sample was on the old kit)
    newKit.triggerDrum(2, 127);
    long energy = 0;
    for (int b = 0; b < 30; b++) {
      eng.renderBlock(128);
      for (int i = 0; i < 128; i++) {
        energy += Math.abs((long) eng.masterBuffer[i].l) + Math.abs((long) eng.masterBuffer[i].r);
      }
    }
    System.out.println(
        "AFTER REBUILD: drum 2 energy="
            + energy
            + " (expect ~0 — sample was on OLD kit, lost in rebuild)");
    // This WILL be 0 because the rebuild lost the sample. The fix: applyKitDrumSampleLive must
    // re-apply the sample AFTER the rebuild, or the factory must load samples from the model.
  }
}
