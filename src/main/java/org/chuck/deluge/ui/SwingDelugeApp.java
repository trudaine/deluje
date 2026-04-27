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
  private CardLayout cardLayout;

  private final org.chuck.deluge.midi.MidiService midiService;

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
            org.chuck.hid.HidMsg msg = new org.chuck.hid.HidMsg();
            msg.deviceType = "keyboard";
            msg.type = org.chuck.hid.HidMsg.BUTTON_DOWN;
            msg.which = e.getKeyCode();
            msg.key = e.getKeyCode();
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
    JMenuItem saveItem = new JMenuItem("Save Project");
    saveItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_S, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    JMenuItem exitItem = new JMenuItem("Exit");
    exitItem.addActionListener(e -> System.exit(0));
    fileMenu.add(newItem);
    fileMenu.add(saveItem);
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
          JDialog dialog = new JDialog(this, "Preferences", true);
          dialog.setSize(750, 950);

          dialog.setLocationRelativeTo(this);
          dialog.setLayout(new BorderLayout());

          JPanel mainGrid = new JPanel(new GridBagLayout());
          GridBagConstraints c = new GridBagConstraints();
          c.fill = GridBagConstraints.HORIZONTAL;
          c.insets = new Insets(15, 20, 15, 20);
          c.anchor = GridBagConstraints.WEST;

          // Top Spacing
          c.gridx = 0;
          c.gridy = 0;
          c.gridwidth = 2;
          mainGrid.add(Box.createVerticalStrut(30), c);

          // 1. Reverb Model
          c.gridwidth = 1;
          c.gridx = 0;
          c.gridy = 1;
          mainGrid.add(new JLabel("Reverb Model:"), c);

          c.gridx = 1;
          JComboBox<String> reverbCombo =
              new JComboBox<>(new String[] {"JCRev", "FreeVerb", "MVerb", "ProceduralReverb"});
          reverbCombo.setSelectedItem(
              org.chuck.deluge.project.PreferencesManager.get("reverb.model", "JCRev"));
          mainGrid.add(reverbCombo, c);

          c.gridx = 1;
          c.gridy = 2;
          JLabel revHelp = new JLabel("<html><i>Select acoustic model structure</i></html>");
          revHelp.setForeground(Color.GRAY);
          mainGrid.add(revHelp, c);

          // 2. MIDI Input
          c.gridx = 0;
          c.gridy = 3;
          mainGrid.add(new JLabel("MIDI Input:"), c);
          c.gridx = 1;
          String[] ports = org.chuck.midi.MidiIn.list();
          String[] comboPorts = new String[ports.length + 1];
          comboPorts[0] = "None";
          System.arraycopy(ports, 0, comboPorts, 1, ports.length);
          JComboBox<String> midiCombo = new JComboBox<>(comboPorts);
          midiCombo.setSelectedItem(
              org.chuck.deluge.project.PreferencesManager.get("midi.input", "None"));
          mainGrid.add(midiCombo, c);

          c.gridx = 1;
          c.gridy = 4;
          JLabel midiHelp =
              new JLabel("<html><i>Requires application reboot to re-route</i></html>");
          midiHelp.setForeground(Color.GRAY);
          mainGrid.add(midiHelp, c);

          // 3. Checkboxes
          c.gridx = 0;
          c.gridy = 5;
          mainGrid.add(new JLabel("Show Visualizers:"), c);
          c.gridx = 1;
          JCheckBox visCheck =
              new JCheckBox(
                  "",
                  Boolean.parseBoolean(
                      org.chuck.deluge.project.PreferencesManager.get("show.visualizers", "true")));
          mainGrid.add(visCheck, c);

          c.gridx = 0;
          c.gridy = 6;
          mainGrid.add(new JLabel("Debug Audio:"), c);
          c.gridx = 1;
          JCheckBox debugCheck =
              new JCheckBox(
                  "",
                  Boolean.parseBoolean(
                      org.chuck.deluge.project.PreferencesManager.get("debug.audio", "false")));
          mainGrid.add(debugCheck, c);

          c.gridx = 0;
          c.gridy = 7;
          mainGrid.add(new JLabel("MIDI Grid Mode:"), c);
          c.gridx = 1;
          JCheckBox gridModeCheck =
              new JCheckBox(
                  "",
                  Boolean.parseBoolean(
                      org.chuck.deluge.project.PreferencesManager.get("midi.grid.mode", "false")));
          mainGrid.add(gridModeCheck, c);

          c.gridx = 0;
          c.gridy = 8;
          mainGrid.add(new JLabel("Show Tooltips:"), c);
          c.gridx = 1;
          JCheckBox tooltipCheck =
              new JCheckBox(
                  "",
                  Boolean.parseBoolean(
                      org.chuck.deluge.project.PreferencesManager.get("show.tooltips", "true")));
          mainGrid.add(tooltipCheck, c);

          c.gridx = 0;
          c.gridy = 9;
          mainGrid.add(new JLabel("Screen Resolution:"), c);
          c.gridx = 1;
          JComboBox<String> screenResCombo = new JComboBox<>(new String[] {"FHD", "QHD", "4K"});
          screenResCombo.setSelectedItem(
              org.chuck.deluge.project.PreferencesManager.get("screen.resolution", "QHD"));
          mainGrid.add(screenResCombo, c);

          // 4. Active Mappings
          c.gridx = 0;
          c.gridy = 10;
          mainGrid.add(new JLabel("Active Mappings:"), c);
          c.gridx = 1;
          DefaultListModel<String> listModel = new DefaultListModel<>();
          if (midiService != null) {
            for (java.util.Map.Entry<String, Integer> entry :
                midiService.getMappings().entrySet()) {
              listModel.addElement(entry.getKey() + " -> CC " + entry.getValue());
            }
          }
          JList<String> mappingList = new JList<>(listModel);
          mainGrid.add(new JScrollPane(mappingList), c);

          // 5. Samples Directory
          c.gridx = 0;
          c.gridy = 11;
          mainGrid.add(new JLabel("Samples Directory:"), c);

          c.gridx = 1;
          String currentDir = org.chuck.deluge.project.PreferencesManager.getSamplesDir();
          JLabel dirLabel = new JLabel(currentDir != null ? currentDir : "Not Set");
          dirLabel.setForeground(Color.CYAN);
          mainGrid.add(dirLabel, c);

          c.gridy = 12;
          JButton browseBtn = new JButton("Browse...");
          browseBtn.addActionListener(
              ev -> {
                JFileChooser chooser = new JFileChooser();
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                  org.chuck.deluge.project.PreferencesManager.setSamplesDir(
                      chooser.getSelectedFile().getAbsolutePath());
                  dirLabel.setText(chooser.getSelectedFile().getAbsolutePath());
                }
              });
          mainGrid.add(browseBtn, c);

          // 6. Save Button
          JButton saveBtn = new JButton("Save");
          saveBtn.setFont(new Font("SansSerif", Font.BOLD, 28));
          saveBtn.addActionListener(
              ev -> {
                org.chuck.deluge.project.PreferencesManager.set(
                    "reverb.model", (String) reverbCombo.getSelectedItem());
                org.chuck.deluge.project.PreferencesManager.set(
                    "midi.input", (String) midiCombo.getSelectedItem());
                org.chuck.deluge.project.PreferencesManager.set(
                    "show.visualizers", String.valueOf(visCheck.isSelected()));
                org.chuck.deluge.project.PreferencesManager.set(
                    "debug.audio", String.valueOf(debugCheck.isSelected()));
                org.chuck.deluge.project.PreferencesManager.set(
                    "midi.grid.mode", String.valueOf(gridModeCheck.isSelected()));
                org.chuck.deluge.project.PreferencesManager.set(
                    "show.tooltips", String.valueOf(tooltipCheck.isSelected()));
                org.chuck.deluge.project.PreferencesManager.set(
                    "screen.resolution", (String) screenResCombo.getSelectedItem());

                dialog.dispose();
                JOptionPane.showMessageDialog(
                    SwingDelugeApp.this,
                    "Screen proportions applied! Please restart application to fully engage desktop scaling docks.");
              });

          // Apply large font to all elements
          Font bigFont = new Font("SansSerif", Font.PLAIN, 24);
          for (Component comp : mainGrid.getComponents()) {
            if (comp instanceof JLabel
                || comp instanceof JComboBox
                || comp instanceof JCheckBox
                || comp instanceof JButton) {
              comp.setFont(bigFont);
            }
          }
          saveBtn.setFont(new Font("SansSerif", Font.BOLD, 28));

          dialog.add(mainGrid, BorderLayout.CENTER);

          JPanel southPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 15));
          southPanel.setBackground(new Color(0x25, 0x25, 0x25));
          southPanel.add(saveBtn);
          dialog.add(southPanel, BorderLayout.SOUTH);

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
    centerCardPanel.add(clipPanel, "CLIP");

    songPanel = new SwingGridPanel(vm, bridge);
    songPanel.setViewMode(SwingGridPanel.GridViewMode.SONG);
    centerCardPanel.add(songPanel, "SONG");

    arrGridPanel = new SwingGridPanel(vm, bridge);

    arrGridPanel.setViewMode(SwingGridPanel.GridViewMode.ARRANGEMENT);
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

    JButton btnExplorer = new JButton("📂 EXPLORER");
    btnExplorer.addActionListener(e -> leftFloat.setVisible(!leftFloat.isVisible()));

    JButton btnMonitor = new JButton("📊 MONITOR");
    btnMonitor.addActionListener(e -> rightFloat.setVisible(!rightFloat.isVisible()));

    if (isHdOpt) {
      topRow1.add(clipBtn);
      topRow1.add(songBtn);
      topRow1.add(arrBtn);
      topRow1.add(btnExplorer);
      topRow1.add(btnMonitor);
    } else {
      topBar.add(clipBtn);
      topBar.add(songBtn);
      topBar.add(arrBtn);
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

    JButton loadBtn = new JButton("📂 LOAD XML");
    loadBtn.addActionListener(
        e -> {
          JFileChooser chooser = new JFileChooser();
          if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
              java.io.File file = chooser.getSelectedFile();
              org.chuck.deluge.model.ProjectModel model =
                  org.chuck.deluge.xml.DelugeXmlParser.parseSong(
                      new java.io.FileInputStream(file), file.getName());

              songPanel.setProjectModel(model);
              if (clipPanel != null) clipPanel.setProjectModel(model);
              if (arrGridPanel != null) arrGridPanel.setProjectModel(model);

              cardLayout.show(centerCardPanel, "SONG");
            } catch (Exception ex) {
              ex.printStackTrace();
            }
          }
        });

    JButton saveSongBtn = new JButton("💾 SAVE XML");
    saveSongBtn.addActionListener(
        e -> {
          JFileChooser chooser = new JFileChooser();
          if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
              if (clipPanel != null && clipPanel.getProjectModel() != null) {
                org.chuck.deluge.project.ProjectSerializer.save(
                    clipPanel.getProjectModel(), chooser.getSelectedFile());
                System.out.println(
                    "Swing: Saved project successfully to " + chooser.getSelectedFile().getName());
              }
            } catch (Exception ex) {
              ex.printStackTrace();
            }
          }
        });

    if (isHdOpt) {
      topRow1.add(playBtn);
      topRow1.add(stopBtn);
      topRow1.add(recBtn);
      topRow1.add(loadBtn);
      topRow1.add(saveSongBtn);
    } else {
      topBar.add(playBtn);
      topBar.add(stopBtn);
      topBar.add(recBtn);
      topBar.add(loadBtn);
      topBar.add(saveSongBtn);
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

    JPanel centeredWrapper = new JPanel(new GridBagLayout());
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
          System.out.println(
              "Swing Callback: Song model loaded! Tracks: " + model.getTracks().size());
          songPanel.setProjectModel(model);
          if (clipPanel != null) clipPanel.setProjectModel(model);
          if (arrGridPanel != null) arrGridPanel.setProjectModel(model);

          // Stop playback and clear state before loading new song
          vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
          bridge.clearPattern();
          for (int i = 0; i < 64; i++) {
            bridge.setMute(i, false);
          }

          if (model.getTracks().size() == 1) {
            cardLayout.show(centerCardPanel, "CLIP");
            if (clipBtn != null) clipBtn.setSelected(true);
            boolean firstIsSynth =
                !model.getTracks().isEmpty()
                    && model.getTracks().get(0) instanceof org.chuck.deluge.model.SynthTrackModel;
            clipPanel.setBaseTrackId(firstIsSynth ? 4 : 0);
          } else {
            cardLayout.show(centerCardPanel, "SONG");
          }

          // Load kit sample paths + per-sound params from first kit track
          for (org.chuck.deluge.model.TrackModel track : model.getTracks()) {
            if (track instanceof org.chuck.deluge.model.KitTrackModel kt) {
              java.util.List<org.chuck.deluge.model.KitTrackModel.KitSound> sounds = kt.getSounds();
              for (int i = 0; i < sounds.size(); i++) {
                org.chuck.deluge.model.KitTrackModel.KitSound snd = sounds.get(i);
                // g_sample_N is already set by the sidebar with resolved temp-file paths — do not
                // overwrite
                ((org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_PITCH))
                    .setFloat(i, snd.getPitchSemitones());
                ((org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_MUTE_GROUP))
                    .setInt(i, (long) snd.getMuteGroup());
                ((org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_REVERSE))
                    .setInt(i, snd.isReverse() ? 1L : 0L);
                org.chuck.deluge.model.EnvelopeModel adsr = snd.getAdsr();
                if (adsr != null) {
                  ((org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_ATTACK))
                      .setFloat(i, adsr.attack());
                  ((org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_DECAY))
                      .setFloat(i, adsr.decay());
                  ((org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_SUSTAIN))
                      .setFloat(i, adsr.sustain());
                  ((org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_RELEASE))
                      .setFloat(i, adsr.release());
                }
              }
              break; // first kit track owns rows 0-3
            }
          }

          // Each model track maps to one engine row in order; G_TRACK_TYPE marks kit(0)/synth(1)
          int engineRow = 0;
          org.chuck.core.ChuckArray trackTypeArr =
              (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_TYPE);
          for (org.chuck.deluge.model.TrackModel track : model.getTracks()) {
            if (engineRow >= BridgeContract.TRACKS) break;
            boolean isSynth = track instanceof org.chuck.deluge.model.SynthTrackModel;
            if (trackTypeArr != null) trackTypeArr.setInt(engineRow, isSynth ? 1L : 0L);
            for (org.chuck.deluge.model.ClipModel clip : track.getClips()) {
              for (int r = 0; r < clip.getRowCount(); r++) {
                for (int s = 0; s < 16; s++) {
                  org.chuck.deluge.model.StepData step = clip.getStep(r, s);
                  if (step != null && step.active()) {
                    if (isSynth) {
                      bridge.setStep(engineRow, s, true);
                      bridge.setPitch(engineRow, s, (24 - 1) - r);
                    } else {
                      bridge.setStep(engineRow, s, true);
                    }
                  }
                }
              }
            }
            engineRow++;
          }

          // Signal engine to reload samples
          vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
          clipPanel.repaint();
        });

    floatingSidebar.setOnSongLoaded(sidebarPanel.getOnSongLoaded());

    songPanel.setOnEditRequest(
        (trackId, clipId) -> {
          System.out.println("Swing Callback: Edit track " + trackId + " Clip: " + clipId);
          sidebarPanel.updateFocusTrack(trackId);

          if (clipPanel != null) {
            // Engine row = model track index (each track maps to one sequential row)
            java.util.List<org.chuck.deluge.model.TrackModel> allTrks =
                clipPanel.getProjectModel() != null
                    ? clipPanel.getProjectModel().getTracks()
                    : java.util.List.of();
            int engineBase = Math.min(trackId, BridgeContract.TRACKS - 1);

            clipPanel.setActiveClipId(clipId);
            clipPanel.setBaseTrackId(engineBase);

            boolean editIsSynth =
                trackId < allTrks.size()
                    && allTrks.get(trackId) instanceof org.chuck.deluge.model.SynthTrackModel;

            // Clear engine rows for this track
            for (int s = 0; s < 16; s++) bridge.setStep(engineBase, s, false);

            if (trackId < allTrks.size()) {
              org.chuck.deluge.model.TrackModel tModel = allTrks.get(trackId);
              if (clipId < tModel.getClips().size()) {
                org.chuck.deluge.model.ClipModel cModel = tModel.getClips().get(clipId);
                for (int r = 0; r < cModel.getRowCount(); r++) {
                  for (int s = 0; s < 16; s++) {
                    org.chuck.deluge.model.StepData sd = cModel.getStep(r, s);
                    if (sd != null && sd.active()) {
                      if (editIsSynth) {
                        bridge.setStep(engineBase, s, true);
                        bridge.setPitch(engineBase, s, (24 - 1) - r);
                      } else {
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
