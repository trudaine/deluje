package org.chuck.deluge.project;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.chuck.deluge.model.Drum;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SoundDrum;
import org.chuck.deluge.model.SynthTrackModel;
import org.chuck.deluge.model.TrackModel;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Serializes the in-memory ProjectModel to a Deluge-compatible Song XML format. */
public class ProjectSerializer {

  /**
   * Resolves a sample path against the samples directory and copies it into a subdirectory
   * next to the song XML, preserving the relative path structure after "SAMPLES/". Rewrites
   * the PathKitSound's samplePath to the new relative location.
   *
   * <p>Samples are loaded from the filesystem only, using PreferencesManager paths.</p>
   */
  private static void cloneSamples(ProjectModel model, File songFile) throws Exception {
    String samplesDir = PreferencesManager.getSamplesDir();
    if (samplesDir == null || samplesDir.isBlank()) return;
    Path samplesRoot = Path.of(samplesDir);
    if (!Files.isDirectory(samplesRoot)) return;

    // Create a data directory next to the song file
    String songName = songFile.getName();
    int dot = songName.lastIndexOf('.');
    if (dot > 0) songName = songName.substring(0, dot);
    Path dataDir = songFile.toPath().getParent().resolve(songName + ".DATA");

    Set<String> seenPaths = new HashSet<>();

    for (TrackModel track : model.getTracks()) {
      if (track instanceof KitTrackModel kit) {
        for (Drum drum : kit.getDrums()) {
          if (!(drum instanceof SoundDrum sound)) continue;
          String sp = sound.getSamplePath();
          if (sp == null || sp.isBlank() || seenPaths.contains(sp)) continue;
          seenPaths.add(sp);

          // Determine destination path: <songName>.DATA/<path-after-SAMPLES/>
          // e.g. songName.DATA/DRUMS/Kick/808 Kick.wav
          String relPart = sp;
          if (relPart.startsWith("SAMPLES/") || relPart.startsWith("SAMPLES\\")) {
            relPart = relPart.substring("SAMPLES/".length());
          }
          Path dest = dataDir.resolve(relPart);
          Files.createDirectories(dest.getParent());

          // Try filesystem first (real Deluge samples dir on disk)
          Path src;
          if (sp.startsWith("SAMPLES/") || sp.startsWith("SAMPLES\\")) {
            src = samplesRoot.getParent().resolve(sp);
          } else {
            src = samplesRoot.resolve(sp);
          }

          if (Files.exists(src)) {
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
          }

          // Rewrite the sample path to be relative to the song file
          sound.setSamplePath("SAMPLES/" + relPart.replace("\\", "/"));
        }
      }
    }
  }

  /**
   * Save a project model to a Deluge song XML file, using a pre-built {@code <tracks>}
   * DOM element for the note data. This overload is used by the ALS converter which
   * generates tick-precise note data directly (bypassing ClipModel's step-grid).
   *
   * @param model         the project model with instrument definitions
   * @param file          the output file (song.xml)
   * @param tracksElement a pre-built {@code <tracks>} DOM element containing noteRows
   */
  public static void save(ProjectModel model, File file, Element tracksElement) throws Exception {
    // Clone samples alongside the song XML before building the document
    cloneSamples(model, file);

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

        for (Drum drum : kit.getDrums()) {
          SoundDrum sound = (SoundDrum) drum;
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

        // DX7 patch hex (312 chars, 156 bytes) stored on osc1
        String dx7patch = synth.getDx7Patch();
        if (dx7patch != null && !dx7patch.isEmpty()) {
          osc1.setAttribute("dx7patch", dx7patch);
        }
        trackElem.appendChild(osc1);

        // Synth mode, algorithm, and FM params
        trackElem.setAttribute("synthMode", String.valueOf(synth.getSynthMode()));
        trackElem.setAttribute("synthAlgorithm", String.valueOf(synth.getSynthAlgorithm()));
        trackElem.setAttribute("engineType", String.valueOf(synth.getEngineType()));
        trackElem.setAttribute("fmRatio", String.valueOf(synth.getFmRatio()));
        trackElem.setAttribute("fmAmount", String.valueOf(synth.getFmAmount()));
        trackElem.setAttribute("modulator1Feedback",
            org.chuck.deluge.xml.DelugeHexMapper.floatToHex(synth.getModulator1Feedback()));
        trackElem.setAttribute("modulator2Amount",
            org.chuck.deluge.xml.DelugeHexMapper.floatToHex(synth.getModulator2Amount()));
        trackElem.setAttribute("modulator2Feedback",
            org.chuck.deluge.xml.DelugeHexMapper.floatToHex(synth.getModulator2Feedback()));
        trackElem.setAttribute("carrier1Feedback",
            org.chuck.deluge.xml.DelugeHexMapper.floatToHex(synth.getCarrier1Feedback()));
        trackElem.setAttribute("carrier2Feedback",
            org.chuck.deluge.xml.DelugeHexMapper.floatToHex(synth.getCarrier2Feedback()));

        instruments.appendChild(trackElem);
      }
    }

    // Use pre-built tracks element (adopt it into this document)
    Element importedTracks = (Element) doc.importNode(tracksElement, true);
    rootElement.appendChild(importedTracks);

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

  public static void save(ProjectModel model, File file) throws Exception {
    // Clone samples alongside the song XML before building the document
    cloneSamples(model, file);

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

        for (Drum drum : kit.getDrums()) {
          SoundDrum sound = (SoundDrum) drum;
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

        // DX7 patch hex (312 chars, 156 bytes) stored on osc1
        String dx7patch = synth.getDx7Patch();
        if (dx7patch != null && !dx7patch.isEmpty()) {
          osc1.setAttribute("dx7patch", dx7patch);
        }
        trackElem.appendChild(osc1);

        // Synth mode, algorithm, and FM params
        trackElem.setAttribute("synthMode", String.valueOf(synth.getSynthMode()));
        trackElem.setAttribute("synthAlgorithm", String.valueOf(synth.getSynthAlgorithm()));
        trackElem.setAttribute("engineType", String.valueOf(synth.getEngineType()));
        trackElem.setAttribute("fmRatio", String.valueOf(synth.getFmRatio()));
        trackElem.setAttribute("fmAmount", String.valueOf(synth.getFmAmount()));
        trackElem.setAttribute("modulator1Feedback",
            org.chuck.deluge.xml.DelugeHexMapper.floatToHex(synth.getModulator1Feedback()));
        trackElem.setAttribute("modulator2Amount",
            org.chuck.deluge.xml.DelugeHexMapper.floatToHex(synth.getModulator2Amount()));
        trackElem.setAttribute("modulator2Feedback",
            org.chuck.deluge.xml.DelugeHexMapper.floatToHex(synth.getModulator2Feedback()));
        trackElem.setAttribute("carrier1Feedback",
            org.chuck.deluge.xml.DelugeHexMapper.floatToHex(synth.getCarrier1Feedback()));
        trackElem.setAttribute("carrier2Feedback",
            org.chuck.deluge.xml.DelugeHexMapper.floatToHex(synth.getCarrier2Feedback()));

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
