package org.chuck.deluge.xml;

import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.SynthTrackModel;
import org.chuck.deluge.model.TrackModel;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.StepData;
import java.io.File;
import java.io.InputStream;
import java.util.List;

public class Dx7CParserTest {
    public static void main(String[] args) throws Exception {
        // Load from classpath just like E2E test does
        InputStream is = Dx7CParserTest.class.getResourceAsStream("/SONGS/Dx7C.xml");
        if (is == null) { System.out.println("NOT FOUND"); return; }
        // Write to temp file for parsing
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("dx7c", ".xml");
        java.nio.file.Files.copy(is, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        System.out.println("Loading: Dx7C.xml");
        ProjectModel pm = DelugeXmlParser.parseSong(tmp.toFile());
        System.out.println("Done. Tracks=" + pm.getTracks().size());

        for (int t = 0; t < pm.getTracks().size(); t++) {
            TrackModel track = pm.getTracks().get(t);
            System.out.println("\n=== Track " + t + ": " + track.getName() + " (" + track.getClass().getSimpleName() + ") ===");
            for (int c = 0; c < track.getClips().size(); c++) {
                ClipModel clip = track.getClips().get(c);
                System.out.println("  Clip " + c + ": " + clip.getName() + " rows=" + clip.getRowCount() + " steps=" + clip.getStepCount());
                for (int r = 0; r < Math.min(clip.getRowCount(), 16); r++) {
                    StringBuilder sb = new StringBuilder();
                    for (int s = 0; s < clip.getStepCount(); s++) {
                        sb.append(clip.getStep(r, s).active() ? "x" : ".");
                    }
                    String cname = track instanceof KitTrackModel ? "drum" + r : "row" + r;
                    System.out.println("    " + cname + ": " + sb);
                }
            }
        }
        java.nio.file.Files.delete(tmp);
    }
}
