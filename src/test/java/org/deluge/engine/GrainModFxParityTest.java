package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.firmware2.Functions;
import org.deluge.firmware2.GlobalEffectable;
import org.deluge.firmware2.ModFx;
import org.deluge.firmware2.Param;
import org.deluge.firmware2.Patcher;
import org.deluge.firmware2.Reverb;
import org.deluge.firmware2.Sound;
import org.deluge.model.ProjectModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Parity tests for synth and song-level {@code ModFXType.GRAIN} ({@code
 * GranularProcessor.cpp:47-129}, {@code sound.cpp:2605-2610}, {@code song.cpp:2548-2549}, {@code
 * global_effectable.cpp:1107-1180}, {@code mod_controllable_audio.cpp:159-162, 197-211}).
 */
class GrainModFxParityTest {

  private static final class ImpulseBurstEffectable extends GlobalEffectable {
    private boolean active = true;

    @Override
    protected void renderInternal(int[] buffer, int numSamples, int[] reverbBuffer) {
      if (!active) {
        return;
      }
      for (int i = 0; i < numSamples; i++) {
        int v = ((i & 4) == 0) ? (1 << 20) : -(1 << 20);
        buffer[i * 2] = v;
        buffer[i * 2 + 1] = v;
      }
    }
  }

  @BeforeEach
  void resetNoiseSeed() {
    Functions.resetNoiseSeed();
  }

  @Test
  void synthGrainAtFullModFxDepthDoesNotOverflowMixNegative() {
    // C sound.cpp:2605-2610, mod_controllable_audio.cpp:160, 207, GranularProcessor.cpp:119-126 —
    // GLOBAL_MOD_FX_DEPTH at full knob (0x7FFFFFFF) evaluates to 2,104,533,975 (> 2^30). Passing
    // effectiveModFXDepth << 1 overflowed signed 32-bit int negative and clamped grainVol to 0.
    int fullDepthFinal =
        Patcher.computeFinalValueForParam(Param.GLOBAL_MOD_FX_DEPTH, Integer.MAX_VALUE);
    assertTrue(
        fullDepthFinal > (1 << 30),
        "Full GLOBAL_MOD_FX_DEPTH final value must exceed 2^30 (actual=" + fullDepthFinal + ")");

    Sound sound = new Sound();
    Sound.initParams(sound.patchedParamValues);
    sound.srrParam = Integer.MIN_VALUE;
    sound.bitcrushParam = Integer.MIN_VALUE;
    sound.modFXType = ModFx.ModFXType.GRAIN;
    sound.modFXDepth = fullDepthFinal;
    sound.modFXRateIncrement = Patcher.computeFinalValueForParam(Param.GLOBAL_MOD_FX_RATE, 0);

    // Prime sound.granular via its public processGrainFX API (> 32,768 samples so bufferFull is
    // true and active grains are running), then call sound.renderInternal with no active voices so
    // the dry buffer is zero and any output comes purely from the wet grain tail.
    int[][] primeBuf = new int[128][2];
    int[] primePostFx = {134217728};
    for (int b = 0; b < 270; b++) {
      for (int i = 0; i < 128; i++) {
        int v = ((i & 4) == 0) ? 200_000_000 : -200_000_000;
        primeBuf[i][0] = v;
        primeBuf[i][1] = v;
      }
      primePostFx[0] = 134217728;
      sound.granular.processGrainFX(
          primeBuf, 128, sound.modFXRateIncrement, 1 << 29, 0, 0, primePostFx, true, 120.0f);
    }
    assertTrue(
        sound.granular.hasActiveTail(), "Primed granular processor must have an active tail");

    int[] outBuf = new int[256];
    int[] revBuf = new int[128];
    sound.renderInternal(outBuf, 128, revBuf);

    long wetEnergy = 0;
    for (int sample : outBuf) {
      wetEnergy += Math.abs((long) sample);
    }
    assertTrue(
        wetEnergy > 0L,
        "Synth GrainFX at full ModFX Depth must produce wet grain audio instead of overflowing mix negative");
  }

  @Test
  void songLevelGrainFxProcessesMasterBufferAndFeedsReverbBackdoor() {
    // C song.cpp:2548-2549, global_effectable.cpp:1169-1180, mod_controllable_audio.cpp:159-162,
    // 197-211 — song-level ModFXType::GRAIN runs GranularProcessor::processGrainFX on the master
    // buffer with verbAmount = reverbSendAmount >> 3 and postFXVolume /= sqrt(2).
    ProjectModel dryProject = new ProjectModel();
    dryProject.setSongModFXType("none");
    dryProject.setSongParamReverbAmount(0.0f);
    FirmwareAudioEngine dryEngine = new FirmwareAudioEngine();
    ImpulseBurstEffectable drySource = new ImpulseBurstEffectable();
    dryEngine.sounds.add(drySource);
    dryEngine.syncMasterEffects(dryProject);

    ProjectModel grainProject = new ProjectModel();
    grainProject.setBpm(128.0f);
    grainProject.setSongModFXType("GrainFX");
    grainProject.setModFXCurrentParam("depth");
    grainProject.setSongParamModFXRate(0.65f);
    grainProject.setSongParamModFXDepth(0.85f);
    grainProject.setSongParamModFXOffset(0.5f);
    grainProject.setSongParamModFXFeedback(0.4f);
    grainProject.setReverbModel(1); // MUTABLE so mute() clears reverb state cleanly
    grainProject.setSongParamReverbAmount(0.8f);

    FirmwareAudioEngine grainEngine = new FirmwareAudioEngine();
    ImpulseBurstEffectable grainSource = new ImpulseBurstEffectable();
    grainEngine.sounds.add(grainSource);
    grainEngine.syncMasterEffects(grainProject);

    assertEquals(ModFx.ModFXType.GRAIN, grainEngine.masterModFxType);
    assertEquals(ModFx.ModFXType.GRAIN, grainEngine.getActiveModFXType());
    assertEquals(128.0f, grainEngine.songBpm, 1e-4f);

    // Render 265 blocks (33,920 samples > 32,768) so bufferFull becomes true and grains spawn.
    for (int b = 0; b < 265; b++) {
      dryEngine.renderBlock(128);
      grainEngine.renderBlock(128);
    }

    assertTrue(
        grainEngine.masterGranular.hasActiveTail(),
        "Song-level masterGranular must accumulate audio and enter active tail");

    long diffVsDry = 0;
    for (int i = 0; i < 128; i++) {
      diffVsDry += Math.abs((long) grainEngine.masterBuffer[i].l - dryEngine.masterBuffer[i].l);
    }
    assertTrue(diffVsDry > 0L, "Song-level GrainFX must alter master output compared to dry song");

    // Silence the source, clear reverb tank state, and compare monoReverbBuffer against a
    // reference GranularProcessor run so the grain backdoor send (reverbSendAmount >> 3) is
    // isolated from the post-FX song reverb send (reverbSendAmount >> 1).
    org.deluge.firmware2.GranularProcessor refGranular =
        new org.deluge.firmware2.GranularProcessor();
    int modFXRate =
        Functions.getFinalParameterValueExp(
            Functions.getParamNeutralValue(Param.GLOBAL_MOD_FX_RATE),
            Functions.cableToExpParamShortcut(grainEngine.masterModFxRate));
    int modFXDepth =
        Functions.getFinalParameterValueVolume(
            Functions.getParamNeutralValue(Param.GLOBAL_MOD_FX_DEPTH),
            Functions.cableToLinearParamShortcut(grainEngine.masterModFxDepth));

    grainEngine.masterGranular.clearGrainFXBuffer();
    refGranular.clearGrainFXBuffer();
    grainSource.active = true;

    Functions.resetNoiseSeed();
    for (int b = 0; b < 265; b++) {
      grainEngine.renderBlock(128);
    }

    Functions.resetNoiseSeed();
    int[][] refBlock = new int[128][2];
    long[] refSummed = new long[256];
    ImpulseBurstEffectable refSource = new ImpulseBurstEffectable();
    int[] refPostFxVol = {134217728};
    for (int b = 0; b < 265; b++) {
      java.util.Arrays.fill(refSummed, 0L);
      refSource.renderOutput(refSummed, 128, null);
      for (int i = 0; i < 128; i++) {
        refBlock[i][0] =
            (int) ((refSummed[i * 2] * FirmwareAudioEngine.MASTER_VOLUME_NEUTRAL) >> 27);
        refBlock[i][1] =
            (int) ((refSummed[i * 2 + 1] * FirmwareAudioEngine.MASTER_VOLUME_NEUTRAL) >> 27);
      }
      refPostFxVol[0] = 134217728;
      refGranular.processGrainFX(
          refBlock,
          128,
          modFXRate,
          modFXDepth,
          grainEngine.masterModFxOffset,
          grainEngine.masterModFxFeedback,
          refPostFxVol,
          true,
          128.0f,
          0,
          null);
    }

    grainSource.active = false;
    grainEngine.masterReverb.setModel(Reverb.Model.MUTABLE);
    grainEngine.masterReverb.mute();

    Functions.resetNoiseSeed();
    grainEngine.renderBlock(64);

    Functions.resetNoiseSeed();
    int[][] refTail = new int[64][2];
    refPostFxVol[0] = 134217728;
    refGranular.processGrainFX(
        refTail,
        64,
        modFXRate,
        modFXDepth,
        grainEngine.masterModFxOffset,
        grainEngine.masterModFxFeedback,
        refPostFxVol,
        true,
        128.0f,
        0,
        null);

    int songVolumePostFX =
        Functions.getFinalParameterValueVolume(
                134217728, Functions.cableToLinearParamShortcut(grainEngine.songVolume))
            >> 1;
    int songReverbSendAmount =
        Functions.getFinalParameterValueVolume(
            Functions.getParamNeutralValue(Param.GLOBAL_REVERB_AMOUNT),
            Functions.cableToLinearParamShortcut(grainEngine.songReverbAmount));
    int expectedGrainReverbAmount =
        Functions.multiply_32x32_rshift32(songVolumePostFX, songReverbSendAmount >> 3) << 5;
    int expectedPostFxReverbAmount =
        Functions.multiply_32x32_rshift32(songVolumePostFX, songReverbSendAmount >> 1) << 5;

    long tailMasterEnergy = 0;
    long grainBackdoorOnlyEnergy = 0;
    for (int i = 0; i < 64; i++) {
      tailMasterEnergy +=
          Math.abs((long) grainEngine.masterBuffer[i].l)
              + Math.abs((long) grainEngine.masterBuffer[i].r);

      int wetL = refTail[i][0];
      int wetR = refTail[i][1];
      int expectedBackdoorSend =
          Functions.multiply_32x32_rshift32(wetL + wetR, expectedGrainReverbAmount) << 1;
      int scaledL =
          Functions.lshiftAndSaturate(Functions.multiply_32x32_rshift32(wetL, refPostFxVol[0]), 5);
      int scaledR =
          Functions.lshiftAndSaturate(Functions.multiply_32x32_rshift32(wetR, refPostFxVol[0]), 5);
      int expectedPostFxSend =
          Functions.multiply_32x32_rshift32(scaledL + scaledR, expectedPostFxReverbAmount) << 1;
      int expectedTotalSend =
          (int)
              Math.max(
                  Integer.MIN_VALUE,
                  Math.min(Integer.MAX_VALUE, (long) expectedBackdoorSend + expectedPostFxSend));

      assertEquals(
          expectedTotalSend,
          grainEngine.monoReverbBuffer[i],
          "monoReverbBuffer must equal grain backdoor send (>> 3) + post-FX send (>> 1) at sample "
              + i);
      grainBackdoorOnlyEnergy += Math.abs((long) expectedBackdoorSend);
    }
    assertTrue(
        tailMasterEnergy > 0L, "Song-level GrainFX tail must continue sounding after source stops");
    assertTrue(
        grainBackdoorOnlyEnergy > 0L,
        "Song-level GrainFX backdoor send (reverbSendAmount >> 3) must be non-zero");
  }

  @Test
  void songLevelGrainFxDisabledWhenDepthIsMinimumAndCurrentParamIsDepth() {
    // C global_effectable.cpp:1107-1120 — when currentModFXParam == DEPTH and
    // UNPATCHED_MOD_FX_DEPTH
    // == -2147483648 (0.0 unipolar), getActiveModFXType returns ModFXType::NONE.
    ProjectModel disabledGrain = new ProjectModel();
    disabledGrain.setSongModFXType("GrainFX");
    disabledGrain.setModFXCurrentParam("depth");
    disabledGrain.setSongParamModFXDepth(0.0f);
    FirmwareAudioEngine eDisabled = new FirmwareAudioEngine();
    eDisabled.sounds.add(new ImpulseBurstEffectable());
    eDisabled.syncMasterEffects(disabledGrain);
    assertEquals(ModFx.ModFXType.NONE, eDisabled.getActiveModFXType());

    ProjectModel dry = new ProjectModel();
    dry.setSongModFXType("none");
    FirmwareAudioEngine eDry = new FirmwareAudioEngine();
    eDry.sounds.add(new ImpulseBurstEffectable());
    eDry.syncMasterEffects(dry);

    eDisabled.renderBlock(64);
    eDry.renderBlock(64);

    for (int i = 0; i < 64; i++) {
      assertEquals(
          eDry.masterBuffer[i].l,
          eDisabled.masterBuffer[i].l,
          "Disabled song GrainFX (depth=0, currentParam=depth) must match ModFXType.NONE sample-for-sample at "
              + i);
    }
    assertNotEquals(
        0, eDisabled.masterBuffer[0].l, "Sanity check: rendered master buffer must be non-silent");
  }
}
