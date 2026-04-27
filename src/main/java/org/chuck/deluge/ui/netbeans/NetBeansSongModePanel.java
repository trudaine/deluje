package org.chuck.deluge.ui.netbeans;

import java.awt.*;
import javax.swing.*;
import org.chuck.deluge.model.ProjectModel;

/** NetBeans-compatible Song Mode Panel (Dashboard, Dynamic Data). */
public class NetBeansSongModePanel extends javax.swing.JPanel {
  private MainViewModel viewModel;
  private final int ROWS = 64; // Standard Deluge tracks
  private final int COLS = 18;

  public NetBeansSongModePanel() {
    initComponents();
    setOpaque(true);
    setBackground(new Color(0x1a, 0x1a, 0x1a));
    setPreferredSize(new Dimension(900, ROWS * 40));
  }

  public void setViewModel(MainViewModel viewModel) {
    this.viewModel = viewModel;
    if (viewModel != null) {
      viewModel.addPropertyChangeListener(
          evt -> {
            String prop = evt.getPropertyName();
            if ("currentStep".equals(prop) || "projectModel".equals(prop)) {
              repaint();
            }
          });

      this.addMouseListener(
          new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
              handleMousePress(e);
            }
          });
    }
  }

  private void handleMousePress(java.awt.event.MouseEvent e) {
    if (viewModel == null || viewModel.getBridge() == null) return;

    int w = getWidth();
    int startX = 140;
    int padW = (w - startX) / COLS;
    int padH = 40;

    int c = (e.getX() - startX) / padW;
    int r = e.getY() / padH;

    if (r >= 0 && r < ROWS) {
      if (c >= 0 && c < 16) {
        // Launch Clip (mock logic)
        System.out.println("Launching Track " + r + " Clip " + c);
      } else if (c == 16) {
        // Mute toggle
        boolean isMuted = viewModel.getBridge().getMute(r);
        viewModel.getBridge().setMute(r, !isMuted);
        repaint();
      } else if (c == 17) {
        // EDIT button
        viewModel.setFocusedTrack(r);
        viewModel.setSelectedMode(0); // Jump to CLIP MODE
      }
    }
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    if (viewModel == null) return;

    Graphics2D g2 = (Graphics2D) g;
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int w = getWidth();
    int startX = 140;
    int padW = (w - startX) / COLS;
    int padH = 40;

    ProjectModel model = viewModel.getProjectModel();

    for (int r = 0; r < ROWS; r++) {
      // Draw Track Label
      g2.setColor(new Color(45, 45, 45));
      g2.fillRect(5, r * padH + 2, 130, padH - 4);

      g2.setColor(Color.LIGHT_GRAY);
      String name =
          (model != null && r < model.getTracks().size())
              ? model.getTracks().get(r).getName()
              : "EMPTY " + (r + 1);
      if (name.length() > 15) name = name.substring(0, 12) + "...";
      g2.setFont(new Font("SansSerif", Font.BOLD, 10));
      g2.drawString(name.toUpperCase(), 15, r * padH + padH / 2 + 5);

      for (int c = 0; c < COLS; c++) {
        int x = startX + c * padW + 2;
        int y = r * padH + 2;
        int pw = padW - 4;
        int ph = padH - 4;

        if (c < 16) {
          boolean hasClip =
              (model != null
                  && r < model.getTracks().size()
                  && !model.getTracks().get(r).getClips().isEmpty());
          // For now just show if track has ANY clips
          g2.setColor(hasClip ? new Color(0x00, 0xaa, 0x88) : new Color(35, 35, 35));
          g2.fillRoundRect(x, y, pw, ph, 8, 8);
          g2.setColor(Color.BLACK);
          g2.drawRoundRect(x, y, pw, ph, 8, 8);
        } else if (c == 16) {
          // Mute Column
          boolean isMuted = viewModel.getBridge() != null && viewModel.getBridge().getMute(r);
          g2.setColor(isMuted ? new Color(150, 40, 40) : new Color(40, 100, 40));
          g2.fillRoundRect(x, y, pw, ph, 8, 8);
          g2.setColor(Color.WHITE);
          g2.setFont(new Font("SansSerif", Font.BOLD, 10));
          g2.drawString("M", x + (pw / 2) - 4, y + (ph / 2) + 5);
        } else if (c == 17) {
          // Edit Column
          g2.setColor(new Color(60, 60, 90));
          g2.fillRoundRect(x, y, pw, ph, 8, 8);
          g2.setColor(Color.WHITE);
          g2.setFont(new Font("SansSerif", Font.BOLD, 8));
          g2.drawString("EDIT", x + 4, y + (ph / 2) + 4);
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
  private void initComponents() {

    javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
    this.setLayout(layout);
    layout.setHorizontalGroup(
        layout
            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 800, Short.MAX_VALUE));
    layout.setVerticalGroup(
        layout
            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 2560, Short.MAX_VALUE));
  } // </editor-fold>//GEN-END:initComponents
}
