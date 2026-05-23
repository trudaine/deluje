package org.chuck.deluge.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.firmware.hid.FirmwareDisplay;
import org.chuck.deluge.firmware.hid.MatrixDriver;
import org.chuck.deluge.model.ProjectModel;

/**
 * Top toolbar panel with view mode toggles, track add buttons, transport controls, and master
 * sliders. Uses FlowLayout so controls wrap to multiple rows when the window is too narrow to fit
 * them on one line.
 */
public class SwingTopBarPanel extends JPanel {

  /** Callback interface for actions that need to reach the parent frame. */
  public interface TopBarListener {
    void onViewModeChanged(String viewMode);

    void onAddTrack(String type);

    void onPlayToggle();

    void onStop();

    void onMasterVolumeChanged(float vol);
  }

  private final ProjectModel projectModel;
  private final ChuckVM vm;
  private final JToggleButton clipBtn;
  private final JSlider masterVolSlider;
  private final TopBarListener listener;
  private final RetroLedDisplay retroLedDisplay;
  private final SwingOledPanel oledPanel;

  public RetroLedDisplay getRetroLedDisplay() {
    return retroLedDisplay;
  }

  public SwingOledPanel getOledPanel() {
    return oledPanel;
  }

  /**
   * @param vm ChucK virtual machine for direct bridge writes
   * @param projectModel current project model (used for track count in dialogs)
   * @param leftFloat the explorer JDialog toggled by the EXPLORER button
   * @param rightFloat the monitor JDialog toggled by the MONITOR button
   * @param listener callback for view-mode changes and track additions
   */
  public SwingTopBarPanel(
      ChuckVM vm,
      ProjectModel projectModel,
      JDialog leftFloat,
      JDialog rightFloat,
      TopBarListener listener) {
    this.projectModel = projectModel;
    this.vm = vm;
    this.listener = listener;
    this.retroLedDisplay = new RetroLedDisplay();
    this.oledPanel = new SwingOledPanel();

    setLayout(new FlowLayout(FlowLayout.LEFT, 10, 4));
    setBackground(new Color(0x12, 0x12, 0x14));

    // ── View mode toggles styled as macOS tab segments ──

    clipBtn = new JToggleButton("CLIP", true);
    JToggleButton songBtn = new JToggleButton("SONG");
    JToggleButton arrBtn = new JToggleButton("ARR");
    JToggleButton autoBtn = new JToggleButton("AUTO");
    JToggleButton perfBtn = new JToggleButton("PERF");
    ButtonGroup modeGroup = new ButtonGroup();
    modeGroup.add(clipBtn);
    modeGroup.add(songBtn);
    modeGroup.add(arrBtn);
    modeGroup.add(autoBtn);
    modeGroup.add(perfBtn);

    // Initial styling
    updateTabStyles(clipBtn, songBtn, arrBtn, autoBtn, perfBtn);

    clipBtn.addActionListener(
        e -> {
          updateTabStyles(clipBtn, songBtn, arrBtn, autoBtn, perfBtn);
          listener.onViewModeChanged("CLIP");
        });
    songBtn.addActionListener(
        e -> {
          updateTabStyles(clipBtn, songBtn, arrBtn, autoBtn, perfBtn);
          listener.onViewModeChanged("SONG");
        });
    arrBtn.addActionListener(
        e -> {
          updateTabStyles(clipBtn, songBtn, arrBtn, autoBtn, perfBtn);
          listener.onViewModeChanged("ARR");
        });
    autoBtn.addActionListener(
        e -> {
          updateTabStyles(clipBtn, songBtn, arrBtn, autoBtn, perfBtn);
          listener.onViewModeChanged("AUTO");
        });
    perfBtn.addActionListener(
        e -> {
          updateTabStyles(clipBtn, songBtn, arrBtn, autoBtn, perfBtn);
          listener.onViewModeChanged("PERF");
        });

    add(clipBtn);
    add(songBtn);
    add(arrBtn);
    add(autoBtn);
    add(perfBtn);
    add(new JSeparator(JSeparator.VERTICAL));

    // ── Track add buttons with high contrast and custom themes ──

    JButton addKitBtn = new JButton("+ KIT");
    styleButton(addKitBtn, new Color(0x1e, 0x32, 0x32), new Color(0x00, 0xff, 0xcc));
    addKitBtn.addActionListener(e -> listener.onAddTrack("KIT"));

    JButton addSynthBtn = new JButton("+ SYNTH");
    styleButton(addSynthBtn, new Color(0x32, 0x1e, 0x32), new Color(0xff, 0x33, 0xcc));
    addSynthBtn.addActionListener(e -> listener.onAddTrack("SYNTH"));

    JButton addAudioBtn = new JButton("+ AUDIO");
    styleButton(addAudioBtn, new Color(0x1e, 0x32, 0x22), new Color(0x33, 0xff, 0x33));
    addAudioBtn.addActionListener(e -> listener.onAddTrack("AUDIO"));

    add(addKitBtn);
    add(addSynthBtn);
    add(addAudioBtn);
    add(new JSeparator(JSeparator.VERTICAL));

    // ── Explorer / Monitor toggles ──

    JButton btnExplorer = new JButton("EXPLORER");
    styleButton(btnExplorer, new Color(0x23, 0x23, 0x28), Color.WHITE);
    btnExplorer.addActionListener(e -> leftFloat.setVisible(!leftFloat.isVisible()));
    add(btnExplorer);

    JButton btnMonitor = new JButton("MONITOR");
    styleButton(btnMonitor, new Color(0x23, 0x23, 0x28), Color.WHITE);
    btnMonitor.addActionListener(e -> rightFloat.setVisible(!rightFloat.isVisible()));
    add(btnMonitor);
    add(new JSeparator(JSeparator.VERTICAL));

    // ── Transport ──

    JButton playBtn = new JButton("\u25B6 PLAY");
    styleButton(playBtn, new Color(0x1a, 0x4a, 0x1a), new Color(0x00, 0xff, 0x66));
    playBtn.addActionListener(
        e -> {
          listener.onPlayToggle();
          retroLedDisplay.printTransient("PLAY", "ON");
        });
    add(playBtn);

    JButton stopBtn = new JButton("\u25A0 STOP");
    styleButton(stopBtn, new Color(0x4a, 0x1a, 0x1a), new Color(0xff, 0x33, 0x33));
    stopBtn.addActionListener(
        e -> {
          listener.onStop();
          retroLedDisplay.printTransient("STOP", "OFF");
        });
    add(stopBtn);
    add(new JSeparator(JSeparator.VERTICAL));

    // ── Sliders ──

    JLabel bpmLabel = new JLabel("BPM:");
    bpmLabel.setForeground(Color.WHITE);
    bpmLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
    add(bpmLabel);

    JSlider bpmSlider = new JSlider(60, 200, (int) projectModel.getBpm());
    bpmSlider.setBackground(new Color(0x12, 0x12, 0x14));
    bpmSlider.setForeground(new Color(0x00, 0xff, 0xcc));
    bpmSlider.setOpaque(false);
    bpmSlider.setFocusable(false);
    bpmSlider.setUI(
        new javax.swing.plaf.basic.BasicSliderUI(bpmSlider) {
          @Override
          public void paintTrack(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            int cy = trackRect.y + (trackRect.height / 2) - 2;
            g2.setColor(new Color(0x66, 0x66, 0x6e));
            g2.fillRoundRect(trackRect.x, cy, trackRect.width, 4, 2, 2);
            int thumbPos = thumbRect.x + (thumbRect.width / 2);
            g2.setColor(new Color(0x00, 0xff, 0xcc));
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
            g2.setColor(new Color(0x00, 0xff, 0xcc));
            g2.drawOval(
                thumbRect.x + 2, thumbRect.y + 2, thumbRect.width - 4, thumbRect.height - 4);
            g2.dispose();
          }
        });
    bpmSlider.addChangeListener(
        e -> {
          int val = bpmSlider.getValue();
          projectModel.setBpm(val);
          retroLedDisplay.printTransient("TEM ", String.valueOf(val));
        });
    add(bpmSlider);

    JLabel masterLabel = new JLabel("MASTER:");
    masterLabel.setForeground(Color.WHITE);
    masterLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
    add(masterLabel);

    masterVolSlider = new JSlider(0, 100, (int) (projectModel.getMasterVolume() * 100));
    masterVolSlider.setBackground(new Color(0x12, 0x12, 0x14));
    masterVolSlider.setForeground(new Color(0x00, 0xff, 0xcc));
    masterVolSlider.setOpaque(false);
    masterVolSlider.setFocusable(false);
    masterVolSlider.setUI(
        new javax.swing.plaf.basic.BasicSliderUI(masterVolSlider) {
          @Override
          public void paintTrack(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            int cy = trackRect.y + (trackRect.height / 2) - 2;
            g2.setColor(new Color(0x66, 0x66, 0x6e));
            g2.fillRoundRect(trackRect.x, cy, trackRect.width, 4, 2, 2);
            int thumbPos = thumbRect.x + (thumbRect.width / 2);
            g2.setColor(new Color(0x00, 0xff, 0xcc));
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
            g2.setColor(new Color(0x00, 0xff, 0xcc));
            g2.drawOval(
                thumbRect.x + 2, thumbRect.y + 2, thumbRect.width - 4, thumbRect.height - 4);
            g2.dispose();
          }
        });
    masterVolSlider.addChangeListener(
        e -> {
          int val = masterVolSlider.getValue();
          projectModel.setMasterVolume(val / 100.0f);
          retroLedDisplay.printTransient("VOL ", val + "%");
        });
    add(masterVolSlider);

    // ── Pure Java Mode Indicator ──
    JLabel pureLabel = new JLabel(" PURE JAVA ");
    pureLabel.setOpaque(true);
    pureLabel.setBackground(new Color(0, 50, 0));
    pureLabel.setForeground(new Color(0, 255, 0));
    pureLabel.setFont(new Font("Monospaced", Font.BOLD, 12));
    pureLabel.setBorder(BorderFactory.createLineBorder(Color.GREEN, 1));
    pureLabel.setVisible(vm.getGlobalInt(BridgeContract.G_HI_FI_MODE) != 0);
    add(pureLabel);

    // ── Firmware LED Display (OLED) ──
    oledPanel.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY, 2));
    add(oledPanel);

    // ── Retro Character LED Display (For shift rotary shortcuts!) ──
    add(retroLedDisplay);

    // ── High-Fidelity Encoders ──
    JPanel encoderPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 15, 2));
    encoderPanel.setBackground(new Color(0x25, 0x25, 0x25));

    encoderPanel.add(
        createEncoderSim(
            "HORIZ",
            (offset) -> MatrixDriver.get().horizontalEncoderAction(offset),
            (on) -> MatrixDriver.get().horizontalButtonAction(on)));
    encoderPanel.add(
        createEncoderSim(
            "VERT",
            (offset) -> MatrixDriver.get().verticalEncoderAction(offset),
            (on) -> MatrixDriver.get().verticalButtonAction(on)));
    encoderPanel.add(
        createEncoderSim(
            "SELECT",
            (offset) -> {
              if (SwingDelugeApp.mainInstance != null) {
                SwingGridPanel grid = SwingDelugeApp.mainInstance.getClipPanel();
                if (grid != null && grid.isShiftHeld() && grid.getActiveShiftParam() != null) {
                  grid.adjustRotaryParameter(offset);
                  return;
                }
              }
              MatrixDriver.get().selectEncoderAction(offset);
            },
            (on) -> MatrixDriver.get().selectButtonAction(on)));

    add(encoderPanel);

    FirmwareDisplay.get()
        .setListener(
            (main, popup) -> {
              // Handled by oledPanel
            });

    applyDisplayPreferences();
  }

  private JPanel createEncoderSim(
      String name,
      java.util.function.Consumer<Integer> onRotate,
      java.util.function.Consumer<Boolean> onClick) {
    JPanel p = new JPanel(new BorderLayout());
    p.setBackground(new Color(0x33, 0x33, 0x33));
    JLabel l = new JLabel(name, SwingConstants.CENTER);
    l.setForeground(Color.WHITE);
    l.setFont(new Font("SansSerif", Font.BOLD, 10));
    p.add(l, BorderLayout.NORTH);

    JButton left = new JButton("<");
    JButton right = new JButton(">");
    left.addActionListener(e -> onRotate.accept(-1));
    right.addActionListener(e -> onRotate.accept(1));
    p.add(left, BorderLayout.WEST);
    p.add(right, BorderLayout.EAST);

    JButton click = new JButton("●");
    click.setFont(new Font("SansSerif", Font.PLAIN, 8));
    click.setMargin(new Insets(0, 0, 0, 0));
    click.addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mousePressed(java.awt.event.MouseEvent e) {
            onClick.accept(true);
          }

          @Override
          public void mouseReleased(java.awt.event.MouseEvent e) {
            onClick.accept(false);
          }
        });
    p.add(click, BorderLayout.CENTER);

    return p;
  }

  public void selectClipView() {
    clipBtn.setSelected(true);
    // Force tab update
    java.awt.Component[] comps = getComponents();
    java.util.List<JToggleButton> tabs = new java.util.ArrayList<>();
    for (java.awt.Component c : comps) {
      if (c instanceof JToggleButton tb) {
        tabs.add(tb);
      }
    }
    updateTabStyles(tabs.toArray(new JToggleButton[0]));
  }

  public void applyDisplayPreferences() {
    org.chuck.deluge.project.PreferencesManager.DisplayType dt =
        org.chuck.deluge.project.PreferencesManager.getDisplayType();
    if (oledPanel != null) {
      oledPanel.setVisible(
          dt == org.chuck.deluge.project.PreferencesManager.DisplayType.BOTH
              || dt == org.chuck.deluge.project.PreferencesManager.DisplayType.OLED_ONLY);
    }
    if (retroLedDisplay != null) {
      retroLedDisplay.setVisible(
          dt == org.chuck.deluge.project.PreferencesManager.DisplayType.BOTH
              || dt == org.chuck.deluge.project.PreferencesManager.DisplayType.LED_ONLY);
    }
    revalidate();
    repaint();
  }

  public void setMasterVol(int value) {
    masterVolSlider.setValue(value);
  }

  public int getMasterVol() {
    return masterVolSlider.getValue();
  }

  private void updateTabStyles(JToggleButton... buttons) {
    for (JToggleButton b : buttons) {
      b.setContentAreaFilled(false);
      b.setOpaque(true);
      b.setFocusPainted(false);
      b.setBorder(BorderFactory.createLineBorder(new Color(0x44, 0x44, 0x4f), 1));
      b.setFont(new Font("SansSerif", Font.BOLD, 12));
      b.setMargin(new Insets(4, 12, 4, 12));
      if (b.isSelected()) {
        b.setBackground(Color.WHITE);
        b.setForeground(Color.BLACK);
      } else {
        b.setBackground(new Color(0x18, 0x18, 0x1c));
        b.setForeground(Color.LIGHT_GRAY);
      }
    }
  }

  private void styleButton(JButton btn, Color bg, Color fg) {
    btn.setContentAreaFilled(false);
    btn.setOpaque(true);
    btn.setFocusPainted(false);
    btn.setBackground(bg);
    btn.setForeground(fg);
    btn.setFont(new Font("SansSerif", Font.BOLD, 12));
    btn.setBorder(BorderFactory.createLineBorder(fg, 1));
    btn.setMargin(new Insets(4, 12, 4, 12));

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

  public static class RetroLedDisplay extends JPanel {
    private final JLabel label;

    public RetroLedDisplay() {
      setLayout(new BorderLayout());
      setBackground(new Color(0x1a, 0x05, 0x05)); // dark red background
      setPreferredSize(new java.awt.Dimension(260, 40));
      setBorder(
          BorderFactory.createCompoundBorder(
              BorderFactory.createLineBorder(new Color(0xaa, 0x33, 0x33), 1),
              BorderFactory.createEmptyBorder(2, 6, 2, 6)));

      label = new JLabel("[  --    --  ]");
      label.setForeground(new Color(0xff, 0x33, 0x33)); // bright LED red
      label.setFont(new Font("Monospaced", Font.BOLD, 20));
      label.setHorizontalAlignment(SwingConstants.CENTER);
      add(label, BorderLayout.CENTER);
    }

    private javax.swing.Timer resetTimer;

    public void print(String code, String val) {
      if (resetTimer != null) resetTimer.stop();
      label.setText(String.format("[ %-4s  %6s ]", code.toUpperCase(), val));
      label.setForeground(new Color(0xff, 0x88, 0x00)); // active amber glow!
      setBackground(new Color(0x24, 0x10, 0x00)); // active amber background
      setBorder(BorderFactory.createLineBorder(new Color(0xff, 0x88, 0x00), 1));
    }

    public void printTransient(String code, String val) {
      print(code, val);
      if (resetTimer != null) resetTimer.stop();
      resetTimer = new javax.swing.Timer(1500, e -> reset());
      resetTimer.setRepeats(false);
      resetTimer.start();
    }

    public void reset() {
      label.setText("[  --    --  ]");
      label.setForeground(new Color(0xff, 0x33, 0x33)); // rest standard red
      setBackground(new Color(0x1a, 0x05, 0x05));
      setBorder(BorderFactory.createLineBorder(new Color(0xaa, 0x33, 0x33), 1));
    }
  }
}
