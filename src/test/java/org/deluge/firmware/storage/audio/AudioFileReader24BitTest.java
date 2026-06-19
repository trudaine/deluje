package org.deluge.firmware.storage.audio;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import org.deluge.firmware.model.sample.Sample;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the porting gap where {@link AudioFileReader} only decoded 16-bit and 8-bit
 * PCM, so 24-bit (and 32-bit) WAVs loaded as silence.
 */
public class AudioFileReader24BitTest {

  /** Write a minimal mono PCM WAV with the given bit depth from full-scale-ish int samples. */
  private static Path writeWav(int bitsPerSample, int[] samples) throws IOException {
    int byteDepth = bitsPerSample / 8;
    int dataLen = samples.length * byteDepth;
    ByteBuffer bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
    bb.put("RIFF".getBytes()).putInt(36 + dataLen).put("WAVE".getBytes());
    bb.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1); // PCM, mono
    bb.putInt(44100).putInt(44100 * byteDepth).putShort((short) byteDepth);
    bb.putShort((short) bitsPerSample);
    bb.put("data".getBytes()).putInt(dataLen);
    for (int s : samples) {
      if (byteDepth == 3) {
        bb.put((byte) (s & 0xFF)).put((byte) ((s >> 8) & 0xFF)).put((byte) ((s >> 16) & 0xFF));
      } else if (byteDepth == 2) {
        bb.putShort((short) s);
      }
    }
    Path p = Files.createTempFile("dx7test24", ".wav");
    Files.write(p, bb.array());
    p.toFile().deleteOnExit();
    return p;
  }

  @Test
  public void decodes24BitPcmNonSilentAndCorrect() throws Exception {
    // +half scale, -half scale, ~full scale (24-bit signed range +/-8388608)
    int[] samples = {4194304, -4194304, 8388607, -8388608};
    Path wav = writeWav(24, samples);

    Sample sample = AudioFileReader.readSample(wav.toString());
    assertNotNull(sample);
    assertEquals(3, sample.byteDepth);
    assertNotNull(sample.data, "24-bit data must be decoded");
    assertEquals(samples.length, sample.data.length);

    // Not silent.
    float peak = 0;
    for (float v : sample.data) peak = Math.max(peak, Math.abs(v));
    assertTrue(peak > 0.4f, "24-bit sample must not be silent (peak=" + peak + ")");

    // Values correct (normalized by 2^23 = 8388608), within one LSB.
    float tol = 2.0f / 8388608.0f;
    assertEquals(0.5f, sample.data[0], tol);
    assertEquals(-0.5f, sample.data[1], tol);
    assertEquals(8388607f / 8388608f, sample.data[2], tol);
    assertEquals(-1.0f, sample.data[3], tol);
  }
}
