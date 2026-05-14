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
  private String lastMainText = "";
  private String lastPopupText = "";

  public void setListener(BiConsumer<String, String> listener) {
    this.listener = listener;
  }

  public void setText(String text) {
    this.lastMainText = text;
    notifyListener();
  }

  public void displayPopup(String text) {
    this.lastPopupText = text;
    notifyListener();
    // In a real Deluge, popups disappear after a few seconds.
    // We'll leave it to the UI or a timer later.
  }

  public void displayNotification(String title, String value) {
    this.lastPopupText = title + ": " + value;
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
}
