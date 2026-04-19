package org.chuck.deluge.project;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class PreferencesManagerTest {

  @AfterEach
  void tearDown() {
    PreferencesManager.clearRecentFiles();
  }

  @Test
  void testSamplesDir() {
    String original = PreferencesManager.getSamplesDir();
    PreferencesManager.setSamplesDir("/tmp/TEST_SAMPLES");
    assertEquals("/tmp/TEST_SAMPLES", PreferencesManager.getSamplesDir());

    // Restore
    PreferencesManager.setSamplesDir(original);
  }

  @Test
  void testRecentFilesLimits() {
    PreferencesManager.clearRecentFiles();

    for (int i = 0; i < 15; i++) {
      PreferencesManager.addRecentFile("/tmp/file_" + i + ".xml");
    }

    List<String> recent = PreferencesManager.getRecentFiles();

    // Should cap at 10 (MAX_RECENT)
    assertEquals(10, recent.size(), "Recent files should be capped at 10");

    // Most recent (14) should be at index 0
    assertEquals("/tmp/file_14.xml", recent.get(0));

    // Test deduplication: add an existing file
    PreferencesManager.addRecentFile("/tmp/file_5.xml");
    recent = PreferencesManager.getRecentFiles();
    assertEquals("/tmp/file_5.xml", recent.get(0), "Duplicate file should be moved to front");
    assertEquals(10, recent.size(), "Size should remain 10");
  }
}
