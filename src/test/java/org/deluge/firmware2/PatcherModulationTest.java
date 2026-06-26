package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class PatcherModulationTest {

  @Test
  void testToPolarityOverflowBug() {
    Patcher.PatchCable cable = new Patcher.PatchCable();
    cable.polarity = Patcher.PatchCable.UNIPOLAR;

    // 1. Minimum value: silence
    int minBipolar = -2147483648; // Integer.MIN_VALUE
    int minUnipolar = cable.toPolarity(minBipolar);
    assertEquals(-1, minUnipolar);

    // 2. Maximum value: peak envelope
    int maxBipolar = 2147483646; // close to Integer.MAX_VALUE
    int maxUnipolar = cable.toPolarity(maxBipolar);

    System.out.println("[TEST] Bipolar Peak: " + maxBipolar);
    System.out.println("[TEST] Unipolar Result: " + maxUnipolar);

    // We assert that the unipolar peak should be a large positive number.
    // This assertion will fail BEFORE our fix (due to overflow wrapping to -2)
    // and will pass AFTER our fix!
    assertTrue(
        maxUnipolar > 1000000000,
        "Unipolar peak should be a large positive number, but got: " + maxUnipolar);
  }

  @Test
  void testVoiceModulationEnvelopeDecay() {
    Sound sound = new Sound();

    // Set up a patch cable from Envelope 1 to LOCAL_MODULATOR_0_VOLUME with maximum depth
    Patcher.PatchCable cable = new Patcher.PatchCable();
    cable.source = PatchSource.ENVELOPE_1.ordinal();
    cable.amount = 2147483647; // 100% depth (Q31 max)
    cable.polarity = Patcher.PatchCable.UNIPOLAR;
    sound.patchCableSet.addCable(Param.LOCAL_MODULATOR_0_VOLUME, cable);

    // Set Envelope 1 parameters: instant attack, maximum decay rate, 0% sustain
    // (so the envelope rises to peak instantly and then decays to absolute silence rapidly)
    sound.patchedParamValues[Param.LOCAL_ENV_1_ATTACK] =
        Integer.MIN_VALUE; // instant attack (goes straight to decay)
    sound.patchedParamValues[Param.LOCAL_ENV_1_DECAY] =
        Integer.MIN_VALUE; // maximum decay rate (instant decay)
    sound.patchedParamValues[Param.LOCAL_ENV_1_SUSTAIN] =
        Integer.MIN_VALUE; // 0% sustain (absolute silence)
    sound.patchedParamValues[Param.LOCAL_ENV_1_RELEASE] = 100000;

    // Set Envelope 0 (master amplitude) parameters to keep the voice active
    sound.patchedParamValues[Param.LOCAL_ENV_0_ATTACK] = Integer.MIN_VALUE; // instant attack
    sound.patchedParamValues[Param.LOCAL_ENV_0_SUSTAIN] = 2147483647; // max sustain (holds forever)
    sound.patchedParamValues[Param.LOCAL_ENV_0_DECAY] = Integer.MIN_VALUE;
    sound.patchedParamValues[Param.LOCAL_ENV_0_RELEASE] = 100000;

    // Set the base modulator volume knob to 0 (so all volume comes from the envelope modulation)
    sound.patchedParamValues[Param.LOCAL_MODULATOR_0_VOLUME] = 0;

    // Initialize voice
    Voice voice = new Voice(sound);
    voice.active = true;

    // Trigger noteOn
    voice.noteOn(60, 100);

    // Check initial patched state (Initial patching resets to base knobs before render)
    Patcher.performInitialPatching(
        sound.patchedParamValues, voice.sourceValues, voice.paramFinalValues);

    // Print cable count and sources to verify mapping
    System.out.println(
        "[TEST] Sound Patch Cable destinations count: " + sound.patchCableSet.destinations.size());
    if (!sound.patchCableSet.destinations.isEmpty()) {
      var dest = sound.patchCableSet.destinations.get(0);
      System.out.println(
          "[TEST] Dest Param ID: " + dest.paramId + " | cables size: " + dest.cables.size());
    }

    // Dynamically capture the base uncabled parameter value
    int baseVol = voice.paramFinalValues[Param.LOCAL_MODULATOR_0_VOLUME];
    System.out.println("[TEST] Base modulator volume: " + baseVol);
    System.out.println(
        "[TEST] Envelope 1 initial source value: "
            + voice.sourceValues[PatchSource.ENVELOPE_1.ordinal()]);

    // Render 1 block (16 samples)
    int[] buffer = new int[32];
    voice.render(buffer, 16, false, false);

    System.out.println(
        "[TEST] Envelope 1 source value after 1 block: "
            + voice.sourceValues[PatchSource.ENVELOPE_1.ordinal()]);
    System.out.println("[TEST] Voice active state after 1 block: " + voice.active);

    // After rendering the first block, the envelope is at its PEAK
    int peakVol = voice.paramFinalValues[Param.LOCAL_MODULATOR_0_VOLUME];
    System.out.println("[TEST] Modulator volume at envelope peak: " + peakVol);

    // Assert that the modulator volume has received a large positive modulation!
    assertTrue(
        peakVol > baseVol + 100000000,
        "Modulator volume at peak ("
            + peakVol
            + ") should be significantly higher than base ("
            + baseVol
            + ")");

    // Render multiple blocks to allow the envelope to decay towards 0
    // (Mathematically, it transitions to 0 sustain at block 30)
    for (int i = 0; i < 50; i++) {
      voice.render(buffer, 16, false, false);
    }

    System.out.println(
        "[TEST] Envelope 1 source value after 20 blocks: "
            + voice.sourceValues[PatchSource.ENVELOPE_1.ordinal()]);
    System.out.println("[TEST] Voice active state after 20 blocks: " + voice.active);

    int decayVol = voice.paramFinalValues[Param.LOCAL_MODULATOR_0_VOLUME];
    System.out.println("[TEST] Modulator volume after decay: " + decayVol);

    // Assert that the volume has decayed significantly from its peak
    assertTrue(
        decayVol < peakVol,
        "Modulator volume should decay over time (decayVol="
            + decayVol
            + ", peakVol="
            + peakVol
            + ")");
  }
}
