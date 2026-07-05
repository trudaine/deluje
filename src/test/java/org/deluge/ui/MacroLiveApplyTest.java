package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.BridgeContract;
import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareSound;
import org.deluge.firmware2.Oscillator.OscType;
import org.deluge.firmware2.Param;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Verifies that sweeping macro values on SwingGridPanel immediately updates the parameters
 * on the live sound engine.
 */
public class MacroLiveApplyTest {

  private static FirmwareSound synthSound() {
    FirmwareSound s = new FirmwareSound();
    s.oscTypes[0] = OscType.SAW;
    s.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = org.deluge.firmware2.Functions.ONE_Q31;
    s.paramNeutralValues[Param.LOCAL_VOLUME] = org.deluge.firmware2.Functions.ONE_Q31;
    // LPF frequency starts at default neutral
    s.paramNeutralValues[Param.LOCAL_LPF_FREQ] = 0x10000000;
    return s;
  }

  @Test
  public void sweepingMacroUpdatesLiveSoundParameters() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();
    FirmwareAudioEngine eng = new FirmwareAudioEngine();
    FirmwareSound sound = synthSound();
    eng.sounds.add(sound);
    bridge.setGlobalObject(BridgeContract.G_FIRMWARE_ENGINE, eng);

    ProjectModel project = new ProjectModel();
    SynthTrackModel track = new SynthTrackModel("Synth Track");
    project.addTrack(track);

    SwingGridPanel p = new ClipGridPanel(bridge);
    p.setProjectModel(project);
    p.setEditedModelTrack(0);

    // Initial LPF frequency check
    int originalLpfKnob = sound.paramKnobs[Param.LOCAL_LPF_FREQ];

    // Sweep LPF cutoff macro (Col index 3) to 0.4 (close filter)
    p.setMacroValue(3, 0.4, track);

    // Verify model was updated
    float expectedFreq = (float) (20.0 * Math.pow(1000.0, 0.4));
    assertEquals(expectedFreq, track.getLpfFreq(), 1e-3f, "Model LPF frequency should be updated");

    // Verify live sound parameters were updated via applyModelToLiveSound
    assertTrue(
        sound.paramKnobs[Param.LOCAL_LPF_FREQ] < originalLpfKnob,
        "LPF knob on live sound should drop after macro sweep");
  }
}
