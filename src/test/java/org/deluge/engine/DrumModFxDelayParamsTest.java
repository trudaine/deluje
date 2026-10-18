package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;
import org.deluge.firmware2.Param;
import org.deluge.firmware2.Patcher;
import org.deluge.model.KitTrackModel;
import org.deluge.model.SoundDrum;
import org.deluge.xml.KitXmlParser;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

/**
 * A kit drum's mod-FX rate/depth/offset/feedback and delay rate/feedback are the drum's own params
 * (C sound.cpp:2499-2506, 2605-2606), so the engine must play the file's knobs, through the same
 * final-value curve as a synth.
 */
class DrumModFxDelayParamsTest {

  @Test
  void fileKnobsReachTheDrumsFw2Sound() throws Exception {
    String xml =
        "<sound name=\"KICK\"><defaultParams modFXRate=\"0x20000000\" modFXDepth=\"0x40000000\""
            + " modFXOffset=\"0x10000000\" modFXFeedback=\"0x30000000\""
            + " delayRate=\"0xE0000000\" delayFeedback=\"0x18000000\"/></sound>";
    Element el =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
            .getDocumentElement();
    SoundDrum sd = KitXmlParser.parseSoundDrum(el, "KICK");
    assertEquals(0x20000000, sd.getModFxRateQ31());

    FirmwareSound fs = new FirmwareSound();
    fs.isDrum = true;
    FirmwareFactory.mapDrumToSound(sd, fs, 0, new KitTrackModel("K"));
    fs.syncParamsToFw2();

    assertEquals(
        Patcher.computeFinalValueForParam(Param.GLOBAL_MOD_FX_RATE, 0x20000000),
        fs.fw2Sound.modFXRateIncrement);
    assertEquals(
        Patcher.computeFinalValueForParam(Param.GLOBAL_MOD_FX_DEPTH, 0x40000000),
        fs.fw2Sound.modFXDepth);
    assertEquals(0x10000000, fs.fw2Sound.modFXOffset);
    assertEquals(0x30000000, fs.fw2Sound.modFXFeedback);
    assertEquals(
        Patcher.computeFinalValueForParam(Param.GLOBAL_DELAY_RATE, 0xE0000000),
        fs.fw2Sound.delayUserRate);
    assertEquals(
        Patcher.computeFinalValueForParam(Param.GLOBAL_DELAY_FEEDBACK, 0x18000000),
        fs.fw2Sound.delayFeedbackAmount);
  }
}
