package org.chuck.deluge.midi;

import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.midi.MidiMsg;

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

  private final ChuckVM vm;
  private final BridgeContract bridge;

  private boolean followModeEnabled = true;
  private int activeTrackIndex = 4; // Default to first synth track

  // Follow channel routing: index 0=A, 1=B, 2=C
  private final int[] followMidiChannels = new int[] {0, 1, 2};
  private final int[] followTracks = new int[] {0, 1, 2};

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

  /** Lazily-loaded device definition for CC routing. Updated by MidiService. */
  private MidiDeviceDefinition currentDevice;

  public MidiInputRouter(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
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

  /** Set the device definition for CC-to-param routing. */
  public void setDeviceDefinition(MidiDeviceDefinition def) {
    this.currentDevice = def;
  }

  /** Configure a follow channel: followIndex=0(A),1(B),2(C). */
  public void setFollowChannel(int followIndex, int midiChannel, int track) {
    if (followIndex >= 0 && followIndex < 3) {
      followMidiChannels[followIndex] = midiChannel;
      followTracks[followIndex] = track;
    }
  }

  public int getFollowMidiChannel(int followIndex) {
    return followIndex >= 0 && followIndex < 3 ? followMidiChannels[followIndex] : -1;
  }

  public int getFollowTrack(int followIndex) {
    return followIndex >= 0 && followIndex < 3 ? followTracks[followIndex] : -1;
  }

  /** Resolve target track from MIDI channel when follow mode is enabled. */
  private int resolveTrackFromChannel(int midiChannel) {
    if (!followModeEnabled) return activeTrackIndex;
    for (int i = 0; i < 3; i++) {
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

      // MIDI learn mode
      if (learningTarget != null) {
        ccMappings.put(cc, learningTarget);
        learningTarget = null;
      }

      // First check device definition for this CC
      if (currentDevice != null) {
        MidiDeviceDefinition.CcMapping mapping = currentDevice.findMapping(cc);
        if (mapping != null) {
          vm.setGlobalFloat(mapping.paramName(), (float) normalized);
          bridge.setFollowCc(midiChannel, cc, msg.data3);
          return;
        }
      }

      // Fall back to learned CC mappings
      String mapped = ccMappings.get(cc);
      if (mapped != null) {
        vm.setGlobalFloat(mapped, (float) normalized);
        return;
      }

      // Unmapped CC: broadcast for engine-side handling
      bridge.setFollowCc(midiChannel, cc, msg.data3);
      return;
    }

    if (!followModeEnabled) return;

    // Get the current playback step from the VM
    int currentStep = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
    if (currentStep < 0 || currentStep >= 16) {
      currentStep = 0;
    }

    // Resolve target track from MIDI channel (follow mode aware)
    int targetTrack = resolveTrackFromChannel(midiChannel);

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
          return;
        }
      }

      // Store start time and step
      activeNoteStarts.put(midiNote, new NoteStartInfo(vm.getCurrentTime(), currentStep));

      if (targetTrack < 4) {
        // Kit track: map note to row (e.g., 36 -> row 0, 38 -> row 1, etc.)
        int row = midiNote - 36;
        if (row >= 0 && row < 8) {
          bridge.setStep(targetTrack, currentStep, true);
          bridge.setVelocity(targetTrack, currentStep, velocity / 127.0);
          bridge.setGate(targetTrack, currentStep, 1.0);
        }
      } else {
        // Synth track: map note to pitch offset from middle C (60)
        bridge.setPitch(targetTrack, currentStep, midiNote - 60);
        bridge.setStep(targetTrack, currentStep, true);
        bridge.setVelocity(targetTrack, currentStep, velocity / 127.0);
        bridge.setGate(targetTrack, currentStep, 1.0);
      }

    } else if (msg.isNoteOff()) {
      int midiNote = msg.data2;
      NoteStartInfo start = activeNoteStarts.remove(midiNote);
      if (start != null) {
        long duration = vm.getCurrentTime() - start.time;
        double gate = (double) duration / (vm.getSampleRate() * 0.125);
        bridge.setGate(targetTrack, start.step, gate);
      }
    }
  }
}
