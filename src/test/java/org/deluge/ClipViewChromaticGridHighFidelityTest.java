package org.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import javax.swing.JButton;
import org.deluge.model.ProjectModel;
import org.deluge.ui.DelugePadButton;
import org.deluge.ui.SwingDelugeApp;
import org.deluge.ui.SwingGridPanel;
import org.junit.jupiter.api.Test;

public class ClipViewChromaticGridHighFidelityTest {

  @Test
  public void testChromaticGridAndScaleHighlighting() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();

    // Boot exact app workstation
    SwingDelugeApp app = new SwingDelugeApp(bridge, null);
    ProjectModel project = ProjectModel.createDefaultProject();
    app.loadProject(project);

    SwingGridPanel gridPanel = app.getClipPanel();
    assertNotNull(gridPanel, "Grid panel must be initialized");

    // Explicitly disable scale mode to force chromatic mode for this chromatic test!
    gridPanel.setScaleModeEnabled(false);
    gridPanel.refresh();

    // 1. Verify Chromatic Layout of Note Rows on Boot (scrollOffset = 67)
    // Visual Row index 0 to 7 (modelRow = 67 to 74)
    // Row 1 (visual 0, modelRow 67): pitch 60 -> C4
    // Row 2 (visual 1, modelRow 68): pitch 59 -> B3
    // Row 3 (visual 2, modelRow 69): pitch 58 -> A#3 (accidental)
    // Row 4 (visual 3, modelRow 70): pitch 57 -> A3
    // Row 5 (visual 4, modelRow 71): pitch 56 -> G#3 (accidental)
    // Row 6 (visual 5, modelRow 72): pitch 55 -> G3
    // Row 7 (visual 6, modelRow 73): pitch 54 -> F#3 (accidental)
    // Row 8 (visual 7, modelRow 74): pitch 53 -> F3

    // Verify row pitches
    assertEquals(60, gridPanel.getRowPitch(67), "Row 1 (visual 0) pitch must be C4 (60)");
    assertEquals(59, gridPanel.getRowPitch(68), "Row 2 (visual 1) pitch must be B3 (59)");
    assertEquals(58, gridPanel.getRowPitch(69), "Row 3 (visual 2) pitch must be A#3 (58)");
    assertEquals(53, gridPanel.getRowPitch(74), "Row 8 (visual 7) pitch must be F3 (53)");

    // Verify audition column note names
    JButton audRow1 = gridPanel.getPadButtons()[0][17]; // Row 1
    JButton audRow2 = gridPanel.getPadButtons()[1][17]; // Row 2
    JButton audRow3 = gridPanel.getPadButtons()[2][17]; // Row 3
    JButton audRow8 = gridPanel.getPadButtons()[7][17]; // Row 8

    assertEquals("C4", audRow1.getText(), "Row 1 audition text must be C4");
    assertEquals("B3", audRow2.getText(), "Row 2 audition text must be B3");
    assertEquals("A#3", audRow3.getText(), "Row 3 audition text must be A#3");
    assertEquals("F3", audRow8.getText(), "Row 8 audition text must be F3");

    // 2. Verify Audition Pad Octave Colors on Boot
    // Only root notes (Cs, pitch % 12 == 0) are highlighted in the audition column!
    // Since C4 (Row 1) is a root note, it should be lit (hue-shifted color).
    // F3 (Row 8) and A#3 (Row 3) are not root notes, so they must be completely black/unlit!
    if (audRow1 instanceof DelugePadButton pad1) {
      Color c4Color = pad1.getBaseColor();
      assertNotEquals(Color.BLACK, c4Color, "C4 audition pad must be lit (not black)");

      // Verify Row 3 (A#3) is black/unlit
      if (audRow3 instanceof DelugePadButton pad3) {
        assertEquals(Color.BLACK, pad3.getBaseColor(), "A#3 audition pad must be black/unlit");
      }

      // Verify Row 8 (F3) is black/unlit
      if (audRow8 instanceof DelugePadButton pad8) {
        assertEquals(Color.BLACK, pad8.getBaseColor(), "F3 audition pad must be black/unlit");
      }
    }

    // 3. Verify Scale Highlighting / Accidental Blackout on Empty Step Pads
    // Row 3 (A#3) is an accidental/out-of-scale note, so all its empty step pads (col 0..15) must
    // be Color.BLACK!
    // Row 1 (C4) is in the scale, so its empty step pads must NOT be Color.BLACK (they should use
    // the default theme charcoal/slate bg).
    JButton stepC4 = gridPanel.getPadButtons()[0][0]; // Row 1 Col 1
    JButton stepASharp3 = gridPanel.getPadButtons()[2][0]; // Row 3 Col 1 (A#3)

    if (stepC4 instanceof DelugePadButton padC4) {
      assertNotEquals(Color.BLACK, padC4.getBaseColor(), "C4 empty step pad must be lit/visible");
    }
    if (stepASharp3 instanceof DelugePadButton padASharp) {
      assertEquals(
          Color.BLACK,
          padASharp.getBaseColor(),
          "A#3 empty step pad must be completely black/unlit");
    }

    // 4. Verify Active Step Colors match Audition Column row colors
    // In our implementation, when active, steps are colored using the row's hue-shifted color
    // getGridNoteColor(modelRow).
    // Let's verify this for Row 1 (C4).
    Color c4RowColor = gridPanel.getGridNoteColor(67);
    if (audRow1 instanceof DelugePadButton pad1) {
      assertEquals(
          c4RowColor,
          pad1.getBaseColor(),
          "C4 audition pad color must match its row's hue-shifted color");
    }

    // 5. Verify Scrolling Updates Column 18 Octave Colors dynamically
    // If we scroll down by 12 semitones, the pitch of Row 1 will become C3 (48).
    // Let's scroll down to set scrollOffset to 79 (67 + 12).
    gridPanel.setScrollOffset(79);
    gridPanel.refresh();

    // Re-retrieve button reference as SwingGridPanel.rebuildUIComponents() instantiates new button
    // instances!
    audRow1 = gridPanel.getPadButtons()[0][17];

    // Verify row pitches after scrolling
    assertEquals(48, gridPanel.getRowPitch(79), "Row 1 (visual 0) pitch must now be C3 (48)");
    assertEquals("C3", audRow1.getText(), "Row 1 audition text must now be C3");

    // Row 1 (now C3) is a root note, so it must be lit with the track's hue-shifted color for C3.
    if (audRow1 instanceof DelugePadButton pad1) {
      Color c3Color = pad1.getBaseColor();
      assertNotEquals(Color.BLACK, c3Color, "C3 audition pad must be lit (not black)");
      assertEquals(
          gridPanel.getGridNoteColor(79),
          c3Color,
          "C3 audition pad color must match the C3 row color");
    }

    bridge.shutdown();
  }

  @Test
  public void testExactRGBColorParityForGreenTrack() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();

    SwingDelugeApp app = new SwingDelugeApp(bridge, null);
    ProjectModel project = ProjectModel.createDefaultProject();

    // Explicitly set track color to Green
    project.getTracks().get(0).setColourHex("0x0000FF00");
    app.loadProject(project);

    SwingGridPanel gridPanel = app.getClipPanel();
    assertNotNull(gridPanel, "Grid panel must be initialized");

    // Explicitly enable scale mode (diatonic mode) as on boot!
    gridPanel.setScaleModeEnabled(true);
    gridPanel.refresh();

    // Verify row pitches on boot (Diatonic C Major)
    // Row 1 (visual 0, modelRow 67) = C4 (60)
    // Row 8 (visual 7, modelRow 74) = C3 (48)
    assertEquals(60, gridPanel.getRowPitch(67), "Row 1 pitch must be C4");
    assertEquals(48, gridPanel.getRowPitch(74), "Row 8 pitch must be C3");

    // Verify audition pads
    JButton audRow1 = gridPanel.getPadButtons()[0][17]; // Row 1 (C4)
    JButton audRow8 = gridPanel.getPadButtons()[7][17]; // Row 8 (C3)

    assertEquals("C4", audRow1.getText());
    assertEquals("C3", audRow8.getText());

    // C4 must be lit as Cyan/Teal (Blue)
    if (audRow1 instanceof DelugePadButton pad1) {
      Color c4Color = pad1.getBaseColor();
      assertEquals(
          new Color(0, 255, 255),
          c4Color,
          "C4 audition pad must be exactly Cyan/Teal (Blue) for a Green track");
    }

    // C3 must be lit as Blue (Purpleish-Blue)
    if (audRow8 instanceof DelugePadButton pad8) {
      Color c3Color = pad8.getBaseColor();
      assertEquals(
          new Color(0, 0, 255),
          c3Color,
          "C3 audition pad must be exactly Blue (Purpleish-Blue) for a Green track");
    }

    bridge.shutdown();
  }

  @Test
  public void testDynamicTrackColorShiftingParity() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();

    SwingDelugeApp app = new SwingDelugeApp(bridge, null);
    ProjectModel project = ProjectModel.createDefaultProject();

    // Set track color to Green (0x0000FF00)
    project.getTracks().get(0).setColourHex("0x0000FF00");
    app.loadProject(project);

    SwingGridPanel gridPanel = app.getClipPanel();
    assertNotNull(gridPanel, "Grid panel must be initialized");

    // Enable scale mode (diatonic) on boot
    gridPanel.setScaleModeEnabled(true);
    gridPanel.refresh();

    // 1. Initial State on Boot: colourOffset = 0
    // C4 (Row 1) -> Cyan/Teal (0, 255, 255)
    // C3 (Row 8) -> Blue (0, 0, 255)
    JButton audRow1 = gridPanel.getPadButtons()[0][17]; // C4
    JButton audRow8 = gridPanel.getPadButtons()[7][17]; // C3

    assertEquals("C4", audRow1.getText());
    assertEquals("C3", audRow8.getText());
    assertEquals(
        new Color(0, 255, 255),
        ((DelugePadButton) audRow1).getBaseColor(),
        "C4 must start as Cyan/Teal");
    assertEquals(
        new Color(0, 0, 255), ((DelugePadButton) audRow8).getBaseColor(), "C3 must start as Blue");

    // 2. Adjust track color offset by delta using Y-encoder shortcut!
    // Let's call adjustTrackColorOffset(8) -> shifts colourOffset by 8 * 3 = 24!
    gridPanel.adjustTrackColorOffset(8);
    gridPanel.refresh();

    // Re-retrieve button reference
    audRow8 = gridPanel.getPadButtons()[7][17];

    assertEquals(24, project.getTracks().get(0).getColourOffset(), "Track colourOffset must be 24");
    assertNotEquals(
        new Color(0, 0, 255),
        ((DelugePadButton) audRow8).getBaseColor(),
        "C3 color must have shifted");

    // 3. Shift colourOffset to exactly 48 (which transposes C3 to Red!)
    // Delta to reach 48 from 24 is (48 - 24) / 3 = 8!
    gridPanel.adjustTrackColorOffset(8);
    gridPanel.refresh();

    audRow8 = gridPanel.getPadButtons()[7][17]; // C3
    assertEquals(48, project.getTracks().get(0).getColourOffset(), "Track colourOffset must be 48");

    // Assert that C3 is now exactly RED! (0.0 Hue is Red!)
    Color c3Color = ((DelugePadButton) audRow8).getBaseColor();
    assertEquals(255, c3Color.getRed(), "C3 must shift to Red (Red channel = 255)");
    assertEquals(0, c3Color.getGreen(), "C3 must shift to Red (Green channel = 0)");
    assertEquals(0, c3Color.getBlue(), "C3 must shift to Red (Blue channel = 0)");

    bridge.shutdown();
  }

  @Test
  public void testFillModeHighlighting() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();

    SwingDelugeApp app = new SwingDelugeApp(bridge, null);
    ProjectModel project = ProjectModel.createDefaultProject();

    // 1. Load the project first
    app.loadProject(project);

    SwingGridPanel gridPanel = app.getClipPanel();
    assertNotNull(gridPanel, "Grid panel must be initialized");

    // 2. Program a note at Step 0, Row 1 (C4, modelRow 67) with a FILL condition (fill = 1.0f!)
    // using the grid's setClipStep helper to ensure correct row mapping!
    org.deluge.model.TrackModel t = project.getTracks().get(0);
    org.deluge.model.ClipModel c = t.getActiveClip();
    gridPanel.setClipStep(
        c, 67, 0, new org.deluge.model.StepData(true, 0.8f, 0.9f, 1.0f, 60, 0, 1.0f));

    gridPanel.refresh();

    // 2. Assert that Step 0, Row 1 pad is lit with the glowing Blue FILL highlight color (0x00d2ff)
    // instead of its normal row color!
    JButton fillStepPad = gridPanel.getPadButtons()[0][0]; // Row 1 Col 1 (Step 0)
    assertTrue(((DelugePadButton) fillStepPad).isActive(), "Step 0 must be active");
    assertEquals(
        new Color(0x00, 0xd2, 0xff),
        ((DelugePadButton) fillStepPad).getBaseColor(),
        "Step 0 (FILL step) must be colored glowing Blue (0x00d2ff) on the grid!");

    bridge.shutdown();
  }

  @Test
  public void testNudgedNotesBlurHighlighting() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();

    SwingDelugeApp app = new SwingDelugeApp(bridge, null);
    ProjectModel project = ProjectModel.createDefaultProject();

    app.loadProject(project);

    SwingGridPanel gridPanel = app.getClipPanel();
    assertNotNull(gridPanel, "Grid panel must be initialized");

    // 1. Program a note at Step 0, Row 1 (C4, modelRow 67) with a microtiming nudge (nudge = 0.5f!)
    // and no fill condition (fill = 0.0f!)
    org.deluge.model.TrackModel t = project.getTracks().get(0);
    org.deluge.model.ClipModel c = t.getActiveClip();
    gridPanel.setClipStep(
        c, 67, 0, new org.deluge.model.StepData(true, 0.8f, 0.9f, 1.0f, 60, 0, 0.0f, 0.5f));

    gridPanel.refresh();

    // 2. Assert that Step 0, Row 1 pad is active, has isBlur() == true,
    // and its base color property remains the normal track color (while it renders in blur
    // dynamically).
    JButton nudgedStepPad = gridPanel.getPadButtons()[0][0]; // Row 1 Col 1 (Step 0)
    assertTrue(((DelugePadButton) nudgedStepPad).isActive(), "Step 0 must be active");
    assertTrue(((DelugePadButton) nudgedStepPad).isBlur(), "Step 0 must have isBlur set to true");

    Color normalC4Color = gridPanel.getGridNoteColor(67);
    assertEquals(
        normalC4Color,
        ((DelugePadButton) nudgedStepPad).getBaseColor(),
        "Step 0 (nudged step) baseColor property must remain the normal track color!");

    bridge.shutdown();
  }

  @Test
  public void testTrackTranspositionGesture() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();

    SwingDelugeApp app = new SwingDelugeApp(bridge, null);
    ProjectModel project = ProjectModel.createDefaultProject();
    app.loadProject(project);

    SwingGridPanel gridPanel = app.getClipPanel();
    assertNotNull(gridPanel, "Grid panel must be initialized");
    gridPanel.setScaleModeEnabled(false); // Enable chromatic mode for linear semitone mapping!

    org.deluge.model.TrackModel t = project.getTracks().get(0);
    org.deluge.model.ClipModel c = t.getActiveClip();

    // 1. Program a note at Step 0, Row 67 (pitch 60, C4)
    gridPanel.setClipStep(
        c, 67, 0, new org.deluge.model.StepData(true, 0.8f, 0.9f, 1.0f, 60, 0, 0.0f, 0.0f));
    gridPanel.refresh();

    assertTrue(gridPanel.getClipStep(c, 67, 0).active(), "Original step must be active");
    assertEquals(60, gridPanel.getClipStep(c, 67, 0).pitch(), "Original pitch must be 60");

    // 2. Transpose up by 2 semitones (to D4, pitch 62, row 65!)
    gridPanel.transposeTrack(2);

    // 3. Verify that old step is cleared, and new step is active at transposed pitch!
    assertFalse(gridPanel.getClipStep(c, 67, 0).active(), "Old step must be cleared");
    assertTrue(
        gridPanel.getClipStep(c, 65, 0).active(), "Transposed step must be active at row 65");
    assertEquals(62, gridPanel.getClipStep(c, 65, 0).pitch(), "Transposed pitch must be 62 (D4)");

    // 4. Verify that the bridge is also updated!
    assertFalse(
        bridge.getStep(gridPanel.getBaseTrackId() + 67, 0), "Bridge old step must be inactive");
    assertTrue(
        bridge.getStep(gridPanel.getBaseTrackId() + 65, 0),
        "Bridge transposed step must be active");

    // 5. Transpose up by an octave (12 semitones) (to D5, pitch 74, row 53!)
    gridPanel.transposeTrack(12);

    // 6. Verify octave shift in model
    assertFalse(
        gridPanel.getClipStep(c, 65, 0).active(), "D4 step must be cleared after octave shift");
    assertTrue(gridPanel.getClipStep(c, 53, 0).active(), "D5 octave step must be active at row 53");
    assertEquals(
        74, gridPanel.getClipStep(c, 53, 0).pitch(), "Octave transposed pitch must be 74 (D5)");

    // 7. Verify octave shift in bridge
    assertFalse(
        bridge.getStep(gridPanel.getBaseTrackId() + 65, 0),
        "Bridge D4 step must be inactive after octave shift");
    assertTrue(
        bridge.getStep(gridPanel.getBaseTrackId() + 53, 0), "Bridge D5 octave step must be active");

    bridge.shutdown();
  }

  @Test
  public void testTrackDuplicationGesture() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();

    SwingDelugeApp app = new SwingDelugeApp(bridge, null);
    ProjectModel project = ProjectModel.createDefaultProject();
    app.loadProject(project);

    SwingGridPanel gridPanel = app.getClipPanel();
    assertNotNull(gridPanel, "Grid panel must be initialized");
    gridPanel.setScaleModeEnabled(false); // Enable chromatic mode for linear semitone mapping!

    org.deluge.model.TrackModel t = project.getTracks().get(0);
    org.deluge.model.ClipModel c = t.getActiveClip();

    // 1. Program a note at Step 0, Row 67 (pitch 60, C4) with original length = 16 steps
    gridPanel.setClipStep(
        c, 67, 0, new org.deluge.model.StepData(true, 0.8f, 0.9f, 1.0f, 60, 0, 0.0f, 0.0f));
    gridPanel.refresh();

    assertEquals(16, c.getStepCount(), "Original length must be 16");

    // 2. Duplicate/double length!
    gridPanel.duplicateTrackContent();

    // 3. Verify that length doubled to 32 steps!
    assertEquals(32, c.getStepCount(), "Length must double to 32");
    assertEquals(
        32,
        bridge.getTrackLength(gridPanel.getBaseTrackId()),
        "Bridge track length must double to 32");

    // 4. Verify that the note at Step 0 was duplicated to Step 16!
    assertTrue(
        gridPanel.getClipStep(c, 67, 16).active(), "Duplicated step at index 16 must be active");
    assertEquals(60, gridPanel.getClipStep(c, 67, 16).pitch(), "Duplicated step pitch must be 60");
    assertTrue(
        bridge.getStep(gridPanel.getBaseTrackId() + 67, 16),
        "Bridge duplicated step must be active");

    bridge.shutdown();
  }

  @Test
  public void testMetronomeToggleGesture() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();

    SwingDelugeApp app = new SwingDelugeApp(bridge, null, true);
    try {
      ProjectModel project = ProjectModel.createDefaultProject();
      app.loadProject(project);

      Object eng = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
      assertNotNull(eng, "Audio engine must be initialized");
      org.deluge.engine.FirmwareAudioEngine engine = (org.deluge.engine.FirmwareAudioEngine) eng;

      // Default metronome should be disabled
      assertFalse(engine.metronomeEnabled, "Metronome should default to disabled");

      // Simulate Shift + T key press with the standard old Shift mask (which JDK maps to
      // getModifiersEx() SHIFT_DOWN_MASK)
      java.awt.event.KeyEvent shiftT =
          new java.awt.event.KeyEvent(
              app,
              java.awt.event.KeyEvent.KEY_PRESSED,
              System.currentTimeMillis(),
              java.awt.event.InputEvent.SHIFT_MASK,
              java.awt.event.KeyEvent.VK_T,
              'T');

      // Invoke all registered key listeners to ensure our app handler gets triggered
      for (java.awt.event.KeyListener kl : app.getKeyListeners()) {
        kl.keyPressed(shiftT);
      }

      // Assert that the metronome is now enabled!
      assertTrue(engine.metronomeEnabled, "Metronome must be enabled after Shift+T press!");

      // Simulate Shift + T key press again
      for (java.awt.event.KeyListener kl : app.getKeyListeners()) {
        kl.keyPressed(shiftT);
      }

      // Assert that the metronome is toggled back to disabled!
      assertFalse(engine.metronomeEnabled, "Metronome must toggle back to disabled!");
    } finally {
      app.dispose();
      bridge.shutdown();
    }
  }

  @Test
  public void testGesturalPadModifiers() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();

    SwingDelugeApp app = new SwingDelugeApp(bridge, null, true);
    try {
      ProjectModel project = ProjectModel.createDefaultProject();
      app.loadProject(project);

      SwingGridPanel gridPanel = app.getClipPanel();
      assertNotNull(gridPanel, "Grid panel must be initialized");
      gridPanel.setScaleModeEnabled(false); // Enable chromatic mode for linear mapping!

      org.deluge.model.TrackModel t = project.getTracks().get(0);
      org.deluge.model.ClipModel c = t.getActiveClip();

      // 1. Program a note at Step 0, Row 67 (pitch 60, C4)
      // Initial velocity = 0.8f, gate = 1.0f, probability = 0.9f
      gridPanel.setClipStep(
          c, 67, 0, new org.deluge.model.StepData(true, 0.8f, 1.0f, 0.9f, 60, 0, 0.0f, 0.0f));
      gridPanel.refresh();

      assertTrue(gridPanel.getClipStep(c, 67, 0).active(), "Original step must be active");

      // Dynamically calculate the visual row for model row 67 based on the panel's scroll offset
      int visualRow = 67 - gridPanel.getScrollOffset();

      // 2. Test Velocity Adjustment (No Modifiers)
      // Scroll down (rotation = 1 -> dir = -1) -> velocity should decrease by 0.05f to 0.75f
      java.awt.event.MouseWheelEvent scrollDownVel =
          new java.awt.event.MouseWheelEvent(
              gridPanel,
              java.awt.event.MouseEvent.MOUSE_WHEEL,
              System.currentTimeMillis(),
              0, // no modifiers
              0,
              0,
              0,
              false,
              java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL,
              1,
              1);
      gridPanel.handlePadMouseWheel(visualRow, 0, scrollDownVel);
      assertEquals(
          0.75f,
          gridPanel.getClipStep(c, 67, 0).velocity(),
          0.001f,
          "Velocity must decrease to 0.75");
      assertEquals(
          0.75f,
          bridge.getVelocity(gridPanel.getBaseTrackId() + 67, 0),
          0.001f,
          "Bridge velocity must sync to 0.75");

      // Scroll up (rotation = -2 -> dir = 2) -> velocity should increase by 0.10f to 0.85f
      java.awt.event.MouseWheelEvent scrollUpVel =
          new java.awt.event.MouseWheelEvent(
              gridPanel,
              java.awt.event.MouseEvent.MOUSE_WHEEL,
              System.currentTimeMillis(),
              0,
              0,
              0,
              0,
              false,
              java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL,
              1,
              -2);
      gridPanel.handlePadMouseWheel(visualRow, 0, scrollUpVel);
      assertEquals(
          0.85f,
          gridPanel.getClipStep(c, 67, 0).velocity(),
          0.001f,
          "Velocity must increase to 0.85");

      // 3. Test Probability Adjustment (Shift Held)
      gridPanel.setShiftHeld(true);
      // Scroll down (rotation = 1 -> dir = -1) -> probability should decrease by 0.05f from 0.9f to
      // 0.85f
      java.awt.event.MouseWheelEvent scrollDownProb =
          new java.awt.event.MouseWheelEvent(
              gridPanel,
              java.awt.event.MouseEvent.MOUSE_WHEEL,
              System.currentTimeMillis(),
              java.awt.event.InputEvent.SHIFT_DOWN_MASK,
              0,
              0,
              0,
              false,
              java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL,
              1,
              1);
      gridPanel.handlePadMouseWheel(visualRow, 0, scrollDownProb);
      assertEquals(
          0.85f,
          gridPanel.getClipStep(c, 67, 0).probability(),
          0.001f,
          "Probability must decrease to 0.85");
      assertEquals(
          0.85f,
          bridge.getStepProbability(gridPanel.getBaseTrackId() + 67, 0),
          0.001f,
          "Bridge probability must sync to 0.85");
      gridPanel.setShiftHeld(false);

      // 4. Test Gate Length Adjustment (Alt Held)
      // Scroll up (rotation = -1 -> dir = 1) -> gate should increase by 0.25f from 1.0f to 1.25f
      java.awt.event.MouseWheelEvent scrollUpGate =
          new java.awt.event.MouseWheelEvent(
              gridPanel,
              java.awt.event.MouseEvent.MOUSE_WHEEL,
              System.currentTimeMillis(),
              java.awt.event.InputEvent.ALT_DOWN_MASK,
              0,
              0,
              0,
              false,
              java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL,
              1,
              -1);
      gridPanel.handlePadMouseWheel(visualRow, 0, scrollUpGate);
      assertEquals(
          1.25f, gridPanel.getClipStep(c, 67, 0).gate(), 0.001f, "Gate must increase to 1.25");
      assertEquals(
          1.25f,
          bridge.getGate(gridPanel.getBaseTrackId() + 67, 0),
          0.001f,
          "Bridge gate must sync to 1.25");
    } finally {
      app.dispose();
      bridge.shutdown();
    }
  }

  @Test
  public void testGesturalCopyPasteShortcut() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();

    SwingDelugeApp app = new SwingDelugeApp(bridge, null, true);
    try {
      ProjectModel project = ProjectModel.createDefaultProject();
      app.loadProject(project);

      SwingGridPanel gridPanel = app.getClipPanel();
      assertNotNull(gridPanel, "Grid panel must be initialized");
      gridPanel.setScaleModeEnabled(false);

      org.deluge.model.TrackModel t = project.getTracks().get(0);
      org.deluge.model.ClipModel c = t.getActiveClip();

      // 1. Program a note at Step 0, Row 67 (pitch 60, C4)
      gridPanel.setClipStep(
          c, 67, 0, new org.deluge.model.StepData(true, 0.8f, 1.0f, 0.9f, 60, 0, 0.0f, 0.0f));
      gridPanel.refresh();

      assertTrue(gridPanel.getClipStep(c, 67, 0).active(), "Original step must be active");

      // 2. Simulate Ctrl + Shift + C (Copy notes)
      java.awt.event.KeyEvent copyEvent =
          new java.awt.event.KeyEvent(
              app,
              java.awt.event.KeyEvent.KEY_PRESSED,
              System.currentTimeMillis(),
              java.awt.event.InputEvent.CTRL_DOWN_MASK
                  | java.awt.event.InputEvent.SHIFT_DOWN_MASK
                  | java.awt.event.InputEvent.CTRL_MASK
                  | java.awt.event.InputEvent.SHIFT_MASK,
              java.awt.event.KeyEvent.VK_C,
              'C');
      for (java.awt.event.KeyListener kl : app.getKeyListeners()) {
        kl.keyPressed(copyEvent);
      }

      // 3. Clear the note from the grid
      gridPanel.setClipStep(c, 67, 0, org.deluge.model.StepData.empty());
      gridPanel.refresh();
      assertFalse(
          gridPanel.getClipStep(c, 67, 0).active(), "Step must be cleared after setting to empty");

      // 4. Simulate Ctrl + Shift + V (Paste notes)
      java.awt.event.KeyEvent pasteEvent =
          new java.awt.event.KeyEvent(
              app,
              java.awt.event.KeyEvent.KEY_PRESSED,
              System.currentTimeMillis(),
              java.awt.event.InputEvent.CTRL_DOWN_MASK
                  | java.awt.event.InputEvent.SHIFT_DOWN_MASK
                  | java.awt.event.InputEvent.CTRL_MASK
                  | java.awt.event.InputEvent.SHIFT_MASK,
              java.awt.event.KeyEvent.VK_V,
              'V');
      for (java.awt.event.KeyListener kl : app.getKeyListeners()) {
        kl.keyPressed(pasteEvent);
      }

      // 5. Assert that the note has been perfectly restored and synchronized to the audio engine!
      org.deluge.model.StepData restored = gridPanel.getClipStep(c, 67, 0);
      assertTrue(restored.active(), "Step must be active after paste");
      assertEquals(0.8f, restored.velocity(), 0.001f, "Velocity must be restored to 0.8");
      assertEquals(1.0f, restored.gate(), 0.001f, "Gate must be restored to 1.0");
      assertEquals(0.9f, restored.probability(), 0.001f, "Probability must be restored to 0.9");

      // Verify audio bridge synchronization
      assertEquals(
          0.8f,
          bridge.getVelocity(gridPanel.getBaseTrackId() + 67, 0),
          0.001f,
          "Bridge velocity must sync after paste");
      assertEquals(
          1.0f,
          bridge.getGate(gridPanel.getBaseTrackId() + 67, 0),
          0.001f,
          "Bridge gate must sync after paste");
    } finally {
      app.dispose();
      bridge.shutdown();
    }
  }

  @Test
  public void testVerticalOctaveScroll() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();

    SwingDelugeApp app = new SwingDelugeApp(bridge, null, true);
    try {
      ProjectModel project = ProjectModel.createDefaultProject();
      app.loadProject(project);

      SwingGridPanel gridPanel = app.getClipPanel();
      assertNotNull(gridPanel, "Grid panel must be initialized");

      int initialOffset = gridPanel.getScrollOffset();
      System.out.println("[TEST-SCROLL] Initial scroll offset: " + initialOffset);

      // 1. Scroll vertically by an octave (12 rows) down
      gridPanel.scrollVertically(12);
      int offsetAfterScrollDown = gridPanel.getScrollOffset();
      System.out.println("[TEST-SCROLL] Offset after scroll down 12: " + offsetAfterScrollDown);
      assertEquals(
          initialOffset + 12,
          offsetAfterScrollDown,
          "Scroll offset must increase by exactly 12 rows (one octave)");

      // 2. Scroll vertically by an octave (12 rows) up
      gridPanel.scrollVertically(-12);
      int offsetAfterScrollUp = gridPanel.getScrollOffset();
      System.out.println("[TEST-SCROLL] Offset after scroll up 12: " + offsetAfterScrollUp);
      assertEquals(
          initialOffset, offsetAfterScrollUp, "Scroll offset must return to the initial offset");

      // 3. Scroll beyond bounds (say, 200 rows up) -> should clamp to 0
      gridPanel.scrollVertically(-200);
      assertEquals(
          0, gridPanel.getScrollOffset(), "Scroll offset must clamp to 0 at the lower boundary");
    } finally {
      app.dispose();
      bridge.shutdown();
    }
  }

  @Test
  public void testGesturalNotePitchTransposition() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();

    SwingDelugeApp app = new SwingDelugeApp(bridge, null, true);
    try {
      ProjectModel project = ProjectModel.createDefaultProject();
      app.loadProject(project);

      SwingGridPanel gridPanel = app.getClipPanel();
      assertNotNull(gridPanel, "Grid panel must be initialized");
      gridPanel.setScaleModeEnabled(false);

      org.deluge.model.TrackModel t = project.getTracks().get(0);
      org.deluge.model.ClipModel c = t.getActiveClip();

      // 1. Program a note at Step 0, Row 67 (pitch 60, C4)
      gridPanel.setClipStep(
          c, 67, 0, new org.deluge.model.StepData(true, 0.8f, 1.0f, 0.9f, 60, 0, 0.0f, 0.0f));
      gridPanel.refresh();

      assertTrue(gridPanel.getClipStep(c, 67, 0).active(), "Original step must be active");

      // Dynamically calculate the visual row for model row 67 based on the panel's scroll offset
      int visualRow = 67 - gridPanel.getScrollOffset();

      // 2. Simulate Ctrl + Scroll Up 2 ticks (rotation = -2 -> dir = 2)
      // This should transpose the note pitch by +2 semitones to pitch 62 (D4, model row 65)
      java.awt.event.MouseWheelEvent scrollUpPitchEvent =
          new java.awt.event.MouseWheelEvent(
              gridPanel,
              java.awt.event.MouseEvent.MOUSE_WHEEL,
              System.currentTimeMillis(),
              java.awt.event.InputEvent.CTRL_DOWN_MASK | java.awt.event.InputEvent.CTRL_MASK,
              0,
              0,
              0,
              false,
              java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL,
              1,
              -2);
      gridPanel.handlePadMouseWheel(visualRow, 0, scrollUpPitchEvent);

      // 3. Assert note pitch migration in memory
      assertFalse(gridPanel.getClipStep(c, 67, 0).active(), "Old step at row 67 must be cleared");
      org.deluge.model.StepData transposed = gridPanel.getClipStep(c, 65, 0);
      assertTrue(transposed.active(), "New step at row 65 must be active");
      assertEquals(62, transposed.pitch(), "Pitch must be transposed to 62");
      assertEquals(0.8f, transposed.velocity(), 0.001f, "Velocity must be preserved");
      assertEquals(1.0f, transposed.gate(), 0.001f, "Gate must be preserved");

      // 4. Assert note channel migration and parameter synchronization in the ChucK DSP bridge
      assertFalse(
          bridge.getStep(gridPanel.getBaseTrackId() + 67, 0), "Bridge old step must be cleared");
      assertTrue(
          bridge.getStep(gridPanel.getBaseTrackId() + 65, 0), "Bridge new step must be active");
      assertEquals(
          0.8f,
          bridge.getVelocity(gridPanel.getBaseTrackId() + 65, 0),
          0.001f,
          "Bridge velocity must sync to new channel");
      assertEquals(
          1.0f,
          bridge.getGate(gridPanel.getBaseTrackId() + 65, 0),
          0.001f,
          "Bridge gate must sync to new channel");
    } finally {
      app.dispose();
      bridge.shutdown();
    }
  }

  @Test
  public void testDynamicTripletNoteRealignment() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();

    SwingDelugeApp app = new SwingDelugeApp(bridge, null, true);
    try {
      ProjectModel project = ProjectModel.createDefaultProject();
      app.loadProject(project);

      SwingGridPanel gridPanel = app.getClipPanel();
      org.deluge.model.TrackModel t = project.getTracks().get(0);
      org.deluge.model.ClipModel c = t.getActiveClip();

      // 1. Program notes at Step 1 and Step 3 in straight 16-step mode
      // Pos 1 should be at 1 * 24 = 24 ticks
      // Pos 3 should be at 3 * 24 = 72 ticks
      gridPanel.setClipStep(
          c, 60, 1, new org.deluge.model.StepData(true, 0.8f, 0.5f, 1.0f, 60, 0, 0.0f, 0.0f));
      gridPanel.setClipStep(
          c, 60, 3, new org.deluge.model.StepData(true, 0.8f, 0.5f, 1.0f, 60, 0, 0.0f, 0.0f));

      // Verify straight tick positions in noteRows
      assertEquals(1, c.getNoteRowsList().size(), "Should have exactly 1 note row");
      org.deluge.model.NoteRowModel row = c.getNoteRowsList().get(0);
      assertEquals(2, row.getNotes().size(), "Should have exactly 2 notes");

      org.deluge.model.NoteModel note1 = row.getNotes().get(0);
      org.deluge.model.NoteModel note2 = row.getNotes().get(1);

      int posA = Math.min(note1.getPos(), note2.getPos());
      int posB = Math.max(note1.getPos(), note2.getPos());
      assertEquals(24, posA, "First note straight tick position must be 24");
      assertEquals(72, posB, "Second note straight tick position must be 72");

      // 2. Toggle triplet mode to active (12 steps, 32 ticks per step)
      c.setTripletMode(true);
      c.setStepCount(12);
      c.rebuildNotesFromGrid();

      // Verify notes are re-aligned to 32 ticks per step
      assertEquals(2, row.getNotes().size(), "Should still have 2 notes");
      note1 = row.getNotes().get(0);
      note2 = row.getNotes().get(1);
      posA = Math.min(note1.getPos(), note2.getPos());
      posB = Math.max(note1.getPos(), note2.getPos());
      assertEquals(32, posA, "First note triplet tick position must be 32 (1 * 32)");
      assertEquals(96, posB, "Second note triplet tick position must be 96 (3 * 32)");

      // 3. Toggle triplet mode back to inactive (16 steps, 24 ticks per step)
      c.setTripletMode(false);
      c.setStepCount(16);
      c.rebuildNotesFromGrid();

      // Verify notes are re-aligned back to 24 ticks per step
      assertEquals(2, row.getNotes().size(), "Should still have 2 notes");
      note1 = row.getNotes().get(0);
      note2 = row.getNotes().get(1);
      posA = Math.min(note1.getPos(), note2.getPos());
      posB = Math.max(note1.getPos(), note2.getPos());
      assertEquals(24, posA, "First note straight tick position must be restored to 24");
      assertEquals(72, posB, "Second note straight tick position must be restored to 72");
    } finally {
      app.dispose();
      bridge.shutdown();
    }
  }
}
