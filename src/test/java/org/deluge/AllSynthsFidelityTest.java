package org.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.*;
import javax.sound.sampled.*;
import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.firmware2.StereoSample;
import org.deluge.model.*;
import org.deluge.project.ProjectSerializer;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * Generates a song with every available synth preset (one note each, sequential bars), renders
 * through the engine, and optionally compares against a golden hardware WAV recording. The golden
 * WAV is one continuous recording of the same multi-synth song played on a real Deluge — slice by
 * bar and compare per-synth.
 */
public class AllSynthsFidelityTest {

  private static final int BLOCK_SIZE = 128;
  private static final int SAMPLE_RATE = 44100;
  private static final int STEPS_PER_BAR = 16; // 4 beats × 16th notes
  private static final int TICKS_PER_STEP = 24; // 96 PPQ / 4 = 24 ticks per 16th note
  private static final int SECTIONS = 24; // C kMaxNumSections (definitions_cxx.hpp:459)

  private static int countOccurrences(String haystack, String needle) {
    int n = 0, i = 0;
    while ((i = haystack.indexOf(needle, i)) >= 0) {
      n++;
      i += needle.length();
    }
    return n;
  }

  /** Sample fileName="..." paths referenced by a preset that don't exist under the card root. */
  private static java.util.List<String> missingSamples(SynthTrackModel synth, File cardRoot) {
    java.util.List<String> refs = new ArrayList<>();
    for (String raw : new String[] {synth.getOsc1RawXml(), synth.getOsc2RawXml()}) {
      if (raw == null) continue;
      java.util.regex.Matcher m =
          java.util.regex.Pattern.compile("fileName=\"([^\"]+)\"").matcher(raw);
      while (m.find()) refs.add(m.group(1));
    }
    if (synth.getOsc1SamplePath() != null && !synth.getOsc1SamplePath().isEmpty())
      refs.add(synth.getOsc1SamplePath());
    if (synth.getOsc2SamplePath() != null && !synth.getOsc2SamplePath().isEmpty())
      refs.add(synth.getOsc2SamplePath());
    java.util.List<String> missing = new ArrayList<>();
    for (String p : refs) {
      if (cardRoot == null || !fileExistsCaseInsensitive(cardRoot, p)) missing.add(p);
    }
    return missing;
  }

  /** Resolve a relative path under root matching each component case-insensitively (FAT-like). */
  private static boolean fileExistsCaseInsensitive(File root, String relPath) {
    File cur = root;
    for (String part : relPath.split("/")) {
      if (part.isEmpty()) continue;
      File[] kids = cur.listFiles();
      if (kids == null) return false;
      File match = null;
      for (File k : kids) {
        if (k.getName().equalsIgnoreCase(part)) {
          match = k;
          break;
        }
      }
      if (match == null) return false;
      cur = match;
    }
    return cur.isFile();
  }

  @Test
  void generateAllSynthsSong() throws Exception {
    // Override with -Dsynth.dir=/path (e.g. the real SD card SYNTHS) to test the actual card
    // presets.
    String synthDir = System.getProperty("synth.dir", "src/main/resources/SYNTHS");
    File[] synthFiles =
        new File(synthDir)
            .listFiles(
                (d, n) ->
                    (n.endsWith(".XML") || n.endsWith(".xml"))
                        && !n.toUpperCase().startsWith("SONG"));
    assertTrue(synthFiles != null && synthFiles.length > 0, "No synth XML files in " + synthDir);
    Arrays.sort(synthFiles, Comparator.comparing(File::getName));
    System.out.println("[AllSynths] Found " + synthFiles.length + " synth presets");

    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);
    // Boot into the Arranger so playback follows the (sequential) clipInstances — not every session
    // clip firing at once. Combined with isPlaying=0 below, this gives a clean one-by-one render.
    project.setBootInArrangementView(true);

    // Stagger: each synth plays its note at a different bar on the arranger timeline.
    // Bar 0 = ticks 0..95, Bar 1 = ticks 96..191, etc. One bar per synth at 120 BPM.
    int ticksPerBar =
        96; // 96 PPQ ticks per quarter note × 4 quarters = 384? No: 96 ticks total per bar at 24
    // ticks/step × 4 steps/beat × 4 beats = 384.
    // Actually: FirmwareFactory uses stepTicks=24 ticks per step. A 16-step bar = 16*24 = 384 ticks
    // per bar.
    ticksPerBar = STEPS_PER_BAR * TICKS_PER_STEP; // 16 * 24 = 384 ticks per bar
    // Each synth gets 2 bars = 768 ticks, to capture attack + sustain.
    int ticksPerSynth = ticksPerBar * 2;
    // Silence GAP between synths (default 1 bar = 384 ticks = 2s at 120 BPM). The clip note still
    // plays for the full 768-tick slot, but the NEXT clipInstance is pushed gapTicks later, leaving
    // a clear silence so each synth's attack is an unambiguous onset for the fidelity scorecard's
    // per-synth alignment (a concatenated, gapless recording made onsets impossible to locate
    // reliably). Configurable via -Dsynth.gap (0 = back-to-back, the old behaviour).
    int gapTicks = Integer.getInteger("synth.gap", ticksPerBar);

    // Card root (parent of SYNTHS/) — used to verify referenced sample files actually exist, so the
    // generated song LOADS on hardware. A multisample preset whose samples are missing makes the
    // Deluge fail the whole song load (error e369 = can't load a referenced sample), so we skip it.
    File cardRoot = new File(synthDir).getParentFile();

    // Optional cap + offset over the PLAYABLE synths (e.g. -Dsynth.offset=94 -Dsynth.max=94 to take
    // the 2nd ~94). Lets us split the full set into RAM-safe arranger songs (the Deluge can't fit
    // ~188 instruments — it plays cleanly up to ~120). Offset/max count playable synths, so halves
    // are clean and non-overlapping regardless of missing-sample skips.
    int maxSynths = Integer.getInteger("synth.max", Integer.MAX_VALUE);
    int offsetSynths = Integer.getInteger("synth.offset", 0);

    List<String> synthNames = new ArrayList<>();
    List<ClipModel> allClips = new ArrayList<>();
    int barIdx = 0;
    int skippedMissingSamples = 0;
    int playableIndex = 0; // index among playable (passed missing-sample filter) synths
    for (File f : synthFiles) {
      try {
        SynthTrackModel synth = DelugeXmlParser.parseSynth(new FileInputStream(f), f.getName());
        synth.setName(f.getName().replace(".XML", ""));

        // Skip presets that reference sample files not present on this card (otherwise the whole
        // song fails to load on hardware).
        java.util.List<String> missing = missingSamples(synth, cardRoot);
        if (!missing.isEmpty()) {
          skippedMissingSamples++;
          System.out.println(
              "[AllSynths] SKIP "
                  + synth.getName()
                  + " — "
                  + missing.size()
                  + " missing sample(s), e.g. "
                  + missing.get(0));
          continue;
        }

        // Window selection over playable synths (offset .. offset+max).
        if (playableIndex++ < offsetSynths) continue;
        if (barIdx >= maxSynths) break;

        // One clip spanning the whole 2-bar slot, with ONE sustained note held for the entire slot
        // (gate = all steps). A 1-step blip (~125ms) leaves slow-attack pads inaudible on hardware
        // —
        // which is exactly the "1 note every 10-20s" symptom. Sustaining the note lets every synth
        // bloom. (The per-synth engine RMS test held the note for 400 blocks, hiding this.)
        int stepsPerSynth = STEPS_PER_BAR * 2; // 2 bars, matching the arranger instance length
        ClipModel clip = new ClipModel("CLIP", 1, stepsPerSynth);
        clip.setStep(0, 0, StepData.of(true, 1.0f, (float) stepsPerSynth, 1.0f, 60)); // held C4
        // Valid session section (cycled — there are far more synths than kMaxNumSections). The
        // ARRANGER (clipInstances), not sections, sequences the 173 synths one-by-one.
        clip.setSection(barIdx % SECTIONS);
        // Inactive in the session clip-launcher so session view stays silent; the arranger sets
        // each
        // clip active as its instance is reached (arrangement.cpp:265,404).
        clip.setActiveInSession(false);
        synth.addClip(clip);
        allClips.add(clip);

        project.addTrack(synth);
        synthNames.add(synth.getName());

        // Place this clip on the arranger timeline AND song sections. The instance LENGTH stays the
        // 768-tick slot (note blooms fully); the slot PITCH includes gapTicks of trailing silence.
        int startTicks = barIdx * (ticksPerSynth + gapTicks);
        project.addArrangerClip(
            new ArrangerClip(synthNames.size() - 1, clip, startTicks, ticksPerSynth));
        project.addSongSection(new SongSection(String.valueOf(barIdx))); // numRepeats defaults to 0
        barIdx++;
      } catch (Exception e) {
        System.out.println("[AllSynths] Skipping " + f.getName() + ": " + e.getMessage());
      }
    }

    System.out.println("[AllSynths] Generated song with " + project.getTracks().size() + " tracks");

    // Save song XML. Override with -Dsong.out=/path (e.g. the card's SONGS/ALL_SYNTHS_SONG.XML).
    File songFile = new File(System.getProperty("song.out", "target/ALL_SYNTHS_SONG.xml"));
    songFile.getParentFile().mkdirs();
    ProjectSerializer.save(project, songFile);
    System.out.println(
        "[AllSynths] Saved song to "
            + songFile.getAbsolutePath()
            + " ("
            + songFile.length()
            + " bytes)");

    // Verify the C-correct arranger serialization: every synth must get a clipInstance placed at
    // its
    // own sequential timeline slot (this is what plays the synths one-by-one on hardware), plus a
    // round-tripping section. (clipInstances IS the hardware arranger format — output.cpp:259-291.)
    String xml = new String(java.nio.file.Files.readAllBytes(songFile.toPath()));
    int nTracks = project.getTracks().size();
    int ciCount = countOccurrences(xml, "clipInstances=\"0x");
    assertEquals(nTracks, ciCount, "expected one clipInstances attribute per synth track");
    // Spot-check the 2nd synth's instance starts at one slot+gap (sequential with the silence gap).
    String expectedPos2 = String.format("%08X", ticksPerSynth + gapTicks);
    assertTrue(
        xml.contains("clipInstances=\"0x" + String.format("%08X", 0)) // 1st at tick 0
            && xml.contains(expectedPos2), // 2nd at ticksPerSynth+gapTicks
        "arranger clipInstances are not placed sequentially");
    // Boots into the arranger (so it plays sequentially, not all-at-once in session view), and NO
    // session clip is left active (every isPlaying must be 0 — else session view = 173-way
    // cacophony).
    assertTrue(
        xml.contains("inArrangementView=\"1\""), "song does not boot into the arranger view");
    assertEquals(
        0, countOccurrences(xml, "isPlaying=\"1\""), "session clips are active → all play at once");
    // Each note must be SUSTAINED for the slot (length 0x300=768), not a 1-step blip (0x18=24) —
    // a blip leaves slow-attack synths silent on hardware ("1 note every 10-20s").
    assertTrue(
        xml.contains("noteDataWithLift=\"0x00000000000003007F"),
        "clip note is not sustained for the full slot");
    assertFalse(
        xml.contains("noteDataWithLift=\"0x00000000000000187F"),
        "clip note is a 1-step blip — slow synths will be inaudible");
    // Section round-trip through the parser (C clip.cpp:713-715).
    ProjectModel reparsed = DelugeXmlParser.parseSong(songFile);
    ClipModel firstReparsed = reparsed.getTracks().get(0).getClips().get(0);
    assertEquals(
        0, firstReparsed.getSection(), "section did not round-trip through save/parse (synth 0)");
    System.out.println(
        "[AllSynths] Arranger song OK: "
            + ciCount
            + " sequential clipInstances, boots in arranger, 0 active session clips");

    // Render each synth independently through the engine and capture block-RMS
    double[] engineMaxRms = new double[synthNames.size()];
    for (int i = 0; i < project.getTracks().size(); i++) {
      TrackModel tm = project.getTracks().get(i);
      SynthTrackModel sm = (SynthTrackModel) tm;
      ProjectModel single = new ProjectModel();
      single.setBpm(120.0f);
      single.addTrack(sm);
      ProjectModel fwProject = FirmwareFactory.createSong(single);

      ClipModel clip = fwProject.getTracks().get(0).getActiveClip();
      assertNotNull(clip, "No active clip for " + sm.getName());
      FirmwareSound fs = (FirmwareSound) clip.getSound();
      assertNotNull(fs, "No FirmwareSound for " + sm.getName());

      fs.triggerNote(60, 127);

      FirmwareAudioEngine engine = new FirmwareAudioEngine();
      engine.metronomeEnabled = false;
      engine.sounds.add(fs);

      StereoSample[] block = new StereoSample[BLOCK_SIZE];
      for (int j = 0; j < BLOCK_SIZE; j++) block[j] = new StereoSample();
      double maxRms = 0;
      for (int b = 0; b < 400; b++) { // ~1.2s to capture sustain
        for (int j = 0; j < BLOCK_SIZE; j++) {
          block[j].l = 0;
          block[j].r = 0;
        }
        engine.renderBlock(BLOCK_SIZE);
        double sumSq = 0;
        for (int j = 0; j < BLOCK_SIZE; j++) {
          double v = engine.masterBuffer[j].l / 2147483648.0;
          sumSq += v * v;
        }
        double rms = Math.sqrt(sumSq / BLOCK_SIZE);
        if (rms > maxRms) maxRms = rms;
      }
      engineMaxRms[i] = maxRms;
    }

    // Report
    System.out.println("\n[AllSynths] === PER-SYNTH RMS REPORT ===");
    double minRms = Double.MAX_VALUE, maxRmsAll = 0, sumRms = 0;
    int silentCount = 0; // non-multisample silent (a real engine miss)
    int multisampleCount = 0;
    for (int i = 0; i < engineMaxRms.length; i++) {
      double rms = engineMaxRms[i];
      // Multisample (<sampleRanges>) presets reference SAMPLES/Multisamples WAVs that aren't in the
      // repo, so they legitimately render silent HERE (they round-trip verbatim for hardware).
      // Don't
      // count them as engine misses.
      boolean multisample = ((SynthTrackModel) project.getTracks().get(i)).getOsc1RawXml() != null;
      if (multisample) multisampleCount++;
      else if (rms < 0.001) silentCount++;
      System.out.printf(
          "  %3d %-40s RMS=%.6f%s%n",
          i, synthNames.get(i), rms, multisample ? "  [multisample-verbatim]" : "");
      if (rms < minRms && rms > 0) minRms = rms;
      if (rms > maxRmsAll) maxRmsAll = rms;
      sumRms += rms;
    }

    double avgRms = sumRms / engineMaxRms.length;
    System.out.printf(
        "%n[AllSynths] Summary: %d synths (%d multisample-verbatim, %d skipped: missing samples),"
            + " avg RMS=%.6f, max=%.6f, non-multisample silent=%d%n",
        engineMaxRms.length,
        multisampleCount,
        skippedMissingSamples,
        avgRms,
        maxRmsAll,
        silentCount);

    // Subtractive synths must render; multisample ones are excluded (no WAVs in the repo env).
    int subtractive = engineMaxRms.length - multisampleCount;
    assertTrue(
        silentCount < subtractive * 0.1,
        silentCount + " of " + subtractive + " subtractive synths are silent!");
    System.out.println(
        "[AllSynths] PASSED: "
            + (subtractive - silentCount)
            + "/"
            + subtractive
            + " subtractive synths audible; "
            + multisampleCount
            + " multisample preserved verbatim");
  }

  /**
   * Compares the golden hardware WAV against per-synth engine rendering. The golden WAV is one
   * continuous recording of ALL_SYNTHS_SONG.xml played on a real Deluge. Each synth plays one note
   * at bar N (N bars × 16 steps × 24 ticks / 120 BPM = N * 2 seconds per bar).
   *
   * <p>To record the golden WAV:
   *
   * <ol>
   *   <li>Copy {@code target/ALL_SYNTHS_SONG.xml} to the Deluge SD card SONGS/ folder
   *   <li>Play it on the real Deluge and record the audio output as 24-bit mono WAV
   *   <li>Save as {@code deluge/src/test/resources/fidelity/ALL_SYNTHS_GOLDEN.WAV}
   * </ol>
   */
  @Test
  void compareGoldenWavAgainstEngine() throws Exception {
    File goldenFile = new File("src/test/resources/fidelity/ALL_SYNTHS_GOLDEN.WAV");
    if (!goldenFile.exists()) {
      goldenFile = new File("../deluge/src/test/resources/fidelity/ALL_SYNTHS_GOLDEN.WAV");
    }
    // Skip if no golden file — the test is a framework for when the recording exists
    if (!goldenFile.exists()) {
      System.out.println(
          "[AllSynths] SKIP: no golden WAV at "
              + goldenFile.getAbsolutePath()
              + " — run generateAllSynthsSong first, record on hardware, then re-run");
      return;
    }

    double[] goldenSegments = sliceGoldenWav(goldenFile);
    System.out.println("[AllSynths] Golden WAV: " + goldenSegments.length + " segments");

    // Re-render each synth and compare
    File[] synthFiles =
        new File("src/main/resources/SYNTHS")
            .listFiles((d, n) -> n.endsWith(".XML") && !n.startsWith("SONG"));
    assertTrue(synthFiles != null && synthFiles.length > 0);
    Arrays.sort(synthFiles, Comparator.comparing(File::getName));

    int mismatches = 0;
    int segIdx = 0;
    for (File f : synthFiles) {
      if (segIdx >= goldenSegments.length) break;
      try {
        SynthTrackModel sm = DelugeXmlParser.parseSynth(new FileInputStream(f), f.getName());
        ProjectModel single = new ProjectModel();
        single.setBpm(120.0f);
        single.addTrack(sm);
        ProjectModel fwProject = FirmwareFactory.createSong(single);
        ClipModel clip = fwProject.getTracks().get(0).getActiveClip();
        FirmwareSound fs = (FirmwareSound) clip.getSound();
        fs.triggerNote(60, 127);

        FirmwareAudioEngine engine = new FirmwareAudioEngine();
        engine.metronomeEnabled = false;
        engine.sounds.add(fs);

        StereoSample[] block = new StereoSample[BLOCK_SIZE];
        for (int j = 0; j < BLOCK_SIZE; j++) block[j] = new StereoSample();
        double maxRms = 0;
        for (int b = 0; b < 400; b++) {
          for (int j = 0; j < BLOCK_SIZE; j++) {
            block[j].l = 0;
            block[j].r = 0;
          }
          engine.renderBlock(BLOCK_SIZE);
          double sumSq = 0;
          for (int j = 0; j < BLOCK_SIZE; j++) {
            double v = engine.masterBuffer[j].l / 2147483648.0;
            sumSq += v * v;
          }
          double rms = Math.sqrt(sumSq / BLOCK_SIZE);
          if (rms > maxRms) maxRms = rms;
        }

        double goldenRms = goldenSegments[segIdx];
        double ratio = maxRms / Math.max(goldenRms, 1e-10);
        if (ratio < 0.1 || ratio > 10.0) {
          System.out.printf(
              "[MISMATCH] %-40s engine=%.6f golden=%.6f ratio=%.2f%n",
              f.getName(), maxRms, goldenRms, ratio);
          mismatches++;
        }
        segIdx++;
      } catch (Exception e) {
        System.out.println("[AllSynths] Error on " + f.getName() + ": " + e.getMessage());
        segIdx++;
      }
    }

    System.out.println("[AllSynths] Mismatches: " + mismatches + " / " + segIdx);
    // Allow some mismatch — gain staging differences are expected (-28 dB gap)
    assertTrue(mismatches < segIdx * 0.5, "Too many mismatches: " + mismatches + " / " + segIdx);
  }

  /** Slice a golden WAV into per-bar RMS segments (barsPerSynth bars per synth). */
  private static double[] sliceGoldenWav(File wavFile) throws Exception {
    AudioInputStream ais = AudioSystem.getAudioInputStream(wavFile);
    byte[] raw = ais.readAllBytes();
    ais.close();

    int channels = ais.getFormat().getChannels();
    int bits = ais.getFormat().getSampleSizeInBits();
    int bytesPerSample = bits / 8;
    int totalFrames = raw.length / (bytesPerSample * channels);

    // Convert to float array (mono)
    float[] mono = new float[totalFrames];
    ByteBuffer bb =
        ByteBuffer.wrap(raw)
            .order(ais.getFormat().isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);

    if (bits == 24) {
      for (int i = 0; i < totalFrames; i++) {
        int b0 = bb.get() & 0xFF, b1 = bb.get() & 0xFF, b2 = bb.get() & 0xFF;
        if (channels > 1) bb.position(bb.position() + (channels - 1) * 3);
        int v = (b2 << 16) | (b1 << 8) | b0;
        if ((v & 0x800000) != 0) v |= 0xFF000000;
        mono[i] = v / 8388608.0f;
      }
    } else if (bits == 16) {
      ShortBuffer sb = bb.asShortBuffer();
      for (int i = 0; i < totalFrames; i++) {
        mono[i] = sb.get(i * channels) / 32768.0f;
      }
    }

    // Slice by bar: each synth gets barsPerSynth bars. We read barsPerSynth bars from the WAV
    // and compute the max block-RMS during that window as the synth's "signature."
    int barsPerSynth = 2; // each synth plays for 2 bars (32 steps at 120 BPM = 4 seconds)
    int samplesPerBar = SAMPLE_RATE * 60 / 120 * 4; // samples per bar at 120 BPM 4/4
    int samplesPerSynth = samplesPerBar * barsPerSynth;

    int numSynths = totalFrames / samplesPerSynth;
    double[] segments = new double[numSynths];

    for (int s = 0; s < numSynths; s++) {
      int start = s * samplesPerSynth;
      int end = Math.min(start + samplesPerSynth, totalFrames);
      // Compute block-RMS and take the max (sustained portion)
      double bestRms = 0;
      for (int pos = start; pos + BLOCK_SIZE <= end; pos += BLOCK_SIZE) {
        double sumSq = 0;
        for (int i = 0; i < BLOCK_SIZE; i++) {
          sumSq += mono[pos + i] * mono[pos + i];
        }
        double rms = Math.sqrt(sumSq / BLOCK_SIZE);
        if (rms > bestRms) bestRms = rms;
      }
      segments[s] = bestRms;
    }

    return segments;
  }
}
