package org.chuck.deluge;

import java.io.*;
import org.chuck.audio.util.DelugeAdsr;

/**
 * Diagnostic subclass of DelugeAdsr that overrides the block tick to dump the first few samples of
 * each block to stderr, so we can see what ADSR is outputting during block-based advance().
 */
public class DelugeAdsrDiag extends DelugeAdsr {

  private int blockCount = 0;
  private boolean firstBlockDumped = false;

  // File to dump output samples
  private DataOutputStream dumpStream;

  public DelugeAdsrDiag() {
    try {
      File dumpFile = new File(System.getProperty("java.io.tmpdir"), "deluge-adsr-dump.bin");
      dumpStream = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(dumpFile)));
    } catch (Exception e) {
      System.err.println("[AdsrDiag] Failed to open dump file: " + e.getMessage());
    }
  }

  public DelugeAdsrDiag(float sampleRate) {
    super(sampleRate);
    try {
      File dumpFile = new File(System.getProperty("java.io.tmpdir"), "deluge-adsr-dump.bin");
      dumpStream = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(dumpFile)));
    } catch (Exception e) {
      System.err.println("[AdsrDiag] Failed to open dump file: " + e.getMessage());
    }
  }

  @Override
  public void tick(float[] buffer, int offset, int length, long systemTime) {
    blockCount++;

    // ALWAYS log every blockTick
    System.err.println(
        "[AdsrDiag] blockTick #"
            + blockCount
            + " sysTime="
            + systemTime
            + " length="
            + length
            + " state="
            + state()
            + " value="
            + value());

    // Call the default block tick (per-sample loop via super)
    super.tick(buffer, offset, length, systemTime);

    // Write all output samples to binary dump file
    System.err.println(
        "[AdsrDiag] dumpStream="
            + dumpStream
            + " buffer="
            + buffer
            + " offset="
            + offset
            + " length="
            + length);
    if (dumpStream != null && buffer != null) {
      try {
        for (int i = 0; i < length; i++) {
          dumpStream.writeFloat(buffer[offset + i]);
        }
        dumpStream.flush();
        System.err.println("[AdsrDiag] Wrote " + length + " floats to dump");
      } catch (IOException e) {
        System.err.println("[AdsrDiag] Dump write error: " + e.getMessage());
      }
    }
  }

  /** Thread-safe: also trace per-sample tick calls */
  @Override
  public float tick(long systemTime) {
    boolean shouldLog = (systemTime == 99999); // only log the known manual tick at 99999
    if (shouldLog)
      System.err.println(
          "[AdsrDiag] tick(long) called: sysTime="
              + systemTime
              + " state="
              + state()
              + " value="
              + value());
    float result = super.tick(systemTime);
    if (shouldLog) System.err.println("[AdsrDiag] tick(long) result=" + result);
    return result;
  }

  public void closeDump() {
    try {
      if (dumpStream != null) {
        dumpStream.flush();
        dumpStream.close();
        System.err.println("[AdsrDiag] Dump closed.");
      }
    } catch (Exception e) {
      System.err.println("[AdsrDiag] Close error: " + e.getMessage());
    }
  }
}
