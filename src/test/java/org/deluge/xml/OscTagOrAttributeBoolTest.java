package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Regression: the firmware parses every {@code <sound>}/source field with {@code
 * readTagOrAttributeValueInt()} (sound.cpp:3300-3400), so the child-element form is exactly as
 * valid as the attribute form. Our parser read these osc flags as <em>attributes only</em>, so the
 * element form was silently dropped and the flag kept its default.
 *
 * <p>For {@code oscillatorSync} that meant every hand-written hard-sync preset loaded with sync OFF
 * — a completely different sound, with no warning. On the reference SD card 23 of the 36 presets
 * that set {@code oscillatorSync} use the element form (e.g. "045 Square Sync", "046 Saw Sync").
 *
 * <p>Same class of bug as the osc2 {@code type} binding ({@link Osc2AttributeTypeTest}) and the
 * {@code hpfMode} handling — see docs/FIDELITY_GAP_ANALYSIS.md §4.2nonies.
 */
public class OscTagOrAttributeBoolTest {

  private static SynthTrackModel parse(String xml) throws Exception {
    return DelugeXmlParser.parseSynth(
        new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "T");
  }

  @Test
  void childElementOscillatorSyncParses() throws Exception {
    // The exact shape used by "045 Square Sync.XML" on a real card.
    SynthTrackModel synth =
        parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <sound>
              <osc1><type>square</type></osc1>
              <osc2>
                <type>square</type>
                <transpose>7</transpose>
                <oscillatorSync>1</oscillatorSync>
              </osc2>
            </sound>
            """);
    assertTrue(
        synth.isOscillatorSync(),
        "<oscillatorSync>1</oscillatorSync> must enable hard sync — the firmware accepts the tag"
            + " form, and most hand-written presets use it");
    assertEquals(7, synth.getOsc2Transpose());
  }

  @Test
  void attributeOscillatorSyncStillParses() throws Exception {
    SynthTrackModel synth =
        parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <sound>
              <osc2 type="square" transpose="7" oscillatorSync="1" />
            </sound>
            """);
    assertTrue(synth.isOscillatorSync());
  }

  @Test
  void absentOscillatorSyncStaysOff() throws Exception {
    // The firmware only writes the attribute when it is set (sound.cpp:3677), so absence means off.
    // This is the shape the ALLSYN song's embedded copy of "045 Square Sync" actually has.
    SynthTrackModel synth =
        parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <sound>
              <osc2 type="square" transpose="7" retrigPhase="-1" />
            </sound>
            """);
    assertFalse(synth.isOscillatorSync());
  }

  @Test
  void childElementSamplePlaybackFlagsParse() throws Exception {
    SynthTrackModel synth =
        parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <sound>
              <osc1>
                <type>sample</type>
                <reversed>1</reversed>
                <loopMode>2</loopMode>
                <timeStretchEnable>1</timeStretchEnable>
              </osc1>
            </sound>
            """);
    assertTrue(synth.isOsc1Reversed(), "<reversed> element form must parse");
    assertEquals(2, synth.getOsc1LoopMode(), "<loopMode> element form must parse");
    assertTrue(synth.isOsc1TimeStretch(), "<timeStretchEnable> element form must parse");
  }
}
