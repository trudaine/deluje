package org.deluge.firmware2;

import org.deluge.firmware2.Oscillator.OscType;

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

  // Envelope array (4 per voice: amp + 3 modulation)
  public final Envelope[] envelopes = new Envelope[4];

  /** Test seam: override osc1 initial phase. -2 = use random (default). */
  public static final java.util.concurrent.atomic.AtomicInteger testStartPhaseOverrideOsc1 =
      new java.util.concurrent.atomic.AtomicInteger(-2);

  /** Test seam: override osc2 initial phase. -2 = use random (default). */
  public static final java.util.concurrent.atomic.AtomicInteger testStartPhaseOverrideOsc2 =
      new java.util.concurrent.atomic.AtomicInteger(-2);

  /**
   * Test seam: native-FM modulation-index multiplier (1.0 = faithful, no effect). Lets
   * FmIndexAbHarness sweep the FM depth to calibrate against hardware references. NOT used in
   * production rendering.
   */
  public static volatile double testFmIndexScale = 1.0;

  // Unison parts and per-source oscillators
  public final VoiceUnisonPart[] unisonParts = new VoiceUnisonPart[Sound.kMaxNumVoicesUnison];
  public final VoiceSource[] sources;

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

  // FM modulator state (increments and phase are now in unisonParts)
  public int[] modulatorAmplitudeLastTime = {0, 0};
  public int[] modulatorAmplitudeIncrements = new int[2];
  public int[] sourceAmplitudesLastTime = {0, 0};

  // Filter set
  public final FilterSet filterSet = new FilterSet();

  // Pan
  public int panAmplitudeL;
  public int panAmplitudeR;

  // Overall oscillator amplitude (envelope 0 * volume)
  public int overallOscAmplitudeLastTime;
  public int overallOscillatorAmplitudeIncrement;
  public boolean doneFirstRender;

  /** C: voice.h:76 — per-channel anti-aliased tanh state for the saturation/clipping stage. */
  public final int[] lastSaturationTanHWorkingValue = new int[2];

  // Scratch buffers for audio rendering (pre-allocated to prevent GC churn)
  private int[] mixBuf = new int[256];
  private int[] tempBufA = new int[128];
  private int[] tempBufB = new int[128];
  private int[] tempBuf = new int[128];
  private int[] uniBuf = new int[128];
  private int[] fmBuf = new int[128];
  private int[] fmOscBuffer = new int[128];
  private final int[] tempAmpLR = new int[2];
  private final int[] oscSyncPos = new int[Sound.kMaxNumVoicesUnison];
  private final int[] oscSyncPhaseIncrement = new int[Sound.kMaxNumVoicesUnison];

  /** C: voice.h:73 — portamento envelope position; 0xFFFFFFFF = no porta (voice.cpp:190). */
  public int portaEnvelopePos = 0xFFFFFFFF;

  /**
   * C: voice.h:74 — pitch span of the porta glide (previous note's increment − kMaxSampleValue).
   */
  public int portaEnvelopeMaxAmplitude;

  public int overallPitchAdjust = Functions.K_MAX_SAMPLE_VALUE;

  // For the envelope center (return value)
  public int env0LastValue;

  public final Sound sound;
  public final Lfo lfo2 = new Lfo();
  public final Lfo lfo4 = new Lfo();

  public Voice(Sound sound) {
    this.sound = sound;
    for (int i = 0; i < envelopes.length; i++) envelopes[i] = new Envelope();
    for (int i = 0; i < unisonParts.length; i++) {
      unisonParts[i] = new VoiceUnisonPart();
      for (int s = 0; s < 2; s++) {
        unisonParts[i].sources[s].voiceRef = this;
        unisonParts[i].sources[s].sourceIdx = s;
      }
    }
    this.sources = unisonParts[0].sources;

    // Seed paramFinalValues with the static per-param neutral
    for (int i = 0; i < Param.kNumParams; i++) {
      paramFinalValues[i] = Functions.getParamNeutralValue(i);
    }
  }

  public Voice() {
    this(new Sound());
  }

  // ── VoiceSource (port of VoiceUnisonPartSource) ──

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

    Patcher.performInitialPatching(sound, sourceValues, paramFinalValues);
    for (int e = 0; e < envelopes.length; e++) {
      sourceValues[PatchSource.ENVELOPE_0.ordinal() + e] = envelopes[e].noteOn(e, sound, this);
    }

    // C: a freshly acquired voice randomizes all part phases (Voice::randomizeOscPhases,
    // voice.cpp:399-411, called from sound.cpp:1654); noteOn then pins any source/modulator with a
    // retrigger phase set (voice_unison_part_source.cpp:79-82, voice.cpp:319-327). The test
    // override pins everything to a fixed phase for deterministic renders.
    for (int u = 0; u < sound.numUnison; u++) {
      for (int s = 0; s < 2; s++) {
        VoiceSource vs = unisonParts[u].sources[s];
        vs.active = true;
        int ovr = (s == 0) ? testStartPhaseOverrideOsc1.get() : testStartPhaseOverrideOsc2.get();
        vs.oscPos = (ovr != -2) ? ovr : Functions.getNoise(); // random initial phase
        // C vups:79-82 — retrigger overrides the random phase: zero-phase base for the wave type
        // plus the configured offset. 0xFFFFFFFF (-1) = off.
        if (sound.oscRetriggerPhase[s] != 0xFFFFFFFF) {
          vs.oscPos =
              Functions.getOscInitialPhaseForZero(sound.oscTypes[s]) + sound.oscRetriggerPhase[s];
        }
        vs.carrierFeedback = 0;
      }
      for (int m = 0; m < 2; m++) {
        // C voice.cpp:405-407 — FM modulator phases are random on a fresh voice; pinned to 0 under
        // the test override (so FM fidelity tests stay deterministic).
        unisonParts[u].modulatorPhase[m] =
            (testStartPhaseOverrideOsc1.get() != -2) ? 0 : Functions.getNoise();
        // C voice.cpp:321-324 — modulator retrigger: getOscInitialPhaseForZero(SINE)=0 + offset.
        if (sound.synthMode == 1 && sound.modulatorRetriggerPhase[m] != 0xFFFFFFFF) {
          unisonParts[u].modulatorPhase[m] = sound.modulatorRetriggerPhase[m];
        }
        unisonParts[u].modulatorFeedback[m] = 0;
      }
    }
    overallOscAmplitudeLastTime = 0;
    modulatorAmplitudeLastTime[0] = 0;
    modulatorAmplitudeLastTime[1] = 0;
    sourceAmplitudesLastTime[0] = 0;
    sourceAmplitudesLastTime[1] = 0;
    doneFirstRender = false;
    // C voice.cpp:178-179 — tanh saturation state starts at the table's zero point (2147483648u).
    lastSaturationTanHWorkingValue[0] = 0x80000000;
    lastSaturationTanHWorkingValue[1] = 0x80000000;

    // Portamento (voice.cpp:190, 372-374): off unless the UNPATCHED_PORTAMENTO knob is set and
    // there is a previous note to glide from.
    portaEnvelopePos = 0xFFFFFFFF;
    if (sound.portamentoKnob != Integer.MIN_VALUE && sound.lastNoteCode != Integer.MIN_VALUE) {
      setupPorta();
    }
    sound.lastNoteCode = midiNote; // C sound.cpp:1681 — stored for the NEXT note's porta

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

    // Per-source DX7 init
    for (int u = 0; u < sound.numUnison; u++) {
      for (int s = 0; s < 2; s++) {
        VoiceSource vs = unisonParts[u].sources[s];
        if (sound.oscTypes[s] == OscType.DX7 && sound.sourceDx7Patch[s] != null) {
          System.arraycopy(sound.sourceDx7Patch[s], 0, vs.dxPatch.params, 0, 156);
          vs.dxPatch.setEngineMode(sound.sourceDx7EngineType[s]);
          vs.dxPatch.randomDetune = sound.sourceDx7RandomDetune[s];
          vs.dxVoice.init(vs.dxPatch, midiNote, velocity);
        }
      }
    }
  }

  /** Backward-compat overload. */
  public void noteOn(int midiNote, int velocity) {
    noteOn(midiNote, velocity, -1, null);
  }

  // ── Portamento pitch adjust (voice.cpp:840-856) ──

  /**
   * C: voice.cpp:840-856 — while the porta envelope runs (pos &lt; 8388608 unsigned), multiply the
   * overall pitch adjust by the decaying glide ratio and advance the envelope (speed from the
   * release-rate table of the UNPATCHED_PORTAMENTO knob). Must run exactly once per block.
   */
  private int computeOverallPitchAdjust(int numSamples) {
    int overallPitchAdjust = paramFinalValues[Param.LOCAL_PITCH_ADJUST];
    if (Integer.compareUnsigned(portaEnvelopePos, 8388608) < 0) {
      int envValue = Functions.getDecay4(portaEnvelopePos, 23);
      int pitchAdjustmentHere =
          Functions.K_MAX_SAMPLE_VALUE
              + (Functions.multiply_32x32_rshift32_rounded(envValue, portaEnvelopeMaxAmplitude)
                  << 1);

      int a = Functions.multiply_32x32_rshift32_rounded(overallPitchAdjust, pitchAdjustmentHere);
      if (a > 8388607) {
        a = 8388607; // C: "Prevent overflow! Happened to Matt Bates"
      }
      overallPitchAdjust = a << 8;

      int envelopeSpeed =
          Functions.lookupReleaseRate(Functions.cableToExpParamShortcut(sound.portamentoKnob))
              >> 13;
      portaEnvelopePos += envelopeSpeed * numSamples;
    }
    return overallPitchAdjust;
  }

  // ── setupPorta (voice.cpp:379-397) ──

  /**
   * C: voice.cpp:379-397 — primes the porta glide from the sound's previous note: the envelope
   * starts at the OLD note's relative pitch (noteIntervalTable ratio of the semitone distance) and
   * decays to neutral (kMaxSampleValue).
   */
  private void setupPorta() {
    portaEnvelopePos = 0;
    int semitoneAdjustment = sound.lastNoteCode - noteCode;

    int noteWithinOctave;
    int octave;
    int noteIntervalRatio;
    if (sound.tuning != null) {
      TuningProvider tuning = sound.tuning;
      noteWithinOctave = tuning.noteWithinOctaveOf(semitoneAdjustment);
      octave = tuning.octaveOf(semitoneAdjustment);
      noteIntervalRatio = tuning.noteIntervalRatio(noteWithinOctave);

      int phaseIncrement = noteIntervalRatio;
      int shiftRightAmount = 6 - octave;
      if (shiftRightAmount >= 0) {
        phaseIncrement = (shiftRightAmount >= 32) ? 0 : (phaseIncrement >> shiftRightAmount);
      } else {
        phaseIncrement = 2147483647;
      }
      portaEnvelopeMaxAmplitude = phaseIncrement - Functions.K_MAX_SAMPLE_VALUE;
    } else {
      noteWithinOctave = ((semitoneAdjustment + 120) % 12 + 12) % 12;
      octave = (semitoneAdjustment + 120) / 12;
      noteIntervalRatio = LookupTables.noteIntervalTable[noteWithinOctave];

      int phaseIncrement = noteIntervalRatio;
      int shiftRightAmount = 16 - octave;
      if (shiftRightAmount >= 0) {
        // Java masks shifts to 5 bits; the C's >> of >=32 yields 0 on a positive value — match
        // that.
        phaseIncrement = (shiftRightAmount >= 32) ? 0 : (phaseIncrement >> shiftRightAmount);
      } else {
        phaseIncrement = 2147483647;
      }
      portaEnvelopeMaxAmplitude = phaseIncrement - Functions.K_MAX_SAMPLE_VALUE;
    }
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

    boolean unassignVoiceAfter =
        (envelopes[0].state == Envelope.Stage.OFF)
            || (envelopes[0].state.compareTo(Envelope.Stage.DECAY) > 0
                && env0LastValue == Integer.MIN_VALUE);
    if (unassignVoiceAfter) {
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
    lfo2.customWave = sound.customLfoWave;
    sourceValues[PatchSource.LFO_LOCAL_1.ordinal()] =
        lfo2.render(numSamples, sound.lfoConfig[1], phaseInc1);

    // LFO 2 (local, uses lfoConfig[3])
    int phaseInc2 = getLocalLFOPhaseIncrement(3, Param.LOCAL_LFO_LOCAL_FREQ_2);
    lfo4.customWave = sound.customLfoWave;
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
    Patcher.performPatching(sound, sourceValues, sound.patchCableSet, paramFinalValues);

    // ── 5. Phase increments (voice.cpp:414-560) ──
    // C voice.cpp:447-458 — SAMPLE and INPUT_* sources use ratio-style increments (2^24 = unity);
    // for INPUT the neutral is kMaxSampleValue (live input has no file sample rate).
    int carrierIncA;
    if (sound.synthMode != 1
        && (sound.oscTypes[0] == Oscillator.OscType.SAMPLE || isInputType(sound.oscTypes[0]))) {
      int pitchAdjustNeutralValue = 16777216; // default (and the INPUT_* neutral, C:457)
      int oscNoteCode = noteCode + sound.masterTranspose;
      if (sound.oscTypes[0] == Oscillator.OscType.SAMPLE
          && unisonParts[0].sources[0].sampleRef != null) {
        pitchAdjustNeutralValue =
            (int) (((unisonParts[0].sources[0].sampleRef.sampleRate) * 16777216L) / 44100);
        if (unisonParts[0].sources[0].zoneTranspose != Integer.MIN_VALUE) {
          oscNoteCode +=
              unisonParts[0].sources[0].zoneTranspose; // authoritative multisample tuning
        } else {
          float sampleMidiNote = unisonParts[0].sources[0].sampleRef.midiNoteFromFile;
          if (sampleMidiNote != -1.0f) {
            int sampleTranspose = Math.round(60.0f - sampleMidiNote);
            oscNoteCode += sampleTranspose;
          }
        }
      }
      int noteWithinOctave;
      int octave;
      int noteIntervalRatio;
      if (sound.tuning != null) {
        TuningProvider tuning = sound.tuning;
        noteWithinOctave = tuning.noteWithinOctaveOf(oscNoteCode);
        octave = tuning.octaveOf(oscNoteCode);
        noteIntervalRatio = tuning.noteIntervalRatio(noteWithinOctave);

        int shiftRightAmount = 3 - octave;
        long rawInc = ((long) noteIntervalRatio * pitchAdjustNeutralValue) >> 32;
        carrierIncA = (int) rawInc;
        if (shiftRightAmount >= 0) {
          carrierIncA >>>= shiftRightAmount;
        } else {
          carrierIncA <<= (-shiftRightAmount);
        }
      } else {
        noteWithinOctave = (oscNoteCode + 240) % 12;
        octave = (oscNoteCode + 120) / 12;
        noteIntervalRatio = LookupTables.noteIntervalTable[noteWithinOctave];

        int shiftRightAmount = 13 - octave;
        long rawInc = ((long) noteIntervalRatio * pitchAdjustNeutralValue) >> 32;
        carrierIncA = (int) rawInc;
        if (shiftRightAmount >= 0) {
          carrierIncA >>>= shiftRightAmount;
        } else {
          carrierIncA <<= (-shiftRightAmount);
        }
      }
    } else {
      // C voice.cpp:442 — transposedNoteCode = note + source transpose; then cents (line 505).
      carrierIncA =
          calculateBasePhaseIncrement(noteCode + sound.masterTranspose + sound.sourceTranspose[0]);
      carrierIncA = sound.sourceFineTuners[0].detune(carrierIncA);
    }
    if (carrierIncA <= 0) {
      active = false;
      return false;
    }

    int carrierIncB;
    if (sound.synthMode != 1
        && (sound.oscTypes[1] == Oscillator.OscType.SAMPLE || isInputType(sound.oscTypes[1]))) {
      int pitchAdjustNeutralValue = 16777216; // default (and the INPUT_* neutral, C:457)
      int oscNoteCode = noteCode + sound.masterTranspose;
      if (sound.oscTypes[1] == Oscillator.OscType.SAMPLE
          && unisonParts[0].sources[1].sampleRef != null) {
        pitchAdjustNeutralValue =
            (int) (((unisonParts[0].sources[1].sampleRef.sampleRate) * 16777216L) / 44100);
        if (unisonParts[0].sources[1].zoneTranspose != Integer.MIN_VALUE) {
          oscNoteCode +=
              unisonParts[0].sources[1].zoneTranspose; // authoritative multisample tuning
        } else {
          float sampleMidiNote = unisonParts[0].sources[1].sampleRef.midiNoteFromFile;
          if (sampleMidiNote != -1.0f) {
            int sampleTranspose = Math.round(60.0f - sampleMidiNote);
            oscNoteCode += sampleTranspose;
          }
        }
      }
      int noteWithinOctave;
      int octave;
      int noteIntervalRatio;
      if (sound.tuning != null) {
        TuningProvider tuning = sound.tuning;
        noteWithinOctave = tuning.noteWithinOctaveOf(oscNoteCode);
        octave = tuning.octaveOf(oscNoteCode);
        noteIntervalRatio = tuning.noteIntervalRatio(noteWithinOctave);

        int shiftRightAmount = 3 - octave;
        long rawInc = ((long) noteIntervalRatio * pitchAdjustNeutralValue) >> 32;
        carrierIncB = (int) rawInc;
        if (shiftRightAmount >= 0) {
          carrierIncB >>>= shiftRightAmount;
        } else {
          carrierIncB <<= (-shiftRightAmount);
        }
      } else {
        noteWithinOctave = (oscNoteCode + 240) % 12;
        octave = (oscNoteCode + 120) / 12;
        noteIntervalRatio = LookupTables.noteIntervalTable[noteWithinOctave];

        int shiftRightAmount = 13 - octave;
        long rawInc = ((long) noteIntervalRatio * pitchAdjustNeutralValue) >> 32;
        carrierIncB = (int) rawInc;
        if (shiftRightAmount >= 0) {
          carrierIncB >>>= shiftRightAmount;
        } else {
          carrierIncB <<= (-shiftRightAmount);
        }
      }
    } else {
      // C voice.cpp:442 — osc B uses its OWN source transpose + cents (was: copied osc A, so osc 2
      // transpose/cents were silently ignored).
      carrierIncB =
          calculateBasePhaseIncrement(noteCode + sound.masterTranspose + sound.sourceTranspose[1]);
      carrierIncB = sound.sourceFineTuners[1].detune(carrierIncB);
    }

    // Apply unison detune
    for (int u = 0; u < sound.numUnison; u++) {
      int pIncA = carrierIncA;
      int pIncB = carrierIncB;
      if (sound.numUnison > 1) {
        pIncA = sound.unisonDetuners[u].detune(pIncA);
        pIncB = sound.unisonDetuners[u].detune(pIncB);
      }
      unisonParts[u].sources[0].phaseIncrementStoredValue = pIncA;
      unisonParts[u].sources[1].phaseIncrementStoredValue = pIncB;
    }

    // Portamento (voice.cpp:840-856): while the porta envelope runs, the overall pitch adjust is
    // multiplied by the decaying glide ratio. Computed ONCE per block (the envelope advances
    // here) and used by every pitch consumer below.
    int overallPitchAdjustPorta = computeOverallPitchAdjust(numSamples);

    // Pitch adjust via MIDI pitch bend and MPE
    // default BEND_RANGE_MAIN = 2, BEND_RANGE_FINGER_LEVEL = 48
    long monophonicBend = (sound.monophonicExpressionValues[0] / 192L) * 2;
    long polyphonicBend = (localExpressionSourceValuesBeforeSmoothing[0] / 192L) * 48;
    int totalBendAmount = (int) (monophonicBend + polyphonicBend);
    overallPitchAdjust = Functions.getExp(overallPitchAdjustPorta, totalBendAmount >> 1);

    // FM modulators
    if (sound.synthMode == 1) { // FM
      for (int m = 0; m < 2; m++) {
        if (sound.getSmoothedPatchedParamValue(Param.LOCAL_MODULATOR_0_VOLUME + m)
            == Integer.MIN_VALUE) {
          for (int u = 0; u < sound.numUnison; u++) {
            unisonParts[u].modulatorPhaseIncrement[m] = 0xFFFFFFFF; // inactive
          }
          continue;
        }

        int modInc =
            calculateBasePhaseIncrement(
                noteCode + sound.masterTranspose + sound.modulatorTranspose[m]);
        if (modInc <= 0) {
          for (int u = 0; u < sound.numUnison; u++) {
            unisonParts[u].modulatorPhaseIncrement[m] = 0xFFFFFFFF; // inactive
          }
          continue;
        }

        modInc = sound.modulatorTransposers[m].detune(modInc);

        for (int u = 0; u < sound.numUnison; u++) {
          if (sound.numUnison == 1) {
            unisonParts[u].modulatorPhaseIncrement[m] = modInc;
          } else {
            unisonParts[u].modulatorPhaseIncrement[m] = sound.unisonDetuners[u].detune(modInc);
          }
        }
      }
    }

    // ── 6. Per-source rendering (lines 950-1400) ──
    // Overall osc amplitude (C voice.cpp:984-986): multiply LOCAL_VOLUME by the envelope-0 SOURCE
    // value reconstructed to unipolar — (sourceValues[ENVELOPE_0] >> 1) + 2^30 — NOT the raw
    // envelope lastValue field (that mismatch silenced some patches).
    int env0Gain = (env0LastValue >> 1) + 1073741824;
    int trackVol = paramFinalValues[Param.LOCAL_VOLUME];

    int overallOscAmplitude =
        Functions.lshiftAndSaturate(Functions.multiply_32x32_rshift32(env0Gain, trackVol), 2);

    if (!doneFirstRender
        && Integer.compareUnsigned(paramFinalValues[Param.LOCAL_ENV_0_ATTACK], 245632) > 0) {
      overallOscAmplitudeLastTime = overallOscAmplitude;
    }

    overallOscillatorAmplitudeIncrement =
        (overallOscAmplitude - overallOscAmplitudeLastTime) / numSamples;

    // Prepare the filters and the makeup gain (voice.cpp:991-997). Configure ONCE here; filterGain
    // is folded into the per-source amplitudes below (subtractive), and overallOscAmplitude is
    // applied
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
    if (!doneFirstRender) {
      filterSet.lpLadder.dryFade = 0.0f;
      filterSet.lpLadder.wetLevel = Functions.ONE_Q31;
      filterSet.hpLadder.dryFade = 0.0f;
      filterSet.hpLadder.wetLevel = Functions.ONE_Q31;
      filterSet.lpSvf.dryFade = 0.0f;
      filterSet.lpSvf.wetLevel = Functions.ONE_Q31;
      filterSet.hpSvf.dryFade = 0.0f;
      filterSet.hpSvf.wetLevel = Functions.ONE_Q31;
    }
    boolean hasFilters =
        (lpfModeVal != FilterSet.FilterMode.OFF) || (hpfModeVal != FilterSet.FilterMode.OFF);

    // Prepare scratch buffers: stereo output buffer
    int requiredLen = numSamples * 2;
    if (mixBuf.length < requiredLen) {
      mixBuf = new int[requiredLen];
    }
    java.util.Arrays.fill(mixBuf, 0, requiredLen, 0);

    // ── Oscillator sync (voice.cpp:1100-1106): capture osc A's per-part phases BEFORE any
    // rendering advances them; osc B then hard-syncs to them. ──
    boolean doingOscSync = sound.renderingOscillatorSyncCurrently();
    if (doingOscSync) {
      for (int u = 0; u < sound.numUnison; u++) {
        oscSyncPos[u] = unisonParts[u].sources[0].oscPos;
      }
    }

    // ── FM path (voice.cpp:1400-1560) ──
    if (sound.synthMode == 1) { // FM
      renderFmPath(mixBuf, numSamples, overallOscAmplitude, overallPitchAdjust);
    } else if (sound.synthMode == 2) {
      renderRingModPath(
          mixBuf, numSamples, overallPitchAdjust, hasFilters, filterGain, doingOscSync);
    } else {
      renderSubtractivePath(
          mixBuf, numSamples, overallPitchAdjust, hasFilters, filterGain, doingOscSync);
    }

    // Noise (voice.cpp:1131-1149): add the noise source into the mono mix before the filter +
    // overall amplitude (non-FM only). Scaled like the osc sources: >>1, then *filterGain<<4 when
    // filtered, limited to 268435455, then >>2. fw2 was missing this entirely → noise patches
    // silent.
    if (sound.synthMode != 1 && paramFinalValues[Param.LOCAL_NOISE_VOLUME] != 0) {
      int n = paramFinalValues[Param.LOCAL_NOISE_VOLUME] >> 1;
      if (hasFilters) {
        n = Functions.lshiftAndSaturate(Functions.multiply_32x32_rshift32(n, filterGain), 4);
      }
      int noiseAmplitude = Math.min(n, 268435455) >> 2;
      for (int i = 0; i < numSamples; i++) {
        int ns = Functions.multiply_32x32_rshift32(Functions.getNoise(), noiseAmplitude);
        mixBuf[i * 2] = Functions.add_saturate(mixBuf[i * 2], ns);
        mixBuf[i * 2 + 1] = Functions.add_saturate(mixBuf[i * 2 + 1], ns);
      }
    }

    // ── 7+8. Filter, pan, overall amplitude into output buffer (voice.cpp:1560-1670) ──
    // NOTE: the overall oscillator amplitude (env0 * LOCAL_VOLUME) is applied ONCE, inside
    // applyFilterAndGain (overallOscAmplitudeLastTime). The C folds it into sourceAmplitude and
    // applies it during osc render; here it is applied to the summed mix instead — same single
    // application. (A previous separate env0*trackVol step here double-applied volume → silence.)
    applyFilterAndGain(mixBuf, numSamples, doLPF, doHPF);

    int maxValVoice = 0;
    for (int i = 0; i < numSamples * 2; i++) {
      maxValVoice = Math.max(maxValVoice, Math.abs(mixBuf[i]));
    }
    if (org.deluge.engine.FirmwareAudioEngine.debugTelemetry && maxValVoice > 1000) {}

    // Copy to output
    for (int i = 0; i < numSamples; i++) {
      soundBuffer[i * 2] = Functions.add_saturate(soundBuffer[i * 2], mixBuf[i * 2]);
      soundBuffer[i * 2 + 1] = Functions.add_saturate(soundBuffer[i * 2 + 1], mixBuf[i * 2 + 1]);
    }

    // Port of voice.cpp:1664 — return false if voice should be unassigned
    // unassignVoiceAfter = envelope OFF OR (past DECAY AND env source == MIN)
    unassignVoiceAfter =
        (envelopes[0].state == Envelope.Stage.OFF)
            || (envelopes[0].state.compareTo(Envelope.Stage.DECAY) > 0
                && sourceValues[PatchSource.ENVELOPE_0.ordinal()] == Integer.MIN_VALUE);
    if (unassignVoiceAfter) {
      active = false;
    }

    overallOscAmplitudeLastTime = overallOscAmplitude;
    doneFirstRender = true;

    return !unassignVoiceAfter;
  }

  // ── FM render path ──

  private int getModulatorInc(int m, int overallPitchAdjust, int u) {
    // voice.cpp:533-553 — modulator phase increment from the note frequency table + the modulator's
    // semitone transpose, then a cents detune.
    int modInc = unisonParts[u].modulatorPhaseIncrement[m];
    if (modInc <= 0 || modInc == 0xFFFFFFFF)
      return 0; // shiftRightAmount < 0 → too high; C marks it inactive
    // voice.cpp:1387-1401 — overall pitch adjust (incl. bend) + per-modulator pitch adjust param.
    modInc = adjustPitch(modInc, overallPitchAdjust);
    if (modInc < 0) return 0;
    modInc = adjustPitch(modInc, paramFinalValues[Param.LOCAL_MODULATOR_0_PITCH_ADJUST + m]);
    if (modInc < 0) return 0;
    return modInc;
  }

  private void renderRingModPath(
      int[] mixBuf,
      int numSamples,
      int overallPitchAdjust,
      boolean hasFilters,
      int filterGain,
      boolean doingOscSync) {
    if (tempBufA.length < numSamples) {
      tempBufA = new int[numSamples];
    }
    if (tempBufB.length < numSamples) {
      tempBufB = new int[numSamples];
    }
    boolean stereoUnison = sound.unisonStereoSpread != 0 && sound.numUnison > 1;

    for (int u = 0; u < sound.numUnison; u++) {
      int[] ampLR = tempAmpLR;
      boolean doPanning = shouldDoPanning(stereoUnison ? sound.unisonPan[u] : 0, ampLR);

      VoiceSource vsA = unisonParts[u].sources[0];
      VoiceSource vsB = unisonParts[u].sources[1];

      int pIncA = vsA.phaseIncrementStoredValue;
      int pIncB = vsB.phaseIncrementStoredValue;

      pIncA = adjustPitch(pIncA, overallPitchAdjust);
      if (pIncA < 0) continue;
      pIncA = adjustPitch(pIncA, paramFinalValues[Param.LOCAL_OSC_A_PITCH_ADJUST]);
      if (pIncA < 0) continue;

      pIncB = adjustPitch(pIncB, overallPitchAdjust);
      if (pIncB < 0) continue;
      pIncB = adjustPitch(pIncB, paramFinalValues[Param.LOCAL_OSC_B_PITCH_ADJUST]);
      if (pIncB < 0) continue;

      java.util.Arrays.fill(tempBufA, 0, numSamples, 0);
      java.util.Arrays.fill(tempBufB, 0, numSamples, 0);

      int amplitudeForRingMod = 1 << 27;
      if (hasFilters) {
        amplitudeForRingMod =
            Functions.lshiftAndSaturate(
                Functions.multiply_32x32_rshift32_rounded(amplitudeForRingMod, filterGain), 4);
      }

      // C voice.cpp:1317-1340 — osc A can never osc-sync (cantBeDoingOscSyncForFirstOsc); osc B
      // syncs to osc A's captured phase + this part's osc-A increment. Both pass the source's
      // retrigger phase (used by the pulse-width reset path even without sync).
      if (sound.oscTypes[0] == OscType.WAVETABLE && sound.waveTables[0] != null) {
        int waveIndexA = paramFinalValues[Param.LOCAL_OSC_A_WAVE_INDEX];
        if (!doneFirstRender) {
          vsA.waveIndexLastTime = waveIndexA;
        }
        int waveIndexIncrementA = (waveIndexA - vsA.waveIndexLastTime) / numSamples;
        vsA.oscPos =
            sound.waveTables[0].render(
                tempBufA,
                0,
                numSamples,
                pIncA,
                vsA.oscPos,
                false,
                0,
                0,
                0,
                sound.oscRetriggerPhase[0],
                vsA.waveIndexLastTime,
                waveIndexIncrementA);
        vsA.waveIndexLastTime = waveIndexA;
      } else {
        int pulseWidthA =
            Functions.lshiftAndSaturate(paramFinalValues[Param.LOCAL_OSC_A_PHASE_WIDTH], 1);
        int[] phaseA = {vsA.oscPos};
        Oscillator.renderOsc(
            sound.oscTypes[0],
            0,
            tempBufA,
            0,
            numSamples,
            pIncA,
            pulseWidthA,
            phaseA,
            false,
            0,
            false,
            0,
            0,
            sound.oscRetriggerPhase[0]);
        vsA.oscPos = phaseA[0];
      }
      if (sound.oscTypes[0] == OscType.SAW || sound.oscTypes[0] == OscType.ANALOG_SAW_2) {
        amplitudeForRingMod = Functions.lshiftAndSaturate(amplitudeForRingMod, 1);
      } else if (sound.oscTypes[0] == OscType.WAVETABLE) {
        amplitudeForRingMod = Functions.lshiftAndSaturate(amplitudeForRingMod, 2);
      }

      if (sound.oscTypes[1] == OscType.WAVETABLE && sound.waveTables[1] != null) {
        int waveIndexB = paramFinalValues[Param.LOCAL_OSC_B_WAVE_INDEX];
        if (!doneFirstRender) {
          vsB.waveIndexLastTime = waveIndexB;
        }
        int waveIndexIncrementB = (waveIndexB - vsB.waveIndexLastTime) / numSamples;

        int resetterDivideByPhaseIncrement = 0;
        if (doingOscSync) {
          resetterDivideByPhaseIncrement =
              (int) (0x80000000L / (((pIncA & 0xFFFF0000L) + 65536) >>> 16));
        }

        vsB.oscPos =
            sound.waveTables[1].render(
                tempBufB,
                0,
                numSamples,
                pIncB,
                vsB.oscPos,
                doingOscSync,
                oscSyncPos[u],
                pIncA,
                resetterDivideByPhaseIncrement,
                sound.oscRetriggerPhase[1],
                vsB.waveIndexLastTime,
                waveIndexIncrementB);
        vsB.waveIndexLastTime = waveIndexB;
      } else {
        int pulseWidthB =
            Functions.lshiftAndSaturate(paramFinalValues[Param.LOCAL_OSC_B_PHASE_WIDTH], 1);
        int[] phaseB = {vsB.oscPos};
        Oscillator.renderOsc(
            sound.oscTypes[1],
            0,
            tempBufB,
            0,
            numSamples,
            pIncB,
            pulseWidthB,
            phaseB,
            false,
            0,
            doingOscSync,
            oscSyncPos[u],
            pIncA,
            sound.oscRetriggerPhase[1]);
        vsB.oscPos = phaseB[0];
      }
      if (sound.oscTypes[1] == OscType.SAW || sound.oscTypes[1] == OscType.ANALOG_SAW_2) {
        amplitudeForRingMod = Functions.lshiftAndSaturate(amplitudeForRingMod, 1);
      } else if (sound.oscTypes[1] == OscType.WAVETABLE) {
        amplitudeForRingMod = Functions.lshiftAndSaturate(amplitudeForRingMod, 2);
      }

      for (int i = 0; i < numSamples; i++) {
        int out =
            Functions.multiply_32x32_rshift32_rounded(
                Functions.multiply_32x32_rshift32(tempBufA[i], tempBufB[i]), amplitudeForRingMod);
        if (stereoUnison) {
          int l = Functions.lshiftAndSaturate(Functions.multiply_32x32_rshift32(out, ampLR[0]), 2);
          int r = Functions.lshiftAndSaturate(Functions.multiply_32x32_rshift32(out, ampLR[1]), 2);
          mixBuf[i * 2] = Functions.add_saturate(mixBuf[i * 2], l);
          mixBuf[i * 2 + 1] = Functions.add_saturate(mixBuf[i * 2 + 1], r);
        } else {
          mixBuf[i * 2] = Functions.add_saturate(mixBuf[i * 2], out);
          mixBuf[i * 2 + 1] = Functions.add_saturate(mixBuf[i * 2 + 1], out);
        }
      }
    }
  }

  private void renderSubtractivePath(
      int[] mixBuf,
      int numSamples,
      int overallPitchAdjust,
      boolean hasFilters,
      int filterGain,
      boolean doingOscSync) {
    if (tempBuf.length < numSamples) {
      tempBuf = new int[numSamples];
    }
    boolean stereoUnison = sound.unisonStereoSpread != 0 && sound.numUnison > 1;

    for (int s = 0; s < 2; s++) {
      boolean getPhaseIncrements = (s == 0) && doingOscSync;
      boolean getOutAfterGettingPhaseIncrements = false;
      if (!sound.isSourceActiveCurrently(s, sources[s].sampleRef != null)) {
        if (getPhaseIncrements) {
          getOutAfterGettingPhaseIncrements = true;
        } else {
          continue;
        }
      }
      boolean doOscSyncThisSource = (s == 1) && doingOscSync;

      int srcAmp =
          hasFilters
              ? Functions.multiply_32x32_rshift32_rounded(
                  paramFinalValues[Param.LOCAL_OSC_A_VOLUME + s], filterGain)
              : paramFinalValues[Param.LOCAL_OSC_A_VOLUME + s] >> 4;

      for (int u = 0; u < sound.numUnison; u++) {
        VoiceSource vs = unisonParts[u].sources[s];
        if (!vs.active) continue;

        int pInc = vs.phaseIncrementStoredValue;
        pInc = adjustPitch(pInc, overallPitchAdjust);
        if (pInc >= 0) {
          pInc = adjustPitch(pInc, paramFinalValues[Param.LOCAL_OSC_A_PITCH_ADJUST + s]);
        }
        if (pInc < 0) {
          if (getPhaseIncrements) oscSyncPhaseIncrement[u] = 0;
          continue;
        }
        if (getPhaseIncrements) {
          oscSyncPhaseIncrement[u] = pInc;
          if (getOutAfterGettingPhaseIncrements) {
            vs.oscPos += pInc * numSamples;
            continue;
          }
        }
        if (doOscSyncThisSource && oscSyncPhaseIncrement[u] == 0) continue;

        int[] ampLR = tempAmpLR;
        shouldDoPanning(stereoUnison ? sound.unisonPan[u] : 0, ampLR);

        java.util.Arrays.fill(tempBuf, 0, numSamples, 0);

        if (vs.sampleRef != null) {
          if (vs.voiceSample.pendingSamplesLate > 0) {
            long rawSamplesLate =
                ((((long) vs.voiceSample.pendingSamplesLate * vs.phaseIncrementStoredValue) >> 24)
                        * vs.timeStretchRatio)
                    >> 24;
            boolean success = vs.voiceSample.attemptLateSampleStart((int) rawSamplesLate);
            if (!success) {
              vs.active = false;
              continue;
            }
          }
          int sampAmp =
              (srcAmp > 0)
                  ? srcAmp
                  : Math.max(paramFinalValues[Param.LOCAL_OSC_A_VOLUME + s] >> 4, 1 << 26);
          if (vs.voiceSample.timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_NEWER]
              || vs.voiceSample.timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_OLDER]) {
            int requiredTsLen = numSamples * 2;
            if (vs.tsBuf == null || vs.tsBuf.length < requiredTsLen) {
              vs.tsBuf = new int[requiredTsLen];
            }
            int[] tsBuf = vs.tsBuf;
            java.util.Arrays.fill(tsBuf, 0, requiredTsLen, 0);
            int[] ampArr = {sampAmp};
            vs.voiceSample.renderTimeStretched(
                tsBuf, numSamples, 2, pInc, vs.timeStretchRatio, ampArr, 0);
            for (int i = 0; i < numSamples; i++) {
              tempBuf[i] = (tsBuf[i * 2] + tsBuf[i * 2 + 1]) >> 1;
            }
            if (!vs.voiceSample.active) vs.active = false;
          } else if (vs.voiceSample.active) {
            int[] ampArr = {sampAmp};
            vs.voiceSample.render(tempBuf, numSamples, 1, pInc, ampArr, 0);
            if (!vs.voiceSample.active) vs.active = false;
          }
        } else if (isInputType(sound.oscTypes[s])) {
          pInc = vs.phaseIncrementStoredValue;
          int[] input = LiveInput.currentBlock;
          if (input != null) {
            OscType inputTypeNow = sound.oscTypes[s];
            boolean anyInputDevice = LiveInput.lineInPluggedIn || LiveInput.micPluggedIn;
            if (inputTypeNow == OscType.INPUT_STEREO && !anyInputDevice) {
              inputTypeNow = OscType.INPUT_L;
            }
            int n = Math.min(numSamples, input.length / 2);
            if (pInc != Functions.K_MAX_SAMPLE_VALUE && anyInputDevice) {
              if (vs.livePitchShifter == null) {
                LiveInputBuffer.InputType t =
                    switch (inputTypeNow) {
                      case INPUT_R -> LiveInputBuffer.InputType.INPUT_R;
                      case INPUT_STEREO -> LiveInputBuffer.InputType.STEREO;
                      default -> LiveInputBuffer.InputType.INPUT_L;
                    };
                vs.livePitchShifter = new LivePitchShifter(t, pInc);
                vs.liveInputBuffer = new LiveInputBuffer();
                vs.liveInputTimer = 0;
              }
            } else if (vs.livePitchShifter != null
                && vs.livePitchShifter.mayBeRemovedWithoutClick()) {
              vs.livePitchShifter = null;
              vs.liveInputBuffer = null;
            }

            if (vs.livePitchShifter != null) {
              int ch = vs.livePitchShifter.numChannels;
              int requiredShiftLen = n * ch;
              if (vs.shiftedBuf == null || vs.shiftedBuf.length < requiredShiftLen) {
                vs.shiftedBuf = new int[requiredShiftLen];
              }
              int[] shifted = vs.shiftedBuf;
              java.util.Arrays.fill(shifted, 0, requiredShiftLen, 0);
              vs.liveInputTimer += n;
              vs.livePitchShifter.render(
                  shifted,
                  n,
                  pInc,
                  Functions.lshiftAndSaturate(srcAmp, 4),
                  0,
                  16,
                  vs.liveInputBuffer,
                  vs.liveInputTimer,
                  input);
              if (ch == 2) {
                for (int i = 0; i < n; i++) {
                  tempBuf[i] = (shifted[i * 2] >> 1) + (shifted[i * 2 + 1] >> 1);
                }
              } else {
                System.arraycopy(shifted, 0, tempBuf, 0, n);
              }
            } else if (inputTypeNow != OscType.INPUT_STEREO) {
              int channelOffset = (inputTypeNow == OscType.INPUT_R && anyInputDevice) ? 1 : 0;
              for (int i = 0; i < n; i++) {
                tempBuf[i] =
                    Functions.lshiftAndSaturate(
                        Functions.multiply_32x32_rshift32(input[i * 2 + channelOffset], srcAmp), 4);
              }
            } else {
              for (int i = 0; i < n; i++) {
                int mono = (input[i * 2] >> 1) + (input[i * 2 + 1] >> 1);
                tempBuf[i] =
                    Functions.lshiftAndSaturate(Functions.multiply_32x32_rshift32(mono, srcAmp), 4);
              }
            }
          }
        } else if (sound.oscTypes[s] == OscType.DX7) {
          if (vs.dxVoice.patch == null && sound.sourceDx7Patch[s] != null) {
            System.arraycopy(sound.sourceDx7Patch[s], 0, vs.dxPatch.params, 0, 156);
            vs.dxPatch.setEngineMode(sound.sourceDx7EngineType[s]);
            vs.dxPatch.randomDetune = sound.sourceDx7RandomDetune[s];
            vs.dxVoice.init(vs.dxPatch, note, velocity);
          }
          if (vs.dxVoice.patch == null) continue;

          int logpitch = (int) (Math.log(pInc & 0xFFFFFFFFL) / Math.log(2.0) * (1 << 24));
          int adjpitch = logpitch - 278023814;
          int ampMod = paramFinalValues[Param.LOCAL_OSC_A_PHASE_WIDTH + s] >> 13;
          vs.dxPatch.computeLfo(numSamples);
          if (uniBuf.length < numSamples) {
            uniBuf = new int[numSamples];
          }
          vs.dxVoice.compute(uniBuf, numSamples, adjpitch, vs.dxPatch, ampMod, 0, 0);
          for (int i = 0; i < numSamples; i++) {
            tempBuf[i] =
                Functions.lshiftAndSaturate(
                    Functions.multiply_32x32_rshift32(uniBuf[i], srcAmp), 6);
          }
        } else if (sound.oscTypes[s] == OscType.WAVETABLE && sound.waveTables[s] != null) {
          int waveIndex = paramFinalValues[Param.LOCAL_OSC_A_WAVE_INDEX + s];
          if (!doneFirstRender) {
            vs.waveIndexLastTime = waveIndex;
          }
          int waveIndexIncrement = (waveIndex - vs.waveIndexLastTime) / numSamples;

          int resetterDivideByPhaseIncrement = 0;
          if (doOscSyncThisSource) {
            long divisor = ((oscSyncPhaseIncrement[u] & 0xFFFFFFFFL) + 65535) >>> 16;
            if (divisor > 0) {
              resetterDivideByPhaseIncrement = (int) (2147483648L / divisor);
            }
          }

          vs.oscPos =
              sound.waveTables[s].render(
                  tempBuf,
                  0,
                  numSamples,
                  pInc,
                  vs.oscPos,
                  doOscSyncThisSource,
                  oscSyncPos[u],
                  oscSyncPhaseIncrement[u],
                  resetterDivideByPhaseIncrement,
                  sound.oscRetriggerPhase[s],
                  vs.waveIndexLastTime,
                  waveIndexIncrement);
          vs.waveIndexLastTime = waveIndex;

          for (int i = 0; i < numSamples; i++) {
            tempBuf[i] =
                Functions.lshiftAndSaturate(
                    Functions.multiply_32x32_rshift32(tempBuf[i], srcAmp), 1);
          }
        } else {
          int pulseWidth =
              Functions.lshiftAndSaturate(paramFinalValues[Param.LOCAL_OSC_A_PHASE_WIDTH + s], 1);
          int[] phase = {vs.oscPos};
          Oscillator.renderOsc(
              sound.oscTypes[s],
              srcAmp,
              tempBuf,
              0,
              numSamples,
              pInc,
              pulseWidth,
              phase,
              true,
              0,
              doOscSyncThisSource,
              oscSyncPos[u],
              oscSyncPhaseIncrement[u],
              sound.oscRetriggerPhase[s]);
          vs.oscPos = phase[0];
        }

        if (stereoUnison) {
          for (int i = 0; i < numSamples; i++) {
            int out = tempBuf[i];
            int l =
                Functions.lshiftAndSaturate(Functions.multiply_32x32_rshift32(out, ampLR[0]), 2);
            int r =
                Functions.lshiftAndSaturate(Functions.multiply_32x32_rshift32(out, ampLR[1]), 2);
            mixBuf[i * 2] = Functions.add_saturate(mixBuf[i * 2], l);
            mixBuf[i * 2 + 1] = Functions.add_saturate(mixBuf[i * 2 + 1], r);
          }
        } else {
          for (int i = 0; i < numSamples; i++) {
            int out = tempBuf[i];
            mixBuf[i * 2] = Functions.add_saturate(mixBuf[i * 2], out);
            mixBuf[i * 2 + 1] = Functions.add_saturate(mixBuf[i * 2 + 1], out);
          }
        }
      }
    }
  }

  private void renderFmPath(
      int[] mixBuf, int numSamples, int overallOscAmplitude, int overallPitchAdjust) {
    // C: voice.cpp:975-979 — on the first render of a fast attack, seed lastTime to the target so
    // the modulator amplitude doesn't ramp up from zero across the first block.
    if (!doneFirstRender && paramFinalValues[Param.LOCAL_ENV_0_ATTACK] > 245632) {
      modulatorAmplitudeLastTime[0] = paramFinalValues[Param.LOCAL_MODULATOR_0_VOLUME];
      modulatorAmplitudeLastTime[1] = paramFinalValues[Param.LOCAL_MODULATOR_1_VOLUME];
    }

    // C: voice.cpp:1069-1079 — the modulator amplitude is ramped per sample from last block's value
    // (modulatorAmplitudeLastTime) to this block's target (paramFinalValues). A modulator stays
    // active while either is non-zero so a release ramp-down still renders.
    int modTarget0 = paramFinalValues[Param.LOCAL_MODULATOR_0_VOLUME];
    int modTarget1 = paramFinalValues[Param.LOCAL_MODULATOR_1_VOLUME];
    boolean mod0Active = modTarget0 != 0 || modulatorAmplitudeLastTime[0] != 0;
    boolean mod1Active = modTarget1 != 0 || modulatorAmplitudeLastTime[1] != 0;
    int modAmp0 = modulatorAmplitudeLastTime[0]; // C:1424/1443 — start amplitude (ramped below)
    int modAmp1 = modulatorAmplitudeLastTime[1];
    int modAmpInc0 = mod0Active ? (modTarget0 - modulatorAmplitudeLastTime[0]) / numSamples : 0;
    int modAmpInc1 = mod1Active ? (modTarget1 - modulatorAmplitudeLastTime[1]) / numSamples : 0;
    // TEST-ONLY FM-index calibration hook (1.0 = faithful, no effect). Scales the native-FM
    // modulation depth so the FmIndexAbHarness can A/B index multipliers against hardware.
    double fmScale = testFmIndexScale;
    if (fmScale != 1.0) {
      modAmp0 = (int) (modAmp0 * fmScale);
      modAmp1 = (int) (modAmp1 * fmScale);
      modAmpInc0 = (int) (modAmpInc0 * fmScale);
      modAmpInc1 = (int) (modAmpInc1 * fmScale);
    }

    if (fmBuf.length < numSamples) {
      fmBuf = new int[numSamples];
    }
    if (fmOscBuffer.length < numSamples) {
      fmOscBuffer = new int[numSamples];
    }

    boolean stereoUnison = sound.unisonStereoSpread != 0 && sound.numUnison > 1;

    for (int u = 0; u < sound.numUnison; u++) {
      boolean carriersAreSine = false;
      int modInc0 = getModulatorInc(0, overallPitchAdjust, u);
      int modInc1 = getModulatorInc(1, overallPitchAdjust, u);

      boolean mod0ActiveThisUnison = mod0Active && modInc0 > 0;
      boolean mod1ActiveThisUnison = mod1Active && modInc1 > 0;

      java.util.Arrays.fill(fmBuf, 0);
      java.util.Arrays.fill(fmOscBuffer, 0);

      if (mod1ActiveThisUnison) {
        renderSineWaveWithFeedback(
            fmBuf,
            numSamples,
            unisonParts[u].modulatorPhase,
            1,
            modAmp1,
            modInc1,
            paramFinalValues[Param.LOCAL_MODULATOR_1_FEEDBACK],
            unisonParts[u].modulatorFeedback,
            1,
            false,
            modAmpInc1);
        if (sound.modulator1ToModulator0 && mod0ActiveThisUnison) {
          renderFMWithFeedback(
              fmBuf,
              numSamples,
              unisonParts[u].modulatorPhase,
              0,
              modAmp0,
              modInc0,
              paramFinalValues[Param.LOCAL_MODULATOR_0_FEEDBACK],
              unisonParts[u].modulatorFeedback,
              0,
              modAmpInc0);
        } else if (!sound.modulator1ToModulator0 && mod0ActiveThisUnison) {
          renderSineWaveWithFeedback(
              fmBuf,
              numSamples,
              unisonParts[u].modulatorPhase,
              0,
              modAmp0,
              modInc0,
              paramFinalValues[Param.LOCAL_MODULATOR_0_FEEDBACK],
              unisonParts[u].modulatorFeedback,
              0,
              true,
              modAmpInc0);
        }
      } else if (mod0ActiveThisUnison) {
        renderSineWaveWithFeedback(
            fmBuf,
            numSamples,
            unisonParts[u].modulatorPhase,
            0,
            modAmp0,
            modInc0,
            paramFinalValues[Param.LOCAL_MODULATOR_0_FEEDBACK],
            unisonParts[u].modulatorFeedback,
            0,
            false,
            modAmpInc0);
      } else {
        carriersAreSine = true;
      }

      // Carriers
      int carrierIncA = unisonParts[u].sources[0].phaseIncrementStoredValue;
      int carrierIncB = unisonParts[u].sources[1].phaseIncrementStoredValue;

      carrierIncA = adjustPitch(carrierIncA, overallPitchAdjust);
      if (carrierIncA >= 0) {
        carrierIncA = adjustPitch(carrierIncA, paramFinalValues[Param.LOCAL_OSC_A_PITCH_ADJUST]);
      }
      carrierIncB = adjustPitch(carrierIncB, overallPitchAdjust);
      if (carrierIncB >= 0) {
        carrierIncB = adjustPitch(carrierIncB, paramFinalValues[Param.LOCAL_OSC_B_PITCH_ADJUST]);
      }

      // C voice.cpp:1026-1028 — unison compensation folded into the CURRENT overall amplitude.
      int overallForCarriers =
          Functions.multiply_32x32_rshift32_rounded(
                  overallOscAmplitude, sound.volumeNeutralValueForUnison)
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

      if (carrierIncA < 0) carrierAmp0 = 0;
      if (carrierIncB < 0) carrierAmp1 = 0;

      if (carriersAreSine) {
        renderCarrierSine(
            fmOscBuffer,
            numSamples,
            unisonParts[u].sources[0],
            carrierAmp0,
            carrierIncA,
            paramFinalValues[Param.LOCAL_CARRIER_0_FEEDBACK]);
        renderCarrierSine(
            fmOscBuffer,
            numSamples,
            unisonParts[u].sources[1],
            carrierAmp1,
            carrierIncB,
            paramFinalValues[Param.LOCAL_CARRIER_1_FEEDBACK]);
      } else {
        renderCarrierFM(
            fmOscBuffer,
            numSamples,
            fmBuf,
            unisonParts[u].sources[0],
            carrierAmp0,
            carrierIncA,
            paramFinalValues[Param.LOCAL_CARRIER_0_FEEDBACK]);
        renderCarrierFM(
            fmOscBuffer,
            numSamples,
            fmBuf,
            unisonParts[u].sources[1],
            carrierAmp1,
            carrierIncB,
            paramFinalValues[Param.LOCAL_CARRIER_1_FEEDBACK]);
      }

      int[] ampLR = tempAmpLR;
      shouldDoPanning(stereoUnison ? sound.unisonPan[u] : 0, ampLR);

      if (stereoUnison) {
        for (int i = 0; i < numSamples; i++) {
          int out = fmOscBuffer[i];
          mixBuf[i * 2] =
              Functions.add_saturate(
                  mixBuf[i * 2],
                  Functions.lshiftAndSaturate(Functions.multiply_32x32_rshift32(out, ampLR[0]), 2));
          mixBuf[i * 2 + 1] =
              Functions.add_saturate(
                  mixBuf[i * 2 + 1],
                  Functions.lshiftAndSaturate(Functions.multiply_32x32_rshift32(out, ampLR[1]), 2));
        }
      } else {
        for (int i = 0; i < numSamples; i++) {
          int out = fmOscBuffer[i];
          mixBuf[i * 2] = Functions.add_saturate(mixBuf[i * 2], out);
          mixBuf[i * 2 + 1] = Functions.add_saturate(mixBuf[i * 2 + 1], out);
        }
      }
    }

    // C: voice.cpp:1660 — carry this block's modulator amplitude target into the next block's ramp
    // start, so the per-sample interpolation tracks the modulator-volume envelope across blocks.
    modulatorAmplitudeLastTime[0] = paramFinalValues[Param.LOCAL_MODULATOR_0_VOLUME];
    modulatorAmplitudeLastTime[1] = paramFinalValues[Param.LOCAL_MODULATOR_1_VOLUME];
  }

  // ── Filter + gain application ──

  private void applyFilterAndGain(int[] stereoBuf, int numSamples, boolean doLPF, boolean doHPF) {
    // The filter is already configured and its makeup gain (filterGain) already folded into the
    // source
    // amplitudes in render() (voice.cpp folds it into sourceAmplitudes, not the buffer). Here we
    // just
    // run the filter, then apply overallOscAmplitude AFTER the filter (non-FM) and pan.

    // Wavefolder (voice.cpp:1499-1501/1583-1587): fold the osc buffer BEFORE the filters when
    // LOCAL_FOLD's final value is positive (knob off = INT_MIN → final 0 → no fold).
    if (paramFinalValues[Param.LOCAL_FOLD] > 0) {
      Functions.foldBufferPolyApproximation(
          stereoBuf, 0, numSamples * 2, paramFinalValues[Param.LOCAL_FOLD]);
    }

    int maxBeforeFilter = 0;
    for (int i = 0; i < numSamples * 2; i++) {
      maxBeforeFilter = Math.max(maxBeforeFilter, Math.abs(stereoBuf[i]));
    }

    if (filterSet.isOn()) {
      filterSet.renderLongStereo(stereoBuf, numSamples);
    }

    int maxAfterFilter = 0;
    for (int i = 0; i < numSamples * 2; i++) {
      maxAfterFilter = Math.max(maxAfterFilter, Math.abs(stereoBuf[i]));
    }

    // Pan (voice.cpp:1159-1166)
    int[] ampLR = tempAmpLR;
    int panAmount = paramFinalValues[Param.LOCAL_PAN];
    boolean doPanning = shouldDoPanning(panAmount, ampLR);

    if (sound.synthMode != 1) { // Not FM: apply overall oscillator amplitude
      int overallOscAmplitudeNow = overallOscAmplitudeLastTime;
      for (int i = 0; i < numSamples; i++) {
        overallOscAmplitudeNow += overallOscillatorAmplitudeIncrement;
        stereoBuf[i * 2] =
            Functions.lshiftAndSaturate(
                Functions.multiply_32x32_rshift32_rounded(stereoBuf[i * 2], overallOscAmplitudeNow),
                1);
        stereoBuf[i * 2 + 1] =
            Functions.lshiftAndSaturate(
                Functions.multiply_32x32_rshift32_rounded(
                    stereoBuf[i * 2 + 1], overallOscAmplitudeNow),
                1);
      }
    }

    int maxAfterAmp = 0;
    for (int i = 0; i < numSamples * 2; i++) {
      maxAfterAmp = Math.max(maxAfterAmp, Math.abs(stereoBuf[i]));
    }

    if (org.deluge.engine.FirmwareAudioEngine.debugTelemetry && maxBeforeFilter > 0) {
      System.out.printf(
          "[TELEMETRY DEBUG Voice] beforeFilter=%d, afterFilter=%d, afterAmp=%d (overallAmp=%d)\n",
          maxBeforeFilter, maxAfterFilter, maxAfterAmp, overallOscAmplitudeLastTime);
    }

    // Saturation/clipping (voice.cpp:1535-1565 "Yes clipping" branch): after the overall
    // amplitude, before pan — Sound::saturate (sound.h:290-294) = anti-aliased tanh at
    // 5 + clippingAmount with per-channel working-value state, output << shiftAmount.
    if (sound.clippingAmount > 0) {
      int shiftAmount = sound.getShiftAmountForSaturation();
      int saturationAmount = 5 + sound.clippingAmount;
      for (int i = 0; i < numSamples; i++) {
        for (int ch = 0; ch < 2; ch++) {
          int data = stereoBuf[i * 2 + ch];
          int newWorkingValue =
              Functions.lshiftAndSaturateUnknown(data, saturationAmount) + 0x80000000;
          int outputVal =
              Functions.getTanHAntialiased(
                  data, lastSaturationTanHWorkingValue[ch], saturationAmount);
          stereoBuf[i * 2 + ch] = Functions.lshiftAndSaturate(outputVal, shiftAmount);
          lastSaturationTanHWorkingValue[ch] = newWorkingValue;
        }
      }
    }

    // Apply pan
    if (doPanning) {
      for (int i = 0; i < numSamples; i++) {
        int l =
            Functions.lshiftAndSaturate(
                Functions.multiply_32x32_rshift32(stereoBuf[i * 2], ampLR[0]), 2);
        int r =
            Functions.lshiftAndSaturate(
                Functions.multiply_32x32_rshift32(stereoBuf[i * 2 + 1], ampLR[1]), 2);
        stereoBuf[i * 2] = l;
        stereoBuf[i * 2 + 1] = r;
      }
    }
  }

  // ── FM helpers (ports of voice.cpp:1703-1830) ──
  // These are verified faithful from the FM fix session.

  // C: voice.cpp:1703 — {@code amp} is the start amplitude (modulatorAmplitudeLastTime) and {@code
  // ampInc} ramps it per sample toward this block's target. Non-modulator callers pass ampInc 0.
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
      boolean add,
      int ampInc) {
    int phaseNow = ph[pi];
    int ampNow = amp;
    if (fbAmt != 0) {
      int fbVal = fb[fi];
      for (int i = 0; i < n; i++) {
        ampNow += ampInc; // C:1716 — amplitudeNow += amplitudeIncrement
        int fb2 = Functions.signed_saturate(Functions.multiply_32x32_rshift32(fbVal, fbAmt), 22);
        phaseNow += pInc;
        fbVal = SineOsc.doFMNew(phaseNow, fb2);
        if (add) {
          buf[i] = Functions.multiply_accumulate_32x32_rshift32_rounded(buf[i], fbVal, ampNow);
        } else {
          buf[i] = Functions.multiply_32x32_rshift32(fbVal, ampNow);
        }
      }
      fb[fi] = fbVal;
    } else {
      for (int i = 0; i < n; i++) {
        ampNow += ampInc; // C:1746
        phaseNow += pInc;
        int sine = SineOsc.doFMNew(phaseNow, 0);
        if (add) {
          buf[i] = Functions.multiply_accumulate_32x32_rshift32_rounded(buf[i], sine, ampNow);
        } else {
          buf[i] = Functions.multiply_32x32_rshift32(sine, ampNow);
        }
      }
    }
    ph[pi] = phaseNow;
  }

  // C: voice.cpp:1785 — {@code amp}/{@code ampInc} ramp the modulator amplitude per sample.
  private void renderFMWithFeedback(
      int[] buf,
      int n,
      int[] ph,
      int pi,
      int amp,
      int pInc,
      int fbAmt,
      int[] fb,
      int fi,
      int ampInc) {
    int phaseNow = ph[pi];
    int ampNow = amp;
    if (fbAmt != 0) {
      int fbVal = fb[fi];
      for (int i = 0; i < n; i++) {
        ampNow += ampInc;
        int fb2 = Functions.signed_saturate(Functions.multiply_32x32_rshift32(fbVal, fbAmt), 22);
        int sum = buf[i] + fb2;
        phaseNow += pInc;
        fbVal = SineOsc.doFMNew(phaseNow, sum);
        buf[i] = Functions.multiply_32x32_rshift32(fbVal, ampNow);
      }
      fb[fi] = fbVal;
    } else {
      for (int i = 0; i < n; i++) {
        ampNow += ampInc;
        phaseNow += pInc;
        buf[i] = Functions.multiply_32x32_rshift32(SineOsc.doFMNew(phaseNow, buf[i]), ampNow);
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
    renderSineWaveWithFeedback(buf, n, ph, 0, amp, pInc, fbAmt, fb, 0, true, 0);
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

  /** True for the live-input oscillator source types (C OscType::INPUT_L/R/STEREO). */
  static boolean isInputType(OscType t) {
    return t == OscType.INPUT_L || t == OscType.INPUT_R || t == OscType.INPUT_STEREO;
  }

  public int calculateBasePhaseIncrement(int noteCode) {
    if (sound.tuning != null) {
      TuningProvider tuning = sound.tuning;
      // Pass noteCode - 4 to align synth octave indexing!
      int noteWithinOctave = tuning.noteWithinOctaveOf(noteCode - 4);
      int octave = tuning.octaveOf(noteCode - 4);
      int shiftRightAmount = 10 - octave;
      if (shiftRightAmount >= 0) {
        return (shiftRightAmount >= 32)
            ? 0
            : (tuning.noteFrequencyRatio(noteWithinOctave) >>> shiftRightAmount);
      }
      return 0; // inactive / too high
    } else {
      int noteWithinOctave = (noteCode + 240 - 4) % 12;
      int octave = (noteCode + 120 - 4) / 12;
      int shiftRightAmount = 20 - octave;
      if (shiftRightAmount >= 0) {
        return (shiftRightAmount >= 32)
            ? 0
            : (LookupTables.noteFrequencyTable[noteWithinOctave] >>> shiftRightAmount);
      }
      return 0; // inactive / too high
    }
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

  public int getPriorityRating() {
    int activeVoicesCount = 0;
    synchronized (sound.voices) {
      for (Voice v : sound.voices) {
        if (v.active) {
          activeVoicesCount++;
        }
      }
    }
    return
    // Bits 30-31 - manual priority setting
    ((3 - sound.voicePriority) << 30)
        // Bits 27-29 - how many voices that Sound has
        + (Math.min(activeVoicesCount, 7) << 27)
        // Bits 24-26 - envelope state
        + (envelopes[0].state.ordinal() << 24)
        // Bits 0-23 - time entered
        + ((-envelopes[0].timeEnteredState) & (0xFFFFFFFF >>> 8));
  }
}
