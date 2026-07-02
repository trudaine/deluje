package org.deluge;

import static org.junit.jupiter.api.Assertions.*;

import javax.swing.JButton;
import org.deluge.hid.FirmwareDisplay;
import org.deluge.model.ProjectModel;
import org.deluge.ui.DelugePadButton;
import org.deluge.ui.SwingDelugeApp;
import org.junit.jupiter.api.Test;

public class ClipViewUtilityColumnBootTest {

  @Test
  public void testBootUtilityColumnsAndOledDisplay() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();

    // Boot exact app workstation
    SwingDelugeApp app = new SwingDelugeApp(bridge, null);
    try {
      ProjectModel project = ProjectModel.createDefaultProject();
      app.loadProject(project);

      // Verify Column 17 (Mute)
      JButton muteBtn = app.getClipPanel().getPadButtons()[0][16];
      assertNotNull(muteBtn, "Column 17 button should not be null");
      assertEquals("MUTE", muteBtn.getText(), "Column 17 text should be MUTE");
      assertTrue(
          muteBtn.getToolTipText().startsWith("Clip View: Row 1 Mute"),
          "Column 17 tooltip should describe Clip View Mute action");

      if (muteBtn instanceof DelugePadButton pad) {
        assertFalse(
            pad.isDrawCenterCircle(), "Center circle must be strictly blocked on Column 17");
        assertEquals(1.0f, pad.getIntensity(), "Intensity must be 1.0f for active illumination");
      }

      // Verify Column 18 (Audition)
      JButton audBtnTop = app.getClipPanel().getPadButtons()[0][17];
      assertNotNull(audBtnTop, "Column 18 top button should not be null");
      assertEquals("C4", audBtnTop.getText(), "Column 18 top text should correctly render note C4");
      assertTrue(
          audBtnTop.getToolTipText().startsWith("Clip View: Audition"),
          "Column 18 tooltip should describe Clip View Audition preview");

      System.out.println(
          "TEST-DEBUG-BOOT: trackColorHex="
              + project.getTracks().get(0).getColourHex()
              + " trackCount="
              + project.getTracks().size()
              + " viewMode="
              + app.getClipPanel().getViewMode()
              + " audBtnTopText="
              + audBtnTop.getText()
              + " audBtnTopColor="
              + (audBtnTop instanceof DelugePadButton pad ? pad.getBaseColor() : "null"));

      if (audBtnTop instanceof DelugePadButton pad) {
        assertFalse(
            pad.isDrawCenterCircle(), "Center circle must be strictly blocked on Column 18");
        assertEquals(
            app.getClipPanel().getGridNoteColor(67),
            pad.getBaseColor(),
            "Row 1 Column 18 base color must match dynamic hue-shifted color of C4");
      }

      JButton audBtnBottom = app.getClipPanel().getPadButtons()[7][17];
      assertNotNull(audBtnBottom, "Column 18 bottom button should not be null");
      assertEquals(
          "C3", audBtnBottom.getText(), "Column 18 bottom text should correctly render note C3");

      if (audBtnBottom instanceof DelugePadButton pad) {
        assertEquals(
            app.getClipPanel().getGridNoteColor(74),
            pad.getBaseColor(),
            "Row 8 Column 18 base color must match dynamic hue-shifted color of C3");
      }

      // Verify Virtual OLED Screen Readout
      assertNotNull(
          FirmwareDisplay.get().getVirtualOLED(), "Virtual OLED singleton must not be null");
    } finally {
      app.dispose();
      bridge.shutdown();
    }
  }
}
