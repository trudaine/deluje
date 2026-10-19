package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.deluge.BridgeContract;
import org.deluge.firmware2.Functions;
import org.deluge.firmware2.GlobalEffectable;
import org.deluge.firmware2.Param;
import org.deluge.model.ProjectModel;
import org.deluge.modulation.patch.PatchCable;
import org.deluge.modulation.patch.PatchSource;
import org.deluge.project.ProjectSerializer;
import org.deluge.xml.SongXmlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parity tests for song-level {@code <delay>} and {@code <songParams reverbAmount="0x...">} across
 * XML, {@link ProjectModel}, {@link BridgeContract}, {@link FirmwareAudioEngine}, and {@link
 * PureFirmwareEngine} (C {@code delay.h:66-72}, {@code delay.cpp:104-118}, {@code
 * global_effectable.cpp:75-79, 902-917, 1129-1140}, {@code mod_controllable_audio.cpp:195, 219,
 * 248-250, 789-790}, {@code song.cpp:2538-2552}, {@code audio_engine.cpp:1493-1494}).
 */
class SongDelayAndReverbSendParityTest {

  /** Emits a short dry pulse only on block 0, with track reverb send completely off. */
  private static final class SingleBlockDryPulse extends GlobalEffectable {
    private boolean fired = false;

    SingleBlockDryPulse() {
      this.reverbSendKnob = Integer.MIN_VALUE;
    }

    @Override
    protected void renderInternal(int[] buffer, int numSamples, int[] reverbBuffer) {
      if (fired) {
        return;
      }
      fired = true;
      for (int i = 0; i < Math.min(4, numSamples); i++) {
        buffer[i * 2] = 1 << 24;
        buffer[i * 2 + 1] = 1 << 24;
      }
    }
  }

  @Test
  void songDelayDefaultsAndXmlParsingFollowFirmwareRules(@TempDir Path tempDir) throws Exception {
    ProjectModel defaults = new ProjectModel();
    // C delay.h:66-72, global_effectable.cpp:75-79
    assertEquals(1, defaults.getDelayPingPong());
    assertEquals(0, defaults.getDelayAnalog());
    assertEquals(7, defaults.getDelaySyncLevel());
    assertEquals(0, defaults.getDelaySyncType());
    assertEquals(0.5f, defaults.getSongParamDelayRate(), 1e-6f);
    assertEquals(0.0f, defaults.getSongParamDelayFeedback(), 1e-6f);
    assertEquals(0.0f, defaults.getSongParamReverbAmount(), 1e-6f);

    // C mod_controllable_audio.cpp:789-790 — when <delay> is present on <song> without syncLevel,
    // syncLevel and syncType reset to 0 (SYNC_LEVEL_NONE, SYNC_TYPE_EVEN).
    // C global_effectable.cpp:902-917 — inside <songParams>, delay rate/feedback sit under <delay>.
    String unsyncedDelayXml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song
          firmwareVersion="4.1.3"
          earliestCompatibleFirmware="4.1.0"
          tempo="120.0"
          inputTickMagnitude="2">
          <delay
            pingPong="0"
            analog="1" />
          <songParams
            reverbAmount="0x00000000">
            <delay
              rate="0x10000000"
              feedback="0x00000000" />
          </songParams>
          <instruments />
          <sessionClips />
          <arrangementOnlyTracks />
        </song>
        """;
    Path unsyncedPath = tempDir.resolve("UNSYNCED_DELAY.XML");
    Files.writeString(unsyncedPath, unsyncedDelayXml);

    ProjectModel loadedUnsynced = SongXmlParser.parseSong(unsyncedPath.toFile());
    assertEquals(0, loadedUnsynced.getDelayPingPong());
    assertEquals(1, loadedUnsynced.getDelayAnalog());
    assertEquals(0, loadedUnsynced.getDelaySyncLevel());
    assertEquals(0, loadedUnsynced.getDelaySyncType());
    assertEquals(0.5f, loadedUnsynced.getSongParamDelayFeedback(), 1e-4f);
    assertEquals(0.5f, loadedUnsynced.getSongParamReverbAmount(), 1e-4f);

    // Round-trip save + reload preserves syncLevel=0 and syncType=0.
    Path outPath = tempDir.resolve("SAVED_DELAY.XML");
    ProjectSerializer.save(loadedUnsynced, outPath.toFile());
    ProjectModel reloadedUnsynced = SongXmlParser.parseSong(outPath.toFile());
    assertEquals(0, reloadedUnsynced.getDelayPingPong());
    assertEquals(1, reloadedUnsynced.getDelayAnalog());
    assertEquals(0, reloadedUnsynced.getDelaySyncLevel());
    assertEquals(0, reloadedUnsynced.getDelaySyncType());
  }

  @Test
  void configureMasterDelayMatchesSyncedRateMathAndStaysStableAcrossBlocks() {
    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);
    project.setInputTickMagnitude(2);
    project.setSongParamDelayRate(0.5f); // 0x00000000 neutral rate knob
    project.setSongParamDelayFeedback(0.5f); // audible feedback so doDelay == true
    project.setDelayPingPong(0);
    project.setDelayAnalog(0);

    FirmwareAudioEngine engine = new FirmwareAudioEngine();

    // File syncLevel=6 -> internal 5; file syncLevel=7 -> internal 6 (halves period, doubles rate).
    project.setDelaySyncLevel(6);
    project.setDelaySyncType(0);
    engine.syncMasterEffects(project);
    engine.renderBlock(64);
    int rateSync6 = engine.delayState.userDelayRate;

    project.setDelaySyncLevel(7);
    engine.syncMasterEffects(project);
    engine.renderBlock(64);
    int rateSync7 = engine.delayState.userDelayRate;
    assertEquals(rateSync6 * 2, rateSync7, 2, "Increasing syncLevel by 1 must double delay rate");

    // TRIPLET (syncType=1) speeds rate up by 3/2 (period * 2/3).
    project.setDelaySyncType(1);
    engine.syncMasterEffects(project);
    engine.renderBlock(64);
    int rateTriplet = engine.delayState.userDelayRate;
    assertEquals((int) ((long) rateSync7 * 3L / 2L), rateTriplet, 4);

    // DOTTED (syncType=2) slows rate down by 2/3 (period * 3/2).
    project.setDelaySyncType(2);
    engine.syncMasterEffects(project);
    engine.renderBlock(64);
    int rateDotted = engine.delayState.userDelayRate;
    assertEquals((int) ((long) rateSync7 * 2L / 3L), rateDotted, 4);

    // Rendering multiple blocks without calling syncMasterEffects again must NOT drift or
    // double-shift userDelayRate (delay.cpp:104-118 mutates workingState.userDelayRate in place).
    for (int b = 0; b < 8; b++) {
      engine.renderBlock(64);
    }
    assertEquals(
        rateDotted,
        engine.delayState.userDelayRate,
        "renderSongPreReverbFX must recompute userDelayRate from songDelayRate each block");

    // PureFirmwareEngine bridge sync path must produce the exact same Delay configuration.
    PureFirmwareEngine pure = new PureFirmwareEngine();
    BridgeContract bridge = new BridgeContract();
    bridge.clear();
    pure.setProject(project);
    bridge.setGlobalFloat(BridgeContract.G_SP_DELAY_RATE, 0.5);
    bridge.setGlobalFloat(BridgeContract.G_SP_DELAY_FEEDBACK, 0.5);
    bridge.setGlobalInt(BridgeContract.G_DELAY_SYNC_LEVEL, 7L);
    bridge.setGlobalInt(BridgeContract.G_DELAY_SYNC_TYPE, 2L);
    bridge.setGlobalInt(BridgeContract.G_DELAY_PINGPONG, 0L);
    bridge.setGlobalInt(BridgeContract.G_DELAY_ANALOG, 0L);
    pure.syncFromBridge(bridge);

    FirmwareAudioEngine pureAudio = pure.getAudioEngine();
    assertEquals(6, pureAudio.masterDelay.syncLevel);
    assertEquals(2, pureAudio.masterDelay.syncType);
    pureAudio.renderBlock(64);
    assertEquals(rateDotted, pureAudio.delayState.userDelayRate, 4);
  }

  @Test
  void songReverbSendAndPreReverbDelayFeedMasterReverbFromDryTrack() {
    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);
    project.setInputTickMagnitude(2);
    project.setReverbModel(1); // MUTABLE model (has exact zero-state clear() via mute())
    project.setDelayPingPong(0);
    project.setDelayAnalog(0);
    project.setDelaySyncLevel(9); // fast echo (~128th note, ~826 samples at 44.1 kHz / 120 BPM)
    project.setDelaySyncType(0);
    project.setSongParamDelayRate(0.5f);
    project.setSongParamDelayFeedback(0.0f);

    // 1. With songParamReverbAmount = 0.0f (0x80000000 off) and no delay, blocks 1..30 are silent.
    project.setSongParamReverbAmount(0.0f);
    FirmwareAudioEngine dryEngine = new FirmwareAudioEngine();
    dryEngine.sounds.add(new SingleBlockDryPulse());
    dryEngine.syncMasterEffects(project);
    dryEngine.renderBlock(64);
    long dryTail = 0;
    for (int b = 1; b < 30; b++) {
      dryEngine.renderBlock(64);
      for (int i = 0; i < 64; i++) {
        dryTail += Math.abs((long) dryEngine.masterBuffer[i].l);
      }
    }
    assertEquals(0L, dryTail, "Default songParamReverbAmount=0.0f must send nothing to reverb");

    // 2. With songParamReverbAmount = 0.8f and no delay, block 0's pulse excites masterReverb,
    // producing an audible wet tail on blocks 1..30 even though the track itself has zero send.
    project.setSongParamReverbAmount(0.8f);
    FirmwareAudioEngine wetEngine = new FirmwareAudioEngine();
    wetEngine.sounds.add(new SingleBlockDryPulse());
    wetEngine.syncMasterEffects(project);
    wetEngine.renderBlock(64);
    long wetTail = 0;
    for (int b = 1; b < 30; b++) {
      wetEngine.renderBlock(64);
      for (int i = 0; i < 64; i++) {
        wetTail += Math.abs((long) wetEngine.masterBuffer[i].l);
      }
    }
    assertTrue(wetTail > 0, "Song-level reverb send must excite masterReverb from a dry track");

    // 3. Pre-reverb delay ordering (song.cpp:2538-2552, mod_controllable_audio.cpp:195):
    // Enable song delay (syncLevel=9 -> ~826 samples = block 12-13). Render block 0 so the pulse
    // enters masterDelay, then immediately mute/clear masterReverb after block 0.
    // When the first delayed echo emerges around block 12-13, it must feed song reverb send and
    // re-excite masterReverb, leaving a wet reverb tail on blocks 16..20 (between echo 1 and 2).
    project.setSongParamDelayFeedback(0.75f);
    FirmwareAudioEngine delayEngine = new FirmwareAudioEngine();
    delayEngine.sounds.add(new SingleBlockDryPulse());
    delayEngine.syncMasterEffects(project);
    delayEngine.renderBlock(64);
    delayEngine.masterReverb.mute();

    // Advance through the first echo (blocks 1..15).
    for (int b = 1; b <= 15; b++) {
      delayEngine.renderBlock(64);
    }

    // Between echo 1 (~sample 826) and echo 2 (~sample 1652), blocks 16..20 (samples 1024..1343)
    // contain no dry delay impulse, only the reverb tail excited by echo 1.
    long echoReverbTail = 0;
    for (int b = 16; b <= 20; b++) {
      delayEngine.renderBlock(64);
      for (int i = 0; i < 64; i++) {
        echoReverbTail += Math.abs((long) delayEngine.masterBuffer[i].l);
      }
    }
    assertTrue(
        echoReverbTail > 0,
        "Pre-reverb song delay echo must feed song reverb send and excite masterReverb");
  }

  @Test
  void negativeQ31SynthReverbKnobBeatsDefaultSongReverbAmountForAutoDucking() {
    ProjectModel project = new ProjectModel();
    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.syncMasterEffects(project);
    assertEquals(
        Integer.MIN_VALUE,
        engine.songReverbAmount,
        "Default songParamReverbAmount=0.0f must map to Q31 minimum (0x80000000)");

    FirmwareSound sound = new FirmwareSound();
    // Negative Q31 knob (0xA0000000 = -1610612736) is still above 0x80000000 (off).
    sound.paramKnobs[Param.GLOBAL_REVERB_AMOUNT] = 0xA0000000;
    sound.paramKnobsPopulated = true;
    engine.sounds.add(sound);

    PatchCable cable = new PatchCable();
    cable.from = PatchSource.SIDECHAIN;
    cable.amount = 500000000;
    sound.paramManager.getPatchCableSet().addCable(Param.GLOBAL_VOLUME_POST_REVERB_SEND, cable);

    sound.triggerNote(60, 100);
    engine.renderBlock(64);

    assertNotEquals(
        0,
        engine.reverbSidechainVolumeInEffect,
        "A synth with negative Q31 reverb knob above 0x80000000 must win over default songReverbAmount");
    assertEquals(
        Functions.getParamNeutralValue(Param.GLOBAL_DELAY_RATE),
        engine.delayState.userDelayRate,
        0x7FFFFFFF);
  }
}
