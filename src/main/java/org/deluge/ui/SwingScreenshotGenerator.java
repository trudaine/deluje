package org.deluge.ui;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.deluge.BridgeContract;
import org.deluge.model.KitTrackModel;
import org.deluge.model.SoundDrum;
import org.deluge.model.SynthTrackModel;
import org.deluge.model.TrackModel;

/**
 * Programmatic high-fidelity Swing JComponent screenshots generator. Uses a background scheduler
 * thread to set dialogs visible, lets AWT paint peers on the EDT thread, and captures them with
 * high-fidelity AWT paintAll commands.
 */
public class SwingScreenshotGenerator {

  public static void runAutoScreenshots(SwingDelugeApp app, final BridgeContract bridge) {

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

                // 2d. Capture Performance View (PERF) Workspace Panel!
                System.out.println(
                    "[Screenshot] Switching to Performance View (PERF) workspace...");
                SwingUtilities.invokeAndWait(
                    () -> {
                      app.setWorkspaceView("PERF");
                      app.repaint();
                    });
                Thread.sleep(1500);
                SwingUtilities.invokeAndWait(
                    () -> captureComponent(app, "deluge_performance_view"));
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
                      randBox[0] = new SwingRandomizerDialog(app, bridge, app.getCurrentProject());
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
                          new SwingAudioSlicerDialog(app, bridge, app.getCurrentProject());
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
                boolean capturedKitShots = false;
                for (TrackModel t : tracks) {
                  if (t instanceof KitTrackModel kt) {
                    // Both kit-only shots need a SoundDrum to seed the wavetable dialog; find the
                    // first one rather than blindly casting drum 0 (a sample-only Drum there would
                    // throw and abort the rest of the pipeline).
                    SoundDrum targetSound = null;
                    for (var d : kt.getDrums()) {
                      if (d instanceof SoundDrum sd) {
                        targetSound = sd;
                        break;
                      }
                    }
                    if (targetSound == null) {
                      continue; // no SoundDrum in this kit; try the next track
                    }
                    final SoundDrum seedSound = targetSound;
                    final SwingKitConfigDialog[] kitBox = new SwingKitConfigDialog[1];
                    SwingUtilities.invokeAndWait(
                        () -> {
                          kitBox[0] = new SwingKitConfigDialog(app, kt, bridge, tracks.indexOf(kt));
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
                    Thread.sleep(1000);

                    // 5b. Capture Wavetable Index Laboratory Dialog!
                    System.out.println(
                        "[Screenshot] Spawning Wavetable Index Laboratory Dialog...");
                    final SwingWavetableDialog[] wtBox = new SwingWavetableDialog[1];
                    int trackIndex = tracks.indexOf(kt);
                    SwingUtilities.invokeAndWait(
                        () -> {
                          wtBox[0] =
                              new SwingWavetableDialog(app, seedSound, bridge, trackIndex, 0);
                          wtBox[0].setSize(900, 480);
                          wtBox[0].setVisible(true);
                        });
                    Thread.sleep(2000);
                    SwingUtilities.invokeAndWait(
                        () -> captureComponent(wtBox[0], "deluge_wavetable_laboratory"));
                    Thread.sleep(500);
                    SwingUtilities.invokeAndWait(() -> wtBox[0].dispose());
                    Thread.sleep(500);
                    capturedKitShots = true;
                    break;
                  }
                }
                if (!capturedKitShots) {
                  // The default boot project is synth-only (ProjectModel.createDefaultProject), so
                  // a
                  // plain `--screenshot` run cannot refresh these two. Say so loudly instead of
                  // silently leaving deluge_waveform_crop.png / deluge_wavetable_laboratory.png
                  // stale.
                  System.out.println(
                      "[Screenshot] WARNING: no Kit track with a SoundDrum in the loaded project — "
                          + "skipping deluge_waveform_crop and deluge_wavetable_laboratory. "
                          + "Re-run with a kit song, e.g. `--screenshot /path/to/KitSong.xml`, "
                          + "to regenerate those two images.");
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
                                  app, st, bridge, trackIdx, app.getCurrentProject());
                          // Keep the dialog's own content-fitting size (constructor
                          // sizeToFitContent)
                          // instead of forcing a small fixed size that clipped the taller tabs.
                          synthBox[0].setVisible(true);
                        });
                    Thread.sleep(1500);

                    JTabbedPane synthTabs = synthBox[0].getTabbedPane();
                    final int[] synBase = new int[2];
                    SwingUtilities.invokeAndWait(
                        () -> {
                          synBase[0] = synthBox[0].getWidth();
                          synBase[1] = synthBox[0].getHeight();
                        });
                    for (int tIdx = 0; tIdx < synthTabs.getTabCount(); tIdx++) {
                      final int finalTIdx = tIdx;
                      SwingUtilities.invokeAndWait(() -> synthTabs.setSelectedIndex(finalTIdx));
                      Thread.sleep(800);
                      Component tabComp = synthTabs.getComponentAt(tIdx);
                      if (tabComp instanceof JTabbedPane subTabs) {
                        // Drill into nested sub-tabs (SOURCES, FX, SETUP) so each leaf tab (OSC,
                        // ALGORITHM, DX7, MOD FX, EQ, COMPRESSOR, AUTOMATION, MIDI LEARN) is
                        // captured — whole dialog, so header + both tab strips identify it.
                        for (int sIdx = 0; sIdx < subTabs.getTabCount(); sIdx++) {
                          final int finalSIdx = sIdx;
                          SwingUtilities.invokeAndWait(() -> subTabs.setSelectedIndex(finalSIdx));
                          Thread.sleep(800);
                          String sub = "deluge_synth_tab_" + slug(subTabs.getTitleAt(sIdx));
                          captureDialogTab(
                              synthBox[0],
                              subTabs.getComponentAt(finalSIdx),
                              synBase[0],
                              synBase[1],
                              sub);
                        }
                      } else {
                        String tabName = "deluge_synth_tab_" + slug(synthTabs.getTitleAt(tIdx));
                        captureDialogTab(synthBox[0], tabComp, synBase[0], synBase[1], tabName);
                      }
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
                      prefBox[0] =
                          new PreferencesDialog(app, app.getMidiService(), () -> {}, () -> {});
                      prefBox[0].setModal(
                          false); // Disable modality for programmatic screenshot capture!
                      prefBox[0].pack();
                      prefBox[0].setSize(680, 800);
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
                Thread.sleep(1000);

                // 9. Open and capture Pure JTree Project Explorer!
                System.out.println(
                    "[Screenshot] Spawning and capturing Pure JTree Project Explorer JDialog...");
                SwingUtilities.invokeAndWait(
                    () -> {
                      JDialog explorerDialog = app.getLeftFloat();
                      if (explorerDialog != null) {
                        explorerDialog.setVisible(true);
                        captureComponent(explorerDialog, "deluge_project_explorer");
                        explorerDialog.setVisible(false);
                      }
                    });
                Thread.sleep(1000);

                // 10. Open and capture the new Dedicated MIDI Settings Dialog!
                System.out.println(
                    "[Screenshot] Spawning and capturing the new Dedicated MIDI Settings JDialog...");
                final PreferencesDialog[] midiBox = new PreferencesDialog[1];
                SwingUtilities.invokeAndWait(
                    () -> {
                      midiBox[0] =
                          new PreferencesDialog(app, app.getMidiService(), () -> {}, () -> {});
                      midiBox[0].setModal(false); // Modality off for screenshots!
                      midiBox[0].pack();
                      midiBox[0].setSize(680, 800);
                      midiBox[0].selectMidiTab();
                      midiBox[0].setVisible(true);
                    });
                Thread.sleep(2000);
                SwingUtilities.invokeAndWait(
                    () -> {
                      captureComponent(midiBox[0], "deluge_midi_device_settings");
                      midiBox[0].dispose();
                    });
                Thread.sleep(1000);

                // 11. Open and capture Threshold Record Dialog with its Target Track dropdown open!
                System.out.println(
                    "[Screenshot] Spawning and capturing Threshold Record Dialog with target track dropdown open...");
                final ThresholdRecordDialog[] recBox = new ThresholdRecordDialog[1];
                SwingUtilities.invokeAndWait(
                    () -> {
                      recBox[0] = new ThresholdRecordDialog(app, app.getCurrentProject(), () -> {});
                      recBox[0].setModal(false); // Modality off for screenshot
                      recBox[0].pack();
                      recBox[0].setSize(500, 360);
                      recBox[0].setVisible(true);
                    });
                Thread.sleep(1000);
                SwingUtilities.invokeAndWait(
                    () -> {
                      // Find the track combobox and show it!
                      JComboBox<?> combo = findComponent(recBox[0], JComboBox.class);
                      if (combo != null) {
                        combo.setPopupVisible(true);
                      }
                      recBox[0].repaint();
                    });
                Thread.sleep(1500); // Give the popup list time to paint
                SwingUtilities.invokeAndWait(
                    () -> {
                      captureComponent(recBox[0], "deluge_threshold_record_dropdown");
                      recBox[0].dispose();
                    });
                Thread.sleep(1000);

                // ── 12. Previously-uncaptured dialogs (Master FX + Track Inspector are tabbed) ──
                System.out.println("[Screenshot] Capturing remaining dialogs...");
                java.util.List<TrackModel> allTracks = app.getCurrentProject().getTracks();
                SynthTrackModel firstSynth = null;
                int firstSynthIdx = 0;
                for (int i = 0; i < allTracks.size(); i++) {
                  if (allTracks.get(i) instanceof SynthTrackModel s) {
                    firstSynth = s;
                    firstSynthIdx = i;
                    break;
                  }
                }

                try {
                  captureDialog(
                      new SwingMasterFxDialog(app, app.getCurrentProject(), bridge, app),
                      "deluge_master_fx_console");
                } catch (Exception ex) {
                  System.err.println("[Screenshot] MasterFx skipped: " + ex);
                }
                try {
                  captureDialog(
                      new TrackInspectorDialog(app, 0, allTracks, () -> {}),
                      "deluge_track_inspector");
                } catch (Exception ex) {
                  System.err.println("[Screenshot] TrackInspector skipped: " + ex);
                }
                try {
                  captureDialog(
                      new SwingTuningDialog(app, app.getCurrentProject(), () -> {}),
                      "deluge_tuning");
                } catch (Exception ex) {
                  System.err.println("[Screenshot] Tuning skipped: " + ex);
                }
                try {
                  captureDialog(new SwingRecordingCleanerDialog(app), "deluge_recording_cleaner");
                } catch (Exception ex) {
                  System.err.println("[Screenshot] RecordingCleaner skipped: " + ex);
                }
                try {
                  captureDialog(new BarAutomationDialog(app, 0), "deluge_bar_automation");
                } catch (Exception ex) {
                  System.err.println("[Screenshot] BarAutomation skipped: " + ex);
                }
                try {
                  captureDialog(
                      new EuclideanRhythmDialog(app, bridge, 0, 16, "Row", () -> {}),
                      "deluge_euclidean_rhythm");
                } catch (Exception ex) {
                  System.err.println("[Screenshot] Euclidean skipped: " + ex);
                }
                if (firstSynth != null) {
                  try {
                    captureDialog(
                        new SwingDroneLabDialog(
                            app, firstSynth, bridge, firstSynthIdx, app.getCurrentProject()),
                        "deluge_drone_lab");
                  } catch (Exception ex) {
                    System.err.println("[Screenshot] DroneLab skipped: " + ex);
                  }
                  try {
                    captureDialog(
                        new SwingKeyZoneMapperDialog(app, firstSynth, 0, firstSynthIdx, bridge),
                        "deluge_keyzone_mapper");
                  } catch (Exception ex) {
                    System.err.println("[Screenshot] KeyZoneMapper skipped: " + ex);
                  }
                  try {
                    captureDialog(
                        new SwingSynthWavetableEditorDialog(
                            app, firstSynth, 0, firstSynthIdx, bridge),
                        "deluge_synth_wavetable_editor");
                  } catch (Exception ex) {
                    System.err.println("[Screenshot] SynthWavetableEditor skipped: " + ex);
                  }
                }

                // ── 13. Menu-bar menus (File / Edit / Tools / View / Settings / Macro / Help) ──
                System.out.println("[Screenshot] Capturing menu-bar menus...");
                JMenuBar menuBar = app.getJMenuBar();
                if (menuBar != null) {
                  for (int i = 0; i < menuBar.getMenuCount(); i++) {
                    JMenu menu = menuBar.getMenu(i);
                    if (menu == null) continue;
                    try {
                      captureMenu(app, menu, slug(menu.getText()));
                    } catch (Exception ex) {
                      System.err.println("[Screenshot] Menu " + menu.getText() + " skipped: " + ex);
                    }
                  }
                }

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

  /**
   * Guidebook workflow captures (invoked by {@code --screenshot-guide}): Song view, Arranger view,
   * a kit track's clip grid, and the Piano Roll editor — the workflow views the user manual lacked
   * images for. Unlike {@link #runAutoScreenshots} this leaves every existing image untouched.
   * Expects a multi-track song (with at least one kit track) to be loaded.
   */
  public static void runGuidebookCaptures(SwingDelugeApp app, final BridgeContract bridge) {
    new Thread(
            () -> {
              try {
                System.out.println("[Screenshot] Starting guidebook workflow captures...");

                // ── Song view ──
                SwingUtilities.invokeAndWait(
                    () -> {
                      app.setWorkspaceView("SONG");
                      app.repaint();
                    });
                Thread.sleep(1500);
                SwingUtilities.invokeAndWait(() -> captureComponent(app, "deluge_song_view"));

                // ── Arranger view ──
                SwingUtilities.invokeAndWait(
                    () -> {
                      app.setWorkspaceView("ARR");
                      app.repaint();
                    });
                Thread.sleep(1500);
                SwingUtilities.invokeAndWait(() -> captureComponent(app, "deluge_arranger_view"));

                // ── Kit track clip grid ──
                var project = app.getCurrentProject();
                int kitIdx = -1, synthIdx = -1;
                if (project != null) {
                  for (int t = 0; t < project.getTracks().size(); t++) {
                    var track = project.getTracks().get(t);
                    if (kitIdx < 0 && track instanceof org.deluge.model.KitTrackModel) kitIdx = t;
                    // For the piano roll, prefer a synth track whose first clip actually has
                    // notes so the capture isn't an empty grid.
                    if (synthIdx < 0
                        && track instanceof org.deluge.model.SynthTrackModel
                        && !track.getClips().isEmpty()
                        && clipHasNotes(track.getClips().get(0))) {
                      synthIdx = t;
                    }
                  }
                }
                if (kitIdx >= 0) {
                  final int fk = kitIdx;
                  SwingUtilities.invokeAndWait(
                      () -> {
                        app.setWorkspaceView("CLIP");
                        app.switchToTrackEdit(fk, 0);
                        app.repaint();
                      });
                  Thread.sleep(1500);
                  SwingUtilities.invokeAndWait(() -> captureComponent(app, "deluge_kit_clip_grid"));
                } else {
                  System.out.println("[Screenshot] No kit track in song — kit grid skipped.");
                }

                // ── Piano Roll editor ──
                if (synthIdx >= 0) {
                  final int fs = synthIdx;
                  try {
                    final SwingPianoRollDialog[] prBox = new SwingPianoRollDialog[1];
                    SwingUtilities.invokeAndWait(
                        () -> {
                          prBox[0] =
                              new SwingPianoRollDialog(
                                  app, app.getClipPanel(), fs, 0, project, bridge);
                          prBox[0].pack();
                          prBox[0].setVisible(true);
                        });
                    Thread.sleep(1200);
                    // Scroll the pitch axis so the band of rows that actually contain notes is
                    // centered (the dialog paints row r at r * rowHeight over a 128-row canvas).
                    final var prClip = project.getTracks().get(fs).getClips().get(0);
                    SwingUtilities.invokeAndWait(
                        () -> {
                          javax.swing.JScrollPane sp =
                              findComponent(prBox[0], javax.swing.JScrollPane.class);
                          if (sp == null) return;
                          int minRow = Integer.MAX_VALUE, maxRow = -1;
                          for (int r = 0; r < 128; r++) {
                            for (int s = 0; s < prClip.getStepCount(); s++) {
                              var step = prClip.getStep(r, s);
                              if (step != null && step.active()) {
                                minRow = Math.min(minRow, r);
                                maxRow = Math.max(maxRow, r);
                              }
                            }
                          }
                          if (maxRow < 0) return;
                          var vp = sp.getViewport();
                          int rowH = vp.getView().getHeight() / 128;
                          int bandCenter = ((minRow + maxRow) / 2) * rowH;
                          int y = Math.max(0, bandCenter - vp.getExtentSize().height / 2);
                          vp.setViewPosition(new java.awt.Point(0, y));
                        });
                    Thread.sleep(600);
                    SwingUtilities.invokeAndWait(
                        () -> {
                          captureComponent(prBox[0], "deluge_piano_roll");
                          prBox[0].dispose();
                        });
                  } catch (Exception ex) {
                    System.err.println("[Screenshot] Piano Roll skipped: " + ex);
                  }
                } else {
                  System.out.println("[Screenshot] No synth track in song — piano roll skipped.");
                }

                System.out.println("🎉 Guidebook workflow captures complete!");
                System.exit(0);
              } catch (Exception ex) {
                System.err.println("[Screenshot] Guidebook capture error: " + ex.getMessage());
                System.exit(1);
              }
            })
        .start();
  }

  /** True if any step in the clip is active (used to pick a non-empty clip to photograph). */
  private static boolean clipHasNotes(org.deluge.model.ClipModel clip) {
    for (int r = 0; r < clip.getRowCount(); r++) {
      for (int s = 0; s < clip.getStepCount(); s++) {
        org.deluge.model.StepData step = clip.getStep(r, s);
        if (step != null && step.active()) return true;
      }
    }
    return false;
  }

  /**
   * Tab title -> image-name slug, e.g. "MOD FX" -> "mod_fx", "OSC / FILTER / FM" ->
   * "osc___filter___fm".
   */
  private static String slug(String title) {
    return title.toUpperCase().replaceAll("[^A-Z0-9]", "_").toLowerCase();
  }

  /**
   * Capture a synth tab's content UN-CLIPPED: unwrap the scroll pane, lay the panel out at its full
   * preferred size, and paint that (so even oversized tabs like AUTOMATION are captured whole
   * rather than showing a scrollbar). Writes deluge_synth_tab_&lt;name&gt;.png.
   */
  private static void captureTabContent(Component tabComp, String name) {
    captureFull(tabComp, "deluge_synth_tab_" + name);
  }

  /** Full-size capture of a (possibly scroll-wrapped) panel; {@code imageName} is the file stem. */
  private static void captureFull(Component tabComp, String imageName) {
    Component view = tabComp;
    if (tabComp instanceof javax.swing.JScrollPane sp) {
      view = sp.getViewport().getView();
    }
    if (view == null) return;
    Dimension pref = view.getPreferredSize();
    int w = Math.max(pref.width, view.getWidth());
    int h = Math.max(pref.height, view.getHeight());
    if (w <= 0) w = 800;
    if (h <= 0) h = 600;
    view.setSize(w, h);
    layoutTree(view); // recursively lay out at the full size so nothing clips
    captureComponent(view, imageName);
  }

  /**
   * Open a dialog, capture it (recursing into any tabbed pane so every tab is a full-size image),
   * then dispose. Base name {@code deluge_<slug>}; tabs are {@code deluge_<slug>_<tab>}.
   */
  private static void captureDialog(JDialog dialog, String base) throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          // Force modeless: a modal dialog's setVisible(true) blocks the EDT and hangs the
          // pipeline.
          dialog.setModalityType(java.awt.Dialog.ModalityType.MODELESS);
          dialog.pack();
          Rectangle scr =
              GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
          dialog.setSize(
              Math.min(dialog.getWidth(), scr.width - 40),
              Math.min(dialog.getHeight(), scr.height - 60));
          dialog.setLocationRelativeTo(null);
          dialog.setVisible(true);
        });
    Thread.sleep(1200);
    JTabbedPane tp = findComponent(dialog, JTabbedPane.class);
    if (tp != null) {
      final int[] base0 = new int[2];
      SwingUtilities.invokeAndWait(
          () -> {
            base0[0] = dialog.getWidth();
            base0[1] = dialog.getHeight();
          });
      for (int i = 0; i < tp.getTabCount(); i++) {
        final int fi = i;
        SwingUtilities.invokeAndWait(() -> tp.setSelectedIndex(fi));
        Thread.sleep(600);
        String nm = base + "_" + slug(tp.getTitleAt(i));
        captureDialogTab(dialog, tp.getComponentAt(i), base0[0], base0[1], nm);
      }
    } else {
      SwingUtilities.invokeAndWait(() -> captureComponent(dialog, base));
    }
    SwingUtilities.invokeAndWait(dialog::dispose);
    Thread.sleep(500);
  }

  /**
   * Capture the WHOLE dialog for the currently-selected tab — grown so the tab's content fully fits
   * (no scroll/clip) while keeping the dialog's header + tab strip visible (so each image
   * identifies its dialog and tab). Restores the base size afterwards so the next tab measures from
   * the same baseline.
   */
  private static void captureDialogTab(
      JDialog dialog, Component tabComp, int baseW, int baseH, String imageName) throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          Component leaf =
              (tabComp instanceof javax.swing.JScrollPane sp)
                  ? sp.getViewport().getView()
                  : tabComp;
          Dimension pref = leaf.getPreferredSize();
          int deficitW = Math.max(0, pref.width - tabComp.getWidth());
          int deficitH = Math.max(0, pref.height - tabComp.getHeight());
          dialog.setSize(baseW + deficitW + 4, baseH + deficitH + 4);
          dialog.validate();
          captureComponent(dialog, imageName);
          dialog.setSize(baseW, baseH);
          dialog.validate();
        });
    Thread.sleep(200);
  }

  /**
   * Show a menu-bar menu's popup and capture it at full size. Writes {@code deluge_menu_<name>}.
   */
  private static void captureMenu(SwingDelugeApp app, JMenu menu, String name) throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          JPopupMenu pm = menu.getPopupMenu();
          pm.setInvoker(menu);
          // Position under the menu in the menu bar; show to realize peers.
          java.awt.Point p = menu.getLocationOnScreen();
          pm.setLocation(p.x, p.y + menu.getHeight());
          pm.setVisible(true);
        });
    Thread.sleep(500);
    SwingUtilities.invokeAndWait(
        () -> {
          JPopupMenu pm = menu.getPopupMenu();
          captureFull(pm, "deluge_menu_" + name);
          pm.setVisible(false);
        });
    Thread.sleep(200);
  }

  /**
   * Recursively re-layout a container subtree (so a resized off-viewport panel paints correctly).
   */
  private static void layoutTree(Component c) {
    c.doLayout();
    if (c instanceof Container cont) {
      for (Component child : cont.getComponents()) {
        layoutTree(child);
      }
    }
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
      File out = new File("src/main/resources/docs/images/" + filename + ".png");
      out.getParentFile().mkdirs();
      ImageIO.write(img, "PNG", out);
      System.out.println(
          "✅ Saved real Swing screenshot: src/main/resources/docs/images/"
              + filename
              + ".png ("
              + out.length()
              + " bytes)");
    } catch (Exception ex) {
      System.err.println("❌ Failed to save real screenshot " + filename + ": " + ex.getMessage());
    }
  }

  /**
   * Helper to recursively find a child component of a specific type in an AWT Container. Enables
   * finding deep controls like JComboBoxes without modifying dialog classes.
   */
  private static <T extends Component> T findComponent(Container parent, Class<T> type) {
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
}
