package org.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.chuck.core.ChuckVM;
import org.deluge.BridgeContract;

/** Painted step parameter bar lane. */
public class SwingVelocityLanePanel extends JPanel {
  private final ChuckVM vm;
  private final BridgeContract bridge;
  private int stepCount = 16;

  public SwingVelocityLanePanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;

    setPreferredSize(new Dimension(0, 120));
    setBackground(new Color(0x1a, 0x1a, 0x1a));

    java.awt.event.MouseAdapter mouseAdapter =
        new java.awt.event.MouseAdapter() {
          @Override
          public void mousePressed(java.awt.event.MouseEvent e) {
            handlePaint(e);
          }

          @Override
          public void mouseDragged(java.awt.event.MouseEvent e) {
            handlePaint(e);
          }
        };
    addMouseListener(mouseAdapter);
    addMouseMotionListener(mouseAdapter);
  }

  private int startStep = -1;
  private double startVal = 0.0;

  private void handlePaint(java.awt.event.MouseEvent e) {
    boolean isHd =
        Boolean.parseBoolean(org.deluge.project.PreferencesManager.get("hd.optimization", "false"));
    int padSz = isHd ? 70 : 120;

    int h = getHeight();
    int step = (e.getX() - 172) / padSz;

    if (step >= 0 && step < stepCount) {
      double val = 1.0 - (double) e.getY() / h;
      val = Math.max(0.0, Math.min(1.0, val));

      if (e.getID() == java.awt.event.MouseEvent.MOUSE_PRESSED) {
        startStep = step;
        startVal = val;
        updateValue(step, val);
      } else if (e.getID() == java.awt.event.MouseEvent.MOUSE_DRAGGED && startStep >= 0) {
        if (e.isShiftDown()) {
          int min = Math.min(startStep, step);
          int max = Math.max(startStep, step);
          double v1 = (min == startStep) ? startVal : val;
          double v2 = (min == startStep) ? val : startVal;
          for (int s = min; s <= max; s++) {
            double t = (max == min) ? 0.0 : (double) (s - min) / (max - min);
            updateValue(s, v1 + t * (v2 - v1));
          }
        } else {
          updateValue(step, val);
        }
      }
      repaint();
    }
  }

  private void updateValue(int step, double val) {
    if (bridge == null || vm == null) return;
    if ("VELOCITY".equals(currentMode)) {
      bridge.setVelocity(0, step, val);
    } else if ("PAN".equals(currentMode)) {
      Object obj = vm.getGlobalObject(BridgeContract.G_STEP_PAN);
      if (obj instanceof org.chuck.core.ChuckArray arr)
        arr.setFloat(step, (float) (val * 2.0 - 1.0));
    } else if ("FILTER".equals(currentMode)) {
      Object obj = vm.getGlobalObject(BridgeContract.G_STEP_FILTER);
      if (obj instanceof org.chuck.core.ChuckArray arr) arr.setFloat(step, (float) val);
    } else if ("RESONANCE".equals(currentMode)) {
      Object obj = vm.getGlobalObject(BridgeContract.G_STEP_RES);
      if (obj instanceof org.chuck.core.ChuckArray arr) arr.setFloat(step, (float) val);
    }
  }

  private String currentMode = "VELOCITY";

  public void setMode(String mode) {
    this.currentMode = mode;
    repaint();
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g;
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int w = getWidth();
    int h = getHeight();

    if ("VELOCITY".equals(currentMode)) {
      g2.setColor(new Color(0x00, 0xff, 0xcc, 0xaa));
    } else if ("GATE".equals(currentMode)) {
      g2.setColor(new Color(0xff, 0x98, 0x00, 0xaa));
    } else {
      g2.setColor(new Color(0xe9, 0x1e, 0x63, 0xaa));
    }

    boolean isHd =
        Boolean.parseBoolean(org.deluge.project.PreferencesManager.get("hd.optimization", "false"));
    int padSz = isHd ? 70 : 120;

    for (int i = 0; i < stepCount; i++) {
      double val = (bridge != null) ? bridge.getVelocity(0, i) : 0.5;
      int barH = (int) (val * (h - 20));

      int barX = 172 + i * padSz;
      g2.fillRect(barX + (padSz - padSz / 2) / 2, h - barH - 10, padSz / 2, barH);
    }

    g2.setColor(Color.DARK_GRAY);
    g2.drawLine(0, 0, w, 0);
  }
}
