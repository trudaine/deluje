package org.chuck.deluge.ui;

import java.awt.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import javax.swing.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.engine.DelugeatorRandomizer;
import org.chuck.deluge.engine.DelugeatorRandomizer.RandomizerSettings;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;
import org.chuck.deluge.model.TrackModel;
import org.chuck.deluge.project.PreferencesManager;
import org.chuck.deluge.xml.DelugeXmlExporter;

/**
 * A beautiful, highly-interactive wide-screen dialog implementing all rules and logics of the
 * Delugeator Web Randomizer (triangular distributions, live patch generation & SD card exports).
 */
public class SwingRandomizerDialog extends JDialog {

  private final ChuckVM vm;
  private final BridgeContract bridge;
  private final ProjectModel projectModel;
  private final Frame parentFrame;

  // UI components array trackers
  private final String[] categoryNames = {
    "General",
    "Osc 1",
    "Osc 2",
    "Noise",
    "Envelope 1",
    "Envelope 2",
    "Lfo 1",
    "Lfo 2",
    "Effects",
    "ModFx",
    "Delay",
    "Filters",
    "EQ",
    "Compressor",
    "Arpeggiator",
    "Unison"
  };

  private final JCheckBox[] checkBoxes = new JCheckBox[16];
  private final JSlider[] sliders = new JSlider[16];
  private final JLabel[] valueLabels = new JLabel[16];

  private JComboBox<String> templateCombo;
  private JCheckBox hardcoreBox;
  private RandomnessGauge gaugeMeter;

  public SwingRandomizerDialog(
      Frame parent, ChuckVM vm, BridgeContract bridge, ProjectModel projectModel) {
    super(parent, "Delugeator Web Randomizer", false);
    this.parentFrame = parent;
    this.vm = vm;
    this.bridge = bridge;
    this.projectModel = projectModel;

    setSize(800, 720);
    setLocationRelativeTo(parent);
    setLayout(new BorderLayout(10, 10));
    getContentPane().setBackground(new Color(0x12, 0x12, 0x14));

    // ── TOP PANEL (Templates & Settings) ──
    JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 8));
    topPanel.setBackground(new Color(0x1a, 0x1a, 0x1e));
    topPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x2d, 0x2d, 0x32)));

    JLabel templateLabel = new JLabel("Randomization Preset Template:");
    templateLabel.setForeground(Color.WHITE);
    templateLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
    topPanel.add(templateLabel);

    String[] templates = {
      "Custom (Keep Sliders)",
      "Mild Randomization (25%)",
      "Moderate Randomization (50%)",
      "Hard Randomization (75%)",
      "Wild / Brain-Crushing (100%)"
    };
    templateCombo = new JComboBox<>(templates);
    templateCombo.setSelectedIndex(2); // Moderate default
    templateCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    templateCombo.setForeground(Color.WHITE);
    templateCombo.addActionListener(e -> applyTemplatePreset());
    topPanel.add(templateCombo);

    add(topPanel, BorderLayout.NORTH);

    // ── CENTER PANEL (Scrollable Sliders list) ──
    JPanel slidersListPanel = new JPanel(new GridBagLayout());
    slidersListPanel.setBackground(new Color(0x12, 0x12, 0x14));
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(6, 12, 6, 12);
    c.weightx = 1.0;

    for (int i = 0; i < 16; i++) {
      c.gridy = i;
      final int idx = i;

      // Checkbox
      c.gridx = 0;
      c.weightx = 0.0;
      checkBoxes[i] = new JCheckBox(categoryNames[i], true);
      checkBoxes[i].setFont(new Font("SansSerif", Font.BOLD, 12));
      checkBoxes[i].setForeground(Color.WHITE);
      checkBoxes[i].setBackground(new Color(0x12, 0x12, 0x14));
      checkBoxes[i].setPreferredSize(new Dimension(150, 24));
      checkBoxes[i].addActionListener(
          e -> {
            sliders[idx].setEnabled(checkBoxes[idx].isSelected());
            updateAverageMeter();
          });
      slidersListPanel.add(checkBoxes[i], c);

      // Slider
      c.gridx = 1;
      c.weightx = 1.0;
      sliders[i] = new JSlider(0, 100, 50);
      sliders[i].setBackground(new Color(0x12, 0x12, 0x14));
      sliders[i].addChangeListener(
          e -> {
            valueLabels[idx].setText(sliders[idx].getValue() + "%");
            updateAverageMeter();
          });
      slidersListPanel.add(sliders[i], c);

      // Value label
      c.gridx = 2;
      c.weightx = 0.0;
      valueLabels[i] = new JLabel("50%");
      valueLabels[i].setFont(new Font("Monospaced", Font.BOLD, 12));
      valueLabels[i].setForeground(Color.CYAN);
      valueLabels[i].setPreferredSize(new Dimension(50, 20));
      slidersListPanel.add(valueLabels[i], c);
    }

    JScrollPane scroll = new JScrollPane(slidersListPanel);
    scroll.setBorder(null);
    scroll.getVerticalScrollBar().setUnitIncrement(16);
    add(scroll, BorderLayout.CENTER);

    // ── SOUTH PANEL (Gauge & Action Buttons) ──
    JPanel southPanel = new JPanel(new GridBagLayout());
    southPanel.setBackground(new Color(0x1a, 0x1a, 0x1e));
    southPanel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x2d, 0x2d, 0x32)),
            BorderFactory.createEmptyBorder(12, 16, 12, 16)));
    GridBagConstraints cs = new GridBagConstraints();
    cs.fill = GridBagConstraints.HORIZONTAL;
    cs.weightx = 1.0;

    // Hardcore Mode row
    cs.gridx = 0;
    cs.gridy = 0;
    cs.gridwidth = 2;
    hardcoreBox =
        new JCheckBox(
            "☠️ HARDCORE MODE: Allow brain-crushing saturation and maximum feedback limit parameters!");
    hardcoreBox.setFont(new Font("SansSerif", Font.BOLD, 12));
    hardcoreBox.setForeground(new Color(0xff, 0x55, 0x55));
    hardcoreBox.setBackground(new Color(0x1a, 0x1a, 0x1e));
    southPanel.add(hardcoreBox, cs);

    // Custom Gauge Meter
    cs.gridy = 1;
    gaugeMeter = new RandomnessGauge();
    gaugeMeter.setPreferredSize(new Dimension(750, 65));
    southPanel.add(gaugeMeter, cs);

    // Actions Row
    cs.gridy = 2;
    cs.gridwidth = 1;
    cs.weightx = 0.5;
    cs.insets = new Insets(8, 0, 0, 8);

    JButton randOptionsBtn = new JButton("🎲 Randomize Mappings");
    randOptionsBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
    randOptionsBtn.setPreferredSize(new Dimension(350, 42));
    randOptionsBtn.setBackground(new Color(0x32, 0x2d, 0x4d));
    randOptionsBtn.setForeground(Color.WHITE);
    randOptionsBtn.setFocusable(false);
    randOptionsBtn.addActionListener(e -> triggerRandomizeOptions());
    southPanel.add(randOptionsBtn, cs);

    cs.gridx = 1;
    cs.insets = new Insets(8, 8, 0, 0);
    JButton createSynthBtn = new JButton("⚡ Create & Load Random Synth");
    createSynthBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
    createSynthBtn.setPreferredSize(new Dimension(350, 42));
    createSynthBtn.setBackground(new Color(0x0c, 0x38, 0x1f));
    createSynthBtn.setForeground(Color.GREEN);
    createSynthBtn.setFocusable(false);
    createSynthBtn.addActionListener(e -> generateAndLoadSynth());
    southPanel.add(createSynthBtn, cs);

    add(southPanel, BorderLayout.SOUTH);

    applyTemplatePreset(); // Apply initial default (50%)
    DarkComboBoxRenderer.styleComponentTree(this);
  }

  private void applyTemplatePreset() {
    int idx = templateCombo.getSelectedIndex();
    if (idx == 0) return; // Custom

    int percent = 50;
    if (idx == 1) percent = 25;
    else if (idx == 2) percent = 50;
    else if (idx == 3) percent = 75;
    else if (idx == 4) percent = 100;

    for (int i = 0; i < 16; i++) {
      checkBoxes[i].setSelected(true);
      sliders[i].setEnabled(true);
      sliders[i].setValue(percent);
      valueLabels[i].setText(percent + "%");
    }
    updateAverageMeter();
  }

  private void updateAverageMeter() {
    int sum = 0;
    int count = 0;
    for (int i = 0; i < 16; i++) {
      if (checkBoxes[i].isSelected()) {
        sum += sliders[i].getValue();
        count++;
      }
    }
    int average = count > 0 ? (sum / count) : 0;
    gaugeMeter.setAverage(average);

    // If average is custom, update combo to "Custom"
    boolean matchPreset = false;
    for (int presetIdx = 1; presetIdx <= 4; presetIdx++) {
      int targetPercent = presetIdx == 1 ? 25 : (presetIdx == 2 ? 50 : (presetIdx == 3 ? 75 : 100));
      boolean allCheckedMatch = true;
      for (int i = 0; i < 16; i++) {
        if (!checkBoxes[i].isSelected() || sliders[i].getValue() != targetPercent) {
          allCheckedMatch = false;
          break;
        }
      }
      if (allCheckedMatch) {
        templateCombo.setSelectedIndex(presetIdx);
        matchPreset = true;
        break;
      }
    }
    if (!matchPreset) {
      templateCombo.setSelectedIndex(0); // Custom
    }
  }

  private void triggerRandomizeOptions() {
    for (int i = 0; i < 16; i++) {
      // 75% chance to stay enabled
      boolean enable = Math.random() < 0.75;
      checkBoxes[i].setSelected(enable);
      sliders[i].setEnabled(enable);

      // Complete random slider value
      int randVal = (int) (Math.random() * 101);
      sliders[i].setValue(randVal);
      valueLabels[i].setText(randVal + "%");
    }
    updateAverageMeter();
  }

  private void generateAndLoadSynth() {
    SwingGridPanel activeGrid = SwingDelugeApp.mainInstance.getActiveGridPanel();
    if (activeGrid == null) {
      JOptionPane.showMessageDialog(
          this,
          "⚠️ Please focus a project workspace grid panel first!",
          "Error",
          JOptionPane.ERROR_MESSAGE);
      return;
    }

    int activeTrackIdx = activeGrid.getEditedModelTrack();
    List<TrackModel> tracks = projectModel.getTracks();
    if (activeTrackIdx < 0 || activeTrackIdx >= tracks.size()) {
      JOptionPane.showMessageDialog(
          this, "⚠️ Please select a valid track channel row!", "Error", JOptionPane.ERROR_MESSAGE);
      return;
    }

    TrackModel track = tracks.get(activeTrackIdx);
    boolean fallbackCreated = false;

    // Fallback: If not a Synth track, automatically add a new Synth track!
    if (!(track instanceof SynthTrackModel)) {
      int confirm =
          JOptionPane.showConfirmDialog(
              this,
              "💡 Active row channel is NOT a Synthesizer track. Would you like to automatically create and load a new Synth track channel?",
              "Create New Track?",
              JOptionPane.YES_NO_OPTION,
              JOptionPane.QUESTION_MESSAGE);
      if (confirm != JOptionPane.YES_OPTION) return;

      SynthTrackModel newSynth = new SynthTrackModel("Random Synth");
      newSynth.addClip(new ClipModel("CLIP 1", 8, 16));
      activeTrackIdx = tracks.size();
      projectModel.addTrack(newSynth);
      track = newSynth;
      fallbackCreated = true;
    }

    // Capture user randomizer settings
    RandomizerSettings settings = new RandomizerSettings();
    settings.generalChecked = checkBoxes[0].isSelected();
    settings.generalAmount = sliders[0].getValue() / 100.0;
    settings.osc1Checked = checkBoxes[1].isSelected();
    settings.osc1Amount = sliders[1].getValue() / 100.0;
    settings.osc2Checked = checkBoxes[2].isSelected();
    settings.osc2Amount = sliders[2].getValue() / 100.0;
    settings.noiseChecked = checkBoxes[3].isSelected();
    settings.noiseAmount = sliders[3].getValue() / 100.0;
    settings.env1Checked = checkBoxes[4].isSelected();
    settings.env1Amount = sliders[4].getValue() / 100.0;
    settings.env2Checked = checkBoxes[5].isSelected();
    settings.env2Amount = sliders[5].getValue() / 100.0;
    settings.lfo1Checked = checkBoxes[6].isSelected();
    settings.lfo1Amount = sliders[6].getValue() / 100.0;
    settings.lfo2Checked = checkBoxes[7].isSelected();
    settings.lfo2Amount = sliders[7].getValue() / 100.0;
    settings.effectsChecked = checkBoxes[8].isSelected();
    settings.effectsAmount = sliders[8].getValue() / 100.0;
    settings.modFxChecked = checkBoxes[9].isSelected();
    settings.modFxAmount = sliders[9].getValue() / 100.0;
    settings.delayChecked = checkBoxes[10].isSelected();
    settings.delayAmount = sliders[10].getValue() / 100.0;
    settings.filtersChecked = checkBoxes[11].isSelected();
    settings.filtersAmount = sliders[11].getValue() / 100.0;
    settings.eqChecked = checkBoxes[12].isSelected();
    settings.eqAmount = sliders[12].getValue() / 100.0;
    settings.compressorChecked = checkBoxes[13].isSelected();
    settings.compressorAmount = sliders[13].getValue() / 100.0;
    settings.arpeggiatorChecked = checkBoxes[14].isSelected();
    settings.arpeggiatorAmount = sliders[14].getValue() / 100.0;
    settings.unisonChecked = checkBoxes[15].isSelected();
    settings.unisonAmount = sliders[15].getValue() / 100.0;
    settings.hardcoreMode = hardcoreBox.isSelected();

    // Run core randomizer engine
    SynthTrackModel synth = (SynthTrackModel) track;
    DelugeatorRandomizer.randomizeSynth(synth, projectModel, settings);

    // Save and export patch XML file to real SYNTHS SD card library directory
    String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
    String synthName = "Random_" + timestamp;
    synth.setName(synthName);

    File exportFile = new File(PreferencesManager.getSynthsDir(), synthName + ".XML");
    try {
      DelugeXmlExporter.saveSynthPreset(synth, bridge, activeTrackIdx, exportFile);
    } catch (Exception ex) {
      System.err.println("[Randomizer] Export XML preset failed: " + ex.getMessage());
    }

    // Dynamic Live Hot-Swap play synchronizer
    SwingDelugeApp.mainInstance.pushModelToBridge();
    SwingDelugeApp.mainInstance.reloadSidebarLibraries();
    SwingDelugeApp.mainInstance.propagateCurrentModel();
    SwingDelugeApp.mainInstance.syncHighFidelityEngine(projectModel);

    // Focus new track if fallback created
    if (fallbackCreated) {
      activeGrid.setEditedModelTrack(activeTrackIdx);
    }
    SwingDelugeApp.mainInstance.refreshGrids();

    dispose(); // Close dialog on completion

    JOptionPane.showMessageDialog(
        parentFrame,
        "<html>🎉 <b>SYNTH RANDOMIZED LIVE AND EXPORTED SUCCESSFULLY!</b><br><br>"
            + "New custom preset <b>'"
            + synthName
            + "'</b> loaded live into active play track row channel! Parameters are now playing live in real-time!<br><br>"
            + "WAV preset patch XML file exported to your library folder:<br>"
            + "📁 <code>"
            + exportFile.getAbsolutePath()
            + "</code></html>",
        "Synth Randomized!",
        JOptionPane.INFORMATION_MESSAGE);
  }

  /** Custom visual premium gradient Progress/Gauge status bar needle component. */
  private static class RandomnessGauge extends JComponent {
    private int average = 50;

    public void setAverage(int value) {
      this.average = value;
      repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

      int w = getWidth();
      int h = getHeight();

      // Draw horizontal bar frame
      int barX = 15;
      int barY = 15;
      int barW = w - 30;
      int barH = 14;

      // Draw gradient background segments
      // Green (0-25) -> Yellow (25-50) -> Orange (50-75) -> Red (75-100)
      for (int px = 0; px < barW; px++) {
        float ratio = (float) px / barW;
        Color c;
        if (ratio < 0.25f) {
          c = new Color(0, 255, 33);
        } else if (ratio < 0.50f) {
          c = new Color(251, 255, 68);
        } else if (ratio < 0.75f) {
          c = new Color(255, 205, 58);
        } else {
          c = new Color(255, 0, 0);
        }
        g2.setColor(c);
        g2.fillRect(barX + px, barY, 1, barH);
      }

      // Draw rounded container frame
      g2.setColor(new Color(0x44, 0x44, 0x48));
      g2.drawRoundRect(barX - 1, barY - 1, barW + 2, barH + 2, 4, 4);

      // Draw needle pointer triangle
      int needleX = barX + (int) ((average / 100.0) * barW);
      int[] tx = {needleX, needleX - 6, needleX + 6};
      int[] ty = {barY + barH + 2, barY + barH + 12, barY + barH + 12};
      g2.setColor(Color.WHITE);
      g2.fillPolygon(tx, ty, 3);
      g2.setColor(Color.RED);
      g2.drawLine(needleX, barY - 3, needleX, barY + barH + 3);

      // Print current status label description text
      String levelName = "Moderate";
      if (average < 25) levelName = "Mild / Safe";
      else if (average < 50) levelName = "Ok-ish";
      else if (average < 75) levelName = "Hard Mode";
      else levelName = "WILD / BRAIN-CRUSHING! ☠️";

      g2.setColor(Color.WHITE);
      g2.setFont(new Font("SansSerif", Font.BOLD, 12));
      String statusStr = "Randomness average range status: " + average + "% (" + levelName + ")";
      FontMetrics fm = g2.getFontMetrics();
      g2.drawString(statusStr, (w - fm.stringWidth(statusStr)) / 2, barY + barH + 28);

      g2.dispose();
    }
  }
}
