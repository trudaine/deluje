package org.deluge.modulation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareKit;
import org.deluge.engine.FirmwareSound;
import org.deluge.firmware2.FilterSet.FilterMode;
import org.deluge.firmware2.Functions;
import org.deluge.firmware2.GlobalSidechainBus;
import org.deluge.firmware2.Lfo;
import org.deluge.firmware2.Oscillator.OscType;
import org.deluge.firmware2.Param;
import org.deluge.firmware2.PatchSource;
import org.deluge.firmware2.Patcher;
import org.deluge.firmware2.Sound;
import org.deluge.firmware2.Sound.PatchCableAcceptance;
import org.deluge.firmware2.StereoSample;
import org.deluge.model.ClipModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.modulation.params.ParamManager;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies line-by-line C++ parity for:
 *
 * <ul>
 *   <li>{@code Sound::maySourcePatchToParam} ({@code sound.cpp:1340-1507}) and {@code
 *       Sound::isSourceActiveEver*} / {@code renderingOscillatorSyncEver} ({@code
 *       sound.cpp:2107-2144}, {@code auto_param.cpp:2152-2158})
 *   <li>Two-pass {@code PatchCableSet::setupPatching} &amp; {@code patchCableIsUsable} ({@code
 *       patch_cable_set.cpp:157-289, 486-516}), including orphan/inactive range-cable pruning and
 *       dynamic re-activation when sound mode/filter/knobs change
 *   <li>Live sequencer automation + {@code paramLPF} smoothing of FM modulator volumes ({@code
 *       LOCAL_MODULATOR_0_VOLUME}, {@code LOCAL_MODULATOR_1_VOLUME}) when {@code
 *       fmModulatorAmountBase} holds a non-neutral base value
 * </ul>
 */
class PatchCableUsabilityAndFmModAutomationTest {

  @BeforeEach
  void resetEngineDeterminism() {
    GlobalSidechainBus.reset();
    Functions.resetNoiseSeed();
  }

  @Test
  void maySourcePatchToParamEnforcesDrumNoteLocalGlobalVolumePitchAndPostFxRules() {
    Sound sound = new Sound();
    Sound.initParams(sound.patchedParamValues);

    // 1. Melodic sound allows NOTE; Drum disallows NOTE everywhere (including p == 255 query).
    assertEquals(
        PatchCableAcceptance.ALLOWED,
        sound.maySourcePatchToParam(PatchSource.NOTE, Param.LOCAL_PITCH_ADJUST));
    assertEquals(PatchCableAcceptance.ALLOWED, sound.maySourcePatchToParam(PatchSource.NOTE, 255));
    sound.isDrum = true;
    assertEquals(
        PatchCableAcceptance.DISALLOWED,
        sound.maySourcePatchToParam(PatchSource.NOTE, Param.LOCAL_PITCH_ADJUST));
    assertEquals(
        PatchCableAcceptance.DISALLOWED, sound.maySourcePatchToParam(PatchSource.NOTE, 255));
    sound.isDrum = false;

    // 2. Local sources (>= kFirstLocalSource) disallowed on global params (>= FIRST_GLOBAL).
    assertEquals(
        PatchCableAcceptance.DISALLOWED,
        sound.maySourcePatchToParam(PatchSource.ENVELOPE_0, Param.GLOBAL_REVERB_AMOUNT));
    assertEquals(
        PatchCableAcceptance.DISALLOWED,
        sound.maySourcePatchToParam(PatchSource.VELOCITY, Param.GLOBAL_DELAY_RATE));
    assertEquals(
        PatchCableAcceptance.ALLOWED,
        sound.maySourcePatchToParam(PatchSource.LFO_GLOBAL_1, Param.GLOBAL_REVERB_AMOUNT));
    assertEquals(
        PatchCableAcceptance.ALLOWED,
        sound.maySourcePatchToParam(PatchSource.SIDECHAIN, Param.GLOBAL_DELAY_FEEDBACK));

    // 3. Invalid param IDs (GLOBAL_NONE == 55, PLACEHOLDER_RANGE == 89, unpatched >= 90).
    assertEquals(
        PatchCableAcceptance.DISALLOWED,
        sound.maySourcePatchToParam(PatchSource.LFO_GLOBAL_1, Param.GLOBAL_NONE));
    assertEquals(
        PatchCableAcceptance.DISALLOWED,
        sound.maySourcePatchToParam(PatchSource.LFO_GLOBAL_1, Param.PLACEHOLDER_RANGE));
    assertEquals(
        PatchCableAcceptance.DISALLOWED,
        sound.maySourcePatchToParam(PatchSource.LFO_GLOBAL_1, Param.UNPATCHED_BASS));

    // 4. LOCAL_VOLUME disallows ENVELOPE_0..3 and SIDECHAIN, allows LFOs/VELOCITY/AFTERTOUCH/Y.
    for (PatchSource env :
        new PatchSource[] {
          PatchSource.ENVELOPE_0,
          PatchSource.ENVELOPE_1,
          PatchSource.ENVELOPE_2,
          PatchSource.ENVELOPE_3,
          PatchSource.SIDECHAIN
        }) {
      assertEquals(
          PatchCableAcceptance.DISALLOWED, sound.maySourcePatchToParam(env, Param.LOCAL_VOLUME));
    }
    assertEquals(
        PatchCableAcceptance.ALLOWED,
        sound.maySourcePatchToParam(PatchSource.VELOCITY, Param.LOCAL_VOLUME));
    assertEquals(
        PatchCableAcceptance.ALLOWED,
        sound.maySourcePatchToParam(PatchSource.LFO_LOCAL_1, Param.LOCAL_VOLUME));
    assertEquals(
        PatchCableAcceptance.ALLOWED,
        sound.maySourcePatchToParam(PatchSource.LFO_GLOBAL_1, Param.LOCAL_VOLUME));

    // 5. GLOBAL_VOLUME_POST_FX disallows all sources; GLOBAL_VOLUME_POST_REVERB_SEND allows only
    // SIDECHAIN; LOCAL_PITCH_ADJUST disallows X only.
    assertEquals(
        PatchCableAcceptance.DISALLOWED,
        sound.maySourcePatchToParam(PatchSource.SIDECHAIN, Param.GLOBAL_VOLUME_POST_FX));
    assertEquals(
        PatchCableAcceptance.DISALLOWED,
        sound.maySourcePatchToParam(PatchSource.LFO_GLOBAL_1, Param.GLOBAL_VOLUME_POST_FX));
    assertEquals(
        PatchCableAcceptance.ALLOWED,
        sound.maySourcePatchToParam(PatchSource.SIDECHAIN, Param.GLOBAL_VOLUME_POST_REVERB_SEND));
    assertEquals(
        PatchCableAcceptance.DISALLOWED,
        sound.maySourcePatchToParam(
            PatchSource.LFO_GLOBAL_1, Param.GLOBAL_VOLUME_POST_REVERB_SEND));
    assertEquals(
        PatchCableAcceptance.DISALLOWED,
        sound.maySourcePatchToParam(PatchSource.X, Param.LOCAL_PITCH_ADJUST));
    assertEquals(
        PatchCableAcceptance.ALLOWED,
        sound.maySourcePatchToParam(PatchSource.Y, Param.LOCAL_PITCH_ADJUST));
  }

  @Test
  void maySourcePatchToParamEnforcesModeSourceFilterNoiseFmDx7AndGlobalLfoSyncRules() {
    Sound sound = new Sound();
    Sound.initParams(sound.patchedParamValues);
    ParamManager pm = new ParamManager();

    // Filter rules (sound.cpp:1425-1439)
    sound.lpfMode = FilterMode.OFF;
    sound.hpfMode = FilterMode.OFF;
    assertEquals(
        PatchCableAcceptance.DISALLOWED,
        sound.maySourcePatchToParam(PatchSource.ENVELOPE_1.ordinal(), Param.LOCAL_LPF_FREQ, pm));
    assertEquals(
        PatchCableAcceptance.DISALLOWED,
        sound.maySourcePatchToParam(PatchSource.ENVELOPE_1.ordinal(), Param.LOCAL_LPF_MORPH, pm));
    assertEquals(
        PatchCableAcceptance.DISALLOWED,
        sound.maySourcePatchToParam(
            PatchSource.ENVELOPE_1.ordinal(), Param.LOCAL_LPF_RESONANCE, pm));
    assertEquals(
        PatchCableAcceptance.DISALLOWED,
        sound.maySourcePatchToParam(PatchSource.ENVELOPE_1.ordinal(), Param.LOCAL_HPF_FREQ, pm));
    sound.lpfMode = FilterMode.TRANSISTOR_24DB;
    sound.hpfMode = FilterMode.HPLADDER;
    assertEquals(
        PatchCableAcceptance.ALLOWED,
        sound.maySourcePatchToParam(PatchSource.ENVELOPE_1.ordinal(), Param.LOCAL_LPF_FREQ, pm));
    assertEquals(
        PatchCableAcceptance.ALLOWED,
        sound.maySourcePatchToParam(PatchSource.ENVELOPE_1.ordinal(), Param.LOCAL_HPF_FREQ, pm));

    // Osc A / Osc B activity, hard sync master pitch rule, and RingMod volume disallowance
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MIN_VALUE;
    sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    assertEquals(
        PatchCableAcceptance.EDITABLE,
        sound.maySourcePatchToParam(
            PatchSource.LFO_LOCAL_1.ordinal(), Param.LOCAL_OSC_A_VOLUME, pm));
    assertEquals(
        PatchCableAcceptance.EDITABLE,
        sound.maySourcePatchToParam(
            PatchSource.LFO_LOCAL_1.ordinal(), Param.LOCAL_OSC_A_PITCH_ADJUST, pm));

    // Hard sync: muted Osc A is still active sync master when Osc B is audible
    // (sound.cpp:2107-2144).
    sound.oscillatorSync = true;
    sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = 0;
    assertEquals(
        PatchCableAcceptance.ALLOWED,
        sound.maySourcePatchToParam(
            PatchSource.LFO_LOCAL_1.ordinal(), Param.LOCAL_OSC_A_PITCH_ADJUST, pm));
    sound.oscillatorSync = false;

    // Automation at Integer.MIN_VALUE still counts as containsSomething == true
    // (auto_param.cpp:2152).
    sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    pm.recordParamValue(Param.LOCAL_OSC_B_VOLUME, Integer.MIN_VALUE, 0);
    assertEquals(
        PatchCableAcceptance.ALLOWED,
        sound.maySourcePatchToParam(
            PatchSource.LFO_LOCAL_1.ordinal(), Param.LOCAL_OSC_B_PITCH_ADJUST, pm));

    // RingMod disallows osc volume cables, allows osc pitch cables & phase width
    sound.synthMode = 2;
    assertEquals(
        PatchCableAcceptance.DISALLOWED,
        sound.maySourcePatchToParam(
            PatchSource.LFO_LOCAL_1.ordinal(), Param.LOCAL_OSC_A_VOLUME, pm));
    assertEquals(
        PatchCableAcceptance.DISALLOWED,
        sound.maySourcePatchToParam(
            PatchSource.LFO_LOCAL_1.ordinal(), Param.LOCAL_OSC_B_VOLUME, pm));
    assertEquals(
        PatchCableAcceptance.ALLOWED,
        sound.maySourcePatchToParam(
            PatchSource.LFO_LOCAL_1.ordinal(), Param.LOCAL_OSC_A_PITCH_ADJUST, pm));

    // Subtractive noise EDITABLE vs ALLOWED; 2-op FM disallows noise & pulse width
    sound.synthMode = 0;
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    sound.patchedParamValues[Param.LOCAL_NOISE_VOLUME] = Integer.MIN_VALUE;
    assertEquals(
        PatchCableAcceptance.EDITABLE,
        sound.maySourcePatchToParam(
            PatchSource.ENVELOPE_1.ordinal(), Param.LOCAL_NOISE_VOLUME, pm));
    sound.patchedParamValues[Param.LOCAL_NOISE_VOLUME] = 0;
    assertEquals(
        PatchCableAcceptance.ALLOWED,
        sound.maySourcePatchToParam(
            PatchSource.ENVELOPE_1.ordinal(), Param.LOCAL_NOISE_VOLUME, pm));

    // 2-op FM rules (carrier feedback, modulator volume/pitch/feedback)
    sound.synthMode = 1;
    assertEquals(
        PatchCableAcceptance.DISALLOWED,
        sound.maySourcePatchToParam(
            PatchSource.LFO_LOCAL_1.ordinal(), Param.LOCAL_OSC_A_PHASE_WIDTH, pm));
    assertEquals(
        PatchCableAcceptance.DISALLOWED,
        sound.maySourcePatchToParam(
            PatchSource.ENVELOPE_1.ordinal(), Param.LOCAL_NOISE_VOLUME, pm));
    assertEquals(
        PatchCableAcceptance.EDITABLE,
        sound.maySourcePatchToParam(
            PatchSource.ENVELOPE_1.ordinal(), Param.LOCAL_MODULATOR_0_VOLUME, pm));
    assertEquals(
        PatchCableAcceptance.EDITABLE,
        sound.maySourcePatchToParam(
            PatchSource.ENVELOPE_1.ordinal(), Param.LOCAL_MODULATOR_0_FEEDBACK, pm));
    assertEquals(
        PatchCableAcceptance.EDITABLE,
        sound.maySourcePatchToParam(
            PatchSource.ENVELOPE_1.ordinal(), Param.LOCAL_CARRIER_0_FEEDBACK, pm));

    sound.patchedParamValues[Param.LOCAL_MODULATOR_0_VOLUME] = 0;
    assertEquals(
        PatchCableAcceptance.ALLOWED,
        sound.maySourcePatchToParam(
            PatchSource.ENVELOPE_1.ordinal(), Param.LOCAL_MODULATOR_0_VOLUME, pm));
    assertEquals(
        PatchCableAcceptance.ALLOWED,
        sound.maySourcePatchToParam(
            PatchSource.ENVELOPE_1.ordinal(), Param.LOCAL_MODULATOR_0_PITCH_ADJUST, pm));
    assertEquals(
        PatchCableAcceptance.EDITABLE,
        sound.maySourcePatchToParam(
            PatchSource.ENVELOPE_1.ordinal(), Param.LOCAL_MODULATOR_0_FEEDBACK, pm));
    sound.patchedParamValues[Param.LOCAL_MODULATOR_0_FEEDBACK] = 0;
    assertEquals(
        PatchCableAcceptance.ALLOWED,
        sound.maySourcePatchToParam(
            PatchSource.ENVELOPE_1.ordinal(), Param.LOCAL_MODULATOR_0_FEEDBACK, pm));
    sound.patchedParamValues[Param.LOCAL_CARRIER_0_FEEDBACK] = 0;
    assertEquals(
        PatchCableAcceptance.ALLOWED,
        sound.maySourcePatchToParam(
            PatchSource.ENVELOPE_1.ordinal(), Param.LOCAL_CARRIER_0_FEEDBACK, pm));

    // 6-op DX7 (oscTypes[0] == DX7 even when UI sets synthMode == 1): allows phaseWidth (DX7 EG
    // mod)
    // and disallows 2-op FM modulator/carrier params!
    sound.oscTypes[0] = OscType.DX7;
    assertEquals(
        PatchCableAcceptance.ALLOWED,
        sound.maySourcePatchToParam(
            PatchSource.LFO_LOCAL_1.ordinal(), Param.LOCAL_OSC_A_PHASE_WIDTH, pm));
    assertEquals(
        PatchCableAcceptance.DISALLOWED,
        sound.maySourcePatchToParam(
            PatchSource.ENVELOPE_1.ordinal(), Param.LOCAL_MODULATOR_0_VOLUME, pm));
    sound.oscTypes[0] = OscType.SINE;
    sound.synthMode = 0;

    // Global LFO rate cables disallowed when tempo-synced (LFO1 -> lfoConfig[0], LFO3 ->
    // lfoConfig[2])
    assertEquals(
        PatchCableAcceptance.ALLOWED,
        sound.maySourcePatchToParam(
            PatchSource.LFO_GLOBAL_2.ordinal(), Param.GLOBAL_LFO_FREQ_1, pm));
    assertEquals(
        PatchCableAcceptance.ALLOWED,
        sound.maySourcePatchToParam(
            PatchSource.LFO_GLOBAL_1.ordinal(), Param.GLOBAL_LFO_FREQ_2, pm));
    sound.lfoConfig[0].syncLevel = Lfo.SyncLevel.L_8TH;
    sound.lfoConfig[2].syncLevel = Lfo.SyncLevel.L_16TH;
    assertEquals(
        PatchCableAcceptance.DISALLOWED,
        sound.maySourcePatchToParam(
            PatchSource.LFO_GLOBAL_2.ordinal(), Param.GLOBAL_LFO_FREQ_1, pm));
    assertEquals(
        PatchCableAcceptance.DISALLOWED,
        sound.maySourcePatchToParam(
            PatchSource.LFO_GLOBAL_1.ordinal(), Param.GLOBAL_LFO_FREQ_2, pm));
  }

  @Test
  void setupPatchingPrunesOrphanAndInactiveRangeCablesAndDynamicallyReenablesThem() {
    FirmwareSound fs = new FirmwareSound();
    Sound.initParams(fs.paramNeutralValues);
    fs.setLpfMode(org.deluge.model.FilterMode.OFF);
    fs.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;

    // 1. Direct + depth-range cable on LPF while LPF mode is OFF -> omitted from fw2Sound, kept in
    // paramManager, and automatically re-enabled when LPF turns on.
    var lpfCable = new org.deluge.modulation.patch.PatchCable();
    lpfCable.from = org.deluge.modulation.patch.PatchSource.LFO_LOCAL_1;
    lpfCable.amount = 0x30000000;
    fs.paramManager.getPatchCableSet().addCable(Param.LOCAL_LPF_FREQ, lpfCable);

    var lpfDepthCable = new org.deluge.modulation.patch.PatchCable();
    lpfDepthCable.from = org.deluge.modulation.patch.PatchSource.VELOCITY;
    lpfDepthCable.amount = 0x40000000;
    fs.paramManager
        .getPatchCableSet()
        .addRangeCable(
            Param.LOCAL_LPF_FREQ,
            org.deluge.modulation.patch.PatchSource.LFO_LOCAL_1,
            lpfDepthCable);

    // 2. Orphan range cable on LOCAL_PAN whose target cable (ENVELOPE_1 -> LOCAL_PAN) does NOT
    // exist -> must be pruned in Pass 2 even when LOCAL_PAN is ALLOWED!
    var orphanRangeCable = new org.deluge.modulation.patch.PatchCable();
    orphanRangeCable.from = org.deluge.modulation.patch.PatchSource.VELOCITY;
    orphanRangeCable.amount = 0x30000000;
    fs.paramManager
        .getPatchCableSet()
        .addRangeCable(
            Param.LOCAL_PAN, org.deluge.modulation.patch.PatchSource.ENVELOPE_1, orphanRangeCable);

    // 3. Bare PLACEHOLDER_RANGE (89) cable from legacy XML -> rejected safely with zero OOB crash.
    var placeholderCable = new org.deluge.modulation.patch.PatchCable();
    placeholderCable.from = org.deluge.modulation.patch.PatchSource.LFO_GLOBAL_1;
    placeholderCable.amount = 0x20000000;
    fs.paramManager.getPatchCableSet().addCable(Param.PLACEHOLDER_RANGE, placeholderCable);

    // 4. EDITABLE Osc B pitch cable while Osc B volume == INT_MIN -> omitted so inactive Osc B does
    // not get treated as patched/active, then automatically enabled when Osc B volume rises.
    var oscBPitchCable = new org.deluge.modulation.patch.PatchCable();
    oscBPitchCable.from = org.deluge.modulation.patch.PatchSource.LFO_LOCAL_1;
    oscBPitchCable.amount = 0x20000000;
    fs.paramManager.getPatchCableSet().addCable(Param.LOCAL_OSC_B_PITCH_ADJUST, oscBPitchCable);

    fs.syncParamsToFw2();
    assertTrue(
        fs.fw2Sound.patchCableSet.destinations.isEmpty(),
        "All 5 unusable/orphan cables must be omitted from active fw2Sound destinations");
    assertEquals(
        5,
        fs.paramManager.getPatchCableSet().destinations.size(),
        "Timeline ParamManager must preserve stored EDITABLE/inactive cables intact");

    // Render a real note to prove bare PLACEHOLDER_RANGE (89) never crashes Patcher.
    fs.triggerNote(60, 100);
    StereoSample[] buf = new StereoSample[64];
    for (int i = 0; i < buf.length; i++) buf[i] = new StereoSample();
    assertDoesNotThrow(() -> fs.renderOutput(buf, 64, null));

    // Turn LPF ON and turn Osc B volume up -> LPF cable + its range cable + Osc B pitch cable all
    // become usable automatically, while the orphan LOCAL_PAN range cable and PLACEHOLDER_RANGE
    // remain pruned!
    fs.setLpfMode(org.deluge.model.FilterMode.LADDER_24);
    fs.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = 0;
    fs.syncParamsToFw2();
    assertEquals(
        3,
        fs.fw2Sound.patchCableSet.destinations.size(),
        "LPF direct, LPF range, and Osc B pitch cables must re-activate while orphan/placeholder stay pruned");
  }

  @Test
  void kitDrumRejectsNotePatchCablesAcrossClipAttachmentAndSync() throws Exception {
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song firmwareVersion="4.1.0">
          <sessionClips>
            <instrumentClip presetName="KIT1" instrumentPresetName="KIT1" length="96">
              <noteRows>
                <noteRow drumIndex="0" noteDataWithLift="0x0000000000000018404014"/>
              </noteRows>
            </instrumentClip>
          </sessionClips>
          <instruments>
            <kit presetName="KIT1">
              <soundSources>
                <sound name="KICK" lpfMode="24dB">
                  <osc1 type="sine"/>
                  <defaultParams lpfFrequency="0x20000000">
                    <patchCables>
                      <patchCable source="note" destination="lpfFrequency" amount="0x30000000"/>
                      <patchCable source="velocity" destination="lpfFrequency" amount="0x25000000"/>
                    </patchCables>
                  </defaultParams>
                </sound>
              </soundSources>
            </kit>
          </instruments>
        </song>
        """;

    ProjectModel project =
        DelugeXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "drum_note.xml");
    ProjectModel liveSong = FirmwareFactory.createSong(project);
    KitTrackModel kitTrack = (KitTrackModel) liveSong.getTracks().get(0);
    FirmwareKit fwKit = (FirmwareKit) kitTrack.getActiveClip().getSound();
    FirmwareSound drum = fwKit.drumSounds.get(0);
    drum.syncParamsToFw2();

    assertTrue(drum.isDrum && drum.fw2Sound.isDrum, "Kit SoundDrum must propagate isDrum=true");
    assertEquals(1, drum.fw2Sound.patchCableSet.destinations.size());
    Patcher.Destination lpfDest = drum.fw2Sound.patchCableSet.destinations.get(0);
    assertEquals(Param.LOCAL_LPF_FREQ, lpfDest.paramId);
    assertEquals(
        1, lpfDest.cables.size(), "NOTE cable on drum must be pruned while VELOCITY remains");
    assertEquals(PatchSource.VELOCITY.ordinal(), lpfDest.cables.get(0).source);
  }

  @Test
  void rangeModulationCablesDrivePerformPatchingAtRenderTime() {
    // Canonical Deluge range-modulation patches where the range modulator itself is disallowed as a
    // direct destination source:
    //   1) ENVELOPE_1 -> (LFO_LOCAL_1 -> LOCAL_VOLUME)
    //   2) LFO_GLOBAL_1 -> (SIDECHAIN -> GLOBAL_VOLUME_POST_REVERB_SEND)
    //   3) X -> (LFO_LOCAL_1 -> LOCAL_PITCH_ADJUST)
    Sound sound = new Sound();
    sound.oscTypes[0] = OscType.SAW;
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    sound.patchedParamValues[Param.LOCAL_VOLUME] = 0x20000000;
    sound.patchedParamValues[Param.GLOBAL_VOLUME_POST_REVERB_SEND] = 0x20000000;

    Patcher.PatchCable lfoToVol = new Patcher.PatchCable();
    lfoToVol.source = PatchSource.LFO_LOCAL_1.ordinal();
    lfoToVol.amount = 0x30000000;
    sound.patchCableSet.addCable(Param.LOCAL_VOLUME, lfoToVol);

    Patcher.PatchCable envToTremDepth = new Patcher.PatchCable();
    envToTremDepth.source = PatchSource.ENVELOPE_1.ordinal();
    envToTremDepth.amount = 0x3FFFFFFF;
    sound.patchCableSet.addRangeCable(
        Param.LOCAL_VOLUME, PatchSource.LFO_LOCAL_1.ordinal(), envToTremDepth);

    Patcher.PatchCable scToPostRevVol = new Patcher.PatchCable();
    scToPostRevVol.source = PatchSource.SIDECHAIN.ordinal();
    scToPostRevVol.amount = 0x30000000;
    sound.patchCableSet.addCable(Param.GLOBAL_VOLUME_POST_REVERB_SEND, scToPostRevVol);

    Patcher.PatchCable globalLfoToDuckDepth = new Patcher.PatchCable();
    globalLfoToDuckDepth.source = PatchSource.LFO_GLOBAL_1.ordinal();
    globalLfoToDuckDepth.amount = 0x3FFFFFFF;
    sound.patchCableSet.addRangeCable(
        Param.GLOBAL_VOLUME_POST_REVERB_SEND,
        PatchSource.SIDECHAIN.ordinal(),
        globalLfoToDuckDepth);

    Patcher.PatchCable lfoToPitch = new Patcher.PatchCable();
    lfoToPitch.source = PatchSource.LFO_LOCAL_1.ordinal();
    lfoToPitch.amount = 0x20000000;
    sound.patchCableSet.addCable(Param.LOCAL_PITCH_ADJUST, lfoToPitch);

    Patcher.PatchCable xToVibratoDepth = new Patcher.PatchCable();
    xToVibratoDepth.source = PatchSource.X.ordinal();
    xToVibratoDepth.amount = 0x3FFFFFFF;
    sound.patchCableSet.addRangeCable(
        Param.LOCAL_PITCH_ADJUST, PatchSource.LFO_LOCAL_1.ordinal(), xToVibratoDepth);

    sound.patchCableSet.setupPatching(sound);

    int[] sourceValues = new int[PatchSource.kNumPatchSources];
    int[] finalZero = new int[Param.kNumParams];
    int[] finalFull = new int[Param.kNumParams];

    sourceValues[PatchSource.LFO_LOCAL_1.ordinal()] = 0x40000000;
    sourceValues[PatchSource.SIDECHAIN.ordinal()] = -1073741824;
    sourceValues[PatchSource.ENVELOPE_1.ordinal()] = 0;
    sourceValues[PatchSource.X.ordinal()] = 0;
    sourceValues[PatchSource.LFO_GLOBAL_1.ordinal()] = 0;
    Patcher.performPatching(sound, sourceValues, sound.patchCableSet, finalZero);

    sourceValues[PatchSource.ENVELOPE_1.ordinal()] = Integer.MAX_VALUE;
    sourceValues[PatchSource.X.ordinal()] = Integer.MAX_VALUE;
    sourceValues[PatchSource.LFO_GLOBAL_1.ordinal()] = Integer.MAX_VALUE;
    Patcher.performPatching(sound, sourceValues, sound.patchCableSet, finalFull);

    assertTrue(
        finalZero[Param.LOCAL_VOLUME] != finalFull[Param.LOCAL_VOLUME],
        "ENVELOPE_1 must modulate LFO_LOCAL_1 -> LOCAL_VOLUME range depth at render time");
    assertTrue(
        finalZero[Param.LOCAL_PITCH_ADJUST] != finalFull[Param.LOCAL_PITCH_ADJUST],
        "X must modulate LFO_LOCAL_1 -> LOCAL_PITCH_ADJUST range depth at render time");
    assertTrue(
        finalZero[Param.GLOBAL_VOLUME_POST_REVERB_SEND]
            != finalFull[Param.GLOBAL_VOLUME_POST_REVERB_SEND],
        "LFO_GLOBAL_1 must modulate SIDECHAIN -> GLOBAL_VOLUME_POST_REVERB_SEND range depth");
  }

  @Test
  void kitDrumFirmwareFilterModesMorphsAndRowOverridesPreserveParity() throws Exception {
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song firmwareVersion="4.1.0">
          <sessionClips>
            <instrumentClip presetName="KIT_FLT" instrumentPresetName="KIT_FLT" length="96">
              <noteRows>
                <noteRow drumIndex="0" noteDataWithLift="0x0000000000000018404014">
                  <soundParams
                      lpfFrequency="0xE0000000"
                      lpfResonance="0x30000000"
                      lpfMorph="0x20000000"
                      hpfFrequency="0xF0000000"
                      hpfResonance="0x10000000"
                      hpfMorph="0x28000000"/>
                </noteRow>
                <noteRow drumIndex="1" noteDataWithLift="0x0000000000000018404014"/>
              </noteRows>
            </instrumentClip>
          </sessionClips>
          <instruments>
            <kit presetName="KIT_FLT">
              <soundSources>
                <sound name="D0" hpfMode="SVF_Band" filterRoute="PARA">
                  <osc1 type="saw"/>
                  <defaultParams
                      lpfFrequency="0x10000000"
                      lpfMorph="0xD0000000"
                      hpfMorph="0xC0000000"/>
                </sound>
                <sound name="D1" hpfMode="SVF_Notch">
                  <osc1 type="square"/>
                  <defaultParams lpfMorph="0x18000000" hpfMorph="0x08000000"/>
                </sound>
              </soundSources>
            </kit>
          </instruments>
        </song>
        """;

    ProjectModel project =
        DelugeXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "kit_flt.xml");
    ProjectModel liveSong = FirmwareFactory.createSong(project);
    KitTrackModel kitTrack = (KitTrackModel) liveSong.getTracks().get(0);
    FirmwareKit fwKit = (FirmwareKit) kitTrack.getActiveClip().getSound();

    FirmwareSound d0 = fwKit.drumSounds.get(0);
    FirmwareSound d1 = fwKit.drumSounds.get(1);

    // Default unannotated <sound> LPF mode must be 24dB transistor ladder; mixed-case SVF_Band /
    // SVF_Notch and firmware "PARA" filterRoute must map cleanly.
    assertEquals(FilterMode.TRANSISTOR_24DB, d0.fw2Sound.lpfMode);
    assertEquals(FilterMode.SVF_BAND, d0.fw2Sound.hpfMode);
    assertEquals(2, d0.fw2Sound.filterRoute);

    assertEquals(FilterMode.TRANSISTOR_24DB, d1.fw2Sound.lpfMode);
    assertEquals(FilterMode.SVF_NOTCH, d1.fw2Sound.hpfMode);
    assertEquals(0x18000000, d1.paramNeutralValues[Param.LOCAL_LPF_MORPH], 8);
    assertEquals(0x08000000, d1.paramNeutralValues[Param.LOCAL_HPF_MORPH], 8);

    // Per-row <noteRow><soundParams> overrides on D0 must survive ClipModel.setSound without being
    // overwritten by D0's base <defaultParams>!
    assertEquals(0xE0000000, d0.paramNeutralValues[Param.LOCAL_LPF_FREQ], 8);
    assertEquals(0x30000000, d0.paramNeutralValues[Param.LOCAL_LPF_RESONANCE], 8);
    assertEquals(0x20000000, d0.paramNeutralValues[Param.LOCAL_LPF_MORPH], 8);
    assertEquals(0xF0000000, d0.paramNeutralValues[Param.LOCAL_HPF_FREQ], 8);
    assertEquals(0x10000000, d0.paramNeutralValues[Param.LOCAL_HPF_RESONANCE], 8);
    assertEquals(0x28000000, d0.paramNeutralValues[Param.LOCAL_HPF_MORPH], 8);
  }

  @Test
  void fmModulatorVolumeAutomationDrivesLiveAudioAndParamLpfWithoutBaseOverwrite()
      throws Exception {
    // Base <defaultParams> sets modulator1Amount="0x20000000" (non-zero fmModulatorAmountBase[0]!)
    // and modulator2Amount="0x10000000" (non-zero fmModulatorAmountBase[1]!), while clip-level
    // <soundParams> automates modulator1Amount from 0x80000000 (pure sine at pos 0) up to
    // 0x60000000 (deep FM at pos 48) and modulator2Amount to 0x40000000.
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song firmwareVersion="4.1.0">
          <sessionClips>
            <instrumentClip presetName="FM1" instrumentPresetName="FM1" length="96">
              <soundParams
                  modulator1Amount="0x2000000080000000000000006000000000000030"
                  modulator2Amount="0x1000000080000000000000004000000000000030"/>
              <noteRows>
                <noteRow y="60" noteDataWithLift="0x0000000000000060404014"/>
              </noteRows>
            </instrumentClip>
          </sessionClips>
          <instruments>
            <sound presetName="FM1" mode="fm">
              <osc1 type="sine"/>
              <osc2 type="sine"/>
              <modulator1 transpose="12" retrigPhase="0"/>
              <modulator2 transpose="19" retrigPhase="0"/>
              <defaultParams
                  oscAVolume="0x7FFFFFFF"
                  oscBVolume="0x80000000"
                  volume="0x50000000"
                  modulator1Amount="0x20000000"
                  modulator2Amount="0x10000000"/>
            </sound>
          </instruments>
        </song>
        """;

    ProjectModel project =
        DelugeXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "fm_mod_auto.xml");
    ProjectModel liveSong = FirmwareFactory.createSong(project);
    SynthTrackModel synth = (SynthTrackModel) liveSong.getTracks().get(0);
    ClipModel clip = synth.getActiveClip();
    FirmwareSound fs = (FirmwareSound) clip.getSound();

    // Confirm non-neutral fmModulatorAmountBase was populated from <defaultParams>.
    assertTrue(fs.fmModulatorAmountBase[0] != Integer.MIN_VALUE);
    assertTrue(fs.fmModulatorAmountBase[1] != Integer.MIN_VALUE);

    // 1. At pos 0, modulator1Amount is automated to 0x80000000 (off): must NOT be overwritten by
    // fmModulatorAmountBase[0]!
    fs.paramManager.processCurrentPos(0, 0, 96, false, false, true);
    fs.syncParamsToFw2();
    assertEquals(
        Integer.MIN_VALUE,
        fs.fw2Sound.patchedParamValues[Param.LOCAL_MODULATOR_0_VOLUME],
        "Automated modulator1Amount at pos 0 must override fmModulatorAmountBase[0]");

    fs.triggerNote(60, 110);
    float[] unmodulatedCarrier = renderBlocks(fs, 4096);
    double pureBrightness = spectralBrightness(unmodulatedCarrier, 1024, 4096);

    // 2. Step timeline to pos 48 -> modulator1Amount jumps to 0x60000000 while voice is active:
    // notifyValueChangeViaLPF must engage paramLPF smoothing rather than clicking instantaneously!
    fs.paramManager.processCurrentPos(48, 48, 96, false, false, true);
    fs.syncParamsToFw2();
    assertEquals(
        0x60000000,
        fs.fw2Sound.patchedParamValues[Param.LOCAL_MODULATOR_0_VOLUME],
        "Automated modulator1Amount at pos 48 must reach fw2Sound.patchedParamValues");
    assertEquals(
        0x40000000,
        fs.fw2Sound.patchedParamValues[Param.LOCAL_MODULATOR_1_VOLUME],
        "Automated modulator2Amount at pos 48 must reach fw2Sound.patchedParamValues");
    assertEquals(
        Param.LOCAL_MODULATOR_1_VOLUME,
        fs.fw2Sound.paramLPF.p,
        "Automated FM modulator jump with active voice must engage paramLPF click-free smoothing");

    assertTrue(
        pureBrightness < 0.045,
        "At pos 0 automated modulator1Amount=0x80000000 must render pure unmodulated sine (pure="
            + pureBrightness
            + ")");

    float[] modulatedFm = renderBlocks(fs, 8192);
    double fmBrightness = spectralBrightness(modulatedFm, 4096, 8192);
    assertTrue(
        fmBrightness > pureBrightness * 1.85,
        "Live FM modulator automation at pos 48 must dramatically brighten carrier harmonics (pure="
            + pureBrightness
            + ", fm="
            + fmBrightness
            + ")");
  }

  @Test
  void multiClipAndArrangementKitPreservesActiveClipDrumOverridesAndSparseDrumIndexMapping()
      throws Exception {
    // V2.0+ Deluge song format: <kit><soundSources><sound> omits <defaultParams>; active session
    // clip0 has sparse/reordered <noteRow drumIndex="1" bendRange="9"> with <soundParams> filter
    // overrides (including decimal Hz lpfFrequency="1000.0"), while inactive session clip1 and an
    // <arrangementOnlyClips> clip have no filter overrides. Neither clip1 nor the arrangement clip
    // may overwrite clip0's active drum filter knobs or per-drum bend ranges!
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song firmwareVersion="4.1.0">
          <sessionClips>
            <instrumentClip presetName="KIT1" instrumentPresetName="KIT1"
                instrumentPresetFolder="KITS" length="96">
              <noteRows>
                <noteRow drumIndex="1" bendRange="9" noteDataWithLift="0x0000000000000018404014">
                  <soundParams
                      lpfFrequency="1000.0"
                      lpfResonance="0x30000000"
                      lpfMorph="0x20000000"
                      hpfFrequency="0xF0000000"
                      hpfResonance="0x10000000"
                      hpfMorph="0x28000000"/>
                </noteRow>
              </noteRows>
            </instrumentClip>
            <instrumentClip presetName="KIT1" instrumentPresetName="KIT1"
                instrumentPresetFolder="KITS" length="96">
              <noteRows>
                <noteRow drumIndex="0" bendRange="3" noteDataWithLift="0x0000000000000018404014"/>
              </noteRows>
            </instrumentClip>
          </sessionClips>
          <arrangementOnlyClips>
            <instrumentClip presetName="KIT1" instrumentPresetName="KIT1"
                instrumentPresetFolder="KITS" length="96">
              <noteRows>
                <noteRow drumIndex="0" noteDataWithLift="0x0000000000000018404014"/>
              </noteRows>
            </instrumentClip>
          </arrangementOnlyClips>
          <instruments>
            <kit presetName="KIT1">
              <soundSources>
                <sound name="KICK" mode="subtractive" lpfMode="24dB" hpfMode="HPLadder">
                  <osc1 type="saw"/>
                </sound>
                <sound name="SNARE" mode="subtractive" lpfMode="24dB" hpfMode="HPLadder">
                  <osc1 type="saw"/>
                </sound>
              </soundSources>
            </kit>
          </instruments>
        </song>
        """;

    ProjectModel project =
        DelugeXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "multiclip_kit.xml");
    ProjectModel liveSong = FirmwareFactory.createSong(project);
    KitTrackModel kt = (KitTrackModel) liveSong.getTracks().get(0);
    assertEquals(3, kt.getClips().size(), "Expected 2 session clips + 1 arrangement clip");

    FirmwareKit kit = (FirmwareKit) kt.getActiveClip().getSound();
    FirmwareSound kick = kit.drumSounds.get(0);
    FirmwareSound snare = kit.drumSounds.get(1);

    // Drum 1 ("SNARE") received sparse drumIndex="1" overrides from active clip0 and must NOT be
    // wiped out by inactive session clip1 or arrangement clip2!
    int expected1kHzQ31 = org.deluge.xml.DelugeHexMapper.hzToQ31(1000.0f);
    assertEquals(expected1kHzQ31, snare.paramNeutralValues[Param.LOCAL_LPF_FREQ], 256);
    assertEquals(0x30000000, snare.paramNeutralValues[Param.LOCAL_LPF_RESONANCE], 8);
    assertEquals(0x20000000, snare.paramNeutralValues[Param.LOCAL_LPF_MORPH], 8);
    assertEquals(0xF0000000, snare.paramNeutralValues[Param.LOCAL_HPF_FREQ], 8);
    assertEquals(0x10000000, snare.paramNeutralValues[Param.LOCAL_HPF_RESONANCE], 8);
    assertEquals(0x28000000, snare.paramNeutralValues[Param.LOCAL_HPF_MORPH], 8);
    assertEquals(9, snare.fw2Sound.bendRangeFingerLevel);
    // Drum 0 ("KICK") must keep default finger-level drum bend range (2), NOT inactive clip1's 3!
    assertEquals(2, kick.fw2Sound.bendRangeFingerLevel);
  }

  @Test
  void patchCableAmountLiveMutationAndZeroAllocSteadyStateSync() {
    Sound sound = new Sound();
    sound.lpfMode = FilterMode.OFF;

    Patcher.PatchCable cable = new Patcher.PatchCable();
    cable.source = PatchSource.LFO_LOCAL_1.ordinal();
    cable.amount = 0x20000000;
    sound.patchCableSet.addCable(Param.LOCAL_LPF_FREQ, cable);

    // 1. Pruned while LPF is OFF; mutate cable.amount while pruned -> re-enabled with new amount!
    sound.patchCableSet.setupPatching(sound);
    assertTrue(sound.patchCableSet.destinations.isEmpty());
    cable.amount = 0x55000000;
    sound.lpfMode = FilterMode.TRANSISTOR_24DB;
    sound.patchCableSet.setupPatching(sound);
    assertEquals(1, sound.patchCableSet.destinations.size());
    assertSame(cable, sound.patchCableSet.destinations.get(0).cables.get(0));
    assertEquals(0x55000000, sound.patchCableSet.destinations.get(0).cables.get(0).amount);

    // 2. Mutate cable.amount AFTER setupPatching -> immediately visible AND preserved across future
    // setupPatching calls!
    cable.amount = -0x33000000;
    assertEquals(-0x33000000, sound.patchCableSet.destinations.get(0).cables.get(0).amount);
    sound.patchCableSet.setupPatching(sound);
    assertEquals(-0x33000000, sound.patchCableSet.destinations.get(0).cables.get(0).amount);

    // 3. FirmwareSound.syncParamsToFw2 reuses the exact List<Destination> snapshot across
    // steady-state blocks (zero heap churn) and publishes a fresh snapshot as soon as amount or
    // filter mode changes!
    FirmwareSound fs = new FirmwareSound();
    fs.setLpfMode(org.deluge.model.FilterMode.LADDER_24);
    org.deluge.modulation.patch.PatchCable mc = new org.deluge.modulation.patch.PatchCable();
    mc.from = org.deluge.modulation.patch.PatchSource.LFO_LOCAL_1;
    mc.amount = 0x40000000;
    fs.paramManager.getPatchCableSet().addCable(Param.LOCAL_LPF_FREQ, mc);

    fs.syncParamsToFw2();
    var firstSnapshot = fs.fw2Sound.patchCableSet.destinations;
    assertEquals(1, firstSnapshot.size());

    fs.syncParamsToFw2();
    assertSame(
        firstSnapshot,
        fs.fw2Sound.patchCableSet.destinations,
        "Unchanged patch cable set must reuse published snapshot with zero heap allocations");

    mc.amount = 0x20000000;
    fs.syncParamsToFw2();
    var updatedSnapshot = fs.fw2Sound.patchCableSet.destinations;
    assertNotSame(firstSnapshot, updatedSnapshot);
    assertEquals(0x20000000, updatedSnapshot.get(0).cables.get(0).amount);
  }

  private static float[] renderBlocks(FirmwareSound fs, int totalSamples) {
    float[] out = new float[totalSamples];
    StereoSample[] block = new StereoSample[128];
    for (int i = 0; i < block.length; i++) block[i] = new StereoSample();
    for (int off = 0; off < totalSamples; off += 128) {
      for (int i = 0; i < 128; i++) {
        block[i].l = 0;
        block[i].r = 0;
      }
      fs.renderOutput(block, 128, null);
      for (int i = 0; i < 128 && off + i < totalSamples; i++) {
        out[off + i] = block[i].l / 2147483648.0f;
      }
    }
    return out;
  }

  private static double spectralBrightness(float[] s, int from, int to) {
    double diff = 0.0;
    double energy = 0.0;
    for (int i = from + 1; i < to; i++) {
      double d = s[i] - s[i - 1];
      diff += d * d;
      energy += (double) s[i] * s[i];
    }
    int n = Math.max(1, to - from - 1);
    return Math.sqrt(diff / n) / (Math.sqrt(energy / n) + 1e-12);
  }
}
