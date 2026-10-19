package org.deluge.model;

/**
 * Sequencing direction modes for a clip or note row (C {@code SequenceDirection}, {@code
 * util/functions.cpp:1248-1281}).
 */
public enum SequenceDirection {
  FORWARD,
  REVERSE,
  PINGPONG,
  OBEY_PARENT;

  /** C {@code stringToSequenceDirectionMode} ({@code util/functions.cpp:1268-1281}). */
  public static SequenceDirection fromFirmwareString(String s) {
    if (s == null) {
      return FORWARD;
    }
    return switch (s) {
      case "reverse" -> REVERSE;
      case "pingpong" -> PINGPONG;
      case "obeyParent" -> OBEY_PARENT;
      default -> FORWARD;
    };
  }

  /** C {@code sequenceDirectionModeToString} ({@code util/functions.cpp:1248-1264}). */
  public String toFirmwareString() {
    return switch (this) {
      case FORWARD -> "forward";
      case REVERSE -> "reverse";
      case PINGPONG -> "pingpong";
      case OBEY_PARENT -> "none";
    };
  }
}
