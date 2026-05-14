package org.chuck.deluge.ui;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.ModKnob;
import org.chuck.deluge.model.PatchCable;
import org.chuck.deluge.model.SynthTrackModel;

/** MODULATION tab: patch cables section + mod knobs grid. */
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
    "oscAVolume",
    "oscBVolume",
    "pitch",
    "noiseVolume",
    "modFxRate",
    "modFxDepth",
    "modFxFeedback",
    "modFxOffset"
  };

  public ModulationPanel(SynthTrackModel model, BridgeContract bridge, int trackIndex) {
    super(new BorderLayout(4, 4));
    setBackground(new Color(0x22, 0x22, 0x22));

    // ── Patch Cables section ──
    JPanel cablePanel = new JPanel(new BorderLayout(4, 4));
    cablePanel.setBackground(new Color(0x22, 0x22, 0x22));
    cablePanel.add(SwingSynthConfigDialog.sectionLabel("PATCH CABLES"), BorderLayout.NORTH);

    List<PatchCable> cables = model.getPatchCables();
    JPanel cableRows = new JPanel();
    cableRows.setLayout(new BoxLayout(cableRows, BoxLayout.Y_AXIS));
    cableRows.setBackground(new Color(0x22, 0x22, 0x22));
    List<JPanel> cableRowPanels = new ArrayList<>();

    Runnable rebuildCableRows =
        () -> rebuildCableRows(cableRows, model, MOD_SRC_OPTIONS, MOD_DST_OPTIONS);

    JScrollPane cableScroll = new JScrollPane(cableRows);
    cableScroll.setPreferredSize(new Dimension(500, 180));
    cablePanel.add(cableScroll, BorderLayout.CENTER);

    JButton addCableBtn = new JButton("+ Add Cable");
    addCableBtn.addActionListener(
        ev -> {
          model.addPatchCable(new PatchCable("velocity", "volume", 0.0f));
          rebuildCableRows.run();
        });
    cablePanel.add(addCableBtn, BorderLayout.SOUTH);

    // ── Mod Knobs section ──
    JPanel knobPanel = new JPanel(new BorderLayout(4, 4));
    knobPanel.setBackground(new Color(0x22, 0x22, 0x22));
    knobPanel.add(
        SwingSynthConfigDialog.sectionLabel("MOD KNOBS (Gold Knobs)"), BorderLayout.NORTH);

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

    JPanel knobGrid = new JPanel(new GridLayout(4, 4, 6, 6));
    knobGrid.setBackground(new Color(0x22, 0x22, 0x22));
    java.util.List<ModKnob> knobs = model.getModKnobs();
    for (int i = 0; i < 16 && i < knobs.size(); i++) {
      final int ki = i;
      JPanel kp = new JPanel(new BorderLayout(2, 2));
      kp.setBackground(new Color(0x2a, 0x2a, 0x2a));
      kp.setBorder(BorderFactory.createTitledBorder("Knob " + (i + 1)));

      JComboBox<String> knobCombo = new JComboBox<>(knobParams);
      knobCombo.setSelectedItem(knobs.get(i).param());
      knobCombo.setBackground(new Color(0x33, 0x33, 0x33));
      knobCombo.setForeground(Color.WHITE);
      knobCombo.addActionListener(
          ev -> {
            String sel = (String) knobCombo.getSelectedItem();
            model.setModKnob(ki, new ModKnob(sel, "NONE"));
          });
      kp.add(knobCombo, BorderLayout.CENTER);
      knobGrid.add(kp);
    }
    knobPanel.add(knobGrid, BorderLayout.CENTER);

    // ── Split pane ──
    JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, cablePanel, knobPanel);
    split.setResizeWeight(0.4);
    split.setBackground(new Color(0x22, 0x22, 0x22));
    add(split, BorderLayout.CENTER);
  }

  /** Rebuild the patch cable rows panel from the model. */
  private static void rebuildCableRows(
      JPanel cableRows, SynthTrackModel model, String[] srcOptions, String[] dstOptions) {
    cableRows.removeAll();
    java.util.List<PatchCable> cur = model.getPatchCables();
    for (int i = 0; i < cur.size(); i++) {
      final int idx = i;
      PatchCable pc = cur.get(i);
      JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
      row.setBackground(new Color(0x22, 0x22, 0x22));

      JComboBox<String> srcCombo = new JComboBox<>(srcOptions);
      srcCombo.setSelectedItem(pc.source());
      srcCombo.setBackground(new Color(0x33, 0x33, 0x33));
      srcCombo.setForeground(Color.WHITE);
      srcCombo.addActionListener(
          ev -> {
            PatchCable old = model.getPatchCables().get(idx);
            model
                .getPatchCables()
                .set(
                    idx,
                    new PatchCable(
                        (String) srcCombo.getSelectedItem(), old.destination(), old.amount()));
          });
      row.add(new JLabel("Src:"));

      JComboBox<String> dstCombo = new JComboBox<>(dstOptions);
      dstCombo.setSelectedItem(pc.destination());
      dstCombo.setBackground(new Color(0x33, 0x33, 0x33));
      dstCombo.setForeground(Color.WHITE);
      dstCombo.addActionListener(
          ev -> {
            PatchCable old = model.getPatchCables().get(idx);
            model
                .getPatchCables()
                .set(
                    idx,
                    new PatchCable(
                        old.source(), (String) dstCombo.getSelectedItem(), old.amount()));
          });
      row.add(new JLabel("Dst:"));

      boolean isBipolar = pc.polarity() == PatchCable.Polarity.BIPOLAR;
      int sliderMin = isBipolar ? -100 : 0;
      JSlider amtSlider = new JSlider(sliderMin, 100, (int) (pc.amount() * 100));
      amtSlider.setBackground(new Color(0x22, 0x22, 0x22));
      JLabel amtVal = new JLabel(String.format("%.0f%%", pc.amount() * 100));
      amtVal.setForeground(Color.CYAN);

      JToggleButton polBtn = new JToggleButton("Bi", isBipolar);
      polBtn.setToolTipText("Toggle bipolar (Bi) / unipolar (Uni) mode");
      polBtn.setFont(polBtn.getFont().deriveFont(Font.PLAIN, 10f));
      polBtn.setBackground(isBipolar ? new Color(0x66, 0x44, 0x00) : new Color(0x33, 0x33, 0x33));
      polBtn.setForeground(Color.WHITE);
      polBtn.setPreferredSize(new Dimension(40, 22));
      polBtn.addActionListener(
          ev -> {
            PatchCable old = model.getPatchCables().get(idx);
            PatchCable.Polarity newPol =
                polBtn.isSelected() ? PatchCable.Polarity.BIPOLAR : PatchCable.Polarity.UNIPOLAR;
            model
                .getPatchCables()
                .set(idx, new PatchCable(old.source(), old.destination(), old.amount(), newPol));
            polBtn.setBackground(
                polBtn.isSelected() ? new Color(0x66, 0x44, 0x00) : new Color(0x33, 0x33, 0x33));
            if (polBtn.isSelected()) {
              amtSlider.setMinimum(-100);
            } else {
              amtSlider.setMinimum(0);
              if (amtSlider.getValue() < 0) amtSlider.setValue(0);
            }
          });
      row.add(polBtn);

      amtSlider.addChangeListener(
          ev -> {
            float v = amtSlider.getValue() / 100f;
            PatchCable old = model.getPatchCables().get(idx);
            model
                .getPatchCables()
                .set(idx, new PatchCable(old.source(), old.destination(), v, old.polarity()));
            amtVal.setText(String.format("%.0f%%", v * 100));
          });
      row.add(amtSlider);
      row.add(amtVal);

      JButton removeBtn = new JButton("X");
      removeBtn.addActionListener(
          ev -> {
            model.getPatchCables().remove(idx);
            rebuildCableRows(cableRows, model, srcOptions, dstOptions);
          });
      row.add(removeBtn);

      cableRows.add(row);
    }
    cableRows.revalidate();
    cableRows.repaint();
  }
}
