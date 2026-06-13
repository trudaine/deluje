package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.chuck.deluge.firmware.engine.FirmwareAudioEngine;
import org.chuck.deluge.firmware.engine.FirmwareSound;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware2.Oscillator.OscType;
import org.junit.jupiter.api.Test;

public class SampleLateStartTest {

  @Test
  public void sampleStartsLateJumpingAhead() {
    // 1. Build a sample containing a ramp: data[i] = i
    int len = 2000;
    org.chuck.deluge.firmware.model.sample.Sample modelSample =
        new org.chuck.deluge.firmware.model.sample.Sample();
    modelSample.numChannels = 1;
    modelSample.byteDepth = 2;
    modelSample.sampleRate = 44100;
    modelSample.data = new float[len];
    for (int i = 0; i < len; i++) {
      // Put a linear ramp of amplitude, scaled to Q31 range inside modelSample
      modelSample.data[i] = i / 2000.0f;
    }

    // 2. Set up the sound with this sample
    FirmwareSound s = new FirmwareSound();
    s.oscTypes[0] = OscType.SAMPLE;
    s.samples[0] = modelSample;
    s.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    s.paramNeutralValues[Param.LOCAL_VOLUME] = Integer.MAX_VALUE;
    s.paramNeutralValues[Param.LOCAL_ENV_0_SUSTAIN] = Integer.MAX_VALUE;
    s.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;

    // Turn off filters
    s.lpfMode = org.chuck.deluge.firmware2.FilterSet.FilterMode.OFF;
    s.hpfMode = org.chuck.deluge.firmware2.FilterSet.FilterMode.OFF;

    // 3. Set up the engine and trigger the note late by 100 samples!
    FirmwareAudioEngine eng = new FirmwareAudioEngine();
    eng.sounds.add(s);

    // Render a block of silence to warm up the compressor gain ramp
    eng.renderBlock(128);

    // Trigger note late by 100 samples
    s.triggerNoteLate(36, 100, 100);

    // Render a block of 128 samples
    eng.renderBlock(128);

    // 4. Assert that the first output values correspond to frames 100, 101, 102... of the sample!
    float[] output = new float[128];
    for (int i = 0; i < 128; i++) {
      output[i] = eng.masterBuffer[i].l / 2147483648.0f;
    }

    assertTrue(Math.abs(output[0]) > 0.0f, "Should have rendered audio");
    assertTrue(output[1] > 0.0001f, "Should have jumped ahead past the start of the sample");
    assertTrue(output[10] > output[1], "Should be ramping up linearly");
  }
}
