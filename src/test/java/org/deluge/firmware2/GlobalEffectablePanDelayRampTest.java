package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareKit;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.AudioTrackModel;
import org.deluge.model.ClipModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.playback.PlaybackHandler;
import org.deluge.xml.SongXmlParser;
import org.junit.jupiter.api.Test;

/**
 * Verifies C++ parity for {@code shouldLimitDelayFeedback} (song.cpp:2509-2514,
 * sound.cpp:2500-2503, kit.cpp:582-584), clip-level {@code UNPATCHED_PAN}
 * (global_effectable_for_clip.cpp:88, 146-147 vs sound.cpp:2613), and the per-sample {@code
 * postReverbSendVolumeIncrement} ramp (mod_controllable_audio.cpp:226-266, sound.cpp:94, 2601-2603,
 * 2700).
 */
class GlobalEffectablePanDelayRampTest {

  private static final int DELAY_FEEDBACK_LIMIT = (1 << 30) - (1 << 26); // 0x3C000000

  @Test
  void soundAndKitPropagateShouldLimitDelayFeedback() {
    Kit kit = new Kit();
    kit.shouldLimitDelayFeedback = true;
    int[] buf = new int[32];
    int[] rev = new int[16];
    kit.renderInternal(buf, 16, rev);
    for (Sound drum : kit.drums) {
      assertTrue(
          drum.shouldLimitDelayFeedback,
          "C kit.cpp:582-584: Kit must propagate shouldLimitDelayFeedback to every child drum");
    }

    FirmwareKit fwKit = new FirmwareKit();
    fwKit.shouldLimitDelayFeedback = true;
    fwKit.drumSounds.get(0).triggerNote(60, 100);
    fwKit.renderOutput(new long[32], 16, new long[16]);
    assertTrue(fwKit.drumSounds.get(0).shouldLimitDelayFeedback);
    assertTrue(fwKit.drumSounds.get(0).fw2Sound.shouldLimitDelayFeedback);

    // Sound delay feedback clamps at (1 << 30) - (1 << 26) when shouldLimitDelayFeedback is true.
    Sound sound = new Sound();
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    sound.patchedParamValues[Param.GLOBAL_VOLUME_POST_FX] = 0x50000000;
    sound.patchedParamValues[Param.GLOBAL_VOLUME_POST_REVERB_SEND] = 0;
    sound.delayFeedbackAmount = 0x70000000;
    sound.delayUserRate = 1 << 24;
    sound.shouldLimitDelayFeedback = true;
    sound.triggerNote(60, 100);
    long[] out = new long[32];
    long[] revLong = new long[16];
    sound.renderOutput(out, 16, revLong);
    assertEquals(
        DELAY_FEEDBACK_LIMIT,
        sound.delayState.delayFeedbackAmount,
        "C sound.cpp:2500-2503: delayFeedbackAmount must clamp when shouldLimitDelayFeedback=true");
  }

  @Test
  void audioOutputAndPlaybackHandlerUpdateShouldLimitDelayFeedback() {
    AudioOutput ao = new AudioOutput();
    Sample sample = new Sample();
    sample.data = new int[] {1 << 20, 1 << 20, 1 << 20, 1 << 20};
    sample.lengthInSamples = 4;
    sample.numChannels = 1;
    ao.setClip(sample, true);
    ao.addTimelineRange(10, 20);

    ao.updateTimeline(0, true); // before placement -> not playing
    assertFalse(ao.isPlaying());
    assertTrue(ao.shouldLimitDelayFeedback);

    ao.updateTimeline(12, true); // inside placement -> playing
    assertTrue(ao.isPlaying());
    assertFalse(ao.shouldLimitDelayFeedback);

    ao.updateTimeline(25, true); // after placement -> stopped
    assertFalse(ao.isPlaying());
    assertTrue(ao.shouldLimitDelayFeedback);

    ao.setPlaying(true);
    assertFalse(ao.shouldLimitDelayFeedback);
    ao.setPlaying(false);
    assertTrue(ao.shouldLimitDelayFeedback);

    // Inactive clip at song creation / setProject before start() must already limit delay feedback
    ProjectModel inactiveProj = new ProjectModel();
    SynthTrackModel inactiveSynth = new SynthTrackModel("InactiveSynth");
    ClipModel inactiveClip = new ClipModel("IdleClip", 1, 16);
    inactiveClip.setActiveInSession(false);
    inactiveSynth.addClip(inactiveClip);
    inactiveProj.addTrack(inactiveSynth);
    FirmwareFactory.createSong(inactiveProj);
    FirmwareSound inactiveFwSound = (FirmwareSound) inactiveClip.getSound();
    assertTrue(
        inactiveFwSound.shouldLimitDelayFeedback,
        "createSong must set shouldLimitDelayFeedback=true when no clip is active");
    inactiveFwSound.shouldLimitDelayFeedback = false;
    PlaybackHandler idleHandler = new PlaybackHandler();
    idleHandler.setProject(inactiveProj);
    assertTrue(
        inactiveFwSound.shouldLimitDelayFeedback,
        "setProject must sync shouldLimitDelayFeedback before start()");

    // PlaybackHandler session clip activeness -> shouldLimitDelayFeedback (!isClipActiveNow)
    ProjectModel project = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("Synth");
    ClipModel clip = new ClipModel("OnceClip", 1, 16);
    clip.setLaunchStyle("once");
    clip.setLoopLength(96);
    clip.setActiveInSession(true);
    synth.addClip(clip);
    project.addTrack(synth);
    FirmwareFactory.createSong(project);

    FirmwareSound fwSound = (FirmwareSound) clip.getSound();
    PlaybackHandler handler = new PlaybackHandler();
    handler.setProject(project);
    handler.start();
    assertFalse(
        fwSound.shouldLimitDelayFeedback,
        "Active session clip must keep shouldLimitDelayFeedback=false");

    // Toggle ONCE clip while playing (armed to stop at end of 1st loop) or instant-stop:
    handler.toggleClipStatus(synth, clip, true);
    assertFalse(clip.isActiveInSession());
    assertTrue(
        fwSound.shouldLimitDelayFeedback,
        "C song.cpp:2509-2514: stopped session clip must set shouldLimitDelayFeedback=true");
  }

  @Test
  void clipLevelUnpatchedPanScalesClipEffectableButNotSound() {
    int sampleVal = 1 << 24;
    int postFX = 134217728;
    int postRev = 134217728;

    GlobalEffectable clipEffectable =
        new GlobalEffectable() {
          @Override
          protected boolean hasTrackLevelSaturation() {
            return false;
          }

          @Override
          protected void renderInternal(int[] buffer, int numSamples, int[] reverbBuffer) {
            this.postFXVolume = postFX;
            this.postReverbVolume = postRev;
            for (int i = 0; i < numSamples; i++) {
              buffer[i * 2] = sampleVal;
              buffer[i * 2 + 1] = sampleVal;
            }
          }
        };

    // Pan full left (UNPATCHED_PAN = -2^31 -> pan = -2^30 -> right channel attenuated to 0)
    clipEffectable.unpatchedPan = Integer.MIN_VALUE;
    long[] out = new long[4];
    long[] rev = new long[2];
    clipEffectable.renderOutput(out, 2, rev);
    assertTrue(out[0] > 0, "Left channel must stay audible when panned full left");
    assertEquals(0L, out[1], "Right channel must be zero when panned full left");

    // Pan full right (UNPATCHED_PAN = 2^31 - 1 -> pan = ~2^30 - 1 -> left channel ~0, right full)
    clipEffectable.unpatchedPan = Integer.MAX_VALUE;
    out = new long[4];
    clipEffectable.renderOutput(out, 2, rev);
    assertTrue(out[1] > out[0] * 1000, "Right channel must dominate when panned full right");

    // Sound must ignore unpatchedPan (C sound.cpp:2613 passes pan = 0)
    Sound sound = new Sound();
    sound.unpatchedPan = Integer.MIN_VALUE;
    assertEquals(0, sound.getPostReverbPan());
    FirmwareSound fwSound = new FirmwareSound();
    fwSound.unpatchedPan = Integer.MIN_VALUE;
    fwSound.triggerNote(60, 100);
    long[] fwOut = new long[32];
    fwSound.renderOutput(fwOut, 16, new long[16]);
    assertEquals(
        fwOut[0], fwOut[1], "FirmwareSound must ignore unpatchedPan and stay center-panned");
  }

  @Test
  void postReverbSendVolumeIncrementRampsPerSample() {
    int sampleVal = 1 << 24;
    int postFX = 134217728;
    int prevPostRev = 67108864; // 0.5x neutral
    int currPostRev = 134217728; // 1.0x neutral
    int numSamples = 4;

    GlobalEffectable effectable =
        new GlobalEffectable() {
          @Override
          protected boolean hasTrackLevelSaturation() {
            return false;
          }

          @Override
          protected void renderInternal(int[] buffer, int numSamples, int[] reverbBuffer) {
            this.postFXVolume = postFX;
            this.postReverbVolume = currPostRev;
            for (int i = 0; i < numSamples * 2; i++) {
              buffer[i] = sampleVal;
            }
          }
        };
    effectable.postReverbVolumeLastTime = prevPostRev;

    long[] out = new long[numSamples * 2];
    long[] rev = new long[numSamples];
    effectable.renderOutput(out, numSamples, rev);

    // Check per-sample ramp from postReverbVolumeLastTime to postReverbVolume.
    int baseVol = Functions.multiply_32x32_rshift32(prevPostRev, postFX) << 5;
    int inc = (int) ((double) (currPostRev - prevPostRev) / (double) numSamples);
    int ampInc = Functions.multiply_32x32_rshift32(postFX, inc) << 5;
    int runningVol = baseVol;
    for (int i = 0; i < numSamples; i++) {
      runningVol += ampInc;
      int expected =
          Functions.lshiftAndSaturate(Functions.multiply_32x32_rshift32(sampleVal, runningVol), 5);
      assertEquals(expected, out[i * 2], "Sample " + i + " L must follow C per-sample ramp");
      assertEquals(expected, out[i * 2 + 1], "Sample " + i + " R must follow C per-sample ramp");
    }
    assertEquals(currPostRev, effectable.postReverbVolumeLastTime);

    // Sound starts with postReverbVolumeLastTime = -1 (C sound.cpp:94) and resets to -1 on silence
    Sound sound = new Sound();
    assertEquals(-1, sound.postReverbVolumeLastTime);
    sound.renderOutput(new long[8], 4, new long[4]); // silent block -> stays -1
    assertEquals(-1, sound.postReverbVolumeLastTime);
  }

  @Test
  void songXmlParserAndFactoryPreserveSignedKitAndAudioClipPan() throws Exception {
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song firmwareVersion="4.1.0">
          <instruments>
            <kit name="PanKit">
              <soundSources>
                <sound name="Kick">
                  <osc1 type="square"/>
                </sound>
              </soundSources>
            </kit>
            <audioTrack name="Audio1"/>
          </instruments>
          <sessionClips>
            <instrumentClip instrumentPresetName="PanKit" instrumentPresetFolder="KITS" length="96">
              <kitParams volume="0x00000000" pan="0xC0000000" reverbAmount="0x80000000">
                <equalizer bass="0xC0000000" treble="0x40000000"/>
              </kitParams>
            </instrumentClip>
            <audioClip trackName="Audio1" length="96">
              <params volume="0x00000000" pan="0xC0000000"/>
            </audioClip>
          </sessionClips>
        </song>
        """;

    ProjectModel project =
        SongXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "pan_test.xml");
    KitTrackModel kitTrack = (KitTrackModel) project.getTracks().get(0);
    ClipModel kitClip = kitTrack.getClips().get(0);
    assertEquals(
        -0.5f,
        kitClip.getKitParams().get("pan"),
        0.001f,
        "Negative kit clip pan (0xC0000000) must stay negative");
    assertEquals(0.5f, kitClip.getKitParams().get("volume"), 0.001f);
    assertEquals(0.0f, kitClip.getKitParams().get("reverbAmount"), 0.001f);
    assertEquals(-0.5f, kitClip.getKitParams().get("eqBass"), 0.001f);
    assertEquals(0.5f, kitClip.getKitParams().get("eqTreble"), 0.001f);

    FirmwareKit fwKit = FirmwareFactory.createKitSound(kitTrack, project);
    assertTrue(fwKit.unpatchedPan < -1_000_000_000, "Kit unpatchedPan must receive negative Q31");

    // Live apply must also sync kit unpatchedPan from model/clip
    fwKit.unpatchedPan = 0;
    FirmwareFactory.applyModelToLiveSound(kitTrack, fwKit);
    assertTrue(
        fwKit.unpatchedPan < -1_000_000_000, "applyModelToLiveSound must sync kit unpatchedPan");

    // Round-trip save via ProjectSerializer must preserve signed kit pan
    java.io.File tmpXml = java.io.File.createTempFile("kit_pan_roundtrip_", ".xml");
    tmpXml.deleteOnExit();
    org.deluge.project.ProjectSerializer.save(project, tmpXml);
    try (java.io.FileInputStream fis = new java.io.FileInputStream(tmpXml)) {
      ProjectModel reloaded = SongXmlParser.parseSong(fis, tmpXml.getName());
      KitTrackModel reloadedKit = (KitTrackModel) reloaded.getTracks().get(0);
      assertEquals(
          -0.5f,
          reloadedKit.getClips().get(0).getKitParams().get("pan"),
          0.01f,
          "ProjectSerializer save/load must preserve negative kit clip pan");
      assertEquals(-0.5f, reloadedKit.getPan(), 0.01f);
    }

    AudioTrackModel audioTrack = (AudioTrackModel) project.getTracks().get(1);
    GlobalEffectable fwAudio = FirmwareFactory.createAudioSound(audioTrack, 1, project);
    assertTrue(
        fwAudio.unpatchedPan < -1_000_000_000,
        "AudioOutput unpatchedPan must receive negative Q31");
  }
}
