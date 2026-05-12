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
  /** Old noteData format: 10 bytes per note (20 hex chars). */
  public static final int HEX_CHARS_PER_NOTE_OLD = 20;

  /** noteDataWithLift format (c1.2.0): 11 bytes per note (22 hex chars). */
  public static final int HEX_CHARS_PER_NOTE_LIFT = 22;

  /** noteDataWithSplitProb format (1.3+): 14 bytes per note (28 hex chars). */
  public static final int HEX_CHARS_PER_NOTE_SPLIT = 28;

  /** Ticks per step for our internal format (matches serialization roundtrip). */
  public static final int TICKS_PER_STEP = 12;

  public static String encodeRow(List<StepData> row) {
    return encodeRow(row, TICKS_PER_STEP);
  }

  public static String encodeRow(List<StepData> row, int ticksPerStep) {
    StringBuilder sb = new StringBuilder("0x");
    for (int s = 0; s < row.size(); s++) {
      StepData step = row.get(s);
      if (step.active()) {
        int pos = s * ticksPerStep;
        int len = (int) (step.gate() * ticksPerStep);
        if (len == 0) len = 1;

        String hexPos = String.format("%08X", pos);
        String hexLen = String.format("%08X", len);
        String hexFlags = "4014"; // Default flags for now

        sb.append(hexPos).append(hexLen).append(hexFlags);
      }
    }
    return sb.toString();
  }

  /**
   * Decode firmware XML noteData into step data.
   * @param hex the hex-encoded noteData string
   * @param stepCount total steps in the clip (determines output list size)
   * @param ticksPerStep tick-per-step ratio of the source data (24 for firmware, 12 for our format)
   * @param hexCharsPerNote number of hex chars per note (20 for old, 22 for noteDataWithLift, 28 for noteDataWithSplitProb)
   */
  public static List<StepData> decodeRow(String hex, int stepCount, int ticksPerStep, int hexCharsPerNote) {
    List<StepData> row = new ArrayList<>();
    for (int s = 0; s < stepCount; s++) {
      row.add(StepData.empty());
    }

    if (hex == null || !hex.startsWith("0x") || hex.length() < 2 + hexCharsPerNote) {
      return row;
    }

    String data = hex.substring(2);
    int idx = 0;
    while (idx + hexCharsPerNote <= data.length()) {
      String hexPos = data.substring(idx, idx + 8);
      String hexLen = data.substring(idx + 8, idx + 16);

      int pos = (int) Long.parseLong(hexPos, 16);
      int len = (int) Long.parseLong(hexLen, 16);

      int step = pos / ticksPerStep;
      float gate = (float) len / ticksPerStep;

      // Parse velocity if available (byte at offset 16-17 in hex, i.e. hex chars 16-18)
      float velocity = 0.8f;
      if (hexCharsPerNote >= 22) {
        String hexVel = data.substring(idx + 16, idx + 18);
        int velInt = Integer.parseInt(hexVel, 16);
        velocity = velInt / 127.0f;
      }

      if (step >= 0 && step < stepCount) {
        row.set(step, StepData.of(true, velocity, gate, 1.0f, 0));
      }

      idx += hexCharsPerNote;
    }

    return row;
  }

  /**
   * Encode a single note as a 20-hex-char (10-byte) noteData entry.
   * Format: 8 chars tick position + 8 chars tick length + 4 chars flags ("4014").
   * Matches the existing encodeRow() output format.
   */
  public static String encodeTickNote(int tickPos, int tickLen) {
    return String.format("%08X%08X4014", tickPos, Math.max(1, tickLen));
  }

  /** Backward-compatible decode using 20-char notes and internal ticks-per-step. */
  public static List<StepData> decodeRow(String hex, int stepCount) {
    return decodeRow(hex, stepCount, TICKS_PER_STEP, HEX_CHARS_PER_NOTE_OLD);
  }

  /** Backward-compatible decode with ticksPerStep and default 20-char notes. */
  public static List<StepData> decodeRow(String hex, int stepCount, int ticksPerStep) {
    return decodeRow(hex, stepCount, ticksPerStep, HEX_CHARS_PER_NOTE_OLD);
  }
}
