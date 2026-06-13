package org.chuck.deluge.firmware2;

public class WaveTableBand {
  public int maxPhaseIncrement;
  public int fromCycleNumber;
  public int toCycleNumber;
  public int cycleSizeNoDuplicates;
  public byte cycleSizeMagnitude;
  public boolean intendedForLinearInterpolation;
  public short[] data; // Interleaved band data
}
