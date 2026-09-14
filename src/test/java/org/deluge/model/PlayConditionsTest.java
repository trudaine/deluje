package org.deluge.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.deluge.engine.FirmwareSound;
import org.deluge.firmware2.Functions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Note play conditions — probability, iterance, fill — as evaluated by the sequencer, against the C
 * InstrumentClip::processCurrentPos (instrument_clip.cpp:738-905).
 *
 * <p>Until 2026-09-13 none of the three was evaluated: every note played on every pass. The plain
 * note is the control in each scenario — if it stops counting, the drive loop is broken and every
 * other assertion here would be vacuous.
 */
class PlayConditionsTest {

  private static final int LEN_STEPS = 4;

  /** Counts note-ons per pitch, and which loop each happened in, instead of rendering. */
  static final class CountingSound extends FirmwareSound {
    final Map<Integer, List<Integer>> loopsByPitch = new HashMap<>();
    int currentLoop;

    @Override
    public void triggerNote(int note, int vel) {
      loopsByPitch.computeIfAbsent(note, k -> new ArrayList<>()).add(currentLoop);
    }

    int count(int pitch) {
      return loopsByPitch.getOrDefault(pitch, List.of()).size();
    }

    List<Integer> loops(int pitch) {
      return loopsByPitch.getOrDefault(pitch, List.of());
    }
  }

  private final ClipModel clip = new ClipModel("cond", 4, LEN_STEPS);
  private final CountingSound sound = new CountingSound();

  PlayConditionsTest() {
    clip.setSound(sound);
  }

  @AfterEach
  void resetFill() {
    ProjectModel.changeFillMode(false);
  }

  private NoteModel addNote(int row, int pitch, int step) {
    NoteRowModel r = clip.getOrCreateRow(row);
    r.setPitch(pitch);
    NoteModel n = new NoteModel(step * 24, 12, 1.0f, 1.0f, 0);
    r.getNotes().add(n);
    return n;
  }

  /** Drive as PlaybackHandler.java:208-209 does: the caller advances lastProcessedPos. */
  private void play(int loops) {
    Functions.resetNoiseSeed();
    int len = clip.getLoopLength();
    for (int t = 0; t < loops * len; t++) {
      sound.currentLoop = t / len;
      clip.lastProcessedPos += 1;
      clip.processCurrentPos(1);
    }
  }

  /**
   * An independent copy of the C CONG (functions.cpp:305-311), so the test does not grade itself.
   */
  private static final class Cong {
    int seed = 380116160;

    int random255() {
      seed = 69069 * seed + 1234567;
      return seed >>> 24;
    }
  }

  @Test
  void plainNotePlaysEveryLoop() {
    addNote(0, 60, 0);
    play(4);
    assertEquals(4, sound.count(60));
  }

  @Test
  void probabilityDrawsMatchTheCExactly() {
    addNote(0, 60, 0).setProbabilityValue(10); // 50%
    int loops = 32;
    play(loops);

    // One draw per pass: conditionPassed = ((random255 * 20) >> 8) < probability (C :851-852).
    Cong cong = new Cong();
    List<Integer> expected = new ArrayList<>();
    for (int loop = 0; loop < loops; loop++) {
      if (((cong.random255() * 20) >> 8) < 10) expected.add(loop);
    }
    assertTrue(expected.size() > 0 && expected.size() < loops, "seed must give a mixed pattern");
    assertEquals(expected, sound.loops(60));
  }

  @Test
  void neighboursWithTheSameProbabilityShareOneOutcome() {
    // C :846-862 — probabilityCount stores the first note's result (255/254) for the rest. 25% +
    // 25%: two 50% notes would sum to 100 and take the one-winner path instead (C :769).
    addNote(0, 60, 0).setProbabilityValue(5);
    addNote(1, 62, 0).setProbabilityValue(5);
    play(32);
    assertEquals(sound.loops(60), sound.loops(62));
    assertTrue(sound.count(60) > 0 && sound.count(60) < 32);
  }

  @Test
  void probabilitiesSummingTo100PickExactlyOneWinner() {
    // C :769-805 — 25% + 75% at the same position: one draw, one winner.
    addNote(0, 60, 0).setProbabilityValue(5);
    addNote(1, 62, 0).setProbabilityValue(15);
    play(32);
    assertEquals(32, sound.count(60) + sound.count(62));
    assertTrue(sound.count(60) > 0 && sound.count(62) > 0);
  }

  @Test
  void followPreviousRepeatsTheEarlierOutcome() {
    // C :821-831 — 128|p plays iff the earlier note with probability p played.
    addNote(0, 60, 0).setProbabilityValue(10);
    addNote(1, 62, 2).setProbabilityValue(128 | 10);
    play(32);
    assertEquals(sound.loops(60), sound.loops(62));
    assertTrue(sound.count(60) > 0 && sound.count(60) < 32);
  }

  @Test
  void followPreviousComplementPlaysExactlyWhenTheEarlierDidNot() {
    // C :855-856 — deciding p also records the opposite result for 20 - p.
    addNote(0, 60, 0).setProbabilityValue(5);
    addNote(1, 62, 2).setProbabilityValue(128 | 15);
    play(32);
    assertEquals(32, sound.count(60) + sound.count(62));
    List<Integer> both = new ArrayList<>(sound.loops(60));
    both.retainAll(sound.loops(62));
    assertEquals(List.of(), both);
  }

  @Test
  void iterancePlaysOnlyItsRepeats() {
    addNote(0, 60, 0);
    addNote(1, 62, 0).setIterance(new Iterance((byte) 2, (byte) 0b01)); // 1of2
    addNote(2, 64, 0).setIterance(new Iterance((byte) 4, (byte) 0b1000)); // 4of4
    play(8);
    assertEquals(8, sound.count(60));
    assertEquals(List.of(0, 2, 4, 6), sound.loops(62));
    assertEquals(List.of(3, 7), sound.loops(64));
  }

  @Test
  void iteranceFirstPlaysOnlyTheFirstPass() {
    addNote(0, 60, 0).setIterance(Iterance.fromPresetIndex(1)); // FIRST
    play(4);
    assertEquals(List.of(0), sound.loops(60));
  }

  @Test
  void fillNotesFollowTheFillButton() {
    addNote(0, 60, 0);
    addNote(1, 62, 0).setFill(NoteModel.FILL_MODE_FILL);
    addNote(2, 64, 0).setFill(NoteModel.FILL_MODE_NOT_FILL);
    play(4);
    assertEquals(4, sound.count(60));
    assertEquals(0, sound.count(62));
    assertEquals(4, sound.count(64));

    ProjectModel.changeFillMode(true);
    play(4);
    assertEquals(8, sound.count(60));
    assertEquals(4, sound.count(62));
    assertEquals(4, sound.count(64));
  }

  // ── Loader: NoteRow::readFromFile per-note decode (note_row.cpp:3384-3440) ──

  private static NoteModel decode(String hexNote) {
    NoteModel n = new NoteModel();
    org.deluge.xml.DelugeNoteDataMapper.decodePlayConditions(hexNote, hexNote.length(), n);
    return n;
  }

  private static int[] conditions(NoteModel n) {
    return new int[] {n.getProbabilityValue(), n.getIterance().toInt(), n.getFill(), n.getLift()};
  }

  @Test
  void decodesSplitProbFormat() {
    // pos len vel | lift 50, prob 8a (follow-previous|10), iterance 0x0402 (2of4), fill 02
    assertArrayEquals(
        new int[] {0x8A, 0x0402, 2, 0x50},
        conditions(decode("00000000" + "00000018" + "64" + "50" + "8A" + "0402" + "02")));
    // garbage iterance (divisor 9) is refused by Iterance::fromInt
    assertEquals(
        0,
        decode("00000000" + "00000018" + "64" + "50" + "14" + "0901" + "00").getIterance().toInt());
  }

  @Test
  void decodesLiftFormatWithPackedFillAndIterance() {
    String head = "00000000" + "00000018" + "64" + "40";
    assertArrayEquals(new int[] {20, 0, 2, 0x40}, conditions(decode(head + "00"))); // old FILL
    assertArrayEquals(new int[] {20, 0, 1, 0x40}, conditions(decode(head + "80"))); // old NOT_FILL
    // 20 + 3 = preset 3 = 1of2 (lookuptables.cpp:509)
    assertArrayEquals(new int[] {20, 0x0201, 0, 0x40}, conditions(decode(head + "17")));
    assertArrayEquals(new int[] {7, 0, 0, 0x40}, conditions(decode(head + "07")));
    // lift 0 → kDefaultLiftValue
    assertEquals(64, decode("00000000" + "00000018" + "64" + "00" + "14").getLift());
  }

  @Test
  void decodesOldFormatAlwaysWithDefaultLift() {
    assertArrayEquals(
        new int[] {20, 0, 0, 64}, conditions(decode("00000000" + "00000018" + "40" + "14")));
  }
}
