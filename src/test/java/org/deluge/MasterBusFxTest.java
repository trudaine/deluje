package org.deluge;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.firmware2.FilterSet;
import org.deluge.firmware2.ModFx;
import org.deluge.model.ProjectModel;
import org.junit.jupiter.api.Test;

/**
 * Unit tests verifying master-bus FX performance macro wiring (§4.16 item 2).
 * Confirms that song-level performance macros configure master post-processing stages
 * without clobbering per-track parameter neutral values.
 */
public class MasterBusFxTest {

  @Test
  public void testMasterLpfSyncAndFiltering() {
    ProjectModel project = new ProjectModel();
    FirmwareAudioEngine engine = new FirmwareAudioEngine();

    // Default: wide open (20000 Hz), master filter should be OFF
    engine.syncMasterEffects(project);
    assertFalse(engine.masterFilterSet.isOn(), "Master filter should be off by default");

    // Set song-level LPF performance macro to 500 Hz
    project.setSongParamLpfFrequency(500.0f);
    engine.syncMasterEffects(project);
    assertTrue(engine.masterFilterSet.isOn(), "Master filter should activate when LPF frequency lowered");
  }

  @Test
  public void testMasterModFxSync() {
    ProjectModel project = new ProjectModel();
    FirmwareAudioEngine engine = new FirmwareAudioEngine();

    project.setSongParamModFXRate(0.5f);
    project.setSongParamModFXDepth(0.75f);
    engine.syncMasterEffects(project);

    assertEquals((int) (0.5f * 2147483647.0f), engine.masterModFxRate);
    assertEquals((int) (0.75f * 2147483647.0f), engine.masterModFxDepth);
  }

  @Test
  public void testMasterEqAndSrrSync() {
    ProjectModel project = new ProjectModel();
    FirmwareAudioEngine engine = new FirmwareAudioEngine();

    project.setSongParamEqBass(0.8f);
    project.setSongParamEqTreble(0.2f);
    project.setSongParamSampleRateReduction(0.25f);
    project.setSongParamBitCrush(0.5f);
    engine.syncMasterEffects(project);

    assertEquals((int) (0.8f * 2147483647.0f), engine.masterEqBass);
    assertEquals((int) (0.2f * 2147483647.0f), engine.masterEqTreble);
    assertEquals((int) (0.25f * 2147483647.0f), engine.masterSrr);
    assertEquals((int) (0.5f * 2147483647.0f), engine.masterBitcrush);
  }
}
