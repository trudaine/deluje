package org.deluge.xml;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.deluge.model.ClipModel;
import org.deluge.model.EnvelopeModel;
import org.deluge.model.KeyZone;
import org.deluge.model.LfoType;
import org.deluge.model.MidiKnob;
import org.deluge.model.PatchCable;
import org.deluge.model.TrackModel;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Common XML parsing and DOM-traversal utility helpers for Deluge XML files. */
public class DelugeXmlUtil {
  private static final Logger LOG = Logger.getLogger(DelugeXmlUtil.class.getName());

  public static Document parseXml(InputStream is) throws Exception {
    byte[] bytes = is.readAllBytes();
    String content = new String(bytes, StandardCharsets.UTF_8);

    // 1. Remove XML declaration
    content = content.replaceFirst("<\\?xml.*?\\?>", "");

    // 2. Escape ALL ampersands.
    content = content.replace("&", "&amp;");

    // 3. Wrap in virtual root to handle multiple top-level elements
    String wrapped = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root>\n" + content + "\n</root>";

    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setValidating(false);
    factory.setNamespaceAware(true);
    DocumentBuilder builder = factory.newDocumentBuilder();
    return builder.parse(new ByteArrayInputStream(wrapped.getBytes(StandardCharsets.UTF_8)));
  }

  public static Element getFirstChild(Element parent, String tag) {
    NodeList nodes = parent.getElementsByTagName(tag);
    return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
  }

  public static String getChildText(Element parent, String tag) {
    NodeList nodes = parent.getElementsByTagName(tag);
    if (nodes.getLength() > 0) {
      String text = nodes.item(0).getTextContent();
      return (text == null || text.isBlank()) ? null : text.trim();
    }
    return null;
  }

  public static int getChildInteger(Element parent, String tag, int def) {
    NodeList nodes = parent.getElementsByTagName(tag);
    if (nodes.getLength() == 0) return def;
    Element child = (Element) nodes.item(0);
    String val = child.getAttribute("value");
    if (val == null || val.isEmpty()) val = child.getTextContent();
    if (val != null && !val.isBlank()) {
      try {
        return Integer.parseInt(val.trim());
      } catch (NumberFormatException e) {
        return def;
      }
    }
    return def;
  }

  public static String readAttr(Element el, String attr) {
    return el.hasAttribute(attr) ? el.getAttribute(attr).trim() : null;
  }

  public static int readIntAttr(Element el, String attr, int def) {
    String val = readAttr(el, attr);
    if (val != null && !val.isEmpty()) {
      try {
        return Integer.parseInt(val);
      } catch (NumberFormatException e) {
        return def;
      }
    }
    return def;
  }

  public static int readIntAttrWithDefault(Element el, String attr, int def) {
    return readIntAttr(el, attr, def);
  }

  public static float readAttrFloatWithDefault(Element el, String attr, float def, boolean hex) {
    String val = readAttr(el, attr);
    if (val != null && !val.isEmpty()) {
      try {
        return hex ? Math.abs(DelugeHexMapper.hexToFloat(val)) : Float.parseFloat(val);
      } catch (NumberFormatException e) {
        return def;
      }
    }
    return def;
  }

  public static void readAttrString(
      Element el, String attr, java.util.function.Consumer<String> setter) {
    String val = readAttr(el, attr);
    if (val != null && !val.isEmpty()) {
      setter.accept(val);
    }
  }

  public static void readAttrOrChildString(
      Element el, String tagOrAttr, java.util.function.Consumer<String> setter) {
    String val = el.getAttribute(tagOrAttr);
    if (val == null || val.isEmpty()) {
      val = getChildText(el, tagOrAttr);
    }
    if (val != null && !val.isEmpty()) {
      setter.accept(val.trim());
    }
  }

  public static void readAttrOrChildHexFloat(
      Element el, String name, java.util.function.Consumer<Float> setter) {
    String val = el.getAttribute(name);
    if (val == null || val.isEmpty()) {
      if ("threshold".equals(name)) {
        val = el.getAttribute("thresh");
      }
    }
    if (val != null && !val.isEmpty()) {
      setter.accept(DelugeHexMapper.hexToFloat(val.trim()));
      return;
    }
    NodeList nodes = el.getElementsByTagName(name);
    if (nodes.getLength() == 0 && "threshold".equals(name)) {
      nodes = el.getElementsByTagName("thresh");
    }
    if (nodes.getLength() > 0) {
      Element child = (Element) nodes.item(0);
      String childVal = child.getAttribute("value");
      if (childVal == null || childVal.isEmpty()) {
        childVal = child.getTextContent();
      }
      if (childVal != null && !childVal.isBlank()) {
        setter.accept(DelugeHexMapper.hexToFloat(childVal.trim()));
      }
    }
  }

  public static void readAttrOrChildInt(
      Element el, String name, int defaultValue, java.util.function.Consumer<Integer> setter) {
    String val = el.getAttribute(name);
    if (val != null && !val.isEmpty()) {
      try {
        setter.accept(Integer.parseInt(val.trim()));
        return;
      } catch (NumberFormatException e) {
        // fall through
      }
    }
    NodeList nodes = el.getElementsByTagName(name);
    if (nodes.getLength() > 0) {
      String childVal = nodes.item(0).getTextContent();
      if (childVal != null && !childVal.isBlank()) {
        try {
          setter.accept(Integer.parseInt(childVal.trim()));
          return;
        } catch (NumberFormatException e) {
          // fall through
        }
      }
    }
    setter.accept(defaultValue);
  }

  public static void readHexFloat(
      Element parent, String tag, java.util.function.Consumer<Float> setter) {
    NodeList nodes = parent.getElementsByTagName(tag);
    if (nodes.getLength() == 0) return;
    Element child = (Element) nodes.item(0);
    String val = child.getAttribute("value");
    if (val == null || val.isEmpty()) {
      val = child.getTextContent();
    }
    if (val != null && !val.isBlank()) {
      float f = DelugeHexMapper.hexToFloat(val.trim());
      setter.accept(f);
    }
  }

  public static void readHexFloatUnipolar(
      Element parent, String tag, java.util.function.Consumer<Float> setter) {
    NodeList nodes = parent.getElementsByTagName(tag);
    if (nodes.getLength() == 0) return;
    Element child = (Element) nodes.item(0);
    String val = child.getAttribute("value");
    if (val == null || val.isEmpty()) {
      val = child.getTextContent();
    }
    if (val != null && !val.isBlank()) {
      float f = toUnipolar(DelugeHexMapper.hexToFloat(val.trim()));
      setter.accept(f);
    }
  }

  public static void readHexHz(
      Element parent, String tag, java.util.function.Consumer<Float> setter) {
    NodeList nodes = parent.getElementsByTagName(tag);
    if (nodes.getLength() == 0) return;
    Element child = (Element) nodes.item(0);
    String val = child.getAttribute("value");
    if (val == null || val.isEmpty()) {
      val = child.getTextContent();
    }
    if (val != null && !val.isBlank()) {
      setter.accept(DelugeHexMapper.hexToHz(val.trim()));
    }
  }

  public static float readHexFloatVal(Element el, String tag, float def) {
    NodeList nodes = el.getElementsByTagName(tag);
    if (nodes.getLength() == 0) return def;
    Element child = (Element) nodes.item(0);
    String val = child.getAttribute("value");
    if (val == null || val.isEmpty()) val = child.getTextContent();
    if (val != null && !val.isBlank()) {
      try {
        return toUnipolar(DelugeHexMapper.hexToFloat(val.trim()));
      } catch (Exception e) {
        return def;
      }
    }
    return def;
  }

  public static float readHexEnvTime(Element el, String tag, float def) {
    NodeList nodes = el.getElementsByTagName(tag);
    if (nodes.getLength() == 0) return def;
    Element child = (Element) nodes.item(0);
    String val = child.getAttribute("value");
    if (val == null || val.isEmpty()) val = child.getTextContent();
    if (val == null || val.isBlank()) return def;
    try {
      return DelugeHexMapper.hexToEnvTime(val.trim());
    } catch (Exception e) {
      return def;
    }
  }

  public static float readHexSustain(Element el, String tag, float def) {
    NodeList nodes = el.getElementsByTagName(tag);
    if (nodes.getLength() == 0) return def;
    Element child = (Element) nodes.item(0);
    String val = child.getAttribute("value");
    if (val == null || val.isEmpty()) val = child.getTextContent();
    if (val == null || val.isBlank()) return def;
    try {
      return DelugeHexMapper.hexToSustain(val.trim());
    } catch (Exception e) {
      return def;
    }
  }

  public static LfoType parseLfoType(String type) {
    if (type == null) return LfoType.SINE;
    String clean =
        type.trim()
            .toUpperCase()
            .replace("&AMP;", "&")
            .replace("&amp;", "&")
            .replace("_", "")
            .replace(" ", "");
    return switch (clean) {
      case "SAW" -> LfoType.SAW;
      case "SQUARE" -> LfoType.SQUARE;
      case "TRIANGLE" -> LfoType.TRIANGLE;
      case "S_AND_H", "SAMPLEANDHOLD", "S&H" -> LfoType.S_AND_H;
      case "RANDOM_WALK", "RANDOMWALK", "RANDOM" -> LfoType.RANDOM_WALK;
      case "WARBLER" -> LfoType.WARBLER;
      case "CUSTOM" -> LfoType.CUSTOM;
      default -> LfoType.SINE;
    };
  }

  public static ClipModel.PlayDirection readPlayDirectionAttr(Element el) {
    String directionAttr = el.getAttribute("sequenceDirection");
    if (directionAttr.isEmpty()) {
      directionAttr = el.getAttribute("sequenceDirectionMode");
    }
    if (!directionAttr.isEmpty()) {
      try {
        String clean = directionAttr.toUpperCase().replace("_", "");
        if ("PINGPONG".equals(clean)) {
          return ClipModel.PlayDirection.PING_PONG;
        } else {
          return ClipModel.PlayDirection.valueOf(directionAttr.toUpperCase());
        }
      } catch (IllegalArgumentException iae) {
        try {
          int val = Integer.parseInt(directionAttr);
          return switch (val) {
            case 1 -> ClipModel.PlayDirection.REVERSE;
            case 2 -> ClipModel.PlayDirection.PING_PONG;
            case 3 -> ClipModel.PlayDirection.RANDOM;
            default -> ClipModel.PlayDirection.FORWARD;
          };
        } catch (NumberFormatException nfe) {
          return ClipModel.PlayDirection.FORWARD;
        }
      }
    }
    return ClipModel.PlayDirection.FORWARD;
  }

  public static float toUnipolar(float f) {
    return (f + 1.0f) / 2.0f;
  }

  public static void readAttrFloatHex(
      Element el, String attr, java.util.function.Consumer<Float> setter, boolean unipolar) {
    String val = readAttr(el, attr);
    if (val != null && !val.isEmpty()) {
      float f = DelugeHexMapper.hexToFloat(val);
      if (unipolar) f = toUnipolar(f);
      setter.accept(f);
    }
  }

  public static void readAttrBool(
      Element el, String attr, java.util.function.Consumer<Boolean> setter) {
    String val = readAttr(el, attr);
    if (val != null) {
      setter.accept("true".equalsIgnoreCase(val) || "1".equals(val));
    }
  }

  public static String attrOrChildText(Element el, String name) {
    String val = readAttr(el, name);
    if (val == null || val.isEmpty()) {
      val = getChildText(el, name);
    }
    return val;
  }

  public static int attrOrChildInt(Element parent, String tag, int def) {
    String val = readAttr(parent, tag);
    if (val == null || val.isEmpty()) {
      val = getChildText(parent, tag);
    }
    if (val != null && !val.isBlank()) {
      try {
        return Integer.parseInt(val.trim());
      } catch (NumberFormatException e) {
        return def;
      }
    }
    return def;
  }

  public static int childInt(Element parent, String tag, int def) {
    return getChildInteger(parent, tag, def);
  }

  public static int soundQ31(Element soundNode, String tag, int def) {
    NodeList nodes = soundNode.getElementsByTagName(tag);
    if (nodes.getLength() == 0) return def;
    Element child = (Element) nodes.item(0);
    String val = child.getAttribute("value");
    if (val == null || val.isEmpty()) val = child.getTextContent();
    if (val != null && !val.isBlank()) {
      try {
        return DelugeHexMapper.hexToQ31(val.trim());
      } catch (Exception e) {
        return def;
      }
    }
    return def;
  }

  public static EnvelopeModel parseEnvelopeElement(Element envEl) {
    float attack = readHexEnvTime(envEl, "attack", 0.01f);
    float decay = readHexEnvTime(envEl, "decay", 0.1f);
    float sustain = readHexSustain(envEl, "sustain", 0.7f);
    float release = readHexEnvTime(envEl, "release", 0.2f);
    return new EnvelopeModel(attack, decay, sustain, release, "NONE", 0.0f);
  }

  public static void parseSampleRangeZones(Element sampleRangesEl, List<KeyZone> out) {
    NodeList ranges = sampleRangesEl.getElementsByTagName("sampleRange");
    if (ranges.getLength() > 0) {
      int prevTop = -1;
      for (int i = 0; i < ranges.getLength(); i++) {
        Element range = (Element) ranges.item(i);
        KeyZone kz = new KeyZone();
        kz.samplePath = range.getAttribute("fileName");
        if (kz.samplePath.isEmpty()) kz.samplePath = getChildText(range, "fileName");
        boolean last = (i == ranges.getLength() - 1);
        int top = attrOrChildInt(range, "rangeTopNote", last ? 127 : prevTop + 1);
        kz.minPitch = prevTop + 1;
        kz.maxPitch = last ? 127 : top;
        prevTop = top;
        kz.transpose = attrOrChildInt(range, "transpose", 0);
        kz.cents = attrOrChildInt(range, "cents", 0);
        Element zone = getFirstChild(range, "zone");
        if (zone != null) {
          kz.startSamplePos = attrOrChildInt(zone, "startSamplePos", 0);
          kz.endSamplePos = attrOrChildInt(zone, "endSamplePos", -1);
          kz.startLoopPos = attrOrChildInt(zone, "startLoopPos", -1);
          kz.endLoopPos = attrOrChildInt(zone, "endLoopPos", -1);
        }
        if (!kz.samplePath.isEmpty()) out.add(kz);
      }
      return;
    }
    NodeList zones = sampleRangesEl.getElementsByTagName("zone");
    for (int i = 0; i < zones.getLength(); i++) {
      Element zone = (Element) zones.item(i);
      KeyZone kz = new KeyZone();
      kz.samplePath = zone.getAttribute("fileName");
      if (kz.samplePath.isEmpty()) kz.samplePath = getChildText(zone, "fileName");
      kz.minPitch = readIntAttr(zone, "minPitch", 0);
      kz.maxPitch = readIntAttr(zone, "maxPitch", 127);
      kz.minVelocity = readIntAttr(zone, "minVelocity", 0);
      kz.maxVelocity = readIntAttr(zone, "maxVelocity", 127);
      kz.startSamplePos = readIntAttr(zone, "startSamplePos", 0);
      kz.endSamplePos = readIntAttr(zone, "endSamplePos", -1);
      kz.startLoopPos = readIntAttr(zone, "startLoopPos", -1);
      kz.endLoopPos = readIntAttr(zone, "endLoopPos", -1);
      kz.looping = "1".equals(zone.getAttribute("loopMode"));
      if (!kz.samplePath.isEmpty()) out.add(kz);
    }
  }

  public static PatchCable parseSinglePatchCable(Element cableElem) {
    String src = cableElem.getAttribute("source");
    if (src == null || src.isEmpty()) src = getChildText(cableElem, "source");
    String dst = cableElem.getAttribute("destination");
    if (dst == null || dst.isEmpty()) dst = getChildText(cableElem, "destination");
    String amtStr = cableElem.getAttribute("amount");
    if (amtStr == null || amtStr.isEmpty()) amtStr = getChildText(cableElem, "amount");

    if (src == null || src.isEmpty() || amtStr == null || amtStr.isEmpty()) {
      return null;
    }
    if (dst == null) dst = "";

    String polarityStr = cableElem.getAttribute("polarity");
    if (polarityStr == null || polarityStr.isEmpty()) {
      polarityStr = getChildText(cableElem, "polarity");
    }
    PatchCable.Polarity polarityVal = PatchCable.Polarity.BIPOLAR;
    if (polarityStr != null && !polarityStr.isEmpty()) {
      if ("unipolar".equalsIgnoreCase(polarityStr.trim())) {
        polarityVal = PatchCable.Polarity.UNIPOLAR;
      } else if ("bipolar".equalsIgnoreCase(polarityStr.trim())) {
        polarityVal = PatchCable.Polarity.BIPOLAR;
      }
    } else {
      if ("aftertouch".equalsIgnoreCase(src.trim())) {
        polarityVal = PatchCable.Polarity.UNIPOLAR;
      } else {
        polarityVal = PatchCable.Polarity.BIPOLAR;
      }
    }

    float amt = PatchCable.applyScaling(dst.trim(), DelugeHexMapper.hexToFloat(amtStr));

    List<PatchCable> depthCables = new ArrayList<>();
    Element depthParent = getFirstChild(cableElem, "depthControlledBy");
    if (depthParent != null) {
      NodeList nestedList = depthParent.getChildNodes();
      for (int j = 0; j < nestedList.getLength(); j++) {
        if (nestedList.item(j) instanceof Element nestedEl
            && "patchCable".equals(nestedEl.getTagName())) {
          PatchCable dc = parseSinglePatchCable(nestedEl);
          if (dc != null) {
            depthCables.add(dc);
          }
        }
      }
    }

    return new PatchCable(src.trim(), dst.trim(), amt, polarityVal, depthCables);
  }

  public static void parseDefaultVelocity(Element node, TrackModel track) {
    String velAttr = node.getAttribute("defaultVelocity");
    if (velAttr != null && !velAttr.isEmpty()) {
      try {
        track.setDefaultVelocity(Integer.parseInt(velAttr.trim()));
      } catch (NumberFormatException ignored) {
      }
    } else {
      NodeList velNodes = node.getElementsByTagName("defaultVelocity");
      if (velNodes.getLength() > 0) {
        try {
          track.setDefaultVelocity(Integer.parseInt(velNodes.item(0).getTextContent().trim()));
        } catch (NumberFormatException ignored) {
        }
      }
    }
  }

  public static void parseClippingAmount(Element node, TrackModel track) {
    String clipStr = node.getAttribute("clippingAmount");
    if (clipStr == null || clipStr.isEmpty()) {
      NodeList clipNodes = node.getElementsByTagName("clippingAmount");
      if (clipNodes.getLength() > 0) {
        clipStr = clipNodes.item(0).getTextContent().trim();
      }
    }
    if (clipStr != null && !clipStr.isEmpty()) {
      try {
        track.setClippingAmount(Integer.parseInt(clipStr));
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "Error parsing clippingAmount", e);
      }
    }
  }

  public static float modulatorRatio(int transpose, int cents) {
    float semitones = transpose + (cents / 100.0f);
    return (float) Math.pow(2.0, semitones / 12.0);
  }

  public static void parseMidiKnobs(Element soundNode, List<MidiKnob> midiKnobs) {
    NodeList midiKnobsContainer = soundNode.getElementsByTagName("midiKnobs");
    if (midiKnobsContainer.getLength() == 0) return;
    Element container = (Element) midiKnobsContainer.item(0);
    NodeList knobList = container.getElementsByTagName("midiKnob");
    for (int i = 0; i < knobList.getLength(); i++) {
      Element knobElem = (Element) knobList.item(i);

      int channel = 1;
      if (knobElem.hasAttribute("channel")) {
        channel = Integer.parseInt(knobElem.getAttribute("channel"));
      }

      int ccNumber = 0;
      if (knobElem.hasAttribute("ccNumber")) {
        ccNumber = Integer.parseInt(knobElem.getAttribute("ccNumber"));
      }

      boolean relative =
          "1".equals(knobElem.getAttribute("relative"))
              || "true".equalsIgnoreCase(knobElem.getAttribute("relative"));

      String controlsParam = knobElem.getAttribute("controlsParam");
      if (controlsParam == null) {
        controlsParam = getChildText(knobElem, "controlsParam");
      }
      if (controlsParam == null) {
        controlsParam = "NONE";
      }

      String patchSource = knobElem.getAttribute("patchAmountFromSource");
      if (patchSource == null) {
        patchSource = getChildText(knobElem, "patchAmountFromSource");
      }
      if (patchSource == null) {
        patchSource = "NONE";
      }

      midiKnobs.add(new MidiKnob(channel, ccNumber, relative, controlsParam, patchSource));
    }
  }

  public static String nodeToXmlString(org.w3c.dom.Node node) {
    try {
      javax.xml.transform.Transformer transformer =
          javax.xml.transform.TransformerFactory.newInstance().newTransformer();
      transformer.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes");
      transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes");
      java.io.StringWriter writer = new java.io.StringWriter();
      transformer.transform(
          new javax.xml.transform.dom.DOMSource(node),
          new javax.xml.transform.stream.StreamResult(writer));
      return writer.toString();
    } catch (Exception ex) {
      return "";
    }
  }

  public static int convertSyncLevelFromFileValueToInternalValue(
      int fileValue, int inputTickMagnitude) {
    if (fileValue == 0) {
      return 0; // 0 means "off"
    }
    int internalValue = fileValue + 1 - inputTickMagnitude;
    if (internalValue < 1) {
      internalValue = 1;
    } else if (internalValue > 9) {
      internalValue = 9;
    }
    return internalValue;
  }

  public static int convertSyncLevelFromInternalValueToFileValue(
      int internalValue, int inputTickMagnitude) {
    if (internalValue == 0) {
      return 0; // 0 means "off"
    }
    int fileValue = internalValue - 1 + inputTickMagnitude;
    if (fileValue < 1) {
      fileValue = 1;
    }
    return fileValue;
  }
}
