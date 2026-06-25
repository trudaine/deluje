package org.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.deluge.BridgeContract;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;

/** ARP tab: arpeggiator toggle, mode, rate, gate, sync, octaves, etc. (model-backed). */
public class ArpPanel extends JPanel {

  // Combo order: OFF, 1/1, 1/2, 1/2T, 1/4, 1/4T, 1/8, 1/8T, 1/16, 1/16T, 1/32, 1/32T, 1/64.
  // ArpModel.syncLevel is a note-division denominator; triplets map to syncType=1.
  private static final int[] SYNC_DIVISIONS = {0, 1, 2, 2, 4, 4, 8, 8, 16, 16, 32, 32, 64};
  private static final boolean[] SYNC_TRIPLET = {
    false, false, false, true, false, true, false, true, false, true, false, true, false
  };

  private static int syncComboIndexFor(int division, int syncType) {
    for (int i = 0; i < SYNC_DIVISIONS.length; i++) {
      if (SYNC_DIVISIONS[i] == division && SYNC_TRIPLET[i] == (syncType == 1)) {
        return i;
      }
    }
    return 0;
  }

  public ArpPanel(
      SynthTrackModel model, BridgeContract bridge, int trackIndex, ProjectModel projectModel) {
    super(new GridBagLayout());
    setBackground(SwingSynthConfigDialog.BG_CARD);
    if (model.getArp() == null) {
      model.setArp(org.deluge.model.ArpModel.defaultConfig());
    }
    org.deluge.model.ArpModel arp = model.getArp();

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
    arpBox.setSelected(arp.active());
    arpBox.setBackground(SwingSynthConfigDialog.BG_CARD);
    arpBox.setForeground(Color.WHITE);
    arpBox.setToolTipText("Enable the arpeggiator");
    arpBox.addActionListener(
        e -> model.setArp(model.getArp().toBuilder().active(arpBox.isSelected()).build()));
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
    arpModeBox.setSelectedIndex(Math.max(0, java.util.Arrays.asList(arpModes).indexOf(arp.mode())));
    arpModeBox.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    arpModeBox.setForeground(Color.WHITE);
    arpModeBox.setToolTipText("Arpeggiator note sequence direction");
    arpModeBox.addActionListener(
        e ->
            model.setArp(
                model.getArp().toBuilder().mode((String) arpModeBox.getSelectedItem()).build()));
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
            (int) (arp.rate() * 100),
            val -> model.setArp(model.getArp().toBuilder().rate((float) (val / 100.0)).build()),
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
            (int) (arp.gate() * 100),
            val -> model.setArp(model.getArp().toBuilder().gate((float) (val / 100.0)).build()),
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
    syncCombo.setSelectedIndex(syncComboIndexFor(arp.syncLevel(), arp.syncType()));
    syncCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    syncCombo.setForeground(Color.WHITE);
    syncCombo.setToolTipText("Sync arpeggiator rate to note division (overrides Rate slider)");
    syncCombo.addActionListener(
        e -> {
          int sel = syncCombo.getSelectedIndex();
          model.setArp(
              model.getArp().toBuilder()
                  .syncLevel(SYNC_DIVISIONS[sel])
                  .syncType(SYNC_TRIPLET[sel] ? 1 : 0)
                  .build());
        });
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
    octCombo.setSelectedItem(Math.max(1, Math.min(4, arp.octaves())));
    octCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    octCombo.setForeground(Color.WHITE);
    octCombo.setToolTipText("Number of octaves the arpeggiator spans");
    octCombo.addActionListener(
        e ->
            model.setArp(
                model.getArp().toBuilder().octaves((Integer) octCombo.getSelectedItem()).build()));
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
    noteModeCombo.setSelectedIndex(
        Math.max(0, java.util.Arrays.asList(noteModes).indexOf(arp.noteMode())));
    noteModeCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    noteModeCombo.setForeground(Color.WHITE);
    noteModeCombo.setToolTipText("Note selection pattern within the arpeggiated chord");
    noteModeCombo.addActionListener(
        e ->
            model.setArp(
                model.getArp().toBuilder()
                    .noteMode((String) noteModeCombo.getSelectedItem())
                    .build()));
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
    octModeCombo.setSelectedIndex(
        Math.max(0, java.util.Arrays.asList(octModes).indexOf(arp.octaveMode())));
    octModeCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    octModeCombo.setForeground(Color.WHITE);
    octModeCombo.setToolTipText("Octave progression pattern");
    octModeCombo.addActionListener(
        e ->
            model.setArp(
                model.getArp().toBuilder()
                    .octaveMode((String) octModeCombo.getSelectedItem())
                    .build()));
    leftPanel.add(octModeCombo, lc);
    leftRow++;

    // Chord Type
    lc.gridx = 0;
    lc.gridy = leftRow;
    lc.gridwidth = 1;
    leftPanel.add(SwingSynthConfigDialog.label("Chord Type:"), lc);
    lc.gridx = 1;
    lc.gridwidth = 2;
    String[] chordTypes = {
      "Single",
      "Octaves",
      "Fifths",
      "Major",
      "Minor",
      "Suspended",
      "Seventh",
      "Minor 7th",
      "Major 7th"
    };
    JComboBox<String> chordCombo = new JComboBox<>(chordTypes);
    chordCombo.setSelectedIndex(Math.max(0, Math.min(8, arp.chordType())));
    chordCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    chordCombo.setForeground(Color.WHITE);
    chordCombo.setToolTipText("Select base chord type for arpeggiation");
    chordCombo.addActionListener(
        e -> {
          int oldVal = model.getArp().chordType();
          int newVal = chordCombo.getSelectedIndex();
          if (oldVal == newVal) return;
          model.setArp(model.getArp().toBuilder().chordType(newVal).build());
          if (projectModel.getUndoRedoStack() != null) {
            projectModel
                .getUndoRedoStack()
                .push(
                    new Consequence.SynthParamConsequence(
                        projectModel,
                        trackIndex,
                        "arpChordType",
                        oldVal,
                        newVal,
                        System.currentTimeMillis()));
          }
        });
    leftPanel.add(chordCombo, lc);
    leftRow++;

    // Secondary Octaves (numOctaves)
    lc.gridx = 0;
    lc.gridy = leftRow;
    lc.gridwidth = 1;
    leftPanel.add(SwingSynthConfigDialog.label("Sec Octaves:"), lc);
    lc.gridx = 1;
    lc.gridwidth = 2;
    JComboBox<Integer> numOctCombo = new JComboBox<>(new Integer[] {1, 2, 3, 4});
    numOctCombo.setSelectedItem(Math.max(1, Math.min(4, arp.numOctaves())));
    numOctCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    numOctCombo.setForeground(Color.WHITE);
    numOctCombo.setToolTipText("Secondary arpeggiator octave span");
    numOctCombo.addActionListener(
        e -> {
          int oldVal = model.getArp().numOctaves();
          int newVal = (Integer) numOctCombo.getSelectedItem();
          if (oldVal == newVal) return;
          model.setArp(model.getArp().toBuilder().numOctaves(newVal).build());
          if (projectModel.getUndoRedoStack() != null) {
            projectModel
                .getUndoRedoStack()
                .push(
                    new Consequence.SynthParamConsequence(
                        projectModel,
                        trackIndex,
                        "arpNumOctaves",
                        oldVal,
                        newVal,
                        System.currentTimeMillis()));
          }
        });
    leftPanel.add(numOctCombo, lc);
    leftRow++;

    // Kit Arpeggiator Checkbox
    lc.gridx = 0;
    lc.gridy = leftRow;
    lc.gridwidth = 1;
    leftPanel.add(SwingSynthConfigDialog.label("Kit Arp:"), lc);
    lc.gridx = 1;
    lc.gridwidth = 2;
    JCheckBox kitArpBox = new JCheckBox();
    kitArpBox.setSelected(arp.kitArp() == 1);
    kitArpBox.setBackground(SwingSynthConfigDialog.BG_CARD);
    kitArpBox.setForeground(Color.WHITE);
    kitArpBox.setToolTipText("Enable arpeggiation on drum kit track slots");
    kitArpBox.addActionListener(
        e -> {
          int oldVal = model.getArp().kitArp();
          int newVal = kitArpBox.isSelected() ? 1 : 0;
          if (oldVal == newVal) return;
          model.setArp(model.getArp().toBuilder().kitArp(newVal).build());
          if (projectModel.getUndoRedoStack() != null) {
            projectModel
                .getUndoRedoStack()
                .push(
                    new Consequence.SynthParamConsequence(
                        projectModel,
                        trackIndex,
                        "arpKitArp",
                        oldVal,
                        newVal,
                        System.currentTimeMillis()));
          }
        });
    leftPanel.add(kitArpBox, lc);
    leftRow++;

    // Randomizer Lock Checkbox
    lc.gridx = 0;
    lc.gridy = leftRow;
    lc.gridwidth = 1;
    leftPanel.add(SwingSynthConfigDialog.label("Rand Lock:"), lc);
    lc.gridx = 1;
    lc.gridwidth = 2;
    JCheckBox randLockBox = new JCheckBox();
    randLockBox.setSelected(arp.randomizerLock() == 1);
    randLockBox.setBackground(SwingSynthConfigDialog.BG_CARD);
    randLockBox.setForeground(Color.WHITE);
    randLockBox.setToolTipText("Lock the step-level randomizations into a repeating loop pattern");
    leftPanel.add(randLockBox, lc);
    leftRow++;

    // Clear Step-Locks Button
    lc.gridx = 0;
    lc.gridy = leftRow;
    lc.gridwidth = 3;
    JButton clearLocksBtn = new JButton("CLEAR RANDOM STEP-LOCKS");
    clearLocksBtn.setBackground(new Color(0x6e, 0x2e, 0x2e));
    clearLocksBtn.setForeground(Color.WHITE);
    clearLocksBtn.setOpaque(true);
    clearLocksBtn.setBorderPainted(false);
    clearLocksBtn.setToolTipText("Instantly clear all step-level randomization pattern locks");
    clearLocksBtn.addActionListener(
        e -> {
          model.setArp(
              model.getArp().toBuilder()
                  .randomizerLock(0)
                  .lastLockedBassProb(0)
                  .lockedBassProbArray("00000000000000000000000000000000")
                  .lastLockedChordProb(0)
                  .lockedChordProbArray("00000000000000000000000000000000")
                  .lastLockedGateSpread(0)
                  .lockedGateSpreadArray("00000000000000000000000000000000")
                  .lastLockedGlideProb(0)
                  .lockedGlideProbArray("00000000000000000000000000000000")
                  .lastLockedNoteProb(0)
                  .lockedNoteProbArray("00000000000000000000000000000000")
                  .lastLockedOctaveSpread(0)
                  .lockedOctaveSpreadArray("00000000000000000000000000000000")
                  .lastLockedRatchetProb(0)
                  .lockedRatchetProbArray("00000000000000000000000000000000")
                  .lastLockedReverseProb(0)
                  .lockedReverseProbArray("00000000000000000000000000000000")
                  .lastLockedSwapProb(0)
                  .lockedSwapProbArray("00000000000000000000000000000000")
                  .lastLockedVelocitySpread(0)
                  .lockedVelocitySpreadArray("00000000000000000000000000000000")
                  .build());
          randLockBox.setSelected(false);
          JOptionPane.showMessageDialog(
              this,
              "All step-level randomization locks cleared!",
              "Locks Cleared",
              JOptionPane.INFORMATION_MESSAGE);
        });
    randLockBox.addActionListener(
        e -> {
          int oldVal = model.getArp().randomizerLock();
          int newVal = randLockBox.isSelected() ? 1 : 0;
          if (oldVal == newVal) return;
          model.setArp(model.getArp().toBuilder().randomizerLock(newVal).build());
          if (projectModel.getUndoRedoStack() != null) {
            projectModel
                .getUndoRedoStack()
                .push(
                    new Consequence.SynthParamConsequence(
                        projectModel,
                        trackIndex,
                        "arpRandomizerLock",
                        oldVal,
                        newVal,
                        System.currentTimeMillis()));
          }
        });
    leftPanel.add(clearLocksBtn, lc);
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
            Math.max(1, arp.stepRepeat()),
            val -> model.setArp(model.getArp().toBuilder().stepRepeat(val).build()),
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
            arp.rhythmIndex(),
            val -> model.setArp(model.getArp().toBuilder().rhythmIndex(val).build()),
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
            Math.max(1, arp.seqLength()),
            val -> model.setArp(model.getArp().toBuilder().seqLength(val).build()),
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
            (int) (arp.octaveSpread() * 100),
            val ->
                model.setArp(
                    model.getArp().toBuilder().octaveSpread((float) (val / 100.0)).build()),
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
            (int) (arp.gateSpread() * 100),
            val ->
                model.setArp(model.getArp().toBuilder().gateSpread((float) (val / 100.0)).build()),
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
            (int) (arp.velSpread() * 100),
            val ->
                model.setArp(model.getArp().toBuilder().velSpread((float) (val / 100.0)).build()),
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
            arp.ratchetAmount(),
            val -> model.setArp(model.getArp().toBuilder().ratchetAmount(val).build()),
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
            (int) ((arp.noteProbability() <= 0f ? 1f : arp.noteProbability()) * 100),
            val ->
                model.setArp(
                    model.getArp().toBuilder().noteProbability((float) (val / 100.0)).build()),
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
            Math.max(1, arp.chordPolyphony()),
            val -> model.setArp(model.getArp().toBuilder().chordPolyphony(val).build()),
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
            (int) (arp.chordProbability() * 100),
            val ->
                model.setArp(
                    model.getArp().toBuilder().chordProbability((float) (val / 100.0)).build()),
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
