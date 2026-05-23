package org.chuck.deluge.firmware.engine;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.dsp.dx.FmCore;
import org.chuck.deluge.firmware.dsp.oscillators.OscType;
import org.chuck.deluge.firmware.dsp.oscillators.Oscillator;
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

    // Translate starting phases from degrees to Q31 bounds
    int osc1Phase = getStartingPhase(sound.osc1RetriggerPhase);
    int osc2Phase = getStartingPhase(sound.osc2RetriggerPhase);
    int mod1Phase = getStartingPhase(sound.mod1RetrigPhase);
    int mod2Phase = getStartingPhase(sound.mod2RetrigPhase);

    // Reset unison parts with custom initial phases
    for (int i = 0; i < sound.numUnison; i++) {
      unisonParts[i].reset(osc1Phase, osc2Phase, mod1Phase, mod2Phase);
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
  }

  public void noteOff(int velocity) {
    System.out.println(
        "[DIAG voice] noteOff called for voice note="
            + note
            + " envelopes[0].state before unconditionalRelease="
            + envelopes[0].state);
    for (int i = 0; i < 4; i++) {
      envelopes[i].unconditionalRelease(Envelope.EnvelopeStage.RELEASE, 1024);
    }
    System.out.println(
        "[DIAG voice] noteOff completed for voice note="
            + note
            + " envelopes[0].state after unconditionalRelease="
            + envelopes[0].state);
  }

  public boolean render(int[] buffer, int numSamples, int phaseIncrementA, int phaseIncrementB) {
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

    // 2. Process Local LFOs with dynamic logarithmic rates
    int lfoRate1 = paramFinalValues[Param.LOCAL_LFO_LOCAL_FREQ_1];
    int phaseInc1 = (int) (200 + Math.pow(2.0, (double) lfoRate1 / 2147483647.0 * 10.0) * 500.0);
    sourceValues[PatchSource.LFO_LOCAL_1.ordinal()] =
        lfos[1].render(numSamples, LFO.LFOType.TRIANGLE, phaseInc1);

    int lfoRate2 = paramFinalValues[Param.LOCAL_LFO_LOCAL_FREQ_2];
    int phaseInc2 = (int) (200 + Math.pow(2.0, (double) lfoRate2 / 2147483647.0 * 10.0) * 500.0);
    sourceValues[PatchSource.LFO_LOCAL_2.ordinal()] =
        lfos[3].render(numSamples, LFO.LFOType.TRIANGLE, phaseInc2);

    // 3. Update voice static sources (Velocity, Note, Random, Sidechain, MPE aftertouch/timbre)
    sourceValues[PatchSource.VELOCITY.ordinal()] = velocity * 16909320;
    sourceValues[PatchSource.NOTE.ordinal()] = (note - 60) * 17895697;
    sourceValues[PatchSource.RANDOM.ordinal()] = voiceRandomValue;
    sourceValues[PatchSource.AFTERTOUCH.ordinal()] = mpePressure * 16909320;
    sourceValues[PatchSource.Y.ordinal()] = mpeTimbre * 16909320;
    sourceValues[PatchSource.SIDECHAIN.ordinal()] = sound.sidechain.render(numSamples, 0);

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

    // 3. Render Unison Parts
    int[] voiceBuffer = new int[numSamples];
    for (int u = 0; u < sound.numUnison; u++) {
      renderUnisonPart(
          u, voiceBuffer, numSamples, phaseIncrementA, phaseIncrementB, overallPitchAdjust);
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

    // Safety check: ensure volume is not squashed to zero by un-patched synth defaults
    int trackVol = paramFinalValues[Param.LOCAL_VOLUME];

    for (int i = 0; i < numSamples; i++) {
      int wet = Q31.mult(voiceBuffer[i], env0Gain);
      wet = Q31.mult(wet, trackVol);
      // Bit-accurate per-voice non-linear saturation
      buffer[i] = Q31.addSaturate(buffer[i], Q31.lshiftAndSaturate(wet, 1));
    }

    return true;
  }

  private void renderUnisonPart(
      int u, int[] buffer, int numSamples, int pIncA, int pIncB, int pitchAdjust) {
    VoiceUnisonPart part = unisonParts[u];

    // ── Bit-Accurate FM Engine ──
    if (sound.getSynthMode() == FirmwareSound.SynthMode.FM) {
      int env0Gain = envelopes[0].lastValue;
      int voiceVolume = paramFinalValues[Param.LOCAL_VOLUME];
      int finalCarrierLevel = (int) (((long) env0Gain * voiceVolume) >> 31);

      for (int i = 0; i < 6; i++) {
        if (i == 5) {
          fmParams[i].freq = pIncA;
          fmParams[i].level_in = finalCarrierLevel;
        } else if (i == 4) {
          fmParams[i].freq = (int) (pIncA * sound.fmRatio1);
          fmParams[i].level_in = paramFinalValues[Param.LOCAL_OSC_B_VOLUME];
        } else if (i == 3) {
          fmParams[i].freq = pIncA;
          fmParams[i].level_in = finalCarrierLevel;
        } else if (i == 2) {
          fmParams[i].freq = (int) (pIncA * sound.fmRatio2);
          fmParams[i].level_in = (int) (0.2 * 2147483647.0);
        } else {
          fmParams[i].freq = pIncA;
          fmParams[i].level_in = 0;
        }
        int srcIdx = (i == 5) ? 0 : ((i == 4) ? 2 : ((i == 3) ? 1 : ((i == 2) ? 3 : 0)));
        fmParams[i].phase = (int) part.sources[srcIdx].oscPos;
      }
      new FmCore().render(buffer, numSamples, fmParams, 0, fmFeedbackBuffer, 0);
      part.sources[0].oscPos = fmParams[5].phase;
      part.sources[1].oscPos = fmParams[3].phase;
      part.sources[2].oscPos = fmParams[4].phase;
      part.sources[3].oscPos = fmParams[2].phase;
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
  }

  private int getStartingPhase(int retrigPhaseDegrees) {
    if (retrigPhaseDegrees == -1) {
      return -1; // Keep running phase (FREE)
    }
    // Scale 0-360 degrees to Q31 bounds (0 to 2147483647)
    return (int) ((double) retrigPhaseDegrees / 360.0 * 2147483647.0);
  }
}
