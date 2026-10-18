package org.deluge.midi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import org.deluge.engine.DelugeatorRandomizer;
import org.deluge.engine.DelugeatorRandomizer.RandomizerSettings;
import org.deluge.midi.MidiToProjectCompiler.TrackImportConfig;
import org.deluge.model.ClipModel;
import org.deluge.model.NoteModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MidiCompilerFollowCableParityTest {

  @Test
  void midiCompilerPreservesOffGridNotesWithoutDuplicationAndKeepsOnGridLengths(
      @TempDir Path tempDir) throws Exception {
    Sequence sequence = new Sequence(Sequence.PPQ, 96);
    Track track = sequence.createTrack();

    // Off-grid note: D4 (62) at tick 10, duration 72 ticks (3/4 beat = 3 steps)
    track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 62, 100), 10));
    track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 62, 0), 82));

    // On-grid note: D4 (62) at tick 96 (step 4), duration 192 ticks (2 beats = 8 steps)
    track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 62, 90), 96));
    track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 62, 0), 288));

    File midiFile = tempDir.resolve("offgrid_and_long.mid").toFile();
    MidiSystem.write(sequence, 1, midiFile);

    List<TrackImportConfig> configs = MidiToProjectCompiler.parseMidiMetadata(midiFile);
    ProjectModel project = MidiToProjectCompiler.compileMidi(midiFile, configs);
    ClipModel clip = project.getTracks().get(0).getClips().get(0);

    List<NoteModel> rawNotes = clip.getRawNoteEvents(0);
    assertEquals(2, rawNotes.size(), "Off-grid note must not be duplicated by setStep");

    NoteModel first = rawNotes.get(0);
    assertEquals(10, first.getPos(), "Off-grid note tick position must stay unquantized");
    assertEquals(72, first.getLength(), "Off-grid note length must stay 72 ticks");

    NoteModel second = rawNotes.get(1);
    assertEquals(96, second.getPos());
    assertEquals(192, second.getLength(), "On-grid note length must not be clobbered to 24 ticks");

    StepData step0 = clip.getStep(0, 0);
    assertTrue(step0.active());
    assertEquals(62, step0.pitch());
    assertEquals(3.0f, step0.gate(), 1e-4f);

    StepData step4 = clip.getStep(0, 4);
    assertTrue(step4.active());
    assertEquals(62, step4.pitch());
    assertEquals(8.0f, step4.gate(), 1e-4f);
  }

  @Test
  void midiCompilerIsolatesChannelsOnSamePitchAndGuardsZeroTempo(@TempDir Path tempDir)
      throws Exception {
    Sequence sequence = new Sequence(Sequence.PPQ, 96);
    Track track = sequence.createTrack();

    // Corrupt tempo meta event with tempo = 0 microseconds/quarter
    MetaMessage zeroTempo = new MetaMessage();
    zeroTempo.setMessage(0x51, new byte[] {0, 0, 0}, 3);
    track.add(new MidiEvent(zeroTempo, 0));

    // Format 0 multi-channel overlap on C4 (60): Ch 0 [0..96], Ch 1 [48..144]
    track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100), 0));
    track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 1, 60, 80), 48));
    track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 60, 0), 96));
    track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 1, 60, 0), 144));

    File midiFile = tempDir.resolve("multich_zerotempo.mid").toFile();
    MidiSystem.write(sequence, 0, midiFile);

    List<TrackImportConfig> configs = MidiToProjectCompiler.parseMidiMetadata(midiFile);
    ProjectModel project = MidiToProjectCompiler.compileMidi(midiFile, configs);

    assertEquals(
        120.0f, project.getBpm(), 1e-4f, "Zero tempo meta event must fall back to 120 BPM");

    ClipModel clip = project.getTracks().get(0).getClips().get(0);
    List<NoteModel> rawNotes = clip.getRawNoteEvents(0);
    assertEquals(2, rawNotes.size());
    assertEquals(0, rawNotes.get(0).getPos());
    assertEquals(96, rawNotes.get(0).getLength(), "Ch 1 Note-On must not truncate Ch 0 note");
    assertEquals(48, rawNotes.get(1).getPos());
    assertEquals(96, rawNotes.get(1).getLength());
  }

  @Test
  void midiFollowDenormalizesBipolarPanForTakeover() {
    MidiFollow follow = new MidiFollow();
    MidiTakeover takeover = new MidiTakeover();
    takeover.setMode(MidiTakeover.Mode.PICKUP);
    follow.setTakeover(takeover);

    MidiFollow.ParamMapping panMapping = follow.getMapping(10);
    assertNotNull(panMapping);
    assertEquals(0, panMapping.denormalizeToCc(-1.0f));
    assertEquals(64, panMapping.denormalizeToCc(0.0f));
    assertEquals(127, panMapping.denormalizeToCc(1.0f));

    // Current pan is centered (0.0f in [-1, +1] -> virtual CC 64)
    follow.setOnGetParam(name -> "g_sp_pan".equals(name) ? 0.0f : null);
    AtomicReference<Float> lastSetValue = new AtomicReference<>();
    follow.setOnSetParam((name, val) -> lastSetValue.set(val));

    // First CC at 60 (< 64) initializes pickup state and is blocked because 60 != 64
    follow.handleCC(MIDIMessage.cc(0, 10, 60));
    assertNull(lastSetValue.get(), "CC 60 below center 64 should be blocked by pickup");

    // Second CC at 65 crosses virtual 64 -> pickup engages
    follow.handleCC(MIDIMessage.cc(0, 10, 65));
    assertNotNull(lastSetValue.get(), "Crossing virtual 64 should latch pickup");
    assertTrue(lastSetValue.get() > 0.0f);
  }

  @Test
  void midiCableMovesOpposingMpeZoneOutOfWayAndCountsMembers() {
    MidiCable cable = new MidiCable("Port A");

    // Lower zone 1..7 (master 0)
    cable.setLowerZone(1, 7, 0);
    assertEquals(7, cable.getMemberChannelCount());

    // Setting overlapping upper zone 5..14 (master 15) shrinks lower zone to 1..4 (C
    // midi_device.cpp:277-286)
    cable.setUpperZone(5, 14, 15);
    assertNotNull(cable.getLowerZone());
    assertEquals(4, cable.getLowerZone().lastMemberChannel);
    assertEquals(1, cable.channelToZone(4));
    assertEquals(2, cable.channelToZone(5));
    assertEquals(14, cable.getMemberChannelCount());

    // Expanding lower zone to 1..14 clears upper zone entirely (C midi_device.cpp:266-275)
    cable.setLowerZone(1, 14, 0);
    assertNull(cable.getUpperZone(), "Upper zone must be cleared when lower zone uses 1..14");
    assertEquals(14, cable.getMemberChannelCount());

    // Master channel inside range is excluded from member count
    MidiCable.MpeZone zoneWithMasterInside = new MidiCable.MpeZone(0, 7, 0);
    assertFalse(zoneWithMasterInside.containsChannel(0));
    assertEquals(7, zoneWithMasterInside.getMemberChannelCount());
  }

  @Test
  void delugeatorRandomizerKeepsCompressorSyncLevelWithinFirmwareBounds() {
    SynthTrackModel synth = new SynthTrackModel("Init");
    ProjectModel project = new ProjectModel();
    RandomizerSettings settings = new RandomizerSettings();
    settings.compressorChecked = true;
    settings.compressorAmount = 1.0;

    for (int i = 0; i < 64; i++) {
      DelugeatorRandomizer.randomizeSynth(synth, project, settings);
      int syncLevel = synth.getCompressorSyncLevel();
      assertTrue(
          syncLevel >= 0 && syncLevel <= 9,
          "compressorSyncLevel must stay in 0..9 (C sync.h MAX_SYNC_LEVEL), got " + syncLevel);
    }
  }
}
