package org.chuck.deluge.ui;

import java.awt.Color;
import java.awt.FlowLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.ProjectModel;

/**
 * Top toolbar panel with view mode toggles, track add buttons, transport controls, and master
 * sliders. Uses FlowLayout so controls wrap to multiple rows when the window is too narrow to fit
 * them on one line. Placed inside a BorderLayout.NORTH wrapper in SwingDelugeApp so the top bar
 * can grow vertically as controls wrap.
 */
public class SwingTopBarPanel extends JPanel {

  /** Callback interface for actions that need to reach the parent frame. */
  public interface TopBarListener {
    void onViewModeChanged(String viewMode);
    void onAddTrack(String type);
  }

  private final ProjectModel projectModel;
  private final ChuckVM vm;
  private final JToggleButton clipBtn;
  private final JSlider masterVolSlider;
  private final TopBarListener listener;

  /**
   * @param vm           ChucK virtual machine for direct bridge writes
   * @param bridge       bridge contract (used only for G_PLAY/G_STOP constants)
   * @param projectModel current project model (used for track count in dialogs)
   * @param leftFloat    the explorer JDialog toggled by the EXPLORER button
   * @param rightFloat   the monitor JDialog toggled by the MONITOR button
   * @param listener     callback for view-mode changes and track additions
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
    ButtonGroup modeGroup = new ButtonGroup();
    modeGroup.add(clipBtn);
    modeGroup.add(songBtn);
    modeGroup.add(arrBtn);
    modeGroup.add(autoBtn);

    clipBtn.addActionListener(e -> listener.onViewModeChanged("CLIP"));
    songBtn.addActionListener(e -> listener.onViewModeChanged("SONG"));
    arrBtn.addActionListener(e -> listener.onViewModeChanged("ARR"));
    autoBtn.addActionListener(e -> listener.onViewModeChanged("AUTO"));

    add(clipBtn);
    add(songBtn);
    add(arrBtn);
    add(autoBtn);
    add(new JSeparator(JSeparator.VERTICAL));

    // ── Track add buttons ──

    JButton addKitBtn = new JButton("+ KIT");
    addKitBtn.setBackground(new Color(0x33, 0x44, 0x55));
    addKitBtn.setForeground(Color.WHITE);
    addKitBtn.setToolTipText("Add a new Kit (drum) track to the song");
    addKitBtn.addActionListener(e -> listener.onAddTrack("KIT"));

    JButton addSynthBtn = new JButton("+ SYNTH");
    addSynthBtn.setBackground(new Color(0x44, 0x33, 0x55));
    addSynthBtn.setForeground(Color.WHITE);
    addSynthBtn.setToolTipText("Add a new Synth track to the song");
    addSynthBtn.addActionListener(e -> listener.onAddTrack("SYNTH"));

    JButton addAudioBtn = new JButton("+ AUDIO");
    addAudioBtn.setBackground(new Color(0x33, 0x55, 0x44));
    addAudioBtn.setForeground(Color.WHITE);
    addAudioBtn.setToolTipText("Add a new Audio (recording) track to the song");
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

    JToggleButton recBtn = new JToggleButton("\u25CF REC");
    recBtn.setForeground(Color.RED);
    recBtn.addActionListener(e -> {
      // Rec button references midiService which lives in SwingDelugeApp — skip it here;
      // SwingDelugeApp can observe the button state via a public accessor if needed.
    });
    add(recBtn);
    add(new JSeparator(JSeparator.VERTICAL));

    // ── Sliders ──

    JLabel tempoLabel = new JLabel("BPM:");
    tempoLabel.setForeground(Color.WHITE);
    add(tempoLabel);

    JSlider bpmSlider = new JSlider(60, 200, (int) projectModel.getBpm());
    bpmSlider.addChangeListener(e -> projectModel.setBpm(bpmSlider.getValue()));
    add(bpmSlider);

    JLabel swingLabel = new JLabel("SWING:");
    swingLabel.setForeground(Color.WHITE);
    add(swingLabel);

    JSlider swingSlider = new JSlider(0, 100, (int) (projectModel.getSwing() * 100));
    swingSlider.addChangeListener(
        e -> projectModel.setSwing(swingSlider.getValue() / 100.0f));
    add(swingSlider);

    JLabel volLabel = new JLabel("MASTER:");
    volLabel.setForeground(Color.WHITE);
    add(volLabel);

    masterVolSlider = new JSlider(0, 100, (int) (projectModel.getMasterVolume() * 100));
    masterVolSlider.addChangeListener(
        e -> projectModel.setMasterVolume(masterVolSlider.getValue() / 100.0f));
    add(masterVolSlider);
  }

  /** Programmatically select the CLIP view toggle button (e.g. after loading a project). */
  public void selectClipView() {
    clipBtn.setSelected(true);
  }

  /** Programmatically set the master volume slider value (e.g. for bottom-slider sync). */
  public void setMasterVol(int value) {
    masterVolSlider.setValue(value);
  }

  /** Current master volume slider value. */
  public int getMasterVol() {
    return masterVolSlider.getValue();
  }
}
