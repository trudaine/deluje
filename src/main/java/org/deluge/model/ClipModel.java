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
    LOOP
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
  public transient Object sound; // The transient DSP engine reference (FirmwareSound / FirmwareKit)

  public NoteRowModel getOrCreateRow(int rowIndex) {
    return noteRows.computeIfAbsent(rowIndex, idx -> new NoteRowModel(idx));
  }

  public Map<Integer, NoteRowModel> getNoteRowsMap() {
    return noteRows;
  }

  public List<NoteRowModel> getNoteRowsList() {
    return new ArrayList<>(noteRows.values());
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

    // Deep-copy note rows
    for (Map.Entry<Integer, NoteRowModel> e : this.noteRows.entrySet()) {
      NoteRowModel oldRow = e.getValue();
      NoteRowModel newRow = copy.getOrCreateRow(e.getKey());
      newRow.setPitch(oldRow.getPitch());
      newRow.setMuted(oldRow.isMuted());
      newRow.setMutedBeforeStemExport(oldRow.isMutedBeforeStemExport());
      newRow.setExportStem(oldRow.isExportStem());

      // Copy notes
      List<NoteModel> copiedNotes = new ArrayList<>();
      for (NoteModel nm : oldRow.getNotes()) {
        NoteModel copyNote =
            new NoteModel(
                nm.getTickPos(),
                nm.getTickLen(),
                nm.getVelocity(),
                nm.getProbability(),
                nm.getSubTriggers());
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

    copy.playMode = this.playMode;
    copy.playDirection = this.playDirection;
    copy.tripletMode = this.tripletMode;
    copy.isArrangementOnly = this.isArrangementOnly;
    return copy;
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
    float[] array = row.getRowAutomation().computeIfAbsent(paramName, k -> new float[stepCount]);
    if (stepIndex >= 0 && stepIndex < stepCount) {
      array[stepIndex] = value;
    }
  }

  public float[] getRowAutomation(int rowIndex, String paramName) {
    NoteRowModel row = noteRows.get(rowIndex);
    return row != null ? row.getRowAutomation().get(paramName) : null;
  }

  public void setRawNoteEvents(int rowIndex, List<NoteModel> notes) {
    if (notes == null) {
      noteRows.remove(rowIndex);
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
    this.playDirection = playDirection;
  }

  public boolean isTripletMode() {
    return tripletMode;
  }

  public void setTripletMode(boolean tripletMode) {
    this.tripletMode = tripletMode;
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
        step = StepData.of(step.active(), step.velocity(), step.gate(), step.probability(), pitch);
      }

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
        if (existingNote == null) {
          NoteModel note = new NoteModel();
          note.setPos(notePos);
          note.setLength((int) (step.gate() * stepTicks));
          note.setVelocity((int) (step.velocity() * 127.0f));
          note.setProbability((int) (step.probability() * 100.0f));
          row.getNotes().add(note);
        } else {
          existingNote.setLength((int) (step.gate() * stepTicks));
          existingNote.setVelocity((int) (step.velocity() * 127.0f));
          existingNote.setProbability((int) (step.probability() * 100.0f));
        }
      } else {
        if (existingNote != null) {
          row.getNotes().remove(existingNote);
        }
      }
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
          row.getNotes().add(note);
        }
      }
    }
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
    playDirection = src.playDirection;
    tripletMode = src.tripletMode;
    isArrangementOnly = src.isArrangementOnly;
    rebuildNotesFromGrid();
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
            row.getNotes().add(note);
          }
        }
      }
    }
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

  @Override
  public int getLoopLength() {
    return loopLength > 0 ? loopLength : (stepCount * (tripletMode ? 32 : 24));
  }

  public void setLoopLength(int loopLength) {
    this.loopLength = loopLength;
  }

  public Object getSound() {
    return sound;
  }

  public void setSound(Object sound) {
    this.sound = sound;
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

    int endPos = currentlyPlayingReversed ? 0 : effectiveLength;
    if (lastProcessedPos == endPos && repeatCount >= 0) {
      lastProcessedPos %= effectiveLength;
    }

    int ticksTilEnd;
    boolean didPingpong = false;

    if (currentlyPlayingReversed) {
      if (lastProcessedPos == 0) {
        repeatCount++;
        if (sequenceDirectionMode == SequenceDirection.PINGPONG) {
          lastProcessedPos = -lastProcessedPos;
          currentlyPlayingReversed = !currentlyPlayingReversed;
          paramManager.notifyPingpongOccurred();
          didPingpong = true;
          ticksTilEnd = effectiveLength - lastProcessedPos;
        } else {
          ticksTilEnd = lastProcessedPos;
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
          paramManager.notifyPingpongOccurred();
          didPingpong = true;
        }
        ticksTilEnd += effectiveLength;
      }
    }

    if (paramManager.mightContainAutomation()) {
      boolean mayInterpolate = (type == ClipType.INSTRUMENT || type == ClipType.AUDIO);
      paramManager.processCurrentPos(
          lastProcessedPos, effectiveLength, currentlyPlayingReversed, didPingpong, mayInterpolate);
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

      List<org.deluge.model.PendingNoteOn> pendingNoteOns = new ArrayList<>();
      List<Integer> pendingNoteOffs = new ArrayList<>();
      ticksTilNextEvent = Integer.MAX_VALUE;

      for (NoteRowModel noteRow : noteRows.values()) {
        int dist =
            noteRow.processCurrentPos(
                ticksSinceLast,
                pendingNoteOns,
                pendingNoteOffs,
                lastProcessedPos,
                effectiveLength,
                currentlyPlayingReversed,
                sound);
        if (dist < ticksTilNextEvent) {
          ticksTilNextEvent = dist;
        }
      }

      int ticksTilEndVal =
          currentlyPlayingReversed ? lastProcessedPos : (effectiveLength - lastProcessedPos);
      if (ticksTilEndVal <= 0) ticksTilEndVal = effectiveLength;
      if (ticksTilEndVal < ticksTilNextEvent) ticksTilNextEvent = ticksTilEndVal;

      for (int pitchToRelease : pendingNoteOffs) {
        releaseNote(pitchToRelease);
      }

      for (org.deluge.model.PendingNoteOn noteOn : pendingNoteOns) {
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
