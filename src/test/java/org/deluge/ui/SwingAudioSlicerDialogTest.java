package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import javax.swing.JSlider;
import org.deluge.BridgeContract;
import org.deluge.model.KitTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SoundDrum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class SwingAudioSlicerDialogTest {

  private <T extends Component> T findComponent(Container parent, Class<T> type) {
    for (Component child : parent.getComponents()) {
      if (type.isInstance(child)) {
        return type.cast(child);
      }
      if (child instanceof Container) {
        T res = findComponent((Container) child, type);
        if (res != null) return res;
      }
    }
    return null;
  }

  @Test
  @DisplayName("Verify volSlider in SwingAudioSlicerDialog mutates KitTrackModel SoundDrum volumes")
  public void testVolSliderMutatesModel() throws Exception {
    ProjectModel project = new ProjectModel();
    KitTrackModel kitTrack = new KitTrackModel("TestKit");
    SoundDrum drum1 = new SoundDrum("Kick");
    drum1.setVolume(0.8f);
    SoundDrum drum2 = new SoundDrum("Snare");
    drum2.setVolume(0.8f);
    kitTrack.addDrum(drum1);
    kitTrack.addDrum(drum2);
    project.getTracks().add(kitTrack);

    BridgeContract bridge = new BridgeContract();

    javax.swing.SwingUtilities.invokeAndWait(() -> {
      SwingAudioSlicerDialog dialog = new SwingAudioSlicerDialog(null, bridge, project);
      JSlider volSlider = findComponent(dialog, JSlider.class);
      assertNotNull(volSlider, "volSlider must be present in SwingAudioSlicerDialog");

      // Move slider to 50%
      volSlider.setValue(50);

      // Verify model mutation across all drum slots
      assertEquals(0.5f, drum1.getVolume(), 0.001f);
      assertEquals(0.5f, drum2.getVolume(), 0.001f);

      dialog.dispose();
    });
  }
}
