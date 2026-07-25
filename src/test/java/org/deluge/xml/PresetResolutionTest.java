package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

public class PresetResolutionTest {

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
