package org.chuck.deluge.reproduce;

import java.io.File;
import org.chuck.audio.util.WavReader;
import org.chuck.audio.util.WavReader.WavData;

public class VerifyWavChannels {
  public static void main(String[] args) {
    String path = "deluge/src/test/resources/fidelity/reference_rec07.wav";
    try {
      System.out.println("=== ANALYZING RAW WAV CHANNELS ===");
      System.out.println("File: " + path);
      File file = new File(path);
      if (!file.exists()) {
        file = new File("src/test/resources/fidelity/reference_rec07.wav");
      }
      if (!file.exists()) {
        System.err.println("File not found!");
        return;
      }
      WavData data = WavReader.read(file);
      System.out.println("Sample Rate: " + data.sampleRate);
      System.out.println("Bit Depth:   " + data.bitsPerSample);
      System.out.println("Channels:    " + data.channels.length);
      System.out.println("Frames:      " + data.frameCount());

      float[][] ch = data.channels;

      // Find the first index where signal starts
      int startIdx = -1;
      for (int i = 0; i < data.frameCount(); i++) {
        if (Math.abs(ch[0][i]) > 0.02f || Math.abs(ch[1][i]) > 0.02f) {
          startIdx = i;
          break;
        }
      }
      System.out.println("Active Onset Start Index: " + startIdx);

      if (startIdx == -1) {
        System.out.println("No active signal detected!");
        return;
      }

      System.out.println("\n--- First 20 Active Samples (Channel 0 vs Channel 1) ---");
      System.out.printf(
          "%-10s | %-12s | %-12s | %-12s\n", "Index", "Channel 0 (L)", "Channel 1 (R)", "Mono Sum");
      System.out.println("------------------------------------------------------------");
      for (int i = startIdx; i < startIdx + 20; i++) {
        float l = ch[0][i];
        float r = ch[1][i];
        float sum = (l + r) * 0.5f;
        System.out.printf("%-10d | %-12.6f | %-12.6f | %-12.6f\n", i, l, r, sum);
      }

      // Calculate individual channel RMS and correlation between channels
      double sumLSq = 0, sumRSq = 0, sumLR = 0;
      int activeFrames = data.frameCount() - startIdx;
      for (int i = startIdx; i < data.frameCount(); i++) {
        float l = ch[0][i];
        float r = ch[1][i];
        sumLSq += l * l;
        sumRSq += r * r;
        sumLR += l * r;
      }
      double rmsL = Math.sqrt(sumLSq / activeFrames);
      double rmsR = Math.sqrt(sumRSq / activeFrames);
      double channelCorr = sumLR / Math.sqrt(sumLSq * sumRSq + 1e-15);

      System.out.println("\n--- Global Active Metrics ---");
      System.out.printf("  Channel 0 (Left) RMS:  %.6f\n", rmsL);
      System.out.printf("  Channel 1 (Right) RMS: %.6f\n", rmsR);
      System.out.printf(
          "  Inter-Channel Correlation: %.6f (1.0 = Mono, -1.0 = Out of Phase)\n", channelCorr);

    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
