package org.deluge.xml;

import java.util.ArrayList;
import java.util.List;
import org.deluge.model.StepData;

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

  /** noteDataWithIteranceAndFill format (c1.3-nightly): 13 bytes per note (26 hex chars). */
  public static final int HEX_CHARS_PER_NOTE_ITERANCE_AND_FILL = 26;

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
        int pos = Math.round((s + step.nudge()) * ticksPerStep);
        int len = (int) (step.gate() * ticksPerStep);
        if (len == 0) len = 1;

        String hexPos = String.format("%08X", pos);
        String hexLen = String.format("%08X", len);

        // Encode velocity in 1 byte (2 hex chars) at offset 16-17
        int velInt = Math.round(step.velocity() * 127.0f);
        String hexVel = String.format("%02X", Math.max(0, Math.min(127, velInt)));
        String hexFlags = "4014"; // Default flags/lift

        sb.append(hexPos).append(hexLen).append(hexVel).append(hexFlags);
      }
    }
    return sb.toString();
  }

  public static String encodeRowSplit(List<StepData> row, int ticksPerStep) {
    StringBuilder sb = new StringBuilder("0x");
    for (int s = 0; s < row.size(); s++) {
      StepData step = row.get(s);
      if (step.active()) {
        int pos = Math.round((s + step.nudge()) * ticksPerStep);
        int len = (int) (step.gate() * ticksPerStep);
        if (len == 0) len = 1;

        String hexPos = String.format("%08X", pos);
        String hexLen = String.format("%08X", len);

        int velInt = Math.round(step.velocity() * 127.0f);
        String hexVel = String.format("%02X", Math.max(0, Math.min(127, velInt)));
        // C note_row.cpp:3607-3617 — lift (1B, default 0x40), probability (1B, 0..20 in split
        // format where fill is in its own byte; positive values clamp to 1..20),
        // iterance.toInt() (2B, 0x0000 when OFF/1of1), fill (1B, OFF=0, NOT_FILL=1, FILL=2)
        int probByte =
            step.probability() <= 0.0f
                ? 0
                : Math.max(1, Math.min(20, Math.round(step.probability() * 20.0f)));
        int iteranceInt = step.playCondition() == 0x0101 ? 0 : (step.playCondition() & 0xFFFF);
        int fillByte = step.fillMode() != 0 ? step.fillMode() : (step.fill() > 0.001f ? 2 : 0);
        String hexTail = String.format("40%02X%04X%02X", probByte, iteranceInt, fillByte);

        sb.append(hexPos).append(hexLen).append(hexVel).append(hexTail);
      }
    }
    return sb.toString();
  }

  /**
   * Decode firmware XML noteData into step data.
   *
   * @param hex the hex-encoded noteData string
   * @param stepCount total steps in the clip (determines output list size)
   * @param ticksPerStep tick-per-step ratio of the source data (24 for firmware, 12 for our format)
   * @param hexCharsPerNote number of hex chars per note (20 for old, 22 for noteDataWithLift, 26
   *     for noteDataWithIteranceAndFill, 28 for noteDataWithSplitProb)
   */
  public static List<StepData> decodeRow(
      String hex, int stepCount, int ticksPerStep, int hexCharsPerNote) {
    List<StepData> row = new ArrayList<>();
    for (int s = 0; s < stepCount; s++) {
      row.add(StepData.empty());
    }

    List<org.deluge.model.NoteModel> rawNotes = decodeRawNotes(hex, hexCharsPerNote);
    for (org.deluge.model.NoteModel nm : rawNotes) {
      int pos = nm.getPos();
      int len = nm.getLength();
      int step = pos / ticksPerStep;
      float nudge = (float) (pos % ticksPerStep) / ticksPerStep;
      float gate = (float) len / ticksPerStep;
      float velocity = hexCharsPerNote >= 22 ? nm.getVelocity() : 0.8f;
      int fillMode = nm.getFill();
      float fill = fillMode == 2 ? 1.0f : 0.0f;
      int playCondition = nm.getIterance() != null ? nm.getIterance().toInt() : 0;

      if (step >= 0 && step < stepCount) {
        row.set(
            step,
            new org.deluge.model.StepData(
                true,
                velocity,
                gate,
                nm.getProbability(),
                0,
                0,
                fill,
                nudge,
                playCondition,
                fillMode));
      }
    }

    return row;
  }

  public static List<org.deluge.model.NoteModel> decodeRawNotes(String hex, int hexCharsPerNote) {
    List<org.deluge.model.NoteModel> list = new ArrayList<>();
    if (hex == null || !hex.startsWith("0x") || hex.length() < 2 + hexCharsPerNote) {
      return list;
    }

    String data = hex.substring(2);
    int idx = 0;
    while (idx + hexCharsPerNote <= data.length()) {
      String hexPos = data.substring(idx, idx + 8);
      String hexLen = data.substring(idx + 8, idx + 16);

      int pos = (int) Long.parseLong(hexPos, 16);
      int len = (int) Long.parseLong(hexLen, 16);
      int velInt = Integer.parseInt(data.substring(idx + 16, idx + 18), 16);
      float velocity = velInt / 127.0f;

      int lift = 64; // C definitions_cxx.hpp:725 kDefaultLiftValue
      float probability = 1.0f;
      int fill = 0;
      org.deluge.model.Iterance iterance =
          new org.deluge.model.Iterance((byte) 0, (byte) 0); // kDefaultIteranceValue (OFF)

      // C note_row.cpp:3405-3482 — decode lift, probability, iterance, and fill by note length.
      if (hexCharsPerNote == 28) {
        int rawLift = Integer.parseInt(data.substring(idx + 18, idx + 20), 16);
        lift = (rawLift > 0 && rawLift <= 127) ? rawLift : 64;
        int rawProb = Integer.parseInt(data.substring(idx + 20, idx + 22), 16);
        int probByte = rawProb & 0x7F;
        // C note_row.cpp:3494 — only (probability & 127) > 20 or >= (20 | 128) resets to 20 (100%);
        // byte 00 in 26/28-char split-fill formats preserves a 0% silent step.
        probability = (probByte <= 20 && rawProb < (20 | 128)) ? (probByte / 20.0f) : 1.0f;
        iterance =
            org.deluge.model.Iterance.fromInt(
                Integer.parseInt(data.substring(idx + 22, idx + 26), 16));
        fill = Integer.parseInt(data.substring(idx + 26, idx + 28), 16);
      } else if (hexCharsPerNote == 26) {
        int rawLift = Integer.parseInt(data.substring(idx + 18, idx + 20), 16);
        lift = (rawLift > 0 && rawLift <= 127) ? rawLift : 64;
        int rawProb = Integer.parseInt(data.substring(idx + 20, idx + 22), 16);
        int probByte = rawProb & 0x7F;
        probability = (probByte <= 20 && rawProb < (20 | 128)) ? (probByte / 20.0f) : 1.0f;
        iterance =
            org.deluge.model.Iterance.fromLegacyPresetIndex(
                Integer.parseInt(data.substring(idx + 22, idx + 24), 16));
        fill = Integer.parseInt(data.substring(idx + 24, idx + 26), 16);
      } else if (hexCharsPerNote == 22) {
        int rawLift = Integer.parseInt(data.substring(idx + 18, idx + 20), 16);
        lift = (rawLift > 0 && rawLift <= 127) ? rawLift : 64;
        int probByte = Integer.parseInt(data.substring(idx + 20, idx + 22), 16);
        if (probByte == 0 || probByte == 128) {
          fill = (probByte == 0) ? 2 : 1; // C FillMode::FILL=2, FillMode::NOT_FILL=1
          probability = 1.0f;
        } else if (probByte > 20 && probByte <= 20 + 35) {
          iterance = org.deluge.model.Iterance.fromLegacyProbabilityByte(probByte);
          probability = 1.0f;
        } else {
          int pb = probByte & 0x7F;
          probability = (pb >= 1 && pb <= 20) ? (pb / 20.0f) : 1.0f;
        }
      } else if (hexCharsPerNote >= 20) {
        int probByte = Integer.parseInt(data.substring(idx + 18, idx + 20), 16);
        if (probByte == 0 || probByte == 128) {
          fill = (probByte == 0) ? 2 : 1; // C FillMode::FILL=2, FillMode::NOT_FILL=1
          probability = 1.0f;
        } else if (probByte > 20 && probByte <= 20 + 35) {
          iterance = org.deluge.model.Iterance.fromLegacyProbabilityByte(probByte);
          probability = 1.0f;
        } else {
          int pb = probByte & 0x7F;
          probability = (pb >= 1 && pb <= 20) ? (pb / 20.0f) : 1.0f;
        }
      }

      org.deluge.model.NoteModel nm =
          new org.deluge.model.NoteModel(pos, len, velocity, probability, 0);
      nm.setLift(lift);
      nm.setFill(fill);
      nm.setIterance(iterance);
      list.add(nm);
      idx += hexCharsPerNote;
    }
    return list;
  }

  /**
   * Encode a single note as a 20-hex-char (10-byte) noteData entry. Format: 8 chars tick position +
   * 8 chars tick length + 4 chars flags ("4014"). Matches the existing encodeRow() output format.
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
