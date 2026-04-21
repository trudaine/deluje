package org.chuck.deluge.engine;

import static org.chuck.core.ChuckDSL.*;

import org.chuck.audio.filter.HPF;
import org.chuck.audio.fx.Chorus;
import org.chuck.audio.fx.Dyno;
import org.chuck.audio.fx.Echo;
import org.chuck.audio.fx.JCRev;
import org.chuck.audio.util.DelugeAdsr;
import org.chuck.audio.util.Gain;
import org.chuck.core.ChuckEvent;
import org.chuck.core.ChuckVM;
import org.chuck.core.Shred;
import org.chuck.deluge.BridgeContract;

/** Global FX Bus Shred. Manages master Delay, Reverb, and Modulation effects. */
public class DelugeFxBus implements Shred {
  private final ChuckVM vm;
  private final BridgeContract bridge;

  private Gain delayIn;
  private Gain reverbIn;
  private Gain modIn;

  public DelugeFxBus(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;
  }

  @Override
  public void shred() {
    // Initialize global input buses
    delayIn = new Gain();
    reverbIn = new Gain();
    modIn = new Gain();

    vm.setGlobalObject("g_delay_in", delayIn);
    vm.setGlobalObject("g_reverb_in", reverbIn);
    vm.setGlobalObject("g_mod_in", modIn);

    Gain fxOut = new Gain();
    HPF hpf = new HPF(sampleRate());
    Dyno limiter = new Dyno(sampleRate());
    DelugeAdsr safetyGate = new DelugeAdsr(sampleRate());

    fxOut.chuck(hpf).chuck(limiter).chuck(safetyGate).chuck(dac());

    hpf.freq(20);
    limiter.limiter();
    safetyGate.set(0.001, 0.0, 1.0, 0.001);
    safetyGate.keyOn();

    Echo delay = new Echo(sampleRate(), sampleRate());
    JCRev rev = new JCRev(sampleRate());
    Chorus mod = new Chorus(sampleRate());

    delayIn.chuck(delay).chuck(fxOut);
    reverbIn.chuck(rev).chuck(fxOut);
    modIn.chuck(mod).chuck(fxOut);

    fxOut.gain(0.3f);
    mod.setModDepth(0.2f);
    mod.setModFreq(0.5f);

    ChuckEvent tickEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.TICK_EVENT);

    while (true) {
      // Wait for next step to update params (avoids zipper noise)
      if (tickEvent != null) advance(tickEvent);
      else advance(ms(100));

      float delayTime = (float) vm.getGlobalFloat(BridgeContract.G_DELAY_TIME);
      float delayFb = (float) vm.getGlobalFloat(BridgeContract.G_DELAY_FB);
      float revRoom = (float) vm.getGlobalFloat(BridgeContract.G_REVERB_ROOM);

      delay.delay(second(delayTime).samples());
      delay.gain(delayFb);
      rev.mix(revRoom);
    }
  }
}
