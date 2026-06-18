package org.chuck.core;

import java.util.concurrent.ConcurrentHashMap;

/**
 * A lightweight, thread-safe shadow registry representing the global ChucK VM state. Implemented in
 * pure Java to completely decouple the Deluge UI from the heavy native ChucK VM.
 */
public class ChuckVM {
  private final ConcurrentHashMap<String, Object> registry = new ConcurrentHashMap<>();

  public ChuckVM(int srate, int channels) {
    // Stub constructor
  }

  public void setGlobalInt(String name, long val) {
    registry.put(name, val);
  }

  public void setGlobalFloat(String name, double val) {
    registry.put(name, val);
  }

  public void setGlobalString(String name, String val) {
    registry.put(name, val);
  }

  public void setGlobalObject(String name, Object val) {
    registry.put(name, val);
  }

  public long getGlobalInt(String name) {
    Object o = registry.get(name);
    if (o instanceof Number n) {
      return n.longValue();
    }
    return 0L;
  }

  public double getGlobalFloat(String name) {
    Object o = registry.get(name);
    if (o instanceof Number n) {
      return n.doubleValue();
    }
    return 0.0;
  }

  public String getGlobalString(String name) {
    Object o = registry.get(name);
    return o != null ? o.toString() : "";
  }

  public Object getGlobalObject(String name) {
    return registry.get(name);
  }

  public int getActiveShredCount() {
    return 0; // No active shreds are running in pure Java mode
  }

  public long getCurrentTime() {
    // Return sample count: milliseconds * 44.1 samples/ms
    return (long) (System.currentTimeMillis() * 44.1);
  }

  public int getSampleRate() {
    return 44100; // Default CD sample rate
  }

  public int getLogLevel() {
    return 0; // Quiet logging mode
  }

  public void broadcastGlobalEvent(String name) {
    // Stub event broadcaster
  }

  public boolean eval(String code) {
    return true; // Stub code evaluator
  }

  public DacChannel getDacChannel(int ch) {
    return new DacChannel(); // Return shadow channel object
  }

  public void dispatchHidMsg(org.chuck.hid.HidMsg msg) {
    // Stub HID dispatcher
  }
}
