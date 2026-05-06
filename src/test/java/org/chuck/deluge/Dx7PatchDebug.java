package org.chuck.deluge;

import org.chuck.audio.util.Dx7Patch;
import org.chuck.deluge.model.*;
import org.chuck.deluge.xml.DelugeXmlParser;
import java.io.*;

public class Dx7PatchDebug {
    public static void main(String[] args) throws Exception {
        ProjectModel project = DelugeXmlParser.parseSong(
            new FileInputStream("deluge/src/main/resources/SONGS/Dx7A.xml"), "Dx7A");
        for (TrackModel t : project.getTracks()) {
            if (t instanceof SynthTrackModel s) {
                System.out.println("Track: " + s.getName());
                System.out.println("  SynthMode: " + s.getSynthMode());
                System.out.println("  SynthAlgorithm: " + s.getSynthAlgorithm());
                String hex = s.getDx7Patch();
                if (hex != null && hex.length() > 10) {
                    Dx7Patch patch = Dx7Patch.fromHex(hex);
                    System.out.println("  Algorithm: " + patch.algorithm());
                    System.out.println("  Feedback: " + patch.feedback());
                    System.out.println("  Transpose: " + patch.transpose());
                    System.out.println("  Name: " + patch.name());
                    System.out.println("  LFO speed=" + (patch.raw()[137]&0xFF) + " delay=" + (patch.raw()[138]&0xFF)
                        + " pmd=" + (patch.raw()[139]&0xFF) + " amd=" + (patch.raw()[140]&0xFF)
                        + " sync=" + (patch.raw()[141]&0xFF) + " wave=" + (patch.raw()[142]&0xFF));
                    int opSwitch = patch.raw()[155] & 0xFF;
                    System.out.println("  opSwitch=0x" + Integer.toHexString(opSwitch));
                    for (int i = 0; i < 6; i++) {
                        boolean active = ((opSwitch >> i) & 1) != 0;
                        var op = patch.operators()[i];
                        System.out.println("  Op" + i + ": active=" + active
                            + " outLevel=" + op.outputLevel()
                            + " mode=" + op.mode() + " coarse=" + op.coarseFreq()
                            + " fine=" + op.fineFreq() + " detune=" + op.detune()
                            + " velSens=" + op.velSens()
                            + " ampModSens=" + op.ampModSens()
                            + " egRates=[" + op.egRate()[0]+","+op.egRate()[1]+","+op.egRate()[2]+","+op.egRate()[3]+"]"
                            + " egLevels=[" + op.egLevel()[0]+","+op.egLevel()[1]+","+op.egLevel()[2]+","+op.egLevel()[3]+"]");
                    }
                }
            }
        }
    }
}
