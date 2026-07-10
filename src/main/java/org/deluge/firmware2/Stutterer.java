package org.deluge.firmware2;

import static org.deluge.firmware2.Functions.multiply_32x32_rshift32;

import org.deluge.modulation.params.ParamManager;

/**
 * Port of the Deluge's Stutterer class. Implements real-time buffer-based stutter with bit-accurate
 * rate mapping. Moved to org.deluge.firmware2 for package decoupling.
 *
 * <p>C: "There's only one stutter effect active at a time, so we have a global stutterer to save
 * memory" (stutterer.h: {@code extern Stutterer stutterer;}) -- matched here via {@link #GLOBAL},
 * the single shared instance production code should use (mirroring {@code
 * ModControllableAudio::beginStutter/processStutter/endStutter} calling the one global {@code
 * stutterer}, gated by {@link #isStuttering}). The class itself stays instantiable for isolated
 * unit tests, same as C's {@code Stutterer} class is usable on its own merits.
 */
public class Stutterer {
  /** The one shared stutter engine, matching the real firmware's global {@code stutterer}. */
  public static final Stutterer GLOBAL = new Stutterer();

  /**
   * C: FlashStorage::defaultMagnitude (flash_storage.cpp:328) -- matches
   * Sidechain.DEFAULT_MAGNITUDE.
   */
  private static final int DEFAULT_MAGNITUDE = 2;

  /**
   * C: {@code sync} (stutterer.h) -- "TODO: This is currently unused! It's set to 7 initially, and
   * never modified." Real hardware runs with this same hardcoded 7 today, so the tempo-sync branch
   * in {@link #getStutterRate} is unconditionally taken there too; ported as a fixed constant
   * rather than a mutable field for that reason.
   */
  private static final int SYNC = 7;

  /**
   * C: {@code playbackHandler.getTimePerInternalTickInverse()} -- tempo-derived tick rate used by
   * {@link #getStutterRate}'s sync scaling. No live tempo clock reaches this DSP layer yet; matches
   * the same documented proxy {@link Sidechain#timePerInternalTickInverse} uses until one is wired.
   */
  private static final int DEFAULT_TIME_PER_TICK_INVERSE = 1 << 20;

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

  /**
   * The ParamManager captured at {@link #beginStutter}, reused by the render-loop overload of
   * {@link #processStutter} that takes no ParamManager of its own. C passes {@code paramManager}
   * explicitly on every {@code processStutter}/{@code endStutter} call (it always has one in
   * scope); this port's {@code firmware2.Sound} render loop does not carry a live {@code
   * org.deluge.modulation.params.ParamManager} reference of its own (that lives on the engine
   * wrapper, {@code engine.FirmwareSound}), so the reference captured here stands in for the render
   * call site.
   */
  private ParamManager activeParamManager;

  public boolean isStuttering(Object source) {
    return stutterSource == source;
  }

  public boolean isActive() {
    return status != Status.OFF;
  }

  public void beginStutter(Object source, ParamManager paramManager, Config sc) {
    beginStutter(source, paramManager, sc, DEFAULT_TIME_PER_TICK_INVERSE);
  }

  /**
   * @param timePerTickInverse C: {@code playbackHandler.getTimePerInternalTickInverse()} -- pass
   *     the caller's live tempo-tick value (e.g. {@code
   *     firmware2.Sound#timePerInternalTickInverse}) for tempo-synced stutter timing.
   */
  public void beginStutter(
      Object source, ParamManager paramManager, Config sc, int timePerTickInverse) {
    this.activeParamManager = paramManager;
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
    int rate = getStutterRate(paramManager, DEFAULT_MAGNITUDE, timePerTickInverse);

    if (buffer.init(rate) == DelayBuffer.Error.NONE) {
      status = Status.RECORDING;
      sizeLeftUntilRecordFinished = buffer.size();
      stutterSource = source;
    }
  }

  /**
   * Render-loop overload: uses the ParamManager captured at {@link #beginStutter} (see {@link
   * #activeParamManager}) since the caller (firmware2.Sound's render loop) has none of its own.
   *
   * @param timePerTickInverse C: {@code playbackHandler.getTimePerInternalTickInverse()}, e.g.
   *     {@code firmware2.Sound#timePerInternalTickInverse}
   */
  public void processStutter(StereoSample[] audio, int timePerTickInverse) {
    processStutter(audio, activeParamManager, DEFAULT_MAGNITUDE, timePerTickInverse);
  }

  public void processStutter(StereoSample[] audio, ParamManager paramManager) {
    processStutter(audio, paramManager, DEFAULT_MAGNITUDE, DEFAULT_TIME_PER_TICK_INVERSE);
  }

  public void processStutter(
      StereoSample[] audio, ParamManager paramManager, int magnitude, int timePerTickInverse) {
    if (status == Status.OFF) return;

    int rate = getStutterRate(paramManager, magnitude, timePerTickInverse);
    buffer.setupForRender(rate);

    if (status == Status.RECORDING) {
      for (StereoSample sample : audio) {
        if (buffer.isNative()) {
          buffer.clearAndMoveOn();
          sizeLeftUntilRecordFinished--;
          buffer.writeNative(sample.l, sample.r);
        } else {
          int strength2 =
              buffer.advance(
                  () -> {
                    buffer.clearAndMoveOn();
                    sizeLeftUntilRecordFinished--;
                  });
          int strength1 = 65536 - strength2;
          buffer.writeResampled(sample.l, sample.r, strength1, strength2);
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

          int[] curr = buffer.current();
          audio[i].l = curr[0];
          audio[i].r = curr[1];
        } else {
          int strength2 =
              currentReverse
                  ? buffer.retreat(
                      () -> {
                        buffer.moveBack();
                      })
                  : buffer.advance(
                      () -> {
                        buffer.moveOn();
                      });
          int strength1 = 65536 - strength2;

          int currentOffset = buffer.getCurrentOffset();
          int neighborOffset = currentReverse ? currentOffset - 1 : currentOffset + 1;
          if (neighborOffset < 0) {
            neighborOffset = buffer.sizeIncludingExtra - 1;
          } else if (neighborOffset == buffer.sizeIncludingExtra) {
            neighborOffset = 0;
          }

          int[] fromDelay1 = buffer.current();
          int[] fromDelay2 = buffer.at(neighborOffset);
          audio[i].l =
              (multiply_32x32_rshift32(fromDelay1[0], strength1 << 14)
                      + multiply_32x32_rshift32(fromDelay2[0], strength2 << 14))
                  << 2;
          audio[i].r =
              (multiply_32x32_rshift32(fromDelay1[1], strength1 << 14)
                      + multiply_32x32_rshift32(fromDelay2[1], strength2 << 14))
                  << 2;
        }

        if (config.pingPong
            && ((currentReverse && buffer.getCurrentOffset() == 0)
                || (!currentReverse
                    && buffer.getCurrentOffset() == buffer.sizeIncludingExtra - 1))) {
          currentReverse = !currentReverse;
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
    activeParamManager = null;
  }

  private int getStutterRate(ParamManager paramManager, int magnitude, int timePerTickInverse) {
    int paramValue = paramManager.getUnpatchedValue(Param.UNPATCHED_STUTTER_RATE);
    int knobPos = paramValueToKnobPos(paramValue) + lastQuantizedKnobDiff;
    if (knobPos < -64) {
      knobPos = -64;
    } else if (knobPos > 64) {
      knobPos = 64;
    }
    int quantizedParamValue = knobPosToParamValue(knobPos);
    // C: stutterer.cpp:44 -- getFinalParameterValueExp(paramNeutralValues[GLOBAL_DELAY_RATE],
    // cableToExpParamShortcut(paramValue)). Was previously Functions.getExp(1, quantizedParamValue)
    // -- the literal 1 (instead of the real ~2^29 neutral value) and the missing
    // cableToExpParamShortcut shortcut made the computed rate collapse to ~0 for any input,
    // silently floored to the 1000 fallback below; the stutter rate never actually tracked the
    // rate knob at all.
    int rate =
        Functions.getExp(
            Functions.getParamNeutralValue(Param.GLOBAL_DELAY_RATE),
            Functions.cableToExpParamShortcut(quantizedParamValue));

    // C: stutterer.cpp:47-56 -- sync is always 7 (see SYNC), so this branch is unconditionally
    // taken on real hardware too. Was previously missing entirely from this port: the stutter
    // rate never locked to song tempo at all.
    rate = multiply_32x32_rshift32(rate, timePerTickInverse);
    int lShiftAmount = SYNC + 6 - magnitude;
    int limit = Integer.MAX_VALUE >> lShiftAmount;
    rate = Math.min(rate, limit);
    rate <<= lShiftAmount;

    return Math.max(rate, 1000);
  }

  public static int paramValueToKnobPos(int paramValue) {
    if (paramValue >= 0x7F000000) {
      return 64;
    }
    return (paramValue + (1 << 24)) >> 25;
  }

  public static int knobPosToParamValue(int knobPos) {
    if (knobPos < 64) {
      return knobPos << 25;
    }
    return Integer.MAX_VALUE;
  }
}
