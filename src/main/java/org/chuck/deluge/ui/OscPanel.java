package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;

/** OSC tab: oscMix, noiseVol, portamento, osc1/2 sample-playback controls. */
public class OscPanel extends JPanel {

  public OscPanel(
      SynthTrackModel model, BridgeContract bridge, int trackIndex, ProjectModel projectModel) {
    super(new GridBagLayout());
    setBackground(SwingSynthConfigDialog.BG_CARD);
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(6, 10, 6, 10);
    c.anchor = GridBagConstraints.WEST;
    int row = 0;

    // ── Mix ──
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 3;
    add(SwingSynthConfigDialog.sectionLabel("MIX"), c);
    row++;

    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
            "Osc Mix:",
            "Balance between oscillator 1 and 2 (0% = only osc1, 100% = only osc2)",
            0,
            100,
            (int) (bridge.getOscMix(trackIndex) * 100),
            val -> {
              model.setOscMix(val / 100f);
              bridge.setOscMix(trackIndex, val / 100f);
            },
            "%",
            "oscMix",
            projectModel,
            trackIndex);

    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
            "Noise Vol:",
            "Noise generator volume (0-100%). Adds white noise to the signal.",
            0,
            100,
            (int) (bridge.getNoiseVol(trackIndex) * 100),
            val -> {
              model.setNoiseVol(val / 100f);
              bridge.setNoiseVol(trackIndex, val / 100f);
            },
            "%",
            "noiseVol",
            projectModel,
            trackIndex);

    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
            "Portamento:",
            "Portamento / glide time (0-100%). Higher = slower pitch slides between notes.",
            0,
            100,
            (int) (bridge.getPortamento(trackIndex) * 100),
            val -> {
              model.setPortamento(val / 100f);
              bridge.setPortamento(trackIndex, val / 100f);
            },
            "%",
            "portamento",
            projectModel,
            trackIndex);

    // ── Oscillator 1 Sample Playback ──
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 3;
    add(SwingSynthConfigDialog.sectionLabel("OSCILLATOR 1 \u2014 SAMPLE PLAYBACK"), c);
    row++;

    // Loop mode
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    add(SwingSynthConfigDialog.label("Loop Mode:"), c);
    c.gridx = 1;
    c.gridwidth = 2;
    String[] loopModes = {"OFF", "LOOP", "ONESHOT"};
    JComboBox<String> osc1LoopCombo = new JComboBox<>(loopModes);
    osc1LoopCombo.setSelectedIndex(model.getOsc1LoopMode());
    osc1LoopCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    osc1LoopCombo.setForeground(Color.WHITE);
    osc1LoopCombo.setToolTipText(
        "Sample playback loop mode: OFF = play once, LOOP = repeat, ONESHOT = play entire sample ignoring note-off");
    osc1LoopCombo.addActionListener(
        e -> {
          int idx = osc1LoopCombo.getSelectedIndex();
          model.setOsc1LoopMode(idx);
          bridge.setOsc1LoopMode(trackIndex, idx);
        });
    add(osc1LoopCombo, c);
    row++;

    // Reversed
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    add(SwingSynthConfigDialog.label("Reversed:"), c);
    c.gridx = 1;
    c.gridwidth = 2;
    JCheckBox osc1RevBox = new JCheckBox("Play sample in reverse");
    osc1RevBox.setSelected(model.isOsc1Reversed());
    osc1RevBox.setBackground(SwingSynthConfigDialog.BG_CARD);
    osc1RevBox.setForeground(Color.WHITE);
    osc1RevBox.setToolTipText("Reverse sample playback direction");
    osc1RevBox.addActionListener(
        e -> {
          boolean sel = osc1RevBox.isSelected();
          model.setOsc1Reversed(sel);
          bridge.setOsc1Reversed(trackIndex, sel ? 1 : 0);
        });
    add(osc1RevBox, c);
    row++;

    // Time stretch
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    add(SwingSynthConfigDialog.label("Time Stretch:"), c);
    c.gridx = 1;
    c.gridwidth = 2;
    JCheckBox osc1TsBox = new JCheckBox("Enable time stretching");
    osc1TsBox.setSelected(model.isOsc1TimeStretch());
    osc1TsBox.setBackground(SwingSynthConfigDialog.BG_CARD);
    osc1TsBox.setForeground(Color.WHITE);
    osc1TsBox.setToolTipText("Time-stretch sample to match project tempo without changing pitch");
    osc1TsBox.addActionListener(
        e -> {
          boolean sel = osc1TsBox.isSelected();
          model.setOsc1TimeStretch(sel);
          bridge.setOsc1TimeStretch(trackIndex, sel ? 1 : 0);
        });
    add(osc1TsBox, c);
    row++;

    // Time stretch amount
    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
            "TS Amount:",
            "Time stretch amount (0-100%). Only relevant when time stretch is enabled.",
            0,
            100,
            (int) (model.getOsc1TimeStretchAmount() * 100),
            val -> {
              model.setOsc1TimeStretchAmount(val / 100f);
              bridge.setOsc1TimeStretchAmount(trackIndex, val / 100f);
            },
            "%",
            "osc1TsAmt",
            projectModel,
            trackIndex);

    // Cents
    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
            "Cents:",
            "Fine pitch detune in cents (-50 to +50). 100 cents = 1 semitone.",
            -50,
            50,
            model.getOsc1Cents(),
            val -> {
              model.setOsc1Cents(val);
              bridge.setOsc1Cents(trackIndex, val);
            },
            "",
            "osc1Cents",
            projectModel,
            trackIndex);

    // Linear interpolation
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    add(SwingSynthConfigDialog.label("Interpolation:"), c);
    c.gridx = 1;
    c.gridwidth = 2;
    JCheckBox osc1LinBox = new JCheckBox("Linear (smoother, less aliasing)");
    osc1LinBox.setSelected(model.isOsc1LinearInterpolation());
    osc1LinBox.setBackground(SwingSynthConfigDialog.BG_CARD);
    osc1LinBox.setForeground(Color.WHITE);
    osc1LinBox.setToolTipText(
        "Unchecked = zero-order hold (gritty); Checked = linear interpolation (smoother pitch shifting)");
    osc1LinBox.addActionListener(
        e -> {
          boolean sel = osc1LinBox.isSelected();
          model.setOsc1LinearInterpolation(sel);
          bridge.setOsc1LinearInterpolation(trackIndex, sel ? 1 : 0);
        });
    add(osc1LinBox, c);
    row++;

    // ── Oscillator 2 Sample Playback ──
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 3;
    add(SwingSynthConfigDialog.sectionLabel("OSCILLATOR 2 \u2014 SAMPLE PLAYBACK"), c);
    row++;

    // Loop mode
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    add(SwingSynthConfigDialog.label("Loop Mode:"), c);
    c.gridx = 1;
    c.gridwidth = 2;
    JComboBox<String> osc2LoopCombo = new JComboBox<>(loopModes);
    osc2LoopCombo.setSelectedIndex(model.getOsc2LoopMode());
    osc2LoopCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    osc2LoopCombo.setForeground(Color.WHITE);
    osc2LoopCombo.setToolTipText(
        "Sample playback loop mode: OFF = play once, LOOP = repeat, ONESHOT = play entire sample ignoring note-off");
    osc2LoopCombo.addActionListener(
        e -> {
          int idx = osc2LoopCombo.getSelectedIndex();
          model.setOsc2LoopMode(idx);
          bridge.setOsc2LoopMode(trackIndex, idx);
        });
    add(osc2LoopCombo, c);
    row++;

    // Reversed
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    add(SwingSynthConfigDialog.label("Reversed:"), c);
    c.gridx = 1;
    c.gridwidth = 2;
    JCheckBox osc2RevBox = new JCheckBox("Play sample in reverse");
    osc2RevBox.setSelected(model.isOsc2Reversed());
    osc2RevBox.setBackground(SwingSynthConfigDialog.BG_CARD);
    osc2RevBox.setForeground(Color.WHITE);
    osc2RevBox.setToolTipText("Reverse sample playback direction");
    osc2RevBox.addActionListener(
        e -> {
          boolean sel = osc2RevBox.isSelected();
          model.setOsc2Reversed(sel);
          bridge.setOsc2Reversed(trackIndex, sel ? 1 : 0);
        });
    add(osc2RevBox, c);
    row++;

    // Time stretch
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    add(SwingSynthConfigDialog.label("Time Stretch:"), c);
    c.gridx = 1;
    c.gridwidth = 2;
    JCheckBox osc2TsBox = new JCheckBox("Enable time stretching");
    osc2TsBox.setSelected(model.isOsc2TimeStretch());
    osc2TsBox.setBackground(SwingSynthConfigDialog.BG_CARD);
    osc2TsBox.setForeground(Color.WHITE);
    osc2TsBox.setToolTipText("Time-stretch sample to match project tempo without changing pitch");
    osc2TsBox.addActionListener(
        e -> {
          boolean sel = osc2TsBox.isSelected();
          model.setOsc2TimeStretch(sel);
          bridge.setOsc2TimeStretch(trackIndex, sel ? 1 : 0);
        });
    add(osc2TsBox, c);
    row++;

    // Time stretch amount
    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
            "TS Amount:",
            "Time stretch amount (0-100%). Only relevant when time stretch is enabled.",
            0,
            100,
            (int) (model.getOsc2TimeStretchAmount() * 100),
            val -> {
              model.setOsc2TimeStretchAmount(val / 100f);
              bridge.setOsc2TimeStretchAmount(trackIndex, val / 100f);
            },
            "%",
            "osc2TsAmt",
            projectModel,
            trackIndex);

    // Cents
    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
            "Cents:",
            "Fine pitch detune in cents (-50 to +50). 100 cents = 1 semitone.",
            -50,
            50,
            model.getOsc2Cents(),
            val -> {
              model.setOsc2Cents(val);
              bridge.setOsc2Cents(trackIndex, val);
            },
            "",
            "osc2Cents",
            projectModel,
            trackIndex);

    // Linear interpolation
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    add(SwingSynthConfigDialog.label("Interpolation:"), c);
    c.gridx = 1;
    c.gridwidth = 2;
    JCheckBox osc2LinBox = new JCheckBox("Linear (smoother, less aliasing)");
    osc2LinBox.setSelected(model.isOsc2LinearInterpolation());
    osc2LinBox.setBackground(SwingSynthConfigDialog.BG_CARD);
    osc2LinBox.setForeground(Color.WHITE);
    osc2LinBox.setToolTipText(
        "Unchecked = zero-order hold (gritty); Checked = linear interpolation (smoother pitch shifting)");
    osc2LinBox.addActionListener(
        e -> {
          boolean sel = osc2LinBox.isSelected();
          model.setOsc2LinearInterpolation(sel);
          bridge.setOsc2LinearInterpolation(trackIndex, sel ? 1 : 0);
        });
    add(osc2LinBox, c);
    row++;

    // Transpose
    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
            "Transpose:",
            "Oscillator 2 transpose in semitones (-24 to +24).",
            -24,
            24,
            model.getOsc2Transpose(),
            val -> {
              model.setOsc2Transpose(val);
              bridge.setOsc2Transpose(trackIndex, val);
            },
            "st",
            "osc2Transpose",
            projectModel,
            trackIndex);

    // ── Oscillator Sync ──
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 3;
    add(SwingSynthConfigDialog.sectionLabel("OSCILLATOR SYNC"), c);
    row++;

    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    add(SwingSynthConfigDialog.label("Hard Sync:"), c);
    c.gridx = 1;
    c.gridwidth = 2;
    JCheckBox syncBox = new JCheckBox("Reset osc 2 phase from osc 1 (hard sync)");
    syncBox.setSelected(model.isOscillatorSync());
    syncBox.setBackground(SwingSynthConfigDialog.BG_CARD);
    syncBox.setForeground(Color.WHITE);
    syncBox.setToolTipText(
        "Oscillator hard sync: osc 2's phase is reset by osc 1's cycle, creating characteristic sync sweep sounds");
    syncBox.addActionListener(
        e -> {
          boolean sel = syncBox.isSelected();
          model.setOscillatorSync(sel);
          bridge.setOscillatorSync(trackIndex, sel ? 1 : 0);
        });
    add(syncBox, c);
    row++;
  }
}
