package org.deluge.ui;

import java.awt.*;
import java.util.ArrayList;
import javax.swing.*;
import org.deluge.engine.AudioInputCaptureLine;
import org.deluge.model.KitTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.TrackModel;

/**
 * High-fidelity microphone loop sampling dialog featuring a real-time input level meter, decibel
 * threshold slider, and target drum slot destination maps. Automatically triggers transport sync.
 */
public class ThresholdRecordDialog extends JDialog {
  private final ProjectModel project;
  private final Runnable onRefreshGrid;

  private JSlider thresholdSlider;
  private JProgressBar levelMeter;
  private JComboBox<String> trackCombo;
  private JComboBox<String> slotCombo;
  private JLabel statusLabel;
  private JButton armBtn;
  private JButton stopBtn;

  private Timer meterTimer;
  private final AudioInputCaptureLine captureLine = AudioInputCaptureLine.getInstance();

  public ThresholdRecordDialog(Frame parent, ProjectModel project, Runnable onRefreshGrid) {
    super(parent, "🎙️ Audio Input Loop Sampler", true);
    this.project = project;
    this.onRefreshGrid = onRefreshGrid;

    buildUI();
    startLevelMeter();
  }

  private void buildUI() {
    setSize(480, 360);
    setLocationRelativeTo(getParent());
    setResizable(false);

    JPanel mainPanel = new JPanel();
    mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
    mainPanel.setBackground(new Color(0x1a, 0x1a, 0x1c));
    mainPanel.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

    // ── Title Header ──
    JLabel title = new JLabel("THRESHOLD AUTO-START LOOP SAMPLER");
    title.setFont(new Font("SansSerif", Font.BOLD, 14));
    title.setForeground(new Color(0x00, 0xff, 0xcc));
    title.setAlignmentX(Component.CENTER_ALIGNMENT);
    mainPanel.add(title);
    mainPanel.add(Box.createVerticalStrut(15));

    // ── Live Peak Level Meter ──
    JPanel meterPanel = new JPanel(new BorderLayout(5, 0));
    meterPanel.setOpaque(false);
    meterPanel.setMaximumSize(new Dimension(420, 24));
    JLabel meterLabel = new JLabel("IN: ");
    meterLabel.setForeground(Color.LIGHT_GRAY);
    meterLabel.setFont(new Font("Monospaced", Font.BOLD, 12));
    meterPanel.add(meterLabel, BorderLayout.WEST);

    levelMeter = new JProgressBar(0, 100);
    levelMeter.setValue(0);
    levelMeter.setBackground(new Color(0x2d, 0x2d, 0x32));
    levelMeter.setForeground(new Color(0x00, 0xff, 0x66));
    levelMeter.setBorderPainted(false);
    levelMeter.setPreferredSize(new Dimension(360, 14));
    meterPanel.add(levelMeter, BorderLayout.CENTER);
    mainPanel.add(meterPanel);
    mainPanel.add(Box.createVerticalStrut(10));

    // ── dB Threshold Slider ──
    JPanel sliderPanel = new JPanel(new BorderLayout());
    sliderPanel.setOpaque(false);
    sliderPanel.setMaximumSize(new Dimension(420, 48));

    JLabel sliderLabel = new JLabel("Trigger Threshold: -26 dB");
    sliderLabel.setForeground(Color.LIGHT_GRAY);
    sliderLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
    sliderPanel.add(sliderLabel, BorderLayout.NORTH);

    thresholdSlider = new JSlider(-60, 0, -26); // Default -26dB
    DarkSliderUI.styleSlider(thresholdSlider, new Color(0x00, 0xff, 0xcc));
    thresholdSlider.addChangeListener(
        e -> {
          sliderLabel.setText("Trigger Threshold: " + thresholdSlider.getValue() + " dB");
        });
    sliderPanel.add(thresholdSlider, BorderLayout.CENTER);
    mainPanel.add(sliderPanel);
    mainPanel.add(Box.createVerticalStrut(15));

    // ── Destination Target Selectors ──
    JPanel destPanel = new JPanel(new GridLayout(2, 2, 8, 8));
    destPanel.setOpaque(false);
    destPanel.setMaximumSize(new Dimension(420, 64));

    JLabel trkLbl = new JLabel("Target Track:");
    trkLbl.setForeground(Color.LIGHT_GRAY);
    destPanel.add(trkLbl);

    // Kit tracks record into a drum slot; synth tracks record into osc 1 (slot ignored).
    ArrayList<String> kitTracksNames = new ArrayList<>();
    ArrayList<Integer> kitTracksIndices = new ArrayList<>();
    if (project != null) {
      for (int i = 0; i < project.getTracks().size(); i++) {
        TrackModel t = project.getTracks().get(i);
        String nm = t.getName() != null ? t.getName() : ("Track " + (i + 1));
        if (t instanceof KitTrackModel) {
          kitTracksNames.add(nm + " (Kit)");
          kitTracksIndices.add(i);
        } else if (t instanceof org.deluge.model.SynthTrackModel) {
          kitTracksNames.add(nm + " (Synth → Osc1)");
          kitTracksIndices.add(i);
        } else if (t instanceof org.deluge.model.AudioTrackModel) {
          kitTracksNames.add(nm + " (Audio Clip)");
          kitTracksIndices.add(i);
        }
      }
    }

    String[] trkItems = kitTracksNames.toArray(new String[0]);
    trackCombo = new JComboBox<>(trkItems);
    trackCombo.setBackground(new Color(0x2d, 0x2d, 0x32));
    trackCombo.setForeground(Color.WHITE);
    destPanel.add(trackCombo);

    JLabel slotLbl = new JLabel("Target Instrument Slot:");
    slotLbl.setForeground(Color.LIGHT_GRAY);
    destPanel.add(slotLbl);

    String[] slotItems = new String[16];
    for (int i = 0; i < 16; i++) {
      slotItems[i] = "Slot " + (i + 1) + " (Drum)";
    }
    slotCombo = new JComboBox<>(slotItems);
    slotCombo.setBackground(new Color(0x2d, 0x2d, 0x32));
    slotCombo.setForeground(Color.WHITE);
    destPanel.add(slotCombo);
    mainPanel.add(destPanel);
    mainPanel.add(Box.createVerticalStrut(15));

    // ── Status Display label ──
    statusLabel = new JLabel("INPUT IDLE");
    statusLabel.setFont(new Font("Monospaced", Font.BOLD, 12));
    statusLabel.setForeground(Color.GRAY);
    statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
    mainPanel.add(statusLabel);
    mainPanel.add(Box.createVerticalStrut(15));

    // ── Operation Control Buttons Panel ──
    JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
    buttonsPanel.setOpaque(false);

    armBtn = new JButton("🔴 ARM");
    armBtn.setFocusable(false);
    armBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
    armBtn.setBackground(new Color(0x4a, 0x1a, 0x1a));
    armBtn.setForeground(Color.WHITE);
    armBtn.setOpaque(true);
    armBtn.setContentAreaFilled(true);
    armBtn.addActionListener(
        e -> {
          if (kitTracksIndices.isEmpty()) {
            JOptionPane.showMessageDialog(
                this, "Error: No Kit or Synth track exists to map samples onto!");
            return;
          }
          int targetTrk = kitTracksIndices.get(trackCombo.getSelectedIndex());
          int targetSlt = slotCombo.getSelectedIndex();

          captureLine.arm(
              thresholdSlider.getValue(),
              targetTrk,
              targetSlt,
              () -> {
                // onTrigger: Auto-start play clocks
                if (SwingDelugeApp.mainInstance != null
                    && !SwingDelugeApp.mainInstance.isPlaying()) {
                  SwingDelugeApp.mainInstance.triggerPlayToggle();
                }
                statusLabel.setText("🔴 RECORDING LIVE INTERACTIVE ENVELOPE...");
                statusLabel.setForeground(Color.RED);
              },
              () -> {
                // onFinished
                statusLabel.setText("⏹️ WAV SAMPLE LOADED SUCCESSFULLY!");
                statusLabel.setForeground(Color.GREEN);
                if (onRefreshGrid != null) {
                  onRefreshGrid.run();
                }
              });

          statusLabel.setText("⏳ ARMED: WAITING FOR AUDIO THRESHOLD...");
          statusLabel.setForeground(Color.ORANGE);
          armBtn.setEnabled(false);
          stopBtn.setEnabled(true);
        });
    buttonsPanel.add(armBtn);

    stopBtn = new JButton("⏹️ STOP / SAVE");
    stopBtn.setFocusable(false);
    stopBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
    stopBtn.setBackground(new Color(0x2d, 0x2d, 0x32));
    stopBtn.setForeground(Color.WHITE);
    stopBtn.setOpaque(true);
    stopBtn.setContentAreaFilled(true);
    stopBtn.setEnabled(false);
    stopBtn.addActionListener(
        e -> {
          captureLine.stop();
          armBtn.setEnabled(true);
          stopBtn.setEnabled(false);
        });
    buttonsPanel.add(stopBtn);

    mainPanel.add(buttonsPanel);
    getContentPane().add(mainPanel);
  }

  private void startLevelMeter() {
    meterTimer =
        new Timer(
            33,
            e -> {
              if (captureLine.isArmed() || captureLine.isRecording()) {
                float peak = captureLine.getLivePeak();
                levelMeter.setValue((int) (peak * 100));
                if (captureLine.isRecording()) {
                  levelMeter.setForeground(Color.RED);
                } else {
                  levelMeter.setForeground(Color.ORANGE);
                }
              } else {
                levelMeter.setValue(0);
                levelMeter.setForeground(new Color(0x00, 0xff, 0x66));
              }
            });
    meterTimer.start();
  }

  @Override
  public void dispose() {
    if (meterTimer != null) {
      meterTimer.stop();
    }
    captureLine.stop();
    super.dispose();
  }
}
