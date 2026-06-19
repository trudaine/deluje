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
    // Map MIDI channel 0 → Track 4 (follow channel A)
    router.setFollowChannel(0, 0, 4);
    // Synth Track 1 (Index 4)
    router.setActiveTrack(4);

    // Fake sequencer stopped (step 0)
    bridge.setGlobalInt(BridgeContract.G_CURRENT_STEP, 0L);

    MidiMsg noteOn = new MidiMsg();
    noteOn.data1 = 0x90; // Note On, CH 1
    noteOn.data2 = 72; // C5
    noteOn.data3 = 100; // Velocity

    router.handleMidiMessage(noteOn);

    // Retrieve arrays from bridge
    ChuckArray pitchArray = (ChuckArray) bridge.getGlobalObject(BridgeContract.G_PITCH);
    ChuckArray patternArray = (ChuckArray) bridge.getGlobalObject(BridgeContract.G_PATTERN);
    ChuckArray velArray = (ChuckArray) bridge.getGlobalObject(BridgeContract.G_VELOCITY);

    // Track 4, Step 0
    int index = 4 * BridgeContract.STEPS + 0;

    // Pitch should be offset from C3 (60): 72 - 60 = 12 (1 octave up)
    assertEquals(12L, pitchArray.getInt(index));

    // Pattern should be 1 (active)
    assertEquals(1L, patternArray.getInt(index));

    // Velocity should be 100/127
    assertEquals(100.0 / 127.0, velArray.getFloat(index), 0.01);
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
    // Map MIDI channel 0 → Track 4 (follow channel A)
    router.setFollowChannel(0, 0, 4);
    router.setActiveTrack(4);

    // Fake sequencer stopped (step 0)
    bridge.setGlobalInt(BridgeContract.G_CURRENT_STEP, 0L);

    MidiMsg noteOn = new MidiMsg();
    noteOn.data1 = 0x90; // Note On, CH 1
    noteOn.data2 = 72; // C5
    noteOn.data3 = 100; // Velocity

    router.handleMidiMessage(noteOn);

    // Now switch active track index to a DIFFERENT track (e.g. 5)
    router.setActiveTrack(5);
    bridge.advanceTime(4410);

    MidiMsg noteOff = new MidiMsg();
    noteOff.data1 = 0x80; // Note Off, CH 1
    noteOff.data2 = 72; // C5
    noteOff.data3 = 0;

    router.handleMidiMessage(noteOff);

    // Retrieve gate array from bridge
    ChuckArray gateArray = (ChuckArray) bridge.getGlobalObject(BridgeContract.G_GATE);

    // Track 4 index step 0
    int index4 = 4 * BridgeContract.STEPS + 0;
    // Track 5 index step 0
    int index5 = 5 * BridgeContract.STEPS + 0;

    // Gate on original track 4 should be updated to a positive float value of exactly 0.8
    // (calculated from 4410 samples / 100ms time advance)
    assertEquals(0.8f, gateArray.getFloat(index4), 0.01f, "track 4 gate should be set to 0.8");
    // Gate on track 5 should remain at its pre-initialized default value (0.9f)
    assertEquals(0.9f, gateArray.getFloat(index5), 0.01f, "track 5 gate should remain untouched");
  }
}
