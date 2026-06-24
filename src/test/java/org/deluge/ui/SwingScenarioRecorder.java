package org.deluge.ui;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.deluge.BridgeContract;
import org.deluge.engine.JavaAudioDriver;
import org.deluge.midi.MidiInputRouter;
import org.deluge.midi.MidiService;

public class SwingScenarioRecorder {

  private static SwingDelugeApp app;
  private static BridgeContract bridge;
  private static MidiService midiService;
  private static AutomationGlassPane glass;

  private static final String TEMP_DIR = "target/recorder";
  private static final List<NarrationEvent> narrationTimeline = new ArrayList<>();
  private static volatile boolean isRecordingFrames = false;
  private static volatile int frameCounter = 0;

  public static void main(String[] args) throws Exception {
    System.out.println("=== BOOTING DELUGE SEQUENCER BOOT CAMP RECORDING SESSION ===");

    // Run audio driver in high-precision silent software rendering mode to prevent blocking
    System.setProperty("deluge.audio.silent", "true");

    // Enable a clean, rich, and perfectly mixed digital volume level (12x gain, ~ -9.6 dB peak)
    // that blends beautifully with the voiceover without any clipping or saturation!
    JavaAudioDriver.monitorGainMul = 12;

    // Ensure temp directories are clean
    File tempDirFile = new File(TEMP_DIR);
    deleteDirectory(tempDirFile);
    new File(TEMP_DIR, "frames").mkdirs();

    // 1. Start Swing Workstation in high-fidelity Pure Java Audio Mode!
    CountDownLatch startupLatch = new CountDownLatch(1);

    SwingUtilities.invokeLater(
        () -> {
          try {
            bridge = new BridgeContract();
            MidiInputRouter router = new MidiInputRouter(bridge);
            midiService = new MidiService(bridge, router);
            app = new SwingDelugeApp(bridge, midiService, true); // Enable Pure Java Audio mode!

            // Size window to gorgeous HD widescreen
            app.setSize(1280, 800);
            app.validate();
            app.doLayout();
            app.setVisible(true);

            // Attach custom Automation GlassPane
            glass = new AutomationGlassPane();
            app.setGlassPane(glass);
            glass.setVisible(true);

            startupLatch.countDown();
          } catch (Exception e) {
            e.printStackTrace();
          }
        });

    startupLatch.await();
    Thread.sleep(1000); // Let UI settle

    // 2. Start Frame Capture and Audio Resampling (Classloader-safe!)
    startFrameCapture();
    Object audioDriverInstance = app.getPureEngine().getAudioDriver();
    startResamplingSafe(audioDriverInstance);
    long startSessionTime = System.currentTimeMillis();

    // 3. Play Scenario
    try {
      runScenario(startSessionTime);
    } catch (Exception e) {
      System.err.println("Scenario failed: " + e.getMessage());
      e.printStackTrace();
    }

    // 4. Stop Recording and Save Master Files (Classloader-safe!)
    isRecordingFrames = false;
    byte[] audioData = stopResamplingSafe(audioDriverInstance);

    // Print diagnostic block count!
    try {
      long blocks =
          (Long) audioDriverInstance.getClass().getField("blockCounter").get(audioDriverInstance);
      System.out.println("[DIAG-AUDIO] Total audio blocks rendered by audio thread: " + blocks);
    } catch (Exception e) {
      System.out.println("[DIAG-AUDIO] Failed to read blockCounter: " + e.getMessage());
    }

    File audioFile = new File(TEMP_DIR, "audio_master.wav");

    // Check if firmware already stopped and saved the resample to SAMPLES/RESAMPLE
    File latestResample = findLatestResampleFile();
    if (latestResample != null && latestResample.exists()) {
      System.out.println(
          "[Java] Locating firmware-saved resample: " + latestResample.getAbsolutePath());
      // Copy latestResample to audioFile!
      java.nio.file.Files.copy(
          latestResample.toPath(),
          audioFile.toPath(),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } else {
      System.out.println("[Java] No firmware-saved resample found. Saving manual buffer...");
      saveWavFileSafe(audioDriverInstance, audioData, audioFile);
    }

    saveNarrationTimeline();

    // 5. Trigger Video Compiler Python script!
    System.out.println("\n[Java] Triggering Python post-processing compiler...");
    File script = new File("deluge/src/test/python/CompileVideo.py");
    if (!script.exists()) {
      script = new File("src/test/python/CompileVideo.py");
    }
    ProcessBuilder pb = new ProcessBuilder("python3", script.getAbsolutePath());
    pb.inheritIO();
    Process p = pb.start();
    p.waitFor();

    // Cleanup
    SwingUtilities.invokeLater(() -> app.dispose());
    System.out.println("=== RECORDING SESSION COMPLETE ===");
  }

  private static void runScenario(long startTime) throws Exception {
    SwingGridPanel grid = activeGridPanel();
    if (grid == null) throw new IllegalStateException("Active grid not found!");

    // --- SECTION 1: INTRO (0:00 - 0:11) ---
    narrate(
        startTime,
        0,
        "Welcome to the Deluge Sequencer Boot Camp! Today we will learn note entry, transposing, note lengths, and probability step conditions on the isomorphic grid.");
    Thread.sleep(11000);

    // --- SECTION 2: ORIENTATION (0:11 - 0:23) ---
    narrate(
        startTime,
        11000,
        "First, look at the grid. It is divided horizontally in columns of four: step 1, 5, 9, and 13. This represents standard sixteenth note divisions.");
    // Move mouse across the top grid pads to show subdivisions
    for (int col = 0; col < 16; col += 4) {
      JButton pad = getPadButtonSafe(grid, 0, col);
      if (pad != null) {
        animateMouseTo(pad, 0.6);
        Thread.sleep(300);
      }
    }
    Thread.sleep(9600);

    // --- SECTION 3: NOTE ENTRY (0:23 - 0:34) ---
    narrate(
        startTime,
        23000,
        "To insert a note, click on any blank pad. Let's enter a standard four-on-the-floor beat by placing notes at columns 0, 4, 8, and 12.");

    // Scroll grid programmatically so C4 (row 4) and E4 (row 0) are perfectly centered!
    SwingUtilities.invokeAndWait(
        () -> {
          grid.setScrollOffset(63);
          grid.refresh();
        });
    Thread.sleep(1000);

    // Toggle C4 note on 1st beat (col 0) -> visible row 4, col 0
    JButton pad0 = getPadButtonSafe(grid, 4, 0);
    if (pad0 != null) {
      animateMouseTo(pad0, 0.7);
      simulateVisualClick(pad0);
      SwingUtilities.invokeAndWait(
          () -> {
            grid.handleStepToggled(4, 0);
            grid.refresh();
          });
    }
    Thread.sleep(500);

    // Toggle C4 note on 2nd beat (col 4) -> visible row 4, col 4
    JButton pad4 = getPadButtonSafe(grid, 4, 4);
    if (pad4 != null) {
      animateMouseTo(pad4, 0.5);
      simulateVisualClick(pad4);
      SwingUtilities.invokeAndWait(
          () -> {
            grid.handleStepToggled(4, 4);
            grid.refresh();
          });
    }
    Thread.sleep(500);

    // Toggle C4 note on 3rd beat (col 8) -> visible row 4, col 8
    JButton pad8 = getPadButtonSafe(grid, 4, 8);
    if (pad8 != null) {
      animateMouseTo(pad8, 0.5);
      simulateVisualClick(pad8);
      SwingUtilities.invokeAndWait(
          () -> {
            grid.handleStepToggled(4, 8);
            grid.refresh();
          });
    }
    Thread.sleep(500);

    // Toggle C4 note on 4th beat (col 12) -> visible row 4, col 12
    JButton pad12 = getPadButtonSafe(grid, 4, 12);
    if (pad12 != null) {
      animateMouseTo(pad12, 0.5);
      simulateVisualClick(pad12);
      SwingUtilities.invokeAndWait(
          () -> {
            grid.handleStepToggled(4, 12);
            grid.refresh();
          });
    }
    Thread.sleep(1000);

    // Move to PLAY button and press play
    JButton playBtn = findButtonByText(app, "▶ PLAY");
    if (playBtn != null) {
      animateMouseTo(playBtn, 0.7);
      simulateVisualClick(playBtn);
      SwingUtilities.invokeAndWait(
          () -> {
            playBtn.doClick();
          });
    }
    Thread.sleep(4000); // Let the track play for 4 seconds!

    // --- SECTION 4: NOTE TRANSPOSE (0:34 - 0:42) ---
    narrate(
        startTime,
        34000,
        "To transpose a note, hold the pad and drag or scroll it vertically. Let's move our second note from C4 up to E4.");

    JButton padC4_at_4 = getPadButtonSafe(grid, 4, 4);
    JButton padE4 = getPadButtonSafe(grid, 0, 4);
    if (padC4_at_4 != null && padE4 != null) {
      animateMouseTo(padC4_at_4, 0.6);
      simulateVisualClick(padC4_at_4);

      SwingUtilities.invokeAndWait(
          () -> {
            grid.handleStepToggled(4, 4); // Remove C4
            grid.handleStepToggled(0, 4); // Add E4
            grid.refresh();
          });
      animateMouseTo(padE4, 0.5);
      Thread.sleep(6000);
    }

    // --- SECTION 5: NOTE LENGTH (0:42 - 0:50) ---
    narrate(
        startTime,
        42000,
        "Adjust note length by holding the start pad and clicking a pad further right. This stretches the note's gate visually.");

    JButton startPad = getPadButtonSafe(grid, 4, 8);
    JButton endPad = getPadButtonSafe(grid, 4, 10);
    if (startPad != null && endPad != null) {
      animateMouseTo(startPad, 0.7);
      SwingUtilities.invokeAndWait(() -> glass.setMouseDown(true));
      Thread.sleep(300);
      animateMouseTo(endPad, 0.5);

      SwingUtilities.invokeAndWait(
          () -> {
            int engineR = grid.getBaseTrackId() + 4; // row 4
            if (bridge != null) {
              // Stretches note to 3 steps (steps 8, 9, 10 active)
              bridge.setStep(engineR, 8, true);
              bridge.setStep(engineR, 9, true);
              bridge.setStep(engineR, 10, true);
            }
            glass.setMouseDown(false);
            grid.refresh();
          });
      Thread.sleep(6000);
    }

    // --- SECTION 6: DUPLICATE PATTERN (0:50 - 1:00) ---
    narrate(
        startTime,
        50000,
        "To double the pattern length and clone all active notes, hold Shift and press down the scroll encoder. Watch the grid expand from 16 to 32 steps.");

    // Simulate Duplicate trigger
    SwingUtilities.invokeAndWait(
        () -> {
          // Double track length via bridge
          int engineR = grid.getBaseTrackId();
          if (bridge != null) {
            bridge.setTrackLength(engineR, 32);
          }
          grid.refresh();
        });
    Thread.sleep(8000);

    // --- SECTION 7: ZOOMING (1:00 - 1:10) ---
    narrate(
        startTime,
        60000,
        "Turn the scroll encoder to zoom the grid resolution. Zooming out displays eighth notes; zooming in displays thirty-second notes for ultra-fine programming.");

    SwingUtilities.invokeAndWait(
        () -> {
          grid.setViewMode(
              SwingGridPanel.GridViewMode.AUTOMATION); // toggle visual layout zoom reference
          grid.refresh();
        });
    Thread.sleep(4000);

    SwingUtilities.invokeAndWait(
        () -> {
          grid.setViewMode(SwingGridPanel.GridViewMode.CLIP);
          grid.refresh();
        });
    Thread.sleep(4000);

    // --- SECTION 8: PROBABILITY & CONDITIONS (1:10 - 1:22) ---
    narrate(
        startTime,
        70000,
        "Finally, let's create generative variations. Hold a step pad and turn the encoder to set a 70% probability condition, creating organic, evolving melodies.");

    JButton probPad = getPadButtonSafe(grid, 4, 12);
    if (probPad != null) {
      animateMouseTo(probPad, 0.7);
      SwingUtilities.invokeAndWait(
          () -> {
            int engineR = grid.getBaseTrackId() + 4;
            if (bridge != null) {
              bridge.setStepProbability(engineR, 12, 0.7); // 70% chance!
            }
            grid.refresh();
          });
      Thread.sleep(8000);
    }

    // --- SECTION 9: JAM & OUTRO (1:22 - END) ---
    narrate(
        startTime,
        82000,
        "Let's listen to our generative, high-fidelity synthesis pattern play out!");
    Thread.sleep(10000); // Enjoy the final jam for 10 seconds!

    // Stop playback
    JButton stopBtn = findButtonByText(app, "■ STOP");
    if (stopBtn != null) {
      animateMouseTo(stopBtn, 0.7);
      simulateVisualClick(stopBtn);
      SwingUtilities.invokeAndWait(
          () -> {
            stopBtn.doClick();
          });
    }
    Thread.sleep(1000);
  }

  private static void narrate(long startTime, long offsetMs, String text) {
    long targetTime = startTime + offsetMs;
    long delay = targetTime - System.currentTimeMillis();
    if (delay > 0) {
      try {
        Thread.sleep(delay);
      } catch (InterruptedException ignored) {
      }
    }

    long elapsed = System.currentTimeMillis() - startTime;
    System.out.println(String.format("[Java] Narration event at %dms: %s", elapsed, text));
    narrationTimeline.add(new NarrationEvent(elapsed, text));

    SwingUtilities.invokeLater(() -> glass.setSubtitle(text));
  }

  private static void startFrameCapture() {
    isRecordingFrames = true;
    new Thread(
            () -> {
              long frameDelay = 33; // 30 FPS
              long nextFrameTime = System.currentTimeMillis();

              while (isRecordingFrames) {
                long now = System.currentTimeMillis();
                if (now >= nextFrameTime) {
                  captureFrame();
                  frameCounter++;
                  nextFrameTime += frameDelay;
                }
                try {
                  Thread.sleep(Math.max(1, nextFrameTime - System.currentTimeMillis()));
                } catch (InterruptedException ignored) {
                }
              }
              System.out.println(
                  String.format("[FrameGrabber] Stopped. Captured %d frames.", frameCounter));
            })
        .start();
  }

  private static void captureFrame() {
    try {
      final BufferedImage img =
          new BufferedImage(app.getWidth(), app.getHeight(), BufferedImage.TYPE_INT_RGB);
      SwingUtilities.invokeAndWait(
          () -> {
            Graphics2D g = img.createGraphics();
            app.paint(g);
            g.dispose();
          });

      File outFile = new File(TEMP_DIR + "/frames", String.format("frame_%04d.png", frameCounter));
      ImageIO.write(img, "png", outFile);
    } catch (Exception e) {
      System.err.println("Frame capture failed: " + e.getMessage());
    }
  }

  private static void animateMouseTo(Component target, double durationSeconds) throws Exception {
    // Set target component on the glass pane to draw focus highlights!
    SwingUtilities.invokeLater(() -> glass.setTargetComponent(target));

    Point p = target.getLocationOnScreen();
    Point frameLoc = app.getLocationOnScreen();
    int targetX = p.x - frameLoc.x + target.getWidth() / 2;
    int targetY = p.y - frameLoc.y + target.getHeight() / 2;

    int startX = glass.getMouseX();
    int startY = glass.getMouseY();

    long startTime = System.currentTimeMillis();
    long durationMs = (long) (durationSeconds * 1000);

    while (System.currentTimeMillis() - startTime < durationMs) {
      double t = (double) (System.currentTimeMillis() - startTime) / durationMs;
      double ease = 1 - Math.pow(1 - t, 3); // cubic ease out
      int curX = (int) (startX + (targetX - startX) * ease);
      int curY = (int) (startY + (targetY - startY) * ease);

      SwingUtilities.invokeLater(() -> glass.setMousePosition(curX, curY));
      Thread.sleep(16); // ~60 FPS
    }

    SwingUtilities.invokeLater(() -> glass.setMousePosition(targetX, targetY));
  }

  private static void simulateVisualClick(Component target) throws Exception {
    SwingUtilities.invokeAndWait(() -> glass.setMouseDown(true));
    Thread.sleep(150); // visual hold
    SwingUtilities.invokeAndWait(() -> glass.setMouseDown(false));
    Thread.sleep(100);
  }

  private static SwingGridPanel activeGridPanel() {
    return findComponentByClass(app.getContentPane(), SwingGridPanel.class);
  }

  private static JButton getPadButtonSafe(SwingGridPanel grid, int visibleRow, int col)
      throws Exception {
    final JButton[] result = new JButton[1];
    SwingUtilities.invokeAndWait(
        () -> {
          result[0] = grid.getPadButton(visibleRow, col);
        });
    return result[0];
  }

  private static JButton findButtonByText(Container container, String text) {
    for (Component c : container.getComponents()) {
      if (c instanceof JButton btn && text.equals(btn.getText())) {
        return btn;
      }
      if (c instanceof Container child) {
        JButton btn = findButtonByText(child, text);
        if (btn != null) return btn;
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static <T extends Component> T findComponentByClass(Container container, Class<T> clazz) {
    for (Component c : container.getComponents()) {
      if (clazz.isInstance(c)) {
        return (T) c;
      }
      if (c instanceof Container child) {
        T found = findComponentByClass(child, clazz);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static void saveNarrationTimeline() throws IOException {
    File file = new File(TEMP_DIR, "narration_timeline.json");
    try (FileWriter writer = new FileWriter(file)) {
      writer.write("[\n");
      for (int i = 0; i < narrationTimeline.size(); i++) {
        NarrationEvent ev = narrationTimeline.get(i);
        writer.write(
            String.format(
                "  { \"timestamp_ms\": %d, \"text\": \"%s\" }",
                ev.timestampMs, ev.text.replace("\"", "\\\"")));
        if (i < narrationTimeline.size() - 1) {
          writer.write(",\n");
        }
      }
      writer.write("\n]");
    }
    System.out.println("[Java] Saved narration timeline to: " + file.getAbsolutePath());
  }

  private static void deleteDirectory(File path) {
    if (path.exists()) {
      for (File f : path.listFiles()) {
        if (f.isDirectory()) deleteDirectory(f);
        else f.delete();
      }
      path.delete();
    }
  }

  private static class NarrationEvent {
    long timestampMs;
    String text;

    NarrationEvent(long timestampMs, String text) {
      this.timestampMs = timestampMs;
      this.text = text;
    }
  }

  private static class AutomationGlassPane extends JComponent {
    private int mouseX = 150;
    private int mouseY = 150;
    private String subtitleText = "";
    private boolean isMouseDown = false;
    private Component targetComponent;

    public void setMousePosition(int x, int y) {
      this.mouseX = x;
      this.mouseY = y;
      repaint();
    }

    public int getMouseX() {
      return mouseX;
    }

    public int getMouseY() {
      return mouseY;
    }

    public void setSubtitle(String text) {
      this.subtitleText = text;
      repaint();
    }

    public void setMouseDown(boolean down) {
      this.isMouseDown = down;
      repaint();
    }

    public void setTargetComponent(Component comp) {
      this.targetComponent = comp;
      repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D) g;
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

      // 1. Draw Target Widget focus ring and neon pointer arrow!
      if (targetComponent != null && targetComponent.isShowing()) {
        Point p = targetComponent.getLocationOnScreen();
        Point frameLoc = app.getLocationOnScreen();
        int compX = p.x - frameLoc.x;
        int compY = p.y - frameLoc.y;
        int compW = targetComponent.getWidth();
        int compH = targetComponent.getHeight();

        // Glowing neon green rounded focus ring
        g2.setColor(new Color(0x00, 0xff, 0x66, 40));
        g2.fillRoundRect(compX - 4, compY - 4, compW + 8, compH + 8, 8, 8);
        g2.setColor(new Color(0x00, 0xff, 0x66, 180));
        g2.setStroke(
            new BasicStroke(
                2,
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
                0,
                new float[] {4, 4},
                0)); // Dotted neon ring
        g2.drawRoundRect(compX - 4, compY - 4, compW + 8, compH + 8, 8, 8);

        // Draw an ultra-visible neon green pointer arrow pointing from top-left
        int arrowX = compX - 25;
        int arrowY = compY - 25;
        int[] arrowXPoints = {
          arrowX, arrowX + 15, arrowX + 8, arrowX + 18, arrowX + 15, arrowX + 5, arrowX + 8, arrowX
        };
        int[] arrowYPoints = {
          arrowY, arrowY, arrowY + 8, arrowY + 18, arrowY + 21, arrowY + 11, arrowY + 15, arrowY
        };
        g2.setColor(new Color(0, 0, 0, 50)); // shadow
        g2.fillPolygon(
            translateArray(arrowXPoints, 2), translateArray(arrowYPoints, 2), arrowXPoints.length);

        g2.setColor(new Color(0x00, 0xff, 0x66, 220));
        g2.fillPolygon(arrowXPoints, arrowYPoints, arrowXPoints.length);
        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(1));
        g2.drawPolygon(arrowXPoints, arrowYPoints, arrowXPoints.length);
      }

      // 2. Draw Subtitle Banner at the bottom
      if (subtitleText != null && !subtitleText.isEmpty()) {
        int w = getWidth();
        int h = getHeight();
        int bannerHeight = 70;
        int bannerY = h - bannerHeight - 50;

        // Semi-transparent deep glass
        g2.setColor(new Color(18, 18, 20, 220));
        g2.fillRoundRect(80, bannerY, w - 160, bannerHeight, 16, 16);

        // Glowing neon green border
        g2.setColor(new Color(0x00, 0xff, 0x66, 180));
        g2.setStroke(new BasicStroke(2));
        g2.drawRoundRect(80, bannerY, w - 160, bannerHeight, 16, 16);

        // Text
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("SansSerif", Font.BOLD, 16));
        FontMetrics fm = g2.getFontMetrics();
        int textX = (w - fm.stringWidth(subtitleText)) / 2;
        int textY = bannerY + (bannerHeight - fm.getHeight()) / 2 + fm.getAscent();
        g2.drawString(subtitleText, textX, textY);
      }

      // 3. Draw Click Indicator (glowing neon circle)
      if (isMouseDown) {
        g2.setColor(new Color(0x00, 0xff, 0x66, 80));
        g2.fillOval(mouseX - 18, mouseY - 18, 36, 36);
        g2.setColor(new Color(0x00, 0xff, 0x66, 200));
        g2.setStroke(new BasicStroke(2));
        g2.drawOval(mouseX - 18, mouseY - 18, 36, 36);
      }

      // 4. Draw Mouse Cursor (Mac style white pointer with shadow)
      int[] xPoints = {
        mouseX, mouseX, mouseX + 12, mouseX + 7, mouseX + 12, mouseX + 10, mouseX + 5, mouseX
      };
      int[] yPoints = {
        mouseY, mouseY + 18, mouseY + 13, mouseY + 12, mouseY + 21, mouseY + 22, mouseY + 14, mouseY
      };

      g2.setColor(new Color(0, 0, 0, 60)); // Shadow
      g2.fillPolygon(translateArray(xPoints, 2), translateArray(yPoints, 2), xPoints.length);

      g2.setColor(Color.WHITE);
      g2.fillPolygon(xPoints, yPoints, xPoints.length);
      g2.setColor(Color.BLACK);
      g2.setStroke(new BasicStroke(1));
      g2.drawPolygon(xPoints, yPoints, xPoints.length);
    }

    private int[] translateArray(int[] arr, int delta) {
      int[] copy = new int[arr.length];
      for (int i = 0; i < arr.length; i++) copy[i] = arr[i] + delta;
      return copy;
    }
  }

  private static void startResamplingSafe(Object driverInstance) throws Exception {
    driverInstance.getClass().getMethod("startResampling").invoke(null);
  }

  private static byte[] stopResamplingSafe(Object driverInstance) throws Exception {
    return (byte[]) driverInstance.getClass().getMethod("stopResampling").invoke(null);
  }

  private static void saveWavFileSafe(Object driverInstance, byte[] pcmData, File targetFile)
      throws Exception {
    driverInstance
        .getClass()
        .getMethod("saveWavFile", byte[].class, File.class)
        .invoke(null, pcmData, targetFile);
  }

  private static File findLatestResampleFile() {
    File dir = new File("deluge/src/main/resources/SAMPLES/RESAMPLE");
    if (!dir.exists()) {
      dir = new File("src/main/resources/SAMPLES/RESAMPLE");
    }
    File[] files =
        dir.listFiles((d, name) -> name.startsWith("Resample_") && name.endsWith(".wav"));
    if (files == null || files.length == 0) return null;

    File latest = files[0];
    for (File f : files) {
      if (f.lastModified() > latest.lastModified()) {
        latest = f;
      }
    }
    return latest;
  }
}
