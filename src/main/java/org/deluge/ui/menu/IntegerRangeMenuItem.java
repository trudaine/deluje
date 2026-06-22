package org.deluge.ui.menu;

import org.deluge.hid.FirmwareDisplay;

/** A menu item for selecting a value from an integer range. */
public class IntegerRangeMenuItem extends MenuItem {
  private int value;
  private final int min;
  private final int max;
  private final java.util.function.Consumer<Integer> callback;
  private java.util.function.Function<Integer, String> labelMapper;

  public IntegerRangeMenuItem(String name, int initial, int min, int max) {
    this(name, initial, min, max, null);
  }

  public IntegerRangeMenuItem(
      String name, int initial, int min, int max, java.util.function.Consumer<Integer> callback) {
    super(name);
    this.value = initial;
    this.min = min;
    this.max = max;
    this.callback = callback;
  }

  public IntegerRangeMenuItem(
      String name,
      int initial,
      int min,
      int max,
      java.util.function.Function<Integer, String> labelMapper,
      java.util.function.Consumer<Integer> callback) {
    super(name);
    this.value = initial;
    this.min = min;
    this.max = max;
    this.labelMapper = labelMapper;
    this.callback = callback;
  }

  @Override
  public void onFocus() {
    String valStr = (labelMapper != null) ? labelMapper.apply(value) : String.valueOf(value);
    FirmwareDisplay.get().setText(name + ": " + valStr);
  }

  @Override
  public void selectEncoderAction(int offset) {
    value = Math.max(min, Math.min(max, value + offset));
    if (callback != null) {
      callback.accept(value);
    }
    onFocus();
  }
}
