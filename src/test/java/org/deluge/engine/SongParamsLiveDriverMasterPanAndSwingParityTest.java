package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.deluge.BridgeContract;
import org.deluge.firmware2.Functions;
import org.deluge.firmware2.GlobalEffectable;
import org.deluge.firmware2.Param;
import org.deluge.model.ClipModel;
import org.deluge.model.FilterMode;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.modulation.automation.AutoParam;
import org.deluge.modulation.params.ParamManager;
import org.deluge.playback.PlaybackHandler;
import org.deluge.project.ProjectSerializer;
import org.deluge.xml.DelugeHexMapper;
import org.deluge.xml.SongXmlParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Deterministic C++ firmware parity tests for:
 *
 * <ul>
 *   <li>Post-compressor master output fader gain &amp; stereo balance pan ({@code
 *       audio_engine.cpp:1122-1123, 1330-1365}, {@code functions.cpp:1487-1498}) without distorting
 *       pre-FX {@code MASTER_VOLUME_NEUTRAL} headroom.
 *   <li>Sample-accurate live {@code <songParams>} &amp; {@code UNPATCHED_TEMPO} stepping in {@link
 *       JavaAudioDriver#stepBlock(int)} + {@link FirmwareAudioEngine#syncAutomatedSongParams} +
 *       {@link PureFirmwareEngine#syncFromBridge} ({@code song.cpp:2555-2595, 6062-6070}, {@code
 *       arrangement.cpp:120-129, 359-365}).
 *   <li>Bidirectional hardware {@code swingAmount}/{@code swing} synchronization and firmware
 *       {@code v3.1.0-alpha2} {@code swingInterval} XML conversion ({@code song.cpp:1688-1702,
 *       2117-2124}, {@code Deserializer.cpp:62}, {@code firmware_version.cpp:6-20}, {@code
 *       semver.h:27-37}).
 * </ul>
 */
class SongParamsLiveDriverMasterPanAndSwingParityTest {

  private static final class SquareWaveEffectable extends GlobalEffectable {
    private final int amplitude;

    SquareWaveEffectable(int amplitude) {
      this.amplitude = amplitude;
    }

    @Override
    protected void renderInternal(int[] buffer, int numSamples, int[] reverbBuffer) {
      for (int i = 0; i < numSamples; i++) {
        int v = ((i & 4) == 0) ? amplitude : -amplitude;
        buffer[i * 2] += v;
        buffer[i * 2 + 1] += v;
      }
    }
  }

  @BeforeEach
  void resetNoise() {
    Functions.resetNoiseSeed();
  }

  @AfterEach
  void restoreNoise() {
    Functions.resetNoiseSeed();
  }

  private static ProjectModel createSingleSynthProject(float bpm) {
    ProjectModel project = new ProjectModel();
    project.setBpm(bpm);
    SynthTrackModel synth = new SynthTrackModel("Lead");
    synth.setOsc1Type("SAW");
    synth.setOsc2Type("NONE");
    synth.setOscMix(1.0f);
    synth.setLpfFreq(20000.0f);
    synth.setLpfRes(0.0f);
    synth.setVolume(0.75f);
    ClipModel clip = new ClipModel("Clip 1", 8, 16);
    synth.addClip(clip);
    project.addTrack(synth);
    FirmwareFactory.createSong(project);
    return project;
  }

  @Test
  void defaultMasterGainAndPanAreBitExactAndHardPanOrZeroGainMutesWetAndMetronomeOutput() {
    Functions.resetNoiseSeed();
    ProjectModel baselineProject = createSingleSynthProject(120.0f);
    FirmwareAudioEngine baselineEngine = new FirmwareAudioEngine();
    baselineEngine.sounds.add(new SquareWaveEffectable(1 << 22));
    baselineEngine.syncMasterEffects(baselineProject);
    baselineEngine.renderBlock(128);
    int[] expectedL = new int[128];
    int[] expectedR = new int[128];
    long energyL = 0L;
    for (int i = 0; i < 128; i++) {
      expectedL[i] = baselineEngine.masterBuffer[i].l;
      expectedR[i] = baselineEngine.masterBuffer[i].r;
      energyL += Math.abs((long) expectedL[i]);
    }
    assertTrue(energyL > 0L, "Baseline synth block must contain non-zero audio");

    // 1. Explicit unity gain (1.0f) and center pan (0.0f) via PureFirmwareEngine.syncFromBridge
    // must leave MASTER_VOLUME_NEUTRAL untouched and produce 100% bit-identical samples.
    Functions.resetNoiseSeed();
    ProjectModel bridgeProject = createSingleSynthProject(120.0f);
    BridgeContract bridge = new BridgeContract();
    bridge.setGlobalFloat(BridgeContract.G_MASTER_VOL, 1.0);
    bridge.setGlobalFloat(BridgeContract.G_MASTER_PAN, 0.0);
    bridge.setGlobalFloat(BridgeContract.G_BPM, 120.0);
    bridge.setGlobalFloat(BridgeContract.G_SP_VOLUME, 0.5);
    bridge.setGlobalFloat(BridgeContract.G_SP_LPF_FREQ, 20000.0);
    bridge.setGlobalFloat(BridgeContract.G_SP_HPF_FREQ, 20.0);
    bridge.setGlobalFloat(BridgeContract.G_SP_DELAY_RATE, 0.5);
    bridge.setGlobalFloat(BridgeContract.G_SP_EQ_BASS_FREQ, 0.5);
    bridge.setGlobalFloat(BridgeContract.G_SP_EQ_TREBLE_FREQ, 0.5);
    bridge.setGlobalInt(BridgeContract.G_DELAY_PINGPONG, 1);
    bridge.setGlobalInt(BridgeContract.G_DELAY_SYNC_LEVEL, 7);
    bridge.setGlobalInt(BridgeContract.G_REVERB_COMP_SYNC_LEVEL, 5);
    PureFirmwareEngine pfe = new PureFirmwareEngine();
    pfe.setProject(bridgeProject);
    pfe.getAudioEngine().sounds.clear();
    pfe.getAudioEngine().sounds.add(new SquareWaveEffectable(1 << 22));
    pfe.getAudioEngine().syncMasterEffects(bridgeProject);
    pfe.syncFromBridge(bridge);

    assertEquals(
        FirmwareAudioEngine.MASTER_VOLUME_NEUTRAL,
        pfe.getAudioEngine().masterVolumeAdjustmentL,
        "Pre-compressor masterVolumeAdjustmentL must stay at MASTER_VOLUME_NEUTRAL");
    assertEquals(
        FirmwareAudioEngine.MASTER_VOLUME_NEUTRAL,
        pfe.getAudioEngine().masterVolumeAdjustmentR,
        "Pre-compressor masterVolumeAdjustmentR must stay at MASTER_VOLUME_NEUTRAL");

    pfe.getAudioEngine().renderBlock(128);
    for (int i = 0; i < 128; i++) {
      assertEquals(expectedL[i], pfe.getAudioEngine().masterBuffer[i].l, "Bit-exact L at i=" + i);
      assertEquals(expectedR[i], pfe.getAudioEngine().masterBuffer[i].r, "Bit-exact R at i=" + i);
    }

    // 2. Hard-left pan (-1.0f) mutes R cleanly; hard-right pan (+1.0f) mutes L cleanly.
    bridge.setGlobalFloat(BridgeContract.G_MASTER_PAN, -1.0);
    pfe.syncFromBridge(bridge);
    pfe.getAudioEngine().renderBlock(128);
    long leftPanL = 0L;
    for (int i = 0; i < 128; i++) {
      leftPanL += Math.abs((long) pfe.getAudioEngine().masterBuffer[i].l);
      assertEquals(0, pfe.getAudioEngine().masterBuffer[i].r, "Hard-left pan must zero R");
    }
    assertTrue(leftPanL > 0L, "Hard-left pan must preserve non-zero L signal");

    bridge.setGlobalFloat(BridgeContract.G_MASTER_PAN, 1.0);
    pfe.syncFromBridge(bridge);
    pfe.getAudioEngine().renderBlock(128);
    long rightPanR = 0L;
    for (int i = 0; i < 128; i++) {
      assertEquals(0, pfe.getAudioEngine().masterBuffer[i].l, "Hard-right pan must zero L");
      rightPanR += Math.abs((long) pfe.getAudioEngine().masterBuffer[i].r);
    }
    assertTrue(rightPanR > 0L, "Hard-right pan must preserve non-zero R signal");

    // 3. Master gain = 0.0f completely silences ALL output blocks, wet reverb tail, and metronome.
    pfe.getAudioEngine().songReverbAmount = Integer.MAX_VALUE;
    pfe.getAudioEngine().metronomeEnabled = true;
    pfe.getAudioEngine().triggerMetronome(128411753);
    bridge.setGlobalFloat(BridgeContract.G_MASTER_VOL, 0.0);
    bridge.setGlobalFloat(BridgeContract.G_MASTER_PAN, 0.0);
    pfe.syncFromBridge(bridge);
    for (int block = 0; block < 4; block++) {
      pfe.getAudioEngine().renderBlock(128);
      for (int i = 0; i < 128; i++) {
        assertEquals(0, pfe.getAudioEngine().masterBuffer[i].l, "Muted master must output 0 L");
        assertEquals(0, pfe.getAudioEngine().masterBuffer[i].r, "Muted master must output 0 R");
      }
    }
  }

  @Test
  void liveTempoRampAndSongParamsStepSampleAccuratelyInDriverAndSyncBridgeWithoutClobbering() {
    ProjectModel project = createSingleSynthProject(120.0f);
    project.setSongLpfMode(FilterMode.LADDER_24);

    // Configure sample-interpolated tempo ramp 120.00 BPM -> 180.00 BPM over 1 beat (96 ticks)
    // and simultaneous sample-interpolated song LPF sweep 20 kHz -> 250 Hz over 1 beat (96 ticks),
    // while leaving song pan unautomated at +0.625f to prove unautomated engine params are never
    // clobbered.
    project.setSongParamPan(0.625f);
    project.rebuildSongParamManager();
    ParamManager sm = project.getSongParamManager();

    AutoParam tempoAuto = new AutoParam(Param.UNPATCHED_TEMPO);
    tempoAuto.addNode(0, 12000, false);
    tempoAuto.addNode(96, 18000, true);
    sm.automatedParams.add(tempoAuto);

    int lpfOpenQ31 = DelugeHexMapper.hzToQ31(20000.0f);
    int lpfClosedQ31 = DelugeHexMapper.hzToQ31(250.0f);
    AutoParam lpfAuto = new AutoParam(Param.UNPATCHED_LPF_FREQ);
    lpfAuto.addNode(0, lpfOpenQ31, false);
    lpfAuto.addNode(96, lpfClosedQ31, true);
    sm.automatedParams.add(lpfAuto);

    BridgeContract bridge = new BridgeContract();
    bridge.setGlobalInt(BridgeContract.G_PLAY, 1L);
    bridge.setGlobalFloat(BridgeContract.G_MASTER_VOL, 1.0);
    bridge.setGlobalFloat(BridgeContract.G_MASTER_PAN, 0.0);
    bridge.setGlobalFloat(BridgeContract.G_BPM, 120.0);
    bridge.setGlobalFloat(BridgeContract.G_SP_VOLUME, 0.5);
    bridge.setGlobalFloat(BridgeContract.G_SP_PAN, 0.625);
    bridge.setGlobalFloat(BridgeContract.G_SP_LPF_FREQ, 20000.0);
    bridge.setGlobalFloat(BridgeContract.G_SP_HPF_FREQ, 20.0);
    bridge.setGlobalFloat(BridgeContract.G_SP_DELAY_RATE, 0.5);
    bridge.setGlobalFloat(BridgeContract.G_SP_EQ_BASS_FREQ, 0.5);
    bridge.setGlobalFloat(BridgeContract.G_SP_EQ_TREBLE_FREQ, 0.5);

    PureFirmwareEngine pfe = new PureFirmwareEngine();
    pfe.setProject(project);
    FirmwareAudioEngine engine = pfe.getAudioEngine();
    PlaybackHandler handler = pfe.getPlaybackHandler();
    engine.syncMasterEffects(project);
    int expectedUnautomatedSongPan = engine.songPan;

    JavaAudioDriver driver = pfe.getAudioDriver();
    handler.start();

    int initialMasterLpfFreq = engine.masterLpfFreq;
    int samplesRenderedUntilBeatOne = 0;
    float midRampBpm = -1.0f;
    float midRampLpfHz = -1.0f;

    while (handler.lastSwungTickActioned < 96 && samplesRenderedUntilBeatOne < 44100) {
      driver.stepBlock(128);
      samplesRenderedUntilBeatOne += 128;
      if (handler.lastSwungTickActioned == 48 && midRampBpm < 0.0f) {
        midRampBpm = project.getBpm();
        midRampLpfHz = project.getSongParamLpfFrequency();
        // Exercise periodic syncFromBridge mid-ramp: must push live BPM & LPF Hz to BridgeContract
        // rather than overwriting live automation with stale initial 120 BPM / 20 kHz values!
        pfe.syncFromBridge(bridge);
        assertEquals(midRampBpm, bridge.getGlobalFloat(BridgeContract.G_BPM), 0.05);
        assertEquals(midRampLpfHz, bridge.getGlobalFloat(BridgeContract.G_SP_LPF_FREQ), 1.0);
      }
    }

    assertTrue(
        midRampBpm > 145.0f && midRampBpm < 155.0f,
        "Mid-beat BPM must interpolate smoothly around 150 BPM, got " + midRampBpm);
    assertTrue(
        midRampLpfHz > 1500.0f && midRampLpfHz < 4000.0f,
        "Mid-beat exponential LPF cutoff must sweep near geometric midpoint (~2236 Hz), got "
            + midRampLpfHz);

    // Analytical duration of linear beat-domain tempo ramp B(u) = 120 + 60*u over u in [0, 1]:
    // T = integral_0^1 (60 / (120 + 60*u)) du = ln(180 / 120) = ln(1.5) seconds (~17,881 samples).
    double expectedSamplesAnalytic = 44100.0 * Math.log(180.0 / 120.0);
    assertEquals(
        expectedSamplesAnalytic,
        (double) samplesRenderedUntilBeatOne,
        256.0,
        "Integrated sample count to tick 96 must match analytical 44100 * ln(180/120)");

    // At tick >= 96, node 96 snaps cleanly to 180.00 BPM and ~250 Hz without clobbering songPan.
    assertEquals(180.0f, project.getBpm(), 0.01f);
    assertEquals(250.0f, project.getSongParamLpfFrequency(), 2.0f);
    assertTrue(
        engine.masterLpfFreq < initialMasterLpfFreq,
        "Engine masterLpfFreq must reflect automated 250 Hz cutoff");
    assertEquals(
        expectedUnautomatedSongPan,
        engine.songPan,
        "Unautomated songPan must remain completely untouched");
  }

  @Test
  void swingBidirectionalSyncAndFirmwareVersionGateMatchCppSongDeserialization() throws Exception {
    // 1. Bidirectional sync & [-49, 49] clamping between swingAmount and normalized 0..1 swing.
    ProjectModel model = new ProjectModel();
    model.setSwingAmount(25);
    assertEquals(25, model.getSwingAmount());
    assertEquals(0.75f, model.getSwing(), 1e-6f);

    model.setSwingAmount(99);
    assertEquals(49, model.getSwingAmount());
    assertEquals(0.99f, model.getSwing(), 1e-6f);

    model.setSwingAmount(-99);
    assertEquals(-49, model.getSwingAmount());
    assertEquals(0.01f, model.getSwing(), 1e-6f);

    model.setSwing(0.35f);
    assertEquals(0.35f, model.getSwing(), 1e-6f);
    assertEquals(-15, model.getSwingAmount());

    model.setSwingInterval(99);
    assertEquals(9, model.getSwingInterval());
    model.setSwingInterval(-4);
    assertEquals(0, model.getSwingInterval());

    // 2. Pre-3.1.0-alpha2 hardware songs keep raw internal swingInterval verbatim without file
    // conversion (C song.cpp:2117-2124, Deserializer.cpp:62, semver.h:27-37).
    String pre310Xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song firmwareVersion="3.0.5" inputTickMagnitude="2" swingAmount="18" swingInterval="6">
          <instruments/>
          <sessionClips/>
        </song>
        """;
    ProjectModel parsedPre310 =
        SongXmlParser.parseSong(
            new ByteArrayInputStream(pre310Xml.getBytes(StandardCharsets.UTF_8)), "PRE310");
    assertEquals(18, parsedPre310.getSwingAmount());
    assertEquals(0.68f, parsedPre310.getSwing(), 1e-6f);
    assertEquals(6, parsedPre310.getSwingInterval());

    // 3. 3.1.0-alpha (strictly less than 3.1.0-alpha2) ALSO keeps raw internal swingInterval,
    // whereas 3.1.0-alpha2, 3.1.0 final, and community firmware c1.3.0 convert file -> internal
    // regardless of child-element ordering!
    String alpha1Xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song firmwareVersion="3.1.0-alpha" inputTickMagnitude="2" swingInterval="6">
          <instruments/>
          <sessionClips/>
        </song>
        """;
    assertEquals(
        6,
        SongXmlParser.parseSong(
                new ByteArrayInputStream(alpha1Xml.getBytes(StandardCharsets.UTF_8)), "ALPHA1")
            .getSwingInterval());

    String childTagReorderedXml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song>
          <swingInterval>7</swingInterval>
          <swingAmount>-12</swingAmount>
          <inputTickMagnitude>3</inputTickMagnitude>
          <firmwareVersion>3.1.0-alpha2</firmwareVersion>
          <instruments/>
          <sessionClips/>
        </song>
        """;
    ProjectModel parsedReordered =
        SongXmlParser.parseSong(
            new ByteArrayInputStream(childTagReorderedXml.getBytes(StandardCharsets.UTF_8)),
            "ALPHA2_REORDERED");
    assertEquals(-12, parsedReordered.getSwingAmount());
    assertEquals(0.38f, parsedReordered.getSwing(), 1e-6f);
    assertEquals(5, parsedReordered.getSwingInterval());

    // 4. Full ProjectSerializer round-trip preserves both swingAmount and internal swingInterval.
    ByteArrayInputStream roundTripIn =
        new ByteArrayInputStream(
            ProjectSerializer.serializeToString(parsedReordered).getBytes(StandardCharsets.UTF_8));
    ProjectModel roundTripped = SongXmlParser.parseSong(roundTripIn, "ROUNDTRIP");
    assertEquals(-12, roundTripped.getSwingAmount());
    assertEquals(0.38f, roundTripped.getSwing(), 1e-6f);
    assertEquals(5, roundTripped.getSwingInterval());
  }
}
