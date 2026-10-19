package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.firmware2.Arpeggiator.ArpMode;
import org.deluge.firmware2.Arpeggiator.SyncLevel;
import org.deluge.firmware2.Arpeggiator.SyncType;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.project.ProjectSerializer;
import org.deluge.xml.DelugeHexMapper;
import org.deluge.xml.SongXmlParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies C++ parity for unpatched arpeggiator and randomizer parameters, arp-off randomizer
 * routing, {@code Sound.invertReversed} sample reversal, and live synced arp tempo updates against
 * {@code mod_controllable_audio.cpp}, {@code arpeggiator.cpp}, {@code sound.cpp}, and {@code
 * voice.cpp}.
 */
class ArpRandomizerAndUnpatchedParamsParityTest {

  @BeforeEach
  void resetPrng() {
    Functions.resetNoiseSeed();
  }

  private static Sample createTestSample(int frames) {
    Sample sample = new Sample();
    sample.numChannels = 1;
    sample.byteDepth = 2;
    sample.sampleRate = 44100;
    sample.lengthInSamples = frames;
    sample.data = new int[frames];
    for (int i = 0; i < frames; i++) {
      sample.data[i] = i << 16;
    }
    return sample;
  }

  private static Sound createSampleSound(boolean sampleReversed) {
    Sound sound = new Sound();
    Sound.initParams(sound.patchedParamValues);
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    sound.patchedParamValues[Param.LOCAL_VOLUME] = 0;
    sound.oscTypes[0] = Oscillator.OscType.SAMPLE;
    sound.samples[0] = createTestSample(128);
    sound.sampleEndPoint[0] = 128;
    sound.sampleReverse[0] = sampleReversed;
    return sound;
  }

  @Test
  void initParamsAndUpdateParamFromUnpatchedMatchCppScaling() {
    // C mod_controllable_audio.cpp:113-127 — UNPATCHED_ARP_GATE defaults to 0 (50% duty cycle)
    // and UNPATCHED_NOTE_PROBABILITY defaults to 0x7FFFFFFF (100% probability).
    int[] params = new int[200];
    Sound.initParams(params);
    assertEquals(0, params[Param.UNPATCHED_ARP_GATE]);
    assertEquals(0x7FFFFFFF, params[Param.UNPATCHED_NOTE_PROBABILITY]);
    assertEquals(Integer.MIN_VALUE, params[Param.UNPATCHED_REVERSE_PROBABILITY]);
    assertEquals(Integer.MIN_VALUE, params[Param.UNPATCHED_SPREAD_VELOCITY]);

    // C arpeggiator.cpp:1968-1983, sound.cpp:2468 — gate stays signed Q31 while the 14 unsigned
    // randomizer/sequence fields add 2147483648u (Integer.MIN_VALUE).
    Arpeggiator.Settings settings = new Arpeggiator.Settings();
    settings.updateParamFromUnpatched(Param.UNPATCHED_ARP_GATE, 0);
    assertEquals(0, settings.gate);

    settings.updateParamFromUnpatched(Param.UNPATCHED_NOTE_PROBABILITY, 0x7FFFFFFF);
    assertEquals(0xFFFFFFFF, settings.noteProbability);

    settings.updateParamFromUnpatched(Param.UNPATCHED_NOTE_PROBABILITY, Integer.MIN_VALUE);
    assertEquals(0, settings.noteProbability);

    settings.updateParamFromUnpatched(Param.UNPATCHED_REVERSE_PROBABILITY, 0);
    assertEquals(0x80000000, settings.reverseProbability);
  }

  @Test
  void arpOffNoteProbabilityAndSpreadVelocityRunThroughArpeggiator() {
    // C sound.cpp:1540, arpeggiator.cpp:740-780 — Sound::noteOn routes ArpMode::OFF notes through
    // ArpeggiatorSynth::noteOn so noteProbability and spreadVelocity apply even with arp off.
    Sound sound = createSampleSound(false);
    sound.arpSettings.mode = ArpMode.OFF;

    // 0% noteProbability drops every note when ArpMode.OFF.
    sound.arpSettings.noteProbability = 0;
    for (int i = 0; i < 8; i++) {
      sound.triggerNote(60 + i, 100);
    }
    int activeVoicesAtZeroProb = 0;
    for (Voice v : sound.voices) {
      if (v.active) activeVoicesAtZeroProb++;
    }
    assertEquals(0, activeVoicesAtZeroProb, "0% noteProbability must drop all arp-off notes");

    // 100% noteProbability with max spreadVelocity varies velocity across triggered notes.
    sound.arpSettings.noteProbability = 0xFFFFFFFF;
    sound.arpSettings.spreadVelocity = 0xFFFFFFFF;
    Set<Integer> velocities = new HashSet<>();
    for (int i = 0; i < 12; i++) {
      sound.triggerNote(48 + i, 64);
      for (Voice v : sound.voices) {
        if (v.active && v.note == 48 + i) {
          velocities.add(v.velocity);
        }
      }
      sound.releaseNote(48 + i);
    }
    assertTrue(
        velocities.size() > 1,
        "spreadVelocity must vary velocity in ArpMode.OFF, got=" + velocities);
  }

  @Test
  void reverseProbabilityFlipsSamplePlayDirectionInArpOffAndArpOnModes() {
    // C sound.cpp:1553, 2371, voice.cpp:297-298, sample_controls.cpp:52-54 — invertReversed XORs
    // with sampleReverse[s] when setting up sample playback bounds.
    Sound forwardSampleSound = createSampleSound(false);
    forwardSampleSound.arpSettings.mode = ArpMode.OFF;
    forwardSampleSound.arpSettings.noteProbability = 0xFFFFFFFF;
    forwardSampleSound.arpSettings.reverseProbability = 0xFFFFFFFF;

    forwardSampleSound.triggerNote(60, 100);
    assertTrue(forwardSampleSound.invertReversed);
    Voice v0 = forwardSampleSound.voices.get(0);
    assertTrue(v0.active);
    assertEquals(
        -1,
        v0.unisonParts[0].sources[0].voiceSample.reader.playDirection,
        "100% reverseProbability must flip a forward sample to reverse (-1)");

    // When sampleReverse[0] is already true, invertReversed XORs it back to forward (+1).
    Sound reversedSampleSound = createSampleSound(true);
    reversedSampleSound.arpSettings.mode = ArpMode.OFF;
    reversedSampleSound.arpSettings.noteProbability = 0xFFFFFFFF;
    reversedSampleSound.arpSettings.reverseProbability = 0xFFFFFFFF;

    reversedSampleSound.triggerNote(60, 100);
    assertEquals(
        1,
        reversedSampleSound.voices.get(0)
            .unisonParts[0]
            .sources[0]
            .voiceSample
            .reader
            .playDirection,
        "100% reverseProbability must flip a reversed sample back to forward (+1)");

    // Arp-on mode also sets Sound.invertReversed from arpInstr.invertReversed on arp ticks.
    Sound arpOnSound = createSampleSound(false);
    arpOnSound.arpSettings.mode = ArpMode.ARP;
    arpOnSound.arpSettings.noteProbability = 0xFFFFFFFF;
    arpOnSound.arpSettings.reverseProbability = 0xFFFFFFFF;
    arpOnSound.arpSettings.gate = 0;
    arpOnSound.arpPhaseIncrement = 16777216;

    arpOnSound.triggerNote(60, 100);
    int[] interleaved = new int[128];
    arpOnSound.renderInternal(interleaved, 64, null);
    assertFalse(arpOnSound.voices.isEmpty(), "arp-on tick must trigger voice");
    assertTrue(arpOnSound.voices.get(0).active, "arp-on tick must trigger voice");
    assertEquals(
        -1,
        arpOnSound.voices.get(0).unisonParts[0].sources[0].voiceSample.reader.playDirection,
        "arp-on 100% reverseProbability must flip sample playDirection to -1");

    // C sound.cpp:1769-1777 — releaseAllNotes resets invertReversed and arpeggiator state.
    arpOnSound.releaseAllNotes();
    assertFalse(arpOnSound.invertReversed);
    assertFalse(arpOnSound.arpeggiator.hasAnyInputNotesActive());
  }

  @Test
  void firmwareSoundSyncsUnpatchedArpParamsAndLiveSyncedTempo() {
    FirmwareSound sound = new FirmwareSound();
    sound.arpSettings.mode = ArpMode.ARP;
    sound.arpSettings.syncType = SyncType.EVEN;
    sound.arpSettings.syncLevel = SyncLevel.SYNC_LEVEL_16TH;
    sound.arpSettings.gate = 0x3FFFFFFF; // 75% gate set directly on arpSettings
    sound.currentBpm = 120.0f;

    StereoSample[] buf = new StereoSample[64];
    for (int i = 0; i < buf.length; i++) buf[i] = new StereoSample();

    // Unset paramKnobs[101..115] must NOT clobber direct arpSettings edits.
    sound.renderInternal(buf, buf.length, null);
    assertEquals(0x3FFFFFFF, sound.arpSettings.gate);
    int phaseIncAt120 = sound.fw2Sound.arpPhaseIncrement;
    assertTrue(phaseIncAt120 > 0);

    // Changing currentBpm must update synced arpPhaseIncrement on the very next render block.
    sound.currentBpm = 180.0f;
    sound.renderInternal(buf, buf.length, null);
    int phaseIncAt180 = sound.fw2Sound.arpPhaseIncrement;
    assertTrue(
        phaseIncAt180 > phaseIncAt120,
        "synced arpPhaseIncrement must increase when BPM rises from 120 to 180");

    // Setting paramKnobs[Param.UNPATCHED_ARP_GATE] = 0 (50% gate, matching paramNeutralValues[101])
    // must override arpSettings.gate because UNSET_ARP_KNOB was replaced with an explicit 0.
    sound.paramKnobs[Param.UNPATCHED_ARP_GATE] = 0;
    sound.renderInternal(buf, buf.length, null);
    assertEquals(0, sound.arpSettings.gate);

    // Live AutoParam automation on UNPATCHED_NOTE_PROBABILITY updates arpSettings.noteProbability.
    sound.paramManager.recordParamValue(Param.UNPATCHED_NOTE_PROBABILITY, 0, 0);
    sound.paramManager.setPlayPos(0, 96, false);
    sound.renderInternal(buf, buf.length, null);
    assertEquals(0x80000000, sound.arpSettings.noteProbability);
  }

  @Test
  void xmlSoundParamsParsesAndSerializesUnpatchedArpAndRandomizerAttributes(@TempDir Path tempDir)
      throws Exception {
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song firmwareVersion="4.1.4-c">
          <instruments>
            <sound name="ARP RAND TEST" presetSlot="0" presetSubSlot="-1" mode="subtractive"
                   polyphonic="poly" voicePriority="1" lpfMode="24dB" modFXType="none">
              <osc1 type="saw" transpose="0" cents="0" retrigPhase="-1" />
              <osc2 type="square" transpose="0" cents="0" retrigPhase="-1" />
              <lfo1 type="triangle" syncLevel="0" />
              <lfo2 type="sine" />
              <unison num="1" detune="8" />
              <delay pingPong="1" analog="0" syncLevel="7" />
              <compressor syncLevel="6" attack="327244" release="936" />
              <arpeggiator mode="arp" numOctaves="2" syncLevel="7" syncType="0" />
              <defaultParams
                arpeggiatorGate="0x00000000"
                arpeggiatorRate="0x00000000"
                noteProbability="0x40000000"
                reverseProbability="0x20000000"
                rhythm="0x88000000"
                sequenceLength="0x90000000"
                spreadVelocity="0xC0000000"
                oscAVolume="0x7FFFFFFF"
                oscBVolume="0x80000000"
                volume="0x4CCCCCA8" />
            </sound>
          </instruments>
          <sessionClips>
            <instrumentClip length="96" instrumentPresetSlot="0" instrumentPresetSubSlot="-1">
              <arpeggiator mode="arp" numOctaves="2" syncLevel="7" syncType="0" />
              <soundParams
                arpeggiatorGate="0x10000000"
                noteProbability="0x40000000"
                reverseProbability="0x20000000"
                rhythm="0x88000000"
                sequenceLength="0x90000000"
                spreadVelocity="0xC0000000"
                oscAVolume="0x7FFFFFFF"
                oscBVolume="0x80000000"
                volume="0x4CCCCCA8" />
              <noteRows />
            </instrumentClip>
          </sessionClips>
        </song>
        """;

    Path inFile = tempDir.resolve("arp_rand_in.xml");
    Files.writeString(inFile, xml, StandardCharsets.UTF_8);

    ProjectModel project = SongXmlParser.parseSong(inFile.toFile());
    SynthTrackModel synth = (SynthTrackModel) project.getTracks().get(0);
    var rawKnobs = synth.getRawKnobs().getRawParamKnobs();
    assertEquals(0x10000000, rawKnobs.get(Param.UNPATCHED_ARP_GATE));
    assertEquals(0x40000000, rawKnobs.get(Param.UNPATCHED_NOTE_PROBABILITY));
    assertEquals(0x20000000, rawKnobs.get(Param.UNPATCHED_REVERSE_PROBABILITY));
    assertEquals(0x88000000, rawKnobs.get(Param.UNPATCHED_ARP_RHYTHM));
    assertEquals(0x90000000, rawKnobs.get(Param.UNPATCHED_ARP_SEQUENCE_LENGTH));
    assertEquals(0xC0000000, rawKnobs.get(Param.UNPATCHED_SPREAD_VELOCITY));

    // Verify FirmwareFactory + FirmwareSound wire the parsed knobs into fw2Sound.arpSettings.
    FirmwareSound fwSound = new FirmwareSound();
    FirmwareFactory.applyModelToLiveSound(synth, fwSound);
    StereoSample[] buf = new StereoSample[16];
    for (int i = 0; i < buf.length; i++) buf[i] = new StereoSample();
    fwSound.renderInternal(buf, buf.length, null);
    assertEquals(0x10000000, fwSound.arpSettings.gate);
    assertEquals(0x40000000 + Integer.MIN_VALUE, fwSound.arpSettings.noteProbability);
    assertEquals(0x20000000 + Integer.MIN_VALUE, fwSound.arpSettings.reverseProbability);
    assertEquals(0x88000000 + Integer.MIN_VALUE, fwSound.arpSettings.rhythm);
    assertEquals(0x90000000 + Integer.MIN_VALUE, fwSound.arpSettings.sequenceLength);
    assertEquals(0xC0000000 + Integer.MIN_VALUE, fwSound.arpSettings.spreadVelocity);

    // Round-trip through ProjectSerializer and verify non-default unpatched arp knobs persist.
    Path outFile = tempDir.resolve("arp_rand_out.xml");
    ProjectSerializer.save(project, outFile.toFile());
    String serialized = Files.readString(outFile, StandardCharsets.UTF_8);
    assertTrue(serialized.contains("arpeggiatorGate=\"0x10000000\""));
    assertTrue(serialized.contains("noteProbability=\"0x40000000\""));
    assertTrue(serialized.contains("reverseProbability=\"0x20000000\""));
    assertTrue(serialized.contains("rhythm=\"0x88000000\""));
    assertTrue(serialized.contains("sequenceLength=\"0x90000000\""));
    assertTrue(serialized.contains("spreadVelocity=\"0xC0000000\""));
    assertNotEquals(-1, serialized.indexOf("soundParams"));

    // Verify FirmwareFactory.createSong and subsequent UI ArpModel edits (synth.setArp) +
    // clip switches (synth.setActiveClipIndex) survive syncParamsToFw2 without stale rawKnobs.
    ProjectModel songWithEngine = FirmwareFactory.createSong(project);
    SynthTrackModel liveSynth = (SynthTrackModel) songWithEngine.getTracks().get(0);
    FirmwareSound liveSound = (FirmwareSound) liveSynth.getClips().get(0).getSound();
    liveSound.renderInternal(buf, buf.length, null);
    assertEquals(0x10000000, liveSound.arpSettings.gate);

    // Simulate ArpPanel UI edit: setArp updates only the changed UNPATCHED_ARP_GATE knob in
    // rawParamKnobs, leaving the other 5 XML <soundParams> knobs intact.
    liveSynth.setArp(liveSynth.getArp().toBuilder().active(true).gate(0.75f).build());
    FirmwareFactory.applyModelToLiveSound(liveSynth, liveSound);
    int expectedUiGate = DelugeHexMapper.unipolarFloatToQ31Unified(0.75f);
    assertEquals(0x3FFFFFFF, expectedUiGate);
    liveSound.renderInternal(buf, buf.length, null);
    assertEquals(expectedUiGate, liveSound.arpSettings.gate);
    assertEquals(0x40000000 + Integer.MIN_VALUE, liveSound.arpSettings.noteProbability);
    assertEquals(0x20000000 + Integer.MIN_VALUE, liveSound.arpSettings.reverseProbability);
    assertEquals(0x88000000 + Integer.MIN_VALUE, liveSound.arpSettings.rhythm);
    assertEquals(0x90000000 + Integer.MIN_VALUE, liveSound.arpSettings.sequenceLength);
    assertEquals(0xC0000000 + Integer.MIN_VALUE, liveSound.arpSettings.spreadVelocity);

    // Save after UI edit and verify both the edited gate and all 5 untouched <soundParams>
    // fields survive serialization and reload.
    Path editedFile = tempDir.resolve("arp_rand_ui_edited.xml");
    ProjectSerializer.save(songWithEngine, editedFile.toFile());
    String editedXml = Files.readString(editedFile, StandardCharsets.UTF_8);
    assertTrue(editedXml.contains("arpeggiatorGate=\"0x3FFFFFFF\""));
    assertTrue(editedXml.contains("noteProbability=\"0x40000000\""));
    assertTrue(editedXml.contains("reverseProbability=\"0x20000000\""));
    assertTrue(editedXml.contains("rhythm=\"0x88000000\""));
    assertTrue(editedXml.contains("sequenceLength=\"0x90000000\""));
    assertTrue(editedXml.contains("spreadVelocity=\"0xC0000000\""));

    ProjectModel reloadedEdited = SongXmlParser.parseSong(editedFile.toFile());
    SynthTrackModel reloadedSynth = (SynthTrackModel) reloadedEdited.getTracks().get(0);
    assertEquals(
        0x3FFFFFFF, reloadedSynth.getRawKnobs().getRawParamKnobs().get(Param.UNPATCHED_ARP_GATE));
    assertEquals(
        0x40000000,
        reloadedSynth.getRawKnobs().getRawParamKnobs().get(Param.UNPATCHED_NOTE_PROBABILITY));

    // Simulate clip switch to a second clip with its own ArpModel gate (0.25 -> -0x40000000).
    org.deluge.model.ClipModel secondClip = new org.deluge.model.ClipModel("Clip 2", 8, 16);
    secondClip.setSound(liveSound);
    secondClip.setArp(
        org.deluge.model.ArpModel.defaultConfig().toBuilder().active(true).gate(0.25f).build());
    liveSynth.addClip(secondClip);
    liveSynth.setActiveClipIndex(1);
    int expectedClip2Gate = DelugeHexMapper.unipolarFloatToQ31Unified(0.25f);
    assertEquals(0xC0000000, expectedClip2Gate);
    liveSound.renderInternal(buf, buf.length, null);
    assertEquals(expectedClip2Gate, liveSound.arpSettings.gate);
  }

  @Test
  void testMultiClipSoundParamsWithoutArpeggiatorTagAndDefaultSentinels(@TempDir Path tempDir)
      throws Exception {
    // 1. ArpModel.defaultConfig() matches C++ defaults for all 15 unpatched arp params,
    //    so seqLength=8 and noteProbability=0.0f are treated as real non-default values.
    org.deluge.model.ArpModel def = org.deluge.model.ArpModel.defaultConfig();
    assertEquals(0, def.seqLength());
    assertEquals(1.0f, def.noteProbability());
    for (int pid = Param.UNPATCHED_ARP_GATE; pid <= Param.UNPATCHED_SPREAD_VELOCITY; pid++) {
      assertEquals(
          Arpeggiator.getUnpatchedArpDefault(pid),
          org.deluge.xml.ArpXmlCodec.unpatchedQ31FromArp(def, pid),
          "ArpModel.defaultConfig() must map to C++ default for param " + pid);
    }

    // 2. Multi-clip XML where Clip 1 has <soundParams> (with sequenceLength=8, noteProbability=0%)
    //    but NO <arpeggiator> tag: Clip 0 stays active on SynthTrackModel, and switching to Clip 1
    //    applies Clip 1's unpatched arp params cleanly.
    int seqLen8Q31 = Functions.computeFinalValueForStandardMenuItem(8);
    String seqLen8Hex = String.format("0x%08X", seqLen8Q31);
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song firmwareVersion="4.1.4-c">
          <instruments>
            <sound presetName="LEAD" presetSlot="0" presetSubSlot="-1">
              <osc1 type="saw" />
              <osc2 type="saw" />
              <defaultParams />
            </sound>
          </instruments>
          <sessionClips>
            <instrumentClip length="96" instrumentPresetName="LEAD" instrumentPresetSlot="0" instrumentPresetSubSlot="-1">
              <arpeggiator mode="arp" numOctaves="2" syncLevel="7" syncType="0" />
              <soundParams
                arpeggiatorGate="0x20000000"
                noteProbability="0x60000000" />
              <noteRows />
            </instrumentClip>
            <instrumentClip length="96" instrumentPresetName="LEAD" instrumentPresetSlot="0" instrumentPresetSubSlot="-1">
              <soundParams
                sequenceLength="%s"
                noteProbability="0x80000000"
                reverseProbability="0x7FFFFFFF" />
              <noteRows />
            </instrumentClip>
          </sessionClips>
        </song>
        """
            .formatted(seqLen8Hex);

    Path inFile = tempDir.resolve("multi_clip_arp.xml");
    Files.writeString(inFile, xml, StandardCharsets.UTF_8);

    ProjectModel project = FirmwareFactory.createSong(SongXmlParser.parseSong(inFile.toFile()));
    SynthTrackModel synth = (SynthTrackModel) project.getTracks().get(0);
    assertEquals(2, synth.getClips().size());

    // Clip 0 is active initially; its rawKnobs must NOT be overwritten by Clip 1.
    assertEquals(0x20000000, synth.getRawKnobs().getRawParamKnobs().get(Param.UNPATCHED_ARP_GATE));
    assertEquals(
        0x60000000, synth.getRawKnobs().getRawParamKnobs().get(Param.UNPATCHED_NOTE_PROBABILITY));

    FirmwareSound liveSound = (FirmwareSound) synth.getClips().get(0).getSound();
    StereoSample[] buf = new StereoSample[16];
    for (int i = 0; i < buf.length; i++) buf[i] = new StereoSample();
    liveSound.renderInternal(buf, buf.length, null);
    assertEquals(0x20000000, liveSound.arpSettings.gate);
    assertEquals(0x60000000 + Integer.MIN_VALUE, liveSound.arpSettings.noteProbability);

    // Switch to Clip 1 (which had NO <arpeggiator> tag): sequenceLength=8, noteProbability=0%,
    // reverseProbability=100% must all apply, and gate resets to 0 (50% duty cycle).
    synth.setActiveClipIndex(1);
    liveSound.renderInternal(buf, buf.length, null);
    assertEquals(0, liveSound.arpSettings.gate);
    assertEquals(
        8, Functions.computeCurrentValueForUnsignedMenuItem(liveSound.arpSettings.sequenceLength));
    assertEquals(0, liveSound.arpSettings.noteProbability);
    assertEquals(
        50,
        Functions.computeCurrentValueForUnsignedMenuItem(liveSound.arpSettings.reverseProbability));

    // Save with Clip 0 active again and verify Clip 1's sequenceLength=8 and noteProbability=0%
    // are serialized on the inactive clip without colliding with ArpModel.defaultConfig().
    synth.setActiveClipIndex(0);
    Path outFile = tempDir.resolve("multi_clip_arp_out.xml");
    ProjectSerializer.save(project, outFile.toFile());
    String saved = Files.readString(outFile, StandardCharsets.UTF_8);
    assertTrue(saved.contains("sequenceLength=\"" + seqLen8Hex + "\""));
    assertTrue(saved.contains("noteProbability=\"0x80000000\""));
  }
}
