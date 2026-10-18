package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import javax.swing.JButton;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import org.deluge.BridgeContract;
import org.deluge.model.ArrangerClip;
import org.deluge.model.AudioTrackModel;
import org.deluge.model.ClipModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SoundDrum;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link ArrangerTimelineController}, {@link TrackInspectorDialog}, {@link
 * SwingAudioSlicerDialog}, and {@link SwingPianoRollDialog}.
 */
class ArrangerInspectorSlicerPianoRollTest {

  @Test
  void testArrangerDragAndResizePreserveAudioClip() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();
    ProjectModel project = new ProjectModel();

    AudioTrackModel audioTrack = new AudioTrackModel("VOX");
    AudioTrackModel.AudioClip audioClip = new AudioTrackModel.AudioClip();
    audioClip.setClipName("VOX_TAKE_1");
    audioClip.setLength(192);
    audioTrack.addAudioClip(audioClip);
    project.addTrack(audioTrack);

    ArrangerClip initial = new ArrangerClip(0, null, audioClip, 0, 96);
    project.addArrangerClip(initial);

    SwingUtilities.invokeAndWait(
        () -> {
          ArrangerGridPanel panel = new ArrangerGridPanel(bridge);
          panel.setProjectModel(project);
          panel.setViewMode(SwingGridPanel.GridViewMode.ARRANGEMENT);
          panel.forceRebuild();
          panel.setSize(1200, 500);
          doLayoutRecursive(panel);

          JButton col0Pad = panel.pads[0][0];
          JButton col2Pad = panel.pads[0][2];
          assertNotNull(col0Pad);
          assertNotNull(col2Pad);

          // Drag-move from column 0 to column 2
          dispatchMouse(col0Pad, MouseEvent.MOUSE_PRESSED, 0, 2, 2);
          java.awt.Point targetInCol0 =
              SwingUtilities.convertPoint(col2Pad, new java.awt.Point(5, 5), col0Pad);
          dispatchMouseDrag(col0Pad, 0, targetInCol0.x, targetInCol0.y);
          dispatchMouse(col0Pad, MouseEvent.MOUSE_RELEASED, 0, targetInCol0.x, targetInCol0.y);

          int ticksPerCol = panel.arrangerController.arrangerTicksPerColumn();

          assertEquals(1, project.getArrangerTimeline().size());
          ArrangerClip moved = project.getArrangerTimeline().get(0);
          assertSame(
              audioClip,
              moved.audioClip(),
              "Dragging an audio arranger clip must preserve its AudioClip reference");
          assertEquals(2 * ticksPerCol, moved.startTicks());

          // Shift-drag resize from column 2 to column 4
          panel.forceRebuild();
          panel.setSize(1200, 500);
          doLayoutRecursive(panel);

          JButton movedPad = panel.pads[0][2];
          JButton col4Pad = panel.pads[0][4];
          dispatchMouse(movedPad, MouseEvent.MOUSE_PRESSED, InputEvent.SHIFT_DOWN_MASK, 2, 2);
          java.awt.Point resizeInMoved =
              SwingUtilities.convertPoint(col4Pad, new java.awt.Point(5, 5), movedPad);
          dispatchMouseDrag(movedPad, InputEvent.SHIFT_DOWN_MASK, resizeInMoved.x, resizeInMoved.y);
          dispatchMouse(
              movedPad,
              MouseEvent.MOUSE_RELEASED,
              InputEvent.SHIFT_DOWN_MASK,
              resizeInMoved.x,
              resizeInMoved.y);

          ArrangerClip resized = project.getArrangerTimeline().get(0);
          assertSame(
              audioClip,
              resized.audioClip(),
              "Resizing an audio arranger clip must preserve its AudioClip reference");
          assertEquals(96 + 2 * ticksPerCol, resized.durationTicks());
        });

    bridge.shutdown();
  }

  @Test
  void testArrangerSelectionPopupListsAudioClips() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();
    ProjectModel project = new ProjectModel();

    AudioTrackModel audioTrack = new AudioTrackModel("VOX");
    AudioTrackModel.AudioClip audioClip = new AudioTrackModel.AudioClip();
    audioClip.setClipName("HOOK_LOOP");
    audioClip.setLength(192);
    audioTrack.addAudioClip(audioClip);
    project.addTrack(audioTrack);

    SwingUtilities.invokeAndWait(
        () -> {
          ArrangerGridPanel panel = new ArrangerGridPanel(bridge);
          panel.setProjectModel(project);
          panel.setViewMode(SwingGridPanel.GridViewMode.ARRANGEMENT);
          panel.forceRebuild();

          JPopupMenu menu = panel.arrangerController.buildArrangerClipSelectionPopup(0, 1);
          assertNotNull(menu);
          javax.swing.JMenuItem placeHook = null;
          for (Component comp : menu.getComponents()) {
            if (comp instanceof javax.swing.JMenuItem mi
                && "Place: HOOK_LOOP".equals(mi.getText())) {
              placeHook = mi;
              break;
            }
          }
          assertNotNull(placeHook, "Popup menu must include AudioClip placement item");
          placeHook.doClick();

          assertEquals(1, project.getArrangerTimeline().size());
          ArrangerClip placed = project.getArrangerTimeline().get(0);
          assertSame(audioClip, placed.audioClip());
          assertEquals(panel.arrangerController.arrangerTicksPerColumn(), placed.startTicks());
          assertEquals(192, placed.durationTicks());
        });

    bridge.shutdown();
  }

  @Test
  void testTrackInspectorClampsOutOfRangeSlidersAndTargetsActiveClip() throws Exception {
    SynthTrackModel synth = new SynthTrackModel("LEAD");
    synth.setVolume(1.5f); // > 1.0f
    synth.setPan(-1.4f); // < -1.0f
    synth.setFmRatio(8.0f); // > 4.0f

    ClipModel clip0 = new ClipModel("CLIP 1", 8, 16);
    clip0.setLeftCol("VELOCITY");
    ClipModel clip1 = new ClipModel("CLIP 2", 8, 16);
    clip1.setLeftCol("MOD");
    synth.addClip(clip0);
    synth.addClip(clip1);
    synth.setActiveClipIndex(1);

    java.util.List<org.deluge.model.TrackModel> tracks = new java.util.ArrayList<>();
    tracks.add(synth);

    SwingUtilities.invokeAndWait(
        () -> {
          TrackInspectorDialog dlg =
              assertDoesNotThrow(() -> new TrackInspectorDialog(null, 0, tracks, () -> {}));
          assertEquals(100, dlg.getVolume(), "Volume > 1.0 must clamp slider initial value to 100");
          assertEquals(0, dlg.getPan(), "Pan < -1.0 must clamp slider initial value to 0");
          assertEquals(
              400, dlg.getRatio(), "FM ratio > 4.0 must clamp slider initial value to 400");

          JButton cloneBtn = findButtonByText(dlg, "Clone Clip Variant");
          assertNotNull(cloneBtn);
          cloneBtn.getActionListeners()[0].actionPerformed(
              new ActionEvent(cloneBtn, ActionEvent.ACTION_PERFORMED, ""));

          assertEquals(3, synth.getClips().size());
          assertEquals(
              "CLIP 2 (variant)",
              synth.getClips().get(2).getName(),
              "Clone Clip Variant must clone the active clip (CLIP 2), not clip 0");
          dlg.dispose();
        });
  }

  @Test
  void testAudioSlicerPopulatesExactSliceCountAndRemainderFrames() {
    KitTrackModel kit = new KitTrackModel("BreakKit");
    SwingAudioSlicerDialog.populateSlicedKitDrums(
        kit,
        "SAMPLES/break.aiff",
        SwingAudioSlicerDialog.stripAudioExtension("break.aiff"),
        1003,
        4,
        0.75f,
        true);

    assertEquals(4, kit.getDrums().size(), "4 slices must create exactly 4 SoundDrums, not 16");
    SoundDrum first = (SoundDrum) kit.getDrums().get(0);
    SoundDrum last = (SoundDrum) kit.getDrums().get(3);

    assertEquals("break_SL01", first.getName());
    assertEquals(0, first.getStartSamplePos());
    assertEquals(250, first.getEndSamplePos());
    assertEquals(0.75f, first.getVolume(), 1e-5f);
    assertEquals(1, first.getMuteGroup());

    assertEquals("break_SL04", last.getName());
    assertEquals(750, last.getStartSamplePos());
    assertEquals(
        1003, last.getEndSamplePos(), "Last slice must include remainder frames up to totalFrames");
    assertEquals("break", SwingAudioSlicerDialog.stripAudioExtension("break.FLAC"));
  }

  @Test
  void testPianoRollMidiImportPreservesNoteOnZeroVelocityAndSupports8RowClip() throws Exception {
    Sequence seq = new Sequence(Sequence.PPQ, 96);
    Track track = seq.createTrack();

    // Note 1: pitch 60 at tick 0 with velocity 100, ended by NOTE_ON velocity=0 at tick 24 (step 1)
    ShortMessage noteOn1 = new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100);
    ShortMessage noteOff1 = new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 0);
    track.add(new MidiEvent(noteOn1, 0L));
    track.add(new MidiEvent(noteOff1, 24L));

    // Note 2: pitch 64 at tick 48 (step 2) with velocity 80, ended by NOTE_OFF at tick 96 (step 4)
    ShortMessage noteOn2 = new ShortMessage(ShortMessage.NOTE_ON, 0, 64, 80);
    ShortMessage noteOff2 = new ShortMessage(ShortMessage.NOTE_OFF, 0, 64, 64);
    track.add(new MidiEvent(noteOn2, 48L));
    track.add(new MidiEvent(noteOff2, 96L));

    java.util.List<SwingPianoRollDialog.MidiNoteEvent> notes =
        SwingPianoRollDialog.extractMidiNotes(seq);
    assertEquals(2, notes.size());
    assertEquals(
        100 / 127.0f,
        notes.get(0).velocity,
        1e-4f,
        "Note closed by NOTE_ON velocity=0 must keep its original NOTE_ON velocity (100/127)");
    assertEquals(80 / 127.0f, notes.get(1).velocity, 1e-4f);

    // Apply onto an 8-row clip (where 127 - 60 = 67 would be out of row bounds if not mapped)
    ClipModel clip8 = new ClipModel("GRID_CLIP", 8, 16);
    SwingPianoRollDialog.applyImportedMidiNotes(notes, clip8, null, 0);

    StepData step0 = clip8.getStep(0, 0);
    assertTrue(step0.active(), "Pitch 60 note must be stored in row 0 of an 8-row clip");
    assertEquals(60, step0.pitch());
    assertEquals(100 / 127.0f, step0.velocity(), 1e-4f);
    assertEquals(1.0f, step0.gate(), 1e-4f);

    StepData step2 = clip8.getStep(1, 2);
    assertTrue(step2.active(), "Pitch 64 note at step 2 must be stored in row 1 of an 8-row clip");
    assertEquals(64, step2.pitch());
    assertEquals(2.0f, step2.gate(), 1e-4f);
  }

  private static void doLayoutRecursive(Container root) {
    root.doLayout();
    for (Component comp : root.getComponents()) {
      if (comp instanceof Container child) {
        if (child.getWidth() == 0 || child.getHeight() == 0) {
          child.setSize(child.getPreferredSize());
        }
        doLayoutRecursive(child);
      }
    }
  }

  private static void dispatchMouse(JButton btn, int id, int modifiersEx, int x, int y) {
    MouseEvent ev =
        new MouseEvent(
            btn, id, System.currentTimeMillis(), modifiersEx, x, y, 1, false, MouseEvent.BUTTON1);
    for (var ml : btn.getMouseListeners()) {
      if (id == MouseEvent.MOUSE_PRESSED) ml.mousePressed(ev);
      else if (id == MouseEvent.MOUSE_RELEASED) ml.mouseReleased(ev);
    }
  }

  private static void dispatchMouseDrag(JButton btn, int modifiersEx, int x, int y) {
    MouseEvent ev =
        new MouseEvent(
            btn,
            MouseEvent.MOUSE_DRAGGED,
            System.currentTimeMillis(),
            modifiersEx | InputEvent.BUTTON1_DOWN_MASK,
            x,
            y,
            0,
            false,
            MouseEvent.BUTTON1);
    for (var mml : btn.getMouseMotionListeners()) {
      mml.mouseDragged(ev);
    }
  }

  private static JButton findButtonByText(Container root, String text) {
    for (Component comp : root.getComponents()) {
      if (comp instanceof JButton btn && text.equals(btn.getText())) {
        return btn;
      }
      if (comp instanceof Container child) {
        JButton found = findButtonByText(child, text);
        if (found != null) return found;
      }
    }
    return null;
  }
}
