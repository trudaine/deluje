package org.deluge.reproduce;

import java.io.InputStream;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

public class DumpWindowSamples {
  public static void main(String[] args) {
    try {
      System.out.println("=== SCANNING TRUE INITIAL TRIGGER SAMPLES OF REFERENCE FILES ===");
      String[] files = {
        "reference_detuned_saw_c5.wav",
        "reference_filter_mod_saw_c5.wav",
        "reference_pwm_square_c5.wav",
        "reference_fm_simple_c5.wav",
        "reference_dx7_vintage_c5.wav"
      };

      for (String file : files) {
        float[] samples = loadWav("/fidelity/" + file);
        float[] norm = normalizePeak(samples, 0.5f);

        int trueHwStart = 0;
        for (int i = 0; i < norm.length; i++) {
          if (Math.abs(norm[i]) > 0.01f) {
            trueHwStart = i;
            break;
          }
        }
        System.out.printf(
            "  [DIAG] First sample above 0.01f in Hardware: %d (approx block: %.2f)\n",
            trueHwStart, trueHwStart / 128.0);

        int peakIdx = 0;
        float maxVal = 0.0f;
        for (int i = 0; i < samples.length; i++) {
          if (Math.abs(samples[i]) > maxVal) {
            maxVal = Math.abs(samples[i]);
            peakIdx = i;
          }
        }
        System.out.printf(
            "  [DIAG] Absolute max peak of %.4f at index: %d (time: %.2f ms)\n",
            maxVal, peakIdx, peakIdx / 44.1);
      }
      System.out.println("================================================================");
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private static float[] normalizePeak(float[] data, float targetPeak) {
    double max = 0;
    for (float v : data) if (Math.abs(v) > max) max = Math.abs(v);
    if (max < 1e-9) return data.clone();
    float scale = (float) (targetPeak / max);
    float[] out = new float[data.length];
    for (int i = 0; i < data.length; i++) out[i] = data[i] * scale;
    return out;
  }

  private static double peak(float[] data) {
    double p = 0;
    for (float v : data) if (Math.abs(v) > p) p = Math.abs(v);
    return p;
  }

  private static float[] loadWav(String path) throws Exception {
    InputStream in = DumpWindowSamples.class.getResourceAsStream(path);
    if (in == null) {
      throw new IllegalArgumentException("Resource not found: " + path);
    }
    AudioInputStream ais = AudioSystem.getAudioInputStream(in);
    javax.sound.sampled.AudioFormat format = ais.getFormat();
    int bits = format.getSampleSizeInBits();
    int frameSize = format.getFrameSize();
    int totalFrames = (int) ais.getFrameLength();
    byte[] bytes = ais.readAllBytes();

    float[] samples = new float[totalFrames];
    for (int i = 0; i < totalFrames; i++) {
      int byteIdx = i * frameSize;
      if (bits == 24) {
        int b0 = bytes[byteIdx] & 0xFF;
        int b1 = bytes[byteIdx + 1] & 0xFF;
        int b2 = bytes[byteIdx + 2];
        int val = (b0) | (b1 << 8) | (b2 << 16);
        samples[i] = val / 8388608.0f;
      } else if (bits == 16) {
        int b0 = bytes[byteIdx] & 0xFF;
        int b1 = bytes[byteIdx + 1];
        short val = (short) (b0 | (b1 << 8));
        samples[i] = val / 32768.0f;
      }
    }
    return samples;
  }
}
