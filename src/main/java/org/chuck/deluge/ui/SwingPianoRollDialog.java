package org.chuck.deluge.ui;

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sound.midi.*;
import javax.swing.*;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.StepData;
import org.chuck.deluge.model.TrackModel;
import org.chuck.deluge.model.TrackType;

/**
 * A high-fidelity, pop-out standalone Piano Roll Sequencer Dialog. Provides DAW-grade visual note
 * gate editing, micro-timing nudge dragging, a synchronized velocity stalk editor lane, acoustic
 * note previews, and a built-in standard MIDI file importer.
 */
public class SwingPianoRollDialog extends JDialog {

  private static final Color BG_DARK = new Color(0x11, 0x11, 0x13);
  private static final Color BG_GRID = new Color(0x17, 0x17, 0x1a);
  private static final Color BG_KEY_BLACK = new Color(0x15, 0x15, 0x18);
  private static final Color BG_KEY_WHITE = new Color(0x28, 0x28, 0x2d);
  private static final Color COLOR_BORDER = new Color(0x2d, 0x2d, 0x32);
  private static final Color COLOR_GRID_LINE = new Color(0x22, 0x22, 0x26);
  private static final Color COLOR_BEAT_LINE = new Color(0x36, 0x36, 0x3d);

  private final SwingGridPanel gridPanel;
  private final int trackIndex;
  private final int clipIndex;
  private final ProjectModel projectModel;
  private final BridgeContract bridge;

  private TrackModel trackModel;
  private ClipModel clipModel;
  private int baseTrackId;

  private int stepWidth = 45;
  private final int rowHeight = 22;
  private final int keyboardWidth = 75;

  private JScrollPane gridScrollPane;
  private JScrollPane velocityScrollPane;
  private PianoRollCanvas canvas;
  private VelocityLanePanel velocityLane;
  private PianoKeyboardPanel keyboardPanel;

  private int activePreviewNote = -1;

  public SwingPianoRollDialog(
      Frame owner,
      SwingGridPanel gridPanel,
      int trackIndex,
      int clipIndex,
      ProjectModel projectModel,
      BridgeContract bridge) {
    super(owner, "Piano Roll Editor", false);
    this.gridPanel = gridPanel;
    this.trackIndex = trackIndex;
    this.clipIndex = clipIndex;
    this.projectModel = projectModel;
    this.bridge = bridge;

    if (projectModel != null && trackIndex < projectModel.getTracks().size()) {
      this.trackModel = projectModel.getTracks().get(trackIndex);
      if (clipIndex < trackModel.getClips().size()) {
        this.clipModel = trackModel.getClips().get(clipIndex);
      }
    }

    // Determine engine base track ID mapping
    this.baseTrackId = 0;
    for (int t = 0; t < trackIndex; t++) {
      TrackModel tm = projectModel.getTracks().get(t);
      if (tm.getType() == TrackType.SYNTH) {
        this.baseTrackId += 128;
      } else if (tm.getType() == TrackType.KIT) {
        this.baseTrackId += ((KitTrackModel) tm).getDrums().size();
      } else {
        this.baseTrackId += 1;
      }
    }

    setTitle("Piano Roll Editor - Track " + (trackIndex + 1) + ": " + trackModel.getName());
    setSize(1150, 780);
    setLocationRelativeTo(owner);
    setLayout(new BorderLayout());
    getContentPane().setBackground(BG_DARK);

    buildUI();

    // Scroll to C4 (MIDI Note 60) on open
    SwingUtilities.invokeLater(
        () -> {
          int viewY = (127 - 60) * rowHeight - (gridScrollPane.getViewport().getHeight() / 2);
          gridScrollPane.getVerticalScrollBar().setValue(Math.max(0, viewY));
        });
  }

  private void buildUI() {
    // ── 1. Header Control Toolbar ──
    JPanel toolbar = new JPanel(new BorderLayout());
    toolbar.setBackground(new Color(0x15, 0x15, 0x18));
    toolbar.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, COLOR_BORDER),
            BorderFactory.createEmptyBorder(8, 16, 8, 16)));

    // Left info
    JPanel infoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
    infoPanel.setOpaque(false);
    JLabel titleLabel = new JLabel("PIANO ROLL EDITOR");
    titleLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
    titleLabel.setForeground(ThemeManager.getPrimaryAccent());
    infoPanel.add(titleLabel);

    JLabel descLabel =
        new JLabel(
            "Clip: "
                + clipModel.getName()
                + " ("
                + clipModel.getStepCount()
                + " steps) | Alt+Drag = Nudge microtiming");
    descLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
    descLabel.setForeground(Color.LIGHT_GRAY);
    infoPanel.add(descLabel);
    toolbar.add(infoPanel, BorderLayout.WEST);

    // Right actions
    JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    actionsPanel.setOpaque(false);

    JButton zoomInBtn = new JButton("Zoom +");
    styleButton(zoomInBtn);
    zoomInBtn.addActionListener(
        e -> {
          stepWidth = Math.min(100, stepWidth + 10);
          updateSizes();
        });
    actionsPanel.add(zoomInBtn);

    JButton zoomOutBtn = new JButton("Zoom -");
    styleButton(zoomOutBtn);
    zoomOutBtn.addActionListener(
        e -> {
          stepWidth = Math.max(25, stepWidth - 10);
          updateSizes();
        });
    actionsPanel.add(zoomOutBtn);

    JButton midiImportBtn = new JButton("Import MIDI...");
    styleButton(midiImportBtn);
    midiImportBtn.addActionListener(e -> handleMidiImport());
    actionsPanel.add(midiImportBtn);

    toolbar.add(actionsPanel, BorderLayout.EAST);
    add(toolbar, BorderLayout.NORTH);

    // ── 2. The Main Note Grid ScrollPane ──
    canvas = new PianoRollCanvas();
    gridScrollPane = new JScrollPane(canvas);
    gridScrollPane.setBackground(BG_GRID);
    gridScrollPane.getViewport().setBackground(BG_GRID);
    gridScrollPane.setBorder(BorderFactory.createLineBorder(COLOR_BORDER, 1));
    gridScrollPane.getVerticalScrollBar().setUnitIncrement(16);
    gridScrollPane.getHorizontalScrollBar().setUnitIncrement(16);

    // Fixed Keyboard Header in Row Header View
    keyboardPanel = new PianoKeyboardPanel();
    gridScrollPane.setRowHeaderView(keyboardPanel);

    // ── 3. The Bottom Velocity Lane ScrollPane ──
    velocityLane = new VelocityLanePanel();
    velocityScrollPane =
        new JScrollPane(
            velocityLane,
            JScrollPane.VERTICAL_SCROLLBAR_NEVER,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    velocityScrollPane.setBackground(BG_GRID);
    velocityScrollPane.getViewport().setBackground(BG_GRID);
    velocityScrollPane.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, COLOR_BORDER));
    velocityScrollPane.setPreferredSize(new Dimension(3000, 110));

    // Bind horizontal scrollbar of velocity lane to the main note grid scrollbar
    gridScrollPane
        .getHorizontalScrollBar()
        .addAdjustmentListener(
            e -> {
              velocityScrollPane.getHorizontalScrollBar().setValue(e.getValue());
            });

    // Outer container grouping Grid + Velocity with a divider line
    JPanel centralPanel = new JPanel(new BorderLayout());
    centralPanel.setOpaque(false);
    centralPanel.add(gridScrollPane, BorderLayout.CENTER);
    centralPanel.add(velocityScrollPane, BorderLayout.SOUTH);

    add(centralPanel, BorderLayout.CENTER);

    // Theme repaint registration
    ThemeManager.addThemeListener(this::repaint);
  }

  private void styleButton(JButton btn) {
    btn.setBackground(new Color(0x25, 0x25, 0x2a));
    btn.setForeground(Color.WHITE);
    btn.setFont(new Font("SansSerif", Font.BOLD, 10));
    btn.setBorder(BorderFactory.createLineBorder(COLOR_BORDER, 1));
    btn.setPreferredSize(new Dimension(95, 24));
    btn.setFocusPainted(false);
  }

  private void updateSizes() {
    int totalW = clipModel.getStepCount() * stepWidth + 20;
    int totalH = 128 * rowHeight;
    canvas.setPreferredSize(new Dimension(totalW, totalH));
    velocityLane.setPreferredSize(new Dimension(totalW, 100));
    keyboardPanel.setPreferredSize(new Dimension(keyboardWidth, totalH));
    canvas.revalidate();
    velocityLane.revalidate();
    keyboardPanel.revalidate();
    canvas.repaint();
    velocityLane.repaint();
    keyboardPanel.repaint();
  }

  // ── MIDI File Importer ──
  private void handleMidiImport() {
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("Select Standard MIDI File");
    chooser.setFileFilter(
        new javax.swing.filechooser.FileNameExtensionFilter(
            "MIDI Files (*.mid, *.midi)", "mid", "midi"));
    int res = chooser.showOpenDialog(this);
    if (res != JFileChooser.APPROVE_OPTION) {
      return;
    }
    File file = chooser.getSelectedFile();
    try {
      Sequence seq = MidiSystem.getSequence(file);
      int resolution = seq.getResolution();
      double ticksPerStep = resolution / 4.0; // 16th notes

      // Temporary map to track active note-on tick times
      Map<Integer, Long> activeNotes = new HashMap<>();
      Map<Integer, Integer> activeVelocities = new HashMap<>();

      // Scan all tracks, extract notes
      List<MidiNoteEvent> importedNotes = new ArrayList<>();

      for (Track track : seq.getTracks()) {
        for (int i = 0; i < track.size(); i++) {
          MidiEvent event = track.get(i);
          MidiMessage msg = event.getMessage();
          if (msg instanceof ShortMessage sm) {
            int command = sm.getCommand();
            int key = sm.getData1();
            int velocity = sm.getData2();

            if (command == ShortMessage.NOTE_ON && velocity > 0) {
              activeNotes.put(key, event.getTick());
              activeVelocities.put(key, velocity);
            } else if (command == ShortMessage.NOTE_OFF
                || (command == ShortMessage.NOTE_ON && velocity == 0)) {
              Long startTick = activeNotes.remove(key);
              Integer vel = activeNotes.containsKey(key) ? activeVelocities.remove(key) : velocity;
              if (startTick != null) {
                double startStep = startTick / ticksPerStep;
                double endStep = event.getTick() / ticksPerStep;
                double duration = endStep - startStep;
                if (duration <= 0.0) duration = 1.0;
                importedNotes.add(new MidiNoteEvent(key, startStep, duration, vel / 127.0f));
              }
            }
          }
        }
      }

      if (importedNotes.isEmpty()) {
        JOptionPane.showMessageDialog(
            this,
            "No note events found in the selected MIDI file.",
            "Import MIDI",
            JOptionPane.WARNING_MESSAGE);
        return;
      }

      // Confirm overwrite
      int confirm =
          JOptionPane.showConfirmDialog(
              this,
              "Importing MIDI will overwrite the active sequencer clip. Proceed?",
              "Confirm Import",
              JOptionPane.YES_NO_OPTION,
              JOptionPane.QUESTION_MESSAGE);
      if (confirm != JOptionPane.YES_OPTION) {
        return;
      }

      // Overwrite local clip model and bridge
      int stepLimit = clipModel.getStepCount();

      // Clear existing steps in model & bridge first
      for (int r = 0; r < 128; r++) {
        int engineRow = baseTrackId + r;
        for (int s = 0; s < stepLimit; s++) {
          bridge.setStep(engineRow, s, false);
          clipModel.setStep(r, s, StepData.empty());
        }
      }

      // Map imported notes onto the grid
      for (MidiNoteEvent ne : importedNotes) {
        int step = (int) Math.floor(ne.startStep);
        if (step < 0 || step >= stepLimit) {
          continue; // out of clip range
        }
        int row = 127 - ne.pitch;
        if (row < 0 || row >= 128) {
          continue;
        }

        float fill = (float) (ne.startStep - step);
        float gate = (float) ne.duration;
        int engineRow = baseTrackId + row;

        // Apply to engine bridge
        bridge.setStep(engineRow, step, true);
        bridge.setVelocity(engineRow, step, ne.velocity);
        bridge.setGate(engineRow, step, gate);
        bridge.setStepFill(engineRow, step, fill);

        // Apply to model
        StepData stepData = new StepData(true, ne.velocity, gate, 1.0f, ne.pitch, 0, fill);
        clipModel.setStep(row, step, stepData);
      }

      gridPanel.repaint();
      updateSizes();
      JOptionPane.showMessageDialog(
          this,
          "Successfully imported " + importedNotes.size() + " notes from MIDI file!",
          "Import MIDI",
          JOptionPane.INFORMATION_MESSAGE);

    } catch (Exception ex) {
      ex.printStackTrace();
      JOptionPane.showMessageDialog(
          this,
          "Error reading MIDI file:\n" + ex.getMessage(),
          "MIDI Import Error",
          JOptionPane.ERROR_MESSAGE);
    }
  }

  private static class MidiNoteEvent {
    final int pitch;
    final double startStep;
    final double duration;
    final float velocity;

    MidiNoteEvent(int pitch, double startStep, double duration, float velocity) {
      this.pitch = pitch;
      this.startStep = startStep;
      this.duration = duration;
      this.velocity = velocity;
    }
  }

  // ── Grid Note Canvas (Logarithmic Grid + Horizontal Key Strips) ──
  private class PianoRollCanvas extends JPanel {

    private NoteDragInfo dragInfo = null;
    private static final int DRAG_RESIZE = 1;
    private static final int DRAG_MOVE = 2;

    public PianoRollCanvas() {
      setBackground(BG_GRID);
      updateSizes();

      MouseAdapter listener =
          new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
              int stepIdx = (e.getX() - 5) / stepWidth;
              int rowIdx = e.getY() / rowHeight;

              if (stepIdx < 0
                  || stepIdx >= clipModel.getStepCount()
                  || rowIdx < 0
                  || rowIdx >= 128) {
                return;
              }

              // Check if clicked on an existing note
              StepData clickedNote = findNoteAt(rowIdx, e.getX());
              int noteStepStart = findNoteStartStep(rowIdx, e.getX());

              if (clickedNote != null && clickedNote.active()) {
                if (e.getClickCount() == 2) {
                  // Double click to delete
                  deleteNote(rowIdx, noteStepStart);
                  return;
                }

                // Single click selects and initiates drag
                int noteX = 5 + noteStepStart * stepWidth + (int) (clickedNote.fill() * stepWidth);
                int noteW = (int) (clickedNote.gate() * stepWidth);
                boolean isResizeHandle = (e.getX() >= noteX + noteW - 8);

                dragInfo = new NoteDragInfo();
                dragInfo.mode = isResizeHandle ? DRAG_RESIZE : DRAG_MOVE;
                dragInfo.row = rowIdx;
                dragInfo.startStep = noteStepStart;
                dragInfo.note = clickedNote;
                dragInfo.pressX = e.getX();
                dragInfo.pressY = e.getY();

                // Trigger acoustic pitch preview on drag start
                playPreviewNote(127 - rowIdx);
              } else {
                if (e.getClickCount() == 2) {
                  // Double click on empty space to add note
                  addNote(rowIdx, stepIdx);
                }
              }
              repaint();
              velocityLane.repaint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
              if (dragInfo == null) return;

              int deltaX = e.getX() - dragInfo.pressX;
              int deltaY = e.getY() - dragInfo.pressY;

              if (dragInfo.mode == DRAG_RESIZE) {
                // Resize gate duration
                double stepDelta = (double) deltaX / stepWidth;
                float newGate = (float) (dragInfo.note.gate() + stepDelta);
                newGate =
                    Math.max(
                        0.0625f, Math.min(clipModel.getStepCount() - dragInfo.startStep, newGate));

                // Temporarily update model/bridge for live visual feedback
                int engineRow = baseTrackId + dragInfo.row;
                bridge.setGate(engineRow, dragInfo.startStep, newGate);
                StepData updated =
                    new StepData(
                        true,
                        dragInfo.note.velocity(),
                        newGate,
                        dragInfo.note.probability(),
                        dragInfo.note.pitch(),
                        dragInfo.note.iterance(),
                        dragInfo.note.fill());
                clipModel.setStep(dragInfo.row, dragInfo.startStep, updated);

              } else if (dragInfo.mode == DRAG_MOVE) {
                // Move step/pitch
                int stepDelta = deltaX / stepWidth;
                int rowDelta = deltaY / rowHeight;

                int targetRow = Math.max(0, Math.min(127, dragInfo.row + rowDelta));
                int targetStep =
                    Math.max(
                        0, Math.min(clipModel.getStepCount() - 1, dragInfo.startStep + stepDelta));

                float fill = dragInfo.note.fill();
                if (e.isAltDown()) {
                  // Holding Alt enables microtiming nudge!
                  double nudgeDelta = (double) (deltaX % stepWidth) / stepWidth;
                  fill = (float) (dragInfo.note.fill() + nudgeDelta);
                  if (fill < 0f) fill = 0f;
                  if (fill > 0.95f) fill = 0.95f;
                }

                int currentPitch = 127 - targetRow;
                if (currentPitch != activePreviewNote) {
                  playPreviewNote(currentPitch);
                }

                // Perform move in model/bridge
                moveNote(dragInfo.row, dragInfo.startStep, targetRow, targetStep, fill);
                dragInfo.row = targetRow;
                dragInfo.startStep = targetStep;
                dragInfo.pressX = e.getX();
                dragInfo.pressY = e.getY();
              }
              repaint();
              velocityLane.repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
              if (dragInfo != null) {
                dragInfo = null;
                stopPreviewNote();
                gridPanel.repaint();
                fireProjectChanged();
              }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
              int stepIdx = (e.getX() - 5) / stepWidth;
              int rowIdx = e.getY() / rowHeight;

              if (stepIdx < 0
                  || stepIdx >= clipModel.getStepCount()
                  || rowIdx < 0
                  || rowIdx >= 128) {
                setCursor(Cursor.getDefaultCursor());
                return;
              }

              StepData note = findNoteAt(rowIdx, e.getX());
              if (note != null && note.active()) {
                int noteStep = findNoteStartStep(rowIdx, e.getX());
                int noteX = 5 + noteStep * stepWidth + (int) (note.fill() * stepWidth);
                int noteW = (int) (note.gate() * stepWidth);

                if (e.getX() >= noteX + noteW - 8) {
                  setCursor(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR));
                } else {
                  setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                }
              } else {
                setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
              }
            }
          };

      addMouseListener(listener);
      addMouseMotionListener(listener);
    }

    private StepData findNoteAt(int row, int mouseX) {
      for (int s = 0; s < clipModel.getStepCount(); s++) {
        StepData note = clipModel.getStep(row, s);
        if (note.active()) {
          int startX = 5 + s * stepWidth + (int) (note.fill() * stepWidth);
          int width = (int) (note.gate() * stepWidth);
          if (mouseX >= startX && mouseX <= startX + width) {
            return note;
          }
        }
      }
      return null;
    }

    private int findNoteStartStep(int row, int mouseX) {
      for (int s = 0; s < clipModel.getStepCount(); s++) {
        StepData note = clipModel.getStep(row, s);
        if (note.active()) {
          int startX = 5 + s * stepWidth + (int) (note.fill() * stepWidth);
          int width = (int) (note.gate() * stepWidth);
          if (mouseX >= startX && mouseX <= startX + width) {
            return s;
          }
        }
      }
      return -1;
    }

    private void addNote(int row, int step) {
      int pitch = 127 - row;
      int engineRow = baseTrackId + row;

      StepData newStep =
          new StepData(true, 0.8f, StepData.DEFAULT_CLICK_GATE, 1.0f, pitch, 0, 0.0f);

      bridge.setStep(engineRow, step, true);
      bridge.setVelocity(engineRow, step, 0.8f);
      bridge.setGate(engineRow, step, StepData.DEFAULT_CLICK_GATE);
      bridge.setStepFill(engineRow, step, 0.0f);

      clipModel.setStep(row, step, newStep);
      playPreviewNote(pitch);
      Timer t = new Timer(150, ev -> stopPreviewNote());
      t.setRepeats(false);
      t.start();
    }

    private void deleteNote(int row, int step) {
      int engineRow = baseTrackId + row;
      bridge.setStep(engineRow, step, false);
      clipModel.setStep(row, step, StepData.empty());
      gridPanel.repaint();
      fireProjectChanged();
    }

    private void moveNote(int fromRow, int fromStep, int toRow, int toStep, float fill) {
      if (fromRow == toRow
          && fromStep == toStep
          && fill == clipModel.getStep(fromRow, fromStep).fill()) {
        return;
      }

      StepData note = clipModel.getStep(fromRow, fromStep);
      int fromEngineRow = baseTrackId + fromRow;
      int toEngineRow = baseTrackId + toRow;

      // Clear old position
      bridge.setStep(fromEngineRow, fromStep, false);
      clipModel.setStep(fromRow, fromStep, StepData.empty());

      // Set new position
      bridge.setStep(toEngineRow, toStep, true);
      bridge.setVelocity(toEngineRow, toStep, note.velocity());
      bridge.setGate(toEngineRow, toStep, note.gate());
      bridge.setStepFill(toEngineRow, toStep, fill);

      StepData movedNote =
          new StepData(
              true,
              note.velocity(),
              note.gate(),
              note.probability(),
              127 - toRow,
              note.iterance(),
              fill);
      clipModel.setStep(toRow, toStep, movedNote);
    }

    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

      int steps = clipModel.getStepCount();

      // ── 1. Paint Horizontal Lane Backgrounds (OLED scale colors) ──
      boolean[] isBlackKey = {
        false, true, false, true, false, false, true, false, true, false, true, false
      };
      for (int r = 0; r < 128; r++) {
        int noteInOct = (127 - r) % 12;
        g2.setColor(isBlackKey[noteInOct] ? BG_KEY_BLACK : BG_KEY_WHITE);
        g2.fillRect(0, r * rowHeight, getWidth(), rowHeight);

        g2.setColor(COLOR_GRID_LINE);
        g2.drawLine(0, r * rowHeight + rowHeight, getWidth(), r * rowHeight + rowHeight);
      }

      // ── 2. Paint Grid Column Lines (Beats and Subdivisions) ──
      for (int s = 0; s <= steps; s++) {
        int x = 5 + s * stepWidth;
        boolean isBeat = (s % 4 == 0);
        g2.setColor(isBeat ? COLOR_BEAT_LINE : COLOR_GRID_LINE);
        g2.setStroke(new BasicStroke(isBeat ? 1.5f : 1.0f));
        g2.drawLine(x, 0, x, getHeight());
      }

      // ── 3. Paint Active Note Bars (Glowing Cyberpunk Blocks) ──
      Color accent = ThemeManager.getPrimaryAccent();
      Color glowFill = new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 170);

      for (int r = 0; r < 128; r++) {
        for (int s = 0; s < steps; s++) {
          StepData note = clipModel.getStep(r, s);
          if (note.active()) {
            int noteX = 5 + s * stepWidth + (int) (note.fill() * stepWidth) + 1;
            int noteY = r * rowHeight + 2;
            int noteW = (int) (note.gate() * stepWidth) - 2;
            int noteH = rowHeight - 4;

            // Draw glowing border and solid rounded fill
            g2.setColor(glowFill);
            g2.fillRoundRect(noteX, noteY, noteW, noteH, 6, 6);
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(1.0f));
            g2.drawRoundRect(noteX, noteY, noteW, noteH, 6, 6);

            // Draw note label inside if large enough
            if (noteW > 35) {
              g2.setFont(new Font("SansSerif", Font.BOLD, 9));
              g2.setColor(Color.WHITE);
              String label = getNoteName(127 - r);
              g2.drawString(label, noteX + 6, noteY + noteH - 4);
            }
          }
        }
      }

      // ── 4. Paint Sweeping Playhead Position (Live Sync) ──
      int playheadStep = gridPanel.getCurrentPlayheadStep();
      if (playheadStep >= 0) {
        int activeCol = playheadStep % steps;
        int px = 5 + activeCol * stepWidth;
        g2.setColor(new Color(0xff, 0xff, 0xff, 80));
        g2.fillRect(px, 0, stepWidth, getHeight());
        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(2.0f));
        g2.drawLine(px, 0, px, getHeight());
      }

      g2.dispose();
    }
  }

  private static class NoteDragInfo {
    int mode;
    int row;
    int startStep;
    StepData note;
    int pressX;
    int pressY;
  }

  // ── Piano Keys Left RowHeader View ──
  private class PianoKeyboardPanel extends JPanel {

    public PianoKeyboardPanel() {
      setBackground(BG_DARK);
      setPreferredSize(new Dimension(keyboardWidth, 128 * rowHeight));
    }

    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

      boolean[] isBlackKey = {
        false, true, false, true, false, false, true, false, true, false, true, false
      };
      String[] noteNames = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

      for (int r = 0; r < 128; r++) {
        int pitch = 127 - r;
        int noteInOct = pitch % 12;
        boolean black = isBlackKey[noteInOct];

        // Draw key body
        g2.setColor(black ? Color.BLACK : Color.WHITE);
        g2.fillRect(0, r * rowHeight, keyboardWidth - 5, rowHeight - 1);
        g2.setColor(COLOR_BORDER);
        g2.drawRect(0, r * rowHeight, keyboardWidth - 5, rowHeight - 1);

        // Key labels (C4, D#3, etc.) styled beautifully
        g2.setFont(new Font("SansSerif", Font.PLAIN, 8));
        g2.setColor(black ? Color.LIGHT_GRAY : Color.DARK_GRAY);
        String label = noteNames[noteInOct] + (pitch / 12 - 1);

        if (noteInOct == 0) {
          // Highlight octaves (C0, C1, etc.) in mint accent
          g2.setFont(new Font("SansSerif", Font.BOLD, 9));
          g2.setColor(ThemeManager.getSecondaryAccent());
        }
        g2.drawString(label, 6, r * rowHeight + rowHeight - 6);
      }

      g2.dispose();
    }
  }

  // ── Synchronized Velocity Stalk Panel ──
  private class VelocityLanePanel extends JPanel {

    private int draggedStalkStep = -1;

    public VelocityLanePanel() {
      setBackground(BG_GRID);
      setPreferredSize(new Dimension(3000, 100));

      MouseAdapter listener =
          new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
              int stepIdx = (e.getX() - 5) / stepWidth;
              if (stepIdx >= 0 && stepIdx < clipModel.getStepCount()) {
                // Find if there is an active note in any row at this step
                for (int r = 0; r < 128; r++) {
                  StepData note = clipModel.getStep(r, stepIdx);
                  if (note.active()) {
                    draggedStalkStep = stepIdx;
                    updateVelocity(stepIdx, e.getY());
                    break;
                  }
                }
              }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
              if (draggedStalkStep != -1) {
                updateVelocity(draggedStalkStep, e.getY());
              }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
              if (draggedStalkStep != -1) {
                draggedStalkStep = -1;
                gridPanel.repaint();
                fireProjectChanged();
              }
            }
          };

      addMouseListener(listener);
      addMouseMotionListener(listener);
    }

    private void updateVelocity(int step, int mouseY) {
      float vel = 1.0f - (float) mouseY / 90.0f;
      vel = Math.max(0.01f, Math.min(1.0f, vel));

      // Update all active notes at this step column in both model and bridge
      for (int r = 0; r < 128; r++) {
        StepData note = clipModel.getStep(r, step);
        if (note.active()) {
          int engineRow = baseTrackId + r;
          bridge.setVelocity(engineRow, step, vel);

          StepData updated =
              new StepData(
                  true,
                  vel,
                  note.gate(),
                  note.probability(),
                  note.pitch(),
                  note.iterance(),
                  note.fill());
          clipModel.setStep(r, step, updated);
        }
      }
      repaint();
      canvas.repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

      int steps = clipModel.getStepCount();

      // Draw horizontal dividing line
      g2.setColor(COLOR_BORDER);
      g2.drawLine(0, 0, getWidth(), 0);

      // Paint step column guides
      for (int s = 0; s <= steps; s++) {
        int x = 5 + s * stepWidth;
        g2.setColor(COLOR_GRID_LINE);
        g2.drawLine(x, 0, x, getHeight());
      }

      // Draw velocity stalks in global theme accent colors
      Color accent = ThemeManager.getSecondaryAccent();
      g2.setColor(accent);

      for (int s = 0; s < steps; s++) {
        // Find notes at this step
        for (int r = 0; r < 128; r++) {
          StepData note = clipModel.getStep(r, s);
          if (note.active()) {
            int sx = 5 + s * stepWidth + stepWidth / 2;
            int sy = 90 - (int) (note.velocity() * 80.0);

            // Draw vertical stalk line
            g2.setColor(COLOR_GRID_LINE);
            g2.setStroke(new BasicStroke(2.0f));
            g2.drawLine(sx, 10, sx, 90);

            g2.setColor(accent);
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawLine(sx, sy, sx, 90);

            // Draw glowing handle circle
            g2.fillOval(sx - 4, sy - 4, 8, 8);
            g2.setColor(Color.WHITE);
            g2.drawOval(sx - 4, sy - 4, 8, 8);
            break; // paint one stalk per step column
          }
        }
      }

      g2.dispose();
    }
  }

  // ── Helper: Play short preview pitch on synth ──
  private void playPreviewNote(int pitch) {
    if (bridge == null) return;
    stopPreviewNote();
    activePreviewNote = pitch;
    gridPanel.triggerKeyboardNote(pitch);
  }

  private void stopPreviewNote() {
    if (bridge == null || activePreviewNote == -1) return;
    gridPanel.triggerKeyboardNoteRelease(activePreviewNote);
    activePreviewNote = -1;
  }

  private void fireProjectChanged() {
    gridPanel.fireProjectChanged();
  }

  private String getNoteName(int pitch) {
    String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    return names[pitch % 12] + (pitch / 12 - 1);
  }
}
