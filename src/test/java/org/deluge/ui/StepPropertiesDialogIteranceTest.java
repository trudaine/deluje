package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.model.Iterance;
import org.junit.jupiter.api.Test;

/**
 * Verifies 100% hardware-parity Step Iterance / Play Condition selection and bitmask cycle
 * evaluation in StepPropertiesDialog.
 */
public class StepPropertiesDialogIteranceTest {

  @Test
  public void testDefaultPlayConditionIsAlways1Of1() {
    StepPropertiesDialog dlg = new StepPropertiesDialog(null);
    Iterance cond = dlg.getPlayCondition();
    assertNotNull(cond);
    assertEquals(1, cond.divisor, "Default divisor should be 1");
    assertEquals(1, cond.iteranceStep, "Default iteranceStep bitmask should be 0b1");
    assertTrue(cond.passesCheck(0));
    assertTrue(cond.passesCheck(1));
    assertTrue(cond.passesCheck(99));
  }

  @Test
  public void testInitialConditionPreservedAndPackedToInt() {
    Iterance initCond = new Iterance((byte) 4, (byte) 0b0101); // 1st and 3rd cycle of 4
    StepPropertiesDialog dlg = new StepPropertiesDialog(null, 80, 0, 0, 100, 0.9, 0, initCond);

    Iterance retrieved = dlg.getPlayCondition();
    assertEquals(4, retrieved.divisor);
    assertEquals(0b0101, retrieved.iteranceStep);

    // Verify evaluation across 8 cycles (0..7)
    assertTrue(retrieved.passesCheck(0), "Cycle 0 (1st of 4) should pass");
    assertFalse(retrieved.passesCheck(1), "Cycle 1 (2nd of 4) should not pass");
    assertTrue(retrieved.passesCheck(2), "Cycle 2 (3rd of 4) should pass");
    assertFalse(retrieved.passesCheck(3), "Cycle 3 (4th of 4) should not pass");
    assertTrue(retrieved.passesCheck(4), "Cycle 4 (1st of next 4) should pass");

    int packed = dlg.getPlayConditionInt();
    assertEquals(((4 << 8) | 0b0101), packed, "Must match 16-bit packed Iterance format");
  }

  @Test
  public void testAllThirtySevenRealIterancePresetsAreExposed() throws Exception {
    // Regression: only 10 of the real hardware presets (C: lookuptables.cpp:506
    // iterancePresets -- FIRST, LAST, then divisor 2..8 every bit position, kNumIterancePresets=37)
    // were exposed in the dropdown; the others were reachable only via the generic Custom bitmask
    // editor, not as a named preset.
    java.lang.reflect.Field field = StepPropertiesDialog.class.getDeclaredField("ITERANCE_PRESETS");
    field.setAccessible(true);
    java.util.List<?> presets = (java.util.List<?>) field.get(null);
    // 37 real presets (FIRST, LAST, 1of2..8of8) + the "Always (1 of 1)" convenience entry = 38.
    assertEquals(38, presets.size());

    java.lang.reflect.Method divisorM = presets.get(0).getClass().getDeclaredMethod("divisor");
    java.lang.reflect.Method maskM = presets.get(0).getClass().getDeclaredMethod("mask");
    divisorM.setAccessible(true);
    maskM.setAccessible(true);

    // FIRST (0, 1) and LAST (0, 2) must be present.
    assertEquals(0, (int) divisorM.invoke(presets.get(1)));
    assertEquals(1, (int) maskM.invoke(presets.get(1)));
    assertEquals(0, (int) divisorM.invoke(presets.get(2)));
    assertEquals(2, (int) maskM.invoke(presets.get(2)));

    int total = 0;
    for (int divisor = 2; divisor <= 8; divisor++) {
      for (int step = 1; step <= divisor; step++) {
        int mask = 1 << (step - 1);
        boolean found = false;
        for (Object preset : presets) {
          if ((int) divisorM.invoke(preset) == divisor && (int) maskM.invoke(preset) == mask) {
            found = true;
            break;
          }
        }
        assertTrue(found, "Missing preset for divisor=" + divisor + " step=" + step);
        total++;
      }
    }
    assertEquals(35, total, "must cover all 35 cycle presets (1of2..8of8) plus FIRST and LAST");
  }

  @Test
  public void testOffIteranceZeroNormalizesToAlways1Of1() {
    // Default unconditional steps carry playCondition == 0 (Iterance(0, 0) / OFF).
    // StepPropertiesDialog must normalize OFF to Always (0x0101) instead of Custom with no checks.
    StepPropertiesDialog dlg =
        new StepPropertiesDialog(null, 80, 0, 0, 100, 0.9, 0, Iterance.fromInt(0));
    Iterance cond = dlg.getPlayCondition();
    assertEquals(1, cond.divisor & 0xFF, "divisor should normalize from 0 to 1");
    assertEquals(1, cond.iteranceStep & 0xFF, "iteranceStep should normalize from 0 to 1");
    assertEquals(0x0101, dlg.getPlayConditionInt());
  }

  @Test
  public void testPreviouslyMissingIterancePresetsRoundTripThroughTheDialog() {
    // 3 of the 25 presets that used to be reachable only via the Custom bitmask editor, not as a
    // named dropdown preset -- verified end-to-end through the dialog's public API.
    int[][] cases = {
      {3, 0b10}, // 2nd of 3
      {6, 0b100000}, // 6th of 6
      {7, 0b1000000}, // 7th of 7
    };
    for (int[] c : cases) {
      Iterance cond = new Iterance((byte) c[0], (byte) c[1]);
      StepPropertiesDialog dlg = new StepPropertiesDialog(null, 80, 0, 0, 100, 0.9, 0, cond);
      Iterance retrieved = dlg.getPlayCondition();
      assertEquals(c[0], retrieved.divisor & 0xFF, "divisor for case " + c[0] + "/" + c[1]);
      assertEquals(c[1], retrieved.iteranceStep & 0xFF, "mask for case " + c[0] + "/" + c[1]);
    }
  }
}
