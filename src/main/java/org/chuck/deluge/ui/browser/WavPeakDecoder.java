package org.chuck.deluge.ui.browser;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decodes the header of a WAV file and computes a low-resolution peak envelope array for rendering
 * in a JavaFX Canvas without keeping the whole file in memory.
 */
public class WavPeakDecoder {

  /** Basic metadata extracted from a WAV header. */
  public static class WavInfo {
    public int numChannels;
    public int sampleRate;
    public int bitDepth;
    public long numSamples;
    public float[] peaks; // Downsampled peak envelope [-1.0, 1.0]

    public double getDurationMs() {
      if (sampleRate == 0) return 0;
      return (numSamples / (double) sampleRate) * 1000.0;
    }
  }

  /**
   * Decodes a WAV file and returns its metadata and a peak envelope array of the requested size.
   * Assumes standard 16-bit or 24-bit PCM.
   */
  public static WavInfo decode(File file, int peakArraySize) throws Exception {
    WavInfo info = new WavInfo();

    try (FileInputStream fis = new FileInputStream(file)) {
      byte[] header = new byte[44];
      if (fis.read(header) < 44) throw new Exception("File too small to be a WAV");

      ByteBuffer bb = ByteBuffer.wrap(header);
      bb.order(ByteOrder.LITTLE_ENDIAN);

      // Check "RIFF"
      if (header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F') {
        throw new Exception("Not a RIFF file");
      }

      // Check "WAVE"
      if (header[8] != 'W' || header[9] != 'A' || header[10] != 'V' || header[11] != 'E') {
        throw new Exception("Not a WAVE file");
      }

      // Check format (1 = PCM)
      int audioFormat = bb.getShort(20);
      if (audioFormat != 1 && audioFormat != 3) {
        // We can only reliably peak-decode standard PCM or IEEE float right now
        // For MVP, assume it's PCM enough to get lengths
      }

      info.numChannels = bb.getShort(22);
      info.sampleRate = bb.getInt(24);
      info.bitDepth = bb.getShort(34);

      // Find "data" chunk
      int dataSize = bb.getInt(40);
      if (header[36] != 'd' || header[37] != 'a' || header[38] != 't' || header[39] != 'a') {
        // Sometimes there are other chunks before 'data'. We'd need a real chunk parser here.
        // For MVP, if it's not standard 44-byte header, we just guess the remaining size.
        dataSize = (int) file.length() - 44;
      }

      int bytesPerSample = info.bitDepth / 8;
      if (bytesPerSample == 0) bytesPerSample = 2; // fallback
      info.numSamples = dataSize / (info.numChannels * bytesPerSample);

      // Downsample to peaks
      info.peaks = new float[peakArraySize];
      if (info.numSamples == 0) return info;

      long samplesPerPixel = info.numSamples / peakArraySize;
      if (samplesPerPixel == 0) samplesPerPixel = 1;

      byte[] frame = new byte[info.numChannels * bytesPerSample * 1024]; // Read in chunks
      int peakIdx = 0;
      long sampleCount = 0;
      float maxPeak = 0.0f;

      int bytesRead;
      while ((bytesRead = fis.read(frame)) != -1 && peakIdx < peakArraySize) {
        for (int i = 0; i < bytesRead; i += (info.numChannels * bytesPerSample)) {

          float val = 0.0f;
          if (info.bitDepth == 16) {
            short s = (short) ((frame[i] & 0xFF) | (frame[i + 1] << 8));
            val = s / 32768.0f;
          } else if (info.bitDepth == 24) {
            int s = ((frame[i] & 0xFF) | ((frame[i + 1] & 0xFF) << 8) | (frame[i + 2] << 16));
            if ((s & 0x800000) != 0) s |= 0xFF000000; // Sign extend
            val = s / 8388608.0f;
          }

          if (Math.abs(val) > maxPeak) {
            maxPeak = Math.abs(val);
          }

          sampleCount++;
          if (sampleCount >= samplesPerPixel) {
            info.peaks[peakIdx++] = maxPeak;
            maxPeak = 0.0f;
            sampleCount = 0;
            if (peakIdx >= peakArraySize) break;
          }
        }
      }
    }

    return info;
  }
}
