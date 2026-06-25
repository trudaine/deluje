package org.deluge;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Proves the oscAPulseWidth/oscBPulseWidth gap is truly closed: the param must reach the DSP and
 * change the sound, not merely round-trip. A square oscillator's RMS depends on duty cycle (RMS =
 * A·√duty), so shifting the pulse width must change the rendered RMS. (Guards against a "parsed
 * into a field that goes nowhere" false fix.)
 */
public class PulseWidthParamTest {

  private static double renderRms(int osc1PhaseWidthQ31) {
    SynthTrackModel synth = new SynthTrackModel("SquarePW");
    synth.setOsc1Type("SQUARE");
    synth.setOsc2Type("NONE");
    if (osc1PhaseWidthQ31 != Integer.MIN_VALUE) synth.setOsc1PhaseWidthQ31(osc1PhaseWidthQ31);
    ClipModel clip = new ClipModel("c", 1, 16);
    clip.setStep(0, 0, StepData.of(true, 1.0f, 16.0f, 1.0f, 60));
    synth.addClip(clip);

    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);
    project.addTrack(synth);
    ProjectModel song = FirmwareFactory.createSong(project);
    FirmwareSound fs = (FirmwareSound) song.getTracks().get(0).getActiveClip().getSound();
    fs.triggerNote(60, 127);

    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.metronomeEnabled = false;
    engine.sounds.add(fs);
    double sumSq = 0;
    long n = 0;
    for (int b = 0; b < 200; b++) {
      engine.renderBlock(128);
      for (int i = 0; i < 128; i++) {
        double v = engine.masterBuffer[i].l / 2147483648.0;
        sumSq += v * v;
        n++;
      }
    }
    return Math.sqrt(sumSq / n);
  }

  @Test
  void pulseWidthChangesTheSound() {
    double neutral = renderRms(Integer.MIN_VALUE); // engine default (50% duty)
    double shifted = renderRms(0x50000000); // strongly off-centre duty
    System.out.printf("[PWM] neutralRMS=%.6f shiftedRMS=%.6f%n", neutral, shifted);
    assertTrue(neutral > 1e-4, "square synth produced no sound at neutral PW");
    assertTrue(shifted > 1e-4, "square synth produced no sound at shifted PW");
    // Duty change must move the RMS measurably — if the param were dropped, these would be equal.
    double relDiff = Math.abs(neutral - shifted) / neutral;
    assertTrue(
        relDiff > 0.05,
        "oscAPulseWidth did not change the sound (neutral="
            + neutral
            + " shifted="
            + shifted
            + ") — param not reaching the DSP");
  }
}
