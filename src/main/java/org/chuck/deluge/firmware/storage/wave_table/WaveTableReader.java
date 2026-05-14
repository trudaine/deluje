package org.chuck.deluge.firmware.storage.wave_table;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class WaveTableReader {
  public static void readWavetable(WaveTable waveTable, String path) throws IOException {
    File file = new File(path);
    if (!file.exists()) return;

    try (FileInputStream fis = new FileInputStream(file)) {
      byte[] header = new byte[44];
      fis.read(header);

      ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
      // Simple WAV check
      if (bb.getInt(0) != 0x46464952) return; // "RIFF"

      int dataSize = bb.getInt(40);
      int cycleSize = 2048; // Assume default if not metadata present

      waveTable.setup(cycleSize, dataSize / 2); // 16-bit mono

      byte[] data = new byte[dataSize];
      fis.read(data);
      ByteBuffer dataBB = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

      float[] samples = new float[dataSize / 2];
      for (int i = 0; i < samples.length; i++) {
        samples[i] = dataBB.getShort() / 32768.0f;
      }

      WavetableGenerator.generateBands(waveTable, samples);
    }
  }
}
