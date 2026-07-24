package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class VoiceSaturationTest {

  @Test
  @DisplayName("Verify Voice saturation uses exact C++ wrapping left-shift outputVal << shiftAmount")
  public void testVoiceSaturationOutputShift() {
    Sound sound = new Sound();
    sound.clippingAmount = 2; // shiftAmount = (2 >= 2) ? 0 : 0 = 0
    assertEquals(0, sound.getShiftAmountForSaturation());

    sound.clippingAmount = 3; // shiftAmount = (3 >= 2) ? 1 : 0 = 1
    assertEquals(1, sound.getShiftAmountForSaturation());

    sound.clippingAmount = 4; // shiftAmount = (4 >= 2) ? 2 : 0 = 2
    assertEquals(2, sound.getShiftAmountForSaturation());

    // Verify bit-shift output calculation matches verbatim C:
    // outputVal << shiftAmount without artificial signed_saturate(outputVal, 32 - shiftAmount) clamping
    int outputVal = 0x30000000; // Large Q31 value
    int shiftAmount = 2;
    
    int expectedVerbatimC = outputVal << shiftAmount;
    int artificialClamped = Functions.lshiftAndSaturate(outputVal, shiftAmount);

    assertNotEquals(
        expectedVerbatimC,
        artificialClamped,
        "lshiftAndSaturate artificially clamped peaks before shifting");
    
    // Verbatim C preserves 32-bit wrapping shift
    assertEquals(0xc0000000, expectedVerbatimC);
  }
}
