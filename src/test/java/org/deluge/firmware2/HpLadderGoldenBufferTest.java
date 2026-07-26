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
 * Sample-exact bit-diff of the Java {@link HpLadderFilter} against golden buffers emitted by a
 * standalone C harness that links the <em>real</em> firmware {@code hpladder.cpp} on desktop
 * (sources + build script: {@code tools/ladder_harness/}, sibling of {@link
 * LadderGoldenBufferTest}).
 *
 * <p>Unlike the LP ladder, {@code hpladder.cpp} touches neither {@code AudioEngine::cpuDireness}
 * nor {@code getNoise()} (CONG), so the C output is fully deterministic with no PRNG-seed
 * coordination. The harness matches the firmware's FilterSet zeroing (memset) exactly, which is the
 * ground truth for the initial {@code hpfLastWorkingValue} state used by the antialiased tanh.
 *
 * <p>Golden files: {@code src/test/resources/fidelity/hpladder/} (committed, 2&nbsp;KB each);
 * regenerate with {@code tools/ladder_harness/build.sh}.
 */
@Tag("slow")
class HpLadderGoldenBufferTest {

  private static final int AMP = 1 << 27;
  private static final int NSAMP = 512;

  private record Case(String file, int freq, int res, String signal) {}

  private static java.util.stream.Stream<Arguments> cases() {
    return java.util.stream.Stream.of(
            new Case("c_hp_f800_r1000_step.bin", 800000000, 1000000000, "step"),
            new Case("c_hp_f800_r1000_impulse.bin", 800000000, 1000000000, "impulse"),
            new Case("c_hp_f400_r2000_impulse.bin", 400000000, 2000000000, "impulse"),
            new Case("c_hp_f1500_r300_step.bin", 1500000000, 300000000, "step"),
            new Case("c_hp_f600_r1900_sine.bin", 600000000, 1900000000, "sine"))
        .map(c -> Arguments.of(c.file, c));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void javaHpLadderMatchesCGolden(String name, Case c) throws IOException {
    int[] expected = readGolden(c.file());
    Assumptions.assumeTrue(expected != null, "missing golden resource: " + c.file());
    assertEquals(NSAMP, expected.length, "golden size");

    int[] buf = makeInput(c.signal());
    HpLadderFilter filt = new HpLadderFilter();
    filt.reset(false);
    filt.configure(c.freq(), c.res(), FilterSet.FilterMode.HPLADDER, 0, 0);
    filt.filterMono(buf, 0, NSAMP, 1);

    int firstDiff = -1;
    long maxAbs = 0;
    for (int i = 0; i < NSAMP; i++) {
      long d = Math.abs((long) buf[i] - (long) expected[i]);
      if (d != 0 && firstDiff < 0) firstDiff = i;
      if (d > maxAbs) maxAbs = d;
    }
    System.out.printf("[%-32s] firstDiff=%d maxAbsDiff=%d%n", c.file(), firstDiff, maxAbs);
    final int fd = Math.max(firstDiff, 0);
    final int jv = buf[fd];
    final int gv = expected[fd];
    assertEquals(
        0L,
        maxAbs,
        () ->
            "Java HP ladder diverges from C golden "
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
        HpLadderGoldenBufferTest.class.getResourceAsStream("/fidelity/hpladder/" + file)) {
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
