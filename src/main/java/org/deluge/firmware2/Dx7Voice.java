package org.deluge.firmware2;

/**
 * Faithful line-by-line port of the Deluge DX7 engine: {@code dx7note.cpp/h} (DxVoice, DxPatch),
 * {@code env.cpp/h} (per-operator Env), {@code pitchenv.cpp/h} (PitchEnv). Also includes firmware
 * constants and tables.
 *
 * <p>Rendering goes through the faithful {@link FmCore} (modern) / {@link EngineMkI} engines;
 * {@code Sin::lookup} → {@link Dx7Tables#sinLookup}, {@code Freqlut::lookup} → {@link
 * Dx7Tables#freqLookup} (both verbatim from math_lut), {@code getNoise()} → {@link
 * Functions#getNoise}.
 */
public class Dx7Voice {

  // ── Constants (dx7note.cpp) ──

  static final int FEEDBACK_BITDEPTH = 8;
  static final int[] COARSE_MUL = {
    -16777216, 0, 16777216, 26591258, 33554432, 38955489, 43368474, 47099600,
    50331648, 53182516, 55732705, 58039632, 60145690, 62083076, 63876816, 65546747,
    67108864, 68576247, 69959732, 71268397, 72509921, 73690858, 74816848, 75892776,
    76922906, 77910978, 78860292, 79773775, 80654032, 81503396, 82323963, 83117622,
  };
  static final int[] AMP_MOD_SENS = {0, 4342338, 7171437, 16777216};
  static final int[] PITCH_MOD_SENS = {0, 10, 20, 33, 55, 92, 153, 255};

  static final int[] VELOCITY_DATA = {
    0, 70, 86, 97, 106, 114, 121, 126, 132, 138, 142, 148, 152, 156, 160, 163,
    166, 170, 173, 174, 178, 181, 184, 186, 189, 190, 194, 196, 198, 200, 202, 205,
    206, 209, 211, 214, 216, 218, 220, 222, 224, 225, 227, 229, 230, 232, 233, 235,
    237, 238, 240, 241, 242, 243, 244, 246, 246, 248, 249, 250, 251, 252, 253, 254,
  };

  static final int[] EXP_SCALE_DATA = {
    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 14, 16, 19, 23, 27, 33, 39, 47, 56, 66, 80, 94, 110, 126, 142,
    158, 174, 190, 206, 222, 238, 250,
  };

  // ── dxNoteToFreq (dx7note.cpp:50-54) ──
  static int dxNoteToFreq(int note) {
    final int base = 50857777; // (1<<24)*(log(440)/log(2)-69/12)
    final int step = (1 << 24) / 12;
    return base + step * note;
  }

  // ── ScaleVelocity (dx7note.cpp:167-172) ──
  static int scaleVelocity(int velocity, int sensitivity) {
    int clampedVel = Math.max(0, Math.min(127, velocity));
    int velValue = VELOCITY_DATA[clampedVel >> 1] - 239;
    return ((sensitivity * velValue + 7) >> 3) << 4;
  }

  // ── ScaleRate (dx7note.cpp:174-187) ──
  static int scaleRate(int midinote, int sensitivity) {
    int x = Math.min(31, Math.max(0, midinote / 3 - 7));
    return (sensitivity * x) >> 3;
  }

  // ── ScaleCurve (dx7note.cpp:192-208) ──
  static int scaleCurve(int group, int depth, int curve) {
    int scale;
    if (curve == 0 || curve == 3) {
      scale = (group * depth * 329) >> 12;
    } else {
      int nScaleData = EXP_SCALE_DATA.length;
      int rawExp = EXP_SCALE_DATA[Math.min(group, nScaleData - 1)];
      scale = (rawExp * depth * 329) >> 15;
    }
    if (curve < 2) scale = -scale;
    return scale;
  }

  // ── ScaleLevel (dx7note.cpp:210-218) ──
  static int scaleLevel(
      int midinote, int breakPt, int leftDepth, int rightDepth, int leftCurve, int rightCurve) {
    int offset = midinote - breakPt - 17;
    if (offset >= 0) {
      return scaleCurve((offset + 1) / 3, rightDepth, rightCurve);
    } else {
      return scaleCurve(-(offset - 1) / 3, leftDepth, leftCurve);
    }
  }

  // ── lfoPhaseToValue (dx7note.cpp:90-112) ──
  static int lfoPhaseToValue(int phase, int waveform) {
    if (waveform == 0) waveform = 4;
    switch (waveform) {
      case 0: // triangle
        int x = phase >>> 7;
        if (phase < 0) x = ~x;
        return x & ((1 << 24) - 1);
      case 1: // saw down
        return (~phase ^ (1 << 31)) >>> 8;
      case 2: // saw up
        return (phase ^ (1 << 31)) >>> 8;
      case 3: // square
        return ((~phase) >>> 7) & (1 << 24);
      case 4: // sine — C: (1 << 23) + (Sin::lookup(phase_ >> 8) >> 1)
        return (1 << 23) + (Dx7Tables.sinLookup(phase >> 8) >> 1);
      default:
        return 1 << 23;
    }
  }

  // ═══════════════════════════════════════════════════════════════
  // DxPatch (dx7note.h:38-66, dx7note.cpp:56-128)
  // ═══════════════════════════════════════════════════════════════

  public static class DxPatch {
    static final int[] INIT_VOICE = {
      99, 99, 99, 99, 99, 99, 99, 0, 0, 0, 0, 0,
      0, 0, 0, 0, 0, 0, 1, 0, 7, 99, 99, 99,
      99, 99, 99, 99, 0, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 0, 1, 0, 7, 99, 99, 99, 99, 99, 99,
      99, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      1, 0, 7, 99, 99, 99, 99, 99, 99, 99, 0, 0,
      0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 7,
      99, 99, 99, 99, 99, 99, 99, 0, 0, 0, 0, 0,
      0, 0, 0, 0, 0, 0, 1, 0, 7, 99, 99, 99,
      99, 99, 99, 99, 0, 0, 0, 0, 0, 0, 0, 0,
      0, 99, 0, 1, 0, 7, 99, 99, 99, 99, 50, 50,
      50, 50, 0, 0, 1, 35, 0, 0, 0, 1, 0, 3,
      24, 73, 78, 73, 84, 32, 86, 79, 73, 67, 69, 63
    };

    public final byte[] params = new byte[156];
    public FmCore core; // set by engine
    public int engineMode;
    public boolean useMkI; // updateEngineMode result: true -> EngineMkI, false -> modern FmCore
    public int randomDetune;
    public int pitchMod;
    public int egMod = 127;
    public int lfoPhase; // Q32
    public int lfoDelta;
    public int lfoValue;

    public DxPatch() {
      for (int i = 0; i < 156; i++) params[i] = (byte) INIT_VOICE[i];
      setEngineMode(0);
    }

    public boolean opSwitch(int op) {
      return ((params[155] >> op) & 1) != 0;
    }

    // updateEngineMode (dx7note.cpp:66-79). core = modern; MkI if engineMode==2, or (engineMode==0
    // auto && feedback>0 && algo is 3 or 5 — only EngineMkI implements algo 4/6 feedback loops).
    void updateEngineMode() {
      useMkI = false;
      if (engineMode == 2) {
        useMkI = true;
      } else if (engineMode == 0) {
        int algo = params[134] & 0xFF;
        int feedback = params[135] & 0xFF;
        if (feedback > 0 && (algo == 3 || algo == 5)) {
          useMkI = true;
        }
      }
    }

    // setEngineMode (dx7note.cpp:81-88)
    public void setEngineMode(int mode) {
      engineMode = mode;
      updateEngineMode();
    }

    static final int LFO_UNIT = (int) (25190424.0 / 44100.0 + 0.5);

    // computeLfo (dx7note.cpp:115-128)
    public void computeLfo(int n) {
      int rate = params[137] & 0xFF;
      int sr = rate == 0 ? 1 : (165 * rate) >> 6;
      sr *= sr < 160 ? 11 : (11 + ((sr - 160) >> 4));
      lfoDelta = LFO_UNIT * sr;
      lfoPhase += n * lfoDelta;
      int waveform = params[142] & 0xFF;
      lfoValue = lfoPhaseToValue(lfoPhase, waveform);
    }
  }

  // ═══════════════════════════════════════════════════════════════
  // EnvParams + Env (env.h/cpp)
  // ═══════════════════════════════════════════════════════════════

  public static class EnvParams {
    // rates[4], levels[4] — extracted from patch at op*21 offset
  }

  public static class Dx7Env {
    static final int SR_MULTIPLIER = 1 << 24;
    static final int[] LEVEL_LUT = {
      0, 5, 9, 13, 17, 20, 23, 25, 27, 29, 31, 33, 35, 37, 39, 41, 42, 43, 45, 46
    };
    static final int[] STATICS = {
      1764000, 1764000, 1411200, 1411200, 1190700, 1014300, 992250, 882000,
      705600, 705600, 584325, 507150, 502740, 441000, 418950, 352800,
      308700, 286650, 253575, 220500, 220500, 176400, 145530, 145530,
      125685, 110250, 110250, 88200, 88200, 74970, 61740, 61740,
      55125, 48510, 44100, 37485, 31311, 30870, 27562, 27562,
      22050, 18522, 17640, 15435, 14112, 13230, 11025, 9261,
      9261, 7717, 6615, 6615, 5512, 5512, 4410, 3969,
      3969, 3439, 2866, 2690, 2249, 1984, 1896, 1808,
      1411, 1367, 1234, 1146, 926, 837, 837, 705,
      573, 573, 529, 441, 441,
    };

    int level; // Q24
    int targetLevel;
    int ix;
    int inc;
    int staticCount;
    int outlevel;
    int rateScaling;
    boolean down = true;
    boolean rising;

    // init (env.cpp:44-50)
    public void init(byte[] patch, int opOff, int ol, int rateScaling) {
      this.outlevel = ol;
      this.rateScaling = rateScaling;
      level = 0;
      down = true;
      advance(patch, opOff, 0, 0);
    }

    // getsample (env.cpp:52-89)
    public int getSample(byte[] patch, int opOff, int n, int extraRate) {
      if (staticCount != 0) {
        staticCount -= n;
        if (staticCount <= 0) {
          staticCount = 0;
          advance(patch, opOff, ix + 1, extraRate);
        }
      }
      if (ix < 3 || ((ix < 4) && !down)) {
        if (staticCount == 0) {
          if (rising) {
            final int jumpTarget = 1716;
            if (level < (jumpTarget << 16)) {
              level = jumpTarget << 16;
            }
            level += (((17 << 24) - level) >> 24) * inc * n;
            if (level >= targetLevel) {
              level = targetLevel;
              advance(patch, opOff, ix + 1, extraRate);
            }
          } else {
            level -= inc * n;
            if (level <= targetLevel) {
              level = targetLevel;
              advance(patch, opOff, ix + 1, extraRate);
            }
          }
        }
      }
      return level;
    }

    // keydown (env.cpp:91-96)
    public void keydown(byte[] patch, int opOff, boolean d) {
      if (down != d) {
        down = d;
        advance(patch, opOff, d ? 0 : 3, 0);
      }
    }

    // scaleoutlevel (env.cpp:98-100)
    static int scaleOutlevel(int outlevel) {
      return outlevel >= 20 ? 28 + outlevel : LEVEL_LUT[outlevel];
    }

    // advance (env.cpp:102-140)
    void advance(byte[] patch, int opOff, int newix, int extraRate) {
      ix = newix;
      if (ix < 4) {
        int newlevel = patch[opOff + 4 + ix] & 0xFF;
        int actuallevel = scaleOutlevel(newlevel) >> 1;
        actuallevel = (actuallevel << 6) + outlevel - 4256;
        if (actuallevel < 16) actuallevel = 16;
        targetLevel = actuallevel << 16;
        rising = (targetLevel > level);

        int qrate = ((patch[opOff + ix] & 0xFF) * 41) >> 6;
        qrate += rateScaling + extraRate;
        if (qrate > 63) qrate = 63;

        if (targetLevel == level || (ix == 0 && newlevel == 0)) {
          int staticrate = (patch[opOff + ix] & 0xFF) + rateScaling + extraRate;
          if (staticrate > 99) staticrate = 99;
          staticCount = staticrate < STATICS.length ? STATICS[staticrate] : 20 * (99 - staticrate);
          if (staticrate < STATICS.length && ix == 0 && newlevel == 0) {
            staticCount /= 20;
          }
          staticCount = (int) (((long) staticCount * (long) SR_MULTIPLIER) >> 24);
        } else {
          staticCount = 0;
        }
        inc = ((4 + (qrate & 3)) << (2 + (qrate >> 2)));
        inc = (int) (((long) inc * (long) SR_MULTIPLIER) >> 24);
      }
    }

    // update (env.cpp:142-154)
    public void update(byte[] patch, int opOff, int ol, int rateScaling) {
      outlevel = ol;
      this.rateScaling = rateScaling;
      if (down) {
        int newlevel = patch[opOff + 2] & 0xFF;
        int actuallevel = scaleOutlevel(newlevel) >> 1;
        actuallevel = (actuallevel << 6) - 4256;
        if (actuallevel < 16) actuallevel = 16;
        targetLevel = actuallevel << 16;
        advance(patch, opOff, 2, 0);
      }
    }

    public void transfer(Dx7Env src) {
      level = src.level;
      targetLevel = src.targetLevel;
      rising = src.rising;
      ix = src.ix;
      down = src.down;
      staticCount = src.staticCount;
      inc = src.inc;
    }
  }

  // ═══════════════════════════════════════════════════════════════
  // PitchEnv (pitchenv.cpp/h)
  // ═══════════════════════════════════════════════════════════════

  public static class PitchEnv {
    static final int[] RATE = {
      1, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13, 14, 14, 15,
      16, 16, 17, 18, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 30, 31, 33, 34, 36, 37, 38, 39,
      41, 42, 44, 46, 47, 49, 51, 53, 54, 56, 58, 60, 62, 64, 66, 68, 70, 72, 74, 76, 79, 82, 85,
      88, 91, 94, 98, 102, 106, 110, 115, 120, 125, 130, 135, 141, 147, 153, 159, 165, 171, 178,
      185, 193, 202, 211, 232, 243, 254, 255,
    };
    static final int[] TAB = {
      -128, -116, -104, -95, -85, -76, -68, -61, -56, -52, -49, -46, -43, -41, -39, -37, -35, -33,
      -32, -31, -30, -29, -28, -27, -26, -25, -24, -23, -22, -21, -20, -19, -18, -17, -16, -15, -14,
      -13, -12, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11,
      12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34,
      35, 38, 40, 43, 46, 49, 53, 58, 65, 73, 82, 92, 103, 115, 127,
    };
    static int UNIT = (int) ((1 << 24) / (21.3 * 44100.0) + 0.5);

    int level;
    int targetLevel;
    boolean rising;
    int ix;
    int inc;
    boolean down = true;

    /** set (pitchenv.cpp:39-43) */
    public void set(byte[] patch, int off) {
      // C pitchenv.cpp:40 — level_ = pitchenv_tab[levels[3]] << 19. In the packed voice the pitch
      // EG
      // is rates[0..3] at off+0..3 then levels[0..3] at off+4..7, so levels[3] is off+7 (NOT off+3,
      // which is rate[3] — that swap pinned a neutral env to TAB[99]=+127 ≈ +4 octaves).
      level = TAB[patch[off + 4 + 3] & 0xFF] << 19;
      down = true;
      advance(patch, off, 0);
    }

    /** getsample (pitchenv.cpp:45-63) */
    public int getSample(byte[] patch, int off, int n) {
      if (ix < 3 || ((ix < 4) && !down)) {
        if (rising) {
          level += n * inc;
          if (level >= targetLevel) {
            level = targetLevel;
            advance(patch, off, ix + 1);
          }
        } else {
          level -= n * inc;
          if (level <= targetLevel) {
            level = targetLevel;
            advance(patch, off, ix + 1);
          }
        }
      }
      return level;
    }

    /** keydown (pitchenv.cpp:65-70) */
    public void keydown(byte[] patch, int off, boolean d) {
      if (down != d) {
        down = d;
        advance(patch, off, d ? 0 : 3);
      }
    }

    /** advance (pitchenv.cpp:72-80) */
    void advance(byte[] patch, int off, int newix) {
      ix = newix;
      if (ix < 4) {
        // C pitchenv.cpp:74-78 — target from levels[ix] (off+4+ix), rate from rates[ix] (off+ix).
        int newlevel = patch[off + 4 + ix] & 0xFF;
        targetLevel = TAB[newlevel] << 19;
        rising = (targetLevel > level);
        inc = RATE[patch[off + ix] & 0xFF] * UNIT;
      }
    }

    public boolean isDown() {
      return down;
    }
  }

  // ═══════════════════════════════════════════════════════════════
  // DxVoice (dx7note.cpp:220-474)
  // ═══════════════════════════════════════════════════════════════

  public final Dx7Env[] env = new Dx7Env[6];
  public final PitchEnv pitchEnv = new PitchEnv();
  final int[] phase = new int[6];
  final int[] gainOut = new int[6];
  final int[] basePitch = new int[6];
  final int[] fbBuf = new int[2];
  int delayState, delayInc, delayInc2;
  final short[] detunePerVoice = new short[6];
  byte[] patch; // raw 156-byte patch
  int randomDetuneScale;
  int lastVelocity;
  public Dx7Voice nextUnassigned;
  boolean preallocated;

  public final FmCore.FmOpParams[] params = new FmCore.FmOpParams[6];

  public Dx7Voice() {
    for (int i = 0; i < 6; i++) {
      env[i] = new Dx7Env();
      params[i] = new FmCore.FmOpParams();
    }
  }

  // init (dx7note.cpp:229-291)
  public void init(DxPatch newp, int midinote, int velocity) {
    patch = newp.params;
    randomDetuneScale = newp.randomDetune;
    lastVelocity = velocity;
    int logFreq = dxNoteToFreq(midinote);

    for (int op = 0; op < 6; op++) {
      int off = op * 21;
      int outlevel = patch[off + 16] & 0xFF;
      outlevel = Dx7Env.scaleOutlevel(outlevel);
      int levelScaling =
          scaleLevel(
              midinote,
              patch[off + 8] & 0xFF,
              patch[off + 9] & 0xFF,
              patch[off + 10] & 0xFF,
              patch[off + 11] & 0xFF,
              patch[off + 12] & 0xFF);
      outlevel += levelScaling;
      if (outlevel > 127) outlevel = 127;
      outlevel = outlevel << 5;
      outlevel += scaleVelocity(velocity, patch[off + 15] & 0xFF);
      if (outlevel < 0) outlevel = 0;
      int rateScaling = scaleRate(midinote, patch[off + 13] & 0xFF);
      env[op].init(patch, off, outlevel, rateScaling);

      int mode = patch[off + 17] & 0xFF;
      int coarse = patch[off + 18] & 0xFF;
      int fine = patch[off + 19] & 0xFF;
      int detune = patch[off + 20] & 0xFF;
      detunePerVoice[op] = (short) (Functions.getNoise() >> 16);
      int freq = oscFreq(logFreq, mode, coarse, fine, detune, detunePerVoice[op]);
      basePitch[op] = freq;
    }
    pitchEnv.set(patch, 126);

    if ((patch[141] & 0xFF) != 0) {
      newp.lfoPhase = Integer.MAX_VALUE; // dx7note.cpp:267 — (1U << 31) - 1 = 0x7FFFFFFF
    }
    if ((patch[136] & 0xFF) != 0) oscSync();
    else oscUnSync();

    int a = 99 - (patch[138] & 0xFF);
    if (a == 99) {
      delayInc = -1;
      delayInc2 = -1;
    } else {
      a = (16 + (a & 15)) << (1 + (a >> 4));
      delayInc = DxPatch.LFO_UNIT * a;
      a &= 0xFF80;
      if (a < 0x80) a = 0x80;
      delayInc2 = DxPatch.LFO_UNIT * a;
    }
    delayState = 0;
  }

  // osc_freq (dx7note.cpp:130-159)
  int oscFreq(int logFreqForDetune, int mode, int coarse, int fine, int detune, int randomDetune) {
    int logfreq;
    if (mode == 0) {
      logfreq = 0;
      double detuneRatio = 0.0209 * Math.exp(-0.396 * (logFreqForDetune / (double) (1 << 24))) / 7;
      int randomScaled = (randomDetune * randomDetuneScale) >> 17;
      logfreq += (int) (detuneRatio * logFreqForDetune * (detune - 7 + randomScaled));
      logfreq += COARSE_MUL[coarse & 31];
      if (fine != 0) {
        logfreq += (int) Math.floor(24204406.323 * Math.log(1 + 0.01 * fine) + 0.5);
      }
    } else {
      logfreq = (4458616 * ((coarse & 3) * 100 + fine)) >> 3;
      if (detune > 7) logfreq += 13457 * (detune - 7);
    }
    return logfreq;
  }

  // getdelay (dx7note.cpp:293-306)
  int getDelay(int n) {
    // dx7note.cpp:294 — "delaystate_ < (1U << 31)" on uint32 means the top bit is clear.
    long delta = (delayState >= 0) ? (delayInc & 0xFFFFFFFFL) : (delayInc2 & 0xFFFFFFFFL);
    long d = (delayState & 0xFFFFFFFFL) + delta * n;
    if (d > 0xFFFFFFFFL) return 1 << 24;
    delayState = (int) d;
    if (d < (1L << 31)) return 0;
    return ((int) (d >> 7)) & ((1 << 24) - 1);
  }

  // compute (dx7note.cpp:308-394)
  public boolean compute(
      int[] buf, int n, int basePitchIn, DxPatch ctrls, int ampMod, int velMod, int rateMod) {
    int lfoDelay = getDelay(n);
    int lfoVal = ctrls.lfoValue;

    // Pitch
    int pitchModDepth = ((patch[139] & 0xFF) * 165) >> 6;
    int pitchModSens = PITCH_MOD_SENS[patch[143] & 7];
    long pmd = (pitchModDepth & 0xFFFFFFFFL) * (lfoDelay & 0xFFFFFFFFL);
    int sensLfo = pitchModSens * (lfoVal - (1 << 23));
    int pmod1 = (int) (((long) sensLfo * pmd) >> 39);
    pmod1 = Math.abs(pmod1);
    int pmod2 = 0; // patch cable input
    int pitchMod = Math.max(pmod1, pmod2);
    pitchMod = pitchEnv.getSample(patch, 126, n) + (pitchMod * (sensLfo < 0 ? -1 : 1));
    pitchMod += basePitchIn;

    // Amp mod
    lfoVal = (1 << 24) - lfoVal;
    int ampModDepth = ((patch[140] & 0xFF) * 165) >> 6;
    long amod1 = ((ampModDepth & 0xFFFFFFFFL) * (lfoDelay & 0xFFFFFFFFL)) >> 8;
    amod1 = (amod1 * (lfoVal & 0xFFFFFFFFL)) >> 24;
    long amod2 = 0; // patch cable
    long amdMod = Math.max(amod1, amod2);
    long amod3 = (long) (ctrls.egMod + 1) << 17;
    amdMod = Math.max((1L << 24) - amod3, amdMod);

    FmCore.FmOpParams[] params = this.params;

    for (int op = 0; op < 6; op++) {
      params[op].phase = phase[op];
      params[op].gain_out = gainOut[op];
      if (!ctrls.opSwitch(op)) {
        env[op].getSample(patch, op * 21, n, 0);
        params[op].level_in = 0;
        params[op].freq = 0;
      } else {
        int off = op * 21;
        int mode = patch[off + 17] & 0xFF;
        if (mode != 0) {
          params[op].freq = freqLookup(basePitch[op]);
        } else {
          params[op].freq = freqLookup(basePitch[op] + pitchMod);
        }
        int level = env[op].getSample(patch, off, n, rateMod);
        int ampModSens = AMP_MOD_SENS[patch[off + 14] & 3];
        if (ampModSens != 0) {
          long sensamp = ((amdMod & 0xFFFFFFFFL) * (ampModSens & 0xFFFFFFFFL)) >> 24;
          long pt = (long) Math.exp((sensamp / 262144.0) * 0.07 + 12.2);
          long ldiff = ((level & 0xFFFFFFFFL) * (pt << 4)) >> 28;
          level -= (int) ldiff;
          level += ((ampModSens >> 16) * ampMod);
        }
        level += (patch[off + 15] & 0xFF) * velMod;
        params[op].level_in = level;
      }
    }
    int algorithm = patch[134] & 0xFF;
    int feedback = patch[135] & 0xFF;
    int fbShift = feedback != 0 ? FEEDBACK_BITDEPTH - feedback : 16;
    // ctrls->core->render (dx7note.cpp:382). Static dispatch: MkI vs the modern FmCore.
    if (ctrls.useMkI) {
      EngineMkI.render(buf, n, params, algorithm, fbBuf, fbShift);
    } else {
      FmCore.render(buf, n, params, algorithm, fbBuf, fbShift);
    }

    boolean anyActive = false;
    for (int op = 0; op < 6; op++) {
      phase[op] = params[op].phase;
      gainOut[op] = params[op].gain_out;
      if (Integer.compareUnsigned(params[op].gain_out, FmCore.K_GAIN_LEVEL_THRESH) >= 0)
        anyActive = true;
    }
    return pitchEnv.isDown() || anyActive;
  }

  /** Freqlut::lookup — log-freq to phase increment (faithful, math_lut.cpp:107-116). */
  static int freqLookup(int logFreq) {
    return Dx7Tables.freqLookup(logFreq);
  }

  // keyup (dx7note.cpp:396-401)
  public void keyup() {
    for (int op = 0; op < 6; op++) env[op].keydown(patch, op * 21, false);
    pitchEnv.keydown(patch, 126, false);
  }

  // updateBasePitches (dx7note.cpp:403-412)
  public void updateBasePitches(int logFreqForDetune) {
    for (int op = 0; op < 6; op++) {
      int off = op * 21;
      basePitch[op] =
          oscFreq(
              logFreqForDetune,
              patch[off + 17] & 0xFF,
              patch[off + 18] & 0xFF,
              patch[off + 19] & 0xFF,
              patch[off + 20] & 0xFF,
              detunePerVoice[op]);
    }
  }

  // update (dx7note.cpp:414-443)
  public void update(DxPatch newp, int midinote) {
    patch = newp.params;
    randomDetuneScale = newp.randomDetune;
    int logFreq = dxNoteToFreq(midinote);
    int velocity = lastVelocity;
    for (int op = 0; op < 6; op++) {
      int off = op * 21;
      basePitch[op] =
          oscFreq(
              logFreq,
              patch[off + 17] & 0xFF,
              patch[off + 18] & 0xFF,
              patch[off + 19] & 0xFF,
              patch[off + 20] & 0xFF,
              detunePerVoice[op]);
      int outlevel = patch[off + 16] & 0xFF;
      outlevel = Dx7Env.scaleOutlevel(outlevel);
      int levelScaling =
          scaleLevel(
              midinote,
              patch[off + 8] & 0xFF,
              patch[off + 9] & 0xFF,
              patch[off + 10] & 0xFF,
              patch[off + 11] & 0xFF,
              patch[off + 12] & 0xFF);
      outlevel += levelScaling;
      if (outlevel > 127) outlevel = 127;
      outlevel = outlevel << 5;
      outlevel += scaleVelocity(velocity, patch[off + 15] & 0xFF);
      if (outlevel < 0) outlevel = 0;
      int rateScaling = scaleRate(midinote, patch[off + 13] & 0xFF);
      env[op].update(patch, off, outlevel, rateScaling);
    }
  }

  public void transferState(Dx7Voice src) {
    for (int i = 0; i < 6; i++) {
      env[i].transfer(src.env[i]);
      gainOut[i] = src.gainOut[i];
      phase[i] = src.phase[i];
    }
  }

  public void transferSignal(Dx7Voice src) {
    for (int i = 0; i < 6; i++) {
      gainOut[i] = src.gainOut[i];
      phase[i] = src.phase[i];
    }
  }

  public void oscSync() {
    for (int i = 0; i < 6; i++) {
      gainOut[i] = 0;
      phase[i] = 0;
    }
  }

  public void oscUnSync() {
    for (int i = 0; i < 6; i++) {
      gainOut[i] = 0;
      phase[i] = Functions.getNoise();
    }
  }
}
