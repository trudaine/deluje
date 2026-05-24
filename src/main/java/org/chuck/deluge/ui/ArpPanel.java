package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;

/** ARP tab: arpeggiator toggle, mode, rate, gate, sync, octaves, etc. */
public class ArpPanel extends JPanel {

  public ArpPanel(
      SynthTrackModel model, BridgeContract bridge, int trackIndex, ProjectModel projectModel) {
    super(new GridBagLayout());
    setBackground(SwingSynthConfigDialog.BG_CARD);

    JPanel leftPanel = new JPanel(new GridBagLayout());
    leftPanel.setBackground(SwingSynthConfigDialog.BG_CARD);
    GridBagConstraints lc = new GridBagConstraints();
    lc.fill = GridBagConstraints.HORIZONTAL;
    lc.insets = new Insets(6, 10, 6, 10);
    lc.anchor = GridBagConstraints.WEST;
    int leftRow = 0;
    int idx = trackIndex;

    JPanel rightPanel = new JPanel(new GridBagLayout());
    rightPanel.setBackground(SwingSynthConfigDialog.BG_CARD);
    GridBagConstraints rc = new GridBagConstraints();
    rc.fill = GridBagConstraints.HORIZONTAL;
    rc.insets = new Insets(6, 10, 6, 10);
    rc.anchor = GridBagConstraints.WEST;
    int rightRow = 0;

    // ── Left Column: Arpeggiator Settings ──

    lc.gridx = 0;
    lc.gridy = leftRow;
    lc.gridwidth = 3;
    leftPanel.add(SwingSynthConfigDialog.sectionLabel("ARPEGGIATOR STATE"), lc);
    leftRow++;

    // ARP ON
    lc.gridx = 0;
    lc.gridy = leftRow;
    lc.gridwidth = 1;
    JLabel arpLbl = SwingSynthConfigDialog.label("ARP ON:");
    arpLbl.setToolTipText("Enable the arpeggiator — plays notes in sequence automatically");
    leftPanel.add(arpLbl, lc);
    lc.gridx = 1;
    lc.gridwidth = 2;
    JCheckBox arpBox = new JCheckBox();
    arpBox.setSelected(bridge.getArpOn(trackIndex));
    arpBox.setBackground(SwingSynthConfigDialog.BG_CARD);
    arpBox.setForeground(Color.WHITE);
    arpBox.setToolTipText("Enable the arpeggiator");
    arpBox.addActionListener(e -> bridge.setArpOn(trackIndex, arpBox.isSelected()));
    leftPanel.add(arpBox, lc);
    leftRow++;

    // Arp Mode
    lc.gridx = 0;
    lc.gridy = leftRow;
    lc.gridwidth = 1;
    leftPanel.add(SwingSynthConfigDialog.label("Mode:"), lc);
    lc.gridx = 1;
    lc.gridwidth = 2;
    String[] arpModes = {"UP", "DOWN", "UP_DOWN", "RANDOM", "WALK"};
    JComboBox<String> arpModeBox = new JComboBox<>(arpModes);
    arpModeBox.setSelectedIndex(bridge.getArpMode(trackIndex));
    arpModeBox.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    arpModeBox.setForeground(Color.WHITE);
    arpModeBox.setToolTipText("Arpeggiator note sequence direction");
    arpModeBox.addActionListener(e -> bridge.setArpMode(idx, arpModeBox.getSelectedIndex()));
    leftPanel.add(arpModeBox, lc);
    leftRow++;

    leftRow =
        SwingSynthConfigDialog.addSlider(
            leftPanel,
            lc,
            leftRow,
            "Rate (×):",
            "Arpeggiator speed multiplier (0.25× to 4.00×)",
            25,
            400,
            (int) (bridge.getArpRate(trackIndex) * 100),
            val -> bridge.setArpRate(idx, val / 100.0),
            "×0.01",
            "arpRate",
            projectModel,
            trackIndex);

    leftRow =
        SwingSynthConfigDialog.addSlider(
            leftPanel,
            lc,
            leftRow,
            "Gate:",
            "Note-on duration as percentage of step (10%-100%)",
            10,
            100,
            (int) (bridge.getArpGate(trackIndex) * 100),
            val -> bridge.setArpGate(idx, val / 100.0),
            "%",
            "arpGate",
            projectModel,
            trackIndex);

    // Sync
    lc.gridx = 0;
    lc.gridy = leftRow;
    lc.gridwidth = 1;
    leftPanel.add(SwingSynthConfigDialog.label("Sync:"), lc);
    lc.gridx = 1;
    lc.gridwidth = 2;
    String[] syncRates = {
      "OFF", "1/1", "1/2", "1/2T", "1/4", "1/4T", "1/8", "1/8T", "1/16", "1/16T", "1/32", "1/32T",
      "1/64"
    };
    JComboBox<String> syncCombo = new JComboBox<>(syncRates);
    syncCombo.setSelectedIndex(bridge.getArpSyncLevel(trackIndex));
    syncCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    syncCombo.setForeground(Color.WHITE);
    syncCombo.setToolTipText("Sync arpeggiator rate to note division (overrides Rate slider)");
    syncCombo.addActionListener(e -> bridge.setArpSyncLevel(idx, syncCombo.getSelectedIndex()));
    leftPanel.add(syncCombo, lc);
    leftRow++;

    // Octaves
    lc.gridx = 0;
    lc.gridy = leftRow;
    lc.gridwidth = 1;
    leftPanel.add(SwingSynthConfigDialog.label("Octaves:"), lc);
    lc.gridx = 1;
    lc.gridwidth = 2;
    JComboBox<Integer> octCombo = new JComboBox<>(new Integer[] {1, 2, 3, 4});
    octCombo.setSelectedItem(bridge.getArpOctave(trackIndex));
    octCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    octCombo.setForeground(Color.WHITE);
    octCombo.setToolTipText("Number of octaves the arpeggiator spans");
    octCombo.addActionListener(e -> bridge.setArpOctave(idx, (Integer) octCombo.getSelectedItem()));
    leftPanel.add(octCombo, lc);
    leftRow++;

    // Note Mode
    lc.gridx = 0;
    lc.gridy = leftRow;
    lc.gridwidth = 1;
    leftPanel.add(SwingSynthConfigDialog.label("Note Mode:"), lc);
    lc.gridx = 1;
    lc.gridwidth = 2;
    String[] noteModes = {"UP", "DOWN", "UPDN", "RAND", "WLK1", "WLK2", "WLK3", "PLAY", "PATT"};
    JComboBox<String> noteModeCombo = new JComboBox<>(noteModes);
    noteModeCombo.setSelectedIndex(bridge.getArpNoteMode(trackIndex));
    noteModeCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    noteModeCombo.setForeground(Color.WHITE);
    noteModeCombo.setToolTipText("Note selection pattern within the arpeggiated chord");
    noteModeCombo.addActionListener(
        e -> bridge.setArpNoteMode(idx, noteModeCombo.getSelectedIndex()));
    leftPanel.add(noteModeCombo, lc);
    leftRow++;

    // Octave Mode
    lc.gridx = 0;
    lc.gridy = leftRow;
    lc.gridwidth = 1;
    leftPanel.add(SwingSynthConfigDialog.label("Oct Mode:"), lc);
    lc.gridx = 1;
    lc.gridwidth = 2;
    String[] octModes = {"UP", "DOWN", "UPDN", "ALT", "RAND"};
    JComboBox<String> octModeCombo = new JComboBox<>(octModes);
    octModeCombo.setSelectedIndex(bridge.getArpOctaveMode(trackIndex));
    octModeCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    octModeCombo.setForeground(Color.WHITE);
    octModeCombo.setToolTipText("Octave progression pattern");
    octModeCombo.addActionListener(
        e -> bridge.setArpOctaveMode(idx, octModeCombo.getSelectedIndex()));
    leftPanel.add(octModeCombo, lc);
    leftRow++;

    // ── Right Column: Arpeggiator Parameters ──

    rc.gridx = 0;
    rc.gridy = rightRow;
    rc.gridwidth = 3;
    rightPanel.add(SwingSynthConfigDialog.sectionLabel("ARPEGGIATOR PARAMETERS"), rc);
    rightRow++;

    rightRow =
        SwingSynthConfigDialog.addSlider(
            rightPanel,
            rc,
            rightRow,
            "Repeat:",
            "Repeat each step N times (1-8)",
            1,
            8,
            bridge.getArpStepRepeat(trackIndex),
            val -> bridge.setArpStepRepeat(idx, val),
            "×",
            "arpStepRepeat",
            projectModel,
            trackIndex);

    rightRow =
        SwingSynthConfigDialog.addSlider(
            rightPanel,
            rc,
            rightRow,
            "Rhythm:",
            "Rhythm silence pattern index (0-49). 0 = all play, 1-50 = firmware patterns.",
            0,
            49,
            bridge.getArpRhythm(trackIndex),
            val -> bridge.setArpRhythm(idx, val),
            "",
            "arpRhythm",
            projectModel,
            trackIndex);

    rightRow =
        SwingSynthConfigDialog.addSlider(
            rightPanel,
            rc,
            rightRow,
            "Seq Len:",
            "Number of active steps in the arpeggiator sequence (1-16)",
            1,
            16,
            bridge.getArpSeqLength(trackIndex),
            val -> bridge.setArpSeqLength(idx, val),
            "",
            "arpSeqLength",
            projectModel,
            trackIndex);

    rightRow =
        SwingSynthConfigDialog.addSlider(
            rightPanel,
            rc,
            rightRow,
            "Oct Spread:",
            "Randomize octave offset per note (0-100%). Higher = wilder octave jumps.",
            0,
            100,
            (int) (bridge.getArpOctaveSpread(trackIndex) * 100),
            val -> bridge.setArpOctaveSpread(idx, val / 100.0),
            "%",
            "arpOctaveSpread",
            projectModel,
            trackIndex);

    rightRow =
        SwingSynthConfigDialog.addSlider(
            rightPanel,
            rc,
            rightRow,
            "Gate Spread:",
            "Randomize note gate duration per step (0-100%). Higher = more timing variation.",
            0,
            100,
            (int) (bridge.getArpGateSpread(trackIndex) * 100),
            val -> bridge.setArpGateSpread(idx, val / 100.0),
            "%",
            "arpGateSpread",
            projectModel,
            trackIndex);

    rightRow =
        SwingSynthConfigDialog.addSlider(
            rightPanel,
            rc,
            rightRow,
            "Vel Spread:",
            "Randomize note velocity per step (0-100%). Higher = more dynamic contrast.",
            0,
            100,
            (int) (bridge.getArpVelSpread(trackIndex) * 100),
            val -> bridge.setArpVelSpread(idx, val / 100.0),
            "%",
            "arpVelSpread",
            projectModel,
            trackIndex);

    rightRow =
        SwingSynthConfigDialog.addSlider(
            rightPanel,
            rc,
            rightRow,
            "Ratchet:",
            "Sub-divide each step into N+1 mini-notes (0-4). 1 = double-trigger, 4 = machine-gun.",
            0,
            4,
            bridge.getArpRatchet(trackIndex),
            val -> bridge.setArpRatchet(idx, val),
            "x",
            "arpRatchet",
            projectModel,
            trackIndex);

    rightRow =
        SwingSynthConfigDialog.addSlider(
            rightPanel,
            rc,
            rightRow,
            "Note Prob:",
            "Probability that each step plays a note (0-100%). 100% = always play. Applied after rhythm silences.",
            0,
            100,
            (int) (bridge.getArpNoteProbability(trackIndex) * 100),
            val -> bridge.setArpNoteProbability(idx, val / 100.0),
            "%",
            "arpNoteProb",
            projectModel,
            trackIndex);

    rightRow =
        SwingSynthConfigDialog.addSlider(
            rightPanel,
            rc,
            rightRow,
            "Chord Poly:",
            "Maximum number of notes in a chord (1-8). 1 = single note. Only effective with Chord Probability > 0%.",
            1,
            8,
            bridge.getArpChordPoly(trackIndex),
            val -> bridge.setArpChordPoly(idx, val),
            "x",
            "arpChordPoly",
            projectModel,
            trackIndex);

    rightRow =
        SwingSynthConfigDialog.addSlider(
            rightPanel,
            rc,
            rightRow,
            "Chord Prob:",
            "Probability that a step plays a chord instead of a single note (0-100%). Chord size determined by Chord Poly.",
            0,
            100,
            (int) (bridge.getArpChordProb(trackIndex) * 100),
            val -> bridge.setArpChordProb(idx, val / 100.0),
            "%",
            "arpChordProb",
            projectModel,
            trackIndex);

    // ── Mount Left and Right Side-by-Side inside parent ArpPanel ──
    setLayout(new GridBagLayout());
    GridBagConstraints mc = new GridBagConstraints();
    mc.fill = GridBagConstraints.BOTH;
    mc.gridy = 0;
    mc.weighty = 1.0;

    mc.gridx = 0;
    mc.weightx = 0.48;
    add(leftPanel, mc);

    mc.gridx = 1;
    mc.weightx = 0.04;
    mc.insets = new Insets(0, 15, 0, 15);
    JSeparator separator = new JSeparator(JSeparator.VERTICAL);
    separator.setForeground(new Color(0x33, 0x33, 0x35));
    add(separator, mc);

    mc.gridx = 2;
    mc.weightx = 0.48;
    mc.insets = new Insets(0, 0, 0, 0);
    add(rightPanel, mc);
  }
}
