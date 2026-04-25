package org.chuck.deluge.ui.swing2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Color;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.StepData;
import org.junit.jupiter.api.Test;

public class Swing2SyncTest {
  @Test
  public void testModelUpdatesUI() throws Exception {
    ClipModel clip = new ClipModel("CLIP 1", 8, 16);
    Swing2GridPanel panel = new Swing2GridPanel(null, null);

    panel.setClipModel(clip);

    // Verify initial color representation
    javax.swing.JButton[][] pads = getPrivatePads(panel);
    assertEquals(new Color(0x33, 0x33, 0x33), pads[0][0].getBackground());

    // Mutate Model
    clip.setStep(0, 0, new StepData(true, 0.8f, 0.5f, 1.0f, 60));

    // Verify visual state synchronization
    assertEquals(Color.CYAN, pads[0][0].getBackground());
  }

  @Test
  public void testGridRowCount() throws Exception {
    Swing2GridPanel panel = new Swing2GridPanel(null, null);
    panel.refresh();
    javax.swing.JButton[][] pads = getPrivatePads(panel);
    assertEquals(11, pads.length);
  }

  @Test
  public void testSoundTriggered() throws Exception {
    org.chuck.core.ChuckVM vm = new org.chuck.core.ChuckVM(44100, 2);
    org.chuck.deluge.BridgeContract bridge = new org.chuck.deluge.BridgeContract();
    bridge.register(vm);

    org.chuck.audio.ChuckAudio audio = new org.chuck.audio.ChuckAudio(vm, 1024, 2, 44100);
    vm.setAudio(audio);
    audio.start();

    org.chuck.deluge.engine.DelugeEngine engine =
        new org.chuck.deluge.engine.DelugeEngine(vm, bridge);
    vm.spork(engine::shred);

    // Set default note sequence
    vm.setGlobalFloat(org.chuck.deluge.BridgeContract.G_BPM, 120.0f);
    bridge.setStep(0, 0, true);
    bridge.setStep(0, 4, true);
    bridge.setMute(0, false);

    // Trigger Playback
    vm.setGlobalInt(org.chuck.deluge.BridgeContract.G_PLAY, 1L);

    // Sleep for 3 seconds to allow researcher to hear playback audibly
    Thread.sleep(3000);

    vm.setGlobalInt(org.chuck.deluge.BridgeContract.G_PLAY, 0L);
  }

  private javax.swing.JButton[][] getPrivatePads(Swing2GridPanel panel) throws Exception {
    java.lang.reflect.Field field = Swing2GridPanel.class.getDeclaredField("pads");
    field.setAccessible(true);
    return (javax.swing.JButton[][]) field.get(panel);
  }

  public static <T extends java.awt.Component> T findComponent(
      java.awt.Container container, Class<T> clazz) {
    for (java.awt.Component c : container.getComponents()) {

      if (clazz.isInstance(c)) {
        return clazz.cast(c);
      }
      if (c instanceof java.awt.Container) {
        T found = findComponent((java.awt.Container) c, clazz);
        if (found != null) return found;
      }
    }
    return null;
  }
}
