package org.chuck.deluge.engine;

import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.firmware.engine.FirmwareFactory;
import org.chuck.deluge.firmware.engine.FirmwareSound;
import org.chuck.deluge.model.EnvelopeModel;
import org.chuck.deluge.model.FilterMode;
import org.chuck.deluge.model.LfoModel;
import org.chuck.deluge.model.LfoType;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;

/**
 * Generates rich, evolving ambient drone synth sounds, sequences 16-bar tied chord notes,
 * configures pure Just Intonation custom temperaments, and provides real-time macro sweeps.
 */
public class DroneLabGenerator {

  // Pure 5-limit Just Intonation cents adjustments for 12 notes (C-based)
  public static final int[] JUST_INTONATION_CENTS = {
    0, // C (unison)
    -12, // C# (minor second Db)
    4, // D (major second)
    16, // D# (minor third Eb)
    -14, // E (major third - pure!)
    -2, // F (perfect fourth)
    -16, // F# (tritone)
    2, // G (perfect fifth - pure!)
    -10, // G# (minor sixth Ab)
    -16, // A (major sixth)
    -12, // A# (minor seventh Bb)
    -12 // B (major seventh)
  };

  /**
   * Generates the Drone sound preset, configures Just Intonation, and sequences a long tied note.
   */
  public static void generateDrone(
      SynthTrackModel track,
      ProjectModel project,
      BridgeContract bridge,
      int trackIndex,
      boolean isFM,
      boolean isMinor) {

    if (track == null || project == null || bridge == null) return;

    // ── 1. Set Synthesizer Parameters ──
    track.setPolyphony(SynthTrackModel.PolyphonyMode.POLY);
    track.setMaxVoiceCount(8);
    track.setTranspose(0);

    if (!isFM) {
      // Subtractive Detuned Drone
      track.setSynthMode(0); // Subtractive
      track.setOsc1Type("SAW");
      track.setOscAVolume(0.5f);
      track.setOsc1Cents(0);

      track.setOsc2Type("SAW");
      track.setOsc2Transpose(12); // One octave up
      track.setOsc2Cents(16); // Organic detuning
      track.setOscBVolume(0.45f);
      track.setOscMix(0.5f);

      // Massive Unison spread
      track.setUnisonNum(4);
      track.setUnisonDetune(22);
      track.setUnisonStereoSpread(60);

      // Tape-hiss analog noise
      track.setNoiseVol(0.08f);

      // Dark low-pass filter
      track.setFilterMode(FilterMode.LADDER_24);
      track.setLpfFreq(0.25f);
      track.setLpfRes(0.35f);
      track.setFilterDrive(0.12f);

      // Blooming Volume Envelope
      track.setEnv(0, new EnvelopeModel(2.2f, 1.0f, 1.0f, 4.0f, "NONE", 0.0f));
      // Evolving Filter Envelope
      track.setEnv(1, new EnvelopeModel(4.5f, 1.0f, 0.5f, 5.0f, "FILTER", 0.35f));

    } else {
      // 2-Operator Metallic FM Drone
      track.setSynthMode(1); // FM

      // Operator ratios (Modulator 1 & Modulator 2)
      track.setFmRatio(1.414f); // Modulator 1 (square root of 2)
      track.setModulator1Transpose(0);
      track.setModulator1Cents(0);

      track.setFmRatio2(1.618f); // Modulator 2 (Golden Ratio)
      track.setModulator2Transpose(0);
      track.setModulator2Cents(0);

      // Unison
      track.setUnisonNum(2);
      track.setUnisonDetune(12);
      track.setUnisonStereoSpread(40);

      // Warm 12dB LPF
      track.setFilterMode(FilterMode.LADDER_12);
      track.setLpfFreq(0.35f);
      track.setLpfRes(0.25f);

      // Slow ADSR
      track.setEnv(0, new EnvelopeModel(2.5f, 1.0f, 1.0f, 3.5f, "NONE", 0.0f));
    }

    // ── 2. Drifting Modulations (Evolving LFOs) ──
    // LFO1 (Global 1): Slow SINE sweeping filter and pan
    track.setLfo(0, new LfoModel(0.03f, LfoType.SINE, 0.22f, "LPF", false, 0, 0));
    // LFO2 (Local 1): RANDOM WALK panning the sound slowly across the stereo field
    track.setLfo(1, new LfoModel(0.04f, LfoType.RANDOM_WALK, 0.35f, "PAN", false, 0, 0));
    // LFO3 (Global 2): RANDOM WALK doing subtle microtonal pitch drift
    track.setLfo(2, new LfoModel(0.05f, LfoType.RANDOM_WALK, 0.015f, "PITCH", true, 0, 0));

    // ── 3. Deep Cathedral Studio FX ──
    track.setReverbSend(0.6f);
    project.setReverbRoomSize(0.85f);
    project.setReverbDampening(0.3f);
    project.setReverbWidth(0.9f);

    track.setDelaySend(0.42f);
    project.setSongParamDelayRate(0.48f); // Free running delay time (~480ms)
    project.setSongParamDelayFeedback(0.65f);
    project.setDelayPingPong(1);
    project.setDelayAnalog(1);

    // ── 4. Apply Pure Just Intonation Temperament ──
    project.setIsEqualTemperament(false);
    int[] centsAdjust = project.getCentAdjustForNotesInTemperament();
    System.arraycopy(JUST_INTONATION_CENTS, 0, centsAdjust, 0, 12);

    // ── 5. Sequence Long Overlapping Note Ties ──
    // Clear sequencer steps for this track first
    for (int s = 0; s < BridgeContract.STEPS; s++) {
      bridge.setStep(trackIndex, s, false);
    }

    // Sequence a massive C2 root note spanning the entire step grid (192 steps)
    int rootPitch = 36; // C2

    // Since the sequencer is monophonic per track, we will sequence a rich C2 root note.
    // The detuning, octave transpose (+12) of Osc 2, and FM ratios will build the full chord
    // texture!
    bridge.setStep(trackIndex, 0, true);
    bridge.setPitch(trackIndex, 0, rootPitch);
    bridge.setGate(trackIndex, 0, 192.0); // Spans full sequence length (192 steps)
    bridge.setVelocity(trackIndex, 0, 0.85);

    // Trigger UI updates
    javax.swing.SwingUtilities.invokeLater(
        () -> {
          if (org.chuck.deluge.ui.SwingDelugeApp.mainInstance != null) {
            org.chuck.deluge.ui.SwingDelugeApp.mainInstance.fireProjectChanged();
          }
        });
  }

  /**
   * Sweeps real-time macro controls and synchronizes immediately to the active sound engine voice.
   */
  public static void applyMacros(
      SynthTrackModel track,
      FirmwareSound sound,
      double friction, // 0.0 to 1.0
      double turbulence, // 0.0 to 1.0
      double atmosphere, // 0.0 to 1.0
      double dirt // 0.0 to 1.0
      ) {
    if (track == null || sound == null) return;

    // 1. Friction (Tension & detuning dissonance)
    if (track.getSynthMode() == 0) {
      // Subtractive: Detune Osc 2 and add bitcrushing
      int cents = (int) (5.0 + friction * 45.0); // 5 to 50 cents detune (clamped limits)
      track.setOsc2Cents(cents);
      track.setBitCrush((float) (friction * 0.35)); // 0% to 35% decimation
    } else {
      // FM: Feedback & Modulator detuning
      track.setModulator1Cents((int) (friction * 45.0));
    }

    // 2. Turbulence (LFO Speeds & Depth)
    // Speed up slow LFOs and increase filter sweep depths
    float lfo1Speed = (float) (0.02 + turbulence * 0.18); // 0.02Hz to 0.20Hz
    LfoModel lfo1 = track.getLfo(0);
    if (lfo1 != null) {
      track.setLfo(
          0,
          new LfoModel(
              lfo1Speed, lfo1.waveform(), (float) (0.15 + turbulence * 0.25), "LPF", false, 0, 0));
    }

    // 3. Atmosphere (Delay & Reverb Space)
    track.setReverbSend((float) (0.15 + atmosphere * 0.65)); // 15% to 80% reverb
    track.setDelaySend((float) (0.10 + atmosphere * 0.50)); // 10% to 60% delay

    // 4. Dirt (Saturator & Noise Grit)
    track.setNoiseVol((float) (0.02 + dirt * 0.16)); // 2% to 18% noise volume
    track.setFilterDrive((float) (0.05 + dirt * 0.35)); // 5% to 40% filter overdrive

    // PUSH IMMEDIATELY to active shadow sound proxy and DSP engine!
    FirmwareFactory.applyModelToLiveSound(track, sound);
    sound.syncParamsToFw2();
  }

  /** Helper to retrieve the active track's FirmwareSound instance from the VM. */
  public static org.chuck.deluge.firmware.engine.FirmwareSound getActiveTrackSound(
      org.chuck.core.ChuckVM vm, int trackIndex) {
    if (vm == null) return null;
    Object ph = vm.getGlobalObject(BridgeContract.G_PLAYBACK_HANDLER);
    if (ph instanceof org.chuck.deluge.firmware.playback.PlaybackHandler playbackHandler) {
      org.chuck.deluge.firmware.model.Song song = playbackHandler.getSong();
      if (song != null && trackIndex >= 0 && trackIndex < song.clips.size()) {
        org.chuck.deluge.firmware.model.Clip clip = song.clips.get(trackIndex);
        if (clip instanceof org.chuck.deluge.firmware.model.InstrumentClip instrumentClip) {
          if (instrumentClip.sound instanceof org.chuck.deluge.firmware.engine.FirmwareSound fs) {
            return fs;
          }
        }
      }
    }
    return null;
  }
}
