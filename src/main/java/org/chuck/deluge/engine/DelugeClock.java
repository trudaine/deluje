package org.chuck.deluge.engine;

import static org.chuck.core.ChuckDSL.*;

import org.chuck.core.ChuckEvent;
import org.chuck.core.ChuckVM;
import org.chuck.core.Shred;
import org.chuck.deluge.BridgeContract;

/** The Master Clock Shred. High-priority heartbeat that drives the entire project. */
public class DelugeClock implements Shred {
  private final ChuckVM vm;
  private final BridgeContract bridge;

  public DelugeClock(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;
  }

  @Override
  public void shred() {
    int step = 0;
    ChuckEvent tickEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.TICK_EVENT);
    if (tickEvent == null) {
      vm.print("CLOCK ERROR: tick_event is null!\n");
      return;
    }

    org.chuck.core.ChuckShred current = org.chuck.core.ChuckShred.CURRENT_SHRED.get();
    while (current != null && !current.isDone()) {
      // Read from VM every sample or nearly so? No, but let's use advance(samp) for better
      // resolution in virtual time
      long playState = vm.getGlobalInt(BridgeContract.G_PLAY);

      if (playState == 1L) {
        int stutterOn = (int) vm.getGlobalInt(BridgeContract.G_STUTTER_ON);

        if (stutterOn == 0) {
          vm.setGlobalInt(BridgeContract.G_CURRENT_STEP, step % 16);
          tickEvent.broadcast(vm);

          ChuckEvent ckNoteEvent = (ChuckEvent) vm.getGlobalObject("g_ck_noteOn");
          if (ckNoteEvent != null) {
            // Extract pitch and velocity for track 0 step sequence
            Object pitchObj = vm.getGlobalObject(BridgeContract.G_PITCH);
            Object velObj = vm.getGlobalObject(BridgeContract.G_VELOCITY);
            if (pitchObj instanceof org.chuck.core.ChuckArray pitchArr
                && velObj instanceof org.chuck.core.ChuckArray velArr) {
              int idx = (step % 16);
              vm.setGlobalFloat("g_ck_pitch", pitchArr.getFloat(idx));
              vm.setGlobalFloat("g_ck_velocity", velArr.getFloat(idx));
            }
            ckNoteEvent.broadcast(vm);
          }

          advance(samp(getStepDurationSamples(step)));

          step++;
        } else {
          float stutterDiv = (float) vm.getGlobalFloat(BridgeContract.G_STUTTER_DIV);
          if (stutterDiv < 1.0f) stutterDiv = 1.0f;

          tickEvent.broadcast(vm);
          advance(samp(getStepDurationSamples(step) / stutterDiv));
        }
      } else {
        step = 0;
        vm.setGlobalInt(BridgeContract.G_CURRENT_STEP, -1L);
        // In headless/test mode, advance(ms(1)) might be too slow if the test finishes too fast
        // or if virtual time isn't advancing as expected.
        advance(ms(10));
      }
    }
  }

  private double getStepDurationSamples(int step) {
    float bpm = (float) vm.getGlobalFloat(BridgeContract.G_BPM);
    if (bpm < 10.0f) bpm = 120.0f;
    float swing = (float) vm.getGlobalFloat(BridgeContract.G_SWING);
    swing = Math.max(0.0f, Math.min(1.0f, swing));

    double baseSec = 60.0 / bpm / 4.0; // 16th note
    double base = baseSec * sampleRate();

    if (step % 2 == 0) {
      return base * (1.0 + (swing - 0.5) * 0.4);
    } else {
      return base * (1.0 - (swing - 0.5) * 0.4);
    }
  }
}
