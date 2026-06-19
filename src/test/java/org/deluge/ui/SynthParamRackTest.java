package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.deluge.BridgeContract;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/** Verifies the synth param rack edits the bound track and tolerates the no-synth case. */
public class SynthParamRackTest {

  private SynthParamRack rack(SynthTrackModel[] holder) {
    BridgeContract bridge = new BridgeContract(44100, 2);
    return new SynthParamRack(bridge, () -> holder[0], () -> 0);
  }

  @Test
  public void cutoffKnobScalesLpfFreq() {
    SynthTrackModel t = new SynthTrackModel("Lead");
    t.setLpfFreq(1000f);
    SynthParamRack r = rack(new SynthTrackModel[] {t});

    r.editCutoff(1); // one detent up -> *1.05
    assertTrue(t.getLpfFreq() > 1000f, "cutoff up raises freq: " + t.getLpfFreq());
    float up = t.getLpfFreq();
    r.editCutoff(-1);
    assertTrue(t.getLpfFreq() < up, "cutoff down lowers freq");
  }

  @Test
  public void resKnobIsClampedToUnitRange() {
    SynthTrackModel t = new SynthTrackModel("Lead");
    t.setLpfRes(0.99f);
    SynthParamRack r = rack(new SynthTrackModel[] {t});
    for (int i = 0; i < 20; i++) {
      r.editRes(1);
    }
    assertTrue(t.getLpfRes() <= 1.0f, "resonance never exceeds 1.0");
  }

  @Test
  public void noSynthSelectedIsSafe() {
    SynthParamRack r = rack(new SynthTrackModel[] {null});
    assertDoesNotThrow(
        () -> {
          r.refresh(); // disabled path
          r.editCutoff(1); // no-op, no NPE
          BufferedImage img = new BufferedImage(312, 400, BufferedImage.TYPE_INT_ARGB);
          Graphics2D g = img.createGraphics();
          r.setSize(312, 400);
          r.paint(g);
          g.dispose();
        });
  }
}
