package org.deluge.ableton;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.deluge.model.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Exporter that serializes a Deluge ProjectModel into a fully compliant, compressed Ableton Live
 * Set (.als) XML structure. Supports self-contained project bundling and offline stem layout.
 */
public class AbletonTrackExporter {

  /** Decoupled path rewriter and sample collector interface. */
  public interface PathRewriter {
    String rewriteAndCopy(String originalPath);
  }

  /**
   * Main entry point: serializes the project into XML, compresses it using Gzip, and writes the
   * output directly to the target .als file.
   */
  public static void exportProject(ProjectModel project, File targetFile) throws Exception {
    exportProject(project, targetFile, null);
  }

  /**
   * Overloaded entry point: serializes the project into XML, rewriting sample paths and copying
   * assets if a PathRewriter is provided.
   */
  public static void exportProject(ProjectModel project, File targetFile, PathRewriter pathRewriter)
      throws Exception {
    if (project == null || targetFile == null) return;

    // 1. Initialize DOM Document Builder
    DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
    Document doc = docBuilder.newDocument();

    // 2. Create Root Ableton Element (Live 12 Schema Parity)
    Element rootEl = doc.createElement("Ableton");
    rootEl.setAttribute("MajorVersion", "5");
    rootEl.setAttribute("MinorVersion", "12.0_12049");
    rootEl.setAttribute("SchemaVersion", "3");
    rootEl.setAttribute("Creator", "ChucK-Java Deluge Workstation");
    doc.appendChild(rootEl);

    // 3. Create LiveSet Element
    Element liveSet = doc.createElement("LiveSet");
    rootEl.appendChild(liveSet);

    // 4. Set Tempo
    Element masterTrack = doc.createElement("MasterTrack");
    Element deviceChain = doc.createElement("DeviceChain");
    Element mixer = doc.createElement("Mixer");
    Element tempo = doc.createElement("Tempo");
    Element manual = doc.createElement("Manual");
    manual.setAttribute("Value", String.valueOf(project.getBpm()));
    tempo.appendChild(manual);
    mixer.appendChild(tempo);
    deviceChain.appendChild(mixer);
    masterTrack.appendChild(deviceChain);
    liveSet.appendChild(masterTrack);

    // 5. Create Tracks Container
    Element tracksContainer = doc.createElement("Tracks");
    liveSet.appendChild(tracksContainer);

    // 6. Iterate and Export Tracks
    List<TrackModel> tracks = project.getTracks();
    for (int id = 0; id < tracks.size(); id++) {
      TrackModel track = tracks.get(id);
      if (track instanceof SynthTrackModel stm) {
        exportSynthTrack(doc, tracksContainer, stm, id);
      } else if (track instanceof KitTrackModel ktm) {
        exportKitTrack(doc, tracksContainer, ktm, id, pathRewriter);
      } else if (track instanceof AudioTrackModel atm) {
        exportAudioTrack(doc, tracksContainer, atm, id, pathRewriter);
      }
    }

    // 7. Write and Gzip-Compress XML to Disk
    try (GZIPOutputStream gos = new GZIPOutputStream(new FileOutputStream(targetFile))) {
      TransformerFactory transformerFactory = TransformerFactory.newInstance();
      Transformer transformer = transformerFactory.newTransformer();
      transformer.setOutputProperty(OutputKeys.INDENT, "yes");
      transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");

      DOMSource source = new DOMSource(doc);
      StreamResult result = new StreamResult(gos);
      transformer.transform(source, result);
    }
  }

  /**
   * Renders the project tracks as WAV stems, creates a self-contained Ableton Project, and
   * generates a Live Set (.als) that references these stems on the timeline.
   */
  public static void exportStemsProject(
      ProjectModel project,
      File targetAlsFile,
      org.deluge.project.ExportHelper.ProgressCallback callback)
      throws Exception {
    if (project == null || targetAlsFile == null) return;

    File projectDir = targetAlsFile.getParentFile();
    File importedDir = new File(projectDir, "Samples/Imported");
    if (!importedDir.exists()) {
      importedDir.mkdirs();
    }

    // 1. Render all WAV stems into Samples/Imported/
    if (callback != null) {
      callback.onProgress("Initializing Stem Renderer...", 0);
    }
    org.deluge.project.ExportHelper.exportStems(project, importedDir, 0, callback);

    // 2. Build the Ableton Live Set XML Document
    DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
    Document doc = docBuilder.newDocument();

    Element rootEl = doc.createElement("Ableton");
    rootEl.setAttribute("MajorVersion", "5");
    rootEl.setAttribute("MinorVersion", "12.0_12049");
    rootEl.setAttribute("SchemaVersion", "3");
    rootEl.setAttribute("Creator", "ChucK-Java Deluge Workstation");
    doc.appendChild(rootEl);

    Element liveSet = doc.createElement("LiveSet");
    rootEl.appendChild(liveSet);

    // Master Tempo
    Element masterTrack = doc.createElement("MasterTrack");
    Element deviceChain = doc.createElement("DeviceChain");
    Element mixer = doc.createElement("Mixer");
    Element tempo = doc.createElement("Tempo");
    Element manual = doc.createElement("Manual");
    manual.setAttribute("Value", String.valueOf(project.getBpm()));
    tempo.appendChild(manual);
    mixer.appendChild(tempo);
    deviceChain.appendChild(mixer);
    masterTrack.appendChild(deviceChain);
    liveSet.appendChild(masterTrack);

    // Tracks Container
    Element tracksContainer = doc.createElement("Tracks");
    liveSet.appendChild(tracksContainer);

    // Auto-detect song length in ticks
    int maxTicks = 0;
    for (ArrangerClip ac : project.getArrangerTimeline()) {
      maxTicks = Math.max(maxTicks, ac.startTicks() + ac.durationTicks());
    }
    double durationBeats = maxTicks > 0 ? (maxTicks / 96.0) : 16.0;

    // Create an Audio Track in Ableton for each project track
    List<TrackModel> tracks = project.getTracks();
    for (int t = 0; t < tracks.size(); t++) {
      TrackModel track = tracks.get(t);
      String trackName = track.getName();
      String cleanName = trackName.replaceAll("[^a-zA-Z0-9_-]", "_");
      String stemFileName = String.format("Track_%d_%s_stem.wav", t + 1, cleanName);

      Element audioTrack = doc.createElement("AudioTrack");
      audioTrack.setAttribute("Id", String.valueOf(t));
      tracksContainer.appendChild(audioTrack);

      setTrackName(doc, audioTrack, trackName);

      Element trackDeviceChain = doc.createElement("DeviceChain");
      audioTrack.appendChild(trackDeviceChain);

      Element mainSequencer = doc.createElement("MainSequencer");
      trackDeviceChain.appendChild(mainSequencer);

      Element clipSlotList = doc.createElement("ClipSlotList");
      mainSequencer.appendChild(clipSlotList);

      // Create a clip slot for the stem
      Element clipSlot = doc.createElement("ClipSlot");
      clipSlotList.appendChild(clipSlot);

      Element audioClip = doc.createElement("AudioClip");
      clipSlot.appendChild(audioClip);

      audioClip.appendChild(createValueElement(doc, "Name", trackName + " Stem"));
      audioClip.appendChild(createValueElement(doc, "CurrentEnd", String.valueOf(durationBeats)));

      Element sampleRef = doc.createElement("SampleRef");
      audioClip.appendChild(sampleRef);

      Element fileRef = doc.createElement("FileRef");
      sampleRef.appendChild(fileRef);
      // Point relatively to the collected stem file
      fileRef.appendChild(createValueElement(doc, "Path", "Samples/Imported/" + stemFileName));
    }

    // 3. Write and Gzip-Compress XML to Disk
    try (GZIPOutputStream gos = new GZIPOutputStream(new FileOutputStream(targetAlsFile))) {
      TransformerFactory transformerFactory = TransformerFactory.newInstance();
      Transformer transformer = transformerFactory.newTransformer();
      transformer.setOutputProperty(OutputKeys.INDENT, "yes");
      transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");

      DOMSource source = new DOMSource(doc);
      StreamResult result = new StreamResult(gos);
      transformer.transform(source, result);
    }
  }

  /** Exports a SynthTrackModel into a compliant Ableton MidiTrack. */
  private static void exportSynthTrack(
      Document doc, Element container, SynthTrackModel track, int trackId) {
    Element midiTrack = doc.createElement("MidiTrack");
    midiTrack.setAttribute("Id", String.valueOf(trackId));
    container.appendChild(midiTrack);

    // Set Track Name
    setTrackName(doc, midiTrack, track.getName());

    // Create DeviceChain & Sequencer
    Element deviceChain = doc.createElement("DeviceChain");
    midiTrack.appendChild(deviceChain);

    Element mainSequencer = doc.createElement("MainSequencer");
    deviceChain.appendChild(mainSequencer);

    Element clipSlotList = doc.createElement("ClipSlotList");
    mainSequencer.appendChild(clipSlotList);

    // Export Clips
    List<ClipModel> clips = track.getClips();
    for (int i = 0; i < clips.size(); i++) {
      ClipModel clip = clips.get(i);
      Element clipSlot = doc.createElement("ClipSlot");
      clipSlotList.appendChild(clipSlot);

      Element midiClip = doc.createElement("MidiClip");
      clipSlot.appendChild(midiClip);

      midiClip.appendChild(createValueElement(doc, "Name", clip.getName()));
      midiClip.appendChild(
          createValueElement(doc, "CurrentEnd", String.valueOf(clip.getStepCount() / 4.0)));

      Element keyTracks = doc.createElement("KeyTracks");
      midiClip.appendChild(keyTracks);

      // Serialize sequenced notes row by row (sparse mapping)
      for (int r = 0; r < clip.getRowCount(); r++) {
        int pitch = clip.getRowYNote(r);
        if (pitch <= 0) pitch = 60; // Default to Middle C

        Element keyTrack = doc.createElement("KeyTrack");
        keyTracks.appendChild(keyTrack);
        keyTrack.appendChild(createValueElement(doc, "MidiKey", String.valueOf(pitch)));

        Element notes = doc.createElement("Notes");
        keyTrack.appendChild(notes);

        serializeNotesForPitch(doc, notes, clip, r);
      }
    }
  }

  /** Exports a KitTrackModel (Drum Rack) into a compliant Ableton MidiTrack. */
  private static void exportKitTrack(
      Document doc,
      Element container,
      KitTrackModel track,
      int trackId,
      PathRewriter pathRewriter) {
    Element midiTrack = doc.createElement("MidiTrack");
    midiTrack.setAttribute("Id", String.valueOf(trackId));
    container.appendChild(midiTrack);

    // Set Track Name
    setTrackName(doc, midiTrack, track.getName());

    // Create DeviceChain & DeviceList
    Element deviceChain = doc.createElement("DeviceChain");
    midiTrack.appendChild(deviceChain);

    Element deviceList = doc.createElement("DeviceList");
    deviceChain.appendChild(deviceList);

    // Embed Drum Rack (DrumGroupDevice)
    Element drumRack = doc.createElement("DrumGroupDevice");
    deviceList.appendChild(drumRack);

    Element branches = doc.createElement("Branches");
    drumRack.appendChild(branches);

    // Map pads and assign MIDI notes starting at C3 (36)
    List<Drum> drums = track.getDrums();
    for (int d = 0; d < drums.size(); d++) {
      Drum drum = drums.get(d);
      int midiNote = 36 + d;

      Element branch = doc.createElement("DrumBranch");
      branches.appendChild(branch);
      branch.appendChild(createValueElement(doc, "Name", drum.getName()));

      Element branchInfo = doc.createElement("BranchInfo");
      branch.appendChild(branchInfo);
      branchInfo.appendChild(createValueElement(doc, "ReceivingNote", String.valueOf(midiNote)));

      // Add Simpler device with sample reference if sample path exists
      if (drum instanceof SoundDrum sd
          && sd.getSamplePath() != null
          && !sd.getSamplePath().isEmpty()) {
        Element devices = doc.createElement("Devices");
        branch.appendChild(devices);

        Element simpler = doc.createElement("OriginalSimpler");
        devices.appendChild(simpler);

        Element sampleRef = doc.createElement("SampleRef");
        simpler.appendChild(sampleRef);

        Element fileRef = doc.createElement("FileRef");
        sampleRef.appendChild(fileRef);

        String path = sd.getSamplePath();
        if (pathRewriter != null) {
          path = pathRewriter.rewriteAndCopy(path);
        }
        fileRef.appendChild(createValueElement(doc, "Path", path));
      }
    }

    // Create MainSequencer
    Element mainSequencer = doc.createElement("MainSequencer");
    deviceChain.appendChild(mainSequencer);

    Element clipSlotList = doc.createElement("ClipSlotList");
    mainSequencer.appendChild(clipSlotList);

    // Export Clips
    List<ClipModel> clips = track.getClips();
    for (int i = 0; i < clips.size(); i++) {
      ClipModel clip = clips.get(i);
      Element clipSlot = doc.createElement("ClipSlot");
      clipSlotList.appendChild(clipSlot);

      Element midiClip = doc.createElement("MidiClip");
      clipSlot.appendChild(midiClip);

      midiClip.appendChild(createValueElement(doc, "Name", clip.getName()));
      midiClip.appendChild(
          createValueElement(doc, "CurrentEnd", String.valueOf(clip.getStepCount() / 4.0)));

      Element keyTracks = doc.createElement("KeyTracks");
      midiClip.appendChild(keyTracks);

      // Serialize sequenced drum triggers
      for (int d = 0; d < drums.size(); d++) {
        int midiNote = 36 + d;
        Element keyTrack = doc.createElement("KeyTrack");
        keyTracks.appendChild(keyTrack);
        keyTrack.appendChild(createValueElement(doc, "MidiKey", String.valueOf(midiNote)));

        Element notes = doc.createElement("Notes");
        keyTrack.appendChild(notes);

        serializeNotesForPitch(doc, notes, clip, d);
      }
    }
  }

  /** Exports an AudioTrackModel into a compliant Ableton AudioTrack. */
  private static void exportAudioTrack(
      Document doc,
      Element container,
      AudioTrackModel track,
      int trackId,
      PathRewriter pathRewriter) {
    Element audioTrack = doc.createElement("AudioTrack");
    audioTrack.setAttribute("Id", String.valueOf(trackId));
    container.appendChild(audioTrack);

    setTrackName(doc, audioTrack, track.getName());

    Element deviceChain = doc.createElement("DeviceChain");
    audioTrack.appendChild(deviceChain);

    Element mainSequencer = doc.createElement("MainSequencer");
    deviceChain.appendChild(mainSequencer);

    Element clipSlotList = doc.createElement("ClipSlotList");
    mainSequencer.appendChild(clipSlotList);

    // Export Audio clips
    List<AudioTrackModel.AudioClip> clips = track.getAudioClips();
    for (int i = 0; i < clips.size(); i++) {
      AudioTrackModel.AudioClip clip = clips.get(i);
      Element clipSlot = doc.createElement("ClipSlot");
      clipSlotList.appendChild(clipSlot);

      Element audioClip = doc.createElement("AudioClip");
      clipSlot.appendChild(audioClip);

      audioClip.appendChild(createValueElement(doc, "Name", track.getName() + " Loop"));
      audioClip.appendChild(
          createValueElement(doc, "CurrentEnd", String.valueOf(clip.getLength() / 96.0)));

      Element sampleRef = doc.createElement("SampleRef");
      audioClip.appendChild(sampleRef);

      Element fileRef = doc.createElement("FileRef");
      sampleRef.appendChild(fileRef);

      String path = clip.getFilePath();
      if (pathRewriter != null) {
        path = pathRewriter.rewriteAndCopy(path);
      }
      fileRef.appendChild(createValueElement(doc, "Path", path));
    }
  }

  // ── Serialization Helpers ──

  /** Serializes notes for a specific pitch row in a clip (supports high-res and grid paths). */
  private static void serializeNotesForPitch(
      Document doc, Element notesContainer, ClipModel clip, int rowIndex) {
    List<HighResNote> hrNotes = clip.getRawNoteEvents(rowIndex);
    if (hrNotes != null && !hrNotes.isEmpty()) {
      // ── High-Resolution Path (96 PPQN ticks) ──
      for (HighResNote n : hrNotes) {
        Element noteEv = doc.createElement("MidiNoteEvent");
        noteEv.setAttribute("Time", String.valueOf(n.getTickPos() / 96.0));
        noteEv.setAttribute("Duration", String.valueOf(n.getTickLen() / 96.0));
        noteEv.setAttribute("Velocity", String.valueOf((int) (n.getVelocity() * 127)));
        noteEv.setAttribute("Probability", String.valueOf(n.getProbability()));
        notesContainer.appendChild(noteEv);
      }
    } else {
      // ── Quantized Grid Path Fallback (16th note steps) ──
      for (int c = 0; c < clip.getStepCount(); c++) {
        StepData step = clip.getStep(rowIndex, c);
        if (step != null && step.active()) {
          Element noteEv = doc.createElement("MidiNoteEvent");
          noteEv.setAttribute("Time", String.valueOf(c / 4.0));
          noteEv.setAttribute("Duration", String.valueOf(step.gate() / 96.0));
          noteEv.setAttribute("Velocity", String.valueOf((int) (step.velocity() * 127)));
          noteEv.setAttribute("Probability", String.valueOf(step.probability()));
          notesContainer.appendChild(noteEv);
        }
      }
    }
  }

  private static void setTrackName(Document doc, Element trackEl, String name) {
    Element nameEl = doc.createElement("Name");
    trackEl.appendChild(nameEl);
    nameEl.appendChild(createValueElement(doc, "UserName", name));
    nameEl.appendChild(createValueElement(doc, "EffectiveName", name));
  }

  private static Element createValueElement(Document doc, String tagName, String value) {
    Element el = doc.createElement(tagName);
    el.setAttribute("Value", value);
    return el;
  }
}
