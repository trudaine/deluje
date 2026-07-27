package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * Dedicated portable unit test for high-resonance 24dB ladder filters and chorus phase alignment (§4.2triginties).
 * Verifies that high-resonance presets (065 Cello, 132 Organ Strings, 104 Alien Vomit, 042 High Triangle,
 * 047 Basic Dirty Bass) and free-running chorus presets (083 Dark Chorus) render cleanly across multi-block buffers
 * without Q31 integer overflow, clipping, or NaN generation, guarding 24dB ladder stability and phase alignment.
 */
public class HighResonanceAndChorusParityTest {

  private static final String HOME = System.getProperty("user.home");
  private static final String CARD_NAME =
      System.getProperty("deluge.card", new File(HOME + "/ludocard").isDirectory() ? "ludocard" : "deluge-card");
  private static final File SYNTH_DIR = new File(HOME + "/" + CARD_NAME + "/SYNTHS");

  private float[] renderPresetAudio(String xmlName, int note, int modFxPhaseOffset, int numSamples) throws Exception {
    File xml = new File(SYNTH_DIR, xmlName);
    if (!xml.exists()) {
      return new float[0];
    }
    SynthTrackModel synth = DelugeXmlParser.parseSynth(new FileInputStream(xml), xml.getName());
    synth.setName(xml.getName().replace(".XML", ""));

    ClipModel clip = new ClipModel("c", 1, 16);
    clip.setStep(0, 0, StepData.of(true, 1.0f, 16.0f, 1.0f, note));
    synth.addClip(clip);

    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);
    project.addTrack(synth);

    ProjectModel song = FirmwareFactory.createSong(project);
    FirmwareSound fs = (FirmwareSound) song.getTracks().get(0).getActiveClip().getSound();
    fs.triggerNote(note, 127);

    if (modFxPhaseOffset != 0) {
      fs.fw2Sound.modFX.getModFXLFO().phase += modFxPhaseOffset;
      fs.fw2Sound.modFX.getModFXLFOStereo().phase += modFxPhaseOffset;
    }

    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.metronomeEnabled = false;
    engine.syncMasterEffects(project);
    engine.sounds.add(fs);

    float[] out = new float[numSamples];
    int got = 0;
    while (got < numSamples) {
      engine.renderBlock(128);
      for (int i = 0; i < 128 && got < numSamples; i++) {
        out[got++] = (float) (engine.masterBuffer[i].l / 2.147483648e9);
      }
    }
    return out;
  }

  @Test
  public void testHighResonance24DbLadderStability() throws Exception {
    String[] presets = {"065 Cello.XML", "132 Organ Strings.XML", "104 Alien Vomit.XML"};
    for (String preset : presets) {
      float[] out = renderPresetAudio(preset, 60, 0, 44100);
      if (out.length == 0) continue;

      double rms = 0.0;
      for (float f : out) {
        assertFalse(Float.isNaN(f) || Float.isInfinite(f), preset + " sample must not be NaN or Infinite");
        assertTrue(Math.abs(f) <= 2.0f, preset + " sample must remain bounded across high filter resonance");
        rms += f * (double) f;
      }
      rms = Math.sqrt(rms / out.length);
      assertTrue(rms > 0.02, preset + " must produce audible output through 24dB ladder filter");
    }
  }

  @Test
  public void testDarkChorusPhaseSweptAlignment() throws Exception {
    int[] phaseOffsets = {0, 1 << 30, 1 << 31, (1 << 30) * 3}; // 0, 90, 180, 270 deg
    double baseRms = -1.0;

    for (int offset : phaseOffsets) {
      float[] out = renderPresetAudio("083 Dark Chorus.XML", 60, offset, 44100);
      if (out.length == 0) return;

      double rms = 0.0;
      for (float f : out) {
        assertFalse(Float.isNaN(f) || Float.isInfinite(f), "083 Dark Chorus sample must be valid");
        assertTrue(Math.abs(f) <= 2.0f, "083 Dark Chorus sample must remain bounded");
        rms += f * (double) f;
      }
      rms = Math.sqrt(rms / out.length);
      assertTrue(rms > 0.01, "083 Dark Chorus must produce audible output at all starting phases");

      if (baseRms < 0) {
        baseRms = rms;
      } else {
        assertEquals(baseRms, rms, baseRms * 0.05, "Total RMS energy must remain stable across all 4 grid phase offsets");
      }
    }
  }

  @Test
  public void testHighTriangleAndDirtyBassParity() throws Exception {
    String[] presets = {"042 High Triangle.XML", "047 Basic Dirty Bass.XML"};
    for (String preset : presets) {
      float[] out = renderPresetAudio(preset, 48, 0, 44100);
      if (out.length == 0) continue;

      double rms = 0.0;
      for (float f : out) {
        assertFalse(Float.isNaN(f) || Float.isInfinite(f), preset + " sample must be valid");
        assertTrue(Math.abs(f) <= 2.0f, preset + " sample must remain bounded");
        rms += f * (double) f;
      }
      rms = Math.sqrt(rms / out.length);
      assertTrue(rms > 0.05, preset + " must produce strong bass/triangle audio");
    }
  }
}
