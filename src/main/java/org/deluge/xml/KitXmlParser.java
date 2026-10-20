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
    // try-with-resources: see the note in SongXmlParser.parseSong(File) -- an unclosed stream here
    // leaks a descriptor and locks the file on Windows.
    try (java.io.InputStream is = new java.io.FileInputStream(xmlFile)) {
      return parseKit(is, xmlFile.getName().replace(".XML", ""));
    }
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
    parseKitGlobalAttributesAndDefaults(kitNode, kit);

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
    parseKitGlobalAttributesAndDefaults(kitNode, kit);

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
          if ("choke".equalsIgnoreCase(v) || "2".equals(v)) {
            sound.setPolyphony(SynthTrackModel.PolyphonyMode.CHOKE);
          } else if ("auto".equalsIgnoreCase(v) || "0".equals(v)) {
            // C functions.cpp:861-863 — pre-June 2017 firmware maps "0" to AUTO
            sound.setPolyphony(SynthTrackModel.PolyphonyMode.AUTO);
          } else if ("mono".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) {
            sound.setPolyphony(SynthTrackModel.PolyphonyMode.MONO);
          } else if ("legato".equalsIgnoreCase(v)) {
            sound.setPolyphony(SynthTrackModel.PolyphonyMode.LEGATO);
          } else {
            sound.setPolyphony(SynthTrackModel.PolyphonyMode.POLY);
          }
        });
    readAttrOrChildInt(soundNode, "voicePriority", 1, sound::setVoicePriority);
    // C sound.cpp:1257-1260 — readTagOrAttributeValueInt() for maxVoices on drum <sound>.
    readAttrOrChildInt(soundNode, "maxVoices", 8, sound::setMaxVoiceCount);
    NodeList midiOutputNodes = soundNode.getElementsByTagName("midiOutput");
    if (midiOutputNodes.getLength() > 0) {
      Element midiOutputEl = (Element) midiOutputNodes.item(0);
      readAttrOrChildInt(midiOutputEl, "channel", 255, sound::setMidiChannel);
      readAttrOrChildInt(midiOutputEl, "noteForDrum", 255, sound::setNoteForDrum);
    }
    readAttrOrChildHexFloat(soundNode, "sideChainSend", sound::setSidechainSend);
    readAttrOrChildString(soundNode, "modFXType", sound::setModFxType);
    readAttrOrChildString(soundNode, "modFxType", sound::setModFxType);
    // C mod_controllable_audio.cpp:61-62 — every firmware SoundDrum initializes with
    // TRANSISTOR_24DB + HPLADDER even if minimal/legacy XML omits lpfMode/hpfMode attributes.
    sound.setLpfMode(FilterMode.LADDER_24);
    sound.setHpfMode(FilterMode.LADDER_12);
    readAttrOrChildString(
        soundNode,
        "lpfMode",
        v -> {
          if ("24dB".equals(v)) sound.setLpfMode(FilterMode.LADDER_24);
          else if ("24dBDrive".equalsIgnoreCase(v) || "DRIVE".equalsIgnoreCase(v))
            sound.setLpfMode(FilterMode.DRIVE);
          else if ("SVF_Band".equalsIgnoreCase(v) || "SVF Band".equalsIgnoreCase(v))
            sound.setLpfMode(FilterMode.SVF_BAND);
          else if ("SVF_Notch".equalsIgnoreCase(v) || "SVF Notch".equalsIgnoreCase(v))
            sound.setLpfMode(FilterMode.SVF_NOTCH);
          else if ("SVF".equalsIgnoreCase(v)) sound.setLpfMode(FilterMode.SVF);
          else if ("Off".equalsIgnoreCase(v)) sound.setLpfMode(FilterMode.OFF);
          else sound.setLpfMode(FilterMode.LADDER_12);
        });
    readAttrOrChildString(
        soundNode,
        "hpfMode",
        v -> {
          // C-faithful: LP-mode strings in hpfMode load as modes the HPF dispatch never renders
          // (filter_set.cpp:26-41, 169-185) — an inert high-pass; see InstrumentXmlParser.
          if ("12dB".equalsIgnoreCase(v)
              || "24dB".equalsIgnoreCase(v)
              || "24dBDrive".equalsIgnoreCase(v)) sound.setHpfMode(FilterMode.OFF);
          else if ("SVF".equalsIgnoreCase(v)) sound.setHpfMode(FilterMode.SVF);
          else if ("SVF_BAND".equalsIgnoreCase(v) || "SVF Band".equalsIgnoreCase(v))
            sound.setHpfMode(FilterMode.SVF_BAND);
          else if ("SVF_NOTCH".equalsIgnoreCase(v) || "SVF Notch".equalsIgnoreCase(v))
            sound.setHpfMode(FilterMode.SVF_NOTCH);
          else if ("Off".equalsIgnoreCase(v)) sound.setHpfMode(FilterMode.OFF);
          else sound.setHpfMode(FilterMode.LADDER_12);
        });
    int synthMode = DelugeXmlUtil.synthModeFromSound(soundNode); // C sound.cpp:433-441, 744-750
    if (synthMode >= 0) {
      sound.setSynthMode(synthMode);
    }
    readAttrOrChildString(
        soundNode,
        "filterRoute",
        v -> {
          // C filter_config.cpp:16-20 — "H2L"=0, "L2H"=1, "PARA"=2 (accept "PARALLEL" alias).
          if ("L2H".equalsIgnoreCase(v)) sound.setFilterRoute(1);
          else if ("PARA".equalsIgnoreCase(v) || "PARALLEL".equalsIgnoreCase(v))
            sound.setFilterRoute(2);
          else sound.setFilterRoute(0);
        });
    // C mod_controllable_audio.cpp:412-414, 736-739 — plain decimal uint8_t clippingAmount.
    String drumClipStr = attrOrChildText(soundNode, "clippingAmount");
    if (drumClipStr != null && !drumClipStr.isBlank()) {
      try {
        sound.setClippingAmount(Float.parseFloat(drumClipStr.trim()));
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing drum clippingAmount", e);
      }
    }

    // Sound-level master transpose & cents (C sound.cpp:579-585). Check attribute or DIRECT child
    // only so we do not match <osc1>/<osc2> <transpose>/<cents> descendants.
    String soundTransStr = soundNode.getAttribute("transpose");
    String soundCentsStr = soundNode.getAttribute("cents");
    for (org.w3c.dom.Node n = soundNode.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element ce) {
        if ((soundTransStr == null || soundTransStr.isBlank())
            && "transpose".equals(ce.getTagName())) {
          soundTransStr = ce.getTextContent();
        } else if ((soundCentsStr == null || soundCentsStr.isBlank())
            && "cents".equals(ce.getTagName())) {
          soundCentsStr = ce.getTextContent();
        }
      }
    }
    if (soundTransStr != null && !soundTransStr.isBlank()) {
      try {
        sound.setPitchSemitones(Integer.parseInt(soundTransStr.trim()));
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing kit sound transpose", e);
      }
    }
    if (soundCentsStr != null && !soundCentsStr.isBlank()) {
      try {
        sound.setCents(Integer.parseInt(soundCentsStr.trim()));
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing kit sound cents", e);
      }
    }

    // C sound.cpp:3241-3243 — Sound::readFromFile resets modulatorTranspose[1] and all four
    // retrigger phases to 0 before reading XML tags.
    sound.setModulator2Transpose(0);
    sound.setOsc1RetrigPhase(0);
    sound.setOsc2RetrigPhase(0);
    sound.setMod1RetrigPhase(0);
    sound.setMod2RetrigPhase(0);

    // -- oscillatorReset (compatibility, tag or attribute; C sound.cpp:603-614) --
    String oscReset = attrOrChildText(soundNode, "oscillatorReset");
    if (oscReset != null && !oscReset.isEmpty()) {
      String v = oscReset.trim();
      if ("0".equals(v) || "false".equalsIgnoreCase(v)) {
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

      String rpStr1 = attrOrChildText(osc1El, "retrigPhase");
      if (rpStr1 != null && !rpStr1.isBlank()) {
        try {
          sound.setOsc1RetrigPhase((int) Long.parseLong(rpStr1.trim()));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing kit osc1 retrigPhase", e);
        }
      }
      // C sound.cpp source tags, all readTagOrAttributeValueInt: kit files use the tag form.
      readAttrOrChildInt(osc1El, "loopMode", 0, sound::setOsc1LoopMode);
      sound.setOsc1TimeStretchAmount(
          DelugeXmlUtil.attrOrDirectChildInt(osc1El, "timeStretchAmount", 0)); // C sound.cpp:3423
      readBoolAttrOrChild(osc1El, "reversed", sound::setReverse);
      readBoolAttrOrChild(osc1El, "timeStretchEnable", sound::setOsc1TimeStretch);
      readBoolAttrOrChild(osc1El, "linearInterpolation", sound::setOsc1LinearInterpolation);
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
      // C sound.cpp:3389-3393, 3701-3703 — oscillatorSync on osc2 (absent means off).
      sound.setOscillatorSync(false);
      readBoolAttrOrChild(osc2, "oscillatorSync", sound::setOscillatorSync);

      // osc2 sample fileName (attribute or child element)
      String osc2fn = osc2.getAttribute("fileName");
      if (osc2fn == null || osc2fn.isBlank()) {
        osc2fn = getChildText(osc2, "fileName");
      }
      if (osc2fn != null && !osc2fn.isBlank()) {
        sound.setOsc2SamplePath(osc2fn);
      }
      // osc2 zone (attribute or child element form, matching osc1 parseZoneFromOsc)
      Element osc2Zone = getFirstChild(osc2, "zone");
      if (osc2Zone != null) {
        readAttrOrChildInt(osc2Zone, "startSamplePos", -1, sound::setOsc2StartSamplePos);
        readAttrOrChildInt(osc2Zone, "endSamplePos", -1, sound::setOsc2EndSamplePos);
        readAttrOrChildInt(osc2Zone, "startLoopPos", -1, sound::setOsc2StartLoopPos);
        readAttrOrChildInt(osc2Zone, "endLoopPos", -1, sound::setOsc2EndLoopPos);
      }
      // osc2 sample-playback attrs
      readAttrOrChildInt(osc2, "loopMode", 0, sound::setOsc2LoopMode);
      readBoolAttrOrChild(osc2, "reversed", sound::setOsc2Reversed);
      readBoolAttrOrChild(osc2, "timeStretchEnable", sound::setOsc2TimeStretch);
      // C sound.cpp:3423-3425 — int8_t, readTagOrAttributeValueInt
      sound.setOsc2TimeStretchAmount(
          DelugeXmlUtil.attrOrDirectChildInt(osc2, "timeStretchAmount", 0));
      readBoolAttrOrChild(osc2, "linearInterpolation", sound::setOsc2LinearInterpolation);
      // osc2 retrigPhase
      String rpStr2 = attrOrChildText(osc2, "retrigPhase");
      if (rpStr2 != null && !rpStr2.isBlank()) {
        try {
          sound.setOsc2RetrigPhase((int) Long.parseLong(rpStr2.trim()));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing kit osc2 retrigPhase", e);
        }
      }
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
      String retrigStr1 = attrOrChildText(mod1, "retrigPhase");
      if (retrigStr1 != null && !retrigStr1.isBlank()) {
        try {
          sound.setMod1RetrigPhase((int) Long.parseLong(retrigStr1.trim()));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing kit mod1 retrigPhase", e);
        }
      }
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
      String retrigStr2 = attrOrChildText(mod2, "retrigPhase");
      if (retrigStr2 != null && !retrigStr2.isBlank()) {
        try {
          sound.setMod2RetrigPhase((int) Long.parseLong(retrigStr2.trim()));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing kit mod2 retrigPhase", e);
        }
      }
      // C sound.cpp:3549-3553, 4231 — modulator1ToModulator0 written as toModulator1 on modulator2
      String toMod1 = attrOrChildText(mod2, "toModulator1");
      if (toMod1 != null && !toMod1.isBlank()) {
        sound.setModulator1ToModulator0(
            "1".equals(toMod1.trim()) || "true".equalsIgnoreCase(toMod1.trim()));
      }
    }
    NodeList legacyM1M0 = soundNode.getElementsByTagName("modulator1ToModulator0");
    if (legacyM1M0.getLength() > 0) {
      String v = legacyM1M0.item(0).getTextContent().trim();
      sound.setModulator1ToModulator0("1".equals(v) || "true".equalsIgnoreCase(v));
    }

    // lfo1..lfo4 (C sound.cpp:4196-4215 — lfo1/lfo3 local, lfo2/lfo4 global)
    parseDrumLfo(soundNode, "lfo1", sound, true);
    parseDrumLfo(soundNode, "lfo2", sound, false);
    parseDrumLfo(soundNode, "lfo3", sound, true);
    parseDrumLfo(soundNode, "lfo4", sound, false);

    // unison
    Element unisonEl = getFirstChild(soundNode, "unison");
    if (unisonEl != null) {
      sound.setUnisonNum(readIntAttr(unisonEl, "num", 1));
      // detune/spread are PLAIN INTEGERS in the Deluge format, read as tag OR attribute: the C does
      // readTagOrAttributeValueInt + clamp (sound.cpp:619-626), and real card kits carry both forms
      // — `<unison num="1" detune="8" />` in the Afterlife kits, `<detune>8</detune>` in 016
      // Electronisounds. This unconditionally ran the value through hexToFloat and only looked at
      // the attribute, so a detune of 8 was read as 7.45e-9 and every real kit lost its unison
      // detune silently. The synth parser already handled both encodings; this is the same logic.
      Float detune = unisonIntOrHex(unisonEl, "detune");
      if (detune != null) {
        sound.setUnisonDetune(detune);
      }
      Float spread = unisonIntOrHex(unisonEl, "spread");
      if (spread != null) {
        sound.setUnisonStereoSpread(spread);
      }
    }

    // delay (C mod_controllable_audio.cpp:787-841 — pingPong, analog, syncLevel, syncType)
    Element delayEl = getFirstChild(soundNode, "delay");
    if (delayEl != null) {
      readAttrOrChildHexFloat(delayEl, "rate", sound::setDelayRate);
      readAttrOrChildHexFloat(delayEl, "feedback", sound::setDelayFeedback);
      readAttrOrChildInt(delayEl, "pingPong", 0, sound::setDelayPingPong);
      readAttrOrChildInt(delayEl, "analog", 0, sound::setDelayAnalog);
      readAttrOrChildInt(delayEl, "syncLevel", 7, sound::setDelaySyncLevel);
      readAttrOrChildInt(delayEl, "syncType", 0, v -> sound.setDelaySyncType(syncTypeFromFile(v)));
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

    // sidechain (at sound level, separate from compressor; C mod_controllable_audio.cpp:892-907)
    Element sidechainEl = getFirstChild(soundNode, "sidechain");
    if (sidechainEl != null) {
      String scAttack = attrOrChildText(sidechainEl, "attack");
      if (scAttack != null && !scAttack.isBlank()) {
        sound.setSidechainAttack(Math.abs(DelugeHexMapper.hexToFloat(scAttack.trim())));
        try {
          sound.setSidechainAttackRaw(Integer.parseInt(scAttack.trim()));
        } catch (NumberFormatException ignored) {
        }
      }
      String scRelease = attrOrChildText(sidechainEl, "release");
      if (scRelease != null && !scRelease.isBlank()) {
        sound.setSidechainRelease(Math.abs(DelugeHexMapper.hexToFloat(scRelease.trim())));
        try {
          sound.setSidechainReleaseRaw(Integer.parseInt(scRelease.trim()));
        } catch (NumberFormatException ignored) {
        }
      }
      readAttrOrChildInt(sidechainEl, "syncLevel", 0, sound::setSidechainSyncLevel);
      readAttrOrChildInt(
          sidechainEl, "syncType", 0, v -> sound.setSidechainSyncType(syncTypeFromFile(v)));
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
        syncType = syncTypeFromFile(Integer.parseInt(syncTypeStr.trim())); // C sound.cpp:912/945
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }

    LfoModel lfo = new LfoModel(rateHz, waveform, depth, "NONE", isLocal, syncLevel, syncType);
    switch (lfoTag) {
      case "lfo1" -> sound.setLfo1(lfo);
      case "lfo2" -> sound.setLfo2(lfo);
      case "lfo3" -> sound.setLfo3(lfo);
      case "lfo4" -> sound.setLfo4(lfo);
      default -> {}
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
    readHexFloatUnipolar(dp, "lpfMorph", sound::setLpfMorph);
    readHexHz(dp, "hpfFrequency", sound::setHpfFreq);
    readHexFloatUnipolar(dp, "hpfResonance", sound::setHpfRes);
    readHexFloatUnipolar(dp, "hpfMorph", sound::setHpfMorph);
    String fmVal;
    if ((fmVal = attrOrChildText(dp, "modulator1Amount")) != null && !fmVal.isBlank()) {
      sound.setFmAmount(toUnipolar(DelugeHexMapper.hexToFloat(fmVal)));
      sound.setModulator1AmountQ31(DelugeHexMapper.hexToQ31(fmVal));
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
    // Set lossy UI float views first, then preserve exact 32-bit Q31 hardware knobs after so float
    // setters do not wipe the Q31 overrides.
    readHexFloatUnipolar(dp, "modFXRate", sound::setModFxRate);
    readHexFloatUnipolar(dp, "modFXDepth", sound::setModFxDepth);
    readHexFloatUnipolar(dp, "modFXOffset", sound::setModFxOffset);
    readHexFloatUnipolar(dp, "modFXFeedback", sound::setModFxFeedback);
    readHexFloatUnipolar(dp, "delayRate", sound::setDelayRate);
    readHexFloatUnipolar(dp, "delayFeedback", sound::setDelayFeedback);
    readHexQ31(dp, "modFXRate", sound::setModFxRateQ31);
    readHexQ31(dp, "modFXDepth", sound::setModFxDepthQ31);
    readHexQ31(dp, "modFXOffset", sound::setModFxOffsetQ31);
    readHexQ31(dp, "modFXFeedback", sound::setModFxFeedbackQ31);
    readHexQ31(dp, "delayRate", sound::setDelayRateQ31);
    readHexQ31(dp, "delayFeedback", sound::setDelayFeedbackQ31);
    readHexFloatUnipolar(dp, "reverbAmount", sound::setReverbAmount);
    readHexFloatUnipolar(dp, "arpeggiatorGate", sound::setArpeggiatorGate);
    readHexFloatUnipolar(dp, "arpeggiatorRate", sound::setArpeggiatorRate);
    readHexFloatUnipolar(dp, "portamento", sound::setPortamento);
    readHexFloatUnipolar(dp, "stutterRate", sound::setStutterRate);
    readHexFloatUnipolar(dp, "sampleRateReduction", sound::setSampleRateReduction);
    readHexFloatUnipolar(dp, "bitCrush", sound::setBitCrush);
    readHexFloatUnipolar(dp, "waveIndex", sound::setWaveIndex);
    readHexFloatUnipolar(dp, "oscAWavetablePosition", sound::setWaveIndex);
    readHexFloatUnipolar(dp, "oscBWavetablePosition", sound::setOsc2WaveIndex);
    readHexFloatUnipolar(dp, "compressorShape", sound::setCompressorShape);

    // LFO 1..4 rates inside defaultParams (C sound.cpp:3955-3970, 4084-4087)
    String[] lfoRateTags = {"lfo1Rate", "lfo2Rate", "lfo3Rate", "lfo4Rate"};
    for (int i = 0; i < 4; i++) {
      String lfoRateHex = attrOrChildText(dp, lfoRateTags[i]);
      if (lfoRateHex != null && !lfoRateHex.isBlank()) {
        LfoModel prev =
            switch (i) {
              case 0 -> sound.getLfo1();
              case 1 -> sound.getLfo2();
              case 2 -> sound.getLfo3();
              default -> sound.getLfo4();
            };
        LfoModel updated =
            new LfoModel(
                DelugeHexMapper.hexToLfoHz(lfoRateHex),
                prev.waveform(),
                prev.depth(),
                prev.target(),
                prev.isLocal(),
                prev.syncLevel(),
                prev.syncType());
        switch (i) {
          case 0 -> sound.setLfo1(updated);
          case 1 -> sound.setLfo2(updated);
          case 2 -> sound.setLfo3(updated);
          default -> sound.setLfo4(updated);
        }
      }
    }

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
        String prefix = envTag + ".";
        for (String sub : new String[] {"attack", "decay", "sustain", "release"}) {
          readHexQ31(envEl, sub, q -> sound.setRawDefaultParamQ31(prefix + sub, q));
        }
      }
    }

    // Equalizer child element (C mod_controllable_audio.cpp:557-582)
    Element eqEl = getFirstChild(dp, "equalizer");
    if (eqEl != null) {
      readHexFloat(eqEl, "bass", sound::setEqBass);
      readHexFloat(eqEl, "treble", sound::setEqTreble);
      readHexFloat(eqEl, "bassFrequency", sound::setEqBassFreq);
      readHexFloat(eqEl, "trebleFrequency", sound::setEqTrebleFreq);
      readHexQ31(eqEl, "bass", q -> sound.setRawDefaultParamQ31("eq.bass", q));
      readHexQ31(eqEl, "treble", q -> sound.setRawDefaultParamQ31("eq.treble", q));
      readHexQ31(eqEl, "bassFrequency", q -> sound.setRawDefaultParamQ31("eq.bassFrequency", q));
      readHexQ31(
          eqEl, "trebleFrequency", q -> sound.setRawDefaultParamQ31("eq.trebleFrequency", q));
    }

    // Preserve exact 32-bit Q31 values last so float/Hz/LFO setters above cannot clear them.
    for (String rawKey :
        new String[] {
          "oscAVolume",
          "oscAPulseWidth",
          "oscAWavetablePosition",
          "oscBVolume",
          "oscBPulseWidth",
          "oscBWavetablePosition",
          "pitchAdjust",
          "oscAPitchAdjust",
          "oscBPitchAdjust",
          "mod1PitchAdjust",
          "mod2PitchAdjust",
          "noiseVolume",
          "volume",
          "pan",
          "lpfFrequency",
          "lpfResonance",
          "lpfMorph",
          "hpfFrequency",
          "hpfResonance",
          "hpfMorph",
          "reverbAmount",
          "arpeggiatorGate",
          "arpeggiatorRate",
          "portamento",
          "stutterRate",
          "sampleRateReduction",
          "bitCrush",
          "waveIndex",
          "waveFold",
          "compressorShape",
          "lfo1Rate",
          "lfo2Rate",
          "lfo3Rate",
          "lfo4Rate"
        }) {
      readHexQ31(dp, rawKey, q -> sound.setRawDefaultParamQ31(rawKey, q));
    }

    // Patch cables inside defaultParams (replace any top-level cables and resolve legacy range)
    Element dpPcContainer = getFirstChild(dp, "patchCables");
    if (dpPcContainer != null) {
      sound.getPatchCables().clear();
      for (PatchCable pc : InstrumentXmlParser.parsePatchCableList(dpPcContainer, 0)) {
        sound.addPatchCable(pc);
      }
    }
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
    // C mod_controllable_audio.cpp:481-486, 760-785 — hardware writes "reverse"; tolerate legacy
    // "reversed" as fallback when "reverse" is absent.
    readBoolAttrOrChild(stut, "quantized", sound::setStutterQuantized);
    if (attrOrChildText(stut, "reverse") != null) {
      readBoolAttrOrChild(stut, "reverse", sound::setStutterReversed);
    } else {
      readBoolAttrOrChild(stut, "reversed", sound::setStutterReversed);
    }
    readBoolAttrOrChild(stut, "pingPong", sound::setStutterPingPong);
  }

  /**
   * C kit.cpp:106-131, 312-313; global_effectable.cpp:811-1037 — parses kit-level
   * GlobalEffectableForClip attributes/direct children and ONLY the direct {@code <defaultParams>}
   * child of {@code kitNode} (never matching nested {@code <soundSources><sound><defaultParams>}).
   */
  private static void parseKitGlobalAttributesAndDefaults(Element kitNode, KitTrackModel kit) {
    String lpfStr = attrOrDirectChildText(kitNode, "lpfMode");
    if (lpfStr != null) {
      if ("24dB".equalsIgnoreCase(lpfStr)) kit.setLpfMode(FilterMode.LADDER_24);
      else if ("24dBDrive".equalsIgnoreCase(lpfStr) || "DRIVE".equalsIgnoreCase(lpfStr))
        kit.setLpfMode(FilterMode.DRIVE);
      else if ("SVF_Band".equalsIgnoreCase(lpfStr) || "SVF Band".equalsIgnoreCase(lpfStr))
        kit.setLpfMode(FilterMode.SVF_BAND);
      else if ("SVF_Notch".equalsIgnoreCase(lpfStr) || "SVF Notch".equalsIgnoreCase(lpfStr))
        kit.setLpfMode(FilterMode.SVF_NOTCH);
      else if ("SVF".equalsIgnoreCase(lpfStr)) kit.setLpfMode(FilterMode.SVF);
      else if ("Off".equalsIgnoreCase(lpfStr)) kit.setLpfMode(FilterMode.OFF);
      else kit.setLpfMode(FilterMode.LADDER_12);
    }
    String hpfStr = attrOrDirectChildText(kitNode, "hpfMode");
    if (hpfStr != null) {
      if ("12dB".equalsIgnoreCase(hpfStr)
          || "24dB".equalsIgnoreCase(hpfStr)
          || "24dBDrive".equalsIgnoreCase(hpfStr)
          || "Off".equalsIgnoreCase(hpfStr)) kit.setHpfMode(FilterMode.OFF);
      else if ("SVF".equalsIgnoreCase(hpfStr)) kit.setHpfMode(FilterMode.SVF);
      else if ("SVF_BAND".equalsIgnoreCase(hpfStr) || "SVF Band".equalsIgnoreCase(hpfStr))
        kit.setHpfMode(FilterMode.SVF_BAND);
      else if ("SVF_NOTCH".equalsIgnoreCase(hpfStr) || "SVF Notch".equalsIgnoreCase(hpfStr))
        kit.setHpfMode(FilterMode.SVF_NOTCH);
      else kit.setHpfMode(FilterMode.LADDER_12);
    }
    String routeStr = attrOrDirectChildText(kitNode, "filterRoute");
    if (routeStr != null) {
      if ("L2H".equalsIgnoreCase(routeStr)) kit.setFilterRoute(1);
      else if ("PARA".equalsIgnoreCase(routeStr) || "PARALLEL".equalsIgnoreCase(routeStr))
        kit.setFilterRoute(2);
      else kit.setFilterRoute(0);
    }
    String mfxStr = attrOrDirectChildText(kitNode, "modFXType");
    if (mfxStr == null) mfxStr = attrOrDirectChildText(kitNode, "modFxType");
    if (mfxStr != null) kit.setModFxType(mfxStr);

    String clipAmtStr = attrOrDirectChildText(kitNode, "clippingAmount");
    if (clipAmtStr != null) {
      try {
        kit.setClippingAmount(Math.round(Float.parseFloat(clipAmtStr)));
      } catch (NumberFormatException ignored) {
      }
    }

    Element directDelay = directChild(kitNode, "delay");
    if (directDelay != null) {
      readAttrOrChildInt(directDelay, "pingPong", 1, kit::setDelayPingPong);
      readAttrOrChildInt(directDelay, "analog", 0, kit::setDelayAnalog);
      readAttrOrChildInt(directDelay, "syncLevel", 7, kit::setDelaySyncLevel);
      readAttrOrChildInt(
          directDelay, "syncType", 0, v -> kit.setDelaySyncType(syncTypeFromFile(v)));
    }

    Element kitDp = directChild(kitNode, "defaultParams");
    if (kitDp == null) {
      return;
    }
    String v;
    if ((v = attrOrDirectChildText(kitDp, "volume")) != null) {
      int q31 = DelugeHexMapper.hexToQ31(v);
      kit.setVolume(DelugeHexMapper.q31ToUnipolarFloatUnified(q31));
      kit.setRawDefaultParamQ31("volume", q31);
    }
    if ((v = attrOrDirectChildText(kitDp, "pan")) != null) {
      int q31 = DelugeHexMapper.hexToQ31(v);
      kit.setPan(DelugeHexMapper.q31ToFloat(q31));
      kit.setRawDefaultParamQ31("pan", q31);
    }
    if ((v = attrOrDirectChildText(kitDp, "reverbAmount")) != null) {
      int q31 = DelugeHexMapper.hexToQ31(v);
      kit.setReverbAmount(DelugeHexMapper.q31ToUnipolarFloatUnified(q31));
      kit.setRawDefaultParamQ31("reverbAmount", q31);
    }
    if ((v = attrOrDirectChildText(kitDp, "bitCrush")) != null) {
      int q31 = DelugeHexMapper.hexToQ31(v);
      kit.setBitCrush(DelugeHexMapper.q31ToUnipolarFloatUnified(q31));
      kit.setRawDefaultParamQ31("bitCrush", q31);
    }
    if ((v = attrOrDirectChildText(kitDp, "sampleRateReduction")) != null) {
      int q31 = DelugeHexMapper.hexToQ31(v);
      kit.setSampleRateReduction(DelugeHexMapper.q31ToUnipolarFloatUnified(q31));
      kit.setRawDefaultParamQ31("sampleRateReduction", q31);
    }
    Element dpDelay = directChild(kitDp, "delay");
    if (dpDelay != null) {
      if ((v = attrOrDirectChildText(dpDelay, "rate")) != null) {
        int q31 = DelugeHexMapper.hexToQ31(v);
        kit.setDelayRate(DelugeHexMapper.q31ToUnipolarFloatUnified(q31));
        kit.setRawDefaultParamQ31("delay.rate", q31);
      }
      if ((v = attrOrDirectChildText(dpDelay, "feedback")) != null) {
        int q31 = DelugeHexMapper.hexToQ31(v);
        kit.setDelayFeedback(DelugeHexMapper.q31ToUnipolarFloatUnified(q31));
        kit.setRawDefaultParamQ31("delay.feedback", q31);
      }
    }
    Element dpLpf = directChild(kitDp, "lpf");
    if (dpLpf != null) {
      if ((v = attrOrDirectChildText(dpLpf, "frequency")) != null) {
        int q31 = DelugeHexMapper.hexToQ31(v);
        kit.setLpfFreq(DelugeHexMapper.q31ToHz(q31));
        kit.setRawDefaultParamQ31("lpf.frequency", q31);
      }
      if ((v = attrOrDirectChildText(dpLpf, "resonance")) != null) {
        int q31 = DelugeHexMapper.hexToQ31(v);
        kit.setLpfRes(DelugeHexMapper.q31ToUnipolarFloatUnified(q31));
        kit.setRawDefaultParamQ31("lpf.resonance", q31);
      }
      if ((v = attrOrDirectChildText(dpLpf, "morph")) != null) {
        int q31 = DelugeHexMapper.hexToQ31(v);
        kit.setLpfMorph(DelugeHexMapper.q31ToUnipolarFloatUnified(q31));
        kit.setRawDefaultParamQ31("lpf.morph", q31);
      }
    }
    Element dpHpf = directChild(kitDp, "hpf");
    if (dpHpf != null) {
      if ((v = attrOrDirectChildText(dpHpf, "frequency")) != null) {
        int q31 = DelugeHexMapper.hexToQ31(v);
        kit.setHpfFreq(DelugeHexMapper.q31ToHz(q31));
        kit.setRawDefaultParamQ31("hpf.frequency", q31);
      }
      if ((v = attrOrDirectChildText(dpHpf, "resonance")) != null) {
        int q31 = DelugeHexMapper.hexToQ31(v);
        kit.setHpfRes(DelugeHexMapper.q31ToUnipolarFloatUnified(q31));
        kit.setRawDefaultParamQ31("hpf.resonance", q31);
      }
      if ((v = attrOrDirectChildText(dpHpf, "morph")) != null) {
        int q31 = DelugeHexMapper.hexToQ31(v);
        kit.setHpfMorph(DelugeHexMapper.q31ToUnipolarFloatUnified(q31));
        kit.setRawDefaultParamQ31("hpf.morph", q31);
      }
    }
    Element dpEq = directChild(kitDp, "equalizer");
    if (dpEq != null) {
      if ((v = attrOrDirectChildText(dpEq, "bass")) != null) {
        int q31 = DelugeHexMapper.hexToQ31(v);
        kit.setEqBass(DelugeHexMapper.q31ToFloat(q31));
        kit.setRawDefaultParamQ31("equalizer.bass", q31);
      }
      if ((v = attrOrDirectChildText(dpEq, "treble")) != null) {
        int q31 = DelugeHexMapper.hexToQ31(v);
        kit.setEqTreble(DelugeHexMapper.q31ToFloat(q31));
        kit.setRawDefaultParamQ31("equalizer.treble", q31);
      }
      if ((v = attrOrDirectChildText(dpEq, "bassFrequency")) != null) {
        int q31 = DelugeHexMapper.hexToQ31(v);
        kit.setEqBassFreq(DelugeHexMapper.q31ToFloat(q31));
        kit.setRawDefaultParamQ31("equalizer.bassFrequency", q31);
      }
      if ((v = attrOrDirectChildText(dpEq, "trebleFrequency")) != null) {
        int q31 = DelugeHexMapper.hexToQ31(v);
        kit.setEqTrebleFreq(DelugeHexMapper.q31ToFloat(q31));
        kit.setRawDefaultParamQ31("equalizer.trebleFrequency", q31);
      }
    }
  }

  /**
   * Reads a unison field as tag or attribute, accepting the format's plain integer and tolerating a
   * legacy {@code 0x...} hex Q31 (which older builds of our own serializer emitted). Returns null
   * when absent so the caller can leave the model default alone.
   */
  private static Float unisonIntOrHex(Element unisonEl, String name) {
    String raw = readAttr(unisonEl, name);
    if (raw == null || raw.isBlank()) {
      raw = getChildText(unisonEl, name);
    }
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String val = raw.trim();
    try {
      if (val.startsWith("0x") || val.startsWith("0X")) {
        return Math.abs(DelugeHexMapper.hexToFloat(val));
      }
      return Math.abs(Float.parseFloat(val));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static void readHexQ31(
      Element parent, String tag, java.util.function.Consumer<Integer> setter) {
    String hex = attrOrChildText(parent, tag);
    if (hex != null && !hex.isBlank()) {
      setter.accept(DelugeHexMapper.hexToQ31(hex));
    }
  }
}
