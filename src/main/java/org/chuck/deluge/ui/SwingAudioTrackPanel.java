package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.AudioTrackModel;

/**
 * Sidebar/editor panel for controlling an Audio track: record, play, loop, rate. Talks to the
 * engine via BridgeContract globals (G_AUDIO_REC, G_AUDIO_PLAY, etc.).
 */
public class SwingAudioTrackPanel extends JPanel {

  private final ChuckVM vm;
  private final BridgeContract bridge;
  private int engineRow = -1;
  private AudioTrackModel model;

  private final JToggleButton recordBtn = new JToggleButton("REC");
  private final JToggleButton playBtn = new JToggleButton("PLAY");
  private final JToggleButton loopBtn = new JToggleButton("LOOP");
  private final JSlider rateSlider = new JSlider(25, 400, 100);
  private final JLabel rateLabel = new JLabel("1.00x");

  public SwingAudioTrackPanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;
    setLayout(new GridBagLayout());
    setBackground(new Color(0x2a, 0x2a, 0x2a));
    setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(0x44, 0x44, 0x44)),
            "Audio Track",
            javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
            javax.swing.border.TitledBorder.DEFAULT_POSITION,
            new Font("Monospaced", Font.BOLD, 12),
            new Color(0x88, 0xcc, 0xaa)));

    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(4, 6, 4, 6);
    c.fill = GridBagConstraints.HORIZONTAL;

    // Record
    c.gridx = 0;
    c.gridy = 0;
    recordBtn.setBackground(new Color(0x66, 0x22, 0x22));
    recordBtn.setForeground(Color.WHITE);
    recordBtn.setToolTipText("Toggle recording from audio input");
    recordBtn.addActionListener(e -> pushRecord());
    add(recordBtn, c);

    // Play
    c.gridx = 1;
    c.gridy = 0;
    playBtn.setBackground(new Color(0x22, 0x66, 0x22));
    playBtn.setForeground(Color.WHITE);
    playBtn.setToolTipText("Toggle playback");
    playBtn.addActionListener(e -> pushPlay());
    add(playBtn, c);

    // Loop
    c.gridx = 2;
    c.gridy = 0;
    loopBtn.setSelected(true);
    loopBtn.setBackground(new Color(0x44, 0x44, 0x22));
    loopBtn.setForeground(Color.WHITE);
    loopBtn.setToolTipText("Loop playback on/off");
    loopBtn.addActionListener(e -> pushLoop());
    add(loopBtn, c);

    // Rate slider
    c.gridx = 0;
    c.gridy = 1;
    c.gridwidth = 2;
    rateSlider.setMajorTickSpacing(75);
    rateSlider.setPaintTicks(true);
    rateSlider.setForeground(Color.LIGHT_GRAY);
    rateSlider.addChangeListener(e -> pushRate());
    add(rateSlider, c);

    c.gridx = 2;
    c.gridy = 1;
    c.gridwidth = 1;
    rateLabel.setForeground(Color.LIGHT_GRAY);
    rateLabel.setFont(new Font("Monospaced", Font.PLAIN, 11));
    add(rateLabel, c);
  }

  /** Set which engine row and model this panel controls. */
  public void setAudioTrack(int engineRow, AudioTrackModel model) {
    this.engineRow = engineRow;
    this.model = model;

    if (model != null) {
      recordBtn.setSelected(model.isRecording());
      playBtn.setSelected(model.isPlaying());
      loopBtn.setSelected(model.isLooping());
      rateSlider.setValue((int) (model.getPlayRate() * 100));
      rateLabel.setText(String.format("%.2fx", model.getPlayRate()));
    }
  }

  private void pushRecord() {
    if (model == null || engineRow < 0) return;
    boolean rec = recordBtn.isSelected();
    model.setRecording(rec);
    bridge.setAudioRec(engineRow, rec ? 1 : 0);
  }

  private void pushPlay() {
    if (model == null || engineRow < 0) return;
    boolean play = playBtn.isSelected();
    model.setPlaying(play);
    bridge.setAudioPlay(engineRow, play ? 1 : 0);
  }

  private void pushLoop() {
    if (model == null || engineRow < 0) return;
    boolean loop = loopBtn.isSelected();
    model.setLooping(loop);
    bridge.setAudioLoop(engineRow, loop ? 1 : 0);
  }

  private void pushRate() {
    if (model == null || engineRow < 0) return;
    float rate = rateSlider.getValue() / 100.0f;
    model.setPlayRate(rate);
    bridge.setAudioRate(engineRow, rate);
    rateLabel.setText(String.format("%.2fx", rate));
  }
}
