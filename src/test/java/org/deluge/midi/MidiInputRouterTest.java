package org.deluge.midi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.deluge.BridgeContract;
import org.deluge.shadow.core.ChuckArray;
import org.deluge.shadow.midi.MidiMsg;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MidiInputRouterTest {

  private BridgeContract bridge;

  private MidiInputRouter router;

  @BeforeEach
  void setUp() {
    System.setProperty("chuck.audio.dummy", "true");
    bridge = new BridgeContract();

    org.deluge.project.PreferencesManager.set("midi.grid.mode", "false");
    router = new MidiInputRouter(bridge);
  }

  @AfterEach
  void tearDown() {
    if (bridge != null) bridge.shutdown();
  }

  @Test
  void testNoteOnRouting() {
    // Set up FirmwareAudioEngine with our mock Sound at index 4
    org.deluge.engine.FirmwareAudioEngine engine = new org.deluge.engine.FirmwareAudioEngine();
    bridge.setGlobalObject(BridgeContract.G_FIRMWARE_ENGINE, engine);

    final int[] triggerData = new int[] {-1, -1};
    org.deluge.engine.FirmwareSound mockSound =
        new org.deluge.engine.FirmwareSound() {
          @Override
          public void triggerNote(int note, int velocity) {
            triggerData[0] = note;
            triggerData[1] = velocity;
          }
        };
    for (int i = 0; i < 4; i++) {
      engine.sounds.add(new org.deluge.engine.FirmwareSound());
    }
    engine.sounds.add(mockSound);

    // Map MIDI channel 0 → Track 4 (follow channel A)
    router.setFollowChannel(0, 0, 4);
    router.setActiveTrack(4);

    // Send Note On
    MidiMsg noteOn = new MidiMsg();
    noteOn.data1 = 0x90; // Note On, CH 1
    noteOn.data2 = 72; // C5
    noteOn.data3 = 100; // Velocity

    router.handleMidiMessage(noteOn);

    // 1. Verify it was triggered live on the sound engine!
    assertEquals(72, triggerData[0]);
    assertEquals(100, triggerData[1]);

    // 2. Verify that NO step was written to the sequencer/bridge!
    ChuckArray patternArray = (ChuckArray) bridge.getGlobalObject(BridgeContract.G_PATTERN);
    int index = 4 * BridgeContract.STEPS + 0;
    assertEquals(0L, patternArray.getInt(index), "No step should be written in audition mode");
  }

  @Test
  void testMidiLearning() {
    String target = BridgeContract.G_FILTER;
    router.startLearning(target);

    // CC 7, Value 127
    MidiMsg learnMsg = new MidiMsg();
    learnMsg.data1 = 0xB0;
    learnMsg.data2 = 7;
    learnMsg.data3 = 127;
    router.handleMidiMessage(learnMsg);

    // Now send CC 7 with value 64 (approx 0.5)
    MidiMsg ctrlMsg = new MidiMsg();
    ctrlMsg.data1 = 0xB0;
    ctrlMsg.data2 = 7;
    ctrlMsg.data3 = 64;
    router.handleMidiMessage(ctrlMsg);

    assertEquals(64.0 / 127.0, bridge.getGlobalFloat(target), 0.01);
  }

  @Test
  void testNoteOffContextSwitchSafety() {
    // Set up FirmwareAudioEngine with mock Sounds at index 4 and 5
    org.deluge.engine.FirmwareAudioEngine engine = new org.deluge.engine.FirmwareAudioEngine();
    bridge.setGlobalObject(BridgeContract.G_FIRMWARE_ENGINE, engine);

    final int[] releaseData4 = new int[] {-1};
    final int[] releaseData5 = new int[] {-1};

    org.deluge.engine.FirmwareSound mockSound4 =
        new org.deluge.engine.FirmwareSound() {
          @Override
          public void releaseNote(int note) {
            releaseData4[0] = note;
          }
        };
    org.deluge.engine.FirmwareSound mockSound5 =
        new org.deluge.engine.FirmwareSound() {
          @Override
          public void releaseNote(int note) {
            releaseData5[0] = note;
          }
        };

    for (int i = 0; i < 4; i++) {
      engine.sounds.add(new org.deluge.engine.FirmwareSound());
    }
    engine.sounds.add(mockSound4);
    engine.sounds.add(mockSound5);

    // Map MIDI channel 0 → Track 4 (follow channel A)
    router.setFollowChannel(0, 0, 4);
    router.setActiveTrack(4);

    // Send Note On
    MidiMsg noteOn = new MidiMsg();
    noteOn.data1 = 0x90; // Note On, CH 1
    noteOn.data2 = 72; // C5
    noteOn.data3 = 100;

    router.handleMidiMessage(noteOn);

    // Now switch active track index to a DIFFERENT track (e.g. 5)
    router.setActiveTrack(5);

    // Send Note Off
    MidiMsg noteOff = new MidiMsg();
    noteOff.data1 = 0x80; // Note Off, CH 1
    noteOff.data2 = 72; // C5
    noteOff.data3 = 0;

    router.handleMidiMessage(noteOff);

    // Verify that the note off was routed to the original track (Track 4), NOT Track 5!
    assertEquals(72, releaseData4[0], "Track 4 should receive the release note");
    assertEquals(-1, releaseData5[0], "Track 5 should not receive any release note");
  }

  @Test
  void testRealTimeMidiRecording() throws Exception {
    // 1. Initialize MIDI Service & Headless App
    org.deluge.midi.MidiService midiService = new org.deluge.midi.MidiService(bridge, router);
    org.deluge.ui.SwingDelugeApp app = new org.deluge.ui.SwingDelugeApp(bridge, midiService, true);

    try {
      org.deluge.ui.SwingGridPanel activeGrid = app.getActiveGridPanel();
      org.junit.jupiter.api.Assertions.assertNotNull(activeGrid);

      // 2. Enable record mode & mock playback
      org.deluge.ui.SwingGridPanel.isLiveRecordModeActive = true;
      bridge.setGlobalInt(BridgeContract.G_PLAY, 1L);

      // Mock playhead at step 2
      activeGrid.updatePlayhead(2);

      // 3. Send Note On: C4 (60) with velocity 100 on Track 4 (Synth)
      MidiMsg noteOn = new MidiMsg();
      noteOn.data1 = 0x90; // Note On, CH 1
      noteOn.data2 = 60; // C4 (Middle C)
      noteOn.data3 = 100; // Velocity

      router.handleMidiMessage(noteOn);

      // 4. Assert Note was recorded in active ClipModel
      org.deluge.model.ClipModel clip = activeGrid.getEditedActiveClip();
      org.junit.jupiter.api.Assertions.assertNotNull(clip);

      // Middle C (60) maps to piano roll row: 127 - 60 = 67
      org.deluge.model.StepData step = activeGrid.getClipStep(clip, 67, 2);
      org.junit.jupiter.api.Assertions.assertNotNull(step);
      org.junit.jupiter.api.Assertions.assertTrue(step.active(), "Step should be recorded active!");
      assertEquals(60, step.pitch(), "Pitch should be the absolute MIDI pitch 60");
      assertEquals(100.0 / 127.0, step.velocity(), 0.01);

      // 5. Send Note Off after 100ms time advance
      bridge.advanceTime(4410);
      MidiMsg noteOff = new MidiMsg();
      noteOff.data1 = 0x80; // Note Off, CH 1
      noteOff.data2 = 60;
      noteOff.data3 = 0;

      router.handleMidiMessage(noteOff);

      // 6. Assert gate length was updated to ~0.8
      org.deluge.model.StepData updatedStep = activeGrid.getClipStep(clip, 67, 2);
      assertEquals(0.8f, updatedStep.gate(), 0.05f, "Gate length should reflect note duration!");

      // 7. Assert consequence was registered on UndoRedoStack
      org.junit.jupiter.api.Assertions.assertTrue(
          activeGrid.getProjectModel().getUndoRedoStack().size() > 0,
          "Should push StepConsequence to undo/redo stack!");

    } finally {
      // Clean up static process global
      org.deluge.ui.SwingDelugeApp.mainInstance = null;
      org.deluge.ui.SwingGridPanel.isLiveRecordModeActive = false;
    }
  }
}
