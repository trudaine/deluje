package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.deluge.BridgeContract;
import org.deluge.model.EnvelopeModel;
import org.deluge.model.NoteModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NoteDataHexExporterParityTest {

  @Test
  void encodeRowAndDecodeRowRoundTripMultipleNotesAndPlayConditions() {
    List<StepData> row = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      row.add(StepData.empty());
    }
    row.set(0, new StepData(true, 0.5f, 1.0f, 0.75f, 60, 0, 0.0f, 0.0f, 0, 0));
    row.set(4, new StepData(true, 1.0f, 0.5f, 1.0f, 60, 0, 1.0f, 0.0f, 0, 2)); // FILL
    row.set(8, new StepData(true, 0.25f, 1.0f, 1.0f, 60, 0, 0.0f, 0.0f, 0, 1)); // NOT_FILL

    String hex = DelugeNoteDataMapper.encodeRow(row);
    // 3 notes * 22 hex chars + "0x" = 68 chars
    assertEquals(2 + 3 * DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_LIFT, hex.length());

    List<StepData> decoded = DelugeNoteDataMapper.decodeRow(hex, 16);
    assertTrue(decoded.get(0).active(), "Step 0 must remain active");
    assertTrue(decoded.get(4).active(), "Step 4 must not be shifted/dropped after step 0");
    assertTrue(decoded.get(8).active(), "Step 8 must not be shifted/dropped after step 4");
    assertFalse(decoded.get(1).active());

    assertEquals(Math.round(0.5f * 127) / 127.0f, decoded.get(0).velocity(), 0.01f);
    assertEquals(0.75f, decoded.get(0).probability(), 0.01f);
    assertEquals(0, decoded.get(0).fillMode());

    assertEquals(1.0f, decoded.get(4).velocity(), 0.01f);
    assertEquals(0.5f, decoded.get(4).gate(), 0.01f);
    assertEquals(2, decoded.get(4).fillMode(), "FILL must roundtrip via 0x00 probability byte");

    assertEquals(Math.round(0.25f * 127) / 127.0f, decoded.get(8).velocity(), 0.01f);
    assertEquals(1, decoded.get(8).fillMode(), "NOT_FILL must roundtrip via 0x80 probability byte");
  }

  @Test
  void decodeRowInfers22CharStrideAtLcmLength220() {
    List<StepData> row = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      row.add(StepData.empty());
    }
    // 10 active notes * 22 chars = 220 hex chars (divisible by both 20 and 22)
    for (int i = 0; i < 10; i++) {
      row.set(i, StepData.of(true, 0.8f, 1.0f, 1.0f, 60));
    }

    String hex = DelugeNoteDataMapper.encodeRow(row);
    assertEquals(2 + 220, hex.length());
    assertEquals(22, DelugeNoteDataMapper.inferHexCharsPerNote(hex));

    List<StepData> decoded = DelugeNoteDataMapper.decodeRow(hex, 16);
    for (int i = 0; i < 10; i++) {
      assertTrue(decoded.get(i).active(), "Step " + i + " must remain active at 220-char LCM");
    }
    for (int i = 10; i < 16; i++) {
      assertFalse(decoded.get(i).active());
    }
  }

  @Test
  void decodeRowPreserves20CharVelocityAndClampsInvalidLengthAndVelocity() {
    // 20-char noteData: pos=0x00000018 (step 2), len=0x00000000 (clamps to 1), vel=0x00 (clamps to
    // 64), prob=0x14 (100%)
    String hex = "0x00000018000000000014";
    List<NoteModel> raw = DelugeNoteDataMapper.decodeRawNotes(hex, 20);
    assertEquals(1, raw.size());
    assertEquals(1, raw.get(0).getLength(), "Zero length must clamp to 1 (note_row.cpp:3485)");
    assertEquals(
        64,
        raw.get(0).getVelocityByte(),
        "Zero velocity must clamp to 64 (note_row.cpp:3491-3493)");

    // Valid 20-char noteData with vel=0x20 (32)
    String hexVel32 = "0x0000000C0000000C2014";
    List<StepData> row = DelugeNoteDataMapper.decodeRow(hexVel32, 16);
    assertTrue(row.get(1).active());
    assertEquals(32 / 127.0f, row.get(1).velocity(), 0.001f);
  }

  @Test
  void delugeHexMapperReadsFirstTokenWhenAutomationNodesAreAppended() {
    // C auto_param.cpp:2008-2029 — "0x" + 8-char currentValue + 16-char automation node(s)
    String automated = "0x400000001000000080000018";
    assertEquals(0x40000000, DelugeHexMapper.hexToQ31(automated));
    assertEquals(0.5f, DelugeHexMapper.hexToFloat(automated), 0.0001f);
    assertEquals(0.75f, DelugeHexMapper.hexToUnipolarFloatUnified(automated), 0.0001f);
  }

  @Test
  void fieldBindingReadsAttributeStyleDefaultParams() throws Exception {
    String xml =
        """
        <sound name="AttrTest" mode="subtractive" polyphonic="mono">
          <osc1 type="analogSaw"/>
          <defaultParams lpfFrequency="0x00000000" lpfResonance="0x40000000" oscAVolume="0x7FFFFFFF"/>
        </sound>
        """;
    SynthTrackModel synth =
        InstrumentXmlParser.parseSynth(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "AttrTest");

    // 0x00000000 -> norm 0.0 -> 20 * 1000^0.5 = 632.4555 Hz
    assertEquals(632.4555f, synth.getLpfFreq(), 1.0f);
    assertEquals(0.75f, synth.getLpfRes(), 0.001f);
    assertEquals(1.0f, synth.getOscAVolume(), 0.001f);
  }

  @Test
  void delugeXmlExporterSavesOscillatorsFilterEnvelopesAndModes(@TempDir File tempDir)
      throws Exception {
    SynthTrackModel synth = new SynthTrackModel("EXPORT_TEST");
    synth.setOsc1Type("ANALOG_SAW");
    synth.setOsc2Type("SQUARE");
    synth.setPolyphony(SynthTrackModel.PolyphonyMode.MONO);
    synth.setSynthMode(2); // ringmod
    synth.setLpfFreq(1200.0f);
    synth.setLpfRes(0.65f);
    synth.setHpfFreq(120.0f);
    synth.setHpfRes(0.25f);
    synth.setOscAVolume(0.9f);
    synth.setOscBVolume(0.4f);
    synth.setVolume(0.8f);
    synth.setPan(-0.5f);
    synth.setEnv(0, new EnvelopeModel(0.02f, 0.3f, 0.8f, 0.4f, "NONE", 0.0f));
    synth.setEnv(2, new EnvelopeModel(0.05f, 0.5f, 0.5f, 0.9f, "NONE", 0.0f));
    synth.setEnv(3, new EnvelopeModel(0.10f, 0.6f, 0.3f, 1.2f, "NONE", 0.0f));

    File outFile = new File(tempDir, "EXPORT_TEST.XML");
    DelugeXmlExporter.saveSynthPreset(synth, new BridgeContract(), 0, outFile);

    String rawXml = Files.readString(outFile.toPath());
    assertTrue(rawXml.contains("type=\"analogSaw\""), "Osc1 type must use C firmware casing");
    assertTrue(rawXml.contains("type=\"square\""), "Osc2 type must be exported in C casing");
    assertTrue(rawXml.contains("polyphonic=\"mono\""));
    assertTrue(rawXml.contains("mode=\"ringmod\""));
    assertFalse(rawXml.contains("<lpf "), "Non-firmware <lpf> tag must not be written");
    assertTrue(rawXml.contains("<envelope3>"));
    assertTrue(rawXml.contains("<envelope4>"));

    SynthTrackModel parsed = DelugeXmlParser.parseSynth(outFile);
    assertEquals("ANALOGSAW", parsed.getOsc1Type().replace("_", ""));
    assertEquals("SQUARE", parsed.getOsc2Type());
    assertEquals(SynthTrackModel.PolyphonyMode.MONO, parsed.getPolyphony());
    assertEquals(2, parsed.getSynthMode());
    assertEquals(1200.0f, parsed.getLpfFreq(), 5.0f);
    assertEquals(0.65f, parsed.getLpfRes(), 0.01f);
    assertEquals(120.0f, parsed.getHpfFreq(), 2.0f);
    assertEquals(0.25f, parsed.getHpfRes(), 0.01f);
    assertEquals(0.9f, parsed.getOscAVolume(), 0.01f);
    assertEquals(0.4f, parsed.getOscBVolume(), 0.01f);
    assertEquals(0.8f, parsed.getVolume(), 0.01f);
    assertEquals(-0.5f, parsed.getPan(), 0.01f);
    assertEquals(0.05f, parsed.getEnv(2).attack(), 0.005f);
    assertEquals(0.5f, parsed.getEnv(2).sustain(), 0.01f);
    assertEquals(1.2f, parsed.getEnv(3).release(), 0.05f);
  }
}
