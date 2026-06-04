package org.chuck.deluge.firmware.engine;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.dsp.dx.FmCore;
import org.chuck.deluge.firmware.dsp.filter.FilterSet;
import org.chuck.deluge.firmware.dsp.oscillators.OscType;
import org.chuck.deluge.firmware.dsp.oscillators.Oscillator;
import org.chuck.deluge.firmware.dsp.oscillators.SineOsc;
import org.chuck.deluge.firmware.model.PolyphonyMode;
import org.chuck.deluge.firmware.modulation.Arpeggiator;
import org.chuck.deluge.firmware.modulation.Envelope;
import org.chuck.deluge.firmware.modulation.LFO;
import org.chuck.deluge.firmware.modulation.automation.AutoParam;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.modulation.patch.PatchSource;
import org.chuck.deluge.firmware.modulation.patch.Patcher;
import org.chuck.deluge.firmware.util.FirmwareUtils;
import org.chuck.deluge.firmware.util.LookupTables;
import org.chuck.deluge.firmware.util.Q31;

/**
 * Port of the Deluge's Voice class. This is the per-note renderer that implements the full signal
 * path.
 */
public class FirmwareVoice {
  public static int testStartPhaseOverrideOsc1 = -2;
  public static int testStartPhaseOverrideOsc2 = -2;
  public final FirmwareSound sound;
  public final Envelope[] envelopes = new Envelope[6]; // kNumEnvelopes = 6 (some for modulation)
  public final LFO[] lfos = new LFO[4]; // lfo1-lfo4 (lfo1/3 global, lfo2/4 local)
  public final Arpeggiator arpeggiator;
  public final int[] paramFinalValues = new int[Param.kNumParams];
  public final int[] sourceValues = new int[PatchSource.kNumPatchSources];
  public final Patcher patcher = new Patcher();
  public final VoiceUnisonPart[] unisonParts = new VoiceUnisonPart[8];

  private final FmCore.FmOpParams[] fmParams = new FmCore.FmOpParams[6];
  private final int[] fmFeedbackBuffer = new int[2];
  // Scratch modulation buffer for the native 2-op FM engine (summed modulator output).
  private int[] fmModBuffer;

  // Internal state
  public int note;
  public int noteCode;
  public int velocity;
  public boolean active = false;
  public int midiChannel = -1;

  // MPE Expression States
  public int mpePitchBend = 8192;
  public int mpePressure = 0;
  public int mpeTimbre = 64;

  // ── Ported High-Fidelity Logic ──
  public int portaEnvelopePos = 0xFFFFFFFF;
  public int portaEnvelopeMaxAmplitude;
  private final boolean[] expressionSourcesCurrentlySmoothing = new boolean[3]; // X, Y, Z
  private int voiceRandomValue;
  public final FilterSet filterSet = new FilterSet();

  // Zero-GC Stereo Voice buffers
  private org.chuck.deluge.firmware.dsp.StereoSample[] voiceBuffer = null;
  private int[] tempMonoBuffer = null;

  private void prepareVoiceBuffer(int numSamples) {
    if (voiceBuffer == null || voiceBuffer.length < numSamples) {
      voiceBuffer = new org.chuck.deluge.firmware.dsp.StereoSample[numSamples];
      for (int i = 0; i < numSamples; i++) {
        voiceBuffer[i] = new org.chuck.deluge.firmware.dsp.StereoSample();
      }
    } else {
      for (int i = 0; i < numSamples; i++) {
        voiceBuffer[i].l = 0;
        voiceBuffer[i].r = 0;
      }
    }
  }

  private void prepareTempMonoBuffer(int numSamples) {
    if (tempMonoBuffer == null || tempMonoBuffer.length < numSamples) {
      tempMonoBuffer = new int[numSamples];
    } else {
      java.util.Arrays.fill(tempMonoBuffer, 0);
    }
  }

  public FirmwareVoice(FirmwareSound sound) {
    this.sound = sound;
    for (int i = 0; i < envelopes.length; i++) envelopes[i] = new Envelope();
    for (int i = 0; i < lfos.length; i++) lfos[i] = new LFO();
    for (int i = 0; i < unisonParts.length; i++) unisonParts[i] = new VoiceUnisonPart();
    for (int i = 0; i < 6; i++) fmParams[i] = new FmCore.FmOpParams();
    this.arpeggiator = new Arpeggiator(new Arpeggiator.Settings());
  }

  public void noteOn(int note, int vel) {
    // ── Porta Logic ──
    if (this.active && sound.polyphonic != PolyphonyMode.POLY) {
      portaEnvelopePos = 0;
      portaEnvelopeMaxAmplitude = (int) (((long) this.noteCode - note) << 8);
    } else {
      portaEnvelopePos = 0xFFFFFFFF;
    }

    this.note = note;
    this.noteCode = note;
    this.velocity = vel;
    this.active = true;
    filterSet.reset();

    // Reset unison parts with custom initial phases (generating unique per-keypress starting phases
    // for each unison part if -1!)
    for (int i = 0; i < sound.numUnison; i++) {
      int uOsc1Phase =
          testStartPhaseOverrideOsc1 != -2
              ? testStartPhaseOverrideOsc1
              : (sound.osc1RetriggerPhase == -1
                  ? java.util.concurrent.ThreadLocalRandom.current().nextInt(2147483647)
                  : getStartingPhase(sound.osc1RetriggerPhase));
      int uOsc2Phase =
          testStartPhaseOverrideOsc2 != -2
              ? testStartPhaseOverrideOsc2
              : (sound.osc2RetriggerPhase == -1
                  ? java.util.concurrent.ThreadLocalRandom.current().nextInt(2147483647)
                  : getStartingPhase(sound.osc2RetriggerPhase));

      if (testStartPhaseOverrideOsc1 != -2) {
        System.out.printf(
            "  [DIAG noteOn] Phase override applied: osc1=%d | osc2=%d\n", uOsc1Phase, uOsc2Phase);
      }

      int uMod1Phase =
          sound.mod1RetrigPhase == -1
              ? java.util.concurrent.ThreadLocalRandom.current().nextInt(2147483647)
              : getStartingPhase(sound.mod1RetrigPhase);
      int uMod2Phase =
          sound.mod2RetrigPhase == -1
              ? java.util.concurrent.ThreadLocalRandom.current().nextInt(2147483647)
              : getStartingPhase(sound.mod2RetrigPhase);

      unisonParts[i].reset(uOsc1Phase, uOsc2Phase, uMod1Phase, uMod2Phase);
      unisonParts[i].sources[0].active = true;
      unisonParts[i].sources[1].active = true;
      unisonParts[i].sources[2].active = true;
      unisonParts[i].sources[3].active = true;

      // Prime sample oscillators
      for (int s = 0; s < 2; s++) {
        if (sound.oscTypes[s] == OscType.SAMPLE && sound.samples[s] != null) {
          unisonParts[i].sources[s].noteOn(sound.samples[s], sound.sampleSettings[s], 0);
        }
      }
    }

    // Initialize voice-level random value
    this.voiceRandomValue = (int) (Math.random() * 2147483647.0);

    // Trigger all 4 envelopes
    for (int i = 0; i < 4; i++) {
      envelopes[i].noteOn(false);
    }

    // DX7 patch: configure and trigger all unison DX7 engines (internal EGs shape the carrier
    // levels).
    if (sound.isDx7()) {
      for (int i = 0; i < sound.numUnison; i++) {
        if (unisonParts[i].sources[0].dxVoice == null) {
          unisonParts[i].sources[0].dxVoice = new org.chuck.audio.util.Dx7Engine(44100.0f);
        }
        org.chuck.audio.util.Dx7Engine dxVoice = unisonParts[i].sources[0].dxVoice;
        dxVoice.loadPatch(sound.dx7Patch);
        dxVoice.setForceVintage(sound.dx7EngineType);
        dxVoice.setRandomDetuneScale(sound.dx7RandomDetune);
        dxVoice.noteOn(note, vel);
      }
    }
  }

  public void noteOff(int velocity) {
    int releaseParam = paramFinalValues[Param.LOCAL_ENV_0_RELEASE];
    System.out.println(
        "[DIAG voice] noteOff called for voice note="
            + note
            + " releaseParam="
            + releaseParam
            + " envelopes[0].state before="
            + envelopes[0].state);
    for (int i = 0; i < 4; i++) {
      envelopes[i].unconditionalRelease(Envelope.EnvelopeStage.RELEASE, 1024);
    }
    if (sound.isDx7()) {
      for (int i = 0; i < sound.numUnison; i++) {
        if (unisonParts[i].sources[0].dxVoice != null) {
          unisonParts[i].sources[0].dxVoice.noteOff();
        }
      }
    }
    System.out.println(
        "[DIAG voice] noteOff completed for voice note="
            + note
            + " envelopes[0].state after="
            + envelopes[0].state);
  }

  /**
   * Renders all active unison DX7 engines into the stereo voice buffer with custom detuning, pitch
   * bend, constant-power panning, and MPE modulation hookups.
   */
  private void renderDx7(
      org.chuck.deluge.firmware.dsp.StereoSample[] voiceBuffer,
      int numSamples,
      int overallPitchAdjust) {
    // Convert overallPitchAdjust scale factor in Q31 to Q24 log-frequency offset
    double scale = (double) overallPitchAdjust / 2147483648.0;
    int overallPitchBendOffset = 0;
    if (scale > 0.0) {
      overallPitchBendOffset = (int) (Math.log(scale) / Math.log(2.0) * (1 << 24));
    }

    double gainScale = 1.0 / Math.sqrt(sound.numUnison);
    int activeParts = 0;

    for (int u = 0; u < sound.numUnison; u++) {
      org.chuck.audio.util.Dx7Engine dxVoice = unisonParts[u].sources[0].dxVoice;
      if (dxVoice == null || !dxVoice.isActive()) continue;

      activeParts++;

      // Route virtual CC modulations (Aftertouch and Mod Wheel/Timbre)
      dxVoice.aftertouch = mpePressure; // MPE pressure (0-127)
      dxVoice.modWheel = mpeTimbre; // MPE Timbre/Slide or Mod Wheel (0-127)

      // C++ voice.cpp: calculate detune per unison part
      double offset = 0.0;
      if (sound.numUnison > 1) {
        offset = (double) (2 * u - (sound.numUnison - 1)) / (double) (sound.numUnison - 1);
      }
      double cents = (double) sound.unisonDetune * offset;
      int centsOffset = (int) (cents * (1 << 24) / 1200.0);

      // Pass total pitch bend + cents detuning into the DX7 engine
      dxVoice.pitchBendOffset = overallPitchBendOffset + centsOffset;

      // Render samples into tempMonoBuffer
      prepareTempMonoBuffer(numSamples);
      for (int i = 0; i < numSamples; i++) {
        tempMonoBuffer[i] = (int) (dxVoice.tick() * (2147483647.0 / 4.0));
      }

      // Constant power panning calculation incorporating track panning and unison spread
      int voicePan = paramFinalValues[Param.LOCAL_PAN]; // Q31
      double uPan = (double) voicePan / 2147483648.0;
      uPan += 0.6 * offset; // 0.6 spread
      if (uPan < -1.0) uPan = -1.0;
      if (uPan > 1.0) uPan = 1.0;

      double angle = (uPan + 1.0) * (Math.PI / 4.0);
      int gainL = (int) (Math.cos(angle) * 2147483647.0);
      int gainR = (int) (Math.sin(angle) * 2147483647.0);

      int scaledL = (int) (gainL * gainScale);
      int scaledR = (int) (gainR * gainScale);

      // Sum stereo-panned signals into voiceBuffer
      for (int i = 0; i < numSamples; i++) {
        voiceBuffer[i].l = Q31.addSaturate(voiceBuffer[i].l, Q31.mult(tempMonoBuffer[i], scaledL));
        voiceBuffer[i].r = Q31.addSaturate(voiceBuffer[i].r, Q31.mult(tempMonoBuffer[i], scaledR));
      }
    }

    if (activeParts == 0) {
      active = false;
    }
  }

  public boolean render(
      org.chuck.deluge.firmware.dsp.StereoSample[] buffer,
      int numSamples,
      int phaseIncrementA,
      int phaseIncrementB) {
    if (!active) return false;

    // ── MPE Smoothing ──
    for (int i = 0; i < 3; i++) {
      if (expressionSourcesCurrentlySmoothing[i]) {
        int targetValue = sound.monophonicExpressionValues[i];
        int diff = (targetValue >> 8) - (sourceValues[PatchSource.X.ordinal() + i] >> 8);
        if (diff == 0) {
          expressionSourcesCurrentlySmoothing[i] = false;
        } else {
          sourceValues[PatchSource.X.ordinal() + i] += (diff * numSamples) / 4;
        }
      }
    }

    int sourcesChanged = 0xFFFFFFFF;

    // 0. Synchronize Parameters
    System.arraycopy(sound.paramNeutralValues, 0, paramFinalValues, 0, Param.kNumParams);

    // 1. Process Envelopes
    int attack = paramFinalValues[Param.LOCAL_ENV_0_ATTACK];
    int decay = paramFinalValues[Param.LOCAL_ENV_0_DECAY];
    int sustain = (sound.isDrum) ? 0 : paramFinalValues[Param.LOCAL_ENV_0_SUSTAIN];
    int release = paramFinalValues[Param.LOCAL_ENV_0_RELEASE];

    // Sanitize increments if zero
    if (attack == 0) attack = 20000;
    if (decay == 0) decay = 400;
    if (release == 0) release = 400;
    if (!sound.isDrum && sustain == 0) sustain = ONE / 2;

    // 1. Process Envelopes 0 to 3
    int env0 =
        envelopes[0].render(
            numSamples, attack, decay, sustain, release, LookupTables.decayTableSmall8);
    if (envelopes[0].state == Envelope.EnvelopeStage.OFF) {
      active = false;
      return false;
    }
    // NOTE: firmware envelope.cpp centres the patch source: (lastValue - 1073741824) << 1. Doing
    // that here is correct for env-as-modulation but changes env2->cutoff sweeps in a way that
    // needs
    // a hardware A/B to validate (it pushed Dx7C's filter near-shut). Left uncentred pending that
    // verification — see docs/java-port-review-non-dx7-2026-06-03.md finding #1.
    sourceValues[PatchSource.ENVELOPE_0.ordinal()] = env0;

    for (int i = 1; i < 4; i++) {
      int eAttack = paramFinalValues[Param.LOCAL_ENV_0_ATTACK + i];
      int eDecay = paramFinalValues[Param.LOCAL_ENV_0_DECAY + i];
      int eSustain = paramFinalValues[Param.LOCAL_ENV_0_SUSTAIN + i];
      int eRelease = paramFinalValues[Param.LOCAL_ENV_0_RELEASE + i];

      if (eAttack == 0) eAttack = 20000;
      if (eDecay == 0) eDecay = 400;
      if (eRelease == 0) eRelease = 400;

      int envVal =
          envelopes[i].render(
              numSamples, eAttack, eDecay, eSustain, eRelease, LookupTables.decayTableSmall8);
      sourceValues[PatchSource.ENVELOPE_0.ordinal() + i] = envVal;
    }

    // 2. Process Local LFOs. Faithful: for an unsynced LFO the phase increment is the exp-curved
    // rate param directly (Voice::getLocalLFOPhaseIncrement), not an ad-hoc formula.
    int phaseInc1 = paramFinalValues[Param.LOCAL_LFO_LOCAL_FREQ_1];
    sourceValues[PatchSource.LFO_LOCAL_1.ordinal()] =
        lfos[1].render(numSamples, sound.lfoWaveforms[1], phaseInc1);

    int phaseInc2 = paramFinalValues[Param.LOCAL_LFO_LOCAL_FREQ_2];
    sourceValues[PatchSource.LFO_LOCAL_2.ordinal()] =
        lfos[3].render(numSamples, sound.lfoWaveforms[3], phaseInc2);

    // 3. Update voice static sources (Velocity, Note, Random, Sidechain, MPE aftertouch/timbre)
    sourceValues[PatchSource.VELOCITY.ordinal()] = velocity * 16909320;
    sourceValues[PatchSource.NOTE.ordinal()] = (note - 60) * 17895697;
    sourceValues[PatchSource.RANDOM.ordinal()] = voiceRandomValue;
    sourceValues[PatchSource.AFTERTOUCH.ordinal()] = mpePressure * 16909320;
    sourceValues[PatchSource.Y.ordinal()] = mpeTimbre * 16909320;
    sourceValues[PatchSource.SIDECHAIN.ordinal()] = sound.sidechain.lastValue;

    // Copy global sources (LFO 1 and 3 are global)
    for (int i = 0; i < PatchSource.kFirstLocalSource; i++) {
      sourceValues[i] = sound.globalSourceValues[i];
    }

    // Initialize paramFinalValues with neutral/automated values before patching
    for (int i = 0; i < paramFinalValues.length; i++) {
      int val = (i < sound.paramNeutralValues.length) ? sound.paramNeutralValues[i] : 0;
      AutoParam autoParam = sound.paramManager.getAutomatedParam(i);
      if (autoParam != null) {
        val = autoParam.currentValue;
      }
      paramFinalValues[i] = val;
    }

    // Perform patching
    patcher.performPatching(
        sourcesChanged, sound, sound.paramManager, sourceValues, paramFinalValues);

    // ── Pitch Calculation ──
    int overallPitchAdjust = paramFinalValues[Param.LOCAL_PITCH_ADJUST];

    // Apply polyphonic MPE pitch bend offset
    if (mpePitchBend != 8192) {
      double bendSemitones = ((double) mpePitchBend - 8192.0) / 8192.0 * 48.0;
      double bendFactor = Math.pow(2.0, bendSemitones / 12.0);
      overallPitchAdjust = (int) (overallPitchAdjust * bendFactor);
    }

    // Porta
    if (Integer.compareUnsigned(portaEnvelopePos, 8388608) < 0) {
      int envValue = FirmwareUtils.getDecay4(portaEnvelopePos, 23);
      int pitchAdjustmentHere =
          2147483647 + (int) (((long) envValue * portaEnvelopeMaxAmplitude) >> 30);
      overallPitchAdjust = (int) (((long) overallPitchAdjust * pitchAdjustmentHere) >> 31);
      portaEnvelopePos += 1000 * numSamples;
    }

    // Dynamically calculate phaseIncrementB to support precise Oscillator 2 transposition & cents
    // detuning!
    int osc2PitchOffset = paramFinalValues[Param.LOCAL_OSC_B_PITCH_ADJUST];
    double osc2PitchFactor = Math.pow(2.0, (double) osc2PitchOffset / (12.0 * 17895697.0));
    phaseIncrementB = (int) (phaseIncrementA * osc2PitchFactor);

    // 3. Render Unison Parts in Stereo
    prepareVoiceBuffer(numSamples);
    if (sound.isDx7()) {
      renderDx7(voiceBuffer, numSamples, overallPitchAdjust);
    } else {
      for (int u = 0; u < sound.numUnison; u++) {
        prepareTempMonoBuffer(numSamples);
        renderUnisonPart(
            u, tempMonoBuffer, numSamples, phaseIncrementA, phaseIncrementB, overallPitchAdjust);

        // Unison part constant-power panning calculation
        double offset = 0.0;
        if (sound.numUnison > 1) {
          offset = (double) (2 * u - (sound.numUnison - 1)) / (double) (sound.numUnison - 1);
        }
        int voicePan = paramFinalValues[Param.LOCAL_PAN]; // Q31
        double uPan = (double) voicePan / 2147483648.0;
        uPan += 0.6 * offset; // 0.6 spread
        if (uPan < -1.0) uPan = -1.0;
        if (uPan > 1.0) uPan = 1.0;

        double angle = (uPan + 1.0) * (Math.PI / 4.0);
        int gainL = (int) (Math.cos(angle) * 2147483647.0);
        int gainR = (int) (Math.sin(angle) * 2147483647.0);

        for (int i = 0; i < numSamples; i++) {
          voiceBuffer[i].l = Q31.addSaturate(voiceBuffer[i].l, Q31.mult(tempMonoBuffer[i], gainL));
          voiceBuffer[i].r = Q31.addSaturate(voiceBuffer[i].r, Q31.mult(tempMonoBuffer[i], gainR));
        }
      }
    }

    // ── Final Gain & Saturation ──
    int env0Gain = envelopes[0].lastValue;

    if (noteCode == 72 && Math.random() < 0.005) {
      System.out.printf(
          "      [DSP VOICE STATE] Note=72 | LOCAL_VOLUME=%d | LOCAL_OSC_A_VOLUME=%d | Env0Gain=%d\n",
          paramFinalValues[Param.LOCAL_VOLUME],
          paramFinalValues[Param.LOCAL_OSC_A_VOLUME],
          env0Gain);
    }

    // Apply envelope gain and track volume to stereo voice buffer before filtering
    int trackVol = paramFinalValues[Param.LOCAL_VOLUME];
    for (int i = 0; i < numSamples; i++) {
      int wetL = Q31.mult(voiceBuffer[i].l, env0Gain);
      voiceBuffer[i].l = Q31.mult(wetL, trackVol);

      int wetR = Q31.mult(voiceBuffer[i].r, env0Gain);
      voiceBuffer[i].r = Q31.mult(wetR, trackVol);
    }

    // Configure and render dynamic polyphonic per-voice filter set in stereo!
    filterSet.setConfig(
        paramFinalValues[Param.LOCAL_LPF_FREQ],
        paramFinalValues[Param.LOCAL_LPF_RESONANCE],
        sound.lpfMode,
        paramFinalValues[Param.LOCAL_LPF_MORPH],
        paramFinalValues[Param.LOCAL_HPF_FREQ],
        paramFinalValues[Param.LOCAL_HPF_RESONANCE],
        sound.hpfMode,
        paramFinalValues[Param.LOCAL_HPF_MORPH],
        Q31.ONE,
        sound.filterRoute);
    filterSet.renderStereoInterleaved(voiceBuffer, numSamples);

    for (int i = 0; i < numSamples; i++) {
      // Bit-accurate per-voice non-linear saturation summing (sum directly without left-shifting!)
      buffer[i].l = Q31.addSaturate(buffer[i].l, voiceBuffer[i].l);
      buffer[i].r = Q31.addSaturate(buffer[i].r, voiceBuffer[i].r);
    }

    return true;
  }

  private void renderUnisonPart(
      int u, int[] buffer, int numSamples, int pIncA, int pIncB, int pitchAdjust) {
    VoiceUnisonPart part = unisonParts[u];

    // ── Native 2-op FM Engine (faithful port of voice.cpp's SynthMode::FM path) ──
    // Topology: up to two sine modulators (each with optional self-feedback) sum into one
    // modulation buffer — or modulator 1 chains into modulator 0 — and the two carriers (osc A/B)
    // are phase-modulated by that buffer via SineOsc.doFMNew. The carrier amplitude is the osc
    // volume only; the overall envelope/track volume is applied downstream (see the env0Gain *
    // trackVol pass), so timbre (modulation index) depends solely on the modulator amplitudes,
    // exactly as on hardware. Replaces the former dexed-FmCore approximation.
    if (sound.getSynthMode() == FirmwareSound.SynthMode.FM) {
      int n = numSamples;
      int carrierIncA = pIncA << 8; // 32-bit phase increment (subtractive uses the same <<8)
      int carrierIncB = pIncB << 8;
      long ciaU = carrierIncA & 0xFFFFFFFFL;
      int modInc0 = (int) Math.round(ciaU * (double) sound.fmRatio1);
      int modInc1 = (int) Math.round(ciaU * (double) sound.fmRatio2);

      int carrierAmp0 = paramFinalValues[Param.LOCAL_OSC_A_VOLUME];
      int carrierAmp1 = paramFinalValues[Param.LOCAL_OSC_B_VOLUME];
      // Live FM depth: base knob value combined with any patch cables (e.g. envelope2 / note ->
      // modulator volume) through the Deluge volume curve, recomputed each block so the timbre
      // tracks the envelope exactly as on hardware.
      int modAmp0 = computeFmModulatorAmplitude(0);
      int modAmp1 = computeFmModulatorAmplitude(1);
      boolean mod0Active = modAmp0 != 0;
      boolean mod1Active = modAmp1 != 0;

      if (fmModBuffer == null || fmModBuffer.length < n) fmModBuffer = new int[n];
      java.util.Arrays.fill(fmModBuffer, 0, n, 0);

      boolean carriersAreSine = false;
      if (mod1Active) {
        renderSineWaveWithFeedback(
            fmModBuffer, n, part.modulatorPhase, 1, modAmp1, modInc1,
            sound.fmModulatorFeedback[1], part.modulatorFeedback, 1, false);
        if (sound.fmModulator1ToModulator0) {
          if (mod0Active) {
            // Modulator 0 receives FM from modulator 1 and replaces the buffer.
            renderFMWithFeedbackReplace(
                fmModBuffer, n, part.modulatorPhase, 0, modAmp0, modInc0,
                sound.fmModulatorFeedback[0], part.modulatorFeedback, 0);
          } else {
            // mod1 -> mod0 but mod0 off: no modulation reaches the carriers.
            carriersAreSine = true;
          }
        } else if (mod0Active) {
          // Both modulators sum into the modulation buffer.
          renderSineWaveWithFeedback(
              fmModBuffer, n, part.modulatorPhase, 0, modAmp0, modInc0,
              sound.fmModulatorFeedback[0], part.modulatorFeedback, 0, true);
        }
      } else if (mod0Active) {
        renderSineWaveWithFeedback(
            fmModBuffer, n, part.modulatorPhase, 0, modAmp0, modInc0,
            sound.fmModulatorFeedback[0], part.modulatorFeedback, 0, false);
      } else {
        carriersAreSine = true;
      }

      if (carriersAreSine) {
        renderCarrierSine(buffer, n, part.sources[0], carrierAmp0, carrierIncA, sound.fmCarrierFeedback[0]);
        renderCarrierSine(buffer, n, part.sources[1], carrierAmp1, carrierIncB, sound.fmCarrierFeedback[1]);
      } else {
        renderCarrierFM(buffer, n, fmModBuffer, part.sources[0], carrierAmp0, carrierIncA, sound.fmCarrierFeedback[0]);
        renderCarrierFM(buffer, n, fmModBuffer, part.sources[1], carrierAmp1, carrierIncB, sound.fmCarrierFeedback[1]);
      }
      return;
    }

    // ── Bit-Accurate Subtractive / Sample Engine ──
    double offset = 0.0;
    if (sound.numUnison > 1) {
      offset = (double) (2 * u - (sound.numUnison - 1)) / (double) (sound.numUnison - 1);
    }
    double cents = (double) sound.unisonDetune * offset;
    double pitchFactor = Math.pow(2.0, cents / 1200.0);
    int detunedPIncA = (int) (pIncA * pitchFactor);
    int detunedPIncB = (int) (pIncB * pitchFactor);

    // ── Bit-Accurate Ring Modulation Engine ──
    // Port of voice.cpp's SynthMode::RINGMOD path: render osc A and osc B at fixed unit amplitude,
    // then multiply them (A * B) scaled by amplitudeForRingMod, with the firmware's per-wave-type
    // amplitude compensation (saw <<1, wavetable <<2). The master volume/envelope and filter are
    // applied downstream, matching the firmware which applies overall amplitude after the product.
    if (sound.getSynthMode() == FirmwareSound.SynthMode.RINGMOD) {
      int[] in0 = new int[numSamples];
      int[] in1 = new int[numSamples];
      int amplitudeForRingMod = 1 << 27;

      for (int s = 0; s < 2; s++) {
        OscType type = sound.oscTypes[s];
        int pInc = (s == 0) ? detunedPIncA : detunedPIncB;
        if (type != OscType.SAMPLE) {
          pInc <<= 8;
        }
        int[] target = (s == 0) ? in0 : in1;
        int[] phase = {(int) part.sources[s].oscPos};
        int pw =
            (s == 0)
                ? paramFinalValues[Param.LOCAL_OSC_A_PHASE_WIDTH]
                : paramFinalValues[Param.LOCAL_OSC_B_PHASE_WIDTH];
        int rp =
            (s == 0)
                ? getStartingPhase(sound.osc1RetriggerPhase)
                : getStartingPhase(sound.osc2RetriggerPhase);
        // Fixed-amplitude render (applyAmplitude=false): write the raw oscillator wave.
        Oscillator.renderOsc(
            type,
            0,
            target,
            0,
            numSamples,
            pInc,
            pw,
            phase,
            false,
            0,
            false,
            0,
            0,
            Math.max(0, rp));
        part.sources[s].oscPos = phase[0];

        // Sine/triangle come out bigger in fixed-amplitude rendering, so saw/wavetable compensate.
        if (type == OscType.SAW) {
          amplitudeForRingMod <<= 1;
        } else if (type == OscType.WAVETABLE) {
          amplitudeForRingMod <<= 2;
        }
      }

      // Unison gain compensation (1/sqrt(numUnison)), consistent with the subtractive path; the
      // mono port sums products across unison parts.
      double gainScale = 1.0 / Math.sqrt(sound.numUnison);
      for (int i = 0; i < numSamples; i++) {
        int prod = (int) (((long) in0[i] * in1[i]) >> 32); // multiply_32x32_rshift32
        int out =
            (int) (((long) prod * amplitudeForRingMod + 0x80000000L) >> 32); // rounded rshift32
        buffer[i] += (int) (out * gainScale);
      }
      return;
    }

    for (int s = 0; s < 2; s++) {
      OscType type = sound.oscTypes[s];
      int vol =
          (s == 0)
              ? paramFinalValues[Param.LOCAL_OSC_A_VOLUME]
              : paramFinalValues[Param.LOCAL_OSC_B_VOLUME];

      double gainScale = 1.0 / Math.sqrt(sound.numUnison);
      int scaledVol = (int) (vol * gainScale);
      int pInc = (s == 0) ? detunedPIncA : detunedPIncB;
      if (type != OscType.SAMPLE) {
        pInc <<= 8;
      }

      if (type == OscType.SAMPLE) {
        if (!part.sources[s].render(buffer, numSamples, pInc, sound.samples[s], scaledVol)) {
          // Sample finished, can we deactivate the voice?
          // (Simplification: only deactivate if oscA finished)
          if (s == 0) active = false;
        }
      } else {
        int[] phase = {(int) part.sources[s].oscPos};
        int pw =
            (s == 0)
                ? paramFinalValues[Param.LOCAL_OSC_A_PHASE_WIDTH]
                : paramFinalValues[Param.LOCAL_OSC_B_PHASE_WIDTH];

        int rp =
            (s == 0)
                ? getStartingPhase(sound.osc1RetriggerPhase)
                : getStartingPhase(sound.osc2RetriggerPhase);
        Oscillator.renderOsc(
            type,
            scaledVol,
            buffer,
            0,
            numSamples,
            pInc,
            pw,
            phase,
            true,
            0,
            false,
            0,
            0,
            Math.max(0, rp));
        part.sources[s].oscPos = phase[0];
      }
    }

    // ── Render Noise Generator ──
    int noiseVol = paramFinalValues[Param.LOCAL_NOISE_VOLUME];
    if (noiseVol > 0) {
      double gainScale = 1.0 / Math.sqrt(sound.numUnison);
      int scaledNoiseVol = (int) (noiseVol * gainScale);
      for (int i = 0; i < numSamples; i++) {
        int noiseSample =
            (int)
                (((long) org.chuck.deluge.firmware.util.FirmwareUtils.getNoise() * scaledNoiseVol)
                    >> 31);
        buffer[i] += noiseSample;
      }
    }

    // NOTE: the firmware does NOT attenuate the multi-source sum here. Source volumes are already
    // capped (LOCAL_OSC_x_VOLUME ~1/4 range) and unison is compensated by 1/sqrt(numUnison), so the
    // straight sum matches voice.cpp; peaks are handled by the master limiter in
    // FirmwareAudioEngine.
    // (Removed a non-firmware `buffer >>= 1|2` clipping hack that attenuated multi-osc patches by
    // 6–12 dB relative to hardware.)
  }

  private int getStartingPhase(int retrigPhaseDegrees) {
    if (retrigPhaseDegrees == -1) {
      return -1; // Keep running phase (FREE)
    }
    // Scale 0-360 degrees to Q31 bounds (0 to 2147483647)
    return (int) ((double) retrigPhaseDegrees / 360.0 * 2147483647.0);
  }

  public static int linearToDx7Level(int q31Gain) {
    if (q31Gain <= 0) return 0;
    double gainFloat = q31Gain / 2147483647.0;
    double log2Val = Math.log(gainFloat) / Math.log(2.0);
    int level = (14 << 24) + (int) (log2Val * 16777216.0);
    return Math.max(0, Math.min(14 << 24, level));
  }

  // ── Native 2-op FM render primitives (ports of Voice::renderSineWaveWithFeedback /
  // renderFMWithFeedback / renderFMWithFeedbackAdd). Phase and per-source feedback memory are
  // passed by (array,index) so callers can hold them in the unison-part state.

  /**
   * Render a sine (optionally self-FM'd via feedback) into {@code buf}, either overwriting or
   * accumulating. Used for the FM modulators and for carriers when no modulator is active.
   */
  private static void renderSineWaveWithFeedback(
      int[] buf,
      int n,
      int[] phaseArr,
      int pi,
      int amplitude,
      int phaseInc,
      int feedbackAmount,
      int[] fbArr,
      int fi,
      boolean add) {
    int phaseNow = phaseArr[pi];
    if (feedbackAmount != 0) {
      int feedbackValue = fbArr[fi];
      for (int i = 0; i < n; i++) {
        int feedback =
            Q31.signedSaturate(Q31.multiply_32x32_rshift32(feedbackValue, feedbackAmount), 22);
        phaseNow += phaseInc;
        feedbackValue = SineOsc.doFMNew(phaseNow, feedback);
        if (add) {
          buf[i] = Q31.multiply_accumulate_32x32_rshift32_rounded(buf[i], feedbackValue, amplitude);
        } else {
          buf[i] = Q31.multiply_32x32_rshift32(feedbackValue, amplitude);
        }
      }
      fbArr[fi] = feedbackValue;
    } else {
      for (int i = 0; i < n; i++) {
        phaseNow += phaseInc;
        int sine = SineOsc.doFMNew(phaseNow, 0);
        if (add) {
          buf[i] = Q31.multiply_accumulate_32x32_rshift32_rounded(buf[i], sine, amplitude);
        } else {
          buf[i] = Q31.multiply_32x32_rshift32(sine, amplitude);
        }
      }
    }
    phaseArr[pi] = phaseNow;
  }

  /**
   * Modulator 0 receiving FM from the contents of {@code buf} (modulator 1's output), replacing the
   * buffer with its own output. Port of renderFMWithFeedback (fmBuffer == buffer in firmware).
   */
  private static void renderFMWithFeedbackReplace(
      int[] buf,
      int n,
      int[] phaseArr,
      int pi,
      int amplitude,
      int phaseInc,
      int feedbackAmount,
      int[] fbArr,
      int fi) {
    int phaseNow = phaseArr[pi];
    if (feedbackAmount != 0) {
      int feedbackValue = fbArr[fi];
      for (int i = 0; i < n; i++) {
        int feedback =
            Q31.signedSaturate(Q31.multiply_32x32_rshift32(feedbackValue, feedbackAmount), 22);
        int sum = buf[i] + feedback;
        phaseNow += phaseInc;
        feedbackValue = SineOsc.doFMNew(phaseNow, sum);
        buf[i] = Q31.multiply_32x32_rshift32(feedbackValue, amplitude);
      }
      fbArr[fi] = feedbackValue;
    } else {
      for (int i = 0; i < n; i++) {
        phaseNow += phaseInc;
        int fmValue = SineOsc.doFMNew(phaseNow, buf[i]);
        buf[i] = Q31.multiply_32x32_rshift32(fmValue, amplitude);
      }
    }
    phaseArr[pi] = phaseNow;
  }

  /** Carrier phase-modulated by {@code fmBuffer}, accumulated into {@code buf}. */
  private static void renderFMWithFeedbackAdd(
      int[] buf,
      int n,
      int[] fmBuffer,
      int[] phaseArr,
      int pi,
      int amplitude,
      int phaseInc,
      int feedbackAmount,
      int[] fbArr,
      int fi) {
    int phaseNow = phaseArr[pi];
    if (feedbackAmount != 0) {
      int feedbackValue = fbArr[fi];
      for (int i = 0; i < n; i++) {
        int feedback =
            Q31.signedSaturate(Q31.multiply_32x32_rshift32(feedbackValue, feedbackAmount), 22);
        int sum = fmBuffer[i] + feedback;
        phaseNow += phaseInc;
        feedbackValue = SineOsc.doFMNew(phaseNow, sum);
        buf[i] = Q31.multiply_accumulate_32x32_rshift32_rounded(buf[i], feedbackValue, amplitude);
      }
      fbArr[fi] = feedbackValue;
    } else {
      for (int i = 0; i < n; i++) {
        phaseNow += phaseInc;
        int sine = SineOsc.doFMNew(phaseNow, fmBuffer[i]);
        buf[i] = Q31.multiply_accumulate_32x32_rshift32_rounded(buf[i], sine, amplitude);
      }
    }
    phaseArr[pi] = phaseNow;
  }

  /**
   * Live amplitude of FM modulator {@code m} (0 = modulator1, 1 = modulator2): the stored base knob
   * value combined with any patch cables targeting its volume param, run through the Deluge
   * patched-param volume curve. Faithful port of the firmware's combineCablesLinear +
   * getFinalParameterValueVolume for LOCAL_MODULATOR_0/1_VOLUME (modulator neutral 2^25, range
   * 2^30). Cables (e.g. envelope2 -> modulator1Volume) make the FM depth dynamic.
   */
  private int computeFmModulatorAmplitude(int m) {
    int paramId = Param.LOCAL_MODULATOR_0_VOLUME + m;
    int running =
        FirmwareUtils.patchCombineLinearStep(536870912, sound.fmModulatorAmountBase[m], 1073741824);
    for (var dest : sound.paramManager.getPatchCableSet().destinations) {
      if (dest.paramId != paramId) continue;
      for (var cable : dest.cables) {
        running =
            FirmwareUtils.patchCombineLinearStep(
                running, sourceValues[cable.from.ordinal()], cable.getAmount());
      }
      break;
    }
    return FirmwareUtils.getFinalParameterValueVolume(33554432, running - 536870912);
  }

  /** Render one FM carrier (osc A/B) modulated by the modulation buffer, into the voice buffer. */
  private void renderCarrierFM(
      int[] buf,
      int n,
      int[] fmBuffer,
      VoiceUnisonPartSource src,
      int amplitude,
      int phaseInc,
      int feedbackAmount) {
    if (amplitude == 0) return;
    int[] ph = {(int) src.oscPos};
    int[] fb = {src.carrierFeedback};
    renderFMWithFeedbackAdd(buf, n, fmBuffer, ph, 0, amplitude, phaseInc, feedbackAmount, fb, 0);
    src.oscPos = ph[0] & 0xFFFFFFFFL;
    src.carrierFeedback = fb[0];
  }

  /** Render one FM carrier as a plain sine (no active modulators), into the voice buffer. */
  private void renderCarrierSine(
      int[] buf, int n, VoiceUnisonPartSource src, int amplitude, int phaseInc, int feedbackAmount) {
    if (amplitude == 0) return;
    int[] ph = {(int) src.oscPos};
    int[] fb = {src.carrierFeedback};
    renderSineWaveWithFeedback(buf, n, ph, 0, amplitude, phaseInc, feedbackAmount, fb, 0, true);
    src.oscPos = ph[0] & 0xFFFFFFFFL;
    src.carrierFeedback = fb[0];
  }
}
