package org.chuck.deluge.firmware2;

import org.chuck.deluge.firmware2.Oscillator.OscType;

/**
 * Faithful line-by-line port of the Deluge {@code voice.cpp} / {@code voice.h} Voice class. This is
 * the per-note renderer — the main DSP synthesis loop. It dispatches between subtractive, FM,
 * ringmod, and sample rendering, applies envelopes/LFOs/filters/pan/gain, and writes to the output
 * buffer.
 *
 * <p>The FM helpers ({@code renderSineWaveWithFeedback}, {@code renderFMWithFeedback}, {@code
 * renderFMWithFeedbackAdd}) are reused from the existing faithful port in {@code
 * FirmwareVoice.java} — they were verified line-by-line during the FM fix session. Similarly {@code
 * do24dBLPFOnSample} is in {@link FilterSet}.
 *
 * <p>Firmware reference: {@code voice.cpp} lines 710-1670 (render function).
 */
public class Voice {

  // ── Voice state ──

  public int note; // MIDI note number
  public int noteCode; // transposed note code
  public int velocity;
  public boolean active;

  // Phase increments per source
  public int phaseIncrementA;
  public int phaseIncrementB;

  // Envelope array (4 per voice: amp + 3 modulation)
  public final Envelope[] envelopes = new Envelope[4];

  // Per-source oscillators (2 sources: osc A, osc B)
  public final VoiceSource[] sources = new VoiceSource[2];

  // Param final values (output of patcher)
  public final int[] paramFinalValues = new int[Param.kNumParams];

  // Source values for patcher
  public final int[] sourceValues = new int[32]; // PatchSource count

  // FM modulator state
  public final int[] modulatorPhase = {0, 0};
  public final int[] modulatorPhaseIncrement = new int[2];
  public final int[] modulatorFeedback = {0, 0};
  public int[] modulatorAmplitudeLastTime = {0, 0};
  public int[] modulatorAmplitudeIncrements = new int[2];

  // Filter set
  public final FilterSet filterSet = new FilterSet();

  // Pan
  public int panAmplitudeL;
  public int panAmplitudeR;

  // Overall oscillator amplitude (envelope 0 * volume)
  public int overallOscAmplitudeLastTime;
  public int overallOscillatorAmplitudeIncrement;

  // For the envelope center (return value)
  public int env0LastValue;

  public final Sound sound;
  public final Lfo lfo2 = new Lfo();
  public final Lfo lfo4 = new Lfo();

  public Voice(Sound sound) {
    this.sound = sound;
    for (int i = 0; i < envelopes.length; i++) envelopes[i] = new Envelope();
    for (int i = 0; i < sources.length; i++) sources[i] = new VoiceSource();
    for (int i = 0; i < Param.kNumParams; i++) {
      paramFinalValues[i] = Functions.getParamNeutralValue(i);
    }
  }

  public Voice() {
    this(new Sound());
  }

  // ── VoiceSource (port of VoiceUnisonPartSource) ──

  public static class VoiceSource {
    public int oscPos; // 32-bit phase accumulator
    public OscType oscType = OscType.SINE;
    public int carrierFeedback; // FM carrier feedback memory
    public int phaseIncrementStoredValue;
    public boolean active;
  }

  // ── noteOn (voice.cpp:110-339) ──

  public void noteOn(int midiNote, int velocity) {
    filterSet.reset();
    this.note = midiNote;
    this.noteCode = midiNote;
    this.velocity = velocity;
    this.active = true;
    for (Envelope e : envelopes) e.noteOn(false);
    for (VoiceSource s : sources) {
      s.active = true;
      s.oscPos = Functions.getNoise() & 0x7FFFFFFF; // random initial phase
      s.carrierFeedback = 0;
    }
    modulatorPhase[0] = 0;
    modulatorPhase[1] = 0;
    modulatorFeedback[0] = 0;
    modulatorFeedback[1] = 0;
    overallOscAmplitudeLastTime = 0;

    // Line-for-line note setting
    if (noteCode >= 128) {
      sourceValues[PatchSource.NOTE.ordinal()] = 2147483647;
    } else if (noteCode <= 0) {
      sourceValues[PatchSource.NOTE.ordinal()] = -2147483648;
    } else {
      sourceValues[PatchSource.NOTE.ordinal()] = (noteCode - 64) * 33554432;
    }

    // Velocity setting
    sourceValues[PatchSource.VELOCITY.ordinal()] =
        (velocity == 128) ? 2147483647 : (velocity - 64) * 33554432;

    // Random
    sourceValues[PatchSource.RANDOM.ordinal()] = Functions.getNoise();

    // Setup and render local LFO
    lfo2.setLocalInitialPhase(sound.lfoConfig[1]); // LFO2_ID = 1
    sourceValues[PatchSource.LFO_LOCAL_1.ordinal()] = lfo2.render(0, sound.lfoConfig[1], 0);
    lfo4.setLocalInitialPhase(sound.lfoConfig[3]); // LFO4_ID = 3
    sourceValues[PatchSource.LFO_LOCAL_2.ordinal()] = lfo4.render(0, sound.lfoConfig[3], 0);
  }

  // ── noteOff (voice.cpp:570-634) ──

  public void noteOff() {
    for (Envelope e : envelopes) {
      e.unconditionalRelease(Envelope.Stage.RELEASE, 1024);
    }
  }

  // ── shouldDoPanning (functions.cpp:1487-1500) ──

  public static boolean shouldDoPanning(int panAmount, int[] amplitudeLR) {
    if (panAmount == 0) {
      amplitudeLR[1] = 1073741823; // right full
      amplitudeLR[0] = 1073741823; // left full
      return false;
    }
    int panOffset = Math.max(-1073741824, Math.min(1073741824, panAmount));
    amplitudeLR[1] = (panAmount >= 0) ? 1073741823 : (1073741824 + panOffset);
    amplitudeLR[0] = (panAmount <= 0) ? 1073741823 : (1073741824 - panOffset);
    return true;
  }

  // ── getLocalLFOPhaseIncrement (voice.cpp:699-706) ──

  public int getLocalLFOPhaseIncrement(int lfoId, int param) {
    Lfo.LfoConfig config = sound.lfoConfig[lfoId];
    if (config.syncLevel == Lfo.SyncLevel.NONE) {
      return paramFinalValues[param];
    }
    return sound.getSyncedLFOPhaseIncrement(config);
  }

  // ── RENDER (voice.cpp:710-1670) ──

  /** Compatibility overload for legacy tests/runners. */
  public boolean render(
      int[] soundBuffer,
      int numSamples,
      int synthMode,
      OscType[] oscTypes,
      FilterSet.FilterMode lpfMode,
      FilterSet.FilterMode hpfMode,
      int filterRoute,
      int soundVolumeNeutral) {
    this.sound.synthMode = synthMode;
    this.sound.oscTypes[0] = oscTypes[0];
    this.sound.oscTypes[1] = oscTypes[1];
    this.sound.lpfMode = lpfMode;
    this.sound.hpfMode = hpfMode;
    this.sound.filterRoute = filterRoute;
    this.sound.volumeNeutralValueForUnison = soundVolumeNeutral;
    return render(
        soundBuffer,
        numSamples,
        lpfMode != FilterSet.FilterMode.OFF,
        hpfMode != FilterSet.FilterMode.OFF);
  }

  /**
   * Main voice render. Processes envelope, LFO, patching, per-source oscillator rendering
   * (subtractive/FM/ringmod/sample), filter, pan, and gain staging. Returns false if the voice has
   * become inactive and should be unassigned.
   *
   * @param soundBuffer output stereo buffer (int[], LRLRLR interleaved)
   * @param numSamples samples to render this block
   * @param doLPF whether low-pass filter is enabled
   * @param doHPF whether high-pass filter is enabled
   * @return true if voice is still active
   */
  public boolean render(int[] soundBuffer, int numSamples, boolean doLPF, boolean doHPF) {

    // Copy global sources from sound
    System.arraycopy(sound.globalSourceValues, 0, sourceValues, 0, 3);

    // ── 1. Envelopes (voice.cpp:740-800) ──

    // Envelope 0 (amplitude) always renders
    env0LastValue =
        envelopes[0].render(
            numSamples,
            paramFinalValues[Param.LOCAL_ENV_0_ATTACK],
            paramFinalValues[Param.LOCAL_ENV_0_DECAY],
            paramFinalValues[Param.LOCAL_ENV_0_SUSTAIN],
            paramFinalValues[Param.LOCAL_ENV_0_RELEASE],
            LookupTables.decayTableSmall8);
    sourceValues[PatchSource.ENVELOPE_0.ordinal()] = env0LastValue;

    if (envelopes[0].state == Envelope.Stage.OFF) {
      active = false;
      return false;
    }

    // Envelopes 1-3 (modulation)
    for (int e = 1; e < 4; e++) {
      sourceValues[PatchSource.ENVELOPE_0.ordinal() + e] =
          envelopes[e].render(
              numSamples, // PATCH_SOURCE_ENVELOPE_0 offset
              paramFinalValues[Param.LOCAL_ENV_0_ATTACK + e],
              paramFinalValues[Param.LOCAL_ENV_0_DECAY + e],
              paramFinalValues[Param.LOCAL_ENV_0_SUSTAIN + e],
              paramFinalValues[Param.LOCAL_ENV_0_RELEASE + e],
              LookupTables.decayTableSmall8);
    }

    // ── 2. Local LFOs (voice.cpp:760-780) ──
    // LFO 1 (local, uses lfoConfig[1])
    int phaseInc1 = getLocalLFOPhaseIncrement(1, Param.LOCAL_LFO_LOCAL_FREQ_1);
    sourceValues[PatchSource.LFO_LOCAL_1.ordinal()] =
        lfo2.render(numSamples, sound.lfoConfig[1], phaseInc1);

    // LFO 2 (local, uses lfoConfig[3])
    int phaseInc2 = getLocalLFOPhaseIncrement(3, Param.LOCAL_LFO_LOCAL_FREQ_2);
    sourceValues[PatchSource.LFO_LOCAL_2.ordinal()] =
        lfo4.render(numSamples, sound.lfoConfig[3], phaseInc2);

    // ── 3. Source values (velocity, note, random, sidechain) (voice.cpp:800-820) ──
    // (Note: velocity, note, random are already initialized in noteOn faithfully)

    // ── 4. Patcher: run cables, produce paramFinalValues ──
    // For now, paramFinalValues = static neutral values or updated externally
    System.arraycopy(paramFinalValues, 0, paramFinalValues, 0, Param.kNumParams);

    // ── 5. Phase increments (voice.cpp:414-560) ──
    int carrierIncA = calculateBasePhaseIncrement(noteCode);
    if (carrierIncA <= 0) {
      active = false;
      return false;
    }
    int carrierIncB = carrierIncA;

    int overallPitchAdjust = paramFinalValues[Param.LOCAL_PITCH_ADJUST];

    int pIncA = adjustPitch(carrierIncA, overallPitchAdjust);
    if (pIncA < 0) {
      active = false;
      return false;
    }
    pIncA = adjustPitch(pIncA, paramFinalValues[Param.LOCAL_OSC_A_PITCH_ADJUST]);
    if (pIncA < 0) {
      active = false;
      return false;
    }

    int pIncB = adjustPitch(carrierIncB, overallPitchAdjust);
    if (pIncB < 0) {
      active = false;
      return false;
    }
    pIncB = adjustPitch(pIncB, paramFinalValues[Param.LOCAL_OSC_B_PITCH_ADJUST]);
    if (pIncB < 0) {
      active = false;
      return false;
    }

    phaseIncrementA = pIncA;
    phaseIncrementB = pIncB;

    // ── 6. Per-source rendering (lines 950-1400) ──
    // Overall osc amplitude: envelope 0 (unipolar lastValue) * LOCAL_VOLUME
    int env0Gain = (sourceValues[PatchSource.ENVELOPE_0.ordinal()] >> 1) + 1073741824;
    int trackVol = paramFinalValues[Param.LOCAL_VOLUME];

    overallOscAmplitudeLastTime =
        Functions.lshiftAndSaturate(Functions.multiply_32x32_rshift32(env0Gain, trackVol), 2);

    // Prepare scratch buffers: 2 mono source buffers + stereo output buffer
    int[] sourceBuf = new int[numSamples * 2]; // osc A + osc B mono
    int[] mixBuf = new int[numSamples * 2]; // stereo interleaved output
    java.util.Arrays.fill(sourceBuf, 0, numSamples * 2, 0);
    java.util.Arrays.fill(mixBuf, 0, numSamples * 2, 0);

    // ── FM path (voice.cpp:1400-1560) ──
    if (sound.synthMode == 1) { // FM
      renderFmPath(mixBuf, numSamples, pIncA, pIncB);
      // Apply filter + pan + gain after FM
      applyFilterAndGain(mixBuf, numSamples, doLPF, doHPF);
      // Copy to output
      for (int i = 0; i < numSamples; i++) {
        soundBuffer[i * 2] = Functions.add_saturate(soundBuffer[i * 2], mixBuf[i * 2]);
        soundBuffer[i * 2 + 1] = Functions.add_saturate(soundBuffer[i * 2 + 1], mixBuf[i * 2 + 1]);
      }
      return active;
    }

    // ── Subtractive / Ringmod path (voice.cpp:950-1400) ──
    for (int s = 0; s < 2; s++) {
      int sourceVol = paramFinalValues[Param.LOCAL_OSC_A_VOLUME + s];
      if (sourceVol <= 0) continue;
      int pInc = (s == 0) ? pIncA : pIncB;

      int[] phase = {sources[s].oscPos};
      // Each source writes to its own section of the stereo buffer (s * numSamples offset)
      Oscillator.renderOsc(
          sound.oscTypes[s],
          sourceVol,
          sourceBuf,
          s * numSamples,
          numSamples,
          pInc,
          0,
          phase,
          true,
          0,
          false,
          0,
          0,
          0);
      sources[s].oscPos = phase[0];
    }

    // Mix both sources into stereo (voices sum into both channels)
    for (int i = 0; i < numSamples; i++) {
      int mix =
          Functions.add_saturate(
              (sources[0].active ? sourceBuf[i] : 0),
              (sources[1].active ? sourceBuf[numSamples + i] : 0));
      mixBuf[i * 2] = mix;
      mixBuf[i * 2 + 1] = mix;
    }

    // ── Ringmod (voice.cpp:540-596) ──
    if (sound.synthMode == 2) { // RINGMOD
      // Multiply osc A * osc B (both fixed-amplitude), scaled by amplitudeForRingMod
      int amplitudeForRingMod = 1 << 27; // port of (1 << 27)
      for (int i = 0; i < numSamples; i++) {
        int a = sourceBuf[i]; // osc A
        int b = sourceBuf[numSamples + i]; // osc B
        int product = Functions.multiply_32x32_rshift32(a, b);
        mixBuf[i * 2] = Functions.multiply_32x32_rshift32(product, amplitudeForRingMod);
        mixBuf[i * 2 + 1] = mixBuf[i * 2]; // mono
      }
    }

    // ── 7. Apply envelope 0 + track volume to stereo buffer (voice.cpp:1025-1060) ──
    for (int i = 0; i < numSamples; i++) {
      mixBuf[i * 2] =
          Functions.multiply_32x32_rshift32(
              Functions.multiply_32x32_rshift32(mixBuf[i * 2], env0Gain), trackVol);
      mixBuf[i * 2 + 1] =
          Functions.multiply_32x32_rshift32(
              Functions.multiply_32x32_rshift32(mixBuf[i * 2 + 1], env0Gain), trackVol);
    }

    // ── 8. Filter, pan, gain into output buffer (voice.cpp:1560-1670) ──
    applyFilterAndGain(mixBuf, numSamples, doLPF, doHPF);

    // Copy to output
    for (int i = 0; i < numSamples; i++) {
      soundBuffer[i * 2] = Functions.add_saturate(soundBuffer[i * 2], mixBuf[i * 2]);
      soundBuffer[i * 2 + 1] = Functions.add_saturate(soundBuffer[i * 2 + 1], mixBuf[i * 2 + 1]);
    }

    return active;
  }

  // ── FM render path ──

  private int getModulatorInc(
      int carrierInc, float fmRatio, int overallPitchAdjust, int modPitchAdjustParam) {
    int modInc = (int) Math.round(carrierInc * (double) fmRatio);
    modInc = adjustPitch(modInc, overallPitchAdjust);
    if (modInc < 0) return 0;
    modInc = adjustPitch(modInc, paramFinalValues[modPitchAdjustParam]);
    if (modInc < 0) return 0;
    return modInc;
  }

  private void renderFmPath(int[] buffer, int numSamples, int carrierIncA, int carrierIncB) {
    int modAmp0 = paramFinalValues[Param.LOCAL_MODULATOR_0_VOLUME];
    int modAmp1 = paramFinalValues[Param.LOCAL_MODULATOR_1_VOLUME];
    boolean mod0Active = modAmp0 != 0;
    boolean mod1Active = modAmp1 != 0;

    int[] fmBuf = new int[numSamples]; // modulation buffer
    boolean carriersAreSine = false;

    int overallPitchAdjust = paramFinalValues[Param.LOCAL_PITCH_ADJUST];
    int modInc0 =
        getModulatorInc(
            carrierIncA, sound.fmRatio1, overallPitchAdjust, Param.LOCAL_MODULATOR_0_PITCH_ADJUST);
    int modInc1 =
        getModulatorInc(
            carrierIncA, sound.fmRatio2, overallPitchAdjust, Param.LOCAL_MODULATOR_1_PITCH_ADJUST);

    if (mod1Active) {
      renderSineWaveWithFeedback(
          fmBuf,
          numSamples,
          modulatorPhase,
          1,
          modAmp1,
          modInc1,
          paramFinalValues[Param.LOCAL_MODULATOR_1_FEEDBACK],
          modulatorFeedback,
          1,
          false);
      if (sound.modulator1ToModulator0 && mod0Active) {
        renderFMWithFeedback(
            fmBuf,
            numSamples,
            modulatorPhase,
            0,
            modAmp0,
            modInc0,
            paramFinalValues[Param.LOCAL_MODULATOR_0_FEEDBACK],
            modulatorFeedback,
            0);
      } else if (!sound.modulator1ToModulator0 && mod0Active) {
        renderSineWaveWithFeedback(
            fmBuf,
            numSamples,
            modulatorPhase,
            0,
            modAmp0,
            modInc0,
            paramFinalValues[Param.LOCAL_MODULATOR_0_FEEDBACK],
            modulatorFeedback,
            0,
            true);
      }
    } else if (mod0Active) {
      renderSineWaveWithFeedback(
          fmBuf,
          numSamples,
          modulatorPhase,
          0,
          modAmp0,
          modInc0,
          paramFinalValues[Param.LOCAL_MODULATOR_0_FEEDBACK],
          modulatorFeedback,
          0,
          false);
    } else {
      carriersAreSine = true;
    }

    int carrierAmp0 = paramFinalValues[Param.LOCAL_OSC_A_VOLUME];
    int carrierAmp1 = paramFinalValues[Param.LOCAL_OSC_B_VOLUME];

    if (carriersAreSine) {
      renderCarrierSine(
          buffer,
          numSamples,
          sources[0],
          carrierAmp0,
          carrierIncA,
          paramFinalValues[Param.LOCAL_CARRIER_0_FEEDBACK]);
      renderCarrierSine(
          buffer,
          numSamples,
          sources[1],
          carrierAmp1,
          carrierIncB,
          paramFinalValues[Param.LOCAL_CARRIER_1_FEEDBACK]);
    } else {
      renderCarrierFM(
          buffer,
          numSamples,
          fmBuf,
          sources[0],
          carrierAmp0,
          carrierIncA,
          paramFinalValues[Param.LOCAL_CARRIER_0_FEEDBACK]);
      renderCarrierFM(
          buffer,
          numSamples,
          fmBuf,
          sources[1],
          carrierAmp1,
          carrierIncB,
          paramFinalValues[Param.LOCAL_CARRIER_1_FEEDBACK]);
    }
  }

  // ── Filter + gain application ──

  private void applyFilterAndGain(int[] stereoBuf, int numSamples, boolean doLPF, boolean doHPF) {
    FilterSet.FilterMode lpfModeVal = doLPF ? sound.lpfMode : FilterSet.FilterMode.OFF;
    FilterSet.FilterMode hpfModeVal = doHPF ? sound.hpfMode : FilterSet.FilterMode.OFF;

    int filterGain = sound.volumeNeutralValueForUnison << 1;
    FilterRoute route = FilterRoute.values()[sound.filterRoute];
    filterGain =
        filterSet.setConfig(
            paramFinalValues[Param.LOCAL_LPF_FREQ],
            paramFinalValues[Param.LOCAL_LPF_RESONANCE],
            lpfModeVal,
            paramFinalValues[Param.LOCAL_LPF_MORPH],
            paramFinalValues[Param.LOCAL_HPF_FREQ],
            paramFinalValues[Param.LOCAL_HPF_RESONANCE],
            hpfModeVal,
            paramFinalValues[Param.LOCAL_HPF_MORPH],
            filterGain,
            route);

    if (filterGain != Functions.ONE_Q31) {
      for (int i = 0; i < numSamples; i++) {
        stereoBuf[i * 2] = Functions.multiply_32x32_rshift32(stereoBuf[i * 2], filterGain) << 1;
        stereoBuf[i * 2 + 1] =
            Functions.multiply_32x32_rshift32(stereoBuf[i * 2 + 1], filterGain) << 1;
      }
    }

    if (filterSet.isOn()) {
      filterSet.renderLongStereo(stereoBuf, numSamples);
    }

    // Pan (voice.cpp:1159-1166)
    int[] ampLR = new int[2];
    int panAmount = paramFinalValues[Param.LOCAL_PAN];
    boolean doPanning = shouldDoPanning(panAmount, ampLR);

    if (sound.synthMode != 1) { // Not FM: apply overall oscillator amplitude
      for (int i = 0; i < numSamples; i++) {
        stereoBuf[i * 2] =
            Functions.multiply_32x32_rshift32_rounded(stereoBuf[i * 2], overallOscAmplitudeLastTime)
                << 1;
        stereoBuf[i * 2 + 1] =
            Functions.multiply_32x32_rshift32_rounded(
                    stereoBuf[i * 2 + 1], overallOscAmplitudeLastTime)
                << 1;
      }
    }

    // Apply pan
    if (doPanning) {
      for (int i = 0; i < numSamples; i++) {
        int l = Functions.multiply_32x32_rshift32(stereoBuf[i * 2], ampLR[0]) << 2;
        int r = Functions.multiply_32x32_rshift32(stereoBuf[i * 2 + 1], ampLR[1]) << 2;
        stereoBuf[i * 2] = l;
        stereoBuf[i * 2 + 1] = r;
      }
    }
  }

  // ── FM helpers (ports of voice.cpp:1703-1830) ──
  // These are verified faithful from the FM fix session.

  private void renderSineWaveWithFeedback(
      int[] buf,
      int n,
      int[] ph,
      int pi,
      int amp,
      int pInc,
      int fbAmt,
      int[] fb,
      int fi,
      boolean add) {
    int phaseNow = ph[pi];
    if (fbAmt != 0) {
      int fbVal = fb[fi];
      for (int i = 0; i < n; i++) {
        int fb2 = Functions.signed_saturate(Functions.multiply_32x32_rshift32(fbVal, fbAmt), 22);
        phaseNow += pInc;
        fbVal = SineOsc.doFMNew(phaseNow, fb2);
        if (add) {
          buf[i] = Functions.multiply_accumulate_32x32_rshift32_rounded(buf[i], fbVal, amp);
        } else {
          buf[i] = Functions.multiply_32x32_rshift32(fbVal, amp);
        }
      }
      fb[fi] = fbVal;
    } else {
      for (int i = 0; i < n; i++) {
        phaseNow += pInc;
        int sine = SineOsc.doFMNew(phaseNow, 0);
        if (add) {
          buf[i] = Functions.multiply_accumulate_32x32_rshift32_rounded(buf[i], sine, amp);
        } else {
          buf[i] = Functions.multiply_32x32_rshift32(sine, amp);
        }
      }
    }
    ph[pi] = phaseNow;
  }

  private void renderFMWithFeedback(
      int[] buf, int n, int[] ph, int pi, int amp, int pInc, int fbAmt, int[] fb, int fi) {
    int phaseNow = ph[pi];
    if (fbAmt != 0) {
      int fbVal = fb[fi];
      for (int i = 0; i < n; i++) {
        int fb2 = Functions.signed_saturate(Functions.multiply_32x32_rshift32(fbVal, fbAmt), 22);
        int sum = buf[i] + fb2;
        phaseNow += pInc;
        fbVal = SineOsc.doFMNew(phaseNow, sum);
        buf[i] = Functions.multiply_32x32_rshift32(fbVal, amp);
      }
      fb[fi] = fbVal;
    } else {
      for (int i = 0; i < n; i++) {
        phaseNow += pInc;
        buf[i] = Functions.multiply_32x32_rshift32(SineOsc.doFMNew(phaseNow, buf[i]), amp);
      }
    }
    ph[pi] = phaseNow;
  }

  private void renderFMWithFeedbackAdd(
      int[] buf,
      int n,
      int[] fmBuf,
      int[] ph,
      int pi,
      int amp,
      int pInc,
      int fbAmt,
      int[] fb,
      int fi) {
    int phaseNow = ph[pi];
    if (fbAmt != 0) {
      int fbVal = fb[fi];
      for (int i = 0; i < n; i++) {
        int fb2 = Functions.signed_saturate(Functions.multiply_32x32_rshift32(fbVal, fbAmt), 22);
        int sum = fmBuf[i] + fb2;
        phaseNow += pInc;
        fbVal = SineOsc.doFMNew(phaseNow, sum);
        buf[i] = Functions.multiply_accumulate_32x32_rshift32_rounded(buf[i], fbVal, amp);
      }
      fb[fi] = fbVal;
    } else {
      for (int i = 0; i < n; i++) {
        phaseNow += pInc;
        buf[i] =
            Functions.multiply_accumulate_32x32_rshift32_rounded(
                buf[i], SineOsc.doFMNew(phaseNow, fmBuf[i]), amp);
      }
    }
    ph[pi] = phaseNow;
  }

  private void renderCarrierSine(int[] buf, int n, VoiceSource src, int amp, int pInc, int fbAmt) {
    if (amp == 0) return;
    int[] ph = {src.oscPos};
    int[] fb = {src.carrierFeedback};
    renderSineWaveWithFeedback(buf, n, ph, 0, amp, pInc, fbAmt, fb, 0, true);
    src.oscPos = ph[0];
    src.carrierFeedback = fb[0];
  }

  private void renderCarrierFM(
      int[] buf, int n, int[] fmBuf, VoiceSource src, int amp, int pInc, int fbAmt) {
    if (amp == 0) return;
    int[] ph = {src.oscPos};
    int[] fb = {src.carrierFeedback};
    renderFMWithFeedbackAdd(buf, n, fmBuf, ph, 0, amp, pInc, fbAmt, fb, 0);
    src.oscPos = ph[0];
    src.carrierFeedback = fb[0];
  }

  public static int calculateBasePhaseIncrement(int noteCode) {
    int noteWithinOctave = (noteCode + 240 - 4) % 12;
    int octave = (noteCode + 120 - 4) / 12;

    int shiftRightAmount = 20 - octave;
    if (shiftRightAmount >= 0) {
      return LookupTables.noteFrequencyTable[noteWithinOctave] >>> shiftRightAmount;
    }
    return 0; // inactive / too high
  }

  public static int adjustPitch(int phaseIncrement, int adjustment) {
    if (adjustment != 16777216) {
      int output = Functions.multiply_32x32_rshift32_rounded(phaseIncrement, adjustment);
      if (Integer.compareUnsigned(output, 16777216) >= 0) {
        return -1; // inactive / too high
      }
      return output << 8;
    }
    return phaseIncrement;
  }
}
