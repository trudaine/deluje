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

  // ── Dimensions ────────────────────────────────────────────────────────

  /** Number of engine tracks (rows / voices). Configurable via {@code -Ddeluge.tracks=N}. */
  public static final int TRACKS = Integer.getInteger("deluge.tracks", 64);
  /** Maximum step capacity per clip, matching real Deluge hardware (1-192). */
  public static final int STEPS = 192;
  /** Flat stride array size: {@code TRACKS × STEPS}. All 14 step-data arrays use this. */
  public static final int PATTERN_SIZE = TRACKS * STEPS;

  /** Number of envelope generators available (one per track row). */
  public static final int ENV_COUNT = 4;
  /** Number of ADSR parameters per envelope (attack, decay, sustain, release). */
  public static final int ENV_PARAMS = 4;
  /** Envelope array stride: TRACKS × ENV_COUNT × ENV_PARAMS. Four 4-element blocks per track row (one per envelope generator). */
  public static final int ENV_STRIDE = TRACKS * ENV_COUNT * ENV_PARAMS;
  /** Number of LFO generators available globally. Each has rate, type, depth, target, track. */
  public static final int LFO_COUNT = 4;

  // ── Transport & Master ─────────────────────────────────────────────────

  /** Scalar: current BPM (float). */
  public static final String G_BPM = "g_bpm";
  /** Scalar: swing amount 0-1 (float). */
  public static final String G_SWING = "g_swing";
  /** Scalar: 1 = playing, 0 = stopped (int). */
  public static final String G_PLAY = "g_play";
  /** Scalar: sequencer step currently being played (int). Written by clock_shred, read by kit/synth. */
  public static final String G_CURRENT_STEP = "g_current_step";
  /** Scalar: 1 = stutter active (int). Freezes step counter and repeats current tick at higher rate. */
  public static final String G_STUTTER_ON = "g_stutter_on";
  /** Scalar: stutter division factor (float). Higher = faster repeat. */
  public static final String G_STUTTER_DIV = "g_stutter_div";

  // ── Step-data arrays (size PATTERN_SIZE each) ─────────────────────────

  /** Step on/off (int array, 0 or 1). */
  public static final String G_PATTERN = "g_pattern";
  /** Per-step velocity 0-1 (float array). */
  public static final String G_VELOCITY = "g_velocity";
  /** Per-step gate time 0-1 (float array). Fraction of step duration the note sounds. */
  public static final String G_GATE = "g_gate";
  /** Per-step pitch offset (int array). MIDI note number when in synth mode. */
  public static final String G_PITCH = "g_pitch";
  /** Per-step probability 0-1 (float array). Engine probabilistically skips steps below threshold. */
  public static final String G_PROBABILITY = "g_probability";
  /** Per-step filter cutoff offset (float array). Added to track filter freq. */
  public static final String G_STEP_FILTER = "g_step_filter";
  /** Per-step filter resonance offset (float array). */
  public static final String G_STEP_RES = "g_step_res";
  /** Per-step filter mode override (int array). -1 = use track mode, 0=LP, 1=HP, 2=BP, 3=NOTCH. */
  public static final String G_STEP_FILTER_MODE = "g_step_filter_mode";
  /** Per-step pan offset -1 to 1 (float array). Added to track pan. */
  public static final String G_STEP_PAN = "g_step_pan";
  /** Per-step delay send amount 0-1 (float array). */
  public static final String G_STEP_DELAY = "g_step_delay";
  /** Per-step reverb send amount 0-1 (float array). */
  public static final String G_STEP_REVERB = "g_step_reverb";
  /** Per-step general modulation (float array). Mapped to applicable parameter by track type. */
  public static final String G_STEP_MOD = "g_step_mod";
  /** Per-step sample start position 0-1 (float array). 0 = beginning of sample. */
  public static final String G_STEP_START = "g_step_start";
  /** Per-step sample end position 0-1 (float array). 1 = end of sample. */
  public static final String G_STEP_END = "g_step_end";

  // ── Track-level arrays (size TRACKS each) ─────────────────────────────

  /** Track type per engine row (int array). 0=kit, 1=synth, 2=audio. */
  public static final String G_TRACK_TYPE = "g_track_type";
  /**
   * Oscillator/wave index for synth carriers (int array). Not registered in the VM by default —
   * the UI pushes it locally and both engine and UI null-guard, defaulting to 0 (sine)
   * when absent.
   */
  public static final String G_OSC_TYPE = "g_osc_type";
  /** Per-track output level 0-1 (float array). */
  public static final String G_TRACK_LEVEL = "g_track_level";
  /** Per-track mute state (int array). 0=unmuted, 1=muted. */
  public static final String G_MUTE = "g_mute";

  /** Scalar: master output volume 0-1 (float). */
  public static final String G_MASTER_VOL = "g_master_vol";
  /** Scalar: master pan -1 to 1 (float). -1=full left, 1=full right, 0=centre. */
  public static final String G_MASTER_PAN = "g_master_pan";

  // ── Per-track synth/filter arrays ──────────────────────────────────────

  /** Per-track filter state (float array, size TRACKS×2). Even index = freq, odd = resonance. */
  public static final String G_FILTER = "g_filter";
  /** Per-track filter mode (int array). 0=LP, 1=HP, 2=BP, 3=NOTCH. */
  public static final String G_FILTER_MODE = "g_filter_mode";
  /** Per-track filter morph 0-1 (float array). Blends between filter topologies. */
  public static final String G_FILTER_MORPH = "g_filter_morph";
  /** Envelope parameters (float array, size ENV_COUNT×ENV_PARAMS). Layout: [a, d, s, r] per env. */
  public static final String G_ENV = "g_env";
  /** Per-LFO rate in Hz (float array, size LFO_COUNT). */
  public static final String G_LFO_RATE = "g_lfo_rate";
  /** Per-LFO waveform type (int array). 0=sine, 1=saw, 2=square, 3=triangle. */
  public static final String G_LFO_TYPE = "g_lfo_type";
  /** Per-LFO modulation depth 0-1 (float array). */
  public static final String G_LFO_DEPTH = "g_lfo_depth";

  /**
   * Per-LFO target parameter (int array).
   * Values: 0=filter, 1=resonance, 2=pan, 3=pitch, 4=volume, 5=FM amount.
   */
  public static final String G_LFO_TARGET = "g_lfo_target";
  /** Per-LFO assigned engine track (int array). -1 = all tracks. */
  public static final String G_LFO_TRACK = "g_lfo_track";
  /** Per-LFO current output value (float array). Written by engine, read by UI for visualisation. */
  public static final String G_LFO_VALUE = "g_lfo_value";
  /** Per-track step length (int array). Number of steps before wrapping to 0 (polyrhythm). */
  public static final String G_TRACK_LENGTH = "g_track_length";

  // ── FX Sends ───────────────────────────────────────────────────────────

  /** Per-track delay send amount 0-1 (float array). */
  public static final String G_DELAY_SEND = "g_delay_send";
  /** Per-track reverb send amount 0-1 (float array). */
  public static final String G_REVERB_SEND = "g_reverb_send";
  /** Scalar: delay time in seconds (float). Written by UI, read by Echo UGen. */
  public static final String G_DELAY_TIME = "g_delay_time";
  /** Scalar: delay feedback amount 0-1 (float). */
  public static final String G_DELAY_FB = "g_delay_fb";
  /** Scalar: reverb room/mix size 0-1 (float). Passed to JCRev, FreeVerb, or MVerb. */
  public static final String G_REVERB_ROOM = "g_reverb_room";
  /** Scalar: reverb damping 0-1 (float). */
  public static final String G_REVERB_DAMP = "g_reverb_damp";

  // ── Scale / MIDI ──────────────────────────────────────────────────────

  /** Scalar: musical scale index (int). Maps to Scale enum in engine. 0=chromatic, 1=major, etc. */
  public static final String G_SCALE = "g_scale";
  /** Scalar: root MIDI note for scale (int). 0=C, 1=C#, etc. */
  public static final String G_ROOT_KEY = "g_root_key";

  // ── Events ─────────────────────────────────────────────────────────────

  /**
   * Event broadcast to trigger a song/kit reload in the engine. UI fires this after loading
   * a new project or preset. All sub-shreds waiting on this event re-read their UGens.
   */
  public static final String G_LOAD_TRIGGER = "g_load_trigger";
  /**
   * Alias for {@link #E_TICK}. Deprecated name kept for backward compatibility.
   * Use {@code E_TICK} in new code.
   */
  public static final String TICK_EVENT = "tick_event";
  /**
   * Event broadcast by clock_shred on every sequencer step. All sub-shreds (kit, synth, audio,
   * FX, master, sidechain) advance on this event, making the engine fully event-driven.
   */
  public static final String E_TICK = "tick_event";

  // ── Engine Internals (UGen buses) ──────────────────────────────────────

  /** Gain UGen: input bus for the delay (Echo) effect. Kit/synth voices send to this. */
  public static final String G_DELAY_IN = "g_delay_in";
  /** Gain UGen: input bus for the reverb (JCRev/FreeVerb/MVerb) effect. */
  public static final String G_REVERB_IN = "g_reverb_in";
  /** Gain UGen: submix bus for all synth voices. Routed through HPF → compressor → limiter. */
  public static final String G_SYNTH_BUS = "g_synth_bus";
  /** Scalar: master compressor threshold (float). 0=full compression, 1=no compression. */
  public static final String G_MASTER_COMP = "g_master_comp";

  // ── Arp & FM ───────────────────────────────────────────────────────────

  /** Per-track arpeggiator on/off (int array). */
  public static final String G_ARP_ON = "g_arp_on";
  /** Per-track arpeggiator rate multiplier (float array). 1.0 = quarter-note rate. */
  public static final String G_ARP_RATE = "g_arp_rate";
  /** Per-track arpeggiator octave range (int array). Number of octaves to arpeggiate over. */
  public static final String G_ARP_OCTAVE = "g_arp_octave";
  /** Per-track arpeggiator mode (int array). 0=UP, 1=DOWN, 2=UP_DOWN, 3=RANDOM. */
  public static final String G_ARP_MODE = "g_arp_mode";
  /** Per-track FM modulator/carrier frequency ratio (float array). */
  public static final String G_FM_RATIO = "g_fm_ratio";
  /** Per-track FM modulation amount (float array). Output gain of the modulator UGen. */
  public static final String G_FM_AMOUNT = "g_fm_amount";

  // ── Audition / Preview ─────────────────────────────────────────────────

  /** Engine track index to preview/audition (int). The preview_shred reads this on E_PREVIEW. */
  public static final String G_PREVIEW_TRACK = "g_preview_track";
  /** Event: broadcast when UI requests a pad preview (single note audition). */
  public static final String E_PREVIEW = "e_preview";
  /** Event: broadcast by kit row 0 to trigger sidechain ducking on synth bus. */
  public static final String E_SIDECHAIN = "e_sidechain";

  // ── Synth Algorithm ──────────────────────────────────────────────────

  /**
   * Per-track synthesis algorithm selector (int array).
   * 0 = FM (dual MorphingWavetable), 10 = Mandolin, 11 = Rhodey, 12 = ModalBar, 13 = Moog.
   * Codes 10+ select STK physical models.
   */
  public static final String G_SYNTH_ALGO = "g_synth_algo";
  /** Per-track synth mode (int array). 0 = SUBTRACTIVE (single osc+filter), 1 = FM (mod→car), 2 = RINGMOD (car×mod). */
  public static final String G_SYNTH_MODE = "g_synth_mode";

  // ── Kit per-drum ADSR + Pitch ─────────────────────────────────────────

  /** Per-voice kit envelope attack time in seconds (float array). */
  public static final String G_KIT_ATTACK = "g_kit_attack";
  /** Per-voice kit envelope decay time in seconds (float array). */
  public static final String G_KIT_DECAY = "g_kit_decay";
  /** Per-voice kit envelope sustain level 0-1 (float array). */
  public static final String G_KIT_SUSTAIN = "g_kit_sustain";
  /** Per-voice kit envelope release time in seconds (float array). */
  public static final String G_KIT_RELEASE = "g_kit_release";
  /** Per-voice pitch offset in semitones (float array). Applied as playback rate multiplier. */
  public static final String G_KIT_PITCH = "g_kit_pitch";
  /** Per-voice reverse playback flag (int array). 1 = play sample backwards. */
  public static final String G_KIT_REVERSE = "g_kit_reverse";
  /**
   * Per-voice mute group assignment (int array). Tracks sharing the same non-zero mute group
   * choke each other: when one fires, all others in the group are silenced.
   */
  public static final String G_KIT_MUTE_GROUP = "g_kit_mute_group";

  // ── Audio Track (LiSa) ─────────────────────────────────────────────────

  /** Per-track LiSa recording state (int array). 1 = recording, 0 = idle. */
  public static final String G_AUDIO_REC = "g_audio_rec";
  /** Per-track LiSa playback state (int array). 1 = playing, 0 = stopped. */
  public static final String G_AUDIO_PLAY = "g_audio_play";
  /** Per-track LiSa loop mode (int array). 1 = loop, 0 = one-shot. */
  public static final String G_AUDIO_LOOP = "g_audio_loop";
  /** Per-track LiSa playback rate multiplier (float array). 0.25-4.0. */
  public static final String G_AUDIO_RATE = "g_audio_rate";
  /** Gain UGen: submix bus for all LiSa audio track outputs. Routed through master processing. */
  public static final String G_AUDIO_BUS = "g_audio_bus";

  // ── WvOut Export ──────────────────────────────────────────────────────

  /** Scalar: 1.0 when export is active (float). The export_shred polls this each cycle. */
  public static final String G_WVOUT_ACTIVE = "g_wvout_active";
  /** String: target file path for WAV export (String). Set by UI before starting export. */
  public static final String G_WVOUT_FILE = "g_wvout_file";

  /** Gain UGen: output tap placed just before dac. WvOut2 splices in here during export. */
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
  private final ChuckArray arpMode;
  private final ChuckArray fmRatio;
  private final ChuckArray fmAmount;

  private final ChuckArray synthAlgo;
  private final ChuckArray synthMode;

  private final ChuckArray audioRec;
  private final ChuckArray audioPlay;
  private final ChuckArray audioLoop;
  private final ChuckArray audioRate;

  private ChuckVM vm;
  private boolean recording = false;

  /**
   * Creates all 31 typed ChuckArrays with default dimensions and initialises every slot to its
   * default value ({@link #initDefaults()}). Called once from the UI thread at application start.
   * Step-data arrays are sized to {@link #PATTERN_SIZE}; track-level arrays to {@link #TRACKS}.
   */
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
    trackLevel = new ChuckArray("float", TRACKS);
    mute = new ChuckArray("int", TRACKS);
    filter = new ChuckArray("float", TRACKS * 2);
    filterMode = new ChuckArray("int", TRACKS);
    filterMorph = new ChuckArray("float", TRACKS);
    env = new ChuckArray("float", ENV_STRIDE);
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
    arpMode = new ChuckArray("int", TRACKS);
    fmRatio = new ChuckArray("float", TRACKS);
    fmAmount = new ChuckArray("float", TRACKS);
    synthAlgo = new ChuckArray("int", TRACKS);
    synthMode = new ChuckArray("int", TRACKS);

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
      arpMode.setInt(t, 0L);
      fmRatio.setFloat(t, 1.0f);
      fmAmount.setFloat(t, 0.0f);
      synthAlgo.setInt(t, 0L);
      synthMode.setInt(t, 0L);

      audioRec.setInt(t, 0L);
      audioPlay.setInt(t, 0L);
      audioLoop.setInt(t, 1L);
      audioRate.setFloat(t, 1.0f);
    }
    for (int e = 0; e < TRACKS * ENV_COUNT; e++) {
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

  /**
   * Registers all globals and UGens in the ChucK VM. Called once after the VM is created.
   * Registers:
   * <ul>
   *   <li>Scalar globals: BPM, swing, play state, step counter, master vol/pan, FX params, scale</li>
   *   <li>All 31 typed ChuckArrays as global objects (step data + track params)</li>
   *   <li>Engine bus UGens: g_delay_in, g_reverb_in, g_synth_bus, g_audio_bus (Gain)</li>
   *   <li>Events: tick_event, e_preview, e_sidechain, g_load_trigger</li>
   *   <li>Export state: g_wvout_active (0.0), g_wvout_file ("")</li>
   * </ul>
   *
   * @param vm the ChucK VM instance (must be started but may not yet be running).
   */
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
    vm.setGlobalObject(G_ARP_MODE, arpMode);
    vm.setGlobalObject(G_FM_RATIO, fmRatio);
    vm.setGlobalObject(G_FM_AMOUNT, fmAmount);
    vm.setGlobalObject(G_SYNTH_ALGO, synthAlgo);
    vm.setGlobalObject(G_SYNTH_MODE, synthMode);

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

  /**
   * Clears the entire pattern array (all steps set to 0/inactive). Does not touch velocity,
   * pitch, or modulation data — those remain at their previous values so re-enabled steps
   * retain their parameters.
   */
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

  /**
   * Sets the four ADSR parameters for one envelope generator on one track row. Values are clamped:
   * attack/decay/release each minimum 0.001 seconds, sustain clamped 0-1.
   *
   * @param row track row index (0 to {@code TRACKS-1}).
   * @param envIndex envelope generator index (0 to {@code ENV_COUNT-1}).
   * @param a attack time in seconds.
   * @param d decay time in seconds.
   * @param s sustain level (0-1).
   * @param r release time in seconds.
   */
  public void setEnv(int row, int envIndex, double a, double d, double s, double r) {
    int b = (row * ENV_COUNT + envIndex) * ENV_PARAMS;
    env.setFloat(b + 0, (float) Math.max(0.001, a));
    env.setFloat(b + 1, (float) Math.max(0.001, d));
    env.setFloat(b + 2, (float) Math.max(0, Math.min(1, s)));
    env.setFloat(b + 3, (float) Math.max(0.001, r));
  }

  /**
   * Sets all parameters for one LFO generator. Rate is clamped to minimum 0.01 Hz,
   * depth is clamped 0-1.
   *
   * @param lfoIndex LFO index (0 to {@code LFO_COUNT-1}).
   * @param rateHz LFO oscillation rate in Hz.
   * @param waveType waveform: 0=sine, 1=saw, 2=square, 3=triangle.
   * @param depth modulation depth 0-1.
   */
  public void setLfo(int lfoIndex, double rateHz, int waveType, double depth) {
    lfoRate.setFloat(lfoIndex, (float) Math.max(0.01, rateHz));
    lfoType.setInt(lfoIndex, (long) waveType);
    lfoDepth.setFloat(lfoIndex, (float) Math.max(0, Math.min(1, depth)));
  }

  /**
   * Sets the modulation target for one LFO.
   *
   * @param lfoIndex LFO index (0 to {@code LFO_COUNT-1}).
   * @param target 0=filter, 1=resonance, 2=pan, 3=pitch, 4=volume, 5=FM amount.
   */
  public void setLfoTarget(int lfoIndex, int target) {
    lfoTarget.setInt(lfoIndex, (long) target);
  }

  public int getLfoTarget(int lfoIndex) {
    return (int) lfoTarget.getInt(lfoIndex);
  }

  /**
   * Restricts an LFO to a single engine track. -1 means the LFO affects all tracks.
   *
   * @param lfoIndex LFO index (0 to {@code LFO_COUNT-1}).
   * @param track engine track index, or -1 for global.
   */
  public void setLfoTrack(int lfoIndex, int track) {
    lfoTrack.setInt(lfoIndex, (long) track);
  }

  public int getLfoTrack(int lfoIndex) {
    return (int) lfoTrack.getInt(lfoIndex);
  }

  /**
   * Sets the step length for a track, enabling per-track polyrhythm. When track length differs
   * from the master step count, the track wraps to step 0 at its own boundary.
   * Clamped to minimum 1.
   *
   * @param track engine track index.
   * @param steps number of steps before wrapping (1-192 recommended; no upper bound enforced).
   */
  public void setTrackLength(int track, int steps) {
    trackLength.setInt(track, (long) Math.max(1, steps));
  }

  public int getTrackLength(int track) {
    return (int) trackLength.getInt(track);
  }

  /**
   * Sets the global BPM in the VM. The clock_shred reads this on every step to compute
   * tick duration with swing.
   *
   * @param bpm beats per minute (any positive value; no upper bound enforced).
   */
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

  public int getArpMode(int track) {
    return (int) arpMode.getInt(track);
  }

  public void setArpMode(int track, int mode) {
    arpMode.setInt(track, (long) mode);
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

  /**
   * Returns true when an offline WAV export is in progress. The export_shred sets
   * {@code G_WVOUT_ACTIVE} to 1.0 during recording and 0.0 when done.
   *
   * @return true if WvOut2 is currently recording to file.
   */
  public boolean isExporting() {
    return vm != null && vm.getGlobalFloat(G_WVOUT_ACTIVE) > 0.5;
  }

  /**
   * Starts an offline WAV export. Sets {@code G_WVOUT_FILE} to the target path and sets
   * {@code G_WVOUT_ACTIVE} to 1.0. The export_shred in DelugeEngineDSL polls this flag
   * and splices WvOut2 into the master output chain.
   *
   * @param filePath absolute or relative path for the output .wav file.
   */
  public void startExport(String filePath) {
    if (vm == null) return;
    vm.setGlobalString(G_WVOUT_FILE, filePath);
    vm.setGlobalFloat(G_WVOUT_ACTIVE, 1.0);
  }

  /**
   * Stops the current WAV export. Sets {@code G_WVOUT_ACTIVE} to 0.0. The export_shred
   * detects this, restores the original masterTap → dac chain, and closes the WvOut2 file.
   */
  public void stopExport() {
    if (vm == null) return;
    vm.setGlobalFloat(G_WVOUT_ACTIVE, 0.0);
  }

  /**
   * Stub: intended to sync the currently active clip to the library file system. In a future
   * implementation this would write the clip's step data to a preset file.
   *
   * @param track engine track index whose active clip to sync.
   */
  public void syncActiveClipToLibrary(int track) {}

  /**
   * Stub: intended to load a synth preset from disk and apply its parameters to a track.
   * Currently a no-op; preset loading is handled by the UI via XML deserialization.
   *
   * @param track engine track index.
   * @param model the SynthTrackModel to populate from the preset.
   */
  public void loadSynthPreset(int track, org.chuck.deluge.model.SynthTrackModel model) {}

  /**
   * Stub: intended to swap the active clip for a track at runtime. In Session mode this would
   * trigger the engine to read clipIdx's step data instead of the current clip.
   *
   * @param track engine track index.
   * @param clipIdx clip index within the track.
   */
  public void loadClip(int track, int clipIdx) {}

  /**
   * Clears all step-on bits in the pattern array. Identical to {@link #clearAllSteps()} but
   * does not iterate the full array — uses {@code PATTERN_SIZE} directly.
   */
  public void clearPattern() {
    for (int i = 0; i < PATTERN_SIZE; i++) pattern.setInt(i, 0L);
  }

  /**
   * Direct access to the underlying pattern {@link ChuckArray}. Used by the engine DSL to
   * read step-on bits without going through typed accessor indirection.
   *
   * @return the raw int array backing G_PATTERN.
   */
  public ChuckArray patternArray() {
    return pattern;
  }

  /**
   * Sets the global recording arm flag. When recording is enabled, step edits are persisted
   * to the backing model (future: MIDI/performance capture).
   *
   * @param r true to arm recording.
   */
  public void setRecording(boolean r) {
    this.recording = r;
  }

  /**
   * Returns the global recording arm state set by {@link #setRecording(boolean)}.
   *
   * @return true if recording is armed.
   */
  public boolean isRecording() {
    return recording;
  }

  /**
   * Returns true, indicating this implementation uses the Java-native engine (DelugeEngineDSL)
   * rather than an external .ck script. Used as a feature flag by the launcher.
   *
   * @return always {@code true} in this implementation.
   */
  public boolean isUseJavaEngine() {
    return true;
  }
}
