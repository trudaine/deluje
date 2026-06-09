package org.chuck.deluge.firmware2;

/**
 * In-RAM sample for the firmware2 sample engine (Phase A of docs/FIRMWARE2_SAMPLE_ENGINE_PLAN.md).
 *
 * <p>Mirrors the metadata fields the C {@code Sample}/{@code AudioFile} expose to the time-stretch /
 * playback DSP ({@code model/sample/sample.h}). The C streams audio from the SD card in {@code Cluster}s;
 * on desktop the whole decoded PCM lives in {@link #data} (interleaved, {@code [frame*numChannels + ch]}),
 * which is the documented Phase-A adapter (NOT a line-for-line port of the cluster layer). All DSP that
 * reads samples goes through a reader over {@link #data}.
 */
public class Sample {
  /** C: AudioFile::numChannels */
  public int numChannels;
  /** C: sample.h:109 */
  public int byteDepth;
  /** C: sample.h:110 */
  public int sampleRate = 44100;
  /** C: sample.h:111 — offset from the start of the WAV file (uint32). */
  public int audioDataStartPosBytes;
  /** C: sample.h:112 (uint64). */
  public long audioDataLengthBytes;
  /** C: sample.h:116 (uint64). */
  public long lengthInSamples;
  /** C: sample.h:119 */
  public int fileLoopStartSamples;
  /** C: sample.h:120 */
  public int fileLoopEndSamples;

  /** Phase-A adapter: decoded interleaved PCM ({@code [frame*numChannels + ch]}); replaces clusters. */
  public int[] data;
}
