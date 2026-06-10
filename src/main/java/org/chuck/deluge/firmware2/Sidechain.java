package org.chuck.deluge.firmware2;

/**
 * Faithful line-by-line port of {@code modulation/sidechain/sidechain.cpp} (214 lines).
 *
 * <p>A self-contained envelope follower that computes the sidechain ducking amount with
 * attack/release stages and exponential curves.
 */
public class Sidechain {

  // ── State (sidechain.h:32-44) ──

  /** C: EnvelopeStage status (sidechain.h:32) */
  public Envelope.Stage status = Envelope.Stage.OFF;

  /** C: uint32_t pos (sidechain.h:33) */
  public int pos;

  /** C: int32_t lastValue (sidechain.h:34) */
  public int lastValue = 2147483647; // ONE_Q31

  /** C: int32_t pendingHitStrength (sidechain.h:35) */
  public int pendingHitStrength;

  /** C: int32_t envelopeOffset (sidechain.h:37) */
  public int envelopeOffset;

  /** C: int32_t envelopeHeight (sidechain.h:38) */
  public int envelopeHeight;

  /** C: int32_t attack (sidechain.h:40) */
  public int attack;

  /** C: int32_t release (sidechain.h:41) */
  public int release;

  /** C: SyncType syncType (sidechain.h:43) */
  public int syncType; // 0=EVEN, 1=TRIPLET, 2=DOTTED

  /** C: SyncLevel syncLevel (sidechain.h:44). 0=off, 9=fastest. */
  public int syncLevel;

  private static final int SYNC_LEVEL_NONE = 0;
  private static final int ONE_Q31 = 2147483647;

  // ── Constructor (sidechain.cpp:25-48) ──

  public Sidechain() {
    // C: sidechain.cpp:26-28
    status = Envelope.Stage.OFF;
    lastValue = ONE_Q31;
    pos = 0;

    // C: sidechain.cpp:29-30 — getParamFromUserValue for attack=7, release=28
    attack = Functions.getParamFromUserValue(Param.STATIC_SIDECHAIN_ATTACK, 7);
    release = Functions.getParamFromUserValue(Param.STATIC_SIDECHAIN_RELEASE, 28);

    pendingHitStrength = 0; // C:31
    // C: sidechain.cpp:41-46 — with a song: 7 - (insideWorldTickMagnitude + offsetFromBPM); with no
    // song: 7 - FlashStorage::defaultMagnitude. The song default insideWorldTickMagnitude is itself
    // defaultMagnitude (=2, flash_storage.cpp:328, offset 0), so the faithful default is 7 - 2 = 5.
    syncLevel = 7 - DEFAULT_MAGNITUDE;
    syncType = 0; // SYNC_TYPE_EVEN
  }

  /** C: FlashStorage::defaultMagnitude (flash_storage.cpp:328). */
  static final int DEFAULT_MAGNITUDE = 2;

  /**
   * C: playbackHandler.getTimePerInternalTickInverse() — tempo-derived tick rate used by the synced
   * attack/release. No faithful fixed value exists without a transport; this proxy stands in until
   * a tempo clock is wired (set it to the real inverse-tick to get faithful synced timing).
   */
  public int timePerInternalTickInverse = 1 << 20;

  // ── cloneFrom (sidechain.cpp:50-55) ──

  public void cloneFrom(Sidechain other) {
    attack = other.attack;
    release = other.release;
    syncType = other.syncType;
    syncLevel = other.syncLevel;
  }

  // ── registerHit (sidechain.cpp:57-59) ──

  /** C: sidechain.cpp:57-59 */
  public void registerHit(int strength) {
    pendingHitStrength = combineHitStrengths(pendingHitStrength, strength);
  }

  /**
   * C: util/functions.cpp:1472-1479. Rather than true pythagoras, go halfway between the biggest
   * one and the (uint32-capped) sum. {@code sum} is uint32_t; cap and {@code >> 1} are unsigned.
   */
  static int combineHitStrengths(int strength1, int strength2) {
    long sum = (strength1 & 0xFFFFFFFFL) + (strength2 & 0xFFFFFFFFL); // (uint32_t)s1 + (uint32_t)s2
    if (Long.compareUnsigned(sum, 2147483647L) > 0) {
      sum = 2147483647L; // std::min(sum, (uint32_t)2147483647)
    }
    int maxOne = Math.max(strength1, strength2);
    return (maxOne >> 1) + (int) (sum >>> 1);
  }

  // ── getActualAttackRate (sidechain.cpp:95-112) ──

  int getActualAttackRate() {
    int alteredAttack;
    // C:97-98 — sync off
    if (syncLevel == SYNC_LEVEL_NONE) {
      alteredAttack = attack;
    } else {
      // C:101-109
      int rshiftAmount = (9 - syncLevel) - 2;
      alteredAttack = Functions.multiply_32x32_rshift32(attack << 11, timePerInternalTickInverse);

      if (rshiftAmount >= 0) {
        alteredAttack >>= rshiftAmount;
      } else {
        alteredAttack <<= -rshiftAmount;
      }
    }
    return alteredAttack;
  }

  // ── getActualReleaseRate (sidechain.cpp:114-124) ──

  int getActualReleaseRate() {
    int alteredRelease;
    // C:116-117 — sync off
    if (syncLevel == SYNC_LEVEL_NONE) {
      alteredRelease = release;
    } else {
      // C:119-121
      alteredRelease =
          Functions.multiply_32x32_rshift32(release << 13, timePerInternalTickInverse)
              >> (9 - syncLevel);
    }
    return alteredRelease;
  }

  // ── render (sidechain.cpp:126-214) ──

  /**
   * C: sidechain.cpp:126-214. Advances the envelope by numSamples and returns the current value
   * (bipolar, negative = ducked).
   */
  public int render(int numSamples, int shapeValue) {

    // C:129-148 — initial hit detected
    if (pendingHitStrength != 0) {
      int newOffset = ONE_Q31 - pendingHitStrength;
      pendingHitStrength = 0; // C:132

      // C:136 — only if deeper than current dip
      if (Integer.compareUnsigned(newOffset, lastValue) < 0) {
        envelopeOffset = newOffset; // C:137

        // C:140-146 — if attack is max, jump to release; otherwise start attack
        envelopeHeight = lastValue - envelopeOffset; // C:145 (must be set before prepareForRelease)
        if (attack == LookupTables.attackRateTable[0] << 2) {
          // C:140-142 — goto prepareForRelease (sets status=RELEASE, height=ONE_Q31-offset)
          pos = 0;
          status = Envelope.Stage.RELEASE;
          envelopeHeight = ONE_Q31 - envelopeOffset;
          // continue to doRelease below
        } else {
          status = Envelope.Stage.ATTACK; // C:144
          pos = 0; // C:146
        }
      }
    }

    // C:150-171 — ATTACK stage
    if (status == Envelope.Stage.ATTACK) {
      pos += numSamples * getActualAttackRate(); // C:151

      if (pos >= 8388608) { // C:153
        // prepareForRelease: (C:154-158)
        pos = 0;
        status = Envelope.Stage.RELEASE;
        envelopeHeight = ONE_Q31 - envelopeOffset;
        // goto doRelease
        // fall through to RELEASE below
      } else {
        // C:163-164 — exp attack curve
        int decayInput = 8388608 - pos;
        int decayVal = Functions.getDecay4(decayInput, 23);
        lastValue =
            (Functions.multiply_32x32_rshift32(envelopeHeight, (ONE_Q31 - decayVal)) << 1)
                + envelopeOffset;
        return lastValue - ONE_Q31;
      }
    }

    // C:172-207 — RELEASE stage
    if (status == Envelope.Stage.RELEASE) {
      // doRelease: (C:173)
      pos += numSamples * getActualReleaseRate(); // C:174

      if (pos >= 8388608) { // C:176
        status = Envelope.Stage.OFF; // C:177
        // goto doOff
      } else {
        // C:181 — uint32_t positiveShapeValue = (uint32_t)shapeValue + 2147483648. Adding
        // 0x80000000
        // wraps mod 2^32 exactly like the C uint32_t; the >> 15 below must be UNSIGNED (>>>).
        int positiveShapeValue = shapeValue + 0x80000000;

        int preValue;

        // C:189-199 — complex curve
        int curvedness16 = (positiveShapeValue >>> 15) - (pos >> 7);
        if (curvedness16 < 0) {
          preValue = pos << 8; // C:191
        } else {
          if (curvedness16 > 65536) {
            curvedness16 = 65536; // C:194-195
          }
          int straightness = 65536 - curvedness16;
          // C:198
          int decayInput = 8388608 - pos;
          int decayVal = Functions.getDecay8(decayInput, 23);
          preValue = straightness * (pos >> 8) + (decayVal >> 16) * curvedness16;
        }

        // C:201
        lastValue =
            ONE_Q31
                - envelopeHeight
                + (Functions.multiply_32x32_rshift32(preValue, envelopeHeight) << 1);

        return lastValue - ONE_Q31;
      }
    }

    // C:208-211 — OFF (doOff)
    // doOff: lastValue = ONE_Q31
    lastValue = ONE_Q31; // C:210

    return lastValue - ONE_Q31;
  }

  // ── registerHitRetrospectively (sidechain.cpp:61-93) ──

  /** C: sidechain.cpp:61-93. Registers a hit that happened numSamplesAgo. */
  public void registerHitRetrospectively(int strength, int numSamplesAgo) {
    pendingHitStrength = 0; // C:62
    envelopeOffset = ONE_Q31 - strength; // C:63

    int alteredAttack = getActualAttackRate(); // C:65
    int attackStageLengthInSamples = 8388608 / alteredAttack; // C:66

    envelopeHeight = ONE_Q31 - envelopeOffset; // C:68

    // C:71-73 — if still in attack stage
    if (Integer.compareUnsigned(numSamplesAgo, attackStageLengthInSamples) < 0) {
      pos = numSamplesAgo * alteredAttack; // C:72
      status = Envelope.Stage.ATTACK; // C:73
    } else {
      // C:77-92 — past attack stage
      int numSamplesSinceRelease = numSamplesAgo - attackStageLengthInSamples; // C:78
      int alteredRelease = getActualReleaseRate(); // C:79
      int releaseStageLengthInSamples = 8388608 / alteredRelease; // C:80

      // C:83 — if still in release
      if (Integer.compareUnsigned(numSamplesSinceRelease, releaseStageLengthInSamples) < 0) {
        pos = numSamplesSinceRelease * alteredRelease; // C:84
        status = Envelope.Stage.RELEASE; // C:85
      } else {
        status = Envelope.Stage.OFF; // C:90
      }
    }
  }
}
