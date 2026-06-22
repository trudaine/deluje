package org.deluge.midi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.BridgeContract;
import org.deluge.engine.FirmwareSound;
import org.deluge.playback.InstrumentClip;
import org.deluge.playback.PlaybackHandler;
import org.deluge.playback.Song;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MidiServiceTest {

  private BridgeContract bridge;

  private MidiInputRouter router;
  private MidiService service;
  private PlaybackHandler playbackHandler;
  private Song song;
  private FirmwareSound sound;

  @BeforeEach
  void setUp() {
    System.setProperty("chuck.audio.dummy", "true");
    bridge = new BridgeContract();

    router = new MidiInputRouter(bridge);
    service = new MidiService(bridge, router);

    // Setup active song & firmware sounds
    playbackHandler = new PlaybackHandler();
    song = new Song();
    InstrumentClip clip = new InstrumentClip();
    sound = new FirmwareSound();
    clip.sound = sound;
    song.addClip(clip); // clip at track 0
    playbackHandler.setSong(song);

    // Register G_PLAYBACK_HANDLER so MidiService can lookup the song/sound
    bridge.setGlobalObject(BridgeContract.G_PLAYBACK_HANDLER, playbackHandler);

    // Set active track to 0
    service.setActiveTrack(0);
  }

  @AfterEach
  void tearDown() {
    if (bridge != null) bridge.shutdown();
  }

  @Test
  void testPitchBendRouting() {
    // Pitch Bend message: Channel 0, value 10000 (standard 14-bit value)
    MIDIMessage msg = MIDIMessage.pitchBend(0, 10000 & 0x7F, (10000 >> 7) & 0x7F);

    // Trigger callback
    service.getEngine().midiMessageReceived(msg, null);

    // Verify bridge step pitch has been updated
    double expectedOffset = (10000 - 8192.0) / 8192.0 * 2.0;
    assertEquals(expectedOffset / 24.0, bridge.getStepPitch(0, 0), 1e-4);
  }

  @Test
  void testAftertouchRouting() {
    // Channel Aftertouch message: Channel 0, value 85
    MIDIMessage msg = new MIDIMessage(0x0D, 0x00, 85, 0);

    // Trigger callback
    service.getEngine().midiMessageReceived(msg, null);

    // Just check that execution completed without error
    assertTrue(true);
  }
}
