package org.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.deluge.BridgeContract;
import org.deluge.model.SynthTrackModel;

/** ALGORITHM tab: DX7 algorithm grid (0-31) + STK engine selector + engine type. */
public class AlgorithmPanel extends JPanel {

  public AlgorithmPanel(SynthTrackModel model, BridgeContract bridge, int trackIndex) {
    super(new BorderLayout(8, 8));
    setBackground(SwingSynthConfigDialog.BG_CARD);
    setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

    // ── Top: STK engine selector ──
    JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
    topPanel.setBackground(SwingSynthConfigDialog.BG_CARD);
    topPanel.add(SwingSynthConfigDialog.sectionLabel("ENGINE:"));

    String[] stkNames = {"DX7 FM (6-op)", "Mandolin", "Rhodey EP", "ModalBar", "Moog Bass"};
    int[] stkValues = {0, 10, 11, 12, 13};
    JComboBox<String> engineCombo = new JComboBox<>(stkNames);
    engineCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    engineCombo.setForeground(Color.WHITE);
    int curAlgo = model.getSynthAlgorithm();
    int curEngineIdx = 0;
    for (int i = 0; i < stkValues.length; i++) {
      if (stkValues[i] == curAlgo || (i == 0 && curAlgo < 10)) {
        curEngineIdx = i;
        break;
      }
    }
    engineCombo.setSelectedIndex(curEngineIdx);
    engineCombo.addActionListener(
        e -> {
          int ei = engineCombo.getSelectedIndex();
          int algoVal = stkValues[ei];
          model.setSynthAlgorithm(algoVal);
        });
    topPanel.add(engineCombo);

    // Engine Type
    topPanel.add(Box.createHorizontalStrut(16));
    topPanel.add(SwingSynthConfigDialog.sectionLabel("ENGINE TYPE:"));
    String[] engineTypeNames = {
      "Auto (firmware default)", "Modern (32-bit float)", "Vintage (14-bit ENV)"
    };
    JComboBox<String> engineTypeCombo = new JComboBox<>(engineTypeNames);
    engineTypeCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    engineTypeCombo.setForeground(Color.WHITE);
    int curEngineType = model.getEngineType();
    engineTypeCombo.setSelectedIndex(curEngineType + 1);
    engineTypeCombo.addActionListener(
        ev -> {
          int idx = engineTypeCombo.getSelectedIndex();
          int typeVal = idx - 1;
          model.setEngineType(typeVal);
        });
    topPanel.add(engineTypeCombo);
    add(topPanel, BorderLayout.NORTH);

    // ── Center: 32-algorithm grid ──
    JPanel gridPanel = new JPanel(new GridLayout(16, 2, 6, 6));
    gridPanel.setBackground(SwingSynthConfigDialog.BG_CARD);
    JScrollPane scroll = new JScrollPane(gridPanel);
    scroll.setPreferredSize(new Dimension(700, 400));
    scroll.setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(0x44, 0x44, 0x44)), "DX7 ALGORITHMS (0–31)"));
    scroll.getViewport().setBackground(SwingSynthConfigDialog.BG_CARD);

    for (int algo = 0; algo < 32; algo++) {
      final int a = algo;
      JPanel algoCard = new JPanel(new BorderLayout(4, 2));
      algoCard.setBackground(
          curAlgo == a ? new Color(0x3a, 0x5a, 0x3a) : new Color(0x2a, 0x2a, 0x2a));
      algoCard.setBorder(
          BorderFactory.createLineBorder(
              curAlgo == a ? Color.CYAN : new Color(0x44, 0x44, 0x44), 1));

      JTextArea algoPreview = new JTextArea(formatAlgorithmMini(a));
      algoPreview.setEditable(false);
      algoPreview.setBackground(SwingSynthConfigDialog.BG_CARD);
      algoPreview.setForeground(Color.LIGHT_GRAY);
      algoPreview.setFont(algoPreview.getFont().deriveFont(10f));
      algoPreview.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
      algoCard.add(algoPreview, BorderLayout.CENTER);

      JPanel labelRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
      labelRow.setBackground(algoCard.getBackground());
      JLabel algoLabel = new JLabel("Algo " + a);
      algoLabel.setForeground(Color.CYAN);
      algoLabel.setFont(algoLabel.getFont().deriveFont(Font.BOLD, 11f));
      labelRow.add(algoLabel);
      JButton selectBtn = new JButton("Select");
      selectBtn.setFont(selectBtn.getFont().deriveFont(10f));
      selectBtn.addActionListener(
          ev -> {
            model.setSynthAlgorithm(a);
            for (Component comp : gridPanel.getComponents()) {
              if (comp instanceof JPanel card) {
                card.setBackground(
                    a == getAlgoForCard(card, gridPanel)
                        ? new Color(0x3a, 0x5a, 0x3a)
                        : new Color(0x2a, 0x2a, 0x2a));
              }
            }
          });
      labelRow.add(selectBtn);
      algoCard.add(labelRow, BorderLayout.SOUTH);

      gridPanel.add(algoCard);
    }
    add(scroll, BorderLayout.CENTER);

    // ── Bottom: STK description ──
    JTextArea desc =
        new JTextArea(
            "Algo 0-9: Standard DX7 FM algorithms (6-op, algorithm routing determined by firmware tables).\n"
                + "Algo 10: Mandolin — Plucked string physical model with body resonance.\n"
                + "Algo 11: Rhodey EP — FM electric piano based on the Rhodes sound.\n"
                + "Algo 12: ModalBar — Mallet percussion with adjustable bar material.\n"
                + "Algo 13: Moog Bass — Monophonic bass synthesizer with ladder filter.");
    desc.setEditable(false);
    desc.setBackground(new Color(0x2a, 0x2a, 0x2a));
    desc.setForeground(Color.LIGHT_GRAY);
    desc.setFont(desc.getFont().deriveFont(11f));
    desc.setBorder(BorderFactory.createEmptyBorder(8, 5, 8, 5));
    desc.setLineWrap(true);
    desc.setWrapStyleWord(true);
    add(desc, BorderLayout.SOUTH);
  }

  /** Produce a 3-line ASCII mini-representation of a DX7 algorithm (0-31). */
  static String formatAlgorithmMini(int algo) {
    int[] algos = org.deluge.shadow.audio.Dx7EngineLookupTables.ALGORITHMS;
    int base = algo * 6;
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 3; i++) {
      int flags = algos[base + i];
      String opLabel = "OP" + (i + 1);
      char out =
          (flags & 0x01) != 0 ? '1' : (flags & 0x02) != 0 ? '2' : (flags & 0x04) != 0 ? 'A' : '?';
      char fb = (flags & 0x80) != 0 ? 'F' : ' ';
      sb.append(String.format("%s%c%c ", opLabel, fb, out));
    }
    sb.append('\n');
    for (int i = 3; i < 6; i++) {
      int flags = algos[base + i];
      String opLabel = "OP" + (i + 1);
      char out =
          (flags & 0x01) != 0 ? '1' : (flags & 0x02) != 0 ? '2' : (flags & 0x04) != 0 ? 'A' : '?';
      char fb = (flags & 0x80) != 0 ? 'F' : ' ';
      sb.append(String.format("%s%c%c ", opLabel, fb, out));
    }
    sb.append('\n');
    sb.append(String.format("FB=%d/7", algo < 10 ? 7 - algo : 0));
    return sb.toString();
  }

  /** Get the algorithm index from a card component in the grid. */
  private static int getAlgoForCard(JPanel card, JPanel grid) {
    int idx = 0;
    for (Component comp : grid.getComponents()) {
      if (comp == card) return idx;
      if (comp instanceof JPanel) idx++;
    }
    return -1;
  }
}
