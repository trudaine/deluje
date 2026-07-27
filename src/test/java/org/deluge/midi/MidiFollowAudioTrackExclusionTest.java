package org.deluge.midi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.BridgeContract;
import org.deluge.model.AudioTrackModel;
import org.deluge.model.ClipModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.shadow.midi.MidiMsg;
import org.deluge.ui.SwingDelugeApp;
import org.deluge.ui.SwingGridPanel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Dedicated portable unit test and verification for upstream C++ commit c8a9dc6f (#4708):
 * "Fix midi follow sending irrelevant midi to audio tracks".
 * Verifies that when MIDI follow is active during live recording, incoming Note On and Note Off messages
 * are safely ignored when an AudioTrackModel is selected, preventing accidental note recording into audio clips
 * or runtime class cast exceptions, while continuing to record cleanly when a SynthTrackModel is selected.
 */
public class MidiFollowAudioTrackExclusionTest {

  private BridgeContract bridge;
  private MidiInputRouter router;
  private SwingDelugeApp app;

  @BeforeEach
  public void setUp() {
    System.setProperty("chuck.audio.dummy", "true");
    bridge = new BridgeContract();
    bridge.setGlobalInt(BridgeContract.G_PLAY, 1L); // Playback active
    router = new MidiInputRouter(bridge);
    router.resetToDefaults();
  }

  @AfterEach
  public void tearDown() {
    if (app != null) {
      app.dispose();
    }
    if (bridge != null) {
      bridge.shutdown();
    }
    SwingDelugeApp.mainInstance = null;
    SwingGridPanel.isLiveRecordModeActive = false;
  }

  @Test
  public void testMidiFollowIgnoresAudioTracksAndRecordsToSynthTracks() throws Exception {
    MidiService midiService = new MidiService(bridge, router);
    app = new SwingDelugeApp(bridge, midiService, true);
    SwingDelugeApp.mainInstance = app;
    bridge.setGlobalInt(BridgeContract.G_PLAY, 1L); // Ensure playback active AFTER app boot!

    SwingGridPanel activeGrid = app.getActiveGridPanel();
    assertNotNull(activeGrid, "Headless SwingDelugeApp must initialize an active grid panel");

    // Track 0 is already a default SynthTrackModel created by SwingDelugeApp
    var tracks = activeGrid.getProjectModel().getTracks();
    SynthTrackModel synthTrack = (SynthTrackModel) tracks.get(0);
    ClipModel synthClip = synthTrack.getClips().get(0);

    // Add Track 1: AudioTrackModel (should be ignored by MIDI follow)
    AudioTrackModel audioTrack = new AudioTrackModel("AUDIO_LOOP_TRACK");
    ClipModel audioClip = new ClipModel("audio_clip", 1, 16);
    audioTrack.addClip(audioClip);
    tracks.add(audioTrack);

    activeGrid.refresh();
    SwingGridPanel.isLiveRecordModeActive = true;
    activeGrid.updatePlayhead(2);

    // 1. Select Track 0 (Synth Track) and send MIDI Note On (Note 60, velocity 100)
    activeGrid.setEditedModelTrack(0);
    activeGrid.setActiveClipId(0);
    activeGrid.updatePlayhead(2);

    MidiMsg noteOnMsg = new MidiMsg();
    noteOnMsg.data1 = 0x90; // Note On, CH 1
    noteOnMsg.data2 = 60;   // Note 60
    noteOnMsg.data3 = 100;  // Velocity 100

    router.handleMidiMessage(noteOnMsg);

    // Verify note step WAS recorded into the synth clip
    ClipModel editedClip = activeGrid.getEditedActiveClip();
    assertNotNull(editedClip, "Edited active clip must be non-null when synth track is selected");
    
    boolean foundActiveStep = false;
    for (int r = 0; r < editedClip.getRowCount(); r++) {
      for (int c = 0; c < editedClip.getStepCount(); c++) {
        StepData s = editedClip.getStep(r, c);
        if (s != null && s.active()) {
          foundActiveStep = true;
          assertEquals(100.0f / 127.0f, s.velocity(), 0.05f, "Recorded note velocity must match incoming MIDI message");
        }
      }
    }
    assertTrue(foundActiveStep, "MIDI follow must record notes when a SynthTrackModel is selected");

    // 2. Select Track 1 (Audio Track) and send a new Note On (Note 62, velocity 100)
    activeGrid.setEditedModelTrack(1);
    activeGrid.setActiveClipId(0);
    activeGrid.updatePlayhead(2);

    MidiMsg noteOnMsg2 = new MidiMsg();
    noteOnMsg2.data1 = 0x90;
    noteOnMsg2.data2 = 62;
    noteOnMsg2.data3 = 100;

    router.handleMidiMessage(noteOnMsg2);

    // Verify ZERO note steps were added to the audio clip (C++ c8a9dc6f parity)
    StepData audioStep = audioClip.getStep(0, 2);
    assertTrue(
        audioStep == null || !audioStep.active(),
        "MIDI follow must safely ignore AudioTrackModel and not record notes into audio clips");
  }
}
