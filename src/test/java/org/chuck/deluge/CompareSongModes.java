package org.chuck.deluge;

import java.io.*;
import java.util.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.engine.DelugeEngineDSL;
import org.chuck.deluge.model.*;
import org.chuck.deluge.xml.DelugeXmlParser;

/**
 * Renders a saved song through multiple engine modes and saves each as a WAV.
 *
 * <p>Modes: dsl — Default DSL engine (as-is) dsl-raw — DSL engine with SVFilter bypassed dsl-loud —
 * DSL engine with higher gain staging
 *
 * <p>Usage: mvn exec:java -pl deluge -Dexec.classpathScope=test
 * -Dexec.mainClass="org.chuck.deluge.CompareSongModes" -Dexec.args="<xmlPath> [durationSec]"
 */
public class CompareSongModes {

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("Usage: CompareSongModes <songXmlPath> [durationSec]");
      System.exit(1);
    }
    String xmlPath = args[0];
    int durationSec = args.length > 1 ? Integer.parseInt(args[1]) : 10;

    File f = new File(xmlPath);
    if (!f.exists()) {
      System.err.println("File not found: " + xmlPath);
      System.exit(1);
    }

    // Parse song once, render in multiple modes
    ProjectModel project;
    try (InputStream is = new FileInputStream(f)) {
      project = DelugeXmlParser.parseSong(is, f.getName().replace(".xml", ""));
    }
    System.out.println("Loaded " + project.getTracks().size() + " track(s) from " + xmlPath);

    String base = xmlPath.replace(".xml", "");

    // Mode 1: Default DSL
    renderMode(project, base + "-dsl.wav", durationSec, "dsl");
    // Mode 2: DSL with log level suppressed
    renderMode(project, base + "-dsl-quiet.wav", durationSec, "dsl-quiet");
    // Mode 3: DSL with slightly higher track level
    renderMode(project, base + "-dsl-louder.wav", durationSec, "dsl-louder");

    // Compare WAVs
    compareWavs(base);
  }

  static void renderMode(ProjectModel project, String wavPath, int durationSec, String mode)
      throws Exception {
    System.setProperty("chuck.loglevel", "0");

    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    double trackLevel = 0.8;
    if (mode.contains("louder")) trackLevel = 1.0;

    // Push tracks to bridge
    int engineRow = 0;
    for (TrackModel track : project.getTracks()) {
      if (track instanceof KitTrackModel kit) {
        List<Drum> sounds = kit.getDrums();
        int kitVoiceCount = Math.min(8, sounds.size());
        for (int v = 0; v < kitVoiceCount; v++) {
          int r = engineRow + v;
          bridge.setTrackType(r, 0);
          bridge.setMute(r, false);
          bridge.setTrackLevel(r, trackLevel);
          bridge.setTrackLength(r, 16);
          String path = ((SoundDrum) sounds.get(v)).getSamplePath();
          if (path != null && !path.isEmpty()) {
            vm.setGlobalString("g_sample_" + r, path);
          }
        }
        engineRow += kitVoiceCount;
      } else if (track instanceof SynthTrackModel synth) {
        int totalRows = 8;
        if (!synth.getClips().isEmpty()) {
          totalRows = Math.max(totalRows, synth.getClips().get(0).getRowCount());
        }
        for (int v = 0; v < totalRows; v++) {
          int r = engineRow + v;
          bridge.setTrackType(r, 1);
          bridge.setMute(r, false);
          bridge.setTrackLevel(r, trackLevel);
          bridge.setTrackLength(r, 16);
        }
        engineRow += totalRows;
      }
    }

    // Push clip data
    engineRow = 0;
    for (TrackModel track : project.getTracks()) {
      int voiceCount = 8;
      int trackRows =
          (track instanceof KitTrackModel)
              ? Math.min(voiceCount, ((KitTrackModel) track).getDrums().size())
              : voiceCount;
      if (!track.getClips().isEmpty()) {
        ClipModel clip = track.getClips().get(0);
        int stepCount = clip.getStepCount();
        int rowCount = clip.getRowCount();
        for (int r = 0; r < rowCount && r < trackRows; r++) {
          for (int s = 0; s < stepCount; s++) {
            StepData step = clip.getStep(r, s);
            if (step != null && step.active()) {
              bridge.setStep(engineRow + r, s, true);
              bridge.setVelocity(engineRow + r, s, step.velocity());
            }
          }
        }
      }
      engineRow += trackRows;
    }

    // Start engine
    vm.spork(new DelugeEngineDSL(vm));
    vm.advanceTime(44100);

    // Broadcast load trigger
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.advanceTime(44100);

    // Start playback
    vm.setGlobalFloat(BridgeContract.G_MASTER_VOL, 1.0);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.setGlobalFloat(BridgeContract.G_BPM, project.getBpm() > 0 ? project.getBpm() : 120.0f);

    // Capture audio
    int totalSamples = durationSec * 44100;
    short[] leftBuf = new short[totalSamples];
    short[] rightBuf = new short[totalSamples];
    int written = 0;

    for (int i = 0; i < totalSamples / 441; i++) {
      vm.advanceTime(441);
      int toWrite = Math.min(441, totalSamples - written);
      for (int s = 0; s < toWrite && written < totalSamples; s++) {
        leftBuf[written] =
            (short) Math.max(-32768, Math.min(32767, vm.getDacChannel(0).getLastOut() * 32767));
        rightBuf[written] =
            (short) Math.max(-32768, Math.min(32767, vm.getDacChannel(1).getLastOut() * 32767));
        written++;
        vm.advanceTime(1);
      }
    }

    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
    vm.shutdown();

    writeWav(wavPath, leftBuf, rightBuf, written);
    System.out.println("[" + mode + "] WAV: " + wavPath + " (" + written + " samples)");
  }

  static void compareWavs(String base) throws Exception {
    System.out.println("\n--- Comparison ---");
    String[] suffixes = {"-dsl.wav", "-dsl-quiet.wav", "-dsl-louder.wav"};
    double[][] peaks = new double[suffixes.length][];
    String[] labels = new String[suffixes.length];

    for (int i = 0; i < suffixes.length; i++) {
      labels[i] = suffixes[i].replace(".wav", "");
      File wf = new File(base + suffixes[i]);
      if (!wf.exists()) {
        System.out.println(labels[i] + ": NOT FOUND");
        continue;
      }
      try (DataInputStream dis =
          new DataInputStream(new BufferedInputStream(new FileInputStream(wf)))) {
        byte[] header = new byte[44];
        dis.readFully(header);
        int dataLen = Integer.reverseBytes(readInt(header, 40));
        int sampleCount = dataLen / 4;
        double peakL = 0, peakR = 0, sumL = 0, sumR = 0;
        int nonZeroL = 0, nonZeroR = 0;
        for (int s = 0; s < sampleCount; s++) {
          short l = Short.reverseBytes(dis.readShort());
          short r = Short.reverseBytes(dis.readShort());
          double dl = Math.abs(l) / 32767.0;
          double dr = Math.abs(r) / 32767.0;
          if (dl > peakL) peakL = dl;
          if (dr > peakR) peakR = dr;
          sumL += dl;
          sumR += dr;
          if (l != 0) nonZeroL++;
          if (r != 0) nonZeroR++;
        }
        peaks[i] = new double[] {peakL, peakR, sumL / sampleCount, sumR / sampleCount};
        System.out.printf(
            "%s: peak L=%.4f R=%.4f  avg L=%.4f R=%.4f  nonZero=%d/%d%n",
            labels[i],
            peakL,
            peakR,
            sumL / sampleCount,
            sumR / sampleCount,
            (nonZeroL + nonZeroR) / 2,
            sampleCount);
      }
    }

    // Difference analysis between dsl and dsl-quiet (should be identical)
    if (peaks[0] != null && peaks[1] != null) {
      File f1 = new File(base + "-dsl.wav");
      File f2 = new File(base + "-dsl-quiet.wav");
      try (DataInputStream d1 =
              new DataInputStream(new BufferedInputStream(new FileInputStream(f1)));
          DataInputStream d2 =
              new DataInputStream(new BufferedInputStream(new FileInputStream(f2)))) {
        byte[] hdr = new byte[44];
        d1.readFully(hdr);
        d2.readFully(hdr);
        int len = Integer.reverseBytes(readInt(hdr, 40)) / 4;
        double maxDiff = 0;
        long diffCount = 0;
        for (int s = 0; s < len; s++) {
          short a = Short.reverseBytes(d1.readShort());
          short b = Short.reverseBytes(d2.readShort());
          double diff = Math.abs(a - b) / 32767.0;
          if (diff > maxDiff) maxDiff = diff;
          if (diff > 0.0001) diffCount++;
        }
        System.out.printf(
            "\ndsl vs dsl-quiet: maxSampleDiff=%.6f differingSamples=%d/%d%n",
            maxDiff, diffCount, len);
        if (maxDiff < 0.001)
          System.out.println("  => BIT-IDENTICAL (logging change doesn't affect audio)");
        else System.out.println("  => DIFFERENT (unexpected)");
      }
    }
  }

  static int readInt(byte[] buf, int off) {
    return (buf[off] & 0xff)
        | ((buf[off + 1] & 0xff) << 8)
        | ((buf[off + 2] & 0xff) << 16)
        | ((buf[off + 3] & 0xff) << 24);
  }

  static void writeWav(String path, short[] left, short[] right, int len) throws IOException {
    try (DataOutputStream dos =
        new DataOutputStream(new BufferedOutputStream(new FileOutputStream(path)))) {
      int dataLen = len * 4;
      dos.writeBytes("RIFF");
      dos.writeInt(Integer.reverseBytes(36 + dataLen));
      dos.writeBytes("WAVE");
      dos.writeBytes("fmt ");
      dos.writeInt(Integer.reverseBytes(16));
      dos.writeShort(Short.reverseBytes((short) 1));
      dos.writeShort(Short.reverseBytes((short) 2));
      dos.writeInt(Integer.reverseBytes(44100));
      dos.writeInt(Integer.reverseBytes(44100 * 2 * 16 / 8));
      dos.writeShort(Short.reverseBytes((short) (2 * 16 / 8)));
      dos.writeShort(Short.reverseBytes((short) 16));
      dos.writeBytes("data");
      dos.writeInt(Integer.reverseBytes(dataLen));
      for (int i = 0; i < len; i++) {
        dos.writeShort(Short.reverseBytes(left[i]));
        dos.writeShort(Short.reverseBytes(right[i]));
      }
    }
  }
}
