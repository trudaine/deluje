package org.chuck.deluge.engine;

import java.lang.reflect.InvocationTargetException;
import static org.chuck.core.ChuckDSL.*;

import org.chuck.audio.*;
import org.chuck.audio.filter.*;
import org.chuck.audio.fx.*;
import org.chuck.audio.util.*;
import org.chuck.core.*;
import org.chuck.deluge.BridgeContract;

/**
 * Native Java implementation of the Deluge audio engine, written in the ChucK-Java DSL.
 *
 * <p>This class is sporked as a single shred by {@code SequencerLauncher} and internally
 * forks 8 sub-shreds that collectively implement the full Deluge audio pipeline: kit
 * sample playback, FM/STK synthesis, audio clip recording/playback (LiSa), master export
 * (WvOut2), clock generation, FX buses, and sidechain ducking.
 *
 * <h2>Architecture</h2>
 * All state is read from shared {@link ChuckArray}s registered by {@link BridgeContract}.
 * The main transport loop is event-driven: {@link #clock_shred()} broadcasts
 * {@code tick_event} at each step boundary (swing-aware), and all other shreds {@code
 * advance()} on that event. No polling or shared locks are needed — the UI writes data
 * between ticks and the engine reads it at tick boundaries.
 *
 * <h2>Sub-shreds (sporked from {@link #transport_shred()})</h2>
 * <ol>
 *   <li><b>fx_bus_shred</b> — Creates the delay (Echo), reverb (JCRev/FreeVerb/MVerb),
 *       and chorus UGens. Reads from {@code g_delay_in} and {@code g_reverb_in} Gain
 *       buses that kit/synth voices send to. Gates the FX bus on/off with transport.</li>
 *   <li><b>master_shred</b> — Routes the synth bus and audio bus through HPF → compressor
 *       (Dyno) → limiter (Dyno) → masterTap → dac. Reads {@code G_MASTER_COMP} threshold.</li>
 *   <li><b>clock_shred</b> — Step sequencer clock. Reads {@code G_BPM} and {@code G_SWING}
 *       to compute tick duration with swing per even/odd step. Broadcasts {@code E_TICK}
 *       on every step. Supports stutter via {@code G_STUTTER_ON/DIV}.</li>
 *   <li><b>kit_shred</b> — Multi-voice sample playback. Each kit voice is
 *       SndBuf → DelugeAdsr → Pan2 → master, with delay/reverb sends. Reads per-step
 *       patterns, velocity, probability, sample start/end, pitch offset, reverse, mute
 *       groups. LFO modulation computed inline at audio rate (no poll thread).</li>
 *   <li><b>synth_shred</b> — Per-voice synthesis supporting two algorithm families:
 *       <ul>
 *         <li><b>FM</b> — Two MorphingWavetable UGens (modulator → carrier) with
 *             configurable ratio, amount, and wave index.</li>
 *         <li><b>STK</b> — Physical models (Mandolin, Rhodey, ModalBar, Moog) triggered
 *             via reflection. Algorithm codes: 0=FM, 10=Mandolin, 11=Rhodey, 12=ModalBar, 13=Moog.</li>
 *       </ul>
 *       Voices route through SVFilter → DelugeAdsr → Pan2 → synthBus. Supports
 *       arpeggiator, per-step LFO at 6 targets (filter, res, pan, pitch, vol, FM).</li>
 *   <li><b>sidechain_shred</b> — On {@code E_SIDECHAIN} (broadcast when kit row 0 fires),
 *       ducks synth bus gain to 15% for 60ms then ramps back over 120ms.</li>
 *   <li><b>audio_shred</b> — Per-track LiSa-based audio clip recording and playback.
 *       Creates LiSa UGens lazily when audio tracks appear. Adc → LiSa for recording;
 *       LiSa → Pan2 → audioBus for playback. Reads {@code G_AUDIO_REC/PLAY/LOOP/RATE}.</li>
 *   <li><b>export_shred</b> — Offline WAV export via WvOut2. Listens on
 *       {@code G_WVOUT_ACTIVE}. When triggered, splices WvOut2 between masterTap and dac,
 *       records to the path in {@code G_WVOUT_FILE}, then restores the original chain.</li>
 * </ol>
 *
 * <h2>Audio Graph</h2>
 * <pre>
 *   ┌─ KIT ───────────────────────┐
 *   │ SndBuf→Adsr→Pan2→master     │
 *   │            ├→delay_in→Echo   │  ┌─ FX ──────┐
 *   │            └→reverb_in→JCRev │  │ fxIn→gate→dac│
 *   └──────────────────────────────┘  └────────────┘
 *   ┌─ SYNTH ──────────────────────┐
 *   │ FM|STK→SVF→Adsr→Pan2→synthBus│
 *   │            ├→delay_in        │
 *   │            └→reverb_in       │
 *   └──────────────────────────────┘
 *            synthBus→HPF→comp→limit→masterTap→dac
 *   ┌─ AUDIO ──────────────────────┐  ↑ WvOut2 (export only)
 *   │ LiSa→Pan2→audioBus→──────────┘
 *   └──────────────────────────────┘
 * </pre>
 *
 * <h2>LFO Implementation</h2>
 * Replaced the original polling thread (which slept 5ms and wrote to shared arrays) with
 * a per-sample phase accumulator computed inline in the kit and synth shreds. Each LFO
 * tracks its phase in {@code lfoPhase[]} / {@code lfoPhaseKit[]}, advancing by
 * {@code rate / sampleRate} per computation. This eliminates timing jitter and the
 * dedicated LFO poll thread entirely.
 *
 * <h2>Wave Tables</h2>
 * Built once in {@link #buildWaveTables()} — 4 tables of 256 samples each:
 * Sine, Saw, Square, Triangle. Used by MorphingWavetable in FM synthesis.
 *
 * <h2>Configuration</h2>
 * Reverb type is read from {@code PreferencesManager.get("reverb.model", "JCRev")}
 * at startup. Engine dimensions (TRACKS, STEPS) come from {@link BridgeContract}.
 *
 * @see BridgeContract
 * @see org.chuck.deluge.ui.SwingDelugeApp
 */
public class DelugeEngineDSL implements Shred, Runnable {

  private ChuckVM vm;
  private volatile boolean running = true;

  // LFO phase accumulators (per-sample, replacing the poll-loop in lfo_shred)
  private final double[] lfoPhase = new double[BridgeContract.LFO_COUNT];
  private final double[] lfoPhaseKit = new double[BridgeContract.LFO_COUNT];

  /** Returns true while the engine should keep running. Also checks shred-level abort flag. */
  private boolean isRunning() {
    if (!running) return false;
    if (ChuckShred.CURRENT_SHRED.isBound() && ChuckShred.CURRENT_SHRED.get().isDone()) {
      running = false;
      return false;
    }
    return true;
  }

  /**
   * No-arg constructor for frameworks that instantiate via reflection. The VM must be set
   * externally (via the single-arg constructor or direct field access) before
   * {@link #shred()} is called.
   */
  public DelugeEngineDSL() {}

  /**
   * Creates a Deluge engine instance bound to the given ChucK VM.
   *
   * @param vm the ChucK VM instance (must have a BridgeContract registered via
   *           {@link BridgeContract#register(ChuckVM)} before {@link #shred()} is called).
   */
  public DelugeEngineDSL(ChuckVM vm) {
    this.vm = vm;
  }

  private static final float[][] WAVE_TABLES = buildWaveTables();

  private static float[][] buildWaveTables() {
    float[][] t = new float[4][256];
    for (int i = 0; i < 256; i++) {
      t[0][i] = (float) Math.sin(2.0 * Math.PI * i / 256.0);
      t[1][i] = (float) (2.0 * (i / 256.0) - 1.0);
      t[2][i] = (i < 128) ? 1.0f : -1.0f;
      t[3][i] = (i < 128) ? (i / 64.0f - 1.0f) : (3.0f - i / 64.0f);
    }
    return t;
  }

  /**
   * Runnable entry point, delegates to {@link #shred()}. Used when the engine is submitted to
   * a thread pool or started via {@code new Thread(engine)}.
   */
  @Override
  public void run() {
    shred();
  }

  /**
   * Shred entry point called by the ChucK VM when sporked. Sets {@code this.vm} from
   * the current thread's VM context ({@link ChuckVM#CURRENT_VM}), then calls
   * {@link #transport_shred()} which sporks the 8 sub-shreds and blocks until stopped.
   *
   * <p>Sub-shreds forked from transport_shred:
   * <ol>
   *   <li>{@link #fx_bus_shred()} — delay, reverb, chorus
   *   <li>{@link #master_shred()} — synth/audio bus processing
   *   <li>{@link #clock_shred()} — swing-aware step clock
   *   <li>{@link #kit_shred()} — sample playback engine
   *   <li>{@link #synth_shred()} — FM/STK synthesis engine
   *   <li>{@link #sidechain_shred()} — kick-triggered ducking
   *   <li>{@link #audio_shred()} — LiSa recording/playback
   *   <li>{@link #export_shred()} — WvOut2 offline export
   * </ol>
   */
  @Override
  public void shred() {
    this.vm = ChuckVM.CURRENT_VM.get();
    transport_shred();
  }

  private ChuckDuration stepDuration(int step) {
    double bpm = vm.getGlobalFloat(BridgeContract.G_BPM);
    if (bpm < 1.0) bpm = 120.0;
    double swing = Math.max(0.0, Math.min(1.0, vm.getGlobalFloat(BridgeContract.G_SWING)));
    double baseSec = 60.0 / bpm / 4.0;
    if (step % 2 == 0) return second(baseSec * (1.0 + (swing - 0.5) * 0.4));
    return second(baseSec * (1.0 - (swing - 0.5) * 0.4));
  }

  private double mtof(double m) {
    return 440.0 * Math.pow(2.0, (m - 69.0) / 12.0);
  }

  // ── KIT SHRED ────────────────────────────────────────────────────────────

  private void kit_shred() {
    float sr = (float) sampleRate();
    Gain master = new Gain();
    HPF hpf = new HPF(sr);
    Dyno limit = new Dyno(sr);
    Gain masterTap = (Gain) vm.getGlobalObject(BridgeContract.G_MASTER_TAP);
    master.chuck(hpf).chuck(limit).chuck(masterTap).chuck(dac());
    hpf.freq(20);
    limit.limiter();

    // Wait for the first song load before building voice graph
    ChuckEvent loadEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.G_LOAD_TRIGGER);
    advance(loadEvent);

    // Block until the first load has at least one kit track.
    // If all track types are -1 (uninitialized empty project), wait for a real song load.
    while (isRunning()) {
      ChuckArray trackTypeInit = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_TYPE);
      boolean hasKit = false;
      for (int i = 0; i < BridgeContract.TRACKS && !hasKit; i++) {
        if (trackTypeInit != null && trackTypeInit.getInt(i) == 0) hasKit = true;
      }
      if (hasKit) break;
      advance(ms(100)); // Prevent hang if loadEvent was already broadcast
    }

    // Count actual kit voices from G_TRACK_TYPE (type 0 = kit)
    ChuckArray trackTypeInit = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_TYPE);
    int voiceCount = 0;
    for (int i = 0; i < BridgeContract.TRACKS; i++) {
      if (trackTypeInit == null || trackTypeInit.getInt(i) == 0) {
        // Count only rows that have a sample path set
        Object p = vm.getGlobalObject("g_sample_" + i);
        if (p instanceof String s && !s.isEmpty()) voiceCount = i + 1;
      }
    }
    if (voiceCount < 1) voiceCount = 1;

    SndBuf[] kit = new SndBuf[voiceCount];
    Pan2[] pan = new Pan2[voiceCount];
    Gain[] dSend = new Gain[voiceCount];
    Gain[] rSend = new Gain[voiceCount];
    DelugeAdsr[] kitEnv = new DelugeAdsr[voiceCount];

    for (int i = 0; i < voiceCount; i++) {
      kit[i] = new SndBuf();
      kit[i].rate(0);
      pan[i] = new Pan2();
      dSend[i] = new Gain();
      rSend[i] = new Gain();
      kitEnv[i] = new DelugeAdsr();
      kit[i].chuck(kitEnv[i]).chuck(pan[i]).chuck(master);
      pan[i].chuck(dSend[i]).chuck((ChuckUGen) vm.getGlobalObject(BridgeContract.G_DELAY_IN));
      pan[i].chuck(rSend[i]).chuck((ChuckUGen) vm.getGlobalObject(BridgeContract.G_REVERB_IN));
      kitEnv[i].forceMute();
      kitEnv[i].set(0.001, 0, 1, 0.05);
      dSend[i].gain(0.0f);
      rSend[i].gain(0.15f);
    }

    loadKitSamples(kit);

    SndBuf[] kitRef = kit;
    DelugeAdsr[] kitEnvRef = kitEnv;
    vm.spork(() -> kit_preview_shred(kitRef, kitEnvRef));
    vm.spork(() -> kit_reload_shred(kitRef));

    ChuckEvent tickEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.E_TICK);
    ChuckEvent sidechainEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.E_SIDECHAIN);
    long lastStep = -1;

    org.chuck.core.ChuckShred current = org.chuck.core.ChuckShred.CURRENT_SHRED.get();
    while (current != null && !current.isDone()) {
      advance(tickEvent);
      if (vm.getGlobalInt(BridgeContract.G_PLAY) == 0) {
        lastStep = -1;
        for (int r = 0; r < kit.length; r++) {
          kitEnv[r].keyOff();
          kit[r].rate(0);
        }
        continue;
      }

      long currentStep = vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
      if (currentStep == lastStep) continue;
      lastStep = currentStep;

      ChuckArray pat = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PATTERN);
      ChuckArray vel = (ChuckArray) vm.getGlobalObject(BridgeContract.G_VELOCITY);
      ChuckArray mute = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MUTE);
      ChuckArray trkLvl = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_LEVEL);
      ChuckArray sStart = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_START);
      ChuckArray sEnd = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_END);
      ChuckArray prob = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PROBABILITY);
      ChuckArray dlySnd = (ChuckArray) vm.getGlobalObject(BridgeContract.G_DELAY_SEND);
      ChuckArray revSnd = (ChuckArray) vm.getGlobalObject(BridgeContract.G_REVERB_SEND);
      ChuckArray kitPitch = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_PITCH);
      ChuckArray kitRev = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_REVERSE);
      ChuckArray trackType = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_TYPE);

      ChuckArray kitAtk = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_ATTACK);
      ChuckArray kitDec = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_DECAY);
      ChuckArray kitSus = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_SUSTAIN);
      ChuckArray kitRel = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_RELEASE);
      ChuckArray kitMuteGrp = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_MUTE_GROUP);
      ChuckArray trkLen = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_LENGTH);
	      ChuckArray lfoRate = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_RATE);
	      ChuckArray lfoType = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_TYPE);
	      ChuckArray lfoDepth = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_DEPTH);
      ChuckArray lfoTgt = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_TARGET);
      ChuckArray lfoTrk = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_TRACK);

      master.gain((float) vm.getGlobalFloat(BridgeContract.G_MASTER_VOL));

      // Update per-track sends and ADSR for all tracks each tick
      for (int r = 0; r < kit.length; r++) {
        dSend[r].gain(dlySnd != null ? (float) dlySnd.getFloat(r) : 0.0f);
        rSend[r].gain(revSnd != null ? (float) revSnd.getFloat(r) : 0.15f);
        if (kitAtk != null) {
          double a = kitAtk.getFloat(r);
          double d = kitDec != null ? kitDec.getFloat(r) : 0;
          double s = kitSus != null ? kitSus.getFloat(r) : 1;
          double rl = kitRel != null ? kitRel.getFloat(r) : 0.05;
          kitEnv[r].set(Math.max(0.0001, a), d, s, Math.max(0.001, rl));
        }
      }

      for (int r = 0; r < kit.length; r++) {
        // Only process Kit tracks (Type 0)
        if (trackType != null && trackType.getInt(r) != 0) continue;
        if (mute != null && mute.getInt(r) != 0) continue;

        // Per-track LFO contributions computed inline (audio-rate, no poll thread)
        // Compute LFO values for this tick
        double[] lfoVals = new double[BridgeContract.LFO_COUNT];
        for (int l = 0; l < BridgeContract.LFO_COUNT; l++) {
          double rate = lfoRate != null ? lfoRate.getFloat(l) : 1.0;
          double depth = lfoDepth != null ? lfoDepth.getFloat(l) : 0.0;
          int type = lfoType != null ? (int) lfoType.getInt(l) : 0;
          if (depth == 0.0) { lfoVals[l] = 0.0; continue; }
          lfoPhaseKit[l] = (lfoPhaseKit[l] + rate / sr) % 1.0;
          double raw = switch (type) {
            case 1 -> 2.0 * lfoPhaseKit[l] - 1.0;
            case 2 -> lfoPhaseKit[l] < 0.5 ? 1.0 : -1.0;
            case 3 -> lfoPhaseKit[l] < 0.5 ? (4.0 * lfoPhaseKit[l] - 1.0) : (3.0 - 4.0 * lfoPhaseKit[l]);
            default -> Math.sin(2.0 * Math.PI * lfoPhaseKit[l]);
          };
          lfoVals[l] = raw * depth;
        }

        double lfoPit = 0, lfoV = 0;
        for (int l = 0; l < BridgeContract.LFO_COUNT; l++) {
          long lfoTrackTarget = lfoTrk != null ? lfoTrk.getInt(l) : -1L;
          if (lfoTrackTarget != -1L && lfoTrackTarget != r) continue;
          double lv = lfoVals[l];
          int tgt = lfoTgt != null ? (int) lfoTgt.getInt(l) : -1;
          if (tgt == 3) lfoPit += lv;
          else if (tgt == 4) lfoV += lv;
        }

        // Per-track polyrhythm step
        int len = trkLen != null ? (int) Math.max(1, trkLen.getInt(r)) : BridgeContract.STEPS;
        int step = (int) (currentStep % len);
        int idx = r * BridgeContract.STEPS + step;

        if (pat == null || pat.getInt(idx) == 0) continue;
        if (prob != null && Math.random() > prob.getFloat(idx)) continue;

        // Mute group choke
        if (kitMuteGrp != null) {
          long grp = kitMuteGrp.getInt(r);
          if (grp > 0) {
            for (int o = 0; o < kit.length; o++) {
              if (o != r && kitMuteGrp.getInt(o) == grp) {
                kit[o].rate(0);
                kitEnv[o].keyOff();
              }
            }
          }
        }

        if (r == 0) sidechainEvent.broadcast();

        double pitchSemi = (kitPitch != null) ? kitPitch.getFloat(r) : 0.0;
        double rate = Math.pow(2.0, (pitchSemi + lfoPit * 12.0) / 12.0);
        boolean reverse = (kitRev != null) && kitRev.getInt(r) != 0;
        long samples = Math.max(1, kit[r].samples());

        float startAt = sStart != null ? (float) sStart.getFloat(idx) : 0.0f;
        float endAt = sEnd != null ? (float) sEnd.getFloat(idx) : 1.0f;

        long startPos = (long) (startAt * samples);
        long endPos = (long) (endAt * samples);

        if (reverse) {
          kit[r].rate((float) -rate);
          kit[r].pos(endPos);
        } else {
          kit[r].rate((float) rate);
          kit[r].pos(startPos);
        }

        float gain =
            (float)
                (vel.getFloat(idx) * trkLvl.getFloat(r) * 0.8 * Math.max(0.0, 1.0 + lfoV * 0.5));
        kit[r].gain(gain);
        kitEnv[r].keyOn();

        // Schedule keyOff based on playback duration — no polling thread needed
        long playLen = Math.abs(endPos - startPos);
        double durSec = playLen / (sampleRate() * Math.abs(rate));
        int trackIdx = r;
        vm.spork(
            () -> {
              advance(second(durSec));
              kitEnv[trackIdx].keyOff();
              kit[trackIdx].rate(0);
            });
      }
    }
  }

  private void loadKitSamples(SndBuf[] kit) {
    java.io.File scratchDir =
        new java.io.File(System.getProperty("java.io.tmpdir"), "deluge-scratch");
    scratchDir.mkdirs();

    for (int i = 0; i < kit.length; i++) {
      Object pathObj = vm.getGlobalObject("g_sample_" + i);
      if (!(pathObj instanceof String path) || path.isEmpty()) continue;

      // If it's already an absolute filesystem path that exists, use it directly
      java.io.File f = new java.io.File(path);
      if (f.isAbsolute() && f.exists()) {
        loadSndBuf(kit[i], path);
        continue;
      }

      // Treat as classpath resource — ensure leading slash
      String resPath = path.startsWith("/") ? path : "/" + path;
      String resolved = resolveResource(resPath, scratchDir);
      if (resolved != null) {
        loadSndBuf(kit[i], resolved);
      }
    }
  }

  private String resolveResource(String resPath, java.io.File scratchDir) {
    // Check local Maven target directory first to avoid scratch file locking issues
    java.io.File localTarget = new java.io.File("target/classes" + (resPath.startsWith("/") ? resPath : "/" + resPath));
    if (localTarget.exists()) {
      return localTarget.getAbsolutePath();
    }
    
    // Try exact path, then .WAV / .wav case variants
    String[] candidates = {
      resPath, resPath.replace(".wav", ".WAV"), resPath.replace(".WAV", ".wav"),
    };
    for (String candidate : candidates) {
      try (java.io.InputStream is = getClass().getResourceAsStream(candidate)) {
        if (is != null) {
          String uniqueName = java.util.UUID.randomUUID().toString() + "_" + new java.io.File(candidate).getName();
          java.io.File tmp = new java.io.File(scratchDir, uniqueName);
          java.nio.file.Files.copy(
              is, tmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
          return tmp.getAbsolutePath();
        }
      } catch (Exception ignored) {
      }
    }
    return null;
  }

  private void loadSndBuf(SndBuf buf, String path) {
    try {
      buf.rate(0);
      buf.read(path);
      buf.rate(0);
      if (buf.samples() > 0) buf.pos(buf.samples());
    } catch (Exception ignored) {
    }
  }

  private void kit_reload_shred(SndBuf[] kit) {
    ChuckEvent loadEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.G_LOAD_TRIGGER);
    while (isRunning()) {
      advance(loadEvent);
      loadKitSamples(kit);
    }
  }

  private void kit_preview_shred(SndBuf[] kit, DelugeAdsr[] kitEnv) {
    ChuckEvent previewEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.E_PREVIEW);
    while (isRunning()) {
      advance(previewEvent);
      int r = (int) vm.getGlobalInt(BridgeContract.G_PREVIEW_TRACK);
      if (r >= 0 && r < kit.length && kit[r].samples() > 0) {
        kit[r].rate(1);
        kit[r].pos(0);
        kit[r].gain(0.8f);
        kitEnv[r].keyOn();
        // Brief spork to keyOff after the audition
        int trackIdx = r;
        vm.spork(
            () -> {
              advance(ms(200));
              kitEnv[trackIdx].keyOff();
            });
      }
    }
  }

  // ── SYNTH SHRED ──────────────────────────────────────────────────────────

  private void synth_shred() {
    float sr = (float) sampleRate();
    Gain synthBus = (Gain) vm.getGlobalObject(BridgeContract.G_SYNTH_BUS);

    // Wait for the first song load before building voice graph
    // Block until at least one synth track (type 1) is present, so arrays aren't sized 1
    while (isRunning()) {
      ChuckArray trackTypeInit = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_TYPE);
      boolean hasSynth = false;
      for (int i = 0; i < BridgeContract.TRACKS && !hasSynth; i++) {
        if (trackTypeInit != null && trackTypeInit.getInt(i) == 1) hasSynth = true;
      }
      if (hasSynth) break;
      advance(ms(100)); // Prevent hang if loadEvent was already broadcast
    }

    // Find synth track range in bridge: first and last contiguous type-1 index
    ChuckArray trackTypeInit = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_TYPE);
    int synthBase = -1;
    int maxSynthBridgeRow = -1;
    for (int i = 0; i < BridgeContract.TRACKS; i++) {
      if (trackTypeInit != null && trackTypeInit.getInt(i) == 1) {
        if (synthBase < 0) synthBase = i;
        maxSynthBridgeRow = i;
      }
    }
    if (synthBase < 0) synthBase = 0;
    if (maxSynthBridgeRow < synthBase) maxSynthBridgeRow = synthBase;
    int totalSynthSlots = maxSynthBridgeRow - synthBase + 1;

    // Read synth algorithm array to know which voices are FM vs STK
    ChuckArray algoArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_SYNTH_ALGO);

    // Allocate one UGen per synth bridge row (each row owns its own voice).
    // This matches real Deluge behavior: each note voice gets dedicated UGens.
    ChuckUGen[] src = new ChuckUGen[totalSynthSlots];
    MorphingWavetable[] car = new MorphingWavetable[totalSynthSlots];
    MorphingWavetable[] mod = new MorphingWavetable[totalSynthSlots];
    SVFilter[] fil = new SVFilter[totalSynthSlots];
    HPF[] hpf = new HPF[totalSynthSlots];
    DelugeAdsr[] env = new DelugeAdsr[totalSynthSlots];
    Pan2[] pan = new Pan2[totalSynthSlots];
    Gain[] sDsend = new Gain[totalSynthSlots];
    Gain[] sRsend = new Gain[totalSynthSlots];
    // (Carrier feedback disabled in graph — see block-rate modulation below)

    for (int i = 0; i < totalSynthSlots; i++) {
      int algo = algoArr != null ? (int) algoArr.getInt(i) : 0;
      fil[i] = new SVFilter();
      hpf[i] = new HPF(sr);
      env[i] = new DelugeAdsr();
      pan[i] = new Pan2();
      sDsend[i] = new Gain();
      sRsend[i] = new Gain();

      if (algo >= 10) {
        // STK physical model
        src[i] = createStkUGen(algo, sr);
        src[i].chuck(fil[i]).chuck(hpf[i]).chuck(env[i]).chuck(pan[i]).chuck(synthBus);
      } else {
        // FM synthesis (original behavior)
        car[i] = new MorphingWavetable(sr);
        car[i].setTables(WAVE_TABLES);
        mod[i] = new MorphingWavetable(sr);
        mod[i].setTables(WAVE_TABLES);
        mod[i].chuck(car[i]);
        car[i].chuck(fil[i]).chuck(hpf[i]).chuck(env[i]).chuck(pan[i]).chuck(synthBus);
        src[i] = car[i];
      }

      pan[i].chuck(sDsend[i]).chuck((ChuckUGen) vm.getGlobalObject(BridgeContract.G_DELAY_IN));
      pan[i].chuck(sRsend[i]).chuck((ChuckUGen) vm.getGlobalObject(BridgeContract.G_REVERB_IN));
      fil[i].reset();
      fil[i].freq(5000);
      hpf[i].freq(20.0f);
      env[i].set(0.05, 0.2, 0.5, 0.3);
      env[i].forceMute();
      sDsend[i].gain(0.0f);
      sRsend[i].gain(0.15f);
    }

    // Spork preview shred for synth audition
    MorphingWavetable[] carRef = car;
    MorphingWavetable[] modRef = mod;
    DelugeAdsr[] envRef = env;
    vm.spork(() -> synth_preview_shred(carRef, modRef, envRef));

    ChuckEvent tickEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.E_TICK);
    long lastStep = -1;

    while (isRunning()) {
      advance(tickEvent);
      if (vm.getGlobalInt(BridgeContract.G_PLAY) == 0) {
        lastStep = -1;
          for (DelugeAdsr env1 : env) {
              env1.keyOff();
          }
        continue;
      }

      long currentStep = vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
      boolean isNewStep = (currentStep != lastStep);
      lastStep = currentStep;

      ChuckArray pat = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PATTERN);
      ChuckArray vel = (ChuckArray) vm.getGlobalObject(BridgeContract.G_VELOCITY);
      ChuckArray mute = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MUTE);
      ChuckArray trkLvl = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_LEVEL);
      ChuckArray gFil = (ChuckArray) vm.getGlobalObject(BridgeContract.G_FILTER);
      ChuckArray sFil = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_FILTER);
      ChuckArray sRes = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_RES);
      ChuckArray arpOn = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ARP_ON);
      ChuckArray fmRatio = (ChuckArray) vm.getGlobalObject(BridgeContract.G_FM_RATIO);
      ChuckArray fmAmt = (ChuckArray) vm.getGlobalObject(BridgeContract.G_FM_AMOUNT);
      ChuckArray prob = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PROBABILITY);
      ChuckArray oscType = (ChuckArray) vm.getGlobalObject(BridgeContract.G_OSC_TYPE);
      ChuckArray gateArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_GATE);
      ChuckArray sPan = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_PAN);
      ChuckArray dlySnd = (ChuckArray) vm.getGlobalObject(BridgeContract.G_DELAY_SEND);
      ChuckArray revSnd = (ChuckArray) vm.getGlobalObject(BridgeContract.G_REVERB_SEND);
      ChuckArray trackType = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_TYPE);
      ChuckArray filterModeArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_FILTER_MODE);
      ChuckArray filterMorphArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_FILTER_MORPH);

      double masterPan = vm.getGlobalFloat(BridgeContract.G_MASTER_PAN);
      ChuckArray trkLen = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_LENGTH);
      ChuckArray lfoRate = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_RATE);
      ChuckArray lfoType = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_TYPE);
      ChuckArray lfoDepth = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_DEPTH);
      ChuckArray lfoTgt = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_TARGET);
      ChuckArray lfoTrk = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_TRACK);
      ChuckArray envArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ENV);

      // Re-read algo array each tick (user may change algorithm)
      ChuckArray algoArrLive = (ChuckArray) vm.getGlobalObject(BridgeContract.G_SYNTH_ALGO);
      ChuckArray synthModeArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_SYNTH_MODE);
      ChuckArray hpfFreqArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_HPF_FREQ);
      ChuckArray hpfResArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_HPF_RES);
      ChuckArray polyphonyArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_POLYPHONY);
      ChuckArray car1FbArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_CARRIER1_FB);

      // Iterate all bridge rows for this synth track; each row has its own dedicated UGen
      for (int r = synthBase; r <= maxSynthBridgeRow; r++) {
        int u = r - synthBase;
        // Only process Synth tracks (Type 1)
        if (trackType != null && trackType.getInt(r) != 1) continue;

        int algo = algoArrLive != null ? (int) algoArrLive.getInt(r) : 0;

        // Compute LFO values inline at audio rate (no poll thread)
        double[] lfoVals = new double[BridgeContract.LFO_COUNT];
        for (int l = 0; l < BridgeContract.LFO_COUNT; l++) {
          double rate = lfoRate != null ? lfoRate.getFloat(l) : 1.0;
          double depth = lfoDepth != null ? lfoDepth.getFloat(l) : 0.0;
          int type = lfoType != null ? (int) lfoType.getInt(l) : 0;
          if (depth == 0.0) { lfoVals[l] = 0.0; continue; }
          lfoPhase[l] = (lfoPhase[l] + rate / sr) % 1.0;
          double raw = switch (type) {
            case 1 -> 2.0 * lfoPhase[l] - 1.0;
            case 2 -> lfoPhase[l] < 0.5 ? 1.0 : -1.0;
            case 3 -> lfoPhase[l] < 0.5 ? (4.0 * lfoPhase[l] - 1.0) : (3.0 - 4.0 * lfoPhase[l]);
            default -> Math.sin(2.0 * Math.PI * lfoPhase[l]);
          };
          lfoVals[l] = raw * depth;
        }

        // Per-track LFO contributions
        double lfoF = 0, lfoQ = 0, lfoP = 0, lfoPit = 0, lfoV = 0, lfoFm = 0;
        for (int l = 0; l < BridgeContract.LFO_COUNT; l++) {
          long lfoTrackTarget = lfoTrk != null ? lfoTrk.getInt(l) : -1L;
          if (lfoTrackTarget != -1L && lfoTrackTarget != r) continue;
          double lv = lfoVals[l];
          int tgt = lfoTgt != null ? (int) lfoTgt.getInt(l) : -1;
          switch (tgt) {
            case 0 -> lfoF += lv;
            case 1 -> lfoQ += lv;
            case 2 -> lfoP += lv;
            case 3 -> lfoPit += lv;
            case 4 -> lfoV += lv;
            case 5 -> lfoFm += lv;
          }
        }

        // Per-track polyrhythm step
        int len = trkLen != null ? (int) Math.max(1, trkLen.getInt(r)) : BridgeContract.STEPS;
        int step = (int) (currentStep % len);
        int idx = r * BridgeContract.STEPS + step;

        if (algo < 10 && oscType != null && car[u] != null) car[u].index((int) oscType.getInt(r));

        // Check step data
        if (pat.getInt(idx) == 0) {
          // Each row has its own UGen; keyOff is always safe
          env[u].keyOff();
          continue;
        }
        // Mute check
        if (mute.getInt(r) != 0) {
          env[u].keyOff();
          continue;
        }

        // Update filter, pan, sends for this row (each row has its own dedicated UGen).
        sDsend[u].gain(dlySnd != null ? (float) dlySnd.getFloat(r) : 0.0f);
        sRsend[u].gain(revSnd != null ? (float) revSnd.getFloat(r) : 0.15f);

        double tf = (gFil.getFloat(r * 2) + sFil.getFloat(idx)) * 10000.0 + 100.0 + lfoF * 5000.0;
        double tq = (gFil.getFloat(r * 2 + 1) + sRes.getFloat(idx)) * 4.0 + 1.0 + lfoQ * 3.0;
        double tp = masterPan + (sPan != null ? sPan.getFloat(idx) : 0.0) + lfoP;
        fil[u].freq((float) Math.max(20.0, Math.min(20000.0, tf)));
        fil[u].Q((float) Math.max(1.0, Math.min(10.0, tq)));
        // Per-voice HPF: read from bridge, 20Hz = off/bypass
        float hf = hpfFreqArr != null ? (float) hpfFreqArr.getFloat(r) : 20.0f;
        float hr = hpfResArr != null ? (float) hpfResArr.getFloat(r) : 0.0f;
        hpf[u].freq(Math.max(20.0f, hf));
        hpf[u].Q(1.0f + hr * 9.0f);
        // Apply filter mode + morph to SVFilter morph parameter.
        // LADDER_12 (0) and LADDER_24 (1) default to LP (morph=0).
        // SVF (2) maps morph to SVFilter's LP→BP→HP continuum.
        int fm = filterModeArr != null ? (int) filterModeArr.getInt(r) : 2;
        double fmorph = filterMorphArr != null ? filterMorphArr.getFloat(r) : 0.0;
        double svfMorph = (fm == 2) ? fmorph : 0.0;
        fil[u].morph(svfMorph);
        pan[u].pan((float) Math.max(-1.0, Math.min(1.0, tp)));


        if (isNewStep) {
          if (prob != null && Math.random() > prob.getFloat(idx)) continue;

          // Apply envelope shape from bridge (if available)
          if (envArr != null) {
            int eb = (r * BridgeContract.ENV_COUNT + 0) * BridgeContract.ENV_PARAMS;
            double a = envArr.getFloat(eb + 0);
            double d = envArr.getFloat(eb + 1);
            double s = envArr.getFloat(eb + 2);
            double rel = envArr.getFloat(eb + 3);
            env[u].set(a, d, s, rel);
          }

          double gainVal =
              vel.getFloat(idx) * trkLvl.getFloat(r) * 0.8 * Math.max(0.0, 1.0 + lfoV * 0.5);
          double gateSec =
              (gateArr != null ? gateArr.getFloat(idx) : 0.9)
                  * stepDuration(step % 2).samples()
                  / sampleRate();

          if (algo >= 10) {
            // STK physical model trigger
            double f = mtof(((24 - 1) - (r - synthBase)) + 60) * Math.pow(2.0, lfoPit);
            triggerStkNote(src[u], (float) f, (float) gainVal);
            env[u].gain(0.0f); // STK has its own internal envelope; pass level via gain
            env[u].keyOn();    // Keep the gate UGen open
            double noteSec = gateSec;
            int rv = u;
            ChuckUGen srcRef = src[u];
            vm.spork(
                () -> {
                  advance(second(noteSec));
                  releaseStkNote(srcRef);
                  env[rv].keyOff();
                });
          } else if (arpOn != null && arpOn.getInt(r) == 1) {
            int baseMidi = (int) ((24 - 1) - (r - synthBase)) + 60;
            int v = u;
            vm.spork(() -> run_arp(v, baseMidi, (float) gainVal, car[v], mod[v], env[v]));
          } else {
            double f = mtof(((24 - 1) - (r - synthBase)) + 60) * Math.pow(2.0, lfoPit);
            int synthMode = synthModeArr != null ? (int) synthModeArr.getInt(r) : 0;
            if (synthMode == 1) {
              // FM: mod→car with FM ratio + amount
              if (car[u] != null) car[u].freq((float) f);
              if (mod[u] != null) {
                double fmR = fmRatio != null ? fmRatio.getFloat(r) : 1.0;
                double fmA =
                    (fmAmt != null ? fmAmt.getFloat(r) : 0.0) * Math.max(0.0, 1.0 + lfoFm * 0.5);
                mod[u].freq((float) (f * fmR));
                mod[u].gain((float) (fmA * 1000.0));
              }
            } else if (synthMode >= 2) {
              // RINGMOD (2): both oscillators active, mixed via mod→car FM at audio rate.
              // True sample-by-sample multiplication isn't possible in ChucK's block-advance model,
              // so we drive both oscillators at the same frequency at unity gain, letting them
              // interact acoustically through the filter.
              if (car[u] != null) {
                car[u].freq((float) f);
                car[u].gain(1.0f);
              }
              if (mod[u] != null) {
                mod[u].freq((float) f);
                mod[u].gain(1.0f);
              }
            } else {
              // SUBTRACTIVE (0): single oscillator through filter
              if (car[u] != null) {
                car[u].freq((float) f);
                car[u].gain(1.0f);
              }
              // Mute the modulator to prevent FM
              if (mod[u] != null) mod[u].gain(0.0f);
            }
            env[u].gain((float) gainVal);
            env[u].keyOn();
            double noteSec = gateSec;
            if (vm.getLogLevel() >= 2) vm.print("SYNTH trigger track: " + r + " step: " + (idx % BridgeContract.STEPS) + "\n");
            int[] capturedR = new int[]{r};
            int rv = u;
            vm.spork(
                () -> {
                  advance(second(noteSec));
                  env[rv].keyOff();
                  if (vm.getLogLevel() >= 2) vm.print("SYNTH note end track: " + capturedR[0] + "\n");
                });
          }
        }
      }
    }
  }

  /** Create an STK physical model UGen based on algorithm code. */
  private ChuckUGen createStkUGen(int algo, float sr) {
    return switch (algo) {
      case 10 -> new org.chuck.audio.stk.Mandolin(20.0f, sr);
      case 11 -> new org.chuck.audio.stk.Rhodey(sr);
      case 12 -> new org.chuck.audio.stk.ModalBar(sr);
      case 13 -> new org.chuck.audio.stk.Moog(sr);
      default -> new org.chuck.audio.stk.Mandolin(20.0f, sr);
    };
  }

  /** Trigger a note on an STK physical model UGen via reflection. */
  private void triggerStkNote(ChuckUGen ugen, float freq, float velocity) {
    try {
      ugen.getClass().getMethod("setFreq", double.class).invoke(ugen, (double) freq);
      ugen.getClass().getMethod("noteOn", float.class).invoke(ugen, velocity);
    } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
      if (vm.getLogLevel() >= 1) vm.print("STK noteOn failed: " + e.getMessage() + "\n");
    }
  }

  /** Release a note on an STK physical model via reflection. */
  private void releaseStkNote(ChuckUGen ugen) {
    try {
      ugen.getClass().getMethod("noteOff", float.class).invoke(ugen, 0.0f);
    } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
      if (vm.getLogLevel() >= 1) vm.print("STK noteOff failed: " + e.getMessage() + "\n");
    }
  }

  /** Preview/audition a synth track on E_PREVIEW. Fires a brief gate on the specified track. */
  private void synth_preview_shred(
      MorphingWavetable[] car, MorphingWavetable[] mod, DelugeAdsr[] env) {
    ChuckEvent previewEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.E_PREVIEW);
    while (isRunning()) {
      advance(previewEvent);
      int r = (int) vm.getGlobalInt(BridgeContract.G_PREVIEW_TRACK);
      if (r >= 0 && r < car.length) {
        // Verify this is a synth track by checking G_TRACK_TYPE array
        ChuckArray trackTypeArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_TYPE);
        if (trackTypeArr == null || trackTypeArr.getInt(r) != 1) continue;
        // Read the preview pitch set by the UI on click
        double previewPitch = vm.getGlobalFloat("g_preview_pitch");
        double f = mtof(previewPitch + 60);
        car[r].freq((float) f);
        mod[r].freq((float) f);
        env[r].gain(0.8f);
        env[r].keyOn();
        int trackIdx = r;
        vm.spork(
            () -> {
              advance(ms(200));
              env[trackIdx].keyOff();
            });
      }
    }
  }

  private void run_arp(
      int v,
      int baseMidi,
      float gain,
      MorphingWavetable car,
      MorphingWavetable mod,
      DelugeAdsr env) {
    ChuckArray octArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ARP_OCTAVE);
    ChuckArray rateArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ARP_RATE);
    ChuckArray arpOn = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ARP_ON);
    ChuckArray fmRatio = (ChuckArray) vm.getGlobalObject(BridgeContract.G_FM_RATIO);
    ChuckArray synthModeArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_SYNTH_MODE);
    ChuckArray arpModeArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ARP_MODE);
    int octaves = (octArr != null) ? (int) octArr.getInt(v) : 1;
    if (octaves < 1) octaves = 1;
    int mode = (arpModeArr != null) ? (int) arpModeArr.getInt(v) : 0;
    int synthMode = (synthModeArr != null) ? (int) synthModeArr.getInt(v) : 0;
    int totalNotes = octaves;
    // UP_DOWN mode: first go up, then back down (excluding repeated top/bottom root)
    if (mode == 2) totalNotes = octaves * 2 - 1;
    for (int n = 0; n < totalNotes; n++) {
      int midiNote;
      switch (mode) {
        case 1 -> // DOWN
          midiNote = baseMidi + (octaves - 1 - n) * 12;
        case 2 -> {
            // UP_DOWN
            if (n < octaves) {
                midiNote = baseMidi + n * 12;
            } else {
                midiNote = baseMidi + (octaves * 2 - 2 - n) * 12;
            } }
        case 3 -> // RANDOM
          // Only re-roll on first iteration; subsequent random picks are per-loop
          midiNote = baseMidi + (int) (Math.random() * octaves) * 12;
        default -> // UP
          midiNote = baseMidi + n * 12;
      }
      double f = mtof(midiNote);
      car.freq((float) f);
      if (synthMode == 1) {
        mod.freq((float) (f * (fmRatio != null ? fmRatio.getFloat(v) : 1.0)));
      } else {
        mod.gain(0.0f);
      }
      env.gain((float) (gain * 0.8));
      env.keyOn();
      double rate = (rateArr != null) ? rateArr.getFloat(v) : 1.0;
      double bpm = vm.getGlobalFloat(BridgeContract.G_BPM);
      ChuckDuration d = second(60.0 / bpm / 4.0 / rate);
      advance(samp(d.samples() * 0.8));
      env.keyOff();
      advance(samp(d.samples() * 0.2));
      if (vm.getGlobalInt(BridgeContract.G_PLAY) == 0 || (arpOn != null && arpOn.getInt(v) == 0))
        break;
    }
  }

  // ── CLOCK SHRED ──────────────────────────────────────────────────────────

  private void clock_shred() {
    int step = 0;
    long prevPlay = -1;
    while (isRunning()) {
      long play = vm.getGlobalInt(BridgeContract.G_PLAY);
      if (play == 0) {
        step = 0;
        if (prevPlay != 0) {
          ((ChuckEvent) vm.getGlobalObject(BridgeContract.E_TICK)).broadcast();
        }
        prevPlay = 0;
        advance(ms(10));
        continue;
      }
      prevPlay = play;
      if (vm.getGlobalInt(BridgeContract.G_STUTTER_ON) == 0) {
        vm.setGlobalInt(BridgeContract.G_CURRENT_STEP, (long) step);
        ((ChuckEvent) vm.getGlobalObject(BridgeContract.E_TICK)).broadcast();
        advance(stepDuration(step % 2));
        step++;
      } else {
        ((ChuckEvent) vm.getGlobalObject(BridgeContract.E_TICK)).broadcast();
        double div = Math.max(1.0, vm.getGlobalFloat(BridgeContract.G_STUTTER_DIV));
        advance(samp(stepDuration(step % 2).samples() / div));
      }
    }
  }

  // ── FX BUS SHRED ─────────────────────────────────────────────────────────

  private void fx_bus_shred() {
    float sr = (float) sampleRate();
    Gain fxIn = new Gain();
    DelugeAdsr gate = new DelugeAdsr();
    fxIn.chuck(gate).chuck(dac());

    Echo delay = new Echo();

    String reverbModel = org.chuck.deluge.project.PreferencesManager.get("reverb.model", "JCRev");
    org.chuck.audio.util.StereoUGen rev;
    if (null == reverbModel) {
        rev = new JCRev();
    } else rev = switch (reverbModel) {
        case "FreeVerb" -> new FreeVerb();
        case "MVerb" -> new MVerb();
        case "ProceduralReverb" -> new ProceduralReverb();
        default -> new JCRev();
    };

    Chorus chorus = new Chorus(sr);
    chorus.setModDepth(0.2f);
    chorus.setModFreq(0.5f);
    Gain modIn = new Gain();
    vm.setGlobalObject("g_mod_in", modIn);
    modIn.chuck(chorus).chuck(fxIn);

    ((Gain) vm.getGlobalObject(BridgeContract.G_DELAY_IN)).chuck(delay).chuck(fxIn);
    ((Gain) vm.getGlobalObject(BridgeContract.G_REVERB_IN)).chuck(rev).chuck(fxIn);

    fxIn.gain(0.3f);
    advance(ms(100));
    gate.forceMute();
    gate.set(0.01, 0, 1, 0.01);

    ChuckEvent tickEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.E_TICK);
    while (isRunning()) {
      advance(tickEvent);
      if (vm.getGlobalInt(BridgeContract.G_PLAY) != 0) gate.keyOn();
      else gate.keyOff();
      delay.delay((float) vm.getGlobalFloat(BridgeContract.G_DELAY_TIME));
      delay.gain((float) vm.getGlobalFloat(BridgeContract.G_DELAY_FB));
      if (rev instanceof JCRev jcr)
        jcr.mix((float) vm.getGlobalFloat(BridgeContract.G_REVERB_ROOM));
      else if (rev instanceof FreeVerb fv)
        fv.roomSize((float) vm.getGlobalFloat(BridgeContract.G_REVERB_ROOM));
    }
  }

  // ── MASTER SHRED ─────────────────────────────────────────────────────────

  private void master_shred() {
    float sr = (float) sampleRate();
    HPF hpf = new HPF(sr);
    Dyno limit = new Dyno(sr);
    Dyno comp = new Dyno(sr);
    Gain masterTap = (Gain) vm.getGlobalObject(BridgeContract.G_MASTER_TAP);
    ((Gain) vm.getGlobalObject(BridgeContract.G_SYNTH_BUS))
        .chuck(hpf)
        .chuck(comp)
        .chuck(limit)
        .chuck(masterTap)
        .chuck(dac());
    // Audio bus routes through same master processing
    ((Gain) vm.getGlobalObject(BridgeContract.G_AUDIO_BUS))
        .chuck(hpf)
        .chuck(comp)
        .chuck(limit)
        .chuck(masterTap)
        .chuck(dac());
    hpf.freq(20);
    limit.limiter();
    comp.compressor();
    ChuckEvent tickEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.E_TICK);
    while (isRunning()) {
      advance(tickEvent);
      double th = 1.0 - vm.getGlobalFloat(BridgeContract.G_MASTER_COMP);
      comp.thresh((float) Math.max(0.01, Math.min(0.9, th)));
    }
  }

  // ── SIDECHAIN SHRED ──────────────────────────────────────────────────────

  private void sidechain_shred() {
    ChuckEvent sc = (ChuckEvent) vm.getGlobalObject(BridgeContract.E_SIDECHAIN);
    Gain synthBus = (Gain) vm.getGlobalObject(BridgeContract.G_SYNTH_BUS);
    float normalGain = 1.0f;
    float duckedGain = 0.15f;
    float duckMs = 60.0f;
    float releaseMs = 120.0f;
    while (isRunning()) {
      advance(sc);
      synthBus.gain(duckedGain);
      advance(ms(duckMs));
      int steps = 8;
      float stepGain = (normalGain - duckedGain) / steps;
      for (int i = 0; i < steps; i++) {
        synthBus.gain(duckedGain + stepGain * (i + 1));
        advance(ms(releaseMs / steps));
      }
      synthBus.gain(normalGain);
    }
  }

  // ── AUDIO SHRED (LiSa) ────────────────────────────────────────────────────

  private void audio_shred() {
    float sr = (float) sampleRate();
    Gain audioBus = (Gain) vm.getGlobalObject(BridgeContract.G_AUDIO_BUS);
    ChuckUGen adc = org.chuck.core.ChuckDSL.adc();

    // Max record duration: 60 seconds
    int maxRecordSamples = (int) (sr * 60.0f);

    // LiSa per audio track (indexed by engine row)
    LiSa[] lisa = new LiSa[BridgeContract.TRACKS];
    Pan2[] lisaPan = new Pan2[BridgeContract.TRACKS];
    Gain[] lisaDsend = new Gain[BridgeContract.TRACKS];
    Gain[] lisaRsend = new Gain[BridgeContract.TRACKS];

    // Wait for at least one audio track before building graph
    while (isRunning()) {
      ChuckArray trackTypeInit = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_TYPE);
      boolean hasAudio = false;
      for (int i = 0; i < BridgeContract.TRACKS && !hasAudio; i++) {
        if (trackTypeInit != null && trackTypeInit.getInt(i) == 2) hasAudio = true;
      }
      if (hasAudio) break;
      advance(ms(100));
    }

    ChuckEvent tickEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.E_TICK);

    while (isRunning()) {
      advance(tickEvent);

      ChuckArray trackType = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_TYPE);
      ChuckArray audioRec = (ChuckArray) vm.getGlobalObject(BridgeContract.G_AUDIO_REC);
      ChuckArray audioPlay = (ChuckArray) vm.getGlobalObject(BridgeContract.G_AUDIO_PLAY);
      ChuckArray audioLoop = (ChuckArray) vm.getGlobalObject(BridgeContract.G_AUDIO_LOOP);
      ChuckArray audioRate = (ChuckArray) vm.getGlobalObject(BridgeContract.G_AUDIO_RATE);

      for (int r = 0; r < BridgeContract.TRACKS; r++) {
        if (trackType == null || trackType.getInt(r) != 2) continue;

        // Lazily create LiSa + routing for each audio track
        if (lisa[r] == null) {
          lisa[r] = new LiSa(sr);
          lisa[r].duration(maxRecordSamples);
          lisaPan[r] = new Pan2();
          lisaDsend[r] = new Gain();
          lisaRsend[r] = new Gain();

          // Adc => LiSa for recording input
          adc.chuck(lisa[r]);

          // LiSa => Pan2 => audioBus for playback, plus FX sends
          lisa[r].chuck(lisaPan[r]).chuck(audioBus);
          lisaPan[r].chuck(lisaDsend[r]).chuck((ChuckUGen) vm.getGlobalObject(BridgeContract.G_DELAY_IN));
          lisaPan[r].chuck(lisaRsend[r]).chuck((ChuckUGen) vm.getGlobalObject(BridgeContract.G_REVERB_IN));

          lisa[r].voiceGain(0, 1.0f);
          lisa[r].voicePan(0, 0.0f);
          lisa[r].rate(0, 1.0f);
          lisa[r].loop(0, 1);
          lisaDsend[r].gain(0.0f);
          lisaRsend[r].gain(0.15f);
        }

        // Update sends
        ChuckArray dlySnd = (ChuckArray) vm.getGlobalObject(BridgeContract.G_DELAY_SEND);
        ChuckArray revSnd = (ChuckArray) vm.getGlobalObject(BridgeContract.G_REVERB_SEND);
        lisaDsend[r].gain(dlySnd != null ? (float) dlySnd.getFloat(r) : 0.0f);
        lisaRsend[r].gain(revSnd != null ? (float) revSnd.getFloat(r) : 0.15f);

        // Recording
        int rec = audioRec != null ? (int) audioRec.getInt(r) : 0;
        lisa[r].record(rec);

        // Playback
        int play = audioPlay != null ? (int) audioPlay.getInt(r) : 0;
        lisa[r].play(0, play);

        // Loop mode
        int loop = audioLoop != null ? (int) audioLoop.getInt(r) : 1;
        lisa[r].loop(0, loop);

        // Play rate
        float rate = audioRate != null ? (float) audioRate.getFloat(r) : 1.0f;
        lisa[r].rate(0, rate);
      }
    }
  }

  // ── EXPORT SHRED (WvOut2) ────────────────────────────────────────────────

  private void export_shred() {
    WvOut2 wvOut = null;
    boolean wasRecording = false;

    while (isRunning()) {
      float active = (float) vm.getGlobalFloat(BridgeContract.G_WVOUT_ACTIVE);
      if (active > 0.5f) {
        if (!wasRecording) {
          // Start new export — create WvOut2 and tap into master output
          if (wvOut != null) wvOut.closeFile();
          wvOut = new WvOut2((float) sampleRate());
          wvOut.fileGain(1.0f);
          wvOut.record(1);

          // Splice WvOut2 between masterTap and dac:
          //   Before: masterTap → dac
          //   After:  masterTap → wvOut → dac
          // This ensures WvOut2.computeStereo() receives the mixed stereo signal
          // from masterTap's last output, then passes audio through to dac.
          Gain masterTap = (Gain) vm.getGlobalObject(BridgeContract.G_MASTER_TAP);
          ChuckUGen dac = dac();
          masterTap.unchuck(dac);
          masterTap.chuck(wvOut);
          wvOut.chuck(dac);

          Object filePathObj = vm.getGlobalObject(BridgeContract.G_WVOUT_FILE);
          String filePath = filePathObj instanceof String ? (String) filePathObj : null;
          if (filePath != null && !filePath.isEmpty()) {
            wvOut.wavWrite(filePath);
            if (vm.getLogLevel() >= 1) {
              vm.print("[export] Starting export to: " + filePath + "\n");
            }
          }
          wasRecording = true;
        }
        advance(ms(100));
      } else {
        if (wasRecording && wvOut != null) {
          // Restore original chain: masterTap → dac
          Gain masterTap = (Gain) vm.getGlobalObject(BridgeContract.G_MASTER_TAP);
          ChuckUGen dac = dac();
          wvOut.unchuck(dac);
          masterTap.unchuck(wvOut);
          masterTap.chuck(dac);

          wvOut.closeFile();
          if (vm.getLogLevel() >= 1) vm.print("[export] Export complete.\n");
        }
        wvOut = null;
        wasRecording = false;
        advance(ms(100));
      }
    }
  }

  // ── TRANSPORT ────────────────────────────────────────────────────────────

  private void transport_shred() {
    vm.setGlobalObject(BridgeContract.G_DELAY_IN, new Gain());
    vm.setGlobalObject(BridgeContract.G_REVERB_IN, new Gain());
    vm.setGlobalObject(BridgeContract.G_SYNTH_BUS, new Gain());
    vm.setGlobalObject(BridgeContract.G_AUDIO_BUS, new Gain());
    vm.setGlobalObject(BridgeContract.G_MASTER_TAP, new Gain());
    vm.setGlobalObject(BridgeContract.E_SIDECHAIN, new ChuckEvent());

    // Sync point: ensure buses are registered before any sub-shreds try to fetch them
    advance(samp(1));

    vm.spork(this::fx_bus_shred);
    vm.spork(this::master_shred);
    vm.spork(this::clock_shred);
    vm.spork(this::kit_shred);
    vm.spork(this::synth_shred);
    vm.spork(this::sidechain_shred);
    vm.spork(this::audio_shred);
    vm.spork(this::export_shred);

    while (isRunning()) {
      advance(ms(100));
    }
    running = false; // signal sub-shreds to stop
  }
}
