package org.chuck.deluge.firmware2;

/**
 * Faithful port of {@code processing/live/live_input_buffer.{cpp,h}}: the ring buffer + percussiveness
 * detector that feeds the live (input-monitoring) pitch shifter. The C reads the hardware I2S RX buffer
 * ({@code AudioEngine::i2sRXBufferPos}); here the input block is injected as a seam (interleaved L/R).
 * The angle / LPF / percussiveness DSP and {@link #getAveragesForCrossfade} are verbatim.
 */
public class LiveInputBuffer {

  // definitions_cxx.hpp
  static final int K_INPUT_RAW_BUFFER_SIZE = 8192;
  static final int K_PERC_BUFFER_REDUCTION_MAGNITUDE = 7;
  static final int K_PERC_BUFFER_REDUCTION_SIZE = 1 << K_PERC_BUFFER_REDUCTION_MAGNITUDE; // 128
  static final int K_INPUT_PERC_BUFFER_SIZE = K_INPUT_RAW_BUFFER_SIZE >> K_PERC_BUFFER_REDUCTION_MAGNITUDE; // 64
  static final int K_DIFFERENCE_LPF_POLES = 2;

  /** C: OscType INPUT_L / INPUT_R / IN_STEREO. */
  public enum InputType { INPUT_L, INPUT_R, STEREO }

  public int upToTime;              // uint32
  public int numRawSamplesProcessed; // uint32
  public int lastSampleRead;
  public int lastAngle;
  public final int[] angleLPFMem = new int[K_DIFFERENCE_LPF_POLES];
  public final byte[] percBuffer = new byte[K_INPUT_PERC_BUFFER_SIZE]; // uint8
  public final int[] rawBuffer = new int[K_INPUT_RAW_BUFFER_SIZE * 2]; // double-length for stereo

  /** C: live_input_buffer.cpp:29-32 */
  public LiveInputBuffer() {
    upToTime = 0;
    numRawSamplesProcessed = 0;
  }

  /**
   * C: giveInput (live_input_buffer.cpp:34-110). {@code input} is the interleaved-stereo live input
   * block ({@code input[i*2]} = L, {@code input[i*2+1]} = R) — the seam replacing the I2S RX read.
   */
  public void giveInput(int[] input, int numSamples, int currentTime, InputType inputType) {
    if (upToTime == currentTime + numSamples) {
      return; // C:36-38 already done
    }
    if (upToTime != currentTime) { // C:41-46 — missed some, reset
      numRawSamplesProcessed = 0;
      lastSampleRead = 0;
      lastAngle = 0;
      for (int p = 0; p < K_DIFFERENCE_LPF_POLES; p++) {
        angleLPFMem[p] = 0;
      }
    }

    int inIdx = 0;
    int endNumRawSamplesProcessed = numRawSamplesProcessed + numSamples;
    do {
      int l = input[inIdx * 2];
      int r = input[inIdx * 2 + 1];
      int thisSampleRead;
      int mask = K_INPUT_RAW_BUFFER_SIZE - 1;
      if (inputType == InputType.INPUT_L) {
        thisSampleRead = l >> 2;
        rawBuffer[numRawSamplesProcessed & mask] = l;
      } else if (inputType == InputType.INPUT_R) {
        thisSampleRead = r >> 2;
        rawBuffer[numRawSamplesProcessed & mask] = r;
      } else { // STEREO
        thisSampleRead = (l >> 2) + (r >> 2);
        rawBuffer[(numRawSamplesProcessed & mask) * 2] = l;
        rawBuffer[(numRawSamplesProcessed & mask) * 2 + 1] = r;
      }

      int angle = thisSampleRead - lastSampleRead; // C:70
      lastSampleRead = thisSampleRead;
      if (angle < 0) {
        angle = -angle;
      }

      for (int p = 0; p < K_DIFFERENCE_LPF_POLES; p++) { // C:76-81
        int distanceToGo = angle - angleLPFMem[p];
        angleLPFMem[p] += Functions.multiply_32x32_rshift32_rounded(distanceToGo, 1 << 23);
        angle = angleLPFMem[p];
      }

      if ((numRawSamplesProcessed & (K_PERC_BUFFER_REDUCTION_SIZE - 1)) == 0) { // C:83
        int difference = angle - lastAngle;
        if (difference < 0) {
          difference = -difference;
        }
        // C:90 — angle can be 0 for silence (the C divides by it: UB); guard to 0.
        int percussiveness = (angle != 0) ? (int) ((((long) difference * 262144) / angle) >> 1) : 0;
        percussiveness = Functions.getTanHUnknown(percussiveness, 23); // C:92 getTanH<23>
        percBuffer[(numRawSamplesProcessed >>> K_PERC_BUFFER_REDUCTION_MAGNITUDE) & (K_INPUT_PERC_BUFFER_SIZE - 1)] =
            (byte) percussiveness;
      }
      lastAngle = angle; // C:96

      inIdx++; // (the C ring-wraps the hardware RX buffer; here the input is a flat block)
      numRawSamplesProcessed++;
    } while (numRawSamplesProcessed != endNumRawSamplesProcessed);

    upToTime = currentTime + numSamples;
  }

  /**
   * C: getAveragesForCrossfade (live_input_buffer.cpp:112-130) — moving-average similarity metric over
   * the ring buffer (summed top-16-bits, all channels), as the live pitch shifter's hop search uses.
   */
  public boolean getAveragesForCrossfade(int[] totals, int startPos, int lengthToAverageEach, int numChannels) {
    int currentPos = startPos;
    for (int i = 0; i < TimeStretcher.K_NUM_MOVING_AVERAGES; i++) {
      totals[i] = 0;
      int endPos = (currentPos + lengthToAverageEach) & (K_INPUT_RAW_BUFFER_SIZE - 1);
      do {
        totals[i] += rawBuffer[currentPos * numChannels] >> 16;
        if (numChannels == 2) {
          totals[i] += rawBuffer[currentPos * 2 + 1] >> 16;
        }
        currentPos = (currentPos + 1) & (K_INPUT_RAW_BUFFER_SIZE - 1);
      } while (currentPos != endPos);
    }
    return true;
  }
}
