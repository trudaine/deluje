package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.util.List;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.model.*;
import org.chuck.deluge.ui.ArrangerPlaybackScheduler;
import org.chuck.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * High-fidelity regressions testing for linear Arranger timelines load and playbacks. Asserts
 * hexadecimal clipInstances decodes, dynamic steps indexing, and scheduler steps transfer loops.
 */
public class ArrangerTimelineFidelityTest {

  @Test
  public void testClipInstancesHexParsing() throws Exception {
    // Placements: pos=96 (bar 1 step 4), duration=192 (2 bars). Session Clip Index = 0.
    // Hex: pos (00000060) + length (000000C0) + code (00000000) = "0x00000060000000C000000000"
    String xmlContent =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<song bpm=\"120\" swing=\"50\">\n"
            + "  <instruments>\n"
            + "    <sound presetSlot=\"0\" presetName=\"SYNTH 1\">\n"
            + "      <noteRows>\n"
            + "        <noteRow note=\"60\" noteData=\"0x0000000000000018\"/>\n"
            + "      </noteRows>\n"
            + "    </sound>\n"
            + "  </instruments>\n"
            + "  <tracks>\n"
            + "    <track clipInstances=\"0x00000060000000C000000000\">\n"
            + "      <noteRows>\n"
            + "        <noteRow note=\"60\" noteData=\"0x0000000000000018\"/>\n"
            + "      </noteRows>\n"
            + "    </track>\n"
            + "  </tracks>\n"
            + "  <sessionClips>\n"
            + "    <instrumentClip name=\"CLIP 0\">\n"
            + "      <noteRows>\n"
            + "        <noteRow note=\"60\" noteData=\"0x0000000000000018\"/>\n"
            + "      </noteRows>\n"
            + "    </instrumentClip>\n"
            + "  </sessionClips>\n"
            + "</song>";

    ProjectModel project =
        DelugeXmlParser.parseSong(new ByteArrayInputStream(xmlContent.getBytes()), "MockSong");

    assertNotNull(project);
    List<ArrangerClip> timeline = project.getArrangerTimeline();
    assertEquals(1, timeline.size());

    ArrangerClip placement = timeline.get(0);
    assertEquals(0, placement.trackIndex());
    assertEquals("CLIP 0", placement.clip().getName());
    assertEquals(96, placement.startTicks());
    assertEquals(192, placement.durationTicks());
    assertEquals(1.0, placement.getStartBar());
    assertEquals(2.0, placement.getDurationBars());
  }

  @Test
  public void testRealTimeArrangerSchedulerStepTransfer() throws Exception {
    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    ProjectModel project = new ProjectModel();

    TrackModel track = new SynthTrackModel("SYNTH 1");
    ClipModel clip = new ClipModel("CLIP A", 1, 16);
    // Put a step note trigger at local step 2
    clip.setStep(0, 2, StepData.of(true, 60.0f, 1.0f, 1.0f, 0));
    track.addClip(clip);
    project.addTrack(track);

    // Place this clip on the arranger timeline starting at ticks pos=48 (step 2) with duration=96
    // (1
    // bar)
    ArrangerClip placement = new ArrangerClip(0, clip, 48, 96);
    project.addArrangerClip(placement);

    ArrangerPlaybackScheduler scheduler = new ArrangerPlaybackScheduler(vm, bridge, project);
    scheduler.setArrangerModeActive(true);

    // Force play state G_PLAY = 1, and set current playhead step to 3 (upcoming step is 4!)
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.setGlobalInt(BridgeContract.G_CURRENT_STEP, 3L);

    // Wait for the fast thread polling loop to register step updates
    Thread.sleep(20);

    // Upcoming step 4 % 16 = 4. Local step = (4 - 2) = 2. Since clip step 2 is active, bridge pad 4
    // must be true!
    int col = 4;
    assertTrue(bridge.getStep(0, col));

    scheduler.shutdown();
  }
}
