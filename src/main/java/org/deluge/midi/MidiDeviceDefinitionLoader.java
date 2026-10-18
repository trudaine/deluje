package org.deluge.midi;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.deluge.project.PreferencesManager;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Loads/saves MidiDeviceDefinition to/from XML files in the MIDI_DEVICES/DEFINITION/ directory. */
public class MidiDeviceDefinitionLoader {
  private static final Logger LOGGER = Logger.getLogger(MidiDeviceDefinitionLoader.class.getName());

  /** C: definitions_cxx.hpp:758 — CC 1 is skipped in <ccLabels> (midi_instrument.cpp:453). */
  private static final int CC_EXTERNAL_MOD_WHEEL = 1;

  /** C: definitions_cxx.hpp:767 — real MIDI CCs are 0..119. */
  private static final int NUM_REAL_CC_NUMBERS = 120;

  /** Matches the opening <ccLabels ...> tag produced by C++ Serializer::writeAttribute. */
  private static final Pattern CC_LABELS_TAG_PATTERN =
      Pattern.compile("<ccLabels\\b(?:[^>\"']|\"[^\"]*\"|'[^']*')*>", Pattern.DOTALL);

  /**
   * Matches each attribute token (name + quoted value) inside {@code <ccLabels>}. Consuming full
   * quoted attribute values left-to-right ensures digits inside quoted label text (e.g. {@code
   * 0="Knob 12=&quot;x&quot;"} or {@code a=' 12="x" '}) are never rewritten as attribute names.
   */
  private static final Pattern ATTR_TOKEN_PATTERN =
      Pattern.compile("(\\s+)([^\\s=/>]+)(\\s*=\\s*(?:\"[^\"]*\"|'[^']*'))");

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
        LOGGER.warning(
            "MIDI: Failed to load device definition: " + f.getName() + " - " + e.getMessage());
      }
    }
    return result;
  }

  /**
   * Load a single device definition from an XML file. Supports both the Deluge firmware {@code
   * <midiDevice>} schema (midi_instrument.cpp:422-466, 562-643; storage_manager.cpp:428) and the
   * desktop {@code <midiDeviceDefinition>} schema.
   */
  public static MidiDeviceDefinition load(File xmlFile) throws Exception {
    String rawXml = Files.readString(xmlFile.toPath(), StandardCharsets.UTF_8);
    String sanitizedXml = sanitizeFirmwareCcLabelsAttributes(rawXml);

    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    DocumentBuilder builder = factory.newDocumentBuilder();
    Document doc =
        builder.parse(new ByteArrayInputStream(sanitizedXml.getBytes(StandardCharsets.UTF_8)));
    doc.getDocumentElement().normalize();

    Element root = doc.getDocumentElement();
    String rootName = root.getNodeName();
    if ("midiDevice".equals(rootName)) {
      return loadFirmwareFormat(root, xmlFile);
    }
    if (!"midiDeviceDefinition".equals(rootName)) {
      throw new IllegalArgumentException(
          "Expected <midiDevice> or <midiDeviceDefinition> root, got <" + rootName + ">");
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

  /**
   * Save a device definition to an XML file using the desktop {@code <midiDeviceDefinition>}
   * schema.
   */
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

  /**
   * Save a device definition in the exact C++ Deluge firmware {@code <midiDevice>} format
   * (midi_instrument.cpp:422-466). Writes {@code <ccLabels>} with attributes {@code 0..119}
   * (skipping CC 1, {@code CC_EXTERNAL_MOD_WHEEL}) and {@code <hideUnlabeledCC value="0|1"/>}.
   */
  public static void saveFirmwareFormat(
      MidiDeviceDefinition def, File xmlFile, boolean hideUnlabeledCc) throws Exception {
    StringBuilder sb = new StringBuilder(2048);
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    sb.append("<midiDevice>\n");
    sb.append("\t<ccLabels");
    for (int i = 0; i < NUM_REAL_CC_NUMBERS; i++) {
      if (i != CC_EXTERNAL_MOD_WHEEL) {
        MidiDeviceDefinition.CcMapping mapping = def.findMapping(i);
        String label = mapping != null ? mapping.displayName() : "";
        sb.append("\n\t\t").append(i).append("=\"").append(escapeXmlAttr(label)).append("\"");
      }
    }
    sb.append(" />\n");
    sb.append("\t<hideUnlabeledCC\n\t\tvalue=\"").append(hideUnlabeledCc ? 1 : 0).append("\" />\n");
    sb.append("</midiDevice>\n");
    Files.writeString(xmlFile.toPath(), sb.toString(), StandardCharsets.UTF_8);
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

  // ── Firmware <midiDevice> helpers ──

  private static MidiDeviceDefinition loadFirmwareFormat(Element root, File xmlFile) {
    MidiDeviceDefinition def = new MidiDeviceDefinition();

    // C midi_instrument.cpp:439-448, 595-604 — optional <definitionFile name="..."/>
    String derivedName = stripXmlExtension(xmlFile.getName());
    Element defFileEl = getChildElement(root, "definitionFile");
    if (defFileEl != null) {
      String attrName = defFileEl.getAttribute("name");
      if (attrName != null && !attrName.isBlank()) {
        String filePart = new File(attrName.replace('\\', '/')).getName();
        String stripped = stripXmlExtension(filePart);
        if (!stripped.isBlank()) {
          derivedName = stripped;
        }
      }
    }

    String idText = getChildText(root, "id");
    String nameText = getChildText(root, "name");
    def.setId(idText.isBlank() ? derivedName : idText);
    def.setName(nameText.isBlank() ? derivedName : nameText);
    def.setManufacturer(getChildText(root, "manufacturer"));
    def.setDescription(getChildText(root, "description"));

    // C midi_instrument.cpp:606-628 — <ccLabels 0="Bank" 2="Breath" .../>
    Element ccLabelsEl = getChildElement(root, "ccLabels");
    if (ccLabelsEl != null) {
      NamedNodeMap attrs = ccLabelsEl.getAttributes();
      for (int i = 0; i < attrs.getLength(); i++) {
        Attr attr = (Attr) attrs.item(i);
        parseAndAddCcLabel(def, attr.getName(), attr.getValue());
      }
      NodeList children = ccLabelsEl.getChildNodes();
      for (int i = 0; i < children.getLength(); i++) {
        Node child = children.item(i);
        if (child instanceof Element childEl) {
          String label = childEl.getAttribute("value");
          if (label == null || label.isEmpty()) {
            label = childEl.getTextContent();
          }
          parseAndAddCcLabel(def, childEl.getNodeName(), label);
        }
      }
    }
    return def;
  }

  private static void parseAndAddCcLabel(MidiDeviceDefinition def, String rawCcName, String label) {
    if (rawCcName == null || label == null || label.isBlank()) return;
    String ccDigits = rawCcName.startsWith("cc") ? rawCcName.substring(2) : rawCcName;
    final int cc;
    try {
      cc = Integer.parseInt(ccDigits);
    } catch (NumberFormatException e) {
      return;
    }
    // C midi_instrument.cpp:453, 615 — valid real CCs are 0..119 (excluding CC 1 mod wheel).
    if (cc < 0 || cc >= NUM_REAL_CC_NUMBERS || cc == CC_EXTERNAL_MOD_WHEEL) {
      return;
    }
    String cleanLabel = label.trim();
    if (!cleanLabel.isEmpty()) {
      def.addCcMapping(new MidiDeviceDefinition.CcMapping(cc, cleanLabel, cleanLabel));
    }
  }

  private static String sanitizeFirmwareCcLabelsAttributes(String xml) {
    Matcher tagMatcher = CC_LABELS_TAG_PATTERN.matcher(xml);
    StringBuilder sb = new StringBuilder(xml.length() + 128);
    while (tagMatcher.find()) {
      String tag = tagMatcher.group();
      Matcher attrMatcher = ATTR_TOKEN_PATTERN.matcher(tag);
      StringBuilder rewrittenTag = new StringBuilder(tag.length() + 32);
      while (attrMatcher.find()) {
        String attrName = attrMatcher.group(2);
        if (isAllDigits(attrName)) {
          String replacement = attrMatcher.group(1) + "cc" + attrName + attrMatcher.group(3);
          attrMatcher.appendReplacement(rewrittenTag, Matcher.quoteReplacement(replacement));
        } else {
          attrMatcher.appendReplacement(
              rewrittenTag, Matcher.quoteReplacement(attrMatcher.group(0)));
        }
      }
      attrMatcher.appendTail(rewrittenTag);
      tagMatcher.appendReplacement(sb, Matcher.quoteReplacement(rewrittenTag.toString()));
    }
    tagMatcher.appendTail(sb);
    return sb.toString();
  }

  private static boolean isAllDigits(String s) {
    if (s == null || s.isEmpty()) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      if (!Character.isDigit(s.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static String stripXmlExtension(String fileName) {
    return fileName.replaceFirst("(?i)\\.xml$", "");
  }

  private static String escapeXmlAttr(String value) {
    if (value == null || value.isEmpty()) return "";
    return value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;");
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
