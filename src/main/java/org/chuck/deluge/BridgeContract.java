package org.chuck.deluge;

import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;

/**
 * Typed builder that creates and registers every shared global between Java UI and ChucK engine.
 *
 * <p>Call {@link #register(ChuckVM)} before loading any .ck file. Re-call after {@link
 * ChuckVM#clear()} to re-bind the same array objects (their Java references remain valid).
 *
 * <p>All array sizes and names are the single source of truth for both Java and ChucK code.
 */
public final class BridgeContract {

  // ── dimensions ──────────────────────────────────────────────────────────────
  public static final int TRACKS = 8;
  public static final int STEPS = 16;
  public static final int PATTERN_SIZE = TRACKS * STEPS; // 128

  public static final int ENV_COUNT = 4; // ENV_0..ENV_3 (matches Deluge firmware)
  public static final int ENV_PARAMS = 4; // attack, decay, sustain, release
  public static final int LFO_COUNT = 4; // 2 per-voice + 2 global (matches Deluge firmware)

  // ── global variable names (shared with engine.ck via global keyword) ────────

  // Transport
  public static final String G_BPM = "g_bpm";
  public static final String G_SWING = "g_swing"; // 0.0–1.0 (0.5 = no swing)
  public static final String G_PLAY = "g_play"; // 0 = stop, 1 = play
  public static final String G_CURRENT_STEP = "g_current_step"; // written by engine

  // Grid pattern: int[TRACKS * STEPS], row-major (row*STEPS + col)
  public static final String G_PATTERN = "g_pattern";

  // Per-step velocity: float[TRACKS * STEPS] 0.0–1.0
  public static final String G_VELOCITY = "g_velocity";

  // Per-step gate: float[TRACKS * STEPS] 0.0–1.0 (fraction of step duration)
  public static final String G_GATE = "g_gate";

  // Per-step pitch offset in semitones: int[TRACKS * STEPS]
  public static final String G_PITCH = "g_pitch";

  // Per-step probability: float[TRACKS * STEPS] 0.0–1.0
  public static final String G_PROBABILITY = "g_probability";

  // Per-track mute: int[TRACKS] 0 = active, 1 = muted
  public static final String G_MUTE = "g_mute";

  // Master volume/pan
  public static final String G_MASTER_VOL = "g_master_vol"; // float 0.0–1.0
  public static final String G_MASTER_PAN = "g_master_pan"; // float -1.0..+1.0

  // Filter per-track: float[TRACKS * 2] — pairs of (freq_norm, resonance)
  public static final String G_FILTER = "g_filter";
  // Filter mode per-track: int[TRACKS] 0=LADDER_12 1=LADDER_24 2=SVF
  public static final String G_FILTER_MODE = "g_filter_mode";
  // SVF morph per-track: float[TRACKS] 0=LP 0.5=BP 1=HP
  public static final String G_FILTER_MORPH = "g_filter_morph";

  // Envelope parameters: float[ENV_COUNT * ENV_PARAMS] row-major (env*ENV_PARAMS + param)
  // param order: 0=attack 1=decay 2=sustain 3=release — all in seconds (attack/decay/release)
  // or 0.0–1.0 (sustain)
  public static final String G_ENV = "g_env";

  // LFO rates: float[LFO_COUNT] in Hz; indices 0–1 = per-voice, 2–3 = global
  public static final String G_LFO_RATE = "g_lfo_rate";
  // LFO waveform types: int[LFO_COUNT] 0=SINE 1=SAW 2=SQUARE 3=TRIANGLE 4=S&H 5=RNDWALK 6=WARBLER
  public static final String G_LFO_TYPE = "g_lfo_type";
  // LFO depths for amplitude mod: float[LFO_COUNT] 0.0–1.0
  public static final String G_LFO_DEPTH = "g_lfo_depth";

  // Delay send: float[TRACKS] per-track send amount 0.0–1.0
  public static final String G_DELAY_SEND = "g_delay_send";
  // Reverb send: float[TRACKS] per-track send amount 0.0–1.0
  public static final String G_REVERB_SEND = "g_reverb_send";

  // Global FX parameters (scalars)
  public static final String G_DELAY_TIME = "g_delay_time"; // float seconds
  public static final String G_DELAY_FB = "g_delay_fb"; // float 0.0–1.0
  public static final String G_REVERB_ROOM = "g_reverb_room"; // float 0.0–1.0
  public static final String G_REVERB_DAMP = "g_reverb_damp"; // float 0.0–1.0

  // ── arrays (held in Java, shared via setGlobalObject) ──────────────────────
  private final ChuckArray pattern;
  private final ChuckArray velocity;
  private final ChuckArray gate;
  private final ChuckArray pitch;
  private final ChuckArray probability;
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

  public BridgeContract() {
    pattern = new ChuckArray("int", PATTERN_SIZE);
    velocity = new ChuckArray("float", PATTERN_SIZE);
    gate = new ChuckArray("float", PATTERN_SIZE);
    pitch = new ChuckArray("int", PATTERN_SIZE);
    probability = new ChuckArray("float", PATTERN_SIZE);
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
      velocity.setFloat(i, 0.8);
      gate.setFloat(i, 0.9);
      pitch.setInt(i, 0L);
      probability.setFloat(i, 1.0);
    }
    for (int t = 0; t < TRACKS; t++) {
      mute.setInt(t, 0L);
      filter.setFloat(t * 2, 1.0); // lpf freq norm = max open
      filter.setFloat(t * 2 + 1, 0.5); // resonance = moderate
      filterMode.setInt(t, 0L); // LADDER_12
      filterMorph.setFloat(t, 0.0); // LP
      delaySend.setFloat(t, 0.0);
      reverbSend.setFloat(t, 0.15);
    }
    // Envelope defaults: attack=10ms decay=100ms sustain=0.7 release=200ms
    for (int e = 0; e < ENV_COUNT; e++) {
      env.setFloat(e * ENV_PARAMS + 0, 0.01);
      env.setFloat(e * ENV_PARAMS + 1, 0.1);
      env.setFloat(e * ENV_PARAMS + 2, 0.7);
      env.setFloat(e * ENV_PARAMS + 3, 0.2);
    }
    // LFO defaults
    for (int l = 0; l < LFO_COUNT; l++) {
      lfoRate.setFloat(l, 1.0); // 1 Hz
      lfoType.setInt(l, 0L); // SINE
      lfoDepth.setFloat(l, 0.0);
    }
  }

  /** Register (or re-register after vm.clear()) all globals into the VM. */
  public void register(ChuckVM vm) {
    // Scalars
    if (!vm.isGlobalDouble(G_BPM)) vm.setGlobalFloat(G_BPM, 120.0);
    if (!vm.isGlobalDouble(G_SWING)) vm.setGlobalFloat(G_SWING, 0.5);
    if (!vm.isGlobalInt(G_PLAY)) vm.setGlobalInt(G_PLAY, 0L);
    if (!vm.isGlobalInt(G_CURRENT_STEP)) vm.setGlobalInt(G_CURRENT_STEP, -1L);
    if (!vm.isGlobalDouble(G_MASTER_VOL)) vm.setGlobalFloat(G_MASTER_VOL, 0.7);
    if (!vm.isGlobalDouble(G_MASTER_PAN)) vm.setGlobalFloat(G_MASTER_PAN, 0.0);
    if (!vm.isGlobalDouble(G_DELAY_TIME)) vm.setGlobalFloat(G_DELAY_TIME, 0.375);
    if (!vm.isGlobalDouble(G_DELAY_FB)) vm.setGlobalFloat(G_DELAY_FB, 0.4);
    if (!vm.isGlobalDouble(G_REVERB_ROOM)) vm.setGlobalFloat(G_REVERB_ROOM, 0.6);
    if (!vm.isGlobalDouble(G_REVERB_DAMP)) vm.setGlobalFloat(G_REVERB_DAMP, 0.5);

    // Arrays
    vm.setGlobalObject(G_PATTERN, pattern);
    vm.setGlobalObject(G_VELOCITY, velocity);
    vm.setGlobalObject(G_GATE, gate);
    vm.setGlobalObject(G_PITCH, pitch);
    vm.setGlobalObject(G_PROBABILITY, probability);
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

  // ── accessors for direct Java mutation ─────────────────────────────────────

  public void setStep(int track, int step, boolean active) {
    pattern.setInt(track * STEPS + step, active ? 1L : 0L);
  }

  public boolean getStep(int track, int step) {
    return pattern.getInt(track * STEPS + step) > 0;
  }

  public void setVelocity(int track, int step, double v) {
    velocity.setFloat(track * STEPS + step, Math.max(0.0, Math.min(1.0, v)));
  }

  public void setGate(int track, int step, double g) {
    gate.setFloat(track * STEPS + step, Math.max(0.0, Math.min(1.0, g)));
  }

  public void setPitch(int track, int step, int semitones) {
    pitch.setInt(track * STEPS + step, (long) semitones);
  }

  public void setStepProbability(int track, int step, double p) {
    probability.setFloat(track * STEPS + step, Math.max(0.0, Math.min(1.0, p)));
  }

  public void setMute(int track, boolean muted) {
    mute.setInt(track, muted ? 1L : 0L);
  }

  public boolean getMute(int track) {
    return mute.getInt(track) > 0;
  }

  /** Set LPF frequency as normalised 0.0–1.0 (maps to 20–20000 Hz in engine). */
  public void setFilterFreq(int track, double normFreq) {
    filter.setFloat(track * 2, Math.max(0.0, Math.min(1.0, normFreq)));
  }

  public void setFilterRes(int track, double res) {
    filter.setFloat(track * 2 + 1, Math.max(0.0, Math.min(1.0, res)));
  }

  public void setFilterMode(int track, int mode) {
    filterMode.setInt(track, (long) mode);
  }

  public void setFilterMorph(int track, double morph) {
    filterMorph.setFloat(track, Math.max(0.0, Math.min(1.0, morph)));
  }

  public void setEnv(int envIndex, double attack, double decay, double sustain, double release) {
    int base = envIndex * ENV_PARAMS;
    env.setFloat(base + 0, Math.max(0.001, attack));
    env.setFloat(base + 1, Math.max(0.001, decay));
    env.setFloat(base + 2, Math.max(0.0, Math.min(1.0, sustain)));
    env.setFloat(base + 3, Math.max(0.001, release));
  }

  public void setLfo(int lfoIndex, double rateHz, int waveType, double depth) {
    lfoRate.setFloat(lfoIndex, Math.max(0.01, rateHz));
    lfoType.setInt(lfoIndex, (long) waveType);
    lfoDepth.setFloat(lfoIndex, Math.max(0.0, Math.min(1.0, depth)));
  }

  public void clearPattern() {
    for (int i = 0; i < PATTERN_SIZE; i++) pattern.setInt(i, 0L);
  }

  /** Snapshot the 128-cell pattern as a flat boolean array (track-major). */
  public boolean[] snapshotPattern() {
    boolean[] snap = new boolean[PATTERN_SIZE];
    for (int i = 0; i < PATTERN_SIZE; i++) snap[i] = pattern.getInt(i) > 0;
    return snap;
  }

  /** Restore a previously snapshotted pattern. */
  public void restorePattern(boolean[] snap) {
    for (int i = 0; i < Math.min(snap.length, PATTERN_SIZE); i++) {
      pattern.setInt(i, snap[i] ? 1L : 0L);
    }
  }

  // ── raw array access for SequencerPanel compatibility ──────────────────────

  public ChuckArray patternArray() {
    return pattern;
  }

  public ChuckArray probabilityArray() {
    return probability;
  }
}
