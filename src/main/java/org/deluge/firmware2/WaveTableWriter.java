package org.deluge.firmware2;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Utility to write a custom WaveTable or raw sample buffer back to a standard 16-bit mono PCM WAV
 * file, fully compatible with the Deluge audio engine.
 */
public class WaveTableWriter {

  public static void writeWavetable(float[] samples, String outputPath) throws IOException {
    File file = new File(outputPath);
    File parent = file.getParentFile();
    if (parent != null && !parent.exists()) {
      parent.mkdirs();
    }

    int numSamples = samples.length;
    int dataSize = numSamples * 2; // 16-bit = 2 bytes per sample

    byte[] header = createWavHeader(dataSize);
    byte[] data = new byte[dataSize];

    ByteBuffer dataBB = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    for (float sample : samples) {
      short pcmVal = (short) Math.max(-32768, Math.min(32767, sample * 32767.0f));
      dataBB.putShort(pcmVal);
    }

    try (FileOutputStream fos = new FileOutputStream(file)) {
      fos.write(header);
      fos.write(data);
    }
  }

  private static byte[] createWavHeader(int dataSize) {
    byte[] header = new byte[44];
    ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);

    bb.putInt(0, 0x46464952); // "RIFF"
    bb.putInt(4, 36 + dataSize); // ChunkSize
    bb.putInt(8, 0x45564157); // "WAVE"
    bb.putInt(12, 0x20746d66); // "fmt "
    bb.putInt(16, 16); // Subchunk1Size
    bb.putShort(20, (short) 1); // AudioFormat (1 = PCM)
    bb.putShort(22, (short) 1); // NumChannels (1 = Mono)
    bb.putInt(24, 44100); // SampleRate
    bb.putInt(28, 44100 * 2); // ByteRate
    bb.putShort(32, (short) 2); // BlockAlign
    bb.putShort(34, (short) 16); // BitsPerSample (16 bits)
    bb.putInt(36, 0x61746164); // "data"
    bb.putInt(40, dataSize); // Subchunk2Size

    return header;
  }
}
