package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.chuck.audio.ChuckUGen;
import org.chuck.audio.util.DelugeAdsr;
import org.chuck.audio.util.SndBuf;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.engine.DelugeEngineDSL;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * Minimal test: direct SndBuf → dac with no intermediate buses.
 */
public class MinimalPreviewTest {

  @Test
  public void testDirectSndBufToDac() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    System.setProperty("chuck.loglevel", "1");

    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    // Start engine
    vm.spork(new DelugeEngineDSL());
    vm.advanceTime(44100);

    // Load the 2024 song
    java.io.File songFile = new java.io.File("C:\\Users\\ludo\\delugedownload\\ludocard\\SONGS\\2024.XML");
    assertTrue(songFile.exists(), "2024.XML not found");

    ProjectModel project = DelugeXmlParser.parseSong(
        new java.io.FileInputStream(songFile), songFile.getName());

    // Get first kit track's first sample path
    var kitTrack = (org.chuck.deluge.model.KitTrackModel) project.getTracks().get(0);
    String samplePath = kitTrack.getSounds().get(0).getSamplePath();
    System.out.println("[test] Sample path: " + samplePath);

    // Create a standalone SndBuf → DelugeAdsr → dac (direct, no masterTap)
    // Using a separate shred to run this test
    final boolean[] testDone = {false};
    final float[] peakDuringPlay = {0f};

    vm.spork(() -> {
      try {
        SndBuf buf = new SndBuf();
        DelugeAdsr env = new DelugeAdsr();
        env.set(0.001, 0.0, 1.0, 0.05); // fast attack, no decay, full sustain, fast release
        buf.chuck(env).chuck(org.chuck.core.ChuckDSL.dac());

        // Load the sample — resolve against library dir if it's a relative SD card path
        java.io.File f = new java.io.File(samplePath);
        if (!f.exists()) {
          java.io.File libraryFile = new java.io.File(
              org.chuck.deluge.project.PreferencesManager.getLibraryDir(), samplePath);
          if (libraryFile.exists()) {
            f = libraryFile;
          }
        }
        System.out.println("[test] File exists: " + f.exists() + " path=" + f.getAbsolutePath());
        buf.setRead(f.getAbsolutePath());
        System.out.println("[test] After setRead, samples=" + buf.samples());

        if (buf.samples() > 0) {
          buf.rate(1);
          buf.gain(1.0f);
          buf.pos(0);
          env.keyOn();

          // Let audio play for 1000 samples, checking peak
          for (int i = 0; i < 1000; i++) {
            org.chuck.core.ChuckDSL.advance(org.chuck.core.ChuckDSL.samp(1));
            float l = Math.abs(vm.getDacChannel(0).getLastOut());
            float r = Math.abs(vm.getDacChannel(1).getLastOut());
            if (l > peakDuringPlay[0]) peakDuringPlay[0] = l;
            if (r > peakDuringPlay[0]) peakDuringPlay[0] = r;
          }
        }
      } catch (Exception e) {
        System.err.println("[test] Error: " + e.getMessage());
        e.printStackTrace();
      }
      testDone[0] = true;
    });

    // Run the VM for 2 seconds to let the test shred execute
    vm.advanceTime(44100 * 2);

    System.out.println("[test] Peak during direct play: " + peakDuringPlay[0]);
    System.out.println("[test] Test done: " + testDone[0]);

    vm.shutdown();

    assertTrue(testDone[0], "Test shred didn't complete");
    assertTrue(peakDuringPlay[0] > 0.001f, "No audio from direct SndBuf→dac");
  }

  @Test
  public void testSndBufDirectCompute() throws Exception {
    // Pure unit test — no shred, no event, just direct compute
    SndBuf buf = new SndBuf();
    buf.setSamples(new float[]{0.5f, -0.3f, 0.2f, -0.1f, 0.05f});
    buf.rate(1);

    System.out.println("[test] samples loaded: " + buf.samples());
    float out0 = buf.tick(0); System.out.println("[test] tick(0): " + out0);
    float out1 = buf.tick(1); System.out.println("[test] tick(1): " + out1);
    float out2 = buf.tick(2); System.out.println("[test] tick(2): " + out2);
    float out3 = buf.tick(3); System.out.println("[test] tick(3): " + out3);
    float out4 = buf.tick(4); System.out.println("[test] tick(4): " + out4);
    float out5 = buf.tick(5); System.out.println("[test] tick(5): " + out5); // should be 0

    assertTrue(Math.abs(out0 - 0.5f) < 0.001f, "First sample should be 0.5");
    assertTrue(Math.abs(out1 - (-0.3f)) < 0.001f, "Second sample should be -0.3");
    assertTrue(out5 == 0.0f, "Past end should be 0");
  }
}
