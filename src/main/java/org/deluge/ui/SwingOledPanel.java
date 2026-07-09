package org.deluge.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import javax.swing.JPanel;
import org.deluge.hid.FirmwareDisplay;
import org.deluge.hid.VirtualOLED;

/**
 * Swing component that renders the high-fidelity VirtualOLED display. Emulates the look of the
 * hardware's 128x64 white-on-black OLED.
 */
public class SwingOledPanel extends JPanel {
  private final VirtualOLED virtualOLED;

  public SwingOledPanel() {
    this.virtualOLED = FirmwareDisplay.get().getVirtualOLED();
    setBackground(Color.BLACK);
    setPreferredSize(new Dimension(128, 48));
    setMinimumSize(new Dimension(128, 48));
    setMaximumSize(new Dimension(128, 48));

    FirmwareDisplay.get().setOledListener(this::repaint);
    setToolTipText("Right-click or double-click to open Deluge Tools & System Settings");

    addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mousePressed(java.awt.event.MouseEvent e) {
            if (javax.swing.SwingUtilities.isRightMouseButton(e) || e.getClickCount() == 2) {
              showSystemOledMenu(e.getX(), e.getY());
            }
          }
        });
  }

  private javax.swing.JPopupMenu showSystemOledMenu(int x, int y) {
    javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();

    javax.swing.JMenuItem newItem = new javax.swing.JMenuItem("New Project");
    newItem.addActionListener(
        e -> {
          if (SwingDelugeApp.mainInstance != null) SwingDelugeApp.mainInstance.launchNewProject();
        });
    menu.add(newItem);

    javax.swing.JMenuItem exportItem = new javax.swing.JMenuItem("Export Audio / WAV...");
    exportItem.addActionListener(
        e -> {
          if (SwingDelugeApp.mainInstance != null) SwingDelugeApp.mainInstance.launchExportAudio();
        });
    menu.add(exportItem);

    javax.swing.JMenuItem importMidiItem = new javax.swing.JMenuItem("Import MIDI File...");
    importMidiItem.addActionListener(
        e -> {
          if (SwingDelugeApp.mainInstance != null) SwingDelugeApp.mainInstance.launchImportMidi();
        });
    menu.add(importMidiItem);

    menu.addSeparator();

    javax.swing.JMenuItem droneItem = new javax.swing.JMenuItem("Drone Lab...");
    droneItem.addActionListener(
        e -> {
          if (SwingDelugeApp.mainInstance != null) SwingDelugeApp.mainInstance.launchDroneLab();
        });
    menu.add(droneItem);

    javax.swing.JMenuItem pianoRollItem = new javax.swing.JMenuItem("Piano Roll Editor...");
    pianoRollItem.addActionListener(
        e -> {
          if (SwingDelugeApp.mainInstance != null) SwingDelugeApp.mainInstance.launchPianoRoll();
        });
    menu.add(pianoRollItem);

    javax.swing.JMenuItem randItem = new javax.swing.JMenuItem("Step / Note Randomizer...");
    randItem.addActionListener(
        e -> {
          if (SwingDelugeApp.mainInstance != null) SwingDelugeApp.mainInstance.launchRandomizer();
        });
    menu.add(randItem);

    javax.swing.JMenuItem slicerItem = new javax.swing.JMenuItem("Audio Sample Slicer...");
    slicerItem.addActionListener(
        e -> {
          if (SwingDelugeApp.mainInstance != null) SwingDelugeApp.mainInstance.launchAudioSlicer();
        });
    menu.add(slicerItem);

    javax.swing.JMenuItem wtItem = new javax.swing.JMenuItem("Wavetable Editor & 3D Terrain...");
    wtItem.addActionListener(
        e -> {
          if (SwingDelugeApp.mainInstance != null)
            SwingDelugeApp.mainInstance.launchWavetableEditor();
        });
    menu.add(wtItem);

    javax.swing.JMenuItem tuningItem = new javax.swing.JMenuItem("Microtonal Tuning Editor...");
    tuningItem.addActionListener(
        e -> {
          if (SwingDelugeApp.mainInstance != null) SwingDelugeApp.mainInstance.launchTuning();
        });
    menu.add(tuningItem);

    javax.swing.JMenuItem fxItem = new javax.swing.JMenuItem("Master FX Deck...");
    fxItem.addActionListener(
        e -> {
          if (SwingDelugeApp.mainInstance != null)
            SwingDelugeApp.mainInstance.launchMasterFxDialog();
        });
    menu.add(fxItem);

    javax.swing.JMenuItem stutterItem =
        new javax.swing.JMenuItem("Track Stutter Modes (Quantize / Reverse / Ping-Pong)...");
    stutterItem.addActionListener(
        e -> {
          if (SwingDelugeApp.mainInstance != null)
            SwingDelugeApp.mainInstance.launchStutterConfig();
        });
    menu.add(stutterItem);

    javax.swing.JMenuItem stutterLatchItem =
        new javax.swing.JMenuItem("Toggle Latched Stutter Loop (Shift + Q)");
    stutterLatchItem.addActionListener(
        e -> {
          if (SwingDelugeApp.mainInstance != null
              && SwingDelugeApp.mainInstance.transportController != null) {
            SwingDelugeApp.mainInstance.transportController.toggleStutterLatched();
          }
        });
    menu.add(stutterLatchItem);

    menu.addSeparator();

    javax.swing.JMenuItem prefsItem = new javax.swing.JMenuItem("Preferences & UI Style...");
    prefsItem.addActionListener(
        e -> {
          if (SwingDelugeApp.mainInstance != null) SwingDelugeApp.mainInstance.launchPreferences();
        });
    menu.add(prefsItem);

    javax.swing.JMenuItem helpItem = new javax.swing.JMenuItem("Help & User Manual...");
    helpItem.addActionListener(
        e -> {
          if (SwingDelugeApp.mainInstance != null) SwingDelugeApp.mainInstance.launchHelp();
        });
    menu.add(helpItem);

    menu.addSeparator();

    // Real hardware has no per-row text label/column in the grid (just pads), so these track-level
    // actions -- previously reachable only via a now-removed grid-row label -- live here instead,
    // scoped to whichever track is currently open in the Clip editor.
    javax.swing.JMenuItem trackMenuItem =
        new javax.swing.JMenuItem("Current Track: Rename / Color / Inspector / Move...");
    trackMenuItem.addActionListener(
        e -> {
          if (SwingDelugeApp.mainInstance != null) {
            SwingGridPanel clip = SwingDelugeApp.mainInstance.getClipPanel();
            if (clip != null) {
              clip.showTrackContextMenu(this, x, y, clip.editedModelTrack);
            }
          }
        });
    menu.add(trackMenuItem);

    javax.swing.JMenuItem oneShotItem =
        new javax.swing.JMenuItem("Toggle One-Shot Mode (current track)");
    oneShotItem.addActionListener(
        e -> {
          if (SwingDelugeApp.mainInstance != null) {
            SwingGridPanel clip = SwingDelugeApp.mainInstance.getClipPanel();
            if (clip != null
                && clip.editedModelTrack >= 0
                && clip.editedModelTrack < clip.isOneShotTrack.length) {
              clip.isOneShotTrack[clip.editedModelTrack] =
                  !clip.isOneShotTrack[clip.editedModelTrack];
            }
          }
        });
    menu.add(oneShotItem);

    javax.swing.JMenuItem hotSwapItem =
        new javax.swing.JMenuItem("Hot-Swap Sample (current track)...");
    hotSwapItem.addActionListener(
        e -> {
          if (SwingDelugeApp.mainInstance == null) return;
          SwingGridPanel clip = SwingDelugeApp.mainInstance.getClipPanel();
          if (clip == null) return;
          javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
          chooser.setFileFilter(
              new javax.swing.filechooser.FileNameExtensionFilter(
                  "WAV / AIFF samples", "wav", "aif", "aiff"));
          if (chooser.showOpenDialog(this) == javax.swing.JFileChooser.APPROVE_OPTION) {
            clip.hotSwapTrackSample(clip.editedModelTrack, 0, chooser.getSelectedFile());
          }
        });
    menu.add(hotSwapItem);

    menu.show(this, x, y);
    return menu;
  }

  public void drawRawFrameBuffer(byte[] frameBuffer) {
    virtualOLED.drawRawFrameBuffer(frameBuffer);
    repaint();
  }

  public void showParamText(String banner, String val) {
    virtualOLED.drawTrackScreen(banner, val, "");
    repaint();
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2d = (Graphics2D) g;

    // Scale up for visibility
    g2d.drawImage(virtualOLED.getImage(), 0, 0, getWidth(), getHeight(), null);

    // Draw a slight scanline/pixel effect for authenticity
    g2d.setColor(new Color(0, 0, 0, 50));
    for (int y = 0; y < getHeight(); y += 2) {
      g2d.drawLine(0, y, getWidth(), y);
    }
  }
}
