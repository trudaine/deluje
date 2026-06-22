package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.model.ProjectModel;
import org.deluge.model.tuning.ScalaScale;
import org.deluge.model.tuning.ScalaScaleParser;
import org.deluge.project.ProjectSerializer;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

class MicrotuningTest {

  @Test
  void testDefault12TETParity() {
    ProjectModel song = new ProjectModel();
    song.calculateNoteFrequencies();

    Sound sound = new Sound();
    sound.tuning = song;
    Voice voice = new Voice(sound);

    // 1. Verify that the precalculated tables match the standard 12-TET tables exactly
    for (int i = 0; i < 12; i++) {
      assertEquals(
          LookupTables.noteFrequencyTable[i],
          song.noteFrequencyRatio(i),
          "Frequency table mismatch at note " + i);
      assertEquals(
          LookupTables.noteIntervalTable[i],
          song.noteIntervalRatio(i),
          "Interval table mismatch at note " + i);
    }

    // 2. Verify that phase increments match the standard 12-TET engine across multiple octaves
    Sound standardSound = new Sound();
    standardSound.tuning = null;
    Voice standardVoice = new Voice(standardSound);
    for (int noteCode = 0; noteCode <= 127; noteCode++) {
      int expectedBaseInc = standardVoice.calculateBasePhaseIncrement(noteCode);
      int actualBaseInc = voice.calculateBasePhaseIncrement(noteCode);
      assertEquals(
          expectedBaseInc, actualBaseInc, "Base phase increment mismatch at noteCode " + noteCode);
    }
  }

  @Test
  void testCustomCentsTuning() {
    ProjectModel song = new ProjectModel();

    // Detune note 1 (C#) by +50 cents (quarter tone up)
    song.getCentAdjustForNotesInTemperament()[1] = 50;
    // Detune note 2 (D) by -50 cents (quarter tone down)
    song.getCentAdjustForNotesInTemperament()[2] = -50;

    song.calculateNoteFrequencies();

    // Verify frequency and interval adjustments in Q24 / Q30 fixed-point format
    int baseFreqQ24 = 1027294024; // Q24 base for 440Hz
    double expectedRatioPlus50 = Math.pow(2.0, 1.5 / 12.0); // 1 semitone + 50 cents = 1.5 semitones
    int expectedFreqPlus50 = (int) (expectedRatioPlus50 * baseFreqQ24);
    int expectedIntervalPlus50 = (int) (expectedRatioPlus50 * 1073741824.0);

    assertEquals(expectedFreqPlus50, song.noteFrequencyRatio(1), 1.0);
    assertEquals(expectedIntervalPlus50, song.noteIntervalRatio(1), 1.0);

    // Note 1 and Note 2 should now have the exact same frequency because they meet in the middle!
    assertEquals(song.noteFrequencyRatio(1), song.noteFrequencyRatio(2), 2.0);

    // Verify that the voice renders the detuned frequency
    Sound standardSound = new Sound();
    standardSound.tuning = null;
    Voice standardVoice = new Voice(standardSound);

    Sound sound = new Sound();
    sound.tuning = song;
    Voice voice = new Voice(sound);

    // Note 5 is C# (note 1 in the octave, since we subtract 4)
    int pInc = voice.calculateBasePhaseIncrement(5);
    int standardPInc = standardVoice.calculateBasePhaseIncrement(5);
    assertTrue(pInc > standardPInc, "Detuned note should have a higher pitch than standard 12-TET");
  }

  @Test
  void testCustomTemperamentRatios() {
    ProjectModel song = new ProjectModel();
    song.setIsEqualTemperament(false);
    song.setOctaveNumMicrotonalNotes(5); // 5-note pentatonic scale

    // Just Intonation Pentatonic Ratios: 1.0 (C), 9/8 (D), 5/4 (E), 3/2 (G), 5/3 (A)
    song.getCustomRatios()[0] = 1.0;
    song.getCustomRatios()[1] = 9.0 / 8.0;
    song.getCustomRatios()[2] = 5.0 / 4.0;
    song.getCustomRatios()[3] = 3.0 / 2.0;
    song.getCustomRatios()[4] = 5.0 / 3.0;

    song.calculateNoteFrequencies();

    // 1. Verify ratio-based frequency table calculations (against Q24 base 1027294024)
    int baseFreqQ24 = 1027294024;
    assertEquals(baseFreqQ24, song.noteFrequencyRatio(0));
    assertEquals((int) (1.5 * baseFreqQ24), song.noteFrequencyRatio(3)); // Perfect fifth ratio

    // 2. Verify custom octave and note division (including negative note codes)
    int o1 = song.octaveOf(0);
    int n1 = song.noteWithinOctaveOf(0);
    assertEquals(0, o1);
    assertEquals(0, n1);

    int o2 = song.octaveOf(5);
    int n2 = song.noteWithinOctaveOf(5);
    assertEquals(1, o2); // One octave up in a 5-note scale
    assertEquals(0, n2);

    int o3 = song.octaveOf(7);
    int n3 = song.noteWithinOctaveOf(7);
    assertEquals(1, o3);
    assertEquals(2, n3);

    int o4 = song.octaveOf(-1);
    int n4 = song.noteWithinOctaveOf(-1);
    assertEquals(-1, o4);
    assertEquals(4, n4);

    // 3. Verify that voice rendering scales perfectly by octaves
    Sound sound = new Sound();
    sound.tuning = song;
    Voice voice = new Voice(sound);

    // Note 4 is note 0 at octave 0 (since 4 - 4 = 0)
    // Note 9 is note 0 at octave 1 (since 9 - 4 = 5, which is 1 octave up in a 5-note scale)
    int pIncBase = voice.calculateBasePhaseIncrement(4);
    int pIncOctaveUp = voice.calculateBasePhaseIncrement(9);
    assertTrue(
        Math.abs(pIncOctaveUp - pIncBase * 2) <= 1,
        "Octave up note should have approximately double the phase increment (allowing 1 LSB bit-shift precision delta, got "
            + pIncOctaveUp
            + " vs "
            + (pIncBase * 2)
            + ")");
  }

  @Test
  void testXmlPersistence() throws Exception {
    // 1. Create a custom project model with microtonal settings
    ProjectModel model = new ProjectModel();
    model.setOctaveNumMicrotonalNotes(19); // 19-TET!
    model.setIsEqualTemperament(true);
    model.setBaseFrequencyHz(432.0); // A = 432Hz
    model.getCentAdjustForNotesInTemperament()[2] = 25; // Custom cent detune
    model.getCentAdjustForNotesInTemperament()[5] = -33;

    // 2. Export the project to a temporary XML file
    java.io.File tempFile = java.io.File.createTempFile("song_microtuning_test", ".xml");
    tempFile.deleteOnExit();
    ProjectSerializer.save(model, tempFile);

    // 3. Import the project back from the XML file
    ProjectModel importedModel = DelugeXmlParser.parseSong(tempFile);

    // 4. Assert that all microtonal parameters were correctly saved and loaded
    assertEquals(19, importedModel.getOctaveNumMicrotonalNotes());
    assertTrue(importedModel.isEqualTemperament());
    assertEquals(432.0, importedModel.getBaseFrequencyHz(), 0.001);
    assertEquals(25, importedModel.getCentAdjustForNotesInTemperament()[2]);
    assertEquals(-33, importedModel.getCentAdjustForNotesInTemperament()[5]);
    assertEquals(
        0, importedModel.getCentAdjustForNotesInTemperament()[0]); // untouched note should remain 0

    // 5. Repeat for non-equal temperament (ratio-based)
    ProjectModel ratioModel = new ProjectModel();
    ratioModel.setOctaveNumMicrotonalNotes(5);
    ratioModel.setIsEqualTemperament(false);
    ratioModel.getCustomRatios()[0] = 1.0;
    ratioModel.getCustomRatios()[1] = 1.125;
    ratioModel.getCustomRatios()[2] = 1.25;
    ratioModel.getCustomRatios()[3] = 1.5;
    ratioModel.getCustomRatios()[4] = 1.666667;

    java.io.File ratioTempFile = java.io.File.createTempFile("song_microtuning_ratio_test", ".xml");
    ratioTempFile.deleteOnExit();
    ProjectSerializer.save(ratioModel, ratioTempFile); // Typo fixed here: save ratioModel!

    ProjectModel importedRatioModel = DelugeXmlParser.parseSong(ratioTempFile);

    assertEquals(5, importedRatioModel.getOctaveNumMicrotonalNotes());
    assertTrue(!importedRatioModel.isEqualTemperament());
    assertEquals(1.125, importedRatioModel.getCustomRatios()[1], 0.00001);
    assertEquals(1.5, importedRatioModel.getCustomRatios()[3], 0.00001);
    assertEquals(0.0, importedRatioModel.getCustomRatios()[5], 0.00001); // untouched ratio slot
  }

  @Test
  void testScalaImport() throws Exception {
    // 1. Define a 7-note Just Intonation scale in standard Scala (.scl) format
    String sclContent =
        "! 5-limit.scl\n"
            + "!\n"
            + "7-note Just Intonation scale\n"
            + " 7\n"
            + "!\n"
            + " 9/8\n"
            + " 5/4\n"
            + " 4/3\n"
            + " 3/2\n"
            + " 5/3\n"
            + " 15/8\n"
            + " 2/1\n";

    // 2. Parse the Scala scale using our ScalaScaleParser
    java.io.ByteArrayInputStream in =
        new java.io.ByteArrayInputStream(
            sclContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    ScalaScale scale = ScalaScaleParser.parse(in, "5-limit");

    // 3. Import the parsed scale into a new ProjectModel
    ProjectModel model = new ProjectModel();
    model.importScalaScale(scale);

    // 4. Assert the ProjectModel's fields were correctly populated
    assertEquals(7, model.getOctaveNumMicrotonalNotes());
    assertTrue(!model.isEqualTemperament());
    assertEquals(1.0, model.getCustomRatios()[0]);
    assertEquals(1.125, model.getCustomRatios()[1]); // 9/8
    assertEquals(1.25, model.getCustomRatios()[2]); // 5/4
    assertEquals(1.3333333333333333, model.getCustomRatios()[3], 0.000001); // 4/3
    assertEquals(1.5, model.getCustomRatios()[4]); // 3/2
    assertEquals(1.6666666666666667, model.getCustomRatios()[5], 0.000001); // 5/3
    assertEquals(1.875, model.getCustomRatios()[6]); // 15/8
  }
}
