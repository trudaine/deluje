package org.chuck.deluge.firmware.engine;

import java.util.ArrayList;
import java.util.List;
import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.compressor.RMSFeedbackCompressor;
import org.chuck.deluge.firmware.dsp.delay.Delay;
import org.chuck.deluge.firmware.dsp.reverb.freeverb.Freeverb;
import org.chuck.deluge.firmware.util.Q31;

/** Port of the Deluge's AudioEngine class, managing the master rendering loop and windowing. */
public class FirmwareAudioEngine {
  public final List<FirmwareSound> sounds = new ArrayList<>();
  public final StereoSample[] masterBuffer = new StereoSample[128];
  public final StereoSample[] delayBuffer = new StereoSample[128];
  public final StereoSample[] reverbBuffer = new StereoSample[128];

  // Global FX
  public final RMSFeedbackCompressor masterCompressor = new RMSFeedbackCompressor();
  public final Delay masterDelay = new Delay();
  public final Freeverb masterReverb = new Freeverb();
  public final Delay.State delayState = new Delay.State();

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

  /**
   * Replaces the ChucK-Java master shred loop. Manually orchestrates the rendering of all
   * instruments into a master bus.
   */
  public void renderBlock(int numSamples) {
    // 1. Clear master buffers
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

    // 2. Render all sounds (Synths/Kits)
    for (FirmwareSound sound : sounds) {
      // sound is now a GlobalEffectable
      if (sound instanceof GlobalEffectable ge) {
        ge.renderOutput(masterBuffer, numSamples, sound.paramManager, monoReverbBuffer);
      }
    }

    // 3. Process master reverb from its buffer
    masterReverb.process(monoReverbBuffer, masterBuffer);

    // 4. Apply Global FX (Delay, etc.)
    masterDelay.process(masterBuffer, delayState);

    // 5. Apply Master Compression
    masterCompressor.renderVolNeutral(masterBuffer, 1 << 27);

    // 6. Master Volume & Clipping
    for (int i = 0; i < numSamples; i++) {
      long lAdjustedBig = (long) masterBuffer[i].l * masterVolumeAdjustmentL;
      long rAdjustedBig = (long) masterBuffer[i].r * masterVolumeAdjustmentR;

      int lAdjusted = (int) (lAdjustedBig >> 31);
      int rAdjusted = (int) (rAdjustedBig >> 31);

      masterBuffer[i].l = Q31.lshiftAndSaturate(lAdjusted, 8);
      masterBuffer[i].r = Q31.lshiftAndSaturate(rAdjusted, 8);
    }
  }
}
