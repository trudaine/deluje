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
    setPreferredSize(new Dimension(2000, 1200));

    setFocusable(true);
    addKeyListener(new java.awt.event.KeyAdapter() {
      @Override
      public void keyPressed(java.awt.event.KeyEvent e) {
        if (e.getKeyCode() == java.awt.event.KeyEvent.VK_P) {
          String hitsStr = JOptionPane.showInputDialog(SwingMatrixPanel.this, "Euclidean Hits (K):", "4");
          String stepsStr = JOptionPane.showInputDialog(SwingMatrixPanel.this, "Sequence Steps (N):", "16");
          if (hitsStr != null && stepsStr != null) {
            int K = Integer.parseInt(hitsStr);
            int N = Integer.parseInt(stepsStr);
            for (int i = 0; i < 16; i++) {
              boolean active = ((i * K) % N) < K;
              bridge.setStep(baseTrack, i, active);
            }
            repaint();
          }
        }
      }
    });


    addMouseListener(new java.awt.event.MouseAdapter() {
      @Override
      public void mousePressed(java.awt.event.MouseEvent e) {
        handleMousePress(e);
      }
    });
  }


  private int baseTrack = 0;

  public void setBaseTrack(int baseTrack) {
    this.baseTrack = baseTrack;
    repaint();
  }

  public void setCurrentStep(int step) {
    this.currentStep = step;
    repaint();
  }


  private void handleMousePress(MouseEvent e) {
    int cellW = 120;
    int cellH = 120;
    int gridX = 200; // Offset to the right for labels
    int gridY = 20; 

    int offset = (currentStep >= 0) ? (currentStep / 16) * 16 : 0;
    int c = (e.getX() - gridX) / cellW;
    int r = (e.getY() - gridY) / cellH;

    if (c >= 0 && c < cols && r >= 0 && r < rows) {
      if (bridge != null) {
        boolean active = bridge.getStep(baseTrack + r, offset + c);
        bridge.setStep(baseTrack + r, offset + c, !active);
        repaint();
      }
    } else if (e.getY() >= (gridY + rows * cellH + 10) && e.getY() <= (gridY + rows * cellH + 130)) {
      // Piano key click
      int keyX = e.getX() - gridX;
      int whiteKeyIndex = keyX / 68;
      if (whiteKeyIndex >= 0 && whiteKeyIndex < 28) {

         System.out.println("Piano note clicked: " + whiteKeyIndex);
         // Trigger note through bridge or vm
      }
    }
  }



  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g;
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int cellW = 120;
    int cellH = 120;
    int gridX = 200;
    int gridY = 20;

    for (int r = 0; r < rows; r++) {
      // Draw label
      g2.setColor(Color.LIGHT_GRAY);
      String labelStr = (vm != null) ? (String) vm.getGlobalObject("g_sample_" + r) : "";
      if (labelStr == null || labelStr.isEmpty()) {
        labelStr = "PAD " + (r + 1);
      } else {
        // Shorten path to filename
        int lastSlash = labelStr.lastIndexOf('/');
        if (lastSlash != -1) {
          labelStr = labelStr.substring(lastSlash + 1);
        }
      }
    int offset = (currentStep >= 0) ? (currentStep / 16) * 16 : 0;

      // ...
      g2.drawString(labelStr, 20, gridY + r * cellH + cellH / 2);

      for (int c = 0; c < cols; c++) {
        boolean active = bridge != null && bridge.getStep(baseTrack + r, offset + c);

        int padX = gridX + c * cellW + 4;
        int padY = gridY + r * cellH + 4;
        int padW = cellW - 8;
        int padH = cellH - 8;

        if (active) {
          g2.setColor(new Color(0x00, 0xff, 0xcc, 0xee));
          g2.fillRoundRect(padX, padY, padW, padH, 10, 10);
          g2.setColor(Color.WHITE);
          g2.setStroke(new BasicStroke(2));
          g2.drawRoundRect(padX + 2, padY + 2, padW - 4, padH - 4, 8, 8);
        } else {
          g2.setColor(new Color(0x33, 0x33, 0x33));
          g2.fillRoundRect(padX, padY, padW, padH, 10, 10);
        }

        // Playhead highlight
        if (c == (currentStep % 16)) {
          g2.setColor(Color.YELLOW);
          g2.setStroke(new BasicStroke(3));

          g2.drawRoundRect(padX - 1, padY - 1, padW + 2, padH + 2, 12, 12);
        }
    }
    }

    // Draw Piano Roll at the bottom

    int keyboardY = gridY + rows * cellH + 10;
    int keyH = 120;

    // 28 White keys (4 octaves) aligned with grid columns
    int keyW = 68; 
    for (int i = 0; i < 28; i++) {
      boolean activeKey = (bridge != null) && (currentStep >= 0) && bridge.getStep(baseTrack + (i % 8), currentStep % 16);
      
      g2.setColor(activeKey ? new Color(0x00, 0xff, 0xcc) : Color.WHITE);
      g2.fillRect(gridX + i * keyW, keyboardY, keyW - 2, keyH);
      g2.setColor(Color.BLACK);
      g2.drawRect(gridX + i * keyW, keyboardY, keyW - 2, keyH);
    }

    // Black keys
    int[] blackKeyOffsets = {
      0, 1, 3, 4, 5, 
      7, 8, 10, 11, 12, 
      14, 15, 17, 18, 19, 
      21, 22, 24, 25, 26
    };
    for (int offsetKey : blackKeyOffsets) {
      int bx = gridX + offsetKey * keyW + keyW - keyW / 3;
      g2.setColor(new Color(0x1a, 0x1a, 0x1a));
      g2.fillRect(bx, keyboardY, keyW / 2, keyH / 2);
    }
  }
}


