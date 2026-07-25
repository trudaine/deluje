package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class VoicePriorityParityTest {

  @Test
  public void testPriorityRatingBitfieldsAndCullingOrder() {
    Sound sound = new Sound();
    // voicePriority = 2 (HIGH) -> (3 - 2) = 1 << 30
    sound.voicePriority = 2;
    Voice v1 = new Voice(sound);
    sound.voices.add(v1);
    v1.active = true;

    // Set envelope state and time entered
    v1.envelopes[0].state = Envelope.Stage.ATTACK; // ordinal 0
    v1.envelopes[0].timeEnteredState = 10;

    int rating = v1.getPriorityRating();
    int expectedManual = (3 - 2) << 30;
    int expectedCount = (Math.min(1, 7) << 27);
    int expectedState = (0 << 24);
    int expectedTime = (-10) & 0x00FFFFFF;
    int expectedRating = expectedManual + expectedCount + expectedState + expectedTime;

    assertEquals(
        expectedRating,
        rating,
        "Priority rating must match C++ voice.cpp:2509 bitfield formulation");
  }

  @Test
  public void testAttackVoiceHasHigherPriorityThanReleaseVoice() {
    Sound sound = new Sound();
    sound.voicePriority = 1; // MEDIUM

    Voice vAttack = new Voice(sound);
    vAttack.active = true;
    vAttack.envelopes[0].state = Envelope.Stage.ATTACK;
    vAttack.envelopes[0].timeEnteredState = 100;

    Voice vRelease = new Voice(sound);
    vRelease.active = true;
    vRelease.envelopes[0].state = Envelope.Stage.RELEASE;
    vRelease.envelopes[0].timeEnteredState = 100;

    sound.voices.add(vAttack);
    sound.voices.add(vRelease);

    // Higher numerical rating means lower priority (stolen first).
    // Therefore Release voice must have a higher numerical rating than Attack voice.
    assertTrue(
        vRelease.getPriorityRating() > vAttack.getPriorityRating(),
        "Voice in RELEASE stage must have higher numerical rating (lower priority) than ATTACK stage");
  }
}
