package org.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.deluge.BridgeContract;
import org.deluge.model.ScaleMapper;
import org.deluge.model.TrackModel;
import org.deluge.project.PreferencesManager;

/** Specialized Clip Editor sequencer grid panel. */
public class ClipGridPanel extends SwingGridPanel {

  public ClipGridPanel(BridgeContract bridge) {
    super(bridge);
  }

  @Override
  public void rebuildUIComponents() {
    if (viewMode == GridViewMode.KEYPLAY) {
      rebuildKeyplayComponents();
      return;
    }
    stopAuditionIfNeeded();
    refreshInProgress = true;
    removeAll();
    java.util.List<org.deluge.model.TrackModel> tracks = projectModel.getTracks();

    voiceRowCount = computeVoiceRowCount();
    this.stepCount = gridMode.columns;
    this.columnCount = this.stepCount + 2; // grid mode column count + MUTE/SOLO

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

    // Section 2: Scrollable voice rows — always show gridMode.rows slots in the viewport
    voicePanel = new JPanel();
    voicePanel.setBackground(new Color(0x15, 0x15, 0x15));
    voicePanel.setOpaque(true);
    voicePanel.setLayout(new BoxLayout(voicePanel, BoxLayout.Y_AXIS));
    for (int v = 0; v < gridMode.rows; v++) {
      if (isFaceplate && v > 0) {
        voicePanel.add(Box.createRigidArea(new Dimension(1, 5)));
      }
      int modelRow = getModelRow(v);
      if (modelRow >= 0 && modelRow < voiceRowCount) {
        JPanel row = buildVoiceRow(modelRow, v, padSz, tracks);
        voicePanel.add(row);
      } else {
        JPanel blankRow = new JPanel();
        blankRow.setPreferredSize(new Dimension(rowW, padSz));
        blankRow.setMaximumSize(new Dimension(rowW, padSz));
        blankRow.setBackground(new Color(0x22, 0x22, 0x22));
        voicePanel.add(blankRow);
      }
    }

    // Wrapper for Scroll Panel
    JPanel voiceWrapper = new JPanel(new BorderLayout());
    voiceWrapper.setBackground(new Color(0x15, 0x15, 0x15));
    boolean isSynthTrack = false;
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      isSynthTrack =
          projectModel.getTracks().get(editedModelTrack)
              instanceof org.deluge.model.SynthTrackModel;
    }
    boolean showNavPanel = (voiceRowCount > gridMode.rows) || isSynthTrack || isFaceplate;

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
      pgUpBtn.setToolTipText("Page Up / Octave Up (Shift 8 rows up)");
      pgUpBtn.addActionListener(e -> scrollVertically(-gridMode.rows));

      JButton pgDnBtn = new JButton("▼");
      pgDnBtn.setUI(new javax.swing.plaf.basic.BasicButtonUI());
      pgDnBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
      pgDnBtn.setForeground(new Color(0x00, 0xff, 0xcc));
      pgDnBtn.setBackground(new Color(0x1f, 0x1f, 0x24));
      pgDnBtn.setBorder(BorderFactory.createEmptyBorder(6, 2, 6, 2));
      pgDnBtn.setFocusable(false);
      pgDnBtn.setToolTipText("Page Down / Octave Down (Shift 8 rows down)");
      pgDnBtn.addActionListener(e -> scrollVertically(gridMode.rows));

      boolean showScrollControls = (voiceRowCount > gridMode.rows) || isFaceplate;
      pgUpBtn.setVisible(showScrollControls);
      vertScrollBar.setVisible(showScrollControls);

      pageNavPanel.add(pgUpBtn, BorderLayout.NORTH);
      pageNavPanel.add(vertScrollBar, BorderLayout.CENTER);

      int gridRows = showScrollControls ? 2 : 1;
      JPanel southPanel = new JPanel(new java.awt.GridLayout(gridRows, 1, 0, 4));
      southPanel.setBackground(new Color(0x15, 0x15, 0x18));
      if (showScrollControls) {
        southPanel.add(pgDnBtn);
      }

      JButton foldBtn = new JButton(foldMode ? "UNFLD" : "FOLD");
      foldBtn.setUI(new javax.swing.plaf.basic.BasicButtonUI());
      foldBtn.setFont(new Font("SansSerif", Font.BOLD, 8));
      foldBtn.setForeground(foldMode ? Color.BLACK : new Color(0x00, 0xff, 0xcc));
      foldBtn.setBackground(foldMode ? new Color(0x00, 0xff, 0xcc) : new Color(0x1f, 0x1f, 0x24));
      foldBtn.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
      foldBtn.setFocusable(false);
      foldBtn.setToolTipText("Fold Mode (Only show rows containing notes)");
      foldBtn.addActionListener(
          evt -> {
            foldMode = !foldMode;
            resetScrollOffset();
            refresh();
          });
      southPanel.add(foldBtn);
      pageNavPanel.add(southPanel, BorderLayout.SOUTH);

      voiceWrapper.add(pageNavPanel, BorderLayout.EAST);
    }
    add(voiceWrapper);

    // Page Selection Bar in CLIP mode
    int trackLen = (bridge != null) ? bridge.getTrackLength(baseTrackId) : stepCount;
    int numPages = Math.max(1, (trackLen + 15) / 16);
    if (numPages > 1) {
      JPanel pageBar = new JPanel();
      pageBar.setLayout(new BoxLayout(pageBar, BoxLayout.X_AXIS));
      pageBar.setBackground(new Color(0x15, 0x15, 0x15));
      pageBar.setPreferredSize(new Dimension(rowW, 20));
      pageBar.setMinimumSize(new Dimension(100, 20));
      pageBar.setMaximumSize(new Dimension(rowW, 20));
      pageBar.setAlignmentX(Component.LEFT_ALIGNMENT);

      JLabel pageLabel = new JLabel("PAGE SELECT: ");
      pageLabel.setFont(new Font("SansSerif", Font.BOLD, 9));
      pageLabel.setForeground(Color.GRAY);
      pageBar.add(pageLabel);

      this.pageButtons.clear();
      int currentPageIndex = scrollOffsetX / 16;
      for (int i = 0; i < numPages; i++) {
        final int pageIdx = i;
        JButton pageBtn = new JButton(String.valueOf(i + 1));
        pageBtn.setFont(new Font("Monospaced", Font.BOLD, 10));
        pageBtn.setFocusable(false);
        pageBtn.setPreferredSize(new Dimension(20, 16));
        pageBtn.setMinimumSize(new Dimension(20, 16));
        pageBtn.setMaximumSize(new Dimension(20, 16));
        pageBtn.setMargin(new Insets(0, 0, 0, 0));
        pageBtn.setOpaque(false);
        pageBtn.setContentAreaFilled(false);
        pageBtn.setFocusPainted(false);

        if (i == currentPageIndex) {
          pageBtn.setForeground(new Color(0x00, 0xff, 0xcc));
          pageBtn.setBorder(BorderFactory.createLineBorder(new Color(0x00, 0xff, 0xcc), 1));
        } else {
          pageBtn.setForeground(Color.GRAY);
          pageBtn.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
        }

        pageBtn.addActionListener(
            e -> {
              scrollOffsetX = pageIdx * 16;
              JScrollBar horizBar = scrollController.getHorizontalScrollBar();
              if (horizBar != null) {
                horizBar.setValue(scrollOffsetX);
              }
              refresh();
            });
        pageButtons.add(pageBtn);
        pageBar.add(pageBtn);
        pageBar.add(Box.createRigidArea(new Dimension(4, 10)));
      }
      add(pageBar);
    }

    // Lazily creates the scrollbar and wires its AdjustmentListener (GridScrollController) so
    // horizontal scroll state stays consistent even though the widget itself is never displayed
    // (X_ENC / the page-select bar above are the live ways to change scrollOffsetX).
    this.horizScrollBar = scrollController.getHorizontalScrollBar();

    // Fixed rows: MACROS (16 macro-param sliders) and KEYBOARD (mini isomorphic strip), matching
    // SongGridPanel/ArrangerGridPanel where the same rows are unconditional.
    int macroRowIdx = gridMode.rows;
    int keyboardRowIdx = gridMode.rows + 2;
    int macroHeight = (int) (padSz * 1.1);
    int keyboardHeight = (int) (padSz * 0.6);
    add(buildFixedRow("MACROS", macroRowIdx, padSz, Math.max(28, macroHeight)));
    add(buildFixedRow("KEYBOARD", keyboardRowIdx, padSz, Math.max(16, keyboardHeight)));

    revalidate();
    repaint();
    refreshInProgress = false;
    scrollController.updateScrollBarValues();
  }

  @Override
  public void refreshInPlace() {
    if (projectModel == null) return;
    if (viewMode == GridViewMode.KEYPLAY) {
      refreshKeyplayInPlace();
      return;
    }
    updatePageBarHighlights();
    scrollController.syncScrollBarValues();

    java.util.List<org.deluge.model.TrackModel> tracks = projectModel.getTracks();
    int curTrackLen = bridge != null ? bridge.getTrackLength(baseTrackId) : stepCount;
    boolean isSynthMode = bridge != null && bridge.getTrackType(baseTrackId) == 1;

    for (int v = 0; v < gridMode.rows; v++) {
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
                "Clip View: Row "
                    + (v + 1)
                    + " Mute / Unmute (Right-Click for Options / Shift-Click to Clear Steps)");
          } else if (isSoloColumn(c)) {
            boolean isSynth = false;
            if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
              org.deluge.model.TrackModel tm = projectModel.getTracks().get(editedModelTrack);
              isSynth = tm instanceof org.deluge.model.SynthTrackModel;
            }
            String nName;
            if (isSynth) {
              int midiPitch = getRowPitch(modelRow);
              String[] noteNames =
                  new String[] {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
              nName = noteNames[Math.max(0, midiPitch) % 12] + ((midiPitch / 12) - 1);
            } else if (projectModel != null
                && editedModelTrack < projectModel.getTracks().size()
                && projectModel.getTracks().get(editedModelTrack)
                    instanceof org.deluge.model.KitTrackModel kit) {
              nName =
                  (modelRow < kit.getDrums().size())
                      ? kit.getDrums().get(modelRow).getName()
                      : ("PAD " + (modelRow + 1));
              if (nName.toLowerCase().endsWith(".wav") || nName.toLowerCase().endsWith(".aif")) {
                nName = nName.substring(0, nName.lastIndexOf('.'));
              }
            } else {
              nName = "ROW " + (modelRow + 1);
            }
            clipBtn.setText(nName);
            Color labelBg = getAuditionPadBgColor(modelRow, isSynth, v);
            Color labelFg = getAuditionPadFgColor(modelRow, isSynth, v);
            clipBtn.setBackground(labelBg);
            clipBtn.setForeground(labelFg);
            if (clipBtn instanceof DelugePadButton pad) {
              pad.setBaseColor(labelBg);
              pad.setTextColorOverride(labelFg);
              pad.setActive(true);
              pad.setIntensity(1.0f);
            }
            clipBtn.setToolTipText(
                "Clip View: Audition / Preview Row " + (v + 1) + " Note (" + nName + ")");
          } else {
            int activeCol;
            if (curTrackLen > 0 && curTrackLen < stepCount) {
              activeCol = c % curTrackLen;
            } else if (curTrackLen > stepCount) {
              activeCol = Math.min(c + scrollOffsetX, curTrackLen - 1);
            } else {
              activeCol = c;
            }

            double[] outVelProb = {0.8, 1.0};
            boolean stepState = isStepActiveOrSpanned(modelRow, activeCol, outVelProb);
            double vel = outVelProb[0];
            double prob = outVelProb[1];
            boolean inLoop = activeCol < curTrackLen;

            if (clipBtn instanceof DelugePadButton pad) {
              org.deluge.project.PreferencesManager.GridColorTheme theme =
                  org.deluge.project.PreferencesManager.getGridColorTheme();
              Color trackColor = getGridNoteColor(modelRow);
              boolean inScale = true;
              boolean isRoot = false;

              if (isSynthMode) {
                int pitchMidi = getRowPitch(modelRow);
                isRoot = ScaleMapper.isRootNote(pitchMidi, projectModel.getKey());
                inScale =
                    ScaleMapper.isNoteInScale(
                        pitchMidi, projectModel.getKey(), projectModel.getScale());
              }

              Color cellBaseColor =
                  getThemeColor(theme, trackColor, stepState, inScale, isRoot, modelRow);
              boolean isNudged = false;
              if (stepState) {
                org.deluge.model.ClipModel cModel = null;
                if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
                  org.deluge.model.TrackModel tModel =
                      projectModel.getTracks().get(editedModelTrack);
                  if (activeClipId < tModel.getClips().size()) {
                    cModel = tModel.getClips().get(activeClipId);
                  }
                }
                if (cModel != null) {
                  org.deluge.model.StepData sd = getClipStep(cModel, modelRow, activeCol);
                  if (sd != null && sd.active()) {
                    if (sd.fill() > 0.0f) {
                      cellBaseColor =
                          new Color(0, 0, 255); // FILL note = colours::blue (note_row.cpp:1993)
                    } else if (sd.nudge() > 0.0f) {
                      isNudged = true;
                    }
                  }
                } else if (bridge != null) {
                  if (bridge.getStepFill(engineR, activeCol) > 0.0) {
                    isNudged = true;
                  }
                }
              }
              pad.setBaseColor(cellBaseColor);
              pad.setBlur(isNudged);
              pad.setApplicable(inScale || !isSynthMode);
              pad.setTheme(theme);
              org.deluge.model.ClipModel curClip = activeEditedClip();
              boolean isTrip = curClip != null && curClip.isTripletMode();
              pad.setBeatMarker(
                  ((c + scrollOffsetX) % (isTrip ? 3 : 4) == 0)
                      || (isTrip && (c + scrollOffsetX) % 4 == 0));
              pad.setScaleRoot(isRoot);
              pad.setScaleNote(inScale);

              pad.setMuted(isMuted);
              pad.setInLoop(inLoop);
              pad.setActive(stepState);
              pad.setIntensity(
                  stepState
                      ? (float) vel
                      : 1.0f); // C: velocity-only head brightness (pad applies (65+1.5v)/255);
              // tails full
              pad.setTail(isStepTied(modelRow, activeCol) && !stepState);
              if (stepState) {
                if (isSynthMode) {
                  int pitchMidi = getRowPitch(modelRow);
                  pad.setNoteText(getNoteName(pitchMidi));
                } else {
                  pad.setNoteText(String.format("v%d", (int) (vel * 100)));
                }
              } else {
                pad.setNoteText("");
              }
            } else {
              clipBtn.setBackground(
                  stepState
                      ? getGridNoteColor(modelRow, (float) vel)
                      : getStepPadDefaultBg(modelRow, c));
            }
          }
        }
      }
    }
  }

  @Override
  public void toggleTripletMode() {
    org.deluge.model.ClipModel fActiveClip = activeEditedClip();
    if (fActiveClip != null) {
      boolean nextTrip = !fActiveClip.isTripletMode();
      fActiveClip.setTripletMode(nextTrip);
      fActiveClip.setStepCount(nextTrip ? 12 : 16);
      fActiveClip.rebuildNotesFromGrid();
      if (bridge != null) {
        bridge.setTrackLength(baseTrackId, nextTrip ? 12 : 16);
        bridge.setStepResolution(nextTrip ? (1.0 / 3.0) : 0.25);
      }
      refresh();
    }
  }

  private JPanel buildVoiceRow(
      int visualRowIndex,
      int visibleRow,
      int padSz,
      java.util.List<org.deluge.model.TrackModel> tracks) {
    boolean isSynth = false;
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      isSynth =
          projectModel.getTracks().get(editedModelTrack)
              instanceof org.deluge.model.SynthTrackModel;
    }
    final int modelRow =
        isSynth ? (127 - getRowPitch(scrollOffset + visibleRow)) : getModelRow(visibleRow);
    String samplePathLoc = null;
    if (modelRow < tracks.size()) {
      org.deluge.model.TrackModel track = tracks.get(modelRow);
      if (track instanceof org.deluge.model.KitTrackModel kit) {
        java.util.List<org.deluge.model.Drum> sounds = kit.getDrums();
        int drumIdx = modelRow;
        if (drumIdx >= 0 && drumIdx < sounds.size()) {
          org.deluge.model.Drum drum = sounds.get(drumIdx);
          if (drum instanceof org.deluge.model.SoundDrum soundDrum) {
            samplePathLoc = soundDrum.getSamplePath();
          }
        }
      } else if (track instanceof org.deluge.model.AudioTrackModel audioTrack
          && !audioTrack.getAudioClips().isEmpty()) {
        samplePathLoc = audioTrack.getAudioClips().get(0).getFilePath();
      }
    }
    final String targetSamplePath = samplePathLoc;

    JPanel rowPanel =
        new JPanel() {
          @Override
          protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (targetSamplePath == null || targetSamplePath.isEmpty()) return;
            float[] points = getCachedWaveform(targetSamplePath);
            if (points == null || points.length == 0) return;

            int startX = 0;
            int endX = getWidth();
            boolean foundPads = false;
            for (Component c : getComponents()) {
              if (c instanceof DelugePadButton pad) {
                Integer col = (Integer) pad.getClientProperty("col");
                if (col != null) {
                  if (col == 0) {
                    startX = pad.getX();
                    foundPads = true;
                  }
                  if (col == stepCount - 1) {
                    endX = pad.getX() + pad.getWidth();
                  }
                }
              }
            }

            int width = endX - startX;
            if (!foundPads || width <= 0) return;

            Graphics2D g2d = (Graphics2D) g;
            g2d.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setColor(new Color(0x00, 0xff, 0x66, 0x1a));

            int midY = getHeight() / 2;
            int maxAmp = (int) (getHeight() * 0.45);

            java.awt.geom.Path2D.Float path = new java.awt.geom.Path2D.Float();
            for (int i = 0; i < points.length; i++) {
              int x = startX + (int) ((i / (float) (points.length - 1)) * width);
              int yOffset = (int) (points[i] * maxAmp);
              if (i == 0) {
                path.moveTo(x, midY - yOffset);
              } else {
                path.lineTo(x, midY - yOffset);
              }
            }
            for (int i = points.length - 1; i >= 0; i--) {
              int x = startX + (int) ((i / (float) (points.length - 1)) * width);
              int yOffset = (int) (points[i] * maxAmp);
              path.lineTo(x, midY + yOffset);
            }
            path.closePath();
            g2d.fill(path);
          }
        };

    boolean isFaceplateRow =
        org.deluge.project.PreferencesManager.getTopPanelStyle()
            == org.deluge.project.PreferencesManager.TopPanelStyle.HARDWARE_FACEPLATE;
    double faceScaleRow = Math.max(800, getWidth()) / 2256.0;
    int lw = currentLabelWidth();
    int rowW = isFaceplateRow ? (int) Math.round(2270 * faceScaleRow) : getGridWidth(padSz, lw);
    rowPanel.setLayout(new BoxLayout(rowPanel, BoxLayout.X_AXIS));
    rowPanel.setBackground(new Color(0x22, 0x22, 0x22));
    rowPanel.setPreferredSize(new Dimension(rowW, padSz));
    rowPanel.setMinimumSize(new Dimension(rowW, padSz));
    rowPanel.setMaximumSize(new Dimension(rowW, padSz));
    rowPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

    if (modelRow < tracks.size()) {
      org.deluge.model.TrackModel t = tracks.get(modelRow);
      String hex = t.getColourHex();
      if (hex != null && hex.startsWith("0x")) {
        try {
          trackColors[modelRow % trackColors.length] =
              new Color(Integer.decode(hex.substring(0, 8)));
        } catch (Exception e) {
          LOG.warning("Bad color hex for track " + modelRow + ": " + e.getMessage());
        }
      } else {
        int stored = 0;
        try {
          if (hex != null && !hex.isBlank()) stored = Integer.parseInt(hex.trim());
        } catch (NumberFormatException ignore) {
        }
        trackColors[modelRow % trackColors.length] = DelugeColour.sessionColour(stored, modelRow);
      }
    }

    String trackName;
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      org.deluge.model.TrackModel rowTrack = projectModel.getTracks().get(editedModelTrack);
      if (rowTrack instanceof org.deluge.model.KitTrackModel kit) {
        java.util.List<org.deluge.model.Drum> sounds = kit.getDrums();
        trackName =
            (modelRow < sounds.size() && modelRow >= 0)
                ? sounds.get(modelRow).getName()
                : rowTrack.getName();
      } else {
        int pitchMidi = getRowPitch(modelRow);
        trackName = getNoteName(pitchMidi);
      }
    } else {
      trackName =
          (modelRow < tracks.size()) ? tracks.get(modelRow).getName() : "EMPTY " + (modelRow + 1);
    }

    final int trk = visibleRow;
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
                  hotSwapTrackSample(modelRow, visibleRow, soundFile);
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

    if (isFaceplateRow) {
      // Faceplate mode has no room for a full text label, but the same `label` widget (with its
      // rename/color/inspector/move context menu, shift-click one-shot toggle, click-to-edit, and
      // sample drag-drop) is still reachable, shrunk to a color swatch in the same reserved space
      // a blank spacer previously occupied.
      int swatchW = (int) Math.round(58 * faceScaleRow);
      label.setPreferredSize(new Dimension(swatchW, padSz));
      label.setMinimumSize(new Dimension(swatchW, padSz));
      label.setMaximumSize(new Dimension(swatchW, padSz));
      label.setText("");
      label.setOpaque(true);
      label.setBackground(getTrackBaseColor());
      label.setToolTipText(
          "<html><b>"
              + tName
              + "</b><br>Click: edit track &nbsp; Shift-click: toggle one-shot<br>Right-click:"
              + " track menu &nbsp; Drag a sample here: hot-swap</html>");
      rowPanel.add(label);
    } else {
      rowPanel.add(label);
      if (modelRow < tracks.size() && modelRow < 8) {
        int stepLen = (bridge != null) ? bridge.getTrackLength(modelRow) : 16;
        JLabel lenBadge = new JLabel("[" + stepLen + "]");
        lenBadge.setPreferredSize(new Dimension(48, 26));
        lenBadge.setMinimumSize(new Dimension(48, 26));
        lenBadge.setMaximumSize(new Dimension(48, 26));
        lenBadge.setFont(new Font("Monospaced", Font.BOLD, 11));
        lenBadge.setForeground(stepLen == 16 ? Color.GRAY : new Color(0xff, 0xcc, 0x00));
        lenBadge.setCursor(new Cursor(Cursor.HAND_CURSOR));
        lenBadge.addMouseListener(
            new java.awt.event.MouseAdapter() {
              @Override
              public void mouseClicked(java.awt.event.MouseEvent e) {
                if (javax.swing.SwingUtilities.isRightMouseButton(e) || e.getClickCount() == 2) {
                  String input =
                      JOptionPane.showInputDialog(
                          ClipGridPanel.this, "Track length (1-192):", stepLen);
                  if (input != null) {
                    try {
                      int newLen = Integer.parseInt(input.trim());
                      if (newLen >= 1 && newLen <= BridgeContract.STEPS) {
                        bridge.setTrackLength(modelRow, newLen);
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

      vuManager.registerVoiceVu(modelRow, vu);
    }

    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      org.deluge.model.TrackModel activeTrack = projectModel.getTracks().get(editedModelTrack);
      if (activeTrack instanceof org.deluge.model.KitTrackModel kitTrack) {
        java.util.List<org.deluge.model.Drum> drumsList = kitTrack.getDrums();
        int soundIndex = modelRow;
        if (soundIndex >= 0 && soundIndex < drumsList.size()) {
          JButton drumCfgBtn = new JButton("⚙");
          drumCfgBtn.setPreferredSize(new Dimension(20, 20));
          drumCfgBtn.setMinimumSize(new Dimension(20, 20));
          drumCfgBtn.setMaximumSize(new Dimension(20, 20));
          drumCfgBtn.setMargin(new Insets(0, 0, 0, 0));
          drumCfgBtn.setFont(new Font("SansSerif", Font.PLAIN, 10));
          drumCfgBtn.setForeground(new Color(0x00, 0xff, 0xcc));
          drumCfgBtn.setOpaque(false);
          drumCfgBtn.setContentAreaFilled(false);
          drumCfgBtn.setFocusPainted(false);
          drumCfgBtn.setBorder(BorderFactory.createLineBorder(new Color(0x00, 0xff, 0xcc), 1));
          drumCfgBtn.setFocusable(false);
          drumCfgBtn.setToolTipText("Open full settings editor for drum slot: " + tName);
          drumCfgBtn.addActionListener(
              e -> {
                Window owner = SwingUtilities.getWindowAncestor(rowPanel);
                SwingKitConfigDialog dialog =
                    new SwingKitConfigDialog((Frame) owner, kitTrack, bridge, editedModelTrack);
                dialog.setSelectedTab(soundIndex);
                dialog.setVisible(true);
              });
          rowPanel.add(Box.createHorizontalStrut(3));
          rowPanel.add(drumCfgBtn);
        }
      }
    }

    rowPanel.add(Box.createHorizontalStrut(5));

    for (int c = 0; c < columnCount; c++) {
      final int colId = c;
      JButton clipBtn;
      boolean isAdvanced =
          org.deluge.project.PreferencesManager.getGridPanelType()
              == org.deluge.project.PreferencesManager.GridPanelType.ADVANCED;
      if (isAdvanced) {
        DelugePadButton pad = new DelugePadButton();
        pad.putClientProperty("row", visibleRow);
        pad.putClientProperty("col", c);
        clipBtn = pad;
      } else {
        clipBtn = new CleanJButton();
        clipBtn.setFocusable(false);
      }

      clipBtn.setPreferredSize(new Dimension(padSz, padSz));
      clipBtn.setMinimumSize(new Dimension(padSz, padSz));
      clipBtn.setMaximumSize(new Dimension(padSz, padSz));
      clipBtn.setMargin(new Insets(0, 0, 0, 0));

      pads[visibleRow][c] = clipBtn;

      if (isMuteColumn(colId)) {
        final int trackToMute = isEditedTrackKit() ? (baseTrackId + modelRow) : editedModelTrack;
        final int engineRow = baseTrackId + modelRow;
        boolean isMuted;
        if (isEditedTrackKit()) {
          org.deluge.model.ClipModel clip = activeEditedClip();
          org.deluge.model.NoteRowModel noteRow =
              (clip != null) ? clip.getOrCreateRow(modelRow) : null;
          isMuted =
              (noteRow != null)
                  ? noteRow.isMuted()
                  : (bridge != null && bridge.getMute(trackToMute));
        } else {
          if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
            isMuted = projectModel.getTracks().get(editedModelTrack).isMuted();
          } else {
            isMuted = bridge != null && bridge.getMute(trackToMute);
          }
        }

        Color muteBg;
        Color muteFg;
        if (isEditedTrackKit()) {
          Color rowColor = getGridNoteColor(modelRow);
          muteBg = isMuted ? new Color(0x33, 0x11, 0x11) : rowColor;
          muteFg = isMuted ? Color.LIGHT_GRAY : Color.BLACK;
        } else {
          muteBg = isMuted ? new Color(0xff, 0xd7, 0x00) : Color.WHITE;
          muteFg = Color.BLACK;
        }

        clipBtn.setText(isMuted ? "UNMUTE" : "MUTE");
        clipBtn.setFont(new Font("SansSerif", Font.BOLD, padSz > 70 ? 11 : 9));
        clipBtn.setBackground(muteBg);
        clipBtn.setForeground(muteFg);

        if (clipBtn instanceof DelugePadButton pad) {
          pad.setBaseColor(muteBg);
          pad.setTextColorOverride(muteFg);
          pad.setDrawCenterCircle(false);
          pad.setIntensity(isMuted ? 0.4f : 1.0f);
          pad.setActive(true);
          pad.setNoteText(isMuted ? "UNMUTE" : "MUTE");
        }

        JPopupMenu mutePopup = createMutePopupMenu(modelRow);
        clipBtn.setComponentPopupMenu(mutePopup);

        clipBtn.setToolTipText(
            "Clip View: Row "
                + (visibleRow + 1)
                + " Mute / Unmute (Right-Click for Options / Shift-Click to Clear Steps)");
        javax.swing.ToolTipManager.sharedInstance().registerComponent(clipBtn);

        clearActionListeners(clipBtn);
        clipBtn.addActionListener(
            e -> {
              if ((e.getModifiers() & java.awt.event.ActionEvent.SHIFT_MASK) != 0) {
                for (int s = 0; s < stepCount; s++) {
                  if (bridge != null) bridge.setStep(engineRow, s, false);
                }
                refresh();
                return;
              }
              boolean nextMute;
              if (isEditedTrackKit()) {
                org.deluge.model.ClipModel clip = activeEditedClip();
                org.deluge.model.NoteRowModel noteRow =
                    (clip != null) ? clip.getOrCreateRow(modelRow) : null;
                if (noteRow != null) {
                  nextMute = !noteRow.isMuted();
                  noteRow.setMuted(nextMute);
                } else {
                  nextMute = bridge != null && !bridge.getMute(trackToMute);
                }
              } else {
                if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
                  org.deluge.model.TrackModel track =
                      projectModel.getTracks().get(editedModelTrack);
                  nextMute = !track.isMuted();
                  track.setMuted(nextMute);
                } else {
                  nextMute = bridge != null && !bridge.getMute(trackToMute);
                }
              }
              updateEngineMutes();
              if (SwingDelugeApp.mainInstance != null) {
                SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient(
                    "MUT ", (nextMute ? "ON  " : "OFF ") + "T" + (modelRow + 1));
              }
              refresh();
            });
      } else if (isSoloColumn(colId)) {
        String nName;
        if (isSynth) {
          int midiPitch = getRowPitch(modelRow);
          String[] noteNames =
              new String[] {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
          nName = noteNames[Math.max(0, midiPitch) % 12] + ((midiPitch / 12) - 1);
        } else if (projectModel != null
            && editedModelTrack < projectModel.getTracks().size()
            && projectModel.getTracks().get(editedModelTrack)
                instanceof org.deluge.model.KitTrackModel kit) {
          nName =
              (modelRow < kit.getDrums().size())
                  ? kit.getDrums().get(modelRow).getName()
                  : ("PAD " + (modelRow + 1));
          if (nName.toLowerCase().endsWith(".wav") || nName.toLowerCase().endsWith(".aif")) {
            nName = nName.substring(0, nName.lastIndexOf('.'));
          }
        } else {
          nName = "ROW " + (modelRow + 1);
        }

        clipBtn.setText(nName);
        clipBtn.setFont(new Font("SansSerif", Font.BOLD, padSz > 70 ? 11 : 9));

        Color cellBg = getAuditionPadBgColor(modelRow, isSynth, visibleRow);
        Color cellFg = getAuditionPadFgColor(modelRow, isSynth, visibleRow);
        clipBtn.setBackground(cellBg);
        clipBtn.setForeground(cellFg);

        if (clipBtn instanceof DelugePadButton pad) {
          pad.setBaseColor(cellBg);
          pad.setTextColorOverride(cellFg);
          pad.setDrawCenterCircle(false);
          pad.setIntensity(1.0f);
          pad.setActive(true);
          pad.setNoteText(nName);
        }

        clipBtn.setToolTipText(
            "Clip View: Audition / Preview Row " + (visibleRow + 1) + " Note (" + nName + ")");
        javax.swing.ToolTipManager.sharedInstance().registerComponent(clipBtn);

        final String finalNoteName = nName;
        clearKeyboardMouseListeners(clipBtn);
        clipBtn.addMouseListener(
            new java.awt.event.MouseAdapter() {
              private boolean isPressed = false;
              private boolean auditionSounded = false;

              private void startAudition(java.awt.event.MouseEvent e) {
                if (isPressed) return;
                isPressed = true;

                if (e != null && e.isShiftDown()) {
                  if (SwingDelugeApp.mainInstance != null) {
                    SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient(
                        "SEL ", finalNoteName);
                  }
                  return;
                }

                boolean isSynthMode = bridge != null && bridge.getTrackType(baseTrackId) == 1;
                int pitchMidi = isSynthMode ? getRowPitch(modelRow) : 60;

                if (SwingDelugeApp.mainInstance != null) {
                  SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient(
                      "AUD ", finalNoteName);
                }

                Object fwEngineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
                if (fwEngineObj instanceof org.deluge.engine.FirmwareAudioEngine fwEngine) {
                  if (editedModelTrack < fwEngine.sounds.size()) {
                    org.deluge.firmware2.GlobalEffectable sound =
                        fwEngine.sounds.get(editedModelTrack);
                    if (sound instanceof org.deluge.engine.FirmwareKit kit) {
                      if (modelRow < kit.drumSounds.size()) {
                        kit.triggerDrum(modelRow, 127);
                        auditionSounded = true;
                      }
                    } else if (sound instanceof org.deluge.engine.FirmwareSound synth) {
                      stopAuditionIfNeeded();
                      auditionMidiNote = pitchMidi;
                      auditionSynth = synth;
                      synth.triggerNote(pitchMidi, 127);
                      auditionSounded = true;
                    }
                  }
                }
              }

              private void stopAudition() {
                if (!isPressed) return;
                isPressed = false;

                if (!auditionSounded) return;
                auditionSounded = false;

                boolean isSynthMode = bridge != null && bridge.getTrackType(baseTrackId) == 1;
                int pitchMidi = isSynthMode ? getRowPitch(modelRow) : 60;

                Object fwEngineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
                if (fwEngineObj instanceof org.deluge.engine.FirmwareAudioEngine fwEngine) {
                  if (editedModelTrack < fwEngine.sounds.size()) {
                    org.deluge.firmware2.GlobalEffectable sound =
                        fwEngine.sounds.get(editedModelTrack);
                    if (sound instanceof org.deluge.engine.FirmwareKit kit) {
                      if (modelRow < kit.drumSounds.size()) {
                        kit.drumSounds.get(modelRow).releaseNote(60);
                      }
                    } else if (sound instanceof org.deluge.engine.FirmwareSound synth) {
                      synth.releaseNote(pitchMidi);
                    }
                  }
                }
              }

              @Override
              public void mousePressed(java.awt.event.MouseEvent e) {
                if (javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                  startAudition(e);
                }
              }

              @Override
              public void mouseReleased(java.awt.event.MouseEvent e) {
                stopAudition();
              }

              @Override
              public void mouseExited(java.awt.event.MouseEvent e) {
                stopAudition();
              }
            });

        clipBtn.addMouseWheelListener(
            e -> {
              int rotation = e.getWheelRotation();
              boolean isShift = e.isShiftDown();
              if (isShift) {
                rotateRow(modelRow, rotation > 0);
              } else {
                transposeRow(modelRow, rotation < 0);
              }
            });
      } else {
        int engineRow = baseTrackId + modelRow;
        boolean isMuted = bridge != null && bridge.getMute(engineRow);

        int activeCol;
        int curTrackLen = bridge != null ? bridge.getTrackLength(baseTrackId) : stepCount;
        if (curTrackLen > 0 && curTrackLen < stepCount) {
          activeCol = colId % curTrackLen;
        } else if (curTrackLen > stepCount) {
          activeCol = Math.min(colId + scrollOffsetX, curTrackLen - 1);
        } else {
          activeCol = colId;
        }

        double[] outVelProb = {0.8, 1.0};
        boolean stepState = isStepActiveOrSpanned(modelRow, activeCol, outVelProb);
        double vel = outVelProb[0];
        double prob = outVelProb[1];
        boolean inLoop = activeCol < curTrackLen;

        clipBtn.setBackground(
            stepState
                ? getGridNoteColor(modelRow, (float) vel)
                : getStepPadDefaultBg(modelRow, colId));

        if (clipBtn instanceof DelugePadButton pad) {
          org.deluge.project.PreferencesManager.GridColorTheme theme =
              org.deluge.project.PreferencesManager.getGridColorTheme();
          Color trackColor = getGridNoteColor(modelRow);
          boolean inScale = true;
          boolean isRoot = false;

          boolean isSynthMode = bridge != null && bridge.getTrackType(baseTrackId) == 1;
          if (isSynthMode) {
            int pitchMidi = getRowPitch(modelRow);
            isRoot = ScaleMapper.isRootNote(pitchMidi, projectModel.getKey());
            inScale =
                ScaleMapper.isNoteInScale(
                    pitchMidi, projectModel.getKey(), projectModel.getScale());
          }

          Color cellBaseColor =
              getThemeColor(theme, trackColor, stepState, inScale, isRoot, modelRow);
          boolean isNudged = false;
          if (stepState) {
            org.deluge.model.ClipModel cModel = null;
            if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
              org.deluge.model.TrackModel tModel = projectModel.getTracks().get(editedModelTrack);
              if (activeClipId < tModel.getClips().size()) {
                cModel = tModel.getClips().get(activeClipId);
              }
            }
            if (cModel != null) {
              org.deluge.model.StepData sd = getClipStep(cModel, modelRow, activeCol);
              if (sd != null && sd.active()) {
                if (sd.fill() > 0.0f) {
                  cellBaseColor =
                      new Color(0, 0, 255); // FILL note = colours::blue (note_row.cpp:1993)
                } else if (sd.nudge() > 0.0f) {
                  isNudged = true;
                }
              }
            } else if (bridge != null) {
              if (bridge.getStepFill(engineRow, activeCol) > 0.0) {
                isNudged = true;
              }
            }
          }
          pad.setBaseColor(cellBaseColor);
          pad.setBlur(isNudged);
          pad.setApplicable(inScale || !isSynthMode);
          pad.setTheme(theme);
          org.deluge.model.ClipModel curClip2 = activeEditedClip();
          boolean isTrip2 = curClip2 != null && curClip2.isTripletMode();
          pad.setBeatMarker(
              ((colId + scrollOffsetX) % (isTrip2 ? 3 : 4) == 0)
                  || (isTrip2 && (colId + scrollOffsetX) % 4 == 0));
          pad.setScaleRoot(isRoot);
          pad.setScaleNote(inScale);

          pad.setMuted(isMuted);
          pad.setInLoop(inLoop);
          pad.setActive(stepState);
          pad.setIntensity(
              stepState
                  ? (float) vel
                  : 1.0f); // C: velocity-only head brightness (pad applies (65+1.5v)/255); tails
          // full
          pad.setTail(isStepTied(modelRow, activeCol) && !stepState);
          if (stepState) {
            if (isSynthMode) {
              int pitchMidi = getRowPitch(modelRow);
              pad.setNoteText(getNoteName(pitchMidi));
            } else {
              pad.setNoteText(String.format("v%d", (int) (vel * 100)));
            }
          } else {
            pad.setNoteText("");
          }
        }

        if (isStepColumn(colId)) {
          final int vr = visibleRow;
          final int vc = colId;
          clipBtn.addMouseWheelListener(e -> handlePadMouseWheel(vr, vc, e));
        }
        clipController.attachListeners(clipBtn, modelRow, visibleRow, colId);
      }

      if (isFaceplateRow) {
        if (c == stepCount) {
          rowPanel.add(createFaceplateSeparator(faceScaleRow, padSz));
        } else if (c > 0) {
          rowPanel.add(Box.createRigidArea(new Dimension((int) Math.round(41 * faceScaleRow), 1)));
        }
      } else {
        if (isMuteColumn(c)) {
          rowPanel.add(Box.createHorizontalStrut(20));
        }
      }
      rowPanel.add(clipBtn);
    }
    return rowPanel;
  }

  static JPanel createFaceplateSeparator(double scale, int height) {
    int w = (int) Math.round(112 * scale);
    int line1 = (int) Math.round(17 * scale);
    int line2 = (int) Math.round(98 * scale);
    int lineW = Math.max(1, (int) Math.round(2 * scale));
    JPanel sep =
        new JPanel() {
          @Override
          protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            g.setColor(new Color(0xd8, 0xd8, 0xd8, 220));
            g.fillRect(line1, 0, lineW, getHeight());
            g.fillRect(line2, 0, lineW, getHeight());
          }
        };
    sep.setOpaque(false);
    sep.setPreferredSize(new Dimension(w, height));
    sep.setMinimumSize(new Dimension(w, height));
    sep.setMaximumSize(new Dimension(w, height));
    return sep;
  }

  private void rebuildKeyplayComponents() {
    stopAuditionIfNeeded();
    refreshInProgress = true;
    removeAll();

    voiceRowCount = gridMode.rows;
    int songVoiceRows = gridMode.rows;
    this.stepCount = gridMode.columns;
    int savedColCount = this.stepCount + 2;
    this.columnCount = savedColCount;

    vuManager.clear();
    vuManager.startTimer();

    boolean isFaceplate =
        PreferencesManager.getTopPanelStyle()
            == PreferencesManager.TopPanelStyle.HARDWARE_FACEPLATE;
    double faceScale = Math.max(800, getWidth()) / 2256.0;
    int padSz = cachedPadSz;
    int lw = currentLabelWidth();
    int rowW;
    if (isFaceplate) {
      // Match the CLIP faceplate grid sizing so the keyboard grid is the same pad size.
      padSz = Math.max(16, (int) Math.round(78 * faceScale));
      rowW = (int) Math.round(2270 * faceScale);
    } else {
      rowW = getGridWidth(padSz, lw);
    }

    voicePanel = new JPanel();
    voicePanel.setBackground(new Color(0x15, 0x15, 0x15));
    voicePanel.setOpaque(true);
    voicePanel.setLayout(new BoxLayout(voicePanel, BoxLayout.Y_AXIS));

    boolean kitTrack = isEditedTrackKit();
    Color trackColor = getTrackBaseColor();
    PreferencesManager.GridColorTheme theme = PreferencesManager.getGridColorTheme();

    for (int t = 0; t < songVoiceRows; t++) {
      final int rowIdx = t;
      JPanel rowPanel = new JPanel();
      rowPanel.setLayout(new BoxLayout(rowPanel, BoxLayout.X_AXIS));
      rowPanel.setBackground(new Color(0x22, 0x22, 0x22));
      rowPanel.setPreferredSize(new Dimension(rowW, padSz));
      rowPanel.setMinimumSize(new Dimension(rowW, padSz));
      rowPanel.setMaximumSize(new Dimension(rowW, padSz));
      rowPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

      String trackName = "";
      if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
        TrackModel rowTrack = projectModel.getTracks().get(editedModelTrack);
        if (rowTrack instanceof org.deluge.model.KitTrackModel kit) {
          java.util.List<org.deluge.model.Drum> sounds = kit.getDrums();
          trackName =
              (t < sounds.size())
                  ? sounds.get(sounds.size() - 1 - t).getName()
                  : rowTrack.getName();
        } else {
          trackName = (t == 0) ? rowTrack.getName() : "-" + t + "st";
        }
      }

      JLabel label = new JLabel(trackName);
      lw = currentLabelWidth();
      label.setPreferredSize(new Dimension(lw, 30));
      label.setMinimumSize(new Dimension(lw, 30));
      label.setMaximumSize(new Dimension(lw, 30));
      label.setForeground(Color.LIGHT_GRAY);
      label.setFont(new Font("SansSerif", Font.BOLD, 10));

      // In faceplate mode the CLIP grid omits the leading label + VU meter so pads start at the
      // left edge; mirror that here so the keyboard grid lines up 1:1 with the clip grid.
      if (!isFaceplate) {
        rowPanel.add(label);
        rowPanel.add(Box.createRigidArea(new Dimension(69, 1)));
        rowPanel.add(Box.createHorizontalStrut(5));

        VUMeterPanel vu = new VUMeterPanel();
        vu.setPreferredSize(new Dimension(12, padSz));
        vu.setMaximumSize(new Dimension(12, padSz));
        rowPanel.add(vu);
        rowPanel.add(Box.createHorizontalStrut(5));

        vuManager.registerTrackVu(t, vu);
      }

      for (int c = 0; c < columnCount; c++) {
        final int colId = c;
        boolean isAdvanced =
            PreferencesManager.getGridPanelType() == PreferencesManager.GridPanelType.ADVANCED;
        JButton clipBtn;

        if (isAdvanced) {
          DelugePadButton pad = new DelugePadButton();
          pad.putClientProperty("row", t);
          pad.putClientProperty("col", c);
          clipBtn = pad;
        } else {
          clipBtn = new CleanJButton();
        }

        clipBtn.setPreferredSize(new Dimension(padSz, padSz));
        clipBtn.setMinimumSize(new Dimension(padSz, padSz));
        clipBtn.setMaximumSize(new Dimension(padSz, padSz));
        clipBtn.setMargin(new Insets(0, 0, 0, 0));

        pads[t][c] = clipBtn;
        clipBtn.setBorder(UIManager.getBorder("Button.border"));

        if (isMuteColumn(colId) || isSoloColumn(colId)) {
          clipBtn.setVisible(false);
          clipBtn.setEnabled(false);
        } else {
          if (colId < 16) {
            if (kitTrack) {
              int drumIdx = org.deluge.model.KeyplayKeyboard.getDrumIndex(t, colId, gridMode.rows);
              clipBtn.setText(drumIdx < editedKitDrumCount() ? ("D" + (drumIdx + 1)) : "");
              clearActionListeners(clipBtn);
              clearKeyboardMouseListeners(clipBtn);
              boolean drumPlayable = drumIdx < editedKitDrumCount();
              if (drumPlayable) {
                clipBtn.addMouseListener(
                    new java.awt.event.MouseAdapter() {
                      @Override
                      public void mousePressed(java.awt.event.MouseEvent e) {
                        double pct = (double) e.getY() / e.getComponent().getHeight();
                        int velocity = 127 - (int) (pct * 97); // 30 to 127
                        velocity = Math.max(1, Math.min(velocity, 127));
                        triggerKeyboardDrum(drumIdx, velocity);
                        refresh();
                      }

                      @Override
                      public void mouseReleased(java.awt.event.MouseEvent e) {
                        releaseKeyboardDrum(drumIdx);
                        refresh();
                      }
                    });
              }
            } else {
              if (colId == 15) {
                // CC74 Modulation Column touch strip
                clearActionListeners(clipBtn);
                clearKeyboardMouseListeners(clipBtn);
                clipBtn.setText(
                    rowIdx == 0
                        ? "CC74"
                        : (rowIdx == songVoiceRows - 1 ? String.valueOf(currentCc74Value) : ""));
                java.awt.event.MouseAdapter ma =
                    new java.awt.event.MouseAdapter() {
                      @Override
                      public void mousePressed(java.awt.event.MouseEvent e) {
                        updateCc74FromRow(rowIdx);
                      }

                      @Override
                      public void mouseDragged(java.awt.event.MouseEvent e) {
                        Component comp = e.getComponent();
                        if (comp instanceof JButton btn) {
                          Point p = SwingUtilities.convertPoint(btn, e.getPoint(), voicePanel);
                          int rowHeight = btn.getHeight() + 5;
                          int row = p.y / rowHeight;
                          row = Math.max(0, Math.min(row, songVoiceRows - 1));
                          updateCc74FromRow(row);
                        }
                      }
                    };
                clipBtn.addMouseListener(ma);
                clipBtn.addMouseMotionListener(ma);
              } else if (colId == 14) {
                // Velocity Column touch strip
                clearActionListeners(clipBtn);
                clearKeyboardMouseListeners(clipBtn);
                clipBtn.setText(
                    rowIdx == 0
                        ? "VEL"
                        : (rowIdx == songVoiceRows - 1
                            ? String.valueOf(defaultKeyboardVelocity)
                            : ""));
                java.awt.event.MouseAdapter ma =
                    new java.awt.event.MouseAdapter() {
                      @Override
                      public void mousePressed(java.awt.event.MouseEvent e) {
                        updateVelocityFromRow(rowIdx);
                      }

                      @Override
                      public void mouseDragged(java.awt.event.MouseEvent e) {
                        Component comp = e.getComponent();
                        if (comp instanceof JButton btn) {
                          Point p = SwingUtilities.convertPoint(btn, e.getPoint(), voicePanel);
                          int rowHeight = btn.getHeight() + 5;
                          int row = p.y / rowHeight;
                          row = Math.max(0, Math.min(row, songVoiceRows - 1));
                          updateVelocityFromRow(row);
                        }
                      }
                    };
                clipBtn.addMouseListener(ma);
                clipBtn.addMouseMotionListener(ma);
              } else {
                int note =
                    org.deluge.model.KeyplayKeyboard.getNote(
                        rowIdx,
                        colId,
                        scaleModeEnabled,
                        projectModel.getKey(),
                        projectModel.getScale(),
                        gridMode.rows);
                clipBtn.setText(getNoteName(note));
                clearActionListeners(clipBtn);
                clearKeyboardMouseListeners(clipBtn);
                clipBtn.addMouseListener(new KeyboardMouseAdapter(this, note));
              }
            }
          } else {
            clipBtn.setText("");
          }
        }

        if (isFaceplate) {
          // Mirror the CLIP faceplate row: inter-pad gaps + a separator before the utility
          // columns so the keyboard grid spans the same width as the clip grid.
          if (c == stepCount) {
            rowPanel.add(createFaceplateSeparator(faceScale, padSz));
          } else if (c > 0) {
            rowPanel.add(Box.createRigidArea(new Dimension((int) Math.round(41 * faceScale), 1)));
          }
          rowPanel.add(clipBtn);
        } else {
          rowPanel.add(clipBtn);
          rowPanel.add(Box.createHorizontalStrut(5));
        }
      }
      voicePanel.add(rowPanel);
    }

    JPanel voiceWrapper = new JPanel(new BorderLayout());
    voiceWrapper.setBackground(new Color(0x15, 0x15, 0x15));
    voiceWrapper.setPreferredSize(new Dimension(rowW, gridMode.rows * (padSz + 5) - 5));
    voiceWrapper.setMaximumSize(new Dimension(rowW, gridMode.rows * (padSz + 5) - 5));
    voiceWrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
    voiceWrapper.add(voicePanel, BorderLayout.CENTER);

    add(voiceWrapper);
    revalidate();
    repaint();
    refreshInProgress = false;
  }

  private int currentCc74Value = 64; // Default to neutral

  private void updateCc74FromRow(int v) {
    int ccVal = (gridMode.rows - 1 - v) * 127 / (gridMode.rows - 1);
    currentCc74Value = ccVal;

    if (bridge != null) {
      Object fwEngineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
      if (fwEngineObj instanceof org.deluge.engine.FirmwareAudioEngine fwEngine) {
        if (editedModelTrack >= 0 && editedModelTrack < fwEngine.sounds.size()) {
          org.deluge.firmware2.GlobalEffectable sound = fwEngine.sounds.get(editedModelTrack);
          if (sound instanceof org.deluge.firmware2.Sound s) {
            int newValue = (ccVal - 64) << 25;
            s.polyphonicExpressionEventOnChannelOrNote(newValue, 1, 1, 1);
          }
        }
      }
    }

    refreshKeyplayInPlace();
  }

  private void updateVelocityFromRow(int v) {
    int velVal = (gridMode.rows - 1 - v) * 127 / (gridMode.rows - 1);
    defaultKeyboardVelocity = velVal;
    refreshKeyplayInPlace();
  }

  private void refreshKeyplayInPlace() {
    boolean kitTrack = isEditedTrackKit();
    Color trackColor = getTrackBaseColor();
    PreferencesManager.GridColorTheme theme = PreferencesManager.getGridColorTheme();

    for (int v = 0; v < gridMode.rows; v++) {
      for (int c = 0; c < columnCount; c++) {
        JButton clipBtn = pads[v][c];
        if (clipBtn == null) continue;

        if (isMuteColumn(c) || isSoloColumn(c)) {
          clipBtn.setVisible(false);
          clipBtn.setEnabled(false);
        } else if (c < 16) {
          if (kitTrack) {
            int drumIdx = org.deluge.model.KeyplayKeyboard.getDrumIndex(v, c, gridMode.rows);
            boolean drumPlayable = drumIdx < editedKitDrumCount();
            boolean isPlaying =
                bridge != null
                    && bridge.getGlobalInt(BridgeContract.G_PREVIEW_TRACK)
                        == (long) (baseTrackId + (v % 8));

            if (clipBtn instanceof DelugePadButton pad) {
              pad.setActive(drumPlayable);
              pad.setBaseColor(drumPlayable ? trackColor : new Color(0x1a, 0x1a, 0x1e));
              pad.setApplicable(drumPlayable);
              pad.setIntensity(isPlaying ? 1.0f : 0.4f);
            } else {
              clipBtn.setBackground(drumPlayable ? trackColor : new Color(0x22, 0x22, 0x24));
            }
          } else {
            if (c == 15) {
              // CC74 Modulation Column touch strip
              int padCcValue = (gridMode.rows - 1 - v) * 127 / (gridMode.rows - 1);
              boolean active = padCcValue <= currentCc74Value;
              Color ccColor = active ? new Color(0xd0, 0x00, 0xff) : new Color(0x3a, 0x1c, 0x4a);
              clipBtn.setText(
                  v == 0
                      ? "CC74"
                      : (v == gridMode.rows - 1 ? String.valueOf(currentCc74Value) : ""));
              clipBtn.setForeground(Color.WHITE);
              if (clipBtn instanceof DelugePadButton pad) {
                pad.setBaseColor(ccColor);
                pad.setActive(active);
                pad.setApplicable(true);
                pad.setIntensity(active ? 1.0f : 0.3f);
                pad.setDrawCenterCircle(false);
              } else {
                clipBtn.setBackground(ccColor);
              }
            } else if (c == 14) {
              // Velocity Column touch strip
              int padVelValue = (gridMode.rows - 1 - v) * 127 / (gridMode.rows - 1);
              boolean active = padVelValue <= defaultKeyboardVelocity;
              Color velColor = active ? new Color(0xff, 0x6f, 0x00) : new Color(0x4a, 0x24, 0x00);
              clipBtn.setText(
                  v == 0
                      ? "VEL"
                      : (v == gridMode.rows - 1 ? String.valueOf(defaultKeyboardVelocity) : ""));
              clipBtn.setForeground(Color.WHITE);
              if (clipBtn instanceof DelugePadButton pad) {
                pad.setBaseColor(velColor);
                pad.setActive(active);
                pad.setApplicable(true);
                pad.setIntensity(active ? 1.0f : 0.3f);
                pad.setDrawCenterCircle(false);
              } else {
                clipBtn.setBackground(velColor);
              }
            } else {
              int note =
                  org.deluge.model.KeyplayKeyboard.getNote(
                      v,
                      c,
                      scaleModeEnabled,
                      projectModel.getKey(),
                      projectModel.getScale(),
                      gridMode.rows);
              boolean isRoot = ScaleMapper.isRootNote(note, projectModel.getKey());
              boolean inScale =
                  ScaleMapper.isNoteInScale(note, projectModel.getKey(), projectModel.getScale());

              boolean isPlaying = false;
              Color cellBaseColor = getThemeColor(theme, trackColor, isPlaying, inScale, isRoot, v);

              if (clipBtn instanceof DelugePadButton pad) {
                pad.setBaseColor(cellBaseColor);
                pad.setApplicable(inScale);
                pad.setTheme(theme);
                pad.setBeatMarker(false);
                pad.setScaleRoot(isRoot);
                pad.setScaleNote(inScale);
                pad.setActive(isPlaying || isRoot || inScale);
                pad.setIntensity(isPlaying ? 1.0f : (isRoot ? 0.6f : 0.3f));
                pad.setDrawCenterCircle(true);
              } else {
                clipBtn.setBackground(cellBaseColor);
              }
            }
          }
        }
      }
    }
    repaint();
  }

  /** The clip currently being edited on this grid, or {@code null} if none is resolvable. */
  private org.deluge.model.ClipModel activeEditedClip() {
    if (projectModel == null || editedModelTrack >= projectModel.getTracks().size()) {
      return null;
    }
    org.deluge.model.TrackModel track = projectModel.getTracks().get(editedModelTrack);
    int idx = track.getActiveClipIndex();
    if (idx < 0 || idx >= track.getClips().size()) {
      return null;
    }
    return track.getClips().get(idx);
  }

  private void rotateRow(int modelRow, boolean right) {
    if (projectModel == null) return;
    org.deluge.model.TrackModel track = projectModel.getTracks().get(editedModelTrack);
    if (activeClipId >= track.getClips().size()) return;
    org.deluge.model.ClipModel clip = track.getClips().get(activeClipId);

    // Get the NoteRowModel
    org.deluge.model.NoteRowModel rowModel = clip.noteRows.get(modelRow);
    if (rowModel == null || rowModel.notes.isEmpty()) return;

    int stepTicks = 24; // Sixteenth note step
    int totalTicks = clip.getStepCount() * stepTicks;
    int shift = right ? stepTicks : -stepTicks;

    for (org.deluge.model.NoteModel note : rowModel.notes) {
      int newPos = (note.getPos() + shift + totalTicks) % totalTicks;
      note.setPos(newPos);
    }

    // Update the visual step data grid as well
    org.deluge.model.StepData[] shiftedSteps = new org.deluge.model.StepData[clip.getStepCount()];
    for (int s = 0; s < clip.getStepCount(); s++) {
      int srcIdx = (s - (shift / 24) + clip.getStepCount()) % clip.getStepCount();
      shiftedSteps[s] = clip.getStep(modelRow, srcIdx);
    }
    for (int s = 0; s < clip.getStepCount(); s++) {
      clip.setStep(modelRow, s, shiftedSteps[s]);
    }

    if (SwingDelugeApp.mainInstance != null) {
      SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient(
          "ROTA", right ? "RGHT" : "LEFT");
      SwingDelugeApp.mainInstance.getSyncCoordinator().pushModelToBridge();
    }

    refreshKeyplayInPlace();
    repaint();
  }

  private void transposeRow(int modelRow, boolean up) {
    if (projectModel == null) return;
    org.deluge.model.TrackModel track = projectModel.getTracks().get(editedModelTrack);
    if (activeClipId >= track.getClips().size()) return;
    org.deluge.model.ClipModel clip = track.getClips().get(activeClipId);

    if (track instanceof org.deluge.model.KitTrackModel) return;

    int currentPitch = clip.getRowYNote(modelRow);
    if (currentPitch == -1) {
      currentPitch = getRowPitch(modelRow);
    }

    int newPitch = currentPitch + (up ? 1 : -1);
    newPitch = Math.max(0, Math.min(newPitch, 127));

    clip.setRowYNote(modelRow, newPitch);

    for (int s = 0; s < clip.getStepCount(); s++) {
      org.deluge.model.StepData step = clip.getStep(modelRow, s);
      if (step != null && step.active()) {
        // Only the pitch changes — preserve every other per-step property (iterance, fill,
        // nudge as well as velocity/gate/probability). The 5-arg StepData.of() resets
        // iterance/fill/nudge to 0, silently discarding them on transpose.
        clip.setStep(
            modelRow,
            s,
            new org.deluge.model.StepData(
                true,
                step.velocity(),
                step.gate(),
                step.probability(),
                newPitch,
                step.iterance(),
                step.fill(),
                step.nudge()));
      }
    }

    String[] keyNames = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    int oct = (newPitch / 12) - 1;
    String noteName = keyNames[newPitch % 12] + oct;
    if (SwingDelugeApp.mainInstance != null) {
      SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient("TRSP", noteName);
      SwingDelugeApp.mainInstance.getSyncCoordinator().pushModelToBridge();
    }

    refreshKeyplayInPlace();
    repaint();
  }
}
