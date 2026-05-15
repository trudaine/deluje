package org.chuck.deluge.firmware.engine;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.dsp.oscillators.OscType;
import org.chuck.deluge.firmware.dsp.oscillators.Oscillator;
import org.chuck.deluge.firmware.modulation.Arpeggiator;
import org.chuck.deluge.firmware.modulation.Envelope;
import org.chuck.deluge.firmware.modulation.LFO;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.modulation.patch.PatchSource;
import org.chuck.deluge.firmware.modulation.patch.Patcher;
import org.chuck.deluge.firmware.util.LookupTables;
import org.chuck.deluge.firmware.util.Q31;

/**
 * Port of the Deluge's Voice class, implementing the bit-accurate signal path for a single note.
 */
public class FirmwareVoice {
  public final FirmwareSound sound;
  public final Envelope[] envelopes = new Envelope[4];
  public final LFO[] lfos = new LFO[2];
  public final Arpeggiator arpeggiator;
  public final int[] paramFinalValues = new int[Param.kNumParams];
  public final int[] sourceValues = new int[PatchSource.kNumPatchSources];
  public final Patcher patcher = new Patcher();

  // Internal state
  public int note;
  public int noteCode;
  public int velocity;
  public boolean active = false;
  public int[] oscPos = new int[2];
  public OscType[] oscTypes = {OscType.SINE, OscType.SINE};

  public FirmwareVoice(FirmwareSound sound) {
    this.sound = sound;
    for (int i = 0; i < envelopes.length; i++) envelopes[i] = new Envelope();
    for (int i = 0; i < lfos.length; i++) lfos[i] = new LFO();
    this.arpeggiator = new Arpeggiator(new Arpeggiator.Settings());
  }

  public void noteOn(int note, int vel) {
    this.note = note;
    this.noteCode = note;
    this.velocity = vel;
    this.active = true;
    envelopes[0].noteOn(false);
  }

  public void noteOff(int velocity) {
    envelopes[0].unconditionalRelease(Envelope.EnvelopeStage.RELEASE, 1024);
  }

  /**
   * Renders audio for this voice into the provided buffer. Replicates the non-linear summing and
   * gain staging of the hardware.
   */
  public boolean render(int[] buffer, int numSamples, int phaseIncrementA, int phaseIncrementB) {
    if (!active) return false;

    int sourcesChanged = 0;

    // 1. Process Envelopes (Fixed-point ticks)
    int env0 = envelopes[0].render(numSamples, 1000, 1000, ONE / 2, 1000, LookupTables.decayTableSmall8);

    if (envelopes[0].state == Envelope.EnvelopeStage.OFF) {
      active = false;
      return false;
    }

    sourceValues[PatchSource.ENVELOPE_0.ordinal()] = env0;
    sourcesChanged |= (1 << PatchSource.ENVELOPE_0.ordinal());

    // 2. Process Local LFOs
    for (int i = 0; i < 2; i++) {
      int lfoVal = lfos[i].render(numSamples, LFO.LFOType.TRIANGLE, 10000); // dummy rate
      sourceValues[PatchSource.LFO_LOCAL_1.ordinal() + i] = lfoVal;
      sourcesChanged |= (1 << (PatchSource.LFO_LOCAL_1.ordinal() + i));
    }

    // Copy global sources
    for (int i = 0; i < PatchSource.kFirstLocalSource; i++) {
      sourceValues[i] = sound.globalSourceValues[i];
      sourcesChanged |= (1 << i);
    }

    // Perform patching
    if (sourcesChanged != 0) {
      patcher.performPatching(
          sourcesChanged, sound, sound.paramManager, sourceValues, paramFinalValues);
    }

    // 3. Render Oscillators (Direct port using Oscillator.renderOsc)
    int[] tempBuffer = new int[numSamples];

    // Osc A
    int[] phaseA = {oscPos[0]};
    Oscillator.renderOsc(
        oscTypes[0],
        paramFinalValues[Param.LOCAL_OSC_A_VOLUME],
        tempBuffer,
        0,
        numSamples,
        phaseIncrementA,
        paramFinalValues[Param.LOCAL_OSC_A_PHASE_WIDTH],
        phaseA,
        false,
        0,
        false,
        0,
        0,
        0);
    oscPos[0] = phaseA[0];

    // Osc B
    int[] phaseB = {oscPos[1]};
    Oscillator.renderOsc(
        oscTypes[1],
        paramFinalValues[Param.LOCAL_OSC_B_VOLUME],
        tempBuffer,
        0,
        numSamples,
        phaseIncrementB,
        paramFinalValues[Param.LOCAL_OSC_B_PHASE_WIDTH],
        phaseB,
        true,
        0,
        false,
        0,
        0,
        0);
    oscPos[1] = phaseB[0];

    // Apply Env0 to combined volume
    int env0Gain = (sourceValues[PatchSource.ENVELOPE_0.ordinal()] >> 1) + 1073741824;
    for (int i = 0; i < numSamples; i++) {
      int wet = Q31.mult(tempBuffer[i], env0Gain);
      // Per-voice saturation
      buffer[i] = Q31.addSaturate(buffer[i], Q31.lshiftAndSaturate(wet, 1));
    }

    return true;
  }
}
