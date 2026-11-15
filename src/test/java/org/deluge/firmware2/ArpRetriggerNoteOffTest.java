package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ArpModel;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Upstream #5255 ({@code 910d479a6}, "always send note off before note on"): when a note that is
 * already sounding is played again — auditioned while the sequencer plays it — {@code
 * Arpeggiator::noteOn} now switches it off before sounding it again, because both note-ons share
 * one {@code ArpNote} and so only ever produce ONE note-off between them. A receiver that counts
 * note-ons (a mono synth's note stack, a MIDI device) would otherwise hold the note forever.
 *
 * <p>The C block is arpeggiator.cpp:376-387, in the arp-OFF branch of {@code Arpeggiator::noteOn}.
 * It is written into the instruction for whoever drains it: the kit note-on path does
 * (kit.cpp:745), the MIDI/gate outputs do (non_audio_instrument.cpp:94-100), and {@code
 * Sound::noteOn} deliberately does NOT — it reads only {@code instruction.arpNoteOn}
 * (sound.cpp:1578-1582), since it manages its own voices. The second test here pins that second
 * half, which this port had wrong: {@code Sound.triggerNote} used to drain both off-arrays, so with
 * the C block ported it would have released the very voice the call was starting.
 */
class ArpRetriggerNoteOffTest {

  private static final int CHANNEL = 3;

  @BeforeEach
  void resetPrng() {
    Functions.resetNoiseSeed();
  }

  /** Arp OFF, as a plain synth or a sequenced row plays. */
  private static Arpeggiator.Settings arpOff() {
    Arpeggiator.Settings s = new Arpeggiator.Settings();
    s.mode = Arpeggiator.ArpMode.OFF;
    return s;
  }

  private static Arpeggiator.ArpReturnInstruction freshInstruction() {
    Arpeggiator.ArpReturnInstruction i = new Arpeggiator.ArpReturnInstruction();
    i.reset();
    return i;
  }

  @Test
  void retriggeringASoundingNoteEmitsItsNoteOffFirst() {
    ArpeggiatorSynth arp = new ArpeggiatorSynth();
    Arpeggiator.Settings settings = arpOff();

    Arpeggiator.ArpReturnInstruction first = freshInstruction();
    arp.noteOn(settings, 60, 100, first, CHANNEL, new int[] {0, 0, 0});

    // The scenario has to be real before it can be probed: the first note-on must have produced a
    // note to play, and no note-off of its own.
    assertNotNull(first.arpNoteOn, "the first note-on must hand back a note to play");
    assertEquals(60, first.arpNoteOn.noteCodeOnPostArp[0]);
    assertEquals(
        Arpeggiator.ARP_NOTE_NONE,
        first.noteCodeOffPostArp[0],
        "a note that was not already sounding must not produce a note-off");

    // Sound/Kit/MIDI mark the note PLAYING once they have actually started it (sound.cpp:1597,
    // kit.cpp:1243). Only a PLAYING note gets switched off by the retrigger.
    Arpeggiator.ArpNote arpNote = first.arpNoteOn;
    arpNote.noteStatus[0] = Arpeggiator.ArpNoteStatus.PLAYING;
    arpNote.outputMemberChannel[0] = 9;

    Arpeggiator.ArpReturnInstruction second = freshInstruction();
    arp.noteOn(settings, 60, 100, second, CHANNEL, new int[] {0, 0, 0});

    assertEquals(
        60,
        second.noteCodeOffPostArp[0],
        "C arpeggiator.cpp:380 — retriggering a PLAYING note must switch it off first, or a"
            + " note-counting receiver holds it forever");
    assertEquals(
        9,
        second.outputMIDIChannelOff[0],
        "C arpeggiator.cpp:381 — the note-off has to go out on the channel the note was sounded on");
    assertEquals(
        Arpeggiator.MIDI_CHANNEL_NONE,
        arpNote.outputMemberChannel[0],
        "C arpeggiator.cpp:383 — the member channel is cleared so MPE output does not count the old"
            + " note when choosing one for the new one");

    // And the retrigger still plays: the note-off is in addition to the note-on, not instead of it.
    assertEquals(60, second.arpNoteOn.noteCodeOnPostArp[0]);
    assertEquals(Arpeggiator.ArpNoteStatus.PENDING, second.arpNoteOn.noteStatus[0]);
  }

  @Test
  void aNoteThatIsNotPlayingProducesNoNoteOff() {
    ArpeggiatorSynth arp = new ArpeggiatorSynth();
    Arpeggiator.Settings settings = arpOff();

    Arpeggiator.ArpReturnInstruction first = freshInstruction();
    arp.noteOn(settings, 60, 100, first, CHANNEL, new int[] {0, 0, 0});
    assertNotNull(first.arpNoteOn);
    // Left as the C's noteOn set it: PENDING, i.e. the caller never started it (no voice was free,
    // or note probability skipped the step). C arpeggiator.cpp:379 requires PLAYING.
    assertEquals(Arpeggiator.ArpNoteStatus.PENDING, first.arpNoteOn.noteStatus[0]);

    Arpeggiator.ArpReturnInstruction second = freshInstruction();
    arp.noteOn(settings, 60, 100, second, CHANNEL, new int[] {0, 0, 0});
    assertEquals(
        Arpeggiator.ARP_NOTE_NONE,
        second.noteCodeOffPostArp[0],
        "a note still only PENDING was never sounded, so there is nothing to switch off");
  }

  @Test
  void retriggeringThroughASoundDoesNotReleaseTheVoiceItIsStarting() {
    SynthTrackModel model = new SynthTrackModel("Retrigger");
    model.setArp(ArpModel.defaultConfig().toBuilder().active(false).mode("off").build());
    FirmwareSound sound = new FirmwareSound();
    FirmwareFactory.applyModelToLiveSound(model, sound);

    sound.triggerNote(60, 100);
    assertEquals(
        1, voicesPlaying(sound, 60), "the first hit must sound, or the retrigger proves nothing");

    // The arp now carries note 60 as PLAYING, so the second note-on writes a note-off for it
    // (arpeggiator.cpp:376-387). Sound::noteOn must ignore that note-off.
    sound.triggerNote(60, 100);
    // Measured both ways rather than assumed. Faithful (no drain): playing=2, releasing=0 — the
    // old voice keeps sounding and a second one starts, as POLY does. With the drain restored:
    // playing=1, releasing=1 — the retrigger released the note it was about to play. Asserting
    // only "some voice for 60 is playing" would pass BOTH ways (it did, when this test was first
    // written), because releaseVoice leaves the new voice behind.
    assertEquals(
        0,
        voicesReleasing(sound, 60),
        "C sound.cpp:1578-1582 — Sound::noteOn reads only instruction.arpNoteOn; acting on the"
            + " retrigger's note-off here releases the voice this very call started");
    assertEquals(
        2, voicesPlaying(sound, 60), "both the held voice and the retriggered one must sound");
  }

  /** The release side of the same count — a voice in RELEASE or FAST_RELEASE, not yet OFF. */
  private static int voicesReleasing(FirmwareSound sound, int note) {
    int n = 0;
    for (Voice v : sound.fw2Sound.voices) {
      if (v.active
          && v.note == note
          && v.envelopes[0].state.compareTo(Envelope.Stage.RELEASE) >= 0
          && v.envelopes[0].state != Envelope.Stage.OFF) {
        n++;
      }
    }
    return n;
  }

  /**
   * Counts voices for ONE note and only while they are pre-release: a released voice stays {@code
   * active} through its envelope release, so {@code getActiveVoiceCount() > 0} would pass with the
   * release applied — which is exactly the defect this is asserting against.
   */
  private static int voicesPlaying(FirmwareSound sound, int note) {
    int n = 0;
    for (Voice v : sound.fw2Sound.voices) {
      if (v.active
          && v.note == note
          && v.envelopes[0].state.compareTo(Envelope.Stage.RELEASE) < 0) {
        n++;
      }
    }
    return n;
  }
}
