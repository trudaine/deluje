package org.chuck.deluge.engine.dsp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.junit.jupiter.api.Test;

public class NativeMidiExporterTest {

  @Test
  public void testExport() {
    NativeMidiExporter exporter = new NativeMidiExporter();
    File temp = new File("test_midi_export.mid");
    exporter.open(temp.getAbsolutePath());
    exporter.addNote(0, 60, 100, 480);
    exporter.advance(480);
    exporter.save(temp.getAbsolutePath());

    assertTrue(temp.exists());
    assertTrue(temp.length() > 0);
    temp.delete();
  }
}
