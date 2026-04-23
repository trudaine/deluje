package org.chuck.deluge.ui;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/** Custom painted sequencer step grid panel using pure Java2D. */
public class SwingMatrixPanel extends JPanel {
  private final ChuckVM vm;
  private final BridgeContract bridge;

  private int currentStep = -1;
  private final int rows = 8;
  private final int cols = 16;

  public SwingMatrixPanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;

    setBackground(new Color(0x20, 0x20, 0x20));

    addMouseListener(
        new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent e) {
            handleMousePress(e);
          }
        });
  }

  public void setCurrentStep(int step) {
    this.currentStep = step;
    repaint();
  }

  private void handleMousePress(MouseEvent e) {
    int w = getWidth();
    int h = getHeight();
    int cellW = w / cols;
    int cellH = h / rows;

    int c = e.getX() / cellW;
    int r = e.getY() / cellH;

    if (c >= 0 && c < cols && r >= 0 && r < rows) {
      if (bridge != null) {
        boolean active = bridge.getStep(r, c);
        bridge.setStep(r, c, !active);
        repaint();
      }
    }
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g;
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int w = getWidth();
    int h = getHeight();
    int cellW = w / cols;
    int cellH = h / rows;

    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < cols; c++) {
        boolean active = bridge != null && bridge.getStep(r, c);

        int padX = c * cellW + 4;
        int padY = r * cellH + 4;
        int padW = cellW - 8;
        int padH = cellH - 8;

        if (active) {
          g2.setColor(new Color(0x00, 0xff, 0xcc, 0xee));
          g2.fillRoundRect(padX, padY, padW, padH, 10, 10);

          // Inner glow
          g2.setColor(Color.WHITE);
          g2.setStroke(new BasicStroke(2));
          g2.drawRoundRect(padX + 2, padY + 2, padW - 4, padH - 4, 8, 8);
        } else {
          g2.setColor(new Color(0x33, 0x33, 0x33));
          g2.fillRoundRect(padX, padY, padW, padH, 10, 10);
        }

        // Playhead highlight
        if (c == currentStep) {
          g2.setColor(Color.YELLOW);
          g2.setStroke(new BasicStroke(3));
          g2.drawRoundRect(padX - 1, padY - 1, padW + 2, padH + 2, 12, 12);
        }
      }
    }
  }
}
