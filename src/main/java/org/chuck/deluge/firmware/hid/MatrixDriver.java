package org.chuck.deluge.firmware.hid;

import java.util.Stack;

/** Virtual button matrix driver. Ports the logic from matrix_driver.h. */
public class MatrixDriver {
  public static final int kDisplayWidth = 16;
  public static final int kDisplayHeight = 8;
  public static final int kSideBarWidth = 2;

  private static final MatrixDriver INSTANCE = new MatrixDriver();

  public static MatrixDriver get() {
    return INSTANCE;
  }

  private final boolean[][] padStates = new boolean[kDisplayWidth + kSideBarWidth][kDisplayHeight];
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
    if (x >= 0 && x < kDisplayWidth + kSideBarWidth && y >= 0 && y < kDisplayHeight) {
      int curvedVelocity = applyCurve(velocity);
      padStates[x][y] = (curvedVelocity != 0);

      FirmwareUI current = getCurrentUI();
      if (current != null) {
        current.padAction(x, y, curvedVelocity);
      }
    }
  }

  public void buttonAction(int buttonId, boolean on) {
    FirmwareUI current = getCurrentUI();
    if (current != null) {
      current.buttonAction(buttonId, on);
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

  public boolean isPadPressed(int x, int y) {
    if (x >= 0 && x < kDisplayWidth + kSideBarWidth && y >= 0 && y < kDisplayHeight) {
      return padStates[x][y];
    }
    return false;
  }
}
