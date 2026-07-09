package org.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.deluge.model.*;

/**
 * Factory class extracting context/popup menus from SwingGridPanel to reduce its size. Uses
 * package-private companion access to communicate with the panel.
 */
public class GridContextMenuFactory {

  public static void stylePopupMenu(JPopupMenu menu) {
    menu.setBackground(new Color(0x1e, 0x1e, 0x22));
    menu.setBorder(BorderFactory.createLineBorder(new Color(0x3e, 0x3e, 0x42), 1));
    for (java.awt.Component comp : menu.getComponents()) {
      styleMenuComponent(comp);
    }
  }

  private static void styleMenuComponent(java.awt.Component comp) {
    if (comp instanceof javax.swing.JMenuItem item) {
      item.setForeground(Color.WHITE);
      item.setBackground(new Color(0x1e, 0x1e, 0x22));
      if (item instanceof javax.swing.JMenu subMenu) {
        for (java.awt.Component subComp : subMenu.getMenuComponents()) {
          styleMenuComponent(subComp);
        }
      }
    }
  }

  public static void showSoloButtonContextMenu(
      SwingGridPanel panel, Component src, int x, int y, int trackIdx) {
    java.util.List<TrackModel> tracks = panel.projectModel.getTracks();
    if (trackIdx >= tracks.size()) return;
    TrackModel track = tracks.get(trackIdx);

    JPopupMenu menu = new JPopupMenu();
    menu.setBackground(new Color(0x1e, 0x1e, 0x22));
    menu.setBorder(BorderFactory.createLineBorder(new Color(0x3e, 0x3e, 0x42), 1));

    // 1. Exclusive Solo
    JMenuItem exclusiveSolo = new JMenuItem("Solo Exclusive (Unsolo Others)");
    exclusiveSolo.setForeground(Color.WHITE);
    exclusiveSolo.setBackground(new Color(0x1e, 0x1e, 0x22));
    exclusiveSolo.addActionListener(
        e -> {
          panel.setSoloRow(trackIdx);
          panel.updateEngineMutes();
          if (SwingDelugeApp.mainInstance != null) {
            SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient(
                "SOLO", "T" + (trackIdx + 1));
          }
          panel.refresh();
        });
    menu.add(exclusiveSolo);

    // 2. Unsolo All
    JMenuItem unsoloAll = new JMenuItem("Unsolo All Tracks");
    unsoloAll.setForeground(Color.WHITE);
    unsoloAll.setBackground(new Color(0x1e, 0x1e, 0x22));
    unsoloAll.addActionListener(
        e -> {
          panel.setSoloRow(-1);
          panel.updateEngineMutes();
          if (SwingDelugeApp.mainInstance != null) {
            SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient("SOLO", "OFF");
          }
          panel.refresh();
        });
    menu.add(unsoloAll);

    menu.addSeparator();

    // 3. Mute/Unmute Track
    boolean isMuted = track.isMuted();
    JMenuItem muteItem = new JMenuItem(isMuted ? "Unmute Track" : "Mute Track");
    muteItem.setForeground(Color.WHITE);
    muteItem.setBackground(new Color(0x1e, 0x1e, 0x22));
    muteItem.addActionListener(
        e -> {
          track.setMuted(!track.isMuted());
          panel.updateEngineMutes();
          panel.refresh();
        });
    menu.add(muteItem);

    menu.addSeparator();

    // 4. Rename Track...
    JMenuItem renameItem = new JMenuItem("Rename Track...");
    renameItem.setForeground(Color.WHITE);
    renameItem.setBackground(new Color(0x1e, 0x1e, 0x22));
    renameItem.addActionListener(
        e -> {
          String newName = JOptionPane.showInputDialog(panel, "Track name:", track.getName());
          if (newName != null && !newName.isBlank()) {
            track.setName(newName);
            panel.fireProjectChanged();
          }
        });
    menu.add(renameItem);

    // 5. Change Color...
    JMenuItem colorItem = new JMenuItem("Change Track Color...");
    colorItem.setForeground(Color.WHITE);
    colorItem.setBackground(new Color(0x1e, 0x1e, 0x22));
    colorItem.addActionListener(
        e -> {
          Color chosen =
              javax.swing.JColorChooser.showDialog(
                  panel, "Track Color", panel.trackColors[trackIdx]);
          if (chosen != null) {
            panel.trackColors[trackIdx] = chosen;
            track.setColourHex(
                "0x" + Integer.toHexString(chosen.getRGB() & 0xFFFFFF).toUpperCase());
            panel.fireProjectChanged();
          }
        });
    menu.add(colorItem);

    if (track instanceof SynthTrackModel synthTrack) {
      menu.addSeparator();
      // 6. Synthesizer Parameters Dashboard
      JMenuItem synthDashboard = new JMenuItem("Synth Dashboard...");
      synthDashboard.setForeground(new Color(0x00, 0xff, 0xcc));
      synthDashboard.setBackground(new Color(0x1e, 0x1e, 0x22));
      synthDashboard.addActionListener(
          e -> {
            new SwingSynthConfigDialog(
                    (Frame) SwingUtilities.getWindowAncestor(src),
                    synthTrack,
                    panel.bridge,
                    trackIdx,
                    panel.projectModel)
                .setVisible(true);
          });
      menu.add(synthDashboard);
    }

    menu.show(src, x, y);
  }

  public static void showTrackContextMenu(
      SwingGridPanel panel, Component src, int x, int y, int trackIdx) {
    java.util.List<TrackModel> tracks = panel.projectModel.getTracks();
    if (trackIdx < 0 || trackIdx >= tracks.size()) return;
    TrackModel track = tracks.get(trackIdx);

    JPopupMenu menu = new JPopupMenu();

    JMenuItem renameItem = new JMenuItem("Rename...");
    renameItem.addActionListener(
        e -> {
          String newName = JOptionPane.showInputDialog(panel, "Track name:", track.getName());
          if (newName != null && !newName.isBlank()) {
            track.setName(newName);
            panel.fireProjectChanged();
          }
        });
    menu.add(renameItem);

    JMenuItem colorItem = new JMenuItem("Set Color...");
    colorItem.addActionListener(
        e -> {
          Color chosen =
              JColorChooser.showDialog(panel, "Track Color", panel.trackColors[trackIdx]);
          if (chosen != null) {
            panel.trackColors[trackIdx] = chosen;
            track.setColourHex(
                "0x" + Integer.toHexString(chosen.getRGB() & 0xFFFFFF).toUpperCase());
            panel.fireProjectChanged();
          }
        });
    menu.add(colorItem);

    JMenuItem inspectItem = new JMenuItem("Track Inspector...");
    inspectItem.setToolTipText(
        "Open advanced preset switching, mixer channel controls, and FM operator mappings");
    inspectItem.addActionListener(
        e -> {
          new TrackInspectorDialog(
                  (Frame) javax.swing.SwingUtilities.getWindowAncestor(panel),
                  trackIdx,
                  tracks,
                  () -> {
                    panel.fireProjectChanged();
                    panel.refresh();
                  })
              .setVisible(true);
        });
    menu.add(inspectItem);

    menu.addSeparator();

    JMenuItem pianoRollItem = new JMenuItem("Open Piano Roll Editor...");
    pianoRollItem.addActionListener(
        e -> {
          if (SwingDelugeApp.mainInstance != null) SwingDelugeApp.mainInstance.launchPianoRoll();
        });
    menu.add(pianoRollItem);

    JMenuItem droneItem = new JMenuItem("Open Drone Lab on Track...");
    droneItem.addActionListener(
        e -> {
          if (SwingDelugeApp.mainInstance != null) SwingDelugeApp.mainInstance.launchDroneLab();
        });
    menu.add(droneItem);

    JMenuItem randItem = new JMenuItem("Randomize Track Steps...");
    randItem.addActionListener(
        e -> {
          if (SwingDelugeApp.mainInstance != null) SwingDelugeApp.mainInstance.launchRandomizer();
        });
    menu.add(randItem);

    menu.addSeparator();

    JMenuItem upItem = new JMenuItem("Move Up");
    upItem.setEnabled(trackIdx > 0);
    upItem.addActionListener(
        e -> {
          panel.projectModel.moveTrackUp(trackIdx);
          // swap colors so they follow the track
          Color tmp = panel.trackColors[trackIdx];
          panel.trackColors[trackIdx] = panel.trackColors[trackIdx - 1];
          panel.trackColors[trackIdx - 1] = tmp;
          javax.swing.SwingUtilities.invokeLater(panel::fireProjectChanged);
        });
    menu.add(upItem);

    JMenuItem downItem = new JMenuItem("Move Down");
    downItem.setEnabled(trackIdx < tracks.size() - 1);
    downItem.addActionListener(
        e -> {
          panel.projectModel.moveTrackDown(trackIdx);
          // swap colors so they follow the track
          Color tmp = panel.trackColors[trackIdx];
          panel.trackColors[trackIdx] = panel.trackColors[trackIdx + 1];
          panel.trackColors[trackIdx + 1] = tmp;
          javax.swing.SwingUtilities.invokeLater(panel::fireProjectChanged);
        });
    menu.add(downItem);

    menu.addSeparator();

    // ── Grid Color Theme Sub-menu ──
    javax.swing.JMenu themeMenu = new javax.swing.JMenu("Grid Color Theme");
    for (org.deluge.project.PreferencesManager.GridColorTheme theme :
        org.deluge.project.PreferencesManager.GridColorTheme.values()) {
      javax.swing.JRadioButtonMenuItem item =
          new javax.swing.JRadioButtonMenuItem(
              theme.name(), theme == org.deluge.project.PreferencesManager.getGridColorTheme());
      item.addActionListener(
          evt -> {
            org.deluge.project.PreferencesManager.setGridColorTheme(theme);
            panel.refresh();
          });
      themeMenu.add(item);
    }
    menu.add(themeMenu);

    menu.addSeparator();

    if (track instanceof KitTrackModel kitTrack) {
      JMenuItem saveKitItem = new JMenuItem("Save as Kit preset...");
      saveKitItem.addActionListener(e -> panel.saveTrackPreset(kitTrack, false));
      menu.add(saveKitItem);
    } else if (track instanceof SynthTrackModel synthTrack) {
      JMenuItem saveSynthItem = new JMenuItem("Save as Synth preset...");
      saveSynthItem.addActionListener(e -> panel.saveTrackPreset(synthTrack, true));
      menu.add(saveSynthItem);

      JMenuItem toMidiItem = new JMenuItem("Convert to MIDI Track");
      toMidiItem.addActionListener(e -> panel.convertTrackToMidi(trackIdx));
      menu.add(toMidiItem);
    } else if (track instanceof MidiTrackModel) {
      JMenuItem toSynthItem = new JMenuItem("Convert to Synth Track");
      toSynthItem.addActionListener(e -> panel.convertTrackToSynth(trackIdx));
      menu.add(toSynthItem);
    }

    menu.addSeparator();

    // Per-row probability and velocity — operates on all steps of this row in the active clip
    JMenuItem rowProbItem = new JMenuItem("Set Row Probability...");
    rowProbItem.addActionListener(
        e -> {
          String input = JOptionPane.showInputDialog(panel, "Row probability (0-100%):", 100);
          if (input != null) {
            try {
              double val = Double.parseDouble(input.trim()) / 100.0;
              val = Math.max(0, Math.min(1, val));
              int engineRow = panel.baseTrackId + trackIdx;
              int len =
                  panel.bridge != null ? panel.bridge.getTrackLength(engineRow) : panel.stepCount;
              for (int s = 0; s < len && s < panel.stepCount; s++) {
                panel.bridge.setStepProbability(engineRow, s, val);
              }
              panel.refresh();
            } catch (NumberFormatException ignored) {
            }
          }
        });
    menu.add(rowProbItem);

    JMenuItem rowVelItem = new JMenuItem("Set Row Velocity...");
    rowVelItem.addActionListener(
        e -> {
          String input = JOptionPane.showInputDialog(panel, "Row velocity (0-100%):", 80);
          if (input != null) {
            try {
              double val = Double.parseDouble(input.trim()) / 100.0;
              val = Math.max(0, Math.min(1, val));
              int engineRow = panel.baseTrackId + trackIdx;
              int len =
                  panel.bridge != null ? panel.bridge.getTrackLength(engineRow) : panel.stepCount;
              for (int s = 0; s < len && s < panel.stepCount; s++) {
                panel.bridge.setVelocity(engineRow, s, val);
              }
              panel.refresh();
            } catch (NumberFormatException ignored) {
            }
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
                  panel,
                  "Delete track \"" + track.getName() + "\" and all its clips?",
                  "Delete Track",
                  JOptionPane.YES_NO_OPTION,
                  JOptionPane.WARNING_MESSAGE);
          if (confirm == JOptionPane.YES_OPTION) {
            panel.projectModel.removeTrack(track);
            panel.fireProjectChanged();
          }
        });
    menu.add(deleteItem);

    menu.show(src, x, y);
  }

  public static void showClipContextMenu(
      SwingGridPanel panel,
      Component src,
      int x,
      int y,
      TrackModel track,
      int clipIdx,
      int trackIndex) {
    JPopupMenu menu = new JPopupMenu();
    ClipModel clip = track.getClips().get(clipIdx);

    JMenuItem editItem = new JMenuItem("Edit Clip Pattern");
    editItem.addActionListener(
        e -> {
          if (org.deluge.ui.SwingDelugeApp.mainInstance != null) {
            org.deluge.ui.SwingDelugeApp.mainInstance.switchToTrackEdit(trackIndex, clipIdx);
          }
        });
    menu.add(editItem);

    JMenuItem renameItem = new JMenuItem("Rename Clip...");
    renameItem.addActionListener(
        e -> {
          String newName = JOptionPane.showInputDialog(panel, "Clip name:", clip.getName());
          if (newName != null && !newName.isBlank()) {
            clip.setName(newName);
            panel.fireProjectChanged();
          }
        });
    menu.add(renameItem);

    JMenuItem dupeItem = new JMenuItem("Duplicate Clip");
    dupeItem.addActionListener(
        e -> {
          ClipModel copy = clip.deepCopy(clip.getName() + " copy");
          track.addClip(copy);
          panel.fireProjectChanged();
        });
    menu.add(dupeItem);

    JMenuItem copyItem = new JMenuItem("Copy Clip");
    copyItem.addActionListener(
        e -> {
          panel.setCopiedClip(clip);
        });
    menu.add(copyItem);

    if (panel.getCopiedClip() != null) {
      JMenuItem pasteOverItem = new JMenuItem("Paste Over Clip");
      pasteOverItem.addActionListener(
          e -> {
            ClipModel copied = panel.getCopiedClip();
            ClipModel copy = copied.deepCopy(clip.getName());
            track.getClips().set(clipIdx, copy);
            panel.fireProjectChanged();
            panel.refresh();
          });
      menu.add(pasteOverItem);
    }

    menu.addSeparator();

    JMenuItem deleteItem = new JMenuItem("Delete Clip");
    deleteItem.setForeground(Color.RED);
    deleteItem.addActionListener(
        e -> {
          if (track.getClips().size() <= 1) {
            JOptionPane.showMessageDialog(panel, "A track must have at least one clip.");
            return;
          }
          int confirm =
              JOptionPane.showConfirmDialog(
                  panel,
                  "Delete clip \"" + clip.getName() + "\"?",
                  "Delete Clip",
                  JOptionPane.YES_NO_OPTION);
          if (confirm == JOptionPane.YES_OPTION) {
            track.removeClip(clip);
            panel.fireProjectChanged();
          }
        });
    menu.add(deleteItem);

    menu.addSeparator();

    // ── Assign Section submenu ──
    JMenu assignSectionMenu = new JMenu("Assign Section");
    char currentSec = (char) clip.getSection();
    ButtonGroup secGroup = new ButtonGroup();
    for (char cSec = 'A'; cSec <= 'H'; cSec++) {
      final char secChar = cSec;
      JRadioButtonMenuItem secItem =
          new JRadioButtonMenuItem(String.valueOf(secChar), currentSec == secChar);
      secItem.addActionListener(
          e -> {
            clip.setSection(secChar);
            panel.fireProjectChanged();
            panel.refresh();
          });
      secGroup.add(secItem);
      assignSectionMenu.add(secItem);
    }
    menu.add(assignSectionMenu);

    menu.addSeparator();

    // ── Play Mode submenu ──
    JMenu playModeMenu = new JMenu("Play Mode");
    ClipModel.PlayMode currentMode = clip.getPlayMode();

    JRadioButtonMenuItem normalItem =
        new JRadioButtonMenuItem("Normal", currentMode == ClipModel.PlayMode.NORMAL);
    normalItem.addActionListener(
        e -> {
          clip.setPlayMode(ClipModel.PlayMode.NORMAL);
          if (panel.bridge != null) panel.bridge.setClipPlayMode(trackIndex, clipIdx, 0);
          panel.fireProjectChanged();
          panel.refresh();
        });
    playModeMenu.add(normalItem);

    JRadioButtonMenuItem loopItem =
        new JRadioButtonMenuItem("Loop (green)", currentMode == ClipModel.PlayMode.LOOP);
    loopItem.addActionListener(
        e -> {
          clip.setPlayMode(ClipModel.PlayMode.LOOP);
          if (panel.bridge != null) panel.bridge.setClipPlayMode(trackIndex, clipIdx, 1);
          panel.fireProjectChanged();
          panel.refresh();
        });
    playModeMenu.add(loopItem);

    JRadioButtonMenuItem onceItem =
        new JRadioButtonMenuItem("Once (yellow)", currentMode == ClipModel.PlayMode.ONCE);
    onceItem.addActionListener(
        e -> {
          clip.setPlayMode(ClipModel.PlayMode.ONCE);
          if (panel.bridge != null) panel.bridge.setClipPlayMode(trackIndex, clipIdx, 2);
          panel.fireProjectChanged();
          panel.refresh();
        });
    playModeMenu.add(onceItem);

    JRadioButtonMenuItem fillItem =
        new JRadioButtonMenuItem("Fill (purple)", currentMode == ClipModel.PlayMode.FILL);
    fillItem.addActionListener(
        e -> {
          clip.setPlayMode(ClipModel.PlayMode.FILL);
          if (panel.bridge != null) panel.bridge.setClipPlayMode(trackIndex, clipIdx, 3);
          panel.fireProjectChanged();
          panel.refresh();
        });
    playModeMenu.add(fillItem);

    // Group the radio buttons so only one can be selected
    ButtonGroup playModeGroup = new ButtonGroup();
    playModeGroup.add(normalItem);
    playModeGroup.add(loopItem);
    playModeGroup.add(onceItem);
    playModeGroup.add(fillItem);

    menu.add(playModeMenu);

    // ── Play Direction submenu ──
    JMenu playDirMenu = new JMenu("Play Direction");
    ClipModel.PlayDirection currentDir = clip.getPlayDirection();

    JRadioButtonMenuItem forwardItem =
        new JRadioButtonMenuItem("Forward", currentDir == ClipModel.PlayDirection.FORWARD);
    forwardItem.addActionListener(
        e -> {
          clip.setPlayDirection(ClipModel.PlayDirection.FORWARD);
          if (panel.bridge != null) panel.bridge.setClipPlayDirection(trackIndex, clipIdx, 0);
          panel.fireProjectChanged();
        });
    playDirMenu.add(forwardItem);

    JRadioButtonMenuItem reverseItem =
        new JRadioButtonMenuItem("Reverse", currentDir == ClipModel.PlayDirection.REVERSE);
    reverseItem.addActionListener(
        e -> {
          clip.setPlayDirection(ClipModel.PlayDirection.REVERSE);
          if (panel.bridge != null) panel.bridge.setClipPlayDirection(trackIndex, clipIdx, 1);
          panel.fireProjectChanged();
        });
    playDirMenu.add(reverseItem);

    JRadioButtonMenuItem pingPongItem =
        new JRadioButtonMenuItem("Ping-Pong", currentDir == ClipModel.PlayDirection.PING_PONG);
    pingPongItem.addActionListener(
        e -> {
          clip.setPlayDirection(ClipModel.PlayDirection.PING_PONG);
          if (panel.bridge != null) panel.bridge.setClipPlayDirection(trackIndex, clipIdx, 2);
          panel.fireProjectChanged();
        });
    playDirMenu.add(pingPongItem);

    JRadioButtonMenuItem randomItem =
        new JRadioButtonMenuItem("Random", currentDir == ClipModel.PlayDirection.RANDOM);
    randomItem.addActionListener(
        e -> {
          clip.setPlayDirection(ClipModel.PlayDirection.RANDOM);
          if (panel.bridge != null) panel.bridge.setClipPlayDirection(trackIndex, clipIdx, 3);
          panel.fireProjectChanged();
        });
    playDirMenu.add(randomItem);

    ButtonGroup playDirGroup = new ButtonGroup();
    playDirGroup.add(forwardItem);
    playDirGroup.add(reverseItem);
    playDirGroup.add(pingPongItem);
    playDirGroup.add(randomItem);

    menu.add(playDirMenu);

    stylePopupMenu(menu);
    menu.show(src, x, y);
  }

  public static JPopupMenu createMutePopupMenu(SwingGridPanel panel, int rowToSolo) {
    JPopupMenu mutePopup = new JPopupMenu();
    mutePopup.setBackground(new Color(0x18, 0x18, 0x1a));
    mutePopup.setBorder(BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x34), 1));

    JMenuItem muteOthersItem = new JMenuItem("Mute Others (Solo)");
    muteOthersItem.setBackground(new Color(0x18, 0x18, 0x1a));
    muteOthersItem.setForeground(new Color(0xdd, 0xdd, 0xde));
    muteOthersItem.setFont(new Font("SansSerif", Font.PLAIN, 11));
    muteOthersItem.addActionListener(
        evt -> {
          if (panel.projectModel != null) {
            if (panel.viewMode == SwingGridPanel.GridViewMode.CLIP
                || panel.viewMode == SwingGridPanel.GridViewMode.AUTOMATION) {
              panel.setSoloRow(panel.editedModelTrack);
            } else {
              panel.setSoloRow(rowToSolo);
            }
            panel.updateEngineMutes();
            panel.refresh();
          }
        });

    JMenuItem unmuteAllItem = new JMenuItem("Unmute All");
    unmuteAllItem.setBackground(new Color(0x18, 0x18, 0x1a));
    unmuteAllItem.setForeground(new Color(0xdd, 0xdd, 0xde));
    unmuteAllItem.setFont(new Font("SansSerif", Font.PLAIN, 11));
    unmuteAllItem.addActionListener(
        evt -> {
          if (panel.projectModel != null) {
            panel.setSoloRow(-1);
            for (TrackModel t : panel.projectModel.getTracks()) {
              t.setMuted(false);
            }
            panel.updateEngineMutes();
            panel.refresh();
          }
        });

    mutePopup.add(muteOthersItem);
    mutePopup.add(unmuteAllItem);
    return mutePopup;
  }
}
