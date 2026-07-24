package org.deluge.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JTabbedPane;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import org.deluge.BridgeContract;
import org.deluge.model.ProjectModel;
import org.deluge.project.PreferencesManager;

/**
 * A beautiful, dark-mode Master FX Mixing Console providing visual control over global song-level
 * reverb, sidechain compression, stereo delay, and master saturation.
 */
public class SwingMasterFxDialog extends JDialog {

  private final ProjectModel projectModel;
  private final BridgeContract bridge;
  private final SwingDelugeApp app;

  // Modern HSL-tailored colors matching premium Deluge aesthetics
  private static final Color BG_DARK = new Color(0x18, 0x18, 0x1a);
  private static final Color BG_CARD = new Color(0x26, 0x26, 0x26);
  private static final Color GLOW_BLUE = new Color(0x38, 0xbd, 0xf8);
  private static final Color GLOW_GREEN = new Color(0x10, 0xb9, 0x81);
  private static final Color GLOW_ORANGE = new Color(0xf9, 0x73, 0x16);
  private static final Color TEXT_LIGHT = new Color(0xf1, 0xf5, 0xf9);
  private static final Font FONT_HEADER = new Font("SansSerif", Font.BOLD, 14);
  private static final Font FONT_LABEL = new Font("SansSerif", Font.BOLD, 11);
  private static final Font FONT_VALUE = new Font("SansSerif", Font.PLAIN, 11);

  public SwingMasterFxDialog(
      Frame owner, ProjectModel projectModel, BridgeContract bridge, SwingDelugeApp app) {
    super(owner, "Master FX Console", true);
    this.projectModel = projectModel;
    this.bridge = bridge;
    this.app = app;

    setLayout(new BorderLayout());
    getContentPane().setBackground(BG_DARK);

    // ── Header Panel ──
    JPanel headerPanel = new JPanel(new BorderLayout());
    headerPanel.setBackground(BG_DARK);
    headerPanel.setBorder(BorderFactory.createEmptyBorder(15, 20, 10, 20));

    JLabel titleLabel = new JLabel("🎛️ MASTER EFFECTS CONSOLE", SwingConstants.LEFT);
    titleLabel.setFont(FONT_HEADER);
    titleLabel.setForeground(GLOW_BLUE);
    // The emoji prefix under-measures on some fonts, clipping the final glyph ("CONSOLI");
    // trailing padding gives the label slack to render the full title.
    titleLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 16));
    headerPanel.add(titleLabel, BorderLayout.WEST);

    JLabel subtitleLabel = new JLabel("Global Mix & Spatial Sculpting Desk", SwingConstants.RIGHT);
    subtitleLabel.setFont(new Font("SansSerif", Font.ITALIC, 11));
    subtitleLabel.setForeground(Color.GRAY);
    headerPanel.add(subtitleLabel, BorderLayout.EAST);

    add(headerPanel, BorderLayout.NORTH);

    // Per-component styling only — the old UIManager.put("TabbedPane.*") calls here mutated
    // GLOBAL look-and-feel state, so every tab bar in the app themed differently depending on
    // whether this dialog had been opened first.
    JTabbedPane tabbedPane = new JTabbedPane();
    tabbedPane.setFont(FONT_LABEL);
    tabbedPane.setBackground(BG_DARK);
    tabbedPane.setForeground(TEXT_LIGHT);
    SwingGridPanel.styleTabs(tabbedPane);

    // Add Tabs
    tabbedPane.addTab("🌌 REVERB TANK", createReverbTab());
    tabbedPane.addTab("💨 REVERB COMP", createReverbCompTab());
    tabbedPane.addTab("⏳ STEREO DELAY", createDelayTab());
    tabbedPane.addTab("🌋 DRIVE & SAT", createDriveTab());

    tabbedPane.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));
    add(tabbedPane, BorderLayout.CENTER);

    // ── Footer Panel ──
    JPanel footerPanel = new JPanel(new BorderLayout());
    footerPanel.setBackground(BG_DARK);
    footerPanel.setBorder(BorderFactory.createEmptyBorder(10, 20, 15, 20));

    JButton closeBtn = new JButton("CLOSE CONSOLE");
    styleFooterButton(closeBtn, new Color(0x33, 0x41, 0x55), Color.WHITE);
    closeBtn.addActionListener(e -> dispose());
    footerPanel.add(closeBtn, BorderLayout.EAST);

    add(footerPanel, BorderLayout.SOUTH);

    setSize(640, 480);
    setLocationRelativeTo(owner);
    setResizable(false);
  }

  // ── Tab 1: Reverb Tank 🌌 ──
  private JPanel createReverbTab() {
    JPanel panel = createTabPanel();

    // Reverb Model ComboBox
    String[] models = {"Freeverb (Default)", "Mutable Rings Excitation", "Digital Chamber"};
    JComboBox<String> modelCombo = new JComboBox<>(models);
    modelCombo.setBackground(BG_DARK);
    modelCombo.setForeground(TEXT_LIGHT);
    modelCombo.setFont(FONT_VALUE);
    modelCombo.setSelectedIndex(projectModel.getReverbModel());
    modelCombo.addActionListener(
        e -> {
          int idx = modelCombo.getSelectedIndex();
          projectModel.setReverbModel(idx);
          bridge.setGlobalInt(BridgeContract.G_REVERB_MODEL, idx);
          app.updateHardwareLedDisplayTransient("RV.MD", "M" + idx);
        });

    addControlRow(
        panel,
        0,
        "Reverb Model:",
        modelCombo,
        "Selects the DSP algorithm driving the spatial tank.");

    // Sliders
    addSliderRow(
        panel,
        1,
        "Room Size:",
        (int) (projectModel.getReverbRoomSize() * 100),
        0,
        100,
        "%",
        val -> {
          float f = val / 100.0f;
          projectModel.setReverbRoomSize(f);
          bridge.setGlobalFloat(BridgeContract.G_REVERB_ROOM, f);
          app.updateHardwareLedDisplayTransient("RV.RM", val + "%");
        },
        GLOW_BLUE);

    addSliderRow(
        panel,
        2,
        "Dampening:",
        (int) (projectModel.getReverbDampening() * 100),
        0,
        100,
        "%",
        val -> {
          float f = val / 100.0f;
          projectModel.setReverbDampening(f);
          bridge.setGlobalFloat(BridgeContract.G_REVERB_DAMP, f);
          app.updateHardwareLedDisplayTransient("RV.DM", val + "%");
        },
        GLOW_BLUE);

    addSliderRow(
        panel,
        3,
        "Stereo Width:",
        (int) (projectModel.getReverbWidth() * 100),
        0,
        100,
        "%",
        val -> {
          float f = val / 100.0f;
          projectModel.setReverbWidth(f);
          bridge.setGlobalFloat(BridgeContract.G_REVERB_WIDTH, f);
          app.updateHardwareLedDisplayTransient("RV.WD", val + "%");
        },
        GLOW_BLUE);

    addSliderRow(
        panel,
        4,
        "High-Pass Filter:",
        (int) (projectModel.getReverbHpf() * 100),
        0,
        100,
        "%",
        val -> {
          float f = val / 100.0f;
          projectModel.setReverbHpf(f);
          bridge.setGlobalFloat(BridgeContract.G_REVERB_HPF, f);
          app.updateHardwareLedDisplayTransient("RV.HP", val + "%");
        },
        GLOW_BLUE);

    addSliderRow(
        panel,
        5,
        "Stereo Pan:",
        (int) ((projectModel.getReverbPan() + 1.0f) * 50),
        0,
        100,
        "L/R",
        val -> {
          float f = (val / 50.0f) - 1.0f;
          projectModel.setReverbPan(f);
          bridge.setGlobalFloat(BridgeContract.G_REVERB_PAN, f);
          app.updateHardwareLedDisplayTransient("RV.PN", val + "");
        },
        GLOW_BLUE);

    return panel;
  }

  // ── Tab 2: Reverb Compressor 💨 ──
  private JPanel createReverbCompTab() {
    JPanel panel = createTabPanel();

    addSliderRow(
        panel,
        0,
        "Sidechain Attack:",
        (int) (projectModel.getReverbCompressorAttack() * 100),
        0,
        100,
        "ms",
        val -> {
          float f = val / 100.0f;
          projectModel.setReverbCompressorAttack(f);
          bridge.setGlobalFloat(BridgeContract.G_REVERB_COMP_ATTACK, f);
          app.updateHardwareLedDisplayTransient("RC.AT", val + "ms");
        },
        GLOW_BLUE);

    addSliderRow(
        panel,
        1,
        "Sidechain Release:",
        (int) (projectModel.getReverbCompressorRelease() * 100),
        0,
        100,
        "ms",
        val -> {
          float f = val / 100.0f;
          projectModel.setReverbCompressorRelease(f);
          bridge.setGlobalFloat(BridgeContract.G_REVERB_COMP_RELEASE, f);
          app.updateHardwareLedDisplayTransient("RC.RL", val + "ms");
        },
        GLOW_BLUE);

    addSliderRow(
        panel,
        2,
        "Sync Threshold:",
        projectModel.getReverbCompressorSyncLevel(),
        0,
        8,
        "lvl",
        val -> {
          projectModel.setReverbCompressorSyncLevel(val);
          bridge.setGlobalInt(BridgeContract.G_REVERB_COMP_SYNC_LEVEL, val);
          app.updateHardwareLedDisplayTransient("RC.SY", "L" + val);
        },
        GLOW_BLUE);

    addSliderRow(
        panel,
        3,
        "Compressor HPF:",
        (int) (projectModel.getReverbCompHpf() * 100),
        0,
        100,
        "%",
        val -> {
          float f = val / 100.0f;
          projectModel.setReverbCompHpf(f);
          bridge.setGlobalFloat(BridgeContract.G_REVERB_COMP_HPF, f);
          app.updateHardwareLedDisplayTransient("RC.HP", val + "%");
        },
        GLOW_BLUE);

    addSliderRow(
        panel,
        4,
        "Wet/Dry Blend:",
        (int) (projectModel.getReverbCompBlend() * 100),
        0,
        100,
        "%",
        val -> {
          float f = val / 100.0f;
          projectModel.setReverbCompBlend(f);
          bridge.setGlobalFloat(BridgeContract.G_REVERB_COMP_BLEND, f);
          app.updateHardwareLedDisplayTransient("RC.BL", val + "%");
        },
        GLOW_BLUE);

    addSliderRow(
        panel,
        5,
        "Ducking Shape:",
        (int) (projectModel.getReverbCompressorShape() * 100),
        0,
        100,
        "%",
        val -> {
          float f = val / 100.0f;
          projectModel.setReverbCompressorShape(f);
          bridge.setGlobalFloat(BridgeContract.G_REVERB_COMP_SHAPE, f);
          app.updateHardwareLedDisplayTransient("RC.SH", val + "%");
        },
        GLOW_BLUE);

    addSliderRow(
        panel,
        6,
        "Threshold Vol:",
        (int) (projectModel.getReverbCompressorVolume() * 100),
        0,
        100,
        "%",
        val -> {
          float f = val / 100.0f;
          projectModel.setReverbCompressorVolume(f);
          bridge.setGlobalFloat(BridgeContract.G_REVERB_COMP_VOLUME, f);
          app.updateHardwareLedDisplayTransient("RC.VO", val + "%");
        },
        GLOW_BLUE);

    return panel;
  }

  // ── Tab 3: Stereo Delay ⏳ ──
  private JPanel createDelayTab() {
    JPanel panel = createTabPanel();

    // Ping-Pong toggle
    JToggleButton pingPongBtn =
        new JToggleButton(
            projectModel.getDelayPingPong() == 1 ? "PING-PONG ACTIVE" : "PING-PONG OFF");
    styleToggleButton(pingPongBtn, projectModel.getDelayPingPong() == 1, GLOW_GREEN);
    pingPongBtn.addActionListener(
        e -> {
          int v = pingPongBtn.isSelected() ? 1 : 0;
          projectModel.setDelayPingPong(v);
          bridge.setGlobalInt(BridgeContract.G_DELAY_PINGPONG, v);
          pingPongBtn.setText(v == 1 ? "PING-PONG ACTIVE" : "PING-PONG OFF");
          styleToggleButton(pingPongBtn, pingPongBtn.isSelected(), GLOW_GREEN);
          app.updateHardwareLedDisplayTransient("DL.PP", v == 1 ? "ON" : "OFF");
        });

    // Analog Warmth toggle
    JToggleButton analogBtn =
        new JToggleButton(projectModel.getDelayAnalog() == 1 ? "ANALOG WARMTH ON" : "ANALOG CLEAN");
    styleToggleButton(analogBtn, projectModel.getDelayAnalog() == 1, GLOW_GREEN);
    analogBtn.addActionListener(
        e -> {
          int v = analogBtn.isSelected() ? 1 : 0;
          projectModel.setDelayAnalog(v);
          bridge.setGlobalInt(BridgeContract.G_DELAY_ANALOG, v);
          analogBtn.setText(v == 1 ? "ANALOG WARMTH ON" : "ANALOG CLEAN");
          styleToggleButton(analogBtn, analogBtn.isSelected(), GLOW_GREEN);
          app.updateHardwareLedDisplayTransient("DL.AN", v == 1 ? "WARM" : "CLN");
        });

    JPanel togglesPanel = new JPanel(new GridBagLayout());
    togglesPanel.setBackground(BG_CARD);
    GridBagConstraints cToggle = new GridBagConstraints();
    cToggle.fill = GridBagConstraints.HORIZONTAL;
    cToggle.weightx = 1.0;
    cToggle.insets = new Insets(0, 0, 0, 10);
    togglesPanel.add(pingPongBtn, cToggle);
    cToggle.gridx = 1;
    cToggle.insets = new Insets(0, 0, 0, 0);
    togglesPanel.add(analogBtn, cToggle);

    addControlRow(
        panel,
        0,
        "Delay Mode:",
        togglesPanel,
        "Configure ping-pong bounce and analog tape saturation warmth.");

    // Sliders
    addSliderRow(
        panel,
        1,
        "Delay Feedback:",
        (int) (projectModel.getMasterDelay() * 100),
        0,
        100,
        "%",
        val -> {
          float f = val / 100.0f;
          projectModel.setMasterDelay(f);
          bridge.setGlobalFloat(BridgeContract.G_DELAY_FB, f);
          app.updateHardwareLedDisplayTransient("DL.FB", val + "%");
        },
        GLOW_GREEN);

    addSliderRow(
        panel,
        2,
        "Sync Division:",
        projectModel.getDelaySyncLevel(),
        0,
        16,
        "div",
        val -> {
          projectModel.setDelaySyncLevel(val);
          bridge.setGlobalInt(BridgeContract.G_DELAY_SYNC_LEVEL, val);
          app.updateHardwareLedDisplayTransient("DL.SY", "D" + val);
        },
        GLOW_GREEN);

    return panel;
  }

  // ── Tab 4: Saturation & Color 🌋 ──
  private JPanel createDriveTab() {
    JPanel panel = createTabPanel();

    // Master Saturation Checkbox
    boolean satInit = PreferencesManager.isMasterSaturationEnabled();
    JCheckBox satCheck =
        new JCheckBox(satInit ? "MASTER SATURATION ACTIVE" : "MASTER SATURATION OFF");
    satCheck.setBackground(BG_CARD);
    satCheck.setForeground(TEXT_LIGHT);
    satCheck.setFont(FONT_LABEL);
    satCheck.setSelected(satInit);
    satCheck.addActionListener(
        e -> {
          boolean sel = satCheck.isSelected();
          PreferencesManager.setMasterSaturationEnabled(sel);
          bridge.setGlobalFloat(BridgeContract.G_MASTER_SATURATION, sel ? 1.0f : 0.0f);
          satCheck.setText(sel ? "MASTER SATURATION ACTIVE" : "MASTER SATURATION OFF");
          app.updateHardwareLedDisplayTransient("M.SAT", sel ? "ON" : "OFF");
        });

    addControlRow(
        panel,
        0,
        "Saturation:",
        satCheck,
        "Enables Master Saturation glue across the main audio output.");

    // Filter Drive Checkbox
    boolean driveInit = PreferencesManager.isFilterDriveEnabled();
    JCheckBox driveCheck =
        new JCheckBox(driveInit ? "CHARACTER DRIVE ACTIVE" : "CHARACTER DRIVE CLEAN");
    driveCheck.setBackground(BG_CARD);
    driveCheck.setForeground(TEXT_LIGHT);
    driveCheck.setFont(FONT_LABEL);
    driveCheck.setSelected(driveInit);
    driveCheck.addActionListener(
        e -> {
          boolean sel = driveCheck.isSelected();
          PreferencesManager.setFilterDriveEnabled(sel);
          bridge.setGlobalFloat(BridgeContract.G_CHAR_FILTER_DRIVE, sel ? 1.0f : 0.0f);
          driveCheck.setText(sel ? "CHARACTER DRIVE ACTIVE" : "CHARACTER DRIVE CLEAN");
          app.updateHardwareLedDisplayTransient("C.DRV", sel ? "ON" : "OFF");
        });

    addControlRow(
        panel,
        1,
        "Filter Drive:",
        driveCheck,
        "Adds analog-modeled transistor drive to synth voice filter paths.");

    // Bit Crunch Checkbox
    boolean crunchInit = PreferencesManager.isBitCrunchEnabled();
    JCheckBox crunchCheck =
        new JCheckBox(crunchInit ? "LO-FI BITCRUSH ACTIVE" : "LO-FI BITCRUSH OFF");
    crunchCheck.setBackground(BG_CARD);
    crunchCheck.setForeground(TEXT_LIGHT);
    crunchCheck.setFont(FONT_LABEL);
    crunchCheck.setSelected(crunchInit);
    crunchCheck.addActionListener(
        e -> {
          boolean sel = crunchCheck.isSelected();
          PreferencesManager.setBitCrunchEnabled(sel);
          bridge.setGlobalFloat(BridgeContract.G_BIT_CRUNCH, sel ? 1.0f : 0.0f);
          crunchCheck.setText(sel ? "LO-FI BITCRUSH ACTIVE" : "LO-FI BITCRUSH OFF");
          app.updateHardwareLedDisplayTransient("B.CRN", sel ? "ON" : "OFF");
        });

    addControlRow(
        panel,
        2,
        "Bit Crush:",
        crunchCheck,
        "Applies hardware sample rate degradation for classic lo-fi character.");

    return panel;
  }

  // ── Helper Methods ──
  private JPanel createTabPanel() {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBackground(BG_CARD);
    panel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0x3f, 0x3f, 0x46), 1),
            BorderFactory.createEmptyBorder(15, 20, 15, 20)));
    return panel;
  }

  private void addControlRow(
      JPanel panel,
      int gridy,
      String labelText,
      javax.swing.JComponent control,
      String description) {
    GridBagConstraints c = new GridBagConstraints();
    c.gridy = gridy;
    c.insets = new Insets(8, 0, 8, 0);

    // Label
    c.gridx = 0;
    c.anchor = GridBagConstraints.WEST;
    c.weightx = 0.25;
    JLabel label = new JLabel(labelText);
    label.setFont(FONT_LABEL);
    label.setForeground(TEXT_LIGHT);
    panel.add(label, c);

    // Control
    c.gridx = 1;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.weightx = 0.55;
    panel.add(control, c);

    // Description/Help
    c.gridx = 2;
    c.anchor = GridBagConstraints.WEST;
    c.weightx = 0.20;
    c.insets = new Insets(8, 15, 8, 0);
    JLabel descLabel =
        new JLabel(
            "<html><body style='width: 100px; color: gray; font-size: 8px;'>"
                + description
                + "</body></html>");
    panel.add(descLabel, c);
  }

  private void addSliderRow(
      JPanel panel,
      int gridy,
      String labelText,
      int initValue,
      int min,
      int max,
      String unit,
      java.util.function.Consumer<Integer> listener,
      Color sliderColor) {
    JSlider slider = new JSlider(min, max, initValue);
    slider.setBackground(BG_CARD);
    slider.setFocusable(false);
    slider.setOpaque(false);

    JLabel valLabel = new JLabel(initValue + unit);
    valLabel.setFont(FONT_VALUE);
    valLabel.setForeground(TEXT_LIGHT);

    slider.addChangeListener(
        e -> {
          int val = slider.getValue();
          valLabel.setText(val + unit);
          listener.accept(val);
        });

    // Custom slider track painting matching topbar
    slider.setUI(
        new javax.swing.plaf.basic.BasicSliderUI(slider) {
          @Override
          public void paintTrack(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            int cy = trackRect.y + (trackRect.height / 2) - 2;
            g2.setColor(new Color(0x52, 0x52, 0x5b));
            g2.fillRoundRect(trackRect.x, cy, trackRect.width, 4, 2, 2);
            int thumbPos = thumbRect.x + (thumbRect.width / 2);
            g2.setColor(sliderColor);
            g2.fillRoundRect(trackRect.x, cy, Math.max(0, thumbPos - trackRect.x), 4, 2, 2);
            g2.dispose();
          }

          @Override
          public void paintThumb(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Color.WHITE);
            g2.fillOval(
                thumbRect.x + 2, thumbRect.y + 2, thumbRect.width - 4, thumbRect.height - 4);
            g2.setColor(sliderColor);
            g2.drawOval(
                thumbRect.x + 2, thumbRect.y + 2, thumbRect.width - 4, thumbRect.height - 4);
            g2.dispose();
          }
        });

    JPanel sliderPane = new JPanel(new BorderLayout());
    sliderPane.setBackground(BG_CARD);
    sliderPane.add(slider, BorderLayout.CENTER);
    sliderPane.add(valLabel, BorderLayout.EAST);

    addControlRow(
        panel,
        gridy,
        labelText,
        sliderPane,
        "Adjust the level of " + labelText.toLowerCase().replace(":", "") + " dynamically.");
  }

  private void styleFooterButton(JButton btn, Color bg, Color fg) {
    btn.setBackground(bg);
    btn.setForeground(fg);
    btn.setOpaque(true);
    btn.setContentAreaFilled(true);
    btn.setFont(FONT_LABEL);
    btn.setFocusPainted(false);
    btn.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(bg.brighter(), 1),
            BorderFactory.createEmptyBorder(6, 15, 6, 15)));
  }

  private void styleToggleButton(JToggleButton btn, boolean active, Color activeColor) {
    Color bg = active ? activeColor.darker().darker() : new Color(0x3f, 0x3f, 0x46);
    Color border = active ? activeColor : new Color(0x71, 0x71, 0x7a);
    btn.setBackground(bg);
    btn.setForeground(TEXT_LIGHT);
    btn.setOpaque(true);
    btn.setContentAreaFilled(true);
    btn.setFont(FONT_LABEL);
    btn.setFocusPainted(false);
    btn.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(border, 1),
            BorderFactory.createEmptyBorder(5, 12, 5, 12)));
  }
}
