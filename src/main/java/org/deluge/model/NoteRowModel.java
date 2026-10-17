package org.deluge.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
// We will relocate or import temporarily
// We will import our new model one shortly
import org.deluge.modulation.params.ParamManager;

/**
 * Unified NoteRow model representing both structured document note rows (with pitch, parameter
 * overrides, and automation curves) and runtime sequencer note-triggering pipelines.
 */
public class NoteRowModel {
  private int pitch;
  public int y; // Alias for pitch to compile-protect tests
  private boolean muted = false;
  private boolean mutedBeforeStemExport = false;
  private boolean exportStem = false;

  public List<NoteModel> notes = new ArrayList<>();
  private Map<String, Float> soundParams = new HashMap<>();
  private Map<String, List<org.deluge.modulation.automation.ParamNode>> automationCurves =
      new HashMap<>();
  private Map<String, float[]> rowAutomation = new HashMap<>();

  // ── Transient Transport / Sequencer States ──
  private transient int loopLengthIfIndependent = 0;
  private transient int lastProcessedPosIfIndependent = 0;
  private transient int repeatCountIfIndependent = 0;
  private transient boolean currentlyPlayingReversedIfIndependent = false;
  private transient boolean reverseStartAtZeroPending = false;
  private transient int ignoreNoteOnsBefore_ = 0;
  private transient ParamManager paramManager = new ParamManager();
  private transient Runnable onScheduleInvalidated;

  void setOnScheduleInvalidated(Runnable onScheduleInvalidated) {
    this.onScheduleInvalidated = onScheduleInvalidated;
  }

  private void invalidateSchedule() {
    if (onScheduleInvalidated != null) {
      onScheduleInvalidated.run();
    }
  }

  void copyLiveTransportFrom(NoteRowModel live) {
    this.lastProcessedPosIfIndependent = live.lastProcessedPosIfIndependent;
    this.repeatCountIfIndependent = live.repeatCountIfIndependent;
    this.currentlyPlayingReversedIfIndependent = live.currentlyPlayingReversedIfIndependent;
    this.reverseStartAtZeroPending = live.reverseStartAtZeroPending;
  }

  public int getLastProcessedPosIfIndependent() {
    return lastProcessedPosIfIndependent;
  }

  /**
   * C NoteRow::hasIndependentPlayPos (note_row.cpp:4335-4337). In the C, this is also true when a
   * row overrides its sequence direction; this model has no per-row direction, so only the
   * independent-length half applies.
   */
  public boolean hasIndependentPlayPos() {
    return loopLengthIfIndependent != 0;
  }

  /** C ModelStackWithNoteRow::isCurrentlyPlayingReversed (model_stack.cpp:64-73). */
  public boolean isCurrentlyPlayingReversed(boolean clipCurrentlyPlayingReversed) {
    return hasIndependentPlayPos()
        ? currentlyPlayingReversedIfIndependent
        : clipCurrentlyPlayingReversed;
  }

  /**
   * C ModelStackWithNoteRow::getRepeatCount (model_stack.cpp:102-109). The C's
   * hasIndependentPlayPos (note_row.cpp:4374-4376) is also true for a row with its own sequence
   * direction; this model has no per-row direction, so only the independent-length half applies.
   */
  public int getRepeatCount(int clipRepeatCount) {
    if (loopLengthIfIndependent != 0) {
      return repeatCountIfIndependent;
    } else {
      return clipRepeatCount;
    }
  }

  /**
   * The note-row half of InstrumentClip::setPos (instrument_clip.cpp:405-436). This model has no
   * per-row sequence direction, so the effective mode is always the clip's (the C's OBEY_PARENT,
   * note_row.cpp:2022-2029) and only the independent-length half of hasIndependentPlayPos
   * (note_row.cpp:4335-4337) applies.
   */
  public void setPosIfIndependent(int newPos, SequenceDirection effectiveSequenceDirectionMode) {
    if (loopLengthIfIndependent == 0) {
      return;
    }
    int effectiveLoopLength = loopLengthIfIndependent;

    // The below basically mirrors the code / logic in Clip::setPos()
    repeatCountIfIndependent = Integer.divideUnsigned(newPos, effectiveLoopLength);

    currentlyPlayingReversedIfIndependent =
        (effectiveSequenceDirectionMode == SequenceDirection.REVERSE
            || (effectiveSequenceDirectionMode == SequenceDirection.PINGPONG
                && (repeatCountIfIndependent & 1) != 0));

    lastProcessedPosIfIndependent = newPos - repeatCountIfIndependent * effectiveLoopLength;
    reverseStartAtZeroPending = false;
    if (currentlyPlayingReversedIfIndependent) {
      if (lastProcessedPosIfIndependent != 0) {
        lastProcessedPosIfIndependent = effectiveLoopLength - lastProcessedPosIfIndependent;
      } else {
        repeatCountIfIndependent--;
        reverseStartAtZeroPending = true;
      }
    }
  }

  /**
   * The per-row half of InstrumentClip::incrementPos (instrument_clip.cpp:4738-4747): advances
   * {@code lastProcessedPosIfIndependent} by {@code numTicks}, negated when this row is currently
   * playing in reverse.
   */
  public void incrementPosIfIndependent(int numTicks) {
    if (!hasIndependentPlayPos()) {
      return;
    }
    if (numTicks > 0 && reverseStartAtZeroPending) {
      // C runs processCurrentPos(0) at pos 0 before the first non-zero increment, which
      // increments repeatCountIfIndependent from -1 to 0 (note_row.cpp:2084-2085).
      repeatCountIfIndependent++;
      reverseStartAtZeroPending = false;
    }
    int movement = currentlyPlayingReversedIfIndependent ? -numTicks : numTicks;
    lastProcessedPosIfIndependent += movement;
  }

  /**
   * Advances {@code lastProcessedPosIfIndependent} for quiet ticks flushed outside {@link
   * #processCurrentPos}, normalizing negative reverse positions into {@code [0,
   * loopLengthIfIndependent)}.
   */
  void flushIndependentPos(int numTicks) {
    incrementPosIfIndependent(numTicks);
    if (loopLengthIfIndependent > 0
        && currentlyPlayingReversedIfIndependent
        && lastProcessedPosIfIndependent < 0) {
      lastProcessedPosIfIndependent =
          Math.floorMod(lastProcessedPosIfIndependent, loopLengthIfIndependent);
    }
  }

  /**
   * Mirrors {@code lastProcessedPosIfIndependent} when the parent clip changes direction while this
   * row obeys the parent (C NoteRow::setSequenceDirectionMode, note_row.cpp:4453-4480).
   */
  public void syncDirectionWithClip(SequenceDirection clipDirectionMode) {
    if (!hasIndependentPlayPos()) {
      return;
    }
    if (clipDirectionMode != SequenceDirection.PINGPONG) {
      boolean reversedBefore = currentlyPlayingReversedIfIndependent;
      currentlyPlayingReversedIfIndependent = (clipDirectionMode == SequenceDirection.REVERSE);
      if (reversedBefore != currentlyPlayingReversedIfIndependent
          && lastProcessedPosIfIndependent != 0) {
        lastProcessedPosIfIndependent = loopLengthIfIndependent - lastProcessedPosIfIndependent;
      }
    }
  }

  public NoteRowModel(int pitch) {
    this.pitch = pitch;
    this.y = pitch;
  }

  public int getPitch() {
    return pitch;
  }

  public void setPitch(int pitch) {
    this.pitch = pitch;
    this.y = pitch;
  }

  public int getNoteCode() {
    return pitch;
  }

  public boolean isMuted() {
    return muted;
  }

  public void setMuted(boolean muted) {
    this.muted = muted;
    invalidateSchedule();
  }

  public boolean isMutedBeforeStemExport() {
    return mutedBeforeStemExport;
  }

  public void setMutedBeforeStemExport(boolean muted) {
    this.mutedBeforeStemExport = muted;
  }

  public boolean isExportStem() {
    return exportStem;
  }

  public void setExportStem(boolean exportStem) {
    this.exportStem = exportStem;
  }

  public List<NoteModel> getNotes() {
    return notes;
  }

  public void setNotes(List<NoteModel> notes) {
    this.notes = notes;
    invalidateSchedule();
  }

  public Map<String, Float> getSoundParams() {
    return soundParams;
  }

  public Map<String, List<org.deluge.modulation.automation.ParamNode>> getAutomationCurves() {
    return automationCurves;
  }

  public Map<String, float[]> getRowAutomation() {
    return rowAutomation;
  }

  // --- Transport Setup ---

  public int getLoopLengthIfIndependent() {
    return loopLengthIfIndependent;
  }

  public void setLoopLengthIfIndependent(int len) {
    this.loopLengthIfIndependent = len;
    invalidateSchedule();
  }

  public ParamManager getParamManager() {
    return paramManager;
  }

  // --- Note Manipulation Parity ---

  public boolean hasNoNotes() {
    return notes.isEmpty();
  }

  public int getNumNotes() {
    return notes.size();
  }

  public int attemptNoteAdd(
      int pos,
      int length,
      int velocity,
      int probability,
      org.deluge.model.Iterance iterance,
      int fill) {
    NoteModel note = new NoteModel();
    note.setPos(pos);
    note.setLength(length);
    note.setVelocity(velocity);
    note.setProbability(probability);
    note.setIterance(iterance);
    note.setFill(fill);
    notes.add(note);
    invalidateSchedule();
    return 0;
  }

  public void quantize(int increment, int amount) {
    if (notes.isEmpty()) return;

    int halfIncrement = increment / 2;
    int lastPos = Integer.MIN_VALUE;

    List<NoteModel> newNotes = new ArrayList<>();

    for (NoteModel note : notes) {
      int destination = ((note.getPos() - 1 + halfIncrement) / increment) * increment;
      if (amount < 0) { // Humanize
        int hmAmount = (int) (Math.random() * halfIncrement / 2) - (increment / 100);
        destination = note.getPos() + hmAmount;
      }
      int distance = destination - note.getPos();
      distance = (distance * Math.abs(amount)) / 100;
      int newPos = note.getPos() + distance;

      if (newPos != lastPos) {
        NoteModel writeNote = new NoteModel();
        writeNote.setPos(newPos);
        writeNote.setLength(note.getLength());
        writeNote.setVelocity(note.getVelocityByte());
        writeNote.setProbabilityValue(note.getProbabilityValue());
        writeNote.setIterance(note.getIterance());
        writeNote.setFill(note.getFill());
        newNotes.add(writeNote);
      }
      lastPos = newPos;
    }

    notes.clear();
    notes.addAll(newNotes);
    invalidateSchedule();
  }

  // ── Sequencer Playback Tick Logic ──

  public void resumePlayback(int currentPos, int loopLength, boolean mayMakeSound) {
    if (mayMakeSound && !muted && !notes.isEmpty()) {
      for (NoteModel note : notes) {
        int noteEnd = note.getPos() + note.getLength();
        if (currentPos > note.getPos() && currentPos < noteEnd) {
          // Trigger late catch notes (handled by voice allocation)
        }
      }
    }
    ignoreNoteOnsBefore_ = 0;
  }

  public int processCurrentPos(
      int ticksSinceLast,
      List<org.deluge.model.PendingNoteOn> pendingNoteOns,
      List<Integer> pendingNoteOffs,
      int clipLastProcessedPos,
      int clipLoopLength,
      boolean clipCurrentlyPlayingReversed,
      Object sound) {
    return processCurrentPos(
        ticksSinceLast,
        pendingNoteOns,
        pendingNoteOffs,
        clipLastProcessedPos,
        clipLoopLength,
        clipCurrentlyPlayingReversed,
        false,
        SequenceDirection.FORWARD,
        sound);
  }

  public int processCurrentPos(
      int ticksSinceLast,
      List<org.deluge.model.PendingNoteOn> pendingNoteOns,
      List<Integer> pendingNoteOffs,
      int clipLastProcessedPos,
      int clipLoopLength,
      boolean clipCurrentlyPlayingReversed,
      boolean clipDidPingpong,
      SequenceDirection clipSequenceDirectionMode,
      Object sound) {

    int effectiveLength = (loopLengthIfIndependent > 0) ? loopLengthIfIndependent : clipLoopLength;
    if (effectiveLength <= 0) return Integer.MAX_VALUE;
    boolean playingReversedNow =
        (loopLengthIfIndependent > 0)
            ? currentlyPlayingReversedIfIndependent
            : clipCurrentlyPlayingReversed;
    boolean didPingpong = (loopLengthIfIndependent > 0) ? false : clipDidPingpong;

    // C note_row.cpp:2072-2117 — independent-row wrap and ping-pong.
    if (loopLengthIfIndependent > 0) {
      if (playingReversedNow) {
        if (lastProcessedPosIfIndependent < 0) {
          lastProcessedPosIfIndependent += effectiveLength;
        }
        if (lastProcessedPosIfIndependent == 0) {
          repeatCountIfIndependent++;
          reverseStartAtZeroPending = false;
          if (clipSequenceDirectionMode == SequenceDirection.PINGPONG) {
            lastProcessedPosIfIndependent = -lastProcessedPosIfIndependent;
            currentlyPlayingReversedIfIndependent = playingReversedNow = !playingReversedNow;
            didPingpong = true;
          }
        }
      } else {
        int ticksTilEnd = effectiveLength - lastProcessedPosIfIndependent;
        if (ticksTilEnd <= 0) {
          lastProcessedPosIfIndependent -= effectiveLength;
          repeatCountIfIndependent++;
          if (clipSequenceDirectionMode == SequenceDirection.PINGPONG) {
            if (lastProcessedPosIfIndependent > 0) {
              lastProcessedPosIfIndependent = effectiveLength - lastProcessedPosIfIndependent;
            }
            currentlyPlayingReversedIfIndependent = playingReversedNow = !playingReversedNow;
            didPingpong = true;
          }
        }
      }
    }

    int currentPos =
        (loopLengthIfIndependent > 0) ? lastProcessedPosIfIndependent : clipLastProcessedPos;

    if (paramManager.mightContainAutomation()) {
      paramManager.processCurrentPos(
          currentPos, effectiveLength, playingReversedNow, didPingpong, true);
    }

    if (muted) {
      // C note_row.cpp:2155-2164 — keep waking at the end of an independent row even when muted.
      if (loopLengthIfIndependent > 0) {
        int ticksTilEnd =
            playingReversedNow
                ? (currentPos == 0 ? effectiveLength : currentPos)
                : (effectiveLength - currentPos);
        return Math.min(ticksTilEnd, paramManager.ticksTilNextEvent);
      }
      return Integer.MAX_VALUE;
    }

    boolean playingReversedDuringStep = playingReversedNow ^ didPingpong;
    boolean allowNoteTails =
        (playingReversedDuringStep || playingReversedNow) && allowsNoteTails(sound, pitch);
    int ticksTilNextNoteEvent = Integer.MAX_VALUE;
    int startTick =
        playingReversedDuringStep
            ? Math.floorMod(currentPos, effectiveLength)
            : Math.floorMod(currentPos - ticksSinceLast, effectiveLength);
    boolean steppedLeftFromZero =
        playingReversedDuringStep && (currentPos + ticksSinceLast >= effectiveLength);

    for (NoteModel note : notes) {
      boolean reverseTailedDuringStep =
          playingReversedDuringStep && allowNoteTails && note.getLength() > 0;
      int triggerPos =
          reverseTailedDuringStep
              ? Math.floorMod(note.getPos() + note.getLength(), effectiveLength)
              : note.getPos();
      int releasePos =
          reverseTailedDuringStep
              ? note.getPos()
              : Math.floorMod(note.getPos() + note.getLength(), effectiveLength);

      if (isTickReached(startTick, ticksSinceLast, releasePos, effectiveLength)) {
        pendingNoteOffs.add(pitch);
      }
      // C note_row.cpp:2308-2317, 2350-2361 — a reverse note-on whose edge lands on 0 (either a
      // full-bar tailed note ending at effectiveLength or a one-shot at pos 0) fires when stepping
      // left from 0 at the start of the reverse pass, before pos 0 wraps repeatCount at pass end.
      boolean triggerReached =
          (playingReversedDuringStep && triggerPos == 0)
              ? (ticksSinceLast > 0 && steppedLeftFromZero)
              : isTickReached(startTick, ticksSinceLast, triggerPos, effectiveLength);
      if (triggerReached) {
        if (currentPos >= ignoreNoteOnsBefore_) {
          boolean legato = false;
          if (sound instanceof org.deluge.engine.FirmwareSound) {
            legato = ((org.deluge.engine.FirmwareSound) sound).fw2Sound.noteIsOn(pitch, true);
          }

          if (!legato) {
            pendingNoteOns.add(
                new org.deluge.model.PendingNoteOn(
                    this,
                    note.getVelocityByte(),
                    note.getProbabilityValue(), // C note_row.cpp:2554-2555
                    note.getIterance(),
                    note.getFill()));
          }
        }
      }

      boolean reverseTailedNow = playingReversedNow && allowNoteTails && note.getLength() > 0;
      int triggerPosNow =
          reverseTailedNow
              ? Math.floorMod(note.getPos() + note.getLength(), effectiveLength)
              : note.getPos();
      int releasePosNow =
          reverseTailedNow
              ? note.getPos()
              : Math.floorMod(note.getPos() + note.getLength(), effectiveLength);

      int distTrigger;
      if (playingReversedNow) {
        distTrigger =
            (triggerPosNow == 0)
                ? (currentPos == 0 ? 1 : currentPos + 1)
                : currentPos - triggerPosNow;
      } else {
        // In forward mode isTickReached checks [currentPos - ticksSinceLast, currentPos), so an
        // event at target fires when currentPos steps past target to target + 1.
        distTrigger = (triggerPosNow + 1) - currentPos;
      }
      if (distTrigger <= 0) distTrigger += effectiveLength;

      int distRelease =
          playingReversedNow ? (currentPos - releasePosNow) : ((releasePosNow + 1) - currentPos);
      if (distRelease <= 0) distRelease += effectiveLength;

      int dist = Math.min(distTrigger, distRelease);
      if (dist < ticksTilNextNoteEvent) {
        ticksTilNextNoteEvent = dist;
      }
    }

    // C note_row.cpp:2155-2164 — wake at the end of an independent row's own loop.
    if (loopLengthIfIndependent > 0) {
      int ticksTilRowEnd =
          playingReversedNow
              ? (currentPos == 0 ? effectiveLength : currentPos)
              : (effectiveLength - currentPos);
      if (ticksTilRowEnd < ticksTilNextNoteEvent) {
        ticksTilNextNoteEvent = ticksTilRowEnd;
      }
    }

    return Math.min(ticksTilNextNoteEvent, paramManager.ticksTilNextEvent);
  }

  // C InstrumentClip::allowNoteTails (instrument_clip.cpp:3350-3371) ->
  // Sound::allowNoteTails (sound.cpp:1927-1964).
  private static boolean allowsNoteTails(Object sound, int pitch) {
    if (sound instanceof org.deluge.engine.FirmwareSound fs) {
      fs.syncParamsToFw2();
      return fs.fw2Sound.allowNoteTails(false);
    }
    if (sound instanceof org.deluge.engine.FirmwareKit kit) {
      if (pitch >= 0 && pitch < kit.drumSounds.size()) {
        org.deluge.engine.FirmwareSound drum = kit.drumSounds.get(pitch);
        drum.syncParamsToFw2();
        return drum.fw2Sound.allowNoteTails(false);
      }
      return true;
    }
    return true;
  }

  private static boolean isTickReached(
      int startTick, int ticksSinceLast, int target, int effectiveLength) {
    if (ticksSinceLast <= 0) return false;
    int endTick = (startTick + ticksSinceLast) % effectiveLength;
    if (startTick < endTick) {
      return startTick <= target && target < endTick;
    } else {
      return target >= startTick || target < endTick;
    }
  }
}
