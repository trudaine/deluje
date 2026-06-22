package org.deluge.playback;

public class Iterance {
  public byte divisor;
  public byte iteranceStep; // Using byte as a bitset of 8 bits

  public Iterance() {
    this.divisor = 1;
    this.iteranceStep = 1;
  }

  public Iterance(byte divisor, byte iteranceStep) {
    this.divisor = divisor;
    this.iteranceStep = iteranceStep;
  }

  public boolean passesCheck(int repeatCount) {
    if (divisor == 0) return false;
    int index = repeatCount % divisor;
    return (iteranceStep & (1 << index)) != 0;
  }

  public int toInt() {
    return ((divisor & 0xFF) << 8) | (iteranceStep & 0xFF);
  }

  public static Iterance fromInt(int value) {
    return new Iterance((byte) ((value >> 8) & 0xFF), (byte) (value & 0xFF));
  }
}
