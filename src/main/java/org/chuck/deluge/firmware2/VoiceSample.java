package org.chuck.deluge.firmware2;

/**
 * Minimal pitched sample voice for the firmware2 sample engine (the pitched-playback milestone of
 * docs/FIRMWARE2_SAMPLE_ENGINE_PLAN.md). Plays an in-RAM {@link Sample} once at a given pitch via
 * {@link SampleReader}, choosing the native (1:1) or resampled path exactly as the C does
 * ({@code phaseIncrement == kMaxSampleValue} ⇒ native; else windowed-sinc with the C's
 * {@link Functions#getWhichKernel}).
 *
 * <p>Scope: a single non-looping play head, no time-stretch (that is {@code TimeStretcher.hopEnd},
 * a later increment), no cluster/loop/end-of-sample envelope handling yet.
 */
public class VoiceSample {

  /** C: kMaxSampleValue (1 << 24) — unity phaseIncrement ⇒ no resampling. */
  static final int K_MAX_SAMPLE_VALUE = 16777216;

  public final SampleReader reader = new SampleReader();
  public boolean active;

  /** Prepare to play {@code sample} from {@code startFrame} in {@code playDirection} (+1/-1). */
  public void setup(Sample sample, int startFrame, int playDirection) {
    reader.sample = sample;
    reader.playDirection = playDirection;
    reader.init(startFrame);
    active = true;
  }

  /**
   * Render {@code numSamples} into {@code osc} (interleaved when stereo), accumulating, with the given
   * pitch and amplitude ramp. Picks native vs resampled like the C.
   */
  public void render(int[] osc, int numSamples, int numChannels, int phaseIncrement,
                     int[] amplitude, int amplitudeIncrement) {
    if (phaseIncrement == K_MAX_SAMPLE_VALUE) {
      reader.readNative(osc, numSamples, numChannels, amplitude, amplitudeIncrement);
    } else {
      reader.readResampled(
          osc, numSamples, numChannels, Functions.getWhichKernel(phaseIncrement), phaseIncrement,
          amplitude, amplitudeIncrement);
    }
  }
}
