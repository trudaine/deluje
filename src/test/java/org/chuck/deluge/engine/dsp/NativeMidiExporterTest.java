package org.chuck.deluge.engine.dsp;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.io.File;

public class NativeMidiExporterTest {

    @Test
    public void testExport() {
        NativeMidiExporter exporter = new NativeMidiExporter();
        exporter.addNote(0, 60, 100, 480);
        exporter.advance(480);
        
        File temp = new File("test_midi_export.mid");
        exporter.save(temp.getAbsolutePath());
        
        assertTrue(temp.exists());
        assertTrue(temp.length() > 0);
        temp.delete();
    }
}
