package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.engine.FirmwareSound;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test: sample playback through the fw2 engine. Creates a synthetic sample, attaches it
 * to a FirmwareSound, triggers a note, and verifies audible output. Proves the sample bridge works
 * without needing WAV files on disk.
 */
class SampleAudioTest {

  @Test
  void syntheticSampleProducesAudio() {
    // 1. Build a synthetic "sample" — a 440Hz sine tone, 441 samples (10ms)
    int nFrames = 441;
    org.chuck.deluge.firmware.model.sample.Sample modelSample =
        new org.chuck.deluge.firmware.model.sample.Sample();
    modelSample.numChannels = 1;
    modelSample.byteDepth = 3;
    modelSample.sampleRate = 44100;
    modelSample.data = new float[nFrames];
    for (int i = 0; i < nFrames; i++) {
      modelSample.data[i] = (float) Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0) * 0.5f;
    }

    // 2. Set up a FirmwareSound with the sample as source 0
    FirmwareSound sound = new FirmwareSound();
    sound.oscTypes[0] = org.chuck.deluge.firmware.dsp.oscillators.OscType.SAMPLE;
    sound.samples[0] = modelSample;
    // useFirmware2 removed - always true

    // 3. Trigger a voice
    sound.triggerNote(60, 100);

    // 4. Render a few blocks
    int N = 128;
    StereoSample[] buf = new StereoSample[N];
    for (int i = 0; i < N; i++) buf[i] = new StereoSample();
    long energy = 0;
    for (int blk = 0; blk < 8; blk++) {
      sound.renderOutput(buf, N, null);
      for (int i = 0; i < N; i++) {
        energy += Math.abs((long) buf[i].l) + Math.abs((long) buf[i].r);
        buf[i].l = buf[i].r = 0; // reset for next block
      }
    }

    assertTrue(
        energy > 0, "Sample-based voice should produce audio output (energy=" + energy + ")");
  }

  /** Time-stretch sample playback: pitched read rate decoupled from duration advance. */
  @Test
  void timeStretchedSampleProducesAudio() {
    int nFrames = 2048;
    org.chuck.deluge.firmware.model.sample.Sample modelSample =
        new org.chuck.deluge.firmware.model.sample.Sample();
    modelSample.numChannels = 1;
    modelSample.byteDepth = 3;
    modelSample.sampleRate = 44100;
    modelSample.data = new float[nFrames];
    for (int i = 0; i < nFrames; i++) {
      modelSample.data[i] = (float) Math.sin(2.0 * Math.PI * 220.0 * i / 44100.0) * 0.4f;
    }

    FirmwareSound sound = new FirmwareSound();
    sound.oscTypes[0] = org.chuck.deluge.firmware.dsp.oscillators.OscType.SAMPLE;
    sound.samples[0] = modelSample;
    sound.sampleSettings[0].timestretch = true; // enable time-stretch
    // useFirmware2 removed - always true

    sound.triggerNote(60, 100);

    int N = 128;
    StereoSample[] buf = new StereoSample[N];
    for (int i = 0; i < N; i++) buf[i] = new StereoSample();
    long energy = 0;
    for (int blk = 0; blk < 16; blk++) {
      sound.renderOutput(buf, N, null);
      for (int i = 0; i < N; i++) {
        energy += Math.abs((long) buf[i].l) + Math.abs((long) buf[i].r);
        buf[i].l = buf[i].r = 0;
      }
    }

    assertTrue(
        energy > 0, "Time-stretched sample should produce audio output (energy=" + energy + ")");
  }
}
