package org.deluge;

import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.util.*;
import java.util.logging.Logger;
import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * Per-synth fidelity scorecard: our engine render vs the real Deluge hardware recordings of the
 * all-synths arranger songs. For each synth, compares a normalized log-magnitude spectrum (timbre,
 * alignment/level-tolerant) and reports cosine similarity. THROWAWAY analysis tool.
 */
public class FidelityScorecardTest {
  private static final Logger LOGGER = Logger.getLogger(FidelityScorecardTest.class.getName());

  static final String CARD_NAME = System.getProperty("deluge.card", "src/main/resources");
  static final File CARD = new File(CARD_NAME);
  static final String SYNTH_DIR = new File(CARD, "SYNTHS").getPath();
  static final File RECORDINGS_DIR =
      new File(
          System.getProperty(
              "scorecard.recordings", "src/test/resources/fidelity/hardware-recordings"));
  static final int SR = 44100;

  /**
   * Optional per-synth CSV ({@code name,singleWindow,timeResolved}), enabled with {@code
   * -Dscorecard.csv=/path/out.csv}. The summary median alone cannot tell you <em>which</em>
   * subsystem is costing fidelity; a per-synth table can be joined against preset features (osc
   * type, FX enabled, unison, ...) to find the systematic gaps instead of guessing at families.
   */
  private static final java.io.PrintWriter CSV = openCsv();

  private static java.io.PrintWriter openCsv() {
    String path = System.getProperty("scorecard.csv");
    if (path == null || path.isBlank()) {
      return null;
    }
    try {
      java.io.PrintWriter w = new java.io.PrintWriter(new java.io.FileWriter(path), true);
      // hw_rms/our_rms turn the CSV into a LEVEL census as well as a timbre one. The cosine is
      // normalised (it deliberately ignores overall level), so a whole family can be 15 dB hot with
      // no effect on the score — which is exactly the CALIB noise/HPF/modFX situation. Without
      // these
      // columns that error is invisible in the very table meant to localise it.
      w.println("name,single_window,time_resolved,hw_rms,our_rms,level_db");
      return w;
    } catch (java.io.IOException e) {
      LOGGER.warning("cannot open scorecard.csv: " + e);
      return null;
    }
  }

  /**
   * A decoded recording: the mono mix everything is scored against, plus the per-frame peak across
   * the ORIGINAL channels.
   *
   * <p>The peak channel exists because clipping cannot be detected on the mono mix: averaging L+R
   * halves a one-channel rail hit to ~0.5, so a detector reading {@link #mono} sees almost nothing
   * where the true per-channel figure is 4.8% (CALIB2) and 3.3% (CALIB3). The distortion is real
   * either way — a clipped channel carries harmonics the signal never had, and averaging keeps
   * them. See docs/FIDELITY_GAP_ANALYSIS.md §4.2duoseptuagies.
   */
  record Recording(float[] mono, float[] peak) {}

  // ---- WAV (16/24-bit, stereo->mono float) ----
  static float[] readWavMono(File f) throws Exception {
    return readWav(f).mono();
  }

  static Recording readWav(File f) throws Exception {
    try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
      byte[] hdr = new byte[12];
      raf.readFully(hdr);
      int ch = 2, bits = 16;
      long dataOff = -1, dataLen = 0;
      while (raf.getFilePointer() < raf.length() - 8) {
        byte[] id = new byte[4];
        raf.readFully(id);
        int sz = Integer.reverseBytes(raf.readInt());
        String cid = new String(id);
        if (cid.equals("fmt ")) {
          long p = raf.getFilePointer();
          raf.readShort(); // fmt
          ch = Short.reverseBytes(raf.readShort());
          raf.readInt(); // sr
          raf.readInt(); // byterate
          raf.readShort(); // blockalign
          bits = Short.reverseBytes(raf.readShort());
          raf.seek(p + sz);
        } else if (cid.equals("data")) {
          dataOff = raf.getFilePointer();
          dataLen = sz & 0xFFFFFFFFL;
          break;
        } else {
          raf.seek(raf.getFilePointer() + sz);
        }
      }
      int bytesPer = bits / 8;
      int frames = (int) (dataLen / (bytesPer * ch));
      float[] out = new float[frames];
      float[] peak = new float[frames];
      raf.seek(dataOff);
      byte[] buf = new byte[(int) Math.min(dataLen, (long) frames * bytesPer * ch)];
      raf.readFully(buf);
      int i = 0;
      for (int fr = 0; fr < frames; fr++) {
        double sum = 0;
        double mx = 0;
        for (int c = 0; c < ch; c++) {
          int v;
          double scaled;
          if (bytesPer == 3) {
            v = (buf[i] & 0xFF) | ((buf[i + 1] & 0xFF) << 8) | (buf[i + 2] << 16);
            scaled = v / (double) (1 << 23);
          } else {
            v = (buf[i] & 0xFF) | (buf[i + 1] << 8);
            scaled = v / 32768.0;
          }
          sum += scaled;
          mx = Math.max(mx, Math.abs(scaled));
          i += bytesPer;
        }
        out[fr] = (float) (sum / ch);
        peak[fr] = (float) mx;
      }
      return new Recording(out, peak);
    }
  }

  // ---- normalized log spectrum (48 log bins 50Hz..15kHz) via Goertzel ----
  static final int BANDS = 48;

  static double[] spectrum(float[] x, int from, int len) {
    double[] s = new double[BANDS];
    for (int k = 0; k < BANDS; k++) {
      double freq = 50.0 * Math.pow(15000.0 / 50.0, k / (double) (BANDS - 1));
      double w = 2 * Math.PI * freq / SR, c = 2 * Math.cos(w), s1 = 0, s2 = 0;
      for (int n = from; n < from + len && n < x.length; n++) {
        double t = x[n] + c * s1 - s2;
        s2 = s1;
        s1 = t;
      }
      double p = s2 * s2 + s1 * s1 - c * s1 * s2;
      s[k] = Math.log10(Math.max(p, 1e-12));
    }
    double mean = 0;
    for (double v : s) mean += v;
    mean /= BANDS;
    for (int k = 0; k < BANDS; k++) s[k] -= mean; // remove overall level/tilt offset
    return s;
  }

  static double cosine(double[] a, double[] b) {
    double dot = 0, na = 0, nb = 0;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
      na += a[i] * a[i];
      nb += b[i] * b[i];
    }
    return dot / (Math.sqrt(na * nb) + 1e-12);
  }

  /**
   * TIME-RESOLVED score: average per-frame spectral cosine across the note (onset-aligned), so it
   * captures time-varying timbre (FM bell decay, reverb tail, chorus movement) the single-window
   * cosine is blind to. 250 ms frames from each side's onset; frames where BOTH are quiet (gap /
   * release) are skipped so silence isn't scored.
   */
  static double timeResolvedScore(float[] a, int aOn, float[] b, int bOn, int bEnd) {
    int frame = SR / 4; // 250 ms
    double sum = 0;
    int cnt = 0;
    for (int i = 0; i < 12; i++) { // up to 3 s from the onset
      int ao = aOn + i * frame, bo = bOn + i * frame;
      if (ao + frame >= a.length || bo + frame >= bEnd || bo + frame >= b.length) break;
      if (rms(a, ao, frame) < 0.005 && rms(b, bo, frame) < 0.005) continue; // both silent → skip
      sum += cosine(spectrum(a, ao, frame), spectrum(b, bo, frame));
      cnt++;
    }
    return cnt > 0 ? sum / cnt : Double.NaN;
  }

  static float[] renderSynth(File xml) throws Exception {
    SynthTrackModel synth = DelugeXmlParser.parseSynth(new FileInputStream(xml), xml.getName());
    synth.setName(xml.getName().replace(".XML", ""));
    return renderSynthModel(synth, 60, 127);
  }

  /**
   * Render one C-note of a synth model through the pure engine. Used both for standalone preset
   * files and for the ALLSYN songs' EMBEDDED instrument copies — the latter is what the hardware
   * recordings actually played (see FIDELITY_GAP_ANALYSIS.md 4.1quater: embedded copies drift from
   * the preset files, e.g. 068's transpose and modulator-2 gate).
   */
  static float[] renderSynthModel(SynthTrackModel synth, int note, int velocity) throws Exception {
    FirmwareAudioEngine.cpuDireness = 0;
    org.deluge.firmware2.Functions.resetNoiseSeed();
    if (synth.getClips().isEmpty()) {
      ClipModel clip = new ClipModel("c", 1, 16);
      clip.setStep(0, 0, StepData.of(true, 1.0f, 16.0f, 1.0f, note));
      synth.addClip(clip);
    }
    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);
    project.addTrack(synth);
    ProjectModel song = FirmwareFactory.createSong(project);
    FirmwareSound fs = (FirmwareSound) song.getTracks().get(0).getActiveClip().getSound();
    fs.triggerNote(note, velocity);
    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.metronomeEnabled = false;
    // Previously never called, so masterReverb rendered with roomSize/damping/width all at their
    // raw Java field default (0), i.e. a near-degenerate reverb, regardless of the preset's
    // reverbAmount send - every preset's reverb tail was effectively broken by omission.
    engine.syncMasterEffects(project);
    engine.sounds.add(fs);
    int n = SR * 3;
    float[] out = new float[n];
    int got = 0;
    for (int b = 0; got < n; b++) {
      engine.renderBlock(128);
      for (int i = 0; i < 128 && got < n; i++)
        out[got++] = (float) (engine.masterBuffer[i].l / 2.147483648e9);
    }
    // The compiled FirmwareSound (holding decoded multisample float arrays) is attached to the
    // ClipModel, and the track models are retained for the whole ~187-preset run — without this
    // release the compiled sounds accumulate and the last few presets' sample loads die of heap
    // exhaustion with no error (§5's OOM failure mode, one layer above the reader cache).
    for (ClipModel c : synth.getClips()) {
      c.setSound(null);
    }
    if (Boolean.getBoolean("scorecard.lineout")) {
      applyAnalogLineOutModel(out);
    }
    return out;
  }

  /**
   * Opt-in ({@code -Dscorecard.lineout=true}) experimental model of the hardware DAC line-out stage
   * (§5): a ~35 Hz AC-coupling high-pass and a ~10 kHz reconstruction shelf. NOTE: these corner
   * frequencies are plausible defaults, NOT measured from the reference hardware, so this is a
   * signal-conditioning approximation for A/B experiments — it is off by default and does not
   * affect the reported scorecard baseline. (An earlier cubic "op-amp THD" term was removed: it was
   * an ungrounded, invented nonlinearity, not a faithful model.)
   */
  public static void applyAnalogLineOutModel(float[] out) {
    double fcHp = 35.0; // AC coupling high-pass roll-off below 35 Hz
    double alphaHp = 1.0 / (1.0 + 2.0 * Math.PI * fcHp / SR);
    double hpPrevIn = 0.0;
    double hpPrevOut = 0.0;
    double fcLp = 10000.0; // Reconstruction shelf above 10 kHz
    double alphaLp = (2.0 * Math.PI * fcLp / SR) / (1.0 + 2.0 * Math.PI * fcLp / SR);
    double lpPrevOut = 0.0;
    for (int i = 0; i < out.length; i++) {
      double x = out[i];
      double hp = alphaHp * (hpPrevOut + x - hpPrevIn);
      hpPrevIn = x;
      hpPrevOut = hp;
      double lp = lpPrevOut + alphaLp * (hp - lpPrevOut);
      lpPrevOut = lp;
      out[i] = (float) lp;
    }
  }

  static boolean ciExists(File root, String rel) {
    File cur = root;
    for (String part : rel.split("/")) {
      if (part.isEmpty()) continue;
      File[] kids = cur.listFiles();
      if (kids == null) return false;
      File m = null;
      for (File k : kids)
        if (k.getName().equalsIgnoreCase(part)) {
          m = k;
          break;
        }
      if (m == null) return false;
      cur = m;
    }
    return cur.isFile();
  }

  static boolean playable(File xml) {
    try {
      SynthTrackModel s = DelugeXmlParser.parseSynth(new FileInputStream(xml), xml.getName());
      for (String raw : new String[] {s.getOsc1RawXml(), s.getOsc2RawXml()}) {
        if (raw == null) continue;
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("fileName=\"([^\"]+)\"").matcher(raw);
        while (m.find()) if (!ciExists(CARD, m.group(1))) return false;
      }
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  static double rms(float[] x, int from, int len) {
    double s = 0;
    int c = 0;
    for (int n = from; n < from + len && n < x.length; n++) {
      s += x[n] * (double) x[n];
      c++;
    }
    return Math.sqrt(s / Math.max(c, 1));
  }

  /**
   * Detect the N note onsets by energy-rise tracking. Equal slicing fails because the hardware
   * notes do not start on the slice boundaries (a constant offset + slow drift over 94 synths);
   * tracking the strongest energy rise near each expected position follows both. Returns sample
   * offsets.
   */
  static int[] detectOnsets(float[] rec, int lead, int tail, int n) {
    int hop = SR / 100; // 10ms
    int envN = tail / hop + 1;
    double[] env = new double[envN];
    for (int i = 0; i < envN; i++) {
      int a = i * hop, b = Math.min(a + 2 * hop, tail);
      double s = 0;
      for (int j = a; j < b; j++) s += (double) rec[j] * rec[j];
      env[i] = Math.sqrt(s / Math.max(1, b - a));
    }
    int d = 8; // 80ms rise window
    double[] rise = new double[envN]; // half-wave-rectified energy increase = onset strength
    for (int i = d; i < envN; i++) rise[i] = Math.max(0, env[i] - env[i - d]);
    // The synths are equally spaced (one fixed-length arranger slot each), so fit a single global
    // grid (period + offset) by cross-correlation against the onset-strength function. This is far
    // more robust than greedy per-synth tracking, which cascades on any false detection.
    double content = tail - lead;
    double basePer = content / n;
    double bestScore = -1, bestPer = basePer, bestOff = 0;
    for (double perS = basePer * 0.96; perS <= basePer * 1.04; perS += hop * 0.5) {
      double maxOff = content - (n - 1) * perS;
      if (maxOff < 0) continue;
      for (double off = 0; off < maxOff; off += hop) {
        double sc = 0;
        for (int k = 0; k < n; k++) {
          int idx = (int) ((lead + off + k * perS) / hop);
          if (idx >= 0 && idx < envN) sc += rise[idx];
        }
        if (sc > bestScore) {
          bestScore = sc;
          bestPer = perS;
          bestOff = off;
        }
      }
    }
    int[] onset = new int[n];
    int snapH = 30; // ±0.3s: tight snap to sub-grid jitter only. Wider snaps just grab loud
    // neighbours and overfit the cosine (the grid already locates each uniform slot correctly).
    for (int k = 0; k < n; k++) {
      int g = (int) ((lead + bestOff + k * bestPer) / hop);
      int loH = Math.max(d, g - snapH), hiH = Math.min(envN - 1, g + snapH);
      int bestH = Math.min(g, envN - 1);
      double best = -1;
      for (int i = loH; i <= hiH; i++)
        if (rise[i] > best) {
          best = rise[i];
          bestH = i;
        }
      onset[k] = bestH * hop;
    }
    return onset;
  }

  /** One item to score: a display name plus how to render it. */
  record Renderable(String name, java.util.concurrent.Callable<float[]> render) {}

  /**
   * How far below the session's reference level a hardware slice may sit before its score is
   * considered meaningless. 0.02 ≈ -34 dB: at that point the recording holds essentially nothing in
   * that slice and the cosine is computed against the recording's noise floor, which can land
   * anywhere including negative. Chosen from the CALIB HPF group, whose hardware slices measured
   * 40-64 dB below the dry control (docs/FIDELITY_GAP_ANALYSIS.md §4.2novemsexagies).
   */
  private static final double NEAR_SILENCE_FLOOR = 0.02;

  /**
   * Accumulators for one scorecard run, spanning its songs.
   *
   * <p>{@code window}/{@code timeResolved} hold <em>every</em> measurable synth, so the headline
   * median stays comparable across the whole project history. {@code timeResolvedClean}
   * additionally drops the near-silent slices (see {@link #NEAR_SILENCE_FLOOR}) — reported
   * alongside, never instead, so improving the number by excluding more cases stays visible rather
   * than silent.
   */
  static final class Scores {
    final List<Double> window = new ArrayList<>();
    final List<Double> timeResolved = new ArrayList<>();
    final List<Double> timeResolvedClean = new ArrayList<>();

    /** Our render produced silence — nothing to compare (e.g. multisamples with no samples). */
    final List<String> notMeasurable = new ArrayList<>();

    /**
     * Subset of {@link #notMeasurable} where OUR render was exactly silent. Tracked separately
     * because it has a wholly different meaning from a both-silent slice: it is us producing
     * nothing, either a real engine defect or — as happened with the entire 30-case CALIB wavetable
     * group — samples the render could not find because the card path lacked them. That group sat
     * unmeasured and unnoticed behind an aggregate "not-measurable" count; once the wavetables were
     * reachable it scored a median of 0.902, one of the best groups in the corpus.
     */
    final List<String> ourRenderSilent = new ArrayList<>();

    /** Scored, but the hardware slice is below the near-silence floor. */
    final List<String> nearSilent = new ArrayList<>();

    /** Hardware slice far quieter than our render — a level defect OR a bad slice. */
    final List<String> levelMismatch = new ArrayList<>();

    /**
     * Slices whose HARDWARE recording is clipped at the digital rail. Nothing downstream of a
     * clipped reference is trustworthy: the level is wrong (clipping raises RMS toward the rail)
     * and so is the spectrum (clipping manufactures harmonics that were never in the signal), so
     * both the cosine and the level guard are measuring the recorder, not our DSP.
     *
     * <p>This is the counterpart to {@link #nearSilent} and exists because its absence burned us:
     * CALIB2 and CALIB3 turned out to be recorded 3.8% and 3.1% at the rail, which is 156 of the
     * 250 cases (every hpf, delay, register, morph, drive, noise and reverb case), and a whole
     * session's worth of "the delay is 4.4 dB quiet" / "the HPF is 2.9 dB hot" conclusions were
     * drawn off them before anyone checked the recording. See docs/FIDELITY_GAP_ANALYSIS.md
     * §4.2duoseptuagies.
     */
    final List<String> clipped = new ArrayList<>();

    /**
     * The run's 0 dB mark: the loudest dry-control slice seen in any of its songs, or -1 until one
     * turns up. Deliberately run-wide rather than per-song — CALIB1/2/3 are three arrangements
     * recorded back to back at one gain setting, and only CALIB1 carries the control lane, so a
     * per-song reference left CALIB2/3 (which hold the entire HPF group) falling back to their own
     * median slice level.
     */
    double controlLevel = -1;

    String controlSource = "";

    /**
     * Our own render level for the same dry-control slice, so {@code controlOurLevel /
     * controlLevel} is the run's baseline our-vs-hardware gain offset.
     *
     * <p>This exists because the offset is NOT zero and is NOT a defect: the CALIB session measured
     * +8.4 dB on the dry control, i.e. the hardware's output-to-interface chain was quieter than
     * our float render by a constant. Every group inherits that constant, so a raw our-vs-hw ratio
     * flags all 250 cases and localises nothing. Subtracting the control turns the same numbers
     * into a real signal: noise +7.0 dB, HPF +2.9 dB, delay -4.4 dB, everything else inside ±2.5
     * dB.
     */
    double controlOurLevel = -1;
  }

  /**
   * A sample this close to full scale is treated as clipped. Not 1.0: {@link #readWavMono} averages
   * the channels, so a sample that hit the rail in both reads back a hair under 1.0 after the
   * divide, and a one-channel clip reads lower still.
   */
  private static final double CLIP_LEVEL = 0.98;

  /** Fraction of samples in {@code [from, to)} sitting at the digital rail. */
  private static double clippedFraction(float[] x, int from, int to) {
    int hits = 0;
    int n = 0;
    for (int i = Math.max(0, from); i < Math.min(to, x.length); i++, n++) {
      if (Math.abs(x[i]) >= CLIP_LEVEL) {
        hits++;
      }
    }
    return n > 0 ? hits / (double) n : 0.0;
  }

  /** Loudest {@code win}-sample RMS inside synth {@code k}'s slice of the hardware recording. */
  private static double hwSliceRms(float[] rec, int[] onset, int k, int tail, int win) {
    int sliceEnd = (k + 1 < onset.length) ? onset[k + 1] : tail;
    double best = -1;
    for (int off = onset[k]; off + win < sliceEnd && off + win < rec.length; off += SR / 4) {
      best = Math.max(best, rms(rec, off, win));
    }
    return best;
  }

  static Renderable fromPresetFile(File xml) {
    return new Renderable(xml.getName().replace(".XML", ""), () -> renderSynth(xml));
  }

  /**
   * Score items from the song's embedded tracks — the patches the hardware recording actually
   * played. Uses each clip's own first note pitch + velocity (the ALLSYN songs play y=60 vel 127).
   */
  static Renderable fromSongTrack(org.deluge.model.TrackModel track) {
    String name = track.getName();
    return new Renderable(
        name,
        () -> {
          if (!(track instanceof SynthTrackModel st)) {
            return new float[SR * 3]; // non-synth lane (shouldn't happen in ALLSYN) → silent
          }
          int note = 60, vel = 127;
          if (!st.getClips().isEmpty()) {
            ClipModel c = st.getClips().get(0);
            outer:
            for (int r = 0; r < c.getRowCount(); r++) {
              for (int stp = 0; stp < c.getStepCount(); stp++) {
                StepData sd = c.getStep(r, stp);
                if (sd != null && sd.active()) {
                  note = sd.pitch();
                  vel = Math.max(1, Math.round(sd.velocity() * 127));
                  break outer;
                }
              }
            }
          }
          return renderSynthModel(st, note, vel);
        });
  }

  void scoreSong(List<Renderable> synths, File recWav, String label, Scores s) throws Exception {
    Recording recording = readWav(recWav);
    float[] rec = recording.mono();
    float[] recPeak = recording.peak();
    // Trim BOTH leading and trailing silence — manual recordings have variable lead/tail, and
    // dividing total length by N otherwise drifts the per-synth slices (artificially low scores).
    int lead = 0;
    while (lead < rec.length && Math.abs(rec[lead]) < 0.003) lead++;
    int tail = rec.length;
    while (tail > lead && Math.abs(rec[tail - 1]) < 0.003) tail--;
    int per = (tail - lead) / synths.size();
    int win = SR * 2; // 2s analysis window
    int[] onset = detectOnsets(rec, lead, tail, synths.size());

    // Is this whole recording clipped? Checked FIRST because it invalidates everything below it,
    // and
    // because not checking it cost a session: CALIB2/CALIB3 sit 3.8%/3.1% at the rail while CALIB1
    // is
    // clean (peak 0.67), and every level conclusion drawn across those songs was really measuring
    // the
    // recorder's input gain. A clipped song is also recorded at a DIFFERENT gain from a clean one,
    // so
    // the run-wide dry-control reference (which lives in CALIB1) must not be used to normalise it.
    double songClipped = clippedFraction(recPeak, lead, tail);
    boolean songIsClipped = songClipped > 0.001;
    // Always report it, not only when it trips: the recording's health is a precondition for every
    // number below, so "0.00%" is information worth printing.
    LOGGER.info(
        String.format(
            "  %s recording: %.3f%% of samples at the digital rail (frames %d..%d of %d)",
            label, 100 * songClipped, lead, tail, rec.length));
    if (songIsClipped) {
      LOGGER.warning(
          String.format(
              "%n  *** %s IS CLIPPED: %.2f%% of samples at the digital rail. Cosines are polluted by"
                  + " clipping harmonics and levels are pinned toward full scale — scores for this"
                  + " song measure the RECORDING, not the engine. Re-record at lower input gain. ***",
              label, 100 * songClipped));
    }

    int minGap = Integer.MAX_VALUE, maxGap = 0;
    for (int k = 1; k < onset.length; k++) {
      int g = onset[k] - onset[k - 1];
      minGap = Math.min(minGap, g);
      maxGap = Math.max(maxGap, g);
    }
    LOGGER.fine(
        String.format(
            "\n=== %s : %d synths, rec %.1fs, content %.1fs, lead %.2fs, %.2fs/synth, onset gaps %.2f-%.2fs ===",
            label,
            synths.size(),
            rec.length / (double) SR,
            (tail - lead) / (double) SR,
            lead / (double) SR,
            per / (double) SR,
            minGap / (double) SR,
            maxGap / (double) SR));

    // Reference level for the near-silence guard. An ABSOLUTE RMS threshold cannot tell "the
    // hardware genuinely emitted almost nothing in this slice" apart from "this session was
    // recorded 12 dB quieter", and the two demand opposite conclusions. So the floor is relative to
    // the session's own DRY CONTROL: the CALIB corpus opens each song with unfiltered, un-FX'd saw
    // notes ("CTL dry saw C4"/"C2") for exactly this purpose, giving a per-recording 0 dB mark.
    // ALLSYN has no control lane, so there we fall back to the song's MEDIAN slice level — less
    // principled but still relative to the session, and robust to a few silent outliers.
    double[] sliceRms = new double[synths.size()];
    for (int k = 0; k < synths.size(); k++) {
      sliceRms[k] = hwSliceRms(rec, onset, k, tail, win);
    }
    for (int k = 0; k < synths.size(); k++) {
      if (synths.get(k).name().startsWith("CTL dry") && sliceRms[k] > s.controlLevel) {
        s.controlLevel = sliceRms[k];
        s.controlSource = "dry control (" + label + ")";
      }
    }
    // A clipped song was recorded at a different input gain than the clean song holding the
    // control,
    // so cross-song normalisation would compare two different gain staircases. Fall back to this
    // song's own median slice.
    double reference = songIsClipped ? -1 : s.controlLevel;
    String refSource = songIsClipped ? "" : s.controlSource;
    if (reference <= 0) {
      double[] sorted = sliceRms.clone();
      Arrays.sort(sorted);
      reference = sorted[sorted.length / 2];
      refSource =
          songIsClipped
              ? "median slice — song is CLIPPED"
              : "median slice — no dry control seen yet";
    }
    final double silenceFloor = reference * NEAR_SILENCE_FLOOR;
    LOGGER.fine(
        String.format(
            "  reference level %.4f (%s); near-silence floor %.4f (%.0f dB down)",
            reference, refSource, silenceFloor, 20 * Math.log10(NEAR_SILENCE_FLOOR)));

    for (int k = 0; k < synths.size(); k++) {
      String name = synths.get(k).name();
      float[] our = synths.get(k).render().call();
      // AudioFileReader's decode cache is unbounded (full float[] PCM per file); walking ~190
      // presets in one JVM without clearing it exhausts the heap partway through and makes the
      // later multisample-heavy presets render silent with no error (OutOfMemoryError isn't an
      // IOException, so the loader's catch never fires). See docs/FIDELITY_GAP_ANALYSIS.md §5.
      org.deluge.storage.audio.AudioFileReader.clearCache();
      double ourMax = 0;
      for (int off = 0; off + win < our.length; off += SR / 4)
        ourMax = Math.max(ourMax, rms(our, off, win));
      if (ourMax < 0.002) { // genuinely silent in our engine (e.g. multisample w/o samples loaded)
        s.notMeasurable.add(name);
        s.ourRenderSilent.add(name);
        LOGGER.fine(String.format("  %3d  %-30s   n/a (our render silent)", k, name));
        continue;
      }
      // spectrum from our loudest 2s window (handles slow attack / arp gaps)
      int ourBest = 0;
      double ourBR = -1;
      for (int off = 0; off + win < our.length; off += SR / 4) {
        double r = rms(our, off, win);
        if (r > ourBR) {
          ourBR = r;
          ourBest = off;
        }
      }
      double[] ours = spectrum(our, ourBest, win);
      // hardware: within this synth's onset→next-onset region, find the loudest 2s window
      int sliceStart = onset[k];
      int sliceEnd = (k + 1 < onset.length) ? onset[k + 1] : tail;
      int bestOff = sliceStart;
      double bestR = -1;
      for (int off = sliceStart; off + win < sliceEnd && off + win < rec.length; off += SR / 4) {
        double r = rms(rec, off, win);
        if (r > bestR) {
          bestR = r;
          bestOff = off;
        }
      }
      double sim = cosine(ours, spectrum(rec, bestOff, win));
      // Time-resolved score: align our render's onset (first frame above 10% of its peak) to the
      // hardware onset, then average per-frame spectral cosine across the note.
      int aOn = 0;
      while (aOn + win < our.length && rms(our, aOn, SR / 4) < 0.1 * ourMax) aOn += SR / 8;
      double ts = timeResolvedScore(our, aOn, rec, sliceStart, sliceEnd);
      // FIDELITY_GAP_ANALYSIS.md §4.2septies item 3: if all evaluation frames are both-silent
      // (e.g. genuinely near-silent slices like 129 Sci-fi Scenic), report as n/a instead of 0.000.
      if (Double.isNaN(ts) || Double.isNaN(sim)) {
        s.notMeasurable.add(name);
        LOGGER.fine(
            String.format("  %3d  %-30s   n/a (both silent in evaluation window)", k, name));
        continue;
      }
      s.window.add(sim);
      s.timeResolved.add(ts);
      if (name.startsWith("CTL dry") && bestR > 0 && ourMax > s.controlOurLevel) {
        s.controlOurLevel = ourMax;
      }
      // Per-synth row, for correlating scores against preset features (which subsystem is actually
      // costing us). Enable with -Dscorecard.csv=/path/out.csv.
      if (CSV != null) {
        CSV.printf(
            "%s,%.4f,%.4f,%.5f,%.5f,%.1f%n",
            name.replace(',', ' '),
            sim,
            ts,
            bestR,
            ourMax,
            (bestR > 0 && ourMax > 0) ? 20 * Math.log10(ourMax / bestR) : Double.NaN);
      }
      // GUARD 0 — the hardware slice is clipped. Checked before the others because a clipped
      // reference makes both of them meaningless: the level is pinned toward the rail and the
      // spectrum carries harmonics the signal never had.
      double sliceClipped =
          clippedFraction(recPeak, sliceStart, Math.min(sliceEnd, sliceStart + 4 * win));
      if (sliceClipped > 0.0005) {
        s.clipped.add(name);
        LOGGER.info(
            String.format(
                "  %3d  %-30s  win=%.3f time=%.3f  [CLIPPED: %.2f%% of the hardware slice is at the"
                    + " rail — this score measures the recording, not the engine]",
                k, name, sim, ts, 100 * sliceClipped));
      }
      // GUARD 1 — the hardware slice sits below this session's near-silence floor. There is nothing
      // in the recording to compare against, so whatever cosine came out is a property of the noise
      // floor, not of our DSP. This is what made the CALIB HPF group look catastrophic (median
      // 0.677
      // with nine NEGATIVE cosines) while FilterSet was proven bit-exact at the operating point.
      // Kept in the headline median and reported separately in the clean one — never silently
      // dropped, because "exclude the hard cases" is the easiest way to fake progress here.
      if (bestR >= 0 && bestR < silenceFloor) {
        s.nearSilent.add(name);
        LOGGER.info(
            String.format(
                "  %3d  %-30s  win=%.3f time=%.3f  [NEAR-SILENT: hwRMS=%.4f is %.0f dB below this"
                    + " song's %s (%.4f) — scored against the noise floor, not a render defect]",
                k, name, sim, ts, bestR, 20 * Math.log10(bestR / reference), refSource, reference));
      } else if (sliceClipped <= 0.0005) {
        s.timeResolvedClean.add(ts);
      }
      // GUARD 2 — the slice is audible but our render's level is wrong by more than the run's
      // baseline offset. The baseline matters: the dry control itself measures +8.4 dB (the
      // hardware's output chain is quieter than our float render by a constant), so comparing raw
      // our-vs-hw ratios flags every case in the corpus and localises nothing. What is diagnostic
      // is
      // the EXCESS over the control — noise sits +7 dB above it, delay -4 dB below, and the rest
      // land inside ±2.5 dB. Not excluded from any median: a genuine level error is a real defect.
      double excessDb = Double.NaN;
      if (bestR >= silenceFloor && s.controlOurLevel > 0 && s.controlLevel > 0 && ourMax > 0) {
        excessDb =
            20 * Math.log10(ourMax / bestR) - 20 * Math.log10(s.controlOurLevel / s.controlLevel);
      }
      if (Math.abs(excessDb) > 6.0) {
        s.levelMismatch.add(name);
        LOGGER.info(
            String.format(
                "  %3d  %-30s  win=%.3f time=%.3f  [LEVEL: %+.1f dB vs the dry control"
                    + " (hwRMS=%.4f ourRMS=%.4f) — level defect, kept in the median]",
                k, name, sim, ts, excessDb, bestR, ourMax));
      }
    }
  }

  /**
   * Scores the CALIB corpus — the second calibration set that covers what ALLSYN structurally
   * cannot see (modFX, delay, noise, wavetable oscillators, an audible HPF, and the bass register;
   * see docs/FIDELITY_GAP_ANALYSIS.md §4.2duosexagies and tools/calib_song/).
   *
   * <p>Kept as its own test rather than folded into {@link #scorecard()} so the ALLSYN median stays
   * a stable, comparable number across the whole project history — mixing in 250 new cases would
   * silently redefine the headline metric.
   *
   * <p>Songs come from {@code -Dcalib.songs} (default {@code ~/a/deluge_calib/SONGS}), recordings
   * from {@code -Dcalib.recordings} (default: the normal recordings dir). Self-skips when absent.
   */
  @Test
  void calibScorecard() throws Exception {
    File songDir =
        new File(
            System.getProperty(
                "calib.songs", System.getProperty("user.home") + "/a/deluge_calib/SONGS"));
    File recDir = new File(System.getProperty("calib.recordings", RECORDINGS_DIR.getPath()));
    org.junit.jupiter.api.Assumptions.assumeTrue(
        songDir.isDirectory() && new File(recDir, "CALIB1/output_000.wav").isFile(),
        "CALIB scorecard needs the generated songs (-Dcalib.songs) and recordings"
            + " (-Dcalib.recordings); generate with tools/calib_song/gen_calib.py");

    Scores s = new Scores();
    for (int part = 1; ; part++) {
      File songFile = new File(songDir, "CALIB" + part + ".XML");
      File recWav = new File(recDir, "CALIB" + part + "/output_000.wav");
      if (!songFile.isFile() || !recWav.isFile()) {
        break;
      }
      ProjectModel songModel =
          DelugeXmlParser.parseSong(new FileInputStream(songFile), songFile.getName());
      List<Renderable> items = new ArrayList<>();
      for (org.deluge.model.TrackModel t : songModel.getTracks()) {
        items.add(fromSongTrack(t));
      }
      scoreSong(items, recWav, "CALIB" + part, s);
    }
    org.junit.jupiter.api.Assumptions.assumeTrue(!s.window.isEmpty(), "no CALIB songs scored");
    report("CALIB", s);
  }

  /**
   * Prints both medians — over every measurable synth, and over only those whose hardware slice
   * cleared the near-silence floor — plus the flag counts behind the difference.
   */
  static void report(String label, Scores s) {
    LOGGER.info(
        String.format(
            "%n  %s: not-measurable %d (of which %d rendered SILENT on our side),"
                + " near-silent %d (excluded from clean), level-mismatch %d,"
                + " CLIPPED reference %d",
            label,
            s.notMeasurable.size(),
            s.ourRenderSilent.size(),
            s.nearSilent.size(),
            s.levelMismatch.size(),
            s.clipped.size()));
    if (s.controlOurLevel > 0 && s.controlLevel > 0) {
      LOGGER.info(
          String.format(
              "  dry-control baseline: hwRMS=%.4f ourRMS=%.4f (%+.1f dB) — the run's zero for the"
                  + " level guard, not a defect",
              s.controlLevel,
              s.controlOurLevel,
              20 * Math.log10(s.controlOurLevel / s.controlLevel)));
    }
    int scoredPlusSilent = s.timeResolved.size() + s.ourRenderSilent.size();
    if (scoredPlusSilent > 0 && s.ourRenderSilent.size() * 10 > scoredPlusSilent) {
      LOGGER.warning(
          String.format(
              "%n  *** %d of %d %s items rendered SILENT in our engine (>10%%). That is us"
                  + " producing nothing, not a fidelity result, and it silently removes them from"
                  + " the median. The usual cause is samples the render cannot find: check that"
                  + " -Ddeluge.card points at a card containing the SAMPLES/ this corpus"
                  + " references (for CALIB that is the gen_calib.py output directory, which"
                  + " carries SAMPLES/WAVETABLES). The entire 30-case wavetable group hid here"
                  + " until 2026-08-12. First few: %s ***",
              s.ourRenderSilent.size(),
              scoredPlusSilent,
              label,
              s.ourRenderSilent.subList(0, Math.min(5, s.ourRenderSilent.size()))));
    }
    if (!s.nearSilent.isEmpty()) {
      LOGGER.info("  near-silent slices: " + String.join(", ", s.nearSilent));
    }
    if (!s.clipped.isEmpty()) {
      LOGGER.warning(
          String.format(
              "  *** %d of %d scored slices have a CLIPPED hardware reference — their cosines and"
                  + " levels describe the recording, not the engine. Do not draw DSP conclusions"
                  + " from them. ***",
              s.clipped.size(), s.timeResolved.size()));
    }
    summarize(label + " SINGLE-WINDOW", s.window);
    summarize(label + " TIME-RESOLVED", s.timeResolved);
    // Only worth printing when the guard actually removed something and left something.
    if (!s.nearSilent.isEmpty() && !s.timeResolvedClean.isEmpty()) {
      summarize(
          label + " TIME-RESOLVED (clean: hardware slice above the near-silence floor)",
          s.timeResolvedClean);
    }
  }

  @Test
  void scorecard() throws Exception {
    // Local analysis tool: needs the SYNTHS dir + the hardware resamplings. Skip otherwise.
    org.junit.jupiter.api.Assumptions.assumeTrue(
        new File(SYNTH_DIR).isDirectory()
            && new File(RECORDINGS_DIR, "ALLSYN_1/output_000.wav").isFile(),
        "fidelity scorecard needs hardware calibration recordings (set via -Dscorecard.recordings)");
    List<Renderable> p1;
    List<Renderable> p2;
    if (Boolean.getBoolean("scorecard.presets")) {
      // LEGACY mode: render the standalone SYNTHS/ preset files. Known-invalid as a fidelity
      // gate: the recordings played the songs' EMBEDDED instrument copies, which drift from the
      // preset files (FIDELITY_GAP_ANALYSIS.md 4.1quater). Kept for comparison runs.
      File[] files =
          new File(SYNTH_DIR)
              .listFiles(
                  (d, n) ->
                      (n.endsWith(".XML") || n.endsWith(".xml"))
                          && !n.toUpperCase().startsWith("SONG")
                          && !n.matches("\\d\\d CAL .*"));
      Arrays.sort(files, Comparator.comparing(File::getName));
      List<File> playable = new ArrayList<>();
      for (File f : files) if (playable(f)) playable.add(f);
      LOGGER.fine("[Scorecard] LEGACY preset-file mode; playable: " + playable.size());
      p1 = new ArrayList<>();
      p2 = new ArrayList<>();
      for (int i = 0; i < playable.size(); i++) {
        (i < 94 ? p1 : p2).add(fromPresetFile(playable.get(i)));
      }
    } else {
      // DEFAULT: render the ALLSYN songs' embedded instruments — what the recording played.
      p1 = new ArrayList<>();
      p2 = new ArrayList<>();
      for (int part = 1; part <= 2; part++) {
        File songFile =
            new File(SYNTH_DIR)
                .getParentFile()
                .toPath()
                .resolve("SONGS")
                .resolve("ALLSYN_" + part + ".XML")
                .toFile();
        org.junit.jupiter.api.Assumptions.assumeTrue(
            songFile.isFile(), "embedded mode needs " + songFile);
        ProjectModel songModel =
            DelugeXmlParser.parseSong(new FileInputStream(songFile), songFile.getName());
        List<Renderable> target = (part == 1) ? p1 : p2;
        for (org.deluge.model.TrackModel t : songModel.getTracks()) {
          target.add(fromSongTrack(t));
        }
        LOGGER.fine("[Scorecard] EMBEDDED mode: ALLSYN_" + part + " tracks: " + target.size());
      }
    }

    Scores s = new Scores();
    scoreSong(
        new ArrayList<>(p1), new File(RECORDINGS_DIR, "ALLSYN_1/output_000.wav"), "ALLSYN_1", s);
    scoreSong(
        new ArrayList<>(p2), new File(RECORDINGS_DIR, "ALLSYN_2/output_000.wav"), "ALLSYN_2", s);
    report("ALLSYN", s);
  }

  static void summarize(String label, List<Double> scores) {
    List<Double> all = new ArrayList<>(scores);
    Collections.sort(all);
    double mean = all.stream().mapToDouble(d -> d).average().orElse(0);
    double median = all.get(all.size() / 2);
    long ge9 = all.stream().filter(d -> d >= 0.9).count();
    long ge8 = all.stream().filter(d -> d >= 0.8).count();
    long lt6 = all.stream().filter(d -> d < 0.6).count();
    // The scorecard's summary is its entire purpose — keep it visible at the default log level (a
    // 2026-07-27 logging refactor had demoted it to FINE, making a bare scorecard run print
    // nothing).
    // Per-preset lines above stay at FINE (verbose).
    LOGGER.info(String.format("%n=== FIDELITY SUMMARY (%s cosine vs hardware) ===", label));
    LOGGER.info(String.format("  n=%d  mean=%.3f  median=%.3f", all.size(), mean, median));
    LOGGER.info(
        String.format(
            "  >=0.90: %d (%.0f%%)   >=0.80: %d (%.0f%%)   <0.60: %d",
            ge9, 100.0 * ge9 / all.size(), ge8, 100.0 * ge8 / all.size(), lt6));
  }
}
