package org.deluge.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * The supersaw oscillator type round-trips through song XML with the firmware's own token (C {@code
 * oscTypeToString} / {@code stringToOscType}, functions.cpp:691-692, :740-741; upstream {@code
 * 451c9f82f}).
 *
 * <p>Before the 2026-11-03 sync the token was unknown, so a supersaw song loaded as the C's
 * unrecognized-type TRIANGLE fallback and saved back as a square.
 */
class SupersawXmlRoundTripTest {

  @Test
  void supersawSerializesAndParsesWithTheFirmwareToken() throws Exception {
    ProjectModel project = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("SUPER");
    synth.setOsc1Type("SUPERSAW");
    synth.addClip(new ClipModel("C1", 8, 16));
    project.addTrack(synth);

    String xml = ProjectSerializer.serializeToString(project);
    assertTrue(xml.contains("type=\"supersaw\""), "osc1 must write the exact supersaw token");

    ProjectModel parsed =
        DelugeXmlParser.parseSong(
            new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "SUPERSAW_ROUNDTRIP");
    SynthTrackModel parsedSynth =
        (SynthTrackModel)
            parsed.getTracks().stream()
                .filter(t -> t instanceof SynthTrackModel)
                .findFirst()
                .orElseThrow();
    assertEquals("SUPERSAW", parsedSynth.getOsc1Type().toUpperCase(java.util.Locale.ROOT));
  }
}
