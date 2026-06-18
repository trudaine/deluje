package org.deluge.ui;

import java.awt.*;
import java.util.logging.Logger;
import javax.swing.*;
import org.chuck.audio.util.Dx7Patch;
import org.chuck.core.ChuckVM;
import org.deluge.BridgeContract;
import org.deluge.model.SynthTrackModel;

/**
 * Premium, high-fidelity DX7 voice and operator editing panel. Replaces the legacy microscopic
 * slider matrix with an elegant sidebar (Globals & LFO) and a modern, tabbed-operator control dock
 * complete with realtime envelope visualization graphs.
 */
public class Dx7Panel extends JPanel {
  private static final Logger LOG = Logger.getLogger(Dx7Panel.class.getName());

  private final SynthTrackModel model;
  private final ChuckVM vm;
  private final BridgeContract bridge;
  private final int trackIndex;
  private final Window owner;
  private final Runnable reloadCallback;

  private int currentSelectedOp = 0; // 0 to 5 for OP 1 to OP 6
  private boolean isRefreshingOpDetails = false;

  // Global Components
  private JTextField patchNameField;
  private JLabel algoValLabel;
  private JTextArea algoAsciiPreview;
  private JSlider feedbackSlider;
  private JSlider transposeSlider;
  private JLabel feedbackValLabel;
  private JLabel transposeValLabel;

  // LFO Components
  private JSlider lfoSpeedSlider;
  private JSlider lfoDelaySlider;
  private JSlider lfoPModSlider;
  private JSlider lfoAModSlider;
  private JComboBox<String> lfoWaveformCombo;
  private JSlider lfoSyncSlider;
  private JLabel lfoSpeedValLabel;
  private JLabel lfoDelayValLabel;
  private JLabel lfoPModValLabel;
  private JLabel lfoAModValLabel;
  private JLabel lfoSyncValLabel;

  // Active Operator Panel Components
  private JCheckBox activeCheckbox;
  private JComboBox<String> modeCombo;
  private JSlider coarseSlider;
  private JSlider fineSlider;
  private JSlider detuneSlider;
  private JLabel coarseValLabel;
  private JLabel fineValLabel;
  private JLabel detuneValLabel;

  private JSlider levelSlider;
  private JLabel levelValLabel;
  private JSlider velSensSlider;
  private JLabel velValLabel;
  private JSlider ampModSensSlider;
  private JLabel ampModValLabel;

  // EG Rate/Level Sliders & Readouts
  private final JSlider[] rateSliders = new JSlider[4];
  private final JSlider[] levelSliders = new JSlider[4];
  private final JLabel[] rateValLabels = new JLabel[4];
  private final JLabel[] levelValLabels = new JLabel[4];

  // Operator selection ribbon button array
  private final JButton[] opButtons = new JButton[6];

  private Dx7EnvelopeGraph envelopeGraph;

  public Dx7Panel(
      SynthTrackModel model,
      ChuckVM vm,
      BridgeContract bridge,
      int trackIndex,
      Window owner,
      Runnable reloadCallback) {
    super(new BorderLayout(8, 8));
    this.model = model;
    this.vm = vm;
    this.bridge = bridge;
    this.trackIndex = trackIndex;
    this.owner = owner;
    this.reloadCallback = reloadCallback;

    setBackground(new Color(0x12, 0x12, 0x14));
    setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

    // ── Active FM mode check & activation prompt card ──
    if (model.getSynthMode() != 1) {
      JPanel promptCard = new JPanel(new GridBagLayout());
      promptCard.setBackground(new Color(0x18, 0x18, 0x1c));
      promptCard.setBorder(BorderFactory.createEmptyBorder(40, 40, 40, 40));

      GridBagConstraints cp = new GridBagConstraints();
      cp.gridx = 0;
      cp.gridy = 0;
      cp.insets = new Insets(12, 12, 12, 12);
      cp.anchor = GridBagConstraints.CENTER;

      JLabel iconLabel = new JLabel("⚡ FM / DX7 SYNTHESIS IS INACTIVE");
      iconLabel.setFont(new Font("SansSerif", Font.BOLD, 22));
      iconLabel.setForeground(new Color(0xff, 0x99, 0x00)); // Gold/orange
      promptCard.add(iconLabel, cp);

      cp.gridy++;
      JLabel descLabel =
          new JLabel(
              "<html><center>The active track is currently configured for <b>SUBTRACTIVE</b> synthesis.<br>Click below to activate high-fidelity 6-Operator FM synthesis matching the DX7 voice architectures!</center></html>");
      descLabel.setFont(new Font("SansSerif", Font.PLAIN, 14));
      descLabel.setForeground(Color.LIGHT_GRAY);
      promptCard.add(descLabel, cp);

      cp.gridy++;
      JButton activateBtn = new JButton("⚡ Activate FM / DX7 Synthesis Mode");
      activateBtn.setFont(new Font("SansSerif", Font.BOLD, 15));
      activateBtn.setPreferredSize(new Dimension(320, 45));
      activateBtn.setBackground(new Color(0x0c, 0x38, 0x1f));
      activateBtn.setForeground(Color.GREEN);
      activateBtn.setFocusable(false);

      activateBtn.addActionListener(
          e -> {
            model.setSynthMode(1);

            // Load initial default DX7 voice patch!
            byte[] defaultRaw = new byte[156];
            // Set simple default patch values: algorithm 5, feedback 6, operator output switches
            // active!
            defaultRaw[Dx7Patch.OFF_ALGORITHM] = 5;
            defaultRaw[Dx7Patch.OFF_OP_SWITCH] = 0x3F; // all 6 operators active!
            // Set operator output levels to reasonable defaults (e.g. OP1 = 99, others = 75)
            for (int op = 0; op < 6; op++) {
              int opOff = op * 21;
              defaultRaw[opOff + 15] = (byte) 99; // Level = 99
              defaultRaw[opOff + 18] = (byte) 1; // Coarse ratio = 1.0
            }
            String defaultHex = Dx7Patch.bytesToHex(defaultRaw);
            model.setDx7Patch(defaultHex);
            bridge.setDx7Patch(trackIndex, defaultHex);
            if (vm != null) {
              vm.setGlobalString("g_dx7_patch_" + trackIndex, defaultHex);
              vm.setGlobalInt("g_dx7_opSwitch_" + trackIndex, 0x3FL);
            }

            if (reloadCallback != null) {
              reloadCallback.run();
            }
          });
      promptCard.add(activateBtn, cp);

      add(promptCard, BorderLayout.CENTER);
      return;
    }

    // ── Build Layout Components ──
    JPanel sidebar = buildSidebarPanel();
    JPanel operatorArea = buildOperatorAreaPanel();

    JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, operatorArea);
    splitPane.setDividerLocation(340);
    splitPane.setDividerSize(5);
    splitPane.setBackground(new Color(0x12, 0x12, 0x14));
    splitPane.setBorder(null);

    add(splitPane, BorderLayout.CENTER);

    // Initial state sync
    syncActiveOperatorDetails();
  }

  /** Build Left Sidebar containing bank loaders, globals, and LFO settings. */
  private JPanel buildSidebarPanel() {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBackground(new Color(0x1a, 0x1a, 0x1e));
    panel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x30), 1),
            BorderFactory.createEmptyBorder(10, 12, 10, 12)));

    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(6, 0, 6, 0);
    c.anchor = GridBagConstraints.WEST;
    c.gridx = 0;
    c.gridy = 0;
    c.weightx = 1.0;

    // ── Section Title: Voice Info ──
    JLabel mainTitle = new JLabel("DX7 FM VOICE PARAMETERS");
    mainTitle.setForeground(new Color(0x00, 0xcc, 0xff));
    mainTitle.setFont(new Font("SansSerif", Font.BOLD, 13));
    panel.add(mainTitle, c);
    c.gridy++;

    // Load .syx Button
    JButton loadSyxBtn = new JButton("Load .syx Patch Bank...");
    styleActionBtn(loadSyxBtn, new Color(0x1c, 0x2d, 0x3d), new Color(0x00, 0xcc, 0xff));
    loadSyxBtn.setToolTipText("Open a 32-patch Yamaha SysEx bank file (.syx)");
    loadSyxBtn.addActionListener(e -> triggerSyxBankLoader());
    panel.add(loadSyxBtn, c);
    c.gridy++;

    // Patch Name Display
    JPanel namePanel = new JPanel(new BorderLayout(8, 0));
    namePanel.setBackground(panel.getBackground());
    namePanel.add(SwingSynthConfigDialog.label("Voice:"), BorderLayout.WEST);
    patchNameField = new JTextField(12);
    patchNameField.setBackground(new Color(0x28, 0x28, 0x2c));
    patchNameField.setForeground(Color.WHITE);
    patchNameField.setEditable(false);
    patchNameField.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0x44, 0x44, 0x48), 1),
            BorderFactory.createEmptyBorder(2, 4, 2, 4)));
    namePanel.add(patchNameField, BorderLayout.CENTER);
    panel.add(namePanel, c);
    c.gridy++;

    // ── Section Title: Globals ──
    panel.add(SwingSynthConfigDialog.sectionLabel("PATCH GLOBALS"), c);
    c.gridy++;

    // Algorithm Selector with ASCII mini-preview
    JPanel algoTopRow = new JPanel(new BorderLayout(8, 0));
    algoTopRow.setBackground(panel.getBackground());
    algoTopRow.add(SwingSynthConfigDialog.label("Algorithm Routing:"), BorderLayout.WEST);
    algoValLabel = new JLabel("-", SwingConstants.RIGHT);
    algoValLabel.setForeground(Color.CYAN);
    algoValLabel.setFont(algoValLabel.getFont().deriveFont(Font.BOLD));
    algoTopRow.add(algoValLabel, BorderLayout.EAST);
    panel.add(algoTopRow, c);
    c.gridy++;

    JSlider algoSlider = createStyledSlider(0, 31, 0, 200);
    algoSlider.addChangeListener(
        ev -> {
          int val = algoSlider.getValue();
          model.setSynthAlgorithm(val);
          writeGlobalField(Dx7Patch.OFF_ALGORITHM, val);
        });
    panel.add(algoSlider, c);
    c.gridy++;

    // Algorithm ASCII Box
    algoAsciiPreview = new JTextArea(3, 16);
    algoAsciiPreview.setEditable(false);
    algoAsciiPreview.setFont(new Font("Monospaced", Font.BOLD, 10));
    algoAsciiPreview.setBackground(new Color(0x08, 0x08, 0x0c));
    algoAsciiPreview.setForeground(new Color(0x39, 0xff, 0x14)); // retro CRT green!
    algoAsciiPreview.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0x22, 0x22, 0x26), 1),
            BorderFactory.createEmptyBorder(4, 6, 4, 6)));
    panel.add(algoAsciiPreview, c);
    c.gridy++;

    // Feedback Depth (0-7)
    JPanel fbRow = new JPanel(new BorderLayout(8, 0));
    fbRow.setBackground(panel.getBackground());
    fbRow.add(SwingSynthConfigDialog.label("Feedback Depth:"), BorderLayout.WEST);
    feedbackValLabel = new JLabel("-", SwingConstants.RIGHT);
    feedbackValLabel.setForeground(Color.CYAN);
    fbRow.add(feedbackValLabel, BorderLayout.EAST);
    panel.add(fbRow, c);
    c.gridy++;

    feedbackSlider = createStyledSlider(0, 7, 0, 200);
    feedbackSlider.addChangeListener(
        ev -> {
          int val = feedbackSlider.getValue();
          writeGlobalField(Dx7Patch.OFF_FEEDBACK, val);
          feedbackValLabel.setText(String.valueOf(val));
        });
    panel.add(feedbackSlider, c);
    c.gridy++;

    // Transpose Offset
    JPanel transRow = new JPanel(new BorderLayout(8, 0));
    transRow.setBackground(panel.getBackground());
    transRow.add(SwingSynthConfigDialog.label("Transpose Offset:"), BorderLayout.WEST);
    transposeValLabel = new JLabel("-", SwingConstants.RIGHT);
    transposeValLabel.setForeground(Color.CYAN);
    transRow.add(transposeValLabel, BorderLayout.EAST);
    panel.add(transRow, c);
    c.gridy++;

    transposeSlider = createStyledSlider(0, 99, 64, 200);
    transposeSlider.addChangeListener(
        ev -> {
          int val = transposeSlider.getValue();
          writeGlobalField(Dx7Patch.OFF_TRANSPOSE, val);
          int offset = val - 64;
          transposeValLabel.setText((offset >= 0 ? "+" : "") + offset);
        });
    panel.add(transposeSlider, c);
    c.gridy++;

    // ── Section Title: LFO ──
    panel.add(SwingSynthConfigDialog.sectionLabel("DX7 MODULATION LFO"), c);
    c.gridy++;

    // LFO Waveform
    JPanel lfoWaveRow = new JPanel(new BorderLayout(8, 0));
    lfoWaveRow.setBackground(panel.getBackground());
    lfoWaveRow.add(SwingSynthConfigDialog.label("LFO Waveform:"), BorderLayout.WEST);
    String[] lfoWaves = {"TRIANGLE", "SAW DOWN", "SAW UP", "SQUARE", "SINE", "S&H (NOISE)"};
    lfoWaveformCombo = new JComboBox<>(lfoWaves);
    lfoWaveformCombo.setBackground(new Color(0x2d, 0x2d, 0x30));
    lfoWaveformCombo.setForeground(Color.WHITE);
    lfoWaveformCombo.addActionListener(
        e -> {
          int selIdx = lfoWaveformCombo.getSelectedIndex();
          writeGlobalField(Dx7Patch.OFF_LFO_WAVEFORM, selIdx);
        });
    lfoWaveRow.add(lfoWaveformCombo, BorderLayout.CENTER);
    panel.add(lfoWaveRow, c);
    c.gridy++;

    // LFO Speed
    JPanel speedRow = new JPanel(new BorderLayout(8, 0));
    speedRow.setBackground(panel.getBackground());
    speedRow.add(SwingSynthConfigDialog.label("LFO Speed:"), BorderLayout.WEST);
    lfoSpeedValLabel = new JLabel("-", SwingConstants.RIGHT);
    lfoSpeedValLabel.setForeground(Color.CYAN);
    speedRow.add(lfoSpeedValLabel, BorderLayout.EAST);
    panel.add(speedRow, c);
    c.gridy++;

    lfoSpeedSlider = createStyledSlider(0, 99, 0, 200);
    lfoSpeedSlider.addChangeListener(
        ev -> {
          int val = lfoSpeedSlider.getValue();
          writeGlobalField(Dx7Patch.OFF_LFO_SPEED, val);
          lfoSpeedValLabel.setText(String.valueOf(val));
        });
    panel.add(lfoSpeedSlider, c);
    c.gridy++;

    // LFO Delay
    JPanel delayRow = new JPanel(new BorderLayout(8, 0));
    delayRow.setBackground(panel.getBackground());
    delayRow.add(SwingSynthConfigDialog.label("LFO Delay:"), BorderLayout.WEST);
    lfoDelayValLabel = new JLabel("-", SwingConstants.RIGHT);
    lfoDelayValLabel.setForeground(Color.CYAN);
    delayRow.add(lfoDelayValLabel, BorderLayout.EAST);
    panel.add(delayRow, c);
    c.gridy++;

    lfoDelaySlider = createStyledSlider(0, 99, 0, 200);
    lfoDelaySlider.addChangeListener(
        ev -> {
          int val = lfoDelaySlider.getValue();
          writeGlobalField(Dx7Patch.OFF_LFO_DELAY, val);
          lfoDelayValLabel.setText(String.valueOf(val));
        });
    panel.add(lfoDelaySlider, c);
    c.gridy++;

    // Pitch Mod Depth
    JPanel pmodRow = new JPanel(new BorderLayout(8, 0));
    pmodRow.setBackground(panel.getBackground());
    pmodRow.add(SwingSynthConfigDialog.label("Pitch Mod Depth:"), BorderLayout.WEST);
    lfoPModValLabel = new JLabel("-", SwingConstants.RIGHT);
    lfoPModValLabel.setForeground(Color.CYAN);
    pmodRow.add(lfoPModValLabel, BorderLayout.EAST);
    panel.add(pmodRow, c);
    c.gridy++;

    lfoPModSlider = createStyledSlider(0, 99, 0, 200);
    lfoPModSlider.addChangeListener(
        ev -> {
          int val = lfoPModSlider.getValue();
          writeGlobalField(Dx7Patch.OFF_PMOD_DEPTH, val);
          lfoPModValLabel.setText(String.valueOf(val));
        });
    panel.add(lfoPModSlider, c);
    c.gridy++;

    // Amp Mod Depth
    JPanel amodRow = new JPanel(new BorderLayout(8, 0));
    amodRow.setBackground(panel.getBackground());
    amodRow.add(SwingSynthConfigDialog.label("Amp Mod Depth:"), BorderLayout.WEST);
    lfoAModValLabel = new JLabel("-", SwingConstants.RIGHT);
    lfoAModValLabel.setForeground(Color.CYAN);
    amodRow.add(lfoAModValLabel, BorderLayout.EAST);
    panel.add(amodRow, c);
    c.gridy++;

    lfoAModSlider = createStyledSlider(0, 99, 0, 200);
    lfoAModSlider.addChangeListener(
        ev -> {
          int val = lfoAModSlider.getValue();
          writeGlobalField(Dx7Patch.OFF_AMOD_DEPTH, val);
          lfoAModValLabel.setText(String.valueOf(val));
        });
    panel.add(lfoAModSlider, c);
    c.gridy++;

    // LFO Sync
    JPanel syncRow = new JPanel(new BorderLayout(8, 0));
    syncRow.setBackground(panel.getBackground());
    syncRow.add(SwingSynthConfigDialog.label("Key Sync (Retrigger):"), BorderLayout.WEST);
    lfoSyncValLabel = new JLabel("-", SwingConstants.RIGHT);
    lfoSyncValLabel.setForeground(Color.CYAN);
    syncRow.add(lfoSyncValLabel, BorderLayout.EAST);
    panel.add(syncRow, c);
    c.gridy++;

    lfoSyncSlider = createStyledSlider(0, 1, 0, 200);
    lfoSyncSlider.addChangeListener(
        ev -> {
          int val = lfoSyncSlider.getValue();
          writeGlobalField(Dx7Patch.OFF_LFO_SYNC, val);
          lfoSyncValLabel.setText(val == 1 ? "ON" : "OFF");
        });
    panel.add(lfoSyncSlider, c);

    return panel;
  }

  /**
   * Build Right details area containing Operator selection ribbons and individual settings panels.
   */
  private JPanel buildOperatorAreaPanel() {
    JPanel areaPanel = new JPanel(new BorderLayout(8, 8));
    areaPanel.setBackground(new Color(0x12, 0x12, 0x14));

    // ── North: Styled Operator Select Ribbon ──
    JPanel ribbon = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
    ribbon.setBackground(new Color(0x1a, 0x1a, 0x1e));
    ribbon.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x2d, 0x2d, 0x30)));

    JLabel selectLabel = SwingSynthConfigDialog.sectionLabel("OPERATOR DOCK: ");
    selectLabel.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
    ribbon.add(selectLabel);

    ButtonGroup opGroup = new ButtonGroup();
    for (int i = 0; i < 6; i++) {
      final int opIdx = i;
      JButton btn = new JButton("OP " + (i + 1));
      btn.setFont(new Font("SansSerif", Font.BOLD, 12));
      btn.setFocusPainted(false);
      btn.setPreferredSize(new Dimension(68, 28));
      btn.addActionListener(
          e -> {
            currentSelectedOp = opIdx;
            syncActiveOperatorDetails();
          });
      opButtons[i] = btn;
      ribbon.add(btn);
    }
    areaPanel.add(ribbon, BorderLayout.NORTH);

    // ── Center: Operator Details Sub-Panel ──
    JPanel detailsGrid = new JPanel(new GridBagLayout());
    detailsGrid.setBackground(new Color(0x1e, 0x1e, 0x24));
    detailsGrid.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x30), 1),
            BorderFactory.createEmptyBorder(12, 16, 12, 16)));

    GridBagConstraints dc = new GridBagConstraints();
    dc.fill = GridBagConstraints.HORIZONTAL;
    dc.insets = new Insets(8, 4, 8, 4);
    dc.anchor = GridBagConstraints.WEST;
    dc.weightx = 1.0;

    // Row 0: Active State Switch
    dc.gridx = 0;
    dc.gridy = 0;
    dc.gridwidth = 4;
    activeCheckbox = new JCheckBox("Operator is ENABLED");
    activeCheckbox.setFont(new Font("SansSerif", Font.BOLD, 13));
    activeCheckbox.setBackground(detailsGrid.getBackground());
    activeCheckbox.setForeground(new Color(0x00, 0xff, 0x66));
    activeCheckbox.setFocusPainted(false);
    activeCheckbox.addActionListener(
        ev -> {
          if (isRefreshingOpDetails) return;
          byte[] raw = getCurrentRaw(model, model.getDx7Patch());
          if (raw == null) return;
          if (activeCheckbox.isSelected()) {
            raw[Dx7Patch.OFF_OP_SWITCH] |= (byte) (1 << currentSelectedOp);
          } else {
            raw[Dx7Patch.OFF_OP_SWITCH] &= (byte) ~(1 << currentSelectedOp);
          }
          String newHex = Dx7Patch.bytesToHex(raw);
          model.setDx7Patch(newHex);
          bridge.setDx7Patch(trackIndex, newHex);
          if (vm != null) {
            vm.setGlobalString("g_dx7_patch_" + trackIndex, newHex);
            int opSwitchVal = raw[Dx7Patch.OFF_OP_SWITCH] & 0xFF;
            vm.setGlobalInt("g_dx7_opSwitch_" + trackIndex, opSwitchVal);
          }
          syncActiveOperatorDetails();
        });
    detailsGrid.add(activeCheckbox, dc);
    dc.gridwidth = 1;

    // Row 1: Frequency settings group
    dc.gridy++;
    dc.gridx = 0;
    detailsGrid.add(SwingSynthConfigDialog.sectionLabel("FREQUENCY GENERATION"), dc);

    // Mode combo (Ratio / Fixed)
    dc.gridy++;
    dc.gridx = 0;
    detailsGrid.add(SwingSynthConfigDialog.label("Oscillator Mode:"), dc);
    dc.gridx = 1;
    String[] modes = {"RATIO MODE", "FIXED FREQUENCY (Hz)"};
    modeCombo = new JComboBox<>(modes);
    modeCombo.setBackground(new Color(0x2d, 0x2d, 0x30));
    modeCombo.setForeground(Color.WHITE);
    modeCombo.addActionListener(e -> writeOpField(17, modeCombo.getSelectedIndex()));
    detailsGrid.add(modeCombo, dc);

    // Coarse Frequency
    dc.gridy++;
    dc.gridx = 0;
    JPanel coarseHeader = new JPanel(new BorderLayout(4, 0));
    coarseHeader.setBackground(detailsGrid.getBackground());
    coarseHeader.add(SwingSynthConfigDialog.label("Coarse Freq:"), BorderLayout.WEST);
    coarseValLabel = new JLabel("-", SwingConstants.RIGHT);
    coarseValLabel.setForeground(Color.CYAN);
    coarseHeader.add(coarseValLabel, BorderLayout.EAST);
    detailsGrid.add(coarseHeader, dc);

    dc.gridx = 1;
    coarseSlider = createStyledSlider(0, 31, 1, 180);
    coarseSlider.addChangeListener(
        e -> {
          int val = coarseSlider.getValue();
          writeOpField(18, val);
          coarseValLabel.setText(String.valueOf(val));
        });
    detailsGrid.add(coarseSlider, dc);

    // Fine Frequency
    dc.gridy++;
    dc.gridx = 0;
    JPanel fineHeader = new JPanel(new BorderLayout(4, 0));
    fineHeader.setBackground(detailsGrid.getBackground());
    fineHeader.add(SwingSynthConfigDialog.label("Fine Freq:"), BorderLayout.WEST);
    fineValLabel = new JLabel("-", SwingConstants.RIGHT);
    fineValLabel.setForeground(Color.CYAN);
    fineHeader.add(fineValLabel, BorderLayout.EAST);
    detailsGrid.add(fineHeader, dc);

    dc.gridx = 1;
    fineSlider = createStyledSlider(0, 99, 0, 180);
    fineSlider.addChangeListener(
        e -> {
          int val = fineSlider.getValue();
          writeOpField(19, val);
          fineValLabel.setText(String.valueOf(val));
        });
    detailsGrid.add(fineSlider, dc);

    // Detune Offset
    dc.gridy++;
    dc.gridx = 0;
    JPanel detuneHeader = new JPanel(new BorderLayout(4, 0));
    detuneHeader.setBackground(detailsGrid.getBackground());
    detuneHeader.add(SwingSynthConfigDialog.label("Detune Offset:"), BorderLayout.WEST);
    detuneValLabel = new JLabel("-", SwingConstants.RIGHT);
    detuneValLabel.setForeground(Color.CYAN);
    detuneHeader.add(detuneValLabel, BorderLayout.EAST);
    detailsGrid.add(detuneHeader, dc);

    dc.gridx = 1;
    detuneSlider = createStyledSlider(0, 14, 7, 180);
    detuneSlider.addChangeListener(
        e -> {
          int val = detuneSlider.getValue();
          writeOpField(20, val);
          int offset = val - 7;
          detuneValLabel.setText((offset >= 0 ? "+" : "") + offset);
        });
    detailsGrid.add(detuneSlider, dc);

    // Row 2: Levels & Modulation Sensitivity
    dc.gridy++;
    dc.gridx = 0;
    detailsGrid.add(SwingSynthConfigDialog.sectionLabel("AMPLITUDE & SENSITIVITY"), dc);

    // Output Level
    dc.gridy++;
    dc.gridx = 0;
    JPanel lvlHeader = new JPanel(new BorderLayout(4, 0));
    lvlHeader.setBackground(detailsGrid.getBackground());
    lvlHeader.add(SwingSynthConfigDialog.label("Output Level:"), BorderLayout.WEST);
    levelValLabel = new JLabel("-", SwingConstants.RIGHT);
    levelValLabel.setForeground(Color.CYAN);
    lvlHeader.add(levelValLabel, BorderLayout.EAST);
    detailsGrid.add(lvlHeader, dc);

    dc.gridx = 1;
    levelSlider = createStyledSlider(0, 99, 90, 180);
    levelSlider.addChangeListener(
        e -> {
          int val = levelSlider.getValue();
          writeOpField(16, val);
          levelValLabel.setText(String.valueOf(val));
        });
    detailsGrid.add(levelSlider, dc);

    // Key Velocity Sensitivity
    dc.gridy++;
    dc.gridx = 0;
    JPanel velHeader = new JPanel(new BorderLayout(4, 0));
    velHeader.setBackground(detailsGrid.getBackground());
    velHeader.add(SwingSynthConfigDialog.label("Velocity Sens:"), BorderLayout.WEST);
    velValLabel = new JLabel("-", SwingConstants.RIGHT);
    velValLabel.setForeground(Color.CYAN);
    velHeader.add(velValLabel, BorderLayout.EAST);
    detailsGrid.add(velHeader, dc);

    dc.gridx = 1;
    velSensSlider = createStyledSlider(0, 7, 0, 180);
    velSensSlider.addChangeListener(
        e -> {
          int val = velSensSlider.getValue();
          writeOpField(15, val);
          velValLabel.setText(String.valueOf(val));
        });
    detailsGrid.add(velSensSlider, dc);

    // Amp Mod Sensitivity
    dc.gridy++;
    dc.gridx = 0;
    JPanel ampHeader = new JPanel(new BorderLayout(4, 0));
    ampHeader.setBackground(detailsGrid.getBackground());
    ampHeader.add(SwingSynthConfigDialog.label("AMod Sensitivity:"), BorderLayout.WEST);
    ampModValLabel = new JLabel("-", SwingConstants.RIGHT);
    ampModValLabel.setForeground(Color.CYAN);
    ampHeader.add(ampModValLabel, BorderLayout.EAST);
    detailsGrid.add(ampHeader, dc);

    dc.gridx = 1;
    ampModSensSlider = createStyledSlider(0, 3, 0, 180);
    ampModSensSlider.addChangeListener(
        e -> {
          int val = ampModSensSlider.getValue();
          writeOpField(14, val);
          ampModValLabel.setText(String.valueOf(val));
        });
    detailsGrid.add(ampModSensSlider, dc);

    // Row 3: Envelope Generator Rates & Levels visual workspace!
    dc.gridy++;
    dc.gridx = 0;
    dc.gridwidth = 4;
    detailsGrid.add(SwingSynthConfigDialog.sectionLabel("OPERATOR ENVELOPE GENERATOR (EG)"), dc);
    dc.gridwidth = 1;

    // Main Envelope split row containing rates/levels controls on the left and realtime graph on
    // the right!
    dc.gridy++;
    dc.gridx = 0;
    dc.gridwidth = 2;

    JPanel envelopeSplitPanel = new JPanel(new BorderLayout(16, 0));
    envelopeSplitPanel.setBackground(detailsGrid.getBackground());

    // Left container: Grid layout for 4 rates and 4 levels
    JPanel egSlidersPanel = new JPanel(new GridLayout(4, 2, 8, 4));
    egSlidersPanel.setBackground(detailsGrid.getBackground());

    for (int i = 0; i < 4; i++) {
      final int stepIdx = i;

      // Rate Slider Row
      JPanel rateCell = new JPanel(new BorderLayout(4, 0));
      rateCell.setBackground(detailsGrid.getBackground());
      rateCell.add(SwingSynthConfigDialog.label("Rate " + (i + 1) + ":"), BorderLayout.WEST);
      rateValLabels[i] = new JLabel("-", SwingConstants.RIGHT);
      rateValLabels[i].setForeground(Color.CYAN);
      rateValLabels[i].setPreferredSize(new Dimension(24, 20));
      rateCell.add(rateValLabels[i], BorderLayout.EAST);

      JSlider rSlider = createStyledSlider(0, 99, 50, 100);
      rSlider.addChangeListener(
          e -> {
            int val = rSlider.getValue();
            writeOpField(stepIdx, val);
            rateValLabels[stepIdx].setText(String.valueOf(val));
          });
      rateSliders[i] = rSlider;
      rateCell.add(rSlider, BorderLayout.CENTER);
      egSlidersPanel.add(rateCell);

      // Level Slider Row
      JPanel levelCell = new JPanel(new BorderLayout(4, 0));
      levelCell.setBackground(detailsGrid.getBackground());
      levelCell.add(SwingSynthConfigDialog.label("Level " + (i + 1) + ":"), BorderLayout.WEST);
      levelValLabels[i] = new JLabel("-", SwingConstants.RIGHT);
      levelValLabels[i].setForeground(Color.CYAN);
      levelValLabels[i].setPreferredSize(new Dimension(24, 20));
      levelCell.add(levelValLabels[i], BorderLayout.EAST);

      JSlider lSlider = createStyledSlider(0, 99, 90, 100);
      lSlider.addChangeListener(
          e -> {
            int val = lSlider.getValue();
            writeOpField(4 + stepIdx, val);
            levelValLabels[stepIdx].setText(String.valueOf(val));
          });
      levelSliders[i] = lSlider;
      levelCell.add(lSlider, BorderLayout.CENTER);
      egSlidersPanel.add(levelCell);
    }
    envelopeSplitPanel.add(egSlidersPanel, BorderLayout.CENTER);

    // Right container: Visual Glowing AWT Graph!
    envelopeGraph = new Dx7EnvelopeGraph();
    envelopeSplitPanel.add(envelopeGraph, BorderLayout.EAST);

    detailsGrid.add(envelopeSplitPanel, dc);

    // Make the subpanel scrollable just in case
    JScrollPane scroll = new JScrollPane(detailsGrid);
    scroll.getViewport().setBackground(new Color(0x12, 0x12, 0x14));
    scroll.setBorder(null);
    areaPanel.add(scroll, BorderLayout.CENTER);

    return areaPanel;
  }

  /**
   * Trigger a JFileChooser to open SysEx bulk dumps, showing combo box selectors on multi-preset
   * files.
   */
  private void triggerSyxBankLoader() {
    JFileChooser fc = new JFileChooser();
    fc.setDialogTitle("Select a Yamaha DX7 SysEx Bank File (.syx)");
    fc.setFileFilter(
        new javax.swing.filechooser.FileNameExtensionFilter("DX7 SysEx (*.syx)", "syx"));
    if (fc.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
      try {
        java.util.List<org.chuck.audio.util.Dx7Patch> patches =
            org.deluge.xml.Dx7SyxParser.parseSyx(fc.getSelectedFile());
        if (!patches.isEmpty()) {
          if (patches.size() == 1) {
            applyDx7Patch(model, vm, bridge, trackIndex, patches.get(0));
            reloadCallback.run();
          } else {
            // High-Value Feature: Visual bank preset selector dialog!
            String[] patchNames = new String[patches.size()];
            for (int i = 0; i < patches.size(); i++) {
              patchNames[i] = String.format("%02d: %s", i + 1, patches.get(i).name().trim());
            }
            String selected =
                (String)
                    JOptionPane.showInputDialog(
                        owner,
                        "Select a DX7 patch preset from the SysEx bank:",
                        "SysEx Bank Presets (" + patches.size() + " Patches)",
                        JOptionPane.PLAIN_MESSAGE,
                        null,
                        patchNames,
                        patchNames[0]);
            if (selected != null) {
              int selectedIndex = Integer.parseInt(selected.substring(0, 2)) - 1;
              applyDx7Patch(model, vm, bridge, trackIndex, patches.get(selectedIndex));
              reloadCallback.run();
            }
          }
        }
      } catch (Exception ex) {
        JOptionPane.showMessageDialog(
            owner,
            "Failed to parse SysEx: " + ex.getMessage(),
            "SysEx Parse Error",
            JOptionPane.ERROR_MESSAGE);
      }
    }
  }

  /** Synchronize the current selected operator's parameters and dynamic AWT graph. */
  private void syncActiveOperatorDetails() {
    isRefreshingOpDetails = true;
    try {
      String curPatch = model.getDx7Patch();
      if (curPatch == null || curPatch.isEmpty()) return;
      byte[] raw = Dx7Patch.hexToBytes(curPatch);
      int opOff = currentSelectedOp * 21;

      // ── Operator Enabled switch ──
      boolean opOn = ((raw[Dx7Patch.OFF_OP_SWITCH] >> currentSelectedOp) & 1) != 0;
      activeCheckbox.setSelected(opOn);
      activeCheckbox.setText(
          opOn
              ? "Operator " + (currentSelectedOp + 1) + " is ENABLED"
              : "Operator " + (currentSelectedOp + 1) + " is DISABLED");
      activeCheckbox.setForeground(opOn ? new Color(0x00, 0xff, 0x66) : Color.LIGHT_GRAY);

      // ── Read Values ──
      int level = raw[opOff + 16] & 0xFF;
      int mode = raw[opOff + 17] & 0xFF;
      int coarse = raw[opOff + 18] & 0xFF;
      int fine = raw[opOff + 19] & 0xFF;
      int detune = raw[opOff + 20] & 0xFF;
      int velSens = raw[opOff + 15] & 0xFF;
      int ampMod = raw[opOff + 14] & 0xFF;

      int r1 = raw[opOff + 0] & 0xFF;
      int r2 = raw[opOff + 1] & 0xFF;
      int r3 = raw[opOff + 2] & 0xFF;
      int r4 = raw[opOff + 3] & 0xFF;
      int l1 = raw[opOff + 4] & 0xFF;
      int l2 = raw[opOff + 5] & 0xFF;
      int l3 = raw[opOff + 6] & 0xFF;
      int l4 = raw[opOff + 7] & 0xFF;

      // ── Update Sliders and Labels ──
      levelSlider.setValue(level);
      levelValLabel.setText(String.valueOf(level));

      modeCombo.setSelectedIndex(Math.max(0, Math.min(1, mode)));

      coarseSlider.setValue(coarse);
      coarseValLabel.setText(String.valueOf(coarse));

      fineSlider.setValue(fine);
      fineValLabel.setText(String.valueOf(fine));

      detuneSlider.setValue(detune);
      int detuneOffset = detune - 7;
      detuneValLabel.setText((detuneOffset >= 0 ? "+" : "") + detuneOffset);

      velSensSlider.setValue(velSens);
      velValLabel.setText(String.valueOf(velSens));

      ampModSensSlider.setValue(ampMod);
      ampModValLabel.setText(String.valueOf(ampMod));

      for (int i = 0; i < 4; i++) {
        int rVal = raw[opOff + i] & 0xFF;
        rateSliders[i].setValue(rVal);
        rateValLabels[i].setText(String.valueOf(rVal));

        int lVal = raw[opOff + 4 + i] & 0xFF;
        levelSliders[i].setValue(lVal);
        levelValLabels[i].setText(String.valueOf(lVal));
      }

      // Update envelope visual graph
      envelopeGraph.updateEnvelope(r1, r2, r3, r4, l1, l2, l3, l4);

      // Re-style active ribbon selection focus buttons
      for (int i = 0; i < 6; i++) {
        opButtons[i].setBackground(
            i == currentSelectedOp ? new Color(0x00, 0xcc, 0xff) : new Color(0x33, 0x33, 0x38));
        opButtons[i].setForeground(i == currentSelectedOp ? Color.BLACK : Color.WHITE);
      }

      // ── Sync LFO & Globals ──
      int lfoSpeed = raw[Dx7Patch.OFF_LFO_SPEED] & 0xFF;
      int lfoDelay = raw[Dx7Patch.OFF_LFO_DELAY] & 0xFF;
      int lfoPMod = raw[Dx7Patch.OFF_PMOD_DEPTH] & 0xFF;
      int lfoAMod = raw[Dx7Patch.OFF_AMOD_DEPTH] & 0xFF;
      int lfoSync = raw[Dx7Patch.OFF_LFO_SYNC] & 0xFF;
      int lfoWave = raw[Dx7Patch.OFF_LFO_WAVEFORM] & 0xFF;
      int feedback = raw[Dx7Patch.OFF_FEEDBACK] & 0x07;
      int transpose = raw[Dx7Patch.OFF_TRANSPOSE] & 0xFF;

      feedbackSlider.setValue(feedback);
      feedbackValLabel.setText(String.valueOf(feedback));

      transposeSlider.setValue(transpose);
      int transOffset = transpose - 64;
      transposeValLabel.setText((transOffset >= 0 ? "+" : "") + transOffset);

      lfoSpeedSlider.setValue(lfoSpeed);
      lfoSpeedValLabel.setText(String.valueOf(lfoSpeed));

      lfoDelaySlider.setValue(lfoDelay);
      lfoDelayValLabel.setText(String.valueOf(lfoDelay));

      lfoPModSlider.setValue(lfoPMod);
      lfoPModValLabel.setText(String.valueOf(lfoPMod));

      lfoAModSlider.setValue(lfoAMod);
      lfoAModValLabel.setText(String.valueOf(lfoAMod));

      lfoSyncSlider.setValue(lfoSync);
      lfoSyncValLabel.setText(lfoSync == 1 ? "ON" : "OFF");

      lfoWaveformCombo.setSelectedIndex(Math.max(0, Math.min(5, lfoWave)));

      // ── Sync Voice Name & Algo Display ──
      try {
        patchNameField.setText(org.chuck.audio.util.Dx7Patch.fromHex(curPatch).name().trim());
      } catch (Exception e) {
        patchNameField.setText("UNKNOWN");
      }

      int algo = raw[Dx7Patch.OFF_ALGORITHM] & 0x1F;
      algoValLabel.setText(String.valueOf(algo));
      algoAsciiPreview.setText(AlgorithmPanel.formatAlgorithmMini(algo));

    } catch (Exception ex) {
      LOG.warning("Failed to sync operator details: " + ex.getMessage());
    } finally {
      isRefreshingOpDetails = false;
    }
  }

  /** Helper: write a byte to the current selected operator's field offset. */
  private void writeOpField(int fieldOff, int value) {
    if (isRefreshingOpDetails) return;
    byte[] raw = getCurrentRaw(model, model.getDx7Patch());
    if (raw == null) return;
    int opOff = currentSelectedOp * 21;
    raw[opOff + fieldOff] = (byte) (value & 0xFF);
    String newHex = Dx7Patch.bytesToHex(raw);
    model.setDx7Patch(newHex);
    bridge.setDx7Patch(trackIndex, newHex);
    if (vm != null) {
      vm.setGlobalString("g_dx7_patch_" + trackIndex, newHex);
    }

    // Live update envelope graph if this is a rate/level parameter!
    if (fieldOff < 8) {
      int r1 = raw[opOff + 0] & 0xFF;
      int r2 = raw[opOff + 1] & 0xFF;
      int r3 = raw[opOff + 2] & 0xFF;
      int r4 = raw[opOff + 3] & 0xFF;
      int l1 = raw[opOff + 4] & 0xFF;
      int l2 = raw[opOff + 5] & 0xFF;
      int l3 = raw[opOff + 6] & 0xFF;
      int l4 = raw[opOff + 7] & 0xFF;
      envelopeGraph.updateEnvelope(r1, r2, r3, r4, l1, l2, l3, l4);
    }
  }

  /** Helper: write a byte to a global patch field offset. */
  private void writeGlobalField(int offset, int value) {
    if (isRefreshingOpDetails) return;
    setPatchByte(model, model.getDx7Patch(), offset, value);
    if (offset == Dx7Patch.OFF_ALGORITHM) {
      algoValLabel.setText(String.valueOf(value));
      algoAsciiPreview.setText(AlgorithmPanel.formatAlgorithmMini(value));
    }
  }

  /** Helper: create a custom-styled JSlider. */
  private JSlider createStyledSlider(int min, int max, int val, int width) {
    JSlider slider = new JSlider(min, max, val);
    slider.setBackground(new Color(0x1e, 0x1e, 0x24));
    slider.setPreferredSize(new Dimension(width, 24));
    slider.setUI(
        new javax.swing.plaf.basic.BasicSliderUI(slider) {
          @Override
          public void paintTrack(Graphics g) {
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(0x3e, 0x3e, 0x42));
            int cy = trackRect.y + trackRect.height / 2 - 2;
            g2.fillRoundRect(trackRect.x, cy, trackRect.width, 4, 2, 2);

            int thumbX = thumbRect.x + thumbRect.width / 2;
            int trackX = thumbX - trackRect.x;
            g2.setColor(new Color(0x00, 0xcc, 0xff)); // glowing cyan selection track!
            g2.fillRoundRect(trackRect.x, cy, trackX, 4, 2, 2);
          }

          @Override
          public void paintThumb(Graphics g) {
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Color.WHITE);
            g2.fillOval(
                thumbRect.x + 2, thumbRect.y + 2, thumbRect.width - 4, thumbRect.height - 4);
          }
        });
    return slider;
  }

  /** Style buttons with modern flat action colors. */
  private void styleActionBtn(JButton btn, Color bg, Color borderAccent) {
    btn.setBackground(bg);
    btn.setForeground(Color.WHITE);
    btn.setFocusPainted(false);
    btn.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(borderAccent, 1),
            BorderFactory.createEmptyBorder(6, 12, 6, 12)));
    btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
    btn.addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mouseEntered(java.awt.event.MouseEvent e) {
            btn.setBackground(bg.brighter());
          }

          @Override
          public void mouseExited(java.awt.event.MouseEvent e) {
            btn.setBackground(bg);
          }
        });
  }

  /** Get a byte from the DX7 patch hex string. */
  private static int getPatchByte(String hex, int offset) {
    if (hex == null || hex.length() < (offset + 1) * 2) return 0;
    byte[] raw = Dx7Patch.hexToBytes(hex);
    return raw[offset] & 0xFF;
  }

  /** Set a byte in the DX7 patch hex string and update the model. */
  private void setPatchByte(SynthTrackModel model, String curHex, int offset, int value) {
    byte[] raw = getCurrentRaw(model, curHex);
    if (raw == null) return;
    if (offset >= 0 && offset < raw.length) {
      raw[offset] = (byte) (value & 0xFF);
      String newHex = Dx7Patch.bytesToHex(raw);
      model.setDx7Patch(newHex);
      bridge.setDx7Patch(trackIndex, newHex);
      if (vm != null) {
        vm.setGlobalString("g_dx7_patch_" + trackIndex, newHex);
      }
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
  private static void applyDx7Patch(
      SynthTrackModel model,
      ChuckVM vm,
      BridgeContract bridge,
      int trackIndex,
      org.chuck.audio.util.Dx7Patch patch) {
    String hex = org.deluge.xml.Dx7SyxParser.patchToHex(patch);
    model.setDx7Patch(hex);
    model.setSynthMode(1);
    model.setSynthAlgorithm(patch.algorithm());
    String globalName = "g_dx7_patch_" + trackIndex;
    vm.setGlobalString(globalName, hex);
    vm.setGlobalInt("g_dx7_opSwitch_" + trackIndex, patch.opSwitch());
  }

  /** Custom visualizer component for rendering 4-stage DX7 envelope shapes in neon cyan. */
  private static class Dx7EnvelopeGraph extends JPanel {
    private int r1, r2, r3, r4;
    private int l1, l2, l3, l4;

    public Dx7EnvelopeGraph() {
      setPreferredSize(new Dimension(200, 110));
      setBackground(new Color(0x10, 0x10, 0x14));
      setBorder(
          BorderFactory.createCompoundBorder(
              BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x30), 1),
              BorderFactory.createEmptyBorder(2, 2, 2, 2)));
    }

    public void updateEnvelope(int r1, int r2, int r3, int r4, int l1, int l2, int l3, int l4) {
      this.r1 = r1;
      this.r2 = r2;
      this.r3 = r3;
      this.r4 = r4;
      this.l1 = l1;
      this.l2 = l2;
      this.l3 = l3;
      this.l4 = l4;
      repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      Graphics2D g2 = (Graphics2D) g;
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

      int w = getWidth();
      int h = getHeight();

      // Draw grid lines
      g2.setColor(new Color(0x22, 0x22, 0x28));
      for (int i = 1; i < 4; i++) {
        int x = w * i / 4;
        g2.drawLine(x, 0, x, h);
        int y = h * i / 4;
        g2.drawLine(0, y, w, y);
      }

      // Map rates (0-99) to durations (inversely proportional)
      int d1 = Math.max(8, (99 - r1) * 35 / 99);
      int d2 = Math.max(8, (99 - r2) * 35 / 99);
      int d3 = Math.max(8, (99 - r3) * 35 / 99);
      int d4 = Math.max(8, (99 - r4) * 35 / 99);

      // Map levels (0-99) to Y coordinates (bottom to top)
      int y0 = h - 8;
      int y1 = h - 8 - (l1 * (h - 16) / 99);
      int y2 = h - 8 - (l2 * (h - 16) / 99);
      int y3 = h - 8 - (l3 * (h - 16) / 99);
      int y4 = h - 8 - (l4 * (h - 16) / 99);

      // Distribute stages across width
      int pad = 12;
      int availWidth = w - pad * 2;
      int totalDuration = d1 + d2 + d3 + d4;
      if (totalDuration == 0) totalDuration = 1;

      int x0 = pad;
      int x1 = pad + (d1 * availWidth / totalDuration);
      int x2 = x1 + (d2 * availWidth / totalDuration);
      int x3 = x2 + (d3 * availWidth / totalDuration);
      int x4 = w - pad;

      // Draw active fill shape with soft neon cyan glow
      int[] px = {x0, x1, x2, x3, x4, x4, x0};
      int[] py = {y0, y1, y2, y3, y4, h, h};
      g2.setColor(new Color(0x00, 0xcc, 0xff, 20));
      g2.fillPolygon(px, py, px.length);

      // Draw envelope stroke line
      g2.setColor(new Color(0x00, 0xcc, 0xff));
      g2.setStroke(new BasicStroke(2.0f));
      g2.drawPolyline(new int[] {x0, x1, x2, x3, x4}, new int[] {y0, y1, y2, y3, y4}, 5);

      // Draw anchor points
      g2.setColor(Color.WHITE);
      g2.fillOval(x1 - 2, y1 - 2, 5, 5);
      g2.fillOval(x2 - 2, y2 - 2, 5, 5);
      g2.fillOval(x3 - 2, y3 - 2, 5, 5);
      g2.fillOval(x4 - 2, y4 - 2, 5, 5);

      // Sub-label: "EG Graph"
      g2.setColor(new Color(0x66, 0x66, 0x6e));
      g2.setFont(new Font("SansSerif", Font.BOLD, 8));
      g2.drawString("EG SHAPE", 6, 12);
    }
  }
}
