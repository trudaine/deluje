package org.deluge.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.deluge.model.ClipModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.NoteModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SoundDrum;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportHelperXmlSerializerSaveNameParityTest {

  @Test
  void xmlSerializerWriteAttributeHexBytesOmits0xPrefixAndBoundsLength() throws Exception {
    StringWriter sw = new StringWriter();
    XMLSerializer xml = new XMLSerializer(sw);
    xml.writeOpeningTagBeginning("sound");
    xml.writeAttributeHexBytes("dx7patch", new byte[] {0x01, 0x2A, (byte) 0xFF}, 5, false);
    xml.closeTag();
    xml.flush();

    String out = sw.toString();
    assertTrue(
        out.contains("dx7patch=\"012AFF\""),
        "writeAttributeHexBytes must not prepend 0x (C Serializer.cpp:108), got: " + out);
  }

  @Test
  void saveNameSuggesterSupportsShortSlotNamesAndCustomNames(@TempDir Path tempDir)
      throws Exception {
    File songsDir = tempDir.toFile();

    // 1. Short 2-digit slot name ("SONG42.xml") + existing case-insensitive "SONG42A.XML"
    File song42 = new File(songsDir, "SONG42.xml");
    Files.writeString(song42.toPath(), "<song/>");
    Files.writeString(new File(songsDir, "SONG42A.XML").toPath(), "<song/>");

    File nextSlotSub = SaveNameSuggester.suggestNextSaveFile(songsDir, song42);
    assertEquals("SONG42B.xml", nextSlotSub.getName());

    // 2. Custom song name ("MYTRACK.xml" -> "MYTRACK 2.xml" -> "MYTRACK 3.xml", C
    // default_name.cpp:170-183)
    File custom = new File(songsDir, "MYTRACK.xml");
    Files.writeString(custom.toPath(), "<song/>");
    File custom2 = SaveNameSuggester.suggestNextSaveFile(songsDir, custom);
    assertEquals("MYTRACK 2.xml", custom2.getName());

    Files.writeString(custom2.toPath(), "<song/>");
    File custom3 = SaveNameSuggester.suggestNextSaveFile(songsDir, custom);
    assertEquals("MYTRACK 3.xml", custom3.getName());

    // 3. Custom name with underscore delimiter ("TRACK_1.xml" -> "TRACK_2.xml")
    File underscored = new File(songsDir, "TRACK_1.xml");
    Files.writeString(underscored.toPath(), "<song/>");
    File underscored2 = SaveNameSuggester.suggestNextSaveFile(songsDir, underscored);
    assertEquals("TRACK_2.xml", underscored2.getName());
  }

  @Test
  void exportMidiWritesTempoMetaEventPreservesOffGridNotesAndHandlesUtf8TrackNames(
      @TempDir Path tempDir) throws Exception {
    ProjectModel project = new ProjectModel();
    project.setBpm(140.0f);

    // Multi-byte UTF-8 track name ("Café — Lead")
    SynthTrackModel track = new SynthTrackModel("Café — Lead");
    ClipModel clip = new ClipModel("Clip 1", 1, 16);
    clip.setRowYNote(0, 64);
    // Off-grid note at tick 13, length 55 ticks
    clip.setRawNoteEvents(0, List.of(new NoteModel(13, 55, 0.8f, 1.0f, 0)));
    track.addClip(clip);
    project.addTrack(track);

    File midiFile = tempDir.resolve("utf8_offgrid.mid").toFile();
    ExportHelper.exportMidi(project, midiFile);

    Sequence seq = MidiSystem.getSequence(midiFile);
    assertEquals(96, seq.getResolution());

    Track midiTrack = seq.getTracks()[0];
    int foundTempoMicros = -1;
    String foundTrackName = null;
    long noteOnTick = -1;
    long noteOffTick = -1;
    int notePitch = -1;

    for (int i = 0; i < midiTrack.size(); i++) {
      MidiEvent ev = midiTrack.get(i);
      MidiMessage msg = ev.getMessage();
      if (msg instanceof MetaMessage mm) {
        if (mm.getType() == 0x51 && mm.getData().length >= 3) {
          byte[] d = mm.getData();
          foundTempoMicros = ((d[0] & 0xFF) << 16) | ((d[1] & 0xFF) << 8) | (d[2] & 0xFF);
        } else if (mm.getType() == 0x03) {
          foundTrackName = new String(mm.getData(), StandardCharsets.UTF_8);
        }
      } else if (msg instanceof ShortMessage sm) {
        if (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0) {
          noteOnTick = ev.getTick();
          notePitch = sm.getData1();
        } else if (sm.getCommand() == ShortMessage.NOTE_OFF
            || (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() == 0)) {
          noteOffTick = ev.getTick();
        }
      }
    }

    assertEquals(
        140.0f, 60_000_000.0f / foundTempoMicros, 0.05f, "Tempo meta event must be 140 BPM");
    assertEquals("Café — Lead", foundTrackName, "UTF-8 track name must not be truncated");
    assertEquals(64, notePitch);
    assertEquals(13L, noteOnTick, "Off-grid Note-On tick must be preserved");
    assertEquals(68L, noteOffTick, "Off-grid Note-Off tick (13 + 55) must be preserved");
  }

  @Test
  void exportStemsRendersKitTrackAudioIntoStemWav(@TempDir Path tempDir) throws Exception {
    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);

    KitTrackModel kit = new KitTrackModel("TestKit");
    SoundDrum drum = new SoundDrum("SynthHit");
    drum.setOsc1Type("SAW");
    drum.setOscAVolume(1.0f);
    drum.setVolume(1.0f);
    kit.addDrum(drum);

    ClipModel clip = new ClipModel("KitClip", 1, 16);
    clip.setIsKit(true);
    clip.getKitParams().put("volume", 1.0f);
    clip.setStep(0, 0, StepData.of(true, 1.0f, 4.0f, 1.0f, 0));
    kit.addClip(clip);
    project.addTrack(kit);

    File stemsDir = tempDir.resolve("stems").toFile();
    stemsDir.mkdirs();

    ExportHelper.exportStems(project, stemsDir, 0.25, null);

    File kitStem = new File(stemsDir, "Track_1_TestKit_stem.wav");
    assertTrue(kitStem.exists(), "Kit track stem WAV must be created");

    int maxAbsSample = 0;
    try (AudioInputStream ais = AudioSystem.getAudioInputStream(kitStem)) {
      byte[] bytes = ais.readAllBytes();
      for (int i = 0; i + 1 < bytes.length; i += 2) {
        short s = (short) ((bytes[i] & 0xFF) | (bytes[i + 1] << 8));
        int abs = Math.abs((int) s);
        if (abs > maxAbsSample) {
          maxAbsSample = abs;
        }
      }
    }
    assertTrue(
        maxAbsSample > 100,
        "Kit track stem must contain non-silent audio (GlobalEffectable FirmwareKit), maxAbs="
            + maxAbsSample);
  }
}
