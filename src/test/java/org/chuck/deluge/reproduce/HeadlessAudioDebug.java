package org.chuck.deluge.reproduce;

import java.io.File;
import java.io.FileInputStream;
import org.chuck.deluge.firmware.engine.*;
import org.chuck.deluge.firmware.gui.views.KitView;
import org.chuck.deluge.firmware.model.*;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.xml.DelugeXmlParser;

public class HeadlessAudioDebug {
  public static void main(String[] args) throws Exception {
    System.out.println("=== Deluge Headless Audio Debug ===");

    File kitFile = new File("deluge/src/main/resources/KITS/000 TR-808.XML");
    System.out.println("Loading kit: " + kitFile.getAbsolutePath());
    KitTrackModel kitModel;
    try (FileInputStream fis = new FileInputStream(kitFile)) {
      kitModel = DelugeXmlParser.parseKit(fis, "808");
    }

    Song fwSong = FirmwareFactory.createSong(new org.chuck.deluge.model.ProjectModel());
    InstrumentClip ic = FirmwareFactory.createKitClip(kitModel);
    fwSong.clips.add(ic);

    FirmwareKit fwKit = (FirmwareKit) ic.sound;
    FirmwareSound kick = fwKit.drumSounds.get(0);

    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.sounds.add(fwKit);

    KitView view = new KitView(ic);

    System.out.println("Triggering pad (0,0,127)...");
    view.padAction(0, 0, 127);

    System.out.println("Rendering 50 blocks (6400 samples)...");
    long totalSum = 0;
    int totalNonZero = 0;
    for (int blk = 0; blk < 50; blk++) {
      engine.renderBlock(128);
      for (int i = 0; i < 128; i++) {
        if (engine.masterBuffer[i].l != 0) {
          totalNonZero++;
          totalSum += Math.abs(engine.masterBuffer[i].l);
        }
      }
    }

    System.out.println("Result:");
    System.out.println("  Total non-zero samples across all blocks: " + totalNonZero);
    System.out.println("  Total Sum of Abs L: " + totalSum);

    int activeVoices = 0;
    for (FirmwareVoice v : kick.voices) {
      if (v.active) {
        activeVoices++;
        org.chuck.deluge.firmware.dsp.StereoSample[] dummy =
            new org.chuck.deluge.firmware.dsp.StereoSample[128];
        for (int i = 0; i < 128; i++) dummy[i] = new org.chuck.deluge.firmware.dsp.StereoSample();
        v.render(dummy, 128, 10000, 10000);
        long voiceSum = 0;
        for (var s : dummy) voiceSum += Math.abs(s.l) + Math.abs(s.r);
        System.out.println("  Individual Voice Internal Sum: " + voiceSum);
      }
    }
    System.out.println("  Active voices in kick sound: " + activeVoices);
  }
}
