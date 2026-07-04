package org.deluge.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JButton;

/** Extracted MacroSliderButton custom component from SwingGridPanel to offload complexity. */
public class MacroSliderButton extends JButton {
  private final SwingGridPanel parent;
  private final int colId;
  private final String paramName;
  private boolean isSliding = false;
  private double value = 0.5;
  private String displayValueStr = "";

  public MacroSliderButton(SwingGridPanel parent, int colId, String paramName) {
    this.parent = parent;
    this.colId = colId;
    this.paramName = paramName;

    setContentAreaFilled(false);
    setBorderPainted(false);
    setFocusPainted(false);
    setOpaque(false);
    setMargin(new java.awt.Insets(0, 0, 0, 0));

    updateValueFromModel();
    if (colId >= 0 && colId < SwingGridPanel.MACRO_TOOLTIPS.length) {
      setToolTipText(SwingGridPanel.MACRO_TOOLTIPS[colId]);
    }

    MouseAdapter adapter =
        new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent e) {
            handleDrag(e);
          }

          @Override
          public void mouseReleased(MouseEvent e) {
            isSliding = false;
            repaint();
            propagateValueToModel();
            parent.fireProjectChanged();
            parent.refresh();
          }

          @Override
          public void mouseDragged(MouseEvent e) {
            handleDrag(e);
          }

          private void handleDrag(MouseEvent e) {
            double v = 1.0 - (double) e.getY() / getHeight();
            v = Math.max(0.0, Math.min(1.0, v));
            value = v;
            isSliding = true;

            propagateValueToModel();
            updateDisplayValueStr();
            repaint();

            if (SwingDelugeApp.mainInstance != null) {
              SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient(
                  paramName.substring(0, Math.min(4, paramName.length())).toUpperCase(),
                  displayValueStr);
            }
          }
        };

    addMouseListener(adapter);
    addMouseMotionListener(adapter);
  }

  public void updateValueFromModel() {
    if (parent.projectModel == null
        || parent.editedModelTrack >= parent.projectModel.getTracks().size()) return;
    org.deluge.model.TrackModel track =
        parent.projectModel.getTracks().get(parent.editedModelTrack);
    value = parent.getMacroValue(colId, track);
    updateDisplayValueStr();
  }

  private void updateDisplayValueStr() {
    switch (colId) {
      case 0:
        displayValueStr = (int) (value * 100) + "%";
        break;
      case 1:
        int panPct = (int) ((value - 0.5) * 200);
        displayValueStr = panPct == 0 ? "C" : (panPct < 0 ? "L" + Math.abs(panPct) : "R" + panPct);
        break;
      case 2:
        int semi = (int) (value * 48 - 24);
        displayValueStr = (semi >= 0 ? "+" : "") + semi;
        break;
      case 3:
        float freq = (float) (20.0 * Math.pow(1000.0, value));
        displayValueStr =
            freq >= 1000.0f ? String.format("%.1fk", freq / 1000.0f) : String.format("%.0f", freq);
        break;
      case 4:
        displayValueStr = (int) (value * 100) + "%";
        break;
      case 5:
        displayValueStr = (int) (value * 100) + "%";
        break;
      case 6:
        displayValueStr = (int) (value * 100) + "%";
        break;
      case 7:
        displayValueStr = String.format("%.1fH", value * 20.0f);
        break;
      case 8:
        displayValueStr = (int) (value * 100) + "%";
        break;
      case 9:
        displayValueStr = (int) (value * 100) + "%";
        break;
      case 10:
        displayValueStr = (int) (value * 100) + "%";
        break;
      case 11:
        displayValueStr = (int) (value * 100) + "%";
        break;
      case 12:
        displayValueStr = (int) (value * 100) + "%";
        break;
      case 13:
        displayValueStr = (int) (value * 100) + "%";
        break;
      case 14:
        displayValueStr = (int) (value * 100) + "%";
        break;
      case 15:
        displayValueStr = "SMPL";
        break;
      default:
        displayValueStr = String.format("%.2f", value);
    }
  }

  private void propagateValueToModel() {
    if (parent.projectModel == null
        || parent.editedModelTrack >= parent.projectModel.getTracks().size()) return;
    org.deluge.model.TrackModel track =
        parent.projectModel.getTracks().get(parent.editedModelTrack);
    parent.setMacroValue(colId, value, track);
  }

  @Override
  protected void paintComponent(Graphics g) {
    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

    int w = getWidth();
    int h = getHeight();
    int xPad = 2;
    int yPad = 2;
    int rw = w - 2 * xPad;
    int rh = h - 2 * yPad;
    int arc = 6;

    g2.setColor(new Color(0x11, 0x11, 0x13));
    g2.fillRoundRect(xPad, yPad, rw, rh, arc, arc);

    // Draw slot shadow
    g2.setColor(new Color(0, 0, 0, 100));
    g2.drawRoundRect(xPad, yPad, rw, rh, arc, arc);

    int barH = (int) (value * rh);
    if (barH > 0) {
      Color c1 = ThemeManager.getPrimaryAccent();
      Color c2 = ThemeManager.getSecondaryAccent();

      Color alphaC1 = new Color(c1.getRed(), c1.getGreen(), c1.getBlue(), 95);
      Color alphaC2 = new Color(c2.getRed(), c2.getGreen(), c2.getBlue(), 160);

      GradientPaint grad =
          new GradientPaint(w / 2.0f, h - yPad, alphaC1, w / 2.0f, h - yPad - barH, alphaC2);
      g2.setPaint(grad);
      g2.fillRoundRect(xPad, h - yPad - barH, rw, barH, arc, arc);

      // Draw bright center line (LED strip hotspot)
      g2.setColor(new Color(255, 255, 255, 180));
      g2.setStroke(new BasicStroke(1.2f));
      g2.drawLine(w / 2, h - yPad, w / 2, h - yPad - barH);

      // Draw top bar cap line
      g2.setColor(c2);
      g2.setStroke(new BasicStroke(1.5f));
      g2.drawLine(xPad + 2, h - yPad - barH, w - xPad - 2, h - yPad - barH);
    }

    Color borderCol = isSliding ? ThemeManager.getSecondaryAccent() : new Color(0x2d, 0x2d, 0x35);
    g2.setColor(borderCol);
    g2.setStroke(new BasicStroke(isSliding ? 1.5f : 1.0f));
    g2.drawRoundRect(xPad, yPad, rw, rh, arc, arc);

    g2.setFont(new Font("SansSerif", Font.BOLD, w > 65 ? 10 : 8));
    FontMetrics fm = g2.getFontMetrics();
    String activeText = isSliding ? displayValueStr : paramName;
    int tx = (w - fm.stringWidth(activeText)) / 2;
    int ty = (h + fm.getAscent() - fm.getLeading()) / 2 - 1;

    g2.setColor(new Color(0, 0, 0, 200));
    g2.drawString(activeText, tx + 1, ty + 1);

    g2.setColor(isSliding ? Color.WHITE : new Color(0xe2, 0xe2, 0xe8));
    g2.drawString(activeText, tx, ty);

    g2.dispose();
  }
}
