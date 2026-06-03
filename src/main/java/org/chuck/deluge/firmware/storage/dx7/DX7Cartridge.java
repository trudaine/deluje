package org.chuck.deluge.firmware.storage.dx7;

public class DX7Cartridge {
  public static final int SYSEX_SIZE = 4104;
  public static final int SMALL_SYSEX_SIZE = 163;
  private byte[] voiceData = new byte[SYSEX_SIZE];
  private boolean isCartridge;

  public String load(byte[] stream) {
    if (stream.length < 163) return "FILE_TOO_SMALL";
    if (stream[0] != (byte) 0xF0) return "NO_SYSEX_START";

    int end = -1;
    for (int i = 0; i < stream.length; i++) {
      if (stream[i] == (byte) 0xF7) {
        end = i;
        break;
      }
    }
    if (end == -1) return "NO_SYSEX_END";

    int msgSize = end + 1;
    if (msgSize != SYSEX_SIZE && msgSize != SMALL_SYSEX_SIZE) return "INVALID_LEN";

    System.arraycopy(stream, 0, voiceData, 0, msgSize);
    isCartridge = voiceData[3] == 9;

    return null; // OK
  }

  public int numPatches() {
    return isCartridge ? 32 : 1;
  }

  public byte[] unpackProgram(int idx) {
    byte[] unpackPgm = new byte[155];
    if (!isCartridge) {
      System.arraycopy(voiceData, 6, unpackPgm, 0, 155);
      return unpackPgm;
    }

    int bulkOffset = 6 + (idx * 128);
    for (int op = 0; op < 6; op++) {
      int opBulk = bulkOffset + op * 17;
      int opUnpack = op * 21;
      for (int i = 0; i < 10; i++) {
        unpackPgm[opUnpack + i] = (byte) (voiceData[opBulk + i] & 0x7F);
      }
      // Packed bit-fields are stored low-bits-first (firmware unpackBits<a,b>): the first field
      // occupies the least-significant bits. Reading from the high end corrupts every operator.
      byte b11 = voiceData[opBulk + 11];
      unpackPgm[opUnpack + 11] = (byte) (b11 & 3); // left curve
      unpackPgm[opUnpack + 12] = (byte) ((b11 >> 2) & 3); // right curve

      byte b12 = voiceData[opBulk + 12];
      unpackPgm[opUnpack + 13] = (byte) (b12 & 7); // rate scaling
      unpackPgm[opUnpack + 20] = (byte) ((b12 >> 3) & 0xF); // detune

      byte b13 = voiceData[opBulk + 13];
      unpackPgm[opUnpack + 14] = (byte) (b13 & 3); // amp mod sensitivity
      unpackPgm[opUnpack + 15] = (byte) ((b13 >> 2) & 7); // key velocity sensitivity

      unpackPgm[opUnpack + 16] = (byte) (voiceData[opBulk + 14] & 0x7F); // output level

      byte b15 = voiceData[opBulk + 15];
      unpackPgm[opUnpack + 17] = (byte) (b15 & 1); // osc mode (ratio/fixed)
      unpackPgm[opUnpack + 18] = (byte) ((b15 >> 1) & 0x1F); // freq coarse

      unpackPgm[opUnpack + 19] = (byte) (voiceData[opBulk + 16] & 0x7F);
    }

    for (int i = 0; i < 8; i++) {
      unpackPgm[126 + i] = (byte) (voiceData[bulkOffset + 102 + i] & 0x7F);
    }

    unpackPgm[134] = (byte) (voiceData[bulkOffset + 110] & 0x1F); // algo

    byte b111 = voiceData[bulkOffset + 111];
    unpackPgm[135] = (byte) (b111 & 7); // feedback
    unpackPgm[136] = (byte) ((b111 >> 3) & 1); // oks (osc key sync)

    unpackPgm[137] = (byte) (voiceData[bulkOffset + 112] & 0x7F);
    unpackPgm[138] = (byte) (voiceData[bulkOffset + 113] & 0x7F);
    unpackPgm[139] = (byte) (voiceData[bulkOffset + 114] & 0x7F);
    unpackPgm[140] = (byte) (voiceData[bulkOffset + 115] & 0x7F);

    byte b116 = voiceData[bulkOffset + 116];
    unpackPgm[141] = (byte) (b116 & 1); // lfo key sync
    unpackPgm[142] = (byte) ((b116 >> 1) & 0xF); // lfo waveform
    unpackPgm[143] = (byte) ((b116 >> 5) & 3); // lfo pitch mod sensitivity

    unpackPgm[144] = (byte) (voiceData[bulkOffset + 117] & 0x7F);
    for (int i = 0; i < 10; i++) {
      unpackPgm[145 + i] = (byte) (voiceData[bulkOffset + 118 + i] & 0x7F);
    }

    return unpackPgm;
  }
}
