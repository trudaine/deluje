package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.deluge.BridgeContract;
import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.PureFirmwareEngine;
import org.deluge.model.FilterMode;
import org.deluge.model.ProjectModel;
import org.deluge.project.ProjectSerializer;
import org.deluge.xml.SongXmlParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for Stage 6 (#2 — CALIB Noise & HPF upstream parity):
 *
 * <ul>
 *   <li>Mono vs stereo voice filtering ({@code FilterSet.renderLong} vs {@code renderLongStereo}, C
 *       {@code voice.cpp:1515-1670}, {@code filter_set.cpp:25-99}).
 *   <li>Voice noise ordering and {@code stereoUnison} PRNG cadence (C {@code voice.cpp:1129-1156,
 *       1219-1253, 1277, 1372-1382}).
 *   <li>Ringmod {@code amplitudeForRingMod} raw wrapping {@code << 4} (C {@code voice.cpp:1327}).
 *   <li>Song-level {@code masterFilterSet} parameter curves, {@code doLPF}/{@code doHPF} gates, and
 *       XML round-trip for {@code lpfMode}, {@code hpfMode}, and {@code filterRoute} (C {@code
 *       global_effectable.cpp:752-785}, {@code mod_controllable_audio.cpp:61-63, 408-411,
 *       723-733}).
 * </ul>
 */
class CalibNoiseHpfParityTest {

  private static final int NUM_SAMPLES = 32;

  @BeforeEach
  void resetPrng() {
    Functions.resetNoiseSeed();
  }

  @AfterEach
  void resetOverrides() {
    Voice.testStartPhaseOverrideOsc1.set(-2);
    Voice.testStartPhaseOverrideOsc2.set(-2);
    Functions.resetNoiseSeed();
  }

  private static Sound makeInitializedSound() {
    Sound sound = new Sound();
    Sound.initParams(sound.patchedParamValues);
    Sound.setupAsBlankSynth(sound.patchedParamValues, sound, false);
    sound.bitcrushParam = Integer.MIN_VALUE;
    sound.srrParam = Integer.MIN_VALUE;
    return sound;
  }

  /**
   * Counts how many {@link Functions#getNoise()} calls elapsed since {@link
   * Functions#resetNoiseSeed()} by comparing the next output of {@link Functions#getNoise()}.
   */
  private static int prngStepsSinceReset(int nextNoiseValue) {
    Functions.resetNoiseSeed();
    for (int steps = 0; steps <= 4096; steps++) {
      int candidate = Functions.getNoise();
      if (candidate == nextNoiseValue) {
        return steps;
      }
    }
    fail("nextNoiseValue was not reached within 4096 PRNG steps from resetNoiseSeed()");
    return -1;
  }

  @Test
  void monoFilterSetRenderLongConsumesOneNoisePerSampleAndBlendsOver512Samples() {
    FilterSet monoFs = new FilterSet();
    monoFs.reset();
    // Configure OFF first, then TRANSISTOR_24DB so reset(true) triggers the 512-sample dry/wet fade
    monoFs.setConfig(
        2000000,
        0,
        FilterSet.FilterMode.OFF,
        0,
        2672947,
        0,
        FilterSet.FilterMode.OFF,
        0,
        1 << 28,
        FilterRoute.HIGH_TO_LOW);
    monoFs.setConfig(
        2000000,
        0,
        FilterSet.FilterMode.TRANSISTOR_24DB,
        0,
        2672947,
        0,
        FilterSet.FilterMode.OFF,
        0,
        1 << 28,
        FilterRoute.HIGH_TO_LOW);

    int[] monoBuf = new int[NUM_SAMPLES];
    Arrays.fill(monoBuf, 1 << 22);
    Functions.resetNoiseSeed();
    monoFs.renderLong(monoBuf, NUM_SAMPLES);
    int nextAfterMono = Functions.getNoise();
    assertEquals(
        NUM_SAMPLES,
        prngStepsSinceReset(nextAfterMono),
        "C lpladder.cpp:253-279 — mono 24dB LPF must call getNoise() once per sample");

    FilterSet stereoFs = new FilterSet();
    stereoFs.reset();
    stereoFs.setConfig(
        2000000,
        0,
        FilterSet.FilterMode.OFF,
        0,
        2672947,
        0,
        FilterSet.FilterMode.OFF,
        0,
        1 << 28,
        FilterRoute.HIGH_TO_LOW);
    stereoFs.setConfig(
        2000000,
        0,
        FilterSet.FilterMode.TRANSISTOR_24DB,
        0,
        2672947,
        0,
        FilterSet.FilterMode.OFF,
        0,
        1 << 28,
        FilterRoute.HIGH_TO_LOW);
    int[] stereoBuf = new int[NUM_SAMPLES * 2];
    Arrays.fill(stereoBuf, 1 << 22);
    Functions.resetNoiseSeed();
    stereoFs.renderLongStereo(stereoBuf, NUM_SAMPLES);
    int nextAfterStereo = Functions.getNoise();
    assertEquals(
        NUM_SAMPLES * 2,
        prngStepsSinceReset(nextAfterStereo),
        "C lpladder.cpp:281-319 — stereo 24dB LPF calls getNoise() twice per stereo frame");
    assertTrue(
        monoFs.lpLadder.dryFade > stereoFs.lpLadder.dryFade,
        "C filter.h:68-103 — mono filter updates dryFade once per sample (512-sample fade), slower than stereo");
  }

  @Test
  void monoVoiceUsesMonoFilterAndMonoSaturationState() {
    Voice.testStartPhaseOverrideOsc1.set(0);
    Voice.testStartPhaseOverrideOsc2.set(0);
    Sound sound = makeInitializedSound();
    sound.synthMode = 0; // SUBTRACTIVE
    sound.lpfMode = FilterSet.FilterMode.TRANSISTOR_24DB;
    sound.hpfMode = FilterSet.FilterMode.OFF;
    sound.oscTypes[0] = Oscillator.OscType.SAW;
    sound.oscTypes[1] = Oscillator.OscType.SAW;
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    sound.patchedParamValues[Param.LOCAL_NOISE_VOLUME] = Integer.MIN_VALUE;
    sound.patchedParamValues[Param.LOCAL_VOLUME] = 134217728;
    sound.patchedParamValues[Param.LOCAL_LPF_FREQ] = 0;
    sound.clippingAmount = 2;
    sound.numUnison = 1;
    sound.unisonStereoSpread = 0;
    sound.calculateEffectiveVolume();

    Voice voice = new Voice(sound);
    voice.noteOn(60, 100, -1, null);
    voice.lastSaturationTanHWorkingValue[0] = 0x80000000;
    voice.lastSaturationTanHWorkingValue[1] = 0x12345678;

    int[] out = new int[NUM_SAMPLES * 2];
    Functions.resetNoiseSeed();
    assertTrue(voice.render(out, NUM_SAMPLES, true, false));
    int nextNoise = Functions.getNoise();

    assertEquals(
        NUM_SAMPLES,
        prngStepsSinceReset(nextNoise),
        "C voice.cpp:1606 — mono voice with LP ladder must consume 1 getNoise() per sample, not 2");
    assertEquals(
        0x12345678,
        voice.lastSaturationTanHWorkingValue[1],
        "C voice.cpp:1653 — mono voice saturation must only touch lastSaturationTanHWorkingValue[0]");
    assertNotEquals(
        0x80000000,
        voice.lastSaturationTanHWorkingValue[0],
        "C voice.cpp:1653 — mono voice saturation must update lastSaturationTanHWorkingValue[0]");
  }

  @Test
  void voiceNoiseCadenceAndStereoBehaviorMatchCppInSubtractiveAndRingmod() {
    Voice.testStartPhaseOverrideOsc1.set(0);
    Voice.testStartPhaseOverrideOsc2.set(0);

    // 1. SUBTRACTIVE with stereoUnison + noise-only: noise consumes 2 * numSamples PRNG steps, and
    // L == R because mono prefix is filtered/expanded.
    Sound subSound = makeInitializedSound();
    subSound.synthMode = 0; // SUBTRACTIVE
    subSound.lpfMode = FilterSet.FilterMode.OFF;
    subSound.hpfMode = FilterSet.FilterMode.OFF;
    subSound.oscTypes[0] = Oscillator.OscType.SAW;
    subSound.oscTypes[1] = Oscillator.OscType.SAW;
    subSound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MIN_VALUE;
    subSound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    subSound.patchedParamValues[Param.LOCAL_NOISE_VOLUME] = 1 << 28;
    subSound.patchedParamValues[Param.LOCAL_VOLUME] = 134217728;
    subSound.numUnison = 2;
    subSound.unisonStereoSpread = 32;
    subSound.setupUnisonDetuners();
    subSound.setupUnisonStereoSpread();
    subSound.calculateEffectiveVolume();

    Voice subVoice = new Voice(subSound);
    subVoice.noteOn(60, 100, -1, null);
    int[] subOut = new int[NUM_SAMPLES * 2];
    Functions.resetNoiseSeed();
    assertTrue(subVoice.render(subOut, NUM_SAMPLES, false, false));
    int nextNoiseSub = Functions.getNoise();

    assertEquals(
        NUM_SAMPLES * 2,
        prngStepsSinceReset(nextNoiseSub),
        "C voice.cpp:1129, 1150-1153 — stereoUnison noise must consume 2 * numSamples PRNG calls");
    for (int i = 0; i < NUM_SAMPLES; i++) {
      assertEquals(
          subOut[i * 2],
          subOut[i * 2 + 1],
          "C voice.cpp:1222-1226, 1598 — subtractive noise-only / mono-expanded noise has L == R");
    }

    // 2. RINGMOD with stereoUnison + noise: noise consumes 2 * numSamples PRNG steps, and L/R
    // noise samples stay independent (voice.cpp:1277, 1372-1382).
    Sound rmSound = makeInitializedSound();
    rmSound.synthMode = 2; // RINGMOD
    rmSound.lpfMode = FilterSet.FilterMode.OFF;
    rmSound.hpfMode = FilterSet.FilterMode.OFF;
    rmSound.oscTypes[0] = Oscillator.OscType.SINE;
    rmSound.oscTypes[1] = Oscillator.OscType.SINE;
    rmSound.patchedParamValues[Param.LOCAL_NOISE_VOLUME] = 1 << 28;
    rmSound.patchedParamValues[Param.LOCAL_VOLUME] = 134217728;
    rmSound.numUnison = 2;
    rmSound.unisonStereoSpread = 32;
    rmSound.setupUnisonDetuners();
    rmSound.setupUnisonStereoSpread();
    rmSound.calculateEffectiveVolume();

    Voice rmVoice = new Voice(rmSound);
    rmVoice.noteOn(60, 100, -1, null);
    for (int u = 0; u < rmSound.numUnison; u++) {
      rmVoice.unisonParts[u].sources[0].active = false;
      rmVoice.unisonParts[u].sources[1].active = false;
    }

    int[] rmOut = new int[NUM_SAMPLES * 2];
    Functions.resetNoiseSeed();
    assertTrue(rmVoice.render(rmOut, NUM_SAMPLES, false, false));
    int nextNoiseRm = Functions.getNoise();

    assertEquals(
        NUM_SAMPLES * 2,
        prngStepsSinceReset(nextNoiseRm),
        "C voice.cpp:1129, 1150-1153 — ringmod stereoUnison noise consumes 2 * numSamples PRNG calls");
    boolean anyDiff = false;
    for (int i = 0; i < NUM_SAMPLES; i++) {
      if (rmOut[i * 2] != rmOut[i * 2 + 1]) {
        anyDiff = true;
        break;
      }
    }
    assertTrue(
        anyDiff,
        "C voice.cpp:1277, 1372-1382 — ringmod stereoUnison keeps independent L and R noise samples");
  }

  @Test
  void ringModFilterGainUsesRawWrappingLeftShiftFour() {
    Voice.testStartPhaseOverrideOsc1.set(0x20000000);
    Voice.testStartPhaseOverrideOsc2.set(0x20000000);

    // Verify via a live RingMod voice with active filter mode that amplitudeForRingMod << 4
    // renders cleanly without pinning via lshiftAndSaturate.
    Sound rmSound = makeInitializedSound();
    rmSound.synthMode = 2; // RINGMOD
    rmSound.lpfMode = FilterSet.FilterMode.TRANSISTOR_24DB;
    rmSound.hpfMode = FilterSet.FilterMode.OFF;
    rmSound.oscTypes[0] = Oscillator.OscType.SAW;
    rmSound.oscTypes[1] = Oscillator.OscType.SAW;
    rmSound.patchedParamValues[Param.LOCAL_NOISE_VOLUME] = Integer.MIN_VALUE;
    rmSound.patchedParamValues[Param.LOCAL_VOLUME] = 134217728;
    rmSound.patchedParamValues[Param.LOCAL_LPF_FREQ] = Integer.MAX_VALUE;
    rmSound.patchedParamValues[Param.LOCAL_LPF_RESONANCE] = Integer.MIN_VALUE;
    rmSound.calculateEffectiveVolume();

    Voice rmVoice = new Voice(rmSound);
    rmVoice.noteOn(60, 100, -1, null);
    int[] out = new int[NUM_SAMPLES * 2];
    assertTrue(rmVoice.render(out, NUM_SAMPLES, false, false));
    boolean nonZero = false;
    for (int sample : out) {
      if (sample != 0) {
        nonZero = true;
        break;
      }
    }
    assertTrue(nonZero, "RingMod voice with active filter mode must render non-zero audio");
  }

  @Test
  void masterFilterSetUsesGlobalEffectableParameterCurvesAndGates() {
    ProjectModel project = new ProjectModel();
    FirmwareAudioEngine engine = new FirmwareAudioEngine();

    // Default project: LPF = 20000 Hz (knob = MAX_VALUE), HPF = 20 Hz (knob = MIN_VALUE)
    engine.syncMasterEffects(project);
    assertFalse(engine.masterFilterSet.isOn(), "Default song must leave masterFilterSet OFF");

    int expectedDefaultLpfFreq =
        Functions.getFinalParameterValueExp(
            Functions.getParamNeutralValue(Param.LOCAL_LPF_FREQ),
            Functions.cableToExpParamShortcut(Integer.MAX_VALUE));
    int expectedDefaultHpfFreq =
        Functions.getFinalParameterValueExp(
            Functions.getParamNeutralValue(Param.LOCAL_HPF_FREQ),
            Functions.cableToExpParamShortcut(Integer.MIN_VALUE));
    int expectedDefaultRes =
        Functions.getFinalParameterValueLinear(
            Functions.getParamNeutralValue(Param.LOCAL_LPF_RESONANCE),
            Functions.cableToLinearParamShortcut(Integer.MIN_VALUE));
    assertEquals(expectedDefaultLpfFreq, engine.masterLpfFreq);
    assertEquals(expectedDefaultHpfFreq, engine.masterHpfFreq);
    assertEquals(expectedDefaultRes, engine.masterLpfRes);

    // Lower LPF cutoff to 1000 Hz: doLPF engages
    project.setSongParamLpfFrequency(1000.0f);
    engine.syncMasterEffects(project);
    assertTrue(
        engine.masterFilterSet.isOn(), "Lowering song LPF cutoff must engage masterFilterSet");

    // Raise HPF cutoff to 400 Hz on a fresh default LPF project: doHPF engages
    ProjectModel hpfProject = new ProjectModel();
    hpfProject.setSongParamHpfFrequency(400.0f);
    engine.syncMasterEffects(hpfProject);
    assertTrue(
        engine.masterFilterSet.isOn(),
        "Raising song HPF cutoff above 20 Hz must engage masterFilterSet");

    // DRIVE mode (TRANSISTOR_24DB_DRIVE) engages doLPF even when LPF cutoff is wide open
    ProjectModel driveProject = new ProjectModel();
    driveProject.setSongLpfMode(FilterMode.DRIVE);
    engine.syncMasterEffects(driveProject);
    assertTrue(
        engine.masterFilterSet.isOn(),
        "C global_effectable.cpp:775 — TRANSISTOR_24DB_DRIVE engages doLPF even at max cutoff");
  }

  @Test
  void songFilterModesAndRouteRoundTripThroughXml(@TempDir Path tempDir) throws Exception {
    ProjectModel original = new ProjectModel();
    original.setSongLpfMode(FilterMode.DRIVE);
    original.setSongHpfMode(FilterMode.SVF_NOTCH);
    original.setSongFilterRoute(2); // PARALLEL ("PARA")

    Path xmlPath = tempDir.resolve("song_filter_modes.xml");
    ProjectSerializer.save(original, xmlPath.toFile());
    String xml = Files.readString(xmlPath);
    assertTrue(
        xml.contains("lpfMode=\"24dBDrive\""),
        "Serialized <song> must include lpfMode=\"24dBDrive\"");
    assertTrue(
        xml.contains("hpfMode=\"SVF_Notch\""),
        "Serialized <song> must include hpfMode=\"SVF_Notch\"");
    assertTrue(
        xml.contains("filterRoute=\"PARA\""),
        "Serialized <song> must include filterRoute=\"PARA\"");

    ProjectModel loaded = SongXmlParser.parseSong(xmlPath.toFile());
    assertEquals(FilterMode.DRIVE, loaded.getSongLpfMode());
    assertEquals(FilterMode.SVF_NOTCH, loaded.getSongHpfMode());
    assertEquals(2, loaded.getSongFilterRoute());

    // Also verify C-faithful LP string in hpfMode on <song> maps to OFF (inert HPF)
    String inertHpfXml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song lpfMode="12dB" hpfMode="12dB" filterRoute="L2H">
          <songParams>
            <hpf frequency="0x00000000" resonance="0x80000000"/>
          </songParams>
          <instruments/>
          <sessionClips/>
        </song>
        """;
    Path inertPath = tempDir.resolve("song_inert_hpf.xml");
    Files.writeString(inertPath, inertHpfXml);
    ProjectModel inertLoaded = SongXmlParser.parseSong(inertPath.toFile());
    assertEquals(FilterMode.LADDER_12, inertLoaded.getSongLpfMode());
    assertEquals(FilterMode.OFF, inertLoaded.getSongHpfMode());
    assertEquals(1, inertLoaded.getSongFilterRoute());

    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.syncMasterEffects(inertLoaded);
    assertFalse(
        engine.masterFilterSet.isOn(),
        "Song hpfMode=\"12dB\" is inert in C++ (filter_set.cpp:25-41), so masterFilterSet stays OFF");
  }

  @Test
  void pureFirmwareEngineSyncFromBridgePreservesSongFilterModesAndRoute() {
    PureFirmwareEngine pureEngine = new PureFirmwareEngine();
    BridgeContract bridge = new BridgeContract();

    // DRIVE mode on <song> engages doLPF even at default wide-open cutoff (20 kHz)
    ProjectModel driveProject = new ProjectModel();
    driveProject.setSongLpfMode(FilterMode.DRIVE);
    driveProject.setSongHpfMode(FilterMode.SVF_NOTCH);
    driveProject.setSongFilterRoute(2);
    pureEngine.setProject(driveProject);

    pureEngine.syncFromBridge(bridge);
    assertTrue(
        pureEngine.getAudioEngine().masterFilterSet.isOn(),
        "PureFirmwareEngine.syncFromBridge must preserve project songLpfMode (DRIVE) instead of resetting to LADDER_24");

    // OFF mode on <song> keeps doLPF disengaged even when G_SP_LPF_FREQ is lowered
    ProjectModel offProject = new ProjectModel();
    offProject.setSongLpfMode(FilterMode.OFF);
    offProject.setSongHpfMode(FilterMode.OFF);
    pureEngine.setProject(offProject);
    bridge.setGlobalFloat(BridgeContract.G_SP_LPF_FREQ, 1000.0);
    bridge.setGlobalFloat(BridgeContract.G_SP_HPF_FREQ, 400.0);

    pureEngine.syncFromBridge(bridge);
    assertFalse(
        pureEngine.getAudioEngine().masterFilterSet.isOn(),
        "PureFirmwareEngine.syncFromBridge must preserve project songLpfMode/songHpfMode (OFF)");
  }
}
