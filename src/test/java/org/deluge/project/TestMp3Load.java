package org.deluge.project;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.deluge.firmware.model.sample.Sample;
import org.deluge.firmware.storage.audio.AudioFileReader;
import org.deluge.model.ProjectModel;
import org.junit.jupiter.api.Test;

public class TestMp3Load {
  @Test
  public void testLoadVocals() throws Exception {
    File f =
        new File(
            "/Users/ludo/Downloads/Michael Jackson - Billie Jean (Ableton Remake)/Project/Samples/Imported/Vocals.mp3");
    assertTrue(f.exists(), "Vocals.mp3 should exist!");
    System.out.println("==================================================");
    System.out.println("DIAGNOSTIC MP3 LOAD TEST:");
    System.out.println("==================================================");
    System.out.println("Loading: " + f.getAbsolutePath());
    Sample s = AudioFileReader.readSample(f.getAbsolutePath());
    assertNotNull(s, "Returned null sample!");
    System.out.println("Sample loaded successfully!");
    System.out.println("FileName: " + s.fileName);
    System.out.println("SampleRate: " + s.sampleRate);
    System.out.println("Channels: " + s.numChannels);
    System.out.println("ByteDepth: " + s.byteDepth);
    assertNotNull(s.data, "Data array is null!");
    System.out.println("Data length: " + s.data.length);

    assertTrue(s.data.length > 0, "Data array should not be empty!");
    float maxVal = 0;
    for (float val : s.data) {
      if (Math.abs(val) > maxVal) maxVal = Math.abs(val);
    }
    System.out.println("Max amplitude in data: " + maxVal);
    assertTrue(maxVal > 0.01f, "Max amplitude should be non-zero (contains actual sound)!");
    System.out.println("==================================================");
  }

  @Test
  public void testPrintTrackVolumes() throws Exception {
    File alsFile =
        new File(
            "/Users/ludo/Downloads/Michael Jackson - Billie Jean (Ableton Remake)/Project/Michael Jackson - Billie Jean.als");
    org.w3c.dom.Document doc = org.deluge.ableton.AbletonProjectManager.parseAlsToXml(alsFile);
    ProjectModel project = new ProjectModel();
    org.deluge.ableton.AbletonTrackMapper.importAbletonSet(doc, project, alsFile);
    System.out.println("==================================================");
    System.out.println("PARSED TRACK VOLUME & MUTE STATES:");
    System.out.println("==================================================");
    for (int i = 0; i < project.getTracks().size(); i++) {
      var track = project.getTracks().get(i);
      System.out.println(
          "Track "
              + (i + 1)
              + ": '"
              + track.getName()
              + "' Volume="
              + track.getVolume()
              + " Muted="
              + track.isMuted());
    }
    System.out.println("==================================================");
  }
}
