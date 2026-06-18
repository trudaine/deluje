package org.deluge.cleanup;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.deluge.project.PreferencesManager;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Core engine for scanning Deluge project XMLs and identifying unused WAV recordings inside the
 * SAMPLES/RECORD directory.
 */
public class DelugeRecordingCleaner {
  private static final Logger LOG = Logger.getLogger(DelugeRecordingCleaner.class.getName());

  /** Represents the results of a library cleanup scan. */
  public static class ScanResult {
    public final List<File> allRecordings = new ArrayList<>();
    public final List<File> unusedRecordings = new ArrayList<>();
    public final Set<String> referencedPaths = new HashSet<>();
    public final List<File> problemFiles = new ArrayList<>();
  }

  /**
   * Performs a complete scan of the Deluge library.
   *
   * @return A {@link ScanResult} containing referenced paths and unused recordings.
   */
  public static ScanResult scanLibrary() {
    ScanResult result = new ScanResult();
    File libraryDir = PreferencesManager.getLibraryDir();
    if (libraryDir == null || !libraryDir.isDirectory()) {
      LOG.warning("Invalid Deluge library directory: " + libraryDir);
      return result;
    }

    // 1. Scan all XML project files in SONGS, KITS, and SYNTHS
    File songsDir = PreferencesManager.getSongsDir();
    File kitsDir = PreferencesManager.getKitsDir();
    File synthsDir = PreferencesManager.getSynthsDir();

    scanXmlFolder(songsDir, result);
    scanXmlFolder(kitsDir, result);
    scanXmlFolder(synthsDir, result);

    // 2. Scan all candidate recordings in SAMPLES/RECORD
    File samplesDir = new File(libraryDir, "SAMPLES");
    File recordDir = new File(samplesDir, "RECORD");
    if (recordDir.isDirectory()) {
      scanRecordingsFolder(recordDir, result.allRecordings);
    }

    // 3. Cross-reference to find unused recordings
    // We normalize all referenced paths to absolute paths for robust matching
    Set<String> normalizedReferencedPaths = new HashSet<>();
    for (String ref : result.referencedPaths) {
      File resolved = resolveSampleFile(libraryDir, ref);
      if (resolved != null) {
        try {
          normalizedReferencedPaths.add(resolved.getCanonicalPath());
        } catch (IOException e) {
          normalizedReferencedPaths.add(resolved.getAbsolutePath());
        }
      }
    }

    for (File f : result.allRecordings) {
      String canonPath;
      try {
        canonPath = f.getCanonicalPath();
      } catch (IOException e) {
        canonPath = f.getAbsolutePath();
      }
      if (!normalizedReferencedPaths.contains(canonPath)) {
        result.unusedRecordings.add(f);
      }
    }

    LOG.info(
        String.format(
            "Scan complete: %d total recordings, %d unused, %d active references, %d problem files",
            result.allRecordings.size(),
            result.unusedRecordings.size(),
            result.referencedPaths.size(),
            result.problemFiles.size()));

    return result;
  }

  /**
   * Quarantines a list of unused recordings by moving them to SAMPLES/UNUSED RECORDINGS/.
   *
   * @param files The list of files to quarantine.
   * @return The number of successfully quarantined files.
   */
  public static int quarantineFiles(List<File> files) {
    if (files.isEmpty()) return 0;
    File libraryDir = PreferencesManager.getLibraryDir();
    File samplesDir = new File(libraryDir, "SAMPLES");
    File quarantineDir = new File(samplesDir, "UNUSED RECORDINGS");
    if (!quarantineDir.exists()) {
      quarantineDir.mkdirs();
    }

    int successCount = 0;
    for (File f : files) {
      if (f.exists() && f.isFile()) {
        File dest = new File(quarantineDir, f.getName());
        try {
          Files.move(f.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
          successCount++;
        } catch (IOException e) {
          LOG.log(Level.SEVERE, "Failed to quarantine file: " + f.getName(), e);
        }
      }
    }
    return successCount;
  }

  /**
   * Safely deletes a list of files from disk.
   *
   * @param files The list of files to delete.
   * @return The number of successfully deleted files.
   */
  public static int deleteFiles(List<File> files) {
    int successCount = 0;
    for (File f : files) {
      if (f.exists() && f.isFile()) {
        if (f.delete()) {
          successCount++;
        } else {
          LOG.warning("Failed to delete file: " + f.getAbsolutePath());
        }
      }
    }
    return successCount;
  }

  /**
   * Moves a file from the RECORD folder to a permanent SAVED folder so it is no longer scanned as a
   * temporary recording candidate.
   */
  public static boolean saveFile(File file) {
    if (!file.exists() || !file.isFile()) return false;
    // Traverse up to find the SAMPLES directory, regardless of nesting depth
    File samplesDir = file.getParentFile();
    while (samplesDir != null && !"SAMPLES".equalsIgnoreCase(samplesDir.getName())) {
      samplesDir = samplesDir.getParentFile();
    }
    if (samplesDir == null) {
      LOG.warning("Could not locate SAMPLES directory for file: " + file.getAbsolutePath());
      return false;
    }
    File savedDir = new File(samplesDir, "SAVED");
    if (!savedDir.exists()) {
      savedDir.mkdirs();
    }
    File dest = new File(savedDir, file.getName());
    try {
      Files.move(file.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
      return true;
    } catch (IOException e) {
      LOG.log(Level.SEVERE, "Failed to save file: " + file.getName(), e);
      return false;
    }
  }

  // ── Helper Methods ────────────────────────────────────────────────────────

  private static void scanXmlFolder(File folder, ScanResult result) {
    if (folder == null || !folder.isDirectory()) return;
    File[] xmlFiles = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".xml"));
    if (xmlFiles == null) return;

    for (File xml : xmlFiles) {
      Set<String> refs = extractReferencedPaths(xml, result.problemFiles);
      result.referencedPaths.addAll(refs);
    }
  }

  private static void scanRecordingsFolder(File folder, List<File> recordings) {
    File[] wavFiles = folder.listFiles();
    if (wavFiles == null) return;
    for (File f : wavFiles) {
      if (f.isDirectory()) {
        scanRecordingsFolder(f, recordings);
      } else if (f.isFile() && f.getName().toLowerCase().endsWith(".wav")) {
        recordings.add(f);
      }
    }
  }

  /** Parses an XML file to extract all referenced sample paths. */
  public static Set<String> extractReferencedPaths(File xmlFile, List<File> problemFiles) {
    Set<String> paths = new HashSet<>();
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      // Disable DTD loading to run safely offline and prevent sandbox connection timeouts!
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      DocumentBuilder builder = factory.newDocumentBuilder();
      Document doc = builder.parse(xmlFile);
      doc.getDocumentElement().normalize();

      // 1. Extract all 'fileName' attributes recursively
      extractAttributes(doc.getDocumentElement(), "fileName", paths);

      // 2. Extract all '<fileName>' elements recursively
      NodeList list = doc.getElementsByTagName("fileName");
      for (int i = 0; i < list.getLength(); i++) {
        Node node = list.item(i);
        String text = node.getTextContent();
        if (text != null && !text.isBlank()) {
          paths.add(text.trim());
        }
      }
    } catch (Exception e) {
      LOG.warning("Failed to parse project XML " + xmlFile.getName() + ": " + e.getMessage());
      if (problemFiles != null) {
        problemFiles.add(xmlFile);
      }
    }
    return paths;
  }

  private static void extractAttributes(Element element, String attrName, Set<String> paths) {
    String val = element.getAttribute(attrName);
    if (val != null && !val.isBlank()) {
      paths.add(val.trim());
    }
    NodeList children = element.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node node = children.item(i);
      if (node.getNodeType() == Node.ELEMENT_NODE) {
        extractAttributes((Element) node, attrName, paths);
      }
    }
  }

  /** Resolves a sample file reference to its absolute physical file location on disk. */
  public static File resolveSampleFile(File libraryDir, String xmlPath) {
    if (xmlPath == null || xmlPath.isBlank()) return null;
    xmlPath = xmlPath.replace('\\', '/'); // normalize slashes

    // 1. If path is absolute, use it directly
    File f = new File(xmlPath);
    if (f.isAbsolute() && f.exists()) return f;

    // 2. If it starts with SAMPLES/, resolve from library root
    if (xmlPath.toUpperCase().startsWith("SAMPLES/")) {
      return new File(libraryDir, xmlPath);
    }

    // 3. Otherwise, it is relative to the SAMPLES/ directory
    File samplesDir = new File(libraryDir, "SAMPLES");
    return new File(samplesDir, xmlPath);
  }
}
