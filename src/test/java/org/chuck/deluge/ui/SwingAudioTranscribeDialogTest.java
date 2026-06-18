package org.chuck.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.GraphicsEnvironment;
import java.io.File;
import org.junit.jupiter.api.Test;

public class SwingAudioTranscribeDialogTest {

  @Test
  public void testDialogInitialization() {
    // Prevent failure in headless environments (like CI/CD servers)
    if (GraphicsEnvironment.isHeadless()) {
      System.out.println("Skipping Swing GUI test in headless environment.");
      return;
    }

    File tempAudio = new File("test_loop.wav");
    SwingAudioTranscribeDialog dialog = new SwingAudioTranscribeDialog(null, tempAudio);

    assertNotNull(dialog);
    assertEquals("Audio Transcriber (Neural Audio-to-MIDI)", dialog.getTitle());
    assertFalse(dialog.isTranscriptionSuccessful());
    assertNull(dialog.getCompiledProject());

    // Clean up
    dialog.dispose();
  }
}
