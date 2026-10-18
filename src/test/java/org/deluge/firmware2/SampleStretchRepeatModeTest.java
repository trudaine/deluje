package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareSound;
import org.deluge.firmware2.Oscillator.OscType;
import org.junit.jupiter.api.Test;

/**
 * SampleRepeatMode::STRETCH (issue #33): the sample is pitch-stretched to fit the sequenced note's
 * length (voice_unison_part_source.cpp:166-180: phaseIncrement * sampleLength / noteLength).
 */
class SampleStretchRepeatModeTest {

  /** Playhead after rendering {@code blocks} 128-sample blocks of a note of {@code ticks}. */
  private static int playheadAfter(int loopMode, int ticks, int blocks) {
    int len = 44100;
    org.deluge.playback.Sample modelSample = new org.deluge.playback.Sample();
    modelSample.numChannels = 1;
    modelSample.byteDepth = 2;
    modelSample.sampleRate = 44100;
    modelSample.data = new float[len];
    for (int i = 0; i < len; i++) modelSample.data[i] = 0.5f;

    FirmwareSound s = new FirmwareSound();
    s.oscTypes[0] = OscType.SAMPLE;
    s.samples[0] = modelSample;
    s.sampleSettings[0].loopMode = loopMode;
    s.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    s.paramNeutralValues[Param.LOCAL_ENV_0_SUSTAIN] = Integer.MAX_VALUE;
    s.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    s.currentBpm = 120f; // 96 clip ticks per quarter = 22050 samples

    FirmwareAudioEngine eng = new FirmwareAudioEngine();
    eng.sounds.add(s);
    eng.renderBlock(128);
    s.syncParamsToFw2();
    s.fw2Sound.pendingSampleSyncLength = ticks;
    s.triggerNote(60, 100);
    s.fw2Sound.pendingSampleSyncLength = 0;
    for (int b = 0; b < blocks; b++) eng.renderBlock(128);
    var v = s.fw2Sound.voices.get(0);
    return v.unisonParts[0].sources[0].voiceSample.reader.playPos;
  }

  @Test
  void stretchPlaysTheSampleAcrossTheNoteLength() {
    int blocks = 50; // 6400 samples
    int cut = playheadAfter(Sound.REPEAT_CUT, 96, blocks);
    int stretched = playheadAfter(Sound.REPEAT_STRETCH, 96, blocks); // 1 s sample, 0.5 s note
    assertTrue(cut > 1000, "cut mode advanced: " + cut);
    // 44100-sample sample over a 22050-sample note: twice the speed of unsynced playback.
    assertEquals(2.0, (double) stretched / cut, 0.02, "cut=" + cut + " stretched=" + stretched);
    // A note as long as the sample plays at normal speed.
    int same = playheadAfter(Sound.REPEAT_STRETCH, 192, blocks);
    assertEquals(1.0, (double) same / cut, 0.02, "cut=" + cut + " same=" + same);
  }
}
