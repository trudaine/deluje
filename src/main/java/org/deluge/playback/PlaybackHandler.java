package org.deluge.playback;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import org.deluge.BridgeContract;
import org.deluge.hid.BridgeHolder;
import org.deluge.hid.FirmwareDisplay;
import org.deluge.model.ArmState;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.TrackModel;

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
  // ArmState.OFF.
  private volatile long launchEventAtSwungTickCount = 0;
  private int numRepeatsTilLaunch = 0;
  private long currentArmedLaunchLengthForOneRepeat = 0; // C session.cpp:766

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
  private long totalInputTicksTimes50 =
      0L; // cumulative input clock in fiftieths (playback_handler.cpp:1761)
  private volatile long runEpoch = 0L; // incremented on each start()/setProject()
  private long lastSeededAutomationGeneration = -1L;

  public long getRunEpoch() {
    return runEpoch;
  }

  /**
   * Ensures {@link ProjectModel#getSongParamManager()} has been seeded at {@link
   * #lastSwungTickActioned} whenever {@link ProjectModel#getAutomationGeneration()} changes (e.g.,
   * live curve edits after {@link #start()}). Safe to call from {@link #start()}, {@link
   * #setProject(ProjectModel)}, {@link #advanceTicks(int)}, or {@code JavaAudioDriver.stepBlock}.
   */
  public synchronized void ensureSongAutomationSeeded() {
    if (!playing || currentProject == null) {
      return;
    }
    long gen = currentProject.getAutomationGeneration();
    if (gen == lastSeededAutomationGeneration) {
      return;
    }
    lastSeededAutomationGeneration = gen;
    org.deluge.modulation.params.ParamManager sm = currentProject.getSongParamManager();
    int songNext = Integer.MAX_VALUE;
    boolean songAutomated = false;
    synchronized (sm) {
      if (sm.mightContainAutomation()) {
        sm.setPlayPos(lastSwungTickActioned, Integer.MAX_VALUE, false);
        sm.processCurrentPos(0, lastSwungTickActioned, Integer.MAX_VALUE, false, false, true);
        songNext = sm.ticksTilNextEvent;
        songAutomated = true;
      }
    }
    if (songAutomated) {
      currentProject.applyAutomatedSongParams();
    }
    currentProject.setSwungTicksTilNextEvent(songNext);
    swungTicksTilNextEvent = Math.max(1, Math.min(swungTicksTilNextEvent, songNext));
  }

  /**
   * Closed-form internal tick timestamp (multiplied by 50) for a target swung tick {@code
   * nextSwungTick}, ported verbatim from C {@code
   * PlaybackHandler::scheduleSwungTickFromExternalClock} (C playback_handler.cpp:1761-1790). At
   * default 96 PPQN ({@code inputTickMagnitude == 2}), {@code swingInterval == 6} (16th-note
   * default) yields {@code leftShift = 4}, {@code doubleSwingInterval = 48} ticks (two 16th notes),
   * and half-interval {@code 24} ticks.
   */
  public static long internalTickPositionTimes50(
      long nextSwungTick, int swingAmount, int swingInterval) {
    if (swingAmount == 0) {
      return nextSwungTick * 50L;
    }
    int leftShift = Math.max(10 - swingInterval, 0);
    long doubleSwingInterval = 3L << leftShift;
    long swungTickWithinInterval = Math.floorMod(nextSwungTick, doubleSwingInterval);
    long startOfSwingInterval = nextSwungTick - swungTickWithinInterval;
    long internalTickWithinIntervalTimes50;
    if (swungTickWithinInterval <= (doubleSwingInterval >> 1)) {
      internalTickWithinIntervalTimes50 = swungTickWithinInterval * (50L + swingAmount);
    } else {
      long ticksTilEnd = doubleSwingInterval - swungTickWithinInterval;
      internalTickWithinIntervalTimes50 =
          doubleSwingInterval * 50L - ticksTilEnd * (50L - swingAmount);
    }
    return internalTickWithinIntervalTimes50 + (startOfSwingInterval * 50L);
  }

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
    this.currentArmedLaunchLengthForOneRepeat =
        Math.max(0L, atSwungTickCount - lastSwungTickActioned);
  }

  /** Clear any scheduled launch event (C: {@code launchEventAtSwungTickCount = 0}). */
  public synchronized void clearLaunchEvent() {
    this.launchEventAtSwungTickCount = 0;
    this.numRepeatsTilLaunch = 0;
    this.currentArmedLaunchLengthForOneRepeat = 0;
  }

  /**
   * Faithful port of {@code Session::scheduleLaunchTiming} (session.cpp:759-774). Schedules the
   * launch at {@code atTickCount} only if it is later than any already-scheduled event (so a longer
   * clip doesn't get its launch pulled earlier by a shorter one), and pulls {@code
   * swungTicksTilNextEvent} in so the transport wakes at the launch tick.
   */
  public synchronized void scheduleLaunchTiming(long atTickCount, int numRepeatsUntil) {
    scheduleLaunchTiming(
        atTickCount, numRepeatsUntil, Math.max(0L, atTickCount - lastSwungTickActioned));
  }

  public synchronized void scheduleLaunchTiming(
      long atTickCount, int numRepeatsUntil, long armedLaunchLengthForOneRepeat) {
    if (atTickCount > launchEventAtSwungTickCount) {
      launchEventAtSwungTickCount = atTickCount;
      numRepeatsTilLaunch = numRepeatsUntil;
      currentArmedLaunchLengthForOneRepeat = armedLaunchLengthForOneRepeat;
      long ticksTilLaunchEvent = atTickCount - lastSwungTickActioned;
      if (swungTicksTilNextEvent > ticksTilLaunchEvent) {
        swungTicksTilNextEvent = (int) Math.max(1L, ticksTilLaunchEvent);
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
    scheduleLaunchTiming(lastSwungTickActioned + ticksTilSwap, numRepeatsUntil, quantization);
  }

  /** C Session::scheduleFillEvent (session.cpp:789-796). */
  public synchronized void scheduleFillEvent(ClipModel clip, long atTickCount) {
    clip.setFillEventAtTickCount(atTickCount);
    long ticksTilFillEvent = atTickCount - lastSwungTickActioned;
    if (swungTicksTilNextEvent > ticksTilFillEvent) {
      swungTicksTilNextEvent = (int) Math.max(1L, ticksTilFillEvent);
    }
  }

  /**
   * C Session::scheduleFillClip (session.cpp:1906-1964). Right-aligns a {@code FILL} clip so its
   * end lands on {@code launchEventAtSwungTickCount}. If {@code fillStartTime <= currentTick},
   * starts immediately at {@code pos = getMaxLength() - (launchEvent - currentTick)}; otherwise
   * schedules {@code fillEventAtTickCount = fillStartTime}. Refuses to steal a track that already
   * has an active non-fill clip.
   */
  public synchronized void scheduleFillClip(TrackModel track, ClipModel clip) {
    if (track == null || clip == null || !"fill".equals(clip.getLaunchStyle())) {
      return;
    }
    if (launchEventAtSwungTickCount <= 0) {
      return;
    }

    long fillStartTime =
        launchEventAtSwungTickCount
            - clip.getMaxLength()
            + (long) (numRepeatsTilLaunch - 1) * currentArmedLaunchLengthForOneRepeat;

    // C session.cpp:1916 uses `<` because scheduleFillEvent can set swungTicksTilNextEvent = 0 in
    // C++; Java's advanceTicks clamps deadlines to >= 1 tick, so `<=` starts at pos = 0 on the
    // exact current tick without a 1-tick delay.
    if (fillStartTime <= lastSwungTickActioned) {
      ClipModel active = track.getActiveClip();
      if (active != null && active.isActiveInSession()) {
        if (!"fill".equals(active.getLaunchStyle())) {
          launchSchedulingMightNeedCancelling();
          return; // C session.cpp:1922 — leave active non-fill clip alone
        }
        if (active != clip) {
          active.stopAllNotesPlaying(true);
          active.setActiveInSession(false);
          active.setArmState(ArmState.OFF);
        }
      }

      int pos = (int) (clip.getMaxLength() - (launchEventAtSwungTickCount - lastSwungTickActioned));
      clip.setArmState(ArmState.OFF);
      clip.setActiveInSession(true);
      int clipIdx = track.getClips().indexOf(clip);
      if (clipIdx >= 0) {
        track.setActiveClipIndex(clipIdx);
        syncBridgeCurrentClip(track, clipIdx);
      }
      clip.setPos(Math.max(0, pos));
      clip.resumePlayback(true);
      clip.setArmState(ArmState.ON_NORMAL); // C session.cpp:1956 — arm to stop at launchEvent
      syncClipActiveToSound(track);
    } else {
      scheduleFillEvent(clip, fillStartTime);
      clip.setArmState(ArmState.ON_NORMAL); // C session.cpp:1960
    }
  }

  /**
   * C Session::armClipToStartOrSoloUsingQuantization, late-start branch (session.cpp:1862-1880):
   * the clip becomes active on its output at the synced live position and catches any note under
   * the play-head (resumePlayback). getNumSwungTicksInSinceLastActionedSwungTick() is taken as 0.
   */
  private void lateStartClip(TrackModel track, ClipModel clip, int pos, boolean isDefault) {
    if (clip.getArmState() != ArmState.OFF && isDefault) { // In case also already armed
      clip.setArmState(ArmState.OFF);
      launchSchedulingMightNeedCancelling();
    }
    ClipModel old = track.getActiveClip();
    if (old != null && old != clip && old.isActiveInSession()) {
      old.stopAllNotesPlaying(true); // C assertActiveness → the output's other clip stops
      old.setActiveInSession(false);
    }
    clip.setActiveInSession(true);
    int idx = track.getClips().indexOf(clip);
    if (idx >= 0) {
      track.setActiveClipIndex(idx);
      syncBridgeCurrentClip(track, idx);
    }
    clip.setPos(pos);
    clip.resumePlayback(true);
    syncClipActiveToSound(track);
  }

  /** C Session::scheduleFillClips (session.cpp:1974-1989). */
  public synchronized void scheduleFillClips(int section) {
    if (currentProject == null) return;
    for (TrackModel track : currentProject.getTracks()) {
      for (ClipModel clip : track.getClips()) {
        if (clip.getSection() == section && "fill".equals(clip.getLaunchStyle())) {
          ClipModel active = track.getActiveClip();
          if (active != null
              && active.isActiveInSession()
              && !"fill".equals(active.getLaunchStyle())) {
            continue; // C session.cpp:1982 — non-fill already has this output
          }
          scheduleFillClip(track, clip);
        }
      }
    }
  }

  /**
   * C Session::launchSchedulingMightNeedCancelling (session.cpp:802-819): clears {@code
   * launchEventAtSwungTickCount} when no clip across the song remains armed.
   */
  public synchronized void launchSchedulingMightNeedCancelling() {
    if (currentProject == null) {
      clearLaunchEvent();
      return;
    }
    for (TrackModel track : currentProject.getTracks()) {
      for (ClipModel clip : track.getClips()) {
        if (clip.getArmState() != ArmState.OFF || clip.getFillEventAtTickCount() > 0) {
          return;
        }
      }
    }
    clearLaunchEvent();
  }

  /**
   * C Session::toggleClipStatus (session.cpp:997-1150) &amp;
   * Session::userWantsToArmClipsToStartOrSolo (session.cpp:1406-1480). Arms or toggles a single
   * session clip, respecting {@code FILL} and {@code ONCE} launch styles.
   */
  public synchronized void toggleClipStatus(TrackModel track, ClipModel clip, boolean doInstant) {
    if (track == null || clip == null || currentProject == null) return;

    boolean isActiveNow = track.getActiveClip() == clip && clip.isActiveInSession();

    // C session.cpp:1009-1017 — armed FILL clips can be disarmed before they start, but once
    // active on their output they are armed to stop and pressing them instant-stops (line 1095).
    if ("fill".equals(clip.getLaunchStyle())
        && clip.getArmState() != ArmState.OFF
        && !isActiveNow) {
      clip.setArmState(ArmState.OFF);
      clip.setFillEventAtTickCount(0);
      launchSchedulingMightNeedCancelling();
      return;
    }
    if (clip.getArmState() != ArmState.OFF && !doInstant && !isActiveNow) {
      clip.setArmState(ArmState.OFF);
      launchSchedulingMightNeedCancelling();
      return;
    }

    if (!isActiveNow) {
      if (!playing) {
        // C session.cpp:1049-1056
        ClipModel old = track.getActiveClip();
        if (old != null && old != clip) {
          old.setActiveInSession(false);
        }
        clip.setActiveInSession(true);
        int idx = track.getClips().indexOf(clip);
        if (idx >= 0) {
          track.setActiveClipIndex(idx);
          syncBridgeCurrentClip(track, idx);
        }
        syncClipActiveToSound(track);
        return;
      }

      // C session.cpp:1420-1477 — quantized launch synced to longest active multiple/factor clip
      ClipModel waitForClip =
          currentProject.getLongestActiveClipWithMultipleOrFactorLength(
              clip.getLoopLength(), true, null);
      if (waitForClip == null) {
        if (hasLaunchEvent()) {
          // LAUNCH_ALONG_WITH_EXISTING_LAUNCHING (session.cpp:1467-1468, 1364)
          disarmOtherClipsOnTrack(track, clip);
          if ("fill".equals(clip.getLaunchStyle())) {
            scheduleFillClip(track, clip);
          } else {
            clip.setArmState(ArmState.ON_NORMAL);
          }
        } else {
          // NOTHING_TO_SYNC_TO (session.cpp:1462-1464)
          if (!"fill".equals(clip.getLaunchStyle())) {
            ClipModel old = track.getActiveClip();
            if (old != null && old != clip && old.isActiveInSession()) {
              old.stopAllNotesPlaying(true);
              old.setActiveInSession(false);
            }
            clip.setActiveInSession(true);
            int idx = track.getClips().indexOf(clip);
            if (idx >= 0) {
              track.setActiveClipIndex(idx);
              syncBridgeCurrentClip(track, idx);
            }
            clip.setPos(0);
            if ("once".equals(clip.getLaunchStyle())) {
              clip.setArmState(ArmState.ON_NORMAL);
              int len = clip.getLoopLength();
              if (len > 0) {
                scheduleLaunchTiming(lastSwungTickActioned + len, 1, len);
              }
            }
            syncClipActiveToSound(track);
          }
        }
        return;
      }

      // C session.cpp:1424-1429 — FILL clips never subdivide waitForClip's loop
      long longestStarting =
          "fill".equals(clip.getLaunchStyle()) ? waitForClip.getLoopLength() : clip.getLoopLength();
      long quantization =
          LaunchQuantizer.syncedQuantization(waitForClip.getLoopLength(), longestStarting, true);
      // C investigateSyncedLaunch (session.cpp:1561-1562) →
      // getActualCurrentPosAsIfPlayingInForwardDirection (clip.cpp:167-179), then pos %=
      // quantization
      // (armClipsToStartOrSoloWithQuantization, session.cpp:1631).
      long forwardPos =
          waitForClip.currentlyPlayingReversed
              ? waitForClip.getLoopLength() - waitForClip.lastProcessedPos
              : waitForClip.lastProcessedPos;
      long pos = Math.floorMod(forwardPos, quantization);

      // C session.cpp:1633-1647 — shift forces a late start (DEFAULT clips only); otherwise a
      // launch point reached less than kAmountNoteOnLatenessAllowed ago also starts late.
      boolean doLateStart = doInstant;
      boolean isDefault = "default".equals(clip.getLaunchStyle());
      if (isDefault || !doLateStart) {
        if (!doLateStart) {
          double samplesPerTick = 60.0 * 44100.0 / (currentProject.getBpm() * 96.0);
          doLateStart = pos * samplesPerTick < 2205; // kAmountNoteOnLatenessAllowed
        }
        if (doLateStart) {
          lateStartClip(track, clip, (int) pos, isDefault);
        } else {
          // C armClipLowLevel (session.cpp:1891, 2002-2013) — disarm other inactive clips on the
          // output; FILL clips get armed by scheduleFillClip below.
          disarmOtherClipsOnTrack(track, clip);
          if (!"fill".equals(clip.getLaunchStyle())) {
            clip.setArmState(ArmState.ON_NORMAL);
          }
        }
      }
      if (!doLateStart) {
        scheduleLaunchTiming(lastSwungTickActioned + (quantization - pos), 1, quantization);
      }
      scheduleFillClip(track, clip); // C session.cpp:1476 (no-op unless FILL)
      return;
    }

    // Clip is currently playing (session.cpp:1077-1150)
    if (!playing) {
      clip.setActiveInSession(false);
      syncClipActiveToSound(track);
      return;
    }
    if (doInstant || "fill".equals(clip.getLaunchStyle())) {
      // C session.cpp:1095 — instant-stop (always instant for a playing FILL clip)
      clip.setArmState(ArmState.OFF);
      clip.setFillEventAtTickCount(0);
      clip.stopAllNotesPlaying(true);
      clip.setActiveInSession(false);
      syncClipActiveToSound(track);
      launchSchedulingMightNeedCancelling();
      return;
    }

    // Normal quantized stop (session.cpp:1136)
    if (clip.getArmState() != ArmState.OFF) {
      clip.setArmState(ArmState.OFF);
      launchSchedulingMightNeedCancelling();
      return;
    }
    clip.setArmState(ArmState.ON_NORMAL);
    ClipModel waitForClip =
        currentProject.getLongestActiveClipWithMultipleOrFactorLength(
            clip.getLoopLength(), true, null);
    if (waitForClip == null) {
      waitForClip = clip;
    }
    long len = waitForClip.getLoopLength();
    if (len > 0) {
      long pos = Math.floorMod(waitForClip.lastProcessedPos, len);
      scheduleLaunchTiming(lastSwungTickActioned + (len - pos), 1, len);
    }
  }

  /**
   * C Session::armSection / Session::armClipsToStartOrSoloWithQuantization (session.cpp:1244-1819).
   * Normal section launches skip {@code FILL} clips (session.cpp:1280, 1370, 1395, 1444, 1668,
   * 1691), preserve stop-arming on already-active {@code ONCE} clips (session.cpp:1737), and then
   * call {@link #scheduleFillClips(int)} (session.cpp:1479).
   */
  public synchronized void armSection(int section, boolean stopAllOtherClips) {
    if (currentProject == null) return;

    if (!playing) {
      // C session.cpp:1276-1293 — armSectionWhenNeitherClockActive
      for (TrackModel track : currentProject.getTracks()) {
        for (int c = 0; c < track.getClips().size(); c++) {
          ClipModel clip = track.getClips().get(c);
          if (clip.getSection() == section
              && !"fill".equals(clip.getLaunchStyle())
              && !clip.isActiveInSession()) {
            ClipModel old = track.getActiveClip();
            if (old != null && old != clip) {
              old.setActiveInSession(false);
            }
            clip.setActiveInSession(true);
            track.setActiveClipIndex(c);
            syncBridgeCurrentClip(track, c);
          } else if (stopAllOtherClips
              && clip.getSection() != section
              && clip.isActiveInSession()) {
            clip.setActiveInSession(false);
          }
        }
      }
      syncAllClipsActiveToSound();
      return;
    }

    ClipModel waitForClip = currentProject.getLongestClip(false);
    long longestStartingClipLength = 0;
    for (TrackModel track : currentProject.getTracks()) {
      for (ClipModel clip : track.getClips()) {
        if (clip.getSection() == section
            && !"fill".equals(clip.getLaunchStyle())
            && clip.getLoopLength() > longestStartingClipLength) {
          longestStartingClipLength = clip.getLoopLength(); // C session.cpp:1444
        }
      }
    }
    if (longestStartingClipLength == 0 && waitForClip != null) {
      longestStartingClipLength = waitForClip.getLoopLength();
    }

    if (waitForClip == null) {
      if (hasLaunchEvent()) {
        // C session.cpp:1368-1378 — armClipsAlongWithExistingLaunching
        for (TrackModel track : currentProject.getTracks()) {
          for (ClipModel clip : track.getClips()) {
            if (clip.getSection() == section && !"fill".equals(clip.getLaunchStyle())) {
              if (!clip.isActiveInSession()) {
                clip.setArmState(ArmState.ON_NORMAL);
              }
            }
          }
        }
        scheduleFillClips(section);
      } else {
        // C session.cpp:1393-1400 — armClipsWithNothingToSyncTo
        long distanceTilLaunch = 0;
        for (TrackModel track : currentProject.getTracks()) {
          for (int c = 0; c < track.getClips().size(); c++) {
            ClipModel clip = track.getClips().get(c);
            if (clip.getSection() == section && !"fill".equals(clip.getLaunchStyle())) {
              clip.setActiveInSession(true);
              track.setActiveClipIndex(c);
              syncBridgeCurrentClip(track, c);
              clip.setPos(0);
              if ("once".equals(clip.getLaunchStyle())) {
                clip.setArmState(ArmState.ON_NORMAL);
                distanceTilLaunch = Math.max(distanceTilLaunch, clip.getLoopLength());
              }
            }
          }
        }
        if (distanceTilLaunch > 0) {
          scheduleLaunchTiming(lastSwungTickActioned + distanceTilLaunch, 1, distanceTilLaunch);
        }
        syncAllClipsActiveToSound();
      }
      return;
    }

    // C session.cpp:1653-1818 — LAUNCH_USING_QUANTIZATION for a section
    Set<TrackModel> outputsTakenBySectionLaunch = new HashSet<>();
    for (TrackModel track : currentProject.getTracks()) {
      for (ClipModel sectionClip : track.getClips()) {
        if (sectionClip.getSection() != section) continue;
        if ("fill".equals(sectionClip.getLaunchStyle())) continue; // C session.cpp:1668
        boolean isActive = track.getActiveClip() == sectionClip && sectionClip.isActiveInSession();
        if (isActive) continue;
        outputsTakenBySectionLaunch.add(track);
      }
    }

    Set<TrackModel> outputsWeHavePickedAClipFor = new HashSet<>();
    for (TrackModel track : currentProject.getTracks()) {
      for (ClipModel thisClip : track.getClips()) {
        if ("fill".equals(thisClip.getLaunchStyle())) continue; // C session.cpp:1691
        boolean isActive = track.getActiveClip() == thisClip && thisClip.isActiveInSession();

        if (thisClip.getSection() == section) {
          if (!outputsWeHavePickedAClipFor.contains(track)) {
            outputsWeHavePickedAClipFor.add(track);
            if (isActive) {
              // C session.cpp:1737 — do not cancel stop-arming on an active ONCE clip
              if (thisClip.getArmState() != ArmState.OFF
                  && !"once".equals(thisClip.getLaunchStyle())) {
                thisClip.setArmState(ArmState.OFF);
              }
            } else {
              thisClip.setArmState(ArmState.ON_NORMAL);
            }
          } else {
            if (isActive && !outputsTakenBySectionLaunch.contains(track)) {
              thisClip.setArmState(ArmState.ON_NORMAL);
            } else if (!isActive) {
              thisClip.setArmState(ArmState.OFF);
            }
          }
        } else if (stopAllOtherClips) {
          if (isActive) {
            if (!outputsTakenBySectionLaunch.contains(track)) {
              thisClip.setArmState(ArmState.ON_NORMAL);
            }
          } else if (thisClip.getArmState() != ArmState.OFF) {
            thisClip.setArmState(ArmState.OFF);
          }
        }
      }
    }

    if (!stopAllOtherClips) {
      for (TrackModel track : currentProject.getTracks()) {
        if (!outputsWeHavePickedAClipFor.contains(track)) continue;
        for (ClipModel thisClip : track.getClips()) {
          if (thisClip.getSection() != section) {
            boolean isActive = track.getActiveClip() == thisClip && thisClip.isActiveInSession();
            if (isActive && !outputsTakenBySectionLaunch.contains(track)) {
              thisClip.setArmState(ArmState.ON_NORMAL);
            }
          }
        }
      }
    }

    long quantization =
        LaunchQuantizer.syncedQuantization(
            waitForClip.getLoopLength(), longestStartingClipLength, false);
    long pos = Math.floorMod(waitForClip.lastProcessedPos, quantization);
    long ticksTilStart = quantization - pos;
    scheduleLaunchTiming(lastSwungTickActioned + ticksTilStart, 1, quantization);

    // C session.cpp:1479 — schedule any FILL clips in this section
    scheduleFillClips(section);
  }

  /**
   * C Session::doLaunch (session.cpp:283-727). Executes a regular launch ({@code isFillLaunch ==
   * false}) or a fill launch ({@code isFillLaunch == true}).
   */
  public synchronized void doLaunch(boolean isFillLaunch) {
    if (currentProject == null) return;

    // Pass 1 (C session.cpp:304-374): see which tracks have an inactive armed clip launching.
    Set<TrackModel> outputsLaunchedFor = new HashSet<>();
    for (TrackModel track : currentProject.getTracks()) {
      for (ClipModel clip : track.getClips()) {
        if (isFillLaunch
            && (clip.getFillEventAtTickCount() <= 0
                || lastSwungTickActioned < clip.getFillEventAtTickCount())) {
          continue; // C session.cpp:307-313
        }
        boolean clipActiveNow = track.getActiveClip() == clip && clip.isActiveInSession();
        if (clip.getArmState() != ArmState.OFF && !clipActiveNow) {
          ClipModel activeOnTrack = track.getActiveClip();
          if (isFillLaunch
              && "fill".equals(clip.getLaunchStyle())
              && activeOnTrack != null
              && activeOnTrack.isActiveInSession()
              && !"fill".equals(activeOnTrack.getLaunchStyle())) {
            // C session.cpp:325-329 — non-fill clip already active on this output, don't launch
            clip.setArmState(ArmState.OFF);
            continue;
          }
          outputsLaunchedFor.add(track);
        }
      }
    }

    // Pass 2 (C session.cpp:390-541): stop or update currently playing clips before starting new.
    Map<ClipModel, Boolean> wasActiveBefore = new IdentityHashMap<>();
    for (TrackModel track : currentProject.getTracks()) {
      for (ClipModel clip : track.getClips()) {
        boolean activeBefore = track.getActiveClip() == clip && clip.isActiveInSession();
        wasActiveBefore.put(clip, activeBefore);
        if (!activeBefore) continue;

        if (isFillLaunch && !"fill".equals(clip.getLaunchStyle())) {
          continue; // C session.cpp:402-405 — fill launch leaves non-fill clips unaffected
        }

        if ((clip.getArmState() == ArmState.ON_NORMAL || clip.getArmState() == ArmState.ON_TO_SOLO)
            && !isFillLaunch) {
          // C session.cpp:439-495 — armed to stop
          clip.setArmState(ArmState.OFF);
          clip.setActiveInSession(false);
          clip.stopAllNotesPlaying(true);
        } else if (outputsLaunchedFor.contains(track)) {
          // C session.cpp:514-536 — another clip is taking this output
          if ("fill".equals(clip.getLaunchStyle())) {
            // C session.cpp:516-520 — disarm fill clip so it doesn't restart at launchEvent
            clip.setArmState(ArmState.OFF);
          }
          clip.setActiveInSession(false);
          clip.stopAllNotesPlaying(true);
        }
      }
    }

    // Pass 3 (C session.cpp:551-665): launch clips that were not active before.
    long distanceTilLaunchEvent = 0;
    Set<TrackModel> alreadyGotItsNewClip = new HashSet<>();
    for (int t = 0; t < currentProject.getTracks().size(); t++) {
      TrackModel track = currentProject.getTracks().get(t);
      for (int c = 0; c < track.getClips().size(); c++) {
        ClipModel clip = track.getClips().get(c);
        if (isFillLaunch
            && (clip.getFillEventAtTickCount() <= 0
                || lastSwungTickActioned < clip.getFillEventAtTickCount())) {
          continue; // C session.cpp:554-560
        }
        clip.setFillEventAtTickCount(0); // C session.cpp:564

        if (!Boolean.TRUE.equals(wasActiveBefore.get(clip))) {
          if (clip.getArmState() != ArmState.OFF) {
            clip.setArmState(ArmState.OFF); // C session.cpp:585
            if (!alreadyGotItsNewClip.contains(track)) {
              alreadyGotItsNewClip.add(track);
              clip.setActiveInSession(true);
              clip.setPos(0); // C session.cpp:613
              if (isFillLaunch && "fill".equals(clip.getLaunchStyle())) {
                // C session.cpp:624-626 — re-arm fill clip so it stops at launchEvent
                clip.setArmState(ArmState.ON_NORMAL);
              }
              track.setActiveClipIndex(c); // C session.cpp:630
              syncBridgeCurrentClip(t, c);
            }
          }
        }

        // C session.cpp:646-651 — arm ONCE clip so it stops after one loop
        boolean activeNow = track.getActiveClip() == clip && clip.isActiveInSession();
        if (!isFillLaunch
            && activeNow
            && "once".equals(clip.getLaunchStyle())
            && clip.getArmState() == ArmState.OFF) {
          clip.setArmState(ArmState.ON_NORMAL);
          distanceTilLaunchEvent = Math.max(distanceTilLaunchEvent, clip.getLoopLength());
        }
      }
    }

    // C session.cpp:720-722 — schedule the auto-stop launch event for ONCE clips
    if (!isFillLaunch && distanceTilLaunchEvent > 0) {
      scheduleLaunchTiming(
          lastSwungTickActioned + distanceTilLaunchEvent, 1, distanceTilLaunchEvent);
    }
    syncAllClipsActiveToSound();
  }

  /**
   * C Song::renderAudio (song.cpp:2509-2514): shouldLimitDelayFeedback = !isClipActiveNow, where
   * isClipActiveNow = output->getActiveClip() &amp;&amp; isClipActive(...). Syncs this flag onto
   * each track's GlobalEffectable whenever session clip activeness changes.
   */
  private void syncClipActiveToSound(TrackModel track) {
    if (track == null) return;
    ClipModel active = track.getActiveClip();
    boolean isClipActiveNow = active != null && active.isActiveInSession();
    for (ClipModel clip : track.getClips()) {
      if (clip.getSound() instanceof org.deluge.firmware2.GlobalEffectable ge) {
        ge.shouldLimitDelayFeedback = !isClipActiveNow;
      }
    }
  }

  private void syncAllClipsActiveToSound() {
    if (currentProject == null) return;
    for (TrackModel track : currentProject.getTracks()) {
      syncClipActiveToSound(track);
    }
  }

  /**
   * Whether the clip advances and is processed this tick: the track's active clip, active in the
   * session (C {@code Song::isClipActive}). Track mute here is the UI's silence switch (bridge
   * mutes / {@code FirmwareSound.muted}), not C clip deactivation: a muted clip keeps its position
   * so it is still in sync when un-muted.
   */
  private static boolean ticksInSession(TrackModel track, ClipModel clip) {
    return track.getActiveClip() == clip && clip.isActiveInSession();
  }

  /**
   * Faithful port of {@code Session::willClipContinuePlayingAtEnd} (session.cpp:2730-2762). Returns
   * false if the clip is inactive or armed to stop at a launch event that lands at or before its
   * next loop point; returns true otherwise. Feeds {@link org.deluge.model.Iterance#LAST} during
   * {@code InstrumentClip::processCurrentPos} (instrument_clip.cpp:719).
   */
  public boolean willClipContinuePlayingAtEnd(TrackModel track, ClipModel clip) {
    if (track == null || clip == null || !ticksInSession(track, clip)) {
      return false; // C session.cpp:2734-2735
    }
    // C session.cpp:2742-2750 (per-clip soloingInSessionMode is not modelled on ClipModel;
    // session soloing is track-level via SwingGridPanel.soloedTracks).
    boolean willLoop =
        launchEventAtSwungTickCount <= 0
            || numRepeatsTilLaunch > 1
            || clip.getArmState() != ArmState.ON_NORMAL;
    if (willLoop) {
      return true;
    }
    // C session.cpp:2758-2761
    int ticksTilReachLoopPoint =
        clip.currentlyPlayingReversed
            ? clip.lastProcessedPos
            : (clip.getLoopLength() - clip.lastProcessedPos);
    return (launchEventAtSwungTickCount - lastSwungTickActioned > ticksTilReachLoopPoint);
  }

  /**
   * C Session::considerLaunchEvent (session.cpp:2292-2426): increments active clips before any
   * launch event so newly launched clips stay at position 0, then fires fill launches and regular
   * launch events.
   */
  private void considerLaunchEvent(int numTicksBeingIncremented) {
    if (currentProject == null) return;

    ClipModel nextClipWithFillEvent = null;
    for (TrackModel track : currentProject.getTracks()) {
      for (ClipModel clip : track.getClips()) {
        if (clip.getFillEventAtTickCount() > 0) {
          if (nextClipWithFillEvent == null
              || nextClipWithFillEvent.getFillEventAtTickCount() > clip.getFillEventAtTickCount()) {
            nextClipWithFillEvent = clip;
          }
        }
        boolean active = ticksInSession(track, clip);
        if (active) {
          // C session.cpp:2325 — increment before doLaunch so newly launched clips stay at pos 0
          clip.incrementPos(numTicksBeingIncremented);
        }
      }
    }

    if (nextClipWithFillEvent != null
        && lastSwungTickActioned >= nextClipWithFillEvent.getFillEventAtTickCount()) {
      doLaunch(true); // C session.cpp:2335
      nextClipWithFillEvent.setFillEventAtTickCount(0);
    }

    if (launchEventAtSwungTickCount > 0 && lastSwungTickActioned >= launchEventAtSwungTickCount) {
      numRepeatsTilLaunch--; // C session.cpp:2342
      if (numRepeatsTilLaunch <= 0) {
        clearLaunchEvent();
        doLaunch(false); // C session.cpp:2383
      } else {
        launchEventAtSwungTickCount =
            lastSwungTickActioned + currentArmedLaunchLengthForOneRepeat; // C session.cpp:2396
      }
    }
  }

  /**
   * C Session::armClipLowLevel (session.cpp:2002-2013): unarms other <em>inactive</em> clips on the
   * same output, preserving {@code armState} on any active clip (such as a playing {@code ONCE} or
   * {@code FILL} clip armed to stop).
   */
  private void disarmOtherClipsOnTrack(TrackModel track, ClipModel keepClip) {
    ClipModel active = track.getActiveClip();
    for (ClipModel other : track.getClips()) {
      boolean isActive = other == active && other.isActiveInSession();
      if (other != keepClip && !isActive && other.getArmState() != ArmState.OFF) {
        other.setArmState(ArmState.OFF);
        other.setFillEventAtTickCount(0);
      }
    }
  }

  private void syncBridgeCurrentClip(TrackModel track, int clipIdx) {
    if (currentProject == null) return;
    int t = currentProject.getTracks().indexOf(track);
    if (t >= 0) {
      syncBridgeCurrentClip(t, clipIdx);
    }
  }

  private void syncBridgeCurrentClip(int trackIdx, int clipIdx) {
    BridgeContract bridge = BridgeHolder.getBridge();
    if (bridge != null && trackIdx >= 0 && trackIdx < BridgeContract.TRACKS) {
      bridge.setCurrentClip(trackIdx, clipIdx);
    }
  }

  public synchronized void setProject(ProjectModel project) {
    this.currentProject = project;
    this.runEpoch++;
    this.lastSeededAutomationGeneration = -1L;
    syncAllClipsActiveToSound();
    if (playing && project != null) {
      swungTicksTilNextEvent = Integer.MAX_VALUE;
      ensureSongAutomationSeeded();
      swungTicksTilNextEvent = 1;
    }
  }

  public synchronized ProjectModel getProject() {
    return currentProject;
  }

  public synchronized boolean isPlaying() {
    return playing;
  }

  public synchronized void start() {
    playing = true;
    runEpoch++;
    lastSeededAutomationGeneration = -1L;
    lastSwungTickActioned = 0;
    swungTicksTilNextEvent = Integer.MAX_VALUE;
    totalInputTicksTimes50 = 0L;
    launchEventAtSwungTickCount = 0;
    numRepeatsTilLaunch = 0;
    currentArmedLaunchLengthForOneRepeat = 0;
    lastClockTick = 0;
    if (midiClockOut != null) midiClockOut.start(); // 0xFA — external gear starts from the top
    FirmwareDisplay.get().setText(" PLAYING ");
    if (currentProject != null) {
      syncAllClipsActiveToSound();
      for (ClipModel clip : currentProject.getClips()) {
        if (!clip.isActiveInSession()) continue;
        // C InstrumentClip::setPos(…, 0): also resets rows with independent play positions,
        // whose repeat count drives iterance (model_stack.cpp:100-107).
        clip.setPos(0);
      }
      // C arrangement.cpp:120-129, 359-365: seed song-level <songParams> / UNPATCHED_TEMPO at tick
      // 0
      // and include sm.ticksTilNextEvent in the initial swungTicksTilNextEvent deadline.
      ensureSongAutomationSeeded();

      // C: playback starts with swungTicksTilNextEvent = 0, so the first actioned tick has a zero
      // increment and every clip runs processCurrentPos(…, 0) before any position moves
      // (playback_handler.cpp:533 in setupPlayback, then Session::doTickForward →
      // session.cpp:2502).
      // That is what takes a reversed clip's repeatCount from setPos's -1 to 0 (clip.cpp:259-260).
      // It also sounds the notes at pos 0 (note_row.cpp:2373).
      setArpClockActive(true);
      int next = currentProject.getSwungTicksTilNextEvent();
      for (TrackModel track : currentProject.getTracks()) {
        for (ClipModel clip : track.getClips()) {
          if (!ticksInSession(track, clip)) continue;
          clip.processCurrentPos(0, track, this);
          next = Math.min(next, clip.getTicksTilNextEvent());
        }
      }
      next = Math.min(next, doTickForwardForArps());
      swungTicksTilNextEvent = Math.max(1, next);
    } else {
      swungTicksTilNextEvent = 0;
    }
    arrangement.resetPlayPos(0);
  }

  public synchronized void stop() {
    playing = false;
    if (currentProject != null) {
      // C Clip::expectNoFurtherTicks -> InstrumentClip::stopAllNotesPlaying
      // (instrument_clip.cpp:1147-1160, 1198-1205): every sounding sequenced note gets its
      // note-off, and cancelAllArming (session.cpp:1991-1995) clears pending arm/fill state.
      for (TrackModel track : currentProject.getTracks()) {
        for (ClipModel clip : track.getClips()) {
          clip.stopAllNotesPlaying(true);
          clip.setArmState(ArmState.OFF);
          clip.setFillEventAtTickCount(0);
        }
      }
    }
    setArpClockActive(false);
    totalInputTicksTimes50 = 0L;
    launchEventAtSwungTickCount = 0;
    numRepeatsTilLaunch = 0;
    currentArmedLaunchLengthForOneRepeat = 0;
    if (midiClockOut != null) midiClockOut.stop(); // 0xFC
    FirmwareDisplay.get().setText(" STOPPED ");
  }

  /** Advance the sequencer by a number of ticks. Includes high-fidelity Swing logic. */
  public synchronized void advanceTicks(int numTicks) {
    if (org.deluge.engine.FirmwareAudioEngine.debugTelemetry && playing && Math.random() < 0.05) {}
    if (!playing || currentProject == null || numTicks <= 0) return;

    ensureSongAutomationSeeded();
    long limitTimes50 = totalInputTicksTimes50 + 50L * (long) numTicks;
    while (true) {
      int nextDeadline =
          Math.min(swungTicksTilNextEvent, currentProject.getSwungTicksTilNextEvent());
      if (launchEventAtSwungTickCount > 0) {
        long ticksTilLaunch = launchEventAtSwungTickCount - lastSwungTickActioned;
        if (ticksTilLaunch > 0 && ticksTilLaunch < nextDeadline) {
          nextDeadline = (int) ticksTilLaunch;
        }
      }
      for (TrackModel track : currentProject.getTracks()) {
        for (ClipModel clip : track.getClips()) {
          if (clip.getFillEventAtTickCount() > 0) {
            long ticksTilFill = clip.getFillEventAtTickCount() - lastSwungTickActioned;
            if (ticksTilFill > 0 && ticksTilFill < nextDeadline) {
              nextDeadline = (int) ticksTilFill;
            }
          }
          boolean active = ticksInSession(track, clip);
          if (active) {
            nextDeadline = Math.min(nextDeadline, clip.getTicksTilNextEvent());
          }
        }
      }
      int deadlineTicks = Math.max(1, nextDeadline);

      // ── Stateless Closed-Form Swing Math (C playback_handler.cpp:1761-1790) ──
      // Anchors every swung tick directly to internalTickPositionTimes50(swungTick, swingAmount,
      // swingInterval) so complete pairs never drift even across live swingAmount/swingInterval
      // edits.
      int swingAmount = Math.max(-49, Math.min(49, currentProject.getSwingAmount()));
      int swingInterval = currentProject.getSwingInterval();
      int lo = 0;
      int hi = deadlineTicks;
      while (lo < hi) {
        int mid = (lo + hi + 1) >>> 1;
        if (internalTickPositionTimes50(
                (long) lastSwungTickActioned + (long) mid, swingAmount, swingInterval)
            <= limitTimes50) {
          lo = mid;
        } else {
          hi = mid - 1;
        }
      }
      int effectiveAdvance = lo;
      if (effectiveAdvance <= 0) {
        break;
      }

      lastSwungTickActioned += effectiveAdvance;
      arrangement.advance(effectiveAdvance);

      // C playback_handler.cpp:1036 — increment active clips and fire fill/regular launch events
      considerLaunchEvent(effectiveAdvance);

      // C arrangement.cpp:120-129, 359-365 — advance song-level <songParams> / UNPATCHED_TEMPO
      // nodes
      // with mayInterpolate = true so sample-domain tickSamples() owns ramp slopes at 1.0x rate.
      org.deluge.modulation.params.ParamManager sm = currentProject.getSongParamManager();
      int songNext = Integer.MAX_VALUE;
      boolean songAutomated = false;
      synchronized (sm) {
        if (sm.mightContainAutomation()) {
          sm.processCurrentPos(
              effectiveAdvance, lastSwungTickActioned, Integer.MAX_VALUE, false, false, true);
          songNext = sm.ticksTilNextEvent;
          songAutomated = true;
        }
      }
      if (songAutomated) {
        currentProject.applyAutomatedSongParams();
      }
      currentProject.setSwungTicksTilNextEvent(songNext);

      // C Session::doTickForward (session.cpp:2428-2535)
      int nextEventTicks = songNext;
      if (launchEventAtSwungTickCount > 0) {
        long ticksTilLaunch = launchEventAtSwungTickCount - lastSwungTickActioned;
        if (ticksTilLaunch > 0 && ticksTilLaunch < nextEventTicks) {
          nextEventTicks = (int) ticksTilLaunch;
        }
      }
      for (TrackModel track : currentProject.getTracks()) {
        for (ClipModel clip : track.getClips()) {
          if (clip.getFillEventAtTickCount() > 0) {
            long ticksTilFill = clip.getFillEventAtTickCount() - lastSwungTickActioned;
            if (ticksTilFill > 0 && ticksTilFill < nextEventTicks) {
              nextEventTicks = (int) ticksTilFill;
            }
          }
          boolean active = ticksInSession(track, clip);
          if (active) {
            clip.processCurrentPos(effectiveAdvance, track, this);
            nextEventTicks = Math.min(nextEventTicks, clip.getTicksTilNextEvent());
          }
        }
      }
      nextEventTicks = Math.min(nextEventTicks, doTickForwardForArps());

      // Update next event distance (C playback_handler.cpp:1095-1097 clamps >= 1).
      swungTicksTilNextEvent = Math.max(1, nextEventTicks);
    }
    totalInputTicksTimes50 = limitTimes50;

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
   * C Session::doTickForward (session.cpp:2523-2535) + Kit::doTickForwardForArp
   * (kit.cpp:1136-1240): ticks every track output once per event using {@code
   * activeClip.lastProcessedPos} when session-active or {@code lastSwungTickActioned} when the
   * output's clip is stopped (so live held pad/MIDI notes still arpeggiate in sync).
   */
  private int doTickForwardForArps() {
    int next = Integer.MAX_VALUE;
    if (currentProject == null) return next;
    for (TrackModel track : currentProject.getTracks()) {
      ClipModel clip = track.getActiveClip();
      if (clip == null || clip.getSound() == null) continue;
      boolean clipIsActive = clip.isActiveInSession();
      int posForArp = clipIsActive ? clip.lastProcessedPos : lastSwungTickActioned;
      boolean clipRev = clipIsActive && clip.currentlyPlayingReversed;
      if (clip.getSound() instanceof org.deluge.engine.FirmwareSound fs) {
        int t = fs.fw2Sound.doTickForwardForArp(posForArp, clipRev);
        next = Math.min(next, t);
      }
      if (clip.getSound() instanceof org.deluge.engine.FirmwareKit kit) {
        // C Kit::doTickForwardForArp (kit.cpp:1136-1217): the kit arp ticks first...
        next = Math.min(next, kit.doTickForwardForKitArp(posForArp, clipRev));
        // ...then (kit.cpp:1221-1240) each drum's own arpeggiator ticks at its row's own
        // position and direction when clipIsActive, or posForArp forward otherwise.
        for (int drumIdx = 0; drumIdx < kit.drumSounds.size(); drumIdx++) {
          org.deluge.model.NoteRowModel rowModel = null;
          for (org.deluge.model.NoteRowModel r : clip.getNoteRowsMap().values()) {
            if (r.getPitch() == drumIdx) {
              rowModel = r;
              break;
            }
          }
          boolean shouldUseIndependentPlayPos =
              clipIsActive && rowModel != null && rowModel.hasIndependentPlayPos();
          int rowPos =
              shouldUseIndependentPlayPos ? rowModel.getLastProcessedPosIfIndependent() : posForArp;
          boolean rowRev = clipIsActive && clip.isRowCurrentlyPlayingReversed(rowModel);
          int t = kit.drumSounds.get(drumIdx).fw2Sound.doTickForwardForArp(rowPos, rowRev);
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
        kit.arpeggiator.playbackClockActive = active;
        for (org.deluge.engine.FirmwareSound drum : kit.drumSounds) {
          drum.fw2Sound.arpeggiator.playbackClockActive = active;
        }
      }
    }
  }
}
