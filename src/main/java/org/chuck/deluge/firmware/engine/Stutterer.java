package org.chuck.deluge.firmware.engine;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.delay.DelayBuffer;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.modulation.params.ParamManager;
import org.chuck.deluge.firmware.util.FirmwareUtils;

/**
 * Port of the Deluge's Stutterer class. Implements real-time buffer-based stutter with bit-accurate
 * rate mapping.
 */
public class Stutterer {
  public enum Status {
    OFF,
    RECORDING,
    PLAYING
  }

  public static class Config {
    public boolean useSongStutter = true;
    public boolean quantized = true;
    public boolean reversed = false;
    public boolean pingPong = false;
  }

  private final DelayBuffer buffer = new DelayBuffer();
  private Status status = Status.OFF;
  private boolean currentReverse;
  private Config config = new Config();
  private int sizeLeftUntilRecordFinished = 0;
  private int valueBeforeStuttering = 0;
  private int lastQuantizedKnobDiff = 0;
  private Object stutterSource = null;

  public boolean isStuttering(Object source) {
    return stutterSource == source;
  }

  public void beginStutter(Object source, ParamManager paramManager, Config sc) {
    this.config = sc;
    this.currentReverse = config.reversed;

    if (config.quantized) {
      int paramValue = paramManager.getUnpatchedValue(Param.UNPATCHED_STUTTER_RATE);
      int knobPos = paramValueToKnobPos(paramValue);
      if (knobPos < -39) {
        knobPos = -16; // 4ths
      } else if (knobPos < -14) {
        knobPos = -8; // 8ths
      } else if (knobPos < 14) {
        knobPos = 0; // 16ths
      } else if (knobPos < 39) {
        knobPos = 8; // 32nds
      } else {
        knobPos = 16; // 64ths
      }
      valueBeforeStuttering = paramValue;
      lastQuantizedKnobDiff = knobPos;
      paramManager.setUnpatchedValue(Param.UNPATCHED_STUTTER_RATE, 0);
    }

    // ── Bit-Accurate Stutter Rate ──
    int rate = getStutterRate(paramManager);

    if (buffer.init(rate) == DelayBuffer.Error.NONE) {
      status = Status.RECORDING;
      sizeLeftUntilRecordFinished = buffer.size();
      stutterSource = source;
    }
  }

  public void processStutter(StereoSample[] audio, ParamManager paramManager) {
    if (status == Status.OFF) return;

    int rate = getStutterRate(paramManager);
    buffer.setupForRender(rate);

    if (status == Status.RECORDING) {
      for (StereoSample sample : audio) {
        if (buffer.isNative()) {
          buffer.clearAndMoveOn();
          sizeLeftUntilRecordFinished--;
          buffer.writeNative(sample);
        } else {
          int strength2 =
              buffer.advance(
                  () -> {
                    buffer.clearAndMoveOn();
                    sizeLeftUntilRecordFinished--;
                  });
          int strength1 = 65536 - strength2;
          buffer.writeResampled(sample, strength1, strength2);
        }
      }

      if (sizeLeftUntilRecordFinished < 0) {
        if (currentReverse) {
          buffer.setCurrentOffset(buffer.sizeIncludingExtra - 1);
        } else {
          buffer.setCurrentOffset(0);
        }
        status = Status.PLAYING;
      }
    } else { // PLAYING
      for (int i = 0; i < audio.length; i++) {
        if (buffer.isNative()) {
          if (currentReverse) buffer.moveBack();
          else buffer.moveOn();

          StereoSample curr = buffer.current();
          audio[i].l = curr.l;
          audio[i].r = curr.r;
        } else {
          int strength2 =
              buffer.advance(
                  () -> {
                    // loop around
                  });
          int strength1 = 65536 - strength2;
          StereoSample s = buffer.readResampled(strength1, strength2);
          audio[i].l = s.l;
          audio[i].r = s.r;
        }
      }
    }
  }

  public void endStutter() {
    endStutter(null);
  }

  public void endStutter(ParamManager paramManager) {
    buffer.discard();
    status = Status.OFF;

    if (paramManager != null) {
      if (config.quantized) {
        paramManager.setUnpatchedValue(Param.UNPATCHED_STUTTER_RATE, valueBeforeStuttering);
      } else if (paramManager.getUnpatchedValue(Param.UNPATCHED_STUTTER_RATE) < 0) {
        paramManager.setUnpatchedValue(Param.UNPATCHED_STUTTER_RATE, 0);
      }
    }

    lastQuantizedKnobDiff = 0;
    valueBeforeStuttering = 0;
    stutterSource = null;
  }

  private int getStutterRate(ParamManager paramManager) {
    int paramValue = paramManager.getUnpatchedValue(Param.UNPATCHED_STUTTER_RATE);
    int knobPos = paramValueToKnobPos(paramValue) + lastQuantizedKnobDiff;
    if (knobPos < -64) {
      knobPos = -64;
    } else if (knobPos > 64) {
      knobPos = 64;
    }
    int quantizedParamValue = knobPosToParamValue(knobPos);
    int rate = FirmwareUtils.getExp(1, quantizedParamValue);
    return Math.max(rate, 1000);
  }

  static int paramValueToKnobPos(int paramValue) {
    if (paramValue >= 0x7F000000) {
      return 64;
    }
    return (paramValue + (1 << 24)) >> 25;
  }

  static int knobPosToParamValue(int knobPos) {
    if (knobPos < 64) {
      return knobPos << 25;
    }
    return Integer.MAX_VALUE;
  }
}
