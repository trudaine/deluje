package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Toggling pitch/speed independence while playing continues from the current position, as the C
 * starts/ends time stretching in place (voice_sample.cpp:528-575) rather than rewinding.
 */
class AudioOutputStretchToggleTest {

  @Test
  void toggleWhilePlayingKeepsThePlayhead() {
    Functions.resetNoiseSeed();
    int n = 44100;
    Sample s = new Sample();
    s.numChannels = 1;
    s.byteDepth = 4;
    s.sampleRate = 44100;
    s.lengthInSamples = n;
    s.audioDataLengthBytes = (long) n * 4;
    s.data = new int[n];
    for (int i = 0; i < n; i++) {
      s.data[i] = (i % 100) * 1_000_000;
    }

    AudioOutput ao = new AudioOutput();
    ao.setClip(s, true);
    ao.setAmplitude(1 << 27);
    ao.setPlayback(1.0f, false);
    ao.onTransportStart();
    int[] buf = new int[256];
    int[] rev = new int[128];
    for (int b = 0; b < 40; b++) {
      ao.renderInternal(buf, 128, rev);
    }
    int before = ao.voiceSample.reader.playPos;
    assertTrue(before > 4000, "played some frames first: " + before);

    ao.setPlayback(1.0f, true); // enable time stretch mid-playback
    int after = ao.voiceSample.timeStretcher.getSamplePos(1);
    assertTrue(Math.abs(after - before) < 128, "rewound to " + after + " from " + before);
  }
}
