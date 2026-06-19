package org.deluge.ui;

import java.util.List;
import javax.swing.SwingUtilities;
import org.deluge.BridgeContract;
import org.deluge.model.ArrangerClip;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;

/**
 * High-performance real-time thread scheduler for the Arranger Timeline. 1. Playback: Overwrites
 * JNI bridge active step columns ahead of the playhead tick during Arranger Mode. 2. Live Capture:
 * Listens to real-time session grid launches and automatically logs Arranger placements.
 */
public class ArrangerPlaybackScheduler implements Runnable {
  private final BridgeContract bridge;

  private ProjectModel project;

  private volatile boolean running = true;
  private volatile boolean arrangerModeActive = false;
  private volatile boolean captureActive = false;

  private long lastStepActioned = -1;

  // Live Capture records state tracking arrays
  private final ClipModel[] activeCaptureClips = new ClipModel[BridgeContract.TRACKS];
  private final int[] activeCaptureStartSteps = new int[BridgeContract.TRACKS];
  private Runnable repaintCallback = null;

  public ArrangerPlaybackScheduler(final BridgeContract bridge, ProjectModel project) {
    this.bridge = bridge;

    this.project = project;

    // Start high-performance polling thread
    Thread thread = new Thread(this, "Arranger-Timeline-Scheduler");
    thread.setPriority(Thread.MAX_PRIORITY);
    thread.setDaemon(true);
    thread.start();
  }

  public void setProject(ProjectModel project) {
    this.project = project;
  }

  public boolean isArrangerModeActive() {
    return arrangerModeActive;
  }

  public void setArrangerModeActive(boolean active) {
    this.arrangerModeActive = active;
    if (active) {
      this.lastStepActioned = -1; // Reset to force immediate upcoming steps refresh!
    }
  }

  public boolean isCaptureActive() {
    return captureActive;
  }

  public void setCaptureActive(boolean active) {
    this.captureActive = active;
    if (!active) {
      finalizeAllActiveCaptures();
    }
  }

  public void setRepaintCallback(Runnable callback) {
    this.repaintCallback = callback;
  }

  public void shutdown() {
    this.running = false;
  }

  @Override
  public void run() {
    while (running) {
      try {
        long play = bridge.getGlobalInt(BridgeContract.G_PLAY);
        if (play != 0) {
          long currentStep = bridge.getGlobalInt(BridgeContract.G_CURRENT_STEP);
          if (currentStep != lastStepActioned) {
            lastStepActioned = currentStep;
            if (arrangerModeActive) {
              updateBridgeUpcomingStep((int) (currentStep + 1));
            }
          }
        } else {
          if (lastStepActioned != -1) {
            lastStepActioned = -1;
            finalizeAllActiveCaptures();
          }
        }
        Thread.sleep(5); // Ultra-fast sub-millisecond polling clock!
      } catch (InterruptedException e) {
        break;
      }
    }
  }

  /**
   * Transfers the upcoming arranger note cell step properties into the circular JNI step matrix
   * ahead of playhead.
   */
  private void updateBridgeUpcomingStep(int upcomingStep) {
    if (project == null) return;

    int col = Math.floorMod(upcomingStep, 16);
    List<org.deluge.model.TrackModel> tracks = project.getTracks();

    for (int t = 0; t < tracks.size(); t++) {
      ArrangerClip activePlacement = findActiveArrangerClip(t, upcomingStep);

      if (activePlacement != null && activePlacement.clip() != null) {
        ClipModel clip = activePlacement.clip();
        int localStep =
            Math.floorMod(upcomingStep - (activePlacement.startTicks() / 24), clip.getStepCount());

        // Transfer step parameter states straight to the JNI circular index
        for (int r = 0; r < clip.getRowCount(); r++) {
          int engineRow = t * 8 + r; // Map visual track grid lanes offsets
          if (engineRow >= BridgeContract.TRACKS) continue;

          StepData sd = clip.getStep(r, localStep);
          if (sd != null) {
            bridge.setStep(engineRow, col, sd.active());
            bridge.setStepPitch(engineRow, col, sd.pitch());
            bridge.setStepVolume(engineRow, col, sd.velocity());
            bridge.setGate(engineRow, col, sd.gate());
          } else {
            bridge.setStep(engineRow, col, false);
          }
        }
      } else {
        // No arranger placement active -> clear/mute circular step slot to keep background audio
        // quiet
        for (int r = 0; r < 8; r++) {
          int engineRow = t * 8 + r;
          if (engineRow >= BridgeContract.TRACKS) continue;
          bridge.setStep(engineRow, col, false);
        }
      }
    }
  }

  /** Helper lookup: finds the ArrangerClip active at the absolute step time index. */
  private ArrangerClip findActiveArrangerClip(int trackIndex, int absoluteStep) {
    if (project == null) return null;
    int queryTicks = absoluteStep * 24;

    for (ArrangerClip placement : project.getArrangerTimeline()) {
      if (placement.trackIndex() == trackIndex) {
        if (queryTicks >= placement.startTicks()
            && queryTicks < placement.startTicks() + placement.durationTicks()) {
          return placement;
        }
      }
    }
    return null;
  }

  // ===================== LIVE CAPTURE HOOKS =====================

  public synchronized void notifyClipLaunched(int trackIndex, ClipModel clip) {
    if (!captureActive || project == null) return;

    long currentStep = bridge.getGlobalInt(BridgeContract.G_CURRENT_STEP);
    int step = (int) currentStep;

    // Finalize any clip already currently running on this track row first
    finalizeActiveCapture(trackIndex, step);

    // Open a new launch capture block record
    activeCaptureClips[trackIndex] = clip;
    activeCaptureStartSteps[trackIndex] = step;
  }

  public synchronized void notifyClipStopped(int trackIndex) {
    if (!captureActive || project == null) return;

    long currentStep = bridge.getGlobalInt(BridgeContract.G_CURRENT_STEP);
    int step = (int) currentStep;

    finalizeActiveCapture(trackIndex, step);
  }

  private synchronized void finalizeActiveCapture(int trackIndex, int endStep) {
    ClipModel clip = activeCaptureClips[trackIndex];
    if (clip == null) return;

    int startStep = activeCaptureStartSteps[trackIndex];
    int durationSteps = Math.max(1, endStep - startStep);

    int startTicks = startStep * 24;
    int durationTicks = durationSteps * 24;

    ArrangerClip newPlacement = new ArrangerClip(trackIndex, clip, startTicks, durationTicks);
    project.addArrangerClip(newPlacement);

    System.out.println(
        "[Capture] Logged Arranger Placement on track "
            + trackIndex
            + " at startBar="
            + newPlacement.getStartBar()
            + " len="
            + newPlacement.getDurationBars());

    // Reset capture fields state
    activeCaptureClips[trackIndex] = null;

    if (repaintCallback != null) {
      SwingUtilities.invokeLater(repaintCallback);
    }
  }

  private synchronized void finalizeAllActiveCaptures() {
    if (project == null) return;
    long currentStep = bridge.getGlobalInt(BridgeContract.G_CURRENT_STEP);
    int step = (int) currentStep;

    for (int t = 0; t < BridgeContract.TRACKS; t++) {
      finalizeActiveCapture(t, step);
    }
  }
}
