package org.deluge.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.HeadlessException;
import java.awt.Insets;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import org.deluge.BridgeContract;
import org.deluge.ableton.AbletonProjectManager;
import org.deluge.ableton.AbletonTrackMapper;
import org.deluge.engine.FirmwareFactory;
import org.deluge.kit.KitAssembler;
import org.deluge.model.AudioTrackModel;
import org.deluge.model.ClipModel;
import org.deluge.model.Consequence;
import org.deluge.model.KitTrackModel;
import org.deluge.model.PatternModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.model.TrackModel;
import org.deluge.project.ExportHelper;
import org.deluge.project.KitSynthSerializer;
import org.deluge.project.PatternSerializer;
import org.deluge.project.PreferencesManager;
import org.deluge.project.ProjectSerializer;
import org.deluge.project.SaveNameSuggester;
import org.deluge.storage.audio.AudioFileReader;
import org.w3c.dom.Document;

/**
 * Controller class coordinating Deluge XML project saving, loading, audio/MIDI stem exports, custom
 * ChUCk scripting, and Ableton project importing.
 */
public class FileMenuController {
  private final SwingDelugeApp app;
  private final BridgeContract bridge;
  private volatile boolean exportInProgress = false;

  public FileMenuController(SwingDelugeApp app) {
    this.app = app;
    this.bridge = app.bridge;
  }

  public void saveProject(boolean forceChooser) {
    File songsDir = PreferencesManager.getSongsDir();
    File suggestedFile = SaveNameSuggester.suggestNextSaveFile(songsDir, app.currentProjectFile);

    File target = (suggestedFile != null) ? suggestedFile : app.currentProjectFile;

    if (app.currentProjectFile == null || forceChooser) {
      JFileChooser chooser = new JFileChooser(songsDir);
      chooser.setFileFilter(
          new javax.swing.filechooser.FileNameExtensionFilter("Song XML", "xml", "XML"));

      if (target != null) {
        chooser.setSelectedFile(target);
      }

      if (chooser.showSaveDialog(app) != JFileChooser.APPROVE_OPTION) return;
      target = chooser.getSelectedFile();
      if (!target.getName().toLowerCase().endsWith(".xml")) {
        target = new File(target.getAbsolutePath() + ".xml");
      }
    }
    try {
      app.pushModelToBridge();
      ProjectSerializer.save(app.currentProject, target);
      app.currentProjectFile = target;
      app.setTitle("DELUGE WORKSTATION — " + target.getName());
      if (app.sidebarPanel != null) {
        app.sidebarPanel.reloadLibrary();
      }
      if (app.getTopBar() != null) {
        app.getTopBar().setSaved(true);
      }
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(
          app, "Save failed:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
    }
  }

  public void loadProjectWithProgress(final File file, final boolean isAbleton) {
    if (file == null) return;

    final JDialog progressDialog =
        new JDialog(app, isAbleton ? "Importing Ableton Live Set" : "Loading Deluge Project", true);
    progressDialog.setUndecorated(true);
    progressDialog.setSize(420, 110);
    progressDialog.setLocationRelativeTo(app);

    JPanel panel = new JPanel(new BorderLayout(12, 12));
    panel.setBackground(new Color(0x18, 0x18, 0x1a));
    panel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x34), 1),
            BorderFactory.createEmptyBorder(16, 16, 16, 16)));

    final JLabel label =
        new JLabel(
            isAbleton ? "Parsing Ableton Live Set..." : "Parsing Deluge Project XML...",
            JLabel.CENTER);
    label.setForeground(new Color(0xaa, 0xbb, 0xcc));
    label.setFont(new Font("SansSerif", Font.PLAIN, 12));

    final JProgressBar progressBar = new JProgressBar(0, 100);
    progressBar.setIndeterminate(true);
    progressBar.setBackground(new Color(0x22, 0x22, 0x25));
    progressBar.setForeground(new Color(0x00, 0xcc, 0xff));
    progressBar.setBorderPainted(false);
    progressBar.setPreferredSize(new Dimension(380, 6));

    panel.add(label, BorderLayout.CENTER);
    panel.add(progressBar, BorderLayout.SOUTH);
    progressDialog.add(panel);

    SwingWorker<ProjectModel, String> worker =
        new SwingWorker<>() {
          @Override
          protected ProjectModel doInBackground() throws Exception {
            ProjectModel model;
            if (isAbleton) {
              publish("Parsing Ableton Live Set...");
              Document doc = AbletonProjectManager.parseAlsToXml(file);
              model = new ProjectModel();
              AbletonTrackMapper.importAbletonSet(doc, model, file);
            } else {
              publish("Parsing Deluge Project XML...");
              try (FileInputStream fis = new FileInputStream(file)) {
                model = org.deluge.xml.DelugeXmlParser.parseSong(fis, file.getName());
              }
            }

            // Pre-resolve and load all sample paths to cache them in the background thread
            final List<String> samplePaths = new ArrayList<>();
            File sdRoot = PreferencesManager.getLibraryDir();

            for (TrackModel track : model.getTracks()) {
              if (track instanceof AudioTrackModel atm) {
                for (AudioTrackModel.AudioClip clip : atm.getAudioClips()) {
                  String p = clip.getFilePath();
                  if (p != null && !p.isEmpty()) samplePaths.add(p);
                }
              } else if (track instanceof SynthTrackModel stm) {
                String p1 = stm.getOsc1SamplePath();
                if (p1 != null && !p1.isEmpty()) samplePaths.add(p1);
                String p2 = stm.getOsc2SamplePath();
                if (p2 != null && !p2.isEmpty()) samplePaths.add(p2);
              } else if (track instanceof KitTrackModel ktm) {
                for (org.deluge.model.Drum d : ktm.getDrums()) {
                  if (d instanceof org.deluge.model.SoundDrum sd) {
                    String p = sd.getSamplePath();
                    if (p != null && !p.isEmpty()) samplePaths.add(p);
                  }
                }
              }
            }

            if (!samplePaths.isEmpty()) {
              // Switch to determinate progress bar
              SwingUtilities.invokeLater(
                  () -> {
                    progressBar.setIndeterminate(false);
                    progressBar.setMaximum(samplePaths.size());
                    progressBar.setValue(0);
                  });

              for (int i = 0; i < samplePaths.size(); i++) {
                String path = samplePaths.get(i);
                File resolved = FirmwareFactory.resolveSample(path, sdRoot);
                if (resolved != null && resolved.exists()) {
                  publish(
                      "Loading "
                          + resolved.getName()
                          + " ("
                          + (i + 1)
                          + "/"
                          + samplePaths.size()
                          + ")...");
                  try {
                    AudioFileReader.readSample(resolved.getAbsolutePath());
                  } catch (Exception ignored) {
                  }
                }
                final int progressValue = i + 1;
                SwingUtilities.invokeLater(() -> progressBar.setValue(progressValue));
              }
            }

            return model;
          }

          @Override
          protected void process(List<String> chunks) {
            if (!chunks.isEmpty()) {
              String lastMsg = chunks.get(chunks.size() - 1);
              label.setText(lastMsg);
            }
          }

          @Override
          protected void done() {
            progressDialog.dispose();
            try {
              ProjectModel model = get();
              if (isAbleton) {
                app.currentProjectFile = null;
                app.loadProject(model);
                app.setTitle("DELUGE WORKSTATION — [Imported] " + file.getName());
              } else {
                app.currentProjectFile = file;
                app.loadProject(model);
                app.setTitle("DELUGE WORKSTATION — " + file.getName());
              }
            } catch (Exception ex) {
              Throwable cause = (ex.getCause() != null) ? ex.getCause() : ex;
              cause.printStackTrace();
              JOptionPane.showMessageDialog(
                  app,
                  "Failed to "
                      + (isAbleton ? "import" : "load")
                      + " project:\n"
                      + cause.getMessage(),
                  "Error",
                  JOptionPane.ERROR_MESSAGE);
            }
          }
        };

    worker.execute();
    progressDialog.setVisible(true);
  }

  public void exportAudio() {
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("Export Audio");
    chooser.setSelectedFile(new File("deluge_export.wav"));
    if (chooser.showSaveDialog(app) != JFileChooser.APPROVE_OPTION) return;

    String filePath = chooser.getSelectedFile().getAbsolutePath();
    if (!filePath.toLowerCase().endsWith(".wav")) filePath += ".wav";

    bridge.setGlobalString(BridgeContract.G_WVOUT_FILE, filePath);
    bridge.setGlobalFloat(BridgeContract.G_WVOUT_ACTIVE, 1.0f);

    JOptionPane.showMessageDialog(
        app,
        "Export started to:\n" + filePath + "\n\nClick OK to stop export.",
        "Exporting Audio...",
        JOptionPane.INFORMATION_MESSAGE);

    bridge.setGlobalFloat(BridgeContract.G_WVOUT_ACTIVE, 0.0f);
  }

  public void exportWavStems() {
    if (exportInProgress) {
      JOptionPane.showMessageDialog(
          app,
          "An export is already in progress. Please wait until it completes.",
          "Export Busy",
          JOptionPane.WARNING_MESSAGE);
      return;
    }

    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("Select Directory to Export Stems");
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    if (chooser.showSaveDialog(app) != JFileChooser.APPROVE_OPTION) return;

    File targetDir = chooser.getSelectedFile();
    if (!targetDir.exists()) {
      targetDir.mkdirs();
    }

    String input =
        JOptionPane.showInputDialog(
            app, "Enter duration to render in seconds (0 for auto-detect from Arranger):", "0");
    if (input == null) return;

    double duration;
    try {
      duration = Double.parseDouble(input);
    } catch (NumberFormatException e) {
      JOptionPane.showMessageDialog(
          app, "Invalid duration. Using auto-detect.", "Export Stems", JOptionPane.WARNING_MESSAGE);
      duration = 0;
    }

    exportInProgress = true;

    JDialog progressDialog = new JDialog(app, "Exporting WAV Stems...", true);
    progressDialog.setSize(350, 120);
    progressDialog.setLocationRelativeTo(app);
    progressDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
    progressDialog.setLayout(new BorderLayout(10, 10));

    JLabel statusLabel = new JLabel("Preparing export...", JLabel.CENTER);
    JProgressBar progressBar = new JProgressBar(0, 100);
    progressBar.setStringPainted(true);

    JPanel panel = new JPanel(new java.awt.GridLayout(2, 1, 5, 5));
    panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
    panel.add(statusLabel);
    panel.add(progressBar);
    progressDialog.add(panel, BorderLayout.CENTER);

    double finalDuration = duration;

    SwingWorker<Void, String> worker =
        new SwingWorker<>() {
          @Override
          protected Void doInBackground() throws Exception {
            ExportHelper.exportStems(
                app.currentProject,
                targetDir,
                finalDuration,
                (status, percent) -> {
                  publish(status + "|" + percent);
                });
            return null;
          }

          @Override
          protected void process(List<String> chunks) {
            String lastChunk = chunks.get(chunks.size() - 1);
            String[] parts = lastChunk.split("\\|");
            statusLabel.setText(parts[0]);
            progressBar.setValue(Integer.parseInt(parts[1]));
          }

          @Override
          protected void done() {
            exportInProgress = false;
            progressDialog.dispose();
            try {
              get(); // Check for exceptions
              JOptionPane.showMessageDialog(
                  app,
                  "WAV Stems exported successfully to:\n" + targetDir.getAbsolutePath(),
                  "Export Success",
                  JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
              JOptionPane.showMessageDialog(
                  app,
                  "WAV Stems export failed:\n" + ex.getMessage(),
                  "Export Error",
                  JOptionPane.ERROR_MESSAGE);
            }
          }
        };

    worker.execute();
    progressDialog.setVisible(true);
  }

  public void exportMidiFile() {
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("Export MIDI File");
    chooser.setSelectedFile(new File("deluge_export.mid"));
    if (chooser.showSaveDialog(app) != JFileChooser.APPROVE_OPTION) return;

    File targetFile = chooser.getSelectedFile();
    String filePath = targetFile.getAbsolutePath();
    if (!filePath.toLowerCase().endsWith(".mid") && !filePath.toLowerCase().endsWith(".midi")) {
      targetFile = new File(filePath + ".mid");
    }

    try {
      ExportHelper.exportMidi(app.currentProject, targetFile);
      JOptionPane.showMessageDialog(
          app,
          "MIDI file exported successfully to:\n" + targetFile.getAbsolutePath(),
          "Export Success",
          JOptionPane.INFORMATION_MESSAGE);
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(
          app,
          "MIDI export failed:\n" + ex.getMessage(),
          "Export Error",
          JOptionPane.ERROR_MESSAGE);
    }
  }

  public void saveCurrentClipAsPattern() {
    SwingGridPanel active = app.activeGridPanel();
    if (active == null) return;
    int focusTrack = active.getFocusTrack();
    if (focusTrack < 0 || focusTrack >= app.currentProject.getTracks().size()) {
      JOptionPane.showMessageDialog(
          app, "No track selected.", "Save Pattern", JOptionPane.WARNING_MESSAGE);
      return;
    }
    var track = app.currentProject.getTracks().get(focusTrack);
    int clipIdx = track.getActiveClipIndex();
    if (clipIdx < 0 || clipIdx >= track.getClips().size()) {
      JOptionPane.showMessageDialog(
          app, "Active clip not found.", "Save Pattern", JOptionPane.WARNING_MESSAGE);
      return;
    }
    ClipModel clip = track.getClips().get(clipIdx);

    JFileChooser chooser = new JFileChooser(PreferencesManager.getPatternsDir());
    chooser.setDialogTitle("Save Pattern");
    chooser.setFileFilter(
        new javax.swing.filechooser.FileNameExtensionFilter("Pattern XML", "xml", "XML"));
    String suggestedName = track.getName() + "_" + clip.getName() + ".xml";
    chooser.setSelectedFile(new File(suggestedName));
    if (chooser.showSaveDialog(app) != JFileChooser.APPROVE_OPTION) return;

    File target = chooser.getSelectedFile();
    if (!target.getName().toLowerCase().endsWith(".xml")) {
      target = new File(target.getAbsolutePath() + ".xml");
    }

    try {
      PatternModel pattern = new PatternModel(UUID.randomUUID().toString(), clip.getName());
      pattern.setCategory("MELODIC");

      PatternModel.ClipSnapshot snap =
          PatternModel.ClipSnapshot.fromClipModel(clip, focusTrack, track.getName());
      snap.setInstrumentSlot(track.getName());
      snap.setColourHex(track.getColourHex());
      pattern.addClipSnapshot(snap);

      PatternSerializer.save(pattern, target);
      JOptionPane.showMessageDialog(
          app,
          "Pattern saved:\n" + target.getName(),
          "Save Pattern",
          JOptionPane.INFORMATION_MESSAGE);
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(
          app, "Failed to save pattern:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
    }
  }

  public void loadPatternIntoActiveTrack(File patternFile) {
    try {
      PatternModel pattern = PatternSerializer.load(patternFile);
      if (pattern.getClipSnapshots().isEmpty()) {
        JOptionPane.showMessageDialog(
            app, "Pattern file contains no clips.", "Load Pattern", JOptionPane.WARNING_MESSAGE);
        return;
      }

      SwingGridPanel active = app.activeGridPanel();
      int focusTrack = (active != null) ? active.getFocusTrack() : 0;
      if (focusTrack < 0 || focusTrack >= app.currentProject.getTracks().size()) {
        focusTrack = 0;
      }
      var track = app.currentProject.getTracks().get(focusTrack);
      int clipIdx = track.getActiveClipIndex();
      if (clipIdx < 0 || clipIdx >= track.getClips().size()) {
        JOptionPane.showMessageDialog(
            app,
            "Active clip not found on target track.",
            "Load Pattern",
            JOptionPane.WARNING_MESSAGE);
        return;
      }
      ClipModel clip = track.getClips().get(clipIdx);

      // Capture before-snapshot for undo
      var beforeSnapshot =
          PatternModel.ClipSnapshot.fromClipModel(clip, focusTrack, track.getName());

      // Apply the first clip snapshot to the active clip
      pattern.getClipSnapshots().get(0).applyTo(clip);

      // Push undo: re-apply the old snapshot
      var afterSnapshot =
          PatternModel.ClipSnapshot.fromClipModel(clip, focusTrack, track.getName());
      app.currentProject
          .getUndoRedoStack()
          .push(
              new Consequence.CompoundConsequence(
                  "Load pattern",
                  List.of(
                      new Consequence.PatternLoadConsequence(
                          app.currentProject,
                          focusTrack,
                          clipIdx,
                          beforeSnapshot,
                          afterSnapshot))));

      app.pushModelToBridge();
      app.propagateCurrentModel();
      app.refreshGrids();

      JOptionPane.showMessageDialog(
          app,
          "Pattern loaded into: " + track.getName(),
          "Load Pattern",
          JOptionPane.INFORMATION_MESSAGE);
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(
          app, "Failed to load pattern:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
    }
  }

  public void loadChuckScript() {
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("Load ChucK Script");
    javax.swing.filechooser.FileNameExtensionFilter filter =
        new javax.swing.filechooser.FileNameExtensionFilter("ChucK Scripts (*.ck)", "ck");
    chooser.setFileFilter(filter);
    if (chooser.showOpenDialog(app) != JFileChooser.APPROVE_OPTION) return;

    File file = chooser.getSelectedFile();
    try {
      String content = new String(Files.readAllBytes(file.toPath()));
      bridge.eval(content);
      JOptionPane.showMessageDialog(
          app,
          "Script loaded successfully:\n" + file.getName(),
          "Script Loaded",
          JOptionPane.INFORMATION_MESSAGE);
    } catch (HeadlessException | IOException ex) {
      JOptionPane.showMessageDialog(
          app, "Failed to load script:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
    }
  }

  public void assembleKitFromSynths() {
    JFileChooser chooser = new JFileChooser(PreferencesManager.getSongsDir());
    chooser.setDialogTitle("Select Synth Preset XML Files");
    chooser.setMultiSelectionEnabled(true);
    chooser.setFileFilter(
        new javax.swing.filechooser.FileNameExtensionFilter("Synth XML", "xml", "XML"));
    if (chooser.showOpenDialog(app) != JFileChooser.APPROVE_OPTION) return;

    File[] selected = chooser.getSelectedFiles();
    if (selected.length == 0) return;

    // Dialog for configuring each lane
    JPanel configPanel = new JPanel(new GridBagLayout());
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(3, 5, 3, 5);
    c.gridx = 0;

    List<JTextField> nameFields = new ArrayList<>();
    List<JSpinner> muteFields = new ArrayList<>();
    List<JSpinner> pitchFields = new ArrayList<>();

    for (int i = 0; i < selected.length; i++) {
      JTextField nameFld = new JTextField(selected[i].getName().replaceAll("(?i)\\.xml$", ""), 20);
      SpinnerNumberModel muteModel = new SpinnerNumberModel(0, 0, 16, 1);
      JSpinner muteSpinner = new JSpinner(muteModel);
      SpinnerNumberModel pitchModel = new SpinnerNumberModel(0, -24, 24, 1);
      JSpinner pitchSpinner = new JSpinner(pitchModel);

      c.gridy = i;
      c.gridwidth = 1;
      configPanel.add(new JLabel((i + 1) + ":"), c);
      c.gridx = 1;
      configPanel.add(nameFld, c);
      c.gridx = 2;
      configPanel.add(new JLabel("MG:"), c);
      c.gridx = 3;
      configPanel.add(muteSpinner, c);
      c.gridx = 4;
      configPanel.add(new JLabel("Pitch:"), c);
      c.gridx = 5;
      configPanel.add(pitchSpinner, c);
      c.gridx = 0;

      nameFields.add(nameFld);
      muteFields.add(muteSpinner);
      pitchFields.add(pitchSpinner);
    }

    int result =
        JOptionPane.showConfirmDialog(
            app,
            configPanel,
            "Configure Kit Lanes",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE);
    if (result != JOptionPane.OK_OPTION) return;

    try {
      List<Integer> muteGroups = new ArrayList<>();
      List<Integer> pitchOffsets = new ArrayList<>();
      for (int i = 0; i < selected.length; i++) {
        muteGroups.add((Integer) muteFields.get(i).getValue());
        pitchOffsets.add((Integer) pitchFields.get(i).getValue());
      }

      String kitName = JOptionPane.showInputDialog(app, "Kit name:", "Kit from Synths");
      if (kitName == null || kitName.isBlank()) kitName = "Kit from Synths";

      KitTrackModel kit =
          KitAssembler.assembleFromSynths(
              kitName, Arrays.asList(selected), muteGroups, pitchOffsets);

      JFileChooser saveChooser = new JFileChooser(PreferencesManager.getSongsDir());
      saveChooser.setDialogTitle("Save Kit As");
      saveChooser.setFileFilter(
          new javax.swing.filechooser.FileNameExtensionFilter("Kit XML", "xml", "XML"));
      saveChooser.setSelectedFile(new File(kitName + ".xml"));
      if (saveChooser.showSaveDialog(app) != JFileChooser.APPROVE_OPTION) return;

      File saveFile = saveChooser.getSelectedFile();
      if (!saveFile.getName().toLowerCase().endsWith(".xml")) {
        saveFile = new File(saveFile.getAbsolutePath() + ".xml");
      }
      KitSynthSerializer.saveKit(kit, saveFile);

      JOptionPane.showMessageDialog(
          app,
          "Kit saved to:\n" + saveFile.getAbsolutePath(),
          "Kit Assembly Complete",
          JOptionPane.INFORMATION_MESSAGE);
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(
          app, "Failed to assemble kit:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
    }
  }

  public void launchNewInstance() {
    try {
      List<String> cmd = new ArrayList<>();
      ProcessHandle.Info info = ProcessHandle.current().info();
      String exe = info.command().orElse(null);
      String[] args = info.arguments().orElse(null);
      if (exe != null && args != null) {
        cmd.add(exe);
        Collections.addAll(cmd, args);
      } else {
        // Fallback: java.home/bin/java + the JVM's own input args + classpath + this main class.
        cmd.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        cmd.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
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
          app,
          "Could not launch a new Deluge window:\n" + ex.getMessage(),
          "New Window",
          JOptionPane.ERROR_MESSAGE);
    }
  }
}
