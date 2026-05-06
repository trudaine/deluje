package org.chuck.deluge.xml;

import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.SynthTrackModel;
import org.chuck.deluge.model.TrackModel;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.StepData;
import java.io.File;
import java.io.FileInputStream;
import java.util.List;

public class Halcyon3ParserTest {
    public static void main(String[] args) throws Exception {
        // Use Halcyon 3 from C: drive (D: drive removed)
        boolean useD = false;
        String defaultSong = "C:/Users/ludo/delugedownload/ludocard/SONGS/Halcyon 3.XML";
        String defaultBase = "C:/Users/ludo/delugedownload/ludocard";
        String songPath = args.length > 0 ? args[0] : defaultSong;
        String basePath = args.length > 1 ? args[1] : defaultBase;
        File f = new File(songPath);
        System.out.println("Loading: " + f.getAbsolutePath() + " exists=" + f.exists());
        System.out.println("Args: " + java.util.Arrays.toString(args));
        System.out.println("songPath=" + songPath + " basePath=" + basePath);
        ProjectModel pm = DelugeXmlParser.parseSong(new FileInputStream(f), basePath);
        System.out.println("Done. Tracks=" + pm.getTracks().size());

        for (int t = 0; t < pm.getTracks().size(); t++) {
            TrackModel track = pm.getTracks().get(t);
            System.out.println("\n=== Track " + t + ": \"" + track.getName() + "\" (" + track.getClass().getSimpleName() + ") ===");

            if (track instanceof KitTrackModel kt) {
                System.out.println("  KitTrack: clipCount=" + kt.getClips().size());
            }

            for (int c = 0; c < track.getClips().size(); c++) {
                ClipModel clip = track.getClips().get(c);
                int steps = clip.getStepCount();
                int bars = (steps + 15) / 16;
                System.out.println("  Clip " + c + ": \"" + clip.getName() + "\" rows=" + clip.getRowCount()
                    + " steps=" + steps + " bars=" + bars);

                // Show clip in bar-aligned chunks so user can map to hardware "1:1:1" etc.
                for (int r = 0; r < clip.getRowCount(); r++) {
                    StringBuilder sb = new StringBuilder();
                    for (int s = 0; s < steps; s++) {
                        sb.append(clip.getStep(r, s).active() ? "x" : ".");
                        if ((s + 1) % 16 == 0) sb.append("|");
                    }
                    String label = (track instanceof KitTrackModel) ? "ch" + r : "row" + r;
                    System.out.println("    " + label + ": " + sb);
                }

                // Also show per-bar view for all tracks (not just track 0)
                if (bars > 1) {
                    System.out.println("    --- Per-bar breakdown ---");
                    for (int b = 0; b < bars; b++) {
                        int barStart = b * 16;
                        int barEnd = Math.min(barStart + 16, steps);
                        System.out.println("    Bar " + (b+1) + " (steps " + barStart + "-" + (barEnd-1) + "):");
                        for (int r = 0; r < clip.getRowCount(); r++) {
                            StringBuilder barSb = new StringBuilder();
                            for (int s = barStart; s < barEnd; s++) {
                                barSb.append(clip.getStep(r, s).active() ? "x" : ".");
                            }
                            String label = (track instanceof KitTrackModel) ? "ch" + r : "row" + r;
                            System.out.println("      " + label + ": " + barSb);
                        }
                    }
                }
            }
        }
    }
}
