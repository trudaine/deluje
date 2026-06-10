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

  /** C: voice.h:70 — {@code inputCharacteristics[2]} (NOTE, CHANNEL). */
  public final int[] inputCharacteristics = {60, -1};

  // Phase increments per source
  public int phaseIncrementA;
  public int phaseIncrementB;

  // Envelope array (4 per voice: amp + 3 modulation)
  public final Envelope[] envelopes = new Envelope[4];

  /** Test seam: override osc1 initial phase. -2 = use random (default). */
  public static int testStartPhaseOverrideOsc1 = -2;

  /** Test seam: override osc2 initial phase. -2 = use random (default). */
  public static int testStartPhaseOverrideOsc2 = -2;

  // Per-source oscillators (2 sources: osc A, osc B)
  public final VoiceSource[] sources = new VoiceSource[2];

  // Param final values (output of patcher)
  public final int[] paramFinalValues = new int[Param.kNumParams];

  // Source values for patcher
  public final int[] sourceValues = new int[32]; // PatchSource count

  // ── MPE / expression (voice.h:59-61) ──

  /**
   * C: voice.h:59 — {@code std::bitset<kNumExpressionDimensions>
   * expressionSourcesCurrentlySmoothing;}.
   */
  public int expressionSourcesCurrentlySmoothing;

  /**
   * C: voice.h:60 — {@code std::bitset<kNumExpressionDimensions>
   * expressionSourcesFinalValueChanged;}.
   */
  public int expressionSourcesFinalValueChanged;

  /**
   * C: voice.h:61 — {@code std::array<int32_t, kNumExpressionDimensions>
   * localExpressionSourceValuesBeforeSmoothing;}.
   */
  public final int[] localExpressionSourceValuesBeforeSmoothing = new int[3];

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

  // Per-source DX7 voices (mirror voice.cpp unisonParts[u].sources[s].dxVoice; OscType::DX7).
  public final Dx7Voice[] dxVoice = {new Dx7Voice(), new Dx7Voice()};
  public final Dx7Voice.DxPatch[] dxPatch = {new Dx7Voice.DxPatch(), new Dx7Voice.DxPatch()};

  public Voice(Sound sound) {
    this.sound = sound;
    for (int i = 0; i < envelopes.length; i++) envelopes[i] = new Envelope();
    for (int i = 0; i < sources.length; i++) sources[i] = new VoiceSource();
    // Seed paramFinalValues with the static per-param neutral (C: paramNeutralValues[p] =
    // getParamNeutralValue(p), functions.cpp:175-181). This is only a placeholder:
    // performInitialPatching (FirmwareSound:520) recomputes EVERY param before the first render
    // (C: voice.cpp:201), so the seed never reaches audio. (fw2 Sound has no paramNeutralValues
    // field — the knobs live in the bridge as patchedParamValues.)
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

    // Sample playback (when oscType == SAMPLE or source is sample-based).
    public final VoiceSample voiceSample = new VoiceSample();
    public Sample sampleRef; // the fw2 Sample backing this source
    public int timeStretchRatio = 16777216; // 1 << 24 ≡ 1.0 (no time-stretch)

    public void setupSample(Sample fw2Sample, int startFrame, int playDirection) {
      sampleRef = fw2Sample;
      voiceSample.active = false; // reset any prior time-stretch state
      voiceSample.setup(fw2Sample, startFrame, playDirection);
    }

    public void setupSampleTimeStretch(
        Sample fw2Sample, int startFrame, int playDirection, int tsRatio) {
      sampleRef = fw2Sample;
      timeStretchRatio = tsRatio;
      voiceSample.setupTimeStretch(fw2Sample, startFrame, playDirection);
    }
  }

  // ── combineExpressionValues (voice.cpp:78-83) ──

  /**
   * C: voice.cpp:78-83 — combines synth-level and voice-level expression values.
   *
   * <pre>
   * int32_t synthLevelValue = sound.monophonicExpressionValues[expressionDimension];
   * int32_t voiceLevelValue = this->localExpressionSourceValuesBeforeSmoothing[expressionDimension];
   * int32_t combinedValue = (synthLevelValue >> 1) + (voiceLevelValue >> 1);
   * return lshiftAndSaturate&lt;1&gt;(combinedValue);
   * </pre>
   */
  public int combineExpressionValues(Sound sound, int expressionDimension) {
    int synthLevelValue = sound.monophonicExpressionValues[expressionDimension];
    int voiceLevelValue = localExpressionSourceValuesBeforeSmoothing[expressionDimension];
    int combinedValue = (synthLevelValue >> 1) + (voiceLevelValue >> 1);
    return Functions.lshiftAndSaturate(combinedValue, 1);
  }

  // ── expressionEventImmediate (voice.cpp:340-346) ──

  /** C: voice.cpp:340-346 — immediate expression update (no smoothing). */
  public void expressionEventImmediate(Sound sound, int voiceLevelValue, int s) {
    int expressionDimension = s - PatchSource.X.ordinal();
    localExpressionSourceValuesBeforeSmoothing[expressionDimension] = voiceLevelValue;
    expressionSourcesFinalValueChanged |= (1 << expressionDimension);
    sourceValues[s] = combineExpressionValues(sound, expressionDimension);
  }

  // ── expressionEventSmooth (voice.cpp:348-352) ──

  /** C: voice.cpp:348-352 — smooth expression update. */
  public void expressionEventSmooth(int newValue, int s) {
    int expressionDimension = s - PatchSource.X.ordinal();
    localExpressionSourceValuesBeforeSmoothing[expressionDimension] = newValue;
    expressionSourcesCurrentlySmoothing |= (1 << expressionDimension);
  }

  // ── noteOn (voice.cpp:110-339) ──

  /** C: voice.cpp:110-339. Simplified signature — full C signature has more params. */
  public void noteOn(int midiNote, int velocity, int fromMidiChannel, int[] mpeValues) {
    // C: voice.cpp:117 — inputCharacteristics[NOTE] = newNoteCodeBeforeArpeggiation
    inputCharacteristics[0] = midiNote;
    // C: voice.cpp:118 — inputCharacteristics[CHANNEL] = newFromMIDIChannel
    inputCharacteristics[1] = fromMidiChannel;

    filterSet.reset();
    this.note = midiNote;
    this.noteCode = midiNote;
    this.velocity = velocity;
    this.active = true;
    for (Envelope e : envelopes) e.noteOn(false);
    for (VoiceSource s : sources) {
      s.active = true;
      int ovr = (s == sources[0]) ? testStartPhaseOverrideOsc1 : testStartPhaseOverrideOsc2;
      s.oscPos = (ovr != -2) ? ovr : (Functions.getNoise() & 0x7FFFFFFF); // random initial phase
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

    // C: voice.cpp:165-168 — MPE expression sources
    if (mpeValues != null) {
      for (int m = 0; m < 3; m++) {
        localExpressionSourceValuesBeforeSmoothing[m] = mpeValues[m] << 16;
        sourceValues[PatchSource.X.ordinal() + m] = combineExpressionValues(sound, m);
      }
    } else {
      for (int m = 0; m < 3; m++) {
        localExpressionSourceValuesBeforeSmoothing[m] = 0;
        sourceValues[PatchSource.X.ordinal() + m] = combineExpressionValues(sound, m);
      }
    }

    // Per-source DX7 init (sources[s].oscType == OscType::DX7 -> set up dxVoice).
    for (int s = 0; s < 2; s++) {
      if (sound.oscTypes[s] == OscType.DX7 && sound.sourceDx7Patch[s] != null) {
        System.arraycopy(sound.sourceDx7Patch[s], 0, dxPatch[s].params, 0, 156);
        dxPatch[s].updateEngineMode(); // select modern vs MkI from the loaded algo/feedback
        dxVoice[s].init(dxPatch[s], midiNote, velocity);
      }
    }
  }

  /** Backward-compat overload. */
  public void noteOn(int midiNote, int velocity) {
    noteOn(midiNote, velocity, -1, null);
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

    // ── 2.5 MPE expression smoothing (voice.cpp:779-804) ──
    expressionSourcesCurrentlySmoothing |= sound.expressionSourcesChangedAtSynthLevel;
    if (expressionSourcesCurrentlySmoothing != 0) {
      expressionSourcesFinalValueChanged |= expressionSourcesCurrentlySmoothing;
      for (int i = 0; i < 3; i++) {
        if ((expressionSourcesCurrentlySmoothing & (1 << i)) != 0) {
          int targetValue = combineExpressionValues(sound, i);
          int diff = (targetValue >> 8) - (sourceValues[PatchSource.X.ordinal() + i] >> 8);
          if (diff == 0) {
            expressionSourcesCurrentlySmoothing &= ~(1 << i);
          } else {
            int amountToAdd = diff * numSamples / 4;
            sourceValues[PatchSource.X.ordinal() + i] += amountToAdd;
          }
        }
      }
    }
    // C: voice.cpp:804 — expressionSourcesFinalValueChanged.reset() (done after patching would use
    // it)

    // ── 3. Source values (velocity, note, random, sidechain) (voice.cpp:800-820) ──
    // (Note: velocity, note, random are already initialized in noteOn faithfully)

    // ── 4. Patcher: apply cable modulation on top of the curve-applied base
    //       (the base is set by performInitialPatching in the driver each block).
    //       (patcher.cpp performPatching) ──
    Patcher.performPatching(
        sound.patchedParamValues, sourceValues, sound.patchCableSet, paramFinalValues);

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

    // Prepare the filters and the makeup gain (voice.cpp:991-997). Configure ONCE here; filterGain
    // is
    // folded into the per-source amplitudes below (subtractive), and overallOscAmplitude is applied
    // AFTER the filter in applyFilterAndGain. "Level adjustment for unison happens *before* the
    // filter."
    FilterSet.FilterMode lpfModeVal = doLPF ? sound.lpfMode : FilterSet.FilterMode.OFF;
    FilterSet.FilterMode hpfModeVal = doHPF ? sound.hpfMode : FilterSet.FilterMode.OFF;
    int filterGain =
        filterSet.setConfig(
            paramFinalValues[Param.LOCAL_LPF_FREQ],
            paramFinalValues[Param.LOCAL_LPF_RESONANCE],
            lpfModeVal,
            paramFinalValues[Param.LOCAL_LPF_MORPH],
            paramFinalValues[Param.LOCAL_HPF_FREQ],
            paramFinalValues[Param.LOCAL_HPF_RESONANCE],
            hpfModeVal,
            paramFinalValues[Param.LOCAL_HPF_MORPH],
            sound.volumeNeutralValueForUnison << 1,
            FilterRoute.values()[sound.filterRoute]);
    boolean hasFilters =
        (lpfModeVal != FilterSet.FilterMode.OFF) || (hpfModeVal != FilterSet.FilterMode.OFF);

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

    // ── Source rendering + mix (voice.cpp:1039-1052, 1260-1370) ──
    if (sound.synthMode == 2) {
      // RINGMOD (voice.cpp:1309-1370): render BOTH oscs at FIXED amplitude (applyAmplitude=false),
      // then ring-modulate, scaled by amplitudeForRingMod. overallOscAmplitude is applied AFTER the
      // filter (in applyFilterAndGain), same as subtractive.
      int amplitudeForRingMod = 1 << 27;
      if (hasFilters) {
        amplitudeForRingMod =
            Functions.multiply_32x32_rshift32_rounded(amplitudeForRingMod, filterGain) << 4;
      }
      for (int s = 0; s < 2; s++) {
        int pInc = (s == 0) ? pIncA : pIncB;
        int pulseWidth =
            Functions.lshiftAndSaturate(paramFinalValues[Param.LOCAL_OSC_A_PHASE_WIDTH + s], 1);
        int[] phase = {sources[s].oscPos};
        Oscillator.renderOsc(
            sound.oscTypes[s],
            0,
            sourceBuf,
            s * numSamples,
            numSamples,
            pInc,
            pulseWidth,
            phase,
            false,
            0,
            false,
            0,
            0,
            0);
        sources[s].oscPos = phase[0];
        // Sine/triangle come out bigger in fixed-amplitude rendering; compensate for the others.
        OscType ot = sound.oscTypes[s];
        if (ot == OscType.SAW || ot == OscType.ANALOG_SAW_2) {
          amplitudeForRingMod <<= 1;
        } else if (ot == OscType.WAVETABLE) {
          amplitudeForRingMod <<= 2;
        }
      }
      for (int i = 0; i < numSamples; i++) {
        int out =
            Functions.multiply_32x32_rshift32_rounded(
                Functions.multiply_32x32_rshift32(sourceBuf[i], sourceBuf[numSamples + i]),
                amplitudeForRingMod);
        mixBuf[i * 2] = out;
        mixBuf[i * 2 + 1] = out;
      }
    } else {
      // SUBTRACTIVE (voice.cpp:1042-1049): source-volume scaling applied during osc render
      // (>>4 no-filter, or *filterGain with filter); overallOscAmplitude applied AFTER the filter.
      for (int s = 0; s < 2; s++) {
        int srcAmp =
            hasFilters
                ? Functions.multiply_32x32_rshift32_rounded(
                    paramFinalValues[Param.LOCAL_OSC_A_VOLUME + s], filterGain)
                : paramFinalValues[Param.LOCAL_OSC_A_VOLUME + s] >> 4;
        if (srcAmp <= 0 && sources[s].sampleRef == null) continue;
        int pInc = (s == 0) ? pIncA : pIncB;

        // Sample playback path (bypasses oscillator when a Sample is attached to this source).
        if (sources[s].sampleRef != null) {
          VoiceSample vs = sources[s].voiceSample;
          // Kit drums zero LOCAL_OSC_A_VOLUME to silence the oscillator — samples need their
          // own baseline amplitude. The sample reader applies its own per-sample scaling; this
          // is the "source gain" before the voice's overall amplitude chain, equivalent to the
          // per-source volume for oscillators. Minimum = 1 << 26 (unity through the >> 4 path).
          int sampAmp = (srcAmp > 0)
              ? srcAmp
              : Math.max(paramFinalValues[Param.LOCAL_OSC_A_VOLUME + s] >> 4, 1 << 26);
          if (vs.timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_NEWER]
              || vs.timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_OLDER]) {
            // ── Time-stretch path (two-head crossfade, pitch decoupled from speed) ──
            int[] tsBuf = new int[numSamples * 2]; // stereo
            int[] ampArr = {0};
            vs.renderTimeStretched(
                tsBuf,
                numSamples,
                2,
                pInc,
                /*timeStretchRatio*/ sources[s].timeStretchRatio,
                ampArr,
                0);
            for (int i = 0; i < numSamples; i++) {
              int mono = (tsBuf[i * 2] + tsBuf[i * 2 + 1]) >> 1; // downmix to mono
              sourceBuf[s * numSamples + i] = Functions.multiply_32x32_rshift32(mono, sampAmp) << 2;
            }
            if (!vs.active) sources[s].active = false;
          } else if (vs.active) {
            // ── Pitched path (full-precision windowed-sinc, no time-stretch) ──
            int[] sampBuf = new int[numSamples];
            int[] ampArr = {0};
            vs.render(sampBuf, numSamples, 1, pInc, ampArr, 0);
            for (int i = 0; i < numSamples; i++) {
              sourceBuf[s * numSamples + i] =
                  Functions.multiply_32x32_rshift32(sampBuf[i], sampAmp) << 2;
            }
            if (!vs.active) sources[s].active = false;
          }
          continue;
        }

        if (srcAmp <= 0) continue;
        if (sound.oscTypes[s] == OscType.DX7) {
          // voice.cpp:2360-2387 — per-source DX7. adjpitch = (int)(log2f(phaseIncrement)*(1<<24)) -
          // 278023814; ctrl.ampmod = LOCAL_OSC_A_PHASE_WIDTH[s] >> 13;
          // dxVoice->compute(uniBuf,...);
          // then oscBuffer += multiply_32x32_rshift32(uniBuf[i], sourceAmplitude) << 6.
          if (dxVoice[s].patch == null && sound.sourceDx7Patch[s] != null) {
            // chuckjava sets oscType=DX7 per render block (after noteOn), so init the DxVoice here.
            System.arraycopy(sound.sourceDx7Patch[s], 0, dxPatch[s].params, 0, 156);
            dxPatch[s].updateEngineMode();
            dxVoice[s].init(dxPatch[s], note, velocity);
          }
          if (dxVoice[s].patch == null) continue; // no patch -> nothing to render
          int logpitch = (int) (Math.log(pInc & 0xFFFFFFFFL) / Math.log(2.0) * (1 << 24));
          int adjpitch = logpitch - 278023814;
          int ampMod = paramFinalValues[Param.LOCAL_OSC_A_PHASE_WIDTH + s] >> 13;
          dxPatch[s].computeLfo(numSamples);
          int[] uniBuf = new int[numSamples];
          dxVoice[s].compute(uniBuf, numSamples, adjpitch, dxPatch[s], ampMod, 0, 0);
          for (int i = 0; i < numSamples; i++) {
            sourceBuf[s * numSamples + i] =
                Functions.multiply_32x32_rshift32(uniBuf[i], srcAmp) << 6;
          }
          continue;
        }
        int[] phase = {sources[s].oscPos};
        Oscillator.renderOsc(
            sound.oscTypes[s],
            srcAmp,
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
    }

    // ── 7+8. Filter, pan, overall amplitude into output buffer (voice.cpp:1560-1670) ──
    // NOTE: the overall oscillator amplitude (env0 * LOCAL_VOLUME) is applied ONCE, inside
    // applyFilterAndGain (overallOscAmplitudeLastTime). The C folds it into sourceAmplitude and
    // applies it during osc render; here it is applied to the summed mix instead — same single
    // application. (A previous separate env0*trackVol step here double-applied volume → silence.)
    applyFilterAndGain(mixBuf, numSamples, doLPF, doHPF);

    // Copy to output
    for (int i = 0; i < numSamples; i++) {
      soundBuffer[i * 2] = Functions.add_saturate(soundBuffer[i * 2], mixBuf[i * 2]);
      soundBuffer[i * 2 + 1] = Functions.add_saturate(soundBuffer[i * 2 + 1], mixBuf[i * 2 + 1]);
    }

    // Port of voice.cpp:1664 — return false if voice should be unassigned
    // unassignVoiceAfter = envelope OFF OR (past DECAY AND env source == MIN)
    boolean unassignVoiceAfter =
        (envelopes[0].state == Envelope.Stage.OFF)
            || (envelopes[0].state.compareTo(Envelope.Stage.DECAY) > 0
                && sourceValues[PatchSource.ENVELOPE_0.ordinal()] == Integer.MIN_VALUE);
    if (unassignVoiceAfter) active = false;
    return !unassignVoiceAfter;
  }

  // ── FM render path ──

  private int getModulatorInc(int m, int overallPitchAdjust) {
    // voice.cpp:533-553 — modulator phase increment from the note frequency table + the modulator's
    // semitone transpose, then a cents detune. NOT carrierInc*ratio (that was a float
    // reconstruction).
    int modInc = calculateBasePhaseIncrement(noteCode + sound.modulatorTranspose[m]);
    if (modInc <= 0) return 0; // shiftRightAmount < 0 → too high; C marks it inactive
    modInc = sound.modulatorTransposers[m].detune(modInc); // cents
    // voice.cpp:1387-1401 — overall pitch adjust (incl. bend) + per-modulator pitch adjust param.
    modInc = adjustPitch(modInc, overallPitchAdjust);
    if (modInc < 0) return 0;
    modInc = adjustPitch(modInc, paramFinalValues[Param.LOCAL_MODULATOR_0_PITCH_ADJUST + m]);
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
    int modInc0 = getModulatorInc(0, overallPitchAdjust);
    int modInc1 = getModulatorInc(1, overallPitchAdjust);

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

    // FM (voice.cpp:1024-1037): fold overallOscAmplitude (env0 * LOCAL_VOLUME) into the carrier
    // amplitudes with the unison level adjustment, capped at 134217727. For FM the overall amp is
    // applied HERE (applyFilterAndGain skips the post-filter overall-amp for synthMode==FM).
    int overallForCarriers =
        Functions.multiply_32x32_rshift32_rounded(
                overallOscAmplitudeLastTime, sound.volumeNeutralValueForUnison)
            << 3;
    int carrierAmp0 =
        Math.min(
            Functions.multiply_32x32_rshift32(
                paramFinalValues[Param.LOCAL_OSC_A_VOLUME], overallForCarriers),
            134217727);
    int carrierAmp1 =
        Math.min(
            Functions.multiply_32x32_rshift32(
                paramFinalValues[Param.LOCAL_OSC_B_VOLUME], overallForCarriers),
            134217727);

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
    // The filter is already configured and its makeup gain (filterGain) already folded into the
    // source
    // amplitudes in render() (voice.cpp folds it into sourceAmplitudes, not the buffer). Here we
    // just
    // run the filter, then apply overallOscAmplitude AFTER the filter (non-FM) and pan.
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
