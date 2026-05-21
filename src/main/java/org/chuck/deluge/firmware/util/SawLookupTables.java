package org.chuck.deluge.firmware.util;

public class SawLookupTables {

  public static final short[] sawWave1 =
      WavetableLoader.loadTable("/lookuptables/sawWave1.bin", 513);

  public static final short[] sawWave3 =
      WavetableLoader.loadTable("/lookuptables/sawWave3.bin", 513);

  public static final short[] sawWave5 =
      WavetableLoader.loadTable("/lookuptables/sawWave5.bin", 1025);

  public static final short[] sawWave7 =
      WavetableLoader.loadTable("/lookuptables/sawWave7.bin", 1025);

  public static final short[] sawWave9 =
      WavetableLoader.loadTable("/lookuptables/sawWave9.bin", 1025);

  public static final short[] sawWave13 =
      WavetableLoader.loadTable("/lookuptables/sawWave13.bin", 1025);

  public static final short[] sawWave19 =
      WavetableLoader.loadTable("/lookuptables/sawWave19.bin", 2049);

  public static final short[] sawWave27 =
      WavetableLoader.loadTable("/lookuptables/sawWave27.bin", 2049);

  public static final short[] sawWave39 =
      WavetableLoader.loadTable("/lookuptables/sawWave39.bin", 2049);

  public static final short[] sawWave53 =
      WavetableLoader.loadTable("/lookuptables/sawWave53.bin", 2049);

  public static final short[] sawWave76 =
      WavetableLoader.loadTable("/lookuptables/sawWave76.bin", 2049);

  public static final short[] sawWave109 =
      WavetableLoader.loadTable("/lookuptables/sawWave109.bin", 2049);

  public static final short[] sawWave153 =
      WavetableLoader.loadTable("/lookuptables/sawWave153.bin", 2049);

  public static final short[] sawWave215 =
      WavetableLoader.loadTable("/lookuptables/sawWave215.bin", 2049);

  public static final short[][] sawWaveTables = {
    null,
    null,
    null,
    null,
    null,
    null,
    sawWave215,
    sawWave153,
    sawWave109,
    sawWave76,
    sawWave53,
    sawWave39,
    sawWave27,
    sawWave19,
    sawWave13,
    sawWave9,
    sawWave7,
    sawWave5,
    sawWave3,
    sawWave1
  };
}
