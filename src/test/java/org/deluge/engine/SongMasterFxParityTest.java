package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.deluge.BridgeContract;
import org.deluge.firmware2.GlobalEffectable;
import org.deluge.firmware2.ModFx;
import org.deluge.firmware2.SrrBitcrush;
import org.deluge.model.ProjectModel;
import org.deluge.project.ProjectSerializer;
import org.deluge.xml.SongXmlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parity tests for song-level master FX across XML, {@link ProjectModel}, {@link
 * FirmwareAudioEngine}, and {@link PureFirmwareEngine} (C {@code global_effectable.cpp:1063-1181},
 * {@code mod_controllable_audio.cpp:110-192, 279-306, 407-414, 736-739}, {@code
 * audio_engine.cpp:1082-1121}).
 */
class SongMasterFxParityTest {

  private static final class ConstantToneEffectable extends GlobalEffectable {
    private final int sampleValue;

    ConstantToneEffectable(int sampleValue) {
      this.sampleValue = sampleValue;
    }

    @Override
    protected void renderInternal(int[] buffer, int numSamples, int[] reverbBuffer) {
      for (int i = 0; i < numSamples; i++) {
        // Simple alternating square wave so shelving EQ and chorus delay lines have AC content
        int v = ((i & 4) == 0) ? sampleValue : -sampleValue;
        buffer[i * 2] = v;
        buffer[i * 2 + 1] = v;
      }
    }
  }

  @Test
  void songModFxTypeAndClippingAmountRoundTripThroughXml(@TempDir Path tempDir) throws Exception {
    ProjectModel project = new ProjectModel();
    project.setSongModFXType("StereoChorus");
    project.setModFXCurrentParam("depth");
    project.setSongClippingAmount(3);
    project.setSongParamEqBassFrequency(0.5f);
    project.setSongParamEqTrebleFrequency(0.75f);

    Path xmlPath = tempDir.resolve("SONG_MASTER_FX.XML");
    ProjectSerializer.save(project, xmlPath.toFile());
    String xmlText = java.nio.file.Files.readString(xmlPath);
    assertTrue(
        xmlText.contains("modFXType=\"StereoChorus\""),
        "Expected firmware modFXType=\"StereoChorus\" on <song> (functions.cpp:891)");
    assertTrue(
        xmlText.contains("clippingAmount=\"3\""),
        "Expected clippingAmount=\"3\" on <song> when non-zero (mod_controllable_audio.cpp:412)");

    ProjectModel loaded = SongXmlParser.parseSong(xmlPath.toFile());
    assertEquals("StereoChorus", loaded.getSongModFXType());
    assertEquals("depth", loaded.getModFXCurrentParam());
    assertEquals(3, loaded.getSongClippingAmount());
    assertEquals(0.5f, loaded.getSongParamEqBassFrequency(), 1e-4f);
    assertEquals(0.75f, loaded.getSongParamEqTrebleFrequency(), 1e-4f);

    // Default clippingAmount == 0 must be omitted on <song> (mod_controllable_audio.cpp:411-413)
    ProjectModel defaultClipProject = new ProjectModel();
    Path defaultXmlPath = tempDir.resolve("SONG_DEFAULT_CLIP.XML");
    ProjectSerializer.save(defaultClipProject, defaultXmlPath.toFile());
    String defaultXmlText = java.nio.file.Files.readString(defaultXmlPath);
    assertFalse(
        defaultXmlText.contains("clippingAmount="),
        "clippingAmount must be omitted when 0 (mod_controllable_audio.cpp:411-413)");
    assertTrue(
        defaultXmlText.contains("modFXType=\"none\""),
        "Default song modFXType must serialize as \"none\" (functions.cpp:899)");
  }

  @Test
  void pureFirmwareEngineDefaultSyncLeavesSrrAndBitcrushOffAndCentersEqFreq() {
    PureFirmwareEngine pureEngine = new PureFirmwareEngine();
    BridgeContract bridge = new BridgeContract();
    bridge.clear();

    ProjectModel project = new ProjectModel();
    project.setSongModFXType("flanger");
    project.setModFXCurrentParam("feedback");
    project.setSongClippingAmount(2);
    pureEngine.setProject(project);
    bridge.setGlobalFloat(BridgeContract.G_SP_PAN, -0.5);

    pureEngine.syncFromBridge(bridge);
    FirmwareAudioEngine audioEngine = pureEngine.getAudioEngine();

    // Default 0.0 unipolar SRR / bitcrush must map to 0x80000000 (off), not 0 (~50% on).
    assertEquals(Integer.MIN_VALUE, audioEngine.masterSrr);
    assertEquals(Integer.MIN_VALUE, audioEngine.masterBitcrush);
    assertFalse(SrrBitcrush.isSRREnabled(audioEngine.masterSrr));
    assertFalse(SrrBitcrush.isBitcrushingEnabled(audioEngine.masterBitcrush));

    // Default 0.5 unipolar EQ corner frequencies must map to 0 (neutral Q31 midpoint).
    assertEquals(0, audioEngine.masterEqBassFreq);
    assertEquals(0, audioEngine.masterEqTrebleFreq);

    // Song ModFX type, current param, clipping amount, and pan must sync through.
    assertEquals(ModFx.ModFXType.FLANGER, audioEngine.masterModFxType);
    assertEquals("feedback", audioEngine.masterModFxCurrentParam);
    assertEquals(2, audioEngine.songClippingAmount);
    assertEquals((int) (-0.5f * 2147483647.0f), audioEngine.songPan);
  }

  @Test
  void getActiveModFxTypeDisablesEffectWhenCurrentParamIsAtMinimum() {
    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.masterModFxType = ModFx.ModFXType.CHORUS_STEREO;

    // C global_effectable.cpp:1112-1119 — DEPTH at -2147483648 disables ModFX.
    engine.masterModFxCurrentParam = "depth";
    engine.masterModFxDepth = Integer.MIN_VALUE;
    assertEquals(ModFx.ModFXType.NONE, engine.getActiveModFXType());

    engine.masterModFxDepth = 0;
    assertEquals(ModFx.ModFXType.CHORUS_STEREO, engine.getActiveModFXType());

    // FEEDBACK at -2147483648 disables ModFX when currentModFXParam == FEEDBACK.
    engine.masterModFxCurrentParam = "feedback";
    engine.masterModFxFeedback = Integer.MIN_VALUE;
    assertEquals(ModFx.ModFXType.NONE, engine.getActiveModFXType());

    // OFFSET at -2147483648 disables ModFX when currentModFXParam == OFFSET.
    engine.masterModFxCurrentParam = "offset";
    engine.masterModFxOffset = Integer.MIN_VALUE;
    assertEquals(ModFx.ModFXType.NONE, engine.getActiveModFXType());
  }

  @Test
  void songStereoChorusAndSongPanAffectRenderedMasterOutput() {
    // 1. Song pan hard left vs hard right (C audio_engine.cpp:1098-1110).
    // Render two blocks so the master compressor's first-sample tanH working value
    // (rms_feedback.cpp
    // :66-73) has settled.
    ProjectModel leftProject = new ProjectModel();
    leftProject.setSongParamPan(-1.0f);
    FirmwareAudioEngine leftEngine = new FirmwareAudioEngine();
    leftEngine.sounds.add(new ConstantToneEffectable(1 << 20));
    leftEngine.syncMasterEffects(leftProject);
    leftEngine.renderBlock(64);
    leftEngine.renderBlock(64);

    long leftEnergyL = 0;
    long leftEnergyR = 0;
    for (int i = 0; i < 64; i++) {
      leftEnergyL += Math.abs((long) leftEngine.masterBuffer[i].l);
      leftEnergyR += Math.abs((long) leftEngine.masterBuffer[i].r);
    }
    assertTrue(leftEnergyL > 0, "Hard-left song pan must produce left channel output");
    assertEquals(0L, leftEnergyR, "Hard-left song pan must silence right channel output");

    // 2. Song StereoChorus active vs inactive (C global_effectable.cpp:1150-1180)
    ProjectModel dryProject = new ProjectModel();
    FirmwareAudioEngine dryEngine = new FirmwareAudioEngine();
    dryEngine.sounds.add(new ConstantToneEffectable(1 << 20));
    dryEngine.syncMasterEffects(dryProject);
    for (int b = 0; b < 4; b++) {
      dryEngine.renderBlock(64);
    }

    ProjectModel chorusProject = new ProjectModel();
    chorusProject.setSongModFXType("StereoChorus");
    chorusProject.setModFXCurrentParam("depth");
    chorusProject.setSongParamModFXRate(0.6f);
    chorusProject.setSongParamModFXDepth(0.8f);
    chorusProject.setSongParamModFXOffset(0.5f);
    FirmwareAudioEngine chorusEngine = new FirmwareAudioEngine();
    chorusEngine.sounds.add(new ConstantToneEffectable(1 << 20));
    chorusEngine.syncMasterEffects(chorusProject);
    for (int b = 0; b < 4; b++) {
      chorusEngine.renderBlock(64);
    }

    long stereoDiffSum = 0;
    long dryVsChorusDiff = 0;
    for (int i = 0; i < 64; i++) {
      stereoDiffSum +=
          Math.abs((long) chorusEngine.masterBuffer[i].l - chorusEngine.masterBuffer[i].r);
      dryVsChorusDiff +=
          Math.abs((long) chorusEngine.masterBuffer[i].l - dryEngine.masterBuffer[i].l);
    }
    assertTrue(
        stereoDiffSum > 0,
        "Active song StereoChorus must create stereo L/R decorrelation on a mono source");
    assertTrue(dryVsChorusDiff > 0, "Active song StereoChorus must alter master output vs dry");
  }

  @Test
  void highSongBitcrushAppliesPostFxVolumeShift() {
    // C mod_controllable_audio.cpp:287-291 — positivePreset > 4 shifts postFXVolume right by
    // (positivePreset - 4), which flows into masterCompressor.render (audio_engine.cpp:1089-1121).
    ProjectModel moderateCrush = new ProjectModel();
    moderateCrush.setSongParamBitCrush(0.55f); // positivePreset == 4 -> no shift
    FirmwareAudioEngine e4 = new FirmwareAudioEngine();
    e4.sounds.add(new ConstantToneEffectable(1 << 20));
    e4.syncMasterEffects(moderateCrush);
    e4.renderBlock(64);

    ProjectModel heavyCrush = new ProjectModel();
    heavyCrush.setSongParamBitCrush(0.95f); // positivePreset == 7 -> >>= 3
    FirmwareAudioEngine e7 = new FirmwareAudioEngine();
    e7.sounds.add(new ConstantToneEffectable(1 << 20));
    e7.syncMasterEffects(heavyCrush);
    e7.renderBlock(64);

    long sum4 = 0;
    long sum7 = 0;
    for (int i = 0; i < 64; i++) {
      sum4 += Math.abs((long) e4.masterBuffer[i].l);
      sum7 += Math.abs((long) e7.masterBuffer[i].l);
    }
    assertTrue(
        sum7 < sum4,
        "Heavy song bitcrush (preset > 4) must reduce postFXVol gain (sum7="
            + sum7
            + ", sum4="
            + sum4
            + ")");
  }
}
