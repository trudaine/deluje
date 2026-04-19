package org.chuck.deluge.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.chuck.deluge.ui.browser.WavPeakDecoder;
import org.junit.jupiter.api.Test;

public class WavPeakDecoderTest {

  @Test
  void testDecodeValidWav() throws Exception {
    File wav = new File("src/main/resources/examples/data/kick.wav");
    assertTrue(wav.exists(), "Test kick.wav not found");

    WavPeakDecoder.WavInfo info = WavPeakDecoder.decode(wav, 100);

    assertEquals(44100, info.sampleRate);
    assertEquals(1, info.numChannels);
    assertTrue(info.getDurationMs() > 0);
    assertEquals(100, info.peaks.length);

    // Check that we got actual peak data
    boolean hasAudio = false;
    for (float p : info.peaks) {
      if (p > 0.01f) {
        hasAudio = true;
        break;
      }
    }
    assertTrue(hasAudio, "Peak array should contain audio data");
  }
}
