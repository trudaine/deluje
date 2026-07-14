package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Guards the single-sample (non-multizone) oscillator playback settings: {@code
 * FirmwareFactory.loadOscResources} previously loaded the sample but never copied {@code
 * loopMode}/{@code reversed}/{@code timeStretch} from the {@link SynthTrackModel} into {@code
 * sound.sampleSettings}, so a synth-track sample-based oscillator always played un-looped, forward,
 * and without time-stretch regardless of what the preset specified.
 */
public class SynthSampleOscSettingsTest {

  @Test
  void loopModeReverseAndTimeStretchAreAppliedToSingleSampleOscillator() throws Exception {
    File wav = new File("src/main/resources/examples/data/kick.wav");
    assertTrue(wav.exists(), "fixture WAV not found");

    SynthTrackModel synth = new SynthTrackModel("Test Sample Synth");
    synth.setOsc1Type("SAMPLE");
    synth.setOsc1SamplePath(wav.getAbsolutePath());
    synth.setOsc1LoopMode(1);
    synth.setOsc1Reversed(true);
    synth.setOsc1TimeStretch(true);
    synth.addClip(new ClipModel("c", 1, 16));

    ProjectModel project = new ProjectModel();
    project.addTrack(synth);
    ProjectModel song = FirmwareFactory.createSong(project);
    FirmwareSound fs = (FirmwareSound) song.getTracks().get(0).getActiveClip().getSound();

    assertEquals(1, fs.sampleSettings[0].loopMode);
    assertTrue(fs.sampleSettings[0].reverse);
    assertTrue(fs.sampleSettings[0].timestretch);

    // And the fw2 engine fields they feed (post param-sync) must reflect the same values.
    fs.syncParamsToFw2();
    assertEquals(1, fs.fw2Sound.sampleLoopMode[0]);
    assertTrue(fs.fw2Sound.sampleReverse[0]);
    assertTrue(fs.fw2Sound.sampleTimestretch[0]);
  }
}
