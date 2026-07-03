package org.deluge.xml;

import static org.deluge.xml.DelugeXmlUtil.*;

import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.deluge.model.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Parses Drum Kits and parameters from Deluge XML patch presets or song XML node. */
public class KitXmlParser {
  private static final Logger LOG = Logger.getLogger(KitXmlParser.class.getName());

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

    String sdiStr = getChildText(kitNode, "selectedDrumIndex");
    if (sdiStr != null) {
      try {
        kit.setSelectedDrumIndex(Integer.parseInt(sdiStr.trim()));
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing selectedDrumIndex", e);
      }
    }

    NodeList soundSourcesNodes = kitNode.getElementsByTagName("soundSources");
    NodeList soundNodes;
    if (soundSourcesNodes.getLength() > 0) {
      soundNodes = ((Element) soundSourcesNodes.item(0)).getElementsByTagName("sound");
    } else {
      soundNodes = kitNode.getElementsByTagName("sound");
    }

    for (int i = 0; i < soundNodes.getLength(); i++) {
      Element soundNode = (Element) soundNodes.item(i);
      String soundName = "SOUND " + i;
      if (soundNode.hasAttribute("name")) {
        soundName = soundNode.getAttribute("name");
      } else {
        NodeList nameNodes = soundNode.getElementsByTagName("name");
        if (nameNodes.getLength() > 0) {
          soundName = nameNodes.item(0).getTextContent();
        }
      }

      SoundDrum sound = parseSoundDrum(soundNode, soundName);
      kit.addDrum(sound);
    }

    return kit;
  }

  public static KitTrackModel parseKitElement(Element kitNode) throws Exception {
    String name = "KIT";
    if (kitNode.hasAttribute("name")) {
      name = kitNode.getAttribute("name");
    } else if (kitNode.hasAttribute("presetName")) {
      name = kitNode.getAttribute("presetName");
    } else {
      NodeList slotNodes = kitNode.getElementsByTagName("presetSlot");
      if (slotNodes.getLength() > 0) {
        name = "KIT " + slotNodes.item(0).getTextContent();
      }
    }

    KitTrackModel kit = new KitTrackModel(name);

    String sdiStr = getChildText(kitNode, "selectedDrumIndex");
    if (sdiStr != null) {
      try {
        kit.setSelectedDrumIndex(Integer.parseInt(sdiStr.trim()));
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing selectedDrumIndex", e);
      }
    }

    NodeList soundSourcesNodes = kitNode.getElementsByTagName("soundSources");
    NodeList soundNodes;
    if (soundSourcesNodes.getLength() > 0) {
      soundNodes = ((Element) soundSourcesNodes.item(0)).getElementsByTagName("sound");
    } else {
      soundNodes = kitNode.getElementsByTagName("sound");
    }

    if (kitNode.hasAttribute("isMutedInArrangement")) {
      kit.setMutedInArrangement("1".equals(kitNode.getAttribute("isMutedInArrangement")));
    }
    if (kitNode.hasAttribute("isSoloingInArrangement")) {
      kit.setSoloingInArrangement("1".equals(kitNode.getAttribute("isSoloingInArrangement")));
    }
    parseDefaultVelocity(kitNode, kit);
    parseClippingAmount(kitNode, kit);

    for (int i = 0; i < soundNodes.getLength(); i++) {
      Element soundNode = (Element) soundNodes.item(i);
      String soundName = "SOUND " + i;
      if (soundNode.hasAttribute("name")) {
        soundName = soundNode.getAttribute("name");
      } else {
        NodeList nameNodes = soundNode.getElementsByTagName("name");
        if (nameNodes.getLength() > 0) {
          soundName = nameNodes.item(0).getTextContent();
        }
      }
      SoundDrum sound = parseSoundDrum(soundNode, soundName);
      kit.addDrum(sound);
    }
    return kit;
  }

  public static SoundDrum parseSoundDrum(java.io.File xmlFile) throws Exception {
    try (java.io.InputStream is = new java.io.FileInputStream(xmlFile)) {
      Document doc = parseXml(is);
      Element root = doc.getDocumentElement();
      Element soundNode = root;
      if (!root.getTagName().equals("sound")) {
        NodeList sounds = root.getElementsByTagName("sound");
        if (sounds.getLength() > 0) {
          soundNode = (Element) sounds.item(0);
        }
      }
      return parseSoundDrum(soundNode, xmlFile.getName().replace(".XML", ""));
    }
  }

  public static SoundDrum parseSoundDrum(Element soundNode, String soundName) {
    SoundDrum sound = new SoundDrum(soundName);

    // ── Sample path ──
    NodeList sampleNodes = soundNode.getElementsByTagName("sample");
    if (sampleNodes.getLength() > 0) {
      Element sampleNode = (Element) sampleNodes.item(0);
      if (sampleNode.hasAttribute("fileName")) {
        sound.setSamplePath(sampleNode.getAttribute("fileName"));
      } else {
        NodeList fnNodes = sampleNode.getElementsByTagName("fileName");
        if (fnNodes.getLength() > 0) {
          String fn = fnNodes.item(0).getTextContent();
          if (fn != null && !fn.isBlank()) {
            sound.setSamplePath(fn);
          }
        }
      }
    } else {
      NodeList oscNodes = soundNode.getElementsByTagName("osc1");
      if (oscNodes.getLength() > 0) {
        Element osc = (Element) oscNodes.item(0);
        if (osc.hasAttribute("fileName")) {
          sound.setSamplePath(osc.getAttribute("fileName"));
        } else {
          NodeList fnNodes = osc.getElementsByTagName("fileName");
          if (fnNodes.getLength() > 0) {
            sound.setSamplePath(fnNodes.item(0).getTextContent());
          }
        }
      }
    }

    // ── Zone (sample truncation) ──
    parseZoneFromOsc(sound, soundNode);

    // ── Root attributes ──
    readAttrOrChildString(
        soundNode,
        "polyphonic",
        v -> {
          if ("choke".equalsIgnoreCase(v)) {
            sound.setPolyphony(SynthTrackModel.PolyphonyMode.CHOKE);
          } else if ("mono".equalsIgnoreCase(v) || "0".equals(v) || "false".equalsIgnoreCase(v)) {
            sound.setPolyphony(SynthTrackModel.PolyphonyMode.MONO);
          } else if ("legato".equalsIgnoreCase(v)) {
            sound.setPolyphony(SynthTrackModel.PolyphonyMode.LEGATO);
          } else {
            sound.setPolyphony(SynthTrackModel.PolyphonyMode.POLY);
          }
        });
    readAttrOrChildInt(soundNode, "voicePriority", 1, sound::setVoicePriority);
    NodeList midiOutputNodes = soundNode.getElementsByTagName("midiOutput");
    if (midiOutputNodes.getLength() > 0) {
      Element midiOutputEl = (Element) midiOutputNodes.item(0);
      readAttrOrChildInt(midiOutputEl, "channel", 255, sound::setMidiChannel);
      readAttrOrChildInt(midiOutputEl, "noteForDrum", 255, sound::setNoteForDrum);
    }
    readAttrOrChildHexFloat(soundNode, "sideChainSend", sound::setSidechainSend);
    readAttrOrChildString(soundNode, "modFXType", sound::setModFxType);
    readAttrOrChildString(soundNode, "modFxType", sound::setModFxType);
    readAttrOrChildString(
        soundNode,
        "lpfMode",
        v -> {
          if ("24dB".equals(v)) sound.setLpfMode(FilterMode.LADDER_24);
          else sound.setLpfMode(FilterMode.LADDER_12);
        });
    readAttrOrChildString(
        soundNode,
        "hpfMode",
        v -> {
          if ("24dB".equals(v)) sound.setHpfMode(FilterMode.LADDER_24);
          else if ("SVF".equals(v)) sound.setHpfMode(FilterMode.SVF);
          else if ("DRIVE".equals(v)) sound.setHpfMode(FilterMode.DRIVE);
          else if ("SVF_BAND".equals(v) || "SVF Band".equalsIgnoreCase(v))
            sound.setHpfMode(FilterMode.SVF_BAND);
          else if ("SVF_NOTCH".equals(v) || "SVF Notch".equalsIgnoreCase(v))
            sound.setHpfMode(FilterMode.SVF_NOTCH);
          else sound.setHpfMode(FilterMode.LADDER_12);
        });
    readAttrOrChildString(
        soundNode,
        "mode",
        v -> {
          if ("fm".equalsIgnoreCase(v)) {
            sound.setSynthMode(1);
          } else if ("ringmod".equalsIgnoreCase(v)) {
            sound.setSynthMode(2);
          } else {
            sound.setSynthMode(0);
          }
        });
    readAttrString(
        soundNode,
        "filterRoute",
        v -> {
          // filterRoute is an internal routing flag; not directly stored
        });
    readAttrOrChildHexFloat(soundNode, "clippingAmount", sound::setClippingAmount);

    // -- oscillatorReset (compatibility, tag or attribute) --
    String oscReset = attrOrChildText(soundNode, "oscillatorReset");
    if (oscReset != null && !oscReset.isEmpty()) {
      if ("0".equals(oscReset) || "false".equalsIgnoreCase(oscReset)) {
        sound.setOsc1RetrigPhase(-1);
        sound.setOsc2RetrigPhase(-1);
        sound.setMod1RetrigPhase(-1);
        sound.setMod2RetrigPhase(-1);
      }
    }

    // ── Child elements ──
    // osc1
    Element osc1El = getFirstChild(soundNode, "osc1");
    if (osc1El != null) {
      String type = osc1El.getAttribute("type");
      if (type == null || type.isEmpty() || type.isBlank()) {
        type = getChildText(osc1El, "type");
      }
      if (type != null && !type.isBlank()) {
        sound.setOsc1Type(type.toUpperCase());
      }
      readAttrOrChildInt(osc1El, "transpose", 0, sound::setOsc1Transpose);
      readAttrOrChildInt(osc1El, "cents", 0, sound::setOsc1Cents);

      readAttrOrChildInt(osc1El, "retrigPhase", -1, sound::setOsc1RetrigPhase);
      NodeList wtNodes = osc1El.getElementsByTagName("wavetableIndexPct");
      if (wtNodes.getLength() > 0) {
        try {
          sound.setWavetableIndexPct(Integer.parseInt(wtNodes.item(0).getTextContent().trim()));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
    }

    // osc2
    Element osc2 = getFirstChild(soundNode, "osc2");
    if (osc2 != null) {
      String type = osc2.getAttribute("type");
      if (type == null || type.isEmpty() || type.isBlank()) {
        type = getChildText(osc2, "type");
      }
      if (type != null && !type.isBlank()) {
        sound.setOsc2Type(type.toUpperCase());
      }
      readAttrOrChildInt(osc2, "transpose", 0, sound::setOsc2Transpose);
      readAttrOrChildInt(osc2, "cents", 0, sound::setOsc2Cents);

      // osc2 sample fileName (attribute or child element)
      String osc2fn = osc2.getAttribute("fileName");
      if (osc2fn == null || osc2fn.isBlank()) {
        osc2fn = getChildText(osc2, "fileName");
      }
      if (osc2fn != null && !osc2fn.isBlank()) {
        sound.setOsc2SamplePath(osc2fn);
      }
      // osc2 zone (startSamplePos/endSamplePos/startLoopPos/endLoopPos)
      Element osc2Zone = getFirstChild(osc2, "zone");
      if (osc2Zone != null) {
        sound.setOsc2StartSamplePos(readIntAttr(osc2Zone, "startSamplePos", -1));
        sound.setOsc2EndSamplePos(readIntAttr(osc2Zone, "endSamplePos", -1));
        sound.setStartLoopPos(readIntAttr(osc2Zone, "startLoopPos", -1));
        sound.setEndLoopPos(readIntAttr(osc2Zone, "endLoopPos", -1));
      }
      // osc2 sample-playback attrs
      String osc2lm = osc2.getAttribute("loopMode");
      if (osc2lm != null && !osc2lm.isBlank()) {
        try {
          sound.setOsc2LoopMode(Integer.parseInt(osc2lm));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
      readAttrBool(osc2, "reversed", sound::setOsc2Reversed);
      readAttrBool(osc2, "timeStretchEnable", sound::setOsc2TimeStretch);
      readAttrFloatHex(osc2, "timeStretchAmount", sound::setOsc2TimeStretchAmount, true);
      readAttrBool(osc2, "linearInterpolation", sound::setOsc2LinearInterpolation);
      // osc2 retrigPhase
      readAttrOrChildInt(osc2, "retrigPhase", -1, sound::setOsc2RetrigPhase);
    }

    // Modulator 1
    NodeList mod1Nodes = soundNode.getElementsByTagName("modulator1");
    if (mod1Nodes.getLength() > 0) {
      Element mod1 = (Element) mod1Nodes.item(0);
      int transpose = attrOrChildInt(mod1, "transpose", 0);
      int cents = attrOrChildInt(mod1, "cents", 0);
      sound.setFmRatio(modulatorRatio(transpose, cents));
      sound.setModulator1Transpose(transpose);
      sound.setModulator1Cents(cents);
      int retrig = attrOrChildInt(mod1, "retrigPhase", 0);
      sound.setMod1RetrigPhase(retrig);
    }
    // Modulator 2
    NodeList mod2Nodes = soundNode.getElementsByTagName("modulator2");
    if (mod2Nodes.getLength() > 0) {
      Element mod2 = (Element) mod2Nodes.item(0);
      int transpose = attrOrChildInt(mod2, "transpose", 0);
      int cents = attrOrChildInt(mod2, "cents", 0);
      sound.setFmRatio2(modulatorRatio(transpose, cents));
      sound.setModulator2Transpose(transpose);
      sound.setModulator2Cents(cents);
      int retrig = attrOrChildInt(mod2, "retrigPhase", 0);
      sound.setMod2RetrigPhase(retrig);
    }

    // lfo1, lfo2
    parseDrumLfo(soundNode, "lfo1", sound, true);
    parseDrumLfo(soundNode, "lfo2", sound, false);

    // unison
    Element unisonEl = getFirstChild(soundNode, "unison");
    if (unisonEl != null) {
      sound.setUnisonNum(readIntAttr(unisonEl, "num", 1));
      sound.setUnisonDetune(Math.abs(DelugeHexMapper.hexToFloat(readAttr(unisonEl, "detune"))));
      String spreadStr = readAttr(unisonEl, "spread");
      if (spreadStr != null && !spreadStr.isEmpty()) {
        sound.setUnisonStereoSpread(Math.abs(DelugeHexMapper.hexToFloat(spreadStr)));
      }
    }

    // delay
    Element delayEl = getFirstChild(soundNode, "delay");
    if (delayEl != null) {
      readAttrOrChildHexFloat(delayEl, "rate", sound::setDelayRate);
      readAttrOrChildHexFloat(delayEl, "feedback", sound::setDelayFeedback);
      readAttrOrChildInt(delayEl, "pingPong", 0, sound::setDelayPingPong);
      readAttrOrChildInt(delayEl, "analog", 0, sound::setDelayAnalog);
    } else {
      // Fallback: look up direct attributes on soundNode
      String rateStr = readAttr(soundNode, "delayRate");
      if (rateStr != null) sound.setDelayRate(DelugeHexMapper.hexToFloat(rateStr));
      String feedbackStr = readAttr(soundNode, "delayFeedback");
      if (feedbackStr != null) sound.setDelayFeedback(DelugeHexMapper.hexToFloat(feedbackStr));
      sound.setDelayPingPong(readIntAttr(soundNode, "delayPingPong", 0));
      sound.setDelayAnalog(readIntAttr(soundNode, "delayAnalog", 0));
    }

    // audioCompressor
    Element compEl = getFirstChild(soundNode, "audioCompressor");
    if (compEl == null) {
      compEl = getFirstChild(soundNode, "compressor"); // tag fallback
    }
    if (compEl != null) {
      readAttrOrChildHexFloat(compEl, "attack", sound::setCompressorAttack);
      readAttrOrChildHexFloat(compEl, "release", sound::setCompressorRelease);
      readAttrOrChildInt(compEl, "syncLevel", 0, sound::setCompressorSyncLevel);
      readAttrOrChildHexFloat(compEl, "threshold", v -> sound.setCompressorThreshold(Math.abs(v)));
      readAttrOrChildHexFloat(compEl, "ratio", v -> sound.setCompressorRatio(Math.abs(v)));
      readAttrOrChildHexFloat(compEl, "blend", v -> sound.setCompressorBlend(Math.abs(v)));
      readAttrOrChildHexFloat(
          compEl, "sidechainHpf", v -> sound.setCompressorSidechainHpf(Math.abs(v)));
    } else {
      // Fallback: look up direct attributes on soundNode
      String attackStr = readAttr(soundNode, "compressorAttack");
      if (attackStr != null) sound.setCompressorAttack(DelugeHexMapper.hexToFloat(attackStr));
      String releaseStr = readAttr(soundNode, "compressorRelease");
      if (releaseStr != null) sound.setCompressorRelease(DelugeHexMapper.hexToFloat(releaseStr));
      sound.setCompressorSyncLevel(readIntAttr(soundNode, "compressorSyncLevel", 0));
      String thresholdStr = readAttr(soundNode, "compressorThreshold");
      if (thresholdStr != null)
        sound.setCompressorThreshold(Math.abs(DelugeHexMapper.hexToFloat(thresholdStr)));
      String ratioStr = readAttr(soundNode, "compressorRatio");
      if (ratioStr != null)
        sound.setCompressorRatio(Math.abs(DelugeHexMapper.hexToFloat(ratioStr)));
      String blendStr = readAttr(soundNode, "compressorBlend");
      if (blendStr != null)
        sound.setCompressorBlend(Math.abs(DelugeHexMapper.hexToFloat(blendStr)));
      String compHpfStr = readAttr(soundNode, "compressorSidechainHpf");
      if (compHpfStr != null)
        sound.setCompressorSidechainHpf(Math.abs(DelugeHexMapper.hexToFloat(compHpfStr)));
    }

    // ── Stutter config (quantized, reverse, pingPong) ──
    parseStutter(soundNode, sound);

    // sidechain (at sound level, separate from compressor)
    Element sidechainEl = getFirstChild(soundNode, "sidechain");
    if (sidechainEl != null) {
      String scAttack = readAttr(sidechainEl, "attack");
      if (scAttack != null)
        sound.setSidechainAttack(Math.abs(DelugeHexMapper.hexToFloat(scAttack)));
      String scRelease = readAttr(sidechainEl, "release");
      if (scRelease != null)
        sound.setSidechainRelease(Math.abs(DelugeHexMapper.hexToFloat(scRelease)));
      sound.setSidechainSyncLevel(readIntAttr(sidechainEl, "syncLevel", 0));
      sound.setSidechainSyncType(readIntAttr(sidechainEl, "syncType", 0));
    }

    // arpeggiator
    Element arpEl = getFirstChild(soundNode, "arpeggiator");
    if (arpEl != null) {
      sound.setArpeggiatorGate(DelugeHexMapper.hexToFloat(readAttr(arpEl, "gate")));
    }

    // modKnobs
    parseDrumModKnobs(soundNode, sound);
    parseMidiKnobs(soundNode, sound.getMidiKnobs());

    // patchCables (direct child)
    parseDrumPatchCables(soundNode, sound);

    // defaultParams
    Element dp = getFirstChild(soundNode, "defaultParams");
    if (dp != null) {
      parseDrumDefaultParams(dp, sound);
    }

    return sound;
  }

  private static void parseZoneFromOsc(SoundDrum sound, Element soundNode) {
    NodeList oscNodes = soundNode.getElementsByTagName("osc1");
    if (oscNodes.getLength() == 0) return;
    Element osc = (Element) oscNodes.item(0);
    NodeList zoneNodes = osc.getElementsByTagName("zone");
    if (zoneNodes.getLength() == 0) return;
    Element zone = (Element) zoneNodes.item(0);

    // Try attributes first (newer format)
    String ss = zone.getAttribute("startSamplePos");
    String es = zone.getAttribute("endSamplePos");
    String sm = zone.getAttribute("startMilliseconds");
    String em = zone.getAttribute("endMilliseconds");
    String sSec = zone.getAttribute("startSeconds");
    String eSec = zone.getAttribute("endSeconds");

    // Fall back to child elements if attributes are empty (older format)
    if (ss.isEmpty()) {
      NodeList children = zone.getElementsByTagName("startSamplePos");
      if (children.getLength() > 0) ss = children.item(0).getTextContent();
    }
    if (es.isEmpty()) {
      NodeList children = zone.getElementsByTagName("endSamplePos");
      if (children.getLength() > 0) es = children.item(0).getTextContent();
    }
    if (sm.isEmpty()) {
      NodeList children = zone.getElementsByTagName("startMilliseconds");
      if (children.getLength() > 0) sm = children.item(0).getTextContent();
    }
    if (em.isEmpty()) {
      NodeList children = zone.getElementsByTagName("endMilliseconds");
      if (children.getLength() > 0) em = children.item(0).getTextContent();
    }
    if (sSec.isEmpty()) {
      NodeList children = zone.getElementsByTagName("startSeconds");
      if (children.getLength() > 0) sSec = children.item(0).getTextContent();
    }
    if (eSec.isEmpty()) {
      NodeList children = zone.getElementsByTagName("endSeconds");
      if (children.getLength() > 0) eSec = children.item(0).getTextContent();
    }

    if (!es.isEmpty()) {
      sound.setEndSamplePos(Integer.parseInt(es));
    }
    if (!ss.isEmpty()) sound.setStartSamplePos(Integer.parseInt(ss));
    // Firmware: seconds * 1000 -> milliseconds, same as startMilliseconds/endMilliseconds
    if (!eSec.isEmpty()) sound.setEndMs(Float.parseFloat(eSec) * 1000.0f);
    if (!sSec.isEmpty()) sound.setStartMs(Float.parseFloat(sSec) * 1000.0f);
    if (!em.isEmpty()) sound.setEndMs(Float.parseFloat(em));
    if (!sm.isEmpty()) sound.setStartMs(Float.parseFloat(sm));

    // startLoopPos/endLoopPos (conditional — firmware only writes these for looped zones)
    String slp = zone.getAttribute("startLoopPos");
    if (slp.isEmpty()) {
      NodeList slpNodes = zone.getElementsByTagName("startLoopPos");
      if (slpNodes.getLength() > 0) slp = slpNodes.item(0).getTextContent();
    }
    String elp = zone.getAttribute("endLoopPos");
    if (elp.isEmpty()) {
      NodeList elpNodes = zone.getElementsByTagName("endLoopPos");
      if (elpNodes.getLength() > 0) elp = elpNodes.item(0).getTextContent();
    }
    if (!slp.isEmpty()) sound.setStartLoopPos(Integer.parseInt(slp));
    if (!elp.isEmpty()) sound.setEndLoopPos(Integer.parseInt(elp));
  }

  private static void parseDrumLfo(Element soundNode, String lfoTag, Drum sound, boolean isLocal) {
    Element lfoEl = getFirstChild(soundNode, lfoTag);
    if (lfoEl == null) return;

    LfoType waveform = LfoType.SINE;
    float rateHz = 1.0f;
    float depth = 0.0f;
    int syncLevel = 0;

    String typeStr = readAttr(lfoEl, "type");
    if (typeStr == null) typeStr = getChildText(lfoEl, "type");
    if (typeStr != null) {
      waveform = parseLfoType(typeStr);
    }

    String rateStr = readAttr(lfoEl, "rate");
    if (rateStr != null) {
      rateHz = DelugeHexMapper.hexToLfoHz(rateStr);
    } else {
      String rateChild = getChildText(lfoEl, "rate");
      if (rateChild != null) rateHz = DelugeHexMapper.hexToLfoHz(rateChild);
    }

    String depthStr = readAttr(lfoEl, "depth");
    if (depthStr == null || depthStr.isBlank()) {
      depthStr = getChildText(lfoEl, "depth");
    }
    if (depthStr != null && !depthStr.isBlank()) {
      depth = toUnipolar(DelugeHexMapper.hexToFloat(depthStr));
    }

    // syncLevel: read attr first, fallback to child element
    String syncLevelStr = readAttr(lfoEl, "syncLevel");
    if (syncLevelStr != null && !syncLevelStr.isEmpty()) {
      try {
        syncLevel = Integer.parseInt(syncLevelStr);
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    } else {
      String syncChild = getChildText(lfoEl, "syncLevel");
      if (syncChild != null) {
        try {
          syncLevel = Integer.parseInt(syncChild);
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
    }

    // syncType: attribute first, child-element fallback
    String syncTypeStr = readAttr(lfoEl, "syncType");
    if (syncTypeStr == null || syncTypeStr.isBlank()) {
      syncTypeStr = getChildText(lfoEl, "syncType");
    }
    int syncType = 0;
    if (syncTypeStr != null && !syncTypeStr.isEmpty()) {
      try {
        syncType = Integer.parseInt(syncTypeStr);
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }

    LfoModel lfo = new LfoModel(rateHz, waveform, depth, "NONE", isLocal, syncLevel, syncType);
    if (lfoTag.equals("lfo1")) {
      sound.setLfo1(lfo);
    } else {
      sound.setLfo2(lfo);
    }
  }

  private static void parseDrumDefaultParams(Element dp, Drum sound) {
    readHexFloatUnipolar(dp, "oscAVolume", sound::setOscAVolume);
    readHexFloatUnipolar(dp, "oscBVolume", sound::setOscBVolume);
    readHexFloatUnipolar(dp, "noiseVolume", sound::setNoiseVolume);
    readHexFloatUnipolar(dp, "volume", sound::setVolume);
    readHexFloat(dp, "pan", sound::setPan);
    readHexHz(dp, "lpfFrequency", sound::setLpfFreq);
    readHexFloatUnipolar(dp, "lpfResonance", sound::setLpfRes);
    readHexHz(dp, "hpfFrequency", sound::setHpfFreq);
    readHexFloatUnipolar(dp, "hpfResonance", sound::setHpfRes);
    String fmVal;
    if ((fmVal = attrOrChildText(dp, "modulator1Amount")) != null && !fmVal.isBlank()) {
      sound.setModulator1AmountQ31(DelugeHexMapper.hexToQ31(fmVal));
      sound.setFmAmount(toUnipolar(DelugeHexMapper.hexToFloat(fmVal)));
    }
    if ((fmVal = attrOrChildText(dp, "modulator2Amount")) != null && !fmVal.isBlank()) {
      sound.setModulator2AmountQ31(DelugeHexMapper.hexToQ31(fmVal));
    }
    if ((fmVal = attrOrChildText(dp, "modulator1Feedback")) != null && !fmVal.isBlank()) {
      sound.setModulator1FeedbackQ31(DelugeHexMapper.hexToQ31(fmVal));
    }
    if ((fmVal = attrOrChildText(dp, "modulator2Feedback")) != null && !fmVal.isBlank()) {
      sound.setModulator2FeedbackQ31(DelugeHexMapper.hexToQ31(fmVal));
    }
    if ((fmVal = attrOrChildText(dp, "carrier1Feedback")) != null && !fmVal.isBlank()) {
      sound.setCarrier1FeedbackQ31(DelugeHexMapper.hexToQ31(fmVal));
    }
    if ((fmVal = attrOrChildText(dp, "carrier2Feedback")) != null && !fmVal.isBlank()) {
      sound.setCarrier2FeedbackQ31(DelugeHexMapper.hexToQ31(fmVal));
    }
    readHexFloatUnipolar(dp, "modFXRate", sound::setModFxRate);
    readHexFloatUnipolar(dp, "modFXDepth", sound::setModFxDepth);
    readHexFloatUnipolar(dp, "modFXOffset", sound::setModFxOffset);
    readHexFloatUnipolar(dp, "modFXFeedback", sound::setModFxFeedback);
    readHexFloatUnipolar(dp, "delayRate", sound::setDelayRate);
    readHexFloatUnipolar(dp, "delayFeedback", sound::setDelayFeedback);
    readHexFloatUnipolar(dp, "reverbAmount", sound::setReverbAmount);
    readHexFloatUnipolar(dp, "arpeggiatorGate", sound::setArpeggiatorGate);
    readHexFloatUnipolar(dp, "arpeggiatorRate", sound::setArpeggiatorRate);
    readHexFloatUnipolar(dp, "portamento", sound::setPortamento);
    readHexFloatUnipolar(dp, "stutterRate", sound::setStutterRate);
    readHexFloatUnipolar(dp, "sampleRateReduction", sound::setSampleRateReduction);
    readHexFloatUnipolar(dp, "bitCrush", sound::setBitCrush);
    readHexFloatUnipolar(dp, "waveIndex", sound::setWaveIndex);
    readHexFloatUnipolar(dp, "compressorShape", sound::setCompressorShape);

    // Envelopes 1-4 as child elements of defaultParams (child-element format)
    for (int i = 1; i <= 4; i++) {
      String envTag = "envelope" + i;
      Element envEl = getFirstChild(dp, envTag);
      if (envEl != null) {
        EnvelopeModel env = parseEnvelopeElement(envEl);
        if (i == 1) sound.setAdsr(env);
        else if (i == 2) sound.setEnv2(env);
        else if (i == 3) sound.setEnv3(env);
        else if (i == 4) sound.setEnv4(env);
      }
    }

    // Equalizer child element
    Element eqEl = getFirstChild(dp, "equalizer");
    if (eqEl != null) {
      readHexFloat(eqEl, "bass", sound::setEqBass);
      readHexFloat(eqEl, "treble", sound::setEqTreble);
    }

    // Patch cables inside defaultParams
    parseDrumCablesFromContainer(dp, sound);
  }

  private static void parseDrumModKnobs(Element soundNode, Drum sound) {
    // Try direct &lt;modKnobs&gt; container
    Element mkContainer = getFirstChild(soundNode, "modKnobs");
    if (mkContainer != null) {
      NodeList knobList = mkContainer.getChildNodes();
      int idx = 0;
      for (int i = 0; i < knobList.getLength() && idx < 16; i++) {
        if (knobList.item(i) instanceof Element knobElem) {
          // child-element format:
          // &lt;modKnob&gt;&lt;controlsParam&gt;...&lt;/controlsParam&gt;&lt;/modKnob&gt;
          String param = getChildText(knobElem, "controlsParam");
          // attribute format: &lt;modKnob controlsParam="..." /&gt;
          if (param == null) param = knobElem.getAttribute("controlsParam");
          if (param != null && !param.isBlank()) {
            String patchSrc = getChildText(knobElem, "patchSource");
            if (patchSrc == null) patchSrc = getChildText(knobElem, "patchAmountFromSource");
            if (patchSrc == null) patchSrc = knobElem.getAttribute("patchSource");
            if (patchSrc == null || patchSrc.isBlank()) patchSrc = "NONE";
            sound.setModKnob(idx, new ModKnob(param.trim(), patchSrc.trim()));
            idx++;
          }
        }
      }
    } else {
      // Fallback: direct &lt;modKnob&gt; children of soundNode
      NodeList knobList = soundNode.getElementsByTagName("modKnob");
      for (int i = 0; i < knobList.getLength() && i < 16; i++) {
        Element knobElem = (Element) knobList.item(i);
        String param = getChildText(knobElem, "controlsParam");
        if (param != null && !param.isBlank()) {
          sound.setModKnob(i, new ModKnob(param.trim(), "NONE"));
        }
      }
    }
  }

  private static void parseDrumCablesFromContainer(Element container, Drum sound) {
    // Try <patchCables> container
    Element pcContainer = getFirstChild(container, "patchCables");
    if (pcContainer != null) {
      NodeList cableList = pcContainer.getChildNodes();
      for (int i = 0; i < cableList.getLength(); i++) {
        if (cableList.item(i) instanceof Element cableElem
            && "patchCable".equals(cableElem.getTagName())) {
          PatchCable pc = parseSinglePatchCable(cableElem);
          if (pc != null) {
            sound.addPatchCable(pc);
          }
        }
      }
    }
  }

  private static void parseDrumPatchCables(Element soundNode, Drum sound) {
    // Only match direct children to avoid double-parsing (patchCables is also inside defaultParams)
    Element pcContainer = null;
    NodeList children = soundNode.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      if (children.item(i) instanceof Element el && "patchCables".equals(el.getTagName())) {
        pcContainer = el;
        break;
      }
    }
    if (pcContainer != null) {
      parseDrumCablesFromContainer(soundNode, sound);
      return;
    }
    // Fallback: direct <patchCable> children
    for (int i = 0; i < children.getLength(); i++) {
      if (children.item(i) instanceof Element cableElem
          && "patchCable".equals(cableElem.getTagName())) {
        PatchCable pc = parseSinglePatchCable(cableElem);
        if (pc != null) {
          sound.addPatchCable(pc);
        }
      }
    }
  }

  private static void parseStutter(Element soundNode, SoundDrum sound) {
    NodeList nodes = soundNode.getElementsByTagName("stutter");
    if (nodes.getLength() == 0) return;
    Element stut = (Element) nodes.item(0);
    readAttrBool(stut, "quantized", sound::setStutterQuantized);
    readAttrBool(stut, "reverse", sound::setStutterReversed);
    readAttrBool(stut, "pingPong", sound::setStutterPingPong);
  }
}
