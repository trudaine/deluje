package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.playback.Song;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

public class MpeRoutingTest {

  @Test
  public void testPitchBendAndAftertouchRouting() throws Exception {
    File synthFile = new File("src/main/resources/SYNTHS/999 Ultimate Workstation Showcase.XML");
    if (!synthFile.exists()) {
      synthFile =
          new File("../deluge/src/main/resources/SYNTHS/999 Ultimate Workstation Showcase.XML");
    }
    assertTrue(synthFile.exists(), "Showcase Synth XML not found!");

    SynthTrackModel synthModel = DelugeXmlParser.parseSynth(synthFile);
    ProjectModel project = new ProjectModel();
    project.addTrack(synthModel);
    Song fwSong = FirmwareFactory.createSong(project);

    // Retrieve active sound instrument
    org.deluge.playback.InstrumentClip clip =
        (org.deluge.playback.InstrumentClip) fwSong.clips.get(0);
    FirmwareSound synth = (FirmwareSound) clip.sound;

    // Trigger note on MIDI channel 4
    int testMidiChannel = 4;
    synth.triggerNote(60, 100, testMidiChannel);

    // Get the Firmware 2.0 Sound engine reference
    Sound fw2Sound = synth.fw2Sound;
    assertTrue(fw2Sound != null, "Fw2Sound should be initialized");

    // Initially Pitch Bend value on source X (index 0) should be 0
    synchronized (fw2Sound.voices) {
      assertTrue(!fw2Sound.voices.isEmpty(), "Voices list should have at least 1 active voice");
      Voice voice = fw2Sound.voices.get(0);
      assertEquals(0, voice.sourceValues[PatchSource.X.ordinal()]);
      assertEquals(0, voice.sourceValues[PatchSource.AFTERTOUCH.ordinal()]);
    }

    // Send Pitch Bend: maximum bend up (16383) on channel 4
    // newValue = (16383 - 8192) << 18 = 8191 << 18 = 2147221504
    int newValuePitchBend = (16383 - 8192) << 18;
    fw2Sound.polyphonicExpressionEventOnChannelOrNote(newValuePitchBend, 0, testMidiChannel, 1);

    // Verify Pitch Bend is updated immediately on the active voice
    synchronized (fw2Sound.voices) {
      Voice voice = fw2Sound.voices.get(0);
      assertEquals(newValuePitchBend, voice.sourceValues[PatchSource.X.ordinal()]);
    }

    // Send Channel Aftertouch: maximum pressure (127) on channel 4
    // newValue = 127 << 24 = 2130706432
    int newValueAftertouch = 127 << 24;
    fw2Sound.polyphonicExpressionEventOnChannelOrNote(newValueAftertouch, 2, testMidiChannel, 1);

    // Verify Aftertouch is updated immediately on the active voice
    synchronized (fw2Sound.voices) {
      Voice voice = fw2Sound.voices.get(0);
      assertEquals(newValueAftertouch, voice.sourceValues[PatchSource.AFTERTOUCH.ordinal()]);
    }
  }
}
