package org.chuck.deluge.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.chuck.deluge.model.AudioTrackModel;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.MidiTrackModel;
import org.chuck.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Guards the structural-change detection that decides whether to rebuild live voices. A mismatch
 * must force a rebuild; a match must allow the in-place (non-destructive) update — which is what
 * keeps editing during playback from producing garbage audio.
 */
public class EngineRebuildGuardTest {

  @Test
  public void synthTrackWithNoSoundYetCountsAsChanged() {
    // null sound for a synth slot -> not matched -> triggers a (re)build.
    assertFalse(SwingDelugeApp.soundMatchesTrack(new SynthTrackModel("s"), null));
  }

  @Test
  public void kitTrackWithNoSoundCountsAsChanged() {
    assertFalse(SwingDelugeApp.soundMatchesTrack(new KitTrackModel("k"), null));
  }

  @Test
  public void midiAndAudioTracksMatchWhenSoundIsNull() {
    // MIDI / Audio tracks have no engine sound, so a null slot is a correct match (no rebuild).
    assertTrue(SwingDelugeApp.soundMatchesTrack(new MidiTrackModel("m"), null));
    assertTrue(SwingDelugeApp.soundMatchesTrack(new AudioTrackModel("a"), null));
  }
}
