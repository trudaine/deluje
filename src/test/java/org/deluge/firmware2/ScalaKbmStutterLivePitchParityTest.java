package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.deluge.model.tuning.ScalaKeyboardMap;
import org.deluge.model.tuning.ScalaKeyboardMapParser;
import org.deluge.model.tuning.ScalaScale;
import org.deluge.model.tuning.ScalaScaleParser;
import org.deluge.modulation.params.ParamManager;
import org.junit.jupiter.api.Test;

class ScalaKbmStutterLivePitchParityTest {

  @Test
  void scalaKbm_parsesSevenHeaderFieldsAndTunesReferenceMidiNoteToReferenceFrequency()
      throws Exception {
    String sclText =
        """
        ! 12-tet.scl
        12-tone equal temperament
        12
        !
        100.0
        200.0
        300.0
        400.0
        500.0
        600.0
        700.0
        800.0
        900.0
        1000.0
        1100.0
        2/1
        """;
    String kbmText =
        """
        ! Standard 12-key A440 keyboard map
        12
        0
        127
        60
        69
        440.0
        12
        0
        1
        2
        3
        4
        5
        6
        7
        8
        9
        10
        11
        """;

    ScalaScale scale =
        ScalaScaleParser.parse(
            new ByteArrayInputStream(sclText.getBytes(StandardCharsets.UTF_8)), "12-tet");
    ScalaKeyboardMap kbm =
        ScalaKeyboardMapParser.parse(
            new ByteArrayInputStream(kbmText.getBytes(StandardCharsets.UTF_8)), "a440.kbm");
    scale.setKeyboardMap(kbm);

    assertEquals(12, kbm.getMapSize());
    assertEquals(60, kbm.getMiddleMidiNote());
    assertEquals(69, kbm.getReferenceMidiNote());
    assertEquals(440.0, kbm.getReferenceFrequency(), 1e-9);
    assertEquals(12, kbm.getOctaveDegree());

    assertEquals(440.0, scale.mtof(69), 1e-6);
    assertEquals(440.0 / Math.pow(2.0, 9.0 / 12.0), scale.mtof(60), 1e-6);
    assertEquals(880.0, scale.mtof(81), 1e-6);
  }

  @Test
  void scalaKbm_handlesUnmappedKeysAndCustomOctaveDegree() throws Exception {
    String sclText =
        """
        ! 5-tet.scl
        5-tone equal temperament
        5
        !
        240.0
        480.0
        720.0
        960.0
        2/1
        """;
    String kbmText =
        """
        ! 5-note scale on 12 keys (white keys C D E F G), octaveDegree = 5
        12
        0
        127
        60
        60
        261.625565
        5
        0
        x
        1
        x
        2
        3
        x
        4
        x
        x
        x
        x
        """;

    ScalaScale scale =
        ScalaScaleParser.parse(
            new ByteArrayInputStream(sclText.getBytes(StandardCharsets.UTF_8)), "5-tet");
    ScalaKeyboardMap kbm =
        ScalaKeyboardMapParser.parse(
            new ByteArrayInputStream(kbmText.getBytes(StandardCharsets.UTF_8)), "5-on-12.kbm");
    scale.setKeyboardMap(kbm);

    assertEquals(261.625565, scale.mtof(60), 1e-6);
    assertEquals(0.0, scale.mtof(61), 1e-9, "unmapped key 'x' must be silent");
    assertEquals(261.625565 * 2.0, scale.mtof(72), 1e-6, "one 12-key pattern up = 5 scale degrees");
    assertEquals(261.625565 * 0.5, scale.mtof(48), 1e-6, "one 12-key pattern down");
  }

  @Test
  void scalaKbm_rejectsHeaderWithFewerThanSevenFields() {
    String badKbm = "12\n0\n127\n60\n440.0\n12\n";
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ScalaKeyboardMapParser.parse(
                new ByteArrayInputStream(badKbm.getBytes(StandardCharsets.UTF_8)), "bad.kbm"));
  }

  @Test
  void stutterer_threeArgBeginStutterUsesSoundLiveTimePerTickInverse() {
    Stutterer stutterer = new Stutterer();
    ParamManager pm = new ParamManager();
    pm.setUnpatchedValue(Param.UNPATCHED_STUTTER_RATE, 0);

    Sound slowSound = new Sound();
    slowSound.timePerInternalTickInverse = 1 << 19; // half tempo of default (1 << 20)

    Sound fastSound = new Sound();
    fastSound.timePerInternalTickInverse = 1 << 21; // double tempo of default (1 << 20)

    Stutterer.Config cfg = new Stutterer.Config();
    cfg.quantized = false;

    stutterer.beginStutter(slowSound, pm, cfg);
    StereoSample[] block = {new StereoSample(1000, -1000)};
    int slowRecordSamples = 0;
    while (stutterer.isActive()) {
      stutterer.processStutter(block, 1, slowSound.timePerInternalTickInverse);
      slowRecordSamples++;
      if (slowRecordSamples > 100000 || block[0].l != 1000) {
        break;
      }
      block[0].l = 1000;
      block[0].r = -1000;
    }
    stutterer.endStutter(pm);

    stutterer.beginStutter(fastSound, pm, cfg);
    int fastRecordSamples = 0;
    while (stutterer.isActive()) {
      block[0].l = 1000;
      block[0].r = -1000;
      stutterer.processStutter(block, 1, fastSound.timePerInternalTickInverse);
      fastRecordSamples++;
      if (fastRecordSamples > 100000 || block[0].l != 1000) {
        break;
      }
    }
    stutterer.endStutter(pm);

    assertTrue(
        slowRecordSamples > fastRecordSamples * 2,
        "slower sound tempo must record a longer stutter buffer ("
            + slowRecordSamples
            + " vs "
            + fastRecordSamples
            + ")");
  }

  @Test
  void livePitchShifter_unsignedPercPosAndUnsignedSampleCountDoNotCollideOrBreakSearch() {
    LiveInputBuffer inputBuf = new LiveInputBuffer();
    int phaseIncUp = LivePitchShifter.K_MAX_SAMPLE_VALUE * 3 / 2; // 1.5x pitch up
    LivePitchShifter shifter = new LivePitchShifter(LiveInputBuffer.InputType.INPUT_L, phaseIncUp);

    int numSamples = 64;
    int[] input = new int[numSamples * 2];
    for (int i = 0; i < numSamples; i++) {
      input[i * 2] = (i % 8 < 4) ? (1 << 22) : -(1 << 22);
    }
    int[] out = new int[numSamples];

    // Set up RAW_REPITCHING state with no active crossfade and howFarBack > numRawSamplesProcessed
    // so (numRawSamplesProcessed - howFarBack - 1) is negative as signed int32.
    shifter.playHeads[TimeStretcher.PLAY_HEAD_NEWER].mode =
        LivePitchShifterPlayHead.PlayHeadMode.RAW_REPITCHING;
    shifter.playHeads[TimeStretcher.PLAY_HEAD_NEWER].rawBufferReadPos =
        (64 - 200) & (LiveInputBuffer.K_INPUT_RAW_BUFFER_SIZE - 1);
    shifter.playHeads[TimeStretcher.PLAY_HEAD_NEWER].percPos = 0xFFFFFFFF;
    shifter.crossfadeProgress = LivePitchShifter.K_MAX_SAMPLE_VALUE;
    shifter.samplesTilHopEnd = 500;
    shifter.percThresholdForCut = 300;

    shifter.render(out, numSamples, phaseIncUp, 1 << 26, 0, 16, inputBuf, 0, input);

    // After early block where (64 - 200 - 1) < 0, percPos must use unsigned >>> 7
    // and not sign-extend to 0xFFFFFFFF (-1).
    assertNotEquals(
        0xFFFFFFFF,
        shifter.playHeads[TimeStretcher.PLAY_HEAD_NEWER].percPos,
        "percPos must use unsigned >>> 7 and not sign-extend to 0xFFFFFFFF");
    assertEquals(
        (64 - 200 - 1) >>> LiveInputBuffer.K_PERC_BUFFER_REDUCTION_MAGNITUDE,
        shifter.playHeads[TimeStretcher.PLAY_HEAD_NEWER].percPos);

    // Advance numRawSamplesProcessed into the upper half of uint32 (> Integer.MAX_VALUE)
    // and force a hopEnd to verify unsigned comparisons succeed without exception.
    inputBuf.numRawSamplesProcessed = 0x80001000;
    inputBuf.upToTime = 0x80001000;
    shifter.samplesTilHopEnd = 0;
    int[] outHigh = new int[numSamples];
    shifter.render(outHigh, numSamples, phaseIncUp, 1 << 26, 0, 16, inputBuf, 0x80001000, input);

    assertTrue(shifter.samplesTilHopEnd > 0, "hopEnd must complete with unsigned sample counter");
  }
}
