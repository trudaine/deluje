package org.chuck.deluge;

import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;
import org.chuck.core.ChuckEvent;
import org.chuck.audio.util.Gain;

/**
 * Typed builder that creates and registers every shared global between Java UI and ChucK engine.
 */
public final class BridgeContract {

  // ── dimensions ──────────────────────────────────────────────────────────────
  public static final int TRACKS = 8;
  public static final int STEPS = 16;
  public static final int PATTERN_SIZE = TRACKS * STEPS; // 128

  public static final int ENV_COUNT = 8;
  public static final int ENV_PARAMS = 4;
  public static final int LFO_COUNT = 8;

  // ── global variable names ──────────────────────────────────────────────────
  public static final String G_BPM = "g_bpm";
  public static final String G_SWING = "g_swing";
  public static final String G_PLAY = "g_play";
  public static final String G_CURRENT_STEP = "g_current_step";
  public static final String G_RECORD_ON = "g_record_on";

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
  public static final String G_STUTTER_ON = "g_stutter_on";
  public static final String G_STUTTER_DIV = "g_stutter_div";
  public static final String G_SCALE = "g_scale";
  public static final String G_ROOT_KEY = "g_root_key";

  // Advanced DSP
  public static final String G_FM_RATIO = "g_fm_ratio";
  public static final String G_FM_AMOUNT = "g_fm_amount";
  public static final String G_SIDECHAIN_AMOUNT = "g_sidechain_amount";
  public static final String G_MASTER_COMP = "g_master_comp";
  public static final String G_DELAY_IN = "g_delay_in";
  public static final String G_REVERB_IN = "g_reverb_in";
  public static final String G_MOD_IN = "g_mod_in";
  public static final String G_SYNTH_BUS = "g_synth_bus";

  // Arpeggiator
  public static final String G_ARP_ON = "g_arp_on";
  public static final String G_ARP_MODE = "g_arp_mode";
  public static final String G_ARP_RATE = "g_arp_rate";
  public static final String G_ARP_OCTAVE = "g_arp_octave";

  // Events
  public static final String E_TICK = "tick_event";
  public static final String E_SIDECHAIN = "sidechain_event";
  public static final String E_MIDI_NOTE_ON = "midi_note_on";
  public static final String E_MIDI_NOTE_OFF = "midi_note_off";

  // ── arrays & objects ────────────────────────────────────────────────────────
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

  private final ChuckArray fmRatio;
  private final ChuckArray fmAmount;

  private final ChuckArray arpOn;
  private final ChuckArray arpMode;
  private final ChuckArray arpRate;
  private final ChuckArray arpOctave;

  private final ChuckEvent tickEvent;
  private final ChuckEvent sidechainEvent;
  private final ChuckEvent midiNoteOn;
  private final ChuckEvent midiNoteOff;

  private final Gain delayIn;
  private final Gain reverbIn;
  private final Gain modIn;
  private final Gain synthBus;

  private ChuckVM vm;
  private boolean recording = false;
  private boolean useJavaEngine = false;

  private final org.chuck.deluge.model.ClipLibrary clipLibrary;
  private final int[] activeClipSlots = new int[TRACKS];

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

    fmRatio = new ChuckArray("float", TRACKS);
    fmAmount = new ChuckArray("float", TRACKS);

    arpOn = new ChuckArray("int", TRACKS);
    arpMode = new ChuckArray("int", TRACKS);
    arpRate = new ChuckArray("float", TRACKS);
    arpOctave = new ChuckArray("int", TRACKS);

    tickEvent = new ChuckEvent();
    sidechainEvent = new ChuckEvent();
    midiNoteOn = new ChuckEvent();
    midiNoteOff = new ChuckEvent();

    delayIn = new Gain();
    reverbIn = new Gain();
    modIn = new Gain();
    synthBus = new Gain();

    clipLibrary = new org.chuck.deluge.model.ClipLibrary(TRACKS, 8);
    java.util.Arrays.fill(activeClipSlots, 0);

    initDefaults();
  }

  private void initDefaults() {
    for (int i = 0; i < PATTERN_SIZE; i++) {
      pattern.setInt(i, 0L);
      velocity.setFloat(i, 0.8);
      gate.setFloat(i, 0.9);
      pitch.setInt(i, 0L);
      probability.setFloat(i, 1.0);
      stepFilter.setFloat(i, 0.0);
      stepRes.setFloat(i, 0.0);
      stepFilterMode.setInt(i, -1L);
      stepPan.setFloat(i, 0.0);
      stepDelay.setFloat(i, 0.0);
      stepReverb.setFloat(i, 0.0);
      stepMod.setFloat(i, 0.0);
      stepStart.setFloat(i, 0.0);
      stepEnd.setFloat(i, 1.0);
    }
    for (int t = 0; t < TRACKS; t++) {
      mute.setInt(t, 0L);
      trackLevel.setFloat(t, 0.7);
      fmRatio.setFloat(t, 1.0f);
      fmAmount.setFloat(t, 0.0f);
      arpOn.setInt(t, 0L);
      arpMode.setInt(t, 0L);
      arpRate.setFloat(t, 1.0f);
      arpOctave.setInt(t, 1L);
      filter.setFloat(t * 2, 1.0);
      filter.setFloat(t * 2 + 1, 0.5);
      filterMode.setInt(t, 0L);
      filterMorph.setFloat(t, 0.0);
      delaySend.setFloat(t, 0.0);
      reverbSend.setFloat(t, 0.15);
    }
    for (int e = 0; e < ENV_COUNT; e++) {
      env.setFloat(e * ENV_PARAMS + 0, 0.01);
      env.setFloat(e * ENV_PARAMS + 1, 0.1);
      env.setFloat(e * ENV_PARAMS + 2, 0.7);
      env.setFloat(e * ENV_PARAMS + 3, 0.2);
    }
    for (int l = 0; l < LFO_COUNT; l++) {
      lfoRate.setFloat(l, 1.0);
      lfoType.setInt(l, 0L);
      lfoDepth.setFloat(l, 0.0);
    }
  }

  public void register(ChuckVM vm) {
    this.vm = vm;
    // Scalars
    if (!vm.isGlobalDouble(G_BPM)) vm.setGlobalFloat(G_BPM, 120.0);
    if (!vm.isGlobalDouble(G_SWING)) vm.setGlobalFloat(G_SWING, 0.5);
    if (!vm.isGlobalInt(G_PLAY)) vm.setGlobalInt(G_PLAY, 0L);
    if (!vm.isGlobalInt(G_RECORD_ON)) vm.setGlobalInt(G_RECORD_ON, 0L);
    if (!vm.isGlobalInt(G_CURRENT_STEP)) vm.setGlobalInt(G_CURRENT_STEP, -1L);
    if (!vm.isGlobalDouble(G_MASTER_VOL)) vm.setGlobalFloat(G_MASTER_VOL, 0.7);
    if (!vm.isGlobalDouble(G_MASTER_PAN)) vm.setGlobalFloat(G_MASTER_PAN, 0.0);
    if (!vm.isGlobalDouble(G_DELAY_TIME)) vm.setGlobalFloat(G_DELAY_TIME, 0.375);
    if (!vm.isGlobalDouble(G_DELAY_FB)) vm.setGlobalFloat(G_DELAY_FB, 0.4);
    if (!vm.isGlobalDouble(G_REVERB_ROOM)) vm.setGlobalFloat(G_REVERB_ROOM, 0.6);
    if (!vm.isGlobalDouble(G_REVERB_DAMP)) vm.setGlobalFloat(G_REVERB_DAMP, 0.5);
    if (!vm.isGlobalInt(G_STUTTER_ON)) vm.setGlobalInt(G_STUTTER_ON, 0L);
    if (!vm.isGlobalDouble(G_STUTTER_DIV)) vm.setGlobalFloat(G_STUTTER_DIV, 4.0);
    if (!vm.isGlobalInt(G_SCALE)) vm.setGlobalInt(G_SCALE, 0L);
    if (!vm.isGlobalInt(G_ROOT_KEY)) vm.setGlobalInt(G_ROOT_KEY, 0L);
    if (!vm.isGlobalDouble(G_SIDECHAIN_AMOUNT)) vm.setGlobalFloat(G_SIDECHAIN_AMOUNT, 0.5);
    if (!vm.isGlobalDouble(G_MASTER_COMP)) vm.setGlobalFloat(G_MASTER_COMP, 0.1);

    // Arrays
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
    vm.setGlobalObject(G_FM_RATIO, fmRatio);
    vm.setGlobalObject(G_FM_AMOUNT, fmAmount);
    vm.setGlobalObject(G_ARP_ON, arpOn);
    vm.setGlobalObject(G_ARP_MODE, arpMode);
    vm.setGlobalObject(G_ARP_RATE, arpRate);
    vm.setGlobalObject(G_ARP_OCTAVE, arpOctave);

    // Objects
    vm.setGlobalObject(G_DELAY_IN, delayIn);
    vm.setGlobalObject(G_REVERB_IN, reverbIn);
    vm.setGlobalObject(G_MOD_IN, modIn);
    vm.setGlobalObject(G_SYNTH_BUS, synthBus);
    vm.setGlobalObject(E_TICK, tickEvent);
    vm.setGlobalObject(E_SIDECHAIN, sidechainEvent);
    vm.setGlobalObject(E_MIDI_NOTE_ON, midiNoteOn);
    vm.setGlobalObject(E_MIDI_NOTE_OFF, midiNoteOff);
  }

  public ChuckVM getVm() { return vm; }
  public void setUseJavaEngine(boolean use) { this.useJavaEngine = use; }
  public boolean isUseJavaEngine() { return useJavaEngine; }

  public void setRecording(boolean recording) {
    this.recording = recording;
    if (vm != null) vm.setGlobalInt(G_RECORD_ON, recording ? 1L : 0L);
  }
  public boolean isRecording() { return recording; }

  public void triggerMidiNoteOn() { midiNoteOn.broadcast(vm); }
  public void triggerMidiNoteOff() { midiNoteOff.broadcast(vm); }

  // ── Accessors (Strictly Clamped) ──────────────────────────────────────────
  public void setStep(int t, int s, boolean a) { pattern.setInt(t * STEPS + s, a ? 1L : 0L); }
  public boolean getStep(int t, int s) { return pattern.getInt(t * STEPS + s) > 0; }
  
  public void setVelocity(int t, int s, double v) { 
    velocity.setFloat(t * STEPS + s, (float) Math.max(0, Math.min(1.0, v))); 
  }
  public double getVelocity(int t, int s) { return velocity.getFloat(t * STEPS + s); }
  
  public void setGate(int t, int s, double v) { 
    gate.setFloat(t * STEPS + s, (float) Math.max(0, Math.min(1.0, v))); 
  }
  public double getGate(int t, int s) { return gate.getFloat(t * STEPS + s); }
  
  public void setPitch(int t, int s, int p) { pitch.setInt(t * STEPS + s, (long)p); }
  public int getPitch(int t, int s) { return (int)pitch.getInt(t * STEPS + s); }
  
  public void setStepProbability(int t, int s, double v) { 
    probability.setFloat(t * STEPS + s, (float) Math.max(0, Math.min(1.0, v))); 
  }
  public double getStepProbability(int t, int s) { return probability.getFloat(t * STEPS + s); }
  
  public void setStepFilter(int t, int s, double v) { stepFilter.setFloat(t * STEPS + s, (float)v); }
  public double getStepFilter(int t, int s) { return stepFilter.getFloat(t * STEPS + s); }

  public void setStepRes(int t, int s, double v) { stepRes.setFloat(t * STEPS + s, (float)v); }
  public double getStepRes(int t, int s) { return stepRes.getFloat(t * STEPS + s); }

  public void setStepPan(int t, int s, double v) { stepPan.setFloat(t * STEPS + s, (float)v); }
  public double getStepPan(int t, int s) { return stepPan.getFloat(t * STEPS + s); }

  public void setStepDelay(int t, int s, double v) { stepDelay.setFloat(t * STEPS + s, (float)v); }
  public double getStepDelay(int t, int s) { return stepDelay.getFloat(t * STEPS + s); }

  public void setStepReverb(int t, int s, double v) { stepReverb.setFloat(t * STEPS + s, (float)v); }
  public double getStepReverb(int t, int s) { return stepReverb.getFloat(t * STEPS + s); }

  public void setStepMod(int t, int s, double v) { stepMod.setFloat(t * STEPS + s, (float)v); }
  public double getStepMod(int t, int s) { return stepMod.getFloat(t * STEPS + s); }

  public void setStepStart(int t, int s, double v) { stepStart.setFloat(t * STEPS + s, (float)v); }
  public double getStepStart(int t, int s) { return stepStart.getFloat(t * STEPS + s); }

  public void setStepEnd(int t, int s, double v) { stepEnd.setFloat(t * STEPS + s, (float)v); }
  public double getStepEnd(int t, int s) { return stepEnd.getFloat(t * STEPS + s); }

  public void setTrackLevel(int t, double v) { 
    trackLevel.setFloat(t, (float) Math.max(0, Math.min(1.0, v))); 
  }
  public double getTrackLevel(int t) { return trackLevel.getFloat(t); }
  
  public void setMute(int t, boolean v) { mute.setInt(t, v ? 1L : 0L); }
  public boolean getMute(int t) { return mute.getInt(t) > 0; }
  
  public void setFilterFreq(int t, double v) { filter.setFloat(t * 2, (float)v); }
  public double getTrackFilterFreq(int t) { return filter.getFloat(t * 2); }
  public void setFilterRes(int t, double v) { filter.setFloat(t * 2 + 1, (float)v); }
  public double getTrackFilterRes(int t) { return filter.getFloat(t * 2 + 1); }
  public void setFilterMode(int t, int m) { filterMode.setInt(t, (long)m); }
  
  public void setArpOn(int t, boolean o) { arpOn.setInt(t, o ? 1L : 0L); }
  public boolean getArpOn(int t) { return arpOn.getInt(t) > 0; }
  public void setArpRate(int t, double r) { arpRate.setFloat(t, (float)r); }
  public double getArpRate(int t) { return arpRate.getFloat(t); }
  public void setArpOctave(int t, int o) { arpOctave.setInt(t, (long)o); }
  public int getArpOctave(int t) { return (int)arpOctave.getInt(t); }
  
  public void setFmRatio(int t, double r) { fmRatio.setFloat(t, (float)r); }
  public double getFmRatio(int t) { return fmRatio.getFloat(t); }
  public void setFmAmount(int t, double a) { fmAmount.setFloat(t, (float)a); }
  public double getFmAmount(int t) { return fmAmount.getFloat(t); }

  public void setEnv(int eIdx, double a, double d, double s, double r) {
    int b = eIdx * ENV_PARAMS;
    env.setFloat(b + 0, (float)Math.max(0.001, a));
    env.setFloat(b + 1, (float)Math.max(0.001, d));
    env.setFloat(b + 2, (float)Math.max(0, Math.min(1, s)));
    env.setFloat(b + 3, (float)Math.max(0.001, r));
  }

  public void loadSynthPreset(int t, org.chuck.deluge.model.SynthTrackModel m) {
    setFilterFreq(t, m.getLpfFreq() / 20000.0);
    setFilterRes(t, m.getLpfRes());
    setFilterMode(t, m.getFilterMode().ordinal());
    for (int i = 0; i < 4; i++) {
        var e = m.getEnv(i);
        if (e != null) setEnv(i, e.attack(), e.decay(), e.sustain(), e.release());
    }
    if (m.getArp() != null) {
        setArpOn(t, m.getArp().active());
        setArpRate(t, m.getArp().rate());
        setArpOctave(t, m.getArp().octaves());
    }
  }

  public void loadClip(int t, int s) {
    var c = clipLibrary.getClip(t, s);
    if (c == null) return;
    activeClipSlots[t] = s;
    for (int i = 0; i < STEPS; i++) {
      setStep(t, i, c.getTrigger(i));
      setVelocity(t, i, c.getVelocity(i));
      setGate(t, i, c.getGate(i));
      setPitch(t, i, c.getPitch(i));
      setStepProbability(t, i, c.getProbability(i));
    }
  }

  public void syncActiveClipToLibrary(int t) {
    var c = clipLibrary.getClip(t, activeClipSlots[t]);
    if (c == null) return;
    for (int i = 0; i < STEPS; i++) {
      c.setTrigger(i, getStep(t, i));
      c.setVelocity(i, getVelocity(t, i));
      c.setGate(i, getGate(t, i));
      c.setPitch(i, getPitch(t, i));
      c.setProbability(i, getStepProbability(t, i));
    }
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

  public org.chuck.deluge.model.ClipLibrary getClipLibrary() { return clipLibrary; }
  public ChuckArray patternArray() { return pattern; }
  public ChuckArray probabilityArray() { return probability; }
}
