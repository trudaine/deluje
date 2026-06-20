package org.deluge.project;

import java.io.File;
import org.deluge.model.ProjectModel;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

public class RoundtripDiagnostics {
  @Test
  public void runRoundtrip() throws Exception {
    File originalFile = new File("src/main/resources/SONGS/SONG006669.XML");
    System.out.println("=== DIAGNOSTIC RUNNER: PARSING ORIGINAL SONG ===");
    System.out.println("Original path: " + originalFile.getAbsolutePath());

    ProjectModel model = DelugeXmlParser.parseSong(originalFile);
    System.out.println("=== DIAGNOSTIC RUNNER: PARSED SUCCESSFULLY! ===");
    System.out.println("Tracks found: " + model.getTracks().size());
    System.out.println("BPM: " + model.getBpm());
    System.out.println("Swing: " + model.getSwing());

    File regenFile = new File("src/main/resources/SONGS/SONG006669_REGEN.XML");
    System.out.println("=== DIAGNOSTIC RUNNER: SERIALIZING REGENERATED SONG (DOM) ===");
    ProjectSerializer.save(model, regenFile);
    System.out.println("=== DIAGNOSTIC RUNNER: REGENERATED (DOM) SUCCESSFULLY TO ===");
    System.out.println(regenFile.getAbsolutePath());

    File regenFile2 = new File("src/main/resources/SONGS/SONG006669_REGEN2.XML");
    System.out.println("=== DIAGNOSTIC RUNNER: SERIALIZING REGENERATED SONG (STREAM) ===");
    org.deluge.xml2.ProjectSerializer2.save(model, regenFile2);
    System.out.println("=== DIAGNOSTIC RUNNER: REGENERATED (STREAM) SUCCESSFULLY TO ===");
    System.out.println(regenFile2.getAbsolutePath());
  }
}
