package org.deluge.modulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.deluge.firmware2.Param;
import org.deluge.model.ProjectModel;
import org.deluge.modulation.automation.AutoParam;
import org.deluge.modulation.automation.ParamNode;
import org.deluge.playback.Arrangement;
import org.deluge.project.ProjectSerializer;
import org.deluge.xml.SongXmlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * C++ parity tests for {@link AutoParam} float interpolation ({@code UNPATCHED_TEMPO}, C
 * auto_param.cpp:778-995, param_set.cpp:398-407), {@code <songParams tempo="0x...">} XML round-trip
 * (C global_effectable.cpp:848, 980-983, song.cpp:3121), hardware {@code timePerTimerTick}/{@code
 * timerTickFraction} BPM decoding (C song.cpp:1665-1675, 2125-2129, song.h:443-457), and live
 * {@link Arrangement} / {@link ProjectModel} song-level automation playback (C
 * arrangement.cpp:120-129, 359-365, song.cpp:5822, 6062-6070).
 */
class SongParamsLiveAutomationAndTempoTest {

  @Test
  void autoParamFloatInterpolationAdvancesSmallTempoRampWhereIntegerDivisionStalls() {
    // 120.00 BPM (12000) -> 121.00 BPM (12100) over 96 ticks.
    // halfDistance = (12100 >> 1) - (12000 >> 1) = 50.
    // Integer division 50 / 96 == 0, so without float interpolation the ramp stalls at 12000.
    AutoParam tempoParam = new AutoParam(Param.UNPATCHED_TEMPO);
    assertTrue(tempoParam.shouldInterpolateWithFloat());

    AutoParam volumeParam = new AutoParam(Param.UNPATCHED_VOLUME);
    assertFalse(volumeParam.shouldInterpolateWithFloat());

    tempoParam.addNode(0, 12000, false);
    tempoParam.addNode(96, 12100, true);

    tempoParam.setPlayPos(0, Integer.MAX_VALUE, false);
    assertEquals(12000, tempoParam.currentValue);
    assertTrue(tempoParam.hasInterpolationIncrement());
    assertEquals(0, tempoParam.valueIncrementPerHalfTick);
    assertEquals(50.0f / 96.0f, tempoParam.valueIncrementPerHalfTickFloat, 1e-6f);

    // Advance 48 ticks (halfway) one tick at a time via tickTicks(1)
    for (int t = 0; t < 48; t++) {
      tempoParam.tickTicks(1);
    }
    assertEquals(12050, tempoParam.currentValue, 1);

    // Advance remaining 48 ticks
    for (int t = 0; t < 48; t++) {
      tempoParam.tickTicks(1);
    }
    assertEquals(12100, tempoParam.currentValue, 1);
  }

  @Test
  void autoParamFloatInterpolationTickSamplesHandlesUnsignedTimePerTickInverseAndPingpong() {
    AutoParam tempoParam = new AutoParam(Param.UNPATCHED_TEMPO);
    tempoParam.addNode(0, 10000, false);
    tempoParam.addNode(48, 14800, true);
    tempoParam.setPlayPos(0, Integer.MAX_VALUE, false);

    // Verify unsigned uint32_t interpretation when bit 31 is set (0x80000000 == 2^31):
    // halfTicks = 2^31 * (1 / 2^32) * 6 * 16 = 0.5 * 96 = 48 half-ticks = 24 full ticks (halfway).
    int unsignedHalfScale = 0x80000000;
    assertTrue(tempoParam.tickSamples(16, unsignedHalfScale));
    assertEquals(12400, tempoParam.currentValue, 1);

    // Seed a fractional remainder and verify notifyPingpongOccurred flips both slope and remainder.
    tempoParam.interpolationIncrementRemainderFloat = 0.375f;
    float slopeBefore = tempoParam.valueIncrementPerHalfTickFloat;
    tempoParam.notifyPingpongOccurred();
    assertEquals(-slopeBefore, tempoParam.valueIncrementPerHalfTickFloat, 1e-6f);
    assertEquals(-0.375f, tempoParam.interpolationIncrementRemainderFloat, 1e-6f);
  }

  @Test
  void hardwareTimePerTimerTickAndSongParamsTempoRoundTrip(@TempDir Path tempDir) throws Exception {
    // 140.00 BPM at inputTickMagnitude=2:
    // scaledBPM = 140 * 4 = 560; timePerTimerTick = 110250 / 560 = 196.875.
    // 196.875 * 2^32 = 845571686400 = (196L << 32) | 0xE0000000L (-536870912).
    // <songParams tempo> carries 14000 (0x000036B0) at pos 0 -> 16000 (0x00003E80) at pos 384
    // (0x180).
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song
            firmwareVersion="c1.3.0"
            inputTickMagnitude="2"
            timePerTimerTick="196"
            timerTickFraction="-536870912">
          <songParams
              tempo="0x000036B0000036B08000000000003E8080000180"
              volume="0x00000000" />
          <instruments>
            <sound name="SYNTH1" presetSlot="0" />
          </instruments>
          <sessionClips>
            <instrumentClip instrumentPresetSlot="0" length="96" />
          </sessionClips>
        </song>
        """;

    ProjectModel parsed =
        SongXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "HwTempoSong");

    // Decoded from hardware timePerTimerTick / timerTickFraction / inputTickMagnitude
    assertEquals(140.0f, parsed.getBpm(), 0.01f);

    List<ParamNode> tempoNodes = parsed.getSongParamAutomationCurves().get("tempo");
    assertNotNull(tempoNodes);
    assertEquals(2, tempoNodes.size());
    assertEquals(0, tempoNodes.get(0).pos);
    assertEquals(14000, tempoNodes.get(0).value);
    assertTrue(tempoNodes.get(0).interpolated);
    assertEquals(384, tempoNodes.get(1).pos);
    assertEquals(16000, tempoNodes.get(1).value);
    assertTrue(tempoNodes.get(1).interpolated);

    AutoParam liveTempo = parsed.getSongParamManager().getAutomatedParam(Param.UNPATCHED_TEMPO);
    assertNotNull(liveTempo);
    assertEquals(2, liveTempo.nodes.size());

    File out = tempDir.resolve("HwTempoSongRoundTrip.XML").toFile();
    ProjectSerializer.save(parsed, out);
    String savedXml = Files.readString(out.toPath(), StandardCharsets.UTF_8);
    assertTrue(savedXml.contains("tempo=\"0x000036B0000036B08000000000003E8080000180\""));

    // User BPM edit updates the base hex while preserving automation nodes on save
    parsed.setBpm(150.0f);
    assertFalse(parsed.getRawSongParamHex().containsKey("tempo"));
    File outEdited = tempDir.resolve("HwTempoSongEdited.XML").toFile();
    ProjectSerializer.save(parsed, outEdited);
    String editedXml = Files.readString(outEdited.toPath(), StandardCharsets.UTF_8);
    assertTrue(editedXml.contains("tempo=\"0x00003A98000036B08000000000003E8080000180\""));
  }

  @Test
  void arrangementStepsSongParamAutomationAndUpdatesBpmLive() throws Exception {
    // Tempo ramps 120.00 BPM (0x00002EE0) -> 140.00 BPM (0x000036B0) over 96 ticks (0x60),
    // then jumps (uninterpolated) to 100.00 BPM (0x00002710) at pos 1920 (0x780).
    // Volume ramps 0.5 (0x00000000) -> 1.0 (0x7FFFFFFF) over 96 ticks (0x60).
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song firmwareVersion="c1.3.0" tempo="120.0">
          <songParams
              tempo="0x00002EE000002EE000000000000036B0800000600000271000000780"
              volume="0x0000000000000000000000007FFFFFFF80000060" />
        </song>
        """;

    ProjectModel project =
        SongXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "LiveArrSong");
    Arrangement arrangement = new Arrangement(project);

    arrangement.resetPlayPos(0);
    arrangement.advance(0); // Initial tick at pos 0 sets up interpolation slopes
    assertEquals(120.0f, project.getBpm(), 0.05f);
    assertEquals(0.5f, project.getSongParamVolume(), 0.01f);
    // While tick-domain interpolation is active, ticksTilNextEvent is 0 so transport wakes every
    // tick.
    assertEquals(0, arrangement.swungTicksTilNextEvent);
    assertEquals(0, project.getSwungTicksTilNextEvent());

    // Step to pos 24 (25% of 96-tick ramp): BPM ~ 125.0, volume ~ 0.625
    arrangement.advance(24);
    assertEquals(125.0f, project.getBpm(), 0.15f);
    assertEquals(0.625f, project.getSongParamVolume(), 0.02f);

    // Step to pos 48 (50% of 96-tick ramp): BPM ~ 130.0, volume ~ 0.75
    arrangement.advance(24);
    assertEquals(130.0f, project.getBpm(), 0.15f);
    assertEquals(0.75f, project.getSongParamVolume(), 0.02f);

    // Step to pos 96 (end of ramp): BPM == 140.0, volume == 1.0
    arrangement.advance(48);
    assertEquals(140.0f, project.getBpm(), 0.05f);
    assertEquals(1.0f, project.getSongParamVolume(), 0.01f);

    // Next node is at pos 1920 (1824 ticks away), uninterpolated, so transport sleeps until 1920!
    assertEquals(1824, arrangement.swungTicksTilNextEvent);
    assertEquals(1824, project.getSwungTicksTilNextEvent());

    // Step across pos 1920 (well past any short clip loop length, proving effectiveLength ==
    // Integer.MAX_VALUE)
    arrangement.advance(1824);
    assertEquals(100.0f, project.getBpm(), 0.05f);
  }
}
