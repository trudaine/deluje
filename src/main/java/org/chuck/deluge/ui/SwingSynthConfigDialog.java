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
  public static final Color BG_DARK = new Color(0x12, 0x12, 0x14);
  public static final Color BG_CARD = new Color(0x1a, 0x1a, 0x1e);
  public static final Color BG_CONTROL = new Color(0x2d, 0x2d, 0x32);
  public static final Color ACCENT_BLUE = new Color(0x00, 0xcc, 0xff);
  public static final Color ACCENT_MINT = new Color(0x00, 0xff, 0xcc);

  private static final String[] OSC_TYPES = {"SINE", "SAW", "SQUARE", "TRIANGLE", "NOISE"};
  private static final String[] SYNTH_MODES = {"SUBTRACTIVE", "FM", "RINGMOD"};
  private static final String[] POLY_MODES = {"POLY", "MONO", "LEGATO", "AUTO", "CHOKE"};

  private final JTabbedPane tabs = new JTabbedPane();
  private final ProjectModel projectModel;
  private final int trackIndex;
  private MidiLearnPanel midiLearnPanel;
  private static JLabel globalHelpLabel;
  private static final String DEFAULT_HELP_TEXT =
      "<html>\uD83D\uDCA1 <b>QUICK HELP:</b> Hover over any control knob or slider to see its details and hardware mappings here!</html>";

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
    getContentPane().setBackground(BG_DARK);

    tabs.setBackground(BG_CARD);
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

    midiLearnPanel = new MidiLearnPanel();
    tabs.addTab("MIDI LEARN", midiLearnPanel);

    // Enable/disable DX7 tab when synth mode changes
    add(tabs, BorderLayout.CENTER);

    // ── Composite South Panel Stack (Help Bar + Close button) ──
    JPanel southStack = new JPanel();
    southStack.setLayout(new BoxLayout(southStack, BoxLayout.Y_AXIS));

    JPanel helpBarPanel = new JPanel(new BorderLayout());
    helpBarPanel.setBackground(new Color(0x1a, 0x1a, 0x1e));
    helpBarPanel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x2d, 0x2d, 0x32)),
            BorderFactory.createEmptyBorder(8, 16, 8, 16)));
    globalHelpLabel = new JLabel(DEFAULT_HELP_TEXT);
    globalHelpLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
    globalHelpLabel.setForeground(Color.LIGHT_GRAY);
    helpBarPanel.add(globalHelpLabel, BorderLayout.CENTER);
    southStack.add(helpBarPanel);

    JButton closeBtn = new JButton("Close");
    closeBtn.addActionListener(e -> dispose());
    JPanel south = new JPanel();
    south.setBackground(new Color(0x25, 0x25, 0x25));
    south.add(closeBtn);
    southStack.add(south);

    add(southStack, BorderLayout.SOUTH);
    DarkComboBoxRenderer.styleComponentTree(this);
  }

  @Override
  public void dispose() {
    if (midiLearnPanel != null) {
      midiLearnPanel.stopTimer();
    }
    super.dispose();
  }

  private JPanel buildMainPanel(
      SynthTrackModel model, ChuckVM vm, BridgeContract bridge, int trackIndex) {
    JPanel mainContainer = new JPanel(new GridBagLayout());
    mainContainer.setBackground(BG_CARD);

    JPanel leftPanel = new JPanel(new GridBagLayout());
    leftPanel.setBackground(BG_CARD);
    GridBagConstraints lc = new GridBagConstraints();
    lc.fill = GridBagConstraints.HORIZONTAL;
    lc.insets = new Insets(6, 10, 6, 10);
    lc.anchor = GridBagConstraints.WEST;
    int leftRow = 0;

    JPanel rightPanel = new JPanel(new GridBagLayout());
    rightPanel.setBackground(BG_CARD);
    GridBagConstraints rc = new GridBagConstraints();
    rc.fill = GridBagConstraints.HORIZONTAL;
    rc.insets = new Insets(6, 10, 6, 10);
    rc.anchor = GridBagConstraints.WEST;
    int rightRow = 0;

    // ── Left Column: Oscillators & Mode & Poly & Unison ──

    // ── Oscillator section ──
    lc.gridx = 0;
    lc.gridy = leftRow;
    lc.gridwidth = 3;
    leftPanel.add(sectionLabel("OSCILLATORS"), lc);
    leftRow++;

    // Osc1 type
    lc.gridx = 0;
    lc.gridy = leftRow;
    lc.gridwidth = 1;
    JLabel osc1Lbl = label("Osc 1:");
    osc1Lbl.setToolTipText("Carrier oscillator waveform type");
    leftPanel.add(osc1Lbl, lc);
    lc.gridx = 1;
    lc.gridwidth = 2;
    JComboBox<String> osc1Combo = new JComboBox<>(OSC_TYPES);
    osc1Combo.setSelectedItem(model.getOsc1Type());
    osc1Combo.setBackground(BG_CONTROL);
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
    leftPanel.add(osc1Combo, lc);
    leftRow++;

    // Osc2 type
    lc.gridx = 0;
    lc.gridy = leftRow;
    lc.gridwidth = 1;
    JLabel osc2Lbl = label("Osc 2:");
    osc2Lbl.setToolTipText("Second oscillator type (modulator in FM mode)");
    leftPanel.add(osc2Lbl, lc);
    lc.gridx = 1;
    lc.gridwidth = 2;
    String osc2Current = model.getOsc2Type();
    JComboBox<String> osc2Combo = new JComboBox<>(OSC_TYPES);
    if ("NONE".equals(osc2Current) || osc2Current == null) osc2Combo.setSelectedIndex(0);
    else osc2Combo.setSelectedItem(osc2Current);
    osc2Combo.setBackground(BG_CONTROL);
    osc2Combo.setForeground(Color.WHITE);
    osc2Combo.addActionListener(e -> model.setOsc2Type((String) osc2Combo.getSelectedItem()));
    leftPanel.add(osc2Combo, lc);
    leftRow++;

    // Retrigger Phase
    lc.gridx = 0;
    lc.gridy = leftRow;
    lc.gridwidth = 1;
    JLabel retrigLbl = label("Retrig:");
    retrigLbl.setToolTipText(
        "Oscillator retrigger phase on note-on: FREE (no reset), RESET (phase=0), PHASE 90-270°");
    leftPanel.add(retrigLbl, lc);
    lc.gridx = 1;
    lc.gridwidth = 2;
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
    retrigCombo.setBackground(BG_CONTROL);
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
    leftPanel.add(retrigCombo, lc);
    leftRow++;

    // Wave Index
    leftRow =
        addSlider(
            leftPanel,
            lc,
            leftRow,
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
    lc.gridx = 0;
    lc.gridy = leftRow;
    lc.gridwidth = 3;
    leftPanel.add(sectionLabel("SYNTH MODE"), lc);
    leftRow++;

    lc.gridx = 0;
    lc.gridy = leftRow;
    lc.gridwidth = 1;
    JLabel modeLbl = label("Mode:");
    modeLbl.setToolTipText(
        "SUBTRACTIVE = single osc through filter; FM = mod→car FM; RINGMOD = carrier×mod");
    leftPanel.add(modeLbl, lc);
    lc.gridx = 1;
    lc.gridwidth = 2;
    JComboBox<String> modeCombo = new JComboBox<>(SYNTH_MODES);
    modeCombo.setSelectedIndex(Math.max(0, Math.min(2, model.getSynthMode())));
    modeCombo.setBackground(BG_CONTROL);
    modeCombo.setForeground(Color.WHITE);
    modeCombo.addActionListener(
        e -> {
          int mode = modeCombo.getSelectedIndex();
          model.setSynthMode(mode);
          bridge.setSynthMode(trackIndex, mode);
          tabs.setEnabledAt(1, mode == 1);
        });
    leftPanel.add(modeCombo, lc);
    leftRow++;

    // ── Polyphony ──
    lc.gridx = 0;
    lc.gridy = leftRow;
    lc.gridwidth = 3;
    leftPanel.add(sectionLabel("POLYPHONY"), lc);
    leftRow++;

    lc.gridx = 0;
    lc.gridy = leftRow;
    lc.gridwidth = 1;
    JLabel polyLbl = label("Mode:");
    polyLbl.setToolTipText(
        "POLY = multiple simultaneous notes; MONO = one note at a time; LEGATO = mono with legato sliding; AUTO = auto-select POLY or MONO; CHOKE = cut previous note");
    leftPanel.add(polyLbl, lc);
    lc.gridx = 1;
    lc.gridwidth = 2;
    JComboBox<String> polyCombo = new JComboBox<>(POLY_MODES);
    polyCombo.setSelectedItem(model.getPolyphony().name());
    polyCombo.setBackground(BG_CONTROL);
    polyCombo.setForeground(Color.WHITE);
    polyCombo.addActionListener(
        e -> {
          SynthTrackModel.PolyphonyMode pm =
              SynthTrackModel.PolyphonyMode.valueOf((String) polyCombo.getSelectedItem());
          model.setPolyphony(pm);
          bridge.setPolyphony(trackIndex, pm.ordinal());
        });
    leftPanel.add(polyCombo, lc);
    leftRow++;

    // VCNT
    lc.gridx = 0;
    lc.gridy = leftRow;
    lc.gridwidth = 1;
    JLabel vcntLbl = label("VCNT:");
    vcntLbl.setToolTipText(
        "Maximum voices (1-16). Useless beyond track rows, but sets voice stealing limit.");
    leftPanel.add(vcntLbl, lc);
    lc.gridx = 1;
    lc.gridwidth = 2;
    JSpinner vcntSpinner = new JSpinner(new SpinnerNumberModel(model.getMaxVoiceCount(), 1, 16, 1));
    vcntSpinner.setBackground(BG_CONTROL);
    vcntSpinner.setForeground(Color.WHITE);
    JSpinner.NumberEditor vcntEditor = (JSpinner.NumberEditor) vcntSpinner.getEditor();
    vcntEditor.getTextField().setBackground(BG_CONTROL);
    vcntEditor.getTextField().setForeground(Color.WHITE);
    vcntSpinner.addChangeListener(
        e -> {
          int vc = (int) vcntSpinner.getValue();
          model.setMaxVoiceCount(vc);
          bridge.setMaxVoices(trackIndex, vc);
        });
    leftPanel.add(vcntSpinner, lc);
    leftRow++;

    // ── Unison ──
    lc.gridx = 0;
    lc.gridy = leftRow;
    lc.gridwidth = 3;
    leftPanel.add(sectionLabel("UNISON"), lc);
    leftRow++;

    lc.gridx = 0;
    lc.gridy = leftRow;
    lc.gridwidth = 1;
    JLabel unisonNumLbl = label("Voices:");
    unisonNumLbl.setToolTipText("Number of unison voices (1-8)");
    leftPanel.add(unisonNumLbl, lc);
    lc.gridx = 1;
    lc.gridwidth = 2;
    JComboBox<Integer> unisonNumCombo = new JComboBox<>(new Integer[] {1, 2, 3, 4, 5, 6, 7, 8});
    unisonNumCombo.setSelectedItem(model.getUnisonNum());
    unisonNumCombo.setBackground(BG_CONTROL);
    unisonNumCombo.setForeground(Color.WHITE);
    unisonNumCombo.addActionListener(
        e -> {
          int v = (Integer) unisonNumCombo.getSelectedItem();
          model.setUnisonNum(v);
          bridge.setUnisonNum(trackIndex, v);
        });
    leftPanel.add(unisonNumCombo, lc);
    leftRow++;

    leftRow =
        addSlider(
            leftPanel,
            lc,
            leftRow,
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

    leftRow =
        addSlider(
            leftPanel,
            lc,
            leftRow,
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

    // ── Right Column: Filter LPF & FM Synthesis ──

    // ── Filter (LPF) ──
    rc.gridx = 0;
    rc.gridy = rightRow;
    rc.gridwidth = 3;
    rightPanel.add(sectionLabel("FILTER (LPF)"), rc);
    rightRow++;

    rightRow =
        addSlider(
            rightPanel,
            rc,
            rightRow,
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

    rightRow =
        addSlider(
            rightPanel,
            rc,
            rightRow,
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

    rightRow =
        addSlider(
            rightPanel,
            rc,
            rightRow,
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

    // Filter Mode Selector
    rc.gridx = 0;
    rc.gridy = rightRow;
    rc.gridwidth = 1;
    rightPanel.add(label("Mode:"), rc);
    rc.gridx = 1;
    rc.gridwidth = 2;
    JComboBox<String> filterModeCombo =
        new JComboBox<>(new String[] {"LADDER_12", "LADDER_24", "SVF"});
    filterModeCombo.setSelectedItem(model.getFilterMode().name());
    filterModeCombo.setBackground(BG_CONTROL);
    filterModeCombo.setForeground(Color.WHITE);
    rightPanel.add(filterModeCombo, rc);
    rightRow++;

    // SVF NOTCH checkbox
    rc.gridx = 0;
    rc.gridy = rightRow;
    rc.gridwidth = 1;
    rightPanel.add(label("SVF NOTCH:"), rc);
    rc.gridx = 1;
    rc.gridwidth = 2;
    JCheckBox notchBox = new JCheckBox("Enable NOTCH mode (SVF only)");
    notchBox.setSelected(model.isFilterNotch());
    notchBox.setEnabled(model.getFilterMode() == org.chuck.deluge.model.FilterMode.SVF);
    notchBox.setBackground(BG_CARD);
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
          if (!isSvf) {
            notchBox.setSelected(false);
            model.setFilterNotch(false);
            bridge.setFilterNotch(trackIndex, 0);
          }
          org.chuck.deluge.model.FilterMode fm = org.chuck.deluge.model.FilterMode.valueOf(sel);
          model.setFilterMode(fm);
          bridge.setFilterMode(trackIndex, fm.ordinal());
        });
    rightPanel.add(notchBox, rc);
    rightRow++;

    // Filter route
    rc.gridx = 0;
    rc.gridy = rightRow;
    rc.gridwidth = 1;
    JLabel routeLbl = label("Route:");
    routeLbl.setToolTipText("0=SERIES LPF→HPF, 1=SERIES HPF→LPF, 2=PARALLEL");
    rightPanel.add(routeLbl, rc);
    rc.gridx = 1;
    rc.gridwidth = 2;
    JComboBox<String> routeCombo =
        new JComboBox<>(new String[] {"SERIES LPF→HPF", "SERIES HPF→LPF", "PARALLEL"});
    routeCombo.setSelectedIndex(model.getFilterRoute());
    routeCombo.setBackground(BG_CONTROL);
    routeCombo.setForeground(Color.WHITE);
    routeCombo.addActionListener(
        e -> {
          int route = routeCombo.getSelectedIndex();
          model.setFilterRoute(route);
          bridge.setFilterRoute(trackIndex, route);
        });
    rightPanel.add(routeCombo, rc);
    rightRow++;

    // ── FM ──
    rc.gridx = 0;
    rc.gridy = rightRow;
    rc.gridwidth = 3;
    rightPanel.add(sectionLabel("FM SYNTHESIS"), rc);
    rightRow++;

    rightRow =
        addSlider(
            rightPanel,
            rc,
            rightRow,
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

    rightRow =
        addSlider(
            rightPanel,
            rc,
            rightRow,
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

    rightRow =
        addSlider(
            rightPanel,
            rc,
            rightRow,
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

    rightRow =
        addSlider(
            rightPanel,
            rc,
            rightRow,
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

    rightRow =
        addSlider(
            rightPanel,
            rc,
            rightRow,
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

    rightRow =
        addSlider(
            rightPanel,
            rc,
            rightRow,
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

    rightRow =
        addSlider(
            rightPanel,
            rc,
            rightRow,
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

    // ── Mount Left and Right Side-by-Side ──
    mainContainer.setLayout(new GridBagLayout());
    GridBagConstraints mc = new GridBagConstraints();
    mc.fill = GridBagConstraints.BOTH;
    mc.gridy = 0;
    mc.weighty = 1.0;

    mc.gridx = 0;
    mc.weightx = 0.48;
    mainContainer.add(leftPanel, mc);

    mc.gridx = 1;
    mc.weightx = 0.04;
    mc.insets = new Insets(0, 15, 0, 15);
    JSeparator separator = new JSeparator(JSeparator.VERTICAL);
    separator.setForeground(new Color(0x33, 0x33, 0x35));
    mainContainer.add(separator, mc);

    mc.gridx = 2;
    mc.weightx = 0.48;
    mc.insets = new Insets(0, 0, 0, 0);
    mainContainer.add(rightPanel, mc);

    return mainContainer;
  }

  // ── Shared UI helpers (package-private, called by panel classes) ──

  public static void updateGlobalHelp(String htmlHelpText) {
    if (globalHelpLabel != null) {
      if (htmlHelpText == null || htmlHelpText.isEmpty()) {
        resetGlobalHelp();
      } else {
        globalHelpLabel.setText("<html>\uD83D\uDCA1 " + htmlHelpText + "</html>");
      }
    }
  }

  public static void resetGlobalHelp() {
    if (globalHelpLabel != null) {
      globalHelpLabel.setText(DEFAULT_HELP_TEXT);
    }
  }

  public static void attachHoverHelp(JComponent comp, String helpText) {
    if (comp == null || helpText == null || helpText.isEmpty()) return;
    comp.addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mouseEntered(java.awt.event.MouseEvent e) {
            updateGlobalHelp(helpText);
          }

          @Override
          public void mouseExited(java.awt.event.MouseEvent e) {
            resetGlobalHelp();
          }
        });
  }

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
    attachHoverHelp(lbl, tooltip);
    panel.add(lbl, c);
    c.gridx = 1;
    c.gridwidth = 1;
    JSlider slider = new JSlider(min, max, Math.max(min, Math.min(max, initial)));
    slider.setBackground(BG_CARD);
    slider.setToolTipText(tooltip);
    attachHoverHelp(slider, tooltip);
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
    l.setForeground(ACCENT_MINT);
    l.setFont(l.getFont().deriveFont(Font.BOLD, 11f));
    return l;
  }

  static JLabel sectionLabel(String text) {
    JLabel l = new JLabel(text);
    l.setForeground(ACCENT_MINT);
    l.setFont(l.getFont().deriveFont(Font.BOLD));
    return l;
  }
}
