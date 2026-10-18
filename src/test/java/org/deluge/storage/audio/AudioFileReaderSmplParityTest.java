package org.deluge.storage.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.deluge.playback.Sample;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies WAV smpl chunk parsing against C++ audio_file.cpp:189-235. */
class AudioFileReaderSmplParityTest {

  @TempDir Path tempDir;

  @BeforeEach
  void clearCache() {
    AudioFileReader.clearCache();
  }

  @Test
  void unsignedMidiPitchFractionWithHighBitSetAddsPositiveHalfSemitone() throws IOException {
    // midiNote = 60, midiPitchFraction = 0x80000000 (50 cents above 60 -> 60.5f)
    File wav = writeWavWithSmpl(tempDir.resolve("high_frac.wav"), 60, 0x80000000, 0, 0, 0);
    Sample sample = AudioFileReader.readSample(wav.getPath());
    assertNotNull(sample);
    assertEquals(60.5f, sample.midiNoteFromFile, 1e-5f);
  }

  @Test
  void zeroMidiNoteAndZeroPitchFractionKeepsDefaultMidiNote60() throws IOException {
    // C audio_file.cpp:202: if ((midiNote || midiPitchFraction) && midiNote < 128)
    File wav = writeWavWithSmpl(tempDir.resolve("zero_note.wav"), 0, 0, 1, 10, 100);
    Sample sample = AudioFileReader.readSample(wav.getPath());
    assertNotNull(sample);
    assertEquals(60.0f, sample.midiNoteFromFile, 1e-5f);
    assertEquals(10, sample.fileLoopStartSamples);
    assertEquals(100, sample.fileLoopEndSamples);
  }

  @Test
  void zeroMidiNoteWithNonZeroPitchFractionUpdatesMidiNoteFromFile() throws IOException {
    // midiNote = 0, midiPitchFraction = 0x80000000 -> 0.5f
    File wav = writeWavWithSmpl(tempDir.resolve("zero_note_frac.wav"), 0, 0x80000000, 0, 0, 0);
    Sample sample = AudioFileReader.readSample(wav.getPath());
    assertNotNull(sample);
    assertEquals(0.5f, sample.midiNoteFromFile, 1e-5f);
  }

  @Test
  void negativeSignedMidiNoteIsRejectedByUnsignedRangeCheck() throws IOException {
    // 0xFFFFFFFF as uint32_t is 4294967295 (>= 128), so midiNoteFromFile must stay 60.0f
    File wav = writeWavWithSmpl(tempDir.resolve("invalid_note.wav"), -1, 0, 0, 0, 0);
    Sample sample = AudioFileReader.readSample(wav.getPath());
    assertNotNull(sample);
    assertEquals(60.0f, sample.midiNoteFromFile, 1e-5f);
  }

  @Test
  void multipleSmplLoopsAreIgnoredMatchingCppNumLoopsEqualsOneCheck() throws IOException {
    // C audio_file.cpp:212: only numLoops == 1 sets fileLoopStartSamples / fileLoopEndSamples
    File wav = writeWavWithSmpl(tempDir.resolve("two_loops.wav"), 64, 0, 2, 20, 80);
    Sample sample = AudioFileReader.readSample(wav.getPath());
    assertNotNull(sample);
    assertEquals(64.0f, sample.midiNoteFromFile, 1e-5f);
    assertEquals(-1, sample.fileLoopStartSamples);
    assertEquals(-1, sample.fileLoopEndSamples);
  }

  private static File writeWavWithSmpl(
      Path target, int midiNote, int midiPitchFraction, int numLoops, int loopStart, int loopEnd)
      throws IOException {
    int numSamples = 128;
    int fmtLen = 16;
    int dataLen = numSamples * 2; // 16-bit mono
    int smplLen = 36 + numLoops * 24;
    int riffLen = 4 + (8 + fmtLen) + (8 + smplLen) + (8 + dataLen);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteBuffer buf = ByteBuffer.allocate(16 + fmtLen + 8 + smplLen + 8 + dataLen + 4);
    buf.order(ByteOrder.LITTLE_ENDIAN);

    buf.put("RIFF".getBytes(StandardCharsets.US_ASCII));
    buf.putInt(riffLen);
    buf.put("WAVE".getBytes(StandardCharsets.US_ASCII));

    // fmt chunk
    buf.put("fmt ".getBytes(StandardCharsets.US_ASCII));
    buf.putInt(fmtLen);
    buf.putShort((short) 1); // PCM
    buf.putShort((short) 1); // mono
    buf.putInt(44100); // sampleRate
    buf.putInt(88200); // byteRate
    buf.putShort((short) 2); // blockAlign
    buf.putShort((short) 16); // bitsPerSample

    // smpl chunk
    buf.put("smpl".getBytes(StandardCharsets.US_ASCII));
    buf.putInt(smplLen);
    buf.putInt(0); // Manufacturer
    buf.putInt(0); // Product
    buf.putInt(22675); // SamplePeriod
    buf.putInt(midiNote); // MIDIUnityNote
    buf.putInt(midiPitchFraction); // MIDIPitchFraction
    buf.putInt(0); // SMPTEFormat
    buf.putInt(0); // SMPTEOffset
    buf.putInt(numLoops); // NumSampleLoops
    buf.putInt(0); // SamplerData
    for (int i = 0; i < numLoops; i++) {
      buf.putInt(i); // CuePointID
      buf.putInt(0); // Type
      buf.putInt(loopStart + i * 10); // Start
      buf.putInt(loopEnd + i * 10); // End
      buf.putInt(0); // Fraction
      buf.putInt(0); // PlayCount
    }

    // data chunk
    buf.put("data".getBytes(StandardCharsets.US_ASCII));
    buf.putInt(dataLen);
    for (int i = 0; i < numSamples; i++) {
      buf.putShort((short) (i * 100));
    }

    out.write(buf.array(), 0, buf.position());
    Files.write(target, out.toByteArray());
    return target.toFile();
  }
}
