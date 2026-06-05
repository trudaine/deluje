package org.chuck.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Field;
import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.delay.DelayBuffer;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.modulation.params.ParamManager;
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

  private static DelayBuffer extractBuffer(Stutterer stutterer) throws Exception {
    Field field = Stutterer.class.getDeclaredField("buffer");
    field.setAccessible(true);
    return (DelayBuffer) field.get(stutterer);
  }
}
