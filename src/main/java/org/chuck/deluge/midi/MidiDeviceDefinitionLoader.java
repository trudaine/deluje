package org.chuck.deluge.midi;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.chuck.deluge.project.PreferencesManager;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Loads/saves MidiDeviceDefinition to/from XML files in the MIDI_DEVICES/DEFINITION/ directory. */
public class MidiDeviceDefinitionLoader {

  /** Returns the MIDI_DEVICES/DEFINITION/ directory, creating it if missing. */
  public static File getDefinitionsDir() {
    return PreferencesManager.getMidiDeviceDefinitionsDir();
  }

  /** Scan the definitions directory and load all device definition files. */
  public static List<MidiDeviceDefinition> loadAll() {
    List<MidiDeviceDefinition> result = new ArrayList<>();
    File dir = getDefinitionsDir();
    File[] files = dir.listFiles((d, name) -> name.endsWith(".xml") || name.endsWith(".XML"));
    if (files == null) return result;
    for (File f : files) {
      try {
        MidiDeviceDefinition def = load(f);
        if (def != null) result.add(def);
      } catch (Exception e) {
        System.err.println(
            "MIDI: Failed to load device definition: " + f.getName() + " - " + e.getMessage());
      }
    }
    return result;
  }

  /** Load a single device definition from an XML file. */
  public static MidiDeviceDefinition load(File xmlFile) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    DocumentBuilder builder = factory.newDocumentBuilder();
    Document doc = builder.parse(xmlFile);
    doc.getDocumentElement().normalize();

    Element root = doc.getDocumentElement();
    if (!"midiDeviceDefinition".equals(root.getNodeName())) {
      throw new IllegalArgumentException(
          "Expected <midiDeviceDefinition> root, got <" + root.getNodeName() + ">");
    }

    MidiDeviceDefinition def = new MidiDeviceDefinition();
    def.setId(getChildText(root, "id"));
    def.setName(getChildText(root, "name"));
    def.setManufacturer(getChildText(root, "manufacturer"));
    def.setDescription(getChildText(root, "description"));

    Element mappingsEl = getChildElement(root, "ccMappings");
    if (mappingsEl != null) {
      NodeList mappingNodes = mappingsEl.getElementsByTagName("ccMapping");
      for (int i = 0; i < mappingNodes.getLength(); i++) {
        Element el = (Element) mappingNodes.item(i);
        int cc = Integer.parseInt(el.getAttribute("cc"));
        String paramName = el.getAttribute("paramName");
        String displayName = el.getAttribute("displayName");
        if (displayName == null || displayName.isEmpty()) displayName = paramName;
        def.addCcMapping(new MidiDeviceDefinition.CcMapping(cc, paramName, displayName));
      }
    }

    return def;
  }

  /** Save a device definition to an XML file. */
  public static void save(MidiDeviceDefinition def, File xmlFile) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    DocumentBuilder builder = factory.newDocumentBuilder();
    Document doc = builder.newDocument();

    Element root = doc.createElement("midiDeviceDefinition");
    doc.appendChild(root);

    appendChildText(doc, root, "id", def.getId() != null ? def.getId() : "");
    appendChildText(doc, root, "name", def.getName() != null ? def.getName() : "");
    appendChildText(doc, root, "manufacturer", def.getManufacturer());
    appendChildText(doc, root, "description", def.getDescription());

    Element mappingsEl = doc.createElement("ccMappings");
    root.appendChild(mappingsEl);
    for (MidiDeviceDefinition.CcMapping mapping : def.getCcMappings()) {
      Element el = doc.createElement("ccMapping");
      el.setAttribute("cc", String.valueOf(mapping.cc()));
      el.setAttribute("paramName", mapping.paramName());
      el.setAttribute("displayName", mapping.displayName());
      mappingsEl.appendChild(el);
    }

    TransformerFactory tf = TransformerFactory.newInstance();
    Transformer transformer = tf.newTransformer();
    transformer.setOutputProperty(OutputKeys.INDENT, "yes");
    transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
    DOMSource source = new DOMSource(doc);
    StreamResult result = new StreamResult(xmlFile);
    transformer.transform(source, result);
  }

  /** Load a device definition by its id. Searches the definitions directory. */
  public static MidiDeviceDefinition findById(String id) {
    File dir = getDefinitionsDir();
    File[] files = dir.listFiles((d, name) -> name.endsWith(".xml") || name.endsWith(".XML"));
    if (files == null) return null;
    for (File f : files) {
      try {
        MidiDeviceDefinition def = load(f);
        if (id.equals(def.getId())) return def;
      } catch (Exception e) {
        // skip
      }
    }
    return null;
  }

  // ── XML helpers ──

  private static String getChildText(Element parent, String tagName) {
    Element el = getChildElement(parent, tagName);
    return el != null ? el.getTextContent() : "";
  }

  private static Element getChildElement(Element parent, String tagName) {
    NodeList list = parent.getChildNodes();
    for (int i = 0; i < list.getLength(); i++) {
      if (list.item(i) instanceof Element el && tagName.equals(el.getNodeName())) {
        return el;
      }
    }
    return null;
  }

  private static void appendChildText(Document doc, Element parent, String tagName, String text) {
    Element el = doc.createElement(tagName);
    el.setTextContent(text != null ? text : "");
    parent.appendChild(el);
  }
}
