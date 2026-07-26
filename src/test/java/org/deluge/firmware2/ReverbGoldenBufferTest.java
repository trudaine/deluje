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
 * Sample-exact bit-diff of the Java {@link Freeverb} (the FREEVERB reverb model) against golden
 * buffers emitted by a standalone C harness that links the <em>real</em> firmware {@code
 * freeverb.cpp} on desktop (sources + build script: {@code tools/ladder_harness/}).
 *
 * <p>The freeverb per-sample path is pure integer (comb/allpass {@code
 * multiply_32x32_rshift32_rounded}); its setup ({@code update()}) uses only deterministic float
 * arithmetic ({@code *}, {@code /}, {@code -} — no transcendentals), and both sides use {@code
 * (float)INT32_MAX == 2147483648.0f}, so it is a valid bit-exact target (unlike the compressor,
 * whose audio path depends on libm exp/log — see §4.2vicessemel).
 *
 * <p>Buffers are 4096 frames — the comb delays are ~1116-1617 samples, so a shorter window yields
 * all-zero output before any tail emerges. Interleaved L,R int32. Golden files: {@code
 * src/test/resources/fidelity/reverb/}; regenerate with {@code tools/ladder_harness/build.sh}.
 */
@Tag("slow")
class ReverbGoldenBufferTest {

  private static final int AMP = 1 << 27;
  private static final int NSAMP = 4096;

  private record Case(String file, float room, float damp, float width, String signal) {}

  private static java.util.stream.Stream<Arguments> cases() {
    return java.util.stream.Stream.of(
            new Case("c_reverb_r70_d50_w100_impulse.bin", 0.7f, 0.5f, 1.0f, "impulse"),
            new Case("c_reverb_r90_d20_w100_impulse.bin", 0.9f, 0.2f, 1.0f, "impulse"),
            new Case("c_reverb_r50_d80_w50_impulse.bin", 0.5f, 0.8f, 0.5f, "impulse"),
            new Case("c_reverb_r70_d50_w100_square.bin", 0.7f, 0.5f, 1.0f, "square"))
        .map(c -> Arguments.of(c.file, c));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void javaFreeverbMatchesCGolden(String name, Case c) throws IOException {
    int[] expected = readGolden(c.file()); // interleaved L,R
    Assumptions.assumeTrue(expected != null, "missing golden resource: " + c.file());
    assertEquals(NSAMP * 2, expected.length, "golden size");

    int[] input = makeInput(c.signal());
    int[][] out = new int[NSAMP][2];
    Freeverb rv = new Freeverb();
    rv.setRoomSize(c.room());
    rv.setDamping(c.damp());
    rv.setWidth(c.width());
    rv.setPanLevels(1 << 30, 1 << 30); // same fixed centered pan as the C harness
    rv.process(input, out);

    int firstDiff = -1;
    long maxAbs = 0;
    for (int i = 0; i < NSAMP; i++) {
      for (int ch = 0; ch < 2; ch++) {
        long d = Math.abs((long) out[i][ch] - (long) expected[2 * i + ch]);
        if (d != 0 && firstDiff < 0) firstDiff = 2 * i + ch;
        if (d > maxAbs) maxAbs = d;
      }
    }
    System.out.printf("[%-34s] firstDiff=%d maxAbsDiff=%d%n", c.file(), firstDiff, maxAbs);
    final int fd = Math.max(firstDiff, 0);
    final int jv = out[fd / 2][fd % 2];
    final int gv = expected[fd];
    assertEquals(
        0L,
        maxAbs,
        () ->
            "Java Freeverb diverges from C golden "
                + c.file()
                + " at interleaved index "
                + fd
                + " (java="
                + jv
                + " golden="
                + gv
                + ")");
  }

  private static int[] readGolden(String file) throws IOException {
    try (InputStream in =
        ReverbGoldenBufferTest.class.getResourceAsStream("/fidelity/reverb/" + file)) {
      if (in == null) return null;
      byte[] raw = in.readAllBytes();
      ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
      int[] out = new int[raw.length / 4];
      for (int i = 0; i < out.length; i++) out[i] = bb.getInt();
      return out;
    }
  }

  private static int[] makeInput(String signal) {
    int[] buf = new int[NSAMP];
    for (int i = 0; i < NSAMP; i++) {
      buf[i] =
          switch (signal) {
            case "step" -> AMP;
            case "impulse" -> (i == 0) ? AMP : 0;
            // "square": pure-integer sustained signal at reduced amplitude — MUST match the C
            // harness bit-for-bit. In-range (full-scale sustained drive overflows the reverb's
            // integer accumulators, where C signed-overflow UB and Java wrap legitimately differ).
            default -> (((i / 16) & 1) != 0) ? -(AMP >> 4) : (AMP >> 4);
          };
    }
    return buf;
  }
}
