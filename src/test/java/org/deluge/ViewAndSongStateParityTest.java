package org.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.deluge.model.ProjectModel;
import org.deluge.project.ProjectSerializer;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * 100% round-trip fidelity validation for all 30 cosmetic, view, and song-state XML attributes that
 * have been successfully closed in the coverage audit.
 */
public class ViewAndSongStateParityTest {

  @Test
  public void testViewAndSongStateParityRoundTrip() throws Exception {
    ProjectModel project = new ProjectModel();

    // 1. Set custom, non-default values for all 30 view/song-state parameters
    project.setAffectEntire(true);
    project.setArrangementAutoScrollOn(false);
    project.setInputTickMagnitude(3);
    project.setKey("7"); // maps to rootNote
    project.setSwingAmount(15);
    project.setSwingInterval(9);
    project.setTimePerTimerTick(300);
    project.setTimerTickFraction(123456789);
    project.setXScroll(100);
    project.setXZoom(12);
    project.setXScrollArrangementView(200);
    project.setXZoomArrangementView(18);
    project.setYScrollArrangementView(50);
    project.setYScrollSongView(-3);
    project.setYScroll(4);
    project.setYScrollKeyboard(10);
    project.setBootInArrangementView(true);
    project.setSessionLayout(2);
    project.setSongGridScrollX(8);
    project.setSongGridScrollY(16);
    project.setKeyboardLayout(1);
    project.setKeyboardRowInterval(5);
    project.setInKeyMode(true);
    project.setInKeyRowInterval(4);
    project.setInKeyScrollOffset(12);
    project.setDrumsScrollOffset(6);
    project.setDrumsZoomLevel(2);
    project.setDrumsEdgeSize(8);
    project.setAnyOfMelodicKitPercussion(1);
    project.setNumClips(14);
    project.setModFXCurrentParam("feedback");
    project.setCurrentFilterType("hpf");

    // 2. Serialize to XML
    String xml = ProjectSerializer.serializeToString(project);
    assertNotNull(xml);

    // 3. Parse back to a new model
    ProjectModel parsed =
        DelugeXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "TEST_PARITY");
    assertNotNull(parsed);

    // 4. Assert 100% lossless parity across all 30 fields
    assertTrue(parsed.isAffectEntire(), "affectEntire parity failed");
    assertFalse(parsed.isArrangementAutoScrollOn(), "arrangementAutoScrollOn parity failed");
    assertEquals(3, parsed.getInputTickMagnitude(), "inputTickMagnitude parity failed");
    assertEquals("7", parsed.getKey(), "rootNote / key parity failed");
    assertEquals(15, parsed.getSwingAmount(), "swingAmount parity failed");
    assertEquals(9, parsed.getSwingInterval(), "swingInterval parity failed");
    assertEquals(300, parsed.getTimePerTimerTick(), "timePerTimerTick parity failed");
    assertEquals(123456789, parsed.getTimerTickFraction(), "timerTickFraction parity failed");
    assertEquals(100, parsed.getXScroll(), "xScroll parity failed");
    assertEquals(12, parsed.getXZoom(), "xZoom parity failed");
    assertEquals(200, parsed.getXScrollArrangementView(), "xScrollArrangementView parity failed");
    assertEquals(18, parsed.getXZoomArrangementView(), "xZoomArrangementView parity failed");
    assertEquals(50, parsed.getYScrollArrangementView(), "yScrollArrangementView parity failed");
    assertEquals(-3, parsed.getYScrollSongView(), "yScrollSongView parity failed");
    assertEquals(4, parsed.getYScroll(), "yScroll parity failed");
    assertEquals(10, parsed.getYScrollKeyboard(), "yScrollKeyboard parity failed");
    assertTrue(parsed.isBootInArrangementView(), "inArrangementView parity failed");
    assertEquals(2, parsed.getSessionLayout(), "sessionLayout parity failed");
    assertEquals(8, parsed.getSongGridScrollX(), "songGridScrollX parity failed");
    assertEquals(16, parsed.getSongGridScrollY(), "songGridScrollY parity failed");
    assertEquals(1, parsed.getKeyboardLayout(), "keyboardLayout parity failed");
    assertEquals(5, parsed.getKeyboardRowInterval(), "keyboardRowInterval parity failed");
    assertTrue(parsed.isInKeyMode(), "inKeyMode parity failed");
    assertEquals(4, parsed.getInKeyRowInterval(), "inKeyRowInterval parity failed");
    assertEquals(12, parsed.getInKeyScrollOffset(), "inKeyScrollOffset parity failed");
    assertEquals(6, parsed.getDrumsScrollOffset(), "drumsScrollOffset parity failed");
    assertEquals(2, parsed.getDrumsZoomLevel(), "drumsZoomLevel parity failed");
    assertEquals(8, parsed.getDrumsEdgeSize(), "drumsEdgeSize parity failed");
    assertEquals(
        1, parsed.getAnyOfMelodicKitPercussion(), "anyOfMelodicKitPercussion parity failed");
    assertEquals(14, parsed.getNumClips(), "numClips parity failed");
    assertEquals("feedback", parsed.getModFXCurrentParam(), "modFXCurrentParam parity failed");
    assertEquals("hpf", parsed.getCurrentFilterType(), "currentFilterType parity failed");
  }
}
