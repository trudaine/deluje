package org.chuck.deluge.xml;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.chuck.deluge.model.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class DelugeXmlParser {

  // ── Declarative bindings for simple synth fields ──

  private static final List<FieldBinding<?>> DIRECT_BINDINGS = List.of(
    // osc1 type: attribute or child element
    FieldBinding.attrOrChild("osc1", "type", SynthTrackModel::setOsc1Type, String::toUpperCase),
    // osc2 type: child element only
    FieldBinding.childText("osc2", "type", SynthTrackModel::setOsc2Type, String::toUpperCase)
  );

  private static final List<FieldBinding<?>> DEFAULT_PARAMS_BINDINGS = List.of(
    FieldBinding.hexHz("defaultParams", "lpfFrequency",    SynthTrackModel::setLpfFreq),
    FieldBinding.hexFloat("defaultParams", "lpfResonance",    SynthTrackModel::setLpfRes),
    FieldBinding.hexHz("defaultParams", "hpfFrequency",    SynthTrackModel::setHpfFreq),
    FieldBinding.hexFloat("defaultParams", "hpfResonance",    SynthTrackModel::setHpfRes),
    FieldBinding.hexFloat("defaultParams", "modulator1Feedback", SynthTrackModel::setModulator1Feedback),
    FieldBinding.hexFloat("defaultParams", "modulator2Amount",   SynthTrackModel::setModulator2Amount),
    FieldBinding.hexFloat("defaultParams", "modulator2Feedback", SynthTrackModel::setModulator2Feedback),
    FieldBinding.hexFloat("defaultParams", "carrier1Feedback",   SynthTrackModel::setCarrier1Feedback),
    FieldBinding.hexFloat("defaultParams", "carrier2Feedback",   SynthTrackModel::setCarrier2Feedback)
  );

  // ── Public entry points ──

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
        } else {
          // Some XML stores fileName as child element rather than attribute
          NodeList fnNodes = sampleNode.getElementsByTagName("fileName");
          if (fnNodes.getLength() > 0) {
            String fn = fnNodes.item(0).getTextContent();
            if (fn != null && !fn.isBlank()) {
              sound.setSamplePath(fn);
            }
          }
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
    populateSynth(soundNode, synth);
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
      String sw = songNode.getAttribute("swing");
      if (sw.startsWith("0x")) {
        project.setSwing(DelugeHexMapper.hexToFloat(sw));
      } else {
        project.setSwing(Float.parseFloat(sw));
      }
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
          if (trackElem.hasAttribute("colour")) {
            targetTrack.setColourHex(trackElem.getAttribute("colour"));
          }

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

  // ── Package-private helper: used by both parseSynth and parseSynthElement ──

  static void populateSynth(Element soundNode, SynthTrackModel synth) {
    // Direct child bindings (osc1 attr/or child type, osc2 child type)
    applyDirectBindings(soundNode, synth);

    // ── Synth Mode ──
    parseSynthMode(soundNode, synth);

    // ── Polyphonic mode ──
    parsePolyphony(soundNode, synth);

    // ── LPF Mode ──
    parseFilterMode(soundNode, synth);

    // ── FM Modulator 1 ──
    parseModulator1(soundNode, synth);

    // ── Envelopes 0-3 ──
    parseEnvelopes(soundNode, synth);

    // ── defaultParams bindings (LPF/HPF freq+res, FM feedback params) ──
    applyDefaultParamsBindings(soundNode, synth);

    // ── Patch Cables ──
    parsePatchCables(soundNode, synth);

    // ── Mod Knobs ──
    parseModKnobs(soundNode, synth);
  }

  // ── Kit/song element parsers ──

  private static KitTrackModel parseKitElement(Element kitNode) throws Exception {
    String name = "KIT";

    NodeList slotNodes = kitNode.getElementsByTagName("presetSlot");
    if (slotNodes.getLength() > 0) {
      name = "KIT " + slotNodes.item(0).getTextContent();
    }

    NodeList soundNodes = kitNode.getElementsByTagName("sound");

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
      boolean found = false;
      if (sampleNodes.getLength() > 0) {
        Element sampleNode = (Element) sampleNodes.item(0);
        if (sampleNode.hasAttribute("fileName")) {
          sound.setSamplePath(sampleNode.getAttribute("fileName"));
          found = true;
        } else {
          NodeList fnNodes = sampleNode.getElementsByTagName("fileName");
          if (fnNodes.getLength() > 0) {
            String fn = fnNodes.item(0).getTextContent();
            if (fn != null && !fn.isBlank()) {
              sound.setSamplePath(fn);
              found = true;
            }
          }
        }
      }
      // Fallback: try osc1/fileName (song instrument nodes use this structure)
      if (!found) {
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

  private static SynthTrackModel parseSynthElement(Element soundNode) throws Exception {
    String name = "SYNTH";

    NodeList slotNodes = soundNode.getElementsByTagName("presetSlot");
    if (slotNodes.getLength() > 0) {
      name = "SYNTH " + slotNodes.item(0).getTextContent();
    }

    SynthTrackModel synth = new SynthTrackModel(name);
    populateSynth(soundNode, synth);
    return synth;
  }

  // ── Complex sub-parsers (don't fit simple tag→value bindings) ──

  private static void applyDirectBindings(Element soundNode, SynthTrackModel synth) {
    for (FieldBinding<?> b : DIRECT_BINDINGS) {
      b.apply(soundNode, synth);
    }
  }

  private static void applyDefaultParamsBindings(Element soundNode, SynthTrackModel synth) {
    for (FieldBinding<?> b : DEFAULT_PARAMS_BINDINGS) {
      b.apply(soundNode, synth);
    }
  }

  private static void parseSynthMode(Element soundNode, SynthTrackModel synth) {
    NodeList modeNodes = soundNode.getElementsByTagName("mode");
    if (modeNodes.getLength() == 0) return;
    String mode = modeNodes.item(0).getTextContent().trim().toLowerCase();
    switch (mode) {
      case "fm" -> synth.setSynthMode(1);
      case "ringmod" -> synth.setSynthMode(2);
      default -> synth.setSynthMode(0);
    }
  }

  private static void parsePolyphony(Element soundNode, SynthTrackModel synth) {
    NodeList polyNodes = soundNode.getElementsByTagName("polyphonic");
    if (polyNodes.getLength() == 0) return;
    String val = polyNodes.item(0).getTextContent().trim().toLowerCase();
    switch (val) {
      case "mono":
      case "0":
        synth.setPolyphony(SynthTrackModel.PolyphonyMode.MONO);
        break;
      case "legato":
        synth.setPolyphony(SynthTrackModel.PolyphonyMode.LEGATO);
        break;
      default:
        synth.setPolyphony(SynthTrackModel.PolyphonyMode.POLY);
        break;
    }
  }

  private static void parseFilterMode(Element soundNode, SynthTrackModel synth) {
    NodeList modeNodes = soundNode.getElementsByTagName("lpfMode");
    if (modeNodes.getLength() == 0) return;
    String lpfMode = modeNodes.item(0).getTextContent().trim();
    if ("24dB".equals(lpfMode)) {
      synth.setFilterMode(FilterMode.LADDER_24);
    } else {
      synth.setFilterMode(FilterMode.LADDER_12);
    }
  }

  private static void parseModulator1(Element soundNode, SynthTrackModel synth) {
    NodeList mod1Nodes = soundNode.getElementsByTagName("modulator1");
    if (mod1Nodes.getLength() > 0) {
      Element mod1 = (Element) mod1Nodes.item(0);
      NodeList transpNodes = mod1.getElementsByTagName("transpose");
      if (transpNodes.getLength() > 0) {
        try {
          int transpose = Integer.parseInt(transpNodes.item(0).getTextContent().trim());
          synth.setFmRatio((float) Math.pow(2.0, transpose / 12.0));
        } catch (NumberFormatException ignored) {}
      }
    }
    NodeList mod1AmtNodes = soundNode.getElementsByTagName("modulator1Amount");
    if (mod1AmtNodes.getLength() > 0) {
      float hexVal = DelugeHexMapper.hexToFloat(mod1AmtNodes.item(0).getTextContent());
      synth.setFmAmount(Math.abs(hexVal));
    }
  }

  private static void parseEnvelopes(Element soundNode, SynthTrackModel synth) {
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
  }

  private static void parsePatchCables(Element soundNode, SynthTrackModel synth) {
    NodeList cableList = soundNode.getElementsByTagName("patchCable");
    for (int i = 0; i < cableList.getLength(); i++) {
      Element cableElem = (Element) cableList.item(i);
      String src = getChildText(cableElem, "source");
      String dst = getChildText(cableElem, "destination");
      String amtStr = getChildText(cableElem, "amount");
      if (src != null && dst != null && amtStr != null) {
        float amt = PatchCable.applyScaling(dst, DelugeHexMapper.hexToFloat(amtStr));
        synth.addPatchCable(new PatchCable(src, dst, amt));
      }
    }
  }

  private static void parseModKnobs(Element soundNode, SynthTrackModel synth) {
    NodeList knobList = soundNode.getElementsByTagName("modKnob");
    for (int i = 0; i < knobList.getLength(); i++) {
      Element knobElem = (Element) knobList.item(i);
      String param = getChildText(knobElem, "controlsParam");
      if (param != null && i < synth.getModKnobs().size()) {
        synth.setModKnob(i, new ModKnob(param, "NONE"));
      }
    }
  }

  // ── Helpers ──

  /** Gets the text content of the first child element with {@code tag} under {@code parent}, or null if absent/blank. */
  private static String getChildText(Element parent, String tag) {
    NodeList nodes = parent.getElementsByTagName(tag);
    if (nodes.getLength() > 0) {
      String text = nodes.item(0).getTextContent();
      return (text == null || text.isBlank()) ? null : text.trim();
    }
    return null;
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
