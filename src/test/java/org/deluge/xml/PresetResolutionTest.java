package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

public class PresetResolutionTest {

  @Test
  public void testDirectPresetFileResolution() {
    // 1. Verify resolving an existing factory preset returns a valid file without absolute paths
    File epiano = InstrumentXmlParser.resolvePresetFile("SYNTHS", "074 Electric Piano");
    assertNotNull(epiano, "Should resolve 074 Electric Piano from relative project resources");
    assertTrue(epiano.isFile(), "Resolved preset must be an existing file on disk");
    assertTrue(
        epiano.getPath().replace('\\', '/').contains("src/main/resources/SYNTHS/074 Electric Piano.XML"),
        "Resolved path must map cleanly to relative resources without hardcoded user directories");

    // 2. Verify resolving a non-existent preset returns null gracefully
    File nonExistent = InstrumentXmlParser.resolvePresetFile("SYNTHS", "99999 Non Existent Preset");
    assertNull(nonExistent, "Non-existent preset should return null");

    // 3. Verify dynamic deluge.card property override (per CLAUDE.md)
    String origCard = System.getProperty("deluge.card");
    try {
      System.setProperty("deluge.card", "src/main/resources");
      File cardResolved = InstrumentXmlParser.resolvePresetFile("SYNTHS", "027 PW Envelope");
      assertNotNull(cardResolved, "Should resolve using deluge.card system property");
      assertEquals("027 PW Envelope.XML", cardResolved.getName());
    } finally {
      if (origCard != null) {
        System.setProperty("deluge.card", origCard);
      } else {
        System.clearProperty("deluge.card");
      }
    }
  }

  @Test
  public void testSongPresetResolutionPreservesCables() throws Exception {
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song>
          <instruments>
            <sound presetName="074 Electric Piano" presetFolder="SYNTHS">
              <defaultParams/>
            </sound>
            <sound presetName="027 PW Envelope" presetFolder="SYNTHS">
              <defaultParams/>
            </sound>
          </instruments>
        </song>
        """;

    ProjectModel project =
        DelugeXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "test_song.xml");

    assertNotNull(project);
    assertTrue(project.getTracks().size() >= 2, "Should have parsed two synth tracks");

    SynthTrackModel epiano = (SynthTrackModel) project.getTracks().get(0);
    SynthTrackModel pwm = (SynthTrackModel) project.getTracks().get(1);

    // Verify 074 Electric Piano resolved from relative src/main/resources/SYNTHS/ and loaded cables
    assertFalse(
        epiano.getModulation().getPatchCables().isEmpty(),
        "074 Electric Piano patch cables should be resolved from preset XML");
    assertTrue(
        epiano.getModulation().getPatchCables().size() >= 17,
        "074 Electric Piano should load at least 17 preset patch cables");

    // Verify 027 PW Envelope resolved and loaded cables
    assertFalse(
        pwm.getModulation().getPatchCables().isEmpty(),
        "027 PW Envelope patch cables should be resolved from preset XML");
    assertTrue(
        pwm.getModulation().getPatchCables().size() >= 6,
        "027 PW Envelope should load at least 6 preset patch cables");

    // Verify resetClipParamsToFirmwareDefaults preserves preset cables
    int cablesBefore = epiano.getModulation().getPatchCables().size();
    InstrumentXmlParser.resetClipParamsToFirmwareDefaults(epiano);
    assertTrue(
        epiano.getModulation().getPatchCables().size() == cablesBefore,
        "resetClipParamsToFirmwareDefaults must not wipe resolved preset patch cables");
  }
}
