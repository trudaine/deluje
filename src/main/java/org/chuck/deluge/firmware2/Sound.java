package org.chuck.deluge.firmware2;

import org.chuck.deluge.firmware2.FilterSet.FilterMode;
import org.chuck.deluge.firmware2.Lfo.LfoConfig;
import org.chuck.deluge.firmware2.Oscillator.OscType;

/**
 * Replica of the Deluge C++ Sound class configuration. Self-contained in firmware2 package with
 * zero dependencies on legacy firmware.
 */
public class Sound {
  public int synthMode = 0; // 0=subtractive, 1=FM, 2=ringmod
  public final OscType[] oscTypes = {OscType.SINE, OscType.SINE};
  public FilterMode lpfMode = FilterMode.OFF;
  public FilterMode hpfMode = FilterMode.OFF;
  public int filterRoute = 0;
  public int volumeNeutralValueForUnison = 134217728;
  public boolean modulator1ToModulator0 = false;
  public float fmRatio1 = 1.0f;
  public float fmRatio2 = 1.0f;
  public final int[] globalSourceValues = new int[3];

  public final LfoConfig[] lfoConfig = new LfoConfig[4];
  public int timePerInternalTickInverse = 1 << 20;

  public Sound() {
    for (int i = 0; i < 4; i++) {
      lfoConfig[i] = new LfoConfig();
    }
  }

  public int getSyncedLFOPhaseIncrement(LfoConfig config) {
    int shift = Lfo.SyncLevel.L_256TH.ordinal() - config.syncLevel.ordinal();
    int phaseIncrement = timePerInternalTickInverse >> shift;
    switch (config.syncType) {
      case EVEN:
        break;
      case TRIPLET:
        phaseIncrement = phaseIncrement * 3 / 2;
        break;
      case DOTTED:
        phaseIncrement = phaseIncrement * 2 / 3;
        break;
    }
    return phaseIncrement;
  }
}
