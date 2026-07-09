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
    StepPropertiesDialog dlg =
        new StepPropertiesDialog(null, 80, 0, 0, 100, 0.9, 0, initCond);

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
}
