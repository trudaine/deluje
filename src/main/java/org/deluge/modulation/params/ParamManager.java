package org.deluge.modulation.params;

import java.util.List;
import org.deluge.firmware2.Param;
import org.deluge.modulation.automation.AutoParam;
import org.deluge.modulation.midi.MidiParamCollection;
import org.deluge.modulation.patch.PatchCableSet;

public class ParamManager {
  public final List<AutoParam> automatedParams = new java.util.concurrent.CopyOnWriteArrayList<>();
  // C param_manager.cpp:333-334 — ParamManagerForTimeline starts at ticksSkipped=0,
  // ticksTilNextEvent=0.
  public int ticksTilNextEvent = 0;
  public int ticksSkipped = 0;
  private PatchCableSet patchCableSet = new PatchCableSet();
  private final MidiParamCollection midiParamCollection = new MidiParamCollection();
  private MidiParamCollection.MidiCcSender midiCcSender;
  private final int[] unpatchedValues = new int[Param.UNPATCHED_PITCH_ADJUST + 1];

  public int getUnpatchedValue(int paramId) {
    if (paramId < 0 || paramId >= unpatchedValues.length) return 0;
    return unpatchedValues[paramId];
  }

  public void setUnpatchedValue(int paramId, int value) {
    if (paramId >= 0 && paramId < unpatchedValues.length) {
      unpatchedValues[paramId] = value;
    }
  }

  public MidiParamCollection getMidiParamCollection() {
    return midiParamCollection;
  }

  public void setMidiCcSender(MidiParamCollection.MidiCcSender midiCcSender) {
    this.midiCcSender = midiCcSender;
  }

  public boolean mightContainAutomation() {
    return !automatedParams.isEmpty()
        || patchCableSet.mightContainAutomation()
        || midiParamCollection.mightContainAutomation();
  }

  /** C param_manager.cpp:393, 401 — whether any automated parameter is currently ramping. */
  public boolean isAnyParamInterpolating() {
    for (AutoParam param : automatedParams) {
      if (param.hasInterpolationIncrement()) {
        return true;
      }
    }
    return patchCableSet.isAnyCableInterpolating() || midiParamCollection.isAnyParamInterpolating();
  }

  public void notifyPingpongOccurred() {
    for (AutoParam param : automatedParams) {
      param.notifyPingpongOccurred();
    }
    // C patch_cable_set.cpp:1181-1189 — flip interpolating patch cable slopes on bounce.
    patchCableSet.notifyPingpongOccurred();
    // C midi_param_collection.cpp:313-320 — flip interpolating MIDI CC slopes on bounce.
    midiParamCollection.notifyPingpongOccurred();
    // C param_manager.cpp:605 — wake on the very next tick after a ping-pong bounce.
    ticksTilNextEvent = 0;
  }

  public PatchCableSet getPatchCableSet() {
    return patchCableSet;
  }

  public void recordParamValue(int paramId, int value, int currentPos) {
    recordParamValue(paramId, value, currentPos, false);
  }

  public void recordParamValue(int paramId, int value, int currentPos, boolean interpolated) {
    AutoParam target = null;
    for (AutoParam p : automatedParams) {
      if (p.paramId == paramId) {
        target = p;
        break;
      }
    }
    if (target == null) {
      target = new AutoParam(paramId);
      automatedParams.add(target);
    }
    target.addNode(currentPos, value, interpolated);
    // C param_manager.cpp:466-468 — automation change wakes the timeline on the next tick.
    ticksTilNextEvent = 0;
  }

  public AutoParam getAutomatedParam(int paramId) {
    for (AutoParam p : automatedParams) {
      if (p.paramId == paramId) {
        return p;
      }
    }
    return null;
  }

  /** C param_manager.cpp:438-450 (ParamManagerForTimeline::setPlayPos). */
  public void setPlayPos(int pos, int loopLength, boolean reversed) {
    for (AutoParam param : automatedParams) {
      param.setPlayPos(pos, loopLength, reversed);
    }
    // C patch_cable_set.cpp:594-608 (PatchCableSet::setPlayPos).
    patchCableSet.setPlayPos(pos, loopLength, reversed);
    // C midi_param_collection.cpp:86-100 (MIDIParamCollection::setPlayPos).
    midiParamCollection.setPlayPos(pos, loopLength, reversed, midiCcSender);
    ticksTilNextEvent = 0;
    ticksSkipped = 0;
  }

  public void processCurrentPos(
      int currentPos,
      int loopLength,
      boolean reversed,
      boolean didPingpong,
      boolean mayInterpolate) {
    ticksTilNextEvent = 0;
    processCurrentPos(0, currentPos, loopLength, reversed, didPingpong, mayInterpolate);
  }

  /** C param_manager.cpp:373-410 (ParamManagerForTimeline::processCurrentPos). */
  public void processCurrentPos(
      int ticksSinceLast,
      int currentPos,
      int loopLength,
      boolean reversed,
      boolean didPingpong,
      boolean mayInterpolate) {
    ticksSkipped += ticksSinceLast;
    ticksTilNextEvent -= ticksSinceLast;

    if (ticksTilNextEvent <= 0) {
      ticksTilNextEvent = Integer.MAX_VALUE;

      // C param_manager.cpp:388-395 — when sample-rate interpolation is unavailable (e.g. MIDI),
      // advance interpolation by ticksSkipped BEFORE processCurrentPos() updates the node/slope.
      if (!mayInterpolate && isAnyParamInterpolating()) {
        tickTicks(ticksSkipped);
      }

      for (AutoParam param : automatedParams) {
        // C param_manager.cpp:397-398, midi_param_collection.cpp:165-167 — pass true to AutoParam
        // so it sets up valueIncrementPerHalfTick; tickTicks() advances it when !mayInterpolate.
        int dist = param.processCurrentPos(currentPos, loopLength, reversed, didPingpong, true);
        if (dist < ticksTilNextEvent) {
          ticksTilNextEvent = dist;
        }
      }
      // C patch_cable_set.cpp:727-752 — step automated patch cables on the same timeline.
      int cableDist =
          patchCableSet.processCurrentPos(currentPos, loopLength, reversed, didPingpong);
      if (cableDist < ticksTilNextEvent) {
        ticksTilNextEvent = cableDist;
      }
      // C midi_param_collection.cpp:151-175 — step MIDI CC automation on the same timeline.
      int midiDist =
          midiParamCollection.processCurrentPos(
              currentPos, loopLength, reversed, didPingpong, midiCcSender);
      if (midiDist < ticksTilNextEvent) {
        ticksTilNextEvent = midiDist;
      }

      // C param_manager.cpp:401-403 — wake every tick while tick-domain interpolation is active.
      if (!mayInterpolate && isAnyParamInterpolating()) {
        ticksTilNextEvent = 0;
      }

      ticksSkipped = 0;
    }
  }

  public void tickSamples(int numSamples, int timePerTimerTickInverse) {
    for (AutoParam param : automatedParams) {
      param.tickSamples(numSamples, timePerTimerTickInverse);
    }
    // C patch_cable_set.cpp:576-592 (PatchCableSet::tickSamples).
    patchCableSet.tickSamples(numSamples, timePerTimerTickInverse);
  }

  /** C param_set.cpp:150-164 (ParamSet::tickTicks) + midi_param_collection.cpp:60-75. */
  public void tickTicks(int numTicks) {
    for (AutoParam param : automatedParams) {
      param.tickTicks(numTicks);
    }
    patchCableSet.tickTicks(numTicks);
    midiParamCollection.tickTicks(numTicks, midiCcSender);
  }
}
