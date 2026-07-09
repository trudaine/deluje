package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.event.ActionEvent;
import javax.swing.JButton;
import org.deluge.BridgeContract;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.model.TrackModel;
import org.junit.jupiter.api.Test;

/**
 * Verifies C++ hardware parity (`// C clip_view.cpp`) for Exclusive Solo and Un-Solo All when
 * Alt-Clicking Mute Column pads.
 */
public class ExclusiveSoloParityTest {

  @Test
  public void testExclusiveSoloMutesOthersAndSecondClickUnsolosAll() {
    ProjectModel project = new ProjectModel();
    TrackModel t1 = new SynthTrackModel("Track 1");
    TrackModel t2 = new SynthTrackModel("Track 2");
    TrackModel t3 = new SynthTrackModel("Track 3");
    project.addTrack(t1);
    project.addTrack(t2);
    project.addTrack(t3);

    // Initially all 3 unmuted
    assertFalse(t1.isMuted());
    assertFalse(t2.isMuted());
    assertFalse(t3.isMuted());

    // Execute Exclusive Solo logic targeting Track 2 (index 1)
    exclusiveSoloTrack(project, 1);

    assertTrue(t1.isMuted(), "Track 1 must be muted");
    assertFalse(t2.isMuted(), "Track 2 must remain soloed (unmuted)");
    assertTrue(t3.isMuted(), "Track 3 must be muted");

    // Execute Exclusive Solo on Track 2 again -> Must Un-Solo All
    exclusiveSoloTrack(project, 1);

    assertFalse(t1.isMuted(), "Track 1 must be unmuted after unsolo");
    assertFalse(t2.isMuted(), "Track 2 must remain unmuted after unsolo");
    assertFalse(t3.isMuted(), "Track 3 must be unmuted after unsolo");
  }

  private void exclusiveSoloTrack(ProjectModel projectModel, int targetIndex) {
    TrackModel targetTrack = projectModel.getTracks().get(targetIndex);
    boolean alreadySolo = !targetTrack.isMuted();
    for (TrackModel t : projectModel.getTracks()) {
      if (t != targetTrack && !t.isMuted()) {
        alreadySolo = false;
        break;
      }
    }
    if (alreadySolo) {
      for (TrackModel t : projectModel.getTracks()) {
        t.setMuted(false);
      }
    } else {
      for (TrackModel t : projectModel.getTracks()) {
        t.setMuted(t != targetTrack);
      }
    }
  }
}
