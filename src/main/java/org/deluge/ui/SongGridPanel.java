package org.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.deluge.BridgeContract;
import org.deluge.model.SongSection;
import org.deluge.ui.SwingGridPanel.GridRow;

/** Specialized Song Launcher Grid Panel. */
public class SongGridPanel extends SwingGridPanel {

  // Blink phase for armed (queued) launch pads. Toggled by launchBlinkTimer; the mute column reads
  // it in refreshInPlace to flash the pad white while a clip is armed for the next bar boundary.
  private boolean launchBlinkOn = true;
  private javax.swing.Timer launchBlinkTimer;

  public SongGridPanel(BridgeContract bridge) {
    super(bridge);
    // 60ms blink (matching hardware kFastFlashTime); only repaints while something is actually
    // armed.
    launchBlinkTimer =
        new javax.swing.Timer(
            60,
            e -> {
              if (!anyTrackArmed()) return;
              launchBlinkOn = !launchBlinkOn;
              refreshInPlace();
            });
    launchBlinkTimer.start();
  }

  /** Whether any visible track has a clip queued for launch (used to gate the blink repaint). */
  private boolean anyTrackArmed() {
    if (bridge == null || projectModel == null) return false;
    for (int v = 0; v < gridMode.rows; v++) {
      int modelRow = songRowIndex(v);
      if (modelRow >= 0
          && modelRow < voiceRowCount
          && bridge.getLaunchQueue(baseTrackId + modelRow) >= 0) {
        return true;
      }
    }
    return false;
  }

  /**
   * The Deluge session status/mute square colour (view.cpp getClipMuteSquareColour): blue when
   * soloed, red when muted/stopped, green when active; dulled (channels clamped to 5..50) when
   * another track is soloing. (Session muted is red — stoppedColourMenu — unlike the arranger's
   * yellow.) Used by both the rebuild and in-place paths so structure-change and steady-state
   * renders agree.
   */
  private Color sessionStatusColour(
      boolean muted,
      boolean soloedHere,
      boolean anySoloing,
      org.deluge.model.ClipModel.PlayMode playMode) {
    Color base;
    if (soloedHere) {
      base = new Color(0, 0, 255);
    } else if (muted) {
      base = new Color(255, 0, 0);
    } else if (playMode == org.deluge.model.ClipModel.PlayMode.ONCE) {
      base = new Color(245, 190, 0);
    } else if (playMode == org.deluge.model.ClipModel.PlayMode.FILL) {
      base = new Color(185, 0, 220);
    } else {
      base = new Color(0, 255, 6);
    }
    return (anySoloing && !soloedHere) ? DelugeColour.dull(base) : base;
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
    int songVoiceRows = gridMode.rows; // always draw full viewport slots

    // ── Section bar (A-Z) for SONG mode ──
    java.util.List<SongSection> sections = getProjectModel().getSongSections();
    if (!isFaceplate && sections != null && !sections.isEmpty()) {
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

      if (!isFaceplate) {
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
      } else {
        rowPanel.add(Box.createRigidArea(new Dimension((int) Math.round(58 * faceScale), 1)));
      }

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
          clipBtn = new MacroSliderButton(this, colId, allParams[colId]);
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

          if (t >= songVoiceRows && (isMuteColumn(colId) || isSoloColumn(colId))) {
            clipBtn.setVisible(false);
            clipBtn.setEnabled(false);
          } else if (isMuteColumn(colId)) {
            final int engineRow = trk;
            org.deluge.model.TrackModel track = tracks.get(trk);
            boolean anySoloing = !soloedTracks.isEmpty();
            boolean curMute = anySoloing ? !soloedTracks.contains(trk) : track.isMuted();
            org.deluge.model.ClipModel.PlayMode pMode = org.deluge.model.ClipModel.PlayMode.NORMAL;
            org.deluge.model.ClipModel activeClip = track.getActiveClip();
            if (activeClip != null) {
              pMode = activeClip.getPlayMode();
            }
            Color muteBg =
                sessionStatusColour(curMute, soloedTracks.contains(trk), anySoloing, pMode);
            clipBtn.setText(
                curMute ? "UNMUTE" : "MUTE"); // getText() = mute state (E2E observes it)
            clipBtn.setBackground(muteBg);
            clipBtn.setFont(new Font("SansSerif", Font.BOLD, padSz > 70 ? 11 : 9));
            if (clipBtn instanceof DelugePadButton pad) {
              pad.setBaseColor(muteBg);
              pad.setIntensity(1.0f);
              pad.setActive(true);
            }
            clipBtn.setToolTipText(curMute ? "Muted — click to unmute" : "Mute track");
            clearActionListeners(clipBtn);
            clipBtn.addActionListener(
                e -> {
                  System.out.println(
                      "DEBUG-MUTE: listener track hash="
                          + System.identityHashCode(track)
                          + " before="
                          + track.isMuted());
                  track.setMuted(!track.isMuted());
                  System.out.println(
                      "DEBUG-MUTE: listener track hash="
                          + System.identityHashCode(track)
                          + " after="
                          + track.isMuted());
                  updateEngineMutes();
                  refresh();
                });
            JPopupMenu mutePopup = createMutePopupMenu(engineRow);
            clipBtn.setComponentPopupMenu(mutePopup);
          } else if (isSoloColumn(colId)) {
            Color labelBg;
            Color labelFg;
            boolean rowHasClip = !tracks.get(trk).getClips().isEmpty();
            if (soloedTracks.contains(trk)) {
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
                  boolean isMulti =
                      (e.getModifiers()
                              & (java.awt.event.ActionEvent.SHIFT_MASK
                                  | java.awt.event.ActionEvent.CTRL_MASK))
                          != 0;
                  if (soloedTracks.contains(trk)) {
                    soloedTracks.remove(trk);
                  } else {
                    if (!isMulti) {
                      soloedTracks.clear();
                    }
                    soloedTracks.add(trk);
                  }
                  updateEngineMutes();
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
              renderSongPatternPad(clipBtn, currentTrack, colId);

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
                          createItem.setForeground(new Color(0x00, 0xff, 0xcc));

                          if (getCopiedClip() != null) {
                            JMenuItem pasteItem = new JMenuItem("Paste Clip");
                            pasteItem.addActionListener(
                                ev -> {
                                  while (songTrack.getClips().size() <= clipCol) {
                                    songTrack.addClip(
                                        new org.deluge.model.ClipModel(
                                            "CLIP " + (songTrack.getClips().size() + 1),
                                            songTrack.getClips().isEmpty()
                                                ? 8
                                                : songTrack.getClips().get(0).getRowCount(),
                                            16));
                                  }
                                  org.deluge.model.ClipModel copied = getCopiedClip();
                                  org.deluge.model.ClipModel copy =
                                      copied.deepCopy("CLIP " + (clipCol + 1));
                                  songTrack.getClips().set(clipCol, copy);
                                  fireProjectChanged();
                                  refresh();
                                });
                            emptyClipMenu.add(pasteItem);
                            pasteItem.setForeground(new Color(0x00, 0xff, 0xcc));
                          }

                          stylePopupMenu(emptyClipMenu);
                          emptyClipMenu.show(clipBtn, e.getX(), e.getY());
                        }
                      } else {
                        if (clipCol >= songTrack.getClips().size()) {
                          if (e.getClickCount() == 1) {
                            String name = "CLIP " + (clipCol + 1);
                            songTrack.addClip(
                                new org.deluge.model.ClipModel(
                                    name,
                                    songTrack.getClips().isEmpty()
                                        ? 8
                                        : songTrack.getClips().get(0).getRowCount(),
                                    16));
                            editedModelTrack = trkIdx;
                            if (SwingDelugeApp.mainInstance != null) {
                              SwingDelugeApp.mainInstance.refreshTrackInspector();
                            }
                            fireProjectChanged();
                          }
                        } else {
                          if (e.getClickCount() == 2) {
                            if (SwingDelugeApp.mainInstance != null) {
                              SwingDelugeApp.mainInstance.switchToTrackEdit(trkIdx, clipCol);
                            }
                          } else {
                            editedModelTrack = trkIdx;
                            if (SwingDelugeApp.mainInstance != null) {
                              SwingDelugeApp.mainInstance.refreshTrackInspector();
                            }
                            if (e.isShiftDown()) {
                              // Instant launch
                              songTrack.setActiveClipIndex(clipCol);
                              bridge.setCurrentClip(trkIdx, clipCol);
                              if (SwingDelugeApp.mainInstance != null
                                  && SwingDelugeApp.mainInstance.getArrangerScheduler() != null) {
                                SwingDelugeApp.mainInstance
                                    .getArrangerScheduler()
                                    .notifyClipLaunched(trkIdx, songTrack.getClips().get(clipCol));
                              }
                              fireProjectChanged();
                              refresh();
                            } else {
                              // Quantized launch
                              bridge.setLaunchQueue(trkIdx, clipCol);
                              refresh();
                            }
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
      if (t < songVoiceRows + 1) {
        if (isFaceplate) {
          voicePanel.add(Box.createRigidArea(new Dimension(1, (int) Math.round(41 * faceScale))));
        } else {
          voicePanel.add(Box.createVerticalStrut(5));
        }
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

  /**
   * Deluge-faithful session render for one main pad: colour column {@code c} by the note lighting
   * that step — the pitch's velocity-scaled main colour for a note head, {@code forTail} for a held
   * note's tail, black when unlit (via {@link SongProjector#noteColourAt}). Parity with the
   * hardware's session_view.cpp renderAsSingleRow / note_row.cpp renderRow, replacing the
   * Ableton-style clip-slot launcher look. The final LED colour is baked by the projector, so the
   * pad blits it verbatim (intensity 1.0, tail flag off). Used by both the rebuild and the in-place
   * recolour so structure-change and steady-state renders agree.
   */
  private void renderSongPatternPad(JButton clipBtn, int modelRow, int c) {
    org.deluge.model.TrackModel track = projectModel.getTracks().get(modelRow);
    java.util.List<org.deluge.model.ClipModel> clips = track.getClips();
    org.deluge.model.ClipModel clip = clips.isEmpty() ? null : clips.get(0);
    Color cell =
        clip == null
            ? null
            : SongProjector.cellColour(clip, track.getColourOffset(), c + scrollOffsetX);
    boolean lit = cell != null;
    Color base = lit ? cell : Color.BLACK;
    boolean isMuted = bridge != null && bridge.getMute(baseTrackId + modelRow);
    if (clipBtn instanceof DelugePadButton pad) {
      pad.setBaseColor(base);
      pad.setTheme(org.deluge.project.PreferencesManager.getGridColorTheme());
      pad.setActive(lit);
      pad.setTail(false);
      pad.setMuted(isMuted);
      pad.setIntensity(1.0f);
      pad.setScaleRoot(false);
      pad.setScaleNote(false);
      pad.setNoteText("");
      pad.setText("");
    } else {
      clipBtn.setBackground(lit ? base : new Color(0x1a, 0x1a, 0x1a));
    }
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

    for (int v = 0; v < gridMode.rows; v++) {
      int modelRow = songRowIndex(v);
      if (modelRow >= 0 && modelRow < voiceRowCount) {
        int engineRow = baseTrackId + modelRow;
        if (sync != null) {
          int syncStart = sync.getTrackEngineStart(modelRow);
          if (syncStart >= 0) {
            engineRow = syncStart;
          }
        }
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
            org.deluge.model.ClipModel.PlayMode pMode = org.deluge.model.ClipModel.PlayMode.NORMAL;
            org.deluge.model.ClipModel activeClip = tracks.get(modelRow).getActiveClip();
            if (activeClip != null) {
              pMode = activeClip.getPlayMode();
            }
            Color statusBg =
                sessionStatusColour(
                    isMuted, soloedTracks.contains(modelRow), !soloedTracks.isEmpty(), pMode);
            if (isLiveRecordModeActive && modelRow == editedModelTrack) {
              statusBg = launchBlinkOn ? Color.RED : Color.BLACK;
            }
            // Armed for launch (queued for the next bar boundary): fast-blink the pad, matching the
            // hardware's blinking "launch" pad, until the queue is consumed.
            boolean armed = bridge != null && bridge.getLaunchQueue(engineRow) >= 0;
            if (armed && !launchBlinkOn) {
              statusBg = Color.BLACK;
            }
            clipBtn.setBackground(statusBg);
            clipBtn.setText(
                isMuted ? "UNMUTE" : "MUTE"); // getText() = mute state (E2E observes it)
            if (clipBtn instanceof DelugePadButton pad) {
              pad.setBaseColor(statusBg);
              pad.setIntensity(1.0f);
              pad.setActive(true);
            }
            clipBtn.setToolTipText(
                isLiveRecordModeActive && modelRow == editedModelTrack
                    ? "Recording active track"
                    : (armed
                        ? "Armed — launches at the next bar"
                        : (isMuted ? "Muted — click to unmute" : "Mute track")));
          } else if (isSoloColumn(c)) {
            // Deluge section square (session_view.cpp drawSectionSquare): the clip's section
            // colour,
            // black when the row has no clip. Solo/mute state lives on the status square, NOT here.
            Color labelBg;
            Color labelFg;
            boolean rowHasClip = !tracks.get(modelRow).getClips().isEmpty();
            if (rowHasClip) {
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
            if (isLiveRecordModeActive && modelRow == editedModelTrack) {
              labelBg = Color.RED;
              labelFg = Color.WHITE;
            }
            clipBtn.setBackground(labelBg);
            clipBtn.setForeground(labelFg);

            String nName = tracks.get(modelRow).getName();
            if (isLiveRecordModeActive && modelRow == editedModelTrack) {
              clipBtn.setText("● REC");
            } else {
              clipBtn.setText(nName);
            }

            if (clipBtn instanceof DelugePadButton pad) {
              pad.setBaseColor(labelBg);
              pad.setTextColorOverride(labelFg);
              pad.setIntensity(1.0f);
              pad.setActive(true);
              pad.setNoteText(nName);
            }
          } else {
            renderSongPatternPad(clipBtn, modelRow, c);
          }
        }
      }
    }
    repaint();
  }
}
