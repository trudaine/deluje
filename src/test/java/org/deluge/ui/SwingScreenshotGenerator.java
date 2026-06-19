package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.deluge.BridgeContract;
import org.deluge.midi.MidiInputRouter;
import org.deluge.midi.MidiService;
import org.junit.jupiter.api.Test;

public class SwingScreenshotGenerator {

  private BridgeContract bridge;

  private MidiService midiService;
  private SwingDelugeApp app;

  @Test
  public void testGenerateSwingScreenshots() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");

    CountDownLatch startupLatch = new CountDownLatch(1);

    SwingUtilities.invokeLater(
        () -> {
          try {
            bridge = new BridgeContract();

            MidiInputRouter router = new MidiInputRouter(bridge);
            midiService = new MidiService(bridge, router);

            app = new SwingDelugeApp(bridge, midiService);
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
    SwingUtilities.invokeAndWait(
        () -> {
          try (java.io.InputStream is = getClass().getResourceAsStream("/SONGS/song1.xml")) {
            if (is != null) {
              org.deluge.model.ProjectModel model =
                  org.deluge.xml.DelugeXmlParser.parseSong(is, "song1");
              app.getContentPane().getComponent(1); // Force update
            }
          } catch (Exception ex) {
            ex.printStackTrace();
          }
        });
    img = captureComponent(app);
    saveSnapshot(img, "../docs/swing_step1_loaded_songview.png", "Swing Step 1", "Song View");

    // Step 1b: Clip View
    SwingUtilities.invokeAndWait(
        () -> {
          // Set bridge steps simulating data
          bridge.setStep(0, 0, true);
          bridge.setStep(0, 4, true);
          bridge.setStep(0, 8, true);
          bridge.setStep(0, 12, true);
        });
    img = captureComponent(app);
    saveSnapshot(img, "../docs/swing_step1_loaded_clipview.png", "Swing Step 1b", "Clip View");

    // Step 2: Playing
    SwingUtilities.invokeAndWait(
        () -> {
          bridge.setGlobalInt(BridgeContract.G_PLAY, 1L);
        });
    img = captureComponent(app);
    saveSnapshot(img, "../docs/swing_step3_playing.png", "Swing Step 3", "Transport Playing");

    // Cleanup
    SwingUtilities.invokeLater(() -> app.dispose());
  }

  @Test
  public void testGenerateAutoViewScreenshot() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");

    CountDownLatch startupLatch = new CountDownLatch(1);

    SwingUtilities.invokeLater(
        () -> {
          try {
            bridge = new BridgeContract();

            MidiInputRouter router = new MidiInputRouter(bridge);
            midiService = new MidiService(bridge, router);

            app = new SwingDelugeApp(bridge, midiService);
            app.addNotify();
            app.validate();
            app.doLayout();
            app.setVisible(true);
            startupLatch.countDown();
          } catch (Exception e) {
            e.printStackTrace();
          }
        });

    startupLatch.await();

    // Load Song 3 first so there are tracks and active clips
    SwingUtilities.invokeAndWait(
        () -> {
          try (java.io.InputStream is = getClass().getResourceAsStream("/SONGS/song3.xml")) {
            if (is != null) {
              org.deluge.model.ProjectModel model =
                  org.deluge.xml.DelugeXmlParser.parseSong(is, "song3");
              app.loadProject(model);
            }
          } catch (Exception ex) {
            ex.printStackTrace();
          }
        });

    // Select AUTO view mode
    SwingUtilities.invokeAndWait(
        () -> {
          app.setWorkspaceView("AUTO");
        });

    // Wait a bit for layout to settle
    Thread.sleep(500);

    BufferedImage img = captureComponent(app);
    saveSnapshot(
        img,
        new File(System.getProperty("java.io.tmpdir"), "deluge_auto_view.png").getAbsolutePath(),
        "Auto View",
        "Automation view of the Swing Deluge");

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

  private void saveSnapshot(BufferedImage image, String path, String title, String description)
      throws Exception {
    File output = new File(path);
    output.getParentFile().mkdirs();
    ImageIO.write(image, "png", output);
    System.out.println("Swing Screenshot saved to " + output.getAbsolutePath());
  }
}
