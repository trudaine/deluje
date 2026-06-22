package org.deluge.ableton;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.deluge.model.ArrangerClip;
import org.deluge.model.AudioTrackModel;
import org.deluge.model.ClipModel;
import org.deluge.model.EnvelopeModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.NoteModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SoundDrum;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Translator that converts Ableton Live Set XML elements (<MidiTrack>, <AudioTrack>,
 * <DrumGroupDevice>) into corresponding high-fidelity Deluge track, drum slot, and clip models.
 */
public class AbletonTrackMapper {

  /**
   * Main entry point: parses the entire Ableton DOM Document, extracts the tempo, iterates through
   * all tracks, and populates the Deluge ProjectModel.
   */
  public static void importAbletonSet(Document doc, ProjectModel project) {
    importAbletonSet(doc, project, null);
  }

  public static void importAbletonSet(Document doc, ProjectModel project, File alsFile) {
    if (doc == null || project == null) return;

    File samplesDir = null;
    if (alsFile != null) {
      File parent = alsFile.getParentFile();
      if (parent != null) {
        samplesDir = new File(parent, "Samples/Imported");
        if (!samplesDir.exists() || !samplesDir.isDirectory()) {
          samplesDir = new File(parent, "Project/Samples/Imported");
        }
      }
    }

    // 1. Import Tempo
    try {
      NodeList tempoNodes = doc.getElementsByTagName("Tempo");
      if (tempoNodes.getLength() > 0) {
        Element tempoEl = (Element) tempoNodes.item(0);
        NodeList manualList = tempoEl.getElementsByTagName("Manual");
        if (manualList.getLength() > 0) {
          Element manualEl = (Element) manualList.item(0);
          float bpm = Float.parseFloat(manualEl.getAttribute("Value"));
          project.setBpm(bpm);
        }
      }
    } catch (Exception e) {
      System.err.println("[AbletonTrackMapper] Failed to parse tempo: " + e.getMessage());
    }

    // 2. Import Tracks
    NodeList trackContainerList = doc.getElementsByTagName("Tracks");
    if (trackContainerList.getLength() == 0) return;
    Element tracksContainer = (Element) trackContainerList.item(0);

    NodeList children = tracksContainer.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child instanceof Element trackEl) {
        String tagName = trackEl.getTagName();
        try {
          if ("MidiTrack".equals(tagName)) {
            importMidiTrack(trackEl, project, samplesDir);
          } else if ("AudioTrack".equals(tagName)) {
            importAudioTrack(trackEl, project, samplesDir);
          }
        } catch (Exception e) {
          System.err.println(
              "[AbletonTrackMapper] Failed to import track '"
                  + getTrackName(trackEl, "?")
                  + "': "
                  + e.getMessage());
          // Continue importing remaining tracks
        }
      }
    }
  }

  /** Imports an Ableton MIDI track, checking if it is a Drum Rack (Kit) or Synth track. */
  private static void importMidiTrack(Element trackEl, ProjectModel project, File samplesDir) {
    String trackName = getTrackName(trackEl, "MIDI Track");

    // Check if track contains a Drum Group Device (Drum Rack)
    NodeList drumGroupDevices = trackEl.getElementsByTagName("DrumGroupDevice");
    if (drumGroupDevices.getLength() > 0) {
      // ── DRUM RACK TRACK → KIT TRACK ──
      Element drumRack = (Element) drumGroupDevices.item(0);
      importDrumRackTrack(trackEl, drumRack, trackName, project, samplesDir);
    } else {
      // ── REGULAR MIDI TRACK → SYNTH TRACK ──
      importSynthTrack(trackEl, trackName, project, samplesDir);
    }
  }

  private static float parseTrackVolume(Element trackEl) {
    try {
      NodeList mixerList = trackEl.getElementsByTagName("Mixer");
      if (mixerList.getLength() > 0) {
        Element mixerEl = (Element) mixerList.item(0);
        NodeList volumeList = mixerEl.getElementsByTagName("Volume");
        if (volumeList.getLength() > 0) {
          Element volumeEl = (Element) volumeList.item(0);
          NodeList manualList = volumeEl.getElementsByTagName("Manual");
          if (manualList.getLength() > 0) {
            Element manualEl = (Element) manualList.item(0);
            if (manualEl.hasAttribute("Value")) {
              return Float.parseFloat(manualEl.getAttribute("Value"));
            }
          }
        }
      }
    } catch (Exception e) {
      // Fallback
    }
    return 1.0f; // Default to unity gain (1.0)
  }

  /** Imports an Ableton MIDI track as a SynthTrackModel. */
  private static void importSynthTrack(
      Element trackEl, String name, ProjectModel project, File samplesDir) {
    SynthTrackModel synthTrack = new SynthTrackModel(name);
    project.addTrack(synthTrack); // Add first so it has a valid index

    float rawVol = parseTrackVolume(trackEl);

    // 1. Try to parse native Simpler/Sampler device parameters first! (Fully Generic!)
    boolean parsedNative = parseNativeSimpler(trackEl, synthTrack);

    // 2. Fallback to name-based semantic presets if no native device was found!
    if (!parsedNative) {
      applySemanticPreset(synthTrack, name, samplesDir, rawVol);
    }

    // Parse clips
    NodeList clipElements = trackEl.getElementsByTagName("MidiClip");
    for (int i = 0; i < clipElements.getLength(); i++) {
      Element clipEl = (Element) clipElements.item(i);
      ClipModel clip = parseSynthClip(clipEl, name);
      if (clip != null) {
        synthTrack.addClip(clip);

        // Parse arranger timeline placement
        try {
          double startBeats = Double.parseDouble(getElementValue(clipEl, "CurrentStart", "-1.0"));
          double endBeats = Double.parseDouble(getElementValue(clipEl, "CurrentEnd", "-1.0"));
          if (startBeats >= 0.0 && endBeats > startBeats) {
            int startTicks = (int) Math.round(startBeats * 96.0);
            int durationTicks = (int) Math.round((endBeats - startBeats) * 96.0);
            int trackIndex = project.getTracks().indexOf(synthTrack);
            project.addArrangerClip(new ArrangerClip(trackIndex, clip, startTicks, durationTicks));
          }
        } catch (Exception ex) {
          // ignore timeline parsing errors
        }
      }
    }

    // Fallback: if no clips were parsed, create a default empty clip
    if (synthTrack.getClips().isEmpty()) {
      synthTrack.addClip(new ClipModel("Clip 1", 8, 16));
    }
  }

  private static int parseStepCount(Element clipEl) {
    double clipLengthBeats = 16.0;
    try {
      double currentStart = Double.parseDouble(getElementValue(clipEl, "CurrentStart", "0.0"));
      double currentEnd = Double.parseDouble(getElementValue(clipEl, "CurrentEnd", "16.0"));
      clipLengthBeats = Math.max(0.25, currentEnd - currentStart);

      // Check if clip has loop enabled in Ableton
      NodeList loopNodes = clipEl.getElementsByTagName("Loop");
      if (loopNodes.getLength() > 0) {
        Element loopEl = (Element) loopNodes.item(0);
        String loopOnVal = getElementValue(loopEl, "LoopOn", "false");
        if ("true".equalsIgnoreCase(loopOnVal)) {
          double loopStart = Double.parseDouble(getElementValue(loopEl, "LoopStart", "0.0"));
          double loopEnd = Double.parseDouble(getElementValue(loopEl, "LoopEnd", "4.0"));
          if (loopEnd > loopStart) {
            clipLengthBeats = loopEnd - loopStart;
          }
        }
      }
    } catch (Exception e) {
      // Fallback
    }
    int stepCount = (int) Math.round(clipLengthBeats * 4.0);
    return stepCount <= 0 ? 16 : stepCount;
  }

  /** Parses a MidiClip element into a sparse-pitch piano roll ClipModel. */
  private static ClipModel parseSynthClip(Element clipEl, String trackName) {
    try {
      String clipName = getElementValue(clipEl, "Name", "Clip");
      int stepCount = parseStepCount(clipEl);

      // 1. Gather all note events from the Ableton XML
      List<ParsedNoteEvent> events = new ArrayList<>();
      NodeList keyTrackNodes = clipEl.getElementsByTagName("KeyTrack");
      for (int k = 0; k < keyTrackNodes.getLength(); k++) {
        Element keyTrack = (Element) keyTrackNodes.item(k);
        int pitch =
            (int) Math.round(Double.parseDouble(getElementValue(keyTrack, "MidiKey", "-1")));
        if (pitch == -1) continue;

        String trackNameLower = trackName != null ? trackName.toLowerCase() : "";
        if (trackNameLower.contains("glock")
            || trackNameLower.contains("bell")
            || trackNameLower.contains("calimba")) {
          pitch += 36; // Transpose up 3 octaves to correct sparkling register
        }
        if (trackNameLower.contains("bass")) {
          pitch += 12; // Transpose up 1 octave from subsonic to gorgeous warm bass
        }

        NodeList noteEvents = keyTrack.getElementsByTagName("MidiNoteEvent");
        for (int n = 0; n < noteEvents.getLength(); n++) {
          Element noteEv = (Element) noteEvents.item(n);
          double time = Double.parseDouble(noteEv.getAttribute("Time"));
          double duration = Double.parseDouble(noteEv.getAttribute("Duration"));
          int velocity = (int) Math.round(Double.parseDouble(noteEv.getAttribute("Velocity")));
          events.add(new ParsedNoteEvent(pitch, time, duration, velocity));
        }
      }

      if (events.isEmpty()) {
        // Return a default empty clip if no notes exist
        return new ClipModel(clipName, 8, stepCount);
      }

      // 2. Identify and sort unique pitches to build a sparse-pitch piano roll
      java.util.Set<Integer> pitchSet = new java.util.HashSet<>();
      for (ParsedNoteEvent ev : events) {
        pitchSet.add(ev.pitch);
      }
      List<Integer> uniquePitches = new ArrayList<>(pitchSet);
      // Sort in descending order: highest pitch at row 0 (standard piano roll view)
      Collections.sort(uniquePitches, Collections.reverseOrder());

      int rowCount = uniquePitches.size();
      ClipModel clip = new ClipModel(clipName, rowCount, stepCount);

      // Map rows to absolute pitches
      Map<Integer, Integer> pitchToRow = new HashMap<>();
      for (int r = 0; r < rowCount; r++) {
        int pitch = uniquePitches.get(r);
        clip.setRowYNote(r, pitch);
        pitchToRow.put(pitch, r);
        // Initialize raw note events list for this row
        clip.setRawNoteEvents(r, new ArrayList<>());
      }

      // 3. Write note events to the high-res and quantized grids
      for (ParsedNoteEvent ev : events) {
        Integer rowObj = pitchToRow.get(ev.pitch);
        if (rowObj == null) continue; // Pitch not in unique set (data integrity guard)
        int r = rowObj;

        // A. High-Res Path (96 PPQN ticks)
        int tickPos = (int) Math.round(ev.time * 96);
        int tickLen = (int) Math.round(ev.duration * 96);
        float normVel = ev.velocity / 127.0f;
        NoteModel hrn = new NoteModel(tickPos, tickLen, normVel, 1.0f, 0);
        clip.getRawNoteEvents(r).add(hrn);

        // B. Quantized Grid Path (16th note steps)
        int stepIdx = (int) Math.round(ev.time * 4);
        if (stepIdx >= 0 && stepIdx < stepCount) {
          StepData existingStep = clip.getStep(r, stepIdx);
          if (existingStep == null || !existingStep.active() || normVel > existingStep.velocity()) {
            clip.setStep(
                r, stepIdx, StepData.of(true, normVel, StepData.DEFAULT_CLICK_GATE, 1.0f, 60));
          }
        }
      }

      return clip;
    } catch (Exception e) {
      System.err.println("[AbletonTrackMapper] Failed to parse MIDI clip: " + e.getMessage());
      return null;
    }
  }

  /** Imports an Ableton MIDI track containing a Drum Group Device as a KitTrackModel. */
  private static void importDrumRackTrack(
      Element trackEl, Element drumRack, String name, ProjectModel project, File samplesDir) {
    KitTrackModel kitTrack = new KitTrackModel(name);
    project.addTrack(kitTrack); // Add first so it has a valid index

    float vol = parseTrackVolume(trackEl);
    kitTrack.setVolume(vol);

    // 1. Map Drum Pads (Branches) to Kit Channels
    NodeList branches = drumRack.getElementsByTagName("DrumBranch");
    Map<Integer, Integer> midiPitchToDrumIndex = new HashMap<>();

    for (int i = 0; i < branches.getLength(); i++) {
      Element branch = (Element) branches.item(i);
      String padName = getElementValue(branch, "Name", "Drum Pad");
      Element branchInfo = getDirectChild(branch, "BranchInfo");
      int receivingNote = -1;
      if (branchInfo != null) {
        receivingNote =
            (int)
                Math.round(Double.parseDouble(getElementValue(branchInfo, "ReceivingNote", "-1")));
      }

      if (receivingNote == -1) continue;

      // Extract and resolve audio sample path
      String samplePath = "";
      NodeList sampleRefs = branch.getElementsByTagName("SampleRef");
      if (sampleRefs.getLength() > 0) {
        Element sampleRef = (Element) sampleRefs.item(0);
        NodeList fileRefs = sampleRef.getElementsByTagName("FileRef");
        if (fileRefs.getLength() > 0) {
          Element fileRef = (Element) fileRefs.item(0);
          String relPath = getElementValue(fileRef, "RelativePath", "");
          String absPath = getElementValue(fileRef, "Path", "");
          String packName = getElementValue(fileRef, "LivePackName", "");

          try {
            File resolved = AbletonAssetResolver.resolveSamplePath(packName, relPath, absPath);
            if (resolved != null && resolved.exists()) {
              samplePath = resolved.getAbsolutePath();
            }
          } catch (Exception e) {
            System.err.println(
                "[AbletonTrackMapper] Drum pad sample resolution failed: " + e.getMessage());
          }
        }
      }

      SoundDrum drum = new SoundDrum(padName, samplePath);
      drum.setPitchSemitones(0.0f);
      kitTrack.addDrum(drum);

      // Track the mapping from MIDI pitch to the drum index (row index in kit)
      int drumIndex = kitTrack.getDrums().size() - 1;
      midiPitchToDrumIndex.put(receivingNote, drumIndex);
    }

    // 2. Parse MIDI sequencing clips for the Drum Kit
    int drumCount = kitTrack.getDrums().size();
    if (drumCount == 0) {
      // Create a default empty kick channel if no pads were resolved
      kitTrack.addDrum(new SoundDrum("Kick"));
      drumCount = 1;
    }

    NodeList clipElements = trackEl.getElementsByTagName("MidiClip");
    for (int i = 0; i < clipElements.getLength(); i++) {
      Element clipEl = (Element) clipElements.item(i);
      ClipModel clip = parseDrumClip(clipEl, drumCount, midiPitchToDrumIndex);
      if (clip != null) {
        kitTrack.addClip(clip);

        // Parse arranger timeline placement
        try {
          double startBeats = Double.parseDouble(getElementValue(clipEl, "CurrentStart", "-1.0"));
          double endBeats = Double.parseDouble(getElementValue(clipEl, "CurrentEnd", "-1.0"));
          if (startBeats >= 0.0 && endBeats > startBeats) {
            int startTicks = (int) Math.round(startBeats * 96.0);
            int durationTicks = (int) Math.round((endBeats - startBeats) * 96.0);
            int trackIndex = project.getTracks().indexOf(kitTrack);
            project.addArrangerClip(new ArrangerClip(trackIndex, clip, startTicks, durationTicks));
          }
        } catch (Exception ex) {
          // ignore timeline parsing errors
        }
      }
    }

    // Fallback: if no clips were parsed, create a default empty clip
    if (kitTrack.getClips().isEmpty()) {
      kitTrack.addClip(new ClipModel("Clip 1", drumCount, 16));
    }
  }

  /** Parses a MidiClip element for a drum kit track. */
  private static ClipModel parseDrumClip(
      Element clipEl, int drumCount, Map<Integer, Integer> midiPitchToDrumIndex) {
    try {
      String clipName = getElementValue(clipEl, "Name", "Clip");
      int stepCount = parseStepCount(clipEl);

      ClipModel clip = new ClipModel(clipName, drumCount, stepCount);

      // Initialize raw note lists
      for (int d = 0; d < drumCount; d++) {
        clip.setRawNoteEvents(d, new ArrayList<>());
      }

      NodeList keyTrackNodes = clipEl.getElementsByTagName("KeyTrack");
      for (int k = 0; k < keyTrackNodes.getLength(); k++) {
        Element keyTrack = (Element) keyTrackNodes.item(k);
        int pitch =
            (int) Math.round(Double.parseDouble(getElementValue(keyTrack, "MidiKey", "-1")));

        // Match pitch to drum slot index
        Integer r = midiPitchToDrumIndex.get(pitch);
        if (r == null || r < 0 || r >= drumCount) continue;

        NodeList noteEvents = keyTrack.getElementsByTagName("MidiNoteEvent");
        for (int n = 0; n < noteEvents.getLength(); n++) {
          Element noteEv = (Element) noteEvents.item(n);
          double time = Double.parseDouble(noteEv.getAttribute("Time"));
          double duration = Double.parseDouble(noteEv.getAttribute("Duration"));
          int velocity = (int) Math.round(Double.parseDouble(noteEv.getAttribute("Velocity")));

          // A. High-Res Path
          int tickPos = (int) Math.round(time * 96);
          int tickLen = (int) Math.round(duration * 96);
          float normVel = velocity / 127.0f;
          NoteModel hrn = new NoteModel(tickPos, tickLen, normVel, 1.0f, 0);
          clip.getRawNoteEvents(r).add(hrn);

          // B. Quantized Grid Path
          int stepIdx = (int) Math.round(time * 4);
          if (stepIdx >= 0 && stepIdx < stepCount) {
            clip.setStep(
                r, stepIdx, StepData.of(true, normVel, StepData.DEFAULT_CLICK_GATE, 1.0f, 60));
          }
        }
      }

      return clip;
    } catch (Exception e) {
      System.err.println("[AbletonTrackMapper] Failed to parse drum clip: " + e.getMessage());
      return null;
    }
  }

  /** Imports an Ableton Audio track into the ProjectModel. */
  private static void importAudioTrack(Element trackEl, ProjectModel project, File samplesDir) {
    String trackName = getTrackName(trackEl, "Audio Track");
    AudioTrackModel audioTrack = new AudioTrackModel(trackName);
    project.addTrack(audioTrack); // Add first so it has a valid index

    float vol = parseTrackVolume(trackEl);
    audioTrack.setVolume(vol);

    // Parse audio clips
    NodeList audioClips = trackEl.getElementsByTagName("AudioClip");
    for (int i = 0; i < audioClips.getLength(); i++) {
      Element clipEl = (Element) audioClips.item(i);
      AudioTrackModel.AudioClip delugeClip = parseAudioClip(clipEl, trackName, samplesDir);
      if (delugeClip != null) {
        audioTrack.addAudioClip(delugeClip);

        // Parse arranger timeline placement
        try {
          double startBeats = Double.parseDouble(getElementValue(clipEl, "CurrentStart", "-1.0"));
          double endBeats = Double.parseDouble(getElementValue(clipEl, "CurrentEnd", "-1.0"));
          if (startBeats >= 0.0 && endBeats > startBeats) {
            int startTicks = (int) Math.round(startBeats * 96.0);
            int durationTicks = (int) Math.round((endBeats - startBeats) * 96.0);
            int trackIndex = project.getTracks().indexOf(audioTrack);
            project
                .getArrangerTimeline()
                .add(new ArrangerClip(trackIndex, null, delugeClip, startTicks, durationTicks));
          }
        } catch (Exception e) {
          // Ignore parsing errors for malformed placements
        }
      }
    }
  }

  /** Parses an Ableton AudioClip element, resolving its sample path. */
  private static AudioTrackModel.AudioClip parseAudioClip(
      Element clipEl, String trackName, File samplesDir) {
    try {
      String samplePath = "";
      NodeList sampleRefs = clipEl.getElementsByTagName("SampleRef");
      if (sampleRefs.getLength() > 0) {
        Element sampleRef = (Element) sampleRefs.item(0);
        NodeList fileRefs = sampleRef.getElementsByTagName("FileRef");
        if (fileRefs.getLength() > 0) {
          Element fileRef = (Element) fileRefs.item(0);
          String relPath = getElementValue(fileRef, "RelativePath", "");
          String absPath = getElementValue(fileRef, "Path", "");
          String packName = getElementValue(fileRef, "LivePackName", "");
          String fileName = getElementValue(fileRef, "Name", "");

          File resolved = null;
          if (fileName != null && !fileName.isEmpty()) {
            resolved = new File(samplesDir, fileName);
          }
          if (resolved == null || !resolved.exists()) {
            resolved = AbletonAssetResolver.resolveSamplePath(packName, relPath, absPath);
          }
          if (resolved != null && resolved.exists()) {
            samplePath = resolved.getAbsolutePath();
          }
        }
      }

      if (samplePath.isEmpty()) {
        return null; // Skip clips with missing samples
      }

      AudioTrackModel.AudioClip delugeClip = new AudioTrackModel.AudioClip();
      delugeClip.setTrackName(trackName);
      delugeClip.setFilePath(samplePath);
      delugeClip.setStartSamplePos(0);
      delugeClip.setEndSamplePos(-1);
      delugeClip.setLength(768); // 8 beats = 768 ticks default length
      delugeClip.setPlaying(true);

      return delugeClip;
    } catch (Exception e) {
      System.err.println("[AbletonTrackMapper] Failed to parse audio clip: " + e.getMessage());
      return null;
    }
  }

  // ── Helper Methods for DOM Traversals ──

  private static String getTrackName(Element trackEl, String defaultName) {
    NodeList nameList = trackEl.getElementsByTagName("Name");
    if (nameList.getLength() > 0) {
      Element nameEl = (Element) nameList.item(0);
      NodeList userNames = nameEl.getElementsByTagName("UserName");
      if (userNames.getLength() > 0) {
        Element userEl = (Element) userNames.item(0);
        String val = userEl.getAttribute("Value");
        if (val != null && !val.isBlank()) {
          return val.trim();
        }
      }
      NodeList effectiveNames = nameEl.getElementsByTagName("EffectiveName");
      if (effectiveNames.getLength() > 0) {
        Element effEl = (Element) effectiveNames.item(0);
        String val = effEl.getAttribute("Value");
        if (val != null && !val.isBlank()) {
          return val.trim();
        }
      }
    }
    return defaultName;
  }

  /** Searches direct children of {@code parent} for an element with the given tag name. */
  private static String getElementValue(Element parent, String tagName, String defaultVal) {
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child instanceof Element el && tagName.equals(el.getTagName())) {
        String val = el.getAttribute("Value");
        if (val != null && !val.isBlank()) {
          return val;
        }
      }
    }
    return defaultVal;
  }

  /** Returns the direct child element of {@code parent} with the given tag name, or null. */
  private static Element getDirectChild(Element parent, String tagName) {
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child instanceof Element el && tagName.equals(el.getTagName())) {
        return el;
      }
    }
    return null;
  }

  /** Internal tuple to represent a parsed note event before sparse grouping. */
  private static class ParsedNoteEvent {
    final int pitch;
    final double time;
    final double duration;
    final int velocity;

    ParsedNoteEvent(int pitch, double time, double duration, int velocity) {
      this.pitch = pitch;
      this.time = time;
      this.duration = duration;
      this.velocity = velocity;
    }
  }

  /** Dynamically configures a synthesizer track with premium presets matching its track name. */
  private static void applySemanticPreset(
      SynthTrackModel track, String name, File samplesDir, float rawVol) {
    String lower = name.toLowerCase();

    // ── 0. Hybrid Sampler Engine (Load pristine studio WAV samples if available!) ──
    File sampleFile = findMatchingSample(name, samplesDir);
    if (sampleFile != null && sampleFile.exists()) {
      track.setOsc1Type("SAMPLE");
      track.setOsc1SamplePath(sampleFile.getAbsolutePath());
      track.setOscMix(1.0f); // Play only the high-fidelity sample!

      // Attenuate sample volumes based on raw mixer levels and preset scaling factors
      if (lower.contains("kick")) {
        track.setVolume(rawVol * 0.50f); // Punchy but leaves room
      } else if (lower.contains("hat") || lower.contains("shaker") || lower.contains("cymbal")) {
        track.setVolume(rawVol * 0.10f); // Smooth, non-harsh high frequencies
      } else if (lower.contains("snare")
          || lower.contains("clap")
          || lower.contains("snap")
          || lower.contains("tom")) {
        track.setVolume(rawVol * 0.20f); // Clean mids
      } else if (lower.contains("rain")) {
        track.setVolume(rawVol * 0.04f); // Soft background rain sizzle
      } else if (lower.contains("choir")) {
        track.setVolume(rawVol * 0.30f); // Soft lush choir pads
        track.setEqTreble(8.0f); // Brighten the dark choir sample natively!
      } else if (lower.contains("string")) {
        track.setVolume(rawVol * 0.28f); // Cinematic strings
        track.setEqTreble(6.0f); // Brighten the dark strings sample natively!
      } else {
        track.setVolume(rawVol * 0.30f); // Lush melodic/ambient pads
      }

      if (lower.contains("choir") || lower.contains("string") || lower.contains("pad")) {
        // Long looping pads: slow swell, full sustain, lush release
        track.setEnv(0, new EnvelopeModel(0.20f, 1.0f, 0.90f, 0.80f, "NONE", 0.0f));
        track.setReverbSend(0.40f); // Wide cinematic space
      } else {
        // One-shot drum hits or plucks: instant attack, decay-only or fast release
        track.setEnv(0, new EnvelopeModel(0.001f, 0.60f, 0.0f, 0.25f, "NONE", 0.0f));
        if (lower.contains("clap") || lower.contains("snare") || lower.contains("tom")) {
          track.setReverbSend(0.20f); // Snare plate/clap room reverb
        }
      }
      System.out.println(
          "[AbletonTrackMapper] Successfully mapped track '"
              + name
              + "' to studio WAV sample: "
              + sampleFile.getName());

      // Explicitly open the LPF/HPF filters and turn them OFF for high-fidelity sample playback!
      track.setLpfFreq(20000.0f);
      track.setFilterMode(null);
      track.setHpfFreq(20.0f);
      track.setHpfMode(null);

      return;
    }

    if (lower.contains("kick")) {
      // Punchy 808/909-style Analog Kick Drum Synth with pitch sweep transient
      track.setOsc1Type("SINE");
      track.setOsc2Type("NONE");
      track.setOscMix(1.0f);
      track.setVolume(rawVol * 0.55f); // Slightly boosted for solid punch

      // Volume envelope: deep sub-bass thump decay
      track.setEnv(0, new EnvelopeModel(0.001f, 0.22f, 0.0f, 0.10f, "NONE", 0.0f));

      // Pitch Envelope: ultra-fast sweep (15ms decay) creates the punchy physical "click/knock"
      // transient!
      track.setEnv(1, new EnvelopeModel(0.001f, 0.015f, 0.0f, 0.05f, "PITCH", 0.65f));

      // Low-pass filter closed slightly to warm up and thicken the sub-bass
      track.setLpfFreq(0.35f);
      track.setLpfRes(0.20f);

    } else if (lower.contains("snare") || lower.contains("tom")) {
      // Snappy Analog Snare Drum (Sine body + Noise snap)
      track.setOsc1Type("SINE");
      track.setOsc2Type("NONE");
      track.setOscMix(0.30f);
      track.setNoiseVol(0.75f); // Bright snare wires!
      track.setVolume(rawVol * 0.20f); // Attenuated to match sample levels

      // Snappy snare envelope
      track.setEnv(0, new EnvelopeModel(0.001f, 0.14f, 0.0f, 0.10f, "NONE", 0.0f));

      // Filter open for crisp snare rattle
      track.setLpfFreq(0.65f);
      track.setLpfRes(0.10f);

      // Snare plate reverb!
      track.setReverbSend(0.25f);

    } else if (lower.contains("hat") || lower.contains("shaker") || lower.contains("cymbal")) {
      // Crisp Analog Hi-Hat / Shaker (Pure high-passed White Noise)
      track.setOsc1Type("NONE");
      track.setOsc2Type("NONE");
      track.setNoiseVol(0.85f);
      track.setVolume(rawVol * 0.10f); // Attenuated to match sample levels

      // Ultra-fast decay envelope (slightly longer for open hats/shakers)
      float decay = lower.contains("open") ? 0.22f : 0.05f;
      track.setEnv(0, new EnvelopeModel(0.001f, decay, 0.0f, 0.05f, "NONE", 0.0f));

      // High-pass filter open to remove low rumble, leaving crisp sizzle!
      track.setHpfFreq(0.70f);
      track.setLpfFreq(1.0f); // Low-pass fully open

    } else if (lower.contains("clap") || lower.contains("snap")) {
      // Crisp Band-passed Clap / Snap
      track.setOsc1Type("NONE");
      track.setOsc2Type("NONE");
      track.setNoiseVol(0.90f);
      track.setVolume(rawVol * 0.20f); // Attenuated to match sample levels

      // Snappy clap envelope
      track.setEnv(0, new EnvelopeModel(0.001f, 0.08f, 0.0f, 0.08f, "NONE", 0.0f));

      // Band-pass filtering
      track.setLpfFreq(0.45f);
      track.setLpfRes(0.15f);

      // Nice clap hall reverb!
      track.setReverbSend(0.35f);

    } else if (lower.contains("bass")) {
      // Legendary Billie Jean Detuned Dual-Oscillator Synth Bass (Roland Juno / Yamaha DX7 style)
      track.setOsc1Type("SQUARE"); // Hollow woody vintage body
      track.setOsc2Type("SAW"); // Rich analog buzz
      track.setOsc2Transpose(0);
      track.setOsc2Cents(15); // Fat detuning
      track.setOscMix(0.50f); // Equal blend
      track.setVolume(rawVol * 1.00f); // Bass presence!

      // Snappy volume envelope
      track.setEnv(0, new EnvelopeModel(0.002f, 0.22f, 0.30f, 0.15f, "NONE", 0.0f));

      // Resonant filter for the rubber-band bounce!
      track.setLpfFreq(0.24f); // Darker, warmer sub-bass!
      track.setLpfRes(0.28f); // Juicy plump resonance!

      // Snappy filter envelope sweep (low sustain makes it bouncy!)
      track.setEnv(1, new EnvelopeModel(0.002f, 0.14f, 0.15f, 0.15f, "FILTER", 0.35f));

    } else if (lower.contains("string") || lower.contains("pad") || lower.contains("choir")) {
      // Lush, wide stereo analog string/pad
      track.setOsc1Type("SAW");
      track.setOsc2Type("SAW");
      track.setOsc2Transpose(0);
      track.setOsc2Cents(12); // Warm detuning
      track.setOscMix(0.50f);
      track.setVolume(rawVol * 0.32f); // Attenuated to clear up the mid-range

      // Slow cinematic swell volume envelope
      track.setEnv(0, new EnvelopeModel(0.40f, 1.50f, 0.85f, 1.20f, "NONE", 0.0f));

      // Moderate low-pass filter modulated by slow sweep envelope
      track.setLpfFreq(0.45f);
      track.setLpfRes(0.25f);
      track.setEnv(1, new EnvelopeModel(0.60f, 2.00f, 0.70f, 1.50f, "FILTER", 0.25f));

      // Fat 4-voice Unison with stereo spread
      track.setUnisonNum(4);
      track.setUnisonDetune(0.15f);
      track.setUnisonStereoSpread(0.80f);

      // High Reverb and Chorus (Mod FX) spatial effects
      track.setReverbSend(0.45f);
      track.setModFxType("CHORUS");
      track.setModFxRate(0.25f);
      track.setModFxDepth(0.50f);

    } else if (lower.contains("glock") || lower.contains("bell") || lower.contains("calimba")) {
      // Sparkling ambient bells / plucks
      track.setOsc1Type("SINE");
      track.setOsc2Type("TRIANGLE");
      track.setOsc2Transpose(12); // Octave up
      track.setOscMix(0.40f);
      track.setVolume(rawVol * 0.15f); // Soft, sparkling Glock

      // Plucky decay-only volume envelope
      track.setEnv(0, new EnvelopeModel(0.002f, 0.35f, 0.0f, 0.40f, "NONE", 0.0f));

      // Open low-pass filter
      track.setLpfFreq(0.85f);
      track.setLpfRes(0.10f);

      // Strong Delay and Reverb sends for sparkling space
      track.setReverbSend(0.55f);
      track.setDelaySend(0.40f);

    } else if (lower.contains("meuw") || lower.contains("lead")) {
      // Breath-like resonant vocal vowel lead ("Meuw Lead" from Billie Jean bridge)
      track.setOsc1Type("TRIANGLE"); // Soft pure body
      track.setOsc2Type("SAW"); // High-frequency buzz
      track.setOsc2Transpose(0);
      track.setOsc2Cents(10); // Subtle detune
      track.setOscMix(0.40f);
      track.setVolume(rawVol * 0.35f);

      // Fast volume envelope
      track.setEnv(0, new EnvelopeModel(0.005f, 0.30f, 0.60f, 0.20f, "NONE", 0.0f));

      // Highly resonant filter to create the vocal vowel sound!
      track.setLpfFreq(0.35f);
      track.setLpfRes(0.45f); // High resonance!

      // Fast vocal envelope sweep on filter cutoff (attack creates the "m", decay the "eow"!)
      track.setEnv(1, new EnvelopeModel(0.02f, 0.12f, 0.10f, 0.10f, "FILTER", 0.55f));

      // Chorus + Reverb for lush stereo placement
      track.setModFxType("CHORUS");
      track.setModFxRate(0.40f);
      track.setModFxDepth(0.35f);
      track.setReverbSend(0.30f);

    } else if (lower.contains("trumpet") || lower.contains("brass") || lower.contains("horn")) {
      // Bright, fat analog synth brass section (Oberheim OB-Xa style)
      track.setOsc1Type("SAW");
      track.setOsc2Type("SAW");
      track.setOsc2Transpose(0);
      track.setOsc2Cents(18); // Wide brass detuning
      track.setOscMix(0.50f);
      track.setVolume(rawVol * 0.38f);

      // Instant attack volume envelope
      track.setEnv(0, new EnvelopeModel(0.001f, 0.40f, 0.75f, 0.25f, "NONE", 0.0f));

      // Brass filter sweep on attack
      track.setLpfFreq(0.38f);
      track.setLpfRes(0.20f);
      track.setEnv(1, new EnvelopeModel(0.05f, 0.20f, 0.70f, 0.20f, "FILTER", 0.30f));

      // Chorus + Reverb to widen the horns
      track.setModFxType("CHORUS");
      track.setModFxRate(0.20f);
      track.setModFxDepth(0.25f);
      track.setReverbSend(0.25f);

    } else if (lower.contains("guitar")) {
      // Plucky organic acoustic / funky guitar
      track.setOsc1Type("TRIANGLE");
      track.setOsc2Type("SAW");
      track.setOsc2Transpose(0);
      track.setOsc2Cents(-8); // Warm detuning
      track.setOscMix(0.65f);
      track.setVolume(rawVol * 0.25f); // Clean up mid-range excess

      // Plucky, fast decay volume envelope
      track.setEnv(0, new EnvelopeModel(0.01f, 0.18f, 0.35f, 0.20f, "NONE", 0.0f));

      // Snappy filter decay sweep
      track.setLpfFreq(0.40f);
      track.setLpfRes(0.20f);
      track.setEnv(1, new EnvelopeModel(0.01f, 0.15f, 0.20f, 0.15f, "FILTER", 0.30f));

      // Chorus (Mod FX) + Delay to widen and organicize
      track.setModFxType("CHORUS");
      track.setModFxRate(0.35f);
      track.setModFxDepth(0.30f);
      track.setDelaySend(0.15f);
      track.setReverbSend(0.20f);

    } else {
      // Warm, polyphonic analog synth default
      track.setOsc1Type("SAW");
      track.setOsc2Type("TRIANGLE");
      track.setOsc2Transpose(-12); // Sub-octave
      track.setOscMix(0.60f);
      track.setVolume(rawVol * 0.30f);

      track.setEnv(0, new EnvelopeModel(0.02f, 0.40f, 0.60f, 0.30f, "NONE", 0.0f));
      track.setLpfFreq(0.55f);
      track.setLpfRes(0.20f);
      track.setReverbSend(0.15f);
    }
  }

  private static Float getManualParamValue(Element parent, String paramTag) {
    try {
      NodeList paramNodes = parent.getElementsByTagName(paramTag);
      if (paramNodes.getLength() == 0) return null;
      Element paramEl = (Element) paramNodes.item(0);
      NodeList manualNodes = paramEl.getElementsByTagName("Manual");
      if (manualNodes.getLength() == 0) return null;
      Element manualEl = (Element) manualNodes.item(0);
      if (manualEl.hasAttribute("Value")) {
        return Float.parseFloat(manualEl.getAttribute("Value"));
      }
    } catch (Exception e) {
      // Ignore and return null
    }
    return null;
  }

  private static Boolean getManualParamBoolean(Element parent, String paramTag) {
    try {
      NodeList paramNodes = parent.getElementsByTagName(paramTag);
      if (paramNodes.getLength() == 0) return null;
      Element paramEl = (Element) paramNodes.item(0);
      NodeList manualNodes = paramEl.getElementsByTagName("Manual");
      if (manualNodes.getLength() == 0) {
        if (paramEl.hasAttribute("Value")) {
          return "true".equalsIgnoreCase(paramEl.getAttribute("Value"));
        }
        return null;
      }
      Element manualEl = (Element) manualNodes.item(0);
      if (manualEl.hasAttribute("Value")) {
        return "true".equalsIgnoreCase(manualEl.getAttribute("Value"));
      }
    } catch (Exception e) {
      // Ignore and return null
    }
    return null;
  }

  private static boolean parseNativeSimpler(Element trackEl, SynthTrackModel track) {
    NodeList simplerNodes = trackEl.getElementsByTagName("OriginalSimpler");
    if (simplerNodes.getLength() == 0) {
      return false; // No Simpler device found
    }
    Element simplerEl = (Element) simplerNodes.item(0);
    System.out.println(
        "🤖 [GENERIC PARSER] Found native Ableton Simpler device on track: " + track.getName());

    // 1. Parse Transposition / Tuning (Ableton semitones -> Deluge transpose; cents -> Deluge
    // cents)
    Float transpose = getManualParamValue(simplerEl, "TransposeKey");
    Float fineTune = getManualParamValue(simplerEl, "TransposeFine");
    if (transpose != null) {
      track.setOsc2Transpose(Math.round(transpose));
    }
    if (fineTune != null) {
      track.setOsc2Cents(Math.round(fineTune));
    }

    // 2. Parse Volume Envelope (Env 0)
    NodeList volPanNodes = simplerEl.getElementsByTagName("VolumeAndPan");
    if (volPanNodes.getLength() > 0) {
      Element volPanEl = (Element) volPanNodes.item(0);
      Float attack = getManualParamValue(volPanEl, "AttackTime");
      Float decay = getManualParamValue(volPanEl, "DecayTime");
      Float sustain = getManualParamValue(volPanEl, "SustainLevel");
      Float release = getManualParamValue(volPanEl, "ReleaseTime");

      // Convert millisecond-based times from the XML to normalized seconds for the Deluge engine
      float a = attack != null ? attack / 1000.0f : 0.002f;
      float d = decay != null ? decay / 1000.0f : 0.20f;
      float s = sustain != null ? sustain : 1.0f;
      float r = release != null ? release / 1000.0f : 0.15f;

      // Env 0 is hardwired to volume in the Deluge engine
      track.setEnv(0, new EnvelopeModel(a, d, s, r, "NONE", 0.0f));
      System.out.println(
          String.format(
              "   -> Volume Envelope (Env 0): A=%.3fs, D=%.3fs, S=%.2f, R=%.3fs", a, d, s, r));
    }

    // 3. Parse Filter & Filter Envelope (Env 1)
    NodeList filterNodes = simplerEl.getElementsByTagName("Filter");
    if (filterNodes.getLength() > 0) {
      Element filterEl = (Element) filterNodes.item(0);
      Boolean isOn = getManualParamBoolean(filterEl, "IsOn");
      if (isOn != null && isOn) {
        Float freq = getManualParamValue(filterEl, "Freq");
        Float res = getManualParamValue(filterEl, "Res");
        if (freq == null) {
          freq = getManualParamValue(filterEl, "LegacyQ"); // fallback
        }

        // Convert Cutoff Frequency (Hz) to normalized 0..1 scale.
        // Ableton Simpler cutoff frequency is logarithmic, ranging from 30Hz to 22,000Hz.
        // We translate it using natural logarithms to achieve pristine scaling:
        //   normFreq = (ln(freq) - ln(30)) / (ln(22000) - ln(30))
        float normFreq = 1.0f;
        if (freq != null) {
          float fVal = Math.max(30.0f, Math.min(22000.0f, freq));
          normFreq =
              (float) ((Math.log(fVal) - Math.log(30.0)) / (Math.log(22000.0) - Math.log(30.0)));
          track.setLpfFreq(normFreq);
        }

        // Convert resonance/Q to normalized 0..1 scale (clamped relative to Simpler's 1.25 maximum)
        float normRes = 0.15f;
        if (res != null) {
          normRes = Math.max(0.0f, Math.min(1.0f, res / 1.25f));
          track.setLpfRes(normRes);
        }

        System.out.println(
            String.format(
                "   -> Filter LPF: Cutoff=%.1fHz (norm=%.2f), Resonance=%.2f (norm=%.2f)",
                freq != null ? freq : 22000.0f, normFreq, res != null ? res : 0.0f, normRes));

        // Parse Filter Envelope (Env 1)
        NodeList envNodes = filterEl.getElementsByTagName("Envelope");
        if (envNodes.getLength() > 0) {
          Element envEl = (Element) envNodes.item(0);
          Float fAttack = getManualParamValue(envEl, "AttackTime");
          Float fDecay = getManualParamValue(envEl, "DecayTime");
          Float fSustain = getManualParamValue(envEl, "SustainLevel");
          Float fRelease = getManualParamValue(envEl, "ReleaseTime");
          Float fAmount = getManualParamValue(envEl, "Amount");

          if (fAmount != null && Math.abs(fAmount) > 0.01f) {
            // Convert millisecond envelope rates to seconds
            float fa = fAttack != null ? fAttack / 1000.0f : 0.002f;
            float fd = fDecay != null ? fDecay / 1000.0f : 0.20f;
            float fs = fSustain != null ? fSustain : 0.0f;
            float fr = fRelease != null ? fRelease / 1000.0f : 0.15f;

            // Simpler's filter envelope amount ranges from -72 to +72 semitones.
            // We normalize this relative to the 72-semitone ceiling to fit Deluge's -1.0 to 1.0
            // depth.
            float depth = fAmount / 72.0f;

            // Env 1 is patched dynamically to the filter cutoff in the engine
            track.setEnv(1, new EnvelopeModel(fa, fd, fs, fr, "FILTER", depth));
            System.out.println(
                String.format(
                    "   -> Filter Envelope (Env 1): A=%.3fs, D=%.3fs, S=%.2f, R=%.3fs, Depth=%.2f",
                    fa, fd, fs, fr, depth));
          }
        }
      }
    }
    return true;
  }

  /** Searches the samplesDir for a high-fidelity WAV sample matching the track name. */
  private static File findMatchingSample(String trackName, File samplesDir) {
    if (samplesDir == null || !samplesDir.exists() || !samplesDir.isDirectory()) return null;
    String lower = trackName.toLowerCase();

    File[] files = samplesDir.listFiles();
    if (files == null) return null;

    // 1. Try exact/contains match on filename (without extension)
    for (File f : files) {
      if (f.getName().endsWith(".asd")) continue; // Skip Ableton analysis files
      int dotIdx = f.getName().lastIndexOf('.');
      if (dotIdx <= 0) continue;
      String nameWithoutExt = f.getName().substring(0, dotIdx).toLowerCase();

      // Check if track name contains sample name or vice versa
      if (lower.contains(nameWithoutExt) || nameWithoutExt.contains(lower)) {
        return f;
      }
    }

    // 2. Specific fallback mappings
    if (lower.contains("kick")) return new File(samplesDir, "Kick.wav");
    if (lower.contains("snare")) return new File(samplesDir, "snare.wav");
    if (lower.contains("closed hat")
        || lower.contains("closed_hat")
        || (lower.contains("hat") && !lower.contains("open"))) {
      return new File(samplesDir, "Closed Hat.wav");
    }
    if (lower.contains("open hat") || lower.contains("open_hat")) {
      if (lower.contains("2")) return new File(samplesDir, "Open Hat 2.wav");
      return new File(samplesDir, "Open Hat.wav");
    }
    if (lower.contains("clap")) return new File(samplesDir, "clap.wav");
    if (lower.contains("snap")) return new File(samplesDir, "Snap.wav");
    if (lower.contains("shaker")) return new File(samplesDir, "Shaker.wav");
    if (lower.contains("tom")) return new File(samplesDir, "Tom.wav");
    if (lower.contains("cymbal")) {
      if (lower.contains("2")) return new File(samplesDir, "Cymbal 2.wav");
      return new File(samplesDir, "Cymbal 1.wav");
    }
    if (lower.contains("rain")) return new File(samplesDir, "Rain.wav");
    if (lower.contains("choir")) return new File(samplesDir, "Choir.wav");
    if (lower.contains("strings")) {
      if (lower.contains("stac")) return new File(samplesDir, "Strings Stac.wav");
      return new File(samplesDir, "House Strings Octaves.wav");
    }

    return null;
  }
}
