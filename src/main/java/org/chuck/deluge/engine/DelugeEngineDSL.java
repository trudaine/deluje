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
 * Native Java implementation of the Deluge Engine using the ChucK-Java DSL.
 */
public class DelugeEngineDSL implements Shred, Runnable {

    private ChuckVM vm;

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
        if (step % 2 == 0) {
            return second(baseSec * (1.0 + (swing - 0.5) * 0.4));
        } else {
            return second(baseSec * (1.0 - (swing - 0.5) * 0.4));
        }
    }

    private void kit_shred() {
        float sr = (float)sampleRate();
        SndBuf[] kit = new SndBuf[4];
        Pan2[] pan = new Pan2[4];
        Gain[] dSend = new Gain[4];
        Gain[] rSend = new Gain[4];
        
        Gain master = new Gain();
        HPF hpf = new HPF(sr);
        Dyno limit = new Dyno(sr);
        DelugeAdsr gate = new DelugeAdsr();

        master.chuck(hpf).chuck(limit).chuck(gate).chuck(dac());
        hpf.freq(20);
        limit.limiter();
        advance(ms(100));
        gate.forceMute();
        gate.set(0.001, 0, 1, 0.001);
        int gatesOpen = 0;

        String[] samples = {"examples/data/kick.wav", "examples/data/snare.wav", "examples/data/hihat.wav", "examples/data/hihat-open.wav"};
        for (int i = 0; i < 4; i++) {
            kit[i] = new SndBuf();
            kit[i].read(samples[i]);
            kit[i].rate(0);
            kit[i].pos(kit[i].samples());
            
            pan[i] = new Pan2();
            dSend[i] = new Gain();
            rSend[i] = new Gain();
            
            kit[i].chuck(pan[i]).chuck(master);
            pan[i].chuck(dSend[i]).chuck((ChuckUGen)vm.getGlobalObject(BridgeContract.G_DELAY_IN));
            pan[i].chuck(rSend[i]).chuck((ChuckUGen)vm.getGlobalObject(BridgeContract.G_REVERB_IN));
        }

        ChuckEvent tickEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.E_TICK);
        ChuckEvent sidechainEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.E_SIDECHAIN);

        while (vm.getGlobalInt(BridgeContract.G_PLAY) != 0) {
            advance(tickEvent);
            if (vm.getGlobalInt(BridgeContract.G_PLAY) == 0) break;

            ChuckArray pat = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PATTERN);
            ChuckArray vel = (ChuckArray) vm.getGlobalObject(BridgeContract.G_VELOCITY);
            ChuckArray mute = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MUTE);
            ChuckArray trkLvl = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_LEVEL);
            ChuckArray sStart = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_START);
            ChuckArray sEnd = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_END);

            if (pat == null || trkLvl == null) continue;

            master.gain((float)vm.getGlobalFloat(BridgeContract.G_MASTER_VOL));
            int step = (int) (vm.getGlobalInt(BridgeContract.G_CURRENT_STEP) % 16);

            for (int r = 0; r < 4; r++) {
                if (mute.getInt(r) != 0) continue;
                int idx = r * 16 + step;
                if (pat.getInt(idx) == 0) continue;
                
                if (r == 0) sidechainEvent.broadcast();
                
                kit[r].rate(1);
                kit[r].pos((long)(sStart.getFloat(idx) * kit[r].samples()));
                kit[r].gain((float)(vel.getFloat(idx) * trkLvl.getFloat(r) * 0.8));
                
                if (gatesOpen == 0) {
                    gatesOpen = 1;
                    gate.keyOn();
                }
            }
        }
    }

    private void synth_shred() {
        float sr = (float)sampleRate();
        MorphingWavetable[] car = new MorphingWavetable[8];
        MorphingWavetable[] mod = new MorphingWavetable[8];
        SVFilter[] fil = new SVFilter[8];
        DelugeAdsr[] env = new DelugeAdsr[8];
        Pan2[] pan = new Pan2[8];

        Gain synthBus = (Gain) vm.getGlobalObject(BridgeContract.G_SYNTH_BUS);

        for (int i = 0; i < 8; i++) {
            car[i] = new MorphingWavetable(sr);
            mod[i] = new MorphingWavetable(sr);
            fil[i] = new SVFilter();
            env[i] = new DelugeAdsr();
            pan[i] = new Pan2();

            mod[i].chuck(car[i]);
            // sync(2) was missing in Java MorphingWavetable, skip for now or fix core
            car[i].chuck(fil[i]).chuck(env[i]).chuck(pan[i]).chuck(synthBus);

            fil[i].reset();
            fil[i].freq(5000);
            env[i].set(0.05, 0.2, 0.5, 0.3);
        }

        ChuckEvent tickEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.E_TICK);

        while (vm.getGlobalInt(BridgeContract.G_PLAY) != 0) {
            advance(tickEvent);
            if (vm.getGlobalInt(BridgeContract.G_PLAY) == 0) break;

            ChuckArray pat = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PATTERN);
            ChuckArray vel = (ChuckArray) vm.getGlobalObject(BridgeContract.G_VELOCITY);
            ChuckArray pitch = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PITCH);
            ChuckArray mute = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MUTE);
            ChuckArray trkLvl = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_LEVEL);
            ChuckArray gFil = (ChuckArray) vm.getGlobalObject(BridgeContract.G_FILTER);
            ChuckArray sFil = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_FILTER);
            ChuckArray sRes = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_RES);
            ChuckArray sPan = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_PAN);
            ChuckArray arpOn = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ARP_ON);
            ChuckArray fmRatio = (ChuckArray) vm.getGlobalObject(BridgeContract.G_FM_RATIO);
            ChuckArray fmAmount = (ChuckArray) vm.getGlobalObject(BridgeContract.G_FM_AMOUNT);

            if (pat == null || gFil == null) continue;

            int step = (int) (vm.getGlobalInt(BridgeContract.G_CURRENT_STEP) % 16);

            for (int r = 4; r < 8; r++) {
                int v = r;
                int idx = r * 16 + step;

                double tf = (gFil.getFloat(v * 2) + sFil.getFloat(idx)) * 10000.0 + 100.0;
                double tq = (gFil.getFloat(v * 2 + 1) + sRes.getFloat(idx)) * 4.0 + 1.0;
                double tp = vm.getGlobalFloat(BridgeContract.G_MASTER_PAN) + sPan.getFloat(idx);

                fil[v].freq((float)tf);
                fil[v].Q((float)tq);
                pan[v].pan((float)tp);

                if (mute.getInt(r) != 0) continue;
                if (pat.getInt(idx) == 0) {
                    env[v].keyOff();
                    continue;
                }

                if (arpOn.getInt(r) == 1) {
                    int baseMidi = (int) (pitch.getInt(idx) + 60);
                    float gainVal = (float) (vel.getFloat(idx) * trkLvl.getFloat(r));
                    vm.spork(() -> run_arp(v, baseMidi, gainVal, car[v], mod[v], env[v]));
                } else {
                    double f = mtof(pitch.getInt(idx) + 60);
                    car[v].freq((float)f);
                    mod[v].freq((float)(f * fmRatio.getFloat(r)));
                    mod[v].gain((float)(fmAmount.getFloat(r) * 1000.0));
                    env[v].gain((float)(vel.getFloat(idx) * trkLvl.getFloat(r) * 0.8));
                    env[v].keyOn();
                }
            }
        }
    }

    private void run_arp(int v, int baseMidi, float gain, MorphingWavetable car, MorphingWavetable mod, DelugeAdsr env) {
        ChuckArray octArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ARP_OCTAVE);
        ChuckArray rateArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ARP_RATE);
        ChuckArray arpOn = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ARP_ON);
        ChuckArray fmRatio = (ChuckArray) vm.getGlobalObject(BridgeContract.G_FM_RATIO);
        ChuckArray fmAmount = (ChuckArray) vm.getGlobalObject(BridgeContract.G_FM_AMOUNT);
        
        int octaves = (int) octArr.getInt(v);
        if (octaves < 1) octaves = 1;

        for (int o = 0; o < octaves; o++) {
            int m = baseMidi + (o * 12);
            double f = mtof(m);
            car.freq((float)f);
            mod.freq((float)(f * fmRatio.getFloat(v)));
            mod.gain((float)(fmAmount.getFloat(v) * 1000.0));
            env.gain((float)(gain * 0.8));
            env.keyOn();

            double rate = rateArr.getFloat(v);
            double bpm = vm.getGlobalFloat(BridgeContract.G_BPM);
            ChuckDuration d = second(60.0 / bpm / 4.0 / rate);
            
            advance(samp(d.samples() * 0.8));
            env.keyOff();
            advance(samp(d.samples() * 0.2));

            if (vm.getGlobalInt(BridgeContract.G_PLAY) == 0 || arpOn.getInt(v) == 0) break;
        }
    }

    private void clock_shred() {
        int step = 0;
        long nowSamples = vm.getCurrentTime();
        long stepSamples = Math.round(stepDuration(0).samples());
        advance(samp(stepSamples - (nowSamples % stepSamples)));

        while (vm.getGlobalInt(BridgeContract.G_PLAY) != 0) {
            if (vm.getGlobalInt(BridgeContract.G_STUTTER_ON) == 0) {
                vm.setGlobalInt(BridgeContract.G_CURRENT_STEP, (long)(step % 16));
                ((ChuckEvent)vm.getGlobalObject(BridgeContract.E_TICK)).broadcast();
                advance(stepDuration(step % 2));
                step++;
            } else {
                ((ChuckEvent)vm.getGlobalObject(BridgeContract.E_TICK)).broadcast();
                double div = vm.getGlobalFloat(BridgeContract.G_STUTTER_DIV);
                advance(samp(stepDuration(step % 2).samples() / div));
            }
        }
    }

    private void fx_bus_shred() {
        float sr = (float)sampleRate();
        Gain fxIn = new Gain();
        DelugeAdsr gate = new DelugeAdsr();
        fxIn.chuck(gate).chuck(dac());
        
        Echo delay = new Echo();
        JCRev reverb = new JCRev();
        Chorus chorus = new Chorus(sr);

        ((Gain)vm.getGlobalObject(BridgeContract.G_DELAY_IN)).chuck(delay).chuck(fxIn);
        ((Gain)vm.getGlobalObject(BridgeContract.G_REVERB_IN)).chuck(reverb).chuck(fxIn);
        ((Gain)vm.getGlobalObject(BridgeContract.G_MOD_IN)).chuck(chorus).chuck(fxIn);

        advance(ms(100));
        gate.forceMute();
        gate.set(0.01, 0, 1, 0.01);
        
        ChuckEvent tickEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.E_TICK);
        while (true) {
            advance(tickEvent);
            if (vm.getGlobalInt(BridgeContract.G_PLAY) != 0) gate.keyOn();
            
            delay.delay((float)vm.getGlobalFloat(BridgeContract.G_DELAY_TIME));
            delay.gain((float)vm.getGlobalFloat(BridgeContract.G_DELAY_FB));
            reverb.mix((float)vm.getGlobalFloat(BridgeContract.G_REVERB_ROOM));
        }
    }

    private void master_shred() {
        float sr = (float)sampleRate();
        HPF hpf = new HPF(sr);
        Dyno limit = new Dyno(sr);
        Dyno comp = new Dyno(sr);
        DelugeAdsr gate = new DelugeAdsr();

        ((Gain)vm.getGlobalObject(BridgeContract.G_SYNTH_BUS)).chuck(hpf).chuck(comp).chuck(limit).chuck(gate).chuck(dac());
        
        hpf.freq(20);
        limit.limiter();
        comp.compressor();
        advance(ms(100));
        gate.forceMute();
        gate.set(0.005, 0, 1, 0.005);

        ChuckEvent tickEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.E_TICK);
        while (true) {
            advance(tickEvent);
            if (vm.getGlobalInt(BridgeContract.G_PLAY) != 0) gate.keyOn();
            double th = 1.0 - vm.getGlobalFloat(BridgeContract.G_MASTER_COMP);
            comp.thresh((float)Math.max(0.01, Math.min(0.9, th)));
        }
    }

    private void transport_shred() {
        long lastPlay = 0;
        vm.spork(this::fx_bus_shred);
        vm.spork(this::master_shred);
        
        while (true) {
            advance(ms(10));
            long play = vm.getGlobalInt(BridgeContract.G_PLAY);
            if (play != lastPlay) {
                lastPlay = play;
                if (play == 1) {
                    vm.spork(this::clock_shred);
                    vm.spork(this::kit_shred);
                    vm.spork(this::synth_shred);
                } else {
                    vm.setGlobalInt(BridgeContract.G_CURRENT_STEP, -1L);
                    ((ChuckEvent)vm.getGlobalObject(BridgeContract.E_TICK)).broadcast();
                }
            }
        }
    }
}
