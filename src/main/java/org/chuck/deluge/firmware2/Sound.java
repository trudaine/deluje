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

  /** FM modulator transpose in semitones (voice.cpp modulatorTranspose[m]). */
  public final int[] modulatorTranspose = new int[2];

  /** FM modulator cents fine-tuners (voice.cpp modulatorTransposers[m]). */
  public final PhaseIncrementFineTuner[] modulatorTransposers = {
    new PhaseIncrementFineTuner(), new PhaseIncrementFineTuner()
  };

  /** C: modulatorTransposers[m].setup((int32_t)modulatorCents[m] * 42949672) (sound.cpp:3077). */
  public void setModulatorCents(int m, int cents) {
    modulatorTransposers[m].setup(cents * 42949672);
  }

  /** Per-source DX7 patch (156-byte). Mirrors C sources[s].ensureDxPatch(); null = source not DX7. */
  public final byte[][] sourceDx7Patch = new byte[2][];

  public final int[] globalSourceValues = new int[3];

  /**
   * The patch's per-param "knob"/preset values (bipolar Q31), mirroring the C
   * {@code ParamManager}'s patched-param set. The patcher reads these via
   * {@link #getSmoothedPatchedParamValue} and runs them through the firmware curves.
   */
  public final int[] patchedParamValues = new int[Param.kNumParams];

  /** The patch's modulation cables (mirrors the C {@code ParamManager}'s PatchCableSet). */
  public final Patcher.PatchCableSet patchCableSet = new Patcher.PatchCableSet();

  /** C: {@code Sound::getSmoothedPatchedParamValue}. No automation smoothing yet (static value). */
  public int getSmoothedPatchedParamValue(int p) {
    return patchedParamValues[p];
  }

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
