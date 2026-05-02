package org.chuck.deluge.xml;

import java.util.ArrayList;
import java.util.List;
import org.chuck.deluge.model.StepData;

/**
 * Utility to convert between a list of StepData and the Deluge hex-encoded noteData string. Based
 * on reverse engineering: 10 bytes per note (20 hex chars). - 4 bytes: Position in ticks (1 step =
 * 12 ticks) - 4 bytes: Length in ticks - 2 bytes: Flags/Velocity (default 4014)
 */
public class DelugeNoteDataMapper {
  private static final int TICKS_PER_STEP = 12;

  public static String encodeRow(List<StepData> row) {
    StringBuilder sb = new StringBuilder("0x");
    for (int s = 0; s < row.size(); s++) {
      StepData step = row.get(s);
      if (step.active()) {
        int pos = s * TICKS_PER_STEP;
        int len = (int) (step.gate() * TICKS_PER_STEP);
        if (len == 0) len = 1;

        String hexPos = String.format("%08X", pos);
        String hexLen = String.format("%08X", len);
        String hexFlags = "4014"; // Default flags for now

        sb.append(hexPos).append(hexLen).append(hexFlags);
      }
    }
    return sb.toString();
  }

  public static List<StepData> decodeRow(String hex, int stepCount) {
    List<StepData> row = new ArrayList<>();
    for (int s = 0; s < stepCount; s++) {
      row.add(StepData.empty());
    }

    if (hex == null || !hex.startsWith("0x") || hex.length() < 22) {
      return row;
    }

    String data = hex.substring(2);
    int idx = 0;
    while (idx + 20 <= data.length()) {
      String hexPos = data.substring(idx, idx + 8);
      String hexLen = data.substring(idx + 8, idx + 16);
      // ignore flags for now

      int pos = (int) Long.parseLong(hexPos, 16);
      int len = (int) Long.parseLong(hexLen, 16);

      int step = pos / TICKS_PER_STEP;
      float gate = (float) len / TICKS_PER_STEP;

      if (step >= 0 && step < stepCount) {
        row.set(step, new StepData(true, 0.8f, gate, 1.0f, 0));
      }

      idx += 20;
    }

    return row;
  }
}
