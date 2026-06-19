package org.deluge.ableton;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * End-to-end integration test validating Ableton Live Set (.als) decompression, XML DOM parsing,
 * and macOS factory pack/user library sample path resolution.
 */
public class AbletonIntegrationTest {

  @Test
  public void testDecompressAndParseTemplate() throws Exception {
    String home = System.getProperty("user.home");
    File templateFile = new File(home, "Music/Ableton/User Library/Templates/opz.als");
    Assumptions.assumeTrue(templateFile.exists(), "Skipping: Ableton template not installed");

    // 1. Verify decompression
    String xml = AbletonProjectManager.decompressAls(templateFile);
    assertNotNull(xml);
    assertTrue(xml.startsWith("<?xml"), "Decompressed file must be valid XML");

    // 2. Verify DOM parsing
    Document doc = AbletonProjectManager.parseAlsToXml(templateFile);
    assertNotNull(doc);
    assertEquals("Ableton", doc.getDocumentElement().getNodeName());
  }

  @Test
  public void testDecompressAndResolveDemoSetSamples() throws Exception {
    String home = System.getProperty("user.home");
    File demoFile =
        new File(home, "Music/Ableton/Factory Packs/Sequencers/Sequencers Demo Set.als");
    Assumptions.assumeTrue(demoFile.exists(), "Skipping: Ableton Demo Set not installed");

    // 1. Parse ALS to XML Document
    Document doc = AbletonProjectManager.parseAlsToXml(demoFile);
    assertNotNull(doc);

    // 2. Extract SampleRefs and verify path resolution
    NodeList sampleRefs = doc.getElementsByTagName("SampleRef");
    assertTrue(sampleRefs.getLength() > 0, "Demo Set must contain sample references");

    boolean verifiedAtLeastOne = false;
    for (int i = 0; i < sampleRefs.getLength(); i++) {
      Element sampleRef = (Element) sampleRefs.item(i);
      NodeList fileRefs = sampleRef.getElementsByTagName("FileRef");
      if (fileRefs.getLength() > 0) {
        Element fileRef = (Element) fileRefs.item(0);

        // Extract attributes
        String relPath = getElementValueByTagName(fileRef, "RelativePath");
        String absPath = getElementValueByTagName(fileRef, "Path");
        String packName = getElementValueByTagName(fileRef, "LivePackName");

        if (relPath != null && !relPath.isBlank()) {
          // Resolve sample path
          File resolvedFile = AbletonAssetResolver.resolveSamplePath(packName, relPath, absPath);
          assertNotNull(resolvedFile, "Resolved file must not be null for relPath: " + relPath);
          assertTrue(
              resolvedFile.exists(),
              "Resolved file must exist on disk: " + resolvedFile.getAbsolutePath());
          assertTrue(
              resolvedFile.isFile(),
              "Resolved path must be a file: " + resolvedFile.getAbsolutePath());

          verifiedAtLeastOne = true;
          break; // One successful verification of a physical file is enough to prove E2E
          // integration!
        }
      }
    }
    assertTrue(
        verifiedAtLeastOne, "Must have verified at least one sample reference path successfully");
  }

  @Test
  public void testImportAbletonSet() throws Exception {
    String home = System.getProperty("user.home");
    File demoFile =
        new File(home, "Music/Ableton/Factory Packs/Sequencers/Sequencers Demo Set.als");
    Assumptions.assumeTrue(demoFile.exists(), "Skipping: Ableton Demo Set not installed");

    // 1. Parse ALS to XML Document
    Document doc = AbletonProjectManager.parseAlsToXml(demoFile);
    assertNotNull(doc);

    // 2. Import into a new ProjectModel
    org.deluge.model.ProjectModel project = new org.deluge.model.ProjectModel();
    AbletonTrackMapper.importAbletonSet(doc, project);

    // 3. Assertions on imported project metadata
    assertTrue(project.getBpm() > 0, "Imported BPM must be positive");
    System.out.println("[Test] Imported Tempo: " + project.getBpm() + " BPM");

    // Assert tracks
    java.util.List<org.deluge.model.TrackModel> tracks = project.getTracks();
    assertFalse(tracks.isEmpty(), "Imported project must have tracks");
    System.out.println("[Test] Imported track count: " + tracks.size());

    boolean foundSynthNotes = false;
    boolean foundKitSamples = false;

    for (org.deluge.model.TrackModel t : tracks) {
      System.out.println("  Track Name: \"" + t.getName() + "\" (" + t.getType() + ")");
      if (t instanceof org.deluge.model.SynthTrackModel stm) {
        System.out.println("    Clip Count: " + stm.getClips().size());
        for (org.deluge.model.ClipModel cm : stm.getClips()) {
          System.out.println(
              "      Clip Name: \""
                  + cm.getName()
                  + "\", steps: "
                  + cm.getStepCount()
                  + ", rows: "
                  + cm.getRowCount());
          for (int r = 0; r < cm.getRowCount(); r++) {
            java.util.List<org.deluge.model.HighResNote> notes = cm.getRawNoteEvents(r);
            if (notes != null && !notes.isEmpty()) {
              foundSynthNotes = true;
              System.out.println(
                  "        Row "
                      + r
                      + " (Pitch "
                      + cm.getRowYNote(r)
                      + ") has "
                      + notes.size()
                      + " high-res notes:");
              for (org.deluge.model.HighResNote n : notes) {
                System.out.println(
                    "          Note: tickPos="
                        + n.getTickPos()
                        + ", tickLen="
                        + n.getTickLen()
                        + ", vel="
                        + n.getVelocity());
                assertTrue(n.getTickPos() >= 0, "Note tick position must be non-negative");
                assertTrue(n.getTickLen() > 0, "Note tick length must be positive");
                assertTrue(
                    n.getVelocity() >= 0.0f && n.getVelocity() <= 1.0f,
                    "Note velocity must be normalized");
              }
            }
          }
        }
      } else if (t instanceof org.deluge.model.KitTrackModel ktm) {
        System.out.println("    Drum Channel Count: " + ktm.getDrums().size());
        for (org.deluge.model.Drum d : ktm.getDrums()) {
          if (d instanceof org.deluge.model.SoundDrum sd) {
            System.out.println(
                "      Drum pad: \"" + sd.getName() + "\", sample: " + sd.getSamplePath());
            if (sd.getSamplePath() != null && !sd.getSamplePath().isEmpty()) {
              foundKitSamples = true;
              File sampleFile = new File(sd.getSamplePath());
              assertTrue(
                  sampleFile.exists(),
                  "Resolved drum sample file must exist: " + sd.getSamplePath());
            }
          }
        }
        System.out.println("    Clip Count: " + ktm.getClips().size());
        for (org.deluge.model.ClipModel cm : ktm.getClips()) {
          System.out.println(
              "      Clip Name: \""
                  + cm.getName()
                  + "\", steps: "
                  + cm.getStepCount()
                  + ", rows: "
                  + cm.getRowCount());
        }
      }
    }

    assertTrue(
        foundSynthNotes,
        "Must have successfully imported and translated at least one MIDI synth note");
    assertTrue(
        foundKitSamples, "Must have successfully resolved at least one Drum Rack pad sample path");
  }

  @Test
  public void testRoundTripExportParity() throws Exception {
    String home = System.getProperty("user.home");
    File demoFile =
        new File(home, "Music/Ableton/Factory Packs/Sequencers/Sequencers Demo Set.als");
    Assumptions.assumeTrue(demoFile.exists(), "Skipping: Ableton Demo Set not installed");

    // 1. Import original set into project1
    Document doc1 = AbletonProjectManager.parseAlsToXml(demoFile);
    org.deluge.model.ProjectModel project1 = new org.deluge.model.ProjectModel();
    AbletonTrackMapper.importAbletonSet(doc1, project1);

    // 2. Export project1 to temporary .als file in scratch directory
    File scratchDir = new File(System.getProperty("java.io.tmpdir"), "deluge-ableton-test");
    if (!scratchDir.exists()) scratchDir.mkdirs();
    File exportedFile = new File(scratchDir, "roundtrip_test.als");

    // Perform export
    AbletonTrackExporter.exportProject(project1, exportedFile);
    assertTrue(exportedFile.exists(), "Exported file must exist on disk");
    assertTrue(exportedFile.length() > 0, "Exported file must not be empty");
    System.out.println(
        "[Test] Shaded round-trip export completed: "
            + exportedFile.getAbsolutePath()
            + " ("
            + exportedFile.length()
            + " bytes)");

    // 3. Import exported set back into project2
    Document doc2 = AbletonProjectManager.parseAlsToXml(exportedFile);
    org.deluge.model.ProjectModel project2 = new org.deluge.model.ProjectModel();
    AbletonTrackMapper.importAbletonSet(doc2, project2);

    // 4. Assert Round-Trip Parity
    assertEquals(project1.getBpm(), project2.getBpm(), 0.01, "BPM must match on round-trip");
    assertEquals(
        project1.getTracks().size(),
        project2.getTracks().size(),
        "Track count must match on round-trip");

    for (int i = 0; i < project1.getTracks().size(); i++) {
      org.deluge.model.TrackModel t1 = project1.getTracks().get(i);
      org.deluge.model.TrackModel t2 = project2.getTracks().get(i);

      assertEquals(t1.getName(), t2.getName(), "Track names must match");
      assertEquals(t1.getType(), t2.getType(), "Track types must match");

      if (t1 instanceof org.deluge.model.SynthTrackModel stm1) {
        org.deluge.model.SynthTrackModel stm2 = (org.deluge.model.SynthTrackModel) t2;
        assertEquals(
            stm1.getClips().size(), stm2.getClips().size(), "Synth track clip count must match");

        for (int c = 0; c < stm1.getClips().size(); c++) {
          org.deluge.model.ClipModel cm1 = stm1.getClips().get(c);
          org.deluge.model.ClipModel cm2 = stm2.getClips().get(c);
          assertEquals(cm1.getName(), cm2.getName(), "Clip names must match");
          assertEquals(cm1.getStepCount(), cm2.getStepCount(), "Clip step counts must match");
        }
      } else if (t1 instanceof org.deluge.model.KitTrackModel ktm1) {
        org.deluge.model.KitTrackModel ktm2 = (org.deluge.model.KitTrackModel) t2;
        assertEquals(
            ktm1.getDrums().size(),
            ktm2.getDrums().size(),
            "Kit track drum channel count must match");

        for (int d = 0; d < ktm1.getDrums().size(); d++) {
          org.deluge.model.Drum d1 = ktm1.getDrums().get(d);
          org.deluge.model.Drum d2 = ktm2.getDrums().get(d);
          assertEquals(d1.getName(), d2.getName(), "Drum names must match");
        }
      }
    }
    System.out.println("[Test] E2E Round-Trip Parity Verified Successfully!");
  }

  private String getElementValueByTagName(Element parent, String tagName) {
    NodeList list = parent.getElementsByTagName(tagName);
    if (list.getLength() > 0) {
      Element el = (Element) list.item(0);
      return el.getAttribute("Value");
    }
    return null;
  }
}
