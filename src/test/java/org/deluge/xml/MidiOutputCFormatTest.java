package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import org.deluge.model.ClipModel;
import org.deluge.model.MidiTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.project.ProjectSerializer;
import org.junit.jupiter.api.Test;

/**
 * MIDI outputs use the C's own format: {@code <midi channel=0..15 | zone=lower|upper suffix>}
 * (midi_instrument.h:122-126, instrument.cpp:44-68) and MIDI clips link by {@code midiChannel}
 * (instrument_clip.cpp:2357-2362), with the Java's older {@code <sound><midiChannel>} still read.
 */
class MidiOutputCFormatTest {

  private static File save(ProjectModel model) throws Exception {
    File f = File.createTempFile("midi_cformat", ".xml");
    f.deleteOnExit();
    ProjectSerializer.save(model, f);
    return f;
  }

  @Test
  void writesMidiElementAndRoundTrips() throws Exception {
    ProjectModel model = new ProjectModel();
    MidiTrackModel ch5 = new MidiTrackModel("Moog");
    ch5.setMidiChannel(5);
    ch5.addClip(new ClipModel("c1", 1, 16));
    model.addTrack(ch5);
    MidiTrackModel mpe = new MidiTrackModel("Osmose");
    mpe.setMpe(true);
    mpe.setMpeZone("upper");
    mpe.addClip(new ClipModel("c2", 1, 16));
    model.addTrack(mpe);

    File f = save(model);
    String xml = Files.readString(f.toPath());
    assertTrue(xml.contains("<midi") && xml.contains("channel=\"4\""), xml);
    assertTrue(xml.contains("zone=\"upper\""), xml);
    assertFalse(xml.contains("presetFolder=\"SYNTHS\""), xml);
    assertTrue(xml.contains("midiChannel=\"4\""), xml);
    assertTrue(xml.contains("midiChannel=\"17\""), xml);

    ProjectModel parsed = DelugeXmlParser.parseSong(f);
    assertEquals(2, parsed.getTracks().size());
    MidiTrackModel p5 = (MidiTrackModel) parsed.getTracks().get(0);
    assertEquals("Moog", p5.getName());
    assertEquals(5, p5.getMidiChannel());
    assertFalse(p5.isMpe());
    MidiTrackModel pMpe = (MidiTrackModel) parsed.getTracks().get(1);
    assertTrue(pMpe.isMpe());
    assertEquals("upper", pMpe.getMpeZone());
  }

  @Test
  void legacyJavaSoundFormStillReadsOneBased() throws Exception {
    ProjectModel model = new ProjectModel();
    MidiTrackModel ch5 = new MidiTrackModel("Moog");
    ch5.setMidiChannel(5);
    model.addTrack(ch5);
    File f = save(model);
    // Rewrite to the pre-#105 Java form: <sound presetName presetFolder="SYNTHS"> + 1-based child.
    String xml = Files.readString(f.toPath());
    xml =
        xml.replaceFirst(
                "<midi\\b[^>]*>",
                "<sound presetName=\"Moog\" presetFolder=\"SYNTHS\">"
                    + "<midiChannel>5</midiChannel>")
            .replace("</midi>", "</sound>");
    Files.writeString(f.toPath(), xml);
    MidiTrackModel p = (MidiTrackModel) DelugeXmlParser.parseSong(f).getTracks().get(0);
    assertEquals(5, p.getMidiChannel());
  }
}
