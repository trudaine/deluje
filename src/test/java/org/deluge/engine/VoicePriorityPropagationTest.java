package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.playback.InstrumentClip;
import org.deluge.playback.Song;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the bridge gap found in the 2026-06 Sound.java/Bridge audit: {@code
 * voicePriority} is read by voice stealing (Voice.java {@code (3 - sound.voicePriority) << 30}) but
 * {@code FirmwareSound.syncParamsToFw2} never propagated it, so it was permanently stuck at the
 * default "medium" (1) regardless of the patch. This pins model → fw2Sound propagation.
 */
class VoicePriorityPropagationTest {

  @Test
  void synthVoicePriorityReachesFw2Sound() {
    for (int vp = 0; vp <= 2; vp++) {
      SynthTrackModel synth = new SynthTrackModel("vp" + vp);
      synth.setVoicePriority(vp);
      synth.addClip(new ClipModel("c", 1, 16));

      ProjectModel project = new ProjectModel();
      project.addTrack(synth);
      Song song = org.deluge.engine.FirmwareFactory.createSong(project);
      FirmwareSound sound = (FirmwareSound) ((InstrumentClip) song.clips.get(0)).sound;
      sound.syncParamsToFw2();

      assertEquals(vp, sound.fw2Sound.voicePriority, "bridge voicePriority");
      assertEquals(vp, sound.fw2Sound.voicePriority, "fw2 voicePriority (read by voice stealing)");
    }
  }

  @Test
  void defaultVoicePriorityIsMedium() {
    assertEquals(1, new org.deluge.firmware2.Sound().voicePriority);
    assertEquals(1, new SynthTrackModel("d").getVoicePriority());
  }
}
