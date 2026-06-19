package org.deluge.ui;

import org.deluge.BridgeContract;
import org.junit.jupiter.api.Test;

public class VerifySync {
  @Test
  public void testSync() throws Exception {

    BridgeContract bridge = new BridgeContract();

    // 1. Load Model
    java.io.InputStream is = VerifySync.class.getResourceAsStream("/SONGS/song3.xml");
    org.deluge.model.ProjectModel model = org.deluge.xml.DelugeXmlParser.parseSong(is, "song3");

    for (org.deluge.model.TrackModel track : model.getTracks()) {
      for (org.deluge.model.ClipModel clip : track.getClips()) {
        clip.setRowCount(8);
        for (int r = 0; r < 8; r++) {
          for (int s = 0; s < clip.getStepCount(); s++) {
            clip.setStep(r, s, org.deluge.model.StepData.of(false, 0.8f, 0.5f, 1.0f, 0));
          }
        }

        for (int s = 0; s < clip.getStepCount(); s++) {
          int r = (s < 8) ? s : (15 - s);
          clip.setStep(r, s, org.deluge.model.StepData.of(true, 0.8f, 0.5f, 1.0f, 0));
        }
      }
    }

    org.deluge.project.ProjectSerializer.save(
        model,
        new java.io.File(
            System.getProperty("project.basedir", System.getProperty("user.dir"))
                + "/src/main/resources/SONGS/song3.xml"));
    System.out.println("VerifySync: song3.xml written on disk!");

    System.out.println("=== MODEL STATE ===");

    int tIdx = 0;
    for (org.deluge.model.TrackModel track : model.getTracks()) {
      int cIdx = 0;
      for (org.deluge.model.ClipModel clip : track.getClips()) {
        System.out.println("Track " + tIdx + " (" + track.getName() + ") Clip " + cIdx + ":");
        for (int r = 0; r < 8; r++) {
          for (int s = 0; s < clip.getStepCount(); s++) {
            org.deluge.model.StepData sd = clip.getStep(r, s);
            if (sd != null && sd.active()) {
              System.out.println("  Selected Step -> Row: " + r + " Col: " + s);
            }
          }
        }
        cIdx++;
      }
      tIdx++;
    }

    // 2. Load UI View
    SwingGridPanel clipPanel = new SwingGridPanel(bridge);
    clipPanel.setProjectModel(model);
    clipPanel.setViewMode(SwingGridPanel.GridViewMode.CLIP);

    System.out.println("\n=== UI VIEW STATE ===");
    for (int t = 0; t < model.getTracks().size(); t++) {
      clipPanel.setBaseTrackId(t * 8);
      clipPanel.setActiveClipId(0);

      bridge.clearAllSteps();
      org.deluge.model.TrackModel tModel = model.getTracks().get(t);
      org.deluge.model.ClipModel cModel = tModel.getClips().get(0);
      boolean isSynth = tModel instanceof org.deluge.model.SynthTrackModel;
      for (int r = 0; r < 8; r++) {
        for (int s = 0; s < cModel.getStepCount(); s++) {
          org.deluge.model.StepData sd = cModel.getStep(r, s);
          if (sd != null && sd.active()) {
            if (isSynth) {
              bridge.setStep(t * 8, s, true);
              bridge.setPitch(t * 8, s, (24 - 1) - r);
            } else {
              bridge.setStep(t * 8 + r, s, true);
            }
          }
        }
      }

      System.out.println("UI view for Track " + t + " Clip 0:");

      for (int r = 0; r < 8; r++) {
        for (int s = 0; s < BridgeContract.STEPS; s++) {
          if (isSynth) {
            if (bridge.getStep(t * 8, s) && bridge.getPitch(t * 8, s) == (24 - 1 - r)) {
              System.out.println("  UI Active Pad -> Row: " + r + " Col: " + s);
            }
          } else {
            if (bridge.getStep(t * 8 + r, s)) {
              System.out.println("  UI Active Pad -> Row: " + r + " Col: " + s);
            }
          }
        }
      }
    }

    bridge.shutdown();
  }
}
