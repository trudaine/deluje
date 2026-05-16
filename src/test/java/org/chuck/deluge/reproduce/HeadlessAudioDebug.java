package org.chuck.deluge.reproduce;

import org.chuck.deluge.firmware.engine.*;
import org.chuck.deluge.firmware.model.*;
import org.chuck.deluge.firmware.gui.views.KitView;
import org.chuck.deluge.firmware.hid.ActionResult;
import org.chuck.deluge.firmware.hid.MatrixDriver;
import org.chuck.deluge.firmware.model.note.Note;
import org.chuck.deluge.firmware.dsp.oscillators.OscType;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.xml.DelugeXmlParser;
import java.io.File;
import java.io.FileInputStream;

public class HeadlessAudioDebug {
    public static void main(String[] args) throws Exception {
        System.out.println("=== Deluge Headless Audio Debug ===");
        
        // 0. Verify Math
        System.out.println("Verifying Project Constants:");
        System.out.println("  sineWaveSmall length: " + org.chuck.deluge.firmware.util.LookupTables.sineWaveSmall.length);
        int a = org.chuck.deluge.firmware.util.Q31.ONE;
        int b = org.chuck.deluge.firmware.util.Q31.ONE;
        int res = org.chuck.deluge.firmware.util.Q31.mult(a, b);
        System.out.println("  Q31.mult(ONE, ONE) = " + res + " (Expected " + a + " or close)");
        
        // 1. Load Kit
        File kitFile = new File("deluge/src/main/resources/KITS/000 TR-808.XML");
        System.out.println("Loading kit: " + kitFile.getAbsolutePath());
        KitTrackModel kitModel;
        try (FileInputStream fis = new FileInputStream(kitFile)) {
            kitModel = DelugeXmlParser.parseKit(fis, "808");
        }
        
        System.out.println("Parsed model has " + kitModel.getDrums().size() + " drums.");
        for (int i = 0; i < Math.min(5, kitModel.getDrums().size()); i++) {
            org.chuck.deluge.model.Drum d = kitModel.getDrums().get(i);
            if (d instanceof org.chuck.deluge.model.SoundDrum sd) {
                System.out.println("  Drum " + i + " path: " + sd.getSamplePath());
            }
        }
        
        // 2. Create Firmware Objects
        Song fwSong = FirmwareFactory.createSong(new org.chuck.deluge.model.ProjectModel());
        InstrumentClip ic = FirmwareFactory.createKitClip(kitModel); // USE THE FACTORY TO CREATE THE CLIP FROM MODEL
        fwSong.clips.add(ic);
        
        FirmwareKit fwKit = (FirmwareKit) ic.sound;
        FirmwareSound kick = fwKit.drumSounds.get(0);
        
        FirmwareAudioEngine engine = new FirmwareAudioEngine();
        engine.sounds.add(fwKit);
        
        KitView view = new KitView(ic);
        
        // 3. Trigger Pad
        System.out.println("Setting sound to SINE mode for verification...");
        fwKit.drumSounds.get(0).oscTypes[0] = OscType.SINE;
        fwKit.drumSounds.get(0).paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = org.chuck.deluge.firmware.util.Q31.ONE;
        
        System.out.println("Triggering pad (0,0,127)...");
        view.padAction(0, 0, 127);
        
        // 4. Trace Rendering
        System.out.println("Rendering 50 blocks (6400 samples)...");
        long totalSum = 0;
        int totalNonZero = 0;
        
        for (int blk = 0; blk < 50; blk++) {
            // Force envelope open for testing
            fwKit.drumSounds.get(0).voices.forEach(v -> {
                v.sourceValues[org.chuck.deluge.firmware.modulation.patch.PatchSource.ENVELOPE_0.ordinal()] = 1 << 30; // 50% open
            });

            engine.renderBlock(128);
            for (int i = 0; i < 128; i++) {
                if (engine.masterBuffer[i].l != 0) {
                    totalNonZero++;
                    totalSum += Math.abs(engine.masterBuffer[i].l);
                }
            }
        }
        
        // 5. Inspect Output
        System.out.println("Result:");
        System.out.println("  Total non-zero samples across all blocks: " + totalNonZero);
        System.out.println("  Total Sum of Abs L: " + totalSum);
        
        if (totalNonZero == 0) {
            System.err.println("FAIL: Engine is SILENT.");
            // Deep debug
            System.out.println("Deep Debug Info:");
            System.out.println("  Kick Sample Loaded: " + (kick.samples[0] != null));
            if (kick.samples[0] != null) {
                System.out.println("  Kick Sample Size: " + kick.samples[0].getNumSamples());
            }
        } else {
            System.out.println("SUCCESS: Engine produced audio data.");
        }
    }
}
