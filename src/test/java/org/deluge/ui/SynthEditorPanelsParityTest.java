package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import javax.swing.border.LineBorder;
import org.deluge.BridgeContract;
import org.deluge.model.ArpModel;
import org.deluge.model.EnvelopeModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Portable unit tests for {@link ThemeManager}, {@link SwingSynthConfigDialog}, {@link
 * FilterGraphComponent}, {@link EnvelopeGraphComponent}, {@link ModFxPanel}, {@link ArpPanel},
 * {@link AlgorithmPanel}, and {@link LfoMonitorComponent}.
 */
class SynthEditorPanelsParityTest {

  @Test
  void testThemeManagerRemoveListenerAndModFxNormalize() {
    AtomicInteger calls = new AtomicInteger();
    Runnable listener = calls::incrementAndGet;
    ThemeManager.Theme original = ThemeManager.getActiveTheme();
    try {
      ThemeManager.addThemeListener(listener);
      ThemeManager.setActiveTheme(ThemeManager.Theme.MATRIX_GREEN);
      assertEquals(1, calls.get());

      ThemeManager.removeThemeListener(listener);
      ThemeManager.setActiveTheme(ThemeManager.Theme.SOLAR_ORANGE);
      assertEquals(1, calls.get(), "Removed theme listener must not fire after removal");
    } finally {
      ThemeManager.removeThemeListener(listener);
      ThemeManager.setActiveTheme(original);
    }

    assertEquals(8, ModFxPanel.MOD_FX_TYPES.length, "All 8 C++ ModFXType options must be present");
    assertEquals("CHORUS_STEREO", ModFxPanel.normalizeModFxType("StereoChorus"));
    assertEquals("WARBLE", ModFxPanel.normalizeModFxType("TapeWarble"));
    assertEquals("GRAIN", ModFxPanel.normalizeModFxType("grainFX"));
    assertEquals("DIMENSION", ModFxPanel.normalizeModFxType("dimension"));
  }

  @Test
  void testArpPanelDottedSyncAndRhythmMax50() throws Exception {
    assertEquals(28, ArpPanel.SYNC_RATES.length, "ArpPanel must carry all 28 C sync options");
    int idx8D = ArpPanel.syncComboIndexFor(8, 2); // 1/8 dotted (syncType = 2)
    assertTrue(idx8D > 0, "ArpPanel must recognize dotted syncType=2");
    assertEquals("1/8D", ArpPanel.SYNC_RATES[idx8D]);

    int idx1T = ArpPanel.syncComboIndexFor(1, 1); // 1/1 triplet (syncType = 1)
    assertEquals("1/1T", ArpPanel.SYNC_RATES[idx1T]);

    int idx1D = ArpPanel.syncComboIndexFor(1, 2); // 1/1 dotted (syncType = 2)
    assertEquals("1/1D", ArpPanel.SYNC_RATES[idx1D]);

    int idx256 = ArpPanel.syncComboIndexFor(256, 0); // 1/256 even
    assertEquals("1/256", ArpPanel.SYNC_RATES[idx256]);

    int idxMultiBar = ArpPanel.syncComboIndexFor(-2, 0); // 2-bar period clamps to 1/1, not OFF
    assertEquals("1/1", ArpPanel.SYNC_RATES[idxMultiBar]);

    int idx64T = ArpPanel.syncComboIndexFor(64, 1); // 1/64 triplet (syncType = 1)
    assertTrue(idx64T > 0, "ArpPanel must recognize 1/64T");
    assertEquals("1/64T", ArpPanel.SYNC_RATES[idx64T]);

    if (GraphicsEnvironment.isHeadless()) {
      return;
    }

    SynthTrackModel model = new SynthTrackModel("Synth 1");
    model.setArp(
        ArpModel.defaultConfig().toBuilder().syncLevel(8).syncType(2).rhythmIndex(50).build());

    SwingUtilities.invokeAndWait(
        () -> {
          // Null ProjectModel must not throw NullPointerException
          ArpPanel panel = new ArpPanel(model, new BridgeContract(), 0, null);
          JSlider rhythmSlider = SwingSynthConfigDialog.findSliderByName(panel, "arpRhythm");
          assertNotNull(rhythmSlider);
          assertEquals(
              50,
              rhythmSlider.getMaximum(),
              "Rhythm slider max must match C++ kMaxPresetArpRhythm = 50");
          assertEquals(50, rhythmSlider.getValue());
        });
  }

  @Test
  void testModFxPanelSelectsAllEightTypes() throws Exception {
    if (GraphicsEnvironment.isHeadless()) {
      return;
    }

    SynthTrackModel model = new SynthTrackModel("Synth 1");
    model.setModFxType("StereoChorus"); // Firmware XML token

    SwingUtilities.invokeAndWait(
        () -> {
          ModFxPanel panel = new ModFxPanel(model, new BridgeContract(), 0, new ProjectModel());
          JComboBox<String> combo = panel.getTypeComboForTest();
          assertEquals("CHORUS_STEREO", combo.getSelectedItem());
          assertTrue(Arrays.asList(ModFxPanel.MOD_FX_TYPES).contains("GRAIN"));
        });
  }

  @Test
  void testAlgorithmPanelCardSelectionUpdatesBorderAndCombo() throws Exception {
    if (GraphicsEnvironment.isHeadless()) {
      return;
    }

    SynthTrackModel model = new SynthTrackModel("Synth 1");
    model.setSynthAlgorithm(0);
    model.setEngineType(99); // Out-of-range engineType must clamp safely

    SwingUtilities.invokeAndWait(
        () -> {
          AlgorithmPanel panel = new AlgorithmPanel(model, new BridgeContract(), 0);
          JPanel grid = panel.getGridPanelForTest();
          JPanel card10 = (JPanel) grid.getComponent(10); // Algo 10 = Mandolin

          JButton selectBtn10 = findSelectButton(card10);
          assertNotNull(selectBtn10);
          selectBtn10.doClick();

          assertEquals(10, model.getSynthAlgorithm());
          assertEquals(
              1,
              panel.getEngineComboForTest().getSelectedIndex(),
              "Selecting Algo 10 must sync engineCombo to Mandolin");

          assertTrue(card10.getBorder() instanceof LineBorder);
          assertEquals(
              Color.CYAN,
              ((LineBorder) card10.getBorder()).getLineColor(),
              "Selected algorithm card must have a cyan border");
        });
  }

  @Test
  void testEnvelopeGraphClampedHitTestAndLfoMonitorRemoveNotify() throws Exception {
    if (GraphicsEnvironment.isHeadless()) {
      return;
    }

    SynthTrackModel model = new SynthTrackModel("Synth 1");
    // Attack = 4.5s (> 2.0s display cap)
    model.setEnv(0, new EnvelopeModel(4.5f, 1.0f, 0.5f, 1.0f, "VOLUME", 1.0f));

    SwingUtilities.invokeAndWait(
        () -> {
          EnvelopeGraphComponent graph = new EnvelopeGraphComponent(model, 0);
          graph.setSize(360, 180);
          // paddingX = 20, paddingY = 20, drawW = 320, maxSegW = 80
          // Clamped attack = 2.0s -> attackNorm = 1.0 -> attackX = 20 + 80 = 100, startY = 20
          assertTrue(
              graph.isHandleHitForTest(100, 20),
              "Attack handle must be clickable at its clamped display position when attack > 2.0s");

          LfoMonitorComponent monitor = new LfoMonitorComponent(model);
          monitor.startAnimation();
          assertTrue(monitor.isAnimationTimerRunningForTest());
          monitor.removeNotify();
          assertFalse(
              monitor.isAnimationTimerRunningForTest(),
              "removeNotify must stop LfoMonitorComponent animationTimer");
        });
  }

  @Test
  void testSynthDialogLpfResonanceScalingAndPresetInit() throws Exception {
    ProjectModel project = new ProjectModel();
    SynthTrackModel model = new SynthTrackModel("Synth 1");
    model.setLpfFreq(10000.0f); // 50% of 20 kHz
    model.setLpfRes(0.35f); // 35% resonance
    project.addTrack(model);

    var consequence =
        new org.deluge.model.Consequence.SynthParamConsequence(
            project, 0, "lpfResonance", 35f, 60f, 0L);
    consequence.redo();
    assertEquals(0.60f, model.getLpfRes(), 1e-4f, "Redo of lpfResonance=60 must set 0.60f");
    consequence.undo();
    assertEquals(0.35f, model.getLpfRes(), 1e-4f, "Undo of lpfResonance=35 must set 0.35f");

    if (GraphicsEnvironment.isHeadless()) {
      return;
    }

    SwingUtilities.invokeAndWait(
        () -> {
          SwingSynthConfigDialog dialog =
              new SwingSynthConfigDialog(null, model, new BridgeContract(), 0, project);
          try {
            JSlider cutoffSlider = SwingSynthConfigDialog.findSliderByName(dialog, "lpfCutoff");
            JSlider resSlider = SwingSynthConfigDialog.findSliderByName(dialog, "lpfResonance");
            assertNotNull(cutoffSlider);
            assertNotNull(resSlider);
            assertEquals(
                50, cutoffSlider.getValue(), "Cutoff slider must read initial value from model");
            assertEquals(
                35, resSlider.getValue(), "Resonance slider must read initial value from model");

            resSlider.setValue(60);
            assertEquals(
                0.60f,
                model.getLpfRes(),
                1e-4f,
                "Setting resonance slider to 60% must set model.getLpfRes() to 0.60f, not 60.0f");
          } finally {
            dialog.dispose();
          }
        });
  }

  private static JButton findSelectButton(JPanel card) {
    for (Component child : card.getComponents()) {
      if (child instanceof JPanel row) {
        for (Component btn : row.getComponents()) {
          if (btn instanceof JButton b) {
            return b;
          }
        }
      }
    }
    return null;
  }
}
