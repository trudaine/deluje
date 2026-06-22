package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Phase 0 behavior-freeze for the planned FirmwareSound-facade refactor (see
 * docs/FIRMWARE_BRIDGE_REFACTOR_PLAN.md). Builds a richly-configured synth and pins the model →
 * fw2Sound mapping the bridge performs in {@code syncParamsToFw2} — both 1:1 copies (bucket A) and
 * the non-trivial derivations (bucket B, e.g. fmRatio → modulatorTranspose). The refactor will
 * change HOW these values reach fw2Sound (direct fields instead of a manual copy); this test must
 * stay green throughout, proving the refactor preserves behavior. (Targeted assertions, not a
 * full-field dump, so it is robust to the parallel agent adding unrelated runtime fields.)
 */
class BridgeMappingSnapshotTest {

  private org.deluge.firmware2.Sound buildRichSynth() {
    SynthTrackModel m = new SynthTrackModel("rich");
    m.setSynthMode(1); // FM
    m.setOsc1Type("SAW");
    m.setOsc2Type("SQUARE");
    m.setUnisonNum(3);
    m.setVoicePriority(2);
    m.setMaxVoiceCount(5);
    m.setPolyphony(SynthTrackModel.PolyphonyMode.MONO);
    m.setFmRatio(2.0f); // 2.0 == +12 semitones for FM modulator 1
    m.setModFxType("CHORUS");
    m.addClip(new ClipModel("c", 1, 16));

    ProjectModel project = new ProjectModel();
    project.addTrack(m);
    ProjectModel song = FirmwareFactory.createSong(project);
    FirmwareSound sound = (FirmwareSound) (song.getTracks().get(0).getActiveClip()).getSound();
    sound.syncParamsToFw2();
    return sound.fw2Sound;
  }

  @Test
  void bucketA_oneToOneCopies() {
    var fw2 = buildRichSynth();
    assertEquals(1, fw2.synthMode, "synthMode (FM)");
    assertEquals(org.deluge.firmware2.Oscillator.OscType.SAW, fw2.oscTypes[0], "osc1 type");
    assertEquals(org.deluge.firmware2.Oscillator.OscType.SQUARE, fw2.oscTypes[1], "osc2 type");
    assertEquals(3, fw2.numUnison, "unison count");
    assertEquals(2, fw2.voicePriority, "voice priority");
    assertEquals(5, fw2.maxPolyphony, "max polyphony");
    assertEquals(org.deluge.firmware2.Sound.PolyphonyMode.MONO, fw2.polyphonic, "polyphony mode");
    assertEquals(org.deluge.firmware2.ModFx.ModFXType.CHORUS, fw2.modFXType, "mod FX type");
  }

  @Test
  void bucketB_fmRatioDerivesModulatorTranspose() {
    var fw2 = buildRichSynth();
    // fmRatio 2.0 → 1200 cents → modulator-1 transpose +12 semitones, 0 cents remainder.
    assertEquals(2.0f, fw2.fmRatio1, 1e-6, "fmRatio1 copied");
    assertEquals(12, fw2.modulatorTranspose[0], "fmRatio 2.0 derives +12 semitone transpose");
  }

  @Test
  void patchedParamsPopulated() {
    var fw2 = buildRichSynth();
    assertTrue(
        fw2.patchedParamValues != null && fw2.patchedParamValues.length >= 55,
        "patchedParamValues sized for all params");
  }
}
