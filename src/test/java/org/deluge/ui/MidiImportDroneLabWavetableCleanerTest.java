package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Path;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import org.deluge.midi.MidiToProjectCompiler.TrackImportConfig;
import org.deluge.model.ProjectModel;
import org.deluge.model.SoundDrum;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Portable unit tests for {@link SwingMidiImportDialog}, {@link SwingDroneLabDialog}, {@link
 * SwingWavetableDialog} / {@link SwingWavetableVisualizer}, and {@link
 * SwingRecordingCleanerDialog}.
 */
class MidiImportDroneLabWavetableCleanerTest {

  @TempDir Path tempDir;

  @Test
  void testWavetableVisualizerDecodes8Bit16Bit24BitAnd32BitWav() throws Exception {
    // 1. 24-bit signed little-endian WAV: +0.5 full scale -> 0x400000
    File wav24 = tempDir.resolve("wt24.wav").toFile();
    byte[] pcm24 = new byte[] {0x00, 0x00, 0x40, 0x00, 0x00, (byte) 0xC0}; // +0.5, -0.5
    AudioFormat fmt24 = new AudioFormat(44100f, 24, 1, true, false);
    try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(pcm24), fmt24, 2)) {
      AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wav24);
    }
    float[] decoded24 = SwingWavetableVisualizer.decodeFullWavFile(wav24);
    assertNotNull(decoded24);
    assertEquals(2, decoded24.length);
    assertEquals(0.5f, decoded24[0], 1e-4f, "24-bit +0.5 sample must decode accurately");
    assertEquals(-0.5f, decoded24[1], 1e-4f, "24-bit -0.5 sample must decode accurately");

    // 2. 32-bit signed little-endian WAV: +0.5 full scale -> 0x40000000
    File wav32 = tempDir.resolve("wt32.wav").toFile();
    byte[] pcm32 = new byte[] {0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00, (byte) 0xC0};
    AudioFormat fmt32 = new AudioFormat(44100f, 32, 1, true, false);
    try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(pcm32), fmt32, 2)) {
      AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wav32);
    }
    float[] decoded32 = SwingWavetableVisualizer.decodeFullWavFile(wav32);
    assertNotNull(decoded32);
    assertEquals(2, decoded32.length);
    assertEquals(0.5f, decoded32[0], 1e-4f, "32-bit +0.5 sample must decode accurately");
    assertEquals(-0.5f, decoded32[1], 1e-4f, "32-bit -0.5 sample must decode accurately");

    // 3. q31FromFloat saturates out-of-range floats (fixedpoint.h:224-252)
    assertEquals(Integer.MAX_VALUE, SwingWavetableVisualizer.q31FromFloat(1.5f));
    assertEquals(Integer.MIN_VALUE, SwingWavetableVisualizer.q31FromFloat(-1.5f));
    assertEquals(0x40000000, SwingWavetableVisualizer.q31FromFloat(0.5f));

    // 4. Multi-cycle wavetables require power-of-two cycleSize (wave_table.cpp:173-195)
    assertTrue(SwingWavetableVisualizer.isValidWavetableCycleSize(2048, 4096));
    assertTrue(SwingWavetableVisualizer.isValidWavetableCycleSize(300, 300));
    assertTrue(SwingWavetableVisualizer.isValidWavetableCycleSize(300, 599));
    assertFalse(SwingWavetableVisualizer.isValidWavetableCycleSize(300, 600));
    assertFalse(SwingWavetableVisualizer.isValidWavetableCycleSize(4, 16));

    // 5. SwingWavetableDialog clamps out-of-range wavetableIndexPct and visualizer clamps cycleSize
    SoundDrum drum =
        new SoundDrum("WT", "") {
          @Override
          public int getWavetableIndexPct() {
            return 150;
          }
        };
    SwingUtilities.invokeAndWait(
        () -> {
          SwingWavetableDialog dlg = new SwingWavetableDialog(null, drum, null, 0, 0);
          try {
            JSlider slider = findFirstSlider(dlg.getContentPane());
            assertNotNull(slider);
            assertEquals(100, slider.getValue(), "Slider must clamp initial value to 100");
          } finally {
            dlg.dispose();
          }

          SwingWavetableVisualizer vis = new SwingWavetableVisualizer();
          vis.setCycleSize(1); // must clamp to >= 8 (kWavetableMinCycleSize)
          vis.setSize(400, 200);
          java.awt.image.BufferedImage img =
              new java.awt.image.BufferedImage(
                  400, 200, java.awt.image.BufferedImage.TYPE_INT_ARGB);
          assertDoesNotThrow(() -> vis.paint(img.getGraphics()));
        });
  }

  @Test
  void testDroneLabSliderNonAdjustingUpdatesAndNullAppSafety() throws Exception {
    SynthTrackModel track = new SynthTrackModel("Drone");
    ProjectModel project = new ProjectModel();
    project.addTrack(track);

    SwingUtilities.invokeAndWait(
        () -> {
          SwingDroneLabDialog dlg = new SwingDroneLabDialog(null, track, null, 0, project);
          try {
            JSlider frictionSlider = findFirstSlider(dlg.getContentPane());
            assertNotNull(frictionSlider);
            // Non-adjusting change (simulating keyboard arrow key or click-jump)
            frictionSlider.setValueIsAdjusting(false);
            assertDoesNotThrow(() -> frictionSlider.setValue(80));

            JButton playBtn = findButtonWithText(dlg.getContentPane(), "▶ Play/Stop");
            assertNotNull(playBtn);
            assertDoesNotThrow(
                () -> playBtn.doClick(), "Play button must not NPE when app is null");
          } finally {
            dlg.dispose();
          }
        });
  }

  @Test
  void testMidiImportDialogGuardsNullConfigsAndClampsSplitPoint() throws Exception {
    File midiFile = tempDir.resolve("test.mid").toFile();
    Sequence seq = new Sequence(Sequence.PPQ, 480);
    Track trk = seq.createTrack();
    trk.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100), 0));
    trk.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 60, 0), 480));
    MidiSystem.write(seq, 1, midiFile);

    SwingUtilities.invokeAndWait(
        () -> {
          SwingMidiImportDialog dlg = new SwingMidiImportDialog(null, midiFile);
          try {
            // Clicking SELECT ALL before SwingWorker completes must not throw NPE
            JButton selectAllBtn = findButtonWithText(dlg.getContentPane(), "SELECT ALL");
            assertNotNull(selectAllBtn);
            assertDoesNotThrow(() -> selectAllBtn.doClick());

            // Out-of-range splitPoint (< 21) must be clamped before creating SpinnerNumberModel
            TrackImportConfig cfg = new TrackImportConfig();
            cfg.trackName = "Bass";
            cfg.splitPoint = 5;
            JPanel card = assertDoesNotThrow(() -> dlg.createTrackConfigCard(cfg));
            assertNotNull(card);
            assertEquals(21, cfg.splitPoint, "splitPoint must be clamped to >= 21");
          } finally {
            dlg.dispose();
          }
        });
  }

  @Test
  void testRecordingCleanerDisposeClosesActiveClip() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          SwingRecordingCleanerDialog dlg = new SwingRecordingCleanerDialog(null);
          assertDoesNotThrow(dlg::dispose, "dispose() must cleanly stop any active audio clip");
        });
  }

  private static JSlider findFirstSlider(Container root) {
    for (Component comp : root.getComponents()) {
      if (comp instanceof JSlider s) return s;
      if (comp instanceof Container child) {
        JSlider found = findFirstSlider(child);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static JButton findButtonWithText(Container root, String text) {
    for (Component comp : root.getComponents()) {
      if (comp instanceof JButton btn && text.equals(btn.getText())) return btn;
      if (comp instanceof Container child) {
        JButton found = findButtonWithText(child, text);
        if (found != null) return found;
      }
    }
    return null;
  }
}
