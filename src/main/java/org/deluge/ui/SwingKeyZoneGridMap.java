package org.deluge.ui;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.List;
import javax.swing.*;
import org.deluge.model.SynthTrackModel;

/**
 * A luxury, interactive horizontal grid and piano roll keyzone mapper. Displays white/black piano
 * keys across the top and color-coded keyzones below, supporting click-and-drag moving, resizing
 * (min/max pitch), and real-time auditioning.
 */
public class SwingKeyZoneGridMap extends JComponent {

  private final List<SynthTrackModel.KeyZone> zones;
  private SynthTrackModel.KeyZone selectedZone = null;

  private final int NOTE_WIDTH = 11; // 128 notes * 11px = 1408px width (perfect for JScrollPane)
  private final int PIANO_HEIGHT = 45;
  private final int ROW_HEIGHT = 35;

  // Interaction State
  private int dragStartX = -1;
  private int dragStartMinPitch = -1;
  private int dragStartMaxPitch = -1;
  private int dragMode = 0; // 0 = none, 1 = move, 2 = resize left, 3 = resize right
  private SynthTrackModel.KeyZone dragZone = null;

  private Runnable onSelectionChanged = null;
  private Runnable onZonesModified = null;

  public SwingKeyZoneGridMap(List<SynthTrackModel.KeyZone> zones) {
    this.zones = zones;

    setBackground(new Color(0x10, 0x10, 0x12));
    setDoubleBuffered(true);

    MouseAdapter mouseHandler =
        new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent e) {
            handleMousePressed(e);
          }

          @Override
          public void mouseReleased(MouseEvent e) {
            handleMouseReleased(e);
          }

          @Override
          public void mouseDragged(MouseEvent e) {
            handleMouseDragged(e);
          }

          @Override
          public void mouseMoved(MouseEvent e) {
            handleMouseMoved(e);
          }
        };

    addMouseListener(mouseHandler);
    addMouseMotionListener(mouseHandler);
  }

  public SynthTrackModel.KeyZone getSelectedZone() {
    return selectedZone;
  }

  public void setSelectedZone(SynthTrackModel.KeyZone zone) {
    this.selectedZone = zone;
    repaint();
  }

  public void setOnSelectionChanged(Runnable listener) {
    this.onSelectionChanged = listener;
  }

  public void setOnZonesModified(Runnable listener) {
    this.onZonesModified = listener;
  }

  @Override
  public Dimension getPreferredSize() {
    int height = PIANO_HEIGHT + Math.max(5, zones.size()) * ROW_HEIGHT + 20;
    return new Dimension(128 * NOTE_WIDTH, height);
  }

  private void handleMousePressed(MouseEvent e) {
    int x = e.getX();
    int y = e.getY();

    if (y < PIANO_HEIGHT) {
      // Clicked on piano keys: Audition note!
      int note = x / NOTE_WIDTH;
      auditionNote(note);
      return;
    }

    // Find which zone/row was clicked
    int row = (y - PIANO_HEIGHT) / ROW_HEIGHT;
    if (row >= 0 && row < zones.size()) {
      SynthTrackModel.KeyZone kz = zones.get(row);
      selectedZone = kz;
      if (onSelectionChanged != null) {
        onSelectionChanged.run();
      }

      // Check if clicked near boundaries for resizing or inside for moving
      int minX = kz.minPitch * NOTE_WIDTH;
      int maxX = (kz.maxPitch + 1) * NOTE_WIDTH;

      if (x >= minX && x <= maxX) {
        dragZone = kz;
        dragStartX = x;
        dragStartMinPitch = kz.minPitch;
        dragStartMaxPitch = kz.maxPitch;

        int edgeTolerance = 5;
        if (Math.abs(x - minX) <= edgeTolerance) {
          dragMode = 2; // Resize left
          setCursor(Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR));
        } else if (Math.abs(x - maxX) <= edgeTolerance) {
          dragMode = 3; // Resize right
          setCursor(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR));
        } else {
          dragMode = 1; // Move
          setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        }
      }
      repaint();
    }
  }

  private void handleMouseReleased(MouseEvent e) {
    dragZone = null;
    dragMode = 0;
    setCursor(Cursor.getDefaultCursor());
    if (onZonesModified != null) {
      onZonesModified.run();
    }
  }

  private void handleMouseDragged(MouseEvent e) {
    if (dragZone == null || dragMode == 0) return;

    int x = e.getX();
    int deltaX = x - dragStartX;
    int deltaNotes = deltaX / NOTE_WIDTH;

    if (dragMode == 1) {
      // Move entire block
      int length = dragStartMaxPitch - dragStartMinPitch;
      int newMin = Math.max(0, Math.min(127 - length, dragStartMinPitch + deltaNotes));
      dragZone.minPitch = newMin;
      dragZone.maxPitch = newMin + length;
    } else if (dragMode == 2) {
      // Resize left boundary (minPitch)
      int newMin = Math.max(0, Math.min(dragZone.maxPitch, dragStartMinPitch + deltaNotes));
      dragZone.minPitch = newMin;
    } else if (dragMode == 3) {
      // Resize right boundary (maxPitch)
      int newMax = Math.max(dragZone.minPitch, Math.min(127, dragStartMaxPitch + deltaNotes));
      dragZone.maxPitch = newMax;
    }

    repaint();
    if (onSelectionChanged != null) {
      onSelectionChanged.run(); // Update detail fields during drag!
    }
  }

  private void handleMouseMoved(MouseEvent e) {
    int x = e.getX();
    int y = e.getY();

    if (y < PIANO_HEIGHT) {
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      return;
    }

    int row = (y - PIANO_HEIGHT) / ROW_HEIGHT;
    if (row >= 0 && row < zones.size()) {
      SynthTrackModel.KeyZone kz = zones.get(row);
      int minX = kz.minPitch * NOTE_WIDTH;
      int maxX = (kz.maxPitch + 1) * NOTE_WIDTH;

      if (x >= minX && x <= maxX) {
        int edgeTolerance = 5;
        if (Math.abs(x - minX) <= edgeTolerance) {
          setCursor(Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR));
        } else if (Math.abs(x - maxX) <= edgeTolerance) {
          setCursor(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR));
        } else {
          setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        return;
      }
    }
    setCursor(Cursor.getDefaultCursor());
  }

  private void auditionNote(int midiNote) {
    // Dynamically play the note on the live sound engine for previewing!
    try {
      org.deluge.BridgeContract bridge = org.deluge.hid.BridgeHolder.getBridge();
      if (bridge != null) {
        Object eng = bridge.getGlobalObject(org.deluge.BridgeContract.G_FIRMWARE_ENGINE);
        if (eng instanceof org.deluge.engine.FirmwareAudioEngine engine) {
          // Play on first sound slot for auditioning
          if (!engine.sounds.isEmpty()) {
            var sound = engine.sounds.get(0);
            if (sound instanceof org.deluge.engine.FirmwareSound fs) {
              fs.triggerNote(midiNote, 100);
              // Auto-release after 400ms using virtual thread
              Thread.startVirtualThread(
                  () -> {
                    try {
                      Thread.sleep(400);
                      fs.releaseNote(midiNote);
                    } catch (InterruptedException ignored) {
                    }
                  });
            }
          }
        }
      }
    } catch (Exception ignored) {
    }
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g;
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int w = getWidth();
    int h = getHeight();

    // ── 1. Draw Grid Backdrop ──
    g2.setColor(getBackground());
    g2.fillRect(0, 0, w, h);

    // Vertical note grid lines
    g2.setColor(new Color(0x1c, 0x1c, 0x22));
    g2.setStroke(new BasicStroke(0.5f));
    for (int i = 0; i < 128; i++) {
      int x = i * NOTE_WIDTH;
      // Mark octaves (C notes: 0, 12, 24, 36, 48, 60, 72, 84, 96, 108, 120) with slightly brighter
      // lines
      if (i % 12 == 0) {
        g2.setColor(new Color(0x2d, 0x2d, 0x3a));
      } else {
        g2.setColor(new Color(0x18, 0x18, 0x1e));
      }
      g2.drawLine(x, 0, x, h);
    }

    // Horizontal row lines
    g2.setColor(new Color(0x1c, 0x1c, 0x22));
    for (int y = PIANO_HEIGHT; y < h; y += ROW_HEIGHT) {
      g2.drawLine(0, y, w, y);
    }

    // ── 2. Draw Color-Coded Keyzones ──
    for (int i = 0; i < zones.size(); i++) {
      SynthTrackModel.KeyZone kz = zones.get(i);
      int rowY = PIANO_HEIGHT + i * ROW_HEIGHT;

      int x = kz.minPitch * NOTE_WIDTH;
      int width = (kz.maxPitch - kz.minPitch + 1) * NOTE_WIDTH;

      boolean isSelected = (kz == selectedZone);

      // Generate beautiful gradient color based on row index (rotating neon palette)
      float hue = (float) i / Math.max(5, zones.size());
      Color baseColor = Color.getHSBColor(hue, 0.85f, 0.9f);
      Color darkColor = Color.getHSBColor(hue, 0.95f, 0.4f);

      // Neon glowing border & fill
      if (isSelected) {
        g2.setColor(new Color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), 65));
      } else {
        g2.setColor(new Color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), 35));
      }
      g2.fillRoundRect(x + 2, rowY + 4, width - 4, ROW_HEIGHT - 8, 6, 6);

      // Highlight Border
      if (isSelected) {
        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(1.5f));
      } else {
        g2.setColor(baseColor);
        g2.setStroke(new BasicStroke(1.0f));
      }
      g2.drawRoundRect(x + 2, rowY + 4, width - 4, ROW_HEIGHT - 8, 6, 6);

      // Text Label (Filename)
      g2.setColor(Color.WHITE);
      g2.setFont(new Font("SansSerif", Font.BOLD, 9));
      String fileName =
          (kz.samplePath != null && !kz.samplePath.isEmpty())
              ? new File(kz.samplePath).getName()
              : "Empty Zone";
      g2.drawString(fileName, x + 8, rowY + ROW_HEIGHT / 2 + 3);

      // Draw range borders indicators in selective color
      g2.setFont(new Font("Monospaced", Font.PLAIN, 8));
      g2.setColor(new Color(255, 255, 255, 120));
      g2.drawString(String.valueOf(kz.minPitch), x + 3, rowY + 13);
      g2.drawString(String.valueOf(kz.maxPitch), x + width - 15, rowY + 13);
    }

    // ── 3. Draw Piano Keyboard Header ──
    g2.setColor(new Color(0x14, 0x14, 0x16));
    g2.fillRect(0, 0, w, PIANO_HEIGHT);

    // Draw White Keys
    g2.setColor(Color.WHITE);
    g2.setStroke(new BasicStroke(0.5f));
    for (int i = 0; i < 128; i++) {
      if (!isBlackKey(i)) {
        int x = i * NOTE_WIDTH;
        g2.fillRect(x, 0, NOTE_WIDTH - 1, PIANO_HEIGHT);
      }
    }

    // Draw Black Keys (overlayed on top)
    g2.setColor(new Color(0x28, 0x28, 0x2c));
    for (int i = 0; i < 128; i++) {
      if (isBlackKey(i)) {
        int x = i * NOTE_WIDTH;
        g2.fillRect(x, 0, NOTE_WIDTH - 1, (int) (PIANO_HEIGHT * 0.6));
      }
    }

    // Draw bottom boundary line of the piano keyboard
    g2.setColor(new Color(0x2d, 0x2d, 0x32));
    g2.drawLine(0, PIANO_HEIGHT, w, PIANO_HEIGHT);
  }

  private boolean isBlackKey(int midiNote) {
    int noteInOctave = midiNote % 12;
    return noteInOctave == 1
        || noteInOctave == 3
        || noteInOctave == 6
        || noteInOctave == 8
        || noteInOctave == 10;
  }
}
