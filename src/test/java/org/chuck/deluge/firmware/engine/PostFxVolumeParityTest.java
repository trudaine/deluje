package org.chuck.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.fx.ModFXType;
import org.chuck.deluge.firmware.model.InstrumentClip;
import org.chuck.deluge.firmware.model.Song;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/** Ensures post-FX makeup gain from SRR/bitcrush reaches the final output stage. */
public class PostFxVolumeParityTest {

  private static FirmwareSound buildSynth() {
    SynthTrackModel model = new SynthTrackModel("postfx");
    model.setOsc1Type("SAW");
    model.setOsc2Type("NONE");
    model.setRetrigPhase(0);
    model.setMod1RetrigPhase(0);
    model.setMod2RetrigPhase(0);
    model.setOscMix(1.0f);
    model.setLpfFreq(20000f);
    model.setLpfRes(0.0f);
    model.setVolume(1.0f);
    model.addClip(new ClipModel("c", 8, 16));
    ProjectModel project = new ProjectModel();
    project.addTrack(model);
    Song song = FirmwareFactory.createSong(project);
    return (FirmwareSound) ((InstrumentClip) song.clips.get(0)).sound;
  }

  private static float[] renderInternalBlocks(
      FirmwareSound sound, int note, int velocity, int total) {
    float[] out = new float[total];
    StereoSample[] block = new StereoSample[128];
    for (int i = 0; i < 128; i++) block[i] = new StereoSample();
    sound.triggerNote(note, velocity);
    for (int off = 0; off < total; off += 128) {
      for (int i = 0; i < 128; i++) {
        block[i].l = 0;
        block[i].r = 0;
      }
      sound.renderInternal(block, 128, null);
      for (int i = 0; i < 128 && off + i < total; i++) {
        out[off + i] = block[i].l / 2147483648.0f;
      }
    }
    return out;
  }

  private static float[] renderOutputBlocks(
      FirmwareSound sound, int note, int velocity, int total) {
    float[] out = new float[total];
    StereoSample[] block = new StereoSample[128];
    for (int i = 0; i < 128; i++) block[i] = new StereoSample();
    sound.triggerNote(note, velocity);
    for (int off = 0; off < total; off += 128) {
      for (int i = 0; i < 128; i++) {
        block[i].l = 0;
        block[i].r = 0;
      }
      sound.renderOutput(block, 128, null);
      for (int i = 0; i < 128 && off + i < total; i++) {
        out[off + i] = block[i].l / 2147483648.0f;
      }
    }
    return out;
  }

  private static double rms(float[] samples, int from, int to) {
    double sum = 0;
    for (int i = from; i < to; i++) sum += (double) samples[i] * samples[i];
    return Math.sqrt(sum / Math.max(1, to - from));
  }

  @Test
  public void heavyBitcrushReducesFinalOutputViaPostFxVolume() {
    FirmwareSound internal = buildSynth();
    FirmwareSound finalOutput = buildSynth();
    internal.bitcrushParam = Integer.MAX_VALUE;
    finalOutput.bitcrushParam = Integer.MAX_VALUE;

    float[] internalWave = renderInternalBlocks(internal, 60, 110, 22050);
    float[] finalWave = renderOutputBlocks(finalOutput, 60, 110, 22050);

    double internalRms = rms(internalWave, 4096, 22050);
    double finalRms = rms(finalWave, 4096, 22050);
    assertTrue(internalRms > 0.01, "internal wet signal should be audible");
    assertTrue(
        finalRms < internalRms * 0.35,
        "final output should apply bitcrush post-FX attenuation (internal="
            + internalRms
            + ", final="
            + finalRms
            + ")");
  }

  @Test
  public void granularReducesFinalOutputViaPostFxVolume() {
    FirmwareSound internal = buildSynth();
    FirmwareSound finalOutput = buildSynth();
    internal.modFXType = ModFXType.GRAIN;
    finalOutput.modFXType = ModFXType.GRAIN;
    internal.modFXRateIncrement = 16777216;
    finalOutput.modFXRateIncrement = 16777216;
    internal.modFXDepth = 0x30000000;
    finalOutput.modFXDepth = 0x30000000;
    internal.modFXOffset = 0;
    finalOutput.modFXOffset = 0;
    internal.modFXFeedback = 0;
    finalOutput.modFXFeedback = 0;

    float[] internalWave = renderInternalBlocks(internal, 60, 110, 22050);
    float[] finalWave = renderOutputBlocks(finalOutput, 60, 110, 22050);

    double internalRms = rms(internalWave, 4096, 22050);
    double finalRms = rms(finalWave, 4096, 22050);
    assertTrue(internalRms > 0.005, "internal granular signal should be audible");
    // Master compressor adds gentle makeup gain; the attenuation is still present but less
    // pronounced at the final output.
    assertTrue(
        finalRms < internalRms * 0.98,
        "final output should apply granular post-FX attenuation (internal="
            + internalRms
            + ", final="
            + finalRms
            + ")");
  }
}
