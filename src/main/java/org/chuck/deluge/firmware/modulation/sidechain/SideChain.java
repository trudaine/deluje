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
  public Envelope.EnvelopeStage status = Envelope.EnvelopeStage.OFF;
  public int pos = 0;
  public int lastValue = 2147483647;
  public int pendingHitStrength = 0;
  public int envelopeHeight = 2147483647;

  public int attack = 1000;
  public int release = 5000;

  public SyncType syncType = SyncType.SYNC_TYPE_EVEN;
  public SyncLevel syncLevel = SyncLevel.SYNC_LEVEL_NONE;

  public void registerHit(int strength) {
    pendingHitStrength = strength;
    if (status == Envelope.EnvelopeStage.OFF || status == Envelope.EnvelopeStage.RELEASE) {
      status = Envelope.EnvelopeStage.ATTACK;
      pos = 0;
      envelopeHeight = lastValue; // Starts from current dip
    }
  }

  public int render(int numSamples, int shapeValue) {
    if (status == Envelope.EnvelopeStage.OFF) return 2147483647;

    switch (status) {
      case ATTACK:
        pos += attack * numSamples;
        if (pos >= 8388608) {
          pos = 0;
          status = Envelope.EnvelopeStage.RELEASE;
          envelopeHeight = lastValue;
        }
        break;

      case RELEASE:
        pos += release * numSamples;
        if (pos >= 8388608) {
          status = Envelope.EnvelopeStage.OFF;
          lastValue = 2147483647;
          return lastValue;
        }
        break;
    }

    // ── Bit-Accurate Shape Modulation ──
    int positiveShapeValue = (int) (shapeValue + 2147483648L);
    int preValue;

    int curvedness16 = (positiveShapeValue >> 15) - (pos >> 7);
    if (curvedness16 < 0) {
      preValue = pos << 8;
    } else {
      if (curvedness16 > 65536) curvedness16 = 65536;
      int straightness = 65536 - curvedness16;
      // Blend between straight (linear) and exponential (decay8)
      preValue =
          straightness * (pos >> 8)
              + (FirmwareUtils.getDecay8(8388608 - pos, 23) >> 16) * curvedness16;
    }

    lastValue = ONE - envelopeHeight + (Q31.multiply_32x32_rshift32(preValue, envelopeHeight) << 1);
    return lastValue;
  }
}
