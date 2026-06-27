package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.firmware2.StereoSample;
import org.junit.jupiter.api.Test;

/** Verifies the metronome click is gated by the enable flag and decays after ~1000 samples. */
public class FirmwareAudioEngineMetronomeTest {

  private static final int N = 128;
  private static final int DOWNBEAT_PHASE = 128411753;

  private static boolean anyNonZero(StereoSample[] buf) {
    for (int i = 0; i < N; i++) {
      if (buf[i].l != 0 || buf[i].r != 0) {
        return true;
      }
    }
    return false;
  }

  @Test
  public void disabledMetronomeIsSilent() {
    FirmwareAudioEngine e = new FirmwareAudioEngine();
    e.triggerMetronome(DOWNBEAT_PHASE); // gated off -> no-op
    e.renderBlock(N);
    assertFalse(anyNonZero(e.masterBuffer), "no click when metronome disabled");
  }

  @Test
  public void enabledMetronomeProducesAClick() {
    FirmwareAudioEngine e = new FirmwareAudioEngine();
    e.metronomeEnabled = true;
    e.triggerMetronome(DOWNBEAT_PHASE);
    e.renderBlock(N);
    assertTrue(anyNonZero(e.masterBuffer), "enabled + triggered produces a click");
  }

  @Test
  public void clickDecaysAfterAboutAThousandSamples() {
    FirmwareAudioEngine e = new FirmwareAudioEngine();
    e.dcBlockerEnabled = false;
    e.metronomeEnabled = true;
    e.triggerMetronome(DOWNBEAT_PHASE);
    // The one-shot click reaches true silence by ~block 9. The engaged master compressor sustains
    // the decaying tail a touch, but once the click envelope hits zero the silent input renders
    // silent output again — so render well past block 9 and assert true silence.
    for (int b = 0; b < 13; b++) {
      e.renderBlock(N);
    }
    assertFalse(anyNonZero(e.masterBuffer), "click is a one-shot that decays to silence");
  }
}
