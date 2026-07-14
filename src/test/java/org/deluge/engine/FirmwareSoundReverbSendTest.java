package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.firmware2.Param;
import org.junit.jupiter.api.Test;

/**
 * Guards {@link FirmwareSound#computeReverbSendAmount}, which overrides {@link
 * org.deluge.firmware2.GlobalEffectable#computeReverbSendAmount}'s Kit/AudioOutput-style formula
 * with the correct Sound-specific one (C sound.cpp:2428-2431: a plain multiply against the
 * already-Patcher-resolved {@code paramFinalValues[GLOBAL_REVERB_AMOUNT]}). Before this fix, every
 * FirmwareSound inherited the Kit formula against a stale {@code reverbSendKnob} snapshot set once
 * at factory build time — re-applying the volume curve to an already-curved value and never
 * tracking patch-cable modulation of the send.
 */
public class FirmwareSoundReverbSendTest {

  @Test
  void tracksLivePatchedValueNotTheStaleKnobSnapshot() {
    FirmwareSound fs = new FirmwareSound();
    // Deliberately leave the stale field at its "off" default, and set only the live per-block
    // patched value the Patcher would have resolved from a patch cable this block - the override
    // must read the latter, not the former.
    fs.reverbSendKnob = Integer.MIN_VALUE;
    fs.fw2Sound.patchedParamValues[Param.GLOBAL_REVERB_AMOUNT] = Integer.MAX_VALUE;

    int amount = fs.computeReverbSendAmount(67108864);
    assertTrue(
        amount > 0,
        "a full-scale live patched reverb amount must produce a positive send, even with a"
            + " stale/off reverbSendKnob; got "
            + amount);
  }

  @Test
  void offSendProducesNoReverb() {
    FirmwareSound fs = new FirmwareSound();
    fs.fw2Sound.patchedParamValues[Param.GLOBAL_REVERB_AMOUNT] = Integer.MIN_VALUE;
    assertEquals(0, fs.computeReverbSendAmount(67108864));
  }
}
