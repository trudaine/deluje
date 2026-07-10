package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import org.deluge.firmware2.DelayBuffer;
import org.deluge.firmware2.Param;
import org.deluge.firmware2.StereoSample;
import org.deluge.firmware2.Stutterer;
import org.deluge.modulation.params.ParamManager;
import org.junit.jupiter.api.Test;

class StuttererTest {

  @Test
  void changingRateDuringStutterReconfiguresDelayBufferForResampling() throws Exception {
    Stutterer stutterer = new Stutterer();
    ParamManager params = new ParamManager();
    params.setUnpatchedValue(Param.UNPATCHED_STUTTER_RATE, 0);

    Stutterer.Config config = new Stutterer.Config();
    stutterer.beginStutter(new Object(), params, config);

    DelayBuffer buffer = extractBuffer(stutterer);
    assertNull(buffer.resampleConfig, "buffer should start in native mode");

    params.setUnpatchedValue(Param.UNPATCHED_STUTTER_RATE, Integer.MAX_VALUE);
    StereoSample[] audio = new StereoSample[32];
    for (int i = 0; i < audio.length; i++) {
      audio[i] = new StereoSample(i << 20, i << 20);
    }

    stutterer.processStutter(audio, params);

    assertNotNull(
        buffer.resampleConfig,
        "rate change should push the stutter delay buffer into resampling mode");
  }

  @Test
  void quantizedStutterCentersParamAndRestoresOriginalValueOnEnd() {
    Stutterer stutterer = new Stutterer();
    ParamManager params = new ParamManager();
    int originalValue = Stutterer.knobPosToParamValue(20);
    params.setUnpatchedValue(Param.UNPATCHED_STUTTER_RATE, originalValue);

    Stutterer.Config config = new Stutterer.Config();
    config.quantized = true;

    stutterer.beginStutter(new Object(), params, config);

    assertEquals(
        0,
        params.getUnpatchedValue(Param.UNPATCHED_STUTTER_RATE),
        "quantized stutter should center the knob while active");

    stutterer.endStutter(params);

    assertEquals(
        originalValue,
        params.getUnpatchedValue(Param.UNPATCHED_STUTTER_RATE),
        "quantized stutter should restore the pre-stutter knob value");
  }

  @Test
  void nonQuantizedStutterResetsNegativeRateToCenterOnEnd() {
    Stutterer stutterer = new Stutterer();
    ParamManager params = new ParamManager();
    params.setUnpatchedValue(Param.UNPATCHED_STUTTER_RATE, Stutterer.knobPosToParamValue(-8));

    Stutterer.Config config = new Stutterer.Config();
    config.quantized = false;

    stutterer.beginStutter(new Object(), params, config);
    stutterer.endStutter(params);

    assertEquals(
        0,
        params.getUnpatchedValue(Param.UNPATCHED_STUTTER_RATE),
        "non-quantized stutter should recenter negative rates on end");
  }

  @Test
  void reverseResampledPlaybackRetreatsThroughDelayBuffer() throws Exception {
    Stutterer stutterer = new Stutterer();
    ParamManager params = new ParamManager();
    params.setUnpatchedValue(Param.UNPATCHED_STUTTER_RATE, Stutterer.knobPosToParamValue(20));

    Stutterer.Config config = new Stutterer.Config();
    config.quantized = false;
    config.reversed = true;
    stutterer.beginStutter(new Object(), params, config);

    setStatus(stutterer, "PLAYING");
    DelayBuffer buffer = extractBuffer(stutterer);
    buffer.setCurrentOffset(10);
    params.setUnpatchedValue(Param.UNPATCHED_STUTTER_RATE, 0);

    StereoSample[] audio = {new StereoSample()};
    stutterer.processStutter(audio, params);

    assertEquals(
        9,
        buffer.getCurrentOffset(),
        "reverse resampled playback should retreat through the delay buffer");
  }

  @Test
  void pingPongFlipsDirectionAtPlaybackEdge() throws Exception {
    Stutterer stutterer = new Stutterer();
    ParamManager params = new ParamManager();
    params.setUnpatchedValue(Param.UNPATCHED_STUTTER_RATE, 0);

    Stutterer.Config config = new Stutterer.Config();
    config.quantized = false;
    config.reversed = false;
    config.pingPong = true;
    stutterer.beginStutter(new Object(), params, config);

    setStatus(stutterer, "PLAYING");
    DelayBuffer buffer = extractBuffer(stutterer);
    // One native moveOn() from sizeIncludingExtra-2 lands exactly at sizeIncludingExtra-1 (the
    // ping-pong edge check happens AFTER the move), matching DelayBuffer.moveOn()'s wraparound
    // semantics: starting AT sizeIncludingExtra-1 instead would immediately wrap to 0.
    buffer.setCurrentOffset(buffer.sizeIncludingExtra - 2);
    assertFalse(extractCurrentReverse(stutterer));

    StereoSample[] audio = {new StereoSample()};
    stutterer.processStutter(audio, params);

    assertTrue(extractCurrentReverse(stutterer), "ping-pong should reverse direction at the edge");
  }

  @Test
  void stutterRateActuallyTracksTheRateKnobPosition() throws Exception {
    // Regression: getStutterRate called Functions.getExp(1, quantizedParamValue) -- the literal
    // 1 (instead of Functions.getParamNeutralValue(Param.GLOBAL_DELAY_RATE), the real ~2^29
    // neutral value C uses: paramNeutralValues[GLOBAL_DELAY_RATE]) and skipped the
    // cableToExpParamShortcut transform C applies to the knob value. Turning the knob therefore
    // never changed the computed rate at all -- every position collapsed to ~0 and was silently
    // floored to the same 1000 fallback, always producing an identical buffer size.
    Stutterer centered = new Stutterer();
    ParamManager centeredParams = new ParamManager();
    centeredParams.setUnpatchedValue(Param.UNPATCHED_STUTTER_RATE, 0);
    centered.beginStutter(new Object(), centeredParams, new Stutterer.Config());

    Stutterer turnedUp = new Stutterer();
    ParamManager turnedUpParams = new ParamManager();
    turnedUpParams.setUnpatchedValue(
        Param.UNPATCHED_STUTTER_RATE, Stutterer.knobPosToParamValue(40));
    turnedUp.beginStutter(new Object(), turnedUpParams, new Stutterer.Config());

    assertNotEquals(
        extractBuffer(centered).size(),
        extractBuffer(turnedUp).size(),
        "the stutter buffer size must actually change with the rate knob position");
  }

  @Test
  void tempoSyncScalingChangesBufferSizeWithTimePerTickInverse() throws Exception {
    // Regression: getStutterRate previously never applied the C's tempo-sync scaling
    // (multiply_32x32_rshift32(rate, timePerTickInverse) + magnitude-based clamp/shift,
    // stutterer.cpp:47-56) at all -- the stutter rate never locked to song tempo. Two drastically
    // different timePerTickInverse values must now produce different buffer sizes.
    Stutterer low = new Stutterer();
    ParamManager lowParams = new ParamManager();
    lowParams.setUnpatchedValue(Param.UNPATCHED_STUTTER_RATE, 0);
    low.beginStutter(new Object(), lowParams, new Stutterer.Config(), 1 << 19);

    Stutterer high = new Stutterer();
    ParamManager highParams = new ParamManager();
    highParams.setUnpatchedValue(Param.UNPATCHED_STUTTER_RATE, 0);
    high.beginStutter(new Object(), highParams, new Stutterer.Config(), 1 << 21);

    assertNotEquals(
        extractBuffer(low).size(),
        extractBuffer(high).size(),
        "tempo-sync scaling must change the stutter buffer size with timePerTickInverse");
  }

  @Test
  void globalOnlyOneSourceStuttersAtATime() {
    // C: "There's only one stutter effect active at a time, so we have a global stutterer"
    // (stutterer.h) -- Stutterer.GLOBAL matches that; a second beginStutter takes over from the
    // first, and isStuttering(oldSource) must go false.
    Object trackA = new Object();
    Object trackB = new Object();
    ParamManager params = new ParamManager();
    params.setUnpatchedValue(Param.UNPATCHED_STUTTER_RATE, 0);
    Stutterer.Config config = new Stutterer.Config();

    try {
      Stutterer.GLOBAL.beginStutter(trackA, params, config);
      assertTrue(Stutterer.GLOBAL.isStuttering(trackA));
      assertFalse(Stutterer.GLOBAL.isStuttering(trackB));

      Stutterer.GLOBAL.beginStutter(trackB, params, config);
      assertTrue(Stutterer.GLOBAL.isStuttering(trackB));
      assertFalse(
          Stutterer.GLOBAL.isStuttering(trackA),
          "only one source can be stuttering at a time, matching the real firmware's single"
              + " global stutterer");
    } finally {
      Stutterer.GLOBAL.endStutter();
    }
  }

  private static DelayBuffer extractBuffer(Stutterer stutterer) throws Exception {
    Field field = Stutterer.class.getDeclaredField("buffer");
    field.setAccessible(true);
    return (DelayBuffer) field.get(stutterer);
  }

  private static boolean extractCurrentReverse(Stutterer stutterer) throws Exception {
    Field field = Stutterer.class.getDeclaredField("currentReverse");
    field.setAccessible(true);
    return field.getBoolean(stutterer);
  }

  private static void setStatus(Stutterer stutterer, String statusName) throws Exception {
    Field field = Stutterer.class.getDeclaredField("status");
    field.setAccessible(true);
    field.set(stutterer, Enum.valueOf(Stutterer.Status.class, statusName));
  }
}
