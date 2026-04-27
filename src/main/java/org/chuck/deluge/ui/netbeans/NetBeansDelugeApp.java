package org.chuck.deluge.ui.netbeans;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import javax.swing.UIManager;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/** NetBeans-compatible main application frame with fixed-size core and wrapping top panel. */
public class NetBeansDelugeApp extends javax.swing.JFrame {
  private final MainViewModel mainVM;

  private NetBeansTransportPanel transportPanel;
  private NetBeansProjectSidebarPanel sidebarPanel;
  private NetBeansGridPanel gridPanel;
  private NetBeansSongModePanel songPanel;
  private NetBeansVisualizerPanel visualizerPanel;
  private NetBeansStatusRibbonPanel statusRibbon;
  private javax.swing.JTabbedPane centerTabs;

  public NetBeansDelugeApp(ChuckVM vm, BridgeContract bridge) {
    System.out.println("UI: Initializing NetBeansDelugeApp...");
    this.mainVM = new MainViewModel(vm, bridge);
    applyGlobalStyles();
    initComponents();
    setupComponents();
    setupKeyListeners();
    setupViewModelListeners();

    setTitle("DELUGE WORKSTATION [NETBEANS EDITION]");
    pack();
    setSize(1350, 950);
    setLocationRelativeTo(null);
  }

  private void applyGlobalStyles() {
    Color darkBg = new Color(30, 30, 30);
    Color darkerBg = new Color(20, 20, 20);
    Color lightFg = new Color(220, 220, 220);
    Color accent = new Color(0, 255, 204);

    UIManager.put("Panel.background", darkBg);
    UIManager.put("Label.foreground", lightFg);
    UIManager.put("TabbedPane.background", darkBg);
    UIManager.put("TabbedPane.foreground", lightFg);
    UIManager.put("TabbedPane.selected", darkerBg);
    UIManager.put("TabbedPane.contentAreaColor", darkerBg);
    UIManager.put("TabbedPane.unselectedBackground", darkBg);
    UIManager.put("Button.background", new Color(50, 50, 50));
    UIManager.put("Button.foreground", Color.WHITE);
    UIManager.put("Tree.background", darkerBg);
    UIManager.put("Tree.foreground", lightFg);
  }

  private void setupViewModelListeners() {
    mainVM.addPropertyChangeListener(
        evt -> {
          if ("selectedMode".equals(evt.getPropertyName())) {
            int mode = (int) evt.getNewValue();
            if (centerTabs != null && mode < centerTabs.getTabCount()) {
              centerTabs.setSelectedIndex(mode);
            }
          }
        });
  }

  private void setupKeyListeners() {
    java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
        .addKeyEventDispatcher(
            e -> {
              if (e.getID() == java.awt.event.KeyEvent.KEY_PRESSED) {
                switch (e.getKeyCode()) {
                  case java.awt.event.KeyEvent.VK_SHIFT -> mainVM.setShiftDown(true);
                  case java.awt.event.KeyEvent.VK_SPACE -> {
                    mainVM.togglePlayback();
                    return true;
                  }
                  case java.awt.event.KeyEvent.VK_R -> {
                    mainVM.toggleRecording();
                    return true;
                  }
                  case java.awt.event.KeyEvent.VK_Z -> mainVM.triggerNote(60);
                  case java.awt.event.KeyEvent.VK_S -> mainVM.triggerNote(61);
                  case java.awt.event.KeyEvent.VK_X -> mainVM.triggerNote(62);
                  case java.awt.event.KeyEvent.VK_D -> mainVM.triggerNote(63);
                  case java.awt.event.KeyEvent.VK_C -> mainVM.triggerNote(64);
                  case java.awt.event.KeyEvent.VK_V -> mainVM.triggerNote(65);
                }
              } else if (e.getID() == java.awt.event.KeyEvent.KEY_RELEASED) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_SHIFT) mainVM.setShiftDown(false);
              }
              return false;
            });
  }

  private void setupComponents() {
    // 1. Top Panel
    transportPanel = new NetBeansTransportPanel();
    transportPanel.setViewModel(mainVM);
    transportPanel.setPreferredSize(new Dimension(900, 60));

    javax.swing.JPanel topWrapper = new javax.swing.JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
    topWrapper.setBackground(new Color(40, 40, 40));
    topWrapper.add(transportPanel);
    mainPanel.add(topWrapper, BorderLayout.NORTH);

    // 2. Sidebar
    sidebarPanel = new NetBeansProjectSidebarPanel();
    sidebarPanel.setViewModel(mainVM);
    sidebarPanel.setPreferredSize(new Dimension(280, 0));
    mainPanel.add(sidebarPanel, BorderLayout.WEST);

    // 3. Center
    centerTabs = new javax.swing.JTabbedPane();
    gridPanel = new NetBeansGridPanel();
    gridPanel.setViewModel(mainVM);

    javax.swing.JPanel gridWrapper = new javax.swing.JPanel(new GridBagLayout());
    gridWrapper.setBackground(new Color(20, 20, 20));
    gridWrapper.add(gridPanel);
    centerTabs.addTab("CLIP MODE", new javax.swing.JScrollPane(gridWrapper));

    songPanel = new NetBeansSongModePanel();
    songPanel.setViewModel(mainVM);
    centerTabs.addTab("SONG MODE", new javax.swing.JScrollPane(songPanel));

    mainPanel.add(centerTabs, BorderLayout.CENTER);

    // 4. Bottom
    javax.swing.JPanel bottomBox = new javax.swing.JPanel(new BorderLayout());
    visualizerPanel = new NetBeansVisualizerPanel();
    visualizerPanel.setViewModel(mainVM);
    visualizerPanel.setPreferredSize(new Dimension(0, 120));
    bottomBox.add(visualizerPanel, BorderLayout.CENTER);

    statusRibbon = new NetBeansStatusRibbonPanel();
    statusRibbon.setViewModel(mainVM);
    bottomBox.add(statusRibbon, BorderLayout.SOUTH);

    mainPanel.add(bottomBox, BorderLayout.SOUTH);
  }

  @SuppressWarnings("unchecked")
  // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
  private void initComponents() {

    menuBar = new javax.swing.JMenuBar();
    fileMenu = new javax.swing.JMenu();
    newSongMenuItem = new javax.swing.JMenuItem();
    openSongMenuItem = new javax.swing.JMenuItem();
    jSeparator1 = new javax.swing.JPopupMenu.Separator();
    saveMenuItem = new javax.swing.JMenuItem();
    saveAsMenuItem = new javax.swing.JMenuItem();
    jSeparator2 = new javax.swing.JPopupMenu.Separator();
    exitMenuItem = new javax.swing.JMenuItem();
    editMenu = new javax.swing.JMenu();
    undoMenuItem = new javax.swing.JMenuItem();
    redoMenuItem = new javax.swing.JMenuItem();
    jSeparator3 = new javax.swing.JPopupMenu.Separator();
    settingsMenuItem = new javax.swing.JMenuItem();
    helpMenu = new javax.swing.JMenu();
    aboutMenuItem = new javax.swing.JMenuItem();
    mainPanel = new javax.swing.JPanel();

    fileMenu.setText("File");

    newSongMenuItem.setAccelerator(
        javax.swing.KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_N, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    newSongMenuItem.setText("New Song");
    fileMenu.add(newSongMenuItem);

    openSongMenuItem.setAccelerator(
        javax.swing.KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_O, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    openSongMenuItem.setText("Open Song...");
    fileMenu.add(openSongMenuItem);
    fileMenu.add(jSeparator1);

    saveMenuItem.setAccelerator(
        javax.swing.KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_S, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    saveMenuItem.setText("Save");
    fileMenu.add(saveMenuItem);

    saveAsMenuItem.setText("Save As...");
    fileMenu.add(saveAsMenuItem);
    fileMenu.add(jSeparator2);

    exitMenuItem.setText("Exit");
    exitMenuItem.addActionListener(
        new java.awt.event.ActionListener() {
          public void actionPerformed(java.awt.event.ActionEvent evt) {
            exitMenuItemActionPerformed(evt);
          }
        });
    fileMenu.add(exitMenuItem);

    menuBar.add(fileMenu);

    editMenu.setText("Edit");

    undoMenuItem.setAccelerator(
        javax.swing.KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_Z, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    undoMenuItem.setText("Undo");
    editMenu.add(undoMenuItem);

    redoMenuItem.setAccelerator(
        javax.swing.KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_Y, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    redoMenuItem.setText("Redo");
    editMenu.add(redoMenuItem);
    editMenu.add(jSeparator3);

    settingsMenuItem.setText("Settings...");
    editMenu.add(settingsMenuItem);

    menuBar.add(editMenu);

    helpMenu.setText("Help");

    aboutMenuItem.setText("About Deluge Workstation");
    helpMenu.add(aboutMenuItem);

    menuBar.add(helpMenu);

    setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
    setTitle("DELUGE WORKSTATION [NETBEANS EDITION]");
    setJMenuBar(menuBar);

    mainPanel.setBackground(new java.awt.Color(26, 26, 26));
    mainPanel.setLayout(new java.awt.BorderLayout());
    getContentPane().add(mainPanel, java.awt.BorderLayout.CENTER);

    pack();
  } // </editor-fold>//GEN-END:initComponents

  private void exitMenuItemActionPerformed(java.awt.event.ActionEvent evt) {
    System.exit(0);
  }

  // Variables declaration - do not modify//GEN-BEGIN:variables
  private javax.swing.JMenuItem aboutMenuItem;
  private javax.swing.JMenu editMenu;
  private javax.swing.JMenuItem exitMenuItem;
  private javax.swing.JMenu fileMenu;
  private javax.swing.JMenu helpMenu;
  private javax.swing.JPopupMenu.Separator jSeparator1;
  private javax.swing.JPopupMenu.Separator jSeparator2;
  private javax.swing.JPopupMenu.Separator jSeparator3;
  private javax.swing.JPanel mainPanel;
  private javax.swing.JMenuBar menuBar;
  private javax.swing.JMenuItem newSongMenuItem;
  private javax.swing.JMenuItem openSongMenuItem;
  private javax.swing.JMenuItem redoMenuItem;
  private javax.swing.JMenuItem saveAsMenuItem;
  private javax.swing.JMenuItem saveMenuItem;
  private javax.swing.JMenuItem undoMenuItem;
  private javax.swing.JMenuItem settingsMenuItem;
  // End of variables declaration//GEN-END:variables
}
