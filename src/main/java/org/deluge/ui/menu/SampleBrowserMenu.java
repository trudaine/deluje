package org.deluge.ui.menu;

import java.io.File;
import org.deluge.engine.FirmwareSound;
import org.deluge.firmware.hid.ActionResult;
import org.deluge.firmware.hid.FirmwareDisplay;
import org.deluge.playback.Sample;
import org.deluge.project.PreferencesManager;
import org.deluge.storage.audio.AudioFileReader;

/** A specialized menu for browsing and loading samples into a sound slot. */
public class SampleBrowserMenu extends Submenu {
  private final FirmwareSound sound;
  private final int oscSlot;
  private final File currentDir;

  public SampleBrowserMenu(String name, FirmwareSound sound, int oscSlot) {
    this(name, sound, oscSlot, PreferencesManager.getLibraryDir());
  }

  public SampleBrowserMenu(String name, FirmwareSound sound, int oscSlot, File dir) {
    super(name);
    this.sound = sound;
    this.oscSlot = oscSlot;
    this.currentDir = dir;
    populate();
  }

  private void populate() {
    if (currentDir == null || !currentDir.exists()) return;

    File[] files = currentDir.listFiles();
    if (files == null) return;

    // Folders first
    for (File f : files) {
      if (f.isDirectory()) {
        this.addItem(new SampleBrowserMenu(f.getName(), sound, oscSlot, f));
      }
    }

    // Then WAV files
    for (File f : files) {
      if (f.isFile() && f.getName().toLowerCase().endsWith(".wav")) {
        this.addItem(new SampleFileMenuItem(f, sound, oscSlot));
      }
    }
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
      // Audition and Load
      try {
        Sample s = AudioFileReader.readSample(file.getAbsolutePath());
        if (s != null) {
          sound.samples[oscSlot] = s;
          sound.oscTypes[oscSlot] = org.deluge.firmware2.Oscillator.OscType.SAMPLE;
          // Trigger audition
          sound.triggerNote(60, 100);
          FirmwareDisplay.get().setText("LOADED: " + name);
        }
      } catch (Exception e) {
        FirmwareDisplay.get().setText("ERR: " + e.getMessage());
      }
      return ActionResult.DEALT_WITH;
    }
  }
}
