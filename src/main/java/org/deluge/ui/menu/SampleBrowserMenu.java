package org.deluge.ui.menu;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.deluge.engine.FirmwareSound;
import org.deluge.hid.ActionResult;
import org.deluge.hid.Button;
import org.deluge.hid.FirmwareDisplay;
import org.deluge.playback.Sample;
import org.deluge.project.PreferencesManager;
import org.deluge.storage.audio.AudioFileReader;

/**
 * A specialized menu for browsing and loading samples into a sound slot. Refactored to support
 * async, virtual-threaded lazy loading and non-blocking audio decoding.
 */
public class SampleBrowserMenu extends Submenu {
  private final FirmwareSound sound;
  private final int oscSlot;
  private final File currentDir;
  private boolean populated = false;
  private final List<MenuItem> items = new ArrayList<>();
  private int focusIndex = 0;

  public SampleBrowserMenu(String name, FirmwareSound sound, int oscSlot) {
    this(name, sound, oscSlot, PreferencesManager.getLibraryDir());
  }

  public SampleBrowserMenu(String name, FirmwareSound sound, int oscSlot, File dir) {
    super(name);
    this.sound = sound;
    this.oscSlot = oscSlot;
    this.currentDir = dir;
    // Do NOT populate in constructor. Defer until entered by user (Lazy Loading).
  }

  @Override
  public void selectEncoderAction(int offset) {
    if (!populated) {
      triggerLazyLoad();
      return;
    }
    if (items.isEmpty()) return;
    focusIndex = (focusIndex + offset) % items.size();
    if (focusIndex < 0) focusIndex += items.size();

    MenuItem focused = items.get(focusIndex);
    focused.onFocus();
    FirmwareDisplay.get().setText(focused.title);
  }

  @Override
  public ActionResult enter() {
    if (!populated) {
      triggerLazyLoad();
      return ActionResult.DEALT_WITH;
    }
    if (items.isEmpty()) return ActionResult.DEALT_WITH;
    return items.get(focusIndex).enter();
  }

  @Override
  public ActionResult buttonAction(Button b, boolean on) {
    if (!populated) return ActionResult.NOT_DEALT_WITH;
    if (items.isEmpty()) return ActionResult.NOT_DEALT_WITH;
    return items.get(focusIndex).buttonAction(b, on);
  }

  private void triggerLazyLoad() {
    populated = true;
    MenuItem loadingItem =
        new MenuItem("LOADING...") {
          @Override
          public void onFocus() {
            FirmwareDisplay.get().setText("LOADING...");
          }
        };
    items.add(loadingItem);
    FirmwareDisplay.get().setText("LOADING...");

    Thread.ofVirtual()
        .start(
            () -> {
              try {
                if (currentDir == null || !currentDir.exists()) {
                  javax.swing.SwingUtilities.invokeLater(
                      () -> {
                        items.clear();
                        FirmwareDisplay.get().setText("EMPTY");
                      });
                  return;
                }

                File[] files = currentDir.listFiles();
                final List<MenuItem> loadedItems = new ArrayList<>();

                if (files != null) {
                  List<File> dirs = new ArrayList<>();
                  List<File> wavs = new ArrayList<>();
                  for (File f : files) {
                    if (f.isDirectory()) {
                      dirs.add(f);
                    } else if (f.isFile() && f.getName().toLowerCase().endsWith(".wav")) {
                      wavs.add(f);
                    }
                  }

                  // Sort alphabetically
                  Collections.sort(
                      dirs, (f1, f2) -> f1.getName().compareToIgnoreCase(f2.getName()));
                  Collections.sort(
                      wavs, (f1, f2) -> f1.getName().compareToIgnoreCase(f2.getName()));

                  for (File d : dirs) {
                    loadedItems.add(new SampleBrowserMenu(d.getName(), sound, oscSlot, d));
                  }
                  for (File w : wavs) {
                    loadedItems.add(new SampleFileMenuItem(w, sound, oscSlot));
                  }
                }

                javax.swing.SwingUtilities.invokeLater(
                    () -> {
                      items.clear();
                      items.addAll(loadedItems);
                      focusIndex = 0;
                      if (!items.isEmpty()) {
                        MenuItem focused = items.get(0);
                        focused.onFocus();
                        FirmwareDisplay.get().setText(focused.title);
                      } else {
                        FirmwareDisplay.get().setText("EMPTY");
                      }
                    });
              } catch (Exception ex) {
                javax.swing.SwingUtilities.invokeLater(
                    () -> {
                      items.clear();
                      FirmwareDisplay.get().setText("ERR: " + ex.getMessage());
                    });
              }
            });
  }

  private static class SampleFileMenuItem extends MenuItem {
    private final File file;
    private final FirmwareSound sound;
    private final int oscSlot;

    public SampleFileMenuItem(File file, FirmwareSound sound, int oscSlot) {
      super(file.getName());
      this.file = file;
      this.sound = sound;
      this.oscSlot = oscSlot;
    }

    @Override
    public void onFocus() {
      FirmwareDisplay.get().setText(name);
    }

    @Override
    public ActionResult enter() {
      FirmwareDisplay.get().setText("LOADING SAMPLE...");
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  final Sample s = AudioFileReader.readSample(file.getAbsolutePath());
                  if (s != null) {
                    javax.swing.SwingUtilities.invokeLater(
                        () -> {
                          sound.samples[oscSlot] = s;
                          sound.oscTypes[oscSlot] = org.deluge.firmware2.Oscillator.OscType.SAMPLE;
                          // Trigger audition
                          sound.triggerNote(60, 100);
                          FirmwareDisplay.get().setText("LOADED: " + name);
                        });
                  } else {
                    javax.swing.SwingUtilities.invokeLater(
                        () -> {
                          FirmwareDisplay.get().setText("ERR: NULL SAMPLE");
                        });
                  }
                } catch (Exception e) {
                  javax.swing.SwingUtilities.invokeLater(
                      () -> {
                        FirmwareDisplay.get().setText("ERR: " + e.getMessage());
                      });
                }
              });
      return ActionResult.DEALT_WITH;
    }
  }
}
