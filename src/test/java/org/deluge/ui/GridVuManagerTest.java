package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GridVuManagerTest {

  @Test
  void testManagerLifecycle() {
    // Instantiate in headless environment
    GridVuManager manager = new GridVuManager();
    assertNotNull(manager);

    VUMeterPanel voiceVu = new VUMeterPanel();
    VUMeterPanel trackVu = new VUMeterPanel();

    // Register panels
    manager.registerVoiceVu(0, voiceVu);
    manager.registerTrackVu(5, trackVu);

    // Spike levels (simulates notes playing)
    manager.spikeVu(0);
    manager.spikeVu(5);
    manager.spikeVu(127); // edge boundary
    manager.spikeVu(-1); // out of bounds, should be safely ignored
    manager.spikeVu(128); // out of bounds, should be safely ignored

    // Clear and reset
    manager.clear();

    // Shutdown
    manager.shutdown();
  }
}
