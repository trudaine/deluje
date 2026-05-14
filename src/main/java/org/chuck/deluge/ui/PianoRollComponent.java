package org.chuck.deluge.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import javax.swing.JComponent;

/**
 * Draws a 28-key piano keyboard overlay for isomorphic pad visualization. Used inside the CLIP view
 * of {@link SwingGridPanel}.
 */
public class PianoRollComponent extends JComponent {

  public PianoRollComponent() {
    setPreferredSize(new Dimension(2600, 120));
    setMaximumSize(new Dimension(2600, 120));
  }

  @Override
  protected void paintComponent(Graphics g) {
    Graphics2D g2 = (Graphics2D) g;
    int gridX = 160;

    double totalWidth = 18 * 125.0 + 20.0;
    double keyW = totalWidth / 28.0;
    int keyH = 110;

    // White keys
    for (int i = 0; i < 28; i++) {
      int x = (int) (gridX + i * keyW);
      int nextX = (int) (gridX + (i + 1) * keyW);
      int kw = (nextX - x) - 2;

      g2.setColor(Color.WHITE);
      g2.fillRect(x, 0, kw, keyH);
      g2.setColor(Color.BLACK);
      g2.drawRect(x, 0, kw, keyH);
    }

    // Black keys
    int[] blackKeyOffsets = {
      0, 1, 3, 4, 5, 7, 8, 10, 11, 12, 14, 15, 17, 18, 19, 21, 22, 24, 25, 26
    };
    for (int offsetKey : blackKeyOffsets) {
      int x = (int) (gridX + offsetKey * keyW);
      int nextX = (int) (gridX + (offsetKey + 1) * keyW);
      int kw = nextX - x;
      int bx = x + kw - (int) (keyW / 3.0);

      g2.setColor(new Color(0x1a, 0x1a, 0x1a));
      g2.fillRect(bx, 0, (int) (keyW / 2.0), keyH / 2);
    }

    // QWERTY assistants
    g2.setFont(new Font("SansSerif", Font.BOLD, 14));
    String[] whiteQwerty = {"Z", "X", "C", "V", "B", "N", "M"};
    for (int i = 0; i < 7; i++) {
      int x = (int) (gridX + i * keyW);
      g2.setColor(Color.GRAY);
      g2.drawString(whiteQwerty[i], x + 10, keyH - 15);
    }

    String[] blackQwerty = {"S", "D", "", "G", "H", "J"};
    for (int i = 0; i < blackKeyOffsets.length; i++) {
      if (i < 6 && !blackQwerty[i].isEmpty()) {
        int offsetKey = blackKeyOffsets[i];
        int x = (int) (gridX + offsetKey * keyW);
        int nextX = (int) (gridX + (offsetKey + 1) * keyW);
        int kw = nextX - x;
        int bx = x + kw - (int) (keyW / 3.0);
        g2.setColor(Color.WHITE);
        g2.drawString(blackQwerty[i], bx + 2, (keyH / 2) - 5);
      }
    }
  }
}
