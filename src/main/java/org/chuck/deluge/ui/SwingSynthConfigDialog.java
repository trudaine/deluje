package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;
import java.util.ArrayList;
import java.util.List;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.AutomationParam;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.EnvelopeModel;
import org.chuck.deluge.model.ModKnob;
import org.chuck.deluge.model.PatchCable;
import org.chuck.deluge.model.SynthTrackModel;

/** Swing dialog for editing a Synth track: Arp, Filter, FM, and 4-slot LFO. */
public class SwingSynthConfigDialog extends JDialog {

  private static final String[] LFO_SHAPES = {"SINE", "SAW", "SQUARE", "TRI", "S&H", "RANDOM WALK", "WARBLER"};
  private static final String[] LFO_TARGETS = {"Filter", "Res", "Pan", "Pitch", "Vol", "FM"};
  private static final String[] OSC_TYPES = {"SINE", "SAW", "SQUARE", "TRIANGLE", "NOISE"};
  private static final String[] SYNTH_MODES = {"SUBTRACTIVE", "FM", "RINGMOD"};
  private static final String[] POLY_MODES = {"POLY", "MONO", "LEGATO", "AUTO", "CHOKE"};
  private static final String[] ENV_TARGETS = {"NONE", "VOLUME", "FILTER", "PITCH", "PAN"};
  private static final String[] MOD_SRC_OPTIONS = {"velocity", "envelope1", "envelope2", "envelope3", "envelope4",
      "lfo1", "lfo2", "lfo3", "lfo4", "aftertouch", "note", "random", "sidechain"};
  private static final String[] MOD_DST_OPTIONS = {"volume", "pan", "lpfFrequency", "lpfResonance",
      "oscAVolume", "oscBVolume", "pitch", "noiseVolume", "modFxRate", "modFxDepth"};

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
    tabs.addTab("ALGORITHM", buildAlgorithmPanel(model, bridge, trackIndex));
    tabs.addTab("LFO", buildLfoPanel(vm, bridge, trackIndex));
    tabs.addTab("ENVELOPE", buildEnvelopePanel(model, bridge, trackIndex));
    tabs.addTab("MODULATION", buildModulationPanel(model, bridge, trackIndex));
    tabs.addTab("AUTOMATION", buildAutomationPanel(model, bridge, trackIndex));

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
    c.gridx = 0; c.gridy = row; c.gridwidth = 3;
    panel.add(sectionLabel("OSCILLATORS"), c); row++;

    // Osc1 type
    c.gridx = 0; c.gridy = row; c.gridwidth = 1;
    JLabel osc1Lbl = label("Osc 1:");
    osc1Lbl.setToolTipText("Carrier oscillator waveform type");
    panel.add(osc1Lbl, c);
    c.gridx = 1; c.gridwidth = 2;
    JComboBox<String> osc1Combo = new JComboBox<>(OSC_TYPES);
    osc1Combo.setSelectedItem(model.getOsc1Type());
    osc1Combo.setBackground(new Color(0x33, 0x33, 0x33));
    osc1Combo.setForeground(Color.WHITE);
    osc1Combo.addActionListener(e -> {
      String sel = (String) osc1Combo.getSelectedItem();
      model.setOsc1Type(sel);
      int typeIdx = switch (sel) { case "SAW" -> 1; case "SQUARE" -> 2; case "TRIANGLE" -> 3; case "NOISE" -> 4; default -> 0; };
      bridge.setVelocity(trackIndex, 0, typeIdx); // temp push — real push via pushModelToBridge
    });
    panel.add(osc1Combo, c); row++;

    // Osc2 type
    c.gridx = 0; c.gridy = row; c.gridwidth = 1;
    JLabel osc2Lbl = label("Osc 2:");
    osc2Lbl.setToolTipText("Second oscillator type (modulator in FM mode)");
    panel.add(osc2Lbl, c);
    c.gridx = 1; c.gridwidth = 2;
    String osc2Current = model.getOsc2Type();
    JComboBox<String> osc2Combo = new JComboBox<>(OSC_TYPES);
    if ("NONE".equals(osc2Current) || osc2Current == null) osc2Combo.setSelectedIndex(0);
    else osc2Combo.setSelectedItem(osc2Current);
    osc2Combo.setBackground(new Color(0x33, 0x33, 0x33));
    osc2Combo.setForeground(Color.WHITE);
    osc2Combo.addActionListener(e -> model.setOsc2Type((String) osc2Combo.getSelectedItem()));
    panel.add(osc2Combo, c); row++;

    // Retrigger Phase
    c.gridx = 0; c.gridy = row; c.gridwidth = 1;
    JLabel retrigLbl = label("Retrig:");
    retrigLbl.setToolTipText("Oscillator retrigger phase on note-on: FREE (no reset), RESET (phase=0), PHASE 90-270°");
    panel.add(retrigLbl, c);
    c.gridx = 1; c.gridwidth = 2;
    String[] RETRIG_OPTIONS = {"FREE", "RESET", "90°", "180°", "270°"};
    JComboBox<String> retrigCombo = new JComboBox<>(RETRIG_OPTIONS);
    int retrigIdx = switch (model.getRetrigPhase()) {
      case -1 -> 0;
      case 0  -> 1;
      case 90 -> 2;
      case 180 -> 3;
      case 270 -> 4;
      default -> 1;
    };
    retrigCombo.setSelectedIndex(retrigIdx);
    retrigCombo.setBackground(new Color(0x33, 0x33, 0x33));
    retrigCombo.setForeground(Color.WHITE);
    retrigCombo.addActionListener(e -> {
      int val = switch (retrigCombo.getSelectedIndex()) {
        case 0 -> -1;
        case 1 -> 0;
        case 2 -> 90;
        case 3 -> 180;
        case 4 -> 270;
        default -> 0;
      };
      model.setRetrigPhase(val);
    });
    panel.add(retrigCombo, c); row++;

    // Wave Index (wavetable position for oscillator)
    row = addSlider(panel, c, row, "Wave Idx:",
        "Wavetable position (0.0-1.0). Controls inter-table interpolation for wavetable-type oscillators.",
        0, 1000, (int)(model.getWaveIndex() * 1000),
        val -> { model.setWaveIndex(val / 1000.0f); bridge.setWaveIndex(trackIndex, val / 1000.0f); }, "");

    // ── Synth Mode ──
    c.gridx = 0; c.gridy = row; c.gridwidth = 3;
    panel.add(sectionLabel("SYNTH MODE"), c); row++;

    c.gridx = 0; c.gridy = row; c.gridwidth = 1;
    JLabel modeLbl = label("Mode:");
    modeLbl.setToolTipText("SUBTRACTIVE = single osc through filter; FM = mod→car FM; RINGMOD = carrier×mod");
    panel.add(modeLbl, c);
    c.gridx = 1; c.gridwidth = 2;
    JComboBox<String> modeCombo = new JComboBox<>(SYNTH_MODES);
    modeCombo.setSelectedIndex(Math.max(0, Math.min(2, model.getSynthMode())));
    modeCombo.setBackground(new Color(0x33, 0x33, 0x33));
    modeCombo.setForeground(Color.WHITE);
    modeCombo.addActionListener(e -> {
      int mode = modeCombo.getSelectedIndex();
      model.setSynthMode(mode);
      bridge.setSynthMode(trackIndex, mode);
    });
    panel.add(modeCombo, c); row++;

    // ── Polyphony ──
    c.gridx = 0; c.gridy = row; c.gridwidth = 3;
    panel.add(sectionLabel("POLYPHONY"), c); row++;

    c.gridx = 0; c.gridy = row; c.gridwidth = 1;
    JLabel polyLbl = label("Mode:");
    polyLbl.setToolTipText("POLY = multiple simultaneous notes; MONO = one note at a time; LEGATO = mono with legato sliding; AUTO = auto-select POLY or MONO; CHOKE = cut previous note");
    panel.add(polyLbl, c);
    c.gridx = 1; c.gridwidth = 2;
    JComboBox<String> polyCombo = new JComboBox<>(POLY_MODES);
    polyCombo.setSelectedItem(model.getPolyphony().name());
    polyCombo.setBackground(new Color(0x33, 0x33, 0x33));
    polyCombo.setForeground(Color.WHITE);
    polyCombo.addActionListener(e -> {
      SynthTrackModel.PolyphonyMode pm = SynthTrackModel.PolyphonyMode.valueOf((String) polyCombo.getSelectedItem());
      model.setPolyphony(pm);
      bridge.setPolyphony(trackIndex, pm.ordinal());
    });
    panel.add(polyCombo, c); row++;

    // VCNT (voice count)
    c.gridx = 0; c.gridy = row; c.gridwidth = 1;
    JLabel vcntLbl = label("VCNT:");
    vcntLbl.setToolTipText("Maximum voices (1-16). Useless beyond track rows, but sets voice stealing limit.");
    panel.add(vcntLbl, c);
    c.gridx = 1; c.gridwidth = 2;
    JSpinner vcntSpinner = new JSpinner(new SpinnerNumberModel(model.getMaxVoiceCount(), 1, 16, 1));
    vcntSpinner.setBackground(new Color(0x33, 0x33, 0x33));
    vcntSpinner.setForeground(Color.WHITE);
    JSpinner.NumberEditor vcntEditor = (JSpinner.NumberEditor) vcntSpinner.getEditor();
    vcntEditor.getTextField().setBackground(new Color(0x33, 0x33, 0x33));
    vcntEditor.getTextField().setForeground(Color.WHITE);
    vcntSpinner.addChangeListener(e -> {
      int vc = (int) vcntSpinner.getValue();
      model.setMaxVoiceCount(vc);
      bridge.setMaxVoices(trackIndex, vc);
    });
    panel.add(vcntSpinner, c); row++;

    // ── Unison ──
    c.gridx = 0; c.gridy = row; c.gridwidth = 3;
    panel.add(sectionLabel("UNISON"), c); row++;

    c.gridx = 0; c.gridy = row; c.gridwidth = 1;
    JLabel unisonNumLbl = label("Voices:");
    unisonNumLbl.setToolTipText("Number of unison voices (1-8)");
    panel.add(unisonNumLbl, c);
    c.gridx = 1; c.gridwidth = 2;
    JComboBox<Integer> unisonNumCombo = new JComboBox<>(new Integer[]{1, 2, 3, 4, 5, 6, 7, 8});
    unisonNumCombo.setSelectedItem(model.getUnisonNum());
    unisonNumCombo.setBackground(new Color(0x33, 0x33, 0x33));
    unisonNumCombo.setForeground(Color.WHITE);
    unisonNumCombo.addActionListener(e -> {
      int v = (Integer) unisonNumCombo.getSelectedItem();
      model.setUnisonNum(v);
      bridge.setUnisonNum(trackIndex, v);
    });
    panel.add(unisonNumCombo, c); row++;

    row = addSlider(panel, c, row, "Detune:",
        "Unison detune in cents (0-50). Higher values = wider, chorus-ier sound.",
        0, 500, (int)(model.getUnisonDetune() * 100),
        val -> { model.setUnisonDetune(val / 100.0f); bridge.setUnisonDetune(trackIndex, val / 100.0f); }, "cts");

    row = addSlider(panel, c, row, "Spread:",
        "Unison stereo spread (0-50). Distributes voices across stereo field.",
        0, 500, (int)(model.getUnisonStereoSpread() * 100),
        val -> { model.setUnisonStereoSpread(val / 100.0f); bridge.setUnisonSpread(trackIndex, val / 100.0f); }, "");

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

    // Arp Mode
    c.gridx = 0; c.gridy = row; c.gridwidth = 1;
    panel.add(label("Mode:"), c);
    c.gridx = 1; c.gridwidth = 2;
    String[] arpModes = {"UP", "DOWN", "UP_DOWN", "RANDOM", "WALK"};
    JComboBox<String> arpModeBox = new JComboBox<>(arpModes);
    arpModeBox.setSelectedIndex(bridge.getArpMode(trackIndex));
    arpModeBox.setBackground(new Color(0x33, 0x33, 0x33));
    arpModeBox.setToolTipText("Arpeggiator note sequence direction");
    int idx = trackIndex;
    arpModeBox.addActionListener(e -> bridge.setArpMode(idx, arpModeBox.getSelectedIndex()));
    panel.add(arpModeBox, c); row++;

    row = addSlider(panel, c, row, "Rate (×):",
        "Arpeggiator speed multiplier (0.25× to 4.00×)",
        25, 400, (int)(bridge.getArpRate(trackIndex) * 100),
        val -> bridge.setArpRate(idx, val / 100.0), "×0.01");

    row = addSlider(panel, c, row, "Gate:",
        "Note-on duration as percentage of step (10%-100%)",
        10, 100, (int)(bridge.getArpGate(trackIndex) * 100),
        val -> bridge.setArpGate(idx, val / 100.0), "%");

    c.gridx = 0; c.gridy = row; c.gridwidth = 1;
    panel.add(label("Sync:"), c);
    c.gridx = 1; c.gridwidth = 2;
    String[] syncRates = {"OFF", "1/1", "1/2", "1/2T", "1/4", "1/4T", "1/8", "1/8T", "1/16", "1/16T", "1/32", "1/32T", "1/64"};
    JComboBox<String> syncCombo = new JComboBox<>(syncRates);
    syncCombo.setSelectedIndex(bridge.getArpSyncLevel(trackIndex));
    syncCombo.setBackground(new Color(0x33, 0x33, 0x33));
    syncCombo.setToolTipText("Sync arpeggiator rate to note division (overrides Rate slider)");
    syncCombo.addActionListener(e -> bridge.setArpSyncLevel(idx, syncCombo.getSelectedIndex()));
    panel.add(syncCombo, c); row++;

    c.gridx = 0; c.gridy = row; c.gridwidth = 1;
    panel.add(label("Octaves:"), c);
    c.gridx = 1; c.gridwidth = 2;
    JComboBox<Integer> octCombo = new JComboBox<>(new Integer[]{1, 2, 3, 4});
    octCombo.setSelectedItem(bridge.getArpOctave(trackIndex));
    octCombo.setBackground(new Color(0x33, 0x33, 0x33));
    octCombo.setToolTipText("Number of octaves the arpeggiator spans");
    octCombo.addActionListener(e -> bridge.setArpOctave(idx, (Integer) octCombo.getSelectedItem()));
    panel.add(octCombo, c); row++;

    // Note Mode
    c.gridx = 0; c.gridy = row; c.gridwidth = 1;
    panel.add(label("Note Mode:"), c);
    c.gridx = 1; c.gridwidth = 2;
    String[] noteModes = {"UP", "DOWN", "UPDN", "RAND", "WLK1", "WLK2", "WLK3", "PLAY", "PATT"};
    JComboBox<String> noteModeCombo = new JComboBox<>(noteModes);
    noteModeCombo.setSelectedIndex(bridge.getArpNoteMode(trackIndex));
    noteModeCombo.setBackground(new Color(0x33, 0x33, 0x33));
    noteModeCombo.setToolTipText("Note selection pattern within the arpeggiated chord");
    noteModeCombo.addActionListener(e -> bridge.setArpNoteMode(idx, noteModeCombo.getSelectedIndex()));
    panel.add(noteModeCombo, c); row++;

    // Octave Mode
    c.gridx = 0; c.gridy = row; c.gridwidth = 1;
    panel.add(label("Oct Mode:"), c);
    c.gridx = 1; c.gridwidth = 2;
    String[] octModes = {"UP", "DOWN", "UPDN", "ALT", "RAND"};
    JComboBox<String> octModeCombo = new JComboBox<>(octModes);
    octModeCombo.setSelectedIndex(bridge.getArpOctaveMode(trackIndex));
    octModeCombo.setBackground(new Color(0x33, 0x33, 0x33));
    octModeCombo.setToolTipText("Octave progression pattern");
    octModeCombo.addActionListener(e -> bridge.setArpOctaveMode(idx, octModeCombo.getSelectedIndex()));
    panel.add(octModeCombo, c); row++;

    row = addSlider(panel, c, row, "Repeat:",
        "Repeat each step N times (1-8)",
        1, 8, bridge.getArpStepRepeat(trackIndex),
        val -> bridge.setArpStepRepeat(idx, val), "×");

    row = addSlider(panel, c, row, "Rhythm:",
        "Rhythm pattern index (0-49). Controls step timing offsets.",
        0, 49, bridge.getArpRhythm(trackIndex),
        val -> bridge.setArpRhythm(idx, val), "");

    row = addSlider(panel, c, row, "Seq Len:",
        "Number of active steps in the arpeggiator sequence (1-16)",
        1, 16, bridge.getArpSeqLength(trackIndex),
        val -> bridge.setArpSeqLength(idx, val), "");

    // ── Filter (LPF) ──
    c.gridx = 0; c.gridy = row; c.gridwidth = 3;
    panel.add(sectionLabel("FILTER (LPF)"), c); row++;

    row = addSlider(panel, c, row, "Cutoff:",
        "Low-pass filter cutoff frequency (0% = fully closed, 100% = fully open)",
        0, 100, (int)(bridge.getTrackFilterFreq(trackIndex) * 100),
        val -> bridge.setFilterFreq(trackIndex, val / 100.0), "%");
    row = addSlider(panel, c, row, "Resonance:",
        "Filter resonance / Q — emphasises frequencies around the cutoff",
        0, 100, (int)(bridge.getTrackFilterRes(trackIndex) * 100),
        val -> bridge.setFilterRes(trackIndex, val / 100.0), "%");

    row = addSlider(panel, c, row, "Drive:",
        "Filter drive / saturation (0–200%). >100% adds soft-clip saturation.",
        0, 200, (int)(model.getFilterDrive() * 100),
        val -> { model.setFilterDrive(val / 100.0f); bridge.setFilterDrive(trackIndex, val / 100.0f); }, "%");

    // Filter mode selector (used for notch enable logic below)
    JComboBox<String> filterModeCombo = new JComboBox<>(new String[]{"LADDER_12", "LADDER_24", "SVF"});
    filterModeCombo.setSelectedItem(model.getFilterMode().name());
    filterModeCombo.setBackground(new Color(0x33, 0x33, 0x33));
    filterModeCombo.setForeground(Color.WHITE);

    // SVF NOTCH checkbox (only enabled when filter mode == SVF)
    c.gridx = 0; c.gridy = row; c.gridwidth = 1;
    panel.add(label("SVF NOTCH:"), c);
    c.gridx = 1; c.gridwidth = 2;
    JCheckBox notchBox = new JCheckBox("Enable NOTCH mode (SVF only)");
    notchBox.setSelected(model.isFilterNotch());
    notchBox.setEnabled(model.getFilterMode() == org.chuck.deluge.model.FilterMode.SVF);
    notchBox.setBackground(new Color(0x22, 0x22, 0x22));
    notchBox.setForeground(Color.WHITE);
    notchBox.addActionListener(e -> {
      model.setFilterNotch(notchBox.isSelected());
      bridge.setFilterNotch(trackIndex, notchBox.isSelected() ? 1 : 0);
    });
    // Update notch checkbox enable when filter mode changes
    filterModeCombo.addActionListener(e -> {
      String sel = (String) filterModeCombo.getSelectedItem();
      boolean isSvf = "SVF".equals(sel);
      notchBox.setEnabled(isSvf);
      if (!isSvf) notchBox.setSelected(false);
    });
    panel.add(notchBox, c); row++;

    // Filter route
    c.gridx = 0; c.gridy = row; c.gridwidth = 1;
    JLabel routeLbl = label("Route:");
    routeLbl.setToolTipText("0=SERIES LPF→HPF, 1=SERIES HPF→LPF, 2=PARALLEL");
    panel.add(routeLbl, c);
    c.gridx = 1; c.gridwidth = 2;
    JComboBox<String> routeCombo = new JComboBox<>(new String[]{"SERIES LPF→HPF", "SERIES HPF→LPF", "PARALLEL"});
    routeCombo.setSelectedIndex(model.getFilterRoute());
    routeCombo.setBackground(new Color(0x33, 0x33, 0x33));
    routeCombo.setForeground(Color.WHITE);
    routeCombo.addActionListener(e -> {
      int route = routeCombo.getSelectedIndex();
      model.setFilterRoute(route);
      bridge.setFilterRoute(trackIndex, route);
    });
    panel.add(routeCombo, c); row++;

    // ── Filter (HPF) ──
    c.gridx = 0; c.gridy = row; c.gridwidth = 3;
    panel.add(sectionLabel("FILTER (HPF)"), c); row++;

    row = addSlider(panel, c, row, "Cutoff:",
        "High-pass filter cutoff (0% = 20Hz/off, 100% = ~20kHz)",
        0, 100, (int)(bridge.getHpfFreq(trackIndex) / 200.0f * 100),
        val -> bridge.setHpfFreq(trackIndex, val / 100.0f * 200.0f), "Hz");
    row = addSlider(panel, c, row, "Resonance:",
        "HPF resonance / Q",
        0, 100, (int)(bridge.getHpfRes(trackIndex) * 100),
        val -> bridge.setHpfRes(trackIndex, val / 100.0f), "%");
    row = addSlider(panel, c, row, "Morph:",
        "HPF morph: 0=fully HP, 50=fully LP (inverted vs LPF morph). Only for SVF-based modes.",
        0, 50, (int)(bridge.getHpfMorph(trackIndex) * 50),
        val -> bridge.setHpfMorph(trackIndex, val / 50.0f), "");
    c.gridx = 0; c.gridy = row; c.gridwidth = 1;
    JLabel hpfModeLbl = label("HPF Mode:");
    hpfModeLbl.setToolTipText("0=LADDER_12, 1=LADDER_24, 2=SVF, 3=DRIVE, 4=SVF_BAND, 5=SVF_NOTCH");
    panel.add(hpfModeLbl, c);
    c.gridx = 1; c.gridwidth = 2;
    JComboBox<String> hpfModeCombo = new JComboBox<>(
        new String[]{"12dB", "24dB", "SVF", "DRIVE", "SVF BAND", "SVF NOTCH"});
    hpfModeCombo.setSelectedIndex(bridge.getHpfMode(trackIndex));
    hpfModeCombo.setBackground(new Color(0x33, 0x33, 0x33));
    hpfModeCombo.setForeground(Color.WHITE);
    hpfModeCombo.addActionListener(e ->
        bridge.setHpfMode(trackIndex, hpfModeCombo.getSelectedIndex()));
    panel.add(hpfModeCombo, c); row++;
    row = addSlider(panel, c, row, "FM:",
        "HPF FM amount — modulates HPF cutoff with audio-rate signal",
        0, 100, (int)(bridge.getHpfFm(trackIndex) * 100),
        val -> bridge.setHpfFm(trackIndex, val / 100.0f), "%");

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
    row = addSlider(panel, c, row, "Carrier FB:",
        "Carrier 1 self-feedback amount (0–100%). Creates characteristic FM feedback timbre.",
        0, 100, (int)(bridge.getCarrier1Fb(trackIndex) * 100),
        val -> bridge.setCarrier1Fb(trackIndex, val / 100.0f), "%");

    return panel;
  }

  private JPanel buildLfoPanel(ChuckVM vm, BridgeContract bridge, int trackIndex) {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBackground(new Color(0x22, 0x22, 0x22));
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(4, 8, 4, 8);
    c.anchor = GridBagConstraints.WEST;

    // ── Sync note divisions matching firmware ──
    String[] SYNC_VALS = {"OFF", "1/1", "1/2", "1/4", "1/8", "1/16", "1/32", "1/64",
                          "1/2T", "1/4T", "1/8T", "1/16T", "1/32T", "1/64T",
                          "1/2D", "1/4D", "1/8D", "1/16D", "1/32D", "1/64D"};

    // Header row
    int col = 0;
    c.gridx = col++; c.gridy = 0; panel.add(label(""), c);
    c.gridx = col++; panel.add(headerLabel("SHAPE"), c);
    c.gridx = col++; panel.add(headerLabel("RATE"), c);
    c.gridx = col++; panel.add(headerLabel("DEPTH"), c);
    c.gridx = col++; panel.add(headerLabel("TARGET"), c);
    c.gridx = col++; panel.add(headerLabel("SYNC"), c);
    c.gridx = col;   panel.add(headerLabel("SCOPE"), c);

    ChuckArray lfoRateArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_RATE);
    ChuckArray lfoTypeArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_TYPE);
    ChuckArray lfoDepthArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_DEPTH);
    ChuckArray lfoSyncArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_SYNC_LEVEL);

    for (int l = 0; l < BridgeContract.LFO_COUNT; l++) {
      final int lfoIdx = l;
      int row = l + 1;
      col = 0;

      c.gridx = col++; c.gridy = row;
      panel.add(label("LFO " + l + ":"), c);

      // Shape — use LFO_SHAPES which now has all 7 types: SINE, SAW, SQUARE, TRI, S&H, RANDOM WALK, WARBLER
      JComboBox<String> shapeCombo = new JComboBox<>(LFO_SHAPES);
      int lfoType = (int) lfoTypeArr.getInt(l);
      shapeCombo.setSelectedIndex(Math.max(0, Math.min(LFO_SHAPES.length - 1, lfoType)));
      shapeCombo.setBackground(new Color(0x33, 0x33, 0x33));
      shapeCombo.setForeground(Color.WHITE);
      shapeCombo.addActionListener(e -> {
        int type = shapeCombo.getSelectedIndex();
        lfoTypeArr.setInt(lfoIdx, (long) type);
        int sync = lfoSyncArr != null ? (int) lfoSyncArr.getInt(lfoIdx) : 0;
        bridge.setLfo(lfoIdx, lfoRateArr.getFloat(lfoIdx),
            type, lfoDepthArr.getFloat(lfoIdx), sync);
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
        int sync = lfoSyncArr != null ? (int) lfoSyncArr.getInt(lfoIdx) : 0;
        bridge.setLfo(lfoIdx, hz, (int) lfoTypeArr.getInt(lfoIdx), lfoDepthArr.getFloat(lfoIdx), sync);
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
        int sync = lfoSyncArr != null ? (int) lfoSyncArr.getInt(lfoIdx) : 0;
        bridge.setLfo(lfoIdx, lfoRateArr.getFloat(lfoIdx),
            (int) lfoTypeArr.getInt(lfoIdx), depth, sync);
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

      // Sync
      JComboBox<String> syncCombo = new JComboBox<>(SYNC_VALS);
      int curSync = lfoSyncArr != null ? Math.max(0, Math.min(SYNC_VALS.length - 1, (int) lfoSyncArr.getInt(l))) : 0;
      syncCombo.setSelectedIndex(curSync);
      syncCombo.setBackground(new Color(0x33, 0x33, 0x33));
      syncCombo.setForeground(Color.WHITE);
      syncCombo.addActionListener(e -> {
        int syncIdx = syncCombo.getSelectedIndex();
        if (lfoSyncArr != null) lfoSyncArr.setInt(lfoIdx, (long) syncIdx);
        bridge.setLfo(lfoIdx, lfoRateArr.getFloat(lfoIdx),
            (int) lfoTypeArr.getInt(lfoIdx), lfoDepthArr.getFloat(lfoIdx), syncIdx);
      });
      c.gridx = col++; panel.add(syncCombo, c);

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
    c.gridx = 0; c.gridy = BridgeContract.LFO_COUNT + 1; c.gridwidth = 7;
    JLabel note = new JLabel("<html><i>Depth 100% = Filter ±5kHz, Res ±3Q, Pan ±1.0, Pitch ±1 oct, Vol ±50%, FM ±50%</i></html>");
    note.setForeground(Color.GRAY);
    panel.add(note, c);

    return panel;
  }

  /** Build the synth algorithm selector tab. */
  private JPanel buildAlgorithmPanel(SynthTrackModel model, BridgeContract bridge, int trackIndex) {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBackground(new Color(0x22, 0x22, 0x22));
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(6, 10, 6, 10);
    c.anchor = GridBagConstraints.WEST;

    String[] algos = {"FM Synthesis", "Mandolin", "Rhodey EP", "ModalBar", "Moog Bass"};
    int[] algoValues = {0, 10, 11, 12, 13};

    c.gridx = 0; c.gridy = 0; c.gridwidth = 3;
    panel.add(sectionLabel("SYNTHESIS ALGORITHM"), c); c.gridy++;

    c.gridx = 0; c.gridwidth = 1;
    JLabel algoLbl = label("Engine:");
    algoLbl.setToolTipText("Select the sound engine for this track — FM or STK physical model");
    panel.add(algoLbl, c); c.gridx = 1; c.gridwidth = 2;

    JComboBox<String> algoCombo = new JComboBox<>(algos);
    algoCombo.setBackground(new Color(0x33, 0x33, 0x33));
    algoCombo.setForeground(Color.WHITE);
    // Select current algorithm
    int current = model.getSynthAlgorithm();
    for (int i = 0; i < algoValues.length; i++) {
      if (algoValues[i] == current) { algoCombo.setSelectedIndex(i); break; }
    }
    algoCombo.addActionListener(e -> {
      int idx = algoCombo.getSelectedIndex();
      int algo = algoValues[idx];
      model.setSynthAlgorithm(algo);
      bridge.setSynthAlgo(trackIndex, algo);
    });
    panel.add(algoCombo, c); c.gridy++;

    // Description panel
    c.gridx = 0; c.gridy++; c.gridwidth = 3;
    JTextArea desc = new JTextArea(
        "FM Synthesis — Classic 2-operator FM via MorphingWavetable. Use Ratio & Amount controls.\n" +
        "Mandolin — Plucked string physical model with body resonance.\n" +
        "Rhodey EP — FM electric piano based on the Rhodes sound.\n" +
        "ModalBar — Mallet percussion with adjustable bar material.\n" +
        "Moog Bass — Monophonic bass synthesizer with ladder filter."
    );
    desc.setEditable(false);
    desc.setBackground(new Color(0x2a, 0x2a, 0x2a));
    desc.setForeground(Color.LIGHT_GRAY);
    desc.setFont(desc.getFont().deriveFont(11f));
    desc.setBorder(BorderFactory.createEmptyBorder(10, 5, 10, 5));
    desc.setLineWrap(true);
    desc.setWrapStyleWord(true);
    panel.add(desc, c);

    return panel;
  }

  /**
   * Build the ENVELOPE tab: 4 sub-panels (ENV 0-3) with ADSR sliders + Target combo + Amount.
   */
  private JPanel buildEnvelopePanel(SynthTrackModel model, BridgeContract bridge, int trackIndex) {
    JPanel outer = new JPanel(new BorderLayout(4, 4));
    outer.setBackground(new Color(0x22, 0x22, 0x22));

    JTabbedPane envTabs = new JTabbedPane();
    envTabs.setBackground(new Color(0x25, 0x25, 0x25));
    envTabs.setForeground(Color.WHITE);

    for (int e = 0; e < 4; e++) {
      final int envIdx = e;
      EnvelopeModel env = model.getEnv(e);
      JPanel panel = new JPanel(new GridBagLayout());
      panel.setBackground(new Color(0x22, 0x22, 0x22));
      GridBagConstraints c = new GridBagConstraints();
      c.fill = GridBagConstraints.HORIZONTAL;
      c.insets = new Insets(6, 10, 6, 10);
      c.anchor = GridBagConstraints.WEST;
      int row = 0;

      // ADSR sliders
      row = addSlider(panel, c, row, "Attack:",
          "Time to reach peak level after note-on (" + envIdx + ")",
          1, 2000, (int)(env.attack() * 1000),
          val -> { model.setEnv(envIdx, new EnvelopeModel(val / 1000f, env.decay(), env.sustain(), env.release(), env.target(), env.amount())); },
          "ms");
      row = addSlider(panel, c, row, "Decay:",
          "Time to fall from peak to sustain level (" + envIdx + ")",
          0, 5000, (int)(env.decay() * 1000),
          val -> { model.setEnv(envIdx, new EnvelopeModel(env.attack(), val / 1000f, env.sustain(), env.release(), env.target(), env.amount())); },
          "ms");
      row = addSlider(panel, c, row, "Sustain:",
          "Level held while note is held (" + envIdx + ")",
          0, 100, (int)(env.sustain() * 100),
          val -> { model.setEnv(envIdx, new EnvelopeModel(env.attack(), env.decay(), val / 100f, env.release(), env.target(), env.amount())); },
          "%");
      row = addSlider(panel, c, row, "Release:",
          "Time to fade to silence after note-off (" + envIdx + ")",
          0, 5000, (int)(env.release() * 1000),
          val -> { model.setEnv(envIdx, new EnvelopeModel(env.attack(), env.decay(), env.sustain(), val / 1000f, env.target(), env.amount())); },
          "ms");

      // Target
      c.gridx = 0; c.gridy = row; c.gridwidth = 1;
      panel.add(label("Target:"), c);
      c.gridx = 1; c.gridwidth = 2;
      JComboBox<String> targetCombo = new JComboBox<>(ENV_TARGETS);
      targetCombo.setSelectedItem(env.target());
      targetCombo.setBackground(new Color(0x33, 0x33, 0x33));
      targetCombo.setForeground(Color.WHITE);
      targetCombo.addActionListener(ev -> {
        String sel = (String) targetCombo.getSelectedItem();
        model.setEnv(envIdx, new EnvelopeModel(env.attack(), env.decay(), env.sustain(), env.release(), sel, env.amount()));
      });
      panel.add(targetCombo, c);
      row++;

      // Amount
      row = addSlider(panel, c, row, "Amount:",
          "Depth of envelope modulation (0–100%)",
          0, 100, (int)(env.amount() * 100),
          val -> {
            float amt = val / 100f;
            model.setEnv(envIdx, new EnvelopeModel(env.attack(), env.decay(), env.sustain(), env.release(), env.target(), amt));
          },
          "%");

      envTabs.addTab("ENV " + e, panel);
    }

    outer.add(envTabs, BorderLayout.CENTER);

    JLabel note = new JLabel(
        "<html><i>Default: ENV0→Volume, ENV1→Filter, ENV2→Pitch, ENV3→Pan. " +
        "Set Target & Amount to override per envelope via patch cable.</i></html>");
    note.setForeground(Color.GRAY);
    note.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
    outer.add(note, BorderLayout.SOUTH);

    return outer;
  }

  /**
   * Build the MODULATION tab: patch cable table (top) and mod knob grid (bottom).
   */
  private JPanel buildModulationPanel(SynthTrackModel model, BridgeContract bridge, int trackIndex) {
    JPanel outer = new JPanel(new BorderLayout(4, 4));
    outer.setBackground(new Color(0x22, 0x22, 0x22));

    // ── Patch Cables section ──
    JPanel cablePanel = new JPanel(new BorderLayout(4, 4));
    cablePanel.setBackground(new Color(0x22, 0x22, 0x22));
    cablePanel.add(sectionLabel("PATCH CABLES"), BorderLayout.NORTH);

    String[] srcOptions = MOD_SRC_OPTIONS;
    String[] dstOptions = MOD_DST_OPTIONS;

    java.util.List<PatchCable> cables = model.getPatchCables();
    JPanel cableRows = new JPanel();
    cableRows.setLayout(new BoxLayout(cableRows, BoxLayout.Y_AXIS));
    cableRows.setBackground(new Color(0x22, 0x22, 0x22));
    java.util.List<JPanel> cableRowPanels = new ArrayList<>();

    // Rebuild cable rows from model
    // Use a list reference so the Runnable and remove buttons share the same view
    Runnable rebuildCableRows = () -> rebuildCableRows(cableRows, model, srcOptions, dstOptions);

    JScrollPane cableScroll = new JScrollPane(cableRows);
    cableScroll.setPreferredSize(new Dimension(500, 180));
    cablePanel.add(cableScroll, BorderLayout.CENTER);

    JButton addCableBtn = new JButton("+ Add Cable");
    addCableBtn.addActionListener(ev -> {
      model.addPatchCable(new PatchCable("velocity", "volume", 0.0f));
      rebuildCableRows.run();
    });
    cablePanel.add(addCableBtn, BorderLayout.SOUTH);

    // ── Mod Knobs section ──
    JPanel knobPanel = new JPanel(new BorderLayout(4, 4));
    knobPanel.setBackground(new Color(0x22, 0x22, 0x22));
    knobPanel.add(sectionLabel("MOD KNOBS (Gold Knobs)"), BorderLayout.NORTH);

    String[] knobParams = {"NONE", "volume", "pan", "reverb", "delay",
        "lpfFrequency", "lpfResonance", "hpfFrequency", "pitch", "oscAVolume",
        "oscBVolume", "noiseVolume", "modFxRate", "modFxDepth", "modFxFeedback"};

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
      knobCombo.addActionListener(ev -> {
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
    outer.add(split, BorderLayout.CENTER);

    return outer;
  }

  /**
   * Build the AUTOMATION tab: per-parameter × per-step slider table.
   * Operates on the active clip's automation data.
   */
  private JPanel buildAutomationPanel(SynthTrackModel model, BridgeContract bridge, int trackIndex) {
    JPanel panel = new JPanel(new BorderLayout(4, 4));
    panel.setBackground(new Color(0x22, 0x22, 0x22));

    ClipModel clip = model.getClips().isEmpty() ? null : model.getClips().get(model.getActiveClipIndex());
    int stepCount = (clip != null) ? clip.getStepCount() : 16;

    // ── Header with Clear All button ──
    JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
    topBar.setBackground(new Color(0x22, 0x22, 0x22));
    topBar.add(sectionLabel("PER-STEP AUTOMATION — Active clip: " + (clip != null ? clip.getName() : "none")));

    JButton clearAllBtn = new JButton("Clear All");
    clearAllBtn.setToolTipText("Remove all automation data for the active clip");
    clearAllBtn.addActionListener(e -> {
      if (clip != null) {
        for (String param : AutomationParam.ALL) {
          clip.clearAutomation(param);
        }
      }
    });
    topBar.add(clearAllBtn);
    panel.add(topBar, BorderLayout.NORTH);

    // ── Scrollable table: rows = params, columns = steps ──
    JPanel tablePanel = new JPanel(new GridBagLayout());
    tablePanel.setBackground(new Color(0x22, 0x22, 0x22));
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(2, 4, 2, 4);
    c.anchor = GridBagConstraints.WEST;

    // Column widths: param label = 140px, each step slider cell ~120px
    // Header row: corner + step numbers
    c.gridx = 0; c.gridy = 0; c.gridwidth = 1;
    tablePanel.add(headerLabel("PARAM"), c);
    for (int s = 0; s < stepCount; s++) {
      c.gridx = s + 1;
      tablePanel.add(headerLabel(String.valueOf(s + 1)), c);
    }

    // Param rows
    String[] paramNames = AutomationParam.ALL;
    for (int p = 0; p < paramNames.length; p++) {
      final int paramIdx = p;
      String paramName = paramNames[p];
      int row = p + 1;

      // Param label + enable checkbox
      c.gridx = 0; c.gridy = row; c.gridwidth = 1;
      boolean paramHasData = clip != null && clip.hasAutomation(paramName);
      JCheckBox enableBox = new JCheckBox(paramName, paramHasData);
      enableBox.setForeground(paramHasData ? Color.CYAN : Color.LIGHT_GRAY);
      enableBox.setBackground(new Color(0x22, 0x22, 0x22));
      enableBox.setPreferredSize(new Dimension(140, 24));
      enableBox.addActionListener(ev -> {
        if (clip == null) return;
        if (enableBox.isSelected()) {
          // Enable this param: set all steps to 0 automation
          for (int ss = 0; ss < clip.getStepCount(); ss++) {
            clip.setAutomation(paramName, ss, 0.0f);
          }
        } else {
          clip.clearAutomation(paramName);
        }
        enableBox.setForeground(enableBox.isSelected() ? Color.CYAN : Color.LIGHT_GRAY);
        // Refresh all slider cells in this row
        // (rebuilding the whole panel is simpler for this dialog)
        Container parent = tablePanel.getParent();
        if (parent instanceof JViewport) {
          ((JViewport) parent).getParent().revalidate();
          ((JViewport) parent).getParent().repaint();
        }
      });
      tablePanel.add(enableBox, c);

      // Per-step sliders
      for (int s = 0; s < stepCount; s++) {
        final int stepIdx = s;
        boolean hasAuto = clip != null && clip.hasAutomation(paramName, s);
        int val = hasAuto ? (int) (clip.getAutomation(paramName, s) * 127) : 0;

        JSlider slider = new JSlider(0, 127, val);
        slider.setBackground(new Color(0x22, 0x22, 0x22));
        slider.setPreferredSize(new Dimension(70, 22));
        slider.setPaintTicks(false);
        slider.setPaintLabels(false);
        slider.setEnabled(hasAuto);

        JLabel valLabel = new JLabel(hasAuto ? String.valueOf(val) : "-");
        valLabel.setForeground(hasAuto ? Color.CYAN : Color.DARK_GRAY);
        valLabel.setPreferredSize(new Dimension(30, 20));

        slider.addChangeListener(ev -> {
          if (clip == null || !slider.isEnabled()) return;
          clip.setAutomation(paramName, stepIdx, slider.getValue() / 127.0f);
          valLabel.setText(String.valueOf(slider.getValue()));
        });

        JPanel cell = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        cell.setBackground(new Color(0x22, 0x22, 0x22));
        cell.add(slider);
        cell.add(valLabel);

        c.gridx = s + 1; c.gridy = row;
        tablePanel.add(cell, c);
      }
    }

    JScrollPane scroll = new JScrollPane(tablePanel);
    scroll.setPreferredSize(new Dimension(900, 400));
    panel.add(scroll, BorderLayout.CENTER);

    return panel;
  }

  /** Rebuild the patch cable rows panel from the model. */
  private static void rebuildCableRows(
      JPanel cableRows,
      SynthTrackModel model,
      String[] srcOptions,
      String[] dstOptions) {
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
      srcCombo.addActionListener(ev -> {
        PatchCable old = model.getPatchCables().get(idx);
        model.getPatchCables().set(idx, new PatchCable(
            (String) srcCombo.getSelectedItem(), old.destination(), old.amount()));
      });
      row.add(new JLabel("Src:"));

      JComboBox<String> dstCombo = new JComboBox<>(dstOptions);
      dstCombo.setSelectedItem(pc.destination());
      dstCombo.setBackground(new Color(0x33, 0x33, 0x33));
      dstCombo.setForeground(Color.WHITE);
      dstCombo.addActionListener(ev -> {
        PatchCable old = model.getPatchCables().get(idx);
        model.getPatchCables().set(idx, new PatchCable(
            old.source(), (String) dstCombo.getSelectedItem(), old.amount()));
      });
      row.add(new JLabel("Dst:"));

      boolean isBipolar = pc.polarity() == PatchCable.Polarity.BIPOLAR;
      int sliderMin = isBipolar ? -100 : 0;
      JSlider amtSlider = new JSlider(sliderMin, 100, (int)(pc.amount() * 100));
      amtSlider.setBackground(new Color(0x22, 0x22, 0x22));
      JLabel amtVal = new JLabel(String.format("%.0f%%", pc.amount() * 100));
      amtVal.setForeground(Color.CYAN);

      JToggleButton polBtn = new JToggleButton("Bi", isBipolar);
      polBtn.setToolTipText("Toggle bipolar (Bi) / unipolar (Uni) mode");
      polBtn.setFont(polBtn.getFont().deriveFont(java.awt.Font.PLAIN, 10f));
      polBtn.setBackground(isBipolar ? new Color(0x66, 0x44, 0x00) : new Color(0x33, 0x33, 0x33));
      polBtn.setForeground(Color.WHITE);
      polBtn.setPreferredSize(new Dimension(40, 22));
      polBtn.addActionListener(ev -> {
        PatchCable old = model.getPatchCables().get(idx);
        PatchCable.Polarity newPol = polBtn.isSelected() ? PatchCable.Polarity.BIPOLAR : PatchCable.Polarity.UNIPOLAR;
        model.getPatchCables().set(idx, new PatchCable(old.source(), old.destination(), old.amount(), newPol));
        polBtn.setBackground(polBtn.isSelected() ? new Color(0x66, 0x44, 0x00) : new Color(0x33, 0x33, 0x33));
        // Adjust slider range: bipolar goes -100..+100, unipolar goes 0..+100
        if (polBtn.isSelected()) {
          amtSlider.setMinimum(-100);
        } else {
          amtSlider.setMinimum(0);
          if (amtSlider.getValue() < 0) amtSlider.setValue(0);
        }
      });
      row.add(polBtn);

      amtSlider.addChangeListener(ev -> {
        float v = amtSlider.getValue() / 100f;
        PatchCable old = model.getPatchCables().get(idx);
        model.getPatchCables().set(idx, new PatchCable(old.source(), old.destination(), v, old.polarity()));
        amtVal.setText(String.format("%.0f%%", v * 100));
      });
      row.add(amtSlider);
      row.add(amtVal);

      JButton removeBtn = new JButton("X");
      removeBtn.addActionListener(ev -> {
        model.getPatchCables().remove(idx);
        rebuildCableRows(cableRows, model, srcOptions, dstOptions);
      });
      row.add(removeBtn);

      cableRows.add(row);
    }
    cableRows.revalidate();
    cableRows.repaint();
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
