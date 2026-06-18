package org.deluge.ui;

import java.awt.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.LineBorder;
import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckEvent;
import org.chuck.core.ChuckVM;
import org.deluge.BridgeContract;
import org.deluge.model.ChordModel;
import org.deluge.model.ChordModel.ChordType;
import org.deluge.model.ProjectModel;
import org.deluge.model.Scales;

/**
 * Chord Keyboard panel supporting CORK and CORL layouts.
 *
 * <p>CORK (Chord Keyboard): Two modes — Column Mode (harmonically similar chords stacked
 * vertically) and Row Mode (Launchpad Pro style interval spread). CORL (Chord Library): A library
 * of chords where each column is a chromatic note, with chords laid out vertically within each
 * column. Scale-aware with highlighting for in-scale chords.
 *
 * <p>Layout modes: PIANO (standard), CHORD (CORK), CHORD_LIBRARY (CORL). The last two columns of
 * the grid contain embedded controls for mode switching.
 */
public class SwingChordKeyboardPanel extends JPanel {

  public enum KeyboardLayout {
    PIANO,
    CHORD,
    CHORD_LIBRARY
  }

  private static final int COLS = 18;
  private static final int ROWS = 12;

  private final ChuckVM vm;
  private final BridgeContract bridge;
  private final ProjectModel projectModel;

  private KeyboardLayout layout = KeyboardLayout.CHORD;
  private boolean corkColumnMode = true; // true=COLUMN, false=ROW (CORK only)
  private int scrollOffset = 0; // scale degree offset for CORK, vertical scroll for CORL

  private final JButton[][] pads = new JButton[ROWS][COLS];
  private JLabel statusLabel;

  /**
   * @param vm ChucK VM for note events
   * @param bridge bridge contract
   * @param projectModel project model (for scale/key info)
   */
  public SwingChordKeyboardPanel(ChuckVM vm, BridgeContract bridge, ProjectModel projectModel) {
    this.vm = vm;
    this.bridge = bridge;
    this.projectModel = projectModel;

    setBackground(new Color(0x18, 0x18, 0x18));
    setLayout(new BorderLayout(8, 8));

    // ── Top bar ──
    JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
    topBar.setBackground(new Color(0x25, 0x25, 0x25));

    JLabel modeLabel = new JLabel("KB:");
    modeLabel.setForeground(Color.WHITE);
    topBar.add(modeLabel);

    JComboBox<KeyboardLayout> layoutCombo = new JComboBox<>(KeyboardLayout.values());
    layoutCombo.setSelectedItem(layout);
    layoutCombo.addActionListener(
        e -> {
          layout = (KeyboardLayout) layoutCombo.getSelectedItem();
          rebuildGrid();
        });
    topBar.add(layoutCombo);

    topBar.add(Box.createHorizontalStrut(10));

    JToggleButton corkModeBtn = new JToggleButton("COLUMN", true);
    corkModeBtn.addActionListener(
        e -> {
          corkColumnMode = corkModeBtn.isSelected();
          corkModeBtn.setText(corkColumnMode ? "COLUMN" : "ROW");
          rebuildGrid();
        });
    topBar.add(new JLabel("CORK:"));
    topBar.add(corkModeBtn);

    topBar.add(Box.createHorizontalStrut(10));

    JButton upBtn = new JButton("\u25B2");
    upBtn.addActionListener(
        e -> {
          scrollOffset++;
          rebuildGrid();
        });
    topBar.add(upBtn);

    JButton downBtn = new JButton("\u25BC");
    downBtn.addActionListener(
        e -> {
          scrollOffset--;
          if (scrollOffset < 0) scrollOffset = 0;
          rebuildGrid();
        });
    topBar.add(downBtn);

    statusLabel = new JLabel(getStatusText());
    statusLabel.setForeground(Color.LIGHT_GRAY);
    topBar.add(Box.createHorizontalGlue());
    topBar.add(statusLabel);

    add(topBar, BorderLayout.NORTH);

    // ── Grid ──
    rebuildGrid();

    // ── Bottom info bar ──
    JPanel infoBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
    infoBar.setBackground(new Color(0x25, 0x25, 0x25));
    JLabel infoLabel =
        new JLabel(
            "<html><b>CHORD KEYBOARD</b> — CORK: column/row mode &nbsp;|&nbsp; "
                + "CORL: chord library &nbsp;|&nbsp; Scale-aware chords</html>");
    infoLabel.setForeground(Color.LIGHT_GRAY);
    infoBar.add(infoLabel);
    add(infoBar, BorderLayout.SOUTH);
  }

  /** Get the status text for the current layout state. */
  private String getStatusText() {
    String scaleName = projectModel.getScale();
    return switch (layout) {
      case PIANO -> "PIANO | " + scaleName;
      case CHORD -> "CORK " + (corkColumnMode ? "COLUMN" : "ROW") + " | " + scaleName;
      case CHORD_LIBRARY -> "CORL | " + scaleName;
    };
  }

  /** Rebuild the entire grid based on current layout selection. */
  private void rebuildGrid() {
    removeAll();
    // Re-add top bar
    // We rebuild only the center grid; keep top/bottom bars
    // Actually we need a different approach — use a card layout for the center
    // For now, rebuild the whole panel

    // Remove everything and recreate the grid
    // Simpler approach: just repopulate the pads
    statusLabel.setText(getStatusText());
    buildGrid();
    revalidate();
    repaint();
  }

  /** Build or rebuild the pad grid in the center. */
  private void buildGrid() {
    // Remove the old grid if exists
    Component oldCenter = null;
    for (Component c : getComponents()) {
      if (c instanceof JPanel
          && c != getComponent(0)
          && c != getComponent(getComponentCount() - 1)) {
        oldCenter = c;
        break;
      }
    }
    if (oldCenter != null) remove(oldCenter);

    JPanel gridPanel = new JPanel(new GridBagLayout());
    gridPanel.setBackground(new Color(0x18, 0x18, 0x18));
    GridBagConstraints gc = new GridBagConstraints();
    gc.fill = GridBagConstraints.BOTH;
    gc.insets = new Insets(1, 1, 1, 1);
    gc.weightx = 1.0;
    gc.weighty = 1.0;

    Font headerFont = new Font("SansSerif", Font.BOLD, 11);
    Font padFont = new Font("Monospaced", Font.PLAIN, 10);

    // Column headers
    gc.gridy = 0;
    for (int c = 0; c < COLS; c++) {
      gc.gridx = c;
      String h =
          switch (layout) {
            case PIANO -> c < 12 ? Scales.KEY_NAMES[c] : "";
            case CHORD -> {
              if (c >= COLS - 2) {
                yield c == COLS - 2 ? (corkColumnMode ? "ROW" : "COL") : "MODE";
              }
              // Scale degree label: I, ii, iii, IV, V, vi, vii
              yield scaleDegreeLabel(c + scrollOffset);
            }
            case CHORD_LIBRARY -> {
              if (c == COLS - 1) yield "";
              yield c < 12 ? Scales.KEY_NAMES[(c + scrollOffset) % 12] : "";
            }
          };
      JLabel header = new JLabel(h, SwingConstants.CENTER);
      header.setOpaque(true);
      header.setBackground(new Color(0x30, 0x30, 0x30));
      header.setForeground(Color.WHITE);
      header.setFont(headerFont);
      header.setBorder(new LineBorder(Color.DARK_GRAY, 1));
      gridPanel.add(header, gc);
    }

    // Populate pads
    for (int r = 0; r < ROWS; r++) {
      gc.gridy = r + 1;
      for (int c = 0; c < COLS; c++) {
        gc.gridx = c;
        JButton pad = createPad(r, c, padFont);
        pads[r][c] = pad;
        gridPanel.add(pad, gc);
      }
    }

    // Wrap in scroll pane
    JScrollPane scroll = new JScrollPane(gridPanel);
    scroll.setBorder(null);
    scroll.getVerticalScrollBar().setUnitIncrement(16);
    add(scroll, BorderLayout.CENTER);
  }

  /** Create a single pad button for the given row/col. */
  private JButton createPad(int row, int col, Font font) {
    JButton pad = new JButton();
    pad.setFont(font);
    pad.setFocusPainted(false);
    pad.setMargin(new Insets(0, 0, 0, 0));
    pad.setBorder(new LineBorder(new Color(0x55, 0x55, 0x55), 1));

    switch (layout) {
      case PIANO -> configurePianoPad(pad, row, col);
      case CHORD -> configureCorkPad(pad, row, col);
      case CHORD_LIBRARY -> configureCorlPad(pad, row, col);
    }

    return pad;
  }

  /** Configure a pad for standard PIANO keyboard layout. */
  private void configurePianoPad(JButton pad, int row, int col) {
    if (row >= 8 || col >= 18) {
      pad.setBackground(new Color(0x1a, 0x1a, 0x1a));
      pad.setEnabled(false);
      return;
    }
    int note = 36 + row * 18 + col;
    if (note >= 128) {
      pad.setBackground(new Color(0x1a, 0x1a, 0x1a));
      pad.setEnabled(false);
      return;
    }
    boolean isBlack =
        (note % 12 == 1 || note % 12 == 3 || note % 12 == 6 || note % 12 == 8 || note % 12 == 10);
    pad.setText(String.valueOf(note));
    pad.setBackground(isBlack ? SwingSynthConfigDialog.BG_CONTROL : Color.WHITE);
    pad.setForeground(isBlack ? Color.WHITE : Color.BLACK);
    final int fNote = note;
    pad.addActionListener(e -> triggerNote(fNote));
  }

  /** Configure a pad for CORK (chord keyboard) layout. */
  private void configureCorkPad(JButton pad, int row, int col) {
    // Embedded controls in last two columns
    if (col >= COLS - 2) {
      if (row == 0) {
        pad.setText(corkColumnMode ? "ROW" : "COL");
        pad.setBackground(new Color(0x44, 0x44, 0x88));
        pad.setForeground(Color.WHITE);
        pad.addActionListener(
            e -> {
              corkColumnMode = !corkColumnMode;
              rebuildGrid();
            });
      } else {
        pad.setBackground(new Color(0x1a, 0x1a, 0x1a));
        pad.setEnabled(false);
      }
      return;
    }

    if (corkColumnMode) {
      configureCorkColumnPad(pad, row, col);
    } else {
      configureCorkRowPad(pad, row, col);
    }
  }

  /** Configure a pad for CORK Column Mode. */
  private void configureCorkColumnPad(JButton pad, int row, int col) {
    int degree = col + scrollOffset;
    ChordType[] chordOptions = ChordModel.chordsForScaleDegree(degree);

    if (row >= chordOptions.length) {
      pad.setBackground(new Color(0x1a, 0x1a, 0x1a));
      pad.setEnabled(false);
      return;
    }

    ChordType chord = chordOptions[row];
    String scaleName = projectModel.getScale();
    Scales.ScaleType scaleType = parseScaleType(scaleName);
    int rootKey = projectModel.getUserScale(); // root key in semitones

    List<Integer> notes = ChordModel.buildChordForScaleDegree(rootKey, scaleType, degree, chord);
    String label = chord.name();

    pad.setText(label);
    pad.setBackground(colorForQuality(chord));
    pad.setForeground(Color.WHITE);

    final List<Integer> fNotes = notes;
    pad.addActionListener(e -> triggerChord(fNotes));
  }

  /** Configure a pad for CORK Row Mode. */
  private void configureCorkRowPad(JButton pad, int row, int col) {
    // Row mode: each row starts from the next scale degree
    int degree = row + scrollOffset;
    String scaleName = projectModel.getScale();
    Scales.ScaleType scaleType = parseScaleType(scaleName);
    int rootKey = projectModel.getUserScale();
    int[] scaleIntervals = scaleType.getIntervals();

    // Get the root note for this row's scale degree
    int degreeInterval = scaleIntervals[degree % scaleIntervals.length];
    int octaveShift = (degree / scaleIntervals.length) * 12;
    int rowRoot = rootKey + degreeInterval + octaveShift;
    while (rowRoot < 48) rowRoot += 12;
    while (rowRoot > 72) rowRoot -= 12;

    if (col == 0) {
      // First column = single root note
      pad.setText(String.valueOf(rowRoot));
      pad.setBackground(new Color(0x55, 0x55, 0x88));
      pad.setForeground(Color.WHITE);
      final int fNote = rowRoot;
      pad.addActionListener(e -> triggerNote(fNote));
    } else if (col == COLS - 3) {
      // Last non-control column: triad (root + 5th + compound 3rd)
      int third = scaleIntervals[2 % scaleIntervals.length]; // 3rd scale degree
      // Convert to semitones from row root
      int thirdSemitones = semitonesFromScaleSteps(scaleIntervals, 2);
      pad.setText("TRIAD");
      pad.setBackground(new Color(0x55, 0x55, 0x55));
      pad.setForeground(Color.WHITE);
      int fifthSemitones = 7; // perfect 5th
      final int fRowRoot = rowRoot;
      pad.addActionListener(
          e ->
              triggerChord(
                  List.of(fRowRoot, fRowRoot + fifthSemitones, fRowRoot + thirdSemitones + 12)));
    } else {
      // Interval columns: each represents a specific interval from row root
      // Following firmware spec scale-step intervals
      int[] rowIntervals = ChordModel.ROW_MODE_INTERVALS[0];
      int idx = col - 1;
      if (idx < rowIntervals.length) {
        int scaleSteps = rowIntervals[idx];
        int semitones = semitonesFromScaleSteps(scaleIntervals, scaleSteps);
        if (idx >= 4) semitones += 12; // compound intervals

        int note = rowRoot + semitones;
        note = Math.min(127, Math.max(0, note));
        pad.setText(String.valueOf(note));
        pad.setBackground(new Color(0x44, 0x66, 0x44));
        pad.setForeground(Color.WHITE);
        final int fNote = note;
        pad.addActionListener(e -> triggerNote(fNote));
      } else {
        pad.setBackground(new Color(0x1a, 0x1a, 0x1a));
        pad.setEnabled(false);
      }
    }
  }

  /** Configure a pad for CORL (chord library) layout. */
  private void configureCorlPad(JButton pad, int row, int col) {
    // Last column is empty/reserved
    if (col == COLS - 1) {
      pad.setBackground(new Color(0x1a, 0x1a, 0x1a));
      pad.setEnabled(false);
      return;
    }

    String scaleName = projectModel.getScale();
    Scales.ScaleType scaleType = parseScaleType(scaleName);
    int rootKey = projectModel.getUserScale();

    // Each column = chromatic note (12 columns, repeating)
    int chromaticNote = (col + scrollOffset) % 12;
    int baseOctave = 4 + (col + scrollOffset) / 12;
    int rootNote = baseOctave * 12 + chromaticNote;

    if (row == 0) {
      // Row 0 = root note (single note)
      pad.setText(String.valueOf(rootNote));
      boolean inScale = Scales.isNoteInScale(rootNote, rootKey, scaleType);
      pad.setBackground(inScale ? new Color(0x66, 0x66, 0xaa) : new Color(0x33, 0x33, 0x44));
      pad.setForeground(inScale ? Color.WHITE : Color.GRAY);
      final int fNote = rootNote;
      pad.addActionListener(e -> triggerNote(fNote));
    } else if (row < ChordModel.CHORD_LIBRARY_ROWS.length) {
      ChordType chord = ChordModel.CHORD_LIBRARY_ROWS[row];
      if (chord == null) {
        pad.setBackground(new Color(0x1a, 0x1a, 0x1a));
        pad.setEnabled(false);
        return;
      }

      // Build chord notes from this root
      List<Integer> chordNotes = ChordModel.buildChord(rootNote, chord);
      boolean allInScale =
          chordNotes.stream().allMatch(n -> Scales.isNoteInScale(n, rootKey, scaleType));

      pad.setText(chord.name());
      Color bg = allInScale ? colorForQuality(chord) : SwingSynthConfigDialog.BG_CONTROL;
      pad.setBackground(bg);
      pad.setForeground(allInScale ? Color.WHITE : Color.GRAY);
      pad.setEnabled(true);

      final List<Integer> fNotes = chordNotes;
      pad.addActionListener(e -> triggerChord(fNotes));
    } else {
      // Scroll indicator for lower rows
      String noteName = Scales.KEY_NAMES[chromaticNote];
      pad.setText("\u25BC " + noteName);
      pad.setBackground(SwingSynthConfigDialog.BG_CARD);
      pad.setForeground(Color.DARK_GRAY);
      pad.addActionListener(
          e -> {
            scrollOffset += 12;
            rebuildGrid();
          });
    }
  }

  // ── Note/chord triggering ──

  /** Trigger a single MIDI note via the g_ck_noteOn ChuckEvent. */
  private void triggerNote(int midiNote) {
    try {
      ChuckEvent noteEv = (ChuckEvent) vm.getGlobalObject("g_ck_noteOn");
      if (noteEv != null) {
        ChuckArray pitchArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PITCH);
        pitchArr.setInt(0, midiNote - 60L);
        noteEv.broadcast();
      }
    } catch (Exception ex) {
      // ignore
    }
  }

  /** Trigger multiple MIDI notes (a chord) sequentially. */
  private void triggerChord(List<Integer> midiNotes) {
    try {
      ChuckEvent noteEv = (ChuckEvent) vm.getGlobalObject("g_ck_noteOn");
      ChuckArray pitchArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PITCH);
      if (noteEv == null) return;

      for (int note : midiNotes) {
        if (note >= 0 && note < 128) {
          pitchArr.setInt(0, note - 60L);
          noteEv.broadcast();
        }
      }
    } catch (Exception ex) {
      // ignore
    }
  }

  // ── Helpers ──

  /** Get the quality color matching the firmware spec: Major=Blue, Minor=Purple, etc. */
  private static Color colorForQuality(ChordType chord) {
    ChordModel.Quality q = ChordModel.getQuality(chord);
    return switch (q) {
      case MAJOR -> new Color(0x33, 0x66, 0xff); // Blue
      case MINOR -> new Color(0x99, 0x44, 0xcc); // Purple
      case DOMINANT -> new Color(0x33, 0xcc, 0xcc); // Cyan
      case DIMINISHED -> new Color(0x44, 0xaa, 0x44); // Green
      case AUGMENTED -> new Color(0x88, 0x88, 0xbb); // Greyish Blue
      case OTHER -> new Color(0xcc, 0xcc, 0x44); // Yellow
    };
  }

  /** Convert scale steps to semitones using the scale's interval pattern. */
  private static int semitonesFromScaleSteps(int[] scaleIntervals, int steps) {
    if (steps <= 0) return 0;
    if (steps >= scaleIntervals.length) {
      int octaves = steps / scaleIntervals.length;
      int remainder = steps % scaleIntervals.length;
      return octaves * 12 + scaleIntervals[remainder];
    }
    return scaleIntervals[steps];
  }

  /** Map scale name string to Scales.ScaleType. */
  private static Scales.ScaleType parseScaleType(String name) {
    for (Scales.ScaleType st : Scales.ScaleType.values()) {
      if (st.getName().equalsIgnoreCase(name)) return st;
    }
    return Scales.ScaleType.MAJOR;
  }

  /** Get a Roman numeral label for a scale degree. */
  private static String scaleDegreeLabel(int degree) {
    String[] labels = {"I", "ii", "iii", "IV", "V", "vi", "vii"};
    int idx = degree % 7;
    if (idx < 0) idx += 7;
    String label = labels[idx];
    int octave = degree / 7;
    if (octave > 0) label += "'".repeat(Math.min(octave, 3));
    return label;
  }

  /** Get current keyboard layout. */
  public KeyboardLayout getKeyboardLayout() {
    return layout;
  }

  /** Set keyboard layout and rebuild. */
  public void setKeyboardLayout(KeyboardLayout l) {
    this.layout = l;
    rebuildGrid();
  }
}
