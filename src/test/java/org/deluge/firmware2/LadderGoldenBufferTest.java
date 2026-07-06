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
 * Sample-exact bit-diff of the Java {@link LpLadderFilter} against golden buffers emitted by a
 * standalone C harness that links the <em>real</em> firmware {@code lpladder.cpp} on desktop
 * (sources + build script: {@code tools/ladder_harness/}).
 *
 * <p>This is the offline golden-buffer instrument the fidelity docs kept asking for: it settles
 * ladder parity at the bit level with no hardware in the loop, and it is the faithful method — the
 * harness reuses the real {@code lpladder.cpp} and the real {@code lookuptables.cpp} (so no lookup
 * table can be silently mis-transcribed), stubbing only {@code AudioEngine::cpuDireness} and a
 * couple of globals. The harness seeds its CONG PRNG ({@code jcong}) to the same value as {@link
 * Functions#resetNoiseSeed()} (380116160), so the noise-modulated ladder moveability is
 * deterministic and directly comparable.
 *
 * <p>Golden files live under {@code src/test/resources/fidelity/ladder/} and are committed
 * (2&nbsp;KB each). Regenerate them with {@code tools/ladder_harness/build.sh}.
 */
@Tag("slow")
class LadderGoldenBufferTest {

  private static final int AMP = 1 << 27;
  private static final int NSAMP = 512;

  /** One golden case: filename encodes mode + cutoff/1e6 + resonance/1e6 + signal. */
  private record Case(String file, FilterSet.FilterMode mode, int freq, int res, String signal) {}

  private static java.util.stream.Stream<Arguments> cases() {
    return java.util.stream.Stream.of(
            new Case("c_12db_f800_r1000_step.bin", m(0), 800000000, 1000000000, "step"),
            new Case("c_12db_f800_r1000_impulse.bin", m(0), 800000000, 1000000000, "impulse"),
            new Case("c_24db_f800_r1000_step.bin", m(1), 800000000, 1000000000, "step"),
            new Case("c_24db_f800_r1000_impulse.bin", m(1), 800000000, 1000000000, "impulse"),
            new Case("c_drive_f800_r1000_step.bin", m(2), 800000000, 1000000000, "step"),
            new Case("c_drive_f800_r1000_impulse.bin", m(2), 800000000, 1000000000, "impulse"),
            new Case("c_24db_f400_r2000_impulse.bin", m(1), 400000000, 2000000000, "impulse"),
            new Case("c_drive_f400_r2000_impulse.bin", m(2), 400000000, 2000000000, "impulse"),
            new Case("c_24db_f1500_r300_step.bin", m(1), 1500000000, 300000000, "step"))
        .map(c -> Arguments.of(c.file, c));
  }

  private static FilterSet.FilterMode m(int ordinal) {
    return FilterSet.FilterMode.values()[ordinal];
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void javaLadderMatchesCGolden(String name, Case c) throws IOException {
    int[] expected = readGolden(c.file());
    Assumptions.assumeTrue(expected != null, "missing golden resource: " + c.file());
    assertEquals(NSAMP, expected.length, "golden size");

    Functions.resetNoiseSeed(); // match the C harness jcong seed (380116160)
    int[] buf = makeInput(c.signal());
    LpLadderFilter filt = new LpLadderFilter();
    filt.reset(false);
    filt.configure(c.freq(), c.res(), c.mode(), 0, 0);
    filt.filterMono(buf, 0, NSAMP, 1);

    int firstDiff = -1;
    long maxAbs = 0;
    for (int i = 0; i < NSAMP; i++) {
      long d = Math.abs((long) buf[i] - (long) expected[i]);
      if (d != 0 && firstDiff < 0) firstDiff = i;
      if (d > maxAbs) maxAbs = d;
    }
    System.out.printf("[%-34s] firstDiff=%d maxAbsDiff=%d%n", c.file(), firstDiff, maxAbs);
    final int fd = Math.max(firstDiff, 0);
    final int jv = buf[fd];
    final int gv = expected[fd];
    assertEquals(
        0L,
        maxAbs,
        () ->
            "Java ladder diverges from C golden "
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
        LadderGoldenBufferTest.class.getResourceAsStream("/fidelity/ladder/" + file)) {
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
            default -> (int) (AMP * Math.sin(2.0 * Math.PI * i / 16.0));
          };
    }
    return buf;
  }
}
