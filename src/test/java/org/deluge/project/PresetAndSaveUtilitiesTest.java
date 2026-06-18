package org.deluge.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class PresetAndSaveUtilitiesTest {

  @Test
  public void testPresetFinderWithMockFiles(@TempDir Path tempDir) throws IOException {
    File folder = tempDir.toFile();

    // With empty folder, it should return null
    assertNull(PresetFinder.findFirstPreset(folder));

    // Write some mock files out of order
    File f2 = new File(folder, "002_Lead.xml");
    File f0 = new File(folder, "000_Kick.xml");
    File f1 = new File(folder, "001_Snare.xml");
    File nonXml = new File(folder, "000_Clap.txt");

    Files.writeString(f2.toPath(), "<sound></sound>");
    Files.writeString(f0.toPath(), "<sound></sound>");
    Files.writeString(f1.toPath(), "<sound></sound>");
    Files.writeString(nonXml.toPath(), "text");

    File first = PresetFinder.findFirstPreset(folder);
    assertNotNull(first);
    assertEquals("000_Kick.xml", first.getName());
  }

  @Test
  public void testSaveNameSuggesterForNewSong(@TempDir Path tempDir) throws IOException {
    File songsDir = tempDir.toFile();

    // 1. Empty folder -> suggest SONG000.xml
    File suggested = SaveNameSuggester.suggestNextSaveFile(songsDir, null);
    assertNotNull(suggested);
    assertEquals("SONG000.xml", suggested.getName());

    // 2. Write SONG000 and SONG001 -> suggest SONG002
    Files.writeString(new File(songsDir, "SONG000.xml").toPath(), "<song></song>");
    Files.writeString(new File(songsDir, "SONG001.xml").toPath(), "<song></song>");

    File suggested2 = SaveNameSuggester.suggestNextSaveFile(songsDir, null);
    assertEquals("SONG002.xml", suggested2.getName());

    // 3. Write SONG004 (creating a gap) -> suggest SONG005 (max index + 1)
    Files.writeString(new File(songsDir, "SONG004.xml").toPath(), "<song></song>");
    File suggested3 = SaveNameSuggester.suggestNextSaveFile(songsDir, null);
    assertEquals("SONG005.xml", suggested3.getName());
  }

  @Test
  public void testSaveNameSuggesterForExistingSong(@TempDir Path tempDir) throws IOException {
    File songsDir = tempDir.toFile();

    File currentFile = new File(songsDir, "SONG003.xml");

    // 1. No other subslots -> suggest SONG003A
    File suggested = SaveNameSuggester.suggestNextSaveFile(songsDir, currentFile);
    assertEquals("SONG003A.xml", suggested.getName());

    // 2. Write SONG003A -> suggest SONG003B
    Files.writeString(new File(songsDir, "SONG003A.xml").toPath(), "<song></song>");
    File suggested2 = SaveNameSuggester.suggestNextSaveFile(songsDir, currentFile);
    assertEquals("SONG003B.xml", suggested2.getName());

    // 3. Write SONG003B and SONG003C -> suggest SONG003D
    Files.writeString(new File(songsDir, "SONG003B.xml").toPath(), "<song></song>");
    Files.writeString(new File(songsDir, "SONG003C.xml").toPath(), "<song></song>");
    File suggested3 = SaveNameSuggester.suggestNextSaveFile(songsDir, currentFile);
    assertEquals("SONG003D.xml", suggested3.getName());

    // 4. If current is already SONG003B -> suggest SONG003C (wait! if SONG003C already exists, it
    // loops past it to D!)
    File currentB = new File(songsDir, "SONG003B.xml");
    File suggested4 = SaveNameSuggester.suggestNextSaveFile(songsDir, currentB);
    assertEquals("SONG003D.xml", suggested4.getName());
  }
}
