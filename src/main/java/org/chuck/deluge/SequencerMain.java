package org.chuck.deluge;

import org.chuck.core.ChuckVM;
import org.chuck.deluge.engine.NativeJavaSequencer;
import org.chuck.deluge.model.*;
import org.chuck.deluge.project.PreferencesManager;
import org.chuck.deluge.xml.DelugeXmlParser;

import java.io.File;
import java.io.FileInputStream;

/**
 * CLI headless launcher for the Deluge sequencer.
 *
 * <p>Takes a song.xml path (relative or absolute), resolves the KITS / SYNTHS / SAMPLES
 * directories relative to the song's parent directory, parses the song, pushes all
 * track data into the {@link BridgeContract}, and starts playback with no UI.
 *
 * <p>Default engine is the Pure-Java native sequencer. Use {@code --chuck} to use the
 * ChucK engine DSL instead.
 *
 * <p>Usage:
 * <pre>
 *   java org.chuck.deluge.SequencerMain [--chuck] path/to/song.xml
 * </pre>
 *
 * <p>If the path is relative, it is resolved against the current working directory.
 * Sample paths in the XML (e.g. {@code SAMPLES/DRUMS/Kick/kick_01.wav}) are resolved
 * by trying: (1) relative to the song file's parent directory, (2) relative to
 * {@link PreferencesManager#getLibraryDir()}, (3) as an absolute fallback.
 */
public class SequencerMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        boolean useChuck = false;
        String songArg = null;

        for (String arg : args) {
            if ("--chuck".equals(arg)) {
                useChuck = true;
            } else if (!arg.startsWith("--")) {
                songArg = arg;
            }
        }

        if (songArg == null) {
            printUsage();
            System.exit(1);
        }

        File songFile = new File(songArg);
        if (!songFile.isAbsolute()) {
            songFile = new File(System.getProperty("user.dir"), songArg).getCanonicalFile();
        }
        if (!songFile.exists()) {
            System.err.println("[SequencerMain] Song file not found: " + songFile);
            System.exit(1);
        }

        File songDir = songFile.getParentFile();
        System.out.println("[SequencerMain] Loading song: " + songFile);

        // Parse the song XML
        ProjectModel project = DelugeXmlParser.parseSong(new FileInputStream(songFile), songFile.getName());
        System.out.println("[SequencerMain] Parsed " + project.getTracks().size() + " track(s), BPM=" + project.getBpm());

        if (useChuck) {
            runWithChuckEngine(project, songFile, songDir);
        } else {
            runWithNativeEngine(project, songFile, songDir);
        }
    }

    private static void printUsage() {
        System.err.println("Usage: java org.chuck.deluge.SequencerMain [--chuck] <song.xml>");
        System.err.println("  --chuck        Use the ChucK engine DSL (default: Pure-Java native sequencer)");
        System.err.println("  <song.xml>     Path to a Deluge song XML file (relative or absolute)");
        System.err.println("  KITS, SYNTHS, SAMPLES dirs are resolved relative to the song's parent.");
    }

    // ── Pure-Java Native Engine ──

    private static void runWithNativeEngine(ProjectModel project, File songFile, File songDir) throws Exception {
        File libDir = resolveLibraryDir(songDir);

        // Bridge setup (no ChuckVM needed for Pure-Java engine)
        BridgeContract bridge = new BridgeContract();

        pushProjectToBridge(project, songDir, libDir, bridge);

        System.out.println("[SequencerMain] Starting Pure-Java native sequencer...");

        // Launch the Pure-Java sequencer engine
        NativeJavaSequencer sequencer = new NativeJavaSequencer(bridge);
        sequencer.start();
        bridge.setPlayState(1);

        System.out.println("[SequencerMain] Engine running. Press Ctrl+C to stop.");

        // Keep alive until Ctrl+C
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[SequencerMain] Shutting down...");
            sequencer.stop();
        }));

        //noinspection InfiniteLoopStatement
        while (true) {
            Thread.sleep(1000);
        }
    }

    // ── ChucK Engine DSL ──

    private static void runWithChuckEngine(ProjectModel project, File songFile, File songDir) throws Exception {
        File libDir = resolveLibraryDir(songDir);

        // Standard ChuckVM + BridgeContract setup (copying SwingDelugeApp.main pattern)
        ChuckVM vm = new ChuckVM(44100, 2);
        BridgeContract bridge = new BridgeContract();
        bridge.register(vm);

        org.chuck.audio.ChuckAudio audio = new org.chuck.audio.ChuckAudio(vm, 1024, 2, 44100);
        vm.setAudio(audio);
        audio.start();

        vm.spork(new org.chuck.deluge.engine.DelugeEngineDSL());

        pushProjectToBridge(project, songDir, libDir, bridge);

        // Set the song directory so engine can resolve sample paths
        vm.setGlobalString("g_song_dir", songDir.getAbsolutePath());
        vm.setGlobalString("g_library_dir", libDir.getAbsolutePath());

        // Broadcast load trigger so the ChucK engine initialises its arrays
        vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);

        System.out.println("[SequencerMain] ChucK engine running. Press Ctrl+C to stop.");

        bridge.setPlayState(1);

        // Keep alive until Ctrl+C
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[SequencerMain] Shutting down...");
            bridge.setPlayState(0);
            vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
        }));

        //noinspection InfiniteLoopStatement
        while (true) {
            Thread.sleep(1000);
        }
    }

    // ── Shared bridge data push ──

    /**
     * Push the project model into the bridge, resolving sample paths.
     * Works identically for both the Pure-Java and ChucK engines.
     */
    private static void pushProjectToBridge(ProjectModel project, File songDir, File libDir, BridgeContract bridge) {
        // Push global params
        bridge.setBpm(project.getBpm());
        bridge.setSwing(project.getSwing());
        bridge.setMasterVol(project.getMasterVolume());
        bridge.setDelayParams(project.getMasterDelay(), 0.3);
        bridge.setReverbParams(project.getMasterReverb(), 0.5);

        bridge.clearPattern();
        for (int i = 0; i < BridgeContract.TRACKS; i++) {
            bridge.setMute(i, false);
            bridge.setTrackLength(i, 16);
            bridge.setTrackType(i, -1);
        }

        // Push tracks
        int engineRow = 0;
        for (TrackModel track : project.getTracks()) {
            if (track instanceof KitTrackModel kit) {
                int voiceCount = Math.min(kit.getSounds().size(), BridgeContract.TRACKS - engineRow);
                for (int v = 0; v < voiceCount; v++) {
                    int er = engineRow + v;
                    bridge.setTrackType(er, 0);

                    KitTrackModel.KitSound snd = kit.getSounds().get(v);
                    String rawPath = snd.getSamplePath();
                    String resolved = resolveSamplePath(rawPath, songDir, libDir, snd.getName());
                    bridge.setSamplePath(er, resolved);

                    bridge.setFilterFreq(er, snd.getLpfFreq() / 20000.0f);
                    bridge.setFilterRes(er, snd.getLpfRes() / 100.0f);
                    bridge.setTrackLevel(er, 1.0);

                    EnvelopeModel adsr = snd.getAdsr();
                    if (adsr != null) {
                        bridge.setEnv(er, 0, adsr.attack(), adsr.decay(), adsr.sustain(), adsr.release());
                    }
                }

                // Push clip pattern data
                int clipIdx = kit.getActiveClipIndex();
                if (clipIdx >= 0 && clipIdx < kit.getClips().size()) {
                    ClipModel clip = kit.getClips().get(clipIdx);
                    int stepCount = clip.getStepCount();
                    for (int r = 0; r < clip.getRowCount(); r++) {
                        for (int s = 0; s < stepCount; s++) {
                            StepData step = clip.getStep(r, s);
                            if (step != null && step.active() && r < voiceCount) {
                                bridge.setStep(engineRow + r, s, true);
                                bridge.setVelocity(engineRow + r, s, step.velocity());
                            }
                        }
                    }
                }

                engineRow += voiceCount;

            } else if (track instanceof SynthTrackModel synth) {
                int voiceCount = Math.min(8, BridgeContract.TRACKS - engineRow);

                // Determine total rows from clip
                int activeClipIdx = synth.getActiveClipIndex();
                int totalRows = voiceCount;
                if (activeClipIdx >= 0 && activeClipIdx < synth.getClips().size()) {
                    totalRows = Math.max(voiceCount, synth.getClips().get(activeClipIdx).getRowCount());
                }
                totalRows = Math.min(totalRows, BridgeContract.TRACKS - engineRow);

                for (int v = 0; v < totalRows; v++) {
                    int er = engineRow + v;
                    bridge.setTrackType(er, 1);
                    bridge.setSynthAlgo(er, synth.getSynthAlgorithm());
                    bridge.setFilterFreq(er, synth.getLpfFreq() / 20000.0f);
                    bridge.setFilterRes(er, synth.getLpfRes() / 100.0f);
                    bridge.setFilterMode(er, synth.getFilterMode().ordinal());
                    bridge.setFilterMorph(er, synth.getLpfMorph());
                    bridge.setSynthMode(er, synth.getSynthMode());
                    bridge.setFmRatio(er, synth.getFmRatio());
                    bridge.setFmAmount(er, synth.getFmAmount());
                    bridge.setMod1Fb(er, synth.getModulator1Feedback());
                    bridge.setMod2Amt(er, synth.getModulator2Amount());
                    bridge.setMod2Fb(er, synth.getModulator2Feedback());
                    bridge.setCarrier1Fb(er, synth.getCarrier1Feedback());
                    bridge.setCarrier2Fb(er, synth.getCarrier2Feedback());
                    bridge.setHpfFreq(er, synth.getHpfFreq());
                    bridge.setHpfRes(er, synth.getHpfRes());
                    bridge.setPolyphony(er, synth.getPolyphony().ordinal());
                    bridge.setTrackLevel(er, 1.0);
                }

                // Push ADSR envelopes (4 envs)
                for (int e = 0; e < 4; e++) {
                    EnvelopeModel adsr = synth.getEnv(e);
                    if (adsr != null) {
                        for (int v = 0; v < totalRows; v++) {
                            bridge.setEnv(engineRow + v, e, adsr.attack(), adsr.decay(), adsr.sustain(), adsr.release());
                        }
                    }
                }

                // Push LFO params (4 LFOs)
                for (int l = 0; l < 4; l++) {
                    LfoModel lfo = synth.getLfo(l);
                    if (lfo != null) {
                        bridge.setLfo(l, lfo.rateHz(), lfo.waveform().ordinal(), lfo.depth());
                    }
                }

                // Push arp params
                ArpModel arp = synth.getArp();
                if (arp != null) {
                    int arpMode = switch (arp.mode()) {
                        case "DOWN" -> 1;
                        case "UP_DOWN" -> 2;
                        case "RANDOM" -> 3;
                        default -> 0;
                    };
                    for (int v = 0; v < totalRows; v++) {
                        bridge.setArpOn(engineRow + v, arp.active());
                        bridge.setArpRate(engineRow + v, arp.rate());
                        bridge.setArpOctave(engineRow + v, arp.octaves());
                        bridge.setArpMode(engineRow + v, arpMode);
                    }
                }

                // Push clip pattern data
                if (activeClipIdx >= 0 && activeClipIdx < synth.getClips().size()) {
                    ClipModel clip = synth.getClips().get(activeClipIdx);
                    int stepCount = clip.getStepCount();
                    for (int r = 0; r < clip.getRowCount(); r++) {
                        int er = engineRow + r;
                        for (int s = 0; s < stepCount; s++) {
                            StepData step = clip.getStep(r, s);
                            if (step != null && step.active()) {
                                bridge.setStep(er, s, true);
                                bridge.setVelocity(er, s, step.velocity());
                            }
                        }
                    }
                }

                engineRow += totalRows;

            } else if (track instanceof AudioTrackModel audio) {
                int er = engineRow;
                bridge.setTrackType(er, 2);
                bridge.setTrackLevel(er, 1.0);

                int clipIdx = audio.getActiveClipIndex();
                if (clipIdx >= 0 && clipIdx < audio.getClips().size()) {
                    ClipModel clip = audio.getClips().get(clipIdx);
                    int stepCount = clip.getStepCount();
                    for (int r = 0; r < clip.getRowCount(); r++) {
                        for (int s = 0; s < stepCount; s++) {
                            StepData step = clip.getStep(r, s);
                            if (step != null && step.active()) {
                                bridge.setStep(er + r, s, true);
                                bridge.setVelocity(er + r, s, step.velocity());
                            }
                        }
                    }
                }
                engineRow += 1;
            }
        }

        System.out.println("[SequencerMain] Pushed " + engineRow + " engine rows to bridge.");
    }

    // ── Path resolution ──

    /**
     * Resolve the library root directory (parent of KITS, SYNTHS, SAMPLES).
     *
     * <p>Tries, in order:
     * <ol>
     *   <li>The song file's parent directory (if it contains a SAMPLES subdirectory)</li>
     *   <li>{@link PreferencesManager#getLibraryDir()}</li>
     *   <li>The song file's parent directory as-is</li>
     * </ol>
     */
    private static File resolveLibraryDir(File songDir) {
        if (new File(songDir, "SAMPLES").isDirectory()) {
            return songDir;
        }
        File parent = songDir.getParentFile();
        if (parent != null && new File(parent, "SAMPLES").isDirectory()) {
            return parent;
        }
        File grandparent = parent != null ? parent.getParentFile() : null;
        if (grandparent != null && new File(grandparent, "SAMPLES").isDirectory()) {
            return grandparent;
        }
        File prefLib = PreferencesManager.getLibraryDir();
        if (prefLib.isDirectory()) {
            return prefLib;
        }
        return songDir;
    }

    /**
     * Resolve a Deluge-relative sample path to an absolute file path.
     *
     * <p>The song XML stores sample paths relative to the library root
     * (e.g. {@code SAMPLES/DRUMS/Kick/kick_01.wav}). This method tries:
     * <ol>
     *   <li>Relative to the song file's parent directory</li>
     *   <li>Relative to {@link PreferencesManager#getLibraryDir()}</li>
     *   <li>As an absolute path</li>
     * </ol>
     */
    private static String resolveSamplePath(String rawPath, File songDir, File libDir, String soundName) {
        if (rawPath == null || rawPath.isEmpty()) return "";

        File asAbs = new File(rawPath);
        if (asAbs.isAbsolute() && asAbs.exists()) return asAbs.getAbsolutePath();

        File fromSong = new File(songDir, rawPath);
        if (fromSong.exists()) return fromSong.getAbsolutePath();

        File fromLib = new File(libDir, rawPath);
        if (fromLib.exists()) return fromLib.getAbsolutePath();

        if (soundName != null && !soundName.isEmpty()) {
            File byName = new File(libDir, "SAMPLES/" + soundName + ".wav");
            if (byName.exists()) return byName.getAbsolutePath();
            byName = new File(songDir, "SAMPLES/" + soundName + ".wav");
            if (byName.exists()) return byName.getAbsolutePath();
        }

        System.err.println("[SequencerMain] WARNING: sample not found: " + rawPath
            + " (tried: " + fromSong + ", " + fromLib + ")");
        return rawPath;
    }
}
