package org.chuck.deluge;

import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.model.AudioTrackModel;
import org.chuck.deluge.model.KitTrackModel;

/**
 * Typed contract between the Java Swing UI and the ChucK audio engine — every global that
 * either side reads or writes is declared, created, and registered here.
 *
 * <h2>Architecture</h2>
 * This class serves as the single source of truth for all shared state. The UI writes
 * step data, track parameters, and transport controls into primitive Java arrays
 * and scalar globals; the engine's shreds (sporked by {@code DelugeEngineDSL}) read them every
 * tick via {@link ChuckArray} wrappers that point to these same primitive arrays.
 * No locks are needed because the UI writes between ticks and the engine reads at
 * tick boundaries.
 *
 * <h2>Dimensions</h2>
 * <ul>
 *   <li>{@link #TRACKS} — 64 (configurable via {@code deluge.tracks} system property)</li>
 *   <li>{@link #STEPS} — 192 (max step capacity per clip, matching real Deluge hardware)</li>
 *   <li>{@link #PATTERN_SIZE} = {@code TRACKS × STEPS} = 12 288 — sizes all 22 step-data arrays</li>
 * </ul>
 *
 * <h2>Array Layout</h2>
 * Step-data arrays use a flat stride layout: {@code index = track * STEPS + step}.
 * All accessor methods ({@link #getStep}, {@link #setPitch}, etc.) follow this convention.
 * Track-level arrays (level, mute, filter, etc.) are indexed directly by {@code track}.
 *
 * <h2>Global Name Conventions (used in the ChucK VM)</h2>
 * <table>
 *   <caption>Global naming groups</caption>
 *   <tr><td>{@code g_*}</td><td>Scalar globals (BPM, play state, master volume)</td></tr>
 *   <tr><td>{@code g_track_*}</td><td>Per-track integer/float arrays</td></tr>
 *   <tr><td>{@code g_kit_*}</td><td>Per-track kit-specific params (ADSR, pitch, mute group)</td></tr>
 *   <tr><td>{@code g_step_*}</td><td>Per-step modulation arrays (filter, res, pan, delay, reverb)</td></tr>
 *   <tr><td>{@code g_lfo_*}</td><td>Global LFO configuration (rate, type, depth, target, track)</td></tr>
 *   <tr><td>{@code g_audio_*}</td><td>Audio clip recording/playback state (LiSa)</td></tr>
 *   <tr><td>{@code g_delay_in / g_reverb_in}</td><td>{@code Gain} UGens for FX send buses</td></tr>
 *   <tr><td>{@code g_synth_bus / g_audio_bus}</td><td>{@code Gain} UGens for submix buses</td></tr>
 *   <tr><td>{@code g_master_tap}</td><td>{@code Gain} UGen tapped before dac for WvOut export</td></tr>
 * </table>
 *
 * <h2>Engine Internals (UGen buses)</h2>
 * The {@link #register} method also creates four {@code Gain} UGens that structure the
 * engine's audio graph:
 * <ol>
 *   <li>{@code g_delay_in} — kit/synth Pan2 sends → Echo → fxIn → dac</li>
 *   <li>{@code g_reverb_in} — kit/synth Pan2 sends → JCRev/FreeVerb/MVerb → fxIn → dac</li>
 *   <li>{@code g_synth_bus} — all synth voices → HPF → compressor → limiter → masterTap → dac</li>
 *   <li>{@code g_audio_bus} — all LiSa outputs → HPF → compressor → limiter → masterTap → dac</li>
 *   <li>{@code g_master_tap} — WvOut2 spliced in during export; direct pass-through otherwise</li>
 * </ol>
 *
 * <h2>Lifecycle</h2>
 * 1. Constructed in the UI thread with all default values.
 * 2. {@link #register(ChuckVM)} called after the VM is created — registers all globals and UGens.
 * 3. On song load, the UI calls setter methods to copy model data into the arrays.
 * 4. {@code pushModelToBridge()} in {@code SwingDelugeApp} synchronises the full ProjectModel.
 * 5. The engine's shreds read these arrays on every tick event.
 *
 * @see org.chuck.deluge.engine.DelugeEngineDSL
 * @see org.chuck.deluge.ui.SwingDelugeApp
 */
public final class BridgeContract {

  // ── Dimensions ────────────────────────────────────────────────────────

  public static final int TRACKS = Integer.getInteger("deluge.tracks", 64);
  public static final int STEPS = 192;
  public static final int PATTERN_SIZE = TRACKS * STEPS;

  public static final int ENV_COUNT = 4;
  public static final int ENV_PARAMS = 4;
  public static final int ENV_STRIDE = TRACKS * ENV_COUNT * ENV_PARAMS;
  public static final int LFO_COUNT = 4;
  public static final int MAX_CLIPS_PER_TRACK = 16;

  // ── Clip launch globals ────────────────────────────────────────────────
  public static final String G_CLIP_COUNT = "g_clip_count";
  public static final String G_CURRENT_CLIP = "g_current_clip";
  public static final String G_LAUNCH_QUEUE = "g_launch_queue";
  public static final String G_QUEUE_STEP = "g_queue_step";
  // ── Transport & Master ─────────────────────────────────────────────────
  public static final String G_BPM = "g_bpm";
  public static final String G_SWING = "g_swing";
  public static final String G_PLAY = "g_play";
  public static final String G_CURRENT_STEP = "g_current_step";
  public static final String G_STUTTER_ON = "g_stutter_on";
  public static final String G_STUTTER_DIV = "g_stutter_div";

  // ── Step-data arrays (size PATTERN_SIZE each) ─────────────────────────
  public static final String G_PATTERN = "g_pattern";
  public static final String G_VELOCITY = "g_velocity";
  public static final String G_GATE = "g_gate";
  public static final String G_PITCH = "g_pitch";
  public static final String G_PROBABILITY = "g_probability";
  public static final String G_STEP_FILTER = "g_step_filter";
  public static final String G_STEP_RES = "g_step_res";
  public static final String G_STEP_FILTER_MODE = "g_step_filter_mode";
  public static final String G_STEP_PAN = "g_step_pan";
  public static final String G_STEP_DELAY = "g_step_delay";
  public static final String G_STEP_REVERB = "g_step_reverb";
  public static final String G_STEP_MOD = "g_step_mod";
  public static final String G_STEP_START = "g_step_start";
  public static final String G_STEP_END = "g_step_end";
  public static final String G_STEP_HPF_FREQ = "g_step_hpf_freq";
  public static final String G_STEP_HPF_RES = "g_step_hpf_res";
  public static final String G_STEP_MOD_RATE = "g_step_mod_rate";
  public static final String G_STEP_MOD_DEPTH = "g_step_mod_depth";
  public static final String G_STEP_OSC_A_VOL = "g_step_osc_a_vol";
  public static final String G_STEP_OSC_B_VOL = "g_step_osc_b_vol";
  public static final String G_STEP_NOISE_VOL = "g_step_noise_vol";
  public static final String G_STEP_PITCH = "g_step_pitch";
  // ── Track-level arrays (size TRACKS each) ─────────────────────────────
  public static final String G_TRACK_TYPE = "g_track_type";
  public static final String G_OSC_TYPE = "g_osc_type";
  public static final String G_TRACK_LEVEL = "g_track_level";
  public static final String G_MUTE = "g_mute";
  public static final String G_MASTER_VOL = "g_master_vol";
  public static final String G_MASTER_PAN = "g_master_pan";
  // ── Per-track synth/filter arrays ──────────────────────────────────────
  public static final String G_FILTER = "g_filter";
  public static final String G_FILTER_MODE = "g_filter_mode";
  public static final String G_FILTER_MORPH = "g_filter_morph";
  public static final String G_ENV = "g_env";
  public static final String G_LFO_RATE = "g_lfo_rate";
  public static final String G_LFO_TYPE = "g_lfo_type";
  public static final String G_LFO_DEPTH = "g_lfo_depth";
  public static final String G_LFO_TARGET = "g_lfo_target";
  public static final String G_LFO_TRACK = "g_lfo_track";
  public static final String G_LFO_VALUE = "g_lfo_value";
  public static final String G_TRACK_LENGTH = "g_track_length";
  public static final String G_DELAY_SEND = "g_delay_send";
  public static final String G_REVERB_SEND = "g_reverb_send";
  public static final String G_DELAY_TIME = "g_delay_time";
  public static final String G_DELAY_FB = "g_delay_fb";
  public static final String G_REVERB_ROOM = "g_reverb_room";
  public static final String G_REVERB_DAMP = "g_reverb_damp";
  public static final String G_SCALE = "g_scale";
  public static final String G_ROOT_KEY = "g_root_key";
  public static final String G_LOAD_TRIGGER = "g_load_trigger";
  public static final String TICK_EVENT = "tick_event";
  public static final String E_TICK = "tick_event";
  public static final String G_DELAY_IN = "g_delay_in";
  public static final String G_REVERB_IN = "g_reverb_in";
  public static final String G_SYNTH_BUS = "g_synth_bus";
  public static final String G_MASTER_COMP = "g_master_comp";
  public static final String G_ARP_ON = "g_arp_on";
  public static final String G_ARP_RATE = "g_arp_rate";
  public static final String G_ARP_OCTAVE = "g_arp_octave";
  public static final String G_ARP_MODE = "g_arp_mode";
  public static final String G_FM_RATIO = "g_fm_ratio";
  public static final String G_FM_AMOUNT = "g_fm_amount";
  public static final String G_PREVIEW_TRACK = "g_preview_track";
  public static final String E_PREVIEW = "e_preview";
  public static final String E_SIDECHAIN = "e_sidechain";
  public static final String G_SYNTH_ALGO = "g_synth_algo";
  public static final String G_DX7_PATCH_PREFIX = "g_dx7_patch_";
  public static final String G_SYNTH_MODE = "g_synth_mode";
  public static final String G_HPF_FREQ = "g_hpf_freq";
  public static final String G_HPF_RES = "g_hpf_res";
  public static final String G_POLYPHONY = "g_polyphony";
  public static final String G_MOD1_FB = "g_mod1_fb";
  public static final String G_MOD2_AMT = "g_mod2_amt";
  public static final String G_MOD2_FB = "g_mod2_fb";
  public static final String G_CARRIER1_FB = "g_carrier1_fb";
  public static final String G_CARRIER2_FB = "g_carrier2_fb";
  public static final String G_KIT_ATTACK = "g_kit_attack";
  public static final String G_KIT_DECAY = "g_kit_decay";
  public static final String G_KIT_SUSTAIN = "g_kit_sustain";
  public static final String G_KIT_RELEASE = "g_kit_release";
  public static final String G_KIT_PITCH = "g_kit_pitch";
  public static final String G_KIT_REVERSE = "g_kit_reverse";
  public static final String G_KIT_MUTE_GROUP = "g_kit_mute_group";
  // ── Extended per-track synth arrays ──
  public static final String G_OSC_MIX = "g_osc_mix";
  public static final String G_NOISE_VOL = "g_noise_vol";
  public static final String G_UNISON_NUM = "g_unison_num";
  public static final String G_UNISON_DETUNE = "g_unison_detune";
  public static final String G_MOD_FX_TYPE = "g_mod_fx_type";
  public static final String G_MOD_FX_RATE = "g_mod_fx_rate";
  public static final String G_MOD_FX_DEPTH = "g_mod_fx_depth";
  public static final String G_MOD_FX_FEEDBACK = "g_mod_fx_feedback";
  public static final String G_PORTAMENTO = "g_portamento";
  public static final String G_EQ_BASS = "g_eq_bass";
  public static final String G_EQ_TREBLE = "g_eq_treble";
  public static final String G_PAN = "g_pan";
  public static final String G_STUTTER_RATE = "g_stutter_rate";
  public static final String G_SAMPLE_RATE_RED = "g_sample_rate_red";
  public static final String G_BITCRUSH = "g_bitcrush";
  public static final String G_COMP_ATTACK = "g_comp_attack";
  public static final String G_COMP_RELEASE = "g_comp_release";
  public static final String G_OSC2_TYPE = "g_osc2_type";
  // ── Extended per-track kit-specific arrays ──
  public static final String G_KIT_LPF_MODE = "g_kit_lpf_mode";
  public static final String G_KIT_EQ_BASS = "g_kit_eq_bass";
  public static final String G_KIT_EQ_TREBLE = "g_kit_eq_treble";
  public static final String G_KIT_SIDECHAIN = "g_kit_sidechain";
  public static final String G_KIT_MOD_FX_TYPE = "g_kit_mod_fx_type";
  public static final String G_KIT_HPF_FREQ = "g_kit_hpf_freq";
  public static final String G_KIT_HPF_RES = "g_kit_hpf_res";
  public static final String G_KIT_OSC2_TYPE = "g_kit_osc2_type";
  public static final String G_KIT_UNISON_NUM = "g_kit_unison_num";
  public static final String G_KIT_UNISON_DETUNE = "g_kit_unison_detune";
  public static final String G_KIT_COMP_ATTACK = "g_kit_comp_attack";
  public static final String G_KIT_COMP_RELEASE = "g_kit_comp_release";
  public static final String G_KIT_DELAY_RATE = "g_kit_delay_rate";
  public static final String G_KIT_DELAY_FB = "g_kit_delay_fb";
  public static final String G_KIT_VOLUME = "g_kit_volume";
  public static final String G_KIT_PAN = "g_kit_pan";
  public static final String G_KIT_NOISE_VOL = "g_kit_noise_vol";
  public static final String G_KIT_STUTTER_RATE = "g_kit_stutter_rate";
  public static final String G_KIT_SAMPLE_RATE_RED = "g_kit_sample_rate_red";
  public static final String G_KIT_BITCRUSH = "g_kit_bitcrush";
  public static final String G_AUDIO_REC = "g_audio_rec";
  public static final String G_AUDIO_PLAY = "g_audio_play";
  public static final String G_AUDIO_LOOP = "g_audio_loop";
  public static final String G_AUDIO_RATE = "g_audio_rate";
  public static final String G_AUDIO_BUS = "g_audio_bus";
  public static final String G_WVOUT_ACTIVE = "g_wvout_active";
  public static final String G_WVOUT_FILE = "g_wvout_file";
  public static final String G_MASTER_TAP = "g_master_tap";

  // ── Java-engine-only transport state (not in VM) ──
  private volatile int javaPlayState = 0;
  private volatile int javaCurrentStep = -1;

  // ── Primitive Data Arrays ──
  private final int[] pattern = new int[PATTERN_SIZE];
  private final float[] velocity = new float[PATTERN_SIZE];
  private final float[] gate = new float[PATTERN_SIZE];
  private final int[] pitch = new int[PATTERN_SIZE];
  private final float[] probability = new float[PATTERN_SIZE];
  private final float[] stepFilter = new float[PATTERN_SIZE];
  private final float[] stepRes = new float[PATTERN_SIZE];
  private final int[] stepFilterMode = new int[PATTERN_SIZE];
  private final float[] stepPan = new float[PATTERN_SIZE];
  private final float[] stepDelay = new float[PATTERN_SIZE];
  private final float[] stepReverb = new float[PATTERN_SIZE];
  private final float[] stepMod = new float[PATTERN_SIZE];
  private final float[] stepStart = new float[PATTERN_SIZE];
  private final float[] stepEnd = new float[PATTERN_SIZE];
  private final float[] stepHpfFreq = new float[PATTERN_SIZE];
  private final float[] stepHpfRes = new float[PATTERN_SIZE];
  private final float[] stepModRate = new float[PATTERN_SIZE];
  private final float[] stepModDepth = new float[PATTERN_SIZE];
  private final float[] stepOscAVol = new float[PATTERN_SIZE];
  private final float[] stepOscBVol = new float[PATTERN_SIZE];
  private final float[] stepNoiseVol = new float[PATTERN_SIZE];
  private final float[] stepPitch = new float[PATTERN_SIZE];
  private final int[] trackType = new int[TRACKS];
  private final float[] trackLevel = new float[TRACKS];
  private final int[] mute = new int[TRACKS];
  private final float[] filter = new float[TRACKS * 2];
  private final int[] filterMode = new int[TRACKS];
  private final float[] filterMorph = new float[TRACKS];
  private final float[] env = new float[ENV_STRIDE];
  private final float[] lfoRate = new float[LFO_COUNT];
  private final int[] lfoType = new int[LFO_COUNT];
  private final float[] lfoDepth = new float[LFO_COUNT];
  private final int[] lfoTarget = new int[LFO_COUNT];
  private final int[] lfoTrack = new int[LFO_COUNT];
  private final float[] lfoValue = new float[LFO_COUNT];
  private final int[] trackLength = new int[TRACKS];
  private final float[] delaySend = new float[TRACKS];
  private final float[] reverbSend = new float[TRACKS];
  private final float[] kitAttack = new float[TRACKS];
  private final float[] kitDecay = new float[TRACKS];
  private final float[] kitSustain = new float[TRACKS];
  private final float[] kitRelease = new float[TRACKS];
  private final float[] kitPitch = new float[TRACKS];
  private final int[] kitReverse = new int[TRACKS];
  private final int[] kitMuteGroup = new int[TRACKS];
  private final float[] oscMix = new float[TRACKS];
  private final float[] noiseVol = new float[TRACKS];
  private final int[] unisonNum = new int[TRACKS];
  private final float[] unisonDetune = new float[TRACKS];
  private final int[] modFxType = new int[TRACKS];
  private final float[] modFxRate = new float[TRACKS];
  private final float[] modFxDepth = new float[TRACKS];
  private final float[] modFxFeedback = new float[TRACKS];
  private final float[] portamento = new float[TRACKS];
  private final float[] eqBass = new float[TRACKS];
  private final float[] eqTreble = new float[TRACKS];
  private final float[] panArr = new float[TRACKS];
  private final float[] stutterRateArr = new float[TRACKS];
  private final float[] sampleRateReductionArr = new float[TRACKS];
  private final float[] bitCrushArr = new float[TRACKS];
  private final float[] compressorAttackArr = new float[TRACKS];
  private final float[] compressorReleaseArr = new float[TRACKS];
  private final int[] osc2Type = new int[TRACKS];
  private final int[] kitLpfMode = new int[TRACKS];
  private final float[] kitEqBass = new float[TRACKS];
  private final float[] kitEqTreble = new float[TRACKS];
  private final float[] kitSidechain = new float[TRACKS];
  private final int[] kitModFxType = new int[TRACKS];
  private final float[] kitHpfFreq = new float[TRACKS];
  private final float[] kitHpfRes = new float[TRACKS];
  private final int[] kitOsc2Type = new int[TRACKS];
  private final int[] kitUnisonNum = new int[TRACKS];
  private final float[] kitUnisonDetune = new float[TRACKS];
  private final float[] kitCompressorAttackArr = new float[TRACKS];
  private final float[] kitCompressorReleaseArr = new float[TRACKS];
  private final float[] kitDelayRate = new float[TRACKS];
  private final float[] kitDelayFb = new float[TRACKS];
  private final float[] kitVolume = new float[TRACKS];
  private final float[] kitPan = new float[TRACKS];
  private final float[] kitNoiseVol = new float[TRACKS];
  private final float[] kitStutterRate = new float[TRACKS];
  private final float[] kitSampleRateRed = new float[TRACKS];
  private final float[] kitBitCrush = new float[TRACKS];
  private final int[] arpOn = new int[TRACKS];
  private final float[] arpRate = new float[TRACKS];
  private final int[] arpOctave = new int[TRACKS];
  private final int[] arpMode = new int[TRACKS];
  private final float[] fmRatio = new float[TRACKS];
  private final float[] fmAmount = new float[TRACKS];
  private final int[] synthAlgo = new int[TRACKS];
  private final int[] synthMode = new int[TRACKS];
  private final float[] hpfFreq = new float[TRACKS];
  private final float[] hpfRes = new float[TRACKS];
  private final int[] polyphony = new int[TRACKS];
  private final float[] mod1Fb = new float[TRACKS];
  private final float[] mod2Amt = new float[TRACKS];
  private final float[] mod2Fb = new float[TRACKS];
  private final float[] carrier1Fb = new float[TRACKS];
  private final float[] carrier2Fb = new float[TRACKS];
  private final int[] audioRec = new int[TRACKS];
  private final int[] audioPlay = new int[TRACKS];
  private final int[] audioLoop = new int[TRACKS];
  private final float[] audioRate = new float[TRACKS];
  private final int[] clipCount = new int[TRACKS];
  private final int[] currentClip = new int[TRACKS];
  private final int[] launchQueue = new int[TRACKS];

  private ChuckVM vm;
  private boolean recording = false;

  private float masterVol = 1.0f;
  private float masterPan = 0.0f;
  private float delayTime = 0.375f;
  private float delayFb = 0.4f;
  private float reverbRoom = 0.6f;
  private float reverbDamp = 0.5f;
  private double bpm = 120.0;
  private double swing = 0.5;

  public BridgeContract() {
    initDefaults();
  }

  private void initDefaults() {
    for (int i = 0; i < PATTERN_SIZE; i++) {
      pattern[i] = 0;
      velocity[i] = 0.8f;
      gate[i] = 0.9f;
      pitch[i] = 0;
      probability[i] = 1.0f;
      stepFilter[i] = 0.0f;
      stepRes[i] = 0.0f;
      stepFilterMode[i] = -1;
      stepPan[i] = 0.0f;
      stepDelay[i] = 0.0f;
      stepReverb[i] = 0.0f;
      stepMod[i] = 0.0f;
      stepStart[i] = 0.0f;
      stepEnd[i] = 1.0f;
      stepHpfFreq[i] = 0.0f;
      stepHpfRes[i] = 0.0f;
      stepModRate[i] = 0.0f;
      stepModDepth[i] = 0.0f;
      stepOscAVol[i] = 1.0f;
      stepOscBVol[i] = 1.0f;
      stepNoiseVol[i] = 1.0f;
      stepPitch[i] = 0.0f;
    }
    for (int t = 0; t < TRACKS; t++) {
      trackType[t] = 0;
      mute[t] = 0;
      trackLevel[t] = 0.7f;
      filter[t * 2] = 1.0f;
      filter[t * 2 + 1] = 0.5f;
      filterMode[t] = 0;
      filterMorph[t] = 0.0f;
      delaySend[t] = 0.0f;
      reverbSend[t] = 0.15f;
      kitAttack[t] = 0.001f;
      kitDecay[t] = 0.1f;
      kitSustain[t] = 0.8f;
      kitRelease[t] = 0.2f;
      kitPitch[t] = 0.0f;
      kitReverse[t] = 0;
      kitMuteGroup[t] = 0;
      arpOn[t] = 0;
      arpRate[t] = 1.0f;
      arpOctave[t] = 0;
      arpMode[t] = 0;
      fmRatio[t] = 1.0f;
      fmAmount[t] = 0.0f;
      synthAlgo[t] = 0;
      synthMode[t] = 0;
      mod1Fb[t] = 0.0f;
      mod2Amt[t] = 0.0f;
      mod2Fb[t] = 0.0f;
      carrier1Fb[t] = 0.0f;
      carrier2Fb[t] = 0.0f;
      audioRec[t] = 0;
      audioPlay[t] = 0;
      audioLoop[t] = 1;
      audioRate[t] = 1.0f;
      trackLength[t] = 16;
      clipCount[t] = 0;
      currentClip[t] = 0;
      launchQueue[t] = -1;
      oscMix[t] = 0.5f;
      noiseVol[t] = 0.0f;
      unisonNum[t] = 1;
      unisonDetune[t] = 0.0f;
      modFxType[t] = 0;
      modFxRate[t] = 0.0f;
      modFxDepth[t] = 0.0f;
      modFxFeedback[t] = 0.0f;
      portamento[t] = 0.0f;
      eqBass[t] = 0.0f;
      eqTreble[t] = 0.0f;
      panArr[t] = 0.0f;
      stutterRateArr[t] = 0.0f;
      sampleRateReductionArr[t] = 0.0f;
      bitCrushArr[t] = 0.0f;
      compressorAttackArr[t] = 0.0f;
      compressorReleaseArr[t] = 0.0f;
      osc2Type[t] = 0;
      kitLpfMode[t] = 0;
      kitEqBass[t] = 0.0f;
      kitEqTreble[t] = 0.0f;
      kitSidechain[t] = 0.0f;
      kitModFxType[t] = 0;
      kitHpfFreq[t] = 20.0f;
      kitHpfRes[t] = 0.0f;
      kitOsc2Type[t] = 0;
      kitUnisonNum[t] = 1;
      kitUnisonDetune[t] = 0.0f;
      kitCompressorAttackArr[t] = 0.0f;
      kitCompressorReleaseArr[t] = 0.0f;
      kitDelayRate[t] = 0.0f;
      kitDelayFb[t] = 0.0f;
      kitVolume[t] = 0.5f;
      kitPan[t] = 0.0f;
      kitNoiseVol[t] = 0.0f;
      kitStutterRate[t] = 0.0f;
      kitSampleRateRed[t] = 0.0f;
      kitBitCrush[t] = 0.0f;
    }
    for (int e = 0; e < TRACKS * ENV_COUNT; e++) {
      env[e * ENV_PARAMS + 0] = 0.01f;
      env[e * ENV_PARAMS + 1] = 0.1f;
      env[e * ENV_PARAMS + 2] = 0.7f;
      env[e * ENV_PARAMS + 3] = 0.2f;
    }
    for (int l = 0; l < LFO_COUNT; l++) {
      lfoRate[l] = 1.0f;
      lfoType[l] = 0;
      lfoDepth[l] = 0.0f;
      lfoTarget[l] = 0;
      lfoTrack[l] = -1;
      lfoValue[l] = 0.0f;
    }
  }

  public void register(ChuckVM vm) {
    this.vm = vm;
    vm.setGlobalFloat(G_BPM, bpm);
    vm.setGlobalFloat(G_SWING, swing);
    vm.setGlobalInt(G_PLAY, 0L);
    vm.setGlobalInt(G_CURRENT_STEP, -1L);
    vm.setGlobalFloat(G_MASTER_VOL, (double) masterVol);
    vm.setGlobalFloat(G_MASTER_PAN, (double) masterPan);
    vm.setGlobalFloat(G_DELAY_TIME, (double) delayTime);
    vm.setGlobalFloat(G_DELAY_FB, (double) delayFb);
    vm.setGlobalFloat(G_REVERB_ROOM, (double) reverbRoom);
    vm.setGlobalFloat(G_REVERB_DAMP, (double) reverbDamp);
    vm.setGlobalInt(G_SCALE, 0L);
    vm.setGlobalInt(G_ROOT_KEY, 0L);
    vm.setGlobalInt(G_STUTTER_ON, 0L);
    vm.setGlobalFloat(G_STUTTER_DIV, 1.0);

    vm.setGlobalInt(G_PREVIEW_TRACK, 0L);
    vm.setGlobalFloat("g_preview_pitch", 60.0f);
    vm.setGlobalObject(E_PREVIEW, new org.chuck.core.ChuckEvent());
    vm.setGlobalObject(E_SIDECHAIN, new org.chuck.core.ChuckEvent());
    vm.setGlobalObject(G_LOAD_TRIGGER, new org.chuck.core.ChuckEvent());
    vm.setGlobalObject(TICK_EVENT, new org.chuck.core.ChuckEvent());

    // Wrap primitive arrays into ChuckArray objects for VM registration
    vm.setGlobalObject(G_PATTERN, new ChuckArray(pattern));
    vm.setGlobalObject(G_VELOCITY, new ChuckArray(velocity));
    vm.setGlobalObject(G_GATE, new ChuckArray(gate));
    vm.setGlobalObject(G_PITCH, new ChuckArray(pitch));
    vm.setGlobalObject(G_PROBABILITY, new ChuckArray(probability));
    vm.setGlobalObject(G_STEP_FILTER, new ChuckArray(stepFilter));
    vm.setGlobalObject(G_STEP_RES, new ChuckArray(stepRes));
    vm.setGlobalObject(G_STEP_FILTER_MODE, new ChuckArray(stepFilterMode));
    vm.setGlobalObject(G_STEP_PAN, new ChuckArray(stepPan));
    vm.setGlobalObject(G_STEP_DELAY, new ChuckArray(stepDelay));
    vm.setGlobalObject(G_STEP_REVERB, new ChuckArray(stepReverb));
    vm.setGlobalObject(G_STEP_MOD, new ChuckArray(stepMod));
    vm.setGlobalObject(G_STEP_START, new ChuckArray(stepStart));
    vm.setGlobalObject(G_STEP_END, new ChuckArray(stepEnd));
    vm.setGlobalObject(G_STEP_HPF_FREQ, new ChuckArray(stepHpfFreq));
    vm.setGlobalObject(G_STEP_HPF_RES, new ChuckArray(stepHpfRes));
    vm.setGlobalObject(G_STEP_MOD_RATE, new ChuckArray(stepModRate));
    vm.setGlobalObject(G_STEP_MOD_DEPTH, new ChuckArray(stepModDepth));
    vm.setGlobalObject(G_STEP_OSC_A_VOL, new ChuckArray(stepOscAVol));
    vm.setGlobalObject(G_STEP_OSC_B_VOL, new ChuckArray(stepOscBVol));
    vm.setGlobalObject(G_STEP_NOISE_VOL, new ChuckArray(stepNoiseVol));
    vm.setGlobalObject(G_STEP_PITCH, new ChuckArray(stepPitch));
    vm.setGlobalObject(G_TRACK_TYPE, new ChuckArray(trackType));
    vm.setGlobalObject(G_TRACK_LEVEL, new ChuckArray(trackLevel));
    vm.setGlobalObject(G_MUTE, new ChuckArray(mute));
    vm.setGlobalObject(G_FILTER, new ChuckArray(filter));
    vm.setGlobalObject(G_FILTER_MODE, new ChuckArray(filterMode));
    vm.setGlobalObject(G_FILTER_MORPH, new ChuckArray(filterMorph));
    vm.setGlobalObject(G_ENV, new ChuckArray(env));
    vm.setGlobalObject(G_LFO_RATE, new ChuckArray(lfoRate));
    vm.setGlobalObject(G_LFO_TYPE, new ChuckArray(lfoType));
    vm.setGlobalObject(G_LFO_DEPTH, new ChuckArray(lfoDepth));
    vm.setGlobalObject(G_LFO_TARGET, new ChuckArray(lfoTarget));
    vm.setGlobalObject(G_LFO_TRACK, new ChuckArray(lfoTrack));
    vm.setGlobalObject(G_LFO_VALUE, new ChuckArray(lfoValue));
    vm.setGlobalObject(G_TRACK_LENGTH, new ChuckArray(trackLength));
    vm.setGlobalObject(G_DELAY_SEND, new ChuckArray(delaySend));
    vm.setGlobalObject(G_REVERB_SEND, new ChuckArray(reverbSend));
    vm.setGlobalObject(G_KIT_ATTACK, new ChuckArray(kitAttack));
    vm.setGlobalObject(G_KIT_DECAY, new ChuckArray(kitDecay));
    vm.setGlobalObject(G_KIT_SUSTAIN, new ChuckArray(kitSustain));
    vm.setGlobalObject(G_KIT_RELEASE, new ChuckArray(kitRelease));
    vm.setGlobalObject(G_KIT_PITCH, new ChuckArray(kitPitch));
    vm.setGlobalObject(G_KIT_REVERSE, new ChuckArray(kitReverse));
    vm.setGlobalObject(G_KIT_MUTE_GROUP, new ChuckArray(kitMuteGroup));
    vm.setGlobalObject(G_OSC_MIX, new ChuckArray(oscMix));
    vm.setGlobalObject(G_NOISE_VOL, new ChuckArray(noiseVol));
    vm.setGlobalObject(G_UNISON_NUM, new ChuckArray(unisonNum));
    vm.setGlobalObject(G_UNISON_DETUNE, new ChuckArray(unisonDetune));
    vm.setGlobalObject(G_MOD_FX_TYPE, new ChuckArray(modFxType));
    vm.setGlobalObject(G_MOD_FX_RATE, new ChuckArray(modFxRate));
    vm.setGlobalObject(G_MOD_FX_DEPTH, new ChuckArray(modFxDepth));
    vm.setGlobalObject(G_MOD_FX_FEEDBACK, new ChuckArray(modFxFeedback));
    vm.setGlobalObject(G_PORTAMENTO, new ChuckArray(portamento));
    vm.setGlobalObject(G_EQ_BASS, new ChuckArray(eqBass));
    vm.setGlobalObject(G_EQ_TREBLE, new ChuckArray(eqTreble));
    vm.setGlobalObject(G_PAN, new ChuckArray(panArr));
    vm.setGlobalObject(G_STUTTER_RATE, new ChuckArray(stutterRateArr));
    vm.setGlobalObject(G_SAMPLE_RATE_RED, new ChuckArray(sampleRateReductionArr));
    vm.setGlobalObject(G_BITCRUSH, new ChuckArray(bitCrushArr));
    vm.setGlobalObject(G_COMP_ATTACK, new ChuckArray(compressorAttackArr));
    vm.setGlobalObject(G_COMP_RELEASE, new ChuckArray(compressorReleaseArr));
    vm.setGlobalObject(G_OSC2_TYPE, new ChuckArray(osc2Type));
    vm.setGlobalObject(G_KIT_LPF_MODE, new ChuckArray(kitLpfMode));
    vm.setGlobalObject(G_KIT_EQ_BASS, new ChuckArray(kitEqBass));
    vm.setGlobalObject(G_KIT_EQ_TREBLE, new ChuckArray(kitEqTreble));
    vm.setGlobalObject(G_KIT_SIDECHAIN, new ChuckArray(kitSidechain));
    vm.setGlobalObject(G_KIT_MOD_FX_TYPE, new ChuckArray(kitModFxType));
    vm.setGlobalObject(G_KIT_HPF_FREQ, new ChuckArray(kitHpfFreq));
    vm.setGlobalObject(G_KIT_HPF_RES, new ChuckArray(kitHpfRes));
    vm.setGlobalObject(G_KIT_OSC2_TYPE, new ChuckArray(kitOsc2Type));
    vm.setGlobalObject(G_KIT_UNISON_NUM, new ChuckArray(kitUnisonNum));
    vm.setGlobalObject(G_KIT_UNISON_DETUNE, new ChuckArray(kitUnisonDetune));
    vm.setGlobalObject(G_KIT_COMP_ATTACK, new ChuckArray(kitCompressorAttackArr));
    vm.setGlobalObject(G_KIT_COMP_RELEASE, new ChuckArray(kitCompressorReleaseArr));
    vm.setGlobalObject(G_KIT_DELAY_RATE, new ChuckArray(kitDelayRate));
    vm.setGlobalObject(G_KIT_DELAY_FB, new ChuckArray(kitDelayFb));
    vm.setGlobalObject(G_KIT_VOLUME, new ChuckArray(kitVolume));
    vm.setGlobalObject(G_KIT_PAN, new ChuckArray(kitPan));
    vm.setGlobalObject(G_KIT_NOISE_VOL, new ChuckArray(kitNoiseVol));
    vm.setGlobalObject(G_KIT_STUTTER_RATE, new ChuckArray(kitStutterRate));
    vm.setGlobalObject(G_KIT_SAMPLE_RATE_RED, new ChuckArray(kitSampleRateRed));
    vm.setGlobalObject(G_KIT_BITCRUSH, new ChuckArray(kitBitCrush));
    vm.setGlobalObject(G_ARP_ON, new ChuckArray(arpOn));
    vm.setGlobalObject(G_ARP_RATE, new ChuckArray(arpRate));
    vm.setGlobalObject(G_ARP_OCTAVE, new ChuckArray(arpOctave));
    vm.setGlobalObject(G_ARP_MODE, new ChuckArray(arpMode));
    vm.setGlobalObject(G_FM_RATIO, new ChuckArray(fmRatio));
    vm.setGlobalObject(G_FM_AMOUNT, new ChuckArray(fmAmount));
    vm.setGlobalObject(G_SYNTH_ALGO, new ChuckArray(synthAlgo));
    vm.setGlobalObject(G_SYNTH_MODE, new ChuckArray(synthMode));
    vm.setGlobalObject(G_HPF_FREQ, new ChuckArray(hpfFreq));
    vm.setGlobalObject(G_HPF_RES, new ChuckArray(hpfRes));
    vm.setGlobalObject(G_POLYPHONY, new ChuckArray(polyphony));
    vm.setGlobalObject(G_MOD1_FB, new ChuckArray(mod1Fb));
    vm.setGlobalObject(G_MOD2_AMT, new ChuckArray(mod2Amt));
    vm.setGlobalObject(G_MOD2_FB, new ChuckArray(mod2Fb));
    vm.setGlobalObject(G_CARRIER1_FB, new ChuckArray(carrier1Fb));
    vm.setGlobalObject(G_CARRIER2_FB, new ChuckArray(carrier2Fb));
    vm.setGlobalObject(G_AUDIO_REC, new ChuckArray(audioRec));
    vm.setGlobalObject(G_AUDIO_PLAY, new ChuckArray(audioPlay));
    vm.setGlobalObject(G_AUDIO_LOOP, new ChuckArray(audioLoop));
    vm.setGlobalObject(G_AUDIO_RATE, new ChuckArray(audioRate));
    vm.setGlobalObject(G_CLIP_COUNT, new ChuckArray(clipCount));
    vm.setGlobalObject(G_CURRENT_CLIP, new ChuckArray(currentClip));
    vm.setGlobalObject(G_LAUNCH_QUEUE, new ChuckArray(launchQueue));
    vm.setGlobalInt(G_QUEUE_STEP, 0L);

    vm.setGlobalObject(G_DELAY_IN, new org.chuck.audio.util.Gain());
    vm.setGlobalObject(G_REVERB_IN, new org.chuck.audio.util.Gain());
    vm.setGlobalObject(G_SYNTH_BUS, new org.chuck.audio.util.Gain());
    vm.setGlobalObject(G_AUDIO_BUS, new org.chuck.audio.util.Gain());
    vm.setGlobalFloat(G_WVOUT_ACTIVE, 0.0);
    vm.setGlobalString(G_WVOUT_FILE, "");
  }

  public ChuckVM getVm() { return vm; }
  /** Expose the pattern array for test verification. */
  public ChuckArray patternArray() { return (ChuckArray) vm.getGlobalObject(G_PATTERN); }

  // ── Accessors ──
  public void setStep(int track, int step, boolean active) { pattern[track * STEPS + step] = active ? 1 : 0; }
  public void setStep(int track, int step, boolean active, int clipIdx) {
    if (clipIdx == 0) {
      pattern[track * STEPS + step] = active ? 1 : 0;
    } else {
      ChuckArray ca = getClipArray(G_PATTERN, clipIdx);
      if (ca != null) ca.setInt(track * STEPS + step, active ? 1L : 0L);
    }
  }
  public void clearAllSteps() { for (int i = 0; i < pattern.length; i++) pattern[i] = 0; }
  public boolean getStep(int track, int step) { return pattern[track * STEPS + step] > 0; }
  public void setVelocity(int track, int step, double val) { velocity[track * STEPS + step] = (float) Math.max(0.0, Math.min(1.0, val)); }
  public void setVelocity(int track, int step, double val, int clipIdx) {
    float clamped = (float) Math.max(0.0, Math.min(1.0, val));
    if (clipIdx == 0) {
      velocity[track * STEPS + step] = clamped;
    } else {
      ChuckArray ca = getClipArray(G_VELOCITY, clipIdx);
      if (ca != null) ca.setFloat(track * STEPS + step, val);
    }
  }
  public double getVelocity(int track, int step) { return velocity[track * STEPS + step]; }
  public void setGate(int track, int step, double val) { gate[track * STEPS + step] = (float) val; }
  public double getGate(int track, int step) { return gate[track * STEPS + step]; }
  public void setPitch(int track, int step, int p) { pitch[track * STEPS + step] = p; }
  public int getPitch(int track, int step) { return pitch[track * STEPS + step]; }
  public void setStepProbability(int track, int step, double val) { probability[track * STEPS + step] = (float) val; }
  public double getStepProbability(int track, int step) { return probability[track * STEPS + step]; }
  
  public ChuckArray getClipArray(String baseName, int clipIdx) {
    if (clipIdx <= 0) return (ChuckArray) vm.getGlobalObject(baseName);
    String name = baseName + "_C" + clipIdx;
    Object obj = vm.getGlobalObject(name);
    if (obj instanceof ChuckArray ca) return ca;
    
    // Auto-create clip arrays for ChucK compatibility if they don't exist
    // (In pure Java mode, we'll eventually move this logic out of the VM)
    ChuckArray base = (ChuckArray) vm.getGlobalObject(baseName);
    ChuckArray arr = "int".equals(base.elementTypeName) 
        ? new ChuckArray(new int[PATTERN_SIZE]) 
        : new ChuckArray(new float[PATTERN_SIZE]);
    vm.setGlobalObject(name, arr);
    return arr;
  }

  public int getClipCount(int track) { return clipCount[track]; }
  public void setClipCount(int track, int count) { clipCount[track] = count; }
  public int getCurrentClip(int track) { return currentClip[track]; }
  public void setCurrentClip(int track, int idx) { currentClip[track] = idx; }
  public int getLaunchQueue(int track) { return launchQueue[track]; }
  public void setLaunchQueue(int track, int clipIdx) { launchQueue[track] = clipIdx; }
  public int getQueueStep() { return vm != null ? (int) vm.getGlobalInt(G_QUEUE_STEP) : 0; }
  public void setQueueStep(int step) { if (vm != null) vm.setGlobalInt(G_QUEUE_STEP, (long) step); }

  public void setStepFilter(int track, int step, double val) { stepFilter[track * STEPS + step] = (float) val; }
  public double getStepFilter(int track, int step) { return stepFilter[track * STEPS + step]; }
  public void setStepRes(int track, int step, double val) { stepRes[track * STEPS + step] = (float) val; }
  public double getStepRes(int track, int step) { return stepRes[track * STEPS + step]; }
  public void setStepPan(int track, int step, double val) { stepPan[track * STEPS + step] = (float) val; }
  public double getStepPan(int track, int step) { return stepPan[track * STEPS + step]; }
  public void setStepDelay(int track, int step, double val) { stepDelay[track * STEPS + step] = (float) val; }
  public double getStepDelay(int track, int step) { return stepDelay[track * STEPS + step]; }
  public void setStepReverb(int track, int step, double val) { stepReverb[track * STEPS + step] = (float) val; }
  public double getStepReverb(int track, int step) { return stepReverb[track * STEPS + step]; }

  public void setStepHpfFreq(int track, int step, double val) { stepHpfFreq[track * STEPS + step] = (float) val; }
  public double getStepHpfFreq(int track, int step) { return stepHpfFreq[track * STEPS + step]; }
  public void setStepHpfRes(int track, int step, double val) { stepHpfRes[track * STEPS + step] = (float) val; }
  public double getStepHpfRes(int track, int step) { return stepHpfRes[track * STEPS + step]; }
  public void setStepModRate(int track, int step, double val) { stepModRate[track * STEPS + step] = (float) val; }
  public double getStepModRate(int track, int step) { return stepModRate[track * STEPS + step]; }
  public void setStepModDepth(int track, int step, double val) { stepModDepth[track * STEPS + step] = (float) val; }
  public double getStepModDepth(int track, int step) { return stepModDepth[track * STEPS + step]; }
  public void setStepOscAVol(int track, int step, double val) { stepOscAVol[track * STEPS + step] = (float) val; }
  public double getStepOscAVol(int track, int step) { return stepOscAVol[track * STEPS + step]; }
  public void setStepOscBVol(int track, int step, double val) { stepOscBVol[track * STEPS + step] = (float) val; }
  public double getStepOscBVol(int track, int step) { return stepOscBVol[track * STEPS + step]; }
  public void setStepNoiseVol(int track, int step, double val) { stepNoiseVol[track * STEPS + step] = (float) val; }
  public double getStepNoiseVol(int track, int step) { return stepNoiseVol[track * STEPS + step]; }
  public void setStepPitch(int track, int step, double val) { stepPitch[track * STEPS + step] = (float) val; }
  public double getStepPitch(int track, int step) { return stepPitch[track * STEPS + step]; }

  public void setTrackLevel(int track, double val) { trackLevel[track] = (float) val; }
  public double getTrackLevel(int track) { return trackLevel[track]; }
  public void setMute(int track, boolean val) { 
    mute[track] = val ? 1 : 0; 
    if (vm != null) vm.setGlobalInt("g_mute_" + track, val ? 1L : 0L);
  }
  public boolean getMute(int track) { 
    if (vm != null) return vm.getGlobalInt("g_mute_" + track) > 0;
    return mute[track] > 0; 
  }
  public int getTrackType(int track) { return trackType[track]; }
  public void setTrackType(int track, int type) { trackType[track] = type; }
  public void setFilterFreq(int track, double val) { filter[track * 2] = (float) val; }
  public double getTrackFilterFreq(int track) { return filter[track * 2]; }
  public void setFilterRes(int track, double val) { filter[track * 2 + 1] = (float) val; }
  public double getTrackFilterRes(int track) { return filter[track * 2 + 1]; }
  public void setFilterMode(int track, int mode) { filterMode[track] = mode; }
  public void setFilterMorph(int track, double morph) { filterMorph[track] = (float) morph; }

  public void setEnv(int row, int envIndex, double a, double d, double s, double r) {
    int b = (row * ENV_COUNT + envIndex) * ENV_PARAMS;
    env[b + 0] = (float) a; env[b + 1] = (float) d; env[b + 2] = (float) s; env[b + 3] = (float) r;
  }
  public void setLfo(int lfoIndex, double rateHz, int waveType, double depth) {
    lfoRate[lfoIndex] = (float) rateHz; lfoType[lfoIndex] = waveType; lfoDepth[lfoIndex] = (float) depth;
  }
  public void setLfoTarget(int lfoIndex, int target) { lfoTarget[lfoIndex] = target; }
  public int getLfoTarget(int lfoIndex) { return lfoTarget[lfoIndex]; }
  public void setLfoTrack(int lfoIndex, int track) { lfoTrack[lfoIndex] = track; }
  public int getLfoTrack(int lfoIndex) { return lfoTrack[lfoIndex]; }
  public void setTrackLength(int track, int steps) { trackLength[track] = steps; }
  public int getTrackLength(int track) { return trackLength[track]; }

  public void setBpm(double bpm) { 
    this.bpm = bpm;
    if (vm != null) vm.setGlobalFloat(G_BPM, bpm); 
  }
  public double getBpm() { return bpm; }
  public void setSwing(double swing) {
    this.swing = swing;
    if (vm != null) vm.setGlobalFloat(G_SWING, swing);
  }
  public double getSwing() { return swing; }

  public void setMasterVol(double val) {
    this.masterVol = (float) val;
    if (vm != null) vm.setGlobalFloat(G_MASTER_VOL, val);
  }
  public double getMasterVol() { return masterVol; }

  public void setDelayParams(double time, double fb) {
    this.delayTime = (float) time;
    this.delayFb = (float) fb;
    if (vm != null) {
      vm.setGlobalFloat(G_DELAY_TIME, time);
      vm.setGlobalFloat(G_DELAY_FB, fb);
    }
  }
  public double getDelayTime() { return delayTime; }
  public double getDelayFb() { return delayFb; }

  public void setReverbParams(double room, double damp) {
    this.reverbRoom = (float) room;
    this.reverbDamp = (float) damp;
    if (vm != null) {
      vm.setGlobalFloat(G_REVERB_ROOM, room);
      vm.setGlobalFloat(G_REVERB_DAMP, damp);
    }
  }
  public double getReverbRoom() { return reverbRoom; }
  public double getReverbDamp() { return reverbDamp; }

  // ── Extended per-track synth accessors ──
  public void setOscMix(int track, float v) { oscMix[track] = v; }
  public float getOscMix(int track) { return oscMix[track]; }
  public void setNoiseVol(int track, float v) { noiseVol[track] = v; }
  public float getNoiseVol(int track) { return noiseVol[track]; }
  public void setUnisonNum(int track, int v) { unisonNum[track] = v; }
  public int getUnisonNum(int track) { return unisonNum[track]; }
  public void setUnisonDetune(int track, float v) { unisonDetune[track] = v; }
  public float getUnisonDetune(int track) { return unisonDetune[track]; }
  public void setModFxType(int track, int v) { modFxType[track] = v; }
  public int getModFxType(int track) { return modFxType[track]; }
  public void setModFxRate(int track, float v) { modFxRate[track] = v; }
  public float getModFxRate(int track) { return modFxRate[track]; }
  public void setModFxDepth(int track, float v) { modFxDepth[track] = v; }
  public float getModFxDepth(int track) { return modFxDepth[track]; }
  public void setModFxFeedback(int track, float v) { modFxFeedback[track] = v; }
  public float getModFxFeedback(int track) { return modFxFeedback[track]; }
  public void setPortamento(int track, float v) { portamento[track] = v; }
  public float getPortamento(int track) { return portamento[track]; }
  public void setEqBass(int track, float v) { eqBass[track] = v; }
  public float getEqBass(int track) { return eqBass[track]; }
  public void setEqTreble(int track, float v) { eqTreble[track] = v; }
  public float getEqTreble(int track) { return eqTreble[track]; }
  public void setTrackPan(int track, float v) { panArr[track] = v; }
  public float getTrackPan(int track) { return panArr[track]; }
  public void setStutterRate(int track, float v) { stutterRateArr[track] = v; }
  public float getStutterRate(int track) { return stutterRateArr[track]; }
  public void setSampleRateReduction(int track, float v) { sampleRateReductionArr[track] = v; }
  public float getSampleRateReduction(int track) { return sampleRateReductionArr[track]; }
  public void setBitCrush(int track, float v) { bitCrushArr[track] = v; }
  public float getBitCrush(int track) { return bitCrushArr[track]; }
  public void setCompAttack(int track, float v) { compressorAttackArr[track] = v; }
  public float getCompAttack(int track) { return compressorAttackArr[track]; }
  public void setCompRelease(int track, float v) { compressorReleaseArr[track] = v; }
  public float getCompRelease(int track) { return compressorReleaseArr[track]; }
  public void setOsc2Type(int track, int v) { osc2Type[track] = v; }
  public int getOsc2Type(int track) { return osc2Type[track]; }

  // ── Extended per-track kit accessors ──
  public void setKitLpfMode(int track, int v) { kitLpfMode[track] = v; }
  public int getKitLpfMode(int track) { return kitLpfMode[track]; }
  public void setKitEqBass(int track, float v) { kitEqBass[track] = v; }
  public float getKitEqBass(int track) { return kitEqBass[track]; }
  public void setKitEqTreble(int track, float v) { kitEqTreble[track] = v; }
  public float getKitEqTreble(int track) { return kitEqTreble[track]; }
  public void setKitSidechain(int track, float v) { kitSidechain[track] = v; }
  public float getKitSidechain(int track) { return kitSidechain[track]; }
  public void setKitModFxType(int track, int v) { kitModFxType[track] = v; }
  public int getKitModFxType(int track) { return kitModFxType[track]; }
  public void setKitHpfFreq(int track, float v) { kitHpfFreq[track] = v; }
  public float getKitHpfFreq(int track) { return kitHpfFreq[track]; }
  public void setKitHpfRes(int track, float v) { kitHpfRes[track] = v; }
  public float getKitHpfRes(int track) { return kitHpfRes[track]; }
  public void setKitOsc2Type(int track, int v) { kitOsc2Type[track] = v; }
  public int getKitOsc2Type(int track) { return kitOsc2Type[track]; }
  public void setKitUnisonNum(int track, int v) { kitUnisonNum[track] = v; }
  public int getKitUnisonNum(int track) { return kitUnisonNum[track]; }
  public void setKitUnisonDetune(int track, float v) { kitUnisonDetune[track] = v; }
  public float getKitUnisonDetune(int track) { return kitUnisonDetune[track]; }
  public void setKitCompAttack(int track, float v) { kitCompressorAttackArr[track] = v; }
  public float getKitCompAttack(int track) { return kitCompressorAttackArr[track]; }
  public void setKitCompRelease(int track, float v) { kitCompressorReleaseArr[track] = v; }
  public float getKitCompRelease(int track) { return kitCompressorReleaseArr[track]; }
  public void setKitDelayRate(int track, float v) { kitDelayRate[track] = v; }
  public float getKitDelayRate(int track) { return kitDelayRate[track]; }
  public void setKitDelayFb(int track, float v) { kitDelayFb[track] = v; }
  public float getKitDelayFb(int track) { return kitDelayFb[track]; }
  public void setKitVolume(int track, float v) { kitVolume[track] = v; }
  public float getKitVolume(int track) { return kitVolume[track]; }
  public void setKitPan(int track, float v) { kitPan[track] = v; }
  public float getKitPan(int track) { return kitPan[track]; }
  public void setKitNoiseVol(int track, float v) { kitNoiseVol[track] = v; }
  public float getKitNoiseVol(int track) { return kitNoiseVol[track]; }
  public void setKitStutterRate(int track, float v) { kitStutterRate[track] = v; }
  public float getKitStutterRate(int track) { return kitStutterRate[track]; }
  public void setKitSampleRateRed(int track, float v) { kitSampleRateRed[track] = v; }
  public float getKitSampleRateRed(int track) { return kitSampleRateRed[track]; }
  public void setKitBitCrush(int track, float v) { kitBitCrush[track] = v; }
  public float getKitBitCrush(int track) { return kitBitCrush[track]; }

  public boolean getArpOn(int track) { return arpOn[track] > 0; }
  public void setArpOn(int track, boolean on) { arpOn[track] = on ? 1 : 0; }
  public double getArpRate(int track) { return arpRate[track]; }
  public void setArpRate(int track, double rate) { arpRate[track] = (float) rate; }
  public int getArpOctave(int track) { return arpOctave[track]; }
  public void setArpOctave(int track, int oct) { arpOctave[track] = oct; }
  public int getArpMode(int track) { return arpMode[track]; }
  public void setArpMode(int track, int mode) { arpMode[track] = mode; }
  public double getFmRatio(int track) { return fmRatio[track]; }
  public void setFmRatio(int track, double r) { fmRatio[track] = (float) r; }
  public double getFmAmount(int track) { return fmAmount[track]; }
  public void setFmAmount(int track, double a) { fmAmount[track] = (float) a; }
  public int getSynthAlgo(int track) { return synthAlgo[track]; }
  public void setSynthAlgo(int track, int algo) { synthAlgo[track] = algo; }
  public int getSynthMode(int track) { return synthMode[track]; }
  public void setSynthMode(int track, int mode) { synthMode[track] = mode; }
  public void setHpfFreq(int track, float freq) { hpfFreq[track] = freq; }
  public float getHpfFreq(int track) { return hpfFreq[track]; }
  public void setHpfRes(int track, float res) { hpfRes[track] = res; }
  public float getHpfRes(int track) { return hpfRes[track]; }
  public void setPolyphony(int track, int mode) { polyphony[track] = mode; }
  public int getPolyphony(int track) { return polyphony[track]; }

  public float getMod1Fb(int track) { return mod1Fb[track]; }
  public void setMod1Fb(int track, float v) { mod1Fb[track] = v; }
  public float getMod2Amt(int track) { return mod2Amt[track]; }
  public void setMod2Amt(int track, float v) { mod2Amt[track] = v; }
  public float getMod2Fb(int track) { return mod2Fb[track]; }
  public void setMod2Fb(int track, float v) { mod2Fb[track] = v; }
  public float getCarrier1Fb(int track) { return carrier1Fb[track]; }
  public void setCarrier1Fb(int track, float v) { carrier1Fb[track] = v; }
  public float getCarrier2Fb(int track) { return carrier2Fb[track]; }
  public void setCarrier2Fb(int track, float v) { carrier2Fb[track] = v; }

  public int getAudioRec(int track) { return audioRec[track]; }
  public void setAudioRec(int track, int state) { audioRec[track] = state; }
  public int getAudioPlay(int track) { return audioPlay[track]; }
  public void setAudioPlay(int track, int state) { audioPlay[track] = state; }
  public int getAudioLoop(int track) { return audioLoop[track]; }
  public void setAudioLoop(int track, int looping) { audioLoop[track] = looping; }
  public float getAudioRate(int track) { return audioRate[track]; }
  public void setAudioRate(int track, float rate) { audioRate[track] = rate; }

  public boolean isExporting() { return vm != null && vm.getGlobalFloat(G_WVOUT_ACTIVE) > 0.5; }
  public void startExport(String filePath) { if (vm != null) { vm.setGlobalString(G_WVOUT_FILE, filePath); vm.setGlobalFloat(G_WVOUT_ACTIVE, 1.0); } }
  public void stopExport() { if (vm != null) vm.setGlobalFloat(G_WVOUT_ACTIVE, 0.0); }
  public void syncActiveClipToLibrary(int track) {}
  public void loadSynthPreset(int track, org.chuck.deluge.model.SynthTrackModel model) {}
  public void loadClip(int track, int clipIdx) { setCurrentClip(track, clipIdx); }
  public void clearPattern() { for (int i = 0; i < PATTERN_SIZE; i++) pattern[i] = 0; }
  
  private final String[] samplePaths = new String[TRACKS];
  private final String[] dx7Patch = new String[TRACKS];

  public void setSamplePath(int track, String path) {
    samplePaths[track] = path;
    // In ChucK mode, this might trigger a reload, but for now we'll just store it
  }

  public String getSamplePath(int track) {
    return samplePaths[track];
  }

  /**
   * Set the play range for a sample within a voice/track.
   * The stepStart/stepEnd arrays are used by DelugeEngineDSL.kit_shred() as normalized
   * 0..1 positions to compute the actual sample start and end positions.
   * Fills all steps for this track (zone is per-voice, not per-step).
   *
   * @param track  the voice index (0..TRACKS-1)
   * @param start  normalized start position (0.0 = beginning)
   * @param end    normalized end position (1.0 = full length)
   */
  public void setSampleRange(int track, float start, float end) {
    if (track < 0 || track >= TRACKS) return;
    float s = Math.max(0.0f, Math.min(1.0f, start));
    float e = Math.max(0.0f, Math.min(1.0f, end));
    int base = track * STEPS;
    for (int step = 0; step < STEPS; step++) {
      stepStart[base + step] = s;
      stepEnd[base + step] = e;
    }
  }

  /**
   * Compute normalized sample range from KitSound zone data and the resolved WAV file path.
   * Returns a float[2] = {startNorm, endNorm}, both in [0,1].
   * Returns {0.0, 1.0} if no zone data is set or the file can't be read.
   */
  public static float[] computeNormalizedRange(
      KitTrackModel.KitSound snd, String resolvedPath) {
    float startNorm = 0.0f;
    float endNorm = 1.0f;
    boolean hasZone = false;

    // Determine the end position
    if (snd.getEndSamplePos() > 0) {
      hasZone = true;
      java.io.File wavFile = new java.io.File(resolvedPath);
      if (wavFile.exists()) {
        long dataSize = wavFile.length() - 44; // skip WAV header
        if (dataSize > 0) {
          float totalSamples = dataSize / 2.0f; // 16-bit mono
          endNorm = Math.min(1.0f, snd.getEndSamplePos() / totalSamples);
        }
      }
    } else if (snd.getEndMs() > 0.0f) {
      hasZone = true;
      java.io.File wavFile = new java.io.File(resolvedPath);
      if (wavFile.exists()) {
        long dataSize = wavFile.length() - 44;
        if (dataSize > 0) {
          float totalSamples = dataSize / 2.0f;
          float endSamplePos = (snd.getEndMs() / 1000.0f) * 44100.0f;
          endNorm = Math.min(1.0f, endSamplePos / totalSamples);
        }
      }
    }

    // Determine the start position
    if (snd.getStartSamplePos() > 0) {
      hasZone = true;
      java.io.File wavFile = new java.io.File(resolvedPath);
      if (wavFile.exists()) {
        long dataSize = wavFile.length() - 44;
        if (dataSize > 0) {
          float totalSamples = dataSize / 2.0f;
          startNorm = snd.getStartSamplePos() / totalSamples;
        }
      }
    } else if (snd.getStartMs() > 0.0f) {
      hasZone = true;
      java.io.File wavFile = new java.io.File(resolvedPath);
      if (wavFile.exists()) {
        long dataSize = wavFile.length() - 44;
        if (dataSize > 0) {
          float totalSamples = dataSize / 2.0f;
          float startSamplePos = (snd.getStartMs() / 1000.0f) * 44100.0f;
          startNorm = startSamplePos / totalSamples;
        }
      }
    }

    return hasZone ? new float[]{startNorm, endNorm} : new float[]{0.0f, 1.0f};
  }

  /**
   * Compute normalized sample range from AudioClip startSamplePos/endSamplePos (absolute sample positions)
   * and the resolved WAV file path.
   * Returns a float[2] = {startNorm, endNorm}, both in [0,1].
   * Returns {0.0, 1.0} if no zone data is set or the file can't be read.
   */
  public static float[] computeAudioClipRange(
      AudioTrackModel.AudioClip clip, String resolvedPath) {
    float startNorm = 0.0f;
    float endNorm = 1.0f;
    boolean hasZone = false;

    java.io.File wavFile = new java.io.File(resolvedPath);
    if (!wavFile.exists()) return new float[]{0.0f, 1.0f};
    long dataSize = wavFile.length() - 44;
    if (dataSize <= 0) return new float[]{0.0f, 1.0f};
    float totalSamples = dataSize / 2.0f;

    if (clip.getEndSamplePos() > 0) {
      hasZone = true;
      endNorm = Math.min(1.0f, clip.getEndSamplePos() / totalSamples);
    }
    if (clip.getStartSamplePos() > 0) {
      hasZone = true;
      startNorm = clip.getStartSamplePos() / totalSamples;
    }

    return hasZone ? new float[]{startNorm, endNorm} : new float[]{0.0f, 1.0f};
  }

  public String getDx7Patch(int track) { return dx7Patch[track]; }
  public void setDx7Patch(int track, String hex) { dx7Patch[track] = hex; }

  // Accessors for Pure Java Engine
  public int[] getPatternRaw() { return pattern; }
  public int[] getPitchRaw() { return pitch; }
  public float[] getVelocityRaw() { return velocity; }
  public float[] getProbabilityRaw() { return probability; }
  public float[] getGateRaw() { return gate; }
  public int[] getTrackTypeRaw() { return trackType; }
  public float[] getTrackLevelRaw() { return trackLevel; }
  public int[] getMuteRaw() { return mute; }
  public float[] getTrackFilterFreqRaw() { return filter; }
  public float[] getEnvRaw() { return env; }
  public float[] getLfoRateRaw() { return lfoRate; }
  public int[] getLfoTypeRaw() { return lfoType; }
  public float[] getLfoDepthRaw() { return lfoDepth; }
  public int[] getLfoTargetRaw() { return lfoTarget; }
  public int[] getLfoTrackRaw() { return lfoTrack; }

  // ── Java engine transport accessors ──
  public int getPlayState() { return javaPlayState; }
  public void setPlayState(int state) {
    this.javaPlayState = state;
    if (vm != null) vm.setGlobalInt(G_PLAY, (long) state);
  }
  public int getCurrentStep() { return javaCurrentStep; }
  public void setCurrentStep(int step) {
    this.javaCurrentStep = step;
    if (vm != null) vm.setGlobalInt(G_CURRENT_STEP, (long) step);
  }
  public long getStutterOn() { return vm != null ? vm.getGlobalInt(G_STUTTER_ON) : 0L; }
  public double getStutterDiv() { return vm != null ? vm.getGlobalFloat(G_STUTTER_DIV) : 1.0; }

  /**
   * Process pending clip launches at bar boundary.
   * Reads G_LAUNCH_QUEUE and applies switches to G_CURRENT_CLIP.
   */
  public void processLaunchQueue() {
    for (int t = 0; t < TRACKS; t++) {
      int q = launchQueue[t];
      if (q >= 0) {
        currentClip[t] = q;
        launchQueue[t] = -1;
      }
    }
  }

  /**
   * Get the step data pattern value for a track, step, and clip index.
   * Clip 0 reads from the primary pattern array; clip > 0 reads from clip arrays
   * (created lazily via getClipArray).
   */
  public int getPatternAtClip(int track, int step, int clipIdx) {
    if (clipIdx <= 0) return pattern[track * STEPS + step];
    if (vm == null) return pattern[track * STEPS + step]; // fallback
    var arr = (org.chuck.core.ChuckArray) vm.getGlobalObject(G_PATTERN + "_C" + clipIdx);
    if (arr != null) return (int) arr.getInt(track * STEPS + step);
    return pattern[track * STEPS + step];
  }

  public int getPitchAtClip(int track, int step, int clipIdx) {
    if (clipIdx <= 0) return pitch[track * STEPS + step];
    if (vm == null) return pitch[track * STEPS + step];
    var arr = (org.chuck.core.ChuckArray) vm.getGlobalObject(G_PITCH + "_C" + clipIdx);
    if (arr != null) return (int) arr.getInt(track * STEPS + step);
    return pitch[track * STEPS + step];
  }

  public float getVelocityAtClip(int track, int step, int clipIdx) {
    if (clipIdx <= 0) return velocity[track * STEPS + step];
    if (vm == null) return velocity[track * STEPS + step];
    var arr = (org.chuck.core.ChuckArray) vm.getGlobalObject(G_VELOCITY + "_C" + clipIdx);
    if (arr != null) return (float) arr.getFloat(track * STEPS + step);
    return velocity[track * STEPS + step];
  }

  public float getProbabilityAtClip(int track, int step, int clipIdx) {
    if (clipIdx <= 0) return probability[track * STEPS + step];
    if (vm == null) return probability[track * STEPS + step];
    var arr = (org.chuck.core.ChuckArray) vm.getGlobalObject(G_PROBABILITY + "_C" + clipIdx);
    if (arr != null) return (float) arr.getFloat(track * STEPS + step);
    return probability[track * STEPS + step];
  }

  public void setRecording(boolean r) { this.recording = r; }
  public boolean isRecording() { return recording; }
  public boolean isUseJavaEngine() { return true; }

  public void setDelaySend(int track, float v) { delaySend[track] = v; }
  public float getDelaySend(int track) { return delaySend[track]; }
  public void setReverbSend(int track, float v) { reverbSend[track] = v; }
  public float getReverbSend(int track) { return reverbSend[track]; }
}
