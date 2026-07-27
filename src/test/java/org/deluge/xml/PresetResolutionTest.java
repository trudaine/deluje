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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class PresetResolutionTest {

  private String origCard;

  @BeforeEach
  void setUp() {
    origCard = System.getProperty("deluge.card");
    System.setProperty("deluge.card", "src/main/resources");
  }

  @AfterEach
  void tearDown() {
    if (origCard != null) {
      System.setProperty("deluge.card", origCard);
    } else {
      System.clearProperty("deluge.card");
    }
  }

  @Test
  public void testDirectPresetFileResolution() {
    // 1. Verify resolving an existing factory preset returns a valid file without absolute paths
    File epiano = InstrumentXmlParser.resolvePresetFile("SYNTHS", "074 Electric Piano");
    assertNotNull(epiano, "Should resolve 074 Electric Piano from relative project resources");
    assertTrue(epiano.isFile(), "Resolved preset must be an existing file on disk");
    assertTrue(
        epiano
            .getPath()
            .replace('\\', '/')
            .contains("src/main/resources/SYNTHS/074 Electric Piano.XML"),
        "Resolved path must map cleanly to relative resources without hardcoded user directories");

    // 2. Verify resolving a non-existent preset returns null gracefully
    File nonExistent = InstrumentXmlParser.resolvePresetFile("SYNTHS", "99999 Non Existent Preset");
    assertNull(nonExistent, "Non-existent preset should return null");

    // 3. Verify dynamic deluge.card property override (per CLAUDE.md)
    File cardResolved = InstrumentXmlParser.resolvePresetFile("SYNTHS", "027 PW Envelope");
    assertNotNull(cardResolved, "Should resolve using deluge.card system property");
    assertEquals("027 PW Envelope.XML", cardResolved.getName());
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

  /**
   * When a song's {@code <sound>} references a preset (which brings its own cables) AND carries its
   * own {@code <patchCables>} container, the song's container is authoritative: it must REPLACE the
   * resolved preset cables, not append to them. Guards {@code parsePatchCables}' clear-on-container
   * (a soundNode's explicit {@code <patchCables>} block wins over preset-resolution defaults, while
   * a soundNode with no container still preserves the preset cables — see the test above).
   */
  @Test
  public void testSongPatchCableContainerReplacesPresetCables() throws Exception {
    // 074 Electric Piano resolves to >=17 preset cables; the song node overrides with exactly one.
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song>
          <instruments>
            <sound presetName="074 Electric Piano" presetFolder="SYNTHS">
              <defaultParams/>
              <patchCables>
                <patchCable>
                  <source>lfo1</source>
                  <destination>pitch</destination>
                  <amount>0x20000000</amount>
                </patchCable>
              </patchCables>
            </sound>
          </instruments>
        </song>
        """;

    ProjectModel project =
        DelugeXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "override_song.xml");
    assertNotNull(project);
    assertTrue(project.getTracks().size() >= 1, "Should have parsed one synth track");

    SynthTrackModel epiano = (SynthTrackModel) project.getTracks().get(0);
    assertEquals(
        1,
        epiano.getModulation().getPatchCables().size(),
        "The song's <patchCables> container must replace the resolved preset cables, not append");
    assertEquals(
        "lfo1",
        epiano.getModulation().getPatchCables().get(0).source(),
        "The single surviving cable must be the song's own (lfo1->pitch), not a preset cable");
  }
}
