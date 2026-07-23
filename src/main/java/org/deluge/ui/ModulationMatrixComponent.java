package org.deluge.ui;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.*;
import org.deluge.model.PatchCable;
import org.deluge.model.SynthTrackModel;

/**
 * A custom-drawn, high-fidelity interactive Modulation Matrix Grid. Displays all modulation sources
 * (columns) vs destinations (rows). Supports: - Hover cell highlighting with description tips. -
 * Single-click and drag up/down to increase/decrease modulation amount. - Double-click to delete an
 * active patch cable.
 */
public class ModulationMatrixComponent extends JComponent {

  private static final String[] SOURCES = {
    "velocity",
    "envelope1",
    "envelope2",
    "envelope3",
    "envelope4",
    "lfo1",
    "lfo2",
    "lfo3",
    "lfo4",
    "note",
    "random",
    "sidechain"
  };

  private static final String[] SRC_LABELS = {
    "VEL", "ENV1", "ENV2", "ENV3", "ENV4", "LFO1", "LFO2", "LFO3", "LFO4", "NOTE", "RAND", "SDCHN"
  };

  private static final String[] DESTINATIONS = {
    "volume", "pan", "lpfFrequency", "lpfResonance", "hpfFrequency", "hpfResonance",
    "oscAVolume", "oscBVolume", "pitch", "noiseVolume", "modFxRate", "modFxDepth",
    "modFxFeedback", "modFxOffset", "lfo1Rate", "lfo2Rate", "envelope1ADSR", "envelope2ADSR",
    "wavetablePosition"
  };

  private static final String[] DST_LABELS = {
    "Volume", "Pan", "LPF Cutoff", "LPF Res", "HPF Cutoff", "HPF Res",
    "Osc A Vol", "Osc B Vol", "Pitch", "Noise Vol", "Mod FX Rate", "Mod FX Depth",
    "Mod FX Fdbk", "Mod FX Offs", "LFO1 Rate", "LFO2 Rate", "Env1 ADSR", "Env2 ADSR",
    "Wavetable Idx"
  };

  private final SynthTrackModel model;
  private final java.lang.Runnable onMatrixChanged;

  // Layout metrics
  private final int headerWidth = 90;
  // Tall enough for the diagonal column labels ("SDCHN" etc.) — horizontal labels at 22px
  // columns overlapped into an unreadable run-on.
  private final int headerHeight = 48;
  private final int cellSize = 22;

  // Interaction state
  private int hoverCol = -1;
  private int hoverRow = -1;
  private int dragCol = -1;
  private int dragRow = -1;
  private Point dragStartPoint = null;
  private float dragStartAmount = 0.0f;
  private PatchCable activeDragCable = null;

  public ModulationMatrixComponent(SynthTrackModel model, java.lang.Runnable onMatrixChanged) {
    this.model = model;
    this.onMatrixChanged = onMatrixChanged;

    int totalW = headerWidth + SOURCES.length * cellSize + 20;
    int totalH = headerHeight + DESTINATIONS.length * cellSize + 10;
    setPreferredSize(new Dimension(totalW, totalH));
    setMinimumSize(new Dimension(totalW, totalH));

    MouseAdapter mouseHandler =
        new MouseAdapter() {
          @Override
          public void mouseMoved(MouseEvent e) {
            updateHoverCell(e.getPoint());
          }

          @Override
          public void mouseExited(MouseEvent e) {
            hoverCol = -1;
            hoverRow = -1;
            repaint();
            SwingSynthConfigDialog.resetGlobalHelp();
          }

          @Override
          public void mousePressed(MouseEvent e) {
            int col = getColAt(e.getX());
            int row = getRowAt(e.getY());
            if (col < 0 || row < 0) return;

            PatchCable cable = findCable(col, row);

            if (e.getClickCount() == 2) {
              // Double click to delete
              if (cable != null) {
                model.getModulation().getPatchCables().remove(cable);
                triggerChanged();
              }
              return;
            }

            // Start drag to adjust depth
            if (cable == null) {
              // Create default cable on click
              cable =
                  new PatchCable(
                      SOURCES[col], DESTINATIONS[row], 0.0f, PatchCable.Polarity.BIPOLAR);
              model.getModulation().addPatchCable(cable);
              triggerChanged();
            }

            dragCol = col;
            dragRow = row;
            dragStartPoint = e.getPoint();
            dragStartAmount = cable.amount();
            activeDragCable = cable;
          }

          @Override
          public void mouseDragged(MouseEvent e) {
            if (activeDragCable == null || dragStartPoint == null) return;

            // Calculate vertical delta (drag up increases, down decreases)
            int dy = dragStartPoint.y - e.getY();
            float amountDelta = dy / 100.0f; // 100 pixels for full 100% range
            float newAmount = dragStartAmount + amountDelta;
            newAmount = Math.max(-1.0f, Math.min(1.0f, newAmount));

            // Update patch cable amount in model
            int index = model.getModulation().getPatchCables().indexOf(activeDragCable);
            if (index >= 0) {
              PatchCable updated =
                  new PatchCable(
                      activeDragCable.source(),
                      activeDragCable.destination(),
                      newAmount,
                      activeDragCable.polarity());
              model.getModulation().getPatchCables().set(index, updated);
              activeDragCable = updated;
              triggerChanged();
            }
          }

          @Override
          public void mouseReleased(MouseEvent e) {
            dragCol = -1;
            dragRow = -1;
            dragStartPoint = null;
            activeDragCable = null;
            updateHoverCell(e.getPoint());
          }
        };

    addMouseListener(mouseHandler);
    addMouseMotionListener(mouseHandler);
  }

  private void updateHoverCell(Point p) {
    int col = getColAt(p.x);
    int row = getRowAt(p.y);

    if (col != hoverCol || row != hoverRow) {
      hoverCol = col;
      hoverRow = row;
      repaint();

      if (col >= 0 && row >= 0) {
        PatchCable c = findCable(col, row);
        String status;
        if (c != null) {
          status =
              String.format(
                  "<html>💡 <b>MODULATION ACTIVE:</b> %s modulates <b>%s</b> at <b>%+d%%</b> depth. <i>(Drag vertically to adjust, Double-click to disconnect)</i></html>",
                  SRC_LABELS[col], DST_LABELS[row], (int) (c.amount() * 100));
        } else {
          status =
              String.format(
                  "<html>💡 <b>MODULATION MATRIX:</b> Click cell to connect <b>%s</b> modulation to <b>%s</b>.</html>",
                  SRC_LABELS[col], DST_LABELS[row]);
        }
        SwingSynthConfigDialog.updateGlobalHelp(status);
      } else {
        SwingSynthConfigDialog.resetGlobalHelp();
      }
    }
  }

  private int getColAt(int x) {
    int gridX = x - headerWidth;
    if (gridX < 0) return -1;
    int col = gridX / cellSize;
    return (col >= 0 && col < SOURCES.length) ? col : -1;
  }

  private int getRowAt(int y) {
    int gridY = y - headerHeight;
    if (gridY < 0) return -1;
    int row = gridY / cellSize;
    return (row >= 0 && row < DESTINATIONS.length) ? row : -1;
  }

  private PatchCable findCable(int col, int row) {
    String src = SOURCES[col];
    String dest = DESTINATIONS[row];
    for (PatchCable c : model.getModulation().getPatchCables()) {
      if (src.equalsIgnoreCase(c.source()) && dest.equalsIgnoreCase(c.destination())) {
        return c;
      }
    }
    return null;
  }

  private void triggerChanged() {
    onMatrixChanged.run();
    repaint();
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g;
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g2.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

    int w = getWidth();
    int h = getHeight();

    // Dark sleek card background
    g2.setColor(new Color(0x1a, 0x1a, 0x1e));
    g2.fillRect(0, 0, w, h);

    // Draw Column Headers (Sources)
    g2.setFont(new Font("SansSerif", Font.BOLD, 9));
    for (int col = 0; col < SOURCES.length; col++) {
      int cellX = headerWidth + col * cellSize;

      // Draw highlighted column backing
      if (col == hoverCol) {
        g2.setColor(new Color(0x28, 0x28, 0x30));
        g2.fillRect(cellX, 0, cellSize, h);
        g2.setColor(ThemeManager.getPrimaryAccent());
      } else {
        g2.setColor(Color.LIGHT_GRAY);
      }

      String label = SRC_LABELS[col];

      // Draw the label diagonally: the columns are narrower than the label text, so horizontal
      // labels collide into an unreadable run-on. Rotate -45° around the column's base point.
      Graphics2D gRot = (Graphics2D) g2.create();
      int pivotX = cellX + cellSize / 2;
      int pivotY = headerHeight - 8;
      gRot.rotate(-Math.PI / 4, pivotX, pivotY);
      gRot.drawString(label, pivotX - 2, pivotY);
      gRot.dispose();
    }

    // Draw Row Headers and Cells
    for (int row = 0; row < DESTINATIONS.length; row++) {
      int cellY = headerHeight + row * cellSize;

      // Draw highlighted row backing
      if (row == hoverRow) {
        g2.setColor(new Color(0x28, 0x28, 0x30));
        g2.fillRect(0, cellY, w, cellSize);
        g2.setColor(ThemeManager.getPrimaryAccent());
      } else {
        g2.setColor(Color.LIGHT_GRAY);
      }

      // 1. Draw Row Label (Destination)
      g2.setFont(new Font("SansSerif", Font.BOLD, 10));
      String label = DST_LABELS[row];
      g2.drawString(label, 12, cellY + cellSize - 8);

      // 2. Draw Cells in this Row
      for (int col = 0; col < SOURCES.length; col++) {
        int cellX = headerWidth + col * cellSize;
        PatchCable cable = findCable(col, row);

        // Grid box bounds
        int boxPad = 2;
        int boxX = cellX + boxPad;
        int boxY = cellY + boxPad;
        int boxW = cellSize - 2 * boxPad;
        int boxH = cellSize - 2 * boxPad;

        if (cable != null) {
          // ACTIVE MODULATION: Glowing Neon Square
          float amt = cable.amount();

          // Color based on polarity: positive is Theme Primary, negative is Theme Secondary
          Color baseColor =
              (amt >= 0) ? ThemeManager.getPrimaryAccent() : ThemeManager.getSecondaryAccent();

          // Filled square with opacity scaled by modulation amount
          int alpha = (int) (40 + Math.abs(amt) * 160);
          g2.setColor(
              new Color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), alpha));
          g2.fillRoundRect(boxX, boxY, boxW, boxH, 4, 4);

          // Neon border outline (rounded rect)
          g2.setColor(baseColor);
          g2.setStroke(new BasicStroke(1.5f));
          g2.drawRoundRect(boxX, boxY, boxW, boxH, 4, 4);

          // Draw tiny value text inside the cell (e.g. "+4", "-9")
          g2.setFont(new Font("SansSerif", Font.BOLD, 8));
          g2.setColor(Color.WHITE);
          String amtStr = String.format("%+.0f", amt * 10); // scale to fit
          if (amt == 0.0f) amtStr = "0";
          FontMetrics cfm = g2.getFontMetrics();
          int tw = cfm.stringWidth(amtStr);
          g2.drawString(amtStr, boxX + (boxW - tw) / 2 + 1, boxY + boxH - 4);
        } else {
          // INACTIVE CELL: Subtle dark box with a center dot
          g2.setColor(new Color(0x28, 0x28, 0x2e));
          g2.drawRoundRect(boxX, boxY, boxW, boxH, 4, 4);

          g2.setColor(new Color(0x38, 0x38, 0x40));
          g2.fillRect(cellX + cellSize / 2 - 1, cellY + cellSize / 2 - 1, 2, 2);
        }

        // Highlight Cell Hover Border (theme secondary accent)
        if (col == hoverCol && row == hoverRow) {
          g2.setColor(ThemeManager.getSecondaryAccent());
          g2.setStroke(new BasicStroke(1.5f));
          g2.drawRoundRect(boxX - 1, boxY - 1, boxW + 2, boxH + 2, 4, 4);
        }
      }
    }

    // Reset stroke
    g2.setStroke(new BasicStroke(1.0f));

    // Outer grid division borders
    g2.setColor(new Color(0x2d, 0x2d, 0x32));
    g2.drawLine(headerWidth, 0, headerWidth, h);
    g2.drawLine(0, headerHeight, w, headerHeight);
  }
}
