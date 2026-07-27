package org.deluge.firmware2;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.logging.Logger;

public class WavetableLoader {
  private static final Logger LOGGER = Logger.getLogger(WavetableLoader.class.getName());

  public static short[] loadTable(String resourcePath, int size) {
    short[] table = new short[size];
    try (InputStream is = WavetableLoader.class.getResourceAsStream(resourcePath)) {
      if (is == null) {
        LOGGER.warning("[WavetableLoader] Could not find resource: " + resourcePath);
        return table;
      }
      try (DataInputStream dis = new DataInputStream(is)) {
        for (int i = 0; i < size; i++) {
          table[i] = dis.readShort(); // Reads big-endian signed 16-bit short
        }
      }
    } catch (Exception e) {
      LOGGER.warning(
          "[WavetableLoader] Failed to load resource: "
              + resourcePath
              + " ("
              + e.getMessage()
              + ")");
    }
    return table;
  }
}
