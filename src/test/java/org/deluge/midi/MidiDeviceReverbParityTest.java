package org.deluge.midi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.deluge.engine.FirmwareMidiInstrument;
import org.deluge.firmware2.Reverb;
import org.deluge.shadow.midi.MidiMsg;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests C++ Deluge firmware parity for {@link MidiDeviceDefinitionLoader}, {@link
 * FirmwareMidiInstrument}, and {@link Reverb.Container}.
 */
class MidiDeviceReverbParityTest {

  @TempDir Path tempDir;

  @Test
  void loadParsesFirmwareMidiDeviceWithBareNumericCcLabels() throws Exception {
    String firmwareXml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<midiDevice>\n"
            + "\t<definitionFile name=\"MIDI_DEVICES/DEFINITION/MINILOGUE.XML\" />\n"
            + "\t<ccLabels\n"
            + "\t\t0=\"Bank\"\n"
            + "\t\t1=\"ShouldSkipModWheel\"\n"
            + "\t\t2=\"Breath\"\n"
            + "\t\t74=\"Cutoff &amp; Res\"\n"
            + "\t\t119=\"LastRealCC\"\n"
            + "\t\t120=\"OutOfRange\" />\n"
            + "\t<hideUnlabeledCC value=\"1\" />\n"
            + "</midiDevice>\n";

    File xmlFile = tempDir.resolve("FALLBACK_NAME.XML").toFile();
    Files.writeString(xmlFile.toPath(), firmwareXml, StandardCharsets.UTF_8);

    MidiDeviceDefinition def = MidiDeviceDefinitionLoader.load(xmlFile);

    assertEquals("MINILOGUE", def.getId());
    assertEquals("MINILOGUE", def.getName());
    assertNotNull(def.findMapping(0));
    assertEquals("Bank", def.findMapping(0).displayName());
    assertNull(
        def.findMapping(1), "CC 1 (CC_EXTERNAL_MOD_WHEEL) is skipped in firmware <ccLabels>");
    assertNotNull(def.findMapping(2));
    assertEquals("Breath", def.findMapping(2).displayName());
    assertNotNull(def.findMapping(74));
    assertEquals("Cutoff & Res", def.findMapping(74).displayName());
    assertNotNull(def.findMapping(119));
    assertEquals("LastRealCC", def.findMapping(119).displayName());
    assertNull(def.findMapping(120), "CC >= 120 must be ignored in <ccLabels>");
  }

  @Test
  void saveFirmwareFormatWritesValidFirmwareSchemaAndRoundTrips() throws Exception {
    MidiDeviceDefinition def = new MidiDeviceDefinition("PROPHET6", "PROPHET6");
    def.addCcMapping(new MidiDeviceDefinition.CcMapping(0, "Bank", "Bank"));
    def.addCcMapping(new MidiDeviceDefinition.CcMapping(74, "Filter Cutoff", "Filter Cutoff"));
    def.addCcMapping(new MidiDeviceDefinition.CcMapping(119, "Mod Depth", "Mod Depth"));

    File xmlFile = tempDir.resolve("PROPHET6.XML").toFile();
    MidiDeviceDefinitionLoader.saveFirmwareFormat(def, xmlFile, true);

    String written = Files.readString(xmlFile.toPath(), StandardCharsets.UTF_8);
    assertTrue(written.contains("<midiDevice>"));
    assertTrue(written.contains("<ccLabels"));
    assertTrue(written.contains("\t\t0=\"Bank\""));
    assertFalse(written.contains("\t\t1="), "CC 1 must not be written to <ccLabels>");
    assertTrue(written.contains("\t\t74=\"Filter Cutoff\""));
    assertTrue(written.contains("<hideUnlabeledCC"));
    assertTrue(written.contains("value=\"1\""));

    MidiDeviceDefinition loaded = MidiDeviceDefinitionLoader.load(xmlFile);
    assertEquals("PROPHET6", loaded.getId());
    assertEquals("Bank", loaded.findMapping(0).displayName());
    assertEquals("Filter Cutoff", loaded.findMapping(74).displayName());
    assertEquals("Mod Depth", loaded.findMapping(119).displayName());
  }

  @Test
  void loadAndSavePreserveLegacyDesktopMidiDeviceDefinitionSchema() throws Exception {
    MidiDeviceDefinition def = new MidiDeviceDefinition("ob6", "OB-6");
    def.setManufacturer("Sequential");
    def.setDescription("6-voice poly");
    def.addCcMapping(new MidiDeviceDefinition.CcMapping(43, "filterFreq", "Filter Freq"));

    File xmlFile = tempDir.resolve("ob6.xml").toFile();
    MidiDeviceDefinitionLoader.save(def, xmlFile);

    MidiDeviceDefinition loaded = MidiDeviceDefinitionLoader.load(xmlFile);
    assertEquals("ob6", loaded.getId());
    assertEquals("OB-6", loaded.getName());
    assertEquals("Sequential", loaded.getManufacturer());
    assertEquals("6-voice poly", loaded.getDescription());
    assertEquals("Filter Freq", loaded.findMapping(43).displayName());
  }

  @Test
  void firmwareMidiInstrumentRoutesMpeZonesAndExpressionCcSentinels() {
    List<MidiMsg> sent = new ArrayList<>();
    MidiTransport capture =
        new MidiTransport() {
          @Override
          public String getPortName() {
            return "test-capture";
          }

          @Override
          public boolean sendMessage(MidiMsg msg) {
            MidiMsg copy = new MidiMsg();
            copy.data1 = msg.data1;
            copy.data2 = msg.data2;
            copy.data3 = msg.data3;
            sent.add(copy);
            return true;
          }

          @Override
          public boolean isOutput() {
            return true;
          }

          @Override
          public boolean isInput() {
            return false;
          }

          @Override
          public void close() {}
        };

    MidiEngine previousEngine = MidiEngine.instance;
    MidiEngine engine = new MidiEngine();
    MidiEngine.instance = engine;
    engine.addOutputTransport(capture);
    try {
      // MPE Upper Zone -> master channel 15 (MIDI channel 16)
      FirmwareMidiInstrument upperMpe = new FirmwareMidiInstrument(0, true);
      upperMpe.setMpeZone("upper");
      assertEquals(15, upperMpe.getOutputMasterChannel());

      upperMpe.triggerNote(60, 100);
      assertEquals(1, sent.size());
      assertEquals(0x9F, sent.get(0).data1); // Note On ch 16
      assertEquals(60, sent.get(0).data2);
      assertEquals(100, sent.get(0).data3);

      // Fake CC 120 -> Pitch Bend (status 0xE0 | chan), NOT CC 120 (All Sound Off)
      sent.clear();
      upperMpe.sendCc(FirmwareMidiInstrument.CC_NUMBER_PITCH_BEND, 64);
      assertEquals(1, sent.size());
      MIDIMessage pb = MIDIMessage.fromMidiMsg(sent.get(0));
      assertTrue(pb.isPitchBend());
      assertEquals(15, pb.channel());
      assertEquals(8192, pb.pitchBendValue());

      // Fake CC 121 -> Channel Aftertouch (status 0xD0 | chan), NOT CC 121 (Reset All Controllers)
      sent.clear();
      upperMpe.sendCc(FirmwareMidiInstrument.CC_NUMBER_AFTERTOUCH, 90);
      assertEquals(1, sent.size());
      MIDIMessage at = MIDIMessage.fromMidiMsg(sent.get(0));
      assertEquals(0x0D, at.statusType());
      assertEquals(15, at.channel());
      assertEquals(90, at.aftertouchValue());

      // Fake CC 122 -> CC 1 (Mod Wheel), NOT CC 122 (Local Control)
      sent.clear();
      upperMpe.sendCc(FirmwareMidiInstrument.CC_NUMBER_Y_AXIS, 110);
      assertEquals(1, sent.size());
      MIDIMessage mw = MIDIMessage.fromMidiMsg(sent.get(0));
      assertTrue(mw.isCC());
      assertEquals(15, mw.channel());
      assertEquals(1, mw.data1());
      assertEquals(110, mw.data2());

      // Fake CC 123 (CC_NUMBER_NONE) -> nothing sent, NOT CC 123 (All Notes Off)
      sent.clear();
      upperMpe.sendCc(FirmwareMidiInstrument.CC_NUMBER_NONE, 64);
      assertTrue(sent.isEmpty());

      // MPE Lower Zone -> master channel 0 (MIDI channel 1)
      FirmwareMidiInstrument lowerMpe = new FirmwareMidiInstrument(0, true);
      lowerMpe.setMpeZone("lower");
      assertEquals(0, lowerMpe.getOutputMasterChannel());
      lowerMpe.sendCc(74, 55);
      assertEquals(1, sent.size());
      assertEquals(0xB0, sent.get(0).data1);
      assertEquals(74, sent.get(0).data2);
      assertEquals(55, sent.get(0).data3);
    } finally {
      engine.removeOutputTransport(capture);
      MidiEngine.instance = previousEngine;
    }
  }

  @Test
  void reverbContainerInitializesFirmwareDefaultsAndPreservesPanAcrossModelSwitches() {
    Reverb.Container reverb = new Reverb.Container();

    // C reverb.hpp:21-27, tuning.h:36-40 — defaults match Freeverb constructor
    assertEquals(0.5f, reverb.getRoomSize(), 1e-6f);
    assertEquals(0.5f, reverb.getDamping(), 1e-6f);
    assertEquals(1.0f, reverb.getWidth(), 1e-6f);
    assertEquals(0.0f, reverb.getHPF(), 1e-6f);
    assertEquals(1.0f, reverb.getLPF(), 1e-6f);

    // Setting HPF or switching model on a fresh container must NOT clobber roomSize/damping/width
    reverb.setHPF(0.25f);
    assertEquals(0.5f, reverb.getRoomSize(), 1e-6f);
    assertEquals(0.5f, reverb.getDamping(), 1e-6f);
    assertEquals(1.0f, reverb.getWidth(), 1e-6f);

    // Set hard-left pan while on FREEVERB (when mutableModel is still null), then switch to MUTABLE
    reverb.setPanLevels(0x40000000, 0);
    reverb.setModel(Reverb.Model.MUTABLE);

    int numSamples = 4096;
    int[] input = new int[numSamples];
    input[0] = 0x20000000;
    for (int i = 1; i < 512; i++) {
      input[i] = (int) (0x10000000 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
    }
    int[][] outputLR = new int[numSamples][2];
    reverb.process(input, outputLR);

    long energyL = 0;
    long energyR = 0;
    for (int i = 0; i < numSamples; i++) {
      energyL += Math.abs((long) outputLR[i][0]);
      energyR += Math.abs((long) outputLR[i][1]);
    }
    assertTrue(energyL > 0, "Left channel must render reverb tail after switching to MUTABLE");
    assertEquals(
        0L,
        energyR,
        "Right channel must stay silent when panRight=0 was set before switching to MUTABLE");
  }
}
