package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.swing.JComboBox;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import org.deluge.BridgeContract;
import org.deluge.midi.MidiInputRouter;
import org.deluge.model.AutomationParam;
import org.deluge.model.ClipModel;
import org.deluge.model.ModKnob;
import org.deluge.model.PatchCable;
import org.deluge.model.SynthTrackModel;
import org.deluge.project.PreferencesManager;
import org.deluge.shadow.midi.MidiMsg;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Portable unit tests for {@link SwingWaveformPanel}, {@link SynthPresetBrowserPanel}, {@link
 * MidiLearnPanel}, {@link ModulationMatrixComponent}, {@link ModulationPanel}, and {@link
 * AutomationPanel}.
 */
class WaveformBrowserMidiLearnModAutoTest {

  @TempDir Path tempDir;

  @Test
  void testWaveformPanelDecodes24BitAnd32BitSamplesAndLateLoadListener() throws Exception {
    // 1. 24-bit signed little-endian: +4194304 (half full scale = 0.5f)
    byte[] pcm24 = new byte[] {0x00, 0x00, 0x40};
    float val24 =
        SwingWaveformPanel.decodeSampleNormalized(
            pcm24, 0, 24, false, AudioFormat.Encoding.PCM_SIGNED);
    assertEquals(0.5f, val24, 1e-4f, "24-bit PCM sample must decode to 0.5");

    // 2. 32-bit float little-endian: -0.75f
    byte[] float32 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(-0.75f).array();
    float valFloat32 =
        SwingWaveformPanel.decodeSampleNormalized(
            float32, 0, 32, false, AudioFormat.Encoding.PCM_FLOAT);
    assertEquals(0.75f, valFloat32, 1e-4f, "32-bit float sample must decode to 0.75");

    // 3. Write a real 24-bit WAV file and decode via decodeAudioFile
    File wav24 = tempDir.resolve("test24.wav").toFile();
    int frames = 600;
    byte[] raw24 = new byte[frames * 3];
    for (int i = 0; i < frames; i++) {
      // +0x400000 in 24-bit little-endian
      raw24[i * 3] = 0x00;
      raw24[i * 3 + 1] = 0x00;
      raw24[i * 3 + 2] = 0x40;
    }
    AudioFormat fmt24 = new AudioFormat(44100.0f, 24, 1, true, false);
    try (AudioInputStream ais =
        new AudioInputStream(new ByteArrayInputStream(raw24), fmt24, frames)) {
      AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wav24);
    }

    SwingWaveformPanel.DecodedWaveform decoded = SwingWaveformPanel.decodeAudioFile(wav24);
    assertNotNull(decoded, "24-bit WAV must decode successfully");
    assertEquals(frames, decoded.totalFrames());
    assertTrue(decoded.points()[10] > 0.45f, "24-bit WAV waveform points must be non-zero");
    assertTrue(decoded.metadataText().contains("24-bit"));

    if (GraphicsEnvironment.isHeadless()) {
      return;
    }

    SwingUtilities.invokeAndWait(
        () -> {
          SwingWaveformPanel panel = new SwingWaveformPanel(wav24.getAbsolutePath());
          assertNotNull(panel);
        });

    // Wait briefly for virtual thread decode to finish, then verify late setLoadListener fires
    SwingWaveformPanel[] holder = new SwingWaveformPanel[1];
    SwingUtilities.invokeAndWait(() -> holder[0] = new SwingWaveformPanel(wav24.getAbsolutePath()));
    long deadline = System.currentTimeMillis() + 2000L;
    while (System.currentTimeMillis() < deadline && holder[0].getTotalFrames() == 0) {
      Thread.sleep(20L);
    }
    AtomicInteger loadedFrames = new AtomicInteger(-1);
    SwingUtilities.invokeAndWait(() -> holder[0].setLoadListener(loadedFrames::set));
    assertEquals(
        frames,
        loadedFrames.get(),
        "setLoadListener registered after decode finishes must fire immediately");
  }

  @Test
  void testSynthPresetBrowserDocumentFilterAndAlphabeticalSort() throws Exception {
    if (GraphicsEnvironment.isHeadless()) {
      return;
    }

    String prevLibDir = PreferencesManager.get("library_dir", "");
    String prevSysCard = System.getProperty("deluge.card");
    try {
      File synthsDir = tempDir.resolve("SYNTHS").toFile();
      assertTrue(synthsDir.mkdirs());
      String minimalXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><sound></sound>";
      Files.writeString(synthsDir.toPath().resolve("ZebraPad.XML"), minimalXml);
      Files.writeString(synthsDir.toPath().resolve("AlphaLead.xml"), minimalXml);
      Files.writeString(synthsDir.toPath().resolve("MidBass.xml"), minimalXml);

      System.setProperty("deluge.card", tempDir.toFile().getAbsolutePath());
      PreferencesManager.setLibraryDir(tempDir.toFile().getAbsolutePath());

      SynthTrackModel model = new SynthTrackModel("Synth 1");
      SwingUtilities.invokeAndWait(
          () -> {
            SynthPresetBrowserPanel browser = new SynthPresetBrowserPanel(model, () -> {});
            var list = browser.getPresetListForTest();
            assertEquals(3, list.getModel().getSize());
            assertEquals(
                "AlphaLead",
                list.getModel().getElementAt(0).name(),
                "Presets must be sorted alphabetically regardless of filesystem order");
            assertEquals("MidBass", list.getModel().getElementAt(1).name());
            assertEquals("ZebraPad", list.getModel().getElementAt(2).name());

            // Programmatic setText (like paste) must trigger DocumentListener filtering
            browser.getSearchFieldForTest().setText("zebra");
            assertEquals(1, list.getModel().getSize());
            assertEquals("ZebraPad", list.getModel().getElementAt(0).name());
          });
    } finally {
      if (prevSysCard == null) {
        System.clearProperty("deluge.card");
      } else {
        System.setProperty("deluge.card", prevSysCard);
      }
      PreferencesManager.setLibraryDir(prevLibDir);
    }
  }

  @Test
  void testMidiLearnPanelCancelAndRemoveNotifyClearRouterLearningTarget() throws Exception {
    if (GraphicsEnvironment.isHeadless()) {
      return;
    }

    BridgeContract bridge = new BridgeContract();
    MidiInputRouter router = new MidiInputRouter(bridge);

    SwingUtilities.invokeAndWait(
        () -> {
          MidiLearnPanel panel = new MidiLearnPanel();
          panel.setRouterForTest(router);

          JToggleButton learnBtn = panel.getLearnButtonForTest(0); // g_sp_volume
          learnBtn.doClick(); // Arm learn
          assertTrue(learnBtn.isSelected());

          learnBtn.doClick(); // Cancel learn
          assertFalse(learnBtn.isSelected());

          // Send CC 21 after canceling learn — must NOT map to g_sp_volume!
          MidiMsg ccMsg = new MidiMsg();
          ccMsg.data1 = 0xB0;
          ccMsg.data2 = 21;
          ccMsg.data3 = 64;
          router.handleMidiMessage(ccMsg);
          assertEquals(
              -1,
              router.getCcForParam("g_sp_volume"),
              "Toggling LEARN off must clear learningTarget on MidiInputRouter");

          // Arm learn again, then click CLEAR on the same row — must also cancel learn
          learnBtn.doClick();
          assertTrue(learnBtn.isSelected());
          panel.getClearButtonForTest(0).doClick();
          assertFalse(learnBtn.isSelected());
          router.handleMidiMessage(ccMsg);
          assertEquals(-1, router.getCcForParam("g_sp_volume"));

          // Arm learn again, then call removeNotify() — must stop timer and cancel learn
          learnBtn.doClick();
          assertTrue(learnBtn.isSelected());
          panel.removeNotify();
          assertFalse(panel.isRefreshTimerRunningForTest());
          router.handleMidiMessage(ccMsg);
          assertEquals(-1, router.getCcForParam("g_sp_volume"));
        });
  }

  @Test
  void testModulationMatrixAftertouchAndKnobPreservesCableSource() throws Exception {
    String[] sources = ModulationMatrixComponent.getSourcesForTest();
    int aftCol = Arrays.asList(sources).indexOf("aftertouch");
    assertTrue(aftCol >= 0, "ModulationMatrixComponent must include aftertouch source");

    SynthTrackModel model = new SynthTrackModel("Synth 1");
    model.getModulation().addPatchCable(new PatchCable("aftertouch", "volume", 0.4f));

    if (GraphicsEnvironment.isHeadless()) {
      return;
    }

    SwingUtilities.invokeAndWait(
        () -> {
          ModulationMatrixComponent matrix = new ModulationMatrixComponent(model, () -> {});
          PatchCable found = matrix.findCableForTest(aftCol, 0); // row 0 = volume
          assertNotNull(found, "Matrix must locate aftertouch -> volume patch cable");
          assertEquals(0.4f, found.amount(), 1e-5f);

          // Set custom patchCableSource on Gold Knob 0, then change its target parameter in UI
          model.getModulation().setModKnob(0, new ModKnob("volume", "lfo1"));
          ModulationPanel modPanel = new ModulationPanel(model, new BridgeContract(), 0);
          JComboBox<?> firstKnobCombo = findFirstKnobCombo(modPanel);
          assertNotNull(firstKnobCombo);
          firstKnobCombo.setSelectedItem("lpfFrequency");

          ModKnob updated = model.getModulation().getModKnobs().get(0);
          assertEquals("lpfFrequency", updated.param());
          assertEquals(
              "lfo1",
              updated.patchSource(),
              "Changing knob parameter must preserve existing patchSource");

          // Negative unipolar cable amount must be preserved when switching polarity
          model.getModulation().getPatchCables().clear();
          model
              .getModulation()
              .addPatchCable(
                  new PatchCable("lfo1", "lpfFrequency", -0.5f, PatchCable.Polarity.BIPOLAR));
          ModulationPanel polPanel = new ModulationPanel(model, new BridgeContract(), 0);
          JToggleButton polBtn = findFirstPolarityToggle(polPanel);
          JSlider amtSlider = findFirstAmountSlider(polPanel);
          assertNotNull(polBtn);
          assertNotNull(amtSlider);
          assertEquals(-100, amtSlider.getMinimum());
          assertTrue(polBtn.isSelected());

          polBtn.doClick(); // Switch to UNIPOLAR
          assertFalse(polBtn.isSelected());
          PatchCable afterPolToggle = model.getModulation().getPatchCables().get(0);
          assertEquals(PatchCable.Polarity.UNIPOLAR, afterPolToggle.polarity());
          assertEquals(
              -0.5f,
              afterPolToggle.amount(),
              1e-5f,
              "Switching to unipolar must not zero a negative cable amount");
          assertEquals(
              -100,
              amtSlider.getMinimum(),
              "Unipolar cables keep -100..100 slider range (C patch_cable_strength.h:36-37)");
        });
  }

  @Test
  void testAutomationPanelOutOfBoundsActiveClipAndClearAllResetsUi() throws Exception {
    if (GraphicsEnvironment.isHeadless()) {
      return;
    }

    // 1. Out-of-bounds activeClipIndex must not throw IndexOutOfBoundsException
    SynthTrackModel badIdxModel = new SynthTrackModel("SynthBadIdx");
    badIdxModel.addClip(new ClipModel("Clip 1", 8, 16));
    badIdxModel.setActiveClipIndex(5);
    SwingUtilities.invokeAndWait(
        () -> {
          assertDoesNotThrow(
              () -> new AutomationPanel(badIdxModel, new BridgeContract(), 0),
              "Out-of-bounds activeClipIndex must not throw IndexOutOfBoundsException");
        });

    // 2. Clear All Automation must uncheck enableBoxes and disable sliders without mainInstance
    SynthTrackModel model = new SynthTrackModel("Synth 1");
    ClipModel clip = new ClipModel("Clip 1", 8, 8);
    String firstParam = AutomationParam.SYTH_PARAMS[0];
    clip.setAutomation(firstParam, 0, 0.75f);
    model.addClip(clip);
    model.setActiveClipIndex(0);

    SwingUtilities.invokeAndWait(
        () -> {
          AutomationPanel panel = new AutomationPanel(model, new BridgeContract(), 0);
          assertTrue(panel.getEnableBoxForTest(0).isSelected());
          assertTrue(panel.getSliderForTest(0, 0).isEnabled());

          panel.clearAllAutomationNow();

          assertFalse(
              clip.hasAutomation(firstParam), "clearAllAutomationNow must clear clip automation");
          assertFalse(
              panel.getEnableBoxForTest(0).isSelected(),
              "clearAllAutomationNow must uncheck parameter checkbox");
          assertFalse(
              panel.getSliderForTest(0, 0).isEnabled(),
              "clearAllAutomationNow must disable step sliders");
        });
  }

  private static JComboBox<?> findFirstKnobCombo(Container root) {
    for (Component comp : root.getComponents()) {
      if (comp instanceof JComboBox<?> cb && cb.getItemCount() == 16) {
        Object first = cb.getItemAt(0);
        if ("NONE".equals(first)) {
          return cb;
        }
      }
      if (comp instanceof Container child) {
        JComboBox<?> found = findFirstKnobCombo(child);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static JToggleButton findFirstPolarityToggle(Container root) {
    for (Component comp : root.getComponents()) {
      if (comp instanceof JToggleButton tb && "Bipolar".equals(tb.getText())) {
        return tb;
      }
      if (comp instanceof Container child) {
        JToggleButton found = findFirstPolarityToggle(child);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static JSlider findFirstAmountSlider(Container root) {
    for (Component comp : root.getComponents()) {
      if (comp instanceof JSlider s && s.getMaximum() == 100) {
        return s;
      }
      if (comp instanceof Container child) {
        JSlider found = findFirstAmountSlider(child);
        if (found != null) return found;
      }
    }
    return null;
  }
}
