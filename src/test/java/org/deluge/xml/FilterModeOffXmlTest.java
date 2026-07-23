package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import org.deluge.model.FilterMode;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.project.ProjectSerializer;
import org.junit.jupiter.api.Test;

/**
 * Regression for upstream firmware #4688 (`a3f5b8a5`): the filter mode string map now includes
 * {@code "Off"}, so current-firmware song/preset XML can carry {@code lpfMode="Off"} / {@code
 * hpfMode="Off"}. Before the fix our parsers fell back to LADDER_12, silently engaging a 12dB
 * ladder on a filter the hardware had bypassed.
 */
public class FilterModeOffXmlTest {

  @Test
  void parsesLpfAndHpfOffFromFirmwareXml() throws Exception {
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <sound>
          <lpfMode>Off</lpfMode>
          <hpfMode>Off</hpfMode>
        </sound>
        """;
    SynthTrackModel synth =
        DelugeXmlParser.parseSynth(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "OFF_TEST");
    assertEquals(FilterMode.OFF, synth.getFilterMode(), "lpfMode=\"Off\" must parse to OFF");
    assertEquals(FilterMode.OFF, synth.getHpfMode(), "hpfMode=\"Off\" must parse to OFF");
  }

  @Test
  void filterOffSurvivesSongRoundtrip() throws Exception {
    ProjectModel original = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("OFF_SYNTH");
    synth.setFilterMode(FilterMode.OFF);
    synth.setHpfMode(FilterMode.OFF);
    original.addTrack(synth);

    File tempFile = File.createTempFile("filter_off_roundtrip", ".xml");
    tempFile.deleteOnExit();
    ProjectSerializer.save(original, tempFile);

    ProjectModel reloaded = DelugeXmlParser.parseSong(tempFile);
    SynthTrackModel reloadedSynth = (SynthTrackModel) reloaded.getTracks().get(0);
    assertEquals(FilterMode.OFF, reloadedSynth.getFilterMode(), "OFF lpfMode lost in roundtrip");
    assertEquals(FilterMode.OFF, reloadedSynth.getHpfMode(), "OFF hpfMode lost in roundtrip");
  }
}
