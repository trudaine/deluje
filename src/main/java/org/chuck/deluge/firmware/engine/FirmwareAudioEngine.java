package org.chuck.deluge.firmware.engine;

import java.util.ArrayList;
import java.util.List;
import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.compressor.RMSFeedbackCompressor;
import org.chuck.deluge.firmware.dsp.delay.Delay;
import org.chuck.deluge.firmware.dsp.reverb.freeverb.Freeverb;
import org.chuck.deluge.firmware.util.Q31;

/** Port of the Deluge's AudioEngine class. Performs master summing and global FX. */
public class FirmwareAudioEngine {
  public final List<GlobalEffectable> sounds = new ArrayList<>();
  public final StereoSample[] masterBuffer = new StereoSample[128];
  public final StereoSample[] delayBuffer = new StereoSample[128];
  public final StereoSample[] reverbBuffer = new StereoSample[128];

  // Global FX
  public final RMSFeedbackCompressor masterCompressor = new RMSFeedbackCompressor();
  public final Delay masterDelay = new Delay();
  public final Freeverb masterReverb = new Freeverb();
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
    delayState.doDelay = true;
    delayState.userDelayRate = 22050 << 5;
  }

  public void renderBlock(int numSamples) {
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
        sound.renderOutput(masterBuffer, numSamples, monoReverbBuffer);
    }

    masterReverb.process(monoReverbBuffer, masterBuffer);
    masterDelay.process(masterBuffer, delayState);
    
    // Hardware Master Compressor
    masterCompressor.renderVolNeutral(masterBuffer, 1 << 27);

    // ── Master Gain & Limiter ──
    for (int i = 0; i < numSamples; i++) {
      masterBuffer[i].l = Q31.mult(masterBuffer[i].l, masterVolumeAdjustmentL);
      masterBuffer[i].r = Q31.mult(masterBuffer[i].r, masterVolumeAdjustmentR);
    }
  }
}
