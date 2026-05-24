package org.chuck.deluge.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JComponent;

/**
 * Draws a 28-key piano keyboard overlay for isomorphic pad visualization. Used inside the CLIP view
 * of {@link SwingGridPanel}.
 */
public class PianoRollComponent extends JComponent {

  private final SwingGridPanel gridPanel;
  private int activePlayingNote = -1;

  public PianoRollComponent(SwingGridPanel gridPanel) {
    this.gridPanel = gridPanel;
    // Compact size: height 80 instead of 120
    setPreferredSize(new Dimension(3000, 80));
    setMinimumSize(new Dimension(100, 80));
    setMaximumSize(new Dimension(3000, 80));

    addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mousePressed(java.awt.event.MouseEvent e) {
            int x = e.getX();
            int y = e.getY();

            // Compute the exact layout coordinates same as paintComponent
            int lw = Math.max(60, Math.min(140, gridPanel.getWidth() / 12));
            int gridX = lw + 91;
            int padSz = gridPanel.cachedPadSz;
            int cols = gridPanel.columnCount - 2;
            double totalWidth = cols * (padSz + 5) - 5;
            double keyW = totalWidth / 28.0;
            int keyH = 70;

            if (x < gridX || x > gridX + totalWidth || y < 0 || y > keyH) {
              return;
            }

            int midiNote = -1;

            // 1. Check black keys in top half
            if (y <= keyH / 2) {
              int[] blackKeyOffsets = {
                0, 1, 3, 4, 5, 7, 8, 10, 11, 12, 14, 15, 17, 18, 19, 21, 22, 24, 25, 26
              };
              int[] sharpValues = {1, 3, -1, 6, 8, 10, -1};

              for (int offsetKey : blackKeyOffsets) {
                int xStart = (int) (gridX + offsetKey * keyW);
                int nextX = (int) (gridX + (offsetKey + 1) * keyW);
                int kw = nextX - xStart;
                int bx = xStart + kw - (int) (keyW / 3.0);
                int bw = (int) (keyW / 2.0);

                if (x >= bx && x <= bx + bw) {
                  int oct = offsetKey / 7;
                  int noteInOct = offsetKey % 7;
                  int sharpVal = sharpValues[noteInOct];
                  if (sharpVal != -1) {
                    midiNote = 60 + oct * 12 + sharpVal;
                    break;
                  }
                }
              }
            }

            // 2. If not black key, it is a white key click
            if (midiNote == -1) {
              int i = (int) ((x - gridX) / keyW);
              if (i >= 0 && i < 28) {
                int oct = i / 7;
                int noteInOct = i % 7;
                int[] whiteNoteValues = {0, 2, 4, 5, 7, 9, 11};
                midiNote = 60 + oct * 12 + whiteNoteValues[noteInOct];
              }
            }

            // 3. Trigger note and flash grid UI
            if (midiNote >= 60 && midiNote < 128) {
              activePlayingNote = midiNote;
              gridPanel.triggerKeyboardNote(midiNote);
              gridPanel.flashIsomorphicNote(midiNote);
            }
          }

          @Override
          public void mouseReleased(java.awt.event.MouseEvent e) {
            if (activePlayingNote != -1) {
              gridPanel.triggerKeyboardNoteRelease(activePlayingNote);
              activePlayingNote = -1;
            }
          }
        });
  }

  @Override
  protected void paintComponent(Graphics g) {
    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    // Compute dynamic starting position to align with grid column pads
    int lw = Math.max(60, Math.min(140, gridPanel.getWidth() / 12));
    int gridX = lw + 91;

    // Compute dynamic total width spanning all step pads
    int padSz = gridPanel.cachedPadSz;
    int cols = gridPanel.columnCount - 2;
    double totalWidth = cols * (padSz + 5) - 5;
    double keyW = totalWidth / 28.0;
    int keyH = 70; // compact keyboard key height

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

    // 1. Draw base musical note pitch names and C-octave markers on white keys
    g2.setFont(new Font("SansSerif", Font.PLAIN, 8));
    String[] whiteNotes = {"C", "D", "E", "F", "G", "A", "B"};
    for (int i = 0; i < 28; i++) {
      int x = (int) (gridX + i * keyW);
      int nextX = (int) (gridX + (i + 1) * keyW);
      int kw = (nextX - x) - 2;

      // Draw subtle pitch letter at the top of the key
      g2.setColor(new Color(0x77, 0x77, 0x7a));
      g2.drawString(whiteNotes[i % 7], x + 4, 12);

      // Draw C-octave markers (C4, C5, C6, C7) at the bottom C-keys
      if (i % 7 == 0) {
        int octNumber = 4 + (i / 7);
        g2.setFont(new Font("SansSerif", Font.BOLD, 10));
        g2.setColor(new Color(0x00, 0xb2, 0xa0)); // Clean mint/teal
        g2.drawString("C" + octNumber, x + (kw / 2) - 6, keyH - 22);
        g2.setFont(new Font("SansSerif", Font.PLAIN, 8));
      }
    }

    // 2. QWERTY key assistants styled cleanly in dark gray at the bottom
    g2.setFont(new Font("SansSerif", Font.BOLD, 9));
    String[] whiteQwerty = {"Z", "X", "C", "V", "B", "N", "M"};
    for (int i = 0; i < 7; i++) {
      int x = (int) (gridX + i * keyW);
      g2.setColor(new Color(0x55, 0x55, 0x5a));
      g2.drawString(whiteQwerty[i], x + (int) (keyW / 2.0) - 3, keyH - 8);
    }

    String[] blackQwerty = {"S", "D", "", "G", "H", "J"};
    for (int i = 0; i < blackKeyOffsets.length; i++) {
      if (i < 6 && !blackQwerty[i].isEmpty()) {
        int offsetKey = blackKeyOffsets[i];
        int x = (int) (gridX + offsetKey * keyW);
        int nextX = (int) (gridX + (offsetKey + 1) * keyW);
        int kw = nextX - x;
        int bx = x + kw - (int) (keyW / 3.0);
        g2.setColor(new Color(0x55, 0x55, 0x5a));
        g2.drawString(blackQwerty[i], bx + (int) (keyW / 4.0) - 2, (keyH / 2) - 3);
      }
    }

    g2.dispose();
  }
}
