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
import org.deluge.model.AudioTrackModel;
import org.deluge.model.ClipModel;
import org.deluge.model.PatternModel;
import org.deluge.model.ProjectModel;
import org.deluge.modulation.automation.ParamNode;
import org.deluge.project.ProjectSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * C++ parity tests for 16-char automation hex nodes ({@code 0xCCCCCCCCVVVVVVVVPPPPPPPP...}) in
 * {@code <kitParams>} and {@code <audioClip><params>} (C global_effectable.cpp:811-1037,
 * mod_controllable_audio.cpp:490-714, auto_param.cpp:2005-2148).
 */
class GlobalEffectableAutomationXmlTest {

  @Test
  void kitParamsDirectAttrsAndSubElementsRoundTripWithAutomation(@TempDir Path tempDir)
      throws Exception {
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song firmwareVersion="4.1.0-alpha">
          <instruments>
            <kit name="AUTOKIT" presetSlot="0">
              <soundSources>
                <sound name="KICK">
                  <osc1 type="sample" fileName="SAMPLES/KICK.WAV" />
                </sound>
              </soundSources>
            </kit>
          </instruments>
          <sessionClips>
            <instrumentClip instrumentPresetSlot="0" length="96">
              <kitParams
                  volume="0x7FFFFFFF00000000800000004000000000000030"
                  pan="0x000000003000000080000018"
                  reverbAmount="0x800000002000000000000030"
                  pitchAdjust="0x000000001000000080000018"
                  lpfMorph="0x800000005000000080000030">
                <delay
                    rate="0x000000002222222280000018"
                    feedback="0x8000000033333333000000307777777700000090" />
                <lpf
                    frequency="0x7FFFFFFF4444444480000018"
                    resonance="0x800000005555555500000030" />
                <hpf
                    frequency="0x800000001111111180000018"
                    resonance="0x800000006666666600000030" />
                <equalizer
                    bass="0x000000001234567880000018"
                    treble="0x000000002345678900000030"
                    bassFrequency="0x00000000"
                    trebleFrequency="0x00000000" />
              </kitParams>
              <noteRows>
                <noteRow drumIndex="0" noteData="0x0000000000000018407F" />
              </noteRows>
            </instrumentClip>
          </sessionClips>
        </song>
        """;

    ProjectModel parsed =
        SongXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "AutoKitSong");
    ClipModel clip = parsed.getTracks().get(0).getClips().get(0);

    // Direct attributes: volume, pan, reverbAmount, pitchAdjust, lpfMorph
    List<ParamNode> volNodes = clip.getAutomationCurves().get("volume");
    assertNotNull(volNodes);
    assertEquals(2, volNodes.size());
    assertEquals(0, volNodes.get(0).pos);
    assertEquals(0x00000000, volNodes.get(0).value);
    assertTrue(volNodes.get(0).interpolated);
    assertEquals(48, volNodes.get(1).pos);
    assertEquals(0x40000000, volNodes.get(1).value);
    assertFalse(volNodes.get(1).interpolated);

    assertEquals(1, clip.getAutomationCurves().get("pan").size());
    assertEquals(0x30000000, clip.getAutomationCurves().get("pan").get(0).value);
    assertEquals(1, clip.getAutomationCurves().get("pitchAdjust").size());
    assertEquals(
        0x00000000,
        Integer.parseUnsignedInt(clip.getRawKitParamHex().get("pitchAdjust").substring(2), 16));
    assertEquals(1, clip.getAutomationCurves().get("lpfMorph").size());

    // Sub-elements: <delay>, <lpf>, <hpf>, <equalizer>
    assertEquals(1, clip.getAutomationCurves().get("delayRate").size());
    // Out-of-bounds node at pos=144 (0x90) is clamped out by readUpTo = length = 96
    List<ParamNode> delayFbNodes = clip.getAutomationCurves().get("delayFeedback");
    assertEquals(1, delayFbNodes.size(), "Node beyond clip length=96 must be ignored");
    assertEquals(48, delayFbNodes.get(0).pos);
    assertEquals(0x33333333, delayFbNodes.get(0).value);

    assertEquals(1, clip.getAutomationCurves().get("lpfFrequency").size());
    assertEquals(1, clip.getAutomationCurves().get("lpfResonance").size());
    assertEquals(1, clip.getAutomationCurves().get("hpfFrequency").size());
    assertEquals(1, clip.getAutomationCurves().get("hpfResonance").size());
    assertEquals(1, clip.getAutomationCurves().get("eqBass").size());
    assertEquals(1, clip.getAutomationCurves().get("eqTreble").size());

    // Round-trip save and re-parse
    File out = tempDir.resolve("AutoKitSongRoundTrip.XML").toFile();
    ProjectSerializer.save(parsed, out);
    String savedXml = Files.readString(out.toPath(), StandardCharsets.UTF_8);
    assertTrue(
        savedXml.contains("pitchAdjust=\"0x000000001000000080000018\""),
        "pitchAdjust base hex and automation node must round-trip on <kitParams>");
    assertTrue(
        savedXml.contains("<delay rate=\"0x000000002222222280000018\""),
        "<delay rate> automation node must round-trip inside <kitParams>");

    ProjectModel reparsed = SongXmlParser.parseSong(out);
    ClipModel reClip = reparsed.getTracks().get(0).getClips().get(0);
    assertEquals(2, reClip.getAutomationCurves().get("volume").size());
    assertEquals(1, reClip.getAutomationCurves().get("pitchAdjust").size());
    assertEquals(1, reClip.getAutomationCurves().get("delayRate").size());
    assertEquals(1, reClip.getAutomationCurves().get("delayFeedback").size());
    assertEquals(1, reClip.getAutomationCurves().get("lpfFrequency").size());
    assertEquals(1, reClip.getAutomationCurves().get("eqBass").size());
  }

  @Test
  void arrangementOnlyKitParamsAndLpfMorphSubAttrRoundTrip(@TempDir Path tempDir) throws Exception {
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song firmwareVersion="4.1.0-alpha">
          <instruments>
            <kit name="ARRKIT" presetSlot="0">
              <soundSources>
                <sound name="KICK">
                  <osc1 type="sample" fileName="SAMPLES/KICK.WAV" />
                </sound>
              </soundSources>
            </kit>
          </instruments>
          <sessionClips>
            <instrumentClip instrumentPresetSlot="0" length="96">
              <kitParams volume="0x7FFFFFFF" />
              <noteRows>
                <noteRow drumIndex="0" />
              </noteRows>
            </instrumentClip>
          </sessionClips>
          <arrangementOnlyTracks>
            <instrumentClip instrumentPresetSlot="0" length="48">
              <kitParams volume="0x7FFFFFFF1111111180000018">
                <lpf
                    frequency="0x7FFFFFFF"
                    resonance="0x80000000"
                    morph="0x8000000022222222800000189999999900000060" />
              </kitParams>
              <noteRows>
                <noteRow drumIndex="0" />
              </noteRows>
            </instrumentClip>
          </arrangementOnlyTracks>
        </song>
        """;

    ProjectModel parsed =
        SongXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "ArrKitSong");
    ClipModel arrClip = parsed.getTracks().get(0).getClips().get(1);
    assertTrue(arrClip.isArrangementOnly());
    assertEquals(1, arrClip.getAutomationCurves().get("volume").size());
    // <lpf morph="..."> maps to lpfMorph and clamps at length=48 (pos=96/0x60 is ignored)
    List<ParamNode> morphNodes = arrClip.getAutomationCurves().get("lpfMorph");
    assertNotNull(morphNodes);
    assertEquals(1, morphNodes.size());
    assertEquals(24, morphNodes.get(0).pos);
    assertEquals(0x22222222, morphNodes.get(0).value);

    File out = tempDir.resolve("ArrKitSongRoundTrip.XML").toFile();
    ProjectSerializer.save(parsed, out);
    ProjectModel reparsed = SongXmlParser.parseSong(out);
    ClipModel reArrClip = reparsed.getTracks().get(0).getClips().get(1);
    assertTrue(reArrClip.isArrangementOnly());
    assertEquals(1, reArrClip.getAutomationCurves().get("volume").size());
    assertEquals(1, reArrClip.getAutomationCurves().get("lpfMorph").size());
  }

  @Test
  void audioClipParamsDirectAttrsAndCppSubElementsRoundTripWithAutomation(@TempDir Path tempDir)
      throws Exception {
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song firmwareVersion="4.1.0-alpha">
          <instruments>
            <audioTrack name="AUDIO1" />
          </instruments>
          <sessionClips>
            <audioClip trackName="AUDIO1" filePath="SAMPLES/LOOP.WAV" length="96">
              <params
                  volume="0x7FFFFFFF10000000800000005000000000000030"
                  pan="0x000000002000000080000018"
                  pitchAdjust="0x000000003000000080000018"
                  lpfMorph="0x800000004000000080000030">
                <delay
                    rate="0x000000001111111180000018"
                    feedback="0x8000000022222222000000309999999900000090" />
                <lpf
                    frequency="0x7FFFFFFF3333333380000018"
                    resonance="0x800000004444444400000030" />
                <hpf
                    frequency="0x800000005555555580000018"
                    resonance="0x800000006666666600000030" />
                <equalizer
                    bass="0x000000007777777780000018"
                    treble="0x000000001234123400000030"
                    bassFrequency="0x00000000"
                    trebleFrequency="0x00000000" />
              </params>
            </audioClip>
          </sessionClips>
        </song>
        """;

    ProjectModel parsed =
        SongXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "AutoAudioSong");
    AudioTrackModel audioTrack = (AudioTrackModel) parsed.getTracks().get(0);
    AudioTrackModel.AudioClip clip = audioTrack.getAudioClips().get(0);

    assertEquals(2, clip.getAutomationCurves().get("volume").size());
    assertEquals(1, clip.getAutomationCurves().get("pan").size());
    assertEquals(1, clip.getAutomationCurves().get("pitchAdjust").size());
    assertEquals(1, clip.getAutomationCurves().get("lpfMorph").size());
    assertEquals(1, clip.getAutomationCurves().get("delayRate").size());
    assertEquals(
        1,
        clip.getAutomationCurves().get("delayFeedback").size(),
        "Node at pos=144 (0x90) beyond audioClip length=96 must be ignored");
    assertEquals(1, clip.getAutomationCurves().get("lpfFrequency").size());
    assertEquals(1, clip.getAutomationCurves().get("lpfResonance").size());
    assertEquals(1, clip.getAutomationCurves().get("hpfFrequency").size());
    assertEquals(1, clip.getAutomationCurves().get("hpfResonance").size());
    assertEquals(1, clip.getAutomationCurves().get("eqBass").size());
    assertEquals(1, clip.getAutomationCurves().get("eqTreble").size());

    File out = tempDir.resolve("AutoAudioSongRoundTrip.XML").toFile();
    ProjectSerializer.save(parsed, out);
    String savedXml = Files.readString(out.toPath(), StandardCharsets.UTF_8);

    // Verify C++ GlobalEffectable child sub-elements (<delay>, <lpf>, <hpf>, <equalizer>) are
    // written inside <params> (C global_effectable.cpp:855-882).
    assertTrue(savedXml.contains("<delay rate=\"0x000000001111111180000018\""));
    assertTrue(savedXml.contains("<lpf frequency=\"0x7FFFFFFF3333333380000018\""));
    assertTrue(savedXml.contains("<hpf frequency=\"0x800000005555555580000018\""));
    assertTrue(savedXml.contains("<equalizer bass=\"0x000000007777777780000018\""));
    assertTrue(savedXml.contains("pitchAdjust=\"0x000000003000000080000018\""));

    ProjectModel reparsed = SongXmlParser.parseSong(out);
    AudioTrackModel.AudioClip reClip =
        ((AudioTrackModel) reparsed.getTracks().get(0)).getAudioClips().get(0);
    assertEquals(2, reClip.getAutomationCurves().get("volume").size());
    assertEquals(1, reClip.getAutomationCurves().get("pitchAdjust").size());
    assertEquals(1, reClip.getAutomationCurves().get("delayRate").size());
    assertEquals(1, reClip.getAutomationCurves().get("lpfFrequency").size());
    assertEquals(1, reClip.getAutomationCurves().get("hpfResonance").size());
    assertEquals(1, reClip.getAutomationCurves().get("eqBass").size());
  }

  @Test
  void clipCopySnapshotAndUiEditsRespectRawHexState() {
    ClipModel clip = new ClipModel("KitClip", 4, 16);
    clip.setKitParam("volume", 1.0f);
    clip.setRawKitParamHex("volume", "0x7FFFFFFF");
    clip.setRawKitParamHex("pitchAdjust", "0x12345678");
    clip.setAutomationCurve("pitchAdjust", List.of(new ParamNode(24, 0x20000000, true)));

    // deepCopy preserves rawKitParamHex and automationCurves
    ClipModel copy = clip.deepCopy("KitClipCopy");
    assertEquals("0x7FFFFFFF", copy.getRawKitParamHex().get("volume"));
    assertEquals("0x12345678", copy.getRawKitParamHex().get("pitchAdjust"));
    assertEquals(1, copy.getAutomationCurves().get("pitchAdjust").size());

    // UI edit via setKitParam clears rawKitParamHex for that key so the edited float wins on save
    copy.setKitParam("volume", 0.5f);
    assertFalse(
        copy.getRawKitParamHex().containsKey("volume"),
        "setKitParam must clear cached raw hex for edited parameter");
    assertEquals("0x12345678", copy.getRawKitParamHex().get("pitchAdjust"));

    // restoreFrom restores rawKitParamHex
    copy.restoreFrom(clip);
    assertEquals("0x7FFFFFFF", copy.getRawKitParamHex().get("volume"));

    // PatternModel.ClipSnapshot round-trips rawKitParamHex
    PatternModel.ClipSnapshot snapshot = PatternModel.ClipSnapshot.fromClipModel(clip, 0, "DRUMS");
    copy.setKitParam("volume", 0.25f);
    snapshot.applyTo(copy);
    assertEquals("0x7FFFFFFF", copy.getRawKitParamHex().get("volume"));

    // AudioClip setter clears rawParamHex for that key
    AudioTrackModel.AudioClip audioClip = new AudioTrackModel.AudioClip();
    audioClip.setRawParamHex("volume", "0x7FFFFFFF");
    audioClip.setRawParamHex("delayRate", "0x00000000");
    audioClip.setVolume(0.5f);
    assertFalse(audioClip.getRawParamHex().containsKey("volume"));
    assertEquals("0x00000000", audioClip.getRawParamHex().get("delayRate"));
  }
}
