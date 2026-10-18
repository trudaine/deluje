package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.model.ClipModel;
import org.junit.jupiter.api.Test;

/** C InstrumentClip::midiBank / midiSub / midiPGM (instrument_clip.cpp:68-70, 127-129). */
class MidiClipPgmTest {

  @Test
  void defaultsAreNoneAndCopiesKeepThem() {
    ClipModel a = new ClipModel("a", 1, 16);
    assertEquals(128, a.getMidiBank());
    assertEquals(128, a.getMidiSub());
    assertEquals(128, a.getMidiPGM());
    a.setMidiBank(2);
    a.setMidiSub(5);
    a.setMidiPGM(17);
    ClipModel b = a.deepCopy("b");
    assertEquals(2, b.getMidiBank());
    assertEquals(5, b.getMidiSub());
    assertEquals(17, b.getMidiPGM());
    assertFalse(b.midiPgmDiffersFrom(a));
    b.setMidiPGM(18);
    assertTrue(b.midiPgmDiffersFrom(a));
  }
}
