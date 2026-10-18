package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import javax.swing.JButton;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import org.deluge.BridgeContract;
import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareSound;
import org.deluge.firmware2.WaveTable;
import org.deluge.firmware2.WaveTableWriter;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.project.PreferencesManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Portable unit tests for {@link SwingSynthWavetableEditorDialog}, {@link Wavetable3DVisualizer},
 * {@link SwingWaveformDrawingCanvas}, {@link SwingAudioTranscribeDialog}, and {@link
 * SwingPerformanceViewPanel}.
 */
class WavetableEditorTranscribePerfViewTest {

  @TempDir Path tempDir;

  @Test
  void testWavetableEditorSyncsWaveIndexAndLoadsNonDefaultCycleGeometry() throws Exception {
    BridgeContract bridge = new BridgeContract();
    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    FirmwareSound fs = new FirmwareSound();
    engine.sounds.add(fs);
    bridge.setGlobalObject(BridgeContract.G_FIRMWARE_ENGINE, engine);

    // Pre-populate a 4-cycle, 512-sample wavetable on Oscillator A
    WaveTable wt = new WaveTable();
    wt.setup(512, 4 * 512);
    short[] bandData = wt.bands.get(0).data;
    int stride = 512 + WaveTable.WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE;
    for (int c = 0; c < 4; c++) {
      short val = (short) ((c + 1) * 4000);
      for (int i = 0; i < 512; i++) {
        bandData[c * stride + i] = val;
      }
      // Put sentinel values in the 7 duplicate padding slots to verify editor does not read them as
      // cycle samples
      for (int d = 0; d < WaveTable.WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE; d++) {
        bandData[c * stride + 512 + d] = -32000;
      }
    }
    fs.fw2Sound.waveTables[0] = wt;

    SynthTrackModel model = new SynthTrackModel("WT Track");
    model.setWaveIndex(0.67f); // -> cycle index Math.round(0.67 * 3) = 2

    SwingUtilities.invokeAndWait(
        () -> {
          SwingSynthWavetableEditorDialog dlg =
              new SwingSynthWavetableEditorDialog(null, model, 0, 0, bridge);
          try {
            JSlider scanSlider = findFirstSliderWithMax(dlg.getContentPane(), 3);
            assertNotNull(scanSlider, "Scan slider max must match loaded 4-cycle wavetable (0..3)");
            assertEquals(2, scanSlider.getValue(), "Scan slider must start at model waveIndex");

            SwingWaveformDrawingCanvas canvas = findCanvas(dlg.getContentPane());
            assertNotNull(canvas);
            float[] cycleBuf = canvas.getCycleBuffer();
            assertEquals(512, cycleBuf.length, "Canvas buffer must match loaded cycleSize (512)");
            // Cycle 2 had constant sample value 3 * 4000 = 12000 -> 12000 / 32768.0f
            assertEquals(12000 / 32768.0f, cycleBuf[0], 1e-4f);
            assertEquals(12000 / 32768.0f, cycleBuf[511], 1e-4f);
          } finally {
            dlg.dispose();
          }
        });
  }

  @Test
  void testWavetable3DVisualizerSingleCyclePaintAndRelativePathResolution() throws Exception {
    File wavFile = tempDir.resolve("rel_wt.wav").toFile();
    float[] singleCycle = new float[2048];
    for (int i = 0; i < 2048; i++) {
      singleCycle[i] = (float) Math.sin(2.0 * Math.PI * i / 2048.0);
    }
    WaveTableWriter.writeWavetable(singleCycle, wavFile.getAbsolutePath());

    String prevLib = PreferencesManager.get("library_dir", "");
    String prevSysCard = System.getProperty("deluge.card");
    try {
      System.clearProperty("deluge.card");
      PreferencesManager.setLibraryDir(tempDir.toFile().getAbsolutePath());
      File resolved = Wavetable3DVisualizer.resolveWavetableFile("rel_wt.wav");
      assertNotNull(resolved, "Relative wavetable path must resolve under library_dir");
      assertEquals(wavFile.getCanonicalPath(), resolved.getCanonicalPath());

      SynthTrackModel model = new SynthTrackModel("WT3D");
      model.setOsc1Type("WAVETABLE");
      model.setWaveIndex(0.5f);

      WaveTable wt1 = new WaveTable();
      wt1.setup(2048, 2048); // numCycles == 1 (C wave_table.cpp:179-182)

      SwingUtilities.invokeAndWait(
          () -> {
            Wavetable3DVisualizer vis = new Wavetable3DVisualizer(model, 0, 0);
            vis.setLoadedWavetableForTest(wt1);
            vis.setSize(300, 200);
            BufferedImage img = new BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB);
            assertDoesNotThrow(
                () -> vis.paint(img.getGraphics()),
                "Painting a single-cycle wavetable (numCycles == 1) must not divide by zero");

            SwingWaveformDrawingCanvas canvas = new SwingWaveformDrawingCanvas(2);
            assertEquals(
                8,
                canvas.getCycleBuffer().length,
                "Canvas must clamp cycleSize < 8 to kWavetableMinCycleSize (8)");
            canvas.setSize(1, 1);
            canvas.drawAtMouse(0, 0, true);
            for (float v : canvas.getCycleBuffer()) {
              assertFalse(Float.isNaN(v), "1px canvas draw must not store NaN");
            }
          });
    } finally {
      PreferencesManager.setLibraryDir(prevLib != null ? prevLib : "");
      if (prevSysCard != null) {
        System.setProperty("deluge.card", prevSysCard);
      }
    }
  }

  @Test
  void testAudioTranscribeDialogDisposeKillsProcessAndRetryResetsColor() throws Exception {
    File dummyAudio = tempDir.resolve("input.wav").toFile();
    assertTrue(dummyAudio.createNewFile());

    Process sleepProc = new ProcessBuilder("sh", "-c", "sleep 30").start();
    try {
      SwingUtilities.invokeAndWait(
          () -> {
            SwingAudioTranscribeDialog dlg = new SwingAudioTranscribeDialog(null, dummyAudio);
            try {
              dlg.getConsoleArea().setForeground(Color.RED);
              dlg.setActiveProcessForTest(sleepProc);
              dlg.dispose();
            } finally {
              if (dlg.isDisplayable()) {
                dlg.dispose();
              }
            }
          });
      assertFalse(
          sleepProc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) && sleepProc.isAlive(),
          "dispose() must forcibly terminate an active transcription subprocess");
      assertFalse(sleepProc.isAlive());
    } finally {
      if (sleepProc.isAlive()) {
        sleepProc.destroyForcibly();
      }
    }

    SwingUtilities.invokeAndWait(
        () -> {
          SwingAudioTranscribeDialog dlg2 = new SwingAudioTranscribeDialog(null, dummyAudio);
          try {
            dlg2.getConsoleArea().setForeground(Color.RED);
            dlg2.startTranscriptionTask();
            assertEquals(
                new Color(0xaa, 0xff, 0xdd),
                dlg2.getConsoleArea().getForeground(),
                "Retrying transcription must reset console foreground from error red to green");
          } finally {
            dlg2.dispose();
          }
        });
  }

  @Test
  void testPerformanceViewLatchExclusivityAndResetPreservesInactiveColumns() throws Exception {
    BridgeContract bridge = new BridgeContract();
    bridge.setFilterFreq(0, 5000.0); // Initial LPF freq on track 0
    bridge.setDelaySend(0, 0.35f); // Initial delay send on track 0 (inactive column 8)

    ProjectModel project = new ProjectModel();

    SwingUtilities.invokeAndWait(
        () -> {
          SwingPerformanceViewPanel panel = new SwingPerformanceViewPanel(bridge, project);
          JToggleButton modeToggle = findToggleButton(panel);
          assertNotNull(modeToggle);
          assertEquals("LATCH", modeToggle.getText(), "Mode button must start with LATCH label");
          assertFalse(modeToggle.isSelected());

          // Latch row 1 in column 2 (LPF FREQ)
          pressPad(panel.getPad(1, 2));
          assertTrue(panel.isPadLatched(1, 2));
          double row1Freq = bridge.getTrackFilterFreq(0);
          assertNotEquals(5000.0, row1Freq, 1.0);

          // Latch row 6 in column 2 (LPF FREQ): row 1 must unlatch, and saved value must stay 5000
          pressPad(panel.getPad(6, 2));
          assertFalse(panel.isPadLatched(1, 2), "Previous row in same column must unlatch");
          assertTrue(panel.isPadLatched(6, 2), "New row in same column must latch");
          double row6Freq = bridge.getTrackFilterFreq(0);
          assertNotEquals(row1Freq, row6Freq, 1.0);

          // Reset all: column 2 must restore to 5000.0, and untouched column 8 (Delay) must stay
          // 0.35f!
          panel.resetAll();
          assertFalse(panel.isPadLatched(6, 2));
          assertEquals(
              5000.0,
              bridge.getTrackFilterFreq(0),
              1.0,
              "resetAll() must restore original pre-latch value");
          assertEquals(
              0.35f,
              bridge.getDelaySend(0),
              1e-4f,
              "resetAll() must not zero out columns that were never activated");
        });
  }

  private static void pressPad(JButton pad) {
    MouseEvent ev =
        new MouseEvent(
            pad,
            MouseEvent.MOUSE_PRESSED,
            System.currentTimeMillis(),
            MouseEvent.BUTTON1_DOWN_MASK,
            2,
            2,
            1,
            false,
            MouseEvent.BUTTON1);
    for (MouseListener ml : pad.getMouseListeners()) {
      ml.mousePressed(ev);
    }
  }

  private static JSlider findFirstSliderWithMax(Container root, int expectedMax) {
    for (Component comp : root.getComponents()) {
      if (comp instanceof JSlider s && s.getMaximum() == expectedMax) return s;
      if (comp instanceof Container child) {
        JSlider found = findFirstSliderWithMax(child, expectedMax);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static SwingWaveformDrawingCanvas findCanvas(Container root) {
    for (Component comp : root.getComponents()) {
      if (comp instanceof SwingWaveformDrawingCanvas c) return c;
      if (comp instanceof Container child) {
        SwingWaveformDrawingCanvas found = findCanvas(child);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static JToggleButton findToggleButton(Container root) {
    for (Component comp : root.getComponents()) {
      if (comp instanceof JToggleButton tb) return tb;
      if (comp instanceof Container child) {
        JToggleButton found = findToggleButton(child);
        if (found != null) return found;
      }
    }
    return null;
  }
}
