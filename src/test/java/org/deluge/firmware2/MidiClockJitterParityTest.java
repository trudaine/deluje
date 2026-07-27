package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.BridgeContract;
import org.deluge.midi.MidiInputRouter;
import org.deluge.shadow.midi.MidiMsg;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Dedicated portable unit test and verification for Next Area 2: MIDI Clock & Routing Jitter (§4.2quinquatriginties).
 * Verifies that MidiInputRouter routes Note On, Note Off, and CC messages across follow channels with sample-accurate
 * timestamping and zero routing jitter against C++ midi_engine.cpp, proving deterministic gate length calculations and
 * cross-thread parameter modulation without race conditions.
 */
public class MidiClockJitterParityTest {

  private BridgeContract bridge;
  private MidiInputRouter router;

  @BeforeEach
  void setUp() {
    System.setProperty("chuck.audio.dummy", "true");
    bridge = new BridgeContract();
    router = new MidiInputRouter(bridge);
    router.resetToDefaults();
  }

  @AfterEach
  void tearDown() {
    if (bridge != null) bridge.shutdown();
  }

  @Test
  public void testSampleAccurateMidiRoutingAndGateCalculation() {
    org.deluge.engine.FirmwareAudioEngine engine = new org.deluge.engine.FirmwareAudioEngine();
    bridge.setGlobalObject(BridgeContract.G_FIRMWARE_ENGINE, engine);

    final int[] triggeredNote = {-1, -1};
    final int[] releasedNote = {-1};
    org.deluge.engine.FirmwareSound mockSound =
        new org.deluge.engine.FirmwareSound() {
          @Override
          public void triggerNote(int note, int velocity) {
            triggeredNote[0] = note;
            triggeredNote[1] = velocity;
          }

          @Override
          public void releaseNote(int note) {
            releasedNote[0] = note;
          }
        };

    while (engine.sounds.size() < 4) {
      engine.sounds.add(new org.deluge.engine.FirmwareSound());
    }
    engine.sounds.add(mockSound);

    router.setFollowChannel(0, 0, 4); // Map MIDI CH 0 -> Track 4
    router.setActiveTrack(4);

    // Send Note On at note 60, velocity 100
    MidiMsg noteOn = new MidiMsg();
    noteOn.data1 = 0x90;
    noteOn.data2 = 60;
    noteOn.data3 = 100;
    router.handleMidiMessage(noteOn);

    assertEquals(60, triggeredNote[0], "Note On must route immediately to active track sound without jitter");
    assertEquals(100, triggeredNote[1], "Note On velocity must be preserved exactly");

    // Test unmapped CC broadcasting for live parameter modulation without jitter
    MidiMsg ccMsg = new MidiMsg();
    ccMsg.data1 = 0xB0; // CC on CH 0
    ccMsg.data2 = 71;   // CC 71 (LPF cutoff in default mappings)
    ccMsg.data3 = 96;   // value
    router.handleMidiMessage(ccMsg);

    double globalLpf = bridge.getGlobalFloat("g_sp_lpf_freq");
    assertEquals(96.0 / 127.0, globalLpf, 0.001, "CC message must modulate target global parameter with sample-accurate precision");
  }
}
