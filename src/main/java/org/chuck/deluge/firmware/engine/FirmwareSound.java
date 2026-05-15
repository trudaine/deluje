package org.chuck.deluge.firmware.engine;

import java.util.ArrayList;
import java.util.List;
import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.modulation.LFO;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.modulation.params.ParamManager;
import org.chuck.deluge.firmware.model.PolyphonyMode;
import org.chuck.deluge.firmware.modulation.patch.PatchSource;

/** Port of the Deluge's Sound class, managing voice allocation and global modulation. */
public class FirmwareSound extends GlobalEffectable {
  public final List<FirmwareVoice> voices = new ArrayList<>();
  public final LFO[] globalLfos = new LFO[2];
  public int maxPolyphony = 64;
  public PolyphonyMode polyphonic = PolyphonyMode.POLY;
  public ParamManager paramManager = new ParamManager();
  public int[] paramNeutralValues = new int[Param.kNumParams];
  public int[] globalSourceValues = new int[PatchSource.kNumPatchSources];

  public FirmwareSound() {
    for (int i = 0; i < globalLfos.length; i++) globalLfos[i] = new LFO();
  }

  @Override
  protected void renderInternal(StereoSample[] buffer, int numSamples, ParamManager unused) {
    // 1. Update Global LFOs
    for (int i = 0; i < 2; i++) {
      globalSourceValues[PatchSource.LFO_GLOBAL_1.ordinal() + i] =
          globalLfos[i].render(numSamples, LFO.LFOType.SINE, 5000); // dummy rate
    }

    int[] monoBuffer = new int[numSamples];

    for (FirmwareVoice voice : voices) {
      if (voice.active) {
        voice.render(monoBuffer, numSamples, 10000, 20000); // dummy increments
      }
    }

    for (int i = 0; i < numSamples; i++) {
      buffer[i].l = monoBuffer[i];
      buffer[i].r = monoBuffer[i];
    }
  }

  public void triggerNote(int note, int vel) {
    FirmwareVoice voiceForLegato = null;

    if (polyphonic != PolyphonyMode.POLY) {
      for (FirmwareVoice v : voices) {
        if (v.active) {
          if (polyphonic == PolyphonyMode.LEGATO) {
            voiceForLegato = v;
            break;
          }
          if (polyphonic == PolyphonyMode.MONO) {
            v.noteOff(0);
          }
        }
      }
    }

    if (voiceForLegato != null) {
      voiceForLegato.noteOn(note, vel);
      return;
    }

    // Find free voice
    for (FirmwareVoice v : voices) {
      if (!v.active) {
        v.noteOn(note, vel);
        return;
      }
    }
    // Allocate new if below max
    if (voices.size() < maxPolyphony) {
      FirmwareVoice v = new FirmwareVoice(this);
      v.noteOn(note, vel);
      voices.add(v);
    }
  }
}
