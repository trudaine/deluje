package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import org.deluge.model.PatchCable;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Guards the pre-V3.2 legacy patch-cable encoding (C {@code
 * PatchCableSet::readPatchCablesFromFile}, patch_cable_set.cpp:807-950): a sibling cable with
 * {@code <destination>range</destination>} modulates the DEPTH of whichever cable in the same file
 * carries {@code <rangeAdjustable>1</rangeAdjustable>}, rather than targeting a literal "range"
 * parameter. Before this was ported, {@code InstrumentXmlParser} silently dropped every such range
 * cable (no destination string matched "RANGE" in {@code FirmwareFactory.mapPatchCables}), so
 * depth-shaped LFO/pitch modulation played at a constant full depth instead of following its
 * controlling envelope/LFO.
 */
public class LegacyRangeCablePatchTest {

  @Test
  void envelopeRangeCableAttachesAsDepthControlOnFlaggedCable() throws Exception {
    InputStream synthStream =
        getClass().getResourceAsStream("/SYNTHS/124 Filter Modulation Pad.XML");
    assertNotNull(synthStream, "124 Filter Modulation Pad preset not found");
    SynthTrackModel synth = DelugeXmlParser.parseSynth(synthStream, "124 Filter Modulation Pad");
    assertNotNull(synth);

    var cables = synth.getModulation().getPatchCables();
    // The legacy "range"-destination cable must not survive as its own top-level cable...
    assertTrue(
        cables.stream().noneMatch(c -> "range".equalsIgnoreCase(c.destination())),
        "a bare 'range' destination cable should never reach the engine as its own cable");

    // ...it must instead be folded into the rangeAdjustable-flagged cable's depthControlledBy.
    PatchCable lfo2ToCutoff =
        cables.stream()
            .filter(
                c ->
                    "lfo2".equalsIgnoreCase(c.source())
                        && "lpfFrequency".equalsIgnoreCase(c.destination()))
            .findFirst()
            .orElse(null);
    assertNotNull(lfo2ToCutoff, "expected the rangeAdjustable lfo2 -> lpfFrequency cable");
    assertEquals(1, lfo2ToCutoff.depthControlledBy().size());
    assertEquals("envelope2", lfo2ToCutoff.depthControlledBy().get(0).source().toLowerCase());
  }

  @Test
  void presetsWithoutLegacyRangeCablesAreUnaffected() throws Exception {
    InputStream synthStream = getClass().getResourceAsStream("/SYNTHS/056 FM Bell Modulation.XML");
    assertNotNull(synthStream);
    SynthTrackModel synth = DelugeXmlParser.parseSynth(synthStream, "056 FM Bell Modulation");
    assertNotNull(synth);
    // Simply must not throw and must still parse a non-empty modulation set.
    assertFalse(synth.getModulation().getPatchCables().isEmpty());
  }
}
