package org.chuck.deluge;

import static org.chuck.core.ChuckDSL.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import org.chuck.audio.*;
import org.chuck.audio.util.*;
import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.engine.DelugeEngineDSL;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.SoundDrum;
import org.chuck.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

/**
 * Quick diagnostic: load the 808 kick into SndBuf through the engine pipeline, capture via WvOut2,
 * and compare raw samples against the source WAV.
 */
@Disabled("Legacy DelugeEngineDSL engine is unsupported; rebuild on the firmware pure engine. See docs/java-port-review-non-dx7-2026-06-03.md.")
public class KitDebugTest {

  private static final int SAMPLE_RATE = 44100;

  @Test
  void debugKickSignalChain() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    System.setProperty("chuck.debug.export", "true"); // enable debug prints in engine
    ChuckVM vm = new ChuckVM(SAMPLE_RATE, 2);
    vm.setLogLevel(2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    // Parse 808 kit
    InputStream is = getClass().getResourceAsStream("/KITS/000 TR-808.XML");
    assertNotNull(is);
    KitTrackModel kit = DelugeXmlParser.parseKit(is, "000 TR-808");
    String samplePath = ((SoundDrum) kit.getDrums().get(0)).getSamplePath();
    System.out.println("KICK sample path from XML: " + samplePath);

    // Resolve sample path
    File localTarget = new File("target/classes/" + samplePath);
    assertTrue(localTarget.exists(), "Sample file not found: " + localTarget);
    String absPath = localTarget.getAbsolutePath();
    System.out.println("Resolved absolute path: " + absPath);

    // Start engine
    vm.spork(new DelugeEngineDSL(vm));
    vm.advanceTime(100);

    // Set sample path for track 0
    vm.setGlobalString("g_sample_0", absPath);

    // Trigger load
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.advanceTime(SAMPLE_RATE); // Give time for sample to load

    // Check if track type for 0 is kit
    org.chuck.core.ChuckArray trackType =
        (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_TYPE);
    if (trackType != null) {
      System.out.println("Track 0 type: " + trackType.getInt(0));
    }

    // Set up: track 0 is kit, muted others
    bridge.setTrackType(0, 0);
    bridge.setMute(0, false);
    for (int t = 1; t < BridgeContract.TRACKS; t++) bridge.setMute(t, true);
    bridge.setTrackLevel(0, 1.0);

    // Set ADSR
    ChuckArray kitAtk = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_ATTACK);
    if (kitAtk != null) {
      kitAtk.setFloat(0, 0.001f);
      System.out.println("KIT_ATTACK[0] = " + kitAtk.getFloat(0));
    }

    // DIAGNOSTIC: check G_PATTERN state
    ChuckArray patArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PATTERN);
    System.out.println("G_PATTERN size: " + (patArr != null ? patArr.size() : -1));

    // Clear all steps then set step 0
    for (int t = 0; t < BridgeContract.TRACKS; t++) {
      for (int s = 0; s < BridgeContract.STEPS; s++) {
        bridge.setStep(t, s, false);
        bridge.setVelocity(t, s, 0.0);
      }
    }
    vm.advanceTime(4410);

    bridge.setStep(0, 0, true);
    bridge.setVelocity(0, 0, 1.0);
    bridge.setGate(0, 0, 0.9);

    // DIAGNOSTIC: verify pattern after setStep
    System.out.println("G_PATTERN[0]=" + patArr.getInt(0) + " (expected 1)");
    System.out.println("G_PATTERN[1]=" + patArr.getInt(1) + " (expected 0)");

    // Check G_CURRENT_CLIP
    ChuckArray curClip = (ChuckArray) vm.getGlobalObject(BridgeContract.G_CURRENT_CLIP);
    if (curClip != null) {
      System.out.println("G_CURRENT_CLIP[0]=" + curClip.getInt(0) + " (expected 0)");
    }

    // Check G_MUTE
    ChuckArray muteArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MUTE);
    if (muteArr != null) {
      System.out.println("G_MUTE[0]=" + muteArr.getInt(0) + " (expected 0=unmuted)");
      System.out.println("G_MUTE[1]=" + muteArr.getInt(1) + " (expected 1=muted)");
    }

    // Check G_VELOCITY
    ChuckArray velArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_VELOCITY);
    if (velArr != null) {
      System.out.println("G_VELOCITY[0]=" + velArr.getFloat(0) + " (expected 1.0)");
    }

    // Reload samples
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.advanceTime(SAMPLE_RATE / 2);

    // ── DIAGNOSTIC: Record directly from masterTap BEFORE play starts ──
    // This bypasses the export_shred entirely to check if audio exists in the chain.
    File diagDir = new File(System.getProperty("java.io.tmpdir"), "deluge-kitdiag");
    diagDir.mkdirs();
    String diagWav = new File(diagDir, "kit_mastertap.wav").getAbsolutePath();

    // We need to connect a diagnostic WvOut2 AFTER the engine is set up but BEFORE play.
    // The engine's transport_shred and kit_shred have already created their chains.
    // masterTap is a Gain that sits between the kit chain and dac.
    // We'll create a WvOut2 and splice it into the chain from the test's own shred.
    vm.spork(
        () -> {
          // Wait for engine to be fully initialized (sample load, etc.)
          advance(samp(100));
          Gain masterTap = (Gain) vm.getGlobalObject(BridgeContract.G_MASTER_TAP);
          WvOut2 diagWv = new WvOut2(SAMPLE_RATE);
          // Splicing BEFORE export_shred does:
          //   masterTap → diagWv → dac
          // This replaces: masterTap → dac
          // NOTE: if export_shred also splices later, there could be two WvOut2s.
          // We do this BEFORE setting G_PLAY to ensure it's in the graph from the start.
          ChuckUGen dacObj = dac();
          masterTap.unchuck(dacObj);
          masterTap.chuck(diagWv);
          diagWv.chuck(dacObj);
          diagWv.wavWrite(diagWav);
          diagWv.fileGain(1.0f);
          // We set a flag so the test can track we're ready
          vm.setGlobalFloat("g_diag_ready", 1.0f);
          System.out.println("[diag] Diagnostic WvOut2 connected. Recording to: " + diagWav);
          // Record for 3 seconds, then close
          advance(second(3));
          diagWv.closeFile();
          System.out.println("[diag] Diagnostic WAV closed.");
        });
    vm.advanceTime(SAMPLE_RATE); // Wait for diag shred to connect

    // Play
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.setGlobalFloat(BridgeContract.G_BPM, 120.0);
    vm.advanceTime(SAMPLE_RATE * 3); // 3 seconds (extra for diag to close)

    // Stop
    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
    vm.advanceTime(4410);

    // Check G_CURRENT_STEP to see if clock advanced
    System.out.println(
        "G_CURRENT_STEP after 3s="
            + vm.getGlobalInt(BridgeContract.G_CURRENT_STEP)
            + " (expected ~24)");

    // Load and analyze diagnostic WAV
    File diagFile = new File(diagWav);
    if (diagFile.exists()) {
      assertTrue(diagFile.length() > 44, "Diagnostic WAV too small");
      float[] diagOut = AudioAnalyzer.loadWav(new File(diagWav));
      System.out.println(
          "DIAGNOSTIC ("
              + diagWav
              + "): "
              + diagOut.length
              + " samples, RMS="
              + rms(diagOut)
              + ", peak="
              + peak(diagOut));
      boolean diagHasAudio = false;
      for (int i = 0; i < Math.min(5000, diagOut.length); i++) {
        if (Math.abs(diagOut[i]) > 0.01) {
          diagHasAudio = true;
          break;
        }
      }
      System.out.println("DIAGNOSTIC has audio: " + diagHasAudio);
      System.out.println("First 10 diagnostic samples:");
      for (int i = 0; i < 10 && i < diagOut.length; i++) {
        System.out.printf("  %3d: %8.6f%n", i, diagOut[i]);
      }
    } else {
      System.out.println("DIAGNOSTIC WAV NOT FOUND: " + diagWav);
    }
  }

  private double rms(float[] data) {
    double sumSq = 0;
    for (float v : data) sumSq += v * v;
    return Math.sqrt(sumSq / data.length);
  }

  private double peak(float[] data) {
    double p = 0;
    for (float v : data) {
      double abs = Math.abs(v);
      if (abs > p) p = abs;
    }
    return p;
  }
}
