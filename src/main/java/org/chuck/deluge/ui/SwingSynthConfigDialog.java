package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.Consequence;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;

/** Swing dialog for editing a Synth track: Arp, Filter, FM, and 4-slot LFO. */
public class SwingSynthConfigDialog extends JDialog {

  private static final String[] OSC_TYPES = {"SINE", "SAW", "SQUARE", "TRIANGLE", "NOISE"};
  private static final String[] SYNTH_MODES = {"SUBTRACTIVE", "FM", "RINGMOD"};
  private static final String[] POLY_MODES = {"POLY", "MONO", "LEGATO", "AUTO", "CHOKE"};

  private final JTabbedPane tabs = new JTabbedPane();
  private final ProjectModel projectModel;
  private final int trackIndex;

  public SwingSynthConfigDialog(
      Frame owner,
      SynthTrackModel model,
      ChuckVM vm,
      BridgeContract bridge,
      int trackIndex,
      ProjectModel projectModel) {
    super(owner, "Synth Config: " + model.getName(), false);
    this.projectModel = projectModel;
    this.trackIndex = trackIndex;
    setSize(1300, 750);
    setLocationRelativeTo(owner);
    setLayout(new BorderLayout());
    getContentPane().setBackground(new Color(0x1a, 0x1a, 0x1a));

    tabs.setBackground(new Color(0x25, 0x25, 0x25));
    tabs.setForeground(Color.WHITE);

    tabs.addTab("OSC / FILTER / FM", buildMainPanel(model, vm, bridge, trackIndex));
    // DX7 tab inserted at index 1 — visible only when synthMode==1 or dx7patch loaded
    Runnable reloadDialog =
        () -> {
          dispose();
          new SwingSynthConfigDialog(owner, model, vm, bridge, trackIndex, projectModel)
              .setVisible(true);
        };
    JPanel dx7Panel = new Dx7Panel(model, vm, bridge, trackIndex, this, reloadDialog);
    tabs.insertTab("DX7", null, dx7Panel, "DX7 6-operator FM editing", 1);
    tabs.setEnabledAt(1, model.getSynthMode() == 1);
    tabs.addTab("ALGORITHM", new AlgorithmPanel(model, bridge, trackIndex));
    tabs.addTab("OSC", new OscPanel(model, bridge, trackIndex, projectModel));
    tabs.addTab("LFO", new LfoPanel(vm, bridge, trackIndex));
    tabs.addTab("ARP", new ArpPanel(model, bridge, trackIndex, projectModel));
    tabs.addTab("ENVELOPE", new EnvelopePanel(model, bridge, trackIndex, projectModel));
    tabs.addTab("MODULATION", new ModulationPanel(model, bridge, trackIndex));
    tabs.addTab("COMPRESSOR", new CompressorPanel(model, bridge, trackIndex, projectModel));
    tabs.addTab("EQ", new EqPanel(model, bridge, trackIndex, projectModel));
    tabs.addTab("MOD FX", new ModFxPanel(model, bridge, trackIndex, projectModel));
    tabs.addTab("HPF", new HpfPanel(model, bridge, trackIndex, projectModel));
    tabs.addTab("AUTOMATION", new AutomationPanel(model, bridge, trackIndex));

    // Enable/disable DX7 tab when synth mode changes
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

    // ── Oscillator section ──
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 3;
    panel.add(sectionLabel("OSCILLATORS"), c);
    row++;

    // Osc1 type
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    JLabel osc1Lbl = label("Osc 1:");
    osc1Lbl.setToolTipText("Carrier oscillator waveform type");
    panel.add(osc1Lbl, c);
    c.gridx = 1;
    c.gridwidth = 2;
    JComboBox<String> osc1Combo = new JComboBox<>(OSC_TYPES);
    osc1Combo.setSelectedItem(model.getOsc1Type());
    osc1Combo.setBackground(new Color(0x33, 0x33, 0x33));
    osc1Combo.setForeground(Color.WHITE);
    osc1Combo.addActionListener(
        e -> {
          String sel = (String) osc1Combo.getSelectedItem();
          model.setOsc1Type(sel);
          int typeIdx =
              switch (sel) {
                case "SAW" -> 1;
                case "SQUARE" -> 2;
                case "TRIANGLE" -> 3;
                case "NOISE" -> 4;
                default -> 0;
              };
          bridge.setVelocity(trackIndex, 0, typeIdx);
        });
    panel.add(osc1Combo, c);
    row++;

    // Osc2 type
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    JLabel osc2Lbl = label("Osc 2:");
    osc2Lbl.setToolTipText("Second oscillator type (modulator in FM mode)");
    panel.add(osc2Lbl, c);
    c.gridx = 1;
    c.gridwidth = 2;
    String osc2Current = model.getOsc2Type();
    JComboBox<String> osc2Combo = new JComboBox<>(OSC_TYPES);
    if ("NONE".equals(osc2Current) || osc2Current == null) osc2Combo.setSelectedIndex(0);
    else osc2Combo.setSelectedItem(osc2Current);
    osc2Combo.setBackground(new Color(0x33, 0x33, 0x33));
    osc2Combo.setForeground(Color.WHITE);
    osc2Combo.addActionListener(e -> model.setOsc2Type((String) osc2Combo.getSelectedItem()));
    panel.add(osc2Combo, c);
    row++;

    // Retrigger Phase
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    JLabel retrigLbl = label("Retrig:");
    retrigLbl.setToolTipText(
        "Oscillator retrigger phase on note-on: FREE (no reset), RESET (phase=0), PHASE 90-270°");
    panel.add(retrigLbl, c);
    c.gridx = 1;
    c.gridwidth = 2;
    String[] RETRIG_OPTIONS = {"FREE", "RESET", "90°", "180°", "270°"};
    JComboBox<String> retrigCombo = new JComboBox<>(RETRIG_OPTIONS);
    int retrigIdx =
        switch (model.getRetrigPhase()) {
          case -1 -> 0;
          case 0 -> 1;
          case 90 -> 2;
          case 180 -> 3;
          case 270 -> 4;
          default -> 1;
        };
    retrigCombo.setSelectedIndex(retrigIdx);
    retrigCombo.setBackground(new Color(0x33, 0x33, 0x33));
    retrigCombo.setForeground(Color.WHITE);
    retrigCombo.addActionListener(
        e -> {
          int val =
              switch (retrigCombo.getSelectedIndex()) {
                case 0 -> -1;
                case 1 -> 0;
                case 2 -> 90;
                case 3 -> 180;
                case 4 -> 270;
                default -> 0;
              };
          model.setRetrigPhase(val);
        });
    panel.add(retrigCombo, c);
    row++;

    // Wave Index
    row =
        addSlider(
            panel,
            c,
            row,
            "Wave Idx:",
            "Wavetable position (0.0-1.0). Controls inter-table interpolation for wavetable-type oscillators.",
            0,
            1000,
            (int) (model.getWaveIndex() * 1000),
            val -> {
              model.setWaveIndex(val / 1000.0f);
              bridge.setWaveIndex(trackIndex, val / 1000.0f);
            },
            "",
            "waveIndex",
            projectModel,
            trackIndex);

    // ── Synth Mode ──
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 3;
    panel.add(sectionLabel("SYNTH MODE"), c);
    row++;

    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    JLabel modeLbl = label("Mode:");
    modeLbl.setToolTipText(
        "SUBTRACTIVE = single osc through filter; FM = mod→car FM; RINGMOD = carrier×mod");
    panel.add(modeLbl, c);
    c.gridx = 1;
    c.gridwidth = 2;
    JComboBox<String> modeCombo = new JComboBox<>(SYNTH_MODES);
    modeCombo.setSelectedIndex(Math.max(0, Math.min(2, model.getSynthMode())));
    modeCombo.setBackground(new Color(0x33, 0x33, 0x33));
    modeCombo.setForeground(Color.WHITE);
    modeCombo.addActionListener(
        e -> {
          int mode = modeCombo.getSelectedIndex();
          model.setSynthMode(mode);
          bridge.setSynthMode(trackIndex, mode);
          tabs.setEnabledAt(1, mode == 1);
        });
    panel.add(modeCombo, c);
    row++;

    // ── Polyphony ──
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 3;
    panel.add(sectionLabel("POLYPHONY"), c);
    row++;

    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    JLabel polyLbl = label("Mode:");
    polyLbl.setToolTipText(
        "POLY = multiple simultaneous notes; MONO = one note at a time; LEGATO = mono with legato sliding; AUTO = auto-select POLY or MONO; CHOKE = cut previous note");
    panel.add(polyLbl, c);
    c.gridx = 1;
    c.gridwidth = 2;
    JComboBox<String> polyCombo = new JComboBox<>(POLY_MODES);
    polyCombo.setSelectedItem(model.getPolyphony().name());
    polyCombo.setBackground(new Color(0x33, 0x33, 0x33));
    polyCombo.setForeground(Color.WHITE);
    polyCombo.addActionListener(
        e -> {
          SynthTrackModel.PolyphonyMode pm =
              SynthTrackModel.PolyphonyMode.valueOf((String) polyCombo.getSelectedItem());
          model.setPolyphony(pm);
          bridge.setPolyphony(trackIndex, pm.ordinal());
        });
    panel.add(polyCombo, c);
    row++;

    // VCNT
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    JLabel vcntLbl = label("VCNT:");
    vcntLbl.setToolTipText(
        "Maximum voices (1-16). Useless beyond track rows, but sets voice stealing limit.");
    panel.add(vcntLbl, c);
    c.gridx = 1;
    c.gridwidth = 2;
    JSpinner vcntSpinner = new JSpinner(new SpinnerNumberModel(model.getMaxVoiceCount(), 1, 16, 1));
    vcntSpinner.setBackground(new Color(0x33, 0x33, 0x33));
    vcntSpinner.setForeground(Color.WHITE);
    JSpinner.NumberEditor vcntEditor = (JSpinner.NumberEditor) vcntSpinner.getEditor();
    vcntEditor.getTextField().setBackground(new Color(0x33, 0x33, 0x33));
    vcntEditor.getTextField().setForeground(Color.WHITE);
    vcntSpinner.addChangeListener(
        e -> {
          int vc = (int) vcntSpinner.getValue();
          model.setMaxVoiceCount(vc);
          bridge.setMaxVoices(trackIndex, vc);
        });
    panel.add(vcntSpinner, c);
    row++;

    // ── Unison ──
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 3;
    panel.add(sectionLabel("UNISON"), c);
    row++;

    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    JLabel unisonNumLbl = label("Voices:");
    unisonNumLbl.setToolTipText("Number of unison voices (1-8)");
    panel.add(unisonNumLbl, c);
    c.gridx = 1;
    c.gridwidth = 2;
    JComboBox<Integer> unisonNumCombo = new JComboBox<>(new Integer[] {1, 2, 3, 4, 5, 6, 7, 8});
    unisonNumCombo.setSelectedItem(model.getUnisonNum());
    unisonNumCombo.setBackground(new Color(0x33, 0x33, 0x33));
    unisonNumCombo.setForeground(Color.WHITE);
    unisonNumCombo.addActionListener(
        e -> {
          int v = (Integer) unisonNumCombo.getSelectedItem();
          model.setUnisonNum(v);
          bridge.setUnisonNum(trackIndex, v);
        });
    panel.add(unisonNumCombo, c);
    row++;

    row =
        addSlider(
            panel,
            c,
            row,
            "Detune:",
            "Unison detune in cents (0-50). Higher values = wider, chorus-ier sound.",
            0,
            500,
            (int) (model.getUnisonDetune() * 100),
            val -> {
              model.setUnisonDetune(val / 100.0f);
              bridge.setUnisonDetune(trackIndex, val / 100.0f);
            },
            "cts",
            "unisonDetune",
            projectModel,
            trackIndex);

    row =
        addSlider(
            panel,
            c,
            row,
            "Spread:",
            "Unison stereo spread (0-50). Distributes voices across stereo field.",
            0,
            500,
            (int) (model.getUnisonStereoSpread() * 100),
            val -> {
              model.setUnisonStereoSpread(val / 100.0f);
              bridge.setUnisonSpread(trackIndex, val / 100.0f);
            },
            "",
            "unisonSpread",
            projectModel,
            trackIndex);

    // ── Arpeggiator reference ──
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 3;
    JLabel arpNote = new JLabel("Arpeggiator controls moved to ARP tab →");
    arpNote.setForeground(new Color(0x88, 0x88, 0x88));
    arpNote.setFont(arpNote.getFont().deriveFont(Font.ITALIC));
    panel.add(arpNote, c);
    row++;

    // ── Filter (LPF) ──
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 3;
    panel.add(sectionLabel("FILTER (LPF)"), c);
    row++;

    row =
        addSlider(
            panel,
            c,
            row,
            "Cutoff:",
            "Low-pass filter cutoff frequency (0% = fully closed, 100% = fully open)",
            0,
            100,
            (int) (bridge.getTrackFilterFreq(trackIndex) * 100),
            val -> bridge.setFilterFreq(trackIndex, val / 100.0),
            "%",
            "lpfCutoff",
            projectModel,
            trackIndex);
    row =
        addSlider(
            panel,
            c,
            row,
            "Resonance:",
            "Filter resonance / Q — emphasises frequencies around the cutoff",
            0,
            100,
            (int) (bridge.getTrackFilterRes(trackIndex) * 100),
            val -> bridge.setFilterRes(trackIndex, val / 100.0),
            "%",
            "lpfResonance",
            projectModel,
            trackIndex);

    row =
        addSlider(
            panel,
            c,
            row,
            "Drive:",
            "Filter drive / saturation (0–200%). >100% adds soft-clip saturation.",
            0,
            200,
            (int) (model.getFilterDrive() * 100),
            val -> {
              model.setFilterDrive(val / 100.0f);
              bridge.setFilterDrive(trackIndex, val / 100.0f);
            },
            "%",
            "filterDrive",
            projectModel,
            trackIndex);

    // Filter mode selector
    JComboBox<String> filterModeCombo =
        new JComboBox<>(new String[] {"LADDER_12", "LADDER_24", "SVF"});
    filterModeCombo.setSelectedItem(model.getFilterMode().name());
    filterModeCombo.setBackground(new Color(0x33, 0x33, 0x33));
    filterModeCombo.setForeground(Color.WHITE);

    // SVF NOTCH checkbox
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    panel.add(label("SVF NOTCH:"), c);
    c.gridx = 1;
    c.gridwidth = 2;
    JCheckBox notchBox = new JCheckBox("Enable NOTCH mode (SVF only)");
    notchBox.setSelected(model.isFilterNotch());
    notchBox.setEnabled(model.getFilterMode() == org.chuck.deluge.model.FilterMode.SVF);
    notchBox.setBackground(new Color(0x22, 0x22, 0x22));
    notchBox.setForeground(Color.WHITE);
    notchBox.addActionListener(
        e -> {
          model.setFilterNotch(notchBox.isSelected());
          bridge.setFilterNotch(trackIndex, notchBox.isSelected() ? 1 : 0);
        });
    filterModeCombo.addActionListener(
        e -> {
          String sel = (String) filterModeCombo.getSelectedItem();
          boolean isSvf = "SVF".equals(sel);
          notchBox.setEnabled(isSvf);
          if (!isSvf) notchBox.setSelected(false);
        });
    panel.add(notchBox, c);
    row++;

    // Filter route
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    JLabel routeLbl = label("Route:");
    routeLbl.setToolTipText("0=SERIES LPF→HPF, 1=SERIES HPF→LPF, 2=PARALLEL");
    panel.add(routeLbl, c);
    c.gridx = 1;
    c.gridwidth = 2;
    JComboBox<String> routeCombo =
        new JComboBox<>(new String[] {"SERIES LPF→HPF", "SERIES HPF→LPF", "PARALLEL"});
    routeCombo.setSelectedIndex(model.getFilterRoute());
    routeCombo.setBackground(new Color(0x33, 0x33, 0x33));
    routeCombo.setForeground(Color.WHITE);
    routeCombo.addActionListener(
        e -> {
          int route = routeCombo.getSelectedIndex();
          model.setFilterRoute(route);
          bridge.setFilterRoute(trackIndex, route);
        });
    panel.add(routeCombo, c);
    row++;

    // ── HPF reference ──
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 3;
    JLabel hpfNote = new JLabel("HPF controls moved to HPF tab →");
    hpfNote.setForeground(new Color(0x88, 0x88, 0x88));
    hpfNote.setFont(hpfNote.getFont().deriveFont(Font.ITALIC));
    panel.add(hpfNote, c);
    row++;

    // ── FM ──
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 3;
    panel.add(sectionLabel("FM SYNTHESIS"), c);
    row++;

    row =
        addSlider(
            panel,
            c,
            row,
            "FM Ratio:",
            "Frequency ratio of the modulator oscillator relative to the carrier (0.25–4.00)",
            25,
            400,
            (int) (bridge.getFmRatio(trackIndex) * 100),
            val -> bridge.setFmRatio(trackIndex, val / 100.0),
            "×0.01",
            "fmRatio",
            projectModel,
            trackIndex);
    row =
        addSlider(
            panel,
            c,
            row,
            "FM Amount:",
            "Depth of FM modulation — how strongly the modulator affects the carrier (0–100%)",
            0,
            100,
            (int) (bridge.getFmAmount(trackIndex) * 100),
            val -> bridge.setFmAmount(trackIndex, val / 100.0),
            "%",
            "fmAmount",
            projectModel,
            trackIndex);
    row =
        addSlider(
            panel,
            c,
            row,
            "Carrier 1 FB:",
            "Carrier 1 self-feedback amount (0–100%). Creates characteristic FM feedback timbre.",
            0,
            100,
            (int) (bridge.getCarrier1Fb(trackIndex) * 100),
            val -> bridge.setCarrier1Fb(trackIndex, val / 100.0f),
            "%",
            "carrier1Fb",
            projectModel,
            trackIndex);
    row =
        addSlider(
            panel,
            c,
            row,
            "Mod 1 FB:",
            "Modulator 1 self-feedback amount (0–100%). Adds complexity to FM timbre.",
            0,
            100,
            (int) (bridge.getMod1Fb(trackIndex) * 100),
            val -> bridge.setMod1Fb(trackIndex, val / 100.0f),
            "%",
            "mod1Fb",
            projectModel,
            trackIndex);
    row =
        addSlider(
            panel,
            c,
            row,
            "Mod 2 Amt:",
            "Modulator 2 output amount / gain (0–100%). Controls how strongly Mod 2 affects the carrier.",
            0,
            100,
            (int) (bridge.getMod2Amt(trackIndex) * 100),
            val -> bridge.setMod2Amt(trackIndex, val / 100.0f),
            "%",
            "mod2Amt",
            projectModel,
            trackIndex);
    row =
        addSlider(
            panel,
            c,
            row,
            "Mod 2 FB:",
            "Modulator 2 self-feedback amount (0–100%). Increases FM harmonic complexity.",
            0,
            100,
            (int) (bridge.getMod2Fb(trackIndex) * 100),
            val -> bridge.setMod2Fb(trackIndex, val / 100.0f),
            "%",
            "mod2Fb",
            projectModel,
            trackIndex);
    row =
        addSlider(
            panel,
            c,
            row,
            "Carrier 2 FB:",
            "Carrier 2 self-feedback amount (0–100%). Second carrier feedback for 2-op FM configurations.",
            0,
            100,
            (int) (bridge.getCarrier2Fb(trackIndex) * 100),
            val -> bridge.setCarrier2Fb(trackIndex, val / 100.0f),
            "%",
            "carrier2Fb",
            projectModel,
            trackIndex);

    return panel;
  }

  // ── Shared UI helpers (package-private, called by panel classes) ──

  static int addSlider(
      JPanel panel,
      GridBagConstraints c,
      int row,
      String labelText,
      String tooltip,
      int min,
      int max,
      int initial,
      java.util.function.IntConsumer onChange,
      String unit,
      String paramName,
      ProjectModel projectModel,
      int trackIndex) {
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    JLabel lbl = label(labelText);
    lbl.setToolTipText(tooltip);
    panel.add(lbl, c);
    c.gridx = 1;
    c.gridwidth = 1;
    JSlider slider = new JSlider(min, max, Math.max(min, Math.min(max, initial)));
    slider.setBackground(new Color(0x22, 0x22, 0x22));
    slider.setToolTipText(tooltip);
    c.gridx = 2;
    c.gridwidth = 1;
    JLabel valLabel = new JLabel(initial + unit);
    valLabel.setForeground(Color.CYAN);
    valLabel.setPreferredSize(new Dimension(60, 20));
    final long[] lastChangeTime = {0L};
    final boolean[] hasCapturedOld = {false};
    slider.addChangeListener(
        e -> {
          long now = System.currentTimeMillis();
          int newVal = slider.getValue();
          if (!hasCapturedOld[0] || (now - lastChangeTime[0]) > 300) {
            hasCapturedOld[0] = false;
            lastChangeTime[0] = now;
          }
          onChange.accept(newVal);
          valLabel.setText(newVal + unit);
        });
    slider.addMouseListener(
        new java.awt.event.MouseAdapter() {
          private float oldValue;

          @Override
          public void mousePressed(java.awt.event.MouseEvent e) {
            oldValue = slider.getValue();
            hasCapturedOld[0] = true;
            lastChangeTime[0] = System.currentTimeMillis();
          }

          @Override
          public void mouseReleased(java.awt.event.MouseEvent e) {
            if (!hasCapturedOld[0]) return;
            int newVal = slider.getValue();
            if (Math.abs(newVal - oldValue) < 0.001f) return;
            var stack = projectModel.getUndoRedoStack();
            stack.push(
                new Consequence.SynthParamConsequence(
                    trackIndex, paramName, oldValue, newVal, System.currentTimeMillis()));
            hasCapturedOld[0] = false;
          }
        });
    c.gridx = 1;
    panel.add(slider, c);
    c.gridx = 2;
    panel.add(valLabel, c);
    return row + 1;
  }

  static JLabel label(String text) {
    JLabel l = new JLabel(text);
    l.setForeground(Color.LIGHT_GRAY);
    return l;
  }

  static JLabel headerLabel(String text) {
    JLabel l = new JLabel(text);
    l.setForeground(new Color(0x00, 0xff, 0xcc));
    l.setFont(l.getFont().deriveFont(Font.BOLD, 11f));
    return l;
  }

  static JLabel sectionLabel(String text) {
    JLabel l = new JLabel(text);
    l.setForeground(new Color(0x00, 0xff, 0xcc));
    l.setFont(l.getFont().deriveFont(Font.BOLD));
    return l;
  }
}
