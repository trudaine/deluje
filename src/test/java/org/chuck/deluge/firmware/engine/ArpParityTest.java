package org.chuck.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.chuck.deluge.firmware2.StereoSample;
import org.junit.jupiter.api.Test;

/**
 * Verifies the faithful firmware2 arpeggiator (port of arpeggiator.cpp). With arp ON, a held chord
 * should be stepped through one note at a time on the arp clock.
 */
public class ArpParityTest {

  @Test
  public void heldChordArpeggiates() {
    FirmwareSound sound = new FirmwareSound();
    sound.polyphonic = org.chuck.deluge.firmware.model.PolyphonyMode.MONO;
    sound.arpSettings.mode = org.chuck.deluge.firmware2.Arpeggiator.ArpMode.ARP;
    sound.arpSettings.numOctaves = 1;
    sound.arpSettings.gate = 1 << 23; // ~50% duty cycle
    sound.fw2Sound.arpPhaseIncrement = 16777216; // 2^24 → one arp step every ~256 samples

    // Hold a 3-note chord; with arp on these go to the arpeggiator, not straight to voices.
    sound.triggerNote(60, 100);
    sound.triggerNote(64, 100);
    sound.triggerNote(67, 100);

    int block = 64;
    int total = 4000; // ~15 arp steps
    StereoSample[] buf = new StereoSample[block];
    for (int i = 0; i < block; i++) buf[i] = new StereoSample();

    Set<Integer> notesHeard = new HashSet<>();
    int maxConcurrent = 0;
    int activeAfterFirstBlock = -1;
    for (int t = 0; t < total; t += block) {
      for (int i = 0; i < block; i++) {
        buf[i].l = 0;
        buf[i].r = 0;
      }
      sound.renderInternal(buf, block, null);
      int concurrent = 0;
      for (org.chuck.deluge.firmware2.Voice v : sound.fw2Sound.voices) {
        if (v.active) {
          concurrent++;
          notesHeard.add(v.note);
        }
      }
      maxConcurrent = Math.max(maxConcurrent, concurrent);
      if (activeAfterFirstBlock < 0) activeAfterFirstBlock = concurrent;
    }

    // The arp must have stepped through more than one note of the chord...
    assertTrue(
        notesHeard.size() >= 2,
        "arp should step through multiple chord notes, heard=" + notesHeard);
    // ...all drawn from the held chord...
    assertTrue(
        Set.of(60, 64, 67).containsAll(notesHeard), "arp notes must come from the held chord");
    // ...sequentially: a sustained chord would have all 3 voices active immediately, but the arp
    // hasn't even reached its first step after one short block.
    assertTrue(
        activeAfterFirstBlock <= 1,
        "arp must not sound the whole chord at once (got "
            + activeAfterFirstBlock
            + " immediately)");
    // ...one voice at a time in mono.
    assertTrue(
        maxConcurrent <= 1, "mono arp should sound one note at a time, max=" + maxConcurrent);
  }
}
