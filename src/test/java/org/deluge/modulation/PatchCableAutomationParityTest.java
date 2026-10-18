package org.deluge.modulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareKit;
import org.deluge.engine.FirmwareSound;
import org.deluge.firmware2.Param;
import org.deluge.model.ClipModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.NoteRowModel;
import org.deluge.model.PatchCable;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.modulation.automation.ParamNode;
import org.deluge.modulation.patch.Destination;
import org.deluge.modulation.patch.PatchSource;
import org.deluge.project.ProjectSerializer;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies C++ parity for automated {@code PatchCable} amounts across XML parsing/serialization,
 * {@code FirmwareFactory} mapping, and {@code ParamManager} sample-rate/timeline stepping (C {@code
 * patch_cable_set.cpp:576-608, 727-752, 848-1030, 1181-1189}).
 */
class PatchCableAutomationParityTest {

  @Test
  void synthClipPatchCablesRoundTripAutomationNodesAndZeroStaticAmount(@TempDir Path tempDir)
      throws Exception {
    // Top-level cable has static amount 0x00000000 + two interpolated nodes at pos 0 and 48.
    // Nested depthControlledBy cable has static amount 0x20000000 + one uninterpolated node at 24.
    // A second unautomated cable with amount 0x00000000 must be dropped (containsSomething(0)).
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song firmwareVersion="4.1.0">
          <sessionClips>
            <instrumentClip length="96" trackName="SYN1">
              <soundParams volume="0x4CCCCCA8">
                <patchCables>
                  <patchCable source="lfo1" destination="lpfFrequency" polarity="bipolar"
                      amount="0x0000000040000000800000007FFFFFFF80000030">
                    <depthControlledBy>
                      <patchCable source="envelope2" polarity="unipolar"
                          amount="0x200000006000000000000018"/>
                    </depthControlledBy>
                  </patchCable>
                  <patchCable source="velocity" destination="volume" polarity="unipolar"
                      amount="0x00000000"/>
                </patchCables>
              </soundParams>
              <noteRows>
                <noteRow y="60" noteDataWithLift="0x0000000000000018404014"/>
              </noteRows>
            </instrumentClip>
          </sessionClips>
          <instruments>
            <sound name="SYN1">
              <defaultParams/>
            </sound>
          </instruments>
        </song>
        """;

    ProjectModel project =
        DelugeXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "cable_auto.xml");
    SynthTrackModel synth = (SynthTrackModel) project.getTracks().get(0);
    ClipModel clip = synth.getClips().get(0);

    // Only the automated cable survives; the unautomated 0x00000000 cable is dropped.
    assertEquals(1, clip.getPatchCables().size());
    PatchCable cable = clip.getPatchCables().get(0);
    assertEquals("lfo1", cable.source());
    assertEquals("lpfFrequency", cable.destination());
    assertEquals(0, cable.resolveAmountQ31());
    assertEquals(2, cable.automationNodes().size());
    assertNode(0, 0x40000000, true, cable.automationNodes().get(0));
    assertNode(48, 0x7FFFFFFF, true, cable.automationNodes().get(1));

    assertEquals(1, cable.depthControlledBy().size());
    PatchCable depth = cable.depthControlledBy().get(0);
    assertEquals("envelope2", depth.source());
    assertEquals(0x20000000, depth.resolveAmountQ31());
    assertEquals(1, depth.automationNodes().size());
    assertNode(24, 0x60000000, false, depth.automationNodes().get(0));

    // Serialize back to XML and re-parse.
    File savedFile = tempDir.resolve("roundtrip_cable.xml").toFile();
    ProjectSerializer.save(project, savedFile);
    String savedXml = Files.readString(savedFile.toPath());
    assertTrue(
        savedXml.contains("0x0000000040000000800000007FFFFFFF80000030"),
        "Serialized clip patchCable must preserve 16-char automation hex nodes");
    assertTrue(
        savedXml.contains("0x200000006000000000000018"),
        "Serialized depthControlledBy patchCable must preserve automation hex nodes");

    ProjectModel reparsed =
        DelugeXmlParser.parseSong(
            new ByteArrayInputStream(savedXml.getBytes(StandardCharsets.UTF_8)), "roundtrip.xml");
    ClipModel reparsedClip = reparsed.getTracks().get(0).getClips().get(0);
    assertEquals(1, reparsedClip.getPatchCables().size());
    assertEquals(2, reparsedClip.getPatchCables().get(0).automationNodes().size());
    assertEquals(
        1,
        reparsedClip.getPatchCables().get(0).depthControlledBy().get(0).automationNodes().size());
  }

  @Test
  void presetDefaultParamsIgnoresAutomationNodesOnPatchCables() throws Exception {
    // C sound.cpp:3651, 3679 passes readAutomationUpToPos=0 when reading preset <defaultParams>.
    String presetXml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <sound firmwareVersion="4.1.0">
          <defaultParams>
            <patchCables>
              <patchCable source="lfo1" destination="pitch"
                  amount="0x100000004000000080000018"/>
            </patchCables>
          </defaultParams>
        </sound>
        """;

    SynthTrackModel synth =
        DelugeXmlParser.parseSynth(
            new ByteArrayInputStream(presetXml.getBytes(StandardCharsets.UTF_8)), "preset.xml");
    assertNotNull(synth);
    assertEquals(1, synth.getModulation().getPatchCables().size());
    PatchCable cable = synth.getModulation().getPatchCables().get(0);
    assertEquals(0x10000000, cable.resolveAmountQ31());
    assertTrue(
        cable.automationNodes().isEmpty(),
        "Preset <defaultParams> must ignore automation nodes (readAutomationUpToPos == 0)");
  }

  @Test
  void kitNoteRowPatchCablesRoundTripAndLoadIntoDrumSound(@TempDir Path tempDir) throws Exception {
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song firmwareVersion="4.1.0">
          <sessionClips>
            <instrumentClip length="96" trackName="KIT1">
              <noteRows>
                <noteRow drumIndex="0" length="48"
                    noteDataWithLift="0x0000000000000018404014">
                  <soundParams volume="0x4CCCCCA8">
                    <patchCables>
                      <patchCable source="lfo1" destination="lpfFrequency"
                          amount="0x0000000030000000800000006000000080000018"/>
                    </patchCables>
                  </soundParams>
                </noteRow>
              </noteRows>
            </instrumentClip>
          </sessionClips>
          <instruments>
            <kit name="KIT1">
              <soundSources>
                <sound name="KICK">
                  <osc1 type="sine"/>
                  <defaultParams/>
                </sound>
              </soundSources>
            </kit>
          </instruments>
        </song>
        """;

    ProjectModel project =
        DelugeXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "kit_cable.xml");
    KitTrackModel kitTrack = (KitTrackModel) project.getTracks().get(0);
    ClipModel clip = kitTrack.getClips().get(0);
    NoteRowModel row = clip.getNoteRowsMap().get(0);
    assertNotNull(row);
    assertEquals(1, row.getPatchCables().size());
    assertEquals(2, row.getPatchCables().get(0).automationNodes().size());

    FirmwareKit fwKit = new FirmwareKit();
    FirmwareFactory.applyModelToLiveSound(kitTrack, fwKit);
    FirmwareSound drumSound = fwKit.drumSounds.get(0);
    assertTrue(
        drumSound.paramManager.mightContainAutomation(),
        "Drum ParamManager must report automation when a row patch cable is automated");

    File savedFile = tempDir.resolve("roundtrip_kit_cable.xml").toFile();
    ProjectSerializer.save(project, savedFile);
    String savedXml = Files.readString(savedFile.toPath());
    assertTrue(
        savedXml.contains("0x0000000030000000800000006000000080000018"),
        "Serialized kit noteRow must write automated patchCables inside <soundParams>");
  }

  @Test
  void paramManagerStepsAutomatedPatchCableAmountIntoFirmwareSound() {
    SynthTrackModel model = new SynthTrackModel("Synth");
    model.setClipParamsFresh(true);
    ClipModel clip = new ClipModel("Clip 1", 1, 4);
    List<ParamNode> nodes =
        List.of(new ParamNode(0, 0x10000000, true), new ParamNode(48, 0x50000000, true));
    clip.getPatchCables()
        .add(
            new PatchCable(
                "lfo1", "lpfFrequency", 0.0f, PatchCable.Polarity.BIPOLAR, List.of(), nodes, 0));
    model.addClip(clip);

    FirmwareSound sound = new FirmwareSound();
    FirmwareFactory.applyModelToLiveSound(model, sound);

    assertTrue(
        sound.paramManager.mightContainAutomation(),
        "ParamManager must report automation when only a patch cable is automated");
    assertFalse(sound.paramManager.isAnyParamInterpolating());

    // Step timeline at pos 0 -> sets up sample slope toward 0x50000000 over 48 ticks.
    sound.paramManager.processCurrentPos(0, 0, 96, false, false, true);
    assertEquals(48, sound.paramManager.ticksTilNextEvent);
    assertTrue(sound.paramManager.isAnyParamInterpolating());

    Destination dest = sound.paramManager.getPatchCableSet().destinations.get(0);
    assertEquals(Param.LOCAL_LPF_FREQ, dest.paramId);
    org.deluge.modulation.patch.PatchCable engineCable = dest.cables.get(0);
    assertEquals(PatchSource.LFO_GLOBAL_1, engineCable.from);
    assertEquals(0x10000000, engineCable.getAmount());

    // Advance samples (timePerTimerTickInverse = 0x10000000 -> ~0.125 ticks per sample).
    sound.paramManager.tickSamples(64, 0x10000000);
    int afterTickSamples = engineCable.getAmount();
    assertTrue(
        afterTickSamples > 0x10000000 && afterTickSamples < 0x50000000,
        "tickSamples must ramp the automated patch cable amount toward the next node");

    // Verify syncParamsToFw2 copies the live automated cable amount into fw2Sound.
    sound.syncParamsToFw2();
    int fw2Amount = sound.fw2Sound.patchCableSet.destinations.get(0).cables.get(0).amount;
    assertEquals(afterTickSamples, fw2Amount);

    // Ping-pong bounce flips the active cable slope (C patch_cable_set.cpp:1181-1189).
    int slopeBefore = engineCable.param.valueIncrementPerHalfTick;
    assertTrue(slopeBefore > 0);
    sound.paramManager.notifyPingpongOccurred();
    assertEquals(-slopeBefore, engineCable.param.valueIncrementPerHalfTick);
    assertEquals(0, sound.paramManager.ticksTilNextEvent);
  }

  private static void assertNode(int pos, int value, boolean interpolated, ParamNode actual) {
    assertEquals(pos, actual.pos);
    assertEquals(value, actual.value);
    assertEquals(interpolated, actual.interpolated);
  }
}
