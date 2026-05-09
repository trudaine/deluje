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
 * <h2>Inner Data Classes</h2>
 * The arrays are grouped into logical inner static classes to keep the file manageable:
 * <ul>
 *   <li>{@link StepData} — 22 per-step arrays of size PATTERN_SIZE (pattern, velocity, gate, pitch, ...)</li>
 *   <li>{@link TrackData} — per-track arrays (type, level, mute, filter, length, clip state)</li>
 *   <li>{@link SynthData} — per-track synth parameters (osc mix, unison, FM feedback, envelopes, LFO)</li>
 *   <li>{@link KitData} — per-track kit parameters (ADSR, pitch, reverse, mute group, EQ, FX)</li>
 *   <li>{@link AudioData} — per-track audio clip state (record, play, loop, rate)</li>
 * </ul>
 * Each inner class has its own {@code initDefaults()} and {@code register(ChuckVM)} methods.
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

  // ═══════════════════════════════════════════════════════════════════════
  //  Global Name Constants
  //  These remain directly on BridgeContract so all 26+ importing files
  //  continue to use BridgeContract.G_* without any import changes.
  // ═══════════════════════════════════════════════════════════════════════

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
  // ── Voice/engine mapping ──────────────────────────────────────────────
  public static final String G_SYNTH_BASE = "g_synth_base";
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
  public static final String G_RELOAD = "g_reload";
  public static final String G_LOAD_TRIGGER = "g_load_trigger";
  public static final String TICK_EVENT = "tick_event";
  public static final String E_TICK = "tick_event";
  // ── Extended reverb globals ─────────────────────────────────────────────
  public static final String G_REVERB_WIDTH = "g_reverb_width";
  public static final String G_REVERB_HPF = "g_reverb_hpf";
  public static final String G_REVERB_PAN = "g_reverb_pan";
  public static final String G_REVERB_MODEL = "g_reverb_model";
  public static final String G_REVERB_COMP_ATTACK = "g_reverb_comp_attack";
  public static final String G_REVERB_COMP_RELEASE = "g_reverb_comp_release";
  public static final String G_REVERB_COMP_SYNC_LEVEL = "g_reverb_comp_sync_level";
  public static final String G_REVERB_COMP_HPF = "g_reverb_comp_hpf";
  public static final String G_REVERB_COMP_BLEND = "g_reverb_comp_blend";
  // ── Extended delay globals ──────────────────────────────────────────────
  public static final String G_DELAY_PINGPONG = "g_delay_pingpong";
  public static final String G_DELAY_ANALOG = "g_delay_analog";
  public static final String G_DELAY_SYNC_LEVEL = "g_delay_sync_level";
  public static final String G_DELAY_SYNC_TYPE = "g_delay_sync_type";
  // ── Sidechain globals ───────────────────────────────────────────────────
  public static final String G_SIDECHAIN_ATTACK = "g_sidechain_attack";
  public static final String G_SIDECHAIN_RELEASE = "g_sidechain_release";
  public static final String G_SIDECHAIN_SYNC_LEVEL = "g_sidechain_sync_level";
  public static final String G_SIDECHAIN_SYNC_TYPE = "g_sidechain_sync_type";
  // ── Master compressor globals (extended) ─────────────────────────────────
  public static final String G_MASTER_COMP_ATTACK = "g_master_comp_attack";
  public static final String G_MASTER_COMP_RELEASE = "g_master_comp_release";
  public static final String G_MASTER_COMP_RATIO = "g_master_comp_ratio";
  public static final String G_MASTER_COMP = "g_master_comp"; // threshold (existing)
  // ── Transpose / humanize ─────────────────────────────────────────────────
  public static final String G_TRANSPOSE = "g_transpose";
  public static final String G_HUMANIZE = "g_humanize";
  // ── SongParams ──────────────────────────────────────────────────────────
  public static final String G_SP_VOLUME = "g_sp_volume";
  public static final String G_SP_PAN = "g_sp_pan";
  public static final String G_SP_REVERB_AMOUNT = "g_sp_reverb_amount";
  public static final String G_SP_DELAY_RATE = "g_sp_delay_rate";
  public static final String G_SP_DELAY_FEEDBACK = "g_sp_delay_feedback";
  public static final String G_SP_SIDECHAIN_SHAPE = "g_sp_sidechain_shape";
  public static final String G_SP_STUTTER_RATE = "g_sp_stutter_rate";
  public static final String G_SP_SAMPLE_RATE_REDUCTION = "g_sp_srr";
  public static final String G_SP_BITCRUSH = "g_sp_bitcrush";
  public static final String G_SP_MOD_FX_RATE = "g_sp_mod_fx_rate";
  public static final String G_SP_MOD_FX_DEPTH = "g_sp_mod_fx_depth";
  public static final String G_SP_MOD_FX_OFFSET = "g_sp_mod_fx_offset";
  public static final String G_SP_MOD_FX_FEEDBACK = "g_sp_mod_fx_feedback";
  public static final String G_SP_COMPRESSOR_THRESHOLD = "g_sp_comp_threshold";
  public static final String G_SP_LPF_MORPH = "g_sp_lpf_morph";
  public static final String G_SP_HPF_MORPH = "g_sp_hpf_morph";
  public static final String G_SP_LPF_FREQ = "g_sp_lpf_freq";
  public static final String G_SP_LPF_RES = "g_sp_lpf_res";
  public static final String G_SP_HPF_FREQ = "g_sp_hpf_freq";
  public static final String G_SP_HPF_RES = "g_sp_hpf_res";
  public static final String G_SP_EQ_BASS = "g_sp_eq_bass";
  public static final String G_SP_EQ_TREBLE = "g_sp_eq_treble";
  public static final String G_SP_EQ_BASS_FREQ = "g_sp_eq_bass_freq";
  public static final String G_SP_EQ_TREBLE_FREQ = "g_sp_eq_treble_freq";
  // ── Scales / mode notes ─────────────────────────────────────────────────
  public static final String G_USER_SCALE = "g_user_scale";
  public static final String G_DISABLED_PRESET_SCALES = "g_disabled_preset_scales";
  public static final String G_MODE_NOTES = "g_mode_notes";

  public static final String G_DELAY_IN = "g_delay_in";
  public static final String G_REVERB_IN = "g_reverb_in";
  public static final String G_SYNTH_BUS = "g_synth_bus";
  public static final String G_ARP_ON = "g_arp_on";
  public static final String G_ARP_RATE = "g_arp_rate";
  public static final String G_ARP_OCTAVE = "g_arp_octave";
  public static final String G_ARP_MODE = "g_arp_mode";
  public static final String G_FM_RATIO = "g_fm_ratio";
  public static final String G_FM_AMOUNT = "g_fm_amount";
  public static final String G_PREVIEW_TRACK = "g_preview_track";
  public static final String G_PREVIEW_PITCH = "g_preview_pitch";
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

  // ───────────────────────────────────────────────────────────────────────
  //  StepData — 22 arrays
  // ───────────────────────────────────────────────────────────────────────

  static final class StepData {
    final int[] pattern = new int[PATTERN_SIZE];
    final float[] velocity = new float[PATTERN_SIZE];
    final float[] gate = new float[PATTERN_SIZE];
    final int[] pitch = new int[PATTERN_SIZE];
    final float[] probability = new float[PATTERN_SIZE];
    final float[] stepFilter = new float[PATTERN_SIZE];
    final float[] stepRes = new float[PATTERN_SIZE];
    final int[] stepFilterMode = new int[PATTERN_SIZE];
    final float[] stepPan = new float[PATTERN_SIZE];
    final float[] stepDelay = new float[PATTERN_SIZE];
    final float[] stepReverb = new float[PATTERN_SIZE];
    final float[] stepMod = new float[PATTERN_SIZE];
    final float[] stepStart = new float[PATTERN_SIZE];
    final float[] stepEnd = new float[PATTERN_SIZE];
    final float[] stepHpfFreq = new float[PATTERN_SIZE];
    final float[] stepHpfRes = new float[PATTERN_SIZE];
    final float[] stepModRate = new float[PATTERN_SIZE];
    final float[] stepModDepth = new float[PATTERN_SIZE];
    final float[] stepOscAVol = new float[PATTERN_SIZE];
    final float[] stepOscBVol = new float[PATTERN_SIZE];
    final float[] stepNoiseVol = new float[PATTERN_SIZE];
    final float[] stepPitch = new float[PATTERN_SIZE];

    void initDefaults() {
      for (int i = 0; i < PATTERN_SIZE; i++) {
        pattern[i] = 0;
        velocity[i] = 0.8f;
        gate[i] = 0.9f;
        pitch[i] = 0;
        probability[i] = 1f;
        stepFilter[i] = 0f;
        stepRes[i] = 0f;
        stepFilterMode[i] = -1;
        stepPan[i] = 0f;
        stepDelay[i] = 0f;
        stepReverb[i] = 0f;
        stepMod[i] = 0f;
        stepStart[i] = 0f;
        stepEnd[i] = 1f;
        stepHpfFreq[i] = 0f;
        stepHpfRes[i] = 0f;
        stepModRate[i] = 0f;
        stepModDepth[i] = 0f;
        stepOscAVol[i] = 1f;
        stepOscBVol[i] = 1f;
        stepNoiseVol[i] = 1f;
        stepPitch[i] = 0f;
      }
    }

    void register(ChuckVM vm) {
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
    }
  }

  // ───────────────────────────────────────────────────────────────────────
  //  TrackData — 13 arrays
  // ───────────────────────────────────────────────────────────────────────

  static final class TrackData {
    final int[] trackType = new int[TRACKS];
    final int[] oscType = new int[TRACKS];
    final float[] trackLevel = new float[TRACKS];
    final int[] mute = new int[TRACKS];
    final float[] filter = new float[TRACKS * 2];
    final int[] filterMode = new int[TRACKS];
    final float[] filterMorph = new float[TRACKS];
    final float[] delaySend = new float[TRACKS];
    final float[] reverbSend = new float[TRACKS];
    final int[] trackLength = new int[TRACKS];
    final int[] currentClip = new int[TRACKS];
    final int[] clipCount = new int[TRACKS];
    final int[] launchQueue = new int[TRACKS];

    void initDefaults() {
      for (int t = 0; t < TRACKS; t++) {
        trackType[t] = 0;
        oscType[t] = 0;
        trackLevel[t] = 0.7f;
        mute[t] = 0;
        filter[t * 2] = 1.0f;
        filter[t * 2 + 1] = 0.5f;
        filterMode[t] = 0;
        filterMorph[t] = 0f;
        delaySend[t] = 0f;
        reverbSend[t] = 0.15f;
        trackLength[t] = 16;
        currentClip[t] = 0;
        clipCount[t] = 0;
        launchQueue[t] = -1;
      }
    }

    void register(ChuckVM vm) {
      vm.setGlobalObject(G_TRACK_TYPE, new ChuckArray(trackType));
      vm.setGlobalObject(G_OSC_TYPE, new ChuckArray(oscType));
      vm.setGlobalObject(G_TRACK_LEVEL, new ChuckArray(trackLevel));
      vm.setGlobalObject(G_MUTE, new ChuckArray(mute));
      vm.setGlobalObject(G_FILTER, new ChuckArray(filter));
      vm.setGlobalObject(G_FILTER_MODE, new ChuckArray(filterMode));
      vm.setGlobalObject(G_FILTER_MORPH, new ChuckArray(filterMorph));
      vm.setGlobalObject(G_DELAY_SEND, new ChuckArray(delaySend));
      vm.setGlobalObject(G_REVERB_SEND, new ChuckArray(reverbSend));
      vm.setGlobalObject(G_TRACK_LENGTH, new ChuckArray(trackLength));
      vm.setGlobalObject(G_CURRENT_CLIP, new ChuckArray(currentClip));
      vm.setGlobalObject(G_CLIP_COUNT, new ChuckArray(clipCount));
      vm.setGlobalObject(G_LAUNCH_QUEUE, new ChuckArray(launchQueue));
    }
  }

  // ───────────────────────────────────────────────────────────────────────
  //  SynthData — 41 arrays
  // ───────────────────────────────────────────────────────────────────────

  static final class SynthData {
    final float[] env = new float[ENV_STRIDE];
    final float[] lfoRate = new float[LFO_COUNT];
    final int[] lfoType = new int[LFO_COUNT];
    final float[] lfoDepth = new float[LFO_COUNT];
    final int[] lfoTarget = new int[LFO_COUNT];
    final int[] lfoTrack = new int[LFO_COUNT];
    final float[] lfoValue = new float[LFO_COUNT];
    final int[] arpOn = new int[TRACKS];
    final float[] arpRate = new float[TRACKS];
    final int[] arpOctave = new int[TRACKS];
    final int[] arpMode = new int[TRACKS];
    final float[] fmRatio = new float[TRACKS];
    final float[] fmAmount = new float[TRACKS];
    final int[] synthAlgo = new int[TRACKS];
    final int[] synthMode = new int[TRACKS];
    final float[] hpfFreq = new float[TRACKS];
    final float[] hpfRes = new float[TRACKS];
    final int[] polyphony = new int[TRACKS];
    final float[] mod1Fb = new float[TRACKS];
    final float[] mod2Amt = new float[TRACKS];
    final float[] mod2Fb = new float[TRACKS];
    final float[] carrier1Fb = new float[TRACKS];
    final float[] carrier2Fb = new float[TRACKS];
    final float[] oscMix = new float[TRACKS];
    final float[] noiseVol = new float[TRACKS];
    final int[] unisonNum = new int[TRACKS];
    final float[] unisonDetune = new float[TRACKS];
    final int[] modFxType = new int[TRACKS];
    final float[] modFxRate = new float[TRACKS];
    final float[] modFxDepth = new float[TRACKS];
    final float[] modFxFeedback = new float[TRACKS];
    final float[] portamento = new float[TRACKS];
    final float[] eqBass = new float[TRACKS];
    final float[] eqTreble = new float[TRACKS];
    final float[] panArr = new float[TRACKS];
    final float[] stutterRateArr = new float[TRACKS];
    final float[] sampleRateReductionArr = new float[TRACKS];
    final float[] bitCrushArr = new float[TRACKS];
    final float[] compressorAttackArr = new float[TRACKS];
    final float[] compressorReleaseArr = new float[TRACKS];
    final int[] osc2Type = new int[TRACKS];

    void initDefaults() {
      for (int t = 0; t < TRACKS; t++) {
        arpOn[t] = 0;
        arpRate[t] = 1f;
        arpOctave[t] = 0;
        arpMode[t] = 0;
        fmRatio[t] = 1f;
        fmAmount[t] = 0f;
        synthAlgo[t] = 0;
        synthMode[t] = 0;
        hpfFreq[t] = 20f;
        hpfRes[t] = 0f;
        polyphony[t] = 0;
        mod1Fb[t] = 0f;
        mod2Amt[t] = 0f;
        mod2Fb[t] = 0f;
        carrier1Fb[t] = 0f;
        carrier2Fb[t] = 0f;
        oscMix[t] = 0.5f;
        noiseVol[t] = 0f;
        unisonNum[t] = 1;
        unisonDetune[t] = 0f;
        modFxType[t] = 0;
        modFxRate[t] = 0f;
        modFxDepth[t] = 0f;
        modFxFeedback[t] = 0f;
        portamento[t] = 0f;
        eqBass[t] = 0f;
        eqTreble[t] = 0f;
        panArr[t] = 0f;
        stutterRateArr[t] = 0f;
        sampleRateReductionArr[t] = 0f;
        bitCrushArr[t] = 0f;
        compressorAttackArr[t] = 0f;
        compressorReleaseArr[t] = 0f;
        osc2Type[t] = 0;
      }
      for (int e = 0; e < TRACKS * ENV_COUNT; e++) {
        env[e * ENV_PARAMS + 0] = 0.01f;
        env[e * ENV_PARAMS + 1] = 0.1f;
        env[e * ENV_PARAMS + 2] = 0.7f;
        env[e * ENV_PARAMS + 3] = 0.2f;
      }
      for (int l = 0; l < LFO_COUNT; l++) {
        lfoRate[l] = 1f;
        lfoType[l] = 0;
        lfoDepth[l] = 0f;
        lfoTarget[l] = 0;
        lfoTrack[l] = -1;
        lfoValue[l] = 0f;
      }
    }

    void register(ChuckVM vm) {
      vm.setGlobalObject(G_ENV, new ChuckArray(env));
      vm.setGlobalObject(G_LFO_RATE, new ChuckArray(lfoRate));
      vm.setGlobalObject(G_LFO_TYPE, new ChuckArray(lfoType));
      vm.setGlobalObject(G_LFO_DEPTH, new ChuckArray(lfoDepth));
      vm.setGlobalObject(G_LFO_TARGET, new ChuckArray(lfoTarget));
      vm.setGlobalObject(G_LFO_TRACK, new ChuckArray(lfoTrack));
      vm.setGlobalObject(G_LFO_VALUE, new ChuckArray(lfoValue));
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
    }
  }

  // ───────────────────────────────────────────────────────────────────────
  //  KitData — 27 arrays
  // ───────────────────────────────────────────────────────────────────────

  static final class KitData {
    final float[] kitAttack = new float[TRACKS];
    final float[] kitDecay = new float[TRACKS];
    final float[] kitSustain = new float[TRACKS];
    final float[] kitRelease = new float[TRACKS];
    final float[] kitPitch = new float[TRACKS];
    final int[] kitReverse = new int[TRACKS];
    final int[] kitMuteGroup = new int[TRACKS];
    final int[] kitLpfMode = new int[TRACKS];
    final float[] kitEqBass = new float[TRACKS];
    final float[] kitEqTreble = new float[TRACKS];
    final float[] kitSidechain = new float[TRACKS];
    final int[] kitModFxType = new int[TRACKS];
    final float[] kitHpfFreq = new float[TRACKS];
    final float[] kitHpfRes = new float[TRACKS];
    final int[] kitOsc2Type = new int[TRACKS];
    final int[] kitUnisonNum = new int[TRACKS];
    final float[] kitUnisonDetune = new float[TRACKS];
    final float[] kitCompressorAttackArr = new float[TRACKS];
    final float[] kitCompressorReleaseArr = new float[TRACKS];
    final float[] kitDelayRate = new float[TRACKS];
    final float[] kitDelayFb = new float[TRACKS];
    final float[] kitVolume = new float[TRACKS];
    final float[] kitPan = new float[TRACKS];
    final float[] kitNoiseVol = new float[TRACKS];
    final float[] kitStutterRate = new float[TRACKS];
    final float[] kitSampleRateRed = new float[TRACKS];
    final float[] kitBitCrush = new float[TRACKS];

    void initDefaults() {
      for (int t = 0; t < TRACKS; t++) {
        kitAttack[t] = 0.001f;
        kitDecay[t] = 0.1f;
        kitSustain[t] = 0.8f;
        kitRelease[t] = 0.2f;
        kitPitch[t] = 0f;
        kitReverse[t] = 0;
        kitMuteGroup[t] = 0;
        kitLpfMode[t] = 0;
        kitEqBass[t] = 0f;
        kitEqTreble[t] = 0f;
        kitSidechain[t] = 0f;
        kitModFxType[t] = 0;
        kitHpfFreq[t] = 20f;
        kitHpfRes[t] = 0f;
        kitOsc2Type[t] = 0;
        kitUnisonNum[t] = 1;
        kitUnisonDetune[t] = 0f;
        kitCompressorAttackArr[t] = 0f;
        kitCompressorReleaseArr[t] = 0f;
        kitDelayRate[t] = 0f;
        kitDelayFb[t] = 0f;
        kitVolume[t] = 0.5f;
        kitPan[t] = 0f;
        kitNoiseVol[t] = 0f;
        kitStutterRate[t] = 0f;
        kitSampleRateRed[t] = 0f;
        kitBitCrush[t] = 0f;
      }
    }

    void register(ChuckVM vm) {
      vm.setGlobalObject(G_KIT_ATTACK, new ChuckArray(kitAttack));
      vm.setGlobalObject(G_KIT_DECAY, new ChuckArray(kitDecay));
      vm.setGlobalObject(G_KIT_SUSTAIN, new ChuckArray(kitSustain));
      vm.setGlobalObject(G_KIT_RELEASE, new ChuckArray(kitRelease));
      vm.setGlobalObject(G_KIT_PITCH, new ChuckArray(kitPitch));
      vm.setGlobalObject(G_KIT_REVERSE, new ChuckArray(kitReverse));
      vm.setGlobalObject(G_KIT_MUTE_GROUP, new ChuckArray(kitMuteGroup));
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
    }
  }

  // ───────────────────────────────────────────────────────────────────────
  //  AudioData — 4 arrays
  // ───────────────────────────────────────────────────────────────────────

  static final class AudioData {
    final int[] audioRec = new int[TRACKS];
    final int[] audioPlay = new int[TRACKS];
    final int[] audioLoop = new int[TRACKS];
    final float[] audioRate = new float[TRACKS];

    void initDefaults() {
      for (int t = 0; t < TRACKS; t++) {
        audioRec[t] = 0;
        audioPlay[t] = 0;
        audioLoop[t] = 1;
        audioRate[t] = 1f;
      }
    }

    void register(ChuckVM vm) {
      vm.setGlobalObject(G_AUDIO_REC, new ChuckArray(audioRec));
      vm.setGlobalObject(G_AUDIO_PLAY, new ChuckArray(audioPlay));
      vm.setGlobalObject(G_AUDIO_LOOP, new ChuckArray(audioLoop));
      vm.setGlobalObject(G_AUDIO_RATE, new ChuckArray(audioRate));
    }
  }



  // ═══════════════════════════════════════════════════════════════════════
  //  Inner Data-Class Instances
  // ═══════════════════════════════════════════════════════════════════════

  private final StepData step = new StepData();
  private final TrackData track = new TrackData();
  private final SynthData synth = new SynthData();
  private final KitData kit = new KitData();
  private final AudioData audio = new AudioData();

  // ── Transport state (Java engine) ──────────────────────────────────────

  private volatile int javaPlayState = 0;
  private volatile int javaCurrentStep = -1;

  // ── Scalar state (shared between UI and engine) ────────────────────────

  private float masterVol = 1.0f;
  private float masterPan = 0.0f;
  private float delayTime = 0.375f;
  private float delayFb = 0.4f;
  private float reverbRoom = 0.6f;
  private float reverbDamp = 0.5f;
  // ── Extended reverb scalars ──
  private float reverbWidth = 0.5f;
  private float reverbHpf = 0.0f;
  private float reverbPan = 0.0f;
  private int reverbModel = 0; // 0=FREEVERB, 1=MUTABLE, 2=DIGITAL
  private float reverbCompAttack = 0.0f;
  private float reverbCompRelease = 0.0f;
  private int reverbCompSyncLevel = 0;
  private float reverbCompHpf = 0.0f;
  private float reverbCompBlend = 0.5f;
  // ── Extended delay scalars ──
  private int delayPingPong = 0;
  private int delayAnalog = 0;
  private int delaySyncLevel = 0;
  private int delaySyncType = 0;
  // ── Sidechain scalars ──
  private float sidechainAttack = 0.0f;
  private float sidechainRelease = 0.0f;
  private int sidechainSyncLevel = 0;
  private int sidechainSyncType = 0;
  // ── Master compressor scalars ──
  private float masterCompAttack = 0.0f;
  private float masterCompRelease = 0.0f;
  private float masterCompRatio = 0.0f;
  // ── Transpose / humanize ──
  private int transpose = 0;
  private float humanize = 0.0f;
  // ── SongParams scalars ──
  private float spVolume = 1.0f;
  private float spPan = 0.0f;
  private float spReverbAmount = 0.5f;
  private float spDelayRate = 0.0f;
  private float spDelayFeedback = 0.0f;
  private float spSidechainShape = 0.5f;
  private float spStutterRate = 0.0f;
  private float spSampleRateReduction = 0.0f;
  private float spBitCrush = 0.0f;
  private float spModFxRate = 0.0f;
  private float spModFxDepth = 0.0f;
  private float spModFxOffset = 0.0f;
  private float spModFxFeedback = 0.0f;
  private float spCompressorThreshold = 0.0f;
  private float spLpfMorph = 0.0f;
  private float spHpfMorph = 0.0f;
  private float spLpfFrequency = 20000.0f;
  private float spLpfResonance = 0.0f;
  private float spHpfFrequency = 20.0f;
  private float spHpfResonance = 0.0f;
  private float spEqBass = 0.0f;
  private float spEqTreble = 0.0f;
  private float spEqBassFrequency = 0.0f;
  private float spEqTrebleFrequency = 0.0f;
  // ── Scales scalars ──
  private int userScale = 0;
  private int disabledPresetScales = 0;
  private final int[] modeNotes = new int[12]; // boolean array packed as int[12] (0 or 1)

  private double bpm = 120.0;
  private double swing = 0.5;

  private ChuckVM vm;
  private boolean recording = false;

  private final String[] samplePaths = new String[TRACKS];
  private final String[] dx7Patch = new String[TRACKS];

  // ── Constructor ────────────────────────────────────────────────────────

  public BridgeContract() {
    step.initDefaults();
    track.initDefaults();
    synth.initDefaults();
    kit.initDefaults();
    audio.initDefaults();
  }

  // ── VM Registration ────────────────────────────────────────────────────

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
    vm.setGlobalFloat(G_PREVIEW_PITCH, 60.0f);
    vm.setGlobalObject(E_PREVIEW, new org.chuck.core.ChuckEvent());
    vm.setGlobalObject(E_SIDECHAIN, new org.chuck.core.ChuckEvent());
    vm.setGlobalObject(G_LOAD_TRIGGER, new org.chuck.core.ChuckEvent());
    vm.setGlobalObject(TICK_EVENT, new org.chuck.core.ChuckEvent());
    step.register(vm);
    track.register(vm);
    synth.register(vm);
    kit.register(vm);
    audio.register(vm);
    vm.setGlobalInt(G_QUEUE_STEP, 0L);
    vm.setGlobalObject(G_DELAY_IN, new org.chuck.audio.util.Gain());
    vm.setGlobalObject(G_REVERB_IN, new org.chuck.audio.util.Gain());
    vm.setGlobalObject(G_SYNTH_BUS, new org.chuck.audio.util.Gain());
    vm.setGlobalObject(G_AUDIO_BUS, new org.chuck.audio.util.Gain());
    vm.setGlobalFloat(G_WVOUT_ACTIVE, 0.0);
    vm.setGlobalString(G_WVOUT_FILE, "");

    // ── Extended reverb scalars ──
    vm.setGlobalFloat(G_REVERB_WIDTH, (double) reverbWidth);
    vm.setGlobalFloat(G_REVERB_HPF, (double) reverbHpf);
    vm.setGlobalFloat(G_REVERB_PAN, (double) reverbPan);
    vm.setGlobalInt(G_REVERB_MODEL, (long) reverbModel);
    vm.setGlobalFloat(G_REVERB_COMP_ATTACK, (double) reverbCompAttack);
    vm.setGlobalFloat(G_REVERB_COMP_RELEASE, (double) reverbCompRelease);
    vm.setGlobalInt(G_REVERB_COMP_SYNC_LEVEL, (long) reverbCompSyncLevel);
    vm.setGlobalFloat(G_REVERB_COMP_HPF, (double) reverbCompHpf);
    vm.setGlobalFloat(G_REVERB_COMP_BLEND, (double) reverbCompBlend);

    // ── Extended delay scalars ──
    vm.setGlobalInt(G_DELAY_PINGPONG, (long) delayPingPong);
    vm.setGlobalInt(G_DELAY_ANALOG, (long) delayAnalog);
    vm.setGlobalInt(G_DELAY_SYNC_LEVEL, (long) delaySyncLevel);
    vm.setGlobalInt(G_DELAY_SYNC_TYPE, (long) delaySyncType);

    // ── Sidechain scalars ──
    vm.setGlobalFloat(G_SIDECHAIN_ATTACK, (double) sidechainAttack);
    vm.setGlobalFloat(G_SIDECHAIN_RELEASE, (double) sidechainRelease);
    vm.setGlobalInt(G_SIDECHAIN_SYNC_LEVEL, (long) sidechainSyncLevel);
    vm.setGlobalInt(G_SIDECHAIN_SYNC_TYPE, (long) sidechainSyncType);

    // ── Master compressor scalars ──
    vm.setGlobalFloat(G_MASTER_COMP_ATTACK, (double) masterCompAttack);
    vm.setGlobalFloat(G_MASTER_COMP_RELEASE, (double) masterCompRelease);
    vm.setGlobalFloat(G_MASTER_COMP_RATIO, (double) masterCompRatio);

    // ── Transpose / humanize ──
    vm.setGlobalInt(G_TRANSPOSE, (long) transpose);
    vm.setGlobalFloat(G_HUMANIZE, (double) humanize);

    // ── SongParams scalars ──
    vm.setGlobalFloat(G_SP_VOLUME, (double) spVolume);
    vm.setGlobalFloat(G_SP_PAN, (double) spPan);
    vm.setGlobalFloat(G_SP_REVERB_AMOUNT, (double) spReverbAmount);
    vm.setGlobalFloat(G_SP_DELAY_RATE, (double) spDelayRate);
    vm.setGlobalFloat(G_SP_DELAY_FEEDBACK, (double) spDelayFeedback);
    vm.setGlobalFloat(G_SP_SIDECHAIN_SHAPE, (double) spSidechainShape);
    vm.setGlobalFloat(G_SP_STUTTER_RATE, (double) spStutterRate);
    vm.setGlobalFloat(G_SP_SAMPLE_RATE_REDUCTION, (double) spSampleRateReduction);
    vm.setGlobalFloat(G_SP_BITCRUSH, (double) spBitCrush);
    vm.setGlobalFloat(G_SP_MOD_FX_RATE, (double) spModFxRate);
    vm.setGlobalFloat(G_SP_MOD_FX_DEPTH, (double) spModFxDepth);
    vm.setGlobalFloat(G_SP_MOD_FX_OFFSET, (double) spModFxOffset);
    vm.setGlobalFloat(G_SP_MOD_FX_FEEDBACK, (double) spModFxFeedback);
    vm.setGlobalFloat(G_SP_COMPRESSOR_THRESHOLD, (double) spCompressorThreshold);
    vm.setGlobalFloat(G_SP_LPF_MORPH, (double) spLpfMorph);
    vm.setGlobalFloat(G_SP_HPF_MORPH, (double) spHpfMorph);
    vm.setGlobalFloat(G_SP_LPF_FREQ, (double) spLpfFrequency);
    vm.setGlobalFloat(G_SP_LPF_RES, (double) spLpfResonance);
    vm.setGlobalFloat(G_SP_HPF_FREQ, (double) spHpfFrequency);
    vm.setGlobalFloat(G_SP_HPF_RES, (double) spHpfResonance);
    vm.setGlobalFloat(G_SP_EQ_BASS, (double) spEqBass);
    vm.setGlobalFloat(G_SP_EQ_TREBLE, (double) spEqTreble);
    vm.setGlobalFloat(G_SP_EQ_BASS_FREQ, (double) spEqBassFrequency);
    vm.setGlobalFloat(G_SP_EQ_TREBLE_FREQ, (double) spEqTrebleFrequency);

    // ── Scales / mode notes ──
    vm.setGlobalInt(G_USER_SCALE, (long) userScale);
    vm.setGlobalInt(G_DISABLED_PRESET_SCALES, (long) disabledPresetScales);
    for (int i = 0; i < 12; i++) {
      vm.setGlobalInt(G_MODE_NOTES + "_" + i, (long) modeNotes[i]);
    }
  }

  public ChuckVM getVm() { return vm; }
  public ChuckArray patternArray() { return (ChuckArray) vm.getGlobalObject(G_PATTERN); }

  // ── Step accessors ────────────────────────────────────────

  public void setStep(int track, int sidx, boolean active) { step.pattern[track * STEPS + sidx] = active ? 1 : 0; }
  public void setStep(int track, int sidx, boolean active, int clipIdx) {
    if (clipIdx == 0) {
      step.pattern[track * STEPS + sidx] = active ? 1 : 0;
    } else {
      ChuckArray ca = getClipArray(G_PATTERN, clipIdx);
      if (ca != null) ca.setInt(track * STEPS + sidx, active ? 1L : 0L);
    }
  }
  public void clearAllSteps() { for (int i = 0; i < step.pattern.length; i++) step.pattern[i] = 0; }
  public boolean getStep(int track, int sidx) { return step.pattern[track * STEPS + sidx] > 0; }
  public void setVelocity(int track, int sidx, double val) { step.velocity[track * STEPS + sidx] = (float) Math.max(0.0, Math.min(1.0, val)); }
  public void setVelocity(int track, int sidx, double val, int clipIdx) {
    float clamped = (float) Math.max(0.0, Math.min(1.0, val));
    if (clipIdx == 0) {
      step.velocity[track * STEPS + sidx] = clamped;
    } else {
      ChuckArray ca = getClipArray(G_VELOCITY, clipIdx);
      if (ca != null) ca.setFloat(track * STEPS + sidx, val);
    }
  }
  public double getVelocity(int track, int sidx) { return step.velocity[track * STEPS + sidx]; }
  public void setGate(int track, int sidx, double val) { step.gate[track * STEPS + sidx] = (float) val; }
  public double getGate(int track, int sidx) { return step.gate[track * STEPS + sidx]; }
  public void setPitch(int track, int sidx, int p) { step.pitch[track * STEPS + sidx] = p; }
  public int getPitch(int track, int sidx) { return step.pitch[track * STEPS + sidx]; }
  public void setStepProbability(int track, int sidx, double val) { step.probability[track * STEPS + sidx] = (float) val; }
  public double getStepProbability(int track, int sidx) { return step.probability[track * STEPS + sidx]; }

  public ChuckArray getClipArray(String baseName, int clipIdx) {
    if (clipIdx <= 0) return (ChuckArray) vm.getGlobalObject(baseName);
    String name = baseName + "_C" + clipIdx;
    Object obj = vm.getGlobalObject(name);
    if (obj instanceof ChuckArray ca) return ca;
    ChuckArray base = (ChuckArray) vm.getGlobalObject(baseName);
    ChuckArray arr = "int".equals(base.elementTypeName) 
        ? new ChuckArray(new int[PATTERN_SIZE]) 
        : new ChuckArray(new float[PATTERN_SIZE]);
    vm.setGlobalObject(name, arr);
    return arr;
  }

  public int getClipCount(int t) { return track.clipCount[t]; }
  public void setClipCount(int t, int count) { track.clipCount[t] = count; }
  public int getCurrentClip(int t) { return track.currentClip[t]; }
  public void setCurrentClip(int t, int idx) { track.currentClip[t] = idx; }
  public int getLaunchQueue(int t) { return track.launchQueue[t]; }
  public void setLaunchQueue(int t, int clipIdx) { track.launchQueue[t] = clipIdx; }
  public int getQueueStep() { return vm != null ? (int) vm.getGlobalInt(G_QUEUE_STEP) : 0; }
  public void setQueueStep(int sidx) { if (vm != null) vm.setGlobalInt(G_QUEUE_STEP, (long) sidx); }

  public void setStepFilter(int track, int sidx, double val) { step.stepFilter[track * STEPS + sidx] = (float) val; }
  public double getStepFilter(int track, int sidx) { return step.stepFilter[track * STEPS + sidx]; }
  public void setStepRes(int track, int sidx, double val) { step.stepRes[track * STEPS + sidx] = (float) val; }
  public double getStepRes(int track, int sidx) { return step.stepRes[track * STEPS + sidx]; }
  public void setStepPan(int track, int sidx, double val) { step.stepPan[track * STEPS + sidx] = (float) val; }
  public double getStepPan(int track, int sidx) { return step.stepPan[track * STEPS + sidx]; }
  public void setStepDelay(int track, int sidx, double val) { step.stepDelay[track * STEPS + sidx] = (float) val; }
  public double getStepDelay(int track, int sidx) { return step.stepDelay[track * STEPS + sidx]; }
  public void setStepReverb(int track, int sidx, double val) { step.stepReverb[track * STEPS + sidx] = (float) val; }
  public double getStepReverb(int track, int sidx) { return step.stepReverb[track * STEPS + sidx]; }
  public void setStepHpfFreq(int track, int sidx, double val) { step.stepHpfFreq[track * STEPS + sidx] = (float) val; }
  public double getStepHpfFreq(int track, int sidx) { return step.stepHpfFreq[track * STEPS + sidx]; }
  public void setStepHpfRes(int track, int sidx, double val) { step.stepHpfRes[track * STEPS + sidx] = (float) val; }
  public double getStepHpfRes(int track, int sidx) { return step.stepHpfRes[track * STEPS + sidx]; }
  public void setStepModRate(int track, int sidx, double val) { step.stepModRate[track * STEPS + sidx] = (float) val; }
  public double getStepModRate(int track, int sidx) { return step.stepModRate[track * STEPS + sidx]; }
  public void setStepModDepth(int track, int sidx, double val) { step.stepModDepth[track * STEPS + sidx] = (float) val; }
  public double getStepModDepth(int track, int sidx) { return step.stepModDepth[track * STEPS + sidx]; }
  public void setStepOscAVol(int track, int sidx, double val) { step.stepOscAVol[track * STEPS + sidx] = (float) val; }
  public double getStepOscAVol(int track, int sidx) { return step.stepOscAVol[track * STEPS + sidx]; }
  public void setStepOscBVol(int track, int sidx, double val) { step.stepOscBVol[track * STEPS + sidx] = (float) val; }
  public double getStepOscBVol(int track, int sidx) { return step.stepOscBVol[track * STEPS + sidx]; }
  public void setStepNoiseVol(int track, int sidx, double val) { step.stepNoiseVol[track * STEPS + sidx] = (float) val; }
  public double getStepNoiseVol(int track, int sidx) { return step.stepNoiseVol[track * STEPS + sidx]; }
  public void setStepPitch(int track, int sidx, double val) { step.stepPitch[track * STEPS + sidx] = (float) val; }
  public double getStepPitch(int track, int sidx) { return step.stepPitch[track * STEPS + sidx]; }
  // ── Track accessors ──────────────────────────────────────

  public void setTrackLevel(int t, double val) { track.trackLevel[t] = (float) val; }
  public double getTrackLevel(int t) { return track.trackLevel[t]; }
  public void setMute(int t, boolean val) { 
    track.mute[t] = val ? 1 : 0; 
    if (vm != null) vm.setGlobalInt("g_mute_" + t, val ? 1L : 0L);
  }
  public boolean getMute(int t) { 
    if (vm != null) return vm.getGlobalInt("g_mute_" + t) > 0;
    return track.mute[t] > 0; 
  }
  public int getTrackType(int t) { return track.trackType[t]; }
  public void setTrackType(int t, int type) { track.trackType[t] = type; }
  public void setFilterFreq(int t, double val) { track.filter[t * 2] = (float) val; }
  public double getTrackFilterFreq(int t) { return track.filter[t * 2]; }
  public void setFilterRes(int t, double val) { track.filter[t * 2 + 1] = (float) val; }
  public double getTrackFilterRes(int t) { return track.filter[t * 2 + 1]; }
  public void setFilterMode(int t, int mode) { track.filterMode[t] = mode; }
  public void setFilterMorph(int t, double morph) { track.filterMorph[t] = (float) morph; }
  public void setEnv(int row, int envIndex, double a, double d, double s, double r) {
    int b = (row * ENV_COUNT + envIndex) * ENV_PARAMS;
    synth.env[b + 0] = (float) a; synth.env[b + 1] = (float) d; synth.env[b + 2] = (float) s; synth.env[b + 3] = (float) r;
  }
  public void setLfo(int lfoIndex, double rateHz, int waveType, double depth) {
    synth.lfoRate[lfoIndex] = (float) rateHz; synth.lfoType[lfoIndex] = waveType; synth.lfoDepth[lfoIndex] = (float) depth;
  }
  public void setLfoTarget(int lfoIndex, int target) { synth.lfoTarget[lfoIndex] = target; }
  public int getLfoTarget(int lfoIndex) { return synth.lfoTarget[lfoIndex]; }
  public void setLfoTrack(int lfoIndex, int t) { synth.lfoTrack[lfoIndex] = t; }
  public int getLfoTrack(int lfoIndex) { return synth.lfoTrack[lfoIndex]; }
  public void setTrackLength(int t, int steps) { track.trackLength[t] = steps; }
  public int getTrackLength(int t) { return track.trackLength[t]; }
  // ── Scalar state accessors ───────────────────────────────

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
  public void setMasterPan(double val) {
    this.masterPan = (float) val;
    if (vm != null) vm.setGlobalFloat(G_MASTER_PAN, val);
  }
  public double getMasterPan() { return masterPan; }
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

  // ── Extended reverb setters ───────────────────────────────
  public void setReverbWidth(double v) {
    this.reverbWidth = (float) v;
    if (vm != null) vm.setGlobalFloat(G_REVERB_WIDTH, v);
  }
  public double getReverbWidth() { return reverbWidth; }
  public void setReverbHpf(double v) {
    this.reverbHpf = (float) v;
    if (vm != null) vm.setGlobalFloat(G_REVERB_HPF, v);
  }
  public double getReverbHpf() { return reverbHpf; }
  public void setReverbPan2(double v) {
    this.reverbPan = (float) v;
    if (vm != null) vm.setGlobalFloat(G_REVERB_PAN, v);
  }
  public double getReverbPan2() { return reverbPan; }
  public void setReverbModel(int v) {
    this.reverbModel = v;
    if (vm != null) vm.setGlobalInt(G_REVERB_MODEL, (long) v);
  }
  public int getReverbModel() { return reverbModel; }
  public void setReverbCompAttack(double v) {
    this.reverbCompAttack = (float) v;
    if (vm != null) vm.setGlobalFloat(G_REVERB_COMP_ATTACK, v);
  }
  public double getReverbCompAttack() { return reverbCompAttack; }
  public void setReverbCompRelease(double v) {
    this.reverbCompRelease = (float) v;
    if (vm != null) vm.setGlobalFloat(G_REVERB_COMP_RELEASE, v);
  }
  public double getReverbCompRelease() { return reverbCompRelease; }
  public void setReverbCompSyncLevel(int v) {
    this.reverbCompSyncLevel = v;
    if (vm != null) vm.setGlobalInt(G_REVERB_COMP_SYNC_LEVEL, (long) v);
  }
  public int getReverbCompSyncLevel() { return reverbCompSyncLevel; }
  public void setReverbCompHpf(double v) {
    this.reverbCompHpf = (float) v;
    if (vm != null) vm.setGlobalFloat(G_REVERB_COMP_HPF, v);
  }
  public double getReverbCompHpf() { return reverbCompHpf; }
  public void setReverbCompBlend(double v) {
    this.reverbCompBlend = (float) v;
    if (vm != null) vm.setGlobalFloat(G_REVERB_COMP_BLEND, v);
  }
  public double getReverbCompBlend() { return reverbCompBlend; }

  // ── Extended delay setters ────────────────────────────────
  public void setDelayPingPong(int v) {
    this.delayPingPong = v;
    if (vm != null) vm.setGlobalInt(G_DELAY_PINGPONG, (long) v);
  }
  public int getDelayPingPong() { return delayPingPong; }
  public void setDelayAnalog(int v) {
    this.delayAnalog = v;
    if (vm != null) vm.setGlobalInt(G_DELAY_ANALOG, (long) v);
  }
  public int getDelayAnalog() { return delayAnalog; }
  public void setDelaySyncLevel(int v) {
    this.delaySyncLevel = v;
    if (vm != null) vm.setGlobalInt(G_DELAY_SYNC_LEVEL, (long) v);
  }
  public int getDelaySyncLevel() { return delaySyncLevel; }
  public void setDelaySyncType(int v) {
    this.delaySyncType = v;
    if (vm != null) vm.setGlobalInt(G_DELAY_SYNC_TYPE, (long) v);
  }
  public int getDelaySyncType() { return delaySyncType; }

  // ── Sidechain setters ──────────────────────────────────────
  public void setSidechainAttack(double v) {
    this.sidechainAttack = (float) v;
    if (vm != null) vm.setGlobalFloat(G_SIDECHAIN_ATTACK, v);
  }
  public double getSidechainAttack() { return sidechainAttack; }
  public void setSidechainRelease(double v) {
    this.sidechainRelease = (float) v;
    if (vm != null) vm.setGlobalFloat(G_SIDECHAIN_RELEASE, v);
  }
  public double getSidechainRelease() { return sidechainRelease; }
  public void setSidechainSyncLevel(int v) {
    this.sidechainSyncLevel = v;
    if (vm != null) vm.setGlobalInt(G_SIDECHAIN_SYNC_LEVEL, (long) v);
  }
  public int getSidechainSyncLevel() { return sidechainSyncLevel; }
  public void setSidechainSyncType(int v) {
    this.sidechainSyncType = v;
    if (vm != null) vm.setGlobalInt(G_SIDECHAIN_SYNC_TYPE, (long) v);
  }
  public int getSidechainSyncType() { return sidechainSyncType; }

  // ── Master compressor setters ──────────────────────────────
  public void setMasterCompAttack(double v) {
    this.masterCompAttack = (float) v;
    if (vm != null) vm.setGlobalFloat(G_MASTER_COMP_ATTACK, v);
  }
  public double getMasterCompAttack() { return masterCompAttack; }
  public void setMasterCompRelease(double v) {
    this.masterCompRelease = (float) v;
    if (vm != null) vm.setGlobalFloat(G_MASTER_COMP_RELEASE, v);
  }
  public double getMasterCompRelease() { return masterCompRelease; }
  public void setMasterCompRatio(double v) {
    this.masterCompRatio = (float) v;
    if (vm != null) vm.setGlobalFloat(G_MASTER_COMP_RATIO, v);
  }
  public double getMasterCompRatio() { return masterCompRatio; }

  // ── Transpose / humanize ───────────────────────────────────
  public void setTranspose(int v) {
    this.transpose = v;
    if (vm != null) vm.setGlobalInt(G_TRANSPOSE, (long) v);
  }
  public int getTranspose() { return transpose; }
  public void setHumanize(double v) {
    this.humanize = (float) v;
    if (vm != null) vm.setGlobalFloat(G_HUMANIZE, v);
  }
  public double getHumanize() { return humanize; }

  // ── SongParams setters ─────────────────────────────────────
  public void setSpVolume(double v) { this.spVolume = (float) v; if (vm != null) vm.setGlobalFloat(G_SP_VOLUME, v); }
  public double getSpVolume() { return spVolume; }
  public void setSpPan(double v) { this.spPan = (float) v; if (vm != null) vm.setGlobalFloat(G_SP_PAN, v); }
  public double getSpPan() { return spPan; }
  public void setSpReverbAmount(double v) { this.spReverbAmount = (float) v; if (vm != null) vm.setGlobalFloat(G_SP_REVERB_AMOUNT, v); }
  public double getSpReverbAmount() { return spReverbAmount; }
  public void setSpDelayRate(double v) { this.spDelayRate = (float) v; if (vm != null) vm.setGlobalFloat(G_SP_DELAY_RATE, v); }
  public double getSpDelayRate() { return spDelayRate; }
  public void setSpDelayFeedback(double v) { this.spDelayFeedback = (float) v; if (vm != null) vm.setGlobalFloat(G_SP_DELAY_FEEDBACK, v); }
  public double getSpDelayFeedback() { return spDelayFeedback; }
  public void setSpSidechainShape(double v) { this.spSidechainShape = (float) v; if (vm != null) vm.setGlobalFloat(G_SP_SIDECHAIN_SHAPE, v); }
  public double getSpSidechainShape() { return spSidechainShape; }
  public void setSpStutterRate(double v) { this.spStutterRate = (float) v; if (vm != null) vm.setGlobalFloat(G_SP_STUTTER_RATE, v); }
  public double getSpStutterRate() { return spStutterRate; }
  public void setSpSampleRateReduction(double v) { this.spSampleRateReduction = (float) v; if (vm != null) vm.setGlobalFloat(G_SP_SAMPLE_RATE_REDUCTION, v); }
  public double getSpSampleRateReduction() { return spSampleRateReduction; }
  public void setSpBitCrush(double v) { this.spBitCrush = (float) v; if (vm != null) vm.setGlobalFloat(G_SP_BITCRUSH, v); }
  public double getSpBitCrush() { return spBitCrush; }
  public void setSpModFxRate(double v) { this.spModFxRate = (float) v; if (vm != null) vm.setGlobalFloat(G_SP_MOD_FX_RATE, v); }
  public double getSpModFxRate() { return spModFxRate; }
  public void setSpModFxDepth(double v) { this.spModFxDepth = (float) v; if (vm != null) vm.setGlobalFloat(G_SP_MOD_FX_DEPTH, v); }
  public double getSpModFxDepth() { return spModFxDepth; }
  public void setSpModFxOffset(double v) { this.spModFxOffset = (float) v; if (vm != null) vm.setGlobalFloat(G_SP_MOD_FX_OFFSET, v); }
  public double getSpModFxOffset() { return spModFxOffset; }
  public void setSpModFxFeedback(double v) { this.spModFxFeedback = (float) v; if (vm != null) vm.setGlobalFloat(G_SP_MOD_FX_FEEDBACK, v); }
  public double getSpModFxFeedback() { return spModFxFeedback; }
  public void setSpCompressorThreshold(double v) { this.spCompressorThreshold = (float) v; if (vm != null) vm.setGlobalFloat(G_SP_COMPRESSOR_THRESHOLD, v); }
  public double getSpCompressorThreshold() { return spCompressorThreshold; }
  public void setSpLpfMorph(double v) { this.spLpfMorph = (float) v; if (vm != null) vm.setGlobalFloat(G_SP_LPF_MORPH, v); }
  public double getSpLpfMorph() { return spLpfMorph; }
  public void setSpHpfMorph(double v) { this.spHpfMorph = (float) v; if (vm != null) vm.setGlobalFloat(G_SP_HPF_MORPH, v); }
  public double getSpHpfMorph() { return spHpfMorph; }
  public void setSpLpfFrequency(double v) { this.spLpfFrequency = (float) v; if (vm != null) vm.setGlobalFloat(G_SP_LPF_FREQ, v); }
  public double getSpLpfFrequency() { return spLpfFrequency; }
  public void setSpLpfResonance(double v) { this.spLpfResonance = (float) v; if (vm != null) vm.setGlobalFloat(G_SP_LPF_RES, v); }
  public double getSpLpfResonance() { return spLpfResonance; }
  public void setSpHpfFrequency(double v) { this.spHpfFrequency = (float) v; if (vm != null) vm.setGlobalFloat(G_SP_HPF_FREQ, v); }
  public double getSpHpfFrequency() { return spHpfFrequency; }
  public void setSpHpfResonance(double v) { this.spHpfResonance = (float) v; if (vm != null) vm.setGlobalFloat(G_SP_HPF_RES, v); }
  public double getSpHpfResonance() { return spHpfResonance; }
  public void setSpEqBass(double v) { this.spEqBass = (float) v; if (vm != null) vm.setGlobalFloat(G_SP_EQ_BASS, v); }
  public double getSpEqBass() { return spEqBass; }
  public void setSpEqTreble(double v) { this.spEqTreble = (float) v; if (vm != null) vm.setGlobalFloat(G_SP_EQ_TREBLE, v); }
  public double getSpEqTreble() { return spEqTreble; }
  public void setSpEqBassFrequency(double v) { this.spEqBassFrequency = (float) v; if (vm != null) vm.setGlobalFloat(G_SP_EQ_BASS_FREQ, v); }
  public double getSpEqBassFrequency() { return spEqBassFrequency; }
  public void setSpEqTrebleFrequency(double v) { this.spEqTrebleFrequency = (float) v; if (vm != null) vm.setGlobalFloat(G_SP_EQ_TREBLE_FREQ, v); }
  public double getSpEqTrebleFrequency() { return spEqTrebleFrequency; }

  // ── Scales setters ──────────────────────────────────────────
  public void setUserScale(int v) {
    this.userScale = v;
    if (vm != null) vm.setGlobalInt(G_USER_SCALE, (long) v);
  }
  public int getUserScale() { return userScale; }
  public void setDisabledPresetScales(int v) {
    this.disabledPresetScales = v;
    if (vm != null) vm.setGlobalInt(G_DISABLED_PRESET_SCALES, (long) v);
  }
  public int getDisabledPresetScales() { return disabledPresetScales; }
  public void setModeNote(int semitone, boolean enabled) {
    if (semitone >= 0 && semitone < 12) {
      this.modeNotes[semitone] = enabled ? 1 : 0;
      if (vm != null) vm.setGlobalInt(G_MODE_NOTES + "_" + semitone, enabled ? 1L : 0L);
    }
  }
  public int getModeNote(int semitone) {
    if (semitone >= 0 && semitone < 12) return modeNotes[semitone];
    return 0;
  }
  public void setAllModeNotes(boolean[] notes) {
    if (notes == null || notes.length < 12) return;
    for (int i = 0; i < 12; i++) {
      this.modeNotes[i] = notes[i] ? 1 : 0;
      if (vm != null) vm.setGlobalInt(G_MODE_NOTES + "_" + i, notes[i] ? 1L : 0L);
    }
  }
  public int[] getModeNotesRaw() { return modeNotes; }
  public void setModeNotesRaw(int[] notes) {
    if (notes == null || notes.length < 12) return;
    for (int i = 0; i < 12; i++) {
      this.modeNotes[i] = notes[i];
      if (vm != null) vm.setGlobalInt(G_MODE_NOTES + "_" + i, (long) notes[i]);
    }
  }
  // ── Extended per-track synth accessors ───────────────────

  public void setOscMix(int track, float v) { synth.oscMix[track] = v; }
  public float getOscMix(int track) { return synth.oscMix[track]; }
  public void setNoiseVol(int track, float v) { synth.noiseVol[track] = v; }
  public float getNoiseVol(int track) { return synth.noiseVol[track]; }
  public void setUnisonNum(int track, int v) { synth.unisonNum[track] = v; }
  public int getUnisonNum(int track) { return synth.unisonNum[track]; }
  public void setUnisonDetune(int track, float v) { synth.unisonDetune[track] = v; }
  public float getUnisonDetune(int track) { return synth.unisonDetune[track]; }
  public void setModFxType(int track, int v) { synth.modFxType[track] = v; }
  public int getModFxType(int track) { return synth.modFxType[track]; }
  public void setModFxRate(int track, float v) { synth.modFxRate[track] = v; }
  public float getModFxRate(int track) { return synth.modFxRate[track]; }
  public void setModFxDepth(int track, float v) { synth.modFxDepth[track] = v; }
  public float getModFxDepth(int track) { return synth.modFxDepth[track]; }
  public void setModFxFeedback(int track, float v) { synth.modFxFeedback[track] = v; }
  public float getModFxFeedback(int track) { return synth.modFxFeedback[track]; }
  public void setPortamento(int track, float v) { synth.portamento[track] = v; }
  public float getPortamento(int track) { return synth.portamento[track]; }
  public void setEqBass(int track, float v) { synth.eqBass[track] = v; }
  public float getEqBass(int track) { return synth.eqBass[track]; }
  public void setEqTreble(int track, float v) { synth.eqTreble[track] = v; }
  public float getEqTreble(int track) { return synth.eqTreble[track]; }
  public void setTrackPan(int track, float v) { synth.panArr[track] = v; }
  public float getTrackPan(int track) { return synth.panArr[track]; }
  public void setStutterRate(int track, float v) { synth.stutterRateArr[track] = v; }
  public float getStutterRate(int track) { return synth.stutterRateArr[track]; }
  public void setSampleRateReduction(int track, float v) { synth.sampleRateReductionArr[track] = v; }
  public float getSampleRateReduction(int track) { return synth.sampleRateReductionArr[track]; }
  public void setBitCrush(int track, float v) { synth.bitCrushArr[track] = v; }
  public float getBitCrush(int track) { return synth.bitCrushArr[track]; }
  public void setCompAttack(int track, float v) { synth.compressorAttackArr[track] = v; }
  public float getCompAttack(int track) { return synth.compressorAttackArr[track]; }
  public void setCompRelease(int track, float v) { synth.compressorReleaseArr[track] = v; }
  public float getCompRelease(int track) { return synth.compressorReleaseArr[track]; }
  public void setOsc2Type(int track, int v) { synth.osc2Type[track] = v; }
  public int getOsc2Type(int track) { return synth.osc2Type[track]; }
  // ── Extended per-track kit accessors ─────────────────────

  public void setKitLpfMode(int track, int v) { kit.kitLpfMode[track] = v; }
  public int getKitLpfMode(int track) { return kit.kitLpfMode[track]; }
  public void setKitEqBass(int track, float v) { kit.kitEqBass[track] = v; }
  public float getKitEqBass(int track) { return kit.kitEqBass[track]; }
  public void setKitEqTreble(int track, float v) { kit.kitEqTreble[track] = v; }
  public float getKitEqTreble(int track) { return kit.kitEqTreble[track]; }
  public void setKitSidechain(int track, float v) { kit.kitSidechain[track] = v; }
  public float getKitSidechain(int track) { return kit.kitSidechain[track]; }
  public void setKitModFxType(int track, int v) { kit.kitModFxType[track] = v; }
  public int getKitModFxType(int track) { return kit.kitModFxType[track]; }
  public void setKitHpfFreq(int track, float v) { kit.kitHpfFreq[track] = v; }
  public float getKitHpfFreq(int track) { return kit.kitHpfFreq[track]; }
  public void setKitHpfRes(int track, float v) { kit.kitHpfRes[track] = v; }
  public float getKitHpfRes(int track) { return kit.kitHpfRes[track]; }
  public void setKitOsc2Type(int track, int v) { kit.kitOsc2Type[track] = v; }
  public int getKitOsc2Type(int track) { return kit.kitOsc2Type[track]; }
  public void setKitUnisonNum(int track, int v) { kit.kitUnisonNum[track] = v; }
  public int getKitUnisonNum(int track) { return kit.kitUnisonNum[track]; }
  public void setKitUnisonDetune(int track, float v) { kit.kitUnisonDetune[track] = v; }
  public float getKitUnisonDetune(int track) { return kit.kitUnisonDetune[track]; }
  public void setKitCompAttack(int track, float v) { kit.kitCompressorAttackArr[track] = v; }
  public float getKitCompAttack(int track) { return kit.kitCompressorAttackArr[track]; }
  public void setKitCompRelease(int track, float v) { kit.kitCompressorReleaseArr[track] = v; }
  public float getKitCompRelease(int track) { return kit.kitCompressorReleaseArr[track]; }
  public void setKitDelayRate(int track, float v) { kit.kitDelayRate[track] = v; }
  public float getKitDelayRate(int track) { return kit.kitDelayRate[track]; }
  public void setKitDelayFb(int track, float v) { kit.kitDelayFb[track] = v; }
  public float getKitDelayFb(int track) { return kit.kitDelayFb[track]; }
  public void setKitVolume(int track, float v) { kit.kitVolume[track] = v; }
  public float getKitVolume(int track) { return kit.kitVolume[track]; }
  public void setKitPan(int track, float v) { kit.kitPan[track] = v; }
  public float getKitPan(int track) { return kit.kitPan[track]; }
  public void setKitNoiseVol(int track, float v) { kit.kitNoiseVol[track] = v; }
  public float getKitNoiseVol(int track) { return kit.kitNoiseVol[track]; }
  public void setKitStutterRate(int track, float v) { kit.kitStutterRate[track] = v; }
  public float getKitStutterRate(int track) { return kit.kitStutterRate[track]; }
  public void setKitSampleRateRed(int track, float v) { kit.kitSampleRateRed[track] = v; }
  public float getKitSampleRateRed(int track) { return kit.kitSampleRateRed[track]; }
  public void setKitBitCrush(int track, float v) { kit.kitBitCrush[track] = v; }
  public float getKitBitCrush(int track) { return kit.kitBitCrush[track]; }
  // ── Arp / FM / synth mode accessors ──────────────────────

  public boolean getArpOn(int track) { return synth.arpOn[track] > 0; }
  public void setArpOn(int track, boolean on) { synth.arpOn[track] = on ? 1 : 0; }
  public double getArpRate(int track) { return synth.arpRate[track]; }
  public void setArpRate(int track, double rate) { synth.arpRate[track] = (float) rate; }
  public int getArpOctave(int track) { return synth.arpOctave[track]; }
  public void setArpOctave(int track, int oct) { synth.arpOctave[track] = oct; }
  public int getArpMode(int track) { return synth.arpMode[track]; }
  public void setArpMode(int track, int mode) { synth.arpMode[track] = mode; }
  public double getFmRatio(int track) { return synth.fmRatio[track]; }
  public void setFmRatio(int track, double r) { synth.fmRatio[track] = (float) r; }
  public double getFmAmount(int track) { return synth.fmAmount[track]; }
  public void setFmAmount(int track, double a) { synth.fmAmount[track] = (float) a; }
  public int getSynthAlgo(int track) { return synth.synthAlgo[track]; }
  public void setSynthAlgo(int track, int algo) { synth.synthAlgo[track] = algo; }
  public int getSynthMode(int track) { return synth.synthMode[track]; }
  public void setSynthMode(int track, int mode) { synth.synthMode[track] = mode; }
  public void setHpfFreq(int track, float freq) { synth.hpfFreq[track] = freq; }
  public float getHpfFreq(int track) { return synth.hpfFreq[track]; }
  public void setHpfRes(int track, float res) { synth.hpfRes[track] = res; }
  public float getHpfRes(int track) { return synth.hpfRes[track]; }
  public void setPolyphony(int track, int mode) { synth.polyphony[track] = mode; }
  public int getPolyphony(int track) { return synth.polyphony[track]; }
  public float getMod1Fb(int track) { return synth.mod1Fb[track]; }
  public void setMod1Fb(int track, float v) { synth.mod1Fb[track] = v; }
  public float getMod2Amt(int track) { return synth.mod2Amt[track]; }
  public void setMod2Amt(int track, float v) { synth.mod2Amt[track] = v; }
  public float getMod2Fb(int track) { return synth.mod2Fb[track]; }
  public void setMod2Fb(int track, float v) { synth.mod2Fb[track] = v; }
  public float getCarrier1Fb(int track) { return synth.carrier1Fb[track]; }
  public void setCarrier1Fb(int track, float v) { synth.carrier1Fb[track] = v; }
  public float getCarrier2Fb(int track) { return synth.carrier2Fb[track]; }
  public void setCarrier2Fb(int track, float v) { synth.carrier2Fb[track] = v; }
  // ── Audio track accessors ────────────────────────────────

  public int getAudioRec(int track) { return audio.audioRec[track]; }
  public void setAudioRec(int track, int state) { audio.audioRec[track] = state; }
  public int getAudioPlay(int track) { return audio.audioPlay[track]; }
  public void setAudioPlay(int track, int state) { audio.audioPlay[track] = state; }
  public int getAudioLoop(int track) { return audio.audioLoop[track]; }
  public void setAudioLoop(int track, int looping) { audio.audioLoop[track] = looping; }
  public float getAudioRate(int track) { return audio.audioRate[track]; }
  public void setAudioRate(int track, float rate) { audio.audioRate[track] = rate; }
  // ── Export / misc ────────────────────────────────────────

  public boolean isExporting() { return vm != null && vm.getGlobalFloat(G_WVOUT_ACTIVE) > 0.5; }
  public void startExport(String filePath) { if (vm != null) { vm.setGlobalString(G_WVOUT_FILE, filePath); vm.setGlobalFloat(G_WVOUT_ACTIVE, 1.0); } }
  public void stopExport() { if (vm != null) vm.setGlobalFloat(G_WVOUT_ACTIVE, 0.0); }
  public void syncActiveClipToLibrary(int track) {}
  public void loadSynthPreset(int track, org.chuck.deluge.model.SynthTrackModel model) {}
  public void loadClip(int track, int clipIdx) { setCurrentClip(track, clipIdx); }
  public void clearPattern() { for (int i = 0; i < PATTERN_SIZE; i++) step.pattern[i] = 0; }
  public void setSamplePath(int track, String path) { samplePaths[track] = path; }
  public String getSamplePath(int track) { return samplePaths[track]; }
  public void setSampleRange(int track, float start, float end) {
    if (track < 0 || track >= TRACKS) return;
    float s = Math.max(0.0f, Math.min(1.0f, start));
    float e = Math.max(0.0f, Math.min(1.0f, end));
    int base = track * STEPS;
    for (int si = 0; si < STEPS; si++) {
      step.stepStart[base + si] = s;
      step.stepEnd[base + si] = e;
    }
  }

  public static float[] computeNormalizedRange(
      KitTrackModel.KitSound snd, String resolvedPath) {
    float startNorm = 0.0f;
    float endNorm = 1.0f;
    boolean hasZone = false;
    if (snd.getEndSamplePos() > 0) {
      hasZone = true;
      java.io.File wavFile = new java.io.File(resolvedPath);
      if (wavFile.exists()) {
        long dataSize = wavFile.length() - 44;
        if (dataSize > 0) {
          float totalSamples = dataSize / 2.0f;
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
  public int[] getPatternRaw() { return step.pattern; }
  public int[] getPitchRaw() { return step.pitch; }
  public float[] getVelocityRaw() { return step.velocity; }
  public float[] getProbabilityRaw() { return step.probability; }
  public float[] getGateRaw() { return step.gate; }
  public int[] getTrackTypeRaw() { return track.trackType; }
  public float[] getTrackLevelRaw() { return track.trackLevel; }
  public int[] getMuteRaw() { return track.mute; }
  public float[] getTrackFilterFreqRaw() { return track.filter; }
  public float[] getEnvRaw() { return synth.env; }
  public float[] getLfoRateRaw() { return synth.lfoRate; }
  public int[] getLfoTypeRaw() { return synth.lfoType; }
  public float[] getLfoDepthRaw() { return synth.lfoDepth; }
  public int[] getLfoTargetRaw() { return synth.lfoTarget; }
  public int[] getLfoTrackRaw() { return synth.lfoTrack; }
  public int getPlayState() { return javaPlayState; }
  public void setPlayState(int state) {
    this.javaPlayState = state;
    if (vm != null) vm.setGlobalInt(G_PLAY, (long) state);
  }
  public int getCurrentStep() { return javaCurrentStep; }
  public void setCurrentStep(int v) {
    this.javaCurrentStep = v;
    if (vm != null) vm.setGlobalInt(G_CURRENT_STEP, (long) v);
  }
  public long getStutterOn() { return vm != null ? vm.getGlobalInt(G_STUTTER_ON) : 0L; }
  public double getStutterDiv() { return vm != null ? vm.getGlobalFloat(G_STUTTER_DIV) : 1.0; }
  public void processLaunchQueue() {
    for (int t = 0; t < TRACKS; t++) {
      int q = track.launchQueue[t];
      if (q >= 0) {
        track.currentClip[t] = q;
        track.launchQueue[t] = -1;
      }
    }
  }
  public int getPatternAtClip(int t, int sidx, int clipIdx) {
    if (clipIdx <= 0) return step.pattern[t * STEPS + sidx];
    if (vm == null) return step.pattern[t * STEPS + sidx];
    var arr = (org.chuck.core.ChuckArray) vm.getGlobalObject(G_PATTERN + "_C" + clipIdx);
    if (arr != null) return (int) arr.getInt(t * STEPS + sidx);
    return step.pattern[t * STEPS + sidx];
  }
  public int getPitchAtClip(int t, int sidx, int clipIdx) {
    if (clipIdx <= 0) return step.pitch[t * STEPS + sidx];
    if (vm == null) return step.pitch[t * STEPS + sidx];
    var arr = (org.chuck.core.ChuckArray) vm.getGlobalObject(G_PITCH + "_C" + clipIdx);
    if (arr != null) return (int) arr.getInt(t * STEPS + sidx);
    return step.pitch[t * STEPS + sidx];
  }
  public float getVelocityAtClip(int t, int sidx, int clipIdx) {
    if (clipIdx <= 0) return step.velocity[t * STEPS + sidx];
    if (vm == null) return step.velocity[t * STEPS + sidx];
    var arr = (org.chuck.core.ChuckArray) vm.getGlobalObject(G_VELOCITY + "_C" + clipIdx);
    if (arr != null) return (float) arr.getFloat(t * STEPS + sidx);
    return step.velocity[t * STEPS + sidx];
  }
  public float getProbabilityAtClip(int t, int sidx, int clipIdx) {
    if (clipIdx <= 0) return step.probability[t * STEPS + sidx];
    if (vm == null) return step.probability[t * STEPS + sidx];
    var arr = (org.chuck.core.ChuckArray) vm.getGlobalObject(G_PROBABILITY + "_C" + clipIdx);
    if (arr != null) return (float) arr.getFloat(t * STEPS + sidx);
    return step.probability[t * STEPS + sidx];
  }
  public void setRecording(boolean r) { this.recording = r; }
  public boolean isRecording() { return recording; }
  public boolean isUseJavaEngine() { return true; }
  public void setDelaySend(int t, float v) { track.delaySend[t] = v; }
  public float getDelaySend(int t) { return track.delaySend[t]; }
  public void setReverbSend(int t, float v) { track.reverbSend[t] = v; }
  public float getReverbSend(int t) { return track.reverbSend[t]; }

  // ── Raw array accessors for tests ────────────────────────────────────

  // SynthData
  public float[] getPanRaw() { return synth.panArr; }
  public int[] getOsc2TypeRaw() { return synth.osc2Type; }
  public float[] getMod1FbRaw() { return synth.mod1Fb; }
  public float[] getMod2AmtRaw() { return synth.mod2Amt; }
  public float[] getMod2FbRaw() { return synth.mod2Fb; }
  public float[] getCarrier2FbRaw() { return synth.carrier2Fb; }
  public float[] getOscMixRaw() { return synth.oscMix; }
  public float[] getNoiseVolRaw() { return synth.noiseVol; }
  public int[] getUnisonNumRaw() { return synth.unisonNum; }
  public float[] getUnisonDetuneRaw() { return synth.unisonDetune; }
  public int[] getModFxTypeRaw() { return synth.modFxType; }
  public float[] getModFxRateRaw() { return synth.modFxRate; }
  public float[] getModFxDepthRaw() { return synth.modFxDepth; }
  public float[] getModFxFeedbackRaw() { return synth.modFxFeedback; }
  public float[] getPortamentoRaw() { return synth.portamento; }
  public float[] getEqBassRaw() { return synth.eqBass; }
  public float[] getEqTrebleRaw() { return synth.eqTreble; }
  public float[] getStutterRateRaw() { return synth.stutterRateArr; }
  public float[] getSampleRateRedRaw() { return synth.sampleRateReductionArr; }
  public float[] getBitCrushRaw() { return synth.bitCrushArr; }
  public float[] getCompAttackRaw() { return synth.compressorAttackArr; }
  public float[] getCompReleaseRaw() { return synth.compressorReleaseArr; }

  // KitData
  public float[] getKitVolumeRaw() { return kit.kitVolume; }
  public float[] getKitPanRaw() { return kit.kitPan; }
  public float[] getKitHpfFreqRaw() { return kit.kitHpfFreq; }
  public float[] getKitHpfResRaw() { return kit.kitHpfRes; }
  public float[] getKitNoiseVolRaw() { return kit.kitNoiseVol; }
  public float[] getKitEqBassRaw() { return kit.kitEqBass; }
  public float[] getKitEqTrebleRaw() { return kit.kitEqTreble; }
  public float[] getKitSidechainRaw() { return kit.kitSidechain; }
  public int[] getKitModFxTypeRaw() { return kit.kitModFxType; }
  public float[] getKitStutterRateRaw() { return kit.kitStutterRate; }
  public float[] getKitSampleRateRedRaw() { return kit.kitSampleRateRed; }
  public float[] getKitBitCrushRaw() { return kit.kitBitCrush; }
  public float[] getKitCompAttackRaw() { return kit.kitCompressorAttackArr; }
  public float[] getKitCompReleaseRaw() { return kit.kitCompressorReleaseArr; }
}
