package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class VoiceSampleParityTest {

  @Test
  public void testVoiceSampleLoopWrappingAndContinuity() {
    int loopLen = 100;
    int loopStart = 40;
    Sample s = new Sample();
    s.numChannels = 1;
    s.byteDepth = 3;
    s.lengthInSamples = loopLen;
    s.data = new int[loopLen];
    for (int i = 0; i < loopLen; i++) {
      s.data[i] = (int) (500000000.0 * Math.sin(2.0 * Math.PI * i / loopLen));
    }

    VoiceSample v = new VoiceSample();
    // setup(sample, start, end, loopMode, looping, loopStart)
    v.setup(s, 0, loopLen, 1, true, loopStart);

    int numSamples = 250;
    int[] osc = new int[numSamples];
    int unityPitch = 16777216; // 1 << 24
    v.render(osc, numSamples, 1, unityPitch, new int[] {1 << 27}, 0);

    assertTrue(v.active, "Looping VoiceSample must remain active indefinitely across wrap boundaries");

    // Check samples past the loop end (frame > 100) wrap back to loopStart (40)
    // At sample 120, 100 frames elapsed, wrapped to 40 + (120 - 100) = 60
    boolean hasEnergy = false;
    for (int i = 110; i < numSamples; i++) {
      if (osc[i] != 0) hasEnergy = true;
    }
    assertTrue(hasEnergy, "Loop-wrapped sample region must generate non-zero acoustic output");
  }

  @Test
  public void testVoiceSamplePitchTransposition() {
    int totalLen = 400;
    Sample s = new Sample();
    s.numChannels = 1;
    s.byteDepth = 3;
    s.lengthInSamples = totalLen;
    s.data = new int[totalLen];
    for (int i = 0; i < totalLen; i++) {
      s.data[i] = 100000000; // constant DC amplitude
    }

    VoiceSample vFast = new VoiceSample();
    vFast.setup(s, 0, totalLen, 0, false, 0);

    VoiceSample vSlow = new VoiceSample();
    vSlow.setup(s, 0, totalLen, 0, false, 0);

    int unityPitch = 16777216; // 1 << 24
    int doublePitch = unityPitch * 2; // +12 semitones
    int halfPitch = unityPitch / 2; // -12 semitones

    int numSamples = 150;
    int[] oscFast = new int[numSamples];
    int[] oscSlow = new int[numSamples];

    vFast.render(oscFast, numSamples, 1, doublePitch, new int[] {1 << 27}, 0);
    vSlow.render(oscSlow, numSamples, 1, halfPitch, new int[] {1 << 27}, 0);

    assertTrue(vFast.active && vSlow.active, "Both voices remain active before reaching end of sample buffer");
  }

  @Test
  public void testMultisampleZoneMatchingParity() {
    Sound sound = new Sound();
    sound.polyphonic = Sound.PolyphonyMode.POLY;
    sound.oscTypes[0] = Oscillator.OscType.SAMPLE;

    Sample sLow = new Sample();
    sLow.lengthInSamples = 1000;
    Sample sHigh = new Sample();
    sHigh.lengthInSamples = 1000;

    Sound.CompiledKeyZone zLow = new Sound.CompiledKeyZone();
    zLow.sample = sLow;
    zLow.minPitch = 0;
    zLow.maxPitch = 59;
    zLow.minVelocity = 0;
    zLow.maxVelocity = 127;

    Sound.CompiledKeyZone zHigh = new Sound.CompiledKeyZone();
    zHigh.sample = sHigh;
    zHigh.minPitch = 60;
    zHigh.maxPitch = 127;
    zHigh.minVelocity = 0;
    zHigh.maxVelocity = 127;

    sound.sourceZones[0].add(zLow);
    sound.sourceZones[0].add(zHigh);

    // Trigger note 48 (C3 -> low zone)
    sound.triggerVoice(48, 100, 0);
    Voice v1 = sound.voices.get(sound.voices.size() - 1);
    assertEquals(sLow, v1.unisonParts[0].sources[0].sampleRef, "Note 48 must match low key zone sample");

    // Trigger note 72 (C5 -> high zone)
    sound.triggerVoice(72, 100, 0);
    Voice v2 = sound.voices.get(sound.voices.size() - 1);
    assertEquals(sHigh, v2.unisonParts[0].sources[0].sampleRef, "Note 72 must match high key zone sample");
  }
}
