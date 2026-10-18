package org.deluge.playback;

import org.deluge.hid.FirmwareDisplay;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;

/**
 * Port of the Deluge's PlaybackHandler class. Manages transport state and high-fidelity timing
 * (Swing, Quantization) on the unified ProjectModel.
 */
public class PlaybackHandler {
  private volatile boolean playing = false;
  private ProjectModel currentProject;
  private final Arrangement arrangement = new Arrangement();
  public volatile int lastSwungTickActioned = 0;
  private int swungTicksTilNextEvent = 0;
  private volatile int syncMode = 0; // 0 = INTERNAL, 1 = EXTERNAL_MIDI

  // Session launch scheduler (C Session, playback/mode/session.cpp). The swung tick at which armed
  // clips fire (0 = no launch scheduled) and how many clip repeats remain until then. Set by the
  // scheduler (Phase 3, scheduleLaunchTiming); armed clips act at this tick and return to
  // ArmState.OFF. Phase 1 adds the state; the wiring lands in later phases.
  private volatile long launchEventAtSwungTickCount = 0;
  private int numRepeatsTilLaunch = 0;

  /**
   * Outbound MIDI clock sink (the Deluge as clock master). Decoupled so the transport doesn't
   * depend on the MIDI layer and stays unit-testable. Wire to {@code MidiEngine}'s
   * sendStart/sendStop/sendClock.
   */
  public interface MidiClockSink {
    void start();

    void stop();

    void clock();
  }

  // MIDI clock is 24 PPQN; our internal resolution is 96 PPQN → one 0xF8 every 4 internal ticks.
  private static final int INTERNAL_TICKS_PER_MIDI_CLOCK = 4;
  private MidiClockSink midiClockOut;
  private int lastClockTick = 0; // internal tick at which the last 0xF8 was emitted
  private int swingSubTicks = 0; // fractional swing progress in fiftieths of an input tick

  public void setMidiClockOut(MidiClockSink sink) {
    this.midiClockOut = sink;
  }

  public int getSyncMode() {
    return syncMode;
  }

  public void setSyncMode(int mode) {
    this.syncMode = mode;
  }

  public long getLaunchEventAtSwungTickCount() {
    return launchEventAtSwungTickCount;
  }

  public int getNumRepeatsTilLaunch() {
    return numRepeatsTilLaunch;
  }

  /** Whether a session launch event is currently scheduled. */
  public boolean hasLaunchEvent() {
    return launchEventAtSwungTickCount > 0;
  }

  /**
   * Swung ticks from the last actioned tick until the scheduled launch event (never negative), or
   * -1 when no launch is scheduled. This is what the launch playhead (Phase 6) counts down.
   */
  public long getSwungTicksTilLaunch() {
    if (!hasLaunchEvent()) return -1;
    return Math.max(0, launchEventAtSwungTickCount - lastSwungTickActioned);
  }

  /**
   * Schedule the session launch event at {@code atSwungTickCount}. Phase 3 drives this from the
   * faithful {@code scheduleLaunchTiming}; exposed now so the state has a single mutator.
   */
  public synchronized void setLaunchEvent(long atSwungTickCount, int numRepeatsUntil) {
    this.launchEventAtSwungTickCount = atSwungTickCount;
    this.numRepeatsTilLaunch = numRepeatsUntil;
  }

  /** Clear any scheduled launch event (C: {@code launchEventAtSwungTickCount = 0}). */
  public synchronized void clearLaunchEvent() {
    this.launchEventAtSwungTickCount = 0;
    this.numRepeatsTilLaunch = 0;
  }

  /**
   * Faithful port of {@code Session::scheduleLaunchTiming} (session.cpp:746). Schedules the launch
   * at {@code atTickCount} only if it is later than any already-scheduled event (so a longer clip
   * doesn't get its launch pulled earlier by a shorter one), and pulls {@code
   * swungTicksTilNextEvent} in so the transport wakes at the launch tick.
   */
  public synchronized void scheduleLaunchTiming(long atTickCount, int numRepeatsUntil) {
    if (atTickCount > launchEventAtSwungTickCount) {
      launchEventAtSwungTickCount = atTickCount;
      numRepeatsTilLaunch = numRepeatsUntil;
      long ticksTilLaunchEvent = atTickCount - lastSwungTickActioned;
      if (swungTicksTilNextEvent > ticksTilLaunchEvent) {
        swungTicksTilNextEvent = (int) ticksTilLaunchEvent;
      }
    }
  }

  /**
   * Assembles Phase 2's quantization with the live transport to schedule the next quantized launch
   * — {@code Session::armAllClipsToStop} + {@code investigateSyncedLaunch}
   * (LAUNCH_USING_QUANTIZATION branch). Given the clip being synced to (its loop length and current
   * position within the quantization window), quantizes and calls {@link #scheduleLaunchTiming} at
   * the next boundary.
   *
   * @param waitForClipLoopLength loop length (ticks) of the clip being synced to (0 = nothing to
   *     sync to, no-op)
   * @param longestStartingClipLength loop length of the longest clip being started now (pass the
   *     same as {@code waitForClipLoopLength} to disable subdivision)
   * @param posWithinQuantization the sync clip's current forward position in ticks
   * @param numRepeatsUntil clip repeats remaining until the launch fires
   * @param allowSubdivided whether sub-loop-length quantization is permitted
   */
  public synchronized void scheduleQuantizedLaunch(
      long waitForClipLoopLength,
      long longestStartingClipLength,
      long posWithinQuantization,
      int numRepeatsUntil,
      boolean allowSubdivided) {
    if (waitForClipLoopLength <= 0) {
      return; // nothing playing to sync to
    }
    long quantization =
        LaunchQuantizer.syncedQuantization(
            waitForClipLoopLength, longestStartingClipLength, allowSubdivided);
    long ticksTilSwap = LaunchQuantizer.ticksTilLaunch(posWithinQuantization, quantization);
    scheduleLaunchTiming(lastSwungTickActioned + ticksTilSwap, numRepeatsUntil);
  }

  public synchronized void setProject(ProjectModel project) {
    this.currentProject = project;
  }

  public synchronized ProjectModel getProject() {
    return currentProject;
  }

  public synchronized boolean isPlaying() {
    return playing;
  }

  public synchronized void start() {
    playing = true;
    lastSwungTickActioned = 0;
    swungTicksTilNextEvent = 0;
    swingSubTicks = 0;
    launchEventAtSwungTickCount = 0;
    numRepeatsTilLaunch = 0;
    lastClockTick = 0;
    if (midiClockOut != null) midiClockOut.start(); // 0xFA — external gear starts from the top
    FirmwareDisplay.get().setText(" PLAYING ");
    if (currentProject != null) {
      for (ClipModel clip : currentProject.getClips()) {
        // C InstrumentClip::setPos(…, 0): also resets rows with independent play positions,
        // whose repeat count drives iterance (model_stack.cpp:100-107).
        clip.setPos(0);
      }
      // C: playback starts with swungTicksTilNextEvent = 0, so the first actioned tick has a zero
      // increment and every clip runs processCurrentPos(…, 0) before any position moves
      // (playback_handler.cpp:533 in setupPlayback, then Session::doTickForward →
      // session.cpp:2502).
      // That is what takes a reversed clip's repeatCount from setPos's -1 to 0 (clip.cpp:259-260).
      // It also sounds the notes at pos 0 (note_row.cpp:2373).
      setArpClockActive(true);
      int next = Integer.MAX_VALUE;
      for (ClipModel clip : currentProject.getClips()) {
        clip.processCurrentPos(0);
        next = Math.min(next, clip.getTicksTilNextEvent());
      }
      next = Math.min(next, doTickForwardForArps());
      swungTicksTilNextEvent = Math.max(1, next);
    }
    arrangement.resetPlayPos(0);
  }

  public synchronized void stop() {
    playing = false;
    if (currentProject != null) {
      // C Clip::expectNoFurtherTicks -> InstrumentClip::stopAllNotesPlaying
      // (instrument_clip.cpp:1147-1160, 1198-1205): every sounding sequenced note gets its
      // note-off.
      for (ClipModel clip : currentProject.getClips()) {
        clip.stopAllNotesPlaying(true);
      }
    }
    setArpClockActive(false);
    swingSubTicks = 0;
    launchEventAtSwungTickCount = 0;
    numRepeatsTilLaunch = 0;
    if (midiClockOut != null) midiClockOut.stop(); // 0xFC
    FirmwareDisplay.get().setText(" STOPPED ");
  }

  /** Advance the sequencer by a number of ticks. Includes high-fidelity Swing logic. */
  public synchronized void advanceTicks(int numTicks) {
    if (org.deluge.engine.FirmwareAudioEngine.debugTelemetry && playing && Math.random() < 0.05) {}
    if (!playing || currentProject == null) return;

    int ticksRemaining = numTicks;
    while (true) {
      int nextDeadline = swungTicksTilNextEvent;
      for (ClipModel clip : currentProject.getClips()) {
        nextDeadline = Math.min(nextDeadline, clip.getTicksTilNextEvent());
      }
      int deadlineTicks = Math.max(1, nextDeadline);

      // ── Bit-Accurate Swing Math (C playback_handler.cpp:1768-1790) ──
      // Each input tick contributes 50 sub-ticks. One swung tick costs (50 + swingAmount) sub-ticks
      // in the first half of the swing pair and (50 - swingAmount) sub-ticks in the second half, so
      // a complete pair of (2 * swingTicks) swung ticks always costs (2 * swingTicks * 50)
      // sub-ticks (= 2 * swingTicks input ticks) and preserves overall playback tempo.
      int effectiveAdvance;
      int swingAmount = Math.max(-49, Math.min(49, currentProject.getSwingAmount()));
      if (swingAmount == 0) {
        swingSubTicks = 0;
        if (ticksRemaining <= 0) break;
        int toAdvance = Math.min(ticksRemaining, deadlineTicks);
        effectiveAdvance = toAdvance;
        ticksRemaining -= toAdvance;
      } else {
        int leftShift =
            Math.max(
                0,
                6
                    - currentProject
                        .getSwingInterval()); // Offset 6 for 96 PPQN (C++ uses 9/10 for 1536 PPQN)
        int swingTicks = 3 << leftShift;
        boolean firstHalf = Math.floorMod(lastSwungTickActioned, swingTicks * 2) < swingTicks;
        int costPerSwungTick = firstHalf ? (50 + swingAmount) : (50 - swingAmount);
        int swungLeftInHalf = swingTicks - Math.floorMod(lastSwungTickActioned, swingTicks);
        int maxSwungAdvance = Math.min(deadlineTicks, swungLeftInHalf);
        int targetSubTicks = maxSwungAdvance * costPerSwungTick;

        if (swingSubTicks < targetSubTicks && ticksRemaining > 0) {
          int neededSubTicks = targetSubTicks - swingSubTicks;
          int inputTicksNeeded = (neededSubTicks + 49) / 50;
          int toConsume = Math.min(ticksRemaining, inputTicksNeeded);
          swingSubTicks += toConsume * 50;
          ticksRemaining -= toConsume;
        }

        effectiveAdvance = Math.min(maxSwungAdvance, swingSubTicks / costPerSwungTick);
        if (effectiveAdvance <= 0) {
          break; // Fractional progress preserved in swingSubTicks for next advanceTicks call.
        }
        swingSubTicks -= effectiveAdvance * costPerSwungTick;
      }

      lastSwungTickActioned += effectiveAdvance;
      arrangement.advance(effectiveAdvance);

      int nextEventTicks = currentProject.getSwungTicksTilNextEvent();
      for (ClipModel clip : currentProject.getClips()) {
        clip.incrementPos(effectiveAdvance); // C clip.cpp:1173-1178, instrument_clip.cpp:4728-4750
        clip.processCurrentPos(effectiveAdvance);
        nextEventTicks = Math.min(nextEventTicks, clip.getTicksTilNextEvent());
      }
      nextEventTicks = Math.min(nextEventTicks, doTickForwardForArps());

      // Update next event distance (C playback_handler.cpp:1095-1097 clamps >= 1).
      swungTicksTilNextEvent = Math.max(1, nextEventTicks);
    }

    // Outbound MIDI clock (24 PPQN): emit one 0xF8 per 4 internal ticks crossed, following the
    // swung tick position so external gear swings with us.
    if (midiClockOut != null) {
      while (lastSwungTickActioned - lastClockTick >= INTERNAL_TICKS_PER_MIDI_CLOCK) {
        midiClockOut.clock();
        lastClockTick += INTERNAL_TICKS_PER_MIDI_CLOCK;
      }
    }

    // Update LED with bar:beat:tick
    int stepTicks = 24;
    int stepCount = 16;
    if (currentProject != null && !currentProject.getClips().isEmpty()) {
      stepTicks = currentProject.getClips().get(0).isTripletMode() ? 32 : 24;
      stepCount = currentProject.getClips().get(0).isTripletMode() ? 12 : 16;
    }
    int bars = (lastSwungTickActioned / (stepTicks * stepCount)) + 1;
    int beats = ((lastSwungTickActioned / stepTicks) % stepCount) + 1;
    int ticks = (lastSwungTickActioned % stepTicks) + 1;
    FirmwareDisplay.get().setText(String.format(" %02d:%02d:%02d ", bars, beats, ticks));
  }

  /**
   * C Session::doTickForward (session.cpp:2522-2535): at every playback event, each output's
   * arpeggiator gets doTickForwardForArp with its active clip's position; the result joins the
   * next-event deadline. Synth sounds and per-drum arps; the kit-level arp (kitArp,
   * kit.cpp:1136-1222) is not yet wired.
   */
  private int doTickForwardForArps() {
    int next = Integer.MAX_VALUE;
    if (currentProject == null) return next;
    for (ClipModel clip : currentProject.getClips()) {
      if (clip.getSound() instanceof org.deluge.engine.FirmwareSound fs) {
        int t =
            fs.fw2Sound.doTickForwardForArp(clip.lastProcessedPos, clip.currentlyPlayingReversed);
        next = Math.min(next, t);
      }
      if (clip.getSound() instanceof org.deluge.engine.FirmwareKit kit) {
        // C Kit::doTickForwardForArp (kit.cpp:1223-1240): each drum's own arpeggiator ticks at
        // its row's position. Rows with independent play positions are not modelled here.
        for (org.deluge.engine.FirmwareSound drum : kit.drumSounds) {
          int t =
              drum.fw2Sound.doTickForwardForArp(
                  clip.lastProcessedPos, clip.currentlyPlayingReversed);
          next = Math.min(next, t);
        }
      }
    }
    return next;
  }

  /** C playbackHandler.isEitherClockActive(), as the arpeggiator sees it (arpeggiator.cpp:1523). */
  private void setArpClockActive(boolean active) {
    if (currentProject == null) return;
    for (ClipModel clip : currentProject.getClips()) {
      if (clip.getSound() instanceof org.deluge.engine.FirmwareSound fs) {
        fs.fw2Sound.arpeggiator.playbackClockActive = active;
      }
      if (clip.getSound() instanceof org.deluge.engine.FirmwareKit kit) {
        for (org.deluge.engine.FirmwareSound drum : kit.drumSounds) {
          drum.fw2Sound.arpeggiator.playbackClockActive = active;
        }
      }
    }
  }
}
