package org.chuck.deluge.ui.netbeans;

import java.awt.*;
import javax.swing.*;

/** NetBeans-compatible Editor Panel. */
public class NetBeansEditorPanel extends javax.swing.JPanel {
  private MainViewModel viewModel;

  public NetBeansEditorPanel() {
    initComponents();
  }

  public void setViewModel(MainViewModel viewModel) {
    this.viewModel = viewModel;
    if (viewModel != null) {
      viewModel.addPropertyChangeListener(
          evt -> {
            if ("focusedTrack".equals(evt.getPropertyName())) {
              updateSlidersForTrack((int) evt.getNewValue());
            }
          });

      if (viewModel.getBridge() != null) {
        lpfSlider.addChangeListener(
            e -> {
              viewModel
                  .getBridge()
                  .setFilterFreq(viewModel.getFocusedTrack(), lpfSlider.getValue() / 100.0);
            });
        attSlider.addChangeListener(
            e -> {
              viewModel
                  .getBridge()
                  .setEnv(viewModel.getFocusedTrack(), attSlider.getValue() / 100.0, 0.2, 0.8, 0.3);
            });
      }
    }
  }

  private void updateSlidersForTrack(int trackIdx) {
    // In a real scenario, read from bridge. Here we just reset or mock
    System.out.println("Editor: Focusing track " + trackIdx);
  }

  @SuppressWarnings("unchecked")
  // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
  private void initComponents() {

    saveBtn = new javax.swing.JButton();
    oscPanel = new javax.swing.JPanel();
    osc1Label = new javax.swing.JLabel();
    osc1VolSlider = new javax.swing.JSlider();
    filterPanel = new javax.swing.JPanel();
    lpfLabel = new javax.swing.JLabel();
    lpfSlider = new javax.swing.JSlider();
    envPanel = new javax.swing.JPanel();
    attLabel = new javax.swing.JLabel();
    attSlider = new javax.swing.JSlider();

    setBackground(new java.awt.Color(37, 37, 37));

    saveBtn.setBackground(new java.awt.Color(51, 102, 51));
    saveBtn.setForeground(new java.awt.Color(255, 255, 255));
    saveBtn.setText("💾 SAVE XML");

    oscPanel.setBackground(new java.awt.Color(51, 51, 51));
    oscPanel.setBorder(
        javax.swing.BorderFactory.createTitledBorder(
            null,
            "OSCILLATORS",
            javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
            javax.swing.border.TitledBorder.DEFAULT_POSITION,
            null,
            java.awt.Color.WHITE));

    osc1Label.setForeground(new java.awt.Color(255, 255, 255));
    osc1Label.setText("Osc 1 Vol");

    javax.swing.GroupLayout oscPanelLayout = new javax.swing.GroupLayout(oscPanel);
    oscPanel.setLayout(oscPanelLayout);
    oscPanelLayout.setHorizontalGroup(
        oscPanelLayout
            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(
                oscPanelLayout
                    .createSequentialGroup()
                    .addContainerGap()
                    .addGroup(
                        oscPanelLayout
                            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(
                                osc1VolSlider,
                                javax.swing.GroupLayout.DEFAULT_SIZE,
                                javax.swing.GroupLayout.DEFAULT_SIZE,
                                Short.MAX_VALUE)
                            .addGroup(
                                oscPanelLayout
                                    .createSequentialGroup()
                                    .addComponent(osc1Label)
                                    .addGap(0, 0, Short.MAX_VALUE)))
                    .addContainerGap()));
    oscPanelLayout.setVerticalGroup(
        oscPanelLayout
            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(
                oscPanelLayout
                    .createSequentialGroup()
                    .addContainerGap()
                    .addComponent(osc1Label)
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addComponent(
                        osc1VolSlider,
                        javax.swing.GroupLayout.PREFERRED_SIZE,
                        javax.swing.GroupLayout.DEFAULT_SIZE,
                        javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)));

    filterPanel.setBackground(new java.awt.Color(51, 51, 51));
    filterPanel.setBorder(
        javax.swing.BorderFactory.createTitledBorder(
            null,
            "FILTERS",
            javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
            javax.swing.border.TitledBorder.DEFAULT_POSITION,
            null,
            java.awt.Color.WHITE));

    lpfLabel.setForeground(new java.awt.Color(255, 255, 255));
    lpfLabel.setText("LPF Cutoff");

    javax.swing.GroupLayout filterPanelLayout = new javax.swing.GroupLayout(filterPanel);
    filterPanel.setLayout(filterPanelLayout);
    filterPanelLayout.setHorizontalGroup(
        filterPanelLayout
            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(
                filterPanelLayout
                    .createSequentialGroup()
                    .addContainerGap()
                    .addGroup(
                        filterPanelLayout
                            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(
                                lpfSlider,
                                javax.swing.GroupLayout.DEFAULT_SIZE,
                                javax.swing.GroupLayout.DEFAULT_SIZE,
                                Short.MAX_VALUE)
                            .addGroup(
                                filterPanelLayout
                                    .createSequentialGroup()
                                    .addComponent(lpfLabel)
                                    .addGap(0, 0, Short.MAX_VALUE)))
                    .addContainerGap()));
    filterPanelLayout.setVerticalGroup(
        filterPanelLayout
            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(
                filterPanelLayout
                    .createSequentialGroup()
                    .addContainerGap()
                    .addComponent(lpfLabel)
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addComponent(
                        lpfSlider,
                        javax.swing.GroupLayout.PREFERRED_SIZE,
                        javax.swing.GroupLayout.DEFAULT_SIZE,
                        javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)));

    envPanel.setBackground(new java.awt.Color(51, 51, 51));
    envPanel.setBorder(
        javax.swing.BorderFactory.createTitledBorder(
            null,
            "ENVELOPE",
            javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
            javax.swing.border.TitledBorder.DEFAULT_POSITION,
            null,
            java.awt.Color.WHITE));

    attLabel.setForeground(new java.awt.Color(255, 255, 255));
    attLabel.setText("Attack");

    javax.swing.GroupLayout envPanelLayout = new javax.swing.GroupLayout(envPanel);
    envPanel.setLayout(envPanelLayout);
    envPanelLayout.setHorizontalGroup(
        envPanelLayout
            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(
                envPanelLayout
                    .createSequentialGroup()
                    .addContainerGap()
                    .addGroup(
                        envPanelLayout
                            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(
                                attSlider,
                                javax.swing.GroupLayout.DEFAULT_SIZE,
                                javax.swing.GroupLayout.DEFAULT_SIZE,
                                Short.MAX_VALUE)
                            .addGroup(
                                envPanelLayout
                                    .createSequentialGroup()
                                    .addComponent(attLabel)
                                    .addGap(0, 0, Short.MAX_VALUE)))
                    .addContainerGap()));
    envPanelLayout.setVerticalGroup(
        envPanelLayout
            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(
                envPanelLayout
                    .createSequentialGroup()
                    .addContainerGap()
                    .addComponent(attLabel)
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addComponent(
                        attSlider,
                        javax.swing.GroupLayout.PREFERRED_SIZE,
                        javax.swing.GroupLayout.DEFAULT_SIZE,
                        javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)));

    javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
    this.setLayout(layout);
    layout.setHorizontalGroup(
        layout
            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(
                layout
                    .createSequentialGroup()
                    .addContainerGap()
                    .addGroup(
                        layout
                            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(
                                oscPanel,
                                javax.swing.GroupLayout.DEFAULT_SIZE,
                                javax.swing.GroupLayout.DEFAULT_SIZE,
                                Short.MAX_VALUE)
                            .addComponent(
                                saveBtn,
                                javax.swing.GroupLayout.DEFAULT_SIZE,
                                javax.swing.GroupLayout.DEFAULT_SIZE,
                                Short.MAX_VALUE)
                            .addComponent(
                                filterPanel,
                                javax.swing.GroupLayout.DEFAULT_SIZE,
                                javax.swing.GroupLayout.DEFAULT_SIZE,
                                Short.MAX_VALUE)
                            .addComponent(
                                envPanel,
                                javax.swing.GroupLayout.DEFAULT_SIZE,
                                javax.swing.GroupLayout.DEFAULT_SIZE,
                                Short.MAX_VALUE))
                    .addContainerGap()));
    layout.setVerticalGroup(
        layout
            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(
                layout
                    .createSequentialGroup()
                    .addContainerGap()
                    .addComponent(
                        saveBtn,
                        javax.swing.GroupLayout.PREFERRED_SIZE,
                        35,
                        javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addComponent(
                        oscPanel,
                        javax.swing.GroupLayout.PREFERRED_SIZE,
                        javax.swing.GroupLayout.DEFAULT_SIZE,
                        javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addComponent(
                        filterPanel,
                        javax.swing.GroupLayout.PREFERRED_SIZE,
                        javax.swing.GroupLayout.DEFAULT_SIZE,
                        javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addComponent(
                        envPanel,
                        javax.swing.GroupLayout.PREFERRED_SIZE,
                        javax.swing.GroupLayout.DEFAULT_SIZE,
                        javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addContainerGap(100, Short.MAX_VALUE)));
  } // </editor-fold>//GEN-END:initComponents

  // Variables declaration - do not modify//GEN-BEGIN:variables
  private javax.swing.JLabel attLabel;
  private javax.swing.JSlider attSlider;
  private javax.swing.JPanel envPanel;
  private javax.swing.JPanel filterPanel;
  private javax.swing.JLabel lpfLabel;
  private javax.swing.JSlider lpfSlider;
  private javax.swing.JLabel osc1Label;
  private javax.swing.JSlider osc1VolSlider;
  private javax.swing.JPanel oscPanel;
  private javax.swing.JButton saveBtn;
  // End of variables declaration//GEN-END:variables
}
