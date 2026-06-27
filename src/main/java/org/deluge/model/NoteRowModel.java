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
  private transient ParamManager paramManager = new ParamManager();

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
        writeNote.setProbability(note.getProbabilityPercent());
        writeNote.setIterance(note.getIterance());
        writeNote.setFill(note.getFill());
        newNotes.add(writeNote);
      }
      lastPos = newPos;
    }

    notes.clear();
    notes.addAll(newNotes);
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

    int effectiveLength = (loopLengthIfIndependent > 0) ? loopLengthIfIndependent : clipLoopLength;
    boolean playingReversedNow =
        (loopLengthIfIndependent > 0)
            ? currentlyPlayingReversedIfIndependent
            : clipCurrentlyPlayingReversed;
    boolean didPingpong = false;

    if (loopLengthIfIndependent > 0) {
      if (playingReversedNow) {
        if (lastProcessedPosIfIndependent < 0) {
          lastProcessedPosIfIndependent += effectiveLength;
        }
        if (lastProcessedPosIfIndependent == 0) {
          repeatCountIfIndependent++;
          lastProcessedPosIfIndependent = 0;
        }
      } else {
        int ticksTilEnd = effectiveLength - lastProcessedPosIfIndependent;
        if (ticksTilEnd <= 0) {
          lastProcessedPosIfIndependent -= effectiveLength;
          repeatCountIfIndependent++;
        }
      }
    }

    int currentPos =
        (loopLengthIfIndependent > 0) ? lastProcessedPosIfIndependent : clipLastProcessedPos;

    if (org.deluge.engine.FirmwareAudioEngine.debugTelemetry) {
      int tempStartTick = (currentPos - ticksSinceLast + effectiveLength) % effectiveLength;
      System.out.printf("[DIAG NoteRow] pitch=%d currentPos=%d ticksSinceLast=%d startTick=%d\n",
          pitch, currentPos, ticksSinceLast, tempStartTick);
    }

    if (paramManager.mightContainAutomation()) {
      paramManager.processCurrentPos(
          currentPos, effectiveLength, playingReversedNow, didPingpong, true);
    }

    if (muted) return Integer.MAX_VALUE;

    int ticksTilNextNoteEvent = Integer.MAX_VALUE;
    int startTick = (currentPos - ticksSinceLast + effectiveLength) % effectiveLength;

    for (NoteModel note : notes) {
      int releasePos = (note.getPos() + note.getLength()) % effectiveLength;
      if (isTickReached(startTick, ticksSinceLast, releasePos, effectiveLength)) {
        pendingNoteOffs.add(pitch);
      }
      if (isTickReached(startTick, ticksSinceLast, note.getPos(), effectiveLength)) {
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
                    note.getProbabilityPercent(),
                    note.getIterance(),
                    note.getFill()));
          }
        }
      }

      int dist = note.getPos() - currentPos;
      if (playingReversedNow) dist = -dist;
      if (dist <= 0) dist += effectiveLength;
      if (dist < ticksTilNextNoteEvent) {
        ticksTilNextNoteEvent = dist;
      }
    }

    return Math.min(ticksTilNextNoteEvent, paramManager.ticksTilNextEvent);
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
