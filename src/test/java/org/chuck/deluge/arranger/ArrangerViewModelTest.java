package org.chuck.deluge.arranger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.chuck.deluge.model.ArrangerClip;
import org.chuck.deluge.ui.arranger.ArrangerViewModel;
import org.junit.jupiter.api.Test;

public class ArrangerViewModelTest {

  @Test
  void testPixelToBarConversion() {
    ArrangerViewModel vm = new ArrangerViewModel();
    vm.setPixelsPerBar(32.0); // Default

    // Bar 1 should be pixel 0
    assertEquals(0.0, vm.barToPixel(1.0), 0.001);
    assertEquals(1.0, vm.pixelToBar(0.0), 0.001);

    // Bar 2 should be pixel 32
    assertEquals(32.0, vm.barToPixel(2.0), 0.001);
    assertEquals(2.0, vm.pixelToBar(32.0), 0.001);

    // Bar 2.5 (middle of bar 2) should be pixel 48
    assertEquals(48.0, vm.barToPixel(2.5), 0.001);
    assertEquals(2.5, vm.pixelToBar(48.0), 0.001);
  }

  @Test
  void testClipManagement() {
    ArrangerViewModel vm = new ArrangerViewModel();
    ArrangerClip clip = new ArrangerClip(0, "PAT_A", 1, 4);

    vm.addClip(clip);
    assertEquals(1, vm.getClips().size());

    vm.removeClip(clip);
    assertEquals(0, vm.getClips().size());
  }
}
