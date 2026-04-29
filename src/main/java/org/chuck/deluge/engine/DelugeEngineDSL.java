package org.chuck.deluge.engine;

import static org.chuck.core.ChuckDSL.*;

import org.chuck.audio.*;
import org.chuck.audio.filter.*;
import org.chuck.audio.fx.*;
import org.chuck.audio.osc.*;
import org.chuck.audio.util.*;
import org.chuck.core.*;
import org.chuck.deluge.BridgeContract;

/**
 * Native Java implementation of the Deluge Engine using the ChucK-Java DSL. Scaled to support up to
 * 64 tracks as defined in the Object Model.
 */
public class DelugeEngineDSL implements Shred, Runnable {

  private ChuckVM vm;
  private volatile boolean running = true;

  /** Returns true while the engine should keep running. Also checks shred-level abort flag. */
  private boolean isRunning() {
    if (!running) return false;
    if (ChuckShred.CURRENT_SHRED.isBound() && ChuckShred.CURRENT_SHRED.get().isDone()) {
      running = false;
      return false;
    }
    return true;
  }

  public DelugeEngineDSL() {}

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

  @Override
  public void run() {
    shred();
  }

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
    master.chuck(hpf).chuck(limit).chuck(dac());
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
      ChuckArray lfoVal = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_VALUE);
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

        // Per-track LFO contributions (pitch=3, vol=4)
        double lfoPit = 0, lfoV = 0;
        for (int l = 0; l < BridgeContract.LFO_COUNT; l++) {
          long lfoTrackTarget = lfoTrk != null ? lfoTrk.getInt(l) : -1L;
          if (lfoTrackTarget != -1L && lfoTrackTarget != r) continue;
          double lv = lfoVal != null ? lfoVal.getFloat(l) : 0.0;
          int tgt = lfoTgt != null ? (int) lfoTgt.getInt(l) : -1;
          if (tgt == 3) lfoPit += lv;
          else if (tgt == 4) lfoV += lv;
        }

        // Per-track polyrhythm step
        int len = trkLen != null ? (int) Math.max(1, Math.min(16, trkLen.getInt(r))) : 16;
        int step = (int) (currentStep % len);
        int idx = r * 16 + step;

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
    ChuckEvent loadEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.G_LOAD_TRIGGER);
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

    // Count actual synth voices from G_TRACK_TYPE (type 1 = synth)
    ChuckArray trackTypeInit = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_TYPE);
    int voiceCount = 0;
    for (int i = 0; i < BridgeContract.TRACKS; i++) {
      if (trackTypeInit != null && trackTypeInit.getInt(i) == 1) voiceCount = i + 1;
    }
    if (voiceCount < 1) voiceCount = 1;

    MorphingWavetable[] car = new MorphingWavetable[voiceCount];
    MorphingWavetable[] mod = new MorphingWavetable[voiceCount];
    SVFilter[] fil = new SVFilter[voiceCount];
    DelugeAdsr[] env = new DelugeAdsr[voiceCount];
    Pan2[] pan = new Pan2[voiceCount];
    Gain[] sDsend = new Gain[voiceCount];
    Gain[] sRsend = new Gain[voiceCount];

    for (int i = 0; i < voiceCount; i++) {
      car[i] = new MorphingWavetable(sr);
      car[i].setTables(WAVE_TABLES);
      mod[i] = new MorphingWavetable(sr);
      mod[i].setTables(WAVE_TABLES);
      fil[i] = new SVFilter();
      env[i] = new DelugeAdsr();
      pan[i] = new Pan2();
      sDsend[i] = new Gain();
      sRsend[i] = new Gain();
      mod[i].chuck(car[i]);
      car[i].chuck(fil[i]).chuck(env[i]).chuck(pan[i]).chuck(synthBus);
      pan[i].chuck(sDsend[i]).chuck((ChuckUGen) vm.getGlobalObject(BridgeContract.G_DELAY_IN));
      pan[i].chuck(sRsend[i]).chuck((ChuckUGen) vm.getGlobalObject(BridgeContract.G_REVERB_IN));
      fil[i].reset();
      fil[i].freq(5000);
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
        for (int i = 0; i < env.length; i++) env[i].keyOff();
        continue;
      }

      long currentStep = vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
      boolean isNewStep = (currentStep != lastStep);
      lastStep = currentStep;

      ChuckArray pat = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PATTERN);
      ChuckArray vel = (ChuckArray) vm.getGlobalObject(BridgeContract.G_VELOCITY);
      ChuckArray pitch = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PITCH);
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

      double masterPan = vm.getGlobalFloat(BridgeContract.G_MASTER_PAN);
      ChuckArray trkLen = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_LENGTH);
      ChuckArray lfoVal = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_VALUE);
      ChuckArray lfoTgt = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_TARGET);
      ChuckArray lfoTrk = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_TRACK);
      ChuckArray envArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ENV);

      for (int r = 0; r < car.length; r++) {
        // Only process Synth tracks (Type 1)
        if (trackType != null && trackType.getInt(r) != 1) continue;

        // Per-track LFO contributions
        double lfoF = 0, lfoQ = 0, lfoP = 0, lfoPit = 0, lfoV = 0, lfoFm = 0;
        for (int l = 0; l < BridgeContract.LFO_COUNT; l++) {
          long lfoTrackTarget = lfoTrk != null ? lfoTrk.getInt(l) : -1L;
          if (lfoTrackTarget != -1L && lfoTrackTarget != r) continue;
          double lv = lfoVal != null ? lfoVal.getFloat(l) : 0.0;
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
        int len = trkLen != null ? (int) Math.max(1, Math.min(16, trkLen.getInt(r))) : 16;
        int step = (int) (currentStep % len);
        int idx = r * 16 + step;

        sDsend[r].gain(dlySnd != null ? (float) dlySnd.getFloat(r) : 0.0f);
        sRsend[r].gain(revSnd != null ? (float) revSnd.getFloat(r) : 0.15f);

        if (oscType != null) car[r].index((int) oscType.getInt(r));

        double tf = (gFil.getFloat(r * 2) + sFil.getFloat(idx)) * 10000.0 + 100.0 + lfoF * 5000.0;
        double tq = (gFil.getFloat(r * 2 + 1) + sRes.getFloat(idx)) * 4.0 + 1.0 + lfoQ * 3.0;
        double tp = masterPan + (sPan != null ? sPan.getFloat(idx) : 0.0) + lfoP;
        fil[r].freq((float) Math.max(20.0, Math.min(20000.0, tf)));
        fil[r].Q((float) Math.max(1.0, Math.min(10.0, tq)));
        pan[r].pan((float) Math.max(-1.0, Math.min(1.0, tp)));

        if (mute.getInt(r) != 0) {
          env[r].keyOff();
          continue;
        }
        if (pat.getInt(idx) == 0) {
          env[r].keyOff();
          continue;
        }

        if (isNewStep) {
          if (prob != null && Math.random() > prob.getFloat(idx)) continue;

          // Apply envelope shape from bridge (if available)
          if (envArr != null) {
            int eb = r * BridgeContract.ENV_PARAMS;
            double a = envArr.getFloat(eb + 0);
            double d = envArr.getFloat(eb + 1);
            double s = envArr.getFloat(eb + 2);
            double rel = envArr.getFloat(eb + 3);
            env[r].set(a, d, s, rel);
          }

          double gainVal =
              vel.getFloat(idx) * trkLvl.getFloat(r) * 0.8 * Math.max(0.0, 1.0 + lfoV * 0.5);
          double gateSec =
              (gateArr != null ? gateArr.getFloat(idx) : 0.9)
                  * stepDuration(step % 2).samples()
                  / sampleRate();

          if (arpOn != null && arpOn.getInt(r) == 1) {
            int baseMidi = (int) ((24 - 1) - (r % 8)) + 60;
            int v = r;
            vm.spork(() -> run_arp(v, baseMidi, (float) gainVal, car[v], mod[v], env[v]));
          } else {
            double f = mtof(((24 - 1) - (r % 8)) + 60) * Math.pow(2.0, lfoPit);
            car[r].freq((float) f);
            double fmR = fmRatio != null ? fmRatio.getFloat(r) : 1.0;
            double fmA =
                (fmAmt != null ? fmAmt.getFloat(r) : 0.0) * Math.max(0.0, 1.0 + lfoFm * 0.5);
            mod[r].freq((float) (f * fmR));
            mod[r].gain((float) (fmA * 1000.0));
            env[r].gain((float) gainVal);
            env[r].keyOn();
            double noteSec = gateSec;
            int rv = r;
            if (vm.getLogLevel() >= 2) vm.print("SYNTH trigger track: " + rv + " step: " + (idx % 16) + "\n");
            vm.spork(
                () -> {
                  advance(second(noteSec));
                  env[rv].keyOff();
                  if (vm.getLogLevel() >= 2) vm.print("SYNTH note end track: " + rv + "\n");
                });
          }
        }
      }
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
    int octaves = (octArr != null) ? (int) octArr.getInt(v) : 1;
    if (octaves < 1) octaves = 1;
    for (int o = 0; o < octaves; o++) {
      double f = mtof(baseMidi + o * 12);
      car.freq((float) f);
      mod.freq((float) (f * (fmRatio != null ? fmRatio.getFloat(v) : 1.0)));
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

  // ── LFO SHRED ────────────────────────────────────────────────────────────

  private void lfo_shred() {
    double[] phase = new double[BridgeContract.LFO_COUNT];
    double dt = 0.005; // 5ms update interval → 200Hz, smooth up to ~50Hz LFO
    while (isRunning()) {
      advance(ms(5));
      ChuckArray lfoRate = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_RATE);
      ChuckArray lfoType = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_TYPE);
      ChuckArray lfoDepth = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_DEPTH);
      ChuckArray lfoValue = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_VALUE);
      if (lfoValue == null) continue;
      for (int l = 0; l < BridgeContract.LFO_COUNT; l++) {
        double rate = lfoRate != null ? lfoRate.getFloat(l) : 1.0;
        double depth = lfoDepth != null ? lfoDepth.getFloat(l) : 0.0;
        if (depth == 0.0) {
          lfoValue.setFloat(l, 0.0f);
          continue;
        }
        phase[l] = (phase[l] + rate * dt) % 1.0;
        int type = lfoType != null ? (int) lfoType.getInt(l) : 0;
        double raw =
            switch (type) {
              case 1 -> 2.0 * phase[l] - 1.0; // saw
              case 2 -> phase[l] < 0.5 ? 1.0 : -1.0; // square
              case 3 -> // triangle
                  phase[l] < 0.5 ? (4.0 * phase[l] - 1.0) : (3.0 - 4.0 * phase[l]);
              default -> Math.sin(2.0 * Math.PI * phase[l]); // sine
            };
        lfoValue.setFloat(l, (float) (raw * depth));
      }
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
    if ("FreeVerb".equals(reverbModel)) {
      rev = new FreeVerb();
    } else if ("MVerb".equals(reverbModel)) {
      rev = new MVerb();
    } else if ("ProceduralReverb".equals(reverbModel)) {
      rev = new ProceduralReverb();
    } else {
      rev = new JCRev();
    }

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
    ((Gain) vm.getGlobalObject(BridgeContract.G_SYNTH_BUS))
        .chuck(hpf)
        .chuck(comp)
        .chuck(limit)
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

  // ── TRANSPORT ────────────────────────────────────────────────────────────

  private void transport_shred() {
    vm.setGlobalObject(BridgeContract.G_DELAY_IN, new Gain());
    vm.setGlobalObject(BridgeContract.G_REVERB_IN, new Gain());
    vm.setGlobalObject(BridgeContract.G_SYNTH_BUS, new Gain());
    vm.setGlobalObject(BridgeContract.E_SIDECHAIN, new ChuckEvent());

    // Sync point: ensure buses are registered before any sub-shreds try to fetch them
    advance(samp(1));

    vm.spork(this::fx_bus_shred);
    vm.spork(this::master_shred);
    vm.spork(this::clock_shred);
    vm.spork(this::kit_shred);
    vm.spork(this::synth_shred);
    vm.spork(this::sidechain_shred);
    vm.spork(this::lfo_shred);

    while (isRunning()) {
      advance(ms(100));
    }
    running = false; // signal sub-shreds to stop
  }
}
