package org.chuck.deluge.engine;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import org.chuck.deluge.BridgeContract;
import org.chuck.core.Std;
import org.chuck.deluge.engine.dsp.*;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * Experimental Pure Java implementation of the Deluge sequencer and audio engine.
 */
public class NativeJavaSequencer implements Runnable {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        r -> {
            Thread t = new Thread(r, "NativeJavaSequencer-Clock");
            t.setPriority(Thread.MAX_PRIORITY);
            return t;
        }
    );

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final BridgeContract bridge;
    
    private volatile double bpm = 120.0;
    private volatile double swing = 0.5;
    private int masterStep = 0;

    private static final int SAMPLE_RATE = 44100;
    private static final int BUFFER_SIZE = 512;
    private SourceDataLine line;
    private Thread audioThread;
    
    private final List<SynthVoice> synthVoices = new ArrayList<>();
    private final List<SampleVoice> sampleVoices = new ArrayList<>();
    private final List<NativeDx7Voice> dx7Voices = new ArrayList<>();
    
    private final NativeLfo[] lfos = new NativeLfo[BridgeContract.LFO_COUNT];
    private final NativeDelay delay = new NativeDelay(SAMPLE_RATE, 2.0);
    private final NativeReverb reverb = new NativeReverb(SAMPLE_RATE);
    private final NativeCompressor compressor = new NativeCompressor(SAMPLE_RATE);
    private final NativeWavExporter wavExporter = new NativeWavExporter(SAMPLE_RATE);
    private final NativeMidiExporter midiExporter = new NativeMidiExporter();

    private final Map<String, float[]> sampleCache = new HashMap<>();

    public NativeJavaSequencer(BridgeContract bridge) {
        this.bridge = bridge;
        for (int i = 0; i < 32; i++) synthVoices.add(new SynthVoice());
        for (int i = 0; i < 32; i++) sampleVoices.add(new SampleVoice());
        for (int i = 0; i < 16; i++) dx7Voices.add(new NativeDx7Voice(SAMPLE_RATE));
        for (int i = 0; i < BridgeContract.LFO_COUNT; i++) lfos[i] = new NativeLfo(SAMPLE_RATE);
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            System.out.println("[NativeJavaSequencer] Starting Pure Java Engine...");
            initAudio();
            scheduleNextTick();
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            System.out.println("[NativeJavaSequencer] Stopping Pure Java Engine...");
            scheduler.shutdownNow();
            if (line != null) { line.stop(); line.close(); }
            if (wavExporter.isActive()) wavExporter.stop();
        }
    }

    private void scheduleNextTick() {
        if (!running.get()) return;
        scheduler.schedule(this, calculateStepDurationNs(masterStep), TimeUnit.NANOSECONDS);
    }

    @Override
    public void run() {
        try { processTick(); } catch (Exception e) { e.printStackTrace(); } finally { scheduleNextTick(); }
    }

    private void processTick() {
        this.bpm = bridge.getBpm();
        this.swing = bridge.getSwing();
        delay.setParams(bridge.getDelayTime(), bridge.getDelayFb(), 0.3);
        compressor.setParams(0.5, 4.0, 0.01, 0.1, 1.0);

        float[] lfoRates = bridge.getLfoRateRaw();
        int[] lfoTypes = bridge.getLfoTypeRaw();
        for (int i = 0; i < BridgeContract.LFO_COUNT; i++) {
            lfos[i].setRate(lfoRates[i]);
            lfos[i].setWaveform(lfoTypes[i]);
        }

        int[] pattern = bridge.getPatternRaw();
        int[] pitch = bridge.getPitchRaw();
        float[] velocity = bridge.getVelocityRaw();
        float[] probability = bridge.getProbabilityRaw();
        int[] mutes = bridge.getMuteRaw();
        int[] trackTypes = bridge.getTrackTypeRaw();
        float[] envData = bridge.getEnvRaw();

        for (int t = 0; t < BridgeContract.TRACKS; t++) {
            if (mutes[t] > 0) continue;

            int trackLen = bridge.getTrackLength(t);
            if (trackLen <= 0) trackLen = 16;
            int step = masterStep % trackLen;
            int idx = t * BridgeContract.STEPS + step;

            if (pattern[idx] > 0) {
                if (probability[idx] < 1.0f && Math.random() > probability[idx]) continue;

                float gain = velocity[idx] * 0.15f;
                int midiNote = 60 + pitch[idx];
                double cut = bridge.getTrackFilterFreq(t) * 15000.0 + 20.0;
                double res = bridge.getTrackFilterRes(t);

                int eb = (t * BridgeContract.ENV_COUNT + 0) * BridgeContract.ENV_PARAMS;
                double a = envData[eb + 0], d = envData[eb + 1], s = envData[eb + 2], r = envData[eb + 3];

                if (trackTypes[t] == 0) {
                    if (bridge.getSynthAlgo(t) > 0) {
                        triggerDx7Note(t, midiNote, (int)(velocity[idx] * 127));
                    } else {
                        triggerSynthNote(t, Std.mtof(midiNote), gain, cut, res, a, d, s, r);
                    }
                } else {
                    String path = bridge.getSamplePath(t);
                    if (path != null && !path.isEmpty()) {
                        float[] buffer = loadSample(path);
                        if (buffer != null) triggerSampleNote(t, buffer, gain, cut, res, a, d, s, r);
                    }
                }
                midiExporter.addNote(t, midiNote, (int)(velocity[idx] * 127), 120);
            }
        }
        midiExporter.advance(120);
        bridge.setQueueStep(masterStep % 16); 
        masterStep++;
    }

    private float[] loadSample(String path) {
        if (sampleCache.containsKey(path)) return sampleCache.get(path);
        try {
            File file = new File(path);
            if (!file.exists()) return null;
            AudioInputStream ais = AudioSystem.getAudioInputStream(file);
            byte[] bytes = ais.readAllBytes();
            float[] samples = new float[(int)ais.getFrameLength()];
            ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int ch = ais.getFormat().getChannels();
            for (int i = 0; i < samples.length; i++) samples[i] = bb.getShort(i * ch * 2) / 32768f;
            ais.close();
            sampleCache.put(path, samples);
            return samples;
        } catch (Exception e) { return null; }
    }

    private void triggerSynthNote(int t, double f, float g, double c, double r, double a, double d, double s, double rv) {
        for (SynthVoice v : synthVoices) if (!v.adsr.isActive()) { v.trigger(t, f, g, c, r, a, d, s, rv); break; }
    }

    private void triggerSampleNote(int t, float[] b, float g, double c, double r, double a, double d, double s, double rv) {
        for (SampleVoice v : sampleVoices) if (!v.adsr.isActive()) { v.trigger(t, b, g, c, r, a, d, s, rv); break; }
    }

    private void triggerDx7Note(int t, int n, int v) {
        for (NativeDx7Voice dv : dx7Voices) if (!dv.isActive()) { dv.trigger(t, n, v); break; }
    }

    private long calculateStepDurationNs(int step) {
        double baseSec = 60.0 / bpm / 4.0;
        double adj = (step % 2 == 0) ? 1.0 + (swing - 0.5) * 0.4 : 1.0 - (swing - 0.5) * 0.4;
        return (long) (baseSec * adj * 1_000_000_000L);
    }

    private void initAudio() {
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
            line = AudioSystem.getSourceDataLine(format);
            line.open(format, BUFFER_SIZE * 4);
            line.start();
            audioThread = new Thread(this::renderLoop, "NativeJavaSequencer-Audio");
            audioThread.setPriority(Thread.MAX_PRIORITY);
            audioThread.setDaemon(true);
            audioThread.start();
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void renderLoop() {
        byte[] buffer = new byte[BUFFER_SIZE * 4];
        float[] lfoValues = new float[BridgeContract.LFO_COUNT];
        int[] lfoTracks = bridge.getLfoTrackRaw();
        float[] lfoDepths = bridge.getLfoDepthRaw();
        int[] lfoTargets = bridge.getLfoTargetRaw();
        
        float[] mixL = new float[BUFFER_SIZE];
        float[] mixR = new float[BUFFER_SIZE];
        VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

        while (running.get()) {
            java.util.Arrays.fill(mixL, 0); java.util.Arrays.fill(mixR, 0);
            
            for (int i = 0; i < BUFFER_SIZE; i++) {
                if (i % 32 == 0) for (int l = 0; l < BridgeContract.LFO_COUNT; l++) lfoValues[l] = lfos[l].tick();
                
                float trigger = 0;
                for (SynthVoice v : synthVoices) if (v.adsr.isActive()) { v.applyModulation(lfoValues, lfoTracks, lfoDepths, lfoTargets); float s = v.tick(); mixL[i] += s; mixR[i] += s; }
                for (SampleVoice v : sampleVoices) if (v.adsr.isActive()) { v.applyModulation(lfoValues, lfoTracks, lfoDepths, lfoTargets); float s = v.tick(); mixL[i] += s; mixR[i] += s; if (v.trackIdx == 0) trigger = s; }
                for (NativeDx7Voice v : dx7Voices) if (v.isActive()) { float s = v.tick(); mixL[i] += s; mixR[i] += s; }


                float[] compressed = compressor.process(mixL[i], mixR[i], trigger);
                mixL[i] = compressed[0]; mixR[i] = compressed[1];
            }

            float masterGain = (float) bridge.getMasterVol();
            int upper = SPECIES.loopBound(BUFFER_SIZE);
            for (int i = 0; i < upper; i += SPECIES.length()) {
                FloatVector.fromArray(SPECIES, mixL, i).mul(masterGain).intoArray(mixL, i);
                FloatVector.fromArray(SPECIES, mixR, i).mul(masterGain).intoArray(mixR, i);
            }

            for (int i = 0; i < BUFFER_SIZE; i++) {
                float[] d = delay.tick(mixL[i], mixR[i]);
                float[] r = reverb.tick(mixL[i], mixR[i]);
                float outL = mixL[i] + d[0] + r[0], outR = mixR[i] + d[1] + r[1];
                if (wavExporter.isActive()) wavExporter.record(outL, outR);
                short sL = (short)(Math.max(-1, Math.min(1, outL)) * 32767);
                short sR = (short)(Math.max(-1, Math.min(1, outR)) * 32767);
                int idx = i * 4;
                buffer[idx] = (byte)(sL & 0xFF); buffer[idx+1] = (byte)((sL>>8)&0xFF);
                buffer[idx+2] = (byte)(sR & 0xFF); buffer[idx+3] = (byte)((sR>>8)&0xFF);
            }
            line.write(buffer, 0, buffer.length);
        }
    }

    private static class SynthVoice {
        final NativeAdsr adsr = new NativeAdsr(SAMPLE_RATE);
        final NativeMoogFilter filter = new NativeMoogFilter(SAMPLE_RATE);
        int trackIdx = -1;
        double baseFreq = 0, phase = 0, phaseInc = 0, baseCutoff = 1000.0, baseResonance = 0.1;
        float gain = 0;
        void trigger(int t, double f, float g, double c, double r, double a, double d, double s, double rv) {
            this.trackIdx = t; this.baseFreq = f; this.phaseInc = 2.0*Math.PI*f/SAMPLE_RATE;
            this.gain = g; this.baseCutoff = c; this.baseResonance = r;
            this.adsr.setParams(a, d, s, rv); this.adsr.keyOn(); this.phase = 0;
        }
        void applyModulation(float[] lfos, int[] trks, float[] depths, int[] targets) {
            double mf = baseFreq, mc = baseCutoff;
            for (int l = 0; l < BridgeContract.LFO_COUNT; l++) if (trks[l] == -1 || trks[l] == trackIdx) {
                float mod = lfos[l]*depths[l];
                if (targets[l] == 1) mf *= Math.pow(2.0, mod*0.5);
                else if (targets[l] == 2) mc *= Math.pow(2.0, mod*2.0);
            }
            this.phaseInc = 2.0*Math.PI*mf/SAMPLE_RATE; this.filter.setParams(mc, baseResonance);
        }
        float tick() {
            float saw = (float)(2.0*(phase/(2.0*Math.PI))-1.0);
            phase += phaseInc; if (phase > 2.0*Math.PI) phase -= 2.0*Math.PI;
            return filter.tick(saw * gain * adsr.tick());
        }
    }

    private static class SampleVoice {
        final NativeAdsr adsr = new NativeAdsr(SAMPLE_RATE);
        final NativeMoogFilter filter = new NativeMoogFilter(SAMPLE_RATE);
        final NativeSndBuf sndBuf = new NativeSndBuf(SAMPLE_RATE);
        int trackIdx = -1;
        double baseCutoff = 15000.0, baseResonance = 0.0;
        float gain = 0;
        void trigger(int t, float[] s, float g, double c, double r, double a, double d, double sv, double rv) {
            this.trackIdx = t; this.sndBuf.setSamples(s); this.gain = g; this.baseCutoff = c; this.baseResonance = r;
            this.adsr.setParams(a, d, sv, rv); this.adsr.keyOn(); this.sndBuf.trigger();
        }
        void applyModulation(float[] lfos, int[] trks, float[] depths, int[] targets) {
            double mc = baseCutoff;
            for (int l = 0; l < BridgeContract.LFO_COUNT; l++) if (trks[l] == -1 || trks[l] == trackIdx) {
                float mod = lfos[l]*depths[l]; if (targets[l] == 2) mc *= Math.pow(2.0, mod*2.0);
            }
            this.filter.setParams(mc, baseResonance);
        }
        float tick() {
            float s = sndBuf.tick(); if (!sndBuf.isActive()) adsr.keyOff();
            return filter.tick(s * gain * adsr.tick());
        }
    }

    public static void launch(BridgeContract bridge) {
        new NativeJavaSequencer(bridge).start();
    }
}
