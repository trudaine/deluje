package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import org.deluge.model.ClipModel;
import org.deluge.model.MidiTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.model.TrackModel;
import org.deluge.project.ProjectSerializer;
import org.junit.jupiter.api.Test;

/**
 * C InstrumentClip::claimOutput (instrument_clip.cpp:3902-3919): a session clip goes to the output
 * its own linkage names (instrumentPresetName, or midiChannel for MIDI), not to the next track in
 * file order.
 */
class ClipClaimOutputTest {

  @Test
  void clipsFollowTheirOwnLinkageNotFileOrder() throws Exception {
    ProjectModel model = new ProjectModel();
    SynthTrackModel a = new SynthTrackModel("LEAD");
    a.addClip(new ClipModel("a1", 1, 16));
    a.addClip(new ClipModel("a2", 1, 16));
    model.addTrack(a);
    MidiTrackModel m = new MidiTrackModel("Ext");
    m.setMidiChannel(3);
    m.addClip(new ClipModel("m1", 1, 16));
    model.addTrack(m);
    SynthTrackModel b = new SynthTrackModel("PAD");
    b.addClip(new ClipModel("b1", 1, 16));
    model.addTrack(b);

    File f = File.createTempFile("claim", ".xml");
    f.deleteOnExit();
    ProjectSerializer.save(model, f);

    ProjectModel parsed = DelugeXmlParser.parseSong(f);
    TrackModel pa = parsed.getTracks().get(0);
    TrackModel pm = parsed.getTracks().get(1);
    TrackModel pb = parsed.getTracks().get(2);
    assertEquals("LEAD", pa.getName());
    assertEquals(2, pa.getClips().size(), "both LEAD clips stay on LEAD");
    assertEquals(1, pm.getClips().size(), "the MIDI clip links by midiChannel");
    assertEquals(1, pb.getClips().size());
  }
}
