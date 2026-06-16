package org.chuck.deluge.ui;

import java.awt.*;
import java.util.List;
import javax.swing.*;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.ModKnob;
import org.chuck.deluge.model.PatchCable;
import org.chuck.deluge.model.SynthTrackModel;

/**
 * MODULATION tab: visual patch cables list with live real-time hot-swap synchronizers and premium
 * dark slate high-contrast layouts.
 */
public class ModulationPanel extends JPanel {

  private static final String[] MOD_SRC_OPTIONS = {
    "velocity",
    "envelope1",
    "envelope2",
    "envelope3",
    "envelope4",
    "lfo1",
    "lfo2",
    "lfo3",
    "lfo4",
    "aftertouch",
    "note",
    "random",
    "sidechain"
  };

  private static final String[] MOD_DST_OPTIONS = {
    "volume",
    "pan",
    "lpfFrequency",
    "lpfResonance",
    "hpfFrequency",
    "hpfResonance",
    "oscAVolume",
    "oscBVolume",
    "pitch",
    "noiseVolume",
    "modFxRate",
    "modFxDepth",
    "modFxFeedback",
    "modFxOffset",
    "lfo1Rate",
    "lfo2Rate",
    "envelope1ADSR",
    "envelope2ADSR",
    "wavetablePosition"
  };

  private final SynthTrackModel model;
  private final BridgeContract bridge;
  private final int trackIndex;

  private final ModulationMatrixComponent matrix;
  private final JPanel cableRows;
  private final JScrollPane cableScroll;
  private final JButton addCableBtn;

  public ModulationPanel(SynthTrackModel model, BridgeContract bridge, int trackIndex) {
    super(new BorderLayout(6, 6));
    this.model = model;
    this.bridge = bridge;
    this.trackIndex = trackIndex;

    setBackground(SwingSynthConfigDialog.BG_CARD);
    setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

    // ── Patch Cables Section ──
    JPanel cablePanel = new JPanel(new BorderLayout(6, 6));
    cablePanel.setBackground(SwingSynthConfigDialog.BG_CARD);
    cablePanel.add(
        SwingSynthConfigDialog.sectionLabel("🔌 MODULATION PATCH CABLES (MOD ROUTING BAY)"),
        BorderLayout.NORTH);

    cableRows = new JPanel();
    cableRows.setLayout(new BoxLayout(cableRows, BoxLayout.Y_AXIS));
    cableRows.setBackground(SwingSynthConfigDialog.BG_CARD);

    cableScroll = new JScrollPane(cableRows);
    cableScroll.setBorder(BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32), 1));
    cableScroll.setBackground(new Color(0x12, 0x12, 0x14));
    cableScroll.getViewport().setBackground(new Color(0x12, 0x12, 0x14));
    cableScroll.getVerticalScrollBar().setUnitIncrement(16);
    SwingRandomizerDialog.styleScrollBar(cableScroll.getVerticalScrollBar());
    SwingRandomizerDialog.styleScrollBar(cableScroll.getHorizontalScrollBar());
    cablePanel.add(cableScroll, BorderLayout.CENTER);

    addCableBtn = new JButton("+ Connect New Modulation Cable");
    styleButton(addCableBtn, new Color(0x0c, 0x38, 0x1f), Color.GREEN);
    addCableBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
    addCableBtn.addActionListener(
        ev -> {
          model.addPatchCable(new PatchCable("velocity", "volume", 0.5f));
          syncModulationsLive();
          rebuildCableRows();
        });
    cablePanel.add(addCableBtn, BorderLayout.SOUTH);

    // ── Mod Knobs Section (Deluge Hardware Gold Knobs mapping) ──
    JPanel knobPanel = new JPanel(new BorderLayout(6, 6));
    knobPanel.setBackground(SwingSynthConfigDialog.BG_CARD);
    knobPanel.add(
        SwingSynthConfigDialog.sectionLabel("🎛️ SYSTEM ASSIGNABLE MODULATION KNOBS"),
        BorderLayout.NORTH);

    String[] knobParams = {
      "NONE",
      "volume",
      "pan",
      "reverb",
      "delay",
      "lpfFrequency",
      "lpfResonance",
      "hpfFrequency",
      "pitch",
      "oscAVolume",
      "oscBVolume",
      "noiseVolume",
      "modFxRate",
      "modFxDepth",
      "modFxFeedback",
      "modFxOffset"
    };

    JPanel knobGrid = new JPanel(new GridLayout(4, 4, 8, 8));
    knobGrid.setBackground(SwingSynthConfigDialog.BG_CARD);
    List<ModKnob> knobs = model.getModKnobs();

    for (int i = 0; i < 16 && i < knobs.size(); i++) {
      final int ki = i;
      JPanel kp = new JPanel(new BorderLayout(4, 4));
      kp.setBackground(new Color(0x1a, 0x1a, 0x1e));
      kp.setBorder(
          BorderFactory.createCompoundBorder(
              BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32), 1),
              BorderFactory.createEmptyBorder(6, 8, 6, 8)));

      JLabel knobLabel = new JLabel("Gold Knob " + String.format("%02d", i + 1));
      knobLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
      knobLabel.setForeground(new Color(0xff, 0xb3, 0x00));
      kp.add(knobLabel, BorderLayout.NORTH);

      JComboBox<String> knobCombo = new JComboBox<>(knobParams);
      knobCombo.setSelectedItem(knobs.get(ki).param());
      knobCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
      knobCombo.setForeground(Color.WHITE);
      knobCombo.addActionListener(
          ev -> {
            String sel = (String) knobCombo.getSelectedItem();
            model.setModKnob(ki, new ModKnob(sel, "NONE"));
          });
      kp.add(knobCombo, BorderLayout.CENTER);
      knobGrid.add(kp);
    }

    JScrollPane knobScroll = new JScrollPane(knobGrid);
    knobScroll.setBorder(null);
    knobScroll.setBackground(SwingSynthConfigDialog.BG_CARD);
    knobScroll.getVerticalScrollBar().setUnitIncrement(16);
    SwingRandomizerDialog.styleScrollBar(knobScroll.getVerticalScrollBar());
    knobPanel.add(knobScroll, BorderLayout.CENTER);

    // Create the interactive Modulation Matrix Grid
    matrix =
        new ModulationMatrixComponent(
            model,
            () -> {
              syncModulationsLive();
              rebuildCableRows();
              if (SwingDelugeApp.mainInstance != null) {
                SwingDelugeApp.mainInstance.fireProjectChanged();
              }
            });

    JPanel matrixWrapper = new JPanel(new BorderLayout());
    matrixWrapper.setBackground(SwingSynthConfigDialog.BG_CARD);
    matrixWrapper.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(0, 0, 0, 8),
            BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32), 1, true),
                "Interactive Modulation Matrix",
                javax.swing.border.TitledBorder.LEFT,
                javax.swing.border.TitledBorder.TOP,
                new Font("SansSerif", Font.BOLD, 11),
                Color.LIGHT_GRAY)));
    matrixWrapper.add(matrix, BorderLayout.CENTER);

    // Right-side detailed view
    JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, cablePanel, knobPanel);
    rightSplit.setDividerLocation(320);
    rightSplit.setBackground(SwingSynthConfigDialog.BG_CARD);
    rightSplit.setBorder(null);

    // Main horizontal split
    JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, matrixWrapper, rightSplit);
    mainSplit.setDividerLocation(385);
    mainSplit.setBackground(SwingSynthConfigDialog.BG_CARD);
    mainSplit.setBorder(null);
    add(mainSplit, BorderLayout.CENTER);

    // Build the visual rows initially on boot!
    rebuildCableRows();
    DarkComboBoxRenderer.styleComponentTree(this);
  }

  private void syncModulationsLive() {
    // Model patch cables are the source of truth; the dialog's live-apply timer re-maps them
    // onto the running sound (FirmwareFactory.applyModelToLiveSound), so nothing else to do.
  }

  /** Rebuilds the custom visual cable rows in place. */
  private void rebuildCableRows() {
    cableRows.removeAll();
    List<PatchCable> cur = model.getPatchCables();

    for (int i = 0; i < cur.size(); i++) {
      final int idx = i;
      PatchCable pc = cur.get(i);

      JPanel row = new JPanel(new GridBagLayout());
      row.setBackground(new Color(0x1a, 0x1a, 0x1e));
      row.setBorder(
          BorderFactory.createCompoundBorder(
              BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x2d, 0x2d, 0x32)),
              BorderFactory.createEmptyBorder(6, 12, 6, 12)));
      GridBagConstraints c = new GridBagConstraints();
      c.fill = GridBagConstraints.HORIZONTAL;
      c.insets = new Insets(2, 6, 2, 6);
      c.weightx = 0.0;

      // Source selector
      c.gridx = 0;
      JLabel srcLabel = new JLabel("Source:");
      srcLabel.setForeground(Color.LIGHT_GRAY);
      srcLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
      row.add(srcLabel, c);

      c.gridx = 1;
      JComboBox<String> srcCombo = new JComboBox<>(MOD_SRC_OPTIONS);
      srcCombo.setSelectedItem(pc.source());
      srcCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
      srcCombo.setForeground(Color.WHITE);
      srcCombo.setPreferredSize(new Dimension(110, 24));
      srcCombo.addActionListener(
          ev -> {
            PatchCable old = model.getPatchCables().get(idx);
            model
                .getPatchCables()
                .set(
                    idx,
                    new PatchCable(
                        (String) srcCombo.getSelectedItem(),
                        old.destination(),
                        old.amount(),
                        old.polarity()));
            syncModulationsLive();
          });
      row.add(srcCombo, c);

      // Destination selector
      c.gridx = 2;
      JLabel dstLabel = new JLabel("Destination:");
      dstLabel.setForeground(Color.LIGHT_GRAY);
      dstLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
      row.add(dstLabel, c);

      c.gridx = 3;
      JComboBox<String> dstCombo = new JComboBox<>(MOD_DST_OPTIONS);
      dstCombo.setSelectedItem(pc.destination());
      dstCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
      dstCombo.setForeground(Color.WHITE);
      dstCombo.setPreferredSize(new Dimension(130, 24));
      dstCombo.addActionListener(
          ev -> {
            PatchCable old = model.getPatchCables().get(idx);
            model
                .getPatchCables()
                .set(
                    idx,
                    new PatchCable(
                        old.source(),
                        (String) dstCombo.getSelectedItem(),
                        old.amount(),
                        old.polarity()));
            syncModulationsLive();
          });
      row.add(dstCombo, c);

      // Polarity Toggle
      c.gridx = 4;
      boolean isBipolar = pc.polarity() == PatchCable.Polarity.BIPOLAR;
      JToggleButton polBtn = new JToggleButton("Bipolar", isBipolar);
      polBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
      styleButton(
          polBtn,
          isBipolar ? new Color(0x55, 0x3e, 0x0c) : new Color(0x23, 0x23, 0x28),
          isBipolar ? new Color(0xff, 0xb3, 0x00) : Color.WHITE);
      polBtn.setPreferredSize(new Dimension(65, 24));

      row.add(polBtn, c);

      // Slider Amount
      c.gridx = 5;
      c.weightx = 1.0;
      int sliderMin = isBipolar ? -100 : 0;
      int rawVal = (int) (pc.amount() * 100);
      int clampedVal = Math.max(sliderMin, Math.min(100, rawVal));
      JSlider amtSlider = new JSlider(sliderMin, 100, clampedVal);
      DarkSliderUI.styleSlider(amtSlider, Color.CYAN);

      JLabel amtVal = new JLabel(String.format("%+.0f%%", pc.amount() * 100));
      amtVal.setFont(new Font("Monospaced", Font.BOLD, 12));
      amtVal.setForeground(Color.CYAN);
      amtVal.setPreferredSize(new Dimension(50, 20));

      polBtn.addActionListener(
          ev -> {
            PatchCable old = model.getPatchCables().get(idx);
            PatchCable.Polarity newPol =
                polBtn.isSelected() ? PatchCable.Polarity.BIPOLAR : PatchCable.Polarity.UNIPOLAR;
            model
                .getPatchCables()
                .set(idx, new PatchCable(old.source(), old.destination(), old.amount(), newPol));
            syncModulationsLive();

            styleButton(
                polBtn,
                polBtn.isSelected() ? new Color(0x55, 0x3e, 0x0c) : new Color(0x23, 0x23, 0x28),
                polBtn.isSelected() ? new Color(0xff, 0xb3, 0x00) : Color.WHITE);

            if (polBtn.isSelected()) {
              amtSlider.setMinimum(-100);
            } else {
              amtSlider.setMinimum(0);
              if (amtSlider.getValue() < 0) {
                amtSlider.setValue(0);
                amtVal.setText("+0%");
              }
            }
          });

      amtSlider.addChangeListener(
          ev -> {
            float v = amtSlider.getValue() / 100f;
            PatchCable old = model.getPatchCables().get(idx);
            model
                .getPatchCables()
                .set(idx, new PatchCable(old.source(), old.destination(), v, old.polarity()));
            syncModulationsLive();
            amtVal.setText(String.format("%+3.0f%%", v * 100));
          });
      row.add(amtSlider, c);

      c.gridx = 6;
      c.weightx = 0.0;
      row.add(amtVal, c);

      // Disconnect Button
      c.gridx = 7;
      JButton removeBtn = new JButton("✖");
      styleButton(removeBtn, new Color(0x3e, 0x0c, 0x0c), new Color(0xff, 0x55, 0x55));
      removeBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
      removeBtn.setPreferredSize(new Dimension(30, 24));
      removeBtn.addActionListener(
          ev -> {
            model.getPatchCables().remove(idx);
            syncModulationsLive();
            rebuildCableRows();
          });
      row.add(removeBtn, c);

      cableRows.add(row);
    }

    cableRows.revalidate();
    cableRows.repaint();
    if (matrix != null) {
      matrix.repaint();
    }
  }

  private void styleButton(AbstractButton btn, Color bg, Color fg) {
    btn.setOpaque(true);
    btn.setBorderPainted(true);
    btn.setContentAreaFilled(true);
    btn.setBackground(bg);
    btn.setForeground(fg);
    btn.setFocusable(false);
    btn.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(bg.brighter(), 1),
            BorderFactory.createEmptyBorder(3, 8, 3, 8)));
  }
}
