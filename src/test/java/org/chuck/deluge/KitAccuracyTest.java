package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.engine.DelugeEngineDSL;
import org.chuck.deluge.model.Drum;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.SoundDrum;
import org.chuck.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

/**
 * Accuracy test: load the 808 kit, play each sound through the full engine pipeline, capture the
 * DAC output as WAV, and compare against the original sample file.
 *
 * <p>The engine applies: ADSR envelope, HPF at 20Hz, gain scaling, limiter, and the SndBuf's
 * mono-downmix (channels averaged). This test quantifies how much the engine transforms the
 * original signal by computing RMS error, peak error, and cross-correlation after accounting for
 * known gain/offset differences.
 */
@Tag("slow")
@Disabled("Legacy DelugeEngineDSL engine is unsupported; rebuild on the firmware pure engine. See docs/java-port-review-non-dx7-2026-06-03.md.")
public class KitAccuracyTest {

  private static final int SAMPLE_RATE = 44100;
  private static final int BLOCK_SIZE = 441; // 10ms blocks for advanceTime
  private static final String KIT_XML = "/KITS/000 TR-808.XML";

  private static ChuckVM vm;
  private static BridgeContract bridge;
  private static KitTrackModel kit;
  private static File tempDir;
  private static final List<String> errors = new ArrayList<>();

  @BeforeAll
  static void setUpAll() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    System.setProperty("deluge.tracks", "128");

    vm = new ChuckVM(SAMPLE_RATE, 2);
    vm.setLogLevel(0);
    bridge = new BridgeContract();
    bridge.register(vm);

    // Parse the 808 kit XML
    InputStream is = KitAccuracyTest.class.getResourceAsStream(KIT_XML);
    assertTrue(is != null, "Kit XML not found: " + KIT_XML);
    kit = DelugeXmlParser.parseKit(is, "000 TR-808");

    tempDir = new File(System.getProperty("java.io.tmpdir"), "deluge-accuracy-test");
    tempDir.mkdirs();
    for (File f : tempDir.listFiles()) f.delete(); // clean previous runs
  }

  @AfterAll
  static void tearDownAll() {
    if (vm != null) vm.shutdown();
  }

  /** Compute RMS of a float array. */
  private double rms(float[] data) {
    double sumSq = 0;
    for (float v : data) sumSq += v * v;
    return Math.sqrt(sumSq / data.length);
  }

  /** Compute peak absolute value. */
  private double peak(float[] data) {
    double p = 0;
    for (float v : data) {
      double abs = Math.abs(v);
      if (abs > p) p = abs;
    }
    return p;
  }

  /** Normalize the source array to have the same RMS as the target. Returns a new array. */
  private float[] normalizeRms(float[] src, float[] target) {
    double srcRms = rms(src);
    double tgtRms = rms(target);
    if (srcRms < 1e-10 || tgtRms < 1e-10) return src.clone();
    float scale = (float) (tgtRms / srcRms);
    float[] out = new float[src.length];
    for (int i = 0; i < src.length; i++) out[i] = src[i] * scale;
    return out;
  }

  /**
   * Compute normalized cross-correlation at zero lag. Returns a value in [-1, 1] where 1 =
   * identical shape.
   */
  private double correlation(float[] a, float[] b) {
    int len = Math.min(a.length, b.length);
    if (len < 2) return 0;
    double meanA = 0, meanB = 0;
    for (int i = 0; i < len; i++) {
      meanA += a[i];
      meanB += b[i];
    }
    meanA /= len;
    meanB /= len;
    double num = 0, denA = 0, denB = 0;
    for (int i = 0; i < len; i++) {
      double da = a[i] - meanA;
      double db = b[i] - meanB;
      num += da * db;
      denA += da * da;
      denB += db * db;
    }
    double den = Math.sqrt(denA * denB);
    return den > 1e-15 ? num / den : 0;
  }

  /** Compute RMS error between two equal-length arrays after optimal gain scaling. */
  private double rmsError(float[] reference, float[] candidate) {
    int len = Math.min(reference.length, candidate.length);
    if (len < 2) return 999;
    // Optimal linear scaling: find scale factor that minimizes sum|ref - scale*cand|²
    double sumRefCand = 0, sumCandSq = 0;
    for (int i = 0; i < len; i++) {
      sumRefCand += reference[i] * candidate[i];
      sumCandSq += candidate[i] * candidate[i];
    }
    double scale = sumCandSq > 1e-15 ? sumRefCand / sumCandSq : 1.0;
    double errSq = 0;
    for (int i = 0; i < len; i++) {
      double d = reference[i] - scale * candidate[i];
      errSq += d * d;
    }
    return Math.sqrt(errSq / len);
  }

  /** Print a sample-by-sample comparison for the first N samples of two signals. */
  private void printSampleComparison(String label, float[] ref, float[] cand, int count) {
    System.out.println("  Sample-by-sample (" + label + "):");
    System.out.println("  idx\tref\t\tcand\t\tdiff");
    for (int i = 0; i < Math.min(count, Math.min(ref.length, cand.length)); i++) {
      System.out.printf("  %3d\t%.6f\t%.6f\t%.6f%n", i, ref[i], cand[i], ref[i] - cand[i]);
    }
  }

  /** Find the sample offset (lag) that maximizes cross-correlation between two signals. */
  private int findBestLag(float[] a, float[] b, int maxLag) {
    int len = Math.min(a.length, b.length);
    int bestLag = 0;
    double bestCorr = -1;
    for (int lag = -maxLag; lag <= maxLag; lag++) {
      int start = Math.max(0, lag);
      int end = Math.min(len, len + lag);
      int n = end - start;
      if (n < 4) continue;
      double num = 0, denA = 0, denB = 0;
      for (int i = start; i < end; i++) {
        int ai = i;
        int bi = i - lag;
        if (bi < 0 || bi >= len) continue;
        num += a[ai] * b[bi];
        denA += a[ai] * a[ai];
        denB += b[bi] * b[bi];
      }
      double den = Math.sqrt(denA * denB);
      double corr = den > 1e-15 ? num / den : 0;
      if (corr > bestCorr) {
        bestCorr = corr;
        bestLag = lag;
      }
    }
    return bestLag;
  }

  @Test
  void test909KitAccuracy() throws Exception {
    String xmlPath = "/KITS/003 TR-909.XML";
    InputStream is909 = KitAccuracyTest.class.getResourceAsStream(xmlPath);
    assertTrue(is909 != null, "909 Kit XML not found: " + xmlPath);
    KitTrackModel kit909 = DelugeXmlParser.parseKit(is909, "003 TR-909");

    List<Drum> sounds = kit909.getDrums();
    assertTrue(sounds.size() > 0, "909 kit must have sounds");

    int count = sounds.size();
    String[] names = new String[count];
    float[] originalRms = new float[count];
    float[] engineRms = new float[count];
    double[] correlations = new double[count];
    double[] bestErrors = new double[count];
    int[] sampleCounts = new int[count];
    boolean[] passed = new boolean[count];

    // Resolve sample paths
    String[] resolvedPaths = new String[count];
    for (int i = 0; i < count; i++) {
      String path = ((SoundDrum) sounds.get(i)).getSamplePath();
      if (path == null || path.isEmpty()) {
        names[i] = sounds.get(i).getName() + " (NO SAMPLE)";
        continue;
      }
      names[i] = sounds.get(i).getName();

      File f = new File(path);
      if (f.isAbsolute() && f.exists()) {
        resolvedPaths[i] = f.getAbsolutePath();
      } else {
        String resPath = path.startsWith("/") ? path : "/" + path;
        File localTarget = new File("target/classes" + resPath);
        if (localTarget.exists()) {
          resolvedPaths[i] = localTarget.getAbsolutePath();
        } else {
          String rp = path.replace("\\", "/");
          if (!rp.startsWith("/")) rp = "/" + rp;
          try (InputStream ris = getClass().getResourceAsStream(rp)) {
            if (ris != null) {
              String uniqueName = "ref909_" + i + "_" + new File(rp).getName();
              File tmp = new File(tempDir, uniqueName);
              java.nio.file.Files.copy(
                  ris, tmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
              resolvedPaths[i] = tmp.getAbsolutePath();
            }
          }
        }
      }
    }

    // Pre-populate all sample paths
    for (int i = 0; i < count; i++) {
      if (resolvedPaths[i] != null) {
        vm.setGlobalString("g_sample_" + i, resolvedPaths[i]);
        bridge.setTrackType(i, 0);
      }
    }

    // Start engine
    vm.spork(new DelugeEngineDSL(vm));
    vm.advanceTime(100);
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.advanceTime(SAMPLE_RATE / 2);

    System.out.println("\n=== 909 Kit Accuracy Test ===");
    System.out.printf(
        "%-20s %-8s %-8s %-10s %-10s %-8s %s%n",
        "Sound", "OrigRMS", "EngRMS", "Correlation", "RMSErr", "Samples", "Result");

    for (int i = 0; i < count; i++) {
      if (resolvedPaths[i] == null) {
        System.out.printf(
            "%-20s %-8s %-8s %-10s %-10s %-8s SKIP%n", names[i], "-", "-", "-", "-", "-");
        continue;
      }

      // Load original source WAV
      float[] original;
      try {
        original = AudioAnalyzer.loadWav(new File(resolvedPaths[i]));
      } catch (Exception e) {
        System.out.printf(
            "%-20s %-8s %-8s %-10s %-10s %-8s ERR: %s%n",
            names[i], "-", "-", "-", "-", "-", e.getMessage());
        errors.add(names[i] + ": load failed - " + e.getMessage());
        continue;
      }
      originalRms[i] = (float) rms(original);

      // Clear all steps
      for (int t = 0; t < BridgeContract.TRACKS; t++) {
        for (int s = 0; s < BridgeContract.STEPS; s++) {
          bridge.setStep(t, s, false);
          bridge.setVelocity(t, s, 0.0);
        }
      }
      vm.advanceTime(4410);

      // Configure this voice
      vm.setGlobalString("g_sample_" + i, resolvedPaths[i]);
      bridge.setTrackType(i, 0);
      bridge.setMute(i, false);
      bridge.setTrackLevel(i, 1.0);
      {
        org.chuck.core.ChuckArray _a_ =
            (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_PITCH);
        if (_a_ != null) _a_.setFloat(i, 0.0);
      }
      {
        org.chuck.core.ChuckArray _a_ =
            (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_ATTACK);
        if (_a_ != null) _a_.setFloat(i, 0.001f);
      }
      {
        org.chuck.core.ChuckArray _a_ =
            (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_DECAY);
        if (_a_ != null) _a_.setFloat(i, 0.0f);
      }
      {
        org.chuck.core.ChuckArray _a_ =
            (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_SUSTAIN);
        if (_a_ != null) _a_.setFloat(i, 1.0f);
      }
      {
        org.chuck.core.ChuckArray _a_ =
            (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_RELEASE);
        if (_a_ != null) _a_.setFloat(i, 0.001f);
      }
      {
        org.chuck.core.ChuckArray _a_ =
            (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_DELAY_SEND);
        if (_a_ != null) _a_.setFloat(i, 0.0f);
      }
      {
        org.chuck.core.ChuckArray _a_ =
            (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_REVERB_SEND);
        if (_a_ != null) _a_.setFloat(i, 0.0f);
      }

      // Mute all other tracks
      for (int t = 0; t < BridgeContract.TRACKS; t++) {
        if (t != i) bridge.setMute(t, true);
      }

      // Trigger the sample
      bridge.setStep(i, 0, true);
      bridge.setVelocity(i, 0, 1.0);
      bridge.setGate(i, 0, 0.9);

      vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
      vm.advanceTime(SAMPLE_RATE / 4);

      // Start WvOut2 export
      String wavPath = new File(tempDir, "engine909_" + i + ".wav").getAbsolutePath();
      bridge.startExport(wavPath);
      vm.advanceTime(SAMPLE_RATE * 250 / 1000);

      vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
      vm.setGlobalFloat(BridgeContract.G_BPM, 120.0);

      int captureMs = 5000;
      int totalSamples = SAMPLE_RATE * captureMs / 1000;
      int blocks = totalSamples / BLOCK_SIZE;
      for (int b = 0; b < blocks; b++) {
        vm.advanceTime(BLOCK_SIZE);
      }

      vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
      bridge.stopExport();
      vm.advanceTime(8820);

      // Load engine output
      File engineWav = new File(wavPath);
      float[] engineOut;
      if (engineWav.exists() && engineWav.length() > 44) {
        engineOut = AudioAnalyzer.loadWav(engineWav);
        System.out.printf(
            "  Original: RMS=%.6f peak=%.6f len=%d%n",
            originalRms[i], peak(original), original.length);
        System.out.printf(
            "  Engine:   RMS=%.6f peak=%.6f len=%d%n",
            rms(engineOut), peak(engineOut), engineOut.length);
      } else {
        System.out.printf(
            "%-20s %-8s %-8s %-10s %-10s %-8s NO OUTPUT%n", names[i], "-", "-", "-", "-", "-");
        errors.add(names[i] + ": engine produced no output WAV");
        continue;
      }
      engineRms[i] = (float) rms(engineOut);
      sampleCounts[i] = engineOut.length;

      // Align and compare
      int engOnset = -1;
      double engNoiseFloor = rms(engineOut) * 0.1;
      if (engNoiseFloor < 0.0001) engNoiseFloor = 0.0001;
      for (int di = 0; di < engineOut.length; di++) {
        if (Math.abs(engineOut[di]) > engNoiseFloor) {
          engOnset = di;
          break;
        }
      }
      if (engOnset < 0) engOnset = 0;

      int origLen = original.length;
      int engLen = engineOut.length - engOnset;
      if (engLen < origLen / 2) {
        bestErrors[i] = 999;
        correlations[i] = 0;
      } else {
        int compLen = Math.min(origLen, engLen);
        float[] engSlice = new float[compLen];
        float[] origSlice = new float[compLen];
        System.arraycopy(original, 0, origSlice, 0, compLen);
        System.arraycopy(engineOut, engOnset, engSlice, 0, compLen);

        int fineLag;
        if (engOnset > 10000 && origLen < 30000) {
          fineLag = findBestLag(origSlice, engSlice, 2000);
        } else {
          fineLag = findBestLag(origSlice, engSlice, 500);
        }
        if (fineLag > 0) {
          int alen = Math.min(compLen, origLen - fineLag);
          origSlice = new float[alen];
          engSlice = new float[alen];
          System.arraycopy(original, fineLag, origSlice, 0, alen);
          System.arraycopy(engineOut, engOnset, engSlice, 0, alen);
        } else if (fineLag < 0) {
          int shift = -fineLag;
          int alen = Math.min(compLen - shift, origLen);
          origSlice = new float[alen];
          engSlice = new float[alen];
          System.arraycopy(original, 0, origSlice, 0, alen);
          System.arraycopy(engineOut, engOnset + shift, engSlice, 0, alen);
        }

        bestErrors[i] = rmsError(origSlice, engSlice);
        correlations[i] = correlation(origSlice, engSlice);
        System.out.printf(
            "  DIAG: aligned compLen=%d fineLag=%d correlation=%.4f rmsError=%.6f%n",
            origSlice.length, fineLag, correlations[i], bestErrors[i]);
      }

      boolean isSilence = originalRms[i] < 0.001 && engineRms[i] < 0.001;
      passed[i] = isSilence || (correlations[i] > 0.9 && bestErrors[i] < 0.15);

      String result;
      if (isSilence) result = "SILENT";
      else if (passed[i]) result = "PASS";
      else result = "FAIL";

      assertTrue(
          isSilence || correlations[i] > 0.8,
          names[i]
              + ": correlation="
              + String.format("%.4f", correlations[i])
              + " too low (expected >0.8)");

      System.out.printf(
          "%-20s %-8.4f %-8.4f %-10.4f %-10.6f %-8d %s%n",
          names[i],
          originalRms[i],
          engineRms[i],
          correlations[i],
          bestErrors[i],
          sampleCounts[i],
          result);
    }

    // Summary
    int passCount = 0, failCount = 0, skipCount = 0;
    for (int i = 0; i < count; i++) {
      if (resolvedPaths[i] == null) skipCount++;
      else if (passed[i]) passCount++;
      else failCount++;
    }
    System.out.println();
    System.out.println("=== 909 Summary ===");
    System.out.println("Total sounds: " + count);
    System.out.println("Passed:      " + passCount);
    System.out.println("Failed:      " + failCount);
    System.out.println("Skipped:     " + skipCount);

    if (!errors.isEmpty()) {
      System.out.println("\nErrors:");
      for (String e : errors) System.out.println("  - " + e);
    }

    int tested = count - skipCount;
    if (tested > 0) {
      assertTrue(
          failCount <= tested * 0.3,
          failCount + "/" + tested + " 909 sounds failed correlation check (threshold: 30%)");
    }
    System.out.println("\n909 Accuracy test: " + passCount + "/" + tested + " passed.");
  }

  @Test
  void test808KitAccuracy() throws Exception {
    List<Drum> sounds = kit.getDrums();
    assertTrue(sounds.size() > 0, "808 kit must have sounds");

    int count = sounds.size();
    String[] names = new String[count];
    float[] originalRms = new float[count];
    float[] engineRms = new float[count];
    double[] correlations = new double[count];
    double[] bestErrors = new double[count];
    int[] sampleCounts = new int[count];
    boolean[] passed = new boolean[count];

    // Cache the kit's sample paths resolved to absolute filesystem paths
    String[] resolvedPaths = new String[count];
    for (int i = 0; i < count; i++) {
      String path = ((SoundDrum) sounds.get(i)).getSamplePath();
      if (path == null || path.isEmpty()) {
        names[i] = sounds.get(i).getName() + " (NO SAMPLE)";
        continue;
      }
      names[i] = sounds.get(i).getName();

      // Resolve the sample path like DelugeEngineDSL.loadKitSamples does
      File f = new File(path);
      if (f.isAbsolute() && f.exists()) {
        resolvedPaths[i] = f.getAbsolutePath();
      } else {
        String resPath = path.startsWith("/") ? path : "/" + path;
        File localTarget = new File("target/classes" + resPath);
        if (localTarget.exists()) {
          resolvedPaths[i] = localTarget.getAbsolutePath();
        } else {
          // Try classpath resource extraction
          String rp = path.replace("\\", "/");
          if (!rp.startsWith("/")) rp = "/" + rp;
          try (InputStream ris = getClass().getResourceAsStream(rp)) {
            if (ris != null) {
              String uniqueName = "ref_" + i + "_" + new File(rp).getName();
              File tmp = new File(tempDir, uniqueName);
              java.nio.file.Files.copy(
                  ris, tmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
              resolvedPaths[i] = tmp.getAbsolutePath();
            }
          }
        }
      }
    }

    // Pre-populate all sample paths and track types BEFORE starting engine,
    // so kit_shred's voiceCount calculation creates SndBuf for ALL kit sounds.
    // Otherwise it only creates 1 SndBuf (for the first g_sample_0), and sounds
    // on tracks 1..N have no voice to play through.
    for (int i = 0; i < count; i++) {
      if (resolvedPaths[i] != null) {
        vm.setGlobalString("g_sample_" + i, resolvedPaths[i]);
        bridge.setTrackType(i, 0);
      }
    }

    // Start engine
    vm.spork(new DelugeEngineDSL(vm));
    vm.advanceTime(100);
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.advanceTime(SAMPLE_RATE / 2);

    System.out.println("=== 808 Kit Accuracy Test ===");
    System.out.printf(
        "%-20s %-8s %-8s %-10s %-10s %-8s %s%n",
        "Sound", "OrigRMS", "EngRMS", "Correlation", "RMSErr", "Samples", "Result");

    for (int i = 0; i < count; i++) {
      if (resolvedPaths[i] == null) {
        System.out.printf(
            "%-20s %-8s %-8s %-10s %-10s %-8s SKIP%n", names[i], "-", "-", "-", "-", "-");
        continue;
      }

      // ── Load original source WAV ──
      float[] original;
      try {
        original = AudioAnalyzer.loadWav(new File(resolvedPaths[i]));
      } catch (Exception e) {
        System.out.printf(
            "%-20s %-8s %-8s %-10s %-10s %-8s ERR: %s%n",
            names[i], "-", "-", "-", "-", "-", e.getMessage());
        errors.add(names[i] + ": load failed - " + e.getMessage());
        continue;
      }
      originalRms[i] = (float) rms(original);

      // ── Set up engine for this voice ──
      // Clear all steps
      for (int t = 0; t < BridgeContract.TRACKS; t++) {
        for (int s = 0; s < BridgeContract.STEPS; s++) {
          bridge.setStep(t, s, false);
          bridge.setVelocity(t, s, 0.0);
        }
      }
      vm.advanceTime(4410);

      // Configure this voice: set sample path, track type, level, no pitch, no LFO
      vm.setGlobalString("g_sample_" + i, resolvedPaths[i]);
      bridge.setTrackType(i, 0);
      bridge.setMute(i, false);
      bridge.setTrackLevel(i, 1.0);
      // Kit ADSR/pitch/delay/reverb via direct ChuckArray access (no bridge setters)
      {
        org.chuck.core.ChuckArray _a_ =
            (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_PITCH);
        if (_a_ != null) _a_.setFloat(i, 0.0);
      }
      {
        org.chuck.core.ChuckArray _a_ =
            (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_ATTACK);
        if (_a_ != null) _a_.setFloat(i, 0.001f);
      }
      {
        org.chuck.core.ChuckArray _a_ =
            (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_DECAY);
        if (_a_ != null) _a_.setFloat(i, 0.0f);
      }
      {
        org.chuck.core.ChuckArray _a_ =
            (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_SUSTAIN);
        if (_a_ != null) _a_.setFloat(i, 1.0f);
      }
      {
        org.chuck.core.ChuckArray _a_ =
            (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_RELEASE);
        if (_a_ != null) _a_.setFloat(i, 0.001f);
      }
      {
        org.chuck.core.ChuckArray _a_ =
            (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_DELAY_SEND);
        if (_a_ != null) _a_.setFloat(i, 0.0f);
      }
      {
        org.chuck.core.ChuckArray _a_ =
            (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_REVERB_SEND);
        if (_a_ != null) _a_.setFloat(i, 0.0f);
      }

      // Mute all other tracks
      for (int t = 0; t < BridgeContract.TRACKS; t++) {
        if (t != i) bridge.setMute(t, true);
      }

      // Trigger the sample: step 0 with full velocity
      bridge.setStep(i, 0, true);
      bridge.setVelocity(i, 0, 1.0);
      bridge.setGate(i, 0, 0.9);

      // Reload samples so the engine picks up the new path
      vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
      vm.advanceTime(SAMPLE_RATE / 4);

      // ── Start WvOut2 export via bridge ──
      String wavPath = new File(tempDir, "engine_" + i + ".wav").getAbsolutePath();
      bridge.startExport(wavPath);

      // Wait 250ms for export_shred (polls every 100ms) to splice WvOut2 into chain.
      // At 120 BPM each step = 125ms, so step 0 fires ~14ms after G_PLAY=1.
      // After 250ms WvOut2 is spliced in time for the 2nd bar at t=2000ms.
      vm.advanceTime(SAMPLE_RATE * 250 / 1000);

      // Play — step 0 fires immediately, but WvOut2 isn't ready yet for first bar.
      // The second bar boundary (step 16) fires at t=2000ms, which WvOut2 will capture.
      vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
      vm.setGlobalFloat(BridgeContract.G_BPM, 120.0);

      // Capture 5000ms: covers 2 full loops at 120 BPM.
      // Step 0 at t=2000ms (2nd bar) is captured by WvOut2.
      int captureMs = 5000;
      int totalSamples = SAMPLE_RATE * captureMs / 1000;
      int blocks = totalSamples / BLOCK_SIZE;
      for (int b = 0; b < blocks; b++) {
        vm.advanceTime(BLOCK_SIZE);
      }

      // Stop
      vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
      bridge.stopExport();
      // Wait for export_shred to restore chain and close file
      vm.advanceTime(8820); // 200ms

      // ── Load engine output ──
      File engineWav = new File(wavPath);
      float[] engineOut;
      if (engineWav.exists() && engineWav.length() > 44) {
        engineOut = AudioAnalyzer.loadWav(engineWav);
        System.out.printf(
            "  Original: RMS=%.6f peak=%.6f len=%d%n",
            originalRms[i], peak(original), original.length);
        System.out.printf(
            "  Engine:   RMS=%.6f peak=%.6f len=%d%n",
            rms(engineOut), peak(engineOut), engineOut.length);
        // DIAG: scan engine output for signal energy in 100ms windows
        if (i == 0) {
          System.out.println(
              "  DIAG: max engine sample=" + peak(engineOut) + " totalLen=" + engineOut.length);
          int windowSamples = SAMPLE_RATE / 10; // 100ms windows
          int windows = engineOut.length / windowSamples;
          System.out.println("  DIAG: Energy per 100ms window (" + windows + " windows):");
          for (int w = 0; w < windows; w++) {
            double winRms = 0;
            double winPeak = 0;
            for (int s = w * windowSamples;
                s < (w + 1) * windowSamples && s < engineOut.length;
                s++) {
              double a = Math.abs(engineOut[s]);
              winRms += a * a;
              if (a > winPeak) winPeak = a;
            }
            winRms = Math.sqrt(winRms / windowSamples);
            if (winPeak > 0.001) {
              int ms = w * 100;
              System.out.printf("  win[%3d] ~%3dms RMS=%.6f peak=%.6f%n", w, ms, winRms, winPeak);
            }
          }
          // Also dump samples at the point of max peak
          int maxIdx = -1;
          double maxVal = -1;
          for (int di = 0; di < engineOut.length; di++) {
            double a = Math.abs(engineOut[di]);
            if (a > maxVal) {
              maxVal = a;
              maxIdx = di;
            }
          }
          System.out.println(
              "  DIAG: max sample at idx="
                  + maxIdx
                  + " (~"
                  + (maxIdx * 1000L / SAMPLE_RATE)
                  + "ms)");
          System.out.println("  DIAG: samples around max:");
          for (int di = Math.max(0, maxIdx - 10);
              di < Math.min(engineOut.length, maxIdx + 90);
              di++) {
            System.out.printf("  eng[%5d] = %.6f%n", di, engineOut[di]);
          }
        }
      } else {
        System.out.printf(
            "%-20s %-8s %-8s %-10s %-10s %-8s NO OUTPUT%n", names[i], "-", "-", "-", "-", "-");
        errors.add(names[i] + ": engine produced no output WAV");
        continue;
      }
      engineRms[i] = (float) rms(engineOut);
      sampleCounts[i] = engineOut.length;

      // ── Compare ──
      // Find the actual onset of the kick in the engine output (first sample > noiseFloor)
      int engOnset = -1;
      double engNoiseFloor = rms(engineOut) * 0.1;
      if (engNoiseFloor < 0.0001) engNoiseFloor = 0.0001;
      for (int di = 0; di < engineOut.length; di++) {
        if (Math.abs(engineOut[di]) > engNoiseFloor) {
          engOnset = di;
          break;
        }
      }
      if (engOnset < 0) engOnset = 0;
      System.out.printf("  DIAG: engOnset=%d (~%dms)%n", engOnset, engOnset * 1000 / SAMPLE_RATE);

      // Align: engine output starts with silence, then the kick at engOnset.
      // Compare the engine kick segment against original, finding best sub-sample alignment.
      int origLen = original.length;
      int engLen = engineOut.length - engOnset;
      if (engLen < origLen / 2) {
        bestErrors[i] = 999;
        correlations[i] = 0;
      } else {
        // Extract engine segment starting at onset, same length as original
        int compLen = Math.min(origLen, engLen);
        float[] engSlice = new float[compLen];
        float[] origSlice = new float[compLen];
        System.arraycopy(original, 0, origSlice, 0, compLen);
        System.arraycopy(engineOut, engOnset, engSlice, 0, compLen);

        // Fine-tune alignment within the capture window (±500 samples = ~11ms)
        int fineLag;
        if (engOnset > 10000 && origLen < 30000) {
          fineLag = findBestLag(origSlice, engSlice, 2000);
        } else {
          fineLag = findBestLag(origSlice, engSlice, 500);
        }
        if (fineLag > 0) {
          // Engine lags original: shift original start
          int alen = Math.min(compLen, origLen - fineLag);
          origSlice = new float[alen];
          engSlice = new float[alen];
          System.arraycopy(original, fineLag, origSlice, 0, alen);
          System.arraycopy(engineOut, engOnset, engSlice, 0, alen);
        } else if (fineLag < 0) {
          // Original lags engine: shift engine start
          int shift = -fineLag;
          int alen = Math.min(compLen - shift, origLen);
          origSlice = new float[alen];
          engSlice = new float[alen];
          System.arraycopy(original, 0, origSlice, 0, alen);
          System.arraycopy(engineOut, engOnset + shift, engSlice, 0, alen);
        }

        bestErrors[i] = rmsError(origSlice, engSlice);
        correlations[i] = correlation(origSlice, engSlice);
        System.out.printf(
            "  DIAG: aligned compLen=%d fineLag=%d correlation=%.4f rmsError=%.6f%n",
            origSlice.length, fineLag, correlations[i], bestErrors[i]);
      }

      // Pass criteria: correlation > 0.9 AND rmsError < 0.1 (after optimal scaling)
      // OR peak is very low on both sides
      boolean isSilence = originalRms[i] < 0.001 && engineRms[i] < 0.001;
      passed[i] = isSilence || (correlations[i] > 0.9 && bestErrors[i] < 0.15);

      String result;
      if (isSilence) result = "SILENT";
      else if (passed[i]) result = "PASS";
      else result = "FAIL";

      assertTrue(
          isSilence || correlations[i] > 0.8,
          names[i]
              + ": correlation="
              + String.format("%.4f", correlations[i])
              + " too low (expected >0.8)");

      System.out.printf(
          "%-20s %-8.4f %-8.4f %-10.4f %-10.6f %-8d %s%n",
          names[i],
          originalRms[i],
          engineRms[i],
          correlations[i],
          bestErrors[i],
          sampleCounts[i],
          result);
    }

    // Summary
    int passCount = 0, failCount = 0, skipCount = 0;
    for (int i = 0; i < count; i++) {
      if (resolvedPaths[i] == null) skipCount++;
      else if (passed[i]) passCount++;
      else failCount++;
    }
    System.out.println();
    System.out.println("=== Summary ===");
    System.out.println("Total sounds: " + count);
    System.out.println("Passed:      " + passCount);
    System.out.println("Failed:      " + failCount);
    System.out.println("Skipped:     " + skipCount);

    if (!errors.isEmpty()) {
      System.out.println("\nErrors:");
      for (String e : errors) System.out.println("  - " + e);
    }

    // Fail the test if more than 30% of non-skipped sounds fail
    int tested = count - skipCount;
    if (tested > 0) {
      assertTrue(
          failCount <= tested * 0.3,
          failCount + "/" + tested + " sounds failed correlation check (threshold: 30%)");
    }
    System.out.println("\nAccuracy test: " + passCount + "/" + tested + " passed.");
  }
}
