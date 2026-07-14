package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.firmware2.Oscillator.OscType;
import org.deluge.firmware2.Param;
import org.junit.jupiter.api.Test;

/**
 * End-to-end proof that a sound's reverb SEND is routed into the (now-working) master reverb:
 * GlobalEffectable.processReverbSendAndVolume -> monoReverbBuffer -> FirmwareAudioEngine ->
 * masterReverb.process -> master output. Guards the full chain behind the reverb-output
 * (setPanLevels) fix; with reverb send 0 the output must match the no-reverb engine.
 *
 * <p>Setup uses {@code paramNeutralValues[GLOBAL_REVERB_AMOUNT]}, not the legacy {@code
 * reverbSendKnob} field Sound no longer reads (see {@link FirmwareSoundReverbSendTest}) — and not a
 * direct {@code fw2Sound.patchedParamValues} write either, since {@code triggerNote} calls {@code
 * syncParamsToFw2()}, which rebuilds that array fresh from {@code paramNeutralValues}/{@code
 * paramKnobs} and would silently discard a direct assignment made beforehand.
 */
class ReverbSendRoutingTest {

  private long[] render(int reverbSendKnob) {
    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.masterReverb.setRoomSize(0.85f);
    engine.masterReverb.setDamping(0.3f);
    engine.masterReverb.setWidth(1.0f);

    FirmwareSound synth = new FirmwareSound();
    synth.oscTypes[0] = OscType.SINE;
    synth.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = org.deluge.firmware2.Functions.ONE_Q31;
    synth.paramNeutralValues[Param.LOCAL_VOLUME] = org.deluge.firmware2.Functions.ONE_Q31;
    synth.paramNeutralValues[Param.GLOBAL_REVERB_AMOUNT] = reverbSendKnob;
    engine.sounds.add(synth);

    synth.triggerNote(60, 127);
    long noteOnEnergy = 0;
    long sig = 1469598103934665603L;
    for (int blk = 0; blk < 80; blk++) {
      engine.renderBlock(128);
      for (int i = 0; i < 128; i++) {
        noteOnEnergy += Math.abs((long) engine.masterBuffer[i].l);
        sig = (sig ^ (engine.masterBuffer[i].l & 0xFFFFFFFFL)) * 1099511628211L;
      }
    }
    synth.releaseNote(60);
    synth.muted = true;
    long tailEnergy = 0;
    for (int blk = 0; blk < 400; blk++) {
      engine.renderBlock(128);
      for (int i = 0; i < 128; i++) tailEnergy += Math.abs((long) engine.masterBuffer[i].l);
    }
    return new long[] {noteOnEnergy, tailEnergy, sig};
  }

  @Test
  void reverbSendRoutesToMasterReverb() {
    long[] dry = render(Integer.MIN_VALUE); // INT_MIN knob = off (dry)
    long[] wet = render(Integer.MAX_VALUE); // max reverb-send knob
    System.out.println(
        "DIAG REVERB: dry[0]="
            + dry[0]
            + " wet[0]="
            + wet[0]
            + " dry[1]="
            + dry[1]
            + " wet[1]="
            + wet[1]);

    // The send path must change the output vs a dry render...
    assertNotEquals(dry[2], wet[2], "reverb send must alter the master output signature");
    // The release tail should ring longer with reverb than the dry-only release.
    assertTrue(
        wet[1] > dry[1],
        "reverb tail should exceed the dry release tail (wet=" + wet[1] + " dry=" + dry[1] + ")");
  }
}
