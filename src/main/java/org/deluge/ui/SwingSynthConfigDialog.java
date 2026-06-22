package org.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.deluge.BridgeContract;
import org.deluge.model.Consequence;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.ui.controls.SegmentedToggle;

/** Swing dialog for editing a Synth track: Arp, Filter, FM, and 4-slot LFO. */
public class SwingSynthConfigDialog extends JDialog {
  public static final Color BG_DARK = new Color(0x12, 0x12, 0x14);
  public static final Color BG_CARD = new Color(0x1a, 0x1a, 0x1e);
  public static final Color BG_CONTROL = new Color(0x2d, 0x2d, 0x32);
  public static final Color ACCENT_BLUE = new Color(0x00, 0xcc, 0xff);
  public static final Color ACCENT_MINT = new Color(0x00, 0xff, 0xcc);

  private static final String[] OSC_TYPES = {
    "SINE", "SAW", "SQUARE", "TRIANGLE", "NOISE", "SAMPLE", "WAVETABLE"
  };
  private static final String[] SYNTH_MODES = {"SUBTRACTIVE", "FM", "RINGMOD"};
  private static final String[] POLY_MODES = {"POLY", "MONO", "LEGATO", "AUTO", "CHOKE"};

  private final JTabbedPane tabs = new JTabbedPane();
  private final SynthTrackModel model;
  private final ProjectModel projectModel;
  private final int trackIndex;
  private final BridgeContract bridge;

  private MidiLearnPanel midiLearnPanel;
  private LfoPanel lfoPanel;
  private static JLabel globalHelpLabel;
  private static final String DEFAULT_HELP_TEXT =
      "<html>\uD83D\uDCA1 <b>QUICK HELP:</b> Hover over any control knob or slider to see its details and hardware mappings here!</html>";

  public JTabbedPane getTabbedPane() {
    return tabs;
  }

  public SwingSynthConfigDialog(
      Frame owner,
      SynthTrackModel model,
      final BridgeContract bridge,
      int trackIndex,
      ProjectModel projectModel) {
    super(
        owner,
        "Synth Track Editor: " + model.getName() + " (Track " + (trackIndex + 1) + ")",
        false);
    this.model = model;
    this.projectModel = projectModel;
    this.trackIndex = trackIndex;
    this.bridge = bridge;

    setSize(1300, 750);
    setLocationRelativeTo(owner);
    setLayout(new BorderLayout());
    getContentPane().setBackground(BG_DARK);

    // ── Global Theme Accent Listener ──
    ThemeManager.addThemeListener(this::repaint);

    // ── Top Header Toolbar (Branding + Theme Picker) ──
    JPanel headerPanel = new JPanel(new BorderLayout(16, 0));
    headerPanel.setBackground(new Color(0x15, 0x15, 0x18));
    headerPanel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x2d, 0x2d, 0x32)),
            BorderFactory.createEmptyBorder(8, 16, 8, 16)));

    JLabel brandLabel = new JLabel("DELUGE WORKSTATION");
    brandLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
    brandLabel.setForeground(Color.LIGHT_GRAY);

    JPanel brandContainer = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    brandContainer.setBackground(new Color(0x15, 0x15, 0x18));
    brandContainer.add(brandLabel);
    headerPanel.add(brandContainer, BorderLayout.WEST);

    JPanel pickerContainer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    pickerContainer.setBackground(new Color(0x15, 0x15, 0x18));

    JLabel themeLabel = new JLabel("ACCENT THEME:");
    themeLabel.setFont(new Font("SansSerif", Font.BOLD, 10));
    themeLabel.setForeground(Color.GRAY);
    pickerContainer.add(themeLabel);

    JComboBox<ThemeManager.Theme> themeCombo = new JComboBox<>(ThemeManager.Theme.values());
    themeCombo.setBackground(BG_CONTROL);
    themeCombo.setForeground(Color.WHITE);
    themeCombo.setFont(new Font("SansSerif", Font.PLAIN, 11));
    themeCombo.setPreferredSize(new Dimension(140, 22));
    themeCombo.setSelectedItem(ThemeManager.getActiveTheme());
    themeCombo.addActionListener(
        ev -> {
          ThemeManager.Theme sel = (ThemeManager.Theme) themeCombo.getSelectedItem();
          ThemeManager.setActiveTheme(sel);
        });
    pickerContainer.add(themeCombo);
    headerPanel.add(pickerContainer, BorderLayout.EAST);

    add(headerPanel, BorderLayout.NORTH);

    tabs.setBackground(BG_CARD);
    tabs.setForeground(Color.WHITE);

    populateTabs();

    // ── Collaborative Collapsible Preset Browser Sidebar ──
    SynthPresetBrowserPanel presetBrowser =
        new SynthPresetBrowserPanel(
            model,
            () -> {
              // Refresh all UI tab sliders and curves
              refreshAllControls();
              // Instantly update the engine's active voice for real-time auditioning!
              liveApplyAction();
            });

    JSplitPane browserSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, presetBrowser, tabs);
    browserSplit.setDividerLocation(220);
    browserSplit.setBackground(new Color(0x15, 0x15, 0x18));
    browserSplit.setBorder(null);
    browserSplit.setOneTouchExpandable(true); // Double arrow toggle button!

    add(browserSplit, BorderLayout.CENTER);

    // ── Composite South Panel Stack (Help Bar + Close button) ──
    JPanel southStack = new JPanel();
    southStack.setLayout(new BoxLayout(southStack, BoxLayout.Y_AXIS));

    JPanel helpBarPanel = new JPanel(new BorderLayout());
    helpBarPanel.setBackground(new Color(0x1a, 0x1a, 0x1e));
    helpBarPanel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x2d, 0x2d, 0x32)),
            BorderFactory.createEmptyBorder(6, 16, 6, 16)));
    helpBarPanel.setPreferredSize(new Dimension(1200, 48));
    helpBarPanel.setMinimumSize(new Dimension(100, 48));
    helpBarPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));

    globalHelpLabel = new JLabel(DEFAULT_HELP_TEXT);
    globalHelpLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
    globalHelpLabel.setForeground(Color.LIGHT_GRAY);
    globalHelpLabel.setVerticalAlignment(SwingConstants.TOP);
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

    // ── Live-apply ──
    // While the dialog is open, periodically re-map the (continuously edited) model onto the
    // running engine sound, so every knob/combo edit is audible immediately instead of waiting
    // for the next project rebuild. Polling the whole model (cheap: pure math, file loads are
    // path-guarded) covers every control on every tab without per-listener wiring.
    liveApplyTimer = new Timer(200, e -> liveApplyToEngine(bridge, model));
    liveApplyTimer.start();
  }

  private final Timer liveApplyTimer;

  private void liveApplyToEngine(BridgeContract bridge, SynthTrackModel model) {
    try {
      Object engineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
      if (engineObj instanceof org.deluge.engine.FirmwareAudioEngine engine
          && trackIndex < engine.sounds.size()
          && engine.sounds.get(trackIndex) instanceof org.deluge.engine.FirmwareSound fs) {
        org.deluge.engine.FirmwareFactory.applyModelToLiveSound(model, fs);
      }
    } catch (Exception ex) {
      // Never let a live-apply hiccup break the dialog (e.g. engine not running in tests).
    }
  }

  @Override
  public void dispose() {
    if (lfoPanel != null) {
      lfoPanel.stopAnimation();
    }
    if (liveApplyTimer != null) {
      liveApplyTimer.stop();
    }
    if (midiLearnPanel != null) {
      midiLearnPanel.stopTimer();
    }
    super.dispose();
  }

  private JPanel buildMainPanel(
      SynthTrackModel model, final BridgeContract bridge, int trackIndex) {
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
    String osc1Tooltip = "Waveform shape of the first carrier oscillator";
    String osc1Help =
        "<b>OSC 1 TYPE:</b> Sets the waveform shape of the first oscillator (SINE, SAW, SQUARE, TRIANGLE, NOISE, SAMPLE, or WAVETABLE). — <i>Physical Deluge:</i> Turn OSC1 TYPE gold shortcut dial knob.";
    osc1Combo.setToolTipText(osc1Tooltip);
    attachHoverHelp(osc1Combo, osc1Help);
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
        });
    leftPanel.add(osc1Combo, lc);
    leftRow++;

    // Osc1 source chip — visible only for SAMPLE/WAVETABLE; opens the scoped LibraryPicker.
    lc.gridx = 1;
    lc.gridy = leftRow;
    lc.gridwidth = 2;
    leftPanel.add(oscSourceChip(model, 0, osc1Combo), lc);
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
    String osc2Tooltip = "Waveform shape of the second oscillator (modulator in FM mode)";
    String osc2Help =
        "<b>OSC 2 TYPE:</b> Sets the waveform shape of the second oscillator. In FM mode, this acts as the modulator. Select NONE to disable. — <i>Physical Deluge:</i> Turn OSC2 TYPE gold shortcut dial knob.";
    osc2Combo.setToolTipText(osc2Tooltip);
    attachHoverHelp(osc2Combo, osc2Help);
    osc2Combo.addActionListener(e -> model.setOsc2Type((String) osc2Combo.getSelectedItem()));
    leftPanel.add(osc2Combo, lc);
    leftRow++;

    // Osc2 source chip — visible only for SAMPLE/WAVETABLE; opens the scoped LibraryPicker.
    lc.gridx = 1;
    lc.gridy = leftRow;
    lc.gridwidth = 2;
    leftPanel.add(oscSourceChip(model, 1, osc2Combo), lc);
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
    String retrigTooltip = "Oscillator phase retriggering behavior on note-on";
    String retrigHelp =
        "<b>OSCILLATOR RETRIGGER:</b> Sets the phase behavior when a note is pressed. FREE: oscillators run continuously. RESET/90°/180°/270°: oscillator phase resets to this angle on every note-on. — <i>Physical Deluge:</i> Set retrigger option inside synth menu.";
    retrigCombo.setToolTipText(retrigTooltip);
    attachHoverHelp(retrigCombo, retrigHelp);
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
    SegmentedToggle modeToggle =
        new SegmentedToggle(
            SYNTH_MODES,
            Math.max(0, Math.min(2, model.getSynthMode())),
            new Color(0x00, 0xbb, 0xff));
    modeToggle.setToolTipText(
        "Synth engine: SUBTRACTIVE (osc→filter), FM (mod→carrier), or RINGMOD (carrier×mod)");
    String modeHelp =
        "<b>SYNTH MODE:</b> Sets the sound synthesis engine. SUBTRACTIVE: oscillator runs through filter. FM: frequency modulation (modulator modulates carrier). RINGMOD: ring modulator (carrier multiplied by modulator). — <i>Physical Deluge:</i> Press synth mode button combinations.";
    attachHoverHelp(modeToggle, modeHelp);
    modeToggle.setPreferredSize(new Dimension(200, 26));
    modeToggle.onChange(
        mode -> {
          model.setSynthMode(mode);
          tabs.setEnabledAt(1, mode == 1);
        });
    leftPanel.add(modeToggle, lc);
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
    String polyTooltip = "Voice playback mode: POLY, MONO, LEGATO, AUTO, or CHOKE";
    String polyHelp =
        "<b>POLYPHONY MODE:</b> Sets how voices are triggered. POLY: multiple simultaneous notes. MONO: single voice, retriggered. LEGATO: single voice, slides pitches smoothly between overlapping notes without retrig. CHOKE: cuts off previous note. — <i>Physical Deluge:</i> Hold shift + press keyboard shortcut keys.";
    polyCombo.setToolTipText(polyTooltip);
    attachHoverHelp(polyCombo, polyHelp);
    polyCombo.addActionListener(
        e -> {
          SynthTrackModel.PolyphonyMode pm =
              SynthTrackModel.PolyphonyMode.valueOf((String) polyCombo.getSelectedItem());
          model.setPolyphony(pm);
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
    String vcntTooltip = "Maximum voice limit for this track (1-16)";
    String vcntHelp =
        "<b>VCNT (VOICE COUNT):</b> Caps the maximum number of concurrent virtual voices this track can trigger. Lower values optimize CPU and manage voice stealing. — <i>Physical Deluge:</i> Set voice limit in synth settings.";
    vcntSpinner.setToolTipText(vcntTooltip);
    attachHoverHelp(vcntSpinner, vcntHelp);
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
    String unisonNumTooltip = "Number of layered unison voices (1-8)";
    String unisonNumHelp =
        "<b>UNISON VOICES:</b> Multiplies and layers multiple copies of the oscillators (1 to 8) per note for a thick, wide, or chorused sound. — <i>Physical Deluge:</i> Set unison voice count inside sound editor.";
    unisonNumCombo.setToolTipText(unisonNumTooltip);
    attachHoverHelp(unisonNumCombo, unisonNumHelp);
    unisonNumCombo.addActionListener(
        e -> {
          int v = (Integer) unisonNumCombo.getSelectedItem();
          model.setUnisonNum(v);
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

    // Create the filter graph component first so it can be referenced in control callbacks
    FilterGraphComponent filterGraph = new FilterGraphComponent(model, bridge, trackIndex);

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
            val -> {
              bridge.setFilterFreq(trackIndex, val / 100.0);
              model.setLpfFreq((val / 100.0f) * 20000.0f);
              filterGraph.repaint();
            },
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
            val -> {
              bridge.setFilterRes(trackIndex, val / 100.0);
              model.setLpfRes(val / 100.0f * 100.0f);
              filterGraph.repaint();
            },
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
              filterGraph.repaint();
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
    // Self-drawn segmented toggle for the filter slope/type (modern replacement for the combo).
    int fmInit = Math.min(2, model.getFilterMode().ordinal());
    SegmentedToggle filterModeToggle =
        new SegmentedToggle(
            new String[] {"12dB", "24dB", "SVF"}, fmInit, new Color(0xff, 0xb3, 0x00));
    filterModeToggle.setToolTipText(
        "Filter type / slope: 12dB or 24dB ladder low-pass, or state-variable filter (SVF)");
    String filterModeHelp =
        "<b>FILTER MODE:</b> Sets the low-pass filter type and slope. 12dB/oct: 2-pole ladder filter. 24dB/oct: 4-pole ladder filter (sharper cutoff). SVF: State-Variable Filter (flexible, clean, supports notch). — <i>Physical Deluge:</i> Turn LP TYPE gold shortcut dial knob.";
    attachHoverHelp(filterModeToggle, filterModeHelp);
    filterModeToggle.setPreferredSize(new Dimension(180, 26));
    rightPanel.add(filterModeToggle, rc);
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
    notchBox.setEnabled(model.getFilterMode() == org.deluge.model.FilterMode.SVF);
    notchBox.setBackground(BG_CARD);
    notchBox.setForeground(Color.WHITE);
    String notchTooltip = "Enable band-rejection notch mode (SVF filter type only)";
    String notchHelp =
        "<b>SVF NOTCH:</b> Converts the State-Variable Filter (SVF) into a band-rejection notch filter, creating a deep frequency dip at the cutoff point. — <i>Physical Deluge:</i> Press LP TYPE gold dial knob.";
    notchBox.setToolTipText(notchTooltip);
    attachHoverHelp(notchBox, notchHelp);
    notchBox.addActionListener(
        e -> {
          model.setFilterNotch(notchBox.isSelected());
          bridge.setFilterNotch(trackIndex, notchBox.isSelected() ? 1 : 0);
          filterGraph.repaint();
        });
    filterModeToggle.onChange(
        idx -> {
          org.deluge.model.FilterMode fm =
              idx == 0
                  ? org.deluge.model.FilterMode.LADDER_12
                  : idx == 1
                      ? org.deluge.model.FilterMode.LADDER_24
                      : org.deluge.model.FilterMode.SVF;
          boolean isSvf = fm == org.deluge.model.FilterMode.SVF;
          notchBox.setEnabled(isSvf);
          if (!isSvf) {
            notchBox.setSelected(false);
            model.setFilterNotch(false);
            bridge.setFilterNotch(trackIndex, 0);
          }
          model.setFilterMode(fm);
          bridge.setFilterMode(trackIndex, fm.ordinal());
          filterGraph.repaint();
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
    String routeTooltip = "Routing configuration of Low-Pass (LPF) and High-Pass (HPF) filters";
    String routeHelp =
        "<b>FILTER ROUTING:</b> Sets how the LPF and HPF are chained. SERIES LPF→HPF: LPF then HPF. HPF→LPF: HPF then LPF. PARALLEL: both filters are fed independently in parallel. — <i>Physical Deluge:</i> Set route option in synth menu.";
    routeCombo.setToolTipText(routeTooltip);
    attachHoverHelp(routeCombo, routeHelp);
    routeCombo.addActionListener(
        e -> {
          int route = routeCombo.getSelectedIndex();
          model.setFilterRoute(route);
          bridge.setFilterRoute(trackIndex, route);
          filterGraph.repaint();
        });
    rightPanel.add(routeCombo, rc);
    rightRow++;

    // Beautiful titled wrapper for the LPF Filter visualizer
    JPanel filterGraphWrapper = new JPanel(new BorderLayout());
    filterGraphWrapper.setBackground(BG_CARD);
    filterGraphWrapper.setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32), 1, true),
            "Filter Curve",
            javax.swing.border.TitledBorder.LEFT,
            javax.swing.border.TitledBorder.TOP,
            new Font("SansSerif", Font.BOLD, 11),
            Color.LIGHT_GRAY));
    filterGraphWrapper.add(filterGraph, BorderLayout.CENTER);

    rc.gridx = 0;
    rc.gridy = rightRow;
    rc.gridwidth = 3;
    rc.insets = new Insets(8, 10, 12, 10);
    rightPanel.add(filterGraphWrapper, rc);
    rightRow++;
    // Restore default gridbag insets
    rc.insets = new Insets(6, 10, 6, 10);

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
            (int) (model.getFmRatio() * 100),
            val -> model.setFmRatio((float) (val / 100.0)),
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
            (int) (model.getFmAmount() * 100),
            val -> model.setFmAmount((float) (val / 100.0)),
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
            (int) (model.getCarrier1Feedback() * 100),
            val -> model.setCarrier1Feedback(val / 100.0f),
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
            (int) (model.getModulator1Feedback() * 100),
            val -> model.setModulator1Feedback(val / 100.0f),
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
            (int) (model.getModulator2Amount() * 100),
            val -> model.setModulator2Amount(val / 100.0f),
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
            (int) (model.getModulator2Feedback() * 100),
            val -> model.setModulator2Feedback(val / 100.0f),
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
            (int) (model.getCarrier2Feedback() * 100),
            val -> model.setCarrier2Feedback(val / 100.0f),
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
    slider.setName(paramName);
    slider.setBackground(BG_CARD);
    slider.setToolTipText(tooltip);
    attachHoverHelp(slider, tooltip);
    // Clickable / typeable value container panel
    JPanel valContainer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
    valContainer.setBackground(BG_CARD);
    valContainer.setPreferredSize(new Dimension(80, 22));

    JTextField valField = new JTextField(String.valueOf(initial));
    valField.setFont(new Font("SansSerif", Font.PLAIN, 11));
    valField.setForeground(Color.CYAN);
    valField.setBackground(BG_CONTROL);
    valField.setCaretColor(Color.CYAN);
    valField.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0x3d, 0x3d, 0x42), 1, true),
            BorderFactory.createEmptyBorder(1, 4, 1, 4)));
    valField.setPreferredSize(new Dimension(42, 20));
    valField.setHorizontalAlignment(JTextField.RIGHT);
    valField.setToolTipText("Click to type a precise value manually");

    JLabel unitLbl = new JLabel(unit);
    unitLbl.setFont(new Font("SansSerif", Font.PLAIN, 11));
    unitLbl.setForeground(Color.GRAY);
    unitLbl.setPreferredSize(new Dimension(28, 20));

    valContainer.add(valField);
    valContainer.add(unitLbl);

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

          if (SwingGridPanel.lockArmedTrack == trackIndex
              && SwingGridPanel.lockArmedStep != -1
              && paramName != null) {
            org.deluge.model.TrackModel track = projectModel.getTracks().get(trackIndex);
            int activeClipIdx = track.getActiveClipIndex();
            if (activeClipIdx >= 0 && activeClipIdx < track.getClips().size()) {
              org.deluge.model.ClipModel clip = track.getClips().get(activeClipIdx);
              float normalized = (float) (newVal - min) / (max - min);
              clip.setAutomation(paramName, SwingGridPanel.lockArmedStep, normalized);
              if (SwingDelugeApp.mainInstance != null) {
                SwingDelugeApp.mainInstance.fireProjectChanged();
              }
            }
          } else {
            onChange.accept(newVal);
            // AFFECT ENTIRE has no effect on a synth track: a synth clip is a single instrument,
            // so there is no "entire" set of rows to broadcast to (it is a kit-only function on
            // hardware). The kit dialog handles the real AFFECT ENTIRE behaviour.
          }

          // Update text field on slider drag
          valField.setText(String.valueOf(newVal));

          if (SwingDelugeApp.mainInstance != null) {
            String code = (paramName != null) ? paramName : labelText.replace(":", "").trim();
            if (code.length() > 4) code = code.substring(0, 4);
            SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient(
                code.toUpperCase(), String.valueOf(newVal));
          }
        });

    // Bi-directional text field listener for manual precise typing
    java.lang.Runnable applyTypedValue =
        () -> {
          try {
            String text = valField.getText().trim();
            text = text.replaceAll("[^0-9\\.-]", "");
            if (text.isEmpty()) return;
            int typedVal = (int) Double.parseDouble(text);
            int clampedVal = Math.max(min, Math.min(max, typedVal));
            slider.setValue(clampedVal);
            valField.setText(String.valueOf(clampedVal));
          } catch (NumberFormatException ex) {
            valField.setText(String.valueOf(slider.getValue()));
          }
        };

    valField.addActionListener(e -> applyTypedValue.run());
    valField.addFocusListener(
        new java.awt.event.FocusAdapter() {
          @Override
          public void focusLost(java.awt.event.FocusEvent e) {
            applyTypedValue.run();
          }
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
    panel.add(valContainer, c);
    return row + 1;
  }

  private void liveApplyAction() {
    liveApplyToEngine(bridge, model);
  }

  /**
   * Builds the full tab set in logical groups — the single source of truth for both the constructor
   * and {@link #refreshAllControls} (the two previously held duplicate tab lists that could drift).
   *
   * <p>Top-level (9): OSC/FILTER/FM · SOURCES · HPF · ENVELOPE · LFO · MODULATION · ARP · FX ·
   * SETUP. The most-used / premium-visualizer editors (ENVELOPE, LFO, MODULATION) stay top-level;
   * the secondary source-detail (OSC/ALGORITHM/DX7), global FX (MOD FX/EQ/COMPRESSOR), and
   * utilities (AUTOMATION/MIDI LEARN) are grouped under one sub-tabbed tab each, cutting the old
   * 14-tab overload without burying the showcase editors or merging "main + detail" content.
   */
  private void populateTabs() {
    tabs.addTab("OSC / FILTER / FM", buildMainPanel(model, bridge, trackIndex));

    // ── Sound sources / FM detail (grouped) ──
    Runnable reloadDialog =
        () -> {
          dispose();
          new SwingSynthConfigDialog((Frame) getOwner(), model, bridge, trackIndex, projectModel)
              .setVisible(true);
        };
    JTabbedPane sourceTabs = styledSubTabs();
    sourceTabs.addTab("OSC", new OscPanel(model, bridge, trackIndex, projectModel));
    sourceTabs.addTab("ALGORITHM", new AlgorithmPanel(model, bridge, trackIndex));
    sourceTabs.addTab("DX7", new Dx7Panel(model, bridge, trackIndex, this, reloadDialog));
    tabs.addTab("SOURCES", sourceTabs);

    // Filter: LPF lives in the main tab; HPF here.
    tabs.addTab("HPF", new HpfPanel(model, bridge, trackIndex, projectModel));

    // ── Modulation (kept top-level to showcase the interactive visualizers) ──
    tabs.addTab("ENVELOPE", new EnvelopePanel(model, bridge, trackIndex, projectModel));
    lfoPanel = new LfoPanel(model, trackIndex);
    tabs.addTab("LFO", lfoPanel);
    lfoPanel.startAnimation();
    tabs.addTab("MODULATION", new ModulationPanel(model, bridge, trackIndex));
    tabs.addTab("ARP", new ArpPanel(model, bridge, trackIndex, projectModel));

    // ── FX & dynamics (grouped) ──
    JTabbedPane fxTabs = styledSubTabs();
    fxTabs.addTab("MOD FX", new ModFxPanel(model, bridge, trackIndex, projectModel));
    fxTabs.addTab("EQ", new EqPanel(model, bridge, trackIndex, projectModel));
    fxTabs.addTab("COMPRESSOR", new CompressorPanel(model, bridge, trackIndex, projectModel));
    tabs.addTab("FX", fxTabs);

    // ── Setup utilities (grouped) ──
    JTabbedPane setupTabs = styledSubTabs();
    setupTabs.addTab("AUTOMATION", new AutomationPanel(model, bridge, trackIndex));
    if (midiLearnPanel == null) {
      midiLearnPanel = new MidiLearnPanel();
    }
    setupTabs.addTab("MIDI LEARN", midiLearnPanel);
    tabs.addTab("SETUP", setupTabs);
  }

  /** A dark-styled inner JTabbedPane for grouping related sub-editors. */
  private JTabbedPane styledSubTabs() {
    JTabbedPane t = new JTabbedPane();
    t.setBackground(BG_CARD);
    t.setForeground(Color.WHITE);
    return t;
  }

  public void refreshAllControls() {
    // Re-instantiate the panels and replace the tabs to update all sliders and visualizers
    // instantly
    int selectedIdx = tabs.getSelectedIndex();

    // Stop the LFO animation before removing to prevent thread leaks
    if (lfoPanel != null) {
      lfoPanel.stopAnimation();
    }

    tabs.removeAll();

    populateTabs();

    // Restore selection
    if (selectedIdx >= 0 && selectedIdx < tabs.getTabCount()) {
      tabs.setSelectedIndex(selectedIdx);
    }

    // Apply dark theme formatting
    DarkComboBoxRenderer.styleComponentTree(this);

    revalidate();
    repaint();
  }

  public static JSlider findSliderByName(Container container, String name) {
    for (Component c : container.getComponents()) {
      if (name.equals(c.getName()) && c instanceof JSlider) {
        return (JSlider) c;
      }
      if (c instanceof Container) {
        JSlider s = findSliderByName((Container) c, name);
        if (s != null) return s;
      }
    }
    return null;
  }

  static JLabel label(String text) {
    JLabel l = new JLabel(text);
    l.setForeground(Color.LIGHT_GRAY);
    return l;
  }

  private static String oscSourceLabel(String path) {
    if (path == null || path.isBlank()) return "▾ pick file…";
    String n = new java.io.File(path).getName();
    return "▾ " + (n.length() > 16 ? n.substring(0, 16) : n);
  }

  /**
   * A source chip for a SAMPLE/WAVETABLE oscillator: shows the current file, opens the scoped
   * LibraryPicker (SAMPLES or WAVETABLES) on click, and is only visible when the osc type needs a
   * file. {@code oscIdx} 0 = osc A, 1 = osc B.
   */
  private JButton oscSourceChip(
      org.deluge.model.SynthTrackModel model, int oscIdx, JComboBox<String> combo) {
    String cur = (oscIdx == 0) ? model.getOsc1SamplePath() : model.getOsc2SamplePath();
    JButton chip = new JButton(oscSourceLabel(cur));
    chip.setBackground(BG_CONTROL);
    chip.setForeground(ACCENT_BLUE);
    chip.setFont(new Font("SansSerif", Font.PLAIN, 11));
    chip.setToolTipText("Pick the sample / wavetable file for this oscillator");
    Runnable sync =
        () -> {
          String t = (String) combo.getSelectedItem();
          boolean on = "SAMPLE".equals(t) || "WAVETABLE".equals(t);
          chip.setVisible(on);
        };
    combo.addActionListener(e -> sync.run());
    sync.run();
    chip.addActionListener(
        e -> {
          String t = (String) combo.getSelectedItem();
          LibraryPicker.Scope scope =
              "WAVETABLE".equals(t) ? LibraryPicker.Scope.WAVETABLES : LibraryPicker.Scope.SAMPLES;
          String curPath = (oscIdx == 0) ? model.getOsc1SamplePath() : model.getOsc2SamplePath();
          LibraryPicker.show(
              chip,
              scope,
              curPath,
              java.util.List.of(
                  new LibraryPicker.Action(
                      "Use file",
                      new Color(0x00, 0x88, 0x66),
                      f -> {
                        String p = f.getAbsolutePath().replace('\\', '/');
                        if (oscIdx == 0) model.setOsc1SamplePath(p);
                        else model.setOsc2SamplePath(p);
                        chip.setText(oscSourceLabel(p));
                      })));
        });
    return chip;
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
