package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Regression: the osc2 type binding was child-element-only, so attribute-style {@code <osc2
 * type="square">} (song-embedded instruments and newer presets) parsed as NONE — silencing the
 * second oscillator of every two-oscillator patch loaded from a song. Osc1's binding always
 * accepted both forms; osc2 must too.
 */
public class Osc2AttributeTypeTest {

  @Test
  void attributeStyleOsc2TypeParses() throws Exception {
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <sound>
          <osc1 type="saw" transpose="0" />
          <osc2 type="square" transpose="0" />
        </sound>
        """;
    SynthTrackModel synth =
        DelugeXmlParser.parseSynth(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "T");
    assertEquals("SAW", synth.getOsc1Type());
    assertEquals("SQUARE", synth.getOsc2Type(), "attribute-style osc2 type must parse");
  }

  @Test
  void childStyleOsc2TypeStillParses() throws Exception {
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <sound>
          <osc2><type>triangle</type></osc2>
        </sound>
        """;
    SynthTrackModel synth =
        DelugeXmlParser.parseSynth(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "T");
    assertEquals("TRIANGLE", synth.getOsc2Type());
  }
}
