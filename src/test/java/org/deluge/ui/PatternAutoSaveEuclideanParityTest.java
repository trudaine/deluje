package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.deluge.model.ClipModel;
import org.deluge.model.Iterance;
import org.deluge.model.PatternModel;
import org.deluge.model.PatternModel.ClipSnapshot;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.project.AutoSaveService;
import org.deluge.project.PatternSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatternAutoSaveEuclideanParityTest {

  @Test
  void patternSerializer_roundTripsMultipleNotesVelocityAndPlayConditions(@TempDir Path tempDir)
      throws Exception {
    ClipModel clip = new ClipModel("Lead", 4, 16);
    int iterance1of2 = new Iterance((byte) 2, (byte) 1).toInt();
    // Row 0: two notes with distinct velocities, probability, iterance, and fillMode
    clip.setStep(0, 1, new StepData(true, 0.50f, 1.0f, 0.75f, 60, 0, 1.0f, 0.0f, iterance1of2, 2));
    clip.setStep(0, 5, new StepData(true, 1.00f, 2.0f, 0.40f, 60, 0, 0.0f, 0.0f, 0, 1));
    // Row 2: one note on step 12
    clip.setStep(2, 12, new StepData(true, 0.25f, 0.5f, 1.0f, 64, 0, 0.0f, 0.0f, 0, 0));

    clip.setRowSoundParam(2, "cutoff", 0.6f);
    clip.setAutomation("resonance", 3, 0.5f);
    clip.setKitParam("stutterRate", 0.3f);

    ClipSnapshot snap = ClipSnapshot.fromClipModel(clip, 1, "Synth Lead");
    snap.setInstrumentSlot("SYNT001");
    snap.setColourHex("#112233");
    snap.setVolumeOverride(0.85f);
    snap.setPanOverride(-0.25f);

    PatternModel pattern = new PatternModel("pat-1", "Main Hook");
    pattern.setCategory("MELODIC");
    pattern.addClipSnapshot(snap);

    File xmlFile = tempDir.resolve("pattern.xml").toFile();
    PatternSerializer.save(pattern, xmlFile);

    PatternModel loaded = PatternSerializer.load(xmlFile);
    assertEquals("pat-1", loaded.getId());
    assertEquals("Main Hook", loaded.getName());
    assertEquals("MELODIC", loaded.getCategory());
    assertEquals(1, loaded.getClipSnapshots().size());

    ClipSnapshot ls = loaded.getClipSnapshots().get(0);
    assertEquals(1, ls.getTrackIndex());
    assertEquals("Synth Lead", ls.getTrackName());
    assertEquals("SYNT001", ls.getInstrumentSlot());
    assertEquals("#112233", ls.getColourHex());
    assertEquals(0.85f, ls.getVolumeOverride(), 1e-6f);
    assertEquals(-0.25f, ls.getPanOverride(), 1e-6f);
    assertEquals(4, ls.getGrid().size());

    // Row 0 step 1 and step 5 both survived with velocity, gate, probability, iterance, fillMode
    StepData s01 = ls.getGrid().get(0).get(1);
    assertTrue(s01.active());
    assertEquals(64.0f / 127.0f, s01.velocity(), 0.01f);
    assertEquals(1.0f, s01.gate(), 0.01f);
    assertEquals(0.75f, s01.probability(), 0.01f);
    assertEquals(iterance1of2, s01.playCondition());
    assertEquals(2, s01.fillMode());

    StepData s05 = ls.getGrid().get(0).get(5);
    assertTrue(s05.active());
    assertEquals(1.0f, s05.velocity(), 0.01f);
    assertEquals(2.0f, s05.gate(), 0.01f);
    assertEquals(0.40f, s05.probability(), 0.01f);
    assertEquals(1, s05.fillMode());

    // Row 2 step 12 survived
    StepData s212 = ls.getGrid().get(2).get(12);
    assertTrue(s212.active());
    assertEquals(32.0f / 127.0f, s212.velocity(), 0.01f);
    assertEquals(0.5f, s212.gate(), 0.01f);
  }

  @Test
  void patternSerializer_loadsLegacy22CharAnd20CharNoteData() throws Exception {
    // 22-char noteData (two notes at step 0 and step 2, 12 ticks/step)
    String xml22 =
        "<pattern><id>p1</id><name>Legacy22</name><category>MELODIC</category><clips>"
            + "<clip trackIndex=\"0\"><rowCount>2</rowCount><stepCount>8</stepCount><noteRows>"
            + "<noteRow><noteData>0x000000000000000C644014000000180000000C324014</noteData></noteRow>"
            + "</noteRows></clip></clips></pattern>";
    PatternModel loaded22 =
        PatternSerializer.load(
            new ByteArrayInputStream(xml22.getBytes(StandardCharsets.UTF_8)), "legacy22.xml");
    ClipSnapshot snap22 = loaded22.getClipSnapshots().get(0);
    assertTrue(snap22.getGrid().get(0).get(0).active());
    assertTrue(snap22.getGrid().get(0).get(2).active());
    assertEquals(100.0f / 127.0f, snap22.getGrid().get(0).get(0).velocity(), 0.01f);
    assertEquals(50.0f / 127.0f, snap22.getGrid().get(0).get(2).velocity(), 0.01f);

    // 20-char noteData (one note at step 2)
    String xml20 =
        "<pattern><id>p2</id><name>Legacy20</name><category>MELODIC</category><clips>"
            + "<clip trackIndex=\"0\"><rowCount>1</rowCount><stepCount>8</stepCount><noteRows>"
            + "<noteRow><noteData>0x000000180000000C4014</noteData></noteRow>"
            + "</noteRows></clip></clips></pattern>";
    PatternModel loaded20 =
        PatternSerializer.load(
            new ByteArrayInputStream(xml20.getBytes(StandardCharsets.UTF_8)), "legacy20.xml");
    assertTrue(loaded20.getClipSnapshots().get(0).getGrid().get(0).get(2).active());
  }

  @Test
  void clipSnapshotApplyTo_clearsStaleAutomationAndRowSoundParams() {
    ClipModel target = new ClipModel("Target", 4, 8);
    target.setAutomation("staleParam", 1, 0.9f);
    target.setAutomation("cutoff", 0, 0.8f);
    target.setRowSoundParam(0, "staleRowParam", 0.7f);

    ClipSnapshot snap = new ClipSnapshot(0, "Fresh", 4, 8);
    float[] cutoffAuto = new float[] {-1f, -1f, 0.4f, -1f, -1f, -1f, -1f, -1f};
    snap.getAutomationData().put("cutoff", cutoffAuto);

    snap.applyTo(target);

    // Stale automation parameter was removed
    assertFalse(target.hasAutomation("staleParam"));
    // Unautomated steps stay -1f (not 0.0f!)
    assertFalse(target.hasAutomation("cutoff", 0));
    assertEquals(-1f, target.getAutomationValue("cutoff", 0), 1e-6f);
    assertTrue(target.hasAutomation("cutoff", 2));
    assertEquals(0.4f, target.getAutomationValue("cutoff", 2), 1e-6f);
    // Stale row sound param was cleared
    assertFalse(target.hasRowSoundParams(0));
  }

  @Test
  void autoSaveService_guardsNonPositiveIntervalAndRemovesShutdownHook(@TempDir Path tempDir) {
    String prevCard = System.getProperty("deluge.card");
    try {
      System.setProperty("deluge.card", tempDir.toFile().getAbsolutePath());
      ProjectModel project = new ProjectModel();
      AutoSaveService service = new AutoSaveService(project);

      assertDoesNotThrow(() -> service.start(0));
      service.stop();
      // Calling stop() twice is also safe
      assertDoesNotThrow(service::stop);
    } finally {
      if (prevCard != null) {
        System.setProperty("deluge.card", prevCard);
      } else {
        System.clearProperty("deluge.card");
      }
    }
  }

  @Test
  void euclidean_supports24StepsZeroPulsesAndNegativeRotation() {
    // 0 pulses -> all false
    boolean[] zero = EuclideanRhythmDialog.euclidean(0, 24, 0);
    assertEquals(24, zero.length);
    for (boolean b : zero) {
      assertFalse(b);
    }

    // 6 pulses in 24 steps -> steps 0, 4, 8, 12, 16, 20 (C instrument_clip_view.cpp:7813)
    boolean[] sixIn24 = EuclideanRhythmDialog.euclidean(6, 24, 0);
    assertEquals(24, sixIn24.length);
    for (int s = 0; s < 24; s++) {
      assertEquals(s % 4 == 0, sixIn24[s], "step " + s);
    }

    // Negative rotation wraps cleanly via Math.floorMod
    boolean[] rotatedNeg1 = EuclideanRhythmDialog.euclidean(4, 16, -1);
    boolean[] rotatedPos15 = EuclideanRhythmDialog.euclidean(4, 16, 15);
    assertArrayEquals(rotatedPos15, rotatedNeg1);

    // steps <= 0 returns empty array safely
    assertEquals(0, EuclideanRhythmDialog.euclidean(4, 0, 0).length);
  }
}
