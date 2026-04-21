package org.chuck.deluge.xml;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.chuck.deluge.model.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class DelugeXmlParser {

  public static KitTrackModel parseKit(java.io.File xmlFile) throws Exception {
    return parseKit(new java.io.FileInputStream(xmlFile), xmlFile.getName().replace(".XML", ""));
  }

  public static KitTrackModel parseKit(InputStream is, String name) throws Exception {
    Document doc = parseXml(is);
    Element root = doc.getDocumentElement();

    Element kitNode = root;
    if (!root.getTagName().equals("kit")) {
      NodeList kits = root.getElementsByTagName("kit");
      if (kits.getLength() > 0) {
        kitNode = (Element) kits.item(0);
      }
    }

    KitTrackModel kit = new KitTrackModel(name);

    NodeList soundNodes = kitNode.getElementsByTagName("sound");
    for (int i = 0; i < soundNodes.getLength(); i++) {
      Element soundNode = (Element) soundNodes.item(i);
      String soundName = "SOUND " + i;

      NodeList nameNodes = soundNode.getElementsByTagName("name");
      if (nameNodes.getLength() > 0) {
        soundName = nameNodes.item(0).getTextContent();
      }

      KitTrackModel.KitSound sound = new KitTrackModel.KitSound(soundName);

      NodeList sampleNodes = soundNode.getElementsByTagName("sample");
      if (sampleNodes.getLength() > 0) {
        Element sampleNode = (Element) sampleNodes.item(0);
        if (sampleNode.hasAttribute("fileName")) {
          sound.setSamplePath(sampleNode.getAttribute("fileName"));
        }
      } else {
        // Some kits use osc1/fileName
        NodeList oscNodes = soundNode.getElementsByTagName("osc1");
        if (oscNodes.getLength() > 0) {
          Element osc = (Element) oscNodes.item(0);
          NodeList fnNodes = osc.getElementsByTagName("fileName");
          if (fnNodes.getLength() > 0) {
            sound.setSamplePath(fnNodes.item(0).getTextContent());
          }
        }
      }

      kit.addSound(sound);
    }

    return kit;
  }

  public static SynthTrackModel parseSynth(java.io.File xmlFile) throws Exception {
    return parseSynth(new java.io.FileInputStream(xmlFile), xmlFile.getName().replace(".XML", ""));
  }

  public static SynthTrackModel parseSynth(InputStream is, String name) throws Exception {
    Document doc = parseXml(is);
    Element root = doc.getDocumentElement();

    Element soundNode = root;
    if (!root.getTagName().equals("sound")) {
      NodeList sounds = root.getElementsByTagName("sound");
      if (sounds.getLength() > 0) {
        soundNode = (Element) sounds.item(0);
      }
    }

    SynthTrackModel synth = new SynthTrackModel(name);

    NodeList osc1Nodes = soundNode.getElementsByTagName("osc1");
    if (osc1Nodes.getLength() > 0) {
      Element osc1 = (Element) osc1Nodes.item(0);
      if (osc1.hasAttribute("type")) {
        synth.setOsc1Type(osc1.getAttribute("type").toUpperCase());
      }
    }

    // Map Envelope 0-3
    NodeList envNodes = soundNode.getElementsByTagName("envelope");
    for (int i = 0; i < Math.min(4, envNodes.getLength()); i++) {
      Element envNode = (Element) envNodes.item(i);
      EnvelopeModel env =
          new EnvelopeModel(
              DelugeHexMapper.hexToFloat(envNode.getAttribute("attack")),
              DelugeHexMapper.hexToFloat(envNode.getAttribute("decay")),
              DelugeHexMapper.hexToFloat(envNode.getAttribute("sustain")),
              DelugeHexMapper.hexToFloat(envNode.getAttribute("release")),
              "NONE",
              0.0f);
      synth.setEnv(i, env);
    }

    return synth;
  }

  private static Document parseXml(InputStream is) throws Exception {
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
    // Re-read from wrapped string
    return builder.parse(new ByteArrayInputStream(wrapped.getBytes(StandardCharsets.UTF_8)));
  }
}
