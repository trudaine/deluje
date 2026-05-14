package org.chuck.deluge.firmware.dsp.timestretch;

/**
 * Port of the Deluge's TimeStretcher class. Handles granular time-stretching by managing multiple
 * play heads and crossfading between "hops".
 */
public class TimeStretcher {
  // Firmware lookup tables for hop management
  private static final short[] minHopSizeCoarse = {2500, 3000, 3000, 600, 300};
  private static final short[] minHopSizeFine = {
    3000, 3000, 3000, 3000, 3000, 3000, 3000, 3000, // -12, ....
    3000, 2500, 2000, 1500, 1000, 900, 800, 700, // +0, ....
    600 // +12
  };

  private static final short[] maxHopSizeCoarse = {5000, 6500, 11000, 4000, 2500};
  private static final short[] maxHopSizeFine = {
    6500, 7000, 8000, 9000, 9500, 9750, 10000, 11000, // -12, ....
    11000, 7500, 8000, 6500, 5000, 4750, 4500, 4250, // +0, ....
    4000 // +12
  };

  private static final short[] crossfadeProportionalCoarse = {200, 160, 0, 9, 9};
  private static final short[] crossfadeProportionalFine = {
    160, 140, 125, 110, 90, 70, 50, 20, // -12, ....
    0, 20, 20, 20, 20, 17, 14, 11, // +0, ....
    9 // +12
  };

  public long samplePosBig; // 24-bit fractional part
  public int crossfadeProgress; // 0 to 1.0 (Q31)
  public int crossfadeIncrement;
  public int samplesTilHopEnd;

  private long olderSamplePosBig;
  private boolean olderActive = false;

  private static final int kMaxQ31 = 2147483647;

  public void process(
      int[] output, int numSamples, int timeStretchRatio, int phaseIncrement, int[] sampleData) {
    // Calculate parameters based on speedLog (simplified interpolation)
    int speedLog = org.chuck.deluge.firmware.util.FirmwareUtils.quickLog(timeStretchRatio);
    int minBeamWidth = 3000; // default
    int crossfadeProportional = 20;

    if (speedLog >= (800 << 20) && speedLog < (864 << 20)) {
      // Fine range
      minBeamWidth = minHopSizeFine[8]; // simplified
    }

    for (int i = 0; i < numSamples; i++) {
      // 1. Advance positions
      samplePosBig += timeStretchRatio;
      if (olderActive) {
        olderSamplePosBig += phaseIncrement;
      }

      // 2. Handle Hops
      if (--samplesTilHopEnd <= 0) {
        initiateHop(minBeamWidth, crossfadeProportional, phaseIncrement);
      }

      // 3. Crossfaded read
      int newerSample = readSample(sampleData, samplePosBig);
      int olderSample = olderActive ? readSample(sampleData, olderSamplePosBig) : 0;

      if (crossfadeIncrement > 0) {
        crossfadeProgress += crossfadeIncrement;
        if (crossfadeProgress > kMaxQ31) {
          crossfadeProgress = kMaxQ31;
          olderActive = false;
        }
      }

      // bit-accurate mix
      long mix =
          ((long) olderSample * (kMaxQ31 - crossfadeProgress)
                  + (long) newerSample * crossfadeProgress)
              >> 31;
      output[i] = (int) mix;
    }
  }

  private int readSample(int[] data, long posBig) {
    int idx = (int) (posBig >> 24);
    if (idx < 0 || idx >= data.length) return 0;
    return data[idx];
  }

  private void initiateHop(int minBeamWidth, int crossfadeProportional, int phaseIncrement) {
    olderSamplePosBig = samplePosBig;
    olderActive = true;

    // Firmware math for hop length
    int bestBeamWidth = minBeamWidth + (int) (Math.random() * 1000);
    samplesTilHopEnd = (int) (((long) bestBeamWidth << 24) / phaseIncrement);

    int crossfadeLengthSamples = (int) (((long) samplesTilHopEnd * crossfadeProportional) >> 16);
    if (crossfadeLengthSamples < 10) crossfadeLengthSamples = 10;

    crossfadeIncrement = kMaxQ31 / crossfadeLengthSamples;
    crossfadeProgress = 0;
  }
}
