package org.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.deluge.BridgeContract;
import org.deluge.engine.FirmwareFactory;
import org.deluge.hid.pic.PIC;
import org.deluge.model.AudioTrackModel;
import org.deluge.model.ClipModel;
import org.deluge.model.Consequence;
import org.deluge.model.KitTrackModel;
import org.deluge.model.SoundDrum;
import org.deluge.model.SynthTrackModel;

/** Alternative lightweight UI running purely on Java Swing (no native libs). */
public class SwingDelugeApp extends JFrame {
  public static SwingDelugeApp mainInstance;
  public static boolean pureModeActive = false;
  final BridgeContract bridge;
  private final org.deluge.model.ScriptingEngine scriptingEngine =
      new org.deluge.model.ScriptingEngine();

  final TransportController transportController;
  final FileMenuController fileMenuController;

  SwingGridPanel clipPanel;
  private SwingVisualizerPanel visualizerPanel;
  SwingGridPanel songPanel;
  private SwingGridPanel arrGridPanel;
  private SwingGridPanel autoPanel;
  private SwingPerformanceViewPanel performancePanel;

  SwingTopBarPanel topBar;
  private AppTopBarListener appTopBarListener;
  private SwingMasterFxPanel masterFxPanel;
  private SynthParamRack synthParamRack;
  private javax.swing.JScrollPane rackScroll;
  private org.deluge.ui.controls.DelugeModKnobBar modKnobBar;

  public SwingMasterFxPanel getMasterFxPanel() {
    return masterFxPanel;
  }

  private JPanel centerCardPanel;
  boolean learnHeld = false;

  public boolean isLearnHeld() {
    return learnHeld;
  }

  /**
   * When true, the boot auto-screenshot pipeline runs (it cycles grid modes 8x16/24x16 to capture
   * dev screenshots). Off for normal launches — otherwise the user sees the grid visibly resize 2-3
   * times on every boot. Enabled only by the {@code --screenshot} CLI flag.
   */
  public static boolean autoScreenshotOnBoot = false;

  /** Wrap a SwingGridPanel with a small top indent so cells aren't flush with the top border. */
  private static JPanel wrapGridPanel(SwingGridPanel grid) {
    grid.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
    return grid;
  }

  private CardLayout cardLayout;

  String activeViewMode = "CLIP";
  org.deluge.model.ProjectModel currentProject =
      org.deluge.model.ProjectModel.createDefaultProject();
  java.io.File currentProjectFile = null;

  private javax.swing.JRadioButtonMenuItem grid8x16Item;
  private javax.swing.JRadioButtonMenuItem grid16x16Item;
  private javax.swing.JRadioButtonMenuItem grid24x16Item;
  private javax.swing.JRadioButtonMenuItem grid16x24Item;

  public org.deluge.model.ProjectModel getCurrentProject() {
    return currentProject;
  }

  public void triggerPlayToggle() {
    transportController.onPlayToggle();
  }

  public boolean isPlaying() {
    return transportController.isPlaying();
  }

  // Engine voice mapping is now managed by the EngineSyncCoordinator
  private final EngineSyncCoordinator syncCoordinator;

  private final org.deluge.midi.MidiService midiService;
  SwingProjectSidebarPanel sidebarPanel;
  SwingProjectSidebarPanel floatingSidebar;
  org.deluge.hid.pic.SwingPicTransport picTransport;
  JDialog leftFloat;
  private JDialog rightFloat;
  private JCheckBoxMenuItem showMonitorItem;
  private Timer visualizerRepaintTimer;
  private Timer picTransportFlushTimer;
  private Timer
      statusTextPlaybackTimer; // Engine voice mapping and model-to-bridge synchronization methods

  // have been moved to EngineSyncCoordinator.

  public org.deluge.midi.MidiService getMidiService() {
    return midiService;
  }

  public JDialog getLeftFloat() {
    return leftFloat;
  }

  public void pushModelToBridge() {
    syncCoordinator.pushModelToBridge();
  }

  public void applyTrackModelToLiveSound(org.deluge.model.TrackModel track) {
    if (currentProject != null && track != null) {
      int idx = currentProject.getTracks().indexOf(track);
      if (idx >= 0) {
        try {
          Object eng = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
          if (eng instanceof org.deluge.engine.FirmwareAudioEngine engine
              && idx < engine.sounds.size()) {
            org.deluge.firmware2.GlobalEffectable ge = engine.sounds.get(idx);
            if (track instanceof org.deluge.model.SynthTrackModel st
                && ge instanceof org.deluge.engine.FirmwareSound fs) {
              org.deluge.engine.FirmwareFactory.applyModelToLiveSound(st, fs);
            } else if (track instanceof org.deluge.model.KitTrackModel kt
                && ge instanceof org.deluge.engine.FirmwareKit fk) {
              org.deluge.engine.FirmwareFactory.applyModelToLiveSound(kt, fk);
            }
          }
        } catch (Exception ignored) {
          // Engine not running (e.g. tests)
        }
      }
    }
  }

  org.deluge.engine.PureFirmwareEngine pureEngine;
  org.deluge.ui.ArrangerPlaybackScheduler arrangerScheduler;

  public org.deluge.ui.ArrangerPlaybackScheduler getArrangerScheduler() {
    return arrangerScheduler;
  }

  public org.deluge.engine.PureFirmwareEngine getPureEngine() {
    return pureEngine;
  }

  public EngineSyncCoordinator getSyncCoordinator() {
    return syncCoordinator;
  }

  public SwingDelugeApp(final BridgeContract bridge, org.deluge.midi.MidiService midiService) {
    this(bridge, midiService, false);
  }

  public SwingDelugeApp(
      final BridgeContract bridge, org.deluge.midi.MidiService midiService, boolean pureMode) {
    this.bridge = bridge;

    this.midiService = midiService;
    mainInstance = this;
    pureModeActive = pureMode;
    this.syncCoordinator = new EngineSyncCoordinator(this, bridge);
    this.transportController = new TransportController(this);
    this.fileMenuController = new FileMenuController(this);

    if (pureMode) {
      System.out.println("[UI] Initializing Pure Java High-Fidelity Engine...");
      this.pureEngine = new org.deluge.engine.PureFirmwareEngine();
      this.pureEngine.start(bridge);
      // Register in bridge for components that poll it
      bridge.setGlobalObject(BridgeContract.G_FIRMWARE_ENGINE, pureEngine.getAudioEngine());
      bridge.setGlobalObject(BridgeContract.G_PLAYBACK_HANDLER, pureEngine.getPlaybackHandler());

      String syncModeStr =
          org.deluge.project.PreferencesManager.get("sequencer.sync.mode", "INTERNAL");
      int syncMode = "EXTERNAL_MIDI".equals(syncModeStr) ? 1 : 0;
      pureEngine.getPlaybackHandler().setSyncMode(syncMode);

      // MIDI clock OUT: when enabled (default on), the transport drives external gear as the master
      // clock (0xFA start / 0xF8 tick @24 PPQN / 0xFC stop) via the MidiEngine.
      if (midiService != null
          && Boolean.parseBoolean(
              org.deluge.project.PreferencesManager.get("midi.clock.out", "true"))) {
        final org.deluge.midi.MidiEngine me = midiService.getEngine();
        pureEngine
            .getPlaybackHandler()
            .setMidiClockOut(
                new org.deluge.playback.PlaybackHandler.MidiClockSink() {
                  @Override
                  public void start() {
                    me.sendStart();
                  }

                  @Override
                  public void stop() {
                    me.sendStop();
                  }

                  @Override
                  public void clock() {
                    me.sendClock();
                  }
                });
      }
      System.out.println(
          "[UI] Boot Sync Mode applied to PlaybackHandler: "
              + (syncMode == 1 ? "EXTERNAL" : "INTERNAL"));
    }

    // Inflate Font Sizes globally (excluding menus to prevent layout bloat!)
    java.util.Enumeration<Object> keys = UIManager.getDefaults().keys();
    while (keys.hasMoreElements()) {
      Object key = keys.nextElement();
      String keyStr = key.toString();
      if (keyStr.contains("Menu") || keyStr.contains("MenuBar") || keyStr.contains("MenuItem")) {
        continue; // Keep all menu components standard and clean!
      }
      Object value = UIManager.get(key);
      if (value instanceof javax.swing.plaf.FontUIResource orig) {
        Font font =
            new Font(
                orig.getFontName(), orig.getStyle(), 13); // Clean, professional desktop font scale
        UIManager.put(key, new javax.swing.plaf.FontUIResource(font));
      }
    }

    setTitle("DELUGE WORKSTATION [SWING EDITION]");
    setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

    // ── Virtual Hardware Initialization ──
    setFocusable(true);
    addKeyListener(new KeyboardShortcutManager(this));

    // Register a global KeyEventDispatcher to capture Shift key state changes instantly across all
    // child components
    KeyboardFocusManager.getCurrentKeyboardFocusManager()
        .addKeyEventDispatcher(
            new KeyEventDispatcher() {
              @Override
              public boolean dispatchKeyEvent(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_TAB) {
                  if (e.getID() == java.awt.event.KeyEvent.KEY_PRESSED) {
                    if (clipPanel != null) clipPanel.setTabHeld(true);
                  } else if (e.getID() == java.awt.event.KeyEvent.KEY_RELEASED) {
                    if (clipPanel != null) clipPanel.setTabHeld(false);
                  }
                  return true; // consume to prevent standard AWT focus traversal!
                }

                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_SHIFT) {
                  if (e.getID() == java.awt.event.KeyEvent.KEY_PRESSED) {
                    if (clipPanel != null) clipPanel.setShiftHeld(true);
                    if (songPanel != null) songPanel.setShiftHeld(true);
                    if (arrGridPanel != null) arrGridPanel.setShiftHeld(true);
                  } else if (e.getID() == java.awt.event.KeyEvent.KEY_RELEASED) {
                    if (clipPanel != null) clipPanel.setShiftHeld(false);
                    if (songPanel != null) songPanel.setShiftHeld(false);
                    if (arrGridPanel != null) arrGridPanel.setShiftHeld(false);
                    if (bridge != null && bridge.getPlayState() != 0) {
                      picTransport.flush();
                    } else {
                      if (clipPanel != null) clipPanel.refresh();
                      if (songPanel != null) songPanel.refresh();
                      if (arrGridPanel != null) arrGridPanel.refresh();
                    }
                  }
                }

                // Intercept Up/Down arrow keys to adjust current parameter value when a grid
                // shortcut is focused in Shift mode
                if (clipPanel != null
                    && clipPanel.isShiftHeld()
                    && clipPanel.getActiveShiftParam() != null) {
                  if (e.getID() == java.awt.event.KeyEvent.KEY_PRESSED) {
                    if (e.getKeyCode() == java.awt.event.KeyEvent.VK_UP) {
                      clipPanel.adjustRotaryParameter(1);
                      return true; // consume the event!
                    } else if (e.getKeyCode() == java.awt.event.KeyEvent.VK_DOWN) {
                      clipPanel.adjustRotaryParameter(-1);
                      return true; // consume the event!
                    }
                  }
                }

                // Global GridMode Zoom: Alt + PageUp/PageDown cycles through all grid modes
                if (e.getID() == java.awt.event.KeyEvent.KEY_PRESSED) {
                  boolean isAlt = e.isAltDown() || e.isMetaDown();
                  if (isAlt
                      && (e.getKeyCode() == java.awt.event.KeyEvent.VK_PAGE_UP
                          || e.getKeyCode() == java.awt.event.KeyEvent.VK_PAGE_DOWN)) {
                    boolean forward = (e.getKeyCode() == java.awt.event.KeyEvent.VK_PAGE_UP);
                    org.deluge.project.PreferencesManager.GridMode[] modes =
                        org.deluge.project.PreferencesManager.GridMode.values();
                    org.deluge.project.PreferencesManager.GridMode currentMode =
                        org.deluge.project.PreferencesManager.getGridMode();
                    int currentIdx = 0;
                    for (int i = 0; i < modes.length; i++) {
                      if (modes[i] == currentMode) {
                        currentIdx = i;
                        break;
                      }
                    }
                    int nextIdx = currentIdx + (forward ? -1 : 1);
                    if (nextIdx >= 0 && nextIdx < modes.length) {
                      org.deluge.project.PreferencesManager.GridMode nextMode = modes[nextIdx];
                      org.deluge.project.PreferencesManager.setGridMode(nextMode);
                      if (clipPanel != null) {
                        clipPanel.setGridMode(nextMode);
                        clipPanel.refresh();
                      }
                      if (songPanel != null) {
                        songPanel.setGridMode(nextMode);
                        songPanel.refresh();
                      }
                      if (arrGridPanel != null) {
                        arrGridPanel.setGridMode(nextMode);
                        arrGridPanel.refresh();
                      }
                      recalcWrapperSize();
                    }
                    return true; // consume event!
                  }
                }
                return false; // Pass event downstream
              }
            });

    // The window size is driven by the Screen Resolution preference, then clamped to the physical
    // screen so it always fits the actual laptop (down to ~1366x768) and never exceeds it.
    setMinimumSize(new Dimension(MIN_WINDOW_W, MIN_WINDOW_H));
    Dimension win = computeWindowSize();
    setSize(win.width, win.height);

    addWindowListener(
        new java.awt.event.WindowAdapter() {
          @Override
          public void windowClosing(java.awt.event.WindowEvent e) {
            org.deluge.project.PreferencesManager.set("window.width", String.valueOf(getWidth()));
            org.deluge.project.PreferencesManager.set("window.height", String.valueOf(getHeight()));
          }
        });

    setLocationRelativeTo(null);
    getContentPane().setBackground(new Color(0x1a, 0x1a, 0x1a));
    setLayout(new BorderLayout(10, 10));

    setupUI();

    // Realize and lay out the frame at its FINAL size while still invisible, then size every grid's
    // cells once up-front. Without this the grids are built at the default cell size and then
    // rebuilt
    // 2–3 times as boot resize events (0 → frame width → viewport width) arrive — the visible
    // "grid keeps resizing" flicker. addNotify()+validate() is exactly what pack() does internally
    // for layout, minus the preferred-size resize, so it keeps our computed window size.
    addNotify();
    validate();
    for (SwingGridPanel grid :
        new SwingGridPanel[] {clipPanel, songPanel, arrGridPanel, autoPanel}) {
      if (grid != null) {
        grid.recomputePadSize();
      }
    }

    // Auto-load our default initial song project to sync tracks, presets, and listeners to the
    // bridge/engine!
    loadProject(currentProject);

    // Trigger a high-fidelity retro rolling welcome message on boot!
    if (topBar != null && topBar.getParamReadout() != null) {
      topBar.getParamReadout().print("HELO", "    ");
      javax.swing.Timer timer1 =
          new javax.swing.Timer(
              800,
              e -> {
                if (topBar != null && topBar.getParamReadout() != null) {
                  topBar.getParamReadout().print("DELU", "V1.0");
                }
              });
      timer1.setRepeats(false);
      timer1.start();

      javax.swing.Timer timer2 =
          new javax.swing.Timer(
              1600,
              e -> {
                if (topBar != null && topBar.getParamReadout() != null) {
                  topBar.getParamReadout().reset();
                }
              });
      timer2.setRepeats(false);
      timer2.start();
    }

    startPlaybackTimer();

    DarkComboBoxRenderer.styleComponentTree(this);

    // Instantiate Arranger Timeline real-time Scheduler
    this.arrangerScheduler = new org.deluge.ui.ArrangerPlaybackScheduler(bridge, currentProject);
    if (arrGridPanel != null) {
      arrangerScheduler.setRepaintCallback(() -> arrGridPanel.refresh());
    }
  }

  public void loadProject(org.deluge.model.ProjectModel model) {
    currentProject = model;
    if (arrangerScheduler != null) {
      arrangerScheduler.setProject(model);
    }
    bridge.setGlobalInt(BridgeContract.G_PLAY, 0L);
    if (bridge != null) bridge.setPlayState(0);

    // Register listener so structural and param changes auto-sync to bridge
    syncCoordinator.registerProject(model);

    // Register UndoRedoStack listener to capture macro recordings
    model
        .getUndoRedoStack()
        .setListener(
            action -> {
              if (action instanceof org.deluge.model.Consequence c) {
                scriptingEngine.record(c);
              }
            });

    syncCoordinator.pushModelToBridge();
    propagateCurrentModel();
    // Decide the initial view BEFORE syncHighFidelityEngine, which updates the OLED from
    // activeViewMode. An arranged song boots into arranger, a single-track song into clip, else
    // song view — matching hardware and giving the OLED the right context (song banner vs synth).
    if (model.getTracks().size() == 1) {
      activeViewMode = "CLIP";
    } else if (!model.getArrangerTimeline().isEmpty()) {
      activeViewMode = "ARR";
    } else {
      activeViewMode = "SONG";
    }
    syncHighFidelityEngine(model);

    if (clipPanel != null) clipPanel.setProjectModel(model);
    if (songPanel != null) songPanel.setProjectModel(model);
    if (arrGridPanel != null) arrGridPanel.setProjectModel(model);

    // Switch view depending on track count to keep UI and top bar perfectly synchronized
    if (model.getTracks().size() == 1) {
      if (cardLayout != null && centerCardPanel != null) {
        cardLayout.show(centerCardPanel, "CLIP");
      }
      if (topBar != null) topBar.selectClipView();
      boolean firstIsSynth =
          !model.getTracks().isEmpty()
              && model.getTracks().get(0) instanceof org.deluge.model.SynthTrackModel;
      int firstStart = syncCoordinator.getTrackEngineStart(0);
      if (clipPanel != null) {
        clipPanel.setBaseTrackId(firstStart >= 0 ? firstStart : (firstIsSynth ? 4 : 0));
      }
    } else if (!model.getArrangerTimeline().isEmpty()) {
      // A song that has an arrangement opens in the arranger view on hardware (the Deluge boots
      // arranged songs into arranger, not session view), so match that.
      activeViewMode = "ARR";
      if (cardLayout != null && centerCardPanel != null) {
        cardLayout.show(centerCardPanel, "ARR");
      }
      if (topBar != null) topBar.selectViewModeButton("ARR");
    } else {
      activeViewMode = "SONG";
      if (cardLayout != null && centerCardPanel != null) {
        cardLayout.show(centerCardPanel, "SONG");
      }
      if (topBar != null) topBar.selectViewModeButton("SONG");
    }

    setTitle(
        "DELUGE WORKSTATION — "
            + (currentProjectFile != null ? currentProjectFile.getName() : "Untitled"));
  }

  public void loadProjectWithProgress(final java.io.File file, final boolean isAbleton) {
    fileMenuController.loadProjectWithProgress(file, isAbleton);
  }

  /**
   * Load a WAV and apply it to a LIVE FirmwareKit drum voice so grid auditioning immediately plays
   * the chosen sample. The kit-config dialog otherwise only updated the model + a no-op
   * BridgeContract.setSamplePath (whose g_sample_/G_LOAD_TRIGGER consumers live in the legacy
   * DelugeEngineDSL, not the pure engine the Swing UI uses), so every drum fell back to its default
   * oscillator — all cells sounded identical. Mirrors the per-drum sample setup in
   * FirmwareFactory.createKitClip (oscType=SAMPLE + samples[0] + fw2SampleCache[0]). drumIdx is the
   * model drum order, which matches FirmwareKit.drumSounds order.
   */
  public void applyKitDrumSampleLive(
      org.deluge.model.KitTrackModel kit, int drumIdx, String absPath) {
    System.err.println(
        "[applyKitDrumSampleLive] ENTER drumIdx="
            + drumIdx
            + " path="
            + (absPath != null ? absPath.substring(Math.max(0, absPath.length() - 40)) : "null"));
    if (currentProject == null || pureEngine == null || absPath == null || absPath.isBlank()) {
      System.err.println("[applyKitDrumSampleLive] EARLY EXIT null");
      return;
    }
    int trackIdx = -1;
    var tracks = currentProject.getTracks();
    for (int i = 0; i < tracks.size(); i++) {
      if (tracks.get(i) == kit) {
        trackIdx = i;
        break;
      }
    }
    System.err.println(
        "[applyKitDrumSampleLive] trackIdx=" + trackIdx + " tracks=" + tracks.size());
    if (trackIdx < 0) {
      System.err.println("[applyKitDrumSampleLive] EARLY EXIT trackIdx");
      return;
    }
    org.deluge.engine.FirmwareAudioEngine eng = pureEngine.getAudioEngine();
    if (eng == null || trackIdx >= eng.sounds.size()) {
      System.err.println("[applyKitDrumSampleLive] EARLY EXIT eng");
      return;
    }
    var sound = eng.sounds.get(trackIdx);
    System.err.println(
        "[applyKitDrumSampleLive] sound@"
            + trackIdx
            + " = "
            + (sound != null ? sound.getClass().getSimpleName() : "null"));
    if (!(sound instanceof org.deluge.engine.FirmwareKit fkit)) {
      System.err.println("[applyKitDrumSampleLive] EARLY EXIT not kit");
      return;
    }
    if (drumIdx < 0 || drumIdx >= fkit.drumSounds.size()) {
      System.err.println("[applyKitDrumSampleLive] EARLY EXIT drumIdx oob");
      return;
    }
    org.deluge.engine.FirmwareSound drum = fkit.drumSounds.get(drumIdx);
    try {
      org.deluge.playback.Sample s = org.deluge.storage.audio.AudioFileReader.readSample(absPath);
      if (s != null && s.data != null) {
        drum.oscTypes[0] = org.deluge.firmware2.Oscillator.OscType.SAMPLE;
        drum.samples[0] = s;
        drum.fw2SampleCache[0] = org.deluge.firmware2.Sample.fromFirmwareSample(s);
        drum.paramNeutralValues[org.deluge.firmware2.Param.LOCAL_OSC_A_VOLUME] =
            org.deluge.firmware2.Functions.ONE_Q31;
        drum.paramNeutralValues[org.deluge.firmware2.Param.LOCAL_VOLUME] =
            org.deluge.firmware2.Functions.ONE_Q31;
        System.err.println("[applyKitDrumSampleLive] OK loaded " + s.getNumSamples() + " samples");
      } else {
        System.err.println(
            "[applyKitDrumSampleLive] FAIL readSample returned "
                + (s != null ? "null data" : "null"));
      }
    } catch (Exception ex) {
      System.err.println("[KitConfig] live drum sample apply failed: " + ex.getMessage());
      ex.printStackTrace();
    }
  }

  /** True if the firmware playback handler is currently playing. */
  private boolean isFirmwarePlaying() {
    Object h = bridge.getGlobalObject(BridgeContract.G_PLAYBACK_HANDLER);
    return h instanceof org.deluge.playback.PlaybackHandler ph && ph.isPlaying();
  }

  /** True if the engine's registered sound count no longer matches the track count. */
  private boolean engineStructureChanged(org.deluge.model.ProjectModel model) {
    Object e = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
    return !(e instanceof org.deluge.engine.FirmwareAudioEngine eng)
        || eng.sounds.size() != model.getTracks().size();
  }

  public void syncHighFidelityEngine(org.deluge.model.ProjectModel model) {
    syncHighFidelityEngine(model, false);
  }

  /**
   * @param forceRebuild rebuild the engine even while playing. Needed for a preset SWAP, which
   *     changes a track's actual sound (not just notes) — the live noteRows-sync can't carry a new
   *     sound, so skipping the rebuild leaves the engine on the stale sound (all notes garbage).
   */
  public void syncHighFidelityEngine(org.deluge.model.ProjectModel model, boolean forceRebuild) {
    if (!forceRebuild && isFirmwarePlaying() && !engineStructureChanged(model)) {
      return;
    }
    // Compile DSP engine in-place on the unified model!
    org.deluge.model.ProjectModel project = FirmwareFactory.createSong(model);

    // ── Sync Audio Registry ──
    Object fwEngineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
    if (fwEngineObj instanceof org.deluge.engine.FirmwareAudioEngine fwEngine) {
      fwEngine.sounds.clear();
      for (int i = 0; i < model.getTracks().size(); i++) {
        org.deluge.model.TrackModel tm = model.getTracks().get(i);
        org.deluge.model.ClipModel activeClip = tm.getActiveClip();
        org.deluge.firmware2.GlobalEffectable sound =
            (activeClip != null)
                ? (org.deluge.firmware2.GlobalEffectable) activeClip.getSound()
                : null;
        fwEngine.sounds.add(sound);
        if (sound != null) {
          System.out.println(
              "[UI] Registered track " + i + " sound: " + sound.getClass().getSimpleName());
        }
      }

      float masterVol = (float) bridge.getGlobalFloat(BridgeContract.G_MASTER_VOL);
      System.out.println("[UI] Engine Sync - MasterVol Global: " + masterVol);
      fwEngine.masterVolumeAdjustmentL = (int) (masterVol * 2147483647.0);
      fwEngine.masterVolumeAdjustmentR = fwEngine.masterVolumeAdjustmentL;

      System.out.println(
          "[UI] Synchronized "
              + fwEngine.sounds.size()
              + " track slots for Hi-Fi Rendering. MasterVol: "
              + masterVol);
    }
    if (topBar != null && !model.getTracks().isEmpty()) {
      int activeTrk = clipPanel != null ? clipPanel.getEditedModelTrack() : 0;
      if (activeTrk >= 0 && activeTrk < model.getTracks().size()) {
        org.deluge.model.TrackModel tm = model.getTracks().get(activeTrk);
        String tName = tm.getName().toUpperCase();
        String modeBanner =
            "CLIP".equals(activeViewMode) || activeViewMode == null
                ? "CLIP VIEW"
                : (activeViewMode + " VIEW");
        String middleText;
        String bottomInfo;
        if ("CLIP".equals(activeViewMode) || activeViewMode == null) {
          if (tm instanceof org.deluge.model.SynthTrackModel) {
            middleText = "SYNTH";
            bottomInfo = "PRESET: 1 (POLY 8)";
          } else if (tm instanceof org.deluge.model.KitTrackModel) {
            middleText = "KIT";
            bottomInfo = "DRUMS: 16 SLOTS";
          } else {
            middleText = "AUDIO";
            bottomInfo = "STEREO STREAM";
          }
        } else if ("SONG".equals(activeViewMode) || "ARR".equals(activeViewMode)) {
          // Match the Deluge song/home + arranger screen: song name, key + scale, BPM.
          modeBanner =
              currentProjectFile != null
                  ? currentProjectFile.getName().replaceFirst("(?i)\\.xml$", "").toUpperCase()
                  : ("SONG: " + model.getTracks().size() + " TRKS");
          String scale = model.getScale() != null ? model.getScale().toUpperCase() : "";
          middleText = (rootNoteToName(model.getKey()) + " " + scale).trim();
          bottomInfo = ((int) model.getBpm()) + " BPM";
        } else {
          middleText = tName;
          bottomInfo = "STATUS: READY";
        }

        final String finalModeBanner = modeBanner;
        final String finalMiddleText = middleText;
        final String finalBottomInfo = bottomInfo;
        javax.swing.SwingUtilities.invokeLater(
            () -> {
              org.deluge.hid.FirmwareDisplay.get()
                  .getVirtualOLED()
                  .drawThreeLineDisplay(finalModeBanner, finalMiddleText, finalBottomInfo);
            });
      }
    }

    Object fwHandlerObj = bridge.getGlobalObject(BridgeContract.G_PLAYBACK_HANDLER);
    if (fwHandlerObj instanceof org.deluge.playback.PlaybackHandler fwHandler) {
      fwHandler.setProject(model);
    }
  }

  private static final String[] NOTE_NAMES = {
    "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
  };

  /** Deluge key display: rootNote 0 -> "C-2" (Deluge octave = MIDI/12 - 2). */
  private static String rootNoteToName(String rootNote) {
    if (rootNote == null || rootNote.isBlank()) return "";
    try {
      int r = Integer.parseInt(rootNote.trim());
      return NOTE_NAMES[((r % 12) + 12) % 12] + (Math.floorDiv(r, 12) - 2);
    } catch (NumberFormatException e) {
      return rootNote; // already a name
    }
  }

  public void refreshGrids() {
    if (clipPanel != null) clipPanel.refresh();
    if (songPanel != null) songPanel.refresh();
    if (arrGridPanel != null) arrGridPanel.refresh();
  }

  public void doUndo() {
    if (currentProject == null) return;
    var stack = currentProject.getUndoRedoStack();
    if (!stack.canUndo()) return;
    var action = stack.peekUndo();
    switch (action) {
      case Consequence.TrackStructureConsequence tsc -> {
        handleTrackStructUndoRedo(tsc, true);
        stack.undo();
      }
      case Consequence.ClipStructureConsequence csc -> {
        handleClipStructUndoRedo(csc, true);
        stack.undo();
      }
      case Consequence.PatternLoadConsequence plc -> {
        handlePatternLoadUndoRedo(plc, true);
        stack.undo();
      }
      default -> stack.undo();
    }
    pushModelToBridge();
    if (currentProject != null) {
      for (org.deluge.model.TrackModel tm : currentProject.getTracks()) {
        applyTrackModelToLiveSound(tm);
      }
    }
    // A synth randomize swaps the whole sound; like a preset swap, the DSP engine must rebuild.
    if (action instanceof Consequence.SynthRandomizeConsequence) {
      syncHighFidelityEngine(currentProject, true);
    }
    refreshGrids();
  }

  public void doRedo() {
    if (currentProject == null) return;
    var stack = currentProject.getUndoRedoStack();
    if (!stack.canRedo()) return;
    var action = stack.peekRedo();
    switch (action) {
      case Consequence.TrackStructureConsequence tsc -> {
        handleTrackStructUndoRedo(tsc, false);
        stack.redo();
      }
      case Consequence.ClipStructureConsequence csc -> {
        handleClipStructUndoRedo(csc, false);
        stack.redo();
      }
      case Consequence.PatternLoadConsequence plc -> {
        handlePatternLoadUndoRedo(plc, false);
        stack.redo();
      }
      default -> stack.redo();
    }
    pushModelToBridge();
    if (currentProject != null) {
      for (org.deluge.model.TrackModel tm : currentProject.getTracks()) {
        applyTrackModelToLiveSound(tm);
      }
    }
    if (action instanceof Consequence.SynthRandomizeConsequence) {
      syncHighFidelityEngine(currentProject, true);
    }
    refreshGrids();
  }

  private void handleTrackStructUndoRedo(
      Consequence.TrackStructureConsequence tsc, boolean isUndo) {
    var tracks = currentProject.getTracks();
    int idx = tsc.index();
    switch (tsc.operation()) {
      case Consequence.TrackStructureConsequence.ADD -> {
        if (isUndo) {
          if (idx < tracks.size()) currentProject.removeTrack(tracks.get(idx));
        } else {
          currentProject.addTrack(idx, tsc.trackSnapshot());
        }
      }
      case Consequence.TrackStructureConsequence.REMOVE -> {
        if (isUndo) {
          currentProject.addTrack(idx, tsc.trackSnapshot());
        } else {
          if (idx < tracks.size()) currentProject.removeTrack(tracks.get(idx));
        }
      }
      case Consequence.TrackStructureConsequence.MOVE_UP -> {
        int swapIdx = isUndo ? idx + 1 : idx - 1;
        if (swapIdx >= 0 && swapIdx < tracks.size() && idx >= 0 && idx < tracks.size()) {
          currentProject.moveTrackUp(Math.max(idx, swapIdx));
        }
      }
      case Consequence.TrackStructureConsequence.MOVE_DOWN -> {
        int swapIdx = isUndo ? idx - 1 : idx + 1;
        if (swapIdx >= 0 && swapIdx < tracks.size() && idx >= 0 && idx < tracks.size()) {
          currentProject.moveTrackDown(Math.min(idx, swapIdx));
        }
      }
    }
  }

  private void handleClipStructUndoRedo(Consequence.ClipStructureConsequence csc, boolean isUndo) {
    var tracks = currentProject.getTracks();
    if (csc.trackIndex() < 0 || csc.trackIndex() >= tracks.size()) return;
    var track = tracks.get(csc.trackIndex());
    var clips = track.getClips();
    int ci = csc.clipIndex();
    switch (csc.operation()) {
      case Consequence.ClipStructureConsequence.ADD -> {
        if (isUndo) {
          if (ci >= 0 && ci < clips.size()) clips.remove(ci);
        } else {
          clips.add(ci, csc.clipSnapshot());
        }
      }
      case Consequence.ClipStructureConsequence.REMOVE -> {
        if (isUndo) {
          clips.add(ci, csc.clipSnapshot());
        } else {
          if (ci >= 0 && ci < clips.size()) clips.remove(ci);
        }
      }
      case Consequence.ClipStructureConsequence.DUPLICATE -> {
        if (isUndo) {
          if (ci + 1 >= 0 && ci + 1 < clips.size()) clips.remove(ci + 1);
        } else {
          clips.add(ci + 1, csc.clipSnapshot());
        }
      }
      case Consequence.ClipStructureConsequence.RENAME -> {
        String name = isUndo ? csc.previousName() : csc.newName();
        if (ci >= 0 && ci < clips.size()) clips.get(ci).setName(name);
      }
    }
  }

  private void handlePatternLoadUndoRedo(Consequence.PatternLoadConsequence plc, boolean isUndo) {
    var tracks = currentProject.getTracks();
    if (plc.trackIndex() < 0 || plc.trackIndex() >= tracks.size()) return;
    var track = tracks.get(plc.trackIndex());
    int ci = plc.clipIndex();
    if (ci < 0 || ci >= track.getClips().size()) return;
    var clip = track.getClips().get(ci);
    var snap = isUndo ? plc.beforeSnapshot() : plc.afterSnapshot();
    snap.applyTo(clip);
  }

  public SwingTopBarPanel getTopBar() {
    return topBar;
  }

  SwingGridPanel activeGridPanel() {
    if (cardLayout == null || centerCardPanel == null) {
      return clipPanel;
    }
    for (java.awt.Component comp : centerCardPanel.getComponents()) {
      if (comp.isVisible() && comp instanceof SwingGridPanel sgp) {
        return sgp;
      }
      if (comp.isVisible()
          && comp instanceof JScrollPane sp
          && sp.getViewport().getView() instanceof SwingGridPanel sgp) {
        return sgp;
      }
    }
    return clipPanel;
  }

  public void propagateCurrentModel() {
    if (clipPanel != null) clipPanel.setProjectModel(currentProject);
    if (songPanel != null) songPanel.setProjectModel(currentProject);
    if (arrGridPanel != null) arrGridPanel.setProjectModel(currentProject);
    if (autoPanel != null) autoPanel.setProjectModel(currentProject);
    refreshTrackInspector();
  }

  // ── Fixed track-inspector strip (above the grid, outside the scroll) ──
  private javax.swing.JLabel trackInspectorLabel;
  private javax.swing.JButton trackInspectorPresetBtn;

  private javax.swing.JComponent buildTrackInspectorStrip() {
    JPanel strip = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 4));
    strip.setBackground(new Color(0x1a, 0x1a, 0x20));
    strip.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x2d, 0x2d, 0x34)));
    javax.swing.JLabel tag = new javax.swing.JLabel("ACTIVE TRACK");
    tag.setForeground(new Color(0x66, 0x88, 0x99));
    tag.setFont(new Font("SansSerif", Font.BOLD, 10));
    strip.add(tag);
    trackInspectorLabel = new javax.swing.JLabel("—");
    trackInspectorLabel.setForeground(new Color(0x00, 0xcc, 0xff));
    trackInspectorLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
    strip.add(trackInspectorLabel);
    javax.swing.JButton cfg =
        stripButton("⚙ Configure", "Open the full config dialog for the active track");
    cfg.addActionListener(e -> openEditedTrackConfig());
    strip.add(cfg);
    trackInspectorPresetBtn =
        stripButton("▾ Preset…", "Replace the active track's sound or load a new track");
    trackInspectorPresetBtn.addActionListener(
        e -> openEditedTrackPresetPicker(trackInspectorPresetBtn));
    strip.add(trackInspectorPresetBtn);
    return strip;
  }

  private javax.swing.JButton stripButton(String text, String tip) {
    javax.swing.JButton b = new javax.swing.JButton(text);
    b.setBackground(new Color(0x2a, 0x2a, 0x30));
    b.setForeground(new Color(0x00, 0xcc, 0xff));
    b.setFocusPainted(false);
    b.setFont(new Font("SansSerif", Font.PLAIN, 11));
    b.setToolTipText(tip);
    return b;
  }

  /** Update the strip label to reflect the active (edited) track. */
  public void refreshTrackInspector() {
    if (trackInspectorLabel == null) return;
    SwingGridPanel a = activeGridPanel();
    if (a == null || a.getProjectModel() == null) {
      trackInspectorLabel.setText("—");
      return;
    }
    int idx = a.getEditedModelTrack();
    java.util.List<org.deluge.model.TrackModel> tl = a.getProjectModel().getTracks();
    if (idx < 0 || idx >= tl.size()) {
      trackInspectorLabel.setText("—");
      return;
    }
    org.deluge.model.TrackModel t = tl.get(idx);
    String type =
        (t instanceof org.deluge.model.KitTrackModel)
            ? "KIT"
            : (t instanceof org.deluge.model.SynthTrackModel) ? "SYNTH" : "TRK";
    boolean hasPreset =
        (t instanceof org.deluge.model.KitTrackModel)
            || (t instanceof org.deluge.model.SynthTrackModel);
    trackInspectorLabel.setText("T" + (idx + 1) + " · " + t.getName() + "  [" + type + "]");
    if (trackInspectorPresetBtn != null) trackInspectorPresetBtn.setEnabled(hasPreset);
  }

  private void openEditedTrackConfig() {
    SwingGridPanel a = activeGridPanel();
    if (a == null || a.getProjectModel() == null) return;
    int idx = a.getEditedModelTrack();
    java.util.List<org.deluge.model.TrackModel> tl = a.getProjectModel().getTracks();
    if (idx < 0 || idx >= tl.size()) return;
    org.deluge.model.TrackModel t = tl.get(idx);
    if (t instanceof org.deluge.model.KitTrackModel kt) {
      new SwingKitConfigDialog(this, kt, bridge, idx).setVisible(true);
    } else if (t instanceof org.deluge.model.SynthTrackModel st) {
      new SwingSynthConfigDialog(this, st, bridge, idx, currentProject).setVisible(true);
    }
  }

  private void openEditedTrackPresetPicker(java.awt.Component anchor) {
    SwingGridPanel a = activeGridPanel();
    if (a == null || a.getProjectModel() == null) return;
    int idx = a.getEditedModelTrack();
    java.util.List<org.deluge.model.TrackModel> tl = a.getProjectModel().getTracks();
    if (idx < 0 || idx >= tl.size()) return;
    boolean isKit = tl.get(idx) instanceof org.deluge.model.KitTrackModel;
    boolean isSynth = tl.get(idx) instanceof org.deluge.model.SynthTrackModel;
    if (!isKit && !isSynth) return;
    LibraryPicker.show(
        anchor,
        isKit ? LibraryPicker.Scope.KITS : LibraryPicker.Scope.SYNTHS,
        null,
        java.util.List.of(
            new LibraryPicker.Action(
                "Replace track",
                new Color(0x00, 0x88, 0x66),
                f -> replaceEditedTrackPreset(f, isKit)),
            new LibraryPicker.Action(
                "Load as NEW", new Color(0x33, 0x55, 0x88), f -> loadPresetAsNewTrack(f, isKit))));
  }

  private void replaceEditedTrackPreset(java.io.File f, boolean isKit) {
    try {
      SwingGridPanel a = activeGridPanel();
      if (a == null || a.getProjectModel() == null) return;
      int idx = a.getEditedModelTrack();
      java.util.List<org.deluge.model.TrackModel> tl = a.getProjectModel().getTracks();
      if (idx < 0 || idx >= tl.size()) return;
      org.deluge.model.TrackModel old = tl.get(idx);

      setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  final org.deluge.model.TrackModel nt =
                      isKit
                          ? org.deluge.xml.DelugeXmlParser.parseKit(f)
                          : org.deluge.xml.DelugeXmlParser.parseSynth(f);
                  nt.getClips().clear();
                  for (org.deluge.model.ClipModel cm : old.getClips()) nt.addClip(cm);
                  nt.setColourHex(old.getColourHex());

                  // Update model list on the EDT and wait
                  javax.swing.SwingUtilities.invokeAndWait(
                      () -> {
                        tl.set(idx, nt);
                        propagateCurrentModel();
                      });

                  // Heavy engine sync on background thread
                  syncHighFidelityEngine(currentProject, true);

                  // Rebuild UI components on the EDT
                  javax.swing.SwingUtilities.invokeLater(
                      () -> {
                        setCursor(Cursor.getDefaultCursor());
                        a.forceRebuild();
                        if (synthParamRack != null) synthParamRack.refresh();
                        if (modKnobBar != null) modKnobBar.refresh();
                      });
                } catch (Exception ex) {
                  javax.swing.SwingUtilities.invokeLater(
                      () -> {
                        setCursor(Cursor.getDefaultCursor());
                        System.err.println("[Inspector] preset replace failed: " + ex.getMessage());
                      });
                }
              });
    } catch (Exception ex) {
      System.err.println("[Inspector] preset replace failed: " + ex.getMessage());
    }
  }

  private void loadPresetAsNewTrack(java.io.File f, boolean isKit) {
    new javax.swing.SwingWorker<org.deluge.model.TrackModel, Void>() {
      @Override
      protected org.deluge.model.TrackModel doInBackground() throws Exception {
        return isKit
            ? org.deluge.xml.DelugeXmlParser.parseKit(f)
            : org.deluge.xml.DelugeXmlParser.parseSynth(f);
      }

      @Override
      protected void done() {
        try {
          org.deluge.model.TrackModel nt = get();
          currentProject.addTrack(nt);
          propagateCurrentModel();
          syncHighFidelityEngine(currentProject);
          if (clipPanel != null) clipPanel.refresh();
          if (modKnobBar != null) modKnobBar.refresh();
        } catch (Exception ex) {
          Throwable cause = (ex.getCause() != null) ? ex.getCause() : ex;
          System.err.println("[Inspector] preset load-new failed: " + cause.getMessage());
        }
      }
    }.execute();
  }

  public void fireProjectChanged() {
    propagateCurrentModel();
    syncHighFidelityEngine(currentProject);
    if (clipPanel != null) clipPanel.refresh();
    if (songPanel != null) songPanel.refresh();
    if (arrGridPanel != null) arrGridPanel.refresh();
    if (autoPanel != null) autoPanel.refresh();
    if (synthParamRack != null) synthParamRack.refresh();
    if (modKnobBar != null) modKnobBar.refresh();
    refreshTrackInspector();
  }

  /** Show/hide the EAST synth param rack; the grid reflows to reclaim the width. */
  public void toggleParamRack() {
    if (rackScroll != null) {
      rackScroll.setVisible(!rackScroll.isVisible());
      revalidate();
      repaint();
    }
  }

  /** Re-read the edited track into the param rack (called on track/view changes). */
  public void refreshParamRack() {
    refreshTrackInspector();
    if (synthParamRack != null) {
      synthParamRack.refresh();
    }
    if (modKnobBar != null) {
      modKnobBar.refresh();
    }
  }

  public void updateHardwareLedDisplay(String paramCode, String valueString) {
    if (topBar != null && topBar.getParamReadout() != null) {
      if (paramCode == null || valueString == null) {
        topBar.getParamReadout().reset();
      } else {
        topBar.getParamReadout().print(paramCode, valueString);
      }
    }
  }

  public void updateHardwareLedDisplayTransient(String paramCode, String valueString) {
    if (topBar != null && topBar.getParamReadout() != null) {
      topBar.getParamReadout().printTransient(paramCode, valueString);
    }
  }

  public SwingGridPanel getClipPanel() {
    return clipPanel;
  }

  public SwingTopBarPanel.TopBarListener getTopBarListener() {
    return appTopBarListener;
  }

  public SwingGridPanel getAutoPanel() {
    return autoPanel;
  }

  public SwingGridPanel getSongPanel() {
    return songPanel;
  }

  public void setWorkspaceView(String viewName) {
    activeViewMode = viewName;
    if (topBar != null) topBar.selectViewModeButton(viewName);
    cardLayout.show(centerCardPanel, viewName);
    revalidate();
    repaint();
  }

  private void saveCurrentClipAsPattern() {
    fileMenuController.saveCurrentClipAsPattern();
  }

  private void loadPatternIntoActiveTrack(java.io.File patternFile) {
    fileMenuController.loadPatternIntoActiveTrack(patternFile);
  }

  private void setupUI() {
    getContentPane().removeAll();
    setLayout(new BorderLayout());

    // 0. Menu Bar
    JMenuBar menuBar = new JMenuBar();
    JMenu fileMenu = new JMenu("File");

    JMenuItem newItem = new JMenuItem("New Project");
    newItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_N, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    newItem.addActionListener(
        e -> {
          int ok =
              JOptionPane.showConfirmDialog(
                  this,
                  "Create a new empty project? Unsaved changes will be lost.",
                  "New Project",
                  JOptionPane.OK_CANCEL_OPTION);
          if (ok == JOptionPane.OK_OPTION) {
            currentProjectFile = null;
            loadProject(org.deluge.model.ProjectModel.createDefaultProject());
          }
        });

    JMenuItem newWindowItem = new JMenuItem("New Window");
    newWindowItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_N,
            java.awt.event.InputEvent.CTRL_DOWN_MASK | java.awt.event.InputEvent.SHIFT_DOWN_MASK));
    newWindowItem.setToolTipText("Launch a second, independent Deluge instance in its own window");
    newWindowItem.addActionListener(e -> fileMenuController.launchNewInstance());

    JMenuItem openItem = new JMenuItem("Open Project...");
    openItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_O, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    openItem.addActionListener(
        e -> {
          JFileChooser chooser =
              new JFileChooser(org.deluge.project.PreferencesManager.getSongsDir());
          chooser.setFileFilter(
              new javax.swing.filechooser.FileNameExtensionFilter("Song XML", "xml", "XML"));
          if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            final java.io.File file = chooser.getSelectedFile();
            loadProjectWithProgress(file, false);
          }
        });

    JMenuItem saveItem = new JMenuItem("Save Project");
    saveItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_S, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    saveItem.addActionListener(e -> fileMenuController.saveProject(false));

    JMenuItem saveAsItem = new JMenuItem("Save Project As...");
    saveAsItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_S,
            java.awt.event.InputEvent.CTRL_DOWN_MASK | java.awt.event.InputEvent.SHIFT_DOWN_MASK));
    saveAsItem.addActionListener(e -> fileMenuController.saveProject(true));

    JMenuItem exportItem = new JMenuItem("Export Audio...");
    exportItem.addActionListener(e -> fileMenuController.exportAudio());

    JMenuItem exportWavStemsItem = new JMenuItem("Export WAV Stems...");
    exportWavStemsItem.addActionListener(e -> fileMenuController.exportWavStems());

    JMenuItem exportMidiItem = new JMenuItem("Export MIDI File...");
    exportMidiItem.addActionListener(e -> fileMenuController.exportMidiFile());

    JMenuItem assembleKitItem = new JMenuItem("Assemble Kit From Synths...");
    assembleKitItem.addActionListener(e -> fileMenuController.assembleKitFromSynths());

    JMenuItem loadScriptItem = new JMenuItem("Load Script...");
    loadScriptItem.addActionListener(e -> fileMenuController.loadChuckScript());

    JMenuItem explorerItem = new JMenuItem("Show Explorer");
    explorerItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_E, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    explorerItem.addActionListener(
        e -> {
          if (leftFloat != null) {
            leftFloat.setVisible(!leftFloat.isVisible());
          }
        });

    JMenuItem exitItem = new JMenuItem("Exit");
    exitItem.addActionListener(e -> System.exit(0));

    fileMenu.add(newItem);
    fileMenu.add(newWindowItem);
    fileMenu.add(openItem);
    fileMenu.add(explorerItem);
    fileMenu.addSeparator();
    fileMenu.add(saveItem);
    fileMenu.add(saveAsItem);
    fileMenu.addSeparator();
    fileMenu.add(exportItem);
    fileMenu.add(exportWavStemsItem);
    fileMenu.add(exportMidiItem);
    fileMenu.addSeparator();
    fileMenu.add(assembleKitItem);

    JMenuItem importAbletonItem = new JMenuItem("Import Ableton Live Set...");
    importAbletonItem.addActionListener(
        e -> {
          String lastDir = org.deluge.project.PreferencesManager.get("last_ableton_import_dir", "");
          java.io.File initialDir = null;
          if (!lastDir.isEmpty()) {
            java.io.File testDir = new java.io.File(lastDir);
            if (testDir.exists() && testDir.isDirectory()) {
              initialDir = testDir;
            }
          }
          if (initialDir == null) {
            initialDir = org.deluge.ableton.AbletonAssetResolver.getDefaultImportDir();
          }

          JFileChooser chooser = new JFileChooser(initialDir);
          chooser.setDialogTitle("Import Ableton Live Set (.als)");
          chooser.setFileFilter(
              new javax.swing.filechooser.FileNameExtensionFilter(
                  "Ableton Live Set", "als", "ALS"));
          if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            final java.io.File file = chooser.getSelectedFile();
            if (file != null && file.getParentFile() != null) {
              org.deluge.project.PreferencesManager.set(
                  "last_ableton_import_dir", file.getParentFile().getAbsolutePath());
            }
            if (file == null || file.isDirectory()) {
              JOptionPane.showMessageDialog(
                  this,
                  "The selected path is a directory, not a file.\n"
                      + "Please double-click to navigate inside, and select a valid Ableton Live Set (.als) file!",
                  "Invalid Selection",
                  JOptionPane.WARNING_MESSAGE);
              return;
            }
            loadProjectWithProgress(file, true);
          }
        });
    fileMenu.add(importAbletonItem);

    JMenuItem importMidiItem = new JMenuItem("Import MIDI File...");
    importMidiItem.addActionListener(
        e -> {
          JFileChooser chooser =
              new JFileChooser(org.deluge.ableton.AbletonAssetResolver.getDefaultImportDir());
          chooser.setDialogTitle("Import MIDI File (.mid)");
          chooser.setFileFilter(
              new javax.swing.filechooser.FileNameExtensionFilter(
                  "MIDI File", "mid", "midi", "MID", "MIDI"));
          if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            java.io.File file = chooser.getSelectedFile();
            if (file != null && file.isFile()) {
              SwingMidiImportDialog wizard = new SwingMidiImportDialog(this, file);
              wizard.setVisible(true);
              if (wizard.isImportSuccessful() && wizard.getCompiledProject() != null) {
                currentProjectFile = null;
                loadProject(wizard.getCompiledProject());
                JOptionPane.showMessageDialog(
                    this,
                    "MIDI file compiled and imported successfully!\n" + file.getName(),
                    "Import Successful",
                    JOptionPane.INFORMATION_MESSAGE);
              }
            }
          }
        });
    fileMenu.add(importMidiItem);

    JMenuItem importAudioItem = new JMenuItem("Import Audio File...");
    importAudioItem.addActionListener(
        e -> {
          JFileChooser chooser =
              new JFileChooser(org.deluge.ableton.AbletonAssetResolver.getDefaultImportDir());
          chooser.setDialogTitle("Select Audio File to Transcribe");
          chooser.setFileFilter(
              new javax.swing.filechooser.FileNameExtensionFilter(
                  "Audio File",
                  "wav",
                  "mp3",
                  "flac",
                  "ogg",
                  "m4a",
                  "WAV",
                  "MP3",
                  "FLAC",
                  "OGG",
                  "M4A"));
          if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            java.io.File file = chooser.getSelectedFile();
            if (file != null && file.isFile()) {
              SwingAudioTranscribeDialog transcriber = new SwingAudioTranscribeDialog(this, file);
              transcriber.setVisible(true);
              if (transcriber.isTranscriptionSuccessful()
                  && transcriber.getCompiledProject() != null) {
                currentProjectFile = null;
                loadProject(transcriber.getCompiledProject());
                JOptionPane.showMessageDialog(
                    this,
                    "Audio transcribed and imported successfully!\n" + file.getName(),
                    "Import Successful",
                    JOptionPane.INFORMATION_MESSAGE);
              }
            }
          }
        });
    fileMenu.add(importAudioItem);

    JMenuItem exportAbletonItem = new JMenuItem("Export to Ableton Live Set...");
    exportAbletonItem.addActionListener(
        e -> {
          String[] options = {
            "Portable Ableton Project (Collect all samples)",
            "Render WAV Stems to Ableton Audio Tracks",
            "Standalone Ableton Live Set (.als file only)"
          };
          javax.swing.JComboBox<String> modeCombo = new javax.swing.JComboBox<>(options);
          modeCombo.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 12));

          int result =
              JOptionPane.showConfirmDialog(
                  this,
                  new Object[] {"Select Export Mode:", modeCombo},
                  "Export to Ableton Live Set",
                  JOptionPane.OK_CANCEL_OPTION,
                  JOptionPane.QUESTION_MESSAGE);
          if (result != JOptionPane.OK_OPTION) return;
          int mode = modeCombo.getSelectedIndex();

          JFileChooser chooser =
              new JFileChooser(org.deluge.ableton.AbletonAssetResolver.getDefaultImportDir());
          chooser.setDialogTitle("Export as Ableton Live Set (.als)");
          chooser.setFileFilter(
              new javax.swing.filechooser.FileNameExtensionFilter(
                  "Ableton Live Set", "als", "ALS"));
          chooser.setSelectedFile(new java.io.File("Exported Project.als"));
          if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

          java.io.File file = chooser.getSelectedFile();
          if (!file.getName().toLowerCase().endsWith(".als")) {
            file = new java.io.File(file.getAbsolutePath() + ".als");
          }

          java.io.File finalFile = file;

          if (mode == 0) {
            // Portable Ableton Project (Collect all samples)
            try {
              java.io.File projectDir = finalFile.getParentFile();
              java.io.File importedDir = new java.io.File(projectDir, "Samples/Imported");

              org.deluge.ableton.AbletonTrackExporter.PathRewriter rewriter =
                  originalPath -> {
                    if (originalPath == null || originalPath.isEmpty()) {
                      return originalPath;
                    }
                    try {
                      java.io.File originalFile =
                          new java.io.File(
                              org.deluge.project.PreferencesManager.getLibraryDir(), originalPath);
                      if (!originalFile.exists()) {
                        originalFile = new java.io.File(originalPath);
                      }
                      if (!originalFile.exists()) {
                        return originalPath;
                      }

                      importedDir.mkdirs();
                      String name = originalFile.getName();
                      java.io.File dest = new java.io.File(importedDir, name);

                      int cnt = 1;
                      String base = name;
                      String ext = "";
                      int dot = name.lastIndexOf('.');
                      if (dot > 0) {
                        base = name.substring(0, dot);
                        ext = name.substring(dot);
                      }
                      while (dest.exists()) {
                        dest = new java.io.File(importedDir, base + "_" + cnt + ext);
                        cnt++;
                      }

                      try (java.io.FileInputStream fis = new java.io.FileInputStream(originalFile);
                          java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
                        fis.transferTo(fos);
                      }
                      return "Samples/Imported/" + dest.getName();
                    } catch (Exception ex) {
                      ex.printStackTrace();
                      return originalPath;
                    }
                  };

              org.deluge.ableton.AbletonTrackExporter.exportProject(
                  currentProject, finalFile, rewriter);
              JOptionPane.showMessageDialog(
                  this,
                  "Project exported successfully as self-contained Ableton Project!\nAll samples collected in Samples/Imported/.",
                  "Export Successful",
                  JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
              ex.printStackTrace();
              JOptionPane.showMessageDialog(
                  this,
                  "Failed to export project:\n" + ex.getMessage(),
                  "Error",
                  JOptionPane.ERROR_MESSAGE);
            }
          } else if (mode == 1) {
            // Render WAV Stems to Ableton Audio Tracks
            JDialog progressDialog = new JDialog(this, "Rendering WAV Stems to Ableton...", true);
            progressDialog.setSize(350, 120);
            progressDialog.setLocationRelativeTo(this);
            progressDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
            progressDialog.setLayout(new java.awt.BorderLayout(10, 10));

            JLabel statusLabel = new JLabel("Preparing export...", JLabel.CENTER);
            JProgressBar progressBar = new JProgressBar(0, 100);
            progressBar.setStringPainted(true);

            JPanel panel = new JPanel(new java.awt.GridLayout(2, 1, 5, 5));
            panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            panel.add(statusLabel);
            panel.add(progressBar);
            progressDialog.add(panel, java.awt.BorderLayout.CENTER);

            SwingWorker<Void, String> worker =
                new SwingWorker<>() {
                  @Override
                  protected Void doInBackground() throws Exception {
                    org.deluge.ableton.AbletonTrackExporter.exportStemsProject(
                        currentProject,
                        finalFile,
                        (status, percent) -> {
                          publish(status + "|" + percent);
                        });
                    return null;
                  }

                  @Override
                  protected void process(java.util.List<String> chunks) {
                    String lastChunk = chunks.get(chunks.size() - 1);
                    String[] parts = lastChunk.split("\\|");
                    statusLabel.setText(parts[0]);
                    progressBar.setValue(Integer.parseInt(parts[1]));
                  }

                  @Override
                  protected void done() {
                    progressDialog.dispose();
                    try {
                      get(); // Check for exceptions
                      JOptionPane.showMessageDialog(
                          SwingDelugeApp.this,
                          "WAV Stems rendered and exported to Ableton Project successfully:\n"
                              + finalFile.getName(),
                          "Export Success",
                          JOptionPane.INFORMATION_MESSAGE);
                    } catch (Exception ex) {
                      JOptionPane.showMessageDialog(
                          SwingDelugeApp.this,
                          "WAV Stems Ableton export failed:\n" + ex.getMessage(),
                          "Export Error",
                          JOptionPane.ERROR_MESSAGE);
                    }
                  }
                };

            worker.execute();
            progressDialog.setVisible(true);
          } else {
            // Standalone Ableton Live Set (.als file only)
            try {
              org.deluge.ableton.AbletonTrackExporter.exportProject(
                  currentProject, finalFile, null);
              JOptionPane.showMessageDialog(
                  this,
                  "Project exported successfully as Standalone Ableton Live Set!\n"
                      + finalFile.getName(),
                  "Export Successful",
                  JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
              ex.printStackTrace();
              JOptionPane.showMessageDialog(
                  this,
                  "Failed to export project:\n" + ex.getMessage(),
                  "Error",
                  JOptionPane.ERROR_MESSAGE);
            }
          }
        });
    fileMenu.add(exportAbletonItem);

    fileMenu.addSeparator();
    fileMenu.add(loadScriptItem);
    fileMenu.addSeparator();
    fileMenu.add(exitItem);

    JMenu settingsMenu = new JMenu("Settings");

    JMenuItem sampleItem = new JMenuItem("Set SD Card Root...");
    sampleItem.addActionListener(
        e -> {
          JFileChooser chooser =
              new JFileChooser(org.deluge.project.PreferencesManager.getLibraryDir());
          chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
          if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            org.deluge.project.PreferencesManager.setLibraryDir(
                chooser.getSelectedFile().getAbsolutePath());
            sidebarPanel.reloadLibrary();
            floatingSidebar.reloadLibrary();
          }
        });

    // Always-on input monitor: opens the microphone and feeds the engine's live-input bus so
    // patches with inLeft/inRight/inStereo oscillator sources monitor the input continuously
    // (without arming the threshold sampler).
    final JCheckBoxMenuItem monitorInputItem = new JCheckBoxMenuItem("Monitor Audio Input");
    monitorInputItem.setSelected(
        org.deluge.engine.AudioInputCaptureLine.getInstance().isMonitoring());
    monitorInputItem.addActionListener(
        e -> {
          var capture = org.deluge.engine.AudioInputCaptureLine.getInstance();
          if (monitorInputItem.isSelected()) {
            capture.startMonitoring();
            if (!capture.isMonitoring()) {
              // The line was already in use (sampler armed) or failed to open.
              monitorInputItem.setSelected(false);
            }
          } else {
            capture.stopMonitoring();
          }
        });

    JMenuItem clearMidiItem = new JMenuItem("Reset MIDI Mappings");
    clearMidiItem.addActionListener(
        e -> {
          int ok =
              JOptionPane.showConfirmDialog(
                  SwingDelugeApp.this,
                  "Are you sure you want to clear all dynamically learned physical MIDI CC controllers mappings?",
                  "Clear MIDI Mappings",
                  JOptionPane.YES_NO_OPTION);
          if (ok == JOptionPane.YES_OPTION) {
            String[] prefKeys = org.deluge.project.PreferencesManager.getKeys();
            for (String key : prefKeys) {
              if (key.startsWith("midi.learn.")) {
                org.deluge.project.PreferencesManager.remove(key);
              }
            }
            JOptionPane.showMessageDialog(
                SwingDelugeApp.this, "All learned physical MIDI CC bindings cleared successfully!");
          }
        });

    JMenuItem midiConfigItem = new JMenuItem("MIDI Settings...");
    midiConfigItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_M,
            java.awt.event.InputEvent.CTRL_DOWN_MASK | java.awt.event.InputEvent.SHIFT_DOWN_MASK));
    midiConfigItem.addActionListener(
        e -> {
          PreferencesDialog dialog =
              new PreferencesDialog(
                  SwingDelugeApp.this,
                  midiService,
                  () -> {
                    org.deluge.project.PreferencesManager.GridMode mode =
                        org.deluge.project.PreferencesManager.getGridMode();
                    if (clipPanel != null) {
                      clipPanel.setGridMode(mode);
                      clipPanel.refresh();
                    }
                    if (songPanel != null) {
                      songPanel.setGridMode(mode);
                      songPanel.refresh();
                    }
                    if (arrGridPanel != null) {
                      arrGridPanel.setGridMode(mode);
                      arrGridPanel.refresh();
                    }
                    recalcWrapperSize();
                  },
                  () -> {
                    sidebarPanel.reloadLibrary();
                    floatingSidebar.reloadLibrary();
                  });
          dialog.selectMidiTab();
          dialog.setVisible(true);
        });

    JMenuItem prefItem = new JMenuItem("Preferences...");
    prefItem.addActionListener(
        e -> {
          PreferencesDialog dialog =
              new PreferencesDialog(
                  SwingDelugeApp.this,
                  midiService,
                  () -> {
                    org.deluge.project.PreferencesManager.GridMode mode =
                        org.deluge.project.PreferencesManager.getGridMode();
                    if (clipPanel != null) {
                      clipPanel.setGridMode(mode);
                      clipPanel.refresh();
                    }
                    if (songPanel != null) {
                      songPanel.setGridMode(mode);
                      songPanel.refresh();
                    }
                    if (arrGridPanel != null) {
                      arrGridPanel.setGridMode(mode);
                      arrGridPanel.refresh();
                    }
                    recalcWrapperSize();
                  },
                  () -> {
                    sidebarPanel.reloadLibrary();
                    floatingSidebar.reloadLibrary();
                  });
          dialog.setVisible(true);
        });

    showMonitorItem = new JCheckBoxMenuItem("Acoustics Monitor");
    showMonitorItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_M, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    showMonitorItem.addActionListener(
        e -> {
          if (rightFloat != null) {
            rightFloat.setVisible(showMonitorItem.isSelected());
          }
        });

    settingsMenu.add(sampleItem);
    settingsMenu.addSeparator();
    JMenuItem tuningItem = new JMenuItem("Tuning & Temperaments...");
    tuningItem.addActionListener(
        e -> {
          SwingTuningDialog dialog =
              new SwingTuningDialog(
                  this,
                  currentProject,
                  () -> {
                    syncHighFidelityEngine(currentProject, true);
                  });
          dialog.setVisible(true);
        });

    settingsMenu.add(monitorInputItem);
    settingsMenu.add(midiConfigItem);
    settingsMenu.add(clearMidiItem);
    settingsMenu.addSeparator();
    settingsMenu.add(tuningItem);
    settingsMenu.addSeparator();
    settingsMenu.add(prefItem);
    settingsMenu.add(showMonitorItem);

    // Edit menu — Undo/Redo
    JMenu editMenu = new JMenu("Edit");

    JMenuItem undoItem = new JMenuItem("Undo");
    undoItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_Z, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    undoItem.addActionListener(e -> doUndo());

    JMenuItem redoItem = new JMenuItem("Redo");
    redoItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_Y, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    redoItem.addActionListener(e -> doRedo());

    editMenu.add(undoItem);
    editMenu.add(redoItem);
    editMenu.addSeparator();
    JCheckBoxMenuItem wrapItem =
        new JCheckBoxMenuItem("Cross-Screen Wrap Edits", SwingGridPanel.isCrossScreenWrapActive);
    wrapItem.addActionListener(e -> SwingGridPanel.isCrossScreenWrapActive = wrapItem.isSelected());
    editMenu.add(wrapItem);

    // Tools menu — Delugeator Randomizer & Loop Slicer
    JMenu toolsMenu = new JMenu("Tools");

    JMenuItem pianoRollItem = new JMenuItem("Piano Roll Editor...");
    pianoRollItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_P, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    pianoRollItem.addActionListener(
        e -> {
          if (clipPanel != null) clipPanel.openPianoRollForActiveClip();
        });
    toolsMenu.add(pianoRollItem);
    toolsMenu.addSeparator();

    JMenuItem randomizerItem = new JMenuItem("Delugeator Randomizer...");
    randomizerItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_R, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    randomizerItem.addActionListener(
        e -> {
          new SwingRandomizerDialog(this, bridge, currentProject).setVisible(true);
        });
    toolsMenu.add(randomizerItem);

    JMenuItem slicerItem = new JMenuItem("Audio Loop Slicer...");
    slicerItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_L, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    slicerItem.addActionListener(
        e -> {
          new SwingAudioSlicerDialog(this, bridge, currentProject).setVisible(true);
        });
    toolsMenu.add(slicerItem);

    JMenuItem thresholdSamplerItem = new JMenuItem("Threshold Loop Sampler...");
    thresholdSamplerItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_H, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    thresholdSamplerItem.addActionListener(
        e -> {
          new ThresholdRecordDialog(
                  this,
                  currentProject,
                  () -> {
                    // Rebuild the engine so the just-recorded sample is loaded into its kit slot /
                    // synth osc and is immediately audible, then refresh the grid.
                    syncHighFidelityEngine(currentProject, true);
                    if (songPanel != null) songPanel.refresh();
                    if (clipPanel != null) clipPanel.refresh();
                  })
              .setVisible(true);
        });
    toolsMenu.add(thresholdSamplerItem);

    JMenuItem droneLabItem = new JMenuItem("Drone Lab & Texture Generator...");
    droneLabItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_D, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    droneLabItem.addActionListener(
        e -> {
          SwingGridPanel a = activeGridPanel();
          if (a == null || a.getProjectModel() == null) {
            JOptionPane.showMessageDialog(
                this, "Please load a project first.", "Drone Lab", JOptionPane.WARNING_MESSAGE);
            return;
          }
          int idx = a.getEditedModelTrack();
          java.util.List<org.deluge.model.TrackModel> tl = a.getProjectModel().getTracks();
          if (idx < 0 || idx >= tl.size()) {
            JOptionPane.showMessageDialog(
                this,
                "Please select a valid track first.",
                "Drone Lab",
                JOptionPane.WARNING_MESSAGE);
            return;
          }
          org.deluge.model.TrackModel t = tl.get(idx);
          if (!(t instanceof org.deluge.model.SynthTrackModel st)) {
            JOptionPane.showMessageDialog(
                this,
                "Drone Lab requires a Synthesizer track. Please select or create a Synth track first.",
                "Drone Lab",
                JOptionPane.WARNING_MESSAGE);
            return;
          }
          new SwingDroneLabDialog(this, st, bridge, idx, currentProject).setVisible(true);
        });
    toolsMenu.add(droneLabItem);

    JMenuItem cleanRecordingsItem = new JMenuItem("Clean Unused Recordings...");
    cleanRecordingsItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_U, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    cleanRecordingsItem.addActionListener(
        e -> {
          new SwingRecordingCleanerDialog(this).setVisible(true);
        });
    toolsMenu.add(cleanRecordingsItem);

    // Help menu — Operations Manual JDialog
    JMenu helpMenu = new JMenu("Help");
    JMenuItem manualItem = new JMenuItem("Operations Manual...");
    manualItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F1, 0));
    manualItem.addActionListener(
        e -> {
          new SwingHelpDialog(this).setVisible(true);
        });
    helpMenu.add(manualItem);

    // View menu — Grid Zooming & Resolution Options
    JMenu viewMenu = new JMenu("View");

    JMenuItem zoomInItem = new JMenuItem("Zoom In (Larger Pads)");
    zoomInItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_EQUALS, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    zoomInItem.addActionListener(e -> zoomGrid(true));

    JMenuItem zoomOutItem = new JMenuItem("Zoom Out (Smaller Pads)");
    zoomOutItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_MINUS, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    zoomOutItem.addActionListener(e -> zoomGrid(false));

    viewMenu.add(zoomInItem);
    viewMenu.add(zoomOutItem);
    viewMenu.addSeparator();

    grid8x16Item = new JRadioButtonMenuItem("8x16 Grid (Large Pads)");
    grid16x16Item = new JRadioButtonMenuItem("16x16 Grid (Medium Pads)");
    grid24x16Item = new JRadioButtonMenuItem("24x16 Grid (Small Pads)");
    grid16x24Item = new JRadioButtonMenuItem("16x24 Grid (Wide Pads)");

    ButtonGroup gridGroup = new ButtonGroup();
    gridGroup.add(grid8x16Item);
    gridGroup.add(grid16x16Item);
    gridGroup.add(grid24x16Item);
    grid8x16Item.addActionListener(
        e -> updateGlobalGridMode(org.deluge.project.PreferencesManager.GridMode.GRID_8x16));
    grid16x16Item.addActionListener(
        e -> updateGlobalGridMode(org.deluge.project.PreferencesManager.GridMode.GRID_16x16));
    grid24x16Item.addActionListener(
        e -> updateGlobalGridMode(org.deluge.project.PreferencesManager.GridMode.GRID_24x16));
    grid16x24Item.addActionListener(
        e -> updateGlobalGridMode(org.deluge.project.PreferencesManager.GridMode.GRID_16x24));

    viewMenu.add(grid8x16Item);
    viewMenu.add(grid16x16Item);
    viewMenu.add(grid24x16Item);
    viewMenu.add(grid16x24Item);

    updateViewMenuChecks();

    // ── Macro Recording & Playback Menu ──
    JMenu macroMenu = new JMenu("Macro");
    macroMenu.setFont(new Font("SansSerif", Font.PLAIN, 12));

    JMenuItem startRecordingItem = new JMenuItem("Start Recording Macro");
    JMenuItem stopRecordingItem = new JMenuItem("Stop Recording Macro");
    JMenuItem saveMacroItem = new JMenuItem("Save Macro Script...");
    JMenuItem playMacroItem = new JMenuItem("Play Macro Script...");

    stopRecordingItem.setEnabled(false);
    saveMacroItem.setEnabled(false);

    startRecordingItem.addActionListener(
        e -> {
          scriptingEngine.startRecording();
          startRecordingItem.setEnabled(false);
          stopRecordingItem.setEnabled(true);
          saveMacroItem.setEnabled(false);
          macroMenu.setText("Macro ●");
          macroMenu.setForeground(Color.RED);
        });

    stopRecordingItem.addActionListener(
        e -> {
          scriptingEngine.stopRecording();
          startRecordingItem.setEnabled(true);
          stopRecordingItem.setEnabled(false);
          saveMacroItem.setEnabled(!scriptingEngine.getRecordedActions().isEmpty());
          macroMenu.setText("Macro");
          macroMenu.setForeground(null);
          JOptionPane.showMessageDialog(
              this,
              "Macro recording stopped.\n"
                  + scriptingEngine.getRecordedActions().size()
                  + " actions recorded.",
              "Macro Recorder",
              JOptionPane.INFORMATION_MESSAGE);
        });

    saveMacroItem.addActionListener(
        e -> {
          FileDialog fd = new FileDialog(this, "Save Macro Script", FileDialog.SAVE);
          fd.setFile("*.txt");
          fd.setVisible(true);
          String dir = fd.getDirectory();
          String file = fd.getFile();
          if (dir != null && file != null) {
            try {
              scriptingEngine.saveScript(new java.io.File(dir, file));
              JOptionPane.showMessageDialog(
                  this,
                  "Macro script saved successfully to:\n" + file,
                  "Success",
                  JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
              JOptionPane.showMessageDialog(
                  this,
                  "Error saving macro script:\n" + ex.getMessage(),
                  "Error",
                  JOptionPane.ERROR_MESSAGE);
            }
          }
        });

    playMacroItem.addActionListener(
        e -> {
          FileDialog fd = new FileDialog(this, "Load & Run Macro Script", FileDialog.LOAD);
          fd.setFile("*.txt");
          fd.setVisible(true);
          String dir = fd.getDirectory();
          String file = fd.getFile();
          if (dir != null && file != null) {
            try {
              var executed =
                  scriptingEngine.loadAndExecuteScript(new java.io.File(dir, file), currentProject);
              pushModelToBridge();
              refreshGrids();
              JOptionPane.showMessageDialog(
                  this,
                  "Macro executed successfully!\n" + executed.size() + " actions applied.",
                  "Success",
                  JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
              JOptionPane.showMessageDialog(
                  this,
                  "Error executing macro script:\n" + ex.getMessage(),
                  "Error",
                  JOptionPane.ERROR_MESSAGE);
            }
          }
        });

    macroMenu.add(startRecordingItem);
    macroMenu.add(stopRecordingItem);
    macroMenu.addSeparator();
    macroMenu.add(saveMacroItem);
    macroMenu.add(playMacroItem);

    menuBar.add(fileMenu);
    menuBar.add(editMenu);
    menuBar.add(macroMenu);
    menuBar.add(viewMenu);
    menuBar.add(toolsMenu);
    menuBar.add(settingsMenu);
    menuBar.add(helpMenu);
    setJMenuBar(menuBar);

    // Global undo/redo keyboard shortcuts (always active regardless of focus)
    getRootPane()
        .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        .put(
            KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_Z, java.awt.event.InputEvent.CTRL_DOWN_MASK),
            "undo");
    getRootPane()
        .getActionMap()
        .put(
            "undo",
            new AbstractAction() {
              @Override
              public void actionPerformed(java.awt.event.ActionEvent e) {
                doUndo();
              }
            });
    getRootPane()
        .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        .put(
            KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_Y, java.awt.event.InputEvent.CTRL_DOWN_MASK),
            "redo");
    getRootPane()
        .getActionMap()
        .put(
            "redo",
            new AbstractAction() {
              @Override
              public void actionPerformed(java.awt.event.ActionEvent e) {
                doRedo();
              }
            });

    // Global record toggle keyboard shortcut (R key)
    getRootPane()
        .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        .put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_R, 0), "toggleRecord");
    getRootPane()
        .getActionMap()
        .put(
            "toggleRecord",
            new AbstractAction() {
              @Override
              public void actionPerformed(java.awt.event.ActionEvent e) {
                if (topBar != null && topBar.getRecBtn() != null) {
                  topBar.getRecBtn().doClick();
                }
              }
            });

    // F12 debug screenshot listener (programmatic memory capture)
    getRootPane()
        .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        .put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F12, 0), "screenshot");
    getRootPane()
        .getActionMap()
        .put(
            "screenshot",
            new AbstractAction() {
              @Override
              public void actionPerformed(java.awt.event.ActionEvent e) {
                try {
                  java.io.File scratchDir =
                      new java.io.File(System.getProperty("user.home"), ".deluge/screenshots");
                  if (!scratchDir.exists()) scratchDir.mkdirs();
                  java.io.File file = new java.io.File(scratchDir, "swing_screenshot.png");

                  java.awt.image.BufferedImage img =
                      new java.awt.image.BufferedImage(
                          getWidth(), getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                  java.awt.Graphics2D g = img.createGraphics();
                  paint(g);
                  g.dispose();

                  javax.imageio.ImageIO.write(img, "png", file);
                  System.out.println(
                      "[Debug] Screenshot captured successfully to: " + file.getAbsolutePath());

                  JOptionPane.showMessageDialog(
                      SwingDelugeApp.this,
                      "Screenshot captured successfully to:\n" + file.getName(),
                      "Screenshot Captured",
                      JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                  ex.printStackTrace();
                }
              }
            });

    leftFloat = new JDialog(this, "SD Explorer", false);
    leftFloat.setSize(300, 700);
    leftFloat.setLocation(50, 150);

    rightFloat = new JDialog(this, "Acoustics Monitor", false);
    rightFloat.setSize(280, 700);
    rightFloat.setLocation(1600, 150);
    rightFloat.addWindowListener(
        new java.awt.event.WindowAdapter() {
          @Override
          public void windowOpened(java.awt.event.WindowEvent e) {
            if (showMonitorItem != null) showMonitorItem.setSelected(true);
          }

          @Override
          public void windowClosing(java.awt.event.WindowEvent e) {
            if (showMonitorItem != null) showMonitorItem.setSelected(false);
          }

          @Override
          public void windowClosed(java.awt.event.WindowEvent e) {
            if (showMonitorItem != null) showMonitorItem.setSelected(false);
          }
        });

    // 1. Top Area (Buttons, Modes, Transport, Sliders)

    cardLayout = new CardLayout();
    centerCardPanel = new JPanel(cardLayout);

    Runnable projectChangeHandler =
        () -> {
          propagateCurrentModel();
          syncHighFidelityEngine(currentProject);
        };

    clipPanel = new ClipGridPanel(bridge);
    clipPanel.setViewMode(SwingGridPanel.GridViewMode.CLIP);
    clipPanel.setProjectModel(currentProject);
    clipPanel.resetScrollOffset();
    clipPanel.setOnProjectChanged(projectChangeHandler);
    clipPanel.setOnClipChanged(
        () -> {
          propagateCurrentModel();
          pushModelToBridge();
          syncHighFidelityEngine(currentProject);
          clipPanel.refresh();
        });
    centerCardPanel.add(wrapGridPanel(clipPanel), "CLIP");

    // Wire PIC transport to Swing pad buttons for protocol-level pad rendering
    this.picTransport = new org.deluge.hid.pic.SwingPicTransport();
    picTransport.setPadButtons(clipPanel.getPadButtons());
    PIC.setTransport(picTransport);

    songPanel = new SongGridPanel(bridge);
    songPanel.setViewMode(SwingGridPanel.GridViewMode.SONG);
    songPanel.setProjectModel(currentProject);
    songPanel.setOnProjectChanged(projectChangeHandler);
    centerCardPanel.add(wrapGridPanel(songPanel), "SONG");

    arrGridPanel = new ArrangerGridPanel(bridge);
    arrGridPanel.setViewMode(SwingGridPanel.GridViewMode.ARRANGEMENT);
    arrGridPanel.setProjectModel(currentProject);
    arrGridPanel.setOnProjectChanged(projectChangeHandler);
    centerCardPanel.add(wrapGridPanel(arrGridPanel), "ARR");

    autoPanel = new AutomationGridPanel(bridge);
    autoPanel.setViewMode(SwingGridPanel.GridViewMode.AUTOMATION);
    autoPanel.setProjectModel(currentProject);
    autoPanel.setOnProjectChanged(projectChangeHandler);
    centerCardPanel.add(wrapGridPanel(autoPanel), "AUTO");

    performancePanel = new SwingPerformanceViewPanel(bridge, currentProject);
    performancePanel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
    centerCardPanel.add(performancePanel, "PERF");

    appTopBarListener = new AppTopBarListener();
    topBar = new SwingTopBarPanel(bridge, currentProject, leftFloat, appTopBarListener);

    // DEBUG: solid background colors to visualize panel sizes
    System.out.println(
        "DEBUG setupUI: topBar bg="
            + topBar.getBackground()
            + " contentPane bg="
            + getContentPane().getBackground());

    JPanel topBarWrapper = new JPanel(new BorderLayout());
    topBarWrapper.add(topBar, BorderLayout.CENTER);

    // Scroll encoders (X timeline / Y note rows) routed to whichever grid panel is showing.
    org.deluge.ui.controls.DelugeEncoderStrip encoderStrip =
        new org.deluge.ui.controls.DelugeEncoderStrip(
            d -> {
              SwingGridPanel a = activeGridPanel();
              if (a != null) {
                if (a.isShiftHeld()) {
                  a.adjustZoomResolution(d);
                } else {
                  a.scrollHorizontally(d);
                }
              }
            },
            d -> {
              SwingGridPanel a = activeGridPanel();
              if (a != null) {
                if (isLearnHeld()) {
                  a.adjustTrackColorOffset(d); // Learn + Turn Y = Change track color!
                } else if (a.isShiftHeld()) {
                  a.scrollVertically(-d * 12); // Shift + Turn Y = Octave scroll (12 rows)!
                } else {
                  a.scrollVertically(-d); // Turn Y = Single-row scroll!
                }
              }
            },
            d -> {
              // Gold mod-encoder: adjust whichever param is selected via the Shift overlay.
              SwingGridPanel a = activeGridPanel();
              if (a != null && a.getActiveShiftParam() != null) {
                a.adjustRotaryParameter(d);
              }
            });

    encoderStrip
        .getXKnob()
        .onPress(
            () -> {
              SwingGridPanel a = activeGridPanel();
              if (a != null) {
                if (isLearnHeld()) {
                  if (a.isShiftHeld()) {
                    a.pasteClipNotes(); // Learn + Shift + Click = Paste!
                  } else {
                    a.copyClipNotes(); // Learn + Click = Copy!
                  }
                } else if (a.isShiftHeld()) {
                  a.duplicateTrackContent(); // Shift + Click = Duplicate!
                }
              }
            });

    encoderStrip
        .getXKnob()
        .onPressTurn(
            d -> {
              SwingGridPanel a = activeGridPanel();
              if (a != null) {
                a.adjustZoomResolution(d);
              }
            });

    encoderStrip
        .getYKnob()
        .onPressTurn(
            d -> {
              SwingGridPanel a = activeGridPanel();
              if (a != null) {
                if (a.isShiftHeld()) {
                  a.transposeTrack(d * 12); // Shift + Press-Turn Y = Octave transposition!
                } else {
                  a.transposeTrack(d); // Press-Turn Y = Semitone transposition!
                }
              }
            });

    encoderStrip.setBackground(topBar.getBackground());
    encoderStrip.setOpaque(true);
    topBarWrapper.add(encoderStrip, BorderLayout.EAST);
    add(topBarWrapper, BorderLayout.NORTH);

    JScrollPane centerScroll =
        new JScrollPane(
            centerCardPanel,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

    // DEBUG: centerScroll.setBorder(BorderFactory.createLineBorder(Color.YELLOW, 3));
    /*centerScroll.getViewport().setOpaque(true);
    centerScroll.getViewport().setBackground(Color.BLUE);*/

    // Fixed track-inspector strip ABOVE the scrolling grid: per-track controls (configure + preset)
    // belong outside the scroll area so you never have to scroll up to reach them.
    JPanel centerWrap = new JPanel(new BorderLayout());
    centerWrap.setOpaque(false);
    centerWrap.add(buildTrackInspectorStrip(), BorderLayout.NORTH);
    centerWrap.add(centerScroll, BorderLayout.CENTER);
    add(centerWrap, BorderLayout.CENTER);

    // Hardware-faithful Gold Knobs & Mod Buttons bar docked WEST
    modKnobBar =
        new org.deluge.ui.controls.DelugeModKnobBar(
            bridge,
            () -> {
              SwingGridPanel a = activeGridPanel();
              if (a == null || a.getProjectModel() == null) {
                return null;
              }
              int idx = a.getEditedModelTrack();
              if (idx < 0 || idx >= a.getProjectModel().getTracks().size()) {
                return null;
              }
              return (a.getProjectModel().getTracks().get(idx)
                      instanceof org.deluge.model.SynthTrackModel st)
                  ? st
                  : null;
            },
            () -> {
              SwingGridPanel a = activeGridPanel();
              return a == null ? -1 : a.getEditedModelTrack();
            },
            this::activeGridPanel);
    add(modKnobBar, BorderLayout.WEST);

    // Always-visible synth param rack docked EAST (collapsible via the RACK button). It costs only
    // horizontal space (abundant) so the grid keeps its full height; scrollable for short screens.
    synthParamRack =
        new SynthParamRack(
            bridge,
            () -> {
              SwingGridPanel a = activeGridPanel();
              if (a == null || a.getProjectModel() == null) {
                return null;
              }
              int idx = a.getEditedModelTrack();
              if (idx < 0 || idx >= a.getProjectModel().getTracks().size()) {
                return null;
              }
              return (a.getProjectModel().getTracks().get(idx)
                      instanceof org.deluge.model.SynthTrackModel st)
                  ? st
                  : null;
            },
            () -> {
              SwingGridPanel a = activeGridPanel();
              return a == null ? -1 : a.getEditedModelTrack();
            });
    synthParamRack.setPresetActions(
        f -> { // Replace the edited synth track's preset in place (preserve clips + colour).
          try {
            SwingGridPanel a = activeGridPanel();
            if (a == null || a.getProjectModel() == null) return;
            int idx = a.getEditedModelTrack();
            java.util.List<org.deluge.model.TrackModel> tl = a.getProjectModel().getTracks();
            if (idx < 0 || idx >= tl.size()) return;
            org.deluge.model.TrackModel old = tl.get(idx);
            org.deluge.model.SynthTrackModel nt = org.deluge.xml.DelugeXmlParser.parseSynth(f);
            nt.getClips().clear();
            for (org.deluge.model.ClipModel cm : old.getClips()) nt.addClip(cm);
            nt.setColourHex(old.getColourHex());
            tl.set(idx, nt);
            propagateCurrentModel();
            syncHighFidelityEngine(currentProject, true); // preset swap: rebuild even while playing
            a.forceRebuild(); // structure unchanged on a swap → force header/name rebuild
            synthParamRack.refresh();
            if (modKnobBar != null) modKnobBar.refresh();
          } catch (Exception ex) {
            System.err.println("[PresetChip] replace failed: " + ex.getMessage());
          }
        },
        f -> { // Load the preset as a brand-new synth track.
          try {
            org.deluge.model.SynthTrackModel nt = org.deluge.xml.DelugeXmlParser.parseSynth(f);
            currentProject.addTrack(nt);
            propagateCurrentModel();
            syncHighFidelityEngine(currentProject);
            if (clipPanel != null) clipPanel.refresh();
            synthParamRack.refresh();
            if (modKnobBar != null) modKnobBar.refresh();
          } catch (Exception ex) {
            System.err.println("[PresetChip] load-new failed: " + ex.getMessage());
          }
        });
    rackScroll =
        new JScrollPane(
            synthParamRack,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    rackScroll.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(0x2d, 0x2d, 0x34)));
    rackScroll.getVerticalScrollBar().setUnitIncrement(16);
    rackScroll.setPreferredSize(new Dimension(312, 100));
    add(rackScroll, BorderLayout.EAST);

    javax.swing.SwingUtilities.invokeLater(() -> centerScroll.getVerticalScrollBar().setValue(0));

    // 2. Left Area (SD Card / Editors)
    sidebarPanel = new SwingProjectSidebarPanel(bridge, midiService);
    sidebarPanel.reloadLibrary();
    floatingSidebar = new SwingProjectSidebarPanel(bridge, midiService);
    // The explorer header's 📂 button changes the SD-card root; refresh BOTH sidebar instances
    // (same effect as File → "Set SD Card Root..." and Preferences → SD Card Root Directory).
    Runnable reloadBothSidebars =
        () -> {
          sidebarPanel.reloadLibrary();
          floatingSidebar.reloadLibrary();
        };
    sidebarPanel.setOnLibraryDirChanged(reloadBothSidebars);
    floatingSidebar.setOnLibraryDirChanged(reloadBothSidebars);
    sidebarPanel.setOnSongLoaded(
        (model, file) -> {
          // Load shared project state
          loadProject(model);
        });

    floatingSidebar.setOnSongLoaded(sidebarPanel.getOnSongLoaded());

    // Wire sidebar "add track" callback — KITS/SYNTHS double-click adds to current project
    java.util.function.Consumer<org.deluge.model.TrackModel> addTrack =
        track -> {
          // Add a default clip so notes entered on grid are stored in the model
          switch (track) {
            case org.deluge.model.KitTrackModel kit -> {
              int rowCount = kit.getDrums().size();
              if (rowCount < 1) rowCount = 1;
              kit.addClip(new org.deluge.model.ClipModel("CLIP 1", rowCount, 16));
            }
            case org.deluge.model.SynthTrackModel synth ->
                synth.addClip(new org.deluge.model.ClipModel("CLIP 1", 8, 16));
            default -> {}
          }
          int idx = currentProject.getTracks().size();
          currentProject.addTrack(track);
          currentProject
              .getUndoRedoStack()
              .push(
                  new Consequence.TrackStructureConsequence(
                      currentProject,
                      Consequence.TrackStructureConsequence.ADD,
                      idx,
                      track,
                      "Add track"));
          syncCoordinator.pushModelToBridge();
          propagateCurrentModel();
          syncHighFidelityEngine(currentProject);
          refreshGrids();

          int engineBase = syncCoordinator.getTrackEngineStart(idx);
          if (engineBase >= 0) {
            clipPanel.setBaseTrackId(engineBase);
            clipPanel.setEditedModelTrack(idx);
            clipPanel.setActiveClipId(0);
            clipPanel.refresh();
          }

          cardLayout.show(centerCardPanel, "CLIP");
          if (topBar != null) topBar.selectClipView();
        };
    sidebarPanel.setOnTrackAdded(addTrack);
    floatingSidebar.setOnTrackAdded(addTrack);
    sidebarPanel.setOnPatternSave(this::saveCurrentClipAsPattern);
    sidebarPanel.setOnPatternLoad(this::loadPatternIntoActiveTrack);
    floatingSidebar.setOnPatternSave(this::saveCurrentClipAsPattern);
    floatingSidebar.setOnPatternLoad(this::loadPatternIntoActiveTrack);

    songPanel.setOnEditRequest(this::switchToTrackEdit);
    arrGridPanel.setOnEditRequest(this::switchToTrackEdit);

    visualizerPanel = new SwingVisualizerPanel(bridge);

    leftFloat.add(floatingSidebar);
    rightFloat.add(visualizerPanel);

    visualizerRepaintTimer = new Timer(33, e -> visualizerPanel.repaint());
    visualizerRepaintTimer.start();

    // Periodically flush PIC framebuffer to Swing pad buttons (≈30 fps)
    if (this.pureEngine == null) {
      picTransportFlushTimer =
          new Timer(
              33,
              e -> {
                if (clipPanel != null && clipPanel.isShiftHeld()) {
                  return;
                }
                if (songPanel != null && songPanel.isShiftHeld()) {
                  return;
                }
                if (arrGridPanel != null && arrGridPanel.isShiftHeld()) {
                  return;
                }
                if (bridge != null && bridge.getPlayState() == 0) {
                  return;
                }
                picTransport.flush();
              });
      picTransportFlushTimer.start();
    }

    // bottom lane purged

    // 5. Bottom Area - Rows 9 and 10 (Param Deck)
    // Obsolete bottom parameter deck removed. Integrated in 10x18 pads matrix.

    // 7. Bottom Area - Row 3 (Master FX dials bounding boxes)
    SwingMasterFxPanel masterFxPanel = new SwingMasterFxPanel(bridge, currentProject, topBar);
    this.masterFxPanel = masterFxPanel;
    // DEBUG: masterFxPanel.setBorder(BorderFactory.createLineBorder(Color.YELLOW, 3));

    masterFxPanel.setPreferredSize(new Dimension(1, 54));
    add(masterFxPanel, BorderLayout.SOUTH);

    revalidate();

    // Push default project to engine and broadcast load trigger to unblock shreds
    pushModelToBridge();

    // Programmatic startup song file loader!
    if (startupFilePath != null) {
      java.io.File file = new java.io.File(startupFilePath);
      if (file.exists()) {
        try {
          org.deluge.model.ProjectModel loaded = org.deluge.xml.DelugeXmlParser.parseSong(file);
          currentProjectFile = file;
          loadProject(loaded);
          System.out.println(
              "[main] Successfully pre-loaded startup song project: " + file.getName());
        } catch (Exception ex) {
          System.err.println("[main] Startup load failed: " + ex.getMessage());
        }
      }
    }
    // Only run the (grid-mode-cycling) screenshot pipeline when explicitly requested via
    // --screenshot. On a normal boot this would visibly resize the grid 2-3 times.
    if (autoScreenshotOnBoot) {
      captureAutoScreenshot("startup");
    }
  }

  /**
   * Launch a second, fully independent Deluge in a NEW OS process (its own JVM). We deliberately do
   * not run two instances in one JVM: the firmware/hid layer (MatrixDriver, Flasher, PIC,
   * FirmwareDisplay) and a few audio statics (noise seed, sidechain bus, cpuDireness) plus {@code
   * mainInstance} are process-global singletons that two in-JVM instances would corrupt. A separate
   * process sidesteps all of that and gets its own audio line (the OS mixer sums them).
   *
   * <p>We relaunch the *exact* current command (java binary + all JVM flags + jar) via {@link
   * ProcessHandle}, so required flags (--enable-preview, --add-modules jdk.incubator.vector, ZGC,
   * native-access) are preserved. Falls back to reconstructing from the running JVM if the OS does
   * not expose the command line.
   */
  private void launchNewInstance() {
    try {
      java.util.List<String> cmd = new java.util.ArrayList<>();
      ProcessHandle.Info info = ProcessHandle.current().info();
      String exe = info.command().orElse(null);
      String[] args = info.arguments().orElse(null);
      if (exe != null && args != null) {
        cmd.add(exe);
        java.util.Collections.addAll(cmd, args);
      } else {
        // Fallback: java.home/bin/java + the JVM's own input args + classpath + this main class.
        cmd.add(
            System.getProperty("java.home")
                + java.io.File.separator
                + "bin"
                + java.io.File.separator
                + "java");
        cmd.addAll(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(SwingDelugeApp.class.getName());
      }
      ProcessBuilder pb = new ProcessBuilder(cmd);
      pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
      pb.redirectError(ProcessBuilder.Redirect.DISCARD);
      pb.start();
      System.out.println("[NewInstance] Launched a second Deluge process: " + cmd);
    } catch (Exception ex) {
      System.err.println("[NewInstance] Failed to launch: " + ex.getMessage());
      JOptionPane.showMessageDialog(
          this,
          "Could not launch a new Deluge window:\n" + ex.getMessage(),
          "New Window",
          JOptionPane.ERROR_MESSAGE);
    }
  }

  /** No-op: centeredWrapper removed, scroll pane sizes to content naturally. */
  public void recalcWrapperSize() {
    // content naturally sizes the scroll viewport
  }

  private void captureSingleScreenshot(String name) {
    try {
      java.io.File scratchDir =
          new java.io.File(System.getProperty("user.home"), ".deluge/screenshots");
      if (!scratchDir.exists()) scratchDir.mkdirs();
      java.io.File file = new java.io.File(scratchDir, name + ".png");

      java.awt.image.BufferedImage img =
          new java.awt.image.BufferedImage(
              getWidth() > 0 ? getWidth() : 1200,
              getHeight() > 0 ? getHeight() : 800,
              java.awt.image.BufferedImage.TYPE_INT_RGB);
      java.awt.Graphics2D g = img.createGraphics();
      paint(g);
      g.dispose();

      javax.imageio.ImageIO.write(img, "png", file);
      System.out.println("[AutoScreenshot] Captured successfully: " + file.getAbsolutePath());
    } catch (Exception ex) {
      System.err.println("[AutoScreenshot] Failed: " + ex.getMessage());
    }
  }

  public void captureAutoScreenshot(String name) {
    if (!"startup".equals(name)) {
      javax.swing.SwingUtilities.invokeLater(
          () -> {
            javax.swing.Timer t =
                new javax.swing.Timer(
                    800,
                    e -> {
                      captureSingleScreenshot(name);
                    });
            t.setRepeats(false);
            t.start();
          });
      return;
    }

    javax.swing.SwingUtilities.invokeLater(
        () -> {
          // Step 1: Wait 800ms, then capture default startup screenshot (GRID_16x24)
          javax.swing.Timer t1 =
              new javax.swing.Timer(
                  800,
                  e1 -> {
                    captureSingleScreenshot("startup");

                    // Step 2: Switch to GRID_8x16 zoom, repaint, and wait 800ms to capture
                    org.deluge.project.PreferencesManager.GridMode originalMode =
                        clipPanel.getGridMode();
                    clipPanel.setGridMode(org.deluge.project.PreferencesManager.GridMode.GRID_8x16);
                    clipPanel.refresh();
                    revalidate();
                    repaint();

                    javax.swing.Timer t2 =
                        new javax.swing.Timer(
                            800,
                            e2 -> {
                              captureSingleScreenshot("zoom_GRID_8x16");

                              // Step 3: Switch to GRID_24x16 zoom, repaint, and wait 800ms to
                              // capture
                              clipPanel.setGridMode(
                                  org.deluge.project.PreferencesManager.GridMode.GRID_24x16);
                              clipPanel.refresh();
                              revalidate();
                              repaint();

                              javax.swing.Timer t3 =
                                  new javax.swing.Timer(
                                      800,
                                      e3 -> {
                                        captureSingleScreenshot("zoom_GRID_24x16");

                                        // Step 4: Restore original zoom level and refresh
                                        clipPanel.setGridMode(originalMode);
                                        clipPanel.refresh();
                                        revalidate();
                                        repaint();
                                        System.out.println(
                                            "[AutoScreenshot] Complete visual capture pipeline finished!");
                                      });
                              t3.setRepeats(false);
                              t3.start();
                            });
                    t2.setRepeats(false);
                    t2.start();
                  });
          t1.setRepeats(false);
          t1.start();
        });
  }

  private void startPlaybackTimer() {
    if (statusTextPlaybackTimer != null) {
      statusTextPlaybackTimer.stop();
    }
    statusTextPlaybackTimer =
        new Timer(
            30,
            e -> {
              int step = (int) bridge.getGlobalInt(BridgeContract.G_CURRENT_STEP);

              int stepCount = 16;
              int beatSteps = 4;
              if (currentProject != null
                  && !currentProject.getTracks().isEmpty()
                  && !currentProject.getTracks().get(0).getClips().isEmpty()) {
                boolean isTriplet =
                    currentProject.getTracks().get(0).getClips().get(0).isTripletMode();
                stepCount = isTriplet ? 12 : 16;
                beatSteps = isTriplet ? 3 : 4;
              }

              int bar = step / stepCount + 1;
              int beat = (step % stepCount) / beatSteps + 1;
              int subStep = (step % beatSteps) + 1;

              String statusStr = "STOP";
              if (bridge.getGlobalInt(BridgeContract.G_PLAY) == 1L) {
                statusStr = String.format("%d.%d.%d", bar, beat, subStep);
              }
              statusStr += " | SHREDS: " + bridge.getActiveShredCount();

              Component[] comps = getContentPane().getComponents();
              for (Component c : comps) {
                if (c instanceof JPanel p) {
                  for (Component child : p.getComponents()) {
                    if (child instanceof JLabel l && l.getForeground().equals(Color.GREEN)) {
                      l.setText(statusStr);
                    }
                  }
                }
              }

              if (clipPanel != null) {
                clipPanel.updatePlayhead(step);
              }
              if (songPanel != null) {
                songPanel.updatePlayhead(step);
              }
              if (arrGridPanel != null) {
                arrGridPanel.updatePlayhead(step);
              }

              if (visualizerPanel != null) {
                visualizerPanel.repaint();
              }
            });
    statusTextPlaybackTimer.start();
  }

  public static String startupFilePath = null;

  public static void main(String[] args) {
    for (String arg : args) {
      if (arg.toUpperCase().endsWith(".XML")) {
        startupFilePath = arg;
      }
    }
    // Configure global tooltip timing parameters (pop up after 250ms, keep open for 20s)
    javax.swing.ToolTipManager.sharedInstance().setDismissDelay(20000);
    javax.swing.ToolTipManager.sharedInstance().setInitialDelay(250);

    BridgeContract bridge = new BridgeContract();

    // The pure Java firmware engine (PureFirmwareEngine) is the only audio path; the legacy
    // ChucK DSL engine (--hifi) was deleted.
    System.out.println(
        "[main] Pure Java (Pure Firmware) direct soundcard output ENABLED by default");

    boolean runScreenshots = false;
    for (String arg : args) {
      if ("--screenshot".equalsIgnoreCase(arg)) {
        runScreenshots = true;
      }
    }
    final boolean finalRunScreenshots = runScreenshots;
    // Gate the boot grid-mode-cycling screenshot pipeline (set before the app is constructed, since
    // the constructor runs the boot path).
    autoScreenshotOnBoot = runScreenshots;

    bridge.register(bridge);

    // Give engine time to initialize before UI loads
    try {
      Thread.sleep(200);
    } catch (InterruptedException ie) {
    }
    System.out.println("[main] after 200ms sleep, activeShreds=" + bridge.getActiveShredCount());

    org.deluge.midi.MidiInputRouter router = new org.deluge.midi.MidiInputRouter(bridge);
    org.deluge.midi.MidiService midiService = new org.deluge.midi.MidiService(bridge, router);
    midiService.start();

    final boolean finalPureMode = true;
    java.awt.EventQueue.invokeLater(
        () -> {
          javax.swing.UIManager.put(
              "Menu.font", new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11));
          javax.swing.UIManager.put(
              "MenuItem.font", new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11));
          javax.swing.UIManager.put(
              "MenuBar.font", new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11));
          javax.swing.UIManager.put(
              "CheckBoxMenuItem.font", new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11));
          javax.swing.UIManager.put(
              "RadioButtonMenuItem.font", new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11));

          // Global JComboBox theme overrides (removes white-on-white text issues)
          javax.swing.UIManager.put("ComboBox.background", new java.awt.Color(0x2d, 0x2d, 0x30));
          javax.swing.UIManager.put("ComboBox.foreground", java.awt.Color.WHITE);
          javax.swing.UIManager.put(
              "ComboBox.selectionBackground", new java.awt.Color(0x00, 0x7a, 0xcc));
          javax.swing.UIManager.put("ComboBox.selectionForeground", java.awt.Color.WHITE);
          javax.swing.UIManager.put(
              "ComboBox.buttonBackground", new java.awt.Color(0x2d, 0x2d, 0x30));
          javax.swing.UIManager.put(
              "ComboBox.buttonDarkShadow", new java.awt.Color(0x1a, 0x1a, 0x1c));

          // Global dropdown list/viewport popups overrides (solves white list background)
          javax.swing.UIManager.put("List.background", new java.awt.Color(0x1e, 0x1e, 0x20));
          javax.swing.UIManager.put("List.foreground", java.awt.Color.WHITE);
          javax.swing.UIManager.put(
              "List.selectionBackground", new java.awt.Color(0x00, 0x7a, 0xcc));
          javax.swing.UIManager.put("List.selectionForeground", java.awt.Color.WHITE);

          // Global JSlider UI delegate registration (solves black-on-black tracks bug!)
          javax.swing.UIManager.put("SliderUI", "org.deluge.ui.DarkSliderUI");

          // Global text inputs focus and contrast overrides
          javax.swing.UIManager.put("TextField.background", new java.awt.Color(0x1e, 0x1e, 0x20));
          javax.swing.UIManager.put("TextField.foreground", java.awt.Color.WHITE);
          javax.swing.UIManager.put("TextField.caretForeground", java.awt.Color.WHITE);

          javax.swing.UIManager.put("TextArea.background", new java.awt.Color(0x1e, 0x1e, 0x20));
          javax.swing.UIManager.put("TextArea.foreground", java.awt.Color.WHITE);
          javax.swing.UIManager.put("TextArea.caretForeground", java.awt.Color.WHITE);

          SwingDelugeApp app = new SwingDelugeApp(bridge, midiService, finalPureMode);
          app.setVisible(true);

          // Benchmark/training hook: clean self-exit after N ms (System.exit -> normal shutdown, so
          // an -XX:AOTCacheOutput training run actually writes its cache). Off unless set.
          long benchExitMs = Long.getLong("deluge.benchExitMs", -1L);
          if (benchExitMs >= 0) {
            javax.swing.Timer bx = new javax.swing.Timer((int) benchExitMs, e -> System.exit(0));
            bx.setRepeats(false);
            bx.start();
          }

          if (finalRunScreenshots) {
            new Thread(
                    () -> {
                      try {
                        System.out.println(
                            "[Screenshot] Waiting 4 seconds for UI repaint and Loom threads...");
                        Thread.sleep(4000);
                        SwingScreenshotGenerator.runAutoScreenshots(app, bridge);
                      } catch (Exception ex) {
                        System.err.println("[Screenshot] Trigger thread error: " + ex.getMessage());
                      }
                    })
                .start();
          }
          // Auto-load if a file path is provided as argument
          if (args.length > 0 && args[0] != null && !args[0].isEmpty()) {
            try {
              java.io.File f = new java.io.File(args[0]);
              if (f.exists()) {
                System.out.println("[main] Auto-loading: " + f.getAbsolutePath());
                org.deluge.model.ProjectModel model =
                    org.deluge.xml.DelugeXmlParser.parseSong(
                        new java.io.FileInputStream(f), f.getName());
                app.currentProjectFile = f;
                app.loadProject(model);
              }
            } catch (Exception ex) {
              System.err.println("[main] Auto-load failed: " + ex.getMessage());
            }
          }
        });
  }

  // ── Inner classes ──

  public SwingGridPanel getActiveGridPanel() {
    return activeGridPanel();
  }

  public void scrollActiveTrack(int offset) {
    if (currentProject == null) return;
    java.util.List<org.deluge.model.TrackModel> tracks = currentProject.getTracks();
    if (tracks.isEmpty()) return;

    int currentTrackIdx = clipPanel != null ? clipPanel.getEditedModelTrack() : 0;
    int newTrackIdx = currentTrackIdx + offset;
    if (newTrackIdx < 0) newTrackIdx = 0;
    if (newTrackIdx >= tracks.size()) newTrackIdx = tracks.size() - 1;

    if (newTrackIdx != currentTrackIdx) {
      final int targetTrack = newTrackIdx;
      javax.swing.SwingUtilities.invokeLater(
          () -> {
            switchToTrackEdit(targetTrack, 0);
          });
    }
  }

  public void cycleViewMode() {
    String[] modes = {"CLIP", "SONG", "ARR", "AUTO", "PERF"};
    int currentIdx = 0;
    for (int i = 0; i < modes.length; i++) {
      if (modes[i].equals(activeViewMode)) {
        currentIdx = i;
        break;
      }
    }
    int nextIdx = (currentIdx + 1) % modes.length;
    String nextMode = modes[nextIdx];

    activeViewMode = nextMode;
    cardLayout.show(centerCardPanel, nextMode);
    if (topBar != null) {
      topBar.selectViewModeButton(nextMode);
    }
    if ("CLIP".equals(nextMode) || "SONG".equals(nextMode)) {
      syncHighFidelityEngine(currentProject);
    }
  }

  public void switchToTrackEdit(int trackId, int clipId) {
    sidebarPanel.updateFocusTrack(trackId);
    if (midiService != null) {
      midiService.setActiveTrack(trackId);
    }

    if (clipPanel != null) {
      int engineBase = syncCoordinator.getTrackEngineStart(trackId);
      int voiceCount = syncCoordinator.getTrackVoiceCount(trackId);
      if (engineBase >= 0) {
        clipPanel.setActiveClipId(clipId);
        clipPanel.setBaseTrackId(engineBase);
        clipPanel.setEditedModelTrack(trackId);
        clipPanel.resetScrollOffset();

        java.util.List<org.deluge.model.TrackModel> allTrks =
            clipPanel.getProjectModel() != null
                ? clipPanel.getProjectModel().getTracks()
                : java.util.List.of();

        boolean editIsSynth =
            trackId < allTrks.size()
                && allTrks.get(trackId) instanceof org.deluge.model.SynthTrackModel;

        // Determine step count for clearing and re-pushing
        int clearSteps = 16;
        if (trackId < allTrks.size()) {
          org.deluge.model.TrackModel tModel = allTrks.get(trackId);
          if (clipId < tModel.getClips().size()) {
            clearSteps = tModel.getClips().get(clipId).getStepCount();
          }
        }

        // Clear engine rows for this track
        for (int v = 0; v < voiceCount; v++) {
          for (int s = 0; s < clearSteps; s++) bridge.setStep(engineBase + v, s, false);
        }

        if (trackId < allTrks.size()) {
          org.deluge.model.TrackModel tModel = allTrks.get(trackId);
          if (clipId < tModel.getClips().size()) {
            org.deluge.model.ClipModel cModel = tModel.getClips().get(clipId);
            int clipSteps = cModel.getStepCount();
            boolean isSynth = tModel instanceof org.deluge.model.SynthTrackModel;
            for (int r = 0; r < cModel.getRowCount(); r++) {
              for (int s = 0; s < clipSteps; s++) {
                org.deluge.model.StepData sd = cModel.getStep(r, s);
                if (sd != null && sd.active()) {
                  int targetRow = isSynth ? (127 - sd.pitch()) : r;
                  if (targetRow >= 0 && targetRow < voiceCount) {
                    bridge.setStep(engineBase + targetRow, s, true);
                  }
                }
              }
            }
          }
        }

        clipPanel.refresh();
      }
    }

    activeViewMode = "CLIP";
    cardLayout.show(centerCardPanel, "CLIP");
    if (topBar != null) topBar.selectViewModeButton("CLIP");
  }

  /** Handles top-bar view-mode and add-track actions. */
  private class AppTopBarListener implements SwingTopBarPanel.TopBarListener {
    @Override
    public void onLiveRecordToggle(JButton btn) {
      transportController.onLiveRecordToggle(btn);
    }

    @Override
    public void onResampleToggle(JButton btn) {
      transportController.onResampleToggle(btn);
    }

    @Override
    public void onViewModeChanged(String viewMode) {
      activeViewMode = viewMode;
      if ("KEYPLAY".equals(viewMode)) {
        if (clipPanel != null) {
          clipPanel.setViewMode(org.deluge.ui.SwingGridPanel.GridViewMode.KEYPLAY);
          clipPanel.refresh();
        }
        cardLayout.show(centerCardPanel, "CLIP");
      } else if ("CLIP".equals(viewMode)) {
        if (clipPanel != null) {
          clipPanel.setViewMode(org.deluge.ui.SwingGridPanel.GridViewMode.CLIP);
          clipPanel.refresh();
        }
        cardLayout.show(centerCardPanel, "CLIP");
      } else {
        cardLayout.show(centerCardPanel, viewMode);
      }

      // Update High-Fidelity UI Stack
      if ("CLIP".equals(viewMode) || "SONG".equals(viewMode) || "KEYPLAY".equals(viewMode)) {
        syncHighFidelityEngine(currentProject);
      }

      // Dynamic real-time Arranger Mode activation
      if (arrangerScheduler != null) {
        arrangerScheduler.setArrangerModeActive("ARR".equals(viewMode));
      }
    }

    @Override
    public void onArrangerCaptureToggle(boolean active) {
      if (arrangerScheduler != null) {
        arrangerScheduler.setCaptureActive(active);
      }
    }

    @Override
    public void onAddTrack(String type, boolean isShift) {
      String name;
      if (isShift) {
        name = type + " " + (currentProject.getTracks().size() + 1);
      } else {
        name =
            JOptionPane.showInputDialog(
                SwingDelugeApp.this,
                type + " track name:",
                type + " " + (currentProject.getTracks().size() + 1));
        if (name == null || name.isBlank()) return;
      }
      var stack = currentProject.getUndoRedoStack();
      int idx;
      switch (type) {
        case "KIT" -> {
          KitTrackModel kit = new KitTrackModel(name);
          kit.addDrum(new SoundDrum("Kick", ""));
          kit.addDrum(new SoundDrum("Snare", ""));
          kit.addDrum(new SoundDrum("Closed Hat", ""));
          kit.addDrum(new SoundDrum("Open Hat", ""));
          kit.addDrum(new SoundDrum("Clap", ""));
          kit.addDrum(new SoundDrum("Tom 1", ""));
          kit.addDrum(new SoundDrum("Tom 2", ""));
          kit.addDrum(new SoundDrum("Percussion", ""));
          kit.addClip(new ClipModel("CLIP 1", 8, 16));
          idx = currentProject.getTracks().size();
          currentProject.addTrack(kit);
          stack.push(
              new Consequence.TrackStructureConsequence(
                  currentProject,
                  Consequence.TrackStructureConsequence.ADD,
                  idx,
                  kit,
                  "Add kit track"));
        }
        case "SYNTH" -> {
          SynthTrackModel synth = new SynthTrackModel(name);
          synth.addClip(new ClipModel("CLIP 1", 8, 16));
          idx = currentProject.getTracks().size();
          currentProject.addTrack(synth);
          stack.push(
              new Consequence.TrackStructureConsequence(
                  currentProject,
                  Consequence.TrackStructureConsequence.ADD,
                  idx,
                  synth,
                  "Add synth track"));
        }
        case "AUDIO" -> {
          AudioTrackModel audio = new AudioTrackModel(name);
          audio.addClip(new ClipModel("CLIP 1", 1, 16));
          idx = currentProject.getTracks().size();
          currentProject.addTrack(audio);
          stack.push(
              new Consequence.TrackStructureConsequence(
                  currentProject,
                  Consequence.TrackStructureConsequence.ADD,
                  idx,
                  audio,
                  "Add audio track"));
        }
      }
      propagateCurrentModel();
    }

    @Override
    public void onPlayToggle() {
      transportController.onPlayToggle();
    }

    @Override
    public void onStop() {
      transportController.onStop();
    }

    @Override
    public void onMasterVolumeChanged(float vol) {
      transportController.onMasterVolumeChanged(vol);
    }
  }

  // BridgeProjectListener and helper methods moved to EngineSyncCoordinator.

  /**
   * Canonical scale cycle order — names understood by both parseScaleIndex and the grid colouring.
   */
  private static final String[] SCALE_CYCLE = {
    "Major",
    "Minor",
    "Harmonic Minor",
    "Melodic Minor",
    "Dorian",
    "Phrygian",
    "Lydian",
    "Mixolydian",
    "Locrian",
    "Whole Tone",
    "Whole Half Dim",
    "Half Whole Dim",
    "Pentatonic Major",
    "Pentatonic Minor",
    "Chromatic"
  };

  /** Advance to the next scale (Deluge SCALE / SHIFT+SCALE): updates model, engine, and grid. */
  public void cycleScale() {
    if (currentProject == null) {
      return;
    }
    String cur = currentProject.getScale();
    int idx = 0;
    for (int i = 0; i < SCALE_CYCLE.length; i++) {
      if (SCALE_CYCLE[i].equalsIgnoreCase(cur)) {
        idx = i;
        break;
      }
    }
    String next = SCALE_CYCLE[(idx + 1) % SCALE_CYCLE.length];
    currentProject.setScale(next);
    bridge.setGlobalInt(BridgeContract.G_SCALE, EngineSyncCoordinator.parseScaleIndex(next));
    if (topBar != null && topBar.getParamReadout() != null) {
      topBar.getParamReadout().printTransient("SCALE", next);
    }
    fireProjectChanged();
  }

  private static final String[] KEY_CYCLE = {
    "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
  };

  /** Advance to the next key (rootNote): updates model, engine, and grid. */
  public void cycleKey() {
    if (currentProject == null) {
      return;
    }
    String cur = currentProject.getKey();
    if (cur == null || cur.isEmpty() || cur.equals("NONE")) {
      cur = "C";
    }
    int idx = 0;
    for (int i = 0; i < KEY_CYCLE.length; i++) {
      if (KEY_CYCLE[i].equalsIgnoreCase(cur)) {
        idx = i;
        break;
      }
    }
    String next = KEY_CYCLE[(idx + 1) % KEY_CYCLE.length];
    currentProject.setKey(next);
    bridge.setGlobalInt(BridgeContract.G_ROOT_KEY, EngineSyncCoordinator.parseRootKey(next));
    if (topBar != null && topBar.getParamReadout() != null) {
      topBar.getParamReadout().printTransient("KEY", next);
    }
    fireProjectChanged();
  }

  // Smallest window we still lay out correctly — fits a 1366x768 laptop after screen margins.
  private static final int MIN_WINDOW_W = 1180;
  private static final int MIN_WINDOW_H = 680;

  /** Target window size for a Screen Resolution profile (before screen clamping). */
  private static java.awt.Dimension resolutionTarget(String res) {
    return switch (res == null ? "QHD" : res) {
      case "FHD" -> new java.awt.Dimension(1760, 980); // fits a 1920x1080 panel
      case "Retina" -> new java.awt.Dimension(2560, 1500);
      case "Default" -> null; // fit the current screen
      default -> new java.awt.Dimension(2360, 1340); // "QHD" — fits a 2560x1440 panel
    };
  }

  /**
   * Window size from the Screen Resolution preference, clamped to the physical screen (minus
   * margins for the title bar / taskbar) and floored to {@link #MIN_WINDOW_W}x{@link
   * #MIN_WINDOW_H}. This is what makes us actually comply with the preference while still fitting
   * low-res laptops.
   */
  /**
   * Pure window-size policy: a resolution profile clamped to the given screen (minus title/taskbar
   * margins) and floored to the minimum. Package-private + screen-size-injected so it is testable
   * headlessly.
   */
  static Dimension windowSizeFor(String res, int screenW, int screenH) {
    Dimension target = resolutionTarget(res);
    int maxW = Math.max(MIN_WINDOW_W, screenW - 16);
    int maxH = Math.max(MIN_WINDOW_H, screenH - 48);
    int w = (target == null) ? maxW : Math.min(target.width, maxW);
    int h = (target == null) ? maxH : Math.min(target.height, maxH);
    return new Dimension(Math.max(w, MIN_WINDOW_W), Math.max(h, MIN_WINDOW_H));
  }

  private static Dimension computeWindowSize() {
    Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
    return windowSizeFor(
        org.deluge.project.PreferencesManager.get("screen.resolution", "QHD"),
        screen.width,
        screen.height);
  }

  /**
   * Re-apply the Screen Resolution preference to the live window (called when the pref changes).
   */
  public void applyWindowResolution() {
    Dimension win = computeWindowSize();
    setSize(win.width, win.height);
    setLocationRelativeTo(null);
    revalidate();
    repaint();
  }

  /** Convert a scale name to an integer index for the bridge G_SCALE global. */
  // parseScaleIndex moved to EngineSyncCoordinator.

  public void reloadSidebarLibraries() {
    if (sidebarPanel != null) sidebarPanel.reloadLibrary();
    if (floatingSidebar != null) floatingSidebar.reloadLibrary();
  }

  @Override
  public void dispose() {
    if (visualizerRepaintTimer != null) {
      visualizerRepaintTimer.stop();
    }
    if (picTransportFlushTimer != null) {
      picTransportFlushTimer.stop();
    }
    if (statusTextPlaybackTimer != null) {
      statusTextPlaybackTimer.stop();
    }
    if (pureEngine != null) {
      pureEngine.stop();
    }
    super.dispose();
  }

  public void updateGlobalGridMode(org.deluge.project.PreferencesManager.GridMode nextMode) {
    org.deluge.project.PreferencesManager.setGridMode(nextMode);
    if (clipPanel != null) {
      clipPanel.setGridMode(nextMode);
      clipPanel.refresh();
    }
    if (songPanel != null) {
      songPanel.setGridMode(nextMode);
      songPanel.refresh();
    }
    if (arrGridPanel != null) {
      arrGridPanel.setGridMode(nextMode);
      arrGridPanel.refresh();
    }
    recalcWrapperSize();
    updateViewMenuChecks();
    captureAutoScreenshot("zoom_" + nextMode.name());
  }

  private void updateViewMenuChecks() {
    org.deluge.project.PreferencesManager.GridMode currentMode =
        org.deluge.project.PreferencesManager.getGridMode();
    if (grid8x16Item != null) {
      grid8x16Item.setSelected(
          currentMode == org.deluge.project.PreferencesManager.GridMode.GRID_8x16);
    }
    if (grid16x16Item != null) {
      grid16x16Item.setSelected(
          currentMode == org.deluge.project.PreferencesManager.GridMode.GRID_16x16);
    }
    if (grid24x16Item != null) {
      grid24x16Item.setSelected(
          currentMode == org.deluge.project.PreferencesManager.GridMode.GRID_24x16);
    }
    if (grid16x24Item != null) {
      grid16x24Item.setSelected(
          currentMode == org.deluge.project.PreferencesManager.GridMode.GRID_16x24);
    }
  }

  private void zoomGrid(boolean zoomIn) {
    org.deluge.project.PreferencesManager.GridMode[] modes = {
      org.deluge.project.PreferencesManager.GridMode.GRID_8x16,
      org.deluge.project.PreferencesManager.GridMode.GRID_16x16,
      org.deluge.project.PreferencesManager.GridMode.GRID_24x16
    };
    org.deluge.project.PreferencesManager.GridMode currentMode =
        org.deluge.project.PreferencesManager.getGridMode();
    int currentIdx = -1;
    for (int i = 0; i < modes.length; i++) {
      if (modes[i] == currentMode) {
        currentIdx = i;
        break;
      }
    }
    if (currentIdx == -1) {
      currentIdx = 1;
    }
    int nextIdx = currentIdx + (zoomIn ? -1 : 1);
    if (nextIdx >= 0 && nextIdx < modes.length) {
      updateGlobalGridMode(modes[nextIdx]);
    }
  }
}
