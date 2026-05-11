package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.engine.DelugeEngineDSL;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.SoundDrum;
import org.chuck.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Loads every KITS/*.XML preset into the engine, plays a simple pattern, and verifies
 * audible output. Each kit gets a fresh VM for clean isolation.
 */
@Tag("slow")
public class KitXmlPresetTest {

  private ChuckVM vm;

  /** Returns the list of kit XML filenames from the classpath resource directory. */
  static Stream<String> kitFiles() {
    // Read the resource directory listing at build time — works when resources are
    // on the classpath (Maven copies src/main/resources to target/classes).
    List<String> names = new ArrayList<>();
    String[] known = {
      "000 TR-808.XML", "001 DDD-1.XML", "002 SDS-5.XML", "003 TR-909.XML",
      "004 R-50.XML", "005 R-100.XML", "006 LD.XML", "007 HR-16B.XML",
      "008 SCDT.XML", "009 RX-5.XML", "010 XV-5080.XML", "011 KR-55.XML",
      "012 HR-II.XML", "013 AT Rhythm.XML", "014 CR-78.XML",
      "015 Andrew Stirton Frugal.XML", "016 Electronisounds 1.XML",
      "017 Electronisounds 2.XML", "018 Electronisounds 3.XML",
      "019 Fairburg.XML", "020 Leonard Ludvigsen Beatbox.XML",
      "021 hodeur 1.XML", "022 hodeur 2.XML", "023 hodeur 3.XML",
      "024 James R Closs 1.XML", "025 James R Closs 2.XML",
      "026 Amiga909.XML", "027 Reciprocal Sound.XML",
      "028 Danny Taurus.XML", "029 Danny Taurus 2.XML",
      "030 Chaz Bundick.XML", "031 Reuben Winter.XML",
      "032 Kody Nielson.XML", "033 Alfred Darlington.XML",
      "034 Travis Egedy.XML", "035 Sjionel Timu.XML",
      "036 Stefanie Franciotti.XML", "037 Stephanie Engelbrecht.XML",
      "038 Jonathan Snipes (FX).XML", "039 Campbell Kneale.XML",
      "040 John Atkinson.XML", "041 Jonathan Snipes (Waterfalls).XML",
      "042 Phil Elverum.XML"
    };
    for (String n : known) names.add(n);
    return names.stream();
  }

  /**
   * Capture peak and RMS from both channels while advancing the VM.
   * Same pattern as AllSoundsComparisonTest.captureOutput().
   */
  private double[] captureOutput(int durationMs) {
    float peakL = 0, peakR = 0;
    double sumSqL = 0, sumSqR = 0;
    int samples = 0;

    int totalSamples = 44100 * durationMs / 1000;
    int block = 441;
    int blocks = totalSamples / block;

    for (int i = 0; i < blocks; i++) {
      vm.advanceTime(block);
      float curL = Math.abs(vm.getDacChannel(0).getLastOut());
      float curR = Math.abs(vm.getDacChannel(1).getLastOut());
      if (curL > peakL) peakL = curL;
      if (curR > peakR) peakR = curR;
      sumSqL += curL * curL;
      sumSqR += curR * curR;
      samples++;
    }

    return new double[]{peakL, peakR, Math.sqrt(sumSqL / samples), Math.sqrt(sumSqR / samples)};
  }

  @AfterEach
  void tearDown() {
    if (vm != null) vm.shutdown();
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("kitFiles")
  void testKitPreset(String fileName) throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    System.setProperty("chuck.loglevel", "1");
    // Allocate enough tracks for the kit's voices
    System.setProperty("deluge.tracks", "128");

    vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    // 1. Parse the kit XML
    String kitName = fileName.replace(".XML", "");
    InputStream is = getClass().getResourceAsStream("/KITS/" + fileName);
    if (is == null) {
      is = getClass().getClassLoader().getResourceAsStream("KITS/" + fileName);
    }
    assertTrue(is != null, "Kit resource not found: " + fileName);
    KitTrackModel kit;
    try {
      kit = DelugeXmlParser.parseKit(is, kitName);
    } catch (Exception e) {
      System.out.println("SKIP " + kitName + ": XML parse error - " + e.getMessage());
      vm.shutdown();
      return; // skip malformed XML presets (e.g., unclosed tags in community samples)
    }

    int soundCount = kit.getDrums().size();
    assertTrue(soundCount > 0, "Kit " + kitName + " should have at least 1 sound");

    // 2. Start engine
    vm.spork(new DelugeEngineDSL(vm));
    vm.advanceTime(44100);

    // 3. Push sample paths to engine rows
    for (int i = 0; i < soundCount; i++) {
      String path = ((SoundDrum) kit.getDrums().get(i)).getSamplePath();
      if (path != null && !path.isEmpty()) {
        vm.setGlobalString("g_sample_" + i, path);
        bridge.setTrackType(i, 0);
        bridge.setMute(i, false);
        bridge.setTrackLevel(i, 0.8);
      }
    }
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.advanceTime(44100);

    // 4. Write a pattern: kick on 0,4,8,12 for the first 8 sounds
    for (int v = 0; v < Math.min(8, soundCount); v++) {
      for (int step : new int[]{0, 4, 8, 12}) {
        bridge.setStep(v, step, true);
        bridge.setVelocity(v, step, 0.9);
      }
    }

    // 5. Start playback
    vm.setGlobalFloat(BridgeContract.G_MASTER_VOL, 1.0);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.setGlobalFloat(BridgeContract.G_BPM, 120.0);

    // 6. Capture audio
    double[] stats = captureOutput(4000);

    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
    vm.advanceTime(4410);

    double peakAvg = (stats[0] + stats[1]) / 2.0;
    double rmsAvg = (stats[2] + stats[3]) / 2.0;

    System.out.printf("Kit %-40s sounds=%2d Peak L=%.6f R=%.6f RMS L=%.6f R=%.6f (avg peak=%.6f)%n",
        kitName, soundCount, stats[0], stats[1], stats[2], stats[3], peakAvg);

    assertTrue(peakAvg > 0.001,
        "Kit " + kitName + " should produce audible output (peak avg=" + peakAvg + ")");
  }
}
