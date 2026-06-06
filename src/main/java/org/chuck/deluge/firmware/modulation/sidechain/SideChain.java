package org.chuck.deluge.firmware.modulation.sidechain;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.model.SyncLevel;
import org.chuck.deluge.firmware.model.SyncType;
import org.chuck.deluge.firmware.modulation.Envelope;
import org.chuck.deluge.firmware.util.FirmwareUtils;
import org.chuck.deluge.firmware.util.Q31;

/**
 * Port of the Deluge's SideChain class. Implements high-fidelity ducking with bit-accurate shape
 * modulation.
 */
public class SideChain {
  private static final int ENVELOPE_STAGE_LENGTH = 8388608;
  private static final int INSTANT_ATTACK = 1048576;
  private static final int DEFAULT_ATTACK = 327244;
  private static final int DEFAULT_RELEASE = 936;

  public Envelope.EnvelopeStage status = Envelope.EnvelopeStage.OFF;
  public int pos = 0;
  public int lastValue = ONE;
  public int pendingHitStrength = 0;
  public int envelopeHeight = ONE;
  public int envelopeOffset = ONE;

  public int attack = DEFAULT_ATTACK;
  public int release = DEFAULT_RELEASE;

  public SyncType syncType = SyncType.SYNC_TYPE_EVEN;
  public SyncLevel syncLevel = SyncLevel.SYNC_LEVEL_NONE;

  public void registerHit(int strength) {
    pendingHitStrength = combineHitStrengths(pendingHitStrength, strength);
  }

  public int render(int numSamples, int shapeValue) {
    if (pendingHitStrength != 0) {
      int newOffset = ONE - pendingHitStrength;
      pendingHitStrength = 0;

      if (newOffset < lastValue) {
        envelopeOffset = newOffset;
        if (attack == INSTANT_ATTACK) {
          pos = 0;
          status = Envelope.EnvelopeStage.RELEASE;
          envelopeHeight = ONE - envelopeOffset;
        } else {
          status = Envelope.EnvelopeStage.ATTACK;
          envelopeHeight = lastValue - envelopeOffset;
          pos = 0;
        }
      }
    }

    if (status == Envelope.EnvelopeStage.ATTACK) {
      pos += (int) ((attack & 0xFFFFFFFFL) * numSamples);
      if (pos >= ENVELOPE_STAGE_LENGTH) {
        pos = 0;
        status = Envelope.EnvelopeStage.RELEASE;
        envelopeHeight = ONE - envelopeOffset;
      } else {
        lastValue =
            (Q31.multiply_32x32_rshift32(
                        envelopeHeight,
                        ONE - FirmwareUtils.getDecay4(ENVELOPE_STAGE_LENGTH - pos, 23))
                    << 1)
                + envelopeOffset;
        return lastValue - ONE;
      }
    }

    if (status == Envelope.EnvelopeStage.RELEASE) {
      pos += (int) ((release & 0xFFFFFFFFL) * numSamples);
      if (pos >= ENVELOPE_STAGE_LENGTH) {
        status = Envelope.EnvelopeStage.OFF;
        lastValue = ONE;
        return 0;
      }

      int positiveShapeValue = (int) (shapeValue + 2147483648L);
      int preValue;

      int curvedness16 = (positiveShapeValue >> 15) - (pos >> 7);
      if (curvedness16 < 0) {
        preValue = pos << 8;
      } else {
        if (curvedness16 > 65536) curvedness16 = 65536;
        int straightness = 65536 - curvedness16;
        preValue =
            straightness * (pos >> 8)
                + (FirmwareUtils.getDecay8(ENVELOPE_STAGE_LENGTH - pos, 23) >> 16) * curvedness16;
      }

      lastValue =
          ONE - envelopeHeight + (Q31.multiply_32x32_rshift32(preValue, envelopeHeight) << 1);
      return lastValue - ONE;
    }

    lastValue = ONE;
    return 0;
  }

  private static int combineHitStrengths(int strength1, int strength2) {
    long sum = (long) strength1 + strength2;
    if (sum > ONE) {
      sum = ONE;
    }
    int maxOne = Math.max(strength1, strength2);
    return (maxOne >> 1) + (((int) sum) >> 1);
  }
}
