package org.chuck.deluge.ui;

import java.awt.*;
import java.io.IOException;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.swing.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.firmware.engine.FirmwareFactory;
import org.chuck.deluge.firmware.gui.views.KitView;
import org.chuck.deluge.firmware.gui.views.PianoRollView;
import org.chuck.deluge.firmware.gui.views.SessionView;
import org.chuck.deluge.firmware.hid.Flasher;
import org.chuck.deluge.firmware.hid.MatrixDriver;
import org.chuck.deluge.firmware.hid.pic.PIC;
import org.chuck.deluge.model.AudioTrackModel;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.Consequence;
import org.chuck.deluge.model.EnvelopeModel;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.LfoModel;
import org.chuck.deluge.model.PatternModel;
import org.chuck.deluge.model.SynthTrackModel;

/** Alternative lightweight UI running purely on Java Swing (no native libs). */
public class SwingDelugeApp extends JFrame {
  public static SwingDelugeApp mainInstance;
  public static boolean pureModeActive = false;
  private final ChuckVM vm;
  private final BridgeContract bridge;

  private SwingGridPanel clipPanel;
  private SwingVisualizerPanel visualizerPanel;
  private SwingGridPanel songPanel;
  private SwingGridPanel arrGridPanel;
  private SwingGridPanel autoPanel;
  private SwingPerformanceViewPanel performancePanel;

  private SwingTopBarPanel topBar;
  private SwingMasterFxPanel masterFxPanel;

  private JPanel centerCardPanel;

  /** Wrap a SwingGridPanel with a small top indent so cells aren't flush with the top border. */
  private static JPanel wrapGridPanel(SwingGridPanel grid) {
    grid.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
    return grid;
  }

  private CardLayout cardLayout;

  private org.chuck.deluge.model.ProjectModel currentProject =
      org.chuck.deluge.model.ProjectModel.createDefaultProject();
  private java.io.File currentProjectFile = null;

  // Engine voice mapping: for each model track, the start engine row and voice count.
  // Kit tracks occupy getDrums().size() rows; Synth tracks occupy 1 row.
  private int[] trackEngineStart;
  private int[] trackVoiceCount;

  private final org.chuck.deluge.midi.MidiService midiService;
  private SwingProjectSidebarPanel sidebarPanel;
  private SwingProjectSidebarPanel floatingSidebar;
  private org.chuck.deluge.firmware.hid.pic.SwingPicTransport picTransport;
  private final java.util.ArrayDeque<Long> tapTimes = new java.util.ArrayDeque<>();

  /** Recompute trackEngineStart and trackVoiceCount from the current project model. */
  private void computeEngineMapping() {
    java.util.List<org.chuck.deluge.model.TrackModel> tracks = currentProject.getTracks();
    int n = tracks.size();
    trackEngineStart = new int[n];
    trackVoiceCount = new int[n];
    int nextRow = 0;
    for (int t = 0; t < n && nextRow < BridgeContract.TRACKS; t++) {
      trackEngineStart[t] = nextRow;
      boolean isKit = tracks.get(t) instanceof org.chuck.deluge.model.KitTrackModel;
      int voices =
          isKit ? ((org.chuck.deluge.model.KitTrackModel) tracks.get(t)).getDrums().size() : 8;
      int capped = Math.min(voices, BridgeContract.TRACKS - nextRow);
      trackVoiceCount[t] = capped;
      nextRow += capped;
    }
  }

  /**
   * Push the current project model into engine globals (G_TRACK_TYPE, samples, kit params,
   * patterns).
   */
  /**
   * Push per-step automation data for a single clip to the bridge, targeting the clip-indexed _C{n}
   * arrays (c==0 writes to primary arrays).
   */
  private void pushClipAutomation(
      int trackIdx, BridgeContract br, ClipModel clip, int clipIdx, int startRow) {
    int stepCount = clip.getStepCount();
    int engRow = startRow;
    for (int s = 0; s < stepCount; s++) {
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_LPF_FREQ, s))
        br.setStepFilter(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_LPF_FREQ, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_LPF_RES, s))
        br.setStepRes(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_LPF_RES, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_PAN, s))
        br.setStepPan(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_PAN, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_DELAY, s))
        br.setStepDelay(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_DELAY, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_REVERB, s))
        br.setStepReverb(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_REVERB, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_HPF_FREQ, s))
        br.setStepHpfFreq(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_HPF_FREQ, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_HPF_RES, s))
        br.setStepHpfRes(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_HPF_RES, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_MOD_FX_RATE, s))
        br.setStepModRate(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_MOD_FX_RATE, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_MOD_FX_DEPTH, s))
        br.setStepModDepth(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_MOD_FX_DEPTH, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_OSC_A_VOL, s))
        br.setStepOscAVol(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_OSC_A_VOL, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_OSC_B_VOL, s))
        br.setStepOscBVol(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_OSC_B_VOL, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_NOISE_VOL, s))
        br.setStepNoiseVol(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_NOISE_VOL, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_PITCH, s))
        br.setStepPitch(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_PITCH, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_VOLUME, s))
        br.setStepVolume(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_VOLUME, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_0_ATTACK, s))
        br.setStepEnv0Attack(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_0_ATTACK, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_0_DECAY, s))
        br.setStepEnv0Decay(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_0_DECAY, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_0_SUSTAIN, s))
        br.setStepEnv0Sustain(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_0_SUSTAIN, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_0_RELEASE, s))
        br.setStepEnv0Release(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_0_RELEASE, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_1_ATTACK, s))
        br.setStepEnv1Attack(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_1_ATTACK, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_1_DECAY, s))
        br.setStepEnv1Decay(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_1_DECAY, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_1_SUSTAIN, s))
        br.setStepEnv1Sustain(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_1_SUSTAIN, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_1_RELEASE, s))
        br.setStepEnv1Release(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_1_RELEASE, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_2_ATTACK, s))
        br.setStepEnv2Attack(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_2_ATTACK, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_2_DECAY, s))
        br.setStepEnv2Decay(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_2_DECAY, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_2_SUSTAIN, s))
        br.setStepEnv2Sustain(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_2_SUSTAIN, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_2_RELEASE, s))
        br.setStepEnv2Release(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_2_RELEASE, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_3_ATTACK, s))
        br.setStepEnv3Attack(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_3_ATTACK, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_3_DECAY, s))
        br.setStepEnv3Decay(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_3_DECAY, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_3_SUSTAIN, s))
        br.setStepEnv3Sustain(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_3_SUSTAIN, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_3_RELEASE, s))
        br.setStepEnv3Release(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_ENV_3_RELEASE, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_LFO_0_RATE, s))
        br.setStepLfo0Rate(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_LFO_0_RATE, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_LFO_0_DEPTH, s))
        br.setStepLfo0Depth(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_LFO_0_DEPTH, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_LFO_1_RATE, s))
        br.setStepLfo1Rate(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_LFO_1_RATE, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_LFO_1_DEPTH, s))
        br.setStepLfo1Depth(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_LFO_1_DEPTH, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_LFO_2_RATE, s))
        br.setStepLfo2Rate(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_LFO_2_RATE, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_LFO_2_DEPTH, s))
        br.setStepLfo2Depth(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_LFO_2_DEPTH, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_LFO_3_RATE, s))
        br.setStepLfo3Rate(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_LFO_3_RATE, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_LFO_3_DEPTH, s))
        br.setStepLfo3Depth(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_LFO_3_DEPTH, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_ARP_RATE, s))
        br.setStepArpRate(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_ARP_RATE, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_ARP_GATE, s))
        br.setStepArpGate(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_ARP_GATE, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_FM_AMOUNT, s))
        br.setStepFmAmount(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_FM_AMOUNT, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_FM_RATIO, s))
        br.setStepFmRatio(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_FM_RATIO, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_MOD_FX_FEEDBACK, s))
        br.setStepModFxFeedback(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_MOD_FX_FEEDBACK, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_COMP_ATTACK, s))
        br.setStepCompAttack(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_COMP_ATTACK, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_COMP_RELEASE, s))
        br.setStepCompRelease(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_COMP_RELEASE, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_PORTAMENTO, s))
        br.setStepPortamento(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_PORTAMENTO, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_STUTTER_RATE, s))
        br.setStepStutter(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_STUTTER_RATE, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_BITCRUSH, s))
        br.setStepBitcrush(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_BITCRUSH, s),
            clipIdx);
      if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_SAMPLE_RATE_RED, s))
        br.setStepSrr(
            engRow,
            s,
            clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_SAMPLE_RATE_RED, s),
            clipIdx);
    }
  }

  public org.chuck.deluge.midi.MidiService getMidiService() {
    return midiService;
  }

  public void pushModelToBridge() {
    computeEngineMapping();
    java.util.List<org.chuck.deluge.model.TrackModel> tracks = currentProject.getTracks();

    // Clear all engine rows first
    bridge.clearPattern();
    for (int i = 0; i < BridgeContract.TRACKS; i++) {
      bridge.setMute(i, false);
      bridge.setTrackLength(i, 16);
    }

    // Initialize both local bridge and VM global track types to -1 (unused)
    for (int i = 0; i < BridgeContract.TRACKS; i++) bridge.setTrackType(i, -1);

    org.chuck.core.ChuckArray trackTypeArr =
        (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_TYPE);
    if (trackTypeArr != null) {
      // Initialize all to -1 (unused)
      for (int i = 0; i < BridgeContract.TRACKS; i++) trackTypeArr.setInt(i, -1L);
    }

    for (int t = 0; t < tracks.size(); t++) {
      org.chuck.deluge.model.TrackModel track = tracks.get(t);
      int startRow = trackEngineStart[t];
      int voiceCount = trackVoiceCount[t];

      if (track instanceof org.chuck.deluge.model.KitTrackModel kit) {
        // Mark engine rows as type-0 (kit)
        for (int v = 0; v < voiceCount; v++) {
          bridge.setTrackType(startRow + v, 0);
          if (trackTypeArr != null) trackTypeArr.setInt(startRow + v, 0L);
        }

        // Push each drum: sample path, pitch, mute group, reverse, ADSR
        java.util.List<org.chuck.deluge.model.Drum> sounds = kit.getDrums();
        for (int v = 0; v < voiceCount; v++) {
          int engineRow = startRow + v;
          String path =
              v < sounds.size()
                  ? ((org.chuck.deluge.model.SoundDrum) sounds.get(sounds.size() - 1 - v))
                      .getSamplePath()
                  : "";
          vm.setGlobalString("g_sample_" + engineRow, path);
          bridge.setSamplePath(engineRow, path);

          // ── Zone (sample truncation) ──
          if (v < sounds.size()) {
            org.chuck.deluge.model.SoundDrum snd =
                (org.chuck.deluge.model.SoundDrum) sounds.get(sounds.size() - 1 - v);
            float[] range = org.chuck.deluge.BridgeContract.computeNormalizedRange(snd, path);
            if (range[0] > 0.0f || range[1] < 1.0f) {
              bridge.setSampleRange(engineRow, range[0], range[1]);
            }
          }

          if (v < sounds.size()) {
            org.chuck.deluge.model.SoundDrum snd =
                (org.chuck.deluge.model.SoundDrum) sounds.get(sounds.size() - 1 - v);
            try {
              ((org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_PITCH))
                  .setFloat(engineRow, snd.getPitchSemitones());
              ((org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_MUTE_GROUP))
                  .setInt(engineRow, (long) snd.getMuteGroup());
              ((org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_REVERSE))
                  .setInt(engineRow, snd.isReverse() ? 1L : 0L);
              org.chuck.deluge.model.EnvelopeModel adsr = snd.getAdsr();
              if (adsr != null) {
                ((org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_ATTACK))
                    .setFloat(engineRow, adsr.attack());
                ((org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_DECAY))
                    .setFloat(engineRow, adsr.decay());
                ((org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_SUSTAIN))
                    .setFloat(engineRow, adsr.sustain());
                ((org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_RELEASE))
                    .setFloat(engineRow, adsr.release());
              }

              // ── New kit sound fields ──
              bridge.setKitVolume(engineRow, snd.getVolume());
              bridge.setKitPan(engineRow, snd.getPan());
              bridge.setKitEqBass(engineRow, snd.getEqBass());
              bridge.setKitEqTreble(engineRow, snd.getEqTreble());
              bridge.setKitSidechain(engineRow, snd.getSidechainSend());
              bridge.setKitNoiseVol(engineRow, snd.getNoiseVolume());
              bridge.setKitStutterRate(engineRow, snd.getStutterRate());
              bridge.setKitSampleRateRed(engineRow, snd.getSampleRateReduction());
              bridge.setKitBitCrush(engineRow, snd.getBitCrush());
              bridge.setKitHpfFreq(engineRow, snd.getHpfFreq());
              bridge.setKitHpfRes(engineRow, snd.getHpfRes());
              bridge.setKitHpfMorph(engineRow, snd.getHpfMorph());
              bridge.setKitHpfMode(engineRow, snd.getHpfMode().ordinal());
              bridge.setKitHpfFm(engineRow, snd.getHpfFm());
              bridge.setKitLpfMode(engineRow, lpfModeOrdinal(snd.getLpfMode()));
              bridge.setKitLpfMorph(engineRow, snd.getLpfMorph());
              bridge.setKitModFxType(engineRow, modFxTypeOrdinal(snd.getModFxType()));
              bridge.setKitModFxRate(engineRow, snd.getModFxRate());
              bridge.setKitModFxDepth(engineRow, snd.getModFxDepth());
              bridge.setKitModFxOffset(engineRow, snd.getModFxOffset());
              bridge.setKitModFxFeedback(engineRow, snd.getModFxFeedback());
              bridge.setKitOsc2Type(engineRow, oscTypeOrdinal(snd.getOsc2Type()));
              bridge.setKitUnisonNum(engineRow, snd.getUnisonNum());
              bridge.setKitUnisonDetune(engineRow, snd.getUnisonDetune());
              bridge.setKitUnisonSpread(engineRow, snd.getUnisonStereoSpread());
              bridge.setKitWaveIndex(engineRow, snd.getWaveIndex());
              bridge.setKitCompAttack(engineRow, snd.getCompressorAttack());
              bridge.setKitCompRelease(engineRow, snd.getCompressorRelease());
              bridge.setKitCompBlend(engineRow, snd.getCompressorBlend());
              bridge.setKitCompSidechainHpf(engineRow, snd.getCompressorSidechainHpf());
              bridge.setKitDelayRate(engineRow, snd.getDelayRate());
              bridge.setKitLpfDrive(engineRow, snd.getLpfDrive());
              bridge.setKitLpfNotch(engineRow, snd.isLpfNotch() ? 1 : 0);
              bridge.setKitMaxVoices(engineRow, snd.getMaxVoiceCount());
              bridge.setKitPolyphony(engineRow, snd.getPolyphony().ordinal());

              // ── Push kit FX arrays (delay pingpong/analog, reverb, comp, sidechain) ──
              bridge.setKitDelayPingpong(engineRow, snd.getDelayPingPong());
              bridge.setKitDelayAnalog(engineRow, snd.getDelayAnalog());
              bridge.setKitReverbAmount(engineRow, snd.getReverbAmount());
              bridge.setKitCompThreshold(engineRow, snd.getCompressorThreshold());
              bridge.setKitCompSyncLevel(engineRow, snd.getCompressorSyncLevel());
              bridge.setKitSidechainSyncLevel(engineRow, snd.getSidechainSyncLevel());
              bridge.setKitSidechainSyncType(engineRow, snd.getSidechainSyncType());
              bridge.setKitSidechainAttack(engineRow, snd.getSidechainAttack());
              bridge.setKitSidechainRelease(engineRow, snd.getSidechainRelease());

              // ── Push kit patch cables ──
              bridge.setKitPatchCables(engineRow, snd.getPatchCables());

              // ── Kit envelopes 2-4 via shared env array ──
              pushKitEnv(engineRow, 1, snd.getEnv2());
              pushKitEnv(engineRow, 2, snd.getEnv3());
              pushKitEnv(engineRow, 3, snd.getEnv4());

              // ── Kit LFOs: distribute per-sound LFO1/2 across global LFO slots ──
              int lfoBase = Math.min(v * 2, BridgeContract.LFO_COUNT - 2);
              LfoModel lfo1 = snd.getLfo1();
              if (lfo1 != null && lfoBase < BridgeContract.LFO_COUNT) {
                bridge.setLfo(
                    lfoBase,
                    lfo1.rateHz(),
                    lfo1.waveform().ordinal(),
                    lfo1.depth(),
                    lfo1.syncLevel());
              }
              LfoModel lfo2 = snd.getLfo2();
              if (lfo2 != null && lfoBase + 1 < BridgeContract.LFO_COUNT) {
                bridge.setLfo(
                    lfoBase + 1,
                    lfo2.rateHz(),
                    lfo2.waveform().ordinal(),
                    lfo2.depth(),
                    lfo2.syncLevel());
              }
            } catch (Exception ex) {
              System.err.println(
                  "[pushModel] kit param error at row " + engineRow + ": " + ex.getMessage());
            }
          }
        }

        // Push clip pattern data for the active clip
        int activeClipIdx = kit.getActiveClipIndex();
        if (activeClipIdx >= 0 && activeClipIdx < kit.getClips().size()) {
          org.chuck.deluge.model.ClipModel clip = kit.getClips().get(activeClipIdx);
          int stepCount = clip.getStepCount();
          for (int r = 0; r < clip.getRowCount(); r++) {
            for (int s = 0; s < stepCount; s++) {
              org.chuck.deluge.model.StepData step = clip.getStep(r, s);
              if (step != null && step.active() && r < voiceCount) {
                bridge.setStep(startRow + r, s, true);
                bridge.setVelocity(startRow + r, s, step.velocity());
                bridge.setIterance(startRow + r, s, step.iterance());
                bridge.setStepFill(startRow + r, s, step.fill());
              }
            }
          }
        }
      } else if (track instanceof org.chuck.deluge.model.SynthTrackModel synth) {
        // Mark all voice rows as type-1 (synth) — includes extended clip rows
        int activeClipIdx = synth.getActiveClipIndex();
        int totalSynthRows = voiceCount;
        if (activeClipIdx >= 0 && activeClipIdx < synth.getClips().size()) {
          totalSynthRows =
              Math.max(totalSynthRows, synth.getClips().get(activeClipIdx).getRowCount());
        }
        for (int v = 0; v < totalSynthRows; v++) {
          bridge.setTrackType(startRow + v, 1);
          if (trackTypeArr != null) trackTypeArr.setInt(startRow + v, 1L);
        }

        // Push clip data — each visual row writes to its own engine row.
        // Extended rows (>8) get unique bridge indices; engine maps them back via
        // synthBase + (r - synthBase) % VOICES_PER_SYNTH for UGen access.
        activeClipIdx = synth.getActiveClipIndex();
        if (activeClipIdx >= 0 && activeClipIdx < synth.getClips().size()) {
          org.chuck.deluge.model.ClipModel clip = synth.getClips().get(activeClipIdx);
          int stepCount = clip.getStepCount();
          for (int r = 0; r < clip.getRowCount(); r++) {
            int engineRow = startRow + r;
            for (int s = 0; s < stepCount; s++) {
              org.chuck.deluge.model.StepData step = clip.getStep(r, s);
              if (step != null && step.active()) {
                bridge.setStep(engineRow, s, true);
                bridge.setVelocity(engineRow, s, step.velocity());
                bridge.setGate(engineRow, s, step.gate());
                bridge.setPitch(engineRow, s, step.pitch());
                bridge.setIterance(engineRow, s, step.iterance());
                bridge.setStepFill(engineRow, s, step.fill());
              }
            }
          }
        }

        // Push oscType (0=SINE, 1=SAW, 2=SQUARE, 3=TRIANGLE, 4=NOISE) to ALL rows
        org.chuck.core.ChuckArray oscTypeArr =
            (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_OSC_TYPE);
        if (oscTypeArr != null) {
          int typeIdx = 1; // default Saw
          String ot = synth.getOsc1Type();
          if ("SINE".equals(ot)) typeIdx = 0;
          else if ("SQUARE".equals(ot)) typeIdx = 2;
          else if ("TRIANGLE".equals(ot)) typeIdx = 3;
          for (int v = 0; v < totalSynthRows; v++) {
            oscTypeArr.setInt(startRow + v, typeIdx);
          }
        }

        // Push filter params to ALL rows
        for (int v = 0; v < totalSynthRows; v++) {
          bridge.setFilterFreq(startRow + v, synth.getLpfFreq() / 20000.0f);
          bridge.setFilterRes(startRow + v, synth.getLpfRes() / 100.0f);
          bridge.setFilterMode(startRow + v, synth.getFilterMode().ordinal());
          bridge.setFilterMorph(startRow + v, synth.getLpfMorph());
          bridge.setFilterDrive(startRow + v, synth.getFilterDrive());
          bridge.setFilterNotch(startRow + v, synth.isFilterNotch() ? 1 : 0);
          bridge.setFilterRoute(startRow + v, synth.getFilterRoute());
          bridge.setMaxVoices(startRow + v, synth.getMaxVoiceCount());
        }

        // Push ADSR envelopes (4 envs) to ALL rows of this track
        for (int e = 0; e < 4; e++) {
          org.chuck.deluge.model.EnvelopeModel adsr = synth.getEnv(e);
          if (adsr != null) {
            for (int v = 0; v < totalSynthRows; v++) {
              bridge.setEnv(
                  startRow + v, e, adsr.attack(), adsr.decay(), adsr.sustain(), adsr.release());
            }
          }
        }

        // Push LFO params (4 LFOs) — global per track, shared by all rows
        org.chuck.core.ChuckArray lfoRateArr =
            (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_RATE);
        org.chuck.core.ChuckArray lfoTypeArr =
            (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_TYPE);
        org.chuck.core.ChuckArray lfoDepthArr =
            (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_DEPTH);
        org.chuck.core.ChuckArray lfoSyncArr =
            (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_SYNC_LEVEL);
        for (int l = 0; l < 4; l++) {
          org.chuck.deluge.model.LfoModel lfo = synth.getLfo(l);
          if (lfo != null) {
            if (lfoRateArr != null) lfoRateArr.setFloat(l, lfo.rateHz());
            if (lfoTypeArr != null) lfoTypeArr.setInt(l, (long) lfo.waveform().ordinal());
            if (lfoDepthArr != null) lfoDepthArr.setFloat(l, lfo.depth());
            if (lfoSyncArr != null) lfoSyncArr.setInt(l, (long) lfo.syncLevel());
          }
        }

        // Push arp params to ALL rows
        org.chuck.deluge.model.ArpModel arp = synth.getArp();
        if (arp != null) {
          int arpMode =
              switch (arp.mode()) {
                case "DOWN" -> 1;
                case "UP_DOWN" -> 2;
                case "RANDOM" -> 3;
                case "WALK" -> 4;
                default -> 0; // UP
              };
          int arpNoteMode =
              switch (arp.noteMode()) {
                case "DOWN" -> 1;
                case "UPDN" -> 2;
                case "RAND" -> 3;
                case "WLK1" -> 4;
                case "WLK2" -> 5;
                case "WLK3" -> 6;
                case "PLAY" -> 7;
                case "PATT" -> 8;
                default -> 0; // UP
              };
          int arpOctaveMode =
              switch (arp.octaveMode()) {
                case "DOWN" -> 1;
                case "UPDN" -> 2;
                case "ALT" -> 3;
                case "RAND" -> 4;
                default -> 0; // UP
              };
          for (int v = 0; v < totalSynthRows; v++) {
            bridge.setArpOn(startRow + v, arp.active());
            bridge.setArpRate(startRow + v, arp.rate());
            bridge.setArpOctave(startRow + v, arp.octaves());
            bridge.setArpMode(startRow + v, arpMode);
            bridge.setArpGate(startRow + v, arp.gate());
            bridge.setArpSyncLevel(startRow + v, arp.syncLevel());
            bridge.setArpNoteMode(startRow + v, arpNoteMode);
            bridge.setArpOctaveMode(startRow + v, arpOctaveMode);
            bridge.setArpStepRepeat(startRow + v, arp.stepRepeat());
            bridge.setArpRhythm(startRow + v, arp.rhythmIndex());
            bridge.setArpSeqLength(startRow + v, arp.seqLength());
            bridge.setArpOctaveSpread(startRow + v, arp.octaveSpread());
            bridge.setArpGateSpread(startRow + v, arp.gateSpread());
            bridge.setArpVelSpread(startRow + v, arp.velSpread());
            bridge.setArpRatchet(startRow + v, arp.ratchetAmount());
            bridge.setArpNoteProbability(startRow + v, arp.noteProbability());
            bridge.setArpChordPoly(startRow + v, arp.chordPolyphony());
            bridge.setArpChordProb(startRow + v, arp.chordProbability());
          }
        }

        // Push synth algorithm to ALL rows
        for (int v = 0; v < totalSynthRows; v++) {
          bridge.setSynthAlgo(startRow + v, synth.getSynthAlgorithm());
        }

        // Push synth mode, FM params, HPF, polyphony to ALL rows
        for (int v = 0; v < totalSynthRows; v++) {
          bridge.setSynthMode(startRow + v, synth.getSynthMode());
          bridge.setFmRatio(startRow + v, synth.getFmRatio());
          bridge.setFmAmount(startRow + v, synth.getFmAmount());
          bridge.setMod1Fb(startRow + v, synth.getModulator1Feedback());
          bridge.setMod2Amt(startRow + v, synth.getModulator2Amount());
          bridge.setMod2Fb(startRow + v, synth.getModulator2Feedback());
          bridge.setCarrier1Fb(startRow + v, synth.getCarrier1Feedback());
          bridge.setCarrier2Fb(startRow + v, synth.getCarrier2Feedback());
          bridge.setHpfFreq(startRow + v, synth.getHpfFreq());
          bridge.setHpfRes(startRow + v, synth.getHpfRes());
          bridge.setHpfMorph(startRow + v, synth.getHpfMorph());
          bridge.setHpfMode(startRow + v, synth.getHpfMode().ordinal());
          bridge.setHpfFm(startRow + v, synth.getHpfFm());
          bridge.setPolyphony(startRow + v, synth.getPolyphony().ordinal());
        }

        // ── Push new synth fields (volume, pan, oscMix, noise, unison, modFX, etc.) ──
        for (int v = 0; v < totalSynthRows; v++) {
          bridge.setTrackLevel(startRow + v, synth.getVolume());
        }
        for (int v = 0; v < totalSynthRows; v++) {
          bridge.setTrackPan(startRow + v, synth.getPan());
        }
        for (int v = 0; v < totalSynthRows; v++) {
          bridge.setOscMix(startRow + v, synth.getOscMix());
        }
        for (int v = 0; v < totalSynthRows; v++) {
          bridge.setNoiseVol(startRow + v, synth.getNoiseVol());
        }
        for (int v = 0; v < totalSynthRows; v++) {
          bridge.setUnisonNum(startRow + v, synth.getUnisonNum());
          bridge.setUnisonDetune(startRow + v, synth.getUnisonDetune());
          bridge.setUnisonSpread(startRow + v, synth.getUnisonStereoSpread());
        }
        for (int v = 0; v < totalSynthRows; v++) {
          bridge.setModFxType(startRow + v, modFxTypeOrdinal(synth.getModFxType()));
          bridge.setModFxRate(startRow + v, synth.getModFxRate());
          bridge.setModFxDepth(startRow + v, synth.getModFxDepth());
          bridge.setModFxFeedback(startRow + v, synth.getModFxFeedback());
          bridge.setModFxOffset(startRow + v, synth.getModFxOffset());
        }
        for (int v = 0; v < totalSynthRows; v++) {
          bridge.setPortamento(startRow + v, synth.getPortamento());
        }
        for (int v = 0; v < totalSynthRows; v++) {
          bridge.setEqBass(startRow + v, synth.getEqBass());
          bridge.setEqTreble(startRow + v, synth.getEqTreble());
        }
        for (int v = 0; v < totalSynthRows; v++) {
          bridge.setStutterRate(startRow + v, synth.getStutterRate());
          bridge.setSampleRateReduction(startRow + v, synth.getSampleRateReduction());
          bridge.setBitCrush(startRow + v, synth.getBitCrush());
        }
        for (int v = 0; v < totalSynthRows; v++) {
          bridge.setCompAttack(startRow + v, synth.getCompressorAttack());
          bridge.setCompRelease(startRow + v, synth.getCompressorRelease());
          bridge.setCompBlend(startRow + v, synth.getCompressorBlend());
          bridge.setCompSidechainHpf(startRow + v, synth.getCompressorSidechainHpf());
        }
        for (int v = 0; v < totalSynthRows; v++) {
          bridge.setOsc2Type(startRow + v, oscTypeOrdinal(synth.getOsc2Type()));
        }
        for (int v = 0; v < totalSynthRows; v++) {
          bridge.setRetrigPhase(startRow + v, synth.getRetrigPhase());
        }
        for (int v = 0; v < totalSynthRows; v++) {
          bridge.setWaveIndex(startRow + v, synth.getWaveIndex());
        }
        for (int v = 0; v < totalSynthRows; v++) {
          bridge.setDelaySend(startRow + v, synth.getDelaySend());
          bridge.setReverbSend(startRow + v, synth.getReverbSend());
        }

        // ── Push patch cables ──
        bridge.setSynthPatchCables(startRow, synth.getPatchCables());

        // ── DX7 engine type (−1=AUTO, 0=MODERN, 1=VINTAGE) ──
        bridge.setEngineType(startRow, synth.getEngineType());

        // ── DX7 patch (per-row string global, read by engine) ──
        String dx7patch = synth.getDx7Patch();
        if (dx7patch != null && !dx7patch.isEmpty()) {
          for (int v = 0; v < totalSynthRows; v++) {
            vm.setGlobalString("g_dx7_patch_" + (startRow + v), dx7patch);
          }
          // Push opSwitch mask (byte 155) so UI edits to operator on/off are reflected
          try {
            byte[] raw = org.chuck.audio.util.Dx7Patch.hexToBytes(dx7patch);
            int opSwitch = raw[org.chuck.audio.util.Dx7Patch.OFF_OP_SWITCH] & 0xFF;
            for (int v = 0; v < totalSynthRows; v++) {
              vm.setGlobalInt("g_dx7_opSwitch_" + (startRow + v), opSwitch);
            }
          } catch (Exception ignored) {
          }
        }
      } else if (track instanceof org.chuck.deluge.model.AudioTrackModel audio) {
        // Mark engine row as type-2 (audio)
        bridge.setTrackType(startRow, 2);
        if (trackTypeArr != null) trackTypeArr.setInt(startRow, 2L);
        // Push audio threshold params
        bridge.setAudioThreshold(startRow, audio.getThresholdMode());
        bridge.setAudioThresholdLevel(startRow, audio.getThresholdLevel());
        // Push clip pattern data
        int activeClipIdx = audio.getActiveClipIndex();
        if (activeClipIdx >= 0 && activeClipIdx < audio.getClips().size()) {
          org.chuck.deluge.model.ClipModel clip = audio.getClips().get(activeClipIdx);
          int stepCount = clip.getStepCount();
          for (int r = 0; r < clip.getRowCount() && r < voiceCount; r++) {
            for (int s = 0; s < stepCount; s++) {
              org.chuck.deluge.model.StepData step = clip.getStep(r, s);
              if (step != null && step.active()) {
                bridge.setStep(startRow + r, s, true);
                bridge.setVelocity(startRow + r, s, step.velocity());
              }
            }
          }
        }
        // ── Push active AudioClip state to engine ──
        int aClipIdx = audio.getActiveClipIndex();
        if (aClipIdx >= 0 && aClipIdx < audio.getAudioClips().size()) {
          org.chuck.deluge.model.AudioTrackModel.AudioClip aClip =
              audio.getAudioClips().get(aClipIdx);
          bridge.setAudioPlay(startRow, aClip.isPlaying() ? 1 : 0);
          bridge.setAudioLoop(startRow, aClip.isPlaying() || audio.isLooping() ? 1 : 0);
          bridge.setAudioRate(startRow, audio.getPlayRate());
          // Push sample position globals for LiSa playback region
          vm.setGlobalFloat("g_audio_clip_start_" + startRow, (float) aClip.getStartSamplePos());
          vm.setGlobalFloat("g_audio_clip_end_" + startRow, (float) aClip.getEndSamplePos());
          // Push audio file path for LiSa sample loading
          String filePath = aClip.getFilePath();
          if (filePath != null && !filePath.isEmpty()) {
            vm.setGlobalString("g_audio_file_path_" + startRow, filePath);
          }
        }
      }

      // Track length and stepCount for all rows of this track
      int rowLen = track.getClips().isEmpty() ? 16 : track.getClips().get(0).getStepCount();
      int totalRows = voiceCount;
      if (track instanceof org.chuck.deluge.model.SynthTrackModel synthTrackModel) {
        int acIdx = synthTrackModel.getActiveClipIndex();
        var clips = synthTrackModel.getClips();
        if (acIdx >= 0 && acIdx < clips.size()) {
          totalRows = Math.max(voiceCount, clips.get(acIdx).getRowCount());
        }
      }
      for (int v = 0; v < totalRows; v++) {
        bridge.setTrackLength(startRow + v, rowLen);
      }
    }

    // ── Push ALL clips per track to clip-indexed C{n} bridge arrays ──
    for (int t = 0; t < tracks.size(); t++) {
      org.chuck.deluge.model.TrackModel track = tracks.get(t);
      int startRow = trackEngineStart[t];
      int voiceCount = trackVoiceCount[t];
      java.util.List<ClipModel> clips = track.getClips();

      bridge.setClipCount(t, clips.size());
      bridge.setCurrentClip(t, track.getActiveClipIndex());

      for (int c = 0; c < clips.size() && c < BridgeContract.MAX_CLIPS_PER_TRACK; c++) {
        ClipModel clip = clips.get(c);
        int stepCount = clip.getStepCount();
        for (int r = 0; r < clip.getRowCount(); r++) {
          int engineRow = startRow + r;
          for (int s = 0; s < stepCount; s++) {
            org.chuck.deluge.model.StepData step = clip.getStep(r, s);
            if (step != null && step.active() && r < voiceCount) {
              bridge.setStep(engineRow, s, true, c);
              bridge.setVelocity(engineRow, s, step.velocity(), c);
              bridge.setPitch(engineRow, s, step.pitch());
            }
          }
        }
        // Push per-step automation for this clip to clip-indexed _C{n} arrays
        pushClipAutomation(t, bridge, clip, c, startRow);

        // Push per-clip play mode (0=NORMAL, 1=LOOP)
        bridge.setClipPlayMode(t, c, clip.getPlayMode().ordinal());
      }
    }

    // ── Push master-level globals (BPM, swing, volume, pan, reverb, delay, scale, key, comp) ──
    // These are normally propagated by ProjectListener callbacks, but during project load the
    // XML parser populates model fields directly without firing setters, so we sync here too.
    vm.setGlobalFloat(BridgeContract.G_BPM, currentProject.getBpm());
    vm.setGlobalFloat(BridgeContract.G_SWING, currentProject.getSwing());
    vm.setGlobalFloat(BridgeContract.G_MASTER_VOL, currentProject.getMasterVolume());
    vm.setGlobalFloat(BridgeContract.G_MASTER_PAN, currentProject.getMasterPan());
    vm.setGlobalFloat(BridgeContract.G_DELAY_TIME, currentProject.getMasterDelay());
    vm.setGlobalFloat(BridgeContract.G_DELAY_FB, currentProject.getSongParamDelayFeedback());
    vm.setGlobalFloat(BridgeContract.G_REVERB_ROOM, currentProject.getReverbRoomSize());
    vm.setGlobalFloat(BridgeContract.G_REVERB_DAMP, currentProject.getReverbDampening());
    vm.setGlobalFloat(BridgeContract.G_MASTER_COMP, currentProject.getCompressorThreshold());
    vm.setGlobalInt(BridgeContract.G_ROOT_KEY, parseRootKey(currentProject.getKey()));
    vm.setGlobalInt(BridgeContract.G_SCALE, parseScaleIndex(currentProject.getScale()));
    vm.setGlobalFloat(BridgeContract.G_PREVIEW_PITCH, 60.0f);

    // ── Extended reverb globals ──
    vm.setGlobalFloat(BridgeContract.G_REVERB_WIDTH, currentProject.getReverbWidth());
    vm.setGlobalFloat(BridgeContract.G_REVERB_HPF, currentProject.getReverbHpf());
    vm.setGlobalFloat(BridgeContract.G_REVERB_PAN, currentProject.getReverbPan());
    vm.setGlobalInt(BridgeContract.G_REVERB_MODEL, currentProject.getReverbModel());
    vm.setGlobalFloat(
        BridgeContract.G_REVERB_COMP_ATTACK, currentProject.getReverbCompressorAttack());
    vm.setGlobalFloat(
        BridgeContract.G_REVERB_COMP_RELEASE, currentProject.getReverbCompressorRelease());
    vm.setGlobalInt(
        BridgeContract.G_REVERB_COMP_SYNC_LEVEL, currentProject.getReverbCompressorSyncLevel());
    vm.setGlobalFloat(BridgeContract.G_REVERB_COMP_HPF, currentProject.getReverbCompHpf());
    vm.setGlobalFloat(BridgeContract.G_REVERB_COMP_BLEND, currentProject.getReverbCompBlend());

    // ── Extended delay globals ──
    vm.setGlobalInt(BridgeContract.G_DELAY_PINGPONG, currentProject.getDelayPingPong());
    vm.setGlobalInt(BridgeContract.G_DELAY_ANALOG, currentProject.getDelayAnalog());
    vm.setGlobalInt(BridgeContract.G_DELAY_SYNC_LEVEL, currentProject.getDelaySyncLevel());
    vm.setGlobalInt(BridgeContract.G_DELAY_SYNC_TYPE, currentProject.getDelaySyncType());

    // ── Sidechain globals ──
    vm.setGlobalFloat(BridgeContract.G_SIDECHAIN_ATTACK, currentProject.getSidechainAttack());
    vm.setGlobalFloat(BridgeContract.G_SIDECHAIN_RELEASE, currentProject.getSidechainRelease());
    vm.setGlobalInt(BridgeContract.G_SIDECHAIN_SYNC_LEVEL, currentProject.getSidechainSyncLevel());
    vm.setGlobalInt(BridgeContract.G_SIDECHAIN_SYNC_TYPE, currentProject.getSidechainSyncType());

    // ── Master compressor (extended) globals ──
    vm.setGlobalFloat(BridgeContract.G_MASTER_COMP_ATTACK, currentProject.getCompressorAttack());
    vm.setGlobalFloat(BridgeContract.G_MASTER_COMP_RELEASE, currentProject.getCompressorRelease());
    vm.setGlobalFloat(BridgeContract.G_MASTER_COMP_RATIO, currentProject.getCompressorRatio());
    vm.setGlobalFloat(BridgeContract.G_MASTER_COMP_BLEND, currentProject.getCompressorBlend());

    // ── Transpose / humanize globals ──
    vm.setGlobalInt(BridgeContract.G_TRANSPOSE, currentProject.getTranspose());
    vm.setGlobalFloat(BridgeContract.G_HUMANIZE, currentProject.getHumanize());

    // ── SongParams globals ──
    vm.setGlobalFloat(BridgeContract.G_SP_VOLUME, currentProject.getSongParamVolume());
    vm.setGlobalFloat(BridgeContract.G_SP_PAN, currentProject.getSongParamPan());
    vm.setGlobalFloat(BridgeContract.G_SP_REVERB_AMOUNT, currentProject.getSongParamReverbAmount());
    vm.setGlobalFloat(BridgeContract.G_SP_DELAY_RATE, currentProject.getSongParamDelayRate());
    vm.setGlobalFloat(
        BridgeContract.G_SP_DELAY_FEEDBACK, currentProject.getSongParamDelayFeedback());
    vm.setGlobalFloat(
        BridgeContract.G_SP_SIDECHAIN_SHAPE, currentProject.getSongParamSidechainShape());
    vm.setGlobalFloat(BridgeContract.G_SP_STUTTER_RATE, currentProject.getSongParamStutterRate());
    vm.setGlobalFloat(
        BridgeContract.G_SP_SAMPLE_RATE_REDUCTION,
        currentProject.getSongParamSampleRateReduction());
    vm.setGlobalFloat(BridgeContract.G_SP_BITCRUSH, currentProject.getSongParamBitCrush());
    vm.setGlobalFloat(BridgeContract.G_SP_MOD_FX_RATE, currentProject.getSongParamModFXRate());
    vm.setGlobalFloat(BridgeContract.G_SP_MOD_FX_DEPTH, currentProject.getSongParamModFXDepth());
    vm.setGlobalFloat(BridgeContract.G_SP_MOD_FX_OFFSET, currentProject.getSongParamModFXOffset());
    vm.setGlobalFloat(
        BridgeContract.G_SP_MOD_FX_FEEDBACK, currentProject.getSongParamModFXFeedback());
    vm.setGlobalFloat(
        BridgeContract.G_SP_COMPRESSOR_THRESHOLD, currentProject.getSongParamCompressorThreshold());
    vm.setGlobalFloat(BridgeContract.G_SP_LPF_MORPH, currentProject.getSongParamLpfMorph());
    vm.setGlobalFloat(BridgeContract.G_SP_HPF_MORPH, currentProject.getSongParamHpfMorph());
    vm.setGlobalFloat(BridgeContract.G_SP_LPF_FREQ, currentProject.getSongParamLpfFrequency());
    vm.setGlobalFloat(BridgeContract.G_SP_LPF_RES, currentProject.getSongParamLpfResonance());
    vm.setGlobalFloat(BridgeContract.G_SP_HPF_FREQ, currentProject.getSongParamHpfFrequency());
    vm.setGlobalFloat(BridgeContract.G_SP_HPF_RES, currentProject.getSongParamHpfResonance());
    vm.setGlobalFloat(BridgeContract.G_SP_EQ_BASS, currentProject.getSongParamEqBass());
    vm.setGlobalFloat(BridgeContract.G_SP_EQ_TREBLE, currentProject.getSongParamEqTreble());
    vm.setGlobalFloat(
        BridgeContract.G_SP_EQ_BASS_FREQ, currentProject.getSongParamEqBassFrequency());
    vm.setGlobalFloat(
        BridgeContract.G_SP_EQ_TREBLE_FREQ, currentProject.getSongParamEqTrebleFrequency());

    // ── Scales globals ──
    vm.setGlobalInt(BridgeContract.G_USER_SCALE, currentProject.getUserScale());
    vm.setGlobalInt(
        BridgeContract.G_DISABLED_PRESET_SCALES, currentProject.getDisabledPresetScales());
    boolean[] modeNotes = currentProject.getModeNotes();
    if (modeNotes != null) {
      for (int i = 0; i < 12 && i < modeNotes.length; i++) {
        vm.setGlobalInt(BridgeContract.G_MODE_NOTES + "_" + i, modeNotes[i] ? 1L : 0L);
      }
    }

    // ── MIDI Follow Mode globals ──
    bridge.setFollowEnabled(midiService != null && midiService.isRecording());
    vm.setGlobalInt(BridgeContract.G_FOLLOW_CH_A, bridge.getFollowMidChannel('A'));
    vm.setGlobalInt(BridgeContract.G_FOLLOW_CH_B, bridge.getFollowMidChannel('B'));
    vm.setGlobalInt(BridgeContract.G_FOLLOW_CH_C, bridge.getFollowMidChannel('C'));
    vm.setGlobalInt(BridgeContract.G_FOLLOW_TRACK_A, bridge.getFollowTrack('A'));
    vm.setGlobalInt(BridgeContract.G_FOLLOW_TRACK_B, bridge.getFollowTrack('B'));
    vm.setGlobalInt(BridgeContract.G_FOLLOW_TRACK_C, bridge.getFollowTrack('C'));

    // Sync the master FX panel slider with the model's volume
    if (masterFxPanel != null) {
      masterFxPanel.setMasterVol(Math.round(currentProject.getMasterVolume() * 100));
      masterFxPanel.updateCompressorUI(
          currentProject.getCompressorThreshold(),
          currentProject.getCompressorAttack(),
          currentProject.getCompressorRelease(),
          currentProject.getCompressorRatio(),
          currentProject.getCompressorBlend());
    }

    // ── Apply GlobalEffectable clip FX overrides for each track's active clip ──
    // This overrides song-level G_SP_* globals with per-clip kitParams where present.
    for (int t = 0; t < tracks.size(); t++) {
      org.chuck.deluge.model.TrackModel track = tracks.get(t);
      int clipIdx = track.getActiveClipIndex();
      // Audio tracks use AudioClips (not ClipModel), so check audioClips instead
      if (track instanceof org.chuck.deluge.model.AudioTrackModel audioTrk) {
        if (clipIdx >= 0 && clipIdx < audioTrk.getAudioClips().size()) {
          applyClipFxOverrides(t, clipIdx);
        }
      } else if (clipIdx >= 0 && clipIdx < track.getClips().size()) {
        applyClipFxOverrides(t, clipIdx);
      }
    }

    // Signal engine shreds to re-allocate their UGen arrays (track add/remove)
    // NOTE: do NOT set G_RELOAD here — the initial advance(loadEvent) in engine sub-shreds
    // already triggers one doInit(). Setting G_RELOAD would cause a second, wasteful re-init.
    if (!tracks.isEmpty()) {
      vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    }

    // ── Push hardware character preferences to engine globals ──
    vm.setGlobalFloat(
        BridgeContract.G_MASTER_SATURATION,
        org.chuck.deluge.project.PreferencesManager.isMasterSaturationEnabled() ? 1.0f : 0.0f);
    vm.setGlobalFloat(
        BridgeContract.G_CHAR_FILTER_DRIVE,
        org.chuck.deluge.project.PreferencesManager.isFilterDriveEnabled() ? 1.0f : 0.0f);
    vm.setGlobalFloat(
        BridgeContract.G_BIT_CRUNCH,
        org.chuck.deluge.project.PreferencesManager.isBitCrunchEnabled() ? 1.0f : 0.0f);

    // Reverb model is already pushed via PreferencesManager.get("reverb.model") in loadProject —
    // skip here
  }

  /**
   * Override song-level FX parameters (G_SP_* globals) with the active clip's per-clip FX params.
   *
   * <p>For synth/kit tracks, reads {@link ClipModel#getKitParams()} (a {@code Map<String, Float>}).
   * For audio tracks, reads typed fields from {@link
   * org.chuck.deluge.model.AudioTrackModel.AudioClip} getters. If the clip has no override for a
   * given parameter, the song-level default is preserved.
   *
   * <p>This implements the real Deluge firmware's GlobalEffectable pattern where InstrumentClip can
   * override Song-level FX parameters.
   */
  private void applyClipFxOverrides(int track, int clipIdx) {
    java.util.List<org.chuck.deluge.model.TrackModel> tracks = currentProject.getTracks();
    if (track < 0 || track >= tracks.size()) return;
    org.chuck.deluge.model.TrackModel trk = tracks.get(track);
    if (clipIdx < 0) return;

    // ── Audio tracks: read typed fields from AudioClip ──
    if (trk instanceof org.chuck.deluge.model.AudioTrackModel audioTrk) {
      if (clipIdx >= audioTrk.getAudioClips().size()) return;
      org.chuck.deluge.model.AudioTrackModel.AudioClip aClip =
          audioTrk.getAudioClips().get(clipIdx);
      vm.setGlobalFloat(BridgeContract.G_SP_VOLUME, aClip.getVolume());
      vm.setGlobalFloat(BridgeContract.G_SP_PAN, aClip.getPan());
      vm.setGlobalFloat(BridgeContract.G_SP_REVERB_AMOUNT, aClip.getReverbAmount());
      vm.setGlobalFloat(BridgeContract.G_SP_DELAY_RATE, aClip.getDelayRate());
      vm.setGlobalFloat(BridgeContract.G_SP_DELAY_FEEDBACK, aClip.getDelayFeedback());
      vm.setGlobalFloat(BridgeContract.G_SP_SIDECHAIN_SHAPE, aClip.getSidechainShape());
      vm.setGlobalFloat(BridgeContract.G_SP_MOD_FX_RATE, aClip.getModFXRate());
      vm.setGlobalFloat(BridgeContract.G_SP_MOD_FX_DEPTH, aClip.getModFXDepth());
      vm.setGlobalFloat(BridgeContract.G_SP_MOD_FX_OFFSET, aClip.getModFXOffset());
      vm.setGlobalFloat(BridgeContract.G_SP_MOD_FX_FEEDBACK, aClip.getModFXFeedback());
      vm.setGlobalFloat(BridgeContract.G_SP_STUTTER_RATE, aClip.getStutterRate());
      vm.setGlobalFloat(BridgeContract.G_SP_SAMPLE_RATE_REDUCTION, aClip.getSampleRateReduction());
      vm.setGlobalFloat(BridgeContract.G_SP_BITCRUSH, aClip.getBitCrush());
      vm.setGlobalFloat(BridgeContract.G_SP_LPF_FREQ, aClip.getLpfFrequency());
      vm.setGlobalFloat(BridgeContract.G_SP_LPF_RES, aClip.getLpfResonance());
      vm.setGlobalFloat(BridgeContract.G_SP_HPF_FREQ, aClip.getHpfFrequency());
      vm.setGlobalFloat(BridgeContract.G_SP_HPF_RES, aClip.getHpfResonance());
      vm.setGlobalFloat(BridgeContract.G_SP_EQ_BASS, aClip.getEqBass());
      vm.setGlobalFloat(BridgeContract.G_SP_EQ_TREBLE, aClip.getEqTreble());
      vm.setGlobalFloat(BridgeContract.G_SP_EQ_BASS_FREQ, aClip.getEqBassFrequency());
      vm.setGlobalFloat(BridgeContract.G_SP_EQ_TREBLE_FREQ, aClip.getEqTrebleFrequency());
      // AudioClip doesn't have its own compressorThreshold, lpfMorph, hpfMorph;
      // keep song defaults for those (already set above in pushModelToBridge)
      return;
    }

    // ── Synth/kit tracks: read kitParams map ──
    if (clipIdx >= trk.getClips().size()) return;
    ClipModel clip = trk.getClips().get(clipIdx);
    java.util.Map<String, Float> kp = clip.getKitParams();
    if (kp == null || kp.isEmpty()) return;

    // For each G_SP_* global: clip override if present, otherwise song default (already set above)
    if (kp.containsKey("volume")) vm.setGlobalFloat(BridgeContract.G_SP_VOLUME, kp.get("volume"));
    if (kp.containsKey("pan")) vm.setGlobalFloat(BridgeContract.G_SP_PAN, kp.get("pan"));
    if (kp.containsKey("reverbAmount"))
      vm.setGlobalFloat(BridgeContract.G_SP_REVERB_AMOUNT, kp.get("reverbAmount"));
    if (kp.containsKey("delayRate"))
      vm.setGlobalFloat(BridgeContract.G_SP_DELAY_RATE, kp.get("delayRate"));
    if (kp.containsKey("delayFeedback"))
      vm.setGlobalFloat(BridgeContract.G_SP_DELAY_FEEDBACK, kp.get("delayFeedback"));
    if (kp.containsKey("sidechainCompressorShape"))
      vm.setGlobalFloat(BridgeContract.G_SP_SIDECHAIN_SHAPE, kp.get("sidechainCompressorShape"));
    if (kp.containsKey("stutterRate"))
      vm.setGlobalFloat(BridgeContract.G_SP_STUTTER_RATE, kp.get("stutterRate"));
    if (kp.containsKey("sampleRateReduction"))
      vm.setGlobalFloat(BridgeContract.G_SP_SAMPLE_RATE_REDUCTION, kp.get("sampleRateReduction"));
    if (kp.containsKey("bitCrush"))
      vm.setGlobalFloat(BridgeContract.G_SP_BITCRUSH, kp.get("bitCrush"));
    if (kp.containsKey("modFXRate"))
      vm.setGlobalFloat(BridgeContract.G_SP_MOD_FX_RATE, kp.get("modFXRate"));
    if (kp.containsKey("modFXDepth"))
      vm.setGlobalFloat(BridgeContract.G_SP_MOD_FX_DEPTH, kp.get("modFXDepth"));
    if (kp.containsKey("modFXOffset"))
      vm.setGlobalFloat(BridgeContract.G_SP_MOD_FX_OFFSET, kp.get("modFXOffset"));
    if (kp.containsKey("modFXFeedback"))
      vm.setGlobalFloat(BridgeContract.G_SP_MOD_FX_FEEDBACK, kp.get("modFXFeedback"));
    if (kp.containsKey("compressorThreshold"))
      vm.setGlobalFloat(BridgeContract.G_SP_COMPRESSOR_THRESHOLD, kp.get("compressorThreshold"));
    if (kp.containsKey("lpfMorph"))
      vm.setGlobalFloat(BridgeContract.G_SP_LPF_MORPH, kp.get("lpfMorph"));
    if (kp.containsKey("hpfMorph"))
      vm.setGlobalFloat(BridgeContract.G_SP_HPF_MORPH, kp.get("hpfMorph"));
    if (kp.containsKey("lpfFrequency"))
      vm.setGlobalFloat(BridgeContract.G_SP_LPF_FREQ, kp.get("lpfFrequency"));
    if (kp.containsKey("lpfResonance"))
      vm.setGlobalFloat(BridgeContract.G_SP_LPF_RES, kp.get("lpfResonance"));
    if (kp.containsKey("hpfFrequency"))
      vm.setGlobalFloat(BridgeContract.G_SP_HPF_FREQ, kp.get("hpfFrequency"));
    if (kp.containsKey("hpfResonance"))
      vm.setGlobalFloat(BridgeContract.G_SP_HPF_RES, kp.get("hpfResonance"));
    if (kp.containsKey("eqBass")) vm.setGlobalFloat(BridgeContract.G_SP_EQ_BASS, kp.get("eqBass"));
    if (kp.containsKey("eqTreble"))
      vm.setGlobalFloat(BridgeContract.G_SP_EQ_TREBLE, kp.get("eqTreble"));
    if (kp.containsKey("eqBassFrequency"))
      vm.setGlobalFloat(BridgeContract.G_SP_EQ_BASS_FREQ, kp.get("eqBassFrequency"));
    if (kp.containsKey("eqTrebleFrequency"))
      vm.setGlobalFloat(BridgeContract.G_SP_EQ_TREBLE_FREQ, kp.get("eqTrebleFrequency"));
  }

  private org.chuck.deluge.engine.PureFirmwareEngine pureEngine;

  public SwingDelugeApp(
      ChuckVM vm, BridgeContract bridge, org.chuck.deluge.midi.MidiService midiService) {
    this(vm, bridge, midiService, false);
  }

  public SwingDelugeApp(
      ChuckVM vm,
      BridgeContract bridge,
      org.chuck.deluge.midi.MidiService midiService,
      boolean pureMode) {
    this.vm = vm;
    this.bridge = bridge;
    this.midiService = midiService;
    mainInstance = this;
    pureModeActive = pureMode;

    if (pureMode) {
      System.out.println("[UI] Initializing Pure Java High-Fidelity Engine...");
      this.pureEngine = new org.chuck.deluge.engine.PureFirmwareEngine();
      this.pureEngine.start(vm);
      // Register in bridge for components that poll it
      vm.setGlobalObject(BridgeContract.G_FIRMWARE_ENGINE, pureEngine.getAudioEngine());
      vm.setGlobalObject(BridgeContract.G_PLAYBACK_HANDLER, pureEngine.getPlaybackHandler());
    }

    // Inflate Font Sizes globally (excluding menus to prevent layout bloat!)
    java.util.Enumeration<Object> keys = UIManager.getDefaults().keys();
    while (keys.hasMoreElements()) {
      Object key = keys.nextElement();
      String keyStr = key.toString();
      if (keyStr.contains("Menu") || keyStr.contains("MenuBar") || keyStr.contains("MenuItem")) {
        continue; // Keep all menu components standard and clean!
      }
      Object value = UIManager.get(key);
      if (value instanceof javax.swing.plaf.FontUIResource orig) {
        Font font =
            new Font(
                orig.getFontName(), orig.getStyle(), 13); // Clean, professional desktop font scale
        UIManager.put(key, new javax.swing.plaf.FontUIResource(font));
      }
    }

    setTitle("DELUGE WORKSTATION [SWING EDITION]");
    setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

    // ── Virtual Hardware Initialization ──
    Flasher.startGlobal();
    MatrixDriver.get().pushUI(new SessionView(null)); // Placeholder song

    setFocusable(true);
    addKeyListener(
        new java.awt.event.KeyAdapter() {
          @Override
          public void keyPressed(java.awt.event.KeyEvent e) {
            int note = -1;
            switch (e.getKeyCode()) {
              case java.awt.event.KeyEvent.VK_Z -> note = 60; // C4
              case java.awt.event.KeyEvent.VK_S -> note = 61; // C#4
              case java.awt.event.KeyEvent.VK_X -> note = 62; // D4
              case java.awt.event.KeyEvent.VK_D -> note = 63; // D#4
              case java.awt.event.KeyEvent.VK_C -> note = 64; // E4
              case java.awt.event.KeyEvent.VK_V -> note = 65; // F4
              case java.awt.event.KeyEvent.VK_G -> note = 66; // F#4
              case java.awt.event.KeyEvent.VK_B -> note = 67; // G4
              case java.awt.event.KeyEvent.VK_H -> note = 68; // G#4
              case java.awt.event.KeyEvent.VK_N -> note = 69; // A4
              case java.awt.event.KeyEvent.VK_J -> note = 70; // A#4
              case java.awt.event.KeyEvent.VK_M -> note = 71; // B4
            }
            if (note != -1) {
              System.out.println("QWERTY Piano Trigger: Note " + note);
              if (clipPanel != null) {
                clipPanel.flashIsomorphicNote(note);
                int trackId = clipPanel.getFocusTrack();

                boolean isSynth =
                    clipPanel.getProjectModel() != null
                        && !clipPanel.getProjectModel().getTracks().isEmpty()
                        && clipPanel.getProjectModel().getTracks().get(0)
                            instanceof org.chuck.deluge.model.SynthTrackModel;

                if (isSynth) {
                  try {
                    org.chuck.core.ChuckEvent noteEv =
                        (org.chuck.core.ChuckEvent) vm.getGlobalObject("g_ck_noteOn");
                    if (noteEv != null) {
                      org.chuck.core.ChuckArray pitchArr =
                          (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_PITCH);
                      pitchArr.setInt(0, (long) (note - 60));
                      noteEv.broadcast();
                    }
                  } catch (Exception ex) {
                  }
                } else {
                  String sp = (String) vm.getGlobalObject("g_sample_" + trackId);
                  if (sp != null && !sp.isEmpty()) {
                    new Thread(
                            () -> {
                              try {
                                java.io.File file = new java.io.File(sp);
                                if (file.exists()) {
                                  javax.sound.sampled.AudioInputStream stream =
                                      javax.sound.sampled.AudioSystem.getAudioInputStream(file);
                                  javax.sound.sampled.Clip c =
                                      javax.sound.sampled.AudioSystem.getClip();
                                  c.open(stream);
                                  c.start();
                                }
                              } catch (IOException
                                  | LineUnavailableException
                                  | UnsupportedAudioFileException ex) {
                              }
                            })
                        .start();
                  }
                }
              }
            }
          }
        });

    // Register a global KeyEventDispatcher to capture Shift key state changes instantly across all
    // child components
    KeyboardFocusManager.getCurrentKeyboardFocusManager()
        .addKeyEventDispatcher(
            new KeyEventDispatcher() {
              @Override
              public boolean dispatchKeyEvent(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_SHIFT) {
                  if (e.getID() == java.awt.event.KeyEvent.KEY_PRESSED) {
                    if (clipPanel != null) clipPanel.setShiftHeld(true);
                    if (songPanel != null) songPanel.setShiftHeld(true);
                    if (arrGridPanel != null) arrGridPanel.setShiftHeld(true);
                  } else if (e.getID() == java.awt.event.KeyEvent.KEY_RELEASED) {
                    if (clipPanel != null) clipPanel.setShiftHeld(false);
                    if (songPanel != null) songPanel.setShiftHeld(false);
                    if (arrGridPanel != null) arrGridPanel.setShiftHeld(false);
                    if (bridge != null && bridge.getPlayState() != 0) {
                      picTransport.flush();
                    } else {
                      if (clipPanel != null) clipPanel.refresh();
                      if (songPanel != null) songPanel.refresh();
                      if (arrGridPanel != null) arrGridPanel.refresh();
                    }
                  }
                }

                // Intercept Up/Down arrow keys to adjust current parameter value when a grid
                // shortcut is focused in Shift mode
                if (clipPanel != null
                    && clipPanel.isShiftHeld()
                    && clipPanel.getActiveShiftParam() != null) {
                  if (e.getID() == java.awt.event.KeyEvent.KEY_PRESSED) {
                    if (e.getKeyCode() == java.awt.event.KeyEvent.VK_UP) {
                      clipPanel.adjustRotaryParameter(1);
                      return true; // consume the event!
                    } else if (e.getKeyCode() == java.awt.event.KeyEvent.VK_DOWN) {
                      clipPanel.adjustRotaryParameter(-1);
                      return true; // consume the event!
                    }
                  }
                }
                return false; // Pass event downstream
              }
            });

    int w =
        Integer.parseInt(org.chuck.deluge.project.PreferencesManager.get("window.width", "2800"));
    int h =
        Integer.parseInt(org.chuck.deluge.project.PreferencesManager.get("window.height", "1600"));
    setSize(w, h);

    addWindowListener(
        new java.awt.event.WindowAdapter() {
          @Override
          public void windowClosing(java.awt.event.WindowEvent e) {
            org.chuck.deluge.project.PreferencesManager.set(
                "window.width", String.valueOf(getWidth()));
            org.chuck.deluge.project.PreferencesManager.set(
                "window.height", String.valueOf(getHeight()));
          }
        });

    setLocationRelativeTo(null);
    getContentPane().setBackground(new Color(0x1a, 0x1a, 0x1a));
    setLayout(new BorderLayout(10, 10));

    setupUI();

    // Auto-load our default initial song project to sync tracks, presets, and listeners to the
    // bridge/engine!
    loadProject(currentProject);

    // Trigger a high-fidelity retro rolling welcome message on boot!
    if (topBar != null && topBar.getRetroLedDisplay() != null) {
      topBar.getRetroLedDisplay().print("HELO", "    ");
      javax.swing.Timer timer1 =
          new javax.swing.Timer(
              800,
              e -> {
                if (topBar != null && topBar.getRetroLedDisplay() != null) {
                  topBar.getRetroLedDisplay().print("DELU", "V1.0");
                }
              });
      timer1.setRepeats(false);
      timer1.start();

      javax.swing.Timer timer2 =
          new javax.swing.Timer(
              1600,
              e -> {
                if (topBar != null && topBar.getRetroLedDisplay() != null) {
                  topBar.getRetroLedDisplay().reset();
                }
              });
      timer2.setRepeats(false);
      timer2.start();
    }

    startPlaybackTimer();

    setFocusable(true);
    addKeyListener(
        new java.awt.event.KeyAdapter() {
          @Override
          public void keyPressed(java.awt.event.KeyEvent e) {
            int kc = e.getKeyCode();
            boolean ctrl = (e.getModifiersEx() & java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0;

            // Ctrl+[ / Ctrl+] — adjust focused track length
            if (ctrl && kc == java.awt.event.KeyEvent.VK_OPEN_BRACKET) {
              SwingGridPanel active = activeGridPanel();
              if (active != null) {
                int trk = active.getFocusTrack();
                int len = bridge.getTrackLength(trk);
                bridge.setTrackLength(trk, Math.max(1, len - 1));
                active.refresh();
              }
              return;
            }
            if (ctrl && kc == java.awt.event.KeyEvent.VK_CLOSE_BRACKET) {
              SwingGridPanel active = activeGridPanel();
              if (active != null) {
                int trk = active.getFocusTrack();
                int len = bridge.getTrackLength(trk);
                bridge.setTrackLength(trk, Math.min(64, len + 1));
                active.refresh();
              }
              return;
            }

            // T — tap tempo
            if (!ctrl && kc == java.awt.event.KeyEvent.VK_T) {
              long now = System.currentTimeMillis();
              tapTimes.addLast(now);
              while (tapTimes.size() > 8) tapTimes.removeFirst();
              if (tapTimes.size() >= 2) {
                long[] arr = tapTimes.stream().mapToLong(Long::longValue).toArray();
                long totalGap = arr[arr.length - 1] - arr[0];
                double avgGap = totalGap / (double) (arr.length - 1);
                double bpm = 60000.0 / avgGap;
                bpm = Math.max(20, Math.min(300, bpm));
                bridge.setBpm(bpm);
              }
              return;
            }

            org.chuck.hid.HidMsg msg = new org.chuck.hid.HidMsg();
            msg.deviceType = "keyboard";
            msg.type = org.chuck.hid.HidMsg.BUTTON_DOWN;
            msg.which = kc;
            msg.key = kc;
            char c = e.getKeyChar();
            if (c != java.awt.event.KeyEvent.CHAR_UNDEFINED) {
              msg.ascii = c;
            }
            vm.dispatchHidMsg(msg);
          }

          @Override
          public void keyReleased(java.awt.event.KeyEvent e) {
            org.chuck.hid.HidMsg msg = new org.chuck.hid.HidMsg();
            msg.deviceType = "keyboard";
            msg.type = org.chuck.hid.HidMsg.BUTTON_UP;
            msg.which = e.getKeyCode();
            msg.key = e.getKeyCode();
            vm.dispatchHidMsg(msg);
          }
        });
  }

  public void loadProject(org.chuck.deluge.model.ProjectModel model) {
    currentProject = model;
    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
    if (bridge != null) bridge.setPlayState(0);

    // Register listener so structural and param changes auto-sync to bridge
    model.addProjectListener(new BridgeProjectListener(model));

    pushModelToBridge();
    propagateCurrentModel();
    syncHighFidelityEngine(model);

    if (clipPanel != null) clipPanel.setProjectModel(model);
    if (songPanel != null) songPanel.setProjectModel(model);
    if (arrGridPanel != null) arrGridPanel.setProjectModel(model);

    setTitle(
        "DELUGE WORKSTATION — "
            + (currentProjectFile != null ? currentProjectFile.getName() : "Untitled"));
  }

  private void syncHighFidelityEngine(org.chuck.deluge.model.ProjectModel model) {
    org.chuck.deluge.firmware.model.Song fwSong = FirmwareFactory.createSong(model);
    MatrixDriver.get().popUI();

    // Switch View to the first track's clip if possible
    if (!fwSong.clips.isEmpty()) {
      org.chuck.deluge.firmware.model.Clip first = fwSong.clips.get(0);
      if (first instanceof org.chuck.deluge.firmware.model.InstrumentClip ic) {
        if (ic.sound instanceof org.chuck.deluge.firmware.engine.FirmwareKit) {
          MatrixDriver.get().pushUI(new KitView(ic));
        } else {
          MatrixDriver.get().pushUI(new PianoRollView(ic));
        }
      } else {
        MatrixDriver.get().pushUI(new SessionView(fwSong));
      }
    } else {
      MatrixDriver.get().pushUI(new SessionView(fwSong));
    }

    // ── Sync Audio Registry ──
    Object fwEngineObj = vm.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
    if (fwEngineObj instanceof org.chuck.deluge.firmware.engine.FirmwareAudioEngine fwEngine) {
      fwEngine.sounds.clear();
      // Ensure sounds list matches track count for direct indexing
      for (int i = 0; i < model.getTracks().size(); i++) {
        fwEngine.sounds.add(null);
      }

      for (int i = 0; i < fwSong.clips.size() && i < model.getTracks().size(); i++) {
        org.chuck.deluge.firmware.model.Clip c = fwSong.clips.get(i);
        if (c instanceof org.chuck.deluge.firmware.model.InstrumentClip ic && ic.sound != null) {
          fwEngine.sounds.set(i, ic.sound);
          System.out.println(
              "[UI] Registered track " + i + " sound: " + ic.sound.getClass().getSimpleName());
        }
      }

      float masterVol = (float) vm.getGlobalFloat(BridgeContract.G_MASTER_VOL);
      System.out.println("[UI] Engine Sync - MasterVol Global: " + masterVol);
      fwEngine.masterVolumeAdjustmentL = (int) (masterVol * 2147483647.0);
      fwEngine.masterVolumeAdjustmentR = fwEngine.masterVolumeAdjustmentL;

      System.out.println(
          "[UI] Synchronized "
              + fwEngine.sounds.size()
              + " track slots for Hi-Fi Rendering. MasterVol: "
              + masterVol);
    }

    Object fwHandlerObj = vm.getGlobalObject(BridgeContract.G_PLAYBACK_HANDLER);
    System.out.println(
        "[DIAG sync] fwHandlerObj="
            + fwHandlerObj
            + " isPlaybackHandler="
            + (fwHandlerObj instanceof org.chuck.deluge.firmware.playback.PlaybackHandler)
            + " fwSongClips="
            + (fwSong != null ? fwSong.clips.size() : "null"));
    if (fwHandlerObj instanceof org.chuck.deluge.firmware.playback.PlaybackHandler fwHandler) {
      fwHandler.setSong(fwSong);
      System.out.println(
          "[DIAG sync] Successfully set fwSong inside PlaybackHandler! Current active play state="
              + fwHandler.isPlaying()
              + " songBpm="
              + fwSong.tempoBPM);
      if (fwSong.clips.size() > 0
          && fwSong.clips.get(0) instanceof org.chuck.deluge.firmware.model.InstrumentClip ic) {
        int activeNotesCount = 0;
        for (var row : ic.noteRows) {
          activeNotesCount += row.notes.size();
        }
        System.out.println(
            "[DIAG sync] First Clip Active NoteRows Count: "
                + ic.noteRows.size()
                + " Total Programmed Note Events: "
                + activeNotesCount);
      }
    }
  }

  private void refreshGrids() {
    if (clipPanel != null) clipPanel.refresh();
    if (songPanel != null) songPanel.refresh();
    if (arrGridPanel != null) arrGridPanel.refresh();
  }

  private void doUndo() {
    if (currentProject == null) return;
    var stack = currentProject.getUndoRedoStack();
    if (!stack.canUndo()) return;
    var action = stack.peekUndo();
    switch (action) {
      case Consequence.TrackStructureConsequence tsc -> {
        handleTrackStructUndoRedo(tsc, true);
        stack.undo();
      }
      case Consequence.ClipStructureConsequence csc -> {
        handleClipStructUndoRedo(csc, true);
        stack.undo();
      }
      case Consequence.PatternLoadConsequence plc -> {
        handlePatternLoadUndoRedo(plc, true);
        stack.undo();
      }
      default -> stack.undo();
    }
    pushModelToBridge();
    refreshGrids();
  }

  private void doRedo() {
    if (currentProject == null) return;
    var stack = currentProject.getUndoRedoStack();
    if (!stack.canRedo()) return;
    var action = stack.peekRedo();
    switch (action) {
      case Consequence.TrackStructureConsequence tsc -> {
        handleTrackStructUndoRedo(tsc, false);
        stack.redo();
      }
      case Consequence.ClipStructureConsequence csc -> {
        handleClipStructUndoRedo(csc, false);
        stack.redo();
      }
      case Consequence.PatternLoadConsequence plc -> {
        handlePatternLoadUndoRedo(plc, false);
        stack.redo();
      }
      default -> stack.redo();
    }
    pushModelToBridge();
    refreshGrids();
  }

  private void handleTrackStructUndoRedo(
      Consequence.TrackStructureConsequence tsc, boolean isUndo) {
    var tracks = currentProject.getTracks();
    int idx = tsc.index();
    switch (tsc.operation()) {
      case Consequence.TrackStructureConsequence.ADD -> {
        if (isUndo) {
          if (idx < tracks.size()) currentProject.removeTrack(tracks.get(idx));
        } else {
          currentProject.addTrack(idx, tsc.trackSnapshot());
        }
      }
      case Consequence.TrackStructureConsequence.REMOVE -> {
        if (isUndo) {
          currentProject.addTrack(idx, tsc.trackSnapshot());
        } else {
          if (idx < tracks.size()) currentProject.removeTrack(tracks.get(idx));
        }
      }
      case Consequence.TrackStructureConsequence.MOVE_UP -> {
        int swapIdx = isUndo ? idx + 1 : idx - 1;
        if (swapIdx >= 0 && swapIdx < tracks.size() && idx >= 0 && idx < tracks.size()) {
          currentProject.moveTrackUp(Math.max(idx, swapIdx));
        }
      }
      case Consequence.TrackStructureConsequence.MOVE_DOWN -> {
        int swapIdx = isUndo ? idx - 1 : idx + 1;
        if (swapIdx >= 0 && swapIdx < tracks.size() && idx >= 0 && idx < tracks.size()) {
          currentProject.moveTrackDown(Math.min(idx, swapIdx));
        }
      }
    }
  }

  private void handleClipStructUndoRedo(Consequence.ClipStructureConsequence csc, boolean isUndo) {
    var tracks = currentProject.getTracks();
    if (csc.trackIndex() < 0 || csc.trackIndex() >= tracks.size()) return;
    var track = tracks.get(csc.trackIndex());
    var clips = track.getClips();
    int ci = csc.clipIndex();
    switch (csc.operation()) {
      case Consequence.ClipStructureConsequence.ADD -> {
        if (isUndo) {
          if (ci >= 0 && ci < clips.size()) clips.remove(ci);
        } else {
          clips.add(ci, csc.clipSnapshot());
        }
      }
      case Consequence.ClipStructureConsequence.REMOVE -> {
        if (isUndo) {
          clips.add(ci, csc.clipSnapshot());
        } else {
          if (ci >= 0 && ci < clips.size()) clips.remove(ci);
        }
      }
      case Consequence.ClipStructureConsequence.DUPLICATE -> {
        if (isUndo) {
          if (ci + 1 >= 0 && ci + 1 < clips.size()) clips.remove(ci + 1);
        } else {
          clips.add(ci + 1, csc.clipSnapshot());
        }
      }
      case Consequence.ClipStructureConsequence.RENAME -> {
        String name = isUndo ? csc.previousName() : csc.newName();
        if (ci >= 0 && ci < clips.size()) clips.get(ci).setName(name);
      }
    }
  }

  private void handlePatternLoadUndoRedo(Consequence.PatternLoadConsequence plc, boolean isUndo) {
    var tracks = currentProject.getTracks();
    if (plc.trackIndex() < 0 || plc.trackIndex() >= tracks.size()) return;
    var track = tracks.get(plc.trackIndex());
    int ci = plc.clipIndex();
    if (ci < 0 || ci >= track.getClips().size()) return;
    var clip = track.getClips().get(ci);
    var snap = isUndo ? plc.beforeSnapshot() : plc.afterSnapshot();
    snap.applyTo(clip);
  }

  private void saveProject(boolean forceChooser) {
    java.io.File target = currentProjectFile;
    if (target == null || forceChooser) {
      JFileChooser chooser =
          new JFileChooser(org.chuck.deluge.project.PreferencesManager.getSongsDir());
      chooser.setFileFilter(
          new javax.swing.filechooser.FileNameExtensionFilter("Song XML", "xml", "XML"));
      if (currentProjectFile != null) chooser.setSelectedFile(currentProjectFile);
      if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
      target = chooser.getSelectedFile();
      if (!target.getName().toLowerCase().endsWith(".xml")) {
        target = new java.io.File(target.getAbsolutePath() + ".xml");
      }
    }
    try {
      // Sync firmware model -> Java model before save, if firmware engine is active
      if (pureEngine != null) {
        var fwSong = pureEngine.getPlaybackHandler().getSong();
        if (fwSong != null) {
          org.chuck.deluge.firmware.engine.FirmwareFactory.syncFirmwareToModel(
              fwSong, currentProject);
        }
      }
      pushModelToBridge();
      org.chuck.deluge.project.ProjectSerializer.save(currentProject, target);
      currentProjectFile = target;
      setTitle("DELUGE WORKSTATION — " + target.getName());
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(
          this, "Save failed:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
    }
  }

  private SwingGridPanel activeGridPanel() {
    if (cardLayout == null || centerCardPanel == null) return clipPanel;
    // Return whichever panel is currently visible
    for (java.awt.Component comp : centerCardPanel.getComponents()) {
      if (comp.isVisible() && comp instanceof SwingGridPanel sgp) return sgp;
      if (comp.isVisible()
          && comp instanceof JScrollPane sp
          && sp.getViewport().getView() instanceof SwingGridPanel sgp) return sgp;
    }
    return clipPanel;
  }

  private void propagateCurrentModel() {
    if (clipPanel != null) clipPanel.setProjectModel(currentProject);
    if (songPanel != null) songPanel.setProjectModel(currentProject);
    if (arrGridPanel != null) arrGridPanel.setProjectModel(currentProject);
    if (autoPanel != null) autoPanel.setProjectModel(currentProject);
  }

  public void updateHardwareLedDisplay(String paramCode, String valueString) {
    if (topBar != null && topBar.getRetroLedDisplay() != null) {
      if (paramCode == null || valueString == null) {
        topBar.getRetroLedDisplay().reset();
      } else {
        topBar.getRetroLedDisplay().print(paramCode, valueString);
      }
    }
  }

  public void updateHardwareLedDisplayTransient(String paramCode, String valueString) {
    if (topBar != null && topBar.getRetroLedDisplay() != null) {
      topBar.getRetroLedDisplay().printTransient(paramCode, valueString);
    }
  }

  public SwingGridPanel getClipPanel() {
    return clipPanel;
  }

  public SwingTopBarPanel getTopBar() {
    return topBar;
  }

  private void exportAudio() {
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("Export Audio");
    chooser.setSelectedFile(new java.io.File("deluge_export.wav"));
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

    String filePath = chooser.getSelectedFile().getAbsolutePath();
    if (!filePath.toLowerCase().endsWith(".wav")) filePath += ".wav";

    vm.setGlobalString(org.chuck.deluge.BridgeContract.G_WVOUT_FILE, filePath);
    vm.setGlobalFloat(org.chuck.deluge.BridgeContract.G_WVOUT_ACTIVE, 1.0f);

    JOptionPane.showMessageDialog(
        this,
        "Export started to:\n" + filePath + "\n\nClick OK to stop export.",
        "Exporting Audio...",
        JOptionPane.INFORMATION_MESSAGE);

    vm.setGlobalFloat(org.chuck.deluge.BridgeContract.G_WVOUT_ACTIVE, 0.0f);
  }

  /**
   * Save the active clip of the currently focused track as a pattern XML file. Prompts the user for
   * a file location under the PATTERNS directory.
   */
  private void saveCurrentClipAsPattern() {
    SwingGridPanel active = activeGridPanel();
    if (active == null) return;
    int focusTrack = active.getFocusTrack();
    if (focusTrack < 0 || focusTrack >= currentProject.getTracks().size()) {
      JOptionPane.showMessageDialog(
          this, "No track selected.", "Save Pattern", JOptionPane.WARNING_MESSAGE);
      return;
    }
    var track = currentProject.getTracks().get(focusTrack);
    int clipIdx = track.getActiveClipIndex();
    if (clipIdx < 0 || clipIdx >= track.getClips().size()) {
      JOptionPane.showMessageDialog(
          this, "Active clip not found.", "Save Pattern", JOptionPane.WARNING_MESSAGE);
      return;
    }
    ClipModel clip = track.getClips().get(clipIdx);

    JFileChooser chooser =
        new JFileChooser(org.chuck.deluge.project.PreferencesManager.getPatternsDir());
    chooser.setDialogTitle("Save Pattern");
    chooser.setFileFilter(
        new javax.swing.filechooser.FileNameExtensionFilter("Pattern XML", "xml", "XML"));
    String suggestedName = track.getName() + "_" + clip.getName() + ".xml";
    chooser.setSelectedFile(new java.io.File(suggestedName));
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

    java.io.File target = chooser.getSelectedFile();
    if (!target.getName().toLowerCase().endsWith(".xml")) {
      target = new java.io.File(target.getAbsolutePath() + ".xml");
    }

    try {
      PatternModel pattern =
          new PatternModel(java.util.UUID.randomUUID().toString(), clip.getName());
      pattern.setCategory("MELODIC");

      PatternModel.ClipSnapshot snap =
          PatternModel.ClipSnapshot.fromClipModel(clip, focusTrack, track.getName());
      snap.setInstrumentSlot(track.getName());
      snap.setColourHex(track.getColourHex());
      pattern.addClipSnapshot(snap);

      org.chuck.deluge.project.PatternSerializer.save(pattern, target);
      JOptionPane.showMessageDialog(
          this,
          "Pattern saved:\n" + target.getName(),
          "Save Pattern",
          JOptionPane.INFORMATION_MESSAGE);
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(
          this, "Failed to save pattern:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
    }
  }

  /**
   * Load a pattern from an XML file and apply it to the active clip of the focused track. Prompts
   * the user to select a target track if the focused track doesn't have a compatible clip.
   */
  private void loadPatternIntoActiveTrack(java.io.File patternFile) {
    try {
      PatternModel pattern = org.chuck.deluge.project.PatternSerializer.load(patternFile);
      if (pattern.getClipSnapshots().isEmpty()) {
        JOptionPane.showMessageDialog(
            this, "Pattern file contains no clips.", "Load Pattern", JOptionPane.WARNING_MESSAGE);
        return;
      }

      SwingGridPanel active = activeGridPanel();
      int focusTrack = (active != null) ? active.getFocusTrack() : 0;
      if (focusTrack < 0 || focusTrack >= currentProject.getTracks().size()) {
        focusTrack = 0;
      }
      var track = currentProject.getTracks().get(focusTrack);
      int clipIdx = track.getActiveClipIndex();
      if (clipIdx < 0 || clipIdx >= track.getClips().size()) {
        JOptionPane.showMessageDialog(
            this,
            "Active clip not found on target track.",
            "Load Pattern",
            JOptionPane.WARNING_MESSAGE);
        return;
      }
      ClipModel clip = track.getClips().get(clipIdx);

      // Capture before-snapshot for undo
      var beforeSnapshot =
          PatternModel.ClipSnapshot.fromClipModel(clip, focusTrack, track.getName());

      // Apply the first clip snapshot to the active clip
      pattern.getClipSnapshots().get(0).applyTo(clip);

      // Push undo: re-apply the old snapshot
      var afterSnapshot =
          PatternModel.ClipSnapshot.fromClipModel(clip, focusTrack, track.getName());
      currentProject
          .getUndoRedoStack()
          .push(
              new Consequence.CompoundConsequence(
                  "Load pattern",
                  java.util.List.of(
                      new Consequence.PatternLoadConsequence(
                          focusTrack, clipIdx, beforeSnapshot, afterSnapshot))));

      pushModelToBridge();
      propagateCurrentModel();
      refreshGrids();

      JOptionPane.showMessageDialog(
          this,
          "Pattern loaded into: " + track.getName(),
          "Load Pattern",
          JOptionPane.INFORMATION_MESSAGE);
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(
          this, "Failed to load pattern:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
    }
  }

  private void loadChuckScript() {
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("Load ChucK Script");
    javax.swing.filechooser.FileNameExtensionFilter filter =
        new javax.swing.filechooser.FileNameExtensionFilter("ChucK Scripts (*.ck)", "ck");
    chooser.setFileFilter(filter);
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

    java.io.File file = chooser.getSelectedFile();
    try {
      String content = new String(java.nio.file.Files.readAllBytes(file.toPath()));
      vm.eval(content);
      JOptionPane.showMessageDialog(
          this,
          "Script loaded successfully:\n" + file.getName(),
          "Script Loaded",
          JOptionPane.INFORMATION_MESSAGE);
    } catch (HeadlessException | IOException ex) {
      JOptionPane.showMessageDialog(
          this, "Failed to load script:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
    }
  }

  private void assembleKitFromSynths() {
    JFileChooser chooser =
        new JFileChooser(org.chuck.deluge.project.PreferencesManager.getSongsDir());
    chooser.setDialogTitle("Select Synth Preset XML Files");
    chooser.setMultiSelectionEnabled(true);
    chooser.setFileFilter(
        new javax.swing.filechooser.FileNameExtensionFilter("Synth XML", "xml", "XML"));
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

    java.io.File[] selected = chooser.getSelectedFiles();
    if (selected.length == 0) return;

    // Dialog for configuring each lane
    JPanel configPanel = new JPanel(new GridBagLayout());
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(3, 5, 3, 5);
    c.gridx = 0;

    java.util.List<JTextField> nameFields = new java.util.ArrayList<>();
    java.util.List<JSpinner> muteFields = new java.util.ArrayList<>();
    java.util.List<JSpinner> pitchFields = new java.util.ArrayList<>();

    for (int i = 0; i < selected.length; i++) {
      JTextField nameFld = new JTextField(selected[i].getName().replaceAll("(?i)\\.xml$", ""), 20);
      SpinnerNumberModel muteModel = new SpinnerNumberModel(0, 0, 16, 1);
      JSpinner muteSpinner = new JSpinner(muteModel);
      SpinnerNumberModel pitchModel = new SpinnerNumberModel(0, -24, 24, 1);
      JSpinner pitchSpinner = new JSpinner(pitchModel);

      c.gridy = i;
      c.gridwidth = 1;
      configPanel.add(new JLabel((i + 1) + ":"), c);
      c.gridx = 1;
      configPanel.add(nameFld, c);
      c.gridx = 2;
      configPanel.add(new JLabel("MG:"), c);
      c.gridx = 3;
      configPanel.add(muteSpinner, c);
      c.gridx = 4;
      configPanel.add(new JLabel("Pitch:"), c);
      c.gridx = 5;
      configPanel.add(pitchSpinner, c);
      c.gridx = 0;

      nameFields.add(nameFld);
      muteFields.add(muteSpinner);
      pitchFields.add(pitchSpinner);
    }

    int result =
        JOptionPane.showConfirmDialog(
            this,
            configPanel,
            "Configure Kit Lanes",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE);
    if (result != JOptionPane.OK_OPTION) return;

    try {
      java.util.List<Integer> muteGroups = new java.util.ArrayList<>();
      java.util.List<Integer> pitchOffsets = new java.util.ArrayList<>();
      for (int i = 0; i < selected.length; i++) {
        muteGroups.add((Integer) muteFields.get(i).getValue());
        pitchOffsets.add((Integer) pitchFields.get(i).getValue());
      }

      String kitName = JOptionPane.showInputDialog(this, "Kit name:", "Kit from Synths");
      if (kitName == null || kitName.isBlank()) kitName = "Kit from Synths";

      org.chuck.deluge.model.KitTrackModel kit =
          org.chuck.deluge.kit.KitAssembler.assembleFromSynths(
              kitName, java.util.Arrays.asList(selected), muteGroups, pitchOffsets);

      JFileChooser saveChooser =
          new JFileChooser(org.chuck.deluge.project.PreferencesManager.getSongsDir());
      saveChooser.setDialogTitle("Save Kit As");
      saveChooser.setFileFilter(
          new javax.swing.filechooser.FileNameExtensionFilter("Kit XML", "xml", "XML"));
      saveChooser.setSelectedFile(new java.io.File(kitName + ".xml"));
      if (saveChooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

      java.io.File saveFile = saveChooser.getSelectedFile();
      if (!saveFile.getName().toLowerCase().endsWith(".xml")) {
        saveFile = new java.io.File(saveFile.getAbsolutePath() + ".xml");
      }
      org.chuck.deluge.project.KitSynthSerializer.saveKit(kit, saveFile);

      JOptionPane.showMessageDialog(
          this,
          "Kit saved to:\n" + saveFile.getAbsolutePath(),
          "Kit Assembly Complete",
          JOptionPane.INFORMATION_MESSAGE);
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(
          this, "Failed to assemble kit:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
      ex.printStackTrace();
    }
  }

  private void setupUI() {
    getContentPane().removeAll();
    setLayout(new BorderLayout());

    // 0. Menu Bar
    JMenuBar menuBar = new JMenuBar();
    JMenu fileMenu = new JMenu("File");

    JMenuItem newItem = new JMenuItem("New Project");
    newItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_N, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    newItem.addActionListener(
        e -> {
          int ok =
              JOptionPane.showConfirmDialog(
                  this,
                  "Create a new empty project? Unsaved changes will be lost.",
                  "New Project",
                  JOptionPane.OK_CANCEL_OPTION);
          if (ok == JOptionPane.OK_OPTION) {
            currentProjectFile = null;
            loadProject(org.chuck.deluge.model.ProjectModel.createDefaultProject());
          }
        });

    JMenuItem openItem = new JMenuItem("Open Project...");
    openItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_O, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    openItem.addActionListener(
        e -> {
          JFileChooser chooser =
              new JFileChooser(org.chuck.deluge.project.PreferencesManager.getSongsDir());
          chooser.setFileFilter(
              new javax.swing.filechooser.FileNameExtensionFilter("Song XML", "xml", "XML"));
          if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
              java.io.File file = chooser.getSelectedFile();
              org.chuck.deluge.model.ProjectModel model =
                  org.chuck.deluge.xml.DelugeXmlParser.parseSong(
                      new java.io.FileInputStream(file), file.getName());
              currentProjectFile = file;
              loadProject(model);
            } catch (Exception ex) {
              JOptionPane.showMessageDialog(
                  this,
                  "Failed to open project:\n" + ex.getMessage(),
                  "Error",
                  JOptionPane.ERROR_MESSAGE);
            }
          }
        });

    JMenuItem saveItem = new JMenuItem("Save Project");
    saveItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_S, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    saveItem.addActionListener(e -> saveProject(false));

    JMenuItem saveAsItem = new JMenuItem("Save Project As...");
    saveAsItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_S,
            java.awt.event.InputEvent.CTRL_DOWN_MASK | java.awt.event.InputEvent.SHIFT_DOWN_MASK));
    saveAsItem.addActionListener(e -> saveProject(true));

    JMenuItem exportItem = new JMenuItem("Export Audio...");
    exportItem.addActionListener(e -> exportAudio());

    JMenuItem assembleKitItem = new JMenuItem("Assemble Kit From Synths...");
    assembleKitItem.addActionListener(e -> assembleKitFromSynths());

    JMenuItem loadScriptItem = new JMenuItem("Load Script...");
    loadScriptItem.addActionListener(e -> loadChuckScript());

    JMenuItem exitItem = new JMenuItem("Exit");
    exitItem.addActionListener(e -> System.exit(0));

    fileMenu.add(newItem);
    fileMenu.add(openItem);
    fileMenu.addSeparator();
    fileMenu.add(saveItem);
    fileMenu.add(saveAsItem);
    fileMenu.addSeparator();
    fileMenu.add(exportItem);
    fileMenu.addSeparator();
    fileMenu.add(assembleKitItem);
    fileMenu.addSeparator();
    fileMenu.add(loadScriptItem);
    fileMenu.addSeparator();
    fileMenu.add(exitItem);

    JMenu settingsMenu = new JMenu("Settings");

    JMenuItem sampleItem = new JMenuItem("Set SD Card Root...");
    sampleItem.addActionListener(
        e -> {
          JFileChooser chooser =
              new JFileChooser(org.chuck.deluge.project.PreferencesManager.getLibraryDir());
          chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
          if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            org.chuck.deluge.project.PreferencesManager.setLibraryDir(
                chooser.getSelectedFile().getAbsolutePath());
            sidebarPanel.reloadLibrary();
            floatingSidebar.reloadLibrary();
          }
        });

    final JCheckBoxMenuItem hifiModeItem = new JCheckBoxMenuItem("High Fidelity Audio");
    hifiModeItem.setSelected(bridge.getHiFiMode() == 0);
    hifiModeItem.addActionListener(
        e -> {
          int newMode = hifiModeItem.isSelected() ? 0 : 1;
          bridge.setHiFiMode(newMode);
          JOptionPane.showMessageDialog(
              SwingDelugeApp.this,
              "Audio render engine updated! Please restart workstation to engage the "
                  + (newMode == 0 ? "ChucK VM High Fidelity" : "Direct Low-Latency JVM")
                  + " output driver.");
        });

    JMenuItem clearMidiItem = new JMenuItem("Reset MIDI Mappings");
    clearMidiItem.addActionListener(
        e -> {
          int ok =
              JOptionPane.showConfirmDialog(
                  SwingDelugeApp.this,
                  "Are you sure you want to clear all dynamically learned physical MIDI CC controllers mappings?",
                  "Clear MIDI Mappings",
                  JOptionPane.YES_NO_OPTION);
          if (ok == JOptionPane.YES_OPTION) {
            String[] prefKeys = org.chuck.deluge.project.PreferencesManager.getKeys();
            for (String key : prefKeys) {
              if (key.startsWith("midi.learn.")) {
                org.chuck.deluge.project.PreferencesManager.remove(key);
              }
            }
            JOptionPane.showMessageDialog(
                SwingDelugeApp.this, "All learned physical MIDI CC bindings cleared successfully!");
          }
        });

    JMenuItem prefItem = new JMenuItem("Preferences...");
    prefItem.addActionListener(
        e -> {
          PreferencesDialog dialog =
              new PreferencesDialog(
                  SwingDelugeApp.this,
                  () -> {
                    org.chuck.deluge.project.PreferencesManager.GridMode mode =
                        org.chuck.deluge.project.PreferencesManager.getGridMode();
                    if (clipPanel != null) {
                      clipPanel.setGridMode(mode);
                      clipPanel.refresh();
                    }
                    if (songPanel != null) {
                      songPanel.setGridMode(mode);
                      songPanel.refresh();
                    }
                    if (arrGridPanel != null) {
                      arrGridPanel.setGridMode(mode);
                      arrGridPanel.refresh();
                    }
                    recalcWrapperSize();
                  },
                  () -> {
                    sidebarPanel.reloadLibrary();
                    floatingSidebar.reloadLibrary();
                  });
          if (midiService != null) dialog.setMappings(midiService.getMappings());
          dialog.setVisible(true);
        });

    settingsMenu.add(sampleItem);
    settingsMenu.addSeparator();
    settingsMenu.add(hifiModeItem);
    settingsMenu.add(clearMidiItem);
    settingsMenu.addSeparator();
    settingsMenu.add(prefItem);

    // Edit menu — Undo/Redo
    JMenu editMenu = new JMenu("Edit");

    JMenuItem undoItem = new JMenuItem("Undo");
    undoItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_Z, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    undoItem.addActionListener(e -> doUndo());

    JMenuItem redoItem = new JMenuItem("Redo");
    redoItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_Y, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    redoItem.addActionListener(e -> doRedo());

    editMenu.add(undoItem);
    editMenu.add(redoItem);

    menuBar.add(fileMenu);
    menuBar.add(editMenu);
    menuBar.add(settingsMenu);
    setJMenuBar(menuBar);

    // Global undo/redo keyboard shortcuts (always active regardless of focus)
    getRootPane()
        .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        .put(
            KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_Z, java.awt.event.InputEvent.CTRL_DOWN_MASK),
            "undo");
    getRootPane()
        .getActionMap()
        .put(
            "undo",
            new AbstractAction() {
              @Override
              public void actionPerformed(java.awt.event.ActionEvent e) {
                doUndo();
              }
            });
    getRootPane()
        .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        .put(
            KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_Y, java.awt.event.InputEvent.CTRL_DOWN_MASK),
            "redo");
    getRootPane()
        .getActionMap()
        .put(
            "redo",
            new AbstractAction() {
              @Override
              public void actionPerformed(java.awt.event.ActionEvent e) {
                doRedo();
              }
            });

    final JDialog leftFloat = new JDialog(this, "SD Explorer", false);
    leftFloat.setSize(300, 700);
    leftFloat.setLocation(50, 150);

    final JDialog rightFloat = new JDialog(this, "Acoustics Monitor", false);
    rightFloat.setSize(280, 700);
    rightFloat.setLocation(1600, 150);

    // 1. Top Area (Buttons, Modes, Transport, Sliders)

    cardLayout = new CardLayout();
    centerCardPanel = new JPanel(cardLayout);

    Runnable projectChangeHandler =
        () -> {
          propagateCurrentModel();
          syncHighFidelityEngine(currentProject);
        };

    clipPanel = new SwingGridPanel(vm, bridge);
    clipPanel.setViewMode(SwingGridPanel.GridViewMode.CLIP);
    clipPanel.setProjectModel(currentProject);
    clipPanel.setOnProjectChanged(projectChangeHandler);
    clipPanel.setOnClipChanged(
        () -> {
          propagateCurrentModel();
          pushModelToBridge();
          syncHighFidelityEngine(currentProject);
          clipPanel.refresh();
        });
    centerCardPanel.add(wrapGridPanel(clipPanel), "CLIP");

    // Wire PIC transport to Swing pad buttons for protocol-level pad rendering
    this.picTransport = new org.chuck.deluge.firmware.hid.pic.SwingPicTransport();
    picTransport.setPadButtons(clipPanel.getPadButtons());
    PIC.setTransport(picTransport);

    songPanel = new SwingGridPanel(vm, bridge);
    songPanel.setViewMode(SwingGridPanel.GridViewMode.SONG);
    songPanel.setProjectModel(currentProject);
    songPanel.setOnProjectChanged(projectChangeHandler);
    centerCardPanel.add(wrapGridPanel(songPanel), "SONG");

    arrGridPanel = new SwingGridPanel(vm, bridge);
    arrGridPanel.setViewMode(SwingGridPanel.GridViewMode.ARRANGEMENT);
    arrGridPanel.setProjectModel(currentProject);
    arrGridPanel.setOnProjectChanged(projectChangeHandler);
    centerCardPanel.add(wrapGridPanel(arrGridPanel), "ARR");

    autoPanel = new SwingGridPanel(vm, bridge);
    autoPanel.setViewMode(SwingGridPanel.GridViewMode.AUTOMATION);
    autoPanel.setProjectModel(currentProject);
    autoPanel.setOnProjectChanged(projectChangeHandler);
    centerCardPanel.add(wrapGridPanel(autoPanel), "AUTO");

    performancePanel = new SwingPerformanceViewPanel(vm, bridge, currentProject);
    performancePanel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
    centerCardPanel.add(performancePanel, "PERF");

    topBar =
        new SwingTopBarPanel(vm, currentProject, leftFloat, rightFloat, new AppTopBarListener());

    // DEBUG: solid background colors to visualize panel sizes
    System.out.println(
        "DEBUG setupUI: topBar bg="
            + topBar.getBackground()
            + " contentPane bg="
            + getContentPane().getBackground());

    JPanel topBarWrapper = new JPanel(new BorderLayout());
    topBarWrapper.add(topBar, BorderLayout.CENTER);
    add(topBarWrapper, BorderLayout.NORTH);

    JScrollPane centerScroll =
        new JScrollPane(
            centerCardPanel,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);

    // DEBUG: centerScroll.setBorder(BorderFactory.createLineBorder(Color.YELLOW, 3));
    /*centerScroll.getViewport().setOpaque(true);
    centerScroll.getViewport().setBackground(Color.BLUE);*/

    add(centerScroll, BorderLayout.CENTER);

    javax.swing.SwingUtilities.invokeLater(() -> centerScroll.getVerticalScrollBar().setValue(0));

    // 2. Left Area (SD Card / Editors)
    sidebarPanel = new SwingProjectSidebarPanel(vm, bridge, midiService);
    sidebarPanel.reloadLibrary();
    floatingSidebar = new SwingProjectSidebarPanel(vm, bridge, midiService);
    sidebarPanel.setOnSongLoaded(
        model -> {
          // Load shared project state (clears pattern, updates all panels, fires engine reload)
          loadProject(model);

          // Switch view depending on track count
          if (model.getTracks().size() == 1) {
            cardLayout.show(centerCardPanel, "CLIP");
            if (topBar != null) topBar.selectClipView();
            boolean firstIsSynth =
                !model.getTracks().isEmpty()
                    && model.getTracks().get(0) instanceof org.chuck.deluge.model.SynthTrackModel;
            clipPanel.setBaseTrackId(
                trackEngineStart != null && trackEngineStart.length > 0
                    ? trackEngineStart[0]
                    : (firstIsSynth ? 4 : 0));
          } else {
            cardLayout.show(centerCardPanel, "SONG");
          }

          // Push model data to engine — use the centralized mapping
          // so Kit sounds get written to the correct engine rows
          if (trackEngineStart != null) {
            pushModelToBridge();
          }
        });

    floatingSidebar.setOnSongLoaded(sidebarPanel.getOnSongLoaded());

    // Wire sidebar "add track" callback — KITS/SYNTHS double-click adds to current project
    java.util.function.Consumer<org.chuck.deluge.model.TrackModel> addTrack =
        track -> {
          // Add a default clip so notes entered on grid are stored in the model
          switch (track) {
            case org.chuck.deluge.model.KitTrackModel kit -> {
              int rowCount = kit.getDrums().size();
              if (rowCount < 1) rowCount = 1;
              kit.addClip(new org.chuck.deluge.model.ClipModel("CLIP 1", rowCount, 16));
            }
            case org.chuck.deluge.model.SynthTrackModel synth ->
                synth.addClip(new org.chuck.deluge.model.ClipModel("CLIP 1", 8, 16));
            default -> {}
          }
          int idx = currentProject.getTracks().size();
          currentProject.addTrack(track);
          currentProject
              .getUndoRedoStack()
              .push(
                  new Consequence.TrackStructureConsequence(
                      Consequence.TrackStructureConsequence.ADD, idx, track, "Add track"));
          pushModelToBridge();
          propagateCurrentModel();
          syncHighFidelityEngine(currentProject);
          refreshGrids();

          if (trackEngineStart != null && idx < trackEngineStart.length) {
            int engineBase = trackEngineStart[idx];
            clipPanel.setBaseTrackId(engineBase);
            clipPanel.setEditedModelTrack(idx);
            clipPanel.setActiveClipId(0);
            clipPanel.refresh();
          }

          cardLayout.show(centerCardPanel, "CLIP");
          if (topBar != null) topBar.selectClipView();
        };
    sidebarPanel.setOnTrackAdded(addTrack);
    floatingSidebar.setOnTrackAdded(addTrack);
    sidebarPanel.setOnPatternSave(this::saveCurrentClipAsPattern);
    sidebarPanel.setOnPatternLoad(this::loadPatternIntoActiveTrack);
    floatingSidebar.setOnPatternSave(this::saveCurrentClipAsPattern);
    floatingSidebar.setOnPatternLoad(this::loadPatternIntoActiveTrack);

    songPanel.setOnEditRequest(
        (trackId, clipId) -> {
          sidebarPanel.updateFocusTrack(trackId);

          if (clipPanel != null && trackId < trackEngineStart.length) {
            int engineBase = trackEngineStart[trackId];
            int voiceCount = trackVoiceCount[trackId];

            clipPanel.setActiveClipId(clipId);
            clipPanel.setBaseTrackId(engineBase);
            clipPanel.setEditedModelTrack(trackId);

            java.util.List<org.chuck.deluge.model.TrackModel> allTrks =
                clipPanel.getProjectModel() != null
                    ? clipPanel.getProjectModel().getTracks()
                    : java.util.List.of();

            boolean editIsSynth =
                trackId < allTrks.size()
                    && allTrks.get(trackId) instanceof org.chuck.deluge.model.SynthTrackModel;

            // Determine step count for clearing and re-pushing
            int clearSteps = 16;
            if (trackId < allTrks.size()) {
              org.chuck.deluge.model.TrackModel tModel = allTrks.get(trackId);
              if (clipId < tModel.getClips().size()) {
                clearSteps = tModel.getClips().get(clipId).getStepCount();
              }
            }

            // Clear engine rows for this track
            for (int v = 0; v < voiceCount; v++) {
              for (int s = 0; s < clearSteps; s++) bridge.setStep(engineBase + v, s, false);
            }

            if (trackId < allTrks.size()) {
              org.chuck.deluge.model.TrackModel tModel = allTrks.get(trackId);
              if (clipId < tModel.getClips().size()) {
                org.chuck.deluge.model.ClipModel cModel = tModel.getClips().get(clipId);
                int clipSteps = cModel.getStepCount();
                for (int r = 0; r < cModel.getRowCount(); r++) {
                  for (int s = 0; s < clipSteps; s++) {
                    org.chuck.deluge.model.StepData sd = cModel.getStep(r, s);
                    if (sd != null && sd.active()) {
                      if (r < voiceCount) {
                        bridge.setStep(engineBase + r, s, true);
                      }
                    }
                  }
                }
              }
            }

            clipPanel.refresh();
          }

          cardLayout.show(centerCardPanel, "CLIP");
          if (topBar != null) topBar.selectClipView();
        });

    visualizerPanel = new SwingVisualizerPanel(vm, bridge);

    leftFloat.add(floatingSidebar);
    rightFloat.add(visualizerPanel);

    new Timer(33, e -> visualizerPanel.repaint()).start();

    // Periodically flush PIC framebuffer to Swing pad buttons (≈30 fps)
    if (this.pureEngine == null) {
      new Timer(
              33,
              e -> {
                if (clipPanel != null && clipPanel.isShiftHeld()) {
                  return;
                }
                if (songPanel != null && songPanel.isShiftHeld()) {
                  return;
                }
                if (arrGridPanel != null && arrGridPanel.isShiftHeld()) {
                  return;
                }
                if (bridge != null && bridge.getPlayState() == 0) {
                  return;
                }
                picTransport.flush();
              })
          .start();
    }

    // bottom lane purged

    // 5. Bottom Area - Rows 9 and 10 (Param Deck)
    // Obsolete bottom parameter deck removed. Integrated in 10x18 pads matrix.

    // 7. Bottom Area - Row 3 (Master FX dials bounding boxes)
    SwingMasterFxPanel masterFxPanel = new SwingMasterFxPanel(vm, currentProject, topBar);
    this.masterFxPanel = masterFxPanel;
    // DEBUG: masterFxPanel.setBorder(BorderFactory.createLineBorder(Color.YELLOW, 3));

    masterFxPanel.setPreferredSize(new Dimension(1, 54));
    add(masterFxPanel, BorderLayout.SOUTH);

    revalidate();

    // Push default project to engine and broadcast load trigger to unblock shreds
    pushModelToBridge();
  }

  /** No-op: centeredWrapper removed, scroll pane sizes to content naturally. */
  private void recalcWrapperSize() {
    // content naturally sizes the scroll viewport
  }

  private void startPlaybackTimer() {
    Timer timer =
        new Timer(
            30,
            e -> {
              int step = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);

              int bar = step / 16 + 1;
              int beat = (step % 16) / 4 + 1;
              int subStep = (step % 4) + 1;

              String statusStr = "STOP";
              if (vm.getGlobalInt(BridgeContract.G_PLAY) == 1L) {
                statusStr = String.format("%d.%d.%d", bar, beat, subStep);
              }
              statusStr += " | SHREDS: " + vm.getActiveShredCount();

              Component[] comps = getContentPane().getComponents();
              for (Component c : comps) {
                if (c instanceof JPanel p) {
                  for (Component child : p.getComponents()) {
                    if (child instanceof JLabel l && l.getForeground().equals(Color.GREEN)) {
                      l.setText(statusStr);
                    }
                  }
                }
              }

              if (clipPanel != null) {
                clipPanel.updatePlayhead(step);
              }
              if (songPanel != null) {
                songPanel.updatePlayhead(step);
              }

              if (visualizerPanel != null) {
                visualizerPanel.repaint();
              }
            });
    timer.start();
  }

  public static void main(String[] args) {
    // Configure global tooltip timing parameters (pop up after 250ms, keep open for 20s)
    javax.swing.ToolTipManager.sharedInstance().setDismissDelay(20000);
    javax.swing.ToolTipManager.sharedInstance().setInitialDelay(250);

    org.chuck.core.ChuckVM vm = new org.chuck.core.ChuckVM(44100, 2);
    org.chuck.deluge.BridgeContract bridge = new org.chuck.deluge.BridgeContract();

    // Default to Pure Java (Pure Firmware) direct soundcard output mode by default!
    boolean pureModeLocal = true;
    bridge.setHiFiMode(1);
    System.out.println(
        "[main] Pure Java (Pure Firmware) direct soundcard output ENABLED by default");

    for (String arg : args) {
      if ("--hifi".equalsIgnoreCase(arg)) {
        pureModeLocal = false;
        bridge.setHiFiMode(1);
        System.out.println("[main] High Fidelity Mode ENABLED (using ChucK DSL output bridge)");
      }
      if ("--pure".equalsIgnoreCase(arg)) {
        pureModeLocal = true;
        bridge.setHiFiMode(1);
        System.out.println("[main] Pure Java Mode ENABLED (Bypassing ChucK DSL)");
      }
    }

    bridge.register(vm);

    if (!pureModeLocal) {
      org.chuck.audio.ChuckAudio audio = new org.chuck.audio.ChuckAudio(vm, 1024, 2, 44100);
      vm.setAudio(audio);
      System.out.println("[main] audio.outputLine=" + (audio.isOutputLineReady() ? "OK" : "NULL"));
      audio.start();
      System.out.println("[main] audio started, activeShreds=" + vm.getActiveShredCount());

      vm.spork(new org.chuck.deluge.engine.DelugeEngineDSL());
      System.out.println("[main] engine sporked, activeShreds=" + vm.getActiveShredCount());
    } else {
      System.out.println("[main] Pure Mode: Skipping ChucK Audio & DSL.");
    }

    // Give engine time to initialize before UI loads
    try {
      Thread.sleep(200);
    } catch (InterruptedException ie) {
    }
    System.out.println("[main] after 200ms sleep, activeShreds=" + vm.getActiveShredCount());

    org.chuck.deluge.midi.MidiInputRouter router =
        new org.chuck.deluge.midi.MidiInputRouter(vm, bridge);
    org.chuck.deluge.midi.MidiService midiService =
        new org.chuck.deluge.midi.MidiService(vm, bridge, router);
    midiService.start();

    final boolean finalPureMode = pureModeLocal;
    java.awt.EventQueue.invokeLater(
        () -> {
          javax.swing.UIManager.put(
              "Menu.font", new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11));
          javax.swing.UIManager.put(
              "MenuItem.font", new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11));
          javax.swing.UIManager.put(
              "MenuBar.font", new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11));
          javax.swing.UIManager.put(
              "CheckBoxMenuItem.font", new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11));
          javax.swing.UIManager.put(
              "RadioButtonMenuItem.font", new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11));
          SwingDelugeApp app = new SwingDelugeApp(vm, bridge, midiService, finalPureMode);
          app.setVisible(true);
          // Auto-load if a file path is provided as argument
          if (args.length > 0 && args[0] != null && !args[0].isEmpty()) {
            try {
              java.io.File f = new java.io.File(args[0]);
              if (f.exists()) {
                System.out.println("[main] Auto-loading: " + f.getAbsolutePath());
                org.chuck.deluge.model.ProjectModel model =
                    org.chuck.deluge.xml.DelugeXmlParser.parseSong(
                        new java.io.FileInputStream(f), f.getName());
                app.currentProjectFile = f;
                app.loadProject(model);
              }
            } catch (Exception ex) {
              System.err.println("[main] Auto-load failed: " + ex.getMessage());
            }
          }
        });
  }

  // ── Inner classes ──

  /** Handles top-bar view-mode and add-track actions. */
  private class AppTopBarListener implements SwingTopBarPanel.TopBarListener {
    @Override
    public void onViewModeChanged(String viewMode) {
      cardLayout.show(centerCardPanel, viewMode);

      // Update High-Fidelity UI Stack
      if ("CLIP".equals(viewMode) || "SONG".equals(viewMode)) {
        syncHighFidelityEngine(currentProject);
      }
    }

    @Override
    public void onAddTrack(String type) {
      String name =
          JOptionPane.showInputDialog(
              SwingDelugeApp.this,
              type + " track name:",
              type + " " + (currentProject.getTracks().size() + 1));
      if (name == null || name.isBlank()) return;
      var stack = currentProject.getUndoRedoStack();
      int idx;
      switch (type) {
        case "KIT" -> {
          KitTrackModel kit = new KitTrackModel(name);
          kit.addClip(new ClipModel("CLIP 1", 8, 16));
          idx = currentProject.getTracks().size();
          currentProject.addTrack(kit);
          stack.push(
              new Consequence.TrackStructureConsequence(
                  Consequence.TrackStructureConsequence.ADD, idx, kit, "Add kit track"));
        }
        case "SYNTH" -> {
          SynthTrackModel synth = new SynthTrackModel(name);
          synth.addClip(new ClipModel("CLIP 1", 8, 16));
          idx = currentProject.getTracks().size();
          currentProject.addTrack(synth);
          stack.push(
              new Consequence.TrackStructureConsequence(
                  Consequence.TrackStructureConsequence.ADD, idx, synth, "Add synth track"));
        }
        case "AUDIO" -> {
          AudioTrackModel audio = new AudioTrackModel(name);
          audio.addClip(new ClipModel("CLIP 1", 1, 16));
          idx = currentProject.getTracks().size();
          currentProject.addTrack(audio);
          stack.push(
              new Consequence.TrackStructureConsequence(
                  Consequence.TrackStructureConsequence.ADD, idx, audio, "Add audio track"));
        }
      }
      propagateCurrentModel();
    }

    @Override
    public void onPlayToggle() {
      long nextPlay = vm.getGlobalInt(BridgeContract.G_PLAY) == 1L ? 0L : 1L;
      vm.setGlobalInt(BridgeContract.G_PLAY, nextPlay);
      if (bridge != null) bridge.setPlayState((int) nextPlay);
    }

    @Override
    public void onStop() {
      vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
      if (bridge != null) bridge.setPlayState(0);
    }

    @Override
    public void onMasterVolumeChanged(float vol) {
      vm.setGlobalFloat(BridgeContract.G_MASTER_VOL, vol);
    }
  }

  /** Propagates ProjectModel changes to bridge globals via MVC listener pattern. */
  private class BridgeProjectListener
      implements org.chuck.deluge.model.ProjectModel.ProjectListener {
    private final org.chuck.deluge.model.ProjectModel model;

    BridgeProjectListener(org.chuck.deluge.model.ProjectModel model) {
      this.model = model;
    }

    @Override
    public void onTrackListChanged() {
      pushModelToBridge();
      propagateCurrentModel();
      refreshGrids();
    }

    @Override
    public void onBpmChanged(float bpm) {
      vm.setGlobalFloat(BridgeContract.G_BPM, bpm);
    }

    @Override
    public void onSwingChanged(float swing) {
      vm.setGlobalFloat(BridgeContract.G_SWING, swing);
    }

    @Override
    public void onMasterVolumeChanged(float vol) {
      vm.setGlobalFloat(BridgeContract.G_MASTER_VOL, vol);
    }

    @Override
    public void onMasterPanChanged(float pan) {
      vm.setGlobalFloat(BridgeContract.G_MASTER_PAN, pan);
    }

    @Override
    public void onKeyChanged(String key) {
      vm.setGlobalInt(BridgeContract.G_ROOT_KEY, parseRootKey(key));
    }

    @Override
    public void onScaleChanged(String scale) {
      vm.setGlobalInt(BridgeContract.G_SCALE, parseScaleIndex(scale));
    }

    @Override
    public void onTransposeChanged(int transpose) {
      vm.setGlobalInt(BridgeContract.G_TRANSPOSE, transpose);
    }

    @Override
    public void onHumanizeChanged(float humanize) {
      vm.setGlobalFloat(BridgeContract.G_HUMANIZE, humanize);
    }

    @Override
    public void onReverbChanged() {
      vm.setGlobalFloat(BridgeContract.G_REVERB_ROOM, model.getReverbRoomSize());
      vm.setGlobalFloat(BridgeContract.G_REVERB_DAMP, model.getReverbDampening());
      vm.setGlobalFloat(BridgeContract.G_REVERB_WIDTH, model.getReverbWidth());
      vm.setGlobalFloat(BridgeContract.G_REVERB_HPF, model.getReverbHpf());
      vm.setGlobalFloat(BridgeContract.G_REVERB_PAN, model.getReverbPan());
      vm.setGlobalInt(BridgeContract.G_REVERB_MODEL, model.getReverbModel());
      vm.setGlobalFloat(BridgeContract.G_REVERB_COMP_ATTACK, model.getReverbCompressorAttack());
      vm.setGlobalFloat(BridgeContract.G_REVERB_COMP_RELEASE, model.getReverbCompressorRelease());
      vm.setGlobalInt(
          BridgeContract.G_REVERB_COMP_SYNC_LEVEL, model.getReverbCompressorSyncLevel());
      vm.setGlobalFloat(BridgeContract.G_REVERB_COMP_HPF, model.getReverbCompHpf());
      vm.setGlobalFloat(BridgeContract.G_REVERB_COMP_BLEND, model.getReverbCompBlend());
    }

    @Override
    public void onDelayChanged() {
      vm.setGlobalFloat(BridgeContract.G_DELAY_TIME, model.getMasterDelay());
      vm.setGlobalFloat(BridgeContract.G_DELAY_FB, model.getSongParamDelayFeedback());
      vm.setGlobalInt(BridgeContract.G_DELAY_PINGPONG, model.getDelayPingPong());
      vm.setGlobalInt(BridgeContract.G_DELAY_ANALOG, model.getDelayAnalog());
      vm.setGlobalInt(BridgeContract.G_DELAY_SYNC_LEVEL, model.getDelaySyncLevel());
      vm.setGlobalInt(BridgeContract.G_DELAY_SYNC_TYPE, model.getDelaySyncType());
    }

    @Override
    public void onSidechainChanged() {
      vm.setGlobalFloat(BridgeContract.G_SIDECHAIN_ATTACK, model.getSidechainAttack());
      vm.setGlobalFloat(BridgeContract.G_SIDECHAIN_RELEASE, model.getSidechainRelease());
      vm.setGlobalInt(BridgeContract.G_SIDECHAIN_SYNC_LEVEL, model.getSidechainSyncLevel());
      vm.setGlobalInt(BridgeContract.G_SIDECHAIN_SYNC_TYPE, model.getSidechainSyncType());
    }

    @Override
    public void onCompressorChanged() {
      vm.setGlobalFloat(BridgeContract.G_MASTER_COMP, model.getCompressorThreshold());
      vm.setGlobalFloat(BridgeContract.G_MASTER_COMP_ATTACK, model.getCompressorAttack());
      vm.setGlobalFloat(BridgeContract.G_MASTER_COMP_RELEASE, model.getCompressorRelease());
      vm.setGlobalFloat(BridgeContract.G_MASTER_COMP_RATIO, model.getCompressorRatio());
      vm.setGlobalFloat(BridgeContract.G_MASTER_COMP_BLEND, model.getCompressorBlend());

      if (masterFxPanel != null) {
        masterFxPanel.updateCompressorUI(
            model.getCompressorThreshold(),
            model.getCompressorAttack(),
            model.getCompressorRelease(),
            model.getCompressorRatio(),
            model.getCompressorBlend());
      }
    }

    @Override
    public void onSongParamsChanged() {
      vm.setGlobalFloat(BridgeContract.G_SP_VOLUME, model.getSongParamVolume());
      vm.setGlobalFloat(BridgeContract.G_SP_PAN, model.getSongParamPan());
      vm.setGlobalFloat(BridgeContract.G_SP_REVERB_AMOUNT, model.getSongParamReverbAmount());
      vm.setGlobalFloat(BridgeContract.G_SP_DELAY_RATE, model.getSongParamDelayRate());
      vm.setGlobalFloat(BridgeContract.G_SP_DELAY_FEEDBACK, model.getSongParamDelayFeedback());
      vm.setGlobalFloat(BridgeContract.G_SP_SIDECHAIN_SHAPE, model.getSongParamSidechainShape());
      vm.setGlobalFloat(BridgeContract.G_SP_STUTTER_RATE, model.getSongParamStutterRate());
      vm.setGlobalFloat(
          BridgeContract.G_SP_SAMPLE_RATE_REDUCTION, model.getSongParamSampleRateReduction());
      vm.setGlobalFloat(BridgeContract.G_SP_BITCRUSH, model.getSongParamBitCrush());
      vm.setGlobalFloat(BridgeContract.G_SP_MOD_FX_RATE, model.getSongParamModFXRate());
      vm.setGlobalFloat(BridgeContract.G_SP_MOD_FX_DEPTH, model.getSongParamModFXDepth());
      vm.setGlobalFloat(BridgeContract.G_SP_MOD_FX_OFFSET, model.getSongParamModFXOffset());
      vm.setGlobalFloat(BridgeContract.G_SP_MOD_FX_FEEDBACK, model.getSongParamModFXFeedback());
      vm.setGlobalFloat(
          BridgeContract.G_SP_COMPRESSOR_THRESHOLD, model.getSongParamCompressorThreshold());
      vm.setGlobalFloat(BridgeContract.G_SP_LPF_MORPH, model.getSongParamLpfMorph());
      vm.setGlobalFloat(BridgeContract.G_SP_HPF_MORPH, model.getSongParamHpfMorph());
      vm.setGlobalFloat(BridgeContract.G_SP_LPF_FREQ, model.getSongParamLpfFrequency());
      vm.setGlobalFloat(BridgeContract.G_SP_LPF_RES, model.getSongParamLpfResonance());
      vm.setGlobalFloat(BridgeContract.G_SP_HPF_FREQ, model.getSongParamHpfFrequency());
      vm.setGlobalFloat(BridgeContract.G_SP_HPF_RES, model.getSongParamHpfResonance());
      vm.setGlobalFloat(BridgeContract.G_SP_EQ_BASS, model.getSongParamEqBass());
      vm.setGlobalFloat(BridgeContract.G_SP_EQ_TREBLE, model.getSongParamEqTreble());
      vm.setGlobalFloat(BridgeContract.G_SP_EQ_BASS_FREQ, model.getSongParamEqBassFrequency());
      vm.setGlobalFloat(BridgeContract.G_SP_EQ_TREBLE_FREQ, model.getSongParamEqTrebleFrequency());
    }

    @Override
    public void onScalesChanged() {
      vm.setGlobalInt(BridgeContract.G_USER_SCALE, model.getUserScale());
      vm.setGlobalInt(BridgeContract.G_DISABLED_PRESET_SCALES, model.getDisabledPresetScales());
      boolean[] modeNotes = model.getModeNotes();
      if (modeNotes != null) {
        for (int i = 0; i < 12 && i < modeNotes.length; i++) {
          vm.setGlobalInt(BridgeContract.G_MODE_NOTES + "_" + i, modeNotes[i] ? 1L : 0L);
        }
      }
    }
  }

  // ── Helper methods ──

  private void pushKitEnv(int engineRow, int envIndex, EnvelopeModel env) {
    if (env == null) return;
    bridge.setEnv(engineRow, envIndex, env.attack(), env.decay(), env.sustain(), env.release());
  }

  private static int lpfModeOrdinal(org.chuck.deluge.model.FilterMode mode) {
    if (mode == null) return 0;
    return mode.ordinal();
  }

  private static int oscTypeOrdinal(String type) {
    if (type == null) return 0;
    return switch (type) {
      case "SINE" -> 0;
      case "SAW" -> 1;
      case "SQUARE" -> 2;
      case "TRIANGLE" -> 3;
      case "NOISE" -> 4;
      default -> 0;
    };
  }

  private static int modFxTypeOrdinal(String type) {
    if (type == null) return 0;
    return switch (type) {
      case "NONE" -> 0;
      case "CHORUS" -> 1;
      case "FLANGER" -> 2;
      case "PHASER" -> 3;
      default -> 0;
    };
  }

  /** Convert a root-key string ("C", "C#", "D", etc.) to a 0–11 MIDI note number. */
  static int parseRootKey(String key) {
    if (key == null) return 0;
    return switch (key) {
      case "C" -> 0;
      case "C#", "Db" -> 1;
      case "D" -> 2;
      case "D#", "Eb" -> 3;
      case "E" -> 4;
      case "F" -> 5;
      case "F#", "Gb" -> 6;
      case "G" -> 7;
      case "G#", "Ab" -> 8;
      case "A" -> 9;
      case "A#", "Bb" -> 10;
      case "B" -> 11;
      default -> 0;
    };
  }

  /** Convert a scale name to an integer index for the bridge G_SCALE global. */
  static int parseScaleIndex(String scale) {
    if (scale == null) return 0;
    return switch (scale) {
      case "Major" -> 0;
      case "Minor" -> 1;
      case "Harmonic Minor" -> 2;
      case "Melodic Minor" -> 3;
      case "Dorian" -> 4;
      case "Phrygian" -> 5;
      case "Lydian" -> 6;
      case "Mixolydian" -> 7;
      case "Locrian" -> 8;
      case "Whole Tone" -> 9;
      case "Whole Half Dim" -> 10;
      case "Half Whole Dim" -> 11;
      case "Pentatonic Major" -> 12;
      case "Pentatonic Minor" -> 13;
      case "Chromatic" -> 14;
      default -> 0;
    };
  }
}
