package org.chuck.deluge.ableton;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * End-to-end integration test validating Ableton Live Set (.als) decompression,
 * XML DOM parsing, and macOS factory pack/user library sample path resolution.
 */
public class AbletonIntegrationTest {

  @Test
  public void testDecompressAndParseTemplate() throws Exception {
    File templateFile = new File("/Users/ludo/Music/Ableton/User Library/Templates/opz.als");
    assertTrue(templateFile.exists(), "Template ALS file must exist on the system");

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
    File demoFile = new File("/Users/ludo/Music/Ableton/Factory Packs/Sequencers/Sequencers Demo Set.als");
    assertTrue(demoFile.exists(), "Demo Set ALS file must exist on the system");

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
          assertTrue(resolvedFile.exists(), "Resolved file must exist on disk: " + resolvedFile.getAbsolutePath());
          assertTrue(resolvedFile.isFile(), "Resolved path must be a file: " + resolvedFile.getAbsolutePath());

          verifiedAtLeastOne = true;
          break; // One successful verification of a physical file is enough to prove E2E integration!
        }
      }
    }
    assertTrue(verifiedAtLeastOne, "Must have verified at least one sample reference path successfully");
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
