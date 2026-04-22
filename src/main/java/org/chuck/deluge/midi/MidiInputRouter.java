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

  /** Called when a MIDI message is received from a hardware controller. */
  public void handleMidiMessage(MidiMsg msg) {
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

      // Set step as active (pattern = 1)
      bridge.setStep(activeTrackIndex, currentStep, true);

      // Set velocity (0.0 - 1.0)
      bridge.setVelocity(activeTrackIndex, currentStep, velocity / 127.0);

      // Keep gate open
      bridge.setGate(activeTrackIndex, currentStep, 1.0);

    } else if (msg.isNoteOff()) {
      // In a full implementation we'd calculate the duration since NoteOn to set the precise Gate
      // length.
      // For MVP, we just decrement the gate slightly to simulate a release.
      bridge.setGate(activeTrackIndex, currentStep, 0.5);
    }
  }
}
