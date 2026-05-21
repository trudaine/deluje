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

    setLayout(new FlowLayout(FlowLayout.LEFT, 10, 4));
    setBackground(new Color(0x25, 0x25, 0x25));

    // ── View mode toggles ──

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

    clipBtn.addActionListener(e -> listener.onViewModeChanged("CLIP"));
    songBtn.addActionListener(e -> listener.onViewModeChanged("SONG"));
    arrBtn.addActionListener(e -> listener.onViewModeChanged("ARR"));
    autoBtn.addActionListener(e -> listener.onViewModeChanged("AUTO"));
    perfBtn.addActionListener(e -> listener.onViewModeChanged("PERF"));

    add(clipBtn);
    add(songBtn);
    add(arrBtn);
    add(autoBtn);
    add(perfBtn);
    add(new JSeparator(JSeparator.VERTICAL));

    // ── Track add buttons ──

    JButton addKitBtn = new JButton("+ KIT");
    addKitBtn.setBackground(new Color(0x33, 0x44, 0x55));
    addKitBtn.setForeground(Color.WHITE);
    addKitBtn.addActionListener(e -> listener.onAddTrack("KIT"));

    JButton addSynthBtn = new JButton("+ SYNTH");
    addSynthBtn.setBackground(new Color(0x44, 0x33, 0x55));
    addSynthBtn.setForeground(Color.WHITE);
    addSynthBtn.addActionListener(e -> listener.onAddTrack("SYNTH"));

    JButton addAudioBtn = new JButton("+ AUDIO");
    addAudioBtn.setBackground(new Color(0x33, 0x55, 0x44));
    addAudioBtn.setForeground(Color.WHITE);
    addAudioBtn.addActionListener(e -> listener.onAddTrack("AUDIO"));

    add(addKitBtn);
    add(addSynthBtn);
    add(addAudioBtn);
    add(new JSeparator(JSeparator.VERTICAL));

    // ── Explorer / Monitor toggles ──

    JButton btnExplorer = new JButton("EXPLORER");
    btnExplorer.addActionListener(e -> leftFloat.setVisible(!leftFloat.isVisible()));
    add(btnExplorer);

    JButton btnMonitor = new JButton("MONITOR");
    btnMonitor.addActionListener(e -> rightFloat.setVisible(!rightFloat.isVisible()));
    add(btnMonitor);
    add(new JSeparator(JSeparator.VERTICAL));

    // ── Transport ──

    JButton playBtn = new JButton("\u25B6 PLAY");
    playBtn.setBackground(new Color(0x33, 0x66, 0x33));
    playBtn.setForeground(Color.WHITE);
    playBtn.addActionListener(
        e ->
            vm.setGlobalInt(
                BridgeContract.G_PLAY, vm.getGlobalInt(BridgeContract.G_PLAY) == 1L ? 0L : 1L));
    add(playBtn);

    JButton stopBtn = new JButton("\u25A0 STOP");
    stopBtn.setBackground(new Color(0x66, 0x33, 0x33));
    stopBtn.setForeground(Color.WHITE);
    stopBtn.addActionListener(e -> vm.setGlobalInt(BridgeContract.G_PLAY, 0L));
    add(stopBtn);
    add(new JSeparator(JSeparator.VERTICAL));

    // ── Sliders ──

    add(new JLabel("BPM:"));
    JSlider bpmSlider = new JSlider(60, 200, (int) projectModel.getBpm());
    bpmSlider.addChangeListener(e -> projectModel.setBpm(bpmSlider.getValue()));
    add(bpmSlider);

    add(new JLabel("MASTER:"));
    masterVolSlider = new JSlider(0, 100, (int) (projectModel.getMasterVolume() * 100));
    masterVolSlider.addChangeListener(
        e -> projectModel.setMasterVolume(masterVolSlider.getValue() / 100.0f));
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
    SwingOledPanel oledPanel = new SwingOledPanel();
    oledPanel.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY, 2));
    add(oledPanel);

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
            (offset) -> MatrixDriver.get().selectEncoderAction(offset),
            (on) -> MatrixDriver.get().selectButtonAction(on)));

    add(encoderPanel);

    FirmwareDisplay.get()
        .setListener(
            (main, popup) -> {
              // Handled by oledPanel
            });
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
  }

  public void setMasterVol(int value) {
    masterVolSlider.setValue(value);
  }

  public int getMasterVol() {
    return masterVolSlider.getValue();
  }
}
