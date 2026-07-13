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

      // smpl (RIFF spec layout: 36-byte header + one 24-byte loop entry = 60 bytes)
      dos.writeBytes("smpl");
      dos.writeInt(Integer.reverseBytes(60));
      dos.writeInt(0); // Manufacturer
      dos.writeInt(0); // Product
      dos.writeInt(0); // SamplePeriod
      dos.writeInt(Integer.reverseBytes(60)); // MIDIUnityNote
      dos.writeInt(0); // MIDIPitchFraction
      dos.writeInt(0); // SMPTEFormat
      dos.writeInt(0); // SMPTEOffset
      dos.writeInt(Integer.reverseBytes(1)); // NumSampleLoops
      dos.writeInt(0); // SamplerData
      dos.writeInt(0); // loop: CuePointID
      dos.writeInt(0); // loop: Type
      dos.writeInt(Integer.reverseBytes(10)); // loop: Start
      dos.writeInt(Integer.reverseBytes(20)); // loop: End
      dos.writeInt(0); // loop: Fraction
      dos.writeInt(0); // loop: PlayCount
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
