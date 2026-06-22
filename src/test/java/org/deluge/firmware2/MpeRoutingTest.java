package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
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
    ProjectModel fwSong = FirmwareFactory.createSong(project);

    // Retrieve active sound instrument
    org.deluge.model.ClipModel clip = fwSong.getTracks().get(0).getActiveClip();
    FirmwareSound synth = (FirmwareSound) clip.getSound();

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

  @Test
  public void testPolyphonicMpeCrosstalkIndependence() throws Exception {
    File synthFile = new File("src/main/resources/SYNTHS/999 Ultimate Workstation Showcase.XML");
    if (!synthFile.exists()) {
      synthFile =
          new File("../deluge/src/main/resources/SYNTHS/999 Ultimate Workstation Showcase.XML");
    }
    assertTrue(synthFile.exists(), "Showcase Synth XML not found!");

    SynthTrackModel synthModel = DelugeXmlParser.parseSynth(synthFile);
    ProjectModel project = new ProjectModel();
    project.addTrack(synthModel);
    ProjectModel fwSong = FirmwareFactory.createSong(project);

    org.deluge.model.ClipModel clip = fwSong.getTracks().get(0).getActiveClip();
    FirmwareSound synth = (FirmwareSound) clip.getSound();
    Sound fw2Sound = synth.fw2Sound;

    // Trigger three notes on three independent MIDI channels
    int ch4 = 4;
    int ch5 = 5;
    int ch6 = 6;

    synth.triggerNote(60, 100, ch4); // Voice 0 (Channel 4)
    synth.triggerNote(64, 100, ch5); // Voice 1 (Channel 5)
    synth.triggerNote(67, 100, ch6); // Voice 2 (Channel 6)

    synchronized (fw2Sound.voices) {
      assertEquals(3, fw2Sound.voices.size(), "Should have exactly 3 active voices");

      // Verify all voices initially have neutral modulation values
      for (Voice voice : fw2Sound.voices) {
        assertEquals(0, voice.sourceValues[PatchSource.X.ordinal()]);
        assertEquals(0, voice.sourceValues[PatchSource.AFTERTOUCH.ordinal()]);
        assertEquals(0, voice.sourceValues[PatchSource.Y.ordinal()]);
      }
    }

    // 1. Send Pitch Bend (X-axis) ONLY on Channel 4
    int newValuePitchBend = (16383 - 8192) << 18;
    fw2Sound.polyphonicExpressionEventOnChannelOrNote(newValuePitchBend, 0, ch4, 1);

    synchronized (fw2Sound.voices) {
      // Find voice for Channel 4 (typically voice 0, but let's find by midiChannel for safety!)
      Voice voiceCh4 = findVoiceByChannel(fw2Sound, ch4);
      Voice voiceCh5 = findVoiceByChannel(fw2Sound, ch5);
      Voice voiceCh6 = findVoiceByChannel(fw2Sound, ch6);

      assertEquals(newValuePitchBend, voiceCh4.sourceValues[PatchSource.X.ordinal()], "Channel 4 must bend");
      assertEquals(0, voiceCh5.sourceValues[PatchSource.X.ordinal()], "Channel 5 must NOT bend");
      assertEquals(0, voiceCh6.sourceValues[PatchSource.X.ordinal()], "Channel 6 must NOT bend");
    }

    // 2. Send Channel Aftertouch (Z-axis) ONLY on Channel 5
    int newValueAftertouch = 127 << 24;
    fw2Sound.polyphonicExpressionEventOnChannelOrNote(newValueAftertouch, 2, ch5, 1);

    synchronized (fw2Sound.voices) {
      Voice voiceCh4 = findVoiceByChannel(fw2Sound, ch4);
      Voice voiceCh5 = findVoiceByChannel(fw2Sound, ch5);
      Voice voiceCh6 = findVoiceByChannel(fw2Sound, ch6);

      assertEquals(0, voiceCh4.sourceValues[PatchSource.AFTERTOUCH.ordinal()], "Channel 4 must have 0 pressure");
      assertEquals(newValueAftertouch, voiceCh5.sourceValues[PatchSource.AFTERTOUCH.ordinal()], "Channel 5 must have max pressure");
      assertEquals(0, voiceCh6.sourceValues[PatchSource.AFTERTOUCH.ordinal()], "Channel 6 must have 0 pressure");
    }

    // 3. Send Y-Slide (CC 74, Y-axis) ONLY on Channel 6
    // Y-slide event: value typically scaled to Q31 (e.g. 127 << 24)
    int newValueYSlide = 100 << 24;
    fw2Sound.polyphonicExpressionEventOnChannelOrNote(newValueYSlide, 1, ch6, 1);

    synchronized (fw2Sound.voices) {
      Voice voiceCh4 = findVoiceByChannel(fw2Sound, ch4);
      Voice voiceCh5 = findVoiceByChannel(fw2Sound, ch5);
      Voice voiceCh6 = findVoiceByChannel(fw2Sound, ch6);

      assertEquals(0, voiceCh4.sourceValues[PatchSource.Y.ordinal()], "Channel 4 must have 0 Y-slide");
      assertEquals(0, voiceCh5.sourceValues[PatchSource.Y.ordinal()], "Channel 5 must have 0 Y-slide");
      assertEquals(newValueYSlide, voiceCh6.sourceValues[PatchSource.Y.ordinal()], "Channel 6 must have max Y-slide");
    }
  }

  private Voice findVoiceByChannel(Sound sound, int channel) {
    for (Voice voice : sound.voices) {
      if (voice.inputCharacteristics[1] == channel) {
        return voice;
      }
    }
    throw new AssertionError("No active voice found on MIDI channel " + channel);
  }
}
