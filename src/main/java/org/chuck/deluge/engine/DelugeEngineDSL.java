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
 * forks 8 sub-shreds (as static inner classes) that collectively implement the full
 * Deluge audio pipeline: kit sample playback, FM/STK synthesis, audio clip
 * recording/playback (LiSa), master export (WvOut2), clock generation, FX buses,
 * and sidechain ducking.
 *
 * <h2>Architecture</h2>
 * All state is read from shared {@link ChuckArray}s registered by {@link BridgeContract}.
 * The main transport loop waits on tick_event broadcast by ClockShred at each step
 * boundary (swing-aware). No polling or shared locks are needed.
 *
 * <h2>Inner Classes (Sub-shreds)</h2>
 * <ol>
 *   <li>{@code FxBusShred} — delay, reverb, chorus UGens</li>
 *   <li>{@code MasterShred} — synth/audio bus processing (HPF, comp, limiter)</li>
 *   <li>{@code ClockShred} — swing-aware step clock broadcasting tick_event</li>
 *   <li>{@code KitShred} — sample playback engine with LFO</li>
 *   <li>{@code SynthShred} — FM/STK synthesis engine with LFO</li>
 *   <li>{@code SidechainShred} — kick-triggered ducking</li>
 *   <li>{@code AudioShred} — LiSa recording/playback</li>
 *   <li>{@code ExportShred} — WvOut2 offline export</li>
 * </ol>
 *
 * @see BridgeContract
 * @see org.chuck.deluge.ui.SwingDelugeApp
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

  private ChuckArray getClipArray(String baseName, int clipIdx) {
    if (clipIdx <= 0) return (ChuckArray) vm.getGlobalObject(baseName);
    String name = baseName + "_C" + clipIdx;
    Object arr = vm.getGlobalObject(name);
    if (arr instanceof ChuckArray ca) return ca;
    return (ChuckArray) vm.getGlobalObject(baseName);
  }

  private void applyClipQueues() {
    ChuckArray queue = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LAUNCH_QUEUE);
    ChuckArray currentClip = (ChuckArray) vm.getGlobalObject(BridgeContract.G_CURRENT_CLIP);
    if (queue == null || currentClip == null) return;
    for (int t = 0; t < BridgeContract.TRACKS; t++) {
      long q = queue.getInt(t);
      if (q >= 0) {
        currentClip.setInt(t, q);
        queue.setInt(t, -1L);
      }
    }
  }

  private ChuckUGen createStkUGen(int algo, float sr) {
    return switch (algo) {
      case 10 -> new org.chuck.audio.stk.Mandolin(20.0f, sr);
      case 11 -> new org.chuck.audio.stk.Rhodey(sr);
      case 12 -> new org.chuck.audio.stk.ModalBar(sr);
      case 13 -> new org.chuck.audio.stk.Moog(sr);
      default -> new org.chuck.audio.stk.Mandolin(20.0f, sr);
    };
  }

  private void triggerStkNote(ChuckUGen ugen, float freq, float velocity) {
    try {
      ugen.getClass().getMethod("setFreq", double.class).invoke(ugen, (double) freq);
      ugen.getClass().getMethod("noteOn", float.class).invoke(ugen, velocity);
    } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
      if (vm.getLogLevel() >= 1) vm.print("STK noteOn failed: " + e.getMessage() + "\n");
    }
  }

  private void releaseStkNote(ChuckUGen ugen) {
    try {
      ugen.getClass().getMethod("noteOff", float.class).invoke(ugen, 0.0f);
    } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
      if (vm.getLogLevel() >= 1) vm.print("STK noteOff failed: " + e.getMessage() + "\n");
    }
  }

  // ── ClockShred ────────────────────────────────────────────────
  static final class ClockShred implements Runnable {
    private final ChuckVM vm;
    private final DelugeEngineDSL outer;
    ClockShred(ChuckVM vm, DelugeEngineDSL outer) { this.vm = vm; this.outer = outer; }

    private boolean isRunning() { return outer.isRunning(); }

    @Override
    public void run() {
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
          if (step % 16 == 0) {
            outer.applyClipQueues();
          }
          ((ChuckEvent) vm.getGlobalObject(BridgeContract.E_TICK)).broadcast();
          advance(outer.stepDuration(step % 2));
          step++;
        } else {
          ((ChuckEvent) vm.getGlobalObject(BridgeContract.E_TICK)).broadcast();
          double div = Math.max(1.0, vm.getGlobalFloat(BridgeContract.G_STUTTER_DIV));
          advance(samp(outer.stepDuration(step % 2).samples() / div));
        }
      }
    }
  }

  // ── FxBusShred ────────────────────────────────────────────────
  static final class FxBusShred implements Runnable {
    private final ChuckVM vm;
    FxBusShred(ChuckVM vm) { this.vm = vm; }

    private boolean isRunning() {
      if (!ChuckShred.CURRENT_SHRED.isBound()) return false;
      return !ChuckShred.CURRENT_SHRED.get().isDone();
    }

    @Override
    public void run() {
      float sr = (float) sampleRate();
      Gain fxIn = new Gain();
      DelugeAdsr gate = new DelugeAdsr();
      HPF fxHpf = new HPF(sr);
      fxHpf.freq(80);
      fxIn.chuck(gate).chuck(fxHpf).chuck(dac());

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

      fxIn.gain(0.15f);
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
  }

  // ── MasterShred ────────────────────────────────────────────────
  static final class MasterShred implements Runnable {
    private final ChuckVM vm;
    MasterShred(ChuckVM vm) { this.vm = vm; }

    private boolean isRunning() {
      if (!ChuckShred.CURRENT_SHRED.isBound()) return false;
      return !ChuckShred.CURRENT_SHRED.get().isDone();
    }

    @Override
    public void run() {
      float sr = (float) sampleRate();
      HPF hpf = new HPF(sr);
      Dyno limit = new Dyno(sr);
      Dyno comp = new Dyno(sr);
      Gain masterTap = (Gain) vm.getGlobalObject(BridgeContract.G_MASTER_TAP);
      ((Gain) vm.getGlobalObject(BridgeContract.G_SYNTH_BUS))
          .chuck(hpf).chuck(comp).chuck(limit).chuck(masterTap).chuck(dac());
      ((Gain) vm.getGlobalObject(BridgeContract.G_AUDIO_BUS))
          .chuck(hpf).chuck(comp).chuck(limit).chuck(masterTap).chuck(dac());
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
  }

  // ── SidechainShred ────────────────────────────────────────────
  static final class SidechainShred implements Runnable {
    private final ChuckVM vm;
    SidechainShred(ChuckVM vm) { this.vm = vm; }

    private boolean isRunning() {
      if (!ChuckShred.CURRENT_SHRED.isBound()) return false;
      return !ChuckShred.CURRENT_SHRED.get().isDone();
    }

    @Override
    public void run() {
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
  }

  // ── AudioShred (LiSa) ─────────────────────────────────────────
  static final class AudioShred implements Runnable {
    private final ChuckVM vm;
    AudioShred(ChuckVM vm) { this.vm = vm; }

    private boolean isRunning() {
      if (!ChuckShred.CURRENT_SHRED.isBound()) return false;
      return !ChuckShred.CURRENT_SHRED.get().isDone();
    }

    @Override
    public void run() {
      float sr = (float) sampleRate();
      Gain audioBus = (Gain) vm.getGlobalObject(BridgeContract.G_AUDIO_BUS);
      ChuckUGen adc = org.chuck.core.ChuckDSL.adc();
      int maxRecordSamples = (int) (sr * 60.0f);
      LiSa[] lisa = new LiSa[BridgeContract.TRACKS];
      Pan2[] lisaPan = new Pan2[BridgeContract.TRACKS];
      Gain[] lisaDsend = new Gain[BridgeContract.TRACKS];
      Gain[] lisaRsend = new Gain[BridgeContract.TRACKS];

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

          if (lisa[r] == null) {
            lisa[r] = new LiSa(sr);
            lisa[r].duration(maxRecordSamples);
            lisaPan[r] = new Pan2();
            lisaDsend[r] = new Gain();
            lisaRsend[r] = new Gain();

            adc.chuck(lisa[r]);

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

          ChuckArray dlySnd = (ChuckArray) vm.getGlobalObject(BridgeContract.G_DELAY_SEND);
          ChuckArray revSnd = (ChuckArray) vm.getGlobalObject(BridgeContract.G_REVERB_SEND);
          lisaDsend[r].gain(dlySnd != null ? (float) dlySnd.getFloat(r) : 0.0f);
          lisaRsend[r].gain(revSnd != null ? (float) revSnd.getFloat(r) : 0.15f);

          int rec = audioRec != null ? (int) audioRec.getInt(r) : 0;
          lisa[r].record(rec);

          int play = audioPlay != null ? (int) audioPlay.getInt(r) : 0;
          lisa[r].play(0, play);

          int loop = audioLoop != null ? (int) audioLoop.getInt(r) : 1;
          lisa[r].loop(0, loop);

          float rate = audioRate != null ? (float) audioRate.getFloat(r) : 1.0f;
          lisa[r].rate(0, rate);
        }
      }
    }
  }

  // ── ExportShred (WvOut2) ──────────────────────────────────────
  static final class ExportShred implements Runnable {
    private final ChuckVM vm;
    ExportShred(ChuckVM vm) { this.vm = vm; }

    private boolean isRunning() {
      if (!ChuckShred.CURRENT_SHRED.isBound()) return false;
      return !ChuckShred.CURRENT_SHRED.get().isDone();
    }

    @Override
    public void run() {
      WvOut2 wvOut = null;
      boolean wasRecording = false;

      while (isRunning()) {
        float active = (float) vm.getGlobalFloat(BridgeContract.G_WVOUT_ACTIVE);
        if (active > 0.5f) {
          if (!wasRecording) {
            if (wvOut != null) wvOut.closeFile();
            wvOut = new WvOut2((float) sampleRate());
            wvOut.fileGain(1.0f);
            wvOut.record(1);

            Gain masterTap = (Gain) vm.getGlobalObject(BridgeContract.G_MASTER_TAP);
            ChuckUGen dac = dac();
            masterTap.unchuck(dac);
            masterTap.chuck(wvOut);
            wvOut.chuck(dac);

            Object filePathObj = vm.getGlobalObject(BridgeContract.G_WVOUT_FILE);
            String filePath = filePathObj instanceof String ? (String) filePathObj : null;
            if (filePath != null && !filePath.isEmpty()) {
              wvOut.wavWrite(filePath);
            }
            wasRecording = true;
          }
          advance(ms(100));
        } else {
          if (wasRecording && wvOut != null) {
            Gain masterTap = (Gain) vm.getGlobalObject(BridgeContract.G_MASTER_TAP);
            ChuckUGen dac = dac();
            wvOut.unchuck(dac);
            masterTap.unchuck(wvOut);
            masterTap.chuck(dac);
            wvOut.closeFile();
          }
          wvOut = null;
          wasRecording = false;
          advance(ms(100));
        }
      }
    }
  }

  // ── KitShred ──────────────────────────────────────────────────
  static final class KitShred implements Runnable {
    private final ChuckVM vm;
    private final DelugeEngineDSL outer;
    private final double[] lfoPhaseKit = new double[BridgeContract.LFO_COUNT];

    KitShred(ChuckVM vm, DelugeEngineDSL outer) { this.vm = vm; this.outer = outer; }

    private boolean isRunning() { return outer.isRunning(); }

    private void loadSndBuf(SndBuf buf, String path) {
      try {
        buf.rate(0);
        java.io.File wavFile = new java.io.File(path);
        vm.print("[kit] loadSndBuf: exists=" + wavFile.exists() + " len=" + wavFile.length() + " path=" + path + "\n");
        buf.read(path);
        buf.rate(0);
        long samples = buf.samples();
        vm.print("[kit] loadSndBuf: after read samples=" + samples + "\n");
        if (samples > 0) buf.pos(samples);
        else vm.print("[kit] loadSndBuf: loaded 0 samples from " + path + "\n");
      } catch (Exception e) {
        vm.print("[kit] loadSndBuf ERROR: " + path + " \u2014 " + e.getMessage() + "\n");
      }
    }

    private void loadKitSamples(SndBuf[] kit) {
      java.io.File libraryDir = org.chuck.deluge.project.PreferencesManager.getLibraryDir();
      for (int i = 0; i < kit.length; i++) {
        Object pathObj = vm.getGlobalObject("g_sample_" + i);
        if (!(pathObj instanceof String path) || path.isEmpty()) continue;
        java.io.File f = new java.io.File(path);
        if (f.exists()) { loadSndBuf(kit[i], f.getAbsolutePath()); continue; }
        java.io.File rel = new java.io.File(libraryDir, path);
        if (rel.exists()) { loadSndBuf(kit[i], rel.getAbsolutePath()); continue; }
        java.net.URL resourceUrl = getClass().getClassLoader().getResource(path);
        if (resourceUrl != null) {
          String decodedPath = java.net.URLDecoder.decode(resourceUrl.getPath(), java.nio.charset.StandardCharsets.UTF_8);
          java.io.File classpathFile = new java.io.File(decodedPath);
          if (classpathFile.exists()) { loadSndBuf(kit[i], classpathFile.getAbsolutePath()); continue; }
        }
        vm.print("[kit] WARN: sample not found: " + path + " (tried " + rel.getAbsolutePath() + ")\n");
      }
    }

    private void kit_preview_shred(SndBuf[] kit, DelugeAdsr[] kitEnv) {
      vm.print("[kit_preview] shred started, kit.length=" + kit.length + "\n");
      SndBuf previewBuf = new SndBuf();
      DelugeAdsr previewEnv = new DelugeAdsr();
      previewBuf.rate(0);
      previewEnv.forceMute();
      previewEnv.set(0.001, 0.0, 1.0, 0.05);
      previewBuf.chuck(previewEnv).chuck((ChuckUGen) vm.getGlobalObject(BridgeContract.G_MASTER_TAP)).chuck(dac());
      ChuckEvent previewEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.E_PREVIEW);
      int currentPreviewTrack = -1;
      boolean stopped = true;
      java.io.File libraryDir = org.chuck.deluge.project.PreferencesManager.getLibraryDir();
      while (isRunning()) {
        advance(previewEvent);
        int r = (int) vm.getGlobalInt(BridgeContract.G_PREVIEW_TRACK);
        if (r < 0) {
          stopped = true;
          previewEnv.forceMute();
          previewBuf.rate(0);
          previewBuf.gain(0);
        } else if (r < kit.length) {
          stopped = false;
          currentPreviewTrack = r;
          Object pathObj = vm.getGlobalObject("g_sample_" + r);
          if (pathObj instanceof String path && !path.isEmpty()) {
            java.io.File f = new java.io.File(path);
            if (f.exists()) { previewBuf.setRead(f.getAbsolutePath()); }
            else {
              java.io.File rel = new java.io.File(libraryDir, path);
              if (rel.exists()) { previewBuf.setRead(rel.getAbsolutePath()); }
              else {
                java.net.URL url = getClass().getClassLoader().getResource(path);
                if (url != null) {
                  String decoded = java.net.URLDecoder.decode(url.getPath(), java.nio.charset.StandardCharsets.UTF_8);
                  previewBuf.setRead(decoded);
                }
              }
            }
          }
          if (previewBuf.samples() > 0) {
            previewBuf.rate(1);
            previewBuf.gain(0.8f);
            previewBuf.pos(0);
            previewEnv.keyOn();
            vm.print("[kit_preview] START r=" + r + " samples=" + previewBuf.samples() + "\n");
            currentPreviewTrack = r;
          }
        }
      }
    }

    private void kit_reload_shred(SndBuf[] kit) {
      ChuckEvent loadEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.G_LOAD_TRIGGER);
      while (isRunning()) {
        advance(loadEvent);
        loadKitSamples(kit);
      }
    }

    @Override
    public void run() {
      float sr = (float) sampleRate();
      Gain master = new Gain();
      HPF hpf = new HPF(sr);
      Dyno limit = new Dyno(sr);
      Gain masterTap = (Gain) vm.getGlobalObject(BridgeContract.G_MASTER_TAP);
      master.chuck(hpf).chuck(limit).chuck(masterTap).chuck(dac());
      hpf.freq(20);
      limit.limiter();
      ChuckEvent loadEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.G_LOAD_TRIGGER);
      advance(loadEvent);
      final SndBuf[][] kitHolder = new SndBuf[1][];
      final Pan2[][] panHolder = new Pan2[1][];
      final Gain[][] dSendHolder = new Gain[1][];
      final Gain[][] rSendHolder = new Gain[1][];
      final DelugeAdsr[][] kitEnvHolder = new DelugeAdsr[1][];
      java.util.function.Consumer<Gain> doInit = (bus) -> {
        ChuckArray tt = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_TYPE);
        int vc = 0;
        for (int i = 0; i < BridgeContract.TRACKS; i++) {
          if (tt == null || tt.getInt(i) == 0) {
            Object p = vm.getGlobalObject("g_sample_" + i);
            if (p instanceof String s && !s.isEmpty()) vc = i + 1;
          }
        }
        if (vc < 1) vc = 1;
        SndBuf[] k = new SndBuf[vc];
        Pan2[] pn = new Pan2[vc];
        Gain[] ds = new Gain[vc];
        Gain[] rs = new Gain[vc];
        DelugeAdsr[] ke = new DelugeAdsr[vc];
        for (int i = 0; i < vc; i++) {
          k[i] = new SndBuf(); k[i].rate(0); pn[i] = new Pan2(); ds[i] = new Gain(); rs[i] = new Gain(); ke[i] = new DelugeAdsr();
          k[i].chuck(ke[i]).chuck(pn[i]).chuck(bus);
          pn[i].chuck(ds[i]).chuck((ChuckUGen) vm.getGlobalObject(BridgeContract.G_DELAY_IN));
          pn[i].chuck(rs[i]).chuck((ChuckUGen) vm.getGlobalObject(BridgeContract.G_REVERB_IN));
          ke[i].forceMute(); ke[i].set(0.001, 0, 1, 0.05); ds[i].gain(0.0f); rs[i].gain(0.15f);
        }
        loadKitSamples(k);
        for (int i = 0; i < k.length && i < 4; i++) {
          System.out.println("[kit_shred] INIT kit[" + i + "]: samples=" + k[i].samples() + " rate=" + k[i].rate() + " pos=" + k[i].pos());
        }
        System.out.println("[kit_shred] INIT voiceCount=" + vc);
        kitHolder[0] = k; panHolder[0] = pn; dSendHolder[0] = ds; rSendHolder[0] = rs; kitEnvHolder[0] = ke;
      };
      doInit.accept(master);
      vm.print("[kit] sporking preview shred, kit.length=" + kitHolder[0].length + "\n");
      vm.spork(() -> kit_preview_shred(kitHolder[0], kitEnvHolder[0]));
      vm.spork(() -> kit_reload_shred(kitHolder[0]));
      ChuckEvent tickEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.E_TICK);
      long lastStep = -1;
      org.chuck.core.ChuckShred current = org.chuck.core.ChuckShred.CURRENT_SHRED.get();
      while (current != null && !current.isDone()) {
        advance(tickEvent);
        if (vm.getGlobalInt(BridgeContract.G_RELOAD) > 0) {
          vm.setGlobalInt(BridgeContract.G_RELOAD, 0L);
          if (vm.getLogLevel() >= 1) vm.print("[kit_shred] re-init triggered\n");
          advance(ms(1));
          doInit.accept(master);
          vm.spork(() -> kit_preview_shred(kitHolder[0], kitEnvHolder[0]));
          vm.spork(() -> kit_reload_shred(kitHolder[0]));
          lastStep = -1; continue;
        }
        SndBuf[] kit = kitHolder[0];
        Pan2[] pan = panHolder[0];
        Gain[] dSend = dSendHolder[0];
        Gain[] rSend = rSendHolder[0];
        DelugeAdsr[] kitEnv = kitEnvHolder[0];
        if (vm.getGlobalInt(BridgeContract.G_PLAY) == 0) {
          lastStep = -1;
          for (int r = 0; r < kit.length; r++) { kitEnv[r].keyOff(); kit[r].rate(0); }
          continue;
        }
        long currentStep = vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
        if (currentStep == lastStep) continue;
        lastStep = currentStep;
        ChuckArray curClipArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_CURRENT_CLIP);
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
          if (trackType != null && trackType.getInt(r) != 0) continue;
          if (mute != null && mute.getInt(r) != 0) continue;
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
          int clipIdx = curClipArr != null ? (int) curClipArr.getInt(r) : 0;
          ChuckArray clipPat = outer.getClipArray(BridgeContract.G_PATTERN, clipIdx);
          ChuckArray clipVel = outer.getClipArray(BridgeContract.G_VELOCITY, clipIdx);
          ChuckArray clipSStart = outer.getClipArray(BridgeContract.G_STEP_START, clipIdx);
          ChuckArray clipSEnd = outer.getClipArray(BridgeContract.G_STEP_END, clipIdx);
          ChuckArray clipProb = outer.getClipArray(BridgeContract.G_PROBABILITY, clipIdx);
          int len = trkLen != null ? (int) Math.max(1, trkLen.getInt(r)) : BridgeContract.STEPS;
          int step = (int) (currentStep % len);
          int idx = r * BridgeContract.STEPS + step;
          if (clipPat == null || clipPat.getInt(idx) == 0) continue;
          if (clipProb != null && Math.random() > clipProb.getFloat(idx)) continue;
          if (kitMuteGrp != null) {
            long grp = kitMuteGrp.getInt(r);
            if (grp > 0) {
              for (int o = 0; o < kit.length; o++) {
                if (o != r && kitMuteGrp.getInt(o) == grp) { kit[o].rate(0); kitEnv[o].keyOff(); }
              }
            }
          }
          if (r == 0) {
            ChuckEvent sidechainEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.E_SIDECHAIN);
            if (sidechainEvent != null) sidechainEvent.broadcast();
          }
          double pitchSemi = (kitPitch != null) ? kitPitch.getFloat(r) : 0.0;
          double rate = Math.pow(2.0, (pitchSemi + lfoPit * 12.0) / 12.0);
          boolean reverse = (kitRev != null) && kitRev.getInt(r) != 0;
          long samples = Math.max(1, kit[r].samples());
          float startAt = clipSStart != null ? (float) clipSStart.getFloat(idx) : 0.0f;
          float endAt = clipSEnd != null ? (float) clipSEnd.getFloat(idx) : 1.0f;
          long startPos = (long) (startAt * samples);
          long endPos = (long) (endAt * samples);
          if (reverse) { kit[r].rate((float) -rate); kit[r].pos(endPos); }
          else { kit[r].rate((float) rate); kit[r].pos(startPos); }
          float gain = (float) (clipVel.getFloat(idx) * trkLvl.getFloat(r) * Math.max(0.0, 1.0 + lfoV * 0.5));
          kit[r].gain(gain);
          kitEnv[r].keyOn();
          long playLen = Math.abs(endPos - startPos);
          double durSec = playLen / (sampleRate() * Math.abs(rate));
          int trackIdx = r;
          vm.spork(() -> {
            advance(second(durSec));
            kitEnv[trackIdx].keyOff();
            kit[trackIdx].rate(0);
          });
        }
      }
    }
  }

  // ── SynthShred ────────────────────────────────────────────────
  static final class SynthShred implements Runnable {
    private final ChuckVM vm;
    private final DelugeEngineDSL outer;
    private final double[] lfoPhase = new double[BridgeContract.LFO_COUNT];

    SynthShred(ChuckVM vm, DelugeEngineDSL outer) { this.vm = vm; this.outer = outer; }

    private boolean isRunning() { return outer.isRunning(); }

    private void synth_preview_shred(
        MorphingWavetable[] car, MorphingWavetable[] mod, Dx7Engine[] dx7, DelugeAdsr[] env) {
      ChuckEvent previewEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.E_PREVIEW);
      while (isRunning()) {
        advance(previewEvent);
        int r = (int) vm.getGlobalInt(BridgeContract.G_PREVIEW_TRACK);
        if (r >= 0 && r < car.length) {
          ChuckArray trackTypeArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_TYPE);
          if (trackTypeArr == null || trackTypeArr.getInt(r) != 1) continue;
          double previewPitch = vm.getGlobalFloat(BridgeContract.G_PREVIEW_PITCH);
          double f = outer.mtof(previewPitch + 60);
          if (dx7 != null && r < dx7.length && dx7[r] != null) {
            dx7[r].setFreq((float) f);
            dx7[r].noteOn();
            int rv = r;
            vm.spork(() -> { advance(ms(200)); dx7[rv].noteOff(); });
          } else {
            car[r].freq((float) f);
            mod[r].freq((float) f);
            env[r].gain(0.8f);
            env[r].keyOn();
            int trackIdx = r;
            vm.spork(() -> { advance(ms(200)); env[trackIdx].keyOff(); });
          }
        }
      }
    }

    private void run_arp(
        int v, int baseMidi, float gain,
        MorphingWavetable car, MorphingWavetable mod, DelugeAdsr env) {
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
      if (mode == 2) totalNotes = octaves * 2 - 1;
      for (int n = 0; n < totalNotes; n++) {
        int midiNote;
        switch (mode) {
          case 1 -> midiNote = baseMidi + (octaves - 1 - n) * 12;
          case 2 -> { if (n < octaves) midiNote = baseMidi + n * 12; else midiNote = baseMidi + (octaves * 2 - 2 - n) * 12; }
          case 3 -> midiNote = baseMidi + (int) (Math.random() * octaves) * 12;
          default -> midiNote = baseMidi + n * 12;
        }
        double f = outer.mtof(midiNote);
        car.freq((float) f);
        if (synthMode == 1) { mod.freq((float) (f * (fmRatio != null ? fmRatio.getFloat(v) : 1.0))); }
        else { mod.gain(0.0f); }
        env.gain((float) (gain * 0.8));
        env.keyOn();
        double rate = (rateArr != null) ? rateArr.getFloat(v) : 1.0;
        double bpm = vm.getGlobalFloat(BridgeContract.G_BPM);
        ChuckDuration d = second(60.0 / bpm / 4.0 / rate);
        advance(samp(d.samples() * 0.8));
        env.keyOff();
        advance(samp(d.samples() * 0.2));
        if (vm.getGlobalInt(BridgeContract.G_PLAY) == 0 || (arpOn != null && arpOn.getInt(v) == 0)) break;
      }
    }

    @Override
    public void run() {
      float sr = (float) sampleRate();
      Gain synthBus = (Gain) vm.getGlobalObject(BridgeContract.G_SYNTH_BUS);
      System.out.println("[synth_shred] entered");
      ChuckEvent loadEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.G_LOAD_TRIGGER);
      advance(loadEvent);
      System.out.println("[synth_shred] past initial load trigger");

      final MorphingWavetable[][] carRefHolder = new MorphingWavetable[1][];
      final MorphingWavetable[][] modRefHolder = new MorphingWavetable[1][];
      final Dx7Engine[][] dx7RefHolder = new Dx7Engine[1][];
      final SVFilter[][] filRefHolder = new SVFilter[1][];
      final HPF[][] hpfRefHolder = new HPF[1][];
      final DelugeAdsr[][] envRefHolder = new DelugeAdsr[1][];
      final Pan2[][] panRefHolder = new Pan2[1][];
      final Gain[][] sDsendRefHolder = new Gain[1][];
      final Gain[][] sRsendRefHolder = new Gain[1][];
      final ChuckUGen[][] srcRefHolder = new ChuckUGen[1][];
      final int[] synthBaseHolder = new int[]{0};
      final int[] maxSynthBridgeRowHolder = new int[]{0};

      java.util.function.Consumer<Gain> doInit = (bus) -> {
        ChuckArray trackTypeInit = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_TYPE);
        int sb = -1, mx = -1;
        for (int i = 0; i < BridgeContract.TRACKS; i++) {
          if (trackTypeInit != null && trackTypeInit.getInt(i) == 1) { if (sb < 0) sb = i; mx = i; }
        }
        if (sb < 0) sb = 0;
        if (mx < sb) mx = sb;
        int total = mx - sb + 1;
        synthBaseHolder[0] = sb;
        maxSynthBridgeRowHolder[0] = mx;
        System.out.println("[synth_shred] init synthBase=" + sb + " maxSynthBridgeRow=" + mx + " slots=" + total);
        ChuckArray algoArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_SYNTH_ALGO);
        ChuckUGen[] src = new ChuckUGen[total];
        MorphingWavetable[] car = new MorphingWavetable[total];
        MorphingWavetable[] mod = new MorphingWavetable[total];
        Dx7Engine[] dx7 = new Dx7Engine[total];
        SVFilter[] fil = new SVFilter[total];
        HPF[] hpf = new HPF[total];
        DelugeAdsr[] env = new DelugeAdsr[total];
        Pan2[] pan = new Pan2[total];
        Gain[] sDsend = new Gain[total];
        Gain[] sRsend = new Gain[total];
        for (int i = 0; i < total; i++) {
          int bridgeRow = sb + i;
          int algo = algoArr != null ? (int) algoArr.getInt(bridgeRow) : 0;
          fil[i] = new SVFilter(); hpf[i] = new HPF(sr); env[i] = new DelugeAdsr();
          pan[i] = new Pan2(); sDsend[i] = new Gain(); sRsend[i] = new Gain();
          if (algo >= 10) {
            src[i] = outer.createStkUGen(algo, sr);
            src[i].chuck(fil[i]).chuck(hpf[i]).chuck(env[i]).chuck(pan[i]).chuck(bus);
          } else {
            String dx7PatchStr = (String) vm.getGlobalObject("g_dx7_patch_" + bridgeRow);
            if (dx7PatchStr != null && !dx7PatchStr.isEmpty()) {
              dx7[i] = new Dx7Engine(sr);
              dx7[i].loadPatch(org.chuck.audio.util.Dx7Patch.fromHex(dx7PatchStr));
              dx7[i].chuck(fil[i]).chuck(hpf[i]).chuck(env[i]).chuck(pan[i]).chuck(bus);
              src[i] = dx7[i];
            } else {
              car[i] = new MorphingWavetable(sr);
              car[i].setTables(DelugeEngineDSL.WAVE_TABLES);
              mod[i] = new MorphingWavetable(sr);
              mod[i].setTables(DelugeEngineDSL.WAVE_TABLES);
              mod[i].chuck(car[i]);
              car[i].chuck(fil[i]).chuck(hpf[i]).chuck(env[i]).chuck(pan[i]).chuck(bus);
              src[i] = car[i];
            }
          }
          pan[i].chuck(sDsend[i]).chuck((ChuckUGen) vm.getGlobalObject(BridgeContract.G_DELAY_IN));
          pan[i].chuck(sRsend[i]).chuck((ChuckUGen) vm.getGlobalObject(BridgeContract.G_REVERB_IN));
          fil[i].reset(); fil[i].freq(5000); hpf[i].freq(20.0f);
          env[i].set(0.05, 0.2, 0.5, 0.3); env[i].forceMute();
          sDsend[i].gain(0.0f); sRsend[i].gain(0.15f);
        }
        carRefHolder[0] = car; modRefHolder[0] = mod; dx7RefHolder[0] = dx7;
        filRefHolder[0] = fil; hpfRefHolder[0] = hpf; envRefHolder[0] = env;
        panRefHolder[0] = pan; sDsendRefHolder[0] = sDsend; sRsendRefHolder[0] = sRsend;
        srcRefHolder[0] = src;
      };

      doInit.accept(synthBus);
      vm.spork(() -> synth_preview_shred(carRefHolder[0], modRefHolder[0], dx7RefHolder[0], envRefHolder[0]));

      ChuckEvent tickEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.E_TICK);
      long lastStep = -1;

      while (isRunning()) {
        advance(tickEvent);
        if (vm.getGlobalInt(BridgeContract.G_RELOAD) > 0) {
          vm.setGlobalInt(BridgeContract.G_RELOAD, 0L);
          if (vm.getLogLevel() >= 1) vm.print("[synth_shred] re-init triggered\n");
          advance(ms(1));
          doInit.accept(synthBus);
          vm.spork(() -> synth_preview_shred(carRefHolder[0], modRefHolder[0], dx7RefHolder[0], envRefHolder[0]));
          lastStep = -1; continue;
        }
        if (vm.getGlobalInt(BridgeContract.G_PLAY) == 0) {
          lastStep = -1;
          for (DelugeAdsr env1 : envRefHolder[0]) env1.keyOff();
          continue;
        }
        long currentStep = vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
        if (currentStep == lastStep) continue;
        lastStep = currentStep;

        int synthBase = synthBaseHolder[0];
        int maxSynthBridgeRow = maxSynthBridgeRowHolder[0];
        MorphingWavetable[] car = carRefHolder[0];
        MorphingWavetable[] mod = modRefHolder[0];
        Dx7Engine[] dx7 = dx7RefHolder[0];
        SVFilter[] fil = filRefHolder[0];
        HPF[] hpfArr = hpfRefHolder[0];
        DelugeAdsr[] env = envRefHolder[0];
        Pan2[] pan = panRefHolder[0];
        Gain[] sDsend = sDsendRefHolder[0];
        Gain[] sRsend = sRsendRefHolder[0];
        ChuckUGen[] src = srcRefHolder[0];

        ChuckArray curClipArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_CURRENT_CLIP);
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
        ChuckArray algoArrLive = (ChuckArray) vm.getGlobalObject(BridgeContract.G_SYNTH_ALGO);
        ChuckArray synthModeArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_SYNTH_MODE);
        ChuckArray hpfFreqArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_HPF_FREQ);
        ChuckArray hpfResArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_HPF_RES);
        ChuckArray polyphonyArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_POLYPHONY);
        ChuckArray car1FbArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_CARRIER1_FB);
        ChuckArray sHpfFreq = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_HPF_FREQ);
        ChuckArray sHpfRes  = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_HPF_RES);
        ChuckArray sModRate = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_MOD_RATE);
        ChuckArray sModDepth = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_MOD_DEPTH);
        ChuckArray sOscAVol = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_OSC_A_VOL);
        ChuckArray sOscBVol = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_OSC_B_VOL);
        ChuckArray sNoiseVol = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_NOISE_VOL);
        ChuckArray sPitchArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_PITCH);
        ChuckArray notePitchArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PITCH);

        for (int r = synthBase; r <= maxSynthBridgeRow; r++) {
          int u = r - synthBase;
          if (trackType != null && trackType.getInt(r) != 1) continue;
          int algo = algoArrLive != null ? (int) algoArrLive.getInt(r) : 0;
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
          double lfoF = 0, lfoQ = 0, lfoP = 0, lfoPit = 0, lfoV = 0, lfoFm = 0;
          for (int l = 0; l < BridgeContract.LFO_COUNT; l++) {
            long lfoTrackTarget = lfoTrk != null ? lfoTrk.getInt(l) : -1L;
            if (lfoTrackTarget != -1L && lfoTrackTarget != r) continue;
            double lv = lfoVals[l];
            int tgt = lfoTgt != null ? (int) lfoTgt.getInt(l) : -1;
            switch (tgt) {
              case 0 -> lfoF += lv; case 1 -> lfoQ += lv; case 2 -> lfoP += lv;
              case 3 -> lfoPit += lv; case 4 -> lfoV += lv; case 5 -> lfoFm += lv;
            }
          }
          int clipIdx = curClipArr != null ? (int) curClipArr.getInt(r) : 0;
          ChuckArray clipPat = outer.getClipArray(BridgeContract.G_PATTERN, clipIdx);
          ChuckArray clipVel = outer.getClipArray(BridgeContract.G_VELOCITY, clipIdx);
          ChuckArray clipProb = outer.getClipArray(BridgeContract.G_PROBABILITY, clipIdx);
          int len = trkLen != null ? (int) Math.max(1, trkLen.getInt(r)) : BridgeContract.STEPS;
          int step = (int) (currentStep % len);
          int idx = r * BridgeContract.STEPS + step;
          if (algo < 10 && oscType != null && car[u] != null) car[u].index((int) oscType.getInt(r));
          if (clipPat.getInt(idx) == 0) { env[u].keyOff(); continue; }
          if (mute.getInt(r) != 0) { env[u].keyOff(); continue; }
          sDsend[u].gain(dlySnd != null ? (float) dlySnd.getFloat(r) : 0.0f);
          sRsend[u].gain(revSnd != null ? (float) revSnd.getFloat(r) : 0.15f);
          double tf = (gFil.getFloat(r * 2) + sFil.getFloat(idx)) * 10000.0 + 100.0 + lfoF * 5000.0;
          double tq = (gFil.getFloat(r * 2 + 1) + sRes.getFloat(idx)) * 4.0 + 1.0 + lfoQ * 3.0;
          double tp = masterPan + (sPan != null ? sPan.getFloat(idx) : 0.0) + lfoP;
          fil[u].freq((float) Math.max(20.0, Math.min(20000.0, tf)));
          fil[u].Q((float) Math.max(1.0, Math.min(10.0, tq)));
          float hf = hpfFreqArr != null ? (float) hpfFreqArr.getFloat(r) : 20.0f;
          float hr = hpfResArr != null ? (float) hpfResArr.getFloat(r) : 0.0f;
          if (sHpfFreq != null) hf += sHpfFreq.getFloat(idx) * 1000.0f;
          if (sHpfRes != null) hr += sHpfRes.getFloat(idx) * 9.0f;
          hpfArr[u].freq(Math.max(20.0f, hf));
          hpfArr[u].Q(1.0f + Math.max(0.0f, hr) * 9.0f);
          int fm = filterModeArr != null ? (int) filterModeArr.getInt(r) : 2;
          double fmorph = filterMorphArr != null ? filterMorphArr.getFloat(r) : 0.0;
          fil[u].morph((fm == 2) ? fmorph : 0.0);
          pan[u].pan((float) Math.max(-1.0, Math.min(1.0, tp)));

          if (clipProb != null && Math.random() > clipProb.getFloat(idx)) continue;
          if (envArr != null) {
            int eb = (r * BridgeContract.ENV_COUNT + 0) * BridgeContract.ENV_PARAMS;
            env[u].set(envArr.getFloat(eb + 0), envArr.getFloat(eb + 1), envArr.getFloat(eb + 2), envArr.getFloat(eb + 3));
          }
          double gainVal = clipVel.getFloat(idx) * trkLvl.getFloat(r) * 0.8 * Math.max(0.0, 1.0 + lfoV * 0.5);
          double gateSec = (gateArr != null ? gateArr.getFloat(idx) : 0.9) * outer.stepDuration(step % 2).samples() / sampleRate();
          double stepPitchOffset = sPitchArr != null ? sPitchArr.getFloat(idx) * 24.0 : 0.0;

          if (algo >= 10) {
            double f = outer.mtof(((24 - 1) - (r - synthBase)) + 60 + stepPitchOffset) * Math.pow(2.0, lfoPit);
            outer.triggerStkNote(src[u], (float) f, (float) gainVal);
            env[u].gain(0.0f); env[u].keyOn();
            double noteSec = gateSec;
            int rv = u;
            ChuckUGen srcRef = src[u];
            vm.spork(() -> { advance(second(noteSec)); outer.releaseStkNote(srcRef); env[rv].keyOff(); });
          } else if (dx7[u] != null) {
            int midiNote = notePitchArr != null ? (int) notePitchArr.getInt(idx) : 0;
            if (midiNote <= 0) midiNote = ((24 - 1) - (r - synthBase)) + 60;
            double f = outer.mtof(midiNote + stepPitchOffset) * Math.pow(2.0, lfoPit);
            dx7[u].setFreq((float) f);
            int dx7Vel = clipVel != null ? (int) (clipVel.getFloat(idx) * 127) : 100;
            if (dx7Vel <= 0) dx7Vel = 100;
            dx7[u].noteOn(dx7Vel);
            env[u].gain((float) gainVal); env[u].keyOn();
            double noteSec = gateSec;
            int rv = u;
            vm.spork(() -> { advance(second(noteSec)); dx7[rv].noteOff(); env[rv].keyOff(); });
          } else if (arpOn != null && arpOn.getInt(r) == 1) {
            int baseMidi = (int) ((24 - 1) - (r - synthBase)) + 60;
            int v = u;
            vm.spork(() -> run_arp(v, baseMidi, (float) gainVal, car[v], mod[v], env[v]));
          } else {
            double f = outer.mtof(((24 - 1) - (r - synthBase)) + 60 + stepPitchOffset) * Math.pow(2.0, lfoPit);
            float oscAGain = sOscAVol != null ? (float) sOscAVol.getFloat(idx) : 1.0f;
            float oscBGain = sOscBVol != null ? (float) sOscBVol.getFloat(idx) : 1.0f;
            int synthMode = synthModeArr != null ? (int) synthModeArr.getInt(r) : 0;
            if (synthMode == 1) {
              if (car[u] != null) car[u].freq((float) f);
              if (mod[u] != null) {
                double fmR = fmRatio != null ? fmRatio.getFloat(r) : 1.0;
                double fmA = (fmAmt != null ? fmAmt.getFloat(r) : 0.0) * Math.max(0.0, 1.0 + lfoFm * 0.5);
                mod[u].freq((float) (f * fmR));
                car[u].modGain((float) (fmA * 50.0));
                mod[u].gain(1.0f);
              }
            } else if (synthMode >= 2) {
              if (car[u] != null) { car[u].freq((float) f); car[u].gain(oscAGain); }
              if (mod[u] != null) { mod[u].freq((float) f); mod[u].gain(oscBGain); }
            } else {
              if (car[u] != null) { car[u].freq((float) f); car[u].gain(oscAGain); }
              if (mod[u] != null) mod[u].gain(0.0f);
            }
            env[u].gain((float) gainVal); env[u].keyOn();
            double noteSec = gateSec;
            if (vm.getLogLevel() >= 2) vm.print("SYNTH trigger track: " + r + " step: " + (idx % BridgeContract.STEPS) + "\n");
            int[] capturedR = new int[]{r};
            int rv = u;
            vm.spork(() -> { advance(second(noteSec)); env[rv].keyOff(); if (vm.getLogLevel() >= 2) vm.print("SYNTH note end track: " + capturedR[0] + "\n"); });
          }
        }
      }
    }
  }

    private void transport_shred() {
      System.out.println("[transport] entered");
      vm.setGlobalObject(BridgeContract.G_DELAY_IN, new Gain());
      vm.setGlobalObject(BridgeContract.G_REVERB_IN, new Gain());
      vm.setGlobalObject(BridgeContract.G_SYNTH_BUS, new Gain());
      vm.setGlobalObject(BridgeContract.G_AUDIO_BUS, new Gain());
      vm.setGlobalObject(BridgeContract.G_MASTER_TAP, new Gain());
      vm.setGlobalObject(BridgeContract.E_SIDECHAIN, new ChuckEvent());

      // Sync point: ensure buses are registered before any sub-shreds try to fetch them
      advance(samp(1));

      vm.spork(new FxBusShred(vm)::run);
      vm.spork(new MasterShred(vm)::run);
      vm.spork(new ClockShred(vm, this)::run);
      vm.spork(new KitShred(vm, this)::run);
      System.out.println("[transport] sporking synth_shred");
      vm.spork(new SynthShred(vm, this)::run);
      System.out.println("[transport] sporked synth_shred, now sporking sidechain");
      vm.spork(new SidechainShred(vm)::run);
      vm.spork(new AudioShred(vm)::run);
      vm.spork(new ExportShred(vm)::run);

      while (isRunning()) {
        advance(ms(100));
      }
      running = false; // signal sub-shreds to stop
    }
  }
