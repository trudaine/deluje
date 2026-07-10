package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import org.deluge.BridgeContract;
import org.deluge.model.EnvelopeModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.ui.SwingHardwareTopPanel.ControlDef;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the real Deluge 8-mode gold-knob system (C: sound.cpp:97-122's
 * modKnobs[mode][knob] table). Before this, MOD0-7 buttons had zero click handling and
 * upperGoldRow/lowerGoldRow (driving the 4-square indicators) were frozen at their initial value
 * forever -- nothing ever wrote to them.
 */
public class ModKnobModeTest {

  private SwingDelugeApp app;
  private BridgeContract bridge;
  private SwingHardwareTopPanel topPanel;
  private SynthTrackModel track;

  private void setUp() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    bridge = new BridgeContract();
    app = new SwingDelugeApp(bridge, null);
    ProjectModel project = app.getCurrentProject();
    project.getTracks().clear();
    track = new SynthTrackModel("MOD_TEST_TRACK");
    project.addTrack(track);
    app.propagateCurrentModel();
    app.getClipPanel().editedModelTrack = 0;
    topPanel = app.hardwareTopPanel;
  }

  private void tearDown() {
    app.dispose();
    bridge.shutdown();
  }

  @Test
  public void testModButtonSelectsAndPersistsMode() throws Exception {
    setUp();
    try {
      Method selectMode =
          SwingHardwareTopPanel.class.getDeclaredMethod("selectModKnobMode", int.class);
      selectMode.setAccessible(true);
      selectMode.invoke(topPanel, 3);

      assertEquals(3, track.getModKnobMode(), "Clicking MOD3 must set the track's modKnobMode");

      Method isActive =
          SwingHardwareTopPanel.class.getDeclaredMethod("isControlActive", ControlDef.class);
      isActive.setAccessible(true);
      ControlDef mod3 = new ControlDef("MOD3", 0, 0, 0, false, null);
      ControlDef mod5 = new ControlDef("MOD5", 0, 0, 0, false, null);
      assertTrue(
          (boolean) isActive.invoke(topPanel, mod3),
          "MOD3's selection highlight must persist after the click, not just flash");
      assertFalse(
          (boolean) isActive.invoke(topPanel, mod5),
          "Only the selected MOD button should be active");
    } finally {
      tearDown();
    }
  }

  @Test
  public void testGoldKnobTurnAdjustsRealParameterForCurrentMode() throws Exception {
    setUp();
    try {
      Method selectMode =
          SwingHardwareTopPanel.class.getDeclaredMethod("selectModKnobMode", int.class);
      selectMode.setAccessible(true);
      Method adjust =
          SwingHardwareTopPanel.class.getDeclaredMethod(
              "adjustModKnobParam", int.class, int.class, boolean.class);
      adjust.setAccessible(true);

      // Mode 1: knob 0 = LPF resonance, knob 1 = LPF cutoff (C: sound.cpp:100-101)
      selectMode.invoke(topPanel, 1);
      float resBefore = track.getLpfRes();
      adjust.invoke(topPanel, 0, 1, false);
      assertTrue(
          track.getLpfRes() > resBefore, "Turning knob 0 in mode 1 must raise LPF resonance");

      // Cutoff defaults to 20000 (the max), so turn down instead of up to observe a change.
      float cutoffBefore = track.getLpfFreq();
      adjust.invoke(topPanel, 1, -1, false);
      assertTrue(
          track.getLpfFreq() < cutoffBefore, "Turning knob 1 in mode 1 must change LPF cutoff");

      // Mode 2: knob 0 = env release, knob 1 = env attack (C: sound.cpp:103-104)
      selectMode.invoke(topPanel, 2);
      EnvelopeModel envBefore = track.getEnv(0);
      adjust.invoke(topPanel, 0, 1, false);
      assertTrue(
          track.getEnv(0).release() > envBefore.release(),
          "Turning knob 0 in mode 2 must raise envelope release");
      assertEquals(
          envBefore.attack(),
          track.getEnv(0).attack(),
          0.0001f,
          "Adjusting release must not disturb attack (immutable record must be rebuilt correctly)");

      // Mode 7: knob 0 = bitcrush, knob 1 = sample rate reduction (C: sound.cpp:120-122)
      selectMode.invoke(topPanel, 7);
      float bitcrushBefore = track.getBitCrush();
      adjust.invoke(topPanel, 0, 1, false);
      assertTrue(
          track.getBitCrush() > bitcrushBefore, "Turning knob 0 in mode 7 must raise bitcrush");
    } finally {
      tearDown();
    }
  }

  @Test
  public void testSquareIndicatorPaintsForBothColumnsWithoutThrowing() throws Exception {
    setUp();
    try {
      Method selectMode =
          SwingHardwareTopPanel.class.getDeclaredMethod("selectModKnobMode", int.class);
      selectMode.setAccessible(true);
      selectMode.invoke(topPanel, 2);
      selectMode.invoke(topPanel, 6);

      Method draw =
          SwingHardwareTopPanel.class.getDeclaredMethod(
              "drawModEncoderSquareLeds", java.awt.Graphics2D.class);
      draw.setAccessible(true);
      java.awt.image.BufferedImage img =
          new java.awt.image.BufferedImage(100, 100, java.awt.image.BufferedImage.TYPE_INT_ARGB);
      java.awt.Graphics2D g2 = img.createGraphics();
      assertDoesNotThrow(
          () -> draw.invoke(topPanel, g2), "Painting the square indicators must not throw");
      g2.dispose();
    } finally {
      tearDown();
    }
  }

  @Test
  public void testSquareIndicatorShowsKnobValueBargraphNotModeSelection() throws Exception {
    // Regression: the 4 squares next to each gold knob show that knob's CURRENT VALUE as a
    // proportional bargraph (confirmed against real hardware: turning a knob partially fills the
    // boundary square, e.g. 2 full + a half-lit 3rd) -- not which of the 8 modKnobModes is
    // selected. The MOD0-7 buttons' own LEDs already show mode selection.
    setUp();
    try {
      Method selectMode =
          SwingHardwareTopPanel.class.getDeclaredMethod("selectModKnobMode", int.class);
      selectMode.setAccessible(true);
      Method fillLevel =
          SwingHardwareTopPanel.class.getDeclaredMethod("currentModKnobFillLevel", int.class);
      fillLevel.setAccessible(true);

      // Mode 0: knob 0 = pan (-1..1 -> 0..1), knob 1 = volume (0..1 directly).
      selectMode.invoke(topPanel, 0);
      track.setPan(0.5f); // (0.5+1)/2 = 0.75
      track.setVolume(0.3f);
      assertEquals(0.75f, (float) fillLevel.invoke(topPanel, 0), 0.001f);
      assertEquals(0.3f, (float) fillLevel.invoke(topPanel, 1), 0.001f);

      // Turning the knob (not just setting the model directly) must move the same fill level.
      Method adjust =
          SwingHardwareTopPanel.class.getDeclaredMethod(
              "adjustModKnobParam", int.class, int.class, boolean.class);
      adjust.setAccessible(true);
      float before = (float) fillLevel.invoke(topPanel, 1);
      adjust.invoke(topPanel, 1, 5, false); // raise volume
      float after = (float) fillLevel.invoke(topPanel, 1);
      assertTrue(after > before, "Turning the knob must raise its own bargraph fill level");

      // Mode 4 / knob 1 (sidechain) has no model+engine plumbing -- must render empty, not a
      // fabricated value.
      selectMode.invoke(topPanel, 4);
      assertEquals(-1f, (float) fillLevel.invoke(topPanel, 1), 0.001f);
    } finally {
      tearDown();
    }
  }
}
