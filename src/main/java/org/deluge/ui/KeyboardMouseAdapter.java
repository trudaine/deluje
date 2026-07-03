package org.deluge.ui;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import org.deluge.model.ClipModel;
import org.deluge.model.StepData;
import org.deluge.model.TrackModel;

/**
 * Adapter handling mouse press/release on isomorphic keyboard grid keys. Accesses companion
 * properties package-privately.
 */
class KeyboardMouseAdapter extends MouseAdapter {
  private final int note;
  private final SwingGridPanel panel;

  KeyboardMouseAdapter(SwingGridPanel panel, int note) {
    this.panel = panel;
    this.note = note;
  }

  @Override
  public void mousePressed(MouseEvent e) {
    if (SwingGridPanel.lockArmedTrack == panel.editedModelTrack
        && SwingGridPanel.lockArmedStep != -1
        && panel.projectModel != null) {
      int trkIndex = panel.scrollOffset - note;
      if (trkIndex >= 0 && trkIndex < panel.voiceRowCount) {
        int engineRow = panel.baseTrackId + trkIndex;
        boolean st = panel.bridge.getStep(engineRow, SwingGridPanel.lockArmedStep);
        panel.bridge.setStep(engineRow, SwingGridPanel.lockArmedStep, !st);

        TrackModel tModel = panel.projectModel.getTracks().get(SwingGridPanel.lockArmedTrack);
        if (panel.activeClipId < tModel.getClips().size()) {
          ClipModel cModel = tModel.getClips().get(panel.activeClipId);
          cModel.setStep(
              trkIndex,
              SwingGridPanel.lockArmedStep,
              new StepData(!st, 0.8f, 0.5f, 1.0f, 0, 1, 1.0f));
        }
        panel.fireProjectChanged();
      }
    }
    panel.triggerKeyboardNote(note);
  }

  @Override
  public void mouseReleased(MouseEvent e) {
    panel.triggerKeyboardNoteRelease(note);
  }
}
