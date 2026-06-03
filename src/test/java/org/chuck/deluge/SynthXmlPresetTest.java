package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Stream;
import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.engine.DelugeEngineDSL;
import org.chuck.deluge.model.EnvelopeModel;
import org.chuck.deluge.model.SynthTrackModel;
import org.chuck.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Loads every SYNTHS/*.XML preset into the engine, pushes its parameters to the bridge, plays a
 * 16-step pattern on all 8 rows, and verifies audible output.
 *
 * <p>Each synth gets a fresh VM for clean isolation. 171 presets × ~4s ≈ 11 minutes total.
 *
 * <p><b>Caveat:</b> Synth XMLs encode "subtractive mode" parameters, but our engine only has an FM
 * signal path. We set algo=0 (FM) with minimal modulation so the carrier oscillates freely. This
 * tests "engine produces sound from parsed XML data" (regression coverage), not "engine sounds
 * identical to a real Deluge" (firmware compliance — separate work).
 */
@Tag("slow")
@Disabled(
    "Legacy DelugeEngineDSL engine is unsupported; rebuild on the firmware pure engine. See docs/java-port-review-non-dx7-2026-06-03.md.")
public class SynthXmlPresetTest {

  private ChuckVM vm;

  /** Returns all 171 synth XML filenames. */
  static Stream<String> synthFiles() {
    return List.of(
        "000 Rich Saw Bass.XML",
        "001 Sync Bass.XML",
        "002 Basic Square Bass.XML",
        "003 Synthwave Bass.XML",
        "004 Dubby Bass.XML",
        "005 Sweet Mono Bass.XML",
        "006 Vaporwave Bass.XML",
        "007 Detuned Saw Bass.XML",
        "008 FM Rich Distorted Bass.XML",
        "009 Hoover Bass.XML",
        "010 Gravel Basscamp.XML",
        "011 Dubstep Bass.XML",
        "012 Blunt Sync Bass.XML",
        "013 Trap Bass 1.XML",
        "014 Trap Bass 2.XML",
        "015 Resonant Filter Bass.XML",
        "016 Dark Saturated Bass.XML",
        "017 Impact Saw Lead.XML",
        "018 Rich Saw Lead.XML",
        "019 Fizzy Strings.XML",
        "020 Soft Saw Lead.XML",
        "021 80s TV Lead.XML",
        "022 Rich Filter LFO Lead.XML",
        "023 Analog Mono Wow.XML",
        "024 Warble Bass Pluck.XML",
        "025 Soft Synth Organ.XML",
        "026 PW Organ.XML",
        "027 PW Envelope.XML",
        "028 PWM.XML",
        "029 Chiptune Trill.XML",
        "030 Distant Porta.XML",
        "031 Nasal Choir.XML",
        "032 Bandpass Choir.XML",
        "033 Rich Square.XML",
        "034 Square Choir.XML",
        "035 Bell Lead & Bass.XML",
        "036 Analog Ambient Square.XML",
        "037 Echo Chord.XML",
        "038 Vapor Arp.XML",
        "039 Detuned Retriggering Saws.XML",
        "040 Spacer Leader.XML",
        "041 Zithar - Vibed.XML",
        "042 High Triangle.XML",
        "043 Square Porta.XML",
        "044 8-Bit Lead.XML",
        "045 Square Sync.XML",
        "046 Saw Sync.XML",
        "047 Basic Dirty Bass.XML",
        "048 Thin Pulse Bass.XML",
        "049 Basic FM.XML",
        "050 FM Basic Bass.XML",
        "051 FM Rich Brass.XML",
        "052 Soft FM.XML",
        "053 Detuned FM Horns.XML",
        "054 Ghostly Sines.XML",
        "055 FM Theremin.XML",
        "056 FM Bell Modulation.XML",
        "057 FM Lead.XML",
        "058 FM Rising Attack.XML",
        "059 Distorted Lead Guitar.XML",
        "060 Bass Guitar.XML",
        "061 Blown-Staccato-Panpipes.XML",
        "062 Trumpet.XML",
        "063 Tuba.XML",
        "064 Reeds-Flute-Oboe.XML",
        "065 Cello.XML",
        "066 Violin.XML",
        "067 Marimba.XML",
        "068 FM Bells 1.XML",
        "069 FM Bells 2.XML",
        "070 Glockenspiel.XML",
        "071 Rhodes.XML",
        "072 Kyoto Phono.XML",
        "073 Piano.XML",
        "074 Electric Piano.XML",
        "075 Electric Piano With Strings.XML",
        "076 Organ.XML",
        "077 FM Perc-Organ.XML",
        "078 House.XML",
        "079 Phased Arper.XML",
        "080 House.XML",
        "081 Xylophone Big Bass.XML",
        "082 Short Sharp Delay.XML",
        "083 Dark Chorus.XML",
        "084 FM Narrow Band.XML",
        "085 Deep Fizz.XML",
        "086 Techno Organ.XML",
        "087 Define Leader.XML",
        "088 Yelp Chords.XML",
        "089 Degraded Retro Lead.XML",
        "090 FM Organ.XML",
        "091 FM Ricochet.XML",
        "092 Degraded Tremolo.XML",
        "093 FM Distorted Bells.XML",
        "094 Ambient Occlusion Lead.XML",
        "095 Harsh FM Feedback.XML",
        "096 FM Guitar Power Chord.XML",
        "097 Saturated Filter.XML",
        "098 Saturated Sync.XML",
        "099 Overdrive Reese Sync.XML",
        "100 Noise Lead.XML",
        "101 Atebit.XML",
        "102 Harsh 5th.XML",
        "103 Sci-fi Chaos.XML",
        "104 Alien Vomit.XML",
        "105 Attack Bass.XML",
        "106 Hang Drum.XML",
        "107 FM LPG Percussion.XML",
        "108 Robo Arp.XML",
        "109 Talking Arp.XML",
        "110 Crystalline Ringmod.XML",
        "111 Satellite Drum.XML",
        "112 Hard Tech Beat.XML",
        "113 Bio Lab.XML",
        "114 Sootheerio.XML",
        "115 Sounds After Take-off.XML",
        "116 Evolving Frequencies.XML",
        "117 Belledy.XML",
        "118 Small Bridge Pad.XML",
        "119 Stars Of The Bin Pad.XML",
        "120 High Harsh Pad.XML",
        "121 Tiny Lights.XML",
        "122 Majestic Synth Orchestra.XML",
        "123 Space Dust.XML",
        "124 Filter Modulation Pad.XML",
        "125 Evolving Pad.XML",
        "126 Dark FM Pad.XML",
        "127 Alien Larvae.XML",
        "128 Lunar Landing.XML",
        "129 Sci-fi Scenic.XML",
        "130 Dark Strings.XML",
        "131 Warm Strings.XML",
        "132 Organ Strings.XML",
        "133 80s Strings.XML",
        "134 Melody String.XML",
        "135 Soothing Growth Pad.XML",
        "136 Synthwave Pad.XML",
        "137 Epic Saw Modulation Pad.XML",
        "138 Brassy Pad.XML",
        "139 Detuned Saw Pad.XML",
        "140 Slow Aural Swells.XML",
        "141 Ringmod Pad.XML",
        "142 Phaser.XML",
        "143 Chillout Pad.XML",
        "144 Sweep Chords.XML",
        "145 Eerie High Pad.XML",
        "146 Atmospheric Squares Pad.XML",
        "147 Resonant Filter Pad.XML",
        "148 Warm 5th Pad.XML",
        "149 Cold 5th Pad.XML",
        "150 Vaporwave Pad.XML",
        "151 Radiant FM Pad.XML",
        "152 Small Jet Pad.XML",
        "153 FM Modulation Pad.XML",
        "154 Rich FM Pad 1.XML",
        "155 Rich FM Pad 2.XML",
        "156 Rich FM Pad 3.XML",
        "157 Rich FM Pad 4.XML",
        "158 Tempo-Synced LFO.XML",
        "159 80s Bass Rhythm.XML",
        "160 Synthwave Bass Arp.XML",
        "161 Synthwave Vibrato Arp.XML",
        "162 Busy Arp.XML",
        "163 Crisp Pop Arp.XML",
        "164 Study Arp.XML",
        "165 Acid Arp.XML",
        "166 Harpsichord Cyborg.XML",
        "167 FM Metallic Bass Arp.XML",
        "168 Hang Drum.XML",
        "169 Double Bass.XML",
        "170 Sitar.XML")
        .stream();
  }

  /** Map oscillator type string to engine type index. */
  private static int oscTypeIndex(String type) {
    if ("SINE".equals(type)) return 0;
    if ("SAW".equals(type)) return 1;
    if ("SQUARE".equals(type)) return 2;
    if ("TRIANGLE".equals(type)) return 3;
    return 0; // default SINE
  }

  /**
   * Capture peak and RMS from both channels. Same pattern as
   * AllSoundsComparisonTest.captureOutput().
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

    return new double[] {peakL, peakR, Math.sqrt(sumSqL / samples), Math.sqrt(sumSqR / samples)};
  }

  @AfterEach
  void tearDown() {
    if (vm != null) vm.shutdown();
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("synthFiles")
  void testSynthPreset(String fileName) throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    System.setProperty("chuck.loglevel", "1");
    System.setProperty("deluge.tracks", "64");

    vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    // 1. Parse the synth XML
    String synthName = fileName.replace(".XML", "");
    InputStream is = getClass().getResourceAsStream("/SYNTHS/" + fileName);
    if (is == null) {
      is = getClass().getClassLoader().getResourceAsStream("SYNTHS/" + fileName);
    }
    assertTrue(is != null, "Synth resource not found: " + fileName);
    SynthTrackModel synth = DelugeXmlParser.parseSynth(is, synthName);

    // 2. Start engine
    vm.spork(new DelugeEngineDSL(vm));
    vm.advanceTime(44100);

    // 3. Set up 8 voice rows as synth tracks (type 1)
    int baseRow = 0;
    int voiceCount = 8;
    for (int v = 0; v < voiceCount; v++) {
      int r = baseRow + v;
      bridge.setTrackType(r, 1);
      bridge.setMute(r, false);
      bridge.setTrackLevel(r, 0.8);
    }

    // Ensure osc_type array exists in the VM (the UI registers it; the engine null-guards)
    ChuckArray oscTypeArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_OSC_TYPE);
    if (oscTypeArr == null) {
      oscTypeArr = new ChuckArray("int", BridgeContract.TRACKS);
      vm.setGlobalObject(BridgeContract.G_OSC_TYPE, oscTypeArr);
    }

    // 4. Push parsed parameters to the bridge
    int typeIdx = oscTypeIndex(synth.getOsc1Type());

    for (int v = 0; v < voiceCount; v++) {
      int r = baseRow + v;

      // Oscillator type
      oscTypeArr.setInt(r, typeIdx);

      // Filter
      bridge.setFilterFreq(r, synth.getLpfFreq() / 20000.0f);
      bridge.setFilterRes(r, synth.getLpfRes() / 100.0f);
      bridge.setFilterMode(r, synth.getFilterMode().ordinal());

      // Envelopes (4)
      for (int e = 0; e < 4; e++) {
        EnvelopeModel adsr = synth.getEnv(e);
        if (adsr != null) {
          bridge.setEnv(r, e, adsr.attack(), adsr.decay(), adsr.sustain(), adsr.release());
        }
      }

      // Algorithm: 0 (FM) — engine defaults to FM path; this works for all presets
      bridge.setSynthAlgo(r, 0);
    }

    // Broadcast load trigger so engine picks up the track type assignments
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.advanceTime(44100);

    // 5. Write a V-shaped 16-step pattern across all rows
    int[] velocities = {20, 40, 60, 80, 100, 80, 60, 40, 20, 40, 60, 80, 100, 80, 60, 40};
    for (int v = 0; v < voiceCount; v++) {
      int r = baseRow + v;
      for (int s = 0; s < 16; s++) {
        float vel = velocities[s] / 100.0f;
        bridge.setStep(r, s, true);
        bridge.setVelocity(r, s, vel);
        bridge.setGate(r, s, 0.9);
      }
    }

    // 6. Start playback
    vm.setGlobalFloat(BridgeContract.G_MASTER_VOL, 1.0);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.setGlobalFloat(BridgeContract.G_BPM, 120.0);

    // 7. Capture audio
    double[] stats = captureOutput(4000);

    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
    vm.advanceTime(4410);

    double peakAvg = (stats[0] + stats[1]) / 2.0;
    double rmsAvg = (stats[2] + stats[3]) / 2.0;

    String oscType = synth.getOsc1Type();
    String filterMode = synth.getFilterMode().name();
    System.out.printf(
        "Synth %-40s osc=%-8s filter=%-10s Peak L=%.6f R=%.6f RMS L=%.6f R=%.6f (avg peak=%.6f)%n",
        synthName, oscType, filterMode, stats[0], stats[1], stats[2], stats[3], peakAvg);

    assertTrue(
        peakAvg > 0.001,
        "Synth " + synthName + " should produce audible output (peak avg=" + peakAvg + ")");
  }
}
