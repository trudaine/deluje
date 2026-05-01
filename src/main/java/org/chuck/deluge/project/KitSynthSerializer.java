package org.chuck.deluge.project;

import java.io.File;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.ModKnob;
import org.chuck.deluge.model.PatchCable;
import org.chuck.deluge.model.SynthTrackModel;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Serializes a single Kit or Synth track to a standalone preset XML file. */
public class KitSynthSerializer {

  public static void saveKit(KitTrackModel kit, File file) throws Exception {
    Document doc = newDoc();
    Element root = doc.createElement("kit");
    doc.appendChild(root);

    root.setAttribute("name", kit.getName());

    for (KitTrackModel.KitSound sound : kit.getSounds()) {
      Element soundElem = doc.createElement("sound");

      Element nameElem = doc.createElement("name");
      nameElem.setTextContent(sound.getName());
      soundElem.appendChild(nameElem);

      Element sampleElem = doc.createElement("sample");
      sampleElem.setAttribute(
          "fileName", sound.getSamplePath() != null ? sound.getSamplePath() : "");
      soundElem.appendChild(sampleElem);

      if (sound.getPitchSemitones() != 0) {
        Element pitchElem = doc.createElement("pitch");
        pitchElem.setTextContent(String.valueOf(sound.getPitchSemitones()));
        soundElem.appendChild(pitchElem);
      }
      if (sound.getMuteGroup() > 0) {
        Element mgElem = doc.createElement("muteGroup");
        mgElem.setTextContent(String.valueOf(sound.getMuteGroup()));
        soundElem.appendChild(mgElem);
      }
      if (sound.isReverse()) {
        Element revElem = doc.createElement("reverse");
        revElem.setTextContent("1");
        soundElem.appendChild(revElem);
      }

      root.appendChild(soundElem);
    }

    write(doc, file);
  }

  public static void saveSynth(SynthTrackModel synth, File file) throws Exception {
    Document doc = newDoc();
    Element root = doc.createElement("sound");
    doc.appendChild(root);

    root.setAttribute("name", synth.getName());

    Element osc1 = doc.createElement("osc1");
    osc1.setAttribute("type", synth.getOsc1Type().toLowerCase());
    root.appendChild(osc1);

    Element osc2 = doc.createElement("osc2");
    osc2.setAttribute("type", synth.getOsc2Type().toLowerCase());
    root.appendChild(osc2);

    Element filter = doc.createElement("lpf");
    filter.setAttribute("freq", String.valueOf(synth.getLpfFreq()));
    filter.setAttribute("res", String.valueOf(synth.getLpfRes()));
    root.appendChild(filter);

    // ── Patch Cables ──
    if (!synth.getPatchCables().isEmpty()) {
      Element cablesElem = doc.createElement("patchCables");
      for (PatchCable pc : synth.getPatchCables()) {
        Element cable = doc.createElement("patchCable");
        appendTextChild(doc, cable, "source", pc.source());
        appendTextChild(doc, cable, "destination", pc.destination());
        appendTextChild(doc, cable, "amount", String.valueOf(pc.amount()));
        cablesElem.appendChild(cable);
      }
      root.appendChild(cablesElem);
    }

    // ── Mod Knobs ──
    boolean hasKnobs = synth.getModKnobs().stream().anyMatch(k -> !"NONE".equals(k.param()));
    if (hasKnobs) {
      Element knobsElem = doc.createElement("modKnobs");
      for (ModKnob mk : synth.getModKnobs()) {
        if (!"NONE".equals(mk.param())) {
          Element knob = doc.createElement("modKnob");
          appendTextChild(doc, knob, "controlsParam", mk.param());
          knobsElem.appendChild(knob);
        }
      }
      root.appendChild(knobsElem);
    }

    // ── Automation Data (from the first clip) ──
    java.util.List<org.chuck.deluge.model.ClipModel> clips = synth.getClips();
    if (!clips.isEmpty()) {
      org.chuck.deluge.model.ClipModel clip = clips.get(0);
      String[] autoParams = org.chuck.deluge.model.AutomationParam.ALL;
      boolean hasAuto = false;
      for (String ap : autoParams) {
        if (clip.hasAutomation(ap)) { hasAuto = true; break; }
      }
      if (hasAuto) {
        Element autoElem = doc.createElement("automation");
        for (String ap : autoParams) {
          if (!clip.hasAutomation(ap)) continue;
          float[] arr = clip.getAutomationArray(ap);
          Element paramElem = doc.createElement("param");
          paramElem.setAttribute("name", ap);
          for (int s = 0; s < arr.length; s++) {
            if (arr[s] >= 0f) {
              Element stepElem = doc.createElement("step");
              stepElem.setAttribute("index", String.valueOf(s));
              stepElem.setTextContent(String.valueOf(arr[s]));
              paramElem.appendChild(stepElem);
            }
          }
          autoElem.appendChild(paramElem);
        }
        root.appendChild(autoElem);
      }
    }

    write(doc, file);
  }

  private static void appendTextChild(Document doc, Element parent, String tag, String value) {
    Element child = doc.createElement(tag);
    child.setTextContent(value);
    parent.appendChild(child);
  }

  private static Document newDoc() throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    DocumentBuilder b = f.newDocumentBuilder();
    return b.newDocument();
  }

  private static void write(Document doc, File file) throws Exception {
    Transformer t = TransformerFactory.newInstance().newTransformer();
    t.setOutputProperty(OutputKeys.INDENT, "yes");
    t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
    t.transform(new DOMSource(doc), new StreamResult(file));
  }
}
