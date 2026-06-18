package org.deluge.midi;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.sound.midi.*;
import org.deluge.model.ClipModel;
import org.deluge.model.HighResNote;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;

/**
 * A native, high-fidelity compiler that parses standard MIDI files and translates them into
 * in-memory Deluge ProjectModels, complete with tempo auto-detection, high-res tick scaling, and
 * pitch-splitting.
 */
public class MidiToProjectCompiler {
  private static final Logger LOG = Logger.getLogger(MidiToProjectCompiler.class.getName());

  /** Beautiful, premium color palette mapping user-friendly names to hex colors. */
  public static final Map<String, String> COLOR_PALETTE = new LinkedHashMap<>();

  static {
    COLOR_PALETTE.put("Cyan", "#00FFCC");
    COLOR_PALETTE.put("Blue", "#0055FF");
    COLOR_PALETTE.put("Purple", "#AA00FF");
    COLOR_PALETTE.put("Pink", "#FF00AA");
    COLOR_PALETTE.put("Red", "#FF0055");
    COLOR_PALETTE.put("Orange", "#FF5500");
    COLOR_PALETTE.put("Yellow", "#FFFF00");
    COLOR_PALETTE.put("Green", "#00FF55");
  }

  /** Represents a parsed note event from a MIDI file. */
  public static class NoteEvent {
    public final int pitch;
    public final long startTick;
    public final long durationTicks;
    public final int velocity;

    public NoteEvent(int pitch, long startTick, long durationTicks, int velocity) {
      this.pitch = pitch;
      this.startTick = startTick;
      this.durationTicks = durationTicks;
      this.velocity = velocity;
    }
  }

  /** Configuration for importing a specific MIDI track. */
  public static class TrackImportConfig {
    public int trackIndex;
    public String trackName;
    public boolean importEnabled = true;
    public String mappedPresetName = "073 Piano";
    public boolean isKit = false;
    public String colorHex = "#00FFCC"; // Default Cyan

    // Pitch Splitting settings
    public boolean splitEnabled = false;
    public int splitPoint = 60; // C3
    public String splitPresetName = "001 Sync Bass";
    public String splitColorHex = "#0055FF"; // Default Blue for Bass
  }

  /** Parses a MIDI file to extract high-level metadata (tracks, tempo, note counts). */
  public static List<TrackImportConfig> parseMidiMetadata(File file) throws Exception {
    Sequence sequence = MidiSystem.getSequence(file);
    List<TrackImportConfig> configs = new ArrayList<>();
    Track[] tracks = sequence.getTracks();

    for (int i = 0; i < tracks.length; i++) {
      Track track = tracks[i];
      String name = "Track " + (i + 1);
      int noteCount = 0;

      for (int j = 0; j < track.size(); j++) {
        MidiEvent event = track.get(j);
        MidiMessage message = event.getMessage();
        if (message instanceof ShortMessage sm) {
          if (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0) {
            noteCount++;
          }
        } else if (message instanceof MetaMessage mm) {
          if (mm.getType() == 0x03) { // Track Name
            byte[] data = mm.getData();
            name = new String(data).trim();
          }
        }
      }

      if (noteCount > 0 || i == 0) { // Keep track 0 or any track with notes
        TrackImportConfig config = new TrackImportConfig();
        config.trackIndex = i;
        config.trackName = name + " (" + noteCount + " notes)";
        config.importEnabled = noteCount > 0;
        configs.add(config);
      }
    }
    return configs;
  }

  /** Compiles the selected MIDI tracks into a new ProjectModel. */
  public static ProjectModel compileMidi(File file, List<TrackImportConfig> configs)
      throws Exception {
    Sequence sequence = MidiSystem.getSequence(file);
    ProjectModel project = new ProjectModel();

    // 1. Detect BPM/Tempo from MIDI MetaEvents
    float bpm = detectBpm(sequence);
    project.setBpm(bpm);
    LOG.info("Auto-detected MIDI tempo: " + bpm + " BPM");

    int resolution = sequence.getResolution();
    Track[] tracks = sequence.getTracks();

    // Create a mapping of config by track index
    Map<Integer, TrackImportConfig> configMap = new HashMap<>();
    for (TrackImportConfig c : configs) {
      if (c.importEnabled) {
        configMap.put(c.trackIndex, c);
      }
    }

    for (int i = 0; i < tracks.length; i++) {
      if (!configMap.containsKey(i)) continue;
      Track track = tracks[i];
      TrackImportConfig config = configMap.get(i);

      // Extract all note events from this track
      List<NoteEvent> notes = extractNoteEvents(track);
      if (notes.isEmpty()) continue;

      if (config.splitEnabled) {
        // Split notes into Low (Bass) and High (Lead) categories
        List<NoteEvent> lowNotes = new ArrayList<>();
        List<NoteEvent> highNotes = new ArrayList<>();
        for (NoteEvent ne : notes) {
          if (ne.pitch < config.splitPoint) {
            lowNotes.add(ne);
          } else {
            highNotes.add(ne);
          }
        }

        // Compile Bass Track
        if (!lowNotes.isEmpty()) {
          SynthTrackModel bassTrack = new SynthTrackModel(config.splitPresetName);
          bassTrack.setColourHex(config.splitColorHex);
          ClipModel bassClip = compileNotesToClip(lowNotes, resolution, "Bass Clip");
          bassTrack.addClip(bassClip);
          project.addTrack(bassTrack);
        }

        // Compile Lead Track
        if (!highNotes.isEmpty()) {
          SynthTrackModel leadTrack = new SynthTrackModel(config.mappedPresetName);
          leadTrack.setColourHex(config.colorHex);
          ClipModel leadClip = compileNotesToClip(highNotes, resolution, "Lead Clip");
          leadTrack.addClip(leadClip);
          project.addTrack(leadTrack);
        }

      } else {
        // Standard single-track compilation
        SynthTrackModel synthTrack = new SynthTrackModel(config.mappedPresetName);
        synthTrack.setColourHex(config.colorHex);
        ClipModel clip = compileNotesToClip(notes, resolution, "Clip 1");
        synthTrack.addClip(clip);
        project.addTrack(synthTrack);
      }
    }

    return project;
  }

  private static ClipModel compileNotesToClip(
      List<NoteEvent> notes, int resolution, String clipName) {
    // Determine unique pitches for row mappings
    List<Integer> uniquePitches = new ArrayList<>();
    long maxEndTick = 0;
    for (NoteEvent ne : notes) {
      if (!uniquePitches.contains(ne.pitch)) {
        uniquePitches.add(ne.pitch);
      }
      long endTick = ne.startTick + ne.durationTicks;
      if (endTick > maxEndTick) {
        maxEndTick = endTick;
      }
    }

    // Sort pitches descending (highest pitch on row 0)
    Collections.sort(uniquePitches, Collections.reverseOrder());
    int rowCount = uniquePitches.size();

    // Quantize length to 16th note steps (4 steps per beat)
    double totalBeats = (double) maxEndTick / resolution;
    int stepCount = (int) Math.round(totalBeats * 4);
    if (stepCount <= 0) stepCount = 16;
    // Round to nearest multiple of 16 for standard bar alignment
    stepCount = ((stepCount + 15) / 16) * 16;

    ClipModel clip = new ClipModel(clipName, rowCount, stepCount);

    Map<Integer, Integer> pitchToRow = new HashMap<>();
    for (int r = 0; r < rowCount; r++) {
      int pitch = uniquePitches.get(r);
      clip.setRowYNote(r, pitch);
      pitchToRow.put(pitch, r);
      clip.setRawNoteEvents(r, new ArrayList<>());
    }

    // Populate note events into high-res and quantized grids
    for (NoteEvent ne : notes) {
      int r = pitchToRow.get(ne.pitch);

      // 1. High-Res Path (96 PPQ ticks in ClipModel)
      int hrStart = (int) Math.round(((double) ne.startTick / resolution) * 96);
      int hrDur = (int) Math.round(((double) ne.durationTicks / resolution) * 96);
      float normVel = ne.velocity / 127.0f;
      HighResNote hrn = new HighResNote(hrStart, hrDur, normVel, 1.0f, 0);
      clip.getRawNoteEvents(r).add(hrn);

      // 2. Quantized Grid Path (16th note steps)
      int stepIdx = (int) Math.round(((double) ne.startTick / resolution) * 4);
      if (stepIdx >= 0 && stepIdx < stepCount) {
        clip.setStep(r, stepIdx, StepData.of(true, normVel, StepData.DEFAULT_CLICK_GATE, 1.0f, 60));
      }
    }

    return clip;
  }

  private static List<NoteEvent> extractNoteEvents(Track track) {
    List<NoteEvent> notes = new ArrayList<>();
    Map<Integer, ShortMessage> activeNotes = new HashMap<>();
    Map<Integer, Long> activeStarts = new HashMap<>();

    for (int i = 0; i < track.size(); i++) {
      MidiEvent event = track.get(i);
      MidiMessage message = event.getMessage();
      if (!(message instanceof ShortMessage sm)) continue;

      int command = sm.getCommand();
      int pitch = sm.getData1();
      int velocity = sm.getData2();

      if (command == ShortMessage.NOTE_ON && velocity > 0) {
        // Note On
        activeNotes.put(pitch, sm);
        activeStarts.put(pitch, event.getTick());
      } else if (command == ShortMessage.NOTE_OFF
          || (command == ShortMessage.NOTE_ON && velocity == 0)) {
        // Note Off
        if (activeNotes.containsKey(pitch)) {
          long startTick = activeStarts.remove(pitch);
          long endTick = event.getTick();
          ShortMessage onMsg = activeNotes.remove(pitch);
          long duration = endTick - startTick;
          if (duration <= 0) duration = 1; // safety minimum

          notes.add(new NoteEvent(pitch, startTick, duration, onMsg.getData2()));
        }
      }
    }
    return notes;
  }

  private static float detectBpm(Sequence sequence) {
    Track[] tracks = sequence.getTracks();
    for (Track track : tracks) {
      for (int i = 0; i < track.size(); i++) {
        MidiEvent event = track.get(i);
        MidiMessage message = event.getMessage();
        if (message instanceof MetaMessage mm && mm.getType() == 0x51) { // Set Tempo
          byte[] data = mm.getData();
          if (data.length >= 3) {
            int tempo = ((data[0] & 0xFF) << 16) | ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
            return 60_000_000.0f / tempo;
          }
        }
      }
    }
    return 120.0f; // Default fallback
  }
}
