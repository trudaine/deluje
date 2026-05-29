package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.reverb.ReverbContainer;
import org.chuck.deluge.firmware.util.Q31;
import org.junit.jupiter.api.Test;

/**
 * High-fidelity regressions testing for Freeverb, Mutable space, and Digital Lexicon 224 reverbs.
 * Asserts energy levels scaling, parameters modulations, and crash-free live hot-swapping.
 */
public class ReverbFidelityTest {

  @Test
  public void testAllModelsProcessing() {
    ReverbContainer reverb = new ReverbContainer();
    reverb.setPanLevels(Q31.ONE, Q31.ONE);
    reverb.setLPF(1.0f);
    int[] input = new int[8192];
    StereoSample[] output = new StereoSample[8192];
    for (int i = 0; i < 8192; i++) {
      output[i] = new StereoSample();
    }

    // Step input signal (impulsive click)
    input[0] = 1000000000;

    for (ReverbContainer.Model model : ReverbContainer.Model.values()) {
      reverb.setModel(model);
      reverb.clear();

      // Clear output buffers
      for (int i = 0; i < 8192; i++) {
        output[i].l = 0;
        output[i].r = 0;
      }

      reverb.process(input, output);

      // Verify output has generated ambient energy and is not silent/NaN/Infinity
      double energy = 0.0;
      for (int i = 0; i < 8192; i++) {
        energy += Math.abs(output[i].l) + Math.abs(output[i].r);
        assertTrue(Float.isFinite(output[i].l));
        assertTrue(Float.isFinite(output[i].r));
      }
      assertTrue(energy > 0.0, "Model " + model + " should generate active output energy!");
    }
  }

  @Test
  public void testParameterModulations() {
    ReverbContainer reverb = new ReverbContainer();
    reverb.setPanLevels(Q31.ONE, Q31.ONE);
    reverb.setLPF(1.0f);
    reverb.setModel(ReverbContainer.Model.MUTABLE);

    int[] input = new int[16384];
    StereoSample[] output = new StereoSample[16384];
    for (int i = 0; i < 16384; i++) {
      input[i] = (int) (Math.random() * 200000000.0 - 100000000.0);
      output[i] = new StereoSample();
    }

    // Measure RMS energy with high room size/decay time
    reverb.clear();
    reverb.setRoomSize(0.9f);
    reverb.process(input, output);
    double highRoomSizeEnergy = 0.0;
    for (int i = 0; i < 16384; i++) {
      highRoomSizeEnergy += Math.abs(output[i].l) + Math.abs(output[i].r);
    }

    // Measure RMS energy with low room size/decay time
    reverb.clear();
    for (int i = 0; i < 16384; i++) {
      output[i].l = 0;
      output[i].r = 0;
    }
    reverb.setRoomSize(0.1f);
    reverb.process(input, output);
    double lowRoomSizeEnergy = 0.0;
    for (int i = 0; i < 16384; i++) {
      lowRoomSizeEnergy += Math.abs(output[i].l) + Math.abs(output[i].r);
    }

    // High room size should have much slower decay and therefore more accumulated energy
    assertTrue(
        highRoomSizeEnergy > lowRoomSizeEnergy,
        "High room size energy ("
            + highRoomSizeEnergy
            + ") must exceed low room size energy ("
            + lowRoomSizeEnergy
            + ")!");
  }

  @Test
  public void testLiveHotSwapping() {
    ReverbContainer reverb = new ReverbContainer();
    reverb.setPanLevels(Q31.ONE, Q31.ONE);
    reverb.setLPF(1.0f);
    int[] input = new int[8192];
    StereoSample[] output = new StereoSample[8192];
    for (int i = 0; i < 8192; i++) {
      input[i] = (int) (Math.random() * 200000000.0 - 100000000.0);
      output[i] = new StereoSample();
    }

    reverb.setModel(ReverbContainer.Model.FREEVERB);
    reverb.process(input, output);

    // Swap to MUTABLE model mid-stream
    assertDoesNotThrow(() -> reverb.setModel(ReverbContainer.Model.MUTABLE));
    reverb.process(input, output);

    // Swap to DIGITAL model mid-stream
    assertDoesNotThrow(() -> reverb.setModel(ReverbContainer.Model.DIGITAL));
    reverb.process(input, output);
  }
}
