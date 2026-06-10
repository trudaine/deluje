package org.chuck.deluge.firmware.model;

import org.chuck.deluge.firmware.dsp.StereoSample;
// timeStretcher removed — replaced with simple pitched read
import org.chuck.deluge.firmware.model.sample.Sample;
import org.chuck.deluge.firmware.modulation.Envelope;

/**
 * Port of the Deluge's AudioClip class. Handles bit-accurate sample playback with real-time
 * time-stretching and lifecycle management.
 */
public class AudioClip extends Clip {
  public Sample sample;
  // TimeStretcher field removed — simple pitched read in place
  public final Envelope outputEnvelope = new Envelope();
  public int timeStretchRatio = 1 << 24; // 1.0
  public boolean doingLateStart = false;

  public AudioClip() {
    super(ClipType.AUDIO);
  }

  @Override
  public int getMaxLength() {
    return sample != null ? sample.getNumSamples() : 0;
  }

  @Override
  public void resumePlayback(boolean mayMakeSound) {
    if (sample == null || sample.unplayable) return;

    // ── Bit-Accurate Time-Stretch Sync ──
    // Resync time-stretcher position to lastProcessedPos (24-bit fractional)
    // timeStretcher.samplePosBig removed

    if (!mayMakeSound) {
      return;
    }

    // ── Late Start Logic ──
    // If already playing, we let the stretcher handle it.
    // Otherwise, we flag a late start which will trigger note-on in the next render.
    if (outputEnvelope.state == Envelope.EnvelopeStage.OFF) {
      doingLateStart = true;
    }
  }

  @Override
  public void expectNoFurtherTicks(boolean actuallySoundChange) {
    if (actuallySoundChange) {
      // ── Bit-Accurate Release ──
      if (doingLateStart) {
        if (outputEnvelope.state.ordinal() < Envelope.EnvelopeStage.FAST_RELEASE.ordinal()) {
          doingLateStart = false;
          outputEnvelope.unconditionalOff();
        } else {
          doingLateStart = false;
        }
      } else {
        // Fade out when stopping
        outputEnvelope.unconditionalRelease(Envelope.EnvelopeStage.FAST_RELEASE, 1024);
      }
    }
  }

  @Override
  public boolean isPlayingAutomationNow() {
    return paramManager.mightContainAutomation();
  }

  @Override
  public boolean backtrackingCouldLoopBackToEnd() {
    return sequenceDirectionMode == SequenceDirection.PINGPONG;
  }

  public void render(StereoSample[] buffer, int numSamples, int phaseIncrement) {
    if (sample == null || sample.data == null) return;

    if (doingLateStart) {
      outputEnvelope.noteOn(false);
      doingLateStart = false;
    }

    if (outputEnvelope.state == Envelope.EnvelopeStage.OFF) return;

    // In Java, we convert float data to int for the high-fidelity processors
    int[] monoData = new int[sample.data.length];
    for (int i = 0; i < sample.data.length; i++) {
      monoData[i] = (int) (sample.data[i] * 2147483647.0f);
    }

    int[] output = new int[numSamples];
    // Simple pitched read (old TimeStretcher deleted; proper fw2 VoiceSample path TBD).
    long pos = (lastProcessedPos << 24);
    for (int i = 0; i < numSamples; i++) {
      int idx = (int) ((pos >> 24) & 0x7FFFFFFFL) % monoData.length;
      output[i] = monoData[idx];
      pos += ((long) phaseIncrement << 8);
    }

    // Process Envelope (dummy values for now)
    int env = outputEnvelope.render(numSamples, 1000, 1000, 1 << 30, 1000, null);

    for (int i = 0; i < numSamples; i++) {
      int wet = (int) (((long) output[i] * env) >> 31);
      buffer[i].l = wet;
      buffer[i].r = wet;
    }
  }
}
