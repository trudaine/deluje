package org.deluge.ableton;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.deluge.model.AudioTrackModel;
import org.deluge.model.ClipModel;
import org.deluge.model.HighResNote;
import org.deluge.model.KitTrackModel;
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
    if (doc == null || project == null) return;

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
            importMidiTrack(trackEl, project);
          } else if ("AudioTrack".equals(tagName)) {
            importAudioTrack(trackEl, project);
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
  private static void importMidiTrack(Element trackEl, ProjectModel project) {
    String trackName = getTrackName(trackEl, "MIDI Track");

    // Check if track contains a Drum Group Device (Drum Rack)
    NodeList drumGroupDevices = trackEl.getElementsByTagName("DrumGroupDevice");
    if (drumGroupDevices.getLength() > 0) {
      // ── DRUM RACK TRACK → KIT TRACK ──
      Element drumRack = (Element) drumGroupDevices.item(0);
      importDrumRackTrack(trackEl, drumRack, trackName, project);
    } else {
      // ── REGULAR MIDI TRACK → SYNTH TRACK ──
      importSynthTrack(trackEl, trackName, project);
    }
  }

  /** Imports an Ableton MIDI track as a SynthTrackModel. */
  private static void importSynthTrack(Element trackEl, String name, ProjectModel project) {
    SynthTrackModel synthTrack = new SynthTrackModel(name);
    // Set default beautiful analog oscillators
    synthTrack.setOsc1Type("SAW");
    synthTrack.setOsc2Type("NONE");
    synthTrack.setOscMix(1.0f);
    synthTrack.setVolume(0.5f);

    // Parse clips
    NodeList clipElements = trackEl.getElementsByTagName("MidiClip");
    for (int i = 0; i < clipElements.getLength(); i++) {
      Element clipEl = (Element) clipElements.item(i);
      ClipModel clip = parseSynthClip(clipEl);
      if (clip != null) {
        synthTrack.addClip(clip);
      }
    }

    // Fallback: if no clips were parsed, create a default empty clip
    if (synthTrack.getClips().isEmpty()) {
      synthTrack.addClip(new ClipModel("Clip 1", 8, 16));
    }

    project.addTrack(synthTrack);
  }

  /** Parses a MidiClip element into a sparse-pitch piano roll ClipModel. */
  private static ClipModel parseSynthClip(Element clipEl) {
    try {
      String clipName = getElementValue(clipEl, "Name", "Clip");
      double clipEnd = Double.parseDouble(getElementValue(clipEl, "CurrentEnd", "16.0"));
      int stepCount = (int) Math.round(clipEnd * 4); // 4 steps per beat (16th notes)
      if (stepCount <= 0) stepCount = 16;

      // 1. Gather all note events from the Ableton XML
      List<ParsedNoteEvent> events = new ArrayList<>();
      NodeList keyTrackNodes = clipEl.getElementsByTagName("KeyTrack");
      for (int k = 0; k < keyTrackNodes.getLength(); k++) {
        Element keyTrack = (Element) keyTrackNodes.item(k);
        int pitch = Integer.parseInt(getElementValue(keyTrack, "MidiKey", "-1"));
        if (pitch == -1) continue;

        NodeList noteEvents = keyTrack.getElementsByTagName("MidiNoteEvent");
        for (int n = 0; n < noteEvents.getLength(); n++) {
          Element noteEv = (Element) noteEvents.item(n);
          double time = Double.parseDouble(noteEv.getAttribute("Time"));
          double duration = Double.parseDouble(noteEv.getAttribute("Duration"));
          int velocity = Integer.parseInt(noteEv.getAttribute("Velocity"));
          events.add(new ParsedNoteEvent(pitch, time, duration, velocity));
        }
      }

      if (events.isEmpty()) {
        // Return a default empty clip if no notes exist
        return new ClipModel(clipName, 8, stepCount);
      }

      // 2. Identify and sort unique pitches to build a sparse-pitch piano roll
      List<Integer> uniquePitches = new ArrayList<>();
      for (ParsedNoteEvent ev : events) {
        if (!uniquePitches.contains(ev.pitch)) {
          uniquePitches.add(ev.pitch);
        }
      }
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
        HighResNote hrn = new HighResNote(tickPos, tickLen, normVel, 1.0f, 0);
        clip.getRawNoteEvents(r).add(hrn);

        // B. Quantized Grid Path (16th note steps)
        int stepIdx = (int) Math.round(ev.time * 4);
        if (stepIdx >= 0 && stepIdx < stepCount) {
          clip.setStep(
              r, stepIdx, StepData.of(true, normVel, StepData.DEFAULT_CLICK_GATE, 1.0f, 60));
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
      Element trackEl, Element drumRack, String name, ProjectModel project) {
    KitTrackModel kitTrack = new KitTrackModel(name);

    // 1. Map Drum Pads (Branches) to Kit Channels
    NodeList branches = drumRack.getElementsByTagName("DrumBranch");
    Map<Integer, Integer> midiPitchToDrumIndex = new HashMap<>();

    for (int i = 0; i < branches.getLength(); i++) {
      Element branch = (Element) branches.item(i);
      String padName = getElementValue(branch, "Name", "Drum Pad");
      int receivingNote = Integer.parseInt(getElementValue(branch, "ReceivingNote", "-1"));

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
      }
    }

    // Fallback: if no clips were parsed, create a default empty clip
    if (kitTrack.getClips().isEmpty()) {
      kitTrack.addClip(new ClipModel("Clip 1", drumCount, 16));
    }

    project.addTrack(kitTrack);
  }

  /** Parses a MidiClip element for a drum kit track. */
  private static ClipModel parseDrumClip(
      Element clipEl, int drumCount, Map<Integer, Integer> midiPitchToDrumIndex) {
    try {
      String clipName = getElementValue(clipEl, "Name", "Clip");
      double clipEnd = Double.parseDouble(getElementValue(clipEl, "CurrentEnd", "16.0"));
      int stepCount = (int) Math.round(clipEnd * 4);
      if (stepCount <= 0) stepCount = 16;

      ClipModel clip = new ClipModel(clipName, drumCount, stepCount);

      // Initialize raw note lists
      for (int d = 0; d < drumCount; d++) {
        clip.setRawNoteEvents(d, new ArrayList<>());
      }

      NodeList keyTrackNodes = clipEl.getElementsByTagName("KeyTrack");
      for (int k = 0; k < keyTrackNodes.getLength(); k++) {
        Element keyTrack = (Element) keyTrackNodes.item(k);
        int pitch = Integer.parseInt(getElementValue(keyTrack, "MidiKey", "-1"));

        // Match pitch to drum slot index
        Integer r = midiPitchToDrumIndex.get(pitch);
        if (r == null || r < 0 || r >= drumCount) continue;

        NodeList noteEvents = keyTrack.getElementsByTagName("MidiNoteEvent");
        for (int n = 0; n < noteEvents.getLength(); n++) {
          Element noteEv = (Element) noteEvents.item(n);
          double time = Double.parseDouble(noteEv.getAttribute("Time"));
          double duration = Double.parseDouble(noteEv.getAttribute("Duration"));
          int velocity = Integer.parseInt(noteEv.getAttribute("Velocity"));

          // A. High-Res Path
          int tickPos = (int) Math.round(time * 96);
          int tickLen = (int) Math.round(duration * 96);
          float normVel = velocity / 127.0f;
          HighResNote hrn = new HighResNote(tickPos, tickLen, normVel, 1.0f, 0);
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
  private static void importAudioTrack(Element trackEl, ProjectModel project) {
    String trackName = getTrackName(trackEl, "Audio Track");
    AudioTrackModel audioTrack = new AudioTrackModel(trackName);

    // Parse audio clips
    NodeList audioClips = trackEl.getElementsByTagName("AudioClip");
    for (int i = 0; i < audioClips.getLength(); i++) {
      Element clipEl = (Element) audioClips.item(i);
      AudioTrackModel.AudioClip delugeClip = parseAudioClip(clipEl, trackName);
      if (delugeClip != null) {
        audioTrack.addAudioClip(delugeClip);
      }
    }

    project.addTrack(audioTrack);
  }

  /** Parses an Ableton AudioClip element, resolving its sample path. */
  private static AudioTrackModel.AudioClip parseAudioClip(Element clipEl, String trackName) {
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

          File resolved = AbletonAssetResolver.resolveSamplePath(packName, relPath, absPath);
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
}
