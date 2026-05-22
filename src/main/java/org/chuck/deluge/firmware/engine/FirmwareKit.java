package org.chuck.deluge.firmware.engine;

import java.util.ArrayList;
import java.util.List;
import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.oscillators.OscType;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.modulation.params.ParamManager;
import org.chuck.deluge.firmware.util.Q31;

/** Port of the Deluge's Kit class. */
public class FirmwareKit extends GlobalEffectable {
  public final List<FirmwareSound> drumSounds = new ArrayList<>();
  private final StereoSample[] isolatedBuffer = new StereoSample[128];

  public FirmwareKit() {
    for (int i = 0; i < 16; i++) {
      FirmwareSound drumSound = new FirmwareSound();
      drumSound.isDrum = true;
      drumSound.oscTypes[0] = OscType.SAMPLE;
      drumSound.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = 0;
      drumSound.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = 0;
      drumSound.paramNeutralValues[Param.LOCAL_NOISE_VOLUME] = 0;
      drumSounds.add(drumSound);
    }
    for (int i = 0; i < 128; i++) isolatedBuffer[i] = new StereoSample();
  }

  @Override
  protected void renderInternal(StereoSample[] buffer, int numSamples, ParamManager paramManager) {
    for (FirmwareSound drum : drumSounds) {
      if (!drum.voices.isEmpty()) {
        // Clear temp buffer
        for (int i = 0; i < numSamples; i++) {
          isolatedBuffer[i].l = 0;
          isolatedBuffer[i].r = 0;
        }
        // Render drum track with its own FX chain
        drum.renderOutput(isolatedBuffer, numSamples, null);
        if (drum == drumSounds.get(0) && isolatedBuffer[0].l != 0) {
          System.out.println(
              "[DIAG-KIT-SUM] lane=0 isolated[0]="
                  + isolatedBuffer[0].l
                  + " master_buffer[0]="
                  + buffer[0].l);
        }

        // Sum to Kit buffer
        for (int i = 0; i < numSamples; i++) {
          buffer[i].l = Q31.addSaturate(buffer[i].l, isolatedBuffer[i].l);
          buffer[i].r = Q31.addSaturate(buffer[i].r, isolatedBuffer[i].r);
        }
      }
    }
  }

  public void triggerDrum(int row, int vel) {
    if (row >= 0 && row < drumSounds.size()) {
      drumSounds.get(row).triggerNote(60, vel);
    }
  }
}
