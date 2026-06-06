package org.chuck.deluge.firmware.engine;

import java.util.List;
import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.compressor.RMSFeedbackCompressor;
import org.chuck.deluge.firmware.dsp.delay.Delay;
import org.chuck.deluge.firmware.dsp.reverb.ReverbContainer;
import org.chuck.deluge.firmware.util.Q31;

/** Port of the Deluge's AudioEngine class. Performs master summing and global FX. */
public class FirmwareAudioEngine {
  public final List<GlobalEffectable> sounds = new java.util.concurrent.CopyOnWriteArrayList<>();
  public final StereoSample[] masterBuffer = new StereoSample[128];
  public final StereoSample[] delayBuffer = new StereoSample[128];
  public final StereoSample[] reverbBuffer = new StereoSample[128];

  // Global FX
  public final RMSFeedbackCompressor masterCompressor = new RMSFeedbackCompressor();
  public final Delay masterDelay = new Delay();
  public final ReverbContainer masterReverb = new ReverbContainer();
  public final Delay.State delayState = new Delay.State();

  // ── High-Fidelity Gain Constants ──
  public int masterVolumeAdjustmentL = Q31.ONE;
  public int masterVolumeAdjustmentR = Q31.ONE;

  public FirmwareAudioEngine() {
    for (int i = 0; i < masterBuffer.length; i++) {
      masterBuffer[i] = new StereoSample();
      delayBuffer[i] = new StereoSample();
      reverbBuffer[i] = new StereoSample();
    }
    delayState.doDelay = false;
    // Drive delay time externally via userDelayRate; disable the delay's internal tempo-sync (which
    // would otherwise rewrite userDelayRate using a tick-inverse the pure engine doesn't feed it).
    masterDelay.syncLevel = org.chuck.deluge.firmware.model.SyncLevel.SYNC_LEVEL_NONE;
    masterVolumeAdjustmentL = Q31.ONE;
    masterVolumeAdjustmentR = Q31.ONE;
  }

  public void renderBlock(int numSamples) {
    GlobalSidechainBus.beginAudioFrame();
    int[] monoReverbBuffer = new int[numSamples];
    for (int i = 0; i < numSamples; i++) {
      masterBuffer[i].l = 0;
      masterBuffer[i].r = 0;
      delayBuffer[i].l = 0;
      delayBuffer[i].r = 0;
      reverbBuffer[i].l = 0;
      reverbBuffer[i].r = 0;
      monoReverbBuffer[i] = 0;
    }

    for (GlobalEffectable sound : sounds) {
      if (sound != null) {
        sound.renderOutput(masterBuffer, numSamples, monoReverbBuffer);
      }
    }

    masterReverb.process(monoReverbBuffer, masterBuffer);
    // setupWorkingState computes doDelay (from feedback) and must run before process or the delay
    // never activates. The pure engine drives delay time externally via userDelayRate with the
    // delay's own sync disabled (syncLevel NONE), so timePerInternalTickInverse is unused here.
    masterDelay.setupWorkingState(delayState, 1 << 20, true);
    masterDelay.process(masterBuffer, delayState);

    // Hardware Master Compressor (port of audio_engine.cpp:899)
    masterCompressor.renderVolNeutral(masterBuffer, Q31.ONE);

    // ── Master Gain & Soft-Clip Limiter ──
    for (int i = 0; i < numSamples; i++) {
      int l = Q31.mult(masterBuffer[i].l, masterVolumeAdjustmentL);
      int r = Q31.mult(masterBuffer[i].r, masterVolumeAdjustmentR);

      // Bit-accurate soft clipping via tanH lookup
      masterBuffer[i].l = org.chuck.deluge.firmware.util.FirmwareUtils.getTanHUnknown(l, 0) << 1;
      masterBuffer[i].r = org.chuck.deluge.firmware.util.FirmwareUtils.getTanHUnknown(r, 0) << 1;
    }
  }

  public void panic() {
    for (GlobalEffectable sound : sounds) {
      if (sound instanceof FirmwareSound synth) {
        synth.releaseAllNotes();
        synth.voices.clear();
      } else if (sound instanceof FirmwareKit kit) {
        for (FirmwareSound drum : kit.drumSounds) {
          drum.releaseAllNotes();
          drum.voices.clear();
        }
      }
    }
  }
}
