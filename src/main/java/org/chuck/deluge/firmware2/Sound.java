package org.chuck.deluge.firmware2;

import java.util.ArrayList;
import org.chuck.deluge.firmware.storage.wave_table.WaveTable;
import org.chuck.deluge.firmware2.FilterSet.FilterMode;
import org.chuck.deluge.firmware2.Lfo.LfoConfig;
import org.chuck.deluge.firmware2.Oscillator.OscType;

/**
 * Replica of the Deluge C++ Sound class configuration. Self-contained in firmware2 package with
 * zero dependencies on legacy firmware.
 */
public class Sound extends GlobalEffectable {
  public static final int kMaxNumVoicesUnison = 8;

  public int synthMode = 0; // 0=subtractive, 1=FM, 2=ringmod
  public int voicePriority = 1; // 0=low, 1=medium, 2=high
  public final OscType[] oscTypes = {OscType.SINE, OscType.SINE};
  public final WaveTable[] waveTables = new WaveTable[2];
  public FilterMode lpfMode = FilterMode.OFF;
  public FilterMode hpfMode = FilterMode.OFF;
  public int filterRoute = 0;
  public int numUnison = 1;
  public int unisonDetune = 8;
  public int unisonStereoSpread = 0;
  public final PhaseIncrementFineTuner[] unisonDetuners =
      new PhaseIncrementFineTuner[kMaxNumVoicesUnison];
  public final int[] unisonPan = new int[kMaxNumVoicesUnison];
  public int volumeNeutralValueForUnison = 134217728;
  public boolean modulator1ToModulator0 = false;
  public float fmRatio1 = 1.0f;
  public float fmRatio2 = 1.0f;

  /** C: sound.h:143 — osc B hard-syncs to osc A when true. */
  public boolean oscillatorSync = false;

  /** C: mod_controllable_audio.h:107 — per-sound saturation/clipping amount; 0 = off. */
  public int clippingAmount = 0;

  /** C: UNPATCHED_PORTAMENTO knob (raw Q31); INT_MIN = off. */
  public int portamentoKnob = Integer.MIN_VALUE;

  /** C: sound.h:141 — previous note code, stored for portamento; INT_MIN = none yet. */
  public int lastNoteCode = Integer.MIN_VALUE;

  /** C: sound.h:286 — saturation output left-shift derived from clippingAmount. */
  public int getShiftAmountForSaturation() {
    return (clippingAmount >= 2) ? (clippingAmount - 2) : 0;
  }

  /** C: sound.h:156 — per-source retrigger phase; 0xFFFFFFFF means "off" (sound.cpp:88). */
  public final int[] oscRetriggerPhase = {0xFFFFFFFF, 0xFFFFFFFF};

  /** C: sound.h:157 — per-modulator retrigger phase; 0xFFFFFFFF means "off". */
  public final int[] modulatorRetriggerPhase = {0xFFFFFFFF, 0xFFFFFFFF};

  /**
   * C: sound.cpp:2112-2121 — osc sync renders only when enabled, not FM, and osc B is audible (its
   * volume KNOB isn't off) or we're in ringmod.
   */
  public boolean renderingOscillatorSyncCurrently() {
    if (!oscillatorSync) {
      return false;
    }
    if (synthMode == 1) { // FM
      return false;
    }
    return patchedParamValues[Param.LOCAL_OSC_B_VOLUME] != Integer.MIN_VALUE || synthMode == 2;
  }

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

  /**
   * Per-source DX7 patch (156-byte). Mirrors C sources[s].ensureDxPatch(); null = source not DX7.
   */
  public final byte[][] sourceDx7Patch = new byte[2][];

  public final int[] globalSourceValues = new int[3];

  /**
   * The patch's per-param "knob"/preset values (bipolar Q31), mirroring the C {@code
   * ParamManager}'s patched-param set. The patcher reads these via {@link
   * #getSmoothedPatchedParamValue} and runs them through the firmware curves.
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

  /** C: sound.h:358 — {@code deluge::fast_vector<ActiveVoice> voices_;} */
  public final ArrayList<Voice> voices = new ArrayList<>();

  /**
   * C: sound.h:154 — {@code std::array<int32_t, kNumExpressionDimensions>
   * monophonicExpressionValues{}};
   */
  public final int[] monophonicExpressionValues = new int[3];

  /**
   * C: sound.h:149 — {@code std::bitset<kNumExpressionDimensions>
   * expressionSourcesChangedAtSynthLevel{0}};
   */
  public int expressionSourcesChangedAtSynthLevel;

  public Sound() {
    for (int i = 0; i < 4; i++) {
      lfoConfig[i] = new LfoConfig();
    }
    for (int i = 0; i < kMaxNumVoicesUnison; i++) {
      unisonDetuners[i] = new PhaseIncrementFineTuner();
    }
  }

  public void setupUnisonDetuners() {
    if (numUnison != 1) {
      int detuneScaled = unisonDetune * 42949672;
      int lowestVoice = -(detuneScaled >> 1);
      int voiceSpacing = detuneScaled / (numUnison - 1);

      for (int u = 0; u < numUnison; u++) {
        // Middle unison part gets no detune
        if ((numUnison & 1) != 0 && u == ((numUnison - 1) >> 1)) {
          unisonDetuners[u].setNoDetune();
        } else {
          unisonDetuners[u].setup(lowestVoice + voiceSpacing * u);
        }
      }
    }
  }

  public void setupUnisonStereoSpread() {
    if (numUnison != 1) {
      int spreadScaled = unisonStereoSpread * 42949672;
      int lowestVoice = -(spreadScaled >> 1);
      int voiceSpacing = spreadScaled / (numUnison - 1);

      for (int u = 0; u < numUnison; u++) {
        // alternate the voices like -2 +1 0 -1 +2 for more balanced
        // interaction with detune
        boolean isOdd = (Math.min(u, numUnison - 1 - u) & 1) != 0;
        int sign = isOdd ? -1 : 1;

        unisonPan[u] = sign * (lowestVoice + voiceSpacing * u);
      }
    }
  }

  public void calculateEffectiveVolume() {
    volumeNeutralValueForUnison = (int) (134217728.0 / Math.sqrt(numUnison));
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // Faithful param-default methods (sound.cpp:131-325). Each takes an int[] that the
  // bridge (FirmwareSound) holds as paramNeutralValues[], matching the C PatchedParamSet.
  // ═══════════════════════════════════════════════════════════════════════════════

  /** C: sound.cpp:131-187 + ModControllableAudio::initParams. */
  public static void initParams(int[] params) {
    for (int i = 0; i < params.length; i++) {
      params[i] = Integer.MIN_VALUE; // C setCurrentValueBasicForSetup(INT_MIN) for most
    }

    params[Param.LOCAL_VOLUME] = 0; // C:141
    params[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE; // C:142 — 2147483647
    params[Param.LOCAL_OSC_B_VOLUME] = Integer.MAX_VALUE; // C:143
    params[Param.GLOBAL_VOLUME_POST_FX] = // C:144-145
        Functions.getParamFromUserValue(Param.GLOBAL_VOLUME_POST_FX, 40);
    params[Param.GLOBAL_VOLUME_POST_REVERB_SEND] = 0; // C:146
    params[Param.LOCAL_FOLD] = Integer.MIN_VALUE; // C:147
    params[Param.LOCAL_HPF_RESONANCE] = Integer.MIN_VALUE; // C:148
    params[Param.LOCAL_HPF_FREQ] = Integer.MIN_VALUE; // C:149
    params[Param.LOCAL_HPF_MORPH] = Integer.MIN_VALUE; // C:150
    params[Param.LOCAL_LPF_MORPH] = Integer.MIN_VALUE; // C:151
    params[Param.LOCAL_PITCH_ADJUST] = 0; // C:152
    params[Param.GLOBAL_REVERB_AMOUNT] = Integer.MIN_VALUE; // C:153
    params[Param.GLOBAL_DELAY_RATE] = 0; // C:154
    params[Param.GLOBAL_ARP_RATE] = 0; // C:155
    params[Param.GLOBAL_DELAY_FEEDBACK] = Integer.MIN_VALUE; // C:156
    params[Param.LOCAL_CARRIER_0_FEEDBACK] = Integer.MIN_VALUE; // C:157
    params[Param.LOCAL_CARRIER_1_FEEDBACK] = Integer.MIN_VALUE; // C:158
    params[Param.LOCAL_MODULATOR_0_FEEDBACK] = Integer.MIN_VALUE; // C:159
    params[Param.LOCAL_MODULATOR_1_FEEDBACK] = Integer.MIN_VALUE; // C:160
    params[Param.LOCAL_MODULATOR_0_VOLUME] = Integer.MIN_VALUE; // C:161
    params[Param.LOCAL_MODULATOR_1_VOLUME] = Integer.MIN_VALUE; // C:162
    params[Param.LOCAL_OSC_A_PHASE_WIDTH] = 0; // C:163
    params[Param.LOCAL_OSC_B_PHASE_WIDTH] = 0; // C:164
    params[Param.LOCAL_ENV_1_ATTACK] = // C:165-166
        Functions.getParamFromUserValue(Param.LOCAL_ENV_1_ATTACK, 20);
    params[Param.LOCAL_ENV_1_DECAY] = // C:167-168
        Functions.getParamFromUserValue(Param.LOCAL_ENV_1_DECAY, 20);
    params[Param.LOCAL_ENV_1_SUSTAIN] = // C:169-170
        Functions.getParamFromUserValue(Param.LOCAL_ENV_1_SUSTAIN, 25);
    params[Param.LOCAL_ENV_1_RELEASE] = // C:171-172
        Functions.getParamFromUserValue(Param.LOCAL_ENV_1_RELEASE, 20);
    params[Param.LOCAL_LFO_LOCAL_FREQ_1] = 0; // C:173
    params[Param.GLOBAL_LFO_FREQ_1] = // C:174-175
        Functions.getParamFromUserValue(Param.GLOBAL_LFO_FREQ_1, 30);
    params[Param.LOCAL_LFO_LOCAL_FREQ_2] = 0; // C:176
    params[Param.GLOBAL_LFO_FREQ_2] = // C:177-178
        Functions.getParamFromUserValue(Param.GLOBAL_LFO_FREQ_2, 30);
    params[Param.LOCAL_PAN] = 0; // C:179
    params[Param.LOCAL_NOISE_VOLUME] = Integer.MIN_VALUE; // C:180
    params[Param.GLOBAL_MOD_FX_DEPTH] = 0; // C:181
    params[Param.GLOBAL_MOD_FX_RATE] = 0; // C:182
    params[Param.LOCAL_OSC_A_PITCH_ADJUST] = 0; // C:183
    params[Param.LOCAL_OSC_B_PITCH_ADJUST] = 0; // C:184
    params[Param.LOCAL_MODULATOR_0_PITCH_ADJUST] = 0; // C:185
    params[Param.LOCAL_MODULATOR_1_PITCH_ADJUST] = 0; // C:186
    // C:138 — PORTAMENTO = INT_MIN (internal param, not exposed)
  }

  /** C: sound.cpp:223-259 — preset that ships with new synths. */
  public static void setupAsDefaultSynth(int[] params, Sound cfg) {
    params[Param.LOCAL_OSC_B_VOLUME] = 0x47AE1457; // C:226 — ~half
    params[Param.LOCAL_LPF_RESONANCE] = 0xA2000000; // C:227
    params[Param.LOCAL_LPF_FREQ] = 0x10000000; // C:228
    params[Param.LOCAL_ENV_0_ATTACK] = 0x80000000; // C:229
    params[Param.LOCAL_ENV_0_DECAY] = 0xE6666654; // C:230
    params[Param.LOCAL_ENV_0_SUSTAIN] = 0x7FFFFFFF; // C:231
    params[Param.LOCAL_ENV_0_RELEASE] = 0x851EB851; // C:232
    params[Param.LOCAL_ENV_1_ATTACK] = 0xA3D70A37; // C:233
    params[Param.LOCAL_ENV_1_DECAY] = 0xA3D70A37; // C:234
    params[Param.LOCAL_ENV_1_SUSTAIN] = 0xFFFFFFE9; // C:235
    params[Param.LOCAL_ENV_1_RELEASE] = 0xE6666654; // C:236
    params[Param.GLOBAL_VOLUME_POST_FX] = 0x50000000; // C:237

    // C:239-243 — default patch cables (4 cables)
    addCable(cfg, PatchSource.NOTE, Param.LOCAL_LPF_FREQ, 0x08F5C28C); // C:239
    addCable(cfg, PatchSource.ENVELOPE_1, Param.LOCAL_LPF_FREQ, 0x1C28F5B8); // C:240
    addCable(cfg, PatchSource.VELOCITY, Param.LOCAL_LPF_FREQ, 0x0F5C28F0); // C:241
    addCable(cfg, PatchSource.VELOCITY, Param.LOCAL_VOLUME, 0x3FFFFFE8); // C:242

    cfg.lpfMode = FilterMode.TRANSISTOR_24DB; // C:248
    cfg.oscTypes[0] = OscType.SAW; // C:250
  }

  /** C: sound.cpp:297-325 — blank init (no patch, no shaping). */
  public static void setupAsBlankSynth(int[] params, Sound cfg, boolean isDx) {
    params[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE; // C:300
    params[Param.LOCAL_LPF_FREQ] = Integer.MAX_VALUE; // C:301
    params[Param.LOCAL_LPF_RESONANCE] = Integer.MIN_VALUE; // C:302
    params[Param.LOCAL_ENV_0_ATTACK] = Integer.MIN_VALUE; // C:303
    params[Param.LOCAL_ENV_0_DECAY] = // C:304-305
        Functions.getParamFromUserValue(Param.LOCAL_ENV_0_DECAY, 20);
    params[Param.LOCAL_ENV_0_SUSTAIN] = Integer.MAX_VALUE; // C:306
    if (isDx) {
      cfg.oscTypes[0] = OscType.DX7; // C:308
      cfg.patchCableSet.destinations.clear(); // C:311
      params[Param.LOCAL_ENV_0_RELEASE] = Integer.MAX_VALUE; // C:312
    } else {
      params[Param.LOCAL_ENV_0_RELEASE] = Integer.MIN_VALUE; // C:315
      cfg.patchCableSet.destinations.clear(); // C:317 (numPatchCables=1)
      addCable(
          cfg,
          PatchSource.VELOCITY,
          Param.LOCAL_VOLUME, // C:318-319
          Functions.getParamFromUserValue(Param.PATCH_CABLE, 50));
    }
  }

  // ── isSourceActiveCurrently (sound.cpp:2088-2092) ──

  /**
   * C: sound.cpp:2088-2092. A source is ACTIVE right now if: 1. Ringmod mode, or the smoothed
   * patched-param value for OSC_A_VOLUME+s != MIN_VALUE (off) 2. AND (FM mode, or source type !=
   * SAMPLE, or a sample IS loaded for this source)
   *
   * @param hasSample true if a sample audio file is loaded for this source (C:
   *     sources[s].hasAtLeastOneAudioFileLoaded())
   */
  public boolean isSourceActiveCurrently(int s, boolean hasSample) {
    boolean ampActive =
        (synthMode == 2) // RINGMOD
            || patchedParamValues[Param.LOCAL_OSC_A_VOLUME + s] != Integer.MIN_VALUE;
    boolean typeActive =
        (synthMode == 1) // FM
            || oscTypes[s] != OscType.SAMPLE
            || hasSample;
    return ampActive && typeActive;
  }

  private static void addCable(Sound cfg, PatchSource source, int paramId, int amount) {
    Patcher.PatchCable cable = new Patcher.PatchCable();
    cable.source = source.ordinal();
    cable.amount = amount;
    cfg.patchCableSet.addCable(paramId, cable);
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

  @Override
  protected void renderInternal(int[] buffer, int numSamples, int[] reverbBuffer) {
    synchronized (voices) {
      var it = voices.iterator();
      while (it.hasNext()) {
        Voice v = it.next();
        if (!v.active) {
          it.remove();
          continue;
        }
        v.render(buffer, numSamples, lpfMode != FilterMode.OFF, hpfMode != FilterMode.OFF);
      }
    }
  }
}
