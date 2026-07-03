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
    launchEventAtSwungTickCount = 0;
    numRepeatsTilLaunch = 0;
    lastClockTick = 0;
    if (midiClockOut != null) midiClockOut.start(); // 0xFA — external gear starts from the top
    FirmwareDisplay.get().setText(" PLAYING ");
    if (currentProject != null) {
      for (ClipModel clip : currentProject.getClips()) {
        clip.lastProcessedPos = 0;
        clip.repeatCount = 0;
      }
    }
    arrangement.resetPlayPos(0);
  }

  public synchronized void stop() {
    playing = false;
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
    while (ticksRemaining > 0) {
      int toAdvance = Math.min(ticksRemaining, swungTicksTilNextEvent);
      if (toAdvance <= 0) toAdvance = 1;

      // ── Bit-Accurate Swing Math ──
      int effectiveAdvance = toAdvance;
      if (currentProject.getSwingAmount() != 0) {
        int leftShift =
            6
                - currentProject
                    .getSwingInterval(); // Offset 6 for 96 PPQN (C++ uses 9/10 for 1536 PPQN)
        int swingTicks = 3 << leftShift;
        if ((lastSwungTickActioned % (swingTicks * 2)) < swingTicks) {
          effectiveAdvance = (toAdvance * (50 + currentProject.getSwingAmount())) / 50;
        } else {
          effectiveAdvance = (toAdvance * (50 - currentProject.getSwingAmount())) / 50;
        }
      }

      lastSwungTickActioned += effectiveAdvance;
      arrangement.advance(effectiveAdvance);

      for (ClipModel clip : currentProject.getClips()) {
        clip.lastProcessedPos += effectiveAdvance;
        clip.processCurrentPos(effectiveAdvance);
      }

      // Update next event distance
      if (currentProject != null) {
        swungTicksTilNextEvent = currentProject.getSwungTicksTilNextEvent();
      }

      ticksRemaining -= toAdvance;
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
}
