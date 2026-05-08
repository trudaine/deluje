package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckEvent;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.engine.DelugeEngineDSL;
import org.junit.jupiter.api.Test;

/**
 * Diagnostic test to determine why macOS produces zero audio.
 *
 * <p>Tests are structured to isolate the failure point:
 * <ol>
 *   <li>Does {@code ChuckVM.advanceTime()} complete without timeout?</li>
 *   <li>Does {@code vm.spork(Runnable)} execute and call {@code advance(event)}?</li>
 *   <li>Does the engine's {@code clock_shred} set {@code G_CURRENT_STEP}?</li>
 *   <li>Does the engine's {@code synth_shred} produce any DAC output?</li>
 *   <li>Do the DX7 engine and sample playback produce signal?</li>
 * </ol>
 */
public class MacOSDiagnosticTest {

  private static final int SAMPLE_RATE = 44100;

  @Test
  void testVmAdvanceTimeWorks() throws Exception {
    // Primitives: VM with a sporked shred that sets a marker
    System.setProperty("chuck.audio.dummy", "true");
    ChuckVM vm = new ChuckVM(SAMPLE_RATE, 2);

    final boolean[] ran = {false};
    vm.spork(() -> {
      ran[0] = true;
    });

    // Must advance enough for the shred to be scheduled and run
    vm.advanceTime(SAMPLE_RATE);

    System.out.println("[DIAG] testVmAdvanceTimeWorks: ran=" + ran[0]);
    assertTrue(ran[0], "Sporked Runnable should execute");
    vm.shutdown();
  }

  @Test
  void testEventBroadcastWakesShred() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    ChuckVM vm = new ChuckVM(SAMPLE_RATE, 2);

    final boolean[] eventReceived = {false};
    final long[] wakeStep = {-1};
    final boolean[] enteredWait = {false};
    final ChuckEvent evt = new ChuckEvent();
    vm.setGlobalObject("test_event", evt);
    // Also track from the test side
    final int[] testEventWaiting = {0};

    vm.spork(() -> {
      System.out.println("[DIAG] event-test shred started at now=" + org.chuck.core.ChuckDSL.now());
      org.chuck.core.ChuckDSL.advance(evt);
      wakeStep[0] = org.chuck.core.ChuckDSL.now();
      eventReceived[0] = true;
      System.out.println("[DIAG] event-test shred received event at now=" + wakeStep[0]);
    });

    // Advance in small increments to see what happens
    for (int i = 0; i < 10; i++) {
      vm.advanceTime(441);
      testEventWaiting[0] = evt.getWaitingCount();
      System.out.println("[DIAG] after advance #" + i + ": now=" + vm.getCurrentTime()
          + " waiting=" + testEventWaiting[0] + " eventReceived=" + eventReceived[0]);
      if (eventReceived[0]) break; // shouldn't happen without broadcast
    }
    // should not have fired yet
    System.out.println("[DIAG] before broadcast: waiting=" + evt.getWaitingCount()
        + " eventReceived=" + eventReceived[0]);

    // Broadcast using vm.broadcastGlobalEvent() or evt.broadcast(vm)
    // NOTE: evt.broadcast() (no-arg) only works inside a sporked shred because it
    // reads ChuckVM from ScopedValue. Use the VM-explicit overload from test threads.
    evt.broadcast(vm);
    System.out.println("[DIAG] after broadcast, before advance: waiting=" + evt.getWaitingCount());
    vm.advanceTime(4410);
    System.out.println("[DIAG] final: eventReceived=" + eventReceived[0] + " wakeStep=" + wakeStep[0]);

    System.out.println("[DIAG] testEventBroadcastWakesShred: eventReceived=" + eventReceived[0]);
    assertTrue(eventReceived[0], "Shred waiting on event should wake when broadcast");
    vm.shutdown();
  }

  @Test
  void testClockShredAdvancesStep() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    System.setProperty("deluge.tracks", "8");
    ChuckVM vm = new ChuckVM(SAMPLE_RATE, 2);

    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    // No kit or synth tracks needed — clock_shred runs independently
    vm.spork(new DelugeEngineDSL(vm));
    vm.advanceTime(SAMPLE_RATE);

    // Broadcast load trigger (allows kit_shred + synth_shred to initialize)
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.advanceTime(SAMPLE_RATE);

    // Start playback
    vm.setGlobalFloat(BridgeContract.G_BPM, 120.0f);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);

    // Advance 50 blocks × 10ms
    boolean stepAdvanced = false;
    for (int i = 0; i < 50; i++) {
      vm.advanceTime(441);
      long step = vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
      if (step >= 0) stepAdvanced = true;
    }

    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);

    System.out.println("[DIAG] testClockShredAdvancesStep: stepAdvanced=" + stepAdvanced);
    assertTrue(stepAdvanced, "G_CURRENT_STEP should advance after G_PLAY=1");
    vm.shutdown();
  }

  @Test
  void testSynthShredProducesAudio() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    System.setProperty("deluge.tracks", "8");
    ChuckVM vm = new ChuckVM(SAMPLE_RATE, 2);

    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    // Push one synth track (type 1) with a SAW oscillator
    ChuckArray oscTypeArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_OSC_TYPE);
    if (oscTypeArr == null) {
      oscTypeArr = new ChuckArray("int", BridgeContract.TRACKS);
      vm.setGlobalObject(BridgeContract.G_OSC_TYPE, oscTypeArr);
    }
    bridge.setTrackType(0, 1);
    bridge.setMute(0, false);
    bridge.setTrackLevel(0, 0.8);
    oscTypeArr.setInt(0, 1); // SAW
    bridge.setFilterFreq(0, 20000.0f / 20000.0f);
    bridge.setFilterRes(0, 0.0f);
    bridge.setFilterMode(0, 0);
    bridge.setSynthAlgo(0, 0);
    bridge.setTrackLength(0, 16);

    // Write step pattern
    for (int s = 0; s < 16; s++) {
      bridge.setStep(0, s, true);
      bridge.setVelocity(0, s, 0.8f);
      bridge.setGate(0, s, 0.9f);
    }

    // Start engine
    vm.spork(new DelugeEngineDSL(vm));
    vm.advanceTime(SAMPLE_RATE);

    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.advanceTime(SAMPLE_RATE);

    vm.setGlobalFloat(BridgeContract.G_BPM, 120.0f);
    vm.setGlobalFloat(BridgeContract.G_MASTER_VOL, 1.0);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);

    float peakL = 0, peakR = 0;
    boolean stepAdvanced = false;

    for (int i = 0; i < 200; i++) {
      vm.advanceTime(441);
      long step = vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
      if (step >= 0) stepAdvanced = true;

      float curL = Math.abs(vm.getDacChannel(0).getLastOut());
      float curR = Math.abs(vm.getDacChannel(1).getLastOut());
      if (curL > peakL) peakL = curL;
      if (curR > peakR) peakR = curR;
    }

    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
    double peakAvg = (peakL + peakR) / 2.0;

    System.out.printf("[DIAG] testSynthShredProducesAudio: stepAdvanced=%s peakL=%.8f peakR=%.8f peakAvg=%.8f%n",
        stepAdvanced, peakL, peakR, peakAvg);
    assertTrue(stepAdvanced, "Step should advance");
    assertTrue(peakAvg > 0.0001,
        "SAW synth should produce audible output peakAvg=" + peakAvg);
    vm.shutdown();
  }
}
