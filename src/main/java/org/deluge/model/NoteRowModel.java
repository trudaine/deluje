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
  private transient int ignoreNoteOnsBefore_ = 0;
  // C NoteRow::sequenced (note_row.h) — a sequenced note is currently sounding on this row.
  private transient boolean sequenced = false;
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
    if (currentlyPlayingReversedIfIndependent) {
      if (lastProcessedPosIfIndependent != 0) {
        lastProcessedPosIfIndependent = effectiveLoopLength - lastProcessedPosIfIndependent;
      } else {
        repeatCountIfIndependent--;
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
    if (muted != this.muted) {
      // C NoteRow::toggleMute (note_row.cpp:3118-3133): muting stops the sounding note, un-muting
      // resumes playback (a possibly late start). The UI calls this off the audio thread, so the
      // clip acts on it at its next processCurrentPos, which invalidateSchedule() makes the next
      // tick.
      if (muted) {
        stopPending = true;
        resumePending = false;
      } else {
        resumePending = true;
      }
    }
    this.muted = muted;
    invalidateSchedule();
  }

  // Requests from the UI thread, acted on by ClipModel.processCurrentPos on the audio thread.
  private transient volatile boolean resumePending;
  private transient volatile boolean stopPending;

  private transient volatile NoteModel addedNotePending;

  /** C note_row.cpp:420-433 — a note added under the play position may start late. */
  void requestLateStartOfAddedNote(NoteModel note) {
    addedNotePending = note;
  }

  NoteModel takeAddedNotePending() {
    NoteModel n = addedNotePending;
    addedNotePending = null;
    return n;
  }

  /**
   * C note_row.cpp:420-433: if the play-pos is inside the newly added note (and the row is not
   * muted), attempt a late start of it.
   */
  NoteModel lateStartAddedNote(
      NoteModel newNote,
      int clipLastProcessedPos,
      int clipLoopLength,
      boolean clipCurrentlyPlayingReversed,
      double samplesPerTick,
      Object sound,
      int[] lateOut) {
    if (muted) {
      return null;
    }
    boolean independent = loopLengthIfIndependent > 0;
    int effectiveLength = independent ? loopLengthIfIndependent : clipLoopLength;
    boolean reversed =
        independent ? currentlyPlayingReversedIfIndependent : clipCurrentlyPlayingReversed;
    int pos = independent ? lastProcessedPosIfIndependent : clipLastProcessedPos;
    int howFarIntoNote = pos - newNote.getPos();
    if (howFarIntoNote < 0) {
      howFarIntoNote += effectiveLength;
    }
    if (howFarIntoNote >= newNote.getLength()) {
      return null;
    }
    return attemptLateStart(
        newNote, pos, effectiveLength, reversed, samplesPerTick, sound, lateOut);
  }

  boolean takeStopPending() {
    boolean p = stopPending;
    stopPending = false;
    return p;
  }

  boolean takeResumePending() {
    boolean p = resumePending;
    resumePending = false;
    return p;
  }

  void clearPendingRequests() {
    resumePending = false;
    stopPending = false;
    addedNotePending = null;
  }

  /** C kAmountNoteOnLatenessAllowed (definitions_cxx.hpp:170): 50 ms in audio samples. */
  static final int AMOUNT_NOTE_ON_LATENESS_ALLOWED = 2205;

  /**
   * C NoteRow::resumePlayback (note_row.cpp:3155-3170) → maybeStartLateNote (:3135-3153) →
   * attemptLateStartOfNextNoteToPlay (note_row.cpp:2440-2511), with CatchNotes on (its default,
   * runtime_feature_settings.cpp:116-118). Called on the audio thread at an actioned tick, so the
   * C's getNumSwungTicksInSinceLastActionedSwungTick() is 0 and getLivePos() is the last processed
   * pos. The C converts the lateness to samples with getInternalTickTime(); this model has no timer
   * clock, so it uses {@code samplesPerTick} — exact without swing or a tempo change in between.
   *
   * @return the note to start late, with {@code lateOut[0]} = ticksLate and {@code lateOut[1]} =
   *     samplesLate; null when nothing starts
   */
  NoteModel resumePlayback(
      int clipLastProcessedPos,
      int clipLoopLength,
      boolean clipCurrentlyPlayingReversed,
      double samplesPerTick,
      Object sound,
      int[] lateOut) {
    NoteModel result = null;
    if (!muted && !sequenced && !notes.isEmpty()) {
      boolean independent = loopLengthIfIndependent > 0;
      int effectiveLength = independent ? loopLengthIfIndependent : clipLoopLength;
      boolean reversed =
          independent ? currentlyPlayingReversedIfIndependent : clipCurrentlyPlayingReversed;
      int pos = independent ? lastProcessedPosIfIndependent : clipLastProcessedPos;
      if (effectiveLength > 0) {
        // maybeStartLateNote: is the play-pos inside a note?
        List<NoteModel> sorted = notesSortedByPos();
        int i = searchNotes(sorted, pos, LESS);
        boolean wrapping = (i == -1);
        if (wrapping) {
          i = sorted.size() - 1;
        }
        NoteModel note = sorted.get(i);
        int noteEnd = note.getPos() + note.getLength();
        if (wrapping) {
          noteEnd -= effectiveLength;
        }
        if (noteEnd > pos) {
          result =
              attemptLateStart(
                  note, pos, effectiveLength, reversed, samplesPerTick, sound, lateOut);
        }
      }
    }
    ignoreNoteOnsBefore_ = 0;
    return result;
  }

  private NoteModel attemptLateStart(
      NoteModel note,
      int pos,
      int effectiveLength,
      boolean reversed,
      double samplesPerTick,
      Object sound,
      int[] lateOut) {
    if (reversed && !allowsNoteTails(sound, pitch)) {
      return null; // Not supported yet (C :2444-2447)
    }
    int noteBeginsSoundingAtPos = note.getPos();
    if (reversed) {
      noteBeginsSoundingAtPos += note.getLength();
    }
    int swungTicksAgo = pos - noteBeginsSoundingAtPos;
    if (reversed) {
      swungTicksAgo = -swungTicksAgo;
    }
    if (swungTicksAgo < 0) {
      swungTicksAgo += effectiveLength;
    } else if (swungTicksAgo >= effectiveLength) {
      swungTicksAgo -= effectiveLength;
    }
    int swungTicksBeforeLastActionedOne = swungTicksAgo;
    int timeAgo = (int) (swungTicksAgo * samplesPerTick);
    if (timeAgo < 0) {
      timeAgo = 0;
    }

    org.deluge.firmware2.Sound s = null;
    if (sound instanceof org.deluge.engine.FirmwareSound fs) {
      fs.syncParamsToFw2();
      s = fs.fw2Sound;
    } else if (sound instanceof org.deluge.engine.FirmwareKit kit
        && pitch >= 0
        && pitch < kit.drumSounds.size()) {
      s = kit.drumSounds.get(pitch).fw2Sound;
    }
    boolean allows = s != null && s.allowsVeryLateNoteStart();
    if (!allows && timeAgo >= AMOUNT_NOTE_ON_LATENESS_ALLOWED) {
      return null;
    }
    if (!allows) {
      swungTicksBeforeLastActionedOne = 0;
      timeAgo = 0; // For one-shot samples, we don't want the sample to start part-way through.
    }
    lateOut[0] = swungTicksBeforeLastActionedOne;
    lateOut[1] = timeAgo;
    if (allowsNoteTails(sound, pitch)) { // playNote(on) — C note_row.cpp:2633-2637
      sequenced = true;
    }
    return note;
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

  /**
   * Keeps the C NoteRow invariant that notes never overlap: every C length edit caps a note at
   * NoteRow::getDistanceToNextNote (note_row.cpp:3859-3887; instrument_clip_view.cpp:2409-2411,
   * 4656, 7182), and attemptNoteAdd limits a new note the same way (note_row.cpp attemptNoteAdd).
   * The step grid can ask for a gate longer than the gap to the next step; the note then ends where
   * the next one starts. The sequencer's note search (processCurrentPos) relies on this.
   */
  public void limitLengthsToNextNote(int clipLoopLength) {
    int effectiveLength = loopLengthIfIndependent > 0 ? loopLengthIfIndependent : clipLoopLength;
    if (effectiveLength <= 0 || notes.isEmpty()) {
      return;
    }
    List<NoteModel> sorted = new ArrayList<>(notes);
    sorted.sort(java.util.Comparator.comparingInt(NoteModel::getPos));
    int n = sorted.size();
    for (int i = 0; i < n; i++) {
      NoteModel note = sorted.get(i);
      // C getDistanceToNextNote (forward): next note's pos - pos, wrapping; <= 0 adds a loop.
      int distance = sorted.get((i + 1) % n).getPos() - note.getPos();
      if (distance <= 0) {
        distance += effectiveLength;
      }
      if (note.getLength() > distance) {
        note.setLength(Math.max(1, distance));
      }
    }
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

  /**
   * C NoteRow::resumePlayback (note_row.cpp:3155-3170), reached from
   * InstrumentClip::resumePlayback. The late start itself runs at the clip's next processCurrentPos
   * on the audio thread (see the resumePending handling in ClipModel), where the play position is
   * current.
   */
  public void resumePlayback(int currentPos, int loopLength, boolean mayMakeSound) {
    if (mayMakeSound) {
      resumePending = true;
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

    // C note_row.cpp:2135-2147
    int ticksTilNextNoteEvent = Integer.MAX_VALUE;
    int effectiveCurrentPos = currentPos;
    int effectiveForwardPos =
        playingReversedNow ? (effectiveLength - effectiveCurrentPos - 1) : effectiveCurrentPos;
    if (effectiveForwardPos >= ignoreNoteOnsBefore_
        || (effectiveForwardPos < ticksSinceLast
            && (effectiveForwardPos + effectiveLength - ticksSinceLast) >= ignoreNoteOnsBefore_)) {
      ignoreNoteOnsBefore_ = 0;
    }

    boolean noFurtherNotes = muted;
    if (!muted) {
      List<NoteModel> notes = notesSortedByPos();
      int numNotes = notes.size();
      boolean justStoppedConstantNote = false;
      boolean alreadySearchedBackwards = false;
      NoteModel thisNote = null;
      boolean stopNote = false;

      // C :2171-2181 isAuditioning() — not ported: rows here have no audition state while playing.

      // C :2184-2283 — if a note is currently playing, all we can do is see if it's stopped yet.
      if (sequenced) {
        if (numNotes == 0) {
          stopNote = true;
        } else {
          int searchLessThan = effectiveCurrentPos;
          if (playingReversedNow) {
            searchLessThan += 1; // C :2199
          }
          int i = searchNotes(notes, searchLessThan, LESS);
          boolean wrapping = (i == -1);
          if (wrapping) {
            i = numNotes - 1;
          }
          thisNote = notes.get(i);

          boolean reachedThisNote = true;
          if (playingReversedNow) { // C :2214-2223
            int posRelativeToNoteLeftEdge = effectiveCurrentPos - thisNote.getPos();
            if (posRelativeToNoteLeftEdge < 0) {
              posRelativeToNoteLeftEdge += effectiveLength;
            }
            if (posRelativeToNoteLeftEdge >= thisNote.getLength()) {
              stopNote = true;
              reachedThisNote = false;
            }
          }

          if (reachedThisNote) {
            int noteLateEdgePos = thisNote.getPos(); // C :2225-2233
            if (!playingReversedNow) {
              noteLateEdgePos += thisNote.getLength();
            }
            if (wrapping) {
              noteLateEdgePos -= effectiveLength;
            }
            ticksTilNextNoteEvent = noteLateEdgePos - effectiveCurrentPos;
            if (playingReversedNow) {
              ticksTilNextNoteEvent = -ticksTilNextNoteEvent;
            }

            if (ticksTilNextNoteEvent <= 0) { // C :2241-2280
              boolean isDrone = thisNote.getPos() == 0 && thisNote.getLength() == effectiveLength;
              if (isDrone && !droneMustRestart(sound, pitch)) {
                justStoppedConstantNote = true;
                sequenced = false;
              } else {
                stopNote = true;
              }
            }
          }
        }
      }

      while (true) {
        if (stopNote) { // C :2189-2192 stopNote:
          stopCurrentlyPlayingNote(pendingNoteOffs);
          stopNote = false;
        }
        if (sequenced) {
          break;
        }

        // C :2285-2390 currentlyOff:
        ticksTilNextNoteEvent = Integer.MAX_VALUE;
        if (numNotes == 0) {
          noFurtherNotes = true;
          break;
        }

        int searchPos = effectiveCurrentPos;
        int nextNoteI = -1;
        boolean gotValidNoteIndex = false;
        boolean allowingNoteTailsNow = false;
        if (playingReversedNow) {
          allowingNoteTailsNow = allowsNoteTails(sound, pitch);
          if (searchPos == 0) { // C :2316-2327
            if (!allowingNoteTailsNow && !alreadySearchedBackwards && notes.get(0).getPos() == 0) {
              nextNoteI = 0;
              gotValidNoteIndex = true;
            } else {
              effectiveCurrentPos = effectiveLength;
              searchPos = effectiveLength;
            }
          } else {
            // C :2337
            searchPos += (!allowingNoteTailsNow && !alreadySearchedBackwards) ? 1 : 0;
          }
        }

        if (!gotValidNoteIndex) {
          nextNoteI = searchNotes(notes, searchPos, playingReversedNow ? LESS : GREATER_OR_EQUAL);
          if (nextNoteI < 0 || nextNoteI >= numNotes) { // C :2343-2355
            if (playingReversedNow && allowingNoteTailsNow) {
              nextNoteI = numNotes - 1;
            } else {
              noFurtherNotes = true;
              break;
            }
          }
        }

        // C :2358-2386 gotValidNoteIndex:
        NoteModel nextNote = notes.get(nextNoteI);
        int newTicksTil = nextNote.getPos() - effectiveCurrentPos;
        if (playingReversedNow) {
          newTicksTil = -newTicksTil;
          if (allowingNoteTailsNow) {
            newTicksTil -= nextNote.getLength();
          }
          if (newTicksTil < 0) {
            noFurtherNotes = true;
            break;
          }
        }

        if (newTicksTil <= 0) {
          if (effectiveForwardPos >= ignoreNoteOnsBefore_) {
            playNote(nextNote, justStoppedConstantNote, pendingNoteOns, sound);
          }
          if (playingReversedNow && !allowingNoteTailsNow) {
            alreadySearchedBackwards = true;
            thisNote = nextNote;
            stopNote = true;
            continue;
          }
          newTicksTil = nextNote.getLength();
        }
        ticksTilNextNoteEvent = newTicksTil;
        break;
      }
    }

    if (noFurtherNotes) { // C :2152-2164 noFurtherNotes:
      if (loopLengthIfIndependent > 0) {
        ticksTilNextNoteEvent = effectiveCurrentPos;
        if (!playingReversedNow) {
          ticksTilNextNoteEvent = loopLengthIfIndependent - ticksTilNextNoteEvent;
        } else if (ticksTilNextNoteEvent == 0) {
          ticksTilNextNoteEvent = loopLengthIfIndependent;
        }
      }
    }

    return Math.min(ticksTilNextNoteEvent, paramManager.ticksTilNextEvent);
  }

  // C OrderedResizeableArray::search (ordered_resizeable_array.cpp:36-52): lower bound +
  // comparison.
  private static final int GREATER_OR_EQUAL = 0;
  private static final int LESS = -1;

  private static int searchNotes(List<NoteModel> notes, int searchKey, int comparison) {
    int rangeBegin = 0;
    int rangeEnd = notes.size();
    while (rangeBegin != rangeEnd) {
      int proposedIndex = (rangeBegin + rangeEnd) >> 1;
      if (notes.get(proposedIndex).getPos() < searchKey) {
        rangeBegin = proposedIndex + 1;
      } else {
        rangeEnd = proposedIndex;
      }
    }
    return rangeBegin + comparison;
  }

  /**
   * The C keeps a NoteRow's notes ordered by pos (NoteVector); this model appends, so the search
   * runs on a sorted view. Returns the list itself when it is already ordered.
   */
  private List<NoteModel> notesSortedByPos() {
    List<NoteModel> list = notes;
    for (int i = 1; i < list.size(); i++) {
      if (list.get(i - 1).getPos() > list.get(i).getPos()) {
        List<NoteModel> sorted = new ArrayList<>(list);
        sorted.sort(java.util.Comparator.comparingInt(NoteModel::getPos));
        return sorted;
      }
    }
    return list;
  }

  /**
   * C note_row.cpp:2248-2269 — a droning full-length note sustains across the loop point, unless
   * the arpeggiator is off and the sound has CUT-mode or time-stretch-synced samples, which must
   * stop and retrigger from the start.
   */
  private static boolean droneMustRestart(Object sound, int pitch) {
    org.deluge.firmware2.Sound s = null;
    if (sound instanceof org.deluge.engine.FirmwareSound fs) {
      fs.syncParamsToFw2();
      if (fs.fw2Sound.arpEnabled()) return false; // clip->arpSettings.mode == ArpMode::OFF
      s = fs.fw2Sound;
    } else if (sound instanceof org.deluge.engine.FirmwareKit kit) {
      // The kit clip's arpSettings is the kit-level arp, not ported (#96) — always OFF here.
      if (pitch >= 0 && pitch < kit.drumSounds.size()) {
        s = kit.drumSounds.get(pitch).fw2Sound;
      }
    }
    return s != null && (s.hasCutModeSamples() || s.hasAnyTimeStretchSyncing());
  }

  /** C NoteRow::stopCurrentlyPlayingNote (note_row.cpp:1869-1877), actuallySoundChange = true. */
  private void stopCurrentlyPlayingNote(List<Integer> pendingNoteOffs) {
    if (!sequenced) {
      return;
    }
    pendingNoteOffs.add(pitch); // playNote(false, …) — sent before the pending note-ons
    sequenced = false;
  }

  /**
   * C NoteRow::stopCurrentlyPlayingNote with actuallySoundChange = false (note_row.cpp:1869-1877):
   * forgets the sounding note without sending its note-off.
   */
  public boolean isSequenced() {
    return sequenced;
  }

  public void stopCurrentlyPlayingNoteSilently() {
    sequenced = false;
  }

  void copySequencedFrom(NoteRowModel live) {
    this.sequenced = live.sequenced;
  }

  /** Called when a pending note-on fails its play conditions (instrument_clip.cpp:901, 905). */
  void clearSequenced() {
    sequenced = false;
  }

  /** C NoteRow::playNote(true, …) (note_row.cpp:2519-2637). */
  private void playNote(
      NoteModel thisNote,
      boolean noteMightBeConstant,
      List<org.deluge.model.PendingNoteOn> pendingNoteOns,
      Object sound) {
    boolean send;
    if (sound instanceof org.deluge.engine.FirmwareKit kit) { // C :2604-2613
      send = true;
      if (noteMightBeConstant && pitch >= 0 && pitch < kit.drumSounds.size()) {
        org.deluge.engine.FirmwareSound drum = kit.drumSounds.get(pitch);
        if (!drum.fw2Sound.voices.isEmpty() && allowsNoteTails(sound, pitch)) {
          send = false; // still sounding; C also resetTimeEnteredState(), not ported
        }
      }
    } else if (sound instanceof org.deluge.engine.FirmwareSound fs) { // C :2528-2541
      // check_for_note_still_sounding (note_row.cpp:2510-2517)
      send =
          !(noteMightBeConstant && fs.fw2Sound.noteIsOn(pitch, true) && allowsNoteTails(fs, pitch));
    } else {
      send = !noteMightBeConstant; // other melodic instruments just don't resend (C :2542)
    }
    if (send) {
      org.deluge.model.PendingNoteOn on =
          new org.deluge.model.PendingNoteOn(
              this,
              thisNote.getVelocityByte(),
              thisNote.getProbabilityValue(), // C note_row.cpp:2554-2555
              thisNote.getIterance(),
              thisNote.getFill());
      on.sampleSyncLength = thisNote.getLength(); // C :2557-2558
      pendingNoteOns.add(on);
    }
    if (allowsNoteTails(sound, pitch)) { // C :2633-2637
      sequenced = true;
    }
  }

  // C InstrumentClip::allowNoteTails (instrument_clip.cpp:3350-3371) ->
  // Sound::allowNoteTails (sound.cpp:1927-1964).
  static boolean allowsNoteTails(Object sound, int pitch) {
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
}
