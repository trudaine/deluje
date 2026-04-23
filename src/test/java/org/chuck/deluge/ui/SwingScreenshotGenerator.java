package org.chuck.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.midi.MidiInputRouter;
import org.chuck.deluge.midi.MidiService;
import org.junit.jupiter.api.Test;

public class SwingScreenshotGenerator {

  private ChuckVM vm;
  private BridgeContract bridge;
  private MidiService midiService;
  private SwingDelugeApp app;

  @Test
  public void testGenerateSwingScreenshots() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");

    CountDownLatch startupLatch = new CountDownLatch(1);
    
    SwingUtilities.invokeLater(() -> {
      try {
        vm = new ChuckVM(44100, 2);
        bridge = new BridgeContract();
        bridge.register(vm);

        MidiInputRouter router = new MidiInputRouter(vm, bridge);
        midiService = new MidiService(vm, bridge, router);

        app = new SwingDelugeApp(vm, bridge, midiService);
        app.setVisible(false); // Run headless for test
        startupLatch.countDown();
      } catch (Exception e) {
        e.printStackTrace();
      }
    });
    
    startupLatch.await();

    // Step 0: Start
    BufferedImage img = captureComponent(app);
    saveSnapshot(img, "../docs/swing_step0_start.png", "Swing Step 0", "Initial State");

    // Step 1: Song View
    SwingUtilities.invokeAndWait(() -> {
      org.chuck.deluge.model.ProjectModel loadedProject = new org.chuck.deluge.model.ProjectModel();
      loadedProject.getTracks().add(new org.chuck.deluge.model.KitTrackModel("KIT 0"));
      app.getContentPane().getComponent(1); // Force refresh
      app.getContentPane().repaint();
    });
    img = captureComponent(app);
    saveSnapshot(img, "../docs/swing_step1_loaded_songview.png", "Swing Step 1", "Song View");

    // Step 1b: Clip View
    SwingUtilities.invokeAndWait(() -> {
      // focus clip card
    });
    img = captureComponent(app);
    saveSnapshot(img, "../docs/swing_step1_loaded_clipview.png", "Swing Step 1b", "Clip View");

    // Cleanup
    SwingUtilities.invokeLater(() -> app.dispose());
  }

  private BufferedImage captureComponent(java.awt.Component c) {
    BufferedImage img = new BufferedImage(2800, 1600, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    c.paint(g);
    g.dispose();
    return img;
  }

  private void saveSnapshot(BufferedImage image, String path, String title, String description) throws Exception {
    File output = new File(path);
    output.getParentFile().mkdirs();
    ImageIO.write(image, "png", output);
    System.out.println("Swing Screenshot saved to " + output.getAbsolutePath());
  }
}

