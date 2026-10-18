package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.lang.reflect.Constructor;
import javax.swing.JButton;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import org.deluge.BridgeContract;
import org.deluge.firmware2.Param;
import org.deluge.model.PatchCable;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.modulation.patch.Destination;
import org.deluge.ui.CompressorPanel;
import org.deluge.ui.OscPanel;
import org.deluge.ui.SwingGridPanel;
import org.junit.jupiter.api.Test;

/**
 * Verifies C++ firmware parity fixes in {@link FirmwareFactory}, {@link CompressorPanel}, {@link
 * OscPanel}, and {@code KeyboardMouseAdapter}.
 */
class FirmwareFactoryPatchOscCompressorParityTest {

  @Test
  void applyModelToLiveSoundUpdatesSampleSettingsEvenWhenSamplePathIsCached() {
    SynthTrackModel model = new SynthTrackModel("Synth");
    model.setOsc1Type("SAMPLE");
    model.setOsc1SamplePath("SAMPLES/KICK.WAV");
    model.setOsc1LoopMode(2); // LOOP
    model.setOsc1Reversed(true);
    model.setOsc1TimeStretch(true);

    model.setOsc2Type("SAMPLE");
    model.setOsc2SamplePath("SAMPLES/SNARE.WAV");
    model.setOsc2LoopMode(1); // ONCE
    model.setOsc2Reversed(false);
    model.setOsc2TimeStretch(true);

    FirmwareSound sound = new FirmwareSound();
    // Simulate already-cached paths so loadOscResources takes its early return.
    sound.loadedOscPath[0] = "SAMPLE:SAMPLES/KICK.WAV";
    sound.loadedOscPath[1] = "SAMPLE:SAMPLES/SNARE.WAV";

    FirmwareFactory.applyModelToLiveSound(model, sound);

    assertEquals(2, sound.sampleSettings[0].loopMode);
    assertTrue(sound.sampleSettings[0].reverse);
    assertTrue(sound.sampleSettings[0].timestretch);
    assertEquals(1, sound.sampleSettings[1].loopMode);
    assertEquals(false, sound.sampleSettings[1].reverse);
    assertTrue(sound.sampleSettings[1].timestretch);
  }

  @Test
  void mapPatchCablesRoutesLfoRatesEnvelopeStagesWavefoldArpRateAndPostVolumes() {
    SynthTrackModel model = new SynthTrackModel("Synth");
    model.setClipParamsFresh(false);
    model.getModulation().addPatchCable(new PatchCable("lfo1", "lfo1Rate", 0.5f));
    model.getModulation().addPatchCable(new PatchCable("lfo1", "lfo2Rate", 0.5f));
    model.getModulation().addPatchCable(new PatchCable("lfo1", "lfo3Rate", 0.5f));
    model.getModulation().addPatchCable(new PatchCable("lfo1", "lfo4Rate", 0.5f));
    model.getModulation().addPatchCable(new PatchCable("velocity", "env1Attack", 0.4f));
    model.getModulation().addPatchCable(new PatchCable("velocity", "env2Decay", 0.4f));
    model.getModulation().addPatchCable(new PatchCable("velocity", "env3Sustain", 0.4f));
    model.getModulation().addPatchCable(new PatchCable("velocity", "env4Release", 0.4f));
    model.getModulation().addPatchCable(new PatchCable("lfo1", "arpRate", 0.3f));
    model.getModulation().addPatchCable(new PatchCable("lfo2", "waveFold", 0.3f));
    model.getModulation().addPatchCable(new PatchCable("lfo1", "volumePostFX", 0.25f));
    model.getModulation().addPatchCable(new PatchCable("lfo1", "volumePostReverbSend", 0.25f));

    FirmwareSound sound = new FirmwareSound();
    FirmwareFactory.applyModelToLiveSound(model, sound);

    var dests = sound.paramManager.getPatchCableSet().destinations;
    assertTrue(hasDestination(dests, Param.GLOBAL_LFO_FREQ_1));
    assertTrue(hasDestination(dests, Param.LOCAL_LFO_LOCAL_FREQ_1));
    assertTrue(hasDestination(dests, Param.GLOBAL_LFO_FREQ_2));
    assertTrue(hasDestination(dests, Param.LOCAL_LFO_LOCAL_FREQ_2));
    assertTrue(hasDestination(dests, Param.LOCAL_ENV_0_ATTACK));
    assertTrue(hasDestination(dests, Param.LOCAL_ENV_1_DECAY));
    assertTrue(hasDestination(dests, Param.LOCAL_ENV_2_SUSTAIN));
    assertTrue(hasDestination(dests, Param.LOCAL_ENV_3_RELEASE));
    assertTrue(hasDestination(dests, Param.GLOBAL_ARP_RATE));
    assertTrue(hasDestination(dests, Param.LOCAL_FOLD));
    assertTrue(hasDestination(dests, Param.GLOBAL_VOLUME_POST_FX));
    assertTrue(hasDestination(dests, Param.GLOBAL_VOLUME_POST_REVERB_SEND));
  }

  @Test
  void compressorPanelScalesRatioBetweenZeroAndOne() throws Exception {
    SynthTrackModel model = new SynthTrackModel("Synth");
    model.setCompressorRatio(0.5f);
    ProjectModel project = new ProjectModel();
    project.addTrack(model);

    SwingUtilities.invokeAndWait(
        () -> {
          CompressorPanel panel = new CompressorPanel(model, new BridgeContract(), 0, project);
          JSlider ratioSlider = findSliderByName(panel, "compRatio");
          assertNotNull(ratioSlider);
          assertEquals(50, ratioSlider.getValue());

          ratioSlider.setValue(75);
          assertEquals(0.75f, model.getCompressorRatio(), 1e-6f);
        });
  }

  @Test
  void oscPanelExposesBothTransposeSlidersWithHardwareRangeAndClampsLoopMode() throws Exception {
    SynthTrackModel model = new SynthTrackModel("Synth");
    model.setOsc1LoopMode(99);
    model.setOsc2LoopMode(-5);
    model.setOsc1Transpose(-36);
    model.setOsc2Transpose(36);
    ProjectModel project = new ProjectModel();
    project.addTrack(model);

    SwingUtilities.invokeAndWait(
        () -> {
          OscPanel panel = new OscPanel(model, new BridgeContract(), 0, project);
          JSlider osc1Trans = findSliderByName(panel, "osc1Transpose");
          JSlider osc2Trans = findSliderByName(panel, "osc2Transpose");
          assertNotNull(osc1Trans);
          assertNotNull(osc2Trans);
          assertEquals(-96, osc1Trans.getMinimum());
          assertEquals(96, osc1Trans.getMaximum());
          assertEquals(-36, osc1Trans.getValue());
          assertEquals(-96, osc2Trans.getMinimum());
          assertEquals(96, osc2Trans.getMaximum());
          assertEquals(36, osc2Trans.getValue());

          osc1Trans.setValue(-48);
          osc2Trans.setValue(48);
          assertEquals(-48, model.getOsc1Transpose());
          assertEquals(48, model.getOsc2Transpose());
        });
  }

  @Test
  void keyboardMouseAdapterHandlesNegativeNoteShiftClickForRootKey() throws Exception {
    ProjectModel project = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("Synth");
    project.addTrack(synth);

    SwingUtilities.invokeAndWait(
        () -> {
          try {
            SwingGridPanel gridPanel = new org.deluge.ui.ClipGridPanel(new BridgeContract());
            gridPanel.setProjectModel(project);

            Class<?> adapterClass = Class.forName("org.deluge.ui.KeyboardMouseAdapter");
            Constructor<?> ctor =
                adapterClass.getDeclaredConstructor(SwingGridPanel.class, int.class);
            ctor.setAccessible(true);
            java.awt.event.MouseAdapter adapter =
                (java.awt.event.MouseAdapter) ctor.newInstance(gridPanel, -1);

            JButton dummyKey = new JButton();
            dummyKey.setSize(20, 40);
            MouseEvent shiftClick =
                new MouseEvent(
                    dummyKey,
                    MouseEvent.MOUSE_PRESSED,
                    System.currentTimeMillis(),
                    MouseEvent.SHIFT_DOWN_MASK,
                    10,
                    20,
                    1,
                    false);
            adapter.mousePressed(shiftClick);

            assertEquals("B", project.getKey());
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  private static boolean hasDestination(java.util.List<Destination> dests, int paramId) {
    for (Destination d : dests) {
      if (d.paramId == paramId && d.targetSource < 0) {
        return !d.cables.isEmpty();
      }
    }
    return false;
  }

  private static JSlider findSliderByName(Container root, String name) {
    for (Component comp : root.getComponents()) {
      if (comp instanceof JSlider slider && name.equals(slider.getName())) {
        return slider;
      }
      if (comp instanceof Container child) {
        JSlider found = findSliderByName(child, name);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }
}
