package org.chuck.deluge.engine.dsp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Pure Java implementation of a WAV recorder. Records 16-bit stereo PCM to a RIFF/WAV file using
 * only java.io, no javax.sound dependency.
 */
public class NativeWavExporter {
  private final float sampleRate;
  private File outputFile;
  private boolean active = false;
  private java.io.ByteArrayOutputStream buffer;

  public NativeWavExporter(float sampleRate) {
    this.sampleRate = sampleRate;
  }

  public void start(String path) {
    this.outputFile = new File(path);
    this.buffer = new java.io.ByteArrayOutputStream();
    this.active = true;
    System.out.println("[NativeWavExporter] Recording to: " + path);
  }

  public void record(float left, float right) {
    if (!active) return;

    short sL = (short) (Math.max(-1f, Math.min(1f, left)) * 32767f);
    short sR = (short) (Math.max(-1f, Math.min(1f, right)) * 32767f);

    byte[] bytes = new byte[4];
    bytes[0] = (byte) (sL & 0xFF);
    bytes[1] = (byte) ((sL >> 8) & 0xFF);
    bytes[2] = (byte) (sR & 0xFF);
    bytes[3] = (byte) ((sR >> 8) & 0xFF);

    buffer.writeBytes(bytes);
  }

  public void stop() {
    if (!active) return;
    active = false;
    try {
      byte[] data = buffer.toByteArray();
      long dataSize = data.length; // already 16-bit stereo PCM bytes
      long fileSize = 36 + dataSize;
      long byteRate = (long) sampleRate * 2 * 2; // sampleRate * channels * bytesPerSample

      ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
      header.put("RIFF".getBytes());
      header.putInt((int) fileSize);
      header.put("WAVE".getBytes());
      header.put("fmt ".getBytes());
      header.putInt(16); // chunk size
      header.putShort((short) 1); // PCM format
      header.putShort((short) 2); // channels
      header.putInt((int) sampleRate);
      header.putInt((int) byteRate);
      header.putShort((short) 4); // block align
      header.putShort((short) 16); // bits per sample
      header.put("data".getBytes());
      header.putInt((int) dataSize);

      try (FileOutputStream fos = new FileOutputStream(outputFile)) {
        fos.write(header.array());
        fos.write(data);
      }
      System.out.println(
          "[NativeWavExporter] Saved "
              + data.length / 4
              + " frames to "
              + outputFile.getAbsolutePath());
    } catch (IOException e) {
      System.err.println("[NativeWavExporter] Error saving WAV: " + e.getMessage());
    }
  }

  public boolean isActive() {
    return active;
  }
}
