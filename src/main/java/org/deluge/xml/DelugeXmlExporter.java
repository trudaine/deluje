package org.deluge.xml;

import java.io.File;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.deluge.BridgeContract;
import org.deluge.model.SynthTrackModel;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Exports a core subset of {@link SynthTrackModel} state (oscillator types, polyphony, synth mode,
 * filter/mixer/envelope {@code <defaultParams>}, and {@code <arpeggiator>}) to a Deluge-compatible
 * {@code <sound>} XML preset file. Callers that need the complete preset tree (LFOs, FM modulators,
 * unison, FX, patch cables, mod knobs, and sample zones) should use {@link
 * KitSynthSerializer#saveSynth(SynthTrackModel, File)}.
 */
public class DelugeXmlExporter {

  /**
   * Writes a core-subset {@code <sound>} XML preset from {@code model} to {@code file}.
   *
   * @deprecated {@code bridge} and {@code trackIndex} are unused; call {@link
   *     #saveSynthPreset(SynthTrackModel, File)} instead.
   */
  @Deprecated
  public static void saveSynthPreset(
      SynthTrackModel model, BridgeContract bridge, int trackIndex, File file) throws Exception {
    saveSynthPreset(model, file);
  }

  public static void saveSynthPreset(SynthTrackModel model, File file) throws Exception {
    DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

    // root element
    Document doc = docBuilder.newDocument();
    Element rootElement = doc.createElement("sound");
    doc.appendChild(rootElement);

    rootElement.setAttribute("name", model.getName());
    // C sound.cpp:3744-3745, 4167, 4175 & functions.cpp:810-852 — polyphony and synth mode strings
    // (DX7 sources require synthMode != FM on hardware).
    boolean isDx7 =
        model.getSynthMode() != 2
            && ("DX7".equalsIgnoreCase(model.getOsc1Type())
                || (model.getDx7Patch() != null && !model.getDx7Patch().isEmpty()));
    int exportedMode = (isDx7 && model.getSynthMode() == 1) ? 0 : model.getSynthMode();
    rootElement.setAttribute("polyphonic", polyphonyToFirmwareString(model.getPolyphony()));
    rootElement.setAttribute("mode", synthModeToFirmwareString(exportedMode));
    // C filter_config.cpp:8-14, mod_controllable_audio.cpp:411 — filterRoute attribute ("H2L",
    // "L2H", "PARA")
    String filterRouteStr =
        switch (model.getFilterRoute()) {
          case 1 -> "L2H";
          case 2 -> "PARA";
          default -> "H2L";
        };
    rootElement.setAttribute("filterRoute", filterRouteStr);

    // Oscillator 1 & 2 (C sound.cpp:3748-3758, 4194-4195 & functions.cpp:680-721)
    Element osc1 = doc.createElement("osc1");
    osc1.setAttribute("type", isDx7 ? "dx7" : oscTypeToFirmwareString(model.getOsc1Type()));
    if (isDx7 && model.getDx7Patch() != null && !model.getDx7Patch().isEmpty()) {
      osc1.setAttribute("dx7patch", model.getDx7Patch());
      if (model.getEngineType() != 0) {
        osc1.setAttribute("dx7enginemode", String.valueOf(model.getEngineType()));
      }
      if (model.getDx7RandomDetune() != 0) {
        osc1.setAttribute("dx7randomdetune", String.valueOf(model.getDx7RandomDetune()));
      }
    }
    rootElement.appendChild(osc1);

    if (model.getOsc2Type() != null && !"NONE".equalsIgnoreCase(model.getOsc2Type().trim())) {
      Element osc2 = doc.createElement("osc2");
      osc2.setAttribute("type", oscTypeToFirmwareString(model.getOsc2Type()));
      rootElement.appendChild(osc2);
    }

    // <defaultParams> (C sound.cpp:4056-4157): filter, mixer, arp rate, and envelopes 1..4.
    Element defaultParams = doc.createElement("defaultParams");
    appendTextChild(
        doc, defaultParams, "arpeggiatorRate", String.format("0x%08X", model.getArpRateQ31()));
    appendTextChild(
        doc, defaultParams, "lpfFrequency", DelugeHexMapper.hzToHex(model.getLpfFreq()));
    appendTextChild(
        doc,
        defaultParams,
        "lpfResonance",
        DelugeHexMapper.unipolarFloatToHexUnified(model.getLpfRes()));
    appendTextChild(
        doc,
        defaultParams,
        "lpfMorph",
        DelugeHexMapper.unipolarFloatToHexUnified(model.getLpfMorph()));
    appendTextChild(
        doc, defaultParams, "hpfFrequency", DelugeHexMapper.hzToHex(model.getHpfFreq()));
    appendTextChild(
        doc,
        defaultParams,
        "hpfResonance",
        DelugeHexMapper.unipolarFloatToHexUnified(model.getHpfRes()));
    appendTextChild(
        doc,
        defaultParams,
        "hpfMorph",
        DelugeHexMapper.unipolarFloatToHexUnified(model.getHpfMorph()));
    appendTextChild(
        doc,
        defaultParams,
        "oscAVolume",
        DelugeHexMapper.unipolarFloatToHexUnified(model.getOscAVolume()));
    appendTextChild(
        doc,
        defaultParams,
        "oscBVolume",
        DelugeHexMapper.unipolarFloatToHexUnified(model.getOscBVolume()));
    appendTextChild(
        doc,
        defaultParams,
        "noiseVolume",
        DelugeHexMapper.unipolarFloatToHexUnified(model.getNoiseVol()));
    appendTextChild(
        doc, defaultParams, "volume", DelugeHexMapper.unipolarFloatToHexUnified(model.getVolume()));
    appendTextChild(doc, defaultParams, "pan", DelugeHexMapper.floatToHex(model.getPan()));

    for (int i = 0; i < 4; i++) {
      Element env = doc.createElement("envelope" + (i + 1));
      org.deluge.model.EnvelopeModel envModel = model.getEnv(i);
      appendTextChild(doc, env, "attack", DelugeHexMapper.envTimeToHex(envModel.attack()));
      appendTextChild(doc, env, "decay", DelugeHexMapper.envTimeToHex(envModel.decay()));
      appendTextChild(doc, env, "sustain", DelugeHexMapper.sustainToHex(envModel.sustain()));
      appendTextChild(doc, env, "release", DelugeHexMapper.envTimeToHex(envModel.release()));
      defaultParams.appendChild(env);
    }
    rootElement.appendChild(defaultParams);

    // Arp (Custom attributes for our emulator, or mapping to official ones)
    Element arp = doc.createElement("arpeggiator");
    org.deluge.model.ArpModel arpModel =
        model.getArp() != null ? model.getArp() : org.deluge.model.ArpModel.defaultConfig();
    arp.setAttribute("active", arpModel.active() ? "1" : "0");
    arp.setAttribute(ArpXmlCodec.FORMAT_ATTR, ArpXmlCodec.FORMAT_C);
    // The C keys (writeCommonParamsToFile, arpeggiator.cpp): without "arpMode" the firmware loads
    // the arp OFF, and without "numOctaves" at 1 octave.
    arp.setAttribute("mode", arpModel.active() ? "arp" : "off");
    arp.setAttribute("arpMode", arpModel.active() ? "arp" : "off");
    arp.setAttribute("numOctaves", String.valueOf(arpModel.octaves()));
    arp.setAttribute("syncType", String.valueOf(ArpXmlCodec.syncTypeToFile(arpModel.syncType())));
    arp.setAttribute("mpeVelocity", ArpXmlCodec.mpeVelocityToFile(arpModel.mpeVelocity()));
    arp.setAttribute("rate", String.valueOf(arpModel.rate()));
    arp.setAttribute("octaves", String.valueOf(arpModel.octaves()));
    arp.setAttribute("gate", String.valueOf(arpModel.gate()));
    arp.setAttribute(
        "syncLevel",
        String.valueOf(
            ArpXmlCodec.syncFileValueFromDenominator(
                arpModel.syncLevel(), ArpXmlCodec.DEFAULT_INPUT_TICK_MAGNITUDE)));
    arp.setAttribute("noteMode", ArpXmlCodec.noteModeToFile(arpModel.noteMode()));
    arp.setAttribute("octaveMode", ArpXmlCodec.octaveModeToFile(arpModel.octaveMode()));
    arp.setAttribute("stepRepeat", String.valueOf(arpModel.stepRepeat()));
    arp.setAttribute("rhythmIndex", String.valueOf(arpModel.rhythmIndex()));
    arp.setAttribute("seqLength", String.valueOf(arpModel.seqLength()));
    arp.setAttribute("octaveSpread", String.valueOf(arpModel.octaveSpread()));
    arp.setAttribute("gateSpread", String.valueOf(arpModel.gateSpread()));
    arp.setAttribute("velSpread", String.valueOf(arpModel.velSpread()));
    arp.setAttribute("ratchetAmount", String.valueOf(arpModel.ratchetAmount()));
    arp.setAttribute("noteProbability", String.valueOf(arpModel.noteProbability()));
    arp.setAttribute("chordPolyphony", String.valueOf(arpModel.chordPolyphony()));
    arp.setAttribute("chordProbability", String.valueOf(arpModel.chordProbability()));
    rootElement.appendChild(arp);

    // Write the content into xml file
    TransformerFactory transformerFactory = TransformerFactory.newInstance();
    Transformer transformer = transformerFactory.newTransformer();
    transformer.setOutputProperty(OutputKeys.INDENT, "yes");
    DOMSource source = new DOMSource(doc);
    StreamResult result = new StreamResult(file);

    transformer.transform(source, result);
  }

  private static void appendTextChild(Document doc, Element parent, String tag, String text) {
    Element child = doc.createElement(tag);
    child.setTextContent(text);
    parent.appendChild(child);
  }

  /** C polyphonyModeToString (functions.cpp:835-852). */
  private static String polyphonyToFirmwareString(SynthTrackModel.PolyphonyMode polyphony) {
    if (polyphony == null) {
      return "poly";
    }
    return switch (polyphony) {
      case MONO -> "mono";
      case AUTO -> "auto";
      case LEGATO -> "legato";
      case CHOKE -> "choke";
      default -> "poly";
    };
  }

  /** C synthModeToString (functions.cpp:810-821). */
  private static String synthModeToFirmwareString(int synthMode) {
    return switch (synthMode) {
      case 1 -> "fm";
      case 2 -> "ringmod";
      default -> "subtractive";
    };
  }

  /** C oscTypeToString (functions.cpp:680-721). */
  private static String oscTypeToFirmwareString(String modelType) {
    if (modelType == null) {
      return "square";
    }
    return switch (modelType.trim().toUpperCase().replace("_", "").replace(" ", "")) {
      case "SAW" -> "saw";
      case "ANALOGSAW", "ANALOGSAW2" -> "analogSaw";
      case "ANALOGSQUARE" -> "analogSquare";
      case "SINE" -> "sine";
      case "TRIANGLE" -> "triangle";
      case "SAMPLE" -> "sample";
      case "WAVETABLE" -> "wavetable";
      case "INPUTL", "INLEFT" -> "inLeft";
      case "INPUTR", "INRIGHT" -> "inRight";
      case "INPUTSTEREO", "INSTEREO" -> "inStereo";
      case "DX7" -> "dx7";
      default -> "square";
    };
  }
}
