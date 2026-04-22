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
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;
import org.chuck.deluge.model.TrackModel;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Serializes the in-memory ProjectModel to a Deluge-compatible Song XML format. */
public class ProjectSerializer {

  public static void save(ProjectModel model, File file) throws Exception {
    DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

    // Root elements
    Document doc = docBuilder.newDocument();
    Element rootElement = doc.createElement("song");
    doc.appendChild(rootElement);

    // Global settings
    rootElement.setAttribute("tempo", String.valueOf(model.getBpm()));
    rootElement.setAttribute(
        "swing", org.chuck.deluge.xml.DelugeHexMapper.floatToHex(model.getSwing()));

    // Tracks (Clips)
    Element instruments = doc.createElement("instruments");
    rootElement.appendChild(instruments);

    for (TrackModel track : model.getTracks()) {
      if (track instanceof KitTrackModel kit) {
        Element trackElem = doc.createElement("kit");
        Element presetSlot = doc.createElement("presetSlot");
        presetSlot.setTextContent(kit.getName());
        trackElem.appendChild(presetSlot);

        for (KitTrackModel.KitSound sound : kit.getSounds()) {
          Element soundElem = doc.createElement("sound");
          Element nameElem = doc.createElement("name");
          nameElem.setTextContent(sound.getName());
          soundElem.appendChild(nameElem);
          
          Element sample = doc.createElement("sample");
          sample.setAttribute("fileName", sound.getSamplePath());
          soundElem.appendChild(sample);
          
          trackElem.appendChild(soundElem);
        }
        instruments.appendChild(trackElem);
      } else if (track instanceof SynthTrackModel synth) {
        Element trackElem = doc.createElement("sound");
        Element presetSlot = doc.createElement("presetSlot");
        presetSlot.setTextContent(synth.getName());
        trackElem.appendChild(presetSlot);

        Element osc1 = doc.createElement("osc1");
        osc1.setAttribute("type", synth.getOsc1Type().toLowerCase());
        trackElem.appendChild(osc1);

        // (Full serialization of all ADSR, LFO, and Patch cables would go here)
        instruments.appendChild(trackElem);
      }
    }

    // Serialize Tracks (Clips)
    Element tracksElem = doc.createElement("tracks");
    rootElement.appendChild(tracksElem);

    for (TrackModel track : model.getTracks()) {
      for (org.chuck.deluge.model.ClipModel clip : track.getClips()) {
        Element clipTrackElem = doc.createElement("track");
        tracksElem.appendChild(clipTrackElem);

        Element noteRowsElem = doc.createElement("noteRows");
        clipTrackElem.appendChild(noteRowsElem);

        for (int r = 0; r < clip.getRowCount(); r++) {
          Element noteRowElem = doc.createElement("noteRow");
          noteRowsElem.appendChild(noteRowElem);

          java.util.List<org.chuck.deluge.model.StepData> row = new java.util.ArrayList<>();
          for (int s = 0; s < clip.getStepCount(); s++) {
            row.add(clip.getStep(r, s));
          }

          String hexData = org.chuck.deluge.xml.DelugeNoteDataMapper.encodeRow(row);

          Element noteDataElem = doc.createElement("noteData");
          noteDataElem.setTextContent(hexData);
          noteRowElem.appendChild(noteDataElem);
        }
      }
    }

    // Write the content into xml file
    TransformerFactory transformerFactory = TransformerFactory.newInstance();
    Transformer transformer = transformerFactory.newTransformer();

    // Pretty print
    transformer.setOutputProperty(OutputKeys.INDENT, "yes");
    transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

    DOMSource source = new DOMSource(doc);
    StreamResult result = new StreamResult(file);
    transformer.transform(source, result);
  }
}
