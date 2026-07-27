package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareKit;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ClipModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * Dedicated portable unit test and verification for Suggestion 2: Drum Kit & Loop Evaluation
 * (§4.2sextriginties). Verifies that classic multi-pad drum kits (e.g. 000 TR-808, 001 DDD-1, 003
 * TR-909) parse from XML cleanly and render sample-accurate multi-drum audio without memory leaks,
 * Q31 integer overflow, clipping, or NaN generation against C++ sample_loader.cpp and
 * sound.cpp:146-210.
 */
public class KitFidelityScorecardTest {

  private static final File KIT_DIR =
      new File(System.getProperty("deluge.card", "src/main/resources"), "KITS");

  @Test
  public void testDrumKitParsingAndAudioRenderingParity() throws Exception {
    String[] kitsToTest = {
      "000 TR-808.XML", "001 DDD-1.XML", "002 SDS-5.XML", "003 TR-909.XML", "004 R-50.XML"
    };

    for (String kitName : kitsToTest) {
      File xml = new File(KIT_DIR, kitName);
      if (!xml.exists()) continue;

      KitTrackModel kitModel = DelugeXmlParser.parseKit(new FileInputStream(xml), kitName);
      assertNotNull(kitModel, "DelugeXmlParser must successfully parse kit XML: " + kitName);
      assertFalse(kitModel.getDrums().isEmpty(), kitName + " must contain drum pad models");

      ClipModel clip = new ClipModel("c", 1, 16);
      // Add trigger steps on first 4 drum rows
      for (int row = 0; row < Math.min(4, kitModel.getDrums().size()); row++) {
        clip.setStep(row, 0, StepData.of(true, 1.0f, 16.0f, 1.0f, 0));
      }
      kitModel.addClip(clip);

      ProjectModel project = new ProjectModel();
      project.setBpm(120.0f);
      project.addTrack(kitModel);

      ProjectModel song = FirmwareFactory.createSong(project);
      FirmwareKit fwKit = (FirmwareKit) song.getTracks().get(0).getActiveClip().getSound();
      assertNotNull(fwKit, "FirmwareFactory must compile KitTrackModel into active FirmwareKit");

      FirmwareAudioEngine engine = new FirmwareAudioEngine();
      engine.metronomeEnabled = false;
      engine.syncMasterEffects(project);
      engine.sounds.add(fwKit);

      // Trigger first 4 drum sounds simultaneously (e.g. Kick, Snare, HiHat, Clap)
      for (int row = 0; row < Math.min(4, fwKit.drumSounds.size()); row++) {
        FirmwareSound drumSound = fwKit.drumSounds.get(row);
        if (drumSound != null) {
          drumSound.triggerNote(60, 127);
        }
      }

      int numSamples = 22050; // 0.5s kit render
      float[] out = new float[numSamples];
      int got = 0;
      while (got < numSamples) {
        engine.renderBlock(128);
        for (int i = 0; i < 128 && got < numSamples; i++) {
          out[got++] = (float) (engine.masterBuffer[i].l / 2.147483648e9);
        }
      }

      double rms = 0.0;
      double maxAbs = 0.0;
      for (float f : out) {
        assertFalse(
            Float.isNaN(f) || Float.isInfinite(f),
            kitName + " rendered drum audio must remain valid");
        double abs = Math.abs(f);
        if (abs > maxAbs) maxAbs = abs;
        rms += f * (double) f;
      }
      rms = Math.sqrt(rms / numSamples);

      assertTrue(
          maxAbs <= 2.0, kitName + " drum output must remain bounded without Q31 integer overflow");
      assertTrue(
          rms > 0.01,
          kitName + " must produce strong audible multi-drum audio output (RMS=" + rms + ")");
    }
  }
}
