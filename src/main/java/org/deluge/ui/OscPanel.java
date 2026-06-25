package org.deluge.ui;

import java.awt.*;
import java.io.File;
import javax.swing.*;
import org.deluge.BridgeContract;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.project.PreferencesManager;

/**
 * A beautiful, 2-column wide-screen optimized layout for Oscillator and Mix settings, preventing
 * vertical scroll clipping and providing a premium desktop dashboard. Supports complete label
 * tooltips and hover quick help mapping details globally.
 */
public class OscPanel extends JPanel {
  private final SynthTrackModel model;
  private final Wavetable3DVisualizer vis1;
  private final Wavetable3DVisualizer vis2;

  public OscPanel(
      SynthTrackModel model, BridgeContract bridge, int trackIndex, ProjectModel projectModel) {
    super(new GridBagLayout());
    this.model = model;
    vis1 = new Wavetable3DVisualizer(model, 0, trackIndex);
    vis2 = new Wavetable3DVisualizer(model, 1, trackIndex);
    setBackground(SwingSynthConfigDialog.BG_CARD);

    // Create left and right sub-panels
    JPanel leftPanel = new JPanel(new GridBagLayout());
    leftPanel.setBackground(SwingSynthConfigDialog.BG_CARD);
    GridBagConstraints cLeft = new GridBagConstraints();
    cLeft.fill = GridBagConstraints.HORIZONTAL;
    cLeft.insets = new Insets(6, 10, 6, 10);
    cLeft.anchor = GridBagConstraints.WEST;
    int leftRow = 0;

    JPanel rightPanel = new JPanel(new GridBagLayout());
    rightPanel.setBackground(SwingSynthConfigDialog.BG_CARD);
    GridBagConstraints cRight = new GridBagConstraints();
    cRight.fill = GridBagConstraints.HORIZONTAL;
    cRight.insets = new Insets(6, 10, 6, 10);
    cRight.anchor = GridBagConstraints.WEST;
    int rightRow = 0;

    // ── Pre-Instance custom loop mode combos & checkboxes ──
    String[] loopModes = {"OFF", "LOOP", "ONESHOT"};

    JComboBox<String> osc1LoopCombo = new JComboBox<>(loopModes);
    osc1LoopCombo.setSelectedIndex(model.getOsc1LoopMode());
    osc1LoopCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    osc1LoopCombo.setForeground(Color.WHITE);
    osc1LoopCombo.setToolTipText(
        "Sample playback loop mode: OFF = play once, LOOP = repeat, ONESHOT = play entire sample ignoring note-off");
    SwingSynthConfigDialog.attachHoverHelp(
        osc1LoopCombo,
        "<b>OSC 1 LOOP MODE:</b> Sample playback loop mode (OFF = play once, LOOP = repeat, ONESHOT = play entire sample ignoring note-off). — <i>Physical Deluge:</i> Press shift + turn first top encoder in sample editor.");
    osc1LoopCombo.addActionListener(
        e -> {
          int idx = osc1LoopCombo.getSelectedIndex();
          model.setOsc1LoopMode(idx);
        });

    JCheckBox osc1RevBox = new JCheckBox("Play sample in reverse");
    osc1RevBox.setSelected(model.isOsc1Reversed());
    osc1RevBox.setBackground(SwingSynthConfigDialog.BG_CARD);
    osc1RevBox.setForeground(Color.WHITE);
    osc1RevBox.setToolTipText("Reverse sample playback direction");
    SwingSynthConfigDialog.attachHoverHelp(
        osc1RevBox,
        "<b>OSC 1 REVERSED:</b> Reverse sample playback direction. — <i>Physical Deluge:</i> Hold shift + click REVERSE button.");
    osc1RevBox.addActionListener(
        e -> {
          boolean sel = osc1RevBox.isSelected();
          model.setOsc1Reversed(sel);
        });

    JCheckBox osc1TsBox = new JCheckBox("Enable time stretching");
    osc1TsBox.setSelected(model.isOsc1TimeStretch());
    osc1TsBox.setBackground(SwingSynthConfigDialog.BG_CARD);
    osc1TsBox.setForeground(Color.WHITE);
    osc1TsBox.setToolTipText("Time-stretch sample to match project tempo without changing pitch");
    SwingSynthConfigDialog.attachHoverHelp(
        osc1TsBox,
        "<b>OSC 1 TIME STRETCH:</b> Time-stretch sample to match project tempo without changing pitch. — <i>Physical Deluge:</i> Set standard TEMPO mapping options.");
    osc1TsBox.addActionListener(
        e -> {
          boolean sel = osc1TsBox.isSelected();
          model.setOsc1TimeStretch(sel);
        });

    JCheckBox osc1LinBox = new JCheckBox("Linear (smoother, less aliasing)");
    osc1LinBox.setSelected(model.isOsc1LinearInterpolation());
    osc1LinBox.setBackground(SwingSynthConfigDialog.BG_CARD);
    osc1LinBox.setForeground(Color.WHITE);
    osc1LinBox.setToolTipText(
        "Unchecked = zero-order hold (gritty); Checked = linear interpolation (smoother pitch shifting)");
    SwingSynthConfigDialog.attachHoverHelp(
        osc1LinBox,
        "<b>OSC 1 INTERPOLATION:</b> Selects pitch-shifting algorithm (Unchecked = zero-order hold/gritty; Checked = linear interpolation/smooth). — <i>Physical Deluge:</i> Hold shift + select INTERPOLATION category.");
    osc1LinBox.addActionListener(
        e -> {
          boolean sel = osc1LinBox.isSelected();
          model.setOsc1LinearInterpolation(sel);
        });

    JComboBox<String> osc2LoopCombo = new JComboBox<>(loopModes);
    osc2LoopCombo.setSelectedIndex(model.getOsc2LoopMode());
    osc2LoopCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    osc2LoopCombo.setForeground(Color.WHITE);
    osc2LoopCombo.setToolTipText(
        "Sample playback loop mode: OFF = play once, LOOP = repeat, ONESHOT = play entire sample ignoring note-off");
    SwingSynthConfigDialog.attachHoverHelp(
        osc2LoopCombo,
        "<b>OSC 2 LOOP MODE:</b> Sample playback loop mode (OFF = play once, LOOP = repeat, ONESHOT = play entire sample ignoring note-off). — <i>Physical Deluge:</i> Press shift + turn first top encoder in sample editor.");
    osc2LoopCombo.addActionListener(
        e -> {
          int idx = osc2LoopCombo.getSelectedIndex();
          model.setOsc2LoopMode(idx);
        });

    JCheckBox osc2RevBox = new JCheckBox("Play sample in reverse");
    osc2RevBox.setSelected(model.isOsc2Reversed());
    osc2RevBox.setBackground(SwingSynthConfigDialog.BG_CARD);
    osc2RevBox.setForeground(Color.WHITE);
    osc2RevBox.setToolTipText("Reverse sample playback direction");
    SwingSynthConfigDialog.attachHoverHelp(
        osc2RevBox,
        "<b>OSC 2 REVERSED:</b> Reverse sample playback direction. — <i>Physical Deluge:</i> Hold shift + click REVERSE button.");
    osc2RevBox.addActionListener(
        e -> {
          boolean sel = osc2RevBox.isSelected();
          model.setOsc2Reversed(sel);
        });

    JCheckBox osc2TsBox = new JCheckBox("Enable time stretching");
    osc2TsBox.setSelected(model.isOsc2TimeStretch());
    osc2TsBox.setBackground(SwingSynthConfigDialog.BG_CARD);
    osc2TsBox.setForeground(Color.WHITE);
    osc2TsBox.setToolTipText("Time-stretch sample to match project tempo without changing pitch");
    SwingSynthConfigDialog.attachHoverHelp(
        osc2TsBox,
        "<b>OSC 2 TIME STRETCH:</b> Time-stretch sample to match project tempo without changing pitch. — <i>Physical Deluge:</i> Set standard TEMPO mapping options.");
    osc2TsBox.addActionListener(
        e -> {
          boolean sel = osc2TsBox.isSelected();
          model.setOsc2TimeStretch(sel);
        });

    JCheckBox osc2LinBox = new JCheckBox("Linear (smoother, less aliasing)");
    osc2LinBox.setSelected(model.isOsc2LinearInterpolation());
    osc2LinBox.setBackground(SwingSynthConfigDialog.BG_CARD);
    osc2LinBox.setForeground(Color.WHITE);
    osc2LinBox.setToolTipText(
        "Unchecked = zero-order hold (gritty); Checked = linear interpolation (smoother pitch shifting)");
    SwingSynthConfigDialog.attachHoverHelp(
        osc2LinBox,
        "<b>OSC 2 INTERPOLATION:</b> Selects pitch-shifting algorithm (Unchecked = zero-order hold/gritty; Checked = linear interpolation/smooth). — <i>Physical Deluge:</i> Hold shift + select INTERPOLATION category.");
    osc2LinBox.addActionListener(
        e -> {
          boolean sel = osc2LinBox.isSelected();
          model.setOsc2LinearInterpolation(sel);
        });

    JCheckBox syncBox = new JCheckBox("Reset osc 2 phase from osc 1 (hard sync)");
    syncBox.setSelected(model.isOscillatorSync());
    syncBox.setBackground(SwingSynthConfigDialog.BG_CARD);
    syncBox.setForeground(Color.WHITE);
    syncBox.setToolTipText(
        "Oscillator hard sync: osc 2's phase is reset by osc 1's cycle, creating characteristic sync sweep sounds");
    SwingSynthConfigDialog.attachHoverHelp(
        syncBox,
        "<b>OSCILLATOR SYNC:</b> Hard sync oscillator 2 phase frequency bounds to oscillator 1. — <i>Physical Deluge:</i> Hold shift + click OSC SYNC button.");
    syncBox.addActionListener(
        e -> {
          model.setOscillatorSync(syncBox.isSelected());
        });

    // ── BUILD LEFT PANEL (MIX & OSCILLATOR 1) ──
    cLeft.gridx = 0;
    cLeft.gridy = leftRow;
    cLeft.gridwidth = 3;
    leftPanel.add(SwingSynthConfigDialog.sectionLabel("MIX"), cLeft);
    leftRow++;

    leftRow =
        SwingSynthConfigDialog.addSlider(
            leftPanel,
            cLeft,
            leftRow,
            "Osc Mix:",
            "Balance between oscillator 1 and 2 (0% = only osc1, 100% = only osc2)",
            0,
            100,
            (int) (model.getOscMix() * 100),
            val -> model.setOscMix(val / 100f),
            "%",
            "oscMix",
            projectModel,
            trackIndex);

    leftRow =
        SwingSynthConfigDialog.addSlider(
            leftPanel,
            cLeft,
            leftRow,
            "Noise Vol:",
            "Noise generator volume (0-100%). Adds white noise to the signal.",
            0,
            100,
            (int) (model.getNoiseVol() * 100),
            val -> model.setNoiseVol(val / 100f),
            "%",
            "noiseVol",
            projectModel,
            trackIndex);

    leftRow =
        SwingSynthConfigDialog.addSlider(
            leftPanel,
            cLeft,
            leftRow,
            "Portamento:",
            "Portamento / glide time (0-100%). Higher = slower pitch slides between notes.",
            0,
            100,
            (int) (model.getPortamento() * 100),
            val -> model.setPortamento(val / 100f),
            "%",
            "portamento",
            projectModel,
            trackIndex);

    cLeft.gridx = 0;
    cLeft.gridy = leftRow;
    cLeft.gridwidth = 3;
    leftPanel.add(
        SwingSynthConfigDialog.sectionLabel("OSCILLATOR 1 \u2014 SAMPLE PLAYBACK"), cLeft);
    leftRow++;

    cLeft.gridx = 0;
    cLeft.gridy = leftRow;
    cLeft.gridwidth = 1;
    leftPanel.add(SwingSynthConfigDialog.label("Sample Map:"), cLeft);

    JPanel osc1SampleButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    osc1SampleButtons.setBackground(SwingSynthConfigDialog.BG_CARD);

    JButton osc1SingleBtn = new JButton("Single...");
    styleOscButton(osc1SingleBtn, new Color(0x2d, 0x2d, 0x32), Color.WHITE);
    osc1SingleBtn.addActionListener(e -> browseSingleSample(0, trackIndex, bridge));
    osc1SampleButtons.add(osc1SingleBtn);

    JButton osc1MultiBtn = new JButton("Map Zones...");
    styleOscButton(osc1MultiBtn, new Color(0x32, 0x1e, 0x32), new Color(0xff, 0x00, 0xcc));
    osc1MultiBtn.addActionListener(e -> openKeyZoneMapper(0, trackIndex, bridge));
    osc1SampleButtons.add(osc1MultiBtn);

    cLeft.gridx = 1;
    cLeft.gridwidth = 2;
    leftPanel.add(osc1SampleButtons, cLeft);
    leftRow++;

    cLeft.gridx = 0;
    cLeft.gridy = leftRow;
    cLeft.gridwidth = 1;
    JLabel osc1LoopLabel = SwingSynthConfigDialog.label("Loop Mode:");
    osc1LoopLabel.setToolTipText(osc1LoopCombo.getToolTipText());
    SwingSynthConfigDialog.attachHoverHelp(
        osc1LoopLabel,
        "<b>OSC 1 LOOP MODE:</b> Sample playback loop mode (OFF = play once, LOOP = repeat, ONESHOT = play entire sample ignoring note-off). — <i>Physical Deluge:</i> Press shift + turn first top encoder in sample editor.");
    leftPanel.add(osc1LoopLabel, cLeft);
    cLeft.gridx = 1;
    cLeft.gridwidth = 2;
    leftPanel.add(osc1LoopCombo, cLeft);
    leftRow++;

    cLeft.gridx = 0;
    cLeft.gridy = leftRow;
    cLeft.gridwidth = 1;
    JLabel osc1RevLabel = SwingSynthConfigDialog.label("Reversed:");
    osc1RevLabel.setToolTipText(osc1RevBox.getToolTipText());
    SwingSynthConfigDialog.attachHoverHelp(
        osc1RevLabel,
        "<b>OSC 1 REVERSED:</b> Reverse sample playback direction. — <i>Physical Deluge:</i> Hold shift + click REVERSE button.");
    leftPanel.add(osc1RevLabel, cLeft);
    cLeft.gridx = 1;
    cLeft.gridwidth = 2;
    leftPanel.add(osc1RevBox, cLeft);
    leftRow++;

    cLeft.gridx = 0;
    cLeft.gridy = leftRow;
    cLeft.gridwidth = 1;
    JLabel osc1TsLabel = SwingSynthConfigDialog.label("Time Stretch:");
    osc1TsLabel.setToolTipText(osc1TsBox.getToolTipText());
    SwingSynthConfigDialog.attachHoverHelp(
        osc1TsLabel,
        "<b>OSC 1 TIME STRETCH:</b> Time-stretch sample to match project tempo without changing pitch. — <i>Physical Deluge:</i> Set standard TEMPO mapping options.");
    leftPanel.add(osc1TsLabel, cLeft);
    cLeft.gridx = 1;
    cLeft.gridwidth = 2;
    leftPanel.add(osc1TsBox, cLeft);
    leftRow++;

    leftRow =
        SwingSynthConfigDialog.addSlider(
            leftPanel,
            cLeft,
            leftRow,
            "TS Amount:",
            "Time stretch amount (0-100%). Only relevant when time stretch is enabled.",
            0,
            100,
            (int) (model.getOsc1TimeStretchAmount() * 100),
            val -> {
              model.setOsc1TimeStretchAmount(val / 100f);
            },
            "%",
            "osc1TsAmt",
            projectModel,
            trackIndex);

    leftRow =
        SwingSynthConfigDialog.addSlider(
            leftPanel,
            cLeft,
            leftRow,
            "Cents:",
            "Fine pitch detune in cents (-50 to +50). 100 cents = 1 semitone.",
            -50,
            50,
            model.getOsc1Cents(),
            val -> {
              model.setOsc1Cents(val);
            },
            "",
            "osc1Cents",
            projectModel,
            trackIndex);

    cLeft.gridx = 0;
    cLeft.gridy = leftRow;
    cLeft.gridwidth = 1;
    JLabel osc1LinLabel = SwingSynthConfigDialog.label("Interpolation:");
    osc1LinLabel.setToolTipText(osc1LinBox.getToolTipText());
    SwingSynthConfigDialog.attachHoverHelp(
        osc1LinLabel,
        "<b>OSC 1 INTERPOLATION:</b> Selects pitch-shifting algorithm (Unchecked = zero-order hold/gritty; Checked = linear interpolation/smooth). — <i>Physical Deluge:</i> Hold shift + select INTERPOLATION category.");
    leftPanel.add(osc1LinLabel, cLeft);
    cLeft.gridx = 1;
    cLeft.gridwidth = 2;
    leftPanel.add(osc1LinBox, cLeft);
    leftRow++;

    // ── BUILD RIGHT PANEL (OSCILLATOR 2 & SYNC) ──
    cRight.gridx = 0;
    cRight.gridy = rightRow;
    cRight.gridwidth = 3;
    rightPanel.add(
        SwingSynthConfigDialog.sectionLabel("OSCILLATOR 2 \u2014 SAMPLE PLAYBACK"), cRight);
    rightRow++;

    cRight.gridx = 0;
    cRight.gridy = rightRow;
    cRight.gridwidth = 1;
    rightPanel.add(SwingSynthConfigDialog.label("Sample Map:"), cRight);

    JPanel osc2SampleButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    osc2SampleButtons.setBackground(SwingSynthConfigDialog.BG_CARD);

    JButton osc2SingleBtn = new JButton("Single...");
    styleOscButton(osc2SingleBtn, new Color(0x2d, 0x2d, 0x32), Color.WHITE);
    osc2SingleBtn.addActionListener(e -> browseSingleSample(1, trackIndex, bridge));
    osc2SampleButtons.add(osc2SingleBtn);

    JButton osc2MultiBtn = new JButton("Map Zones...");
    styleOscButton(osc2MultiBtn, new Color(0x32, 0x1e, 0x32), new Color(0xff, 0x00, 0xcc));
    osc2MultiBtn.addActionListener(e -> openKeyZoneMapper(1, trackIndex, bridge));
    osc2SampleButtons.add(osc2MultiBtn);

    cRight.gridx = 1;
    cRight.gridwidth = 2;
    rightPanel.add(osc2SampleButtons, cRight);
    rightRow++;

    cRight.gridx = 0;
    cRight.gridy = rightRow;
    cRight.gridwidth = 1;
    JLabel osc2LoopLabel = SwingSynthConfigDialog.label("Loop Mode:");
    osc2LoopLabel.setToolTipText(osc2LoopCombo.getToolTipText());
    SwingSynthConfigDialog.attachHoverHelp(
        osc2LoopLabel,
        "<b>OSC 2 LOOP MODE:</b> Sample playback loop mode (OFF = play once, LOOP = repeat, ONESHOT = play entire sample ignoring note-off). — <i>Physical Deluge:</i> Press shift + turn first top encoder in sample editor.");
    rightPanel.add(osc2LoopLabel, cRight);
    cRight.gridx = 1;
    cRight.gridwidth = 2;
    rightPanel.add(osc2LoopCombo, cRight);
    rightRow++;

    cRight.gridx = 0;
    cRight.gridy = rightRow;
    cRight.gridwidth = 1;
    JLabel osc2RevLabel = SwingSynthConfigDialog.label("Reversed:");
    osc2RevLabel.setToolTipText(osc2RevBox.getToolTipText());
    SwingSynthConfigDialog.attachHoverHelp(
        osc2RevLabel,
        "<b>OSC 2 REVERSED:</b> Reverse sample playback direction. — <i>Physical Deluge:</i> Hold shift + click REVERSE button.");
    rightPanel.add(osc2RevLabel, cRight);
    cRight.gridx = 1;
    cRight.gridwidth = 2;
    rightPanel.add(osc2RevBox, cRight);
    rightRow++;

    cRight.gridx = 0;
    cRight.gridy = rightRow;
    cRight.gridwidth = 1;
    JLabel osc2TsLabel = SwingSynthConfigDialog.label("Time Stretch:");
    osc2TsLabel.setToolTipText(osc2TsBox.getToolTipText());
    SwingSynthConfigDialog.attachHoverHelp(
        osc2TsLabel,
        "<b>OSC 2 TIME STRETCH:</b> Time-stretch sample to match project tempo without changing pitch. — <i>Physical Deluge:</i> Set standard TEMPO mapping options.");
    rightPanel.add(osc2TsLabel, cRight);
    cRight.gridx = 1;
    cRight.gridwidth = 2;
    rightPanel.add(osc2TsBox, cRight);
    rightRow++;

    rightRow =
        SwingSynthConfigDialog.addSlider(
            rightPanel,
            cRight,
            rightRow,
            "TS Amount:",
            "Time stretch amount (0-100%). Only relevant when time stretch is enabled.",
            0,
            100,
            (int) (model.getOsc2TimeStretchAmount() * 100),
            val -> {
              model.setOsc2TimeStretchAmount(val / 100f);
            },
            "%",
            "osc2TsAmt",
            projectModel,
            trackIndex);

    rightRow =
        SwingSynthConfigDialog.addSlider(
            rightPanel,
            cRight,
            rightRow,
            "Cents:",
            "Fine pitch detune in cents (-50 to +50). 100 cents = 1 semitone.",
            -50,
            50,
            model.getOsc2Cents(),
            val -> {
              model.setOsc2Cents(val);
            },
            "",
            "osc2Cents",
            projectModel,
            trackIndex);

    rightRow =
        SwingSynthConfigDialog.addSlider(
            rightPanel,
            cRight,
            rightRow,
            "Transpose:",
            "Oscillator 2 transpose in semitones (-24 to +24).",
            -24,
            24,
            model.getOsc2Transpose(),
            val -> {
              model.setOsc2Transpose(val);
            },
            "st",
            "osc2Transpose",
            projectModel,
            trackIndex);

    cRight.gridx = 0;
    cRight.gridy = rightRow;
    cRight.gridwidth = 1;
    JLabel osc2LinLabel = SwingSynthConfigDialog.label("Interpolation:");
    osc2LinLabel.setToolTipText(osc2LinBox.getToolTipText());
    SwingSynthConfigDialog.attachHoverHelp(
        osc2LinLabel,
        "<b>OSC 2 INTERPOLATION:</b> Selects pitch-shifting algorithm (Unchecked = zero-order hold/gritty; Checked = linear interpolation/smooth). — <i>Physical Deluge:</i> Hold shift + select INTERPOLATION category.");
    rightPanel.add(osc2LinLabel, cRight);
    cRight.gridx = 1;
    cRight.gridwidth = 2;
    rightPanel.add(osc2LinBox, cRight);
    rightRow++;

    cRight.gridx = 0;
    cRight.gridy = rightRow;
    cRight.gridwidth = 3;
    rightPanel.add(SwingSynthConfigDialog.sectionLabel("OSCILLATOR SYNC"), cRight);
    rightRow++;

    cRight.gridx = 0;
    cRight.gridy = rightRow;
    cRight.gridwidth = 1;
    JLabel syncLabel = SwingSynthConfigDialog.label("Hard Sync:");
    syncLabel.setToolTipText(syncBox.getToolTipText());
    SwingSynthConfigDialog.attachHoverHelp(
        syncLabel,
        "<b>OSCILLATOR SYNC:</b> Hard sync oscillator 2 phase frequency bounds to oscillator 1. — <i>Physical Deluge:</i> Hold shift + click OSC SYNC button.");
    rightPanel.add(syncLabel, cRight);
    cRight.gridx = 1;
    cRight.gridwidth = 2;
    rightPanel.add(syncBox, cRight);
    rightRow++;

    // ── BUILD RIGHT-MOST VISUALIZER PANEL (COLUMN 3) ──
    JPanel visualizerPanel = new JPanel(new GridBagLayout());
    visualizerPanel.setBackground(SwingSynthConfigDialog.BG_CARD);
    GridBagConstraints cVis = new GridBagConstraints();
    cVis.fill = GridBagConstraints.BOTH;
    cVis.weightx = 1.0;
    cVis.insets = new Insets(6, 10, 6, 10);
    cVis.gridx = 0;

    cVis.gridy = 0;
    cVis.weighty = 0.0;
    visualizerPanel.add(SwingSynthConfigDialog.sectionLabel("OSC 1 WAVETABLE VISUALIZER"), cVis);

    cVis.gridy = 1;
    cVis.weighty = 0.5;
    visualizerPanel.add(vis1, cVis);

    cVis.gridy = 2;
    cVis.weighty = 0.0;
    cVis.insets = new Insets(15, 10, 6, 10);
    visualizerPanel.add(SwingSynthConfigDialog.sectionLabel("OSC 2 WAVETABLE VISUALIZER"), cVis);

    cVis.gridy = 3;
    cVis.weighty = 0.5;
    cVis.insets = new Insets(6, 10, 6, 10);
    visualizerPanel.add(vis2, cVis);

    // ── Main 3-column side-by-side assembly ──
    setLayout(new GridBagLayout());
    GridBagConstraints cMain = new GridBagConstraints();
    cMain.fill = GridBagConstraints.BOTH;
    cMain.weighty = 1.0;
    cMain.gridy = 0;

    cMain.gridx = 0;
    cMain.weightx = 0.3;
    cMain.insets = new Insets(0, 0, 0, 10);
    add(leftPanel, cMain);

    cMain.gridx = 1;
    cMain.weightx = 0.3;
    cMain.insets = new Insets(0, 10, 0, 10);
    add(rightPanel, cMain);

    cMain.gridx = 2;
    cMain.weightx = 0.4;
    cMain.insets = new Insets(0, 10, 0, 0);
    add(visualizerPanel, cMain);
  }

  @Override
  public void addNotify() {
    super.addNotify();
    vis1.startAnimation();
    vis2.startAnimation();
  }

  @Override
  public void removeNotify() {
    super.removeNotify();
    vis1.stopAnimation();
    vis2.stopAnimation();
  }

  private void browseSingleSample(int oscIdx, int trackIdx, BridgeContract bridge) {
    JFileChooser fileChooser = new JFileChooser(PreferencesManager.getLibraryDir());
    fileChooser.setDialogTitle("Select Single WAV Sample");
    if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      File file = fileChooser.getSelectedFile();
      String absPath = file.getAbsolutePath();
      File libraryDir = PreferencesManager.getLibraryDir();
      String relPath = absPath;
      if (libraryDir != null && absPath.startsWith(libraryDir.getAbsolutePath())) {
        relPath = absPath.substring(libraryDir.getAbsolutePath().length());
        if (relPath.startsWith("/") || relPath.startsWith("\\")) {
          relPath = relPath.substring(1);
        }
      }

      if (oscIdx == 0) {
        model.setOsc1SamplePath(relPath);
        model.setOsc1Type("SAMPLE");
        model.getOsc1Zones().clear();
      } else {
        model.setOsc2SamplePath(relPath);
        model.setOsc2Type("SAMPLE");
        model.getOsc2Zones().clear();
      }

      try {
        Object eng = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
        if (eng instanceof org.deluge.engine.FirmwareAudioEngine engine) {
          if (trackIdx >= 0 && trackIdx < engine.sounds.size()) {
            var sound = engine.sounds.get(trackIdx);
            if (sound instanceof org.deluge.engine.FirmwareSound fs) {
              if (oscIdx == 0) {
                fs.fw2Sound.oscTypes[0] = org.deluge.firmware2.Oscillator.OscType.SAMPLE;
              } else {
                fs.fw2Sound.oscTypes[1] = org.deluge.firmware2.Oscillator.OscType.SAMPLE;
              }
              org.deluge.engine.FirmwareFactory.loadOscResources(model, fs);
            }
          }
        }
      } catch (Exception ignored) {
      }
    }
  }

  private void openKeyZoneMapper(int oscIdx, int trackIdx, BridgeContract bridge) {
    Window owner = SwingUtilities.getWindowAncestor(this);
    SwingKeyZoneMapperDialog dialog =
        new SwingKeyZoneMapperDialog(owner, model, oscIdx, trackIdx, bridge);
    dialog.setVisible(true);
  }

  private void styleOscButton(JButton btn, Color bg, Color fg) {
    btn.setBackground(bg);
    btn.setForeground(fg);
    btn.setFocusPainted(false);
    btn.setFont(new Font("SansSerif", Font.BOLD, 9));
    btn.setBorder(BorderFactory.createLineBorder(fg.darker(), 1));
    btn.setPreferredSize(new Dimension(85, 22));
  }
}
