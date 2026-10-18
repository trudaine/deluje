package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ClipModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.project.ProjectSerializer;
import org.junit.jupiter.api.Test;

/**
 * C ExpressionParamSet::bendRanges: a synth clip's "bendRange"/"bendRangeMPE"
 * (instrument_clip.cpp:2432-2433, 2888-2898) and a kit row's "bendRange" (note_row.cpp:3263, 3624)
 * survive load/save and set the voice's bend ranges (voice.cpp:830-835; defaults {2, 48}).
 */
class BendRangeTest {

  @Test
  void bendRangesRoundTripAndReachTheSound() throws Exception {
    ProjectModel model = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("LEAD");
    ClipModel a = new ClipModel("a", 1, 16);
    a.setBendRange(12);
    a.setBendRangeMPE(24);
    ClipModel b = new ClipModel("b", 1, 16);
    synth.addClip(a);
    synth.addClip(b);
    model.addTrack(synth);
    KitTrackModel kit = new KitTrackModel("KIT");
    ClipModel k = new ClipModel("k", 2, 16);
    k.setStep(1, 0, org.deluge.model.StepData.of(true, 1.0f, 1.0f, 1.0f, 60));
    k.getOrCreateRow(1).setBendRange(7);
    kit.addClip(k);
    model.addTrack(kit);

    File f = File.createTempFile("bend", ".xml");
    f.deleteOnExit();
    ProjectSerializer.save(model, f);
    ProjectModel back = DelugeXmlParser.parseSong(f);
    SynthTrackModel bs = (SynthTrackModel) back.getTracks().get(0);
    assertEquals(12, bs.getClips().get(0).getBendRange());
    assertEquals(24, bs.getClips().get(0).getBendRangeMPE());
    assertEquals(-1, bs.getClips().get(1).getBendRange());
    KitTrackModel bk = (KitTrackModel) back.getTracks().get(1);
    assertEquals(7, bk.getClips().get(0).getOrCreateRow(1).getBendRange());

    FirmwareSound fs = new FirmwareSound();
    bs.getClips().forEach(c -> c.setSound(fs));
    bs.setActiveClipIndex(1);
    bs.setActiveClipIndex(0);
    assertEquals(12, fs.fw2Sound.bendRangeMain);
    assertEquals(24, fs.fw2Sound.bendRangeFingerLevel);
    bs.setActiveClipIndex(1);
    assertEquals(2, fs.fw2Sound.bendRangeMain, "no expression params: FlashStorage default");
    assertEquals(48, fs.fw2Sound.bendRangeFingerLevel);
  }
}
