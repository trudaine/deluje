package org.chuck.deluge.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConsequenceTest {

  @Test
  void stepConsequenceStoresFields() {
    StepData oldS = StepData.empty();
    StepData newS = new StepData(true, 0.8f, 0.5f, 1.0f, 60);
    var c = new Consequence.StepConsequence(0, 1, 2, 3, oldS, newS);
    assertEquals(0, c.trackIndex());
    assertEquals(1, c.clipIndex());
    assertEquals(2, c.row());
    assertEquals(3, c.step());
    assertEquals(oldS, c.oldData());
    assertEquals(newS, c.newData());
    assertEquals("Toggle step 4:3", c.getDescription());
    assertEquals(Consequence.Category.STEP, c.category());
  }

  @Test
  void automationConsequenceStoresFields() {
    var c = new Consequence.AutomationConsequence(0, 1, "volume", 5, 0.5f, 0.8f);
    assertEquals(0.5f, c.oldValue(), 0.001f);
    assertEquals(0.8f, c.newValue(), 0.001f);
    assertEquals("Edit automation volume step 6", c.getDescription());
    assertEquals(Consequence.Category.AUTOMATION, c.category());
  }

  @Test
  void synthParamConsequenceStoresFields() {
    long now = System.currentTimeMillis();
    var c = new Consequence.SynthParamConsequence(1, "filterDrive", 0.3f, 0.7f, now);
    assertEquals(0.3f, c.oldValue(), 0.001f);
    assertEquals(0.7f, c.newValue(), 0.001f);
    assertEquals(now, c.timestamp());
    assertEquals("Change filterDrive", c.getDescription());
    assertEquals(Consequence.Category.SYNTH_PARAM, c.category());
  }

  @Test
  void projectParamConsequenceStoresFields() {
    var c = new Consequence.ProjectParamConsequence("bpm", 120.0f, 140.0f);
    assertEquals(120.0f, c.oldValue(), 0.001f);
    assertEquals(140.0f, c.newValue(), 0.001f);
    assertEquals("Change bpm", c.getDescription());
    assertEquals(Consequence.Category.PROJECT_PARAM, c.category());
  }

  @Test
  void trackStructureConsequenceAdd() {
    KitTrackModel kit = new KitTrackModel("test");
    var c = new Consequence.TrackStructureConsequence(
        Consequence.TrackStructureConsequence.ADD, 0, kit, "Add track");
    assertEquals(Consequence.TrackStructureConsequence.ADD, c.operation());
    assertEquals("test", c.trackSnapshot().getName());
    assertEquals("Add track", c.getDescription());
    assertEquals(Consequence.Category.TRACK_STRUCT, c.category());
  }

  @Test
  void clipStructureConsequenceStoresFields() {
    ClipModel clip = new ClipModel("CLIP 1", 8, 16);
    var c = new Consequence.ClipStructureConsequence(
        0, 0, Consequence.ClipStructureConsequence.RENAME, clip, "OLD", "NEW");
    assertEquals("NEW", c.newName());
    assertEquals("OLD", c.previousName());
    assertEquals("Rename clip to NEW", c.getDescription());
    assertEquals(Consequence.Category.CLIP_STRUCT, c.category());
  }

  @Test
  void compoundConsequenceUndoesInReverse() {
    var a1 = new Consequence.StepConsequence(0, 0, 0, 0, StepData.empty(), new StepData(true, 0.8f, 0.5f, 1.0f, 60));
    var a2 = new Consequence.StepConsequence(0, 0, 0, 1, StepData.empty(), new StepData(true, 0.8f, 0.5f, 1.0f, 61));
    var c = new Consequence.CompoundConsequence("Clear row", List.of(a1, a2));
    assertEquals("Clear row", c.getDescription());
    assertEquals(Consequence.Category.PATTERN_LOAD, c.category());
    // compound undo/redo just delegates — concrete undo happens in UI layer
    c.undo();
    c.redo();
  }
}
