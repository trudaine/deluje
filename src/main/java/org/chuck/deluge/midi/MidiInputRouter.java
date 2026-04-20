package org.chuck.deluge.midi;

import java.util.HashMap;
import java.util.Map;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.midi.MidiMsg;

/**
 * Routes incoming MIDI messages (Note On/Off, CC) to the active Track/Step or learned global parameters.
 */
public class MidiInputRouter {

  private final ChuckVM vm;
  private final BridgeContract bridge;

  private boolean followModeEnabled = true;
  private int activeTrackIndex = 4; // Default to first synth track

  // MIDI Learning
  private String learningTarget = null;
  private final Map<Integer, String> ccMappings = new HashMap<>();

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

  public int getActiveTrackIndex() {
    return activeTrackIndex;
  }

  /** Enter learning mode for the next received CC. */
  public void startLearning(String globalName) {
    this.learningTarget = globalName;
  }

  /** Called when a MIDI message is received from a hardware controller. */
  public void handleMidiMessage(MidiMsg msg) {
    int status = msg.data1 & 0xF0;

    if (status == 0xB0) { // Control Change
      handleCC(msg.data2, msg.data3);
      return;
    }

    if (!followModeEnabled) return;

    if (msg.isNoteOn()) {
      handleNoteOn(msg.data2, msg.data3);
    } else if (msg.isNoteOff()) {
      handleNoteOff(msg.data2);
    }
  }

  private void handleCC(int ccNum, int value) {
    // 1. Learning Mode
    if (learningTarget != null) {
      ccMappings.put(ccNum, learningTarget);
      System.out.println("MIDI LEARN: CC " + ccNum + " -> " + learningTarget);
      learningTarget = null;
      return;
    }

    // 2. Applied Mappings
    String target = ccMappings.get(ccNum);
    if (target != null) {
      double normalized = value / 127.0;
      // Update global in VM
      if (target.startsWith("g_step_") || target.startsWith("g_track_")) {
          // Todo: handle array updates via bridge if needed
          // For now, assume it's a global scalar like master vol or filter
      } else {
          vm.setGlobalFloat(target, normalized);
      }
    }
  }

  private void handleNoteOn(int midiNote, int velocity) {
    // 1. Update Sequencer
    int currentStep = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
    if (currentStep < 0) currentStep = 0;

    bridge.setPitch(activeTrackIndex, currentStep, midiNote - 60);
    bridge.setStep(activeTrackIndex, currentStep, true);
    bridge.setVelocity(activeTrackIndex, currentStep, velocity / 127.0);
    bridge.setGate(activeTrackIndex, currentStep, 1.0);

    // 2. Trigger direct "live" note in engine.ck
    vm.setGlobalInt("g_midi_note", (long) midiNote);
    vm.setGlobalInt("g_midi_vel", (long) velocity);
    bridge.triggerMidiNoteOn();
  }

  private void handleNoteOff(int midiNote) {
    // 1. Update Sequencer
    int currentStep = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
    if (currentStep < 0) currentStep = 0;
    bridge.setGate(activeTrackIndex, currentStep, 0.5);

    // 2. Trigger live release
    vm.setGlobalInt("g_midi_note", (long) midiNote);
    vm.setGlobalInt("g_midi_vel", 0L);
    bridge.triggerMidiNoteOff();
  }
}
