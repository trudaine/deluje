package org.chuck.deluge.ui;

import javax.swing.*;
import java.awt.*;
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

    setTitle("DELUGE WORKSTATION [SWING EDITION]");
    setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    setSize(1400, 850);
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
    JMenuItem prefItem = new JMenuItem("Preferences...");
    settingsMenu.add(sampleItem);
    settingsMenu.add(prefItem);

    menuBar.add(fileMenu);
    menuBar.add(settingsMenu);
    setJMenuBar(menuBar);

    // 1. Top Transport Bar
    JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 10));
    topBar.setBackground(new Color(0x25, 0x25, 0x25));
    
    JButton playBtn = new JButton("▶ PLAY");
    playBtn.setBackground(new Color(0x33, 0x66, 0x33));
    playBtn.setForeground(Color.WHITE);
    playBtn.setFocusPainted(false);
    playBtn.addActionListener(e -> {
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
    stopBtn.addActionListener(e -> {
      if (bridge != null) {
        vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
        playBtn.setText("▶ PLAY");
      }
    });

    JLabel tempoLabel = new JLabel("TEMPO: 120 BPM");
    tempoLabel.setForeground(Color.LIGHT_GRAY);

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

    // 5. Bottom Velocity lane
    SwingVelocityLanePanel bottomLane = new SwingVelocityLanePanel(vm, bridge);
    add(bottomLane, BorderLayout.SOUTH);
  }



  private void startPlaybackTimer() {
    Timer timer = new Timer(30, e -> {
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
