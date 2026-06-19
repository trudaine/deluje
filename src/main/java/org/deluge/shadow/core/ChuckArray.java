package org.deluge.shadow.core;

/**
 * A lightweight shadow wrapper representing a ChucK array. Wraps primitive arrays in-place to
 * provide a bi-directional, zero-copy live link between Deluge's primitive arrays and the shadow
 * VM.
 */
public class ChuckArray {
  private final Object rawArray;
  private final int size;
  private String elementTypeName = "int";

  public ChuckArray(int size) {
    this.rawArray = new Object[size];
    this.size = size;
  }

  public ChuckArray(int[] arr) {
    this.rawArray = arr;
    this.size = arr.length;
  }

  public ChuckArray(float[] arr) {
    this.rawArray = arr;
    this.size = arr.length;
  }

  public ChuckArray(long[] arr) {
    this.rawArray = arr;
    this.size = arr.length;
  }

  public ChuckArray(double[] arr) {
    this.rawArray = arr;
    this.size = arr.length;
  }

  public String getElementTypeName() {
    return elementTypeName;
  }

  public void setElementTypeName(String elementTypeName) {
    this.elementTypeName = elementTypeName;
  }

  public void setInt(int idx, long val) {
    if (idx >= 0 && idx < size) {
      if (rawArray instanceof int[] a) {
        a[idx] = (int) val;
      } else if (rawArray instanceof float[] a) {
        a[idx] = (float) val;
      } else if (rawArray instanceof double[] a) {
        a[idx] = (double) val;
      } else if (rawArray instanceof long[] a) {
        a[idx] = val;
      } else if (rawArray instanceof Object[] a) {
        a[idx] = val;
      }
    }
  }

  public void setFloat(int idx, double val) {
    if (idx >= 0 && idx < size) {
      if (rawArray instanceof int[] a) {
        a[idx] = (int) val;
      } else if (rawArray instanceof float[] a) {
        a[idx] = (float) val;
      } else if (rawArray instanceof double[] a) {
        a[idx] = val;
      } else if (rawArray instanceof long[] a) {
        a[idx] = (long) val;
      } else if (rawArray instanceof Object[] a) {
        a[idx] = val;
      }
    }
  }

  public void setObject(int idx, Object val) {
    if (idx >= 0 && idx < size) {
      if (rawArray instanceof Object[] a) {
        a[idx] = val;
      }
    }
  }

  public long getInt(int idx) {
    if (idx >= 0 && idx < size) {
      if (rawArray instanceof int[] a) {
        return a[idx];
      } else if (rawArray instanceof float[] a) {
        return (long) a[idx];
      } else if (rawArray instanceof double[] a) {
        return (long) a[idx];
      } else if (rawArray instanceof long[] a) {
        return a[idx];
      } else if (rawArray instanceof Object[] a) {
        Object o = a[idx];
        if (o instanceof Number n) {
          return n.longValue();
        }
      }
    }
    return 0L;
  }

  public double getFloat(int idx) {
    if (idx >= 0 && idx < size) {
      if (rawArray instanceof int[] a) {
        return a[idx];
      } else if (rawArray instanceof float[] a) {
        return a[idx];
      } else if (rawArray instanceof double[] a) {
        return a[idx];
      } else if (rawArray instanceof long[] a) {
        return a[idx];
      } else if (rawArray instanceof Object[] a) {
        Object o = a[idx];
        if (o instanceof Number n) {
          return n.doubleValue();
        }
      }
    }
    return 0.0;
  }

  public int size() {
    return size;
  }
}
