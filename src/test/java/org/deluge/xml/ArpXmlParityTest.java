package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.deluge.model.ArpModel;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.project.ProjectSerializer;
import org.junit.jupiter.api.Test;

/**
 * A Sound's {@code <arpeggiator>} is read by C ArpeggiatorSettings::readCommonTagsFromFile
 * (arpeggiator.cpp:1648-1788): every key tag-or-attribute, arp on/off from {@code arpMode} (or the
 * pre-1.1.0 {@code mode}), the C mode strings, and {@code syncLevel} relative to the song's input
 * tick magnitude (song.cpp:5842-5872). Before this, the tag form used by every preset file on a
 * card was dropped: "164 Study Arp" loaded with its arp off, 1 octave and no sync.
 */
public class ArpXmlParityTest {

  private static SynthTrackModel parse(String xml) throws Exception {
    return DelugeXmlParser.parseSynth(
        new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "T");
  }

  private static ArpModel arpOf(String arpElement) throws Exception {
    return parse("<?xml version=\"1.0\"?><sound>" + arpElement + "</sound>").getArp();
  }

  @Test
  void studyArpPresetTagFormLoadsLikeTheC() throws Exception {
    // The exact element in SYNTHS/164 Study Arp.XML (firmware 2.0.0-beta, so the legacy "mode").
    ArpModel arp =
        arpOf(
            "<arpeggiator><mode>up</mode><numOctaves>2</numOctaves>"
                + "<syncLevel>7</syncLevel></arpeggiator>");
    assertTrue(arp.active(), "legacy mode=up turns the arp on (arpeggiator.cpp:1747-1760)");
    assertEquals(2, arp.octaves(), "<numOctaves> tag must be read (arpeggiator.cpp:1650)");
    // File 7 = internal 8 - magnitude, the C default: a 16th at any magnitude.
    assertEquals(16, arp.syncLevel());
    assertEquals("UP", arp.noteMode());
    assertEquals("UP", arp.octaveMode());
  }

  @Test
  void studyArpPresetFileOnDiskLoadsActive() throws Exception {
    java.io.File f = new java.io.File("src/main/resources/SYNTHS/164 Study Arp.XML");
    org.junit.jupiter.api.Assumptions.assumeTrue(f.isFile(), "preset not in this checkout");
    ArpModel arp = DelugeXmlParser.parseSynth(f).getArp();
    assertTrue(arp.active());
    assertEquals(2, arp.octaves());
    assertEquals(16, arp.syncLevel());
  }

  @Test
  void legacyModesConvertLikeOldModeToArpNoteAndOctaveMode() throws Exception {
    ArpModel down = arpOf("<arpeggiator><mode>down</mode></arpeggiator>");
    assertTrue(down.active());
    assertEquals("DOWN", down.noteMode());
    assertEquals("DOWN", down.octaveMode());

    ArpModel both = arpOf("<arpeggiator><mode>both</mode></arpeggiator>");
    assertTrue(both.active());
    assertEquals("UP", both.noteMode());
    assertEquals("ALT", both.octaveMode(), "BOTH → ArpOctaveMode::ALTERNATE");

    ArpModel random = arpOf("<arpeggiator><mode>random</mode></arpeggiator>");
    assertEquals("RAND", random.noteMode());
    assertEquals("RAND", random.octaveMode());

    assertFalse(arpOf("<arpeggiator><mode>off</mode></arpeggiator>").active());
  }

  @Test
  void arpModeDecidesOnOffAndLegacyModeIsIgnoredWhenPresent() throws Exception {
    // A 1.1.0+ file: the C ignores "mode" (version check) and arpMode is authoritative.
    assertFalse(arpOf("<arpeggiator mode=\"up\" arpMode=\"off\"/>").active());
    assertTrue(arpOf("<arpeggiator mode=\"off\" arpMode=\"arp\"/>").active());
    // This app's own "active" attribute is not a C key.
    assertFalse(arpOf("<arpeggiator active=\"1\" arpMode=\"off\"/>").active());
  }

  @Test
  void firmwareWrittenAttributesUseTheCStrings() throws Exception {
    ArpModel arp =
        arpOf(
            "<arpeggiator mode=\"arp\" syncLevel=\"6\" numOctaves=\"3\" syncType=\"1\""
                + " arpMode=\"arp\" chordType=\"2\" noteMode=\"asPlayed\" octaveMode=\"alt\""
                + " mpeVelocity=\"z\" stepRepeat=\"2\"/>");
    assertTrue(arp.active());
    assertEquals(3, arp.octaves());
    assertEquals(8, arp.syncLevel(), "file 6 → an 8th (2^(F-3)) at any magnitude");
    assertEquals(1, arp.syncType());
    assertEquals(2, arp.chordType());
    assertEquals("PLAY", arp.noteMode(), "asPlayed was upper-cased to ASPLAYED and lost");
    assertEquals("ALT", arp.octaveMode());
    assertEquals(1, arp.mpeVelocity(), "\"z\" is aftertouch");
    assertEquals(2, arp.stepRepeat());

    assertEquals("UPDN", arpOf("<arpeggiator noteMode=\"upDown\"/>").noteMode());
    assertEquals("WLK2", arpOf("<arpeggiator noteMode=\"walk2\"/>").noteMode());
    assertEquals("PATT", arpOf("<arpeggiator noteMode=\"pattern\"/>").noteMode());
    assertEquals("UPDN", arpOf("<arpeggiator octaveMode=\"upDown\"/>").octaveMode());
    assertEquals("RAND", arpOf("<arpeggiator octaveMode=\"random\"/>").octaveMode());
    assertEquals(2, arpOf("<arpeggiator mpeVelocity=\"y\"/>").mpeVelocity());
    // C :1769-1774 — an out-of-range chordType is ignored.
    assertEquals(0, arpOf("<arpeggiator chordType=\"9\"/>").chordType());
  }

  @Test
  void onlyDirectChildrenAreRead() throws Exception {
    // A deeper same-named tag is not this element's key: the C Deserializer reads one level down.
    ArpModel arp = arpOf("<arpeggiator><nested><numOctaves>4</numOctaves></nested></arpeggiator>");
    assertEquals(1, arp.octaves());
  }

  @Test
  void syncLevelCodecMatchesTheCConversions() {
    assertEquals(0, ArpXmlCodec.syncDenominatorFromFile(0, 2), "0 means not synced");
    // The note depends on the file value alone: 2^(F-3). F=7 is the C default 16th.
    for (int m = 1; m <= 3; m++) {
      assertEquals(16, ArpXmlCodec.syncDenominatorFromFile(7, m));
      assertEquals(2, ArpXmlCodec.syncDenominatorFromFile(4, m), "hardware-measured half note");
      assertEquals(7, ArpXmlCodec.syncFileValueFromDenominator(16, m));
      assertEquals(8 - m, ArpXmlCodec.internalSyncLevelFromDenominator(16, m), "C default");
    }
    // Internal levels clamp to 1..9: at magnitude 2 the fastest is a 128th.
    assertEquals(128, ArpXmlCodec.syncDenominatorFromFile(16, 2));
    assertEquals(0, ArpXmlCodec.syncFileValueFromDenominator(0, 2));
    for (int den = 2; den <= 128; den <<= 1) {
      assertEquals(
          den,
          ArpXmlCodec.syncDenominatorFromFile(ArpXmlCodec.syncFileValueFromDenominator(den, 2), 2));
    }
  }

  @Test
  void absentSyncLevelIsTheCDefaultAndExplicitZeroIsUnsynced() throws Exception {
    assertEquals(16, arpOf("<arpeggiator arpMode=\"arp\"/>").syncLevel());
    assertEquals(0, arpOf("<arpeggiator arpMode=\"arp\" syncLevel=\"0\"/>").syncLevel());
  }

  @Test
  void songSaveAndReloadKeepsTheArpAndWritesCValues() throws Exception {
    SynthTrackModel synth = new SynthTrackModel("ArpSynth");
    synth.setArp(
        ArpModel.defaultConfig().toBuilder()
            .active(true)
            .octaves(3)
            .syncLevel(32)
            .noteMode("PLAY")
            .octaveMode("ALT")
            .mpeVelocity(2)
            .build());
    ClipModel clip = new ClipModel("c", 1, 16);
    clip.setStep(0, 0, StepData.of(true, 1.0f, 16.0f, 1.0f, 60));
    synth.addClip(clip);
    ProjectModel project = new ProjectModel();
    project.addTrack(synth);

    String xml = ProjectSerializer.serializeToString(project);
    // What the hardware reads: C strings and the magnitude-relative sync level (32nd = 6 → 7).
    assertTrue(xml.contains("arpMode=\"arp\""), xml);
    assertTrue(xml.contains("numOctaves=\"3\""), "numOctaves must follow the edited octaves");
    assertTrue(xml.contains("<noteMode>asPlayed</noteMode>"));
    assertTrue(xml.contains("<octaveMode>alt</octaveMode>"));
    assertTrue(xml.contains("mpeVelocity=\"y\""));
    assertTrue(xml.contains("<syncLevel>" + ArpXmlCodec.syncFileValueFromDenominator(32, 2) + "<"));

    ProjectModel reparsed =
        DelugeXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "rt");
    ArpModel back = ((SynthTrackModel) reparsed.getTracks().get(0)).getArp();
    assertTrue(back.active());
    assertEquals(3, back.octaves());
    assertEquals(32, back.syncLevel());
    assertEquals("PLAY", back.noteMode());
    assertEquals("ALT", back.octaveMode());
    assertEquals(2, back.mpeVelocity());
  }

  @Test
  void multiBarSyncLevelsRoundTrip() {
    // At magnitude 2, file 2 is internal 1: eight quarter notes, i.e. two whole notes.
    assertEquals(-2, ArpXmlCodec.syncDenominatorFromFile(2, 2));
    assertEquals(2, ArpXmlCodec.syncFileValueFromDenominator(-2, 2));
    assertEquals(1, ArpXmlCodec.internalSyncLevelFromDenominator(-2, 2));
    assertEquals(1, ArpXmlCodec.syncDenominatorFromFile(3, 2), "file 3 is a whole note");
  }

  @Test
  void thisAppsOldExporterTokensStillLoad() throws Exception {
    // DelugeXmlExporter used to write the panel tokens verbatim. The C never writes them.
    ArpModel arp = arpOf("<arpeggiator arpMode=\"arp\" noteMode=\"PLAY\" octaveMode=\"ALT\"/>");
    assertEquals("PLAY", arp.noteMode());
    assertEquals("ALT", arp.octaveMode());
  }

  @Test
  void arpRateParamIsSavedRawAndReloads() throws Exception {
    SynthTrackModel synth = new SynthTrackModel("R");
    synth.setArpRateQ31(0x12345678);
    synth.addClip(new ClipModel("c", 1, 16));
    ProjectModel project = new ProjectModel();
    project.addTrack(synth);
    String xml = ProjectSerializer.serializeToString(project);
    assertTrue(xml.contains("arpeggiatorRate=\"0x12345678\""), "the raw GLOBAL_ARP_RATE param");
    SynthTrackModel back =
        (SynthTrackModel)
            DelugeXmlParser.parseSong(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "rt")
                .getTracks()
                .get(0);
    assertEquals(0x12345678, back.getArpRateQ31());

    java.io.File f = java.io.File.createTempFile("arprate", ".XML");
    f.deleteOnExit();
    org.deluge.project.KitSynthSerializer.saveSynth(synth, f);
    assertEquals(0x12345678, DelugeXmlParser.parseSynth(f).getArpRateQ31());
  }

  @Test
  void standardMenuConversionsMatchValueScaling() {
    assertEquals(
        Integer.MIN_VALUE, org.deluge.firmware2.Functions.computeFinalValueForStandardMenuItem(0));
    assertEquals(
        Integer.MAX_VALUE, org.deluge.firmware2.Functions.computeFinalValueForStandardMenuItem(50));
    for (int v = 0; v <= 50; v++) {
      assertEquals(
          v,
          org.deluge.firmware2.Functions.computeCurrentValueForStandardMenuItem(
              org.deluge.firmware2.Functions.computeFinalValueForStandardMenuItem(v)));
    }
  }

  @Test
  void anOldSaveOfThisAppKeepsItsDenominatorAndEditedOctaves() throws Exception {
    // The old writers: "active" attribute, syncLevel = the model's denominator, a stale numOctaves
    // beside the edited <octaves>. No arpSyncFormat marker.
    ArpModel arp =
        arpOf(
            "<arpeggiator active=\"1\" arpMode=\"arp\" numOctaves=\"1\">"
                + "<octaves>3</octaves><syncLevel>16</syncLevel></arpeggiator>");
    assertEquals(16, arp.syncLevel(), "a 16th, not the C reading of file value 16 (a 128th)");
    assertEquals(3, arp.octaves());
    // Its 0 stays "not synced" (what the hardware plays for SONG003.xml).
    assertEquals(
        0,
        arpOf("<arpeggiator active=\"0\" mode=\"up\"><syncLevel>0</syncLevel></arpeggiator>")
            .syncLevel());
    // A current save carries the marker and the C value.
    assertEquals(
        16,
        arpOf("<arpeggiator active=\"1\" arpSyncFormat=\"c\" arpMode=\"arp\" syncLevel=\"7\"/>")
            .syncLevel());
  }

  @Test
  void synthPresetExporterSavesTheRawArpRate() throws Exception {
    SynthTrackModel synth = new SynthTrackModel("E");
    synth.setArpRateQ31(-0x20000000);
    synth.setArp(ArpModel.defaultConfig().toBuilder().active(true).syncLevel(32).build());
    java.io.File f = java.io.File.createTempFile("exp", ".XML");
    f.deleteOnExit();
    DelugeXmlExporter.saveSynthPreset(synth, new org.deluge.BridgeContract(), 0, f);
    SynthTrackModel back = DelugeXmlParser.parseSynth(f);
    assertEquals(-0x20000000, back.getArpRateQ31());
    assertEquals(32, back.getArp().syncLevel());
    assertTrue(back.getArp().active());
  }

  @Test
  void gridMacroUnitMapsOntoTheBipolarKnob() {
    assertEquals(Integer.MIN_VALUE, SynthTrackModel.arpRateQ31FromUnit(0.0));
    assertEquals(Integer.MAX_VALUE, SynthTrackModel.arpRateQ31FromUnit(1.0));
    assertEquals(0, SynthTrackModel.arpRateQ31FromUnit(0.5));
  }
}
