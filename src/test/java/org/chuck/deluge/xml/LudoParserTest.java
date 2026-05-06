package org.chuck.deluge.xml;

import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.SynthTrackModel;
import org.chuck.deluge.model.TrackModel;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.StepData;
import java.io.File;
import java.util.List;

public class LudoParserTest {
    public static void main(String[] args) throws Exception {
        File f = new File("C:/Users/ludo/delugedownload/ludocard/SONGS/Ludo.XML");
        System.out.println("Loading: " + f.getAbsolutePath() + " exists=" + f.exists());
        ProjectModel pm = DelugeXmlParser.parseSong(f);
        System.out.println("Done. Tracks=" + pm.getTracks().size());

        for (int t = 0; t < pm.getTracks().size(); t++) {
            TrackModel track = pm.getTracks().get(t);
            System.out.println("\n=== Track " + t + ": " + track.getName() + " (" + track.getClass().getSimpleName() + ") ===");

            for (int c = 0; c < track.getClips().size(); c++) {
                ClipModel clip = track.getClips().get(c);
                System.out.println("  Clip " + c + ": " + clip.getName() + " rows=" + clip.getRowCount() + " steps=" + clip.getStepCount());

                for (int r = 0; r < clip.getRowCount(); r++) {
                    StringBuilder sb = new StringBuilder();
                    for (int s = 0; s < clip.getStepCount(); s++) {
                        sb.append(clip.getStep(r, s).active() ? "x" : ".");
                        if ((s + 1) % 16 == 0) sb.append("|");
                    }
                    System.out.println("    row" + r + ": " + sb);
                }
            }
        }
    }
}
