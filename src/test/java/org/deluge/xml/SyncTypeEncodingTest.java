package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.firmware2.Lfo;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.project.ProjectSerializer;
import org.junit.jupiter.api.Test;

/**
 * The C stores SyncType raw — EVEN 0, TRIPLET 10, DOTTED 19 (model/sync.h:11-13) — for LFOs, delay
 * and sidechain. The parsers passed it through to fields that mean 0 / 1 / 2, so a triplet LFO (10)
 * was clamped to DOTTED and a triplet delay matched neither branch; the writers emitted 1 / 2,
 * which hardware does not read as triplet / dotted.
 */
class SyncTypeEncodingTest {

  private static SynthTrackModel parse(String body) throws Exception {
    return DelugeXmlParser.parseSynth(
        new ByteArrayInputStream(
            ("<?xml version=\"1.0\"?><sound>" + body + "</sound>")
                .getBytes(StandardCharsets.UTF_8)),
        "T");
  }

  @Test
  void decodeAcceptsTheCValuesAndThisAppsOldOnes() {
    assertEquals(0, DelugeXmlUtil.syncTypeFromFile(0));
    assertEquals(1, DelugeXmlUtil.syncTypeFromFile(10));
    assertEquals(2, DelugeXmlUtil.syncTypeFromFile(19));
    assertEquals(1, DelugeXmlUtil.syncTypeFromFile(1), "old app save");
    assertEquals(2, DelugeXmlUtil.syncTypeFromFile(2), "old app save");
    assertEquals(10, DelugeXmlUtil.syncTypeToFile(1));
    assertEquals(19, DelugeXmlUtil.syncTypeToFile(2));
    assertEquals(0, DelugeXmlUtil.syncTypeToFile(0));
  }

  @Test
  void tripletLfoReachesFw2AsTriplet() throws Exception {
    SynthTrackModel synth = parse("<lfo1 type=\"sine\" syncLevel=\"7\" syncType=\"10\"/>");
    assertEquals(1, synth.getLfo(0).syncType());
    synth.addClip(new ClipModel("c", 1, 16));
    ProjectModel project = new ProjectModel();
    project.addTrack(synth);
    FirmwareFactory.createSong(project);
    FirmwareSound fs = (FirmwareSound) synth.getClips().get(0).getSound();
    assertEquals(Lfo.SyncType.TRIPLET, fs.fw2Sound.lfoConfig[0].syncType, "was clamped to DOTTED");
  }

  @Test
  void delayAndSidechainDecodeAndSaveTheCValues() throws Exception {
    SynthTrackModel synth =
        parse(
            "<delay syncLevel=\"7\" syncType=\"19\"/><sidechain syncLevel=\"6\" syncType=\"10\"/>");
    assertEquals(2, synth.getDelaySyncType());
    assertEquals(1, synth.getSidechainSyncType());
  }

  @Test
  void songSaveWritesTheCValues() throws Exception {
    ProjectModel project = new ProjectModel();
    project.setDelaySyncType(1);
    project.setSidechainSyncType(2);
    SynthTrackModel synth = new SynthTrackModel("S");
    synth.addClip(new ClipModel("c", 1, 16));
    project.addTrack(synth);
    String xml = ProjectSerializer.serializeToString(project);
    ProjectModel back =
        DelugeXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "rt");
    assertEquals(1, back.getDelaySyncType());
    assertEquals(2, back.getSidechainSyncType());
    assertTrue(xml.contains("syncType=\"10\""), "triplet is 10 in the file");
    assertTrue(xml.contains("syncType=\"19\""), "dotted is 19 in the file");
  }
}
