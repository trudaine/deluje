package org.chuck.deluge.firmware.util;

import java.io.DataInputStream;
import java.io.InputStream;

public class WavetableLoader {

  public static short[] loadTable(String resourcePath, int size) {
    short[] table = new short[size];
    try (InputStream is = WavetableLoader.class.getResourceAsStream(resourcePath)) {
      if (is == null) {
        System.err.println("[WavetableLoader] Could not find resource: " + resourcePath);
        return table;
      }
      try (DataInputStream dis = new DataInputStream(is)) {
        for (int i = 0; i < size; i++) {
          table[i] = dis.readShort(); // Reads big-endian signed 16-bit short
        }
      }
    } catch (Exception e) {
      System.err.println(
          "[WavetableLoader] Failed to load resource: "
              + resourcePath
              + " ("
              + e.getMessage()
              + ")");
    }
    return table;
  }
}
