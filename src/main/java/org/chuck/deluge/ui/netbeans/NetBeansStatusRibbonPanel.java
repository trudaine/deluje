package org.chuck.deluge.ui.netbeans;

/** NetBeans-compatible Status Ribbon Panel. */
public class NetBeansStatusRibbonPanel extends javax.swing.JPanel {
  private MainViewModel viewModel;

  public NetBeansStatusRibbonPanel() {
    initComponents();
  }

  public void setViewModel(MainViewModel viewModel) {
    this.viewModel = viewModel;
    if (viewModel != null) {
      viewModel.addPropertyChangeListener(
          evt -> {
            if ("currentStep".equals(evt.getPropertyName())) {
              updateDisplay((int) evt.getNewValue());
            }
          });
    }
  }

  private void updateDisplay(int currentStep) {
    if (currentStep >= 0) {
      int bar = (currentStep / 16) + 1;
      int beat = ((currentStep % 16) / 4) + 1;
      int sixteenth = (currentStep % 4) + 1;
      oledLabel.setText(String.format("%d.%d.%d", bar, beat, sixteenth));
    } else {
      oledLabel.setText("STOP");
    }
  }

  @SuppressWarnings("unchecked")
  // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
  private void initComponents() {

    oledPanel = new javax.swing.JPanel();
    oledLabel = new javax.swing.JLabel();
    shredLabel = new javax.swing.JLabel();

    setBackground(new java.awt.Color(26, 26, 26));

    oledPanel.setBackground(new java.awt.Color(0, 0, 0));
    oledPanel.setBorder(
        javax.swing.BorderFactory.createLineBorder(new java.awt.Color(85, 85, 85), 2));

    oledLabel.setFont(new java.awt.Font("Monospaced", 1, 24)); // NOI18N
    oledLabel.setForeground(new java.awt.Color(255, 51, 51));
    oledLabel.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
    oledLabel.setText("DELUGE");

    javax.swing.GroupLayout oledPanelLayout = new javax.swing.GroupLayout(oledPanel);
    oledPanel.setLayout(oledPanelLayout);
    oledPanelLayout.setHorizontalGroup(
        oledPanelLayout
            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(oledLabel, javax.swing.GroupLayout.DEFAULT_SIZE, 180, Short.MAX_VALUE));
    oledPanelLayout.setVerticalGroup(
        oledPanelLayout
            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(oledLabel, javax.swing.GroupLayout.DEFAULT_SIZE, 40, Short.MAX_VALUE));

    shredLabel.setFont(new java.awt.Font("Monospaced", 1, 14)); // NOI18N
    shredLabel.setForeground(new java.awt.Color(0, 255, 204));
    shredLabel.setText("SHREDS: 0");

    javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
    this.setLayout(layout);
    layout.setHorizontalGroup(
        layout
            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(
                layout
                    .createSequentialGroup()
                    .addContainerGap(100, Short.MAX_VALUE)
                    .addComponent(
                        oledPanel,
                        javax.swing.GroupLayout.PREFERRED_SIZE,
                        javax.swing.GroupLayout.DEFAULT_SIZE,
                        javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addGap(18, 18, 18)
                    .addComponent(shredLabel)
                    .addContainerGap(100, Short.MAX_VALUE)));
    layout.setVerticalGroup(
        layout
            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(
                layout
                    .createSequentialGroup()
                    .addContainerGap()
                    .addGroup(
                        layout
                            .createParallelGroup(javax.swing.GroupLayout.Alignment.CENTER)
                            .addComponent(
                                oledPanel,
                                javax.swing.GroupLayout.PREFERRED_SIZE,
                                javax.swing.GroupLayout.DEFAULT_SIZE,
                                javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(shredLabel))
                    .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)));
  } // </editor-fold>//GEN-END:initComponents

  // Variables declaration - do not modify//GEN-BEGIN:variables
  private javax.swing.JLabel oledLabel;
  private javax.swing.JPanel oledPanel;
  private javax.swing.JLabel shredLabel;
  // End of variables declaration//GEN-END:variables
}
