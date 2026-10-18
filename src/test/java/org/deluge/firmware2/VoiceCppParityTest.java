package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.firmware2.Oscillator.OscType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * C++ parity tests for {@link Voice} and {@link Oscillator} against {@code voice.cpp} and {@code
 * oscillator.cpp}.
 */
class VoiceCppParityTest {

  @AfterEach
  void resetGlobals() {
    FirmwareAudioEngine.cpuDireness = 0;
    Voice.testStartPhaseOverrideOsc1.set(-2);
    Voice.testStartPhaseOverrideOsc2.set(-2);
  }

  private static Sound makeInitializedSound() {
    Sound sound = new Sound();
    Sound.initParams(sound.patchedParamValues);
    Sound.setupAsBlankSynth(sound.patchedParamValues, sound, false);
    sound.bitcrushParam = Integer.MIN_VALUE;
    sound.srrParam = Integer.MIN_VALUE;
    return sound;
  }

  private static WaveTable makeTwoCycleWaveTable() {
    int cycleSize = 2048;
    int totalSamples = cycleSize * 2;
    float[] samples = new float[totalSamples];
    for (int i = 0; i < cycleSize; i++) {
      samples[i] = (float) Math.sin(2.0 * Math.PI * i / cycleSize);
      samples[cycleSize + i] = -1.0f + 2.0f * i / cycleSize;
    }
    WaveTable wt = new WaveTable();
    wt.setup(cycleSize, totalSamples);
    WavetableGenerator.generateBands(wt, samples);
    return wt;
  }

  @Test
  void noteOn_legatoModeDoesNotGlideOnFreshNoteOn() {
    // C voice.cpp:182-186 — fresh noteOn in LEGATO mode must not setupPorta; only overlapping
    // notes routed through changeNoteCode (voice.cpp:371-373) glide in LEGATO mode.
    Sound legatoSound = makeInitializedSound();
    legatoSound.polyphonic = Sound.PolyphonyMode.LEGATO;
    legatoSound.portamentoKnob = 0;
    legatoSound.lastNoteCode = 48;

    Voice legatoVoice = new Voice(legatoSound);
    legatoVoice.noteOn(60, 100, -1, null);
    assertEquals(
        0xFFFFFFFF,
        legatoVoice.portaEnvelopePos,
        "Fresh noteOn in LEGATO mode must not start portamento from lastNoteCode");

    legatoVoice.changeNoteCode(72, -1, null);
    assertEquals(
        0,
        legatoVoice.portaEnvelopePos,
        "Overlapping note in LEGATO mode (changeNoteCode) must start portamento");

    // In MONO mode, fresh noteOn DOES glide from lastNoteCode.
    Sound monoSound = makeInitializedSound();
    monoSound.polyphonic = Sound.PolyphonyMode.MONO;
    monoSound.portamentoKnob = 0;
    monoSound.lastNoteCode = 48;

    Voice monoVoice = new Voice(monoSound);
    monoVoice.noteOn(60, 100, -1, null);
    assertEquals(0, monoVoice.portaEnvelopePos, "Fresh noteOn in MONO mode must start portamento");
  }

  @Test
  void render_finalReleaseBlockRampsToZeroBeforeUnassign() {
    // C voice.cpp:755-758, 1683 — when envelope 0 finishes on a block, Voice::render still renders
    // that final block so overallOscAmplitudeNow ramps smoothly from overallOscAmplitudeLastTime
    // down to 0, and returns false at the end of render().
    Voice.testStartPhaseOverrideOsc1.set(0);
    Sound sound = makeInitializedSound();
    sound.lpfMode = FilterSet.FilterMode.OFF;
    sound.hpfMode = FilterSet.FilterMode.OFF;
    sound.oscTypes[0] = OscType.SAW;
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    sound.patchedParamValues[Param.LOCAL_VOLUME] = Integer.MAX_VALUE;

    Voice voice = new Voice(sound);
    voice.noteOn(60, 100, -1, null);

    int[] block0 = new int[64];
    assertTrue(voice.render(block0, 32, false, false));
    assertTrue(voice.overallOscAmplitudeLastTime > 0, "Block 0 must leave non-zero amplitude");

    // Trigger a 1-sample fast release so envelopes[0].render(32, ...) transitions straight to OFF
    // (env0LastValue == Integer.MIN_VALUE) at the start of block 1.
    voice.envelopes[0].unconditionalRelease(Envelope.Stage.FAST_RELEASE, 1 << 24);

    int[] block1 = new int[64];
    boolean stillActive = voice.render(block1, 32, false, false);
    assertFalse(stillActive, "Voice must unassign at the end of the final release block");
    assertFalse(voice.active, "Voice.active must be cleared at the end of the final block");

    long energy = 0;
    for (int sample : block1) {
      energy += Math.abs((long) sample);
    }
    assertTrue(
        energy > 0,
        "Final block must render the ramp-down from overallOscAmplitudeLastTime to 0 instead of dropping abruptly");
    assertEquals(0, voice.overallOscAmplitudeLastTime);
  }

  @Test
  void noteOn_seedsSourceWaveIndexesLastTimeForDelayedSourceActivation() {
    // C voice.cpp:208-211, 1095-1099, 1676 — sourceWaveIndexesLastTime[s] is per-voice per-source,
    // seeded at noteOn and updated every block even when a source is initially silent.
    WaveTable wt = makeTwoCycleWaveTable();
    Sound sound = makeInitializedSound();
    sound.lpfMode = FilterSet.FilterMode.OFF;
    sound.hpfMode = FilterSet.FilterMode.OFF;
    sound.oscTypes[0] = OscType.SINE;
    sound.oscTypes[1] = OscType.WAVETABLE;
    sound.waveTables[1] = wt;
    sound.oscRetriggerPhase[1] = 0;

    int presetWaveIndex = 600_000_000;
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] =
        Integer.MIN_VALUE; // Osc B initially silent
    sound.patchedParamValues[Param.LOCAL_OSC_B_WAVE_INDEX] = presetWaveIndex;

    Voice delayed = new Voice(sound);
    delayed.noteOn(60, 100, -1, null);
    int expectedFinalWaveIndex = delayed.paramFinalValues[Param.LOCAL_OSC_B_WAVE_INDEX];
    assertNotEquals(0, expectedFinalWaveIndex);
    assertEquals(
        expectedFinalWaveIndex,
        delayed.sourceWaveIndexesLastTime[1],
        "Voice.noteOn must seed sourceWaveIndexesLastTime[1] from initial patching");

    int[] dummy = new int[64];
    delayed.render(dummy, 32, false, false);
    assertEquals(
        expectedFinalWaveIndex,
        delayed.sourceWaveIndexesLastTime[1],
        "Voice.render must keep sourceWaveIndexesLastTime[1] updated while Osc B is inactive");

    // Now turn Osc A off and Osc B on for block 1 at the same targetWaveIndex.
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MIN_VALUE;
    sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MAX_VALUE;
    Patcher.performInitialPatching(sound, delayed.sourceValues, delayed.paramFinalValues);
    delayed.sourceAmplitudesLastTime[0] = 0;
    delayed.unisonParts[0].sources[1].oscPos = 0;
    delayed.sourceAmplitudesLastTime[1] = delayed.paramFinalValues[Param.LOCAL_OSC_B_VOLUME] >> 4;
    int[] delayedOut = new int[64];
    delayed.render(delayedOut, 32, false, false);

    // Compare against a voice where Osc B was active from noteOn at the same targetWaveIndex.
    Sound steadySound = makeInitializedSound();
    steadySound.lpfMode = FilterSet.FilterMode.OFF;
    steadySound.hpfMode = FilterSet.FilterMode.OFF;
    steadySound.oscTypes[0] = OscType.SINE;
    steadySound.oscTypes[1] = OscType.WAVETABLE;
    steadySound.waveTables[1] = wt;
    steadySound.oscRetriggerPhase[1] = 0;
    steadySound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MIN_VALUE;
    steadySound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MAX_VALUE;
    steadySound.patchedParamValues[Param.LOCAL_OSC_B_WAVE_INDEX] = presetWaveIndex;

    Voice steady = new Voice(steadySound);
    steady.noteOn(60, 100, -1, null);
    steady.render(new int[64], 32, false, false);
    steady.unisonParts[0].sources[1].oscPos = 0;
    steady.sourceAmplitudesLastTime[1] = steady.paramFinalValues[Param.LOCAL_OSC_B_VOLUME] >> 4;
    int[] steadyOut = new int[64];
    steady.render(steadyOut, 32, false, false);

    assertArrayEquals(
        steadyOut,
        delayedOut,
        "Delayed Osc B activation must not sweep wavetable index from 0 on its first active block");
  }

  @Test
  void renderRingModPath_wavetableOscSyncDivisorUsesPlus65535() {
    // C oscillator.cpp:140-142 — resetterDivideByPhaseIncrement divides 2^31 by
    // (uint16_t)((resetterPhaseIncrement + 65535) >> 16). When low 16 bits of pIncA are 0,
    // +65535 >> 16 must equal pIncA >>> 16, not (pIncA >>> 16) + 1.
    int pIncA = 0x01000000;
    int pIncB = 0x02000000;
    int startOscPosA = 0xFF345678;
    int startOscPosB = 0x10000000;
    long expectedDivisor = ((pIncA & 0xFFFFFFFFL) + 65535) >>> 16;
    long buggyDivisor = ((pIncA & 0xFFFF0000L) + 65536) >>> 16;
    assertEquals(0x0100L, expectedDivisor);
    assertNotEquals(buggyDivisor, expectedDivisor);

    WaveTable wt = makeTwoCycleWaveTable();
    Sound sound = makeInitializedSound();
    sound.synthMode = 2; // Ringmod
    sound.oscillatorSync = true;
    sound.lpfMode = FilterSet.FilterMode.OFF;
    sound.hpfMode = FilterSet.FilterMode.OFF;
    sound.oscTypes[0] = OscType.SINE;
    sound.oscTypes[1] = OscType.WAVETABLE;
    sound.waveTables[1] = wt;
    sound.oscRetriggerPhase[0] = 0;
    sound.oscRetriggerPhase[1] = 0;
    sound.sourceTranspose[1] = 12; // Osc B 1 octave above Osc A (pIncB = 2 * pIncA)
    sound.tuning =
        new TuningProvider() {
          @Override
          public int noteWithinOctaveOf(int noteIndex) {
            return 0;
          }

          @Override
          public int octaveOf(int noteIndex) {
            // Voice.calculateBasePhaseIncrement passes noteCode - 4 (56 for Osc A, 68 for Osc B).
            return noteIndex >= 64 ? 10 : 9;
          }

          @Override
          public int noteIntervalRatio(int noteWithinOctave) {
            return 1 << 30;
          }

          @Override
          public int noteFrequencyRatio(int noteWithinOctave) {
            return pIncB;
          }
        };

    Voice voice = new Voice(sound);
    voice.noteOn(60, 100, -1, null);
    voice.unisonParts[0].sources[0].oscPos = startOscPosA;
    voice.unisonParts[0].sources[1].oscPos = startOscPosB;

    // Reference direct WaveTable.render with C++ divisor vs buggy divisor.
    int expectedResetterDiv = (int) (0x80000000L / expectedDivisor);
    int buggyResetterDiv = (int) (0x80000000L / buggyDivisor);
    int expectedNextOscPosB =
        wt.render(
            new int[32],
            0,
            32,
            pIncB,
            startOscPosB,
            true,
            startOscPosA,
            pIncA,
            expectedResetterDiv,
            0,
            voice.sourceWaveIndexesLastTime[1] + 1073741824,
            0);
    int buggyNextOscPosB =
        wt.render(
            new int[32],
            0,
            32,
            pIncB,
            startOscPosB,
            true,
            startOscPosA,
            pIncA,
            buggyResetterDiv,
            0,
            voice.sourceWaveIndexesLastTime[1] + 1073741824,
            0);
    assertNotEquals(
        buggyNextOscPosB,
        expectedNextOscPosB,
        "Test setup must distinguish C++ +65535 divisor from buggy +65536 divisor");

    int[] out = new int[64];
    voice.render(out, 32, false, false);
    assertEquals(
        expectedNextOscPosB,
        voice.unisonParts[0].sources[1].oscPos,
        "RingMod wavetable osc-sync must use ((pIncA + 65535) >>> 16) divisor from oscillator.cpp:141");
  }

  @Test
  void renderSubtractivePath_dx7ComputeFalseUnassignsUnisonPartAndVoice() {
    // C voice.cpp:2427-2430, 1257-1270 — when Dx7Voice::compute returns false (all operators
    // finished releasing and pitchEnv is up), the unison part unassigns immediately and the voice
    // unassigns when no other sources or filters remain active.
    Sound sound = makeInitializedSound();
    sound.lpfMode = FilterSet.FilterMode.OFF;
    sound.hpfMode = FilterSet.FilterMode.OFF;
    sound.oscTypes[0] = OscType.DX7;
    sound.oscTypes[1] = OscType.SINE;
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;

    // A 156-byte DX7 patch with all operator output levels = 0 and instant release rates = 99.
    byte[] silentDx7Patch = new byte[156];
    for (int op = 0; op < 6; op++) {
      int off = op * 21;
      silentDx7Patch[off + 3] = 99; // R4 = instant release
    }
    silentDx7Patch[126 + 3] = 99; // Pitch env R4 = instant
    silentDx7Patch[134] = 31; // Algorithm 32 (all carriers)
    sound.sourceDx7Patch[0] = silentDx7Patch;

    Voice voice = new Voice(sound);
    voice.noteOn(60, 100, -1, null);
    // Keep amp envelope sustaining long after keyup so only DX7 completion can unassign the voice.
    sound.patchedParamValues[Param.LOCAL_ENV_0_RELEASE] = -2147483648;
    voice.paramFinalValues[Param.LOCAL_ENV_0_RELEASE] = -2147483648;
    voice.noteOff();

    int[] out = new int[64];
    boolean stillActive = voice.render(out, 32, false, false);
    assertFalse(
        voice.unisonParts[0].sources[0].active,
        "DX7 unison part must become inactive when Dx7Voice.compute returns false");
    assertFalse(
        stillActive,
        "Voice must unassign when its only active DX7 source finishes and filters are off");
  }

  @Test
  void oscillator_squarePulseWidthUnsignedPhaseIncAndAnalogSawCpuDireness() {
    // C oscillator.cpp:63 — uint32_t phaseIncrement * 0.6 must treat bit 31 as unsigned.
    int[] buf = new int[16];
    int[] phase = {0};
    Oscillator.renderOsc(
        OscType.SQUARE,
        1 << 27,
        buf,
        0,
        16,
        0x80000000, // unsigned 2^31 (negative if treated as signed int)
        0x20000000, // non-zero pulseWidth triggers phaseIncrement * 0.6
        phase,
        true,
        0,
        false,
        0,
        0,
        0);

    // C oscillator.cpp:74 — ANALOG_SAW_2 falls back to digital SAW when tableNumber >= 8 and
    // tableNumber < AudioEngine::cpuDireness + 6.
    int highPitchInc = 0x10000000; // tableNumber >= 8
    int[] tn = Oscillator.getTableNumber(highPitchInc);
    assertTrue(tn[0] >= 8, "highPitchInc must select band >= 8");

    FirmwareAudioEngine.cpuDireness = 0;
    int[] bufNormal = new int[16];
    int[] phaseNormal = {0};
    Oscillator.renderOsc(
        OscType.ANALOG_SAW_2,
        1 << 27,
        bufNormal,
        0,
        16,
        highPitchInc,
        0,
        phaseNormal,
        true,
        0,
        false,
        0,
        0,
        0);

    FirmwareAudioEngine.cpuDireness = 20;
    int[] bufDire = new int[16];
    int[] phaseDire = {0};
    Oscillator.renderOsc(
        OscType.ANALOG_SAW_2,
        1 << 27,
        bufDire,
        0,
        16,
        highPitchInc,
        0,
        phaseDire,
        true,
        0,
        false,
        0,
        0,
        0);

    int[] bufDigitalSaw = new int[16];
    int[] phaseDigital = {0};
    // Note: when ANALOG_SAW_2 falls back to SAW at oscillator.cpp:75, line 79 (else if SAW) does
    // NOT run retriggerPhase += 2147483648u, so compare against normal ANALOG_SAW_2 to prove the
    // fallback engaged under high cpuDireness.
    assertFalse(
        java.util.Arrays.equals(bufNormal, bufDire),
        "ANALOG_SAW_2 must fall back to digital SAW when tableNumber < cpuDireness + 6");
  }
}
