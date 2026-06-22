package org.deluge.hid;

import java.util.Stack;
import org.deluge.hid.pic.GridConfig;

/** Virtual button matrix driver. Ports the logic from matrix_driver.h. */
public class MatrixDriver {
  /** Width of main pad area — delegates to {@link GridConfig}. */
  public static int kDisplayWidth() {
    return GridConfig.getDisplayWidth();
  }

  /** Height of main pad area — delegates to {@link GridConfig}. */
  public static int kDisplayHeight() {
    return GridConfig.getDisplayHeight();
  }

  public static final int kSideBarWidth = GridConfig.kSideBarWidth;

  private static final MatrixDriver INSTANCE = new MatrixDriver();

  public static MatrixDriver get() {
    return INSTANCE;
  }

  // Allocate for maximum possible grid; resizing with final arrays is safe
  // since GridConfig dimensions only grow from 16×8 to at most 24×16.
  private static final int MAX_WIDTH = 24;
  private static final int MAX_HEIGHT = 16;
  private final boolean[][] padStates = new boolean[MAX_WIDTH + kSideBarWidth][MAX_HEIGHT];
  private final Stack<FirmwareUI> uiStack = new Stack<>();

  public void pushUI(FirmwareUI ui) {
    uiStack.push(ui);
  }

  public void popUI() {
    if (!uiStack.isEmpty()) uiStack.pop();
  }

  public FirmwareUI getCurrentUI() {
    return uiStack.isEmpty() ? null : uiStack.peek();
  }

  public enum VelocityCurve {
    LINEAR,
    LOG,
    EXP
  }

  private VelocityCurve velocityCurve = VelocityCurve.LINEAR;

  public void setVelocityCurve(VelocityCurve curve) {
    this.velocityCurve = curve;
  }

  private int applyCurve(int velocity) {
    switch (velocityCurve) {
      case LOG:
        return (int) (127 * Math.log1p(velocity / 127.0 * (Math.E - 1)));
      case EXP:
        return (int) (127 * (Math.pow(Math.E, velocity / 127.0) - 1) / (Math.E - 1));
      default:
        return velocity;
    }
  }

  public void padAction(int x, int y, int velocity) {
    if (x >= 0 && x < GridConfig.getTotalWidth() && y >= 0 && y < GridConfig.getTotalHeight()) {
      int curvedVelocity = applyCurve(velocity);
      padStates[x][y] = (curvedVelocity != 0);

      FirmwareUI current = getCurrentUI();
      if (current != null) {
        current.padAction(x, y, curvedVelocity);
      }
    }
  }

  public void buttonAction(Button b, boolean on) {
    FirmwareUI current = getCurrentUI();
    if (current != null) {
      current.buttonAction(b, on);
    }
  }

  public void horizontalEncoderAction(int offset) {
    FirmwareUI current = getCurrentUI();
    if (current != null) {
      current.horizontalEncoderAction(offset);
    }
  }

  public void verticalEncoderAction(int offset) {
    FirmwareUI current = getCurrentUI();
    if (current != null) {
      current.verticalEncoderAction(offset);
    }
  }

  public void selectEncoderAction(int offset) {
    FirmwareUI current = getCurrentUI();
    if (current != null) {
      current.selectEncoderAction(offset);
    }
  }

  public void selectButtonAction(boolean on) {
    FirmwareUI current = getCurrentUI();
    if (current != null) {
      current.selectButtonPress(on);
    }
  }

  public void horizontalButtonAction(boolean on) {
    FirmwareUI current = getCurrentUI();
    if (current != null) {
      current.buttonAction(Button.SELECT, on);
    }
  }

  public void verticalButtonAction(boolean on) {
    FirmwareUI current = getCurrentUI();
    if (current != null) {
      current.buttonAction(Button.SELECT, on);
    }
  }

  public boolean isPadPressed(int x, int y) {
    if (x >= 0 && x < GridConfig.getTotalWidth() && y >= 0 && y < GridConfig.getTotalHeight()) {
      return padStates[x][y];
    }
    return false;
  }
}
