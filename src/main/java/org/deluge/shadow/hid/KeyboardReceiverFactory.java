package org.deluge.shadow.hid;

/**
 * A lightweight shadow factory creating keyboard hotkey receivers. Implemented in pure Java to
 * completely decouple the Deluge UI from the heavy native ChucK VM.
 */
public class KeyboardReceiverFactory {

  public static KeyboardReceiver create() {
    return new KeyboardReceiver() {
      @Override
      public void register(java.awt.Component comp) {
        // Stub
      }

      @Override
      public void unregister() {
        // Stub
      }
    };
  }
}
