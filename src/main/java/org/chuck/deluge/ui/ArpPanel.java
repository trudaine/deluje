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
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(6, 10, 6, 10);
    c.anchor = GridBagConstraints.WEST;
    int row = 0;
    int idx = trackIndex;

    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 3;
    add(SwingSynthConfigDialog.sectionLabel("ARPEGGIATOR"), c);
    row++;

    // ARP ON
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    JLabel arpLbl = SwingSynthConfigDialog.label("ARP ON:");
    arpLbl.setToolTipText("Enable the arpeggiator — plays notes in sequence automatically");
    add(arpLbl, c);
    c.gridx = 1;
    c.gridwidth = 2;
    JCheckBox arpBox = new JCheckBox();
    arpBox.setSelected(bridge.getArpOn(trackIndex));
    arpBox.setBackground(SwingSynthConfigDialog.BG_CARD);
    arpBox.setForeground(Color.WHITE);
    arpBox.setToolTipText("Enable the arpeggiator");
    arpBox.addActionListener(e -> bridge.setArpOn(trackIndex, arpBox.isSelected()));
    add(arpBox, c);
    row++;

    // Arp Mode
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    add(SwingSynthConfigDialog.label("Mode:"), c);
    c.gridx = 1;
    c.gridwidth = 2;
    String[] arpModes = {"UP", "DOWN", "UP_DOWN", "RANDOM", "WALK"};
    JComboBox<String> arpModeBox = new JComboBox<>(arpModes);
    arpModeBox.setSelectedIndex(bridge.getArpMode(trackIndex));
    arpModeBox.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    arpModeBox.setForeground(Color.WHITE);
    arpModeBox.setToolTipText("Arpeggiator note sequence direction");
    arpModeBox.addActionListener(e -> bridge.setArpMode(idx, arpModeBox.getSelectedIndex()));
    add(arpModeBox, c);
    row++;

    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
            "Rate (\u00d7):",
            "Arpeggiator speed multiplier (0.25\u00d7 to 4.00\u00d7)",
            25,
            400,
            (int) (bridge.getArpRate(trackIndex) * 100),
            val -> bridge.setArpRate(idx, val / 100.0),
            "\u00d70.01",
            "arpRate",
            projectModel,
            trackIndex);

    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
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
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    add(SwingSynthConfigDialog.label("Sync:"), c);
    c.gridx = 1;
    c.gridwidth = 2;
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
    add(syncCombo, c);
    row++;

    // Octaves
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    add(SwingSynthConfigDialog.label("Octaves:"), c);
    c.gridx = 1;
    c.gridwidth = 2;
    JComboBox<Integer> octCombo = new JComboBox<>(new Integer[] {1, 2, 3, 4});
    octCombo.setSelectedItem(bridge.getArpOctave(trackIndex));
    octCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    octCombo.setForeground(Color.WHITE);
    octCombo.setToolTipText("Number of octaves the arpeggiator spans");
    octCombo.addActionListener(e -> bridge.setArpOctave(idx, (Integer) octCombo.getSelectedItem()));
    add(octCombo, c);
    row++;

    // Note Mode
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    add(SwingSynthConfigDialog.label("Note Mode:"), c);
    c.gridx = 1;
    c.gridwidth = 2;
    String[] noteModes = {"UP", "DOWN", "UPDN", "RAND", "WLK1", "WLK2", "WLK3", "PLAY", "PATT"};
    JComboBox<String> noteModeCombo = new JComboBox<>(noteModes);
    noteModeCombo.setSelectedIndex(bridge.getArpNoteMode(trackIndex));
    noteModeCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    noteModeCombo.setForeground(Color.WHITE);
    noteModeCombo.setToolTipText("Note selection pattern within the arpeggiated chord");
    noteModeCombo.addActionListener(
        e -> bridge.setArpNoteMode(idx, noteModeCombo.getSelectedIndex()));
    add(noteModeCombo, c);
    row++;

    // Octave Mode
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    add(SwingSynthConfigDialog.label("Oct Mode:"), c);
    c.gridx = 1;
    c.gridwidth = 2;
    String[] octModes = {"UP", "DOWN", "UPDN", "ALT", "RAND"};
    JComboBox<String> octModeCombo = new JComboBox<>(octModes);
    octModeCombo.setSelectedIndex(bridge.getArpOctaveMode(trackIndex));
    octModeCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    octModeCombo.setForeground(Color.WHITE);
    octModeCombo.setToolTipText("Octave progression pattern");
    octModeCombo.addActionListener(
        e -> bridge.setArpOctaveMode(idx, octModeCombo.getSelectedIndex()));
    add(octModeCombo, c);
    row++;

    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
            "Repeat:",
            "Repeat each step N times (1-8)",
            1,
            8,
            bridge.getArpStepRepeat(trackIndex),
            val -> bridge.setArpStepRepeat(idx, val),
            "\u00d7",
            "arpStepRepeat",
            projectModel,
            trackIndex);

    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
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

    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
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

    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
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

    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
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

    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
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

    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
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

    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
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

    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
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

    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
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
  }
}
