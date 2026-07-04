package org.deluge.midi;

import org.deluge.BridgeContract;
import org.deluge.shadow.midi.MidiMsg;
import org.deluge.ui.SwingDelugeApp;
import org.deluge.ui.SwingGridPanel;

/**
 * Routes incoming MIDI messages (Note On/Off, CC) to the active Track/Step in the Deluge UI.
 * Supports MIDI Follow Mode: incoming Note On/Off are routed by MIDI channel to a mapped track
 * rather than always writing to {@code activeTrackIndex}.
 *
 * <h2>Follow Mode Routing</h2>
 *
 * Three follow channels (A/B/C) each pair a MIDI channel with a target track index. When follow
 * mode is enabled, Note On/Off messages target the track mapped to their MIDI channel (or fall back
 * to {@code activeTrackIndex} for unmapped channels). When disabled, all notes route to {@code
 * activeTrackIndex} (legacy behavior).
 */
public class MidiInputRouter {

  private final BridgeContract bridge;

  private boolean followModeEnabled = true;
  private int activeTrackIndex = 4; // Default to first synth track

  // Follow channel routing: index 0..15
  private final int[] followMidiChannels = new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
  private final int[] followTracks = new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};

  private static class NoteStartInfo {
    long time;
    int step;
    int track;

    NoteStartInfo(long t, int s, int tr) {
      this.time = t;
      this.step = s;
      this.track = tr;
    }
  }

  private final java.util.Map<Integer, NoteStartInfo> activeNoteStarts = new java.util.HashMap<>();
  private final java.util.Map<Integer, String> ccMappings = new java.util.HashMap<>();
  private String learningTarget = null;

  /** Lazily-loaded device definition for CC routing. Updated by MidiService. */
  private MidiDeviceDefinition currentDevice;

  public MidiInputRouter(final BridgeContract bridge) {
    this.bridge = bridge;
  }

  public void setFollowModeEnabled(boolean enabled) {
    this.followModeEnabled = enabled;
  }

  public boolean isFollowModeEnabled() {
    return followModeEnabled;
  }

  public void setActiveTrack(int trackIndex) {
    this.activeTrackIndex = trackIndex;
  }

  public void startLearning(String target) {
    this.learningTarget = target;
  }

  public int getCcForParam(String target) {
    for (java.util.Map.Entry<Integer, String> entry : ccMappings.entrySet()) {
      if (entry.getValue().equals(target)) {
        return entry.getKey();
      }
    }
    return -1;
  }

  public void clearMapping(String target) {
    ccMappings.values().removeIf(val -> val.equals(target));
  }

  public void resetToDefaults() {
    ccMappings.clear();
    ccMappings.put(7, "g_sp_volume");
    ccMappings.put(10, "g_sp_pan");
    ccMappings.put(71, "g_sp_lpf_freq");
    ccMappings.put(72, "g_sp_lpf_res");
    ccMappings.put(74, "g_sp_lpf_morph");
    ccMappings.put(75, "g_sp_hpf_freq");
    ccMappings.put(76, "g_sp_hpf_res");
    ccMappings.put(77, "g_sp_hpf_morph");
    ccMappings.put(94, "g_sp_delay_rate");
    ccMappings.put(95, "g_sp_delay_feedback");
    ccMappings.put(91, "g_sp_reverb_amount");
    ccMappings.put(80, "g_sp_eq_bass");
    ccMappings.put(81, "g_sp_eq_treble");
  }

  /** Set the device definition for CC-to-param routing. */
  public void setDeviceDefinition(MidiDeviceDefinition def) {
    this.currentDevice = def;
  }

  /** Configure a follow channel: followIndex=0..15. */
  public void setFollowChannel(int followIndex, int midiChannel, int track) {
    if (followIndex >= 0 && followIndex < 16) {
      followMidiChannels[followIndex] = midiChannel;
      followTracks[followIndex] = track;
    }
  }

  public int getFollowMidiChannel(int followIndex) {
    return followIndex >= 0 && followIndex < 16 ? followMidiChannels[followIndex] : -1;
  }

  public int getFollowTrack(int followIndex) {
    return followIndex >= 0 && followIndex < 16 ? followTracks[followIndex] : -1;
  }

  /** Resolve target track from MIDI channel when follow mode is enabled. */
  private int resolveTrackFromChannel(int midiChannel) {
    if (!followModeEnabled) return activeTrackIndex;
    for (int i = 0; i < 16; i++) {
      if (followMidiChannels[i] == midiChannel) {
        return followTracks[i];
      }
    }
    // Unmapped channel — fall back to active track
    return activeTrackIndex;
  }

  /** Called when a MIDI message is received from a hardware controller. */
  public void handleMidiMessage(MidiMsg msg) {
    int status = msg.data1 & 0xF0;
    int midiChannel = msg.data1 & 0x0F; // Extract MIDI channel from lower nibble

    // Handle CC messages (0xB0)
    if (status == 0xB0) {
      int cc = msg.data2;
      double normalized = msg.data3 / 127.0;

      // CC 89 toggles mute, CC 90 toggles solo on the resolved track channel
      if (cc == 89 || cc == 90) {
        int trackIdx = resolveTrackFromChannel(midiChannel);
        if (SwingDelugeApp.mainInstance != null) {
          org.deluge.ui.SwingGridPanel activeGrid = SwingDelugeApp.mainInstance.getActiveGridPanel();
          if (activeGrid != null && activeGrid.getProjectModel() != null) {
            int trackCount = activeGrid.getProjectModel().getTracks().size();
            if (trackIdx >= 0 && trackIdx < trackCount) {
              if (cc == 89) {
                // CC 89: toggle mute
                boolean currentMuted = activeGrid.getBridge().getMute(trackIdx);
                activeGrid.setTrackMuteWithCapture(trackIdx, !currentMuted);
              } else {
                // CC 90: toggle solo
                boolean isAlreadySolo = (activeGrid.getSoloRow() == trackIdx);
                if (isAlreadySolo) {
                  // Unsolo all
                  activeGrid.setSoloRow(-1);
                  for (int i = 0; i < trackCount; i++) {
                    activeGrid.setTrackMuteWithCapture(i, false);
                  }
                } else {
                  // Solo this track
                  activeGrid.setSoloRow(trackIdx);
                  for (int i = 0; i < trackCount; i++) {
                    activeGrid.setTrackMuteWithCapture(i, i != trackIdx);
                  }
                }
              }
              activeGrid.refresh();
            }
          }
        }
        return;
      }

      // MIDI learn mode
      if (learningTarget != null) {
        ccMappings.put(cc, learningTarget);
        learningTarget = null;
      }

      // First check device definition for this CC
      if (currentDevice != null) {
        MidiDeviceDefinition.CcMapping mapping = currentDevice.findMapping(cc);
        if (mapping != null) {
          bridge.setGlobalFloat(mapping.paramName(), (float) normalized);
          bridge.setFollowCc(midiChannel, cc, msg.data3);
          return;
        }
      }

      // Fall back to learned CC mappings
      String mapped = ccMappings.get(cc);
      if (mapped != null) {
        bridge.setGlobalFloat(mapped, (float) normalized);
        return;
      }

      // Unmapped CC: broadcast for engine-side handling
      bridge.setFollowCc(midiChannel, cc, msg.data3);
      return;
    }

    // Check if live recording mode is active in the Swing GUI
    boolean isRecording = SwingGridPanel.isLiveRecordModeActive;
    boolean isPlaying = (bridge.getGlobalInt(BridgeContract.G_PLAY) == 1L);

    if (msg.isNoteOn()) {
      int midiNote = msg.data2;
      int velocity = msg.data3;

      boolean gridMode =
          Boolean.parseBoolean(
              org.deluge.project.PreferencesManager.get("midi.grid.mode", "false"));
      if (gridMode) {
        int row = midiNote / 16;
        int col = midiNote % 16;
        if (row < 8) {
          boolean current = bridge.getStep(row, col);
          bridge.setStep(row, col, !current);
          return;
        }
      }

      // If recording is active, route through the high-level ProjectModel & ClipModel!
      if (isRecording && isPlaying && SwingDelugeApp.mainInstance != null) {
        SwingGridPanel activeGrid = SwingDelugeApp.mainInstance.getActiveGridPanel();
        if (activeGrid != null && activeGrid.getProjectModel() != null) {
          org.deluge.model.ClipModel clip = activeGrid.getEditedActiveClip();
          if (clip != null) {
            int trackIndex = activeGrid.getEditedModelTrack();
            int col = activeGrid.getCurrentPlayheadStep();
            if (col >= 0) {
              col = col % clip.getStepCount();
              int row = -1;
              int pitch = 0;

              org.deluge.model.TrackModel activeTrack =
                  activeGrid.getProjectModel().getTracks().get(trackIndex);
              if (activeTrack instanceof org.deluge.model.KitTrackModel) {
                // Kit track: map note to row index (0-7)
                row = midiNote - 36;
              } else {
                // Synth track: map note to piano roll row index (127 - midiNote)
                row = 127 - midiNote;
                pitch = midiNote - 60;
              }

              if (row >= 0) {
                int clipRow = activeGrid.getClipRowIndex(clip, row, true);
                org.deluge.model.StepData oldStep = clip.getStep(clipRow, col);
                org.deluge.model.StepData newStep =
                    org.deluge.model.StepData.of(
                        true, (float) (velocity / 127.0), 1.0f, 1.0f, pitch);
                activeGrid.setClipStep(clip, row, col, newStep);

                // Push StepConsequence to the undo/redo stack & trigger macro recording!
                activeGrid
                    .getProjectModel()
                    .getUndoRedoStack()
                    .push(
                        new org.deluge.model.Consequence.StepConsequence(
                            activeGrid.getProjectModel(),
                            trackIndex,
                            activeGrid.getActiveClipId(),
                            clipRow,
                            col,
                            oldStep,
                            newStep));

                // Update low-level ChucK bridge
                int engineRow = activeGrid.getBaseTrackId() + clipRow;
                bridge.setStep(engineRow, col, true);
                bridge.setVelocity(engineRow, col, (float) (velocity / 127.0));
                bridge.setGate(engineRow, col, 1.0);
                if (trackIndex >= 4) {
                  bridge.setPitch(engineRow, col, pitch);
                }

                // Store start time and step for Note Off gate calculation
                activeNoteStarts.put(
                    midiNote, new NoteStartInfo(bridge.getCurrentTime(), col, trackIndex));

                // Refresh UI grid
                javax.swing.SwingUtilities.invokeLater(() -> activeGrid.refresh());
                return;
              }
            }
          }
        }
      }

      // Fallback: live auditioning if recording is off or no active grid
      int targetTrack = resolveTrackFromChannel(midiChannel);
      org.deluge.engine.FirmwareSound liveSound = getLiveSound(targetTrack);
      if (liveSound != null) {
        liveSound.triggerNote(midiNote, velocity);
      }
      activeNoteStarts.put(midiNote, new NoteStartInfo(bridge.getCurrentTime(), -1, targetTrack));

    } else if (msg.isNoteOff()) {
      int midiNote = msg.data2;
      NoteStartInfo start = activeNoteStarts.remove(midiNote);
      if (start != null) {
        long duration = bridge.getCurrentTime() - start.time;
        double bpm =
            SwingDelugeApp.mainInstance != null
                ? SwingDelugeApp.mainInstance.getCurrentProject().getBpm()
                : 120.0;
        double gate = (double) duration / (bridge.getSampleRate() * (15.0 / bpm));

        // If recording is active, update the gate length in the high-level model!
        if (isRecording && isPlaying && SwingDelugeApp.mainInstance != null) {
          SwingGridPanel activeGrid = SwingDelugeApp.mainInstance.getActiveGridPanel();
          if (activeGrid != null) {
            org.deluge.model.ClipModel clip = activeGrid.getEditedActiveClip();
            if (clip != null && start.step >= 0 && start.step < clip.getStepCount()) {
              int trackIndex = activeGrid.getEditedModelTrack();
              int row = -1;
              org.deluge.model.TrackModel activeTrack =
                  activeGrid.getProjectModel().getTracks().get(trackIndex);
              if (activeTrack instanceof org.deluge.model.KitTrackModel) {
                row = midiNote - 36;
              } else {
                row = 127 - midiNote;
              }
              if (row >= 0) {
                int clipRow = activeGrid.getClipRowIndex(clip, row, false);
                if (clipRow >= 0) {
                  org.deluge.model.StepData oldStep = clip.getStep(clipRow, start.step);
                  if (oldStep != null && oldStep.active()) {
                    org.deluge.model.StepData newStep =
                        new org.deluge.model.StepData(
                            true,
                            oldStep.velocity(),
                            (float) gate,
                            oldStep.probability(),
                            oldStep.pitch(),
                            oldStep.iterance(),
                            oldStep.fill());
                    activeGrid.setClipStep(clip, row, start.step, newStep);

                    // Update low-level ChucK bridge gate
                    int engineRow = activeGrid.getBaseTrackId() + clipRow;
                    bridge.setGate(engineRow, start.step, gate);

                    // Refresh UI grid
                    javax.swing.SwingUtilities.invokeLater(() -> activeGrid.refresh());
                  }
                }
              }
            }
          }
        } else {
          // Auditioning fallback: release the note live
          org.deluge.engine.FirmwareSound liveSound = getLiveSound(start.track);
          if (liveSound != null) {
            liveSound.releaseNote(midiNote);
          }
        }
      }
    }
  }

  private org.deluge.engine.FirmwareSound getLiveSound(int trackIndex) {
    try {
      Object eng = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
      if (eng instanceof org.deluge.engine.FirmwareAudioEngine engine) {
        if (trackIndex >= 0 && trackIndex < engine.sounds.size()) {
          var sound = engine.sounds.get(trackIndex);
          if (sound instanceof org.deluge.engine.FirmwareSound) {
            return (org.deluge.engine.FirmwareSound) sound;
          }
        }
      }
    } catch (Exception ignored) {
    }
    return null;
  }
}
