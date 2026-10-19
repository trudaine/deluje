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
  private final int[] followMidiChannels =
      new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
  private final int[] followTracks =
      new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};

  private static class NoteStartInfo {
    final long time;
    final int step;
    final int track;
    final int clipId;
    final int clipRow;

    NoteStartInfo(long t, int s, int tr) {
      this(t, s, tr, -1, -1);
    }

    NoteStartInfo(long t, int s, int tr, int clipId, int clipRow) {
      this.time = t;
      this.step = s;
      this.track = tr;
      this.clipId = clipId;
      this.clipRow = clipRow;
    }
  }

  private static int noteKey(int midiChannel, int midiNote) {
    return ((midiChannel & 0x0F) << 8) | (midiNote & 0x7F);
  }

  private final java.util.Map<Integer, NoteStartInfo> activeNoteStarts = new java.util.HashMap<>();
  // C midi_device.h:79, playback_handler.cpp:3176-3297 — per-MPE-channel initial strike expression
  // values (signed 16-bit in int[16][3]) sent prior to NoteOn.
  private final int[][] defaultInputMpeValues = new int[16][3];

  public void setChannelDefaultMpeValue(int midiChannel, int dimension, short value16) {
    if (midiChannel >= 0 && midiChannel < 16 && dimension >= 0 && dimension < 3) {
      defaultInputMpeValues[midiChannel][dimension] = value16;
    }
  }

  public int[] getChannelDefaultMpeValues(int midiChannel) {
    return (midiChannel >= 0 && midiChannel < 16) ? defaultInputMpeValues[midiChannel] : null;
  }

  // C mod_controllable_audio.cpp:1506-1532 — one CC can drive multiple params and one param can
  // hold multiple learned CCs until explicit unlearnKnobs (clearMapping).
  private final java.util.Map<Integer, java.util.Set<String>> ccMappings =
      new java.util.HashMap<>();
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
    int lastCc = -1;
    for (java.util.Map.Entry<Integer, java.util.Set<String>> entry : ccMappings.entrySet()) {
      if (entry.getValue().contains(target)) {
        lastCc = entry.getKey();
      }
    }
    return lastCc;
  }

  private void bindCcToParam(int cc, String target) {
    ccMappings.computeIfAbsent(cc, k -> new java.util.LinkedHashSet<>()).add(target);
  }

  public void clearMapping(String target) {
    // C mod_controllable_audio.cpp:1535-1540 (unlearnKnobs) — removes all learned CCs for target.
    ccMappings.values().forEach(set -> set.remove(target));
    ccMappings.values().removeIf(java.util.Set::isEmpty);
  }

  public void resetToDefaults() {
    ccMappings.clear();
    bindCcToParam(7, "g_sp_volume");
    bindCcToParam(10, "g_sp_pan");
    bindCcToParam(71, "g_sp_lpf_freq");
    bindCcToParam(72, "g_sp_lpf_res");
    bindCcToParam(74, "g_sp_lpf_morph");
    bindCcToParam(75, "g_sp_hpf_freq");
    bindCcToParam(76, "g_sp_hpf_res");
    bindCcToParam(77, "g_sp_hpf_morph");
    bindCcToParam(94, "g_sp_delay_rate");
    bindCcToParam(95, "g_sp_delay_feedback");
    bindCcToParam(91, "g_sp_reverb_amount");
    bindCcToParam(80, "g_sp_eq_bass");
    bindCcToParam(81, "g_sp_eq_treble");
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
    int rawStatus = (msg.status != 0) ? msg.status : msg.data1;
    int status = rawStatus & 0xF0;
    int midiChannel =
        (msg.status != 0 && msg.channel != 0) ? (msg.channel & 0x0F) : (rawStatus & 0x0F);

    // Handle CC messages (0xB0)
    if (status == 0xB0) {
      int cc = msg.data2;
      double normalized = msg.data3 / 127.0;

      // CC 89 toggles mute, CC 90 toggles solo on the resolved track channel
      if (cc == 89 || cc == 90) {
        int trackIdx = resolveTrackFromChannel(midiChannel);
        if (SwingDelugeApp.mainInstance != null) {
          org.deluge.ui.SwingGridPanel activeGrid =
              SwingDelugeApp.mainInstance.getActiveGridPanel();
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

      // MIDI learn mode (C mod_controllable_audio.cpp:1506-1532: additive until explicit unlearn)
      if (learningTarget != null) {
        bindCcToParam(cc, learningTarget);
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

      // Fall back to learned CC mappings (one CC may drive multiple learned params)
      java.util.Set<String> mapped = ccMappings.get(cc);
      if (mapped != null && !mapped.isEmpty()) {
        for (String paramName : mapped) {
          bridge.setGlobalFloat(paramName, (float) normalized);
        }
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
              if (!(activeTrack instanceof org.deluge.model.SynthTrackModel
                  || activeTrack instanceof org.deluge.model.KitTrackModel)) {
                return; // Upstream C++ commit c8a9dc6f (#4708): ignore audio & non-instrument
                // tracks!
              }
              int clipRow;
              if (activeTrack instanceof org.deluge.model.KitTrackModel) {
                // Kit track: map note to drum row index (36 = drum 0)
                int drumRow = midiNote - 36;
                if (drumRow < 0) {
                  return;
                }
                clipRow = activeGrid.getClipRowIndex(clip, drumRow, true);
                pitch = drumRow;
              } else {
                // Synth track: resolve stored clip row directly by MIDI pitch
                clipRow = activeGrid.getOrCreateSynthClipRowForPitch(clip, midiNote);
                pitch = midiNote;
              }

              int engineRow = activeGrid.getEngineRowForStoredClipRow(clipRow);
              if (clipRow < 0 || engineRow < 0) {
                triggerLiveNote(trackIndex, midiNote, velocity, midiChannel);
                activeNoteStarts.put(
                    noteKey(midiChannel, midiNote),
                    new NoteStartInfo(bridge.getCurrentTime(), -1, trackIndex));
                return;
              }

              org.deluge.model.StepData oldStep = clip.getStep(clipRow, col);
              org.deluge.model.StepData newStep =
                  org.deluge.model.StepData.of(true, (float) (velocity / 127.0), 1.0f, 1.0f, pitch);
              clip.setStep(clipRow, col, newStep);

              int clipId = activeGrid.getActiveClipId();
              // Push StepConsequence to the undo/redo stack & trigger macro recording!
              activeGrid
                  .getProjectModel()
                  .getUndoRedoStack()
                  .push(
                      new org.deluge.model.Consequence.StepConsequence(
                          activeGrid.getProjectModel(),
                          trackIndex,
                          clipId,
                          clipRow,
                          col,
                          oldStep,
                          newStep));

              // Update low-level ChucK bridge
              bridge.setStep(engineRow, col, true);
              bridge.setVelocity(engineRow, col, (float) (velocity / 127.0));
              bridge.setGate(engineRow, col, 1.0);
              bridge.setPitch(engineRow, col, pitch);

              // Monitor the recorded note immediately so playback does not wait for the next loop
              triggerLiveNote(trackIndex, midiNote, velocity, midiChannel);

              // Store start time, step, clipId, and clipRow for Note Off gate calculation
              activeNoteStarts.put(
                  noteKey(midiChannel, midiNote),
                  new NoteStartInfo(bridge.getCurrentTime(), col, trackIndex, clipId, clipRow));

              // Refresh UI grid
              javax.swing.SwingUtilities.invokeLater(() -> activeGrid.refresh());
              return;
            }
          }
        }
      }

      // Fallback: live auditioning if recording is off or no active grid
      int targetTrack = resolveTrackFromChannel(midiChannel);
      triggerLiveNote(targetTrack, midiNote, velocity, midiChannel);
      activeNoteStarts.put(
          noteKey(midiChannel, midiNote),
          new NoteStartInfo(bridge.getCurrentTime(), -1, targetTrack));

    } else if (msg.isNoteOff()) {
      int midiNote = msg.data2;
      NoteStartInfo start = activeNoteStarts.remove(noteKey(midiChannel, midiNote));
      if (start != null) {
        long duration = bridge.getCurrentTime() - start.time;
        double bpm =
            SwingDelugeApp.mainInstance != null
                ? SwingDelugeApp.mainInstance.getCurrentProject().getBpm()
                : 120.0;
        double gate = (double) duration / (bridge.getSampleRate() * (15.0 / bpm));

        // If recording is active, update the gate length in the exact clip/row recorded on Note On!
        if (isRecording && isPlaying && start.step >= 0 && SwingDelugeApp.mainInstance != null) {
          SwingGridPanel activeGrid = SwingDelugeApp.mainInstance.getActiveGridPanel();
          if (activeGrid != null && activeGrid.getProjectModel() != null) {
            org.deluge.model.ProjectModel project = activeGrid.getProjectModel();
            if (start.track >= 0 && start.track < project.getTracks().size()) {
              org.deluge.model.TrackModel recordedTrack = project.getTracks().get(start.track);
              if ((recordedTrack instanceof org.deluge.model.SynthTrackModel
                      || recordedTrack instanceof org.deluge.model.KitTrackModel)
                  && start.clipId >= 0
                  && start.clipId < recordedTrack.getClips().size()) {
                org.deluge.model.ClipModel clip = recordedTrack.getClips().get(start.clipId);
                if (clip != null
                    && start.step < clip.getStepCount()
                    && start.clipRow >= 0
                    && start.clipRow < clip.getRowCount()) {
                  int clipRow = start.clipRow;
                  org.deluge.model.StepData oldStep = clip.getStep(clipRow, start.step);
                  if (oldStep != null && oldStep.active()) {
                    org.deluge.model.StepData newStep = oldStep.withGate((float) gate);
                    clip.setStep(clipRow, start.step, newStep);

                    org.deluge.model.UndoRedoStack undoStack = project.getUndoRedoStack();
                    if (undoStack.peekUndo()
                            instanceof org.deluge.model.Consequence.StepConsequence sc
                        && sc.trackIndex() == start.track
                        && sc.clipIndex() == start.clipId
                        && sc.row() == clipRow
                        && sc.step() == start.step) {
                      undoStack.replaceLast(
                          new org.deluge.model.Consequence.StepConsequence(
                              sc.project(),
                              sc.trackIndex(),
                              sc.clipIndex(),
                              sc.row(),
                              sc.step(),
                              sc.oldData(),
                              newStep));
                    }

                    // Update low-level ChucK bridge gate if this track/clip is still the edited one
                    if (activeGrid.getEditedModelTrack() == start.track
                        && activeGrid.getActiveClipId() == start.clipId) {
                      int engineRow = activeGrid.getEngineRowForStoredClipRow(clipRow);
                      if (engineRow >= 0) {
                        bridge.setGate(engineRow, start.step, gate);
                      }
                    }

                    // Refresh UI grid
                    javax.swing.SwingUtilities.invokeLater(() -> activeGrid.refresh());
                  }
                }
              }
            }
          }
        }
        // Always release any monitored live note
        releaseLiveNote(start.track, midiNote, midiChannel);
      }
    }
  }

  org.deluge.firmware2.GlobalEffectable resolveLiveTrackTarget(int trackIndex) {
    Object eng = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
    if (eng instanceof org.deluge.engine.FirmwareAudioEngine engine
        && trackIndex >= 0
        && trackIndex < engine.sounds.size()) {
      return engine.sounds.get(trackIndex);
    }
    Object ph = bridge.getGlobalObject(BridgeContract.G_PLAYBACK_HANDLER);
    if (ph instanceof org.deluge.playback.PlaybackHandler playbackHandler) {
      org.deluge.model.ProjectModel project = playbackHandler.getProject();
      if (project != null && trackIndex >= 0 && trackIndex < project.getTracks().size()) {
        org.deluge.model.ClipModel clip = project.getTracks().get(trackIndex).getActiveClip();
        if (clip != null && clip.getSound() instanceof org.deluge.firmware2.GlobalEffectable ge) {
          return ge;
        }
      }
    }
    return null;
  }

  private void triggerLiveNote(int trackIndex, int midiNote, int velocity) {
    triggerLiveNote(trackIndex, midiNote, velocity, -1);
  }

  // C voice.cpp:118, melodic_instrument.cpp:106, kit.cpp:1504-1521, 1549, 1954-1960 — propagate
  // live MIDI/MPE member channel + pre-NoteOn initial strike MPE values to melodic voices and
  // auditioned drums.
  private void triggerLiveNote(int trackIndex, int midiNote, int velocity, int midiChannel) {
    try {
      var sound = resolveLiveTrackTarget(trackIndex);
      int[] channelMpe = (midiChannel > 0) ? getChannelDefaultMpeValues(midiChannel) : null;
      if (sound instanceof org.deluge.engine.FirmwareSound liveSound) {
        liveSound.triggerWithLiveMidiChannel(
            midiChannel, channelMpe, () -> liveSound.triggerNote(midiNote, velocity));
      } else if (sound instanceof org.deluge.engine.FirmwareKit liveKit) {
        int drumRow = midiNote - 36;
        if (!liveKit.muted && drumRow >= 0 && drumRow < liveKit.drumSounds.size()) {
          liveKit
              .drumSounds
              .get(drumRow)
              .triggerWithLiveMidiChannel(
                  midiChannel, channelMpe, () -> liveKit.triggerDrum(drumRow, velocity));
        }
      } else if (sound instanceof org.deluge.firmware2.Sound rawSound) {
        rawSound.triggerNote(midiNote, velocity, midiChannel, channelMpe);
      }
    } catch (Exception ignored) {
    }
  }

  private void releaseLiveNote(int trackIndex, int midiNote) {
    releaseLiveNote(trackIndex, midiNote, -1);
  }

  // C sound.cpp:1581-1602, kit.cpp:1970 — release live MIDI/MPE note and unlatch auditioned drum
  // MIDI channel on NoteOff.
  private void releaseLiveNote(int trackIndex, int midiNote, int midiChannel) {
    try {
      var sound = resolveLiveTrackTarget(trackIndex);
      if (sound instanceof org.deluge.engine.FirmwareSound liveSound) {
        liveSound.releaseNote(midiNote, midiChannel);
      } else if (sound instanceof org.deluge.engine.FirmwareKit liveKit) {
        int drumRow = midiNote - 36;
        if (drumRow >= 0 && drumRow < liveKit.drumSounds.size()) {
          liveKit
              .drumSounds
              .get(drumRow)
              .releaseWithLiveMidiChannel(midiChannel, () -> liveKit.releaseDrum(drumRow));
        }
      } else if (sound instanceof org.deluge.firmware2.Sound rawSound) {
        rawSound.releaseNote(midiNote, midiChannel);
      }
    } catch (Exception ignored) {
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
