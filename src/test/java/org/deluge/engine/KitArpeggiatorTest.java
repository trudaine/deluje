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

  @Test
  void sequencedShortNoteOffAndStopClearKitArpWithoutRetriggeringHeldDrums() {
    ProjectModel project = new ProjectModel();
    KitTrackModel kitTrack = new KitTrackModel("KitSeq");
    SoundDrum d0 = new SoundDrum("Synth0");
    d0.setOsc1Type("SAW");
    d0.setOscAVolume(1.0f);
    SoundDrum d1 = new SoundDrum("Synth1");
    d1.setOsc1Type("SAW");
    d1.setOscAVolume(1.0f);
    kitTrack.addDrum(d0);
    kitTrack.addDrum(d1);

    ClipModel clip = new ClipModel("Clip", 2, 16); // 384 ticks
    clip.setActiveInSession(true);
    clip.setArp(ArpModel.defaultConfig().toBuilder().active(true).syncLevel(16).build());
    NoteRowModel r0 = clip.getOrCreateRow(0);
    r0.setPitch(0);
    r0.getNotes().add(new NoteModel(0, 24, 1.0f, 1.0f, 0)); // single 16th at step 0 only
    kitTrack.addClip(clip);
    project.addTrack(kitTrack);

    FirmwareFactory.createSong(project);
    FirmwareKit kit = (FirmwareKit) clip.getSound();
    PlaybackHandler ph = new PlaybackHandler();
    ph.setProject(project);
    ph.start();
    assertTrue(kit.arpeggiator.hasAnyInputNotesActive(), "step 0 note-on enters kit arp");

    ph.advanceTicks(24);
    assertFalse(
        kit.arpeggiator.hasAnyInputNotesActive(),
        "step 0 note-off at tick 24 must remove drum 0 from kit.arpeggiator.notes");
    // Drain step 0's release envelope tail, then verify rest steps 1..10 never retrigger a voice.
    int[] drainBuf = new int[256];
    int[] drainRev = new int[256];
    for (int b = 0; b < 400 && kit.drumSounds.get(0).getActiveVoiceCount() > 0; b++) {
      kit.renderInternal(drainBuf, 128, drainRev);
    }
    assertEquals(0, kit.drumSounds.get(0).getActiveVoiceCount(), "step 0 voice finished release");
    ph.advanceTicks(240);
    assertEquals(0, kit.drumSounds.get(0).getActiveVoiceCount(), "rest steps 1..10 stay silent");

    // Mid-gate transport stop releases sequenced notes from kit.arpeggiator via
    // stopAllNotesPlaying,
    // and expectNoFurtherTicks(true) flushes live held notes via kit.releaseAllNotes().
    ph.advanceTicks(126); // wrap 384-tick bar to tick 6 (mid-gate of step 0)
    assertTrue(kit.arpeggiator.hasAnyInputNotesActive(), "sequenced step active mid-gate");
    ph.stop();
    assertFalse(
        kit.arpeggiator.hasAnyInputNotesActive(),
        "stop() releases mid-gate sequenced notes from kit.arpeggiator");

    kit.triggerDrum(1, 100);
    assertTrue(kit.arpeggiator.hasAnyInputNotesActive());
    clip.expectNoFurtherTicks(true);
    assertFalse(
        kit.arpeggiator.hasAnyInputNotesActive(),
        "expectNoFurtherTicks(true) resets kit arpeggiator");

    // With kit arp OFF, releasing one simultaneous drum must NOT retrigger another still-held drum
    // via C mono CV snap-back (arpeggiator.cpp:440-456 vs kit.cpp:1369-1375).
    RecKit rec = new RecKit();
    rec.triggerDrum(0, 100);
    rec.triggerDrum(1, 110);
    rec.hits.clear();
    rec.releaseDrum(1);
    assertEquals(List.of("OFF1"), rec.hits);
  }

  @Test
  void programmaticSoundDrumAndKitDefaultsMatchHardwareAndSustainBeyondOneBlock(
      @TempDir Path tempDir) throws Exception {
    KitTrackModel kitTrack = new KitTrackModel("KitDefaults");
    SoundDrum sd = new SoundDrum("SustainingLeadDrum");
    sd.setOsc1Type("SAW");
    sd.setOscAVolume(1.0f);
    sd.setAdsr(new org.deluge.model.EnvelopeModel(0.0f, 0.5f, 1.0f, 0.0f, "NONE", 0.0f));
    sd.setArp(ArpModel.defaultConfig().toBuilder().active(true).syncLevel(0).build());
    kitTrack.addDrum(sd);
    ClipModel clip = new ClipModel("Clip", 1, 16);
    kitTrack.addClip(clip);
    ProjectModel project = new ProjectModel();
    project.addTrack(kitTrack);

    FirmwareKit fwKit = FirmwareFactory.createKitSound(kitTrack, project);
    assertEquals(
        Integer.MAX_VALUE,
        fwKit.arpGateKnob,
        "Default affect-entire kit gate is INT_MAX (50/50 = 100%, global_effectable.cpp:69)");
    FirmwareSound drum = fwKit.drumSounds.get(0);
    assertEquals(
        0,
        drum.paramNeutralValues[Param.UNPATCHED_ARP_GATE],
        "Default SoundDrum arp gate Q31 is 0 (25/50 = 50%, mod_controllable_audio.cpp:113)");
    assertEquals(
        0,
        drum.paramNeutralValues[Param.GLOBAL_ARP_RATE],
        "Default SoundDrum arp rate Q31 is 0 (25/50, sound.cpp:155)");

    // Trigger unsynced per-drum arp and verify gate stays open across multiple 64-sample blocks.
    drum.syncParamsToFw2();
    drum.triggerNote(60, 100);
    int[] buf = new int[128];
    int[] rev = new int[128];
    drum.renderOutputSumming(buf, 64, rev);
    drum.renderOutputSumming(buf, 64, rev);
    assertTrue(
        drum.fw2Sound.arpeggiator.hasAnyInputNotesActive() && drum.getActiveVoiceCount() > 0,
        "50% default per-drum arp gate must hold voice open beyond block 1");

    // Standalone Kit XML round-trip preserves 0x7FFFFFFF kit gate & 0x00000000 drum gate/rate +
    // unpatched arp params.
    Path kitFile = tempDir.resolve("STANDALONE_KIT.XML");
    org.deluge.project.KitSynthSerializer.saveKit(kitTrack, kitFile.toFile());
    String kitXml = Files.readString(kitFile);
    assertTrue(kitXml.contains("<arpeggiatorGate>0x7FFFFFFF</arpeggiatorGate>"));
    assertTrue(kitXml.contains("<arpeggiatorGate>0x00000000</arpeggiatorGate>"));
    assertTrue(kitXml.contains("<noteProbability>0x7FFFFFFF</noteProbability>"));
    KitTrackModel parsedStandalone = org.deluge.xml.KitXmlParser.parseKit(kitFile.toFile());
    assertEquals(
        0.5f, ((SoundDrum) parsedStandalone.getDrums().get(0)).getArpeggiatorGate(), 1e-4f);
  }

  @Test
  void sessionClipSwitchReSyncsLiveKitArpAndProgrammaticArpModelRoundTrips(@TempDir Path tempDir)
      throws Exception {
    ProjectModel project = new ProjectModel();
    KitTrackModel kitTrack = new KitTrackModel("KitMultiClip");
    SoundDrum d0 = new SoundDrum("Synth0");
    d0.setOsc1Type("SAW");
    d0.setOscAVolume(1.0f);
    kitTrack.addDrum(d0);

    ClipModel clip0 = new ClipModel("Clip0", 1, 16);
    clip0.setActiveInSession(true);
    clip0.setArp(ArpModel.defaultConfig().toBuilder().active(false).build());

    ClipModel clip1 = new ClipModel("Clip1", 1, 16);
    clip1.setActiveInSession(false);
    clip1.setArp(
        ArpModel.defaultConfig().toBuilder()
            .active(true)
            .octaves(2)
            .syncLevel(16)
            .gate(0.25f)
            .rate(0.75f)
            .seqLength(6)
            .reverseProbability(0.5f)
            .velSpread(0.4f)
            .build());
    kitTrack.addClip(clip0);
    kitTrack.addClip(clip1);
    kitTrack.setActiveClipIndex(0);
    project.addTrack(kitTrack);

    FirmwareFactory.createSong(project);
    FirmwareKit liveKit = (FirmwareKit) clip0.getSound();
    assertEquals(Arpeggiator.ArpMode.OFF, liveKit.arpSettings.mode);

    // Switch active session clip from Clip0 (OFF) -> Clip1 (ARP): liveKit updates immediately.
    kitTrack.setActiveClipIndex(1);
    assertEquals(Arpeggiator.ArpMode.ARP, liveKit.arpSettings.mode);
    assertEquals(2, liveKit.arpSettings.numOctaves);
    assertTrue(liveKit.arpGateKnob < 0, "gate=0.25 maps to negative Q31 knob");
    assertTrue(liveKit.arpRateKnob > 0, "rate=0.75 maps to positive Q31 knob");

    // Save & reload song XML: programmatic clip1 ArpModel fields survive <kitParams> round-trip
    // and hydrate clip1.getArp() on load.
    Path saved = tempDir.resolve("MULTI_CLIP_KIT_ARP.XML");
    ProjectSerializer.save(project, saved.toFile());
    ProjectModel reloaded = SongXmlParser.parseSong(saved.toFile());
    ClipModel reloadedClip1 = reloaded.getTracks().get(0).getClips().get(1);
    assertEquals(6, reloadedClip1.getArp().seqLength());
    assertEquals(0.5f, reloadedClip1.getArp().reverseProbability(), 0.02f);
    assertEquals(0.25f, reloadedClip1.getArp().gate(), 0.02f);
    assertEquals(0.75f, reloadedClip1.getArp().rate(), 0.02f);
  }

  @Test
  void livePadArpeggiatesOnStoppedClipAndMultiPageKitSupportsMoreThanSixteenDrums() {
    ProjectModel project = new ProjectModel();
    KitTrackModel kitTrack = new KitTrackModel("BigKit20");
    for (int i = 0; i < 20; i++) {
      SoundDrum sd = new SoundDrum("Drum" + i);
      sd.setOsc1Type("SAW");
      sd.setOscAVolume(1.0f);
      kitTrack.addDrum(sd);
    }
    ClipModel stoppedClip = new ClipModel("StoppedClip", 20, 16);
    stoppedClip.setActiveInSession(false); // session-stopped clip while clock runs
    stoppedClip.setArp(ArpModel.defaultConfig().toBuilder().active(true).syncLevel(16).build());
    kitTrack.addClip(stoppedClip);
    project.addTrack(kitTrack);

    FirmwareFactory.createSong(project);
    FirmwareKit kit = (FirmwareKit) stoppedClip.getSound();
    assertEquals(20, kit.drumSounds.size(), "20-drum multi-page kit must not truncate at 16");

    PlaybackHandler ph = new PlaybackHandler();
    ph.setProject(project);
    ph.start();
    // Hold pad on drum 18 (> 16) while clip is session-inactive: must trigger on clock tick!
    kit.triggerDrum(18, 100);
    ph.advanceTicks(24);
    assertTrue(
        kit.drumSounds.get(18).getActiveVoiceCount() > 0,
        "Live held pad on stopped clip must arpeggiate via lastSwungTickActioned (session.cpp:2529)");
    ph.stop();

    // Multi-octave post-arp releaseDrum releases row + 12 (drum 14 when row 2 is sounding an octave
    // up, arpeggiator.cpp:412-430, kit.cpp:1369-1375).
    RecKit octKit = new RecKit();
    octKit.arpSettings.mode = Arpeggiator.ArpMode.ARP;
    octKit.arpSettings.octaveMode = Arpeggiator.ArpOctaveMode.DOWN;
    octKit.arpSettings.numOctaves = 2;
    octKit.arpSettings.syncLevel = Arpeggiator.SyncLevel.SYNC_LEVEL_16TH;
    octKit.arpeggiator.playbackClockActive = true;
    octKit.triggerDrum(2, 100);
    octKit.doTickForwardForKitArp(0, false); // DOWN w/ 2 octaves plays 2 + 12 = 14!
    assertEquals(List.of("ON14:100"), octKit.hits);
    octKit.hits.clear();
    octKit.releaseDrum(2);
    assertEquals(
        List.of("OFF14"), octKit.hits, "releaseDrum must release sounding post-arp drum 14");
  }
}
