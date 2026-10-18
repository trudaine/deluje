package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JLabel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import org.deluge.BridgeContract;
import org.deluge.midi.MidiInputRouter;
import org.deluge.midi.MidiService;
import org.deluge.model.ClipModel;
import org.deluge.model.KeyZone;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for {@link SwingRandomizerDialog}, {@link SwingKeyZoneMapperDialog}, {@link
 * SwingKeyZoneGridMap}, {@link SwingMasterFxDialog}, and {@link TransportController}.
 */
class RandomizerKeyZoneMasterFxTransportTest {

  @Test
  void testChordProgressionGeneratorWritesValidStepsOn8RowClip() {
    ClipModel clip = new ClipModel("CHORD PROGRESSION", 8, 16);
    // I - IV - V - I in C4 (60), Triads (0), Staccato Stabs (2, gate = 0.25)
    SwingRandomizerDialog.generateChordProgression(
        clip, 60, List.of(0, 3, 4, 0), false, 0, 2, null);

    // Distinct pitches in I-IV-V-I C major triads: C4(60), E4(64), G4(67), F4(65), A4(69), C5(72),
    // B4(71), D5(74) -> 8 distinct pitches sorted descending: 74, 72, 71, 69, 67, 65, 64, 60.
    assertEquals(8, clip.getRowCount());
    assertEquals(74, clip.getRowYNote(0));
    assertEquals(60, clip.getRowYNote(7));

    // Step 0 (bar 0, stab 0) has C4 (60) at row 7, E4 (64) at row 6, G4 (67) at row 4
    StepData c4 = clip.getStep(7, 0);
    assertTrue(c4.active(), "C4 chord root must be active on an 8-row clip");
    assertEquals(60, c4.pitch(), "StepData.of pitch must be 60, not 0");
    assertEquals(1.0f, c4.velocity(), 1e-4f, "StepData.of velocity must be 1.0f");
    assertEquals(0.25f, c4.gate(), 1e-4f, "Staccato stab gate must be 0.25f");
    assertEquals(1.0f, c4.probability(), 1e-4f, "StepData.of probability must be 1.0f");

    // Verify negative custom degree diatonic math (-1 -> B3 = -1 semitone from C4)
    int[] majorIntervals = {0, 2, 4, 5, 7, 9, 11};
    assertEquals(-1, SwingRandomizerDialog.diatonicOffset(majorIntervals, -1));
    assertEquals(-3, SwingRandomizerDialog.diatonicOffset(majorIntervals, -2));
  }

  @Test
  void testKeyZoneRelativePathAndNullSamplePathAndDragNotification(@TempDir Path tempDir)
      throws Exception {
    File sdDir = tempDir.resolve("sd").toFile();
    File siblingDir = tempDir.resolve("sd_backup").toFile();
    assertTrue(new File(sdDir, "SAMPLES/Pianos").mkdirs());
    assertTrue(siblingDir.mkdirs());

    File insideFile = new File(sdDir, "SAMPLES/Pianos/C4.wav");
    File siblingFile = new File(siblingDir, "C4.wav");

    assertEquals(
        "SAMPLES/Pianos/C4.wav",
        SwingKeyZoneMapperDialog.resolveRelativeSdPath(insideFile.getAbsolutePath(), sdDir));
    assertEquals(
        siblingFile.getAbsolutePath(),
        SwingKeyZoneMapperDialog.resolveRelativeSdPath(siblingFile.getAbsolutePath(), sdDir),
        "Sibling directory sharing prefix must not be stripped as relative to sdDir");

    // Verify null samplePath in KeyZone does not throw NPE when opening SwingKeyZoneMapperDialog
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();
    SynthTrackModel synth = new SynthTrackModel("MultiSynth");
    KeyZone kz = new KeyZone();
    kz.samplePath = null;
    kz.minPitch = 36;
    kz.maxPitch = 72;
    synth.getKeyZones().getOsc1Zones().add(kz);

    SwingUtilities.invokeAndWait(
        () -> {
          SwingKeyZoneMapperDialog dlg = new SwingKeyZoneMapperDialog(null, synth, 0, 0, bridge);
          try {
            SwingKeyZoneGridMap gridMap =
                new SwingKeyZoneGridMap(synth.getKeyZones().getOsc1Zones());
            AtomicInteger modCount = new AtomicInteger();
            gridMap.setOnZonesModified(modCount::incrementAndGet);

            // Clicking piano key header (y = 10 < PIANO_HEIGHT=45) must NOT fire onZonesModified
            MouseEvent pressPiano =
                new MouseEvent(
                    gridMap,
                    MouseEvent.MOUSE_PRESSED,
                    System.currentTimeMillis(),
                    0,
                    60 * 11 + 2,
                    10,
                    1,
                    false,
                    MouseEvent.BUTTON1);
            MouseEvent releasePiano =
                new MouseEvent(
                    gridMap,
                    MouseEvent.MOUSE_RELEASED,
                    System.currentTimeMillis(),
                    0,
                    60 * 11 + 2,
                    10,
                    1,
                    false,
                    MouseEvent.BUTTON1);
            for (var ml : gridMap.getMouseListeners()) {
              ml.mousePressed(pressPiano);
              ml.mouseReleased(releasePiano);
            }
            assertEquals(
                0,
                modCount.get(),
                "Clicking piano audition keys must not trigger zone reload callback");

            // Dragging a zone (y = 55 >= PIANO_HEIGHT=45, x inside [36*11, 73*11]) MUST fire
            // onZonesModified on release
            int startX = 50 * 11;
            int endX = 52 * 11;
            MouseEvent pressZone =
                new MouseEvent(
                    gridMap,
                    MouseEvent.MOUSE_PRESSED,
                    System.currentTimeMillis(),
                    0,
                    startX,
                    55,
                    1,
                    false,
                    MouseEvent.BUTTON1);
            MouseEvent dragZone =
                new MouseEvent(
                    gridMap,
                    MouseEvent.MOUSE_DRAGGED,
                    System.currentTimeMillis(),
                    MouseEvent.BUTTON1_DOWN_MASK,
                    endX,
                    55,
                    0,
                    false,
                    MouseEvent.BUTTON1);
            MouseEvent releaseZone =
                new MouseEvent(
                    gridMap,
                    MouseEvent.MOUSE_RELEASED,
                    System.currentTimeMillis(),
                    0,
                    endX,
                    55,
                    1,
                    false,
                    MouseEvent.BUTTON1);
            for (var ml : gridMap.getMouseListeners()) {
              ml.mousePressed(pressZone);
            }
            for (var mml : gridMap.getMouseMotionListeners()) {
              mml.mouseDragged(dragZone);
            }
            for (var ml : gridMap.getMouseListeners()) {
              ml.mouseReleased(releaseZone);
            }
            assertEquals(
                1, modCount.get(), "Releasing after zone drag must fire onZonesModified once");
            assertEquals(38, kz.minPitch);
            assertEquals(74, kz.maxPitch);
          } finally {
            dlg.dispose();
          }
        });

    bridge.shutdown();
  }

  @Test
  void testMasterFxDialogClampsOutOfRangeValuesAndHandlesNullApp() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();
    ProjectModel project = new ProjectModel();
    project.setReverbModel(99);
    project.setReverbRoomSize(1.8f);
    project.setReverbPan(2.5f);
    project.setReverbCompressorSyncLevel(42);

    SwingUtilities.invokeAndWait(
        () -> {
          SwingMasterFxDialog dlg = new SwingMasterFxDialog(null, project, bridge, null);
          try {
            JSlider roomSlider = findSliderByLabel(dlg.getContentPane(), "Room Size:");
            assertNotNull(roomSlider);
            assertEquals(100, roomSlider.getValue(), "Out-of-range room size must clamp to 100");
            // Moving slider with app == null must not throw NPE
            roomSlider.setValue(45);
            assertEquals(0.45f, project.getReverbRoomSize(), 1e-4f);
            assertEquals(0.45f, bridge.getGlobalFloat(BridgeContract.G_REVERB_ROOM), 1e-4f);
          } finally {
            dlg.dispose();
          }
        });

    bridge.shutdown();
  }

  @Test
  void testTransportTapTempoTimeoutAndBpmUpdate() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();
    MidiInputRouter router = new MidiInputRouter(bridge);
    MidiService midiService = new MidiService(bridge, router);
    SwingDelugeApp app = new SwingDelugeApp(bridge, midiService, true);
    try {
      TransportController tc = new TransportController(app);

      // Two taps 500 ms apart -> 120.0 BPM
      tc.recordTapAt(10_000L);
      tc.recordTapAt(10_500L);
      assertEquals(120.0f, app.getCurrentProject().getBpm(), 0.05f);
      assertEquals(120.0f, bridge.getGlobalFloat(BridgeContract.G_BPM), 0.05f);

      // A tap 1200 ms later (> 1100 ms C TAP_TEMPO_SWITCH_OFF timeout) resets the sequence, so one
      // tap alone does not recompute BPM from the old 500 ms interval
      tc.recordTapAt(11_700L);
      assertEquals(
          120.0f,
          app.getCurrentProject().getBpm(),
          0.05f,
          "Single tap after >1100ms timeout must start a fresh tap sequence");

      // Next tap 400 ms later -> 150.0 BPM
      tc.recordTapAt(12_100L);
      assertEquals(150.0f, app.getCurrentProject().getBpm(), 0.05f);

      // Reset after >1100 ms, then tap 10 presses (9 intervals): first interval 400 ms, next 8
      // intervals 500 ms -> total 4400 ms over 9 intervals (avg 488.888... ms -> 122.727 BPM).
      // Hardware averages all presses since tapTempoFirstPressTime without an 8-tap window.
      tc.recordTapAt(20_000L);
      tc.recordTapAt(20_400L);
      long t = 20_400L;
      for (int i = 0; i < 8; i++) {
        t += 500L;
        tc.recordTapAt(t);
      }
      float expectedAllPressesBpm = (float) (60000.0 / (4400.0 / 9.0));
      assertEquals(
          expectedAllPressesBpm,
          app.getCurrentProject().getBpm(),
          0.02f,
          "Tap tempo must average all presses since tapTempoFirstPressTime (no 8-tap window)");

      // Reset after >1100 ms, then tap at 320 BPM (187.5 ms -> 180 ms = 333.33 BPM) to verify
      // no artificial 300 BPM clamp is applied in tapTempoButtonPress.
      tc.recordTapAt(30_000L);
      tc.recordTapAt(30_180L);
      assertEquals(
          333.33f,
          app.getCurrentProject().getBpm(),
          0.1f,
          "Tap tempo must not clamp BPM at 300 (C playback_handler.cpp:2867-2869)");
    } finally {
      app.dispose();
      bridge.shutdown();
    }
  }

  private static JSlider findSliderByLabel(Container container, String labelText) {
    Component[] comps = container.getComponents();
    for (int i = 0; i < comps.length; i++) {
      if (comps[i] instanceof JLabel label && labelText.equals(label.getText())) {
        if (i + 1 < comps.length && comps[i + 1] instanceof Container sliderPanel) {
          for (Component sub : sliderPanel.getComponents()) {
            if (sub instanceof JSlider slider) return slider;
          }
        }
      }
      if (comps[i] instanceof Container sub) {
        JSlider found = findSliderByLabel(sub, labelText);
        if (found != null) return found;
      }
    }
    return null;
  }
}
