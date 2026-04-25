package org.chuck.deluge;

import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;

/**
 * Typed builder that creates and registers every shared global between Java UI and ChucK engine.
 */
public final class BridgeContract {

  // dimensions
  public static final int TRACKS = Integer.getInteger("deluge.tracks", 64);
  public static final int STEPS = 16;
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
  private final ChuckArray delaySend;
  private final ChuckArray reverbSend;
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
    delaySend = new ChuckArray("float", TRACKS);
    reverbSend = new ChuckArray("float", TRACKS);

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
      trackType.setInt(t, 0L); // Default to KIT for all tracks
      mute.setInt(t, 0L);
      trackLevel.setFloat(t, 0.7f);
      filter.setFloat(t * 2, 1.0f);
      filter.setFloat(t * 2 + 1, 0.5f);
      filterMode.setInt(t, 0L);
      filterMorph.setFloat(t, 0.0f);
      delaySend.setFloat(t, 0.0f);
      reverbSend.setFloat(t, 0.15f);
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
    vm.setGlobalObject(G_DELAY_SEND, delaySend);
    vm.setGlobalObject(G_REVERB_SEND, reverbSend);
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

  public void setStepFilter(int track, int step, double val) {
    stepFilter.setFloat(track * STEPS + step, (float) Math.max(-1, Math.min(1, val)));
  }

  public double getStepFilter(int track, int step) {
    return stepFilter.getFloat(track * STEPS + step);
  }

  public void setStepRes(int track, int step, double val) {
    stepRes.setFloat(track * STEPS + step, (float) Math.max(-1, Math.min(1, val)));
  }

  public double getStepRes(int track, int step) {
    return stepRes.getFloat(track * STEPS + step);
  }

  public void setStepFilterMode(int track, int step, int mode) {
    stepFilterMode.setInt(track * STEPS + step, (long) mode);
  }

  public int getStepFilterMode(int track, int step) {
    return (int) stepFilterMode.getInt(track * STEPS + step);
  }

  public void setStepPan(int track, int step, double val) {
    stepPan.setFloat(track * STEPS + step, (float) Math.max(-1, Math.min(1, val)));
  }

  public double getStepPan(int track, int step) {
    return stepPan.getFloat(track * STEPS + step);
  }

  public void setStepDelay(int track, int step, double val) {
    stepDelay.setFloat(track * STEPS + step, (float) Math.max(0, Math.min(1, val)));
  }

  public double getStepDelay(int track, int step) {
    return stepDelay.getFloat(track * STEPS + step);
  }

  public void setStepReverb(int track, int step, double val) {
    stepReverb.setFloat(track * STEPS + step, (float) Math.max(0, Math.min(1, val)));
  }

  public double getStepReverb(int track, int step) {
    return stepReverb.getFloat(track * STEPS + step);
  }

  public void setStepMod(int track, int step, double val) {
    stepMod.setFloat(track * STEPS + step, (float) Math.max(0, Math.min(1, val)));
  }

  public double getStepMod(int track, int step) {
    return stepMod.getFloat(track * STEPS + step);
  }

  public void setStepStart(int track, int step, double val) {
    stepStart.setFloat(track * STEPS + step, (float) Math.max(0, Math.min(1, val)));
  }

  public double getStepStart(int track, int step) {
    return stepStart.getFloat(track * STEPS + step);
  }

  public void setStepEnd(int track, int step, double val) {
    stepEnd.setFloat(track * STEPS + step, (float) Math.max(0, Math.min(1, val)));
  }

  public double getStepEnd(int track, int step) {
    return stepEnd.getFloat(track * STEPS + step);
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

  public void clearPattern() {
    for (int i = 0; i < PATTERN_SIZE; i++) pattern.setInt(i, 0L);
  }

  public boolean[] snapshotPattern() {
    boolean[] snap = new boolean[PATTERN_SIZE];
    for (int i = 0; i < PATTERN_SIZE; i++) snap[i] = pattern.getInt(i) > 0;
    return snap;
  }

  public void restorePattern(boolean[] snap) {
    for (int i = 0; i < Math.min(snap.length, PATTERN_SIZE); i++) {
      pattern.setInt(i, snap[i] ? 1L : 0L);
    }
  }

  private org.chuck.deluge.ui.MatrixPanel matrixPanel;

  public void setMatrixPanel(org.chuck.deluge.ui.MatrixPanel m) {
    this.matrixPanel = m;
  }

  public org.chuck.deluge.ui.MatrixPanel getMatrixPanel() {
    return matrixPanel;
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

  public ChuckArray probabilityArray() {
    return probability;
  }
}
