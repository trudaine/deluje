package org.deluge.modulation.midi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.deluge.engine.FirmwareFactory;
import org.deluge.midi.MidiEngine;
import org.deluge.midi.MidiTransport;
import org.deluge.model.ClipModel;
import org.deluge.model.MidiTrackModel;
import org.deluge.model.PatternModel;
import org.deluge.model.ProjectModel;
import org.deluge.project.ProjectSerializer;
import org.deluge.shadow.midi.MidiMsg;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parity tests for {@link MidiParamCollection} and {@code <midiParams>} XML round-trip and live
 * tick-rate MIDI CC dispatch (C {@code instrument_clip.cpp:2400-2402, 2824-2835, 3033-3137} and
 * {@code midi_param_collection.cpp:60-75, 151-175, 214-230, 284-303, 322-348}).
 */
class MidiParamsAutomationParityTest {

  private static final class RecordingMidiTransport implements MidiTransport {
    private final List<MidiMsg> messages = new ArrayList<>();

    @Override
    public String getPortName() {
      return "Recording Mock Port";
    }

    @Override
    public boolean isOutput() {
      return true;
    }

    @Override
    public boolean isInput() {
      return false;
    }

    @Override
    public boolean sendMessage(MidiMsg msg) {
      messages.add(msg);
      return true;
    }

    @Override
    public void close() {}
  }

  @Test
  void autoparamValueToCcMatchesCppRoundingAndOverflowClamp() {
    // C midi_param_collection.cpp:214-230:
    // rShift = 25, roundingAmountToAdd = 1 << 24, maxValue = 2147483647 - (1 << 24).
    assertEquals(-64, MidiParamCollection.autoparamValueToCc(Integer.MIN_VALUE));
    assertEquals(0, MidiParamCollection.autoparamValueToCc(Integer.MIN_VALUE) + 64);

    assertEquals(0, MidiParamCollection.autoparamValueToCc(0));
    assertEquals(64, MidiParamCollection.autoparamValueToCc(0) + 64);

    // Without the maxValue clamp, Integer.MAX_VALUE + (1 << 24) overflows signed int32 negative.
    assertEquals(63, MidiParamCollection.autoparamValueToCc(Integer.MAX_VALUE));
    assertEquals(127, MidiParamCollection.autoparamValueToCc(Integer.MAX_VALUE) + 64);

    // C midi_instrument.h:146 — valueChangedEnoughToMatter checks oldValue >> 25 != newValue >> 25.
    assertFalse(MidiParamCollection.valueChangedEnoughToMatter(0, (1 << 25) - 1));
    assertTrue(MidiParamCollection.valueChangedEnoughToMatter(0, 1 << 25));
  }

  @Test
  void midiParamsXmlRoundTripsAndSortsCcsWithoutWritingSoundParams(@TempDir Path tempDir)
      throws Exception {
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song firmwareVersion="4.1.4" earliestCompatibleFirmware="4.1.0" bpm="120.0">
          <instruments>
            <midi presetName="Prophet" channel="2" />
          </instruments>
          <sessionClips>
            <instrumentClip clipName="MidiClip" midiChannel="2" length="96" isPlaying="1">
              <midiParams>
                <param>
                  <cc>74</cc>
                  <value>0x0000000080000000000000007FFFFFFF80000030</value>
                </param>
                <param>
                  <cc>none</cc>
                  <value>0x12345678</value>
                </param>
                <param>
                  <cc>1</cc>
                  <value>0x40000000</value>
                </param>
              </midiParams>
            </instrumentClip>
          </sessionClips>
        </song>
        """;

    File inputFile = tempDir.resolve("midi_params_in.xml").toFile();
    Files.writeString(inputFile.toPath(), xml);

    ProjectModel parsed = DelugeXmlParser.parseSong(inputFile);
    assertEquals(1, parsed.getTracks().size());
    MidiTrackModel midiTrack = (MidiTrackModel) parsed.getTracks().get(0);
    assertEquals(3, midiTrack.getMidiChannel()); // 0-indexed channel 2 -> 1-based channel 3

    ClipModel clip = midiTrack.getClips().get(0);
    List<MidiParam> midiParams = clip.getMidiParams();
    assertEquals(2, midiParams.size(), "<cc>none</cc> must be skipped, leaving CC 1 and CC 74");
    assertEquals(1, midiParams.get(0).cc, "MIDIParamVector keeps CCs sorted ascending");
    assertEquals(0x40000000, midiParams.get(0).param.currentValue);
    assertTrue(midiParams.get(0).param.nodes.isEmpty());

    assertEquals(74, midiParams.get(1).cc);
    assertEquals(0, midiParams.get(1).param.currentValue);
    assertEquals(2, midiParams.get(1).param.nodes.size());
    assertEquals(0, midiParams.get(1).param.nodes.get(0).pos);
    assertEquals(0x80000000, midiParams.get(1).param.nodes.get(0).value);
    assertFalse(midiParams.get(1).param.nodes.get(0).interpolated);
    assertEquals(48, midiParams.get(1).param.nodes.get(1).pos);
    assertEquals(0x7FFFFFFF, midiParams.get(1).param.nodes.get(1).value);
    assertTrue(midiParams.get(1).param.nodes.get(1).interpolated);

    // Verify deepCopy, restoreFrom, and PatternModel.ClipSnapshot preserve midiParams.
    ClipModel copy = clip.deepCopy("copy");
    assertEquals(2, copy.getMidiParams().size());
    assertEquals(74, copy.getMidiParams().get(1).cc);
    assertEquals(2, copy.getMidiParams().get(1).param.nodes.size());

    ClipModel restored = new ClipModel("restored", 1, 4);
    restored.restoreFrom(clip);
    assertEquals(2, restored.getMidiParams().size());

    PatternModel.ClipSnapshot snapshot =
        PatternModel.ClipSnapshot.fromClipModel(clip, 0, "Prophet");
    ClipModel fromSnap = new ClipModel("snap", 1, 4);
    snapshot.applyTo(fromSnap);
    assertEquals(2, fromSnap.getMidiParams().size());

    // Round-trip save and verify <midiParams> is written and <soundParams> is NOT written.
    File outputFile = tempDir.resolve("midi_params_out.xml").toFile();
    ProjectSerializer.save(parsed, outputFile);
    String savedXml = Files.readString(outputFile.toPath());
    assertTrue(savedXml.contains("<midiParams>"), savedXml);
    assertTrue(savedXml.contains("<cc>1</cc>"), savedXml);
    assertTrue(savedXml.contains("<cc>74</cc>"), savedXml);
    assertTrue(
        savedXml.contains("<value>0x0000000080000000000000007FFFFFFF80000030</value>"), savedXml);
    assertFalse(
        savedXml.contains("<soundParams"),
        "MIDI clips must not write <soundParams> (instrument_clip.cpp:2400-2425)");

    int idxCc1 = savedXml.indexOf("<cc>1</cc>");
    int idxCc74 = savedXml.indexOf("<cc>74</cc>");
    assertTrue(idxCc1 < idxCc74, "CCs must serialize in ascending CC order");
  }

  @Test
  void liveTickDomainInterpolationDispatchesMidiCcWhenThresholdCrossed(@TempDir Path tempDir)
      throws Exception {
    MidiEngine previousEngine = MidiEngine.instance;
    MidiEngine engine = new MidiEngine();
    RecordingMidiTransport transport = new RecordingMidiTransport();
    engine.addOutputTransport(transport);
    try {
      String xml =
          """
          <?xml version="1.0" encoding="UTF-8"?>
          <song firmwareVersion="4.1.4" earliestCompatibleFirmware="4.1.0" bpm="120.0">
            <instruments>
              <midi presetName="SynthOut" channel="3" />
            </instruments>
            <sessionClips>
              <instrumentClip clipName="SweepClip" midiChannel="3" length="96" isPlaying="1">
                <midiParams>
                  <param>
                    <cc>74</cc>
                    <value>0x0000000080000000000000007FFFFFFF80000030</value>
                  </param>
                </midiParams>
              </instrumentClip>
            </sessionClips>
          </song>
          """;

      File inputFile = tempDir.resolve("midi_sweep.xml").toFile();
      Files.writeString(inputFile.toPath(), xml);

      ProjectModel project = FirmwareFactory.createSong(DelugeXmlParser.parseSong(inputFile));
      ClipModel clip = project.getTracks().get(0).getActiveClip();

      // At setPos(0), currentValue jumps from 0x00000000 (CC 64) to 0x80000000 (CC 0).
      clip.setPos(0);
      assertEquals(1, transport.messages.size());
      MidiMsg firstMsg = transport.messages.get(0);
      assertEquals(0xB3, firstMsg.data1 & 0xFF); // CC on channel 3 (0-indexed)
      assertEquals(74, firstMsg.data2 & 0xFF);
      assertEquals(0, firstMsg.data3 & 0xFF);

      transport.messages.clear();

      // Start tick at pos 0 sets up valueIncrementPerHalfTick toward 0x7FFFFFFF at pos 48.
      clip.processCurrentPos(0);

      // Step tick-by-tick through pos 1..48; tickTicks advances interpolation because
      // mayInterpolate is false for FirmwareMidiInstrument (clip.cpp:311).
      int prevCcValue = 0;
      for (int tick = 1; tick <= 48; tick++) {
        clip.lastProcessedPos = tick;
        clip.processCurrentPos(1);
      }

      assertFalse(transport.messages.isEmpty(), "Tick-rate ramp must emit MIDI CC messages");
      for (MidiMsg msg : transport.messages) {
        assertEquals(0xB3, msg.data1 & 0xFF);
        assertEquals(74, msg.data2 & 0xFF);
        int ccVal = msg.data3 & 0xFF;
        assertTrue(
            ccVal >= prevCcValue,
            "Interpolated MIDI CC ramp must be monotonic: " + prevCcValue + " -> " + ccVal);
        prevCcValue = ccVal;
      }
      assertEquals(127, prevCcValue, "Ramp reaching 0x7FFFFFFF at pos 48 must end at CC value 127");
    } finally {
      MidiEngine.instance = previousEngine;
    }
  }
}
