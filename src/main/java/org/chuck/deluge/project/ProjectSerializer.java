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
      Element trackElem = doc.createElement("instrument");
      trackElem.setAttribute("name", track.getName());

      if (track instanceof KitTrackModel kit) {
        trackElem.setAttribute("type", "kit");
        Element sample = doc.createElement("sample");
        sample.setAttribute("fileName", kit.getSamplePath());
        trackElem.appendChild(sample);
      } else if (track instanceof SynthTrackModel synth) {
        trackElem.setAttribute("type", "synth");

        Element osc1 = doc.createElement("osc1");
        osc1.setAttribute("type", synth.getOsc1Type().toLowerCase());
        trackElem.appendChild(osc1);

        // (Full serialization of all ADSR, LFO, and Patch cables would go here)
      }

      instruments.appendChild(trackElem);
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
