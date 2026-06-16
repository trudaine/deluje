package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.LfoModel;
import org.chuck.deluge.model.LfoType;
import org.chuck.deluge.model.SynthTrackModel;

/**
 * LFO tab: 4 LFO rows with shape, rate, depth, target, sync, scope controls. Model-backed: edits
 * write the track's {@link LfoModel}s (plus the raw rate knob the factory feeds to the firmware exp
 * curve), so the dialog's live-apply makes them audible immediately. Depth/target become a
 * synthesized patch cable in FirmwareFactory.
 */
public class LfoPanel extends JPanel {

  private static final String[] LFO_SHAPES = {
    "SINE", "SAW", "SQUARE", "TRI", "S&H", "RANDOM WALK", "WARBLER"
  };
  private static final String[] LFO_TARGETS = {
    "NONE", "Filter", "Res", "Pan", "Pitch", "Vol", "FM"
  };

  private static final String[] SYNC_VALS = {
    "OFF", "1/1", "1/2", "1/4", "1/8", "1/16", "1/32", "1/64", "1/2T", "1/4T", "1/8T", "1/16T",
    "1/32T", "1/64T", "1/2D", "1/4D", "1/8D", "1/16D", "1/32D", "1/64D"
  };

  private final SynthTrackModel model;

  private LfoModel lfo(int l) {
    LfoModel lm = model.getLfo(l);
    return (lm != null) ? lm : LfoModel.defaultConfig(l % 2 == 1);
  }

  private final LfoMonitorComponent monitor;

  public LfoPanel(SynthTrackModel model, int trackIndex) {
    super(new BorderLayout(16, 16));
    this.model = model;
    setBackground(SwingSynthConfigDialog.BG_CARD);
    setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

    // Controls grid panel (the original GridBag content)
    JPanel controlsPanel = new JPanel(new GridBagLayout());
    controlsPanel.setBackground(SwingSynthConfigDialog.BG_CARD);
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(4, 8, 4, 8);
    c.anchor = GridBagConstraints.WEST;

    // Header row
    int col = 0;
    c.gridx = col++;
    c.gridy = 0;
    controlsPanel.add(SwingSynthConfigDialog.label(""), c);
    c.gridx = col++;
    controlsPanel.add(SwingSynthConfigDialog.headerLabel("SHAPE"), c);
    c.gridx = col++;
    controlsPanel.add(SwingSynthConfigDialog.headerLabel("RATE"), c);
    c.gridx = col++;
    controlsPanel.add(SwingSynthConfigDialog.headerLabel("DEPTH"), c);
    c.gridx = col++;
    controlsPanel.add(SwingSynthConfigDialog.headerLabel("TARGET"), c);
    c.gridx = col++;
    controlsPanel.add(SwingSynthConfigDialog.headerLabel("SYNC"), c);
    c.gridx = col;
    controlsPanel.add(SwingSynthConfigDialog.headerLabel("SCOPE"), c);

    for (int l = 0; l < BridgeContract.LFO_COUNT; l++) {
      final int lfoIdx = l;
      int row = l + 1;
      col = 0;

      c.gridx = col++;
      c.gridy = row;
      controlsPanel.add(SwingSynthConfigDialog.label("LFO " + l + ":"), c);

      LfoModel init = lfo(l);

      // Shape
      JComboBox<String> shapeCombo = new JComboBox<>(LFO_SHAPES);
      shapeCombo.setSelectedIndex(
          Math.max(0, Math.min(LFO_SHAPES.length - 1, init.waveform().ordinal())));
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
            LfoModel lm = lfo(lfoIdx);
            model.setLfo(
                lfoIdx,
                new LfoModel(
                    lm.rateHz(),
                    LfoType.values()[shapeCombo.getSelectedIndex()],
                    lm.depth(),
                    lm.target(),
                    lm.isLocal(),
                    lm.syncLevel(),
                    lm.syncType()));
          });
      c.gridx = col++;
      controlsPanel.add(shapeCombo, c);

      // Rate
      int rateInit = (int) (init.rateHz() * 100);
      JTextField rateField = new JTextField(String.format("%.2f", init.rateHz()));
      rateField.setFont(new Font("SansSerif", Font.PLAIN, 11));
      rateField.setForeground(Color.CYAN);
      rateField.setBackground(SwingSynthConfigDialog.BG_CONTROL);
      rateField.setCaretColor(Color.CYAN);
      rateField.setBorder(
          BorderFactory.createCompoundBorder(
              BorderFactory.createLineBorder(new Color(0x3d, 0x3d, 0x42), 1, true),
              BorderFactory.createEmptyBorder(1, 4, 1, 4)));
      rateField.setPreferredSize(new Dimension(45, 20));
      rateField.setHorizontalAlignment(JTextField.RIGHT);
      rateField.setToolTipText("Click to type a precise frequency in Hz manually");

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
            LfoModel lm = lfo(lfoIdx);
            model.setLfo(
                lfoIdx,
                new LfoModel(
                    (float) hz,
                    lm.waveform(),
                    lm.depth(),
                    lm.target(),
                    lm.isLocal(),
                    lm.syncLevel(),
                    lm.syncType()));
            // The factory feeds the RAW knob to the firmware exp curve — keep it in step.
            model.setLfoRateKnobQ31(
                lfoIdx, org.chuck.deluge.firmware.engine.FirmwareFactory.lfoRateKnobFromHz(hz));
            rateField.setText(String.format("%.2f", hz));
          });

      // Bi-directional typed rate listener
      java.lang.Runnable applyTypedRate =
          () -> {
            try {
              String text = rateField.getText().trim();
              text = text.replaceAll("[^0-9\\.-]", "");
              if (text.isEmpty()) return;
              double hz = Double.parseDouble(text);
              hz = Math.max(0.01, Math.min(20.0, hz));
              rateSlider.setValue((int) (hz * 100));
              rateField.setText(String.format("%.2f", hz));
            } catch (NumberFormatException ex) {
              rateField.setText(String.format("%.2f", rateSlider.getValue() / 100.0));
            }
          };
      rateField.addActionListener(ev -> applyTypedRate.run());
      rateField.addFocusListener(
          new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent ev) {
              applyTypedRate.run();
            }
          });

      JPanel ratePanel = new JPanel(new BorderLayout(4, 0));
      ratePanel.setBackground(SwingSynthConfigDialog.BG_CARD);
      ratePanel.add(rateSlider, BorderLayout.CENTER);
      ratePanel.add(rateField, BorderLayout.EAST);
      c.gridx = col++;
      controlsPanel.add(ratePanel, c);

      // Depth
      int depthInit = (int) (init.depth() * 100);

      // Depth input wrapped with a static % sign label
      JPanel depthContainer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
      depthContainer.setBackground(SwingSynthConfigDialog.BG_CARD);
      depthContainer.setPreferredSize(new Dimension(54, 22));

      JTextField depthField = new JTextField(String.valueOf(depthInit));
      depthField.setFont(new Font("SansSerif", Font.PLAIN, 11));
      depthField.setForeground(Color.CYAN);
      depthField.setBackground(SwingSynthConfigDialog.BG_CONTROL);
      depthField.setCaretColor(Color.CYAN);
      depthField.setBorder(
          BorderFactory.createCompoundBorder(
              BorderFactory.createLineBorder(new Color(0x3d, 0x3d, 0x42), 1, true),
              BorderFactory.createEmptyBorder(1, 4, 1, 4)));
      depthField.setPreferredSize(new Dimension(30, 20));
      depthField.setHorizontalAlignment(JTextField.RIGHT);
      depthField.setToolTipText("Click to type a precise depth percentage manually");

      JLabel depthUnit = new JLabel("%");
      depthUnit.setFont(new Font("SansSerif", Font.PLAIN, 11));
      depthUnit.setForeground(Color.GRAY);

      depthContainer.add(depthField);
      depthContainer.add(depthUnit);

      JSlider depthSlider = new JSlider(0, 100, Math.max(0, Math.min(100, depthInit)));
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
            LfoModel lm = lfo(lfoIdx);
            model.setLfo(
                lfoIdx,
                new LfoModel(
                    lm.rateHz(),
                    lm.waveform(),
                    depth,
                    lm.target(),
                    lm.isLocal(),
                    lm.syncLevel(),
                    lm.syncType()));
            depthField.setText(String.valueOf(depthSlider.getValue()));
          });

      // Bi-directional typed depth listener
      java.lang.Runnable applyTypedDepth =
          () -> {
            try {
              String text = depthField.getText().trim();
              text = text.replaceAll("[^0-9\\.-]", "");
              if (text.isEmpty()) return;
              int val = (int) Double.parseDouble(text);
              int clamped = Math.max(0, Math.min(100, val));
              depthSlider.setValue(clamped);
              depthField.setText(String.valueOf(clamped));
            } catch (NumberFormatException ex) {
              depthField.setText(String.valueOf(depthSlider.getValue()));
            }
          };
      depthField.addActionListener(ev -> applyTypedDepth.run());
      depthField.addFocusListener(
          new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent ev) {
              applyTypedDepth.run();
            }
          });

      JPanel depthPanel = new JPanel(new BorderLayout(4, 0));
      depthPanel.setBackground(SwingSynthConfigDialog.BG_CARD);
      depthPanel.add(depthSlider, BorderLayout.CENTER);
      depthPanel.add(depthContainer, BorderLayout.EAST);
      c.gridx = col++;
      controlsPanel.add(depthPanel, c);

      // Target
      JComboBox<String> targetCombo = new JComboBox<>(LFO_TARGETS);
      int targetIdx = 0;
      for (int t = 0; t < LFO_TARGETS.length; t++) {
        if (LFO_TARGETS[t].equalsIgnoreCase(init.target())) {
          targetIdx = t;
          break;
        }
      }
      targetCombo.setSelectedIndex(targetIdx);
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
          e -> {
            LfoModel lm = lfo(lfoIdx);
            model.setLfo(
                lfoIdx,
                new LfoModel(
                    lm.rateHz(),
                    lm.waveform(),
                    lm.depth(),
                    (String) targetCombo.getSelectedItem(),
                    lm.isLocal(),
                    lm.syncLevel(),
                    lm.syncType()));
          });
      c.gridx = col++;
      controlsPanel.add(targetCombo, c);

      // Sync
      JComboBox<String> syncCombo = new JComboBox<>(SYNC_VALS);
      syncCombo.setSelectedIndex(Math.max(0, Math.min(SYNC_VALS.length - 1, init.syncLevel())));
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
            LfoModel lm = lfo(lfoIdx);
            model.setLfo(
                lfoIdx,
                new LfoModel(
                    lm.rateHz(),
                    lm.waveform(),
                    lm.depth(),
                    lm.target(),
                    lm.isLocal(),
                    syncCombo.getSelectedIndex(),
                    lm.syncType()));
          });
      c.gridx = col++;
      controlsPanel.add(syncCombo, c);

      // Scope
      JComboBox<String> scopeCombo = new JComboBox<>(new String[] {"All tracks", "This track"});
      scopeCombo.setSelectedIndex(init.isLocal() ? 1 : 0);
      scopeCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
      scopeCombo.setForeground(Color.WHITE);
      scopeCombo.addActionListener(
          e -> {
            LfoModel lm = lfo(lfoIdx);
            model.setLfo(
                lfoIdx,
                new LfoModel(
                    lm.rateHz(),
                    lm.waveform(),
                    lm.depth(),
                    lm.target(),
                    scopeCombo.getSelectedIndex() == 1,
                    lm.syncLevel(),
                    lm.syncType()));
          });
      c.gridx = col;
      controlsPanel.add(scopeCombo, c);
    }

    // Depth note
    c.gridx = 0;
    c.gridy = BridgeContract.LFO_COUNT + 1;
    c.gridwidth = 7;
    JLabel note =
        new JLabel(
            "<html><i>Depth 100% = Filter ±5kHz, Res ±3Q, Pan ±1.0, Pitch ±1 oct, Vol ±50%, FM ±50%</i></html>");
    note.setForeground(Color.GRAY);
    controlsPanel.add(note, c);

    add(controlsPanel, BorderLayout.CENTER);

    // Create and wrap the LFO monitor
    monitor = new LfoMonitorComponent(model);
    JPanel monitorWrapper = new JPanel(new BorderLayout());
    monitorWrapper.setBackground(SwingSynthConfigDialog.BG_CARD);
    monitorWrapper.setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32), 1, true),
            "Modulation Monitor",
            javax.swing.border.TitledBorder.LEFT,
            javax.swing.border.TitledBorder.TOP,
            new Font("SansSerif", Font.BOLD, 11),
            Color.LIGHT_GRAY));
    monitorWrapper.add(monitor, BorderLayout.CENTER);
    add(monitorWrapper, BorderLayout.EAST);
  }

  public void startAnimation() {
    if (monitor != null) {
      monitor.startAnimation();
    }
  }

  public void stopAnimation() {
    if (monitor != null) {
      monitor.stopAnimation();
    }
  }
}
