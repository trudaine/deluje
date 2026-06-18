package org.chuck.core;

/**
 * A lightweight shadow representing a DAC audio output channel. Implemented in pure Java to
 * completely decouple the Deluge UI from the heavy native ChucK VM.
 */
public class DacChannel {

  public float[] getVisBuffer() {
    return new float[512]; // Silent visualizer buffer
  }
}
