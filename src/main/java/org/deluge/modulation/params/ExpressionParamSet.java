package org.deluge.modulation.params;

import java.util.List;
import org.deluge.modulation.automation.AutoParam;
import org.deluge.modulation.automation.ParamNode;

/**
 * Port of C++ {@code ExpressionParamSet} ({@code param_set.h:131-158}, {@code
 * param_set.cpp:574-730}) holding the 3 monophonic / MPE expression dimensions: {@code pitchBend}
 * (X), {@code yExpression} (Y), and {@code pressure} (Z).
 */
public class ExpressionParamSet {

  public static final int X_PITCH_BEND = 0;
  public static final int Y_SLIDE_TIMBRE = 1;
  public static final int Z_PRESSURE = 2;
  public static final int NUM_DIMENSIONS = 3;

  /** C {@code expressionParamNames} ({@code param_set.cpp:645}). */
  public static final String[] PARAM_NAMES = {"pitchBend", "yExpression", "pressure"};

  @FunctionalInterface
  public interface ExpressionListener {
    void onExpressionChanged(int dimension, int valueQ31);
  }

  private final AutoParam[] params =
      new AutoParam[] {
        new AutoParam(X_PITCH_BEND), new AutoParam(Y_SLIDE_TIMBRE), new AutoParam(Z_PRESSURE)
      };

  // C param_set.cpp:580-583 — FlashStorage::defaultBendRange = {2, 48}.
  public final int[] bendRanges = new int[] {2, 48};
  public int ticksTilNextEvent = Integer.MAX_VALUE;
  private boolean midiMode = false;

  public ExpressionParamSet() {
    this(false);
  }

  public ExpressionParamSet(boolean forDrum) {
    if (forDrum) {
      bendRanges[1] = bendRanges[0];
    }
  }

  public boolean isMidiMode() {
    return midiMode;
  }

  public void setMidiMode(boolean midiMode) {
    this.midiMode = midiMode;
  }

  public AutoParam getParam(int dimension) {
    return params[dimension];
  }

  public void setParam(int dimension, int baseValue, List<ParamNode> nodes) {
    if (dimension < 0 || dimension >= NUM_DIMENSIONS) {
      return;
    }
    AutoParam param = params[dimension];
    param.nodes.clear();
    param.currentValue = baseValue;
    param.valueIncrementPerHalfTick = 0;
    if (nodes != null) {
      for (ParamNode node : nodes) {
        param.addNode(node.pos, node.value, node.interpolated);
      }
    }
    ticksTilNextEvent = 0;
  }

  public void clear() {
    for (AutoParam param : params) {
      param.nodes.clear();
      param.currentValue = 0;
      param.valueIncrementPerHalfTick = 0;
    }
    ticksTilNextEvent = Integer.MAX_VALUE;
  }

  public void copyFrom(ExpressionParamSet other) {
    clear();
    if (other == null) {
      return;
    }
    this.midiMode = other.midiMode;
    this.bendRanges[0] = other.bendRanges[0];
    this.bendRanges[1] = other.bendRanges[1];
    for (int p = 0; p < NUM_DIMENSIONS; p++) {
      AutoParam src = other.params[p];
      setParam(p, src.currentValue, src.nodes);
    }
  }

  /** C {@code ExpressionParamSet::writeToFile} ({@code param_set.cpp:651-652}). */
  public boolean containsSomething() {
    for (int p = 0; p < NUM_DIMENSIONS; p++) {
      if (params[p].containsSomething(0)) {
        return true;
      }
    }
    return false;
  }

  /** C {@code NoteRow::getMPEValues} ({@code note_row.cpp:4339-4352}). */
  public int[] getMpeValues() {
    return new int[] {
      params[0].currentValue >> 16, params[1].currentValue >> 16, params[2].currentValue >> 16
    };
  }

  public boolean containsSomething(int dimension) {
    return dimension >= 0 && dimension < NUM_DIMENSIONS && params[dimension].containsSomething(0);
  }

  public boolean mightContainAutomation() {
    for (int p = 0; p < NUM_DIMENSIONS; p++) {
      if (params[p].isAutomated()) {
        return true;
      }
    }
    return false;
  }

  public boolean isAnyParamInterpolating() {
    for (int p = 0; p < NUM_DIMENSIONS; p++) {
      if (params[p].hasInterpolationIncrement()) {
        return true;
      }
    }
    return false;
  }

  public void notifyPingpongOccurred() {
    for (int p = 0; p < NUM_DIMENSIONS; p++) {
      params[p].notifyPingpongOccurred();
    }
    ticksTilNextEvent = 0;
  }

  /** C {@code ParamSet::setPlayPos} ({@code param_set.cpp:166-182}). */
  public void setPlayPos(int pos, int loopLength, boolean reversed, ExpressionListener listener) {
    for (int p = 0; p < NUM_DIMENSIONS; p++) {
      AutoParam param = params[p];
      if (!param.isAutomated()) {
        continue;
      }
      int oldValue = param.currentValue;
      param.setPlayPos(pos, loopLength, reversed);
      notifyIfChanged(p, oldValue, param.currentValue, listener);
    }
    ticksTilNextEvent = 0;
  }

  /**
   * C {@code ParamSet::processCurrentPos} ({@code param_set.cpp:111-133}) + {@code
   * ExpressionParamSet::notifyParamModifiedInSomeWay} ({@code param_set.cpp:594-617}).
   */
  public int processCurrentPos(
      int currentPos,
      int loopLength,
      boolean reversed,
      boolean didPingpong,
      boolean mayInterpolate,
      ExpressionListener listener) {
    ticksTilNextEvent = Integer.MAX_VALUE;
    for (int p = 0; p < NUM_DIMENSIONS; p++) {
      AutoParam param = params[p];
      if (!param.isAutomated()) {
        continue;
      }
      int oldValue = param.currentValue;
      int dist =
          param.processCurrentPos(currentPos, loopLength, reversed, didPingpong, mayInterpolate);
      if (dist < ticksTilNextEvent) {
        ticksTilNextEvent = dist;
      }
      notifyIfChanged(p, oldValue, param.currentValue, listener);
    }
    return ticksTilNextEvent;
  }

  /** C {@code ParamSet::tickSamples} ({@code param_set.cpp:135-148}). */
  public void tickSamples(
      int numSamples, int timePerTimerTickInverse, ExpressionListener listener) {
    for (int p = 0; p < NUM_DIMENSIONS; p++) {
      AutoParam param = params[p];
      if (!param.hasInterpolationIncrement()) {
        continue;
      }
      int oldValue = param.currentValue;
      if (param.tickSamples(numSamples, timePerTimerTickInverse)) {
        notifyIfChanged(p, oldValue, param.currentValue, listener);
      }
    }
  }

  /** C {@code ParamSet::tickTicks} ({@code param_set.cpp:150-164}). */
  public void tickTicks(int numTicks, ExpressionListener listener) {
    for (int p = 0; p < NUM_DIMENSIONS; p++) {
      AutoParam param = params[p];
      if (!param.hasInterpolationIncrement()) {
        continue;
      }
      int oldValue = param.currentValue;
      if (param.tickTicks(numTicks)) {
        notifyIfChanged(p, oldValue, param.currentValue, listener);
      }
    }
  }

  private void notifyIfChanged(
      int dimension, int oldValue, int newValue, ExpressionListener listener) {
    if (listener != null && valueChangedEnoughToMatter(dimension, oldValue, newValue, midiMode)) {
      listener.onExpressionChanged(dimension, newValue);
    }
  }

  /**
   * C {@code ModControllable::valueChangedEnoughToMatter} ({@code mod_controllable.h:59}) and
   * {@code MIDIInstrument::valueChangedEnoughToMatter} ({@code midi_instrument.h:136-147}).
   */
  public static boolean valueChangedEnoughToMatter(
      int dimension, int oldValue, int newValue, boolean midiMode) {
    if (!midiMode) {
      return oldValue != newValue;
    }
    if (dimension == X_PITCH_BEND) {
      // C midi_instrument.h:141 — pitch is in 14 bit instead of 7.
      return (oldValue >> 18) != (newValue >> 18);
    }
    // C midi_instrument.h:144 — aftertouch and mod wheel are positive only (>> 24).
    return (oldValue >> 24) != (newValue >> 24);
  }

  /** C {@code MIDIInstrument::sendMonophonicExpressionEvent} ({@code midi_instrument.cpp:294}). */
  public static int toMidiPitchBend14(int valueQ31) {
    return Math.clamp((valueQ31 >> 18) + 8192, 0, 16383);
  }

  /** C {@code MIDIInstrument::sendMonophonicExpressionEvent} ({@code midi_instrument.cpp:305}). */
  public static int toMidiModWheel7(int valueQ31) {
    return Math.clamp(valueQ31 >> 24, 0, 127);
  }

  /** C {@code MIDIInstrument::sendMonophonicExpressionEvent} ({@code midi_instrument.cpp:314}). */
  public static int toMidiChannelPressure7(int valueQ31) {
    return Math.clamp(valueQ31 >> 24, 0, 127);
  }

  /**
   * Maps XML tag/attribute names to expression dimensions (C {@code param_set.cpp:645, 680-693} and
   * {@code instrument_clip.cpp:2858-2877}).
   */
  public static int dimensionFromXmlName(String name) {
    if (name == null) {
      return -1;
    }
    return switch (name) {
      case "pitchBend" -> X_PITCH_BEND;
      case "yExpression" -> Y_SLIDE_TIMBRE;
      case "pressure", "channelPressure" -> Z_PRESSURE;
      default -> -1;
    };
  }
}
