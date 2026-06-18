package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.chuck.core.ChuckVM;
import org.deluge.BridgeContract;
import org.deluge.model.*;
import org.junit.jupiter.api.Test;

/**
 * Verifies the real-time Arranger Live Capture Suite, asserting that live clip launches and stops
 * cleanly register and capture ArrangerClip placements onto the ProjectModel timeline.
 */
public class ArrangerLiveCaptureTest {

  @Test
  public void testLiveCaptureWorkflow() throws Exception {
    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    ProjectModel project = new ProjectModel();

    // 1. Setup track and clip
    TrackModel track = new SynthTrackModel("SYNTH 1");
    ClipModel clip = new ClipModel("CLIP A", 8, 16);
    track.addClip(clip);
    project.addTrack(track);

    // 2. Setup Scheduler and enable Live Capture
    ArrangerPlaybackScheduler scheduler = new ArrangerPlaybackScheduler(vm, bridge, project);
    scheduler.setCaptureActive(true);
    assertTrue(scheduler.isCaptureActive(), "Capture must be active");

    // Set current playhead step to step 4
    vm.setGlobalInt(BridgeContract.G_CURRENT_STEP, 4L);

    // 3. Simulate Clip Launch
    scheduler.notifyClipLaunched(0, clip);

    // Advance playhead step to 12 (spans 8 steps => 8 * 24 = 192 ticks)
    vm.setGlobalInt(BridgeContract.G_CURRENT_STEP, 12L);

    // Verify no clips captured on project timeline yet (still active/unfinalized)
    assertTrue(
        project.getArrangerTimeline().isEmpty(), "No placements should be recorded before stop");

    // 4. Simulate Clip Stop
    scheduler.notifyClipStopped(0);

    // 5. Assert placements successfully recorded on timeline
    List<ArrangerClip> timeline = project.getArrangerTimeline();
    assertEquals(1, timeline.size(), "One ArrangerClip placement should be captured");

    ArrangerClip placement = timeline.get(0);
    assertEquals(0, placement.trackIndex());
    assertEquals(clip, placement.clip());
    assertEquals(4 * 24, placement.startTicks(), "Start ticks must match step 4 * 24");
    assertEquals(8 * 24, placement.durationTicks(), "Duration ticks must match 8 steps * 24");

    scheduler.shutdown();
  }
}
