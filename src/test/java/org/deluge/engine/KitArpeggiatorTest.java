package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.deluge.firmware2.Arpeggiator;
import org.deluge.firmware2.Oscillator.OscType;
import org.deluge.firmware2.Param;
import org.deluge.firmware2.Sound;
import org.deluge.model.ArpModel;
import org.deluge.model.ClipModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.NoteModel;
import org.deluge.model.NoteRowModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SequenceDirection;
import org.deluge.model.SoundDrum;
import org.deluge.playback.PlaybackHandler;
import org.deluge.project.ProjectSerializer;
import org.deluge.xml.SongXmlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * C Kit's own arpeggiator (kit.cpp:705-797, 1136-1240, 1291-1375; arpeggiator.cpp:218-220, 880,
 * 1648-1983): tests pass-through + arp-off randomizer, synced kit arp cycling across held drums,
 * per-drum {@code includeInKitArp} ({@code kitArp="0"}) & tail-less drum bypass, {@code
 * invertReversedFromKitArp} XOR composition + reset on note-off/releaseAllNotes, active-clip kit
 * arp & {@code <kitParams>} unpatched Q31 knobs across XML round-trips, and independent per-row
 * play positions in {@link PlaybackHandler}.
 */
class KitArpeggiatorTest {

  static final class RecKit extends FirmwareKit {
    final List<String> hits = new ArrayList<>();

    @Override
    protected void drumNoteOn(int row, int vel, int samplesLate) {
      hits.add("ON" + row + ":" + vel);
    }

    @Override
    protected void drumNoteOff(int row) {
      super.drumNoteOff(row);
      hits.add("OFF" + row);
    }

    @Override
    protected boolean bypassesKitArp(int row) {
      return !drumSounds.get(row).arpSettings.includeInKitArp;
    }
  }

  private static void configureSynthDrum(FirmwareSound drum) {
    drum.oscTypes[0] = OscType.SAW;
    drum.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = 0x7FFFFFFF;
    drum.paramNeutralValues[Param.GLOBAL_VOLUME_POST_FX] = 0x7FFFFFFF;
    drum.syncParamsToFw2();
  }

  private static org.deluge.playback.Sample makeTinySample() {
    org.deluge.playback.Sample s = new org.deluge.playback.Sample();
    s.numChannels = 1;
    s.byteDepth = 2;
    s.sampleRate = 44100;
    s.data = new float[] {0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f};
    return s;
  }

  @Test
  void arpOffPassesStraightThroughAndHonorsNoteProbability() {
    // 100% noteProbability maps to 0xFFFFFFFF (value_scaling.cpp:22-33, arpeggiator.cpp:894-896),
    // drawing zero random bytes on pass-through note-on.
    FirmwareSound configured = new FirmwareSound();
    FirmwareFactory.configureArp(configured, ArpModel.defaultConfig());
    assertEquals(0xFFFFFFFF, configured.arpSettings.noteProbability);
    RecKit kit = new RecKit();
    kit.arpSettings.noteProbability = configured.arpSettings.noteProbability;
    org.deluge.firmware2.Functions.resetNoiseSeed();
    int firstExpectedNoise = org.deluge.firmware2.Functions.getNoise();
    org.deluge.firmware2.Functions.resetNoiseSeed();
    kit.triggerDrum(3, 100);
    kit.releaseDrum(3);
    assertEquals(List.of("ON3:100", "OFF3"), kit.hits);
    assertEquals(
        firstExpectedNoise,
        org.deluge.firmware2.Functions.getNoise(),
        "100% noteProbability must not consume PRNG draws on pass-through note-on");

    // Arp-off randomizer with 0% noteProbability drops incoming hits cleanly (arpeggiator.cpp:366).
    kit.hits.clear();
    kit.arpSettings.noteProbability = 0;
    kit.triggerDrum(3, 100);
    kit.releaseDrum(3);
    assertEquals(List.of("OFF3"), kit.hits);
  }

  @Test
  void syncedKitArpCyclesHeldDrumsAndBypassesExcludedOrTaillessDrums() {
    RecKit kit = new RecKit();
    kit.arpSettings.mode = Arpeggiator.ArpMode.ARP;
    kit.arpSettings.syncLevel = Arpeggiator.SyncLevel.SYNC_LEVEL_16TH;
    kit.arpSettings.syncType = Arpeggiator.SyncType.EVEN;
    kit.arpeggiator.playbackClockActive = true;
    kit.drumSounds.get(1).arpSettings.includeInKitArp = false; // excluded from kit arp
    int period = 3 << (9 - Arpeggiator.SyncLevel.SYNC_LEVEL_16TH.ordinal());

    kit.triggerDrum(0, 100);
    kit.triggerDrum(1, 90); // bypasses immediately!
    kit.triggerDrum(2, 110);
    assertEquals(
        List.of("ON1:90"), kit.hits, "excluded drum triggers immediately while kit arp waits");
    kit.hits.clear();

    for (int pos = 0; pos < period * 4; pos++) {
      kit.doTickForwardForKitArp(pos, false);
    }
    List<String> ons = kit.hits.stream().filter(h -> h.startsWith("ON")).toList();
    assertEquals(List.of("ON0:100", "ON2:110", "ON0:100", "ON2:110"), ons);

    // Real FirmwareKit also bypasses a ONCE sample drum without note tails (kit.cpp:1303-1318).
    FirmwareKit realKit = new FirmwareKit();
    realKit.arpSettings.mode = Arpeggiator.ArpMode.ARP;
    realKit.arpSettings.syncLevel = Arpeggiator.SyncLevel.SYNC_LEVEL_16TH;
    realKit.arpeggiator.playbackClockActive = true;
    FirmwareSound onceDrum = realKit.drumSounds.get(0);
    onceDrum.oscTypes[0] = OscType.SAMPLE;
    onceDrum.samples[0] = makeTinySample();
    onceDrum.sampleSettings[0].loopMode = Sound.REPEAT_ONCE;
    onceDrum.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = 0x7FFFFFFF;
    assertTrue(realKit.bypassesKitArp(0), "ONCE sample drum without tails must bypass kit arp");

    FirmwareSound synthDrum = realKit.drumSounds.get(1);
    configureSynthDrum(synthDrum);
    assertFalse(realKit.bypassesKitArp(1), "Synth/CUT drum with tails participates in kit arp");
  }

  @Test
  void invertReversedFromKitArpXorsWithDrumReverseAndResetsOnNoteOffAndStop() {
    FirmwareKit kit = new FirmwareKit();
    kit.arpSettings.mode = Arpeggiator.ArpMode.ARP;
    kit.arpSettings.syncLevel = Arpeggiator.SyncLevel.SYNC_LEVEL_16TH;
    kit.arpSettings.reverseProbability = 0xFFFFFFFF; // 100% reverse from kit arp
    kit.arpeggiator.playbackClockActive = true;

    FirmwareSound drum = kit.drumSounds.get(0);
    drum.oscTypes[0] = OscType.SAMPLE;
    drum.samples[0] = makeTinySample();
    drum.sampleSettings[0].loopMode = Sound.REPEAT_CUT;
    drum.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = 0x7FFFFFFF;
    drum.paramNeutralValues[Param.GLOBAL_VOLUME_POST_FX] = 0x7FFFFFFF;
    drum.arpSettings.reverseProbability = 0; // 0% drum reverse -> 1 ^ 0 = reversed!
    drum.syncParamsToFw2();

    kit.triggerDrum(0, 100);
    kit.doTickForwardForKitArp(0, false);
    assertTrue(drum.fw2Sound.arpeggiator.invertReversedFromKitArp);
    assertTrue(drum.fw2Sound.invertReversed, "100% kit reverse XOR 0% drum reverse = reversed");

    // Post-arp note-off via render gate expiry clears invertReversedFromKitArp (kit.cpp:737, 754),
    // while Sound.invertReversed persists until the next noteOn (sound.cpp:1553) or allNotesOff
    // (sound.cpp:1771).
    kit.arpGateKnob = Integer.MIN_VALUE;
    int[] buf = new int[256];
    kit.renderInternal(buf, 128, new int[256]);
    assertFalse(
        drum.fw2Sound.arpeggiator.invertReversedFromKitArp,
        "post-arp note-off must reset invertReversedFromKitArp");

    // 100% kit reverse XOR 100% drum reverse cancels out (1 ^ 1 = forward!).
    drum.arpSettings.reverseProbability = 0xFFFFFFFF;
    drum.syncParamsToFw2();
    int period = 3 << (9 - Arpeggiator.SyncLevel.SYNC_LEVEL_16TH.ordinal());
    kit.doTickForwardForKitArp(period, false);
    assertTrue(drum.fw2Sound.arpeggiator.invertReversedFromKitArp);
    assertFalse(
        drum.fw2Sound.invertReversed, "100% kit reverse XOR 100% drum reverse must cancel out");

    // releaseAllNotes resets kit arpeggiator & clears both reverse flags (sound.cpp:1771).
    drum.fw2Sound.invertReversed = true;
    kit.releaseAllNotes();
    assertFalse(kit.arpeggiator.hasAnyInputNotesActive());
    assertFalse(drum.fw2Sound.arpeggiator.invertReversedFromKitArp);
    assertFalse(drum.fw2Sound.invertReversed);
  }

  @Test
  void xmlRoundTripPreservesPerClipKitArpUnpatchedKnobsAndPerDrumKitArpFlag(@TempDir Path tempDir)
      throws Exception {
    ProjectModel project = new ProjectModel();
    KitTrackModel kitTrack = new KitTrackModel("Kit1");

    SoundDrum d0 = new SoundDrum("SynthHit");
    d0.setOsc1Type("SAW");
    d0.setArp(ArpModel.defaultConfig().toBuilder().kitArp(1).build());
    SoundDrum d1 = new SoundDrum("ExcludedHit");
    d1.setOsc1Type("SAW");
    d1.setArp(ArpModel.defaultConfig().toBuilder().kitArp(0).build());
    kitTrack.addDrum(d0);
    kitTrack.addDrum(d1);

    ClipModel clip0 = new ClipModel("Clip0", 2, 16);
    clip0.setArp(ArpModel.defaultConfig().toBuilder().active(false).build());
    ClipModel clip1 = new ClipModel("Clip1", 2, 16);
    clip1.setArp(ArpModel.defaultConfig().toBuilder().active(true).octaves(2).syncLevel(8).build());
    clip1.getRawKitParamHex().put("arpeggiatorRate", "0x12345678");
    clip1.getRawKitParamHex().put("arpeggiatorGate", "0x20000000");
    clip1.getRawKitParamHex().put("sequenceLength", "0x80000005");
    kitTrack.addClip(clip0);
    kitTrack.addClip(clip1);
    kitTrack.setActiveClipIndex(1);
    project.addTrack(kitTrack);

    Path saved = tempDir.resolve("SONG_KIT_ARP.XML");
    ProjectSerializer.save(project, saved.toFile());
    assertTrue(Files.exists(saved));

    ProjectModel reloaded = SongXmlParser.parseSong(saved.toFile());
    KitTrackModel parsedKit = (KitTrackModel) reloaded.getTracks().get(0);
    parsedKit.setActiveClipIndex(1);
    assertEquals(1, ((SoundDrum) parsedKit.getDrums().get(0)).getArp().kitArp());
    assertEquals(0, ((SoundDrum) parsedKit.getDrums().get(1)).getArp().kitArp());
    assertFalse(parsedKit.getClips().get(0).getArp().active());
    assertTrue(parsedKit.getClips().get(1).getArp().active());

    FirmwareKit fwKit = FirmwareFactory.createKitSound(parsedKit, reloaded);
    assertEquals(Arpeggiator.ArpMode.ARP, fwKit.arpSettings.mode);
    assertEquals(0x12345678, fwKit.arpRateKnob);
    assertEquals(0x20000000, fwKit.arpGateKnob);
    assertEquals(0x80000005 + Integer.MIN_VALUE, fwKit.arpSettings.sequenceLength);
    assertTrue(fwKit.drumSounds.get(0).arpSettings.includeInKitArp);
    assertFalse(fwKit.drumSounds.get(1).arpSettings.includeInKitArp);
  }

  @Test
  void playbackHandlerHonorsIndependentDrumRowPlayPosAndDirection() {
    ProjectModel project = new ProjectModel();
    KitTrackModel kitTrack = new KitTrackModel("KitIndep");
    SoundDrum sd = new SoundDrum("SynthArpDrum");
    sd.setOsc1Type("SAW");
    sd.setArp(ArpModel.defaultConfig().toBuilder().active(true).syncLevel(16).build());
    kitTrack.addDrum(sd);

    ClipModel clip = new ClipModel("Clip", 1, 16); // parent clip = 384 ticks FORWARD
    clip.setActiveInSession(true);
    NoteRowModel row0 = clip.getOrCreateRow(0);
    row0.setPitch(0);
    row0.setLoopLengthIfIndependent(72); // 3 sixteen-note steps independent polyrhythm
    row0.setSequenceDirectionMode(SequenceDirection.REVERSE);
    row0.getNotes().add(new NoteModel(0, 72, 1.0f, 1.0f, 0));
    kitTrack.addClip(clip);
    project.addTrack(kitTrack);

    FirmwareFactory.createSong(project);
    FirmwareKit kit = (FirmwareKit) clip.getSound();
    List<String> observedRowArpTicks = new ArrayList<>();
    Sound spyDrumSound =
        new Sound() {
          @Override
          public int doTickForwardForArp(int currentPos, boolean reversed) {
            observedRowArpTicks.add(currentPos + ":" + reversed);
            return super.doTickForwardForArp(currentPos, reversed);
          }
        };
    spyDrumSound.arpSettings.mode = Arpeggiator.ArpMode.ARP;
    spyDrumSound.arpSettings.syncLevel = Arpeggiator.SyncLevel.SYNC_LEVEL_16TH;
    kit.drumSounds.get(0).fw2Sound.arpSettings.mode = Arpeggiator.ArpMode.ARP;
    // Install spy on slot 1 (independent row 1 with length 48 & REVERSE) while parent clip is
    // FORWARD length 384 so observed pos & reversed boolean are unmistakably independent.
    SoundDrum sd1 = new SoundDrum("SpyDrum");
    sd1.setOsc1Type("SAW");
    kitTrack.addDrum(sd1);
    NoteRowModel row1 = clip.getOrCreateRow(1);
    row1.setPitch(1);
    row1.setLoopLengthIfIndependent(48);
    row1.setSequenceDirectionMode(SequenceDirection.REVERSE);
    row1.getNotes().add(new NoteModel(0, 24, 1.0f, 1.0f, 0));
    kit.drumSounds.set(1, new FirmwareSound(spyDrumSound));

    PlaybackHandler ph = new PlaybackHandler();
    ph.setProject(project);
    ph.start();
    ph.advanceTicks(96);
    ph.stop();

    assertFalse(observedRowArpTicks.isEmpty());
    assertTrue(
        observedRowArpTicks.stream().allMatch(s -> s.endsWith(":true")),
        "REVERSE independent drum row must receive reversed=true while parent clip is FORWARD: "
            + observedRowArpTicks);
    assertTrue(
        observedRowArpTicks.stream()
            .mapToInt(s -> Integer.parseInt(s.substring(0, s.indexOf(':'))))
            .allMatch(pos -> pos >= 0 && pos <= 48),
        "48-tick independent drum row must wrap within [0..48] while parent clip advances to 96: "
            + observedRowArpTicks);
    assertFalse(kit.arpeggiator.playbackClockActive);
    assertFalse(spyDrumSound.arpeggiator.playbackClockActive);
  }
}
