package org.deluge;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import org.deluge.FidelityComparisonTool.ComparisonReport;

public class StemsFidelityComparison {

  public static void main(String[] args) {
    System.out.println("=== BATCh STEMS FIDELITY COMPARISON ===");
    String dirPath =
        args.length > 0
            ? args[0]
            : System.getProperty("user.home") + "/Downloads/BillieJean_Stems_Analysis";
    File dir = new File(dirPath);
    if (!dir.exists() || !dir.isDirectory()) {
      System.err.println("Stems directory not found: " + dir.getAbsolutePath());
      System.exit(1);
    }

    List<File> renderedStems = new ArrayList<>();
    for (File f : dir.listFiles()) {
      if (f.getName().startsWith("Track_") && f.getName().endsWith("_stem.wav")) {
        renderedStems.add(f);
      }
    }

    // Sort by track number for cleaner output
    renderedStems.sort(
        (a, b) -> {
          int numA = extractTrackNumber(a.getName());
          int numB = extractTrackNumber(b.getName());
          return Integer.compare(numA, numB);
        });

    System.out.println("Found " + renderedStems.size() + " rendered stems to analyze.");

    File reportFile = new File("target/stems_fidelity_report.md");
    try (PrintWriter pw = new PrintWriter(new FileWriter(reportFile))) {
      pw.println("# 🎙️ Stem-by-Stem Audio Fidelity & Alignment Report");
      pw.println();
      pw.println("> [!NOTE]");
      pw.println(
          "> This report displays the sample-accurate correlation, RMS level parity, and alignment lag for all 31 individual track stems rendered by the pure-Java Deluge engine compared directly against the original Ableton Live reference stems.");
      pw.println();
      pw.println("## 📊 Stems Comparison Table");
      pw.println();
      pw.println(
          "| Track # | Track Name | Reference File | Lag (samples) | Lag (ms) | Peak Correlation | RMS Java | RMS Ableton | MAE | Status |");
      pw.println("|---:|:---|:---|---:|---:|---:|---:|---:|---:|:---|");

      int analyzed = 0;
      int perfectCount = 0;
      int highCount = 0;

      for (File rendered : renderedStems) {
        File reference = findReference(rendered.getName(), dir);
        if (reference == null) {
          System.out.println("[WARN] No reference found for rendered stem: " + rendered.getName());
          continue;
        }

        System.out.println(
            "Comparing Track "
                + extractTrackNumber(rendered.getName())
                + ": "
                + reference.getName()
                + "...");
        try {
          ComparisonReport report = FidelityComparisonTool.compare(rendered, reference);

          double peakPercent = report.peakCorrelation * 100.0;
          String status = "⚠️ Moderate";
          if (peakPercent >= 95.0) {
            status = "✅ PERFECT";
            perfectCount++;
          } else if (peakPercent >= 80.0) {
            status = "⚡ High";
            highCount++;
          } else if (peakPercent >= 50.0) {
            status = "📈 Medium";
          }

          pw.printf(
              "| %d | %s | %s | %d | %.2f ms | %.2f%% | %.4f | %.4f | %.4f | %s |\n",
              extractTrackNumber(rendered.getName()),
              getCleanTrackName(rendered.getName()),
              reference.getName().replace("Michael Jackson - Billie Jean ", ""),
              report.bestLagSamples,
              report.bestLagMs,
              peakPercent,
              report.rmsRendered,
              report.rmsRecorded,
              report.meanAbsoluteError,
              status);
          analyzed++;
        } catch (Exception e) {
          System.err.println("Failed to compare " + rendered.getName() + ": " + e.getMessage());
        }
      }

      pw.println();
      pw.println("## 📈 Summary Metrics");
      pw.println();
      pw.printf("- **Total Stems Analyzed:** %d\n", analyzed);
      pw.printf("- **Perfect Parity Stems (>= 95%% correlation):** %d\n", perfectCount);
      pw.printf("- **High Parity Stems (80-95%% correlation):** %d\n", highCount);
      pw.printf(
          "- **Fidelity Accuracy:** %.1f%%\n",
          (double) (perfectCount + highCount) / analyzed * 100.0);
      pw.println();
      pw.println("---");
      pw.println(
          "*Report generated automatically via pure-Java Normalized Cross-Correlation Alignment Engine.*");

      System.out.println("\n=== ANALYSIS COMPLETE ===");
      System.out.println("Saved stems fidelity report to: " + reportFile.getAbsolutePath());
    } catch (Exception e) {
      System.err.println("Failed to write report file: " + e.getMessage());
    }
  }

  private static int extractTrackNumber(String filename) {
    // e.g. "Track_2_Bass_1_stem.wav" -> 2
    try {
      String[] parts = filename.split("_");
      return Integer.parseInt(parts[1]);
    } catch (Exception e) {
      return 999;
    }
  }

  private static String getCleanTrackName(String filename) {
    // e.g. "Track_2_Bass_1_stem.wav" -> "Bass 1"
    String name = filename.replace("Track_", "").replace("_stem.wav", "");
    String[] parts = name.split("_");
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i < parts.length; i++) {
      sb.append(parts[i]).append(" ");
    }
    return sb.toString().trim();
  }

  private static File findReference(String renderedName, File dir) {
    String normRendered = normalize(renderedName.replace("Track_", "").replace("_stem.wav", ""));

    for (File f : dir.listFiles()) {
      if (f.getName().startsWith("Michael Jackson - Billie Jean ")
          && f.getName().endsWith(".wav")) {
        String refName =
            f.getName().replace("Michael Jackson - Billie Jean ", "").replace(".wav", "");
        String normRef = normalize(refName);
        if (normRendered.equals(normRef)) {
          return f;
        }
      }
    }
    // Fuzzy fallback
    for (File f : dir.listFiles()) {
      if (f.getName().startsWith("Michael Jackson - Billie Jean ")
          && f.getName().endsWith(".wav")) {
        String refName =
            f.getName().replace("Michael Jackson - Billie Jean ", "").replace(".wav", "");
        String normRef = normalize(refName);
        if (normRendered.contains(normRef) || normRef.contains(normRendered)) {
          return f;
        }
      }
    }
    return null;
  }

  private static String normalize(String s) {
    return s.toLowerCase().replaceAll("[^a-z0-9]", "").replaceAll("track[0-9]+", "");
  }
}
