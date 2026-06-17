package org.chuck.deluge.ui;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.engine.DroneLabGenerator;
import org.chuck.deluge.firmware.engine.FirmwareSound;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;

/**
 * Premium, dark-themed macro control dashboard and generative drone creator. Features real-time
 * parameter sweeping and an interactive X/Y modulation pad.
 */
public class SwingDroneLabDialog extends JDialog {

  private final SwingDelugeApp app;
  private final SynthTrackModel track;
  private final ChuckVM vm;
  private final BridgeContract bridge;
  private final int trackIndex;
  private final ProjectModel project;

  private JSlider frictionSlider;
  private JSlider turbulenceSlider;
  private JSlider atmosphereSlider;
  private JSlider gritSlider;
  private XYPadPanel xyPad;

  private JComboBox<String> styleCombo;
  private JComboBox<String> tonalityCombo;

  // Color Palette
  private static final Color BG_DARK = new Color(0x13, 0x13, 0x16);
  private static final Color BG_CARD = new Color(0x1d, 0x1d, 0x22);
  private static final Color BG_CONTROL = new Color(0x28, 0x28, 0x2f);
  private static final Color TEXT_LIGHT = new Color(0xe2, 0xe2, 0xe8);
  private static final Color TEXT_MUTED = new Color(0x88, 0x88, 0x92);
  private static final Color ACCENT_GLOW = new Color(0x00, 0xff, 0xcc); // Neon Cyan
  private static final Color ACCENT_ORANGE = new Color(0xff, 0x99, 0x33); // Neon Orange

  public SwingDroneLabDialog(
      SwingDelugeApp owner,
      SynthTrackModel track,
      ChuckVM vm,
      BridgeContract bridge,
      int trackIndex,
      ProjectModel project) {
    super(owner, "⚡ DRONE LAB & TEXTURE GENERATOR", true);
    this.app = owner;
    this.track = track;
    this.vm = vm;
    this.bridge = bridge;
    this.trackIndex = trackIndex;
    this.project = project;

    setSize(720, 620);
    setLocationRelativeTo(owner);
    setLayout(new BorderLayout());
    getContentPane().setBackground(BG_DARK);

    // ── HEADER ──
    JPanel headerPanel = new JPanel(new BorderLayout());
    headerPanel.setBackground(BG_DARK);
    headerPanel.setBorder(new EmptyBorder(15, 20, 10, 20));

    JLabel titleLabel = new JLabel("⚡ DRONE LAB & TEXTURE GENERATOR");
    titleLabel.setFont(new Font("SansSerif", Font.BOLD, 18));
    titleLabel.setForeground(ACCENT_GLOW);
    headerPanel.add(titleLabel, BorderLayout.NORTH);

    JLabel subLabel =
        new JLabel(
            "Sculpt evolving, microtonally-pure Just Intonation drones and ambient textures in real-time.");
    subLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
    subLabel.setForeground(TEXT_MUTED);
    headerPanel.add(subLabel, BorderLayout.SOUTH);

    add(headerPanel, BorderLayout.NORTH);

    // ── MAIN PANEL ──
    JPanel mainPanel = new JPanel(new GridBagLayout());
    mainPanel.setBackground(BG_DARK);
    mainPanel.setBorder(new EmptyBorder(10, 20, 10, 20));
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.BOTH;
    c.insets = new Insets(8, 8, 8, 8);

    // Style & Tonality Config Card
    JPanel configCard = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 10));
    configCard.setBackground(BG_CARD);
    configCard.setBorder(BorderFactory.createLineBorder(new Color(0x32, 0x32, 0x3a), 1, true));

    configCard.add(label("Style:"));
    styleCombo =
        new JComboBox<>(new String[] {"Subtractive Octave Detune", "6-Operator FM Metallic"});
    styleCombo.setBackground(BG_CONTROL);
    styleCombo.setForeground(TEXT_LIGHT);
    styleCombo.setFocusable(false);
    configCard.add(styleCombo);

    configCard.add(label("Tonality:"));
    tonalityCombo = new JComboBox<>(new String[] {"C Minor Pentatonic", "C Major Pentatonic"});
    tonalityCombo.setBackground(BG_CONTROL);
    tonalityCombo.setForeground(TEXT_LIGHT);
    tonalityCombo.setFocusable(false);
    configCard.add(tonalityCombo);

    c.gridx = 0;
    c.gridy = 0;
    c.gridwidth = 2;
    c.weightx = 1.0;
    c.weighty = 0.0;
    mainPanel.add(configCard, c);

    // Left Panel: 4 Macros
    JPanel macrosPanel = new JPanel(new GridLayout(4, 1, 10, 10));
    macrosPanel.setBackground(BG_DARK);

    frictionSlider =
        createMacroSlider(
            "Friction (Tension)", "Increase carrier detuning and digital decimation dissonance");
    turbulenceSlider =
        createMacroSlider(
            "Turbulence (Evolve)", "Speed up drifting LFO modulations and panning sweeps");
    atmosphereSlider =
        createMacroSlider(
            "Atmosphere (Space)", "Dissolve the drone into a deep, spacious delay/reverb void");
    gritSlider =
        createMacroSlider(
            "Industrial Grit", "Inject analog noise grit and master tube overdrive drive");

    macrosPanel.add(frictionSlider);
    macrosPanel.add(turbulenceSlider);
    macrosPanel.add(atmosphereSlider);
    macrosPanel.add(gritSlider);

    c.gridx = 0;
    c.gridy = 1;
    c.gridwidth = 1;
    c.weightx = 0.45;
    c.weighty = 1.0;
    mainPanel.add(macrosPanel, c);

    // Right Panel: X/Y Pad
    xyPad = new XYPadPanel();
    c.gridx = 1;
    c.gridy = 1;
    c.gridwidth = 1;
    c.weightx = 0.55;
    c.weighty = 1.0;
    mainPanel.add(xyPad, c);

    add(mainPanel, BorderLayout.CENTER);

    // ── SOUTH CONTROL BUTTONS ──
    JPanel southPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 15, 12));
    southPanel.setBackground(BG_DARK);
    southPanel.setBorder(new EmptyBorder(5, 20, 15, 20));

    JButton generateBtn = new JButton("⚡ Generate Evolving Drone");
    generateBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
    generateBtn.setBackground(new Color(0x00, 0x66, 0x55));
    generateBtn.setForeground(Color.WHITE);
    generateBtn.addActionListener(e -> generateDroneAction());
    southPanel.add(generateBtn);

    JButton playBtn = new JButton("▶ Play/Stop");
    playBtn.addActionListener(
        e -> {
          if (app.getTopBarListener() != null) {
            app.getTopBarListener().onPlayToggle();
          }
        });
    southPanel.add(playBtn);

    JButton closeBtn = new JButton("Close");
    closeBtn.addActionListener(e -> dispose());
    southPanel.add(closeBtn);

    add(southPanel, BorderLayout.SOUTH);
  }

  private void generateDroneAction() {
    boolean isFM = styleCombo.getSelectedIndex() == 1;
    boolean isMinor = tonalityCombo.getSelectedIndex() == 0;

    // Generate Synth Preset & sequence 16-bar tied note
    DroneLabGenerator.generateDrone(track, project, bridge, trackIndex, isFM, isMinor);

    // Reset macro sliders to default starting points
    frictionSlider.setValue(20);
    turbulenceSlider.setValue(30);
    atmosphereSlider.setValue(60);
    gritSlider.setValue(25);
    xyPad.setPoint(0.2, 0.3);

    // Synchronize to active sound engine
    FirmwareSound sound = DroneLabGenerator.getActiveTrackSound(vm, trackIndex);
    if (sound != null) {
      DroneLabGenerator.applyMacros(track, sound, 0.2, 0.3, 0.6, 0.25);
    }

    // Force Playback to Start immediately
    if (app.getPureEngine() != null && !app.getPureEngine().getPlaybackHandler().isPlaying()) {
      if (app.getTopBarListener() != null) {
        app.getTopBarListener().onPlayToggle();
      }
    }

    JOptionPane.showMessageDialog(
        this,
        "Drone sound initialized successfully!\n"
            + "- Pure 5-limit Just Intonation custom temperament active.\n"
            + "- 16-bar overlapping C2 tie note sequenced.\n"
            + "- Slow organic LFO random walks patched to Filter & Pan.\n"
            + "Use the X/Y Pad or sliders to morph the drone live!",
        "Drone Lab",
        JOptionPane.INFORMATION_MESSAGE);
  }

  private void handleSliderSweep() {
    double friction = frictionSlider.getValue() / 100.0;
    double turbulence = turbulenceSlider.getValue() / 100.0;
    double atmosphere = atmosphereSlider.getValue() / 100.0;
    double dirt = gritSlider.getValue() / 100.0;

    xyPad.setPoint(friction, turbulence);

    FirmwareSound sound = DroneLabGenerator.getActiveTrackSound(vm, trackIndex);
    if (sound != null) {
      DroneLabGenerator.applyMacros(track, sound, friction, turbulence, atmosphere, dirt);
    }
  }

  private JSlider createMacroSlider(String title, String tooltip) {
    JPanel card = new JPanel(new BorderLayout(5, 2));
    card.setBackground(BG_CARD);
    card.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0x32, 0x32, 0x3a), 1, true),
            new EmptyBorder(8, 10, 8, 10)));

    JLabel lbl = new JLabel(title);
    lbl.setFont(new Font("SansSerif", Font.BOLD, 12));
    lbl.setForeground(TEXT_LIGHT);
    card.add(lbl, BorderLayout.NORTH);

    JSlider slider = new JSlider(0, 100, 50);
    slider.setBackground(BG_CARD);
    slider.setForeground(ACCENT_GLOW);
    slider.setToolTipText(tooltip);
    slider.setFocusable(false);
    slider.addChangeListener(
        e -> {
          if (slider.getValueIsAdjusting()) {
            handleSliderSweep();
          }
        });
    card.add(slider, BorderLayout.CENTER);

    return slider;
  }

  private static JLabel label(String text) {
    JLabel l = new JLabel(text);
    l.setForeground(TEXT_LIGHT);
    l.setFont(new Font("SansSerif", Font.BOLD, 12));
    return l;
  }

  // ── X/Y TOUCH EXPRESSION PAD ──
  private class XYPadPanel extends JPanel {
    private double px = 0.5; // normalized X
    private double py = 0.5; // normalized Y

    XYPadPanel() {
      setBackground(BG_CARD);
      setBorder(BorderFactory.createLineBorder(new Color(0x32, 0x32, 0x3a), 2, true));
      setToolTipText("Click and drag to morph Friction (X) and Turbulence (Y) simultaneously!");

      MouseAdapter listener =
          new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
              updatePoint(e);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
              updatePoint(e);
            }
          };
      addMouseListener(listener);
      addMouseMotionListener(listener);
    }

    void setPoint(double x, double y) {
      this.px = Math.max(0.0, Math.min(1.0, x));
      this.py = Math.max(0.0, Math.min(1.0, y));
      repaint();
    }

    private void updatePoint(MouseEvent e) {
      double x = e.getX() / (double) getWidth();
      double y = 1.0 - (e.getY() / (double) getHeight());

      px = Math.max(0.0, Math.min(1.0, x));
      py = Math.max(0.0, Math.min(1.0, y));

      frictionSlider.setValue((int) (px * 100));
      turbulenceSlider.setValue((int) (py * 100));

      repaint();

      // Sweep parameters in real-time
      double atmosphere = atmosphereSlider.getValue() / 100.0;
      double dirt = gritSlider.getValue() / 100.0;
      FirmwareSound sound = DroneLabGenerator.getActiveTrackSound(vm, trackIndex);
      if (sound != null) {
        DroneLabGenerator.applyMacros(track, sound, px, py, atmosphere, dirt);
      }
    }

    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      Graphics2D g2 = (Graphics2D) g;
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

      int w = getWidth();
      int h = getHeight();

      // Draw Grid lines
      g2.setColor(new Color(0x2a, 0x2a, 0x34));
      g2.setStroke(new BasicStroke(1));
      int divisions = 8;
      for (int i = 1; i < divisions; i++) {
        int gx = i * w / divisions;
        int gy = i * h / divisions;
        g2.drawLine(gx, 0, gx, h);
        g2.drawLine(0, gy, w, gy);
      }

      // Draw Axis Labels
      g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
      g2.setColor(TEXT_MUTED);
      g2.drawString("FRICTION ➔", 10, h - 10);
      g2.drawString("▲ TURBULENCE", 10, 20);

      // Draw glowing crosshairs
      int cx = (int) (px * w);
      int cy = (int) ((1.0 - py) * h);

      g2.setColor(new Color(0x00, 0xff, 0xcc, 30));
      g2.setStroke(new BasicStroke(1));
      g2.drawLine(cx, 0, cx, h);
      g2.drawLine(0, cy, w, cy);

      // Draw glowing dot
      g2.setColor(new Color(0xff, 0x99, 0x33, 60)); // outer glow
      g2.fillOval(cx - 15, cy - 15, 30, 30);

      g2.setColor(ACCENT_ORANGE); // inner core
      g2.fillOval(cx - 6, cy - 6, 12, 12);

      g2.setColor(Color.WHITE);
      g2.drawOval(cx - 6, cy - 6, 12, 12);
    }
  }
}
