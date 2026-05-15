package org.chuck.deluge.firmware.hid;

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
    // Draw the waveform to the virtual OLED
    virtualOLED.clear();
    if (data != null) {
        short[] shortData = new short[data.length];
        for (int i = 0; i < data.length; i++) shortData[i] = (short)(data[i] * 32767);
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
    virtualOLED.clear();
    virtualOLED.drawString(text, 10, 30);
    notifyOledListener();
    notifyListener();
  }

  public void displayPopup(String text) {
    this.lastPopupText = text;
    virtualOLED.drawRect(5, 5, 118, 54, true);
    virtualOLED.drawString(text, 10, 30);
    notifyOledListener();
    notifyListener();
  }

  public void displayNotification(String title, String value) {
    this.lastPopupText = title + ": " + value;
    virtualOLED.clear();
    virtualOLED.drawString(title, 5, 15);
    virtualOLED.drawString(value, 5, 45);
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
  
  private void notifyOledListener() {
      if (oledListener != null) {
          oledListener.run();
      }
  }
}
