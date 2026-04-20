package org.chuck.deluge;

import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;

/**
 * Typed builder that creates and registers every shared global between Java UI and ChucK engine.
 *
 * <p>Call {@link #register(ChuckVM)} before loading any .ck file. Re-call after {@link
 * ChuckVM#clear()} to re-bind the same array objects (their Java references remain valid).
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
  public static final String G_SCALE = "g_scale";
  public static final String G_ROOT_KEY = "g_root_key";

  // Advanced DSP (v1.2+)
  public static final String G_FM_RATIO = "g_fm_ratio"; // float[TRACKS]
  public static final String G_FM_AMOUNT = "g_fm_amount"; // float[TRACKS]
  public static final String G_SIDECHAIN_AMOUNT = "g_sidechain_amount"; // float 0.0..1.0
  public static final String G_MASTER_COMP = "g_master_comp";

  // Arpeggiator (v1.6+)
  public static final String G_ARP_ON = "g_arp_on"; // int[TRACKS] 0/1
  public static final String G_ARP_MODE = "g_arp_mode"; // int[TRACKS] 0=UP, 1=DOWN, 2=UPDOWN, 3=RAND
  public static final String G_ARP_RATE = "g_arp_rate"; // float[TRACKS] 1.0=1/16, 0.5=1/8, etc.
  public static final String G_ARP_OCTAVE = "g_arp_octave"; // int[TRACKS] 1-4

  // Events
  public static final String E_MIDI_NOTE_ON = "midi_note_on";
  public static final String E_MIDI_NOTE_OFF = "midi_note_off";

  // ── arrays ─────────────────────────────────────────────────────────────────
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

  private final org.chuck.core.ChuckEvent midiNoteOn;
  private final org.chuck.core.ChuckEvent midiNoteOff;

  private ChuckVM vm;
  private boolean recording = false;
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

    midiNoteOn = new org.chuck.core.ChuckEvent();
    midiNoteOff = new org.chuck.core.ChuckEvent();

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
      arpMode.setInt(t, 0L); // UP
      arpRate.setFloat(t, 1.0f); // 1.0 = 1/16th note
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
    if (!vm.isGlobalInt(G_SCALE)) vm.setGlobalInt(G_SCALE, 0L);
    if (!vm.isGlobalInt(G_ROOT_KEY)) vm.setGlobalInt(G_ROOT_KEY, 0L);

    // Advanced DSP
    vm.setGlobalObject(G_FM_RATIO, fmRatio);
    vm.setGlobalObject(G_FM_AMOUNT, fmAmount);
    if (!vm.isGlobalDouble(G_SIDECHAIN_AMOUNT)) vm.setGlobalFloat(G_SIDECHAIN_AMOUNT, 0.5);
    if (!vm.isGlobalDouble(G_MASTER_COMP)) vm.setGlobalFloat(G_MASTER_COMP, 0.1); // subtle by default

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

    vm.setGlobalObject(G_ARP_ON, arpOn);
    vm.setGlobalObject(G_ARP_MODE, arpMode);
    vm.setGlobalObject(G_ARP_RATE, arpRate);
    vm.setGlobalObject(G_ARP_OCTAVE, arpOctave);

    vm.setGlobalObject(E_MIDI_NOTE_ON, midiNoteOn);
    vm.setGlobalObject(E_MIDI_NOTE_OFF, midiNoteOff);
  }

  public ChuckVM getVm() {
    return vm;
  }

  /** Load a Clip into a specific track row in the VM arrays. */
  public void loadClip(int track, int slot) {
    org.chuck.deluge.model.Clip clip = clipLibrary.getClip(track, slot);
    if (clip == null) return;

    activeClipSlots[track] = slot;
    for (int s = 0; s < STEPS; s++) {
      setStep(track, s, clip.getTrigger(s));
      setVelocity(track, s, clip.getVelocity(s));
      setGate(track, s, clip.getGate(s));
      setPitch(track, s, clip.getPitch(s));
      setStepProbability(track, s, clip.getProbability(s));
    }
  }

  /** Update the library clip from the current VM state for a track. */
  public void syncActiveClipToLibrary(int track) {
    int slot = activeClipSlots[track];
    org.chuck.deluge.model.Clip clip = clipLibrary.getClip(track, slot);
    if (clip == null) return;

    for (int s = 0; s < STEPS; s++) {
      clip.setTrigger(s, getStep(track, s));
      clip.setVelocity(s, getVelocity(track, s));
      clip.setGate(s, getGate(track, s));
      clip.setPitch(s, getPitch(track, s));
      clip.setProbability(s, getStepProbability(track, s));
    }
  }

  public org.chuck.deluge.model.ClipLibrary getClipLibrary() {
    return clipLibrary;
  }

  public void setRecording(boolean recording) {
    this.recording = recording;
    if (vm != null) vm.setGlobalInt(G_RECORD_ON, recording ? 1L : 0L);
  }

  public boolean isRecording() {
    return recording;
  }

  public void triggerMidiNoteOn() {
    midiNoteOn.broadcast(vm);
  }

  public void triggerMidiNoteOff() {
    midiNoteOff.broadcast(vm);
  }

  // ── Getters/Setters ────────────────────────────────────────────────────────

  public void setStep(int track, int step, boolean active) {
    pattern.setInt(track * STEPS + step, active ? 1L : 0L);
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
    gate.setFloat(track * STEPS + step, (float) Math.max(0, Math.min(1, val)));
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
  }

  public boolean getMute(int track) {
    return mute.getInt(track) > 0;
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

  public void setArpOn(int track, boolean on) {
    arpOn.setInt(track, on ? 1L : 0L);
  }

  public boolean getArpOn(int track) {
    return arpOn.getInt(track) > 0;
  }

  public void setArpMode(int track, int mode) {
    arpMode.setInt(track, (long) mode);
  }

  public int getArpMode(int track) {
    return (int) arpMode.getInt(track);
  }

  public void setArpRate(int track, double rate) {
    arpRate.setFloat(track, (float) rate);
  }

  public double getArpRate(int track) {
    return arpRate.getFloat(track);
  }

  public void setArpOctave(int track, int oct) {
    arpOctave.setInt(track, (long) oct);
  }

  public int getArpOctave(int track) {
    return (int) arpOctave.getInt(track);
  }

  public void setFmRatio(int track, double ratio) {
    fmRatio.setFloat(track, (float) ratio);
  }

  public double getFmRatio(int track) {
    return fmRatio.getFloat(track);
  }

  public void setFmAmount(int track, double amount) {
    fmAmount.setFloat(track, (float) amount);
  }

  public double getFmAmount(int track) {
    return fmAmount.getFloat(track);
  }

  public void setEnv(int envIndex, double a, double d, double s, double r) {
    int b = envIndex * ENV_PARAMS;
    env.setFloat(b + 0, (float) Math.max(0.001, a));
    env.setFloat(b + 1, (float) Math.max(0.001, d));
    env.setFloat(b + 2, (float) Math.max(0, Math.min(1, s)));
    env.setFloat(b + 3, (float) Math.max(0.001, r));
  }

  /**
   * Loads a full synth preset model into the bridge state for a specific track.
   */
  public void loadSynthPreset(int trackIndex, org.chuck.deluge.model.SynthTrackModel model) {
    // Note: trackIndex 4-7 are synth tracks in our MVP
    // We update oscillator, envelopes, filter etc.
    
    // For now our engine uses MorphingWavetable, so we just log the type change 
    // or we could map it to a morph position if we had a more complex model.
    System.out.println("Applying preset to track " + trackIndex + ": " + model.getName());
    
    // Filter
    setFilterFreq(trackIndex, model.getLpfFreq() / 20000.0); // Simple normalization
    setFilterRes(trackIndex, model.getLpfRes());
    setFilterMode(trackIndex, model.getFilterMode().ordinal());

    // Envelopes
    for (int i = 0; i < 4; i++) {
        org.chuck.deluge.model.EnvelopeModel e = model.getEnv(i);
        if (e != null) {
            setEnv(i, e.attack(), e.decay(), e.sustain(), e.release());
        }
    }
    
    // Arp
    if (model.getArp() != null) {
        setArpOn(trackIndex, model.getArp().active());
        setArpRate(trackIndex, model.getArp().rate());
        setArpOctave(trackIndex, model.getArp().octaves());
    }
  }

  public void setLfo(int lfoIndex, double rateHz, int waveType, double depth) {
    lfoRate.setFloat(lfoIndex, (float) Math.max(0.01, rateHz));
    lfoType.setInt(lfoIndex, (long) waveType);
    lfoDepth.setFloat(lfoIndex, (float) Math.max(0, Math.min(1, depth)));
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

  // compat for UI
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

  public ChuckArray probabilityArray() {
    return probability;
  }
}
