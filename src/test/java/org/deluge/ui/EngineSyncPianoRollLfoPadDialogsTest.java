package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.GraphicsEnvironment;
import javax.swing.SwingUtilities;
import org.deluge.BridgeContract;
import org.deluge.model.ClipModel;
import org.deluge.model.FilterMode;
import org.deluge.model.Iterance;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.project.PreferencesManager;
import org.deluge.shadow.core.ChuckArray;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class EngineSyncPianoRollLfoPadDialogsTest {

  @Test
  void engineSyncCoordinatorPreservesSynthResonanceAndMapsModFxAndOscOrdinals() {
    BridgeContract bridge = new BridgeContract();
    ChuckArray oscTypeArr = new ChuckArray(new long[BridgeContract.TRACKS]);
    bridge.setGlobalObject(BridgeContract.G_OSC_TYPE, oscTypeArr);
    EngineSyncCoordinator sync = new EngineSyncCoordinator(null, bridge);

    ProjectModel project = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("Lead");
    synth.setLpfFreq(5000.0f);
    synth.setLpfRes(0.75f); // 0..1 normalized
    synth.setOsc1Type("ANALOG_SQUARE");
    synth.addClip(new ClipModel("Clip 1", 1, 16));
    project.addTrack(synth);

    sync.pushModelToBridge(project);

    assertEquals(0.25, bridge.getTrackFilterFreq(0), 1e-4);
    assertEquals(
        0.75,
        bridge.getTrackFilterRes(0),
        1e-4,
        "SynthTrackModel.getLpfRes() is already 0..1 and must not be divided by 100");
    assertEquals(2L, oscTypeArr.getInt(0), "ANALOG_SQUARE should map to square osc ordinal 2");

    synth.setOsc1Type("NOISE");
    sync.pushModelToBridge(project);
    assertEquals(4L, oscTypeArr.getInt(0), "NOISE should map to noise osc ordinal 4");

    assertEquals(0, EngineSyncCoordinator.modFxTypeOrdinal("none"));
    assertEquals(1, EngineSyncCoordinator.modFxTypeOrdinal("chorus"));
    assertEquals(2, EngineSyncCoordinator.modFxTypeOrdinal("flanger"));
    assertEquals(3, EngineSyncCoordinator.modFxTypeOrdinal("phaser"));
    assertEquals(4, EngineSyncCoordinator.modFxTypeOrdinal("StereoChorus"));
    assertEquals(4, EngineSyncCoordinator.modFxTypeOrdinal("CHORUS_STEREO"));
    assertEquals(5, EngineSyncCoordinator.modFxTypeOrdinal("TapeWarble"));
    assertEquals(5, EngineSyncCoordinator.modFxTypeOrdinal("WARBLE"));
    assertEquals(6, EngineSyncCoordinator.modFxTypeOrdinal("dimension"));
    assertEquals(7, EngineSyncCoordinator.modFxTypeOrdinal("grainFX"));
    assertEquals(7, EngineSyncCoordinator.modFxTypeOrdinal("GRAIN"));

    assertEquals(1, EngineSyncCoordinator.oscTypeOrdinal("analogSaw"));
    assertEquals(1, EngineSyncCoordinator.oscTypeOrdinal("ANALOG_SAW_2"));
    assertEquals(2, EngineSyncCoordinator.oscTypeOrdinal("analogSquare"));
    assertEquals(2, EngineSyncCoordinator.oscTypeOrdinal("ANALOG_SQUARE"));
  }

  @Test
  void pianoRollBlackQwertyLabelsAndNoteHitTestAreAligned() throws Exception {
    assertArrayEquals(
        new String[] {"S", "D", "G", "H", "J"},
        PianoRollComponent.BLACK_QWERTY,
        "First 5 black keys in BLACK_KEY_OFFSETS are C#4, D#4, F#4, G#4, A#4");

    PreferencesManager.GridMode savedMode = PreferencesManager.getGridMode();
    BridgeContract bridge = new BridgeContract();
    try {
      PreferencesManager.setGridMode(PreferencesManager.GridMode.GRID_8x16);
      SwingGridPanel grid = new ClipGridPanel(bridge);
      grid.setSize(960, 400);
      // Drain the queued componentResized event on the EDT before fixing test layout metrics.
      SwingUtilities.invokeAndWait(() -> {});
      grid.cachedPadSz = 35;
      grid.columnCount = 18; // 16 step columns + 2 utility columns

      PianoRollComponent piano = new PianoRollComponent(grid);
      piano.setSize(960, 80);

      // lw = Math.max(60, Math.min(140, 960 / 12)) = 80; gridX = 80 + 91 = 171
      // totalWidth = 16 * 40 - 5 = 635; keyW = 635 / 28.0 = 22.6785...
      assertEquals(60, piano.noteAtPoint(175, 50), "Bottom half of first white key is C4 (60)");
      assertEquals(61, piano.noteAtPoint(188, 10), "Top boundary between C4 and D4 is C#4 (61)");
      assertEquals(-1, piano.noteAtPoint(10, 10), "Left of gridX is outside keyboard");
      assertEquals(-1, piano.noteAtPoint(175, 75), "Below keyH (70) is outside keyboard");
    } finally {
      PreferencesManager.setGridMode(savedMode);
      bridge.shutdown();
    }
  }

  @Test
  void lfoDrawPadReservesBottomLabelPaddingAndSyncsFromModel() {
    SynthTrackModel model = new SynthTrackModel("Pad");
    int[] wave = model.getCustomLfoWave();
    for (int i = 0; i < 16; i++) {
      wave[i * 16] = (int) (((i / 15.0f) * 2.0f - 1.0f) * 1073741824.0f);
    }

    LfoDrawPad pad = new LfoDrawPad(model, null);
    pad.setSize(340, 140);

    int h = pad.getHeight();
    int baseY = LfoDrawPad.PADDING_TOP + (h - LfoDrawPad.PADDING_TOP - LfoDrawPad.PADDING_BOTTOM);
    assertTrue(baseY + 12 < h, "Step number labels at baseY + 12 must fit inside component height");

    assertEquals(-1.0f, pad.getStepValue(0), 1e-3f);
    assertEquals(1.0f, pad.getStepValue(15), 1e-3f);

    // Mutate model wave externally and verify syncFromModel() updates and clamps steps
    wave[0] = Integer.MAX_VALUE;
    pad.syncFromModel();
    assertEquals(1.0f, pad.getStepValue(0), 1e-4f);
  }

  @Test
  void delugeHwStatusPanelStopsTimersOnRemoveNotifyAndHandlesNullService() {
    DelugeHwStatusPanel panel = new DelugeHwStatusPanel(null);
    assertTrue(panel.isTimersRunning(), "Timers start on construction");

    assertDoesNotThrow(panel::triggerPingTest);

    panel.removeNotify();
    assertFalse(panel.isTimersRunning(), "removeNotify() must stop both background timers");
  }

  @Test
  void stepPropertiesDialogClampsOutOfRangeValuesAndMasksCustomIterance() {
    Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Requires AWT Frame/Dialog");

    StepPropertiesDialog dialog =
        new StepPropertiesDialog(
            null, 150, 9, -20, 200, 0.0, 120, new Iterance((byte) 3, (byte) 0xFF));
    try {
      assertEquals(100, dialog.getVelocity());
      assertEquals(3, dialog.getIterance());
      assertEquals(0, dialog.getFill());
      assertEquals(100, dialog.getProbability());
      assertEquals(0.01, dialog.getGate(), 1e-6);
      assertEquals(99, dialog.getNudge());
    } finally {
      dialog.dispose();
    }
  }

  @Test
  void synthParamRackMapsDriveTo24dBAndSyncsBridge() {
    assertEquals(0, SynthParamRack.filterToggleIndex(FilterMode.LADDER_12));
    assertEquals(1, SynthParamRack.filterToggleIndex(FilterMode.LADDER_24));
    assertEquals(
        1,
        SynthParamRack.filterToggleIndex(FilterMode.DRIVE),
        "DRIVE is a 24dB ladder filter, not SVF");
    assertEquals(2, SynthParamRack.filterToggleIndex(FilterMode.SVF));
    assertEquals(2, SynthParamRack.filterToggleIndex(FilterMode.SVF_BAND));
    assertEquals(2, SynthParamRack.filterToggleIndex(FilterMode.SVF_NOTCH));

    BridgeContract bridge = new BridgeContract();
    SynthTrackModel track = new SynthTrackModel("Bass");
    track.setLpfFreq(1000.0f);
    track.setLpfRes(0.4f);

    SynthParamRack rack = new SynthParamRack(bridge, () -> track, () -> 0);
    rack.editRes(5); // +0.10 -> 0.50
    rack.editCutoff(2);

    assertEquals(0.50f, track.getLpfRes(), 1e-4f);
    assertEquals(0.50, bridge.getTrackFilterRes(0), 1e-4, "Bridge resonance should stay in sync");
    assertEquals(
        track.getLpfFreq() / 20000.0,
        bridge.getTrackFilterFreq(0),
        1e-4,
        "Bridge cutoff should stay in sync");
  }

  @Test
  void gridContextMenuAndKeyboardShortcutUpdateClipModelAndEngineRows() {
    assertEquals("0x0000FF", GridContextMenuFactory.formatColourHex(Color.BLUE));
    assertEquals("0x00FF80", GridContextMenuFactory.formatColourHex(new Color(0x00, 0xFF, 0x80)));

    BridgeContract bridge = new BridgeContract();
    EngineSyncCoordinator sync = new EngineSyncCoordinator(null, bridge);
    ProjectModel project = new ProjectModel();

    SynthTrackModel track0 = new SynthTrackModel("Synth 0");
    track0.addClip(new ClipModel("Clip 0", 1, 16));
    project.addTrack(track0);

    SynthTrackModel track1 = new SynthTrackModel("Synth 1");
    ClipModel clip1 = new ClipModel("Clip 1", 1, 32);
    clip1.setStep(0, 20, StepData.of(true, 0.8f, 0.9f, 1.0f, 60));
    track1.addClip(clip1);
    project.addTrack(track1);

    sync.pushModelToBridge(project);

    SwingGridPanel panel = new ClipGridPanel(bridge);
    panel.setProjectModel(project);
    panel.editedModelTrack = 1; // Edit second track (engine row 8)

    GridContextMenuFactory.applyTrackProbability(panel, 1, 0.35f);
    GridContextMenuFactory.applyTrackVelocity(panel, 1, 0.65f);

    StepData updated = clip1.getStep(0, 20);
    assertEquals(0.35f, updated.probability(), 1e-4f);
    assertEquals(0.65f, updated.velocity(), 1e-4f);
    assertEquals(0.35, bridge.getStepProbability(8, 20), 1e-4);
    assertEquals(0.65, bridge.getVelocity(8, 20), 1e-4);

    KeyboardShortcutManager.adjustEditedClipLength(panel, -2);
    assertEquals(30, clip1.getStepCount(), "Edited clip length should decrease from 32 to 30");
    assertEquals(30, bridge.getTrackLength(8), "Engine row 8 length should match edited clip");
    assertEquals(
        16, track0.getActiveClip().getStepCount(), "Track 0 clip length must remain untouched");
  }
}
