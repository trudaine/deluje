package org.deluge.modulation.midi;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.deluge.modulation.automation.AutoParam;
import org.deluge.modulation.automation.ParamNode;

/**
 * Port of {@code MIDIParamCollection} ({@code modulation/midi/midi_param_collection.cpp}) and
 * {@code MIDIParamVector} ({@code modulation/midi/midi_param_vector.cpp}). Steps per-CC {@link
 * AutoParam} lanes in ticks and sends 7-bit MIDI CC updates whenever the value crosses a 7-bit step
 * ({@code oldValue >> 25 != newValue >> 25}).
 */
public class MidiParamCollection {
  /** C definitions_cxx.hpp:767 — standard MIDI CC numbers 0..119. */
  public static final int NUM_REAL_CC_NUMBERS = 120;

  /** C definitions_cxx.hpp:763 — sentinel CC number for "no CC assigned". */
  public static final int CC_NUMBER_NONE = 123;

  /** Callback that dispatches a 7-bit MIDI CC value (0..127) on the instrument's output channel. */
  @FunctionalInterface
  public interface MidiCcSender {
    void sendCc(int cc, int value7Bit);
  }

  // C midi_param_vector.cpp:35-57 — kept sorted by ascending cc.
  private final List<MidiParam> params = new CopyOnWriteArrayList<>();

  public List<MidiParam> getParams() {
    return params;
  }

  public boolean isEmpty() {
    return params.isEmpty();
  }

  public void clear() {
    params.clear();
  }

  /** C midi_param_collection.cpp:37 — true when MIDI params exist in this collection. */
  public boolean mightContainAutomation() {
    return !params.isEmpty();
  }

  /** C midi_param_collection.cpp:169-173 — whether any MIDI CC param is currently interpolating. */
  public boolean isAnyParamInterpolating() {
    for (MidiParam midiParam : params) {
      if (midiParam.param.hasInterpolationIncrement()) {
        return true;
      }
    }
    return false;
  }

  /** C midi_param_vector.cpp:25-33 (MIDIParamVector::getParamFromCC). */
  public MidiParam getParamFromCc(int cc) {
    for (MidiParam midiParam : params) {
      if (midiParam.cc == cc) {
        return midiParam;
      }
    }
    return null;
  }

  /** C midi_param_vector.cpp:35-57 (MIDIParamVector::getOrCreateParamFromCC). */
  public MidiParam getOrCreateParamFromCc(int cc, int defaultValue) {
    int insertIdx = 0;
    while (insertIdx < params.size()) {
      MidiParam existing = params.get(insertIdx);
      if (existing.cc == cc) {
        return existing;
      }
      if (existing.cc > cc) {
        break;
      }
      insertIdx++;
    }
    MidiParam created = new MidiParam(cc);
    created.param.currentValue = defaultValue;
    params.add(insertIdx, created);
    return created;
  }

  public void setParam(int cc, int baseValueQ31, List<ParamNode> nodes) {
    MidiParam midiParam = getOrCreateParamFromCc(cc, baseValueQ31);
    midiParam.param.currentValue = baseValueQ31;
    midiParam.param.valueIncrementPerHalfTick = 0;
    midiParam.param.nodes.clear();
    if (nodes != null) {
      for (ParamNode node : nodes) {
        midiParam.param.nodes.add(new ParamNode(node.pos, node.value, node.interpolated));
      }
    }
  }

  /**
   * C midi_param_collection.cpp:214-223 (MIDIParamCollection::autoparamValueToCC) — maps signed Q31
   * {@code newValue} to signed {@code [-64, 63]} with {@code 1 << 24} rounding and an upper clamp
   * at {@code 2147483647 - (1 << 24)} to prevent signed 32-bit overflow.
   */
  public static int autoparamValueToCc(int newValue) {
    int rShift = 25;
    int roundingAmountToAdd = 1 << (rShift - 1);
    int maxValue = 2147483647 - roundingAmountToAdd;
    if (newValue > maxValue) {
      newValue = maxValue;
    }
    return (newValue + roundingAmountToAdd) >> rShift;
  }

  /**
   * C midi_instrument.h:146 (MIDIInstrument::valueChangedEnoughToMatter for Kind::MIDI) — returns
   * true when {@code oldValue >> 25 != newValue >> 25}.
   */
  public static boolean valueChangedEnoughToMatter(int oldValue, int newValue) {
    return (oldValue >> 25) != (newValue >> 25);
  }

  /**
   * C midi_param_collection.cpp:224-230, 284-303 — sends {@code autoparamValueToCC(newValue) + 64}
   * (0..127) when {@code oldValue >> 25 != newValue >> 25}.
   */
  private static void notifyIfChanged(int cc, int oldValue, int newValue, MidiCcSender sender) {
    if (sender == null || cc < 0 || cc >= NUM_REAL_CC_NUMBERS) {
      return;
    }
    if (valueChangedEnoughToMatter(oldValue, newValue)) {
      int ccValue7Bit = autoparamValueToCc(newValue) + 64;
      sender.sendCc(cc, ccValue7Bit);
    }
  }

  /** C midi_param_collection.cpp:86-100 + auto_param.cpp:1546-1579 (setPlayPos). */
  public void setPlayPos(int pos, int loopLength, boolean reversed, MidiCcSender sender) {
    for (MidiParam midiParam : params) {
      AutoParam param = midiParam.param;
      boolean automated = !param.nodes.isEmpty();
      int oldValue = param.currentValue;
      param.setPlayPos(pos, loopLength, reversed);
      if (automated) {
        notifyIfChanged(midiParam.cc, oldValue, param.currentValue, sender);
      }
    }
  }

  /** C midi_param_collection.cpp:151-175 + auto_param.cpp:732-744 (processCurrentPos). */
  public int processCurrentPos(
      int currentPos, int loopLength, boolean reversed, boolean didPingpong, MidiCcSender sender) {
    int minDist = Integer.MAX_VALUE;
    for (MidiParam midiParam : params) {
      AutoParam param = midiParam.param;
      int oldValue = param.currentValue;
      // C midi_param_collection.cpp:166-167 — pass mayInterpolate=true so AutoParam sets up
      // valueIncrementPerHalfTick; tickTicks() advances it because MIDI cannot step per sample.
      int dist = param.processCurrentPos(currentPos, loopLength, reversed, didPingpong, true);
      if (!param.nodes.isEmpty()) {
        notifyIfChanged(midiParam.cc, oldValue, param.currentValue, sender);
      }
      if (dist < minDist) {
        minDist = dist;
      }
    }
    return minDist;
  }

  /** C midi_param_collection.cpp:60-75 (MIDIParamCollection::tickTicks). */
  public void tickTicks(int numTicks, MidiCcSender sender) {
    for (MidiParam midiParam : params) {
      AutoParam param = midiParam.param;
      if (param.hasInterpolationIncrement()) {
        int oldValue = param.currentValue;
        boolean shouldNotify = param.tickTicks(numTicks);
        if (shouldNotify) {
          notifyIfChanged(midiParam.cc, oldValue, param.currentValue, sender);
        }
      }
    }
  }

  /** C midi_param_collection.cpp:313-320 (MIDIParamCollection::notifyPingpongOccurred). */
  public void notifyPingpongOccurred() {
    for (MidiParam midiParam : params) {
      midiParam.param.notifyPingpongOccurred();
    }
  }
}
