package org.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.deluge.BridgeContract;
import org.deluge.model.SongSection;
import org.deluge.ui.SwingGridPanel.GridRow;

/** Specialized Song Launcher Grid Panel. */
public class SongGridPanel extends SwingGridPanel {

  public SongGridPanel(BridgeContract bridge) {
    super(bridge);
  }

  @Override
  public void rebuildUIComponents() {
    stopAuditionIfNeeded();
    refreshInProgress = true;
    removeAll();
    java.util.List<org.deluge.model.TrackModel> tracks = projectModel.getTracks();

    voiceRowCount = computeVoiceRowCount();
    this.stepCount = gridMode.columns;
    this.columnCount = this.stepCount + 2; // grid mode column count + MUTE/SOLO

    // Stop old VU timer and clear visual registers maps to prevent Swing leaks!
    vuManager.clear();
    vuManager.startTimer();

    // Compute dynamic pad size: always fit gridMode.rows × gridMode.columns cells in the viewport
    int padSz = cachedPadSz;
    int lw = currentLabelWidth();
    int rowW = getGridWidth(padSz, lw);
    int songVoiceRows = gridMode.rows; // always draw full viewport slots

    // ── Section bar (A-Z) for SONG mode ──
    java.util.List<SongSection> sections = getProjectModel().getSongSections();
    if (sections != null && !sections.isEmpty()) {
      JPanel sectionBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
      sectionBar.setBackground(new Color(0x15, 0x15, 0x15));
      sectionBar.setMaximumSize(new Dimension(rowW, 24));
      sectionBar.setAlignmentX(Component.LEFT_ALIGNMENT);
      JLabel secLabel = new JLabel("SECTION:");
      secLabel.setForeground(Color.LIGHT_GRAY);
      secLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
      sectionBar.add(secLabel);
      for (int i = 0; i < sections.size(); i++) {
        SongSection sec = sections.get(i);
        JButton btn = new JButton(sec.getId());
        btn.setFont(new Font("Monospaced", Font.BOLD, 10));
        btn.setMargin(new Insets(0, 6, 0, 6));
        btn.setBackground(new Color(0x2d, 0x3d, 0x2d));
        btn.setForeground(Color.WHITE);
        btn.setFocusable(false);
        btn.setToolTipText(
            "Activate/Launch Song Section "
                + sec.getId()
                + " (contains "
                + sec.getPatternIds().size()
                + " clips)");

        // Wire to dynamic Quick Help status bar!
        SwingSynthConfigDialog.attachHoverHelp(
            btn,
            "<b>LAUNCH SECTION "
                + sec.getId()
                + ":</b> Queues and launches all clips and pattern tracks associated with Section "
                + sec.getId()
                + " in perfect beat synchronization!");

        int sectionIdx = i;
        btn.addActionListener(e -> activateSection(sectionIdx));
        sectionBar.add(btn);
      }
      add(sectionBar);
    }

    voicePanel = new JPanel();
    voicePanel.setBackground(new Color(0x15, 0x15, 0x15));
    voicePanel.setOpaque(true);
    voicePanel.setLayout(new BoxLayout(voicePanel, BoxLayout.Y_AXIS));

    final java.util.List<GridRow> songRows = songDisplayRows(songVoiceRows);

    String[] allParams = {
      "LEVEL", "PAN", "PITCH", "FILTER", "RESONANCE", "OSC1", "OSC2", "LFO",
      "MOD FX", "DELAY", "REVERB", "STUTTER", "PROBABILITY", "GATE", "VELOCITY", "SAMPLE"
    };

    for (int t = 0; t < songVoiceRows + 2; t++) {
      JPanel rowPanel = new JPanel();
      rowPanel.setLayout(new BoxLayout(rowPanel, BoxLayout.X_AXIS));
      rowPanel.setBackground(new Color(0x22, 0x22, 0x22));
      rowPanel.setPreferredSize(new Dimension(rowW, padSz));
      rowPanel.setMinimumSize(new Dimension(rowW, padSz));
      rowPanel.setMaximumSize(new Dimension(rowW, padSz));
      rowPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

      final int currentTrack;
      final org.deluge.model.TrackModel dispTrack;
      GridRow gr = t < songVoiceRows ? songRows.get(t) : null;
      dispTrack = gr != null ? gr.track() : null;
      currentTrack = (dispTrack != null) ? gr.modelIndex() : (tracks.size() + t);

      if (dispTrack != null) {
        String hex = dispTrack.getColourHex();
        if (hex != null && hex.startsWith("0x")) {
          try {
            int rgb = Integer.decode(hex.substring(0, 8));
            trackColors[Math.floorMod(currentTrack, trackColors.length)] = new Color(rgb);
          } catch (Exception e) {
            LOG.warning("Bad color hex for track " + currentTrack + ": " + e.getMessage());
          }
        }
      }

      String trackName = (dispTrack != null) ? dispTrack.getName() : ("EMPTY " + (t + 1));
      if (t == songVoiceRows) trackName = "MACROS";
      if (t == songVoiceRows + 1) trackName = "KEYBOARD";

      final int trk = currentTrack;
      final String tName = trackName;
      JLabel label = new JLabel(tName);
      label.setPreferredSize(new Dimension(lw, 30));
      label.setMinimumSize(new Dimension(lw, 30));
      label.setMaximumSize(new Dimension(lw, 30));

      label.setTransferHandler(
          new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
              return support.isDataFlavorSupported(
                  java.awt.datatransfer.DataFlavor.javaFileListFlavor);
            }

            @SuppressWarnings("unchecked")
            @Override
            public boolean importData(TransferSupport support) {
              if (!canImport(support)) return false;
              try {
                java.util.List<java.io.File> files =
                    (java.util.List<java.io.File>)
                        support
                            .getTransferable()
                            .getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                if (files != null && !files.isEmpty()) {
                  java.io.File soundFile = files.get(0);
                  if (soundFile.getName().toLowerCase().endsWith(".wav")
                      || soundFile.getName().toLowerCase().endsWith(".aif")) {
                    hotSwapTrackSample(trk, 0, soundFile);
                    return true;
                  }
                }
              } catch (Exception ex) {
                LOG.warning("Sample drop import failed: " + ex.getMessage());
              }
              return false;
            }
          });

      label.setFont(new Font("SansSerif", Font.BOLD, padSz > 36 ? 11 : 9));
      label.setForeground(Color.LIGHT_GRAY);
      label.setOpaque(true);
      label.setBackground(new Color(0x18, 0x18, 0x1c));
      label.setBorder(BorderFactory.createLineBorder(new Color(0x2a, 0x2a, 0x30), 1));
      label.setHorizontalAlignment(SwingConstants.CENTER);
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
      rowPanel.add(Box.createHorizontalStrut(5));

      if (t < songVoiceRows) {
        JLabel lenBadge = new JLabel("[SONG]");
        lenBadge.setPreferredSize(new Dimension(48, 26));
        lenBadge.setMinimumSize(new Dimension(48, 26));
        lenBadge.setMaximumSize(new Dimension(48, 26));
        lenBadge.setFont(new Font("Monospaced", Font.BOLD, 10));
        lenBadge.setForeground(Color.GRAY);
        lenBadge.setToolTipText("Currently in Song matrix launcher view");
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

      vuManager.registerTrackVu(trk, vu);

      for (int c = 0; c < columnCount; c++) {
        final int slot = c;
        final int trkId = t;
        final int colId = c;

        boolean isAdvanced =
            org.deluge.project.PreferencesManager.getGridPanelType()
                == org.deluge.project.PreferencesManager.GridPanelType.ADVANCED;
        JButton clipBtn;
        int macR = songVoiceRows, keyR = songVoiceRows + 1;
        if (colId >= 16) {
          DelugePadButton pad = new DelugePadButton();
          pad.putClientProperty("row", t);
          pad.putClientProperty("col", c);
          pad.setDrawCenterCircle(false);
          clipBtn = pad;
        } else if (trkId == macR && colId < 16) {
          clipBtn = new MacroSliderButton(colId, allParams[colId]);
        } else if (trkId == keyR && colId < 18) {
          if (isAdvanced) {
            DelugePadButton pad = new DelugePadButton();
            pad.putClientProperty("row", t);
            pad.putClientProperty("col", c);
            clipBtn = pad;
          } else {
            clipBtn = new CleanJButton();
          }
        } else {
          if (isAdvanced) {
            DelugePadButton pad = new DelugePadButton();
            pad.putClientProperty("row", t);
            pad.putClientProperty("col", c);
            clipBtn = pad;
          } else {
            clipBtn = new CleanJButton();
          }
        }

        clipBtn.setPreferredSize(new Dimension(padSz, padSz));
        clipBtn.setMinimumSize(new Dimension(padSz, padSz));
        clipBtn.setMaximumSize(new Dimension(padSz, padSz));
        clipBtn.setMargin(new Insets(0, 0, 0, 0));

        pads[t][c] = clipBtn;
        clipBtn.setBorder(BorderFactory.createEmptyBorder());

        boolean isUnusedSongRow = currentTrack >= tracks.size() && t < songVoiceRows;
        if (isUnusedSongRow) {
          Color darkBg = new Color(0x15, 0x15, 0x15);
          clipBtn.setBackground(darkBg);
          clipBtn.setForeground(Color.GRAY);
          clipBtn.setText("");
          if (clipBtn instanceof DelugePadButton pad) {
            pad.setBaseColor(darkBg);
            pad.setTextColorOverride(Color.GRAY);
            pad.setDrawCenterCircle(false);
            pad.setIntensity(0.0f);
            pad.setActive(false);
            pad.setTail(false);
            pad.setNoteText("");
            pad.setMuted(false);
            pad.setPlayhead(false);
            pad.setSelected(false);
            pad.setInLoop(true);
          }
          clipBtn.setComponentPopupMenu(null);
          clipBtn.setToolTipText(null);
          clipBtn.setEnabled(false);
          clearActionListeners(clipBtn);
          for (java.awt.event.MouseListener ml : clipBtn.getMouseListeners()) {
            clipBtn.removeMouseListener(ml);
          }
          for (java.awt.event.MouseMotionListener mml : clipBtn.getMouseMotionListeners()) {
            clipBtn.removeMouseMotionListener(mml);
          }
          for (java.awt.event.MouseWheelListener mwl : clipBtn.getMouseWheelListeners()) {
            clipBtn.removeMouseWheelListener(mwl);
          }
        } else {
          clipBtn.setEnabled(true);
          clipBtn.setText("");

          boolean hasClip = false;
          if (dispTrack != null) {
            if (c < dispTrack.getClips().size()) {
              hasClip = true;
            }
          }

          if (t >= songVoiceRows && (isMuteColumn(colId) || isSoloColumn(colId))) {
            clipBtn.setVisible(false);
            clipBtn.setEnabled(false);
          } else if (isMuteColumn(colId)) {
            final int engineRow = trk;
            boolean curMute = bridge.getMute(engineRow);
            Color muteBg = curMute ? new Color(0xff, 0xd7, 0x00) : Color.WHITE;
            clipBtn.setText(curMute ? "UNMUTE" : "MUTE");
            clipBtn.setBackground(muteBg);
            clipBtn.setForeground(Color.BLACK);
            clipBtn.setFont(new Font("SansSerif", Font.BOLD, padSz > 70 ? 11 : 9));
            if (clipBtn instanceof DelugePadButton pad) {
              pad.setBaseColor(muteBg);
              pad.setIntensity(1.0f);
              pad.setActive(true);
              pad.setNoteText(curMute ? "UNMUTE" : "MUTE");
            }
            clearActionListeners(clipBtn);
            clipBtn.addActionListener(
                e -> {
                  boolean isMuted = bridge.getMute(engineRow);
                  setTrackMuteWithCapture(engineRow, !isMuted);
                  refresh();
                });
            JPopupMenu mutePopup = createMutePopupMenu(engineRow);
            clipBtn.setComponentPopupMenu(mutePopup);
          } else if (isSoloColumn(colId)) {
            Color labelBg;
            Color labelFg;
            boolean rowHasClip = !tracks.get(trk).getClips().isEmpty();
            if (soloRow == trk) {
              labelBg = Color.GREEN;
              labelFg = Color.BLACK;
            } else if (rowHasClip) {
              labelBg = DelugeColour.sectionColour(tracks.get(trk).getClips().get(0).getSection());
              double b =
                  (0.299 * labelBg.getRed()
                          + 0.587 * labelBg.getGreen()
                          + 0.114 * labelBg.getBlue())
                      / 255.0;
              labelFg = b > 0.5 ? Color.BLACK : Color.WHITE;
            } else {
              labelBg = Color.BLACK;
              labelFg = Color.GRAY;
            }
            clipBtn.setBackground(labelBg);
            clipBtn.setForeground(labelFg);
            String nName = tracks.get(trk).getName();
            clipBtn.setText(nName);
            if (clipBtn instanceof DelugePadButton pad) {
              pad.setBaseColor(labelBg);
              pad.setTextColorOverride(labelFg);
              pad.setIntensity(1.0f);
              pad.setActive(true);
              pad.setNoteText(nName);
            }
            clearActionListeners(clipBtn);
            clipBtn.addActionListener(
                e -> {
                  if (soloRow == trk) {
                    soloRow = -1;
                    for (int i = 0; i < voiceRowCount; i++) {
                      setTrackMuteWithCapture(baseTrackId + i, false);
                    }
                  } else {
                    soloRow = trk;
                    for (int i = 0; i < voiceRowCount; i++) {
                      setTrackMuteWithCapture(baseTrackId + i, i != trk);
                    }
                  }
                  refresh();
                });
            clipBtn.addMouseListener(
                new java.awt.event.MouseAdapter() {
                  @Override
                  public void mousePressed(java.awt.event.MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e)) {
                      showSoloButtonContextMenu(clipBtn, e.getX(), e.getY(), trk);
                    }
                  }
                });
          } else {
            // Normal launcher grid cells
            if (trkId == macR) {
              // Macros row cells: handled by MacroSliderButton listeners
            } else if (trkId == keyR) {
              // Keyboard row cells
              if (clipBtn instanceof DelugePadButton pad) {
                pad.setBaseColor(new Color(0x1e, 0x1e, 0x22));
                pad.setIntensity(0.2f);
                pad.setActive(false);
              } else {
                clipBtn.setBackground(new Color(0x22, 0x22, 0x24));
              }
            } else if (currentTrack < tracks.size()) {
              updateSongPadVisuals(
                  clipBtn,
                  currentTrack,
                  colId,
                  hasClip,
                  trackColors[Math.floorMod(currentTrack, trackColors.length)],
                  org.deluge.project.PreferencesManager.getGridColorTheme());

              final int clipCol = colId;
              final int trkIdx = currentTrack;
              final org.deluge.model.TrackModel songTrack = tracks.get(t);
              clearActionListeners(clipBtn);
              clearKeyboardMouseListeners(clipBtn);
              clipBtn.addMouseListener(
                  new java.awt.event.MouseAdapter() {
                    @Override
                    public void mousePressed(java.awt.event.MouseEvent e) {
                      if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                        if (clipCol < songTrack.getClips().size()) {
                          showClipContextMenu(
                              clipBtn, e.getX(), e.getY(), songTrack, clipCol, trkIdx);
                        } else {
                          JPopupMenu emptyClipMenu = new JPopupMenu();
                          JMenuItem createItem = new JMenuItem("Create New Clip Pattern");
                          createItem.addActionListener(
                              ev -> {
                                String name = "CLIP " + (clipCol + 1);
                                songTrack.addClip(
                                    new org.deluge.model.ClipModel(
                                        name,
                                        songTrack.getClips().isEmpty()
                                            ? 8
                                            : songTrack.getClips().get(0).getRowCount(),
                                        16));
                                fireProjectChanged();
                              });
                          emptyClipMenu.add(createItem);
                          stylePopupMenu(emptyClipMenu);
                          createItem.setForeground(new Color(0x00, 0xff, 0xcc));
                          emptyClipMenu.show(clipBtn, e.getX(), e.getY());
                        }
                      } else {
                        if (clipCol >= songTrack.getClips().size()) {
                          String name = "CLIP " + (clipCol + 1);
                          songTrack.addClip(
                              new org.deluge.model.ClipModel(
                                  name,
                                  songTrack.getClips().isEmpty()
                                      ? 8
                                      : songTrack.getClips().get(0).getRowCount(),
                                  16));
                          fireProjectChanged();
                        } else {
                          if (SwingDelugeApp.mainInstance != null) {
                            SwingDelugeApp.mainInstance.switchToTrackEdit(trkIdx, clipCol);
                          }
                        }
                      }
                    }
                  });
            } else {
              // Empty space under tracks
              clipBtn.setBackground(new Color(0x1a, 0x1a, 0x1a));
              if (clipBtn instanceof DelugePadButton pad) {
                pad.setBaseColor(new Color(0x1a, 0x1a, 0x1a));
                pad.setIntensity(0.2f);
                pad.setActive(false);
                pad.setNoteText("");
              }
              final int clickRow = t;
              final int clickCol = colId;
              clearActionListeners(clipBtn);
              clearKeyboardMouseListeners(clipBtn);
              clipBtn.addMouseListener(
                  new java.awt.event.MouseAdapter() {
                    @Override
                    public void mousePressed(java.awt.event.MouseEvent e) {
                      if (javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                        showCreateTrackMenu(clipBtn, e.getX(), e.getY(), clickRow, clickCol);
                      }
                    }
                  });
            }
          }
        }

        rowPanel.add(clipBtn);
        rowPanel.add(Box.createHorizontalStrut(5));
      }

      voicePanel.add(rowPanel);
      if (t < songVoiceRows + 1) {
        voicePanel.add(Box.createVerticalStrut(5));
      }
    }

    JPanel voiceWrapper = new JPanel(new BorderLayout());
    voiceWrapper.setBackground(new Color(0x15, 0x15, 0x15));
    boolean showNavPanel = voiceRowCount > gridMode.rows;

    int viewH = gridMode.rows * (padSz + 5) - 5;
    int wrapperW = rowW + (showNavPanel ? 32 : 0);
    voiceWrapper.setPreferredSize(new Dimension(wrapperW, viewH));
    voiceWrapper.setMaximumSize(new Dimension(wrapperW, viewH));
    voiceWrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
    voiceWrapper.add(voicePanel, BorderLayout.CENTER);

    if (showNavPanel) {
      this.vertScrollBar = scrollController.getVerticalScrollBar();
      JPanel pageNavPanel = new JPanel(new BorderLayout(0, 4));
      pageNavPanel.setBackground(new Color(0x15, 0x15, 0x18));

      JButton pgUpBtn = new JButton("▲");
      pgUpBtn.setUI(new javax.swing.plaf.basic.BasicButtonUI());
      pgUpBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
      pgUpBtn.setForeground(new Color(0x00, 0xff, 0xcc));
      pgUpBtn.setBackground(new Color(0x1f, 0x1f, 0x24));
      pgUpBtn.setBorder(BorderFactory.createEmptyBorder(6, 2, 6, 2));
      pgUpBtn.setFocusable(false);
      pgUpBtn.setToolTipText("Page Up (Shift 8 rows up)");
      pgUpBtn.addActionListener(e -> scrollVertically(-gridMode.rows));

      JButton pgDnBtn = new JButton("▼");
      pgDnBtn.setUI(new javax.swing.plaf.basic.BasicButtonUI());
      pgDnBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
      pgDnBtn.setForeground(new Color(0x00, 0xff, 0xcc));
      pgDnBtn.setBackground(new Color(0x1f, 0x1f, 0x24));
      pgDnBtn.setBorder(BorderFactory.createEmptyBorder(6, 2, 6, 2));
      pgDnBtn.setFocusable(false);
      pgDnBtn.setToolTipText("Page Down (Shift 8 rows down)");
      pgDnBtn.addActionListener(e -> scrollVertically(gridMode.rows));

      pgUpBtn.setVisible(true);
      vertScrollBar.setVisible(true);
      pgDnBtn.setVisible(true);

      pageNavPanel.add(pgUpBtn, BorderLayout.NORTH);
      pageNavPanel.add(vertScrollBar, BorderLayout.CENTER);
      pageNavPanel.add(pgDnBtn, BorderLayout.SOUTH);

      voiceWrapper.add(pageNavPanel, BorderLayout.EAST);
    }

    add(voiceWrapper);
    revalidate();
    repaint();
    refreshInProgress = false;
    scrollController.updateScrollBarValues();
  }

  @Override
  public void refreshInPlace() {
    if (projectModel == null) return;
    updatePageBarHighlights();
    scrollController.syncScrollBarValues();

    java.util.List<org.deluge.model.TrackModel> tracks = projectModel.getTracks();
    int songVoiceRows = gridMode.rows;

    for (int v = 0; v < gridMode.rows; v++) {
      int modelRow = songRowIndex(v);
      if (modelRow >= 0 && modelRow < voiceRowCount) {
        int engineRow = baseTrackId + modelRow;
        boolean isMuted = bridge != null && bridge.getMute(engineRow);

        for (int c = 0; c < columnCount; c++) {
          JButton clipBtn = pads[v][c];
          if (clipBtn == null) continue;

          if (modelRow >= tracks.size()) {
            Color darkBg = new Color(0x15, 0x15, 0x15);
            clipBtn.setBackground(darkBg);
            clipBtn.setForeground(Color.GRAY);
            clipBtn.setText("");
            if (clipBtn instanceof DelugePadButton pad) {
              pad.setBaseColor(darkBg);
              pad.setTextColorOverride(Color.GRAY);
              pad.setDrawCenterCircle(false);
              pad.setIntensity(0.0f);
              pad.setActive(false);
              pad.setTail(false);
              pad.setNoteText("");
              pad.setMuted(false);
              pad.setPlayhead(false);
              pad.setSelected(false);
              pad.setInLoop(true);
            }
          } else if (isMuteColumn(c)) {
            Color muteBg = isMuted ? new Color(0xff, 0xd7, 0x00) : Color.WHITE;
            clipBtn.setBackground(muteBg);
            clipBtn.setText(isMuted ? "UNMUTE" : "MUTE");
            if (clipBtn instanceof DelugePadButton pad) {
              pad.setBaseColor(muteBg);
              pad.setIntensity(1.0f);
              pad.setActive(true);
              pad.setNoteText(isMuted ? "UNMUTE" : "MUTE");
            }
          } else if (isSoloColumn(c)) {
            Color labelBg;
            Color labelFg;
            boolean rowHasClip = !tracks.get(modelRow).getClips().isEmpty();
            if (soloRow == modelRow) {
              labelBg = Color.GREEN;
              labelFg = Color.BLACK;
            } else if (rowHasClip) {
              labelBg =
                  DelugeColour.sectionColour(tracks.get(modelRow).getClips().get(0).getSection());
              double b =
                  (0.299 * labelBg.getRed()
                          + 0.587 * labelBg.getGreen()
                          + 0.114 * labelBg.getBlue())
                      / 255.0;
              labelFg = b > 0.5 ? Color.BLACK : Color.WHITE;
            } else {
              labelBg = Color.BLACK;
              labelFg = Color.GRAY;
            }
            clipBtn.setBackground(labelBg);
            clipBtn.setForeground(labelFg);

            String nName = tracks.get(modelRow).getName();
            clipBtn.setText(nName);

            if (clipBtn instanceof DelugePadButton pad) {
              pad.setBaseColor(labelBg);
              pad.setTextColorOverride(labelFg);
              pad.setIntensity(1.0f);
              pad.setActive(true);
              pad.setNoteText(nName);
            }
          } else {
            boolean hasClip = c < tracks.get(modelRow).getClips().size();
            updateSongPadVisuals(
                clipBtn,
                modelRow,
                c,
                hasClip,
                getTrackColour(modelRow),
                org.deluge.project.PreferencesManager.getGridColorTheme());
          }
        }
      }
    }
    repaint();
  }
}
