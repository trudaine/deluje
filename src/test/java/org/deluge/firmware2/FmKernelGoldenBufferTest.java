package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Sample-exact bit-diff of the Java {@link FmCore} FM operator kernel (computeFb / computePure /
 * computeNormal) against golden buffers emitted by a standalone C harness that links the
 * <em>real</em> firmware {@code fm_op_kernel.cpp} + {@code math_lut.cpp} on desktop (sources:
 * {@code tools/fm_harness/}).
 *
 * <p>The FM op kernel is the FM sideband generator — the exact place a "too-bright FM" divergence
 * would live. The harness fills the real firmware {@code sintab} via {@code dx_init_lut_data()}, so
 * {@code Sin::lookup} on the C side and {@link Dx7Tables#sinLookup} on the Java side are compared
 * on identical ground. The kernel is pure phase/gain math with no PRNG, so no seeding is needed.
 * The Java compute methods are {@code private static}, invoked here by reflection.
 *
 * <p>Golden files live under {@code src/test/resources/fidelity/fm/} (committed, 2&nbsp;KB each);
 * regenerate with {@code tools/fm_harness/build.sh}. Params below MUST mirror {@code main_fm.cpp}.
 */
@Tag("slow")
class FmKernelGoldenBufferTest {

  private static final int N = 512;
  private static final int PHASE0 = 0;
  private static final int FREQ = 0x004ec4ec; // 5162220
  private static final int GAIN1 = 1 << 22;
  private static final int GAIN2 = 1 << 22;
  private static final int DGAIN = 0;
  private static final int FB_SHIFT = 2;

  @Test
  void fmFeedbackMatchesCGolden() throws Exception {
    int[] expected = readGolden("c_fm_fb.bin");
    Assumptions.assumeTrue(expected != null, "missing golden: c_fm_fb.bin");
    int[] out = new int[N];
    FmCore.FmOpParams p = params();
    Method m =
        FmCore.class.getDeclaredMethod(
            "computeFb",
            int[].class,
            int.class,
            FmCore.FmOpParams.class,
            int.class,
            int.class,
            int.class,
            int[].class,
            int.class,
            boolean.class);
    m.setAccessible(true);
    m.invoke(null, out, N, p, GAIN1, GAIN2, DGAIN, new int[] {0, 0}, FB_SHIFT, false);
    assertBitExact("fb", out, expected);
  }

  @Test
  void fmPureMatchesCGolden() throws Exception {
    int[] expected = readGolden("c_fm_pure.bin");
    Assumptions.assumeTrue(expected != null, "missing golden: c_fm_pure.bin");
    int[] out = new int[N];
    FmCore.FmOpParams p = params();
    Method m =
        FmCore.class.getDeclaredMethod(
            "computePure",
            int[].class,
            int.class,
            FmCore.FmOpParams.class,
            int.class,
            int.class,
            int.class,
            boolean.class);
    m.setAccessible(true);
    m.invoke(null, out, N, p, GAIN1, GAIN2, DGAIN, false);
    assertBitExact("pure", out, expected);
  }

  @Test
  void fmModulatedMatchesCGolden() throws Exception {
    int[] expected = readGolden("c_fm_mod.bin");
    Assumptions.assumeTrue(expected != null, "missing golden: c_fm_mod.bin");
    int[] out = new int[N];
    int[] in = new int[N];
    for (int i = 0; i < N; i++) in[i] = (int) ((long) i * 0x00300000);
    FmCore.FmOpParams p = params();
    Method m =
        FmCore.class.getDeclaredMethod(
            "computeNormal",
            int[].class,
            int.class,
            int[].class,
            FmCore.FmOpParams.class,
            int.class,
            int.class,
            int.class,
            boolean.class);
    m.setAccessible(true);
    m.invoke(null, out, N, in, p, GAIN1, GAIN2, DGAIN, false);
    assertBitExact("mod", out, expected);
  }

  private static FmCore.FmOpParams params() {
    FmCore.FmOpParams p = new FmCore.FmOpParams();
    p.phase = PHASE0;
    p.freq = FREQ;
    return p;
  }

  private static void assertBitExact(String label, int[] out, int[] expected) {
    int firstDiff = -1;
    long maxAbs = 0;
    for (int i = 0; i < N; i++) {
      long d = Math.abs((long) out[i] - (long) expected[i]);
      if (d != 0 && firstDiff < 0) firstDiff = i;
      if (d > maxAbs) maxAbs = d;
    }
    System.out.printf("[fm %-4s] firstDiff=%d maxAbsDiff=%d%n", label, firstDiff, maxAbs);
    final int fd = Math.max(firstDiff, 0);
    final int jv = out[fd];
    final int gv = expected[fd];
    assertEquals(
        0L,
        maxAbs,
        () ->
            "Java FM kernel ("
                + label
                + ") diverges from C golden at sample "
                + fd
                + " (java="
                + jv
                + " golden="
                + gv
                + ")");
  }

  private static int[] readGolden(String file) throws IOException {
    try (InputStream in =
        FmKernelGoldenBufferTest.class.getResourceAsStream("/fidelity/fm/" + file)) {
      if (in == null) return null;
      byte[] raw = in.readAllBytes();
      ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
      int[] o = new int[raw.length / 4];
      for (int i = 0; i < o.length; i++) o[i] = bb.getInt();
      return o;
    }
  }
}
