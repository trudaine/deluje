package org.deluge.firmware.storage.audio;

import fr.delthas.javamp3.Sound;
import java.io.*;
import java.nio.*;
import org.deluge.firmware.model.sample.Sample;

public class AudioFileReader {
  public static Sample readSample(String path) throws IOException {
    File file = new File(path);
    if (!file.exists()) return null;

    if (file.getName().toLowerCase().endsWith(".mp3")) {
      try {
        return readMP3Sample(path);
      } catch (Exception e) {
        throw new IOException("Failed to decode MP3 file: " + path, e);
      }
    }

    Sample sample = new Sample();
    sample.fileName = file.getName();

    try (FileInputStream fis = new FileInputStream(file)) {
      DataInputStream dis = new java.io.DataInputStream(fis);

      // RIFF header
      int riff = Integer.reverseBytes(dis.readInt());
      if (riff != 0x46464952) { // "RIFF" in little-endian as seen by big-endian read
        System.out.println("Invalid RIFF: " + Integer.toHexString(riff));
        return null;
      }
      dis.readInt(); // size
      int wave = Integer.reverseBytes(dis.readInt());
      if (wave != 0x45564157) { // "WAVE"
        System.out.println("Invalid WAVE: " + Integer.toHexString(wave));
        return null;
      }

      int audioFormat = 1; // 1 = PCM int, 3 = IEEE float (WAVE_FORMAT_IEEE_FLOAT)

      while (fis.available() >= 8) {
        int chunkId = dis.readInt(); // Big-endian read of little-endian data
        int chunkLen = Integer.reverseBytes(dis.readInt());

        if (chunkId == 0x666d7420) { // 'fmt ' (little-endian on disk, big-endian in int)
          short format = Short.reverseBytes(dis.readShort());
          audioFormat = format & 0xFFFF;
          sample.numChannels = Short.reverseBytes(dis.readShort());
          sample.sampleRate = Integer.reverseBytes(dis.readInt());
          dis.readInt(); // byte rate
          dis.readShort(); // block align
          sample.byteDepth = Short.reverseBytes(dis.readShort()) / 8;
          if (chunkLen > 16) dis.skipBytes(chunkLen - 16);
        } else if (chunkId == 0x64617461) { // 'data'
          byte[] rawData = new byte[chunkLen];
          dis.readFully(rawData);
          ByteBuffer bb = ByteBuffer.wrap(rawData).order(ByteOrder.LITTLE_ENDIAN);
          int numSamples = chunkLen / (sample.numChannels * sample.byteDepth);
          sample.data = new float[numSamples * sample.numChannels];
          for (int i = 0; i < sample.data.length; i++) {
            if (sample.byteDepth == 2) {
              sample.data[i] = bb.getShort() / 32768.0f;
            } else if (sample.byteDepth == 3) {
              // 24-bit little-endian, sign-extended to int32.
              int b0 = bb.get() & 0xFF, b1 = bb.get() & 0xFF, b2 = bb.get() & 0xFF;
              int v = b0 | (b1 << 8) | (b2 << 16);
              if ((v & 0x800000) != 0) v |= 0xFF000000; // sign extend
              sample.data[i] = v / 8388608.0f;
            } else if (sample.byteDepth == 4) {
              // 32-bit: IEEE float (format 3) or PCM int32 (format 1).
              sample.data[i] = (audioFormat == 3) ? bb.getFloat() : bb.getInt() / 2147483648.0f;
            } else if (sample.byteDepth == 1) {
              sample.data[i] = (bb.get() & 0xFF) / 128.0f - 1.0f; // 8-bit WAV is unsigned
            }
          }
        } else if (chunkId == 0x736d706c) { // 'smpl'
          dis.skipBytes(12);
          int midiNote = Integer.reverseBytes(dis.readInt());
          int midiPitchFraction = Integer.reverseBytes(dis.readInt());
          if (midiNote < 128) {
            sample.midiNoteFromFile = midiNote + (midiPitchFraction / 4294967296.0f);
          }
          dis.skipBytes(12);
          int numLoops = Integer.reverseBytes(dis.readInt());
          dis.readInt(); // sampler data
          if (numLoops > 0) {
            dis.readInt(); // loop id
            dis.readInt(); // type
            sample.fileLoopStartSamples = Integer.reverseBytes(dis.readInt());
            sample.fileLoopEndSamples = Integer.reverseBytes(dis.readInt());
            dis.skipBytes(chunkLen - 44);
          } else {
            dis.skipBytes(chunkLen - 36);
          }
        } else if (chunkId == 0x696e7374) { // 'inst'
          int midiNote = dis.readByte() & 0xFF;
          int fineTune = dis.readByte();
          if (midiNote < 128) {
            sample.midiNoteFromFile = midiNote - fineTune * 0.01f;
          }
          dis.skipBytes(chunkLen - 2);
        } else {
          dis.skipBytes(chunkLen);
        }

        if (chunkLen % 2 != 0 && fis.available() > 0) dis.readByte(); // padding
      }
    }
    return sample;
  }

  private static Sample readMP3Sample(String path) throws Exception {
    File file = new File(path);
    if (!file.exists()) return null;

    Sample sample = new Sample();
    sample.fileName = file.getName();

    try (FileInputStream fis = new FileInputStream(file)) {
      Sound sound = new Sound(fis);
      javax.sound.sampled.AudioFormat format = sound.getAudioFormat();
      sample.sampleRate = (int) format.getSampleRate();
      sample.numChannels = format.getChannels();
      sample.byteDepth = format.getSampleSizeInBits() / 8;

      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      byte[] readBuf = new byte[65536];
      int read;
      while ((read = sound.read(readBuf)) != -1) {
        baos.write(readBuf, 0, read);
      }
      byte[] pcmBytes = baos.toByteArray();
      sound.close();

      int bytesPerSample = format.getSampleSizeInBits() / 8;
      int numSamples = pcmBytes.length / bytesPerSample;
      sample.data = new float[numSamples];

      ByteBuffer buf =
          ByteBuffer.wrap(pcmBytes)
              .order(format.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);

      if (format.getSampleSizeInBits() == 16) {
        for (int i = 0; i < numSamples; i++) {
          sample.data[i] = buf.getShort() / 32768.0f;
        }
      } else if (format.getSampleSizeInBits() == 8) {
        for (int i = 0; i < numSamples; i++) {
          sample.data[i] = (buf.get() & 0xFF) / 255.0f;
        }
      }
    }
    return sample;
  }
}
