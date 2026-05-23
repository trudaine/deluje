package org.chuck.deluge.reproduce;

import java.io.InputStream;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

public class PrintWavPitches {
  public static void main(String[] args) {
    try {
      System.out.println("=== ANALYZING FUNDAMENTAL PITCH OF RECORDED WAV FILES ===");
      String[] files = {
        "reference_detuned_saw_c5.wav",
        "reference_filter_mod_saw_c5.wav",
        "reference_pwm_square_c5.wav",
        "reference_fm_simple_c5.wav",
        "reference_dx7_vintage_c5.wav"
      };

      for (String file : files) {
        float[] samples = loadWav("/fidelity/" + file);

        // Find a safe active segment (middle of the file)
        int start = samples.length / 3;
        int len = 8820; // 200ms window
        float[] window = new float[len];
        System.arraycopy(samples, start, window, 0, len);

        double pitch =
            org.chuck.deluge.AudioAnalyzer.estimateFrequency(window, 44100, 80.0, 1000.0);
        int midiNote = (int) Math.round(12.0 * Math.log(pitch / 440.0) / Math.log(2.0) + 69.0);

        System.out.printf("  • %-30s | Pitch: %8.2f Hz | MIDI Note: %3d\n", file, pitch, midiNote);
      }
      System.out.println("=========================================================");
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private static float[] loadWav(String path) throws Exception {
    InputStream in = PrintWavPitches.class.getResourceAsStream(path);
    if (in == null) {
      throw new IllegalArgumentException("Resource not found: " + path);
    }
    AudioInputStream ais = AudioSystem.getAudioInputStream(in);
    AudioFormat format = ais.getFormat();
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
