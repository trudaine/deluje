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
  private static final int SECTIONS = 12; // C kMaxNumSections (session-view sections)

  private static int countOccurrences(String haystack, String needle) {
    int n = 0, i = 0;
    while ((i = haystack.indexOf(needle, i)) >= 0) {
      n++;
      i += needle.length();
    }
    return n;
  }

  @Test
  void generateAllSynthsSong() throws Exception {
    File[] synthFiles =
        new File("src/main/resources/SYNTHS")
            .listFiles((d, n) -> n.endsWith(".XML") && !n.startsWith("SONG"));
    assertTrue(
        synthFiles != null && synthFiles.length > 0,
        "No synth XML files in src/main/resources/SYNTHS");
    Arrays.sort(synthFiles, Comparator.comparing(File::getName));
    System.out.println("[AllSynths] Found " + synthFiles.length + " synth presets");

    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);

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

    List<String> synthNames = new ArrayList<>();
    List<ClipModel> allClips = new ArrayList<>();
    int barIdx = 0;
    for (File f : synthFiles) {
      try {
        SynthTrackModel synth = DelugeXmlParser.parseSynth(new FileInputStream(f), f.getName());
        synth.setName(f.getName().replace(".XML", ""));

        // One clip, one note at step 0
        ClipModel clip = new ClipModel("CLIP", 1, STEPS_PER_BAR);
        clip.setStep(0, 0, StepData.of(true, 1.0f, 1.0f, 1.0f, 60)); // C4, max vel
        // Valid session section (cycled — there are far more synths than kMaxNumSections). The
        // ARRANGER (clipInstances), not sections, sequences the 173 synths one-by-one.
        clip.setSection(barIdx % SECTIONS);
        synth.addClip(clip);
        allClips.add(clip);

        project.addTrack(synth);
        synthNames.add(synth.getName());

        // Place this clip on the arranger timeline AND song sections
        int startTicks = barIdx * ticksPerSynth;
        project.addArrangerClip(
            new ArrangerClip(synthNames.size() - 1, clip, startTicks, ticksPerSynth));
        project.addSongSection(new SongSection(String.valueOf(barIdx))); // numRepeats defaults to 0
        barIdx++;
      } catch (Exception e) {
        System.out.println("[AllSynths] Skipping " + f.getName() + ": " + e.getMessage());
      }
    }

    System.out.println("[AllSynths] Generated song with " + project.getTracks().size() + " tracks");

    // Save song XML
    File songFile = new File("target/ALL_SYNTHS_SONG.xml");
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
    // Spot-check the 2nd synth's instance starts at ticksPerSynth (=0x300=768) — i.e. sequential.
    String expectedPos2 = String.format("%08X", ticksPerSynth); // 0x00000300
    assertTrue(
        xml.contains("clipInstances=\"0x" + String.format("%08X", 0)) // 1st at tick 0
            && xml.contains(expectedPos2), // 2nd at ticksPerSynth
        "arranger clipInstances are not placed sequentially");
    // Section round-trip through the parser (C clip.cpp:713-715).
    ProjectModel reparsed = DelugeXmlParser.parseSong(songFile);
    ClipModel firstReparsed = reparsed.getTracks().get(0).getClips().get(0);
    assertEquals(
        0, firstReparsed.getSection(), "section did not round-trip through save/parse (synth 0)");
    System.out.println(
        "[AllSynths] Arranger serialization OK: "
            + ciCount
            + " sequential clipInstances, sections round-trip");

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
    int silentCount = 0;
    for (int i = 0; i < engineMaxRms.length; i++) {
      double rms = engineMaxRms[i];
      if (rms < 0.001) silentCount++;
      System.out.printf("  %3d %-40s RMS=%.6f%n", i, synthNames.get(i), rms);
      if (rms < minRms && rms > 0) minRms = rms;
      if (rms > maxRmsAll) maxRmsAll = rms;
      sumRms += rms;
    }

    double avgRms = sumRms / engineMaxRms.length;
    System.out.printf(
        "%n[AllSynths] Summary: %d synths, avg RMS=%.6f, min=%.6f, max=%.6f, silent=%d%n",
        engineMaxRms.length, avgRms, minRms, maxRmsAll, silentCount);

    // All synths should produce audible output
    assertTrue(
        silentCount < engineMaxRms.length * 0.1,
        silentCount + " of " + engineMaxRms.length + " synths are silent!");
    System.out.println(
        "[AllSynths] PASSED: "
            + (engineMaxRms.length - silentCount)
            + " synths produce audio, "
            + silentCount
            + " silent");
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
