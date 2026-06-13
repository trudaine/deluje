package org.chuck.deluge.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.ProjectModel;
import org.junit.jupiter.api.Test;

/**
 * Guards the scale-name naming consistency that SwingDelugeApp.cycleScale relies on: the canonical
 * cycle names (which parseScaleIndex understands) must also be recognised by the grid's scale
 * colouring — i.e. they must NOT silently fall back to the Major mask.
 */
public class ScaleNameConsistencyTest {

  private SwingGridPanel panelWithScale(String scale) throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);
    ProjectModel project = new ProjectModel();
    project.setKey("C");
    project.setScale(scale);
    SwingGridPanel panel = new SwingGridPanel(vm, bridge);
    panel.setProjectModel(project);
    return panel;
  }

  @Test
  public void canonicalNamesResolveToTheirRealScaleNotMajorFallback() throws Exception {
    // Dorian has the minor 7th (semitone 10); Major does not -> proves it isn't a Major fallback.
    assertTrue(panelWithScale("Dorian").isNoteInScale(70)); // A# above C

    // Phrygian has the flat 2nd (semitone 1); Major does not.
    assertTrue(panelWithScale("Phrygian").isNoteInScale(61)); // C#

    // Whole Tone has semitone 6; Major does not.
    assertTrue(panelWithScale("Whole Tone").isNoteInScale(66)); // F#

    // Pentatonic Major excludes the 4th (semitone 5); Major includes it -> not a Major fallback.
    assertFalse(panelWithScale("Pentatonic Major").isNoteInScale(65)); // F

    // Chromatic lights everything.
    assertTrue(panelWithScale("Chromatic").isNoteInScale(61));
  }
}
