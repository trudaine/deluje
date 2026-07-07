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

  /**
   * The Deluge arranger status/mute square colour (arranger_view.cpp drawMuteSquare): blue when
   * soloed, yellow-orange when muted, green when active; dulled (channels clamped to 5..50) when
   * another track is soloing. Note the arranger's muted colour is yellow-orange, unlike the session
   * status square's red.
   */
  private Color arrangerStatusColour(boolean muted, boolean soloedHere, boolean anySoloing) {
    Color base =
        soloedHere ? new Color(0, 0, 255) : (muted ? new Color(255, 160, 0) : new Color(0, 255, 6));
    return (anySoloing && !soloedHere) ? DelugeColour.dull(base) : base;
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

    boolean isFaceplate =
        org.deluge.project.PreferencesManager.getTopPanelStyle()
            == org.deluge.project.PreferencesManager.TopPanelStyle.HARDWARE_FACEPLATE;
    double faceScale = Math.max(800, getWidth()) / 2256.0;
    int padSz = cachedPadSz;
    int lw = currentLabelWidth();
    int rowW;
    if (isFaceplate) {
      padSz = Math.max(16, (int) Math.round(78 * faceScale));
      rowW = (int) Math.round(2270 * faceScale);
    } else {
      rowW = getGridWidth(padSz, lw);
    }

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

      if (!isFaceplate) {
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
      } else {
        rowPanel.add(Box.createRigidArea(new Dimension((int) Math.round(58 * faceScale), 1)));
      }

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
          clipBtn = new MacroSliderButton(this, colId, allParams[colId]);
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
            int col = c + scrollOffsetX;
            org.deluge.model.ArrangerClip placement =
                arrangerController.getArrangerClipAt(currentTrack, c);
            if (placement != null && placement.clip() != null) {
              clipBtn.setText(
                  "<html><center><font size='3'><b>"
                      + placement.clip().getName()
                      + "</b><br>Bar "
                      + (col + 1)
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
                      + (col + 1)
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
            org.deluge.model.TrackModel track = tracks.get(trk);
            boolean isMuted = (soloRow >= 0) ? (trk != soloRow) : track.isMuted();
            clipBtn.setText(
                isMuted ? "UNMUTE" : "MUTE"); // getText() = mute state (E2E observes it)
            clipBtn.setFont(new Font("SansSerif", Font.BOLD, padSz > 70 ? 11 : 9));
            Color muteBg = arrangerStatusColour(isMuted, soloRow == trk, soloRow >= 0);
            clipBtn.setBackground(muteBg);
            if (clipBtn instanceof DelugePadButton pad) {
              pad.setBaseColor(muteBg);
              pad.setIntensity(
                  1.0f); // the faithful status colour is the final LED; don't re-dim it
              pad.setActive(true);
            }
            clipBtn.setToolTipText(isMuted ? "Muted — click to unmute" : "Mute track");
            clearActionListeners(clipBtn);
            clipBtn.addActionListener(
                e -> {
                  track.setMuted(!track.isMuted());
                  updateEngineMutes();
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
                    } else {
                      soloRow = trk;
                    }
                    updateEngineMutes();
                    refresh();
                  }
                });
          }
        }

        if (colId < 16) {
          int col = colId + scrollOffsetX;
          org.deluge.model.ArrangerClip ac =
              arrangerController.getArrangerClipAt(currentTrack, colId);
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

        if (isFaceplate) {
          if (c == 16) {
            rowPanel.add(ClipGridPanel.createFaceplateSeparator(faceScale, padSz));
          } else if (c > 0) {
            rowPanel.add(Box.createRigidArea(new Dimension((int) Math.round(41 * faceScale), 1)));
          }
          if (dispTrack != null) {
            String oldTip = clipBtn.getToolTipText();
            clipBtn.setToolTipText(dispTrack.getName() + (oldTip != null ? " — " + oldTip : ""));
          }
        }
        rowPanel.add(clipBtn);
        if (!isFaceplate) {
          rowPanel.add(Box.createHorizontalStrut(5));
        }
      }
      voicePanel.add(rowPanel);
      if (isFaceplate) {
        voicePanel.add(Box.createRigidArea(new Dimension(1, (int) Math.round(41 * faceScale))));
      }
    }

    JPanel voiceWrapper = new JPanel(new BorderLayout());
    voiceWrapper.setBackground(new Color(0x15, 0x15, 0x15));
    boolean showNavPanel = (voiceRowCount > gridMode.rows) || isFaceplate;
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

    EngineSyncCoordinator sync = null;
    if (SwingDelugeApp.mainInstance != null) {
      sync = SwingDelugeApp.mainInstance.getSyncCoordinator();
    }

    for (int v = 0; v < songVoiceRows; v++) {
      int modelRow = getModelRow(v);
      if (modelRow >= 0 && modelRow < voiceRowCount) {
        int engineROffset = getEngineRowOffset(modelRow);
        int engineR = baseTrackId + engineROffset;
        if (sync != null) {
          int syncStart = sync.getTrackEngineStart(modelRow);
          if (syncStart >= 0) {
            engineR = syncStart;
          }
        }
        boolean isMuted = bridge != null && bridge.getMute(engineR);

        for (int c = 0; c < columnCount; c++) {
          JButton clipBtn = pads[v][c];
          if (clipBtn == null) continue;

          if (isMuteColumn(c)) {
            Color muteBg = arrangerStatusColour(isMuted, soloRow == modelRow, soloRow >= 0);
            clipBtn.setBackground(muteBg);
            clipBtn.setText(
                isMuted ? "UNMUTE" : "MUTE"); // getText() = mute state (E2E observes it)
            if (clipBtn instanceof DelugePadButton pad) {
              pad.setBaseColor(muteBg);
              pad.setIntensity(1.0f);
              pad.setActive(true);
            }
            clipBtn.setToolTipText(isMuted ? "Muted — click to unmute" : "Mute track");
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
            org.deluge.model.ArrangerClip ac = null;
            if (modelRow < tracks.size()) {
              ac = arrangerController.getArrangerClipAt(modelRow, c);
              hasClip = ac != null;
              if (ac != null) {
                // Head/loop/blur/dim resolved by the pure ArrangementProjector
                // (ArrangementProjector Test pins the colours); same code the whole-grid projector
                // uses.
                int colTick = arrangerController.arrangerTickForColumn(c);
                int colTicks = arrangerController.arrangerTicksPerColumn();
                arrCellColour = ArrangementProjector.colourFor(ac, colTick, colTicks);
              }
            }

            int col = c + scrollOffsetX;
            if (hasClip && ac != null && ac.clip() != null) {
              clipBtn.setText(
                  "<html><center><font size='3'><b>"
                      + ac.clip().getName()
                      + "</b><br>Bar "
                      + (col + 1)
                      + "</font></center></html>");
            } else {
              clipBtn.setText(
                  "<html><center><font color='#555555' size='3'>Bar "
                      + (col + 1)
                      + "</font></center></html>");
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
              pad.setBeatMarker(col % 4 == 0);
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

  @Override
  public void updatePlayhead(int step) {
    this.currentPlayheadStep = step;
    Object fwHandlerObj = bridge.getGlobalObject(BridgeContract.G_PLAYBACK_HANDLER);
    if (!(fwHandlerObj instanceof org.deluge.playback.PlaybackHandler ph)) {
      return;
    }

    int playheadTick = ph.lastSwungTickActioned;
    int scroll = projectModel != null ? projectModel.getXScrollArrangementView() : 0;
    int ticksPerCol = arrangerController.arrangerTicksPerColumn();
    int playheadCol = (ticksPerCol > 0) ? ((playheadTick - scroll) / ticksPerCol) : -1;

    boolean isPlaying = ph.isPlaying();
    if (!isPlaying) {
      playheadCol = -1;
    }

    int songVoiceRows = gridMode.rows;
    int rowsToScan = songVoiceRows + 2; // Include Macros and Keyboard rows
    boolean isAdvanced =
        org.deluge.project.PreferencesManager.getGridPanelType()
            == org.deluge.project.PreferencesManager.GridPanelType.ADVANCED;

    // Playhead Follow Auto-Scrolling Mode!
    if (isPlaying && isPlayheadFollowMode() && ticksPerCol > 0) {
      int absolutePlayheadCol = playheadTick / ticksPerCol;
      int targetPageCol = (absolutePlayheadCol / stepCount) * stepCount;
      if (targetPageCol != scrollOffsetX) {
        final int fTargetPage = targetPageCol;
        javax.swing.SwingUtilities.invokeLater(
            () -> scrollController.scrollHorizontallyToPage(fTargetPage));
      }
    }

    EngineSyncCoordinator sync = null;
    if (SwingDelugeApp.mainInstance != null) {
      sync = SwingDelugeApp.mainInstance.getSyncCoordinator();
    }

    java.util.List<org.deluge.model.TrackModel> tracks = projectModel.getTracks();

    for (int t = 0; t < rowsToScan; t++) {
      int modelRow = -1;
      int engineR = -1;
      boolean isMuted = false;

      if (t < songVoiceRows) {
        modelRow = getModelRow(t);
        if (modelRow >= 0 && modelRow < voiceRowCount) {
          int engineROffset = getEngineRowOffset(modelRow);
          engineR = baseTrackId + engineROffset;
          if (sync != null) {
            int syncStart = sync.getTrackEngineStart(modelRow);
            if (syncStart >= 0) {
              engineR = syncStart;
            }
          }
          isMuted = bridge != null && bridge.getMute(engineR);
        }
      }

      for (int c = 0; c < columnCount; c++) {
        if (pads[t][c] == null) continue;

        boolean showPlayhead = (c == playheadCol && c < stepCount);
        if (isAdvanced) {
          if (pads[t][c] instanceof DelugePadButton pad) {
            pad.setPlayhead(showPlayhead);
          }
        } else {
          // Standard mode
          if (showPlayhead) {
            pads[t][c].setBackground(Color.WHITE);
          } else {
            // Restore default background color
            if (t < songVoiceRows) {
              if (isMuteColumn(c)) {
                pads[t][c].setBackground(
                    arrangerStatusColour(isMuted, soloRow == modelRow, soloRow >= 0));
              } else if (isSoloColumn(c)) {
                boolean isSoloed = (soloRow == modelRow);
                pads[t][c].setBackground(
                    isSoloed ? new Color(0x00, 0xff, 0xcc) : new Color(0x2d, 0x2d, 0x32));
              } else {
                boolean hasClip = false;
                Color arrCellColour = null;
                if (modelRow >= 0 && modelRow < tracks.size()) {
                  org.deluge.model.ArrangerClip ac =
                      arrangerController.getArrangerClipAt(modelRow, c);
                  hasClip = ac != null;
                  if (ac != null) {
                    int colTick = arrangerController.arrangerTickForColumn(c);
                    int colTicks = arrangerController.arrangerTicksPerColumn();
                    arrCellColour = ArrangementProjector.colourFor(ac, colTick, colTicks);
                  }
                }
                Color cellColour = arrCellColour != null ? arrCellColour : getTrackColour(modelRow);
                pads[t][c].setBackground(hasClip ? cellColour : new Color(0x33, 0x33, 0x33));
              }
            } else {
              // Macros / Keyboard rows
              pads[t][c].setBackground(new Color(0x15, 0x15, 0x15));
            }
          }
        }
      }
    }
  }
}
