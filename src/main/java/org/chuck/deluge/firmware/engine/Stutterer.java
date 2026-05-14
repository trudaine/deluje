package org.chuck.deluge.firmware.engine;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.delay.DelayBuffer;
import org.chuck.deluge.firmware.modulation.params.ParamManager;

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
  private int lastQuantizedKnobDiff = 0;
  private Object stutterSource = null;

  public boolean isStuttering(Object source) {
    return stutterSource == source;
  }

  public void beginStutter(Object source, ParamManager paramManager, Config sc) {
    this.config = sc;
    this.currentReverse = config.reversed;

    // simplified rate for now
    int rate = 10000 << 5;

    if (buffer.init(rate) == DelayBuffer.Error.NONE) {
      status = Status.RECORDING;
      sizeLeftUntilRecordFinished = buffer.size();
      stutterSource = source;
    }
  }

  public void processStutter(StereoSample[] audio, ParamManager paramManager) {
    if (status == Status.OFF) return;

    // update rate...

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
          // resampled playback stub
        }

        if (config.pingPong) {
          // check boundaries to flip currentReverse
        }
      }
    }
  }

  public void endStutter() {
    buffer.discard();
    status = Status.OFF;
    stutterSource = null;
  }
}
