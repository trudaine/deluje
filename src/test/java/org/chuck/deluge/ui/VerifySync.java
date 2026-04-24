package org.chuck.deluge.ui;

import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class VerifySync {
    @Test
    public void testSync() throws Exception {

        ChuckVM vm = new ChuckVM(44100, 2);
        BridgeContract bridge = new BridgeContract();
        bridge.register(vm);

        // 1. Load Model
        java.io.InputStream is = VerifySync.class.getResourceAsStream("/SONGS/song3.xml");
        org.chuck.deluge.model.ProjectModel model = org.chuck.deluge.xml.DelugeXmlParser.parseSong(is, "song3");
        
        System.out.println("=== MODEL STATE ===");
        int tIdx = 0;
        for (org.chuck.deluge.model.TrackModel track : model.getTracks()) {
           int cIdx = 0;
           for (org.chuck.deluge.model.ClipModel clip : track.getClips()) {
              System.out.println("Track " + tIdx + " (" + track.getName() + ") Clip " + cIdx + ":");
              for (int r = 0; r < 8; r++) {
                 for (int s = 0; s < 16; s++) {
                    org.chuck.deluge.model.StepData sd = clip.getStep(r, s);
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
        SwingGridPanel clipPanel = new SwingGridPanel(vm, bridge);
        clipPanel.setProjectModel(model);
        clipPanel.setViewMode(SwingGridPanel.GridViewMode.CLIP);

        System.out.println("\n=== UI VIEW STATE ===");
        for (int t = 0; t < model.getTracks().size(); t++) {
           clipPanel.setBaseTrackId(t * 8);
           clipPanel.setActiveClipId(0);

           bridge.clearAllSteps();
           org.chuck.deluge.model.TrackModel tModel = model.getTracks().get(t);
           org.chuck.deluge.model.ClipModel cModel = tModel.getClips().get(0);
           for (int r = 0; r < 8; r++) {
              for (int s = 0; s < 16; s++) {
                 org.chuck.deluge.model.StepData sd = cModel.getStep(r, s);
                 if (sd != null) {
                    bridge.setStep(t * 8 + r, s, sd.active());
                 }
              }
           }

           System.out.println("UI view for Track " + t + " Clip 0:");
           for (int r = 0; r < 8; r++) {
              for (int s = 0; s < 16; s++) {
                 if (bridge.getStep(t * 8 + r, s)) {
                    System.out.println("  UI Active Pad -> Row: " + r + " Col: " + s);
                 }
              }
           }
        }
        
        vm.shutdown();
        System.exit(0);
    }
}
