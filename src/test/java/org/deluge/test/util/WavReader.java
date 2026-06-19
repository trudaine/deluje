package org.deluge.test.util;

import java.io.File;
import java.io.InputStream;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * Test-only robust WAV reader utilizing standard Java AudioSystem. Supports both 16-bit and 24-bit
 * PCM formats, returning floating-point audio frames normalized to [-1.0, 1.0].
 */
public class WavReader {

  public static class WavData {
    public final float[][] channels;
    public final int sampleRate;
    public final int bitsPerSample;

    public WavData(float[][] channels, int sampleRate, int bitsPerSample) {
      this.channels = channels;
      this.sampleRate = sampleRate;
      this.bitsPerSample = bitsPerSample;
    }

    public int frameCount() {
      return channels == null || channels.length == 0 ? 0 : channels[0].length;
    }
  }

  public static WavData read(File file) throws Exception {
    try (AudioInputStream ais = AudioSystem.getAudioInputStream(file)) {
      return readFromStream(ais);
    }
  }

  public static WavData read(InputStream stream) throws Exception {
    try (AudioInputStream ais = AudioSystem.getAudioInputStream(stream)) {
      return readFromStream(ais);
    }
  }

  private static WavData readFromStream(AudioInputStream ais) throws Exception {
    AudioFormat format = ais.getFormat();
    int sampleRate = (int) format.getSampleRate();
    int numChannels = format.getChannels();
    int bytesPerSample = format.getSampleSizeInBits() / 8;
    boolean bigEndian = format.isBigEndian();

    byte[] bytes = ais.readAllBytes();
    int totalSamples = bytes.length / (numChannels * bytesPerSample);

    float[][] channels = new float[numChannels][totalSamples];

    for (int f = 0; f < totalSamples; f++) {
      for (int c = 0; c < numChannels; c++) {
        int byteOffset = (f * numChannels + c) * bytesPerSample;
        long val = 0;
        if (bigEndian) {
          for (int b = 0; b < bytesPerSample; b++) {
            val = (val << 8) | (bytes[byteOffset + b] & 0xFF);
          }
        } else {
          for (int b = bytesPerSample - 1; b >= 0; b--) {
            val = (val << 8) | (bytes[byteOffset + b] & 0xFF);
          }
        }

        // Sign extend
        int shift = (4 - bytesPerSample) * 8;
        int intVal = ((int) val) << shift >> shift;

        float floatVal;
        if (bytesPerSample == 3) {
          floatVal = intVal / 8388607.0f; // 2^23 - 1
        } else if (bytesPerSample == 2) {
          floatVal = intVal / 32767.0f; // 2^15 - 1
        } else {
          floatVal = intVal / 127.0f;
        }

        // Clip
        if (floatVal > 1.0f) floatVal = 1.0f;
        else if (floatVal < -1.0f) floatVal = -1.0f;

        channels[c][f] = floatVal;
      }
    }

    return new WavData(channels, sampleRate, bytesPerSample * 8);
  }
}
