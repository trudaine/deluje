package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.SynthTrackModel;

/** Swing dialog for editing a Synth track: Arp, Filter, FM, and 4-slot LFO. */
public class SwingSynthConfigDialog extends JDialog {

  private static final String[] LFO_SHAPES = {"SINE", "SAW", "SQUARE", "TRI"};
  private static final String[] LFO_TARGETS = {"Filter", "Res", "Pan", "Pitch", "Vol", "FM"};

  public SwingSynthConfigDialog(
      Frame owner, SynthTrackModel model, ChuckVM vm, BridgeContract bridge, int trackIndex) {
    super(owner, "Synth Config: " + model.getName(), false);
    setSize(1300, 700);
    setLocationRelativeTo(owner);
    setLayout(new BorderLayout());
    getContentPane().setBackground(new Color(0x1a, 0x1a, 0x1a));

    JTabbedPane tabs = new JTabbedPane();
    tabs.setBackground(new Color(0x25, 0x25, 0x25));
    tabs.setForeground(Color.WHITE);

    tabs.addTab("ARP / FILTER / FM", buildMainPanel(model, vm, bridge, trackIndex));
    tabs.addTab("LFO", buildLfoPanel(vm, bridge, trackIndex));

    add(tabs, BorderLayout.CENTER);

    JButton closeBtn = new JButton("Close");
    closeBtn.addActionListener(e -> dispose());
    JPanel south = new JPanel();
    south.setBackground(new Color(0x25, 0x25, 0x25));
    south.add(closeBtn);
    add(south, BorderLayout.SOUTH);
  }

  private JPanel buildMainPanel(
      SynthTrackModel model, ChuckVM vm, BridgeContract bridge, int trackIndex) {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBackground(new Color(0x22, 0x22, 0x22));
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(6, 10, 6, 10);
    c.anchor = GridBagConstraints.WEST;
    int row = 0;

    // ── Arpeggiator ──
    c.gridx = 0; c.gridy = row; c.gridwidth = 3;
    panel.add(sectionLabel("ARPEGGIATOR"), c); row++;

    c.gridx = 0; c.gridy = row; c.gridwidth = 1;
    JLabel arpLbl = label("ARP ON:");
    arpLbl.setToolTipText("Enable the arpeggiator — plays notes in sequence automatically");
    panel.add(arpLbl, c);
    c.gridx = 1; c.gridwidth = 2;
    JCheckBox arpBox = new JCheckBox();
    arpBox.setSelected(bridge.getArpOn(trackIndex));
    arpBox.setBackground(new Color(0x22, 0x22, 0x22));
    arpBox.setToolTipText("Enable the arpeggiator");
    arpBox.addActionListener(e -> bridge.setArpOn(trackIndex, arpBox.isSelected()));
    panel.add(arpBox, c); row++;

    row = addSlider(panel, c, row, "Rate (×):",
        "Arpeggiator speed multiplier (0.25× to 4.00×)",
        25, 400, (int)(bridge.getArpRate(trackIndex) * 100),
        val -> bridge.setArpRate(trackIndex, val / 100.0), "×0.01");

    c.gridx = 0; c.gridy = row; c.gridwidth = 1;
    JLabel octLbl = label("Octaves:");
    octLbl.setToolTipText("Number of octaves the arpeggiator spans (1–4)");
    panel.add(octLbl, c);
    c.gridx = 1; c.gridwidth = 2;
    JComboBox<Integer> octCombo = new JComboBox<>(new Integer[]{1, 2, 3, 4});
    octCombo.setSelectedItem(bridge.getArpOctave(trackIndex));
    octCombo.setBackground(new Color(0x33, 0x33, 0x33));
    octCombo.setToolTipText("Number of octaves the arpeggiator spans");
    octCombo.addActionListener(e -> bridge.setArpOctave(trackIndex, (Integer) octCombo.getSelectedItem()));
    panel.add(octCombo, c); row++;

    // ── Filter ──
    c.gridx = 0; c.gridy = row; c.gridwidth = 3;
    panel.add(sectionLabel("FILTER"), c); row++;

    row = addSlider(panel, c, row, "Cutoff:",
        "Low-pass filter cutoff frequency (0% = fully closed, 100% = fully open)",
        0, 100, (int)(bridge.getTrackFilterFreq(trackIndex) * 100),
        val -> bridge.setFilterFreq(trackIndex, val / 100.0), "%");
    row = addSlider(panel, c, row, "Resonance:",
        "Filter resonance / Q — emphasises frequencies around the cutoff",
        0, 100, (int)(bridge.getTrackFilterRes(trackIndex) * 100),
        val -> bridge.setFilterRes(trackIndex, val / 100.0), "%");

    // ── FM ──
    c.gridx = 0; c.gridy = row; c.gridwidth = 3;
    panel.add(sectionLabel("FM SYNTHESIS"), c); row++;

    row = addSlider(panel, c, row, "FM Ratio:",
        "Frequency ratio of the modulator oscillator relative to the carrier (0.25–4.00)",
        25, 400, (int)(bridge.getFmRatio(trackIndex) * 100),
        val -> bridge.setFmRatio(trackIndex, val / 100.0), "×0.01");
    row = addSlider(panel, c, row, "FM Amount:",
        "Depth of FM modulation — how strongly the modulator affects the carrier (0–100%)",
        0, 100, (int)(bridge.getFmAmount(trackIndex) * 100),
        val -> bridge.setFmAmount(trackIndex, val / 100.0), "%");

    return panel;
  }

  private JPanel buildLfoPanel(ChuckVM vm, BridgeContract bridge, int trackIndex) {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBackground(new Color(0x22, 0x22, 0x22));
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(4, 8, 4, 8);
    c.anchor = GridBagConstraints.WEST;

    // Header row
    int col = 0;
    c.gridx = col++; c.gridy = 0; panel.add(label(""), c);
    c.gridx = col++; panel.add(headerLabel("SHAPE"), c);
    c.gridx = col++; panel.add(headerLabel("RATE (Hz)"), c);
    c.gridx = col++; panel.add(headerLabel("DEPTH"), c);
    c.gridx = col++; panel.add(headerLabel("TARGET"), c);
    c.gridx = col;   panel.add(headerLabel("SCOPE"), c);

    ChuckArray lfoRateArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_RATE);
    ChuckArray lfoTypeArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_TYPE);
    ChuckArray lfoDepthArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_DEPTH);

    for (int l = 0; l < BridgeContract.LFO_COUNT; l++) {
      final int lfoIdx = l;
      int row = l + 1;
      col = 0;

      c.gridx = col++; c.gridy = row;
      panel.add(label("LFO " + l + ":"), c);

      // Shape
      JComboBox<String> shapeCombo = new JComboBox<>(LFO_SHAPES);
      shapeCombo.setSelectedIndex((int) Math.max(0, Math.min(3, lfoTypeArr.getInt(l))));
      shapeCombo.setBackground(new Color(0x33, 0x33, 0x33));
      shapeCombo.setForeground(Color.WHITE);
      shapeCombo.addActionListener(e -> {
        int type = shapeCombo.getSelectedIndex();
        lfoTypeArr.setInt(lfoIdx, (long) type);
        bridge.setLfo(lfoIdx, lfoRateArr.getFloat(lfoIdx),
            type, lfoDepthArr.getFloat(lfoIdx));
      });
      c.gridx = col++; panel.add(shapeCombo, c);

      // Rate slider 1–2000 (mapped to 0.01–20 Hz, step 1 = 0.01 Hz)
      int rateInit = (int)(lfoRateArr.getFloat(l) * 100);
      JLabel rateValLabel = new JLabel(String.format("%.2f", lfoRateArr.getFloat(l)));
      rateValLabel.setForeground(Color.CYAN);
      rateValLabel.setPreferredSize(new Dimension(45, 20));
      JSlider rateSlider = new JSlider(1, 2000, Math.max(1, Math.min(2000, rateInit)));
      rateSlider.setBackground(new Color(0x22, 0x22, 0x22));
      rateSlider.addChangeListener(e -> {
        double hz = rateSlider.getValue() / 100.0;
        lfoRateArr.setFloat(lfoIdx, (float) hz);
        bridge.setLfo(lfoIdx, hz, (int) lfoTypeArr.getInt(lfoIdx), lfoDepthArr.getFloat(lfoIdx));
        rateValLabel.setText(String.format("%.2f", hz));
      });
      JPanel ratePanel = new JPanel(new BorderLayout(3, 0));
      ratePanel.setBackground(new Color(0x22, 0x22, 0x22));
      ratePanel.add(rateSlider, BorderLayout.CENTER);
      ratePanel.add(rateValLabel, BorderLayout.EAST);
      c.gridx = col++; panel.add(ratePanel, c);

      // Depth slider 0–100
      int depthInit = (int)(lfoDepthArr.getFloat(l) * 100);
      JLabel depthValLabel = new JLabel(depthInit + "%");
      depthValLabel.setForeground(Color.CYAN);
      depthValLabel.setPreferredSize(new Dimension(40, 20));
      JSlider depthSlider = new JSlider(0, 100, depthInit);
      depthSlider.setBackground(new Color(0x22, 0x22, 0x22));
      depthSlider.addChangeListener(e -> {
        float depth = depthSlider.getValue() / 100f;
        lfoDepthArr.setFloat(lfoIdx, depth);
        bridge.setLfo(lfoIdx, lfoRateArr.getFloat(lfoIdx),
            (int) lfoTypeArr.getInt(lfoIdx), depth);
        depthValLabel.setText(depthSlider.getValue() + "%");
      });
      JPanel depthPanel = new JPanel(new BorderLayout(3, 0));
      depthPanel.setBackground(new Color(0x22, 0x22, 0x22));
      depthPanel.add(depthSlider, BorderLayout.CENTER);
      depthPanel.add(depthValLabel, BorderLayout.EAST);
      c.gridx = col++; panel.add(depthPanel, c);

      // Target
      JComboBox<String> targetCombo = new JComboBox<>(LFO_TARGETS);
      targetCombo.setSelectedIndex(Math.max(0, Math.min(5, bridge.getLfoTarget(l))));
      targetCombo.setBackground(new Color(0x33, 0x33, 0x33));
      targetCombo.setForeground(Color.WHITE);
      targetCombo.addActionListener(e -> bridge.setLfoTarget(lfoIdx, targetCombo.getSelectedIndex()));
      c.gridx = col++; panel.add(targetCombo, c);

      // Scope
      int currentTrack = bridge.getLfoTrack(l);
      JComboBox<String> scopeCombo = new JComboBox<>(new String[]{"All tracks", "This track"});
      scopeCombo.setSelectedIndex(currentTrack == -1 ? 0 : 1);
      scopeCombo.setBackground(new Color(0x33, 0x33, 0x33));
      scopeCombo.setForeground(Color.WHITE);
      scopeCombo.addActionListener(e ->
          bridge.setLfoTrack(lfoIdx, scopeCombo.getSelectedIndex() == 0 ? -1 : trackIndex));
      c.gridx = col; panel.add(scopeCombo, c);
    }

    // Depth note
    c.gridx = 0; c.gridy = BridgeContract.LFO_COUNT + 1; c.gridwidth = 6;
    JLabel note = new JLabel("<html><i>Depth 100% = Filter ±5kHz, Res ±3Q, Pan ±1.0, Pitch ±1 oct, Vol ±50%, FM ±50%</i></html>");
    note.setForeground(Color.GRAY);
    panel.add(note, c);

    return panel;
  }

  private int addSlider(JPanel panel, GridBagConstraints c, int row,
      String labelText, String tooltip, int min, int max, int initial,
      java.util.function.IntConsumer onChange, String unit) {
    c.gridx = 0; c.gridy = row; c.gridwidth = 1;
    JLabel lbl = label(labelText);
    lbl.setToolTipText(tooltip);
    panel.add(lbl, c);
    c.gridx = 1; c.gridwidth = 1;
    JSlider slider = new JSlider(min, max, Math.max(min, Math.min(max, initial)));
    slider.setBackground(new Color(0x22, 0x22, 0x22));
    slider.setToolTipText(tooltip);
    c.gridx = 2; c.gridwidth = 1;
    JLabel valLabel = new JLabel(initial + unit);
    valLabel.setForeground(Color.CYAN);
    valLabel.setPreferredSize(new Dimension(60, 20));
    slider.addChangeListener(e -> {
      onChange.accept(slider.getValue());
      valLabel.setText(slider.getValue() + unit);
    });
    c.gridx = 1; panel.add(slider, c);
    c.gridx = 2; panel.add(valLabel, c);
    return row + 1;
  }

  private static JLabel label(String text) {
    JLabel l = new JLabel(text);
    l.setForeground(Color.LIGHT_GRAY);
    return l;
  }

  private static JLabel headerLabel(String text) {
    JLabel l = new JLabel(text);
    l.setForeground(new Color(0x00, 0xff, 0xcc));
    l.setFont(l.getFont().deriveFont(Font.BOLD, 11f));
    return l;
  }

  private static JLabel sectionLabel(String text) {
    JLabel l = new JLabel(text);
    l.setForeground(new Color(0x00, 0xff, 0xcc));
    l.setFont(l.getFont().deriveFont(Font.BOLD));
    return l;
  }
}
