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
    return getTriangular(
        min, max, center, amount, java.util.concurrent.ThreadLocalRandom.current());
  }

  public static float getTriangular(
      float min, float max, float center, double amount, java.util.Random rng) {
    float effectiveMin = center - (center - min) * (float) amount;
    float effectiveMax = center + (max - center) * (float) amount;

    if (effectiveMax <= effectiveMin) {
      return center;
    }

    double u = rng.nextDouble();
    float cFraction = (center - effectiveMin) / (effectiveMax - effectiveMin);

    if (u < cFraction) {
      return effectiveMin
          + (float) Math.sqrt(u * (effectiveMax - effectiveMin) * (center - effectiveMin));
    } else {
      return effectiveMax
          - (float) Math.sqrt((1.0 - u) * (effectiveMax - effectiveMin) * (effectiveMax - center));
    }
  }

  public static final int MIN_DX7_CARRIER_LEVEL = 88;
  public static final int MIN_DX7_CARRIER_R1 = 62;
  public static final int MIN_DX7_CARRIER_L1 = 85;

  /**
   * Randomizes the active SynthTrackModel and general ProjectModel properties in place, fully
   * respecting user-selected sliders and safe dynamic threshold guard policies.
   */
  public static void randomizeSynth(
      SynthTrackModel model, ProjectModel projectModel, RandomizerSettings settings) {
    randomizeSynth(model, projectModel, settings, java.util.concurrent.ThreadLocalRandom.current());
  }

  public static void randomizeSynth(
      SynthTrackModel model,
      ProjectModel projectModel,
      RandomizerSettings settings,
      java.util.Random rng) {
    if (model == null || projectModel == null || settings == null || rng == null) return;

    // 1. General Settings
    if (settings.generalChecked) {
      double amt = settings.generalAmount;
      // Polyphony Mode selection
      int polySel = rng.nextInt(3);
      if (polySel == 0) model.setPolyphony(PolyphonyMode.POLY);
      else if (polySel == 1) model.setPolyphony(PolyphonyMode.MONO);
      else model.setPolyphony(PolyphonyMode.LEGATO);

      model.setMaxVoiceCount((int) getTriangular(1, 16, 8, amt, rng));

      // Synth Mode: 0=Subtractive, 1=FM, 2=RingMod
      int modeSel = rng.nextInt(3);
      model.setSynthMode(modeSel);

      // BPM general setup
      projectModel.setBpm((int) getTriangular(60, 200, 120, amt, rng));

      // Octave transpositions: -24, -12, 0, 12, 24 semitones
      int[] octaves = {-24, -12, 0, 12, 24};
      model.setTranspose(octaves[rng.nextInt(octaves.length)]);
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
        String[] waveTypes = {"SINE", "SAW", "TRIANGLE", "SQUARE"};
        model.setOsc1Type(waveTypes[rng.nextInt(waveTypes.length)]);
        model.setOsc1LoopMode(rng.nextInt(3));
        model.setOsc1Reversed(rng.nextDouble() < 0.25);
        model.setOsc1TimeStretch(rng.nextDouble() < 0.2);
        model.setOsc1TimeStretchAmount((int) getTriangular(-48, 48, 0, amt, rng));
        model.setOsc1Cents((int) getTriangular(-50, 50, 0, amt, rng));
        model.setOsc1LinearInterpolation(rng.nextBoolean());
      }

      if (settings.osc2Checked) {
        double amt = settings.osc2Amount;
        String[] waveTypes = {"NONE", "SINE", "SAW", "TRIANGLE", "SQUARE"};
        model.setOsc2Type(waveTypes[rng.nextInt(waveTypes.length)]);
        model.setOsc2LoopMode(rng.nextInt(3));
        model.setOsc2Reversed(rng.nextDouble() < 0.25);
        model.setOsc2TimeStretch(rng.nextDouble() < 0.2);
        model.setOsc2TimeStretchAmount((int) getTriangular(-48, 48, 0, amt, rng));
        model.setOsc2Transpose((int) getTriangular(-24, 24, 0, amt, rng));
        model.setOsc2Cents((int) getTriangular(-50, 50, 0, amt, rng));
        model.setOsc2LinearInterpolation(rng.nextBoolean());

        if (!"NONE".equals(model.getOsc2Type())) {
          model.setOscillatorSync(rng.nextDouble() < 0.2);
        }
      }

      // Mix & Portamento
      double mixAmt = settings.osc1Checked ? settings.osc1Amount : 0.5;
      model.setOscMix(getTriangular(0.0f, 1.0f, 0.5f, mixAmt, rng));
      model.setPortamento(getTriangular(0.0f, 0.8f, 0.0f, mixAmt, rng));

      // Guarantee one active oscillator has reasonable level (>= 0.5f)
      model.setOscAVolume(Math.max(0.5f, model.getOscMix()));

    } else if (mode == 1) {
      // FM / DX7 Synthesis Mode (C dx7note.cpp:42-88, fm_core.cpp:21-54)
      double amt = (settings.osc1Checked ? settings.osc1Amount : 0.5);
      model.setOsc1Type("DX7");
      // C sound.cpp:142, 301 — pure Source 0 (OSC A = 0x7FFFFFFF, OSC B = 0x80000000 off).
      model.setOscMix(1.0f);

      // Seed from C init_voice (dx7note.cpp:42-48) so neutral Pitch EG levels (50), concert
      // transpose (24 = C3), and neutral operator fine detune (7) are preserved.
      byte[] syx = org.deluge.shadow.audio.Dx7Patch.createInitVoiceBytes();
      // Valid 0-indexed DX7 algorithm: 0..31 (FmCore.ALGORITHMS[0..31]).
      int algo = rng.nextInt(32);
      syx[org.deluge.shadow.audio.Dx7Patch.OFF_ALGORITHM] = (byte) algo;
      model.setSynthAlgorithm(algo);
      // Random Feedback level: 0..7
      syx[org.deluge.shadow.audio.Dx7Patch.OFF_FEEDBACK] = (byte) rng.nextInt(8);
      // Active Operator switches mask (6-bit mask; guarantee at least 2 ops + all carrier slots)
      int opMask = 0;
      while (Integer.bitCount(opMask) < 2) {
        opMask = rng.nextInt(64);
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
          syx[offset + 18] = (byte) carrierCoarseRatios[rng.nextInt(carrierCoarseRatios.length)];
          syx[offset + 19] = 0;
          syx[offset + 20] = (byte) (6 + rng.nextInt(3));
        } else {
          syx[offset + 18] = (byte) modCoarseRatios[rng.nextInt(modCoarseRatios.length)];
          syx[offset + 19] = (byte) (rng.nextDouble() < 0.8 ? 0 : rng.nextInt(100));
        }

        // Key velocity sensitivity at +15 (0..7, dx7note.cpp:248); output level at +16 (0..99,
        // dx7note.cpp:241).
        syx[offset + 15] = (byte) rng.nextInt(8);
        int level = (int) getTriangular(0, 99, 75, amt, rng);
        if (isCarrier) {
          level = Math.max(MIN_DX7_CARRIER_LEVEL, level);
        }
        syx[offset + 16] = (byte) level;

        // Operator envelope rates & levels (dx7note.cpp:251)
        syx[offset + 0] =
            (byte)
                (isCarrier
                    ? (MIN_DX7_CARRIER_R1 + rng.nextInt(100 - MIN_DX7_CARRIER_R1))
                    : rng.nextInt(100));
        syx[offset + 1] = (byte) rng.nextInt(100);
        syx[offset + 2] = (byte) rng.nextInt(100);
        syx[offset + 3] = (byte) (35 + rng.nextInt(65));
        syx[offset + 4] =
            (byte)
                (isCarrier
                    ? (MIN_DX7_CARRIER_L1 + rng.nextInt(100 - MIN_DX7_CARRIER_L1))
                    : rng.nextInt(100));
        syx[offset + 5] = (byte) (isCarrier ? (65 + rng.nextInt(35)) : rng.nextInt(100));
        syx[offset + 6] = (byte) (isCarrier ? (60 + rng.nextInt(40)) : rng.nextInt(100));
        syx[offset + 7] = (byte) 0; // Level 4 (return to silence on release)
      }
      syx[org.deluge.shadow.audio.Dx7Patch.OFF_OP_SWITCH] = (byte) opMask;

      String patchHex = org.deluge.shadow.audio.Dx7Patch.bytesToHex(syx);
      model.setDx7Patch(patchHex);
      model.setDx7RandomDetune((int) getTriangular(0, 99, 0, amt, rng));
      // C dx7note.cpp:68-78 — 1 = modern, 2 = vintage (MkI)
      model.setEngineType(rng.nextBoolean() ? 1 : 2);
    }

    // 3. Noise Volume
    if (settings.noiseChecked) {
      model.setNoiseVol(getTriangular(0.0f, 0.8f, 0.0f, settings.noiseAmount, rng));
    }

    // 4. Envelope 1 & 2 (immutable Java records)
    if (settings.env1Checked) {
      double amt = settings.env1Amount;
      EnvelopeModel env1 = model.getEnv(0);
      float maxEnv1Attack = settings.hardcoreMode ? 2.0f : 0.35f;
      model.setEnv(
          0,
          new EnvelopeModel(
              getTriangular(0.002f, maxEnv1Attack, 0.01f, amt, rng),
              getTriangular(0.01f, 5.0f, 1.0f, amt, rng),
              getTriangular(0.25f, 1.0f, 0.8f, amt, rng),
              getTriangular(0.01f, 5.0f, 0.5f, amt, rng),
              env1 != null ? env1.target() : "NONE",
              env1 != null ? env1.amount() : 0.0f));
    }

    if (settings.env2Checked) {
      double amt = settings.env2Amount;
      EnvelopeModel env2 = model.getEnv(1);
      model.setEnv(
          1,
          new EnvelopeModel(
              getTriangular(0.002f, 2.0f, 0.05f, amt, rng),
              getTriangular(0.01f, 5.0f, 0.8f, amt, rng),
              getTriangular(0.0f, 1.0f, 0.2f, amt, rng),
              getTriangular(0.01f, 5.0f, 0.4f, amt, rng),
              env2 != null ? env2.target() : "NONE",
              env2 != null ? env2.amount() : 0.0f));
    }

    // 5. LFO 1 & 2 (immutable Java records)
    if (settings.lfo1Checked) {
      double amt = settings.lfo1Amount;
      org.deluge.model.LfoType[] shapes = org.deluge.model.LfoType.values();
      org.deluge.model.LfoType shape1 = shapes[rng.nextInt(shapes.length)];
      LfoModel lfo1 = model.getLfo(0);
      model.setLfo(
          0,
          new LfoModel(
              getTriangular(0.02f, 25.0f, 1.5f, amt, rng),
              shape1,
              getTriangular(0.0f, 1.0f, 0.0f, amt, rng),
              lfo1 != null ? lfo1.target() : "NONE",
              lfo1 != null ? lfo1.isLocal() : true,
              rng.nextDouble() < 0.3 ? 2 : 0,
              0));
    }

    if (settings.lfo2Checked) {
      double amt = settings.lfo2Amount;
      org.deluge.model.LfoType[] shapes = org.deluge.model.LfoType.values();
      org.deluge.model.LfoType shape2 = shapes[rng.nextInt(shapes.length)];
      LfoModel lfo2 = model.getLfo(1);
      model.setLfo(
          1,
          new LfoModel(
              getTriangular(0.02f, 25.0f, 4.0f, amt, rng),
              shape2,
              getTriangular(0.0f, 1.0f, 0.0f, amt, rng),
              lfo2 != null ? lfo2.target() : "NONE",
              lfo2 != null ? lfo2.isLocal() : true,
              rng.nextDouble() < 0.3 ? 2 : 0,
              0));
    }

    // 6. Effects (Saturation, Bitcrush, Decimation, Reverb Send)
    if (settings.effectsChecked) {
      double amt = settings.effectsAmount;
      model.setSampleRateReduction(getTriangular(0.0f, 0.7f, 0.0f, amt, rng));
      model.setBitCrush(getTriangular(0.0f, 0.7f, 0.0f, amt, rng));

      // Saturation (Filter Drive)
      float maxSat = settings.hardcoreMode ? 2.5f : 1.3f;
      model.setFilterDrive(getTriangular(1.0f, maxSat, 1.0f, amt, rng));

      // Reverb Send
      float maxReverb = settings.hardcoreMode ? 0.95f : 0.6f;
      model.setReverbSend(getTriangular(0.0f, maxReverb, 0.0f, amt, rng));
    }

    // 7. ModFx (Chorus/Flanger/Phaser)
    if (settings.modFxChecked) {
      double amt = settings.modFxAmount;
      String[] modTypes = {"NONE", "CHORUS", "FLANGER", "PHASER"};
      model.setModFxType(modTypes[rng.nextInt(modTypes.length)]);
      model.setModFxRate(getTriangular(0.0f, 1.0f, 0.35f, amt, rng));
      model.setModFxDepth(getTriangular(0.0f, 1.0f, 0.0f, amt, rng));
      model.setModFxFeedback(getTriangular(0.0f, 0.9f, 0.0f, amt, rng));
    }

    // 8. Delay FX (per-sound & song-level delay params)
    if (settings.delayChecked) {
      double amt = settings.delayAmount;
      float maxDelay = settings.hardcoreMode ? 0.95f : 0.5f;
      model.setDelaySend(getTriangular(0.0f, maxDelay, 0.0f, amt, rng));
      float perSoundFb = getTriangular(0.0f, 0.75f, 0.25f, amt, rng);
      model.setDelayFeedbackQ31(
          org.deluge.xml.DelugeHexMapper.unipolarFloatToQ31Unified(perSoundFb));

      // Write target project master delay values
      projectModel.setMasterDelay(getTriangular(0.1f, 2.0f, 0.5f, amt, rng));
      projectModel.setSongParamDelayFeedback(getTriangular(0.0f, 0.9f, 0.3f, amt, rng));
      projectModel.setDelayPingPong(rng.nextBoolean() ? 1 : 0);
      projectModel.setDelayAnalog(rng.nextBoolean() ? 1 : 0);
    }

    // 9. Filters (LPF, HPF)
    if (settings.filtersChecked) {
      double amt = settings.filtersAmount;
      if (model.getFilterMode() == org.deluge.model.FilterMode.OFF) {
        model.setFilterMode(org.deluge.model.FilterMode.LADDER_24);
      }
      model.setLpfFreq(getTriangular(600.0f, 20000.0f, 15000.0f, amt, rng));
      model.setLpfRes(getTriangular(0.0f, 0.92f, 0.0f, amt, rng));
      model.setLpfMorph(getTriangular(0.0f, 1.0f, 0.0f, amt, rng));

      if (model.getHpfMode() == org.deluge.model.FilterMode.OFF) {
        model.setHpfMode(org.deluge.model.FilterMode.LADDER_12);
      }
      model.setHpfFreq(
          getTriangular(20.0f, settings.hardcoreMode ? 6000.0f : 350.0f, 20.0f, amt, rng));
      model.setFilterRoute(rng.nextBoolean() ? 0 : 1);
    }

    // 10. EQ Settings (normalized bipolar -1..+1, matching UNPATCHED_BASS / UNPATCHED_TREBLE)
    if (settings.eqChecked) {
      double amt = settings.eqAmount;
      model.setEqBass(getTriangular(-1.0f, 1.0f, 0.0f, amt, rng));
      model.setEqTreble(getTriangular(-1.0f, 1.0f, 0.0f, amt, rng));
    }

    // 11. Compressor (C sync.h:22-35, MAX_SYNC_LEVEL = 9; mod_controllable_audio.cpp:903-906)
    if (settings.compressorChecked) {
      double amt = settings.compressorAmount;
      model.setCompressorThreshold(getTriangular(0.0f, 1.0f, 0.0f, amt, rng));
      model.setCompressorSyncLevel((int) getTriangular(0, 9, 0, amt, rng));
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
      model.getUnison().setUnisonNum(voiceCounts[rng.nextInt(voiceCounts.length)]);
      model.getUnison().setUnisonDetune(Math.round(getTriangular(0.0f, 20.0f, 0.0f, amt, rng)));
    }

    // ── Generate Dynamic Modulation Patch Cables (1 to 5 unique routings!) ──
    model.getModulation().getPatchCables().clear();
    int modCount = rng.nextInt(5) + 1;

    String[] sources = {"lfo1", "lfo2", "env2", "velocity", "aftertouch"};
    java.util.List<String> targetList =
        new java.util.ArrayList<>(java.util.List.of("pitch", "volume", "pan", "reverbAmount"));
    if (model.getFilterMode() != org.deluge.model.FilterMode.OFF) {
      targetList.add("lpfFrequency");
    }
    if (model.getHpfMode() != org.deluge.model.FilterMode.OFF) {
      targetList.add("hpfFrequency");
    }
    if (model.getDelayFeedbackQ31() > Integer.MIN_VALUE) {
      targetList.add("delayRate");
    }
    String[] targets = targetList.toArray(new String[0]);

    for (int m = 0; m < modCount; m++) {
      String src = sources[rng.nextInt(sources.length)];
      String dst = targets[rng.nextInt(targets.length)];
      float scale = getTriangular(-1.0f, 1.0f, 0.0f, 0.8, rng);
      if (Math.abs(scale) < 0.05f) {
        scale = scale >= 0.0f ? 0.15f : -0.15f;
      }
      if ("volume".equals(dst) || "lpfFrequency".equals(dst)) {
        scale = Math.max(-0.25f, scale);
      }
      if ("hpfFrequency".equals(dst)) {
        scale = Math.min(0.25f, scale);
      }

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
