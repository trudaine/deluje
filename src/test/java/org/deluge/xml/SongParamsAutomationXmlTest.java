package org.deluge.xml;

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
import org.deluge.model.ProjectModel;
import org.deluge.modulation.automation.ParamNode;
import org.deluge.project.ProjectSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * C++ parity tests for 16-char automation hex nodes ({@code 0xCCCCCCCCVVVVVVVVPPPPPPPP...}) in
 * {@code <songParams>}, {@code <equalizer>} tag naming, and {@code modFXCurrentParam} / {@code
 * currentFilterType} placement on {@code <song>} (C song.cpp:1222, 1275-1279, 2070-2073, 2098;
 * global_effectable.cpp:791-882, 884-1075; mod_controllable_audio.cpp:490-714).
 */
class SongParamsAutomationXmlTest {

  @Test
  void songParamsDirectAttrsAndSubElementsRoundTripWithAutomation(@TempDir Path tempDir)
      throws Exception {
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song
            firmwareVersion="4.1.0-alpha"
            modFXCurrentParam="feedback"
            currentFilterType="hpf">
          <songParams
              volume="0x0000000010000000800000006000000000000780"
              pan="0x000000002000000080000060"
              reverbAmount="0x8000000030000000000000C0"
              pitchAdjust="0x000000004000000080000180"
              lpfMorph="0x800000005000000080000300">
            <delay
                rate="0x000000001111111180000060"
                feedback="0x800000002222222200000780" />
            <lpf
                frequency="0x7FFFFFFF3333333380000060"
                resonance="0x8000000044444444000000C0"
                morph="0x800000005000000080000300" />
            <hpf
                frequency="0x800000005555555580000060"
                resonance="0x8000000066666666000000C0" />
            <equalizer
                bass="0x000000007777777780000060"
                treble="0x0000000012345678000000C0"
                bassFrequency="0x00000000"
                trebleFrequency="0x00000000" />
          </songParams>
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
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "AutoSongParams");

    // modFXCurrentParam & currentFilterType read from <song> (C song.cpp:2098 ->
    // global_effectable.cpp:1063-1075).
    assertEquals("feedback", parsed.getModFXCurrentParam());
    assertEquals("hpf", parsed.getCurrentFilterType());

    // Direct attributes with arrangement-length node at pos=1920 (0x780), well past 96-tick clip
    List<ParamNode> volNodes = parsed.getSongParamAutomationCurves().get("volume");
    assertNotNull(volNodes);
    assertEquals(2, volNodes.size());
    assertEquals(0, volNodes.get(0).pos);
    assertEquals(0x10000000, volNodes.get(0).value);
    assertTrue(volNodes.get(0).interpolated);
    assertEquals(1920, volNodes.get(1).pos);
    assertEquals(0x60000000, volNodes.get(1).value);
    assertFalse(volNodes.get(1).interpolated);

    assertEquals(1, parsed.getSongParamAutomationCurves().get("pan").size());
    assertEquals(1, parsed.getSongParamAutomationCurves().get("reverbAmount").size());
    assertEquals(1, parsed.getSongParamAutomationCurves().get("pitchAdjust").size());
    assertEquals(1, parsed.getSongParamAutomationCurves().get("lpfMorph").size());

    // Sub-elements: <delay>, <lpf>, <hpf>, <equalizer>
    assertEquals(1, parsed.getSongParamAutomationCurves().get("delayRate").size());
    assertEquals(1920, parsed.getSongParamAutomationCurves().get("delayFeedback").get(0).pos);
    assertEquals(1, parsed.getSongParamAutomationCurves().get("lpfFrequency").size());
    assertEquals(1, parsed.getSongParamAutomationCurves().get("lpfResonance").size());
    assertEquals(1, parsed.getSongParamAutomationCurves().get("hpfFrequency").size());
    assertEquals(1, parsed.getSongParamAutomationCurves().get("hpfResonance").size());
    assertEquals(1, parsed.getSongParamAutomationCurves().get("eqBass").size());
    assertEquals(1, parsed.getSongParamAutomationCurves().get("eqTreble").size());

    // Round-trip save and re-parse
    File out = tempDir.resolve("AutoSongParamsRoundTrip.XML").toFile();
    ProjectSerializer.save(parsed, out);
    String savedXml = Files.readString(out.toPath(), StandardCharsets.UTF_8);

    // Verify C++ <equalizer> tag (mod_controllable_audio.cpp:540) and <song> attributes
    assertTrue(savedXml.contains("modFXCurrentParam=\"feedback\""));
    assertTrue(savedXml.contains("currentFilterType=\"hpf\""));
    assertTrue(savedXml.contains("pitchAdjust=\"0x000000004000000080000180\""));
    assertTrue(savedXml.contains("<equalizer bass=\"0x000000007777777780000060\""));
    assertFalse(savedXml.contains("<eq "));

    ProjectModel reparsed = SongXmlParser.parseSong(out);
    assertEquals("feedback", reparsed.getModFXCurrentParam());
    assertEquals("hpf", reparsed.getCurrentFilterType());
    assertEquals(2, reparsed.getSongParamAutomationCurves().get("volume").size());
    assertEquals(1, reparsed.getSongParamAutomationCurves().get("pitchAdjust").size());
    assertEquals(1, reparsed.getSongParamAutomationCurves().get("delayFeedback").size());
    assertEquals(1, reparsed.getSongParamAutomationCurves().get("lpfFrequency").size());
    assertEquals(1, reparsed.getSongParamAutomationCurves().get("eqBass").size());
  }

  @Test
  void legacySongParamsEqAndModFxAttrsStillParseAndUiEditsClearRawHex() throws Exception {
    String legacyXml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song firmwareVersion="4.1.0-alpha">
          <songParams
              volume="0x7FFFFFFF"
              modFXCurrentParam="rate"
              currentFilterType="hpf">
            <eq
                bass="0x40000000"
                treble="0xC0000000"
                bassFrequency="0x00000000"
                trebleFrequency="0x00000000" />
          </songParams>
        </song>
        """;

    ProjectModel parsed =
        SongXmlParser.parseSong(
            new ByteArrayInputStream(legacyXml.getBytes(StandardCharsets.UTF_8)),
            "LegacySongParams");
    assertEquals("rate", parsed.getModFXCurrentParam());
    assertEquals("hpf", parsed.getCurrentFilterType());
    assertTrue(parsed.getSongParamEqBass() > 0.4f);
    assertTrue(parsed.getSongParamEqTreble() < -0.4f);
    assertEquals("0x7FFFFFFF", parsed.getRawSongParamHex().get("volume"));
    assertEquals("0x40000000", parsed.getRawSongParamHex().get("eqBass"));

    // UI setter clears rawSongParamHex for that key so edited float wins on save
    parsed.setSongParamVolume(0.25f);
    assertFalse(parsed.getRawSongParamHex().containsKey("volume"));
    assertEquals("0x40000000", parsed.getRawSongParamHex().get("eqBass"));
  }
}
