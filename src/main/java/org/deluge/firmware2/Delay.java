package org.deluge.firmware2;

/**
 * Faithful line-by-line port of {@code dsp/delay/delay.cpp} (464 lines) + {@code
 * dsp/delay/delay_buffer.cpp} (191 lines).
 *
 * <p>Variable-speed stereo delay with resampling, ping-pong, HPF, feedback abandon logic, and
 * optional analog saturation. The delay buffer auto-resizes for rate changes using a dual-buffer
 * scheme.
 */
public class Delay {

  // ── State struct (delay.h:30-35) ──

  public static class State {
    /** C: delay.h:31 */
    public boolean doDelay;

    /** C: delay.h:32 */
    public int userDelayRate;

    /** C: delay.h:33 */
    public int delayFeedbackAmount;

    /** C: delay.h:34 — analog saturation amount (default 8) */
    public int analog_saturation = 8;

    public State() {}
  }

  // ── DelayBuffer constants (delay_buffer.h:28-31) ──

  /** C: delay_buffer.h:28 — space between read and write pointers */
  static final int DELAY_SPACE_BETWEEN_READ_AND_WRITE = 20;

  /** C: delay_buffer.h:32-34 */
  static final int KB_MAX_SIZE = 88200;

  static final int KB_MIN_SIZE = 1;
  static final int KB_NEUTRAL_SIZE = 16384;

  /**
   * C: kMaxSampleValue = 1 &lt;&lt; kBitDepth = 1 &lt;&lt; 24 (definitions_cxx.hpp:992-995). This
   * is the delay-buffer rate unit ("1 is represented as 16777216"), NOT Q31 unity (2147483647). It
   * scales the buffer-size formula (getIdealBufferSizeFromRate / makeNativeRatePrecise) and the
   * resample spin-rate thresholds. Using Q31 max here clamped every buffer to KB_MAX_SIZE and made
   * the spin rate 128× too slow, so the secondary→primary swap never fired and the delay produced
   * no echo.
   */
  static final int K_MAX_SAMPLE_VALUE = 1 << 24; // 16777216

  // ── Delay fields (delay.h:59-81) ──

  final DelayBuffer primaryBuffer = new DelayBuffer();
  final DelayBuffer secondaryBuffer = new DelayBuffer();

  /** C: delay.h:61 — impulse response processor (analog-mode convolution). */
  final ImpulseResponseProcessor irProcessor = new ImpulseResponseProcessor();

  int countCyclesWithoutChange;
  int userRateLastTime;
  private int[][] workingBuf = new int[0][0];

  /** C: delay.h:65 */
  public boolean pingPong = true;

  /** C: delay.h:66 */
  public boolean analog;

  /** C: delay.h:68 */
  public int syncType; // 0=EVEN, 1=TRIPLET, 2=DOTTED

  /** C: delay.h:71 — 0=off, 9=fastest */
  public int syncLevel;

  /** C: delay.h:73 */
  int sizeLeftUntilBufferSwap;

  /** C: delay.h:75-76 — post-HPF state */
  int postLPFL;

  int postLPFR;

  /** C: delay.h:78 */
  int prevFeedback;

  /**
   * C: delay.h:80 — 0 means never abandon. Public so the per-sound delay host can keep rendering
   * the FX tail while repeats are pending (FirmwareSound silence-bypass).
   */
  public int repeatsUntilAbandon;

  // ── isActive (delay.h:48) ──

  /** C: delay.h:48 */
  public boolean isActive() {
    return primaryBuffer.isActive() || secondaryBuffer.isActive();
  }

  // ── informWhetherActive (delay.cpp:31-74) ──

  /** C: delay.cpp:31-74 */
  void informWhetherActive(boolean newActive, int userDelayRate) {
    boolean previouslyActive = isActive();

    if (previouslyActive != newActive) {
      if (!newActive) {
        discardBuffers(); // C:37
        return;
      }
      // setupSecondaryBuffer: C:41-49
      if (!secondaryBuffer.init(userDelayRate)) {
        return;
      }
      prepareToBeginWriting(); // C:46
      postLPFL = 0; // C:47
      postLPFR = 0; // C:48
      return;
    }

    // C:54-73 — if already active
    if (previouslyActive) {
      if (!primaryBuffer.isActive()
          && secondaryBuffer.isActive()
          && sizeLeftUntilBufferSwap == getAmountToWriteBeforeReadingBegins()) {

        int[] ideal = DelayBuffer.getIdealBufferSizeFromRate(userDelayRate);
        int idealBufferSize = ideal[0];

        if (idealBufferSize != secondaryBuffer.size_) {
          secondaryBuffer.discard();
          if (!secondaryBuffer.init(userDelayRate)) return;
          prepareToBeginWriting();
          postLPFL = 0;
          postLPFR = 0;
        }
      }
    }
  }

  // ── copySecondaryToPrimary (delay.cpp:76-80) ──

  /** C: delay.cpp:76-80 */
  void copySecondaryToPrimary() {
    primaryBuffer.discard();
    primaryBuffer.copyFrom(secondaryBuffer); // C:78 — struct copy
    secondaryBuffer.invalidate(); // C:79
  }

  // ── copyPrimaryToSecondary (delay.cpp:82-86) ──

  /** C: delay.cpp:82-86 */
  void copyPrimaryToSecondary() {
    secondaryBuffer.discard();
    secondaryBuffer.copyFrom(primaryBuffer); // C:84 — struct copy
    primaryBuffer.invalidate(); // C:85
  }

  // ── prepareToBeginWriting (delay.cpp:88-90) ──

  /** C: delay.cpp:88-90 */
  void prepareToBeginWriting() {
    sizeLeftUntilBufferSwap = getAmountToWriteBeforeReadingBegins();
  }

  /** C: delay.h:86 — getAmountToWriteBeforeReadingBegins */
  int getAmountToWriteBeforeReadingBegins() {
    return secondaryBuffer.size_;
  }

  // ── setupWorkingState (delay.cpp:93-132) ──

  /**
   * C: delay.cpp:93-132. Sets up the working state for a render block. Call before {@link
   * #process}.
   */
  public void setupWorkingState(
      State workingState, int timePerInternalTickInverse, boolean anySoundComingIn) {
    // C:98 — feedback threshold check
    boolean mightDoDelay =
        (workingState.delayFeedbackAmount >= 256 && (anySoundComingIn || repeatsUntilAbandon != 0));

    if (mightDoDelay) {
      // C:102-118 — sync rate adjustment
      if (syncLevel != 0) {
        workingState.userDelayRate =
            Functions.multiply_32x32_rshift32_rounded(
                workingState.userDelayRate, timePerInternalTickInverse);

        // C:108 — the literal INT_MAX ("the biggest number we can store"), NOT kMaxSampleValue.
        int limit = 2147483647 >> (syncLevel + 5);
        workingState.userDelayRate = Math.min(workingState.userDelayRate, limit);

        if (syncType == 0) {
          /* EVEN — do nothing */
        } else if (syncType == 1) { // TRIPLET
          workingState.userDelayRate = workingState.userDelayRate * 3 / 2;
        } else if (syncType == 2) { // DOTTED
          workingState.userDelayRate = workingState.userDelayRate * 2 / 3;
        }
        workingState.userDelayRate <<= (syncLevel + 5); // C:117
      }
    }

    // C:122-123
    informWhetherActive(mightDoDelay, workingState.userDelayRate);
    workingState.doDelay = isActive();

    // C:125-131
    if (workingState.doDelay) {
      if (anySoundComingIn || workingState.delayFeedbackAmount != prevFeedback) {
        setTimeToAbandon(workingState); // C:128
        prevFeedback = workingState.delayFeedbackAmount; // C:129
      }
    }
  }

  // ── setTimeToAbandon (delay.cpp:134-183) ──

  /** C: delay.cpp:134-183 — determine how many delay repeats before abandon. */
  void setTimeToAbandon(State workingState) {
    if (!workingState.doDelay) {
      repeatsUntilAbandon = 0; // C:137
    } else if (workingState.delayFeedbackAmount < 33554432) {
      repeatsUntilAbandon = 1;
    } else if (workingState.delayFeedbackAmount <= 100663296) {
      repeatsUntilAbandon = 2;
    } else if (workingState.delayFeedbackAmount <= 218103808) {
      repeatsUntilAbandon = 3;
    } else if (workingState.delayFeedbackAmount < 318767104) {
      repeatsUntilAbandon = 4;
    } else if (workingState.delayFeedbackAmount < 352321536) {
      repeatsUntilAbandon = 5;
    } else if (workingState.delayFeedbackAmount < 452984832) {
      repeatsUntilAbandon = 6;
    } else if (workingState.delayFeedbackAmount < 520093696) {
      repeatsUntilAbandon = 9;
    } else if (workingState.delayFeedbackAmount < 637534208) {
      repeatsUntilAbandon = 12;
    } else if (workingState.delayFeedbackAmount < 704643072) {
      repeatsUntilAbandon = 13;
    } else if (workingState.delayFeedbackAmount < 771751936) {
      repeatsUntilAbandon = 18;
    } else if (workingState.delayFeedbackAmount < 838860800) {
      repeatsUntilAbandon = 24;
    } else if (workingState.delayFeedbackAmount < 939524096) {
      repeatsUntilAbandon = 40;
    } else if (workingState.delayFeedbackAmount < 1040187392) {
      repeatsUntilAbandon = 110;
    } else {
      repeatsUntilAbandon = 255;
    }
  }

  // ── hasWrapped (delay.cpp:185-195) ──

  /** C: delay.cpp:185-195 */
  void hasWrapped() {
    if (repeatsUntilAbandon == 255) return;
    repeatsUntilAbandon--;
    if (repeatsUntilAbandon == 0) {
      discardBuffers(); // C:193
    }
  }

  // ── discardBuffers (delay.cpp:197-202) ──

  /** C: delay.cpp:197-202 */
  public void discardBuffers() {
    primaryBuffer.discard();
    secondaryBuffer.discard();
    prevFeedback = 0;
    repeatsUntilAbandon = 0;
  }

  // ── initializeSecondaryBuffer (delay.cpp:204-222) ──

  /** C: delay.cpp:204-222 */
  void initializeSecondaryBuffer(
      int newNativeRate, boolean makeNativeRatePreciseRelativeToOtherBuffer) {
    if (!secondaryBuffer.init(newNativeRate, primaryBuffer.size_)) return;

    if (makeNativeRatePreciseRelativeToOtherBuffer) {
      primaryBuffer.makeNativeRatePreciseRelativeToOtherBuffer(secondaryBuffer);
    } else {
      primaryBuffer.makeNativeRatePrecise();
      secondaryBuffer.makeNativeRatePrecise();
    }
    sizeLeftUntilBufferSwap = secondaryBuffer.size_ + 5; // C:221
  }

  // ── process (delay.cpp:224-464) ──

  /**
   * C: delay.cpp:224-464 — main delay processing loop. Reads from delay buffer, applies feedback +
   * HPF + saturation, mixes with input, writes back.
   */
  public void process(int[][] buffer, int numSamples, State delayWorkingState) {
    if (!delayWorkingState.doDelay) return; // C:225-227

    // C:229-235 — rate change tracking
    if (delayWorkingState.userDelayRate != userRateLastTime) {
      userRateLastTime = delayWorkingState.userDelayRate;
      countCyclesWithoutChange = 0; // C:231
    } else {
      countCyclesWithoutChange += numSamples; // C:234
    }

    // C:238-261 — consider creating secondary buffer
    if (!secondaryBuffer.isActive()) {
      if (primaryBuffer.resampling()
          || delayWorkingState.userDelayRate != primaryBuffer.native_rate_) {
        if (countCyclesWithoutChange >= (44100 >> 3)) { // C:244 — kSampleRate>>3
          initializeSecondaryBuffer(delayWorkingState.userDelayRate, true);
        } else if (delayWorkingState.userDelayRate >= (primaryBuffer.native_rate_ << 1)) { // C:251
          initializeSecondaryBuffer(delayWorkingState.userDelayRate, false);
        } else if (delayWorkingState.userDelayRate < (primaryBuffer.native_rate_ >> 1)) { // C:257
          initializeSecondaryBuffer(delayWorkingState.userDelayRate >> 1, false);
        }
      }
    }

    // C:264-269 — setup buffers for render
    primaryBuffer.setupForRender(delayWorkingState.userDelayRate);
    if (secondaryBuffer.isActive()) {
      secondaryBuffer.setupForRender(delayWorkingState.userDelayRate);
    }

    boolean wrapped = false; // C:271

    // C:273 — working buffer (spareRenderingBuffer)
    // Prepare workingBuf scratch space
    if (this.workingBuf.length < numSamples) {
      int[][] next = new int[numSamples][2];
      System.arraycopy(this.workingBuf, 0, next, 0, this.workingBuf.length);
      for (int i = this.workingBuf.length; i < numSamples; i++) {
        next[i] = new int[2];
      }
      this.workingBuf = next;
    }
    int[][] workingBuf = this.workingBuf;
    for (int i = 0; i < numSamples; i++) {
      workingBuf[i][0] = 0;
      workingBuf[i][1] = 0;
    }
    int primaryBufferOldLongPos = 0;
    int primaryBufferOldLastShortPos = 0;

    // C:282-327 — read from primary buffer
    int primaryOldIdx = -1; // saved for write-back below
    if (!primaryBuffer.isActive()) {
      // C:283 — fill with silence
    } else {
      // C:289-291 — save old position
      primaryOldIdx = primaryBuffer.currentIdx;
      primaryBufferOldLongPos = primaryBuffer.longPos;
      primaryBufferOldLastShortPos = primaryBuffer.lastShortPos;

      // C:294-298 — native read
      if (primaryBuffer.isNative()) {
        for (int i = 0; i < numSamples; i++) {
          wrapped = primaryBuffer.clearAndMoveOn() || wrapped;
          workingBuf[i][0] = primaryBuffer.data[primaryBuffer.currentIdx][0];
          workingBuf[i][1] = primaryBuffer.data[primaryBuffer.currentIdx][1];
        }
      } else {
        // C:303-325 — resampling read
        for (int i = 0; i < numSamples; i++) {
          int primaryStrength2 = primaryBuffer.advanceRead(i == 0);
          int primaryStrength1 = 65536 - primaryStrength2; // C:310

          int nextIdx = (primaryBuffer.currentIdx + 1) % primaryBuffer.sizeIncludingExtra;
          int fromDelay1L = primaryBuffer.data[primaryBuffer.currentIdx][0];
          int fromDelay1R = primaryBuffer.data[primaryBuffer.currentIdx][1];
          int fromDelay2L = primaryBuffer.data[nextIdx][0];
          int fromDelay2R = primaryBuffer.data[nextIdx][1];

          // C:319-324
          int term1L =
              Functions.multiply_32x32_rshift32(
                  fromDelay1L, Functions.lshiftAndSaturate(primaryStrength1, 14));
          int term2L =
              Functions.multiply_32x32_rshift32(
                  fromDelay2L, Functions.lshiftAndSaturate(primaryStrength2, 14));
          workingBuf[i][0] = Functions.lshiftAndSaturate(Functions.add_saturate(term1L, term2L), 2);

          int term1R =
              Functions.multiply_32x32_rshift32(
                  fromDelay1R, Functions.lshiftAndSaturate(primaryStrength1, 14));
          int term2R =
              Functions.multiply_32x32_rshift32(
                  fromDelay2R, Functions.lshiftAndSaturate(primaryStrength2, 14));
          workingBuf[i][1] = Functions.lshiftAndSaturate(Functions.add_saturate(term1R, term2R), 2);
        }
      }
    }

    // C:329-356 — apply feedback + saturation
    if (analog) {
      // C:331-334 — first pass the whole buffer through the impulse-response convolution.
      for (int i = 0; i < numSamples; i++) {
        irProcessor.process(workingBuf[i], workingBuf[i]);
      }
      // C:336-345 — then reduce headroom + tanH saturation (sounds ok with the analog sim).
      for (int i = 0; i < numSamples; i++) {
        workingBuf[i][0] =
            Functions.lshiftAndSaturate(
                Functions.getTanHUnknown(
                    Functions.multiply_32x32_rshift32(
                        workingBuf[i][0], delayWorkingState.delayFeedbackAmount),
                    delayWorkingState.analog_saturation),
                2);
        workingBuf[i][1] =
            Functions.lshiftAndSaturate(
                Functions.getTanHUnknown(
                    Functions.multiply_32x32_rshift32(
                        workingBuf[i][1], delayWorkingState.delayFeedbackAmount),
                    delayWorkingState.analog_saturation),
                2);
      }
    } else {
      // C:348-355 — digital path
      for (int i = 0; i < numSamples; i++) {
        workingBuf[i][0] =
            Functions.lshiftAndSaturate(
                Functions.signed_saturate(
                    Functions.multiply_32x32_rshift32(
                        workingBuf[i][0], delayWorkingState.delayFeedbackAmount),
                    29),
                2);
        workingBuf[i][1] =
            Functions.lshiftAndSaturate(
                Functions.signed_saturate(
                    Functions.multiply_32x32_rshift32(
                        workingBuf[i][1], delayWorkingState.delayFeedbackAmount),
                    29),
                2);
      }
    }

    // C:360-368 — HPF on delay output (~40Hz corner)
    for (int i = 0; i < numSamples; i++) {
      int distanceToGoL = workingBuf[i][0] - postLPFL; // C:361
      postLPFL += distanceToGoL >> 11; // C:362
      workingBuf[i][0] -= postLPFL; // C:363

      int distanceToGoR = workingBuf[i][1] - postLPFR; // C:365
      postLPFR += distanceToGoR >> 11; // C:366
      workingBuf[i][1] -= postLPFR; // C:367
    }

    // C:372-386 — feedback + mix with input
    for (int i = 0; i < numSamples; i++) {
      int currentL = workingBuf[i][0];
      int currentR = workingBuf[i][1];

      // C:376-382 — feedback calculation (ping-pong or mono)
      if (pingPong) {
        workingBuf[i][0] = currentR; // C:377
        workingBuf[i][1] = ((buffer[i][0] + buffer[i][1]) >> 1) + currentL; // C:378
      } else {
        workingBuf[i][0] = buffer[i][0] + currentL; // C:381
        workingBuf[i][1] = buffer[i][1] + currentR; // C:381
      }

      // C:385 — output mix
      buffer[i][0] = Functions.add_saturate(buffer[i][0], currentL);
      buffer[i][1] = Functions.add_saturate(buffer[i][1], currentR);
    }

    // C:389-418 — write feedback back to primary buffer
    if (primaryBuffer.isActive()) {
      if (primaryBuffer.isNative()) {
        // C:393-400 — native write
        int writeIdx = primaryOldIdx - DELAY_SPACE_BETWEEN_READ_AND_WRITE;
        while (writeIdx < 0) writeIdx += primaryBuffer.sizeIncludingExtra;

        for (int i = 0; i < numSamples; i++) {
          primaryBuffer.data[writeIdx][0] = workingBuf[i][0];
          primaryBuffer.data[writeIdx][1] = workingBuf[i][1];
          writeIdx++;
          if (writeIdx >= primaryBuffer.sizeIncludingExtra) writeIdx = 0;
        }
      } else {
        // C:405-417 — resampling write
        primaryBuffer.currentIdx = primaryOldIdx;
        primaryBuffer.longPos = primaryBufferOldLongPos;
        primaryBuffer.lastShortPos = primaryBufferOldLastShortPos;

        for (int i = 0; i < numSamples; i++) {
          int primaryStrength2 = primaryBuffer.advanceRead(false);
          int primaryStrength1 = 65536 - primaryStrength2;
          primaryBuffer.writeResampled(
              workingBuf[i][0], workingBuf[i][1], primaryStrength1, primaryStrength2);
        }
      }
    }

    // C:423-458 — secondary buffer write-through
    if (secondaryBuffer.isActive()) {
      wrapped = false; // C:426

      if (secondaryBuffer.isNative()) {
        // C:429-436
        for (int i = 0; i < numSamples; i++) {
          wrapped = secondaryBuffer.clearAndMoveOn() || wrapped;
          sizeLeftUntilBufferSwap--;
          // writeNative
          int writeIdx = secondaryBuffer.currentIdx - DELAY_SPACE_BETWEEN_READ_AND_WRITE;
          while (writeIdx < 0) writeIdx += secondaryBuffer.sizeIncludingExtra;
          secondaryBuffer.data[writeIdx][0] = workingBuf[i][0];
          secondaryBuffer.data[writeIdx][1] = workingBuf[i][1];
        }
      } else {
        // C:441-453 — resampling write. The advance callback runs once per buffer move-on (C's
        // lambda at delay.cpp:444-447): it does clearAndMoveOn (updating `wrapped`) AND decrements
        // sizeLeftUntilBufferSwap. The simplified advanceRead can't express these side effects, so
        // omitting them meant the swap counter never reached < 0 → copySecondaryToPrimary never ran
        // → the delay produced no echo. Use advance(callback) to mirror the C exactly.
        final boolean[] w = {wrapped};
        for (int i = 0; i < numSamples; i++) {
          int secondaryStrength2 =
              secondaryBuffer.advance(
                  () -> {
                    w[0] = secondaryBuffer.clearAndMoveOn() || w[0]; // C:445
                    sizeLeftUntilBufferSwap--; // C:446
                  });
          int secondaryStrength1 = 65536 - secondaryStrength2;
          secondaryBuffer.writeResampled(
              workingBuf[i][0], workingBuf[i][1], secondaryStrength1, secondaryStrength2);
        }
        wrapped = w[0];
      }

      // C:456-458
      if (sizeLeftUntilBufferSwap < 0) {
        copySecondaryToPrimary();
      }
    }

    // C:461-463
    if (wrapped) {
      hasWrapped();
    }
  }

  // ── ImpulseResponseProcessor (dsp/convolution/impulse_response_processor.h) ──

  /**
   * Verbatim port of ImpulseResponseProcessor: a 26-tap stereo FIR in transposed form (state is the
   * running 25-sample accumulator buffer). Used by the analog delay path.
   */
  static final class ImpulseResponseProcessor {
    static final int IR_SIZE = 26;
    static final int IR_BUFFER_SIZE = IR_SIZE - 1; // 25

    static final int[] ir = {
      -3203916,
      8857848,
      24813136,
      41537808,
      35217472,
      15195632,
      -27538592,
      -61984128,
      1944654848,
      1813580928,
      438462784,
      101125088,
      6042048,
      -22429488,
      -46218864,
      -56638560,
      -64785312,
      -52108528,
      -37256992,
      -11863856,
      1390352,
      14663296,
      12784464,
      14254800,
      5690912,
      4490736,
    };

    private final int[] bufL = new int[IR_BUFFER_SIZE];
    private final int[] bufR = new int[IR_BUFFER_SIZE];

    /** C: process(input, output). in/out may alias (in-place), so capture the input first. */
    void process(int[] in, int[] out) {
      int inL = in[0];
      int inR = in[1];
      out[0] = bufL[0] + Functions.multiply_32x32_rshift32_rounded(inL, ir[0]);
      out[1] = bufR[0] + Functions.multiply_32x32_rshift32_rounded(inR, ir[0]);

      for (int i = 1; i < IR_BUFFER_SIZE; i++) {
        bufL[i - 1] = bufL[i] + Functions.multiply_32x32_rshift32_rounded(inL, ir[i]);
        bufR[i - 1] = bufR[i] + Functions.multiply_32x32_rshift32_rounded(inR, ir[i]);
      }

      bufL[IR_BUFFER_SIZE - 1] = Functions.multiply_32x32_rshift32_rounded(inL, ir[IR_BUFFER_SIZE]);
      bufR[IR_BUFFER_SIZE - 1] = Functions.multiply_32x32_rshift32_rounded(inR, ir[IR_BUFFER_SIZE]);
    }
  }

  // ── DelayBuffer inner class (delay_buffer.h / delay_buffer.cpp) ──

  static class DelayBuffer {
    /** C: delay_buffer.h:285-286 */
    int longPos;

    int lastShortPos;
    int sizeIncludingExtra;
    int size_; // C:305
    int native_rate_; // C:299
    int[][] data; // C:301 — StereoSample* start_
    int end_; // C:302 — index of end
    int currentIdx; // C:303 — current read position
    boolean resampling; // C:143 — resample_config_ != nullopt
    // Resampling config
    int actualSpinRate; // C:290
    int spinRateForSpedUpWriting; // C:291
    int divideByRate; // C:292
    int rateMultiple; // C:293
    int writeSizeAdjustment; // C:294
    private final int[] strength = new int[4];

    DelayBuffer() {
      discard();
    }

    /** C: delay_buffer.cpp:26-56 */
    boolean init(int rate, int failIfThisSize) {
      int[] ideal = getIdealBufferSizeFromRate(rate);
      size_ = ideal[0];
      native_rate_ = rate;

      if (size_ == failIfThisSize) return false;

      if (ideal[1] != 0) {
        makeNativeRatePrecise();
      }

      sizeIncludingExtra = size_ + DELAY_SPACE_BETWEEN_READ_AND_WRITE;
      data = new int[sizeIncludingExtra][2]; // [l, r]
      end_ = sizeIncludingExtra;
      clear();
      return true;
    }

    boolean init(int rate) {
      return init(rate, 0);
    }

    /** C: delay_buffer.cpp:58-62 */
    void clear() {
      for (int i = 0;
          i < Math.min(DELAY_SPACE_BETWEEN_READ_AND_WRITE + 2, sizeIncludingExtra);
          i++) {
        if (data != null && i < data.length) {
          data[i][0] = 0;
          data[i][1] = 0;
        }
      }
      currentIdx = DELAY_SPACE_BETWEEN_READ_AND_WRITE;
      resampling = false;
    }

    /** C: delay_buffer.cpp:64-80 */
    static int[] getIdealBufferSizeFromRate(int newRate) {
      long bufferSize = (long) KB_NEUTRAL_SIZE * (long) K_MAX_SAMPLE_VALUE / Math.max(1, newRate);
      int clamped = 0;
      if (bufferSize > KB_MAX_SIZE) {
        bufferSize = KB_MAX_SIZE;
        clamped = 1;
      }
      if (bufferSize < KB_MIN_SIZE) {
        bufferSize = KB_MIN_SIZE;
        clamped = 1;
      }
      return new int[] {(int) bufferSize, clamped};
    }

    /** C: delay_buffer.cpp:82-84 */
    void makeNativeRatePrecise() {
      native_rate_ =
          (int) Math.round((double) KB_NEUTRAL_SIZE * (double) K_MAX_SAMPLE_VALUE / (double) size_);
    }

    /** C: delay_buffer.cpp:86-89 */
    void makeNativeRatePreciseRelativeToOtherBuffer(DelayBuffer other) {
      double amountTooFast =
          (double) other.native_rate_
              * (double) other.size_
              / ((double) KB_NEUTRAL_SIZE * (double) K_MAX_SAMPLE_VALUE);
      native_rate_ =
          (int)
              Math.round(
                  (double) KB_NEUTRAL_SIZE
                      * (double) K_MAX_SAMPLE_VALUE
                      * amountTooFast
                      / (double) size_);
    }

    /** C: delay_buffer.cpp:92-97 */
    void discard() {
      data = null;
    }

    /** C: delay_buffer.h:42 */
    void invalidate() {
      data = null;
    }

    boolean isActive() {
      return data != null;
    } // C: delay_buffer.h:80

    boolean isNative() {
      return !resampling;
    } // C: delay_buffer.h:267

    boolean resampling() {
      return resampling;
    } // C: delay_buffer.h:268

    void copyFrom(DelayBuffer other) {
      this.data = other.data;
      this.size_ = other.size_;
      this.native_rate_ = other.native_rate_;
      this.sizeIncludingExtra = other.sizeIncludingExtra;
      this.end_ = other.end_;
      this.currentIdx = other.currentIdx;
      this.longPos = other.longPos;
      this.lastShortPos = other.lastShortPos;
      this.resampling = other.resampling;
      this.actualSpinRate = other.actualSpinRate;
      this.spinRateForSpedUpWriting = other.spinRateForSpedUpWriting;
      this.divideByRate = other.divideByRate;
      this.rateMultiple = other.rateMultiple;
      this.writeSizeAdjustment = other.writeSizeAdjustment;
    }

    // C: delay_buffer.h:82-86 — clearAndMoveOn
    boolean clearAndMoveOn() {
      data[currentIdx][0] = 0;
      data[currentIdx][1] = 0;
      return moveOn();
    }

    // C: delay_buffer.h:88-95 — moveOn
    boolean moveOn() {
      currentIdx++;
      boolean wrapped = (currentIdx == end_);
      if (wrapped) currentIdx = 0;
      return wrapped;
    }

    // C: delay_buffer.h:97-105 — moveBack
    boolean moveBack() {
      if (currentIdx == 0) {
        currentIdx = end_ - 1;
        return true;
      } else {
        currentIdx--;
        return false;
      }
    }

    /**
     * C: delay_buffer.h:50-61 — advance (read with callback). Simplified: returns strength2
     * directly.
     */
    int advanceRead(boolean clearOnWrap) {
      longPos += actualSpinRate;
      int newShortPos = (int) ((longPos & 0xFFFFFFFFFFFFFFFFL) >> 24) & 0xFF;
      int shortPosDiff = (newShortPos - lastShortPos) & 0xFF;
      lastShortPos = (byte) newShortPos;

      while (shortPosDiff > 0) {
        if (clearOnWrap) clearAndMoveOn();
        else moveOn();
        shortPosDiff--;
      }
      return (int) ((longPos >> 8) & 0xFFFF);
    }

    /**
     * C: delay_buffer.h:50-61 — advance with a callback run once per "move on" boundary. The C's
     * resampling secondary-buffer write needs the callback to do clearAndMoveOn + side effects
     * (wrapped tracking, sizeLeftUntilBufferSwap--), which the simplified {@link #advanceRead}
     * can't express. Faithful to the lambda form.
     */
    int advance(Runnable callback) {
      longPos += actualSpinRate;
      int newShortPos = (int) ((longPos & 0xFFFFFFFFFFFFFFFFL) >> 24) & 0xFF;
      int shortPosDiff = (newShortPos - lastShortPos) & 0xFF;
      lastShortPos = (byte) newShortPos;

      while (shortPosDiff > 0) {
        callback.run();
        shortPosDiff--;
      }
      return (int) ((longPos >> 8) & 0xFFFF);
    }

    /** C: delay_buffer.cpp:126-191 — setupForRender. C: delay_buffer.cpp:99-124 — setupResample. */
    void setupForRender(int rate) {
      // C:127-135
      if (!resampling) {
        if (rate == native_rate_ || data == null) return;
        setupResample(); // C:134
      }

      // C:143-144
      actualSpinRate = (int) (((double) ((long) rate << 24)) / (double) native_rate_);
      divideByRate = (int) ((double) 0xFFFFFFFFL / (double) (actualSpinRate >> 8));

      if (actualSpinRate < K_MAX_SAMPLE_VALUE) {
        // C:147-163 — buffer spinning slow
        int timesSlowerRead = divideByRate >> 16;
        rateMultiple = (actualSpinRate >> 8) * (timesSlowerRead + 1);
        writeSizeAdjustment =
            (int) ((double) 0xFFFFFFFFL / (double) (rateMultiple * (timesSlowerRead + 1)));
      } else {
        // C:167-183 — buffer spinning fast
        spinRateForSpedUpWriting = Math.min(actualSpinRate, K_MAX_SAMPLE_VALUE * 8);
        spinRateForSpedUpWriting <<= 1;
        divideByRate >>= 1;
      }
      resampling = true; // C:184-190
    }

    /** C: delay_buffer.cpp:99-124 */
    private void setupResample() {
      longPos = 0;
      lastShortPos = 0;

      int writeIdx = currentIdx - DELAY_SPACE_BETWEEN_READ_AND_WRITE;
      while (writeIdx < 0) writeIdx += sizeIncludingExtra;

      int writePlusOne = writeIdx + 1;
      if (writePlusOne >= end_) writePlusOne -= sizeIncludingExtra;

      data[writePlusOne][0] = data[writeIdx][0] >> 2; // C:119
      data[writePlusOne][1] = data[writeIdx][1] >> 2; // C:120
      data[writeIdx][0] -= data[writePlusOne][0]; // C:122
      data[writeIdx][1] -= data[writePlusOne][1]; // C:123
    }

    /** C: delay_buffer.h:127-139 — write (native path). */
    void writeResampled(int sampleL, int sampleR, int strength1, int strength2) {
      if (!resampling) return; // C:143-145

      // C:147-207 — sped up writing
      if (actualSpinRate >= K_MAX_SAMPLE_VALUE) {
        int howFarRightToStart = (strength2 + (spinRateForSpedUpWriting >> 8)) >> 16; // C:158
        int distanceFromMainWrite = howFarRightToStart << 16; // C:162

        int writeIdx = currentIdx - DELAY_SPACE_BETWEEN_READ_AND_WRITE + howFarRightToStart;
        while (writeIdx < 0) writeIdx += sizeIncludingExtra;
        while (writeIdx >= end_) writeIdx -= sizeIncludingExtra;

        // Right side writes
        while (distanceFromMainWrite != 0) {
          int strengthThisWrite =
              (0xFFFFFFFF >>> 4)
                  - (((distanceFromMainWrite - strength2) >> 4) * divideByRate); // C:176-177
          int valL =
              Functions.lshiftAndSaturate(
                  Functions.multiply_32x32_rshift32(sampleL, strengthThisWrite), 3);
          int valR =
              Functions.lshiftAndSaturate(
                  Functions.multiply_32x32_rshift32(sampleR, strengthThisWrite), 3);
          data[writeIdx][0] = Functions.add_saturate(data[writeIdx][0], valL);
          data[writeIdx][1] = Functions.add_saturate(data[writeIdx][1], valR);
          writeIdx--;
          if (writeIdx < 0) writeIdx = end_ - 1;
          distanceFromMainWrite -= 65536; // C:186
        }

        // Left side writes
        while (true) {
          int strengthThisWrite =
              (0xFFFFFFFF >>> 4)
                  - (((distanceFromMainWrite + strength2) >> 4) * divideByRate); // C:191-192
          if (strengthThisWrite <= 0) break; // C:193-194
          int valL =
              Functions.lshiftAndSaturate(
                  Functions.multiply_32x32_rshift32(sampleL, strengthThisWrite), 3);
          int valR =
              Functions.lshiftAndSaturate(
                  Functions.multiply_32x32_rshift32(sampleR, strengthThisWrite), 3);
          data[writeIdx][0] = Functions.add_saturate(data[writeIdx][0], valL);
          data[writeIdx][1] = Functions.add_saturate(data[writeIdx][1], valR);
          writeIdx--;
          if (writeIdx < 0) writeIdx = end_ - 1;
          distanceFromMainWrite += 65536;
        }
      } else {
        // C:211-263 — slowed down writing
        int writeIdx = currentIdx - DELAY_SPACE_BETWEEN_READ_AND_WRITE + 2;
        while (writeIdx < 0) writeIdx += sizeIncludingExtra;

        strength[1] = strength1 + rateMultiple - 65536; // C:236
        strength[2] = strength2 + rateMultiple - 65536; // C:237
        strength[0] = strength[1] - 65536; // C:240
        strength[3] = strength[2] - 65536; // C:241

        int i = 3; // C:243
        while (true) {
          if (strength[i] > 0) {
            int adjust = (strength[i] >> 2) * writeSizeAdjustment;
            int valL =
                Functions.lshiftAndSaturate(Functions.multiply_32x32_rshift32(sampleL, adjust), 2);
            int valR =
                Functions.lshiftAndSaturate(Functions.multiply_32x32_rshift32(sampleR, adjust), 2);
            data[writeIdx][0] = Functions.add_saturate(data[writeIdx][0], valL);
            data[writeIdx][1] = Functions.add_saturate(data[writeIdx][1], valR);
          }
          if (--i < 0) break; // C:253-254
          writeIdx--;
          if (writeIdx < 0) writeIdx = end_ - 1;
        }
      }
    }
  }
}
