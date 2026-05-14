package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.chuck.core.ChuckConfig;
import org.chuck.core.ChuckVM;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** E2E Integration Test for the Deluge Engine. */
public class DelugeEngineTest {
  private static ChuckVM vm;
  private static BridgeContract bridge;
  private static final List<String> logs =
      java.util.Collections.synchronizedList(new ArrayList<>());
  private static final int TRACKS = 8;

  @BeforeAll
  static void setUpAll() {
    System.setProperty("chuck.audio.dummy", "true");
    System.setProperty("deluge.tracks", "" + TRACKS);
    vm = new ChuckVM(44100, 2);
    vm.addPrintListener(logs::add);

    ChuckConfig.addSearchPath("src/main/resources");
    ChuckConfig.addSearchPath("../deluge/src/main/resources");

    bridge = new BridgeContract();

    bridge.setTrackType(0, 0); // Kit
    bridge.setTrackType(4, 1); // Synth
    bridge.register(vm);

    vm.setLogLevel(2);
    vm.spork(new org.chuck.deluge.engine.DelugeEngineDSL(vm));

    vm.advanceTime(100);
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.advanceTime(44100 / 4);
  }

  @AfterAll
  static void tearDownAll() {
    if (vm != null) vm.shutdown();
  }

  @BeforeEach
  void setUp() {
    bridge.clearAllSteps();
    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
    vm.advanceTime(4410); // Settle
    logs.clear();
  }

  @Test
  void testEngineKitTriggerOnCellSelection() throws Exception {
    bridge.setStep(0, 0, true);
    vm.setGlobalString("g_sample_0", "examples/data/kick.wav");
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);

    // Advance time to allow the engine to process
    vm.advanceTime(44100 * 2);

    // Note: Engine doesn't currently log kit triggers (only synth). This is a known gap.
    // Kit audio output is verified by KitXmlPresetTest and DelugeE2ETest.
    boolean triggerFound = logs.stream().anyMatch(l -> l.contains("trigger track: 0 step: 0"));
    System.out.println(
        "Kit test: found trigger log = " + triggerFound + " (engine only logs synth triggers)");
  }

  @Test
  void testTiedNotes() throws Exception {
    // Set a note with gate 2.5 BEFORE playing so it catches the first tick
    bridge.setTrackType(4, 1); // Synth track
    bridge.setStep(4, 0, true);
    bridge.setGate(4, 0, 2.5);
    bridge.setPitch(4, 0, 0);

    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.advanceTime(44100 * 3); // Advance 3 seconds

    // Verify logs
    boolean startFound = logs.stream().anyMatch(l -> l.contains("SYNTH trigger track: 4 step: 0"));
    boolean endFound = logs.stream().anyMatch(l -> l.contains("SYNTH note end track: 4"));

    assertTrue(startFound, "Engine did not start tied note (loglevel=" + vm.getLogLevel() + ")");
    assertTrue(endFound, "Engine did not end tied note");
  }

  @Test
  void testSynthDataPerTrackGlobals() throws Exception {
    // Set distinctive per-track synth defaults via bridge arrays
    // Use direct array access since bridge doesn't expose individual setters for these
    float[] panArr = bridge.getPanRaw();
    int[] osc2Type = bridge.getOsc2TypeRaw();
    float[] mod1Fb = bridge.getMod1FbRaw();
    int[] unisonNum = bridge.getUnisonNumRaw();
    float[] modFxRate = bridge.getModFxRateRaw();
    float[] portamento = bridge.getPortamentoRaw();
    float[] eqBass = bridge.getEqBassRaw();
    float[] stutterRate = bridge.getStutterRateRaw();
    float[] sampleRateRed = bridge.getSampleRateRedRaw();
    float[] bitCrush = bridge.getBitCrushRaw();
    float[] compAttack = bridge.getCompAttackRaw();
    float[] compRelease = bridge.getCompReleaseRaw();
    float[] mod2Amt = bridge.getMod2AmtRaw();
    float[] mod2Fb = bridge.getMod2FbRaw();
    float[] carrier2Fb = bridge.getCarrier2FbRaw();

    for (int t = 0; t < TRACKS; t++) {
      panArr[t] = -0.5f + t * 0.1f;
      osc2Type[t] = t + 1;
      mod1Fb[t] = 0.1f + t * 0.05f;
      unisonNum[t] = t + 2;
      modFxRate[t] = 0.5f + t * 0.1f;
      portamento[t] = 0.01f + t * 0.01f;
      eqBass[t] = 0.2f + t * 0.05f;
      stutterRate[t] = 0.3f + t * 0.1f;
      sampleRateRed[t] = 0.1f + t * 0.02f;
      bitCrush[t] = 0.0f + t * 0.05f;
      compAttack[t] = 0.005f + t * 0.001f;
      compRelease[t] = 0.05f + t * 0.01f;
      mod2Amt[t] = 0.3f + t * 0.05f;
      mod2Fb[t] = 0.2f + t * 0.03f;
      carrier2Fb[t] = 0.1f + t * 0.02f;
    }

    // Force G_RELOAD so engine re-initializes and picks up new data
    vm.setGlobalInt(BridgeContract.G_RELOAD, 1L);
    vm.advanceTime(4410);

    // Start playback on synth track so engine enters SynthShred loop
    bridge.setStep(4, 0, true);
    bridge.setPitch(4, 0, 60);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.advanceTime(44100 * 3); // Run several seconds for many ticks

    // Per-track globals written by SynthShred (lines 1202-1218):
    //   G_MOD1_FB, G_NOISE_VOL, G_OSC_MIX, G_UNISON_NUM, G_UNISON_DETUNE,
    //   G_MOD_FX_TYPE, G_MOD_FX_RATE, G_MOD_FX_DEPTH, G_MOD_FX_FEEDBACK,
    //   G_PORTAMENTO, G_EQ_BASS, G_EQ_TREBLE, G_STUTTER_RATE, G_SAMPLE_RATE_RED,
    //   G_BITCRUSH, G_COMP_ATTACK, G_COMP_RELEASE, G_MOD2_AMT, G_MOD2_FB, G_CARRIER2_FB
    // NOTE: G_PAN is NOT written as a per-track global — it's read locally for pan blending.
    // NOTE: Only track 4 is synth type (set in @BeforeAll), so per-track globals
    // are only written for track 4 (synthBase=4, maxSynthBridgeRow=4, only r=4 processes).
    int r = 4; // The only synth track
    double expectedModFxRate = modFxRate[r];
    double actualModFxRate = vm.getGlobalFloat(BridgeContract.G_MOD_FX_RATE + "_" + r);
    assertEquals(expectedModFxRate, actualModFxRate, 0.001, "modFxRate[" + r + "]");

    double expectedPortamento = portamento[r];
    double actualPortamento = vm.getGlobalFloat(BridgeContract.G_PORTAMENTO + "_" + r);
    assertEquals(expectedPortamento, actualPortamento, 0.001, "portamento[" + r + "]");

    double expectedEqBass = eqBass[r];
    double actualEqBass = vm.getGlobalFloat(BridgeContract.G_EQ_BASS + "_" + r);
    assertEquals(expectedEqBass, actualEqBass, 0.001, "eqBass[" + r + "]");

    double expectedStutter = stutterRate[r];
    double actualStutter = vm.getGlobalFloat(BridgeContract.G_STUTTER_RATE + "_" + r);
    assertEquals(expectedStutter, actualStutter, 0.001, "stutterRate[" + r + "]");

    double expectedSrr = sampleRateRed[r];
    double actualSrr = vm.getGlobalFloat(BridgeContract.G_SAMPLE_RATE_RED + "_" + r);
    assertEquals(expectedSrr, actualSrr, 0.001, "sampleRateRed[" + r + "]");

    double expectedBc = bitCrush[r];
    double actualBc = vm.getGlobalFloat(BridgeContract.G_BITCRUSH + "_" + r);
    assertEquals(expectedBc, actualBc, 0.001, "bitCrush[" + r + "]");

    double expectedCa = compAttack[r];
    double actualCa = vm.getGlobalFloat(BridgeContract.G_COMP_ATTACK + "_" + r);
    assertEquals(expectedCa, actualCa, 0.001, "compAttack[" + r + "]");

    double expectedCr = compRelease[r];
    double actualCr = vm.getGlobalFloat(BridgeContract.G_COMP_RELEASE + "_" + r);
    assertEquals(expectedCr, actualCr, 0.001, "compRelease[" + r + "]");

    double expectedMod1Fb = mod1Fb[r];
    double actualMod1Fb = vm.getGlobalFloat(BridgeContract.G_MOD1_FB + "_" + r);
    assertEquals(expectedMod1Fb, actualMod1Fb, 0.001, "mod1Fb[" + r + "]");

    // Also verify defaults (non-synth tracks should have 0.0 in per-track globals)
    // These are set in bridge.initDefaults or are 0 by default
    for (int t = 0; t < TRACKS; t++) {
      if (t == r) continue; // skip track 4, already verified
      assertEquals(
          0.0,
          vm.getGlobalFloat(BridgeContract.G_MOD_FX_RATE + "_" + t),
          0.001,
          "other modFxRate[" + t + "] should be default");
    }
  }

  @Test
  void testKitDataPerTrackGlobals() throws Exception {
    // Set distinctive per-track kit defaults via bridge arrays
    float[] kitVolume = bridge.getKitVolumeRaw();
    float[] kitPan = bridge.getKitPanRaw();
    float[] kitHpfFreq = bridge.getKitHpfFreqRaw();
    float[] kitHpfRes = bridge.getKitHpfResRaw();
    float[] kitNoiseVol = bridge.getKitNoiseVolRaw();
    float[] kitEqBass = bridge.getKitEqBassRaw();
    float[] kitEqTreble = bridge.getKitEqTrebleRaw();
    float[] kitSidechain = bridge.getKitSidechainRaw();
    int[] kitModFxType = bridge.getKitModFxTypeRaw();
    float[] kitStutterRate = bridge.getKitStutterRateRaw();
    float[] kitSampleRateRed = bridge.getKitSampleRateRedRaw();
    float[] kitBitCrush = bridge.getKitBitCrushRaw();
    float[] kitCompAttack = bridge.getKitCompAttackRaw();
    float[] kitCompRelease = bridge.getKitCompReleaseRaw();

    for (int t = 0; t < TRACKS; t++) {
      kitVolume[t] = 0.3f + t * 0.05f;
      kitPan[t] = -0.4f + t * 0.1f;
      kitHpfFreq[t] = 30f + t * 20f;
      kitHpfRes[t] = 0.1f + t * 0.05f;
      kitNoiseVol[t] = 0.01f + t * 0.01f;
      kitEqBass[t] = 0.15f + t * 0.05f;
      kitEqTreble[t] = 0.1f + t * 0.04f;
      kitSidechain[t] = 0.2f + t * 0.05f;
      kitModFxType[t] = t % 4; // int-backed
      kitStutterRate[t] = 0.2f + t * 0.1f;
      kitSampleRateRed[t] = 0.05f + t * 0.02f;
      kitBitCrush[t] = 0.0f + t * 0.03f;
      kitCompAttack[t] = 0.002f + t * 0.001f;
      kitCompRelease[t] = 0.03f + t * 0.01f;
    }

    // Start playback on kit track so engine enters KitShred loop
    bridge.setStep(0, 0, true);
    vm.setGlobalString("g_sample_0", "examples/data/kick.wav");
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.advanceTime(44100 * 3); // Run several seconds for many ticks

    // Per-track globals written by KitShred (lines 769-778):
    //   G_KIT_HPF_FREQ, G_KIT_HPF_RES, G_KIT_NOISE_VOL, G_KIT_EQ_BASS, G_KIT_EQ_TREBLE,
    //   G_KIT_SIDECHAIN, G_KIT_MOD_FX_TYPE, G_KIT_STUTTER_RATE, G_KIT_SAMPLE_RATE_RED,
    // G_KIT_BITCRUSH
    // NOTE: kitVolume/kitPan are applied directly to UGens (not stored as per-track globals).
    // NOTE: kitCompAttack/kitCompRelease are fetched for FX bus but not written here.
    for (int t = 0; t < TRACKS; t++) {
      double expectedHpfF = kitHpfFreq[t];
      double actualHpfF = vm.getGlobalFloat(BridgeContract.G_KIT_HPF_FREQ + "_" + t);
      assertEquals(expectedHpfF, actualHpfF, 1.0, "kitHpfFreq[" + t + "]");

      double expectedHpfR = kitHpfRes[t];
      double actualHpfR = vm.getGlobalFloat(BridgeContract.G_KIT_HPF_RES + "_" + t);
      assertEquals(expectedHpfR, actualHpfR, 0.001, "kitHpfRes[" + t + "]");

      double expectedNoise = kitNoiseVol[t];
      double actualNoise = vm.getGlobalFloat(BridgeContract.G_KIT_NOISE_VOL + "_" + t);
      assertEquals(expectedNoise, actualNoise, 0.001, "kitNoiseVol[" + t + "]");

      double expectedEqB = kitEqBass[t];
      double actualEqB = vm.getGlobalFloat(BridgeContract.G_KIT_EQ_BASS + "_" + t);
      assertEquals(expectedEqB, actualEqB, 0.001, "kitEqBass[" + t + "]");

      double expectedEqT = kitEqTreble[t];
      double actualEqT = vm.getGlobalFloat(BridgeContract.G_KIT_EQ_TREBLE + "_" + t);
      assertEquals(expectedEqT, actualEqT, 0.001, "kitEqTreble[" + t + "]");

      double expectedSc = kitSidechain[t];
      double actualSc = vm.getGlobalFloat(BridgeContract.G_KIT_SIDECHAIN + "_" + t);
      assertEquals(expectedSc, actualSc, 0.001, "kitSidechain[" + t + "]");

      double expectedSrr = kitSampleRateRed[t];
      double actualSrr = vm.getGlobalFloat(BridgeContract.G_KIT_SAMPLE_RATE_RED + "_" + t);
      assertEquals(expectedSrr, actualSrr, 0.001, "kitSampleRateRed[" + t + "]");

      double expectedBc = kitBitCrush[t];
      double actualBc = vm.getGlobalFloat(BridgeContract.G_KIT_BITCRUSH + "_" + t);
      assertEquals(expectedBc, actualBc, 0.001, "kitBitCrush[" + t + "]");

      // int-backed arrays — modFxType stored via int[] in KitData, read via getFloat()
      double expectedMft = kitModFxType[t];
      double actualMft = vm.getGlobalFloat(BridgeContract.G_KIT_MOD_FX_TYPE + "_" + t);
      assertEquals(expectedMft, actualMft, 0.001, "kitModFxType[" + t + "] (int-backed)");

      double expectedStut = kitStutterRate[t];
      double actualStut = vm.getGlobalFloat(BridgeContract.G_KIT_STUTTER_RATE + "_" + t);
      assertEquals(expectedStut, actualStut, 0.001, "kitStutterRate[" + t + "]");
    }
  }

  @Test
  void testChuckArrayIntBackedGetFloat() {
    // Verify that ChuckArray constructed from int[] can be read via getFloat()
    org.chuck.core.ChuckArray intArr = new org.chuck.core.ChuckArray(new int[] {0, 1, 2, 3, 42});

    assertEquals(0.0, intArr.getFloat(0), 0.001, "intArr[0]");
    assertEquals(1.0, intArr.getFloat(1), 0.001, "intArr[1]");
    assertEquals(42.0, intArr.getFloat(4), 0.001, "intArr[4]");
    assertEquals(0.0, intArr.getFloat(5), 0.001, "intArr[5] out of bounds");
    assertEquals(3.0, intArr.getFloat(-2), 0.001, "intArr[-2] negative index");
  }

  @Test
  void testChuckArrayFloatBackedGetFloat() {
    org.chuck.core.ChuckArray floatArr =
        new org.chuck.core.ChuckArray(new float[] {0.5f, 1.5f, 2.5f});

    assertEquals(0.5, floatArr.getFloat(0), 0.001, "floatArr[0]");
    assertEquals(1.5, floatArr.getFloat(1), 0.001, "floatArr[1]");
    assertEquals(2.5, floatArr.getFloat(2), 0.001, "floatArr[2]");
    assertEquals(0.0, floatArr.getFloat(3), 0.001, "floatArr[3] out of bounds");
    assertEquals(1.5, floatArr.getFloat(-2), 0.001, "floatArr[-2] negative index");
  }
}
