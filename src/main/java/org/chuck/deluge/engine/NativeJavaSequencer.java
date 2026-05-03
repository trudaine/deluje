package org.chuck.deluge.engine;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * Pure Java Deluge sequencer and audio engine.
 *
 * <p>Integrates five components that the original {@code NativeJavaSequencer} was missing:
 * <ul>
 *   <li>{@link VoiceAllocator} — per-track voice pools with stealing</li>
 *   <li>{@link SequencerClock} — dedicated clock thread with swing/stutter/transport</li>
 *   <li>{@link TickEventQueue} — sample-accurate event dispatch from clock to audio thread</li>
 *   <li>Song mode — bar-boundary clip switching via {@link BridgeContract#processLaunchQueue()}</li>
 *   <li>{@link NativeMidiInputRouter} — real-time MIDI input via javax.sound.midi</li>
 * </ul>
 */
public class NativeJavaSequencer {

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final BridgeContract bridge;

    // ── New component: Voice Allocator ──
    private final VoiceAllocator voiceAllocator;

    // ── New component: Tick Event Queue ──
    private final TickEventQueue eventQueue = new TickEventQueue();

    // ── New component: Sequencer Clock ──
    private SequencerClock clock;

    // ── New component: MIDI Input Router ──
    private NativeMidiInputRouter midiInput;

    // ── Audio pipeline (unchanged) ──
    private static final int SAMPLE_RATE = 44100;
    private static final int BUFFER_SIZE = 512;
    private SourceDataLine line;
    private Thread audioThread;

    // ── Voice pools ──
    private final List<SynthVoice> synthVoices = new ArrayList<>();
    private final List<SampleVoice> sampleVoices = new ArrayList<>();
    private final List<NativeDx7Voice> dx7Voices = new ArrayList<>();

    // ── FX ──
    private final NativeLfo[] lfos = new NativeLfo[BridgeContract.LFO_COUNT];
    private final NativeDelay delay = new NativeDelay(SAMPLE_RATE, 2.0);
    private final NativeReverb reverb = new NativeReverb(SAMPLE_RATE);
    private final NativeCompressor compressor = new NativeCompressor(SAMPLE_RATE);
    private final NativeWavExporter wavExporter = new NativeWavExporter(SAMPLE_RATE);
    private final NativeMidiExporter midiExporter = new NativeMidiExporter();

    private final Map<String, float[]> sampleCache = new HashMap<>();

    // ── Per-step voice scheduling state ──
    // Tracks which voice slots are active per track for note-off scheduling
    private final List<Integer>[] activeSynthSlots = new List[BridgeContract.TRACKS];
    private final List<Integer>[] activeSampleSlots = new List[BridgeContract.TRACKS];
    private final List<Integer>[] activeDx7Slots = new List[BridgeContract.TRACKS];

    @SuppressWarnings("unchecked")
    public NativeJavaSequencer(BridgeContract bridge) {
        this.bridge = bridge;
        this.voiceAllocator = new VoiceAllocator(bridge);

        for (int i = 0; i < 32; i++) synthVoices.add(new SynthVoice());
        for (int i = 0; i < 32; i++) sampleVoices.add(new SampleVoice());
        for (int i = 0; i < 16; i++) dx7Voices.add(new NativeDx7Voice(SAMPLE_RATE));
        for (int i = 0; i < BridgeContract.LFO_COUNT; i++) lfos[i] = new NativeLfo(SAMPLE_RATE);

        for (int t = 0; t < BridgeContract.TRACKS; t++) {
            activeSynthSlots[t] = new ArrayList<>();
            activeSampleSlots[t] = new ArrayList<>();
            activeDx7Slots[t] = new ArrayList<>();
        }
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            System.out.println("[NativeJavaSequencer] Starting Pure Java Engine with VoiceAllocator + SequencerClock...");

            // 1. Initialize audio output
            initAudio();

            // 2. Start MIDI input router
            midiInput = new NativeMidiInputRouter(bridge, eventQueue, voiceAllocator);
            if (midiInput.openFirstDevice()) {
                midiInput.start();
            }

            // 3. Start the clock — it calls back on each tick
            clock = new SequencerClock(bridge, (step, isNewStep) -> {
                if (isNewStep) {
                    processStep(step);
                }
            });
            clock.start();

            System.out.println("[NativeJavaSequencer] Engine started with clock + event queue + voice allocator + MIDI");
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            System.out.println("[NativeJavaSequencer] Stopping engine...");
            if (clock != null) clock.stop();
            if (midiInput != null) midiInput.stop();
            if (line != null) { line.stop(); line.close(); }
            if (wavExporter.isActive()) wavExporter.stop();
            System.out.println("[NativeJavaSequencer] Stopped.");
        }
    }

    // ── Per-step processing (called from clock thread) ──

    /**
     * Process one step: read bridge data, schedule voice on/off events into the event queue.
     * The audio thread will drain the event queue at the start of the next buffer render.
     */
    private void processStep(int step) {
        double bpm = bridge.getBpm();
        double swing = bridge.getSwing();
        int masterStep = clock != null ? clock.getMasterStep() : 0;

        // Update shared FX state (read from bridge; will be used by audio thread)
        delay.setParams(bridge.getDelayTime(), bridge.getDelayFb(), 0.3);
        compressor.setParams(0.5, 4.0, 0.01, 0.1, 1.0);

        float[] lfoRates = bridge.getLfoRateRaw();
        int[] lfoTypes = bridge.getLfoTypeRaw();
        for (int i = 0; i < BridgeContract.LFO_COUNT; i++) {
            lfos[i].setRate(lfoRates[i]);
            lfos[i].setWaveform(lfoTypes[i]);
        }

        // Read shared arrays
        int[] pattern = bridge.getPatternRaw();
        int[] pitch = bridge.getPitchRaw();
        float[] velocity = bridge.getVelocityRaw();
        float[] probability = bridge.getProbabilityRaw();
        int[] mutes = bridge.getMuteRaw();
        int[] trackTypes = bridge.getTrackTypeRaw();
        float[] envData = bridge.getEnvRaw();

        // Schedule note-off for any previously active voices on this step's tracks
        // (done before note-on so sustained notes ring out naturally)
        // Note: note-off scheduling for non-latching voices happens at step boundaries.
        // We only schedule note-offs for voices that were triggered on a previous step
        // and whose gate has expired. The render loop handles ADSR release.

        // Schedule note-on events for active steps
        for (int t = 0; t < BridgeContract.TRACKS; t++) {
            if (mutes[t] > 0) continue;

            // Get clip-aware step data (support song mode)
            int clipIdx = bridge.getCurrentClip(t);
            int trackLen = bridge.getTrackLength(t);
            if (trackLen <= 0) trackLen = 16;
            int trackStep = masterStep % trackLen;
            int idx = t * BridgeContract.STEPS + trackStep;

            // Read pattern with clip awareness
            boolean hasNote = bridge.getPatternAtClip(t, trackStep, clipIdx) > 0;
            if (!hasNote) continue;

            // Apply probability
            float probAtClip = bridge.getProbabilityAtClip(t, trackStep, clipIdx);
            if (probAtClip < 1.0f && Math.random() > probAtClip) continue;

            // Get note data
            int pitchOffset = bridge.getPitchAtClip(t, trackStep, clipIdx);
            float vel = bridge.getVelocityAtClip(t, trackStep, clipIdx);
            int midiNote = 60 + pitchOffset;

            // Build a template event and push to the event queue
            TickEventQueue.Command cmd;
            float gain = vel * 0.15f;
            double cut = bridge.getTrackFilterFreq(t) * 15000.0 + 20.0;
            double res = bridge.getTrackFilterRes(t);

            int eb = (t * BridgeContract.ENV_COUNT + 0) * BridgeContract.ENV_PARAMS;
            double a = envData[eb + 0], d = envData[eb + 1], s = envData[eb + 2], r = envData[eb + 3];

            TickEventQueue.Event evt = new TickEventQueue.Event();

            if (trackTypes[t] == 0) {
                // Kit track
                cmd = TickEventQueue.Command.SAMPLE_NOTE_ON;
                evt.set(cmd, t, midiNote, (int) (vel * 127), -1);
                evt.gain = gain;
                evt.setFilter(cut, res);
                evt.setADSR(a, d, s, r);
                evt.setSample(bridge.getSamplePath(t));
            } else if (bridge.getSynthAlgo(t) > 0) {
                // DX7 track
                cmd = TickEventQueue.Command.DX7_NOTE_ON;
                evt.set(cmd, t, midiNote, (int) (vel * 127), -1);
                evt.gain = gain;
            } else {
                // Synth track
                cmd = TickEventQueue.Command.SYNTH_NOTE_ON;
                evt.set(cmd, t, midiNote, (int) (vel * 127), -1);
                evt.gain = gain;
                evt.setFilter(cut, res);
                evt.setADSR(a, d, s, r);
            }

            eventQueue.push(evt);
            midiExporter.addNote(t, midiNote, (int) (vel * 127), 120);
        }

        midiExporter.advance(120);
        bridge.setQueueStep(masterStep % 16);
    }

    // ── Sample loading ──

    private float[] loadSample(String path) {
        if (sampleCache.containsKey(path)) return sampleCache.get(path);
        try {
            File file = new File(path);
            if (!file.exists()) return null;
            AudioInputStream ais = AudioSystem.getAudioInputStream(file);
            byte[] bytes = ais.readAllBytes();
            float[] samples = new float[(int) ais.getFrameLength()];
            ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int ch = ais.getFormat().getChannels();
            for (int i = 0; i < samples.length; i++)
                samples[i] = bb.getShort(i * ch * 2) / 32768f;
            ais.close();
            sampleCache.put(path, samples);
            return samples;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Audio render loop ──

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
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Drain pending tick events and dispatch to appropriate voice.
     */
    private void dispatchEvents() {
        eventQueue.drain(event -> {
            switch (event.command) {
                case SYNTH_NOTE_ON -> {
                    int slot = voiceAllocator.allocSynth(event.track, event.midiNote);
                    if (slot >= 0 && slot < synthVoices.size()) {
                        double freq = Std.mtof(event.midiNote);
                        synthVoices.get(slot).trigger(event.track, freq, event.gain,
                                event.cutoff, event.resonance,
                                event.attack, event.decay, event.sustain, event.release);
                        activeSynthSlots[event.track].add(slot);
                    }
                }
                case SYNTH_NOTE_OFF -> {
                    // Release the most recent synth voice for this track + note
                    releaseVoiceForTrack(event.track, event.midiNote,
                            synthVoices, activeSynthSlots, voiceAllocator::releaseSynth);
                }
                case SAMPLE_NOTE_ON -> {
                    int slot = voiceAllocator.allocSample(event.track, event.midiNote);
                    if (slot >= 0 && slot < sampleVoices.size()) {
                        float[] buf = null;
                        if (event.samplePath != null && !event.samplePath.isEmpty()) {
                            buf = loadSample(event.samplePath);
                        }
                        if (buf != null) {
                            sampleVoices.get(slot).trigger(event.track, buf, event.gain,
                                    event.cutoff, event.resonance,
                                    event.attack, event.decay, event.sustain, event.release);
                            activeSampleSlots[event.track].add(slot);
                        } else {
                            voiceAllocator.releaseSample(slot);
                        }
                    }
                }
                case SAMPLE_NOTE_OFF -> {
                    releaseVoiceForTrack(event.track, event.midiNote,
                            sampleVoices, activeSampleSlots, voiceAllocator::releaseSample);
                }
                case DX7_NOTE_ON -> {
                    int slot = voiceAllocator.allocDx7(event.track, event.midiNote);
                    if (slot >= 0 && slot < dx7Voices.size()) {
                        dx7Voices.get(slot).trigger(event.track, event.midiNote, event.velocity);
                        activeDx7Slots[event.track].add(slot);
                    }
                }
                case DX7_NOTE_OFF -> {
                    releaseVoiceForTrack(event.track, event.midiNote,
                            dx7Voices, activeDx7Slots, voiceAllocator::releaseDx7);
                }
                case ALL_NOTES_OFF -> {
                    for (SynthVoice v : synthVoices) v.adsr.fastRelease();
                    for (SampleVoice v : sampleVoices) v.adsr.fastRelease();
                    for (NativeDx7Voice v : dx7Voices) v.release();
                    voiceAllocator.computePartitioning();
                    for (int t = 0; t < BridgeContract.TRACKS; t++) {
                        activeSynthSlots[t].clear();
                        activeSampleSlots[t].clear();
                        activeDx7Slots[t].clear();
                    }
                }
            }
        });
    }

    /** Find and release a voice for the given track + MIDI note. */
    private <V> void releaseVoiceForTrack(int track, int midiNote,
                                           List<V> voices,
                                           List<Integer>[] activeSlots,
                                           java.util.function.IntConsumer releaseFn) {
        List<Integer> slots = activeSlots[track];
        for (int i = slots.size() - 1; i >= 0; i--) {
            int slot = slots.get(i);
            if (slot >= 0 && slot < voices.size()) {
                V voice = voices.get(slot);
                // For SynthVoice / SampleVoice, trigger ADSR release
                if (voice instanceof SynthVoice sv) sv.adsr.keyOff();
                else if (voice instanceof SampleVoice sv) sv.adsr.keyOff();
                else if (voice instanceof NativeDx7Voice dv) dv.release();
                releaseFn.accept(slot);
                slots.remove(i);
            }
        }
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
            // ── Drain all pending events before rendering this buffer ──
            dispatchEvents();

            java.util.Arrays.fill(mixL, 0);
            java.util.Arrays.fill(mixR, 0);

            for (int i = 0; i < BUFFER_SIZE; i++) {
                if (i % 32 == 0) {
                    for (int l = 0; l < BridgeContract.LFO_COUNT; l++) {
                        lfoValues[l] = lfos[l].tick();
                    }
                }

                float trigger = 0;
                for (SynthVoice v : synthVoices) {
                    if (v.adsr.isActive()) {
                        v.applyModulation(lfoValues, lfoTracks, lfoDepths, lfoTargets);
                        float s = v.tick();
                        mixL[i] += s;
                        mixR[i] += s;
                    }
                }
                for (SampleVoice v : sampleVoices) {
                    if (v.adsr.isActive()) {
                        v.applyModulation(lfoValues, lfoTracks, lfoDepths, lfoTargets);
                        float s = v.tick();
                        mixL[i] += s;
                        mixR[i] += s;
                        if (v.trackIdx == 0) trigger = s;
                    }
                }
                for (NativeDx7Voice v : dx7Voices) {
                    if (v.isActive()) {
                        float s = v.tick();
                        mixL[i] += s;
                        mixR[i] += s;
                    }
                }

                float[] compressed = compressor.process(mixL[i], mixR[i], trigger);
                mixL[i] = compressed[0];
                mixR[i] = compressed[1];
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
                short sL = (short) (Math.max(-1, Math.min(1, outL)) * 32767);
                short sR = (short) (Math.max(-1, Math.min(1, outR)) * 32767);
                int idx = i * 4;
                buffer[idx] = (byte) (sL & 0xFF);
                buffer[idx + 1] = (byte) ((sL >> 8) & 0xFF);
                buffer[idx + 2] = (byte) (sR & 0xFF);
                buffer[idx + 3] = (byte) ((sR >> 8) & 0xFF);
            }
            line.write(buffer, 0, buffer.length);
        }
    }

    // ── Inner voice classes (unchanged) ──

    private static class SynthVoice {
        final NativeAdsr adsr = new NativeAdsr(SAMPLE_RATE);
        final NativeMoogFilter filter = new NativeMoogFilter(SAMPLE_RATE);
        int trackIdx = -1;
        double baseFreq = 0, phase = 0, phaseInc = 0, baseCutoff = 1000.0, baseResonance = 0.1;
        float gain = 0;

        void trigger(int t, double f, float g, double c, double r, double a, double d, double s, double rv) {
            this.trackIdx = t;
            this.baseFreq = f;
            this.phaseInc = 2.0 * Math.PI * f / SAMPLE_RATE;
            this.gain = g;
            this.baseCutoff = c;
            this.baseResonance = r;
            this.adsr.setParams(a, d, s, rv);
            this.adsr.keyOn();
            this.phase = 0;
        }

        void applyModulation(float[] lfos, int[] trks, float[] depths, int[] targets) {
            double mf = baseFreq, mc = baseCutoff;
            for (int l = 0; l < BridgeContract.LFO_COUNT; l++) {
                if (trks[l] == -1 || trks[l] == trackIdx) {
                    float mod = lfos[l] * depths[l];
                    if (targets[l] == 1) mf *= Math.pow(2.0, mod * 0.5);
                    else if (targets[l] == 2) mc *= Math.pow(2.0, mod * 2.0);
                }
            }
            this.phaseInc = 2.0 * Math.PI * mf / SAMPLE_RATE;
            this.filter.setParams(mc, baseResonance);
        }

        float tick() {
            float saw = (float) (2.0 * (phase / (2.0 * Math.PI)) - 1.0);
            phase += phaseInc;
            if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI;
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
            this.trackIdx = t;
            this.sndBuf.setSamples(s);
            this.gain = g;
            this.baseCutoff = c;
            this.baseResonance = r;
            this.adsr.setParams(a, d, sv, rv);
            this.adsr.keyOn();
            this.sndBuf.trigger();
        }

        void applyModulation(float[] lfos, int[] trks, float[] depths, int[] targets) {
            double mc = baseCutoff;
            for (int l = 0; l < BridgeContract.LFO_COUNT; l++) {
                if (trks[l] == -1 || trks[l] == trackIdx) {
                    float mod = lfos[l] * depths[l];
                    if (targets[l] == 2) mc *= Math.pow(2.0, mod * 2.0);
                }
            }
            this.filter.setParams(mc, baseResonance);
        }

        float tick() {
            float s = sndBuf.tick();
            if (!sndBuf.isActive()) adsr.keyOff();
            return filter.tick(s * gain * adsr.tick());
        }
    }

    public static void launch(BridgeContract bridge) {
        new NativeJavaSequencer(bridge).start();
    }
}
