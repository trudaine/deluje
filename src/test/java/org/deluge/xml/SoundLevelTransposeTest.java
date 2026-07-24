package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Regression: old-format (pre-V3 factory) presets carry the sound-level master transpose as a
 * nested {@code <transpose>} child of {@code <sound>}, not an attribute. The parser silently
 * dropped it, shifting every oscillator AND FM modulator of such presets by the missing amount
 * (e.g. "068 FM Bells 1" played a full octave high without its {@code <transpose>-12}). The lookup
 * must also be a DIRECT child only — a descendant search would grab osc1's own {@code <transpose>}
 * instead.
 */
public class SoundLevelTransposeTest {

  @Test
  void nestedSoundTransposeParsesAndDoesNotGrabOscChild() throws Exception {
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <sound>
          <osc1>
            <type>square</type>
            <transpose>12</transpose>
          </osc1>
          <mode>fm</mode>
          <transpose>-12</transpose>
        </sound>
        """;
    SynthTrackModel synth =
        DelugeXmlParser.parseSynth(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "T");
    assertEquals(-12, synth.getTranspose(), "sound-level nested <transpose> must parse");
    assertEquals(12, synth.getOsc1Transpose(), "osc1's own transpose must stay separate");
  }

  @Test
  void attributeTransposeStillWins() throws Exception {
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <sound transpose="-7">
          <osc1><transpose>3</transpose></osc1>
        </sound>
        """;
    SynthTrackModel synth =
        DelugeXmlParser.parseSynth(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "T");
    assertEquals(-7, synth.getTranspose());
  }
}
