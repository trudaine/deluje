package org.deluge.cleanup;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.deluge.project.PreferencesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class DelugeRecordingCleanerTest {

  @TempDir Path tempDir;

  private File originalLibraryDir;
  private File mockLibraryDir;

  @BeforeEach
  public void setUp() throws IOException {
    // Save original preference
    originalLibraryDir = PreferencesManager.getLibraryDir();

    // Set up mock library folder structure
    mockLibraryDir = tempDir.toFile();
    PreferencesManager.setLibraryDir(mockLibraryDir.getAbsolutePath());

    File songsDir = new File(mockLibraryDir, "SONGS");
    File kitsDir = new File(mockLibraryDir, "KITS");
    File synthsDir = new File(mockLibraryDir, "SYNTHS");
    File recordDir = new File(new File(mockLibraryDir, "SAMPLES"), "RECORD");

    songsDir.mkdirs();
    kitsDir.mkdirs();
    synthsDir.mkdirs();
    recordDir.mkdirs();

    // Create a mock song XML with active references
    String songXml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<song>\n"
            + "  <instruments>\n"
            + "    <synth>\n"
            + "      <osc1 fileName=\"RECORD/REC001.WAV\" />\n"
            + "    </synth>\n"
            + "    <kit>\n"
            + "      <sound>\n"
            + "        <osc1>\n"
            + "          <fileName>SAMPLES/RECORD/REC002.WAV</fileName>\n"
            + "        </osc1>\n"
            + "      </sound>\n"
            + "    </kit>\n"
            + "  </instruments>\n"
            + "</song>";
    Files.writeString(new File(songsDir, "song1.XML").toPath(), songXml);

    // Create a mock kit XML with a nested zone active reference
    String kitXml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<kit>\n"
            + "  <sound>\n"
            + "    <zone>\n"
            + "      <sample fileName=\"RECORD/REC003.WAV\" />\n"
            + "    </zone>\n"
            + "  </sound>\n"
            + "</kit>";
    Files.writeString(new File(kitsDir, "kit1.XML").toPath(), kitXml);

    // Create candidate recordings in SAMPLES/RECORD
    Files.writeString(new File(recordDir, "REC001.WAV").toPath(), "mock audio 1");
    Files.writeString(new File(recordDir, "REC002.WAV").toPath(), "mock audio 2");
    Files.writeString(new File(recordDir, "REC003.WAV").toPath(), "mock audio 3");
    Files.writeString(new File(recordDir, "REC004.WAV").toPath(), "mock audio 4 (orphaned)");
    Files.writeString(new File(recordDir, "REC005.WAV").toPath(), "mock audio 5 (orphaned)");
  }

  @AfterEach
  public void tearDown() {
    // Restore original library directory preference
    if (originalLibraryDir != null) {
      PreferencesManager.setLibraryDir(originalLibraryDir.getAbsolutePath());
    }
  }

  @Test
  public void testExtractReferencedPaths() {
    File songFile = new File(new File(mockLibraryDir, "SONGS"), "song1.XML");
    List<File> problems = new java.util.ArrayList<>();
    Set<String> paths = DelugeRecordingCleaner.extractReferencedPaths(songFile, problems);

    assertEquals(0, problems.size());
    assertTrue(paths.contains("RECORD/REC001.WAV"));
    assertTrue(paths.contains("SAMPLES/RECORD/REC002.WAV"));
  }

  @Test
  public void testScanLibraryAndCrossReference() {
    DelugeRecordingCleaner.ScanResult result = DelugeRecordingCleaner.scanLibrary();

    // We have 5 files in SAMPLES/RECORD
    assertEquals(5, result.allRecordings.size());
    assertEquals(0, result.problemFiles.size());

    // REC001, REC002, REC003 are active. REC004 and REC005 are unused
    assertEquals(2, result.unusedRecordings.size());

    Set<String> unusedNames = new java.util.HashSet<>();
    for (File f : result.unusedRecordings) {
      unusedNames.add(f.getName());
    }
    assertTrue(unusedNames.contains("REC004.WAV"));
    assertTrue(unusedNames.contains("REC005.WAV"));
  }

  @Test
  public void testQuarantineOperation() {
    DelugeRecordingCleaner.ScanResult result = DelugeRecordingCleaner.scanLibrary();
    int success = DelugeRecordingCleaner.quarantineFiles(result.unusedRecordings);

    assertEquals(2, success);

    // Verify that the unused recordings are moved out of the RECORD folder
    File recordDir = new File(new File(mockLibraryDir, "SAMPLES"), "RECORD");
    assertFalse(new File(recordDir, "REC004.WAV").exists());
    assertFalse(new File(recordDir, "REC005.WAV").exists());

    // Verify that they now live inside the UNUSED RECORDINGS folder
    File quarantineDir = new File(new File(mockLibraryDir, "SAMPLES"), "UNUSED RECORDINGS");
    assertTrue(new File(quarantineDir, "REC004.WAV").exists());
    assertTrue(new File(quarantineDir, "REC005.WAV").exists());
  }

  @Test
  public void testDeleteOperation() {
    DelugeRecordingCleaner.ScanResult result = DelugeRecordingCleaner.scanLibrary();
    int success = DelugeRecordingCleaner.deleteFiles(result.unusedRecordings);

    assertEquals(2, success);

    // Verify they are completely gone from disk
    File recordDir = new File(new File(mockLibraryDir, "SAMPLES"), "RECORD");
    assertFalse(new File(recordDir, "REC004.WAV").exists());
    assertFalse(new File(recordDir, "REC005.WAV").exists());
  }
}
