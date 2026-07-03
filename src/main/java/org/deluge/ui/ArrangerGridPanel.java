package org.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.deluge.BridgeContract;
import org.deluge.ui.SwingGridPanel.GridRow;

/** Specialized Arranger Timeline Grid Panel. */
public class ArrangerGridPanel extends SwingGridPanel {

  public ArrangerGridPanel(BridgeContract bridge) {
    super(bridge);
  }

  @Override
  public void rebuildUIComponents() {
    stopAuditionIfNeeded();
    refreshInProgress = true;
    removeAll();
    java.util.List<org.deluge.model.TrackModel> tracks = projectModel.getTracks();

    voiceRowCount = computeVoiceRowCount();
    int songVoiceRows = gridMode.rows;
    this.stepCount = gridMode.columns;
    int savedColCount = this.stepCount + 2; // grid mode column count + MUTE/SOLO
    this.columnCount = savedColCount;

    vuManager.clear();
    vuManager.startTimer();

    int padSz = cachedPadSz;
    int lw = currentLabelWidth();
    int rowW = getGridWidth(padSz, lw);

    voicePanel = new JPanel();
    voicePanel.setBackground(new Color(0x15, 0x15, 0x15));
    voicePanel.setOpaque(true);
    voicePanel.setLayout(new BoxLayout(voicePanel, BoxLayout.Y_AXIS));

    final java.util.List<GridRow> songRows = songDisplayRows(songVoiceRows);

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
      if (t < songVoiceRows) {
        GridRow gr = songRows.get(t);
        dispTrack = gr.track();
        currentTrack = (dispTrack != null) ? gr.modelIndex() : (tracks.size() + t);
      } else {
        currentTrack = t;
        dispTrack = null;
      }

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

      String trackName;
      if (t < songVoiceRows) {
        trackName = (dispTrack != null) ? dispTrack.getName() : ("EMPTY " + (t + 1));
      } else if (t == songVoiceRows) {
        trackName = "MACROS";
      } else {
        trackName = "KEYBOARD";
      }

      final int trk = currentTrack;
      final String tName = trackName;
      JLabel label = new JLabel(tName);
      lw = currentLabelWidth();
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

      label.setForeground(Color.LIGHT_GRAY);
      label.setFont(new Font("SansSerif", Font.BOLD, 10));
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

      if (t < songVoiceRows && dispTrack != null) {
        int stepLen = (bridge != null) ? bridge.getTrackLength(trk) : 16;
        JLabel lenBadge = new JLabel("[" + stepLen + "]");
        lenBadge.setPreferredSize(new Dimension(48, 26));
        lenBadge.setMinimumSize(new Dimension(48, 26));
        lenBadge.setMaximumSize(new Dimension(48, 26));
        lenBadge.setFont(new Font("Monospaced", Font.BOLD, 11));
        lenBadge.setForeground(stepLen == 16 ? Color.GRAY : new Color(0xff, 0xcc, 0x00));
        lenBadge.setToolTipText("Track length (right-click to change)");
        lenBadge.setCursor(new Cursor(Cursor.HAND_CURSOR));
        lenBadge.addMouseListener(
            new java.awt.event.MouseAdapter() {
              @Override
              public void mouseClicked(java.awt.event.MouseEvent e) {
                if (javax.swing.SwingUtilities.isRightMouseButton(e) || e.getClickCount() == 2) {
                  String input =
                      JOptionPane.showInputDialog(
                          ArrangerGridPanel.this, "Track length (1-64):", stepLen);
                  if (input != null) {
                    try {
                      int newLen = Integer.parseInt(input.trim());
                      if (newLen >= 1 && newLen <= BridgeContract.STEPS) {
                        bridge.setTrackLength(trk, newLen);
                        refresh();
                      }
                    } catch (NumberFormatException ignored) {
                    }
                  }
                }
              }
            });
        rowPanel.add(Box.createHorizontalStrut(21));
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

      String[] allParams = {
        "LEVEL", "PAN", "PITCH", "FILTER", "RESONANCE", "OSC1", "OSC2", "LFO",
        "MOD FX", "DELAY", "REVERB", "STUTTER", "PROBABILITY", "GATE", "VELOCITY", "SAMPLE"
      };

      for (int c = 0; c < columnCount; c++) {
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
        } else if (t == macR) {
          clipBtn = new MacroSliderButton(colId, allParams[colId]);
        } else if (t == keyR) {
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
        clipBtn.setBorder(UIManager.getBorder("Button.border"));

        boolean isUnusedSongRow = currentTrack >= tracks.size();
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
          rowPanel.add(clipBtn);
          rowPanel.add(Box.createHorizontalStrut(5));
          continue;
        }

        if (t >= songVoiceRows && (isMuteColumn(colId) || isSoloColumn(colId))) {
          clipBtn.setVisible(false);
          clipBtn.setEnabled(false);
        } else if (t == macR) {
          if (c >= 16) {
            clipBtn.setBackground(new Color(0x1a, 0x1a, 0x1a));
            clipBtn.setEnabled(false);
          }
        } else if (t == keyR) {
          if (colId < 18) {
            int note = 48 + colId;
            boolean isBlack =
                (colId % 12 == 1
                    || colId % 12 == 3
                    || colId % 12 == 6
                    || colId % 12 == 8
                    || colId % 12 == 10);
            clipBtn.setBackground(isBlack ? new Color(0x33, 0x33, 0x33) : Color.WHITE);
            clipBtn.setForeground(isBlack ? Color.WHITE : Color.BLACK);
            clipBtn.setText(getNoteName(note));
            clipBtn.setFont(new Font("SansSerif", Font.BOLD, padSz > 70 ? 14 : 10));

            clearActionListeners(clipBtn);
            clearKeyboardMouseListeners(clipBtn);
            clipBtn.addMouseListener(new KeyboardMouseAdapter(this, note));
          }
        } else {
          if (colId < 16) {
            org.deluge.model.ArrangerClip placement =
                arrangerController.getArrangerClipAt(currentTrack, c);
            if (placement != null && placement.clip() != null) {
              clipBtn.setText(
                  "<html><center><font size='3'><b>"
                      + placement.clip().getName()
                      + "</b><br>Bar "
                      + (c + 1)
                      + "</font></center></html>");
              if (clipBtn instanceof DelugePadButton pad) {
                pad.setBaseColor(trackColors[Math.floorMod(currentTrack, trackColors.length)]);
                pad.setIntensity(1.0f);
                pad.setActive(true);
              } else {
                clipBtn.setBackground(trackColors[Math.floorMod(currentTrack, trackColors.length)]);
                clipBtn.setForeground(Color.BLACK);
              }
            } else {
              clipBtn.setText(
                  "<html><center><font color='#555555' size='3'>Bar "
                      + (c + 1)
                      + "</font></center></html>");
              if (clipBtn instanceof DelugePadButton pad) {
                pad.setBaseColor(new Color(0x1e, 0x1e, 0x22));
                pad.setIntensity(0.2f);
                pad.setActive(false);
              } else {
                clipBtn.setBackground(new Color(0x22, 0x22, 0x24));
                clipBtn.setForeground(Color.GRAY);
              }
            }
          }

          if (isMuteColumn(colId)) {
            final int engineRow = trk;
            boolean isMuted = bridge != null && bridge.getMute(engineRow);
            clipBtn.setText(isMuted ? "UNMUTE" : "MUTE");
            clipBtn.setFont(new Font("SansSerif", Font.BOLD, padSz > 70 ? 11 : 9));
            Color muteBg = isMuted ? new Color(0xff, 0xd7, 0x00) : Color.WHITE;
            clipBtn.setBackground(muteBg);
            clipBtn.setForeground(Color.BLACK);
            if (clipBtn instanceof DelugePadButton pad) {
              pad.setBaseColor(muteBg);
              pad.setTextColorOverride(Color.BLACK);
              pad.setIntensity(isMuted ? 0.4f : 1.0f);
              pad.setActive(true);
              pad.setNoteText(isMuted ? "UNMUTE" : "MUTE");
            }
            clipBtn.setToolTipText("Arrangement View: Track " + (trk + 1) + " Full Track Mute");
            clearActionListeners(clipBtn);
            clipBtn.addActionListener(
                e -> {
                  boolean nextMute = bridge != null && !bridge.getMute(engineRow);
                  setTrackMuteWithCapture(engineRow, nextMute);
                  refresh();
                });
          } else if (isSoloColumn(colId)) {
            clipBtn.setText("SOLO");
            clipBtn.setFont(new Font("SansSerif", Font.BOLD, padSz > 70 ? 11 : 9));
            boolean isSoloed = (soloRow == trk);
            Color soloBg = isSoloed ? new Color(0x00, 0xff, 0xcc) : new Color(0x2d, 0x2d, 0x32);
            Color soloFg = isSoloed ? Color.BLACK : Color.LIGHT_GRAY;
            clipBtn.setBackground(soloBg);
            clipBtn.setForeground(soloFg);
            if (clipBtn instanceof DelugePadButton pad) {
              pad.setBaseColor(soloBg);
              pad.setTextColorOverride(soloFg);
              pad.setActive(isSoloed);
            }
            clipBtn.setToolTipText("Arrangement View: Cue Bar / Section Marker " + (trk + 1));
            clearKeyboardMouseListeners(clipBtn);
            clipBtn.addMouseListener(
                new java.awt.event.MouseAdapter() {
                  @Override
                  public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                      showSoloButtonContextMenu(clipBtn, e.getX(), e.getY(), trk);
                      return;
                    }
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
                  }
                });
          }
        }

        if (colId < 16) {
          int col = colId + scrollOffsetX;
          org.deluge.model.ArrangerClip ac =
              arrangerController.getArrangerClipAt(currentTrack, col);
          if (ac != null) {
            String clipName = ac.clip() != null ? ac.clip().getName() : "Arrangement Clip";
            clipBtn.setToolTipText(
                "<html><body style='font-size: 9px; font-family: sans-serif;'><b>Arranger Clip: "
                    + clipName
                    + "</b><br>• Position: Bar "
                    + (col + 1)
                    + "<br>• Duration: "
                    + (ac.durationTicks() / 96)
                    + " bars<br>• Actions: Drag to move, Shift+Drag to resize<br>• Right-Click: Delete clip, Duplicate, or Edit Bar Automation</body></html>");
          } else {
            clipBtn.setToolTipText(
                "<html><body style='font-size: 9px; font-family: sans-serif;'><b>Empty Timeline Bar "
                    + (col + 1)
                    + "</b><br>• Left-Click: Place a clip at this position<br>• Right-Click: Add Arranged Clip or Edit Bar Automation</body></html>");
          }
        }

        arrangerController.attachListeners(clipBtn, currentTrack, colId);

        if (isMuteColumn(c)) {
          rowPanel.add(Box.createHorizontalStrut(20));
        }
        rowPanel.add(clipBtn);
        rowPanel.add(Box.createHorizontalStrut(5));
      }
      voicePanel.add(rowPanel);
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

      boolean showScrollControls = voiceRowCount > gridMode.rows;
      pgUpBtn.setVisible(showScrollControls);
      pgDnBtn.setVisible(showScrollControls);
      vertScrollBar.setVisible(showScrollControls);

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

    for (int v = 0; v < songVoiceRows; v++) {
      int modelRow = getModelRow(v);
      if (modelRow >= 0 && modelRow < voiceRowCount) {
        int engineROffset = getEngineRowOffset(modelRow);
        int engineR = baseTrackId + engineROffset;
        boolean isMuted = bridge != null && bridge.getMute(engineR);

        for (int c = 0; c < columnCount; c++) {
          JButton clipBtn = pads[v][c];
          if (clipBtn == null) continue;

          if (isMuteColumn(c)) {
            Color muteBg = isMuted ? new Color(0xff, 0xd7, 0x00) : Color.WHITE;
            clipBtn.setBackground(muteBg);
            clipBtn.setText(isMuted ? "UNMUTE" : "MUTE");
            if (clipBtn instanceof DelugePadButton pad) {
              pad.setBaseColor(muteBg);
              pad.setMuted(isMuted);
              pad.setNoteText(isMuted ? "UNMUTE" : "MUTE");
            }
            clipBtn.setToolTipText(
                "Arrangement View: Track " + (modelRow + 1) + " Full Track Mute");
          } else if (isSoloColumn(c)) {
            clipBtn.setText("SOLO");
            boolean isSoloed = (soloRow == modelRow);
            Color soloBg = isSoloed ? new Color(0x00, 0xff, 0xcc) : new Color(0x2d, 0x2d, 0x32);
            Color soloFg = isSoloed ? Color.BLACK : Color.LIGHT_GRAY;
            clipBtn.setBackground(soloBg);
            clipBtn.setForeground(soloFg);
            if (clipBtn instanceof DelugePadButton pad) {
              pad.setBaseColor(soloBg);
              pad.setTextColorOverride(soloFg);
              pad.setActive(isSoloed);
            }
            clipBtn.setToolTipText("Arrangement View: Cue Bar / Section Marker " + (modelRow + 1));
          } else {
            boolean hasClip = false;
            Color arrCellColour = null;
            if (modelRow < tracks.size()) {
              int col = c + scrollOffsetX;
              org.deluge.model.ArrangerClip ac =
                  arrangerController.getArrangerClipAt(modelRow, col);
              hasClip = ac != null;
              if (ac != null) {
                int section = ac.clip() != null ? ac.clip().getSection() : -1;
                Color base = DelugeColour.sectionColour(section);
                int colTick = arrangerController.arrangerTickForColumn(col);
                int colTicks = arrangerController.arrangerTicksPerColumn();
                boolean isHead = ac.startTicks() >= colTick && ac.startTicks() < colTick + colTicks;
                if (ac.clip() == null) {
                  arrCellColour = DelugeColour.dim(base, 4);
                } else if (isHead) {
                  arrCellColour = base;
                } else {
                  int loopLen = ac.clip().getLoopLength();
                  int rel = colTick - ac.startTicks();
                  boolean loopStart = loopLen > 0 && (rel % loopLen == 0);
                  arrCellColour =
                      loopStart
                          ? DelugeColour.dim(base, 3)
                          : DelugeColour.dim(DelugePadButton.getBlurColor(base), 3);
                }
              }
            }

            Color cellColour = arrCellColour != null ? arrCellColour : getTrackColour(modelRow);
            if (clipBtn instanceof DelugePadButton pad) {
              org.deluge.project.PreferencesManager.GridColorTheme theme =
                  org.deluge.project.PreferencesManager.getGridColorTheme();
              pad.setBaseColor(cellColour);
              pad.setTheme(theme);
              pad.setActive(hasClip);
              pad.setMuted(isMuted);
              pad.setScaleRoot(false);
              pad.setScaleNote(false);
              pad.setIntensity(1.0f);
              pad.setBeatMarker((c + scrollOffsetX) % 4 == 0);
            } else {
              if (hasClip) {
                clipBtn.setBackground(cellColour);
              } else {
                clipBtn.setBackground(new Color(0x33, 0x33, 0x33));
              }
            }
          }
        }
      }
    }
  }
}
