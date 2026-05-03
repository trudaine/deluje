package org.chuck.deluge.engine.dsp;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

public class NativeMoogFilterTest {

    @Test
    public void testStability() {
        NativeMoogFilter filter = new NativeMoogFilter(44100);
        filter.setParams(1000, 0.95); // High resonance
        
        // Pass some noise/impulses through and check for NaN/Infinity
        for (int i = 0; i < 1000; i++) {
            float in = (float)(Math.random() * 2.0 - 1.0);
            float out = filter.tick(in);
            assertTrue(Float.isFinite(out));
            assertTrue(out >= -1.1f && out <= 1.1f);
        }
    }

    @Test
    public void testCutoff() {
        NativeMoogFilter filter = new NativeMoogFilter(44100);
        filter.setParams(20, 0.0); // Extreme low cutoff
        
        float out = filter.tick(1.0f); // Step input
        assertTrue(Math.abs(out) < 0.1f); // Should be heavily attenuated
    }
}
