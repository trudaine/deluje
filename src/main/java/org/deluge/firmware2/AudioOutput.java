package org.deluge.firmware2;

/**
 * Audio-track instrument (firmware2) — streams an audio clip's sample through the track FX chain.
 *
 * <p>Phase 1 of the audio-track port (docs/AUDIO_TRACK_PORT_PLAN.md): a {@link GlobalEffectable}
 * that renders a loaded {@link Sample} via a {@link VoiceSample} (so filters / EQ / mod-FX / delay
 * / reverb-send / pan / volume all apply for free, exactly like synth/kit tracks). Reuses the
 * already-ported sample-playback DSP rather than adding new DSP, mirroring the C {@code
 * AudioClip::render} which drives a {@code voiceSample} over a {@code sampleHolder}.
 *
 * <p>Phase 1 plays at unity pitch (native, no resampling) and loops the clip while {@link
 * #playing}. Independent pitch / time-stretch (phase 2) and transport-synced start + arrangement
 * placement (phase 3) and live recording/overdub (phase 4) are deferred.
 */
public class AudioOutput extends GlobalEffectable {

  private Sample sample;
  private final VoiceSample voiceSample = new VoiceSample();
  private boolean playing = false;
  private boolean looping = true;

  // Steady playback amplitude (no per-note envelope on an audio track). The final loudness is
  // governed by the downstream GlobalEffectable post-FX volume + master compressor (verified:
  // output
  // level is insensitive to this beyond ~1<<27), so this is just a sane working level. Wiring the
  // per-track volume param into this is a later-phase refinement.
  private int amplitude = 1 << 27;

  /** Load the clip's sample and arm it for (looping) playback from the start. */
  public void setClip(Sample sample, boolean looping) {
    this.sample = sample;
    this.looping = looping;
    if (sample != null) {
      int end = (int) sample.lengthInSamples;
      // VoiceSample.setup(sample, startFrame, endFrame, playDirection, looping, loopStartFrame)
      voiceSample.setup(sample, 0, end, 1, looping, 0);
    }
  }

  public void setPlaying(boolean p) {
    this.playing = p;
    if (p && sample != null) {
      // (re)arm from the start so playback begins cleanly.
      voiceSample.setup(sample, 0, (int) sample.lengthInSamples, 1, looping, 0);
    }
  }

  /**
   * Transport started: begin the clip from its start, phase-aligned to the transport (Phase 3). The
   * engine calls this on the play edge so audio clips are silent until the song is playing.
   */
  public void onTransportStart() {
    if (sample != null) {
      voiceSample.setup(sample, 0, (int) sample.lengthInSamples, 1, looping, 0);
      playing = true;
    }
  }

  /** Transport stopped: stop streaming (renderInternal becomes silent). */
  public void onTransportStop() {
    playing = false;
  }

  public boolean isPlaying() {
    return playing;
  }

  public void setAmplitude(int amp) {
    this.amplitude = amp;
  }

  @Override
  protected void renderInternal(int[] buffer, int numSamples, int[] reverbBuffer) {
    if (!playing || sample == null) {
      return;
    }
    // buffer is interleaved stereo (LRLR), pre-zeroed by GlobalEffectable.renderOutput; VoiceSample
    // accumulates into it. Unity phase (== K_MAX_SAMPLE_VALUE) → native, no resampling (Phase 1).
    int[] amp = {amplitude};
    voiceSample.render(buffer, numSamples, 2, 16777216, amp, 0);
  }
}
