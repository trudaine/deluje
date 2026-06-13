package org.chuck.deluge.firmware.model;

import org.chuck.deluge.firmware.model.sample.Sample;
import org.chuck.deluge.firmware2.Envelope;
import org.chuck.deluge.firmware2.StereoSample;

// timeStretcher removed — replaced with simple pitched read

/**
 * Port of the Deluge's AudioClip class. Handles bit-accurate sample playback with real-time
 * time-stretching and lifecycle management.
 */
public class AudioClip extends Clip {
  public Sample sample;
  private org.chuck.deluge.firmware2.Sample fw2SampleCache;
  private final org.chuck.deluge.firmware2.VoiceSample voiceSample =
      new org.chuck.deluge.firmware2.VoiceSample();
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
    if (outputEnvelope.state == Envelope.Stage.OFF) {
      doingLateStart = true;
    }
  }

  @Override
  public void expectNoFurtherTicks(boolean actuallySoundChange) {
    if (actuallySoundChange) {
      // ── Bit-Accurate Release ──
      if (doingLateStart) {
        if (outputEnvelope.state.ordinal() < Envelope.Stage.FAST_RELEASE.ordinal()) {
          doingLateStart = false;
          outputEnvelope.unconditionalOff();
        } else {
          doingLateStart = false;
        }
      } else {
        // Fade out when stopping
        outputEnvelope.unconditionalRelease(Envelope.Stage.FAST_RELEASE, 1024);
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

    if (outputEnvelope.state == Envelope.Stage.OFF) return;

    // Convert to fw2 Sample and render through the faithful VoiceSample engine.
    if (fw2SampleCache == null) {
      fw2SampleCache = org.chuck.deluge.firmware2.Sample.fromFirmwareSample(sample);
      voiceSample.setup(fw2SampleCache, 0, 1);
    }
    int[] output = new int[numSamples];
    int[] amp = {0};
    voiceSample.render(output, numSamples, 1, phaseIncrement, amp, 0);
    lastProcessedPos = (int) (voiceSample.reader.playPos);

    // Process Envelope (dummy values for now)
    int env = outputEnvelope.render(numSamples, 1000, 1000, 1 << 30, 1000, null);

    for (int i = 0; i < numSamples; i++) {
      int wet = (int) (((long) output[i] * env) >> 31);
      buffer[i].l = wet;
      buffer[i].r = wet;
    }
  }
}
