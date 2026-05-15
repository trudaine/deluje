package org.chuck.deluge.firmware.engine;

import java.util.ArrayList;
import java.util.List;
import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.fx.ModFXProcessor;
import org.chuck.deluge.firmware.dsp.fx.ModFXType;
import org.chuck.deluge.firmware.dsp.granular.GranularProcessor;
import org.chuck.deluge.firmware.dsp.timestretch.TimeStretcher;
import org.chuck.deluge.firmware.model.PolyphonyMode;
import org.chuck.deluge.firmware.modulation.LFO;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.modulation.patch.PatchSource;
import org.chuck.deluge.firmware.modulation.params.ParamManager;
import org.chuck.deluge.firmware.modulation.sidechain.SideChain;

/** 
 * Port of the Deluge's Sound class.
 * This is the central high-fidelity synthesis engine for a single instrument or kit.
 */
public class FirmwareSound extends GlobalEffectable {
  public enum SynthMode { SUBTRACTIVE, FM, RINGMOD }

  public final List<FirmwareVoice> voices = new ArrayList<>();
  public final LFO[] globalLfos = new LFO[2];
  public int maxPolyphony = 64;
  public PolyphonyMode polyphonic = PolyphonyMode.POLY;
  public ParamManager paramManager = new ParamManager();
  public int[] paramNeutralValues = new int[Param.kNumParams];
  public int[] globalSourceValues = new int[PatchSource.kNumPatchSources];

  // ── Ported High-Fidelity Logic ──
  public SynthMode synthMode = SynthMode.SUBTRACTIVE;
  public int numUnison = 1;
  public int unisonDetune = 8;
  public int unisonStereoSpread = 0;
  public final int[] monophonicExpressionValues = new int[3]; // X, Y, Z
  
  public final ModFXProcessor modFX = new ModFXProcessor();
  public ModFXType modFXType = ModFXType.NONE;
  public final GranularProcessor granular = new GranularProcessor();
  public final SideChain sidechain = new SideChain();
  public final Stutterer stutterer = new Stutterer();

  public FirmwareSound() {
    for (int i = 0; i < globalLfos.length; i++) globalLfos[i] = new LFO();
  }

  public SynthMode getSynthMode() { return synthMode; }

  @Override
  protected void renderInternal(StereoSample[] buffer, int numSamples, ParamManager unused) {
    // 1. Update Global LFOs
    for (int i = 0; i < 2; i++) {
      globalSourceValues[PatchSource.LFO_GLOBAL_1.ordinal() + i] =
          globalLfos[i].render(numSamples, LFO.LFOType.SINE, 5000); 
    }

    // 2. Sum Voices
    int[] monoBuffer = new int[numSamples];
    for (FirmwareVoice voice : voices) {
      if (voice.active) {
        voice.render(monoBuffer, numSamples, 10000, 20000); 
      }
    }

    // Convert to Stereo for FX processing
    for (int i = 0; i < numSamples; i++) {
      buffer[i].l = monoBuffer[i];
      buffer[i].r = monoBuffer[i];
    }

    // 3. Apply High-Fidelity FX Chain
    
    // Stutter
    stutterer.processStutter(buffer, paramManager);

    // Modulation FX (Chorus, Flanger, etc.)
    int[] postFXVolume = { 2147483647 };
    modFX.processModFX(buffer, modFXType, 100, 100, postFXVolume, 0, 0);

    // Sidechain
    int scAmount = sidechain.render(numSamples, 0);
    for (int i = 0; i < numSamples; i++) {
        buffer[i].l = (int)(((long)buffer[i].l * scAmount) >> 31);
        buffer[i].r = (int)(((long)buffer[i].r * scAmount) >> 31);
    }

    // Filters (handled by processFilters in GlobalEffectable)
    processFilters(buffer, numSamples);
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

    for (FirmwareVoice v : voices) {
      if (!v.active) {
        v.noteOn(note, vel);
        return;
      }
    }
    if (voices.size() < maxPolyphony) {
      FirmwareVoice v = new FirmwareVoice(this);
      v.noteOn(note, vel);
      voices.add(v);
    }
  }
}
