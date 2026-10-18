package org.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.deluge.BridgeContract;
import org.deluge.model.SynthTrackModel;

/** ALGORITHM tab: DX7 algorithm grid (0-31) + STK engine selector + engine type. */
public class AlgorithmPanel extends JPanel {

  private static final Color CARD_SELECTED_BG = new Color(0x3a, 0x5a, 0x3a);
  private static final Color CARD_NORMAL_BG = new Color(0x2a, 0x2a, 0x2a);
  private static final Color CARD_NORMAL_BORDER = new Color(0x44, 0x44, 0x44);

  private final JComboBox<String> engineCombo;
  private final JPanel gridPanel;
  private boolean syncingEngineCombo = false;

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
    engineCombo = new JComboBox<>(stkNames);
    engineCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    engineCombo.setForeground(Color.WHITE);
    int curAlgo = model.getSynthAlgorithm();
    int curEngineIdx = engineComboIndexForAlgo(curAlgo, stkValues);
    engineCombo.setSelectedIndex(curEngineIdx);
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
    engineTypeCombo.setSelectedIndex(
        Math.max(0, Math.min(engineTypeNames.length - 1, curEngineType + 1)));
    engineTypeCombo.addActionListener(
        ev -> {
          int idx = engineTypeCombo.getSelectedIndex();
          int typeVal = idx - 1;
          model.setEngineType(typeVal);
        });
    topPanel.add(engineTypeCombo);
    add(topPanel, BorderLayout.NORTH);

    // ── Center: 32-algorithm grid ──
    gridPanel = new JPanel(new GridLayout(16, 2, 6, 6));
    gridPanel.setBackground(SwingSynthConfigDialog.BG_CARD);
    JScrollPane scroll = new JScrollPane(gridPanel);
    scroll.setPreferredSize(new Dimension(700, 400));
    scroll.setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(CARD_NORMAL_BORDER), "DX7 ALGORITHMS (0–31)"));
    scroll.getViewport().setBackground(SwingSynthConfigDialog.BG_CARD);

    for (int algo = 0; algo < 32; algo++) {
      final int a = algo;
      JPanel algoCard = new JPanel(new BorderLayout(4, 2));
      algoCard.setBackground(curAlgo == a ? CARD_SELECTED_BG : CARD_NORMAL_BG);
      algoCard.setBorder(
          BorderFactory.createLineBorder(curAlgo == a ? Color.CYAN : CARD_NORMAL_BORDER, 1));

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
            updateAlgorithmCardSelection(gridPanel, a);
            syncingEngineCombo = true;
            try {
              engineCombo.setSelectedIndex(engineComboIndexForAlgo(a, stkValues));
            } finally {
              syncingEngineCombo = false;
            }
          });
      labelRow.add(selectBtn);
      algoCard.add(labelRow, BorderLayout.SOUTH);

      gridPanel.add(algoCard);
    }
    engineCombo.addActionListener(
        e -> {
          if (syncingEngineCombo) return;
          int ei = engineCombo.getSelectedIndex();
          int algoVal = stkValues[ei];
          model.setSynthAlgorithm(algoVal);
          updateAlgorithmCardSelection(gridPanel, algoVal);
        });
    add(scroll, BorderLayout.CENTER);
    // Open the list at the top (Algo 0) — adding cards can leave the viewport scrolled mid-list.
    SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(0));

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

  /**
   * Produce a 3-line ASCII mini-representation of a DX7 algorithm (0-31) from the engine's real
   * routing table ({@code FmCore.ALGORITHMS}; table index i is operator 6-i). Each operator shows C
   * (carrier, adds to the audio output) or M (modulator, feeds another operator), plus F on the
   * feedback operator.
   */
  static String formatAlgorithmMini(int algo) {
    int[][] table = org.deluge.firmware2.FmCore.ALGORITHMS;
    if (algo < 0 || algo >= table.length) return "";
    int[] ops = table[algo];
    StringBuilder sb = new StringBuilder();
    int fbOp = -1;
    for (int op = 1; op <= 6; op++) {
      int flags = ops[6 - op];
      char role = (flags & 0x04) != 0 ? 'C' : 'M';
      boolean fb = (flags & 0x80) != 0;
      if (fb) fbOp = op;
      sb.append(String.format("OP%d%c%c ", op, fb ? 'F' : ':', role));
      if (op == 3) sb.append('\n');
    }
    sb.append('\n');
    sb.append(fbOp > 0 ? "FB on OP" + fbOp : "no FB");
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

  private static int engineComboIndexForAlgo(int algo, int[] stkValues) {
    for (int i = 1; i < stkValues.length; i++) {
      if (stkValues[i] == algo) return i;
    }
    return 0;
  }

  private static void updateAlgorithmCardSelection(JPanel grid, int selectedAlgo) {
    for (Component comp : grid.getComponents()) {
      if (comp instanceof JPanel card) {
        boolean sel = (selectedAlgo == getAlgoForCard(card, grid));
        Color bg = sel ? CARD_SELECTED_BG : CARD_NORMAL_BG;
        card.setBackground(bg);
        card.setBorder(BorderFactory.createLineBorder(sel ? Color.CYAN : CARD_NORMAL_BORDER, 1));
        for (Component child : card.getComponents()) {
          if (child instanceof JPanel labelRow) {
            labelRow.setBackground(bg);
          }
        }
      }
    }
    grid.repaint();
  }

  JComboBox<String> getEngineComboForTest() {
    return engineCombo;
  }

  JPanel getGridPanelForTest() {
    return gridPanel;
  }
}
