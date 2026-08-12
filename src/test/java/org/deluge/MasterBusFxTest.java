package org.deluge;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.model.ProjectModel;
import org.junit.jupiter.api.Test;

/**
 * Unit tests verifying master-bus FX performance macro wiring (§4.16 item 2). Confirms that
 * song-level performance macros configure master post-processing stages without clobbering
 * per-track parameter neutral values.
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
    assertTrue(
        engine.masterFilterSet.isOn(), "Master filter should activate when LPF frequency lowered");
  }

  @Test
  public void testMasterModFxSync() {
    ProjectModel project = new ProjectModel();
    FirmwareAudioEngine engine = new FirmwareAudioEngine();

    project.setSongParamModFXRate(0.5f);
    project.setSongParamModFXDepth(0.75f);
    engine.syncMasterEffects(project);

    // modFX rate/depth are unipolar params spanning the FULL q31 range, where the minimum
    // (0x80000000) is "off" and ZERO is the midpoint — SongXmlParser reads them with unipolar=true,
    // so the model's 0.0 is the file's 0x80000000. This previously asserted `v * 2147483647`, which
    // squeezes 0..1 into midpoint..max and cannot express "off" at all.
    // (1 LSB below the firmware's 0x40000000 anchor: the scale is 2^32-1, not 2^32.)
    assertEquals(0, engine.masterModFxRate, "0.5 is the q31 midpoint");
    assertEquals(1073741823, engine.masterModFxDepth, "0.75 is ~0x40000000");

    ProjectModel fresh = new ProjectModel();
    FirmwareAudioEngine e2 = new FirmwareAudioEngine();
    e2.syncMasterEffects(fresh);
    assertEquals(Integer.MIN_VALUE, e2.masterModFxRate, "default 0.0 must be the off sentinel");
    assertEquals(Integer.MIN_VALUE, e2.masterModFxDepth, "default 0.0 must be the off sentinel");
    // The morph gates key off the minimum (as sound.cpp:2521-2528 does), so a default song must
    // still leave the master filter OFF now that morph maps to MIN_VALUE rather than 0.
    org.junit.jupiter.api.Assertions.assertFalse(
        e2.masterFilterSet.isOn(), "a default song must not engage the master filter");
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
    // SRR and bitcrush span the FULL q31 param range, where the MINIMUM (0x80000000) is "off" —
    // their enable predicates test for it directly (SrrBitcrush.isSRREnabled /
    // isBitcrushingEnabled).
    // This previously asserted `v * 2147483647`, which maps 0.0 to q31 ZERO — the MIDDLE of the
    // range, ~50% — so both effects were permanently ON for every render that synced a song,
    // including every FidelityScorecardTest render. The expectations below match the firmware's own
    // anchors: 0.25 -> 0xC0000000, 0.5 -> 0x00000000 (see gen_calib.py:81-82 for the same table).
    assertEquals(0xC0000000, engine.masterSrr, "0.25 must be q31 0xC0000000");
    assertEquals(0x00000000, engine.masterBitcrush, "0.5 must be q31 0x00000000 (midpoint)");

    // And the case that actually mattered: the model's default of 0.0 means OFF, and must disable.
    ProjectModel fresh = new ProjectModel();
    FirmwareAudioEngine e2 = new FirmwareAudioEngine();
    e2.syncMasterEffects(fresh);
    assertEquals(Integer.MIN_VALUE, e2.masterSrr, "default 0.0 must map to the off sentinel");
    assertEquals(Integer.MIN_VALUE, e2.masterBitcrush, "default 0.0 must map to the off sentinel");
    org.junit.jupiter.api.Assertions.assertFalse(
        org.deluge.firmware2.SrrBitcrush.isSRREnabled(e2.masterSrr), "SRR must be off by default");
    org.junit.jupiter.api.Assertions.assertFalse(
        org.deluge.firmware2.SrrBitcrush.isBitcrushingEnabled(e2.masterBitcrush),
        "bitcrushing must be off by default");
  }
}
