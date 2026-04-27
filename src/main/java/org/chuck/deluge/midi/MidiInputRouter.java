package org.chuck.deluge.midi;

import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.midi.MidiMsg;

/**
 * Routes incoming MIDI messages (Note On/Off, CC) to the active Track/Step in the Deluge UI.
 * Simulates the "MIDI Follow Mode" where external controllers target the selected track.
 */
public class MidiInputRouter {

  private final ChuckVM vm;
  private final BridgeContract bridge;

  private boolean followModeEnabled = true;
  private int activeTrackIndex = 4; // Default to first synth track

  private static class NoteStartInfo {
    long time;
    int step;

    NoteStartInfo(long t, int s) {
      this.time = t;
      this.step = s;
    }
  }

  private final java.util.Map<Integer, NoteStartInfo> activeNoteStarts = new java.util.HashMap<>();
  private final java.util.Map<Integer, String> ccMappings = new java.util.HashMap<>();
  private String learningTarget = null;

  public MidiInputRouter(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;
  }

  public void setFollowModeEnabled(boolean enabled) {
    this.followModeEnabled = enabled;
  }

  public void setActiveTrack(int trackIndex) {
    this.activeTrackIndex = trackIndex;
  }

  public void startLearning(String target) {
    this.learningTarget = target;
  }

  /** Called when a MIDI message is received from a hardware controller. */
  public void handleMidiMessage(MidiMsg msg) {
    // Handle CC messages for MIDI learn and mapped parameters
    if ((msg.data1 & 0xF0) == 0xB0) {
      int cc = msg.data2;
      double normalized = msg.data3 / 127.0;
      if (learningTarget != null) {
        ccMappings.put(cc, learningTarget);
        learningTarget = null;
      }
      String mapped = ccMappings.get(cc);
      if (mapped != null) {
        vm.setGlobalFloat(mapped, normalized);
        return;
      }
    }
    if (!followModeEnabled) return;

    // Get the current playback step from the VM
    int currentStep = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
    if (currentStep < 0 || currentStep >= 16) {
      // Not playing, or invalid step; default to step 0 for previewing
      currentStep = 0;
    }

    if (msg.isNoteOn()) {
      int midiNote = msg.data2;
      int velocity = msg.data3;

      boolean gridMode =
          Boolean.parseBoolean(
              org.chuck.deluge.project.PreferencesManager.get("midi.grid.mode", "false"));
      if (gridMode) {
        int row = midiNote / 16;
        int col = midiNote % 16;
        if (row < 8) {
          boolean current = bridge.getStep(row, col);
          bridge.setStep(row, col, !current);
          return; // Do not play the note
        }
      }

      // Store start time and step
      activeNoteStarts.put(midiNote, new NoteStartInfo(vm.getCurrentTime(), currentStep));

      if (activeTrackIndex < 4) {
        // Kit track: map note to row (e.g., 36 -> row 0, 38 -> row 1, etc.)
        int row = midiNote - 36;
        if (row >= 0 && row < 8) {
          bridge.setStep(activeTrackIndex, currentStep, true);
          bridge.setVelocity(activeTrackIndex, currentStep, velocity / 127.0);
          bridge.setGate(activeTrackIndex, currentStep, 1.0);
        }
      } else {
        // Synth track: map note to pitch offset from middle C (60)
        bridge.setPitch(activeTrackIndex, currentStep, midiNote - 60);
        bridge.setStep(activeTrackIndex, currentStep, true);
        bridge.setVelocity(activeTrackIndex, currentStep, velocity / 127.0);
        bridge.setGate(activeTrackIndex, currentStep, 1.0);
      }

    } else if (msg.isNoteOff()) {
      int midiNote = msg.data2;
      NoteStartInfo start = activeNoteStarts.remove(midiNote);
      if (start != null) {
        long duration = vm.getCurrentTime() - start.time;
        // Convert duration to gate length
        double gate = (double) duration / (vm.getSampleRate() * 0.125);
        bridge.setGate(activeTrackIndex, start.step, gate);
      }
    }
  }
}
