package org.chuck.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.FileInputStream;
import org.chuck.deluge.firmware.dsp.oscillators.OscType;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.util.Q31;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

public class AudioIntegrityTest {

  public AudioIntegrityTest() {
    System.out.println("DEBUG: AudioIntegrityTest instantiated!");
  }

  private File findKit() {
    String[] paths = {
      "deluge/src/main/resources/KITS/000 TR-808.XML",
      "src/main/resources/KITS/000 TR-808.XML",
      "../deluge/src/main/resources/KITS/000 TR-808.XML"
    };
    for (String p : paths) {
      File f = new File(p);
      if (f.exists()) return f;
    }
    return null;
  }

  @Test
  public void testKitPlaybackAndGating() throws Exception {
    FirmwareAudioEngine engine = new FirmwareAudioEngine();

    File kitFile = findKit();
    assertNotNull(kitFile, "Could not find 808 kit XML");

    KitTrackModel kitModel;
    try (FileInputStream fis = new FileInputStream(kitFile)) {
      kitModel = DelugeXmlParser.parseKit(fis, "808");
    }
    FirmwareKit kit = (FirmwareKit) FirmwareFactory.createKitClip(kitModel).sound;
    engine.sounds.add(kit);

    // Baseline check
    engine.renderBlock(128);

    // Trigger Kick
    kit.triggerDrum(0, 127);

    boolean foundSignal = false;
    int kitPeak = 0;
    for (int blk = 0; blk < 100; blk++) {
      engine.renderBlock(128);
      for (int i = 0; i < 128; i++) {
        int absL = Math.abs(engine.masterBuffer[i].l);
        if (absL > kitPeak) kitPeak = absL;
        if (absL != 0) foundSignal = true;
      }
    }
    System.out.println("Kit Peak: " + kitPeak);
    assertTrue(foundSignal, "Kit should produce signal after trigger");

    // Check for decay
    boolean returnedToZero = false;
    int nonZeroValue = 0;
    int failBlock = 0;
    for (int blk = 0; blk < 1000; blk++) {
      engine.renderBlock(128);
      boolean blockIsZero = true;
      for (int i = 0; i < 128; i++) {
        if (engine.masterBuffer[i].l != 0) {
          blockIsZero = false;
          nonZeroValue = engine.masterBuffer[i].l;
        }
      }
      if (blockIsZero) {
        // Confirm 5 consecutive zero blocks for absolute silence
        boolean allZero = true;
        for (int j = 0; j < 5; j++) {
          engine.renderBlock(128);
          for (int k = 0; k < 128; k++) if (engine.masterBuffer[k].l != 0) allZero = false;
        }
        if (allZero) {
          returnedToZero = true;
          break;
        }
      }
      failBlock = blk;
    }
    if (!returnedToZero) {
      System.out.println(
          "FAILURE: Kit sound did not return to zero. Last block: "
              + failBlock
              + " Sample 0: "
              + nonZeroValue);
    }
    assertTrue(returnedToZero, "Kit sound must decay to absolute zero");
  }

  @Test
  public void testSynthPitchIntegrity() throws Exception {
    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    FirmwareSound synth = new FirmwareSound();
    synth.oscTypes[0] = OscType.SINE;
    // Ensure volume is UP
    synth.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = Q31.ONE;
    synth.paramNeutralValues[Param.LOCAL_VOLUME] = Q31.ONE;
    engine.sounds.add(synth);

    // Trigger Note C4 (60)
    synth.triggerNote(60, 127);

    int peak1 = 0;
    for (int blk = 0; blk < 100; blk++) {
      engine.renderBlock(128);
      if (blk == 50) {
        System.out.println("DEBUG: Block 50 sample 0=" + engine.masterBuffer[0].l);
      }
      for (int i = 0; i < 128; i++) {
        int absL = Math.abs(engine.masterBuffer[i].l);
        if (absL > peak1) peak1 = absL;
      }
    }
    System.out.println("Synth C4 Peak: " + peak1);
    assertTrue(peak1 > 0, "Synth C4 should produce signal");

    // Release
    synth.releaseNote(60);
    for (int i = 0; i < 200; i++) engine.renderBlock(128);

    // Baseline after release
    int base = 0;
    engine.renderBlock(128);
    for (int i = 0; i < 128; i++) base = Math.max(base, Math.abs(engine.masterBuffer[i].l));
    assertEquals(0, base, "Should be silent after release");

    // Trigger Note C5 (72)
    synth.triggerNote(72, 127);
    int peak2 = 0;
    for (int blk = 0; blk < 50; blk++) {
      engine.renderBlock(128);
      for (int i = 0; i < 128; i++) peak2 = Math.max(peak2, Math.abs(engine.masterBuffer[i].l));
    }
    assertTrue(peak2 > 0, "Synth C5 should produce signal");
  }
}
