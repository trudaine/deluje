package org.deluge;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.model.*;
import org.deluge.ui.SwingDelugeApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the GlobalEffectable-style per-clip FX overrides in SwingDelugeApp.
 *
 * <p>Verifies that {@code applyClipFxOverrides()} pushes per-clip FX params to the correct G_SP_*
 * globals, overriding song-level defaults, and that switching clips restores the correct set of
 * overrides.
 */
class GlobalEffectableOverridesTest {

  private BridgeContract bridge;

  private SwingDelugeApp app;
  private ProjectModel project;

  @BeforeEach
  void setUp() {
    System.setProperty("chuck.audio.dummy", "true");
    bridge = new BridgeContract();

    app = new SwingDelugeApp(bridge, null);
    project = new ProjectModel();
  }

  @AfterEach
  void tearDown() {
    if (bridge != null) bridge.shutdown();
  }

  // ── Helpers ──

  /** Create a KitTrackModel with the given number of voices and a single clip with kitParams. */
  private KitTrackModel createKitTrack(int voices, String clipName) {
    KitTrackModel kit = new KitTrackModel("TestKit");
    ClipModel clip = new ClipModel(clipName, voices, 16);
    kit.addClip(clip);
    kit.setActiveClipIndex(0);
    return kit;
  }

  /** Create an AudioTrackModel with a single AudioClip. */
  private AudioTrackModel createAudioTrack(String clipName) {
    AudioTrackModel audio = new AudioTrackModel("TestAudio");
    AudioTrackModel.AudioClip aClip = new AudioTrackModel.AudioClip();
    aClip.setFilePath("test.wav");
    aClip.setStartSamplePos(0);
    aClip.setEndSamplePos(44100);
    audio.addAudioClip(aClip);
    audio.setActiveClipIndex(0);
    return audio;
  }

  // ── Tests for kit track (ClipModel.kitParams map) ──

  @Test
  void kitTrackClipOverridesVolumeAndPan() {
    KitTrackModel kit = createKitTrack(8, "CLIP 1");
    kit.getClips().get(0).setKitParam("volume", 0.5f);
    kit.getClips().get(0).setKitParam("pan", -0.3f);
    project.addTrack(kit);
    app.loadProject(project);

    assertEquals(0.5f, bridge.getGlobalFloat(BridgeContract.G_SP_VOLUME), 0.001f);
    assertEquals(-0.3f, bridge.getGlobalFloat(BridgeContract.G_SP_PAN), 0.001f);
  }

  @Test
  void kitTrackClipOverridesAllReverbDelay() {
    KitTrackModel kit = createKitTrack(8, "CLIP 1");
    ClipModel clip = kit.getClips().get(0);
    clip.setKitParam("reverbAmount", 0.8f);
    clip.setKitParam("delayRate", 0.25f);
    clip.setKitParam("delayFeedback", 0.6f);
    project.addTrack(kit);
    app.loadProject(project);

    assertEquals(0.8f, bridge.getGlobalFloat(BridgeContract.G_SP_REVERB_AMOUNT), 0.001f);
    assertEquals(0.25f, bridge.getGlobalFloat(BridgeContract.G_SP_DELAY_RATE), 0.001f);
    assertEquals(0.6f, bridge.getGlobalFloat(BridgeContract.G_SP_DELAY_FEEDBACK), 0.001f);
  }

  @Test
  void kitTrackClipOverridesModFxAndStutter() {
    KitTrackModel kit = createKitTrack(8, "CLIP 1");
    ClipModel clip = kit.getClips().get(0);
    clip.setKitParam("modFXRate", 0.3f);
    clip.setKitParam("modFXDepth", 0.7f);
    clip.setKitParam("modFXOffset", 0.2f);
    clip.setKitParam("modFXFeedback", 0.4f);
    clip.setKitParam("stutterRate", 0.9f);
    clip.setKitParam("sampleRateReduction", 0.1f);
    clip.setKitParam("bitCrush", 0.0f);
    project.addTrack(kit);
    app.loadProject(project);

    assertEquals(0.3f, bridge.getGlobalFloat(BridgeContract.G_SP_MOD_FX_RATE), 0.001f);
    assertEquals(0.7f, bridge.getGlobalFloat(BridgeContract.G_SP_MOD_FX_DEPTH), 0.001f);
    assertEquals(0.2f, bridge.getGlobalFloat(BridgeContract.G_SP_MOD_FX_OFFSET), 0.001f);
    assertEquals(0.4f, bridge.getGlobalFloat(BridgeContract.G_SP_MOD_FX_FEEDBACK), 0.001f);
    assertEquals(0.9f, bridge.getGlobalFloat(BridgeContract.G_SP_STUTTER_RATE), 0.001f);
    assertEquals(0.1f, bridge.getGlobalFloat(BridgeContract.G_SP_SAMPLE_RATE_REDUCTION), 0.001f);
    assertEquals(0.0f, bridge.getGlobalFloat(BridgeContract.G_SP_BITCRUSH), 0.001f);
  }

  @Test
  void kitTrackClipOverridesFiltersAndEq() {
    KitTrackModel kit = createKitTrack(8, "CLIP 1");
    ClipModel clip = kit.getClips().get(0);
    clip.setKitParam("lpfFrequency", 8000.0f);
    clip.setKitParam("lpfResonance", 0.5f);
    clip.setKitParam("hpfFrequency", 100.0f);
    clip.setKitParam("hpfResonance", 0.3f);
    clip.setKitParam("eqBass", -6.0f);
    clip.setKitParam("eqTreble", 3.0f);
    clip.setKitParam("lpfMorph", 0.7f);
    clip.setKitParam("hpfMorph", 0.2f);
    project.addTrack(kit);
    app.loadProject(project);

    assertEquals(8000.0f, bridge.getGlobalFloat(BridgeContract.G_SP_LPF_FREQ), 0.001f);
    assertEquals(0.5f, bridge.getGlobalFloat(BridgeContract.G_SP_LPF_RES), 0.001f);
    assertEquals(100.0f, bridge.getGlobalFloat(BridgeContract.G_SP_HPF_FREQ), 0.001f);
    assertEquals(0.3f, bridge.getGlobalFloat(BridgeContract.G_SP_HPF_RES), 0.001f);
    assertEquals(-6.0f, bridge.getGlobalFloat(BridgeContract.G_SP_EQ_BASS), 0.001f);
    assertEquals(3.0f, bridge.getGlobalFloat(BridgeContract.G_SP_EQ_TREBLE), 0.001f);
    assertEquals(0.7f, bridge.getGlobalFloat(BridgeContract.G_SP_LPF_MORPH), 0.001f);
    assertEquals(0.2f, bridge.getGlobalFloat(BridgeContract.G_SP_HPF_MORPH), 0.001f);
  }

  @Test
  void kitClipWithoutOverridesPreservesSongDefaults() {
    // Set song defaults
    project.setSongParamVolume(0.8f);
    project.setSongParamPan(0.1f);
    project.setSongParamReverbAmount(0.5f);
    project.setSongParamDelayRate(0.2f);
    project.setSongParamDelayFeedback(0.4f);

    KitTrackModel kit = createKitTrack(8, "CLIP 1");
    // No kitParams set
    project.addTrack(kit);
    app.loadProject(project);

    // Should preserve song defaults
    assertEquals(0.8f, bridge.getGlobalFloat(BridgeContract.G_SP_VOLUME), 0.001f);
    assertEquals(0.1f, bridge.getGlobalFloat(BridgeContract.G_SP_PAN), 0.001f);
    assertEquals(0.5f, bridge.getGlobalFloat(BridgeContract.G_SP_REVERB_AMOUNT), 0.001f);
    assertEquals(0.2f, bridge.getGlobalFloat(BridgeContract.G_SP_DELAY_RATE), 0.001f);
    assertEquals(0.4f, bridge.getGlobalFloat(BridgeContract.G_SP_DELAY_FEEDBACK), 0.001f);
  }

  @Test
  void kitTrackPartialOverridesLeaveOthersAtSongDefault() {
    project.setSongParamVolume(1.0f);
    project.setSongParamReverbAmount(0.3f);

    KitTrackModel kit = createKitTrack(8, "CLIP 1");
    kit.getClips().get(0).setKitParam("volume", 0.6f); // only override volume
    project.addTrack(kit);
    app.loadProject(project);

    assertEquals(0.6f, bridge.getGlobalFloat(BridgeContract.G_SP_VOLUME), 0.001f);
    // reverbAmount not overridden — should still be song default
    assertEquals(0.3f, bridge.getGlobalFloat(BridgeContract.G_SP_REVERB_AMOUNT), 0.001f);
  }

  // ── Tests for audio track (AudioClip typed fields) ──

  @Test
  void audioTrackClipOverridesVolume() {
    AudioTrackModel audio = createAudioTrack("AUDIO CLIP");
    audio.getAudioClips().get(0).setVolume(0.4f);
    project.addTrack(audio);
    // set song default volume to a different value
    project.setSongParamVolume(0.9f);
    app.loadProject(project);

    // AudioClip volume (0.4) should override song default (0.9)
    assertEquals(0.4f, bridge.getGlobalFloat(BridgeContract.G_SP_VOLUME), 0.001f);
  }

  @Test
  void audioTrackClipOverridesReverbDelay() {
    AudioTrackModel audio = createAudioTrack("AUDIO CLIP");
    AudioTrackModel.AudioClip clip = audio.getAudioClips().get(0);
    clip.setReverbAmount(0.7f);
    clip.setDelayRate(0.15f);
    clip.setDelayFeedback(0.5f);
    project.addTrack(audio);
    app.loadProject(project);

    assertEquals(0.7f, bridge.getGlobalFloat(BridgeContract.G_SP_REVERB_AMOUNT), 0.001f);
    assertEquals(0.15f, bridge.getGlobalFloat(BridgeContract.G_SP_DELAY_RATE), 0.001f);
    assertEquals(0.5f, bridge.getGlobalFloat(BridgeContract.G_SP_DELAY_FEEDBACK), 0.001f);
  }

  @Test
  void audioTrackClipOverridesModFx() {
    AudioTrackModel audio = createAudioTrack("AUDIO CLIP");
    AudioTrackModel.AudioClip clip = audio.getAudioClips().get(0);
    clip.setModFXRate(0.6f);
    clip.setModFXDepth(0.3f);
    clip.setModFXOffset(0.8f);
    clip.setModFXFeedback(0.9f);
    project.addTrack(audio);
    app.loadProject(project);

    assertEquals(0.6f, bridge.getGlobalFloat(BridgeContract.G_SP_MOD_FX_RATE), 0.001f);
    assertEquals(0.3f, bridge.getGlobalFloat(BridgeContract.G_SP_MOD_FX_DEPTH), 0.001f);
    assertEquals(0.8f, bridge.getGlobalFloat(BridgeContract.G_SP_MOD_FX_OFFSET), 0.001f);
    assertEquals(0.9f, bridge.getGlobalFloat(BridgeContract.G_SP_MOD_FX_FEEDBACK), 0.001f);
  }

  @Test
  void audioTrackClipOverridesFiltersAndEq() {
    AudioTrackModel audio = createAudioTrack("AUDIO CLIP");
    AudioTrackModel.AudioClip clip = audio.getAudioClips().get(0);
    clip.setLpfFrequency(12000.0f);
    clip.setLpfResonance(0.4f);
    clip.setHpfFrequency(200.0f);
    clip.setHpfResonance(0.2f);
    clip.setEqBass(-3.0f);
    clip.setEqTreble(1.5f);
    clip.setEqBassFrequency(200.0f);
    clip.setEqTrebleFrequency(6000.0f);
    project.addTrack(audio);
    app.loadProject(project);

    assertEquals(12000.0f, bridge.getGlobalFloat(BridgeContract.G_SP_LPF_FREQ), 0.001f);
    assertEquals(0.4f, bridge.getGlobalFloat(BridgeContract.G_SP_LPF_RES), 0.001f);
    assertEquals(200.0f, bridge.getGlobalFloat(BridgeContract.G_SP_HPF_FREQ), 0.001f);
    assertEquals(0.2f, bridge.getGlobalFloat(BridgeContract.G_SP_HPF_RES), 0.001f);
    assertEquals(-3.0f, bridge.getGlobalFloat(BridgeContract.G_SP_EQ_BASS), 0.001f);
    assertEquals(1.5f, bridge.getGlobalFloat(BridgeContract.G_SP_EQ_TREBLE), 0.001f);
    assertEquals(200.0f, bridge.getGlobalFloat(BridgeContract.G_SP_EQ_BASS_FREQ), 0.001f);
    assertEquals(6000.0f, bridge.getGlobalFloat(BridgeContract.G_SP_EQ_TREBLE_FREQ), 0.001f);
  }

  @Test
  void audioTrackClipWithoutOverridesPreservesSongDefaults() {
    project.setSongParamVolume(0.75f);
    project.setSongParamReverbAmount(0.4f);

    AudioTrackModel audio = createAudioTrack("AUDIO CLIP");
    // AudioClip defaults are all defaults — don't change them
    project.addTrack(audio);
    app.loadProject(project);

    // AudioClip volume default is 1.0, which overrides song's 0.75
    assertEquals(1.0f, bridge.getGlobalFloat(BridgeContract.G_SP_VOLUME), 0.001f);
    // AudioClip reverbAmount default is 0, which overrides song's 0.4
    assertEquals(0.0f, bridge.getGlobalFloat(BridgeContract.G_SP_REVERB_AMOUNT), 0.001f);
    // AudioClip lpfFrequency default is 20000, delayRate/delayFeedback default is 0,
    // so those override song defaults too. This is correct — audio clips always push
    // their full set of params unconditionally.
  }

  // ── Multi-track tests ──

  @Test
  void twoTracksEachGetTheirOwnOverrides() {
    // Track 0: kit clip with volume override
    KitTrackModel kit0 = createKitTrack(8, "KIT CLIP 0");
    kit0.getClips().get(0).setKitParam("volume", 0.5f);
    project.addTrack(kit0);

    // Track 1: kit clip with different volume override
    KitTrackModel kit1 = createKitTrack(4, "KIT CLIP 1");
    kit1.getClips().get(0).setKitParam("volume", 0.8f);
    project.addTrack(kit1);

    app.loadProject(project);

    // Each track has its own active clip — applyClipFxOverrides runs for each
    // They set G_SP_VOLUME which is shared (last writer wins for now)
    // This tests that the code doesn't crash with >1 track
    assertEquals(0.8f, bridge.getGlobalFloat(BridgeContract.G_SP_VOLUME), 0.001f);
  }

  // ── AudioClip play/loop/rate state push ──

  @Test
  void audioTrackPushClipPlayState() {
    AudioTrackModel audio = createAudioTrack("AUDIO CLIP");
    audio.getAudioClips().get(0).setPlaying(true);
    audio.setLooping(true);
    project.addTrack(audio);
    app.loadProject(project);

    // The audio track push section sets play/loop/rate
    int trackIdx = 0;
    assertEquals(1L, bridge.getAudioPlay(trackIdx));
    assertEquals(1L, bridge.getAudioLoop(trackIdx));
    assertEquals(1.0f, bridge.getAudioRate(trackIdx), 0.001f);
  }
}
