package org.deluge.midi;

/**
 * Minimal push/pull transport for the Deluge SD card over USB SysEx. Abstracts the two operations
 * the calibration workflow needs so the orchestration ({@link CalibrationCardSync}) is testable
 * without hardware; the production adapter is {@link DelugeFileSyncTransport}.
 */
public interface CardTransport {
  /** Write {@code content} to an absolute card path (e.g. {@code "/SONGS/FM_CAL.XML"}). */
  void upload(String remotePath, byte[] content) throws Exception;

  /** Read the full contents of an absolute card path (e.g. {@code "/SAMPLES/output_000.wav"}). */
  byte[] download(String remotePath) throws Exception;
}
