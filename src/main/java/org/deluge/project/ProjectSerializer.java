package org.deluge.project;

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
import org.deluge.model.Drum;
import org.deluge.model.KitTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SoundDrum;
import org.deluge.model.SynthTrackModel;
import org.deluge.model.TrackModel;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Serializes the in-memory ProjectModel to a Deluge-compatible Song XML format. */
public class ProjectSerializer {

  /**
   * Resolves a sample path against the samples directory and copies it into a subdirectory next to
   * the song XML, preserving the relative path structure after "SAMPLES/". Rewrites the
   * PathKitSound's samplePath to the new relative location.
   *
   * <p>Samples are loaded from the filesystem only, using PreferencesManager paths.
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
   * Save a project model to a Deluge song XML file, using a pre-built {@code <tracks>} DOM element
   * for the note data. This overload is used by the ALS converter which generates tick-precise note
   * data directly (bypassing ClipModel's step-grid).
   *
   * @param model the project model with instrument definitions
   * @param file the output file (song.xml)
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
    rootElement.setAttribute("swing", org.deluge.xml.DelugeHexMapper.floatToHex(model.getSwing()));

    serializeMicrotuning(doc, rootElement, model);

    // Tracks (Clips)
    Element instruments = doc.createElement("instruments");
    rootElement.appendChild(instruments);

    for (TrackModel track : model.getTracks()) {
      if (track instanceof KitTrackModel kit) {
        Element trackElem = org.deluge.project.KitSynthSerializer.serializeKit(doc, kit, true);
        instruments.appendChild(trackElem);
      } else if (track instanceof SynthTrackModel synth) {
        Element trackElem = org.deluge.project.KitSynthSerializer.serializeSynth(doc, synth, true);
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
    rootElement.setAttribute("swing", org.deluge.xml.DelugeHexMapper.floatToHex(model.getSwing()));

    serializeMicrotuning(doc, rootElement, model);

    // Tracks (Clips)
    Element instruments = doc.createElement("instruments");
    rootElement.appendChild(instruments);

    for (TrackModel track : model.getTracks()) {
      if (track instanceof KitTrackModel kit) {
        Element trackElem = org.deluge.project.KitSynthSerializer.serializeKit(doc, kit, true);
        instruments.appendChild(trackElem);
      } else if (track instanceof SynthTrackModel synth) {
        Element trackElem = org.deluge.project.KitSynthSerializer.serializeSynth(doc, synth, true);
        instruments.appendChild(trackElem);
      }
    }

    // Build global list of all session clips to resolve indices in arranger placements
    java.util.List<org.deluge.model.ClipModel> allSessionClips = new java.util.ArrayList<>();
    for (TrackModel track : model.getTracks()) {
      for (org.deluge.model.ClipModel clip : track.getClips()) {
        allSessionClips.add(clip);
      }
    }

    // Serialize Tracks (Clips)
    Element tracksElem = doc.createElement("tracks");
    rootElement.appendChild(tracksElem);

    int trackIndex = 0;
    for (TrackModel track : model.getTracks()) {
      for (org.deluge.model.ClipModel clip : track.getClips()) {
        Element clipTrackElem = doc.createElement("track");
        int stepTicks = clip.isTripletMode() ? 32 : 24;
        clipTrackElem.setAttribute("length", String.valueOf(clip.getStepCount() * stepTicks));
        if (clip.isTripletMode()) {
          clipTrackElem.setAttribute("triplet", "1");
        }
        if (clip.getPlayDirection() != org.deluge.model.ClipModel.PlayDirection.FORWARD) {
          clipTrackElem.setAttribute("sequenceDirection", clip.getPlayDirection().name());
        }
        tracksElem.appendChild(clipTrackElem);

        // Serialize arrangement placements (clipInstances) for this clip lane
        StringBuilder ciBuilder = new StringBuilder("0x");
        for (org.deluge.model.ArrangerClip ac : model.getArrangerTimeline()) {
          if (ac.trackIndex() == trackIndex) {
            int clipIdx = allSessionClips.indexOf(ac.clip());
            if (clipIdx >= 0) {
              ciBuilder.append(
                  String.format("%08X%08X%08X", ac.startTicks(), ac.durationTicks(), clipIdx));
            }
          }
        }
        if (ciBuilder.length() > 2) {
          clipTrackElem.setAttribute("clipInstances", ciBuilder.toString());
        }

        trackIndex++;

        // Collect non-empty noteRows first, then write <noteRows> only if there are any.
        // Empty <noteRows/> causes parser NPE: rowCount falls back to 8 but list has 0 items.
        java.util.List<Element> noteRowElements = new java.util.ArrayList<>();
        for (int r = 0; r < clip.getRowCount(); r++) {
          int yNote = clip.getRowYNote(r);
          if (!(track instanceof KitTrackModel) && yNote < 0) {
            for (int s = 0; s < clip.getStepCount(); s++) {
              org.deluge.model.StepData sd = clip.getStepRaw(r, s);
              if (sd.active() && sd.pitch() > 0) {
                yNote = sd.pitch();
                break;
              }
            }
          }
          // For synth tracks: skip empty rows (kit tracks must keep all rows = drum pads)
          if (!(track instanceof KitTrackModel) && yNote < 0) {
            boolean hasActive = false;
            for (int s = 0; s < clip.getStepCount(); s++) {
              if (clip.getStepRaw(r, s).active()) {
                hasActive = true;
                break;
              }
            }
            if (!hasActive) continue;
          }
          Element noteRowElem = doc.createElement("noteRow");
          noteRowElem.setAttribute("y", String.valueOf(Math.max(yNote, 0)));
          noteRowElements.add(noteRowElem);

          java.util.List<org.deluge.model.StepData> row = new java.util.ArrayList<>();
          for (int s = 0; s < clip.getStepCount(); s++) {
            row.add(clip.getStepRaw(r, s));
          }

          String hexData = org.deluge.xml.DelugeNoteDataMapper.encodeRow(row, stepTicks);
          noteRowElem.setAttribute("noteDataWithLift", hexData);
        }
        // Only write <noteRows> if there are rows to write (empty element causes parser NPE)
        if (!noteRowElements.isEmpty()) {
          Element noteRowsElem = doc.createElement("noteRows");
          clipTrackElem.appendChild(noteRowsElem);
          for (Element e : noteRowElements) {
            noteRowsElem.appendChild(e);
          }
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

  private static void serializeMicrotuning(Document doc, Element rootElement, ProjectModel model) {
    boolean hasNonZeroCents = false;
    int[] centAdjust = model.getCentAdjustForNotesInTemperament();
    for (int i = 0; i < model.getOctaveNumMicrotonalNotes(); i++) {
      if (centAdjust[i] != 0) {
        hasNonZeroCents = true;
        break;
      }
    }

    if (model.getOctaveNumMicrotonalNotes() != 12
        || !model.isEqualTemperament()
        || model.getBaseFrequencyHz() != 440.0
        || hasNonZeroCents) {
      Element microtuningEl = doc.createElement("microtuning");
      microtuningEl.setAttribute("notes", String.valueOf(model.getOctaveNumMicrotonalNotes()));
      microtuningEl.setAttribute("isEqual", String.valueOf(model.isEqualTemperament()));
      microtuningEl.setAttribute("baseFrequency", String.valueOf(model.getBaseFrequencyHz()));

      if (model.isEqualTemperament()) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < model.getOctaveNumMicrotonalNotes(); i++) {
          if (i > 0) sb.append(",");
          sb.append(centAdjust[i]);
        }
        Element centsEl = doc.createElement("cents");
        centsEl.setTextContent(sb.toString());
        microtuningEl.appendChild(centsEl);
      } else {
        double[] customRatios = model.getCustomRatios();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < model.getOctaveNumMicrotonalNotes(); i++) {
          if (i > 0) sb.append(",");
          sb.append(customRatios[i]);
        }
        Element ratiosEl = doc.createElement("ratios");
        ratiosEl.setTextContent(sb.toString());
        microtuningEl.appendChild(ratiosEl);
      }
      rootElement.appendChild(microtuningEl);
    }
  }
}
