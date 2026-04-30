package org.chuck.deluge;

import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;

/**
 * Typed contract between the Java Swing UI and the ChucK audio engine — every global that
 * either side reads or writes is declared, created, and registered here.
 *
 * <h2>Architecture</h2>
 * This class serves as the single source of truth for all shared state. The UI writes
 * step data, track parameters, and transport controls into typed {@link ChuckArray}s and
 * scalar globals; the engine's shreds (sporked by {@code DelugeEngineDSL}) read them every
 * tick. No locks are needed because the UI writes between ticks and the engine reads at
 * tick boundaries.
 *
 * <h2>Dimensions</h2>
 * <ul>
 *   <li>{@link #TRACKS} — 64 (configurable via {@code deluge.tracks} system property)</li>
 *   <li>{@link #STEPS} — 192 (max step capacity per clip, matching real Deluge hardware)</li>
 *   <li>{@link #PATTERN_SIZE} = {@code TRACKS × STEPS} = 12 288 — sizes all 14 step-data arrays</li>
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

  // dimensions
  public static final int TRACKS = Integer.getInteger("deluge.tracks", 64);
  public static final int STEPS = 192;
  public static final int PATTERN_SIZE = TRACKS * STEPS;

  public static final int ENV_COUNT = 4;
  public static final int ENV_PARAMS = 4;
  public static final int LFO_COUNT = 4;

  // global names
  public static final String G_BPM = "g_bpm";
  public static final String G_SWING = "g_swing";
  public static final String G_PLAY = "g_play";
  public static final String G_CURRENT_STEP = "g_current_step";
  public static final String G_STUTTER_ON = "g_stutter_on";
  public static final String G_STUTTER_DIV = "g_stutter_div";

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
  public static final String G_TRACK_TYPE = "g_track_type";
  public static final String G_OSC_TYPE = "g_osc_type";
  public static final String G_TRACK_LEVEL = "g_track_level";
  public static final String G_MUTE = "g_mute";

  public static final String G_MASTER_VOL = "g_master_vol";
  public static final String G_MASTER_PAN = "g_master_pan";

  public static final String G_FILTER = "g_filter";
  public static final String G_FILTER_MODE = "g_filter_mode";
  public static final String G_FILTER_MORPH = "g_filter_morph";
  public static final String G_ENV = "g_env";
  public static final String G_LFO_RATE = "g_lfo_rate";
  public static final String G_LFO_TYPE = "g_lfo_type";
  public static final String G_LFO_DEPTH = "g_lfo_depth";
  // LFO target constants: 0=filter, 1=res, 2=pan, 3=pitch, 4=vol, 5=fm
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

  // Engine Internals
  public static final String G_DELAY_IN = "g_delay_in";
  public static final String G_REVERB_IN = "g_reverb_in";
  public static final String G_SYNTH_BUS = "g_synth_bus";
  public static final String G_MASTER_COMP = "g_master_comp";

  // Arp & FM
  public static final String G_ARP_ON = "g_arp_on";
  public static final String G_ARP_RATE = "g_arp_rate";
  public static final String G_ARP_OCTAVE = "g_arp_octave";
  public static final String G_FM_RATIO = "g_fm_ratio";
  public static final String G_FM_AMOUNT = "g_fm_amount";

  // Audition
  public static final String G_PREVIEW_TRACK = "g_preview_track";
  public static final String E_PREVIEW = "e_preview";
  public static final String E_SIDECHAIN = "e_sidechain";

  // Synth algorithm: 0=FM (current), 10=Mandolin, 11=Rhodey, 12=ModalBar, 13=Moog
  public static final String G_SYNTH_ALGO = "g_synth_algo";

  // Kit logic
  public static final String G_KIT_ATTACK = "g_kit_attack";
  public static final String G_KIT_DECAY = "g_kit_decay";
  public static final String G_KIT_SUSTAIN = "g_kit_sustain";
  public static final String G_KIT_RELEASE = "g_kit_release";
  public static final String G_KIT_PITCH = "g_kit_pitch";
  public static final String G_KIT_REVERSE = "g_kit_reverse";
  public static final String G_KIT_MUTE_GROUP = "g_kit_mute_group";

  // Audio track (LiSa) globals
  public static final String G_AUDIO_REC = "g_audio_rec";
  public static final String G_AUDIO_PLAY = "g_audio_play";
  public static final String G_AUDIO_LOOP = "g_audio_loop";
  public static final String G_AUDIO_RATE = "g_audio_rate";
  public static final String G_AUDIO_BUS = "g_audio_bus";

  // WvOut export globals
  public static final String G_WVOUT_ACTIVE = "g_wvout_active";
  public static final String G_WVOUT_FILE = "g_wvout_file";

  // Master output tap (for export/WvOut)
  public static final String G_MASTER_TAP = "g_master_tap";

  private final ChuckArray pattern;
  private final ChuckArray velocity;
  private final ChuckArray gate;
  private final ChuckArray pitch;
  private final ChuckArray probability;
  private final ChuckArray stepFilter;
  private final ChuckArray stepRes;
  private final ChuckArray stepFilterMode;
  private final ChuckArray stepPan;
  private final ChuckArray stepDelay;
  private final ChuckArray stepReverb;
  private final ChuckArray stepMod;
  private final ChuckArray stepStart;
  private final ChuckArray stepEnd;
  private final ChuckArray trackType;
  private final ChuckArray oscType;
  private final ChuckArray trackLevel;
  private final ChuckArray mute;
  private final ChuckArray filter;
  private final ChuckArray filterMode;
  private final ChuckArray filterMorph;
  private final ChuckArray env;
  private final ChuckArray lfoRate;
  private final ChuckArray lfoType;
  private final ChuckArray lfoDepth;
  private final ChuckArray lfoTarget;
  private final ChuckArray lfoTrack;
  private final ChuckArray lfoValue;
  private final ChuckArray trackLength;
  private final ChuckArray delaySend;
  private final ChuckArray reverbSend;

  private final ChuckArray kitAttack;
  private final ChuckArray kitDecay;
  private final ChuckArray kitSustain;
  private final ChuckArray kitRelease;
  private final ChuckArray kitPitch;
  private final ChuckArray kitReverse;
  private final ChuckArray kitMuteGroup;

  private final ChuckArray arpOn;
  private final ChuckArray arpRate;
  private final ChuckArray arpOctave;
  private final ChuckArray fmRatio;
  private final ChuckArray fmAmount;

  private final ChuckArray synthAlgo;

  private final ChuckArray audioRec;
  private final ChuckArray audioPlay;
  private final ChuckArray audioLoop;
  private final ChuckArray audioRate;

  private ChuckVM vm;
  private boolean recording = false;

  public BridgeContract() {
    pattern = new ChuckArray("int", PATTERN_SIZE);
    velocity = new ChuckArray("float", PATTERN_SIZE);
    gate = new ChuckArray("float", PATTERN_SIZE);
    pitch = new ChuckArray("int", PATTERN_SIZE);
    probability = new ChuckArray("float", PATTERN_SIZE);
    stepFilter = new ChuckArray("float", PATTERN_SIZE);
    stepRes = new ChuckArray("float", PATTERN_SIZE);
    stepFilterMode = new ChuckArray("int", PATTERN_SIZE);
    stepPan = new ChuckArray("float", PATTERN_SIZE);
    stepDelay = new ChuckArray("float", PATTERN_SIZE);
    stepReverb = new ChuckArray("float", PATTERN_SIZE);
    stepMod = new ChuckArray("float", PATTERN_SIZE);
    stepStart = new ChuckArray("float", PATTERN_SIZE);
    stepEnd = new ChuckArray("float", PATTERN_SIZE);
    trackType = new ChuckArray("int", TRACKS);
    oscType = new ChuckArray("int", TRACKS);
    trackLevel = new ChuckArray("float", TRACKS);
    mute = new ChuckArray("int", TRACKS);
    filter = new ChuckArray("float", TRACKS * 2);
    filterMode = new ChuckArray("int", TRACKS);
    filterMorph = new ChuckArray("float", TRACKS);
    env = new ChuckArray("float", ENV_COUNT * ENV_PARAMS);
    lfoRate = new ChuckArray("float", LFO_COUNT);
    lfoType = new ChuckArray("int", LFO_COUNT);
    lfoDepth = new ChuckArray("float", LFO_COUNT);
    lfoTarget = new ChuckArray("int", LFO_COUNT);
    lfoTrack = new ChuckArray("int", LFO_COUNT);
    lfoValue = new ChuckArray("float", LFO_COUNT);
    trackLength = new ChuckArray("int", TRACKS);
    delaySend = new ChuckArray("float", TRACKS);
    reverbSend = new ChuckArray("float", TRACKS);

    kitAttack = new ChuckArray("float", TRACKS);
    kitDecay = new ChuckArray("float", TRACKS);
    kitSustain = new ChuckArray("float", TRACKS);
    kitRelease = new ChuckArray("float", TRACKS);
    kitPitch = new ChuckArray("float", TRACKS);
    kitReverse = new ChuckArray("int", TRACKS);
    kitMuteGroup = new ChuckArray("int", TRACKS);

    arpOn = new ChuckArray("int", TRACKS);
    arpRate = new ChuckArray("float", TRACKS);
    arpOctave = new ChuckArray("int", TRACKS);
    fmRatio = new ChuckArray("float", TRACKS);
    fmAmount = new ChuckArray("float", TRACKS);
    synthAlgo = new ChuckArray("int", TRACKS);

    audioRec = new ChuckArray("int", TRACKS);
    audioPlay = new ChuckArray("int", TRACKS);
    audioLoop = new ChuckArray("int", TRACKS);
    audioRate = new ChuckArray("float", TRACKS);

    initDefaults();
  }

  private void initDefaults() {
    for (int i = 0; i < PATTERN_SIZE; i++) {
      pattern.setInt(i, 0L);
      velocity.setFloat(i, 0.8f);
      gate.setFloat(i, 0.9f);
      pitch.setInt(i, 0L);
      probability.setFloat(i, 1.0f);
      stepFilter.setFloat(i, 0.0f);
      stepRes.setFloat(i, 0.0f);
      stepFilterMode.setInt(i, -1L);
      stepPan.setFloat(i, 0.0f);
      stepDelay.setFloat(i, 0.0f);
      stepReverb.setFloat(i, 0.0f);
      stepMod.setFloat(i, 0.0f);
      stepStart.setFloat(i, 0.0f);
      stepEnd.setFloat(i, 1.0f);
    }
    for (int t = 0; t < TRACKS; t++) {
      trackType.setInt(t, 0L);
      mute.setInt(t, 0L);
      trackLevel.setFloat(t, 0.7f);
      filter.setFloat(t * 2, 1.0f);
      filter.setFloat(t * 2 + 1, 0.5f);
      filterMode.setInt(t, 0L);
      filterMorph.setFloat(t, 0.0f);
      delaySend.setFloat(t, 0.0f);
      reverbSend.setFloat(t, 0.15f);

      kitAttack.setFloat(t, 0.001f);
      kitDecay.setFloat(t, 0.1f);
      kitSustain.setFloat(t, 0.8f);
      kitRelease.setFloat(t, 0.2f);
      kitPitch.setFloat(t, 0.0f);
      kitReverse.setInt(t, 0L);
      kitMuteGroup.setInt(t, 0L);

      arpOn.setInt(t, 0L);
      arpRate.setFloat(t, 1.0f);
      arpOctave.setInt(t, 0L);
      fmRatio.setFloat(t, 1.0f);
      fmAmount.setFloat(t, 0.0f);
      synthAlgo.setInt(t, 0L);

      audioRec.setInt(t, 0L);
      audioPlay.setInt(t, 0L);
      audioLoop.setInt(t, 1L);
      audioRate.setFloat(t, 1.0f);
    }
    for (int e = 0; e < ENV_COUNT; e++) {
      env.setFloat(e * ENV_PARAMS + 0, 0.01f);
      env.setFloat(e * ENV_PARAMS + 1, 0.1f);
      env.setFloat(e * ENV_PARAMS + 2, 0.7f);
      env.setFloat(e * ENV_PARAMS + 3, 0.2f);
    }
    for (int l = 0; l < LFO_COUNT; l++) {
      lfoRate.setFloat(l, 1.0f);
      lfoType.setInt(l, 0L);
      lfoDepth.setFloat(l, 0.0f);
      lfoTarget.setInt(l, 0L);
      lfoTrack.setInt(l, -1L);
      lfoValue.setFloat(l, 0.0f);
    }
    for (int t = 0; t < TRACKS; t++) {
      trackLength.setInt(t, 16L);
    }
  }

  public void register(ChuckVM vm) {
    this.vm = vm;
    vm.setGlobalFloat(G_BPM, 120.0);
    vm.setGlobalFloat(G_SWING, 0.5);
    vm.setGlobalInt(G_PLAY, 0L);
    vm.setGlobalInt(G_CURRENT_STEP, -1L);
    vm.setGlobalFloat(G_MASTER_VOL, 0.7);
    vm.setGlobalFloat(G_MASTER_PAN, 0.0);
    vm.setGlobalFloat(G_DELAY_TIME, 0.375);
    vm.setGlobalFloat(G_DELAY_FB, 0.4);
    vm.setGlobalFloat(G_REVERB_ROOM, 0.6);
    vm.setGlobalFloat(G_REVERB_DAMP, 0.5);
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

    vm.setGlobalObject(G_PATTERN, pattern);
    vm.setGlobalObject(G_VELOCITY, velocity);
    vm.setGlobalObject(G_GATE, gate);
    vm.setGlobalObject(G_PITCH, pitch);
    vm.setGlobalObject(G_PROBABILITY, probability);
    vm.setGlobalObject(G_STEP_FILTER, stepFilter);
    vm.setGlobalObject(G_STEP_RES, stepRes);
    vm.setGlobalObject(G_STEP_FILTER_MODE, stepFilterMode);
    vm.setGlobalObject(G_STEP_PAN, stepPan);
    vm.setGlobalObject(G_STEP_DELAY, stepDelay);
    vm.setGlobalObject(G_STEP_REVERB, stepReverb);
    vm.setGlobalObject(G_STEP_MOD, stepMod);
    vm.setGlobalObject(G_STEP_START, stepStart);
    vm.setGlobalObject(G_STEP_END, stepEnd);
    vm.setGlobalObject(G_TRACK_TYPE, trackType);
    vm.setGlobalObject(G_TRACK_LEVEL, trackLevel);
    vm.setGlobalObject(G_MUTE, mute);
    vm.setGlobalObject(G_FILTER, filter);
    vm.setGlobalObject(G_FILTER_MODE, filterMode);
    vm.setGlobalObject(G_FILTER_MORPH, filterMorph);
    vm.setGlobalObject(G_ENV, env);
    vm.setGlobalObject(G_LFO_RATE, lfoRate);
    vm.setGlobalObject(G_LFO_TYPE, lfoType);
    vm.setGlobalObject(G_LFO_DEPTH, lfoDepth);
    vm.setGlobalObject(G_LFO_TARGET, lfoTarget);
    vm.setGlobalObject(G_LFO_TRACK, lfoTrack);
    vm.setGlobalObject(G_LFO_VALUE, lfoValue);
    vm.setGlobalObject(G_TRACK_LENGTH, trackLength);
    vm.setGlobalObject(G_DELAY_SEND, delaySend);
    vm.setGlobalObject(G_REVERB_SEND, reverbSend);

    vm.setGlobalObject(G_KIT_ATTACK, kitAttack);
    vm.setGlobalObject(G_KIT_DECAY, kitDecay);
    vm.setGlobalObject(G_KIT_SUSTAIN, kitSustain);
    vm.setGlobalObject(G_KIT_RELEASE, kitRelease);
    vm.setGlobalObject(G_KIT_PITCH, kitPitch);
    vm.setGlobalObject(G_KIT_REVERSE, kitReverse);
    vm.setGlobalObject(G_KIT_MUTE_GROUP, kitMuteGroup);

    vm.setGlobalObject(G_ARP_ON, arpOn);
    vm.setGlobalObject(G_ARP_RATE, arpRate);
    vm.setGlobalObject(G_ARP_OCTAVE, arpOctave);
    vm.setGlobalObject(G_FM_RATIO, fmRatio);
    vm.setGlobalObject(G_FM_AMOUNT, fmAmount);
    vm.setGlobalObject(G_SYNTH_ALGO, synthAlgo);

    vm.setGlobalObject(G_AUDIO_REC, audioRec);
    vm.setGlobalObject(G_AUDIO_PLAY, audioPlay);
    vm.setGlobalObject(G_AUDIO_LOOP, audioLoop);
    vm.setGlobalObject(G_AUDIO_RATE, audioRate);

    // Monolithic engine buses
    vm.setGlobalObject(G_DELAY_IN, new org.chuck.audio.util.Gain());
    vm.setGlobalObject(G_REVERB_IN, new org.chuck.audio.util.Gain());
    vm.setGlobalObject(G_SYNTH_BUS, new org.chuck.audio.util.Gain());
    vm.setGlobalObject(G_AUDIO_BUS, new org.chuck.audio.util.Gain());
    vm.setGlobalFloat(G_WVOUT_ACTIVE, 0.0);
    vm.setGlobalString(G_WVOUT_FILE, "");
  }

  public ChuckVM getVm() {
    return vm;
  }

  public void setStep(int track, int step, boolean active) {
    pattern.setInt(track * STEPS + step, active ? 1L : 0L);
  }

  public void clearAllSteps() {
    for (int i = 0; i < pattern.size(); i++) {
      pattern.setInt(i, 0L);
    }
  }

  public boolean getStep(int track, int step) {
    return pattern.getInt(track * STEPS + step) > 0;
  }

  public void setVelocity(int track, int step, double val) {
    velocity.setFloat(track * STEPS + step, (float) Math.max(0, Math.min(1, val)));
  }

  public double getVelocity(int track, int step) {
    return velocity.getFloat(track * STEPS + step);
  }

  public void setGate(int track, int step, double val) {
    gate.setFloat(track * STEPS + step, (float) Math.max(0, val));
  }

  public double getGate(int track, int step) {
    return gate.getFloat(track * STEPS + step);
  }

  public void setPitch(int track, int step, int p) {
    pitch.setInt(track * STEPS + step, (long) p);
  }

  public int getPitch(int track, int step) {
    return (int) pitch.getInt(track * STEPS + step);
  }

  public void setStepProbability(int track, int step, double val) {
    probability.setFloat(track * STEPS + step, (float) Math.max(0, Math.min(1, val)));
  }

  public double getStepProbability(int track, int step) {
    return probability.getFloat(track * STEPS + step);
  }

  public void setTrackLevel(int track, double val) {
    trackLevel.setFloat(track, (float) Math.max(0, Math.min(1, val)));
  }

  public double getTrackLevel(int track) {
    return trackLevel.getFloat(track);
  }

  public void setMute(int track, boolean val) {
    mute.setInt(track, val ? 1L : 0L);
    if (vm != null) {
      vm.setGlobalInt("g_mute_" + track, val ? 1L : 0L);
    }
  }

  public boolean getMute(int track) {
    if (vm != null) {
      return vm.getGlobalInt("g_mute_" + track) > 0;
    }
    return mute.getInt(track) > 0;
  }

  public int getTrackType(int track) {
    return (int) trackType.getInt(track);
  }

  public void setTrackType(int track, int type) {
    trackType.setInt(track, (long) type);
  }

  public void setFilterFreq(int track, double val) {
    filter.setFloat(track * 2, (float) Math.max(0, Math.min(1, val)));
  }

  public double getTrackFilterFreq(int track) {
    return filter.getFloat(track * 2);
  }

  public void setFilterRes(int track, double val) {
    filter.setFloat(track * 2 + 1, (float) Math.max(0, Math.min(1, val)));
  }

  public double getTrackFilterRes(int track) {
    return filter.getFloat(track * 2 + 1);
  }

  public void setFilterMode(int track, int mode) {
    filterMode.setInt(track, (long) mode);
  }

  public void setFilterMorph(int track, double morph) {
    filterMorph.setFloat(track, (float) Math.max(0, Math.min(1, morph)));
  }

  public void setEnv(int envIndex, double a, double d, double s, double r) {
    int b = envIndex * ENV_PARAMS;
    env.setFloat(b + 0, (float) Math.max(0.001, a));
    env.setFloat(b + 1, (float) Math.max(0.001, d));
    env.setFloat(b + 2, (float) Math.max(0, Math.min(1, s)));
    env.setFloat(b + 3, (float) Math.max(0.001, r));
  }

  public void setLfo(int lfoIndex, double rateHz, int waveType, double depth) {
    lfoRate.setFloat(lfoIndex, (float) Math.max(0.01, rateHz));
    lfoType.setInt(lfoIndex, (long) waveType);
    lfoDepth.setFloat(lfoIndex, (float) Math.max(0, Math.min(1, depth)));
  }

  public void setLfoTarget(int lfoIndex, int target) {
    lfoTarget.setInt(lfoIndex, (long) target);
  }

  public int getLfoTarget(int lfoIndex) {
    return (int) lfoTarget.getInt(lfoIndex);
  }

  public void setLfoTrack(int lfoIndex, int track) {
    lfoTrack.setInt(lfoIndex, (long) track);
  }

  public int getLfoTrack(int lfoIndex) {
    return (int) lfoTrack.getInt(lfoIndex);
  }

  public void setTrackLength(int track, int steps) {
    trackLength.setInt(track, (long) Math.max(1, steps));
  }

  public int getTrackLength(int track) {
    return (int) trackLength.getInt(track);
  }

  public void setBpm(double bpm) {
    if (vm != null) vm.setGlobalFloat(G_BPM, bpm);
  }

  public double getBpm() {
    return vm != null ? vm.getGlobalFloat(G_BPM) : 120.0;
  }

  public boolean getArpOn(int track) {
    return arpOn.getInt(track) > 0;
  }

  public void setArpOn(int track, boolean on) {
    arpOn.setInt(track, on ? 1L : 0L);
  }

  public double getArpRate(int track) {
    return arpRate.getFloat(track);
  }

  public void setArpRate(int track, double rate) {
    arpRate.setFloat(track, (float) rate);
  }

  public int getArpOctave(int track) {
    return (int) arpOctave.getInt(track);
  }

  public void setArpOctave(int track, int oct) {
    arpOctave.setInt(track, (long) oct);
  }

  public double getFmRatio(int track) {
    return fmRatio.getFloat(track);
  }

  public void setFmRatio(int track, double r) {
    fmRatio.setFloat(track, (float) r);
  }

  public double getFmAmount(int track) {
    return fmAmount.getFloat(track);
  }

  public void setFmAmount(int track, double a) {
    fmAmount.setFloat(track, (float) a);
  }

  public int getSynthAlgo(int track) {
    return (int) synthAlgo.getInt(track);
  }

  public void setSynthAlgo(int track, int algo) {
    synthAlgo.setInt(track, (long) algo);
  }

  // === Audio Track Helpers ===

  public int getAudioRec(int track) {
    return (int) audioRec.getInt(track);
  }

  public void setAudioRec(int track, int state) {
    audioRec.setInt(track, (long) state);
  }

  public int getAudioPlay(int track) {
    return (int) audioPlay.getInt(track);
  }

  public void setAudioPlay(int track, int state) {
    audioPlay.setInt(track, (long) state);
  }

  public int getAudioLoop(int track) {
    return (int) audioLoop.getInt(track);
  }

  public void setAudioLoop(int track, int looping) {
    audioLoop.setInt(track, (long) looping);
  }

  public float getAudioRate(int track) {
    return (float) audioRate.getFloat(track);
  }

  public void setAudioRate(int track, float rate) {
    audioRate.setFloat(track, rate);
  }

  // === WvOut Export Helpers ===

  public boolean isExporting() {
    return vm != null && vm.getGlobalFloat(G_WVOUT_ACTIVE) > 0.5;
  }

  public void startExport(String filePath) {
    if (vm == null) return;
    vm.setGlobalString(G_WVOUT_FILE, filePath);
    vm.setGlobalFloat(G_WVOUT_ACTIVE, 1.0);
  }

  public void stopExport() {
    if (vm == null) return;
    vm.setGlobalFloat(G_WVOUT_ACTIVE, 0.0);
  }

  public void syncActiveClipToLibrary(int track) {}

  public void loadSynthPreset(int track, org.chuck.deluge.model.SynthTrackModel model) {}

  public void loadClip(int track, int clipIdx) {}

  public void clearPattern() {
    for (int i = 0; i < PATTERN_SIZE; i++) pattern.setInt(i, 0L);
  }

  public ChuckArray patternArray() {
    return pattern;
  }

  public void setRecording(boolean r) {
    this.recording = r;
  }

  public boolean isRecording() {
    return recording;
  }

  public boolean isUseJavaEngine() {
    return true;
  }
}
