package org.deluge.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Pins the undo/redo round-trip for a bulk synth randomize (parameters + arp + name). */
class SynthRandomizeConsequenceTest {

  @Test
  void undoRedoRestoresParamsArpAndName() {
    ProjectModel project = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("Original");
    synth.setLpfFreq(5000.0f);
    synth.setVolume(0.4f);
    synth.setArp(synth.getArp().toBuilder().rate(0.2f).build());
    project.addTrack(synth);

    SynthTrackModel before = Consequence.SynthRandomizeConsequence.snapshot(synth);

    // Simulate a randomize: change params, arp, and name.
    synth.setLpfFreq(12000.0f);
    synth.setVolume(0.9f);
    synth.setArp(synth.getArp().toBuilder().rate(0.8f).build());
    synth.setName("Random_X");

    SynthTrackModel after = Consequence.SynthRandomizeConsequence.snapshot(synth);
    Consequence.SynthRandomizeConsequence cons =
        new Consequence.SynthRandomizeConsequence(project, 0, before, after);

    cons.undo();
    SynthTrackModel live = (SynthTrackModel) project.getTracks().get(0);
    assertEquals(5000.0f, live.getLpfFreq(), 1e-3, "param restored");
    assertEquals(0.4f, live.getVolume(), 1e-3);
    assertEquals(0.2f, live.getArp().rate(), 1e-3, "arp restored");
    assertEquals("Original", live.getName(), "name restored");

    cons.redo();
    assertEquals(12000.0f, live.getLpfFreq(), 1e-3);
    assertEquals(0.8f, live.getArp().rate(), 1e-3);
    assertEquals("Random_X", live.getName());
  }
}
