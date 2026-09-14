package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Bit-diff of the Java {@link Reverb.MutableModel} and {@link Reverb.DigitalModel} against golden
 * buffers from a C harness that compiles the real firmware {@code dsp/reverb/mutable.hpp} and
 * {@code digital.hpp} ({@code tools/mutable_reverb_harness/}).
 *
 * <p><b>Why.</b> Reverb golden coverage used to be Freeverb only. With nothing checking Mutable or
 * Digital, the Digital model's right channel ran on the LEFT channel's one-pole filter state for as
 * long as the port existed — ported verbatim from a C bug upstream fixed in {@code f36ae0809}. Left
 * and right are asserted separately so a channel-crossing defect points at the channel.
 *
 * <p><b>Parameter choice.</b> These models are float DSP whose only libm calls are in setup ({@code
 * log2} in {@code setDamping}, {@code exp} in the filter cutoffs), where Java's {@code Math} need
 * not agree with glibc at the last ULP. Damping 0 and HPF 0 avoid both; LPF 1.0 is the single
 * remaining {@code exp}, and bit-exactness here shows it agrees at that point.
 *
 * <p>Goldens: {@code src/test/resources/fidelity/reverb/c_{mutable,digital}_*}; regenerate with
 * {@code tools/mutable_reverb_harness/build.sh}.
 */
@Tag("slow")
class MutableReverbGoldenBufferTest {

  private static final int NSAMP = 8192;
  private static final int BLOCK = 128;
  private static final int AMP = 1 << 27;

  private record Case(
      String file, boolean digital, float room, float damp, float width, String signal) {}

  private static java.util.stream.Stream<Arguments> cases() {
    java.util.List<Case> cs = new java.util.ArrayList<>();
    for (boolean digital : new boolean[] {true, false}) {
      String m = digital ? "digital" : "mutable";
      cs.add(new Case("c_" + m + "_r70_d00_w100_impulse.bin", digital, 0.7f, 0f, 1.0f, "impulse"));
      cs.add(new Case("c_" + m + "_r90_d00_w50_impulse.bin", digital, 0.9f, 0f, 0.5f, "impulse"));
      cs.add(new Case("c_" + m + "_r70_d00_w100_square.bin", digital, 0.7f, 0f, 1.0f, "square"));
    }
    return cs.stream().map(c -> Arguments.of(c.file(), c));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void javaMatchesCGolden(String name, Case c) throws IOException {
    int[] golden = readGolden(c.file());
    Assumptions.assumeTrue(golden != null, "missing golden resource: " + c.file());
    assertEquals(NSAMP * 2, golden.length, "golden size (interleaved L,R)");

    Reverb.MutableModel rv = c.digital() ? new Reverb.DigitalModel() : new Reverb.MutableModel();
    rv.setRoomSize(c.room());
    rv.setDamping(c.damp());
    rv.setWidth(c.width());
    rv.setHPF(0f);
    rv.setLPF(1.0f);
    rv.setPanLevels(1 << 30, 1 << 30);

    int[] in = new int[NSAMP];
    for (int i = 0; i < NSAMP; i++) {
      in[i] =
          "impulse".equals(c.signal())
              ? (i == 0 ? AMP : 0)
              : (((i / 16) & 1) != 0 ? -(AMP >> 4) : (AMP >> 4));
    }

    int[] outL = new int[NSAMP];
    int[] outR = new int[NSAMP];
    for (int pos = 0; pos < NSAMP; pos += BLOCK) {
      int n = Math.min(BLOCK, NSAMP - pos);
      int[] blockIn = new int[n];
      System.arraycopy(in, pos, blockIn, 0, n);
      int[][] blockOut = new int[n][2];
      rv.process(blockIn, blockOut, n);
      for (int i = 0; i < n; i++) {
        outL[pos + i] = blockOut[i][0];
        outR[pos + i] = blockOut[i][1];
      }
    }

    int[] diffL = diff(outL, golden, 0);
    int[] diffR = diff(outR, golden, 1);
    System.out.printf(
        "[%-36s] L firstDiff=%d maxAbs=%d | R firstDiff=%d maxAbs=%d%n",
        c.file(), diffL[0], diffL[1], diffR[0], diffR[1]);

    assertEquals(0, diffL[1], () -> "LEFT channel diverges from C " + c.file() + " at " + diffL[0]);
    assertEquals(
        0, diffR[1], () -> "RIGHT channel diverges from C " + c.file() + " at " + diffR[0]);
  }

  /** {first differing frame (-1 if none), max absolute difference} for one channel. */
  private static int[] diff(int[] ours, int[] golden, int ch) {
    int first = -1;
    long max = 0;
    for (int i = 0; i < ours.length; i++) {
      long d = Math.abs((long) ours[i] - golden[2 * i + ch]);
      if (d != 0 && first < 0) first = i;
      max = Math.max(max, d);
    }
    return new int[] {first, (int) Math.min(max, Integer.MAX_VALUE)};
  }

  private static int[] readGolden(String file) throws IOException {
    try (InputStream is =
        MutableReverbGoldenBufferTest.class.getResourceAsStream("/fidelity/reverb/" + file)) {
      if (is == null) return null;
      byte[] raw = is.readAllBytes();
      ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
      int[] out = new int[raw.length / 4];
      for (int i = 0; i < out.length; i++) out[i] = bb.getInt();
      return out;
    }
  }
}
