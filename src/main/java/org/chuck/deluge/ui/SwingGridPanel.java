package org.chuck.deluge.ui;

import java.awt.*;
import java.util.logging.Logger;
import javax.swing.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

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
    ARRANGEMENT
  }

  private GridViewMode viewMode = GridViewMode.SONG;

  private Color[] trackColors = {
    new Color(0x00, 0xff, 0xcc), // Cyan
    new Color(0xff, 0x33, 0xcc), // Magenta
    new Color(0x33, 0xff, 0x33), // Lime Green
    new Color(0xff, 0x99, 0x33), // Orange
    new Color(0xcc, 0x33, 0xff), // Purple
    new Color(0xff, 0xff, 0x33), // Yellow
    new Color(0x33, 0x99, 0xff), // Blue
    new Color(0xff, 0x33, 0x33), // Red
    new Color(0x33, 0x33, 0x33), // Dark Gray
    new Color(0x22, 0x22, 0x22), // Very Dark Gray
    new Color(0x15, 0x15, 0x15) // Almost Black
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

    final int currentTrack = modelRow;
    if (modelRow < tracks.size()) {
      String hex = tracks.get(modelRow).getColourHex();
      if (hex != null && hex.startsWith("0x")) {
        try {
          int rgb = Integer.decode(hex.substring(0, 8));
          trackColors[modelRow % trackColors.length] = new Color(rgb);
        } catch (Exception e) {
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
                SwingGridPanel.this, "Track length (1-64):", stepLen);
            if (input != null) {
              try {
                int newLen = Integer.parseInt(input.trim());
                if (newLen >= 1 && newLen <= 64) {
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
                  // Also write to debug file for offline inspection
                  try {
                    java.nio.file.Files.write(
                      java.nio.file.Paths.get("grid_debug.log"),
                      ("[grid] mPressed bVR: modelRow=" + modelRow + " visRow=" + visibleRow + " colId=" + colId + " t=" + e.getClickCount() + "\n").getBytes(),
                      java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                  } catch (Exception ignored) {}
                  if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                    // Step Properties dialog (unchanged)
                    JDialog dialog =
                        new JDialog(
                            (Frame)
                                javax.swing.SwingUtilities.getWindowAncestor(SwingGridPanel.this),
                            "Step Properties",
                            true);
                    dialog.setSize(1600, 450);
                    dialog.setLocationRelativeTo(SwingGridPanel.this);
                    dialog.setLayout(new GridBagLayout());
                    GridBagConstraints gc = new GridBagConstraints();
                    gc.fill = GridBagConstraints.HORIZONTAL;
                    gc.insets = new Insets(10, 15, 10, 15);
                    Font labelFont = new Font("SansSerif", Font.BOLD, 18);
                    Dimension sliderDim = new Dimension(1200, 50);
                    Dimension spinDim = new Dimension(80, 40);
                    gc.gridx = 0;
                    gc.gridy = 0;
                    JLabel l1 = new JLabel("Velocity:");
                    l1.setFont(labelFont);
                    dialog.add(l1, gc);
                    gc.gridx = 1;
                    JSlider velSlider = new JSlider(0, 100, 80);
                    velSlider.setPreferredSize(sliderDim);
                    dialog.add(velSlider, gc);
                    gc.gridx = 2;
                    JSpinner velSpin = new JSpinner(new SpinnerNumberModel(80, 0, 100, 1));
                    velSpin.setPreferredSize(spinDim);
                    dialog.add(velSpin, gc);
                    dialog.setVisible(true);
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
                          } catch (Exception ex) {}
                        }
                      }
                      clipBtn.setBackground(
                          !st ? trackColors[6] : new Color(0x33, 0x33, 0x33));
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
                      int engineRow = baseTrackId + modelRow;
                      boolean stepState = bridge.getStep(engineRow, activeCol);
                      bridge.setStep(engineRow, activeCol, !stepState);
                      double velS = bridge.getVelocity(engineRow, activeCol);
                      clipBtn.setBackground(
                          !stepState ? velocityBlend(trackColors[visibleRow % trackColors.length], velS) : new Color(0x33, 0x33, 0x33));
                      vm.setGlobalFloat("g_preview_pitch", (float) ((24 - 1) - modelRow));
                      vm.setGlobalInt(BridgeContract.G_PREVIEW_TRACK, (long) engineRow);
                      vm.broadcastGlobalEvent(BridgeContract.E_PREVIEW);
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
                      if (!stepState) {
                        vm.setGlobalInt(BridgeContract.G_PREVIEW_TRACK, (long) (baseTrackId + modelRow));
                        vm.broadcastGlobalEvent(BridgeContract.E_PREVIEW);
                        if (activeStutterTimer != null) activeStutterTimer.stop();
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
              });
        } else if (viewMode == GridViewMode.SONG) {
          clipBtn.addMouseListener(
              new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent e) {
                  if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                    JDialog dialog =
                        new JDialog(
                            (Frame)
                                javax.swing.SwingUtilities.getWindowAncestor(SwingGridPanel.this),
                            "Track Inspector",
                            true);
                    dialog.setSize(900, 550);
                    dialog.setLocationRelativeTo(SwingGridPanel.this);
                    JTabbedPane tabs = new JTabbedPane();
                    tabs.setFont(new Font("SansSerif", Font.BOLD, 22));
                    JPanel p1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 30, 30));
                    p1.setBackground(new Color(0x2b, 0x2b, 0x2b));
                    JLabel lP = new JLabel("Hot-Swap Patch Preset:");
                    lP.setFont(new Font("SansSerif", Font.BOLD, 18));
                    lP.setForeground(Color.WHITE);
                    JComboBox<String> cb = new JComboBox<>();
                    cb.setFont(new Font("SansSerif", Font.PLAIN, 18));
                    cb.setPreferredSize(new Dimension(400, 45));
                    p1.add(lP);
                    p1.add(cb);
                    tabs.addTab("PRESETS", p1);
                    JPanel p2 = new JPanel(new FlowLayout(FlowLayout.CENTER, 40, 50));
                    p2.setBackground(new Color(0x2b, 0x2b, 0x2b));
                    JButton cloneBtn = new JButton("Clone Clip Variant");
                    cloneBtn.setFont(new Font("SansSerif", Font.BOLD, 24));
                    cloneBtn.setPreferredSize(new Dimension(300, 80));
                    JButton clearBtn = new JButton("Export MIDI Sequence");
                    clearBtn.setFont(new Font("SansSerif", Font.BOLD, 24));
                    clearBtn.setPreferredSize(new Dimension(300, 80));
                    p2.add(cloneBtn);
                    p2.add(clearBtn);
                    tabs.addTab("CLIPBOARD", p2);
                    JPanel p3 = new JPanel(new GridBagLayout());
                    p3.setBackground(new Color(0x2b, 0x2b, 0x2b));
                    GridBagConstraints gcm = new GridBagConstraints();
                    gcm.fill = GridBagConstraints.HORIZONTAL;
                    gcm.insets = new Insets(25, 25, 25, 25);
                    gcm.gridx = 0;
                    gcm.gridy = 0;
                    JLabel vL = new JLabel("Channel Volume:");
                    vL.setFont(new Font("SansSerif", Font.BOLD, 20));
                    vL.setForeground(Color.WHITE);
                    p3.add(vL, gcm);
                    gcm.gridx = 1;
                    JSlider vS = new JSlider(0, 100, 80);
                    vS.setPreferredSize(new Dimension(400, 50));
                    vS.addChangeListener(ev -> System.out.println("Track " + modelRow + " Vol: " + vS.getValue()));
                    p3.add(vS, gcm);
                    tabs.addTab("MIXER", p3);
                    JPanel p4 = new JPanel(new FlowLayout(FlowLayout.LEFT, 30, 30));
                    p4.setBackground(new Color(0x2b, 0x2b, 0x2b));
                    JLabel lAlgo = new JLabel("Algorithm Map: [Op 4] \u279e [Op 3] \u279e [Op 2] \u279e Output");
                    lAlgo.setFont(new Font("SansSerif", Font.BOLD, 20));
                    lAlgo.setForeground(Color.ORANGE);
                    JLabel lRatio = new JLabel("Modulator Ratio (Harmonics):");
                    lRatio.setFont(new Font("SansSerif", Font.BOLD, 18));
                    lRatio.setForeground(Color.WHITE);
                    JSlider ratioSlider = new JSlider(1, 10, 1);
                    ratioSlider.setPreferredSize(new Dimension(300, 50));
                    p4.add(lAlgo);
                    p4.add(lRatio);
                    p4.add(ratioSlider);
                    tabs.addTab("FM OPERATORS", p4);
                    gcm.gridx = 0;
                    gcm.gridy = 1;
                    JLabel pL = new JLabel("Channel Panning:");
                    pL.setFont(new Font("SansSerif", Font.BOLD, 20));
                    pL.setForeground(Color.WHITE);
                    p3.add(pL, gcm);
                    gcm.gridx = 1;
                    JSlider pS = new JSlider(0, 100, 50);
                    pS.setPreferredSize(new Dimension(400, 50));
                    p3.add(pS, gcm);
                    tabs.addTab("MIXER", p3);
                    cloneBtn.addActionListener(ev -> {
                      if (modelRow < tracks.size()) {
                        org.chuck.deluge.model.TrackModel tModel = tracks.get(modelRow);
                        if (!tModel.getClips().isEmpty()) {
                          tModel.addClip(tModel.getClips().get(0));
                        }
                      }
                      dialog.dispose();
                      refresh();
                    });
                    cb.addActionListener(ev -> {
                      if (modelRow < tracks.size()) {
                        tracks.get(modelRow).setName((String) cb.getSelectedItem());
                      }
                      dialog.dispose();
                      refresh();
                    });
                    dialog.add(tabs);
                    dialog.setVisible(true);
                  }
                }
              });
        } else if (viewMode == GridViewMode.ARRANGEMENT) {
          clipBtn.addMouseListener(
              new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent e) {
                  if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                    JDialog dialog =
                        new JDialog(
                            (Frame)
                                javax.swing.SwingUtilities.getWindowAncestor(SwingGridPanel.this),
                            "Bar Automation",
                            true);
                    dialog.setSize(600, 350);
                    dialog.setLocationRelativeTo(SwingGridPanel.this);
                    dialog.setLayout(new GridLayout(3, 1, 20, 20));
                    dialog.add(new JLabel("  Timeline Bar " + (colId + 1) + " Automation:"));
                    dialog.add(new JCheckBox("Enable Low-Pass Filter Sweep"));
                    dialog.add(new JCheckBox("Trigger Volume Fade-In"));
                    dialog.setVisible(true);
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
                clipBtn.setBackground(Color.ORANGE);
                Timer timer = new Timer(100, null);
                final boolean[] flashState = {false};
                timer.addActionListener(ev -> {
                  int step = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
                  if (step == 0) {
                    clipBtn.setBackground(trackColors[visibleRow % trackColors.length]);
                    timer.stop();
                  } else {
                    flashState[0] = !flashState[0];
                    clipBtn.setBackground(flashState[0] ? Color.ORANGE : Color.LIGHT_GRAY);
                  }
                });
                timer.start();
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
  private JPanel buildFixedRow(int rowIdx, int padSz,
      java.util.List<org.chuck.deluge.model.TrackModel> tracks) {
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
            } catch (Exception ex) {}
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
      boolean isTriggered = (bridge != null) && bridge.getStep(engineRow, stepMod);
      if (isTriggered) {
        pads[t][stepMod].setBackground(Color.WHITE);
      } else if (pads[t][stepMod].getBackground().equals(Color.WHITE)) {
        // Restore to velocity-blended color
        double vel = bridge.getVelocity(engineRow, stepMod);
        boolean stepActive = bridge.getStep(engineRow, stepMod);
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

    if (viewMode == GridViewMode.CLIP) {
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
        add(buildFixedRow(fixedRow, padSz, tracks));
      }

    } else {
      // ===== SONG / ARRANGEMENT: gridMode.rows + 3 fixed rows (MACROS/SLIDERS/KEYBOARD) =====
      columnCount = gridMode.columns + 2; // Use grid mode column count + MUTE/SOLO
      int songVoiceRows = gridMode.rows; // always draw full viewport slots
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
                  if (newLen >= 1 && newLen <= 64) {
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
            final int colSlot = colId;

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
                      JDialog dialog =
                          new JDialog(
                              (Frame)
                                  javax.swing.SwingUtilities.getWindowAncestor(SwingGridPanel.this),
                              "Step Properties",
                              true);
                      dialog.setSize(1600, 450);
                      dialog.setLocationRelativeTo(SwingGridPanel.this);
                      dialog.setLayout(new GridBagLayout());

                      GridBagConstraints gc = new GridBagConstraints();
                      gc.fill = GridBagConstraints.HORIZONTAL;
                      gc.insets = new Insets(10, 15, 10, 15);

                      Font labelFont = new Font("SansSerif", Font.BOLD, 18);
                      Dimension sliderDim = new Dimension(1200, 50);
                      Dimension spinDim = new Dimension(80, 40);

                      gc.gridx = 0;
                      gc.gridy = 0;
                      JLabel l1 = new JLabel("Velocity:");
                      l1.setFont(labelFont);
                      dialog.add(l1, gc);
                      gc.gridx = 1;
                      JSlider velSlider = new JSlider(0, 100, 80);
                      velSlider.setPreferredSize(sliderDim);
                      dialog.add(velSlider, gc);
                      gc.gridx = 2;
                      JSpinner velSpin = new JSpinner(new SpinnerNumberModel(80, 0, 100, 1));
                      velSpin.setPreferredSize(spinDim);
                      dialog.add(velSpin, gc);

                      dialog.setVisible(true);
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

                        // Audition via engine preview
                        vm.setGlobalFloat("g_preview_pitch", (float) ((24 - 1) - trk));
                        vm.setGlobalInt(BridgeContract.G_PREVIEW_TRACK, (long) engineRow);
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
                      JDialog dialog =
                          new JDialog(
                              (Frame)
                                  javax.swing.SwingUtilities.getWindowAncestor(SwingGridPanel.this),
                              "Track Inspector",
                              true);
                      dialog.setSize(900, 550);
                      dialog.setLocationRelativeTo(SwingGridPanel.this);

                      JTabbedPane tabs = new JTabbedPane();
                      tabs.setFont(new Font("SansSerif", Font.BOLD, 22));

                      // Tab 1: Presets
                      JPanel p1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 30, 30));
                      p1.setBackground(new Color(0x2b, 0x2b, 0x2b));
                      JLabel lP = new JLabel("Hot-Swap Patch Preset:");
                      lP.setFont(new Font("SansSerif", Font.BOLD, 18));
                      lP.setForeground(Color.WHITE);
                      JComboBox<String> cb = new JComboBox<>();
                      cb.setFont(new Font("SansSerif", Font.PLAIN, 18));
                      cb.setPreferredSize(new Dimension(400, 45));
                      p1.add(lP);
                      p1.add(cb);
                      tabs.addTab("PRESETS", p1);

                      // Tab 2: Clipboard
                      JPanel p2 = new JPanel(new FlowLayout(FlowLayout.CENTER, 40, 50));
                      p2.setBackground(new Color(0x2b, 0x2b, 0x2b));
                      JButton cloneBtn = new JButton("Clone Clip Variant");
                      cloneBtn.setFont(new Font("SansSerif", Font.BOLD, 24));
                      cloneBtn.setPreferredSize(new Dimension(300, 80));
                      JButton clearBtn = new JButton("Export MIDI Sequence");
                      clearBtn.setFont(new Font("SansSerif", Font.BOLD, 24));
                      clearBtn.setPreferredSize(new Dimension(300, 80));
                      p2.add(cloneBtn);
                      p2.add(clearBtn);
                      tabs.addTab("CLIPBOARD", p2);

                      // Tab 3: Mixer
                      JPanel p3 = new JPanel(new GridBagLayout());
                      p3.setBackground(new Color(0x2b, 0x2b, 0x2b));
                      GridBagConstraints gcm = new GridBagConstraints();
                      gcm.fill = GridBagConstraints.HORIZONTAL;
                      gcm.insets = new Insets(25, 25, 25, 25);

                      gcm.gridx = 0;
                      gcm.gridy = 0;
                      JLabel vL = new JLabel("Channel Volume:");
                      vL.setFont(new Font("SansSerif", Font.BOLD, 20));
                      vL.setForeground(Color.WHITE);
                      p3.add(vL, gcm);
                      gcm.gridx = 1;
                      JSlider vS = new JSlider(0, 100, 80);
                      vS.setPreferredSize(new Dimension(400, 50));
                      vS.addChangeListener(
                          ev ->
                              System.out.println(
                                  "Track " + currentTrack + " Vol: " + vS.getValue()));

                      p3.add(vS, gcm);
                      tabs.addTab("MIXER", p3);

                      // Tab 4: FM Operators
                      JPanel p4 = new JPanel(new FlowLayout(FlowLayout.LEFT, 30, 30));
                      p4.setBackground(new Color(0x2b, 0x2b, 0x2b));

                      JLabel lAlgo = new JLabel("Algorithm Map: [Op 4] ➔ [Op 3] ➔ [Op 2] ➔ Output");
                      lAlgo.setFont(new Font("SansSerif", Font.BOLD, 20));
                      lAlgo.setForeground(Color.ORANGE);

                      JLabel lRatio = new JLabel("Modulator Ratio (Harmonics):");
                      lRatio.setFont(new Font("SansSerif", Font.BOLD, 18));
                      lRatio.setForeground(Color.WHITE);

                      JSlider ratioSlider = new JSlider(1, 10, 1);
                      ratioSlider.setPreferredSize(new Dimension(300, 50));

                      p4.add(lAlgo);
                      p4.add(lRatio);
                      p4.add(ratioSlider);
                      tabs.addTab("FM OPERATORS", p4);

                      gcm.gridx = 0;
                      gcm.gridy = 1;
                      JLabel pL = new JLabel("Channel Panning:");
                      pL.setFont(new Font("SansSerif", Font.BOLD, 20));
                      pL.setForeground(Color.WHITE);
                      p3.add(pL, gcm);
                      gcm.gridx = 1;
                      JSlider pS = new JSlider(0, 100, 50);
                      pS.setPreferredSize(new Dimension(400, 50));
                      p3.add(pS, gcm);
                      tabs.addTab("MIXER", p3);

                      // Actions hooks
                      cloneBtn.addActionListener(
                          ev -> {
                            if (currentTrack < tracks.size()) {
                              org.chuck.deluge.model.TrackModel tModel = tracks.get(currentTrack);
                              if (!tModel.getClips().isEmpty()) {
                                tModel.addClip(tModel.getClips().get(0)); // Mock clone
                              }
                            }
                            dialog.dispose();
                            refresh();
                          });

                      cb.addActionListener(
                          ev -> {
                            if (currentTrack < tracks.size()) {
                              tracks.get(currentTrack).setName((String) cb.getSelectedItem());
                            }
                            dialog.dispose();
                            refresh();
                          });

                      dialog.add(tabs);
                      dialog.setVisible(true);
                    }
                  }
                });
          } else if (viewMode == GridViewMode.ARRANGEMENT) {
            clipBtn.addMouseListener(
                new java.awt.event.MouseAdapter() {
                  @Override
                  public void mousePressed(java.awt.event.MouseEvent e) {
                    if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                      JDialog dialog =
                          new JDialog(
                              (Frame)
                                  javax.swing.SwingUtilities.getWindowAncestor(SwingGridPanel.this),
                              "Bar Automation",
                              true);
                      dialog.setSize(600, 350);
                      dialog.setLocationRelativeTo(SwingGridPanel.this);
                      dialog.setLayout(new GridLayout(3, 1, 20, 20));
                      dialog.add(new JLabel("  Timeline Bar " + (slot + 1) + " Automation:"));
                      dialog.add(new JCheckBox("Enable Low-Pass Filter Sweep"));
                      dialog.add(new JCheckBox("Trigger Volume Fade-In"));
                      dialog.setVisible(true);
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
                  clipBtn.setBackground(Color.ORANGE);

                  Timer timer = new Timer(100, null);
                  final boolean[] flashState = {false};
                  timer.addActionListener(
                      ev -> {
                        int step = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
                        if (step == 0) {
                          clipBtn.setBackground(trackColors[currentTrack]);
                          timer.stop();
                        } else {
                          flashState[0] = !flashState[0];
                          clipBtn.setBackground(flashState[0] ? Color.ORANGE : Color.LIGHT_GRAY);
                        }
                      });
                  timer.start();
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

                int rows = (viewMode == GridViewMode.CLIP) ? voiceRowCount : gridMode.rows;
                if (activeCol != lastCol[0]) {
                  lastCol[0] = activeCol;
                  if (activeCol == 0) {
                    for (int t = 0; t < rows; t++) {
                      if (isOneShotTrack[t] && currentStep >= 16) {
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

                for (int t = 0; t < rows; t++) {
                  int engineRow = baseTrackId + (viewMode == GridViewMode.CLIP ? scrollOffset + t : t);
                  int trackLenT = bridge != null ? bridge.getTrackLength(engineRow) : stepCount;
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
                      boolean stepActive = bridge.getStep(engineRow, engineCol);
                      double velPb = bridge.getVelocity(engineRow, engineCol);
                      pads[t][c].setBackground(
                          stepActive ? velocityBlend(trackColors[t % trackColors.length], velPb) : new Color(0x33, 0x33, 0x33));
                    }
                  }
                }
              }
            });
    playheadTimer.start();
  }
}
