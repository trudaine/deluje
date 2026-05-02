package org.chuck.deluge.ui;

import java.awt.*;
import java.util.logging.Logger;
import javax.swing.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.SongSection;

/** Unified 18x8 Grid Panel handling both sequence matrix and clip launch arrangements. */
public class SwingGridPanel extends JPanel {
  private static final Logger LOG = Logger.getLogger(SwingGridPanel.class.getName());
  private final ChuckVM vm;
  private final BridgeContract bridge;

  private org.chuck.deluge.model.ProjectModel projectModel;
  private java.util.function.BiConsumer<Integer, Integer> onEditRequest;
  private static final int MAX_GRID_ROWS = 64;
  private static final int MAX_GRID_COLS = 26; // max columns: 24 steps + MUTE + SOLO
  private JButton[][] pads = new JButton[MAX_GRID_ROWS][MAX_GRID_COLS];
  private org.rtmidijava.RtMidiOut finalMidiOut;
  private double[] vuLevels = new double[MAX_GRID_ROWS];
  private Timer activeStutterTimer;
  private boolean[] isOneShotTrack = new boolean[MAX_GRID_ROWS];
  private int activeClipId = 0;
  private int baseTrackId = 0;
  private int editedModelTrack = 0; // model track index currently being edited in CLIP mode
  private int soloRow = -1; // -1 = no solo
  private Timer playheadTimer; // single timer for playhead updates, avoids leaks
  private int scrollOffset = 0; // vertical scroll offset for voice rows in CLIP mode
  private int scrollOffsetX = 0; // horizontal scroll offset for step columns in CLIP mode
  private int voiceRowCount = 8; // total number of voice rows for current track
  private org.chuck.deluge.project.PreferencesManager.GridMode gridMode =
      org.chuck.deluge.project.PreferencesManager.getGridMode();
  private int stepCount = 16; // steps per row, derived from gridMode
  private int columnCount = 18; // stepCount + 2 (MUTE + SOLO), derived from gridMode

  public enum GridViewMode {
    CLIP,
    SONG,
    ARRANGEMENT,
    AUTOMATION
  }

  private GridViewMode viewMode = GridViewMode.SONG;

  private String selectedAutomationParam = org.chuck.deluge.model.AutomationParam.ALL[0];
  private javax.swing.JComboBox<String> automationParamCombo;
  private boolean automationDragging = false;

  private Color[] trackColors = {
    new Color(0x00, 0xff, 0xcc), // Cyan
    new Color(0xff, 0x33, 0xcc), // Magenta
    new Color(0x33, 0xff, 0x33), // Lime Green
    new Color(0xff, 0x99, 0x33), // Orange
    new Color(0xcc, 0x33, 0xff), // Purple
    new Color(0xff, 0xff, 0x33), // Yellow
    new Color(0x33, 0x99, 0xff), // Blue
    new Color(0xff, 0x33, 0x33), // Red
    new Color(0xff, 0x66, 0x99), // Pink
    new Color(0x99, 0xff, 0x99), // Mint
    new Color(0xff, 0xcc, 0x66), // Peach
    new Color(0x66, 0xcc, 0xff), // Sky Blue
    new Color(0xcc, 0x99, 0xff), // Lavender
    new Color(0x66, 0xff, 0x66), // Bright Green
    new Color(0xff, 0x99, 0x66), // Coral
    new Color(0x99, 0xcc, 0xff), // Baby Blue
  };

  public SwingGridPanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;
    this.projectModel = new org.chuck.deluge.model.ProjectModel();

    setBackground(new Color(0x1a, 0x1a, 0x1a));
    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

    // Apply saved grid mode to step/column counts
    this.stepCount = gridMode.columns;
    this.columnCount = gridMode.columns + 2;

    // Keyboard navigation for scrollable CLIP mode
    InputMap im = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
    ActionMap am = getActionMap();

    im.put(KeyStroke.getKeyStroke("PAGE_UP"), "scrollPageUp");
    im.put(KeyStroke.getKeyStroke("PAGE_DOWN"), "scrollPageDown");
    im.put(KeyStroke.getKeyStroke("UP"), "scrollLineUp");
    im.put(KeyStroke.getKeyStroke("DOWN"), "scrollLineDown");

    am.put("scrollPageUp", new AbstractAction() {
      public void actionPerformed(java.awt.event.ActionEvent e) {
        if (viewMode == GridViewMode.CLIP) scrollPage(-1);
      }
    });
    am.put("scrollPageDown", new AbstractAction() {
      public void actionPerformed(java.awt.event.ActionEvent e) {
        if (viewMode == GridViewMode.CLIP) scrollPage(1);
      }
    });
    am.put("scrollLineUp", new AbstractAction() {
      public void actionPerformed(java.awt.event.ActionEvent e) {
        if (viewMode == GridViewMode.CLIP) scrollBy(-1);
      }
    });
    am.put("scrollLineDown", new AbstractAction() {
      public void actionPerformed(java.awt.event.ActionEvent e) {
        if (viewMode == GridViewMode.CLIP) scrollBy(1);
      }
    });

  }

  private int focusTrack = 0;
  private Runnable onProjectChanged;

  public void setOnProjectChanged(Runnable r) {
    this.onProjectChanged = r;
  }

  private void fireProjectChanged() {
    if (onProjectChanged != null) onProjectChanged.run();
    refresh();
  }

  /** Blend a base color with black proportionally to velocity (0.0 = black, 1.0 = full color). */
  private static Color velocityBlend(Color base, double velocity) {
    if (velocity >= 1.0) return base;
    if (velocity <= 0.0) return new Color(0x33, 0x33, 0x33);
    int r = (int) (base.getRed() * velocity + 0x33 * (1 - velocity));
    int g = (int) (base.getGreen() * velocity + 0x33 * (1 - velocity));
    int b = (int) (base.getBlue() * velocity + 0x33 * (1 - velocity));
    return new Color(Math.min(255, r), Math.min(255, g), Math.min(255, b));
  }

  public int getFocusTrack() {
    return focusTrack;
  }

  private void showTrackContextMenu(java.awt.Component src, int x, int y, int trackIdx) {
    java.util.List<org.chuck.deluge.model.TrackModel> tracks = projectModel.getTracks();
    if (trackIdx >= tracks.size()) return;
    org.chuck.deluge.model.TrackModel track = tracks.get(trackIdx);

    JPopupMenu menu = new JPopupMenu();

    JMenuItem renameItem = new JMenuItem("Rename...");
    renameItem.addActionListener(
        e -> {
          String newName = JOptionPane.showInputDialog(this, "Track name:", track.getName());
          if (newName != null && !newName.isBlank()) {
            track.setName(newName);
            fireProjectChanged();
          }
        });
    menu.add(renameItem);

    JMenuItem colorItem = new JMenuItem("Set Color...");
    colorItem.addActionListener(
        e -> {
          Color chosen = JColorChooser.showDialog(this, "Track Color", trackColors[trackIdx]);
          if (chosen != null) {
            trackColors[trackIdx] = chosen;
            track.setColourHex(
                "0x" + Integer.toHexString(chosen.getRGB() & 0xFFFFFF).toUpperCase());
            fireProjectChanged();
          }
        });
    menu.add(colorItem);

    menu.addSeparator();

    JMenuItem upItem = new JMenuItem("Move Up");
    upItem.setEnabled(trackIdx > 0);
    upItem.addActionListener(
        e -> {
          projectModel.moveTrackUp(trackIdx);
          // swap colors so they follow the track
          Color tmp = trackColors[trackIdx];
          trackColors[trackIdx] = trackColors[trackIdx - 1];
          trackColors[trackIdx - 1] = tmp;
          javax.swing.SwingUtilities.invokeLater(this::fireProjectChanged);
        });
    menu.add(upItem);

    JMenuItem downItem = new JMenuItem("Move Down");
    downItem.setEnabled(trackIdx < tracks.size() - 1);
    downItem.addActionListener(
        e -> {
          projectModel.moveTrackDown(trackIdx);
          // swap colors so they follow the track
          Color tmp = trackColors[trackIdx];
          trackColors[trackIdx] = trackColors[trackIdx + 1];
          trackColors[trackIdx + 1] = tmp;
          javax.swing.SwingUtilities.invokeLater(this::fireProjectChanged);
        });
    menu.add(downItem);

    menu.addSeparator();

    if (track instanceof org.chuck.deluge.model.KitTrackModel kitTrack) {
      JMenuItem saveKitItem = new JMenuItem("Save as Kit preset...");
      saveKitItem.addActionListener(e -> saveTrackPreset(kitTrack, false));
      menu.add(saveKitItem);
    } else if (track instanceof org.chuck.deluge.model.SynthTrackModel synthTrack) {
      JMenuItem saveSynthItem = new JMenuItem("Save as Synth preset...");
      saveSynthItem.addActionListener(e -> saveTrackPreset(synthTrack, true));
      menu.add(saveSynthItem);
    }

    menu.addSeparator();

    // Per-row probability and velocity — operates on all steps of this row in the active clip
    JMenuItem rowProbItem = new JMenuItem("Set Row Probability...");
    rowProbItem.addActionListener(e -> {
      String input = JOptionPane.showInputDialog(this, "Row probability (0-100%):", 100);
      if (input != null) {
        try {
          double val = Double.parseDouble(input.trim()) / 100.0;
          val = Math.max(0, Math.min(1, val));
          int engineRow = baseTrackId + trackIdx;
          int len = bridge != null ? bridge.getTrackLength(engineRow) : stepCount;
          for (int s = 0; s < len && s < stepCount; s++) {
            bridge.setStepProbability(engineRow, s, val);
          }
          refresh();
        } catch (NumberFormatException ignored) {}
      }
    });
    menu.add(rowProbItem);

    JMenuItem rowVelItem = new JMenuItem("Set Row Velocity...");
    rowVelItem.addActionListener(e -> {
      String input = JOptionPane.showInputDialog(this, "Row velocity (0-100%):", 80);
      if (input != null) {
        try {
          double val = Double.parseDouble(input.trim()) / 100.0;
          val = Math.max(0, Math.min(1, val));
          int engineRow = baseTrackId + trackIdx;
          int len = bridge != null ? bridge.getTrackLength(engineRow) : stepCount;
          for (int s = 0; s < len && s < stepCount; s++) {
            bridge.setVelocity(engineRow, s, val);
          }
          refresh();
        } catch (NumberFormatException ignored) {}
      }
    });
    menu.add(rowVelItem);

    menu.addSeparator();

    JMenuItem deleteItem = new JMenuItem("Delete Track");
    deleteItem.setForeground(Color.RED);
    deleteItem.addActionListener(
        e -> {
          int confirm =
              JOptionPane.showConfirmDialog(
                  this,
                  "Delete track \"" + track.getName() + "\" and all its clips?",
                  "Delete Track",
                  JOptionPane.YES_NO_OPTION,
                  JOptionPane.WARNING_MESSAGE);
          if (confirm == JOptionPane.YES_OPTION) {
            projectModel.removeTrack(track);
            fireProjectChanged();
          }
        });
    menu.add(deleteItem);

    menu.show(src, x, y);
  }

  private void saveTrackPreset(org.chuck.deluge.model.TrackModel track, boolean isSynth) {
    java.io.File dir =
        isSynth
            ? org.chuck.deluge.project.PreferencesManager.getSynthsDir()
            : org.chuck.deluge.project.PreferencesManager.getKitsDir();
    JFileChooser chooser = new JFileChooser(dir);
    chooser.setFileFilter(
        new javax.swing.filechooser.FileNameExtensionFilter("XML preset", "xml", "XML"));
    chooser.setSelectedFile(new java.io.File(dir, track.getName() + ".xml"));
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
    java.io.File file = chooser.getSelectedFile();
    if (!file.getName().toLowerCase().endsWith(".xml")) {
      file = new java.io.File(file.getAbsolutePath() + ".xml");
    }
    try {
      if (isSynth) {
        org.chuck.deluge.project.KitSynthSerializer.saveSynth(
            (org.chuck.deluge.model.SynthTrackModel) track, file);
      } else {
        org.chuck.deluge.project.KitSynthSerializer.saveKit(
            (org.chuck.deluge.model.KitTrackModel) track, file);
      }
      JOptionPane.showMessageDialog(
          this,
          "Saved to " + file.getAbsolutePath(),
          "Preset Saved",
          JOptionPane.INFORMATION_MESSAGE);
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(
          this, "Save failed:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
    }
  }

  private void showClipContextMenu(
      java.awt.Component src, int x, int y, org.chuck.deluge.model.TrackModel track, int clipIdx) {
    JPopupMenu menu = new JPopupMenu();
    org.chuck.deluge.model.ClipModel clip = track.getClips().get(clipIdx);

    JMenuItem renameItem = new JMenuItem("Rename Clip...");
    renameItem.addActionListener(
        e -> {
          String newName = JOptionPane.showInputDialog(this, "Clip name:", clip.getName());
          if (newName != null && !newName.isBlank()) {
            clip.setName(newName);
            fireProjectChanged();
          }
        });
    menu.add(renameItem);

    JMenuItem dupeItem = new JMenuItem("Duplicate Clip");
    dupeItem.addActionListener(
        e -> {
          org.chuck.deluge.model.ClipModel copy = clip.deepCopy(clip.getName() + " copy");
          track.addClip(copy);
          fireProjectChanged();
        });
    menu.add(dupeItem);

    menu.addSeparator();

    JMenuItem deleteItem = new JMenuItem("Delete Clip");
    deleteItem.setForeground(Color.RED);
    deleteItem.addActionListener(
        e -> {
          if (track.getClips().size() <= 1) {
            JOptionPane.showMessageDialog(this, "A track must have at least one clip.");
            return;
          }
          int confirm =
              JOptionPane.showConfirmDialog(
                  this,
                  "Delete clip \"" + clip.getName() + "\"?",
                  "Delete Clip",
                  JOptionPane.YES_NO_OPTION);
          if (confirm == JOptionPane.YES_OPTION) {
            track.removeClip(clip);
            fireProjectChanged();
          }
        });
    menu.add(deleteItem);

    menu.show(src, x, y);
  }

  public void setViewMode(GridViewMode mode) {
    this.viewMode = mode;
    if (mode == GridViewMode.AUTOMATION) {
      this.columnCount = stepCount; // no MUTE/SOLO columns
    } else {
      this.columnCount = stepCount + 2;
    }
    refresh();
  }

  public org.chuck.deluge.model.ProjectModel getProjectModel() {
    return projectModel;
  }

  public void setProjectModel(org.chuck.deluge.model.ProjectModel model) {

    this.projectModel = model;
    refresh();
  }

  public void setActiveClipId(int id) {
    this.activeClipId = id;
  }

  public void setBaseTrackId(int id) {
    this.baseTrackId = id;
    refresh();
  }

  public void setEditedModelTrack(int modelTrack) {
    this.editedModelTrack = modelTrack;
    scrollOffsetX = 0; // reset horizontal scroll when editing different track
  }

  /** Returns the total number of voice rows for the currently edited track. */
  public int getVoiceRowCount() { return voiceRowCount; }

  /** Set the grid size mode and recompute step/column counts. */
  public void setGridMode(org.chuck.deluge.project.PreferencesManager.GridMode mode) {
    if (this.gridMode != mode) {
      this.gridMode = mode;
      this.stepCount = mode.columns;
      this.columnCount = mode.columns + 2; // +2 for MUTE and SOLO columns
      if (viewMode == GridViewMode.AUTOMATION) {
        this.columnCount = mode.columns;
      }
      scrollOffset = 0;
      scrollOffsetX = 0;
      refresh();
    }
  }

  public org.chuck.deluge.project.PreferencesManager.GridMode getGridMode() {
    return gridMode;
  }

  public void setOnEditRequest(java.util.function.BiConsumer<Integer, Integer> callback) {
    this.onEditRequest = callback;
  }

  /** Reset scroll offsets when edited track changes. */
  public void resetScrollOffset() {
    scrollOffset = 0;
    scrollOffsetX = 0;
  }

  /** Compute total voice rows for the currently edited track in CLIP mode. */
  private int computeVoiceRowCount() {
    if (viewMode == GridViewMode.AUTOMATION) {
      return 8; // fixed 8 value bands (0-127 mapped to 8 rows)
    }
    if (viewMode == GridViewMode.CLIP && projectModel != null
        && editedModelTrack < projectModel.getTracks().size()) {
      org.chuck.deluge.model.TrackModel t = projectModel.getTracks().get(editedModelTrack);
      if (t instanceof org.chuck.deluge.model.KitTrackModel kit) {
        return kit.getSounds().size();
      }
      // Synth always uses gridMode.rows as voice count
      return gridMode.rows;
    }
    // SONG / ARRANGEMENT — use gridMode.rows as the total number of voice slots
    return gridMode.rows;
  }

  /** Number of visible voice rows in the viewport (up to gridMode.rows). */
  public int getVisibleRowCount() {
    return Math.min(gridMode.rows, voiceRowCount - scrollOffset);
  }

  /** Scroll the voice row viewport by delta rows. Positive = down, negative = up. */
  public void scrollBy(int delta) {
    int maxOffset = Math.max(0, voiceRowCount - gridMode.rows);
    scrollOffset = Math.max(0, Math.min(maxOffset, scrollOffset + delta));
    refresh();
  }

  /** Scroll by one full page (gridMode.rows rows). */
  public void scrollPage(int direction) {
    scrollBy(direction * gridMode.rows);
  }

  /** VU meter panel used inside voice rows. */
  private static class VUMeterPanel extends JPanel {
    private double lvl = 0.0;

    public void setLvl(double l) {
      this.lvl = l;
      repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      g.setColor(Color.DARK_GRAY);
      g.fillRect(0, 0, getWidth(), getHeight());
      g.setColor(Color.GREEN);
      int h = (int) (lvl * getHeight());
      g.fillRect(0, getHeight() - h, getWidth(), h);
    }
  }

  /** Build a single voice row panel. modelRow = the actual engine row index (0..voiceRowCount-1). */
  private JPanel buildVoiceRow(int modelRow, int visibleRow, int padSz,
      java.util.List<org.chuck.deluge.model.TrackModel> tracks) {
    JPanel rowPanel = new JPanel();
    rowPanel.setLayout(new BoxLayout(rowPanel, BoxLayout.X_AXIS));
    rowPanel.setBackground(new Color(0x22, 0x22, 0x22));
    rowPanel.setPreferredSize(new Dimension(3000, padSz));
    rowPanel.setMinimumSize(new Dimension(3000, padSz));
    rowPanel.setMaximumSize(new Dimension(3000, padSz));

    if (modelRow < tracks.size()) {
      String hex = tracks.get(modelRow).getColourHex();
      if (hex != null && hex.startsWith("0x")) {
        try {
          int rgb = Integer.decode(hex.substring(0, 8));
          trackColors[modelRow % trackColors.length] = new Color(rgb);
        } catch (Exception e) {
          LOG.warning("Bad color hex for track " + modelRow + ": " + e.getMessage());
        }
      }
    }

    String trackName;
    if (viewMode == GridViewMode.CLIP && projectModel != null
        && editedModelTrack < projectModel.getTracks().size()) {
      org.chuck.deluge.model.TrackModel rowTrack = projectModel.getTracks().get(editedModelTrack);
      if (rowTrack instanceof org.chuck.deluge.model.KitTrackModel kit) {
        java.util.List<org.chuck.deluge.model.KitTrackModel.KitSound> sounds = kit.getSounds();
        trackName = (modelRow < sounds.size()) ? sounds.get(modelRow).getName() : rowTrack.getName();
      } else {
        trackName = (modelRow == 0) ? rowTrack.getName() : "-" + modelRow + "st";
      }
    } else {
      trackName = (modelRow < tracks.size()) ? tracks.get(modelRow).getName() : "EMPTY " + (modelRow + 1);
    }

    final int trk = visibleRow;
    final String tName = trackName;
    JLabel label = new JLabel(tName);
    int lw = Math.max(60, Math.min(140, getWidth() / 12));
    label.setPreferredSize(new Dimension(lw, 30));
    label.setMinimumSize(new Dimension(lw, 30));
    label.setMaximumSize(new Dimension(lw, 30));

    label.setForeground(Color.LIGHT_GRAY);
    label.setCursor(new Cursor(Cursor.HAND_CURSOR));
    label.addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mouseClicked(java.awt.event.MouseEvent e) {
            if (e.isShiftDown()) {
              isOneShotTrack[modelRow] = !isOneShotTrack[modelRow];
              label.setText(isOneShotTrack[modelRow] ? tName + " (1SH)" : tName);
              return;
            }
            if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
              showTrackContextMenu(label, e.getX(), e.getY(), modelRow);
              return;
            }
            if (onEditRequest != null) {
              onEditRequest.accept(modelRow, 0);
            }
          }
        });

    rowPanel.add(label);

    // Config button and length badge only for modelRow < 8 (real model tracks)
    if (modelRow < tracks.size() && modelRow < 8) {
      org.chuck.deluge.model.TrackModel track = tracks.get(modelRow);

      JButton cfgBtn = new JButton("\u2699");
      cfgBtn.setPreferredSize(new Dimension(28, 26));
      cfgBtn.setMinimumSize(new Dimension(28, 26));
      cfgBtn.setMaximumSize(new Dimension(28, 26));
      cfgBtn.setMargin(new Insets(0, 0, 0, 0));
      cfgBtn.setFont(new Font("SansSerif", Font.PLAIN, 12));
      cfgBtn.setBackground(new Color(0x33, 0x33, 0x33));
      cfgBtn.setForeground(new Color(0x00, 0xff, 0xcc));
      cfgBtn.setToolTipText("Configure track");
      cfgBtn.addActionListener(e -> {
        Frame owner = (Frame) javax.swing.SwingUtilities.getWindowAncestor(SwingGridPanel.this);
        if (track instanceof org.chuck.deluge.model.KitTrackModel kitTrack) {
          new SwingKitConfigDialog(owner, kitTrack, vm, bridge).setVisible(true);
        } else if (track instanceof org.chuck.deluge.model.SynthTrackModel synthTrack) {
          new SwingSynthConfigDialog(owner, synthTrack, vm, bridge, modelRow).setVisible(true);
        }
      });
      rowPanel.add(Box.createHorizontalStrut(3));
      rowPanel.add(cfgBtn);

      int stepLen = (bridge != null) ? bridge.getTrackLength(modelRow) : 16;
      JLabel lenBadge = new JLabel("[" + stepLen + "]");
      lenBadge.setPreferredSize(new Dimension(36, 26));
      lenBadge.setMinimumSize(new Dimension(36, 26));
      lenBadge.setMaximumSize(new Dimension(36, 26));
      lenBadge.setFont(new Font("Monospaced", Font.BOLD, 11));
      lenBadge.setForeground(stepLen == 16 ? Color.GRAY : new Color(0xff, 0xcc, 0x00));
      lenBadge.setToolTipText("Track length (right-click to change)");
      lenBadge.setCursor(new Cursor(Cursor.HAND_CURSOR));
      lenBadge.addMouseListener(new java.awt.event.MouseAdapter() {
        @Override
        public void mouseClicked(java.awt.event.MouseEvent e) {
          if (javax.swing.SwingUtilities.isRightMouseButton(e) || e.getClickCount() == 2) {
            String input = JOptionPane.showInputDialog(
                SwingGridPanel.this, "Track length (1-192):", stepLen);
            if (input != null) {
              try {
                int newLen = Integer.parseInt(input.trim());
                if (newLen >= 1 && newLen <= BridgeContract.STEPS) {
                  bridge.setTrackLength(modelRow, newLen);
                  refresh();
                }
              } catch (NumberFormatException ignored) {}
            }
          }
        }
      });
      rowPanel.add(Box.createHorizontalStrut(2));
      rowPanel.add(lenBadge);
    } else {
      rowPanel.add(Box.createRigidArea(new Dimension(69, 1)));
    }

    rowPanel.add(Box.createHorizontalStrut(5));

    VUMeterPanel vu = new VUMeterPanel();
    vu.setPreferredSize(new Dimension(12, padSz));
    vu.setMaximumSize(new Dimension(12, padSz));
    rowPanel.add(vu);
    rowPanel.add(Box.createHorizontalStrut(5));

    Timer vuTimer =
        new Timer(
            33,
            ev -> {
              vuLevels[modelRow] *= 0.80;
              vu.setLvl(vuLevels[modelRow]);
            });
    vuTimer.start();

    for (int c = 0; c < columnCount; c++) {
      final int colId = c;

      JButton clipBtn;
      clipBtn = new JButton();

      clipBtn.setPreferredSize(new Dimension(padSz, padSz));
      clipBtn.setMinimumSize(new Dimension(padSz, padSz));
      clipBtn.setMaximumSize(new Dimension(padSz, padSz));
      clipBtn.setMargin(new Insets(0, 0, 0, 0));

      pads[visibleRow][c] = clipBtn;

      if (viewMode == GridViewMode.CLIP) {
        int engineR = baseTrackId + modelRow;
        double vel = bridge != null ? bridge.getVelocity(engineR, colId) : 0.8;
        double prob = bridge != null ? bridge.getStepProbability(engineR, colId) : 1.0;
        clipBtn.setText(
            "<html><font size='3'>Pi:"
                + (modelRow)
                + "<br>Ve:" + String.format("%.1f", vel)
                + "<br>Pr:" + String.format("%.1f", prob)
                + "<br>Ga:1</font></html>");
      } else if (viewMode == GridViewMode.ARRANGEMENT) {
        String tn =
            (modelRow < tracks.size()) ? tracks.get(modelRow).getName() : "EMPTY";
        clipBtn.setText(
            "<html><center><font size='3'>"
                + tn
                + "<br><b>Bar "
                + (c + 1)
                + "</b></font></center></html>");
      } else {
        if (modelRow < tracks.size() && c < tracks.get(modelRow).getClips().size()) {
          clipBtn.setText(
              "<html><center><font size='3'>"
                  + tracks.get(modelRow).getClips().get(c).getName()
                  + "</font></center></html>");
        } else {
          clipBtn.setText("PAD " + (c + 1));
        }
      }

      boolean hasClip = false;
      if (modelRow < tracks.size()) {
        org.chuck.deluge.model.TrackModel track = tracks.get(modelRow);
        if (c < track.getClips().size()) {
          hasClip = true;
        }
      }

      // In CLIP mode, wrap extra columns beyond clip length back to beginning
      final int activeCol;
      if (viewMode == GridViewMode.CLIP) {
        int trackLen = 0;
        if (modelRow < tracks.size()) {
          org.chuck.deluge.model.TrackModel track = tracks.get(modelRow);
          if (activeClipId < track.getClips().size()) {
            trackLen = track.getClips().get(activeClipId).getStepCount();
          }
        }
        if (trackLen <= 0) trackLen = bridge != null ? bridge.getTrackLength(baseTrackId + modelRow) : stepCount;
        if (trackLen > 0 && trackLen < stepCount) {
          activeCol = colId % trackLen; // wrap when clip is shorter than viewport
        } else if (trackLen > stepCount) {
          activeCol = Math.min(colId + scrollOffsetX, trackLen - 1); // scroll when clip is wider than viewport
        } else {
          activeCol = colId;
        }
      } else {
        activeCol = colId;
      }

      if (colId == columnCount - 2) {
        final int engineRow = baseTrackId + modelRow;
        clipBtn.setText("MUTE");
        clipBtn.setBackground(
            bridge.getMute(engineRow) ? Color.RED : new Color(0x33, 0x33, 0x33));
        clipBtn.addActionListener(
            e -> {
              if ((e.getModifiers() & java.awt.event.ActionEvent.SHIFT_MASK) != 0) {
                for (int s = 0; s < stepCount; s++) {
                  bridge.setStep(engineRow, s, false);
                }
                refresh();
                return;
              }
              boolean isMuted = bridge.getMute(engineRow);
              bridge.setMute(engineRow, !isMuted);
              clipBtn.setBackground(!isMuted ? Color.RED : new Color(0x33, 0x33, 0x33));
            });
      } else if (colId == columnCount - 1) {
        clipBtn.setText("SOLO");
        clipBtn.setBackground(soloRow == modelRow ? Color.GREEN : new Color(0x33, 0x33, 0x33));

        clipBtn.addActionListener(
            e -> {
              if (viewMode == GridViewMode.CLIP) {
                vm.setGlobalInt(BridgeContract.G_PREVIEW_TRACK, (long) modelRow);
                vm.broadcastGlobalEvent(BridgeContract.E_PREVIEW);

                if (soloRow == modelRow) {
                  soloRow = -1;
                  for (int i = 0; i < MAX_GRID_ROWS; i++) bridge.setMute(baseTrackId + i, false);
                } else {
                  soloRow = modelRow;
                  for (int i = 0; i < MAX_GRID_ROWS; i++) {
                    bridge.setMute(baseTrackId + i, i != modelRow);
                  }
                }
                refresh();
                return;
              }
              if (onEditRequest != null) {
                onEditRequest.accept(modelRow, 0);
              }
            });
      } else {
        // Rendering
        if (viewMode == GridViewMode.CLIP) {
          boolean stepState = bridge.getStep(baseTrackId + modelRow, activeCol);
          double vel = bridge.getVelocity(baseTrackId + modelRow, activeCol);
          clipBtn.setBackground(
              stepState ? velocityBlend(trackColors[visibleRow % trackColors.length], vel) : new Color(0x33, 0x33, 0x33));
        } else {
          if (hasClip) {
            clipBtn.setBackground(trackColors[visibleRow % trackColors.length]);
          } else {
            clipBtn.setBackground(new Color(0x33, 0x33, 0x33));
          }
        }

        // Click handler
        if (viewMode == GridViewMode.CLIP) {
          clipBtn.addMouseListener(
              new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent e) {
                  LOG.info("[grid] mPressed bVR: modelRow=" + modelRow + " visRow=" + visibleRow + " colId=" + colId + " t=" + e.getClickCount());
                  // Stop any preview timer from a previous press (button may have been replaced by refresh)
                  if (activeStutterTimer != null) {
                    activeStutterTimer.stop();
                    activeStutterTimer = null;
                  }
                  // Also write to debug file for offline inspection
                  try {
                    java.nio.file.Files.write(
                      java.nio.file.Paths.get("grid_debug.log"),
                      ("[grid] mPressed bVR: modelRow=" + modelRow + " visRow=" + visibleRow + " colId=" + colId + " t=" + e.getClickCount() + "\n").getBytes(),
                      java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                  } catch (Exception ignored) {
                    LOG.fine("grid_debug.log write failed: " + ignored.getMessage());
                  }
                  if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                    int engineRow = baseTrackId + modelRow;
                    double curVel = bridge.getVelocity(engineRow, activeCol);
                    StepPropertiesDialog dlg = new StepPropertiesDialog(
                        (Frame) javax.swing.SwingUtilities.getWindowAncestor(SwingGridPanel.this),
                        (int)(curVel * 100));
                    dlg.setVisible(true);
                    int newVel = dlg.getVelocity();
                    if (newVel != (int)(curVel * 100)) {
                      bridge.setVelocity(engineRow, activeCol, newVel / 100.0);
                      if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
                        org.chuck.deluge.model.TrackModel tModel =
                            projectModel.getTracks().get(editedModelTrack);
                        if (activeClipId < tModel.getClips().size()) {
                          org.chuck.deluge.model.ClipModel cModel = tModel.getClips().get(activeClipId);
                          boolean st = bridge.getStep(engineRow, activeCol);
                          double prob = bridge.getStepProbability(engineRow, activeCol);
                          cModel.setStep(modelRow, activeCol,
                              new org.chuck.deluge.model.StepData(st, newVel / 100.0f, 0.5f, (float)prob, 0));
                        }
                      }
                      refresh();
                    }
                  } else if (javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    boolean isSynthMode = bridge.getTrackType(baseTrackId) == 1;
                    int trackType = bridge.getTrackType(modelRow);

                    if (trackType == 2) {
                      // MIDI track
                      boolean st = bridge.getStep(baseTrackId + modelRow, activeCol);
                      bridge.setStep(baseTrackId + modelRow, activeCol, !st);
                      if (!st) {
                        if (finalMidiOut != null) {
                          try {
                            finalMidiOut.sendMessage(
                                new byte[] {(byte) 0x90, (byte) (60 + modelRow), (byte) 100});
                          } catch (Exception ex) {
                            LOG.warning("MIDI send failed (synth grid): " + ex.getMessage());
                          }
                        }
                      }
                      refresh();
                      if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
                        org.chuck.deluge.model.TrackModel tModel =
                            projectModel.getTracks().get(editedModelTrack);
                        if (activeClipId < tModel.getClips().size()) {
                          org.chuck.deluge.model.ClipModel cModel = tModel.getClips().get(activeClipId);
                          double curVel = bridge.getVelocity(baseTrackId + modelRow, activeCol);
                          double curProb = bridge.getStepProbability(baseTrackId + modelRow, activeCol);
                          cModel.setStep(modelRow, activeCol,
                              new org.chuck.deluge.model.StepData(!st, (float)curVel, 0.5f, (float)curProb, 0));
                        }
                      }
                    } else if (isSynthMode) {
                      // Synth piano roll: each row = MIDI note, higher row = lower pitch.
                      // Use unique engine row per visual row for independent bridge state.
                      int engineRow = baseTrackId + modelRow;
                      // Base pitch: row 0 = highest (MIDI 83), each step down = 1 semitone
                      // Continues descending: modelRow=8 -> MIDI 75, modelRow=15 -> MIDI 68
                      int pitchMidi = ((24 - 1) - modelRow) + 60;
                      boolean stepState = bridge.getStep(engineRow, activeCol);
                      bridge.setStep(engineRow, activeCol, !stepState);
                      double velS = bridge.getVelocity(engineRow, activeCol);
                      clipBtn.setBackground(
                          !stepState ? velocityBlend(trackColors[visibleRow % trackColors.length], velS) : new Color(0x33, 0x33, 0x33));
                      refresh();
                      // Preview voice: wrap to first POW (8) engine rows since
                      // synth_preview_shred checks r < car.length (8 UGen voices)
                      int voiceSlot = baseTrackId + (modelRow % 8);
                      vm.setGlobalFloat("g_preview_pitch", (float) (pitchMidi - 60));
                      vm.setGlobalInt(BridgeContract.G_PREVIEW_TRACK, (long) voiceSlot);
                      vm.broadcastGlobalEvent(BridgeContract.E_PREVIEW);
                      if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
                        org.chuck.deluge.model.TrackModel tModel =
                            projectModel.getTracks().get(editedModelTrack);
                        if (activeClipId < tModel.getClips().size()) {
                          org.chuck.deluge.model.ClipModel cModel = tModel.getClips().get(activeClipId);
                          double curVel = bridge.getVelocity(engineRow, activeCol);
                          double curProb = bridge.getStepProbability(engineRow, activeCol);
                          cModel.setStep(modelRow, activeCol,
                              new org.chuck.deluge.model.StepData(!stepState, (float)curVel, 0.5f, (float)curProb, 0));
                        }
                      }
                    } else {
                      // Kit track
                      boolean stepState = bridge.getStep(baseTrackId + modelRow, activeCol);
                      bridge.setStep(baseTrackId + modelRow, activeCol, !stepState);
                      double velK = bridge.getVelocity(baseTrackId + modelRow, activeCol);
                      clipBtn.setBackground(
                          !stepState ? velocityBlend(trackColors[visibleRow % trackColors.length], velK) : new Color(0x33, 0x33, 0x33));
                      if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
                        org.chuck.deluge.model.TrackModel tModel =
                            projectModel.getTracks().get(editedModelTrack);
                        if (activeClipId < tModel.getClips().size()) {
                          org.chuck.deluge.model.ClipModel cModel = tModel.getClips().get(activeClipId);
                          double curVel = bridge.getVelocity(baseTrackId + modelRow, activeCol);
                          double curProb = bridge.getStepProbability(baseTrackId + modelRow, activeCol);
                          cModel.setStep(modelRow, activeCol,
                              new org.chuck.deluge.model.StepData(!stepState, (float)curVel, 0.5f, (float)curProb, 0));
                        }
                      }
                      // Stop any previous preview timer before refresh (refresh replaces buttons)
                      if (activeStutterTimer != null) {
                        activeStutterTimer.stop();
                        activeStutterTimer = null;
                      }
                      refresh();
                      if (!stepState) {
                        vm.setGlobalInt(BridgeContract.G_PREVIEW_TRACK, (long) (baseTrackId + modelRow));
                        vm.broadcastGlobalEvent(BridgeContract.E_PREVIEW);
                        activeStutterTimer =
                            new Timer(150, ev -> {
                              vm.setGlobalInt(BridgeContract.G_PREVIEW_TRACK, (long) (baseTrackId + modelRow));
                              vm.broadcastGlobalEvent(BridgeContract.E_PREVIEW);
                            });
                        activeStutterTimer.start();
                      }
                    }
                  }
                }

                @Override
                public void mouseReleased(java.awt.event.MouseEvent e) {
                  if (activeStutterTimer != null) {
                    activeStutterTimer.stop();
                    activeStutterTimer = null;
                  }
                }

                @Override
                public void mouseExited(java.awt.event.MouseEvent e) {
                  if (activeStutterTimer != null) {
                    activeStutterTimer.stop();
                    activeStutterTimer = null;
                  }
                }
              });
        } else if (viewMode == GridViewMode.SONG) {
          clipBtn.addMouseListener(
              new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent e) {
                  if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                    new TrackInspectorDialog(
                        (Frame) javax.swing.SwingUtilities.getWindowAncestor(SwingGridPanel.this),
                        modelRow,
                        tracks,
                        SwingGridPanel.this::refresh)
                        .setVisible(true);
                  }
                }
              });
        } else if (viewMode == GridViewMode.ARRANGEMENT) {
          clipBtn.addMouseListener(
              new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent e) {
                  if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                    new BarAutomationDialog(
                        (Frame) javax.swing.SwingUtilities.getWindowAncestor(SwingGridPanel.this),
                        colId)
                        .setVisible(true);
                  }
                }
              });
        }

        if (viewMode == GridViewMode.SONG && modelRow < tracks.size() && colId < 16) {
          final int clipCol = colId;
          final org.chuck.deluge.model.TrackModel songTrack = tracks.get(modelRow);
          clipBtn.addMouseListener(
              new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent e) {
                  if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                    if (clipCol < songTrack.getClips().size()) {
                      showClipContextMenu(clipBtn, e.getX(), e.getY(), songTrack, clipCol);
                    }
                  } else {
                    if (clipCol >= songTrack.getClips().size()) {
                      String name = "CLIP " + (clipCol + 1);
                      songTrack.addClip(
                          new org.chuck.deluge.model.ClipModel(
                              name,
                              songTrack.getClips().isEmpty() ? 8 : songTrack.getClips().get(0).getRowCount(),
                              16));
                      fireProjectChanged();
                    }
                  }
                }
              });
        }

        clipBtn.addActionListener(
            e -> {
              if (viewMode == GridViewMode.SONG) {
                if ((e.getModifiers() & java.awt.event.ActionEvent.SHIFT_MASK) != 0) return;
                if (colId < 16 && modelRow < tracks.size()) {
                  bridge.setLaunchQueue(modelRow, colId);
                }
                clipBtn.setBackground(new Color(0xff, 0xaa, 0x00)); // amber = queued
                refresh();
              } else if (viewMode == GridViewMode.ARRANGEMENT) {
                if (clipBtn.getBackground().equals(trackColors[visibleRow % trackColors.length])) {
                  clipBtn.setBackground(new Color(0x33, 0x33, 0x33));
                } else {
                  clipBtn.setBackground(trackColors[visibleRow % trackColors.length]);
                }
              }
            });
      }

      if (c == columnCount - 2) {
        rowPanel.add(Box.createHorizontalStrut(20));
      }
      rowPanel.add(clipBtn);
      rowPanel.add(Box.createHorizontalStrut(5));
    }

    return rowPanel;
  }

  /** Build a fixed row (MACROS, SLIDERS, KEYBOARD) for the CLIP grid. */
  private JPanel buildFixedRow(int rowIdx, int padSz) {
    JPanel rowPanel = new JPanel();
    rowPanel.setLayout(new BoxLayout(rowPanel, BoxLayout.X_AXIS));
    rowPanel.setBackground(new Color(0x22, 0x22, 0x22));
    rowPanel.setPreferredSize(new Dimension(3000, padSz));
    rowPanel.setMinimumSize(new Dimension(3000, padSz));
    rowPanel.setMaximumSize(new Dimension(3000, padSz));

    String trackName;
    if (rowIdx == 8) trackName = "MACROS";
    else if (rowIdx == 9) trackName = "SLIDERS";
    else trackName = "KEYBOARD";

    JLabel label = new JLabel(trackName);
    int lw = Math.max(60, Math.min(140, getWidth() / 12));
    label.setPreferredSize(new Dimension(lw, 30));
    label.setMinimumSize(new Dimension(lw, 30));
    label.setMaximumSize(new Dimension(lw, 30));
    label.setForeground(Color.LIGHT_GRAY);
    rowPanel.add(label);
    rowPanel.add(Box.createRigidArea(new Dimension(69, 1)));
    rowPanel.add(Box.createHorizontalStrut(5));

    VUMeterPanel vu = new VUMeterPanel();
    vu.setPreferredSize(new Dimension(12, padSz));
    vu.setMaximumSize(new Dimension(12, padSz));
    rowPanel.add(vu);
    rowPanel.add(Box.createHorizontalStrut(5));

    for (int c = 0; c < columnCount; c++) {
      final int colId = c;
      JButton clipBtn;
      if (rowIdx == 8) {
        // MACROS row: labelled buttons
        if (c < 16) {
          String[] allParams = {
            "LEVEL", "PAN", "PITCH", "FILTER", "RESONANCE", "OSC1", "OSC2", "LFO",
            "MOD FX", "DELAY", "REVERB", "STUTTER", "PROBABILITY", "GATE", "VELOCITY", "SAMPLE"
          };
          clipBtn = new JButton("<html><center><b>" + allParams[c] + "</b></center></html>");
          clipBtn.setBackground(new Color(0x33, 0x33, 0x33));
          clipBtn.setForeground(Color.LIGHT_GRAY);
          clipBtn.setFont(new Font("SansSerif", Font.BOLD, padSz > 70 ? 14 : 10));
        } else {
          clipBtn = new JButton();
          clipBtn.setBackground(new Color(0x1a, 0x1a, 0x1a));
          clipBtn.setEnabled(false);
        }
      } else if (rowIdx == 9) {
        // SLIDERS row: velocity slider
        if (c < 16) {
          clipBtn = new JButton() {
            @Override
            protected void paintComponent(Graphics g) {
              super.paintComponent(g);
              int h = getHeight();
              int w = getWidth();
              g.setColor(new Color(0x00, 0xff, 0xcc, 0xaa));
              double val = (bridge != null) ? bridge.getVelocity(0, colId) : 0.5;
              int barH = (int) (val * h);
              g.fillRect(0, h - barH, w, barH);
            }
          };
          clipBtn.addMouseMotionListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseDragged(java.awt.event.MouseEvent e) {
              double v = 1.0 - (double) e.getY() / clipBtn.getHeight();
              v = Math.max(0.0, Math.min(1.0, v));
              bridge.setVelocity(0, colId, v);
              clipBtn.repaint();
            }
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
              double v = 1.0 - (double) e.getY() / clipBtn.getHeight();
              v = Math.max(0.0, Math.min(1.0, v));
              bridge.setVelocity(0, colId, v);
              clipBtn.repaint();
            }
          });
        } else {
          clipBtn = new JButton();
          clipBtn.setBackground(new Color(0x1a, 0x1a, 0x1a));
          clipBtn.setEnabled(false);
        }
      } else {
        // KEYBOARD row
        if (c < columnCount) {
          int note = 48 + colId;
          boolean isBlack = (colId % 12 == 1 || colId % 12 == 3
              || colId % 12 == 6 || colId % 12 == 8 || colId % 12 == 10);
          clipBtn = new JButton(String.valueOf(note));
          clipBtn.setBackground(isBlack ? new Color(0x33, 0x33, 0x33) : Color.WHITE);
          clipBtn.setForeground(isBlack ? Color.WHITE : Color.BLACK);
          clipBtn.setFont(new Font("SansSerif", Font.BOLD, padSz > 70 ? 14 : 10));
          clipBtn.addActionListener(e -> {
            try {
              org.chuck.core.ChuckEvent noteEv =
                  (org.chuck.core.ChuckEvent) vm.getGlobalObject("g_ck_noteOn");
              if (noteEv != null) {
                org.chuck.core.ChuckArray pitchArr =
                    (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_PITCH);
                pitchArr.setInt(0, (long) (note - 60));
                noteEv.broadcast();
              }
            } catch (Exception ex) {
              LOG.warning("Keyboard note broadcast failed: " + ex.getMessage());
            }
          });
          } else {
            clipBtn = new JButton();
          clipBtn.setBackground(new Color(0x1a, 0x1a, 0x1a));
          clipBtn.setEnabled(false);
        }
      }

      clipBtn.setPreferredSize(new Dimension(padSz, padSz));
      clipBtn.setMinimumSize(new Dimension(padSz, padSz));
      clipBtn.setMaximumSize(new Dimension(padSz, padSz));
      clipBtn.setMargin(new Insets(0, 0, 0, 0));

      pads[rowIdx][c] = clipBtn;

      if (c == columnCount - 2) {
        rowPanel.add(Box.createHorizontalStrut(20));
      }
      rowPanel.add(clipBtn);
      rowPanel.add(Box.createHorizontalStrut(5));
    }
    return rowPanel;
  }

  /** Flash a pad cell to indicate a note-on event from the isomorphic / QWERTY keyboard. */
  public void flashIsomorphicNote(int note) {
    int r = (note - 60) / 5;
    int c = (note - 60) % 5;
    if (r >= 0 && r < gridMode.rows && c >= 0 && c < 5 && pads[r][c] != null) {
      Color orig = pads[r][c].getBackground();
      pads[r][c].setBackground(Color.WHITE);
      Timer restore = new Timer(150, ev -> pads[r][c].setBackground(orig));
      restore.setRepeats(false);
      restore.start();
    }
  }

  public void updatePlayhead(int step) {
    if (step < 0) return;
    int trackLen = bridge != null ? bridge.getTrackLength(baseTrackId) : stepCount;
    // Map the engine step to a visual column — for clips wider than viewport, offset by scrollOffsetX
    int stepMod;
    if (trackLen > stepCount) {
      // The engine's current step is in [0, trackLen). Map to visual column by subtracting scrollOffsetX.
      stepMod = step % trackLen - scrollOffsetX;
      if (stepMod < 0) stepMod = 0;
      if (stepMod >= stepCount) stepMod = stepCount - 1;
    } else {
      stepMod = step % stepCount;
    }
    int rowsToScan = (viewMode == GridViewMode.CLIP) ? voiceRowCount : gridMode.rows;
    for (int t = 0; t < rowsToScan; t++) {
      if (pads[t][stepMod] == null) continue;
      int engineRow = baseTrackId + (viewMode == GridViewMode.CLIP ? scrollOffset + t : t);
      // When clip is shorter than viewport, wrap to get the real engine step index
      int engineStep = (trackLen < stepCount) ? (stepMod % trackLen) : stepMod;
      boolean isTriggered = (bridge != null) && bridge.getStep(engineRow, engineStep);
      if (isTriggered) {
        pads[t][stepMod].setBackground(Color.WHITE);
      } else if (pads[t][stepMod].getBackground().equals(Color.WHITE)) {
        // Restore to velocity-blended color using the wrapped engine step
        double vel = bridge.getVelocity(engineRow, engineStep);
        boolean stepActive = bridge.getStep(engineRow, engineStep);
        pads[t][stepMod].setBackground(
            stepActive ? velocityBlend(trackColors[t % trackColors.length], vel) : new Color(0x33, 0x33, 0x33));
      }
    }
  }

  public void refresh() {
    removeAll();
    java.util.List<org.chuck.deluge.model.TrackModel> tracks = projectModel.getTracks();

    voiceRowCount = computeVoiceRowCount();
    LOG.info("REFRESH gridMode=" + gridMode + " voiceRowCount=" + voiceRowCount
        + " gridMode.rows=" + gridMode.rows + " gridMode.columns=" + gridMode.columns
        + " columnCount=" + columnCount + " scrollOffset=" + scrollOffset
        + " viewMode=" + viewMode);
    // Reset scroll if needed
    int maxOffset = Math.max(0, voiceRowCount - gridMode.rows);
    if (scrollOffset > maxOffset) scrollOffset = maxOffset;

    // Compute dynamic pad size: always fit gridMode.rows × gridMode.columns cells in the viewport
    int availWidth = getWidth() > 0 ? getWidth() : 1200;
    int availHeight = getHeight() > 0 ? getHeight() : 600;
    int labelWidth = Math.max(60, Math.min(140, availWidth / 12));
    int cellsWidth = availWidth - labelWidth - 69 - 5 - 12 - 5 - 20;
    int rowsInView = gridMode.rows + 3; // voice rows + MACROS/SLIDERS/KEYBOARD = 3 fixed rows
    int padSz = Math.min(
      cellsWidth / columnCount,
      (availHeight - 30) / rowsInView
    );
    padSz = Math.max(16, Math.min(200, padSz));

    int savedColCount = columnCount; // saved for SONG/ARRANGEMENT section below

    if (viewMode == GridViewMode.AUTOMATION) {
      // ===== AUTOMATION MODE: 8 value-band rows × stepCount columns =====
      voiceRowCount = 8;
      columnCount = stepCount; // no MUTE/SOLO

      // Build the parameter selector combo and header
      JPanel autoHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
      autoHeader.setBackground(new Color(0x15, 0x15, 0x15));

      JLabel autoLabel = new JLabel("AUTOMATION");
      autoLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
      autoLabel.setForeground(new Color(0x00, 0xff, 0xcc));
      autoHeader.add(autoLabel);

      automationParamCombo = new javax.swing.JComboBox<>(org.chuck.deluge.model.AutomationParam.ALL);
      automationParamCombo.setSelectedItem(selectedAutomationParam);
      automationParamCombo.addActionListener(e -> {
        String selected = (String) automationParamCombo.getSelectedItem();
        if (selected != null) {
          selectedAutomationParam = selected;
          refresh();
        }
      });
      automationParamCombo.setToolTipText("Select automation parameter");
      autoHeader.add(automationParamCombo);

      JButton clearAutoBtn = new JButton("Clear All");
      clearAutoBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
      clearAutoBtn.setMargin(new Insets(0, 4, 0, 4));
      clearAutoBtn.addActionListener(e -> {
        if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
          org.chuck.deluge.model.TrackModel t = projectModel.getTracks().get(editedModelTrack);
          int acIdx = t.getActiveClipIndex();
          java.util.List<org.chuck.deluge.model.ClipModel> clips = t.getClips();
          if (acIdx >= 0 && acIdx < clips.size()) {
            clips.get(acIdx).clearAutomation(selectedAutomationParam);
            refresh();
          }
        }
      });
      autoHeader.add(clearAutoBtn);

      // Show track context if editing
      if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
        JLabel trackLabel = new JLabel("Track: " + projectModel.getTracks().get(editedModelTrack).getName());
        trackLabel.setForeground(Color.LIGHT_GRAY);
        trackLabel.setFont(new Font("SansSerif", Font.ITALIC, 12));
        autoHeader.add(Box.createHorizontalStrut(20));
        autoHeader.add(trackLabel);
      }

      add(autoHeader);

      // Step number header row
      JPanel stepHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
      stepHeader.setBackground(new Color(0x15, 0x15, 0x15));
      // spacing to align with grid cells (label area + spacer width)
      int labelOffset = Math.max(60, Math.min(140, getWidth() / 12)) + 69 + 5 + 12 + 5;
      stepHeader.add(Box.createRigidArea(new Dimension(labelOffset, 20)));
      for (int c = 0; c < stepCount; c++) {
        JLabel stepNum = new JLabel(String.valueOf(c + 1), javax.swing.SwingConstants.CENTER);
        stepNum.setPreferredSize(new Dimension(padSz, 18));
        stepNum.setForeground(Color.GRAY);
        stepNum.setFont(new Font("Monospaced", Font.PLAIN, 10));
        stepHeader.add(stepNum);
      }
      add(stepHeader);

      // Get the active clip's automation data for the selected param
      org.chuck.deluge.model.ClipModel autoClip = null;
      String autoParam = selectedAutomationParam;
      if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
        org.chuck.deluge.model.TrackModel t = projectModel.getTracks().get(editedModelTrack);
        int acIdx = t.getActiveClipIndex();
        if (acIdx >= 0 && acIdx < t.getClips().size()) {
          autoClip = t.getClips().get(acIdx);
        }
      }

      // Label for each value band row
      String[] bandLabels = {"0-15", "16-31", "32-47", "48-63", "64-79", "80-95", "96-111", "112-127"};

      for (int r = 0; r < 8; r++) {
        final int rowIdx = r;
        JPanel rowPanel = new JPanel();
        rowPanel.setLayout(new BoxLayout(rowPanel, BoxLayout.X_AXIS));
        rowPanel.setBackground(new Color(0x22, 0x22, 0x22));
        rowPanel.setPreferredSize(new Dimension(3000, padSz));
        rowPanel.setMinimumSize(new Dimension(3000, padSz));
        rowPanel.setMaximumSize(new Dimension(3000, padSz));

        JLabel valLabel = new JLabel(bandLabels[r]);
        int lw = Math.max(60, Math.min(140, getWidth() / 12));
        valLabel.setPreferredSize(new Dimension(lw, 30));
        valLabel.setMinimumSize(new Dimension(lw, 30));
        valLabel.setMaximumSize(new Dimension(lw, 30));
        valLabel.setForeground(new Color(0x88, 0x88, 0x88));
        valLabel.setFont(new Font("Monospaced", Font.PLAIN, 11));
        rowPanel.add(valLabel);

        // Spacer to match config-button + len-badge area (69px)
        rowPanel.add(Box.createRigidArea(new Dimension(69, 1)));
        rowPanel.add(Box.createHorizontalStrut(5));

        VUMeterPanel vu = new VUMeterPanel();
        vu.setPreferredSize(new Dimension(12, padSz));
        vu.setMaximumSize(new Dimension(12, padSz));
        rowPanel.add(vu);
        rowPanel.add(Box.createHorizontalStrut(5));

        for (int c = 0; c < stepCount; c++) {
          final int colIdx = c;
          JButton cell = new JButton();
          cell.setPreferredSize(new Dimension(padSz, padSz));
          cell.setMinimumSize(new Dimension(padSz, padSz));
          cell.setMaximumSize(new Dimension(padSz, padSz));
          cell.setMargin(new Insets(0, 0, 0, 0));

          pads[r][c] = cell;

          // Determine if this cell is "lit" (value band matches row)
          boolean lit = false;
          float autoVal = -1f;
          if (autoClip != null) {
            autoVal = autoClip.getAutomation(autoParam, colIdx);
            if (autoVal >= 0f) {
              int band = (int)(autoVal * 127f) / 16;
              lit = (band == rowIdx);
            }
          }

          if (lit) {
            // Calculate intensity within band (0-15) for brightness
            int precise = (int)(autoVal * 127f) % 16;
            int bright = 0x44 + precise * 8; // range 0x44 to 0xcc
            cell.setBackground(new Color(0x00, bright, Math.min(0xcc, bright / 2 + 0x44)));
            cell.setForeground(Color.WHITE);
            cell.setText("\u25CF");
          } else {
            cell.setBackground(new Color(0x33, 0x33, 0x33));
            cell.setForeground(new Color(0x55, 0x55, 0x55));
            if (autoVal >= 0f && (int)(autoVal * 127f) / 16 >= 0) {
              // This param has automation but value band doesn't match this row
              cell.setText(".");
            } else {
              cell.setText("");
            }
          }

          cell.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
              if (projectModel == null) return;
              org.chuck.deluge.model.TrackModel tM = projectModel.getTracks().get(editedModelTrack);
              int acIdx2 = tM.getActiveClipIndex();
              if (acIdx2 < 0 || acIdx2 >= tM.getClips().size()) return;
              org.chuck.deluge.model.ClipModel cM = tM.getClips().get(acIdx2);

              if (javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                if (e.isShiftDown()) {
                  // Clear automation at this step
                  float[] arr = cM.getAutomationArray(autoParam);
                  if (arr != null && colIdx < arr.length) {
                    arr[colIdx] = -1f;
                    refresh();
                  }
                } else {
                  // Set automation value = center of band
                  float val = (rowIdx * 16 + 8) / 127.0f;
                  cM.setAutomation(autoParam, colIdx, val);
                  automationDragging = true;
                  refresh();
                }
              }
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
              automationDragging = false;
            }
          });

          cell.addMouseMotionListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseDragged(java.awt.event.MouseEvent e) {
              if (!automationDragging || projectModel == null) return;
              org.chuck.deluge.model.TrackModel tM = projectModel.getTracks().get(editedModelTrack);
              int acIdx2 = tM.getActiveClipIndex();
              if (acIdx2 < 0 || acIdx2 >= tM.getClips().size()) return;
              org.chuck.deluge.model.ClipModel cM = tM.getClips().get(acIdx2);
              float val = (rowIdx * 16 + 8) / 127.0f;
              cM.setAutomation(autoParam, colIdx, val);
              refresh();
            }
          });

          rowPanel.add(cell);
          rowPanel.add(Box.createHorizontalStrut(5));
        }
        add(rowPanel);
      }
    } else if (viewMode == GridViewMode.CLIP) {
      // ===== CLIP MODE: scrollable voice rows + fixed MACROS/SLIDERS/KEYBOARD =====

      // Section 1: Track info header
      if (editedModelTrack < projectModel.getTracks().size()) {
        org.chuck.deluge.model.TrackModel curTrack = projectModel.getTracks().get(editedModelTrack);
        JPanel headerRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        headerRow.setBackground(new Color(0x15, 0x15, 0x15));
        JLabel headerLabel = new JLabel("Editing: " + curTrack.getName() + " (" + voiceRowCount + " voices)  [" + gridMode.name().replace('_', ' ') + "]");
        headerLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        headerLabel.setForeground(new Color(0x00, 0xff, 0xcc));
        headerRow.add(headerLabel);

        // Scroll up/down buttons
        if (voiceRowCount > gridMode.rows) {
          JButton upBtn = new JButton("\u25B2");
          upBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
          upBtn.setMargin(new Insets(0, 4, 0, 4));
          upBtn.setToolTipText("Scroll up");
          upBtn.setEnabled(scrollOffset > 0);
          upBtn.addActionListener(e -> { scrollOffset = Math.max(0, scrollOffset - 1); refresh(); });
          headerRow.add(upBtn);

          JLabel rowCountLabel = new JLabel((scrollOffset + 1) + "-" + Math.min(scrollOffset + gridMode.rows, voiceRowCount) + " / " + voiceRowCount);
          rowCountLabel.setForeground(Color.LIGHT_GRAY);
          rowCountLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
          headerRow.add(rowCountLabel);

          JButton downBtn = new JButton("\u25BC");
          downBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
          downBtn.setMargin(new Insets(0, 4, 0, 4));
          downBtn.setToolTipText("Scroll down");
          int maxOff = voiceRowCount - gridMode.rows;
          downBtn.setEnabled(scrollOffset < maxOff);
          downBtn.addActionListener(e -> { scrollOffset = Math.min(maxOff, scrollOffset + 1); refresh(); });
          headerRow.add(downBtn);
        } else {
          JLabel rowCountLabel = new JLabel("" + voiceRowCount + " voices");
          rowCountLabel.setForeground(Color.LIGHT_GRAY);
          rowCountLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
          headerRow.add(rowCountLabel);
        }
        // Horizontal scroll buttons for CLIP mode
        int trackLenH = bridge != null ? bridge.getTrackLength(baseTrackId) : stepCount;
        if (trackLenH > stepCount) {
          headerRow.add(Box.createHorizontalStrut(20));
          JButton leftBtn = new JButton("\u25C0");
          leftBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
          leftBtn.setMargin(new Insets(0, 4, 0, 4));
          leftBtn.setToolTipText("Scroll steps left");
          leftBtn.setEnabled(scrollOffsetX > 0);
          int maxOffX = trackLenH - stepCount;
          leftBtn.addActionListener(e -> {
            scrollOffsetX = Math.max(0, scrollOffsetX - 1);
            int maxX = (bridge != null ? bridge.getTrackLength(baseTrackId) : stepCount) - stepCount;
            if (scrollOffsetX > maxX) scrollOffsetX = maxX;
            if (scrollOffsetX < 0) scrollOffsetX = 0;
            refresh();
          });
          headerRow.add(leftBtn);
          JLabel stepLabel = new JLabel(
              (scrollOffsetX + 1) + "-" + Math.min(scrollOffsetX + stepCount, trackLenH) + " / " + trackLenH);
          stepLabel.setForeground(Color.LIGHT_GRAY);
          stepLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
          headerRow.add(stepLabel);
          JButton rightBtn = new JButton("\u25B6");
          rightBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
          rightBtn.setMargin(new Insets(0, 4, 0, 4));
          rightBtn.setToolTipText("Scroll steps right");
          rightBtn.setEnabled(scrollOffsetX < maxOffX);
          rightBtn.addActionListener(e -> {
            scrollOffsetX = Math.min(maxOffX, scrollOffsetX + 1);
            refresh();
          });
          headerRow.add(rightBtn);
        }
        add(headerRow);
      }

      // Section 2: Scrollable voice rows — always show gridMode.rows slots in the viewport
      JPanel voicePanel = new JPanel();
      voicePanel.setLayout(new BoxLayout(voicePanel, BoxLayout.Y_AXIS));
      for (int v = 0; v < gridMode.rows; v++) {
        int modelRow = scrollOffset + v;
        if (modelRow < voiceRowCount) {
          JPanel row = buildVoiceRow(modelRow, v, padSz, tracks);
          voicePanel.add(row);
        } else {
          // Blank filler rows for viewport slots beyond actual voice count
          JPanel blankRow = new JPanel();
          blankRow.setPreferredSize(new Dimension(3000, padSz));
          blankRow.setMaximumSize(new Dimension(3000, padSz));
          blankRow.setBackground(new Color(0x22, 0x22, 0x22));
          voicePanel.add(blankRow);
        }
      }
      // Wrap voice rows in a JScrollPane when content exceeds viewport
      if (voiceRowCount > gridMode.rows) {
        JScrollPane voiceScroll = new JScrollPane(voicePanel);
        voiceScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        voiceScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        voiceScroll.setBorder(BorderFactory.createEmptyBorder());
        // Fix viewport height to show exactly gridMode.rows
        int viewH = gridMode.rows * (padSz + 2);
        voiceScroll.setPreferredSize(new Dimension(3000, viewH));
        voiceScroll.setMaximumSize(new Dimension(3000, viewH));
        add(voiceScroll);
      } else {
        add(voicePanel);
      }

      // Section 3: Fixed rows — MACROS, SLIDERS, KEYBOARD
      for (int fixedRow = 8; fixedRow <= 10; fixedRow++) {
        add(buildFixedRow(fixedRow, padSz));
      }

    } else {
      // ===== SONG / ARRANGEMENT: gridMode.rows + 3 fixed rows (MACROS/SLIDERS/KEYBOARD) =====
      columnCount = gridMode.columns + 2; // Use grid mode column count + MUTE/SOLO
      int songVoiceRows = gridMode.rows; // always draw full viewport slots

      // ── Section bar (A-Z) for SONG mode ──
      if (viewMode == GridViewMode.SONG) {
        JPanel sectionBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        sectionBar.setBackground(new Color(0x15, 0x15, 0x15));
        JLabel secLabel = new JLabel("SECTION:");
        secLabel.setForeground(Color.LIGHT_GRAY);
        secLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
        sectionBar.add(secLabel);
        java.util.List<SongSection> sections = getProjectModel().getSongSections();
        for (int i = 0; i < 26; i++) {
          String letter = String.valueOf((char) ('A' + i));
          JButton btn = new JButton(letter);
          btn.setFont(new Font("Monospaced", Font.PLAIN, 10));
          btn.setMargin(new Insets(0, 4, 0, 4));
          int sectionIdx = i;
          btn.addActionListener(e -> activateSection(sectionIdx));
          sectionBar.add(btn);
        }
        add(sectionBar);
      }

      for (int t = 0; t < songVoiceRows + 3; t++) {

      JPanel rowPanel = new JPanel();
      rowPanel.setLayout(new BoxLayout(rowPanel, BoxLayout.X_AXIS));
      rowPanel.setBackground(new Color(0x22, 0x22, 0x22));
      rowPanel.setPreferredSize(new Dimension(3000, padSz));
      rowPanel.setMinimumSize(new Dimension(3000, padSz));
      rowPanel.setMaximumSize(new Dimension(3000, padSz));

      final int currentTrack = t;
      if (t < tracks.size()) {
        String hex = tracks.get(t).getColourHex();
        if (hex != null && hex.startsWith("0x")) {
          try {
            int rgb = Integer.decode(hex.substring(0, 8)); // strip alpha if 8 chars
            trackColors[t % trackColors.length] = new Color(rgb);
          } catch (Exception e) {
            LOG.warning("Bad color hex for track " + t + ": " + e.getMessage());
          }
        }
      }
      String trackName;
      if (t < songVoiceRows && viewMode == GridViewMode.CLIP && projectModel != null
          && editedModelTrack < projectModel.getTracks().size()) {
        org.chuck.deluge.model.TrackModel rowTrack = projectModel.getTracks().get(editedModelTrack);
        if (rowTrack instanceof org.chuck.deluge.model.KitTrackModel kit) {
          // Kit CLIP rows show the individual sound name
          java.util.List<org.chuck.deluge.model.KitTrackModel.KitSound> sounds = kit.getSounds();
          trackName = (t < sounds.size()) ? sounds.get(t).getName() : rowTrack.getName();
        } else {
          // Synth CLIP: row 0 shows track name, rows 1-7 show pitch
          trackName = (t == 0) ? rowTrack.getName() : "-" + t + "st";
        }
      } else {
        trackName = (t < tracks.size()) ? tracks.get(t).getName() : "EMPTY " + (t + 1);
      }
      if (t == songVoiceRows) trackName = "MACROS";
      if (t == songVoiceRows + 1) trackName = "SLIDERS";
      if (t == songVoiceRows + 2) trackName = "KEYBOARD";


      final int trk = currentTrack;
      final String tName = trackName;
      JLabel label = new JLabel(tName);
      int lw = Math.max(60, Math.min(140, getWidth() / 12));
      label.setPreferredSize(new Dimension(lw, 30));
      label.setMinimumSize(new Dimension(lw, 30));
      label.setMaximumSize(new Dimension(lw, 30));

      label.setForeground(Color.LIGHT_GRAY);
      label.setCursor(new Cursor(Cursor.HAND_CURSOR));
      label.addMouseListener(
          new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
              if (e.isShiftDown()) {
                isOneShotTrack[trk] = !isOneShotTrack[trk];
                label.setText(isOneShotTrack[trk] ? tName + " (1SH)" : tName);
                return;
              }
              if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                showTrackContextMenu(label, e.getX(), e.getY(), trk);
                return;
              }
              if (onEditRequest != null) {
                onEditRequest.accept(trk, 0);
              }
            }
          });

      rowPanel.add(label);

      // ⚙ config button and length badge for real tracks; blank spacer for all others
      if (t < tracks.size() && t < songVoiceRows) {
        org.chuck.deluge.model.TrackModel track = tracks.get(t);

        JButton cfgBtn = new JButton("⚙");
        cfgBtn.setPreferredSize(new Dimension(28, 26));
        cfgBtn.setMinimumSize(new Dimension(28, 26));
        cfgBtn.setMaximumSize(new Dimension(28, 26));
        cfgBtn.setMargin(new Insets(0, 0, 0, 0));
        cfgBtn.setFont(new Font("SansSerif", Font.PLAIN, 12));
        cfgBtn.setBackground(new Color(0x33, 0x33, 0x33));
        cfgBtn.setForeground(new Color(0x00, 0xff, 0xcc));
        cfgBtn.setToolTipText("Configure track");
        cfgBtn.addActionListener(e -> {
          Frame owner = (Frame) javax.swing.SwingUtilities.getWindowAncestor(SwingGridPanel.this);
          if (track instanceof org.chuck.deluge.model.KitTrackModel kitTrack) {
            new SwingKitConfigDialog(owner, kitTrack, vm, bridge).setVisible(true);
          } else if (track instanceof org.chuck.deluge.model.SynthTrackModel synthTrack) {
            new SwingSynthConfigDialog(owner, synthTrack, vm, bridge, trk).setVisible(true);
          }
        });
        rowPanel.add(Box.createHorizontalStrut(3));
        rowPanel.add(cfgBtn);

        int stepLen = (bridge != null) ? bridge.getTrackLength(trk) : 16;
        JLabel lenBadge = new JLabel("[" + stepLen + "]");
        lenBadge.setPreferredSize(new Dimension(36, 26));
        lenBadge.setMinimumSize(new Dimension(36, 26));
        lenBadge.setMaximumSize(new Dimension(36, 26));
        lenBadge.setFont(new Font("Monospaced", Font.BOLD, 11));
        lenBadge.setForeground(stepLen == 16 ? Color.GRAY : new Color(0xff, 0xcc, 0x00));
        lenBadge.setToolTipText("Track length (right-click to change)");
        lenBadge.setCursor(new Cursor(Cursor.HAND_CURSOR));
        lenBadge.addMouseListener(new java.awt.event.MouseAdapter() {
          @Override
          public void mouseClicked(java.awt.event.MouseEvent e) {
            if (javax.swing.SwingUtilities.isRightMouseButton(e) || e.getClickCount() == 2) {
              String input = JOptionPane.showInputDialog(
                  SwingGridPanel.this, "Track length (1-64):", stepLen);
              if (input != null) {
                try {
                  int newLen = Integer.parseInt(input.trim());
                  if (newLen >= 1 && newLen <= BridgeContract.STEPS) {
                    bridge.setTrackLength(trk, newLen);
                    refresh();
                  }
                } catch (NumberFormatException ignored) {}
              }
            }
          }
        });
        rowPanel.add(Box.createHorizontalStrut(2));
        rowPanel.add(lenBadge);
      } else {
        // 3 + 28 + 2 + 36 = 69px spacer to keep columns aligned
        rowPanel.add(Box.createRigidArea(new Dimension(69, 1)));
      }

      rowPanel.add(Box.createHorizontalStrut(5));

      VUMeterPanel vu = new VUMeterPanel();
      vu.setPreferredSize(new Dimension(12, padSz));
      vu.setMaximumSize(new Dimension(12, padSz));
      rowPanel.add(vu);
      rowPanel.add(Box.createHorizontalStrut(5));

      Timer vuTimer =
          new Timer(
              33,
              ev -> {
                vuLevels[trk] *= 0.80; // decay
                vu.setLvl(vuLevels[trk]);
              });
      vuTimer.start();

      for (int c = 0; c < columnCount; c++) {
        final int slot = c;
        final int trkId = t;
        final int colId = c;

        JButton clipBtn;
        int macR = songVoiceRows, sliR = songVoiceRows + 1, keyR = songVoiceRows + 2;
        if (trkId == sliR && colId < 16) {
          clipBtn =
              new JButton() {
                @Override
                protected void paintComponent(Graphics g) {
                  super.paintComponent(g);
                  int h = getHeight();
                  int w = getWidth();
                  g.setColor(new Color(0x00, 0xff, 0xcc, 0xaa));
                  double val = (bridge != null) ? bridge.getVelocity(0, colId) : 0.5;
                  int barH = (int) (val * h);
                  g.fillRect(0, h - barH, w, barH);
                }
              };
        } else if (trkId == keyR && colId < 18) {
          clipBtn = new JButton();
        } else {
          clipBtn = new JButton();
        }

        clipBtn.setPreferredSize(new Dimension(padSz, padSz));
        clipBtn.setMinimumSize(new Dimension(padSz, padSz));
        clipBtn.setMaximumSize(new Dimension(padSz, padSz));
        clipBtn.setMargin(new Insets(0, 0, 0, 0));

        pads[t][c] = clipBtn;

        if (t == macR) {
          if (c < 16) {
            String[] allParams = {
              "LEVEL", "PAN", "PITCH", "FILTER", "RESONANCE", "OSC1", "OSC2", "LFO",
              "MOD FX", "DELAY", "REVERB", "STUTTER", "PROBABILITY", "GATE", "VELOCITY", "SAMPLE"
            };
            clipBtn.setText("<html><center><b>" + allParams[c] + "</b></center></html>");
            clipBtn.setBackground(new Color(0x33, 0x33, 0x33));
            clipBtn.setForeground(Color.LIGHT_GRAY);
            clipBtn.setFont(new Font("SansSerif", Font.BOLD, padSz > 70 ? 14 : 10));
          } else {
            clipBtn.setBackground(new Color(0x1a, 0x1a, 0x1a));
            clipBtn.setEnabled(false);
          }
        } else if (trkId == sliR) {
          if (colId < 16) {

            clipBtn.setPreferredSize(new Dimension(padSz, padSz));
            clipBtn.setMinimumSize(new Dimension(padSz, padSz));
            clipBtn.setMaximumSize(new Dimension(padSz, padSz));

            clipBtn.addMouseMotionListener(
                new java.awt.event.MouseAdapter() {
                  @Override
                  public void mouseDragged(java.awt.event.MouseEvent e) {
                    double v = 1.0 - (double) e.getY() / pads[sliR][colId].getHeight();
                    v = Math.max(0.0, Math.min(1.0, v));
                    bridge.setVelocity(0, colId, v);
                    pads[sliR][colId].repaint();
                  }

                  @Override
                  public void mousePressed(java.awt.event.MouseEvent e) {
                    double v = 1.0 - (double) e.getY() / pads[sliR][colId].getHeight();
                    v = Math.max(0.0, Math.min(1.0, v));
                    bridge.setVelocity(0, colId, v);
                    pads[sliR][colId].repaint();
                  }
                });

          } else {
            clipBtn.setBackground(new Color(0x1a, 0x1a, 0x1a));
            clipBtn.setEnabled(false);
          }
        } else if (trkId == keyR) {
          if (colId < 18) {
            int note = 48 + colId; // starting at C3
            boolean isBlack =
                (colId % 12 == 1
                    || colId % 12 == 3
                    || colId % 12 == 6
                    || colId % 12 == 8
                    || colId % 12 == 10);

            clipBtn.setBackground(isBlack ? new Color(0x33, 0x33, 0x33) : Color.WHITE);
            clipBtn.setForeground(isBlack ? Color.WHITE : Color.BLACK);
            clipBtn.setText(String.valueOf(note));
            clipBtn.setFont(new Font("SansSerif", Font.BOLD, padSz > 70 ? 14 : 10));

            clipBtn.addActionListener(
                e -> {
                  try {
                    org.chuck.core.ChuckEvent noteEv =
                        (org.chuck.core.ChuckEvent) vm.getGlobalObject("g_ck_noteOn");
                    if (noteEv != null) {
                      org.chuck.core.ChuckArray pitchArr =
                          (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_PITCH);
                      pitchArr.setInt(0, (long) (note - 60));
                      noteEv.broadcast();
                    }
                  } catch (Exception ex) {
                    LOG.warning("Keyboard note broadcast failed (keyboard row): " + ex.getMessage());
                  }
                });
          }
        } else {

          if (viewMode == GridViewMode.CLIP) {
            double vel = bridge != null ? bridge.getVelocity(baseTrackId + trk, colId) : 0.8;
            double prob = bridge != null ? bridge.getStepProbability(baseTrackId + trk, colId) : 1.0;
            clipBtn.setText(
                "<html><font size='3'>Pi:"
                    + (currentTrack)
                    + "<br>Ve:" + String.format("%.1f", vel)
                    + "<br>Pr:" + String.format("%.1f", prob)
                    + "<br>Ga:1</font></html>");
          } else if (viewMode == GridViewMode.ARRANGEMENT) {
            String tn =
                (currentTrack < tracks.size()) ? tracks.get(currentTrack).getName() : "EMPTY";
            clipBtn.setText(
                "<html><center><font size='3'>"
                    + tn
                    + "<br><b>Bar "
                    + (c + 1)
                    + "</b></font></center></html>");
          } else {
            if (t < tracks.size() && c < tracks.get(t).getClips().size()) {
              clipBtn.setText(
                  "<html><center><font size='3'>"
                      + tracks.get(t).getClips().get(c).getName()
                      + "</font></center></html>");
            } else {
              clipBtn.setText("PAD " + (c + 1));
            }
          }
        }

        boolean hasClip = false;
        if (t < tracks.size()) {
          org.chuck.deluge.model.TrackModel track = tracks.get(t);
          if (c < track.getClips().size()) {
            hasClip = true;
          }
        }

        if (colId == 16) {
          final int engineRow = baseTrackId + trk;
          clipBtn.setText("MUTE");
          clipBtn.setBackground(
              bridge.getMute(engineRow) ? Color.RED : new Color(0x33, 0x33, 0x33));
          clipBtn.addActionListener(
              e -> {
                if ((e.getModifiers() & java.awt.event.ActionEvent.SHIFT_MASK) != 0) {
                  // Clear Sequence row
                  for (int s = 0; s < stepCount; s++) {
                    bridge.setStep(engineRow, s, false);
                  }
                  refresh();
                  return;
                }
                boolean isMuted = bridge.getMute(engineRow);
                bridge.setMute(engineRow, !isMuted);
                clipBtn.setBackground(!isMuted ? Color.RED : new Color(0x33, 0x33, 0x33));
              });
        } else if (colId == columnCount - 1) {
          clipBtn.setText("SOLO");
          clipBtn.setBackground(soloRow == trk ? Color.GREEN : new Color(0x33, 0x33, 0x33));

          clipBtn.addActionListener(
              e -> {
                if (viewMode == GridViewMode.CLIP) {
                  // Audition the row sound immediately
                  vm.setGlobalInt(BridgeContract.G_PREVIEW_TRACK, (long) trk);
                  vm.broadcastGlobalEvent(BridgeContract.E_PREVIEW);

                  // Toggle solo: solo this row or clear solo
                  if (soloRow == trk) {
                    soloRow = -1;
                    // Unmute all rows
                    for (int i = 0; i < 11; i++) bridge.setMute(baseTrackId + i, false);
                  } else {
                    soloRow = trk;
                    // Mute all other rows, unmute this one
                    for (int i = 0; i < 11; i++) {
                      bridge.setMute(baseTrackId + i, i != trk);
                    }
                  }
                  refresh();
                  return;
                }
                if (onEditRequest != null) {
                  onEditRequest.accept(trk, 0);
                }
              });
        } else {
          if (viewMode == GridViewMode.CLIP) {
            boolean stepState = bridge.getStep(baseTrackId + trk, c);
            double vel = bridge.getVelocity(baseTrackId + trk, c);
            clipBtn.setBackground(
                stepState ? velocityBlend(trackColors[currentTrack], vel) : new Color(0x33, 0x33, 0x33));
          } else if (viewMode == GridViewMode.SONG && t < tracks.size() && colId < 16) {
            // SONG visual states: playing (track color), queued (amber), stopped (dark), empty (very dark)
            long launchQ = bridge != null ? bridge.getLaunchQueue(t) : -1L;
            long currentClip = bridge != null ? bridge.getCurrentClip(t) : 0L;
            if (launchQ == colId) {
              clipBtn.setBackground(new Color(0xff, 0xaa, 0x00)); // amber = queued
            } else if (currentClip == colId) {
              clipBtn.setBackground(trackColors[t % trackColors.length]); // playing
            } else if (hasClip) {
              clipBtn.setBackground(new Color(0x33, 0x44, 0x55)); // stopped
            } else {
              clipBtn.setBackground(new Color(0x1a, 0x1a, 0x1a)); // empty
            }
          } else {
            if (hasClip) {
              clipBtn.setBackground(trackColors[currentTrack]);
            } else {
              clipBtn.setBackground(new Color(0x33, 0x33, 0x33));
            }
          }

          if (viewMode == GridViewMode.CLIP) {
            clipBtn.addMouseListener(
                new java.awt.event.MouseAdapter() {
                  @Override
                  public void mousePressed(java.awt.event.MouseEvent e) {
                    if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                      int engineRow = baseTrackId + trk;
                      double curVel = bridge.getVelocity(engineRow, colId);
                      StepPropertiesDialog dlg = new StepPropertiesDialog(
                          (Frame) javax.swing.SwingUtilities.getWindowAncestor(SwingGridPanel.this),
                          (int)(curVel * 100));
                      dlg.setVisible(true);
                      int newVel = dlg.getVelocity();
                      if (newVel != (int)(curVel * 100)) {
                        bridge.setVelocity(engineRow, colId, newVel / 100.0);
                        if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
                          org.chuck.deluge.model.TrackModel tModel =
                              projectModel.getTracks().get(editedModelTrack);
                          if (activeClipId < tModel.getClips().size()) {
                            org.chuck.deluge.model.ClipModel cModel =
                                tModel.getClips().get(activeClipId);
                            boolean st = bridge.getStep(engineRow, colId);
                            double prob = bridge.getStepProbability(engineRow, colId);
                            cModel.setStep(trk, colId,
                                new org.chuck.deluge.model.StepData(st, newVel / 100.0f, 0.5f, (float)prob, 0));
                          }
                        }
                        refresh();
                      }
                    } else if (javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                      boolean isSynthMode = bridge.getTrackType(baseTrackId) == 1;

                      int trackType = bridge.getTrackType(trk);
                      if (trackType == 2) {
                        boolean st = bridge.getStep(baseTrackId + trk, colId);
                        bridge.setStep(baseTrackId + trk, colId, !st);
                        if (!st) {
                          if (finalMidiOut != null) {
                            try {
                              finalMidiOut.sendMessage(
                                  new byte[] {(byte) 0x90, (byte) (60 + trk), (byte) 100});
                            } catch (Exception ex) {
                              LOG.warning("MIDI send failed (kit grid): " + ex.getMessage());
                            }
                          }
                        }
                        clipBtn.setBackground(
                            !st
                                ? trackColors[6]
                                : new Color(0x33, 0x33, 0x33)); // Blue for MIDI Track

                        // Write to model so changes survive view switches
                        if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
                          org.chuck.deluge.model.TrackModel tModel =
                              projectModel.getTracks().get(editedModelTrack);
                          if (activeClipId < tModel.getClips().size()) {
                            org.chuck.deluge.model.ClipModel cModel =
                                tModel.getClips().get(activeClipId);
                            cModel.setStep(
                                trk, colId,
                                new org.chuck.deluge.model.StepData(
                                    !st, 0.8f, 0.5f, 1.0f, 0));
                          }
                        }
                      } else if (isSynthMode) {
                        // Each visual row toggles its own engine row independently (chords)
                        int engineRow = baseTrackId + trk;
                        boolean stepState = bridge.getStep(engineRow, colId);
                        bridge.setStep(engineRow, colId, !stepState);
                        double velS = bridge.getVelocity(engineRow, colId);
                        clipBtn.setBackground(
                            !stepState ? velocityBlend(trackColors[trk], velS) : new Color(0x33, 0x33, 0x33));

                        // Audition via engine preview (wrap to voice slot)
                        int slot = baseTrackId + (trk % 8);
                        vm.setGlobalFloat("g_preview_pitch", (float) ((24 - 1) - trk));
                        vm.setGlobalInt(BridgeContract.G_PREVIEW_TRACK, (long) slot);
                        vm.broadcastGlobalEvent(BridgeContract.E_PREVIEW);

                        // Write to model so changes survive view switches
                        if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
                          org.chuck.deluge.model.TrackModel tModel =
                              projectModel.getTracks().get(editedModelTrack);
                          if (activeClipId < tModel.getClips().size()) {
                            org.chuck.deluge.model.ClipModel cModel =
                                tModel.getClips().get(activeClipId);
                            double curVel = bridge.getVelocity(engineRow, colId);
                            double curProb = bridge.getStepProbability(engineRow, colId);
                            cModel.setStep(
                                trk,
                                colId,
                                new org.chuck.deluge.model.StepData(
                                    !stepState, (float)curVel, 0.5f, (float)curProb, 0));
                          }
                        }
                      } else {
                        boolean stepState = bridge.getStep(baseTrackId + trk, colId);
                        bridge.setStep(baseTrackId + trk, colId, !stepState);
                        double velK = bridge.getVelocity(baseTrackId + trk, colId);
                        clipBtn.setBackground(
                            !stepState ? velocityBlend(trackColors[trk], velK) : new Color(0x33, 0x33, 0x33));
                        if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
                          org.chuck.deluge.model.TrackModel tModel =
                              projectModel.getTracks().get(editedModelTrack);
                          if (activeClipId < tModel.getClips().size()) {
                            org.chuck.deluge.model.ClipModel cModel =
                                tModel.getClips().get(activeClipId);
                            double curVel = bridge.getVelocity(baseTrackId + trk, colId);
                            double curProb = bridge.getStepProbability(baseTrackId + trk, colId);
                            cModel.setStep(
                                trk,
                                colId,
                                new org.chuck.deluge.model.StepData(
                                    !stepState, (float)curVel, 0.5f, (float)curProb, 0));
                          }
                        }

                        if (!stepState) {
                          // Audition the kit sample via engine preview event
                          vm.setGlobalInt(BridgeContract.G_PREVIEW_TRACK, (long) (baseTrackId + trk));
                          vm.broadcastGlobalEvent(BridgeContract.E_PREVIEW);
                          if (activeStutterTimer != null) activeStutterTimer.stop();
                          activeStutterTimer =
                              new Timer(
                                  150,
                                  ev -> {
                                    vm.setGlobalInt(BridgeContract.G_PREVIEW_TRACK, (long) (baseTrackId + trk));
                                    vm.broadcastGlobalEvent(BridgeContract.E_PREVIEW);
                                  });
                          activeStutterTimer.start();
                        }
                      }
                    }
                  }

                  @Override
                  public void mouseReleased(java.awt.event.MouseEvent e) {
                    if (activeStutterTimer != null) {
                      activeStutterTimer.stop();
                      activeStutterTimer = null;
                    }
                  }
                });

          } else if (viewMode == GridViewMode.SONG) {
            clipBtn.addMouseListener(
                new java.awt.event.MouseAdapter() {
                  @Override
                  public void mousePressed(java.awt.event.MouseEvent e) {
                    if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                      new TrackInspectorDialog(
                          (Frame) javax.swing.SwingUtilities.getWindowAncestor(SwingGridPanel.this),
                          currentTrack,
                          tracks,
                          SwingGridPanel.this::refresh)
                          .setVisible(true);
                    }
                  }
                });
          } else if (viewMode == GridViewMode.ARRANGEMENT) {
            clipBtn.addMouseListener(
                new java.awt.event.MouseAdapter() {
                  @Override
                  public void mousePressed(java.awt.event.MouseEvent e) {
                    if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                      new BarAutomationDialog(
                          (Frame)
                              javax.swing.SwingUtilities.getWindowAncestor(SwingGridPanel.this),
                          slot)
                          .setVisible(true);
                    }
                  }
                });
          }

          if (viewMode == GridViewMode.SONG && t < tracks.size() && colId < 16) {
            final int clipCol = colId;
            final org.chuck.deluge.model.TrackModel songTrack = tracks.get(t);
            clipBtn.addMouseListener(
                new java.awt.event.MouseAdapter() {
                  @Override
                  public void mousePressed(java.awt.event.MouseEvent e) {
                    if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                      if (clipCol < songTrack.getClips().size()) {
                        showClipContextMenu(clipBtn, e.getX(), e.getY(), songTrack, clipCol);
                      }
                    } else {
                      if (clipCol >= songTrack.getClips().size()) {
                        String name = "CLIP " + (clipCol + 1);
                        songTrack.addClip(
                            new org.chuck.deluge.model.ClipModel(
                                name,
                                songTrack.getClips().isEmpty()
                                    ? 8
                                    : songTrack.getClips().get(0).getRowCount(),
                                16));
                        fireProjectChanged();
                      }
                    }
                  }
                });
          }

          clipBtn.addActionListener(
              e -> {
                if (viewMode == GridViewMode.SONG) {
                  if ((e.getModifiers() & java.awt.event.ActionEvent.SHIFT_MASK) != 0) {
                    return;
                  }
                  if (colId < 16 && trkId < tracks.size()) {
                    bridge.setLaunchQueue(trkId, colId);
                  }
                  clipBtn.setBackground(new Color(0xff, 0xaa, 0x00)); // amber = queued
                  refresh();
                } else if (viewMode == GridViewMode.ARRANGEMENT) {
                  // Toggle Linear arrangement playback bar state
                  if (clipBtn.getBackground().equals(trackColors[currentTrack])) {
                    clipBtn.setBackground(new Color(0x33, 0x33, 0x33));
                  } else {
                    clipBtn.setBackground(trackColors[currentTrack]);
                  }
                }
              });
        }

        if (c == columnCount - 2) {
          rowPanel.add(Box.createHorizontalStrut(20));
        }
        rowPanel.add(clipBtn);
        rowPanel.add(Box.createHorizontalStrut(5));
      }
      add(rowPanel);
    }
    } // end else (SONG/ARRANGEMENT)
    columnCount = savedColCount; // restore CLIP-mode columnCount

    if (viewMode == GridViewMode.CLIP) {
      class PianoRollComponent extends JComponent {
        public PianoRollComponent() {
          setPreferredSize(new Dimension(2600, 120));
          setMaximumSize(new Dimension(2600, 120));
        }

        @Override
        protected void paintComponent(Graphics g) {
          Graphics2D g2 = (Graphics2D) g;
          int gridX = 160; // Pad 1 starts here

          // 18 pads = 16 * 125 + 20(spacer) + 2 * 125 = 2270 pixels total
          double totalWidth = 18 * 125.0 + 20.0;
          double keyW = totalWidth / 28.0;
          int keyH = 110;

          // 28 White keys
          for (int i = 0; i < 28; i++) {
            int x = (int) (gridX + i * keyW);
            int nextX = (int) (gridX + (i + 1) * keyW);
            int kw = (nextX - x) - 2;

            g2.setColor(Color.WHITE);
            g2.fillRect(x, 0, kw, keyH);
            g2.setColor(Color.BLACK);
            g2.drawRect(x, 0, kw, keyH);
          }

          // Black keys
          int[] blackKeyOffsets = {
            0, 1, 3, 4, 5, 7, 8, 10, 11, 12, 14, 15, 17, 18, 19, 21, 22, 24, 25, 26
          };
          for (int offsetKey : blackKeyOffsets) {
            int x = (int) (gridX + offsetKey * keyW);
            int nextX = (int) (gridX + (offsetKey + 1) * keyW);
            int kw = nextX - x;
            int bx = x + kw - (int) (keyW / 3.0);

            g2.setColor(new Color(0x1a, 0x1a, 0x1a));
            g2.fillRect(bx, 0, (int) (keyW / 2.0), keyH / 2);
          }

          // Draw QWERTY assistants
          g2.setFont(new Font("SansSerif", Font.BOLD, 14));
          String[] whiteQwerty = {"Z", "X", "C", "V", "B", "N", "M"};
          for (int i = 0; i < 7; i++) {
            int x = (int) (gridX + i * keyW);
            g2.setColor(Color.GRAY);
            g2.drawString(whiteQwerty[i], x + 10, keyH - 15);
          }

          String[] blackQwerty = {"S", "D", "", "G", "H", "J"};
          for (int i = 0; i < blackKeyOffsets.length; i++) {
            if (i < 6 && !blackQwerty[i].isEmpty()) {
              int offsetKey = blackKeyOffsets[i];
              int x = (int) (gridX + offsetKey * keyW);
              int nextX = (int) (gridX + (offsetKey + 1) * keyW);
              int kw = nextX - x;
              int bx = x + kw - (int) (keyW / 3.0);
              g2.setColor(Color.WHITE);
              g2.drawString(blackQwerty[i], bx + 2, (keyH / 2) - 5);
            }
          }
        }
      }
      add(Box.createVerticalStrut(10));
      add(new PianoRollComponent());
    }

    revalidate();
    repaint();

    org.rtmidijava.RtMidiOut midiOut = null;
    try {
      midiOut = org.rtmidijava.RtMidiFactory.createDefaultOut();
      if (midiOut.getPortCount() > 0) {
        midiOut.openPort(0, "DelugeOut");
      }

    } catch (Exception ex) {
      LOG.warning("MIDI out init failed: " + ex.getMessage());
    }

    final int[] lastCol = {-1};
    this.finalMidiOut = midiOut;

    // Stop old timer to prevent leaks
    if (playheadTimer != null) playheadTimer.stop();

    playheadTimer =
        new Timer(
            100,
            e -> {
              int currentStep = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
              if (currentStep >= 0) {
                int activeCol = (currentStep % stepCount);
                // When horizontally scrolled, the timer maps engine columns to visible columns.
                // The background-sync loop below uses visual column indexing (pads[t][c]).
                // We need to drive bridge lookups with engine-step columns, not visual columns.
                // activeCol as computed above is fine for border highlighting — it maps the
                // engine step to the visible column where the playhead border should appear.
                int engineActiveCol;
                // For scrollOffsetX > 0: clip is wider than viewport; the engine active col
                // may fall outside the visible window. Map it:
                int trackLenTimer = bridge != null ? bridge.getTrackLength(baseTrackId) : stepCount;
                if (trackLenTimer > stepCount) {
                  int rawCol = currentStep % trackLenTimer;
                  engineActiveCol = rawCol;
                  int visualCol = rawCol - scrollOffsetX;
                  if (visualCol >= 0 && visualCol < stepCount) {
                    activeCol = visualCol;
                  } else {
                    activeCol = -1; // step is outside the visible window — hide border
                  }
                } else {
                  engineActiveCol = currentStep % stepCount;
                }

                int rows = (viewMode == GridViewMode.AUTOMATION) ? 8
                    : (viewMode == GridViewMode.CLIP) ? voiceRowCount : gridMode.rows;
                if (activeCol != lastCol[0]) {
                  lastCol[0] = activeCol;
                  if (activeCol == 0) {
                    for (int t = 0; t < rows; t++) {
                      if (isOneShotTrack[t] && currentStep >= stepCount) {
                        bridge.setMute(baseTrackId + t, true);
                      }
                    }
                  }

                  for (int t = 0; t < rows; t++) {
                    int engineRow = baseTrackId + (viewMode == GridViewMode.CLIP ? scrollOffset + t : t);
                    if (bridge.getStep(engineRow, engineActiveCol)) {
                      vuLevels[t] = 1.0; // Spike VU Meter!
                      if (finalMidiOut != null) {
                        try {
                          finalMidiOut.sendMessage(
                              new byte[] {(byte) 0x90, (byte) (36 + t * 2), (byte) 100});
                        } catch (Exception ex) {
                          LOG.warning("MIDI send failed (playhead): " + ex.getMessage());
                        }
                      }
                    }
                  }

                  // Sidechain Compressor Ducking tied to first engine row
                  if (bridge.getStep(baseTrackId, engineActiveCol)) {
                    for (int t = 1; t < rows; t++) {
                      final int trackIdx = t;
                      bridge.setTrackLevel(trackIdx, 0.15); // Duck

                      Timer duckRelease =
                          new Timer(
                              120,
                              ev -> {
                                bridge.setTrackLevel(trackIdx, 0.70); // Release / Restore
                              });
                      duckRelease.setRepeats(false);
                      duckRelease.start();
                    }
                  }
                }

                if (viewMode != GridViewMode.AUTOMATION) {
                for (int t = 0; t < rows; t++) {
                  int engineRow2 = baseTrackId + (viewMode == GridViewMode.CLIP ? scrollOffset + t : t);
                  int trackLenT = bridge != null ? bridge.getTrackLength(engineRow2) : stepCount;
                  for (int c = 0; c < stepCount; c++) {
                    if (pads[t][c] != null) {
                      // Map visual column to engine column when scrolled horizontally
                      int engineCol;
                      if (trackLenT > stepCount) {
                        engineCol = Math.min(c + scrollOffsetX, trackLenT - 1);
                      } else if (trackLenT > 0 && trackLenT < stepCount) {
                        engineCol = c % trackLenT;
                      } else {
                        engineCol = c;
                      }
                      if (c == activeCol) {
                        pads[t][c].setBorder(BorderFactory.createLineBorder(Color.YELLOW, 4));
                      } else {
                        pads[t][c].setBorder(UIManager.getBorder("Button.border"));
                      }
                      // Re-sync background from bridge so cell selection stays correct during playback
                      boolean stepActive = bridge.getStep(engineRow2, engineCol);
                      double velPb = bridge.getVelocity(engineRow2, engineCol);
                      pads[t][c].setBackground(
                          stepActive ? velocityBlend(trackColors[t % trackColors.length], velPb) : new Color(0x33, 0x33, 0x33));
                    }
                  }
                }
                } // end if (viewMode != AUTOMATION)
              }
            });
    playheadTimer.start();
  }

  /** Activate a song section by its index, queueing clips on each track. */
  private void activateSection(int idx) {
    java.util.List<SongSection> sections = projectModel.getSongSections();
    if (idx >= sections.size()) return;
    SongSection section = sections.get(idx);
    for (int t = 0; t < projectModel.getTracks().size() && t < BridgeContract.TRACKS; t++) {
      org.chuck.deluge.model.TrackModel track = projectModel.getTracks().get(t);
      for (int c = 0; c < track.getClips().size(); c++) {
        if (section.getPatternIds().contains(track.getClips().get(c).getName())) {
          bridge.setLaunchQueue(t, c);
          break;
        }
      }
    }
    refresh();
  }
}
