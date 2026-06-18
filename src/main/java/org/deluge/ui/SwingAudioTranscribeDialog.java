package org.deluge.ui;

import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import org.deluge.model.ProjectModel;

/**
 * A premium, asynchronous Audio-to-MIDI transcription console. Runs Spotify's basic-pitch model in
 * the background, streams log outputs in real-time to a dark glowing terminal panel, and
 * automatically hands off the result to the MIDI Import Wizard.
 */
public class SwingAudioTranscribeDialog extends JDialog {
  private static final Logger LOG = Logger.getLogger(SwingAudioTranscribeDialog.class.getName());

  private final Color BG_DARK = new Color(0x12, 0x12, 0x14);
  private final Color PANEL_DARK = new Color(0x1a, 0x1a, 0x1c);
  private final Color GLOW_CYAN = new Color(0x00, 0xff, 0xcc);
  private final Color GLOW_RED = new Color(0xff, 0x55, 0x55);
  private final Color TEXT_WHITE = Color.WHITE;
  private final Color TEXT_MUTED = new Color(0x88, 0x88, 0x90);

  private final File audioFile;
  private final Frame owner;

  private JTextArea consoleArea;
  private JProgressBar progressBar;
  private JButton startBtn;
  private JButton closeBtn;

  private boolean transcriptionSuccessful = false;
  private File generatedMidiFile = null;
  private ProjectModel compiledProject = null;

  public SwingAudioTranscribeDialog(Frame owner, File audioFile) {
    super(owner, "Audio Transcriber (Neural Audio-to-MIDI)", true);
    this.owner = owner;
    this.audioFile = audioFile;
    initializeUI();
  }

  private void initializeUI() {
    setSize(720, 480);
    setLocationRelativeTo(owner);
    getContentPane().setBackground(BG_DARK);
    setLayout(new BorderLayout(10, 10));

    // ── Header Banner ────────────────────────────────────────────────────────
    JPanel headerPanel = new JPanel(new BorderLayout());
    headerPanel.setBackground(PANEL_DARK);
    headerPanel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x2a, 0x2a, 0x30)),
            new EmptyBorder(12, 16, 12, 16)));

    JLabel titleLabel = new JLabel("NEURAL AUDIO TO MIDI TRANSCRIBER");
    titleLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
    titleLabel.setForeground(GLOW_CYAN);

    JLabel subLabel =
        new JLabel(
            "Transcribing: "
                + audioFile.getName()
                + "  ("
                + (audioFile.length() / (1024 * 1024))
                + " MB)");
    subLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
    subLabel.setForeground(TEXT_MUTED);

    JPanel titleTextPanel = new JPanel(new GridLayout(2, 1, 2, 2));
    titleTextPanel.setOpaque(false);
    titleTextPanel.add(titleLabel);
    titleTextPanel.add(subLabel);

    headerPanel.add(titleTextPanel, BorderLayout.WEST);
    add(headerPanel, BorderLayout.NORTH);

    // ── Center Console Panel ─────────────────────────────────────────────────
    consoleArea = new JTextArea();
    consoleArea.setBackground(new Color(0x0a, 0x0a, 0x0c));
    consoleArea.setForeground(new Color(0xaa, 0xff, 0xdd)); // Neon green glow
    consoleArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
    consoleArea.setEditable(false);
    consoleArea.setMargin(new Insets(10, 10, 10, 10));

    JScrollPane scrollPane = new JScrollPane(consoleArea);
    scrollPane.setBorder(BorderFactory.createLineBorder(new Color(0x2a, 0x2a, 0x30), 1));
    scrollPane.setBackground(BG_DARK);
    add(scrollPane, BorderLayout.CENTER);

    // ── Bottom Control Deck ──────────────────────────────────────────────────
    JPanel bottomPanel = new JPanel(new BorderLayout(10, 10));
    bottomPanel.setBackground(PANEL_DARK);
    bottomPanel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x2a, 0x2a, 0x30)),
            new EmptyBorder(12, 16, 12, 16)));

    progressBar = new JProgressBar();
    progressBar.setForeground(GLOW_CYAN);
    progressBar.setBackground(BG_DARK);
    progressBar.setBorderPainted(false);
    progressBar.setPreferredSize(new Dimension(300, 8));
    bottomPanel.add(progressBar, BorderLayout.CENTER);

    JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
    buttonPanel.setOpaque(false);

    closeBtn = new JButton("CLOSE");
    styleButton(closeBtn, new Color(0x2a, 0x2a, 0x32), TEXT_WHITE);
    closeBtn.addActionListener(e -> dispose());

    startBtn = new JButton("START TRANSCRIPTION");
    styleButton(startBtn, new Color(0x1a, 0x3a, 0x2a), GLOW_CYAN);
    startBtn.addActionListener(e -> startTranscriptionTask());

    buttonPanel.add(closeBtn);
    buttonPanel.add(startBtn);
    bottomPanel.add(buttonPanel, BorderLayout.EAST);

    add(bottomPanel, BorderLayout.SOUTH);

    // Initial console welcome message
    consoleArea.append("SYSTEM READY.\n");
    consoleArea.append("Ready to transcribe audio using Spotify's basic-pitch model.\n");
    consoleArea.append("Click 'START TRANSCRIPTION' to begin...\n");
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

  private void startTranscriptionTask() {
    startBtn.setEnabled(false);
    closeBtn.setEnabled(false);
    progressBar.setIndeterminate(true);
    consoleArea.setText("");
    consoleArea.append("[SYSTEM] Starting neural audio transcription...\n");

    // Launch background thread
    new Thread(this::runTranscription).start();
  }

  private void runTranscription() {
    try {
      // 1. Determine output directory (we use target/temp_midi/)
      Path tempDir = Path.of("target", "temp_midi");
      Files.createDirectories(tempDir);

      // 2. Locate basic-pitch CLI
      String[] command = findTranscriptionCommand(tempDir.toString());
      if (command == null) {
        SwingUtilities.invokeLater(
            () -> {
              progressBar.setIndeterminate(false);
              consoleArea.setForeground(GLOW_RED);
              consoleArea.append(
                  "\n[ERROR] Python or basic-pitch library not found on your system!\n");
              consoleArea.append("To resolve this, please install Python 3 and basic-pitch:\n");
              consoleArea.append("  pip install basic-pitch\n");
              closeBtn.setEnabled(true);
            });
        return;
      }

      // 3. Launch ProcessBuilder
      ProcessBuilder pb = new ProcessBuilder(command);
      pb.redirectErrorStream(true);
      Process process = pb.start();

      // Read output stream in real-time
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          final String logLine = line;
          SwingUtilities.invokeLater(
              () -> {
                consoleArea.append(logLine + "\n");
                consoleArea.setCaretPosition(consoleArea.getDocument().getLength());
              });
        }
      }

      int exitCode = process.waitFor();
      if (exitCode == 0) {
        // 4. Locate generated MIDI file
        String baseName = audioFile.getName();
        int dot = baseName.lastIndexOf('.');
        if (dot > 0) baseName = baseName.substring(0, dot);

        File expectedMidi = tempDir.resolve(baseName + "_basic_pitch.mid").toFile();
        if (expectedMidi.exists()) {
          generatedMidiFile = expectedMidi;
          transcriptionSuccessful = true;
          SwingUtilities.invokeLater(this::handleSuccessfulTranscription);
        } else {
          throw new Exception("Expected MIDI file not found: " + expectedMidi.getAbsolutePath());
        }
      } else {
        throw new Exception("Transcription process exited with non-zero code: " + exitCode);
      }

    } catch (Exception e) {
      final String errMsg = e.getMessage();
      SwingUtilities.invokeLater(
          () -> {
            progressBar.setIndeterminate(false);
            consoleArea.setForeground(GLOW_RED);
            consoleArea.append("\n[ERROR] Transcription failed: " + errMsg + "\n");
            startBtn.setEnabled(true);
            closeBtn.setEnabled(true);
          });
    }
  }

  private String[] findTranscriptionCommand(String outDir) {
    // Stage 1: Try direct basic-pitch command
    if (testCommand(new String[] {"basic-pitch", "--help"})) {
      return new String[] {"basic-pitch", outDir, audioFile.getAbsolutePath()};
    }
    // Stage 2: Try python3 module call
    if (testCommand(new String[] {"python3", "-c", "import basic_pitch"})) {
      return new String[] {"python3", "-m", "basic_pitch.cli", outDir, audioFile.getAbsolutePath()};
    }
    // Stage 3: Try python module call
    if (testCommand(new String[] {"python", "-c", "import basic_pitch"})) {
      return new String[] {"python", "-m", "basic_pitch.cli", outDir, audioFile.getAbsolutePath()};
    }
    return null; // Not found
  }

  private boolean testCommand(String[] cmd) {
    try {
      Process p = new ProcessBuilder(cmd).start();
      p.waitFor();
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private void handleSuccessfulTranscription() {
    progressBar.setIndeterminate(false);
    progressBar.setValue(100);
    consoleArea.append("\n[SYSTEM] Transcription completed successfully!\n");
    consoleArea.append("Handing off to MIDI Import Wizard...\n");

    // Close this dialog
    dispose();

    // Instantly launch the MIDI Import Wizard JDialog with the transcribed MIDI file!
    SwingMidiImportDialog wizard = new SwingMidiImportDialog(owner, generatedMidiFile);
    wizard.setVisible(true);

    if (wizard.isImportSuccessful() && wizard.getCompiledProject() != null) {
      compiledProject = wizard.getCompiledProject();
    }
  }

  public boolean isTranscriptionSuccessful() {
    return transcriptionSuccessful;
  }

  public ProjectModel getCompiledProject() {
    return compiledProject;
  }
}
