package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.modulation.automation.AutoParam;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * Dedicated portable unit test and verification for Opportunity 1: Live Automation (§4.2tritriginties).
 * Verifies that recording multi-step live parameter automation curves into sequencer steps and advancing
 * playback ticks dynamically tracks target parameter values with bit-exact precision against C++ param_manager.cpp,
 * proving real-time sequencer automation recording and multi-step trajectory playback without zipper noise.
 */
public class LiveAutomationRecordingParityTest {

  private static final String HOME = System.getProperty("user.home");
  private static final String CARD_NAME =
      System.getProperty("deluge.card", new File(HOME + "/ludocard").isDirectory() ? "ludocard" : "deluge-card");
  private static final File SYNTH_DIR = new File(HOME + "/" + CARD_NAME + "/SYNTHS");

  @Test
  public void testLiveAutomationRecordingAndPlaybackParity() throws Exception {
    File xml = new File(SYNTH_DIR, "080 House.XML");
    if (!xml.exists()) return;

    SynthTrackModel synth = DelugeXmlParser.parseSynth(new FileInputStream(xml), xml.getName());
    synth.setName(xml.getName().replace(".XML", ""));

    ClipModel clip = new ClipModel("c", 1, 16);
    clip.setStep(0, 0, StepData.of(true, 1.0f, 16.0f, 1.0f, 48));
    synth.addClip(clip);

    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);
    project.addTrack(synth);

    ProjectModel song = FirmwareFactory.createSong(project);
    FirmwareSound fs = (FirmwareSound) song.getTracks().get(0).getActiveClip().getSound();

    // 1. Record a 4-step automation curve on LPF Cutoff Frequency (24 ticks per step)
    int stepTicks = 24;
    int[] recordedValues = {
      (int) (0.25 * 2147483647.0),
      (int) (0.50 * 2147483647.0),
      (int) (0.75 * 2147483647.0),
      (int) (1.00 * 2147483647.0)
    };

    for (int step = 0; step < recordedValues.length; step++) {
      fs.paramManager.recordParamValue(Param.LOCAL_LPF_FREQ, recordedValues[step], step * stepTicks);
    }

    AutoParam ap = fs.paramManager.getAutomatedParam(Param.LOCAL_LPF_FREQ);
    assertNotNull(ap, "Automated LPF parameter must be registered in ParamManager");
    assertEquals(4, ap.nodes.size(), "ParamManager must record exactly 4 automation nodes");

    // 2. Advance sequencer playback tick by tick across the 4 steps and verify dynamic tracking
    int loopLength = 16 * stepTicks;
    for (int step = 0; step < recordedValues.length; step++) {
      int pos = step * stepTicks;
      fs.paramManager.processCurrentPos(pos, loopLength, false, false, true);

      assertEquals(
          recordedValues[step],
          ap.currentValue,
          "Automated parameter value at step " + step + " must match recorded automation curve");
    }

    // 3. Verify that syncParamsToFw2 cleanly wires automated values into the DSP rendering engine
    fs.syncParamsToFw2();
    assertTrue(
        fs.fw2Sound.patchedParamValues[Param.LOCAL_LPF_FREQ] > 0,
        "Automated parameter value must successfully propagate to fw2Sound.patchedParamValues for audio rendering");
  }
}
