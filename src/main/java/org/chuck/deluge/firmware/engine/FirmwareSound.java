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
import org.chuck.deluge.firmware.util.Q31;

/** 
 * Port of the Deluge's Sound class.
 * This is the central high-fidelity synthesis engine for a single instrument or kit.
 */
public class FirmwareSound extends GlobalEffectable {
  public enum SynthMode { SUBTRACTIVE, FM, RINGMOD }

  public final List<FirmwareVoice> voices = new ArrayList<>();
  public final LFO[] globalLfos = new LFO[2];
  public final org.chuck.deluge.firmware.model.sample.Sample[] samples = new org.chuck.deluge.firmware.model.sample.Sample[2];
  public final org.chuck.deluge.firmware.dsp.oscillators.OscType[] oscTypes = {org.chuck.deluge.firmware.dsp.oscillators.OscType.SINE, org.chuck.deluge.firmware.dsp.oscillators.OscType.SINE};
  public int maxPolyphony = 64;
  public PolyphonyMode polyphonic = PolyphonyMode.POLY;
  public boolean isDrum = false;
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
    // Default neutral values as requested for LKG state
    paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = Q31.ONE;
    paramNeutralValues[Param.LOCAL_VOLUME] = Q31.ONE;
  }

  public SynthMode getSynthMode() { return synthMode; }

  public static int noteToPhaseInc(int note) {
    double freq = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
    return (int)(freq * (4294967296.0 / 44100.0));
  }

  @Override
  protected void renderInternal(StereoSample[] buffer, int numSamples, ParamManager unused) {
    // 1. Update Global LFOs
    for (int i = 0; i < 2; i++) {
      globalSourceValues[PatchSource.LFO_GLOBAL_1.ordinal() + i] =
          globalLfos[i].render(numSamples, LFO.LFOType.SINE, 5000); 
    }

    // 2. Sum Voices
    int[] monoBuffer = new int[numSamples];
    synchronized(voices) {
        java.util.Iterator<FirmwareVoice> it = voices.iterator();
        while (it.hasNext()) {
            FirmwareVoice voice = it.next();
            if (!voice.active) {
                it.remove();
                continue;
            }

            java.util.Arrays.fill(monoBuffer, 0);
            int pIncA = noteToPhaseInc(voice.note);
            int pIncB = noteToPhaseInc(voice.note + 12); 

            if (voice.render(monoBuffer, numSamples, pIncA, pIncB)) {
                for (int i = 0; i < numSamples; i++) {
                    buffer[i].l = Q31.addSaturate(buffer[i].l, monoBuffer[i]);
                    buffer[i].r = Q31.addSaturate(buffer[i].r, monoBuffer[i]);
                }
            }
        }
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

  private final int[] voiceMonoBuffer = new int[128];

  public void triggerNote(int note, int vel) {
    synchronized(voices) {
        FirmwareVoice voiceToUse = null;
        
        if (polyphonic != PolyphonyMode.POLY) {
            for (FirmwareVoice v : voices) {
                if (v.active) {
                    voiceToUse = v;
                    break;
                }
            }
        }
        
        if (voiceToUse != null) {
            voiceToUse.noteOn(note, vel);
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

  public void noteOffAll() {
    synchronized(voices) {
        for (FirmwareVoice v : voices) {
          if (v.active) v.noteOff(0);
        }
    }
  }

  public void releaseNote(int note) {
    synchronized(voices) {
        for (FirmwareVoice v : voices) {
          if (v.active && v.note == note) {
            v.noteOff(0);
          }
        }
    }
  }
}
