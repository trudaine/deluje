package org.deluge.midi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.deluge.BridgeContract;
import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.playback.PlaybackHandler;
import org.deluge.project.PreferencesManager;
import org.deluge.shadow.midi.MidiMsg;
import org.deluge.ui.SwingDelugeApp;
import org.deluge.ui.SwingGridPanel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MidiRouterEngineServiceParityTest {

  private BridgeContract bridge;
  private MidiInputRouter router;
  private String oldMidiInput;

  @BeforeEach
  void setUp() {
    System.setProperty("chuck.audio.dummy", "true");
    oldMidiInput = PreferencesManager.get("midi.input", "None");
    PreferencesManager.set("midi.grid.mode", "false");
    bridge = new BridgeContract();
    router = new MidiInputRouter(bridge);
  }

  @AfterEach
  void tearDown() {
    if (bridge != null) {
      bridge.shutdown();
    }
    PreferencesManager.set("midi.input", oldMidiInput != null ? oldMidiInput : "None");
    SwingDelugeApp.mainInstance = null;
    SwingGridPanel.isLiveRecordModeActive = false;
  }

  @Test
  void midiInputRouter_tracksActiveNotesPerMidiChannelInFollowMode() {
    FirmwareAudioEngine fwEngine = new FirmwareAudioEngine();
    final int[] releasedTrack0 = {-1};
    final int[] releasedTrack1 = {-1};

    FirmwareSound sound0 =
        new FirmwareSound() {
          @Override
          public void releaseNote(int note) {
            releasedTrack0[0] = note;
          }
        };
    FirmwareSound sound1 =
        new FirmwareSound() {
          @Override
          public void releaseNote(int note) {
            releasedTrack1[0] = note;
          }
        };
    fwEngine.sounds.add(sound0);
    fwEngine.sounds.add(sound1);
    bridge.setGlobalObject(BridgeContract.G_FIRMWARE_ENGINE, fwEngine);

    router.setFollowModeEnabled(true);
    router.setFollowChannel(0, 0, 0); // MIDI CH 0 -> Track 0
    router.setFollowChannel(1, 1, 1); // MIDI CH 1 -> Track 1

    MidiMsg noteOnCh0 = new MidiMsg();
    noteOnCh0.data1 = 0x90; // CH 0 Note On
    noteOnCh0.data2 = 60;
    noteOnCh0.data3 = 100;
    router.handleMidiMessage(noteOnCh0);

    MidiMsg noteOnCh1 = new MidiMsg();
    noteOnCh1.data1 = 0x91; // CH 1 Note On (same pitch 60)
    noteOnCh1.data2 = 60;
    noteOnCh1.data3 = 110;
    router.handleMidiMessage(noteOnCh1);

    // Releasing pitch 60 on CH 0 must release Track 0 only, leaving Track 1 untouched.
    MidiMsg noteOffCh0 = new MidiMsg();
    noteOffCh0.data1 = 0x80; // CH 0 Note Off
    noteOffCh0.data2 = 60;
    noteOffCh0.data3 = 0;
    router.handleMidiMessage(noteOffCh0);

    assertEquals(60, releasedTrack0[0], "CH 0 Note Off must release Track 0");
    assertEquals(-1, releasedTrack1[0], "CH 0 Note Off must not release Track 1");

    // Releasing pitch 60 on CH 1 now releases Track 1.
    MidiMsg noteOffCh1 = new MidiMsg();
    noteOffCh1.data1 = 0x81; // CH 1 Note Off
    noteOffCh1.data2 = 60;
    noteOffCh1.data3 = 0;
    router.handleMidiMessage(noteOffCh1);

    assertEquals(60, releasedTrack1[0], "CH 1 Note Off must release Track 1");
  }

  @Test
  void midiInputRouter_learningIsAdditiveUntilExplicitUnlearn() {
    router.resetToDefaults();
    assertEquals(71, router.getCcForParam("g_sp_lpf_freq"));

    // Learn CC 20 to g_sp_lpf_freq AND g_sp_lpf_res (one CC -> multiple params).
    router.startLearning("g_sp_lpf_freq");
    MidiMsg learnCc20 = new MidiMsg();
    learnCc20.data1 = 0xB0;
    learnCc20.data2 = 20;
    learnCc20.data3 = 100;
    router.handleMidiMessage(learnCc20);

    router.startLearning("g_sp_lpf_res");
    router.handleMidiMessage(learnCc20);

    // Turning CC 20 drives both g_sp_lpf_freq and g_sp_lpf_res.
    MidiMsg moveCc20 = new MidiMsg();
    moveCc20.data1 = 0xB0;
    moveCc20.data2 = 20;
    moveCc20.data3 = 64;
    router.handleMidiMessage(moveCc20);
    assertEquals(64.0 / 127.0, bridge.getGlobalFloat("g_sp_lpf_freq"), 0.01);
    assertEquals(64.0 / 127.0, bridge.getGlobalFloat("g_sp_lpf_res"), 0.01);

    // Old default CC 71 also remains bound to g_sp_lpf_freq until explicit clearMapping.
    MidiMsg oldCc71 = new MidiMsg();
    oldCc71.data1 = 0xB0;
    oldCc71.data2 = 71;
    oldCc71.data3 = 127;
    router.handleMidiMessage(oldCc71);
    assertEquals(1.0, bridge.getGlobalFloat("g_sp_lpf_freq"), 1e-4);

    // Explicit clearMapping (unlearnKnobs) removes all CCs for g_sp_lpf_freq only.
    router.clearMapping("g_sp_lpf_freq");
    assertEquals(-1, router.getCcForParam("g_sp_lpf_freq"));
    assertEquals(20, router.getCcForParam("g_sp_lpf_res"));
  }

  @Test
  void midiInputRouter_liveRecordNoteOffUpdatesGateInOriginalClipAndUndoRedo() throws Exception {
    MidiService midiService = new MidiService(bridge, router);
    SwingDelugeApp app = new SwingDelugeApp(bridge, midiService, true);
    try {
      SwingGridPanel activeGrid = app.getActiveGridPanel();
      assertNotNull(activeGrid);
      ProjectModel project = activeGrid.getProjectModel();

      // Ensure we have two synth tracks so we can switch tracks mid-note.
      SynthTrackModel extraTrack = new SynthTrackModel("Synth 2");
      extraTrack.addClip(new ClipModel("Clip 2", 8, 16));
      project.addTrack(extraTrack);

      activeGrid.setEditedModelTrack(0);
      activeGrid.setActiveClipId(0);
      SwingGridPanel.isLiveRecordModeActive = true;
      bridge.setGlobalInt(BridgeContract.G_PLAY, 1L);
      activeGrid.updatePlayhead(3);

      MidiMsg noteOn = new MidiMsg();
      noteOn.data1 = 0x90;
      noteOn.data2 = 60;
      noteOn.data3 = 100;
      router.handleMidiMessage(noteOn);

      // Switch edited track before Note Off arrives.
      activeGrid.setEditedModelTrack(project.getTracks().size() - 1);

      bridge.advanceTime(4410);
      MidiMsg noteOff = new MidiMsg();
      noteOff.data1 = 0x80;
      noteOff.data2 = 60;
      noteOff.data3 = 0;
      router.handleMidiMessage(noteOff);

      ClipModel recordedClip = project.getTracks().get(0).getClips().get(0);
      int row = activeGrid.findSynthClipRowForPitch(recordedClip, 60);
      StepData recordedStep = recordedClip.getStep(row, 3);
      assertNotNull(recordedStep);
      assertTrue(recordedStep.active());
      assertEquals(0.8f, recordedStep.gate(), 0.05f, "Original clip gate must be updated");

      // Undo and redo must preserve the Note Off duration gate (~0.8f), not Note On's 1.0f.
      project.getUndoRedoStack().undo();
      StepData undone = recordedClip.getStep(row, 3);
      assertTrue(undone == null || !undone.active());

      project.getUndoRedoStack().redo();
      StepData redone = recordedClip.getStep(row, 3);
      assertNotNull(redone);
      assertTrue(redone.active());
      assertEquals(0.8f, redone.gate(), 0.05f, "Redo must restore the Note Off duration gate");
    } finally {
      app.dispose();
    }
  }

  @Test
  void midiEngine_noteOnWithZeroVelocityDispatchesNoteOffWithDefaultLift64() {
    MidiEngine engine = new MidiEngine();
    AtomicInteger noteOnCount = new AtomicInteger();
    final MIDIMessage[] receivedNoteOff = {null};
    engine.setOnNoteOn(msg -> noteOnCount.incrementAndGet());
    engine.setOnNoteOff(msg -> receivedNoteOff[0] = msg);

    engine.midiMessageReceived(new MIDIMessage(0x09, 3, 64, 0), "src");

    assertEquals(0, noteOnCount.get(), "Note On velocity 0 must not fire onNoteOn");
    assertNotNull(receivedNoteOff[0], "Note On velocity 0 must fire onNoteOff");
    assertEquals(0x08, receivedNoteOff[0].statusType());
    assertEquals(3, receivedNoteOff[0].channel());
    assertEquals(64, receivedNoteOff[0].data1());
    assertEquals(64, receivedNoteOff[0].data2(), "C++ kDefaultLiftValue is 64");
  }

  @Test
  void midiService_ccLearnInterceptsBeforeMidiFollowAndDoesNotDoubleDispatch() {
    PreferencesManager.set("midi.input", "TestPort");
    PreferencesManager.set("midi.learn.TestPort.g_sp_volume", "None");
    PreferencesManager.set("midi.learn.TestPort.g_sp_pan", "15");
    try {
      MidiService service = new MidiService(bridge, router);
      AtomicInteger followSetCount = new AtomicInteger();
      service
          .getEngine()
          .getMidiFollow()
          .setOnSetParam((param, val) -> followSetCount.incrementAndGet());

      // Start MIDI learn for g_sp_volume and send CC 15 (which was previously bound to g_sp_pan).
      service.startLearn("g_sp_volume");
      service.handleMidiMessage(0xB0, 15, 99);

      assertFalse(service.isLearning());
      assertEquals(
          0, followSetCount.get(), "MIDI Learn must intercept CC before MidiFollow routes it");
      assertEquals("15", PreferencesManager.get("midi.learn.TestPort.g_sp_volume", "None"));
      assertEquals(
          "15",
          PreferencesManager.get("midi.learn.TestPort.g_sp_pan", "None"),
          "Previous parameter bound to the same CC must remain bound (mod_controllable_audio.cpp:1506-1532)");

      // Normal CC 7 (built-in volume in MidiFollow) must invoke MidiFollow exactly once.
      service.handleMidiMessage(0xB0, 7, 100);
      assertEquals(1, followSetCount.get(), "MidiFollow must handle each CC once, not twice");

      // Learning a second CC (16) onto g_sp_volume keeps both 15 and 16 bound.
      service.startLearn("g_sp_volume");
      service.handleMidiMessage(0xB0, 16, 80);
      assertEquals("15,16", PreferencesManager.get("midi.learn.TestPort.g_sp_volume", "None"));
    } finally {
      PreferencesManager.set("midi.learn.TestPort.g_sp_volume", "None");
      PreferencesManager.set("midi.learn.TestPort.g_sp_pan", "None");
    }
  }

  @Test
  void midiService_handleMidiMessageRoutesPitchBendAndVelocityZeroNoteOff() {
    PlaybackHandler playbackHandler = new PlaybackHandler();
    ProjectModel song = new ProjectModel();
    ClipModel clip = new ClipModel("clip", 8, 16);
    final int[] lastBend = {-1};
    final int[] triggered = {-1};
    final int[] released = {-1};
    FirmwareSound sound =
        new FirmwareSound() {
          @Override
          public void mpePitchBend(int channel, int bend14) {
            lastBend[0] = bend14;
          }

          @Override
          public void triggerNote(int note, int velocity) {
            triggered[0] = note;
          }

          @Override
          public void releaseNote(int note) {
            released[0] = note;
          }
        };
    clip.setSound(sound);
    SynthTrackModel track = new SynthTrackModel("track");
    track.addClip(clip);
    song.addTrack(track);
    playbackHandler.setProject(song);
    bridge.setGlobalObject(BridgeContract.G_PLAYBACK_HANDLER, playbackHandler);

    FirmwareAudioEngine fwEngine = new FirmwareAudioEngine();
    fwEngine.sounds.add(sound);
    bridge.setGlobalObject(BridgeContract.G_FIRMWARE_ENGINE, fwEngine);

    MidiService service = new MidiService(bridge, router);
    service.setActiveTrack(0);
    router.setFollowModeEnabled(false);

    // Pitch bend: status 0xE0, LSB=0, MSB=96 -> 14-bit value = (96 << 7) | 0 = 12288
    service.handleMidiMessage(0xE0, 0, 96);
    assertEquals(12288, lastBend[0], "Pitch bend must reach FirmwareSound via MidiEngine");

    // Note On followed by Note On with velocity 0 (Note Off)
    service.handleMidiMessage(0x90, 62, 100);
    assertEquals(62, triggered[0]);
    service.handleMidiMessage(0x90, 62, 0);
    assertEquals(62, released[0], "Note On with velocity 0 must release the note");
  }

  @Test
  void midiDeviceDefinitionAndFeedbackService_replaceDuplicateCcAndClampValues() {
    MidiDeviceDefinition def = new MidiDeviceDefinition("test-dev", "Test Device");
    def.addCcMapping(new MidiDeviceDefinition.CcMapping(14, "g_sp_lpf_freq", "Cutoff"));
    def.addCcMapping(new MidiDeviceDefinition.CcMapping(14, "g_sp_lpf_res", "Resonance"));

    assertEquals(1, def.getCcMappings().size(), "Duplicate CC mapping must replace old entry");
    assertEquals("g_sp_lpf_res", def.findMapping(14).paramName());

    final int[] sentCc = {-1, -1};
    MidiFeedbackService feedback =
        new MidiFeedbackService(bridge) {
          @Override
          public void sendCc(int channel, int cc, int value) {
            sentCc[0] = cc;
            sentCc[1] = value;
          }
        };
    feedback.setDeviceDefinition(def);

    feedback.notifyParamChanged("g_sp_lpf_res", 1.5f);
    assertEquals(14, sentCc[0]);
    assertEquals(127, sentCc[1], "Feedback CC value must clamp above 127");

    feedback.notifyParamChanged("g_sp_lpf_res", -0.5f);
    assertEquals(0, sentCc[1], "Feedback CC value must clamp below 0");
  }
}
