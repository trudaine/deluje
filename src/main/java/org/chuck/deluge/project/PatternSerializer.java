package org.chuck.deluge.project;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.chuck.deluge.model.PatternModel;
import org.chuck.deluge.model.PatternModel.ClipSnapshot;
import org.chuck.deluge.model.StepData;
import org.chuck.deluge.xml.DelugeHexMapper;
import org.chuck.deluge.xml.DelugeNoteDataMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Serializes PatternModel to/from XML files in the PATTERNS directory.
 *
 * <p>Directory layout:
 * <ul>
 *   <li>PATTERNS/MELODIC/ — synth pattern XML files</li>
 *   <li>PATTERNS/RHYTHMIC/DRUM/ — drum pattern XML files</li>
 *   <li>PATTERNS/RHYTHMIC/KIT/ — kit pattern XML files</li>
 * </ul>
 */
public class PatternSerializer {

  /**
   * Save a pattern model to the given XML file.
   * Creates parent directories as needed.
   */
  public static void save(PatternModel pattern, File file) throws Exception {
    file.getParentFile().mkdirs();

    DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
    Document doc = docBuilder.newDocument();

    Element root = doc.createElement("pattern");
    doc.appendChild(root);

    Element idElem = doc.createElement("id");
    idElem.setTextContent(pattern.getId());
    root.appendChild(idElem);

    Element nameElem = doc.createElement("name");
    nameElem.setTextContent(pattern.getName());
    root.appendChild(nameElem);

    Element catElem = doc.createElement("category");
    catElem.setTextContent(pattern.getCategory());
    root.appendChild(catElem);

    Element clipsElem = doc.createElement("clips");
    root.appendChild(clipsElem);

    for (ClipSnapshot snap : pattern.getClipSnapshots()) {
      Element clipElem = doc.createElement("clip");
      clipElem.setAttribute("trackIndex", String.valueOf(snap.getTrackIndex()));
      clipsElem.appendChild(clipElem);

      Element slotElem = doc.createElement("instrumentSlot");
      slotElem.setTextContent(snap.getInstrumentSlot());
      clipElem.appendChild(slotElem);

      Element colourElem = doc.createElement("colourHex");
      colourElem.setTextContent(snap.getColourHex());
      clipElem.appendChild(colourElem);

      Element rcElem = doc.createElement("rowCount");
      rcElem.setTextContent(String.valueOf(snap.getRowCount()));
      clipElem.appendChild(rcElem);

      Element scElem = doc.createElement("stepCount");
      scElem.setTextContent(String.valueOf(snap.getStepCount()));
      clipElem.appendChild(scElem);

      // Note rows (hex-encoded)
      Element noteRowsElem = doc.createElement("noteRows");
      clipElem.appendChild(noteRowsElem);
      for (int r = 0; r < snap.getRowCount(); r++) {
        Element nrElem = doc.createElement("noteRow");
        noteRowsElem.appendChild(nrElem);

        List<StepData> row = (r < snap.getGrid().size())
            ? snap.getGrid().get(r)
            : List.of();
        String hexData = DelugeNoteDataMapper.encodeRow(row);
        Element ndElem = doc.createElement("noteData");
        ndElem.setTextContent(hexData);
        nrElem.appendChild(ndElem);

        // Row sound params
        Map<String, Float> rowParams = snap.getRowSoundParams().get(r);
        if (rowParams != null && !rowParams.isEmpty()) {
          Element spElem = doc.createElement("soundParams");
          for (Map.Entry<String, Float> pe : rowParams.entrySet()) {
            spElem.setAttribute(pe.getKey(), DelugeHexMapper.floatToHex(pe.getValue()));
          }
          nrElem.appendChild(spElem);
        }
      }

      // Automation
      Map<String, float[]> autoData = snap.getAutomationData();
      if (!autoData.isEmpty()) {
        Element autoElem = doc.createElement("automation");
        clipElem.appendChild(autoElem);
        for (Map.Entry<String, float[]> e : autoData.entrySet()) {
          Element paramElem = doc.createElement("param");
          paramElem.setAttribute("name", e.getKey());
          autoElem.appendChild(paramElem);
          float[] arr = e.getValue();
          for (int s = 0; s < arr.length; s++) {
            if (arr[s] >= 0f) {
              Element stepElem = doc.createElement("step");
              stepElem.setAttribute("index", String.valueOf(s));
              stepElem.setAttribute("value", DelugeHexMapper.floatToHex(arr[s]));
              paramElem.appendChild(stepElem);
            }
          }
        }
      }

      // Kit params
      Map<String, Float> kitParams = snap.getKitParams();
      if (!kitParams.isEmpty()) {
        Element kpElem = doc.createElement("kitParams");
        clipElem.appendChild(kpElem);
        for (Map.Entry<String, Float> e : kitParams.entrySet()) {
          kpElem.setAttribute(e.getKey(), DelugeHexMapper.floatToHex(e.getValue()));
        }
      }
    }

    // Write XML
    TransformerFactory transformerFactory = TransformerFactory.newInstance();
    Transformer transformer = transformerFactory.newTransformer();
    transformer.setOutputProperty(OutputKeys.INDENT, "yes");
    transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

    DOMSource source = new DOMSource(doc);
    StreamResult result = new StreamResult(file);
    transformer.transform(source, result);
  }

  /**
   * Load a pattern model from the given XML file.
   */
  public static PatternModel load(File file) throws Exception {
    return load(Files.newInputStream(file.toPath()), file.getName());
  }

  /**
   * Load a pattern model from an input stream.
   */
  public static PatternModel load(InputStream is, String fileName) throws Exception {
    DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
    Document doc = docBuilder.parse(is);

    Element root = doc.getDocumentElement();
    if (!"pattern".equals(root.getNodeName())) {
      throw new IllegalArgumentException("Not a pattern XML: " + fileName);
    }

    String id = getTextContent(root, "id");
    String name = getTextContent(root, "name");
    String category = getTextContent(root, "category");

    PatternModel pattern = new PatternModel(id, name);
    if (category != null) pattern.setCategory(category);

    // Parse clips
    NodeList clipNodes = root.getElementsByTagName("clip");
    for (int ci = 0; ci < clipNodes.getLength(); ci++) {
      Element clipElem = (Element) clipNodes.item(ci);

      int trackIndex = 0;
      String tiStr = clipElem.getAttribute("trackIndex");
      if (tiStr != null && !tiStr.isEmpty()) trackIndex = Integer.parseInt(tiStr);

      String instrumentSlot = getTextContent(clipElem, "instrumentSlot");
      String colourHex = getTextContent(clipElem, "colourHex");
      int rowCount = parseIntOrDefault(getTextContent(clipElem, "rowCount"), 8);
      int stepCount = parseIntOrDefault(getTextContent(clipElem, "stepCount"), 16);

      ClipSnapshot snap = new ClipSnapshot(trackIndex, "", rowCount, stepCount);
      if (instrumentSlot != null) snap.setInstrumentSlot(instrumentSlot);
      if (colourHex != null) snap.setColourHex(colourHex);

      // Parse note rows
      Element noteRowsElem = getChildElement(clipElem, "noteRows");
      if (noteRowsElem != null) {
        NodeList nrList = noteRowsElem.getElementsByTagName("noteRow");
        for (int ri = 0; ri < nrList.getLength() && ri < rowCount; ri++) {
          Element nrElem = (Element) nrList.item(ri);
          String nd = getTextContent(nrElem, "noteData");
          if (nd != null && !nd.isEmpty()) {
            List<StepData> decodedRow = DelugeNoteDataMapper.decodeRow(nd, stepCount);
            if (ri < snap.getGrid().size()) {
              snap.getGrid().set(ri, decodedRow);
            }
          }

          // Parse row sound params
          Element spElem = getChildElement(nrElem, "soundParams");
          if (spElem != null) {
            var attrs = spElem.getAttributes();
            Map<String, Float> rowParams = new java.util.HashMap<>();
            for (int ai = 0; ai < attrs.getLength(); ai++) {
              var attr = attrs.item(ai);
              String pn = attr.getNodeName();
              String pv = attr.getNodeValue();
              try {
                rowParams.put(pn, DelugeHexMapper.hexToFloat(pv));
              } catch (Exception ignored) {}
            }
            if (!rowParams.isEmpty()) {
              snap.getRowSoundParams().put(ri, rowParams);
            }
          }
        }
      }

      // Parse automation
      Element autoElem = getChildElement(clipElem, "automation");
      if (autoElem != null) {
        NodeList paramList = autoElem.getElementsByTagName("param");
        for (int pi = 0; pi < paramList.getLength(); pi++) {
          Element paramElem = (Element) paramList.item(pi);
          String paramName = paramElem.getAttribute("name");
          if (paramName == null || paramName.isEmpty()) continue;

          float[] arr = new float[stepCount];
          java.util.Arrays.fill(arr, -1f);

          NodeList stepList = paramElem.getElementsByTagName("step");
          for (int si = 0; si < stepList.getLength(); si++) {
            Element stepElem = (Element) stepList.item(si);
            int idx = parseIntOrDefault(stepElem.getAttribute("index"), -1);
            if (idx >= 0 && idx < stepCount) {
              String valStr = stepElem.getAttribute("value");
              try {
                arr[idx] = DelugeHexMapper.hexToFloat(valStr);
              } catch (Exception ignored) {}
            }
          }
          snap.getAutomationData().put(paramName, arr);
        }
      }

      // Parse kit params
      Element kpElem = getChildElement(clipElem, "kitParams");
      if (kpElem != null) {
        var attrs = kpElem.getAttributes();
        for (int ai = 0; ai < attrs.getLength(); ai++) {
          var attr = attrs.item(ai);
          String pn = attr.getNodeName();
          String pv = attr.getNodeValue();
          try {
            snap.getKitParams().put(pn, DelugeHexMapper.hexToFloat(pv));
          } catch (Exception ignored) {}
        }
      }

      pattern.addClipSnapshot(snap);
    }

    return pattern;
  }

  // ── XML helpers ──

  private static String getTextContent(Element parent, String tagName) {
    NodeList list = parent.getElementsByTagName(tagName);
    if (list.getLength() > 0) {
      String text = list.item(0).getTextContent();
      return (text != null) ? text.trim() : null;
    }
    return null;
  }

  private static Element getChildElement(Element parent, String tagName) {
    NodeList list = parent.getChildNodes();
    for (int i = 0; i < list.getLength(); i++) {
      if (list.item(i) instanceof Element e && tagName.equals(e.getNodeName())) {
        return e;
      }
    }
    return null;
  }

  private static int parseIntOrDefault(String s, int def) {
    if (s == null || s.isEmpty()) return def;
    try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
  }
}
