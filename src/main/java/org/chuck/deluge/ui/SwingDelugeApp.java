package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/** Alternative lightweight UI running purely on Java Swing (no native libs). */
public class SwingDelugeApp extends JFrame {
  private final ChuckVM vm;
  private final BridgeContract bridge;

  private SwingGridPanel clipPanel;
  private SwingVisualizerPanel visualizerPanel;
  private SwingGridPanel songPanel;
  private SwingGridPanel arrGridPanel;

  private JSlider topMasterVolSlider;
  private JSlider bottomMasterVolSlider;

  private JPanel centerCardPanel;
  private JPanel centeredWrapper;
  private CardLayout cardLayout;

  private org.chuck.deluge.model.ProjectModel currentProject =
      org.chuck.deluge.model.ProjectModel.createDefaultProject();
  private java.io.File currentProjectFile = null;

  // Engine voice mapping: for each model track, the start engine row and voice count.
  // Kit tracks occupy getSounds().size() rows; Synth tracks occupy 1 row.
  private int[] trackEngineStart;
  private int[] trackVoiceCount;

  private final org.chuck.deluge.midi.MidiService midiService;
  private final java.util.ArrayDeque<Long> tapTimes = new java.util.ArrayDeque<>();

  /** Recompute trackEngineStart and trackVoiceCount from the current project model. */
  private void computeEngineMapping() {
    java.util.List<org.chuck.deluge.model.TrackModel> tracks = currentProject.getTracks();
    int n = tracks.size();
    trackEngineStart = new int[n];
    trackVoiceCount = new int[n];
    int nextRow = 0;
    for (int t = 0; t < n && nextRow < BridgeContract.TRACKS; t++) {
      trackEngineStart[t] = nextRow;
      boolean isKit = tracks.get(t) instanceof org.chuck.deluge.model.KitTrackModel;
      int voices = isKit
        ? ((org.chuck.deluge.model.KitTrackModel) tracks.get(t)).getSounds().size()
        : 8;
      int capped = Math.min(voices, BridgeContract.TRACKS - nextRow);
      trackVoiceCount[t] = capped;
      nextRow += capped;
    }
  }

  /** Push the current project model into engine globals (G_TRACK_TYPE, samples, kit params, patterns). */
  private void pushModelToBridge() {
    computeEngineMapping();
    java.util.List<org.chuck.deluge.model.TrackModel> tracks = currentProject.getTracks();

    // Clear all engine rows first
    bridge.clearPattern();
    for (int i = 0; i < BridgeContract.TRACKS; i++) {
      bridge.setMute(i, false);
      bridge.setTrackLength(i, 16);
    }

    // Initialize both local bridge and VM global track types to -1 (unused)
    for (int i = 0; i < BridgeContract.TRACKS; i++) bridge.setTrackType(i, -1);

    org.chuck.core.ChuckArray trackTypeArr =
        (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_TYPE);
    if (trackTypeArr != null) {
      // Initialize all to -1 (unused)
      for (int i = 0; i < BridgeContract.TRACKS; i++) trackTypeArr.setInt(i, -1L);
    }

    for (int t = 0; t < tracks.size(); t++) {
      org.chuck.deluge.model.TrackModel track = tracks.get(t);
      int startRow = trackEngineStart[t];
      int voiceCount = trackVoiceCount[t];

      if (track instanceof org.chuck.deluge.model.KitTrackModel kit) {
        // Mark engine rows as type-0 (kit)
        for (int v = 0; v < voiceCount; v++) {
          bridge.setTrackType(startRow + v, 0);
          if (trackTypeArr != null) trackTypeArr.setInt(startRow + v, 0L);
        }

        // Push each kit sound: sample path, pitch, mute group, reverse, ADSR
        java.util.List<org.chuck.deluge.model.KitTrackModel.KitSound> sounds = kit.getSounds();
        for (int v = 0; v < voiceCount; v++) {
          int engineRow = startRow + v;
          String path = v < sounds.size() ? sounds.get(v).getSamplePath() : "";
          vm.setGlobalString("g_sample_" + engineRow, path);

          if (v < sounds.size()) {
            org.chuck.deluge.model.KitTrackModel.KitSound snd = sounds.get(v);
            try {
              ((org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_PITCH))
                  .setFloat(engineRow, snd.getPitchSemitones());
              ((org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_MUTE_GROUP))
                  .setInt(engineRow, (long) snd.getMuteGroup());
              ((org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_REVERSE))
                  .setInt(engineRow, snd.isReverse() ? 1L : 0L);
              org.chuck.deluge.model.EnvelopeModel adsr = snd.getAdsr();
              if (adsr != null) {
                ((org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_ATTACK))
                    .setFloat(engineRow, adsr.attack());
                ((org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_DECAY))
                    .setFloat(engineRow, adsr.decay());
                ((org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_SUSTAIN))
                    .setFloat(engineRow, adsr.sustain());
                ((org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_RELEASE))
                    .setFloat(engineRow, adsr.release());
              }
            } catch (Exception ex) {
              System.err.println("[pushModel] kit param error at row " + engineRow + ": " + ex.getMessage());
            }
          }
        }

        // Push clip pattern data for the active clip
        int activeClipIdx = kit.getActiveClipIndex();
        if (activeClipIdx >= 0 && activeClipIdx < kit.getClips().size()) {
          org.chuck.deluge.model.ClipModel clip = kit.getClips().get(activeClipIdx);
          for (int r = 0; r < clip.getRowCount(); r++) {
            for (int s = 0; s < 16; s++) {
              org.chuck.deluge.model.StepData step = clip.getStep(r, s);
              if (step != null && step.active() && r < voiceCount) {
                bridge.setStep(startRow + r, s, true);
                bridge.setVelocity(startRow + r, s, step.velocity());
              }
            }
          }
        }
      } else if (track instanceof org.chuck.deluge.model.SynthTrackModel synth) {
        // Mark all 8 voice rows as type-1 (synth)
        for (int v = 0; v < voiceCount; v++) {
          bridge.setTrackType(startRow + v, 1);
          if (trackTypeArr != null) trackTypeArr.setInt(startRow + v, 1L);
        }

        // Push clip data — each visual row writes to its own engine row
        int activeClipIdx = synth.getActiveClipIndex();
        if (activeClipIdx >= 0 && activeClipIdx < synth.getClips().size()) {
          org.chuck.deluge.model.ClipModel clip = synth.getClips().get(activeClipIdx);
          for (int r = 0; r < clip.getRowCount() && r < voiceCount; r++) {
            for (int s = 0; s < 16; s++) {
              org.chuck.deluge.model.StepData step = clip.getStep(r, s);
              if (step != null && step.active()) {
                bridge.setStep(startRow + r, s, true);
                bridge.setVelocity(startRow + r, s, step.velocity());
              }
            }
          }
        }

        // Push oscType (0=SINE, 1=SAW, 2=SQUARE, 3=TRIANGLE, 4=NOISE)
        org.chuck.core.ChuckArray oscTypeArr =
            (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_OSC_TYPE);
        if (oscTypeArr != null) {
          int typeIdx = 1; // default Saw
          String ot = synth.getOsc1Type();
          if ("SINE".equals(ot)) typeIdx = 0;
          else if ("SQUARE".equals(ot)) typeIdx = 2;
          else if ("TRIANGLE".equals(ot)) typeIdx = 3;
          oscTypeArr.setInt(startRow, typeIdx);
        }

        // Push filter params
        bridge.setFilterFreq(startRow, synth.getLpfFreq() / 20000.0f);
        bridge.setFilterRes(startRow, synth.getLpfRes() / 100.0f);
        bridge.setFilterMode(startRow, synth.getFilterMode().ordinal());
        bridge.setFilterMorph(startRow, synth.getLpfMorph());

        // Push ADSR envelopes (4 envs)
        for (int e = 0; e < 4; e++) {
          org.chuck.deluge.model.EnvelopeModel adsr = synth.getEnv(e);
          if (adsr != null) {
            bridge.setEnv(e, adsr.attack(), adsr.decay(), adsr.sustain(), adsr.release());
          }
        }

        // Push LFO params (4 LFOs)
        org.chuck.core.ChuckArray lfoRateArr =
            (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_RATE);
        org.chuck.core.ChuckArray lfoTypeArr =
            (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_TYPE);
        org.chuck.core.ChuckArray lfoDepthArr =
            (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_DEPTH);
        for (int l = 0; l < 4; l++) {
          org.chuck.deluge.model.LfoModel lfo = synth.getLfo(l);
          if (lfo != null) {
            if (lfoRateArr != null) lfoRateArr.setFloat(l, lfo.rateHz());
            if (lfoTypeArr != null) lfoTypeArr.setInt(l, (long) lfo.waveform().ordinal());
            if (lfoDepthArr != null) lfoDepthArr.setFloat(l, lfo.depth());
          }
        }

        // Push arp params
        org.chuck.deluge.model.ArpModel arp = synth.getArp();
        if (arp != null) {
          bridge.setArpOn(startRow, arp.active());
          bridge.setArpRate(startRow, arp.rate());
          bridge.setArpOctave(startRow, arp.octaves());
        }
      }

      // Track length
      bridge.setTrackLength(startRow, track.getClips().isEmpty() ? 16 : track.getClips().get(0).getStepCount());
    }

    // Only unblock the engine when there are actual tracks to process.
    // An empty-project broadcast leaves all track types = -1, causing kit_shred to
    // compute voiceCount = 1 and build undersized arrays that can't be resized later.
    if (!tracks.isEmpty()) {
      vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    }
  }

  public SwingDelugeApp(
      ChuckVM vm, BridgeContract bridge, org.chuck.deluge.midi.MidiService midiService) {
    this.vm = vm;
    this.bridge = bridge;
    this.midiService = midiService;

    // Inflate Font Sizes globally (2x bigger)
    java.util.Enumeration<Object> keys = UIManager.getDefaults().keys();
    while (keys.hasMoreElements()) {
      Object key = keys.nextElement();
      Object value = UIManager.get(key);
      if (value instanceof javax.swing.plaf.FontUIResource) {
        javax.swing.plaf.FontUIResource orig = (javax.swing.plaf.FontUIResource) value;
        Font font = new Font(orig.getFontName(), orig.getStyle(), 20); // Increased size
        UIManager.put(key, new javax.swing.plaf.FontUIResource(font));
      }
    }

    setTitle("DELUGE WORKSTATION [SWING EDITION]");
    setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

    setFocusable(true);
    addKeyListener(
        new java.awt.event.KeyAdapter() {
          @Override
          public void keyPressed(java.awt.event.KeyEvent e) {
            int note = -1;
            switch (e.getKeyCode()) {
              case java.awt.event.KeyEvent.VK_Z -> note = 60; // C4
              case java.awt.event.KeyEvent.VK_S -> note = 61; // C#4
              case java.awt.event.KeyEvent.VK_X -> note = 62; // D4
              case java.awt.event.KeyEvent.VK_D -> note = 63; // D#4
              case java.awt.event.KeyEvent.VK_C -> note = 64; // E4
              case java.awt.event.KeyEvent.VK_V -> note = 65; // F4
              case java.awt.event.KeyEvent.VK_G -> note = 66; // F#4
              case java.awt.event.KeyEvent.VK_B -> note = 67; // G4
              case java.awt.event.KeyEvent.VK_H -> note = 68; // G#4
              case java.awt.event.KeyEvent.VK_N -> note = 69; // A4
              case java.awt.event.KeyEvent.VK_J -> note = 70; // A#4
              case java.awt.event.KeyEvent.VK_M -> note = 71; // B4
            }
            if (note != -1) {
              System.out.println("QWERTY Piano Trigger: Note " + note);
              if (clipPanel != null) {
                clipPanel.flashIsomorphicNote(note);
                int trackId = clipPanel.getFocusTrack();

                boolean isSynth =
                    clipPanel.getProjectModel() != null
                        && !clipPanel.getProjectModel().getTracks().isEmpty()
                        && clipPanel.getProjectModel().getTracks().get(0)
                            instanceof org.chuck.deluge.model.SynthTrackModel;

                if (isSynth) {
                  try {
                    org.chuck.core.ChuckEvent noteEv =
                        (org.chuck.core.ChuckEvent) vm.getGlobalObject("g_ck_noteOn");
                    if (noteEv != null) {
                      org.chuck.core.ChuckArray pitchArr =
                          (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_PITCH);
                      pitchArr.setInt(0, (long) (note - 60));
                      noteEv.broadcast();
                    }
                  } catch (Exception ex) {
                  }
                } else {
                  String sp = (String) vm.getGlobalObject("g_sample_" + trackId);
                  if (sp != null && !sp.isEmpty()) {
                    new Thread(
                            () -> {
                              try {
                                java.io.File file = new java.io.File(sp);
                                if (file.exists()) {
                                  javax.sound.sampled.AudioInputStream stream =
                                      javax.sound.sampled.AudioSystem.getAudioInputStream(file);
                                  javax.sound.sampled.Clip c =
                                      javax.sound.sampled.AudioSystem.getClip();
                                  c.open(stream);
                                  c.start();
                                }
                              } catch (Exception ex) {
                              }
                            })
                        .start();
                  }
                }
              }
            }
          }
        });

    int w =
        Integer.parseInt(org.chuck.deluge.project.PreferencesManager.get("window.width", "2800"));
    int h =
        Integer.parseInt(org.chuck.deluge.project.PreferencesManager.get("window.height", "1600"));
    setSize(w, h);

    addWindowListener(
        new java.awt.event.WindowAdapter() {
          @Override
          public void windowClosing(java.awt.event.WindowEvent e) {
            org.chuck.deluge.project.PreferencesManager.set(
                "window.width", String.valueOf(getWidth()));
            org.chuck.deluge.project.PreferencesManager.set(
                "window.height", String.valueOf(getHeight()));
          }
        });

    setLocationRelativeTo(null);
    getContentPane().setBackground(new Color(0x1a, 0x1a, 0x1a));
    setLayout(new BorderLayout(10, 10));

    setupUI();
    startPlaybackTimer();

    setFocusable(true);
    addKeyListener(
        new java.awt.event.KeyAdapter() {
          @Override
          public void keyPressed(java.awt.event.KeyEvent e) {
            int kc = e.getKeyCode();
            boolean ctrl = (e.getModifiersEx() & java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0;

            // Ctrl+[ / Ctrl+] — adjust focused track length
            if (ctrl && kc == java.awt.event.KeyEvent.VK_OPEN_BRACKET) {
              SwingGridPanel active = activeGridPanel();
              if (active != null) {
                int trk = active.getFocusTrack();
                int len = bridge.getTrackLength(trk);
                bridge.setTrackLength(trk, Math.max(1, len - 1));
                active.refresh();
              }
              return;
            }
            if (ctrl && kc == java.awt.event.KeyEvent.VK_CLOSE_BRACKET) {
              SwingGridPanel active = activeGridPanel();
              if (active != null) {
                int trk = active.getFocusTrack();
                int len = bridge.getTrackLength(trk);
                bridge.setTrackLength(trk, Math.min(64, len + 1));
                active.refresh();
              }
              return;
            }

            // T — tap tempo
            if (!ctrl && kc == java.awt.event.KeyEvent.VK_T) {
              long now = System.currentTimeMillis();
              tapTimes.addLast(now);
              while (tapTimes.size() > 8) tapTimes.removeFirst();
              if (tapTimes.size() >= 2) {
                long[] arr = tapTimes.stream().mapToLong(Long::longValue).toArray();
                long totalGap = arr[arr.length - 1] - arr[0];
                double avgGap = totalGap / (double)(arr.length - 1);
                double bpm = 60000.0 / avgGap;
                bpm = Math.max(20, Math.min(300, bpm));
                bridge.setBpm(bpm);
              }
              return;
            }

            org.chuck.hid.HidMsg msg = new org.chuck.hid.HidMsg();
            msg.deviceType = "keyboard";
            msg.type = org.chuck.hid.HidMsg.BUTTON_DOWN;
            msg.which = kc;
            msg.key = kc;
            char c = e.getKeyChar();
            if (c != java.awt.event.KeyEvent.CHAR_UNDEFINED) {
              msg.ascii = c;
            }
            vm.dispatchHidMsg(msg);
          }

          @Override
          public void keyReleased(java.awt.event.KeyEvent e) {
            org.chuck.hid.HidMsg msg = new org.chuck.hid.HidMsg();
            msg.deviceType = "keyboard";
            msg.type = org.chuck.hid.HidMsg.BUTTON_UP;
            msg.which = e.getKeyCode();
            msg.key = e.getKeyCode();
            vm.dispatchHidMsg(msg);
          }
        });
  }

  private void loadProject(org.chuck.deluge.model.ProjectModel model) {
    currentProject = model;
    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);

    // Register listener so structural changes auto-sync
    model.addProjectListener(new org.chuck.deluge.model.ProjectModel.ProjectListener() {
      @Override public void onTrackListChanged() {
        pushModelToBridge();
        propagateCurrentModel();
        refreshGrids();
      }
      @Override public void onBpmChanged(float bpm) {
        vm.setGlobalFloat(BridgeContract.G_BPM, bpm);
      }
    });

    pushModelToBridge();
    propagateCurrentModel();

    if (clipPanel != null) clipPanel.setProjectModel(model);
    if (songPanel != null) songPanel.setProjectModel(model);
    if (arrGridPanel != null) arrGridPanel.setProjectModel(model);

    setTitle(
        "DELUGE WORKSTATION — "
            + (currentProjectFile != null ? currentProjectFile.getName() : "Untitled"));
  }

  private void refreshGrids() {
    if (clipPanel != null) clipPanel.refresh();
    if (songPanel != null) songPanel.refresh();
    if (arrGridPanel != null) arrGridPanel.refresh();
  }

  private void saveProject(boolean forceChooser) {
    java.io.File target = currentProjectFile;
    if (target == null || forceChooser) {
      JFileChooser chooser =
          new JFileChooser(org.chuck.deluge.project.PreferencesManager.getSongsDir());
      chooser.setFileFilter(
          new javax.swing.filechooser.FileNameExtensionFilter("Song XML", "xml", "XML"));
      if (currentProjectFile != null) chooser.setSelectedFile(currentProjectFile);
      if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
      target = chooser.getSelectedFile();
      if (!target.getName().toLowerCase().endsWith(".xml")) {
        target = new java.io.File(target.getAbsolutePath() + ".xml");
      }
    }
    try {
      org.chuck.deluge.project.ProjectSerializer.save(currentProject, target);
      currentProjectFile = target;
      setTitle("DELUGE WORKSTATION — " + target.getName());
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(
          this, "Save failed:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
    }
  }

  private SwingGridPanel activeGridPanel() {
    if (cardLayout == null || centerCardPanel == null) return clipPanel;
    // Return whichever panel is currently visible
    for (java.awt.Component comp : centerCardPanel.getComponents()) {
      if (comp.isVisible() && comp instanceof SwingGridPanel sgp) return sgp;
      if (comp.isVisible() && comp instanceof JScrollPane sp
          && sp.getViewport().getView() instanceof SwingGridPanel sgp) return sgp;
    }
    return clipPanel;
  }

  private void propagateCurrentModel() {
    if (clipPanel != null) clipPanel.setProjectModel(currentProject);
    if (songPanel != null) songPanel.setProjectModel(currentProject);
    if (arrGridPanel != null) arrGridPanel.setProjectModel(currentProject);
  }

  private void setupUI() {
    getContentPane().removeAll();
    setLayout(new GridBagLayout());
    GridBagConstraints gbc = new GridBagConstraints();
    gbc.fill = GridBagConstraints.BOTH;

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
            loadProject(org.chuck.deluge.model.ProjectModel.createDefaultProject());
          }
        });

    JMenuItem openItem = new JMenuItem("Open Project...");
    openItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_O, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    openItem.addActionListener(
        e -> {
          JFileChooser chooser =
              new JFileChooser(org.chuck.deluge.project.PreferencesManager.getSongsDir());
          chooser.setFileFilter(
              new javax.swing.filechooser.FileNameExtensionFilter("Song XML", "xml", "XML"));
          if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
              java.io.File file = chooser.getSelectedFile();
              org.chuck.deluge.model.ProjectModel model =
                  org.chuck.deluge.xml.DelugeXmlParser.parseSong(
                      new java.io.FileInputStream(file), file.getName());
              currentProjectFile = file;
              loadProject(model);
            } catch (Exception ex) {
              JOptionPane.showMessageDialog(
                  this,
                  "Failed to open project:\n" + ex.getMessage(),
                  "Error",
                  JOptionPane.ERROR_MESSAGE);
            }
          }
        });

    JMenuItem saveItem = new JMenuItem("Save Project");
    saveItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_S, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    saveItem.addActionListener(e -> saveProject(false));

    JMenuItem saveAsItem = new JMenuItem("Save Project As...");
    saveAsItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_S,
            java.awt.event.InputEvent.CTRL_DOWN_MASK | java.awt.event.InputEvent.SHIFT_DOWN_MASK));
    saveAsItem.addActionListener(e -> saveProject(true));

    JMenuItem exitItem = new JMenuItem("Exit");
    exitItem.addActionListener(e -> System.exit(0));

    fileMenu.add(newItem);
    fileMenu.add(openItem);
    fileMenu.addSeparator();
    fileMenu.add(saveItem);
    fileMenu.add(saveAsItem);
    fileMenu.addSeparator();
    fileMenu.add(exitItem);

    JMenu settingsMenu = new JMenu("Settings");
    JMenuItem sampleItem = new JMenuItem("Set Samples Directory...");
    sampleItem.addActionListener(
        e -> {
          JFileChooser chooser = new JFileChooser();
          chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
          if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            org.chuck.deluge.project.PreferencesManager.setSamplesDir(
                chooser.getSelectedFile().getAbsolutePath());
          }
        });
    JMenuItem prefItem = new JMenuItem("Preferences...");
    prefItem.addActionListener(
        e -> {
          PreferencesDialog dialog = new PreferencesDialog(
              SwingDelugeApp.this, () -> {
                org.chuck.deluge.project.PreferencesManager.GridMode mode =
                    org.chuck.deluge.project.PreferencesManager.getGridMode();
                if (clipPanel != null) clipPanel.setGridMode(mode);
                if (songPanel != null) songPanel.setGridMode(mode);
                if (arrGridPanel != null) arrGridPanel.setGridMode(mode);
                recalcWrapperSize();
              });
          if (midiService != null) dialog.setMappings(midiService.getMappings());
          dialog.setVisible(true);
        });

    settingsMenu.add(prefItem);

    menuBar.add(fileMenu);
    menuBar.add(settingsMenu);
    setJMenuBar(menuBar);

    final JDialog leftFloat = new JDialog(this, "SD Explorer", false);
    leftFloat.setSize(300, 700);
    leftFloat.setLocation(50, 150);

    final JDialog rightFloat = new JDialog(this, "Acoustics Monitor", false);
    rightFloat.setSize(280, 700);
    rightFloat.setLocation(1600, 150);

    // 1. Top Area (Buttons, Modes, Transport, Sliders)

    boolean isHdOpt =
        Boolean.parseBoolean(
            org.chuck.deluge.project.PreferencesManager.get("hd.optimization", "false"));
    JPanel topBar = new JPanel();
    if (isHdOpt) {
      topBar.setLayout(new BoxLayout(topBar, BoxLayout.Y_AXIS));
    } else {
      topBar.setLayout(new FlowLayout(FlowLayout.LEFT, 12, 8));
    }
    topBar.setBackground(new Color(0x25, 0x25, 0x25));

    JPanel topRow1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
    topRow1.setBackground(new Color(0x25, 0x25, 0x25));
    JPanel topRow2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
    topRow2.setBackground(new Color(0x25, 0x25, 0x25));

    // View Toggle Buttons
    JToggleButton clipBtn = new JToggleButton("CLIP", true);
    JToggleButton songBtn = new JToggleButton("SONG");
    JToggleButton arrBtn = new JToggleButton("ARR");
    ButtonGroup modeGroup = new ButtonGroup();
    modeGroup.add(clipBtn);
    modeGroup.add(songBtn);
    modeGroup.add(arrBtn);

    cardLayout = new CardLayout();
    centerCardPanel = new JPanel(cardLayout);

    clipPanel = new SwingGridPanel(vm, bridge);
    clipPanel.setViewMode(SwingGridPanel.GridViewMode.CLIP);
    clipPanel.setProjectModel(currentProject);
    clipPanel.setOnProjectChanged(this::propagateCurrentModel);
    centerCardPanel.add(clipPanel, "CLIP");

    songPanel = new SwingGridPanel(vm, bridge);
    songPanel.setViewMode(SwingGridPanel.GridViewMode.SONG);
    songPanel.setProjectModel(currentProject);
    songPanel.setOnProjectChanged(this::propagateCurrentModel);
    centerCardPanel.add(songPanel, "SONG");

    arrGridPanel = new SwingGridPanel(vm, bridge);
    arrGridPanel.setViewMode(SwingGridPanel.GridViewMode.ARRANGEMENT);
    arrGridPanel.setProjectModel(currentProject);
    arrGridPanel.setOnProjectChanged(this::propagateCurrentModel);
    centerCardPanel.add(arrGridPanel, "ARR");

    clipBtn.addActionListener(
        e -> {
          cardLayout.show(centerCardPanel, "CLIP");
        });
    songBtn.addActionListener(
        e -> {
          cardLayout.show(centerCardPanel, "SONG");
        });
    arrBtn.addActionListener(
        e -> {
          cardLayout.show(centerCardPanel, "ARR");
        });

    JButton addKitBtn = new JButton("+ KIT");
    addKitBtn.setBackground(new Color(0x33, 0x44, 0x55));
    addKitBtn.setForeground(Color.WHITE);
    addKitBtn.setToolTipText("Add a new Kit (drum) track to the song");
    addKitBtn.addActionListener(
        e -> {
          String name =
              JOptionPane.showInputDialog(
                  this, "Kit track name:", "KIT " + (currentProject.getTracks().size() + 1));
          if (name != null && !name.isBlank()) {
            org.chuck.deluge.model.KitTrackModel kit =
                new org.chuck.deluge.model.KitTrackModel(name);
            kit.addClip(new org.chuck.deluge.model.ClipModel("CLIP 1", 8, 16));
            currentProject.addTrack(kit); // triggers ProjectListener → pushModelToBridge() via listener
            propagateCurrentModel();
          }
        });

    JButton addSynthBtn = new JButton("+ SYNTH");
    addSynthBtn.setBackground(new Color(0x44, 0x33, 0x55));
    addSynthBtn.setForeground(Color.WHITE);
    addSynthBtn.setToolTipText("Add a new Synth track to the song");
    addSynthBtn.addActionListener(
        e -> {
          String name =
              JOptionPane.showInputDialog(
                  this, "Synth track name:", "SYNTH " + (currentProject.getTracks().size() + 1));
          if (name != null && !name.isBlank()) {
            org.chuck.deluge.model.SynthTrackModel synth =
                new org.chuck.deluge.model.SynthTrackModel(name);
            synth.addClip(new org.chuck.deluge.model.ClipModel("CLIP 1", 8, 16));
            currentProject.addTrack(synth); // triggers ProjectListener → pushModelToBridge()
            propagateCurrentModel();
          }
        });

    JButton btnExplorer = new JButton("EXPLORER");
    btnExplorer.addActionListener(e -> leftFloat.setVisible(!leftFloat.isVisible()));

    JButton btnMonitor = new JButton("MONITOR");
    btnMonitor.addActionListener(e -> rightFloat.setVisible(!rightFloat.isVisible()));

    if (isHdOpt) {
      topRow1.add(clipBtn);
      topRow1.add(songBtn);
      topRow1.add(arrBtn);
      topRow1.add(new JSeparator(JSeparator.VERTICAL));
      topRow1.add(addKitBtn);
      topRow1.add(addSynthBtn);
      topRow1.add(new JSeparator(JSeparator.VERTICAL));
      topRow1.add(btnExplorer);
      topRow1.add(btnMonitor);
    } else {
      topBar.add(clipBtn);
      topBar.add(songBtn);
      topBar.add(arrBtn);
      topBar.add(new JSeparator(JSeparator.VERTICAL));
      topBar.add(addKitBtn);
      topBar.add(addSynthBtn);
      topBar.add(new JSeparator(JSeparator.VERTICAL));
      topBar.add(btnExplorer);
      topBar.add(btnMonitor);
      topBar.add(new JSeparator(JSeparator.VERTICAL));
    }

    // Transport
    JButton playBtn = new JButton("▶ PLAY");
    playBtn.setBackground(new Color(0x33, 0x66, 0x33));
    playBtn.setForeground(Color.WHITE);
    playBtn.addActionListener(
        e ->
            vm.setGlobalInt(
                BridgeContract.G_PLAY, vm.getGlobalInt(BridgeContract.G_PLAY) == 1L ? 0L : 1L));

    JButton stopBtn = new JButton("■ STOP");
    stopBtn.setBackground(new Color(0x66, 0x33, 0x33));
    stopBtn.setForeground(Color.WHITE);
    stopBtn.addActionListener(e -> vm.setGlobalInt(BridgeContract.G_PLAY, 0L));

    JToggleButton recBtn = new JToggleButton("● REC");
    recBtn.setForeground(Color.RED);
    recBtn.addActionListener(
        e -> {
          if (midiService != null) midiService.setRecording(recBtn.isSelected());
        });

    if (isHdOpt) {
      topRow1.add(playBtn);
      topRow1.add(stopBtn);
      topRow1.add(recBtn);
    } else {
      topBar.add(playBtn);
      topBar.add(stopBtn);
      topBar.add(recBtn);
      topBar.add(new JSeparator(JSeparator.VERTICAL));
    }

    // Sliders
    JLabel tempoLabel = new JLabel("BPM:");
    tempoLabel.setForeground(Color.WHITE);
    JSlider bpmSlider = new JSlider(60, 200, 120);
    bpmSlider.addChangeListener(e -> vm.setGlobalFloat(BridgeContract.G_BPM, bpmSlider.getValue()));

    JLabel swingLabel = new JLabel("SWING:");
    swingLabel.setForeground(Color.WHITE);
    JSlider swingSlider = new JSlider(0, 100, 50);
    swingSlider.addChangeListener(
        e -> vm.setGlobalFloat(BridgeContract.G_SWING, swingSlider.getValue() / 100.0));

    JLabel volLabel = new JLabel("MASTER:");
    volLabel.setForeground(Color.WHITE);
    topMasterVolSlider = new JSlider(0, 100, 70);
    topMasterVolSlider.addChangeListener(
        e -> {
          double v = topMasterVolSlider.getValue() / 100.0;
          vm.setGlobalFloat(BridgeContract.G_MASTER_VOL, v);
          if (bottomMasterVolSlider != null
              && bottomMasterVolSlider.getValue() != topMasterVolSlider.getValue()) {
            bottomMasterVolSlider.setValue(topMasterVolSlider.getValue());
          }
        });

    if (isHdOpt) {
      topRow2.add(tempoLabel);
      topRow2.add(bpmSlider);
      topRow2.add(swingLabel);
      topRow2.add(swingSlider);
      topRow2.add(volLabel);
      topRow2.add(topMasterVolSlider);

      topBar.add(topRow1);
      topBar.add(topRow2);
    } else {
      topBar.add(tempoLabel);
      topBar.add(bpmSlider);
      topBar.add(swingLabel);
      topBar.add(swingSlider);
      topBar.add(volLabel);
      topBar.add(topMasterVolSlider);
    }

    gbc.gridx = 0;
    gbc.gridy = 0;
    gbc.gridwidth = 3;
    gbc.weightx = 1.0;
    gbc.weighty = 0.0;
    add(topBar, gbc);

    centeredWrapper = new JPanel(new GridBagLayout());
    centeredWrapper.setBackground(new Color(0x1a, 0x1a, 0x1a));

    GridBagConstraints wrapperGbc = new GridBagConstraints();
    wrapperGbc.fill = GridBagConstraints.BOTH;
    wrapperGbc.anchor = GridBagConstraints.NORTHWEST;
    wrapperGbc.gridx = 0;
    wrapperGbc.gridy = 0;

    centeredWrapper.add(centerCardPanel, wrapperGbc);

    String res = org.chuck.deluge.project.PreferencesManager.get("screen.resolution", "QHD");
    int reqW = "FHD".equals(res) ? 1800 : ("4K".equals(res) ? 3600 : 2600);
    int reqH = "FHD".equals(res) ? 1000 : ("4K".equals(res) ? 2200 : 1600);
    centeredWrapper.setPreferredSize(new Dimension(reqW, reqH));

    JScrollPane centerScroll =
        new JScrollPane(
            centeredWrapper,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS);

    centerScroll.setBorder(BorderFactory.createEmptyBorder());

    gbc.fill = GridBagConstraints.BOTH;
    gbc.gridx = 0;
    gbc.gridy = 1;
    gbc.gridwidth = 3;
    gbc.weightx = 1.0;
    gbc.weighty = 1.0;
    add(centerScroll, gbc);

    javax.swing.SwingUtilities.invokeLater(() -> centerScroll.getVerticalScrollBar().setValue(0));

    // 2. Left Area (SD Card / Editors)
    SwingProjectSidebarPanel sidebarPanel = new SwingProjectSidebarPanel(vm, bridge, midiService);
    SwingProjectSidebarPanel floatingSidebar =
        new SwingProjectSidebarPanel(vm, bridge, midiService);
    sidebarPanel.setOnSongLoaded(
        model -> {
          // Load shared project state (clears pattern, updates all panels, fires engine reload)
          loadProject(model);

          // Switch view depending on track count
          if (model.getTracks().size() == 1) {
            cardLayout.show(centerCardPanel, "CLIP");
            if (clipBtn != null) clipBtn.setSelected(true);
            boolean firstIsSynth =
                !model.getTracks().isEmpty()
                    && model.getTracks().get(0) instanceof org.chuck.deluge.model.SynthTrackModel;
            clipPanel.setBaseTrackId(
                trackEngineStart != null && trackEngineStart.length > 0
                    ? trackEngineStart[0]
                    : (firstIsSynth ? 4 : 0));
          } else {
            cardLayout.show(centerCardPanel, "SONG");
          }

          // Push model data to engine — use the centralized mapping
          // so Kit sounds get written to the correct engine rows
          if (trackEngineStart != null) {
            pushModelToBridge();
          }
        });

    floatingSidebar.setOnSongLoaded(sidebarPanel.getOnSongLoaded());

    songPanel.setOnEditRequest(
        (trackId, clipId) -> {
          sidebarPanel.updateFocusTrack(trackId);

          if (clipPanel != null && trackId < trackEngineStart.length) {
            int engineBase = trackEngineStart[trackId];
            int voiceCount = trackVoiceCount[trackId];

            clipPanel.setActiveClipId(clipId);
            clipPanel.setBaseTrackId(engineBase);
            clipPanel.setEditedModelTrack(trackId);

            java.util.List<org.chuck.deluge.model.TrackModel> allTrks =
                clipPanel.getProjectModel() != null
                    ? clipPanel.getProjectModel().getTracks()
                    : java.util.List.of();

            boolean editIsSynth =
                trackId < allTrks.size()
                    && allTrks.get(trackId) instanceof org.chuck.deluge.model.SynthTrackModel;

            // Clear engine rows for this track
            for (int v = 0; v < voiceCount; v++) {
              for (int s = 0; s < 16; s++) bridge.setStep(engineBase + v, s, false);
            }

            if (trackId < allTrks.size()) {
              org.chuck.deluge.model.TrackModel tModel = allTrks.get(trackId);
              if (clipId < tModel.getClips().size()) {
                org.chuck.deluge.model.ClipModel cModel = tModel.getClips().get(clipId);
                for (int r = 0; r < cModel.getRowCount(); r++) {
                  for (int s = 0; s < 16; s++) {
                    org.chuck.deluge.model.StepData sd = cModel.getStep(r, s);
                    if (sd != null && sd.active()) {
                      if (r < voiceCount) {
                        bridge.setStep(engineBase + r, s, true);
                      }
                    }
                  }
                }
              }
            }

            clipPanel.refresh();
          }

          cardLayout.show(centerCardPanel, "CLIP");
          clipBtn.setSelected(true);
        });

    visualizerPanel = new SwingVisualizerPanel(vm, bridge);

    leftFloat.add(floatingSidebar);
    rightFloat.add(visualizerPanel);

    if (isHdOpt) {
      leftFloat.setVisible(true);
      rightFloat.setVisible(true);
    } else {

      gbc.gridx = 0;
      gbc.gridy = 1;
      gbc.gridwidth = 1;
      gbc.weightx = 0.5;
      gbc.weighty = 1.0;
      add(sidebarPanel, gbc);
    }

    new Timer(33, e -> visualizerPanel.repaint()).start();

    // bottom lane purged

    // 5. Bottom Area - Rows 9 and 10 (Param Deck)
    // Obsolete bottom parameter deck removed. Integrated in 10x18 pads matrix.

    // 7. Bottom Area - Row 3 (Master FX dials bounding boxes)
    JPanel masterFxPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 5));
    masterFxPanel.setBackground(new Color(0x25, 0x25, 0x25));
    masterFxPanel.setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(Color.GRAY), "MASTER FX", 0, 0, null, Color.WHITE));

    JLabel bVolLabel = new JLabel("Master Vol:");
    bVolLabel.setForeground(Color.WHITE);
    bottomMasterVolSlider = new JSlider(0, 100, 70);
    bottomMasterVolSlider.addChangeListener(
        e -> {
          double v = bottomMasterVolSlider.getValue() / 100.0;
          vm.setGlobalFloat(BridgeContract.G_MASTER_VOL, v);
          if (topMasterVolSlider != null
              && topMasterVolSlider.getValue() != bottomMasterVolSlider.getValue()) {
            topMasterVolSlider.setValue(bottomMasterVolSlider.getValue());
          }
        });
    masterFxPanel.add(bVolLabel);
    masterFxPanel.add(bottomMasterVolSlider);

    JLabel transLabel = new JLabel("Transpose:");
    transLabel.setForeground(Color.WHITE);
    JSlider transSlider = new JSlider(-24, 24, 0);
    transSlider.setSnapToTicks(true);
    transSlider.setMajorTickSpacing(12);

    transSlider.setPaintTicks(true);
    masterFxPanel.add(transLabel);
    masterFxPanel.add(transSlider);

    JLabel scaleLabel = new JLabel("Scale:");
    scaleLabel.setForeground(Color.WHITE);
    JComboBox<String> scaleCombo =
        new JComboBox<>(new String[] {"Major", "Minor", "Pentatonic", "Chromatic"});
    masterFxPanel.add(scaleLabel);
    masterFxPanel.add(scaleCombo);

    JLabel statusCounter = new JLabel("1:1:1");

    statusCounter.setForeground(Color.GREEN);
    statusCounter.setFont(new Font("Monospaced", Font.BOLD, 24));
    masterFxPanel.add(statusCounter);

    gbc.gridx = 0;
    gbc.gridy = 4;
    gbc.gridwidth = 3;
    gbc.weightx = 1.0;
    gbc.weighty = 0.0;
    add(masterFxPanel, gbc);

    revalidate();

    // Push default project to engine and broadcast load trigger to unblock shreds
    pushModelToBridge();
  }

  /** Recompute centeredWrapper preferred height to fit grid content. */
  private void recalcWrapperSize() {
    String res = org.chuck.deluge.project.PreferencesManager.get("screen.resolution", "QHD");
    int reqW = "FHD".equals(res) ? 1800 : ("4K".equals(res) ? 3600 : 2600);
    int reqH = "FHD".equals(res) ? 1000 : ("4K".equals(res) ? 2200 : 1600);
    // Measure actual content from the grid panel after refresh
    if (clipPanel != null) {
      int contentH = clipPanel.getPreferredSize().height;
      if (contentH > 0) {
        reqH = Math.max(reqH, contentH + 40);
      }
    }
    centeredWrapper.setPreferredSize(new Dimension(reqW, reqH));
    centeredWrapper.revalidate();
  }

  private void startPlaybackTimer() {
    Timer timer =
        new Timer(
            30,
            e -> {
              int step = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);

              int bar = step / 16 + 1;
              int beat = (step % 16) / 4 + 1;
              int subStep = (step % 4) + 1;

              String statusStr = "STOP";
              if (vm.getGlobalInt(BridgeContract.G_PLAY) == 1L) {
                statusStr = String.format("%d.%d.%d", bar, beat, subStep);
              }
              statusStr += " | SHREDS: " + vm.getActiveShredCount();

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

              if (visualizerPanel != null) {
                visualizerPanel.repaint();
              }
            });
    timer.start();
  }

  public static void main(String[] args) {
    org.chuck.core.ChuckVM vm = new org.chuck.core.ChuckVM(44100, 2);
    org.chuck.deluge.BridgeContract bridge = new org.chuck.deluge.BridgeContract();
    bridge.register(vm);

    org.chuck.audio.ChuckAudio audio = new org.chuck.audio.ChuckAudio(vm, 1024, 2, 44100);
    vm.setAudio(audio);
    audio.start();

    if (bridge.isUseJavaEngine()) {
      vm.spork(new org.chuck.deluge.engine.DelugeEngineDSL());
    } else {
      org.chuck.deluge.engine.DelugeEngine engine =
          new org.chuck.deluge.engine.DelugeEngine(vm, bridge);
      vm.spork(engine::shred);
    }

    org.chuck.deluge.midi.MidiInputRouter router =
        new org.chuck.deluge.midi.MidiInputRouter(vm, bridge);
    org.chuck.deluge.midi.MidiService midiService =
        new org.chuck.deluge.midi.MidiService(vm, bridge, router);
    midiService.start();

    java.awt.EventQueue.invokeLater(
        () -> {
          SwingDelugeApp app = new SwingDelugeApp(vm, bridge, midiService);
          app.setVisible(true);
        });
  }
}
