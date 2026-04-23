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

  public SwingDelugeApp(ChuckVM vm, BridgeContract bridge, org.chuck.deluge.midi.MidiService midiService) {
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
    addKeyListener(new java.awt.event.KeyAdapter() {
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
             }
          }

       }
    });

    
    int w = Integer.parseInt(org.chuck.deluge.project.PreferencesManager.get("window.width", "2800"));
    int h = Integer.parseInt(org.chuck.deluge.project.PreferencesManager.get("window.height", "1600"));
    setSize(w, h);
    
    addWindowListener(new java.awt.event.WindowAdapter() {
      @Override
      public void windowClosing(java.awt.event.WindowEvent e) {
        org.chuck.deluge.project.PreferencesManager.set("window.width", String.valueOf(getWidth()));
        org.chuck.deluge.project.PreferencesManager.set("window.height", String.valueOf(getHeight()));
      }
    });

    setLocationRelativeTo(null);
    getContentPane().setBackground(new Color(0x1a, 0x1a, 0x1a));
    setLayout(new BorderLayout(10, 10));


    setupUI();
    startPlaybackTimer();

    setFocusable(true);
    addKeyListener(new java.awt.event.KeyAdapter() {
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
    newItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_N, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    JMenuItem saveItem = new JMenuItem("Save Project");
    saveItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    JMenuItem exitItem = new JMenuItem("Exit");
    exitItem.addActionListener(e -> System.exit(0));
    fileMenu.add(newItem);
    fileMenu.add(saveItem);
    fileMenu.addSeparator();
    fileMenu.add(exitItem);

    JMenu settingsMenu = new JMenu("Settings");
    JMenuItem sampleItem = new JMenuItem("Set Samples Directory...");
    sampleItem.addActionListener(e -> {
      JFileChooser chooser = new JFileChooser();
      chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
      if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
        org.chuck.deluge.project.PreferencesManager.setSamplesDir(chooser.getSelectedFile().getAbsolutePath());
      }
    });
    JMenuItem prefItem = new JMenuItem("Preferences...");
    prefItem.addActionListener(e -> {
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
      c.gridx = 0; c.gridy = 0; c.gridwidth = 2;
      mainGrid.add(Box.createVerticalStrut(30), c);

      // 1. Reverb Model
      c.gridwidth = 1;
      c.gridx = 0; c.gridy = 1;
      mainGrid.add(new JLabel("Reverb Model:"), c);

      c.gridx = 1;
      JComboBox<String> reverbCombo = new JComboBox<>(new String[]{"JCRev", "FreeVerb", "MVerb", "ProceduralReverb"});
      reverbCombo.setSelectedItem(org.chuck.deluge.project.PreferencesManager.get("reverb.model", "JCRev"));
      mainGrid.add(reverbCombo, c);

      c.gridx = 1; c.gridy = 2;
      JLabel revHelp = new JLabel("<html><i>Select acoustic model structure</i></html>");
      revHelp.setForeground(Color.GRAY);
      mainGrid.add(revHelp, c);

      // 2. MIDI Input
      c.gridx = 0; c.gridy = 3;
      mainGrid.add(new JLabel("MIDI Input:"), c);
      c.gridx = 1;
      String[] ports = org.chuck.midi.MidiIn.list();
      String[] comboPorts = new String[ports.length + 1];
      comboPorts[0] = "None";
      System.arraycopy(ports, 0, comboPorts, 1, ports.length);
      JComboBox<String> midiCombo = new JComboBox<>(comboPorts);
      midiCombo.setSelectedItem(org.chuck.deluge.project.PreferencesManager.get("midi.input", "None"));
      mainGrid.add(midiCombo, c);
      
      c.gridx = 1; c.gridy = 4;
      JLabel midiHelp = new JLabel("<html><i>Requires application reboot to re-route</i></html>");
      midiHelp.setForeground(Color.GRAY);
      mainGrid.add(midiHelp, c);

      // 3. Checkboxes
      c.gridx = 0; c.gridy = 5;
      mainGrid.add(new JLabel("Show Visualizers:"), c);
      c.gridx = 1;
      JCheckBox visCheck = new JCheckBox("", Boolean.parseBoolean(org.chuck.deluge.project.PreferencesManager.get("show.visualizers", "true")));
      mainGrid.add(visCheck, c);

      c.gridx = 0; c.gridy = 6;
      mainGrid.add(new JLabel("Debug Audio:"), c);
      c.gridx = 1;
      JCheckBox debugCheck = new JCheckBox("", Boolean.parseBoolean(org.chuck.deluge.project.PreferencesManager.get("debug.audio", "false")));
      mainGrid.add(debugCheck, c);

      c.gridx = 0; c.gridy = 7;
      mainGrid.add(new JLabel("MIDI Grid Mode:"), c);
      c.gridx = 1;
      JCheckBox gridModeCheck = new JCheckBox("", Boolean.parseBoolean(org.chuck.deluge.project.PreferencesManager.get("midi.grid.mode", "false")));
      mainGrid.add(gridModeCheck, c);

      c.gridx = 0; c.gridy = 8;
      mainGrid.add(new JLabel("Show Tooltips:"), c);
      c.gridx = 1;
      JCheckBox tooltipCheck = new JCheckBox("", Boolean.parseBoolean(org.chuck.deluge.project.PreferencesManager.get("show.tooltips", "true")));
      mainGrid.add(tooltipCheck, c);

      // 4. Active Mappings
      c.gridx = 0; c.gridy = 9;
      mainGrid.add(new JLabel("Active Mappings:"), c);
      c.gridx = 1;
      DefaultListModel<String> listModel = new DefaultListModel<>();
      if (midiService != null) {
        for (java.util.Map.Entry<String, Integer> entry : midiService.getMappings().entrySet()) {
          listModel.addElement(entry.getKey() + " -> CC " + entry.getValue());
        }
      }
      JList<String> mappingList = new JList<>(listModel);
      mainGrid.add(new JScrollPane(mappingList), c);

      // 5. Samples Directory
      c.gridx = 0; c.gridy = 10;
      mainGrid.add(new JLabel("Samples Directory:"), c);

      
      c.gridx = 1;
      String currentDir = org.chuck.deluge.project.PreferencesManager.getSamplesDir();
      JLabel dirLabel = new JLabel(currentDir != null ? currentDir : "Not Set");
      dirLabel.setForeground(Color.CYAN);
      mainGrid.add(dirLabel, c);
      
      c.gridy = 11;
      JButton browseBtn = new JButton("Browse...");
      browseBtn.addActionListener(ev -> {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
          org.chuck.deluge.project.PreferencesManager.setSamplesDir(chooser.getSelectedFile().getAbsolutePath());
          dirLabel.setText(chooser.getSelectedFile().getAbsolutePath());
        }
      });
      mainGrid.add(browseBtn, c);

      // 6. Save Button
      JButton saveBtn = new JButton("Save");
      saveBtn.setFont(new Font("SansSerif", Font.BOLD, 28));
      saveBtn.addActionListener(ev -> {
        org.chuck.deluge.project.PreferencesManager.set("reverb.model", (String) reverbCombo.getSelectedItem());
        org.chuck.deluge.project.PreferencesManager.set("midi.input", (String) midiCombo.getSelectedItem());
        org.chuck.deluge.project.PreferencesManager.set("show.visualizers", String.valueOf(visCheck.isSelected()));
        org.chuck.deluge.project.PreferencesManager.set("debug.audio", String.valueOf(debugCheck.isSelected()));
        org.chuck.deluge.project.PreferencesManager.set("midi.grid.mode", String.valueOf(gridModeCheck.isSelected()));
        org.chuck.deluge.project.PreferencesManager.set("show.tooltips", String.valueOf(tooltipCheck.isSelected()));
        dialog.dispose();
      });

      // Apply large font to all elements
      Font bigFont = new Font("SansSerif", Font.PLAIN, 24);
      for (Component comp : mainGrid.getComponents()) {
         if (comp instanceof JLabel || comp instanceof JComboBox || comp instanceof JCheckBox || comp instanceof JButton) {
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

    // 1. Top Area (Buttons, Modes, Transport, Sliders)
    JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 8));
    topBar.setBackground(new Color(0x25, 0x25, 0x25));

    // View Toggle Buttons
    JToggleButton clipBtn = new JToggleButton("CLIP", true);
    JToggleButton songBtn = new JToggleButton("SONG");
    JToggleButton arrBtn = new JToggleButton("ARR");
    ButtonGroup modeGroup = new ButtonGroup();
    modeGroup.add(clipBtn); modeGroup.add(songBtn); modeGroup.add(arrBtn);

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

    clipBtn.addActionListener(e -> {
        cardLayout.show(centerCardPanel, "CLIP");
    });
    songBtn.addActionListener(e -> {
        cardLayout.show(centerCardPanel, "SONG");
    });
    arrBtn.addActionListener(e -> {
        cardLayout.show(centerCardPanel, "ARR");
    });


    topBar.add(clipBtn);
    topBar.add(songBtn);
    topBar.add(arrBtn);
    topBar.add(new JSeparator(JSeparator.VERTICAL));

    // Transport
    JButton playBtn = new JButton("▶ PLAY");
    playBtn.setBackground(new Color(0x33, 0x66, 0x33));
    playBtn.setForeground(Color.WHITE);
    playBtn.addActionListener(e -> vm.setGlobalInt(BridgeContract.G_PLAY, vm.getGlobalInt(BridgeContract.G_PLAY) == 1L ? 0L : 1L));

    JButton stopBtn = new JButton("■ STOP");
    stopBtn.setBackground(new Color(0x66, 0x33, 0x33));
    stopBtn.setForeground(Color.WHITE);
    stopBtn.addActionListener(e -> vm.setGlobalInt(BridgeContract.G_PLAY, 0L));

    JToggleButton recBtn = new JToggleButton("● REC");
    recBtn.setForeground(Color.RED);
    recBtn.addActionListener(e -> {
      if (midiService != null) midiService.setRecording(recBtn.isSelected());
    });

    JButton loadBtn = new JButton("📂 LOAD XML");
    loadBtn.addActionListener(e -> {
      JFileChooser chooser = new JFileChooser();
      if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
        System.out.println("Loading " + chooser.getSelectedFile().getAbsolutePath());
      }
    });

    JButton saveSongBtn = new JButton("💾 SAVE XML");
    saveSongBtn.addActionListener(e -> {
      JFileChooser chooser = new JFileChooser();
      if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
        System.out.println("Swing: Saving song project XML to " + chooser.getSelectedFile().getAbsolutePath());
      }
    });

    topBar.add(playBtn);
    topBar.add(stopBtn);
    topBar.add(recBtn);
    topBar.add(loadBtn);
    topBar.add(saveSongBtn);

    topBar.add(new JSeparator(JSeparator.VERTICAL));

    // Sliders
    JLabel tempoLabel = new JLabel("BPM:");
    tempoLabel.setForeground(Color.WHITE);
    JSlider bpmSlider = new JSlider(60, 200, 120);
    bpmSlider.addChangeListener(e -> vm.setGlobalFloat(BridgeContract.G_BPM, bpmSlider.getValue()));
    
    JLabel swingLabel = new JLabel("SWING:");
    swingLabel.setForeground(Color.WHITE);
    JSlider swingSlider = new JSlider(0, 100, 50);
    swingSlider.addChangeListener(e -> vm.setGlobalFloat(BridgeContract.G_SWING, swingSlider.getValue() / 100.0));

    JLabel volLabel = new JLabel("MASTER:");
    volLabel.setForeground(Color.WHITE);
    topMasterVolSlider = new JSlider(0, 100, 70);
    topMasterVolSlider.addChangeListener(e -> {
      double v = topMasterVolSlider.getValue() / 100.0;
      vm.setGlobalFloat(BridgeContract.G_MASTER_VOL, v);
      if (bottomMasterVolSlider != null && bottomMasterVolSlider.getValue() != topMasterVolSlider.getValue()) {
        bottomMasterVolSlider.setValue(topMasterVolSlider.getValue());
      }
    });

    topBar.add(tempoLabel); topBar.add(bpmSlider);
    topBar.add(swingLabel); topBar.add(swingSlider);
    topBar.add(volLabel); topBar.add(topMasterVolSlider);

    gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 3; gbc.weightx = 1.0; gbc.weighty = 0.0;
    add(topBar, gbc);


    JPanel centeredWrapper = new JPanel(new GridBagLayout());
    centeredWrapper.setBackground(new Color(0x1a, 0x1a, 0x1a));
    centeredWrapper.add(centerCardPanel);
    centeredWrapper.setPreferredSize(new Dimension(2600, 1300));



    JScrollPane centerScroll = new JScrollPane(centeredWrapper);
    centerScroll.setBorder(BorderFactory.createEmptyBorder());

    gbc.fill = GridBagConstraints.BOTH;
    gbc.gridx = 1; gbc.gridy = 1; gbc.gridwidth = 1; gbc.weightx = 0.0; gbc.weighty = 1.0;
    add(centerScroll, gbc);



    // 2. Left Area (SD Card / Editors)
    SwingProjectSidebarPanel sidebarPanel = new SwingProjectSidebarPanel(vm, bridge, midiService);
    sidebarPanel.setOnSongLoaded(model -> {
      System.out.println("Swing Callback: Song model loaded! Tracks: " + model.getTracks().size());
      songPanel.setProjectModel(model);
      if (clipPanel != null) clipPanel.setProjectModel(model);
      if (arrGridPanel != null) arrGridPanel.setProjectModel(model);

      if (model.getTracks().size() == 1) {
         cardLayout.show(centerCardPanel, "CLIP");
         if (clipBtn != null) clipBtn.setSelected(true);
      } else {
         cardLayout.show(centerCardPanel, "SONG");
      }


      int trackIdx = 0;
      for (org.chuck.deluge.model.TrackModel track : model.getTracks()) {
        for (org.chuck.deluge.model.ClipModel clip : track.getClips()) {
          for (int r = 0; r < 8; r++) {
            for (int s = 0; s < 16; s++) {
              org.chuck.deluge.model.StepData step = clip.getStep(r, s);
              if (step != null) {
                bridge.setStep(trackIdx * 8 + r, s, step.active());
              }
            }
          }
        }
        trackIdx++;
      }
      clipPanel.repaint();
    });

    songPanel.setOnEditRequest((trackId, clipId) -> {
      System.out.println("Swing Callback: Edit track " + trackId);
      sidebarPanel.updateFocusTrack(trackId);
      cardLayout.show(centerCardPanel, "CLIP");
      clipBtn.setSelected(true);
    });


    gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1; gbc.weightx = 0.5; gbc.weighty = 1.0;
    add(sidebarPanel, gbc);


    // 4. Right Side Viewport (Curves/Graphs/Visualizers)
    visualizerPanel = new SwingVisualizerPanel(vm, bridge);

    visualizerPanel.setPreferredSize(new Dimension(300, 0));
    gbc.gridx = 2; gbc.gridy = 1; gbc.gridwidth = 1; gbc.weightx = 0.5; gbc.weighty = 1.0;
    add(visualizerPanel, gbc);

    new Timer(33, e -> visualizerPanel.repaint()).start();






    // 6. Bottom Area - Row 2 (Velocity lane plot)
    SwingVelocityLanePanel bottomLane = new SwingVelocityLanePanel(vm, bridge);
    
    // 5. Bottom Area - Rows 9 and 10 (Param Deck)
    JPanel ribbonStrip = new JPanel(new GridLayout(2, 8, 4, 4));
    ribbonStrip.setBackground(new Color(0x1f, 0x1f, 0x1f));

    String[] row1 = {"LEVEL", "PAN", "PITCH", "FILTER", "RESONANCE", "OSC1", "OSC2", "LFO"};
    String[] row2 = {"MOD FX", "DELAY", "REVERB", "STUTTER", "PROBABILITY", "GATE", "VELOCITY", "SAMPLE"};

    for (String label : row1) {
      JButton btn = new JButton(label);
      btn.setPreferredSize(new Dimension(120, 50));
      btn.setBackground(new Color(0x33, 0x33, 0x33));
      btn.setForeground(Color.LIGHT_GRAY);
      btn.addActionListener(e -> bottomLane.setMode(label));
      ribbonStrip.add(btn);
    }

    for (String label : row2) {
      JButton btn = new JButton(label);
      btn.setPreferredSize(new Dimension(120, 50));
      btn.setBackground(new Color(0x33, 0x33, 0x33));
      btn.setForeground(Color.LIGHT_GRAY);
      btn.addActionListener(e -> bottomLane.setMode(label));
      ribbonStrip.add(btn);
    }



    gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 3; gbc.weightx = 1.0; gbc.weighty = 0.0;
    add(ribbonStrip, gbc);

    gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 3; gbc.weightx = 1.0; gbc.weighty = 0.0;
    add(bottomLane, gbc);


    // 7. Bottom Area - Row 3 (Master FX dials bounding boxes)
    JPanel masterFxPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 5));
    masterFxPanel.setBackground(new Color(0x25, 0x25, 0x25));
    masterFxPanel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(Color.GRAY), "MASTER FX", 0, 0, null, Color.WHITE));
    
    JLabel bVolLabel = new JLabel("Master Vol:");
    bVolLabel.setForeground(Color.WHITE);
    bottomMasterVolSlider = new JSlider(0, 100, 70);
    bottomMasterVolSlider.addChangeListener(e -> {
      double v = bottomMasterVolSlider.getValue() / 100.0;
      vm.setGlobalFloat(BridgeContract.G_MASTER_VOL, v);
      if (topMasterVolSlider != null && topMasterVolSlider.getValue() != bottomMasterVolSlider.getValue()) {
        topMasterVolSlider.setValue(bottomMasterVolSlider.getValue());
      }
    });
    masterFxPanel.add(bVolLabel); masterFxPanel.add(bottomMasterVolSlider);


    JLabel statusCounter = new JLabel("1:1:1");
    statusCounter.setForeground(Color.GREEN);
    statusCounter.setFont(new Font("Monospaced", Font.BOLD, 24));
    masterFxPanel.add(statusCounter);

    gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 3; gbc.weightx = 1.0; gbc.weighty = 0.0;
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

}
