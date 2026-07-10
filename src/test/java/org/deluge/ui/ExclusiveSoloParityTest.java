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
 * Verifies Exclusive Solo and Un-Solo All when Alt-Clicking a Clip View mute-column pad
 * (ClipGridPanel, checked via {@code ActionEvent.ALT_MASK} on the mute button's own click handler).
 *
 * <p>Regression: the original version of this test never touched ClipGridPanel at all -- it drove a
 * private helper method that reimplemented the same algorithm independently inside the test itself.
 * That proved the duplicated algorithm was internally consistent, not that the real Alt-Click
 * handler in ClipGridPanel actually exists or works; a bug or a completely missing wiring in the
 * real code (as later confirmed for two now-removed fabricated features earlier in this session)
 * would have passed unnoticed. Now clicks the real mute-column JButton with an Alt-modified
 * ActionEvent, matching how a real user gesture reaches it.
 */
public class ExclusiveSoloParityTest {

  @Test
  public void testExclusiveSoloMutesOthersAndSecondClickUnsolosAll() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();
    SwingDelugeApp app = new SwingDelugeApp(bridge, null);
    try {
      ProjectModel project = app.getCurrentProject();
      project.getTracks().clear();
      TrackModel t1 = new SynthTrackModel("Track 1");
      TrackModel t2 = new SynthTrackModel("Track 2");
      TrackModel t3 = new SynthTrackModel("Track 3");
      project.addTrack(t1);
      project.addTrack(t2);
      project.addTrack(t3);
      app.propagateCurrentModel();
      app.getClipPanel().editedModelTrack = 1; // "Track 2" is the edited/soloed target
      app.getClipPanel().refresh();

      assertFalse(t1.isMuted());
      assertFalse(t2.isMuted());
      assertFalse(t3.isMuted());

      JButton muteBtn = findMuteButton(app.getClipPanel());
      assertNotNull(muteBtn, "must find the real Clip View mute-column button");
      assertTrue(
          muteBtn.getActionListeners().length > 0,
          "the mute button must have a real click handler wired up");

      altClick(muteBtn);
      assertTrue(t1.isMuted(), "Track 1 must be muted");
      assertFalse(t2.isMuted(), "Track 2 must remain soloed (unmuted)");
      assertTrue(t3.isMuted(), "Track 3 must be muted");

      altClick(muteBtn);
      assertFalse(t1.isMuted(), "Track 1 must be unmuted after unsolo");
      assertFalse(t2.isMuted(), "Track 2 must remain unmuted after unsolo");
      assertFalse(t3.isMuted(), "Track 3 must be unmuted after unsolo");
    } finally {
      app.dispose();
      bridge.shutdown();
    }
  }

  private JButton findMuteButton(SwingGridPanel panel) {
    JButton[][] pads = panel.getPads();
    int muteCol = panel.getColumnCount() - 2;
    return pads[0][muteCol];
  }

  private void altClick(JButton btn) {
    ActionEvent evt =
        new ActionEvent(btn, ActionEvent.ACTION_PERFORMED, "click", ActionEvent.ALT_MASK);
    for (java.awt.event.ActionListener l : btn.getActionListeners()) {
      l.actionPerformed(evt);
    }
  }
}
