package org.deluge.firmware2;

import org.deluge.firmware2.Arpeggiator.*;

/** Extracted ArpeggiatorKit subclass from Arpeggiator.java. */
public class ArpeggiatorKit extends ArpeggiatorSynth {
  @Override
  public ArpType getArpType() {
    return ArpType.KIT;
  }

  /** C: ArpeggiatorForKit::removeDrumIndex (arpeggiator.cpp:84-116). */
  public void removeDrumIndex(Settings arpSettings, int drumIndex) {
    int n = findNoteIndex(drumIndex); // notes.search(drumIndex, GREATER_OR_EQUAL)
    int numNotes = notes.size();
    if (n < numNotes) {
      // Delete drumIndex from the notes array.
      notes.remove(n);
      for (int i = 0; i < notesAsPlayed.size(); i++) {
        if (notesAsPlayed.get(i).noteCode == drumIndex) {
          notesAsPlayed.remove(i);
          break;
        }
      }
      // Now shift all the arpeggiator drumIndexes at/after n down by one.
      numNotes = notes.size();
      for (int i = n; i < numNotes; i++) {
        notes.get(i).inputCharacteristics[0] = notes.get(i).inputCharacteristics[0] - 1;
      }
      for (int i = 0; i < notesAsPlayed.size(); i++) {
        if (notesAsPlayed.get(i).noteCode > drumIndex) {
          notesAsPlayed.get(i).noteCode = notesAsPlayed.get(i).noteCode - 1;
        }
      }
      rearrangePatternArpNotes(arpSettings);
    }
  }
}
