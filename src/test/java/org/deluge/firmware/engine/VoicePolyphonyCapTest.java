package org.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.firmware2.Oscillator.OscType;
import org.deluge.firmware2.Param;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for "add many cells while playing → after a while the sound becomes garbage".
 * The per-sound voice cap was a made-up Java value of 64 (vs the C's {@code maxVoiceCount = 8},
 * sound.h:116) and the model's cap was never propagated, so dense playing stacked dozens of voices
 * that summed past the master limiter into a saturated wall. These pin the faithful default and the
 * actual cap behaviour.
 */
class VoicePolyphonyCapTest {

  @Test
  void defaultVoiceCapMatchesHardware() {
    assertEquals(8, new org.deluge.firmware2.Sound().maxPolyphony, "fw2 Sound default");
    assertEquals(8, new FirmwareSound().fw2Sound.maxPolyphony, "bridge FirmwareSound default");
  }

  @Test
  void densePlayingIsCappedAtMaxPolyphony() {
    FirmwareSound s = new FirmwareSound();
    s.oscTypes[0] = OscType.SAW;
    s.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = org.deluge.firmware2.Functions.ONE_Q31;
    s.paramNeutralValues[Param.LOCAL_VOLUME] = org.deluge.firmware2.Functions.ONE_Q31;

    FirmwareAudioEngine eng = new FirmwareAudioEngine();
    eng.sounds.add(s);

    // Slam 30 distinct notes (far more than the cap) as "many cells" would.
    for (int n = 40; n < 70; n++) {
      s.triggerNote(n, 127);
    }
    eng.renderBlock(128); // let allocation/stealing settle

    assertTrue(
        s.getActiveVoiceCount() <= s.fw2Sound.maxPolyphony,
        "active voices ("
            + s.getActiveVoiceCount()
            + ") must not exceed the cap ("
            + s.fw2Sound.maxPolyphony
            + ") — unbounded stacking is the 'garbage wall'");
    assertEquals(8, s.fw2Sound.maxPolyphony, "cap stays at the hardware default");
  }
}
