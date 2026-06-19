package org.deluge;

import java.io.File;
import org.deluge.firmware.model.sample.Sample;
import org.deluge.firmware.storage.audio.AudioFileReader;

/**
 * Utility to compare real hardware recordings with ChucK-Java rendered waveforms. Performs time
 * alignment via normalized cross-correlation and computes fidelity metrics.
 */
public class FidelityComparisonTool {

  public static class ComparisonReport {
    public double rmsRendered;
    public double rmsRecorded;
    public double peakCorrelation;
    public int bestLagSamples;
    public double bestLagMs;
    public double rmse;
    public double meanAbsoluteError;
  }

  public static ComparisonReport compare(File renderedFile, File recordedFile) throws Exception {
    Sample rendered = AudioFileReader.readSample(renderedFile.getAbsolutePath());
    Sample recorded = AudioFileReader.readSample(recordedFile.getAbsolutePath());

    if (rendered == null || recorded == null) {
      throw new IllegalArgumentException("Failed to read one or both wave files.");
    }

    float[] x = rendered.data; // Rendered
    float[] y = recorded.data; // Recorded

    // Assume mono comparison (left channel) for simplicity, or sum/average if stereo
    int chX = rendered.numChannels;
    int chY = recorded.numChannels;

    float[] xMono = toMono(x, chX);
    float[] yMono = toMono(y, chY);

    // 1. Time Alignment via Cross-Correlation
    int searchRange = Math.min(xMono.length / 2, 44100 * 2); // Search up to 2 seconds lag
    double maxCorrSum = -Double.MAX_VALUE;
    int bestLag = 0;

    // Normalize inputs for correlation
    double sumX2 = 0;
    for (float val : xMono) sumX2 += val * val;
    double rmsX = Math.sqrt(sumX2 / xMono.length);

    System.out.println(
        "[FidelityComparisonTool] Rendered samples: " + xMono.length + " (RMS: " + rmsX + ")");
    System.out.println("[FidelityComparisonTool] Recorded samples: " + yMono.length);

    // Compute cross-correlation sum over lag range
    for (int lag = -searchRange; lag < searchRange; lag += 10) { // Coarse search first
      double sum = 0;
      int count = 0;
      for (int i = 0; i < xMono.length; i += 10) {
        int yIdx = i + lag;
        if (yIdx >= 0 && yIdx < yMono.length) {
          sum += xMono[i] * yMono[yIdx];
          count++;
        }
      }
      if (count > 0 && sum > maxCorrSum) {
        maxCorrSum = sum;
        bestLag = lag;
      }
    }

    // Fine search around best coarse lag
    maxCorrSum = -Double.MAX_VALUE;
    int startFine = Math.max(-searchRange, bestLag - 15);
    int endFine = Math.min(searchRange, bestLag + 15);
    for (int lag = startFine; lag <= endFine; lag++) {
      double sum = 0;
      for (int i = 0; i < xMono.length; i++) {
        int yIdx = i + bestLag;
        if (yIdx >= 0 && yIdx < yMono.length) {
          sum += xMono[i] * yMono[yIdx];
        }
      }
      if (sum > maxCorrSum) {
        maxCorrSum = sum;
        bestLag = lag;
      }
    }

    // 2. Compute Parity Metrics on aligned signals
    double alignedSumX2 = 0;
    double alignedSumY2 = 0;
    double alignedSumXY = 0;
    double sumSquareError = 0;
    double sumAbsError = 0;
    int alignedCount = 0;

    for (int i = 0; i < xMono.length; i++) {
      int yIdx = i + bestLag;
      if (yIdx >= 0 && yIdx < yMono.length) {
        float valX = xMono[i];
        float valY = yMono[yIdx];

        alignedSumX2 += valX * valX;
        alignedSumY2 += valY * valY;
        alignedSumXY += valX * valY;

        double err = valX - valY;
        sumSquareError += err * err;
        sumAbsError += Math.abs(err);
        alignedCount++;
      }
    }

    ComparisonReport report = new ComparisonReport();
    report.rmsRendered = Math.sqrt(alignedSumX2 / alignedCount);
    report.rmsRecorded = Math.sqrt(alignedSumY2 / alignedCount);
    report.bestLagSamples = bestLag;
    report.bestLagMs = (bestLag * 1000.0) / 44100.0;
    report.rmse = Math.sqrt(sumSquareError / alignedCount);
    report.meanAbsoluteError = sumAbsError / alignedCount;

    if (alignedSumX2 > 0 && alignedSumY2 > 0) {
      report.peakCorrelation = alignedSumXY / Math.sqrt(alignedSumX2 * alignedSumY2);
    } else {
      report.peakCorrelation = 0;
    }

    return report;
  }

  private static float[] toMono(float[] data, int channels) {
    if (channels == 1) return data;
    float[] mono = new float[data.length / channels];
    for (int i = 0; i < mono.length; i++) {
      float sum = 0;
      for (int c = 0; c < channels; c++) {
        sum += data[i * channels + c];
      }
      mono[i] = sum / channels;
    }
    return mono;
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.out.println(
          "Usage: java org.deluge.FidelityComparisonTool <rendered.wav> <recorded.wav>");
      return;
    }
    File ref = new File(args[0]);
    File rec = new File(args[1]);
    System.out.println("Comparing Reference: " + ref.getAbsolutePath());
    System.out.println("Recording: " + rec.getAbsolutePath());

    ComparisonReport report = compare(ref, rec);
    System.out.println("\n=== HARDWARE FIDELITY COMPARISON REPORT ===");
    System.out.println(
        "  Time Alignment Lag: "
            + report.bestLagSamples
            + " samples ("
            + String.format("%.2f", report.bestLagMs)
            + " ms)");
    System.out.println("  RMS Rendered:       " + String.format("%.6f", report.rmsRendered));
    System.out.println("  RMS Recorded:       " + String.format("%.6f", report.rmsRecorded));
    System.out.println(
        "  Peak Correlation:   " + String.format("%.2f%%", report.peakCorrelation * 100.0));
    System.out.println("  Root Mean Sq Error: " + String.format("%.6f", report.rmse));
    System.out.println("  Mean Absolute Error: " + String.format("%.6f", report.meanAbsoluteError));
    System.out.println("===========================================");
  }
}
