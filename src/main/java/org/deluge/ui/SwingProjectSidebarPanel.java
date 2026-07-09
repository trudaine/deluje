package org.deluge.ui;

import java.awt.*;
import java.io.File;
import javax.swing.*;
import org.deluge.BridgeContract;
import org.deluge.project.PreferencesManager;

/**
 * A dedicated, sleek, and lightweight filesystem tree explorer for mounting SD card resources, Kit
 * and Synth presets, and multi-track songs. Simplified to delegate tab instantiation to modular
 * components.
 */
public class SwingProjectSidebarPanel extends JPanel {

  final BridgeContract bridge;

  java.util.function.BiConsumer<org.deluge.model.ProjectModel, java.io.File> onSongLoaded;
  java.util.function.Consumer<org.deluge.model.TrackModel> onTrackAdded;
  java.util.function.Consumer<java.io.File> onPatternLoad;
  Runnable onPatternSave;

  /**
   * Invoked after the SD-card root has been changed from this panel's header button, so the app can
   * refresh every view that mirrors the library (e.g. both sidebar instances). When unset, the
   * panel just reloads its own tree.
   */
  private Runnable onLibraryDirChanged;

  // Copy/Paste clipboard state
  java.io.File localClipboardFile = null;
  String remoteClipboardPath = null;
  boolean isRemoteSource = false;

  private JButton changeDirButton;
  private JTabbedPane tabbedPane;
  private boolean hardwareRefreshedOnce = false;

  private LibrarySidebarTab libraryTab;
  private HardwareSidebarTab hardwareTab;

  public SwingProjectSidebarPanel(
      final BridgeContract bridge, org.deluge.midi.MidiService midiService) {
    this.bridge = bridge;

    setPreferredSize(new Dimension(300, 0));
    setBackground(new Color(0x12, 0x12, 0x14));
    setLayout(new BorderLayout());

    // Header segment Title + fast access to the SD-card root directory changer (same setting as
    // Preferences → "SD Card Root Directory" / File → "Set SD Card Root...").
    JPanel header = new JPanel(new BorderLayout());
    header.setBackground(new Color(0x18, 0x18, 0x1c));
    header.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
    JLabel titleLabel = new JLabel("📁 SD CARD EXPLORER", SwingConstants.CENTER);
    titleLabel.setForeground(new Color(0x00, 0xff, 0xcc));
    titleLabel.setFont(new Font("SansSerif", Font.BOLD, 10));
    header.add(titleLabel, BorderLayout.CENTER);

    changeDirButton = new JButton("📂");
    changeDirButton.setFont(new Font("SansSerif", Font.PLAIN, 11));
    changeDirButton.setMargin(new Insets(0, 4, 0, 4));
    changeDirButton.setBackground(new Color(0x2a, 0x2a, 0x30));
    changeDirButton.setForeground(Color.LIGHT_GRAY);
    changeDirButton.setFocusPainted(false);
    changeDirButton.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0x3a, 0x3a, 0x42), 1),
            BorderFactory.createEmptyBorder(2, 6, 2, 6)));
    updateChangeDirTooltip();
    changeDirButton.addActionListener(e -> chooseLibraryDir());
    header.add(changeDirButton, BorderLayout.EAST);
    add(header, BorderLayout.NORTH);

    // Tabbed panel for Local vs Physical Hardware SD Card explorer
    tabbedPane = new JTabbedPane();
    tabbedPane.setBackground(new Color(0x18, 0x18, 0x1c));
    tabbedPane.setForeground(Color.LIGHT_GRAY);
    tabbedPane.setFont(new Font("SansSerif", Font.BOLD, 10));

    libraryTab = new LibrarySidebarTab(this);
    tabbedPane.addTab("📁 LOCAL", libraryTab);

    hardwareTab = new HardwareSidebarTab(this);
    tabbedPane.addTab("📡 HARDWARE", hardwareTab);

    tabbedPane.addChangeListener(
        e -> {
          if (tabbedPane.getSelectedIndex() == 1 && !hardwareRefreshedOnce) {
            hardwareRefreshedOnce = true;
            hardwareTab.refreshHardwareTree();
          }
        });

    add(tabbedPane, BorderLayout.CENTER);
  }

  /** Switches this sidebar to its "📡 HARDWARE" (real SD card explorer) tab. */
  public void selectHardwareTab() {
    tabbedPane.setSelectedIndex(1);
  }

  /** Opens a directory chooser and applies the new SD-card root (PreferencesManager library). */
  private void chooseLibraryDir() {
    JFileChooser chooser = new JFileChooser(PreferencesManager.getLibraryDir());
    chooser.setDialogTitle("Set SD Card Root Directory");
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      PreferencesManager.setLibraryDir(chooser.getSelectedFile().getAbsolutePath());
      updateChangeDirTooltip();
      if (onLibraryDirChanged != null) {
        onLibraryDirChanged.run();
      } else {
        reloadLibrary();
      }
    }
  }

  private void updateChangeDirTooltip() {
    if (changeDirButton != null) {
      File dir = PreferencesManager.getLibraryDir();
      changeDirButton.setToolTipText(
          "Change SD card root directory (current: "
              + (dir != null ? dir.getAbsolutePath() : "(not set)")
              + ")");
    }
  }

  public void setOnLibraryDirChanged(Runnable r) {
    this.onLibraryDirChanged = r;
  }

  public void reloadLibrary() {
    updateChangeDirTooltip();
    if (libraryTab != null) {
      libraryTab.reloadLibrary();
    }
  }

  public void setOnSongLoaded(
      java.util.function.BiConsumer<org.deluge.model.ProjectModel, java.io.File> l) {
    this.onSongLoaded = l;
  }

  public java.util.function.BiConsumer<org.deluge.model.ProjectModel, java.io.File>
      getOnSongLoaded() {
    return onSongLoaded;
  }

  public void setOnTrackAdded(java.util.function.Consumer<org.deluge.model.TrackModel> l) {
    this.onTrackAdded = l;
  }

  public void setOnPatternLoad(java.util.function.Consumer<java.io.File> l) {
    this.onPatternLoad = l;
  }

  public void setOnPatternSave(Runnable r) {
    this.onPatternSave = r;
  }

  public void updateFocusTrack(int trackId) {
    // Shield
  }
}
