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
        app.addNotify();
        app.validate();
        app.doLayout();
        app.setVisible(true); // Must be true to realize peers on desktop
        startupLatch.countDown();
      } catch (Exception e) {
        e.printStackTrace();
      }


    });
    
    startupLatch.await();

    // Step 0: Start
    BufferedImage img = captureComponent(app);
    saveSnapshot(img, "../docs/swing_step0_start.png", "Swing Step 0", "Initial State");

    // Step 1: Load Song
    SwingUtilities.invokeAndWait(() -> {
      try (java.io.InputStream is = getClass().getResourceAsStream("/SONGS/song1.xml")) {
        if (is != null) {
          org.chuck.deluge.model.ProjectModel model = org.chuck.deluge.xml.DelugeXmlParser.parseSong(is, "song1");
          app.getContentPane().getComponent(1); // Force update
        }
      } catch (Exception ex) {
        ex.printStackTrace();
      }
    });
    img = captureComponent(app);
    saveSnapshot(img, "../docs/swing_step1_loaded_songview.png", "Swing Step 1", "Song View");

    // Step 1b: Clip View
    SwingUtilities.invokeAndWait(() -> {
      // Set bridge steps simulating data
      bridge.setStep(0, 0, true);
      bridge.setStep(0, 4, true);
      bridge.setStep(0, 8, true);
      bridge.setStep(0, 12, true);
    });
    img = captureComponent(app);
    saveSnapshot(img, "../docs/swing_step1_loaded_clipview.png", "Swing Step 1b", "Clip View");

    // Step 2: Playing
    SwingUtilities.invokeAndWait(() -> {
      vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    });
    img = captureComponent(app);
    saveSnapshot(img, "../docs/swing_step3_playing.png", "Swing Step 3", "Transport Playing");

    // Cleanup
    SwingUtilities.invokeLater(() -> app.dispose());
  }

  private BufferedImage captureComponent(java.awt.Component c) {
    int w = c.getWidth();
    int h = c.getHeight();
    if (w <= 0 || h <= 0) {
      Dimension pref = c.getPreferredSize();
      w = pref.width > 0 ? pref.width : 2800;
      h = pref.height > 0 ? pref.height : 1600;
    }
    System.out.println("captureComponent: Drawing component size " + w + "x" + h);
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    c.printAll(g);
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

