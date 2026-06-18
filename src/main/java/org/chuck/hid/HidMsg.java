package org.chuck.hid;

/**
 * A lightweight shadow representing a key/HID message. Implemented in pure Java to completely
 * decouple the Deluge UI from the heavy native ChucK VM.
 */
public class HidMsg {
  public static final int BUTTON_DOWN = 1;
  public static final int BUTTON_UP = 2;

  public String deviceType;
  public int type;
  public int which;
  public int key;
  public int ascii;
}
