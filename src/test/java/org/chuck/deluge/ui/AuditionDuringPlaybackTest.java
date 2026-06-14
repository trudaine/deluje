package org.chuck.deluge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.firmware.engine.FirmwareAudioEngine;
import org.chuck.deluge.firmware.engine.FirmwareSound;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.util.Q31;
import org.chuck.deluge.firmware2.Oscillator.OscType;
import org.chuck.deluge.model.ProjectModel;
import org.junit.jupiter.api.Test;

/**
 * Drives the actual step-press handler headlessly and asserts on the rendered audio (no GUI click,
 * no ears needed): tapping a cell auditions the note when STOPPED, but must NOT audition while the
 * sequencer is PLAYING — a redundant audition during playback is the "garbage when adding cells"
 * bug. All six edit-audition sites share the same isSequencerPlaying() gate; this drives the
 * gesture-coordinator path (handleStepPressed).
 */
public class AuditionDuringPlaybackTest {

  private static FirmwareSound sawSynth() {
    FirmwareSound s = new FirmwareSound();
    s.oscTypes[0] = OscType.SAW;
    s.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = Q31.ONE;
    s.paramNeutralValues[Param.LOCAL_VOLUME] = Q31.ONE;
    return s;
  }

  private static long energy(FirmwareAudioEngine eng) {
    long e = 0;
    for (int b = 0; b < 20; b++) {
      eng.renderBlock(128);
      for (int i = 0; i < 128; i++) {
        e += Math.abs((long) eng.masterBuffer[i].l);
      }
    }
    return e;
  }

  private static SwingGridPanel panel(ChuckVM vm, BridgeContract bridge, FirmwareAudioEngine eng) {
    bridge.register(vm);
    bridge.setTrackType(0, 1); // baseTrackId 0 = synth mode
    eng.sounds.add(sawSynth());
    vm.setGlobalObject(BridgeContract.G_FIRMWARE_ENGINE, eng);
    SwingGridPanel p = new SwingGridPanel(vm, bridge);
    p.setProjectModel(new ProjectModel());
    p.setEditedModelTrack(0);
    return p;
  }

  @Test
  public void tappingACellAuditionsWhenStopped() {
    System.setProperty("chuck.audio.dummy", "true");
    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    FirmwareAudioEngine eng = new FirmwareAudioEngine();
    SwingGridPanel p = panel(vm, bridge, eng);
    vm.setGlobalInt(BridgeContract.G_PLAY, 0L); // stopped

    p.handleStepPressed(0, 0);
    assertTrue(energy(eng) > 0, "tapping a cell while stopped should audition the note (feedback)");
  }

  @Test
  public void tappingACellDoesNotAuditionWhilePlaying() {
    System.setProperty("chuck.audio.dummy", "true");
    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    FirmwareAudioEngine eng = new FirmwareAudioEngine();
    SwingGridPanel p = panel(vm, bridge, eng);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L); // playing

    p.handleStepPressed(0, 0);
    assertEquals(
        0L, energy(eng), "tapping a cell while playing must NOT audition (no garbage note)");
  }
}
