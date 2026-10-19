package org.deluge.firmware2;

/**
 * Faithful line-by-line port of the Deluge {@code patcher.cpp} / {@code patcher.h} Patcher class.
 * Combines patch cables (source→destination with strength) and runs the stored param knob through
 * the correct firmware curve to produce per-voice {@code paramFinalValues}.
 *
 * <p>The firmware dispatching logic:
 *
 * <ul>
 *   <li>Volume params ({@code p < FIRST_LOCAL_NON_VOLUME}) → getFinalParameterValueVolume
 *   <li>Linear params → getFinalParameterValueLinear
 *   <li>Hybrid params → getFinalParameterValueHybrid
 *   <li>Exp params → getFinalParameterValueExp, with envelope-rate override
 * </ul>
 *
 * <p>Firmware reference: {@code patcher.cpp} lines 20-220.
 */
public class Patcher {

  /** Destination: a param with associated patch cables. */
  public static class Destination {
    public int paramId;
    public int sourcesMask; // bitmask of active source types
    public final java.util.ArrayList<PatchCable> cables = new java.util.ArrayList<>();
    public int targetSource =
        -1; // if >= 0, this modulates the range/depth of targetSource -> targetParamId
    public int targetParamId = -1;

    public Destination(int paramId) {
      this.paramId = paramId;
    }
  }

  /** Patch cable: a source→destination modulation connection. */
  public static class PatchCable {
    public int source; // PatchSource ordinal
    public int amount; // Q31 strength
    public int polarity = BIPOLAR;
    public int rangeValue = 536870912; // dynamic range adjustment value (default 1.0 in Q30)
    public static final int BIPOLAR = 0;
    public static final int UNIPOLAR = 1;

    public int toPolarity(int srcVal) {
      // C patch_cable.h:50-63 — aftertouch is stored unipolar; Y is shared with mod wheel and
      // bypasses conversion; all other sources are stored bipolar.
      if (source == PatchSource.AFTERTOUCH.ordinal()) {
        if (polarity == UNIPOLAR) {
          return srcVal;
        }
        return (srcVal - (Integer.MAX_VALUE / 2)) * 2;
      }
      if (source == PatchSource.Y.ordinal() || polarity == BIPOLAR) {
        return srcVal;
      }
      return (srcVal / 2) + (Integer.MAX_VALUE / 2);
    }
  }

  public static class PatchCableSet {
    private static final class RawCableEntry {
      final int paramId;
      final int targetSource;
      final PatchCable cable;

      RawCableEntry(int paramId, int targetSource, PatchCable cable) {
        this.paramId = paramId;
        this.targetSource = targetSource;
        this.cable = cable;
      }
    }

    private static final class PublishedCableSnapshot {
      final int paramId;
      final int targetSource;
      final PatchCable cable;
      final int source;

      PublishedCableSnapshot(int paramId, int targetSource, PatchCable cable) {
        this.paramId = paramId;
        this.targetSource = targetSource;
        this.cable = cable;
        this.source = cable.source;
      }
    }

    private final java.util.ArrayList<RawCableEntry> rawEntries = new java.util.ArrayList<>();
    private java.util.List<Destination> lastPublishedDestinations = null;
    private final java.util.ArrayList<PublishedCableSnapshot> lastPublishedEntries =
        new java.util.ArrayList<>();
    public volatile java.util.List<Destination> destinations = new java.util.ArrayList<>();

    private void recordPublishedSnapshot(java.util.List<Destination> dests) {
      lastPublishedDestinations = dests;
      lastPublishedEntries.clear();
      for (int i = 0; i < dests.size(); i++) {
        Destination d = dests.get(i);
        int paramId = d.targetSource >= 0 ? d.targetParamId : d.paramId;
        for (int j = 0; j < d.cables.size(); j++) {
          lastPublishedEntries.add(
              new PublishedCableSnapshot(paramId, d.targetSource, d.cables.get(j)));
        }
      }
    }

    private boolean matchesPublishedSnapshot(java.util.List<Destination> currentDests) {
      if (currentDests != lastPublishedDestinations) {
        return false;
      }
      int idx = 0;
      for (int i = 0; i < currentDests.size(); i++) {
        Destination d = currentDests.get(i);
        int paramId = d.targetSource >= 0 ? d.targetParamId : d.paramId;
        for (int j = 0; j < d.cables.size(); j++) {
          if (idx >= lastPublishedEntries.size()) {
            return false;
          }
          PatchCable c = d.cables.get(j);
          PublishedCableSnapshot expected = lastPublishedEntries.get(idx++);
          if (c != expected.cable
              || paramId != expected.paramId
              || d.targetSource != expected.targetSource
              || c.source != expected.source) {
            return false;
          }
        }
      }
      return idx == lastPublishedEntries.size();
    }

    private void reconcileExternalDestinations() {
      java.util.List<Destination> currentDests = destinations;
      boolean externalDestsChanged =
          (lastPublishedDestinations != null && !matchesPublishedSnapshot(currentDests))
              || (lastPublishedDestinations == null
                  && rawEntries.isEmpty()
                  && !currentDests.isEmpty());
      if (externalDestsChanged) {
        rawEntries.clear();
        for (Destination d : currentDests) {
          int paramId = d.targetSource >= 0 ? d.targetParamId : d.paramId;
          for (PatchCable c : d.cables) {
            rawEntries.add(new RawCableEntry(paramId, d.targetSource, c));
          }
        }
        recordPublishedSnapshot(currentDests);
      }
    }

    public void clear() {
      rawEntries.clear();
      destinations = new java.util.ArrayList<>();
      lastPublishedDestinations = null;
      lastPublishedEntries.clear();
    }

    public void addCable(int paramId, PatchCable cable) {
      reconcileExternalDestinations();
      rawEntries.add(new RawCableEntry(paramId, -1, cable));
      Destination dest = null;
      for (Destination d : destinations) {
        if (d.paramId == paramId && d.targetSource < 0) {
          dest = d;
          break;
        }
      }
      if (dest == null) {
        dest = new Destination(paramId);
        destinations.add(dest);
      }
      dest.cables.add(cable);
      dest.sourcesMask |= (1 << cable.source);
      recordPublishedSnapshot(destinations);
    }

    public void addRangeCable(int targetParamId, int targetSource, PatchCable cable) {
      reconcileExternalDestinations();
      rawEntries.add(new RawCableEntry(targetParamId, targetSource, cable));
      Destination dest = null;
      for (Destination d : destinations) {
        if (d.targetParamId == targetParamId && d.targetSource == targetSource) {
          dest = d;
          break;
        }
      }
      if (dest == null) {
        dest = new Destination(targetParamId);
        dest.targetSource = targetSource;
        dest.targetParamId = targetParamId;
        destinations.add(dest);
      }
      dest.cables.add(cable);
      dest.sourcesMask |= (1 << cable.source);
      recordPublishedSnapshot(destinations);
    }

    /**
     * Rebuilds {@link #destinations} from all stored cables using C {@code
     * PatchCableSet::setupPatching} ({@code patch_cable_set.cpp:157-330, 498-516}), preserving
     * {@code EDITABLE} / currently inactive cables in {@link #rawEntries} so subsequent calls
     * re-enable them when the sound's mode, filter, or parameter values change ({@code
     * sound.cpp:3099}).
     */
    public void setupPatching(Sound sound) {
      setupPatching(sound, null);
    }

    /**
     * Two-pass {@code PatchCableSet::setupPatching} with optional timeline {@code paramManager}.
     */
    public void setupPatching(Sound sound, org.deluge.modulation.params.ParamManager paramManager) {
      reconcileExternalDestinations();
      java.util.ArrayList<Destination> pass1Direct = new java.util.ArrayList<>();
      java.util.ArrayList<Destination> pass1Range = new java.util.ArrayList<>();
      // Pass 1 (C patch_cable_set.cpp:160-176, 498-516): keep cables where patchCableIsUsable and
      // reuse each PatchCable instance in place (C patch_cable_set.cpp:175-245) so live mutations
      // to cable.amount before or after setupPatching remain synchronized in both directions.
      for (RawCableEntry entry : rawEntries) {
        PatchCable src = entry.cable;
        if (!isCableUsableInPass1(
            sound, paramManager, src.source, entry.paramId, entry.targetSource)) {
          continue;
        }
        src.rangeValue = 536870912; // C patch_cable_set.cpp:187 neutralRangeAdjustmentValue
        if (entry.targetSource < 0) {
          appendToDestinationList(pass1Direct, entry.paramId, -1, src);
        } else {
          appendToDestinationList(pass1Range, entry.paramId, entry.targetSource, src);
        }
      }
      // Pass 2 (C patch_cable_set.cpp:182-206): retain range-adjusting cables only when the
      // target (bottomLevelSource -> destination) cable itself exists in the usable set.
      java.util.ArrayList<Destination> result = new java.util.ArrayList<>(pass1Direct);
      for (Destination rangeDest : pass1Range) {
        if (hasUsableDirectCable(pass1Direct, rangeDest.targetParamId, rangeDest.targetSource)) {
          result.add(rangeDest);
        }
      }
      destinations = result;
      recordPublishedSnapshot(result);
    }
  }

  /**
   * Returns {@code current} with zero heap allocations when its active destinations, sources,
   * amounts, and polarities already match {@link #buildUsableDestinations(Sound,
   * org.deluge.modulation.params.ParamManager)}; otherwise compiles and returns a fresh usable
   * destination list.
   */
  public static java.util.List<Destination> syncUsableDestinations(
      java.util.List<Destination> current,
      Sound sound,
      org.deluge.modulation.params.ParamManager paramManager) {
    if (usableDestinationsMatch(current, sound, paramManager)) {
      return current;
    }
    return buildUsableDestinations(sound, paramManager);
  }

  private static boolean usableDestinationsMatch(
      java.util.List<Destination> cached,
      Sound sound,
      org.deluge.modulation.params.ParamManager paramManager) {
    if (cached == null) {
      return false;
    }
    java.util.List<org.deluge.modulation.patch.Destination> srcDests =
        paramManager.getPatchCableSet().destinations;
    if (srcDests.isEmpty()) {
      return cached.isEmpty();
    }
    int directCount = 0;
    while (directCount < cached.size() && cached.get(directCount).targetSource < 0) {
      directCount++;
    }
    // Verify direct entries come before range entries, with unique keys across cached.
    for (int k = 0; k < cached.size(); k++) {
      Destination cd = cached.get(k);
      if (k < directCount) {
        if (cd.targetSource >= 0) return false;
        for (int prev = 0; prev < k; prev++) {
          if (cached.get(prev).paramId == cd.paramId) return false;
        }
      } else {
        if (cd.targetSource < 0) return false;
        for (int prev = directCount; prev < k; prev++) {
          Destination pd = cached.get(prev);
          if (pd.targetParamId == cd.targetParamId && pd.targetSource == cd.targetSource) {
            return false;
          }
        }
      }
    }
    // Pass 1 check: merge-aware match across all direct Destination entries in srcDests.
    int totalMatchedDirectCables = 0;
    for (int k = 0; k < directCount; k++) {
      Destination cd = cached.get(k);
      int cableIdx = 0;
      for (int i = 0; i < srcDests.size(); i++) {
        org.deluge.modulation.patch.Destination d = srcDests.get(i);
        if (d.targetSource >= 0 || d.paramId != cd.paramId) continue;
        for (int j = 0; j < d.cables.size(); j++) {
          org.deluge.modulation.patch.PatchCable c = d.cables.get(j);
          int srcOrdinal = c.from.ordinal();
          if (!isCableUsableInPass1(sound, paramManager, srcOrdinal, d.paramId, -1)) continue;
          if (cableIdx >= cd.cables.size()) return false;
          PatchCable fc = cd.cables.get(cableIdx++);
          int expectedPol =
              (c.polarity == org.deluge.modulation.patch.PatchCable.Polarity.UNIPOLAR)
                  ? PatchCable.UNIPOLAR
                  : PatchCable.BIPOLAR;
          if (fc.source != srcOrdinal || fc.amount != c.getAmount() || fc.polarity != expectedPol) {
            return false;
          }
        }
      }
      if (cableIdx != cd.cables.size()) return false;
      totalMatchedDirectCables += cableIdx;
    }
    int totalExpectedDirectCables = 0;
    for (int i = 0; i < srcDests.size(); i++) {
      org.deluge.modulation.patch.Destination d = srcDests.get(i);
      if (d.targetSource >= 0) continue;
      for (int j = 0; j < d.cables.size(); j++) {
        if (isCableUsableInPass1(
            sound, paramManager, d.cables.get(j).from.ordinal(), d.paramId, -1)) {
          totalExpectedDirectCables++;
        }
      }
    }
    if (totalMatchedDirectCables != totalExpectedDirectCables) return false;

    // Pass 2 check: merge-aware match across all active range Destination entries in srcDests.
    int totalMatchedRangeCables = 0;
    for (int k = directCount; k < cached.size(); k++) {
      Destination cd = cached.get(k);
      if (!hasUsableDirectCableInPrefix(cached, directCount, cd.targetParamId, cd.targetSource)) {
        return false;
      }
      int cableIdx = 0;
      for (int i = 0; i < srcDests.size(); i++) {
        org.deluge.modulation.patch.Destination d = srcDests.get(i);
        if (d.targetSource != cd.targetSource || d.targetParamId != cd.targetParamId) continue;
        for (int j = 0; j < d.cables.size(); j++) {
          org.deluge.modulation.patch.PatchCable c = d.cables.get(j);
          int srcOrdinal = c.from.ordinal();
          if (!isCableUsableInPass1(
              sound, paramManager, srcOrdinal, d.targetParamId, d.targetSource)) {
            continue;
          }
          if (cableIdx >= cd.cables.size()) return false;
          PatchCable fc = cd.cables.get(cableIdx++);
          int expectedPol =
              (c.polarity == org.deluge.modulation.patch.PatchCable.Polarity.UNIPOLAR)
                  ? PatchCable.UNIPOLAR
                  : PatchCable.BIPOLAR;
          if (fc.source != srcOrdinal || fc.amount != c.getAmount() || fc.polarity != expectedPol) {
            return false;
          }
        }
      }
      if (cableIdx != cd.cables.size()) return false;
      totalMatchedRangeCables += cableIdx;
    }
    int totalExpectedRangeCables = 0;
    for (int i = 0; i < srcDests.size(); i++) {
      org.deluge.modulation.patch.Destination d = srcDests.get(i);
      if (d.targetSource < 0) continue;
      if (!hasUsableDirectCableInPrefix(cached, directCount, d.targetParamId, d.targetSource)) {
        continue;
      }
      for (int j = 0; j < d.cables.size(); j++) {
        if (isCableUsableInPass1(
            sound, paramManager, d.cables.get(j).from.ordinal(), d.targetParamId, d.targetSource)) {
          totalExpectedRangeCables++;
        }
      }
    }
    return totalMatchedRangeCables == totalExpectedRangeCables;
  }

  private static boolean hasUsableDirectCableInPrefix(
      java.util.List<Destination> directDests, int limit, int targetParamId, int targetSource) {
    for (int i = 0; i < limit; i++) {
      Destination d = directDests.get(i);
      if (d.paramId == targetParamId && d.targetSource < 0) {
        for (int j = 0; j < d.cables.size(); j++) {
          if (d.cables.get(j).source == targetSource) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /**
   * Two-pass C {@code PatchCableSet::setupPatching} ({@code patch_cable_set.cpp:157-330, 498-516})
   * building active firmware2 {@link Destination}s from {@code paramManager.getPatchCableSet()}
   * without mutating the model/timeline cable set (so {@code EDITABLE} and temporarily inactive
   * cables remain intact for save/round-trip and live re-activation).
   */
  public static java.util.List<Destination> buildUsableDestinations(
      Sound sound, org.deluge.modulation.params.ParamManager paramManager) {
    java.util.ArrayList<Destination> pass1Direct = new java.util.ArrayList<>();
    java.util.ArrayList<Destination> pass1Range = new java.util.ArrayList<>();
    for (org.deluge.modulation.patch.Destination d : paramManager.getPatchCableSet().destinations) {
      int paramId = d.targetSource >= 0 ? d.targetParamId : d.paramId;
      for (org.deluge.modulation.patch.PatchCable c : d.cables) {
        int srcOrdinal = c.from.ordinal();
        if (!isCableUsableInPass1(sound, paramManager, srcOrdinal, paramId, d.targetSource)) {
          continue;
        }
        PatchCable fc = new PatchCable();
        fc.source = srcOrdinal;
        fc.amount = c.getAmount();
        fc.polarity =
            (c.polarity == org.deluge.modulation.patch.PatchCable.Polarity.UNIPOLAR)
                ? PatchCable.UNIPOLAR
                : PatchCable.BIPOLAR;
        fc.rangeValue = 536870912; // C patch_cable_set.cpp:187 neutralRangeAdjustmentValue
        if (d.targetSource < 0) {
          appendToDestinationList(pass1Direct, paramId, -1, fc);
        } else {
          appendToDestinationList(pass1Range, paramId, d.targetSource, fc);
        }
      }
    }
    java.util.ArrayList<Destination> result = new java.util.ArrayList<>(pass1Direct);
    for (Destination rangeDest : pass1Range) {
      if (hasUsableDirectCable(pass1Direct, rangeDest.targetParamId, rangeDest.targetSource)) {
        result.add(rangeDest);
      }
    }
    return result;
  }

  private static boolean isCableUsableInPass1(
      Sound sound,
      org.deluge.modulation.params.ParamManager paramManager,
      int cableSource,
      int paramId,
      int targetSource) {
    // C patch_cable_set.cpp:498-516 + source_selection.cpp:269-283 + sound.cpp:1342-1354.
    if (cableSource < 0 || cableSource >= PatchSource.kNumPatchSources) {
      return false;
    }
    if (targetSource < 0) {
      if (paramId == Param.PLACEHOLDER_RANGE) {
        return false; // C patch_cable_set.cpp:499-501
      }
      return sound.maySourcePatchToParam(cableSource, paramId, paramManager)
          == Sound.PatchCableAcceptance.ALLOWED;
    }
    // Range-adjusting cable: reject sources disallowed on the sound (e.g. NOTE on drums, local
    // sources on global params), then check whether the adjusted top-level cable is ALLOWED.
    if (sound.maySourcePatchToParam(cableSource, 255, paramManager)
        == Sound.PatchCableAcceptance.DISALLOWED) {
      return false;
    }
    if (paramId >= Param.FIRST_GLOBAL && cableSource >= PatchSource.kFirstLocalSource) {
      return false;
    }
    return sound.maySourcePatchToParam(targetSource, paramId, paramManager)
        == Sound.PatchCableAcceptance.ALLOWED;
  }

  private static void appendToDestinationList(
      java.util.List<Destination> list, int paramId, int targetSource, PatchCable cable) {
    Destination dest = null;
    for (int i = 0; i < list.size(); i++) {
      Destination d = list.get(i);
      if (targetSource < 0) {
        if (d.paramId == paramId && d.targetSource < 0) {
          dest = d;
          break;
        }
      } else if (d.targetParamId == paramId && d.targetSource == targetSource) {
        dest = d;
        break;
      }
    }
    if (dest == null) {
      dest = new Destination(paramId);
      if (targetSource >= 0) {
        dest.targetSource = targetSource;
        dest.targetParamId = paramId;
      }
      list.add(dest);
    }
    dest.cables.add(cable);
    dest.sourcesMask |= (1 << cable.source);
  }

  private static boolean hasUsableDirectCable(
      java.util.List<Destination> usableDirect, int targetParamId, int targetSource) {
    for (int i = 0; i < usableDirect.size(); i++) {
      Destination d = usableDirect.get(i);
      if (d.paramId == targetParamId && d.targetSource < 0) {
        for (int j = 0; j < d.cables.size(); j++) {
          if (d.cables.get(j).source == targetSource) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /**
   * Evaluates range/depth destinations targeting global parameters ({@code >= Param.FIRST_GLOBAL})
   * using {@code globalSourceValues}. Mirrors C {@code Patcher::performPatching} on the Sound's
   * global patcher (sound.cpp:2446, patcher.cpp:85-105), which runs every block even when {@code
   * voices_.empty()}.
   */
  public static void evaluateGlobalRangeDestinations(
      PatchCableSet patchCableSet, int[] globalSourceValues) {
    java.util.List<Destination> dests = patchCableSet.destinations;
    for (int i = 0; i < dests.size(); i++) {
      Destination dest = dests.get(i);
      if (dest.targetSource < 0 && dest.paramId >= Param.FIRST_GLOBAL) {
        java.util.ArrayList<PatchCable> cables = dest.cables;
        for (int j = 0; j < cables.size(); j++) {
          cables.get(j).rangeValue = 536870912;
        }
      }
    }
    for (int i = 0; i < dests.size(); i++) {
      Destination dest = dests.get(i);
      if (dest.targetSource >= 0 && dest.targetParamId >= Param.FIRST_GLOBAL) {
        int combo = combineCablesLinearForRangeParam(dest, globalSourceValues, null);
        int val = Functions.getFinalParameterValueLinear(536870912, combo);
        for (int k = 0; k < dests.size(); k++) {
          Destination targetDest = dests.get(k);
          if (targetDest.paramId == dest.targetParamId && targetDest.targetSource < 0) {
            java.util.ArrayList<PatchCable> targetCables = targetDest.cables;
            for (int l = 0; l < targetCables.size(); l++) {
              PatchCable targetCable = targetCables.get(l);
              if (targetCable.source == dest.targetSource) {
                targetCable.rangeValue = val;
              }
            }
          }
        }
      }
    }
  }

  /**
   * Evaluates a single global parameter ({@code p >= Param.FIRST_GLOBAL}) using {@code
   * sound.getSmoothedPatchedParamValue(p)}, {@code sound.globalSourceValues}, and any cables in
   * {@code sound.patchCableSet}. Caller must call {@link #evaluateGlobalRangeDestinations} first if
   * range/depth cables may be active. Mirrors C {@code Patcher::performPatching} / {@code
   * recalculateFinalValueForParamWithNoCables} on {@code kPatcherConfigForSound} (sound.cpp:76-85,
   * patcher.cpp:30-141, 275-339).
   */
  public static int evaluateGlobalParam(Sound sound, int p) {
    int knob = sound.getSmoothedPatchedParamValue(p);
    Destination dest = null;
    java.util.List<Destination> dests = sound.patchCableSet.destinations;
    for (int i = 0; i < dests.size(); i++) {
      Destination d = dests.get(i);
      if (d.paramId == p && d.targetSource < 0 && !d.cables.isEmpty()) {
        dest = d;
        break;
      }
    }
    if (dest == null) {
      return computeFinalValueForParam(p, knob);
    }

    int neutral = Functions.getParamNeutralValue(p);
    if (p < Param.FIRST_GLOBAL_NON_VOLUME) {
      return Functions.getFinalParameterValueVolume(
          neutral, combineCablesLinear(dest, knob, sound.globalSourceValues));
    } else if (p < Param.FIRST_GLOBAL_HYBRID) {
      return Functions.getFinalParameterValueLinear(
          neutral, combineCablesLinear(dest, knob, sound.globalSourceValues));
    } else if (p < Param.FIRST_GLOBAL_EXP) {
      return Functions.getFinalParameterValueHybrid(
          neutral, combineCablesExp(dest, knob, sound.globalSourceValues));
    } else {
      return Functions.getFinalParameterValueExpWithDumbEnvelopeHack(
          neutral, combineCablesExp(dest, knob, sound.globalSourceValues), p);
    }
  }

  /**
   * Evaluates all global parameters ({@code Param.FIRST_GLOBAL .. Param.kNumParams - 1}) into
   * {@code paramFinalValues[p]}, evaluating global range/depth destinations first. Mirrors C {@code
   * Sound::patcher.performPatching} / {@code performInitialPatching} (sound.cpp:76-85, 2446;
   * patcher.cpp:63-141, 275-339).
   */
  public static void performGlobalPatching(Sound sound, int[] paramFinalValues) {
    evaluateGlobalRangeDestinations(sound.patchCableSet, sound.globalSourceValues);
    for (int p = Param.FIRST_GLOBAL; p < Param.kNumParams; p++) {
      paramFinalValues[p] = evaluateGlobalParam(sound, p);
    }
  }

  // ── performPatching (patcher.cpp:20-120) ──

  /**
   * Combine stored knobs + patch cables for all params and produce final values.
   * (patcher.cpp:20-120, adapted for full-param pass)
   *
   * @param knobValues stored/automated knob values per param (Q31)
   * @param sourceValues current source values (envelope, LFO, velocity, etc.)
   * @param patchCableSet the cable set for this sound
   * @param paramFinalValues output: final param values after curve application
   */
  public static void performPatching(
      Sound sound, int[] sourceValues, PatchCableSet patchCableSet, int[] paramFinalValues) {
    java.util.List<Destination> dests = patchCableSet.destinations;
    // 1. Reset all cable range values to neutral
    for (int i = 0; i < dests.size(); i++) {
      Destination dest = dests.get(i);
      java.util.ArrayList<PatchCable> cables = dest.cables;
      for (int j = 0; j < cables.size(); j++) {
        cables.get(j).rangeValue = 536870912;
      }
    }

    // 2. Evaluate range/depth modulating destinations first
    for (int i = 0; i < dests.size(); i++) {
      Destination dest = dests.get(i);
      if (dest.targetSource >= 0) {
        int combo = combineCablesLinearForRangeParam(dest, sourceValues, null);
        int val = Functions.getFinalParameterValueLinear(536870912, combo);

        // Apply this range value to the target patch cable
        for (int k = 0; k < dests.size(); k++) {
          Destination targetDest = dests.get(k);
          if (targetDest.paramId == dest.targetParamId && targetDest.targetSource < 0) {
            java.util.ArrayList<PatchCable> targetCables = targetDest.cables;
            for (int l = 0; l < targetCables.size(); l++) {
              PatchCable targetCable = targetCables.get(l);
              if (targetCable.source == dest.targetSource) {
                targetCable.rangeValue = val;
              }
            }
          }
        }
      }
    }

    // 3. Evaluate normal parameter destinations
    for (int i = 0; i < dests.size(); i++) {
      Destination dest = dests.get(i);
      if (dest.targetSource >= 0) continue; // skip range destinations in this pass

      int p = dest.paramId;
      // C (patcher.cpp:118): the curve neutral is paramNeutralValues[param], which the C populates
      // as getParamNeutralValue(param) (functions.cpp:180) — a STATIC per-param constant, NOT the
      // knob.
      int staticNeutral = Functions.getParamNeutralValue(p);
      int finalValue;

      if (dest.cables.isEmpty()) continue; // no cables → knob stays as-is

      if (p < Param.FIRST_LOCAL_NON_VOLUME
          || (p >= Param.FIRST_GLOBAL && p < Param.FIRST_GLOBAL_NON_VOLUME)) {
        // VOLUME
        int combo = combineCablesLinear(dest, sound.getSmoothedPatchedParamValue(p), sourceValues);
        finalValue = Functions.getFinalParameterValueVolume(staticNeutral, combo);
      } else if (p < Param.FIRST_LOCAL_HYBRID
          || (p >= Param.FIRST_GLOBAL && p < Param.FIRST_GLOBAL_HYBRID)) {
        // LINEAR
        int combo = combineCablesLinear(dest, sound.getSmoothedPatchedParamValue(p), sourceValues);
        finalValue = Functions.getFinalParameterValueLinear(staticNeutral, combo);
      } else if (p < Param.FIRST_LOCAL_EXP
          || (p >= Param.FIRST_GLOBAL && p < Param.FIRST_GLOBAL_EXP)) {
        // HYBRID
        int combo = combineCablesExp(dest, sound.getSmoothedPatchedParamValue(p), sourceValues);
        finalValue = Functions.getFinalParameterValueHybrid(staticNeutral, combo);
      } else {
        // EXP
        int combo = combineCablesExp(dest, sound.getSmoothedPatchedParamValue(p), sourceValues);
        int stage = getEnvStage(p);
        if (stage != -1) {
          finalValue =
              Functions.getFinalParameterValueExpWithDumbEnvelopeHack(staticNeutral, combo, p);
        } else {
          finalValue = Functions.getFinalParameterValueExp(staticNeutral, combo);
        }
      }

      paramFinalValues[p] = finalValue;
    }
  }

  // ── combineCablesLinear (patcher.cpp:143-180) ──

  /**
   * Returns whether {@code source} satisfies the sound-independent invariants of C {@code
   * Sound::maySourcePatchToParam} (sound.cpp:1346-1363, 1487-1500): valid source/param range, no
   * local-to-global cables, no envelopes or sidechain on {@code LOCAL_VOLUME}, nothing on {@code
   * GLOBAL_VOLUME_POST_FX}, no {@code X} on {@code LOCAL_PITCH_ADJUST}, and only {@code SIDECHAIN}
   * on {@code GLOBAL_VOLUME_POST_REVERB_SEND}. Sound-state-dependent checks run in {@link
   * Sound#maySourcePatchToParam(int, int, org.deluge.modulation.params.ParamManager)} and {@link
   * PatchCableSet#setupPatching}.
   */
  public static boolean maySourcePatchToParam(int source, int paramId) {
    if (source < 0 || source >= PatchSource.kNumPatchSources) {
      return false;
    }
    if (paramId < 0 || paramId >= Param.kNumParams) {
      return false;
    }
    if (paramId >= Param.FIRST_GLOBAL && source >= PatchSource.kFirstLocalSource) {
      return false;
    }
    return switch (paramId) {
      case Param.LOCAL_VOLUME ->
          (source < PatchSource.ENVELOPE_0.ordinal() || PatchSource.ENVELOPE_3.ordinal() < source)
              && source != PatchSource.SIDECHAIN.ordinal();
      case Param.GLOBAL_VOLUME_POST_FX -> false;
      case Param.LOCAL_PITCH_ADJUST -> source != PatchSource.X.ordinal();
      case Param.GLOBAL_VOLUME_POST_REVERB_SEND -> source == PatchSource.SIDECHAIN.ordinal();
      default -> true;
    };
  }

  /**
   * Fold the stored knob into a multiplicative cable combination. (patcher.cpp combineCablesLinear
   * + cableToLinearParamWithoutRangeAdjustment)
   */
  public static int combineCablesLinear(Destination dest, int knobValue, int[] sourceValues) {
    int runningTotal = 536870912; // "1" in Q30
    int range = Functions.getParamRange(dest.paramId);
    // First fold: the stored knob value (treated as a cable)
    runningTotal = cableToLinearParamWithoutRangeAdjustment(runningTotal, knobValue, range);
    // Then each cable
    for (PatchCable cable : dest.cables) {
      // C sound.cpp:1346-1348 — ignore disallowed local-to-global cables or out-of-range sources.
      if (!maySourcePatchToParam(cable.source, dest.paramId)
          || cable.source >= sourceValues.length) {
        continue;
      }
      int srcVal = sourceValues[cable.source];
      srcVal = cable.toPolarity(srcVal);
      runningTotal = cableToLinearParam(runningTotal, srcVal, cable.amount, cable.rangeValue);
    }
    return runningTotal - 536870912; // return in [-2^29, 3*2^29]
  }

  // ── combineCablesExp (patcher.cpp:220-245) ──

  static int combineCablesExp(Destination dest, int knobValue, int[] sourceValues) {
    int runningTotal = 0;
    // Cables first. Exp cables take the *modified* (per-param squared) strength (patcher.cpp:253).
    for (PatchCable cable : dest.cables) {
      if (!maySourcePatchToParam(cable.source, dest.paramId)
          || cable.source >= sourceValues.length) {
        continue;
      }
      int srcVal = sourceValues[cable.source];
      srcVal = cable.toPolarity(srcVal);
      runningTotal =
          cableToExpParam(
              runningTotal,
              srcVal,
              getModifiedPatchCableAmount(cable, dest.paramId),
              cable.rangeValue);
    }
    // Wave index hack: stretch twice as far
    if (dest.paramId == Param.LOCAL_OSC_A_WAVE_INDEX
        || dest.paramId == Param.LOCAL_OSC_B_WAVE_INDEX) {
      runningTotal <<= 1;
    }
    // Fold the knob last
    int range = Functions.getParamRange(dest.paramId);
    runningTotal = cableToExpParamWithoutRangeAdjustment(runningTotal, knobValue, range);
    return runningTotal;
  }

  // ── cableToLinearParam* (patcher.cpp:143-175) ──

  private static int cableToLinearParamWithoutRangeAdjustment(
      int runningTotal, int source, int strength) {
    int scaledSource = Functions.multiply_32x32_rshift32(source, strength);
    int madePositive = scaledSource + 536870912; // 0 to 2^30
    int preLimits = Functions.multiply_32x32_rshift32(runningTotal, madePositive);
    return Functions.lshiftAndSaturate(preLimits, 3);
  }

  private static int cableToLinearParam(
      int runningTotal, int source, int strength, int rangeValue) {
    int scaledSource = Functions.multiply_32x32_rshift32(source, strength);
    scaledSource = applyRangeAdjustment(scaledSource, rangeValue);
    int madePositive = scaledSource + 536870912;
    int preLimits = Functions.multiply_32x32_rshift32(runningTotal, madePositive);
    return Functions.lshiftAndSaturate(preLimits, 3);
  }

  // ── cableToExpParam* (patcher.cpp:177-195) ──

  // C (patcher.cpp:164-173): both exp accumulators use PLAIN wrapping addition, not a
  // saturating add — saturating here diverges exactly when strong cables push a combo past
  // int range, which is when hardware wraps.
  private static int cableToExpParamWithoutRangeAdjustment(
      int runningTotal, int source, int strength) {
    return runningTotal + Functions.multiply_32x32_rshift32(source, strength);
  }

  private static int cableToExpParam(int runningTotal, int source, int strength, int rangeValue) {
    int scaledSource = Functions.multiply_32x32_rshift32(source, strength);
    scaledSource = applyRangeAdjustment(scaledSource, rangeValue);
    return runningTotal + scaledSource;
  }

  // ── applyRangeAdjustment (patcher.cpp) ──

  private static int applyRangeAdjustment(int value, int rangeValue) {
    int small = Functions.multiply_32x32_rshift32(value, rangeValue);
    return Functions.signed_saturate(small, 32 - 5) << 3;
  }

  // ── recalculateFinalValueForParamWithNoCables (patcher.cpp:30-62) ──

  /**
   * Port of recalculateFinalValueForParamWithNoCables. Runs an uncabled param's stored knob through
   * the correct firmware curve. (patcher.cpp:30-62)
   */
  public static void recalculateFinalValueForParamWithNoCables(
      int p, Sound sound, int[] sourceValues, int[] paramFinalValues) {
    // Uses which ever cable combine is appropriate for this param
    int cableCombination;
    if (p < Param.FIRST_LOCAL_HYBRID
        || (p >= Param.FIRST_GLOBAL && p < Param.FIRST_GLOBAL_HYBRID)) {
      // Linear family: fold knob through combineCablesLinear(nullptr)
      int runningTotal = 536870912;
      int range = Functions.getParamRange(p);
      runningTotal =
          cableToLinearParamWithoutRangeAdjustment(
              runningTotal, sound.getSmoothedPatchedParamValue(p), range);
      cableCombination = runningTotal - 536870912;
    } else {
      // Exp family: fold knob through combineCablesExp(nullptr)
      int range = Functions.getParamRange(p);
      cableCombination =
          cableToExpParamWithoutRangeAdjustment(0, sound.getSmoothedPatchedParamValue(p), range);
    }

    // C (patcher.cpp:37): param_neutral_value = paramNeutralValues[p] = getParamNeutralValue(p)
    // (static per-param constant). The knob is already folded into cableCombination above.
    int neutralValue = Functions.getParamNeutralValue(p);
    int finalValue;
    if (p < Param.FIRST_LOCAL_HYBRID
        || (p >= Param.FIRST_GLOBAL && p < Param.FIRST_GLOBAL_HYBRID)) {
      if (p < Param.FIRST_LOCAL_NON_VOLUME
          || (p >= Param.FIRST_GLOBAL && p < Param.FIRST_GLOBAL_NON_VOLUME)) {
        finalValue = Functions.getFinalParameterValueVolume(neutralValue, cableCombination);
      } else {
        finalValue = Functions.getFinalParameterValueLinear(neutralValue, cableCombination);
      }
    } else {
      if (p < Param.FIRST_LOCAL_EXP || (p >= Param.FIRST_GLOBAL && p < Param.FIRST_GLOBAL_EXP)) {
        finalValue = Functions.getFinalParameterValueHybrid(neutralValue, cableCombination);
      } else {
        finalValue =
            Functions.getFinalParameterValueExpWithDumbEnvelopeHack(
                neutralValue, cableCombination, p);
      }
    }

    paramFinalValues[p] = finalValue;
  }

  // ── combineCablesLinearForRangeParam (patcher.cpp:175-208) ──

  /**
   * Port of combineCablesLinearForRangeParam. For range-type destinations that scale a source value
   * rather than a param. (patcher.cpp:175-208)
   */
  public static int combineCablesLinearForRangeParam(
      Destination destination, int[] sourceValues, ParamManager paramManager) {
    int runningTotal = 536870912;
    int targetParamId =
        destination.targetSource >= 0 ? destination.targetParamId : destination.paramId;
    for (PatchCable cable : destination.cables) {
      // C patcher.cpp:175-202, source_selection.cpp:269-283 — range-adjusting cables allow any
      // valid global source on global/local cables and any valid local source on local cables
      // (without imposing the direct destination param's source restrictions, e.g. ENVELOPE ->
      // (LFO -> LOCAL_VOLUME) or LFO_GLOBAL -> (SIDECHAIN -> GLOBAL_VOLUME_POST_REVERB_SEND)).
      if (cable.source < 0
          || cable.source >= PatchSource.kNumPatchSources
          || cable.source >= sourceValues.length
          || (targetParamId >= Param.FIRST_GLOBAL
              && cable.source >= PatchSource.kFirstLocalSource)) {
        continue;
      }
      int srcVal = sourceValues[cable.source];
      // Special exception: If we're patching aftertouch to range
      if (cable.source == PatchSource.AFTERTOUCH.ordinal()) {
        srcVal = (srcVal - 1073741824) << 1;
      } else {
        srcVal = cable.toPolarity(srcVal);
      }
      int strength = cable.amount;
      runningTotal = cableToLinearParamWithoutRangeAdjustment(runningTotal, srcVal, strength);
    }
    return runningTotal - 536870912;
  }

  /**
   * getModifiedPatchCableAmount (patch_cable_set.cpp:500-534). For pitch-adjust + delay-rate
   * params, square the cable strength so it slopes up slowly (small effects accessible). Used only
   * by exp cables (combineCablesExp). {@code amount} is cable.amount (the fw2 stand-in for the C
   * {@code patch_cable.param.getCurrentValue()}; automation smoothing is a separate gap).
   */
  public static int getModifiedPatchCableAmount(PatchCable cable, int p) {
    int amount = cable.amount;
    switch (p) {
      case Param.LOCAL_PITCH_ADJUST:
      case Param.LOCAL_OSC_A_PITCH_ADJUST:
      case Param.LOCAL_OSC_B_PITCH_ADJUST:
      case Param.LOCAL_MODULATOR_0_PITCH_ADJUST:
      case Param.LOCAL_MODULATOR_1_PITCH_ADJUST:
      case Param.GLOBAL_DELAY_RATE:
        int output = (amount >> 15) * (amount >> 16);
        if (amount < 0) {
          output = -output;
        }
        if (p == Param.LOCAL_PITCH_ADJUST) {
          if (cable.source == PatchSource.VELOCITY.ordinal()) {
            output = Functions.multiply_32x32_rshift32_rounded(output, 1431655765) << 1; // /3*2
          } else {
            // Divides by sqrt(2): 3 octaves of shifting rather than 4.
            output = Functions.multiply_32x32_rshift32_rounded(output, 1518500250) << 1;
          }
        }
        return output;
      default:
        return amount;
    }
  }

  /** Minimal ParamManager stub for combineCablesLinearForRangeParam. */
  public static class ParamManager {}

  // ── performInitialPatching (patcher.cpp:275) ──

  /**
   * Port of performInitialPatching (patcher.cpp:275-339): every param is first computed as if
   * unpatched, then the params that DO have cables are overwritten with the real cable combination
   * (using the source values the caller set up beforehand — note, velocity, random, local LFOs,
   * globals). The overwrite pass is the same per-destination combine+finalize the per-block
   * performPatching does.
   */
  public static void performInitialPatching(
      Sound sound, int[] sourceValues, int[] paramFinalValues) {
    for (int p = 0; p < Param.kNumParams; p++) {
      recalculateFinalValueForParamWithNoCables(p, sound, sourceValues, paramFinalValues);
    }
    performPatching(sound, sourceValues, sound.patchCableSet, paramFinalValues);
  }

  // ── getEnvStage (envelope rate dispatching) ──

  /**
   * Convenience: curve-apply a single param's knob value (no cables), returning the final param
   * value. C: {@code recalculateFinalValueForParamWithNoCables} with no destination.
   * (patcher.cpp:30-57)
   */
  public static int computeFinalValueForParam(int p, int knobValue) {
    int cableCombination;
    if (p < Param.FIRST_LOCAL_HYBRID
        || (p >= Param.FIRST_GLOBAL && p < Param.FIRST_GLOBAL_HYBRID)) {
      int runningTotal = 536870912;
      int range = Functions.getParamRange(p);
      runningTotal = cableToLinearParamWithoutRangeAdjustment(runningTotal, knobValue, range);
      cableCombination = runningTotal - 536870912;
    } else {
      int range = Functions.getParamRange(p);
      cableCombination = cableToExpParamWithoutRangeAdjustment(0, knobValue, range);
    }

    int neutral =
        Functions.getParamNeutralValue(p); // C: paramNeutralValues[p]; knob is in cableCombination
    if (p < Param.FIRST_LOCAL_HYBRID
        || (p >= Param.FIRST_GLOBAL && p < Param.FIRST_GLOBAL_HYBRID)) {
      if (p < Param.FIRST_LOCAL_NON_VOLUME
          || (p >= Param.FIRST_GLOBAL && p < Param.FIRST_GLOBAL_NON_VOLUME)) {
        return Functions.getFinalParameterValueVolume(neutral, cableCombination);
      } else {
        return Functions.getFinalParameterValueLinear(neutral, cableCombination);
      }
    } else {
      if (p < Param.FIRST_LOCAL_EXP || (p >= Param.FIRST_GLOBAL && p < Param.FIRST_GLOBAL_EXP)) {
        return Functions.getFinalParameterValueHybrid(neutral, cableCombination);
      } else {
        return Functions.getFinalParameterValueExpWithDumbEnvelopeHack(
            neutral, cableCombination, p);
      }
    }
  }

  private static int getEnvStage(int p) {
    if (p >= Param.LOCAL_ENV_0_ATTACK && p <= Param.LOCAL_ENV_3_ATTACK) return 0; // attack
    if (p >= Param.LOCAL_ENV_0_DECAY && p <= Param.LOCAL_ENV_3_DECAY) return 1; // decay
    if (p >= Param.LOCAL_ENV_0_RELEASE && p <= Param.LOCAL_ENV_3_RELEASE) return 2; // release
    return -1;
  }
}
