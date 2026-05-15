package org.chuck.deluge.xml;

import java.io.File;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.SynthTrackModel;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Exports SynthTrackModel or Bridge state to Deluge-compatible XML files. */
public class DelugeXmlExporter {

  public static void saveSynthPreset(
      SynthTrackModel model, BridgeContract bridge, int trackIndex, File file) throws Exception {
    DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

    // root element
    Document doc = docBuilder.newDocument();
    Element rootElement = doc.createElement("sound");
    doc.appendChild(rootElement);

    rootElement.setAttribute("name", model.getName());
    rootElement.setAttribute("polyphonic", "auto");

    // Oscillator 1
    Element osc1 = doc.createElement("osc1");
    osc1.setAttribute("type", model.getOsc1Type());
    rootElement.appendChild(osc1);

    // Envelopes (we extract current live values from Bridge)
    // Bridge maps synth tracks 4-7 to Env indices 0-3 for Amp
    int envIdx = trackIndex - 4;
    // Note: BridgeContract might need getEnv methods for individual parameters
    // For now we use the values stored in the model, or add getters to bridge
    for (int i = 0; i < 2; i++) {
      Element env = doc.createElement("envelope");
      // Use model values for persistence
      env.setAttribute("attack", DelugeHexMapper.floatToHex(model.getEnv(i).attack()));
      env.setAttribute("decay", DelugeHexMapper.floatToHex(model.getEnv(i).decay()));
      env.setAttribute("sustain", DelugeHexMapper.floatToHex(model.getEnv(i).sustain()));
      env.setAttribute("release", DelugeHexMapper.floatToHex(model.getEnv(i).release()));
      rootElement.appendChild(env);
    }

    // Filter
    Element lpf = doc.createElement("lpf");
    lpf.setAttribute(
        "frequency", DelugeHexMapper.floatToHex((float) bridge.getTrackFilterFreq(trackIndex)));
    lpf.setAttribute(
        "resonance", DelugeHexMapper.floatToHex((float) bridge.getTrackFilterRes(trackIndex)));
    rootElement.appendChild(lpf);

    // Arp (Custom attributes for our emulator, or mapping to official ones)
    Element arp = doc.createElement("arpeggiator");
    arp.setAttribute("active", bridge.getArpOn(trackIndex) ? "1" : "0");
    arp.setAttribute("rate", String.valueOf(bridge.getArpRate(trackIndex)));
    arp.setAttribute("octaves", String.valueOf(bridge.getArpOctave(trackIndex)));
    arp.setAttribute("gate", String.valueOf(bridge.getArpGate(trackIndex)));
    arp.setAttribute("syncLevel", String.valueOf(bridge.getArpSyncLevel(trackIndex)));
    arp.setAttribute("noteMode", String.valueOf(bridge.getArpNoteMode(trackIndex)));
    arp.setAttribute("octaveMode", String.valueOf(bridge.getArpOctaveMode(trackIndex)));
    arp.setAttribute("stepRepeat", String.valueOf(bridge.getArpStepRepeat(trackIndex)));
    arp.setAttribute("rhythmIndex", String.valueOf(bridge.getArpRhythm(trackIndex)));
    arp.setAttribute("seqLength", String.valueOf(bridge.getArpSeqLength(trackIndex)));
    arp.setAttribute("octaveSpread", String.valueOf(bridge.getArpOctaveSpread(trackIndex)));
    arp.setAttribute("gateSpread", String.valueOf(bridge.getArpGateSpread(trackIndex)));
    arp.setAttribute("velSpread", String.valueOf(bridge.getArpVelSpread(trackIndex)));
    arp.setAttribute("ratchetAmount", String.valueOf(bridge.getArpRatchet(trackIndex)));
    arp.setAttribute("noteProbability", String.valueOf(bridge.getArpNoteProbability(trackIndex)));
    arp.setAttribute("chordPolyphony", String.valueOf(bridge.getArpChordPoly(trackIndex)));
    arp.setAttribute("chordProbability", String.valueOf(bridge.getArpChordProb(trackIndex)));
    rootElement.appendChild(arp);

    // Write the content into xml file
    TransformerFactory transformerFactory = TransformerFactory.newInstance();
    Transformer transformer = transformerFactory.newTransformer();
    transformer.setOutputProperty(OutputKeys.INDENT, "yes");
    DOMSource source = new DOMSource(doc);
    StreamResult result = new StreamResult(file);

    transformer.transform(source, result);
  }
}
