package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.deluge.model.ProjectModel;
import org.junit.jupiter.api.Test;

/**
 * Guards reverb pan's sign preservation: {@code readSongRawAttr} applies {@code Math.abs()}, which
 * is correct for width/hpf (unsigned magnitudes) but was also wrongly applied to pan (bipolar:
 * negative=left, positive=right, matching C {@code audio_engine.cpp}'s signed {@code panAmount}) —
 * a hard-left pan would silently become hard-right. Fixed by routing pan through the new
 * sign-preserving {@code readSongSignedRawAttr}.
 */
public class ReverbPanSignTest {

  @Test
  void negativePanIsPreservedNotFlippedPositive() throws Exception {
    File songFile = new File("src/main/resources/SONGS/TestReverbPanSign.xml");
    assertTrue(songFile.exists(), "fixture song not found");
    ProjectModel project = DelugeXmlParser.parseSong(songFile);

    assertTrue(
        project.getReverbPan() < 0,
        "reverb pan=0x80000000 (hard left) must stay negative, got " + project.getReverbPan());
  }
}
