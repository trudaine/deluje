package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/** Alternative lightweight UI running purely on Java Swing (no native libs). */
public class SwingDelugeApp extends JFrame {
  private final ChuckVM vm;
  private final BridgeContract bridge;

  private SwingMatrixPanel matrixPanel;
  private SwingVisualizerPanel visualizerPanel;

  private JPanel centerCardPanel;
  private CardLayout cardLayout;

  public SwingDelugeApp(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;

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
    setSize(1400, 800);
    setLocationRelativeTo(null);
    getContentPane().setBackground(new Color(0x1a, 0x1a, 0x1a));
    setLayout(new BorderLayout(10, 10));

    setupUI();
    startPlaybackTimer();
  }

  private void setupUI() {
    // 0. Menu Bar
    JMenuBar menuBar = new JMenuBar();

    JMenu fileMenu = new JMenu("File");
    JMenuItem newItem = new JMenuItem("New Project (Ctrl+N)");
    JMenuItem saveItem = new JMenuItem("Save Project (Ctrl+S)");
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
          int result = chooser.showOpenDialog(this);
          if (result == JFileChooser.APPROVE_OPTION) {
            java.io.File dir = chooser.getSelectedFile();
            org.chuck.deluge.project.PreferencesManager.setSamplesDir(dir.getAbsolutePath());
            System.out.println("Swing: Set samples directory to " + dir.getAbsolutePath());
          }
        });

    JMenuItem prefItem = new JMenuItem("Preferences...");
    prefItem.addActionListener(
        e -> {
          String[] midiPorts = org.chuck.midi.MidiIn.list();
          if (midiPorts.length == 0) {
            JOptionPane.showMessageDialog(
                this,
                "No MIDI Input ports available.",
                "Preferences",
                JOptionPane.INFORMATION_MESSAGE);
          } else {
            String input =
                (String)
                    JOptionPane.showInputDialog(
                        this,
                        "Select MIDI Input Port:",
                        "Preferences",
                        JOptionPane.QUESTION_MESSAGE,
                        null,
                        midiPorts,
                        midiPorts[0]);
            if (input != null) {
              org.chuck.deluge.project.PreferencesManager.set("midi.input", input);
              System.out.println("Swing: Set MIDI Input port to " + input);
            }
          }
        });

    settingsMenu.add(sampleItem);
    settingsMenu.add(prefItem);

    menuBar.add(fileMenu);
    menuBar.add(settingsMenu);
    setJMenuBar(menuBar);

    // 1. Top Transport Bar
    JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 10));
    topBar.setBackground(new Color(0x25, 0x25, 0x25));

    JButton playBtn = new JButton("▶ PLAY");
    playBtn.setToolTipText("Toggles VM sound playback on and off.");
    playBtn.setBackground(new Color(0x33, 0x66, 0x33));
    playBtn.setForeground(Color.WHITE);
    playBtn.setFocusPainted(false);
    playBtn.addActionListener(
        e -> {
          if (bridge != null) {
            long currentPlay = vm.getGlobalInt(BridgeContract.G_PLAY);
            vm.setGlobalInt(BridgeContract.G_PLAY, currentPlay == 1L ? 0L : 1L);
            playBtn.setText(currentPlay == 1L ? "▶ PLAY" : "⏸ PAUSE");
          }
        });

    JButton stopBtn = new JButton("■ STOP");
    stopBtn.setBackground(new Color(0x66, 0x33, 0x33));
    stopBtn.setForeground(Color.WHITE);
    stopBtn.setFocusPainted(false);
    stopBtn.addActionListener(
        e -> {
          if (bridge != null) {
            vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
            playBtn.setText("▶ PLAY");
          }
        });

    JLabel tempoLabel = new JLabel("BPM:");
    tempoLabel.setForeground(Color.LIGHT_GRAY);
    JSlider bpmSlider = new JSlider(60, 200, 120);
    bpmSlider.addChangeListener(
        e -> {
          if (bridge != null) {
            vm.setGlobalFloat(BridgeContract.G_BPM, bpmSlider.getValue());
          }
        });

    // Mode Toggle buttons
    JToggleButton clipBtn = new JToggleButton("CLIP", true);
    JToggleButton songBtn = new JToggleButton("SONG");
    JToggleButton arrBtn = new JToggleButton("ARR");
    ButtonGroup group = new ButtonGroup();
    group.add(clipBtn);
    group.add(songBtn);
    group.add(arrBtn);

    cardLayout = new CardLayout();
    centerCardPanel = new JPanel(cardLayout);

    clipBtn.addActionListener(e -> cardLayout.show(centerCardPanel, "CLIP"));
    songBtn.addActionListener(e -> cardLayout.show(centerCardPanel, "SONG"));
    arrBtn.addActionListener(e -> cardLayout.show(centerCardPanel, "ARR"));

    topBar.add(clipBtn);
    topBar.add(songBtn);
    topBar.add(arrBtn);
    topBar.add(new JSeparator(JSeparator.VERTICAL));
    topBar.add(playBtn);
    topBar.add(stopBtn);
    topBar.add(tempoLabel);
    topBar.add(bpmSlider);

    add(topBar, BorderLayout.NORTH);

    // 2. Center Matrix Layout Views
    matrixPanel = new SwingMatrixPanel(vm, bridge);
    centerCardPanel.add(matrixPanel, "CLIP");

    SwingSongModePanel songPanel = new SwingSongModePanel(vm, bridge);
    centerCardPanel.add(songPanel, "SONG");

    SwingArrangerPanel arrPanel = new SwingArrangerPanel(vm, bridge);
    centerCardPanel.add(arrPanel, "ARR");

    add(centerCardPanel, BorderLayout.CENTER);

    // 3. Left Sidebar
    SwingProjectSidebarPanel sidebarPanel = new SwingProjectSidebarPanel(vm, bridge);
    add(sidebarPanel, BorderLayout.WEST);

    // 4. Right Visualizer
    visualizerPanel = new SwingVisualizerPanel(vm);
    visualizerPanel.setPreferredSize(new Dimension(200, 0));
    add(visualizerPanel, BorderLayout.EAST);

    // 5. Bottom Master FX strip and Velocity Lane
    JPanel bottomContainer = new JPanel(new BorderLayout());
    SwingVelocityLanePanel bottomLane = new SwingVelocityLanePanel(vm, bridge);

    JPanel ribbonStrip = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
    ribbonStrip.setBackground(new Color(0x1f, 0x1f, 0x1f));
    JButton velBtn = new JButton("VELOCITY");
    JButton gateBtn = new JButton("GATE");
    JButton pitchBtn = new JButton("PITCH");

    velBtn.addActionListener(e -> bottomLane.setMode("VELOCITY"));
    gateBtn.addActionListener(e -> bottomLane.setMode("GATE"));
    pitchBtn.addActionListener(e -> bottomLane.setMode("PITCH"));

    ribbonStrip.add(velBtn);
    ribbonStrip.add(gateBtn);
    ribbonStrip.add(pitchBtn);

    bottomContainer.add(ribbonStrip, BorderLayout.NORTH);
    bottomContainer.add(bottomLane, BorderLayout.CENTER);

    JPanel masterFxPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 5));
    masterFxPanel.setBackground(new Color(0x25, 0x25, 0x25));

    JLabel volLabel = new JLabel("Master Vol:");
    volLabel.setForeground(Color.WHITE);
    JSlider volSlider = new JSlider(0, 100, 70);
    volSlider.addChangeListener(
        e -> vm.setGlobalFloat(BridgeContract.G_MASTER_VOL, volSlider.getValue() / 100.0));

    JLabel delLabel = new JLabel("Delay Send:");
    delLabel.setForeground(Color.WHITE);
    JSlider delSlider = new JSlider(0, 100, 30);
    delSlider.addChangeListener(
        e -> vm.setGlobalFloat(BridgeContract.G_DELAY_TIME, delSlider.getValue() / 100.0));

    masterFxPanel.add(volLabel);
    masterFxPanel.add(volSlider);
    masterFxPanel.add(delLabel);
    masterFxPanel.add(delSlider);

    bottomContainer.add(masterFxPanel, BorderLayout.SOUTH);
    add(bottomContainer, BorderLayout.SOUTH);
  }

  private void startPlaybackTimer() {
    Timer timer =
        new Timer(
            30,
            e -> {
              int step = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
              if (matrixPanel != null) {
                matrixPanel.setCurrentStep(step);
              }
              if (visualizerPanel != null) {
                visualizerPanel.repaint();
              }
            });
    timer.start();
  }
}
