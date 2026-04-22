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

    // Parse Filter defaults from defaultParams
    NodeList defParams = soundNode.getElementsByTagName("defaultParams");
    if (defParams.getLength() > 0) {
      Element def = (Element) defParams.item(0);
      NodeList lpfFreq = def.getElementsByTagName("lpfFrequency");
      if (lpfFreq.getLength() > 0) {
        float val = DelugeHexMapper.hexToFloat(lpfFreq.item(0).getTextContent());
        synth.setLpfFreq(val * 20000.0f); // Map normalized to freq
      }
      NodeList lpfRes = def.getElementsByTagName("lpfResonance");
      if (lpfRes.getLength() > 0) {
        float val = DelugeHexMapper.hexToFloat(lpfRes.item(0).getTextContent());
        synth.setLpfRes(val);
      }
    }

    return synth;
  }

  public static ProjectModel parseSong(java.io.File xmlFile) throws Exception {
    return parseSong(new java.io.FileInputStream(xmlFile), xmlFile.getName().replace(".XML", ""));
  }

  public static ProjectModel parseSong(InputStream is, String name) throws Exception {
    Document doc = parseXml(is);
    Element root = doc.getDocumentElement();

    Element songNode = root;
    if (!root.getTagName().equals("song")) {
      NodeList songs = root.getElementsByTagName("song");
      if (songs.getLength() > 0) {
        songNode = (Element) songs.item(0);
      }
    }

    ProjectModel project = new ProjectModel();

    if (songNode.hasAttribute("tempo")) {
      project.setBpm(Float.parseFloat(songNode.getAttribute("tempo")));
    }
    if (songNode.hasAttribute("swing")) {
      project.setSwing(Float.parseFloat(songNode.getAttribute("swing")));
    }
    if (songNode.hasAttribute("key")) {
      project.setKey(songNode.getAttribute("key"));
    }
    if (songNode.hasAttribute("scale")) {
      project.setScale(songNode.getAttribute("scale"));
    }

    // 1. Parse Instruments
    NodeList instNodes = songNode.getElementsByTagName("instruments");
    if (instNodes.getLength() > 0) {
      Element instruments = (Element) instNodes.item(0);
      // Parse Kits
      NodeList kitNodes = instruments.getElementsByTagName("kit");
      for (int i = 0; i < kitNodes.getLength(); i++) {
        Element kitNode = (Element) kitNodes.item(i);
        if (kitNode.getParentNode() == instruments) {
          KitTrackModel kit = parseKitElement(kitNode);
          project.addTrack(kit);
          System.out.println("PARSER: Loaded kit track " + kit.getName());
        }
      }

      NodeList soundNodes = instruments.getElementsByTagName("sound");
      for (int i = 0; i < soundNodes.getLength(); i++) {
        Element soundNode = (Element) soundNodes.item(i);
        if (soundNode.getParentNode() == instruments) {
          SynthTrackModel synth = parseSynthElement(soundNode);
          project.addTrack(synth);
          System.out.println("PARSER: Loaded synth track " + synth.getName());
        }
      }
    }

    // 2. Parse Tracks (Clips)
    NodeList tracksNodes = songNode.getElementsByTagName("tracks");
    if (tracksNodes.getLength() > 0) {
      Element tracks = (Element) tracksNodes.item(0);
      NodeList trackList = tracks.getElementsByTagName("track");

      System.out.println("PARSER: Found " + trackList.getLength() + " tracks in XML");

      for (int i = 0; i < trackList.getLength(); i++) {
        Element trackElem = (Element) trackList.item(i);

        java.util.List<TrackModel> projectTracks = project.getTracks();
        if (i < projectTracks.size()) {
          TrackModel targetTrack = projectTracks.get(i);

          NodeList noteRowsList = trackElem.getElementsByTagName("noteRows");
          if (noteRowsList.getLength() > 0) {
            Element noteRowsElem = (Element) noteRowsList.item(0);
            NodeList noteRowList = noteRowsElem.getElementsByTagName("noteRow");

            int rowCount = noteRowList.getLength();
            int stepCount = 16; // Default

            ClipModel clip = new ClipModel("CLIP " + i, rowCount, stepCount);
            System.out.println(
                "PARSER: Created clip "
                    + clip.getName()
                    + " for track "
                    + targetTrack.getName()
                    + " with rows "
                    + rowCount);

            for (int r = 0; r < rowCount; r++) {
              Element noteRowElem = (Element) noteRowList.item(r);
              NodeList noteDataList = noteRowElem.getElementsByTagName("noteData");
              if (noteDataList.getLength() > 0) {
                String hexData = noteDataList.item(0).getTextContent();
                java.util.List<StepData> row = DelugeNoteDataMapper.decodeRow(hexData, stepCount);

                for (int s = 0; s < stepCount; s++) {
                  clip.setStep(r, s, row.get(s));
                }
              }
            }
            targetTrack.addClip(clip);
          }
        } else {
          System.out.println(
              "PARSER: Track index "
                  + i
                  + " out of bounds for project tracks size "
                  + projectTracks.size());
        }
      }
    }

    return project;
  }

  private static KitTrackModel parseKitElement(Element kitNode) throws Exception {
    String name = "KIT";

    NodeList slotNodes = kitNode.getElementsByTagName("presetSlot");
    if (slotNodes.getLength() > 0) {
      name = "KIT " + slotNodes.item(0).getTextContent();
    }

    NodeList soundNodes = kitNode.getElementsByTagName("sound");
    if (soundNodes.getLength() > 0) {
      Element soundNode = (Element) soundNodes.item(0);
      NodeList sampleNodes = soundNode.getElementsByTagName("sample");
      if (sampleNodes.getLength() > 0) {
        Element sampleNode = (Element) sampleNodes.item(0);
        if (sampleNode.hasAttribute("fileName")) {
          String fileName = sampleNode.getAttribute("fileName");
          String baseName = new java.io.File(fileName).getName();
          int extIdx = baseName.lastIndexOf('.');
          if (extIdx > 0) {
            baseName = baseName.substring(0, extIdx);
          }
          if (baseName.length() > 7) {
            baseName = baseName.substring(0, 7);
          }
          name = baseName;
        }
      }
    } else {
      // Load from preset!
      if (slotNodes.getLength() > 0) {
        int slot = Integer.parseInt(slotNodes.item(0).getTextContent());
        String fileName = null;
        if (slot == 0) fileName = "/KITS/000 TR-808.XML";
        else if (slot == 1) fileName = "/KITS/001 DDD-1.XML";
        else if (slot == 2) fileName = "/KITS/002 SDS-5.XML";

        if (fileName != null) {
          try (java.io.InputStream is = DelugeXmlParser.class.getResourceAsStream(fileName)) {
            if (is != null) {
              System.out.println("PARSER: Loading preset kit from " + fileName);
              return parseKit(is, "KIT " + slot);
            }
          }
        }
      }
    }

    KitTrackModel kit = new KitTrackModel(name);

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
      }
      kit.addSound(sound);
    }
    return kit;
  }

  private static SynthTrackModel parseSynthElement(Element soundNode) throws Exception {
    String name = "SYNTH";

    NodeList slotNodes = soundNode.getElementsByTagName("presetSlot");
    if (slotNodes.getLength() > 0) {
      int slot = Integer.parseInt(slotNodes.item(0).getTextContent());
      String fileName = null;
      if (slot == 0) fileName = "/SYNTHS/000 Rich Saw Bass.XML";
      else if (slot == 17) fileName = "/SYNTHS/017 Impact Saw Lead.XML";
      else if (slot == 73) fileName = "/SYNTHS/073 Piano.XML";

      if (fileName != null) {
        try (java.io.InputStream is = DelugeXmlParser.class.getResourceAsStream(fileName)) {
          if (is != null) {
            System.out.println("PARSER: Loading preset synth from " + fileName);
            return parseSynth(is, "SYNTH " + slot);
          }
        }
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
