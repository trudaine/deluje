package org.chuck.deluge.firmware.engine;

import java.util.List;
import org.chuck.deluge.firmware.util.Q31;
import org.chuck.deluge.firmware2.Compressor;
import org.chuck.deluge.firmware2.Delay;
import org.chuck.deluge.firmware2.GlobalEffectable;
import org.chuck.deluge.firmware2.LiveInputBuffer;
import org.chuck.deluge.firmware2.LivePitchShifter;
import org.chuck.deluge.firmware2.Metronome;
import org.chuck.deluge.firmware2.Reverb;
import org.chuck.deluge.firmware2.StereoSample;

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
  public final List<GlobalEffectable> sounds = new java.util.concurrent.CopyOnWriteArrayList<>();
  public final StereoSample[] masterBuffer = new StereoSample[128];
  private int[] summedFlatBuffer = new int[256];

  // Global FX — faithful firmware2 ports.
  public final Compressor masterCompressor = new Compressor();
  public final Delay masterDelay = new Delay();
  public final Reverb.Container masterReverb = new Reverb.Container();
  public final Delay.State delayState = new Delay.State();

  /** int[][] stereo scratch for the fw2 FX chain (fw2 FX read/write [l, r] per sample). */
  private final int[][] fxBuffer = new int[128][2];

  private int[] monoReverbBuffer = new int[128];

  // Live input pitch shifter (monitoring). inputBuffer holds the ring; pitchShifter renders it.
  public final LiveInputBuffer liveInputBuffer = new LiveInputBuffer();
  public final LivePitchShifter livePitchShifter =
      new LivePitchShifter(LiveInputBuffer.InputType.STEREO, 16777216);
  private int liveAudioSampleTimer;

  // ── High-Fidelity Gain Constants ──
  public int masterVolumeAdjustmentL = Q31.ONE;
  public int masterVolumeAdjustmentR = Q31.ONE;

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
    for (int i = 0; i < masterBuffer.length; i++) {
      masterBuffer[i] = new StereoSample();
    }
    delayState.doDelay = false;
    // Drive delay time externally via userDelayRate; disable the delay's internal tempo-sync (which
    // would otherwise rewrite userDelayRate using a tick-inverse the pure engine doesn't feed it).
    // fw2 Delay.syncLevel is an int; SYNC_LEVEL_NONE == 0.
    masterDelay.syncLevel = 0;
    masterVolumeAdjustmentL = Q31.ONE;
    masterVolumeAdjustmentR = Q31.ONE;
  }

  public void renderBlock(int numSamples) {
    GlobalSidechainBus.beginAudioFrame();
    if (monoReverbBuffer.length < numSamples) {
      monoReverbBuffer = new int[numSamples];
    }
    for (int i = 0; i < numSamples; i++) {
      masterBuffer[i].l = 0;
      masterBuffer[i].r = 0;
      monoReverbBuffer[i] = 0;
    }

    int requiredLen = numSamples * 2;
    if (summedFlatBuffer.length < requiredLen) {
      summedFlatBuffer = new int[requiredLen];
    }
    java.util.Arrays.fill(summedFlatBuffer, 0, requiredLen, 0);

    for (GlobalEffectable sound : sounds) {
      if (sound != null) {
        sound.renderOutput(summedFlatBuffer, numSamples, monoReverbBuffer);
      }
    }

    // Move the summed dry signal into the int[][] scratch for the fw2 FX chain.
    for (int i = 0; i < numSamples; i++) {
      fxBuffer[i][0] = summedFlatBuffer[i * 2];
      fxBuffer[i][1] = summedFlatBuffer[i * 2 + 1];
    }

    // C: audio_engine.cpp:819-837 — the reverb output level must be set as the reverb's pan levels
    // BEFORE process(), or every model multiplies its wet by a 0 pan amplitude and emits silence.
    // No reverb sidechain is wired in the bridge yet (a seam), so sidechainOutput = 0; with center
    // pan, reverbAmplitudeL == reverbAmplitudeR == reverbOutputVolume.
    int sidechainOutput = 0; // seam: reverbSidechainVolumeInEffect == 0 in the bridge
    int positivePatchedValue = sidechainOutput + 0x20000000; // C:820-822 (sidechain term is 0)
    int reverbOutputVolume = (positivePatchedValue >> 15) * (positivePatchedValue >> 14); // C:823
    masterReverb.setPanLevels(reverbOutputVolume, reverbOutputVolume); // C:836

    // fw2 reverb ACCUMULATES the wet signal onto the [l, r] frames, so it adds reverb on top of the
    // dry already in fxBuffer (matching the old firmware/ reverb's += into masterBuffer).
    masterReverb.process(monoReverbBuffer, fxBuffer);

    // setupWorkingState computes doDelay (from feedback) and must run before process or the delay
    // never activates. The pure engine drives delay time externally via userDelayRate with the
    // delay's own sync disabled (syncLevel 0), so timePerInternalTickInverse is unused here.
    masterDelay.setupWorkingState(delayState, 1 << 20, true);
    masterDelay.process(fxBuffer, numSamples, delayState);

    // Hardware Master Compressor (port of audio_engine.cpp:899).
    masterCompressor.renderVolNeutral(fxBuffer, Q31.ONE);

    // Metronome click — added dry, after FX, before the master gain/limiter (C: audio_engine.cpp:626).
    if (metronomeEnabled) {
      metronome.render(fxBuffer, numSamples, Q31.ONE);
    }

    // ── Master Gain & Soft-Clip Limiter ──
    for (int i = 0; i < numSamples; i++) {
      int l = Q31.mult(fxBuffer[i][0], masterVolumeAdjustmentL);
      int r = Q31.mult(fxBuffer[i][1], masterVolumeAdjustmentR);

      // Bit-accurate soft clipping via tanH lookup
      masterBuffer[i].l = org.chuck.deluge.firmware.util.FirmwareUtils.getTanHUnknown(l, 0) << 1;
      masterBuffer[i].r = org.chuck.deluge.firmware.util.FirmwareUtils.getTanHUnknown(r, 0) << 1;
    }
  }

  /**
   * Feed a block of live audio input through the pitch shifter and mix into the master output. Call
   * this from a desktop audio input callback. {@code inputBlock} is interleaved stereo int
   * (LRLR...), matching the fw2 DSP convention.
   */
  public void renderLiveInput(int[] inputBlock, int numSamples, int phaseIncrement) {
    int[] out = new int[numSamples * 2];
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
