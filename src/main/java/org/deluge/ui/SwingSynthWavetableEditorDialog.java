package org.deluge.ui;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import org.deluge.BridgeContract;
import org.deluge.engine.FirmwareSound;
import org.deluge.firmware2.WaveTable;
import org.deluge.firmware2.WaveTableBand;
import org.deluge.firmware2.WaveTableWriter;
import org.deluge.firmware2.WavetableGenerator;
import org.deluge.model.SynthTrackModel;

/**
 * A stunning, state-of-the-art dark neon Wavetable Laboratory & Editor Dialog. Allows users to edit
 * wavetables in real-time, draw custom waveforms, generate cycles mathematically (using 8-harmonic
 * additive sliders and FM synthesis), interpolate tables, and hot-swap changes live into the
 * running DSP engine.
 */
public class SwingSynthWavetableEditorDialog extends JDialog {

  private final SynthTrackModel model;
  private final int oscIndex; // 0 = Osc A, 1 = Osc B
  private final int trackIndex;
  private final BridgeContract bridge;

  private WaveTable liveWaveTable;
  private float[] masterCycles; // concatenated float samples [numCycles * cycleSize]
  private final boolean[] cycleEdited; // track edited cycles for interpolation
  private int currentCycleIdx = 0;
  private final int cycleSize = 2048;
  private final int numCycles = 32;

  // UI Components
  private final Wavetable3DVisualizer visualizer3D;
  private final SwingWaveformDrawingCanvas canvas2D;
  private final JSlider positionSlider;
  private final JLabel positionLabel;

  // Additive Harmonic Sliders
  private final JSlider[] harmonicSliders = new JSlider[8];
  private boolean isUpdatingSliders = false;

  // FM Sliders
  private final JSlider fmRatioSlider;
  private final JSlider fmIndexSlider;
  private final JLabel fmRatioLabel;
  private final JLabel fmIndexLabel;

  public SwingSynthWavetableEditorDialog(
      Window owner, SynthTrackModel model, int oscIndex, int trackIndex, BridgeContract bridge) {
    super(
        owner,
        String.format("🔬 Wavetable Creator & Editor — Oscillator %s", (oscIndex == 0) ? "A" : "B"),
        ModalityType.MODELESS);

    this.model = model;
    this.oscIndex = oscIndex;
    this.trackIndex = trackIndex;
    this.bridge = bridge;
    this.cycleEdited = new boolean[numCycles];

    setSize(1100, 600);
    setMinimumSize(new Dimension(950, 500));
    setLocationRelativeTo(owner);
    setResizable(true);
    getContentPane().setBackground(SwingSynthConfigDialog.BG_DARK);
    setLayout(new BorderLayout(10, 10));

    // 1. Resolve or allocate the live WaveTable object in memory
    initLiveWaveTable();

    // 2. Setup visualizers
    visualizer3D = new Wavetable3DVisualizer(model, oscIndex, trackIndex);
    visualizer3D.startAnimation();

    canvas2D = new SwingWaveformDrawingCanvas(cycleSize);
    loadActiveCycleToCanvas();

    canvas2D.setOnUpdateListener(
        () -> {
          // Captures manual drawing from the 2D canvas
          float[] drawn = canvas2D.getCycleBuffer();
          int offset = currentCycleIdx * cycleSize;
          System.arraycopy(drawn, 0, masterCycles, offset, cycleSize);
          cycleEdited[currentCycleIdx] = true;

          // Re-generate bandlimited versions & hot-swap live
          WavetableGenerator.generateBands(liveWaveTable, masterCycles);
          visualizer3D.repaint();
        });

    // 3. Header Panel
    JPanel headerPanel = new JPanel(new BorderLayout());
    headerPanel.setBackground(SwingSynthConfigDialog.BG_DARK);
    headerPanel.setBorder(new EmptyBorder(10, 15, 2, 15));

    String samplePath = (oscIndex == 0) ? model.getOsc1SamplePath() : model.getOsc2SamplePath();
    String fileName =
        (samplePath != null && !samplePath.isBlank())
            ? new File(samplePath).getName()
            : "NEW_WAVETABLE.WAV";

    JLabel titleLabel =
        new JLabel(
            String.format("🔬 3D WAVETABLE LABORATORY — Active File: %s", fileName.toUpperCase()));
    titleLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
    titleLabel.setForeground(SwingSynthConfigDialog.ACCENT_MINT);
    headerPanel.add(titleLabel, BorderLayout.WEST);
    add(headerPanel, BorderLayout.NORTH);

    // 4. Center Split Pane (3D Waterfall left, 2D Editor Canvas right)
    JPanel visualPanel = new JPanel(new GridLayout(1, 2, 12, 0));
    visualPanel.setBackground(SwingSynthConfigDialog.BG_DARK);
    visualPanel.setBorder(new EmptyBorder(0, 15, 0, 15));

    visualPanel.add(visualizer3D);

    JPanel rightEditorPanel = new JPanel(new BorderLayout(0, 8));
    rightEditorPanel.setBackground(SwingSynthConfigDialog.BG_DARK);
    rightEditorPanel.add(canvas2D, BorderLayout.CENTER);

    // Position scan slider
    JPanel scanPanel = new JPanel(new BorderLayout(10, 0));
    scanPanel.setBackground(new Color(0x18, 0x18, 0x1c));
    scanPanel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32), 1),
            new EmptyBorder(6, 12, 6, 12)));

    positionLabel = new JLabel(String.format("CYC: %02d/%d", currentCycleIdx + 1, numCycles));
    positionLabel.setFont(new Font("Monospaced", Font.BOLD, 12));
    positionLabel.setForeground(Color.CYAN);

    positionSlider = new JSlider(0, numCycles - 1, currentCycleIdx);
    DarkSliderUI.styleSlider(positionSlider, new Color(0x00, 0xff, 0xcc));
    positionSlider.addChangeListener(
        e -> {
          currentCycleIdx = positionSlider.getValue();
          positionLabel.setText(String.format("CYC: %02d/%d", currentCycleIdx + 1, numCycles));

          // Set the playhead in both model & 3D visualizer
          model.setWaveIndex((float) currentCycleIdx / (numCycles - 1));
          loadActiveCycleToCanvas();
          updateHarmonicSlidersFromCanvas();
        });

    scanPanel.add(new JLabel("SCAN INDEX:"), BorderLayout.WEST);
    scanPanel.add(positionSlider, BorderLayout.CENTER);
    scanPanel.add(positionLabel, BorderLayout.EAST);
    rightEditorPanel.add(scanPanel, BorderLayout.SOUTH);

    visualPanel.add(rightEditorPanel);
    add(visualPanel, BorderLayout.CENTER);

    // 5. Bottom Control Panel (Harmonics sliders, generators, and action buttons)
    JPanel bottomPanel = new JPanel(new GridBagLayout());
    bottomPanel.setBackground(new Color(0x16, 0x16, 0x1a));
    bottomPanel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x2d, 0x2d, 0x32)),
            new EmptyBorder(12, 15, 12, 15)));

    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.BOTH;
    c.insets = new Insets(4, 6, 4, 6);

    // Additive Harmonics Panel
    c.gridx = 0;
    c.gridy = 0;
    c.gridheight = 2;
    c.weightx = 0.5;
    c.weighty = 1.0;
    bottomPanel.add(createHarmonicsPanel(), c);

    // FM Generator Panel
    c.gridx = 1;
    c.gridy = 0;
    c.gridheight = 1;
    c.weightx = 0.3;
    c.weighty = 0.5;
    fmRatioSlider = new JSlider(5, 80, 10); // 0.5 to 8.0 (divided by 10)
    fmIndexSlider = new JSlider(0, 100, 20); // 0.0 to 10.0 (divided by 10)
    fmRatioLabel = new JLabel("1.0");
    fmIndexLabel = new JLabel("2.0");
    bottomPanel.add(createFmPanel(), c);

    // Preset Wave & Interpolate Actions Panel
    c.gridx = 1;
    c.gridy = 1;
    c.weighty = 0.5;
    bottomPanel.add(createActionsPanel(), c);

    // Row of major control buttons (Save, Close)
    c.gridx = 0;
    c.gridy = 2;
    c.gridwidth = 2;
    c.gridheight = 1;
    c.weighty = 0.0;
    bottomPanel.add(createSaveClosePanel(), c);

    add(bottomPanel, BorderLayout.SOUTH);

    // Initial update of harmonic sliders
    updateHarmonicSlidersFromCanvas();
  }

  private void initLiveWaveTable() {
    int trackIdx = this.trackIndex;
    FirmwareSound fs = getLiveSound(trackIdx);

    if (fs != null && fs.fw2Sound != null) {
      liveWaveTable = fs.fw2Sound.waveTables[oscIndex];
    }

    // If no wavetable exists yet in memory, allocate a default 32-cycle power-of-two table!
    if (liveWaveTable == null || liveWaveTable.bands.isEmpty()) {
      liveWaveTable = new WaveTable();
      liveWaveTable.setup(cycleSize, numCycles * cycleSize);
      if (fs != null && fs.fw2Sound != null) {
        fs.fw2Sound.waveTables[oscIndex] = liveWaveTable;
      }
    }

    // Set model types to WAVETABLE to enable scan engine
    if (oscIndex == 0) {
      model.setOsc1Type("WAVETABLE");
    } else {
      model.setOsc2Type("WAVETABLE");
    }

    // Read existing wavetable data into our master float buffer
    WaveTableBand baseBand = liveWaveTable.bands.get(0);
    int bandCycleSize = baseBand.cycleSizeNoDuplicates;
    masterCycles = new float[numCycles * cycleSize];

    short[] pcm = baseBand.data;
    if (pcm != null && pcm.length > 0) {
      for (int c = 0; c < numCycles; c++) {
        int srcOffset =
            c * (bandCycleSize + WaveTable.WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE);
        int dstOffset = c * cycleSize;
        for (int i = 0; i < cycleSize; i++) {
          if (srcOffset + i < pcm.length) {
            masterCycles[dstOffset + i] = pcm[srcOffset + i] / 32768.0f;
          }
        }
      }
    } else {
      // Default initialization with sine morphing to square
      for (int c = 0; c < numCycles; c++) {
        double t = (double) c / (numCycles - 1);
        int offset = c * cycleSize;
        for (int i = 0; i < cycleSize; i++) {
          double sine = Math.sin(2.0 * Math.PI * i / cycleSize);
          double square = Math.signum(sine);
          masterCycles[offset + i] = (float) (sine * (1.0 - t) + square * t);
        }
      }
      WavetableGenerator.generateBands(liveWaveTable, masterCycles);
    }
  }

  private void loadActiveCycleToCanvas() {
    float[] temp = new float[cycleSize];
    System.arraycopy(masterCycles, currentCycleIdx * cycleSize, temp, 0, cycleSize);
    canvas2D.setCycleBuffer(temp);
  }

  private JPanel createHarmonicsPanel() {
    JPanel panel = new JPanel(new BorderLayout());
    panel.setBackground(new Color(0x1e, 0x1e, 0x24));
    panel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32), 1),
            new EmptyBorder(8, 12, 8, 12)));

    JLabel label = new JLabel("ADDITIVE HARMONICS (1 - 8):");
    label.setFont(new Font("SansSerif", Font.BOLD, 10));
    label.setForeground(Color.LIGHT_GRAY);
    panel.add(label, BorderLayout.NORTH);

    JPanel slidersRow = new JPanel(new GridLayout(1, 8, 4, 0));
    slidersRow.setBackground(new Color(0x1e, 0x1e, 0x24));

    for (int i = 0; i < 8; i++) {
      final int harmonicIdx = i;
      harmonicSliders[i] = new JSlider(JSlider.VERTICAL, 0, 100, 0);
      DarkSliderUI.styleSlider(harmonicSliders[i], new Color(0xff, 0xb3, 0x00));
      harmonicSliders[i].addChangeListener(
          e -> {
            if (!isUpdatingSliders) {
              generateAdditiveWave();
            }
          });
      slidersRow.add(harmonicSliders[i]);
    }
    panel.add(slidersRow, BorderLayout.CENTER);
    return panel;
  }

  private void generateAdditiveWave() {
    float[] cycle = new float[cycleSize];
    double totalAmps = 0;

    // Calculate sum of harmonics
    for (int h = 0; h < 8; h++) {
      double amp = harmonicSliders[h].getValue() / 100.0;
      if (amp > 0) {
        totalAmps += amp;
        int harmonicNum = h + 1;
        for (int i = 0; i < cycleSize; i++) {
          cycle[i] += (float) (amp * Math.sin(2.0 * Math.PI * harmonicNum * i / cycleSize));
        }
      }
    }

    // Normalize to prevent clipping
    if (totalAmps > 0) {
      for (int i = 0; i < cycleSize; i++) {
        cycle[i] /= (float) totalAmps;
      }
    }

    // Update active cycle and hot-swap live
    int offset = currentCycleIdx * cycleSize;
    System.arraycopy(cycle, 0, masterCycles, offset, cycleSize);
    cycleEdited[currentCycleIdx] = true;

    canvas2D.setCycleBuffer(cycle);
    WavetableGenerator.generateBands(liveWaveTable, masterCycles);
    visualizer3D.repaint();
  }

  private void updateHarmonicSlidersFromCanvas() {
    isUpdatingSliders = true;
    float[] cycle = canvas2D.getCycleBuffer();

    // Use a simple discrete Fourier transform (DFT) to extract first 8 harmonics
    for (int h = 0; h < 8; h++) {
      int harmonicNum = h + 1;
      double realSum = 0;
      double imagSum = 0;

      for (int i = 0; i < cycleSize; i++) {
        double angle = 2.0 * Math.PI * harmonicNum * i / cycleSize;
        realSum += cycle[i] * Math.cos(angle);
        imagSum += cycle[i] * Math.sin(angle);
      }

      double magnitude = Math.sqrt(realSum * realSum + imagSum * imagSum) / (cycleSize / 2.0);
      int sliderVal = (int) Math.min(100, Math.max(0, magnitude * 100.0));
      harmonicSliders[h].setValue(sliderVal);
    }
    isUpdatingSliders = false;
  }

  private JPanel createFmPanel() {
    JPanel panel = new JPanel(new BorderLayout(6, 6));
    panel.setBackground(new Color(0x1e, 0x1e, 0x24));
    panel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32), 1),
            new EmptyBorder(8, 12, 8, 12)));

    JLabel label = new JLabel("FM GENERATOR FORMULA:");
    label.setFont(new Font("SansSerif", Font.BOLD, 10));
    label.setForeground(Color.LIGHT_GRAY);
    panel.add(label, BorderLayout.NORTH);

    JPanel slidersGrid = new JPanel(new GridLayout(2, 3, 4, 4));
    slidersGrid.setBackground(new Color(0x1e, 0x1e, 0x24));

    // Ratio row
    slidersGrid.add(new JLabel("RATIO (R):"));
    DarkSliderUI.styleSlider(fmRatioSlider, new Color(0x00, 0xbb, 0xff));
    fmRatioSlider.addChangeListener(
        e -> {
          fmRatioLabel.setText(String.format("%.1f", fmRatioSlider.getValue() / 10.0f));
        });
    slidersGrid.add(fmRatioSlider);
    slidersGrid.add(fmRatioLabel);

    // Index row
    slidersGrid.add(new JLabel("INDEX (I):"));
    DarkSliderUI.styleSlider(fmIndexSlider, new Color(0x00, 0xbb, 0xff));
    fmIndexSlider.addChangeListener(
        e -> {
          fmIndexLabel.setText(String.format("%.1f", fmIndexSlider.getValue() / 10.0f));
        });
    slidersGrid.add(fmIndexSlider);
    slidersGrid.add(fmIndexLabel);

    panel.add(slidersGrid, BorderLayout.CENTER);

    JButton generateFmBtn = new JButton("Generate FM Wave");
    generateFmBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
    styleButton(generateFmBtn, new Color(0x1e, 0x24, 0x32), new Color(0x00, 0xbb, 0xff));
    generateFmBtn.addActionListener(e -> generateFmWave());
    panel.add(generateFmBtn, BorderLayout.SOUTH);

    return panel;
  }

  private void generateFmWave() {
    float ratio = fmRatioSlider.getValue() / 10.0f;
    float index = fmIndexSlider.getValue() / 10.0f;

    float[] cycle = new float[cycleSize];
    for (int i = 0; i < cycleSize; i++) {
      double t = (double) i / cycleSize;
      double modulator = Math.sin(2.0 * Math.PI * ratio * t);
      cycle[i] = (float) Math.sin(2.0 * Math.PI * t + index * modulator);
    }

    int offset = currentCycleIdx * cycleSize;
    System.arraycopy(cycle, 0, masterCycles, offset, cycleSize);
    cycleEdited[currentCycleIdx] = true;

    canvas2D.setCycleBuffer(cycle);
    WavetableGenerator.generateBands(liveWaveTable, masterCycles);
    visualizer3D.repaint();
    updateHarmonicSlidersFromCanvas();
  }

  private JPanel createActionsPanel() {
    JPanel panel = new JPanel(new GridLayout(2, 3, 6, 6));
    panel.setBackground(new Color(0x16, 0x16, 0x1a));

    String[] waves = {"SINE", "TRIANGLE", "SAW", "SQUARE", "NOISE"};
    for (String wave : waves) {
      JButton btn = new JButton(wave);
      btn.setFont(new Font("SansSerif", Font.BOLD, 10));
      styleButton(btn, new Color(0x2d, 0x2d, 0x32), Color.WHITE);
      btn.addActionListener(e -> generatePresetWave(wave));
      panel.add(btn);
    }

    JButton interpBtn = new JButton("INTERPOLATE TABLE");
    interpBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
    styleButton(
        interpBtn, new Color(0x32, 0x1e, 0x32), new Color(0xff, 0x00, 0xcc)); // Hot Neon Pink
    interpBtn.addActionListener(e -> interpolateWavetable());
    panel.add(interpBtn);

    return panel;
  }

  private void generatePresetWave(String type) {
    float[] cycle = new float[cycleSize];
    for (int i = 0; i < cycleSize; i++) {
      double t = (double) i / cycleSize;
      switch (type) {
        case "SINE":
          cycle[i] = (float) Math.sin(2.0 * Math.PI * t);
          break;
        case "TRIANGLE":
          cycle[i] = (float) (2.0 * Math.abs(2.0 * (t - Math.floor(t + 0.5))) - 1.0);
          break;
        case "SAW":
          cycle[i] = (float) (2.0 * (t - Math.floor(t + 0.5)));
          break;
        case "SQUARE":
          cycle[i] = (float) Math.signum(Math.sin(2.0 * Math.PI * t));
          break;
        case "NOISE":
          cycle[i] = (float) (Math.random() * 2.0 - 1.0);
          break;
      }
    }

    int offset = currentCycleIdx * cycleSize;
    System.arraycopy(cycle, 0, masterCycles, offset, cycleSize);
    cycleEdited[currentCycleIdx] = true;

    canvas2D.setCycleBuffer(cycle);
    WavetableGenerator.generateBands(liveWaveTable, masterCycles);
    visualizer3D.repaint();
    updateHarmonicSlidersFromCanvas();
  }

  private void interpolateWavetable() {
    // 1. Find all edited cycles
    int firstIdx = -1;
    int lastIdx = -1;

    for (int i = 0; i < numCycles; i++) {
      if (cycleEdited[i]) {
        if (firstIdx == -1) firstIdx = i;
        lastIdx = i;
      }
    }

    // Safety fallback: if fewer than two cycles are custom edited, interpolate between first and
    // last cycles
    if (firstIdx == lastIdx) {
      firstIdx = 0;
      lastIdx = numCycles - 1;
      cycleEdited[firstIdx] = true;
      cycleEdited[lastIdx] = true;
    }

    // 2. Perform piecewise linear interpolation between adjacent edited keys
    int currentKey = firstIdx;
    for (int i = firstIdx + 1; i <= lastIdx; i++) {
      if (cycleEdited[i]) {
        int nextKey = i;
        int gap = nextKey - currentKey;

        if (gap > 1) {
          int offsetStart = currentKey * cycleSize;
          int offsetEnd = nextKey * cycleSize;

          for (int j = 1; j < gap; j++) {
            double stepNorm = (double) j / gap;
            int targetOffset = (currentKey + j) * cycleSize;

            for (int s = 0; s < cycleSize; s++) {
              float startVal = masterCycles[offsetStart + s];
              float endVal = masterCycles[offsetEnd + s];
              masterCycles[targetOffset + s] = (float) (startVal + stepNorm * (endVal - startVal));
            }
          }
        }
        currentKey = nextKey;
      }
    }

    // Re-generate bands and refresh
    WavetableGenerator.generateBands(liveWaveTable, masterCycles);
    visualizer3D.repaint();
    loadActiveCycleToCanvas();
    updateHarmonicSlidersFromCanvas();

    JOptionPane.showMessageDialog(
        this,
        "Wavetable morph curves interpolated successfully across all intermediate cycles!",
        "Interpolation Complete",
        JOptionPane.INFORMATION_MESSAGE);
  }

  private JPanel createSaveClosePanel() {
    JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
    panel.setBackground(new Color(0x16, 0x16, 0x1a));

    JButton saveAsBtn = new JButton("Save wavetable to SD...");
    saveAsBtn.setFont(new Font("SansSerif", Font.BOLD, 11));
    styleButton(saveAsBtn, new Color(0x1e, 0x32, 0x22), new Color(0x00, 0xff, 0x66));
    saveAsBtn.addActionListener(e -> saveWavetableToFile());
    panel.add(saveAsBtn);

    JButton closeBtn = new JButton("Apply & Close");
    closeBtn.setFont(new Font("SansSerif", Font.BOLD, 11));
    styleButton(closeBtn, new Color(0x2d, 0x2d, 0x32), Color.WHITE);
    closeBtn.addActionListener(e -> dispose());
    panel.add(closeBtn);

    return panel;
  }

  private void saveWavetableToFile() {
    String defaultPath = (oscIndex == 0) ? model.getOsc1SamplePath() : model.getOsc2SamplePath();
    File defaultFile =
        (defaultPath != null && !defaultPath.isBlank())
            ? new File(defaultPath)
            : new File("SD/SYNTHS/CUSTOM_WT.WAV");

    JFileChooser fileChooser = new JFileChooser(defaultFile.getParentFile());
    fileChooser.setDialogTitle("Save Custom Wavetable (.WAV)");
    fileChooser.setSelectedFile(defaultFile);

    int userSelection = fileChooser.showSaveDialog(this);
    if (userSelection == JFileChooser.APPROVE_OPTION) {
      File fileToSave = fileChooser.getSelectedFile();
      String path = fileToSave.getAbsolutePath();
      if (!path.toUpperCase().endsWith(".WAV")) {
        path += ".WAV";
      }

      try {
        // Save using our new WaveTableWriter
        WaveTableWriter.writeWavetable(masterCycles, path);

        // Update active model and trigger refresh
        if (oscIndex == 0) {
          model.setOsc1SamplePath(path);
          model.setOsc1Type("WAVETABLE");
        } else {
          model.setOsc2SamplePath(path);
          model.setOsc2Type("WAVETABLE");
        }

        JOptionPane.showMessageDialog(
            this,
            "Custom Wavetable saved successfully to SD card!\nPath: " + path,
            "Wavetable Saved",
            JOptionPane.INFORMATION_MESSAGE);

      } catch (IOException ex) {
        JOptionPane.showMessageDialog(
            this,
            "Failed to write Wavetable WAV file: " + ex.getMessage(),
            "Save Error",
            JOptionPane.ERROR_MESSAGE);
      }
    }
  }

  private void styleButton(JButton btn, Color bg, Color fg) {
    btn.setBackground(bg);
    btn.setForeground(fg);
    btn.setFocusPainted(false);
    btn.setBorder(BorderFactory.createLineBorder(fg.darker(), 1));
    btn.setPreferredSize(new Dimension(150, 26));
  }

  @Override
  public void dispose() {
    visualizer3D.stopAnimation();
    super.dispose();
  }

  private org.deluge.engine.FirmwareSound getLiveSound(int trackIndex) {
    try {
      Object eng = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
      if (eng instanceof org.deluge.engine.FirmwareAudioEngine engine) {
        if (trackIndex >= 0 && trackIndex < engine.sounds.size()) {
          var sound = engine.sounds.get(trackIndex);
          if (sound instanceof org.deluge.engine.FirmwareSound) {
            return (org.deluge.engine.FirmwareSound) sound;
          }
        }
      }
    } catch (Exception ignored) {
    }
    return null;
  }
}
