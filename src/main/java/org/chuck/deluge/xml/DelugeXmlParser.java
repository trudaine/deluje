package org.chuck.deluge.xml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.chuck.deluge.model.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Parses Deluge XML files (.XML) into our Java Track/Project Models. Supports Files and Resource Streams. */
public class DelugeXmlParser {

  public static KitTrackModel parseKit(File xmlFile) throws Exception {
    try (FileInputStream fis = new FileInputStream(xmlFile)) {
      return parseKit(fis, xmlFile.getName());
    }
  }

  public static KitTrackModel parseKit(InputStream is, String name) throws Exception {
    Document doc = parseXmlStream(is);
    Element root = doc.getDocumentElement();
    KitTrackModel kit = new KitTrackModel(name.replace(".XML", ""));

    if (root.hasAttribute("name")) {
      kit.setName(root.getAttribute("name"));
    }

    NodeList sampleNodes = doc.getElementsByTagName("sample");
    if (sampleNodes.getLength() > 0) {
      Element sampleNode = (Element) sampleNodes.item(0);
      if (sampleNode.hasAttribute("fileName")) {
        kit.setSamplePath(sampleNode.getAttribute("fileName"));
      }
    }
    return kit;
  }

  public static SynthTrackModel parseSynth(File xmlFile) throws Exception {
    try (FileInputStream fis = new FileInputStream(xmlFile)) {
      return parseSynth(fis, xmlFile.getName());
    }
  }

  public static SynthTrackModel parseSynth(InputStream is, String name) throws Exception {
    Document doc = parseXmlStream(is);
    Element root = doc.getDocumentElement();
    SynthTrackModel synth = new SynthTrackModel(name.replace(".XML", ""));

    NodeList osc1Nodes = doc.getElementsByTagName("osc1");
    if (osc1Nodes.getLength() > 0) {
      Element osc1 = (Element) osc1Nodes.item(0);
      if (osc1.hasAttribute("type")) {
        synth.setOsc1Type(osc1.getAttribute("type").toUpperCase());
      }
    }

    NodeList envNodes = doc.getElementsByTagName("envelope");
    for (int i = 0; i < Math.min(4, envNodes.getLength()); i++) {
      Element envNode = (Element) envNodes.item(i);
      EnvelopeModel env = new EnvelopeModel(
          DelugeHexMapper.hexToFloat(envNode.getAttribute("attack")),
          DelugeHexMapper.hexToFloat(envNode.getAttribute("decay")),
          DelugeHexMapper.hexToFloat(envNode.getAttribute("sustain")),
          DelugeHexMapper.hexToFloat(envNode.getAttribute("release")),
          "NONE", 0.0f);
      synth.setEnv(i, env);
    }

    NodeList patchNodes = doc.getElementsByTagName("patchCables");
    if (patchNodes.getLength() > 0) {
      NodeList cables = ((Element) patchNodes.item(0)).getElementsByTagName("patchCable");
      for (int i = 0; i < cables.getLength(); i++) {
        Element cable = (Element) cables.item(i);
        String source = cable.getAttribute("source");
        String dest = cable.getAttribute("destination");
        float amount = DelugeHexMapper.hexToFloat(cable.getAttribute("amount"));
        synth.addPatchCable(new PatchCable(source, dest, amount));
      }
    }
    return synth;
  }

  public static ProjectModel parseSong(File xmlFile) throws Exception {
    Document doc = parseXmlStream(new FileInputStream(xmlFile));
    Element root = doc.getDocumentElement();
    ProjectModel project = new ProjectModel();
    if (root.hasAttribute("tempo")) {
      project.setBpm(Float.parseFloat(root.getAttribute("tempo")));
    }
    if (root.hasAttribute("swing")) {
      project.setSwing(DelugeHexMapper.hexToFloat(root.getAttribute("swing")));
    }
    return project;
  }

  private static Document parseXmlStream(InputStream is) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    DocumentBuilder builder = factory.newDocumentBuilder();
    return builder.parse(is);
  }
}
