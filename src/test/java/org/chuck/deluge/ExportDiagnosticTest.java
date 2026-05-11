package org.chuck.deluge;

import java.io.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.chuck.audio.ChuckAudio;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.engine.DelugeEngineDSL;
import org.chuck.deluge.model.*;
import org.chuck.deluge.xml.DelugeXmlParser;

/**
 * Diagnostic: runs the DSL engine WITH a real audio callback (like the Swing UI),
 * triggers export via BridgeContract globals, and checks the WAV.
 *
 * Usage: mvn exec:java -pl deluge -Dexec.classpathScope=test
 *   -Dexec.mainClass="org.chuck.deluge.ExportDiagnosticTest"
 *   -Dexec.args="<xmlPath>"
 */
public class ExportDiagnosticTest {

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("Usage: ExportDiagnosticTest <songXmlPath>");
      System.exit(1);
    }
    String xmlPath = args[0];
    File f = new File(xmlPath);
    if (!f.exists()) { System.err.println("Not found: " + xmlPath); System.exit(1); }

    String songName = f.getName().replace(".xml", "");
    System.setProperty("chuck.loglevel", "0");

    ProjectModel project;
    try (InputStream is = new FileInputStream(f)) {
      project = DelugeXmlParser.parseSong(is, songName);
    }
    System.out.println("Loaded " + project.getTracks().size() + " track(s)");

    // Create VM and audio engine (same as Swing UI)
    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    ChuckAudio audio = new ChuckAudio(vm, 512, 2, 44100);
    vm.setAudio(audio);
    audio.start();

    // Push model to bridge
    int engineRow = 0;
    for (int t = 0; t < project.getTracks().size(); t++) {
      TrackModel track = project.getTracks().get(t);
      int voiceCount = 8;
      if (track instanceof KitTrackModel kit) {
        List<Drum> sounds = kit.getDrums();
        int kitVoiceCount = Math.min(voiceCount, sounds.size());
        for (int v = 0; v < kitVoiceCount; v++) {
          int r = engineRow + v;
          bridge.setTrackType(r, 0);
          bridge.setMute(r, false);
          bridge.setTrackLevel(r, 0.8);
          bridge.setTrackLength(r, 16);
          String path = ((SoundDrum) sounds.get(v)).getSamplePath();
          if (path != null && !path.isEmpty()) {
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

    // Spork engine
    vm.spork(new DelugeEngineDSL(vm));

    // Wait for engine to initialize (real-time)
    System.out.println("Waiting 1.5s for engine init and playback...");
    Thread.sleep(1500);

    // Broadcast load trigger so kit_shred/synth_shred build their voice graphs
    System.out.println("Broadcasting G_LOAD_TRIGGER...");
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    Thread.sleep(500);

    // Start playback
    vm.setGlobalFloat(BridgeContract.G_MASTER_VOL, 1.0);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.setGlobalFloat(BridgeContract.G_BPM, project.getBpm() > 0 ? project.getBpm() : 120.0f);

    // Let audio play for 2 seconds real-time
    System.out.println("Playing for 2s real-time...");
    Thread.sleep(2000);

    // Now trigger export (same sequence as SwingDelugeApp.exportAudio())
    String exportPath = xmlPath.replace(".xml", "-export-test.wav");
    System.out.println("\n=== TRIGGERING EXPORT ===");
    System.out.println("  Setting G_WVOUT_FILE = " + exportPath);
    vm.setGlobalString(BridgeContract.G_WVOUT_FILE, exportPath);
    System.out.println("  Setting G_WVOUT_ACTIVE = 1.0");
    vm.setGlobalFloat(BridgeContract.G_WVOUT_ACTIVE, 1.0f);

    // Wait 3 seconds real-time while export records
    System.out.println("  Waiting 3s real-time for recording...");
    Thread.sleep(3000);

    System.out.println("  Setting G_WVOUT_ACTIVE = 0.0 (stop)");
    vm.setGlobalFloat(BridgeContract.G_WVOUT_ACTIVE, 0.0f);

    // Wait for export_shred to restore chain and close file
    Thread.sleep(500);

    System.out.println("\n=== CHECKING RESULTS ===");
    File exportFile = new File(exportPath);
    if (exportFile.exists()) {
      long len = exportFile.length();
      System.out.println("  Export file exists: " + exportPath + " (" + len + " bytes)");
      if (len > 44) {
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(exportFile)))) {
          byte[] header = new byte[44];
          dis.readFully(header);
          int dataLen = readIntLE(header, 40);
          int sampleCount = dataLen / 4;
          System.out.println("  Data chunk: " + dataLen + " bytes (" + sampleCount + " samples)");
          int nonZero = 0;
          int totalChecked = Math.min(sampleCount, 44100);
          for (int s = 0; s < totalChecked; s++) {
            short l = Short.reverseBytes(dis.readShort());
            short r = Short.reverseBytes(dis.readShort());
            if (l != 0 || r != 0) nonZero++;
          }
          System.out.println("  Non-zero sample pairs in first " + totalChecked + ": " + nonZero + "/" + totalChecked);
          System.out.println(nonZero > 0 ? "  *** EXPORT WORKING ***" : "  *** EXPORT IS SILENT (all zeros) ***");
        }
      } else {
        System.out.println("  Export file is too small (header only?)");
      }
    } else {
      System.out.println("  Export file NOT FOUND: " + exportPath);
    }

    audio.stop();
    vm.shutdown();
  }

  /** Read 4-byte little-endian int. */
  static int readIntLE(byte[] buf, int off) {
    return (buf[off] & 0xff) | ((buf[off+1] & 0xff) << 8)
         | ((buf[off+2] & 0xff) << 16) | ((buf[off+3] & 0xff) << 24);
  }
}
