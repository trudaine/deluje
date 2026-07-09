package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.model.StutterConfig;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Verifies 100% C++ hardware parity binding between StutterPanel UI controls and the underlying
 * StutterConfig model (Quantized, Reversed, Ping-Pong).
 */
public class StutterPanelParityUITest {

  @Test
  public void testStutterHardwareModesBindingAndDefaults() {
    SynthTrackModel model = new SynthTrackModel("TestTrack");
    StutterConfig st = model.getStutter();

    // Verify Hardware C++ defaults (Quantized=true, Reversed=false, PingPong=false)
    assertTrue(st.isStutterQuantized(), "Hardware default should be Quantized=true");
    assertFalse(st.isStutterReversed(), "Hardware default should be Reversed=false");
    assertFalse(st.isStutterPingPong(), "Hardware default should be PingPong=false");

    StutterPanel panel = new StutterPanel(model, null, 0, null);

    // Verify UI reflects initial model
    assertTrue(panel.getQuantizeCheck().isSelected());
    assertFalse(panel.getReverseCheck().isSelected());
    assertFalse(panel.getPingPongCheck().isSelected());

    // User toggles UI controls
    panel.getQuantizeCheck().doClick(); // false
    panel.getReverseCheck().doClick(); // true
    panel.getPingPongCheck().doClick(); // true

    // Verify underlying model updated instantly
    assertFalse(st.isStutterQuantized(), "Quantize should turn false when unchecked");
    assertTrue(st.isStutterReversed(), "Reversed should turn true when checked");
    assertTrue(st.isStutterPingPong(), "Ping-Pong should turn true when checked");
  }
}
