package org.chuck.deluge;

import java.io.*;
import java.util.List;
import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.engine.DelugeEngineDSL;
import org.chuck.deluge.model.*;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.xml.DelugeXmlParser;

/**
 * CLI tool: load a saved song XML from the filesystem, parse it, run through
 * the DSL engine for N seconds, and export a WAV.
 *
 * Usage: mvn exec:java -pl deluge -Dexec.mainClass="org.chuck.deluge.PlaySavedSong" -Dexec.args="C:\Users\ludo\Deluge\SONGS\lll.xml 10"
 */
public class PlaySavedSong {

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("Usage: PlaySavedSong <songXmlPath> [durationSec]");
      System.exit(1);
    }
    String xmlPath = args[0];
    int durationSec = args.length > 1 ? Integer.parseInt(args[1]) : 10;

    // Read the XML file from the filesystem
    File f = new File(xmlPath);
    if (!f.exists()) {
      System.err.println("File not found: " + xmlPath);
      System.exit(1);
    }
    String songName = f.getName().replace(".xml", "");

    // Set up audio properties and logging
    System.setProperty("chuck.loglevel", "0");
    System.setProperty("deluge.tracks", "256");

    // Parse the song XML
    ProjectModel project;
    try (InputStream is = new FileInputStream(f)) {
      project = DelugeXmlParser.parseSong(is, songName);
    }
    System.out.println("Loaded " + project.getTracks().size() + " track(s) from " + xmlPath);

    // Create VM & bridge
    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    // Push tracks to bridge (mirrors pushModelToBridge + DelugeE2ETest logic)
    int engineRow = 0;
    for (int t = 0; t < project.getTracks().size(); t++) {
      TrackModel track = project.getTracks().get(t);
      int voiceCount = 8;

      if (track instanceof KitTrackModel kit) {
        List<KitTrackModel.KitSound> sounds = kit.getSounds();
        int kitVoiceCount = Math.min(voiceCount, sounds.size());
        for (int v = 0; v < kitVoiceCount; v++) {
          int r = engineRow + v;
          bridge.setTrackType(r, 0);
          bridge.setMute(r, false);
          bridge.setTrackLevel(r, 0.8);
          bridge.setTrackLength(r, 16);
          String path = sounds.get(v).getSamplePath();
          if (path != null && !path.isEmpty()) {
            // Resolve: if starts with "SAMPLES/" try classpath resource
            vm.setGlobalString("g_sample_" + r, path);
            System.out.println("  Kit row " + r + ": " + path);
          }
        }
        engineRow += kitVoiceCount;
      } else if (track instanceof SynthTrackModel synth) {
        int totalRows = voiceCount;
        if (!synth.getClips().isEmpty()) {
          totalRows = Math.max(totalRows, synth.getClips().get(0).getRowCount());
        }
        for (int v = 0; v < totalRows; v++) {
          int r = engineRow + v;
          bridge.setTrackType(r, 1);
          bridge.setMute(r, false);
          bridge.setTrackLevel(r, 0.8);
          bridge.setTrackLength(r, 16);
        }
        engineRow += totalRows;
      }
    }

    // Push clip data
    engineRow = 0;
    for (int t = 0; t < project.getTracks().size(); t++) {
      TrackModel track = project.getTracks().get(t);
      int voiceCount = 8;
      int trackRows = (track instanceof KitTrackModel)
          ? Math.min(voiceCount, ((KitTrackModel) track).getSounds().size())
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

    System.out.println("Rendering " + durationSec + " seconds...");
    for (int i = 0; i < totalSamples / 441; i++) {
      vm.advanceTime(441); // 10ms at 44100
      int toWrite = Math.min(441, totalSamples - written);
      for (int s = 0; s < toWrite && written < totalSamples; s++) {
        leftBuf[written] = (short) Math.max(-32768, Math.min(32767, vm.getDacChannel(0).getLastOut() * 32767));
        rightBuf[written] = (short) Math.max(-32768, Math.min(32767, vm.getDacChannel(1).getLastOut() * 32767));
        written++;
        vm.advanceTime(1);
      }
    }

    // Find output WAV name
    String wavPath = xmlPath.replace(".xml", ".wav");
    writeWav(wavPath, leftBuf, rightBuf, written);
    System.out.println("WAV written: " + wavPath + " (" + written + " samples, " + durationSec + "s)");
  }

  private static void writeWav(String path, short[] left, short[] right, int len) throws IOException {
    try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(path)))) {
      int dataLen = len * 4; // 16-bit stereo = 4 bytes per sample pair
      dos.writeBytes("RIFF");
      dos.writeInt(Integer.reverseBytes(36 + dataLen));
      dos.writeBytes("WAVE");
      dos.writeBytes("fmt ");
      dos.writeInt(Integer.reverseBytes(16)); // chunk size
      dos.writeShort(Short.reverseBytes((short) 1)); // PCM
      dos.writeShort(Short.reverseBytes((short) 2)); // channels
      dos.writeInt(Integer.reverseBytes(44100)); // sample rate
      dos.writeInt(Integer.reverseBytes(44100 * 2 * 16 / 8)); // byte rate
      dos.writeShort(Short.reverseBytes((short) (2 * 16 / 8))); // block align
      dos.writeShort(Short.reverseBytes((short) 16)); // bits per sample
      dos.writeBytes("data");
      dos.writeInt(Integer.reverseBytes(dataLen));
      for (int i = 0; i < len; i++) {
        dos.writeShort(Short.reverseBytes(left[i]));
        dos.writeShort(Short.reverseBytes(right[i]));
      }
    }
  }
}
