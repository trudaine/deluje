package org.chuck.deluge.engine;

import static org.chuck.core.ChuckDSL.*;

import java.util.logging.Logger;
import org.chuck.audio.util.Gain;
import org.chuck.audio.util.Pan2;
import org.chuck.audio.util.SndBuf;
import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckEvent;
import org.chuck.core.ChuckVM;
import org.chuck.core.Shred;
import org.chuck.deluge.BridgeContract;

/**
 * A dedicated processor for a single sound in a Kit. Manages sample playback, envelope, and
 * per-step parameters.
 */
public class KitTrackProcessor implements Shred {
  private static final Logger logger = Logger.getLogger(KitTrackProcessor.class.getName());

  private final int trackId;
  private final ChuckVM vm;
  private final BridgeContract bridge;

  private SndBuf buf;
  private Pan2 pan;
  private Gain delaySend;
  private Gain reverbSend;

  private String lastLoadedPath = null;

  public KitTrackProcessor(int trackId, ChuckVM vm, BridgeContract bridge) {
    this.trackId = trackId;
    this.vm = vm;
    this.bridge = bridge;
  }

  @Override
  public void shred() {
    buf = new SndBuf();
    buf.setLoop(false); // Ensure one-shot playback

    pan = new Pan2();
    delaySend = new Gain();
    delaySend.gain(0.0f); // Initialize sends to SILENT

    reverbSend = new Gain();
    reverbSend.gain(0.0f);

    // Main output path
    buf.chuck(pan).chuck(dac());

    // FX send routing
    Gain gDelayIn = (Gain) vm.getGlobalObject("g_delay_in");
    if (gDelayIn != null) {
      pan.chuck(delaySend).chuck(gDelayIn);
    }

    Gain gReverbIn = (Gain) vm.getGlobalObject("g_reverb_in");
    if (gReverbIn != null) {
      pan.chuck(reverbSend).chuck(gReverbIn);
    }

    ChuckEvent tickEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.TICK_EVENT);
    ChuckEvent loadTrigger = (ChuckEvent) vm.getGlobalObject(BridgeContract.G_LOAD_TRIGGER);
    ChuckEvent previewEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.E_PREVIEW);

    org.chuck.core.ChuckShred current = org.chuck.core.ChuckShred.CURRENT_SHRED.get();

    // Sample loading listener
    vm.spork(
        () -> {
          while (current != null && !current.isDone()) {
            advance(loadTrigger);
            loadSample();
          }
        });

    // Audition listener
    vm.spork(
        () -> {
          while (current != null && !current.isDone()) {
            advance(previewEvent);
            if (vm.getGlobalInt(BridgeContract.G_PREVIEW_TRACK) == trackId) {
              trigger(trackId * 16);
            }
          }
        });

    loadSample();

    // Main Sequencing Loop
    while (current != null && !current.isDone()) {
      advance(tickEvent); // THIS MUST ALWAYS HAPPEN FIRST

      int step = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
      if (step < 0) continue;

      int idx = trackId * 16 + step;

      ChuckArray trackType = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_TYPE);
      ChuckArray pattern = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PATTERN);
      ChuckArray mute = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MUTE);

      if (trackType == null || trackType.getInt(trackId) != 0) continue;

      // Check mute state
      long trackMuted = vm.getGlobalInt("g_mute_" + trackId);
      if (trackMuted != 0 || (mute != null && mute.getInt(trackId) != 0)) {
        continue;
      }

      if (pattern != null && pattern.getInt(idx) != 0) {
        trigger(idx);
      }
    }
  }

  private void trigger(int idx) {
    ChuckArray velocity = (ChuckArray) vm.getGlobalObject(BridgeContract.G_VELOCITY);
    ChuckArray trackLevel = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_LEVEL);

    double vel = velocity != null ? velocity.getFloat(idx) : 0.8;
    double masterVol = vm.getGlobalFloat(BridgeContract.G_MASTER_VOL);

    if (buf.ready() > 0) {
      buf.setRate(1.0);
      buf.setPos(0L);
      buf.setGain(
          (float) (vel * (trackLevel != null ? trackLevel.getFloat(trackId) : 0.7) * masterVol));
    }

    if (vm.getLogLevel() >= 2) {
      System.out.println("ENGINE: Triggered Track " + trackId + " Step " + (idx % 16));
    }
  }

  private synchronized void loadSample() {
    String path = (String) vm.getGlobalObject("g_sample_" + trackId);
    if (path == null || path.isEmpty()) return;
    if (path.equals(lastLoadedPath)) return;

    buf.setRead(path);
    if (buf.ready() > 0) {
      buf.setRate(0);
      buf.setPos(buf.samples());
      lastLoadedPath = path;
    }
  }
}
