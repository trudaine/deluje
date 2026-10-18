package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link Sample#getLengthInMSec()} (sample.cpp:1077-1079) and {@link
 * SampleHolder#getMSecLimit(int)} (sample_holder_for_voice.cpp:130-142).
 */
class SampleHolderMSecLimitTest {

  @Test
  void getMSecLimit_stretchAlwaysReturns9999999() {
    SampleHolder holder = new SampleHolder();
    // Even with no sample loaded, STRETCH returns 9999999 (sample_holder_for_voice.cpp:131-132).
    assertEquals(9999999, holder.getMSecLimit(Sound.REPEAT_STRETCH));

    Sample s = new Sample();
    s.sampleRate = 44100;
    s.lengthInSamples = 44100;
    holder.audioFile = s;
    assertEquals(9999999, holder.getMSecLimit(Sound.REPEAT_STRETCH));
  }

  @Test
  void getMSecLimit_nonStretchReturnsZeroWhenNoAudioFile() {
    SampleHolder holder = new SampleHolder();
    assertEquals(0, holder.getMSecLimit(Sound.REPEAT_CUT));
    assertEquals(0, holder.getMSecLimit(Sound.REPEAT_ONCE));
    assertEquals(0, holder.getMSecLimit(Sound.REPEAT_LOOP));
  }

  @Test
  void getMSecLimit_nonStretchMatchesSampleGetLengthInMSec() {
    Sample s = new Sample();
    s.sampleRate = 44100;
    s.lengthInSamples = 44100; // (44100 - 1) * 1000 / 44100 + 1 = 999 + 1 = 1000 ms

    SampleHolder holder = new SampleHolder();
    holder.audioFile = s;

    assertEquals(1000, s.getLengthInMSec());
    assertEquals(1000, holder.getMSecLimit(Sound.REPEAT_CUT));
    assertEquals(1000, holder.getMSecLimit(Sound.REPEAT_ONCE));
    assertEquals(1000, holder.getMSecLimit(Sound.REPEAT_LOOP));

    // Exact boundary check: 1 sample -> (0 * 1000) / 44100 + 1 = 1 ms.
    s.lengthInSamples = 1;
    assertEquals(1, s.getLengthInMSec());

    // 44101 samples -> (44100 * 1000) / 44100 + 1 = 1001 ms.
    s.lengthInSamples = 44101;
    assertEquals(1001, s.getLengthInMSec());
  }
}
