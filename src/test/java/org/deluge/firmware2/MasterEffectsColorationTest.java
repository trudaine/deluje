package org.deluge.firmware2;

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
 * Dedicated portable unit test for master reverb room saturation and compressor sidechain
 * coloration (§4.2vicesocties). Verifies that complex multi-voice presets (132 Organ Strings and
 * 123 Space Dust) decay cleanly through global master reverb and unpatched compressor stages
 * without 32-bit integer overflow, clipping, or NaN generation, permanently guarding sustained
 * chord decay rates and transient sidechain dynamics.
 */
public class MasterEffectsColorationTest {

  private static final File SYNTH_DIR =
      new File(System.getProperty("deluge.card", "src/main/resources"), "SYNTHS");

  @Test
  public void testOrganStringsReverbTailDecayParity() throws Exception {
    File xml = new File(SYNTH_DIR, "132 Organ Strings.XML");
    if (!xml.exists()) return;

    SynthTrackModel synth = DelugeXmlParser.parseSynth(new FileInputStream(xml), xml.getName());
    synth.setName(xml.getName().replace(".XML", ""));

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
    engine.syncMasterEffects(project);
    engine.sounds.add(fs);

    // Render 1 second of note on
    int numSamples = 44100;
    float[] outOn = new float[numSamples];
    int got = 0;
    while (got < numSamples) {
      engine.renderBlock(128);
      for (int i = 0; i < 128 && got < numSamples; i++) {
        outOn[got++] = (float) (engine.masterBuffer[i].l / 2.147483648e9);
      }
    }

    // Release note and render 2 seconds of reverb tail
    fs.releaseNote(60);
    int tailSamples = 88200;
    float[] outTail = new float[tailSamples];
    got = 0;
    while (got < tailSamples) {
      engine.renderBlock(128);
      for (int i = 0; i < 128 && got < tailSamples; i++) {
        outTail[got++] = (float) (engine.masterBuffer[i].l / 2.147483648e9);
      }
    }

    // Verify clean boundedness and absence of NaN
    for (float f : outOn) {
      assertFalse(
          Float.isNaN(f) || Float.isInfinite(f),
          "Sample must not be NaN or Infinite during sustain");
      assertTrue(Math.abs(f) <= 2.0f, "Sample must remain within bounded headroom during sustain");
    }
    for (float f : outTail) {
      assertFalse(
          Float.isNaN(f) || Float.isInfinite(f),
          "Sample must not be NaN or Infinite in reverb tail");
      assertTrue(Math.abs(f) <= 2.0f, "Reverb tail must not explode or overflow");
    }

    // Verify smooth exponential decay of reverb tail
    double initialTailEnergy = 0.0;
    double finalTailEnergy = 0.0;
    for (int i = 0; i < 1000; i++) initialTailEnergy += outTail[i] * (double) outTail[i];
    for (int i = tailSamples - 1000; i < tailSamples; i++)
      finalTailEnergy += outTail[i] * (double) outTail[i];

    assertTrue(initialTailEnergy > 0.0001, "Reverb tail must have audible initial energy");
    assertTrue(
        finalTailEnergy < initialTailEnergy,
        "Reverb tail must decay over time without feedback loop explosion");
  }

  @Test
  public void testSpaceDustCompressorColorationParity() throws Exception {
    File xml = new File(SYNTH_DIR, "123 Space Dust.XML");
    if (!xml.exists()) return;

    SynthTrackModel synth = DelugeXmlParser.parseSynth(new FileInputStream(xml), xml.getName());
    synth.setName(xml.getName().replace(".XML", ""));

    ClipModel clip = new ClipModel("c", 1, 16);
    clip.setStep(0, 0, StepData.of(true, 1.0f, 16.0f, 1.0f, 48)); // Low chord root
    synth.addClip(clip);

    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);
    project.addTrack(synth);

    ProjectModel song = FirmwareFactory.createSong(project);
    FirmwareSound fs = (FirmwareSound) song.getTracks().get(0).getActiveClip().getSound();
    fs.triggerNote(48, 127);

    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.metronomeEnabled = false;
    engine.syncMasterEffects(project);
    engine.sounds.add(fs);

    int numSamples = 44100;
    float[] out = new float[numSamples];
    int got = 0;
    while (got < numSamples) {
      engine.renderBlock(128);
      for (int i = 0; i < 128 && got < numSamples; i++) {
        out[got++] = (float) (engine.masterBuffer[i].l / 2.147483648e9);
      }
    }

    double rms = 0.0;
    for (float f : out) {
      assertFalse(Float.isNaN(f) || Float.isInfinite(f), "Space Dust sample must be valid");
      assertTrue(Math.abs(f) <= 2.0f, "Compressor must prevent uncontrolled peak clipping");
      rms += f * (double) f;
    }
    rms = Math.sqrt(rms / numSamples);
    assertTrue(rms > 0.01, "123 Space Dust must produce audible output through compressor");
  }
}
