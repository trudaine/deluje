package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import javax.swing.SwingUtilities;
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
import org.chuck.audio.util.Dx7Patch;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;

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

  private final JTabbedPane tabs = new JTabbedPane();

  public SwingSynthConfigDialog(
      Frame owner, SynthTrackModel model, ChuckVM vm, BridgeContract bridge, int trackIndex) {
    super(owner, "Synth Config: " + model.getName(), false);
    setSize(1300, 750);
    setLocationRelativeTo(owner);
    setLayout(new BorderLayout());
    getContentPane().setBackground(new Color(0x1a, 0x1a, 0x1a));

    tabs.setBackground(new Color(0x25, 0x25, 0x25));
    tabs.setForeground(Color.WHITE);

    tabs.addTab("ARP / FILTER / FM", buildMainPanel(model, vm, bridge, trackIndex));
    // DX7 tab inserted at index 1 — visible only when synthMode==1 or dx7patch loaded
    JPanel dx7Panel = buildDx7Panel(model, vm, bridge, trackIndex);
    tabs.insertTab("DX7", null, dx7Panel, "DX7 6-operator FM editing", 1);
    tabs.setEnabledAt(1, model.getSynthMode() == 1);
    tabs.addTab("ALGORITHM", buildAlgorithmPanel(model, bridge, trackIndex));
    tabs.addTab("LFO", buildLfoPanel(vm, bridge, trackIndex));
    tabs.addTab("ENVELOPE", buildEnvelopePanel(model, bridge, trackIndex));
    tabs.addTab("MODULATION", buildModulationPanel(model, bridge, trackIndex));
    tabs.addTab("AUTOMATION", buildAutomationPanel(model, bridge, trackIndex));

    // Enable/disable DX7 tab when synth mode changes (the mode combo is in the main panel)
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
      tabs.setEnabledAt(1, mode == 1);
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

    row = addSlider(panel, c, row, "Oct Spread:",
        "Randomize octave offset per note (0-100%). Higher = wilder octave jumps.",
        0, 100, (int)(bridge.getArpOctaveSpread(trackIndex) * 100),
        val -> bridge.setArpOctaveSpread(idx, val / 100.0), "%");

    row = addSlider(panel, c, row, "Gate Spread:",
        "Randomize note gate duration per step (0-100%). Higher = more timing variation.",
        0, 100, (int)(bridge.getArpGateSpread(trackIndex) * 100),
        val -> bridge.setArpGateSpread(idx, val / 100.0), "%");

    row = addSlider(panel, c, row, "Vel Spread:",
        "Randomize note velocity per step (0-100%). Higher = more dynamic contrast.",
        0, 100, (int)(bridge.getArpVelSpread(trackIndex) * 100),
        val -> bridge.setArpVelSpread(idx, val / 100.0), "%");

    row = addSlider(panel, c, row, "Ratchet:",
        "Sub-divide each step into N+1 mini-notes (0-4). 1 = double-trigger, 4 = machine-gun.",
        0, 4, bridge.getArpRatchet(trackIndex),
        val -> bridge.setArpRatchet(idx, val), "x");

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

  /** Build the synth algorithm selector tab with 32 DX7 algorithms + STK options. */
  private JPanel buildAlgorithmPanel(SynthTrackModel model, BridgeContract bridge, int trackIndex) {
    JPanel outer = new JPanel(new BorderLayout(8, 8));
    outer.setBackground(new Color(0x22, 0x22, 0x22));
    outer.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

    // ── Top: STK engine selector (for algo >= 10) ──
    JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
    topPanel.setBackground(new Color(0x22, 0x22, 0x22));
    topPanel.add(sectionLabel("ENGINE:"));

    String[] stkNames = {"DX7 FM (6-op)", "Mandolin", "Rhodey EP", "ModalBar", "Moog Bass"};
    int[] stkValues = {0, 10, 11, 12, 13};
    JComboBox<String> engineCombo = new JComboBox<>(stkNames);
    engineCombo.setBackground(new Color(0x33, 0x33, 0x33));
    engineCombo.setForeground(Color.WHITE);
    int curAlgo = model.getSynthAlgorithm();
    int curEngineIdx = 0;
    for (int i = 0; i < stkValues.length; i++) {
      if (stkValues[i] == curAlgo || (i == 0 && curAlgo < 10)) {
        curEngineIdx = i; break;
      }
    }
    engineCombo.setSelectedIndex(curEngineIdx);
    engineCombo.addActionListener(e -> {
      int ei = engineCombo.getSelectedIndex();
      int algoVal = stkValues[ei];
      model.setSynthAlgorithm(algoVal);
      bridge.setSynthAlgo(trackIndex, algoVal);
    });
    topPanel.add(engineCombo);

    // -- DX7 Engine Type (-1=AUTO, 0=MODERN, 1=VINTAGE) --
    topPanel.add(Box.createHorizontalStrut(16));
    topPanel.add(sectionLabel("ENGINE TYPE:"));
    String[] engineTypeNames = {"Auto (firmware default)", "Modern (32-bit float)", "Vintage (14-bit ENV)"};
    JComboBox<String> engineTypeCombo = new JComboBox<>(engineTypeNames);
    engineTypeCombo.setBackground(new Color(0x33, 0x33, 0x33));
    engineTypeCombo.setForeground(Color.WHITE);
    int curEngineType = model.getEngineType();
    // -1->0, 0->1, 1->2
    engineTypeCombo.setSelectedIndex(curEngineType + 1);
    engineTypeCombo.addActionListener(ev -> {
      int idx = engineTypeCombo.getSelectedIndex(); // 0, 1, or 2
      int typeVal = idx - 1; // -1, 0, or 1
      model.setEngineType(typeVal);
      bridge.setEngineType(trackIndex, typeVal);
    });
    topPanel.add(engineTypeCombo);
    outer.add(topPanel, BorderLayout.NORTH);

    // ── Center: 32-algorithm grid ──
    JPanel gridPanel = new JPanel(new GridLayout(16, 2, 6, 6));
    gridPanel.setBackground(new Color(0x22, 0x22, 0x22));
    JScrollPane scroll = new JScrollPane(gridPanel);
    scroll.setPreferredSize(new Dimension(700, 400));
    scroll.setBorder(BorderFactory.createTitledBorder(
        BorderFactory.createLineBorder(new Color(0x44, 0x44, 0x44)),
        "DX7 ALGORITHMS (0–31)"));
    scroll.getViewport().setBackground(new Color(0x22, 0x22, 0x22));

    for (int algo = 0; algo < 32; algo++) {
      final int a = algo;
      JPanel algoCard = new JPanel(new BorderLayout(4, 2));
      algoCard.setBackground(curAlgo == a ? new Color(0x3a, 0x5a, 0x3a) : new Color(0x2a, 0x2a, 0x2a));
      algoCard.setBorder(BorderFactory.createLineBorder(
          curAlgo == a ? Color.CYAN : new Color(0x44, 0x44, 0x44), 1));

      // Mini algorithm preview — show operator routing as ASCII
      JTextArea algoPreview = new JTextArea(formatAlgorithmMini(a));
      algoPreview.setEditable(false);
      algoPreview.setBackground(new Color(0x22, 0x22, 0x22));
      algoPreview.setForeground(Color.LIGHT_GRAY);
      algoPreview.setFont(algoPreview.getFont().deriveFont(10f));
      algoPreview.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
      algoCard.add(algoPreview, BorderLayout.CENTER);

      // Label + select button
      JPanel labelRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
      labelRow.setBackground(algoCard.getBackground());
      JLabel algoLabel = new JLabel("Algo " + a);
      algoLabel.setForeground(Color.CYAN);
      algoLabel.setFont(algoLabel.getFont().deriveFont(Font.BOLD, 11f));
      labelRow.add(algoLabel);
      JButton selectBtn = new JButton("Select");
      selectBtn.setFont(selectBtn.getFont().deriveFont(10f));
      selectBtn.addActionListener(ev -> {
        model.setSynthAlgorithm(a);
        bridge.setSynthAlgo(trackIndex, a);
        // Refresh highlights
        for (java.awt.Component comp : gridPanel.getComponents()) {
          if (comp instanceof JPanel card) {
            boolean isSelected = card.getBackground() == new Color(0x3a, 0x5a, 0x3a);
            // Simple: just close and reopen would be better, but this works
            card.setBackground(a == getAlgoForCard(card, gridPanel) ? new Color(0x3a, 0x5a, 0x3a) : new Color(0x2a, 0x2a, 0x2a));
          }
        }
      });
      labelRow.add(selectBtn);
      algoCard.add(labelRow, BorderLayout.SOUTH);

      gridPanel.add(algoCard);
    }
    outer.add(scroll, BorderLayout.CENTER);

    // ── Bottom: STK description ──
    JTextArea desc = new JTextArea(
        "Algo 0-9: Standard DX7 FM algorithms (6-op, algorithm routing determined by firmware tables).\n" +
        "Algo 10: Mandolin — Plucked string physical model with body resonance.\n" +
        "Algo 11: Rhodey EP — FM electric piano based on the Rhodes sound.\n" +
        "Algo 12: ModalBar — Mallet percussion with adjustable bar material.\n" +
        "Algo 13: Moog Bass — Monophonic bass synthesizer with ladder filter."
    );
    desc.setEditable(false);
    desc.setBackground(new Color(0x2a, 0x2a, 0x2a));
    desc.setForeground(Color.LIGHT_GRAY);
    desc.setFont(desc.getFont().deriveFont(11f));
    desc.setBorder(BorderFactory.createEmptyBorder(8, 5, 8, 5));
    desc.setLineWrap(true);
    desc.setWrapStyleWord(true);
    outer.add(desc, BorderLayout.SOUTH);

    return outer;
  }

  /** Produce a 3-line ASCII mini-representation of a DX7 algorithm (0-31). */
  private static String formatAlgorithmMini(int algo) {
    // Simplified visual: show 6 operators in 2 rows of 3, with routing indicators
    // Using Dx7EngineLookupTables.ALGORITHMS flags to show routing
    int[] algos = org.chuck.audio.util.Dx7EngineLookupTables.ALGORITHMS;
    int base = algo * 6;
    StringBuilder sb = new StringBuilder();
    // Row 1: ops 0,1,2 with their bus flags
    for (int i = 0; i < 3; i++) {
      int flags = algos[base + i];
      String opLabel = "OP" + (i + 1);
      char out = (flags & 0x01) != 0 ? '1' : (flags & 0x02) != 0 ? '2' : (flags & 0x04) != 0 ? 'A' : '?';
      char fb = (flags & 0x80) != 0 ? 'F' : ' ';
      sb.append(String.format("%s%c%c ", opLabel, fb, out));
    }
    sb.append('\n');
    // Row 2: ops 3,4,5
    for (int i = 3; i < 6; i++) {
      int flags = algos[base + i];
      String opLabel = "OP" + (i + 1);
      char out = (flags & 0x01) != 0 ? '1' : (flags & 0x02) != 0 ? '2' : (flags & 0x04) != 0 ? 'A' : '?';
      char fb = (flags & 0x80) != 0 ? 'F' : ' ';
      sb.append(String.format("%s%c%c ", opLabel, fb, out));
    }
    sb.append('\n');
    // Row 3: algorithm number + feedback
    sb.append(String.format("FB=%d/7", algo < 10 ? 7 - algo : 0));
    return sb.toString();
  }

  /** Get the algorithm index from a card component in the grid. */
  private static int getAlgoForCard(JPanel card, JPanel grid) {
    int idx = 0;
    for (java.awt.Component comp : grid.getComponents()) {
      if (comp == card) return idx;
      if (comp instanceof JPanel) idx++;
    }
    return -1;
  }

  /**
   * Build the DX7 tab: patch info, LFO, 6-operator table, .syx loader.
   * Only functional when synthMode==1.
   */
  private JPanel buildDx7Panel(
      SynthTrackModel model, ChuckVM vm, BridgeContract bridge, int trackIndex) {
    JPanel outer = new JPanel(new BorderLayout(4, 4));
    outer.setBackground(new Color(0x22, 0x22, 0x22));

    JPanel content = new JPanel(new GridBagLayout());
    content.setBackground(new Color(0x22, 0x22, 0x22));
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(4, 8, 4, 8);
    c.anchor = GridBagConstraints.WEST;
    int row = 0;

    // ── Load .syx button ──
    c.gridx = 0; c.gridy = row; c.gridwidth = 4;
    JButton loadSyxBtn = new JButton("Load .syx (DX7 Patch File)");
    loadSyxBtn.setToolTipText("Open a Roland SysEx bulk dump (.syx) containing DX7 voice patches");
    loadSyxBtn.addActionListener(e -> {
      JFileChooser fc = new JFileChooser();
      fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("DX7 SysEx (*.syx)", "syx"));
      if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
        try {
          java.util.List<org.chuck.audio.util.Dx7Patch> patches =
              org.chuck.deluge.xml.Dx7SyxParser.parseSyx(fc.getSelectedFile());
          if (!patches.isEmpty()) {
            applyDx7Patch(model, vm, bridge, trackIndex, patches.get(0));
            // Refresh dialog by disposing and recreating
            dispose();
            new SwingSynthConfigDialog(
                (Frame) getOwner(), model, vm, bridge, trackIndex).setVisible(true);
          }
        } catch (Exception ex) {
          JOptionPane.showMessageDialog(this,
              "Failed to load .syx: " + ex.getMessage(),
              "Parse Error", JOptionPane.ERROR_MESSAGE);
        }
      }
    });
    content.add(loadSyxBtn, c); row++;
    c.gridwidth = 1;

    // ── Patch Name ──
    c.gridx = 0; c.gridy = row;
    content.add(label("Patch Name:"), c);
    c.gridx = 1; c.gridwidth = 3;
    JTextField patchNameField = new JTextField(16);
    patchNameField.setBackground(new Color(0x33, 0x33, 0x33));
    patchNameField.setForeground(Color.WHITE);
    String curPatch = model.getDx7Patch();
    if (curPatch != null && !curPatch.isEmpty()) {
      try {
        patchNameField.setText(org.chuck.audio.util.Dx7Patch.fromHex(curPatch).name());
      } catch (Exception ignored) {}
    }
    patchNameField.setEditable(false);
    patchNameField.setToolTipText("Patch name from the loaded DX7 SysEx data (read-only)");
    content.add(patchNameField, c); row++;
    c.gridwidth = 1;

    // ── Patch global info: algorithm, feedback, transpose ──
    c.gridx = 0; c.gridy = row; c.gridwidth = 4;
    content.add(sectionLabel("PATCH GLOBALS"), c); row++;
    c.gridwidth = 1;

    // Algorithm (read-only, reflects what's in the patch)
    c.gridx = 0; c.gridy = row;
    content.add(label("Algorithm:"), c);
    c.gridx = 1;
    JLabel algoVal = new JLabel(curPatch != null ? String.valueOf(model.getSynthAlgorithm()) : "-");
    algoVal.setForeground(Color.CYAN);
    content.add(algoVal, c);

    // Feedback (0-7)
    c.gridx = 2;
    content.add(label("Feedback:"), c);
    c.gridx = 3;
    int fbInit = curPatch != null ? getPatchByte(curPatch, org.chuck.audio.util.Dx7Patch.OFF_FEEDBACK) & 0x07 : 0;
    JSlider fbSlider = new JSlider(0, 7, fbInit);
    fbSlider.setBackground(new Color(0x22, 0x22, 0x22));
    JLabel fbVal = new JLabel(String.valueOf(fbInit));
    fbVal.setForeground(Color.CYAN);
    fbSlider.addChangeListener(ev -> {
      setPatchByte(model, curPatch, org.chuck.audio.util.Dx7Patch.OFF_FEEDBACK, fbSlider.getValue());
      fbVal.setText(String.valueOf(fbSlider.getValue()));
    });
    JPanel fbPanel = new JPanel(new BorderLayout(4, 0));
    fbPanel.setBackground(new Color(0x22, 0x22, 0x22));
    fbPanel.add(fbSlider, BorderLayout.CENTER);
    fbPanel.add(fbVal, BorderLayout.EAST);
    content.add(fbPanel, c); row++;

    // Transpose
    c.gridx = 0; c.gridy = row;
    content.add(label("Transpose:"), c);
    c.gridx = 1; c.gridwidth = 3;
    int transpInit = curPatch != null ? getPatchByte(curPatch, org.chuck.audio.util.Dx7Patch.OFF_TRANSPOSE) : 64;
    JSlider transpSlider = new JSlider(0, 127, transpInit);
    transpSlider.setBackground(new Color(0x22, 0x22, 0x22));
    JLabel transpVal = new JLabel(String.valueOf(transpInit));
    transpVal.setForeground(Color.CYAN);
    transpSlider.addChangeListener(ev -> {
      setPatchByte(model, curPatch, org.chuck.audio.util.Dx7Patch.OFF_TRANSPOSE, transpSlider.getValue());
      transpVal.setText(String.valueOf(transpSlider.getValue()));
    });
    JPanel transpPanel = new JPanel(new BorderLayout(4, 0));
    transpPanel.setBackground(new Color(0x22, 0x22, 0x22));
    transpPanel.add(transpSlider, BorderLayout.CENTER);
    transpPanel.add(transpVal, BorderLayout.EAST);
    content.add(transpPanel, c); row++;

    // ── LFO section ──
    c.gridx = 0; c.gridy = row; c.gridwidth = 4;
    content.add(sectionLabel("DX7 LFO"), c); row++;
    c.gridwidth = 1;

    // LFO speed (0-99)
    addDx7SliderRow(content, c, "Speed:", 0, 99, curPatch, Dx7Patch.OFF_LFO_SPEED, model);
    // LFO delay (0-99)
    addDx7SliderRow(content, c, "Delay:", 0, 99, curPatch, Dx7Patch.OFF_LFO_DELAY, model);
    // Pitch mod depth (0-99)
    addDx7SliderRow(content, c, "PMod Depth:", 0, 99, curPatch, Dx7Patch.OFF_PMOD_DEPTH, model);
    // Amp mod depth (0-99)
    addDx7SliderRow(content, c, "AMod Depth:", 0, 99, curPatch, Dx7Patch.OFF_AMOD_DEPTH, model);

    // LFO waveform
    String[] lfoWaves = {"TRIANGLE", "SAW DOWN", "SAW UP", "SQUARE", "SINE", "S&H"};
    addDx7ComboRow(content, c, "Waveform:", lfoWaves, curPatch, Dx7Patch.OFF_LFO_WAVEFORM, model);

    // LFO sync (0/1)
    addDx7SliderRow(content, c, "Sync:", 0, 1, curPatch, Dx7Patch.OFF_LFO_SYNC, model);

    // ── Operator table ──
    c.gridx = 0; c.gridy = row; c.gridwidth = 4;
    content.add(sectionLabel("OPERATORS (OP1-OP6)"), c); row++;
    c.gridwidth = 1;

    // Column headers
    String[] opCols = {"OP", "ON", "Lv", "Md", "Crse", "Fine", "Det", "R1", "R2", "R3", "R4", "L1", "L2", "L3", "L4", "VS", "AM"};
    c.gridx = 0; c.gridy = row;
    for (int ci = 0; ci < opCols.length; ci++) {
      c.gridx = ci;
      content.add(headerLabel(opCols[ci]), c);
    }
    row++;

    java.util.List<JPanel> opPanels = new java.util.ArrayList<>();
    for (int opIdx = 0; opIdx < 6; opIdx++) {
      final int op = opIdx;
      final int opOff = op * 21;
      JPanel opRow = new JPanel(new GridBagLayout());
      opRow.setBackground(new Color(0x22, 0x22, 0x22));
      opRow.setFocusCycleRoot(true);
      opRow.setFocusTraversalPolicyProvider(true);

      // OP label
      c.gridx = 0; c.gridy = 0;
      opRow.add(label("OP" + (op + 1)), c);

      // ON/OFF toggle (opSwitch bit)
      c.gridx = 1;
      String curPatchInner = model.getDx7Patch();
      boolean opOn = curPatchInner != null && ((getPatchByte(curPatchInner, Dx7Patch.OFF_OP_SWITCH) >> op) & 1) != 0;
      JCheckBox opOnBox = new JCheckBox("", opOn);
      opOnBox.setBackground(new Color(0x22, 0x22, 0x22));
      opOnBox.addActionListener(ev -> {
        byte[] raw = getCurrentRaw(model, model.getDx7Patch());
        if (raw == null) return;
        if (opOnBox.isSelected()) {
          raw[Dx7Patch.OFF_OP_SWITCH] |= (byte) (1 << op);
        } else {
          raw[Dx7Patch.OFF_OP_SWITCH] &= (byte) ~(1 << op);
        }
        model.setDx7Patch(Dx7Patch.bytesToHex(raw));
      });
      opRow.add(opOnBox, c);

      // Output level (0-99)
      addDx7OpSliderTo(opRow, c, 2, op, 16, 0, 99, model);
      // Mode (0=ratio, 1=fixed)
      addDx7OpSliderTo(opRow, c, 3, op, 17, 0, 1, model);
      // Coarse (0-31)
      addDx7OpSliderTo(opRow, c, 4, op, 18, 0, 31, model);
      // Fine (0-99)
      addDx7OpSliderTo(opRow, c, 5, op, 19, 0, 99, model);
      // Detune (0-14)
      addDx7OpSliderTo(opRow, c, 6, op, 20, 0, 14, model);
      // EG R1-R4 (0-99)
      for (int eg = 0; eg < 4; eg++) {
        addDx7OpSliderTo(opRow, c, 7 + eg, op, eg, 0, 99, model);
      }
      // EG L1-L4 (0-99)
      for (int eg = 0; eg < 4; eg++) {
        addDx7OpSliderTo(opRow, c, 11 + eg, op, 4 + eg, 0, 99, model);
      }
      // Velocity sensitivity (0-7)
      addDx7OpSliderTo(opRow, c, 15, op, 15, 0, 7, model);
      // Amp mod sensitivity (0-3)
      addDx7OpSliderTo(opRow, c, 16, op, 14, 0, 3, model);

      opRow.setFocusTraversalPolicy(new java.awt.FocusTraversalPolicy() {
        @Override
        public Component getComponentAfter(Container focusCycleRoot, Component aComponent) {
          java.util.List<Component> order = getAllOrder();
          int idx = order.indexOf(aComponent);
          return order.get((idx + 1) % order.size());
        }
        @Override
        public Component getComponentBefore(Container focusCycleRoot, Component aComponent) {
          java.util.List<Component> order = getAllOrder();
          int idx = order.indexOf(aComponent);
          return order.get((idx - 1 + order.size()) % order.size());
        }
        @Override
        public Component getFirstComponent(Container focusCycleRoot) {
          java.util.List<Component> order = getAllOrder();
          return order.isEmpty() ? null : order.get(0);
        }
        @Override
        public Component getLastComponent(Container focusCycleRoot) {
          java.util.List<Component> order = getAllOrder();
          return order.isEmpty() ? null : order.get(order.size() - 1);
        }
        @Override
        public Component getDefaultComponent(Container focusCycleRoot) {
          return getFirstComponent(focusCycleRoot);
        }
        private java.util.List<Component> getAllOrder() {
          java.util.List<Component> all = new java.util.ArrayList<>();
          for (Component child : opRow.getComponents()) {
            if (child instanceof JCheckBox) all.add(child);
            else if (child instanceof JPanel) {
              for (Component sub : ((JPanel) child).getComponents()) {
                if (sub instanceof JSlider || sub instanceof JLabel) all.add(sub);
              }
            }
          }
          return all;
        }
      });

      c.gridx = 0; c.gridy = row;
      c.gridwidth = opCols.length;
      content.add(opRow, c);
      opPanels.add(opRow);
      row++;
    }

    // ── Keyboard focus cycling across operators (Tab/Shift+Tab) ──
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventPostProcessor(e -> {
      if (e.getID() != KeyEvent.KEY_PRESSED) return false;
      if (e.getKeyCode() != KeyEvent.VK_TAB) return false;
      Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
      if (focusOwner == null) return false;
      // Find which opPanel contains the focus
      int curOp = -1;
      for (int i = 0; i < opPanels.size(); i++) {
        if (SwingUtilities.isDescendingFrom(focusOwner, opPanels.get(i))) {
          curOp = i;
          break;
        }
      }
      if (curOp < 0) return false;
      int nextOp;
      if (e.isShiftDown()) {
        nextOp = (curOp - 1 + opPanels.size()) % opPanels.size();
      } else {
        nextOp = (curOp + 1) % opPanels.size();
      }
      e.consume();
      JPanel nextPanel = opPanels.get(nextOp);
      // Focus the first focusable component in the next panel
      Component first = nextPanel.getFocusTraversalPolicy().getDefaultComponent(nextPanel);
      if (first != null) first.requestFocus();
      return true;
    });
    JScrollPane scroll = new JScrollPane(content);
    scroll.setPreferredSize(new Dimension(900, 600));
    scroll.getViewport().setBackground(new Color(0x22, 0x22, 0x22));
    outer.add(scroll, BorderLayout.CENTER);

    return outer;
  }

  /** Helper: add a compact slider cell for a DX7 operator byte field. */
  private void addDx7OpSlider(JPanel panel, GridBagConstraints c, int col, int opOff, int fieldOff,
      int min, int max, SynthTrackModel model) {
    c.gridx = col;
    String curPatch = model.getDx7Patch();
    int val = min;
    if (curPatch != null && !curPatch.isEmpty()) {
      byte[] raw = Dx7Patch.hexToBytes(curPatch);
      int idx = opOff * 21 + fieldOff;
      if (idx >= 0 && idx < raw.length) {
        val = raw[idx] & 0xFF;
      }
    }
    JSlider slider = new JSlider(min, max, Math.max(min, Math.min(max, val)));
    slider.setBackground(new Color(0x22, 0x22, 0x22));
    slider.setPreferredSize(new Dimension(50, 22));
    slider.setPaintTicks(false);
    JLabel valLabel = new JLabel(String.valueOf(val));
    valLabel.setForeground(Color.CYAN);
    valLabel.setPreferredSize(new Dimension(28, 20));
    valLabel.setFont(valLabel.getFont().deriveFont(9f));
    slider.addChangeListener(ev -> {
      byte[] raw = getCurrentRaw(model, model.getDx7Patch());
      if (raw == null) return;
      int idx = opOff * 21 + fieldOff;
      if (idx >= 0 && idx < raw.length) {
        raw[idx] = (byte) slider.getValue();
        model.setDx7Patch(Dx7Patch.bytesToHex(raw));
      }
      valLabel.setText(String.valueOf(slider.getValue()));
    });
    JPanel cell = new JPanel(new BorderLayout(0, 0));
    cell.setBackground(new Color(0x22, 0x22, 0x22));
    cell.add(slider, BorderLayout.CENTER);
    cell.add(valLabel, BorderLayout.EAST);
    panel.add(cell, c);
  }

  /** Helper: add a slider row for a DX7 global byte (patch offset). */

  private void addDx7OpSliderTo(JPanel target, GridBagConstraints c, int col, int opOff, int fieldOff,
      int min, int max, SynthTrackModel model) {
    c.gridx = col;
    String curPatch = model.getDx7Patch();
    int val = min;
    if (curPatch != null && !curPatch.isEmpty()) {
      byte[] raw = Dx7Patch.hexToBytes(curPatch);
      int idx = opOff * 21 + fieldOff;
      if (idx >= 0 && idx < raw.length) {
        val = raw[idx] & 0xFF;
      }
    }
    JSlider slider = new JSlider(min, max, Math.max(min, Math.min(max, val)));
    slider.setBackground(new Color(0x22, 0x22, 0x22));
    slider.setPreferredSize(new Dimension(50, 22));
    slider.setPaintTicks(false);
    JLabel valLabel = new JLabel(String.valueOf(val));
    valLabel.setForeground(Color.CYAN);
    valLabel.setPreferredSize(new Dimension(28, 20));
    valLabel.setFont(valLabel.getFont().deriveFont(9f));
    slider.addChangeListener(ev -> {
      byte[] raw = getCurrentRaw(model, model.getDx7Patch());
      if (raw == null) return;
      int idx = opOff * 21 + fieldOff;
      if (idx >= 0 && idx < raw.length) {
        raw[idx] = (byte) slider.getValue();
        model.setDx7Patch(Dx7Patch.bytesToHex(raw));
      }
      valLabel.setText(String.valueOf(slider.getValue()));
    });
    JPanel cell = new JPanel(new BorderLayout(0, 0));
    cell.setBackground(new Color(0x22, 0x22, 0x22));
    cell.add(slider, BorderLayout.CENTER);
    cell.add(valLabel, BorderLayout.EAST);
    target.add(cell, c);
  }

  /** Helper: add a slider row for a DX7 global byte (patch offset). */
  private void addDx7SliderRow(JPanel panel, GridBagConstraints c,
      String labelText, int min, int max, String curPatch, int offset, SynthTrackModel model) {
    int val = min;
    if (curPatch != null && !curPatch.isEmpty()) {
      byte[] raw = Dx7Patch.hexToBytes(curPatch);
      if (offset >= 0 && offset < raw.length) {
        val = raw[offset] & 0xFF;
      }
    }
    int clamped = Math.max(min, Math.min(max, val));
    c.gridx = 0; c.gridy++; c.gridwidth = 1;
    panel.add(label(labelText), c);
    c.gridx = 1; c.gridwidth = 3;
    JSlider slider = new JSlider(min, max, clamped);
    slider.setBackground(new Color(0x22, 0x22, 0x22));
    JLabel valLabel = new JLabel(String.valueOf(clamped));
    valLabel.setForeground(Color.CYAN);
    slider.addChangeListener(ev -> {
      setPatchByte(model, curPatch, offset, slider.getValue());
      valLabel.setText(String.valueOf(slider.getValue()));
    });
    JPanel rowPanel = new JPanel(new BorderLayout(4, 0));
    rowPanel.setBackground(new Color(0x22, 0x22, 0x22));
    rowPanel.add(slider, BorderLayout.CENTER);
    rowPanel.add(valLabel, BorderLayout.EAST);
    panel.add(rowPanel, c);
  }

  /** Helper: add a combo box row for a DX7 global byte (patch offset). */
  private void addDx7ComboRow(JPanel panel, GridBagConstraints c,
      String labelText, String[] options, String curPatch, int offset, SynthTrackModel model) {
    int val = 0;
    if (curPatch != null && !curPatch.isEmpty()) {
      byte[] raw = Dx7Patch.hexToBytes(curPatch);
      if (offset >= 0 && offset < raw.length) {
        val = raw[offset] & 0xFF;
      }
    }
    int idx = Math.max(0, Math.min(options.length - 1, val));
    c.gridx = 0; c.gridy++; c.gridwidth = 1;
    panel.add(label(labelText), c);
    c.gridx = 1; c.gridwidth = 3;
    JComboBox<String> combo = new JComboBox<>(options);
    combo.setSelectedIndex(idx);
    combo.setBackground(new Color(0x33, 0x33, 0x33));
    combo.setForeground(Color.WHITE);
    combo.addActionListener(ev -> {
      setPatchByte(model, curPatch, offset, combo.getSelectedIndex());
    });
    panel.add(combo, c);
  }

  /** Get a byte from the DX7 patch hex string. */
  private static int getPatchByte(String hex, int offset) {
    if (hex == null || hex.length() < (offset + 1) * 2) return 0;
    byte[] raw = Dx7Patch.hexToBytes(hex);
    return raw[offset] & 0xFF;
  }

  /** Set a byte in the DX7 patch hex string and update the model. */
  private static void setPatchByte(SynthTrackModel model, String curHex, int offset, int value) {
    byte[] raw = getCurrentRaw(model, curHex);
    if (raw == null) return;
    if (offset >= 0 && offset < raw.length) {
      raw[offset] = (byte) (value & 0xFF);
      model.setDx7Patch(Dx7Patch.bytesToHex(raw));
    }
  }

  /** Get mutable raw bytes from the current DX7 patch (or from model). */
  private static byte[] getCurrentRaw(SynthTrackModel model, String fallbackHex) {
    String hex = model.getDx7Patch();
    if (hex == null || hex.isEmpty()) hex = fallbackHex;
    if (hex == null || hex.isEmpty()) return null;
    return Dx7Patch.hexToBytes(hex);
  }

  /** Apply a Dx7Patch to the model and push to the bridge. */
  private static void applyDx7Patch(SynthTrackModel model, ChuckVM vm,
      BridgeContract bridge, int trackIndex, org.chuck.audio.util.Dx7Patch patch) {
    String hex = org.chuck.deluge.xml.Dx7SyxParser.patchToHex(patch);
    model.setDx7Patch(hex);
    model.setSynthMode(1);
    model.setSynthAlgorithm(patch.algorithm());
    bridge.setSynthMode(trackIndex, 1);
    bridge.setSynthAlgo(trackIndex, patch.algorithm());
    // Push patch string to engine
    String globalName = "g_dx7_patch_" + trackIndex;
    vm.setGlobalString(globalName, hex);

    // Push opSwitch
    vm.setGlobalInt("g_dx7_opSwitch_" + trackIndex, patch.opSwitch());
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
