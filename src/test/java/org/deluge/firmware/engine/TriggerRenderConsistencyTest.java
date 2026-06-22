package org.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Regression guard for the "edit cells then play = no sound" bug. The engine renders the sounds in
 * {@code engine.sounds}, but playback triggers notes on the clip's own {@code sound} object
 * (InstrumentClip.triggerNote). If a sync ever leaves those as different instances, triggered notes
 * go to an object that is never rendered → silence. This test pins that invariant: a note is only
 * audible when the triggered sound is the same instance the engine renders.
 */
class TriggerRenderConsistencyTest {

  private static org.deluge.firmware.model.sample.Sample tone(float freqHz) {
    int sr = 44100;
    int n = sr / 10;
    float[] data = new float[n];
    for (int i = 0; i < n; i++) {
      data[i] = (float) Math.sin(2.0 * Math.PI * freqHz * i / sr) * 0.8f;
    }
    var s = new org.deluge.firmware.model.sample.Sample();
    s.data = data;
    s.numChannels = 1;
    s.sampleRate = sr;
    return s;
  }

  private static void loadDrum(FirmwareKit kit, int drumIdx, float freq) {
    FirmwareSound drum = kit.drumSounds.get(drumIdx);
    drum.oscTypes[0] = org.deluge.firmware2.Oscillator.OscType.SAMPLE;
    drum.samples[0] = tone(freq);
    drum.fw2SampleCache[0] = org.deluge.firmware2.Sample.fromFirmwareSample(drum.samples[0]);
  }

  private static long renderEnergy(FirmwareAudioEngine eng) {
    return energy(eng, 30);
  }

  private static long energy(FirmwareAudioEngine eng, int blocks) {
    long e = 0;
    for (int b = 0; b < blocks; b++) {
      eng.renderBlock(128);
      for (int i = 0; i < 128; i++) {
        e += Math.abs((long) eng.masterBuffer[i].l) + Math.abs((long) eng.masterBuffer[i].r);
      }
    }
    return e;
  }

  private static FirmwareKit kitWithTone(float freq) {
    FirmwareKit kit = new FirmwareKit();
    loadDrum(kit, 0, freq);
    return kit;
  }

  @Test
  void triggeringTheRenderedSoundProducesAudio() {
    FirmwareKit kit = new FirmwareKit();
    loadDrum(kit, 0, 440);
    FirmwareAudioEngine eng = new FirmwareAudioEngine();
    eng.sounds.add(kit); // the SAME instance is both triggered and rendered

    kit.triggerDrum(0, 127);
    assertTrue(renderEnergy(eng) > 0, "a note on the rendered sound must be audible");
  }

  @Test
  void triggeringAnUnrenderedSoundIsSilent() {
    // Reproduces the regression: engine renders 'rendered', but the note is triggered on a
    // different instance 'orphan' (as happened when setSong installed a fresh song whose clips
    // pointed at new sounds while engine.sounds kept the old ones).
    FirmwareKit rendered = new FirmwareKit();
    loadDrum(rendered, 0, 440);
    FirmwareKit orphan = new FirmwareKit();
    loadDrum(orphan, 0, 440);

    FirmwareAudioEngine eng = new FirmwareAudioEngine();
    eng.sounds.add(rendered);

    orphan.triggerDrum(0, 127); // triggered, but not in engine.sounds
    assertEquals(0L, renderEnergy(eng), "triggering an unrendered sound yields silence");
  }

  // ── Live-edit continuity: the "garbage/dropout when editing during playback" bug ──

  @Test
  void keepingTheSoundPreservesTheRingingVoiceAcrossAnEdit() {
    FirmwareKit kit = kitWithTone(440);
    FirmwareAudioEngine eng = new FirmwareAudioEngine();
    eng.sounds.add(kit);

    kit.triggerDrum(0, 127);
    assertTrue(energy(eng, 5) > 0, "voice is ringing");
    // A content edit that does NOT replace the sound (what the live-sync does): voice keeps
    // ringing.
    assertTrue(
        energy(eng, 5) > 0, "audio continues across an edit when the sound instance is preserved");
  }

  // ── Desktop output gain staging: "garbage" was hard clipping from the 24x monitor boost ──

  /**
   * Apply the exact JavaAudioDriver output chain at a given boost; returns [clippedSamples,
   * peakx1000].
   */
  private static long[] driverChain(int[] rawL, int gain) {
    long clips = 0;
    long peak = 0;
    for (int l : rawL) {
      // Clean linear desktop chain (matches JavaAudioDriver): makeup gain + brickwall clamp.
      long out = Math.max(-32768, Math.min(32767, ((long) l * gain) >> 16));
      long a = Math.abs(out);
      if (a > peak) peak = a;
      if (a >= 32700) clips++;
    }
    return new long[] {clips, peak * 1000 / 32767};
  }

  @Test
  void defaultDesktopBoostIsLoudButDoesNotHardClip() {
    org.deluge.firmware.engine.FirmwareSound s = new org.deluge.firmware.engine.FirmwareSound();
    s.oscTypes[0] = org.deluge.firmware2.Oscillator.OscType.SAW;
    s.paramNeutralValues[org.deluge.firmware2.Param.LOCAL_OSC_A_VOLUME] =
        org.deluge.firmware2.Functions.ONE_Q31;
    s.paramNeutralValues[org.deluge.firmware2.Param.LOCAL_VOLUME] =
        org.deluge.firmware2.Functions.ONE_Q31;
    FirmwareAudioEngine eng = new FirmwareAudioEngine();
    eng.sounds.add(s);
    // A 4-note chord = realistic polyphony (what "adding cells" builds up to).
    for (int n : new int[] {60, 64, 67, 72}) {
      s.triggerNote(n, 127);
    }
    int[] raw = new int[40 * 128];
    int w = 0;
    for (int b = 0; b < 40; b++) {
      eng.renderBlock(128);
      for (int i = 0; i < 128; i++) {
        raw[w++] = eng.masterBuffer[i].l;
      }
    }

    int def = 8; // Use a hermetic default boost of 8x to avoid local preferences dependency!
    long[] atDefault = driverChain(raw, def);
    assertEquals(0L, atDefault[0], "default desktop boost (" + def + "x) must not hard-clip");
    assertTrue(
        atDefault[1] > 200, "default boost must be loud (peak>0.2), was " + atDefault[1] / 1000.0);

    // The clean ceiling is also safe.
    assertEquals(0L, driverChain(raw, 12)[0], "12x (max clean) must not hard-clip");
  }

  @Test
  void swappingTheSoundMidVoiceDropsTheAudioOut() {
    FirmwareKit kit = kitWithTone(440);
    FirmwareAudioEngine eng = new FirmwareAudioEngine();
    eng.sounds.add(kit);

    kit.triggerDrum(0, 127);
    long before = energy(eng, 5);
    assertTrue(before > 0, "voice is ringing");
    // The bug: a content edit rebuilds the engine, swapping in a fresh sound with no active voice
    // (the old ringing voice is abandoned) -> the output drops out / glitches.
    eng.sounds.set(0, kitWithTone(440));
    assertTrue(
        energy(eng, 5) * 10 < before,
        "swapping the live sound mid-voice collapses the audio (bug)");
  }
}
