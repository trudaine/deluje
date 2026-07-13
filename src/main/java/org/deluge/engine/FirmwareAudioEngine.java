package org.deluge.engine;

import java.util.List;
import org.deluge.firmware2.Compressor;
import org.deluge.firmware2.Delay;
import org.deluge.firmware2.GlobalEffectable;
import org.deluge.firmware2.GlobalSidechainBus;
import org.deluge.firmware2.LiveInputBuffer;
import org.deluge.firmware2.LivePitchShifter;
import org.deluge.firmware2.Metronome;
import org.deluge.firmware2.Reverb;
import org.deluge.firmware2.StereoSample;
import org.deluge.model.ProjectModel;

/**
 * Port of the Deluge's AudioEngine class. Performs master summing and global FX.
 *
 * <p>The master FX bus (reverb / delay / compressor) is the faithful firmware2 port. The fw2 FX
 * operate on {@code int[][]} stereo frames, so the chain runs on an {@code int[][]} scratch ({@link
 * #fxBuffer}) while the public {@link #masterBuffer} stays {@code StereoSample[]} for the
 * sound-render and driver sides. fw2 corrects real bugs the old firmware/ FX had (reverb cross-feed
 * / 2x scale, compressor float-buffer precision), so the master tone changes — that's
 * faithful-to-C.
 */
public class FirmwareAudioEngine {
  public static boolean debugTelemetry = false;

  // ── CPU direness (audio_engine.cpp) — adaptive resampling-quality fallback ──
  // C: AudioEngine::cpuDireness (audio_engine.cpp:161), range 0..14. Raised when the DSP overruns
  // its real-time budget, decayed (with hysteresis) when it recovers. SampleControls.
  // getInterpolationBufferSize reads it to drop to 2-tap linear interpolation on pitched-up samples
  // under load (sample_controls.cpp:35-44); at 0 the sample engine always uses full 24-tap sinc.
  // Stays 0 for offline export/tests (those never call updateDireness), so export stays sinc.
  public static volatile int cpuDireness = 0;

  // C: audio_engine.cpp:122-124 — numSamplesLimit=80, direnessThreshold = numSamplesLimit-30 = 50.
  private static final int DIRENESS_THRESHOLD = 50;
  private static final int MAX_DIRENESS = 14; // C: std::min<int32_t>(..., 14)
  private static final int DIRENESS_DECAY_INTERVAL = 44100 >> 3; // C: kSampleRate >> 3
  private static long timeDirenessChanged = 0; // C: timeDirenessChanged (in audioSampleTimer units)

  /**
   * Faithful-spirit port of AudioEngine::setDireness (audio_engine.cpp:472-515). The hardware reads
   * the audio routine's average run-time from its task scheduler; on desktop we feed in the
   * measured wall-clock render time of the block that just completed. {@code overrun} is how many
   * sample-periods of wall time the render took beyond the audio it produced (C: {@code dspTime -
   * numRoutines*numSamples}, with numRoutines == 1 here). The threshold (50), ceiling (14), and
   * decay hysteresis (kSampleRate>>3) match the C exactly. Voice culling (the C's other branch) is
   * out of scope — desktop has the headroom; this only governs interpolation quality.
   */
  public static void updateDireness(long renderNanos, int blockSamples, long audioSampleTimer) {
    int dspTime = (int) (renderNanos * 44100.0 / 1_000_000_000.0); // C: avgRunTime * 44100.
    int overrun =
        Math.max(dspTime - blockSamples, 0); // C: max(dspTime - numRoutines*numSamples, 0)
    if (overrun >= DIRENESS_THRESHOLD) {
      int newDireness = Math.min(overrun - (DIRENESS_THRESHOLD - 1), MAX_DIRENESS);
      if (newDireness >= cpuDireness) {
        cpuDireness = newDireness;
        timeDirenessChanged = audioSampleTimer;
      }
    } else if (overrun < DIRENESS_THRESHOLD - 3) { // C: hysteresis band to avoid jittering
      if (audioSampleTimer - timeDirenessChanged >= DIRENESS_DECAY_INTERVAL) {
        timeDirenessChanged = audioSampleTimer;
        cpuDireness--;
        if (cpuDireness < 0) {
          cpuDireness = 0;
        }
      }
    }
  }

  public final List<GlobalEffectable> sounds = new java.util.concurrent.CopyOnWriteArrayList<>();
  public final StereoSample[] masterBuffer = new StereoSample[128];
  private int[] summedFlatBuffer = new int[256];

  // Global FX — faithful firmware2 ports.
  public final Compressor masterCompressor = new Compressor();

  {
    // C song.cpp:191 — the song's master compressor uses 0.85, not the constructor's 1.35
    // ("The song compressor must use 0.8 to maintain compatibility", rms_feedback.h:163-167).
    masterCompressor.setBaseGain(0.85f);
  }

  public final Delay masterDelay = new Delay();
  public final Reverb.Container masterReverb = new Reverb.Container();
  public final Delay.State delayState = new Delay.State();

  // C: audio_engine.cpp — the global reverb's own sidechain ("reverb compressor") + the
  // in-effect params resolved by updateReverbParams (auto mode).
  public final org.deluge.firmware2.Sidechain reverbSidechain =
      new org.deluge.firmware2.Sidechain();
  public int reverbSidechainVolumeInEffect = 0;
  public int reverbSidechainShapeInEffect = 0;

  /**
   * C audio_engine.cpp:1251-1317 updateReverbParams, "auto" mode (the C's default,
   * reverbSidechainVolume &lt; 0): find the sound with the most reverb send; if it has a
   * SIDECHAIN→GLOBAL_VOLUME_POST_REVERB_SEND cable, the reverb ducks with that cable's modified
   * amount and borrows the sound's sidechain shape/attack/release/syncLevel. No qualifying sound →
   * no ducking. (The C's manual mode — an explicit song-level reverb sidechain volume — is not
   * modeled; hardware defaults to auto, song.cpp:185.)
   */
  private void updateReverbParams() {
    org.deluge.firmware2.Sound best = null;
    int highestReverbAmountFound = Integer.MIN_VALUE; // C seeds with the song's own send amount
    for (int i = 0; i < sounds.size(); i++) {
      GlobalEffectable ge = sounds.get(i);
      if (ge instanceof org.deluge.firmware2.Sound snd) {
        int amount = snd.patchedParamValues[org.deluge.firmware2.Param.GLOBAL_REVERB_AMOUNT];
        if (amount != Integer.MIN_VALUE && amount > highestReverbAmountFound) {
          highestReverbAmountFound = amount;
          best = snd;
        }
      }
    }
    if (best == null) {
      reverbSidechainVolumeInEffect = 0;
      return;
    }
    int vol = 0; // C:1288-1291 — no SIDECHAIN cable on the send → no ducking
    java.util.List<org.deluge.firmware2.Patcher.Destination> dests =
        best.patchCableSet.destinations;
    for (int dIdx = 0; dIdx < dests.size(); dIdx++) {
      org.deluge.firmware2.Patcher.Destination dest = dests.get(dIdx);
      if (dest.paramId == org.deluge.firmware2.Param.GLOBAL_VOLUME_POST_REVERB_SEND) {
        java.util.List<org.deluge.firmware2.Patcher.PatchCable> cables = dest.cables;
        for (int cIdx = 0; cIdx < cables.size(); cIdx++) {
          org.deluge.firmware2.Patcher.PatchCable cable = cables.get(cIdx);
          if (cable.source == org.deluge.firmware2.PatchSource.SIDECHAIN.ordinal()) {
            vol =
                org.deluge.firmware2.Patcher.getModifiedPatchCableAmount(
                    cable, org.deluge.firmware2.Param.GLOBAL_VOLUME_POST_REVERB_SEND);
            break;
          }
        }
      }
    }
    reverbSidechainVolumeInEffect = vol;
    reverbSidechainShapeInEffect =
        best.patchedParamValues[org.deluge.firmware2.Param.UNPATCHED_SIDECHAIN_SHAPE];
    reverbSidechain.attack = best.sidechain.attack; // C:1304-1306
    reverbSidechain.release = best.sidechain.release;
    reverbSidechain.syncLevel = best.sidechain.syncLevel;
    reverbSidechain.timePerInternalTickInverse = best.sidechain.timePerInternalTickInverse;
  }

  /** int[][] stereo scratch for the fw2 FX chain (fw2 FX read/write [l, r] per sample). */
  private final int[][] fxBuffer = new int[128][2];

  private int[] monoReverbBuffer = new int[128];

  // Live input pitch shifter (monitoring). inputBuffer holds the ring; pitchShifter renders it.
  public final LiveInputBuffer liveInputBuffer = new LiveInputBuffer();
  public final LivePitchShifter livePitchShifter =
      new LivePitchShifter(LiveInputBuffer.InputType.STEREO, 16777216);
  private int liveAudioSampleTimer;

  // Reused scratch for renderLiveInput's pitch-shifter output (grow-only), so the live-input/
  // resample callback path doesn't allocate a fresh int[] every audio block.
  private int[] liveInputOut = new int[256];

  // ── High-Fidelity Gain Constants ──
  // C: audio_engine.cpp:861 — masterVolumeAdjustment starts at getParamNeutralValue(GLOBAL_VOLUME_
  // POST_FX) = 167763968 (~1.25·2^27), NOT full-scale. This is the master-volume the compressor
  // applies (render volAdjust); using Q31.ONE here drove the compressor ~13× too hot into its
  // saturation and broke hardware shape parity.
  public static final int MASTER_VOLUME_NEUTRAL = 167763968;
  public int masterVolumeAdjustmentL = MASTER_VOLUME_NEUTRAL;
  public int masterVolumeAdjustmentR = MASTER_VOLUME_NEUTRAL;
  public int songVolume = 0; // bipolar Q31 master volume

  // Metronome (faithful fw2 port). Off by default; the transport triggers a click each beat when
  // enabled (see JavaAudioDriver). C: audio_engine.cpp:626 renders it into the master buffer.
  public final Metronome metronome = new Metronome();
  public volatile boolean metronomeEnabled = false;

  /** Trigger a metronome click. {@code phaseIncrement} sets the click pitch (downbeat vs beat). */
  public void triggerMetronome(int phaseIncrement) {
    if (metronomeEnabled) {
      metronome.trigger(phaseIncrement);
    }
  }

  public FirmwareAudioEngine() {
    GlobalSidechainBus.reset();
    for (int i = 0; i < masterBuffer.length; i++) {
      masterBuffer[i] = new StereoSample();
    }
    delayState.doDelay = false;
    // Drive delay time externally via userDelayRate; disable the delay's internal tempo-sync (which
    // would otherwise rewrite userDelayRate using a tick-inverse the pure engine doesn't feed it).
    // fw2 Delay.syncLevel is an int; SYNC_LEVEL_NONE == 0.
    masterDelay.syncLevel = 0;
    masterVolumeAdjustmentL = MASTER_VOLUME_NEUTRAL;
    masterVolumeAdjustmentR = MASTER_VOLUME_NEUTRAL;
  }

  public void syncMasterEffects(ProjectModel project) {
    double bpm = project.getBpm();
    int syncLevel = project.getDelaySyncLevel();
    double delaySec;
    if (syncLevel > 0) {
      double stepSec = (bpm > 0 ? 60.0 / bpm : 0.5) / 4.0; // 16th-note step
      double syncFactor = Math.pow(2.0, syncLevel - 1);
      if (project.getDelaySyncType() == 1) {
        syncFactor *= 1.5; // triplet
      }
      delaySec = syncFactor * stepSec;
    } else {
      delaySec = project.getSongParamDelayRate();
    }
    delaySec = Math.max(0.001, Math.min(2.0, delaySec));
    long rate = (long) (16384L * 16777216L / (delaySec * 44100.0));
    this.delayState.userDelayRate = (int) Math.min(rate, Integer.MAX_VALUE);

    double fb = project.getSongParamDelayFeedback();
    fb = Math.max(0.0, Math.min(1.0, fb));
    this.delayState.delayFeedbackAmount =
        Math.min((int) (fb * 2147483647.0), (1 << 30) - (1 << 26));

    this.masterDelay.pingPong = project.getDelayPingPong() != 0;
    this.masterDelay.analog = project.getDelayAnalog() != 0;

    // Sync Reverb
    // C reverb.hpp:15-19 — enum order is FREEVERB=0, MUTABLE=1, DIGITAL=2 (song.cpp:1423 casts
    // the raw XML int straight to this enum); this was previously never synced, so masterReverb
    // stayed on its Java field default (FREEVERB) regardless of the song's actual model.
    this.masterReverb.setModel(
        switch (project.getReverbModel()) {
          case 1 -> org.deluge.firmware2.Reverb.Model.MUTABLE;
          case 2 -> org.deluge.firmware2.Reverb.Model.DIGITAL;
          default -> org.deluge.firmware2.Reverb.Model.FREEVERB;
        });
    this.masterReverb.setRoomSize(project.getReverbRoomSize());
    this.masterReverb.setDamping(project.getReverbDampening());
    this.masterReverb.setWidth(project.getReverbWidth());
    this.masterReverb.setHPF(project.getReverbHpf());

    // Sync Master Volume
    float volFloat = project.getSongParamVolume();
    float bipolar = (volFloat * 2.0f) - 1.0f;
    this.songVolume = (int) (bipolar * 2147483647.0f);
  }

  private long[] summedFlatBufferLong = new long[256];
  private long[] monoReverbBufferLong = new long[128];

  // Transport play state, for audio-track gating (Phase 3). The driver sets this each block from
  // the PlaybackHandler; offline renderers/tests set it true when they want audio clips to stream.
  private volatile boolean transportPlaying = false;
  private volatile long transportTick = 0;

  public void setTransportPlaying(boolean p) {
    this.transportPlaying = p;
  }

  /** Current transport tick (for audio-track arrangement-placement gating, Phase 3b part 2). */
  public void setTransportTick(long tick) {
    this.transportTick = tick;
  }

  public void renderBlock(int numSamples) {
    GlobalSidechainBus.beginAudioFrame();
    // Reset buffers
    if (monoReverbBuffer.length < numSamples) {
      monoReverbBuffer = new int[numSamples];
    }
    for (int i = 0; i < numSamples; i++) {
      masterBuffer[i].l = 0;
      masterBuffer[i].r = 0;
      monoReverbBuffer[i] = 0;
    }

    int requiredLen = numSamples * 2;
    if (summedFlatBufferLong.length < requiredLen) {
      summedFlatBufferLong = new long[requiredLen];
    }
    java.util.Arrays.fill(summedFlatBufferLong, 0, requiredLen, 0L);

    if (monoReverbBufferLong.length < numSamples) {
      monoReverbBufferLong = new long[numSamples];
    }
    java.util.Arrays.fill(monoReverbBufferLong, 0, numSamples, 0L);

    // Audio-track gating (Phase 3): each block, update audio clips with the transport state + tick
    // so they stream only while playing (3a) and only within their arrangement range (3b part 2),
    // restarting phase-aligned on entry.
    for (int i = 0; i < sounds.size(); i++) {
      GlobalEffectable sound = sounds.get(i);
      if (sound instanceof org.deluge.firmware2.AudioOutput ao) {
        ao.updateTimeline(transportTick, transportPlaying);
      }
    }

    // Sum all tracks in 64-bit (infinite headroom!)
    for (int i = 0; i < sounds.size(); i++) {
      GlobalEffectable sound = sounds.get(i);
      if (sound != null) {
        if (debugTelemetry && sound instanceof org.deluge.engine.FirmwareSound fs) {
          System.out.println(
              "[ENGINE-RENDER-DEBUG] sound="
                  + System.identityHashCode(fs)
                  + " muted="
                  + fs.muted
                  + " tick="
                  + transportTick);
        }
        sound.renderOutput(summedFlatBufferLong, numSamples, monoReverbBufferLong);
      }
    }

    // Apply master volume adjustment in 64-bit to prevent summing-bus saturation!
    for (int i = 0; i < numSamples; i++) {
      long lVal = summedFlatBufferLong[i * 2];
      long rVal = summedFlatBufferLong[i * 2 + 1];
      lVal = (lVal * masterVolumeAdjustmentL) >> 27;
      rVal = (rVal * masterVolumeAdjustmentR) >> 27;
      fxBuffer[i][0] = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, lVal));
      fxBuffer[i][1] = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, rVal));

      long revVal = monoReverbBufferLong[i];
      monoReverbBuffer[i] = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, revVal));
    }

    // C: audio_engine.cpp:806-844 — the reverb's own sidechain ("reverb compressor") ducks the
    // wet level. In the C's default "auto" mode, updateReverbParams borrows the sidechain
    // volume/shape/attack/release from the sound with the most reverb send that has a
    // SIDECHAIN→POST_REVERB_SEND cable. The resulting output level must be set as the reverb's
    // pan levels BEFORE process(), or every model multiplies its wet by a 0 pan amplitude and
    // emits silence.
    updateReverbParams();
    int sidechainOutput = 0;
    if (reverbSidechainVolumeInEffect != 0) { // C:812-819
      int hit = GlobalSidechainBus.getActiveFrameHit();
      if (hit != 0) {
        reverbSidechain.registerHit(hit);
      }
      sidechainOutput = reverbSidechain.render(numSamples, reverbSidechainShapeInEffect);
    }
    // C:829-833 — patch the sidechain into the reverb volume.
    int positivePatchedValue =
        org.deluge.firmware2.Functions.multiply_32x32_rshift32(
                sidechainOutput, reverbSidechainVolumeInEffect)
            + 0x20000000;
    int reverbOutputVolume = (positivePatchedValue >> 15) * (positivePatchedValue >> 14); // C:833
    masterReverb.setPanLevels(reverbOutputVolume, reverbOutputVolume); // C:836

    // fw2 reverb ACCUMULATES the wet signal onto the [l, r] frames, so it adds reverb on top of the
    // dry already in fxBuffer (matching the old firmware/ reverb's += into masterBuffer).
    masterReverb.process(monoReverbBuffer, fxBuffer);

    // setupWorkingState computes doDelay (from feedback) and must run before process or the delay
    // never activates. The pure engine drives delay time externally via userDelayRate with the
    // delay's own sync disabled (syncLevel 0), so timePerInternalTickInverse is unused here.
    masterDelay.setupWorkingState(delayState, 1 << 20, true);
    masterDelay.process(fxBuffer, numSamples, delayState);

    // Metronome click — added dry, before the master compressor (C: audio_engine.cpp:626).
    if (metronomeEnabled) {
      metronome.render(fxBuffer, numSamples, org.deluge.firmware2.Functions.ONE_Q31);
    }

    // ── Master output stage — faithful port of audio_engine.cpp:891-901. ──
    // The compressor applies the master volume (masterVolumeAdjustment >> 1), the RMS compression,
    // AND the output saturation (rms_feedback.cpp:106 getTanHAntialiased). songVolume sets the
    // compressor's makeup/threshold. The C has NO separate engine tanh here — the previous
    // renderVolNeutral(2^31) + getTanHUnknown was invented (compressor never engaged, then hard
    // tanh walled dense content). songMasterVolume = 0 (neutral UNPATCHED_VOLUME).
    int songVolume =
        org.deluge.firmware2.Functions.getFinalParameterValueVolume(
                134217728,
                org.deluge.firmware2.Functions.cableToLinearParamShortcut(this.songVolume))
            >> 1;

    // These max-scans feed only the telemetry log below — skip the whole-buffer scan when
    // telemetry is off (the default), saving two passes over the master buffer every block.
    int maxBeforeL = 0;
    int maxBeforeR = 0;
    if (debugTelemetry) {
      for (int i = 0; i < numSamples; i++) {
        maxBeforeL = Math.max(maxBeforeL, Math.abs(fxBuffer[i][0]));
        maxBeforeR = Math.max(maxBeforeR, Math.abs(fxBuffer[i][1]));
      }
    }

    masterCompressor.render(
        fxBuffer,
        numSamples,
        MASTER_VOLUME_NEUTRAL >> 1,
        MASTER_VOLUME_NEUTRAL >> 1,
        songVolume >> 3);

    if (debugTelemetry) {
      int maxAfterL = 0;
      for (int i = 0; i < numSamples; i++) {
        maxAfterL = Math.max(maxAfterL, Math.abs(fxBuffer[i][0]));
      }
      if (maxBeforeL > 1000) {
        System.out.printf(
            "[TELEMETRY Engine] fxBuffer L max absolute value: Before=%d, After=%d\n",
            maxBeforeL, maxAfterL);
      }
    }

    masterVolumeAdjustmentL = MASTER_VOLUME_NEUTRAL; // C:901 resets to ONE_Q31; we keep our neutral
    masterVolumeAdjustmentR = MASTER_VOLUME_NEUTRAL;

    // (No DC blocker — the C master path has none. Measured: with it removed the engine's output DC
    // stays negligible (< 0.0014 of full scale across saw/square/sine/triangle), so the invented
    // AC-coupling high-pass was removing essentially nothing while diverging from the firmware.)

    for (int i = 0; i < numSamples; i++) {
      masterBuffer[i].l = org.deluge.firmware2.Functions.lshiftAndSaturate(fxBuffer[i][0], 4);
      masterBuffer[i].r = org.deluge.firmware2.Functions.lshiftAndSaturate(fxBuffer[i][1], 4);
    }
  }

  /**
   * Feed a block of live audio input through the pitch shifter and mix into the master output. Call
   * this from a desktop audio input callback. {@code inputBlock} is interleaved stereo int
   * (LRLR...), matching the fw2 DSP convention.
   */
  public void renderLiveInput(int[] inputBlock, int numSamples, int phaseIncrement) {
    int requiredLen = numSamples * 2;
    if (liveInputOut.length < requiredLen) {
      liveInputOut = new int[requiredLen];
    }
    int[] out = liveInputOut;
    java.util.Arrays.fill(out, 0, requiredLen, 0);
    livePitchShifter.render(
        out,
        numSamples,
        phaseIncrement,
        1 << 27,
        0,
        16,
        liveInputBuffer,
        liveAudioSampleTimer += numSamples,
        inputBlock);
    for (int i = 0; i < numSamples; i++) {
      fxBuffer[i][0] += out[i * 2];
      fxBuffer[i][1] += out[i * 2 + 1];
    }
  }

  public void panic() {
    for (GlobalEffectable sound : sounds) {
      if (sound instanceof FirmwareSound synth) {
        synth.releaseAllNotes();
        // voice list removed (fw2 manages its own)
      } else if (sound instanceof FirmwareKit kit) {
        for (FirmwareSound drum : kit.drumSounds) {
          drum.releaseAllNotes();
          // voice list removed (fw2 manages its own)
        }
      }
    }
  }
}
