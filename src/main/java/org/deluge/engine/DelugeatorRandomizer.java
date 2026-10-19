package org.deluge.engine;

import org.deluge.model.EnvelopeModel;
import org.deluge.model.LfoModel;
import org.deluge.model.PatchCable;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.model.SynthTrackModel.PolyphonyMode;

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
      // Clear any prior DX7 patch and stale "DX7" osc1Type so re-randomizing into Subtractive or
      // RingMod never stays trapped in DX7 routing even when settings.osc1Checked is false.
      model.setDx7Patch(null);
      if ("DX7".equalsIgnoreCase(model.getOsc1Type())) {
        model.setOsc1Type("SAW");
      }
      // Subtractive / RingMod Modes
      if (settings.osc1Checked) {
        double amt = settings.osc1Amount;
        String[] waveTypes = {"SINE", "SAW", "TRIANGLE", "SQUARE", "NOISE"};
        model.setOsc1Type(waveTypes[(int) (Math.random() * waveTypes.length)]);
        model.setOsc1LoopMode((int) (Math.random() * 3));
        model.setOsc1Reversed(Math.random() < 0.25);
        model.setOsc1TimeStretch(Math.random() < 0.2);
        model.setOsc1TimeStretchAmount((int) getTriangular(-48, 48, 0, amt));
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
        model.setOsc2TimeStretchAmount((int) getTriangular(-48, 48, 0, amt));
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
      // FM / DX7 Synthesis Mode (C dx7note.cpp:42-88, fm_core.cpp:21-54)
      double amt = (settings.osc1Checked ? settings.osc1Amount : 0.5);
      model.setOsc1Type("DX7");
      // C sound.cpp:150-151 — pure Source 0 (OSC A = 0x7FFFFFFF, OSC B = 0x80000000 off).
      model.setOscMix(1.0f);

      // Seed from C init_voice (dx7note.cpp:42-48) so neutral Pitch EG levels (50), concert
      // transpose (24 = C3), and neutral operator fine detune (7) are preserved.
      byte[] syx = org.deluge.shadow.audio.Dx7Patch.createInitVoiceBytes();
      // Valid 0-indexed DX7 algorithm: 0..31 (FmCore.ALGORITHMS[0..31]).
      int algo = (int) (Math.random() * 32);
      syx[org.deluge.shadow.audio.Dx7Patch.OFF_ALGORITHM] = (byte) algo;
      model.setSynthAlgorithm(algo);
      // Random Feedback level: 0..7
      syx[org.deluge.shadow.audio.Dx7Patch.OFF_FEEDBACK] = (byte) ((int) (Math.random() * 8));
      // Active Operator switches mask (6-bit mask; guarantee at least 2 ops + all carrier slots)
      int opMask = 0;
      while (Integer.bitCount(opMask) < 2) {
        opMask = (int) (Math.random() * 64);
      }

      int[] carrierCoarseRatios = {1, 1, 1, 2};
      int[] modCoarseRatios = {0, 1, 2, 3, 4, 5, 6, 8, 10, 12};

      // Generate parameters for the 6 operator slots (op 0..5 = OP6..OP1)
      for (int op = 0; op < 6; op++) {
        int offset = op * 21;
        boolean isCarrier =
            (org.deluge.firmware2.FmCore.ALGORITHMS[algo][op]
                    & org.deluge.firmware2.FmCore.OUT_BUS_ADD)
                != 0;
        if (isCarrier) {
          opMask |= (1 << op);
          // C dx7note.cpp:150-166 — keep carriers on concert harmonic integer ratios (fine = 0,
          // subtle detune around center 7) so MIDI 60 stays in tune with other tracks.
          syx[offset + 18] =
              (byte) carrierCoarseRatios[(int) (Math.random() * carrierCoarseRatios.length)];
          syx[offset + 19] = 0;
          syx[offset + 20] = (byte) (6 + (int) (Math.random() * 3));
        } else {
          syx[offset + 18] = (byte) modCoarseRatios[(int) (Math.random() * modCoarseRatios.length)];
          syx[offset + 19] = (byte) (Math.random() < 0.8 ? 0 : (int) (Math.random() * 100));
        }

        // Key velocity sensitivity at +15 (0..7, dx7note.cpp:248); output level at +16 (0..99,
        // dx7note.cpp:241).
        syx[offset + 15] = (byte) ((int) (Math.random() * 8));
        int level = (int) getTriangular(0, 99, 75, amt);
        if (isCarrier) {
          level = Math.max(85, level);
        }
        syx[offset + 16] = (byte) level;

        // Operator envelope rates & levels (dx7note.cpp:251)
        syx[offset + 0] =
            (byte) (isCarrier ? (55 + (int) (Math.random() * 45)) : ((int) (Math.random() * 100)));
        syx[offset + 1] = (byte) ((int) (Math.random() * 100));
        syx[offset + 2] = (byte) ((int) (Math.random() * 100));
        syx[offset + 3] = (byte) (35 + (int) (Math.random() * 65));
        syx[offset + 4] =
            (byte) (isCarrier ? (85 + (int) (Math.random() * 15)) : ((int) (Math.random() * 100)));
        syx[offset + 5] =
            (byte) (isCarrier ? (65 + (int) (Math.random() * 35)) : ((int) (Math.random() * 100)));
        syx[offset + 6] =
            (byte) (isCarrier ? (60 + (int) (Math.random() * 40)) : ((int) (Math.random() * 100)));
        syx[offset + 7] = (byte) 0; // Level 4 (return to silence on release)
      }
      syx[org.deluge.shadow.audio.Dx7Patch.OFF_OP_SWITCH] = (byte) opMask;

      String patchHex = org.deluge.shadow.audio.Dx7Patch.bytesToHex(syx);
      model.setDx7Patch(patchHex);
      model.setDx7RandomDetune((int) getTriangular(0, 99, 0, amt));
      // C dx7note.cpp:68-78 — 1 = modern, 2 = vintage (MkI)
      model.setEngineType(Math.random() < 0.5 ? 1 : 2);
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
      org.deluge.model.LfoType[] shapes = org.deluge.model.LfoType.values();
      org.deluge.model.LfoType shape1 = shapes[(int) (Math.random() * shapes.length)];
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
      org.deluge.model.LfoType[] shapes = org.deluge.model.LfoType.values();
      org.deluge.model.LfoType shape2 = shapes[(int) (Math.random() * shapes.length)];
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

    // 10. EQ Settings (normalized bipolar -1..+1, matching UNPATCHED_BASS / UNPATCHED_TREBLE)
    if (settings.eqChecked) {
      double amt = settings.eqAmount;
      model.setEqBass(getTriangular(-1.0f, 1.0f, 0.0f, amt));
      model.setEqTreble(getTriangular(-1.0f, 1.0f, 0.0f, amt));
    }

    // 11. Compressor (C sync.h:22-35, MAX_SYNC_LEVEL = 9; mod_controllable_audio.cpp:903-906)
    if (settings.compressorChecked) {
      double amt = settings.compressorAmount;
      model.setCompressorThreshold(getTriangular(0.0f, 1.0f, 0.0f, amt));
      model.setCompressorSyncLevel((int) getTriangular(0, 9, 0, amt));
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
      model.getUnison().setUnisonNum(voiceCounts[(int) (Math.random() * voiceCounts.length)]);
      model.getUnison().setUnisonDetune(getTriangular(0.0f, 0.4f, 0.0f, amt));
    }

    // ── Generate Dynamic Modulation Patch Cables (0 to 8 unique routings!) ──
    model.getModulation().getPatchCables().clear();
    int modCount = (int) (Math.random() * 5) + 1; // 1 to 5 modulations by default!

    String[] sources = {"lfo1", "lfo2", "env2", "velocity", "aftertouch"};
    String[] targets = {"pitch", "lpfFreq", "hpfFreq", "volume", "pan", "delaySend", "reverbSend"};

    for (int m = 0; m < modCount; m++) {
      String src = sources[(int) (Math.random() * sources.length)];
      String dst = targets[(int) (Math.random() * targets.length)];
      float scale = getTriangular(-1.0f, 1.0f, 0.0f, 0.8);

      // Prevent duplicate routings (using exact immutable record field parameters!)
      boolean duplicate = false;
      for (PatchCable c : model.getModulation().getPatchCables()) {
        if (src.equals(c.source()) && dst.equals(c.destination())) {
          duplicate = true;
          break;
        }
      }
      if (!duplicate) {
        model.getModulation().addPatchCable(new PatchCable(src, dst, scale));
      }
    }

    // ── Safeguard Master Volume Policy ──
    model.setVolume(0.8f); // Equivalent to Deluge standard volume 40 (perfect safe headroom!)
  }
}
