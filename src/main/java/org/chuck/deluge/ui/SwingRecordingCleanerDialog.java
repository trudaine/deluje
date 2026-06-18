package org.chuck.deluge.ui;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import org.chuck.deluge.cleanup.DelugeRecordingCleaner;
import org.chuck.deluge.cleanup.DelugeRecordingCleaner.ScanResult;
import org.chuck.deluge.project.PreferencesManager;

/**
 * A premium, interactive GUI dashboard for auditing and cleaning unused WAV recordings from the
 * Deluge SD library. Includes real-time audio auditioning, native file system revealing, and batch
 * quarantine/deletion.
 */
public class SwingRecordingCleanerDialog extends JDialog {

  private final Color BG_DARK = new Color(0x12, 0x12, 0x14);
  private final Color PANEL_DARK = new Color(0x1a, 0x1a, 0x1c);
  private final Color GLOW_CYAN = new Color(0x00, 0xff, 0xcc);
  private final Color GLOW_GOLD = new Color(0xff, 0xb3, 0x00);
  private final Color TEXT_WHITE = Color.WHITE;
  private final Color TEXT_MUTED = new Color(0x88, 0x88, 0x90);

  private ScanResult currentScan;
  private JList<File> fileList;
  private DefaultListModel<File> listModel;

  // Details Panel components
  private JLabel nameLabel;
  private JLabel pathLabel;
  private JLabel sizeLabel;
  private JLabel dateLabel;
  private JButton playBtn;
  private JButton stopBtn;
  private JButton keepBtn;
  private JButton deleteBtn;
  private JButton revealBtn;

  // Bottom Panel components
  private JButton quarantineAllBtn;
  private JButton deleteAllBtn;
  private JLabel statusLabel;
  private JProgressBar progressBar;

  // Audio Playback State
  private Clip activeClip = null;
  private File playingFile = null;

  public SwingRecordingCleanerDialog(Frame owner) {
    super(owner, "Orphaned Recording Cleaner", true);
    initializeUI();
    performScan();
  }

  private void initializeUI() {
    setSize(850, 580);
    setLocationRelativeTo(getOwner());
    getContentPane().setBackground(BG_DARK);
    setLayout(new BorderLayout(10, 10));

    // ── Header Banner ────────────────────────────────────────────────────────
    JPanel headerPanel = new JPanel(new BorderLayout());
    headerPanel.setBackground(PANEL_DARK);
    headerPanel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x2a, 0x2a, 0x30)),
            new EmptyBorder(12, 16, 12, 16)));

    JLabel titleLabel = new JLabel("ORPHANED RECORDINGS AUDITOR");
    titleLabel.setFont(new Font("SansSerif", Font.BOLD, 15));
    titleLabel.setForeground(GLOW_CYAN);

    JLabel subLabel =
        new JLabel("Library Root: " + PreferencesManager.getLibraryDir().getAbsolutePath());
    subLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
    subLabel.setForeground(TEXT_MUTED);

    JPanel titleTextPanel = new JPanel(new GridLayout(2, 1, 2, 2));
    titleTextPanel.setOpaque(false);
    titleTextPanel.add(titleLabel);
    titleTextPanel.add(subLabel);

    JButton rescanBtn = new JButton("🔄 RE-SCAN");
    styleButton(rescanBtn, new Color(0x2d, 0x2d, 0x35), TEXT_WHITE);
    rescanBtn.addActionListener(e -> performScan());

    headerPanel.add(titleTextPanel, BorderLayout.WEST);
    headerPanel.add(rescanBtn, BorderLayout.EAST);
    add(headerPanel, BorderLayout.NORTH);

    // ── Split Pane Body ──────────────────────────────────────────────────────
    JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
    splitPane.setOpaque(false);
    splitPane.setDividerLocation(380);
    splitPane.setBorder(new EmptyBorder(0, 10, 0, 10));
    splitPane.setDividerSize(6);

    // Left Deck: Scrollable List of files
    listModel = new DefaultListModel<>();
    fileList = new JList<>(listModel);
    fileList.setBackground(BG_DARK);
    fileList.setForeground(TEXT_WHITE);
    fileList.setSelectionBackground(new Color(0x2a, 0x2a, 0x32));
    fileList.setSelectionForeground(GLOW_CYAN);
    fileList.setCellRenderer(new FileCellRenderer());
    fileList.addListSelectionListener(
        e -> {
          if (!e.getValueIsAdjusting()) {
            updateDetailsPanel(fileList.getSelectedValue());
          }
        });

    JScrollPane listScrollPane = new JScrollPane(fileList);
    listScrollPane.setBorder(BorderFactory.createLineBorder(new Color(0x2a, 0x2a, 0x30), 1));
    listScrollPane.setBackground(BG_DARK);
    listScrollPane.getViewport().setBackground(BG_DARK);

    JPanel leftPanel = new JPanel(new BorderLayout(5, 5));
    leftPanel.setOpaque(false);
    JLabel listHeader = new JLabel(" UNUSED RECORDINGS (SAMPLES/RECORD)");
    listHeader.setFont(new Font("SansSerif", Font.BOLD, 10));
    listHeader.setForeground(TEXT_MUTED);
    leftPanel.add(listHeader, BorderLayout.NORTH);
    leftPanel.add(listScrollPane, BorderLayout.CENTER);

    // Right Deck: Audition & Actions
    JPanel rightPanel = new JPanel(new BorderLayout(10, 10));
    rightPanel.setBackground(PANEL_DARK);
    rightPanel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0x2a, 0x2a, 0x30), 1),
            new EmptyBorder(16, 20, 16, 20)));

    // Details/Metadata Card
    JPanel detailsCard = new JPanel(new GridBagLayout());
    detailsCard.setOpaque(false);
    GridBagConstraints gbc = new GridBagConstraints();
    gbc.fill = GridBagConstraints.HORIZONTAL;
    gbc.anchor = GridBagConstraints.NORTHWEST;
    gbc.weightx = 1.0;
    gbc.gridx = 0;
    gbc.gridy = 0;

    nameLabel = new JLabel("No File Selected");
    nameLabel.setFont(new Font("SansSerif", Font.BOLD, 18));
    nameLabel.setForeground(GLOW_CYAN);
    detailsCard.add(nameLabel, gbc);

    gbc.gridy++;
    gbc.insets = new Insets(10, 0, 0, 0);
    pathLabel = new JLabel("Select a recording from the list to audition and clean.");
    pathLabel.setFont(new Font("Monospaced", Font.PLAIN, 11));
    pathLabel.setForeground(TEXT_MUTED);
    detailsCard.add(pathLabel, gbc);

    gbc.gridy++;
    gbc.insets = new Insets(15, 0, 0, 0);
    sizeLabel = new JLabel("Size: --");
    sizeLabel.setFont(new Font("SansSerif", Font.PLAIN, 13));
    sizeLabel.setForeground(TEXT_WHITE);
    detailsCard.add(sizeLabel, gbc);

    gbc.gridy++;
    gbc.insets = new Insets(5, 0, 0, 0);
    dateLabel = new JLabel("Modified: --");
    dateLabel.setFont(new Font("SansSerif", Font.PLAIN, 13));
    dateLabel.setForeground(TEXT_WHITE);
    detailsCard.add(dateLabel, gbc);

    // Audition Player Controls
    JPanel playerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
    playerPanel.setOpaque(false);
    playerPanel.setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(0x3a, 0x3a, 0x42), 1),
            "AUDITION PLAYER",
            0,
            0,
            new Font("SansSerif", Font.BOLD, 9),
            TEXT_MUTED));
    playerPanel.setPreferredSize(new Dimension(350, 70));

    playBtn = new JButton("▶ PLAY");
    styleButton(playBtn, new Color(0x1a, 0x3a, 0x2a), new Color(0x88, 0xff, 0x88));
    playBtn.setEnabled(false);
    playBtn.addActionListener(e -> playActiveFile());

    stopBtn = new JButton("■ STOP");
    styleButton(stopBtn, new Color(0x3a, 0x1a, 0x1a), new Color(0xff, 0x88, 0x88));
    stopBtn.setEnabled(false);
    stopBtn.addActionListener(e -> stopActiveFile());

    playerPanel.add(playBtn);
    playerPanel.add(stopBtn);

    gbc.gridy++;
    gbc.insets = new Insets(25, 0, 0, 0);
    detailsCard.add(playerPanel, gbc);

    // Individual File Action Buttons
    JPanel fileActionsPanel = new JPanel(new GridLayout(3, 1, 0, 10));
    fileActionsPanel.setOpaque(false);

    keepBtn = new JButton("⚙ KEEP / SAVE TO LIBRARY");
    styleButton(keepBtn, new Color(0x2a, 0x2d, 0x3d), GLOW_CYAN);
    keepBtn.setEnabled(false);
    keepBtn.setToolTipText(
        "Moves the file to SAMPLES/SAVED so it's kept safe and no longer marked as an orphaned recording");
    keepBtn.addActionListener(e -> keepSelectedFile());

    deleteBtn = new JButton("🗑 DELETE PERMANENTLY");
    styleButton(deleteBtn, new Color(0x3a, 0x1c, 0x1c), new Color(0xff, 0x55, 0x55));
    deleteBtn.setEnabled(false);
    deleteBtn.addActionListener(e -> deleteSelectedFile());

    revealBtn = new JButton("📂 REVEAL IN FINDER");
    styleButton(revealBtn, new Color(0x2a, 0x2a, 0x32), TEXT_WHITE);
    revealBtn.setEnabled(false);
    revealBtn.addActionListener(e -> revealSelectedFile());

    fileActionsPanel.add(keepBtn);
    fileActionsPanel.add(revealBtn);
    fileActionsPanel.add(deleteBtn);

    rightPanel.add(detailsCard, BorderLayout.CENTER);
    rightPanel.add(fileActionsPanel, BorderLayout.SOUTH);

    splitPane.setLeftComponent(leftPanel);
    splitPane.setRightComponent(rightPanel);
    add(splitPane, BorderLayout.CENTER);

    // ── Bottom Panel (Batch Actions & Progress) ──────────────────────────────
    JPanel bottomPanel = new JPanel(new BorderLayout(10, 5));
    bottomPanel.setBackground(PANEL_DARK);
    bottomPanel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x2a, 0x2a, 0x30)),
            new EmptyBorder(12, 16, 12, 16)));

    JPanel statusContainer = new JPanel(new BorderLayout(5, 5));
    statusContainer.setOpaque(false);

    statusLabel = new JLabel("Scanner idle.");
    statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
    statusLabel.setForeground(TEXT_WHITE);

    progressBar = new JProgressBar(0, 100);
    progressBar.setPreferredSize(new Dimension(300, 14));
    progressBar.setBackground(BG_DARK);
    progressBar.setForeground(GLOW_CYAN);
    progressBar.setBorder(BorderFactory.createLineBorder(new Color(0x2a, 0x2a, 0x30), 1));
    progressBar.setStringPainted(false);
    progressBar.setVisible(false);

    statusContainer.add(statusLabel, BorderLayout.CENTER);
    statusContainer.add(progressBar, BorderLayout.SOUTH);

    JPanel batchButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
    batchButtons.setOpaque(false);

    quarantineAllBtn = new JButton("📦 QUARANTINE ALL UNUSED");
    styleButton(quarantineAllBtn, new Color(0x1a, 0x35, 0x3a), GLOW_CYAN);
    quarantineAllBtn.setEnabled(false);
    quarantineAllBtn.setToolTipText(
        "Moves all unused recordings into SAMPLES/UNUSED RECORDINGS folder safely");
    quarantineAllBtn.addActionListener(e -> quarantineAllUnused());

    deleteAllBtn = new JButton("🗑 DELETE ALL UNUSED");
    styleButton(deleteAllBtn, new Color(0x3e, 0x15, 0x15), new Color(0xff, 0x55, 0x55));
    deleteAllBtn.setEnabled(false);
    deleteAllBtn.setToolTipText(
        "Permanently deletes all unused recordings to free up SD card space instantly");
    deleteAllBtn.addActionListener(e -> deleteAllUnused());

    batchButtons.add(quarantineAllBtn);
    batchButtons.add(deleteAllBtn);

    bottomPanel.add(statusContainer, BorderLayout.WEST);
    bottomPanel.add(batchButtons, BorderLayout.EAST);
    add(bottomPanel, BorderLayout.SOUTH);

    // Stop playback on window close to avoid leaks!
    addWindowListener(
        new java.awt.event.WindowAdapter() {
          @Override
          public void windowClosing(java.awt.event.WindowEvent e) {
            stopActiveFile();
          }
        });
  }

  private void styleButton(JButton btn, Color bg, Color fg) {
    btn.setFocusable(false);
    btn.setBackground(bg);
    btn.setForeground(fg);
    btn.setFont(new Font("SansSerif", Font.BOLD, 11));
    btn.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(bg.brighter(), 1),
            BorderFactory.createEmptyBorder(6, 12, 6, 12)));
    btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
  }

  // ── Operations ────────────────────────────────────────────────────────────

  private void performScan() {
    stopActiveFile();
    statusLabel.setText("Scanning Deluge library...");
    progressBar.setVisible(true);
    progressBar.setIndeterminate(true);
    rescaleUI(false);

    SwingWorker<ScanResult, Void> worker =
        new SwingWorker<>() {
          @Override
          protected ScanResult doInBackground() {
            return DelugeRecordingCleaner.scanLibrary();
          }

          @Override
          protected void done() {
            try {
              currentScan = get();
              listModel.clear();
              for (File f : currentScan.unusedRecordings) {
                listModel.addElement(f);
              }

              progressBar.setVisible(false);
              progressBar.setIndeterminate(false);

              if (currentScan.unusedRecordings.isEmpty()) {
                statusLabel.setText(
                    "Scan complete: No unused recordings found! Your library is 100% clean.");
                rescaleUI(false);
              } else {
                statusLabel.setText(
                    String.format(
                        "Scan complete: Found %d unused recordings.",
                        currentScan.unusedRecordings.size()));
                rescaleUI(true);
              }

              // Check if there are any parsing errors
              if (!currentScan.problemFiles.isEmpty()) {
                JOptionPane.showMessageDialog(
                    SwingRecordingCleanerDialog.this,
                    String.format(
                        "The scanner found %d older XML files it could not parse.\n"
                            + "These files were skipped to prevent accidental deletion.\n"
                            + "Please check the console logs for details.",
                        currentScan.problemFiles.size()),
                    "Warning: Parsing Issues",
                    JOptionPane.WARNING_MESSAGE);
              }

            } catch (Exception e) {
              statusLabel.setText("Scan failed: " + e.getMessage());
              progressBar.setVisible(false);
              rescaleUI(false);
            }
          }
        };
    worker.execute();
  }

  private void rescaleUI(boolean hasUnused) {
    quarantineAllBtn.setEnabled(hasUnused);
    deleteAllBtn.setEnabled(hasUnused);
    if (!hasUnused) {
      updateDetailsPanel(null);
    }
  }

  private void updateDetailsPanel(File file) {
    stopActiveFile();

    if (file == null) {
      nameLabel.setText("No File Selected");
      pathLabel.setText("Select a recording from the list to audition and clean.");
      sizeLabel.setText("Size: --");
      dateLabel.setText("Modified: --");
      playBtn.setEnabled(false);
      stopBtn.setEnabled(false);
      keepBtn.setEnabled(false);
      deleteBtn.setEnabled(false);
      revealBtn.setEnabled(false);
      return;
    }

    nameLabel.setText(file.getName());
    // Truncate path if too long
    String fullPath = file.getAbsolutePath();
    String displayPath =
        fullPath.length() > 60 ? "..." + fullPath.substring(fullPath.length() - 57) : fullPath;
    pathLabel.setText(displayPath);
    pathLabel.setToolTipText(fullPath);

    // Format size
    long bytes = file.length();
    String sizeStr =
        bytes > 1024 * 1024
            ? String.format("%.2f MB", (double) bytes / (1024 * 1024))
            : String.format("%.1f KB", (double) bytes / 1024);
    sizeLabel.setText("Size: " + sizeStr);

    // Format date
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    dateLabel.setText("Modified: " + sdf.format(new Date(file.lastModified())));

    playBtn.setEnabled(true);
    stopBtn.setEnabled(false);
    keepBtn.setEnabled(true);
    deleteBtn.setEnabled(true);
    revealBtn.setEnabled(true);
  }

  // ── Audio Auditioning Engine ──────────────────────────────────────────────

  private void playActiveFile() {
    File selected = fileList.getSelectedValue();
    if (selected == null || !selected.exists()) return;

    stopActiveFile();
    playingFile = selected;

    try {
      AudioInputStream stream = AudioSystem.getAudioInputStream(selected);
      activeClip = AudioSystem.getClip();
      activeClip.open(stream);
      activeClip.addLineListener(
          event -> {
            if (event.getType() == LineEvent.Type.STOP) {
              SwingUtilities.invokeLater(this::resetPlayerButtons);
            }
          });
      activeClip.start();

      playBtn.setEnabled(false);
      playBtn.setText("🔊 PLAYING");
      stopBtn.setEnabled(true);

    } catch (Exception e) {
      JOptionPane.showMessageDialog(
          this,
          "Failed to play sample: "
              + e.getMessage()
              + "\n(Note: Deluge records 16-bit or 24-bit PCM WAV. 24-bit requires Java audio engine setup.)",
          "Playback Error",
          JOptionPane.ERROR_MESSAGE);
      resetPlayerButtons();
    }
  }

  private void stopActiveFile() {
    if (activeClip != null) {
      try {
        activeClip.stop();
        activeClip.close();
      } catch (Exception ignored) {
      }
      activeClip = null;
    }
    playingFile = null;
    resetPlayerButtons();
  }

  private void resetPlayerButtons() {
    playBtn.setText("▶ PLAY");
    playBtn.setEnabled(fileList.getSelectedValue() != null);
    stopBtn.setEnabled(false);
  }

  // ── File System Actions ───────────────────────────────────────────────────

  private void keepSelectedFile() {
    File selected = fileList.getSelectedValue();
    if (selected == null) return;

    stopActiveFile();
    boolean success = DelugeRecordingCleaner.saveFile(selected);
    if (success) {
      statusLabel.setText("Saved " + selected.getName() + " permanently to SAMPLES/SAVED.");
      listModel.removeElement(selected);
      currentScan.unusedRecordings.remove(selected);
      rescaleUI(!listModel.isEmpty());
    } else {
      JOptionPane.showMessageDialog(
          this,
          "Failed to save file. Check directory permissions.",
          "Error",
          JOptionPane.ERROR_MESSAGE);
    }
  }

  private void deleteSelectedFile() {
    File selected = fileList.getSelectedValue();
    if (selected == null) return;

    int confirm =
        JOptionPane.showConfirmDialog(
            this,
            "Are you absolutely sure you want to permanently delete "
                + selected.getName()
                + "?\nThis action CANNOT be undone!",
            "Confirm Permanent Deletion",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);

    if (confirm == JOptionPane.YES_OPTION) {
      stopActiveFile();
      int success = DelugeRecordingCleaner.deleteFiles(List.of(selected));
      if (success > 0) {
        statusLabel.setText("Permanently deleted " + selected.getName());
        listModel.removeElement(selected);
        currentScan.unusedRecordings.remove(selected);
        rescaleUI(!listModel.isEmpty());
      } else {
        JOptionPane.showMessageDialog(
            this, "Failed to delete file.", "Error", JOptionPane.ERROR_MESSAGE);
      }
    }
  }

  private void revealSelectedFile() {
    File selected = fileList.getSelectedValue();
    if (selected == null) return;
    try {
      Desktop.getDesktop().open(selected.getParentFile());
    } catch (IOException e) {
      JOptionPane.showMessageDialog(
          this, "Failed to open folder: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
    }
  }

  private void quarantineAllUnused() {
    if (currentScan == null || currentScan.unusedRecordings.isEmpty()) return;

    int confirm =
        JOptionPane.showConfirmDialog(
            this,
            String.format(
                "Do you want to quarantine all %d unused recordings?\n"
                    + "This will move them safely into: SAMPLES/UNUSED RECORDINGS/\n"
                    + "You can recover or delete them manually from there later.",
                currentScan.unusedRecordings.size()),
            "Confirm Batch Quarantine",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE);

    if (confirm == JOptionPane.YES_OPTION) {
      stopActiveFile();
      int count = DelugeRecordingCleaner.quarantineFiles(currentScan.unusedRecordings);
      JOptionPane.showMessageDialog(
          this,
          String.format("Successfully quarantined %d files!", count),
          "Batch Quarantine Complete",
          JOptionPane.INFORMATION_MESSAGE);
      performScan();
    }
  }

  private void deleteAllUnused() {
    if (currentScan == null || currentScan.unusedRecordings.isEmpty()) return;

    int confirm =
        JOptionPane.showConfirmDialog(
            this,
            String.format(
                "🚨 CRITICAL WARNING 🚨\n\n"
                    + "You are about to PERMANENTLY DELETE all %d unused recordings from disk!\n"
                    + "This will free up SD card space instantly, but CANNOT be undone.\n\n"
                    + "Are you absolutely sure you want to proceed?",
                currentScan.unusedRecordings.size()),
            "🚨 PERMANENT BATCH DELETION WARNING 🚨",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.ERROR_MESSAGE);

    if (confirm == JOptionPane.YES_OPTION) {
      stopActiveFile();
      int count = DelugeRecordingCleaner.deleteFiles(currentScan.unusedRecordings);
      JOptionPane.showMessageDialog(
          this,
          String.format("Permanently deleted %d files from disk!", count),
          "Batch Deletion Complete",
          JOptionPane.INFORMATION_MESSAGE);
      performScan();
    }
  }

  // ── Custom Cell Renderer for Premium Card Layout ────────────────────────

  private class FileCellRenderer extends DefaultListCellRenderer {
    @Override
    public Component getListCellRendererComponent(
        JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
      JPanel card = new JPanel(new BorderLayout(8, 4));
      card.setBorder(new EmptyBorder(8, 12, 8, 12));

      if (isSelected) {
        card.setBackground(new Color(0x2a, 0x2a, 0x32));
      } else {
        card.setBackground(index % 2 == 0 ? BG_DARK : new Color(0x16, 0x16, 0x18));
      }

      File file = (File) value;

      JLabel iconLabel = new JLabel("🎵");
      iconLabel.setFont(new Font("SansSerif", Font.PLAIN, 18));
      iconLabel.setForeground(isSelected ? GLOW_CYAN : TEXT_MUTED);

      JLabel nameLbl = new JLabel(file.getName());
      nameLbl.setFont(new Font("SansSerif", Font.BOLD, 12));
      nameLbl.setForeground(isSelected ? GLOW_CYAN : TEXT_WHITE);

      // Meta text: size + modified date
      long bytes = file.length();
      String sizeStr =
          bytes > 1024 * 1024
              ? String.format("%.2f MB", (double) bytes / (1024 * 1024))
              : String.format("%.1f KB", (double) bytes / 1024);

      SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
      String metaText = sizeStr + "  •  " + sdf.format(new Date(file.lastModified()));

      JLabel metaLbl = new JLabel(metaText);
      metaLbl.setFont(new Font("SansSerif", Font.PLAIN, 10));
      metaLbl.setForeground(TEXT_MUTED);

      JPanel textPanel = new JPanel(new GridLayout(2, 1, 2, 2));
      textPanel.setOpaque(false);
      textPanel.add(nameLbl);
      textPanel.add(metaLbl);

      card.add(iconLabel, BorderLayout.WEST);
      card.add(textPanel, BorderLayout.CENTER);

      return card;
    }
  }
}
