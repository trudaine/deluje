package org.deluge.model;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A highly portable, line-based Scripting and Macro Engine for the Deluge application. Allows
 * recording user actions (Consequences), saving them to macro script files, and loading/playing
 * them back to automate workflows.
 */
public class ScriptingEngine {
  private final List<Consequence> recordedActions = new ArrayList<>();
  private boolean recording = false;

  public void startRecording() {
    recordedActions.clear();
    recording = true;
  }

  public void stopRecording() {
    recording = false;
  }

  public boolean isRecording() {
    return recording;
  }

  public List<Consequence> getRecordedActions() {
    return new ArrayList<>(recordedActions);
  }

  /** Record an action if recording is active. */
  public void record(Consequence action) {
    if (recording && action != null) {
      // Don't record composite/compound actions directly, just record their leaves
      if (action instanceof Consequence.CompoundConsequence compound) {
        recordedActions.addAll(compound.children());
      } else {
        recordedActions.add(action);
      }
    }
  }

  /** Save the recorded actions to a simple, human-readable line-based script. */
  public void saveScript(File file) throws IOException {
    try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
      for (Consequence c : recordedActions) {
        String line = serialize(c);
        if (line != null) {
          writer.write(line);
          writer.newLine();
        }
      }
    }
  }

  /**
   * Load a script file, deserialize it into Consequence commands, and execute them on the project.
   */
  public List<Consequence> loadAndExecuteScript(File file, ProjectModel project)
      throws IOException {
    List<Consequence> scriptActions = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#")) {
          continue; // skip empty lines and comments
        }
        Consequence action = deserialize(line, project);
        if (action != null) {
          scriptActions.add(action);
        }
      }
    }

    // Execute all loaded actions in sequence
    for (Consequence action : scriptActions) {
      action.redo();
    }
    return scriptActions;
  }

  /** Custom string serialization for Consequences. */
  private String serialize(Consequence c) {
    if (c instanceof Consequence.ProjectParamConsequence ppc) {
      return String.format("BPM_SWING|%s|%.6f", ppc.paramName(), ppc.newValue());
    } else if (c instanceof Consequence.StepConsequence sc) {
      StepData d = sc.newData();
      return String.format(
          "STEP|%d|%d|%d|%d|%b|%.6f|%.6f|%.6f|%d|%d|%.6f|%.6f",
          sc.trackIndex(),
          sc.clipIndex(),
          sc.row(),
          sc.step(),
          d.active(),
          d.velocity(),
          d.gate(),
          d.probability(),
          d.pitch(),
          d.iterance(),
          d.fill(),
          d.nudge());
    } else if (c instanceof Consequence.SynthParamConsequence spc) {
      return String.format(
          "SYNTH_PARAM|%d|%s|%.6f", spc.trackIndex(), spc.paramName(), spc.newValue());
    } else if (c instanceof Consequence.AutomationConsequence ac) {
      return String.format(
          "AUTOMATION|%d|%d|%s|%d|%.6f",
          ac.trackIndex(), ac.clipIndex(), ac.paramName(), ac.step(), ac.newValue());
    } else if (c instanceof Consequence.TrackStructureConsequence tsc) {
      if (tsc.operation() == Consequence.TrackStructureConsequence.ADD) {
        String type = "SYNTH";
        if (tsc.trackSnapshot() instanceof KitTrackModel) type = "KIT";
        else if (tsc.trackSnapshot() instanceof AudioTrackModel) type = "AUDIO";
        return String.format(
            "TRACK_STRUCT|ADD|%d|%s|%s", tsc.index(), type, tsc.trackSnapshot().getName());
      }
    } else if (c instanceof Consequence.PatternLoadConsequence plc) {
      String slot = plc.afterSnapshot().getInstrumentSlot();
      if (slot != null && !slot.isEmpty()) {
        return String.format("LOAD_PRESET|%d|%s", plc.trackIndex(), slot);
      }
    }
    return null;
  }

  /** Custom deserialization from string to Consequences. */
  private Consequence deserialize(String line, ProjectModel project) {
    String[] parts = line.split("\\|");
    if (parts.length < 2) return null;
    String type = parts[0];

    try {
      switch (type) {
        case "BPM_SWING" -> {
          String paramName = parts[1];
          float val = Float.parseFloat(parts[2]);
          // Re-create the project parameter consequence
          float currentVal = 0.0f;
          if (paramName.equals("bpm")) currentVal = project.getBpm();
          else if (paramName.equals("swing")) currentVal = project.getSwing();
          else if (paramName.equals("reverbRoomSize")) currentVal = project.getReverbRoomSize();
          else if (paramName.equals("reverbDampening")) currentVal = project.getReverbDampening();
          return new Consequence.ProjectParamConsequence(project, paramName, currentVal, val);
        }
        case "STEP" -> {
          int trackIdx = Integer.parseInt(parts[1]);
          int clipIdx = Integer.parseInt(parts[2]);
          int row = Integer.parseInt(parts[3]);
          int step = Integer.parseInt(parts[4]);
          boolean active = Boolean.parseBoolean(parts[5]);
          float vel = Float.parseFloat(parts[6]);
          float gate = Float.parseFloat(parts[7]);
          float prob = Float.parseFloat(parts[8]);
          int pitch = Integer.parseInt(parts[9]);
          int iter = Integer.parseInt(parts[10]);
          float fill = Float.parseFloat(parts[11]);
          float nudge = Float.parseFloat(parts[12]);

          StepData newData = new StepData(active, vel, gate, prob, pitch, iter, fill, nudge);
          // Retrieve old step data if track/clip exist
          StepData oldData = StepData.empty();
          if (trackIdx < project.getTracks().size()) {
            var track = project.getTracks().get(trackIdx);
            if (clipIdx < track.getClips().size()) {
              oldData = track.getClips().get(clipIdx).getStep(row, step);
            }
          }
          return new Consequence.StepConsequence(
              project, trackIdx, clipIdx, row, step, oldData, newData);
        }
        case "SYNTH_PARAM" -> {
          int trackIdx = Integer.parseInt(parts[1]);
          String paramName = parts[2];
          float val = Float.parseFloat(parts[3]);
          float oldVal = 0.0f; // placeholder
          return new Consequence.SynthParamConsequence(
              project, trackIdx, paramName, oldVal, val, System.currentTimeMillis());
        }
        case "AUTOMATION" -> {
          int trackIdx = Integer.parseInt(parts[1]);
          int clipIdx = Integer.parseInt(parts[2]);
          String paramName = parts[3];
          int step = Integer.parseInt(parts[4]);
          float val = Float.parseFloat(parts[5]);
          float oldVal = 0.0f; // placeholder
          return new Consequence.AutomationConsequence(
              project, trackIdx, clipIdx, paramName, step, oldVal, val);
        }
        case "TRACK_STRUCT" -> {
          String opStr = parts[1];
          if (opStr.equals("ADD")) {
            int index = Integer.parseInt(parts[2]);
            String trackType = parts[3];
            String name = parts[4];
            TrackModel track = null;
            if (trackType.equals("SYNTH")) {
              var synth = new SynthTrackModel(name);
              synth.addClip(new ClipModel("CLIP 1", 8, 16));
              track = synth;
            } else if (trackType.equals("KIT")) {
              var kit = new KitTrackModel(name);
              kit.addDrum(new SoundDrum("Kick", ""));
              kit.addDrum(new SoundDrum("Snare", ""));
              kit.addDrum(new SoundDrum("Closed Hat", ""));
              kit.addDrum(new SoundDrum("Open Hat", ""));
              kit.addDrum(new SoundDrum("Clap", ""));
              kit.addDrum(new SoundDrum("Tom 1", ""));
              kit.addDrum(new SoundDrum("Tom 2", ""));
              kit.addDrum(new SoundDrum("Percussion", ""));
              kit.addClip(new ClipModel("CLIP 1", 8, 16));
              track = kit;
            } else if (trackType.equals("AUDIO")) {
              var audio = new AudioTrackModel(name);
              audio.addClip(new ClipModel("CLIP 1", 1, 16));
              track = audio;
            }
            return new Consequence.TrackStructureConsequence(
                project, Consequence.TrackStructureConsequence.ADD, index, track, "Add track");
          }
        }
        case "LOAD_PRESET" -> {
          int trackIdx = Integer.parseInt(parts[1]);
          String presetName = parts[2];
          try {
            java.io.File presetsDir = org.deluge.project.PreferencesManager.getSynthsDir();
            java.io.File presetFile = new java.io.File(presetsDir, presetName + ".XML");
            if (!presetFile.exists()) {
              presetFile = new java.io.File(presetsDir, presetName);
            }
            if (presetFile.exists()) {
              SynthTrackModel loadedSynth = org.deluge.xml.DelugeXmlParser.parseSynth(presetFile);
              if (trackIdx < project.getTracks().size()) {
                var oldTrack = project.getTracks().get(trackIdx);
                loadedSynth.getClips().clear();
                loadedSynth.getClips().addAll(oldTrack.getClips());
                loadedSynth.setName(oldTrack.getName());
                project.getTracks().set(trackIdx, loadedSynth);
              }
            } else {
              System.err.println("Preset file not found: " + presetName);
            }
          } catch (Exception ex) {
            System.err.println("Error loading preset " + presetName + ": " + ex.getMessage());
          }
          return null;
        }
      }
    } catch (Exception e) {
      System.err.println(
          "ScriptingEngine failed to parse line: " + line + " Error: " + e.getMessage());
    }
    return null;
  }
}
