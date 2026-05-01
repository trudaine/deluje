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

    // ── Osc 1 ──
    NodeList osc1Nodes = soundNode.getElementsByTagName("osc1");
    if (osc1Nodes.getLength() > 0) {
      Element osc1 = (Element) osc1Nodes.item(0);
      if (osc1.hasAttribute("type")) {
        synth.setOsc1Type(osc1.getAttribute("type").toUpperCase());
      } else {
        // Some XMLs use <type> child element
        NodeList typeNodes = osc1.getElementsByTagName("type");
        if (typeNodes.getLength() > 0) {
          synth.setOsc1Type(typeNodes.item(0).getTextContent().toUpperCase());
        }
      }
    }

    // ── Osc 2 ──
    NodeList osc2Nodes = soundNode.getElementsByTagName("osc2");
    if (osc2Nodes.getLength() > 0) {
      Element osc2 = (Element) osc2Nodes.item(0);
      NodeList typeNodes = osc2.getElementsByTagName("type");
      if (typeNodes.getLength() > 0) {
        synth.setOsc2Type(typeNodes.item(0).getTextContent().toUpperCase());
      }
    }

    // ── Synth Mode ──
    // Reads <mode>fm</mode>, <mode>subtractive</mode>, <mode>ringmod</mode>
    NodeList modeNodes = soundNode.getElementsByTagName("mode");
    if (modeNodes.getLength() > 0) {
      String mode = modeNodes.item(0).getTextContent().trim().toLowerCase();
      switch (mode) {
        case "fm" -> synth.setSynthMode(1);
        case "ringmod" -> synth.setSynthMode(2);
        default -> synth.setSynthMode(0); // subtractive or absent
      }
    }

    // ── Polyphonic mode ──
    NodeList polyNodes = soundNode.getElementsByTagName("polyphonic");
    if (polyNodes.getLength() > 0) {
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

    // ── LPF Mode ──
    NodeList lpfModeNodes = soundNode.getElementsByTagName("lpfMode");
    if (lpfModeNodes.getLength() > 0) {
      String lpfMode = lpfModeNodes.item(0).getTextContent().trim();
      // Map to FilterMode enum
      if ("12dB".equals(lpfMode)) {
        synth.setFilterMode(FilterMode.LADDER_12);
      } else if ("24dB".equals(lpfMode)) {
        synth.setFilterMode(FilterMode.LADDER_24);
      } else {
        synth.setFilterMode(FilterMode.LADDER_12);
      }
    }

    // ── FM Modulator 1 (from <modulator1><transpose> + <modulator1Amount>) ──
    NodeList mod1Nodes = soundNode.getElementsByTagName("modulator1");
    if (mod1Nodes.getLength() > 0) {
      Element mod1 = (Element) mod1Nodes.item(0);
      NodeList transpNodes = mod1.getElementsByTagName("transpose");
      if (transpNodes.getLength() > 0) {
        try {
          int transpose = Integer.parseInt(transpNodes.item(0).getTextContent().trim());
          // FM ratio = 2^(transpose/12) — semitones to frequency multiplier
          synth.setFmRatio((float) Math.pow(2.0, transpose / 12.0));
        } catch (NumberFormatException ignored) {}
      }
    }
    NodeList mod1AmtNodes = soundNode.getElementsByTagName("modulator1Amount");
    if (mod1AmtNodes.getLength() > 0) {
      float hexVal = DelugeHexMapper.hexToFloat(mod1AmtNodes.item(0).getTextContent());
      // Signed hex knob position → 0-1 magnitude. Use abs so full-left (-1.0) = 0, center (0.0) = 0.5, full-right = 1.0
      synth.setFmAmount(Math.abs(hexVal));
    }

    // ── Envelopes 0-3 ──
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

    // ── Filter defaults from defaultParams ──
    NodeList defParams = soundNode.getElementsByTagName("defaultParams");
    if (defParams.getLength() > 0) {
      Element def = (Element) defParams.item(0);

      NodeList lpfFreq = def.getElementsByTagName("lpfFrequency");
      if (lpfFreq.getLength() > 0) {
        synth.setLpfFreq(DelugeHexMapper.hexToHz(lpfFreq.item(0).getTextContent()));
      }
      NodeList lpfRes = def.getElementsByTagName("lpfResonance");
      if (lpfRes.getLength() > 0) {
        float val = DelugeHexMapper.hexToFloat(lpfRes.item(0).getTextContent());
        synth.setLpfRes(val);
      }

      // HPF frequency + resonance
      NodeList hpfFreq = def.getElementsByTagName("hpfFrequency");
      if (hpfFreq.getLength() > 0) {
        synth.setHpfFreq(DelugeHexMapper.hexToHz(hpfFreq.item(0).getTextContent()));
      }
      NodeList hpfRes = def.getElementsByTagName("hpfResonance");
      if (hpfRes.getLength() > 0) {
        float val = DelugeHexMapper.hexToFloat(hpfRes.item(0).getTextContent());
        synth.setHpfRes(val);
      }

      // ── FM feedback params from defaultParams ──
      parseHexFloatParam(def, "modulator1Feedback", synth::setModulator1Feedback);
      parseHexFloatParam(def, "modulator2Amount", synth::setModulator2Amount);
      parseHexFloatParam(def, "modulator2Feedback", synth::setModulator2Feedback);
      parseHexFloatParam(def, "carrier1Feedback", synth::setCarrier1Feedback);
      parseHexFloatParam(def, "carrier2Feedback", synth::setCarrier2Feedback);
    }

    // ── Patch Cables ──
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

    // ── Mod Knobs ──
    NodeList knobList = soundNode.getElementsByTagName("modKnob");
    for (int i = 0; i < knobList.getLength(); i++) {
      Element knobElem = (Element) knobList.item(i);
      String param = getChildText(knobElem, "controlsParam");
      if (param != null && i < synth.getModKnobs().size()) {
        synth.setModKnob(i, new ModKnob(param, "NONE"));
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

    // Reuse the full parse logic by wrapping the element back into a Document
    // or by duplicating the fragment. Simplest: call parseSynth with the element's
    // XML representation. But that requires serialization. Instead, inline the same
    // field extraction that parseSynth does.
    SynthTrackModel synth = new SynthTrackModel(name);

    // ── Osc 1 ──
    NodeList osc1Nodes = soundNode.getElementsByTagName("osc1");
    if (osc1Nodes.getLength() > 0) {
      Element osc1 = (Element) osc1Nodes.item(0);
      if (osc1.hasAttribute("type")) {
        synth.setOsc1Type(osc1.getAttribute("type").toUpperCase());
      } else {
        NodeList typeNodes = osc1.getElementsByTagName("type");
        if (typeNodes.getLength() > 0) {
          synth.setOsc1Type(typeNodes.item(0).getTextContent().toUpperCase());
        }
      }
    }

    // ── Osc 2 ──
    NodeList osc2Nodes = soundNode.getElementsByTagName("osc2");
    if (osc2Nodes.getLength() > 0) {
      Element osc2 = (Element) osc2Nodes.item(0);
      NodeList typeNodes = osc2.getElementsByTagName("type");
      if (typeNodes.getLength() > 0) {
        synth.setOsc2Type(typeNodes.item(0).getTextContent().toUpperCase());
      }
    }

    // ── Synth Mode ──
    NodeList modeNodes = soundNode.getElementsByTagName("mode");
    if (modeNodes.getLength() > 0) {
      String mode = modeNodes.item(0).getTextContent().trim().toLowerCase();
      switch (mode) {
        case "fm" -> synth.setSynthMode(1);
        case "ringmod" -> synth.setSynthMode(2);
        default -> synth.setSynthMode(0);
      }
    }

    // ── Polyphonic mode ──
    NodeList polyNodes = soundNode.getElementsByTagName("polyphonic");
    if (polyNodes.getLength() > 0) {
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

    // ── FM Modulator 1 (from <modulator1><transpose> + <modulator1Amount>) ──
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

    // ── LPF Mode ──
    NodeList lpfModeNodes = soundNode.getElementsByTagName("lpfMode");
    if (lpfModeNodes.getLength() > 0) {
      String lpfMode = lpfModeNodes.item(0).getTextContent().trim();
      if ("24dB".equals(lpfMode)) {
        synth.setFilterMode(FilterMode.LADDER_24);
      } else {
        synth.setFilterMode(FilterMode.LADDER_12);
      }
    }

    // ── Envelopes 0-3 ──
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

    // ── Filter defaults from defaultParams ──
    NodeList defParams = soundNode.getElementsByTagName("defaultParams");
    if (defParams.getLength() > 0) {
      Element def = (Element) defParams.item(0);
      NodeList lpfFreq = def.getElementsByTagName("lpfFrequency");
      if (lpfFreq.getLength() > 0) {
        synth.setLpfFreq(DelugeHexMapper.hexToHz(lpfFreq.item(0).getTextContent()));
      }
      NodeList lpfRes = def.getElementsByTagName("lpfResonance");
      if (lpfRes.getLength() > 0) {
        float val = DelugeHexMapper.hexToFloat(lpfRes.item(0).getTextContent());
        synth.setLpfRes(val);
      }
      NodeList hpfFreq = def.getElementsByTagName("hpfFrequency");
      if (hpfFreq.getLength() > 0) {
        synth.setHpfFreq(DelugeHexMapper.hexToHz(hpfFreq.item(0).getTextContent()));
      }
      NodeList hpfRes = def.getElementsByTagName("hpfResonance");
      if (hpfRes.getLength() > 0) {
        float val = DelugeHexMapper.hexToFloat(hpfRes.item(0).getTextContent());
        synth.setHpfRes(val);
      }

      // ── FM feedback params from defaultParams ──
      parseHexFloatParam(def, "modulator1Feedback", synth::setModulator1Feedback);
      parseHexFloatParam(def, "modulator2Amount", synth::setModulator2Amount);
      parseHexFloatParam(def, "modulator2Feedback", synth::setModulator2Feedback);
      parseHexFloatParam(def, "carrier1Feedback", synth::setCarrier1Feedback);
      parseHexFloatParam(def, "carrier2Feedback", synth::setCarrier2Feedback);
    }

    // ── Patch Cables ──
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

    // ── Mod Knobs ──
    NodeList knobList = soundNode.getElementsByTagName("modKnob");
    for (int i = 0; i < knobList.getLength(); i++) {
      Element knobElem = (Element) knobList.item(i);
      String param = getChildText(knobElem, "controlsParam");
      if (param != null && i < synth.getModKnobs().size()) {
        synth.setModKnob(i, new ModKnob(param, "NONE"));
      }
    }

    return synth;
  }

  /** Parses a hex-encoded float parameter from a child element of {@code parent}. Sets value via {@code setter}. */
  private static void parseHexFloatParam(Element parent, String tag, java.util.function.Consumer<Float> setter) {
    NodeList nodes = parent.getElementsByTagName(tag);
    if (nodes.getLength() > 0) {
      setter.accept(Math.abs(DelugeHexMapper.hexToFloat(nodes.item(0).getTextContent())));
    }
  }

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
