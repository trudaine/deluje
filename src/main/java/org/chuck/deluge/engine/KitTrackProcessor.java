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
    buf = new SndBuf(sampleRate());
    pan = new Pan2();
    delaySend = new Gain();
    reverbSend = new Gain();

    // Connect directly to stereo DAC to avoid mono downmix issues
    buf.chuck(pan).chuck(dac());

    // Send-based routing
    Gain gDelayIn = (Gain) vm.getGlobalObject("g_delay_in");
    Gain gReverbIn = (Gain) vm.getGlobalObject("g_reverb_in");

    if (gDelayIn != null) pan.chuck(delaySend).chuck(gDelayIn);
    if (gReverbIn != null) pan.chuck(reverbSend).chuck(gReverbIn);

    ChuckEvent tickEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.TICK_EVENT);
    ChuckEvent loadTrigger = (ChuckEvent) vm.getGlobalObject(BridgeContract.G_LOAD_TRIGGER);

    // Persistent listener for sample loading
    vm.spork(
        () -> {
          org.chuck.core.ChuckShred current = org.chuck.core.ChuckShred.CURRENT_SHRED.get();
          while (current != null && !current.isDone()) {
            advance(loadTrigger);
            loadSample();
          }
        });

    loadSample();

    // Main Sequencing Loop
    while (true) {
      advance(tickEvent);

      int step = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
      if (step < 0) continue;

      int idx = trackId * 16 + step;

      ChuckArray trackType = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_TYPE);
      ChuckArray pattern = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PATTERN);
      ChuckArray mute = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MUTE);

      if (trackType == null || trackType.getInt(trackId) != 0) continue;
      if (mute == null || mute.getInt(trackId) != 0) continue;
      if (pattern == null || pattern.getInt(idx) == 0) continue;

      trigger(idx);
    }
  }

  private void trigger(int idx) {
    ChuckArray velocity = (ChuckArray) vm.getGlobalObject(BridgeContract.G_VELOCITY);
    ChuckArray trackLevel = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_LEVEL);

    double vel = velocity != null ? velocity.getFloat(idx) : 0.8;
    double masterVol = vm.getGlobalFloat(BridgeContract.G_MASTER_VOL);

    if (buf.samples() > 0) {
      buf.rate(1.0f);
      buf.pos(0L);
      buf.gain(
          (float) (vel * (trackLevel != null ? trackLevel.getFloat(trackId) : 0.7) * masterVol));
    }

    if (vm.getLogLevel() >= 2) {
      vm.print("KIT trigger track: " + trackId + " step: " + (idx % 16) + "\n");
    }
  }

  private synchronized void loadSample() {
    String path = (String) vm.getGlobalObject("g_sample_" + trackId);
    if (path == null || path.isEmpty()) {
      buf.setSamples(new float[0]);
      lastLoadedPath = null;
      return;
    }

    if (path.equals(lastLoadedPath)) return;

    buf.read(path);
    if (buf.samples() == 0) {
      String altPath = path;
      if (path.endsWith(".wav")) altPath = path.substring(0, path.length() - 4) + ".WAV";
      else if (path.endsWith(".WAV")) altPath = path.substring(0, path.length() - 4) + ".wav";

      if (!altPath.equals(path)) {
        buf.read(altPath);
      }
    }

    if (buf.samples() > 0) {
      buf.rate(0);
      buf.pos(buf.samples());
      lastLoadedPath = path;
    } else {
      logger.warning("Track " + trackId + ": Failed to load sample " + path);
      lastLoadedPath = null;
    }
  }
}
