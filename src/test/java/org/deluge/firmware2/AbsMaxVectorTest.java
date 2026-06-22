package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The Vector-API abs-max reduction in GlobalEffectable must be bit-identical to the scalar
 * Math.abs/Math.max loop it replaced (it gates the silence early-out, so any divergence changes
 * audio behavior). Checks random data, every length around the SIMD lane boundary, and the
 * Math.abs(MIN_VALUE)==MIN_VALUE edge.
 */
class AbsMaxVectorTest {

  private static int scalarAbsMax(int[] a, int len) {
    int max = 0;
    for (int i = 0; i < len; i++) {
      max = Math.max(max, Math.abs(a[i]));
    }
    return max;
  }

  @Test
  void matchesScalarAcrossLengthsAndData() {
    Random r = new Random(42);
    for (int trial = 0; trial < 200; trial++) {
      int len = r.nextInt(300); // spans 0 and many non-lane-multiple lengths
      int[] a = new int[Math.max(len, 1)];
      for (int i = 0; i < len; i++) {
        int kind = r.nextInt(10);
        a[i] =
            switch (kind) {
              case 0 -> Integer.MIN_VALUE;
              case 1 -> Integer.MAX_VALUE;
              case 2 -> 0;
              default -> r.nextInt() >> r.nextInt(31);
            };
      }
      assertEquals(
          scalarAbsMax(a, len),
          GlobalEffectable.absMax(a, len),
          "absMax mismatch at trial " + trial + " len " + len);
    }
  }

  @Test
  void edgeCasesExact() {
    assertEquals(0, GlobalEffectable.absMax(new int[] {0, 0, 0}, 3));
    assertEquals(
        Integer.MAX_VALUE, GlobalEffectable.absMax(new int[] {5, Integer.MAX_VALUE, -3}, 3));
    // Math.abs(MIN_VALUE) is MIN_VALUE (negative) in both paths, so it never wins the max.
    assertEquals(7, GlobalEffectable.absMax(new int[] {Integer.MIN_VALUE, 7, -2}, 3));
    assertEquals(0, GlobalEffectable.absMax(new int[0], 0));
  }
}
