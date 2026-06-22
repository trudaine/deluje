package org.deluge.storage.audio;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.*;
import org.deluge.playback.Sample;
import org.junit.jupiter.api.Test;

public class AudioFileReaderTest {
  @Test
  public void testReadDummyWav() throws IOException {
    File temp = File.createTempFile("test", ".wav");

    try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(temp))) {
      dos.writeBytes("RIFF");
      dos.writeInt(Integer.reverseBytes(36 + 8 + 60 + 8)); // estimated size
      dos.writeBytes("WAVE");

      // fmt
      dos.writeBytes("fmt ");
      dos.writeInt(Integer.reverseBytes(16));
      dos.writeShort(Short.reverseBytes((short) 1)); // PCM
      dos.writeShort(Short.reverseBytes((short) 1)); // Mono
      dos.writeInt(Integer.reverseBytes(44100));
      dos.writeInt(Integer.reverseBytes(88200));
      dos.writeShort(Short.reverseBytes((short) 2));
      dos.writeShort(Short.reverseBytes((short) 16));

      // data
      dos.writeBytes("data");
      dos.writeInt(Integer.reverseBytes(8));
      dos.writeShort(Short.reverseBytes((short) 1000));
      dos.writeShort(Short.reverseBytes((short) 2000));
      dos.writeShort(Short.reverseBytes((short) 3000));
      dos.writeShort(Short.reverseBytes((short) 4000));

      // smpl
      dos.writeBytes("smpl");
      dos.writeInt(Integer.reverseBytes(60));
      for (int i = 0; i < 3; i++) dos.writeInt(0);
      dos.writeInt(Integer.reverseBytes(60)); // midi note
      dos.writeInt(0); // fraction
      for (int i = 0; i < 3; i++) dos.writeInt(0);
      dos.writeInt(Integer.reverseBytes(1)); // 1 loop
      dos.writeInt(0);
      dos.writeInt(0); // loop id
      dos.writeInt(0); // type
      dos.writeInt(Integer.reverseBytes(10)); // start
      dos.writeInt(Integer.reverseBytes(20)); // end
      dos.writeInt(0);
      dos.writeInt(0);
    }

    Sample s = AudioFileReader.readSample(temp.getAbsolutePath());
    assertNotNull(s);
    assertEquals(44100.0f, s.sampleRate);
    assertEquals(60.0f, s.midiNoteFromFile);
    assertEquals(10, s.fileLoopStartSamples);
    assertEquals(20, s.fileLoopEndSamples);
    assertEquals(4, s.data.length);
    assertEquals(1000 / 32768.0f, s.data[0], 0.001f);

    temp.delete();
  }
}
