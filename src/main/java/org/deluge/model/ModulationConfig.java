package org.deluge.model;

import java.util.ArrayList;
import java.util.List;

/** Encapsulates the patch cables and modulation knob mappings for a synthesizer track. */
public class ModulationConfig {
  private final List<PatchCable> patchCables = new ArrayList<>();
  private final List<ModKnob> modKnobs = new ArrayList<>(16);
  private final List<MidiKnob> midiKnobs = new ArrayList<>();

  public ModulationConfig() {
    for (int i = 0; i < 16; i++) {
      modKnobs.add(ModKnob.empty());
    }
  }

  public List<PatchCable> getPatchCables() {
    return patchCables;
  }

  public List<ModKnob> getModKnobs() {
    return modKnobs;
  }

  public List<MidiKnob> getMidiKnobs() {
    return midiKnobs;
  }

  public void addPatchCable(PatchCable cable) {
    this.patchCables.add(cable);
  }

  public void setModKnob(int index, ModKnob knob) {
    this.modKnobs.set(index, knob);
  }

  public void addMidiKnob(MidiKnob knob) {
    this.midiKnobs.add(knob);
  }

  public void clearMidiKnobs() {
    this.midiKnobs.clear();
  }

  public void copyFrom(ModulationConfig other) {
    this.patchCables.clear();
    this.patchCables.addAll(other.patchCables);
    for (int i = 0; i < 16; i++) {
      this.modKnobs.set(i, other.modKnobs.get(i));
    }
    this.midiKnobs.clear();
    this.midiKnobs.addAll(other.midiKnobs);
  }
}
