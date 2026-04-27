package org.chuck.deluge.xml;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public class DelugeXmlValidator {

  public static void main(String[] args) {
    Path downloadArea = Paths.get(System.getProperty("user.home"), "delugedownload");
    if (!Files.exists(downloadArea)) {
      System.err.println("Download area not found: " + downloadArea);
      return;
    }

    System.out.println("Scanning for XML files in: " + downloadArea);

    List<Path> xmlFiles = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(downloadArea)) {
      walk.filter(p -> p.toString().toLowerCase().endsWith(".xml"))
          .filter(p -> !p.getFileName().toString().startsWith("._")) // Filter macOS metadata
          .forEach(xmlFiles::add);
    } catch (Exception e) {
      System.err.println("Error scanning directory: " + e.getMessage());
      return;
    }

    System.out.println("Found " + xmlFiles.size() + " XML files.");

    int success = 0;
    int failure = 0;
    List<String> errorReports = new ArrayList<>();

    for (Path path : xmlFiles) {
      System.out.print("Validating: " + path.getFileName() + " ... ");
      try {
        // Try to detect if it's a song, kit or synth based on root tag
        // DelugeXmlParser.parseSong handles generic wrapping so we can try parseSong first
        // as it's the most comprehensive or we can try to detect the root tag.

        String content = Files.readString(path);
        if (content.contains("<song")) {
          DelugeXmlParser.parseSong(path.toFile());
        } else if (content.contains("<kit")) {
          DelugeXmlParser.parseKit(path.toFile());
        } else if (content.contains("<sound")) {
          DelugeXmlParser.parseSynth(path.toFile());
        } else {
          // Generic attempt if root tag not found in first few chars
          DelugeXmlParser.parseSong(path.toFile());
        }

        System.out.println("OK");
        success++;
      } catch (Exception e) {
        System.out.println("FAILED");
        failure++;
        errorReports.add(
            "File: " + path + "\nError: " + e.getClass().getName() + ": " + e.getMessage());
      }
    }

    System.out.println("\n--- Validation Summary ---");
    System.out.println("Total Files: " + xmlFiles.size());
    System.out.println("Successes:   " + success);
    System.out.println("Failures:    " + failure);

    if (!errorReports.isEmpty()) {
      System.out.println("\n--- Failure Reports ---");
      for (String report : errorReports) {
        System.out.println(report);
        System.out.println("-----------------------");
      }
    }
  }
}
