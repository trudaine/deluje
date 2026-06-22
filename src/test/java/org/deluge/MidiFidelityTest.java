package org.deluge;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.engine.FirmwareFactory;
import org.deluge.midi.MidiEngine;
import org.deluge.midi.MidiTransport;
import org.deluge.model.ClipModel;
import org.deluge.model.MidiTrackModel;
import org.deluge.model.NoteModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.shadow.midi.MidiMsg;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * High-fidelity JUnit 5 tests suite for external hardware MIDI tracks. Asserts properties
 * structures, XML template tag parses, and real-time step sequencers triggers.
 */
public class MidiFidelityTest {

  static class MockMidiTransport implements MidiTransport {
    public final java.util.List<MidiMsg> messages = new java.util.ArrayList<>();

    @Override
    public String getPortName() {
      return "Mock Port";
    }

    @Override
    public boolean isOutput() {
      return true;
    }

    @Override
    public boolean isInput() {
      return false;
    }

    @Override
    public boolean sendMessage(MidiMsg msg) {
      messages.add(msg);
      return true;
    }

    @Override
    public void close() {}
  }

  @Test
  public void testMidiTrackModelProperties() {
    MidiTrackModel midiTrack = new MidiTrackModel("My Synth");
    midiTrack.setMidiChannel(5);
    midiTrack.setDeviceName("Hardware Synth");
    midiTrack.setDeviceDefinitionFile("presets/synth.xml");
    midiTrack.setCcLabel(74, "Filter Cutoff");

    assertEquals(5, midiTrack.getMidiChannel());
    assertEquals("Hardware Synth", midiTrack.getDeviceName());
    assertEquals("presets/synth.xml", midiTrack.getDeviceDefinitionFile());
    assertEquals("Filter Cutoff", midiTrack.getCcLabel(74));
    assertFalse(midiTrack.isMpe());

    midiTrack.setMpe(true);
    assertTrue(midiTrack.isMpe());
    assertEquals(0, midiTrack.getMidiChannel());
  }

  @Test
  public void testMidiTrackXmlParsing() throws Exception {
    String xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<song firmwareVersion=\"4.0.0\" bpm=\"120.0\" swing=\"0.5\">\n"
            + "  <instruments>\n"
            + "    <sound>\n"
            + "      <presetSlot>99</presetSlot>\n"
            + "      <midiChannel>3</midiChannel>\n"
            + "      <midiDevice>\n"
            + "        <name>Moog Minitaur</name>\n"
            + "        <definitionFile>moog.xml</definitionFile>\n"
            + "      </midiDevice>\n"
            + "      <ccLabels>\n"
            + "        <cc19>Filter Cutoff</cc19>\n"
            + "        <cc20>Resonance</cc20>\n"
            + "      </ccLabels>\n"
            + "    </sound>\n"
            + "  </instruments>\n"
            + "  <sessionClips>\n"
            + "    <instrumentClip>\n"
            + "      <noteRows>\n"
            + "        <noteRow y=\"60\" noteData=\"0x0000000000000006404014000000\"/>\n"
            + "      </noteRows>\n"
            + "    </instrumentClip>\n"
            + "  </sessionClips>\n"
            + "</song>";

    ProjectModel project =
        DelugeXmlParser.parseSong(new java.io.ByteArrayInputStream(xml.getBytes()), "test.xml");
    assertEquals(1, project.getTracks().size());
    assertTrue(project.getTracks().get(0) instanceof MidiTrackModel);

    MidiTrackModel midiTrack = (MidiTrackModel) project.getTracks().get(0);
    assertEquals("Moog Minitaur", midiTrack.getName());
    assertEquals("Moog Minitaur", midiTrack.getDeviceName());
    assertEquals(3, midiTrack.getMidiChannel());
    assertEquals("moog.xml", midiTrack.getDeviceDefinitionFile());
    assertEquals("Filter Cutoff", midiTrack.getCcLabel(19));
    assertEquals("Resonance", midiTrack.getCcLabel(20));

    // Verify clip is parsed correctly
    assertEquals(1, midiTrack.getClips().size());
    ClipModel clip = midiTrack.getClips().get(0);
    assertEquals(1, clip.getRawNoteEvents(0).size());
    NoteModel note = clip.getRawNoteEvents(0).get(0);
    assertEquals(0, note.getTickPos());
    assertEquals(6, note.getTickLen());
  }

  @Test
  public void testMidiSequencerPlaybackTriggers() {
    MidiEngine engine = new MidiEngine();
    MockMidiTransport mock = new MockMidiTransport();
    engine.addOutputTransport(mock);

    MidiTrackModel model = new MidiTrackModel("External Synth");
    model.setMidiChannel(4);

    ClipModel clipModel = new ClipModel("Clip 1", 1, 16);
    // Add C4 note at step 0 (velocity 0.8) using immutable StepData record!
    clipModel.setStep(0, 0, StepData.of(true, 0.8f, 1.0f, 1.0f, 60));
    model.addClip(clipModel);

    ProjectModel project = new ProjectModel();
    project.addTrack(model);

    // Build the factory Song
    ProjectModel song = FirmwareFactory.createSong(project);
    assertEquals(1, song.getClips().size());
    assertTrue(song.getTracks().get(0).getActiveClip() instanceof ClipModel);

    ClipModel instClip = song.getTracks().get(0).getActiveClip();

    // Clear and trigger step 0
    mock.messages.clear();

    // Trigger the note at step 0 manually through the instrument clip step playback handler logic!
    instClip.processCurrentPos(1);

    // Verify that the note is triggered and dispatched to the mock MIDI transport
    assertFalse(mock.messages.isEmpty());

    MidiMsg noteOnMsg = mock.messages.get(0);
    assertEquals(
        0x93, noteOnMsg.data1 & 0xFF); // Note On on channel 4 (0x93 since channel is 0-indexed)
    assertEquals(60, noteOnMsg.data2 & 0xFF); // Pitch 60 (C4)
    assertEquals((int) (0.8f * 127), noteOnMsg.data3 & 0xFF); // Velocity
  }
}
