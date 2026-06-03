package org.chuck.deluge.engine;

import static org.chuck.core.ChuckDSL.*;

import java.lang.reflect.InvocationTargetException;
import org.chuck.audio.*;
import org.chuck.audio.filter.*;
import org.chuck.audio.fx.*;
import org.chuck.audio.util.*;
import org.chuck.core.*;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.engine.dsp.*;
import org.chuck.deluge.firmware.engine.FirmwareAudioEngine;
import org.chuck.deluge.firmware.playback.PlaybackHandler;
import org.chuck.deluge.model.tuning.ScalaScale;

/**
 * Native Java implementation of the Deluge audio engine, written in the ChucK-Java DSL.
 *
 * <p>This class is sporked as a single shred by {@code SequencerLauncher} and internally forks 8
 * sub-shreds (as static inner classes) that collectively implement the full Deluge audio pipeline:
 * kit sample playback, FM/STK synthesis, audio clip recording/playback (LiSa), master export
 * (WvOut2), clock generation, FX buses, and sidechain ducking.
 *
 * <h2>Architecture</h2>
 *
 * All state is read from shared {@link ChuckArray}s registered by {@link BridgeContract}. The main
 * transport loop waits on tick_event broadcast by ClockShred at each step boundary (swing-aware).
 * No polling or shared locks are needed.
 *
 * <h2>Inner Classes (Sub-shreds)</h2>
 *
 * <ol>
 *   <li>{@code FxBusShred} — delay, reverb, chorus UGens
 *   <li>{@code MasterShred} — synth/audio bus processing (HPF, comp, limiter)
 *   <li>{@code ClockShred} — swing-aware step clock broadcasting tick_event
 *   <li>{@code KitShred} — sample playback engine with LFO
 *   <li>{@code SynthShred} — FM/STK synthesis engine with LFO
 *   <li>{@code SidechainShred} — kick-triggered ducking
 *   <li>{@code AudioShred} — LiSa recording/playback
 *   <li>{@code ExportShred} — WvOut2 offline export
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
    double res = vm.getGlobalFloat(BridgeContract.G_STEP_RESOLUTION);
    if (res <= 0.0) res = 0.25; // fallback to 1/16th standard!
    double baseSec = 60.0 / bpm * res;
    if (step % 2 == 0) return second(baseSec * (1.0 + (swing - 0.5) * 0.4));
    return second(baseSec * (1.0 - (swing - 0.5) * 0.4));
  }

  private static volatile ScalaScale activeScalaScale = null;

  public static void setScalaScale(ScalaScale scale) {
    activeScalaScale = scale;
  }

  public static ScalaScale getScalaScale() {
    return activeScalaScale;
  }

  private double mtof(double m) {
    if (activeScalaScale != null) {
      return activeScalaScale.mtof(m);
    }
    return 440.0 * Math.pow(2.0, (m - 69.0) / 12.0);
  }

  /**
   * Convert LFO sync level to rate in Hz. syncLevel 0 = free (use raw Hz), values 1+ = note
   * divisions.
   */
  private static double lfoSyncRate(int syncLevel, double bpm) {
    if (syncLevel <= 0) return -1.0; // caller falls back to raw rate
    // Levels 1-7: 1/1 down to 1/64. Level 3 = quarter note = BPM/60 Hz.
    if (syncLevel <= 7) {
      return bpm / 60.0 * Math.pow(2.0, syncLevel - 3);
    }
    // Levels 8-13: triplet variants (1/2T, 1/4T, 1/8T, 1/16T, 1/32T, 1/64T)
    // Triplet notes have 1.5× the rate of the equivalent sync level (shorter period)
    if (syncLevel <= 13) {
      int tripBase = syncLevel - 7; // 1=1/2T, 2=1/4T, ...
      return bpm / 60.0 * Math.pow(2.0, tripBase - 2) * 1.5;
    }
    // Levels 14-19: dotted variants (1/2D, 1/4D, 1/8D, 1/16D, 1/32D, 1/64D)
    // Dotted notes have 2/3 the rate (longer period by 1.5×)
    if (syncLevel <= 19) {
      int dotBase = syncLevel - 13; // 1=1/2D, 2=1/4D, ...
      return bpm / 60.0 * Math.pow(2.0, dotBase - 2) * (2.0 / 3.0);
    }
    return -1.0;
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
    ChuckArray playMode = (ChuckArray) vm.getGlobalObject(BridgeContract.G_CLIP_PLAY_MODE);
    if (queue == null || currentClip == null) return;
    for (int t = 0; t < BridgeContract.TRACKS; t++) {
      long q = queue.getInt(t);
      if (q >= 0) {
        currentClip.setInt(t, q);
        queue.setInt(t, -1L);
      }
      // Auto-requeue LOOP clips (clipPlayMode == 1) so they continue playing
      long clip = currentClip.getInt(t);
      if (clip >= 0 && playMode != null) {
        long mode = playMode.getInt(t * BridgeContract.MAX_CLIPS_PER_TRACK + (int) clip);
        if (mode == 1) { // LOOP
          queue.setInt(t, clip);
        }
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

    ClockShred(ChuckVM vm, DelugeEngineDSL outer) {
      this.vm = vm;
      this.outer = outer;
    }

    private boolean isRunning() {
      return outer.isRunning();
    }

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
    private final DelugeEngineDSL outer;

    FxBusShred(ChuckVM vm, DelugeEngineDSL outer) {
      this.vm = vm;
      this.outer = outer;
    }

    private boolean isRunning() {
      return outer.isRunning();
    }

    @Override
    public void run() {
      float sr = (float) sampleRate();
      Gain fxIn = new Gain();
      SwitchableAdsr gate = new SwitchableAdsr(sr, vm);
      HPF fxHpf = new HPF(sr);
      fxHpf.freq(80);
      fxIn.chuck(gate).chuck(fxHpf).chuck(dac());

      Echo delay = new Echo();

      long currentReverbModel = vm.getGlobalInt(BridgeContract.G_REVERB_MODEL);
      org.chuck.audio.util.StereoUGen rev = createReverbByIndex((int) currentReverbModel);

      Chorus chorus = new Chorus(sr);
      chorus.setModDepth(0.2f);
      chorus.setModFreq(0.5f);
      Gain modIn = new Gain();
      vm.setGlobalObject("g_mod_in", modIn);
      modIn.chuck(chorus).chuck(fxIn);

      Dyno revComp = new Dyno(sr);
      revComp.compressor();
      revComp.attackTime(0.01f * sr); // default 10ms
      revComp.releaseTime(0.1f * sr); // default 100ms

      ((Gain) vm.getGlobalObject(BridgeContract.G_DELAY_IN)).chuck(delay).chuck(fxIn);
      ((Gain) vm.getGlobalObject(BridgeContract.G_REVERB_IN)).chuck(rev).chuck(revComp).chuck(fxIn);

      fxIn.gain(0.15f);
      advance(ms(100));
      gate.forceMute();
      gate.set(0.01, 0, 1, 0.01);

      ChuckEvent tickEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.E_TICK);
      while (isRunning()) {
        advance(tickEvent);
        if (vm.getGlobalInt(BridgeContract.G_PLAY) != 0) gate.keyOn();
        else gate.keyOff();
        delay.gain((float) vm.getGlobalFloat(BridgeContract.G_DELAY_FB));
        // Delay time: sync-level overrides free-running delay time
        long syncLevel = vm.getGlobalInt(BridgeContract.G_DELAY_SYNC_LEVEL);
        long syncType = vm.getGlobalInt(BridgeContract.G_DELAY_SYNC_TYPE);
        long analog = vm.getGlobalInt(BridgeContract.G_DELAY_ANALOG);
        if (syncLevel > 0) {
          double stepSec = outer.stepDuration(0).samples() / sr;
          double syncFactor = Math.pow(2.0, syncLevel - 1);
          if (syncType == 1) syncFactor *= 1.5;
          delay.delay(Math.min(sr - 2, syncFactor * stepSec * sr));
        } else {
          double dt = vm.getGlobalFloat(BridgeContract.G_DELAY_TIME);
          // Normalize 0-1 → samples; ensure at least 1 sample for non-zero
          if (dt > 0.001) delay.delay(Math.max(1.0, dt * sr));
        }
        // Analog mode: slight random fluctuation to delay time (tape flutter)
        if (analog != 0) delay.delay(delay.delay() * (1.0 + (Math.random() - 0.5) * 0.002));
        // Read reverb extended params
        float revRoom = (float) vm.getGlobalFloat(BridgeContract.G_REVERB_ROOM);
        float revDamp = (float) vm.getGlobalFloat(BridgeContract.G_REVERB_DAMP);
        float revWidth = (float) vm.getGlobalFloat(BridgeContract.G_REVERB_WIDTH);
        float revHpf = (float) vm.getGlobalFloat(BridgeContract.G_REVERB_HPF);
        float revPan = (float) vm.getGlobalFloat(BridgeContract.G_REVERB_PAN);
        fxHpf.freq(Math.max(20.0f, Math.min(20000.0f, 20.0f + revHpf * 19980.0f)));
        float revCompAttack = (float) vm.getGlobalFloat(BridgeContract.G_REVERB_COMP_ATTACK);
        float revCompRelease = (float) vm.getGlobalFloat(BridgeContract.G_REVERB_COMP_RELEASE);
        long revCompSyncLevel = vm.getGlobalInt(BridgeContract.G_REVERB_COMP_SYNC_LEVEL);
        float revCompBlend = (float) vm.getGlobalFloat(BridgeContract.G_REVERB_COMP_BLEND);
        // Map 0-1 → 1-100ms attack, 10-500ms release
        revComp.attackTime((revCompAttack * 0.099f + 0.001f) * sr);
        revComp.releaseTime((revCompRelease * 0.49f + 0.01f) * sr);
        revComp.thresh(
            Math.max(
                0.01f, 1.0f - revCompBlend)); // blend=0 → threshold high, blend=1 → threshold low
        // Rev comp sync level: scale release time by tempo division
        if (revCompSyncLevel > 0) {
          double stepSec = outer.stepDuration(0).samples() / sr;
          double syncRelease = stepSec * Math.pow(2.0, revCompSyncLevel - 1);
          revComp.releaseTime(syncRelease * sr);
        }
        rev.gain(1.0f + (revPan - 0.5f) * 0.5f);
        // Song-level reverb/delay amounts — scale the send bus gains
        double spReverb = vm.getGlobalFloat(BridgeContract.G_SP_REVERB_AMOUNT);
        double spDelayRate = vm.getGlobalFloat(BridgeContract.G_SP_DELAY_RATE);
        double spDelayFeedback = vm.getGlobalFloat(BridgeContract.G_SP_DELAY_FEEDBACK);
        // Use song-level reverb as additional gain on the reverb input bus
        if (spReverb > 0.001) {
          Gain revIn = (Gain) vm.getGlobalObject(BridgeContract.G_REVERB_IN);
          revIn.gain((float) Math.max(0.0, Math.min(1.0, spReverb)));
        }
        // Song-level delay rate scales the delay time (when not in sync mode)
        if (spDelayRate > 0.001 && syncLevel == 0) {
          double baseDt = vm.getGlobalFloat(BridgeContract.G_DELAY_TIME);
          if (baseDt > 0.001) delay.delay(Math.max(1.0, baseDt * sr * spDelayRate));
        }
        // Song-level delay feedback scales the feedback gain
        if (spDelayFeedback > 0.001) {
          delay.gain(delay.gain() * (float) Math.min(1.0, spDelayFeedback));
        }
        // Song-level modFX params — apply to global Chorus UGen
        double spModRate = vm.getGlobalFloat(BridgeContract.G_SP_MOD_FX_RATE);
        double spModDepth = vm.getGlobalFloat(BridgeContract.G_SP_MOD_FX_DEPTH);
        double spModOffset = vm.getGlobalFloat(BridgeContract.G_SP_MOD_FX_OFFSET);
        double spModFeedback = vm.getGlobalFloat(BridgeContract.G_SP_MOD_FX_FEEDBACK);
        chorus.setModFreq((float) Math.max(0.01, spModRate * 8.0 + 0.1));
        chorus.setModDepth((float) Math.min(1.0, spModDepth));
        // Mod offset is not directly exposed on Chorus — skip
        // Mod feedback is not directly exposed on Chorus — skip
        if (rev instanceof FreeVerb fv) {
          fv.roomSize(revRoom);
          fv.damp(revDamp);
        } else if (rev instanceof org.chuck.deluge.engine.dsp.FirmwareReverb frv) {
          frv.roomSize(revRoom);
          frv.damp(revDamp);
          frv.width(revWidth);
        } else if (rev instanceof JCRev jcr) {
          jcr.mix(revRoom);
        } else if (rev instanceof MVerb mvb) {
          mvb.setParameter(org.chuck.audio.fx.MVerb.DECAY, revRoom);
          mvb.setParameter(org.chuck.audio.fx.MVerb.DAMPINGFREQ, 1.0 - revDamp);
          mvb.setParameter(org.chuck.audio.fx.MVerb.SIZE, Math.min(1.0, revWidth + 0.5));
        } else if (rev instanceof org.chuck.audio.fx.RingsReverb rings) {
          rings.setBrightness(1.0f - revDamp);
          rings.setStructure(revRoom);
          rings.setPosition(revWidth);
          rings.setDamping(revDamp * 0.3f);
          rings.setExcitation((float) vm.getGlobalFloat(BridgeContract.G_REVERB_EXCITATION));
          rings.setMode((int) vm.getGlobalInt(BridgeContract.G_REVERB_MODE));
        }

        // Runtime reverb model hot-swap
        long newModel = vm.getGlobalInt(BridgeContract.G_REVERB_MODEL);
        if (newModel != currentReverbModel) {
          currentReverbModel = newModel;
          Gain revIn = (Gain) vm.getGlobalObject(BridgeContract.G_REVERB_IN);
          revIn.unchuck(rev);
          rev.unchuck(revComp);
          rev = createReverbByIndex((int) newModel);
          revIn.chuck(rev).chuck(revComp).chuck(fxIn);
        }
      }
    }

    private org.chuck.audio.util.StereoUGen createReverbByIndex(int idx) {
      if (vm.getGlobalInt(BridgeContract.G_HI_FI_MODE) != 0 && idx == 0) {
        return new org.chuck.deluge.engine.dsp.FirmwareReverb();
      }
      return switch (idx) {
        case 0 -> new FreeVerb();
        case 1 -> new JCRev();
        case 2 -> new MVerb();
        case 3 -> new org.chuck.audio.fx.ProceduralReverb();
        case 4 -> new org.chuck.audio.fx.RingsReverb();
        default -> new JCRev();
      };
    }
  }

  // ── PureFirmwareMasterShred ──────────────────────────────────
  /**
   * High-fidelity 'Pure Java' path. Bypasses all ChucK synthesis and sequencing. Runs the ported
   * C++ logic directly.
   */
  public static final class PureFirmwareMasterShred implements Runnable {
    private final ChuckVM vm;
    private final FirmwareAudioEngine fwEngine = new FirmwareAudioEngine();
    private final PlaybackHandler playbackHandler = new PlaybackHandler();

    public PureFirmwareMasterShred(ChuckVM vm) {
      this.vm = vm;
    }

    @Override
    public void run() {
      System.out.println("[PureFirmwareMasterShred] Active - Hi-Fi Mode");

      // Register engine and handler in bridge for UI access
      vm.setGlobalObject(BridgeContract.G_FIRMWARE_ENGINE, fwEngine);
      vm.setGlobalObject(BridgeContract.G_PLAYBACK_HANDLER, playbackHandler);

      // 1. Initial State
      playbackHandler.start();

      // 2. Output Bridge
      FirmwareEngineUGen outputBridge = new FirmwareEngineUGen(vm, fwEngine, playbackHandler);
      outputBridge.chuck(vm.dac);

      // 3. State Synchronization Loop
      while (true) {
        float bpm = (float) vm.getGlobalFloat(BridgeContract.G_BPM);
        outputBridge.updateBpm(bpm);

        // ── Real-Time Parameter Sync ──
        for (org.chuck.deluge.firmware.engine.GlobalEffectable sound : fwEngine.sounds) {
          // 1. Synth-specific params
          if (sound instanceof org.chuck.deluge.firmware.engine.FirmwareSound fs) {
            fs.paramNeutralValues[org.chuck.deluge.firmware.modulation.params.Param.LOCAL_VOLUME] =
                (int) (vm.getGlobalFloat(BridgeContract.G_SP_VOLUME) * 2147483647.0);
            fs.paramNeutralValues[org.chuck.deluge.firmware.modulation.params.Param.LOCAL_PAN] =
                (int) (vm.getGlobalFloat(BridgeContract.G_SP_PAN) * 2147483647.0);
            fs.paramNeutralValues[
                    org.chuck.deluge.firmware.modulation.params.Param.LOCAL_LPF_FREQ] =
                (int) (vm.getGlobalFloat(BridgeContract.G_SP_LPF_FREQ) / 20000.0f * 2147483647.0);
            fs.paramNeutralValues[
                    org.chuck.deluge.firmware.modulation.params.Param.LOCAL_LPF_RESONANCE] =
                (int) (vm.getGlobalFloat(BridgeContract.G_SP_LPF_RES) * 2147483647.0);
            fs.paramNeutralValues[
                    org.chuck.deluge.firmware.modulation.params.Param.GLOBAL_REVERB_AMOUNT] =
                (int) (vm.getGlobalFloat(BridgeContract.G_SP_REVERB_AMOUNT) * 2147483647.0);
            fs.paramNeutralValues[
                    org.chuck.deluge.firmware.modulation.params.Param.GLOBAL_DELAY_RATE] =
                (int) (vm.getGlobalFloat(BridgeContract.G_SP_DELAY_RATE) * 2147483647.0);
            fs.paramNeutralValues[
                    org.chuck.deluge.firmware.modulation.params.Param.UNPATCHED_STUTTER_RATE] =
                (int) (vm.getGlobalFloat(BridgeContract.G_SP_STUTTER_RATE) * 2147483647.0);
          }

          // 2. Global Track FX (Filters, Delay, Reverb)
          sound.filterSet.setConfig(
              (int) (vm.getGlobalFloat(BridgeContract.G_SP_LPF_FREQ) / 20000.0f * 2147483647.0),
              (int) (vm.getGlobalFloat(BridgeContract.G_SP_LPF_RES) * 536870896.0),
              org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.TRANSISTOR_12DB,
              0, // morph
              (int) (vm.getGlobalFloat(BridgeContract.G_SP_HPF_FREQ) / 20000.0f * 2147483647.0),
              (int) (vm.getGlobalFloat(BridgeContract.G_SP_HPF_RES) * 536870896.0),
              org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.OFF,
              0, // morph
              1 << 28, // gain
              org.chuck.deluge.firmware.dsp.filter.FilterRoute.HIGH_TO_LOW);
        }

        advance(second(0.05));
      }
    }
  }

  // ── MasterShred ────────────────────────────────────────────────
  static final class MasterShred implements Runnable {
    private final ChuckVM vm;
    private final DelugeEngineDSL outer;

    MasterShred(ChuckVM vm, DelugeEngineDSL outer) {
      this.vm = vm;
      this.outer = outer;
    }

    private boolean isRunning() {
      return outer.isRunning();
    }

    // ── Hardware character UGens (created once, controlled by globals) ──
    static final class BitCrunchUGen extends org.chuck.audio.ChuckUGen {
      private boolean enabled = false;

      void setEnabled(boolean e) {
        this.enabled = e;
      }

      @Override
      protected float compute(float input, long systemTime) {
        if (!enabled) return input;
        // 14-bit truncation with TPDF dither: 2^14 = 16384 levels
        float scale = 8192.0f;
        float dither = (float) (Math.random() - Math.random()); // TPDF: -1..+1
        float val = input * scale + dither;
        return (float) Math.round(val) / scale;
      }
    }

    /**
     * Always-on tanh soft-clip emulating the hardware's analog summing bus. Uses a gentle pre-gain
     * (1.2×) so only peaks above ~0.83 get rounded by 1-3 dB. Compensates output gain to prevent
     * overall level drop: output = tanh(input * pre) / pre. No configuration — always active as
     * hardware character.
     */
    static final class SummingTanhUGen extends org.chuck.audio.ChuckUGen {
      private static final float PRE_GAIN = 1.2f;

      @Override
      protected float compute(float input, long systemTime) {
        return (float) Math.tanh(input * PRE_GAIN) / PRE_GAIN;
      }
    }

    @Override
    public void run() {
      float sr = (float) sampleRate();
      HPF hpf = new HPF(sr);
      SwitchableCompressor comp = new SwitchableCompressor(sr, vm);
      EQShelving bassEq = new EQShelving(sr);
      EQShelving trebleEq = new EQShelving(sr);
      Distortion masterSat = new Distortion();
      masterSat.gain(1.0f); // always 1 — bypass handled via mode/drive
      BitCrunchUGen bitCrunch = new BitCrunchUGen();
      SummingTanhUGen summingTanh = new SummingTanhUGen();
      Gain masterVol = new Gain();
      Gain masterTap = (Gain) vm.getGlobalObject(BridgeContract.G_MASTER_TAP);

      // ── Always-on summing tanh + compressor-internal tanh ─────────────────────
      // 1) Always-on SummingTanhUGen: lightweight tanh soft-clip with 1.2× pre-gain
      //    emulating the hardware's analog summing bus. Rounds peaks by 1-3 dB.
      // 2) Compressor's internal tanh: firmare's RMSFeedbackCompressor always applies
      //    getTanHAntialiased to its output.
      // 3) User-toggleable masterSat: per-clip saturation controlled by clippingAmount.
      // Our chain: HPF → comp → summingTanh → masterSat → EQ → masterVol → bitCrunch → masterTap →
      // dac()
      // NOTE: EQ is per-track in firmware (ModControllableAudio::doEQ), not song-level.
      // We keep it here as a convenience — the firmware's equivalent per-track EQ runs for each
      // sound.
      ((Gain) vm.getGlobalObject(BridgeContract.G_SYNTH_BUS))
          .chuck(hpf)
          .chuck(comp)
          .chuck(summingTanh)
          .chuck(masterSat)
          .chuck(trebleEq)
          .chuck(bassEq)
          .chuck(masterVol)
          .chuck(bitCrunch)
          .chuck(masterTap)
          .chuck(dac());
      ((Gain) vm.getGlobalObject(BridgeContract.G_AUDIO_BUS))
          .chuck(hpf)
          .chuck(comp)
          .chuck(summingTanh)
          .chuck(masterSat)
          .chuck(trebleEq)
          .chuck(bassEq)
          .chuck(masterVol)
          .chuck(bitCrunch)
          .chuck(masterTap)
          .chuck(dac());
      ((Gain) vm.getGlobalObject(BridgeContract.G_KIT_BUS))
          .chuck(hpf)
          .chuck(comp)
          .chuck(summingTanh)
          .chuck(masterSat)
          .chuck(trebleEq)
          .chuck(bassEq)
          .chuck(masterVol)
          .chuck(bitCrunch)
          .chuck(masterTap)
          .chuck(dac());
      hpf.freq(20);
      bassEq.type(EQShelving.LOW_SHELF);
      bassEq.freq(200);
      trebleEq.type(EQShelving.HIGH_SHELF);
      trebleEq.freq(2000);
      // Processing order matches firmware: treble first (highpass), then bass (lowpass).
      // In firmware's doEQ(): treble → bass, both applied to the same signal in series.
      comp.compressor();
      ChuckEvent tickEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.E_TICK);
      while (isRunning()) {
        advance(tickEvent);

        // ── Compressor (RMSFeedbackCompressor-correct formulas) ──
        // G_SP_COMPRESSOR_THRESHOLD overrides the knob-derived formula when non-zero.
        // The firmware maps 0x00000000 to "no explicit threshold" (use the master comp knob).
        // Our hex mapping gives 0.0 for that case, and (0, 1.0] for actual values.
        double songThreshold = vm.getGlobalFloat(BridgeContract.G_SP_COMPRESSOR_THRESHOLD);
        double compKnob = vm.getGlobalFloat(BridgeContract.G_MASTER_COMP);
        // RMSFeedbackCompressor::setThreshold: threshold = 1 - 0.8 * (knob / ONE_Q31)
        double th = (songThreshold > 0.001) ? Math.min(0.99, songThreshold) : 1.0 - 0.8 * compKnob;
        comp.threshold((float) Math.max(0.01, th));

        float compAttack = (float) vm.getGlobalFloat(BridgeContract.G_MASTER_COMP_ATTACK);
        float compRelease = (float) vm.getGlobalFloat(BridgeContract.G_MASTER_COMP_RELEASE);
        float compRatio = (float) vm.getGlobalFloat(BridgeContract.G_MASTER_COMP_RATIO);

        // RMSFeedbackCompressor::setAttack: attackMS = 0.5 + (exp(2*knob) - 1) * 10
        double attackMS = 0.5 + (Math.exp(2.0 * compAttack) - 1.0) * 10.0;
        double attackSamples = attackMS * sr / 1000.0;
        comp.attackTime((float) attackSamples);

        // RMSFeedbackCompressor::setRelease: releaseMS = 50 + (exp(2*knob) - 1) * 50
        double releaseMS = 50.0 + (Math.exp(2.0 * compRelease) - 1.0) * 50.0;
        double releaseSamples = releaseMS * sr / 1000.0;
        comp.releaseTime((float) releaseSamples);

        // RMSFeedbackCompressor::setRatio: fraction = 0.5 + knob/2, ratio = 1/(1-fraction) → range
        // 2..256
        double fraction = 0.5 + compRatio / 2.0;
        double ratio = 1.0 / Math.max(0.0039, 1.0 - fraction); // avoid div-by-zero, min ratio ~256
        comp.ratio((float) Math.max(2.0, Math.min(256.0, ratio)));

        // ── Master compressor dry/wet blend ──
        float compBlend = (float) vm.getGlobalFloat(BridgeContract.G_MASTER_COMP_BLEND);
        comp.dryWet(compBlend);

        // ── Master volume ──
        double spVol = vm.getGlobalFloat(BridgeContract.G_SP_VOLUME);
        if (spVol < 0.001) spVol = 1.0;
        double mv = vm.getGlobalFloat(BridgeContract.G_MASTER_VOL);
        masterVol.gain((float) (mv * spVol));

        // ── Hardware character: master saturation ──
        // Use Distortion in FOLDBACK mode with very high threshold as a bypass
        // (prevents double-gain multiplication issue with ChuckUGen.gain=0).
        float masterSatOn = (float) vm.getGlobalFloat(BridgeContract.G_MASTER_SATURATION);
        if (masterSatOn > 0.5f) {
          masterSat.mode(Distortion.MODE_OVERDRIVE);
          masterSat.drive(2.5f);
          masterSat.gain(1.0f);
        } else {
          masterSat.mode(Distortion.MODE_FOLDBACK);
          masterSat.threshold(1000.0f);
          masterSat.drive(1.0f);
          masterSat.gain(1.0f);
        }

        // ── Hardware character: 14-bit crunch ──
        float bitCrunchOn = (float) vm.getGlobalFloat(BridgeContract.G_BIT_CRUNCH);
        bitCrunch.setEnabled(bitCrunchOn > 0.5f);

        // ── LPF/HPF via song params (firmware's GlobalEffectable::setupFilterSetConfig) ──
        double spLpfFreq = vm.getGlobalFloat(BridgeContract.G_SP_LPF_FREQ);
        double spLpfRes = vm.getGlobalFloat(BridgeContract.G_SP_LPF_RES);
        double spHpfFreq = vm.getGlobalFloat(BridgeContract.G_SP_HPF_FREQ);
        double spHpfRes = vm.getGlobalFloat(BridgeContract.G_SP_HPF_RES);
        double spLpfMorph = vm.getGlobalFloat(BridgeContract.G_SP_LPF_MORPH);
        double spHpfMorph = vm.getGlobalFloat(BridgeContract.G_SP_HPF_MORPH);

        // Firmware's filterSet.setConfig handles both LPF+HPF in one combined filter.
        // Our chain uses a single HPF UGen. Map song params to it.
        // spHpfFreq/spLpfFreq are already in Hz (20..20000) from hexToHz mapping.
        // spHpfFreq=20 (=hex 0x80000000) → no HPF filtering (firmware's neutral position).
        // spLpfRes/spHpfRes are 0..1 normalized (from hexToFloat→Math.abs).
        // spLpfMorph/spHpfMorph are 0..1 normalized.
        double hpCutoff = 20.0;
        if (spHpfFreq > 20.1 || spHpfMorph > 0.01) {
          double hpMorphOffset = spHpfMorph * 10000.0;
          hpCutoff = Math.max(20.0, Math.min(20000.0, spHpfFreq + hpMorphOffset));
        }
        // Combined: HPF dominates low end, LPF complement is effectively a high-pass too
        hpf.freq((float) Math.max(20.0, Math.min(20000.0, hpCutoff)));

        // Q mapping: firmware res is 0..1, mapped to ~0.3..28 Q with squared taper
        double hpQ = 0.3 + spHpfRes * spHpfRes * 27.7;
        hpf.Q((float) Math.max(0.3, Math.min(28.0, hpQ)));

        // ── SongParams EQ ──
        // Firmware ModControllableAudio::getAmount(): quadratic mapping producing smooth taper.
        //   positive = (param >> 1) + 0x40000000  → in 0..1: pos = param/2 + 0.5, range 0.5..1.0
        //   amount_squared = (positive * positive) >> 31  → normalized square
        //   amount = amount_squared - 0x20000000  → center at zero
        // Equivalent float: pos = param * 0.5 + 0.5, sq = pos * pos, amount = sq * 2.0 - 1.0
        // This gives: param=0 → amount=-0.5, param=0.5 → amount=0, param=1 → amount=+0.5
        double spEqBass = vm.getGlobalFloat(BridgeContract.G_SP_EQ_BASS);
        double spEqTreble = vm.getGlobalFloat(BridgeContract.G_SP_EQ_TREBLE);
        double spEqBassFreq = vm.getGlobalFloat(BridgeContract.G_SP_EQ_BASS_FREQ);
        double spEqTrebleFreq = vm.getGlobalFloat(BridgeContract.G_SP_EQ_TREBLE_FREQ);

        double bassPos = spEqBass * 0.5 + 0.5;
        double treblePos = spEqTreble * 0.5 + 0.5;
        double eqBassAmount = bassPos * bassPos * 2.0 - 1.0; // -0.5..+0.5, quadratic taper
        double eqTrebleAmount = treblePos * treblePos * 2.0 - 1.0;

        // EQShelving.shelfGain is linear 0..2 where 1.0=bypass.
        // EQShelving internally multiplies (shelfGain-1.0)*8.0 matching firmware's ×8 mixing.
        // The firmware's doEQ() also applies ×8: *inputL += (multiply_32x32_rshift32(signal,
        // amount) << 3)
        // Our eqBassAmount=±0.5 → shelfGain=1.5 or 0.5 → mix = (1.5-1)*8 = +4 or (0.5-1)*8 = -4
        bassEq.shelfGain((float) Math.max(0.0, Math.min(2.0, 1.0 + eqBassAmount)));
        bassEq.freq((float) Math.max(20.0, Math.min(20000.0, spEqBassFreq * 19980.0 + 20.0)));
        trebleEq.shelfGain((float) Math.max(0.0, Math.min(2.0, 1.0 + eqTrebleAmount)));
        trebleEq.freq((float) Math.max(20.0, Math.min(20000.0, spEqTrebleFreq * 19980.0 + 20.0)));
      }
    }
  }

  // ── SidechainShred ────────────────────────────────────────────
  static final class SidechainShred implements Runnable {
    private final ChuckVM vm;
    private final DelugeEngineDSL outer;

    SidechainShred(ChuckVM vm, DelugeEngineDSL outer) {
      this.vm = vm;
      this.outer = outer;
    }

    private boolean isRunning() {
      return outer.isRunning();
    }

    @Override
    public void run() {
      ChuckEvent sc = (ChuckEvent) vm.getGlobalObject(BridgeContract.E_SIDECHAIN);
      Gain synthBus = (Gain) vm.getGlobalObject(BridgeContract.G_SYNTH_BUS);
      float normalGain = 1.0f;
      float duckedGain = 0.15f;
      float prevSidechainShape = -1.0f; // force initial update
      int scCount = 0;
      while (isRunning()) {
        advance(sc);
        if (vm.getLogLevel() >= 1) vm.print("[sidechain] DUCK #" + (++scCount) + "\n");
        // Read attack/release from globals (range 0-1 mapped to meaningful ms)
        float duckMs =
            10.0f + (float) vm.getGlobalFloat(BridgeContract.G_SIDECHAIN_ATTACK) * 200.0f;
        float releaseMs =
            20.0f + (float) vm.getGlobalFloat(BridgeContract.G_SIDECHAIN_RELEASE) * 500.0f;
        long scSyncLevel = vm.getGlobalInt(BridgeContract.G_SIDECHAIN_SYNC_LEVEL);
        long scSyncType = vm.getGlobalInt(BridgeContract.G_SIDECHAIN_SYNC_TYPE);
        // If syncLevel > 0, sync duck duration to step grid
        if (scSyncLevel > 0 && vm.getGlobalInt(BridgeContract.G_PLAY) != 0) {
          double bpm = vm.getGlobalFloat(BridgeContract.G_BPM);
          if (bpm < 1.0) bpm = 120.0;
          double stepSec = 60.0 / bpm / 4.0;
          double div = Math.pow(2.0, scSyncLevel - 1);
          // scSyncType: 0=even (×1.0), 1=dotted (×1.5)
          double dotted = (scSyncType == 1) ? 1.5 : 1.0;
          duckMs = (float) (stepSec / div * 1000.0 * dotted);
          releaseMs = duckMs * 2.0f;
          duckedGain = 0.05f;
        }
        // Read song-level sidechain shape and update duck/release behaviour
        float sidechainShape = (float) vm.getGlobalFloat(BridgeContract.G_SP_SIDECHAIN_SHAPE);
        if (sidechainShape != prevSidechainShape) {
          prevSidechainShape = sidechainShape;
          if (vm.getLogLevel() >= 1) vm.print("[sidechain] shape=" + sidechainShape + "\n");
        }
        // shape=0: hard square (full gain reduction), shape=1: smooth exponential curve
        synthBus.gain(Math.min(synthBus.gain(), duckedGain * (1.0f - sidechainShape * 0.5f)));
        advance(ms(duckMs));
        int steps = 8;
        if (sidechainShape > 0.01f) {
          // Exponential release: quick initial recovery, slower at the end
          for (int i = 0; i < steps; i++) {
            float t = (float) (i + 1) / steps;
            // Exponential curve: e^(shape * t) - 1 / (e^shape - 1)
            float expCurve =
                (float) ((Math.exp(sidechainShape * t) - 1.0) / (Math.exp(sidechainShape) - 1.0));
            synthBus.gain(duckedGain + (normalGain - duckedGain) * expCurve);
            advance(ms(releaseMs / steps));
          }
        } else {
          float stepGain = (normalGain - duckedGain) / steps;
          for (int i = 0; i < steps; i++) {
            synthBus.gain(duckedGain + stepGain * (i + 1));
            advance(ms(releaseMs / steps));
          }
        }
        synthBus.gain(normalGain);
        if (vm.getLogLevel() >= 1) vm.print("[sidechain] RELEASE scBus=" + synthBus.gain() + "\n");
      }
    }
  }

  // ── AudioShred (LiSa) ─────────────────────────────────────────
  static final class AudioShred implements Runnable {
    private final ChuckVM vm;
    private final DelugeEngineDSL outer;

    // Threshold recording state per track
    private static final int THR_IDLE = 0;
    private static final int THR_RECORDING = 1;
    private static final int THR_HOLD = 2;
    private static final int RMS_WINDOW = 256;
    private static final int HOLD_SAMPLES = (int) (0.5 * 44100);

    private final int[] thrState = new int[BridgeContract.TRACKS];
    private final double[] rmsAccum = new double[BridgeContract.TRACKS];
    private final int[] rmsCount = new int[BridgeContract.TRACKS];
    private final int[] holdCounter = new int[BridgeContract.TRACKS];

    AudioShred(ChuckVM vm, DelugeEngineDSL outer) {
      this.vm = vm;
      this.outer = outer;
    }

    private boolean isRunning() {
      return outer.isRunning();
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
            lisaPan[r]
                .chuck(lisaDsend[r])
                .chuck((ChuckUGen) vm.getGlobalObject(BridgeContract.G_DELAY_IN));
            lisaPan[r]
                .chuck(lisaRsend[r])
                .chuck((ChuckUGen) vm.getGlobalObject(BridgeContract.G_REVERB_IN));

            lisa[r].voiceGain(0, 1.0f);
            lisa[r].voicePan(0, 0.0f);
            lisa[r].rate(0, 1.0f);
            lisa[r].loop(0, 1);
            lisaDsend[r].gain(0.0f);
            lisaRsend[r].gain(0.15f);

            // Load pre-existing audio clip file into LiSa buffer if configured
            Object filePathObj = vm.getGlobalObject("g_audio_file_path_" + r);
            if (filePathObj instanceof String filePath && !filePath.isEmpty()) {
              java.io.File wavFile = new java.io.File(filePath);
              if (!wavFile.exists()) {
                java.io.File libraryDir =
                    org.chuck.deluge.project.PreferencesManager.getLibraryDir();
                wavFile = new java.io.File(libraryDir, filePath);
              }
              if (wavFile.exists()) {
                try {
                  org.chuck.audio.util.WavReader.WavData wavData =
                      org.chuck.audio.util.WavReader.read(wavFile);
                  float[] mono = new float[wavData.frameCount()];
                  for (int s = 0; s < wavData.frameCount(); s++) {
                    mono[s] = (wavData.channels[0][s] + wavData.channels[1][s]) * 0.5f;
                  }
                  lisa[r].loadSamples(mono);
                  vm.print(
                      "[audio] loaded "
                          + mono.length
                          + " samples from "
                          + wavFile.getAbsolutePath()
                          + "\n");
                } catch (Exception e) {
                  vm.print(
                      "[audio] ERROR loading "
                          + wavFile.getAbsolutePath()
                          + " \u2014 "
                          + e.getMessage()
                          + "\n");
                }
              } else {
                vm.print("[audio] WARN: audio clip file not found: " + filePath + "\n");
              }
            }
          }

          ChuckArray dlySnd = (ChuckArray) vm.getGlobalObject(BridgeContract.G_DELAY_SEND);
          ChuckArray revSnd = (ChuckArray) vm.getGlobalObject(BridgeContract.G_REVERB_SEND);
          lisaDsend[r].gain(dlySnd != null ? (float) dlySnd.getFloat(r) : 0.0f);
          lisaRsend[r].gain(revSnd != null ? (float) revSnd.getFloat(r) : 0.15f);

          int rec = audioRec != null ? (int) audioRec.getInt(r) : 0;
          // Threshold-gated recording
          if (rec == 0) {
            // Not armed: reset state and stop recording
            thrState[r] = THR_IDLE;
            rmsAccum[r] = 0.0;
            rmsCount[r] = 0;
            holdCounter[r] = 0;
            lisa[r].record(0);
          } else {
            ChuckArray thrArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_AUDIO_THRESHOLD);
            int thrMode = thrArr != null ? (int) thrArr.getInt(r) : 0;
            if (thrMode == 0) {
              // No threshold: record directly
              thrState[r] = THR_IDLE;
              lisa[r].record(1);
            } else {
              // Compute RMS of ADC input over window
              float adcSample = adc.last();
              rmsAccum[r] += (double) adcSample * adcSample;
              rmsCount[r]++;
              boolean aboveThreshold = false;
              if (rmsCount[r] >= RMS_WINDOW) {
                double rms = Math.sqrt(rmsAccum[r] / RMS_WINDOW);
                rmsAccum[r] = 0.0;
                rmsCount[r] = 0;
                ChuckArray thrLvlArr =
                    (ChuckArray) vm.getGlobalObject(BridgeContract.G_AUDIO_THRESHOLD_LEVEL);
                double thrLevel = thrLvlArr != null ? (double) thrLvlArr.getFloat(r) : 0.0;
                // Map threshold mode to sensible RMS levels when level is 0
                if (thrLevel <= 0.0) {
                  thrLevel =
                      switch (thrMode) {
                        case 1 -> 0.01; // LOW
                        case 2 -> 0.05; // MEDIUM
                        case 3 -> 0.10; // HIGH
                        default -> 0.0;
                      };
                }
                aboveThreshold = rms > thrLevel;
              }
              // State machine: IDLE → RECORDING → HOLD → IDLE
              switch (thrState[r]) {
                case THR_IDLE:
                  lisa[r].record(0);
                  if (aboveThreshold) {
                    thrState[r] = THR_RECORDING;
                    lisa[r].record(1);
                  }
                  break;
                case THR_RECORDING:
                  lisa[r].record(1);
                  if (!aboveThreshold) {
                    thrState[r] = THR_HOLD;
                    holdCounter[r] = 0;
                  }
                  break;
                case THR_HOLD:
                  lisa[r].record(1);
                  if (aboveThreshold) {
                    thrState[r] = THR_RECORDING;
                  } else if (++holdCounter[r] >= HOLD_SAMPLES / RMS_WINDOW) {
                    thrState[r] = THR_IDLE;
                    lisa[r].record(0);
                  }
                  break;
              }
            }
          }

          int play = audioPlay != null ? (int) audioPlay.getInt(r) : 0;
          lisa[r].play(0, play);

          int loop = audioLoop != null ? (int) audioLoop.getInt(r) : 1;
          lisa[r].loop(0, loop);

          float rate = audioRate != null ? (float) audioRate.getFloat(r) : 1.0f;
          lisa[r].rate(0, rate);

          // Apply per-track audio clip sample position (loop sub-region)
          double clipStart = vm.getGlobalFloat("g_audio_clip_start_" + r);
          double clipEnd = vm.getGlobalFloat("g_audio_clip_end_" + r);
          if (clipEnd > clipStart) {
            lisa[r].loopStart(0, clipStart);
            lisa[r].loopEnd(0, clipEnd);
          }
        }
      }
    }
  }

  // ── ExportShred (WvOut2) ──────────────────────────────────────
  static final class ExportShred implements Runnable {
    private final ChuckVM vm;
    private final DelugeEngineDSL outer;

    ExportShred(ChuckVM vm, DelugeEngineDSL outer) {
      this.vm = vm;
      this.outer = outer;
    }

    private boolean isRunning() {
      return outer.isRunning();
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
    private final double[] lfoSampleHoldKit =
        new double[BridgeContract.LFO_COUNT]; // S&H: held value, updated when phase wraps
    private final double[] lfoRandWalkKit =
        new double[BridgeContract.LFO_COUNT]; // RANDOM_WALK: drifting value
    private final double[] lfoWarbler1Kit =
        new double[BridgeContract.LFO_COUNT]; // WARBLER: first-order smoothed
    private final double[] lfoWarbler2Kit =
        new double[BridgeContract.LFO_COUNT]; // WARBLER: second-order smoothed
    private final int[] triggerGeneration = new int[BridgeContract.TRACKS];

    KitShred(ChuckVM vm, DelugeEngineDSL outer) {
      this.vm = vm;
      this.outer = outer;
    }

    private boolean isRunning() {
      return outer.isRunning();
    }

    private void loadSndBuf(SndBuf buf, String path) {
      try {
        buf.rate(0);
        buf.read(path);
        buf.rate(0);
        long samples = buf.samples();
        if (samples > 0) buf.pos(0);
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
        if (f.exists()) {
          loadSndBuf(kit[i], f.getAbsolutePath());
          continue;
        }
        java.io.File rel = new java.io.File(libraryDir, path);
        if (rel.exists()) {
          loadSndBuf(kit[i], rel.getAbsolutePath());
          continue;
        }
        java.net.URL resourceUrl = getClass().getClassLoader().getResource(path);
        if (resourceUrl != null) {
          String decodedPath =
              java.net.URLDecoder.decode(
                  resourceUrl.getPath(), java.nio.charset.StandardCharsets.UTF_8);
          // On Windows, URL.getPath() returns "/C:/..." — strip leading "/" for File
          if (java.io.File.separatorChar == '\\'
              && decodedPath.length() > 2
              && decodedPath.charAt(0) == '/'
              && decodedPath.charAt(2) == ':') decodedPath = decodedPath.substring(1);
          java.io.File classpathFile = new java.io.File(decodedPath);
          if (classpathFile.exists()) {
            loadSndBuf(kit[i], classpathFile.getAbsolutePath());
            continue;
          }
        }
        vm.print(
            "[kit] WARN: sample not found: " + path + " (tried " + rel.getAbsolutePath() + ")\n");
      }
    }

    private void kit_preview_shred(SndBuf[] kit, SwitchableAdsr[][] kitEnv) {
      float sr = (float) sampleRate();
      vm.print("[kit_preview] shred started, kit.length=" + kit.length + "\n");
      SndBuf previewBuf = new SndBuf();
      DelugeAdsr previewEnv =
          new DelugeAdsr(); // Keep legacy for preview for now or switch if needed
      // Actually switchable is better
      SwitchableAdsr sPreviewEnv = new SwitchableAdsr(sr, vm);
      previewBuf.rate(0);
      previewEnv.forceMute();
      previewEnv.set(0.001, 0.0, 1.0, 0.05);
      previewBuf
          .chuck(previewEnv)
          .chuck((ChuckUGen) vm.getGlobalObject(BridgeContract.G_MASTER_TAP))
          .chuck(dac());
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
            if (f.exists()) {
              previewBuf.setRead(f.getAbsolutePath());
            } else {
              java.io.File rel = new java.io.File(libraryDir, path);
              if (rel.exists()) {
                previewBuf.setRead(rel.getAbsolutePath());
              } else {
                java.net.URL url = getClass().getClassLoader().getResource(path);
                if (url != null) {
                  String decoded =
                      java.net.URLDecoder.decode(
                          url.getPath(), java.nio.charset.StandardCharsets.UTF_8);
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
      Gain kitBus = (Gain) vm.getGlobalObject(BridgeContract.G_KIT_BUS);
      master.chuck(hpf).chuck(limit).chuck(kitBus);
      hpf.freq(20);
      limit.limiter();
      ChuckEvent loadEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.G_LOAD_TRIGGER);
      advance(loadEvent);
      final SndBuf[][] kitHolder = new SndBuf[1][];
      final Pan2[][] panHolder = new Pan2[1][];
      final Gain[][] dSendHolder = new Gain[1][];
      final Gain[][] rSendHolder = new Gain[1][];
      final SwitchableAdsr[][][] kitEnvHolder = new SwitchableAdsr[1][][];
      final SwitchableFilter[][] kitFilHolder = new SwitchableFilter[1][];
      final HPF[][] kitHpfHolder = new HPF[1][];
      final Dyno[][] kitCompHolder = new Dyno[1][];
      final ModFxUnit[][] kitModFxHolder = new ModFxUnit[1][];
      final Gain[][] kitUnisonSummerHolder = new Gain[1][];
      final SndBuf[][][] kitUnisonSubHolder = new SndBuf[1][][];
      final Pan2[][][] kitUnisonPanHolder = new Pan2[1][][];
      java.util.function.Consumer<Gain> doInit =
          (bus) -> {
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
            SwitchableAdsr[][] ke = new SwitchableAdsr[vc][4];
            SwitchableFilter[] kitFil = new SwitchableFilter[vc];
            HPF[] kitHpf = new HPF[vc];
            Dyno[] compArr = new Dyno[vc];
            ModFxUnit[] kitModFx = new ModFxUnit[vc];
            SndBuf[][] kitUnisonSub = new SndBuf[vc][];
            Gain[] kitUnisonSummer = new Gain[vc];
            Pan2[][] kitSubPan = new Pan2[vc][];
            int maxKitUnison = 8;
            for (int i = 0; i < vc; i++) {
              k[i] = new SndBuf();
              k[i].rate(0);
              pn[i] = new Pan2();
              ds[i] = new Gain();
              rs[i] = new Gain();
              ke[i][0] = new SwitchableAdsr(sr, vm);
              ke[i][1] = new SwitchableAdsr(sr, vm);
              ke[i][2] = new SwitchableAdsr(sr, vm);
              ke[i][3] = new SwitchableAdsr(sr, vm);
              kitFil[i] = new SwitchableFilter(sr, vm);
              kitHpf[i] = new HPF(sr);
              kitModFx[i] = new ModFxUnit(sr);
              compArr[i] = new Dyno(sr);
              kitUnisonSummer[i] = new Gain();
              k[i].chuck(kitUnisonSummer[i])
                  .chuck(kitFil[i])
                  .chuck(kitHpf[i])
                  .chuck(ke[i][0])
                  .chuck(pn[i])
                  .chuck(kitModFx[i])
                  .chuck(compArr[i])
                  .chuck(bus);
              SndBuf[] uSub = new SndBuf[maxKitUnison - 1];
              Pan2[] uPan = new Pan2[maxKitUnison - 1];
              for (int us = 0; us < uSub.length; us++) {
                uSub[us] = new SndBuf();
                uSub[us].gain(0.0f);
                uSub[us].rate(0);
                uPan[us] = new Pan2();
                uSub[us].chuck(uPan[us]).chuck(kitUnisonSummer[i]);
              }
              kitUnisonSub[i] = uSub;
              kitSubPan[i] = uPan;
              pn[i].chuck(ds[i]).chuck((ChuckUGen) vm.getGlobalObject(BridgeContract.G_DELAY_IN));
              pn[i].chuck(rs[i]).chuck((ChuckUGen) vm.getGlobalObject(BridgeContract.G_REVERB_IN));
              ke[i][0].forceMute();
              ke[i][0].set(0.001, 0, 1, 0.05);
              ke[i][1].forceMute();
              ke[i][1].set(0.001, 0, 1, 0.05);
              ke[i][2].forceMute();
              ke[i][2].set(0.001, 0, 1, 0.05);
              ke[i][3].forceMute();
              ke[i][3].set(0.001, 0, 1, 0.05);
              ds[i].gain(0.0f);
              rs[i].gain(0.15f);
              kitFil[i].reset();
              kitFil[i].freq(20000);
            }
            loadKitSamples(k);
            // Load same samples into sub-voices for unison
            for (int i = 0; i < k.length; i++) {
              Object pathObj = vm.getGlobalObject("g_sample_" + i);
              if (!(pathObj instanceof String path) || path.isEmpty()) continue;
              SndBuf[] subs = kitUnisonSub[i];
              if (subs == null) continue;
              for (int us = 0; us < subs.length; us++) {
                java.io.File f = new java.io.File(path);
                if (f.exists()) {
                  loadSndBuf(subs[us], f.getAbsolutePath());
                  continue;
                }
                java.io.File libraryDir2 =
                    org.chuck.deluge.project.PreferencesManager.getLibraryDir();
                java.io.File rel = new java.io.File(libraryDir2, path);
                if (rel.exists()) {
                  loadSndBuf(subs[us], rel.getAbsolutePath());
                  continue;
                }
              }
            }
            for (int i = 0; i < k.length && i < 4; i++) {
              System.out.println(
                  "[kit_shred] INIT kit["
                      + i
                      + "]: samples="
                      + k[i].samples()
                      + " rate="
                      + k[i].rate()
                      + " pos="
                      + k[i].pos());
            }
            System.out.println("[kit_shred] INIT voiceCount=" + vc);
            kitHolder[0] = k;
            panHolder[0] = pn;
            dSendHolder[0] = ds;
            rSendHolder[0] = rs;
            kitEnvHolder[0] = ke;
            kitFilHolder[0] = kitFil;
            kitHpfHolder[0] = kitHpf;
            kitModFxHolder[0] = kitModFx;
            kitCompHolder[0] = compArr;
            kitUnisonSummerHolder[0] = kitUnisonSummer;
            kitUnisonSubHolder[0] = kitUnisonSub;
            kitUnisonPanHolder[0] = kitSubPan;
          };
      doInit.accept(master);
      vm.print("[kit] sporking preview shred, kit.length=" + kitHolder[0].length + "\n");
      vm.spork(() -> kit_preview_shred(kitHolder[0], kitEnvHolder[0]));
      vm.spork(() -> kit_reload_shred(kitHolder[0]));
      ChuckEvent tickEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.E_TICK);
      long lastStep = -1;
      while (isRunning()) {
        advance(tickEvent);
        if (vm.getGlobalInt(BridgeContract.G_RELOAD) > 0) {
          vm.setGlobalInt(BridgeContract.G_RELOAD, 0L);
          if (vm.getLogLevel() >= 1) vm.print("[kit_shred] re-init triggered\n");
          advance(ms(1));
          doInit.accept(master);
          vm.spork(() -> kit_preview_shred(kitHolder[0], kitEnvHolder[0]));
          lastStep = -1;
          continue;
        }
        SndBuf[] kit = kitHolder[0];
        Pan2[] pan = panHolder[0];
        Gain[] dSend = dSendHolder[0];
        Gain[] rSend = rSendHolder[0];
        SwitchableAdsr[][] kitEnv = kitEnvHolder[0];
        SwitchableFilter[] kitFil = kitFilHolder[0];
        HPF[] kitHpfArr = kitHpfHolder[0];
        Dyno[] kitComp = kitCompHolder[0];
        ModFxUnit[] kitModFx = kitModFxHolder[0];
        SndBuf[][] kitSub = kitUnisonSubHolder[0];
        Pan2[][] kitSubPan = kitUnisonPanHolder[0];
        Gain[] kitUSummer = kitUnisonSummerHolder[0];
        if (vm.getGlobalInt(BridgeContract.G_PLAY) == 0) {
          lastStep = -1;
          for (int r = 0; r < kit.length; r++) {
            kitEnv[r][0].keyOff();
            kitEnv[r][1].keyOff();
            kitEnv[r][2].keyOff();
            kitEnv[r][3].keyOff();
            kit[r].rate(0);
            if (kitSub != null && r < kitSub.length && kitSub[r] != null) {
              for (SndBuf sb : kitSub[r]) {
                if (sb != null) sb.rate(0);
              }
            }
          }
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
        ChuckArray lfoSync = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_SYNC_LEVEL);
        // Kit sound-level extended params
        ChuckArray kitVol = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_VOLUME);
        ChuckArray kitPan = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_PAN);
        ChuckArray kitHpfF = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_HPF_FREQ);
        ChuckArray kitHpfR = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_HPF_RES);
        ChuckArray kitNoise = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_NOISE_VOL);
        ChuckArray kitEqBass = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_EQ_BASS);
        ChuckArray kitEqTreble = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_EQ_TREBLE);
        ChuckArray kitSidechain = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_SIDECHAIN);
        ChuckArray kitCompA = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_COMP_ATTACK);
        ChuckArray kitCompR = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_COMP_RELEASE);
        ChuckArray kitCompBlend = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_COMP_BLEND);
        ChuckArray kitCompHpf =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_COMP_SIDECHAIN_HPF);
        ChuckArray kitCompRatio = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_COMP_RATIO);
        ChuckArray kitModFxType = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_MOD_FX_TYPE);
        ChuckArray kitModFxRate = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_MOD_FX_RATE);
        ChuckArray kitModFxDepth =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_MOD_FX_DEPTH);
        ChuckArray kitModFxOffset =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_MOD_FX_OFFSET);
        ChuckArray kitModFxFeedback =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_MOD_FX_FEEDBACK);
        ChuckArray kitStutter = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_STUTTER_RATE);
        ChuckArray kitSrr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_SAMPLE_RATE_RED);
        ChuckArray kitBc = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_BITCRUSH);
        // ── Unwired per-kit globals (bridging gap between model/bridge and engine) ──
        ChuckArray kitHpfMode = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_HPF_MODE);
        ChuckArray kitHpfMorph = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_HPF_MORPH);
        ChuckArray kitHpfFm = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_HPF_FM);
        ChuckArray kitLpfMorph = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_LPF_MORPH);
        ChuckArray kitOsc2Type = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_OSC2_TYPE);
        ChuckArray kitUnisonNum = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_UNISON_NUM);
        ChuckArray kitUnisonDetune =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_UNISON_DETUNE);
        ChuckArray kitUnisonSpread =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_UNISON_SPREAD);
        ChuckArray kitWaveIndex = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_WAVE_INDEX);
        ChuckArray kitDelayRate = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_DELAY_RATE);
        ChuckArray kitDelayFb = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_DELAY_FB);
        ChuckArray kitMaxVoices = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_MAX_VOICES);
        ChuckArray kitPolyphony = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_POLYPHONY);
        // ── Kit per-sound FX arrays ──
        ChuckArray kitDelayPingpong =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_DELAY_PINGPONG);
        ChuckArray kitDelayAnalog =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_DELAY_ANALOG);
        ChuckArray kitReverbAmount =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_REVERB_AMOUNT);
        ChuckArray kitCompThreshold =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_COMP_THRESHOLD);
        ChuckArray kitCompSyncLevel =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_COMP_SYNC_LEVEL);
        ChuckArray kitSidechainSyncLevel =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_SIDECHAIN_SYNC_LEVEL);
        ChuckArray kitSidechainSyncType =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_SIDECHAIN_SYNC_TYPE);
        ChuckArray kitSidechainAttack =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_SIDECHAIN_ATTACK);
        ChuckArray kitSidechainRelease =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_SIDECHAIN_RELEASE);
        ChuckArray envArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ENV);
        // ── Extended per-step automation arrays (shared with SynthShred) ──
        ChuckArray sStepVolume = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_VOLUME);
        ChuckArray sStepCompAttack =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_COMP_ATTACK);
        ChuckArray sStepCompRelease =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_COMP_RELEASE);
        ChuckArray sStepModFxFeedback =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_MOD_FX_FEEDBACK);
        ChuckArray sStepStutter = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_STUTTER);
        ChuckArray sStepSrr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_SRR);
        ChuckArray sStepBitcrush = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_BITCRUSH);
        ChuckArray sStepPortamento =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_PORTAMENTO);
        ChuckArray sStepDelay = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_DELAY);
        ChuckArray sStepReverb = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_REVERB);
        for (int r = 0; r < BridgeContract.TRACKS; r++) {
          if (r < kit.length) {
            dSend[r].gain(dlySnd != null ? (float) dlySnd.getFloat(r) : 0.0f);
            rSend[r].gain(revSnd != null ? (float) revSnd.getFloat(r) : 0.15f);
            if (kitVol != null && r < kitVol.size()) kit[r].gain((float) kitVol.getFloat(r));
            if (kitPan != null && r < kitPan.size()) pan[r].pan((float) kitPan.getFloat(r));
          }
          // Per-track default arrays stored as globals for FX bus to read
          if (kitHpfF != null && r < kitHpfF.size())
            vm.setGlobalFloat(BridgeContract.G_KIT_HPF_FREQ + "_" + r, kitHpfF.getFloat(r));
          if (kitHpfR != null && r < kitHpfR.size())
            vm.setGlobalFloat(BridgeContract.G_KIT_HPF_RES + "_" + r, kitHpfR.getFloat(r));
          if (kitNoise != null && r < kitNoise.size())
            vm.setGlobalFloat(BridgeContract.G_KIT_NOISE_VOL + "_" + r, kitNoise.getFloat(r));
          if (kitEqBass != null && r < kitEqBass.size())
            vm.setGlobalFloat(BridgeContract.G_KIT_EQ_BASS + "_" + r, kitEqBass.getFloat(r));
          if (kitEqTreble != null && r < kitEqTreble.size())
            vm.setGlobalFloat(BridgeContract.G_KIT_EQ_TREBLE + "_" + r, kitEqTreble.getFloat(r));
          if (kitSidechain != null && r < kitSidechain.size())
            vm.setGlobalFloat(BridgeContract.G_KIT_SIDECHAIN + "_" + r, kitSidechain.getFloat(r));
          if (kitModFxType != null && r < kitModFxType.size())
            vm.setGlobalFloat(BridgeContract.G_KIT_MOD_FX_TYPE + "_" + r, kitModFxType.getFloat(r));
          if (kitModFxRate != null && r < kitModFxRate.size())
            vm.setGlobalFloat(BridgeContract.G_KIT_MOD_FX_RATE + "_" + r, kitModFxRate.getFloat(r));
          if (kitModFxDepth != null && r < kitModFxDepth.size())
            vm.setGlobalFloat(
                BridgeContract.G_KIT_MOD_FX_DEPTH + "_" + r, kitModFxDepth.getFloat(r));
          if (kitModFxOffset != null && r < kitModFxOffset.size())
            vm.setGlobalFloat(
                BridgeContract.G_KIT_MOD_FX_OFFSET + "_" + r, kitModFxOffset.getFloat(r));
          if (kitModFxFeedback != null && r < kitModFxFeedback.size())
            vm.setGlobalFloat(
                BridgeContract.G_KIT_MOD_FX_FEEDBACK + "_" + r, kitModFxFeedback.getFloat(r));
          if (kitStutter != null && r < kitStutter.size())
            vm.setGlobalFloat(BridgeContract.G_KIT_STUTTER_RATE + "_" + r, kitStutter.getFloat(r));
          if (kitSrr != null && r < kitSrr.size())
            vm.setGlobalFloat(BridgeContract.G_KIT_SAMPLE_RATE_RED + "_" + r, kitSrr.getFloat(r));
          if (kitBc != null && r < kitBc.size())
            vm.setGlobalFloat(BridgeContract.G_KIT_BITCRUSH + "_" + r, kitBc.getFloat(r));
          // ── Unwired per-kit globals (previously never written by engine) ──
          if (kitHpfMode != null && r < kitHpfMode.size())
            vm.setGlobalFloat(BridgeContract.G_KIT_HPF_MODE + "_" + r, kitHpfMode.getFloat(r));
          if (kitHpfMorph != null && r < kitHpfMorph.size())
            vm.setGlobalFloat(BridgeContract.G_KIT_HPF_MORPH + "_" + r, kitHpfMorph.getFloat(r));
          if (kitHpfFm != null && r < kitHpfFm.size())
            vm.setGlobalFloat(BridgeContract.G_KIT_HPF_FM + "_" + r, kitHpfFm.getFloat(r));
          if (kitLpfMorph != null && r < kitLpfMorph.size())
            vm.setGlobalFloat(BridgeContract.G_KIT_LPF_MORPH + "_" + r, kitLpfMorph.getFloat(r));
          if (kitOsc2Type != null && r < kitOsc2Type.size())
            vm.setGlobalFloat(BridgeContract.G_KIT_OSC2_TYPE + "_" + r, kitOsc2Type.getFloat(r));
          if (kitUnisonNum != null && r < kitUnisonNum.size())
            vm.setGlobalFloat(BridgeContract.G_KIT_UNISON_NUM + "_" + r, kitUnisonNum.getFloat(r));
          if (kitUnisonDetune != null && r < kitUnisonDetune.size())
            vm.setGlobalFloat(
                BridgeContract.G_KIT_UNISON_DETUNE + "_" + r, kitUnisonDetune.getFloat(r));
          if (kitUnisonSpread != null && r < kitUnisonSpread.size())
            vm.setGlobalFloat(
                BridgeContract.G_KIT_UNISON_SPREAD + "_" + r, kitUnisonSpread.getFloat(r));
          if (kitWaveIndex != null && r < kitWaveIndex.size())
            vm.setGlobalFloat(BridgeContract.G_KIT_WAVE_INDEX + "_" + r, kitWaveIndex.getFloat(r));
          if (kitDelayRate != null && r < kitDelayRate.size())
            vm.setGlobalFloat(BridgeContract.G_KIT_DELAY_RATE + "_" + r, kitDelayRate.getFloat(r));
          if (kitDelayFb != null && r < kitDelayFb.size())
            vm.setGlobalFloat(BridgeContract.G_KIT_DELAY_FB + "_" + r, kitDelayFb.getFloat(r));
          if (kitDelayPingpong != null && r < kitDelayPingpong.size())
            vm.setGlobalFloat(
                BridgeContract.G_KIT_DELAY_PINGPONG + "_" + r, kitDelayPingpong.getFloat(r));
          if (kitDelayAnalog != null && r < kitDelayAnalog.size())
            vm.setGlobalFloat(
                BridgeContract.G_KIT_DELAY_ANALOG + "_" + r, kitDelayAnalog.getFloat(r));
          if (kitReverbAmount != null && r < kitReverbAmount.size())
            vm.setGlobalFloat(
                BridgeContract.G_KIT_REVERB_AMOUNT + "_" + r, kitReverbAmount.getFloat(r));
          if (kitCompThreshold != null && r < kitCompThreshold.size())
            vm.setGlobalFloat(
                BridgeContract.G_KIT_COMP_THRESHOLD + "_" + r, kitCompThreshold.getFloat(r));
          if (kitCompSyncLevel != null && r < kitCompSyncLevel.size())
            vm.setGlobalFloat(
                BridgeContract.G_KIT_COMP_SYNC_LEVEL + "_" + r, kitCompSyncLevel.getFloat(r));
          if (kitSidechainSyncLevel != null && r < kitSidechainSyncLevel.size())
            vm.setGlobalFloat(
                BridgeContract.G_KIT_SIDECHAIN_SYNC_LEVEL + "_" + r,
                kitSidechainSyncLevel.getFloat(r));
          if (kitSidechainSyncType != null && r < kitSidechainSyncType.size())
            vm.setGlobalFloat(
                BridgeContract.G_KIT_SIDECHAIN_SYNC_TYPE + "_" + r,
                kitSidechainSyncType.getFloat(r));
          if (kitSidechainAttack != null && r < kitSidechainAttack.size())
            vm.setGlobalFloat(
                BridgeContract.G_KIT_SIDECHAIN_ATTACK + "_" + r, kitSidechainAttack.getFloat(r));
          if (kitSidechainRelease != null && r < kitSidechainRelease.size())
            vm.setGlobalFloat(
                BridgeContract.G_KIT_SIDECHAIN_RELEASE + "_" + r, kitSidechainRelease.getFloat(r));
          if (kitMaxVoices != null && r < kitMaxVoices.size())
            vm.setGlobalFloat(BridgeContract.G_KIT_MAX_VOICES + "_" + r, kitMaxVoices.getFloat(r));
          if (kitPolyphony != null && r < kitPolyphony.size())
            vm.setGlobalFloat(BridgeContract.G_KIT_POLYPHONY + "_" + r, kitPolyphony.getFloat(r));
          if (kitCompA != null && r < kitCompA.size())
            vm.setGlobalFloat(BridgeContract.G_KIT_COMP_ATTACK + "_" + r, kitCompA.getFloat(r));
          if (kitCompR != null && r < kitCompR.size())
            vm.setGlobalFloat(BridgeContract.G_KIT_COMP_RELEASE + "_" + r, kitCompR.getFloat(r));
          if (kitCompBlend != null && r < kitCompBlend.size())
            vm.setGlobalFloat(BridgeContract.G_KIT_COMP_BLEND + "_" + r, kitCompBlend.getFloat(r));
          if (kitCompHpf != null && r < kitCompHpf.size())
            vm.setGlobalFloat(
                BridgeContract.G_KIT_COMP_SIDECHAIN_HPF + "_" + r, kitCompHpf.getFloat(r));
          if (kitCompRatio != null && r < kitCompRatio.size())
            vm.setGlobalFloat(BridgeContract.G_KIT_COMP_RATIO + "_" + r, kitCompRatio.getFloat(r));
          if (kitAtk != null && r < kit.length) {
            double a = kitAtk.getFloat(r);
            double d = kitDec != null ? kitDec.getFloat(r) : 0;
            double s = kitSus != null ? kitSus.getFloat(r) : 1;
            double rl = kitRel != null ? kitRel.getFloat(r) : 0.05;
            kitEnv[r][0].set(Math.max(0.0001, a), d, s, Math.max(0.001, rl));
            // ENV 1-3 read from shared g_env array
            if (envArr != null) {
              for (int ei = 1; ei < 4; ei++) {
                int eb = (r * BridgeContract.ENV_COUNT + ei) * BridgeContract.ENV_PARAMS;
                kitEnv[r][ei].set(
                    Math.max(0.0001, envArr.getFloat(eb + 0)),
                    envArr.getFloat(eb + 1),
                    envArr.getFloat(eb + 2),
                    Math.max(0.001, envArr.getFloat(eb + 3)));
              }
            }
          }
        }
        // ── Step blending happens per-note in the per-step loop below ──
        for (int r = 0; r < kit.length; r++) {
          if (trackType != null && trackType.getInt(r) != 0) continue;
          if (mute != null && mute.getInt(r) != 0) continue;
          double[] lfoVals = new double[BridgeContract.LFO_COUNT];
          double kitBpm = vm.getGlobalFloat(BridgeContract.G_BPM);
          if (kitBpm < 1.0) kitBpm = 120.0;
          for (int l = 0; l < BridgeContract.LFO_COUNT; l++) {
            int syncLvl = lfoSync != null ? (int) lfoSync.getInt(l) : 0;
            double rawRate = lfoRate != null ? lfoRate.getFloat(l) : 1.0;
            double rate = syncLvl > 0 ? lfoSyncRate(syncLvl, kitBpm) : rawRate;
            double depth = lfoDepth != null ? lfoDepth.getFloat(l) : 0.0;
            int type = lfoType != null ? (int) lfoType.getInt(l) : 0;
            if (depth == 0.0) {
              lfoVals[l] = 0.0;
              continue;
            }
            lfoPhaseKit[l] = (lfoPhaseKit[l] + rate / sr) % 1.0;
            double raw =
                switch (type) {
                  case 1 -> 2.0 * lfoPhaseKit[l] - 1.0;
                  case 2 -> lfoPhaseKit[l] < 0.5 ? 1.0 : -1.0;
                  case 3 ->
                      lfoPhaseKit[l] < 0.5
                          ? (4.0 * lfoPhaseKit[l] - 1.0)
                          : (3.0 - 4.0 * lfoPhaseKit[l]);
                  case 4 -> { // S_AND_H: sample new value when phase wraps
                    if (lfoPhaseKit[l] < rate / sr) lfoSampleHoldKit[l] = 2.0 * Math.random() - 1.0;
                    yield lfoSampleHoldKit[l];
                  }
                  case 5 -> { // RANDOM_WALK: gradual random drift
                    lfoRandWalkKit[l] += (Math.random() - 0.5) * 0.02;
                    lfoRandWalkKit[l] = Math.max(-1.0, Math.min(1.0, lfoRandWalkKit[l]));
                    yield lfoRandWalkKit[l];
                  }
                  case 6 -> { // WARBLER: second-order smoothed random walk
                    double noise = (Math.random() - 0.5) * 0.1;
                    lfoWarbler1Kit[l] += noise;
                    lfoWarbler1Kit[l] *= 0.99; // leaky integrator
                    lfoWarbler2Kit[l] +=
                        (lfoWarbler1Kit[l] - lfoWarbler2Kit[l]) * 0.2; // second-order smoothing
                    lfoWarbler2Kit[l] = Math.max(-1.0, Math.min(1.0, lfoWarbler2Kit[l]));
                    yield lfoWarbler2Kit[l];
                  }
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
          ChuckArray clipFill = outer.getClipArray(BridgeContract.G_FILL, clipIdx);
          ChuckArray clipIterance = outer.getClipArray(BridgeContract.G_ITERANCE, clipIdx);
          int len = trkLen != null ? (int) Math.max(1, trkLen.getInt(r)) : BridgeContract.STEPS;
          int step = (int) (currentStep % len);
          int idx = r * BridgeContract.STEPS + step;
          if (clipPat == null || clipPat.getInt(idx) == 0) continue;
          if (clipProb != null && Math.random() > clipProb.getFloat(idx)) continue;
          // Fill mode: fill=0 → regular step, fill>0 → fill-only (plays only when fill active)
          {
            double sf = clipFill != null ? clipFill.getFloat(idx) : 0.0;
            int fillActive = (int) vm.getGlobalInt(BridgeContract.G_FILL_ACTIVE);
            if (fillActive != 0) {
              if (sf <= 0f || Math.random() > sf) continue;
            } else {
              if (sf > 0f) continue;
            }
          }
          // Iterance: extra sub-triggers (0-3)
          int it =
              clipIterance != null ? Math.max(0, Math.min(3, (int) clipIterance.getFloat(idx))) : 0;
          if (kitMuteGrp != null) {
            long grp = kitMuteGrp.getInt(r);
            if (grp > 0) {
              for (int o = 0; o < kit.length; o++) {
                if (o != r && kitMuteGrp.getInt(o) == grp) {
                  kit[o].rate(0);
                  kitEnv[o][0].keyOff();
                  kitEnv[o][1].keyOff();
                  kitEnv[o][2].keyOff();
                  kitEnv[o][3].keyOff();
                  if (kitSub != null && o < kitSub.length && kitSub[o] != null) {
                    for (SndBuf sb : kitSub[o]) {
                      if (sb != null) sb.rate(0);
                    }
                  }
                }
              }
            }
          }
          if (r == 0) {
            ChuckEvent sidechainEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.E_SIDECHAIN);
            if (sidechainEvent != null) sidechainEvent.broadcast();
          }

          // ── Kit step automation blending (extended g_step_* arrays) ──
          double kStepVol = sStepVolume != null ? sStepVolume.getFloat(idx) : 0.0;
          if (kStepVol != 0.0 && r < kit.length) {
            kit[r].gain(
                (float)
                    Math.max(
                        0.0,
                        (kitVol != null && r < kitVol.size() ? kitVol.getFloat(r) : 1.0)
                            + kStepVol));
          }
          // Kit compressor attack/release: blend step automation onto per-track globals
          if (sStepCompAttack != null) {
            double perTrack = kitCompA != null && r < kitCompA.size() ? kitCompA.getFloat(r) : 0.0;
            vm.setGlobalFloat(
                BridgeContract.G_KIT_COMP_ATTACK + "_" + r,
                (float) Math.max(0.0, Math.min(1.0, perTrack + sStepCompAttack.getFloat(idx))));
          }
          if (sStepCompRelease != null) {
            double perTrack = kitCompR != null && r < kitCompR.size() ? kitCompR.getFloat(r) : 0.0;
            vm.setGlobalFloat(
                BridgeContract.G_KIT_COMP_RELEASE + "_" + r,
                (float) Math.max(0.0, Math.min(1.0, perTrack + sStepCompRelease.getFloat(idx))));
          }
          // Kit ModFx feedback: blend step automation
          if (sStepModFxFeedback != null) {
            double perTrack =
                kitModFxFeedback != null && r < kitModFxFeedback.size()
                    ? kitModFxFeedback.getFloat(r)
                    : 0.0;
            vm.setGlobalFloat(
                BridgeContract.G_KIT_MOD_FX_FEEDBACK + "_" + r,
                (float) Math.max(0.0, Math.min(1.0, perTrack + sStepModFxFeedback.getFloat(idx))));
          }
          // Kit stutter/bitcrush/SRR: blend step automation
          if (sStepStutter != null) {
            double perTrack =
                kitStutter != null && r < kitStutter.size() ? kitStutter.getFloat(r) : 0.0;
            vm.setGlobalFloat(
                BridgeContract.G_KIT_STUTTER_RATE + "_" + r,
                (float) Math.max(0.0, perTrack + sStepStutter.getFloat(idx)));
          }
          if (sStepSrr != null) {
            double perTrack = kitSrr != null && r < kitSrr.size() ? kitSrr.getFloat(r) : 0.0;
            vm.setGlobalFloat(
                BridgeContract.G_KIT_SAMPLE_RATE_RED + "_" + r,
                (float) Math.max(0.0, perTrack + sStepSrr.getFloat(idx)));
          }
          if (sStepBitcrush != null) {
            double perTrack = kitBc != null && r < kitBc.size() ? kitBc.getFloat(r) : 0.0;
            vm.setGlobalFloat(
                BridgeContract.G_KIT_BITCRUSH + "_" + r,
                (float) Math.max(0.0, perTrack + sStepBitcrush.getFloat(idx)));
          }
          // Kit portamento (shared G_PORTAMENTO with synth)
          if (sStepPortamento != null) {
            vm.setGlobalFloat(BridgeContract.G_PORTAMENTO + "_" + r, sStepPortamento.getFloat(idx));
          }
          // Per-step delay/reverb send overrides (0 = no override, use per-track send)
          if (sStepDelay != null) {
            float sd = (float) sStepDelay.getFloat(idx);
            if (sd > 0f) dSend[r].gain(sd);
          }
          if (sStepReverb != null) {
            float srV = (float) sStepReverb.getFloat(idx);
            if (srV > 0f) rSend[r].gain(srV);
          }
          // ── Hard-wired Env 2-4 → DSP modulation (kit uses same per-track depth globals) ──
          double kEnvMod1 = kitEnv[r][1].lastOut;
          double kEnvMod2 = kitEnv[r][2].lastOut;
          double kEnvMod3 = kitEnv[r][3].lastOut;
          double kEnvToPit = 0, kEnvToV = 0, kEnvToF = 0;
          kEnvToPit +=
              kEnvMod2 * (float) vm.getGlobalFloat(BridgeContract.G_ENV3_PITCH_DEPTH + "_" + r);
          kEnvToV +=
              kEnvMod2 * (float) vm.getGlobalFloat(BridgeContract.G_ENV3_VOLUME_DEPTH + "_" + r);
          kEnvToPit +=
              kEnvMod3 * (float) vm.getGlobalFloat(BridgeContract.G_ENV4_PITCH_DEPTH + "_" + r);
          kEnvToV +=
              kEnvMod3 * (float) vm.getGlobalFloat(BridgeContract.G_ENV4_VOLUME_DEPTH + "_" + r);
          kEnvToF +=
              kEnvMod1 * (float) vm.getGlobalFloat(BridgeContract.G_ENV2_FILTER_DEPTH + "_" + r);

          // ── Kit patch cable modulation evaluation ──
          double pcModPit = 0, pcModV = 0;
          ChuckArray pcCountArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_PC_COUNT);
          ChuckArray pcSourceArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_PC_SOURCE);
          ChuckArray pcDestArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_PC_DEST);
          ChuckArray pcAmtArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_PC_AMOUNT);
          ChuckArray pcPolArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_PC_POLARITY);
          if (pcCountArr != null) {
            long cableCount = pcCountArr.getInt(r);
            int base = r * BridgeContract.MAX_CABLES_PER_TRACK;
            double curVel = clipPat.getFloat(idx);
            for (int c = 0; c < cableCount; c++) {
              int ci = base + c;
              int src = pcSourceArr != null ? (int) pcSourceArr.getInt(ci) : -1;
              int dst = pcDestArr != null ? (int) pcDestArr.getInt(ci) : -1;
              if (src < 0 || dst < 0) continue;
              double rawAmt = pcAmtArr != null ? pcAmtArr.getFloat(ci) : 0.0;
              int pol = pcPolArr != null ? (int) pcPolArr.getInt(ci) : 0;
              double srcVal = 0;
              switch (src) {
                case 0 -> srcVal = curVel; // velocity
                case 1, 2, 3, 4 -> srcVal = kitEnv[r][src - 1].lastOut; // envelope1-4
                case 5, 6, 7, 8 -> srcVal = lfoVals[src - 5]; // lfo1-4
                case 9 -> srcVal = 0.0; // aftertouch
                case 10 -> srcVal = 0.0; // note
                case 11 -> srcVal = Math.random() * 2.0 - 1.0; // random
                case 12 -> srcVal = 0.0; // sidechain
              }
              double effective = srcVal * rawAmt;
              switch (dst) {
                case 0 -> pcModV += effective; // volume
                case 1 -> {} // pan (not used per-voice in kit)
                case 6 -> pcModPit += effective; // pitch
              }
            }
          }
          double totalModPit = lfoPit + pcModPit * 12.0 + kEnvToPit;
          double totalModV = lfoV + pcModV + kEnvToV;
          double totalModF = kEnvToF;
          double pitchSemi = (kitPitch != null) ? kitPitch.getFloat(r) : 0.0;
          // Global transpose (semitones) applied on top of per-sample pitch
          pitchSemi += vm.getGlobalInt(BridgeContract.G_TRANSPOSE);
          // Humanize: 0-1 maps to ±0.5 semitones random pitch variation
          float humanizeAmt = (float) vm.getGlobalFloat(BridgeContract.G_HUMANIZE);
          if (humanizeAmt > 0.0f) pitchSemi += (Math.random() - 0.5) * humanizeAmt;
          double rate = Math.pow(2.0, (pitchSemi + totalModPit) / 12.0);
          boolean reverse = (kitRev != null) && kitRev.getInt(r) != 0;
          long samples = Math.max(1, kit[r].samples());
          float startAt = clipSStart != null ? (float) clipSStart.getFloat(idx) : 0.0f;
          float endAt = clipSEnd != null ? (float) clipSEnd.getFloat(idx) : 1.0f;
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
                  (clipVel.getFloat(idx)
                      * trkLvl.getFloat(r)
                      * Math.max(0.0, 1.0 + totalModV * 0.5));
          if (vm.getLogLevel() >= 1)
            vm.print(
                "[kit] TRIGGER r="
                    + r
                    + " vel="
                    + clipVel.getFloat(idx)
                    + " trkLvl="
                    + trkLvl.getFloat(r)
                    + " gain="
                    + gain
                    + "\n");
          kit[r].gain(gain);
          // ── Kit unison sub-voices (detuned rate + stereo spread) ──
          if (kitSub != null
              && r < kitSub.length
              && kitSub[r] != null
              && kitUSummer != null
              && r < kitUSummer.length) {
            float kitNumVal =
                kitUnisonNum != null && r < kitUnisonNum.size()
                    ? (float) kitUnisonNum.getFloat(r)
                    : 0.0f;
            float kitDetuneVal =
                kitUnisonDetune != null && r < kitUnisonDetune.size()
                    ? (float) kitUnisonDetune.getFloat(r)
                    : 0.0f;
            float kitSpreadVal =
                kitUnisonSpread != null && r < kitUnisonSpread.size()
                    ? (float) kitUnisonSpread.getFloat(r)
                    : 0.0f;
            int totalKitUnison = Math.max(1, Math.min(8, Math.round(kitNumVal)));
            if (totalKitUnison > 1) {
              SndBuf[] subs = kitSub[r];
              Pan2[] subPans = kitSubPan != null && r < kitSubPan.length ? kitSubPan[r] : null;
              int halfCount = totalKitUnison - 1;
              // Adjust main voice gain for power normalization
              double mainBoost = 1.0 / Math.sqrt(totalKitUnison);
              kit[r].gain(gain * (float) mainBoost);
              for (int us = 0; us < subs.length; us++) {
                if (us >= halfCount) {
                  subs[us].gain(0.0f);
                  subs[us].rate(0);
                  continue;
                }
                float offset = (us + 1.0f) - (halfCount + 1.0f) / 2.0f;
                double subRate =
                    (reverse ? -1.0 : 1.0)
                        * Math.abs(rate)
                        * Math.pow(2.0, kitDetuneVal * offset / 1200.0);
                subs[us].rate((float) subRate);
                subs[us].pos(reverse ? endPos : startPos);
                subs[us].gain((float) (gain * mainBoost));
                // Stereo spread via per-sub Pan2
                if (subPans != null && us < subPans.length && subPans[us] != null) {
                  float panPos =
                      halfCount > 1 ? offset / ((halfCount - 1.0f) / 2.0f) * kitSpreadVal : 0.0f;
                  subPans[us].pan(Math.max(-1.0f, Math.min(1.0f, panPos)));
                }
              }
            }
          }
          // Apply kit sound LPF (SVFilter cutoff/res from per-track g_filter) and HPF
          {
            Object gFilObj = vm.getGlobalObject(BridgeContract.G_FILTER);
            Object kitLpfModeObj = vm.getGlobalObject(BridgeContract.G_KIT_LPF_MODE);
            Object kitLpfDriveObj = vm.getGlobalObject(BridgeContract.G_KIT_LPF_DRIVE);
            Object kitLpfNotchObj = vm.getGlobalObject(BridgeContract.G_KIT_LPF_NOTCH);
            float kf =
                (gFilObj instanceof ChuckArray gFilArr && r < gFilArr.size() / 2)
                    ? (float) gFilArr.getFloat(r * 2)
                    : 1.0f;
            float kq =
                (gFilObj instanceof ChuckArray gFilArr && r < gFilArr.size() / 2)
                    ? (float) gFilArr.getFloat(r * 2 + 1)
                    : 0.5f;
            kitFil[r].freq(20.0f + kf * 19980.0f);
            kitFil[r].Q(1.0f + kq * 9.0f);
            if (kitLpfModeObj instanceof ChuckArray lm && r < lm.size()) {
              int klm = (int) lm.getInt(r);
              // LPF morph: prefer per-sound morph from G_KIT_LPF_MORPH, fall back to mode-based
              // default
              double kmorph =
                  (kitLpfMorph != null && r < kitLpfMorph.size())
                      ? kitLpfMorph.getFloat(r)
                      : (klm == 2 ? 0.5 : 0.0);
              kitFil[r].morph(kmorph);
            }
            if (kitLpfDriveObj instanceof ChuckArray kd && r < kd.size()) {
              float baseDrive = (float) kd.getFloat(r);
              // Apply global filter drive preference: if enabled, lift drive floor to 1.8
              float gfd = (float) vm.getGlobalFloat(BridgeContract.G_CHAR_FILTER_DRIVE);
              if (gfd > 0.5f) baseDrive = Math.max(baseDrive, 1.8f);
              kitFil[r].drive(baseDrive);
            }
            if (kitLpfNotchObj instanceof ChuckArray kn && r < kn.size())
              kitFil[r].notchMode(kn.getInt(r) != 0);
            float hpfFmMod =
                kitHpfFm != null && r < kitHpfFm.size() ? (float) kitHpfFm.getFloat(r) : 0f;
            float baseHpfFreq =
                kitHpfF != null && r < kitHpfF.size() ? (float) kitHpfF.getFloat(r) : 20.0f;
            kitHpfArr[r].freq(
                Math.max(20.0f, baseHpfFreq + (float) (totalModF * hpfFmMod * 5000.0)));
            if (kitHpfR != null && r < kitHpfR.size())
              kitHpfArr[r].Q(1.0f + Math.max(0.0f, (float) kitHpfR.getFloat(r)) * 9.0f);
            if (kitHpfMorph instanceof ChuckArray hm && r < hm.size())
              kitHpfArr[r].morph(hm.getFloat(r));
            if (kitHpfFm != null && r < kitHpfFm.size())
              vm.setGlobalFloat(BridgeContract.G_KIT_HPF_FM + "_" + r, kitHpfFm.getFloat(r));
            if (kitHpfMode instanceof ChuckArray hm) {
              kitHpfArr[r].notchMode(r < hm.size() && hm.getInt(r) == 1);
            }
          }
          // Per-voice compressor Dyno param update (RMSFeedbackCompressor-correct formulas)
          if (kitComp != null && r < kitComp.length && kitComp[r] != null) {
            kitComp[r].compressor();
            float trackVol =
                trkLvl != null && r < trkLvl.size() ? (float) trkLvl.getFloat(r) : 0.5f;
            double th2 = 1.0 - 0.8 * trackVol;
            kitComp[r].thresh((float) Math.max(0.01, Math.min(0.99, th2)));
            float ka =
                kitCompA != null && r < kitCompA.size() ? (float) kitCompA.getFloat(r) : 0.0f;
            double attackMS2 = 0.5 + (Math.exp(2.0 * ka) - 1.0) * 10.0;
            kitComp[r].attackTime(attackMS2 * sr / 1000.0);
            float kr =
                kitCompR != null && r < kitCompR.size() ? (float) kitCompR.getFloat(r) : 0.0f;
            double releaseMS2 = 50.0 + (Math.exp(2.0 * kr) - 1.0) * 50.0;
            kitComp[r].releaseTime(releaseMS2 * sr / 1000.0);
            // Ratio from bridge array (default 0.5 → ~3:1)
            float kRatio =
                kitCompRatio != null && r < kitCompRatio.size()
                    ? (float) kitCompRatio.getFloat(r)
                    : 0.5f;
            double kfraction = 0.5 + kRatio / 2.0;
            double kratio = 1.0 / Math.max(0.0039, 1.0 - kfraction);
            kitComp[r].ratio((float) Math.max(2.0, Math.min(256.0, kratio)));
            // Parallel compression blend (dry/wet on Dyno)
            float kb =
                kitCompBlend != null && r < kitCompBlend.size()
                    ? (float) kitCompBlend.getFloat(r)
                    : 0.0f;
            kitComp[r].dryWet(kb);
            // Sidechain HPF approximation: raise effective threshold for low frequencies
            float hpfFreq =
                kitCompHpf != null && r < kitCompHpf.size() ? (float) kitCompHpf.getFloat(r) : 0.0f;
            if (hpfFreq > 0.01f) {
              float hpfAmount = hpfFreq * 0.5f; // max 6dB reduction in sensitivity
              float effectiveThresh =
                  (float) Math.max(0.01, Math.min(0.99, th2 * (1.0 + hpfAmount)));
              kitComp[r].thresh(effectiveThresh);
            }
          }
          // Per-voice ModFxUnit param update (every step for live modulation)
          if (kitModFx != null && r < kitModFx.length && kitModFx[r] != null) {
            int mfxType =
                kitModFxType != null && r < kitModFxType.size() ? (int) kitModFxType.getInt(r) : 0;
            double mfxRate =
                kitModFxRate != null && r < kitModFxRate.size() ? kitModFxRate.getFloat(r) : 0.3f;
            double mfxDepth =
                kitModFxDepth != null && r < kitModFxDepth.size()
                    ? kitModFxDepth.getFloat(r)
                    : 0.3f;
            double mfxFb =
                kitModFxFeedback != null && r < kitModFxFeedback.size()
                    ? kitModFxFeedback.getFloat(r)
                    : 0.0f;
            double mfxOffset =
                kitModFxOffset != null && r < kitModFxOffset.size()
                    ? kitModFxOffset.getFloat(r)
                    : 0.0f;
            kitModFx[r].setType(mfxType);
            kitModFx[r].setModFreq(mfxRate * Math.max(0.01, 1.0 + totalModV * 0.5));
            kitModFx[r].setModDepth(mfxDepth * Math.max(0.0, 1.0 + totalModV * 0.5));
            kitModFx[r].setFeedback(mfxFb);
            kitModFx[r].setOffset(mfxOffset);
          }
          kitEnv[r][0].keyOn();
          kitEnv[r][1].keyOn();
          kitEnv[r][2].keyOn();
          kitEnv[r][3].keyOn();
          long playLen = Math.abs(endPos - startPos);
          double durSec = playLen / (sampleRate() * Math.abs(rate));
          int trackIdx = r;
          int triggerGen = ++triggerGeneration[trackIdx];
          int genCapture = triggerGen;
          int stopR = r;
          SndBuf[] stopSubs = kitSub != null && r < kitSub.length ? kitSub[r] : null;
          int itLocal = it;
          double itDur = itLocal > 0 ? durSec / (itLocal + 1) : durSec;
          vm.spork(
              () -> {
                int subTriggers = Math.max(1, itLocal + 1);
                for (int si = 0; si < subTriggers; si++) {
                  if (si > 0) {
                    kit[trackIdx].pos(startPos);
                    kit[trackIdx].rate(reverse ? (float) -rate : (float) rate);
                    kitEnv[trackIdx][0].keyOn();
                    kitEnv[trackIdx][1].keyOn();
                    kitEnv[trackIdx][2].keyOn();
                    kitEnv[trackIdx][3].keyOn();
                    if (stopSubs != null) {
                      for (int us = 0; us < stopSubs.length; us++) {
                        if (stopSubs[us] != null) {
                          stopSubs[us].pos(startPos);
                          stopSubs[us].rate(reverse ? (float) -rate : (float) rate);
                        }
                      }
                    }
                  }
                  advance(second(itDur));
                  if (triggerGeneration[trackIdx] != genCapture) return;
                  if (si < subTriggers - 1) {
                    kitEnv[trackIdx][0].keyOff();
                    kitEnv[trackIdx][1].keyOff();
                    kitEnv[trackIdx][2].keyOff();
                    kitEnv[trackIdx][3].keyOff();
                    kit[trackIdx].rate(0);
                    if (stopSubs != null) {
                      for (int us = 0; us < stopSubs.length; us++) {
                        if (stopSubs[us] != null) stopSubs[us].rate(0);
                      }
                    }
                  }
                }
                kitEnv[trackIdx][0].keyOff();
                kitEnv[trackIdx][1].keyOff();
                kitEnv[trackIdx][2].keyOff();
                kitEnv[trackIdx][3].keyOff();
                kit[trackIdx].rate(0);
                if (stopSubs != null) {
                  for (int us = 0; us < stopSubs.length; us++) {
                    if (stopSubs[us] != null) stopSubs[us].rate(0);
                  }
                }
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
    private final double[] lfoSampleHold =
        new double[BridgeContract.LFO_COUNT]; // S&H: held value, updated when phase wraps
    private final double[] lfoRandWalk =
        new double[BridgeContract.LFO_COUNT]; // RANDOM_WALK: drifting value
    private final double[] lfoWarbler1 =
        new double[BridgeContract.LFO_COUNT]; // WARBLER: first-order smoothed
    private final double[] lfoWarbler2 =
        new double[BridgeContract.LFO_COUNT]; // WARBLER: second-order smoothed
    private int[] lastFilterRoute =
        new int[0]; // tracks per-voice filter routing mode for dynamic reconnection
    private final boolean[] voiceActive =
        new boolean[BridgeContract.TRACKS]; // true while envelope is in attack/decay/sustain
    private final String[] lastLoadedDx7Patch = new String[BridgeContract.TRACKS];
    private final long[] triggerGeneration =
        new long[BridgeContract.TRACKS]; // generation counter for voice stealing
    private long globalTriggerCounter =
        0; // monotonically increasing counter for voice age tracking

    SynthShred(ChuckVM vm, DelugeEngineDSL outer) {
      this.vm = vm;
      this.outer = outer;
    }

    private boolean isRunning() {
      return outer.isRunning();
    }

    private void synth_preview_shred(
        MorphingWavetable[] car, MorphingWavetable[] mod, Dx7Engine[] dx7, SwitchableAdsr[][] env) {
      ChuckEvent previewEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.E_PREVIEW);
      while (isRunning()) {
        advance(previewEvent);
        int r = (int) vm.getGlobalInt(BridgeContract.G_PREVIEW_TRACK);
        if (r < 0) continue;
        ChuckArray trackTypeArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_TYPE);
        if (trackTypeArr == null || trackTypeArr.getInt(r) != 1) continue;
        int u = r - (int) vm.getGlobalInt(BridgeContract.G_SYNTH_BASE);
        if (u < 0 || u >= car.length) continue;
        double previewPitch = vm.getGlobalFloat(BridgeContract.G_PREVIEW_PITCH);
        double f = outer.mtof(previewPitch + 60);
        if (dx7 != null && u < dx7.length && dx7[u] != null) {
          dx7[u].setFreq((float) f);
          dx7[u].noteOn();
          int rv = u;
          vm.spork(
              () -> {
                advance(ms(200));
                dx7[rv].noteOff();
              });
        } else {
          car[u].freq((float) f);
          if (mod != null && u < mod.length) mod[u].freq((float) f);
          env[u][0].gain(0.8f);
          env[u][0].keyOn();
          int rv = u;
          vm.spork(
              () -> {
                advance(ms(200));
                env[rv][0].keyOff();
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
        SwitchableAdsr[] env) {
      ChuckArray octArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ARP_OCTAVE);
      ChuckArray rateArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ARP_RATE);
      ChuckArray arpOn = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ARP_ON);
      ChuckArray fmRatio = (ChuckArray) vm.getGlobalObject(BridgeContract.G_FM_RATIO);
      ChuckArray synthModeArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_SYNTH_MODE);
      ChuckArray arpModeArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ARP_MODE);
      ChuckArray arpGateArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ARP_GATE);
      ChuckArray arpSyncArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ARP_SYNC_LEVEL);
      ChuckArray arpNoteModeArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ARP_NOTE_MODE);
      ChuckArray arpOctModeArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ARP_OCTAVE_MODE);
      ChuckArray arpRepeatArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ARP_STEP_REPEAT);
      ChuckArray arpRhythmArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ARP_RHYTHM);
      ChuckArray arpSeqLenArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ARP_SEQ_LENGTH);
      int octaves = (octArr != null) ? (int) octArr.getInt(v) : 1;
      if (octaves < 1) octaves = 1;
      int mode = (arpModeArr != null) ? (int) arpModeArr.getInt(v) : 0;
      int synthMode = (synthModeArr != null) ? (int) synthModeArr.getInt(v) : 0;
      int noteMode = (arpNoteModeArr != null) ? (int) arpNoteModeArr.getInt(v) : 0;
      int octMode = (arpOctModeArr != null) ? (int) arpOctModeArr.getInt(v) : 0;
      int stepRepeat = (arpRepeatArr != null) ? (int) arpRepeatArr.getInt(v) : 1;
      int rhythmIdx = (arpRhythmArr != null) ? (int) arpRhythmArr.getInt(v) : 0;
      int seqLen = (arpSeqLenArr != null) ? (int) arpSeqLenArr.getInt(v) : 8;
      ChuckArray octaveSpreadArr =
          (ChuckArray) vm.getGlobalObject(BridgeContract.G_ARP_OCTAVE_SPREAD);
      ChuckArray gateSpreadArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ARP_GATE_SPREAD);
      ChuckArray velSpreadArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ARP_VEL_SPREAD);
      ChuckArray ratchetArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ARP_RATCHET);
      ChuckArray noteProbArr =
          (ChuckArray) vm.getGlobalObject(BridgeContract.G_ARP_NOTE_PROBABILITY);
      ChuckArray chordPolyArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ARP_CHORD_POLY);
      ChuckArray chordProbArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ARP_CHORD_PROB);
      float octaveSpread = octaveSpreadArr != null ? (float) octaveSpreadArr.getFloat(v) : 0f;
      float gateSpread = gateSpreadArr != null ? (float) gateSpreadArr.getFloat(v) : 0f;
      float velSpread = velSpreadArr != null ? (float) velSpreadArr.getFloat(v) : 0f;
      int ratchet = ratchetArr != null ? (int) ratchetArr.getInt(v) : 0;
      if (stepRepeat < 1) stepRepeat = 1;
      if (seqLen < 1) seqLen = 1;
      if (ratchet < 0) ratchet = 0;
      if (ratchet > 4) ratchet = 4;

      double bpm = vm.getGlobalFloat(BridgeContract.G_BPM);

      // Determine step duration: syncLevel overrides rate
      double rate = (rateArr != null) ? rateArr.getFloat(v) : 1.0;
      int syncLevel = (arpSyncArr != null) ? (int) arpSyncArr.getInt(v) : 0;
      double stepDurSec;
      if (syncLevel > 0) {
        // syncLevel: 1=1/1, 2=1/2, 3=1/2T, 4=1/4, 5=1/4T, 6=1/8, 7=1/8T, 8=1/16, ...
        double baseDiv = Math.pow(2, ((syncLevel - 1) / 2));
        boolean triplet = (syncLevel % 2 == 0); // even syncLevels are triplet
        stepDurSec = 60.0 / bpm / 4.0 / baseDiv;
        if (triplet) stepDurSec *= 2.0 / 3.0;
      } else {
        stepDurSec = 60.0 / bpm / 4.0 / rate;
      }

      // Gate fraction from bridge (10%-100%)
      double gateFrac = (arpGateArr != null) ? arpGateArr.getFloat(v) : 0.5;
      if (gateFrac < 0.1) gateFrac = 0.1;
      if (gateFrac > 1.0) gateFrac = 1.0;

      // Pre-compute note order for the chord (always based on chord notes 0..octaves-1 per octave)
      int chordNotes = octaves; // notes in the chord
      int[] noteOrder;
      switch (noteMode) {
        case 1 -> { // DOWN
          noteOrder = new int[chordNotes];
          for (int i = 0; i < chordNotes; i++) noteOrder[i] = chordNotes - 1 - i;
        }
        case 2 -> { // UPDN
          noteOrder = new int[Math.max(1, chordNotes * 2 - 1)];
          for (int i = 0; i < chordNotes; i++) noteOrder[i] = i;
          for (int i = 1; i < chordNotes; i++) noteOrder[chordNotes - 1 + i] = chordNotes - 1 - i;
        }
        case 3 -> { // RAND
          noteOrder = new int[chordNotes];
          for (int i = 0; i < chordNotes; i++) noteOrder[i] = i;
        }
        case 4 -> { // WLK1 — biased random walk towards one end
          noteOrder = new int[seqLen];
          int cur = 0;
          for (int i = 0; i < seqLen; i++) {
            noteOrder[i] = cur;
            cur = Math.max(0, Math.min(chordNotes - 1, cur + (Math.random() < 0.5 ? -1 : 1)));
          }
        }
        case 5 -> { // WLK2 — random walk with larger leaps
          noteOrder = new int[seqLen];
          int cur = 0;
          for (int i = 0; i < seqLen; i++) {
            noteOrder[i] = cur;
            cur = Math.max(0, Math.min(chordNotes - 1, cur + (int) (Math.random() * 3 - 1)));
          }
        }
        case 6 -> { // WLK3 — pure random within chord
          noteOrder = new int[seqLen];
          for (int i = 0; i < seqLen; i++) noteOrder[i] = (int) (Math.random() * chordNotes);
        }
        case 7 -> { // PLAY — always note 0 (no octave cycling)
          noteOrder = new int[] {0};
        }
        case 8 -> { // PATT — use seqLen for pattern length, note 0 only
          noteOrder = new int[seqLen];
          for (int i = 0; i < seqLen; i++) noteOrder[i] = 0;
        }
        default -> { // UP
          noteOrder = new int[chordNotes];
          for (int i = 0; i < chordNotes; i++) noteOrder[i] = i;
        }
      }

      // Octave mode — determines octave offset per step
      int totalSteps = seqLen * stepRepeat;
      int[] octOffsets = new int[totalSteps];
      for (int s = 0; s < totalSteps; s++) {
        int step = s / stepRepeat;
        switch (octMode) {
          case 1 -> octOffsets[s] = octaves - 1 - (step % octaves); // DOWN
          case 2 -> { // UPDN
            int cycle = step % (octaves * 2 - 1);
            octOffsets[s] = (cycle < octaves) ? cycle : octaves * 2 - 2 - cycle;
          }
          case 3 -> octOffsets[s] = (step % 2 == 0) ? 0 : octaves - 1; // ALT — bounce extremes
          case 4 -> octOffsets[s] = (int) (Math.random() * octaves); // RAND
          default -> octOffsets[s] = step % octaves; // UP
        }
      }

      // Rhythm patterns — silence mask from firmware table
      boolean[] rhythmMask = getArpRhythmMask(rhythmIdx);

      // Build sequence: each entry = (noteIndex, octaveOffset, repeatCount)
      // noteIndex selects from noteOrder; actual midi = baseMidi + octaveOffset * 12 + chordNote *
      // 0 + noteIndexOffset
      int seqPos = 0;
      for (int s = 0; s < totalSteps; s++) {
        int noteIdx = noteOrder[s % noteOrder.length];
        int octOffset = octOffsets[s];
        int repeats = stepRepeat;

        // Rhythm silence check: if mask says silence for this step position,
        // skip the entire note-on but still advance the step duration
        boolean playStep = (rhythmMask == null) || (s < rhythmMask.length && rhythmMask[s]);
        // Note probability check: applied after rhythm (only on playable steps)
        if (playStep && noteProbArr != null) {
          playStep = Math.random() < noteProbArr.getFloat(v);
        }
        if (!playStep) {
          // Advance through the full step duration (silent)
          advance(samp(stepDurSec * sampleRate()));
          seqPos++;
          if (vm.getGlobalInt(BridgeContract.G_PLAY) == 0
              || (arpOn != null && arpOn.getInt(v) == 0)) return;
          continue;
        }

        for (int r = 0; r < repeats; r++) {
          // Ratchet sub-division: when ratchet > 0, sub-divide this repeat
          // into ratchet+1 mini-notes with tighter gate
          int ratchetCount = ratchet + 1;

          for (int rr = 0; rr < ratchetCount; rr++) {
            int midiNote = baseMidi + octOffset * 12 + noteIdx * 12;
            if (mode == 1) midiNote = baseMidi + octOffset * 12 + (chordNotes - 1 - noteIdx) * 12;
            else if (mode == 4) {
              // WALK mode — random-ish within range
              midiNote = baseMidi + (int) (Math.random() * octaves) * 12;
            }
            // mode 2 (UP_DOWN) and 3 (RANDOM) handled by the existing switch above, but noteMode
            // takes precedence
            // Actually mode field is the old direction mode — now noteMode handles it primarily.
            // For legacy compatibility if noteMode is UP (default) and octMode is UP (default):
            if (noteMode == 0 && octMode == 0) {
              switch (mode) {
                case 1 -> midiNote = baseMidi + (octaves - 1 - noteIdx) * 12;
                case 2 -> midiNote = baseMidi + noteIdx * 12;
                case 3 -> midiNote = baseMidi + (int) (Math.random() * octaves) * 12;
                default -> {}
              }
            }

            // Apply octaveSpread: randomize octave offset per note
            if (octaveSpread > 0f) {
              int spreadSemitones = (int) ((Math.random() * 2f - 1f) * octaveSpread * 12f);
              midiNote += spreadSemitones;
            }

            // Chord polyphony: intervals in half-steps (root, 3rd, 5th, 7th, 9th, 11th, 13th,
            // double-octave)
            int[] chordIntervals = {0, 4, 7, 10, 14, 17, 21, 24};
            int chordSize = 1;
            boolean chordTriggered = false;
            if (chordProbArr != null && chordPolyArr != null) {
              float chordProb = (float) chordProbArr.getFloat(v);
              if (chordProb > 0f && Math.random() < chordProb) {
                chordSize = Math.min((int) chordPolyArr.getInt(v), chordIntervals.length);
                if (chordSize < 1) chordSize = 1;
                chordTriggered = true;
              }
            }

            // Effective gate for this note (or per chord sub-note)
            float effectiveGate = (float) gateFrac;
            if (gateSpread > 0f) {
              effectiveGate *= (1f + (float) (Math.random() * 2f - 1f) * gateSpread);
              if (effectiveGate < 0.05f) effectiveGate = 0.05f;
              if (effectiveGate > 1.0f) effectiveGate = 1.0f;
            }
            // Ratchet mini-notes use tighter gate
            if (ratchetCount > 1) effectiveGate *= (0.5f / ratchetCount);

            double baseOnDurSec = stepDurSec * effectiveGate;
            // When chord triggered, divide the on-duration among chord notes
            double chordOnDurSec = chordTriggered ? baseOnDurSec / chordSize : baseOnDurSec;

            for (int ci = 0; ci < chordSize; ci++) {
              int chordMidi = midiNote + chordIntervals[Math.min(ci, chordIntervals.length - 1)];

              double f = outer.mtof(chordMidi);
              car.freq((float) f);
              if (synthMode == 1) {
                mod.freq((float) (f * (fmRatio != null ? fmRatio.getFloat(v) : 1.0)));
              } else {
                mod.gain(0.0f);
              }

              // Apply velSpread: randomize gain per note
              float velGain = (float) (gain * 0.8);
              if (velSpread > 0f) {
                velGain *= (1f + (float) (Math.random() * 2f - 1f) * velSpread);
                if (velGain < 0f) velGain = 0f;
              }
              env[0].gain(velGain);
              env[0].keyOn();
              env[1].keyOn();
              env[2].keyOn();
              env[3].keyOn();

              ChuckDuration onDur = samp(chordOnDurSec * sampleRate());
              advance(onDur);
              env[0].keyOff();
              env[1].keyOff();
              env[2].keyOff();
              env[3].keyOff();

              // Tiny gap between chord sub-notes (1ms) to create separation
              if (ci < chordSize - 1) advance(samp(0.001 * sampleRate()));
            }

            // Rest duration — ratchet notes squeeze into the same step slot
            double restFrac = 1.0 / ratchetCount;
            if (rr == ratchetCount - 1) {
              // Last mini-note: rest until end of step
              restFrac = 1.0 - effectiveGate * ratchetCount;
              if (restFrac < 0) restFrac = 0;
            }
            double effectiveRestDur = stepDurSec * restFrac;
            if (effectiveRestDur < 0) effectiveRestDur = 0;
            if (effectiveRestDur > 0) advance(samp(effectiveRestDur * sampleRate()));

            seqPos++;
            if (vm.getGlobalInt(BridgeContract.G_PLAY) == 0
                || (arpOn != null && arpOn.getInt(v) == 0)) return;
          }
        }
      }
    }

    /** Firmware-accurate rhythm pattern table. Index 0 = all play (no silences). */
    private static final boolean[][] ARP_RHYTHM_PATTERNS = {
      {true}, //  0: None
      {true, false, false}, //  1: 0--
      {true, true, false}, //  2: 00-
      {true, false, true}, //  3: 0-0
      {true, false, true, true}, //  4: 0-00
      {true, true, false, false}, //  5: 00--
      {true, true, true, false}, //  6: 000-
      {true, false, false, true}, //  7: 0--0
      {true, true, false, true}, //  8: 00-0
      {true, false, false, false, false}, //  9: 0----
      {true, false, true, true, true}, // 10: 0-000
      {true, true, false, false, false}, // 11: 00---
      {true, true, true, true, false}, // 12: 0000-
      {true, false, false, false, true}, // 13: 0---0
      {true, true, false, true, true}, // 14: 00-00
      {true, false, true, false, false}, // 15: 0-0--
      {true, true, true, false, true}, // 16: 000-0
      {true, false, false, true, false}, // 17: 0--0-
      {true, false, false, true, true}, // 18: 0--00
      {true, true, true, false, false}, // 19: 000--
      {true, true, false, false, true}, // 20: 00--0
      {true, false, true, true, false}, // 21: 0-00-
      {true, true, false, true, false}, // 22: 00-0-
      {true, false, true, false, true}, // 23: 0-0-0
      {true, false, false, false, false, false}, // 24: 0-----
      {true, false, true, true, true, true}, // 25: 0-0000
      {true, true, false, false, false, false}, // 26: 00----
      {true, true, true, true, true, false}, // 27: 00000-
      {true, false, false, false, false, true}, // 28: 0----0
      {true, true, false, true, true, true}, // 29: 00-000
      {true, false, true, false, false, false}, // 30: 0-0---
      {true, true, true, true, false, true}, // 31: 0000-0
      {true, false, false, false, true, false}, // 32: 0---0-
      {true, true, true, false, true, true}, // 33: 000-00
      {true, false, false, true, true, true}, // 34: 0--000
      {true, true, true, false, false, false}, // 35: 000---
      {true, true, true, true, false, false}, // 36: 0000--
      {true, false, false, false, true, true}, // 37: 0---00
      {true, true, false, false, true, true}, // 38: 00--00
      {true, false, true, true, false, false}, // 39: 0-00--
      {true, true, true, false, false, true}, // 40: 000--0
      {true, false, false, true, true, false}, // 41: 0--00-
      {true, false, true, false, true, true}, // 42: 0-0-00
      {true, true, false, true, false, false}, // 43: 00-0--
      {true, true, true, false, true, false}, // 44: 000-0-
      {true, false, false, true, false, true}, // 45: 0--0-0
      {true, false, true, true, true, false}, // 46: 0-000-
      {true, true, false, false, false, true}, // 47: 00---0
      {true, true, false, false, true, false}, // 48: 00--0-
      {true, false, true, false, false, true}, // 49: 0-0--0
      {true, true, false, true, false, true}, // 50: 00-0-0
    };

    /**
     * Get the rhythm silence mask for a given pattern index. Returns the pattern array (true=play,
     * false=silence), or null for index 0 (all play).
     */
    private static boolean[] getArpRhythmMask(int idx) {
      if (idx < 0 || idx >= ARP_RHYTHM_PATTERNS.length) return null;
      if (idx == 0) return null; // null means "all play"
      return ARP_RHYTHM_PATTERNS[idx];
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
      final SwitchableFilter[][] filRefHolder = new SwitchableFilter[1][];
      final HPF[][] hpfRefHolder = new HPF[1][];
      final SwitchableAdsr[][][] envRefHolder = new SwitchableAdsr[1][][];
      final Pan2[][] panRefHolder = new Pan2[1][];
      final Gain[][] sDsendRefHolder = new Gain[1][];
      final Gain[][] sRsendRefHolder = new Gain[1][];
      final ModFxUnit[][] modFxRefHolder = new ModFxUnit[1][];
      final Dyno[][] compRefHolder = new Dyno[1][];
      final ChuckUGen[][] srcRefHolder = new ChuckUGen[1][];
      final Gain[][] routingMixRefHolder = new Gain[1][];
      final int[] synthBaseHolder = new int[] {0};
      final int[] maxSynthBridgeRowHolder = new int[] {0};

      // Unison: per-slot sub-voice MorphingWavetable arrays + summing Gain
      final int MAX_UNISON = 8;
      final MorphingWavetable[][][] unisonSubRefHolder = new MorphingWavetable[1][][];
      final Gain[][] unisonSummerRefHolder = new Gain[1][];

      java.util.function.Consumer<Gain> doInit =
          (bus) -> {
            ChuckArray trackTypeInit = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_TYPE);
            int sb = -1, mx = -1;
            for (int i = 0; i < BridgeContract.TRACKS; i++) {
              if (trackTypeInit != null && trackTypeInit.getInt(i) == 1) {
                if (sb < 0) sb = i;
                mx = i;
              }
            }
            if (sb < 0) sb = 0;
            if (mx < sb) mx = sb;
            int total = mx - sb + 1;
            synthBaseHolder[0] = sb;
            vm.setGlobalInt(BridgeContract.G_SYNTH_BASE, (long) sb);
            maxSynthBridgeRowHolder[0] = mx;

            ChuckArray algoArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_SYNTH_ALGO);
            ChuckUGen[] src = new ChuckUGen[total];
            Gain[] routingMix = new Gain[total];
            MorphingWavetable[] car = new MorphingWavetable[total];
            MorphingWavetable[] mod = new MorphingWavetable[total];
            Dx7Engine[] dx7 = new Dx7Engine[total];
            SwitchableFilter[] fil = new SwitchableFilter[total];
            HPF[] hpf = new HPF[total];
            SwitchableAdsr[][] env = new SwitchableAdsr[total][4];
            Pan2[] pan = new Pan2[total];
            Gain[] sDsend = new Gain[total];
            Gain[] sRsend = new Gain[total];
            ModFxUnit[] modFx = new ModFxUnit[total];
            Dyno[] compArr = new Dyno[total];
            MorphingWavetable[][] unisonSub = new MorphingWavetable[total][];
            Gain[] unisonSummer = new Gain[total];
            for (int i = 0; i < total; i++) {
              int bridgeRow = sb + i;
              int algo = algoArr != null ? (int) algoArr.getInt(bridgeRow) : 0;
              fil[i] = new SwitchableFilter(sr, vm);
              hpf[i] = new HPF(sr);
              env[i][0] = new SwitchableAdsr(sr, vm);
              env[i][1] = new SwitchableAdsr(sr, vm);
              env[i][2] = new SwitchableAdsr(sr, vm);
              env[i][3] = new SwitchableAdsr(sr, vm);
              pan[i] = new Pan2();
              sDsend[i] = new Gain();
              sRsend[i] = new Gain();
              modFx[i] = new ModFxUnit(sr);
              compArr[i] = new Dyno(sr);
              Gain rm = new Gain();
              routingMix[i] = rm;
              rm.gain(1.0f);
              if (algo >= 10) {
                src[i] = outer.createStkUGen(algo, sr);
                src[i]
                    .chuck(fil[i])
                    .chuck(hpf[i])
                    .chuck(rm)
                    .chuck(env[i][0])
                    .chuck(pan[i])
                    .chuck(modFx[i])
                    .chuck(compArr[i])
                    .chuck(bus);
              } else {
                String dx7PatchStr = (String) vm.getGlobalObject("g_dx7_patch_" + bridgeRow);
                if (dx7PatchStr != null && !dx7PatchStr.isEmpty()) {
                  dx7[i] = new Dx7Engine(sr);
                  dx7[i].loadPatch(org.chuck.audio.util.Dx7Patch.fromHex(dx7PatchStr));
                  // Read engine type (−1=AUTO, 0=MODERN, 1=VINTAGE) from bridge
                  ChuckArray dx7EngineArr =
                      (ChuckArray) vm.getGlobalObject(BridgeContract.G_DX7_ENGINE_TYPE);
                  if (dx7EngineArr != null) {
                    int engineTypeVal = (int) dx7EngineArr.getInt(i);
                    dx7[i].setForceVintage(engineTypeVal);
                  }
                  // DX7 bypasses env[i][0] (DelugeAdsr) — per-operator DX7 envelopes handle all
                  // amplitude.
                  // The routingMix Gain (rm) applies track-level volume without ADSR shaping.
                  dx7[i]
                      .chuck(fil[i])
                      .chuck(hpf[i])
                      .chuck(rm)
                      .chuck(pan[i])
                      .chuck(modFx[i])
                      .chuck(compArr[i])
                      .chuck(bus);
                  src[i] = dx7[i];
                } else {
                  car[i] = new MorphingWavetable(sr);
                  car[i].setTables(DelugeEngineDSL.WAVE_TABLES);
                  mod[i] = new MorphingWavetable(sr);
                  mod[i].setTables(DelugeEngineDSL.WAVE_TABLES);
                  // Unison: create summing Gain that sits between car[i] and fil[i]
                  unisonSummer[i] = new Gain();
                  car[i].chuck(unisonSummer[i]);
                  // Create up to MAX_UNISON-1 sub-voice oscillators chucked into the summing gain
                  // (voice 0 = car[i] itself, voices 1..MAX_UNISON-1 = sub-voices)
                  MorphingWavetable[] subs = new MorphingWavetable[MAX_UNISON - 1];
                  for (int us = 0; us < subs.length; us++) {
                    subs[us] = new MorphingWavetable(sr);
                    subs[us].setTables(DelugeEngineDSL.WAVE_TABLES);
                    subs[us].gain(1.0f);
                    subs[us].chuck(unisonSummer[i]);
                  }
                  unisonSub[i] = subs;
                  // Normal chain: car -> unisonSummer -> fil -> hpf -> rm -> env -> pan -> ...
                  mod[i].chuck(car[i]);
                  unisonSummer[i]
                      .chuck(fil[i])
                      .chuck(hpf[i])
                      .chuck(rm)
                      .chuck(env[i][0])
                      .chuck(pan[i])
                      .chuck(modFx[i])
                      .chuck(compArr[i])
                      .chuck(bus);
                  src[i] = car[i];
                }
              }
              pan[i]
                  .chuck(sDsend[i])
                  .chuck((ChuckUGen) vm.getGlobalObject(BridgeContract.G_DELAY_IN));
              pan[i]
                  .chuck(sRsend[i])
                  .chuck((ChuckUGen) vm.getGlobalObject(BridgeContract.G_REVERB_IN));
              fil[i].reset();
              fil[i].freq(5000);
              hpf[i].freq(20.0f);
              env[i][0].set(0.05, 0.2, 0.5, 0.3);
              env[i][0].forceMute();
              env[i][1].set(0.05, 0.2, 0.5, 0.3);
              env[i][1].forceMute();
              env[i][2].set(0.05, 0.2, 0.5, 0.3);
              env[i][2].forceMute();
              env[i][3].set(0.05, 0.2, 0.5, 0.3);
              env[i][3].forceMute();
              sDsend[i].gain(0.0f);
              sRsend[i].gain(0.15f);
            }
            carRefHolder[0] = car;
            modRefHolder[0] = mod;
            dx7RefHolder[0] = dx7;
            filRefHolder[0] = fil;
            hpfRefHolder[0] = hpf;
            envRefHolder[0] = env;
            panRefHolder[0] = pan;
            sDsendRefHolder[0] = sDsend;
            sRsendRefHolder[0] = sRsend;
            modFxRefHolder[0] = modFx;
            srcRefHolder[0] = src;
            routingMixRefHolder[0] = routingMix;
            compRefHolder[0] = compArr;
            unisonSubRefHolder[0] = unisonSub;
            unisonSummerRefHolder[0] = unisonSummer;
            lastFilterRoute = new int[total]; // initialize routing tracking array
          };

      doInit.accept(synthBus);
      vm.spork(
          () ->
              synth_preview_shred(
                  carRefHolder[0], modRefHolder[0], dx7RefHolder[0], envRefHolder[0]));

      ChuckEvent tickEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.E_TICK);
      long lastStep = -1;

      while (isRunning()) {
        advance(tickEvent);
        if (vm.getGlobalInt(BridgeContract.G_RELOAD) > 0) {
          vm.setGlobalInt(BridgeContract.G_RELOAD, 0L);
          if (vm.getLogLevel() >= 1) vm.print("[synth_shred] re-init triggered\n");
          advance(ms(1));
          doInit.accept(synthBus);
          vm.spork(
              () ->
                  synth_preview_shred(
                      carRefHolder[0], modRefHolder[0], dx7RefHolder[0], envRefHolder[0]));
          lastStep = -1;
          continue;
        }
        if (vm.getGlobalInt(BridgeContract.G_PLAY) == 0) {
          lastStep = -1;
          for (SwitchableAdsr[] ev : envRefHolder[0]) {
            ev[0].keyOff();
            ev[1].keyOff();
            ev[2].keyOff();
            ev[3].keyOff();
          }
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
        SwitchableFilter[] fil = filRefHolder[0];
        HPF[] hpfArr = hpfRefHolder[0];
        SwitchableAdsr[][] env = envRefHolder[0];
        Pan2[] pan = panRefHolder[0];
        Gain[] sDsend = sDsendRefHolder[0];
        Gain[] sRsend = sRsendRefHolder[0];
        ChuckUGen[] src = srcRefHolder[0];
        Gain[] routingMix = routingMixRefHolder[0];
        MorphingWavetable[][] unisonSub = unisonSubRefHolder[0];
        Gain[] unisonSummer = unisonSummerRefHolder[0];

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
        ChuckArray filterDriveArr =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_CHAR_FILTER_DRIVE);
        ChuckArray filterNotchArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_FILTER_NOTCH);
        ChuckArray filterRouteArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_FILTER_ROUTE);
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
        ChuckArray hpfMorphArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_HPF_MORPH);
        ChuckArray hpfModeArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_HPF_MODE);
        ChuckArray hpfFmArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_HPF_FM);
        ChuckArray polyphonyArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_POLYPHONY);
        ChuckArray car1FbArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_CARRIER1_FB);
        ChuckArray sHpfFreq = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_HPF_FREQ);
        ChuckArray sHpfRes = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_HPF_RES);
        ChuckArray sModRate = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_MOD_RATE);
        ChuckArray sModDepth = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_MOD_DEPTH);
        ChuckArray sOscAVol = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_OSC_A_VOL);
        ChuckArray sOscBVol = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_OSC_B_VOL);
        ChuckArray sNoiseVol = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_NOISE_VOL);
        ChuckArray sPitchArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_PITCH);
        // ── Extended per-step automation arrays (36 new) ──
        ChuckArray sStepVolume = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_VOLUME);
        ChuckArray sStepEnv0A = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_ENV_0_ATTACK);
        ChuckArray sStepEnv0D = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_ENV_0_DECAY);
        ChuckArray sStepEnv0S =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_ENV_0_SUSTAIN);
        ChuckArray sStepEnv0R =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_ENV_0_RELEASE);
        ChuckArray sStepEnv1A = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_ENV_1_ATTACK);
        ChuckArray sStepEnv1D = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_ENV_1_DECAY);
        ChuckArray sStepEnv1S =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_ENV_1_SUSTAIN);
        ChuckArray sStepEnv1R =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_ENV_1_RELEASE);
        ChuckArray sStepEnv2A = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_ENV_2_ATTACK);
        ChuckArray sStepEnv2D = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_ENV_2_DECAY);
        ChuckArray sStepEnv2S =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_ENV_2_SUSTAIN);
        ChuckArray sStepEnv2R =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_ENV_2_RELEASE);
        ChuckArray sStepEnv3A = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_ENV_3_ATTACK);
        ChuckArray sStepEnv3D = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_ENV_3_DECAY);
        ChuckArray sStepEnv3S =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_ENV_3_SUSTAIN);
        ChuckArray sStepEnv3R =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_ENV_3_RELEASE);
        ChuckArray sStepLfo0Rate =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_LFO_0_RATE);
        ChuckArray sStepLfo0Depth =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_LFO_0_DEPTH);
        ChuckArray sStepLfo1Rate =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_LFO_1_RATE);
        ChuckArray sStepLfo1Depth =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_LFO_1_DEPTH);
        ChuckArray sStepLfo2Rate =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_LFO_2_RATE);
        ChuckArray sStepLfo2Depth =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_LFO_2_DEPTH);
        ChuckArray sStepLfo3Rate =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_LFO_3_RATE);
        ChuckArray sStepLfo3Depth =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_LFO_3_DEPTH);
        ChuckArray sStepArpRate = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_ARP_RATE);
        ChuckArray sStepArpGate = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_ARP_GATE);
        ChuckArray sStepFmAmt = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_FM_AMOUNT);
        ChuckArray sStepFmRatio = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_FM_RATIO);
        ChuckArray sStepModFxFeedback =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_MOD_FX_FEEDBACK);
        ChuckArray sStepCompAttack =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_COMP_ATTACK);
        ChuckArray sStepCompRelease =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_COMP_RELEASE);
        ChuckArray sStepPortamento =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_PORTAMENTO);
        ChuckArray sStepStutter = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_STUTTER);
        ChuckArray sStepBitcrush = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_BITCRUSH);
        ChuckArray sStepSrr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_SRR);
        ChuckArray sStepFilterMode =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_FILTER_MODE);
        ChuckArray sStepDelay = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_DELAY);
        ChuckArray sStepReverb = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_REVERB);
        ChuckArray notePitchArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PITCH);

        // Per-track default arrays (used as baseline when step automation is absent)
        ChuckArray sPanDef = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PAN);
        ChuckArray sOsc2Type = (ChuckArray) vm.getGlobalObject(BridgeContract.G_OSC2_TYPE);
        ChuckArray sMod1Fb = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MOD1_FB);
        ChuckArray sMod2Amt = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MOD2_AMT);
        ChuckArray sMod2Fb = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MOD2_FB);
        ChuckArray sCarrier2Fb = (ChuckArray) vm.getGlobalObject(BridgeContract.G_CARRIER2_FB);
        ChuckArray sOscMix = (ChuckArray) vm.getGlobalObject(BridgeContract.G_OSC_MIX);
        ChuckArray sNoiseVolDef = (ChuckArray) vm.getGlobalObject(BridgeContract.G_NOISE_VOL);
        ChuckArray sUnisonNum = (ChuckArray) vm.getGlobalObject(BridgeContract.G_UNISON_NUM);
        ChuckArray sUnisonDetune = (ChuckArray) vm.getGlobalObject(BridgeContract.G_UNISON_DETUNE);
        ChuckArray sUnisonSpread = (ChuckArray) vm.getGlobalObject(BridgeContract.G_UNISON_SPREAD);
        ChuckArray sModFxType = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MOD_FX_TYPE);
        ChuckArray sModFxRate = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MOD_FX_RATE);
        ChuckArray sModFxDepth = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MOD_FX_DEPTH);
        ChuckArray sModFxFeedback =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_MOD_FX_FEEDBACK);
        ChuckArray sModFxOffset = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MOD_FX_OFFSET);
        ModFxUnit[] modFx = modFxRefHolder[0];
        Dyno[] compArr = compRefHolder[0];
        ChuckArray sPortamento = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PORTAMENTO);
        ChuckArray sEqBass = (ChuckArray) vm.getGlobalObject(BridgeContract.G_EQ_BASS);
        ChuckArray sEqTreble = (ChuckArray) vm.getGlobalObject(BridgeContract.G_EQ_TREBLE);
        ChuckArray sStutterRate = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STUTTER_RATE);
        ChuckArray sSampleRateRed =
            (ChuckArray) vm.getGlobalObject(BridgeContract.G_SAMPLE_RATE_RED);
        ChuckArray sBitCrush = (ChuckArray) vm.getGlobalObject(BridgeContract.G_BITCRUSH);
        ChuckArray sCompAttack = (ChuckArray) vm.getGlobalObject(BridgeContract.G_COMP_ATTACK);
        ChuckArray sCompRelease = (ChuckArray) vm.getGlobalObject(BridgeContract.G_COMP_RELEASE);
        ChuckArray sCompBlend = (ChuckArray) vm.getGlobalObject(BridgeContract.G_COMP_BLEND);
        ChuckArray sCompHpf = (ChuckArray) vm.getGlobalObject(BridgeContract.G_COMP_SIDECHAIN_HPF);
        ChuckArray sCompRatio = (ChuckArray) vm.getGlobalObject(BridgeContract.G_COMP_RATIO);
        ChuckArray maxVoicesArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MAX_VOICES);
        ChuckArray sLfoSync = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_SYNC_LEVEL);
        ChuckArray sWaveIndex = (ChuckArray) vm.getGlobalObject(BridgeContract.G_WAVE_INDEX);

        double synthBpm = vm.getGlobalFloat(BridgeContract.G_BPM);
        if (synthBpm < 1.0) synthBpm = 120.0;
        for (int r = synthBase; r <= maxSynthBridgeRow; r++) {
          int u = r - synthBase;
          if (trackType != null && trackType.getInt(r) != 1) continue;

          // Live DX7 6-operator FM Voice Patch parameters real-time hot-reloading check
          if (dx7 != null && u < dx7.length && dx7[u] != null) {
            String currentPatch = (String) vm.getGlobalObject("g_dx7_patch_" + r);
            if (currentPatch != null && !currentPatch.equals(lastLoadedDx7Patch[r])) {
              lastLoadedDx7Patch[r] = currentPatch;
              dx7[u].loadPatch(org.chuck.audio.util.Dx7Patch.fromHex(currentPatch));
            }
          }

          int algo = algoArrLive != null ? (int) algoArrLive.getInt(r) : 0;
          double[] lfoVals = new double[BridgeContract.LFO_COUNT];
          for (int l = 0; l < BridgeContract.LFO_COUNT; l++) {
            int syncLvl = sLfoSync != null ? (int) sLfoSync.getInt(l) : 0;
            double rawRate = lfoRate != null ? lfoRate.getFloat(l) : 1.0;
            double rate = syncLvl > 0 ? lfoSyncRate(syncLvl, synthBpm) : rawRate;
            double depth = lfoDepth != null ? lfoDepth.getFloat(l) : 0.0;
            int type = lfoType != null ? (int) lfoType.getInt(l) : 0;
            if (depth == 0.0) {
              lfoVals[l] = 0.0;
              continue;
            }
            lfoPhase[l] = (lfoPhase[l] + rate / sr) % 1.0;
            double raw =
                switch (type) {
                  case 1 -> 2.0 * lfoPhase[l] - 1.0;
                  case 2 -> lfoPhase[l] < 0.5 ? 1.0 : -1.0;
                  case 3 ->
                      lfoPhase[l] < 0.5 ? (4.0 * lfoPhase[l] - 1.0) : (3.0 - 4.0 * lfoPhase[l]);
                  case 4 -> { // S_AND_H: sample new value when phase wraps
                    if (lfoPhase[l] < rate / sr) lfoSampleHold[l] = 2.0 * Math.random() - 1.0;
                    yield lfoSampleHold[l];
                  }
                  case 5 -> { // RANDOM_WALK: gradual random drift
                    lfoRandWalk[l] += (Math.random() - 0.5) * 0.02;
                    lfoRandWalk[l] = Math.max(-1.0, Math.min(1.0, lfoRandWalk[l]));
                    yield lfoRandWalk[l];
                  }
                  case 6 -> { // WARBLER: second-order smoothed random walk
                    double noise = (Math.random() - 0.5) * 0.1;
                    lfoWarbler1[l] += noise;
                    lfoWarbler1[l] *= 0.99; // leaky integrator
                    lfoWarbler2[l] +=
                        (lfoWarbler1[l] - lfoWarbler2[l]) * 0.2; // second-order smoothing
                    lfoWarbler2[l] = Math.max(-1.0, Math.min(1.0, lfoWarbler2[l]));
                    yield lfoWarbler2[l];
                  }
                  default -> Math.sin(2.0 * Math.PI * lfoPhase[l]);
                };
            lfoVals[l] = raw * depth;
          }
          double lfoF = 0,
              lfoQ = 0,
              lfoP = 0,
              lfoPit = 0,
              lfoV = 0,
              lfoFm = 0,
              lfoOscA = 0,
              lfoOscB = 0,
              lfoNoise = 0,
              lfoMfxR = 0,
              lfoMfxD = 0;
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
              case 6 -> lfoOscA += lv;
              case 7 -> lfoOscB += lv;
              case 8 -> lfoNoise += lv;
              case 9 -> lfoMfxR += lv;
              case 10 -> lfoMfxD += lv;
            }
          }

          int clipIdx = curClipArr != null ? (int) curClipArr.getInt(r) : 0;
          ChuckArray clipPat = outer.getClipArray(BridgeContract.G_PATTERN, clipIdx);
          ChuckArray clipVel = outer.getClipArray(BridgeContract.G_VELOCITY, clipIdx);
          ChuckArray clipProb = outer.getClipArray(BridgeContract.G_PROBABILITY, clipIdx);
          ChuckArray clipFill = outer.getClipArray(BridgeContract.G_FILL, clipIdx);
          ChuckArray clipIterance = outer.getClipArray(BridgeContract.G_ITERANCE, clipIdx);
          int len = trkLen != null ? (int) Math.max(1, trkLen.getInt(r)) : BridgeContract.STEPS;
          int step = (int) (currentStep % len);
          int idx = r * BridgeContract.STEPS + step;
          if (algo < 10 && oscType != null && car[u] != null) {
            double baseIdx = oscType.getInt(r);
            double wi =
                (sWaveIndex != null && r < sWaveIndex.size()) ? sWaveIndex.getFloat(r) : 0.0;
            car[u].index(baseIdx + wi);
          }
          if (clipPat.getInt(idx) == 0) {
            env[u][0].keyOff();
            env[u][1].keyOff();
            env[u][2].keyOff();
            env[u][3].keyOff();
            continue;
          }
          if (mute.getInt(r) != 0) {
            env[u][0].keyOff();
            env[u][1].keyOff();
            env[u][2].keyOff();
            env[u][3].keyOff();
            continue;
          }

          // Read current envelope output values for hard-wired + patch cable modulation
          double envMod0 = env[u][0].lastOut; // volume envelope
          double envMod1 = env[u][1].lastOut; // filter envelope
          double envMod2 = env[u][2].lastOut; // pitch/aux envelope
          double envMod3 = env[u][3].lastOut; // aux envelope

          // ── Hard-wired Env 2-4 → DSP modulation (before patch cables; depths stored as per-track
          // floats) ──
          double envToF = 0, envToQ = 0, envToPit = 0, envToV = 0;
          envToF +=
              envMod1 * (float) vm.getGlobalFloat(BridgeContract.G_ENV2_FILTER_DEPTH + "_" + r);
          envToPit +=
              envMod2 * (float) vm.getGlobalFloat(BridgeContract.G_ENV3_PITCH_DEPTH + "_" + r);
          envToV +=
              envMod2 * (float) vm.getGlobalFloat(BridgeContract.G_ENV3_VOLUME_DEPTH + "_" + r);
          envToF +=
              envMod3 * (float) vm.getGlobalFloat(BridgeContract.G_ENV4_FILTER_DEPTH + "_" + r);
          envToPit +=
              envMod3 * (float) vm.getGlobalFloat(BridgeContract.G_ENV4_PITCH_DEPTH + "_" + r);
          envToV +=
              envMod3 * (float) vm.getGlobalFloat(BridgeContract.G_ENV4_VOLUME_DEPTH + "_" + r);

          // ── Patch cable modulation evaluation (after clipPat/clipVel/idx available) ──
          double pcModF = 0, pcModQ = 0, pcModP = 0, pcModPit = 0, pcModV = 0;
          double pcModOscA = 0, pcModOscB = 0, pcModNoise = 0, pcModMfxR = 0, pcModMfxD = 0;
          ChuckArray pcCountArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PC_COUNT);
          ChuckArray pcSourceArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PC_SOURCE);
          ChuckArray pcDestArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PC_DEST);
          ChuckArray pcAmtArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PC_AMOUNT);
          ChuckArray pcPolArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PC_POLARITY);
          if (pcCountArr != null) {
            long cableCount = pcCountArr.getInt(r);
            int base = r * BridgeContract.MAX_CABLES_PER_TRACK;
            double[] envCur = new double[] {envMod0, envMod1, envMod2, envMod3};
            double curVel =
                clipPat.getInt(idx) != 0 && clipVel != null ? clipVel.getFloat(idx) : 0.0;
            double curNoteMidi = ((24 - 1) - (r - synthBase)) + 60;
            for (int c = 0; c < cableCount; c++) {
              int ci = base + c;
              int srcIdx = pcSourceArr != null ? (int) pcSourceArr.getInt(ci) : -1;
              int dstIdx = pcDestArr != null ? (int) pcDestArr.getInt(ci) : -1;
              if (srcIdx < 0 || dstIdx < 0) continue;
              double rawAmt = pcAmtArr != null ? pcAmtArr.getFloat(ci) : 0.0;
              int pol = pcPolArr != null ? (int) pcPolArr.getInt(ci) : 0;
              double srcVal = 0;
              switch (srcIdx) {
                case 0 -> srcVal = curVel;
                case 1, 2, 3, 4 -> srcVal = envCur[srcIdx - 1];
                case 5, 6, 7, 8 -> srcVal = lfoVals[srcIdx - 5];
                case 9 -> srcVal = 0.0; // aftertouch
                case 10 -> srcVal = (curNoteMidi - 60) / 48.0; // note -> -1..+1
                case 11 -> srcVal = Math.random() * 2.0 - 1.0; // random
                case 12 -> srcVal = 0.0; // sidechain
              }
              double effective = srcVal * rawAmt;
              switch (dstIdx) {
                case 0 -> pcModV += effective; // volume
                case 1 -> pcModP += effective; // pan
                case 2 -> pcModF += effective; // lpfFrequency
                case 3 -> pcModQ += effective; // lpfResonance
                case 4 -> pcModOscA += effective; // oscAVolume
                case 5 -> pcModOscB += effective; // oscBVolume
                case 6 -> pcModPit += effective; // pitch
                case 7 -> pcModNoise += effective; // noiseVolume
                case 8 -> pcModMfxR += effective; // modFxRate
                case 9 -> pcModMfxD += effective; // modFxDepth
              }
            }
          }
          // LFO step modulation: scale per-track LFO output by step automation
          // (computed first so totalMod* below uses the correct value)
          double stepLfoMod = 1.0;
          if (sStepLfo0Rate != null
              || sStepLfo1Rate != null
              || sStepLfo2Rate != null
              || sStepLfo3Rate != null) {
            // Step LFO modulation applies as an additive offset to the per-track LFO value
            double lfoStepMod = 0;
            for (int l = 0; l < BridgeContract.LFO_COUNT; l++) {
              double stepDepthAdd =
                  switch (l) {
                    case 0 -> sStepLfo0Depth != null ? sStepLfo0Depth.getFloat(idx) : 0.0;
                    case 1 -> sStepLfo1Depth != null ? sStepLfo1Depth.getFloat(idx) : 0.0;
                    case 2 -> sStepLfo2Depth != null ? sStepLfo2Depth.getFloat(idx) : 0.0;
                    case 3 -> sStepLfo3Depth != null ? sStepLfo3Depth.getFloat(idx) : 0.0;
                    default -> 0.0;
                  };
              // Apply step LFO modulation to the accumulated lfoVals
              lfoStepMod += stepDepthAdd;
            }
            // Scale total LFO output by step modulation (normalized to LFO_COUNT)
            stepLfoMod = 1.0 + lfoStepMod / BridgeContract.LFO_COUNT;
          }
          // Apply step LFO modulation as a global scalar to all LFO-derived modulation values
          double slm = stepLfoMod;
          // Hard-wired envelope modulation values are additive, not scaled by step LFO
          double totalModF = (lfoF + pcModF) * slm + envToF;
          double totalModQ = (lfoQ + pcModQ) * slm + envToQ;
          double totalModP = (lfoP + pcModP) * slm;
          double totalModPit = (lfoPit + pcModPit * 12.0) * slm + envToPit;
          double totalModV = (lfoV + pcModV) * slm + envToV;
          double totalModOscA = (lfoOscA + pcModOscA) * slm;
          double totalModOscB = (lfoOscB + pcModOscB) * slm;
          double totalModNoise = (lfoNoise + pcModNoise) * slm;
          double totalModMfxR = (lfoMfxR + pcModMfxR) * slm;
          double totalModMfxD = (lfoMfxD + pcModMfxD) * slm;
          double totalModFm = lfoFm * slm;

          sDsend[u].gain(dlySnd != null ? (float) dlySnd.getFloat(r) : 0.0f);
          sRsend[u].gain(revSnd != null ? (float) revSnd.getFloat(r) : 0.15f);
          double tf =
              (gFil.getFloat(r * 2) + sFil.getFloat(idx)) * 10000.0 + 100.0 + totalModF * 5000.0;
          double tq = (gFil.getFloat(r * 2 + 1) + sRes.getFloat(idx)) * 4.0 + 1.0 + totalModQ * 3.0;
          double tp = masterPan + (sPan != null ? sPan.getFloat(idx) : 0.0) + totalModP;
          fil[u].freq((float) Math.max(20.0, Math.min(20000.0, tf)));
          fil[u].Q((float) Math.max(1.0, Math.min(10.0, tq)));
          float hf = hpfFreqArr != null ? (float) hpfFreqArr.getFloat(r) : 20.0f;
          float hr = hpfResArr != null ? (float) hpfResArr.getFloat(r) : 0.0f;
          if (sHpfFreq != null) hf += sHpfFreq.getFloat(idx) * 1000.0f;
          if (sHpfRes != null) hr += sHpfRes.getFloat(idx) * 9.0f;
          // HPF FM modulation: FM param modulates cutoff via total modulation
          float hpfFmMod = hpfFmArr != null ? (float) hpfFmArr.getFloat(r) : 0f;
          hf += totalModF * hpfFmMod * 5000.0f;
          hpfArr[u].freq(Math.max(20.0f, hf));
          hpfArr[u].Q(1.0f + Math.max(0.0f, hr) * 9.0f);
          // HPF morph/notch from bridge arrays (hpfMorphArr continuous 0-1; hpfModeArr 0=morph
          // mode, 1=notch mode)
          hpfArr[u].morph(hpfMorphArr != null ? hpfMorphArr.getFloat(r) : 1.0);
          hpfArr[u].notchMode(hpfModeArr != null && hpfModeArr.getInt(r) == 1);
          // Per-step delay/reverb/filter-mode overrides (-1 = no override for filter mode; 0=no
          // override for delay/reverb)
          if (sStepDelay != null) {
            float sd = (float) sStepDelay.getFloat(idx);
            if (sd > 0f) sDsend[u].gain(sd);
          }
          if (sStepReverb != null) {
            float srV = (float) sStepReverb.getFloat(idx);
            if (srV > 0f) sRsend[u].gain(srV);
          }
          if (sStepFilterMode != null) {
            int sfm = (int) sStepFilterMode.getInt(idx);
            if (sfm >= 0 && sfm <= 3) {
              // Map discrete filter mode to SVFilter morph + notchMode:
              // 0=LP (morph=0), 1=BP (morph=0.5), 2=HP (morph=1.0), 3=NOTCH (notchMode=true)
              fil[u].notchMode(sfm == 3);
              if (sfm < 3) fil[u].morph(sfm * 0.5);
            }
          }
          int fm = filterModeArr != null ? (int) filterModeArr.getInt(r) : 2;
          double fmorph = filterMorphArr != null ? filterMorphArr.getFloat(r) : 0.0;
          fil[u].morph(fmorph);
          float fd = filterDriveArr != null ? (float) filterDriveArr.getFloat(r) : 1.0f;
          // Apply global filter drive preference: if enabled, lift drive floor to 1.8
          float gfd = (float) vm.getGlobalFloat(BridgeContract.G_CHAR_FILTER_DRIVE);
          if (gfd > 0.5f) fd = Math.max(fd, 1.8f);
          int fn = filterNotchArr != null ? (int) filterNotchArr.getInt(r) : 0;
          fil[u].drive(fd);
          fil[u].notchMode(fn != 0);
          // Filter routing: 0=SERIES_LPF_HPF, 1=SERIES_HPF_LPF, 2=PARALLEL
          int fr = filterRouteArr != null ? (int) filterRouteArr.getInt(r) : 0;
          if (u < lastFilterRoute.length && fr != lastFilterRoute[u]) {
            int oldRoute = lastFilterRoute[u];
            lastFilterRoute[u] = fr;
            // Reconnect UGen chain when route changes using unchuck/chuck
            // Route 0: src->fil->hpf->routingMix  |  Route 1: src->hpf->fil->routingMix
            // Route 2: src->fil->routingMix + src->hpf->routingMix
            if (oldRoute == 0 && fr == 1) {
              src[u].unchuck(fil[u]);
              fil[u].unchuck(hpfArr[u]);
              hpfArr[u].unchuck(routingMix[u]);
              src[u].chuck(hpfArr[u]);
              hpfArr[u].chuck(fil[u]);
              fil[u].chuck(routingMix[u]);
            } else if (oldRoute == 0 && fr == 2) {
              fil[u].unchuck(hpfArr[u]);
              hpfArr[u].unchuck(routingMix[u]);
              src[u].chuck(hpfArr[u]);
              fil[u].chuck(routingMix[u]);
              hpfArr[u].chuck(routingMix[u]);
            } else if (oldRoute == 1 && fr == 0) {
              src[u].unchuck(hpfArr[u]);
              hpfArr[u].unchuck(fil[u]);
              fil[u].unchuck(routingMix[u]);
              src[u].chuck(fil[u]);
              fil[u].chuck(hpfArr[u]);
              hpfArr[u].chuck(routingMix[u]);
            } else if (oldRoute == 1 && fr == 2) {
              hpfArr[u].unchuck(fil[u]);
              src[u].chuck(fil[u]);
              hpfArr[u].chuck(routingMix[u]);
            } else if (oldRoute == 2 && fr == 0) {
              src[u].unchuck(hpfArr[u]);
              fil[u].unchuck(routingMix[u]);
              hpfArr[u].unchuck(routingMix[u]);
              fil[u].chuck(hpfArr[u]);
              hpfArr[u].chuck(routingMix[u]);
            } else if (oldRoute == 2 && fr == 1) {
              src[u].unchuck(fil[u]);
              hpfArr[u].unchuck(routingMix[u]);
              hpfArr[u].chuck(fil[u]); // fil already targets routingMix from route 2
            }
          }
          // Per-track default pan blended with step automation
          double sPanVal = sPanDef != null && r < sPanDef.size() ? sPanDef.getFloat(r) : 0.0;
          double stepPanVal = sPan != null ? sPan.getFloat(idx) : 0.0;
          tp = masterPan + sPanVal + stepPanVal + totalModP;
          pan[u].pan((float) Math.max(-1.0, Math.min(1.0, tp)));
          // Per-track default arrays applied each step (size-guarded for test contexts)
          if (sOsc2Type != null && r < sOsc2Type.size() && mod[u] != null) {
            double baseIdx = sOsc2Type.getInt(r);
            double wi =
                (sWaveIndex != null && r < sWaveIndex.size()) ? sWaveIndex.getFloat(r) : 0.0;
            mod[u].index(baseIdx + wi);
          }
          if (sMod1Fb != null && r < sMod1Fb.size())
            vm.setGlobalFloat(BridgeContract.G_MOD1_FB + "_" + r, sMod1Fb.getFloat(r));
          // Apply modulated noise volume: step value + totalModNoise
          double modulatedNoise =
              (sNoiseVolDef != null && r < sNoiseVolDef.size()) ? sNoiseVolDef.getFloat(r) : 0.0;
          modulatedNoise = Math.max(0.0, modulatedNoise * (1.0 + totalModNoise * 0.5));
          if (sNoiseVolDef != null && r < sNoiseVolDef.size())
            vm.setGlobalFloat(BridgeContract.G_NOISE_VOL + "_" + r, (float) modulatedNoise);
          if (sOscMix != null && r < sOscMix.size())
            vm.setGlobalFloat(BridgeContract.G_OSC_MIX + "_" + r, sOscMix.getFloat(r));
          if (sUnisonNum != null && r < sUnisonNum.size())
            vm.setGlobalFloat(BridgeContract.G_UNISON_NUM + "_" + r, sUnisonNum.getFloat(r));
          if (sUnisonDetune != null && r < sUnisonDetune.size())
            vm.setGlobalFloat(BridgeContract.G_UNISON_DETUNE + "_" + r, sUnisonDetune.getFloat(r));
          if (sUnisonSpread != null && r < sUnisonSpread.size())
            vm.setGlobalFloat(BridgeContract.G_UNISON_SPREAD + "_" + r, sUnisonSpread.getFloat(r));
          if (sModFxType != null && r < sModFxType.size())
            vm.setGlobalFloat(BridgeContract.G_MOD_FX_TYPE + "_" + r, sModFxType.getFloat(r));
          if (sModFxRate != null && r < sModFxRate.size())
            vm.setGlobalFloat(BridgeContract.G_MOD_FX_RATE + "_" + r, sModFxRate.getFloat(r));
          if (sModFxDepth != null && r < sModFxDepth.size())
            vm.setGlobalFloat(BridgeContract.G_MOD_FX_DEPTH + "_" + r, sModFxDepth.getFloat(r));
          if (sModFxFeedback != null && r < sModFxFeedback.size())
            vm.setGlobalFloat(
                BridgeContract.G_MOD_FX_FEEDBACK + "_" + r, sModFxFeedback.getFloat(r));
          if (sModFxOffset != null && r < sModFxOffset.size())
            vm.setGlobalFloat(BridgeContract.G_MOD_FX_OFFSET + "_" + r, sModFxOffset.getFloat(r));
          if (sPortamento != null && r < sPortamento.size())
            vm.setGlobalFloat(BridgeContract.G_PORTAMENTO + "_" + r, sPortamento.getFloat(r));
          if (sEqBass != null && r < sEqBass.size())
            vm.setGlobalFloat(BridgeContract.G_EQ_BASS + "_" + r, sEqBass.getFloat(r));
          if (sEqTreble != null && r < sEqTreble.size())
            vm.setGlobalFloat(BridgeContract.G_EQ_TREBLE + "_" + r, sEqTreble.getFloat(r));
          if (sStutterRate != null && r < sStutterRate.size()) {
            double spStutter = vm.getGlobalFloat(BridgeContract.G_SP_STUTTER_RATE);
            // spStutter=0 means no song-level scaling; use 1.0 as passthrough default
            vm.setGlobalFloat(
                BridgeContract.G_STUTTER_RATE + "_" + r,
                sStutterRate.getFloat(r)
                    * (float) Math.max(0.001, spStutter > 0.001 ? spStutter : 1.0));
          }
          if (sSampleRateRed != null && r < sSampleRateRed.size()) {
            double spSrr = vm.getGlobalFloat(BridgeContract.G_SP_SAMPLE_RATE_REDUCTION);
            vm.setGlobalFloat(
                BridgeContract.G_SAMPLE_RATE_RED + "_" + r,
                sSampleRateRed.getFloat(r) * (float) Math.max(0.001, spSrr > 0.001 ? spSrr : 1.0));
          }
          if (sBitCrush != null && r < sBitCrush.size()) {
            double spBitCrush = vm.getGlobalFloat(BridgeContract.G_SP_BITCRUSH);
            vm.setGlobalFloat(
                BridgeContract.G_BITCRUSH + "_" + r,
                sBitCrush.getFloat(r)
                    * (float) Math.max(0.001, spBitCrush > 0.001 ? spBitCrush : 1.0));
          }
          if (sCompAttack != null && r < sCompAttack.size())
            vm.setGlobalFloat(BridgeContract.G_COMP_ATTACK + "_" + r, sCompAttack.getFloat(r));
          if (sCompRelease != null && r < sCompRelease.size())
            vm.setGlobalFloat(BridgeContract.G_COMP_RELEASE + "_" + r, sCompRelease.getFloat(r));
          if (sCompBlend != null && r < sCompBlend.size())
            vm.setGlobalFloat(BridgeContract.G_COMP_BLEND + "_" + r, sCompBlend.getFloat(r));
          if (sCompHpf != null && r < sCompHpf.size())
            vm.setGlobalFloat(BridgeContract.G_COMP_SIDECHAIN_HPF + "_" + r, sCompHpf.getFloat(r));
          if (sCompRatio != null && r < sCompRatio.size())
            vm.setGlobalFloat(BridgeContract.G_COMP_RATIO + "_" + r, sCompRatio.getFloat(r));

          // ── Step automation blend: modify per-track globals in place ──
          // Volume: step automation adds on top of track volume
          double stepVol = sStepVolume != null ? sStepVolume.getFloat(idx) : 0.0;
          // Portamento, stutter, bitcrush, SRR: blend step automation into existing globals
          if (sStepPortamento != null && r < sStepPortamento.size())
            vm.setGlobalFloat(
                BridgeContract.G_PORTAMENTO + "_" + r,
                (float)
                    Math.max(
                        0.0,
                        (sPortamento != null && r < sPortamento.size()
                                ? sPortamento.getFloat(r)
                                : 0.0)
                            + sStepPortamento.getFloat(idx)));
          if (sStepStutter != null) {
            double spStutter = vm.getGlobalFloat(BridgeContract.G_SP_STUTTER_RATE);
            double perTrack =
                sStutterRate != null && r < sStutterRate.size() ? sStutterRate.getFloat(r) : 0.0;
            double stepAdd = sStepStutter.getFloat(idx);
            vm.setGlobalFloat(
                BridgeContract.G_STUTTER_RATE + "_" + r,
                (float)
                    ((perTrack + stepAdd) * Math.max(0.001, spStutter > 0.001 ? spStutter : 1.0)));
          }
          if (sStepSrr != null) {
            double spSrr = vm.getGlobalFloat(BridgeContract.G_SP_SAMPLE_RATE_REDUCTION);
            double perTrack =
                sSampleRateRed != null && r < sSampleRateRed.size()
                    ? sSampleRateRed.getFloat(r)
                    : 0.0;
            double stepAdd = sStepSrr.getFloat(idx);
            vm.setGlobalFloat(
                BridgeContract.G_SAMPLE_RATE_RED + "_" + r,
                (float) ((perTrack + stepAdd) * Math.max(0.001, spSrr > 0.001 ? spSrr : 1.0)));
          }
          if (sStepBitcrush != null) {
            double spBitCrush = vm.getGlobalFloat(BridgeContract.G_SP_BITCRUSH);
            double perTrack =
                sBitCrush != null && r < sBitCrush.size() ? sBitCrush.getFloat(r) : 0.0;
            double stepAdd = sStepBitcrush.getFloat(idx);
            vm.setGlobalFloat(
                BridgeContract.G_BITCRUSH + "_" + r,
                (float)
                    ((perTrack + stepAdd)
                        * Math.max(0.001, spBitCrush > 0.001 ? spBitCrush : 1.0)));
          }
          // Compressor attack/release: blend step automation
          if (sStepCompAttack != null) {
            double perTrack =
                sCompAttack != null && r < sCompAttack.size() ? sCompAttack.getFloat(r) : 0.0;
            vm.setGlobalFloat(
                BridgeContract.G_COMP_ATTACK + "_" + r,
                (float) Math.max(0.0, Math.min(1.0, perTrack + sStepCompAttack.getFloat(idx))));
          }
          if (sStepCompRelease != null) {
            double perTrack =
                sCompRelease != null && r < sCompRelease.size() ? sCompRelease.getFloat(r) : 0.0;
            vm.setGlobalFloat(
                BridgeContract.G_COMP_RELEASE + "_" + r,
                (float) Math.max(0.0, Math.min(1.0, perTrack + sStepCompRelease.getFloat(idx))));
          }
          // Mod FX feedback: blend step automation
          if (sStepModFxFeedback != null) {
            double perTrack =
                sModFxFeedback != null && r < sModFxFeedback.size()
                    ? sModFxFeedback.getFloat(r)
                    : 0.0;
            vm.setGlobalFloat(
                BridgeContract.G_MOD_FX_FEEDBACK + "_" + r,
                (float) Math.max(0.0, Math.min(1.0, perTrack + sStepModFxFeedback.getFloat(idx))));
          }
          // FM ratio/amount: blend step automation (used directly in FM section below via local
          // vars)
          double stepFmRatio = sStepFmRatio != null ? sStepFmRatio.getFloat(idx) : 0.0;
          double stepFmAmt = sStepFmAmt != null ? sStepFmAmt.getFloat(idx) : 0.0;
          // Arp rate/gate: blend step automation
          if (sStepArpRate != null) {
            double perTrack =
                arpOn != null && r < arpOn.size() ? (int) arpOn.getInt(r) == 1 ? 0.5 : 0.0 : 0.0;
            vm.setGlobalFloat(
                BridgeContract.G_ARP_RATE + "_" + r, perTrack + sStepArpRate.getFloat(idx));
          }
          if (sStepArpGate != null) {
            vm.setGlobalFloat(BridgeContract.G_ARP_GATE + "_" + r, sStepArpGate.getFloat(idx));
          }

          // Per-track compressor Dyno param update (RMSFeedbackCompressor-correct formulas)
          if (compArr != null && compArr[u] != null) {
            compArr[u].compressor();
            // Threshold derived from track volume: thresh = 1 - 0.8 * trackLevel
            float trackVol =
                trkLvl != null && r < trkLvl.size() ? (float) trkLvl.getFloat(r) : 0.5f;
            double th = 1.0 - 0.8 * trackVol;
            compArr[u].thresh((float) Math.max(0.01, Math.min(0.99, th)));
            // Attack: attackMS = 0.5 + (exp(2*knob) - 1) * 10
            float compAttack =
                sCompAttack != null && r < sCompAttack.size()
                    ? (float) sCompAttack.getFloat(r)
                    : 0.0f;
            double attackMS = 0.5 + (Math.exp(2.0 * compAttack) - 1.0) * 10.0;
            compArr[u].attackTime(attackMS * sr / 1000.0);
            // Release: releaseMS = 50 + (exp(2*knob) - 1) * 50
            float compRelease =
                sCompRelease != null && r < sCompRelease.size()
                    ? (float) sCompRelease.getFloat(r)
                    : 0.0f;
            double releaseMS = 50.0 + (Math.exp(2.0 * compRelease) - 1.0) * 50.0;
            compArr[u].releaseTime(releaseMS * sr / 1000.0);
            // Ratio from bridge array (default 0.5 → ~3:1)
            float compRatio =
                sCompRatio != null && r < sCompRatio.size() ? (float) sCompRatio.getFloat(r) : 0.5f;
            double fraction = 0.5 + compRatio / 2.0;
            double ratio = 1.0 / Math.max(0.0039, 1.0 - fraction);
            compArr[u].ratio((float) Math.max(2.0, Math.min(256.0, ratio)));
            // Parallel compression blend (dry/wet on Dyno)
            float cb =
                sCompBlend != null && r < sCompBlend.size() ? (float) sCompBlend.getFloat(r) : 0.0f;
            compArr[u].dryWet(cb);
            // Sidechain HPF via Dyno.sidechainHpf() — filters the sidechain signal before
            // the envelope follower. Replaces the old threshold-boost approximation.
            float hpfFreq =
                sCompHpf != null && r < sCompHpf.size() ? (float) sCompHpf.getFloat(r) : 0.0f;
            if (hpfFreq > 0.01f) {
              compArr[u].sidechainHpf(hpfFreq);
            }
          }

          // Per-track ModFxUnit param update (every step for live modulation)
          if (modFx != null && modFx[u] != null) {
            int mfxType =
                sModFxType != null && r < sModFxType.size() ? (int) sModFxType.getInt(r) : 0;
            double mfxRate =
                sModFxRate != null && r < sModFxRate.size() ? sModFxRate.getFloat(r) : 0.3;
            double mfxDepth =
                sModFxDepth != null && r < sModFxDepth.size() ? sModFxDepth.getFloat(r) : 0.3;
            double mfxFb =
                sModFxFeedback != null && r < sModFxFeedback.size()
                    ? sModFxFeedback.getFloat(r)
                    : 0.0;
            double mfxOffset =
                sModFxOffset != null && r < sModFxOffset.size() ? sModFxOffset.getFloat(r) : 0.0;
            modFx[u].setType(mfxType);
            modFx[u].setModFreq(mfxRate * Math.max(0.01, 1.0 + totalModMfxR * 0.5));
            modFx[u].setModDepth(mfxDepth * Math.max(0.0, 1.0 + totalModMfxD * 0.5));
            modFx[u].setFeedback(mfxFb);
            modFx[u].setOffset(mfxOffset);
          }

          if (clipProb != null && Math.random() > clipProb.getFloat(idx)) continue;
          // Fill mode: fill=0 → regular step, fill>0 → fill-only (plays only when fill active)
          {
            double sf = clipFill != null ? clipFill.getFloat(idx) : 0.0;
            int fillActive = (int) vm.getGlobalInt(BridgeContract.G_FILL_ACTIVE);
            if (fillActive != 0) {
              if (sf <= 0f || Math.random() > sf) continue;
            } else {
              if (sf > 0f) continue;
            }
          }
          if (envArr != null) {
            for (int ei = 0; ei < BridgeContract.ENV_COUNT; ei++) {
              int eb = (r * BridgeContract.ENV_COUNT + ei) * BridgeContract.ENV_PARAMS;
              double a = envArr.getFloat(eb + 0), d = envArr.getFloat(eb + 1);
              double s = envArr.getFloat(eb + 2), rls = envArr.getFloat(eb + 3);
              // Step automation blending per envelope parameter
              double stepA =
                  switch (ei) {
                    case 0 -> sStepEnv0A != null ? sStepEnv0A.getFloat(idx) : 0.0;
                    case 1 -> sStepEnv1A != null ? sStepEnv1A.getFloat(idx) : 0.0;
                    case 2 -> sStepEnv2A != null ? sStepEnv2A.getFloat(idx) : 0.0;
                    case 3 -> sStepEnv3A != null ? sStepEnv3A.getFloat(idx) : 0.0;
                    default -> 0.0;
                  };
              double stepD =
                  switch (ei) {
                    case 0 -> sStepEnv0D != null ? sStepEnv0D.getFloat(idx) : 0.0;
                    case 1 -> sStepEnv1D != null ? sStepEnv1D.getFloat(idx) : 0.0;
                    case 2 -> sStepEnv2D != null ? sStepEnv2D.getFloat(idx) : 0.0;
                    case 3 -> sStepEnv3D != null ? sStepEnv3D.getFloat(idx) : 0.0;
                    default -> 0.0;
                  };
              double stepS =
                  switch (ei) {
                    case 0 -> sStepEnv0S != null ? sStepEnv0S.getFloat(idx) : 0.0;
                    case 1 -> sStepEnv1S != null ? sStepEnv1S.getFloat(idx) : 0.0;
                    case 2 -> sStepEnv2S != null ? sStepEnv2S.getFloat(idx) : 0.0;
                    case 3 -> sStepEnv3S != null ? sStepEnv3S.getFloat(idx) : 0.0;
                    default -> 0.0;
                  };
              double stepR =
                  switch (ei) {
                    case 0 -> sStepEnv0R != null ? sStepEnv0R.getFloat(idx) : 0.0;
                    case 1 -> sStepEnv1R != null ? sStepEnv1R.getFloat(idx) : 0.0;
                    case 2 -> sStepEnv2R != null ? sStepEnv2R.getFloat(idx) : 0.0;
                    case 3 -> sStepEnv3R != null ? sStepEnv3R.getFloat(idx) : 0.0;
                    default -> 0.0;
                  };
              env[u][ei].set(
                  (float) Math.max(0.0, Math.min(1.0, a + stepA)),
                  (float) Math.max(0.0, Math.min(1.0, d + stepD)),
                  (float) Math.max(0.0, Math.min(1.0, s + stepS)),
                  (float) Math.max(0.0, Math.min(1.0, rls + stepR)));
            }
          }
          double gainVal =
              (clipVel.getFloat(idx) + stepVol * 0.5)
                  * trkLvl.getFloat(r)
                  * 0.8
                  * Math.max(0.0, 1.0 + totalModV * 0.5);
          double gateSec =
              (gateArr != null ? gateArr.getFloat(idx) : 0.9)
                  * outer.stepDuration(step % 2).samples()
                  / sampleRate();
          double stepPitchOffset = sPitchArr != null ? sPitchArr.getFloat(idx) * 24.0 : 0.0;
          // Global transpose (semitones) + humanize (±random cents)
          double transpose = vm.getGlobalInt(BridgeContract.G_TRANSPOSE);
          double humanizeAmt = vm.getGlobalFloat(BridgeContract.G_HUMANIZE);
          if (humanizeAmt > 0.0) stepPitchOffset += (Math.random() - 0.5) * humanizeAmt;
          stepPitchOffset += transpose;

          // Iterance: extra sub-triggers per step (0-3). Each sub-division re-fires the envelope.
          int it =
              clipIterance != null ? Math.max(0, Math.min(3, (int) clipIterance.getFloat(idx))) : 0;
          double itGateSec = it > 0 ? gateSec / (it + 1) : gateSec;

          // ── Polyphony mode + voice stealing ──
          int synthPoly = polyphonyArr != null ? (int) polyphonyArr.getInt(r) : 0;
          int maxVoices =
              maxVoicesArr != null ? (int) maxVoicesArr.getInt(r) : BridgeContract.TRACKS;
          // Enforce max voice limit across all synth tracks
          if (maxVoices < BridgeContract.TRACKS) {
            int activeCount = 0;
            for (int vi = synthBase; vi <= maxSynthBridgeRow; vi++) {
              if (voiceActive[vi]) activeCount++;
            }
            if (activeCount >= maxVoices) {
              // Steal oldest active voice (lowest trigger generation, excluding current u)
              int oldest = -1;
              long oldestGen = Long.MAX_VALUE;
              for (int vi = synthBase; vi <= maxSynthBridgeRow; vi++) {
                if (voiceActive[vi] && vi != r && triggerGeneration[vi] < oldestGen) {
                  oldestGen = triggerGeneration[vi];
                  oldest = vi;
                }
              }
              if (oldest >= 0) {
                int oldestU = oldest - synthBase;
                env[oldestU][0].keyOff();
                env[oldestU][1].keyOff();
                env[oldestU][2].keyOff();
                env[oldestU][3].keyOff();
                voiceActive[oldest] = false;
              }
            }
          }
          // Apply PolyphonyMode to current voice
          if (synthPoly == 2 && voiceActive[r]) {
            // MONO: keyOff before keyOn (cut overlapping note)
            env[u][0].keyOff();
            env[u][1].keyOff();
            env[u][2].keyOff();
            env[u][3].keyOff();
          } else if (synthPoly == 4) {
            // CHOKE: immediate cut + re-trigger
            env[u][0].keyOff();
            env[u][1].keyOff();
            env[u][2].keyOff();
            env[u][3].keyOff();
          }
          // LEGATO (1): no keyOff before keyOn — envelope continues smoothly
          // AUTO (3): use POLY behavior (no keyOff) — the voice stealing above handles limit
          // POLY (0): default behavior, no pre-keyOff
          // Mark voice active and assign generation for voice stealing
          voiceActive[r] = true;
          triggerGeneration[r] = ++globalTriggerCounter;

          if (algo >= 10) {
            double f =
                outer.mtof(((24 - 1) - (r - synthBase)) + 60 + stepPitchOffset)
                    * Math.pow(2.0, totalModPit);
            outer.triggerStkNote(src[u], (float) f, (float) gainVal);
            env[u][0].gain(0.0f);
            env[u][0].keyOn();
            env[u][1].keyOn();
            env[u][2].keyOn();
            env[u][3].keyOn();
            double noteSec = gateSec;
            int rv = u;
            ChuckUGen srcRef = src[u];
            int capturedR = r;
            int itLocal = it;
            double itGS = itGateSec;
            vm.spork(
                () -> {
                  int subTriggers = Math.max(1, itLocal + 1);
                  for (int si = 0; si < subTriggers; si++) {
                    if (si > 0) {
                      env[rv][0].gain((float) gainVal);
                      env[rv][0].keyOn();
                      env[rv][1].keyOn();
                      env[rv][2].keyOn();
                      env[rv][3].keyOn();
                    }
                    advance(second(itGS));
                    if (si < subTriggers - 1) {
                      env[rv][0].keyOff();
                      env[rv][1].keyOff();
                      env[rv][2].keyOff();
                      env[rv][3].keyOff();
                    }
                  }
                  outer.releaseStkNote(srcRef);
                  voiceActive[capturedR] = false;
                });
          } else if (dx7[u] != null) {
            int midiNote = notePitchArr != null ? (int) notePitchArr.getInt(idx) : 0;
            if (midiNote <= 0) midiNote = ((24 - 1) - (r - synthBase)) + 60;
            double f = outer.mtof(midiNote + stepPitchOffset) * Math.pow(2.0, totalModPit);
            dx7[u].setFreq((float) f);
            int dx7Vel = clipVel != null ? (int) (clipVel.getFloat(idx) * 127) : 100;
            if (dx7Vel <= 0) dx7Vel = 100;
            final int capturedDx7Vel = dx7Vel;
            dx7[u].noteOn(capturedDx7Vel);
            // DX7: no env[u][*] calls — per-operator envelopes handle all amplitude shaping.
            // Track volume is applied via routingMix[u] which is set during load.
            routingMix[u].gain((float) gainVal);
            double noteSec = gateSec;
            int capturedRv = u;
            int capturedR2 = r;
            int itLocal = it;
            double itGS = itGateSec;
            vm.spork(
                () -> {
                  int subTriggers = Math.max(1, itLocal + 1);
                  for (int si = 0; si < subTriggers; si++) {
                    if (si > 0) dx7[capturedRv].noteOn(capturedDx7Vel);
                    advance(second(itGS));
                    if (si < subTriggers - 1) dx7[capturedRv].noteOff();
                  }
                  dx7[capturedRv].noteOff();
                  voiceActive[capturedR2] = false;
                });
          } else if (arpOn != null && arpOn.getInt(r) == 1) {
            int baseMidi = (int) ((24 - 1) - (r - synthBase)) + 60;
            int capturedR3 = r;
            int v = u;
            vm.spork(
                () -> {
                  run_arp(v, baseMidi, (float) gainVal, car[v], mod[v], env[v]);
                  voiceActive[capturedR3] = false;
                });
          } else {
            double f =
                outer.mtof(((24 - 1) - (r - synthBase)) + 60 + stepPitchOffset)
                    * Math.pow(2.0, totalModPit);
            float oscAGain =
                sOscAVol != null
                    ? (float) (sOscAVol.getFloat(idx) * Math.max(0.0, 1.0 + totalModOscA * 0.5))
                    : (float) Math.max(0.0, 1.0 + totalModOscA * 0.5);
            float oscBGain =
                sOscBVol != null
                    ? (float) (sOscBVol.getFloat(idx) * Math.max(0.0, 1.0 + totalModOscB * 0.5))
                    : (float) Math.max(0.0, 1.0 + totalModOscB * 0.5);
            int synthMode = synthModeArr != null ? (int) synthModeArr.getInt(r) : 0;
            if (synthMode == 1) {
              if (car[u] != null) car[u].freq((float) f);
              if (mod[u] != null) {
                double fmR = (fmRatio != null ? fmRatio.getFloat(r) : 1.0) + stepFmRatio * 2.0;
                double fmA =
                    ((fmAmt != null ? fmAmt.getFloat(r) : 0.0) + stepFmAmt)
                        * Math.max(0.0, 1.0 + totalModFm * 0.5);
                mod[u].freq((float) (f * fmR));
                car[u].modGain((float) (fmA * 50.0));
                mod[u].gain(1.0f);
                // Dual-mod feedback amounts stored as per-track globals (no modGain2 on
                // MorphingWavetable)
                if (sMod2Amt != null && r < sMod2Amt.size())
                  vm.setGlobalFloat(BridgeContract.G_MOD2_AMT + "_" + r, sMod2Amt.getFloat(r));
                if (sMod2Fb != null && r < sMod2Fb.size())
                  vm.setGlobalFloat(BridgeContract.G_MOD2_FB + "_" + r, sMod2Fb.getFloat(r));
                if (sCarrier2Fb != null && r < sCarrier2Fb.size())
                  vm.setGlobalFloat(
                      BridgeContract.G_CARRIER2_FB + "_" + r, sCarrier2Fb.getFloat(r));
              }
            } else if (synthMode >= 2) {
              if (car[u] != null) {
                car[u].freq((float) f);
                car[u].gain(oscAGain);
              }
              if (mod[u] != null) {
                mod[u].freq((float) f);
                mod[u].gain(oscBGain);
              }
            } else {
              if (car[u] != null) {
                car[u].freq((float) f);
                car[u].gain(oscAGain);
              }
              if (mod[u] != null) mod[u].gain(0.0f);
            }
            // ── Oscillator retrigger phase reset ──
            Object retrigObj = vm.getGlobalObject(BridgeContract.G_RETRIG_PHASE);
            if (retrigObj instanceof ChuckArray retrigArr && r < retrigArr.size()) {
              int rp = (int) retrigArr.getInt(r);
              if (rp != -1) {
                double phaseVal =
                    rp == 0 ? 0.0 : rp / 360.0; // 0→reset to 0, 90→0.25, 180→0.5, 270→0.75
                if (car[u] != null) car[u].phase(phaseVal);
                if (mod[u] != null && synthMode == 1)
                  mod[u].phase(phaseVal); // FM mode: reset modulator too
              }
            }

            // ── Unison sub-voice frequency detune + gain ──
            float numVoices =
                sUnisonNum != null && r < sUnisonNum.size() ? (float) sUnisonNum.getFloat(r) : 1.0f;
            float detuneCents =
                sUnisonDetune != null && r < sUnisonDetune.size()
                    ? (float) sUnisonDetune.getFloat(r)
                    : 0.0f;
            float spread =
                sUnisonSpread != null && r < sUnisonSpread.size()
                    ? (float) sUnisonSpread.getFloat(r)
                    : 0.0f;
            int totalUnison = Math.max(1, Math.min(MAX_UNISON, Math.round(numVoices)));
            float totalBoost = 1.0f / (float) Math.sqrt(totalUnison);
            if (totalUnison > 1
                && unisonSub != null
                && u < unisonSub.length
                && unisonSub[u] != null) {
              int halfCount = totalUnison - 1;
              for (int us = 0; us < unisonSub[u].length; us++) {
                if (us >= halfCount) {
                  unisonSub[u][us].gain(0.0f);
                  continue;
                }
                // Symmetric detune distribution around center
                float offset = (us + 1.0f) - (halfCount + 1.0f) / 2.0f;
                double subFreq = f * Math.pow(2.0, detuneCents * offset / 1200.0);
                unisonSub[u][us].freq((float) subFreq);
                unisonSub[u][us].gain(totalBoost);
                // Phase offset for stereo width based on spread
                float panPos = halfCount > 1 ? offset / ((halfCount - 1.0f) / 2.0f) * spread : 0.0f;
                unisonSub[u][us].phase(Math.max(0.0, Math.min(1.0, (panPos + 1.0f) * 0.25f)));
              }
            }

            env[u][0].gain((float) gainVal);
            env[u][0].keyOn();
            env[u][1].keyOn();
            env[u][2].keyOn();
            env[u][3].keyOn();
            double noteSec = gateSec;
            if (vm.getLogLevel() >= 2)
              vm.print(
                  "SYNTH trigger track: " + r + " step: " + (idx % BridgeContract.STEPS) + "\n");
            int[] capturedR = new int[] {r};
            int rv = u;
            int itLocal = it;
            double itGS = itGateSec;
            vm.spork(
                () -> {
                  int subTriggers = Math.max(1, itLocal + 1);
                  for (int si = 0; si < subTriggers; si++) {
                    if (si > 0) {
                      env[rv][0].gain((float) gainVal);
                      env[rv][0].keyOn();
                      env[rv][1].keyOn();
                      env[rv][2].keyOn();
                      env[rv][3].keyOn();
                    }
                    advance(second(itGS));
                    if (si < subTriggers - 1) {
                      env[rv][0].keyOff();
                      env[rv][1].keyOff();
                      env[rv][2].keyOff();
                      env[rv][3].keyOff();
                    }
                  }
                  voiceActive[capturedR[0]] = false;
                  if (vm.getLogLevel() >= 2)
                    vm.print("SYNTH note end track: " + capturedR[0] + "\n");
                });
          }
        }
      }
    }
  }

  private void transport_shred() {
    System.out.println("[transport] entered");

    // ── High Fidelity / Pure Java Path ──
    if (vm.getGlobalInt(BridgeContract.G_HI_FI_MODE) != 0) {
      System.out.println("[transport] Entering High-Fidelity (Pure Java) Mode");
      vm.spork(new PureFirmwareMasterShred(vm)::run);

      while (isRunning()) {
        advance(ms(100));
      }
      return;
    }

    vm.setGlobalObject(BridgeContract.G_DELAY_IN, new Gain());
    vm.setGlobalObject(BridgeContract.G_REVERB_IN, new Gain());
    vm.setGlobalObject(BridgeContract.G_SYNTH_BUS, new Gain());
    vm.setGlobalObject(BridgeContract.G_AUDIO_BUS, new Gain());
    vm.setGlobalObject(BridgeContract.G_KIT_BUS, new Gain());
    vm.setGlobalObject(BridgeContract.G_MASTER_TAP, new Gain());
    vm.setGlobalObject(BridgeContract.E_SIDECHAIN, new ChuckEvent());

    // Sync point: ensure buses are registered before any sub-shreds try to fetch them
    advance(samp(1));

    vm.spork(new FxBusShred(vm, this)::run);
    vm.spork(new MasterShred(vm, this)::run);
    vm.spork(new ClockShred(vm, this)::run);
    vm.spork(new KitShred(vm, this)::run);
    System.out.println("[transport] sporking synth_shred");
    vm.spork(new SynthShred(vm, this)::run);
    System.out.println("[transport] sporked synth_shred, now sporking sidechain");
    vm.spork(new SidechainShred(vm, this)::run);
    vm.spork(new AudioShred(vm, this)::run);
    vm.spork(new ExportShred(vm, this)::run);

    while (isRunning()) {
      advance(ms(100));
    }
    running = false; // signal sub-shreds to stop
  }
}
