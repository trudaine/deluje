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
 * Sample-exact bit-diff of the Java {@link Dx7Voice.Dx7Env} (DX7 per-operator envelope) against
 * golden buffers emitted by a standalone C harness that links the <em>real</em> firmware {@code
 * env.cpp} on desktop (sources: {@code tools/env_harness/}).
 *
 * <p>The DX7 operator envelope controls operator amplitude over time — hence FM sideband
 * brightness/decay — so it is the prime non-kernel suspect for the FM residual (081 Xylophone). The
 * FM op kernel is already proven bit-exact ({@link FmKernelGoldenBufferTest}) and {@code
 * dx7note.cpp} is ARM-SIMD-blocked, but {@code env.cpp} is self-contained (only {@code <math.h>}),
 * pure integer per-sample, and compiles clean on desktop — so it IS a valid bit-exact target. Both
 * sides use {@code sr_multiplier == 1<<24} at 44100.
 *
 * <p>Golden files: {@code src/test/resources/fidelity/dxenv/}; regenerate with {@code
 * tools/env_harness/build.sh}.
 */
@Tag("slow")
class Dx7EnvGoldenBufferTest {

  private static final int NSAMP = 512;
  private static final int N = 64; // subsample block
  private static final int KEYOFF_AT = 300;

  /** rates[4], levels[4], outlevel, rateScaling. */
  private record Case(String file, int[] rates, int[] levels, int outlevel, int rateScaling) {}

  private static java.util.stream.Stream<Arguments> cases() {
    return java.util.stream.Stream.of(
            new Case(
                "c_env_default.bin",
                new int[] {95, 60, 40, 70},
                new int[] {99, 85, 70, 0},
                3168,
                20),
            new Case(
                "c_env_slow.bin", new int[] {20, 30, 25, 40}, new int[] {99, 90, 80, 0}, 3168, 0),
            new Case(
                "c_env_fast.bin", new int[] {99, 99, 99, 99}, new int[] {99, 99, 99, 0}, 4256, 40))
        .map(c -> Arguments.of(c.file, c));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void javaDx7EnvMatchesCGolden(String name, Case c) throws IOException {
    int[] expected = readGolden(c.file());
    Assumptions.assumeTrue(expected != null, "missing golden resource: " + c.file());
    assertEquals(NSAMP, expected.length, "golden size");

    // Java Dx7Env reads rates from patch[opOff+0..3], levels from patch[opOff+4..7].
    byte[] patch = new byte[8];
    for (int i = 0; i < 4; i++) {
      patch[i] = (byte) c.rates()[i];
      patch[4 + i] = (byte) c.levels()[i];
    }

    Dx7Voice.Dx7Env env = new Dx7Voice.Dx7Env();
    env.init(patch, 0, c.outlevel(), c.rateScaling());

    int[] out = new int[NSAMP];
    for (int i = 0; i < NSAMP; i++) {
      if (i == KEYOFF_AT) env.keydown(patch, 0, false);
      out[i] = env.getSample(patch, 0, N, 0);
    }

    int firstDiff = -1;
    long maxAbs = 0;
    for (int i = 0; i < NSAMP; i++) {
      long d = Math.abs((long) out[i] - (long) expected[i]);
      if (d != 0 && firstDiff < 0) firstDiff = i;
      if (d > maxAbs) maxAbs = d;
    }
    System.out.printf("[%-20s] firstDiff=%d maxAbsDiff=%d%n", c.file(), firstDiff, maxAbs);
    final int fd = Math.max(firstDiff, 0);
    final int jv = out[fd];
    final int gv = expected[fd];
    assertEquals(
        0L,
        maxAbs,
        () ->
            "Java DX7 envelope diverges from C golden "
                + c.file()
                + " at sample "
                + fd
                + " (java="
                + jv
                + " golden="
                + gv
                + ")");
  }

  private static int[] readGolden(String file) throws IOException {
    try (InputStream in =
        Dx7EnvGoldenBufferTest.class.getResourceAsStream("/fidelity/dxenv/" + file)) {
      if (in == null) return null;
      byte[] raw = in.readAllBytes();
      ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
      int[] out = new int[raw.length / 4];
      for (int i = 0; i < out.length; i++) out[i] = bb.getInt();
      return out;
    }
  }
}
