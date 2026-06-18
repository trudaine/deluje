package org.chuck.hid;

/**
 * A lightweight shadow interface representing a keyboard hotkey receiver. Implemented in pure Java
 * to completely decouple the Deluge UI from the heavy native ChucK VM.
 */
public interface KeyboardReceiver {
  void register(java.awt.Component comp);

  void unregister();
}
