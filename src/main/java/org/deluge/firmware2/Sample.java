package org.deluge.firmware2;

/**
 * In-RAM sample for the firmware2 sample engine (Phase A of docs/FIRMWARE2_SAMPLE_ENGINE_PLAN.md).
 *
 * <p>Mirrors the metadata fields the C {@code Sample}/{@code AudioFile} expose to the time-stretch
 * / playback DSP ({@code model/sample/sample.h}). The C streams audio from the SD card in {@code
 * Cluster}s; on desktop the whole decoded PCM lives in {@link #data} (interleaved, {@code
 * [frame*numChannels + ch]}), which is the documented Phase-A adapter (NOT a line-for-line port of
 * the cluster layer). All DSP that reads samples goes through a reader over {@link #data}.
 */
public class Sample {
  public String fileName;

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

  /** C: sample.h:122 - root note of the sample, -1.0f means none/unset. */
  public float midiNoteFromFile = -1.0f;

  /**
   * Phase-A adapter: decoded interleaved PCM ({@code [frame*numChannels + ch]}); replaces clusters.
   */
  public int[] data;

  /**
   * Convert from the firmware model {@code Sample} (float[] PCM) to a fw2 Sample (int[] PCM, Q31).
   * This is the bridge adapter — not in the C (the C reads clusters, not floats).
   */
  public static Sample fromFirmwareSample(org.deluge.firmware.model.sample.Sample modelSample) {
    if (modelSample == null || modelSample.data == null) return null;
    Sample s = new Sample();
    s.fileName = modelSample.fileName;
    s.numChannels = modelSample.numChannels;
    s.byteDepth = modelSample.byteDepth;
    s.sampleRate = (int) modelSample.sampleRate;
    s.audioDataStartPosBytes = 0;
    s.lengthInSamples = modelSample.getNumSamples();
    s.audioDataLengthBytes = s.lengthInSamples * s.numChannels * s.byteDepth;
    s.fileLoopStartSamples = modelSample.fileLoopStartSamples;
    s.fileLoopEndSamples = modelSample.fileLoopEndSamples;
    s.midiNoteFromFile = modelSample.midiNoteFromFile;

    int n = modelSample.data.length;
    s.data = new int[n];
    for (int i = 0; i < n; i++) {
      s.data[i] = (int) (modelSample.data[i] * 2147483647.0f);
    }
    return s;
  }

  /**
   * Faithful port of {@code Sample::getAveragesForCrossfade} (sample.cpp:727-833): the
   * moving-average similarity metric the time-stretch hop search compares. Computes {@code
   * kNumMovingAverages} totals, each the sum (over {@code lengthToAverageEach} frames, all
   * channels) of the samples' top 16 bits ({@code >> 16}). The metric stays int16 even under option
   * (b) full-precision audio, so the crossfade-point SELECTION matches the C. The C cluster walk is
   * flattened to direct in-RAM indexing.
   *
   * @return false if the window would fall outside the audio data (C's early-out cases)
   */
  public boolean getAveragesForCrossfade(
      int[] totals,
      int startBytePos,
      int crossfadeLengthSamples,
      int playDirection,
      int lengthToAverageEach) {
    int bytesPerSample = byteDepth * numChannels;
    int len = (int) audioDataLengthBytes;

    int startSamplePos =
        Integer.divideUnsigned(startBytePos - audioDataStartPosBytes, bytesPerSample); // C:740
    int halfCrossfadeLengthSamples = crossfadeLengthSamples >> 1; // C:742
    int samplePosMidCrossfade =
        startSamplePos + halfCrossfadeLengthSamples * playDirection; // C:744
    int readSample =
        samplePosMidCrossfade
            - ((lengthToAverageEach * TimeStretcher.K_NUM_MOVING_AVERAGES) >> 1)
                * playDirection; // C:746
    int halfCrossfadeLengthBytes = halfCrossfadeLengthSamples * bytesPerSample; // C:749
    int readByte = readSample * bytesPerSample + audioDataStartPosBytes; // C:751

    if (playDirection == 1) { // C:753-760
      if (readByte < audioDataStartPosBytes + halfCrossfadeLengthBytes) {
        return false;
      } else if (readByte >= audioDataStartPosBytes + len - halfCrossfadeLengthBytes) {
        return false;
      }
    }

    int endReadByte =
        readByte
            + lengthToAverageEach
                * TimeStretcher.K_NUM_MOVING_AVERAGES
                * bytesPerSample
                * playDirection; // C:762
    if (endReadByte < audioDataStartPosBytes - 1
        || endReadByte > audioDataStartPosBytes + len) { // C:765
      return false;
    }

    for (int i = 0; i < TimeStretcher.K_NUM_MOVING_AVERAGES; i++) { // C:770
      totals[i] = 0;
      for (int j = 0; j < lengthToAverageEach; j++) { // C:780-829 (cluster walk flattened)
        int frame = (readByte - audioDataStartPosBytes) / bytesPerSample;
        int base = frame * numChannels;
        totals[i] += data[base] >> 16; // C:816
        if (numChannels == 2) {
          totals[i] += data[base + 1] >> 16; // C:818
        }
        readByte += bytesPerSample * playDirection; // C:821
      }
    }
    return true;
  }
}
