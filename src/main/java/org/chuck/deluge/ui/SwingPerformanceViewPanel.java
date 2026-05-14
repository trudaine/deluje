package org.chuck.deluge.ui;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import javax.swing.*;
import javax.swing.border.LineBorder;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.ProjectModel;

/**
 * Performance View — a 16-column × 8-row grid of FX pads.
 *
 * <p>Each column maps to one FX type (volume, filter, delay, reverb, mod FX, stutter, bitcrush,
 * SRR, sidechain, compressor, EQ bass, EQ treble, FM amount, arp rate, portamento, noise vol).<br>
 * Each row represents a value intensity (0–7).<br>
 * Modes:
 *
 * <ul>
 *   <li>LATCH — tap toggles the pad on/off mutli-select style
 *   <li>MOMENTARY — press-and-hold activates, release restores previous value
 * </ul>
 *
 * <p>Integration: added as "PERF" card in the CardLayout of SwingDelugeApp, toggled from top bar.
 */
public class SwingPerformanceViewPanel extends JPanel {

  private static final int COLS = 16;
  private static final int ROWS = 8;

  private final ChuckVM vm;
  private final BridgeContract bridge;
  private final ProjectModel projectModel;

  // The 16 FX column names
  private static final String[] FX_NAMES = {
    "VOLUME", "PAN", "LPF FREQ", "LPF RES",
    "HPF FREQ", "HPF RES", "MOD FX RATE", "MOD FX DEPTH",
    "DELAY", "REVERB", "STUTTER", "BITCRUSH",
    "SRR", "SIDECHAIN", "COMP", "NOISE VOL"
  };

  // The per-track bridge globals each column controls (via G_TRACK_LEVEL etc.)
  // Stored as array of string pairs: { globalName, isFloat ("f" or "i") }
  // We use direct VM global writes per-track so each pad affects a parameter.
  // Column → SongParam or track param name.
  // For performance view, we modulate the *currently selected track* (focus track).
  private static final String[] FX_GLOBALS = {
    BridgeContract.G_TRACK_LEVEL, // VOLUME
    BridgeContract.G_PAN, // PAN
    BridgeContract.G_FILTER, // LPF FREQ (stored at index 0 of stride-2)
    BridgeContract.G_FILTER_MODE, // LPF RES (actually filter res is at index 1)
    BridgeContract.G_HPF_FREQ, // HPF FREQ
    BridgeContract.G_HPF_RES, // HPF RES
    BridgeContract.G_MOD_FX_RATE, // MOD FX RATE
    BridgeContract.G_MOD_FX_DEPTH, // MOD FX DEPTH
    null, // DELAY (send)
    null, // REVERB (send)
    null, // STUTTER
    null, // BITCRUSH
    null, // SRR
    null, // SIDECHAIN
    null, // COMP (threshold)
    null // NOISE VOL
  };

  // Row 0 = min value, row 7 = max value per column
  // Each float[row][col] = the value to set for that pad
  private final float[][] padValues = new float[ROWS][COLS];

  // State: which pads are active (latched-on)
  private final boolean[][] latchState = new boolean[ROWS][COLS];

  // Previous values for momentary restore
  private final float[][] previousValues = new float[ROWS][COLS];

  // The pad buttons
  private final JButton[][] pads = new JButton[ROWS][COLS];

  // Mode toggle
  private boolean momentaryMode = false; // false = LATCH, true = MOMENTARY

  // Currently selected focus track (-1 = no track)
  private int focusTrack = 0;

  private JLabel statusLabel;

  // Column header buttons for section toggles (toggle all rows in a column)
  private final boolean[] columnMuteState = new boolean[COLS];

  public SwingPerformanceViewPanel(ChuckVM vm, BridgeContract bridge, ProjectModel projectModel) {
    this.vm = vm;
    this.bridge = bridge;
    this.projectModel = projectModel;

    setBackground(new Color(0x18, 0x18, 0x18));
    setLayout(new BorderLayout(8, 8));

    // ── Top bar: mode toggle + track selector ──
    JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
    topBar.setBackground(new Color(0x25, 0x25, 0x25));

    JToggleButton modeToggle = new JToggleButton("MOMENTARY");
    modeToggle.addActionListener(
        e -> {
          momentaryMode = modeToggle.isSelected();
          modeToggle.setText(momentaryMode ? "MOMENTARY" : "LATCH");
          // Clear all latch state when switching to momentary
          if (momentaryMode) {
            for (int r = 0; r < ROWS; r++) {
              Arrays.fill(latchState[r], false);
            }
            for (int c = 0; c < COLS; c++) columnMuteState[c] = false;
            refreshPadColors();
          }
        });
    topBar.add(new JLabel("MODE:"));
    topBar.add(modeToggle);

    topBar.add(Box.createHorizontalStrut(20));

    JLabel trackLabel = new JLabel("TRACK:");
    trackLabel.setForeground(Color.WHITE);
    topBar.add(trackLabel);

    JSpinner trackSpinner =
        new JSpinner(new SpinnerNumberModel(0, 0, BridgeContract.TRACKS - 1, 1));
    trackSpinner.addChangeListener(
        e -> {
          focusTrack = (int) trackSpinner.getValue();
          statusLabel.setText("Track " + focusTrack);
        });
    topBar.add(trackSpinner);

    JButton allOffBtn = new JButton("ALL OFF");
    allOffBtn.addActionListener(e -> resetAll());
    topBar.add(allOffBtn);

    JButton columnOffBtn = new JButton("COL OFF");
    columnOffBtn.addActionListener(
        e -> {
          for (int c = 0; c < COLS; c++) columnMuteState[c] = false;
          for (int r = 0; r < ROWS; r++) Arrays.fill(latchState[r], false);
          refreshPadColors();
        });
    topBar.add(columnOffBtn);

    statusLabel = new JLabel("Track 0 | LATCH");
    statusLabel.setForeground(Color.LIGHT_GRAY);
    topBar.add(Box.createHorizontalGlue());
    topBar.add(statusLabel);

    add(topBar, BorderLayout.NORTH);

    // ── Compute pad value table ──
    computePadValues();

    // ── Grid of pads ──
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
    gc.gridx = 0;
    JLabel corner = new JLabel("", SwingConstants.CENTER);
    corner.setOpaque(true);
    corner.setBackground(new Color(0x30, 0x30, 0x30));
    gridPanel.add(corner, gc);

    for (int c = 0; c < COLS; c++) {
      gc.gridx = c + 1;
      JLabel header = new JLabel(shortName(FX_NAMES[c]), SwingConstants.CENTER);
      header.setOpaque(true);
      header.setBackground(new Color(0x30, 0x30, 0x30));
      header.setForeground(Color.WHITE);
      header.setFont(headerFont);
      header.setBorder(new LineBorder(Color.DARK_GRAY, 1));
      gridPanel.add(header, gc);
    }

    // Row labels + pads
    for (int r = 0; r < ROWS; r++) {
      gc.gridy = r + 1;

      // Row label
      gc.gridx = 0;
      JLabel rowLabel = new JLabel(String.valueOf(r + 1), SwingConstants.CENTER);
      rowLabel.setOpaque(true);
      rowLabel.setBackground(new Color(0x30, 0x30, 0x30));
      rowLabel.setForeground(Color.WHITE);
      rowLabel.setFont(headerFont);
      rowLabel.setBorder(new LineBorder(Color.DARK_GRAY, 1));
      gridPanel.add(rowLabel, gc);

      for (int c = 0; c < COLS; c++) {
        gc.gridx = c + 1;

        String valStr = formatValue(padValues[r][c]);
        JButton pad = new JButton(valStr);
        pad.setFont(padFont);
        pad.setFocusPainted(false);
        pad.setMargin(new Insets(0, 0, 0, 0));
        pad.setBackground(new Color(0x33, 0x33, 0x33));
        pad.setForeground(Color.LIGHT_GRAY);
        pad.setBorder(new LineBorder(new Color(0x55, 0x55, 0x55), 1));

        final int row = r;
        final int col = c;

        // Mouse handler for both LATCH and MOMENTARY
        pad.addMouseListener(
            new MouseAdapter() {
              @Override
              public void mousePressed(MouseEvent e) {
                if (momentaryMode) {
                  // Momentary: activate on press
                  activatePad(row, col);
                  pad.setBackground(activeColor(col));
                } else {
                  // Latch: toggle
                  boolean newState = !latchState[row][col];
                  latchState[row][col] = newState;
                  if (newState) {
                    activatePad(row, col);
                  } else {
                    deactivatePad(row, col);
                  }
                  refreshPadColors();
                }
              }

              @Override
              public void mouseReleased(MouseEvent e) {
                if (momentaryMode) {
                  // Momentary: deactivate on release
                  deactivatePad(row, col);
                  refreshPadColors();
                }
              }

              @Override
              public void mouseEntered(MouseEvent e) {
                // Drag-to-activate in momentary mode when button is held
                if (momentaryMode && (e.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) != 0) {
                  activatePad(row, col);
                  pad.setBackground(activeColor(col));
                }
              }

              @Override
              public void mouseExited(MouseEvent e) {
                if (momentaryMode && (e.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) != 0) {
                  deactivatePad(row, col);
                  refreshPadColors();
                }
              }
            });

        pads[r][c] = pad;
        gridPanel.add(pad, gc);
      }
    }

    add(gridPanel, BorderLayout.CENTER);

    // ── Bottom info bar ──
    JPanel infoBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
    infoBar.setBackground(new Color(0x25, 0x25, 0x25));
    JLabel infoLabel =
        new JLabel(
            "<html><b>PERFORMANCE VIEW</b> — 16 FX columns × 8 values &nbsp;&nbsp;|&nbsp;&nbsp; "
                + "LATCH: tap to toggle &nbsp;&nbsp;|&nbsp;&nbsp; MOMENTARY: press & hold &nbsp;&nbsp;|&nbsp;&nbsp; "
                + "Column header = section on/off</html>");
    infoLabel.setForeground(Color.LIGHT_GRAY);
    infoBar.add(infoLabel);
    add(infoBar, BorderLayout.SOUTH);
  }

  /** Set which track the performance controls target. */
  public void setFocusTrack(int track) {
    this.focusTrack = Math.max(0, Math.min(BridgeContract.TRACKS - 1, track));
    statusLabel.setText("Track " + focusTrack + " | " + (momentaryMode ? "MOMENTARY" : "LATCH"));
  }

  /** Reset all pads to off. */
  public void resetAll() {
    for (int r = 0; r < ROWS; r++) Arrays.fill(latchState[r], false);
    for (int c = 0; c < COLS; c++) columnMuteState[c] = false;
    // Restore defaults
    for (int r = 0; r < ROWS; r++) {
      for (int c = 0; c < COLS; c++) {
        writeValue(c, previousValues[r][c]);
      }
    }
    refreshPadColors();
  }

  // ── Private helpers ──

  /** Compute the normalized value for each pad (row, col). */
  private void computePadValues() {
    // Each row goes from 0.0 to 1.0 in 8 equal steps
    // but some params have specific ranges mapped differently
    for (int c = 0; c < COLS; c++) {
      for (int r = 0; r < ROWS; r++) {
        float t = r / (float) (ROWS - 1);
        padValues[r][c] = mapToParamRange(c, t);
        previousValues[r][c] = 0f;
      }
    }
  }

  /** Map normalized 0-1 to the actual parameter range for a column. */
  private static float mapToParamRange(int col, float t) {
    return switch (col) {
      case 0 -> 0.1f + t * 0.9f; // VOLUME 0.1-1.0
      case 1 -> -1.0f + t * 2.0f; // PAN -1..1
      case 2 -> 20.0f + t * 19980.0f; // LPF FREQ 20-20000
      case 3 -> t; // LPF RES 0-1
      case 4 -> 20.0f + t * 19980.0f; // HPF FREQ 20-20000
      case 5 -> t; // HPF RES 0-1
      case 6 -> t * 20.0f; // MOD FX RATE 0-20
      case 7 -> t; // MOD FX DEPTH 0-1
      case 8 -> t; // DELAY 0-1
      case 9 -> t; // REVERB 0-1
      case 10 -> t; // STUTTER 0-1
      case 11 -> t; // BITCRUSH 0-1
      case 12 -> t; // SRR 0-1
      case 13 -> t; // SIDECHAIN 0-1
      case 14 -> t; // COMP 0-1
      case 15 -> t; // NOISE VOL 0-1
      default -> t;
    };
  }

  /** Format a parameter value for display on a small pad button. */
  private static String formatValue(float v) {
    if (Math.abs(v) < 0.01f) return "0";
    if (Math.abs(v) >= 1000) return String.format("%.0f", v);
    if (Math.abs(v) >= 10) return String.format("%.1f", v);
    return String.format("%.2f", v);
  }

  /** Shorten column name to fit header. */
  private static String shortName(String name) {
    if (name.length() <= 8) return name;
    // Abbreviate
    return switch (name) {
      case "MOD FX RATE" -> "MFX RATE";
      case "MOD FX DEPTH" -> "MFX DEPTH";
      case "NOISE VOL" -> "NOISE";
      default -> name.substring(0, 8);
    };
  }

  /** Get active color for a column (cycling through colors). */
  private static Color activeColor(int col) {
    Color[] colors = {
      new Color(0x00, 0xcc, 0x99), new Color(0xcc, 0x33, 0xff),
      new Color(0xff, 0x99, 0x33), new Color(0x33, 0xcc, 0xff),
      new Color(0xff, 0x66, 0x99), new Color(0x99, 0xff, 0x33),
      new Color(0xff, 0x33, 0x66), new Color(0x66, 0xff, 0xcc),
      new Color(0xcc, 0x99, 0x33), new Color(0x33, 0xff, 0x99),
      new Color(0xff, 0x66, 0x33), new Color(0x99, 0x33, 0xff),
      new Color(0x33, 0x99, 0xff), new Color(0xff, 0xcc, 0x33),
      new Color(0x66, 0xcc, 0x33), new Color(0xcc, 0x66, 0xff),
    };
    return colors[col % colors.length];
  }

  /** Activate a pad — save current value, write new value. */
  private void activatePad(int row, int col) {
    float newVal = padValues[row][col];
    // Read current value and save as previous
    float current = readValue(col);
    previousValues[row][col] = current;
    writeValue(col, newVal);
  }

  /** Deactivate a pad — restore previous value. */
  private void deactivatePad(int row, int col) {
    writeValue(col, previousValues[row][col]);
  }

  /** Read a per-track float from a VM ChuckArray global. */
  private float readChuckFloat(String global, int track) {
    try {
      Object obj = vm.getGlobalObject(global);
      if (obj instanceof org.chuck.core.ChuckArray arr) {
        return (float) arr.getFloat(track);
      }
    } catch (Exception ignored) {
    }
    return 0f;
  }

  /** Write a per-track float to a VM ChuckArray global. */
  private void writeChuckFloat(String global, int track, float val) {
    try {
      Object obj = vm.getGlobalObject(global);
      if (obj instanceof org.chuck.core.ChuckArray arr) {
        arr.setFloat(track, val);
      }
    } catch (Exception ignored) {
    }
  }

  /** Read a per-track int from a VM ChuckArray global. */
  private int readChuckInt(String global, int track) {
    try {
      Object obj = vm.getGlobalObject(global);
      if (obj instanceof org.chuck.core.ChuckArray arr) {
        return (int) arr.getInt(track);
      }
    } catch (Exception ignored) {
    }
    return 0;
  }

  /** Write a per-track int to a VM ChuckArray global. */
  private void writeChuckInt(String global, int track, int val) {
    try {
      Object obj = vm.getGlobalObject(global);
      if (obj instanceof org.chuck.core.ChuckArray arr) {
        arr.setInt(track, (long) val);
      }
    } catch (Exception ignored) {
    }
  }

  /** Read current value of a column's parameter for the focus track. */
  private float readValue(int col) {
    if (focusTrack < 0) return 0f;
    return switch (col) {
      case 0 -> readChuckFloat(BridgeContract.G_TRACK_LEVEL, focusTrack);
      case 1 -> readChuckFloat(BridgeContract.G_PAN, focusTrack);
      case 2 -> (float) bridge.getTrackFilterFreq(focusTrack); // LPF freq
      case 3 -> (float) bridge.getTrackFilterRes(focusTrack); // LPF res
      case 4 -> readChuckFloat(BridgeContract.G_HPF_FREQ, focusTrack);
      case 5 -> readChuckFloat(BridgeContract.G_HPF_RES, focusTrack);
      case 6 -> readChuckFloat(BridgeContract.G_MOD_FX_RATE, focusTrack);
      case 7 -> readChuckFloat(BridgeContract.G_MOD_FX_DEPTH, focusTrack);
      case 8 -> bridge.getDelaySend(focusTrack);
      case 9 -> bridge.getReverbSend(focusTrack);
      case 10 -> readChuckFloat(BridgeContract.G_STUTTER_RATE, focusTrack);
      case 11 -> readChuckFloat(BridgeContract.G_BITCRUSH, focusTrack);
      case 12 -> readChuckFloat(BridgeContract.G_SAMPLE_RATE_RED, focusTrack);
      case 13 -> readChuckFloat(BridgeContract.G_SIDECHAIN_ATTACK, focusTrack);
      case 14 -> readChuckFloat(BridgeContract.G_COMP_ATTACK, focusTrack);
      case 15 -> readChuckFloat(BridgeContract.G_NOISE_VOL, focusTrack);
      default -> 0f;
    };
  }

  /** Write a value to the column's parameter for the focus track. */
  private void writeValue(int col, float val) {
    if (focusTrack < 0) return;
    switch (col) {
      case 0 -> writeChuckFloat(BridgeContract.G_TRACK_LEVEL, focusTrack, val);
      case 1 -> writeChuckFloat(BridgeContract.G_PAN, focusTrack, val);
      case 2 -> bridge.setFilterFreq(focusTrack, val);
      case 3 -> bridge.setFilterRes(focusTrack, val);
      case 4 -> writeChuckFloat(BridgeContract.G_HPF_FREQ, focusTrack, val);
      case 5 -> writeChuckFloat(BridgeContract.G_HPF_RES, focusTrack, val);
      case 6 -> writeChuckFloat(BridgeContract.G_MOD_FX_RATE, focusTrack, val);
      case 7 -> writeChuckFloat(BridgeContract.G_MOD_FX_DEPTH, focusTrack, val);
      case 8 -> bridge.setDelaySend(focusTrack, val);
      case 9 -> bridge.setReverbSend(focusTrack, val);
      case 10 -> writeChuckFloat(BridgeContract.G_STUTTER_RATE, focusTrack, val);
      case 11 -> writeChuckFloat(BridgeContract.G_BITCRUSH, focusTrack, val);
      case 12 -> writeChuckFloat(BridgeContract.G_SAMPLE_RATE_RED, focusTrack, val);
      case 13 -> writeChuckFloat(BridgeContract.G_SIDECHAIN_ATTACK, focusTrack, val);
      case 14 -> writeChuckFloat(BridgeContract.G_COMP_ATTACK, focusTrack, val);
      case 15 -> writeChuckFloat(BridgeContract.G_NOISE_VOL, focusTrack, val);
      default -> {}
    }
  }

  /** Refresh all pad background colors from latch state. */
  private void refreshPadColors() {
    for (int r = 0; r < ROWS; r++) {
      for (int c = 0; c < COLS; c++) {
        if (latchState[r][c]) {
          pads[r][c].setBackground(activeColor(c));
          pads[r][c].setForeground(Color.WHITE);
        } else {
          pads[r][c].setBackground(new Color(0x33, 0x33, 0x33));
          pads[r][c].setForeground(Color.LIGHT_GRAY);
        }
      }
    }
  }
}
