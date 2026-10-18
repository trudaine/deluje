package org.deluge.firmware2;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

/**
 * WAV wavetable reader aligned with C++ {@code AudioFile::loadFile} ({@code audio_file.cpp:34-279})
 * for {@code AudioFileType::WAVETABLE}.
 */
public class WaveTableReader {

  private static final int RIFF_MAGIC = 0x46464952; // "RIFF"
  private static final int WAVE_MAGIC = 0x45564157; // "WAVE"
  private static final int CHUNK_FMT = 0x20746D66; // "fmt "
  private static final int CHUNK_DATA = 0x61746164; // "data"
  private static final int CHUNK_CLM = 0x206D6C63; // "clm "
  private static final int WAV_FORMAT_PCM = 1;
  private static final int WAV_FORMAT_FLOAT = 3;
  private static final int DEFAULT_WAVETABLE_CYCLE_SIZE = 2048;

  public static void readWavetable(WaveTable waveTable, String path) throws IOException {
    File file = new File(path);
    if (!file.exists()) return;

    byte[] allBytes = Files.readAllBytes(file.toPath());
    if (allBytes.length < 12) return;

    ByteBuffer bb = ByteBuffer.wrap(allBytes).order(ByteOrder.LITTLE_ENDIAN);
    if (bb.getInt(0) != RIFF_MAGIC || bb.getInt(8) != WAVE_MAGIC) {
      return;
    }

    // C audio_file.cpp:48-89 — walk all RIFF chunks to find fmt, data, and optional Serum "clm ".
    boolean foundFmt = false;
    boolean foundData = false;
    int audioFormat = 0;
    int numChannels = 0;
    int bitsPerSample = 0;
    int cycleSize = DEFAULT_WAVETABLE_CYCLE_SIZE;
    int dataOffset = 0;
    int dataLength = 0;

    int pos = 12;
    while (pos <= allBytes.length - 8) {
      int chunkName = bb.getInt(pos);
      int chunkLen = bb.getInt(pos + 4);
      if (chunkLen < 0) {
        break;
      }
      int chunkDataStart = pos + 8;
      int availableInFile = allBytes.length - chunkDataStart;
      int usableLen = Math.min(chunkLen, availableInFile);

      switch (chunkName) {
        case CHUNK_FMT -> {
          // C audio_file.cpp:131-173
          if (usableLen >= 16) {
            audioFormat = bb.getShort(chunkDataStart) & 0xFFFF;
            numChannels = bb.getShort(chunkDataStart + 2) & 0xFFFF;
            bitsPerSample = bb.getShort(chunkDataStart + 14) & 0xFFFF;
            foundFmt = true;
          }
        }
        case CHUNK_DATA -> {
          // C audio_file.cpp:96-100
          dataOffset = chunkDataStart;
          dataLength = usableLen;
          foundData = true;
        }
        case CHUNK_CLM -> {
          // C audio_file.cpp:259-277 — Serum wavetable tag "<!>NNNN" (e.g. "<!>2048").
          if (usableLen >= 7
              && allBytes[chunkDataStart] == '<'
              && allBytes[chunkDataStart + 1] == '!'
              && allBytes[chunkDataStart + 2] == '>') {
            int parsed = parseAsciiUInt(allBytes, chunkDataStart + 3, chunkDataStart + 7);
            if (parsed >= 1) {
              cycleSize = parsed;
            }
          }
        }
        default -> {}
      }

      // C audio_file.cpp:81-83 — odd-sized RIFF chunks carry 1 pad byte after their payload.
      long paddedLen = ((Integer.toUnsignedLong(chunkLen) + 1L) & ~1L);
      long nextPos = chunkDataStart + paddedLen;
      if (nextPos <= pos || nextPos > allBytes.length) {
        break;
      }
      pos = (int) nextPos;
    }

    if (!foundFmt || !foundData) {
      return;
    }

    // C audio_file.cpp:107-110 — stereo files are never loadable as a WaveTable.
    if (numChannels != 1) {
      return;
    }

    // C audio_file.cpp:143-166 — validate PCM (8/16/24/32-bit) or IEEE float (32-bit).
    if (audioFormat == WAV_FORMAT_PCM) {
      if (bitsPerSample != 8 && bitsPerSample != 16 && bitsPerSample != 24 && bitsPerSample != 32) {
        return;
      }
    } else if (audioFormat == WAV_FORMAT_FLOAT) {
      if (bitsPerSample != 32) {
        return;
      }
    } else {
      return;
    }

    int byteDepth = bitsPerSample >> 3;
    int numSamples = dataLength / byteDepth;
    if (numSamples < WaveTable.kWavetableMinCycleSize) {
      return;
    }

    int[] q31Samples =
        decodeSamplesToQ31(allBytes, bb, dataOffset, numSamples, audioFormat, byteDepth);

    waveTable.bands.clear();
    waveTable.setup(cycleSize, q31Samples.length);
    WavetableGenerator.generateBands(waveTable, q31Samples);
  }

  // C functions.cpp:654-666 — memToUIntOrError(mem, memEnd): returns -1 on any non-digit byte.
  private static int parseAsciiUInt(byte[] bytes, int start, int end) {
    long number = 0;
    for (int i = start; i < end; i++) {
      int b = bytes[i] & 0xFF;
      if (b < '0' || b > '9') {
        return -1;
      }
      number = number * 10L + (b - '0');
      if (number > Integer.MAX_VALUE) {
        return -1;
      }
    }
    return (int) number;
  }

  // C wave_table.cpp:421-439 (CONVERT_AND_STORE_SAMPLE) — left-aligned q31 value32 for each sample.
  public static int[] decodeSamplesToQ31(
      byte[] bytes, ByteBuffer bb, int dataOffset, int numSamples, int audioFormat, int byteDepth) {
    int[] q31Samples = new int[numSamples];
    if (audioFormat == WAV_FORMAT_FLOAT) {
      for (int i = 0; i < numSamples; i++) {
        q31Samples[i] = q31FromFloat(bb.getFloat(dataOffset + i * 4));
      }
      return q31Samples;
    }

    switch (byteDepth) {
      case 1 -> {
        // C wave_table.cpp:430-433 — RawDataFormat::UNSIGNED_8: mask top byte and add (1 << 31).
        for (int i = 0; i < numSamples; i++) {
          q31Samples[i] = (bytes[dataOffset + i] << 24) + (1 << 31);
        }
      }
      case 2 -> {
        for (int i = 0; i < numSamples; i++) {
          q31Samples[i] = bb.getShort(dataOffset + i * 2) << 16;
        }
      }
      case 3 -> {
        for (int i = 0; i < numSamples; i++) {
          int o = dataOffset + i * 3;
          q31Samples[i] =
              ((bytes[o] & 0xFF) << 8) | ((bytes[o + 1] & 0xFF) << 16) | (bytes[o + 2] << 24);
        }
      }
      case 4 -> {
        for (int i = 0; i < numSamples; i++) {
          q31Samples[i] = bb.getInt(dataOffset + i * 4);
        }
      }
      default -> {}
    }
    return q31Samples;
  }

  // C fixedpoint.h:224-252 — q31_from_float, saturating at |value| >= 1.0f.
  public static int q31FromFloat(float value) {
    if (Float.isNaN(value)) {
      return 0;
    }
    int bits = Float.floatToRawIntBits(value);
    boolean negative = (bits & 0x80000000) != 0;
    int exponent = ((bits >>> 23) & 0xFF) - 127;
    if (exponent >= 0) {
      return negative ? Integer.MIN_VALUE : Integer.MAX_VALUE;
    }
    int shift = -exponent;
    if (shift >= 32) {
      return 0;
    }
    int mantissa = (bits << 8) | 0x80000000;
    int outputValue = mantissa >>> shift;
    return negative ? -outputValue : outputValue;
  }
}
