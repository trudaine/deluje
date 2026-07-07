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

  private void showSystemOledMenu(int x, int y) {
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

    menu.show(this, x, y);
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
