package org.deluge.kit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.JButton;
import org.deluge.hid.FirmwareDisplay;
import org.deluge.hid.RGB;
import org.deluge.hid.VirtualOLED;
import org.deluge.hid.pic.GridConfig;
import org.deluge.hid.pic.SwingPicTransport;
import org.deluge.model.FilterMode;
import org.deluge.model.KitTrackModel;
import org.deluge.model.SoundDrum;
import org.deluge.modulation.patch.Destination;
import org.deluge.modulation.patch.PatchCable;
import org.deluge.modulation.patch.PatchCableSet;
import org.deluge.modulation.patch.PatchSource;
import org.deluge.project.PreferencesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KitAssemblerPatchCableHidParityTest {

  @AfterEach
  void resetGridAndOled() {
    GridConfig.resetToHardware();
    FirmwareDisplay.get().getVirtualOLED().stopScrollTimer();
  }

  @Test
  void kitAssemblerPreservesSynthParametersWithoutStoringXmlAsSamplePath(@TempDir Path tempDir)
      throws Exception {
    String synthXml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <sound transpose="5" lpfMode="24dB">
          <osc1 type="saw" transpose="-12"/>
          <osc2 type="square" transpose="7"/>
          <defaultParams lpfFrequency="0x30000000"/>
        </sound>
        """;
    File synthFile = tempDir.resolve("SYNT001.XML").toFile();
    Files.writeString(synthFile.toPath(), synthXml);

    KitTrackModel kit =
        KitAssembler.assembleFromSynths(
            "AssembledKit", List.of(synthFile), null, List.of(7), List.of("BassLane"));

    assertEquals(1, kit.getDrums().size());
    SoundDrum drum = (SoundDrum) kit.getDrums().get(0);
    assertEquals("BassLane", drum.getName());
    assertEquals("SAW", drum.getOsc1Type());
    assertEquals(-12, drum.getOsc1Transpose());
    assertEquals("SQUARE", drum.getOsc2Type());
    assertEquals(7, drum.getOsc2Transpose());
    assertEquals(FilterMode.LADDER_24, drum.getLpfMode());
    assertEquals(12.0f, drum.getPitchSemitones(), 1e-4f);
    assertEquals(0, drum.getMuteGroup());
    assertEquals("", drum.getSamplePath());
    assertFalse(drum.getSamplePath().endsWith(".XML"));
  }

  @Test
  void patchCableSetIgnoresNoneSourceAndDeduplicatesSameSourceCables() {
    PatchCableSet set = new PatchCableSet();

    PatchCable noneCable = new PatchCable();
    noneCable.from = PatchSource.NONE;
    noneCable.amount = 100;
    set.addCable(4, noneCable);
    set.addCable(4, null);
    assertTrue(set.destinations.isEmpty());

    PatchCable lfoFirst = new PatchCable();
    lfoFirst.from = PatchSource.LFO_LOCAL_1;
    lfoFirst.amount = 200;
    set.addCable(4, lfoFirst);

    PatchCable lfoReplacement = new PatchCable();
    lfoReplacement.from = PatchSource.LFO_LOCAL_1;
    lfoReplacement.amount = 500;
    set.addCable(4, lfoReplacement);

    assertEquals(1, set.destinations.size());
    Destination dest = set.destinations.get(0);
    assertEquals(1, dest.cables.size());
    assertEquals(500, dest.cables.get(0).amount);
    assertEquals(1 << PatchSource.LFO_LOCAL_1.ordinal(), dest.sourcesMask);

    // Range cables also reject NONE and deduplicate on (from, targetParamId, targetSource).
    set.addRangeCable(4, PatchSource.NONE, lfoReplacement);
    set.addRangeCable(4, PatchSource.LFO_LOCAL_1, noneCable);
    assertEquals(1, set.destinations.size());

    PatchCable rangeFirst = new PatchCable();
    rangeFirst.from = PatchSource.ENVELOPE_0;
    rangeFirst.amount = 300;
    set.addRangeCable(4, PatchSource.LFO_LOCAL_1, rangeFirst);

    PatchCable rangeSecond = new PatchCable();
    rangeSecond.from = PatchSource.ENVELOPE_0;
    rangeSecond.amount = 900;
    set.addRangeCable(4, PatchSource.LFO_LOCAL_1, rangeSecond);

    assertEquals(2, set.destinations.size());
    Destination rangeDest = set.destinations.get(1);
    assertEquals(1, rangeDest.cables.size());
    assertEquals(900, rangeDest.cables.get(0).amount);
  }

  @Test
  void virtualOledDrawWaveformBoundsChecksAndDrawsPositiveSamplesUpward() {
    VirtualOLED oled = new VirtualOLED();
    short[] samples = new short[] {16384, 16384, 16384, 16384};

    assertDoesNotThrow(() -> oled.drawWaveform(null, 0, 4));
    assertDoesNotThrow(() -> oled.drawWaveform(samples, -1, 4));
    assertDoesNotThrow(() -> oled.drawWaveform(samples, 10, 4));
    assertDoesNotThrow(() -> oled.drawWaveform(samples, 2, 100));

    oled.clear();
    oled.drawWaveform(samples, 0, samples.length);
    BufferedImage image = oled.getImage();

    // Positive sample (+16384) maps to y = 32 - (16384 * 64 / 65536) = 16 (upper half), not 48.
    boolean hasUpperWhite = false;
    boolean hasLowerWhite = false;
    for (int x = 0; x < VirtualOLED.WIDTH; x++) {
      if ((image.getRGB(x, 16) & 0x00FFFFFF) != 0) {
        hasUpperWhite = true;
      }
      if ((image.getRGB(x, 48) & 0x00FFFFFF) != 0) {
        hasLowerWhite = true;
      }
    }
    assertTrue(hasUpperWhite);
    assertFalse(hasLowerWhite);
  }

  @Test
  void virtualOledStopsScrollTimerWhenSwitchingDisplayModes() throws Exception {
    VirtualOLED oled = new VirtualOLED();
    oled.drawTrackScreen("SYNTH", "VERY_LONG_SCROLLING_TRACK_TITLE", "SUB");
    oled.drawParameterBar("PARAM", "CUTOFF", 0.5f, new String[] {"A", "B", "C", "D"}, 0);

    // Wait past one 250ms scroll tick and verify parameter bar row (y=36) was not wiped by scroll.
    Thread.sleep(320);
    BufferedImage image = oled.getImage();
    boolean hasBarBorder = (image.getRGB(10, 36) & 0x00FFFFFF) != 0;
    assertTrue(hasBarBorder);
  }

  @Test
  void firmwareDisplayPopupHasReadableContrastAndContextMenuHandlesNegativeAndEmptyInputs() {
    FirmwareDisplay display = FirmwareDisplay.get();
    display.getVirtualOLED().clear();
    display.displayPopup("SAVED");

    BufferedImage image = display.getVirtualOLED().getImage();
    // Popup border at (5,5) is white, interior background at (6,6) is black.
    assertEquals(0xFFFFFFFF, image.getRGB(5, 5));
    assertEquals(0xFF000000, image.getRGB(6, 6));

    assertDoesNotThrow(() -> display.displayContextMenu("MENU", null, 0));
    assertDoesNotThrow(() -> display.displayContextMenu("MENU", new String[0], 0));
    assertDoesNotThrow(() -> display.displayContextMenu("MENU", new String[] {"One", "Two"}, -1));
  }

  @Test
  void swingPicTransportHandlesRuntimeGridResizeTo16x24And24x16() {
    GridConfig.resetToHardware();
    SwingPicTransport transport = new SwingPicTransport();

    GridConfig.configure(PreferencesManager.GridMode.GRID_16x24);
    assertDoesNotThrow(() -> transport.setPadColour(23, 15, new RGB(11, 22, 33)));
    assertEquals(11, transport.getFramebuffer()[15][23].r);
    assertEquals(22, transport.getFramebuffer()[15][23].g);
    assertEquals(33, transport.getFramebuffer()[15][23].b);

    GridConfig.configure(PreferencesManager.GridMode.GRID_24x16);
    assertDoesNotThrow(() -> transport.setPadColour(15, 23, new RGB(44, 55, 66)));
    assertEquals(44, transport.getFramebuffer()[23][15].r);

    JButton[][] buttons = new JButton[24][18];
    buttons[23][15] = new JButton();
    transport.setPadButtons(buttons);
    assertDoesNotThrow(transport::flush);
    assertNotNull(buttons[23][15].getBackground());
    assertEquals(44, buttons[23][15].getBackground().getRed());

    assertDoesNotThrow(transport::clearAll);
    assertEquals(0, transport.getFramebuffer()[23][15].r);
  }
}
