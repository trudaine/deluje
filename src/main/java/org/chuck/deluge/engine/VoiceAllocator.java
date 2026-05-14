package org.chuck.deluge.engine;

import org.chuck.deluge.BridgeContract;

/**
 * Polyphonic voice manager for NativeJavaSequencer.
 *
 * <p>Replaces the naive first-available scan with proper voice allocation:
 *
 * <ul>
 *   <li>Per-track voice pools (configurable via {@link BridgeContract#G_POLYPHONY})
 *   <li>Voice stealing (oldest note when pool exhausted)
 *   <li>Three priority modes: newest, oldest, highest MIDI note
 *   <li>First-available fallback (matches original behavior)
 * </ul>
 *
 * <p>Voice slots are partitioned across tracks. Each slot holds a reference to its owning voice
 * (synth, sample, or DX7) and metadata for stealing decisions.
 */
public class VoiceAllocator {

  public enum Priority {
    NEWEST,
    OLDEST,
    HIGHEST_NOTE
  }

  /** Maximum total voice slots across all types. */
  public static final int MAX_SYNTH_SLOTS = 32;

  public static final int MAX_SAMPLE_SLOTS = 32;
  public static final int MAX_DX7_SLOTS = 16;

  /** Represents one allocated voice slot. */
  public static final class Slot {
    public int track = -1;
    public int midiNote = 60;
    public long allocTick = 0;
    public boolean active = false;

    void claim(int track, int midiNote, long tick) {
      this.track = track;
      this.midiNote = midiNote;
      this.allocTick = tick;
      this.active = true;
    }

    void release() {
      this.active = false;
      this.track = -1;
    }
  }

  private final Slot[] synthSlots = new Slot[MAX_SYNTH_SLOTS];
  private final Slot[] sampleSlots = new Slot[MAX_SAMPLE_SLOTS];
  private final Slot[] dx7Slots = new Slot[MAX_DX7_SLOTS];

  private final int[] trackSynthStart = new int[BridgeContract.TRACKS];
  private final int[] trackSynthEnd = new int[BridgeContract.TRACKS];
  private final int[] trackSampleStart = new int[BridgeContract.TRACKS];
  private final int[] trackSampleEnd = new int[BridgeContract.TRACKS];
  private final int[] trackDx7Start = new int[BridgeContract.TRACKS];
  private final int[] trackDx7End = new int[BridgeContract.TRACKS];

  private Priority priority = Priority.NEWEST;
  private long globalTick = 0;
  private final BridgeContract bridge;

  public VoiceAllocator(BridgeContract bridge) {
    this.bridge = bridge;
    for (int i = 0; i < MAX_SYNTH_SLOTS; i++) synthSlots[i] = new Slot();
    for (int i = 0; i < MAX_SAMPLE_SLOTS; i++) sampleSlots[i] = new Slot();
    for (int i = 0; i < MAX_DX7_SLOTS; i++) dx7Slots[i] = new Slot();
    computePartitioning();
  }

  public void setPriority(Priority p) {
    this.priority = p;
  }

  /** Advance the global tick counter (called once per audio buffer or step). */
  public void tick() {
    globalTick++;
  }

  /** Recompute per-track partitioning based on G_POLYPHONY array. Resets all slots. */
  public final void computePartitioning() {
    int[] weights = new int[BridgeContract.TRACKS];
    int totalWeight = 0;
    for (int t = 0; t < BridgeContract.TRACKS; t++) {
      int p = bridge.getPolyphony(t);
      weights[t] = Math.max(1, p);
      totalWeight += weights[t];
    }

    int nextSynth = 0, nextSample = 0, nextDx7 = 0;
    for (int t = 0; t < BridgeContract.TRACKS; t++) {
      int w = weights[t];
      int synthCount = Math.max(1, w * MAX_SYNTH_SLOTS / Math.max(1, totalWeight));
      int sampleCount = Math.max(1, w * MAX_SAMPLE_SLOTS / Math.max(1, totalWeight));
      int dx7Count = Math.max(1, w * MAX_DX7_SLOTS / Math.max(1, totalWeight));

      trackSynthStart[t] = nextSynth;
      trackSynthEnd[t] = Math.min(nextSynth + synthCount, MAX_SYNTH_SLOTS);
      nextSynth = trackSynthEnd[t];

      trackSampleStart[t] = nextSample;
      trackSampleEnd[t] = Math.min(nextSample + sampleCount, MAX_SAMPLE_SLOTS);
      nextSample = trackSampleEnd[t];

      trackDx7Start[t] = nextDx7;
      trackDx7End[t] = Math.min(nextDx7 + dx7Count, MAX_DX7_SLOTS);
      nextDx7 = trackDx7End[t];
    }

    // Release slots outside new partition ranges (they got reassigned)
    for (int t = 0; t < BridgeContract.TRACKS; t++) {
      for (int i = trackSynthStart[t]; i < trackSynthEnd[t]; i++) synthSlots[i].track = t;
      for (int i = trackSampleStart[t]; i < trackSampleEnd[t]; i++) sampleSlots[i].track = t;
      for (int i = trackDx7Start[t]; i < trackDx7End[t]; i++) dx7Slots[i].track = t;
    }
  }

  /**
   * Allocate a synth voice for the given track.
   *
   * @return the Slot index, or -1 if none available (caller should skip)
   */
  public int allocSynth(int track, int midiNote) {
    return allocSlot(track, midiNote, synthSlots, trackSynthStart, trackSynthEnd);
  }

  /**
   * Allocate a sample voice for the given track.
   *
   * @return the Slot index, or -1 if none available
   */
  public int allocSample(int track, int midiNote) {
    return allocSlot(track, midiNote, sampleSlots, trackSampleStart, trackSampleEnd);
  }

  /**
   * Allocate a DX7 voice for the given track.
   *
   * @return the Slot index, or -1 if none available
   */
  public int allocDx7(int track, int midiNote) {
    return allocSlot(track, midiNote, dx7Slots, trackDx7Start, trackDx7End);
  }

  private int allocSlot(int track, int midiNote, Slot[] slots, int[] trackStart, int[] trackEnd) {
    int start = trackStart[track];
    int end = trackEnd[track];

    // 1. First available within track pool
    for (int i = start; i < end; i++) {
      if (!slots[i].active) {
        slots[i].claim(track, midiNote, globalTick);
        return i;
      }
    }

    // 2. Steal from track pool
    int stealIdx = selectStealCandidate(slots, start, end);
    if (stealIdx >= 0) {
      slots[stealIdx].claim(track, midiNote, globalTick);
      return stealIdx;
    }

    // 3. Steal from any pool (exhausted — fallback)
    stealIdx = selectStealCandidate(slots, 0, slots.length);
    if (stealIdx >= 0) {
      slots[stealIdx].claim(track, midiNote, globalTick);
      return stealIdx;
    }

    return -1; // All slots busy and nothing to steal
  }

  private int selectStealCandidate(Slot[] slots, int start, int end) {
    int candidate = -1;
    long oldestTick = Long.MAX_VALUE;
    int highestNote = -1;

    for (int i = start; i < end; i++) {
      if (!slots[i].active) return i; // free slot found

      switch (priority) {
        case NEWEST:
          if (slots[i].allocTick > oldestTick || candidate < 0) {
            oldestTick = slots[i].allocTick;
            candidate = i;
          }
          break;
        case OLDEST:
          if (slots[i].allocTick < oldestTick) {
            oldestTick = slots[i].allocTick;
            candidate = i;
          }
          break;
        case HIGHEST_NOTE:
          if (slots[i].midiNote > highestNote) {
            highestNote = slots[i].midiNote;
            candidate = i;
          }
          break;
      }
    }
    return candidate;
  }

  /** Release a synth voice slot (mark it inactive). */
  public void releaseSynth(int slotIdx) {
    if (slotIdx >= 0 && slotIdx < MAX_SYNTH_SLOTS) synthSlots[slotIdx].release();
  }

  /** Release a sample voice slot. */
  public void releaseSample(int slotIdx) {
    if (slotIdx >= 0 && slotIdx < MAX_SAMPLE_SLOTS) sampleSlots[slotIdx].release();
  }

  /** Release a DX7 voice slot. */
  public void releaseDx7(int slotIdx) {
    if (slotIdx >= 0 && slotIdx < MAX_DX7_SLOTS) dx7Slots[slotIdx].release();
  }

  /** Release all slots for a given track (e.g. on mute/track type change). */
  public void releaseAllForTrack(int track) {
    for (int i = trackSynthStart[track]; i < trackSynthEnd[track]; i++) synthSlots[i].release();
    for (int i = trackSampleStart[track]; i < trackSampleEnd[track]; i++) sampleSlots[i].release();
    for (int i = trackDx7Start[track]; i < trackDx7End[track]; i++) dx7Slots[i].release();
  }

  /** Total active synth voice count. */
  public int activeSynthCount() {
    int c = 0;
    for (Slot s : synthSlots) if (s.active) c++;
    return c;
  }

  /** Total active sample voice count. */
  public int activeSampleCount() {
    int c = 0;
    for (Slot s : sampleSlots) if (s.active) c++;
    return c;
  }

  /** Total active DX7 voice count. */
  public int activeDx7Count() {
    int c = 0;
    for (Slot s : dx7Slots) if (s.active) c++;
    return c;
  }
}
