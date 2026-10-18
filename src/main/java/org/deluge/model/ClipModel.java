package org.deluge.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unified Clip model representing both a single sequence (clip/pattern) in a Track and a
 * high-performance sequencer playback counter.
 */
public class ClipModel extends TimelineCounter {

  /** Play mode determines how the clip behaves after being launched. */
  public enum PlayMode {
    NORMAL,
    LOOP,
    ONCE,
    FILL
  }

  /** Play direction determines the structural pathway of step playback. */
  public enum PlayDirection {
    FORWARD,
    REVERSE,
    PING_PONG,
    RANDOM
  }

  private String name;
  private int rowCount;
  private int stepCount;
  private final List<List<StepData>> grid = new ArrayList<>();
  private String color = "#00ffcc"; // Default color
  private PlayMode playMode = PlayMode.NORMAL;
  private PlayDirection playDirection = PlayDirection.FORWARD;
  private boolean tripletMode = false;
  private boolean isArrangementOnly = false;

  // Session-view section (clip launcher grouping). Matches the C Clip::section:
  // 0..kMaxNumSections-1,
  // 255 = unassigned. Written only when != 255 (clip.cpp:667), clamped on read (clip.cpp:715).
  private int section = 255;

  private String leftCol = "VELOCITY";
  private String rightCol = "MOD";

  public String getLeftCol() {
    return leftCol;
  }

  public void setLeftCol(String leftCol) {
    this.leftCol = leftCol;
  }

  public String getRightCol() {
    return rightCol;
  }

  public void setRightCol(String rightCol) {
    this.rightCol = rightCol;
  }

  private final Map<String, float[]> automationData = new HashMap<>();
  private final Map<String, Float> kitParams = new HashMap<>();

  // ── Unified NoteRowModel map replacing parallel maps ──
  public final Map<Integer, NoteRowModel> noteRows = new HashMap<>();
  private boolean isKit = false;

  public boolean isKit() {
    return isKit;
  }

  public void setIsKit(boolean isKit) {
    this.isKit = isKit;
  }

  // ── Transient Transport / Sequencer States ──
  private transient ClipType type = ClipType.INSTRUMENT;
  private transient int loopLength = 0;
  private transient org.deluge.modulation.params.ParamManager paramManager =
      new org.deluge.modulation.params.ParamManager();
  private transient int ticksTilNextEvent = Integer.MAX_VALUE;
  private transient int ticksTilNextNoteRowEvent = 0; // C instrument_clip.h:114
  private transient int noteRowsNumTicksBehindClip = 0; // C instrument_clip.h:115
  public transient Object sound; // The transient DSP engine reference (FirmwareSound / FirmwareKit)

  /**
   * The clip's timeline ParamManager. In the C a synth clip's paramManager is the one its Sound
   * renders from (Clip::paramManager, passed to Sound::render via the ModelStack); FirmwareFactory
   * records the clip's automation into the FirmwareSound's ParamManager, so that is the one to
   * advance (clip.cpp:250-283 processCurrentPos → paramManager.processCurrentPos). The standalone
   * clip-level instance remains for clips without a FirmwareSound.
   */
  org.deluge.modulation.params.ParamManager timelineParamManager() {
    return (sound instanceof org.deluge.engine.FirmwareSound fs) ? fs.paramManager : paramManager;
  }

  public NoteRowModel getOrCreateRow(int rowIndex) {
    NoteRowModel row = noteRows.computeIfAbsent(rowIndex, idx -> new NoteRowModel(idx));
    row.setOnScheduleInvalidated(this::expectEvent);
    return row;
  }

  public Map<Integer, NoteRowModel> getNoteRowsMap() {
    return noteRows;
  }

  public List<NoteRowModel> getNoteRowsList() {
    return new ArrayList<>(noteRows.values());
  }

  /**
   * Transposes all note rows in this clip vertically by scale degrees or octaves, matching hardware
   * encoder-nudge transposition. Rejects the entire operation (returning false without modifying
   * any pitches) if any note would be pushed outside the 0-127 MIDI range or if the clip is a drum
   * Kit.
   *
   * <p><b>Row identity is untouched.</b> The C's {@code noteRows} is an array in which {@code y} is
   * an ordinary field, so transposing only writes {@code y}. Ours is a map keyed by <em>row
   * index</em> — the same index that addresses {@link #grid}, {@link #getStep} and {@link
   * #getRowYNote} — with pitch as a separate per-row property. Re-keying it by the new pitch would
   * therefore decouple the rows from the step grid, and would additionally destroy a row outright
   * whenever two rows land on the same pitch, which scale mode makes routine: with a major scale
   * and a +1 degree nudge, C shifts +2 and out-of-scale C# shifts +1, so both arrive at D. Rows
   * keep their keys and only their pitch changes; two rows sharing a pitch is legal here exactly as
   * it is on hardware.
   *
   * <p>Two things the C does at this point have no counterpart at this layer and are the caller's
   * responsibility: {@code stopAllNotesPlaying()} (instrument_clip.cpp:1408) before the pitches
   * move, and {@code yScroll += change} (:1416) so the view follows.
   *
   * @param direction +1 for up, -1 for down
   * @param isOctave true for octave nudge (Shift + Turn), false for degree/semitone nudge
   * @param isScaleMode true if diatonic scale mode is active, false for chromatic
   * @param modeNotes scale intervals (e.g. [0, 2, 4, 5, 7, 9, 11])
   * @param rootKey MIDI root pitch class (0 = C, 1 = C#, etc.)
   * @return true if notes were successfully transposed; false if rejected or no-op
   * @see "C++ InstrumentClip::nudgeNotesVertically in instrument_clip.cpp:1327-1418"
   */
  public boolean nudgeNotesVertically(
      int direction, boolean isOctave, boolean isScaleMode, int[] modeNotes, int rootKey) {
    if (direction == 0 || isKit || noteRows.isEmpty()) {
      return false;
    }

    int[] intervals =
        (modeNotes != null && modeNotes.length > 0)
            ? modeNotes
            : Scales.ScaleType.CHROMATIC.getIntervals();
    int numModeNotes = intervals.length;

    int change = direction > 0 ? 1 : -1;
    if (isOctave) {
      change *= isScaleMode ? numModeNotes : 12;
    }

    int[] shiftForInterval = Scales.computeTransposeShiftTable(intervals, change, isScaleMode);

    // Scan only note rows that hold notes for extreme resulting pitches to enforce 0-127 bounds.
    // Empty rows do not get a vote (and stale out-of-range empty rows from old songs must not veto
    // every transpose forever). C instrument_clip.cpp:1407-1427.
    int extremeNewPitch = 0;
    boolean anyNotesFound = false;
    for (Map.Entry<Integer, NoteRowModel> entry : noteRows.entrySet()) {
      NoteRowModel row = entry.getValue();
      if (rowHasNoNotes(entry.getKey(), row)) {
        continue;
      }
      int currentPitch = row.getPitch();
      int interval = Math.floorMod(currentPitch - rootKey, 12);
      int newPitch = currentPitch + shiftForInterval[interval];
      if (!anyNotesFound
          || (change > 0 ? newPitch > extremeNewPitch : newPitch < extremeNewPitch)) {
        extremeNewPitch = newPitch;
        anyNotesFound = true;
      }
    }

    if (!anyNotesFound || !Scales.isPitchWithinPlayableRange(extremeNewPitch)) {
      return false;
    }

    // Apply the transpose in place: only the pitch moves, the map keys do not.
    // Drop empty rows carried outside 0-127 (C instrument_clip.cpp:1432-1446).
    var it = noteRows.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<Integer, NoteRowModel> entry = it.next();
      int rowIndex = entry.getKey();
      NoteRowModel row = entry.getValue();
      int currentPitch = row.getPitch();
      int interval = Math.floorMod(currentPitch - rootKey, 12);
      int newPitch = currentPitch + shiftForInterval[interval];
      if (rowHasNoNotes(rowIndex, row) && !Scales.isPitchWithinPlayableRange(newPitch)) {
        it.remove();
        continue;
      }
      row.setPitch(newPitch);
      retuneGridRow(rowIndex, newPitch);
    }

    return true;
  }

  /**
   * True when the row has neither raw note events nor active grid steps. C {@code
   * NoteRow::hasNoNotes()} checks {@code notes.getNumElements() == 0}; in {@code ClipModel} notes
   * can live in {@link NoteRowModel#getNotes()} or in {@link #grid}.
   */
  private boolean rowHasNoNotes(int rowIndex, NoteRowModel row) {
    if (row != null && !row.hasNoNotes()) {
      return false;
    }
    if (rowIndex >= 0 && rowIndex < grid.size()) {
      List<StepData> steps = grid.get(rowIndex);
      for (int s = 0; s < steps.size(); s++) {
        if (steps.get(s).active()) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Rewrites the pitch carried by every active step of one grid row after its {@link NoteRowModel}
   * has been retuned.
   *
   * <p>{@link StepData} keeps its own copy of the pitch, and that copy — not the row's — is what
   * {@code EngineSyncCoordinator} pushes to the audio bridge and what {@code ProjectSerializer}
   * writes as the row's {@code yNote}. Leaving it stale would transpose the model while the engine
   * and the saved file kept playing the old notes. Every other per-step property is preserved;
   * {@code StepData.of()} would reset iterance/fill/nudge to 0.
   */
  public void retuneRowInPlace(int rowIndex, int newPitch) {
    getOrCreateRow(rowIndex).setPitch(newPitch);
    retuneGridRow(rowIndex, newPitch);
  }

  /**
   * Replaces a row's visual grid steps in place without mutating {@link NoteRowModel#getNotes()},
   * for callers (such as row rotation) that have already updated the authoritative raw note list.
   */
  public void replaceGridRowInPlace(int rowIndex, StepData[] steps) {
    if (rowIndex < 0 || rowIndex >= grid.size() || steps == null) return;
    List<StepData> row = grid.get(rowIndex);
    int limit = Math.min(row.size(), steps.length);
    for (int s = 0; s < limit; s++) {
      row.set(s, steps[s] != null ? steps[s] : StepData.empty());
    }
    expectEvent();
  }

  private void retuneGridRow(int rowIndex, int newPitch) {
    if (rowIndex < 0 || rowIndex >= grid.size()) return;
    List<StepData> steps = grid.get(rowIndex);
    for (int s = 0; s < steps.size(); s++) {
      StepData step = steps.get(s);
      if (!step.active() || step.pitch() == newPitch) continue;
      steps.set(s, step.withPitch(newPitch));
    }
  }

  public ClipModel(String name, int rowCount, int stepCount) {
    this.name = name;
    this.rowCount = Math.max(1, rowCount);
    this.stepCount = Math.max(1, stepCount);
    initGrid();
  }

  public ClipModel deepCopy(String newName) {
    ClipModel copy = new ClipModel(newName, this.rowCount, this.stepCount);
    for (int r = 0; r < rowCount; r++) {
      for (int s = 0; s < stepCount; s++) {
        copy.setStep(r, s, this.getStep(r, s));
      }
    }
    // Deep-copy automation data
    for (Map.Entry<String, float[]> e : automationData.entrySet()) {
      copy.automationData.put(e.getKey(), e.getValue().clone());
    }
    // Deep-copy kit params
    copy.kitParams.putAll(this.kitParams);
    copy.leftCol = this.leftCol;
    copy.rightCol = this.rightCol;

    copy.copyNoteRowsFrom(this);

    copy.playMode = this.playMode;
    copy.tripletMode = this.tripletMode;
    copy.setPlayDirection(this.playDirection);
    copy.isArrangementOnly = this.isArrangementOnly;
    return copy;
  }

  private void copyNoteRowsFrom(ClipModel src) {
    this.noteRows.clear();
    for (Map.Entry<Integer, NoteRowModel> e : src.noteRows.entrySet()) {
      NoteRowModel oldRow = e.getValue();
      NoteRowModel newRow = this.getOrCreateRow(e.getKey());
      newRow.setPitch(oldRow.getPitch());
      newRow.setMuted(oldRow.isMuted());
      newRow.setMutedBeforeStemExport(oldRow.isMutedBeforeStemExport());
      newRow.setExportStem(oldRow.isExportStem());
      newRow.setLoopLengthIfIndependent(oldRow.getLoopLengthIfIndependent());

      // Copy notes with exact C play conditions (probabilityValue, fill, lift, iterance)
      List<NoteModel> copiedNotes = new ArrayList<>();
      for (NoteModel nm : oldRow.getNotes()) {
        NoteModel copyNote =
            new NoteModel(
                nm.getTickPos(),
                nm.getTickLen(),
                nm.getVelocity(),
                nm.getProbability(),
                nm.getSubTriggers());
        copyNote.setProbabilityValue(nm.getProbabilityValue());
        copyNote.setFill(nm.getFill());
        copyNote.setLift(nm.getLift());
        copyNote.setIterance(new Iterance(nm.getIterance().divisor, nm.getIterance().iteranceStep));
        copiedNotes.add(copyNote);
      }
      newRow.setNotes(copiedNotes);

      // Copy sound params & automation
      newRow.getSoundParams().putAll(oldRow.getSoundParams());
      for (Map.Entry<String, float[]> autoEntry : oldRow.getRowAutomation().entrySet()) {
        newRow.getRowAutomation().put(autoEntry.getKey(), autoEntry.getValue().clone());
      }
    }
  }

  private void initGrid() {
    // Grow or shrink the grid rows to match rowCount non-destructively
    while (grid.size() < rowCount) {
      List<StepData> newRow = new ArrayList<>();
      for (int s = 0; s < stepCount; s++) {
        newRow.add(StepData.empty());
      }
      grid.add(newRow);
    }
    while (grid.size() > rowCount) {
      grid.remove(grid.size() - 1);
    }

    // Grow or shrink each row's steps to match stepCount
    for (List<StepData> row : grid) {
      while (row.size() < stepCount) {
        row.add(StepData.empty());
      }
      while (row.size() > stepCount) {
        row.remove(row.size() - 1);
      }
    }
  }

  // --- Authoritative Getters/Setters mapping to NoteRowModel ---

  public void setRowYNote(int rowIndex, int yNote) {
    getOrCreateRow(rowIndex).setPitch(yNote);
  }

  public int getRowYNote(int rowIndex) {
    NoteRowModel row = noteRows.get(rowIndex);
    return row != null ? row.getPitch() : -1;
  }

  public void setRowAutomation(int rowIndex, String paramName, int stepIndex, float value) {
    NoteRowModel row = getOrCreateRow(rowIndex);
    float[] array =
        row.getRowAutomation()
            .computeIfAbsent(
                paramName,
                k -> {
                  float[] a = new float[stepCount];
                  java.util.Arrays.fill(a, -1f);
                  return a;
                });
    if (stepIndex >= 0 && stepIndex < array.length) {
      array[stepIndex] = value;
    }
  }

  public float[] getRowAutomation(int rowIndex, String paramName) {
    NoteRowModel row = noteRows.get(rowIndex);
    return row != null ? row.getRowAutomation().get(paramName) : null;
  }

  public float getRowAutomationValue(int rowIndex, String paramName, int stepIndex) {
    float[] array = getRowAutomation(rowIndex, paramName);
    if (array == null || stepIndex < 0 || stepIndex >= array.length) return -1f;
    return array[stepIndex];
  }

  public boolean hasRowAutomation(int rowIndex, String paramName, int stepIndex) {
    return getRowAutomationValue(rowIndex, paramName, stepIndex) >= 0.0f;
  }

  public void setRawNoteEvents(int rowIndex, List<NoteModel> notes) {
    if (notes == null) {
      noteRows.remove(rowIndex);
      expectEvent();
    } else {
      getOrCreateRow(rowIndex).setNotes(new ArrayList<>(notes));
    }
  }

  public List<NoteModel> getRawNoteEvents(int rowIndex) {
    NoteRowModel row = noteRows.get(rowIndex);
    return row != null ? row.getNotes() : null;
  }

  public void setRowSoundParam(int row, String paramName, float value) {
    getOrCreateRow(row).getSoundParams().put(paramName, value);
  }

  public float getRowSoundParam(int row, String paramName) {
    NoteRowModel noteRow = noteRows.get(row);
    if (noteRow == null) return -1f;
    Float val = noteRow.getSoundParams().get(paramName);
    return val != null ? val : -1f;
  }

  public boolean hasRowSoundParams(int row) {
    NoteRowModel noteRow = noteRows.get(row);
    return noteRow != null && !noteRow.getSoundParams().isEmpty();
  }

  public Set<String> getRowSoundParamNames(int row) {
    NoteRowModel noteRow = noteRows.get(row);
    return noteRow != null ? noteRow.getSoundParams().keySet() : Set.of();
  }

  // --- Standard Getters/Setters ---

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getColor() {
    return color;
  }

  public void setColor(String color) {
    this.color = color;
  }

  public PlayMode getPlayMode() {
    return playMode;
  }

  public void setPlayMode(PlayMode playMode) {
    this.playMode = playMode;
  }

  public PlayDirection getPlayDirection() {
    return playDirection;
  }

  public void setPlayDirection(PlayDirection playDirection) {
    this.playDirection = playDirection != null ? playDirection : PlayDirection.FORWARD;
    SequenceDirection mode =
        switch (this.playDirection) {
          case REVERSE -> SequenceDirection.REVERSE;
          case PING_PONG -> SequenceDirection.PINGPONG;
          case FORWARD, RANDOM -> SequenceDirection.FORWARD;
        };
    setSequenceDirectionMode(mode);
  }

  /**
   * C Clip::setSequenceDirectionMode (clip.cpp:1041-1055): updates {@link #sequenceDirectionMode}
   * and, when not switching to {@link SequenceDirection#PINGPONG}, sets {@link
   * #currentlyPlayingReversed} and mirrors {@link #lastProcessedPos} if the playing direction
   * flipped.
   */
  public void setSequenceDirectionMode(SequenceDirection newMode) {
    flushPendingNoteRowTicks();
    boolean reversedBefore = currentlyPlayingReversed;
    sequenceDirectionMode = newMode != null ? newMode : SequenceDirection.FORWARD;
    this.playDirection =
        switch (sequenceDirectionMode) {
          case REVERSE -> PlayDirection.REVERSE;
          case PINGPONG -> PlayDirection.PING_PONG;
          case FORWARD ->
              (this.playDirection == PlayDirection.RANDOM)
                  ? PlayDirection.RANDOM
                  : PlayDirection.FORWARD;
        };

    if (sequenceDirectionMode != SequenceDirection.PINGPONG) {
      currentlyPlayingReversed = (sequenceDirectionMode == SequenceDirection.REVERSE);

      if (reversedBefore != currentlyPlayingReversed) {
        int len = getLoopLength();
        if (len > 0) {
          // C clip.cpp:1049 — mirrors position zero too (0 -> loopLength).
          lastProcessedPos = len - lastProcessedPos;
        }
      }
    }
    for (NoteRowModel row : noteRows.values()) {
      row.syncDirectionWithClip(sequenceDirectionMode);
    }
    expectEvent();
  }

  public boolean isTripletMode() {
    return tripletMode;
  }

  public void setTripletMode(boolean tripletMode) {
    this.tripletMode = tripletMode;
    expectEvent();
  }

  public boolean isArrangementOnly() {
    return isArrangementOnly;
  }

  public void setArrangementOnly(boolean arrangementOnly) {
    isArrangementOnly = arrangementOnly;
  }

  public int getRowCount() {
    return rowCount;
  }

  public void setRowCount(int rowCount) {
    this.rowCount = Math.max(1, rowCount);
    initGrid();
  }

  public int getStepCount() {
    return stepCount;
  }

  /** Session section (255 = unassigned). See the {@code section} field. */
  public int getSection() {
    return section;
  }

  public void setSection(int section) {
    this.section = section;
  }

  // C Clip::activeIfNoSolo (serialized as isPlaying). True = active in the session clip-launcher
  // (plays when the session transport runs). Default true matches the C default.
  private boolean activeInSession = true;

  public boolean isActiveInSession() {
    return activeInSession;
  }

  public void setActiveInSession(boolean active) {
    this.activeInSession = active;
  }

  // C Clip::armState (clip.h:170). Whether this clip is armed to change state at the next session
  // launch event, and how. Transient runtime state (not serialized), cleared to OFF when the launch
  // fires. Default OFF matches the C.
  private transient ArmState armState = ArmState.OFF;

  public ArmState getArmState() {
    return armState;
  }

  public void setArmState(ArmState armState) {
    this.armState = armState != null ? armState : ArmState.OFF;
  }

  public void setStepCount(int stepCount) {
    int old = this.stepCount;
    this.stepCount = Math.max(1, stepCount);
    resizeAutomationArrays(old, this.stepCount);
    initGrid();
    expectEvent();
  }

  public void clearAutomation(String param) {
    automationData.remove(param);
  }

  public boolean hasAutomation(String param) {
    return automationData.containsKey(param);
  }

  public StepData getStep(int r, int s) {
    if (r >= 0 && r < rowCount && s >= 0 && s < stepCount) {
      return grid.get(r).get(s);
    }
    return StepData.empty();
  }

  public void setStep(int r, int s, boolean active) {
    setStep(r, s, StepData.of(active, 0.8f, 1.0f, 1.0f, 60));
  }

  public void setStep(int r, int s, StepData step) {
    if (r >= 0 && r < rowCount && s >= 0 && s < stepCount) {
      // Real-time surgical sync to noteRows
      int stepTicks = tripletMode ? 32 : 24;
      boolean isKit =
          this.isKit
              || (type == ClipType.INSTRUMENT && sound instanceof org.deluge.engine.FirmwareKit);
      int pitch =
          isKit
              ? r
              : (getRowYNote(r) >= 0
                  ? getRowYNote(r)
                  : ((step.active() && step.pitch() > 0) ? step.pitch() : (rowCount - 1 - r)));

      if (!isKit && step.pitch() != pitch) {
        step = step.withPitch(pitch);
      }

      StepData previousStep = grid.get(r).get(s);
      grid.get(r).set(s, step);

      NoteRowModel row = getOrCreateRow(r);
      row.setPitch(pitch);

      int notePos = s * stepTicks;
      NoteModel existingNote = null;
      for (NoteModel n : row.getNotes()) {
        if (n.getPos() == notePos) {
          existingNote = n;
          break;
        }
      }

      if (step.active()) {
        Iterance cond = Iterance.fromInt(step.playCondition());
        // C definitions_cxx.hpp:727 — OFF=0, NOT_FILL=1, FILL=2.
        int fillMode = step.fillMode() != 0 ? step.fillMode() : (step.fill() > 0.001f ? 2 : 0);
        if (existingNote == null) {
          NoteModel note = new NoteModel();
          note.setPos(notePos);
          note.setLength((int) (step.gate() * stepTicks));
          note.setVelocity((int) (step.velocity() * 127.0f));
          note.setProbability((int) (step.probability() * 100.0f));
          note.setIterance(cond);
          note.setFill(fillMode);
          row.getNotes().add(note);
        } else {
          existingNote.setLength((int) (step.gate() * stepTicks));
          existingNote.setVelocity((int) (step.velocity() * 127.0f));
          // Only a real edit of a grid field rewrites the note's play conditions. The loader
          // re-sets every step (e.g. to apply row pitch) after installing the file's notes, and
          // the grid cannot hold every C value (follow-previous probability), so an unconditional
          // write reset loaded notes to grid defaults.
          if (previousStep == null || previousStep.probability() != step.probability()) {
            existingNote.setProbability((int) (step.probability() * 100.0f));
          }
          if (previousStep == null || previousStep.playCondition() != step.playCondition()) {
            existingNote.setIterance(cond);
          }
          if (previousStep == null
              || previousStep.fillMode() != step.fillMode()
              || previousStep.fill() != step.fill()) {
            existingNote.setFill(fillMode);
          }
        }
      } else {
        if (existingNote != null) {
          row.getNotes().remove(existingNote);
        }
      }
      expectEvent();
    }
  }

  public void rebuildNotesFromGrid() {
    int stepTicks = tripletMode ? 32 : 24;
    for (int r = 0; r < rowCount; r++) {
      NoteRowModel row = getOrCreateRow(r);
      row.getNotes().clear();
      for (int s = 0; s < stepCount; s++) {
        StepData step = getStep(r, s);
        if (step.active()) {
          NoteModel note = new NoteModel();
          note.setPos(s * stepTicks);
          note.setLength((int) (step.gate() * stepTicks));
          note.setVelocity((int) (step.velocity() * 127.0f));
          note.setProbability((int) (step.probability() * 100.0f));
          note.setIterance(Iterance.fromInt(step.playCondition()));
          note.setFill(step.fillMode() != 0 ? step.fillMode() : (step.fill() > 0.001f ? 2 : 0));
          row.getNotes().add(note);
        }
      }
    }
    expectEvent();
  }

  /**
   * Doubles the clip length, copying the existing pattern into the new second half so the clip
   * plays the same content twice as long — the desktop equivalent of the hardware's clip-double.
   * Active steps are duplicated with their velocity/gate/probability/pitch; per-step automation is
   * extended (not duplicated) by {@link #setStepCount}.
   */
  public void doubleLength() {
    int n = stepCount;
    if (n <= 0) return;
    setStepCount(n * 2); // non-destructive grow: [0,n) preserved, [n,2n) starts empty
    for (int r = 0; r < rowCount; r++) {
      for (int s = 0; s < n; s++) {
        StepData sd = getStep(r, s);
        if (sd.active()) {
          setStep(r, s + n, sd);
        }
      }
    }
  }

  /**
   * Shifts every note in the clip sideways in time by {@code steps} columns (positive = right,
   * negative = left), wrapping any note that moves past an edge around to the other end — the
   * Deluge hardware's "shift track contents horizontally". Operates at the clip's current step
   * resolution.
   */
  public void shiftNotesHorizontally(int steps) {
    int n = stepCount;
    if (n <= 0) return;
    int shift = Math.floorMod(steps, n);
    if (shift == 0) return;
    for (int r = 0; r < rowCount; r++) {
      StepData[] rowSnap = new StepData[n];
      for (int s = 0; s < n; s++) {
        rowSnap[s] = getStep(r, s);
      }
      for (int s = 0; s < n; s++) {
        setStep(r, s, rowSnap[Math.floorMod(s - shift, n)]);
      }
    }
  }

  /**
   * Restores this clip's length and note content from a snapshot (typically produced by {@link
   * #deepCopy}). Used for lossless undo of bulk clip edits — e.g. a typed length change that would
   * otherwise discard notes past the new end. The step grid is authoritative; note rows are rebuilt
   * from it afterwards so the two stay consistent.
   */
  public void restoreFrom(ClipModel src) {
    if (src == null) return;
    flushPendingNoteRowTicks();
    Map<Integer, NoteRowModel> liveRows = new HashMap<>(this.noteRows);
    tripletMode = src.tripletMode;
    setStepCount(src.stepCount);
    for (int r = 0; r < rowCount; r++) {
      for (int s = 0; s < stepCount; s++) {
        setStep(r, s, src.getStep(r, s));
      }
    }
    automationData.clear();
    for (Map.Entry<String, float[]> e : src.automationData.entrySet()) {
      automationData.put(e.getKey(), e.getValue().clone());
    }
    kitParams.clear();
    kitParams.putAll(src.kitParams);
    playMode = src.playMode;
    isArrangementOnly = src.isArrangementOnly;
    rebuildNotesFromGrid();
    // Restore exact C play conditions (probabilityValue, follow-previous bit 128, lift, fill,
    // iterance) that StepData cannot hold.
    if (!src.noteRows.isEmpty()) {
      copyNoteRowsFrom(src);
    }
    // Preserve live transport state (phase, repeat count, direction) for independent-length rows,
    // and initialize newly independent rows from the clip's current play position.
    for (Map.Entry<Integer, NoteRowModel> e : this.noteRows.entrySet()) {
      NoteRowModel row = e.getValue();
      if (!row.hasIndependentPlayPos()) {
        continue;
      }
      NoteRowModel liveRow = liveRows.get(e.getKey());
      if (liveRow != null
          && liveRow.getLoopLengthIfIndependent() == row.getLoopLengthIfIndependent()) {
        row.copyLiveTransportFrom(liveRow);
      } else {
        row.setPosIfIndependent(lastProcessedPos, sequenceDirectionMode);
      }
    }
    setPlayDirection(src.playDirection);
    expectEvent();
  }

  public void syncNoteRowsFromGrid() {
    int stepTicks = tripletMode ? 32 : 24;
    boolean isKit =
        this.isKit
            || (type == ClipType.INSTRUMENT && sound instanceof org.deluge.engine.FirmwareKit);

    for (int r = 0; r < rowCount; r++) {
      boolean hasActiveSteps = false;
      for (int s = 0; s < stepCount; s++) {
        if (getStep(r, s).active()) {
          hasActiveSteps = true;
          break;
        }
      }
      if (!hasActiveSteps && !noteRows.containsKey(r)) {
        continue;
      }

      int pitch = -1;
      if (!isKit && getRowYNote(r) >= 0) {
        pitch = getRowYNote(r);
      } else {
        for (int s = 0; s < stepCount; s++) {
          StepData step = getStep(r, s);
          if (step.active() && step.pitch() > 0) {
            pitch = step.pitch();
            break;
          }
        }
      }
      if (pitch < 0) {
        pitch = isKit ? r : (rowCount - 1 - r);
      }
      NoteRowModel row = getOrCreateRow(r);
      row.setPitch(pitch);

      // If we already have high-res notes parsed from XML, do not overwrite them with empty step
      // grid!
      if (row.getNotes().isEmpty()) {
        for (int s = 0; s < stepCount; s++) {
          StepData step = getStep(r, s);
          if (step.active()) {
            NoteModel note = new NoteModel();
            note.setPos(s * stepTicks);
            note.setLength((int) (step.gate() * stepTicks));
            note.setVelocity((int) (step.velocity() * 127.0f));
            note.setProbability((int) (step.probability() * 100.0f));
            note.setIterance(Iterance.fromInt(step.playCondition()));
            note.setFill(step.fillMode() != 0 ? step.fillMode() : (step.fill() > 0.001f ? 2 : 0));
            row.getNotes().add(note);
          }
        }
      }
    }
    expectEvent();
  }

  public Map<String, float[]> getAutomationData() {
    return automationData;
  }

  public void setAutomation(String param, int step, float value) {
    float[] array = automationData.computeIfAbsent(param, k -> new float[stepCount]);
    if (step >= 0 && step < stepCount) {
      array[step] = value;
    }
  }

  public float getAutomationValue(String param, int step) {
    float[] array = automationData.get(param);
    if (array == null || step < 0 || step >= stepCount) return -1f;
    return array[step];
  }

  public boolean hasAutomation(String param, int step) {
    float[] array = automationData.get(param);
    return array != null && step >= 0 && step < stepCount && array[step] >= 0.0f;
  }

  public float getAutomation(String param, int step) {
    return getAutomationValue(param, step);
  }

  public Set<String> getAutomatedParams() {
    return automationData.keySet();
  }

  public float[] getAutomationArray(String paramName) {
    return automationData.get(paramName);
  }

  public Map<String, Float> getKitParams() {
    return kitParams;
  }

  public void setKitParam(String param, float value) {
    kitParams.put(param, value);
  }

  public void setKitParams(Map<String, Float> params) {
    kitParams.clear();
    kitParams.putAll(params);
  }

  public float getKitParam(String param) {
    return kitParams.getOrDefault(param, -1f);
  }

  // --- Transport Setup & Sequencer Properties ---

  public ClipType getType() {
    return type;
  }

  public void setType(ClipType type) {
    this.type = type;
  }

  /**
   * C Clip::setPos (clip.cpp:353-388) + InstrumentClip::setPos (instrument_clip.cpp:379-436): move
   * the play position, re-deriving repeatCount and the playing direction, and the same for every
   * note row with an independent play position. Resets {@code ticksTilNextNoteRowEvent}
   * (expectEvent(), instrument_clip.cpp:4404) and {@code noteRowsNumTicksBehindClip} (:383).
   */
  public void setPos(int newPos) {
    int loopLength = getLoopLength();
    ticksTilNextNoteRowEvent = 0; // C instrument_clip.cpp:381 -> expectEvent() :4404
    noteRowsNumTicksBehindClip = 0; // C instrument_clip.cpp:383
    if (loopLength <= 0) {
      // A C clip always has loopLength > 0; this model can be built with zero steps. Keep the reset
      // that PlaybackHandler.start() did before this port rather than divide by zero.
      lastProcessedPos = 0;
      repeatCount = 0;
      return;
    }
    // newPos may be negative (C: Session::armClipToStartOrSoloUsingQuantization subtracts)
    if (newPos < 0) {
      newPos += loopLength;
      repeatCount = -1;
    } else {
      repeatCount = Integer.divideUnsigned(newPos, loopLength);
      newPos -= repeatCount * loopLength;
    }

    currentlyPlayingReversed =
        (sequenceDirectionMode == SequenceDirection.REVERSE
            || (sequenceDirectionMode == SequenceDirection.PINGPONG && (repeatCount & 1) != 0));

    if (currentlyPlayingReversed) {
      if (newPos != 0) {
        newPos = loopLength - newPos;
      } else {
        repeatCount--; // Cos it's going to get incremented as a side effect of reversed clips
        // starting at pos 0 after which they'll immediately wrap — by the zero-increment
        // processCurrentPos(0) the playback start runs first (PlaybackHandler.start).
      }
    }

    lastProcessedPos = newPos;

    // instrument_clip.cpp:404-436 — rows with independent play positions. The row's newPos is the
    // value AFTER Clip::setPos reduced it, exactly as in the C (it passes the same variable).
    for (NoteRowModel row : noteRows.values()) {
      row.setPosIfIndependent(newPos, sequenceDirectionMode);
    }
  }

  /**
   * C Clip::incrementPos (clip.cpp:1173-1178) + InstrumentClip::incrementPos
   * (instrument_clip.cpp:4728-4750): advances {@link #lastProcessedPos} (negating {@code numTicks}
   * when playing in reverse) and, once {@code ticksTilNextNoteRowEvent <= 0}, advances every note
   * row with an independent play position by {@code noteRowsNumTicksBehindClip} (negated for rows
   * playing in reverse).
   */
  public void incrementPos(int numTicks) {
    int clipTicks = currentlyPlayingReversed ? -numTicks : numTicks;
    lastProcessedPos += clipTicks;

    if (type == ClipType.INSTRUMENT) {
      advanceNoteRowSchedule(numTicks);
    }
  }

  private void advanceNoteRowSchedule(int numTicks) {
    ticksTilNextNoteRowEvent -= numTicks; // C instrument_clip.cpp:4731
    noteRowsNumTicksBehindClip += numTicks; // C instrument_clip.cpp:4732

    if (ticksTilNextNoteRowEvent <= 0) { // C instrument_clip.cpp:4734
      for (NoteRowModel row : noteRows.values()) {
        if (row.hasIndependentPlayPos()) {
          row.incrementPosIfIndependent(noteRowsNumTicksBehindClip);
        }
      }
    }
  }

  /**
   * C InstrumentClip::expectEvent (instrument_clip.cpp:4403-4406): flushes any quiet ticks already
   * elapsed for independent rows and forces the next tick to re-evaluate row scheduling.
   */
  public void expectEvent() {
    flushPendingNoteRowTicks();
    ticksTilNextNoteRowEvent = 0;
    ticksTilNextEvent = 0;
  }

  private void flushPendingNoteRowTicks() {
    if (noteRowsNumTicksBehindClip > 0) {
      for (NoteRowModel row : noteRows.values()) {
        if (row.hasIndependentPlayPos()) {
          row.flushIndependentPos(noteRowsNumTicksBehindClip);
        }
      }
      noteRowsNumTicksBehindClip = 0;
    }
  }

  @Override
  public int getLoopLength() {
    return loopLength > 0 ? loopLength : (stepCount * (tripletMode ? 32 : 24));
  }

  public void setLoopLength(int loopLength) {
    this.loopLength = loopLength;
    expectEvent();
  }

  public Object getSound() {
    return sound;
  }

  public void setSound(Object sound) {
    this.sound = sound;
  }

  /**
   * Returns true when note row {@code row} allows note tails (C {@code
   * InstrumentClip::allowNoteTails}, {@code instrument_clip.cpp:3350-3371}).
   */
  public boolean allowNoteTails(int row) {
    return NoteRowModel.allowsNoteTails(sound, row);
  }

  public int getTicksTilNextEvent() {
    return ticksTilNextEvent;
  }

  public int getMaxLength() {
    return Integer.MAX_VALUE;
  }

  public void resumePlayback(boolean mayMakeSound) {
    for (NoteRowModel row : noteRows.values()) {
      row.resumePlayback(lastProcessedPos, getLoopLength(), mayMakeSound);
    }
    expectEvent(); // C instrument_clip.cpp:1144
  }

  public void expectNoFurtherTicks(boolean actuallySoundChange) {
    if (actuallySoundChange && sound != null) {
      if (sound instanceof org.deluge.engine.FirmwareSound) {
        ((org.deluge.engine.FirmwareSound) sound).noteOffAll();
      } else if (sound instanceof org.deluge.engine.FirmwareKit) {
        for (org.deluge.engine.FirmwareSound drum :
            ((org.deluge.engine.FirmwareKit) sound).drumSounds) {
          drum.noteOffAll();
        }
      }
    }
  }

  @Override
  public boolean isPlayingAutomationNow() {
    return false;
  }

  @Override
  public boolean backtrackingCouldLoopBackToEnd() {
    return false;
  }

  public void processCurrentPos(int ticksSinceLast) {
    int effectiveLength = getLoopLength();
    if (effectiveLength <= 0) return;

    if (currentlyPlayingReversed) {
      if (lastProcessedPos < 0) {
        lastProcessedPos += effectiveLength;
      }
    }

    // C clip.cpp:244-251 calls posReachedEnd() here, which only extends a linearly-recording clip
    // (clip.cpp:808) — not ported. It must NOT touch lastProcessedPos: an earlier translation did
    // `lastProcessedPos %= effectiveLength`, which zeroed the position before the wrap below could
    // see it, so repeatCount never advanced (breaking iterance) and the forward pingpong branch was
    // unreachable.

    int ticksTilEnd;
    boolean didPingpong = false;

    if (currentlyPlayingReversed) {
      if (lastProcessedPos == 0) {
        repeatCount++;
        if (sequenceDirectionMode == SequenceDirection.PINGPONG) {
          lastProcessedPos = -lastProcessedPos;
          currentlyPlayingReversed = !currentlyPlayingReversed;
          timelineParamManager().notifyPingpongOccurred();
          didPingpong = true;
          ticksTilEnd = effectiveLength - lastProcessedPos;
        } else {
          ticksTilEnd = effectiveLength; // C clip.cpp:271-274 (!ticksTilEnd -> loopLength)
        }
      } else {
        ticksTilEnd = lastProcessedPos;
      }
    } else {
      ticksTilEnd = effectiveLength - lastProcessedPos;
      if (ticksTilEnd <= 0) {
        lastProcessedPos -= effectiveLength;
        repeatCount++;

        if (sequenceDirectionMode == SequenceDirection.PINGPONG) {
          if (lastProcessedPos > 0) {
            lastProcessedPos = effectiveLength - lastProcessedPos;
          }
          currentlyPlayingReversed = !currentlyPlayingReversed;
          timelineParamManager().notifyPingpongOccurred();
          didPingpong = true;
        }
        ticksTilEnd += effectiveLength;
      }
    }

    ticksTilNextEvent = ticksTilEnd;

    org.deluge.modulation.params.ParamManager clipParams = timelineParamManager();
    if (clipParams.mightContainAutomation()) {
      boolean mayInterpolate = (type == ClipType.INSTRUMENT || type == ClipType.AUDIO);
      clipParams.processCurrentPos(
          lastProcessedPos, effectiveLength, currentlyPlayingReversed, didPingpong, mayInterpolate);
      if (clipParams.ticksTilNextEvent < ticksTilNextEvent) {
        ticksTilNextEvent = clipParams.ticksTilNextEvent;
      }
    }

    // ── Sequencer Note Processing (CLIP type) ──
    if (type == ClipType.INSTRUMENT) {
      if (sound instanceof org.deluge.engine.FirmwareKit kit) {
        boolean mayInterpolate = true;
        for (org.deluge.engine.FirmwareSound drumSound : kit.drumSounds) {
          if (drumSound.paramManager.mightContainAutomation()) {
            drumSound.paramManager.processCurrentPos(
                lastProcessedPos, effectiveLength, currentlyPlayingReversed, false, mayInterpolate);
          }
        }
      }

      // C instrument_clip.cpp:706-737 — only process NoteRows when ticksTilNextNoteRowEvent <= 0.
      if (ticksTilNextNoteRowEvent <= 0) {
        if (lastProcessedPos != 0 && currentlyPlayingReversed) {
          ticksTilNextNoteRowEvent = lastProcessedPos;
        } else {
          ticksTilNextNoteRowEvent = effectiveLength - lastProcessedPos;
        }

        List<org.deluge.model.PendingNoteOn> pendingNoteOns = new ArrayList<>();
        List<Integer> pendingNoteOffs = new ArrayList<>();

        for (NoteRowModel noteRow : noteRows.values()) {
          int dist =
              noteRow.processCurrentPos(
                  noteRowsNumTicksBehindClip,
                  pendingNoteOns,
                  pendingNoteOffs,
                  lastProcessedPos,
                  effectiveLength,
                  currentlyPlayingReversed,
                  didPingpong,
                  sequenceDirectionMode,
                  sound);
          if (dist < ticksTilNextNoteRowEvent) {
            ticksTilNextNoteRowEvent = dist;
          }
        }

        noteRowsNumTicksBehindClip = 0;

        for (int pitchToRelease : pendingNoteOffs) {
          releaseNote(pitchToRelease);
        }

        evaluatePlayConditionsAndTrigger(pendingNoteOns);
      }

      // C instrument_clip.cpp:910-912
      if (ticksTilNextNoteRowEvent < ticksTilNextEvent) {
        ticksTilNextEvent = ticksTilNextNoteRowEvent;
      }
    }
  }

  // C InstrumentClip::lastProbabilities / lastProbabiltyPos (instrument_clip.h:259-260), sized
  // kNumProbabilityValues there. Every value the firmware itself produces indexes 0..19 (plain
  // 1..20, follow-previous 128|1..19 — instrument_clip_view.cpp:3070-3077 only latches below the
  // max). 128 slots here, so a corrupt file's out-of-range byte (undefined behaviour in the C)
  // cannot
  // throw.
  private final boolean[] lastProbabilities = new boolean[128];
  private final int[] lastProbabiltyPos = new int[128];

  /** C: util/functions.h:313-315 getRandom255() = CONG >> 24, on the firmware's shared CONG. */
  private static int getRandom255() {
    return org.deluge.firmware2.Functions.getNoise() >>> 24;
  }

  /**
   * C {@code currentPlaybackMode->willClipContinuePlayingAtEnd(modelStack)}, which feeds iterance's
   * LAST condition (instrument_clip.cpp:716-717). The session answer needs launch scheduling and
   * soloing (session.cpp:2719-2760) and is not ported; this defaults to its no-launch-scheduled
   * answer, "it will loop", so LAST notes stay silent until a caller sets it.
   */
  public transient volatile boolean willContinuePlayingAtEnd = true;

  /**
   * Port of the play-condition half of InstrumentClip::processCurrentPos
   * (instrument_clip.cpp:738-905): probability (including follow-previous and sum-to-100), then
   * iterance, then fill, and only a note passing all three sounds.
   */
  private void evaluatePlayConditionsAndTrigger(
      List<org.deluge.model.PendingNoteOn> pendingNoteOns) {
    final int kNumProbabilityValues = NoteModel.NUM_PROBABILITY_VALUES;
    // see if it's starting or ending - might be needed for iterance
    boolean ending = !willContinuePlayingAtEnd; // C :716-717
    int count = pendingNoteOns.size();

    // Count up how many of each probability there are
    // uint8_t[kNumProbabilityValues], C :740-741, indexed ONE HIGHER than the C: C
    // probabilityCount[p] is Java probabilityCount[p]. A file can carry probability 0 (the
    // loader clamp at note_row.cpp:3494 lets it through), and the C then reads and writes
    // probabilityCount[-1] — undefined behaviour on a stack byte. Slot 0 is that byte made
    // defined (zero-initialised), so a 0% note takes the C's own path: it consumes the same random
    // draw and fails `probabilityValue < 0`, i.e. never plays, instead of throwing here.
    int[] probabilityCount = new int[128];

    // Check whether special case where all probability adds up to 100%
    int probabilitySum = 0;

    boolean doingSumTo100 = false;
    int winningI = -1;

    sumTo100:
    {
      for (int i = 0; i < count; i++) {
        int p = pendingNoteOns.get(i).probability;

        // If we found a 100%, we know we're not doing sum-to-100
        if (p == kNumProbabilityValues) {
          break sumTo100; // C: goto skipDoingSumTo100
        }

        // If any follow-previous-probability, skip this statistics-grabbing
        if ((p & 128) != 0) {
          continue;
        }

        probabilitySum += p;

        probabilityCount[p]++;
      }

      doingSumTo100 = (probabilitySum == kNumProbabilityValues);

      if (doingSumTo100) {
        int probabilityValueForSummers = (getRandom255() * kNumProbabilityValues) >> 8;

        int probabilitySumSecondPass = 0;

        boolean foundWinner = false;

        for (int i = 0; i < count; i++) {

          // If any follow-previous-probability, skip this statistics-grabbing
          if ((pendingNoteOns.get(i).probability & 128) != 0) {
            continue;
          }

          int probability = pendingNoteOns.get(i).probability;

          probabilitySumSecondPass += probability;

          lastProbabiltyPos[probability] = lastProcessedPos;

          if (!foundWinner && probabilitySumSecondPass > probabilityValueForSummers) {
            winningI = i;
            lastProbabilities[probability] = true;

            foundWinner = true;
          } else {
            // Mark down this "loser"
            lastProbabilities[probability] = false;
          }
        }
      }
    }

    // Go through each pending note-on
    for (int i = 0; i < count; i++) {
      org.deluge.model.PendingNoteOn noteOn = pendingNoteOns.get(i);

      boolean conditionPassed;

      // If it's a 100%, which usually will be the case...
      if (noteOn.probability == kNumProbabilityValues) {
        conditionPassed = true;
      }

      // Otherwise...
      else {
        int probability = noteOn.probability & 127;
        boolean doNewProbability = false;

        // If based on a previous probability...
        if ((noteOn.probability & 128) != 0) {

          // Check that that previous probability value is still valid. It normally should be,
          // unless
          // the user has changed the probability of that "previous" note
          if (lastProbabiltyPos[probability] == -1
              || lastProbabiltyPos[probability] == lastProcessedPos) {
            doNewProbability = true; // C: goto doNewProbability
            conditionPassed = false;
          } else {
            conditionPassed = lastProbabilities[probability];
          }
        }

        // Or if not based on a previous probability...
        else {

          // If we're summing to 100...
          if (doingSumTo100) {
            conditionPassed = (i == winningI);
          }

          // Or if not summing to 100...
          else {
            doNewProbability = true;
            conditionPassed = false;
          }
        }

        if (doNewProbability) {
          // If the outcome of this probability has already been decided (by another note with same
          // probability)
          if (probabilityCount[probability] >= 254) {
            conditionPassed = probabilityCount[probability] == 255;
          }

          // Otherwise, decide it now
          else {
            int probabilityValue = (getRandom255() * kNumProbabilityValues) >> 8;
            conditionPassed = (probabilityValue < probability);

            lastProbabilities[kNumProbabilityValues - probability] = !conditionPassed;
            lastProbabiltyPos[kNumProbabilityValues - probability] = lastProcessedPos;

            lastProbabilities[probability] = conditionPassed;
            lastProbabiltyPos[probability] = lastProcessedPos;

            // Store the outcome, for any neighbouring notes
            probabilityCount[probability] = conditionPassed ? 255 : 254;
          }
        }
      }

      // if probably setting has resulted in a note on
      if (conditionPassed) {
        // now we check if we should skip note based on iteration condition
        Iterance iterance = noteOn.iterance;

        // If it's an iteration dependence...
        if (iterance != null && !iterance.isDefault()) {
          conditionPassed =
              iterance.passesCheck(noteOn.noteRow.getRepeatCount(repeatCount), ending);
        }

        // lastly, if after checking iteration we still have a note on
        // we'll check if that note should be sounded based on fill state
        if (conditionPassed) {
          // check if it's a FILL note and SYNC_SCALING is *not* pressed
          if (noteOn.fill == NoteModel.FILL_MODE_FILL && !ProjectModel.isFillModeActive()) {
            conditionPassed = false;
          }
          // check if it's a NOT FILL note and SYNC_SCALING is pressed
          else if (noteOn.fill == NoteModel.FILL_MODE_NOT_FILL && ProjectModel.isFillModeActive()) {
            conditionPassed = false;
          }
        }
      }

      // probability, iterance and fill conditions have passed
      if (conditionPassed) {
        triggerNote(noteOn);
      }
    }
  }

  private void releaseNote(int pitch) {
    if (sound instanceof org.deluge.engine.FirmwareSound) {
      ((org.deluge.engine.FirmwareSound) sound).releaseNote(pitch);
    } else if (sound instanceof org.deluge.engine.FirmwareKit) {
      var kit = (org.deluge.engine.FirmwareKit) sound;
      if (pitch < kit.drumSounds.size()) {
        kit.drumSounds.get(pitch).releaseNote(60);
      }
    } else if (sound instanceof org.deluge.engine.FirmwareMidiInstrument) {
      ((org.deluge.engine.FirmwareMidiInstrument) sound).releaseNote(pitch);
    }
  }

  private void triggerNote(org.deluge.model.PendingNoteOn noteOn) {
    if (sound instanceof org.deluge.engine.FirmwareSound) {
      ((org.deluge.engine.FirmwareSound) sound)
          .triggerNote(noteOn.noteRow.getPitch(), noteOn.velocity);
    } else if (sound instanceof org.deluge.engine.FirmwareKit) {
      ((org.deluge.engine.FirmwareKit) sound)
          .triggerDrum(noteOn.noteRow.getPitch(), noteOn.velocity);
    } else if (sound instanceof org.deluge.engine.FirmwareMidiInstrument) {
      ((org.deluge.engine.FirmwareMidiInstrument) sound)
          .triggerNote(noteOn.noteRow.getPitch(), noteOn.velocity);
    }
  }

  // --- Resize Helper ---

  void resizeAutomationArrays(int oldStepCount, int newStepCount) {
    for (Map.Entry<String, float[]> entry : automationData.entrySet()) {
      float[] old = entry.getValue();
      float[] updated = new float[newStepCount];
      java.util.Arrays.fill(updated, -1f);
      System.arraycopy(old, 0, updated, 0, Math.min(oldStepCount, newStepCount));
      entry.setValue(updated);
    }

    // Also resize row automation arrays
    for (NoteRowModel row : noteRows.values()) {
      for (Map.Entry<String, float[]> entry : row.getRowAutomation().entrySet()) {
        float[] old = entry.getValue();
        float[] updated = new float[newStepCount];
        java.util.Arrays.fill(updated, -1f);
        System.arraycopy(old, 0, updated, 0, Math.min(oldStepCount, newStepCount));
        entry.setValue(updated);
      }
    }
  }
}
