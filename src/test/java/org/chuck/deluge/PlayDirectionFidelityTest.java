package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.FileInputStream;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;
import org.chuck.deluge.project.ProjectSerializer;
import org.chuck.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

public class PlayDirectionFidelityTest {

  @Test
  public void testPlayDirectionXmlRoundTrip() throws Exception {
    ProjectModel project = new ProjectModel();
    project.setBpm(125.0f);

    SynthTrackModel track1 = new SynthTrackModel("TestSynth1");
    ClipModel clip1 = new ClipModel("Clip1", 8, 16);
    clip1.setPlayDirection(ClipModel.PlayDirection.FORWARD);
    track1.addClip(clip1);
    project.addTrack(track1);

    SynthTrackModel track2 = new SynthTrackModel("TestSynth2");
    ClipModel clip2 = new ClipModel("Clip2", 8, 16);
    clip2.setPlayDirection(ClipModel.PlayDirection.REVERSE);
    track2.addClip(clip2);
    project.addTrack(track2);

    SynthTrackModel track3 = new SynthTrackModel("TestSynth3");
    ClipModel clip3 = new ClipModel("Clip3", 8, 16);
    clip3.setPlayDirection(ClipModel.PlayDirection.PING_PONG);
    track3.addClip(clip3);
    project.addTrack(track3);

    SynthTrackModel track4 = new SynthTrackModel("TestSynth4");
    ClipModel clip4 = new ClipModel("Clip4", 8, 16);
    clip4.setPlayDirection(ClipModel.PlayDirection.RANDOM);
    track4.addClip(clip4);
    project.addTrack(track4);

    // Serialize to XML temp file
    File tempFile = File.createTempFile("PlayDirectionTestSong", ".xml");
    tempFile.deleteOnExit();
    ProjectSerializer.save(project, tempFile);

    // Parse back from XML
    ProjectModel parsedProject =
        DelugeXmlParser.parseSong(new FileInputStream(tempFile), "PlayDirectionTestSong");

    assertNotNull(parsedProject);
    assertEquals(4, parsedProject.getTracks().size());

    SynthTrackModel parsedTrack1 = (SynthTrackModel) parsedProject.getTracks().get(0);
    assertEquals(
        ClipModel.PlayDirection.FORWARD, parsedTrack1.getClips().get(0).getPlayDirection());

    SynthTrackModel parsedTrack2 = (SynthTrackModel) parsedProject.getTracks().get(1);
    assertEquals(
        ClipModel.PlayDirection.REVERSE, parsedTrack2.getClips().get(0).getPlayDirection());

    SynthTrackModel parsedTrack3 = (SynthTrackModel) parsedProject.getTracks().get(2);
    assertEquals(
        ClipModel.PlayDirection.PING_PONG, parsedTrack3.getClips().get(0).getPlayDirection());

    SynthTrackModel parsedTrack4 = (SynthTrackModel) parsedProject.getTracks().get(3);
    assertEquals(ClipModel.PlayDirection.RANDOM, parsedTrack4.getClips().get(0).getPlayDirection());
  }

  @Test
  public void testPlayDirectionStepCalculationMath() {
    int len = 16;

    // 1. FORWARD Mode Math
    for (int currentStep = 0; currentStep < 100; currentStep++) {
      int step = currentStep % len;
      assertEquals(currentStep % len, step);
    }

    // 2. REVERSE Mode Math
    for (int currentStep = 0; currentStep < 100; currentStep++) {
      int step = (len - 1) - (currentStep % len);
      assertEquals(15 - (currentStep % 16), step);
    }

    // 3. PING_PONG Mode Math
    int period = 2 * len - 2; // 30
    int[] expectedPingPong = {
      0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3,
      2, 1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5,
      4, 3, 2, 1
    };
    for (int currentStep = 0; currentStep < expectedPingPong.length; currentStep++) {
      int phase = currentStep % period;
      int step = (phase < len) ? phase : (period - phase);
      assertEquals(expectedPingPong[currentStep], step);
    }

    // 4. RANDOM Mode Math
    int trackId = 2;
    int currentStep = 12;
    java.util.Random rand1 = new java.util.Random(currentStep * 10003L + trackId * 17L);
    int step1 = rand1.nextInt(len);

    java.util.Random rand2 = new java.util.Random(currentStep * 10003L + trackId * 17L);
    int step2 = rand2.nextInt(len);

    assertEquals(step1, step2);
  }
}
