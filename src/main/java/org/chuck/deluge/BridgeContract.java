package org.chuck.deluge;

import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;

/**
 * Typed contract between the Java Swing UI and the ChucK audio engine — every global that
 * either side reads or writes is declared, created, and registered here.
 *
 * <h2>Architecture</h2>
 * This class serves as the single source of truth for all shared state. The UI writes
 * step data, track parameters, and transport controls into primitive Java arrays
 * and scalar globals; the engine's shreds (sporked by {@code DelugeEngineDSL}) read them every
 * tick via {@link ChuckArray} wrappers that point to these same primitive arrays.
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

  // ── Global Names ──
  public static final String G_CLIP_COUNT = "g_clip_count";
  public static final String G_CURRENT_CLIP = "g_current_clip";
  public static final String G_LAUNCH_QUEUE = "g_launch_queue";
  public static final String G_QUEUE_STEP = "g_queue_step";
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
  public static final String G_STEP_HPF_FREQ = "g_step_hpf_freq";
  public static final String G_STEP_HPF_RES = "g_step_hpf_res";
  public static final String G_STEP_MOD_RATE = "g_step_mod_rate";
  public static final String G_STEP_MOD_DEPTH = "g_step_mod_depth";
  public static final String G_STEP_OSC_A_VOL = "g_step_osc_a_vol";
  public static final String G_STEP_OSC_B_VOL = "g_step_osc_b_vol";
  public static final String G_STEP_NOISE_VOL = "g_step_noise_vol";
  public static final String G_STEP_PITCH = "g_step_pitch";
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
  public static final String G_AUDIO_REC = "g_audio_rec";
  public static final String G_AUDIO_PLAY = "g_audio_play";
  public static final String G_AUDIO_LOOP = "g_audio_loop";
  public static final String G_AUDIO_RATE = "g_audio_rate";
  public static final String G_AUDIO_BUS = "g_audio_bus";
  public static final String G_WVOUT_ACTIVE = "g_wvout_active";
  public static final String G_WVOUT_FILE = "g_wvout_file";
  public static final String G_MASTER_TAP = "g_master_tap";

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

  private float masterVol = 0.7f;
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

  public void setSamplePath(int track, String path) {
    samplePaths[track] = path;
    // In ChucK mode, this might trigger a reload, but for now we'll just store it
  }

  public String getSamplePath(int track) {
    return samplePaths[track];
  }

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

  public void setRecording(boolean r) { this.recording = r; }
  public boolean isRecording() { return recording; }
  public boolean isUseJavaEngine() { return true; }
}
