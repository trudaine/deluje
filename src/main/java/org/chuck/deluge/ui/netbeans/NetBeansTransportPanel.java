package org.chuck.deluge.ui.netbeans;

import java.awt.Color;
import javax.swing.*;

/** NetBeans-compatible Transport Panel bound to MainViewModel. */
public class NetBeansTransportPanel extends javax.swing.JPanel {
  private MainViewModel viewModel;

  public NetBeansTransportPanel() {
    initComponents();
    setOpaque(true);
    setBackground(new Color(0x22, 0x22, 0x22));

    // Explicitly set high-contrast colors for buttons to avoid "white on white"
    playBtn.setBackground(new Color(40, 90, 40));
    playBtn.setForeground(Color.WHITE);
    stopBtn.setBackground(new Color(90, 40, 40));
    stopBtn.setForeground(Color.WHITE);
    recBtn.setBackground(new Color(60, 60, 60));
    recBtn.setForeground(new Color(255, 100, 100));
  }

  public void setViewModel(MainViewModel viewModel) {
    this.viewModel = viewModel;
    if (viewModel != null) {
      viewModel.addPropertyChangeListener(
          evt -> {
            switch (evt.getPropertyName()) {
              case "playing" -> {
                boolean isPlaying = (boolean) evt.getNewValue();
                playBtn.setSelected(isPlaying);
                playBtn.setText(isPlaying ? "⏸ PAUSE" : "▶ PLAY");
              }
              case "recording" -> recBtn.setSelected((boolean) evt.getNewValue());
              case "bpm" -> bpmSlider.setValue((int) (float) evt.getNewValue());
              case "swing" -> swingSlider.setValue((int) ((float) evt.getNewValue() * 100));
            }
          });
    }
  }

  private void playBtnActionPerformed(java.awt.event.ActionEvent evt) {
    if (viewModel != null) viewModel.togglePlayback();
  }

  private void stopBtnActionPerformed(java.awt.event.ActionEvent evt) {
    if (viewModel != null) viewModel.setPlaying(false);
  }

  private void recBtnActionPerformed(java.awt.event.ActionEvent evt) {
    if (viewModel != null) viewModel.toggleRecording();
  }

  private void bpmSliderStateChanged(javax.swing.event.ChangeEvent evt) {
    if (viewModel != null && !bpmSlider.getValueIsAdjusting())
      viewModel.setBpm((float) bpmSlider.getValue());
  }

  private void swingSliderStateChanged(javax.swing.event.ChangeEvent evt) {
    if (viewModel != null && !swingSlider.getValueIsAdjusting())
      viewModel.setSwing(swingSlider.getValue() / 100.0f);
  }

  @SuppressWarnings("unchecked")
  // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
  private void initComponents() {

    playBtn = new javax.swing.JButton();
    stopBtn = new javax.swing.JButton();
    recBtn = new javax.swing.JToggleButton();
    bpmSlider = new javax.swing.JSlider();
    swingSlider = new javax.swing.JSlider();
    volSlider = new javax.swing.JSlider();
    bpmLabel = new javax.swing.JLabel();
    swingLabel = new javax.swing.JLabel();
    volLabel = new javax.swing.JLabel();

    setBackground(new java.awt.Color(34, 34, 34));

    playBtn.setText("▶ PLAY");
    playBtn.addActionListener(
        new java.awt.event.ActionListener() {
          public void actionPerformed(java.awt.event.ActionEvent evt) {
            playBtnActionPerformed(evt);
          }
        });

    stopBtn.setText("■ STOP");
    stopBtn.addActionListener(
        new java.awt.event.ActionListener() {
          public void actionPerformed(java.awt.event.ActionEvent evt) {
            stopBtnActionPerformed(evt);
          }
        });

    recBtn.setText("● REC");
    recBtn.addActionListener(
        new java.awt.event.ActionListener() {
          public void actionPerformed(java.awt.event.ActionEvent evt) {
            recBtnActionPerformed(evt);
          }
        });

    bpmSlider.setBackground(new java.awt.Color(34, 34, 34));
    bpmSlider.setMaximum(200);
    bpmSlider.setMinimum(60);
    bpmSlider.setValue(120);
    bpmSlider.addChangeListener(
        new javax.swing.event.ChangeListener() {
          public void stateChanged(javax.swing.event.ChangeEvent evt) {
            bpmSliderStateChanged(evt);
          }
        });

    swingSlider.setBackground(new java.awt.Color(34, 34, 34));
    swingSlider.addChangeListener(
        new javax.swing.event.ChangeListener() {
          public void stateChanged(javax.swing.event.ChangeEvent evt) {
            swingSliderStateChanged(evt);
          }
        });

    volSlider.setBackground(new java.awt.Color(34, 34, 34));

    bpmLabel.setForeground(new java.awt.Color(220, 220, 220));
    bpmLabel.setText("BPM");

    swingLabel.setForeground(new java.awt.Color(220, 220, 220));
    swingLabel.setText("SWING");

    volLabel.setForeground(new java.awt.Color(220, 220, 220));
    volLabel.setText("MASTER");

    javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
    this.setLayout(layout);
    layout.setHorizontalGroup(
        layout
            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(
                layout
                    .createSequentialGroup()
                    .addContainerGap()
                    .addComponent(
                        playBtn,
                        javax.swing.GroupLayout.PREFERRED_SIZE,
                        95,
                        javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addComponent(
                        stopBtn,
                        javax.swing.GroupLayout.PREFERRED_SIZE,
                        95,
                        javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addComponent(
                        recBtn,
                        javax.swing.GroupLayout.PREFERRED_SIZE,
                        95,
                        javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addGap(18, 18, 18)
                    .addGroup(
                        layout
                            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(
                                bpmSlider,
                                javax.swing.GroupLayout.PREFERRED_SIZE,
                                100,
                                javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(bpmLabel))
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addGroup(
                        layout
                            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(
                                swingSlider,
                                javax.swing.GroupLayout.PREFERRED_SIZE,
                                100,
                                javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(swingLabel))
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addGroup(
                        layout
                            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(
                                volSlider,
                                javax.swing.GroupLayout.PREFERRED_SIZE,
                                100,
                                javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(volLabel))
                    .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)));
    layout.setVerticalGroup(
        layout
            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(
                layout
                    .createSequentialGroup()
                    .addContainerGap()
                    .addGroup(
                        layout
                            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(
                                layout
                                    .createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                                    .addComponent(
                                        playBtn,
                                        javax.swing.GroupLayout.PREFERRED_SIZE,
                                        40,
                                        javax.swing.GroupLayout.PREFERRED_SIZE)
                                    .addComponent(
                                        stopBtn,
                                        javax.swing.GroupLayout.PREFERRED_SIZE,
                                        40,
                                        javax.swing.GroupLayout.PREFERRED_SIZE)
                                    .addComponent(
                                        recBtn,
                                        javax.swing.GroupLayout.PREFERRED_SIZE,
                                        40,
                                        javax.swing.GroupLayout.PREFERRED_SIZE))
                            .addGroup(
                                layout
                                    .createSequentialGroup()
                                    .addComponent(bpmLabel)
                                    .addPreferredGap(
                                        javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                    .addComponent(
                                        bpmSlider,
                                        javax.swing.GroupLayout.PREFERRED_SIZE,
                                        javax.swing.GroupLayout.DEFAULT_SIZE,
                                        javax.swing.GroupLayout.PREFERRED_SIZE))
                            .addGroup(
                                layout
                                    .createSequentialGroup()
                                    .addComponent(swingLabel)
                                    .addPreferredGap(
                                        javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                    .addComponent(
                                        swingSlider,
                                        javax.swing.GroupLayout.PREFERRED_SIZE,
                                        javax.swing.GroupLayout.DEFAULT_SIZE,
                                        javax.swing.GroupLayout.PREFERRED_SIZE))
                            .addGroup(
                                layout
                                    .createSequentialGroup()
                                    .addComponent(volLabel)
                                    .addPreferredGap(
                                        javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                    .addComponent(
                                        volSlider,
                                        javax.swing.GroupLayout.PREFERRED_SIZE,
                                        javax.swing.GroupLayout.DEFAULT_SIZE,
                                        javax.swing.GroupLayout.PREFERRED_SIZE)))
                    .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)));
  } // </editor-fold>//GEN-END:initComponents

  // Variables declaration - do not modify
  private javax.swing.JLabel bpmLabel;
  private javax.swing.JSlider bpmSlider;
  private javax.swing.JButton playBtn;
  private javax.swing.JToggleButton recBtn;
  private javax.swing.JButton stopBtn;
  private javax.swing.JLabel swingLabel;
  private javax.swing.JSlider swingSlider;
  private javax.swing.JLabel volLabel;
  private javax.swing.JSlider volSlider;
  // End of variables declaration
}
