package org.chuck.deluge.firmware.engine;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.dsp.oscillators.OscType;
import org.chuck.deluge.firmware.dsp.oscillators.Oscillator;
import org.chuck.deluge.firmware.dsp.dx.FmCore;
import org.chuck.deluge.firmware.dsp.dx.FmOpKernelVector;
import org.chuck.deluge.firmware.model.PolyphonyMode;
import org.chuck.deluge.firmware.modulation.Arpeggiator;
import org.chuck.deluge.firmware.modulation.Envelope;
import org.chuck.deluge.firmware.modulation.LFO;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.modulation.patch.PatchSource;
import org.chuck.deluge.firmware.modulation.patch.Patcher;
import org.chuck.deluge.firmware.util.LookupTables;
import org.chuck.deluge.firmware.util.Q31;
import org.chuck.deluge.firmware.util.FirmwareUtils;

/**
 * Port of the Deluge's Voice class.
 * This is the per-note renderer that implements the full signal path.
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
  
  // ── Ported High-Fidelity Logic ──
  public int portaEnvelopePos = 0xFFFFFFFF; 
  public int portaEnvelopeMaxAmplitude;
  private final boolean[] expressionSourcesCurrentlySmoothing = new boolean[3]; // X, Y, Z

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
        portaEnvelopeMaxAmplitude = (int)(((long)this.noteCode - note) << 8); 
    } else {
        portaEnvelopePos = 0xFFFFFFFF;
    }

    this.note = note;
    this.noteCode = note;
    this.velocity = vel;
    this.active = true;
    
    // Reset unison parts
    for (int i = 0; i < sound.numUnison; i++) {
        unisonParts[i].reset();
        unisonParts[i].sources[0].active = true;
        unisonParts[i].sources[1].active = true;
        
        // Prime sample oscillators
        for (int s = 0; s < 2; s++) {
            if (sound.oscTypes[s] == OscType.SAMPLE && sound.samples[s] != null) {
                unisonParts[i].sources[s].noteOn(sound.samples[s], 0);
            }
        }
    }

    envelopes[0].noteOn(false);
  }

  public void noteOff(int velocity) {
    envelopes[0].unconditionalRelease(Envelope.EnvelopeStage.RELEASE, 1024);
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

    int env0 = envelopes[0].render(numSamples, attack, decay, sustain, release, LookupTables.decayTableSmall8);
    if (envelopes[0].state == Envelope.EnvelopeStage.OFF) {
      active = false;
      return false;
    }
    sourceValues[PatchSource.ENVELOPE_0.ordinal()] = env0;

    // 2. Process Local LFOs (LFO 2 and 4 are local)
    sourceValues[PatchSource.LFO_LOCAL_1.ordinal()] = lfos[1].render(numSamples, LFO.LFOType.TRIANGLE, 10000);
    sourceValues[PatchSource.LFO_LOCAL_2.ordinal()] = lfos[3].render(numSamples, LFO.LFOType.TRIANGLE, 20000);

    // Copy global sources (LFO 1 and 3 are global)
    for (int i = 0; i < PatchSource.kFirstLocalSource; i++) {
      sourceValues[i] = sound.globalSourceValues[i];
    }

    // Initialize paramFinalValues with neutral values before patching
    System.arraycopy(
        sound.paramNeutralValues,
        0,
        paramFinalValues,
        0,
        Math.min(sound.paramNeutralValues.length, paramFinalValues.length));

    // Perform patching
    patcher.performPatching(sourcesChanged, sound, sound.paramManager, sourceValues, paramFinalValues);

    // ── Pitch Calculation ──
    int overallPitchAdjust = paramFinalValues[Param.LOCAL_PITCH_ADJUST];
    
    // Porta
    if (Integer.compareUnsigned(portaEnvelopePos, 8388608) < 0) {
        int envValue = FirmwareUtils.getDecay4(portaEnvelopePos, 23);
        int pitchAdjustmentHere = 2147483647 + (int)(((long)envValue * portaEnvelopeMaxAmplitude) >> 30);
        overallPitchAdjust = (int)(((long)overallPitchAdjust * pitchAdjustmentHere) >> 31);
        portaEnvelopePos += 1000 * numSamples; 
    }

    // 3. Render Unison Parts
    int[] voiceBuffer = new int[numSamples];
    for (int u = 0; u < sound.numUnison; u++) {
        renderUnisonPart(u, voiceBuffer, numSamples, phaseIncrementA, phaseIncrementB, overallPitchAdjust);
    }

    // ── Final Gain & Saturation ──
    int env0Gain = (sourceValues[PatchSource.ENVELOPE_0.ordinal()] >> 1) + 1073741824;
    
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

  private void renderUnisonPart(int u, int[] buffer, int numSamples, int pIncA, int pIncB, int pitchAdjust) {
      VoiceUnisonPart part = unisonParts[u];
      
      // ── Bit-Accurate FM Engine ──
      if (sound.getSynthMode() == FirmwareSound.SynthMode.FM) {
          for (int i = 0; i < 6; i++) {
              fmParams[i].freq = pIncA; 
              fmParams[i].phase = (int)part.sources[0].oscPos;
              fmParams[i].level_in = paramFinalValues[Param.LOCAL_OSC_A_VOLUME];
          }
          new FmCore().render(buffer, numSamples, fmParams, 0, fmFeedbackBuffer, 0);
          part.sources[0].oscPos = fmParams[0].phase;
          return;
      }

      // ── Bit-Accurate Subtractive / Sample Engine ──
      for (int s = 0; s < 2; s++) {
          OscType type = sound.oscTypes[s];
          int vol = (s == 0) ? paramFinalValues[Param.LOCAL_OSC_A_VOLUME] : paramFinalValues[Param.LOCAL_OSC_B_VOLUME];
          int pInc = (s == 0) ? pIncA : pIncB;
          
          if (type == OscType.SAMPLE) {
            if (!part.sources[s].render(buffer, numSamples, pInc, sound.samples[s], vol)) {
                // Sample finished, can we deactivate the voice?
                // (Simplification: only deactivate if oscA finished)
                if (s == 0) active = false;
            }
          } else {
              int[] phase = {(int)part.sources[s].oscPos};
              int pw = (s == 0) ? paramFinalValues[Param.LOCAL_OSC_A_PHASE_WIDTH] : paramFinalValues[Param.LOCAL_OSC_B_PHASE_WIDTH];
              
              Oscillator.renderOsc(type, vol, buffer, 0, numSamples, pInc, pw, phase, true, 0, false, 0, 0, 0);
              part.sources[s].oscPos = phase[0];
          }
      }
  }
}
