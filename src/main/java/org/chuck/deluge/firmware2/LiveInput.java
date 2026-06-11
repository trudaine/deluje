package org.chuck.deluge.firmware2;

/**
 * Mirror of the C firmware's audio-input globals that the voice INPUT_L/R/STEREO oscillator sources
 * read ({@code AudioEngine::i2sRXBufferPos} ring + {@code lineInPluggedIn}/{@code micPluggedIn},
 * consumed in voice.cpp:2232-2360). On desktop the audio driver publishes each render block's
 * captured input here before rendering; {@code null} means no input this block (the sources render
 * silence).
 */
public final class LiveInput {
  private LiveInput() {}

  /**
   * The current render block's input, stereo interleaved Q31 (L,R per sample frame). Length must be
   * at least 2 × the block's numSamples. Null = no live input available.
   */
  public static volatile int[] currentBlock;

  /** C: AudioEngine::lineInPluggedIn. */
  public static volatile boolean lineInPluggedIn;

  /** C: AudioEngine::micPluggedIn. */
  public static volatile boolean micPluggedIn;
}
