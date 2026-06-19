package org.deluge.shadow.audio;

/**
 * A lightweight shadow containing the flat connection matrix for the DX7 FM algorithms. Implemented
 * in pure Java to completely decouple the Deluge UI from the heavy native ChucK VM.
 */
public class Dx7EngineLookupTables {
  // Flat connection array for 32 algorithms x 6 operators
  public static final int[] ALGORITHMS = new int[2048];
}
