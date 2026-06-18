package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.chuck.core.ChuckVM;
import org.deluge.BridgeContract;
import org.deluge.model.ClipModel;
import org.deluge.model.MidiTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/** Verifies Synth<->MIDI track type conversion preserves clips and is type-guarded. */
public class ConvertTrackTypeTest {

  private SwingGridPanel panel(ProjectModel project) throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);
    SwingGridPanel p = new SwingGridPanel(vm, bridge);
    p.setProjectModel(project);
    return p;
  }

  @Test
  public void synthConvertsToMidiKeepingClips() throws Exception {
    ProjectModel proj = new ProjectModel();
    SynthTrackModel st = new SynthTrackModel("Lead");
    st.addClip(new ClipModel("C1", 8, 16));
    proj.addTrack(st);

    SwingGridPanel p = panel(proj);
    assertTrue(p.convertTrackToMidi(0));
    assertInstanceOf(MidiTrackModel.class, proj.getTracks().get(0));
    assertTrue(proj.getTracks().get(0).getClips().size() == 1, "clips preserved");
    assertTrue(proj.getTracks().get(0).getName().equals("Lead"), "name preserved");
  }

  @Test
  public void midiConvertsBackToSynth() throws Exception {
    ProjectModel proj = new ProjectModel();
    MidiTrackModel mt = new MidiTrackModel("Pad");
    mt.addClip(new ClipModel("C1", 8, 16));
    proj.addTrack(mt);

    SwingGridPanel p = panel(proj);
    assertTrue(p.convertTrackToSynth(0));
    assertInstanceOf(SynthTrackModel.class, proj.getTracks().get(0));
  }

  @Test
  public void conversionIsTypeGuarded() throws Exception {
    ProjectModel proj = new ProjectModel();
    proj.addTrack(new SynthTrackModel("Lead"));
    SwingGridPanel p = panel(proj);
    assertFalse(p.convertTrackToSynth(0), "already a synth -> no-op");
    assertFalse(p.convertTrackToMidi(5), "out of range -> no-op");
  }
}
