package org.deluge.hid;

import java.util.function.BiConsumer;

/**
 * Port of the Deluge's Display class. Acts as a bridge between the bit-accurate firmware logic and
 * the Swing UI.
 */
public class FirmwareDisplay {
  private static final FirmwareDisplay INSTANCE = new FirmwareDisplay();

  public static FirmwareDisplay get() {
    return INSTANCE;
  }

  private BiConsumer<String, String> listener;
  private Runnable oledListener;
  private String lastMainText = "";
  private String lastPopupText = "";
  private float[] waveformPreview;

  private final VirtualOLED virtualOLED = new VirtualOLED();

  public void setWaveformPreview(float[] data) {
    this.waveformPreview = data;
    virtualOLED.stopScrollTimer();
    // Draw the waveform to the virtual OLED
    virtualOLED.clear();
    if (data != null) {
      short[] shortData = new short[data.length];
      for (int i = 0; i < data.length; i++) shortData[i] = (short) (data[i] * 32767);
      virtualOLED.drawWaveform(shortData, 0, shortData.length);
    }
    notifyOledListener();
    notifyListener();
  }

  public VirtualOLED getVirtualOLED() {
    return virtualOLED;
  }

  public float[] getWaveformPreview() {
    return waveformPreview;
  }

  public void setListener(BiConsumer<String, String> listener) {
    this.listener = listener;
  }

  public void setOledListener(Runnable listener) {
    this.oledListener = listener;
  }

  public void setText(String text) {
    this.lastMainText = text;
    virtualOLED.stopScrollTimer();
    virtualOLED.clear();
    virtualOLED.setLargeFont(true);
    virtualOLED.drawString(text != null ? text : "", 10, 38);
    notifyOledListener();
    notifyListener();
  }

  public void displayPopup(String text) {
    this.lastPopupText = text;
    virtualOLED.stopScrollTimer();
    virtualOLED.setLargeFont(true);
    // Clear popup interior to black and draw white outline so white popup text is visible.
    virtualOLED.drawRect(5, 5, 118, 54, true, false);
    virtualOLED.drawRect(5, 5, 118, 54, false, true);
    virtualOLED.drawString(text != null ? text : "", 10, 38);
    notifyOledListener();
    notifyListener();
  }

  public void displayNotification(String title, String value) {
    this.lastPopupText = title + ": " + value;
    virtualOLED.stopScrollTimer();
    virtualOLED.clear();
    virtualOLED.setLargeFont(true);
    virtualOLED.drawString(title != null ? title : "", 5, 22);
    virtualOLED.drawString(value != null ? value : "", 5, 52);
    notifyOledListener();
    notifyListener();
  }

  public void displayContextMenu(String title, String[] options, int selectedIdx) {
    virtualOLED.stopScrollTimer();
    virtualOLED.clear();
    virtualOLED.setLargeFont(false); // Use the crisp smaller list font to prevent line overlapping!
    virtualOLED.drawRect(0, 0, 128, 14, true); // White header bar
    virtualOLED.drawString(title != null ? title : "", 5, 11, false); // Black text on white header

    if (options != null && options.length > 0) {
      for (int i = 0; i < Math.min(3, options.length); i++) {
        int idx = Math.floorMod(selectedIdx + i, options.length);
        int y = 28 + (i * 12);
        String opt = options[idx] != null ? options[idx] : "";
        virtualOLED.drawString(opt, 10, y);
        if (i == 0) virtualOLED.drawString(">", 2, y); // Selection cursor
      }
    }
    notifyOledListener();
    notifyListener();
  }

  public String getMainText() {
    return lastMainText;
  }

  public String getPopupText() {
    return lastPopupText;
  }

  private void notifyListener() {
    if (listener != null) {
      listener.accept(lastMainText, lastPopupText);
    }
  }

  public void notifyOledListener() {
    if (oledListener != null) {
      oledListener.run();
    }
  }
}
