package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/** LFO tab: 4 LFO rows with shape, rate, depth, target, sync, scope controls. */
public class LfoPanel extends JPanel {

  private static final String[] LFO_SHAPES = {
    "SINE", "SAW", "SQUARE", "TRI", "S&H", "RANDOM WALK", "WARBLER"
  };
  private static final String[] LFO_TARGETS = {"Filter", "Res", "Pan", "Pitch", "Vol", "FM"};

  private static final String[] SYNC_VALS = {
    "OFF", "1/1", "1/2", "1/4", "1/8", "1/16", "1/32", "1/64", "1/2T", "1/4T", "1/8T", "1/16T",
    "1/32T", "1/64T", "1/2D", "1/4D", "1/8D", "1/16D", "1/32D", "1/64D"
  };

  public LfoPanel(ChuckVM vm, BridgeContract bridge, int trackIndex) {
    super(new GridBagLayout());
    setBackground(SwingSynthConfigDialog.BG_CARD);
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(4, 8, 4, 8);
    c.anchor = GridBagConstraints.WEST;

    // Header row
    int col = 0;
    c.gridx = col++;
    c.gridy = 0;
    add(SwingSynthConfigDialog.label(""), c);
    c.gridx = col++;
    add(SwingSynthConfigDialog.headerLabel("SHAPE"), c);
    c.gridx = col++;
    add(SwingSynthConfigDialog.headerLabel("RATE"), c);
    c.gridx = col++;
    add(SwingSynthConfigDialog.headerLabel("DEPTH"), c);
    c.gridx = col++;
    add(SwingSynthConfigDialog.headerLabel("TARGET"), c);
    c.gridx = col++;
    add(SwingSynthConfigDialog.headerLabel("SYNC"), c);
    c.gridx = col;
    add(SwingSynthConfigDialog.headerLabel("SCOPE"), c);

    ChuckArray lfoRateArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_RATE);
    ChuckArray lfoTypeArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_TYPE);
    ChuckArray lfoDepthArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_DEPTH);
    ChuckArray lfoSyncArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_SYNC_LEVEL);

    for (int l = 0; l < BridgeContract.LFO_COUNT; l++) {
      final int lfoIdx = l;
      int row = l + 1;
      col = 0;

      c.gridx = col++;
      c.gridy = row;
      add(SwingSynthConfigDialog.label("LFO " + l + ":"), c);

      // Shape
      JComboBox<String> shapeCombo = new JComboBox<>(LFO_SHAPES);
      int lfoType = (int) lfoTypeArr.getInt(l);
      shapeCombo.setSelectedIndex(Math.max(0, Math.min(LFO_SHAPES.length - 1, lfoType)));
      shapeCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
      shapeCombo.setForeground(Color.WHITE);
      String shapeTooltip =
          "LFO " + lfoIdx + " Shape: Waveform shape of the low-frequency modulator.";
      String shapeHelp =
          "<b>LFO "
              + lfoIdx
              + " SHAPE:</b> Selects the low-frequency modulator's oscillator waveform shape (SINE, SAW, SQUARE, TRI, S&H, RANDOM WALK, etc.). — <i>Physical Deluge:</i> Hold shift + turn LFO parameter dial.";
      shapeCombo.setToolTipText(shapeTooltip);
      SwingSynthConfigDialog.attachHoverHelp(shapeCombo, shapeHelp);
      shapeCombo.addActionListener(
          e -> {
            int type = shapeCombo.getSelectedIndex();
            lfoTypeArr.setInt(lfoIdx, (long) type);
            int sync = lfoSyncArr != null ? (int) lfoSyncArr.getInt(lfoIdx) : 0;
            bridge.setLfo(
                lfoIdx, lfoRateArr.getFloat(lfoIdx), type, lfoDepthArr.getFloat(lfoIdx), sync);
          });
      c.gridx = col++;
      add(shapeCombo, c);

      // Rate
      int rateInit = (int) (lfoRateArr.getFloat(l) * 100);
      JLabel rateValLabel = new JLabel(String.format("%.2f", lfoRateArr.getFloat(l)));
      rateValLabel.setForeground(Color.CYAN);
      rateValLabel.setPreferredSize(new Dimension(45, 20));
      JSlider rateSlider = new JSlider(1, 2000, Math.max(1, Math.min(2000, rateInit)));
      rateSlider.setBackground(SwingSynthConfigDialog.BG_CARD);
      String rateTooltip =
          "LFO " + lfoIdx + " Rate (Hz): Modulator speed frequency in cycles per second.";
      String rateHelp =
          "<b>LFO "
              + lfoIdx
              + " RATE:</b> Controls LFO speed in Hz (0.01Hz to 20Hz) when free-running. — <i>Physical Deluge:</i> Press LFO menu button ➔ turn SELECT dial.";
      rateSlider.setToolTipText(rateTooltip);
      SwingSynthConfigDialog.attachHoverHelp(rateSlider, rateHelp);
      rateSlider.addChangeListener(
          e -> {
            double hz = rateSlider.getValue() / 100.0;
            lfoRateArr.setFloat(lfoIdx, (float) hz);
            int sync = lfoSyncArr != null ? (int) lfoSyncArr.getInt(lfoIdx) : 0;
            bridge.setLfo(
                lfoIdx, hz, (int) lfoTypeArr.getInt(lfoIdx), lfoDepthArr.getFloat(lfoIdx), sync);
            rateValLabel.setText(String.format("%.2f", hz));
          });
      JPanel ratePanel = new JPanel(new BorderLayout(3, 0));
      ratePanel.setBackground(SwingSynthConfigDialog.BG_CARD);
      ratePanel.add(rateSlider, BorderLayout.CENTER);
      ratePanel.add(rateValLabel, BorderLayout.EAST);
      c.gridx = col++;
      add(ratePanel, c);

      // Depth
      int depthInit = (int) (lfoDepthArr.getFloat(l) * 100);
      JLabel depthValLabel = new JLabel(depthInit + "%");
      depthValLabel.setForeground(Color.CYAN);
      depthValLabel.setPreferredSize(new Dimension(40, 20));
      JSlider depthSlider = new JSlider(0, 100, depthInit);
      depthSlider.setBackground(SwingSynthConfigDialog.BG_CARD);
      String depthTooltip = "LFO " + lfoIdx + " Depth (%): Modulator amplitude amount.";
      String depthHelp =
          "<b>LFO "
              + lfoIdx
              + " DEPTH:</b> Controls LFO modulator signal volume/modulation depth (0% to 100%) sent to destination target. — <i>Physical Deluge:</i> Turn LFO DEPTH gold shortcut dial knob.";
      depthSlider.setToolTipText(depthTooltip);
      SwingSynthConfigDialog.attachHoverHelp(depthSlider, depthHelp);
      depthSlider.addChangeListener(
          e -> {
            float depth = depthSlider.getValue() / 100f;
            lfoDepthArr.setFloat(lfoIdx, depth);
            int sync = lfoSyncArr != null ? (int) lfoSyncArr.getInt(lfoIdx) : 0;
            bridge.setLfo(
                lfoIdx, lfoRateArr.getFloat(lfoIdx), (int) lfoTypeArr.getInt(lfoIdx), depth, sync);
            depthValLabel.setText(depthSlider.getValue() + "%");
          });
      JPanel depthPanel = new JPanel(new BorderLayout(3, 0));
      depthPanel.setBackground(SwingSynthConfigDialog.BG_CARD);
      depthPanel.add(depthSlider, BorderLayout.CENTER);
      depthPanel.add(depthValLabel, BorderLayout.EAST);
      c.gridx = col++;
      add(depthPanel, c);

      // Target
      JComboBox<String> targetCombo = new JComboBox<>(LFO_TARGETS);
      targetCombo.setSelectedIndex(Math.max(0, Math.min(5, bridge.getLfoTarget(l))));
      targetCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
      targetCombo.setForeground(Color.WHITE);
      String targetTooltip = "LFO " + lfoIdx + " Target: Destination modulated parameter.";
      String targetHelp =
          "<b>LFO "
              + lfoIdx
              + " TARGET:</b> Selects the synthesizer parameter (Filter, Res, Pan, Pitch, Vol, FM) targeted by this LFO modulator. — <i>Physical Deluge:</i> Select targets inside LFO menu patch setup.";
      targetCombo.setToolTipText(targetTooltip);
      SwingSynthConfigDialog.attachHoverHelp(targetCombo, targetHelp);
      targetCombo.addActionListener(
          e -> bridge.setLfoTarget(lfoIdx, targetCombo.getSelectedIndex()));
      c.gridx = col++;
      add(targetCombo, c);

      // Sync
      JComboBox<String> syncCombo = new JComboBox<>(SYNC_VALS);
      int curSync =
          lfoSyncArr != null
              ? Math.max(0, Math.min(SYNC_VALS.length - 1, (int) lfoSyncArr.getInt(l)))
              : 0;
      syncCombo.setSelectedIndex(curSync);
      syncCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
      syncCombo.setForeground(Color.WHITE);
      String syncTooltip = "LFO " + lfoIdx + " Sync: Project BPM beat division rate.";
      String syncHelp =
          "<b>LFO "
              + lfoIdx
              + " SYNC:</b> Lock-syncs the LFO modulator rate to project BPM tempo divisions (whole notes, half notes, triplets, dotted notes, etc.). — <i>Physical Deluge:</i> Set sync values inside LFO parameters list.";
      syncCombo.setToolTipText(syncTooltip);
      SwingSynthConfigDialog.attachHoverHelp(syncCombo, syncHelp);
      syncCombo.addActionListener(
          e -> {
            int syncIdx = syncCombo.getSelectedIndex();
            if (lfoSyncArr != null) lfoSyncArr.setInt(lfoIdx, (long) syncIdx);
            bridge.setLfo(
                lfoIdx,
                lfoRateArr.getFloat(lfoIdx),
                (int) lfoTypeArr.getInt(lfoIdx),
                lfoDepthArr.getFloat(lfoIdx),
                syncIdx);
          });
      c.gridx = col++;
      add(syncCombo, c);

      // Scope
      int currentTrack = bridge.getLfoTrack(l);
      JComboBox<String> scopeCombo = new JComboBox<>(new String[] {"All tracks", "This track"});
      scopeCombo.setSelectedIndex(currentTrack == -1 ? 0 : 1);
      scopeCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
      scopeCombo.setForeground(Color.WHITE);
      scopeCombo.addActionListener(
          e -> bridge.setLfoTrack(lfoIdx, scopeCombo.getSelectedIndex() == 0 ? -1 : trackIndex));
      c.gridx = col;
      add(scopeCombo, c);
    }

    // Depth note
    c.gridx = 0;
    c.gridy = BridgeContract.LFO_COUNT + 1;
    c.gridwidth = 7;
    JLabel note =
        new JLabel(
            "<html><i>Depth 100% = Filter ±5kHz, Res ±3Q, Pan ±1.0, Pitch ±1 oct, Vol ±50%, FM ±50%</i></html>");
    note.setForeground(Color.GRAY);
    add(note, c);
  }
}
