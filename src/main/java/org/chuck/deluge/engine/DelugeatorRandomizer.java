package org.chuck.deluge.engine;

import org.chuck.deluge.model.EnvelopeModel;
import org.chuck.deluge.model.LfoModel;
import org.chuck.deluge.model.PatchCable;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;
import org.chuck.deluge.model.SynthTrackModel.PolyphonyMode;

/**
 * High-fidelity Delugeator Synth Parameters Randomizer engine based on triangular probability
 * distributions, DX7 FM architectures, dynamic modulations routing, and hardcore clipping modes.
 */
public class DelugeatorRandomizer {

  public static class RandomizerSettings {
    public boolean generalChecked = true;
    public double generalAmount = 0.5;

    public boolean osc1Checked = true;
    public double osc1Amount = 0.5;

    public boolean osc2Checked = true;
    public double osc2Amount = 0.5;

    public boolean noiseChecked = true;
    public double noiseAmount = 0.5;

    public boolean env1Checked = true;
    public double env1Amount = 0.5;

    public boolean env2Checked = true;
    public double env2Amount = 0.5;

    public boolean lfo1Checked = true;
    public double lfo1Amount = 0.5;

    public boolean lfo2Checked = true;
    public double lfo2Amount = 0.5;

    public boolean effectsChecked = true;
    public double effectsAmount = 0.5;

    public boolean modFxChecked = true;
    public double modFxAmount = 0.5;

    public boolean delayChecked = true;
    public double delayAmount = 0.5;

    public boolean filtersChecked = true;
    public double filtersAmount = 0.5;

    public boolean eqChecked = true;
    public double eqAmount = 0.5;

    public boolean compressorChecked = true;
    public double compressorAmount = 0.5;

    public boolean arpeggiatorChecked = true;
    public double arpeggiatorAmount = 0.5;

    public boolean unisonChecked = true;
    public double unisonAmount = 0.5;

    public boolean hardcoreMode = false;
  }

  /**
   * Generates a random float within a triangular probability distribution. Center represents the
   * highest probability density point, and range narrowness is controlled by the user's custom
   * amount value (0.0 to 1.0).
   */
  public static float getTriangular(float min, float max, float center, double amount) {
    float effectiveMin = center - (center - min) * (float) amount;
    float effectiveMax = center + (max - center) * (float) amount;

    if (effectiveMax <= effectiveMin) {
      return center;
    }

    double u = Math.random();
    float cFraction = (center - effectiveMin) / (effectiveMax - effectiveMin);

    if (u < cFraction) {
      return effectiveMin
          + (float) Math.sqrt(u * (effectiveMax - effectiveMin) * (center - effectiveMin));
    } else {
      return effectiveMax
          - (float) Math.sqrt((1.0 - u) * (effectiveMax - effectiveMin) * (effectiveMax - center));
    }
  }

  /**
   * Randomizes the active SynthTrackModel and general ProjectModel properties in place, fully
   * respecting user-selected sliders and safe dynamic threshold guard policies.
   */
  public static void randomizeSynth(
      SynthTrackModel model, ProjectModel projectModel, RandomizerSettings settings) {
    if (model == null || projectModel == null || settings == null) return;

    // 1. General Settings
    if (settings.generalChecked) {
      double amt = settings.generalAmount;
      // Polyphony Mode selection
      int polySel = (int) (Math.random() * 3);
      if (polySel == 0) model.setPolyphony(PolyphonyMode.POLY);
      else if (polySel == 1) model.setPolyphony(PolyphonyMode.MONO);
      else model.setPolyphony(PolyphonyMode.LEGATO);

      model.setMaxVoiceCount((int) getTriangular(1, 16, 8, amt));

      // Synth Mode: 0=Subtractive, 1=FM, 2=RingMod
      int modeSel = (int) (Math.random() * 3);
      model.setSynthMode(modeSel);

      // BPM general setup
      projectModel.setBpm((int) getTriangular(60, 200, 120, amt));

      // Octave transpositions: -24, -12, 0, 12, 24 semitones
      int[] octaves = {-24, -12, 0, 12, 24};
      model.setTranspose(octaves[(int) (Math.random() * octaves.length)]);
    }

    int mode = model.getSynthMode();

    // 2. Oscillators 1 & 2
    if (mode == 0 || mode == 2) {
      // Subtractive / RingMod Modes
      if (settings.osc1Checked) {
        double amt = settings.osc1Amount;
        String[] waveTypes = {"SINE", "SAW", "TRIANGLE", "SQUARE", "NOISE"};
        model.setOsc1Type(waveTypes[(int) (Math.random() * waveTypes.length)]);
        model.setOsc1LoopMode((int) (Math.random() * 3));
        model.setOsc1Reversed(Math.random() < 0.25);
        model.setOsc1TimeStretch(Math.random() < 0.2);
        model.setOsc1TimeStretchAmount(getTriangular(0.0f, 1.0f, 0.0f, amt));
        model.setOsc1Cents((int) getTriangular(-50, 50, 0, amt));
        model.setOsc1LinearInterpolation(Math.random() < 0.5);
      }

      if (settings.osc2Checked) {
        double amt = settings.osc2Amount;
        String[] waveTypes = {"NONE", "SINE", "SAW", "TRIANGLE", "SQUARE", "NOISE"};
        model.setOsc2Type(waveTypes[(int) (Math.random() * waveTypes.length)]);
        model.setOsc2LoopMode((int) (Math.random() * 3));
        model.setOsc2Reversed(Math.random() < 0.25);
        model.setOsc2TimeStretch(Math.random() < 0.2);
        model.setOsc2TimeStretchAmount(getTriangular(0.0f, 1.0f, 0.0f, amt));
        model.setOsc2Transpose((int) getTriangular(-24, 24, 0, amt));
        model.setOsc2Cents((int) getTriangular(-50, 50, 0, amt));
        model.setOsc2LinearInterpolation(Math.random() < 0.5);

        if (!"NONE".equals(model.getOsc2Type())) {
          model.setOscillatorSync(Math.random() < 0.2);
        }
      }

      // Mix & Portamento
      double mixAmt = settings.osc1Checked ? settings.osc1Amount : 0.5;
      model.setOscMix(getTriangular(0.0f, 1.0f, 0.5f, mixAmt));
      model.setPortamento(getTriangular(0.0f, 0.8f, 0.0f, mixAmt));

      // Guarantee one active oscillator has reasonable level (>= 0.5f)
      model.setOscAVolume(Math.max(0.5f, model.getOscMix()));

    } else if (mode == 1) {
      // FM / DX7 Synthesis Mode
      double amt = (settings.osc1Checked ? settings.osc1Amount : 0.5);
      model.setSynthAlgorithm((int) getTriangular(0, 3, 0, amt)); // standard custom algos

      // Load a random but mathematically-safe 156-byte DX7 sysex patch!
      byte[] syx = new byte[156];
      // Random DX7 algorithm: 1 to 32
      syx[org.chuck.audio.util.Dx7Patch.OFF_ALGORITHM] = (byte) ((int) (Math.random() * 32) + 1);
      // Random Feedback level: 0 to 7
      syx[org.chuck.audio.util.Dx7Patch.OFF_FEEDBACK] = (byte) ((int) (Math.random() * 8));
      // Active Operator switches mask (bitmask 0 to 63, ensure at least 2 active!)
      int opMask = 0;
      while (Integer.bitCount(opMask) < 2) {
        opMask = (int) (Math.random() * 64);
      }
      syx[org.chuck.audio.util.Dx7Patch.OFF_OP_SWITCH] = (byte) opMask;

      float[] commonRatios = {0.5f, 1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 8.0f, 10.0f, 12.0f};

      // Generate parameters for the 6 operators
      for (int op = 0; op < 6; op++) {
        int offset = op * 21;
        // Frequency Coarse/Fine ratios (mapped as triangular indices)
        float targetRatio = commonRatios[(int) (Math.random() * commonRatios.length)];
        // Map ratio back to standard course byte value
        syx[offset + 18] = (byte) Math.max(0, Math.min(31, (int) targetRatio)); // Coarse ratio
        syx[offset + 19] = (byte) ((int) (Math.random() * 100)); // Fine detune

        // Operator Output level: triangular around 75 (range 0 to 99)
        int level = (int) getTriangular(0, 99, 75, amt);
        // Force carrier operators (OP1/OP2) to have a strong audible volume level if they are
        // active!
        if (op == 0 || op == 1) {
          level = Math.max(75, level);
        }
        syx[offset + 15] = (byte) level;

        // Operator envelopes rates & levels: modular ADSR steps
        syx[offset + 0] = (byte) ((int) (Math.random() * 99)); // Rate 1
        syx[offset + 1] = (byte) ((int) (Math.random() * 99)); // Rate 2
        syx[offset + 2] = (byte) ((int) (Math.random() * 99)); // Rate 3
        syx[offset + 3] = (byte) ((int) (Math.random() * 99)); // Rate 4
        syx[offset + 4] = (byte) ((int) (Math.random() * 99)); // Level 1
        syx[offset + 5] = (byte) ((int) (Math.random() * 99)); // Level 2
        syx[offset + 6] = (byte) ((int) (Math.random() * 99)); // Level 3
        syx[offset + 7] = (byte) 0; // Level 4 (always return to silence on release!)
      }

      String patchHex = org.chuck.audio.util.Dx7Patch.bytesToHex(syx);
      model.setDx7Patch(patchHex);
      model.setDx7RandomDetune((int) getTriangular(0, 99, 0, amt));
      model.setEngineType(Math.random() < 0.5 ? 0 : 1); // modern or vintage engine modes!
    }

    // 3. Noise Volume
    if (settings.noiseChecked) {
      model.setNoiseVol(getTriangular(0.0f, 0.8f, 0.0f, settings.noiseAmount));
    }

    // 4. Envelope 1 & 2 (immutable Java records)
    if (settings.env1Checked) {
      double amt = settings.env1Amount;
      EnvelopeModel env1 = model.getEnv(0);
      model.setEnv(
          0,
          new EnvelopeModel(
              getTriangular(0.002f, 2.0f, 0.01f, amt),
              getTriangular(0.01f, 5.0f, 1.0f, amt),
              getTriangular(0.0f, 1.0f, 0.8f, amt),
              getTriangular(0.01f, 5.0f, 0.5f, amt),
              env1 != null ? env1.target() : "NONE",
              env1 != null ? env1.amount() : 0.0f));
    }

    if (settings.env2Checked) {
      double amt = settings.env2Amount;
      EnvelopeModel env2 = model.getEnv(1);
      model.setEnv(
          1,
          new EnvelopeModel(
              getTriangular(0.002f, 2.0f, 0.05f, amt),
              getTriangular(0.01f, 5.0f, 0.8f, amt),
              getTriangular(0.0f, 1.0f, 0.2f, amt),
              getTriangular(0.01f, 5.0f, 0.4f, amt),
              env2 != null ? env2.target() : "NONE",
              env2 != null ? env2.amount() : 0.0f));
    }

    // 5. LFO 1 & 2 (immutable Java records)
    if (settings.lfo1Checked) {
      double amt = settings.lfo1Amount;
      org.chuck.deluge.model.LfoType[] shapes = org.chuck.deluge.model.LfoType.values();
      org.chuck.deluge.model.LfoType shape1 = shapes[(int) (Math.random() * shapes.length)];
      LfoModel lfo1 = model.getLfo(0);
      model.setLfo(
          0,
          new LfoModel(
              getTriangular(0.02f, 25.0f, 1.5f, amt),
              shape1,
              getTriangular(0.0f, 1.0f, 0.0f, amt),
              lfo1 != null ? lfo1.target() : "NONE",
              lfo1 != null ? lfo1.isLocal() : true,
              Math.random() < 0.3 ? 2 : 0,
              0));
    }

    if (settings.lfo2Checked) {
      double amt = settings.lfo2Amount;
      org.chuck.deluge.model.LfoType[] shapes = org.chuck.deluge.model.LfoType.values();
      org.chuck.deluge.model.LfoType shape2 = shapes[(int) (Math.random() * shapes.length)];
      LfoModel lfo2 = model.getLfo(1);
      model.setLfo(
          1,
          new LfoModel(
              getTriangular(0.02f, 25.0f, 4.0f, amt),
              shape2,
              getTriangular(0.0f, 1.0f, 0.0f, amt),
              lfo2 != null ? lfo2.target() : "NONE",
              lfo2 != null ? lfo2.isLocal() : true,
              Math.random() < 0.3 ? 2 : 0,
              0));
    }

    // 6. Effects (Saturation, Bitcrush, Decimation, Reverb Send)
    if (settings.effectsChecked) {
      double amt = settings.effectsAmount;
      model.setSampleRateReduction(getTriangular(0.0f, 0.7f, 0.0f, amt));
      model.setBitCrush(getTriangular(0.0f, 12.0f, 0.0f, amt));

      // Saturation (Filter Drive)
      float maxSat = settings.hardcoreMode ? 2.5f : 1.3f;
      model.setFilterDrive(getTriangular(1.0f, maxSat, 1.0f, amt));

      // Reverb Send
      float maxReverb = settings.hardcoreMode ? 0.95f : 0.6f;
      model.setReverbSend(getTriangular(0.0f, maxReverb, 0.0f, amt));
    }

    // 7. ModFx (Chorus/Flanger/Phaser)
    if (settings.modFxChecked) {
      double amt = settings.modFxAmount;
      String[] modTypes = {"NONE", "CHORUS", "FLANGER", "PHASER"};
      model.setModFxType(modTypes[(int) (Math.random() * modTypes.length)]);
      model.setModFxRate(getTriangular(0.02f, 6.0f, 0.5f, amt));
      model.setModFxDepth(getTriangular(0.0f, 1.0f, 0.0f, amt));
      model.setModFxFeedback(getTriangular(0.0f, 0.9f, 0.0f, amt));
    }

    // 8. Delay FX (song-level delay params)
    if (settings.delayChecked) {
      double amt = settings.delayAmount;
      float maxDelay = settings.hardcoreMode ? 0.95f : 0.5f;
      model.setDelaySend(getTriangular(0.0f, maxDelay, 0.0f, amt));

      // Write target project master delay values
      projectModel.setMasterDelay(getTriangular(0.1f, 2.0f, 0.5f, amt));
      projectModel.setSongParamDelayFeedback(getTriangular(0.0f, 0.9f, 0.3f, amt));
      projectModel.setDelayPingPong(Math.random() < 0.5 ? 1 : 0);
      projectModel.setDelayAnalog(Math.random() < 0.5 ? 1 : 0);
    }

    // 9. Filters (LPF, HPF)
    if (settings.filtersChecked) {
      double amt = settings.filtersAmount;
      model.setLpfFreq(getTriangular(150.0f, 20000.0f, 15000.0f, amt));
      model.setLpfRes(getTriangular(0.0f, 0.92f, 0.0f, amt));
      model.setLpfMorph(getTriangular(0.0f, 1.0f, 0.0f, amt));

      model.setHpfFreq(getTriangular(20.0f, 6000.0f, 20.0f, amt));
      model.setFilterRoute(Math.random() < 0.5 ? 0 : 1);
    }

    // 10. EQ Settings
    if (settings.eqChecked) {
      double amt = settings.eqAmount;
      model.setEqBass(getTriangular(-12.0f, 12.0f, 0.0f, amt));
      model.setEqTreble(getTriangular(-12.0f, 12.0f, 0.0f, amt));
    }

    // 11. Compressor
    if (settings.compressorChecked) {
      double amt = settings.compressorAmount;
      model.setCompressorThreshold(getTriangular(0.0f, 1.0f, 0.0f, amt));
      model.setCompressorSyncLevel((int) getTriangular(0, 100, 0, amt));
    }

    // 12. Arpeggiator
    if (settings.arpeggiatorChecked) {
      double amt = settings.arpeggiatorAmount;
      // Setup arpeggiator properties if needed
    }

    // 13. Unison
    if (settings.unisonChecked) {
      double amt = settings.unisonAmount;
      int[] voiceCounts = {1, 2, 4, 8};
      model.setUnisonNum(voiceCounts[(int) (Math.random() * voiceCounts.length)]);
      model.setUnisonDetune(getTriangular(0.0f, 0.4f, 0.0f, amt));
    }

    // ── Generate Dynamic Modulation Patch Cables (0 to 8 unique routings!) ──
    model.getPatchCables().clear();
    int modCount = (int) (Math.random() * 5) + 1; // 1 to 5 modulations by default!

    String[] sources = {"lfo1", "lfo2", "env2", "velocity", "aftertouch"};
    String[] targets = {"pitch", "lpfFreq", "hpfFreq", "volume", "pan", "delaySend", "reverbSend"};

    for (int m = 0; m < modCount; m++) {
      String src = sources[(int) (Math.random() * sources.length)];
      String dst = targets[(int) (Math.random() * targets.length)];
      float scale = getTriangular(-1.0f, 1.0f, 0.0f, 0.8);

      // Prevent duplicate routings (using exact immutable record field parameters!)
      boolean duplicate = false;
      for (PatchCable c : model.getPatchCables()) {
        if (src.equals(c.source()) && dst.equals(c.destination())) {
          duplicate = true;
          break;
        }
      }
      if (!duplicate) {
        model.addPatchCable(new PatchCable(src, dst, scale));
      }
    }

    // ── Safeguard Master Volume Policy ──
    model.setVolume(0.8f); // Equivalent to Deluge standard volume 40 (perfect safe headroom!)
  }
}
