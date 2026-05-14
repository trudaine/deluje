package org.chuck.deluge.firmware.modulation.sidechain;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.model.SyncLevel;
import org.chuck.deluge.firmware.model.SyncType;
import org.chuck.deluge.firmware.modulation.Envelope;
import org.chuck.deluge.firmware.util.FirmwareUtils;

public class SideChain {
  public Envelope.EnvelopeStage status = Envelope.EnvelopeStage.OFF;
  public int pos = 0;
  public int lastValue = 2147483647;
  public int pendingHitStrength = 0;

  public int attack = 1000;
  public int release = 5000;

  public SyncType syncType = SyncType.SYNC_TYPE_EVEN;
  public SyncLevel syncLevel = SyncLevel.SYNC_LEVEL_NONE;

  public void registerHit(int strength) {
    pendingHitStrength = strength;
    if (status == Envelope.EnvelopeStage.OFF || status == Envelope.EnvelopeStage.RELEASE) {
      status = Envelope.EnvelopeStage.ATTACK;
      pos = 0;
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
        }
        lastValue = 2147483647 - FirmwareUtils.getDecay4(pos, 23);
        break;

      case RELEASE:
        pos += release * numSamples;
        if (pos >= 8388608) {
          status = Envelope.EnvelopeStage.OFF;
          lastValue = 2147483647;
        } else {
          lastValue = FirmwareUtils.getDecay8(pos, 23);
        }
        break;
    }

    // Apply shape modulation if needed (simplified)
    return lastValue;
  }
}
