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
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareKit;
import org.deluge.engine.FirmwareSound;
import org.deluge.firmware2.Param;
import org.deluge.model.ClipModel;
import org.deluge.model.NoteRowModel;
import org.deluge.model.ProjectModel;
import org.deluge.modulation.automation.AutoParam;
import org.deluge.modulation.automation.ParamNode;
import org.deluge.project.ProjectSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * C++ parity tests for 16-char automation hex nodes ({@code 0xCCCCCCCCVVVVVVVVPPPPPPPP...}) in
 * {@link DelugeHexMapper}, {@link SongXmlParser}, {@link ProjectSerializer}, and {@link
 * FirmwareFactory} (C auto_param.cpp:2005-2148, sound.cpp:3776-4160, note_row.cpp:3280-3295).
 */
class XmlHexAutomationNodesTest {

  @Test
  void parseAndFormatHexAutomationNodesMatchesCppAutoParam() {
    // Base 0x10000000 + node0(val=-2147483648, pos=0, interp=true)
    // + node1(val=1073741824, pos=24, interp=false) + node2(val=2147483647, pos=48, interp=true)
    String hex = "0x10000000800000008000000040000000000000187FFFFFFF80000030";
    List<ParamNode> nodes = DelugeHexMapper.parseHexAutomationNodes(hex, 96);

    assertEquals(3, nodes.size());
    assertEquals(0, nodes.get(0).pos);
    assertEquals(0x80000000, nodes.get(0).value);
    assertTrue(nodes.get(0).interpolated);

    assertEquals(24, nodes.get(1).pos);
    assertEquals(0x40000000, nodes.get(1).value);
    assertFalse(nodes.get(1).interpolated);

    assertEquals(48, nodes.get(2).pos);
    assertEquals(0x7FFFFFFF, nodes.get(2).value);
    assertTrue(nodes.get(2).interpolated);

    String formatted = DelugeHexMapper.formatHexWithAutomationNodes("0x10000000", nodes);
    assertEquals(hex, formatted);
  }

  @Test
  void parseHexAutomationNodesSkipsOutOfOrderAndAppliesEndpointAtZeroFix() {
    // Out-of-order node at pos=12 after pos=24 is skipped (C auto_param.cpp:2109-2112).
    // Node at pos == readAutomationUpToPos (48) moves to pos=0 when no node at pos=0 exists
    // (firmware <= 3.1.5 fix, C auto_param.cpp:2115-2133).
    String hex = "0x000000001111111100000018222222220000000C33333333800000304444444400000040";
    List<ParamNode> nodes = DelugeHexMapper.parseHexAutomationNodes(hex, 48);

    assertEquals(2, nodes.size());
    assertEquals(0, nodes.get(0).pos, "Node at pos == readAutomationUpToPos must move to pos 0");
    assertEquals(0x33333333, nodes.get(0).value);
    assertTrue(nodes.get(0).interpolated);

    assertEquals(24, nodes.get(1).pos, "Valid node at pos=24 must remain");
    assertEquals(0x11111111, nodes.get(1).value);
    assertFalse(nodes.get(1).interpolated);

    // When a node already exists at pos=0, a node at pos == readAutomationUpToPos is simply
    // ignored.
    String hexWithZero = "0x0000000011111111000000003333333380000030";
    List<ParamNode> nodesWithZero = DelugeHexMapper.parseHexAutomationNodes(hexWithZero, 48);
    assertEquals(1, nodesWithZero.size());
    assertEquals(0, nodesWithZero.get(0).pos);
    assertEquals(0x11111111, nodesWithZero.get(0).value);
  }

  @Test
  void synthClipSoundParamsRoundTripsAndPopulatesParamManager(@TempDir Path tempDir)
      throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song firmwareVersion="4.1.0-alpha">
          <instruments>
            <sound name="AUTOSYNTH" presetSlot="0" />
          </instruments>
          <sessionClips>
            <instrumentClip instrumentPresetSlot="0" length="96">
              <soundParams
                  lpfFrequency="0x1000000020000000800000006000000000000030"
                  lpfMorph="0x050000001500000080000018">
                <envelope1
                    attack="0x8000000000000000800000004000000000000030"
                    decay="0xE0000000"
                    sustain="0x7FFFFFFF"
                    release="0x80000000" />
                <equalizer bass="0x000000003000000080000018" />
              </soundParams>
              <noteRows>
                <noteRow y="60" noteData="0x0000000000000018407F" />
              </noteRows>
            </instrumentClip>
          </sessionClips>
        </song>
        """;

    ProjectModel parsed =
        SongXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "AutoSynthSong");
    ClipModel clip = parsed.getTracks().get(0).getClips().get(0);

    assertEquals(2, clip.getAutomationCurves().get("lpfFrequency").size());
    assertEquals(1, clip.getAutomationCurves().get("lpfMorph").size());
    assertEquals(2, clip.getAutomationCurves().get("envelope1_attack").size());
    assertEquals(1, clip.getAutomationCurves().get("eqBass").size());

    // Verify FirmwareFactory wires parsed curves into FirmwareSound.paramManager.
    ProjectModel fwSong = FirmwareFactory.createSong(parsed);
    FirmwareSound fwSound = (FirmwareSound) fwSong.getTracks().get(0).getActiveClip().getSound();
    assertNotNull(fwSound);

    AutoParam lpfFreqParam = findAutoParam(fwSound.paramManager, Param.LOCAL_LPF_FREQ);
    assertNotNull(lpfFreqParam);
    assertEquals(2, lpfFreqParam.nodes.size());
    assertEquals(0, lpfFreqParam.nodes.get(0).pos);
    assertEquals(0x20000000, lpfFreqParam.nodes.get(0).value);
    assertTrue(lpfFreqParam.nodes.get(0).interpolated);
    assertEquals(48, lpfFreqParam.nodes.get(1).pos);
    assertEquals(0x60000000, lpfFreqParam.nodes.get(1).value);
    assertFalse(lpfFreqParam.nodes.get(1).interpolated);

    AutoParam env1Attack = findAutoParam(fwSound.paramManager, Param.LOCAL_ENV_0_ATTACK);
    assertNotNull(env1Attack);
    assertEquals(2, env1Attack.nodes.size());
    assertEquals(0x40000000, env1Attack.nodes.get(1).value);

    AutoParam eqBass = findAutoParam(fwSound.paramManager, Param.UNPATCHED_BASS);
    assertNotNull(eqBass);
    assertEquals(1, eqBass.nodes.size());
    assertEquals(24, eqBass.nodes.get(0).pos);
    assertEquals(0x30000000, eqBass.nodes.get(0).value);
    assertTrue(eqBass.nodes.get(0).interpolated);

    // Verify ProjectSerializer round-trips 16-char automation hex nodes.
    File savedFile = tempDir.resolve("roundtrip_autosynth.xml").toFile();
    ProjectSerializer.save(parsed, savedFile);
    String savedXml = Files.readString(savedFile.toPath());
    assertTrue(
        savedXml.contains("20000000800000006000000000000030"),
        "Saved XML must preserve lpfFrequency 16-char automation nodes");
    assertTrue(
        savedXml.contains("1500000080000018"),
        "Saved XML must preserve lpfMorph 16-char automation nodes");

    ProjectModel reloaded = SongXmlParser.parseSong(savedFile);
    ClipModel reloadedClip = reloaded.getTracks().get(0).getClips().get(0);
    assertEquals(2, reloadedClip.getAutomationCurves().get("lpfFrequency").size());
    assertEquals(1, reloadedClip.getAutomationCurves().get("lpfMorph").size());
    assertEquals(2, reloadedClip.getAutomationCurves().get("envelope1_attack").size());
    assertEquals(1, reloadedClip.getAutomationCurves().get("eqBass").size());
  }

  @Test
  void kitNoteRowSoundParamsUsesIndependentLengthAndRoundTrips(@TempDir Path tempDir)
      throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    // Independent row length="48": node at pos=24 is kept; node at pos=60 (>= 48) is dropped.
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song firmwareVersion="4.1.0-alpha">
          <instruments>
            <kit name="AUTOKIT" presetSlot="0">
              <soundSources>
                <sound name="KICK" osc1Type="sine" />
              </soundSources>
            </kit>
          </instruments>
          <sessionClips>
            <instrumentClip instrumentPresetSlot="0" length="96">
              <noteRows>
                <noteRow drumIndex="0" length="48" noteData="0x0000000000000018407F">
                  <soundParams lpfFrequency="0x000000002222222280000018555555550000003C" />
                </noteRow>
              </noteRows>
            </instrumentClip>
          </sessionClips>
        </song>
        """;

    ProjectModel parsed =
        SongXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "AutoKitSong");
    ClipModel clip = parsed.getTracks().get(0).getClips().get(0);
    NoteRowModel rowModel = clip.getOrCreateRow(0);

    List<ParamNode> rowCurve = rowModel.getAutomationCurves().get("lpfFrequency");
    assertNotNull(rowCurve);
    assertEquals(
        1,
        rowCurve.size(),
        "Independent row length=48 must bound readAutomationUpToPos at 48, dropping pos=60");
    assertEquals(24, rowCurve.get(0).pos);
    assertEquals(0x22222222, rowCurve.get(0).value);
    assertTrue(rowCurve.get(0).interpolated);

    // Verify FirmwareFactory wires kit row automation into the drum's ParamManager.
    ProjectModel fwSong = FirmwareFactory.createSong(parsed);
    FirmwareKit fwKit = (FirmwareKit) fwSong.getTracks().get(0).getActiveClip().getSound();
    assertNotNull(fwKit);
    FirmwareSound drumSound = fwKit.drumSounds.get(0);
    AutoParam drumLpf = findAutoParam(drumSound.paramManager, Param.LOCAL_LPF_FREQ);
    assertNotNull(drumLpf);
    assertEquals(1, drumLpf.nodes.size());
    assertEquals(24, drumLpf.nodes.get(0).pos);
    assertEquals(0x22222222, drumLpf.nodes.get(0).value);
    assertTrue(drumLpf.nodes.get(0).interpolated);

    // Verify ProjectSerializer round-trips kit row <soundParams> automation nodes.
    File savedFile = tempDir.resolve("roundtrip_autokit.xml").toFile();
    ProjectSerializer.save(parsed, savedFile);
    String savedXml = Files.readString(savedFile.toPath());
    assertTrue(
        savedXml.contains("2222222280000018"),
        "Saved kit noteRow <soundParams> must preserve 16-char automation hex nodes");

    ProjectModel reloaded = SongXmlParser.parseSong(savedFile);
    NoteRowModel reloadedRow = reloaded.getTracks().get(0).getClips().get(0).getOrCreateRow(0);
    List<ParamNode> reloadedCurve = reloadedRow.getAutomationCurves().get("lpfFrequency");
    assertNotNull(reloadedCurve);
    assertEquals(1, reloadedCurve.size());
    assertEquals(24, reloadedCurve.get(0).pos);
    assertEquals(0x22222222, reloadedCurve.get(0).value);
  }

  private static AutoParam findAutoParam(
      org.deluge.modulation.params.ParamManager pm, int paramId) {
    for (AutoParam p : pm.automatedParams) {
      if (p.paramId == paramId) {
        return p;
      }
    }
    return null;
  }
}
