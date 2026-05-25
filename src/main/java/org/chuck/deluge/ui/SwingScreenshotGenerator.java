package org.chuck.deluge.ui;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.TrackModel;

/**
 * Programmatic high-fidelity Swing JComponent screenshots generator. Uses a background scheduler
 * thread to set dialogs visible, lets AWT paint peers on the EDT thread, and captures them with
 * high-fidelity AWT paintAll commands.
 */
public class SwingScreenshotGenerator {

  public static void runAutoScreenshots(SwingDelugeApp app, ChuckVM vm, BridgeContract bridge) {

    // Start background scheduler thread so we do not freeze AWT EDT!
    new Thread(
            () -> {
              try {
                System.out.println(
                    "[Screenshot] Starting multi-threaded visual capture pipeline...");
                Thread.sleep(1000);

                // 1. Capture Main sequencers Sequencer grid (Clip view!)
                System.out.println("[Screenshot] Capturing Main Sequencer grid...");
                SwingUtilities.invokeAndWait(() -> captureComponent(app, "deluge_main_sequencer"));
                Thread.sleep(1000);

                // 2. Open and capture Randomizer Suite Dialog
                System.out.println("[Screenshot] Spawning Delugeator Randomizer JDialog...");
                final SwingRandomizerDialog[] randBox = new SwingRandomizerDialog[1];
                SwingUtilities.invokeAndWait(
                    () -> {
                      randBox[0] =
                          new SwingRandomizerDialog(app, vm, bridge, app.getCurrentProject());
                      randBox[0].pack();
                      randBox[0].setSize(840, 940);
                      randBox[0].setVisible(true);
                    });

                // Wait for EDT to fully render all Amber channels and sliders!
                Thread.sleep(1500);

                SwingUtilities.invokeAndWait(
                    () -> {
                      captureComponent(randBox[0], "deluge_randomizer_suite");
                      randBox[0].dispose();
                    });
                Thread.sleep(1000);

                // 3. Open and capture Loop Slicer Dialog
                System.out.println("[Screenshot] Spawning Audio Loop Slicer JDialog...");
                final SwingAudioSlicerDialog[] slicerBox = new SwingAudioSlicerDialog[1];
                SwingUtilities.invokeAndWait(
                    () -> {
                      slicerBox[0] =
                          new SwingAudioSlicerDialog(app, vm, bridge, app.getCurrentProject());
                      slicerBox[0].pack();
                      slicerBox[0].setSize(800, 500);
                      slicerBox[0].setVisible(true);
                    });

                // Wait for EDT to decode files and paint orange division grids!
                Thread.sleep(2000);

                SwingUtilities.invokeAndWait(
                    () -> {
                      captureComponent(slicerBox[0], "deluge_audio_slicer");
                      slicerBox[0].dispose();
                    });
                Thread.sleep(1000);

                // 4. Open and capture Kit Sound Config Dialog with WAV waveform crop deck
                System.out.println(
                    "[Screenshot] Spawning wide-screen Kit Configuration JDialog...");
                List<TrackModel> tracks = app.getCurrentProject().getTracks();
                for (TrackModel t : tracks) {
                  if (t instanceof KitTrackModel kt) {
                    final SwingKitConfigDialog[] kitBox = new SwingKitConfigDialog[1];
                    SwingUtilities.invokeAndWait(
                        () -> {
                          kitBox[0] = new SwingKitConfigDialog(app, kt, vm, bridge);
                          kitBox[0].pack();
                          kitBox[0].setSize(950, 720);
                          kitBox[0].setVisible(true);
                        });

                    // Wait for Project Loom to finish decodes and paint neons!
                    Thread.sleep(2500);

                    SwingUtilities.invokeAndWait(
                        () -> {
                          captureComponent(kitBox[0], "deluge_waveform_crop");
                          kitBox[0].dispose();
                        });
                    break;
                  }
                }

                System.out.println("🎉 PROGRAMMATIC REAL SCREENSHOTS GENERATED SUCCESSFULLY!");
                System.exit(0);
              } catch (Exception ex) {
                System.err.println(
                    "[Screenshot] Capture scheduler scheduler error: " + ex.getMessage());
                System.exit(1);
              }
            })
        .start();
  }

  private static void captureComponent(Component comp, String filename) {
    if (comp == null) return;

    int w = comp.getWidth();
    int h = comp.getHeight();
    if (w <= 0 || h <= 0) {
      w = comp.getPreferredSize().width;
      h = comp.getPreferredSize().height;
    }
    if (w <= 0) w = 800;
    if (h <= 0) h = 600;

    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    Graphics2D g2 = img.createGraphics();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    comp.paintAll(g2);
    g2.dispose();

    try {
      File out = new File("docs/images/" + filename + ".png");
      out.getParentFile().mkdirs();
      ImageIO.write(img, "PNG", out);
      System.out.println(
          "✅ Saved real Swing screenshot: docs/images/"
              + filename
              + ".png ("
              + out.length()
              + " bytes)");
    } catch (Exception ex) {
      System.err.println("❌ Failed to save real screenshot " + filename + ": " + ex.getMessage());
    }
  }
}
