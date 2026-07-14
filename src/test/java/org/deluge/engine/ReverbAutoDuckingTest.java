package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.firmware2.Param;
import org.deluge.modulation.patch.PatchCable;
import org.deluge.modulation.patch.PatchSource;
import org.junit.jupiter.api.Test;

/**
 * Guards {@link FirmwareAudioEngine}'s auto-ducking of the reverb send (C {@code
 * AudioEngine::updateReverbParams}, audio_engine.cpp:1251-1317): the sound with the most reverb
 * send that also has a SIDECHAIN→GLOBAL_VOLUME_POST_REVERB_SEND patch cable lends its sidechain
 * shape/attack/release to duck the master reverb. Before this fix, {@code updateReverbParams}
 * tested {@code ge instanceof org.deluge.firmware2.Sound}, but the engine's {@code sounds} list
 * holds {@link FirmwareSound} (a sibling subclass composing a {@code firmware2.Sound}, not a
 * subclass of it) — so the check never matched any real sound and auto-ducking never engaged.
 *
 * <p>The patch cable is set up via {@code paramManager.getPatchCableSet()} (the model layer), not a
 * direct write to {@code fw2Sound.patchCableSet} — {@code renderBlock} calls {@code
 * syncParamsToFw2()} internally every block, which rebuilds {@code fw2Sound.patchCableSet} fresh
 * from the model layer and would silently discard a direct write.
 */
public class ReverbAutoDuckingTest {

  @Test
  void soundWithSidechainCableOnReverbSendEngagesAutoDucking() {
    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    FirmwareSound sound = new FirmwareSound();
    sound.paramNeutralValues[Param.GLOBAL_REVERB_AMOUNT] = 1000000000;
    engine.sounds.add(sound);

    PatchCable cable = new PatchCable();
    cable.from = PatchSource.SIDECHAIN;
    cable.amount = 500000000;
    sound.paramManager.getPatchCableSet().addCable(Param.GLOBAL_VOLUME_POST_REVERB_SEND, cable);

    sound.triggerNote(60, 100);
    engine.renderBlock(128);

    assertNotEquals(
        0,
        engine.reverbSidechainVolumeInEffect,
        "a sound with the most reverb send and a SIDECHAIN cable on its post-reverb volume must"
            + " engage reverb auto-ducking");
  }
}
