package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.deluge.BridgeContract;
import org.deluge.firmware2.GlobalEffectable;
import org.deluge.firmware2.GlobalSidechainBus;
import org.deluge.firmware2.Param;
import org.deluge.firmware2.Stutterer;
import org.deluge.model.FilterMode;
import org.deluge.model.ProjectModel;
import org.deluge.project.ProjectSerializer;
import org.deluge.xml.SongXmlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parity tests for song-level {@code <reverb>}, {@code <audioCompressor>}, and {@code <stutter>}
 * across XML, {@link ProjectModel}, {@link BridgeContract}, {@link FirmwareAudioEngine}, and {@link
 * PureFirmwareEngine} (C {@code song.cpp:179-192, 1252-1275, 1447-1514}, {@code
 * mod_controllable_audio.cpp:740-880, 1299-1329}, {@code audio_engine.cpp:1094, 1477-1546,
 * 1608-1620}).
 */
class SongReverbCompStutterParityTest {

  private static final class RampSignalEffectable extends GlobalEffectable {
    private int nextSample = 1000;

    @Override
    protected void renderInternal(int[] buffer, int numSamples, int[] reverbBuffer) {
      for (int i = 0; i < numSamples; i++) {
        int val = nextSample;
        nextSample += 2048;
        buffer[i * 2] = val;
        buffer[i * 2 + 1] = val;
        if (reverbBuffer != null && i < reverbBuffer.length) {
          reverbBuffer[i] += val >> 2;
        }
      }
    }
  }

  @Test
  void reverbCompressorAndStutterRoundTripThroughXml(@TempDir Path tempDir) throws Exception {
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song
          firmwareVersion="4.1.3"
          earliestCompatibleFirmware="4.1.0"
          tempo="128.0"
          inputTickMagnitude="2">
          <reverb
            roomSize="1202590843"
            dampening="1546188226"
            width="-2147483648"
            pan="-536870912"
            model="1"
            hpf="107374182"
            lpf="1610612735">
            <compressor
              attack="327244"
              release="936"
              volume="-21474836"
              shape="-601295424"
              syncLevel="4" />
          </reverb>
          <audioCompressor
            attack="83886080"
            release="83886080"
            thresh="268435456"
            ratio="1342177280"
            compHPF="536870912"
            compBlend="1610612735" />
          <stutter
            quantized="0"
            reverse="1"
            pingPong="1" />
          <songParams />
          <instruments />
          <sessionClips />
          <arrangementOnlyTracks />
        </song>
        """;

    Path inPath = tempDir.resolve("IN_SONG.XML");
    Files.writeString(inPath, xml);

    ProjectModel loaded = SongXmlParser.parseSong(inPath.toFile());
    // C song.cpp:1465-1467 — width == -2147483648 wraps to 2147483647 (1.0f).
    assertEquals(1.0f, loaded.getReverbWidth(), 1e-6f);
    assertEquals(1610612735.0f / 2147483647.0f, loaded.getReverbLpf(), 1e-6f);
    assertEquals(-536870912.0f / 2147483647.0f, loaded.getReverbPan(), 1e-6f);
    assertEquals(-21474836.0f / 2147483647.0f, loaded.getReverbCompressorVolume(), 1e-6f);
    assertEquals(-601295424.0f / 2147483647.0f, loaded.getReverbCompressorShape(), 1e-6f);
    assertEquals(4, loaded.getReverbCompressorSyncLevel());
    assertEquals(536870912.0f / 2147483647.0f, loaded.getCompressorHpf(), 1e-6f);
    assertEquals(1610612735.0f / 2147483647.0f, loaded.getCompressorBlend(), 1e-6f);
    assertFalse(loaded.isSongStutterQuantized());
    assertTrue(loaded.isSongStutterReversed());
    assertTrue(loaded.isSongStutterPingPong());

    Path outPath = tempDir.resolve("OUT_SONG.XML");
    ProjectSerializer.save(loaded, outPath.toFile());
    String savedText = Files.readString(outPath);

    assertTrue(savedText.contains("lpf=\"1610612735\""), "Expected reverb lpf in saved XML");
    assertTrue(
        savedText.contains("volume=\"-21474836\""),
        "Expected signed reverb compressor volume in saved XML");
    assertTrue(
        savedText.contains("shape=\"-601295424\""),
        "Expected signed reverb compressor shape in saved XML");
    assertTrue(savedText.contains("syncLevel=\"4\""), "Expected reverb compressor syncLevel=4");
    assertTrue(savedText.contains("compHPF=\"536870912\""), "Expected audioCompressor compHPF");
    assertTrue(
        savedText.contains("compBlend=\"1610612735\""), "Expected audioCompressor compBlend");
    assertTrue(savedText.contains("quantized=\"0\""), "Expected stutter quantized=0");
    assertTrue(savedText.contains("reverse=\"1\""), "Expected stutter reverse=1");
    assertTrue(savedText.contains("pingPong=\"1\""), "Expected stutter pingPong=1");

    ProjectModel reloaded = SongXmlParser.parseSong(outPath.toFile());
    assertEquals(loaded.getReverbLpf(), reloaded.getReverbLpf(), 1e-6f);
    assertEquals(loaded.getReverbCompressorVolume(), reloaded.getReverbCompressorVolume(), 1e-6f);
    assertEquals(loaded.getReverbCompressorShape(), reloaded.getReverbCompressorShape(), 1e-6f);
    assertEquals(4, reloaded.getReverbCompressorSyncLevel());
    assertEquals(loaded.getCompressorHpf(), reloaded.getCompressorHpf(), 1e-6f);
    assertEquals(loaded.getCompressorBlend(), reloaded.getCompressorBlend(), 1e-6f);
    assertFalse(reloaded.isSongStutterQuantized());
    assertTrue(reloaded.isSongStutterReversed());
    assertTrue(reloaded.isSongStutterPingPong());
  }

  @Test
  void songParserIgnoresDeepInstrumentTagsWhenSongLevelTagsAreOmitted(@TempDir Path tempDir)
      throws Exception {
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song
          firmwareVersion="4.1.3"
          earliestCompatibleFirmware="4.1.0"
          tempo="120.0">
          <instruments>
            <sound name="SYNTH1" lpfMode="12dB">
              <delay pingPong="1" analog="1" syncLevel="9" />
              <audioCompressor
                attack="2147483647"
                release="2147483647"
                thresh="2147483647"
                ratio="2147483647"
                compHPF="2147483647"
                compBlend="0" />
              <stutter quantized="0" reverse="1" pingPong="1" />
            </sound>
          </instruments>
          <sessionClips />
          <arrangementOnlyTracks />
        </song>
        """;

    Path inPath = tempDir.resolve("DEEP_TAGS_SONG.XML");
    Files.writeString(inPath, xml);

    ProjectModel loaded = SongXmlParser.parseSong(inPath.toFile());
    // Song-level defaults must remain intact and NOT inherit the nested <sound> child elements.
    assertEquals(FilterMode.LADDER_24, loaded.getSongLpfMode());
    assertEquals(0, loaded.getDelayPingPong());
    assertEquals(0, loaded.getDelayAnalog());
    assertEquals(0.0f, loaded.getCompressorThreshold(), 1e-6f);
    assertEquals(0.0f, loaded.getCompressorHpf(), 1e-6f);
    assertEquals(1.0f, loaded.getCompressorBlend(), 1e-6f);
    assertTrue(loaded.isSongStutterQuantized());
    assertFalse(loaded.isSongStutterReversed());
    assertFalse(loaded.isSongStutterPingPong());
  }

  @Test
  void syncMasterEffectsAndPureFirmwareEngineSyncReverbLpfCompHpfAndManualReverbSidechain() {
    ProjectModel project = new ProjectModel();
    project.setInputTickMagnitude(2);
    project.setReverbLpf(0.65f);
    project.setReverbHpf(0.15f);
    project.setReverbPan(-0.25f);
    project.setReverbCompressorVolume(0.5f); // >= 0 -> manual mode (C audio_engine.cpp:1543-1546)
    project.setReverbCompressorShape(-0.2f);
    project.setReverbCompressorAttack(0.1f);
    project.setReverbCompressorRelease(0.2f);
    project.setReverbCompressorSyncLevel(4); // file syncLevel=4, mag=2 -> internal sync=3
    project.setCompressorHpf(0.35f);
    project.setCompressorBlend(0.75f);
    project.setSongStutterQuantized(false);
    project.setSongStutterReversed(true);
    project.setSongStutterPingPong(true);

    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.syncMasterEffects(project);
    assertEquals(0.65f, engine.masterReverb.getLPF(), 1e-5f);
    assertEquals(0.15f, engine.masterReverb.getHPF(), 1e-5f);
    assertEquals(3, engine.reverbSidechainSync);
    assertTrue(engine.reverbSidechainVolume > 0);
    assertEquals(
        (int) Math.round((double) 0.35f * 2147483647.0), engine.masterCompressor.getSidechain());
    assertEquals(
        (int) Math.round((double) 0.75f * 2147483647.0), engine.masterCompressor.getBlend());
    assertFalse(engine.songStutterConfig.quantized);
    assertTrue(engine.songStutterConfig.reversed);
    assertTrue(engine.songStutterConfig.pingPong);

    // Manual reverb sidechain mode in renderBlock must copy manual settings into in-effect state.
    engine.sounds.add(new RampSignalEffectable());
    GlobalSidechainBus.registerHit(1 << 28);
    engine.renderBlock(32);
    assertEquals(engine.reverbSidechainVolume, engine.reverbSidechainVolumeInEffect);
    assertEquals(engine.reverbSidechainShape, engine.reverbSidechainShapeInEffect);
    assertEquals(3, engine.reverbSidechain.syncLevel);

    // PureFirmwareEngine bridge sync path must also forward LPF, HPF, pan, reverb comp, and comp
    // HPF.
    PureFirmwareEngine pureEngine = new PureFirmwareEngine();
    BridgeContract bridge = new BridgeContract();
    bridge.clear();
    pureEngine.setProject(project);
    bridge.setGlobalFloat(BridgeContract.G_REVERB_LPF, 0.42);
    bridge.setGlobalFloat(BridgeContract.G_REVERB_HPF, 0.12);
    bridge.setGlobalFloat(BridgeContract.G_REVERB_PAN, 0.30);
    bridge.setGlobalFloat(BridgeContract.G_REVERB_COMP_VOLUME, 0.25);
    bridge.setGlobalFloat(BridgeContract.G_REVERB_COMP_SHAPE, -0.15);
    bridge.setGlobalInt(BridgeContract.G_REVERB_COMP_SYNC_LEVEL, 4L);
    bridge.setGlobalFloat(BridgeContract.G_MASTER_COMP_HPF, 0.40);
    pureEngine.syncFromBridge(bridge);

    FirmwareAudioEngine pureAudio = pureEngine.getAudioEngine();
    assertEquals(0.42f, pureAudio.masterReverb.getLPF(), 1e-5f);
    assertEquals(0.12f, pureAudio.masterReverb.getHPF(), 1e-5f);
    assertEquals((int) Math.round(0.30 * 2147483647.0), pureAudio.reverbPan);
    assertEquals((int) Math.round(0.25 * 2147483647.0), pureAudio.reverbSidechainVolume);
    assertEquals((int) Math.round(-0.15 * 2147483647.0), pureAudio.reverbSidechainShape);
    assertEquals(3, pureAudio.reverbSidechainSync);
    assertEquals(
        (int) Math.round((double) 0.40f * 2147483647.0), pureAudio.masterCompressor.getSidechain());
    assertFalse(pureAudio.songStutterConfig.quantized);
    assertTrue(pureAudio.songStutterConfig.reversed);
    assertTrue(pureAudio.songStutterConfig.pingPong);
  }

  @Test
  void songLevelStutterCapturesAndReplaysMasterBufferAndRestoresRateOnEnd() {
    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);
    project.setInputTickMagnitude(2);
    project.setSongParamStutterRate(1.0f); // fast stutter buffer (~64th note)
    project.setSongStutterQuantized(true);
    project.setSongStutterReversed(false);
    project.setSongStutterPingPong(false);
    project.rebuildSongParamManager();

    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.sounds.add(new RampSignalEffectable());
    engine.syncMasterEffects(project);

    int initialRate = project.getSongParamManager().getUnpatchedValue(Param.UNPATCHED_STUTTER_RATE);
    assertTrue(initialRate > 0);

    try {
      engine.beginSongStutter(project.getSongParamManager());
      assertTrue(Stutterer.GLOBAL.isStuttering(engine));
      // Quantized beginStutter zeroes UNPATCHED_STUTTER_RATE while stuttering.
      assertEquals(
          0, project.getSongParamManager().getUnpatchedValue(Param.UNPATCHED_STUTTER_RATE));

      // Render enough blocks to finish RECORDING and enter PLAYING.
      int blocksRendered = 0;
      for (int b = 0; b < 40; b++) {
        engine.renderBlock(64);
        blocksRendered++;
      }
      assertTrue(blocksRendered > 0);
      assertTrue(Stutterer.GLOBAL.isActive());

      // Capture one playing block and verify it produces non-zero repeated audio.
      long energy = 0;
      for (int i = 0; i < 64; i++) {
        energy += Math.abs((long) engine.masterBuffer[i].l);
      }
      assertTrue(energy > 0, "Song-level stutter playback must produce non-zero master output");

      engine.endSongStutter(project.getSongParamManager());
      assertFalse(Stutterer.GLOBAL.isStuttering(engine));
      assertEquals(
          initialRate,
          project.getSongParamManager().getUnpatchedValue(Param.UNPATCHED_STUTTER_RATE),
          "endSongStutter must restore quantized UNPATCHED_STUTTER_RATE");
    } finally {
      engine.endSongStutter(project.getSongParamManager());
    }
  }
}
