package org.chuck.deluge.firmware.engine;

import java.util.ArrayList;
import java.util.List;
import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.modulation.params.ParamManager;

public class FirmwareSynth extends GlobalEffectable {
  public final List<FirmwareVoice> voices = new ArrayList<>();
  public int maxPolyphony = 64;

  @Override
  protected void renderInternal(StereoSample[] buffer, int numSamples, ParamManager paramManager) {
    int[] monoBuffer = new int[numSamples];
    for (FirmwareVoice voice : voices) {
      if (voice.active) {
        voice.render(monoBuffer, numSamples, 10000, 20000);
      }
    }
    for (int i = 0; i < numSamples; i++) {
      buffer[i].l = monoBuffer[i];
      buffer[i].r = monoBuffer[i];
    }
  }

  public void triggerNote(int note, int vel) {
    for (FirmwareVoice v : voices) {
      if (!v.active) {
        v.noteOn(note, vel);
        return;
      }
    }
    if (voices.size() < maxPolyphony) {
      FirmwareVoice v = new FirmwareVoice(null); // sound ref stub
      v.noteOn(note, vel);
      voices.add(v);
    }
  }
}
