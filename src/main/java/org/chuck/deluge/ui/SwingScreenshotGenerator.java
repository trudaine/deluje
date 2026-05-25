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
import org.chuck.deluge.model.SynthTrackModel;
import org.chuck.deluge.model.TrackModel;

/**
 * Programmatic high-fidelity Swing JComponent screenshots generator. Uses a background scheduler
 * thread to set dialogs visible, lets AWT paint peers on the EDT thread, and captures them with
 * high-fidelity AWT paintAll commands.
 */
public class SwingScreenshotGenerator {

  public static void runAutoScreenshots(SwingDelugeApp app, ChuckVM vm, BridgeContract bridge) {

    new Thread(
            () -> {
              try {
                System.out.println(
                    "[Screenshot] Starting expanded 12-tab visual capture pipeline...");
                Thread.sleep(1000);

                // 1. Capture Main Sequencer grid (Clip view!)
                System.out.println("[Screenshot] Capturing Main Sequencer grid...");
                SwingUtilities.invokeAndWait(
                    () -> {
                      app.getClipPanel().setShiftHeld(false);
                      app.repaint();
                    });
                Thread.sleep(1000);
                SwingUtilities.invokeAndWait(() -> captureComponent(app, "deluge_main_sequencer"));
                Thread.sleep(1000);

                // 2. Capture Shift state Active grid (displays function sub-labels!)
                System.out.println(
                    "[Screenshot] Capturing Sequencer Grid with Shift state active...");
                SwingUtilities.invokeAndWait(
                    () -> {
                      app.getClipPanel().setShiftHeld(true);
                      app.repaint();
                    });
                Thread.sleep(1500);
                SwingUtilities.invokeAndWait(() -> captureComponent(app, "deluge_main_grid_shift"));
                Thread.sleep(1000);
                SwingUtilities.invokeAndWait(
                    () -> {
                      app.getClipPanel().setShiftHeld(false);
                      app.repaint();
                    });
                Thread.sleep(500);

                // 2b. Capture Automation Overview Matrix Grid!
                System.out.println("[Screenshot] Switching to Automation Overview workspace...");
                SwingUtilities.invokeAndWait(
                    () -> {
                      app.setWorkspaceView("AUTOMATION");
                      app.getAutoPanel().setAutoOverviewMode(true);
                      app.repaint();
                    });
                Thread.sleep(1500);
                SwingUtilities.invokeAndWait(
                    () -> captureComponent(app, "deluge_grid_automation_overview"));
                Thread.sleep(1000);

                // 2c. Capture Automation Detail Editor Grid!
                System.out.println(
                    "[Screenshot] Switching to Automation Detail Editor workspace...");
                SwingUtilities.invokeAndWait(
                    () -> {
                      app.getAutoPanel().setAutoOverviewMode(false);
                      app.repaint();
                    });
                Thread.sleep(1500);
                SwingUtilities.invokeAndWait(
                    () -> captureComponent(app, "deluge_grid_automation_editor"));
                Thread.sleep(1000);

                // Restore active display back to standard CLIP view before proceeding!
                SwingUtilities.invokeAndWait(
                    () -> {
                      app.setWorkspaceView("CLIP");
                      app.repaint();
                    });
                Thread.sleep(500);

                // 3. Open and capture Randomizer Suite Dialog
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
                Thread.sleep(1500);
                SwingUtilities.invokeAndWait(
                    () -> {
                      captureComponent(randBox[0], "deluge_randomizer_suite");
                      randBox[0].dispose();
                    });
                Thread.sleep(1000);

                // 4. Open and capture Loop Slicer Dialog
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
                Thread.sleep(2000);
                SwingUtilities.invokeAndWait(
                    () -> {
                      captureComponent(slicerBox[0], "deluge_audio_slicer");
                      slicerBox[0].dispose();
                    });
                Thread.sleep(1000);

                // 5. Open and capture Kit Sound Config Dialog with WAV waveform crop deck
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
                    Thread.sleep(2500);
                    SwingUtilities.invokeAndWait(
                        () -> {
                          captureComponent(kitBox[0], "deluge_waveform_crop");
                          kitBox[0].dispose();
                        });
                    break;
                  }
                }
                Thread.sleep(1000);

                // 6. Open, cycle, and capture ALL tabs in Synth Sound Config Dialog
                System.out.println(
                    "[Screenshot] Spawning wide-screen Synth Configuration JDialog and cycling tabs...");
                for (int i = 0; i < tracks.size(); i++) {
                  TrackModel t = tracks.get(i);
                  if (t instanceof SynthTrackModel st) {
                    final int trackIdx = i;

                    // Programmatically force FM (DX7 6-Operator) mode to showcase all dynamic tabs!
                    st.setSynthMode(1);

                    final SwingSynthConfigDialog[] synthBox = new SwingSynthConfigDialog[1];
                    SwingUtilities.invokeAndWait(
                        () -> {
                          synthBox[0] =
                              new SwingSynthConfigDialog(
                                  app, st, vm, bridge, trackIdx, app.getCurrentProject());
                          synthBox[0].pack();
                          synthBox[0].setSize(950, 720);
                          synthBox[0].setVisible(true);
                        });
                    Thread.sleep(1500);

                    JTabbedPane synthTabs = synthBox[0].getTabbedPane();
                    for (int tIdx = 0; tIdx < synthTabs.getTabCount(); tIdx++) {
                      final int finalTIdx = tIdx;
                      SwingUtilities.invokeAndWait(() -> synthTabs.setSelectedIndex(finalTIdx));
                      Thread.sleep(1000); // Give the sub-panel EDT cycles to paint cleanly!

                      String rawTitle = synthTabs.getTitleAt(tIdx);
                      String tabName =
                          rawTitle.toUpperCase().replaceAll("[^A-Z0-9]", "_").toLowerCase();
                      SwingUtilities.invokeAndWait(
                          () -> captureComponent(synthBox[0], "deluge_synth_tab_" + tabName));
                    }

                    SwingUtilities.invokeAndWait(() -> synthBox[0].dispose());
                    break;
                  }
                }
                Thread.sleep(1000);

                // 7. Open and capture Settings Preferences Dialog
                System.out.println("[Screenshot] Spawning system Settings Preferences JDialog...");
                final PreferencesDialog[] prefBox = new PreferencesDialog[1];
                SwingUtilities.invokeAndWait(
                    () -> {
                      prefBox[0] = new PreferencesDialog(app, () -> {}, () -> {});
                      prefBox[0].setModal(
                          false); // Disable modality for programmatic screenshot capture!
                      prefBox[0].pack();
                      prefBox[0].setSize(640, 560);
                      prefBox[0].setVisible(true);
                    });
                Thread.sleep(1500);
                SwingUtilities.invokeAndWait(
                    () -> {
                      captureComponent(prefBox[0], "deluge_preferences");
                      prefBox[0].dispose();
                    });
                Thread.sleep(1000);

                // 8. Open and capture Step Properties Dialog
                System.out.println("[Screenshot] Spawning Step Properties JDialog...");
                final StepPropertiesDialog[] stepBox = new StepPropertiesDialog[1];
                SwingUtilities.invokeAndWait(
                    () -> {
                      stepBox[0] = new StepPropertiesDialog(app, 85, 2, 45);
                      stepBox[0].setModal(false); // Disable modality for screenshots!
                      stepBox[0].pack();
                      stepBox[0].setSize(750, 450);
                      stepBox[0].setVisible(true);
                    });
                Thread.sleep(1500);
                SwingUtilities.invokeAndWait(
                    () -> {
                      captureComponent(stepBox[0], "deluge_step_properties");
                      stepBox[0].dispose();
                    });

                System.out.println(
                    "🎉 ALL EXPANDED SYSTEM REAL SCREENSHOTS GENERATED SUCCESSFULLY!");
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
      File out = new File("deluge/src/main/resources/docs/images/" + filename + ".png");
      out.getParentFile().mkdirs();
      ImageIO.write(img, "PNG", out);
      System.out.println(
          "✅ Saved real Swing screenshot: deluge/src/main/resources/docs/images/"
              + filename
              + ".png ("
              + out.length()
              + " bytes)");
    } catch (Exception ex) {
      System.err.println("❌ Failed to save real screenshot " + filename + ": " + ex.getMessage());
    }
  }
}
