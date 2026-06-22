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
 * <p>Done: playback (phase 1), pitch / time-stretch via {@link #setPlayback} (phase 2), and
 * transport gating via {@link #onTransportStart}/{@link #onTransportStop} (phase 3a — plays only
 * while the song plays). Deferred: arrangement-timeline placement + musical-length loop (phase 3b)
 * and live recording/overdub (phase 4). See docs/AUDIO_TRACK_PORT_PLAN.md.
 */
public class AudioOutput extends GlobalEffectable {

  private Sample sample;
  private final VoiceSample voiceSample = new VoiceSample();
  private boolean playing = false;
  private boolean looping = true;

  // Phase 2 — playback rate / pitch. phaseIncrement is in the 24-bit fixed convention (unity =
  // 16777216 = native). Coupled mode (pitchSpeedIndependent=false): playRate scales phaseIncrement,
  // so pitch and speed move together (a plain resample). Independent mode: phaseIncrement stays at
  // unity (pitch unchanged) and timeStretchRatio carries the speed, via the TimeStretcher.
  private static final int UNITY_PHASE = 16777216;
  private int phaseIncrement = UNITY_PHASE;
  private int timeStretchRatio = UNITY_PHASE;
  private boolean timeStretch = false;

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
      if (timeStretch) {
        // Independent pitch/speed: the TimeStretcher hops through the sample (one-shot for now;
        // musical-length looping is Phase 3b).
        voiceSample.setupTimeStretch(sample, 0, 1);
      } else {
        voiceSample.setup(sample, 0, (int) sample.lengthInSamples, 1, looping, 0);
      }
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

  /**
   * Phase 2 — set playback rate. {@code pitchSpeedIndependent=false}: rate changes pitch+speed
   * together (resample). {@code true}: rate changes speed only (time-stretch), pitch unchanged.
   */
  public void setPlayback(float playRate, boolean pitchSpeedIndependent) {
    this.timeStretch = pitchSpeedIndependent;
    if (playRate <= 0f) playRate = 1f;
    if (pitchSpeedIndependent) {
      phaseIncrement = UNITY_PHASE; // pitch unchanged
      timeStretchRatio = (int) Math.round((double) UNITY_PHASE * playRate); // speed
    } else {
      phaseIncrement = (int) Math.round((double) UNITY_PHASE * playRate); // pitch+speed coupled
      timeStretchRatio = UNITY_PHASE;
    }
  }

  @Override
  protected void renderInternal(int[] buffer, int numSamples, int[] reverbBuffer) {
    if (!playing || sample == null) {
      return;
    }
    // buffer is interleaved stereo (LRLR), pre-zeroed by GlobalEffectable.renderOutput; VoiceSample
    // accumulates into it. phaseIncrement==unity → native; otherwise resampled (pitch).
    int[] amp = {amplitude};
    if (timeStretch) {
      voiceSample.renderTimeStretched(
          buffer, numSamples, 2, phaseIncrement, timeStretchRatio, amp, 0);
    } else {
      voiceSample.render(buffer, numSamples, 2, phaseIncrement, amp, 0);
    }
  }
}
