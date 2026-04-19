package org.chuck.deluge.midi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.midi.MidiMsg;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MidiInputRouterTest {

  private ChuckVM vm;
  private BridgeContract bridge;
  private MidiInputRouter router;

  @BeforeEach
  void setUp() {
    System.setProperty("chuck.audio.dummy", "true");
    vm = new ChuckVM(44100, 2);
    bridge = new BridgeContract();
    bridge.register(vm);
    router = new MidiInputRouter(vm, bridge);
  }

  @AfterEach
  void tearDown() {
    if (vm != null) vm.shutdown();
  }

  @Test
  void testNoteOnRouting() {
    // Synth Track 1 (Index 4)
    router.setActiveTrack(4);

    // Fake sequencer stopped (step 0)
    vm.setGlobalInt(BridgeContract.G_CURRENT_STEP, 0L);

    MidiMsg noteOn = new MidiMsg();
    noteOn.data1 = 0x90; // Note On, CH 1
    noteOn.data2 = 72; // C5
    noteOn.data3 = 100; // Velocity

    router.handleMidiMessage(noteOn);

    // Retrieve arrays from bridge
    ChuckArray pitchArray = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PITCH);
    ChuckArray patternArray = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PATTERN);
    ChuckArray velArray = (ChuckArray) vm.getGlobalObject(BridgeContract.G_VELOCITY);

    // Track 4, Step 0
    int index = 4 * 16 + 0;

    // Pitch should be offset from C3 (60): 72 - 60 = 12 (1 octave up)
    assertEquals(12L, pitchArray.getInt(index));

    // Pattern should be 1 (active)
    assertEquals(1L, patternArray.getInt(index));

    // Velocity should be 100/127
    assertEquals(100.0 / 127.0, velArray.getFloat(index), 0.01);
  }
}
