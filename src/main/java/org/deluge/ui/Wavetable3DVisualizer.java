package org.deluge.ui;

import java.awt.*;
import java.awt.geom.Path2D;
import java.io.File;
import java.io.IOException;
import javax.swing.*;
import org.deluge.BridgeContract;
import org.deluge.firmware2.WaveTable;
import org.deluge.firmware2.WaveTableBand;
import org.deluge.firmware2.WaveTableReader;
import org.deluge.model.SynthTrackModel;

/**
 * A stunning, vector-based 3D Wavetable Scanner & Morphing Visualizer. Renders the stacked
 * single-cycle waveforms of a loaded wavetable file using a perspective projection, displaying a
 * glowing neon playhead plane tracking the real-time waveIndex position.
 */
public class Wavetable3DVisualizer extends JPanel {

  private final SynthTrackModel model;
  private final int oscIndex; // 0 = Osc A, 1 = Osc B
  private final int trackIndex;

  private WaveTable loadedWavetable = null;
  private String lastLoadedPath = null;
  private double animPhase = 0.0;
  private Timer animationTimer;

  public Wavetable3DVisualizer(SynthTrackModel model, int oscIndex, int trackIndex) {
    this.model = model;
    this.oscIndex = oscIndex;
    this.trackIndex = trackIndex;

    setBackground(new Color(0x10, 0x10, 0x12));
    setBorder(BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32), 1));
    setPreferredSize(new Dimension(300, 200));

    // 30 FPS animation timer to smoothly track LFO modulations and dragging
    animationTimer =
        new Timer(
            33,
            e -> {
              checkWavetableReload();
              if (isShowing()) {
                animPhase += 0.04;
                repaint();
              }
            });

    setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    setToolTipText("Double-click or right-click to open Wavetable Editor Laboratory");

    addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mouseClicked(java.awt.event.MouseEvent e) {
            if (e.getClickCount() == 2 && javax.swing.SwingUtilities.isLeftMouseButton(e)) {
              openEditorDialog();
            }
          }

          @Override
          public void mousePressed(java.awt.event.MouseEvent e) {
            if (e.isPopupTrigger()) {
              showPopupMenu(e);
            }
          }

          @Override
          public void mouseReleased(java.awt.event.MouseEvent e) {
            if (e.isPopupTrigger()) {
              showPopupMenu(e);
            }
          }
        });
  }

  public void startAnimation() {
    if (animationTimer != null && !animationTimer.isRunning()) {
      animationTimer.start();
    }
  }

  public void stopAnimation() {
    if (animationTimer != null && animationTimer.isRunning()) {
      animationTimer.stop();
    }
  }

  private void checkWavetableReload() {
    String path = (oscIndex == 0) ? model.getOsc1SamplePath() : model.getOsc2SamplePath();
    boolean isWavetableMode =
        (oscIndex == 0)
            ? "WAVETABLE".equalsIgnoreCase(model.getOsc1Type())
            : "WAVETABLE".equalsIgnoreCase(model.getOsc2Type());

    if (!isWavetableMode) {
      loadedWavetable = null;
      lastLoadedPath = null;
      return;
    }

    if (path == null || path.isBlank()) {
      loadedWavetable = null;
      lastLoadedPath = null;
      return;
    }

    if (path.equals(lastLoadedPath)) {
      return; // Already loaded
    }

    lastLoadedPath = path;
    File f = new File(path);
    if (!f.exists() || !f.isFile()) {
      loadedWavetable = null;
      return;
    }

    // Load in a background thread to prevent GUI hiccups
    new Thread(
            () -> {
              try {
                WaveTable wt = new WaveTable();
                WaveTableReader.readWavetable(wt, f.getAbsolutePath());
                SwingUtilities.invokeLater(
                    () -> {
                      // Verify path hasn't changed in the meantime
                      if (f.getAbsolutePath().equals(lastLoadedPath)) {
                        this.loadedWavetable = wt;
                        repaint();
                      }
                    });
              } catch (IOException e) {
                System.err.println("[Wavetable3D] Failed to read wavetable: " + e.getMessage());
                SwingUtilities.invokeLater(() -> this.loadedWavetable = null);
              }
            })
        .start();
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g;
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int w = getWidth();
    int h = getHeight();

    // ── 1. If not in Wavetable mode or no file loaded, draw beautiful placeholder grid ──
    if (loadedWavetable == null
        || loadedWavetable.bands.isEmpty()
        || loadedWavetable.numCycles <= 0) {
      drawPlaceholder(g2, w, h);
      return;
    }

    WaveTableBand band = loadedWavetable.bands.get(0); // Use full-bandwidth band
    short[] data = band.data;
    int cycleSize = band.cycleSizeNoDuplicates;
    int numCycles = loadedWavetable.numCycles;

    if (cycleSize <= 0 || data == null || data.length == 0) {
      drawPlaceholder(g2, w, h);
      return;
    }

    // ── 2. Perspective Layout Constants ──
    int centerX = w / 2;
    int centerY = h / 2 - 10;

    // Depth settings
    double depthSpread = 130.0; // Pixels Z-axis extends back
    double angleY = 0.35; // Vertical camera angle skew

    // Max cycles to draw to avoid clutter (subsample if wavetable has e.g. 64 or 128 cycles)
    int maxDrawCycles = 32;
    int cycleStep = Math.max(1, numCycles / maxDrawCycles);

    // Current playhead wave index position (0.0 to 1.0)
    float waveIndex = model.getWaveIndex();
    int playheadCycleIdx = Math.round(waveIndex * (numCycles - 1));

    // ── 3. Draw Stacked Curves from Back to Front (Painter's Algorithm) ──
    for (int i = numCycles - 1; i >= 0; i -= cycleStep) {
      // Normalize depth (0.0 at front, 1.0 at back)
      double depthNorm = (double) i / (numCycles - 1);

      // Calculate screen projection coordinates for this cycle
      Path2D.Double path = new Path2D.Double();
      int cycleStartIndex =
          i * (cycleSize + WaveTable.WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE);

      boolean first = true;
      for (int s = 0; s < cycleSize; s++) {
        if (cycleStartIndex + s >= data.length) break;

        // Get normalized coordinates (-1.0 to 1.0)
        double normX = -1.0 + 2.0 * s / (cycleSize - 1);
        double normY = (double) data[cycleStartIndex + s] / 32768.0;

        // Apply 3D perspective projection
        // Z moves from 1.0 (back) to 0.0 (front)
        double z = 1.0 - depthNorm;
        double scale = 0.55 + 0.45 * z; // Perspective scale factor

        double projX = centerX + normX * (w * 0.35) * scale;
        double projY = centerY - normY * (h * 0.22) * scale + (depthNorm - 0.5) * depthSpread;

        if (first) {
          path.moveTo(projX, projY);
          first = false;
        } else {
          path.lineTo(projX, projY);
        }
      }

      // Determine curve styling based on proximity and playhead selection
      boolean isPlayhead = (Math.abs(i - playheadCycleIdx) < cycleStep);
      if (isPlayhead) {
        // Glowing bold white for the active morphed cycle
        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(2.0f));
      } else {
        // Neon color gradient: Theme secondary accent at front, fading to deep Indigo at back
        Color frontColor = ThemeManager.getSecondaryAccent();
        Color backColor = new Color(100, 0, 180); // deep cyberpunk indigo

        int r = (int) (backColor.getRed() * depthNorm + frontColor.getRed() * (1 - depthNorm));
        int gr = (int) (backColor.getGreen() * depthNorm + frontColor.getGreen() * (1 - depthNorm));
        int b = (int) (backColor.getBlue() * depthNorm + frontColor.getBlue() * (1 - depthNorm));
        int alpha = (int) (60 + 155 * (1 - depthNorm)); // fade transparency in distance

        g2.setColor(new Color(r, gr, b, alpha));
        g2.setStroke(new BasicStroke(1.0f));
      }

      g2.draw(path);
    }

    // ── 4. Draw Glowing Playhead Plane ──
    double playheadDepthNorm = (double) playheadCycleIdx / (numCycles - 1);
    double pZ = 1.0 - playheadDepthNorm;
    double pScale = 0.55 + 0.45 * pZ;

    // Calculate boundaries of the playhead plane cutting across the screen
    double leftX = centerX - 1.05 * (w * 0.35) * pScale;
    double rightX = centerX + 1.05 * (w * 0.35) * pScale;
    double planeY = centerY + (playheadDepthNorm - 0.5) * depthSpread;

    // Draw a semi-transparent neon plane cutting through the stack
    Color primaryAccent = ThemeManager.getPrimaryAccent();
    Color fillClr =
        new Color(primaryAccent.getRed(), primaryAccent.getGreen(), primaryAccent.getBlue(), 20);
    Color borderClr =
        new Color(primaryAccent.getRed(), primaryAccent.getGreen(), primaryAccent.getBlue(), 160);

    g2.setColor(fillClr); // ultra-subtle fill
    g2.fillRect((int) leftX, (int) (planeY - 20), (int) (rightX - leftX), 40);

    g2.setColor(borderClr); // glowing border line
    g2.setStroke(
        new BasicStroke(
            1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1.0f, new float[] {4, 4}, 0.0f));
    g2.drawLine((int) leftX, (int) planeY, (int) rightX, (int) planeY);

    // Draw label index text in primary accent next to the plane
    g2.setColor(primaryAccent);
    g2.setFont(new Font("Monospaced", Font.BOLD, 9));
    g2.drawString(
        String.format("IDX: %.2f (CYC %d/%d)", waveIndex, playheadCycleIdx + 1, numCycles),
        10,
        h - 10);
  }

  private void drawPlaceholder(Graphics2D g2, int w, int h) {
    g2.setColor(new Color(0x11, 0x11, 0x13));
    g2.fillRect(0, 0, w, h);

    int centerX = w / 2;
    int centerY = h / 2 - 5;
    int gridLines = 10;
    double depthSpread = 70.0;

    g2.setStroke(new BasicStroke(1.0f));
    for (int i = gridLines - 1; i >= 0; i--) {
      double normZ = (double) i / (gridLines - 1);
      double scale = 0.55 + 0.45 * (1.0 - normZ);

      Path2D.Double path = new Path2D.Double();
      boolean first = true;
      for (int x = 0; x < 35; x++) {
        double normX = -1.0 + 2.0 * x / 34;

        double angle = normX * Math.PI * 2.0 + animPhase + normZ * Math.PI * 0.8;
        double normY = 0.22 * Math.sin(angle) * Math.cos(animPhase * 0.4);

        double projX = centerX + normX * (w * 0.38) * scale;
        double projY = centerY - normY * (h * 0.28) * scale + (normZ - 0.5) * depthSpread;

        if (first) {
          path.moveTo(projX, projY);
          first = false;
        } else {
          path.lineTo(projX, projY);
        }
      }

      Color c = ThemeManager.getPrimaryAccent();
      int alpha = (int) (15 + 85 * (1.0 - normZ));
      g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha));
      g2.draw(path);
    }

    g2.setColor(new Color(255, 255, 255, 180));
    g2.setFont(new Font("SansSerif", Font.BOLD, 10));
    String msg = "📁 LOAD A WAVETABLE (.WAV) TO SCAN IN 3D";
    int msgW = g2.getFontMetrics().stringWidth(msg);
    g2.drawString(msg, (w - msgW) / 2, h / 2 - 8);

    g2.setFont(new Font("SansSerif", Font.PLAIN, 9));
    g2.setColor(new Color(0x60, 0x60, 0x68));
    String sub = "Ensure Osc Type is set to WAVETABLE to preview morphing";
    int subW = g2.getFontMetrics().stringWidth(sub);
    g2.drawString(sub, (w - subW) / 2, h / 2 + 10);
  }

  private void openEditorDialog() {
    Window owner = getParentWindow();
    BridgeContract bridge = org.deluge.hid.BridgeHolder.getBridge();
    if (bridge != null) {
      SwingSynthWavetableEditorDialog dialog =
          new SwingSynthWavetableEditorDialog(owner, model, oscIndex, trackIndex, bridge);
      dialog.setVisible(true);
    }
  }

  private void showPopupMenu(java.awt.event.MouseEvent e) {
    JPopupMenu menu = new JPopupMenu();
    JMenuItem editItem = new JMenuItem("🔬 Open 3D Wavetable Laboratory & Editor...");
    editItem.addActionListener(al -> openEditorDialog());
    menu.add(editItem);

    SwingGridPanel.stylePopupMenu(menu);
    editItem.setForeground(new Color(0x00, 0xff, 0xcc));

    menu.show(this, e.getX(), e.getY());
  }

  private Window getParentWindow() {
    Container parent = getParent();
    while (parent != null && !(parent instanceof Window)) {
      parent = parent.getParent();
    }
    return (Window) parent;
  }
}
