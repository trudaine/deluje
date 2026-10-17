package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.AudioTrackModel;
import org.deluge.model.ClipModel;
import org.deluge.model.Drum;
import org.deluge.model.ProjectModel;
import org.deluge.model.SoundDrum;
import org.deluge.model.SynthTrackModel;
import org.deluge.project.ProjectSerializer;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Dedicated parity tests for the upstream C++ commits ported from {@code 2d7cdf82..a616dd8e6} and
 * the accompanying DSP/XML parity fixes.
 */
class UpstreamSyncOct2026ParityTest {

  @BeforeEach
  void resetDspState() {
    GlobalSidechainBus.reset();
    Functions.resetNoiseSeed();
  }

  @Test
  void delayCopySettingsFromCopiesModeAndSync() {
    Delay src = new Delay();
    src.pingPong = false;
    src.analog = true;
    src.syncLevel = 6;
    src.syncType = 2;

    Delay dst = new Delay();
    dst.copySettingsFrom(src);

    assertFalse(dst.pingPong, "pingPong should be copied");
    assertTrue(dst.analog, "analog should be copied");
    assertEquals(6, dst.syncLevel, "syncLevel should be copied");
    assertEquals(2, dst.syncType, "syncType should be copied");
  }

  @Test
  void delayTripletSyncRateDoesNotOverflowIntoNegative() {
    Delay delay = new Delay();
    delay.syncLevel = 9;
    delay.syncType = 1; // TRIPLET (* 3 / 2)

    Delay.State state = new Delay.State();
    state.delayFeedbackAmount = 1 << 20;
    state.userDelayRate = Integer.MAX_VALUE;

    delay.setupWorkingState(state, Integer.MAX_VALUE, true);

    assertTrue(
        state.userDelayRate > 0,
        "Triplet synced delay rate must clamp before shifting instead of overflowing negative: "
            + state.userDelayRate);
    int expectedLimitShifted = (Integer.MAX_VALUE >> (9 + 5)) << (9 + 5);
    assertEquals(expectedLimitShifted, state.userDelayRate);
  }

  @Test
  void granularProcessorUpdatesGrainRateBeforeGrainSize() {
    GranularProcessor a = new GranularProcessor();
    GranularProcessor b = new GranularProcessor();
    int[] postFxA = {1 << 27};
    int[] postFxB = {1 << 27};
    int rate = 1 << 26;
    int mix = 1 << 28;
    int density = 1 << 27;

    // Processor A updates from default rate to `rate` in one step.
    a.setupGrainFX(rate, mix, density, 0, postFxA);
    // Processor B runs a second block at the same rate/density so grainRate was already updated.
    b.setupGrainFX(rate, mix, density - 1, 0, postFxB);
    b.setupGrainFX(rate, mix, density, 0, postFxB);

    assertEquals(
        b.grainSize,
        a.grainSize,
        "grainSize must use the newly computed grainRate on the first block rate changes");
  }

  @Test
  void envelopeNoteOnSeedsSmoothedSustain() {
    Envelope env = new Envelope();
    int customSustain = 536870912; // 0.25 in Q31
    env.noteOn(false, customSustain);

    assertEquals(customSustain, env.smoothedSustain, "noteOn(bool, int) must seed smoothedSustain");
  }

  @Test
  void shiftVolumeByDbShiftsUpAndDownAndSaturates() {
    int base = 0; // mid knob (interval 8)
    int up = Functions.shiftVolumeByDB(base, 3.0f);
    int down = Functions.shiftVolumeByDB(base, -3.0f);

    assertTrue(up > base, "+3 dB must raise the volume knob");
    assertTrue(down < base, "-3 dB must lower the volume knob");
    assertEquals(
        Integer.MIN_VALUE,
        Functions.shiftVolumeByDB(Integer.MIN_VALUE, 6.0f),
        "Interval 0 is not a real dB interval and must stay unchanged");
    assertEquals(
        Integer.MAX_VALUE,
        Functions.shiftVolumeByDB(base, 200.0f),
        "Large positive dB shift must saturate to Integer.MAX_VALUE");
    assertEquals(
        Integer.MIN_VALUE,
        Functions.shiftVolumeByDB(base, -200.0f),
        "Large negative dB shift must saturate to Integer.MIN_VALUE");
  }

  @Test
  void oscillatorNonSquarePulseWidthUsesUnsignedShift() {
    int[] out = new int[64];
    int[] startPhase = {0};
    // pwAbs >= 0x80000000: signed >> 1 would sign-extend to negative and cancel +1073741824.
    int highPwAbs = 0x80000000;
    Oscillator.renderOsc(
        Oscillator.OscType.SAW,
        1 << 27,
        out,
        0,
        64,
        1 << 24,
        highPwAbs,
        startPhase,
        true,
        0,
        false,
        0,
        0,
        0);

    int peak = 0;
    for (int sample : out) {
      peak = Math.max(peak, Math.abs(sample));
    }
    assertTrue(
        peak > 1_000_000,
        "Non-square PW with pwAbs=0x80000000 must render audible output via unsigned >>> 1");
  }

  @Test
  void reverbModelsApplyUpstreamFixes() {
    assertEquals(
        2147483520, Reverb.toQ31Saturating(1e12f), "toQ31Saturating ceiling is 0x7FFFFF80");
    assertEquals(
        Integer.MIN_VALUE, Reverb.toQ31Saturating(-1e12f), "toQ31Saturating floor is INT_MIN");

    Reverb.MutableModel mutable = new Reverb.MutableModel();
    mutable.setDamping(0.0f);
    float brightLp = mutable.lp;
    mutable.setDamping(1.0f);
    float darkLp = mutable.lp;
    assertEquals(1.0f, brightLp, 1e-5f, "damping=0 should leave lp=1.0 (no damping)");
    assertTrue(darkLp < 0.02f, "damping=1 should set lp near 0.01 (darkest)");

    Reverb.DigitalModel digital = new Reverb.DigitalModel();
    digital.setRoomSize(0.0f);
    assertEquals(0.1f, digital.reverbTime, 1e-6f, "DigitalModel min decay is 0.1");
    digital.setRoomSize(1.0f);
    assertEquals(0.9f, digital.reverbTime, 1e-6f, "DigitalModel max decay is 0.9");
  }

  @Test
  void voiceAdjustPitchGatesAtOneShift23AndMasterCentsTunesVoices() {
    // At neutral adjustment (16777216 = 2^24), adjustPitch returns phaseIncrement unchanged.
    assertEquals(1 << 20, Voice.adjustPitch(1 << 20, 16777216));
    // Double pitch (1 << 25) taking phaseIncrement from (1 << 30) gives output = (1 << 23), hitting
    // the Nyquist gate.
    assertEquals(
        -1,
        Voice.adjustPitch(1 << 30, 1 << 25),
        "adjustPitch must gate at output >= (1 << 23) so << 8 cannot wrap negative");
    assertTrue(
        Voice.adjustPitch((1 << 30) - 1024, 1 << 25) > 0,
        "Below (1 << 23) adjustPitch must remain active");

    Sound sound = new Sound();
    sound.setMasterCents(50);
    assertEquals(50, sound.masterCents);
    int baseInc = 10_000_000;
    int tunedInc = sound.masterFineTuner.detune(baseInc);
    assertTrue(tunedInc > baseInc, "+50 master cents must raise phase increment");
  }

  @Test
  void transposeClampAllowsFourOctavesAndVolumeTargetsPostFx() throws Exception {
    SynthTrackModel synth = new SynthTrackModel("TransposeAndVolume");
    synth.setTranspose(-96);
    synth.setOsc1Transpose(96);
    synth.setOsc2Transpose(-72);
    synth.setCents(35);
    assertEquals(-96, synth.getTranspose());
    assertEquals(96, synth.getOsc1Transpose());
    assertEquals(-72, synth.getOsc2Transpose());
    assertEquals(35, synth.getCents());

    Drum drum = new SoundDrum("SynthDrum");
    drum.setOsc1Transpose(-96);
    drum.setOsc2Transpose(96);
    drum.setCents(-40);
    assertEquals(-96, drum.getOsc1Transpose());
    assertEquals(96, drum.getOsc2Transpose());
    assertEquals(-40, drum.getCents());

    // XML <volume> must populate GLOBAL_VOLUME_POST_FX while leaving LOCAL_VOLUME at 0.
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <sound>
          <osc1 type="saw" transpose="-48" cents="0" />
          <osc2 type="sine" transpose="48" cents="0" />
          <transpose>-36</transpose>
          <cents>25</cents>
          <defaultParams>
            <volume>0x40000000</volume>
          </defaultParams>
        </sound>
        """;
    SynthTrackModel parsed =
        DelugeXmlParser.parseSynth(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "test.xml");
    assertEquals(-36, parsed.getTranspose());
    assertEquals(25, parsed.getCents());
    assertEquals(-48, parsed.getOsc1Transpose());
    assertEquals(48, parsed.getOsc2Transpose());
    assertEquals(
        0,
        parsed.getRawKnobs().getRawParamKnobs().get(Param.LOCAL_VOLUME),
        "LOCAL_VOLUME must stay 0 when parsing XML <volume>");
    assertEquals(
        0x40000000,
        parsed.getRawKnobs().getRawParamKnobs().get(Param.GLOBAL_VOLUME_POST_FX),
        "XML <volume> must map to GLOBAL_VOLUME_POST_FX");

    parsed.addClip(new ClipModel("c", 8, 16));
    ProjectModel project = new ProjectModel();
    project.addTrack(parsed);
    ProjectModel fwSong = FirmwareFactory.createSong(project);
    FirmwareSound fwSound = (FirmwareSound) fwSong.getTracks().get(0).getActiveClip().getSound();
    assertEquals(-36, fwSound.fw2Sound.masterTranspose);
    assertEquals(25, fwSound.fw2Sound.masterCents);
    assertEquals(0, fwSound.paramKnobs[Param.LOCAL_VOLUME]);
    assertEquals(0x40000000, fwSound.paramKnobs[Param.GLOBAL_VOLUME_POST_FX]);
  }

  @Test
  void audioClipNameRoundTripsThroughSongXml() throws Exception {
    ProjectModel project = new ProjectModel();
    AudioTrackModel track = new AudioTrackModel("Audio 1");
    AudioTrackModel.AudioClip clip = new AudioTrackModel.AudioClip();
    clip.setClipName("Vocal Take 3");
    clip.setLength(192);
    clip.setTrackName("Audio 1");
    track.addAudioClip(clip);
    project.addTrack(track);

    String savedXml = ProjectSerializer.serializeToString(project);
    assertTrue(
        savedXml.contains("clipName=\"Vocal Take 3\""),
        "Serialized <audioClip> must write clipName attribute");

    ProjectModel reparsed =
        DelugeXmlParser.parseSong(
            new ByteArrayInputStream(savedXml.getBytes(StandardCharsets.UTF_8)), "SONG.XML");
    AudioTrackModel reparsedTrack =
        reparsed.getTracks().stream()
            .filter(AudioTrackModel.class::isInstance)
            .map(AudioTrackModel.class::cast)
            .findFirst()
            .orElseThrow();
    assertEquals(1, reparsedTrack.getAudioClips().size());
    assertEquals("Vocal Take 3", reparsedTrack.getAudioClips().get(0).getClipName());
  }

  @Test
  void unisonHexDetuneInLegacySongsParsesAsZeroLikeFirmware() throws Exception {
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <sound>
          <unison>
            <num>4</num>
            <detune>0x7FFFFFFF</detune>
            <spread>0x00000000</spread>
          </unison>
        </sound>
        """;
    SynthTrackModel parsed =
        DelugeXmlParser.parseSynth(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "legacy.xml");
    assertEquals(4, parsed.getUnison().getUnisonNum());
    assertEquals(
        0.0f,
        parsed.getUnison().getUnisonDetune(),
        1e-6f,
        "C readTagOrAttributeValueInt stops at 'x' on 0x7FFFFFFF and returns 0");
    assertEquals(0.0f, parsed.getUnison().getUnisonStereoSpread(), 1e-6f);
    assertNotEquals(50.0f, parsed.getUnison().getUnisonDetune());
  }
}
