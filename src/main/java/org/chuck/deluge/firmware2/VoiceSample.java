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

  /** Exclusive end frame in the play direction (forwards: end > start; reverse: end < start). */
  public int endFrame;
  public boolean looping;
  public int loopStartFrame;

  /** One-shot, no loop — plays {@code sample} from {@code startFrame} to the sample end. */
  public void setup(Sample sample, int startFrame, int playDirection) {
    int end = (playDirection == 1) ? (int) sample.lengthInSamples : -1;
    setup(sample, startFrame, end, playDirection, false, startFrame);
  }

  /** Full setup with explicit end + optional looping. */
  public void setup(Sample sample, int startFrame, int endFrame, int playDirection, boolean looping,
                    int loopStartFrame) {
    reader.sample = sample;
    reader.playDirection = playDirection;
    reader.init(startFrame);
    this.endFrame = endFrame;
    this.looping = looping;
    this.loopStartFrame = loopStartFrame;
    active = true;
  }

  /** Input frames remaining before the end, in the play direction. */
  private long framesUntilEnd() {
    return (long) (endFrame - reader.playPos) * reader.playDirection;
  }

  /**
   * Render {@code numSamples} into {@code osc} (interleaved when stereo), accumulating, with the given
   * pitch and amplitude ramp. Picks native vs resampled like the C, and stops (one-shot) or wraps
   * (looping) at {@link #endFrame}. Block-chunked so a render never reads past the end; the exact
   * sample-accurate window math is the C considerUpcomingWindow's job (adapter approximation here for
   * the resampled case).
   */
  public void render(int[] osc, int numSamples, int numChannels, int phaseIncrement,
                     int[] amplitude, int amplitudeIncrement) {
    if (!active) {
      return;
    }
    boolean native_ = (phaseIncrement == K_MAX_SAMPLE_VALUE);
    int produced = 0;
    while (produced < numSamples) {
      long left = framesUntilEnd();
      if (left <= 0) {
        if (looping) {
          reader.init(loopStartFrame);
          left = framesUntilEnd();
        }
        if (left <= 0) { // one-shot ended, or degenerate loop
          active = false;
          return;
        }
      }

      // How many output samples we can emit before consuming `left` input frames.
      long outAvail = native_ ? left : Math.max(1, (left << 24) / phaseIncrement);
      int chunk = (int) Math.min(numSamples - produced, outAvail);

      int[] tmp = new int[chunk * numChannels];
      if (native_) {
        reader.readNative(tmp, chunk, numChannels, amplitude, amplitudeIncrement);
      } else {
        reader.readResampled(tmp, chunk, numChannels, Functions.getWhichKernel(phaseIncrement),
            phaseIncrement, amplitude, amplitudeIncrement);
      }
      int base = produced * numChannels;
      for (int i = 0; i < tmp.length; i++) {
        osc[base + i] += tmp[i];
      }
      produced += chunk;
    }
  }
}
