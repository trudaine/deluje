package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** C sound.cpp:2170-2244 — Sound::reassessRenderSkippingStatus. */
class RenderSkipReassessTest {

  /** Blocks rendered (128 samples each) until the sound asks to stop rendering. */
  private static int blocksUntilSkip(Sound s, int maxBlocks) {
    for (int b = 0; b < maxBlocks; b++) {
      if (!s.reassessRenderSkippingStatus(128)) {
        return b;
      }
    }
    return maxBlocks;
  }

  @Test
  void idleSoundWithoutModFxOrCompressorSkipsAtOnce() {
    Sound s = new Sound();
    s.modFXType = ModFx.ModFXType.NONE;
    s.compressor.thresholdKnobPos = 0;
    assertFalse(s.reassessRenderSkippingStatus(128));
  }

  @Test
  void flangerTailKeepsRenderingForNinetyFourHundredFortyOneSamples() {
    Sound s = new Sound();
    s.skippingRendering = false; // was rendering a note that just ended
    s.modFXType = ModFx.ModFXType.FLANGER;
    s.compressor.thresholdKnobPos = 0;
    s.compressor.releaseMS = 0;
    // C :2207-2208 — default modFX wait 90 * 441 samples, checked per block.
    int blocks = blocksUntilSkip(s, 1000);
    assertTrue(blocks * 128 >= 90 * 441, "flanger tail cut short: " + blocks + " blocks");
    assertTrue(blocks * 128 < 90 * 441 + 2 * 128, "flanger tail too long: " + blocks + " blocks");
  }

  @Test
  void chorusTailIsShort() {
    Sound s = new Sound();
    s.skippingRendering = false;
    s.modFXType = ModFx.ModFXType.CHORUS;
    s.compressor.thresholdKnobPos = 0;
    s.compressor.releaseMS = 0;
    int blocks = blocksUntilSkip(s, 1000);
    assertTrue(blocks * 128 >= 20 * 44 && blocks * 128 < 20 * 44 + 2 * 128, "blocks=" + blocks);
  }

  @Test
  void compressorReleaseExtendsTheTail() {
    Sound s = new Sound();
    s.skippingRendering = false;
    s.modFXType = ModFx.ModFXType.NONE;
    s.compressor.thresholdKnobPos = 1 << 30; // compressor on
    // C :2199-2210 — modFX NONE still takes the switch default (90 * 441), so the wait is
    // max(39690, releaseMS * 44): a 1000 ms release (44000 samples) is the longer one.
    s.compressor.releaseMS = 1000f;
    int blocks = blocksUntilSkip(s, 1000);
    assertTrue(blocks * 128 >= 1000 * 44 && blocks * 128 < 1000 * 44 + 2 * 128, "blocks=" + blocks);
  }

  @Test
  void startablePendingNoteKeepsRendering() {
    // C :2176-2181 (issue #2262) — a note waiting for a voice is only started from render().
    Sound s = new Sound();
    s.modFXType = ModFx.ModFXType.NONE;
    s.arpSettings.mode = Arpeggiator.ArpMode.OFF;
    Arpeggiator.ArpNote pending = new Arpeggiator.ArpNote();
    pending.noteStatus[0] = Arpeggiator.ArpNoteStatus.PENDING;
    pending.noteCodeOnPostArp[0] = 60;
    s.arpeggiator.notes.add(pending);
    s.arpeggiator.anyPending = true;
    assertTrue(s.reassessRenderSkippingStatus(128));
  }
}
