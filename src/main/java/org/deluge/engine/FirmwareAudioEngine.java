package org.deluge.engine;

import java.util.List;
import java.util.logging.Logger;
import org.deluge.firmware2.Compressor;
import org.deluge.firmware2.Delay;
import org.deluge.firmware2.GlobalEffectable;
import org.deluge.firmware2.GlobalSidechainBus;
import org.deluge.firmware2.LiveInputBuffer;
import org.deluge.firmware2.LivePitchShifter;
import org.deluge.firmware2.Metronome;
import org.deluge.firmware2.Reverb;
import org.deluge.firmware2.StereoSample;
import org.deluge.model.ProjectModel;

/**
 * Port of the Deluge's AudioEngine class. Performs master summing and global FX.
 *
 * <p>The master FX bus (reverb / delay / compressor) is the faithful firmware2 port. The fw2 FX
 * operate on {@code int[][]} stereo frames, so the chain runs on an {@code int[][]} scratch ({@link
 * #fxBuffer}) while the public {@link #masterBuffer} stays {@code StereoSample[]} for the
 * sound-render and driver sides. fw2 corrects real bugs the old firmware/ FX had (reverb cross-feed
 * / 2x scale, compressor float-buffer precision), so the master tone changes — that's
 * faithful-to-C.
 */
public class FirmwareAudioEngine {
  private static final Logger LOGGER = Logger.getLogger(FirmwareAudioEngine.class.getName());
  public static boolean debugTelemetry = false;

  // ── CPU direness (audio_engine.cpp) — adaptive resampling-quality fallback ──
  // C: AudioEngine::cpuDireness (audio_engine.cpp:161), range 0..14. Raised when the DSP overruns
  // its real-time budget, decayed (with hysteresis) when it recovers. SampleControls.
  // getInterpolationBufferSize reads it to drop to 2-tap linear interpolation on pitched-up samples
  // under load (sample_controls.cpp:35-44); at 0 the sample engine always uses full 24-tap sinc.
  // Stays 0 for offline export/tests (those never call updateDireness), so export stays sinc.
  public static volatile int cpuDireness = 0;

  // C: audio_engine.cpp:122-124 — numSamplesLimit=80, direnessThreshold = numSamplesLimit-30 = 50.
  private static final int DIRENESS_THRESHOLD = 50;
  private static final int MAX_DIRENESS = 14; // C: std::min<int32_t>(..., 14)
  private static final int DIRENESS_DECAY_INTERVAL = 44100 >> 3; // C: kSampleRate >> 3
  private static long timeDirenessChanged = 0; // C: timeDirenessChanged (in audioSampleTimer units)

  /**
   * Faithful-spirit port of AudioEngine::setDireness (audio_engine.cpp:472-515). The hardware reads
   * the audio routine's average run-time from its task scheduler; on desktop we feed in the
   * measured wall-clock render time of the block that just completed. {@code overrun} is how many
   * sample-periods of wall time the render took beyond the audio it produced (C: {@code dspTime -
   * numRoutines*numSamples}, with numRoutines == 1 here). The threshold (50), ceiling (14), and
   * decay hysteresis (kSampleRate>>3) match the C exactly. Voice culling (the C's other branch) is
   * out of scope — desktop has the headroom; this only governs interpolation quality.
   */
  public static void updateDireness(long renderNanos, int blockSamples, long audioSampleTimer) {
    int dspTime = (int) (renderNanos * 44100.0 / 1_000_000_000.0); // C: avgRunTime * 44100.
    int overrun =
        Math.max(dspTime - blockSamples, 0); // C: max(dspTime - numRoutines*numSamples, 0)
    if (overrun >= DIRENESS_THRESHOLD) {
      int newDireness = Math.min(overrun - (DIRENESS_THRESHOLD - 1), MAX_DIRENESS);
      if (newDireness >= cpuDireness) {
        cpuDireness = newDireness;
        timeDirenessChanged = audioSampleTimer;
      }
    } else if (overrun < DIRENESS_THRESHOLD - 3) { // C: hysteresis band to avoid jittering
      if (audioSampleTimer - timeDirenessChanged >= DIRENESS_DECAY_INTERVAL) {
        timeDirenessChanged = audioSampleTimer;
        cpuDireness--;
        if (cpuDireness < 0) {
          cpuDireness = 0;
        }
      }
    }
  }

  public final List<GlobalEffectable> sounds = new java.util.concurrent.CopyOnWriteArrayList<>();
  public final StereoSample[] masterBuffer = new StereoSample[128];
  private int[] summedFlatBuffer = new int[256];

  // Global FX — faithful firmware2 ports.
  public final Compressor masterCompressor = new Compressor();

  {
    // C song.cpp:191 — the song's master compressor uses 0.85, not the constructor's 1.35
    // ("The song compressor must use 0.8 to maintain compatibility", rms_feedback.h:163-167).
    masterCompressor.setBaseGain(0.85f);
  }

  // ── Master Song-level FX (post-summation stage, ported from C++ Song / AudioEngine) ──
  public final org.deluge.firmware2.ModFx masterModFx = new org.deluge.firmware2.ModFx();
  public final org.deluge.firmware2.Eq masterEq = new org.deluge.firmware2.Eq();
  public final org.deluge.firmware2.SrrBitcrush masterSrrBitcrush =
      new org.deluge.firmware2.SrrBitcrush();
  public final org.deluge.firmware2.FilterSet masterFilterSet =
      new org.deluge.firmware2.FilterSet();
  public final org.deluge.firmware2.Stutterer masterStutterer =
      new org.deluge.firmware2.Stutterer();
  private int[] flatFilterBuffer = new int[256];

  public org.deluge.firmware2.ModFx.ModFXType masterModFxType =
      org.deluge.firmware2.ModFx.ModFXType.NONE;
  public String masterModFxCurrentParam = "offset";
  public int masterModFxRate = 0;
  public int masterModFxDepth = 0;
  public int masterModFxOffset = 0;
  public int masterModFxFeedback = 0;
  public int masterEqBass = 0;
  public int masterEqTreble = 0;
  public int masterEqBassFreq = 0;
  public int masterEqTrebleFreq = 0;
  public int masterSrr = Integer.MIN_VALUE;
  public int masterBitcrush = Integer.MIN_VALUE;
  public int masterStutterRate = 0;
  public int masterLpfFreq = 2147483647;
  public int masterLpfRes = 0;
  public int masterLpfMorph = 0;
  public int masterHpfFreq = 0;
  public int masterHpfRes = 0;
  public int masterHpfMorph = 0;
  public int songReverbAmount = 0x20000000;
  public int songPan = 0;
  public int songClippingAmount = 0;
  private int masterFilterGain = MASTER_VOLUME_NEUTRAL;

  public final Delay masterDelay = new Delay();
  public final Reverb.Container masterReverb = new Reverb.Container();
  public final Delay.State delayState = new Delay.State();
  // C: audio_engine.cpp:137,1366 — signed Q31-range pan for the reverb send (negative=left,
  // positive=right); synced from the song in syncMasterEffects.
  private int reverbPan = 0;

  // C: audio_engine.cpp — the global reverb's own sidechain ("reverb compressor") + the
  // in-effect params resolved by updateReverbParams (auto mode).
  public final org.deluge.firmware2.Sidechain reverbSidechain =
      new org.deluge.firmware2.Sidechain();
  public int reverbSidechainVolumeInEffect = 0;
  public int reverbSidechainShapeInEffect = 0;

  /**
   * C audio_engine.cpp:1477-1546 updateReverbParams, "auto" mode (the C's default,
   * reverbSidechainVolume &lt; 0): find the sound with the most reverb send across both Synth
   * tracks and Kit drum rows (kit.cpp:1397-1417, sound.cpp:2247-2261); if it has a
   * SIDECHAIN→GLOBAL_VOLUME_POST_REVERB_SEND cable, the reverb ducks with that cable's modified
   * amount and borrows the sound's sidechain shape/attack/release/syncLevel/syncType. No qualifying
   * sound → no ducking.
   */
  private void updateReverbParams() {
    FirmwareSound best = null;
    int highestReverbAmountFound = this.songReverbAmount; // C seeds with the song's own send amount
    for (int i = 0; i < sounds.size(); i++) {
      GlobalEffectable ge = sounds.get(i);
      if (ge instanceof FirmwareSound snd) {
        int amount = getUnautomatedReverbAmount(snd);
        if (amount != Integer.MIN_VALUE && amount > highestReverbAmountFound) {
          highestReverbAmountFound = amount;
          best = snd;
        }
      } else if (ge instanceof FirmwareKit kit) {
        // C kit.cpp:1406-1416 — inspect every SoundDrum in the Kit.
        for (int d = 0; d < kit.drumSounds.size(); d++) {
          FirmwareSound drum = kit.drumSounds.get(d);
          int amount = getUnautomatedReverbAmount(drum);
          if (amount != Integer.MIN_VALUE && amount > highestReverbAmountFound) {
            highestReverbAmountFound = amount;
            best = drum;
          }
        }
      }
    }
    if (best == null) {
      reverbSidechainVolumeInEffect = 0;
      return;
    }
    if (best.fw2Sound.patchCableSet.destinations.isEmpty()
        && !best.paramManager.getPatchCableSet().destinations.isEmpty()) {
      best.syncParamsToFw2();
    }
    int vol = 0; // C:1516-1518 — no SIDECHAIN cable on the send → no ducking
    java.util.List<org.deluge.firmware2.Patcher.Destination> dests =
        best.fw2Sound.patchCableSet.destinations;
    for (int dIdx = 0; dIdx < dests.size(); dIdx++) {
      org.deluge.firmware2.Patcher.Destination dest = dests.get(dIdx);
      if (dest.paramId == org.deluge.firmware2.Param.GLOBAL_VOLUME_POST_REVERB_SEND) {
        java.util.List<org.deluge.firmware2.Patcher.PatchCable> cables = dest.cables;
        for (int cIdx = 0; cIdx < cables.size(); cIdx++) {
          org.deluge.firmware2.Patcher.PatchCable cable = cables.get(cIdx);
          if (cable.source == org.deluge.firmware2.PatchSource.SIDECHAIN.ordinal()) {
            vol =
                org.deluge.firmware2.Patcher.getModifiedPatchCableAmount(
                    cable, org.deluge.firmware2.Param.GLOBAL_VOLUME_POST_REVERB_SEND);
            break;
          }
        }
      }
    }
    reverbSidechainVolumeInEffect = vol;
    reverbSidechainShapeInEffect =
        best.fw2Sound.patchedParamValues[org.deluge.firmware2.Param.UNPATCHED_SIDECHAIN_SHAPE];
    reverbSidechain.attack = best.sidechain.attack; // C:1530-1532
    reverbSidechain.release = best.sidechain.release;
    reverbSidechain.syncLevel = best.sidechain.syncLevel;
    reverbSidechain.syncType = best.sidechain.syncType;
    reverbSidechain.timePerInternalTickInverse = best.sidechain.timePerInternalTickInverse;
  }

  /**
   * C sound.cpp:2251-2257 — only non-automated GLOBAL_REVERB_AMOUNT above Integer.MIN_VALUE
   * competes in getThingWithMostReverb.
   */
  private static int getUnautomatedReverbAmount(FirmwareSound snd) {
    var auto = snd.paramManager.getAutomatedParam(org.deluge.firmware2.Param.GLOBAL_REVERB_AMOUNT);
    if (auto != null && !auto.nodes.isEmpty()) {
      return Integer.MIN_VALUE;
    }
    return snd.paramKnobsPopulated
        ? snd.paramKnobs[org.deluge.firmware2.Param.GLOBAL_REVERB_AMOUNT]
        : snd.paramNeutralValues[org.deluge.firmware2.Param.GLOBAL_REVERB_AMOUNT];
  }

  /** int[][] stereo scratch for the fw2 FX chain (fw2 FX read/write [l, r] per sample). */
  private final int[][] fxBuffer = new int[128][2];

  private int[] monoReverbBuffer = new int[128];

  // Live input pitch shifter (monitoring). inputBuffer holds the ring; pitchShifter renders it.
  public final LiveInputBuffer liveInputBuffer = new LiveInputBuffer();
  public final LivePitchShifter livePitchShifter =
      new LivePitchShifter(LiveInputBuffer.InputType.STEREO, 16777216);
  private int liveAudioSampleTimer;

  // Reused scratch for renderLiveInput's pitch-shifter output (grow-only), so the live-input/
  // resample callback path doesn't allocate a fresh int[] every audio block.
  private int[] liveInputOut = new int[256];

  // ── High-Fidelity Gain Constants ──
  // C: audio_engine.cpp:861 — masterVolumeAdjustment starts at getParamNeutralValue(GLOBAL_VOLUME_
  // POST_FX) = 167763968 (~1.25·2^27), NOT full-scale. This is the master-volume the compressor
  // applies (render volAdjust); using Q31.ONE here drove the compressor ~13× too hot into its
  // saturation and broke hardware shape parity.
  public static final int MASTER_VOLUME_NEUTRAL = 167763968;
  public int masterVolumeAdjustmentL = MASTER_VOLUME_NEUTRAL;
  public int masterVolumeAdjustmentR = MASTER_VOLUME_NEUTRAL;
  public int songVolume = 0; // bipolar Q31 master volume

  // Metronome (faithful fw2 port). Off by default; the transport triggers a click each beat when
  // enabled (see JavaAudioDriver). C: audio_engine.cpp:626 renders it into the master buffer.
  public final Metronome metronome = new Metronome();
  public volatile boolean metronomeEnabled = false;

  /** Trigger a metronome click. {@code phaseIncrement} sets the click pitch (downbeat vs beat). */
  public void triggerMetronome(int phaseIncrement) {
    if (metronomeEnabled) {
      metronome.trigger(phaseIncrement);
    }
  }

  public FirmwareAudioEngine() {
    GlobalSidechainBus.reset();
    for (int i = 0; i < masterBuffer.length; i++) {
      masterBuffer[i] = new StereoSample();
    }
    delayState.doDelay = false;
    // Drive delay time externally via userDelayRate; disable the delay's internal tempo-sync (which
    // would otherwise rewrite userDelayRate using a tick-inverse the pure engine doesn't feed it).
    // fw2 Delay.syncLevel is an int; SYNC_LEVEL_NONE == 0.
    masterDelay.syncLevel = 0;
    masterVolumeAdjustmentL = MASTER_VOLUME_NEUTRAL;
    masterVolumeAdjustmentR = MASTER_VOLUME_NEUTRAL;
  }

  public void syncMasterEffects(ProjectModel project) {
    double bpm = project.getBpm();
    int syncLevel = project.getDelaySyncLevel();
    double delaySec;
    if (syncLevel > 0) {
      double stepSec = (bpm > 0 ? 60.0 / bpm : 0.5) / 4.0; // 16th-note step
      double syncFactor = Math.pow(2.0, syncLevel - 1);
      if (project.getDelaySyncType() == 1) {
        syncFactor *= 1.5; // triplet
      }
      delaySec = syncFactor * stepSec;
    } else {
      delaySec = project.getSongParamDelayRate();
    }
    delaySec = Math.max(0.001, Math.min(2.0, delaySec));
    long rate = (long) (16384L * 16777216L / (delaySec * 44100.0));
    this.delayState.userDelayRate = (int) Math.min(rate, Integer.MAX_VALUE);

    double fb = project.getSongParamDelayFeedback();
    fb = Math.max(0.0, Math.min(1.0, fb));
    this.delayState.delayFeedbackAmount =
        Math.min((int) (fb * 2147483647.0), (1 << 30) - (1 << 26));

    this.masterDelay.pingPong = project.getDelayPingPong() != 0;
    this.masterDelay.analog = project.getDelayAnalog() != 0;

    // Sync Reverb
    // C reverb.hpp:15-19 — enum order is FREEVERB=0, MUTABLE=1, DIGITAL=2 (song.cpp:1423 casts
    // the raw XML int straight to this enum); this was previously never synced, so masterReverb
    // stayed on its Java field default (FREEVERB) regardless of the song's actual model.
    this.masterReverb.setModel(
        switch (project.getReverbModel()) {
          case 1 -> org.deluge.firmware2.Reverb.Model.MUTABLE;
          case 2 -> org.deluge.firmware2.Reverb.Model.DIGITAL;
          default -> org.deluge.firmware2.Reverb.Model.FREEVERB;
        });
    this.masterReverb.setRoomSize(project.getReverbRoomSize());
    this.masterReverb.setDamping(project.getReverbDampening());
    this.masterReverb.setWidth(project.getReverbWidth());
    this.masterReverb.setHPF(project.getReverbHpf());
    // C audio_engine.cpp:1366 (reverbPan = song->reverbPan) — previously parsed from XML but never
    // read anywhere in the engine; the render loop always called setPanLevels symmetrically.
    this.reverbPan = (int) (project.getReverbPan() * 2147483647.0);

    // Sync Master Volume
    float volFloat = project.getSongParamVolume();
    float bipolar = (volFloat * 2.0f) - 1.0f;
    this.songVolume = (int) (bipolar * 2147483647.0f);

    // Sync Song-level Master FX params (analogous to C++ Song / AudioEngine::renderSongFX)
    this.masterModFxType = toFw2ModFxType(project.getSongModFXType());
    this.masterModFxCurrentParam = project.getModFXCurrentParam();
    this.songClippingAmount = project.getSongClippingAmount();
    this.songPan = (int) (project.getSongParamPan() * 2147483647.0f);
    this.masterModFxRate = unipolarToQ31Param(project.getSongParamModFXRate());
    this.masterModFxDepth = unipolarToQ31Param(project.getSongParamModFXDepth());
    this.masterModFxOffset = unipolarToQ31Param(project.getSongParamModFXOffset());
    this.masterModFxFeedback = unipolarToQ31Param(project.getSongParamModFXFeedback());

    this.masterEqBass = (int) (project.getSongParamEqBass() * 2147483647.0f);
    this.masterEqTreble = (int) (project.getSongParamEqTreble() * 2147483647.0f);
    // C mod_controllable_audio.cpp:110-111, 182, 186 — bassFrequency / trebleFrequency are unipolar
    // Q31 knobs (0.5f == 0x00000000 default), NOT Hz on 20..20000.
    this.masterEqBassFreq = unipolarToQ31Param(project.getSongParamEqBassFrequency());
    this.masterEqTrebleFreq = unipolarToQ31Param(project.getSongParamEqTrebleFrequency());

    // SRR and bitcrush are OFF at the q31 MINIMUM (0x80000000), not at zero — and their enable
    // predicates key on exactly that (SrrBitcrush.isSRREnabled: `!= 0x80000000`;
    // isBitcrushingEnabled: `>= -2113929216`, i.e. anything above the bottom eighth). The model
    // stores these unipolar, 0.0 meaning "no reduction" (SongXmlParser.readSongHexAttr maps the
    // file's 0x80000000 through toUnipolar to 0.0f). Multiplying that by 2147483647 produced q31
    // ZERO — which is the MIDDLE of the range, i.e. ~50% — so both effects were permanently ON for
    // every render that called this method, including every FidelityScorecardTest render. The
    // symptom was a master bus that quantised the signal to ~2^23 and buried quiet material in
    // quantisation noise. Map the unipolar value across the FULL q31 span instead.
    this.masterSrr = unipolarToQ31Param(project.getSongParamSampleRateReduction());
    this.masterBitcrush = unipolarToQ31Param(project.getSongParamBitCrush());
    this.masterStutterRate = unipolarToQ31Param(project.getSongParamStutterRate());
    this.songReverbAmount = (int) (project.getSongParamReverbAmount() * 2.0f * 536870912.0f);

    configureMasterFilterSet(
        project.getSongParamLpfFrequency(),
        project.getSongParamLpfResonance(),
        project.getSongParamLpfMorph(),
        project.getSongLpfMode(),
        project.getSongParamHpfFrequency(),
        project.getSongParamHpfResonance(),
        project.getSongParamHpfMorph(),
        project.getSongHpfMode(),
        project.getSongFilterRoute());
  }

  /**
   * C global_effectable.cpp:752-785, mod_controllable_audio.cpp:61-63 — configures the song-level
   * {@link #masterFilterSet} from the song's unpatched filter knobs, modes, and routing.
   */
  public void configureMasterFilterSet(
      float lpfFreqHz,
      float lpfResUnipolar,
      float lpfMorphUnipolar,
      org.deluge.model.FilterMode songLpfModelMode,
      float hpfFreqHz,
      float hpfResUnipolar,
      float hpfMorphUnipolar,
      org.deluge.model.FilterMode songHpfModelMode,
      int songFilterRoute) {
    int lpfKnob = cutoffKnobFromHz(lpfFreqHz);
    int lpfResKnob = unipolarToQ31Param(lpfResUnipolar);
    int lpfMorphKnob = unipolarToQ31Param(lpfMorphUnipolar);
    int hpfKnob = cutoffKnobFromHz(hpfFreqHz);
    int hpfResKnob = unipolarToQ31Param(hpfResUnipolar);
    int hpfMorphKnob = unipolarToQ31Param(hpfMorphUnipolar);

    this.masterLpfFreq =
        org.deluge.firmware2.Functions.getFinalParameterValueExp(
            org.deluge.firmware2.Functions.getParamNeutralValue(
                org.deluge.firmware2.Param.LOCAL_LPF_FREQ),
            org.deluge.firmware2.Functions.cableToExpParamShortcut(lpfKnob));
    this.masterLpfRes =
        org.deluge.firmware2.Functions.getFinalParameterValueLinear(
            org.deluge.firmware2.Functions.getParamNeutralValue(
                org.deluge.firmware2.Param.LOCAL_LPF_RESONANCE),
            org.deluge.firmware2.Functions.cableToLinearParamShortcut(lpfResKnob));
    this.masterLpfMorph =
        org.deluge.firmware2.Functions.getFinalParameterValueLinear(
            org.deluge.firmware2.Functions.getParamNeutralValue(
                org.deluge.firmware2.Param.LOCAL_LPF_MORPH),
            org.deluge.firmware2.Functions.cableToExpParamShortcut(lpfMorphKnob));
    this.masterHpfFreq =
        org.deluge.firmware2.Functions.getFinalParameterValueExp(
            org.deluge.firmware2.Functions.getParamNeutralValue(
                org.deluge.firmware2.Param.LOCAL_HPF_FREQ),
            org.deluge.firmware2.Functions.cableToExpParamShortcut(hpfKnob));
    this.masterHpfRes =
        org.deluge.firmware2.Functions.getFinalParameterValueLinear(
            org.deluge.firmware2.Functions.getParamNeutralValue(
                org.deluge.firmware2.Param.LOCAL_HPF_RESONANCE),
            org.deluge.firmware2.Functions.cableToLinearParamShortcut(hpfResKnob));
    this.masterHpfMorph =
        org.deluge.firmware2.Functions.getFinalParameterValueLinear(
            org.deluge.firmware2.Functions.getParamNeutralValue(
                org.deluge.firmware2.Param.LOCAL_HPF_MORPH),
            org.deluge.firmware2.Functions.cableToExpParamShortcut(hpfMorphKnob));

    org.deluge.firmware2.FilterSet.FilterMode songLpfMode = toFw2SongLpfMode(songLpfModelMode);
    org.deluge.firmware2.FilterSet.FilterMode songHpfMode = toFw2SongHpfMode(songHpfModelMode);
    boolean doLPF =
        songLpfMode != org.deluge.firmware2.FilterSet.FilterMode.OFF
            && (songLpfMode == org.deluge.firmware2.FilterSet.FilterMode.TRANSISTOR_24DB_DRIVE
                || lpfKnob < 2147483602
                || lpfMorphKnob > Integer.MIN_VALUE);
    boolean doHPF =
        songHpfMode != org.deluge.firmware2.FilterSet.FilterMode.OFF
            && (hpfKnob > Integer.MIN_VALUE || hpfMorphKnob > Integer.MIN_VALUE);
    org.deluge.firmware2.FilterSet.FilterMode lpfModeVal =
        doLPF ? songLpfMode : org.deluge.firmware2.FilterSet.FilterMode.OFF;
    org.deluge.firmware2.FilterSet.FilterMode hpfModeVal =
        doHPF ? songHpfMode : org.deluge.firmware2.FilterSet.FilterMode.OFF;
    // C audio_engine.cpp:1082-1087, global_effectable.cpp:782-784 — pass MASTER_VOLUME_NEUTRAL
    // (167763968) and keep the filter's resonance/drive-compensated gain.
    this.masterFilterGain =
        this.masterFilterSet.setConfig(
            this.masterLpfFreq,
            this.masterLpfRes,
            lpfModeVal,
            this.masterLpfMorph,
            this.masterHpfFreq,
            this.masterHpfRes,
            hpfModeVal,
            this.masterHpfMorph,
            MASTER_VOLUME_NEUTRAL,
            toFw2FilterRoute(songFilterRoute));
  }

  /**
   * Inverse of {@link org.deluge.xml.DelugeHexMapper#hexToHz(String)}: maps a 20..20000 Hz cutoff
   * back to the signed Q31 unpatched knob value ({@code 0x80000000} at 20 Hz, {@code 0x7FFFFFFF} at
   * 20000 Hz).
   */
  public static int cutoffKnobFromHz(float hz) {
    if (hz <= 20.0f) {
      return Integer.MIN_VALUE;
    }
    if (hz >= 20000.0f) {
      return Integer.MAX_VALUE;
    }
    double exponent = Math.log(hz / 20.0) / Math.log(1000.0);
    double norm = exponent * 2.0 - 1.0;
    return (int) Math.round(Math.max(-1.0, Math.min(1.0, norm)) * (double) Integer.MAX_VALUE);
  }

  /** C functions.cpp:903-928 (stringToFXType) + Java enum aliases. */
  public static org.deluge.firmware2.ModFx.ModFXType toFw2ModFxType(String s) {
    if (s == null || s.isBlank()) {
      return org.deluge.firmware2.ModFx.ModFXType.NONE;
    }
    return switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "flanger" -> org.deluge.firmware2.ModFx.ModFXType.FLANGER;
      case "tapewarble", "warble" -> org.deluge.firmware2.ModFx.ModFXType.WARBLE;
      case "dimension" -> org.deluge.firmware2.ModFx.ModFXType.DIMENSION;
      case "chorus" -> org.deluge.firmware2.ModFx.ModFXType.CHORUS;
      case "stereochorus", "chorus_stereo" -> org.deluge.firmware2.ModFx.ModFXType.CHORUS_STEREO;
      case "grainfx", "grain" -> org.deluge.firmware2.ModFx.ModFXType.GRAIN;
      case "phaser" -> org.deluge.firmware2.ModFx.ModFXType.PHASER;
      default -> org.deluge.firmware2.ModFx.ModFXType.NONE;
    };
  }

  /** C global_effectable.cpp:1107-1120 (GlobalEffectable::getActiveModFXType). */
  public org.deluge.firmware2.ModFx.ModFXType getActiveModFXType() {
    org.deluge.firmware2.ModFx.ModFXType modFXTypeNow = masterModFxType;
    String param =
        masterModFxCurrentParam != null
            ? masterModFxCurrentParam.trim().toLowerCase(java.util.Locale.ROOT)
            : "";
    if (("depth".equals(param) && masterModFxDepth == Integer.MIN_VALUE)
        || ("feedback".equals(param) && masterModFxFeedback == Integer.MIN_VALUE)
        || ("offset".equals(param) && masterModFxOffset == Integer.MIN_VALUE)) {
      modFXTypeNow = org.deluge.firmware2.ModFx.ModFXType.NONE;
    }
    return modFXTypeNow;
  }

  private static org.deluge.firmware2.FilterSet.FilterMode toFw2SongLpfMode(
      org.deluge.model.FilterMode mode) {
    if (mode == null) {
      return org.deluge.firmware2.FilterSet.FilterMode.TRANSISTOR_24DB;
    }
    return switch (mode) {
      case LADDER_12 -> org.deluge.firmware2.FilterSet.FilterMode.TRANSISTOR_12DB;
      case LADDER_24 -> org.deluge.firmware2.FilterSet.FilterMode.TRANSISTOR_24DB;
      case DRIVE -> org.deluge.firmware2.FilterSet.FilterMode.TRANSISTOR_24DB_DRIVE;
      case SVF_NOTCH -> org.deluge.firmware2.FilterSet.FilterMode.SVF_NOTCH;
      case SVF, SVF_BAND -> org.deluge.firmware2.FilterSet.FilterMode.SVF_BAND;
      case OFF -> org.deluge.firmware2.FilterSet.FilterMode.OFF;
    };
  }

  private static org.deluge.firmware2.FilterSet.FilterMode toFw2SongHpfMode(
      org.deluge.model.FilterMode mode) {
    if (mode == null) {
      return org.deluge.firmware2.FilterSet.FilterMode.HPLADDER;
    }
    return switch (mode) {
      case SVF_NOTCH -> org.deluge.firmware2.FilterSet.FilterMode.SVF_NOTCH;
      case SVF, SVF_BAND -> org.deluge.firmware2.FilterSet.FilterMode.SVF_BAND;
      case OFF -> org.deluge.firmware2.FilterSet.FilterMode.OFF;
      default -> org.deluge.firmware2.FilterSet.FilterMode.HPLADDER;
    };
  }

  private static org.deluge.firmware2.FilterRoute toFw2FilterRoute(int route) {
    return switch (route) {
      case 1 -> org.deluge.firmware2.FilterRoute.LOW_TO_HIGH;
      case 2 -> org.deluge.firmware2.FilterRoute.PARALLEL;
      default -> org.deluge.firmware2.FilterRoute.HIGH_TO_LOW;
    };
  }

  /**
   * Maps a unipolar 0..1 model value across the firmware's full-range signed Q31 unpatched param
   * span, where the minimum ({@code 0x80000000}) is "off" and zero ({@code 0x00000000}) is the
   * midpoint.
   */
  public static int unipolarToQ31Param(float v) {
    // eqBass/eqTreble and pan are read BIPOLAR (SongXmlParser passes unipolar=false), so their
    // `v * 2147483647` conversions are correct and are deliberately left alone.
    double d = Math.max(0.0f, Math.min(1.0f, v)) * 4294967295.0 - 2147483648.0;
    return (int) Math.max(-2147483648.0, Math.min(2147483647.0, d));
  }

  private long[] summedFlatBufferLong = new long[256];
  private long[] monoReverbBufferLong = new long[128];

  // Transport play state, for audio-track gating (Phase 3). The driver sets this each block from
  // the PlaybackHandler; offline renderers/tests set it true when they want audio clips to stream.
  private volatile boolean transportPlaying = false;
  private volatile long transportTick = 0;

  public void setTransportPlaying(boolean p) {
    this.transportPlaying = p;
  }

  /** Current transport tick (for audio-track arrangement-placement gating, Phase 3b part 2). */
  public void setTransportTick(long tick) {
    this.transportTick = tick;
  }

  public void renderBlock(int numSamples) {
    GlobalSidechainBus.beginAudioFrame();
    // Reset buffers
    if (monoReverbBuffer.length < numSamples) {
      monoReverbBuffer = new int[numSamples];
    }
    for (int i = 0; i < numSamples; i++) {
      masterBuffer[i].l = 0;
      masterBuffer[i].r = 0;
      monoReverbBuffer[i] = 0;
    }

    int requiredLen = numSamples * 2;
    if (summedFlatBufferLong.length < requiredLen) {
      summedFlatBufferLong = new long[requiredLen];
    }
    java.util.Arrays.fill(summedFlatBufferLong, 0, requiredLen, 0L);

    if (monoReverbBufferLong.length < numSamples) {
      monoReverbBufferLong = new long[numSamples];
    }
    java.util.Arrays.fill(monoReverbBufferLong, 0, numSamples, 0L);

    // Audio-track gating (Phase 3): each block, update audio clips with the transport state + tick
    // so they stream only while playing (3a) and only within their arrangement range (3b part 2),
    // restarting phase-aligned on entry.
    for (int i = 0; i < sounds.size(); i++) {
      GlobalEffectable sound = sounds.get(i);
      if (sound instanceof org.deluge.firmware2.AudioOutput ao) {
        ao.updateTimeline(transportTick, transportPlaying);
      }
    }

    // Sum all tracks in 64-bit (infinite headroom!)
    for (int i = 0; i < sounds.size(); i++) {
      GlobalEffectable sound = sounds.get(i);
      if (sound != null) {
        sound.renderOutput(summedFlatBufferLong, numSamples, monoReverbBufferLong);
      }
    }

    // Apply master volume adjustment in 64-bit to prevent summing-bus saturation!
    for (int i = 0; i < numSamples; i++) {
      long lVal = summedFlatBufferLong[i * 2];
      long rVal = summedFlatBufferLong[i * 2 + 1];
      lVal = (lVal * masterVolumeAdjustmentL) >> 27;
      rVal = (rVal * masterVolumeAdjustmentR) >> 27;
      fxBuffer[i][0] = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, lVal));
      fxBuffer[i][1] = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, rVal));

      long revVal = monoReverbBufferLong[i];
      monoReverbBuffer[i] = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, revVal));
    }

    // ── Song-level ModFX and EQ (post-summation, pre-reverb/delay; C song.cpp:2397) ──
    renderSongPreReverbFX(numSamples);

    // C: audio_engine.cpp:806-844 — the reverb's own sidechain ("reverb compressor") ducks the
    // wet level. In the C's default "auto" mode, updateReverbParams borrows the sidechain
    // volume/shape/attack/release from the sound with the most reverb send that has a
    // SIDECHAIN→POST_REVERB_SEND cable. The resulting output level must be set as the reverb's
    // pan levels BEFORE process(), or every model multiplies its wet by a 0 pan amplitude and
    // emits silence.
    updateReverbParams();
    int sidechainOutput = 0;
    if (reverbSidechainVolumeInEffect != 0) { // C:812-819
      int hit = GlobalSidechainBus.getActiveFrameHit();
      if (hit != 0) {
        reverbSidechain.registerHit(hit);
      }
      sidechainOutput = reverbSidechain.render(numSamples, reverbSidechainShapeInEffect);
    }
    // C:829-833 — patch the sidechain into the reverb volume.
    int positivePatchedValue =
        org.deluge.firmware2.Functions.multiply_32x32_rshift32(
                sidechainOutput, reverbSidechainVolumeInEffect)
            + 0x20000000;
    int reverbOutputVolume = (positivePatchedValue >> 15) * (positivePatchedValue >> 14); // C:833
    // C:840-847 — reverbPan (previously ignored; see syncMasterEffects) skews the L/R send instead
    // of always splitting it evenly. renderInStereo is unconditionally true in this offline/desktop
    // engine, so the C's "renderInStereo &&" guard is always satisfied here.
    int[] reverbAmplitudeLR = new int[2];
    if (org.deluge.firmware2.Functions.shouldDoPanning(reverbPan, reverbAmplitudeLR)) {
      int ampL =
          org.deluge.firmware2.Functions.multiply_32x32_rshift32(
                  reverbAmplitudeLR[0], reverbOutputVolume)
              << 2;
      int ampR =
          org.deluge.firmware2.Functions.multiply_32x32_rshift32(
                  reverbAmplitudeLR[1], reverbOutputVolume)
              << 2;
      masterReverb.setPanLevels(ampL, ampR);
    } else {
      masterReverb.setPanLevels(reverbOutputVolume, reverbOutputVolume); // C:836/847
    }

    // fw2 reverb ACCUMULATES the wet signal onto the [l, r] frames, so it adds reverb on top of the
    // dry already in fxBuffer (matching the old firmware/ reverb's += into masterBuffer).
    masterReverb.process(monoReverbBuffer, fxBuffer);

    // setupWorkingState computes doDelay (from feedback) and must run before process or the delay
    // never activates. The pure engine drives delay time externally via userDelayRate with the
    // delay's own sync disabled (syncLevel 0), so timePerInternalTickInverse is unused here.
    masterDelay.setupWorkingState(delayState, 1 << 20, true);
    masterDelay.process(fxBuffer, numSamples, delayState);

    // ── Song-level FilterSet and SRR/Bitcrush (post-reverb/delay; C audio_engine.cpp:1082-1095) ──
    int postFXVol = renderSongPostReverbFX(numSamples);

    // Metronome click — added dry, before the master compressor (C: audio_engine.cpp:626).
    if (metronomeEnabled) {
      metronome.render(fxBuffer, numSamples, org.deluge.firmware2.Functions.ONE_Q31);
    }

    // ── Master output stage — faithful port of audio_engine.cpp:1098-1121. ──
    // The compressor applies the master volume (masterVolumeAdjustment >> 1), the RMS compression,
    // AND the output saturation (rms_feedback.cpp:106 getTanHAntialiased). songVolume sets the
    // compressor's makeup/threshold. The C has NO separate engine tanh here — the previous
    // renderVolNeutral(2^31) + getTanHUnknown was invented (compressor never engaged, then hard
    // tanh walled dense content). songMasterVolume = 0 (neutral UNPATCHED_VOLUME).
    int volAdjustL = postFXVol;
    int volAdjustR = postFXVol;
    int pan = this.songPan >> 1; // C audio_engine.cpp:1098
    if (pan != 0) {
      int[] panAmps = new int[2];
      if (org.deluge.firmware2.Functions.shouldDoPanning(pan, panAmps)) {
        volAdjustL =
            org.deluge.firmware2.Functions.multiply_32x32_rshift32(volAdjustL, panAmps[0]) << 2;
        volAdjustR =
            org.deluge.firmware2.Functions.multiply_32x32_rshift32(volAdjustR, panAmps[1]) << 2;
      }
    }

    int songVolume =
        org.deluge.firmware2.Functions.getFinalParameterValueVolume(
                134217728,
                org.deluge.firmware2.Functions.cableToLinearParamShortcut(this.songVolume))
            >> 1;

    // These max-scans feed only the telemetry log below — skip the whole-buffer scan when
    // telemetry is off (the default), saving two passes over the master buffer every block.
    int maxBeforeL = 0;
    int maxBeforeR = 0;
    if (debugTelemetry) {
      for (int i = 0; i < numSamples; i++) {
        maxBeforeL = Math.max(maxBeforeL, Math.abs(fxBuffer[i][0]));
        maxBeforeR = Math.max(maxBeforeR, Math.abs(fxBuffer[i][1]));
      }
    }

    masterCompressor.render(
        fxBuffer, numSamples, volAdjustL >> 1, volAdjustR >> 1, songVolume >> 3);

    if (debugTelemetry) {
      int maxAfterL = 0;
      for (int i = 0; i < numSamples; i++) {
        maxAfterL = Math.max(maxAfterL, Math.abs(fxBuffer[i][0]));
      }
      if (maxBeforeL > 1000) {
        LOGGER.fine(
            String.format(
                "[TELEMETRY Engine] fxBuffer L max absolute value: Before=%d, After=%d",
                maxBeforeL, maxAfterL));
      }
    }

    masterVolumeAdjustmentL = MASTER_VOLUME_NEUTRAL; // C:901 resets to ONE_Q31; we keep our neutral
    masterVolumeAdjustmentR = MASTER_VOLUME_NEUTRAL;

    // (No DC blocker — the C master path has none. Measured: with it removed the engine's output DC
    // stays negligible (< 0.0014 of full scale across saw/square/sine/triangle), so the invented
    // AC-coupling high-pass was removing essentially nothing while diverging from the firmware.)

    // C audio_engine.cpp:1122-1123, 1330-1365 — masterVolumeAdjustment is ONE_Q31 after the
    // master compressor; doSomeOutputting() multiplies by ONE_Q31 >> 32 (>> 1) and then applies
    // lshiftAndSaturate<AUDIO_OUTPUT_GAIN_DOUBLINGS> (8), for a net output gain of << 7 (128x).
    for (int i = 0; i < numSamples; i++) {
      int lAdjusted =
          (int) (((long) fxBuffer[i][0] * org.deluge.firmware2.Functions.ONE_Q31) >> 32);
      int rAdjusted =
          (int) (((long) fxBuffer[i][1] * org.deluge.firmware2.Functions.ONE_Q31) >> 32);
      masterBuffer[i].l = org.deluge.firmware2.Functions.lshiftAndSaturate(lAdjusted, 8);
      masterBuffer[i].r = org.deluge.firmware2.Functions.lshiftAndSaturate(rAdjusted, 8);
    }
  }

  /**
   * Feed a block of live audio input through the pitch shifter and mix into the master output. Call
   * this from a desktop audio input callback. {@code inputBlock} is interleaved stereo int
   * (LRLR...), matching the fw2 DSP convention.
   */
  public void renderLiveInput(int[] inputBlock, int numSamples, int phaseIncrement) {
    int requiredLen = numSamples * 2;
    if (liveInputOut.length < requiredLen) {
      liveInputOut = new int[requiredLen];
    }
    int[] out = liveInputOut;
    java.util.Arrays.fill(out, 0, requiredLen, 0);
    livePitchShifter.render(
        out,
        numSamples,
        phaseIncrement,
        1 << 27,
        0,
        16,
        liveInputBuffer,
        liveAudioSampleTimer += numSamples,
        inputBlock);
    for (int i = 0; i < numSamples; i++) {
      fxBuffer[i][0] += out[i * 2];
      fxBuffer[i][1] += out[i * 2 + 1];
    }
  }

  public void panic() {
    for (GlobalEffectable sound : sounds) {
      switch (sound) {
        case FirmwareSound synth -> synth.releaseAllNotes();
        case FirmwareKit kit -> {
          for (FirmwareSound drum : kit.drumSounds) {
            drum.releaseAllNotes();
          }
        }
        case null, default -> {}
      }
    }
  }

  /**
   * C song.cpp:2548, global_effectable.cpp:1145-1181, mod_controllable_audio.cpp:166-192 —
   * Song-level ModFX and EQ processed post-summation on the master buffer, pre-reverb.
   */
  private void renderSongPreReverbFX(int numSamples) {
    org.deluge.firmware2.ModFx.ModFXType activeModFx = getActiveModFXType();
    if (activeModFx != org.deluge.firmware2.ModFx.ModFXType.NONE
        && activeModFx != org.deluge.firmware2.ModFx.ModFXType.GRAIN) {
      int modFXRate =
          org.deluge.firmware2.Functions.getFinalParameterValueExp(
              org.deluge.firmware2.Functions.getParamNeutralValue(
                  org.deluge.firmware2.Param.GLOBAL_MOD_FX_RATE),
              org.deluge.firmware2.Functions.cableToExpParamShortcut(masterModFxRate));
      int modFXDepth =
          org.deluge.firmware2.Functions.getFinalParameterValueVolume(
              org.deluge.firmware2.Functions.getParamNeutralValue(
                  org.deluge.firmware2.Param.GLOBAL_MOD_FX_DEPTH),
              org.deluge.firmware2.Functions.cableToLinearParamShortcut(masterModFxDepth));
      int[] postFXVol = {134217728};
      masterModFx.processModFX(
          fxBuffer,
          numSamples,
          activeModFx,
          modFXRate,
          modFXDepth,
          postFXVol,
          masterModFxOffset,
          masterModFxFeedback,
          true,
          true);
      if (postFXVol[0] != 134217728) {
        // C ModFXProcessor.cpp:105-141, mod_controllable_audio.cpp:222,258-259 — Chorus / Flanger
        // / Warble / Phaser adjust postFXVolume for feedback/wet headroom; apply that gain here.
        for (int i = 0; i < numSamples; i++) {
          fxBuffer[i][0] =
              org.deluge.firmware2.Functions.lshiftAndSaturate(
                  org.deluge.firmware2.Functions.multiply_32x32_rshift32(
                      fxBuffer[i][0], postFXVol[0]),
                  5);
          fxBuffer[i][1] =
              org.deluge.firmware2.Functions.lshiftAndSaturate(
                  org.deluge.firmware2.Functions.multiply_32x32_rshift32(
                      fxBuffer[i][1], postFXVol[0]),
                  5);
        }
      }
    }
    if (masterEqBass != 0 || masterEqTreble != 0) {
      masterEq.process(
          fxBuffer, numSamples, masterEqBass, masterEqTreble, masterEqBassFreq, masterEqTrebleFreq);
    }
  }

  /**
   * C audio_engine.cpp:1082-1095 — Song-level FilterSet (LPF/HPF) and SRR/Bitcrush processed after
   * reverb/delay. Returns the updated {@code masterVolumeAdjustment} post-FX gain.
   */
  private int renderSongPostReverbFX(int numSamples) {
    int postFXVol = masterFilterSet.isOn() ? masterFilterGain : MASTER_VOLUME_NEUTRAL;
    if (masterFilterSet.isOn()) {
      int requiredLen = numSamples * 2;
      if (flatFilterBuffer.length < requiredLen) {
        flatFilterBuffer = new int[requiredLen];
      }
      for (int i = 0; i < numSamples; i++) {
        flatFilterBuffer[i * 2] = fxBuffer[i][0];
        flatFilterBuffer[i * 2 + 1] = fxBuffer[i][1];
      }
      masterFilterSet.renderLongStereo(flatFilterBuffer, numSamples);
      for (int i = 0; i < numSamples; i++) {
        fxBuffer[i][0] = flatFilterBuffer[i * 2];
        fxBuffer[i][1] = flatFilterBuffer[i * 2 + 1];
      }
    }
    if (org.deluge.firmware2.SrrBitcrush.isSRREnabled(masterSrr)
        || org.deluge.firmware2.SrrBitcrush.isBitcrushingEnabled(masterBitcrush)) {
      int[] volRef = {postFXVol};
      masterSrrBitcrush.process(fxBuffer, numSamples, masterBitcrush, masterSrr, volRef);
      postFXVol = volRef[0];
    }
    return postFXVol;
  }
}
