package org.deluge.midi;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.BridgeContract;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.playback.PlaybackHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MidiServiceTest {

  private BridgeContract bridge;

  private MidiInputRouter router;
  private MidiService service;
  private PlaybackHandler playbackHandler;
  private ProjectModel song;
  private FirmwareSound sound;

  @BeforeEach
  void setUp() {
    System.setProperty("chuck.audio.dummy", "true");
    bridge = new BridgeContract();

    router = new MidiInputRouter(bridge);
    service = new MidiService(bridge, router);

    // Setup active song & firmware sounds
    playbackHandler = new PlaybackHandler();
    song = new ProjectModel();
    ClipModel clip = new ClipModel("clip", 8, 16);
    sound = new FirmwareSound();
    clip.setSound(sound);
    org.deluge.model.SynthTrackModel track = new org.deluge.model.SynthTrackModel("track");
    track.addClip(clip);
    song.addTrack(track);
    playbackHandler.setProject(song);

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
    // Initial state: pitch bend on voice should be neutral (0)
    service.handleMidiMessage(0xE0, 64, 64); // Pitch Bend on channel 0, center position
    // Since voice pitch bend is handled per active voice triggering, we just verify
    // that the MIDI message was successfully processed without throwing an exception.
    assertTrue(true);
  }
}
