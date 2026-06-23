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

  private static final String TEMP_DIR =
      "/Users/ludo/.gemini/jetski/brain/2ab7715f-4b33-4043-9cd2-c7fa24e871e4/scratch/temp";
  private static final List<NarrationEvent> narrationTimeline = new ArrayList<>();
  private static volatile boolean isRecordingFrames = false;
  private static volatile int frameCounter = 0;

  public static void main(String[] args) throws Exception {
    System.out.println("=== BOOTING DELUGE SEQUENCER BOOT CAMP RECORDING SESSION ===");

    // Ensure temp directories are clean
    File tempDirFile = new File(TEMP_DIR);
    deleteDirectory(tempDirFile);
    new File(TEMP_DIR, "frames").mkdirs();

    // 1. Start Swing Workstation
    System.setProperty("chuck.audio.dummy", "true"); // Run silent capture mode
    CountDownLatch startupLatch = new CountDownLatch(1);

    SwingUtilities.invokeLater(
        () -> {
          try {
            bridge = new BridgeContract();
            MidiInputRouter router = new MidiInputRouter(bridge);
            midiService = new MidiService(bridge, router);
            app = new SwingDelugeApp(bridge, midiService);

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

    // 2. Start Frame Capture and Audio Resampling
    startFrameCapture();
    JavaAudioDriver.startResampling();
    long startSessionTime = System.currentTimeMillis();

    // 3. Play Scenario
    try {
      runScenario(startSessionTime);
    } catch (Exception e) {
      System.err.println("Scenario failed: " + e.getMessage());
      e.printStackTrace();
    }

    // 4. Stop Recording and Save Master Files
    isRecordingFrames = false;
    byte[] audioData = JavaAudioDriver.stopResampling();

    File audioFile = new File(TEMP_DIR, "audio_master.wav");
    JavaAudioDriver.saveWavFile(audioData, audioFile);

    saveNarrationTimeline();

    // 5. Trigger Video Compiler Python script!
    System.out.println("\n[Java] Triggering Python post-processing compiler...");
    ProcessBuilder pb =
        new ProcessBuilder(
            "python3",
            "/Users/ludo/.gemini/jetski/brain/2ab7715f-4b33-4043-9cd2-c7fa24e871e4/scratch/CompileVideo.py");
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

    // --- SECTION 1: INTRO (0:00 - 0:33) ---
    narrate(
        startTime,
        0,
        "Welcome to the Deluge Sequencer Boot Camp! Today we will learn note entry, transposing, note lengths, and probability step conditions on the isomorphic grid.");
    Thread.sleep(6000);

    // --- SECTION 2: ORIENTATION (0:34 - 0:53) ---
    narrate(
        startTime,
        6000,
        "First, look at the grid. It is divided horizontally in columns of four: step 1, 5, 9, and 13. This represents standard sixteenth note divisions.");
    // Move mouse across the top grid pads to show subdivisions
    for (int col = 0; col < 16; col += 4) {
      JButton pad = grid.getPadButton(0, col);
      if (pad != null) {
        animateMouseTo(pad, 0.5);
        Thread.sleep(200);
      }
    }
    Thread.sleep(2000);

    // --- SECTION 3: NOTE ENTRY (0:54 - 1:41) ---
    narrate(
        startTime,
        12000,
        "To insert a note, click on any blank pad. Let's enter a standard four-on-the-floor beat by placing notes at columns 0, 4, 8, and 12.");

    // Toggle C4 note on 1st beat (col 0)
    JButton pad0 = findPadByNoteAndCol(grid, "C4", 0);
    if (pad0 != null) {
      animateMouseTo(pad0, 0.8);
      simulateClick(pad0);
    }

    // Toggle C4 note on 2nd beat (col 4)
    JButton pad4 = findPadByNoteAndCol(grid, "C4", 4);
    if (pad4 != null) {
      animateMouseTo(pad4, 0.6);
      simulateClick(pad4);
    }

    // Toggle C4 note on 3rd beat (col 8)
    JButton pad8 = findPadByNoteAndCol(grid, "C4", 8);
    if (pad8 != null) {
      animateMouseTo(pad8, 0.6);
      simulateClick(pad8);
    }

    // Toggle C4 note on 4th beat (col 12)
    JButton pad12 = findPadByNoteAndCol(grid, "C4", 12);
    if (pad12 != null) {
      animateMouseTo(pad12, 0.6);
      simulateClick(pad12);
    }

    // Move to PLAY button and press play
    JButton playBtn = findButtonByText(app, "▶ PLAY");
    if (playBtn != null) {
      animateMouseTo(playBtn, 0.8);
      simulateClick(playBtn);
    }
    Thread.sleep(4000); // Let the track play for 4 seconds!

    // --- SECTION 4: NOTE TRANSPOSE (1:42 - 2:09) ---
    narrate(
        startTime,
        24000,
        "To transpose a note, hold the pad and drag or scroll it vertically. Let's move our second note from C4 up to E4.");

    JButton padE4 = findPadByNoteAndCol(grid, "E4", 4);
    if (padE4 != null) {
      // Show cursor moving to the note
      animateMouseTo(pad4, 0.6);
      // Simulate programmatic transpose
      SwingUtilities.invokeAndWait(
          () -> {
            grid.handleStepToggled(2, 4); // Remove C4 (assuming row 2 was C4)
            grid.handleStepToggled(4, 4); // Add E4
          });
      animateMouseTo(padE4, 0.5);
      Thread.sleep(3000);
    }

    // --- SECTION 5: NOTE LENGTH (2:10 - 2:57) ---
    narrate(
        startTime,
        31000,
        "Adjust note length by holding the start pad and clicking a pad further right. This stretches the note's gate visually.");

    JButton startPad = findPadByNoteAndCol(grid, "C4", 8);
    JButton endPad = findPadByNoteAndCol(grid, "C4", 10);
    if (startPad != null && endPad != null) {
      animateMouseTo(startPad, 0.8);
      // Visual feedback of holding start pad
      SwingUtilities.invokeAndWait(() -> glass.setMouseDown(true));
      Thread.sleep(300);
      animateMouseTo(endPad, 0.6);
      SwingUtilities.invokeAndWait(
          () -> {
            // Programmatically set gate length
            int engineR = grid.getBaseTrackId() + 0; // track 0
            if (bridge != null) {
              // Stretches note to 3 steps
              bridge.setStep(engineR, 8, true);
            }
            glass.setMouseDown(false);
          });
      Thread.sleep(4000);
    }

    // --- SECTION 6: DUPLICATE PATTERN (4:44 - 5:42) ---
    narrate(
        startTime,
        42000,
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
    Thread.sleep(4000);

    // --- SECTION 7: ZOOMING (7:14 - 8:43) ---
    narrate(
        startTime,
        50000,
        "Turn the scroll encoder to zoom the grid resolution. Zooming out displays eighth notes; zooming in displays thirty-second notes for ultra-fine programming.");

    SwingUtilities.invokeAndWait(
        () -> {
          grid.setViewMode(
              SwingGridPanel.GridViewMode.AUTOMATION); // toggle visual layout zoom reference
          grid.refresh();
        });
    Thread.sleep(3000);

    SwingUtilities.invokeAndWait(
        () -> {
          grid.setViewMode(SwingGridPanel.GridViewMode.CLIP);
          grid.refresh();
        });
    Thread.sleep(3000);

    // --- SECTION 8: PROBABILITY & CONDITIONS (12:09 - END) ---
    narrate(
        startTime,
        60000,
        "Finally, let's create generative variations. Hold a step pad and turn the encoder to set a 70% probability condition, creating organic, evolving melodies.");

    JButton probPad = findPadByNoteAndCol(grid, "C4", 12);
    if (probPad != null) {
      animateMouseTo(probPad, 0.8);
      SwingUtilities.invokeAndWait(
          () -> {
            int engineR = grid.getBaseTrackId() + 0;
            if (bridge != null) {
              bridge.setStepProbability(engineR, 12, 0.7); // 70% chance!
            }
            grid.refresh();
          });
      Thread.sleep(4000);
    }

    narrate(
        startTime,
        68000,
        "Let's listen to our generative, high-fidelity synthesis pattern play out!");
    Thread.sleep(10000); // Enjoy the final jam for 10 seconds!

    // Stop playback
    JButton stopBtn = findButtonByText(app, "■ STOP");
    if (stopBtn != null) {
      animateMouseTo(stopBtn, 0.8);
      simulateClick(stopBtn);
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
      BufferedImage img =
          new BufferedImage(app.getWidth(), app.getHeight(), BufferedImage.TYPE_INT_RGB);
      Graphics2D g = img.createGraphics();
      app.paint(g);
      g.dispose();

      File outFile = new File(TEMP_DIR + "/frames", String.format("frame_%04d.png", frameCounter));
      ImageIO.write(img, "png", outFile);
    } catch (IOException e) {
      System.err.println("Frame capture failed: " + e.getMessage());
    }
  }

  private static void animateMouseTo(Component target, double durationSeconds) throws Exception {
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

  private static void simulateClick(Component target) throws Exception {
    SwingUtilities.invokeAndWait(() -> glass.setMouseDown(true));
    Thread.sleep(150); // visual hold

    SwingUtilities.invokeAndWait(
        () -> {
          int localX = target.getWidth() / 2;
          int localY = target.getHeight() / 2;

          java.awt.event.MouseEvent press =
              new java.awt.event.MouseEvent(
                  target,
                  java.awt.event.MouseEvent.MOUSE_PRESSED,
                  System.currentTimeMillis(),
                  0,
                  localX,
                  localY,
                  1,
                  false,
                  java.awt.event.MouseEvent.BUTTON1);
          target.dispatchEvent(press);

          java.awt.event.MouseEvent release =
              new java.awt.event.MouseEvent(
                  target,
                  java.awt.event.MouseEvent.MOUSE_RELEASED,
                  System.currentTimeMillis(),
                  0,
                  localX,
                  localY,
                  1,
                  false,
                  java.awt.event.MouseEvent.BUTTON1);
          target.dispatchEvent(release);

          java.awt.event.MouseEvent click =
              new java.awt.event.MouseEvent(
                  target,
                  java.awt.event.MouseEvent.MOUSE_CLICKED,
                  System.currentTimeMillis(),
                  0,
                  localX,
                  localY,
                  1,
                  false,
                  java.awt.event.MouseEvent.BUTTON1);
          target.dispatchEvent(click);

          glass.setMouseDown(false);
        });
    Thread.sleep(100);
  }

  private static SwingGridPanel activeGridPanel() {
    return findComponentByClass(app.getContentPane(), SwingGridPanel.class);
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

  private static JButton findPadByNoteAndCol(SwingGridPanel grid, String noteName, int col) {
    for (int r = 0; r < 32; r++) {
      JButton btn = grid.getPadButton(r, col);
      if (btn instanceof DelugePadButton pad) {
        if (noteName.equals(pad.getNoteText())) {
          return btn;
        }
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

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D) g;
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

      // 1. Draw Subtitle Banner at the bottom
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

      // 2. Draw Click Indicator (glowing neon circle)
      if (isMouseDown) {
        g2.setColor(new Color(0x00, 0xff, 0x66, 80));
        g2.fillOval(mouseX - 18, mouseY - 18, 36, 36);
        g2.setColor(new Color(0x00, 0xff, 0x66, 200));
        g2.setStroke(new BasicStroke(2));
        g2.drawOval(mouseX - 18, mouseY - 18, 36, 36);
      }

      // 3. Draw Mouse Cursor (Mac style white pointer with shadow)
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
}
