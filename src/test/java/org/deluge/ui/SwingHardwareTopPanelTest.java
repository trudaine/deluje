package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.deluge.project.PreferencesManager;
import org.junit.jupiter.api.Test;

public class SwingHardwareTopPanelTest {

  @Test
  public void testTopPanelStylePreferenceAndImageRendering() {
    PreferencesManager.TopPanelStyle oldStyle = PreferencesManager.getTopPanelStyle();
    try {
      PreferencesManager.setTopPanelStyle(PreferencesManager.TopPanelStyle.HARDWARE_FACEPLATE);
      assertEquals(
          PreferencesManager.TopPanelStyle.HARDWARE_FACEPLATE,
          PreferencesManager.getTopPanelStyle());

      SwingHardwareTopPanel panel = new SwingHardwareTopPanel(null, null, null, null);
      panel.setSize(new Dimension(1400, 190));
      assertNotNull(panel, "SwingHardwareTopPanel must initialize cleanly");

      BufferedImage img = new BufferedImage(1400, 190, BufferedImage.TYPE_INT_ARGB);
      Graphics2D g = img.createGraphics();
      panel.paint(g);
      g.dispose();
    } finally {
      PreferencesManager.setTopPanelStyle(oldStyle);
    }
  }
}
