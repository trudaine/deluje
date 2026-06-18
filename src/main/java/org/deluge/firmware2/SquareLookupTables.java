package org.deluge.firmware2;

public class SquareLookupTables {

  public static final short[] squareWave1 =
      WavetableLoader.loadTable("/lookuptables/squareWave1.bin", 513);

  public static final short[] squareWave3 =
      WavetableLoader.loadTable("/lookuptables/squareWave3.bin", 513);

  public static final short[] squareWave5 =
      WavetableLoader.loadTable("/lookuptables/squareWave5.bin", 1025);

  public static final short[] squareWave7 =
      WavetableLoader.loadTable("/lookuptables/squareWave7.bin", 1025);

  public static final short[] squareWave9 =
      WavetableLoader.loadTable("/lookuptables/squareWave9.bin", 1025);

  public static final short[] squareWave13 =
      WavetableLoader.loadTable("/lookuptables/squareWave13.bin", 1025);

  public static final short[] squareWave19 =
      WavetableLoader.loadTable("/lookuptables/squareWave19.bin", 2049);

  public static final short[] squareWave27 =
      WavetableLoader.loadTable("/lookuptables/squareWave27.bin", 2049);

  public static final short[] squareWave39 =
      WavetableLoader.loadTable("/lookuptables/squareWave39.bin", 2049);

  public static final short[] squareWave53 =
      WavetableLoader.loadTable("/lookuptables/squareWave53.bin", 2049);

  public static final short[] squareWave76 =
      WavetableLoader.loadTable("/lookuptables/squareWave76.bin", 2049);

  public static final short[] squareWave109 =
      WavetableLoader.loadTable("/lookuptables/squareWave109.bin", 2049);

  public static final short[] squareWave153 =
      WavetableLoader.loadTable("/lookuptables/squareWave153.bin", 2049);

  public static final short[] squareWave215 =
      WavetableLoader.loadTable("/lookuptables/squareWave215.bin", 2049);

  public static final short[][] squareWaveTables = {
    null,
    null,
    null,
    null,
    null,
    null,
    squareWave215,
    squareWave153,
    squareWave109,
    squareWave76,
    squareWave53,
    squareWave39,
    squareWave27,
    squareWave19,
    squareWave13,
    squareWave9,
    squareWave7,
    squareWave5,
    squareWave3,
    squareWave1
  };
}
