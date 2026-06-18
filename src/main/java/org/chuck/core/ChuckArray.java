package org.chuck.core;

/**
 * A lightweight shadow wrapper representing a ChucK array. Implemented in pure Java to completely
 * decouple the Deluge UI from the heavy native ChucK VM.
 */
public class ChuckArray {
  private final Object[] array;

  public String elementTypeName = "int";

  public ChuckArray(int size) {
    this.array = new Object[size];
  }

  public ChuckArray(int[] arr) {
    this.array = new Object[arr.length];
    for (int i = 0; i < arr.length; i++) {
      this.array[i] = arr[i];
    }
  }

  public ChuckArray(float[] arr) {
    this.array = new Object[arr.length];
    for (int i = 0; i < arr.length; i++) {
      this.array[i] = arr[i];
    }
  }

  public ChuckArray(long[] arr) {
    this.array = new Object[arr.length];
    for (int i = 0; i < arr.length; i++) {
      this.array[i] = arr[i];
    }
  }

  public ChuckArray(double[] arr) {
    this.array = new Object[arr.length];
    for (int i = 0; i < arr.length; i++) {
      this.array[i] = arr[i];
    }
  }

  public void setInt(int idx, long val) {
    array[idx] = val;
  }

  public void setFloat(int idx, double val) {
    array[idx] = val;
  }

  public void setObject(int idx, Object val) {
    array[idx] = val;
  }

  public long getInt(int idx) {
    if (idx >= 0 && idx < array.length) {
      Object o = array[idx];
      if (o instanceof Number n) {
        return n.longValue();
      }
    }
    return 0L;
  }

  public double getFloat(int idx) {
    if (idx >= 0 && idx < array.length) {
      Object o = array[idx];
      if (o instanceof Number n) {
        return n.doubleValue();
      }
    }
    return 0.0;
  }

  public int size() {
    return array.length;
  }
}
