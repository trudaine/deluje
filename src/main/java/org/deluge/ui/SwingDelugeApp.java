package org.deluge.ui;

import java.awt.*;
import java.io.IOException;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.swing.*;
import org.deluge.BridgeContract;
import org.deluge.engine.FirmwareFactory;
import org.deluge.hid.Flasher;
import org.deluge.hid.MatrixDriver;
import org.deluge.hid.pic.PIC;
import org.deluge.model.AudioTrackModel;
import org.deluge.model.ClipModel;
import org.deluge.model.Consequence;
import org.deluge.model.EnvelopeModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.PatternModel;
import org.deluge.model.SoundDrum;
import org.deluge.model.SynthTrackModel;
import org.deluge.ui.views.KitView;
import org.deluge.ui.views.PianoRollView;
import org.deluge.ui.views.SessionView;

/** Alternative lightweight UI running purely on Java Swing (no native libs). */
public class SwingDelugeApp extends JFrame {
  public static SwingDelugeApp mainInstance;
  public static boolean pureModeActive = false;
  private final BridgeContract bridge;

  private SwingGridPanel clipPanel;
  private SwingVisualizerPanel visualizerPanel;
  private SwingGridPanel songPanel;
  private SwingGridPanel arrGridPanel;
  private SwingGridPanel autoPanel;
  private SwingPerformanceViewPanel performancePanel;

  private SwingTopBarPanel topBar;
  private AppTopBarListener appTopBarListener;
  private SwingMasterFxPanel masterFxPanel;
  private SynthParamRack synthParamRack;
  private javax.swing.JScrollPane rackScroll;
  private org.deluge.ui.controls.DelugeModKnobBar modKnobBar;

  private JPanel centerCardPanel;

  /**
   * When true, the boot auto-screenshot pipeline runs (it cycles grid modes 8x16/24x16 to capture
   * dev screenshots). Off for normal launches — otherwise the user sees the grid visibly resize 2-3
   * times on every boot. Enabled only by the {@code --screenshot} CLI flag.
   */
  public static boolean autoScreenshotOnBoot = false;

  /** Wrap a SwingGridPanel with a small top indent so cells aren't flush with the top border. */
  private static JPanel wrapGridPanel(SwingGridPanel grid) {
    grid.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
    return grid;
  }

  private CardLayout cardLayout;

  private String activeViewMode = "CLIP";
  private org.deluge.model.ProjectModel currentProject =
      org.deluge.model.ProjectModel.createDefaultProject();
  private java.io.File currentProjectFile = null;

  private javax.swing.JRadioButtonMenuItem grid8x16Item;
  private javax.swing.JRadioButtonMenuItem grid16x16Item;
  private javax.swing.JRadioButtonMenuItem grid24x16Item;
  private javax.swing.JRadioButtonMenuItem grid16x24Item;

  public org.deluge.model.ProjectModel getCurrentProject() {
    return currentProject;
  }

  public void triggerPlayToggle() {
    if (appTopBarListener != null) {
      appTopBarListener.onPlayToggle();
    }
  }

  public boolean isPlaying() {
    return bridge != null && bridge.getGlobalInt(BridgeContract.G_PLAY) == 1L;
  }

  // Engine voice mapping: for each model track, the start engine row and voice count.
  // Kit tracks occupy getDrums().size() rows; Synth tracks occupy 1 row.
  private int[] trackEngineStart;
  private int[] trackVoiceCount;

  private final org.deluge.midi.MidiService midiService;
  private SwingProjectSidebarPanel sidebarPanel;
  private SwingProjectSidebarPanel floatingSidebar;
  private org.deluge.hid.pic.SwingPicTransport picTransport;
  private JDialog leftFloat;
  private JDialog rightFloat;
  private JCheckBoxMenuItem showMonitorItem;
  private final java.util.ArrayDeque<Long> tapTimes = new java.util.ArrayDeque<>();
  private Timer visualizerRepaintTimer;
  private Timer picTransportFlushTimer;
  private Timer statusTextPlaybackTimer;

  /** Recompute trackEngineStart and trackVoiceCount from the current project model. */
  private void computeEngineMapping() {
    java.util.List<org.deluge.model.TrackModel> tracks = currentProject.getTracks();
    int n = tracks.size();
    trackEngineStart = new int[n];
    trackVoiceCount = new int[n];
    int nextRow = 0;
    for (int t = 0; t < n && nextRow < BridgeContract.TRACKS; t++) {
      trackEngineStart[t] = nextRow;
      boolean isKit = tracks.get(t) instanceof org.deluge.model.KitTrackModel;
      boolean isMidi = tracks.get(t) instanceof org.deluge.model.MidiTrackModel;
      int voices =
          isMidi
              ? 0
              : (isKit ? ((org.deluge.model.KitTrackModel) tracks.get(t)).getDrums().size() : 8);
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
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_LPF_FREQ, s))
        br.setStepFilter(
            engRow, s, clip.getAutomation(org.deluge.model.AutomationParam.A_LPF_FREQ, s), clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_LPF_RES, s))
        br.setStepRes(
            engRow, s, clip.getAutomation(org.deluge.model.AutomationParam.A_LPF_RES, s), clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_PAN, s))
        br.setStepPan(
            engRow, s, clip.getAutomation(org.deluge.model.AutomationParam.A_PAN, s), clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_DELAY, s))
        br.setStepDelay(
            engRow, s, clip.getAutomation(org.deluge.model.AutomationParam.A_DELAY, s), clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_REVERB, s))
        br.setStepReverb(
            engRow, s, clip.getAutomation(org.deluge.model.AutomationParam.A_REVERB, s), clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_HPF_FREQ, s))
        br.setStepHpfFreq(
            engRow, s, clip.getAutomation(org.deluge.model.AutomationParam.A_HPF_FREQ, s), clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_HPF_RES, s))
        br.setStepHpfRes(
            engRow, s, clip.getAutomation(org.deluge.model.AutomationParam.A_HPF_RES, s), clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_MOD_FX_RATE, s))
        br.setStepModRate(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_MOD_FX_RATE, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_MOD_FX_DEPTH, s))
        br.setStepModDepth(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_MOD_FX_DEPTH, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_OSC_A_VOL, s))
        br.setStepOscAVol(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_OSC_A_VOL, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_OSC_B_VOL, s))
        br.setStepOscBVol(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_OSC_B_VOL, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_NOISE_VOL, s))
        br.setStepNoiseVol(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_NOISE_VOL, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_PITCH, s))
        br.setStepPitch(
            engRow, s, clip.getAutomation(org.deluge.model.AutomationParam.A_PITCH, s), clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_VOLUME, s))
        br.setStepVolume(
            engRow, s, clip.getAutomation(org.deluge.model.AutomationParam.A_VOLUME, s), clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_ENV_0_ATTACK, s))
        br.setStepEnv0Attack(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_ENV_0_ATTACK, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_ENV_0_DECAY, s))
        br.setStepEnv0Decay(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_ENV_0_DECAY, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_ENV_0_SUSTAIN, s))
        br.setStepEnv0Sustain(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_ENV_0_SUSTAIN, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_ENV_0_RELEASE, s))
        br.setStepEnv0Release(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_ENV_0_RELEASE, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_ENV_1_ATTACK, s))
        br.setStepEnv1Attack(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_ENV_1_ATTACK, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_ENV_1_DECAY, s))
        br.setStepEnv1Decay(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_ENV_1_DECAY, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_ENV_1_SUSTAIN, s))
        br.setStepEnv1Sustain(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_ENV_1_SUSTAIN, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_ENV_1_RELEASE, s))
        br.setStepEnv1Release(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_ENV_1_RELEASE, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_ENV_2_ATTACK, s))
        br.setStepEnv2Attack(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_ENV_2_ATTACK, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_ENV_2_DECAY, s))
        br.setStepEnv2Decay(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_ENV_2_DECAY, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_ENV_2_SUSTAIN, s))
        br.setStepEnv2Sustain(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_ENV_2_SUSTAIN, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_ENV_2_RELEASE, s))
        br.setStepEnv2Release(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_ENV_2_RELEASE, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_ENV_3_ATTACK, s))
        br.setStepEnv3Attack(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_ENV_3_ATTACK, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_ENV_3_DECAY, s))
        br.setStepEnv3Decay(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_ENV_3_DECAY, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_ENV_3_SUSTAIN, s))
        br.setStepEnv3Sustain(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_ENV_3_SUSTAIN, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_ENV_3_RELEASE, s))
        br.setStepEnv3Release(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_ENV_3_RELEASE, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_LFO_0_RATE, s))
        br.setStepLfo0Rate(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_LFO_0_RATE, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_LFO_0_DEPTH, s))
        br.setStepLfo0Depth(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_LFO_0_DEPTH, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_LFO_1_RATE, s))
        br.setStepLfo1Rate(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_LFO_1_RATE, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_LFO_1_DEPTH, s))
        br.setStepLfo1Depth(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_LFO_1_DEPTH, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_LFO_2_RATE, s))
        br.setStepLfo2Rate(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_LFO_2_RATE, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_LFO_2_DEPTH, s))
        br.setStepLfo2Depth(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_LFO_2_DEPTH, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_LFO_3_RATE, s))
        br.setStepLfo3Rate(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_LFO_3_RATE, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_LFO_3_DEPTH, s))
        br.setStepLfo3Depth(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_LFO_3_DEPTH, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_ARP_RATE, s))
        br.setStepArpRate(
            engRow, s, clip.getAutomation(org.deluge.model.AutomationParam.A_ARP_RATE, s), clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_ARP_GATE, s))
        br.setStepArpGate(
            engRow, s, clip.getAutomation(org.deluge.model.AutomationParam.A_ARP_GATE, s), clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_FM_AMOUNT, s))
        br.setStepFmAmount(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_FM_AMOUNT, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_FM_RATIO, s))
        br.setStepFmRatio(
            engRow, s, clip.getAutomation(org.deluge.model.AutomationParam.A_FM_RATIO, s), clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_MOD_FX_FEEDBACK, s))
        br.setStepModFxFeedback(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_MOD_FX_FEEDBACK, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_COMP_ATTACK, s))
        br.setStepCompAttack(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_COMP_ATTACK, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_COMP_RELEASE, s))
        br.setStepCompRelease(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_COMP_RELEASE, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_PORTAMENTO, s))
        br.setStepPortamento(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_PORTAMENTO, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_STUTTER_RATE, s))
        br.setStepStutter(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_STUTTER_RATE, s),
            clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_BITCRUSH, s))
        br.setStepBitcrush(
            engRow, s, clip.getAutomation(org.deluge.model.AutomationParam.A_BITCRUSH, s), clipIdx);
      if (clip.hasAutomation(org.deluge.model.AutomationParam.A_SAMPLE_RATE_RED, s))
        br.setStepSrr(
            engRow,
            s,
            clip.getAutomation(org.deluge.model.AutomationParam.A_SAMPLE_RATE_RED, s),
            clipIdx);
    }
  }

  public org.deluge.midi.MidiService getMidiService() {
    return midiService;
  }

  public JDialog getLeftFloat() {
    return leftFloat;
  }

  public void pushModelToBridge() {
    computeEngineMapping();
    java.util.List<org.deluge.model.TrackModel> tracks = currentProject.getTracks();

    // Clear all engine rows first
    bridge.clearPattern();
    for (int i = 0; i < BridgeContract.TRACKS; i++) {
      bridge.setMute(i, false);
      bridge.setTrackLength(i, 16);
    }

    // Initialize both local bridge and VM global track types to -1 (unused)
    for (int i = 0; i < BridgeContract.TRACKS; i++) bridge.setTrackType(i, -1);

    org.deluge.shadow.core.ChuckArray trackTypeArr =
        (org.deluge.shadow.core.ChuckArray) bridge.getGlobalObject(BridgeContract.G_TRACK_TYPE);
    if (trackTypeArr != null) {
      // Initialize all to -1 (unused)
      for (int i = 0; i < BridgeContract.TRACKS; i++) trackTypeArr.setInt(i, -1L);
    }

    for (int t = 0; t < tracks.size(); t++) {
      org.deluge.model.TrackModel track = tracks.get(t);
      int startRow = trackEngineStart[t];
      int voiceCount = trackVoiceCount[t];

      int rowsToSet = voiceCount;
      if (track instanceof org.deluge.model.SynthTrackModel synth) {
        int activeClipIdx = synth.getActiveClipIndex();
        if (activeClipIdx >= 0 && activeClipIdx < synth.getClips().size()) {
          rowsToSet = Math.max(rowsToSet, synth.getClips().get(activeClipIdx).getRowCount());
        }
      }
      for (int v = 0; v < rowsToSet; v++) {
        bridge.setTrackId(startRow + v, t);
      }

      if (track instanceof org.deluge.model.KitTrackModel kit) {
        // Mark engine rows as type-0 (kit)
        for (int v = 0; v < voiceCount; v++) {
          bridge.setTrackType(startRow + v, 0);
          if (trackTypeArr != null) trackTypeArr.setInt(startRow + v, 0L);
        }

        // Push each drum: sample path, pitch, mute group, reverse, ADSR
        java.util.List<org.deluge.model.Drum> sounds = kit.getDrums();
        for (int v = 0; v < voiceCount; v++) {
          int engineRow = startRow + v;
          String path =
              v < sounds.size()
                  ? ((org.deluge.model.SoundDrum) sounds.get(sounds.size() - 1 - v)).getSamplePath()
                  : "";
          bridge.setGlobalString("g_sample_" + engineRow, path);
          bridge.setSamplePath(engineRow, path);

          // ── Zone (sample truncation) ──
          if (v < sounds.size()) {
            org.deluge.model.SoundDrum snd =
                (org.deluge.model.SoundDrum) sounds.get(sounds.size() - 1 - v);
            float[] range = BridgeContract.computeNormalizedRange(snd, path);
            if (range[0] > 0.0f || range[1] < 1.0f) {
              bridge.setSampleRange(engineRow, range[0], range[1]);
            }
          }

          if (v < sounds.size()) {
            org.deluge.model.SoundDrum snd =
                (org.deluge.model.SoundDrum) sounds.get(sounds.size() - 1 - v);
            try {
              ((org.deluge.shadow.core.ChuckArray)
                      bridge.getGlobalObject(BridgeContract.G_KIT_PITCH))
                  .setFloat(engineRow, snd.getPitchSemitones());
              ((org.deluge.shadow.core.ChuckArray)
                      bridge.getGlobalObject(BridgeContract.G_KIT_MUTE_GROUP))
                  .setInt(engineRow, (long) snd.getMuteGroup());
              ((org.deluge.shadow.core.ChuckArray)
                      bridge.getGlobalObject(BridgeContract.G_KIT_REVERSE))
                  .setInt(engineRow, snd.isReverse() ? 1L : 0L);
              org.deluge.model.EnvelopeModel adsr = snd.getAdsr();
              if (adsr != null) {
                ((org.deluge.shadow.core.ChuckArray)
                        bridge.getGlobalObject(BridgeContract.G_KIT_ATTACK))
                    .setFloat(engineRow, adsr.attack());
                ((org.deluge.shadow.core.ChuckArray)
                        bridge.getGlobalObject(BridgeContract.G_KIT_DECAY))
                    .setFloat(engineRow, adsr.decay());
                ((org.deluge.shadow.core.ChuckArray)
                        bridge.getGlobalObject(BridgeContract.G_KIT_SUSTAIN))
                    .setFloat(engineRow, adsr.sustain());
                ((org.deluge.shadow.core.ChuckArray)
                        bridge.getGlobalObject(BridgeContract.G_KIT_RELEASE))
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

            } catch (Exception ex) {
              System.err.println(
                  "[pushModel] kit param error at row " + engineRow + ": " + ex.getMessage());
            }
          }
        }

        // Push clip pattern data for the active clip
        int activeClipIdx = kit.getActiveClipIndex();
        if (activeClipIdx >= 0 && activeClipIdx < kit.getClips().size()) {
          org.deluge.model.ClipModel clip = kit.getClips().get(activeClipIdx);
          int stepCount = clip.getStepCount();
          for (int r = 0; r < clip.getRowCount(); r++) {
            for (int s = 0; s < stepCount; s++) {
              org.deluge.model.StepData step = clip.getStep(r, s);
              if (step != null && step.active() && r < voiceCount) {
                bridge.setStep(startRow + r, s, true);
                bridge.setVelocity(startRow + r, s, step.velocity());
                bridge.setIterance(startRow + r, s, step.iterance());
                bridge.setStepFill(startRow + r, s, step.fill());
              }
            }
          }
        }
      } else if (track instanceof org.deluge.model.SynthTrackModel synth) {
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
          org.deluge.model.ClipModel clip = synth.getClips().get(activeClipIdx);
          int stepCount = clip.getStepCount();
          for (int r = 0; r < clip.getRowCount(); r++) {
            int engineRow = startRow + r;
            for (int s = 0; s < stepCount; s++) {
              org.deluge.model.StepData step = clip.getStep(r, s);
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
        org.deluge.shadow.core.ChuckArray oscTypeArr =
            (org.deluge.shadow.core.ChuckArray) bridge.getGlobalObject(BridgeContract.G_OSC_TYPE);
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
          org.deluge.model.FilterMode fm = synth.getFilterMode();
          int fmIdx = (fm != null) ? fm.ordinal() : org.deluge.model.FilterMode.LADDER_24.ordinal();
          bridge.setFilterMode(startRow + v, fmIdx);
          bridge.setFilterMorph(startRow + v, synth.getLpfMorph());
          bridge.setFilterDrive(startRow + v, synth.getFilterDrive());
          bridge.setFilterNotch(startRow + v, synth.isFilterNotch() ? 1 : 0);
          bridge.setFilterRoute(startRow + v, synth.getFilterRoute());
          bridge.setMaxVoices(startRow + v, synth.getMaxVoiceCount());
        }

        // ── Push new synth fields (volume, pan, oscMix, noise, unison, modFX, etc.) ──
        for (int v = 0; v < totalSynthRows; v++) {
          bridge.setTrackLevel(startRow + v, synth.getVolume());
        }
        for (int v = 0; v < totalSynthRows; v++) {
          bridge.setDelaySend(startRow + v, synth.getDelaySend());
          bridge.setReverbSend(startRow + v, synth.getReverbSend());
        }

        // ── DX7 patch (per-row string global, read by engine) ──
        String dx7patch = synth.getDx7Patch();
        if (dx7patch != null && !dx7patch.isEmpty()) {
          for (int v = 0; v < totalSynthRows; v++) {
            bridge.setGlobalString("g_dx7_patch_" + (startRow + v), dx7patch);
          }
          // Push opSwitch mask (byte 155) so UI edits to operator on/off are reflected
          try {
            byte[] raw = org.deluge.shadow.audio.Dx7Patch.hexToBytes(dx7patch);
            int opSwitch = raw[org.deluge.shadow.audio.Dx7Patch.OFF_OP_SWITCH] & 0xFF;
            for (int v = 0; v < totalSynthRows; v++) {
              bridge.setGlobalInt("g_dx7_opSwitch_" + (startRow + v), opSwitch);
            }
          } catch (Exception ignored) {
          }
        }
      } else if (track instanceof org.deluge.model.AudioTrackModel audio) {
        // Mark engine row as type-2 (audio)
        bridge.setTrackType(startRow, 2);
        if (trackTypeArr != null) trackTypeArr.setInt(startRow, 2L);
        // Push audio threshold params
        bridge.setAudioThreshold(startRow, audio.getThresholdMode());
        bridge.setAudioThresholdLevel(startRow, audio.getThresholdLevel());
        // Push clip pattern data
        int activeClipIdx = audio.getActiveClipIndex();
        if (activeClipIdx >= 0 && activeClipIdx < audio.getClips().size()) {
          org.deluge.model.ClipModel clip = audio.getClips().get(activeClipIdx);
          int stepCount = clip.getStepCount();
          for (int r = 0; r < clip.getRowCount() && r < voiceCount; r++) {
            for (int s = 0; s < stepCount; s++) {
              org.deluge.model.StepData step = clip.getStep(r, s);
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
          org.deluge.model.AudioTrackModel.AudioClip aClip = audio.getAudioClips().get(aClipIdx);
          bridge.setAudioPlay(startRow, aClip.isPlaying() ? 1 : 0);
          bridge.setAudioLoop(startRow, aClip.isPlaying() || audio.isLooping() ? 1 : 0);
          bridge.setAudioRate(startRow, audio.getPlayRate());
          // Push sample position globals for LiSa playback region
          bridge.setGlobalFloat(
              "g_audio_clip_start_" + startRow, (float) aClip.getStartSamplePos());
          bridge.setGlobalFloat("g_audio_clip_end_" + startRow, (float) aClip.getEndSamplePos());
          // Push audio file path for LiSa sample loading
          String filePath = aClip.getFilePath();
          if (filePath != null && !filePath.isEmpty()) {
            bridge.setGlobalString("g_audio_file_path_" + startRow, filePath);
          }
        }
      }

      // Track length and stepCount for all rows of this track
      int rowLen = track.getClips().isEmpty() ? 16 : track.getClips().get(0).getStepCount();
      int totalRows = voiceCount;
      if (track instanceof org.deluge.model.SynthTrackModel synthTrackModel) {
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
      org.deluge.model.TrackModel track = tracks.get(t);
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
            org.deluge.model.StepData step = clip.getStep(r, s);
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
        // Push per-clip play direction (0=FORWARD, 1=REVERSE, 2=PING_PONG, 3=RANDOM)
        bridge.setClipPlayDirection(t, c, clip.getPlayDirection().ordinal());
      }
    }

    // ── Push master-level globals (BPM, swing, volume, pan, reverb, delay, scale, key, comp) ──
    // These are normally propagated by ProjectListener callbacks, but during project load the
    // XML parser populates model fields directly without firing setters, so we sync here too.
    bridge.setGlobalFloat(BridgeContract.G_BPM, currentProject.getBpm());
    bridge.setGlobalFloat(BridgeContract.G_SWING, currentProject.getSwing());
    bridge.setGlobalFloat(BridgeContract.G_MASTER_VOL, currentProject.getMasterVolume());
    bridge.setGlobalFloat(BridgeContract.G_MASTER_PAN, currentProject.getMasterPan());
    bridge.setGlobalFloat(BridgeContract.G_DELAY_TIME, currentProject.getMasterDelay());
    bridge.setGlobalFloat(BridgeContract.G_DELAY_FB, currentProject.getSongParamDelayFeedback());
    bridge.setGlobalFloat(BridgeContract.G_REVERB_ROOM, currentProject.getReverbRoomSize());
    bridge.setGlobalFloat(BridgeContract.G_REVERB_DAMP, currentProject.getReverbDampening());
    bridge.setGlobalFloat(BridgeContract.G_MASTER_COMP, currentProject.getCompressorThreshold());
    bridge.setGlobalInt(BridgeContract.G_ROOT_KEY, parseRootKey(currentProject.getKey()));
    bridge.setGlobalInt(BridgeContract.G_SCALE, parseScaleIndex(currentProject.getScale()));
    bridge.setGlobalFloat(BridgeContract.G_PREVIEW_PITCH, 60.0f);

    // ── Extended reverb globals ──
    bridge.setGlobalFloat(BridgeContract.G_REVERB_WIDTH, currentProject.getReverbWidth());
    bridge.setGlobalFloat(BridgeContract.G_REVERB_HPF, currentProject.getReverbHpf());
    bridge.setGlobalFloat(BridgeContract.G_REVERB_PAN, currentProject.getReverbPan());
    bridge.setGlobalInt(BridgeContract.G_REVERB_MODEL, currentProject.getReverbModel());
    bridge.setGlobalFloat(
        BridgeContract.G_REVERB_COMP_ATTACK, currentProject.getReverbCompressorAttack());
    bridge.setGlobalFloat(
        BridgeContract.G_REVERB_COMP_RELEASE, currentProject.getReverbCompressorRelease());
    bridge.setGlobalInt(
        BridgeContract.G_REVERB_COMP_SYNC_LEVEL, currentProject.getReverbCompressorSyncLevel());
    bridge.setGlobalFloat(BridgeContract.G_REVERB_COMP_HPF, currentProject.getReverbCompHpf());
    bridge.setGlobalFloat(BridgeContract.G_REVERB_COMP_BLEND, currentProject.getReverbCompBlend());

    // ── Extended delay globals ──
    bridge.setGlobalInt(BridgeContract.G_DELAY_PINGPONG, currentProject.getDelayPingPong());
    bridge.setGlobalInt(BridgeContract.G_DELAY_ANALOG, currentProject.getDelayAnalog());
    bridge.setGlobalInt(BridgeContract.G_DELAY_SYNC_LEVEL, currentProject.getDelaySyncLevel());
    bridge.setGlobalInt(BridgeContract.G_DELAY_SYNC_TYPE, currentProject.getDelaySyncType());

    // ── Sidechain globals ──
    bridge.setGlobalFloat(BridgeContract.G_SIDECHAIN_ATTACK, currentProject.getSidechainAttack());
    bridge.setGlobalFloat(BridgeContract.G_SIDECHAIN_RELEASE, currentProject.getSidechainRelease());
    bridge.setGlobalInt(
        BridgeContract.G_SIDECHAIN_SYNC_LEVEL, currentProject.getSidechainSyncLevel());
    bridge.setGlobalInt(
        BridgeContract.G_SIDECHAIN_SYNC_TYPE, currentProject.getSidechainSyncType());

    // ── Master compressor (extended) globals ──
    bridge.setGlobalFloat(
        BridgeContract.G_MASTER_COMP_ATTACK, currentProject.getCompressorAttack());
    bridge.setGlobalFloat(
        BridgeContract.G_MASTER_COMP_RELEASE, currentProject.getCompressorRelease());
    bridge.setGlobalFloat(BridgeContract.G_MASTER_COMP_RATIO, currentProject.getCompressorRatio());
    bridge.setGlobalFloat(BridgeContract.G_MASTER_COMP_BLEND, currentProject.getCompressorBlend());

    // ── Transpose / humanize globals ──
    bridge.setGlobalInt(BridgeContract.G_TRANSPOSE, currentProject.getTranspose());
    bridge.setGlobalFloat(BridgeContract.G_HUMANIZE, currentProject.getHumanize());

    // ── SongParams globals ──
    bridge.setGlobalFloat(BridgeContract.G_SP_VOLUME, currentProject.getSongParamVolume());
    bridge.setGlobalFloat(BridgeContract.G_SP_PAN, currentProject.getSongParamPan());
    bridge.setGlobalFloat(
        BridgeContract.G_SP_REVERB_AMOUNT, currentProject.getSongParamReverbAmount());
    bridge.setGlobalFloat(BridgeContract.G_SP_DELAY_RATE, currentProject.getSongParamDelayRate());
    bridge.setGlobalFloat(
        BridgeContract.G_SP_DELAY_FEEDBACK, currentProject.getSongParamDelayFeedback());
    bridge.setGlobalFloat(
        BridgeContract.G_SP_SIDECHAIN_SHAPE, currentProject.getSongParamSidechainShape());
    bridge.setGlobalFloat(
        BridgeContract.G_SP_STUTTER_RATE, currentProject.getSongParamStutterRate());
    bridge.setGlobalFloat(
        BridgeContract.G_SP_SAMPLE_RATE_REDUCTION,
        currentProject.getSongParamSampleRateReduction());
    bridge.setGlobalFloat(BridgeContract.G_SP_BITCRUSH, currentProject.getSongParamBitCrush());
    bridge.setGlobalFloat(BridgeContract.G_SP_MOD_FX_RATE, currentProject.getSongParamModFXRate());
    bridge.setGlobalFloat(
        BridgeContract.G_SP_MOD_FX_DEPTH, currentProject.getSongParamModFXDepth());
    bridge.setGlobalFloat(
        BridgeContract.G_SP_MOD_FX_OFFSET, currentProject.getSongParamModFXOffset());
    bridge.setGlobalFloat(
        BridgeContract.G_SP_MOD_FX_FEEDBACK, currentProject.getSongParamModFXFeedback());
    bridge.setGlobalFloat(
        BridgeContract.G_SP_COMPRESSOR_THRESHOLD, currentProject.getSongParamCompressorThreshold());
    bridge.setGlobalFloat(BridgeContract.G_SP_LPF_MORPH, currentProject.getSongParamLpfMorph());
    bridge.setGlobalFloat(BridgeContract.G_SP_HPF_MORPH, currentProject.getSongParamHpfMorph());
    bridge.setGlobalFloat(BridgeContract.G_SP_LPF_FREQ, currentProject.getSongParamLpfFrequency());
    bridge.setGlobalFloat(BridgeContract.G_SP_LPF_RES, currentProject.getSongParamLpfResonance());
    bridge.setGlobalFloat(BridgeContract.G_SP_HPF_FREQ, currentProject.getSongParamHpfFrequency());
    bridge.setGlobalFloat(BridgeContract.G_SP_HPF_RES, currentProject.getSongParamHpfResonance());
    bridge.setGlobalFloat(BridgeContract.G_SP_EQ_BASS, currentProject.getSongParamEqBass());
    bridge.setGlobalFloat(BridgeContract.G_SP_EQ_TREBLE, currentProject.getSongParamEqTreble());
    bridge.setGlobalFloat(
        BridgeContract.G_SP_EQ_BASS_FREQ, currentProject.getSongParamEqBassFrequency());
    bridge.setGlobalFloat(
        BridgeContract.G_SP_EQ_TREBLE_FREQ, currentProject.getSongParamEqTrebleFrequency());

    // ── Scales globals ──
    bridge.setGlobalInt(BridgeContract.G_USER_SCALE, currentProject.getUserScale());
    bridge.setGlobalInt(
        BridgeContract.G_DISABLED_PRESET_SCALES, currentProject.getDisabledPresetScales());
    boolean[] modeNotes = currentProject.getModeNotes();
    if (modeNotes != null) {
      for (int i = 0; i < 12 && i < modeNotes.length; i++) {
        bridge.setGlobalInt(BridgeContract.G_MODE_NOTES + "_" + i, modeNotes[i] ? 1L : 0L);
      }
    }

    // ── MIDI Follow Mode globals ──
    bridge.setFollowEnabled(midiService != null && midiService.isRecording());
    bridge.setGlobalInt(BridgeContract.G_FOLLOW_CH_A, bridge.getFollowMidChannel('A'));
    bridge.setGlobalInt(BridgeContract.G_FOLLOW_CH_B, bridge.getFollowMidChannel('B'));
    bridge.setGlobalInt(BridgeContract.G_FOLLOW_CH_C, bridge.getFollowMidChannel('C'));
    bridge.setGlobalInt(BridgeContract.G_FOLLOW_TRACK_A, bridge.getFollowTrack('A'));
    bridge.setGlobalInt(BridgeContract.G_FOLLOW_TRACK_B, bridge.getFollowTrack('B'));
    bridge.setGlobalInt(BridgeContract.G_FOLLOW_TRACK_C, bridge.getFollowTrack('C'));

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
      org.deluge.model.TrackModel track = tracks.get(t);
      int clipIdx = track.getActiveClipIndex();
      // Audio tracks use AudioClips (not ClipModel), so check audioClips instead
      if (track instanceof org.deluge.model.AudioTrackModel audioTrk) {
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
      bridge.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    }

    // ── Push hardware character preferences to engine globals ──
    bridge.setGlobalFloat(
        BridgeContract.G_MASTER_SATURATION,
        org.deluge.project.PreferencesManager.isMasterSaturationEnabled() ? 1.0f : 0.0f);
    bridge.setGlobalFloat(
        BridgeContract.G_CHAR_FILTER_DRIVE,
        org.deluge.project.PreferencesManager.isFilterDriveEnabled() ? 1.0f : 0.0f);
    bridge.setGlobalFloat(
        BridgeContract.G_BIT_CRUNCH,
        org.deluge.project.PreferencesManager.isBitCrunchEnabled() ? 1.0f : 0.0f);

    // ── Load global Scala tuning scale preference on startup ──
    try {
      String scalaPath = org.deluge.project.PreferencesManager.get("scala.scale.path", "");
      if (!scalaPath.isEmpty()) {
        java.io.File file = new java.io.File(scalaPath);
        if (file.exists()) {
          try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            org.deluge.model.tuning.ScalaScale scale =
                org.deluge.model.tuning.ScalaScaleParser.parse(fis, file.getName());
            org.deluge.model.tuning.ScalaScale.setActiveScale(scale);
            System.out.println(
                "[Preferences] Successfully cabled tuning scale: " + scale.getName());
          }
        }
      }
    } catch (Exception e) {
      System.err.println("[Preferences] Failed to load startup Scala scale: " + e.getMessage());
    }

    // Reverb model is already pushed via PreferencesManager.get("reverb.model") in loadProject —
    // skip here
  }

  /**
   * Override song-level FX parameters (G_SP_* globals) with the active clip's per-clip FX params.
   *
   * <p>For synth/kit tracks, reads {@link ClipModel#getKitParams()} (a {@code Map<String, Float>}).
   * For audio tracks, reads typed fields from {@link org.deluge.model.AudioTrackModel.AudioClip}
   * getters. If the clip has no override for a given parameter, the song-level default is
   * preserved.
   *
   * <p>This implements the real Deluge firmware's GlobalEffectable pattern where InstrumentClip can
   * override Song-level FX parameters.
   */
  private void applyClipFxOverrides(int track, int clipIdx) {
    java.util.List<org.deluge.model.TrackModel> tracks = currentProject.getTracks();
    if (track < 0 || track >= tracks.size()) return;
    org.deluge.model.TrackModel trk = tracks.get(track);
    if (clipIdx < 0) return;

    // ── Audio tracks: read typed fields from AudioClip ──
    if (trk instanceof org.deluge.model.AudioTrackModel audioTrk) {
      if (clipIdx >= audioTrk.getAudioClips().size()) return;
      org.deluge.model.AudioTrackModel.AudioClip aClip = audioTrk.getAudioClips().get(clipIdx);
      bridge.setGlobalFloat(BridgeContract.G_SP_VOLUME, aClip.getVolume());
      bridge.setGlobalFloat(BridgeContract.G_SP_PAN, aClip.getPan());
      bridge.setGlobalFloat(BridgeContract.G_SP_REVERB_AMOUNT, aClip.getReverbAmount());
      bridge.setGlobalFloat(BridgeContract.G_SP_DELAY_RATE, aClip.getDelayRate());
      bridge.setGlobalFloat(BridgeContract.G_SP_DELAY_FEEDBACK, aClip.getDelayFeedback());
      bridge.setGlobalFloat(BridgeContract.G_SP_SIDECHAIN_SHAPE, aClip.getSidechainShape());
      bridge.setGlobalFloat(BridgeContract.G_SP_MOD_FX_RATE, aClip.getModFXRate());
      bridge.setGlobalFloat(BridgeContract.G_SP_MOD_FX_DEPTH, aClip.getModFXDepth());
      bridge.setGlobalFloat(BridgeContract.G_SP_MOD_FX_OFFSET, aClip.getModFXOffset());
      bridge.setGlobalFloat(BridgeContract.G_SP_MOD_FX_FEEDBACK, aClip.getModFXFeedback());
      bridge.setGlobalFloat(BridgeContract.G_SP_STUTTER_RATE, aClip.getStutterRate());
      bridge.setGlobalFloat(
          BridgeContract.G_SP_SAMPLE_RATE_REDUCTION, aClip.getSampleRateReduction());
      bridge.setGlobalFloat(BridgeContract.G_SP_BITCRUSH, aClip.getBitCrush());
      bridge.setGlobalFloat(BridgeContract.G_SP_LPF_FREQ, aClip.getLpfFrequency());
      bridge.setGlobalFloat(BridgeContract.G_SP_LPF_RES, aClip.getLpfResonance());
      bridge.setGlobalFloat(BridgeContract.G_SP_HPF_FREQ, aClip.getHpfFrequency());
      bridge.setGlobalFloat(BridgeContract.G_SP_HPF_RES, aClip.getHpfResonance());
      bridge.setGlobalFloat(BridgeContract.G_SP_EQ_BASS, aClip.getEqBass());
      bridge.setGlobalFloat(BridgeContract.G_SP_EQ_TREBLE, aClip.getEqTreble());
      bridge.setGlobalFloat(BridgeContract.G_SP_EQ_BASS_FREQ, aClip.getEqBassFrequency());
      bridge.setGlobalFloat(BridgeContract.G_SP_EQ_TREBLE_FREQ, aClip.getEqTrebleFrequency());
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
    if (kp.containsKey("volume"))
      bridge.setGlobalFloat(BridgeContract.G_SP_VOLUME, kp.get("volume"));
    if (kp.containsKey("pan")) bridge.setGlobalFloat(BridgeContract.G_SP_PAN, kp.get("pan"));
    if (kp.containsKey("reverbAmount"))
      bridge.setGlobalFloat(BridgeContract.G_SP_REVERB_AMOUNT, kp.get("reverbAmount"));
    if (kp.containsKey("delayRate"))
      bridge.setGlobalFloat(BridgeContract.G_SP_DELAY_RATE, kp.get("delayRate"));
    if (kp.containsKey("delayFeedback"))
      bridge.setGlobalFloat(BridgeContract.G_SP_DELAY_FEEDBACK, kp.get("delayFeedback"));
    if (kp.containsKey("sidechainCompressorShape"))
      bridge.setGlobalFloat(
          BridgeContract.G_SP_SIDECHAIN_SHAPE, kp.get("sidechainCompressorShape"));
    if (kp.containsKey("stutterRate"))
      bridge.setGlobalFloat(BridgeContract.G_SP_STUTTER_RATE, kp.get("stutterRate"));
    if (kp.containsKey("sampleRateReduction"))
      bridge.setGlobalFloat(
          BridgeContract.G_SP_SAMPLE_RATE_REDUCTION, kp.get("sampleRateReduction"));
    if (kp.containsKey("bitCrush"))
      bridge.setGlobalFloat(BridgeContract.G_SP_BITCRUSH, kp.get("bitCrush"));
    if (kp.containsKey("modFXRate"))
      bridge.setGlobalFloat(BridgeContract.G_SP_MOD_FX_RATE, kp.get("modFXRate"));
    if (kp.containsKey("modFXDepth"))
      bridge.setGlobalFloat(BridgeContract.G_SP_MOD_FX_DEPTH, kp.get("modFXDepth"));
    if (kp.containsKey("modFXOffset"))
      bridge.setGlobalFloat(BridgeContract.G_SP_MOD_FX_OFFSET, kp.get("modFXOffset"));
    if (kp.containsKey("modFXFeedback"))
      bridge.setGlobalFloat(BridgeContract.G_SP_MOD_FX_FEEDBACK, kp.get("modFXFeedback"));
    if (kp.containsKey("compressorThreshold"))
      bridge.setGlobalFloat(
          BridgeContract.G_SP_COMPRESSOR_THRESHOLD, kp.get("compressorThreshold"));
    if (kp.containsKey("lpfMorph"))
      bridge.setGlobalFloat(BridgeContract.G_SP_LPF_MORPH, kp.get("lpfMorph"));
    if (kp.containsKey("hpfMorph"))
      bridge.setGlobalFloat(BridgeContract.G_SP_HPF_MORPH, kp.get("hpfMorph"));
    if (kp.containsKey("lpfFrequency"))
      bridge.setGlobalFloat(BridgeContract.G_SP_LPF_FREQ, kp.get("lpfFrequency"));
    if (kp.containsKey("lpfResonance"))
      bridge.setGlobalFloat(BridgeContract.G_SP_LPF_RES, kp.get("lpfResonance"));
    if (kp.containsKey("hpfFrequency"))
      bridge.setGlobalFloat(BridgeContract.G_SP_HPF_FREQ, kp.get("hpfFrequency"));
    if (kp.containsKey("hpfResonance"))
      bridge.setGlobalFloat(BridgeContract.G_SP_HPF_RES, kp.get("hpfResonance"));
    if (kp.containsKey("eqBass"))
      bridge.setGlobalFloat(BridgeContract.G_SP_EQ_BASS, kp.get("eqBass"));
    if (kp.containsKey("eqTreble"))
      bridge.setGlobalFloat(BridgeContract.G_SP_EQ_TREBLE, kp.get("eqTreble"));
    if (kp.containsKey("eqBassFrequency"))
      bridge.setGlobalFloat(BridgeContract.G_SP_EQ_BASS_FREQ, kp.get("eqBassFrequency"));
    if (kp.containsKey("eqTrebleFrequency"))
      bridge.setGlobalFloat(BridgeContract.G_SP_EQ_TREBLE_FREQ, kp.get("eqTrebleFrequency"));
  }

  private org.deluge.engine.PureFirmwareEngine pureEngine;
  private org.deluge.ui.ArrangerPlaybackScheduler arrangerScheduler;

  public org.deluge.ui.ArrangerPlaybackScheduler getArrangerScheduler() {
    return arrangerScheduler;
  }

  public org.deluge.engine.PureFirmwareEngine getPureEngine() {
    return pureEngine;
  }

  public SwingDelugeApp(final BridgeContract bridge, org.deluge.midi.MidiService midiService) {
    this(bridge, midiService, false);
  }

  public SwingDelugeApp(
      final BridgeContract bridge, org.deluge.midi.MidiService midiService, boolean pureMode) {
    this.bridge = bridge;

    this.midiService = midiService;
    mainInstance = this;
    pureModeActive = pureMode;

    if (pureMode) {
      System.out.println("[UI] Initializing Pure Java High-Fidelity Engine...");
      this.pureEngine = new org.deluge.engine.PureFirmwareEngine();
      this.pureEngine.start(bridge);
      // Register in bridge for components that poll it
      bridge.setGlobalObject(BridgeContract.G_FIRMWARE_ENGINE, pureEngine.getAudioEngine());
      bridge.setGlobalObject(BridgeContract.G_PLAYBACK_HANDLER, pureEngine.getPlaybackHandler());

      String syncModeStr =
          org.deluge.project.PreferencesManager.get("sequencer.sync.mode", "INTERNAL");
      int syncMode = "EXTERNAL_MIDI".equals(syncModeStr) ? 1 : 0;
      pureEngine.getPlaybackHandler().setSyncMode(syncMode);

      // MIDI clock OUT: when enabled (default on), the transport drives external gear as the master
      // clock (0xFA start / 0xF8 tick @24 PPQN / 0xFC stop) via the MidiEngine.
      if (midiService != null
          && Boolean.parseBoolean(
              org.deluge.project.PreferencesManager.get("midi.clock.out", "true"))) {
        final org.deluge.midi.MidiEngine me = midiService.getEngine();
        pureEngine
            .getPlaybackHandler()
            .setMidiClockOut(
                new org.deluge.playback.PlaybackHandler.MidiClockSink() {
                  @Override
                  public void start() {
                    me.sendStart();
                  }

                  @Override
                  public void stop() {
                    me.sendStop();
                  }

                  @Override
                  public void clock() {
                    me.sendClock();
                  }
                });
      }
      System.out.println(
          "[UI] Boot Sync Mode applied to PlaybackHandler: "
              + (syncMode == 1 ? "EXTERNAL" : "INTERNAL"));
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
                            instanceof org.deluge.model.SynthTrackModel;

                if (isSynth) {
                  try {
                    org.deluge.shadow.core.ChuckEvent noteEv =
                        (org.deluge.shadow.core.ChuckEvent) bridge.getGlobalObject("g_ck_noteOn");
                    if (noteEv != null) {
                      org.deluge.shadow.core.ChuckArray pitchArr =
                          (org.deluge.shadow.core.ChuckArray)
                              bridge.getGlobalObject(BridgeContract.G_PITCH);
                      pitchArr.setInt(0, (long) (note - 60));
                      noteEv.broadcast();
                    }
                  } catch (Exception ex) {
                  }
                } else {
                  String sp = (String) bridge.getGlobalObject("g_sample_" + trackId);
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
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_TAB) {
                  if (e.getID() == java.awt.event.KeyEvent.KEY_PRESSED) {
                    if (clipPanel != null) clipPanel.setTabHeld(true);
                  } else if (e.getID() == java.awt.event.KeyEvent.KEY_RELEASED) {
                    if (clipPanel != null) clipPanel.setTabHeld(false);
                  }
                  return true; // consume to prevent standard AWT focus traversal!
                }

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

                // Global GridMode Zoom: Alt + PageUp/PageDown cycles through all grid modes
                if (e.getID() == java.awt.event.KeyEvent.KEY_PRESSED) {
                  boolean isAlt = e.isAltDown() || e.isMetaDown();
                  if (isAlt
                      && (e.getKeyCode() == java.awt.event.KeyEvent.VK_PAGE_UP
                          || e.getKeyCode() == java.awt.event.KeyEvent.VK_PAGE_DOWN)) {
                    boolean forward = (e.getKeyCode() == java.awt.event.KeyEvent.VK_PAGE_UP);
                    org.deluge.project.PreferencesManager.GridMode[] modes =
                        org.deluge.project.PreferencesManager.GridMode.values();
                    org.deluge.project.PreferencesManager.GridMode currentMode =
                        org.deluge.project.PreferencesManager.getGridMode();
                    int currentIdx = 0;
                    for (int i = 0; i < modes.length; i++) {
                      if (modes[i] == currentMode) {
                        currentIdx = i;
                        break;
                      }
                    }
                    int nextIdx = currentIdx + (forward ? -1 : 1);
                    if (nextIdx >= 0 && nextIdx < modes.length) {
                      org.deluge.project.PreferencesManager.GridMode nextMode = modes[nextIdx];
                      org.deluge.project.PreferencesManager.setGridMode(nextMode);
                      if (clipPanel != null) {
                        clipPanel.setGridMode(nextMode);
                        clipPanel.refresh();
                      }
                      if (songPanel != null) {
                        songPanel.setGridMode(nextMode);
                        songPanel.refresh();
                      }
                      if (arrGridPanel != null) {
                        arrGridPanel.setGridMode(nextMode);
                        arrGridPanel.refresh();
                      }
                      recalcWrapperSize();
                    }
                    return true; // consume event!
                  }
                }
                return false; // Pass event downstream
              }
            });

    // The window size is driven by the Screen Resolution preference, then clamped to the physical
    // screen so it always fits the actual laptop (down to ~1366x768) and never exceeds it.
    setMinimumSize(new Dimension(MIN_WINDOW_W, MIN_WINDOW_H));
    Dimension win = computeWindowSize();
    setSize(win.width, win.height);

    addWindowListener(
        new java.awt.event.WindowAdapter() {
          @Override
          public void windowClosing(java.awt.event.WindowEvent e) {
            org.deluge.project.PreferencesManager.set("window.width", String.valueOf(getWidth()));
            org.deluge.project.PreferencesManager.set("window.height", String.valueOf(getHeight()));
          }
        });

    setLocationRelativeTo(null);
    getContentPane().setBackground(new Color(0x1a, 0x1a, 0x1a));
    setLayout(new BorderLayout(10, 10));

    setupUI();

    // Realize and lay out the frame at its FINAL size while still invisible, then size every grid's
    // cells once up-front. Without this the grids are built at the default cell size and then
    // rebuilt
    // 2–3 times as boot resize events (0 → frame width → viewport width) arrive — the visible
    // "grid keeps resizing" flicker. addNotify()+validate() is exactly what pack() does internally
    // for layout, minus the preferred-size resize, so it keeps our computed window size.
    addNotify();
    validate();
    for (SwingGridPanel grid :
        new SwingGridPanel[] {clipPanel, songPanel, arrGridPanel, autoPanel}) {
      if (grid != null) {
        grid.recomputePadSize();
      }
    }

    // Auto-load our default initial song project to sync tracks, presets, and listeners to the
    // bridge/engine!
    loadProject(currentProject);

    // Trigger a high-fidelity retro rolling welcome message on boot!
    if (topBar != null && topBar.getParamReadout() != null) {
      topBar.getParamReadout().print("HELO", "    ");
      javax.swing.Timer timer1 =
          new javax.swing.Timer(
              800,
              e -> {
                if (topBar != null && topBar.getParamReadout() != null) {
                  topBar.getParamReadout().print("DELU", "V1.0");
                }
              });
      timer1.setRepeats(false);
      timer1.start();

      javax.swing.Timer timer2 =
          new javax.swing.Timer(
              1600,
              e -> {
                if (topBar != null && topBar.getParamReadout() != null) {
                  topBar.getParamReadout().reset();
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
            boolean shift = (e.getModifiersEx() & java.awt.event.InputEvent.SHIFT_DOWN_MASK) != 0;

            // Ctrl+Shift+C / Ctrl+Shift+V — copy / paste the active clip's notes (Deluge
            // X_ENC+LEARN copy / paste). Shift-qualified to avoid clashing with system copy/paste.
            if (ctrl && shift && kc == java.awt.event.KeyEvent.VK_C) {
              SwingGridPanel active = activeGridPanel();
              if (active != null) {
                active.copyClipNotes();
              }
              return;
            }
            if (ctrl && shift && kc == java.awt.event.KeyEvent.VK_V) {
              SwingGridPanel active = activeGridPanel();
              if (active != null) {
                active.pasteClipNotes();
              }
              return;
            }

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

            org.deluge.shadow.hid.HidMsg msg = new org.deluge.shadow.hid.HidMsg();
            msg.deviceType = "keyboard";
            msg.type = org.deluge.shadow.hid.HidMsg.BUTTON_DOWN;
            msg.which = kc;
            msg.key = kc;
            char c = e.getKeyChar();
            if (c != java.awt.event.KeyEvent.CHAR_UNDEFINED) {
              msg.ascii = c;
            }
            bridge.dispatchHidMsg(msg);
          }

          @Override
          public void keyReleased(java.awt.event.KeyEvent e) {
            org.deluge.shadow.hid.HidMsg msg = new org.deluge.shadow.hid.HidMsg();
            msg.deviceType = "keyboard";
            msg.type = org.deluge.shadow.hid.HidMsg.BUTTON_UP;
            msg.which = e.getKeyCode();
            msg.key = e.getKeyCode();
            bridge.dispatchHidMsg(msg);
          }
        });
    DarkComboBoxRenderer.styleComponentTree(this);

    // Instantiate Arranger Timeline real-time Scheduler
    this.arrangerScheduler = new org.deluge.ui.ArrangerPlaybackScheduler(bridge, currentProject);
    if (arrGridPanel != null) {
      arrangerScheduler.setRepaintCallback(() -> arrGridPanel.refresh());
    }
  }

  public void loadProject(org.deluge.model.ProjectModel model) {
    currentProject = model;
    if (arrangerScheduler != null) {
      arrangerScheduler.setProject(model);
    }
    bridge.setGlobalInt(BridgeContract.G_PLAY, 0L);
    if (bridge != null) bridge.setPlayState(0);

    // Register listener so structural and param changes auto-sync to bridge
    model.addProjectListener(new BridgeProjectListener(model));

    pushModelToBridge();
    propagateCurrentModel();
    syncHighFidelityEngine(model);

    // DEBUG: dump loaded model clips
    System.out.println("[LOAD-DUMP] project tracks=" + model.getTracks().size());
    for (int ti = 0; ti < model.getTracks().size(); ti++) {
      var trk = model.getTracks().get(ti);
      System.out.println(
          "[LOAD-DUMP] track "
              + ti
              + " type="
              + trk.getClass().getSimpleName()
              + " name="
              + trk.getName()
              + " clips="
              + trk.getClips().size());
      for (int ci = 0; ci < trk.getClips().size(); ci++) {
        var cl = trk.getClips().get(ci);
        System.out.println(
            "[LOAD-DUMP]   clip "
                + ci
                + " rows="
                + cl.getRowCount()
                + " steps="
                + cl.getStepCount());
        int active = 0;
        for (int r = 0; r < cl.getRowCount(); r++) {
          for (int s = 0; s < cl.getStepCount(); s++) {
            if (cl.getStep(r, s).active()) {
              active++;
              System.out.println(
                  "[LOAD-DUMP]     row="
                      + r
                      + " col="
                      + s
                      + " yNote="
                      + cl.getRowYNote(r)
                      + " pitch="
                      + cl.getStep(r, s).pitch()
                      + " vel="
                      + cl.getStep(r, s).velocity());
            }
          }
        }
        System.out.println("[LOAD-DUMP]   clip " + ci + " total active=" + active);
      }
    }

    if (clipPanel != null) clipPanel.setProjectModel(model);
    if (songPanel != null) songPanel.setProjectModel(model);
    if (arrGridPanel != null) arrGridPanel.setProjectModel(model);

    // Switch view depending on track count to keep UI and top bar perfectly synchronized
    if (model.getTracks().size() == 1) {
      if (cardLayout != null && centerCardPanel != null) {
        cardLayout.show(centerCardPanel, "CLIP");
      }
      if (topBar != null) topBar.selectClipView();
      boolean firstIsSynth =
          !model.getTracks().isEmpty()
              && model.getTracks().get(0) instanceof org.deluge.model.SynthTrackModel;
      if (clipPanel != null) {
        clipPanel.setBaseTrackId(
            trackEngineStart != null && trackEngineStart.length > 0
                ? trackEngineStart[0]
                : (firstIsSynth ? 4 : 0));
      }
    } else {
      if (cardLayout != null && centerCardPanel != null) {
        cardLayout.show(centerCardPanel, "SONG");
      }
      if (topBar != null) topBar.selectViewModeButton("SONG");
    }

    setTitle(
        "DELUGE WORKSTATION — "
            + (currentProjectFile != null ? currentProjectFile.getName() : "Untitled"));
  }

  /**
   * Loads or imports a project in the background with a premium dark-themed progress dialog,
   * pre-resolving and caching all audio samples to guarantee instant EDT-free playback.
   */
  public void loadProjectWithProgress(final java.io.File file, final boolean isAbleton) {
    if (file == null) return;

    final JDialog progressDialog =
        new JDialog(
            this, isAbleton ? "Importing Ableton Live Set" : "Loading Deluge Project", true);
    progressDialog.setUndecorated(true);
    progressDialog.setSize(420, 110);
    progressDialog.setLocationRelativeTo(this);

    JPanel panel = new JPanel(new BorderLayout(12, 12));
    panel.setBackground(new Color(0x18, 0x18, 0x1a));
    panel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x34), 1),
            BorderFactory.createEmptyBorder(16, 16, 16, 16)));

    final JLabel label =
        new JLabel(
            isAbleton ? "Parsing Ableton Live Set..." : "Parsing Deluge Project XML...",
            JLabel.CENTER);
    label.setForeground(new Color(0xaa, 0xbb, 0xcc));
    label.setFont(new Font("SansSerif", Font.PLAIN, 12));

    final JProgressBar progressBar = new JProgressBar(0, 100);
    progressBar.setIndeterminate(true);
    progressBar.setBackground(new Color(0x22, 0x22, 0x25));
    progressBar.setForeground(new Color(0x00, 0xcc, 0xff));
    progressBar.setBorderPainted(false);
    progressBar.setPreferredSize(new Dimension(380, 6));

    panel.add(label, BorderLayout.CENTER);
    panel.add(progressBar, BorderLayout.SOUTH);
    progressDialog.add(panel);

    javax.swing.SwingWorker<org.deluge.model.ProjectModel, String> worker =
        new javax.swing.SwingWorker<>() {
          @Override
          protected org.deluge.model.ProjectModel doInBackground() throws Exception {
            org.deluge.model.ProjectModel model;
            if (isAbleton) {
              publish("Parsing Ableton Live Set...");
              org.w3c.dom.Document doc =
                  org.deluge.ableton.AbletonProjectManager.parseAlsToXml(file);
              model = new org.deluge.model.ProjectModel();
              org.deluge.ableton.AbletonTrackMapper.importAbletonSet(doc, model, file);
            } else {
              publish("Parsing Deluge Project XML...");
              try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                model = org.deluge.xml.DelugeXmlParser.parseSong(fis, file.getName());
              }
            }

            // Pre-resolve and load all sample paths to cache them in the background thread
            final java.util.List<String> samplePaths = new java.util.ArrayList<>();
            java.io.File sdRoot = org.deluge.project.PreferencesManager.getLibraryDir();

            for (org.deluge.model.TrackModel track : model.getTracks()) {
              if (track instanceof org.deluge.model.AudioTrackModel atm) {
                for (org.deluge.model.AudioTrackModel.AudioClip clip : atm.getAudioClips()) {
                  String p = clip.getFilePath();
                  if (p != null && !p.isEmpty()) samplePaths.add(p);
                }
              } else if (track instanceof org.deluge.model.SynthTrackModel stm) {
                String p1 = stm.getOsc1SamplePath();
                if (p1 != null && !p1.isEmpty()) samplePaths.add(p1);
                String p2 = stm.getOsc2SamplePath();
                if (p2 != null && !p2.isEmpty()) samplePaths.add(p2);
              } else if (track instanceof org.deluge.model.KitTrackModel ktm) {
                for (org.deluge.model.Drum d : ktm.getDrums()) {
                  if (d instanceof org.deluge.model.SoundDrum sd) {
                    String p = sd.getSamplePath();
                    if (p != null && !p.isEmpty()) samplePaths.add(p);
                  }
                }
              }
            }

            if (!samplePaths.isEmpty()) {
              // Switch to determinate progress bar
              javax.swing.SwingUtilities.invokeLater(
                  () -> {
                    progressBar.setIndeterminate(false);
                    progressBar.setMaximum(samplePaths.size());
                    progressBar.setValue(0);
                  });

              for (int i = 0; i < samplePaths.size(); i++) {
                String path = samplePaths.get(i);
                java.io.File resolved =
                    org.deluge.engine.FirmwareFactory.resolveSample(path, sdRoot);
                if (resolved != null && resolved.exists()) {
                  publish(
                      "Loading "
                          + resolved.getName()
                          + " ("
                          + (i + 1)
                          + "/"
                          + samplePaths.size()
                          + ")...");
                  try {
                    org.deluge.storage.audio.AudioFileReader.readSample(resolved.getAbsolutePath());
                  } catch (Exception ignored) {
                  }
                }
                final int progressValue = i + 1;
                javax.swing.SwingUtilities.invokeLater(() -> progressBar.setValue(progressValue));
              }
            }

            return model;
          }

          @Override
          protected void process(java.util.List<String> chunks) {
            if (!chunks.isEmpty()) {
              String lastMsg = chunks.get(chunks.size() - 1);
              label.setText(lastMsg);
            }
          }

          @Override
          protected void done() {
            progressDialog.dispose();
            try {
              org.deluge.model.ProjectModel model = get();
              if (isAbleton) {
                currentProjectFile = null;
                loadProject(model);
                setTitle("DELUGE WORKSTATION — [Imported] " + file.getName());
              } else {
                currentProjectFile = file;
                loadProject(model);
                setTitle("DELUGE WORKSTATION — " + file.getName());
              }
            } catch (Exception ex) {
              Throwable cause = (ex.getCause() != null) ? ex.getCause() : ex;
              cause.printStackTrace();
              JOptionPane.showMessageDialog(
                  SwingDelugeApp.this,
                  "Failed to "
                      + (isAbleton ? "import" : "load")
                      + " project:\n"
                      + cause.getMessage(),
                  "Error",
                  JOptionPane.ERROR_MESSAGE);
            }
          }
        };

    worker.execute();
    progressDialog.setVisible(true);
  }

  /**
   * Load a WAV and apply it to a LIVE FirmwareKit drum voice so grid auditioning immediately plays
   * the chosen sample. The kit-config dialog otherwise only updated the model + a no-op
   * BridgeContract.setSamplePath (whose g_sample_/G_LOAD_TRIGGER consumers live in the legacy
   * DelugeEngineDSL, not the pure engine the Swing UI uses), so every drum fell back to its default
   * oscillator — all cells sounded identical. Mirrors the per-drum sample setup in
   * FirmwareFactory.createKitClip (oscType=SAMPLE + samples[0] + fw2SampleCache[0]). drumIdx is the
   * model drum order, which matches FirmwareKit.drumSounds order.
   */
  public void applyKitDrumSampleLive(
      org.deluge.model.KitTrackModel kit, int drumIdx, String absPath) {
    System.err.println(
        "[applyKitDrumSampleLive] ENTER drumIdx="
            + drumIdx
            + " path="
            + (absPath != null ? absPath.substring(Math.max(0, absPath.length() - 40)) : "null"));
    if (currentProject == null || pureEngine == null || absPath == null || absPath.isBlank()) {
      System.err.println("[applyKitDrumSampleLive] EARLY EXIT null");
      return;
    }
    int trackIdx = -1;
    var tracks = currentProject.getTracks();
    for (int i = 0; i < tracks.size(); i++) {
      if (tracks.get(i) == kit) {
        trackIdx = i;
        break;
      }
    }
    System.err.println(
        "[applyKitDrumSampleLive] trackIdx=" + trackIdx + " tracks=" + tracks.size());
    if (trackIdx < 0) {
      System.err.println("[applyKitDrumSampleLive] EARLY EXIT trackIdx");
      return;
    }
    org.deluge.engine.FirmwareAudioEngine eng = pureEngine.getAudioEngine();
    if (eng == null || trackIdx >= eng.sounds.size()) {
      System.err.println("[applyKitDrumSampleLive] EARLY EXIT eng");
      return;
    }
    var sound = eng.sounds.get(trackIdx);
    System.err.println(
        "[applyKitDrumSampleLive] sound@"
            + trackIdx
            + " = "
            + (sound != null ? sound.getClass().getSimpleName() : "null"));
    if (!(sound instanceof org.deluge.engine.FirmwareKit fkit)) {
      System.err.println("[applyKitDrumSampleLive] EARLY EXIT not kit");
      return;
    }
    if (drumIdx < 0 || drumIdx >= fkit.drumSounds.size()) {
      System.err.println("[applyKitDrumSampleLive] EARLY EXIT drumIdx oob");
      return;
    }
    org.deluge.engine.FirmwareSound drum = fkit.drumSounds.get(drumIdx);
    try {
      org.deluge.playback.Sample s = org.deluge.storage.audio.AudioFileReader.readSample(absPath);
      if (s != null && s.data != null) {
        drum.oscTypes[0] = org.deluge.firmware2.Oscillator.OscType.SAMPLE;
        drum.samples[0] = s;
        drum.fw2SampleCache[0] = org.deluge.firmware2.Sample.fromFirmwareSample(s);
        System.err.println("[applyKitDrumSampleLive] OK loaded " + s.getNumSamples() + " samples");
      } else {
        System.err.println(
            "[applyKitDrumSampleLive] FAIL readSample returned "
                + (s != null ? "null data" : "null"));
      }
    } catch (Exception ex) {
      System.err.println("[KitConfig] live drum sample apply failed: " + ex.getMessage());
      ex.printStackTrace();
    }
  }

  /** True if the firmware playback handler is currently playing. */
  private boolean isFirmwarePlaying() {
    Object h = bridge.getGlobalObject(BridgeContract.G_PLAYBACK_HANDLER);
    return h instanceof org.deluge.playback.PlaybackHandler ph && ph.isPlaying();
  }

  /** True if the engine's registered sound count no longer matches the track count. */
  private boolean engineStructureChanged(org.deluge.model.ProjectModel model) {
    Object e = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
    return !(e instanceof org.deluge.engine.FirmwareAudioEngine eng)
        || eng.sounds.size() != model.getTracks().size();
  }

  public void syncHighFidelityEngine(org.deluge.model.ProjectModel model) {
    syncHighFidelityEngine(model, false);
  }

  /**
   * @param forceRebuild rebuild the engine even while playing. Needed for a preset SWAP, which
   *     changes a track's actual sound (not just notes) — the live noteRows-sync can't carry a new
   *     sound, so skipping the rebuild leaves the engine on the stale sound (all notes garbage).
   */
  public void syncHighFidelityEngine(org.deluge.model.ProjectModel model, boolean forceRebuild) {
    if (!forceRebuild && isFirmwarePlaying() && !engineStructureChanged(model)) {
      return;
    }
    // Compile DSP engine in-place on the unified model!
    org.deluge.model.ProjectModel project = FirmwareFactory.createSong(model);

    org.deluge.model.ClipModel firstClip = null;
    if (!model.getTracks().isEmpty()) {
      firstClip = model.getTracks().get(0).getActiveClip();
    }

    final org.deluge.model.ClipModel fClip = firstClip;
    javax.swing.SwingUtilities.invokeLater(
        () -> {
          MatrixDriver.get().popUI();
          if (fClip != null) {
            if (fClip.getSound() instanceof org.deluge.engine.FirmwareKit) {
              MatrixDriver.get().pushUI(new KitView(fClip));
            } else {
              MatrixDriver.get().pushUI(new PianoRollView(fClip));
            }
          } else {
            MatrixDriver.get().pushUI(new SessionView(model));
          }
        });

    // ── Sync Audio Registry ──
    Object fwEngineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
    if (fwEngineObj instanceof org.deluge.engine.FirmwareAudioEngine fwEngine) {
      fwEngine.sounds.clear();
      for (int i = 0; i < model.getTracks().size(); i++) {
        org.deluge.model.TrackModel tm = model.getTracks().get(i);
        org.deluge.model.ClipModel activeClip = tm.getActiveClip();
        org.deluge.firmware2.GlobalEffectable sound =
            (activeClip != null)
                ? (org.deluge.firmware2.GlobalEffectable) activeClip.getSound()
                : null;
        fwEngine.sounds.add(sound);
        if (sound != null) {
          System.out.println(
              "[UI] Registered track " + i + " sound: " + sound.getClass().getSimpleName());
        }
      }

      float masterVol = (float) bridge.getGlobalFloat(BridgeContract.G_MASTER_VOL);
      System.out.println("[UI] Engine Sync - MasterVol Global: " + masterVol);
      fwEngine.masterVolumeAdjustmentL = (int) (masterVol * 2147483647.0);
      fwEngine.masterVolumeAdjustmentR = fwEngine.masterVolumeAdjustmentL;

      System.out.println(
          "[UI] Synchronized "
              + fwEngine.sounds.size()
              + " track slots for Hi-Fi Rendering. MasterVol: "
              + masterVol);
    }
    if (topBar != null && !model.getTracks().isEmpty()) {
      int activeTrk = clipPanel != null ? clipPanel.getEditedModelTrack() : 0;
      if (activeTrk >= 0 && activeTrk < model.getTracks().size()) {
        org.deluge.model.TrackModel tm = model.getTracks().get(activeTrk);
        String tName = tm.getName().toUpperCase();
        String modeBanner =
            "CLIP".equals(activeViewMode) || activeViewMode == null
                ? "CLIP VIEW"
                : (activeViewMode + " VIEW");
        String middleText;
        String bottomInfo;
        if ("CLIP".equals(activeViewMode) || activeViewMode == null) {
          if (tm instanceof org.deluge.model.SynthTrackModel) {
            middleText = "SYNTH";
            bottomInfo = "PRESET: 1 (POLY 8)";
          } else if (tm instanceof org.deluge.model.KitTrackModel) {
            middleText = "KIT";
            bottomInfo = "DRUMS: 16 SLOTS";
          } else {
            middleText = "AUDIO";
            bottomInfo = "STEREO STREAM";
          }
        } else if ("SONG".equals(activeViewMode)) {
          middleText = "SONG: " + model.getTracks().size() + " TRKS";
          bottomInfo = "BPM: " + ((int) model.getBpm());
        } else {
          middleText = tName;
          bottomInfo = "STATUS: READY";
        }

        final String finalModeBanner = modeBanner;
        final String finalMiddleText = middleText;
        final String finalBottomInfo = bottomInfo;
        javax.swing.SwingUtilities.invokeLater(
            () -> {
              org.deluge.hid.FirmwareDisplay.get()
                  .getVirtualOLED()
                  .drawThreeLineDisplay(finalModeBanner, finalMiddleText, finalBottomInfo);
            });
      }
    }

    Object fwHandlerObj = bridge.getGlobalObject(BridgeContract.G_PLAYBACK_HANDLER);
    System.out.println(
        "[DIAG sync] fwHandlerObj="
            + fwHandlerObj
            + " isPlaybackHandler="
            + (fwHandlerObj instanceof org.deluge.playback.PlaybackHandler));
    if (fwHandlerObj instanceof org.deluge.playback.PlaybackHandler fwHandler) {
      fwHandler.setProject(model);
      System.out.println(
          "[DIAG sync] Successfully set project inside PlaybackHandler! Current active play state="
              + fwHandler.isPlaying()
              + " songBpm="
              + model.getBpm());
      if (firstClip != null) {
        int activeNotesCount = 0;
        for (var row : firstClip.getNoteRowsList()) {
          activeNotesCount += row.getNotes().size();
        }
        System.out.println(
            "[DIAG sync] First Clip Active NoteRows Count: "
                + firstClip.getNoteRowsList().size()
                + " Total Programmed Note Events: "
                + activeNotesCount);
      }
    }
  }

  public void refreshGrids() {
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
    java.io.File songsDir = org.deluge.project.PreferencesManager.getSongsDir();
    java.io.File suggestedFile =
        org.deluge.project.SaveNameSuggester.suggestNextSaveFile(songsDir, currentProjectFile);

    java.io.File target = (suggestedFile != null) ? suggestedFile : currentProjectFile;

    if (currentProjectFile == null || forceChooser) {
      JFileChooser chooser = new JFileChooser(songsDir);
      chooser.setFileFilter(
          new javax.swing.filechooser.FileNameExtensionFilter("Song XML", "xml", "XML"));

      if (target != null) {
        chooser.setSelectedFile(target);
      }

      if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
      target = chooser.getSelectedFile();
      if (!target.getName().toLowerCase().endsWith(".xml")) {
        target = new java.io.File(target.getAbsolutePath() + ".xml");
      }
    }
    try {
      pushModelToBridge();
      org.deluge.project.ProjectSerializer.save(currentProject, target);
      currentProjectFile = target;
      setTitle("DELUGE WORKSTATION — " + target.getName());
      if (sidebarPanel != null) {
        sidebarPanel.reloadLibrary();
      }
      if (topBar != null) {
        topBar.setSaved(true);
      }
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(
          this, "Save failed:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
    }
  }

  private SwingGridPanel activeGridPanel() {
    if (cardLayout == null || centerCardPanel == null) {
      System.out.println(
          "[TRACE activeGrid] cardLayout or centerCardPanel is null! Returning default clipPanel.");
      return clipPanel;
    }
    System.out.println("[TRACE activeGrid] Querying components inside centerCardPanel...");
    for (java.awt.Component comp : centerCardPanel.getComponents()) {
      System.out.println(
          "[TRACE activeGrid] Comp: class="
              + comp.getClass().getName()
              + " visible="
              + comp.isVisible());
      if (comp.isVisible() && comp instanceof SwingGridPanel sgp) {
        System.out.println(
            "[TRACE activeGrid] Found visible SwingGridPanel: viewMode=" + sgp.getViewMode());
        return sgp;
      }
      if (comp.isVisible()
          && comp instanceof JScrollPane sp
          && sp.getViewport().getView() instanceof SwingGridPanel sgp) {
        System.out.println(
            "[TRACE activeGrid] Found visible SwingGridPanel inside JScrollPane: viewMode="
                + sgp.getViewMode());
        return sgp;
      }
    }
    System.out.println(
        "[TRACE activeGrid] No visible grid panel found! Fallback to default clipPanel.");
    return clipPanel;
  }

  public void propagateCurrentModel() {
    if (clipPanel != null) clipPanel.setProjectModel(currentProject);
    if (songPanel != null) songPanel.setProjectModel(currentProject);
    if (arrGridPanel != null) arrGridPanel.setProjectModel(currentProject);
    if (autoPanel != null) autoPanel.setProjectModel(currentProject);
    refreshTrackInspector();
  }

  // ── Fixed track-inspector strip (above the grid, outside the scroll) ──
  private javax.swing.JLabel trackInspectorLabel;
  private javax.swing.JButton trackInspectorPresetBtn;

  private javax.swing.JComponent buildTrackInspectorStrip() {
    JPanel strip = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 4));
    strip.setBackground(new Color(0x1a, 0x1a, 0x20));
    strip.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x2d, 0x2d, 0x34)));
    javax.swing.JLabel tag = new javax.swing.JLabel("ACTIVE TRACK");
    tag.setForeground(new Color(0x66, 0x88, 0x99));
    tag.setFont(new Font("SansSerif", Font.BOLD, 10));
    strip.add(tag);
    trackInspectorLabel = new javax.swing.JLabel("—");
    trackInspectorLabel.setForeground(new Color(0x00, 0xcc, 0xff));
    trackInspectorLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
    strip.add(trackInspectorLabel);
    javax.swing.JButton cfg =
        stripButton("⚙ Configure", "Open the full config dialog for the active track");
    cfg.addActionListener(e -> openEditedTrackConfig());
    strip.add(cfg);
    trackInspectorPresetBtn =
        stripButton("▾ Preset…", "Replace the active track's sound or load a new track");
    trackInspectorPresetBtn.addActionListener(
        e -> openEditedTrackPresetPicker(trackInspectorPresetBtn));
    strip.add(trackInspectorPresetBtn);
    return strip;
  }

  private javax.swing.JButton stripButton(String text, String tip) {
    javax.swing.JButton b = new javax.swing.JButton(text);
    b.setBackground(new Color(0x2a, 0x2a, 0x30));
    b.setForeground(new Color(0x00, 0xcc, 0xff));
    b.setFocusPainted(false);
    b.setFont(new Font("SansSerif", Font.PLAIN, 11));
    b.setToolTipText(tip);
    return b;
  }

  /** Update the strip label to reflect the active (edited) track. */
  public void refreshTrackInspector() {
    if (trackInspectorLabel == null) return;
    SwingGridPanel a = activeGridPanel();
    if (a == null || a.getProjectModel() == null) {
      trackInspectorLabel.setText("—");
      return;
    }
    int idx = a.getEditedModelTrack();
    java.util.List<org.deluge.model.TrackModel> tl = a.getProjectModel().getTracks();
    if (idx < 0 || idx >= tl.size()) {
      trackInspectorLabel.setText("—");
      return;
    }
    org.deluge.model.TrackModel t = tl.get(idx);
    String type =
        (t instanceof org.deluge.model.KitTrackModel)
            ? "KIT"
            : (t instanceof org.deluge.model.SynthTrackModel) ? "SYNTH" : "TRK";
    boolean hasPreset =
        (t instanceof org.deluge.model.KitTrackModel)
            || (t instanceof org.deluge.model.SynthTrackModel);
    trackInspectorLabel.setText("T" + (idx + 1) + " · " + t.getName() + "  [" + type + "]");
    if (trackInspectorPresetBtn != null) trackInspectorPresetBtn.setEnabled(hasPreset);
  }

  private void openEditedTrackConfig() {
    SwingGridPanel a = activeGridPanel();
    if (a == null || a.getProjectModel() == null) return;
    int idx = a.getEditedModelTrack();
    java.util.List<org.deluge.model.TrackModel> tl = a.getProjectModel().getTracks();
    if (idx < 0 || idx >= tl.size()) return;
    org.deluge.model.TrackModel t = tl.get(idx);
    if (t instanceof org.deluge.model.KitTrackModel kt) {
      new SwingKitConfigDialog(this, kt, bridge, idx).setVisible(true);
    } else if (t instanceof org.deluge.model.SynthTrackModel st) {
      new SwingSynthConfigDialog(this, st, bridge, idx, currentProject).setVisible(true);
    }
  }

  private void openEditedTrackPresetPicker(java.awt.Component anchor) {
    SwingGridPanel a = activeGridPanel();
    if (a == null || a.getProjectModel() == null) return;
    int idx = a.getEditedModelTrack();
    java.util.List<org.deluge.model.TrackModel> tl = a.getProjectModel().getTracks();
    if (idx < 0 || idx >= tl.size()) return;
    boolean isKit = tl.get(idx) instanceof org.deluge.model.KitTrackModel;
    boolean isSynth = tl.get(idx) instanceof org.deluge.model.SynthTrackModel;
    if (!isKit && !isSynth) return;
    LibraryPicker.show(
        anchor,
        isKit ? LibraryPicker.Scope.KITS : LibraryPicker.Scope.SYNTHS,
        null,
        java.util.List.of(
            new LibraryPicker.Action(
                "Replace track",
                new Color(0x00, 0x88, 0x66),
                f -> replaceEditedTrackPreset(f, isKit)),
            new LibraryPicker.Action(
                "Load as NEW", new Color(0x33, 0x55, 0x88), f -> loadPresetAsNewTrack(f, isKit))));
  }

  private void replaceEditedTrackPreset(java.io.File f, boolean isKit) {
    try {
      SwingGridPanel a = activeGridPanel();
      if (a == null || a.getProjectModel() == null) return;
      int idx = a.getEditedModelTrack();
      java.util.List<org.deluge.model.TrackModel> tl = a.getProjectModel().getTracks();
      if (idx < 0 || idx >= tl.size()) return;
      org.deluge.model.TrackModel old = tl.get(idx);

      setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  final org.deluge.model.TrackModel nt =
                      isKit
                          ? org.deluge.xml.DelugeXmlParser.parseKit(f)
                          : org.deluge.xml.DelugeXmlParser.parseSynth(f);
                  nt.getClips().clear();
                  for (org.deluge.model.ClipModel cm : old.getClips()) nt.addClip(cm);
                  nt.setColourHex(old.getColourHex());

                  // Update model list on the EDT and wait
                  javax.swing.SwingUtilities.invokeAndWait(
                      () -> {
                        tl.set(idx, nt);
                        propagateCurrentModel();
                      });

                  // Heavy engine sync on background thread
                  syncHighFidelityEngine(currentProject, true);

                  // Rebuild UI components on the EDT
                  javax.swing.SwingUtilities.invokeLater(
                      () -> {
                        setCursor(Cursor.getDefaultCursor());
                        a.forceRebuild();
                        if (synthParamRack != null) synthParamRack.refresh();
                        if (modKnobBar != null) modKnobBar.refresh();
                      });
                } catch (Exception ex) {
                  javax.swing.SwingUtilities.invokeLater(
                      () -> {
                        setCursor(Cursor.getDefaultCursor());
                        System.err.println("[Inspector] preset replace failed: " + ex.getMessage());
                      });
                }
              });
    } catch (Exception ex) {
      System.err.println("[Inspector] preset replace failed: " + ex.getMessage());
    }
  }

  private void loadPresetAsNewTrack(java.io.File f, boolean isKit) {
    new javax.swing.SwingWorker<org.deluge.model.TrackModel, Void>() {
      @Override
      protected org.deluge.model.TrackModel doInBackground() throws Exception {
        return isKit
            ? org.deluge.xml.DelugeXmlParser.parseKit(f)
            : org.deluge.xml.DelugeXmlParser.parseSynth(f);
      }

      @Override
      protected void done() {
        try {
          org.deluge.model.TrackModel nt = get();
          currentProject.addTrack(nt);
          propagateCurrentModel();
          syncHighFidelityEngine(currentProject);
          if (clipPanel != null) clipPanel.refresh();
          if (modKnobBar != null) modKnobBar.refresh();
        } catch (Exception ex) {
          Throwable cause = (ex.getCause() != null) ? ex.getCause() : ex;
          System.err.println("[Inspector] preset load-new failed: " + cause.getMessage());
        }
      }
    }.execute();
  }

  public void fireProjectChanged() {
    propagateCurrentModel();
    syncHighFidelityEngine(currentProject);
    if (clipPanel != null) clipPanel.refresh();
    if (songPanel != null) songPanel.refresh();
    if (arrGridPanel != null) arrGridPanel.refresh();
    if (autoPanel != null) autoPanel.refresh();
    if (synthParamRack != null) synthParamRack.refresh();
    if (modKnobBar != null) modKnobBar.refresh();
    refreshTrackInspector();
  }

  /** Show/hide the EAST synth param rack; the grid reflows to reclaim the width. */
  public void toggleParamRack() {
    if (rackScroll != null) {
      rackScroll.setVisible(!rackScroll.isVisible());
      revalidate();
      repaint();
    }
  }

  /** Re-read the edited track into the param rack (called on track/view changes). */
  public void refreshParamRack() {
    refreshTrackInspector();
    if (synthParamRack != null) {
      synthParamRack.refresh();
    }
    if (modKnobBar != null) {
      modKnobBar.refresh();
    }
  }

  public void updateHardwareLedDisplay(String paramCode, String valueString) {
    if (topBar != null && topBar.getParamReadout() != null) {
      if (paramCode == null || valueString == null) {
        topBar.getParamReadout().reset();
      } else {
        topBar.getParamReadout().print(paramCode, valueString);
      }
    }
  }

  public void updateHardwareLedDisplayTransient(String paramCode, String valueString) {
    if (topBar != null && topBar.getParamReadout() != null) {
      topBar.getParamReadout().printTransient(paramCode, valueString);
    }
  }

  public SwingGridPanel getClipPanel() {
    return clipPanel;
  }

  public SwingTopBarPanel.TopBarListener getTopBarListener() {
    return appTopBarListener;
  }

  public SwingGridPanel getAutoPanel() {
    return autoPanel;
  }

  public SwingGridPanel getSongPanel() {
    return songPanel;
  }

  public void setWorkspaceView(String viewName) {
    activeViewMode = viewName;
    if (topBar != null) topBar.selectViewModeButton(viewName);
    cardLayout.show(centerCardPanel, viewName);
    revalidate();
    repaint();
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

    bridge.setGlobalString(BridgeContract.G_WVOUT_FILE, filePath);
    bridge.setGlobalFloat(BridgeContract.G_WVOUT_ACTIVE, 1.0f);

    JOptionPane.showMessageDialog(
        this,
        "Export started to:\n" + filePath + "\n\nClick OK to stop export.",
        "Exporting Audio...",
        JOptionPane.INFORMATION_MESSAGE);

    bridge.setGlobalFloat(BridgeContract.G_WVOUT_ACTIVE, 0.0f);
  }

  private volatile boolean exportInProgress = false;

  private void exportWavStems() {
    if (exportInProgress) {
      JOptionPane.showMessageDialog(
          this,
          "An export is already in progress. Please wait until it completes.",
          "Export Busy",
          JOptionPane.WARNING_MESSAGE);
      return;
    }

    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("Select Directory to Export Stems");
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

    java.io.File targetDir = chooser.getSelectedFile();
    if (!targetDir.exists()) {
      targetDir.mkdirs();
    }

    String input =
        JOptionPane.showInputDialog(
            this, "Enter duration to render in seconds (0 for auto-detect from Arranger):", "0");
    if (input == null) return;

    double duration;
    try {
      duration = Double.parseDouble(input);
    } catch (NumberFormatException e) {
      JOptionPane.showMessageDialog(
          this,
          "Invalid duration. Using auto-detect.",
          "Export Stems",
          JOptionPane.WARNING_MESSAGE);
      duration = 0;
    }

    exportInProgress = true;

    JDialog progressDialog = new JDialog(this, "Exporting WAV Stems...", true);
    progressDialog.setSize(350, 120);
    progressDialog.setLocationRelativeTo(this);
    progressDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
    progressDialog.setLayout(new BorderLayout(10, 10));

    JLabel statusLabel = new JLabel("Preparing export...", JLabel.CENTER);
    JProgressBar progressBar = new JProgressBar(0, 100);
    progressBar.setStringPainted(true);

    JPanel panel = new JPanel(new GridLayout(2, 1, 5, 5));
    panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
    panel.add(statusLabel);
    panel.add(progressBar);
    progressDialog.add(panel, BorderLayout.CENTER);

    double finalDuration = duration;

    SwingWorker<Void, String> worker =
        new SwingWorker<>() {
          @Override
          protected Void doInBackground() throws Exception {
            org.deluge.project.ExportHelper.exportStems(
                currentProject,
                targetDir,
                finalDuration,
                (status, percent) -> {
                  publish(status + "|" + percent);
                });
            return null;
          }

          @Override
          protected void process(java.util.List<String> chunks) {
            String lastChunk = chunks.get(chunks.size() - 1);
            String[] parts = lastChunk.split("\\|");
            statusLabel.setText(parts[0]);
            progressBar.setValue(Integer.parseInt(parts[1]));
          }

          @Override
          protected void done() {
            exportInProgress = false;
            progressDialog.dispose();
            try {
              get(); // Check for exceptions
              JOptionPane.showMessageDialog(
                  SwingDelugeApp.this,
                  "WAV Stems exported successfully to:\n" + targetDir.getAbsolutePath(),
                  "Export Success",
                  JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
              JOptionPane.showMessageDialog(
                  SwingDelugeApp.this,
                  "WAV Stems export failed:\n" + ex.getMessage(),
                  "Export Error",
                  JOptionPane.ERROR_MESSAGE);
            }
          }
        };

    worker.execute();
    progressDialog.setVisible(true);
  }

  private void exportMidiFile() {
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("Export MIDI File");
    chooser.setSelectedFile(new java.io.File("deluge_export.mid"));
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

    java.io.File targetFile = chooser.getSelectedFile();
    String filePath = targetFile.getAbsolutePath();
    if (!filePath.toLowerCase().endsWith(".mid") && !filePath.toLowerCase().endsWith(".midi")) {
      targetFile = new java.io.File(filePath + ".mid");
    }

    try {
      org.deluge.project.ExportHelper.exportMidi(currentProject, targetFile);
      JOptionPane.showMessageDialog(
          this,
          "MIDI file exported successfully to:\n" + targetFile.getAbsolutePath(),
          "Export Success",
          JOptionPane.INFORMATION_MESSAGE);
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(
          this,
          "MIDI export failed:\n" + ex.getMessage(),
          "Export Error",
          JOptionPane.ERROR_MESSAGE);
    }
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

    JFileChooser chooser = new JFileChooser(org.deluge.project.PreferencesManager.getPatternsDir());
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

      org.deluge.project.PatternSerializer.save(pattern, target);
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
      PatternModel pattern = org.deluge.project.PatternSerializer.load(patternFile);
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
      bridge.eval(content);
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
    JFileChooser chooser = new JFileChooser(org.deluge.project.PreferencesManager.getSongsDir());
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

      org.deluge.model.KitTrackModel kit =
          org.deluge.kit.KitAssembler.assembleFromSynths(
              kitName, java.util.Arrays.asList(selected), muteGroups, pitchOffsets);

      JFileChooser saveChooser =
          new JFileChooser(org.deluge.project.PreferencesManager.getSongsDir());
      saveChooser.setDialogTitle("Save Kit As");
      saveChooser.setFileFilter(
          new javax.swing.filechooser.FileNameExtensionFilter("Kit XML", "xml", "XML"));
      saveChooser.setSelectedFile(new java.io.File(kitName + ".xml"));
      if (saveChooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

      java.io.File saveFile = saveChooser.getSelectedFile();
      if (!saveFile.getName().toLowerCase().endsWith(".xml")) {
        saveFile = new java.io.File(saveFile.getAbsolutePath() + ".xml");
      }
      org.deluge.project.KitSynthSerializer.saveKit(kit, saveFile);

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
            loadProject(org.deluge.model.ProjectModel.createDefaultProject());
          }
        });

    JMenuItem newWindowItem = new JMenuItem("New Window");
    newWindowItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_N,
            java.awt.event.InputEvent.CTRL_DOWN_MASK | java.awt.event.InputEvent.SHIFT_DOWN_MASK));
    newWindowItem.setToolTipText("Launch a second, independent Deluge instance in its own window");
    newWindowItem.addActionListener(e -> launchNewInstance());

    JMenuItem openItem = new JMenuItem("Open Project...");
    openItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_O, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    openItem.addActionListener(
        e -> {
          JFileChooser chooser =
              new JFileChooser(org.deluge.project.PreferencesManager.getSongsDir());
          chooser.setFileFilter(
              new javax.swing.filechooser.FileNameExtensionFilter("Song XML", "xml", "XML"));
          if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            final java.io.File file = chooser.getSelectedFile();
            loadProjectWithProgress(file, false);
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

    JMenuItem exportWavStemsItem = new JMenuItem("Export WAV Stems...");
    exportWavStemsItem.addActionListener(e -> exportWavStems());

    JMenuItem exportMidiItem = new JMenuItem("Export MIDI File...");
    exportMidiItem.addActionListener(e -> exportMidiFile());

    JMenuItem assembleKitItem = new JMenuItem("Assemble Kit From Synths...");
    assembleKitItem.addActionListener(e -> assembleKitFromSynths());

    JMenuItem loadScriptItem = new JMenuItem("Load Script...");
    loadScriptItem.addActionListener(e -> loadChuckScript());

    JMenuItem explorerItem = new JMenuItem("Show Explorer");
    explorerItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_E, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    explorerItem.addActionListener(
        e -> {
          if (leftFloat != null) {
            leftFloat.setVisible(!leftFloat.isVisible());
          }
        });

    JMenuItem exitItem = new JMenuItem("Exit");
    exitItem.addActionListener(e -> System.exit(0));

    fileMenu.add(newItem);
    fileMenu.add(newWindowItem);
    fileMenu.add(openItem);
    fileMenu.add(explorerItem);
    fileMenu.addSeparator();
    fileMenu.add(saveItem);
    fileMenu.add(saveAsItem);
    fileMenu.addSeparator();
    fileMenu.add(exportItem);
    fileMenu.add(exportWavStemsItem);
    fileMenu.add(exportMidiItem);
    fileMenu.addSeparator();
    fileMenu.add(assembleKitItem);

    JMenuItem importAbletonItem = new JMenuItem("Import Ableton Live Set...");
    importAbletonItem.addActionListener(
        e -> {
          String lastDir = org.deluge.project.PreferencesManager.get("last_ableton_import_dir", "");
          java.io.File initialDir = null;
          if (!lastDir.isEmpty()) {
            java.io.File testDir = new java.io.File(lastDir);
            if (testDir.exists() && testDir.isDirectory()) {
              initialDir = testDir;
            }
          }
          if (initialDir == null) {
            initialDir = org.deluge.ableton.AbletonAssetResolver.getDefaultImportDir();
          }

          JFileChooser chooser = new JFileChooser(initialDir);
          chooser.setDialogTitle("Import Ableton Live Set (.als)");
          chooser.setFileFilter(
              new javax.swing.filechooser.FileNameExtensionFilter(
                  "Ableton Live Set", "als", "ALS"));
          if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            final java.io.File file = chooser.getSelectedFile();
            if (file != null && file.getParentFile() != null) {
              org.deluge.project.PreferencesManager.set(
                  "last_ableton_import_dir", file.getParentFile().getAbsolutePath());
            }
            if (file == null || file.isDirectory()) {
              JOptionPane.showMessageDialog(
                  this,
                  "The selected path is a directory, not a file.\n"
                      + "Please double-click to navigate inside, and select a valid Ableton Live Set (.als) file!",
                  "Invalid Selection",
                  JOptionPane.WARNING_MESSAGE);
              return;
            }
            loadProjectWithProgress(file, true);
          }
        });
    fileMenu.add(importAbletonItem);

    JMenuItem importMidiItem = new JMenuItem("Import MIDI File...");
    importMidiItem.addActionListener(
        e -> {
          JFileChooser chooser =
              new JFileChooser(org.deluge.ableton.AbletonAssetResolver.getDefaultImportDir());
          chooser.setDialogTitle("Import MIDI File (.mid)");
          chooser.setFileFilter(
              new javax.swing.filechooser.FileNameExtensionFilter(
                  "MIDI File", "mid", "midi", "MID", "MIDI"));
          if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            java.io.File file = chooser.getSelectedFile();
            if (file != null && file.isFile()) {
              SwingMidiImportDialog wizard = new SwingMidiImportDialog(this, file);
              wizard.setVisible(true);
              if (wizard.isImportSuccessful() && wizard.getCompiledProject() != null) {
                currentProjectFile = null;
                loadProject(wizard.getCompiledProject());
                JOptionPane.showMessageDialog(
                    this,
                    "MIDI file compiled and imported successfully!\n" + file.getName(),
                    "Import Successful",
                    JOptionPane.INFORMATION_MESSAGE);
              }
            }
          }
        });
    fileMenu.add(importMidiItem);

    JMenuItem importAudioItem = new JMenuItem("Import Audio File...");
    importAudioItem.addActionListener(
        e -> {
          JFileChooser chooser =
              new JFileChooser(org.deluge.ableton.AbletonAssetResolver.getDefaultImportDir());
          chooser.setDialogTitle("Select Audio File to Transcribe");
          chooser.setFileFilter(
              new javax.swing.filechooser.FileNameExtensionFilter(
                  "Audio File",
                  "wav",
                  "mp3",
                  "flac",
                  "ogg",
                  "m4a",
                  "WAV",
                  "MP3",
                  "FLAC",
                  "OGG",
                  "M4A"));
          if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            java.io.File file = chooser.getSelectedFile();
            if (file != null && file.isFile()) {
              SwingAudioTranscribeDialog transcriber = new SwingAudioTranscribeDialog(this, file);
              transcriber.setVisible(true);
              if (transcriber.isTranscriptionSuccessful()
                  && transcriber.getCompiledProject() != null) {
                currentProjectFile = null;
                loadProject(transcriber.getCompiledProject());
                JOptionPane.showMessageDialog(
                    this,
                    "Audio transcribed and imported successfully!\n" + file.getName(),
                    "Import Successful",
                    JOptionPane.INFORMATION_MESSAGE);
              }
            }
          }
        });
    fileMenu.add(importAudioItem);

    JMenuItem exportAbletonItem = new JMenuItem("Export to Ableton Live Set...");
    exportAbletonItem.addActionListener(
        e -> {
          String[] options = {
            "Portable Ableton Project (Collect all samples)",
            "Render WAV Stems to Ableton Audio Tracks",
            "Standalone Ableton Live Set (.als file only)"
          };
          javax.swing.JComboBox<String> modeCombo = new javax.swing.JComboBox<>(options);
          modeCombo.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 12));

          int result =
              JOptionPane.showConfirmDialog(
                  this,
                  new Object[] {"Select Export Mode:", modeCombo},
                  "Export to Ableton Live Set",
                  JOptionPane.OK_CANCEL_OPTION,
                  JOptionPane.QUESTION_MESSAGE);
          if (result != JOptionPane.OK_OPTION) return;
          int mode = modeCombo.getSelectedIndex();

          JFileChooser chooser =
              new JFileChooser(org.deluge.ableton.AbletonAssetResolver.getDefaultImportDir());
          chooser.setDialogTitle("Export as Ableton Live Set (.als)");
          chooser.setFileFilter(
              new javax.swing.filechooser.FileNameExtensionFilter(
                  "Ableton Live Set", "als", "ALS"));
          chooser.setSelectedFile(new java.io.File("Exported Project.als"));
          if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

          java.io.File file = chooser.getSelectedFile();
          if (!file.getName().toLowerCase().endsWith(".als")) {
            file = new java.io.File(file.getAbsolutePath() + ".als");
          }

          java.io.File finalFile = file;

          if (mode == 0) {
            // Portable Ableton Project (Collect all samples)
            try {
              java.io.File projectDir = finalFile.getParentFile();
              java.io.File importedDir = new java.io.File(projectDir, "Samples/Imported");

              org.deluge.ableton.AbletonTrackExporter.PathRewriter rewriter =
                  originalPath -> {
                    if (originalPath == null || originalPath.isEmpty()) {
                      return originalPath;
                    }
                    try {
                      java.io.File originalFile =
                          new java.io.File(
                              org.deluge.project.PreferencesManager.getLibraryDir(), originalPath);
                      if (!originalFile.exists()) {
                        originalFile = new java.io.File(originalPath);
                      }
                      if (!originalFile.exists()) {
                        return originalPath;
                      }

                      importedDir.mkdirs();
                      String name = originalFile.getName();
                      java.io.File dest = new java.io.File(importedDir, name);

                      int cnt = 1;
                      String base = name;
                      String ext = "";
                      int dot = name.lastIndexOf('.');
                      if (dot > 0) {
                        base = name.substring(0, dot);
                        ext = name.substring(dot);
                      }
                      while (dest.exists()) {
                        dest = new java.io.File(importedDir, base + "_" + cnt + ext);
                        cnt++;
                      }

                      try (java.io.FileInputStream fis = new java.io.FileInputStream(originalFile);
                          java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
                        fis.transferTo(fos);
                      }
                      return "Samples/Imported/" + dest.getName();
                    } catch (Exception ex) {
                      ex.printStackTrace();
                      return originalPath;
                    }
                  };

              org.deluge.ableton.AbletonTrackExporter.exportProject(
                  currentProject, finalFile, rewriter);
              JOptionPane.showMessageDialog(
                  this,
                  "Project exported successfully as self-contained Ableton Project!\nAll samples collected in Samples/Imported/.",
                  "Export Successful",
                  JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
              ex.printStackTrace();
              JOptionPane.showMessageDialog(
                  this,
                  "Failed to export project:\n" + ex.getMessage(),
                  "Error",
                  JOptionPane.ERROR_MESSAGE);
            }
          } else if (mode == 1) {
            // Render WAV Stems to Ableton Audio Tracks
            JDialog progressDialog = new JDialog(this, "Rendering WAV Stems to Ableton...", true);
            progressDialog.setSize(350, 120);
            progressDialog.setLocationRelativeTo(this);
            progressDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
            progressDialog.setLayout(new java.awt.BorderLayout(10, 10));

            JLabel statusLabel = new JLabel("Preparing export...", JLabel.CENTER);
            JProgressBar progressBar = new JProgressBar(0, 100);
            progressBar.setStringPainted(true);

            JPanel panel = new JPanel(new java.awt.GridLayout(2, 1, 5, 5));
            panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            panel.add(statusLabel);
            panel.add(progressBar);
            progressDialog.add(panel, java.awt.BorderLayout.CENTER);

            SwingWorker<Void, String> worker =
                new SwingWorker<>() {
                  @Override
                  protected Void doInBackground() throws Exception {
                    org.deluge.ableton.AbletonTrackExporter.exportStemsProject(
                        currentProject,
                        finalFile,
                        (status, percent) -> {
                          publish(status + "|" + percent);
                        });
                    return null;
                  }

                  @Override
                  protected void process(java.util.List<String> chunks) {
                    String lastChunk = chunks.get(chunks.size() - 1);
                    String[] parts = lastChunk.split("\\|");
                    statusLabel.setText(parts[0]);
                    progressBar.setValue(Integer.parseInt(parts[1]));
                  }

                  @Override
                  protected void done() {
                    progressDialog.dispose();
                    try {
                      get(); // Check for exceptions
                      JOptionPane.showMessageDialog(
                          SwingDelugeApp.this,
                          "WAV Stems rendered and exported to Ableton Project successfully:\n"
                              + finalFile.getName(),
                          "Export Success",
                          JOptionPane.INFORMATION_MESSAGE);
                    } catch (Exception ex) {
                      JOptionPane.showMessageDialog(
                          SwingDelugeApp.this,
                          "WAV Stems Ableton export failed:\n" + ex.getMessage(),
                          "Export Error",
                          JOptionPane.ERROR_MESSAGE);
                    }
                  }
                };

            worker.execute();
            progressDialog.setVisible(true);
          } else {
            // Standalone Ableton Live Set (.als file only)
            try {
              org.deluge.ableton.AbletonTrackExporter.exportProject(
                  currentProject, finalFile, null);
              JOptionPane.showMessageDialog(
                  this,
                  "Project exported successfully as Standalone Ableton Live Set!\n"
                      + finalFile.getName(),
                  "Export Successful",
                  JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
              ex.printStackTrace();
              JOptionPane.showMessageDialog(
                  this,
                  "Failed to export project:\n" + ex.getMessage(),
                  "Error",
                  JOptionPane.ERROR_MESSAGE);
            }
          }
        });
    fileMenu.add(exportAbletonItem);

    fileMenu.addSeparator();
    fileMenu.add(loadScriptItem);
    fileMenu.addSeparator();
    fileMenu.add(exitItem);

    JMenu settingsMenu = new JMenu("Settings");

    JMenuItem sampleItem = new JMenuItem("Set SD Card Root...");
    sampleItem.addActionListener(
        e -> {
          JFileChooser chooser =
              new JFileChooser(org.deluge.project.PreferencesManager.getLibraryDir());
          chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
          if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            org.deluge.project.PreferencesManager.setLibraryDir(
                chooser.getSelectedFile().getAbsolutePath());
            sidebarPanel.reloadLibrary();
            floatingSidebar.reloadLibrary();
          }
        });

    // Always-on input monitor: opens the microphone and feeds the engine's live-input bus so
    // patches with inLeft/inRight/inStereo oscillator sources monitor the input continuously
    // (without arming the threshold sampler).
    final JCheckBoxMenuItem monitorInputItem = new JCheckBoxMenuItem("Monitor Audio Input");
    monitorInputItem.setSelected(
        org.deluge.engine.AudioInputCaptureLine.getInstance().isMonitoring());
    monitorInputItem.addActionListener(
        e -> {
          var capture = org.deluge.engine.AudioInputCaptureLine.getInstance();
          if (monitorInputItem.isSelected()) {
            capture.startMonitoring();
            if (!capture.isMonitoring()) {
              // The line was already in use (sampler armed) or failed to open.
              monitorInputItem.setSelected(false);
            }
          } else {
            capture.stopMonitoring();
          }
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
            String[] prefKeys = org.deluge.project.PreferencesManager.getKeys();
            for (String key : prefKeys) {
              if (key.startsWith("midi.learn.")) {
                org.deluge.project.PreferencesManager.remove(key);
              }
            }
            JOptionPane.showMessageDialog(
                SwingDelugeApp.this, "All learned physical MIDI CC bindings cleared successfully!");
          }
        });

    JMenuItem midiConfigItem = new JMenuItem("MIDI Settings...");
    midiConfigItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_M,
            java.awt.event.InputEvent.CTRL_DOWN_MASK | java.awt.event.InputEvent.SHIFT_DOWN_MASK));
    midiConfigItem.addActionListener(
        e -> {
          PreferencesDialog dialog =
              new PreferencesDialog(
                  SwingDelugeApp.this,
                  midiService,
                  () -> {
                    org.deluge.project.PreferencesManager.GridMode mode =
                        org.deluge.project.PreferencesManager.getGridMode();
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
          dialog.selectMidiTab();
          dialog.setVisible(true);
        });

    JMenuItem prefItem = new JMenuItem("Preferences...");
    prefItem.addActionListener(
        e -> {
          PreferencesDialog dialog =
              new PreferencesDialog(
                  SwingDelugeApp.this,
                  midiService,
                  () -> {
                    org.deluge.project.PreferencesManager.GridMode mode =
                        org.deluge.project.PreferencesManager.getGridMode();
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
          dialog.setVisible(true);
        });

    showMonitorItem = new JCheckBoxMenuItem("Acoustics Monitor");
    showMonitorItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_M, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    showMonitorItem.addActionListener(
        e -> {
          if (rightFloat != null) {
            rightFloat.setVisible(showMonitorItem.isSelected());
          }
        });

    settingsMenu.add(sampleItem);
    settingsMenu.addSeparator();
    JMenuItem tuningItem = new JMenuItem("Tuning & Temperaments...");
    tuningItem.addActionListener(
        e -> {
          SwingTuningDialog dialog =
              new SwingTuningDialog(
                  this,
                  currentProject,
                  () -> {
                    syncHighFidelityEngine(currentProject, true);
                  });
          dialog.setVisible(true);
        });

    settingsMenu.add(monitorInputItem);
    settingsMenu.add(midiConfigItem);
    settingsMenu.add(clearMidiItem);
    settingsMenu.addSeparator();
    settingsMenu.add(tuningItem);
    settingsMenu.addSeparator();
    settingsMenu.add(prefItem);
    settingsMenu.add(showMonitorItem);

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

    // Tools menu — Delugeator Randomizer & Loop Slicer
    JMenu toolsMenu = new JMenu("Tools");
    JMenuItem randomizerItem = new JMenuItem("Delugeator Randomizer...");
    randomizerItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_R, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    randomizerItem.addActionListener(
        e -> {
          new SwingRandomizerDialog(this, bridge, currentProject).setVisible(true);
        });
    toolsMenu.add(randomizerItem);

    JMenuItem slicerItem = new JMenuItem("Audio Loop Slicer...");
    slicerItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_L, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    slicerItem.addActionListener(
        e -> {
          new SwingAudioSlicerDialog(this, bridge, currentProject).setVisible(true);
        });
    toolsMenu.add(slicerItem);

    JMenuItem thresholdSamplerItem = new JMenuItem("Threshold Loop Sampler...");
    thresholdSamplerItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_H, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    thresholdSamplerItem.addActionListener(
        e -> {
          new ThresholdRecordDialog(
                  this,
                  currentProject,
                  () -> {
                    // Rebuild the engine so the just-recorded sample is loaded into its kit slot /
                    // synth osc and is immediately audible, then refresh the grid.
                    syncHighFidelityEngine(currentProject, true);
                    if (songPanel != null) songPanel.refresh();
                    if (clipPanel != null) clipPanel.refresh();
                  })
              .setVisible(true);
        });
    toolsMenu.add(thresholdSamplerItem);

    JMenuItem droneLabItem = new JMenuItem("Drone Lab & Texture Generator...");
    droneLabItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_D, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    droneLabItem.addActionListener(
        e -> {
          SwingGridPanel a = activeGridPanel();
          if (a == null || a.getProjectModel() == null) {
            JOptionPane.showMessageDialog(
                this, "Please load a project first.", "Drone Lab", JOptionPane.WARNING_MESSAGE);
            return;
          }
          int idx = a.getEditedModelTrack();
          java.util.List<org.deluge.model.TrackModel> tl = a.getProjectModel().getTracks();
          if (idx < 0 || idx >= tl.size()) {
            JOptionPane.showMessageDialog(
                this,
                "Please select a valid track first.",
                "Drone Lab",
                JOptionPane.WARNING_MESSAGE);
            return;
          }
          org.deluge.model.TrackModel t = tl.get(idx);
          if (!(t instanceof org.deluge.model.SynthTrackModel st)) {
            JOptionPane.showMessageDialog(
                this,
                "Drone Lab requires a Synthesizer track. Please select or create a Synth track first.",
                "Drone Lab",
                JOptionPane.WARNING_MESSAGE);
            return;
          }
          new SwingDroneLabDialog(this, st, bridge, idx, currentProject).setVisible(true);
        });
    toolsMenu.add(droneLabItem);

    JMenuItem cleanRecordingsItem = new JMenuItem("Clean Unused Recordings...");
    cleanRecordingsItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_U, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    cleanRecordingsItem.addActionListener(
        e -> {
          new SwingRecordingCleanerDialog(this).setVisible(true);
        });
    toolsMenu.add(cleanRecordingsItem);

    // Help menu — Operations Manual JDialog
    JMenu helpMenu = new JMenu("Help");
    JMenuItem manualItem = new JMenuItem("Operations Manual...");
    manualItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F1, 0));
    manualItem.addActionListener(
        e -> {
          new SwingHelpDialog(this).setVisible(true);
        });
    helpMenu.add(manualItem);

    // View menu — Grid Zooming & Resolution Options
    JMenu viewMenu = new JMenu("View");

    JMenuItem zoomInItem = new JMenuItem("Zoom In (Larger Pads)");
    zoomInItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_EQUALS, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    zoomInItem.addActionListener(e -> zoomGrid(true));

    JMenuItem zoomOutItem = new JMenuItem("Zoom Out (Smaller Pads)");
    zoomOutItem.setAccelerator(
        KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_MINUS, java.awt.event.InputEvent.CTRL_DOWN_MASK));
    zoomOutItem.addActionListener(e -> zoomGrid(false));

    viewMenu.add(zoomInItem);
    viewMenu.add(zoomOutItem);
    viewMenu.addSeparator();

    grid8x16Item = new JRadioButtonMenuItem("8x16 Grid (Large Pads)");
    grid16x16Item = new JRadioButtonMenuItem("16x16 Grid (Medium Pads)");
    grid24x16Item = new JRadioButtonMenuItem("24x16 Grid (Small Pads)");
    grid16x24Item = new JRadioButtonMenuItem("16x24 Grid (Wide Pads)");

    ButtonGroup gridGroup = new ButtonGroup();
    gridGroup.add(grid8x16Item);
    gridGroup.add(grid16x16Item);
    gridGroup.add(grid24x16Item);
    gridGroup.add(grid16x24Item);

    grid8x16Item.addActionListener(
        e -> updateGlobalGridMode(org.deluge.project.PreferencesManager.GridMode.GRID_8x16));
    grid16x16Item.addActionListener(
        e -> updateGlobalGridMode(org.deluge.project.PreferencesManager.GridMode.GRID_16x16));
    grid24x16Item.addActionListener(
        e -> updateGlobalGridMode(org.deluge.project.PreferencesManager.GridMode.GRID_24x16));
    grid16x24Item.addActionListener(
        e -> updateGlobalGridMode(org.deluge.project.PreferencesManager.GridMode.GRID_16x24));

    viewMenu.add(grid8x16Item);
    viewMenu.add(grid16x16Item);
    viewMenu.add(grid24x16Item);
    viewMenu.add(grid16x24Item);

    updateViewMenuChecks();

    menuBar.add(fileMenu);
    menuBar.add(editMenu);
    menuBar.add(viewMenu);
    menuBar.add(toolsMenu);
    menuBar.add(settingsMenu);
    menuBar.add(helpMenu);
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

    // F12 debug screenshot listener (programmatic memory capture)
    getRootPane()
        .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        .put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F12, 0), "screenshot");
    getRootPane()
        .getActionMap()
        .put(
            "screenshot",
            new AbstractAction() {
              @Override
              public void actionPerformed(java.awt.event.ActionEvent e) {
                try {
                  java.io.File scratchDir =
                      new java.io.File(System.getProperty("user.home"), ".deluge/screenshots");
                  if (!scratchDir.exists()) scratchDir.mkdirs();
                  java.io.File file = new java.io.File(scratchDir, "swing_screenshot.png");

                  java.awt.image.BufferedImage img =
                      new java.awt.image.BufferedImage(
                          getWidth(), getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                  java.awt.Graphics2D g = img.createGraphics();
                  paint(g);
                  g.dispose();

                  javax.imageio.ImageIO.write(img, "png", file);
                  System.out.println(
                      "[Debug] Screenshot captured successfully to: " + file.getAbsolutePath());

                  JOptionPane.showMessageDialog(
                      SwingDelugeApp.this,
                      "Screenshot captured successfully to:\n" + file.getName(),
                      "Screenshot Captured",
                      JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                  ex.printStackTrace();
                }
              }
            });

    leftFloat = new JDialog(this, "SD Explorer", false);
    leftFloat.setSize(300, 700);
    leftFloat.setLocation(50, 150);

    rightFloat = new JDialog(this, "Acoustics Monitor", false);
    rightFloat.setSize(280, 700);
    rightFloat.setLocation(1600, 150);
    rightFloat.addWindowListener(
        new java.awt.event.WindowAdapter() {
          @Override
          public void windowOpened(java.awt.event.WindowEvent e) {
            if (showMonitorItem != null) showMonitorItem.setSelected(true);
          }

          @Override
          public void windowClosing(java.awt.event.WindowEvent e) {
            if (showMonitorItem != null) showMonitorItem.setSelected(false);
          }

          @Override
          public void windowClosed(java.awt.event.WindowEvent e) {
            if (showMonitorItem != null) showMonitorItem.setSelected(false);
          }
        });

    // 1. Top Area (Buttons, Modes, Transport, Sliders)

    cardLayout = new CardLayout();
    centerCardPanel = new JPanel(cardLayout);

    Runnable projectChangeHandler =
        () -> {
          propagateCurrentModel();
          syncHighFidelityEngine(currentProject);
        };

    clipPanel = new SwingGridPanel(bridge);
    clipPanel.setViewMode(SwingGridPanel.GridViewMode.CLIP);
    clipPanel.setProjectModel(currentProject);
    clipPanel.resetScrollOffset();
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
    this.picTransport = new org.deluge.hid.pic.SwingPicTransport();
    picTransport.setPadButtons(clipPanel.getPadButtons());
    PIC.setTransport(picTransport);

    songPanel = new SwingGridPanel(bridge);
    songPanel.setViewMode(SwingGridPanel.GridViewMode.SONG);
    songPanel.setProjectModel(currentProject);
    songPanel.setOnProjectChanged(projectChangeHandler);
    centerCardPanel.add(wrapGridPanel(songPanel), "SONG");

    arrGridPanel = new SwingGridPanel(bridge);
    arrGridPanel.setViewMode(SwingGridPanel.GridViewMode.ARRANGEMENT);
    arrGridPanel.setProjectModel(currentProject);
    arrGridPanel.setOnProjectChanged(projectChangeHandler);
    centerCardPanel.add(wrapGridPanel(arrGridPanel), "ARR");

    autoPanel = new SwingGridPanel(bridge);
    autoPanel.setViewMode(SwingGridPanel.GridViewMode.AUTOMATION);
    autoPanel.setProjectModel(currentProject);
    autoPanel.setOnProjectChanged(projectChangeHandler);
    centerCardPanel.add(wrapGridPanel(autoPanel), "AUTO");

    performancePanel = new SwingPerformanceViewPanel(bridge, currentProject);
    performancePanel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
    centerCardPanel.add(performancePanel, "PERF");

    appTopBarListener = new AppTopBarListener();
    topBar = new SwingTopBarPanel(bridge, currentProject, leftFloat, appTopBarListener);

    // DEBUG: solid background colors to visualize panel sizes
    System.out.println(
        "DEBUG setupUI: topBar bg="
            + topBar.getBackground()
            + " contentPane bg="
            + getContentPane().getBackground());

    JPanel topBarWrapper = new JPanel(new BorderLayout());
    topBarWrapper.add(topBar, BorderLayout.CENTER);

    // Scroll encoders (X timeline / Y note rows) routed to whichever grid panel is showing.
    org.deluge.ui.controls.DelugeEncoderStrip encoderStrip =
        new org.deluge.ui.controls.DelugeEncoderStrip(
            d -> {
              SwingGridPanel a = activeGridPanel();
              if (a != null) {
                if (a.isShiftHeld()) {
                  a.adjustZoomResolution(d);
                } else {
                  a.scrollHorizontally(d);
                }
              }
            },
            d -> {
              SwingGridPanel a = activeGridPanel();
              if (a != null) {
                if (a.isShiftHeld()) {
                  a.adjustTrackColorOffset(d);
                } else {
                  a.scrollVertically(-d);
                }
              }
            },
            d -> {
              // Gold mod-encoder: adjust whichever param is selected via the Shift overlay.
              SwingGridPanel a = activeGridPanel();
              if (a != null && a.getActiveShiftParam() != null) {
                a.adjustRotaryParameter(d);
              }
            });

    encoderStrip
        .getXKnob()
        .onPress(
            () -> {
              SwingGridPanel a = activeGridPanel();
              if (a != null && a.isShiftHeld()) {
                a.duplicateTrackContent();
              }
            });

    encoderStrip
        .getXKnob()
        .onPressTurn(
            d -> {
              SwingGridPanel a = activeGridPanel();
              if (a != null) {
                a.adjustZoomResolution(d);
              }
            });

    encoderStrip
        .getYKnob()
        .onPressTurn(
            d -> {
              SwingGridPanel a = activeGridPanel();
              if (a != null) {
                if (a.isShiftHeld()) {
                  a.transposeTrack(d); // Shift held = semitone transposition
                } else {
                  a.transposeTrack(d * 12); // Shift not held = octave transposition
                }
              }
            });

    encoderStrip.setBackground(topBar.getBackground());
    encoderStrip.setOpaque(true);
    topBarWrapper.add(encoderStrip, BorderLayout.EAST);
    add(topBarWrapper, BorderLayout.NORTH);

    JScrollPane centerScroll =
        new JScrollPane(
            centerCardPanel,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

    // DEBUG: centerScroll.setBorder(BorderFactory.createLineBorder(Color.YELLOW, 3));
    /*centerScroll.getViewport().setOpaque(true);
    centerScroll.getViewport().setBackground(Color.BLUE);*/

    // Fixed track-inspector strip ABOVE the scrolling grid: per-track controls (configure + preset)
    // belong outside the scroll area so you never have to scroll up to reach them.
    JPanel centerWrap = new JPanel(new BorderLayout());
    centerWrap.setOpaque(false);
    centerWrap.add(buildTrackInspectorStrip(), BorderLayout.NORTH);
    centerWrap.add(centerScroll, BorderLayout.CENTER);
    add(centerWrap, BorderLayout.CENTER);

    // Hardware-faithful Gold Knobs & Mod Buttons bar docked WEST
    modKnobBar =
        new org.deluge.ui.controls.DelugeModKnobBar(
            bridge,
            () -> {
              SwingGridPanel a = activeGridPanel();
              if (a == null || a.getProjectModel() == null) {
                return null;
              }
              int idx = a.getEditedModelTrack();
              if (idx < 0 || idx >= a.getProjectModel().getTracks().size()) {
                return null;
              }
              return (a.getProjectModel().getTracks().get(idx)
                      instanceof org.deluge.model.SynthTrackModel st)
                  ? st
                  : null;
            },
            () -> {
              SwingGridPanel a = activeGridPanel();
              return a == null ? -1 : a.getEditedModelTrack();
            },
            this::activeGridPanel);
    add(modKnobBar, BorderLayout.WEST);

    // Always-visible synth param rack docked EAST (collapsible via the RACK button). It costs only
    // horizontal space (abundant) so the grid keeps its full height; scrollable for short screens.
    synthParamRack =
        new SynthParamRack(
            bridge,
            () -> {
              SwingGridPanel a = activeGridPanel();
              if (a == null || a.getProjectModel() == null) {
                return null;
              }
              int idx = a.getEditedModelTrack();
              if (idx < 0 || idx >= a.getProjectModel().getTracks().size()) {
                return null;
              }
              return (a.getProjectModel().getTracks().get(idx)
                      instanceof org.deluge.model.SynthTrackModel st)
                  ? st
                  : null;
            },
            () -> {
              SwingGridPanel a = activeGridPanel();
              return a == null ? -1 : a.getEditedModelTrack();
            });
    synthParamRack.setPresetActions(
        f -> { // Replace the edited synth track's preset in place (preserve clips + colour).
          try {
            SwingGridPanel a = activeGridPanel();
            if (a == null || a.getProjectModel() == null) return;
            int idx = a.getEditedModelTrack();
            java.util.List<org.deluge.model.TrackModel> tl = a.getProjectModel().getTracks();
            if (idx < 0 || idx >= tl.size()) return;
            org.deluge.model.TrackModel old = tl.get(idx);
            org.deluge.model.SynthTrackModel nt = org.deluge.xml.DelugeXmlParser.parseSynth(f);
            nt.getClips().clear();
            for (org.deluge.model.ClipModel cm : old.getClips()) nt.addClip(cm);
            nt.setColourHex(old.getColourHex());
            tl.set(idx, nt);
            propagateCurrentModel();
            syncHighFidelityEngine(currentProject, true); // preset swap: rebuild even while playing
            a.forceRebuild(); // structure unchanged on a swap → force header/name rebuild
            synthParamRack.refresh();
            if (modKnobBar != null) modKnobBar.refresh();
          } catch (Exception ex) {
            System.err.println("[PresetChip] replace failed: " + ex.getMessage());
          }
        },
        f -> { // Load the preset as a brand-new synth track.
          try {
            org.deluge.model.SynthTrackModel nt = org.deluge.xml.DelugeXmlParser.parseSynth(f);
            currentProject.addTrack(nt);
            propagateCurrentModel();
            syncHighFidelityEngine(currentProject);
            if (clipPanel != null) clipPanel.refresh();
            synthParamRack.refresh();
            if (modKnobBar != null) modKnobBar.refresh();
          } catch (Exception ex) {
            System.err.println("[PresetChip] load-new failed: " + ex.getMessage());
          }
        });
    rackScroll =
        new JScrollPane(
            synthParamRack,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    rackScroll.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(0x2d, 0x2d, 0x34)));
    rackScroll.getVerticalScrollBar().setUnitIncrement(16);
    rackScroll.setPreferredSize(new Dimension(312, 100));
    add(rackScroll, BorderLayout.EAST);

    javax.swing.SwingUtilities.invokeLater(() -> centerScroll.getVerticalScrollBar().setValue(0));

    // 2. Left Area (SD Card / Editors)
    sidebarPanel = new SwingProjectSidebarPanel(bridge, midiService);
    sidebarPanel.reloadLibrary();
    floatingSidebar = new SwingProjectSidebarPanel(bridge, midiService);
    // The explorer header's 📂 button changes the SD-card root; refresh BOTH sidebar instances
    // (same effect as File → "Set SD Card Root..." and Preferences → SD Card Root Directory).
    Runnable reloadBothSidebars =
        () -> {
          sidebarPanel.reloadLibrary();
          floatingSidebar.reloadLibrary();
        };
    sidebarPanel.setOnLibraryDirChanged(reloadBothSidebars);
    floatingSidebar.setOnLibraryDirChanged(reloadBothSidebars);
    sidebarPanel.setOnSongLoaded(
        (model, file) -> {
          currentProjectFile = file;
          // Load shared project state (clears pattern, updates all panels, fires engine reload, and
          // switches views)
          loadProject(model);

          // Push model data to engine — use the centralized mapping
          // so Kit sounds get written to the correct engine rows
          if (trackEngineStart != null) {
            pushModelToBridge();
          }
        });

    floatingSidebar.setOnSongLoaded(sidebarPanel.getOnSongLoaded());

    // Wire sidebar "add track" callback — KITS/SYNTHS double-click adds to current project
    java.util.function.Consumer<org.deluge.model.TrackModel> addTrack =
        track -> {
          // Add a default clip so notes entered on grid are stored in the model
          switch (track) {
            case org.deluge.model.KitTrackModel kit -> {
              int rowCount = kit.getDrums().size();
              if (rowCount < 1) rowCount = 1;
              kit.addClip(new org.deluge.model.ClipModel("CLIP 1", rowCount, 16));
            }
            case org.deluge.model.SynthTrackModel synth ->
                synth.addClip(new org.deluge.model.ClipModel("CLIP 1", 8, 16));
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

    songPanel.setOnEditRequest(this::switchToTrackEdit);
    arrGridPanel.setOnEditRequest(this::switchToTrackEdit);

    visualizerPanel = new SwingVisualizerPanel(bridge);

    leftFloat.add(floatingSidebar);
    rightFloat.add(visualizerPanel);

    visualizerRepaintTimer = new Timer(33, e -> visualizerPanel.repaint());
    visualizerRepaintTimer.start();

    // Periodically flush PIC framebuffer to Swing pad buttons (≈30 fps)
    if (this.pureEngine == null) {
      picTransportFlushTimer =
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
              });
      picTransportFlushTimer.start();
    }

    // bottom lane purged

    // 5. Bottom Area - Rows 9 and 10 (Param Deck)
    // Obsolete bottom parameter deck removed. Integrated in 10x18 pads matrix.

    // 7. Bottom Area - Row 3 (Master FX dials bounding boxes)
    SwingMasterFxPanel masterFxPanel = new SwingMasterFxPanel(bridge, currentProject, topBar);
    this.masterFxPanel = masterFxPanel;
    // DEBUG: masterFxPanel.setBorder(BorderFactory.createLineBorder(Color.YELLOW, 3));

    masterFxPanel.setPreferredSize(new Dimension(1, 54));
    add(masterFxPanel, BorderLayout.SOUTH);

    revalidate();

    // Push default project to engine and broadcast load trigger to unblock shreds
    pushModelToBridge();

    // Programmatic startup song file loader!
    if (startupFilePath != null) {
      java.io.File file = new java.io.File(startupFilePath);
      if (file.exists()) {
        try {
          org.deluge.model.ProjectModel loaded = org.deluge.xml.DelugeXmlParser.parseSong(file);
          currentProjectFile = file;
          loadProject(loaded);
          System.out.println(
              "[main] Successfully pre-loaded startup song project: " + file.getName());
        } catch (Exception ex) {
          System.err.println("[main] Startup load failed: " + ex.getMessage());
        }
      }
    }
    // Only run the (grid-mode-cycling) screenshot pipeline when explicitly requested via
    // --screenshot. On a normal boot this would visibly resize the grid 2-3 times.
    if (autoScreenshotOnBoot) {
      captureAutoScreenshot("startup");
    }
  }

  /**
   * Launch a second, fully independent Deluge in a NEW OS process (its own JVM). We deliberately do
   * not run two instances in one JVM: the firmware/hid layer (MatrixDriver, Flasher, PIC,
   * FirmwareDisplay) and a few audio statics (noise seed, sidechain bus, cpuDireness) plus {@code
   * mainInstance} are process-global singletons that two in-JVM instances would corrupt. A separate
   * process sidesteps all of that and gets its own audio line (the OS mixer sums them).
   *
   * <p>We relaunch the *exact* current command (java binary + all JVM flags + jar) via {@link
   * ProcessHandle}, so required flags (--enable-preview, --add-modules jdk.incubator.vector, ZGC,
   * native-access) are preserved. Falls back to reconstructing from the running JVM if the OS does
   * not expose the command line.
   */
  private void launchNewInstance() {
    try {
      java.util.List<String> cmd = new java.util.ArrayList<>();
      ProcessHandle.Info info = ProcessHandle.current().info();
      String exe = info.command().orElse(null);
      String[] args = info.arguments().orElse(null);
      if (exe != null && args != null) {
        cmd.add(exe);
        java.util.Collections.addAll(cmd, args);
      } else {
        // Fallback: java.home/bin/java + the JVM's own input args + classpath + this main class.
        cmd.add(
            System.getProperty("java.home")
                + java.io.File.separator
                + "bin"
                + java.io.File.separator
                + "java");
        cmd.addAll(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(SwingDelugeApp.class.getName());
      }
      ProcessBuilder pb = new ProcessBuilder(cmd);
      pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
      pb.redirectError(ProcessBuilder.Redirect.DISCARD);
      pb.start();
      System.out.println("[NewInstance] Launched a second Deluge process: " + cmd);
    } catch (Exception ex) {
      System.err.println("[NewInstance] Failed to launch: " + ex.getMessage());
      JOptionPane.showMessageDialog(
          this,
          "Could not launch a new Deluge window:\n" + ex.getMessage(),
          "New Window",
          JOptionPane.ERROR_MESSAGE);
    }
  }

  /** No-op: centeredWrapper removed, scroll pane sizes to content naturally. */
  public void recalcWrapperSize() {
    // content naturally sizes the scroll viewport
  }

  private void captureSingleScreenshot(String name) {
    try {
      java.io.File scratchDir =
          new java.io.File(System.getProperty("user.home"), ".deluge/screenshots");
      if (!scratchDir.exists()) scratchDir.mkdirs();
      java.io.File file = new java.io.File(scratchDir, name + ".png");

      java.awt.image.BufferedImage img =
          new java.awt.image.BufferedImage(
              getWidth() > 0 ? getWidth() : 1200,
              getHeight() > 0 ? getHeight() : 800,
              java.awt.image.BufferedImage.TYPE_INT_RGB);
      java.awt.Graphics2D g = img.createGraphics();
      paint(g);
      g.dispose();

      javax.imageio.ImageIO.write(img, "png", file);
      System.out.println("[AutoScreenshot] Captured successfully: " + file.getAbsolutePath());
    } catch (Exception ex) {
      System.err.println("[AutoScreenshot] Failed: " + ex.getMessage());
    }
  }

  public void captureAutoScreenshot(String name) {
    if (!"startup".equals(name)) {
      javax.swing.SwingUtilities.invokeLater(
          () -> {
            javax.swing.Timer t =
                new javax.swing.Timer(
                    800,
                    e -> {
                      captureSingleScreenshot(name);
                    });
            t.setRepeats(false);
            t.start();
          });
      return;
    }

    javax.swing.SwingUtilities.invokeLater(
        () -> {
          // Step 1: Wait 800ms, then capture default startup screenshot (GRID_16x24)
          javax.swing.Timer t1 =
              new javax.swing.Timer(
                  800,
                  e1 -> {
                    captureSingleScreenshot("startup");

                    // Step 2: Switch to GRID_8x16 zoom, repaint, and wait 800ms to capture
                    org.deluge.project.PreferencesManager.GridMode originalMode =
                        clipPanel.getGridMode();
                    clipPanel.setGridMode(org.deluge.project.PreferencesManager.GridMode.GRID_8x16);
                    clipPanel.refresh();
                    revalidate();
                    repaint();

                    javax.swing.Timer t2 =
                        new javax.swing.Timer(
                            800,
                            e2 -> {
                              captureSingleScreenshot("zoom_GRID_8x16");

                              // Step 3: Switch to GRID_24x16 zoom, repaint, and wait 800ms to
                              // capture
                              clipPanel.setGridMode(
                                  org.deluge.project.PreferencesManager.GridMode.GRID_24x16);
                              clipPanel.refresh();
                              revalidate();
                              repaint();

                              javax.swing.Timer t3 =
                                  new javax.swing.Timer(
                                      800,
                                      e3 -> {
                                        captureSingleScreenshot("zoom_GRID_24x16");

                                        // Step 4: Restore original zoom level and refresh
                                        clipPanel.setGridMode(originalMode);
                                        clipPanel.refresh();
                                        revalidate();
                                        repaint();
                                        System.out.println(
                                            "[AutoScreenshot] Complete visual capture pipeline finished!");
                                      });
                              t3.setRepeats(false);
                              t3.start();
                            });
                    t2.setRepeats(false);
                    t2.start();
                  });
          t1.setRepeats(false);
          t1.start();
        });
  }

  private void startPlaybackTimer() {
    if (statusTextPlaybackTimer != null) {
      statusTextPlaybackTimer.stop();
    }
    statusTextPlaybackTimer =
        new Timer(
            30,
            e -> {
              int step = (int) bridge.getGlobalInt(BridgeContract.G_CURRENT_STEP);

              int stepCount = 16;
              int beatSteps = 4;
              if (currentProject != null
                  && !currentProject.getTracks().isEmpty()
                  && !currentProject.getTracks().get(0).getClips().isEmpty()) {
                boolean isTriplet =
                    currentProject.getTracks().get(0).getClips().get(0).isTripletMode();
                stepCount = isTriplet ? 12 : 16;
                beatSteps = isTriplet ? 3 : 4;
              }

              int bar = step / stepCount + 1;
              int beat = (step % stepCount) / beatSteps + 1;
              int subStep = (step % beatSteps) + 1;

              String statusStr = "STOP";
              if (bridge.getGlobalInt(BridgeContract.G_PLAY) == 1L) {
                statusStr = String.format("%d.%d.%d", bar, beat, subStep);
              }
              statusStr += " | SHREDS: " + bridge.getActiveShredCount();

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
    statusTextPlaybackTimer.start();
  }

  public static String startupFilePath = null;

  public static void main(String[] args) {
    for (String arg : args) {
      if (arg.toUpperCase().endsWith(".XML")) {
        startupFilePath = arg;
      }
    }
    // Configure global tooltip timing parameters (pop up after 250ms, keep open for 20s)
    javax.swing.ToolTipManager.sharedInstance().setDismissDelay(20000);
    javax.swing.ToolTipManager.sharedInstance().setInitialDelay(250);

    BridgeContract bridge = new BridgeContract();

    // The pure Java firmware engine (PureFirmwareEngine) is the only audio path; the legacy
    // ChucK DSL engine (--hifi) was deleted.
    System.out.println(
        "[main] Pure Java (Pure Firmware) direct soundcard output ENABLED by default");

    boolean runScreenshots = false;
    for (String arg : args) {
      if ("--screenshot".equalsIgnoreCase(arg)) {
        runScreenshots = true;
      }
    }
    final boolean finalRunScreenshots = runScreenshots;
    // Gate the boot grid-mode-cycling screenshot pipeline (set before the app is constructed, since
    // the constructor runs the boot path).
    autoScreenshotOnBoot = runScreenshots;

    bridge.register(bridge);

    // Give engine time to initialize before UI loads
    try {
      Thread.sleep(200);
    } catch (InterruptedException ie) {
    }
    System.out.println("[main] after 200ms sleep, activeShreds=" + bridge.getActiveShredCount());

    org.deluge.midi.MidiInputRouter router = new org.deluge.midi.MidiInputRouter(bridge);
    org.deluge.midi.MidiService midiService = new org.deluge.midi.MidiService(bridge, router);
    midiService.start();

    final boolean finalPureMode = true;
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

          // Global JComboBox theme overrides (removes white-on-white text issues)
          javax.swing.UIManager.put("ComboBox.background", new java.awt.Color(0x2d, 0x2d, 0x30));
          javax.swing.UIManager.put("ComboBox.foreground", java.awt.Color.WHITE);
          javax.swing.UIManager.put(
              "ComboBox.selectionBackground", new java.awt.Color(0x00, 0x7a, 0xcc));
          javax.swing.UIManager.put("ComboBox.selectionForeground", java.awt.Color.WHITE);
          javax.swing.UIManager.put(
              "ComboBox.buttonBackground", new java.awt.Color(0x2d, 0x2d, 0x30));
          javax.swing.UIManager.put(
              "ComboBox.buttonDarkShadow", new java.awt.Color(0x1a, 0x1a, 0x1c));

          // Global dropdown list/viewport popups overrides (solves white list background)
          javax.swing.UIManager.put("List.background", new java.awt.Color(0x1e, 0x1e, 0x20));
          javax.swing.UIManager.put("List.foreground", java.awt.Color.WHITE);
          javax.swing.UIManager.put(
              "List.selectionBackground", new java.awt.Color(0x00, 0x7a, 0xcc));
          javax.swing.UIManager.put("List.selectionForeground", java.awt.Color.WHITE);

          // Global JSlider UI delegate registration (solves black-on-black tracks bug!)
          javax.swing.UIManager.put("SliderUI", "org.deluge.ui.DarkSliderUI");

          // Global text inputs focus and contrast overrides
          javax.swing.UIManager.put("TextField.background", new java.awt.Color(0x1e, 0x1e, 0x20));
          javax.swing.UIManager.put("TextField.foreground", java.awt.Color.WHITE);
          javax.swing.UIManager.put("TextField.caretForeground", java.awt.Color.WHITE);

          javax.swing.UIManager.put("TextArea.background", new java.awt.Color(0x1e, 0x1e, 0x20));
          javax.swing.UIManager.put("TextArea.foreground", java.awt.Color.WHITE);
          javax.swing.UIManager.put("TextArea.caretForeground", java.awt.Color.WHITE);

          SwingDelugeApp app = new SwingDelugeApp(bridge, midiService, finalPureMode);
          app.setVisible(true);

          // Benchmark/training hook: clean self-exit after N ms (System.exit -> normal shutdown, so
          // an -XX:AOTCacheOutput training run actually writes its cache). Off unless set.
          long benchExitMs = Long.getLong("deluge.benchExitMs", -1L);
          if (benchExitMs >= 0) {
            javax.swing.Timer bx = new javax.swing.Timer((int) benchExitMs, e -> System.exit(0));
            bx.setRepeats(false);
            bx.start();
          }

          if (finalRunScreenshots) {
            new Thread(
                    () -> {
                      try {
                        System.out.println(
                            "[Screenshot] Waiting 4 seconds for UI repaint and Loom threads...");
                        Thread.sleep(4000);
                        SwingScreenshotGenerator.runAutoScreenshots(app, bridge);
                      } catch (Exception ex) {
                        System.err.println("[Screenshot] Trigger thread error: " + ex.getMessage());
                      }
                    })
                .start();
          }
          // Auto-load if a file path is provided as argument
          if (args.length > 0 && args[0] != null && !args[0].isEmpty()) {
            try {
              java.io.File f = new java.io.File(args[0]);
              if (f.exists()) {
                System.out.println("[main] Auto-loading: " + f.getAbsolutePath());
                org.deluge.model.ProjectModel model =
                    org.deluge.xml.DelugeXmlParser.parseSong(
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

  public SwingGridPanel getActiveGridPanel() {
    return activeGridPanel();
  }

  public void scrollActiveTrack(int offset) {
    if (currentProject == null) return;
    java.util.List<org.deluge.model.TrackModel> tracks = currentProject.getTracks();
    if (tracks.isEmpty()) return;

    int currentTrackIdx = clipPanel != null ? clipPanel.getEditedModelTrack() : 0;
    int newTrackIdx = currentTrackIdx + offset;
    if (newTrackIdx < 0) newTrackIdx = 0;
    if (newTrackIdx >= tracks.size()) newTrackIdx = tracks.size() - 1;

    if (newTrackIdx != currentTrackIdx) {
      final int targetTrack = newTrackIdx;
      javax.swing.SwingUtilities.invokeLater(
          () -> {
            switchToTrackEdit(targetTrack, 0);
          });
    }
  }

  public void cycleViewMode() {
    String[] modes = {"CLIP", "SONG", "ARR", "AUTO", "PERF"};
    int currentIdx = 0;
    for (int i = 0; i < modes.length; i++) {
      if (modes[i].equals(activeViewMode)) {
        currentIdx = i;
        break;
      }
    }
    int nextIdx = (currentIdx + 1) % modes.length;
    String nextMode = modes[nextIdx];

    activeViewMode = nextMode;
    cardLayout.show(centerCardPanel, nextMode);
    if (topBar != null) {
      topBar.selectViewModeButton(nextMode);
    }
    if ("CLIP".equals(nextMode) || "SONG".equals(nextMode)) {
      syncHighFidelityEngine(currentProject);
    }
  }

  public void switchToTrackEdit(int trackId, int clipId) {
    sidebarPanel.updateFocusTrack(trackId);
    if (midiService != null) {
      midiService.setActiveTrack(trackId);
    }

    if (clipPanel != null && trackId < trackEngineStart.length) {
      int engineBase = trackEngineStart[trackId];
      int voiceCount = trackVoiceCount[trackId];

      clipPanel.setActiveClipId(clipId);
      clipPanel.setBaseTrackId(engineBase);
      clipPanel.setEditedModelTrack(trackId);
      clipPanel.resetScrollOffset();

      java.util.List<org.deluge.model.TrackModel> allTrks =
          clipPanel.getProjectModel() != null
              ? clipPanel.getProjectModel().getTracks()
              : java.util.List.of();

      boolean editIsSynth =
          trackId < allTrks.size()
              && allTrks.get(trackId) instanceof org.deluge.model.SynthTrackModel;

      // Determine step count for clearing and re-pushing
      int clearSteps = 16;
      if (trackId < allTrks.size()) {
        org.deluge.model.TrackModel tModel = allTrks.get(trackId);
        if (clipId < tModel.getClips().size()) {
          clearSteps = tModel.getClips().get(clipId).getStepCount();
        }
      }

      // Clear engine rows for this track
      for (int v = 0; v < voiceCount; v++) {
        for (int s = 0; s < clearSteps; s++) bridge.setStep(engineBase + v, s, false);
      }

      if (trackId < allTrks.size()) {
        org.deluge.model.TrackModel tModel = allTrks.get(trackId);
        if (clipId < tModel.getClips().size()) {
          org.deluge.model.ClipModel cModel = tModel.getClips().get(clipId);
          int clipSteps = cModel.getStepCount();
          boolean isSynth = tModel instanceof org.deluge.model.SynthTrackModel;
          for (int r = 0; r < cModel.getRowCount(); r++) {
            for (int s = 0; s < clipSteps; s++) {
              org.deluge.model.StepData sd = cModel.getStep(r, s);
              if (sd != null && sd.active()) {
                int targetRow = isSynth ? (127 - sd.pitch()) : r;
                if (targetRow >= 0 && targetRow < voiceCount) {
                  bridge.setStep(engineBase + targetRow, s, true);
                }
              }
            }
          }
        }
      }

      clipPanel.refresh();
    }

    activeViewMode = "CLIP";
    cardLayout.show(centerCardPanel, "CLIP");
    if (topBar != null) topBar.selectViewModeButton("CLIP");
  }

  /** Handles top-bar view-mode and add-track actions. */
  private class AppTopBarListener implements SwingTopBarPanel.TopBarListener {
    @Override
    public void onLiveRecordToggle(JButton btn) {
      SwingGridPanel.isLiveRecordModeActive = !SwingGridPanel.isLiveRecordModeActive;
      if (SwingGridPanel.isLiveRecordModeActive) {
        btn.setBackground(new Color(0xd3, 0x2f, 0x2f));
        btn.setForeground(Color.WHITE);
        btn.setText("\u25CF RECORDING");
        if (topBar != null && topBar.getParamReadout() != null) {
          topBar.getParamReadout().printTransient("REC", "ON");
        }
      } else {
        btn.setBackground(new Color(0x3a, 0x0c, 0x0c));
        btn.setForeground(new Color(0xff, 0x33, 0x33));
        btn.setText("\u25CF REC");
        if (topBar != null && topBar.getParamReadout() != null) {
          topBar.getParamReadout().printTransient("REC", "OFF");
        }
      }
    }

    @Override
    public void onResampleToggle(JButton btn) {
      if (!org.deluge.engine.JavaAudioDriver.isResamplingActive) {
        // Start resample mode: panic old voices, start capture, THEN start play.
        // Order matters: capture must be active before the first note renders.
        if (pureEngine != null && pureEngine.getAudioEngine() != null) {
          pureEngine.getAudioEngine().panic();
        }
        org.deluge.engine.JavaAudioDriver.startResampling();
        // Auto-start play if not already playing
        if (bridge.getGlobalInt(BridgeContract.G_PLAY) == 0L) {
          onPlayToggle();
        }
        btn.setBackground(new Color(0xff, 0xaa, 0x00));
        btn.setForeground(Color.WHITE);
        btn.setText("\u25CF SAMPLING");
        if (topBar != null && topBar.getParamReadout() != null) {
          topBar.getParamReadout().printTransient("LOOP", "REC");
        }
      } else {
        // Stop resample: build looper KitTrack dynamically with a 4-on-the-floor trigger step
        // pattern
        byte[] pcmData = org.deluge.engine.JavaAudioDriver.stopResampling();
        btn.setBackground(new Color(0x3e, 0x27, 0x0c));
        btn.setForeground(new Color(0xff, 0xb3, 0x00));
        btn.setText("\u25CF RESAMPLE");
        if (topBar != null && topBar.getParamReadout() != null) {
          topBar.getParamReadout().printTransient("LOOP", "DONE");
        }

        if (pcmData == null || pcmData.length < 100) return;

        try {
          java.io.File resampleDir =
              new java.io.File(
                  org.deluge.project.PreferencesManager.getLibraryDir(), "SAMPLES/RESAMPLE");
          if (!resampleDir.exists()) resampleDir.mkdirs();
          String sampleName = "Resample_" + System.currentTimeMillis() + ".wav";
          java.io.File targetFile = new java.io.File(resampleDir, sampleName);

          org.deluge.engine.JavaAudioDriver.saveWavFile(pcmData, targetFile);

          // Instantiate a new Kit track with our recorded loop sample loaded
          org.deluge.model.KitTrackModel kitTrack =
              new org.deluge.model.KitTrackModel(
                  "Resample " + (currentProject.getTracks().size() + 1));
          org.deluge.model.Drum drum =
              new org.deluge.model.SoundDrum(sampleName, "SAMPLES/RESAMPLE/" + sampleName);
          kitTrack.addDrum(drum);

          // Program 4-on-the-floor loop triggers (Col 0, 4, 8, 12)
          org.deluge.model.ClipModel clip = new org.deluge.model.ClipModel("CLIP 1", 1, 16);
          clip.setStep(0, 0, org.deluge.model.StepData.of(true, 1.0f, 1.0f, 1.0f, 0));
          clip.setStep(0, 4, org.deluge.model.StepData.of(true, 1.0f, 1.0f, 1.0f, 0));
          clip.setStep(0, 8, org.deluge.model.StepData.of(true, 1.0f, 1.0f, 1.0f, 0));
          clip.setStep(0, 12, org.deluge.model.StepData.of(true, 1.0f, 1.0f, 1.0f, 0));
          kitTrack.addClip(clip);

          currentProject.addTrack(kitTrack);

          // Synchronize model changes to both engines
          propagateCurrentModel();
          pushModelToBridge();
          syncHighFidelityEngine(currentProject);

          if (clipPanel != null) {
            clipPanel.refresh();
          }
        } catch (Exception ex) {
          System.err.println("Failed to save and load master resample: " + ex.getMessage());
        }
      }
    }

    @Override
    public void onViewModeChanged(String viewMode) {
      activeViewMode = viewMode;
      if ("KEYPLAY".equals(viewMode)) {
        if (clipPanel != null) {
          clipPanel.setViewMode(org.deluge.ui.SwingGridPanel.GridViewMode.KEYPLAY);
          clipPanel.refresh();
        }
        cardLayout.show(centerCardPanel, "CLIP");
      } else if ("CLIP".equals(viewMode)) {
        if (clipPanel != null) {
          clipPanel.setViewMode(org.deluge.ui.SwingGridPanel.GridViewMode.CLIP);
          clipPanel.refresh();
        }
        cardLayout.show(centerCardPanel, "CLIP");
      } else {
        cardLayout.show(centerCardPanel, viewMode);
      }

      // Update High-Fidelity UI Stack
      if ("CLIP".equals(viewMode) || "SONG".equals(viewMode) || "KEYPLAY".equals(viewMode)) {
        syncHighFidelityEngine(currentProject);
      }

      // Dynamic real-time Arranger Mode activation
      if (arrangerScheduler != null) {
        arrangerScheduler.setArrangerModeActive("ARRANGER".equals(viewMode));
      }
    }

    @Override
    public void onArrangerCaptureToggle(boolean active) {
      if (arrangerScheduler != null) {
        arrangerScheduler.setCaptureActive(active);
      }
    }

    @Override
    public void onAddTrack(String type, boolean isShift) {
      String name;
      if (isShift) {
        name = type + " " + (currentProject.getTracks().size() + 1);
      } else {
        name =
            JOptionPane.showInputDialog(
                SwingDelugeApp.this,
                type + " track name:",
                type + " " + (currentProject.getTracks().size() + 1));
        if (name == null || name.isBlank()) return;
      }
      var stack = currentProject.getUndoRedoStack();
      int idx;
      switch (type) {
        case "KIT" -> {
          KitTrackModel kit = new KitTrackModel(name);
          kit.addDrum(new SoundDrum("Kick", ""));
          kit.addDrum(new SoundDrum("Snare", ""));
          kit.addDrum(new SoundDrum("Closed Hat", ""));
          kit.addDrum(new SoundDrum("Open Hat", ""));
          kit.addDrum(new SoundDrum("Clap", ""));
          kit.addDrum(new SoundDrum("Tom 1", ""));
          kit.addDrum(new SoundDrum("Tom 2", ""));
          kit.addDrum(new SoundDrum("Percussion", ""));
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
      long nextPlay = bridge.getGlobalInt(BridgeContract.G_PLAY) == 1L ? 0L : 1L;
      if (nextPlay == 1L) {
        syncHighFidelityEngine(currentProject);
        if (clipPanel != null) {
          clipPanel.setPlayheadFollowMode(true);
        }
      }
      bridge.setGlobalInt(BridgeContract.G_PLAY, nextPlay);
      if (bridge != null) bridge.setPlayState((int) nextPlay);
    }

    @Override
    public void onStop() {
      if (org.deluge.engine.JavaAudioDriver.isResamplingActive) {
        if (topBar != null) {
          topBar.stopRecordingIfActive();
        }
      }
      bridge.setGlobalInt(BridgeContract.G_PLAY, 0L);
      if (bridge != null) bridge.setPlayState(0);

      try {
        Object fwEngineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
        if (fwEngineObj instanceof org.deluge.engine.FirmwareAudioEngine fwEngine) {
          fwEngine.panic();
        }
      } catch (Exception ex) {
        // ignore
      }
    }

    @Override
    public void onMasterVolumeChanged(float vol) {
      bridge.setGlobalFloat(BridgeContract.G_MASTER_VOL, vol);
    }
  }

  /** Propagates ProjectModel changes to bridge globals via MVC listener pattern. */
  private class BridgeProjectListener implements org.deluge.model.ProjectModel.ProjectListener {
    private final org.deluge.model.ProjectModel model;

    BridgeProjectListener(org.deluge.model.ProjectModel model) {
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
      bridge.setGlobalFloat(BridgeContract.G_BPM, bpm);
    }

    @Override
    public void onSwingChanged(float swing) {
      bridge.setGlobalFloat(BridgeContract.G_SWING, swing);
    }

    @Override
    public void onMasterVolumeChanged(float vol) {
      bridge.setGlobalFloat(BridgeContract.G_MASTER_VOL, vol);
    }

    @Override
    public void onMasterPanChanged(float pan) {
      bridge.setGlobalFloat(BridgeContract.G_MASTER_PAN, pan);
    }

    @Override
    public void onKeyChanged(String key) {
      bridge.setGlobalInt(BridgeContract.G_ROOT_KEY, parseRootKey(key));
    }

    @Override
    public void onScaleChanged(String scale) {
      bridge.setGlobalInt(BridgeContract.G_SCALE, parseScaleIndex(scale));
    }

    @Override
    public void onTransposeChanged(int transpose) {
      bridge.setGlobalInt(BridgeContract.G_TRANSPOSE, transpose);
    }

    @Override
    public void onHumanizeChanged(float humanize) {
      bridge.setGlobalFloat(BridgeContract.G_HUMANIZE, humanize);
    }

    @Override
    public void onReverbChanged() {
      bridge.setGlobalFloat(BridgeContract.G_REVERB_ROOM, model.getReverbRoomSize());
      bridge.setGlobalFloat(BridgeContract.G_REVERB_DAMP, model.getReverbDampening());
      bridge.setGlobalFloat(BridgeContract.G_REVERB_WIDTH, model.getReverbWidth());
      bridge.setGlobalFloat(BridgeContract.G_REVERB_HPF, model.getReverbHpf());
      bridge.setGlobalFloat(BridgeContract.G_REVERB_PAN, model.getReverbPan());
      bridge.setGlobalInt(BridgeContract.G_REVERB_MODEL, model.getReverbModel());
      bridge.setGlobalFloat(BridgeContract.G_REVERB_COMP_ATTACK, model.getReverbCompressorAttack());
      bridge.setGlobalFloat(
          BridgeContract.G_REVERB_COMP_RELEASE, model.getReverbCompressorRelease());
      bridge.setGlobalInt(
          BridgeContract.G_REVERB_COMP_SYNC_LEVEL, model.getReverbCompressorSyncLevel());
      bridge.setGlobalFloat(BridgeContract.G_REVERB_COMP_HPF, model.getReverbCompHpf());
      bridge.setGlobalFloat(BridgeContract.G_REVERB_COMP_BLEND, model.getReverbCompBlend());
    }

    @Override
    public void onDelayChanged() {
      bridge.setGlobalFloat(BridgeContract.G_DELAY_TIME, model.getMasterDelay());
      bridge.setGlobalFloat(BridgeContract.G_DELAY_FB, model.getSongParamDelayFeedback());
      bridge.setGlobalInt(BridgeContract.G_DELAY_PINGPONG, model.getDelayPingPong());
      bridge.setGlobalInt(BridgeContract.G_DELAY_ANALOG, model.getDelayAnalog());
      bridge.setGlobalInt(BridgeContract.G_DELAY_SYNC_LEVEL, model.getDelaySyncLevel());
      bridge.setGlobalInt(BridgeContract.G_DELAY_SYNC_TYPE, model.getDelaySyncType());
    }

    @Override
    public void onSidechainChanged() {
      bridge.setGlobalFloat(BridgeContract.G_SIDECHAIN_ATTACK, model.getSidechainAttack());
      bridge.setGlobalFloat(BridgeContract.G_SIDECHAIN_RELEASE, model.getSidechainRelease());
      bridge.setGlobalInt(BridgeContract.G_SIDECHAIN_SYNC_LEVEL, model.getSidechainSyncLevel());
      bridge.setGlobalInt(BridgeContract.G_SIDECHAIN_SYNC_TYPE, model.getSidechainSyncType());
    }

    @Override
    public void onCompressorChanged() {
      bridge.setGlobalFloat(BridgeContract.G_MASTER_COMP, model.getCompressorThreshold());
      bridge.setGlobalFloat(BridgeContract.G_MASTER_COMP_ATTACK, model.getCompressorAttack());
      bridge.setGlobalFloat(BridgeContract.G_MASTER_COMP_RELEASE, model.getCompressorRelease());
      bridge.setGlobalFloat(BridgeContract.G_MASTER_COMP_RATIO, model.getCompressorRatio());
      bridge.setGlobalFloat(BridgeContract.G_MASTER_COMP_BLEND, model.getCompressorBlend());

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
      bridge.setGlobalFloat(BridgeContract.G_SP_VOLUME, model.getSongParamVolume());
      bridge.setGlobalFloat(BridgeContract.G_SP_PAN, model.getSongParamPan());
      bridge.setGlobalFloat(BridgeContract.G_SP_REVERB_AMOUNT, model.getSongParamReverbAmount());
      bridge.setGlobalFloat(BridgeContract.G_SP_DELAY_RATE, model.getSongParamDelayRate());
      bridge.setGlobalFloat(BridgeContract.G_SP_DELAY_FEEDBACK, model.getSongParamDelayFeedback());
      bridge.setGlobalFloat(
          BridgeContract.G_SP_SIDECHAIN_SHAPE, model.getSongParamSidechainShape());
      bridge.setGlobalFloat(BridgeContract.G_SP_STUTTER_RATE, model.getSongParamStutterRate());
      bridge.setGlobalFloat(
          BridgeContract.G_SP_SAMPLE_RATE_REDUCTION, model.getSongParamSampleRateReduction());
      bridge.setGlobalFloat(BridgeContract.G_SP_BITCRUSH, model.getSongParamBitCrush());
      bridge.setGlobalFloat(BridgeContract.G_SP_MOD_FX_RATE, model.getSongParamModFXRate());
      bridge.setGlobalFloat(BridgeContract.G_SP_MOD_FX_DEPTH, model.getSongParamModFXDepth());
      bridge.setGlobalFloat(BridgeContract.G_SP_MOD_FX_OFFSET, model.getSongParamModFXOffset());
      bridge.setGlobalFloat(BridgeContract.G_SP_MOD_FX_FEEDBACK, model.getSongParamModFXFeedback());
      bridge.setGlobalFloat(
          BridgeContract.G_SP_COMPRESSOR_THRESHOLD, model.getSongParamCompressorThreshold());
      bridge.setGlobalFloat(BridgeContract.G_SP_LPF_MORPH, model.getSongParamLpfMorph());
      bridge.setGlobalFloat(BridgeContract.G_SP_HPF_MORPH, model.getSongParamHpfMorph());
      bridge.setGlobalFloat(BridgeContract.G_SP_LPF_FREQ, model.getSongParamLpfFrequency());
      bridge.setGlobalFloat(BridgeContract.G_SP_LPF_RES, model.getSongParamLpfResonance());
      bridge.setGlobalFloat(BridgeContract.G_SP_HPF_FREQ, model.getSongParamHpfFrequency());
      bridge.setGlobalFloat(BridgeContract.G_SP_HPF_RES, model.getSongParamHpfResonance());
      bridge.setGlobalFloat(BridgeContract.G_SP_EQ_BASS, model.getSongParamEqBass());
      bridge.setGlobalFloat(BridgeContract.G_SP_EQ_TREBLE, model.getSongParamEqTreble());
      bridge.setGlobalFloat(BridgeContract.G_SP_EQ_BASS_FREQ, model.getSongParamEqBassFrequency());
      bridge.setGlobalFloat(
          BridgeContract.G_SP_EQ_TREBLE_FREQ, model.getSongParamEqTrebleFrequency());
    }

    @Override
    public void onScalesChanged() {
      bridge.setGlobalInt(BridgeContract.G_USER_SCALE, model.getUserScale());
      bridge.setGlobalInt(BridgeContract.G_DISABLED_PRESET_SCALES, model.getDisabledPresetScales());
      boolean[] modeNotes = model.getModeNotes();
      if (modeNotes != null) {
        for (int i = 0; i < 12 && i < modeNotes.length; i++) {
          bridge.setGlobalInt(BridgeContract.G_MODE_NOTES + "_" + i, modeNotes[i] ? 1L : 0L);
        }
      }
    }
  }

  // ── Helper methods ──

  private void pushKitEnv(int engineRow, int envIndex, EnvelopeModel env) {
    if (env == null) return;
  }

  private static int lpfModeOrdinal(org.deluge.model.FilterMode mode) {
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

  /**
   * Canonical scale cycle order — names understood by both parseScaleIndex and the grid colouring.
   */
  private static final String[] SCALE_CYCLE = {
    "Major",
    "Minor",
    "Harmonic Minor",
    "Melodic Minor",
    "Dorian",
    "Phrygian",
    "Lydian",
    "Mixolydian",
    "Locrian",
    "Whole Tone",
    "Whole Half Dim",
    "Half Whole Dim",
    "Pentatonic Major",
    "Pentatonic Minor",
    "Chromatic"
  };

  /** Advance to the next scale (Deluge SCALE / SHIFT+SCALE): updates model, engine, and grid. */
  public void cycleScale() {
    if (currentProject == null) {
      return;
    }
    String cur = currentProject.getScale();
    int idx = 0;
    for (int i = 0; i < SCALE_CYCLE.length; i++) {
      if (SCALE_CYCLE[i].equalsIgnoreCase(cur)) {
        idx = i;
        break;
      }
    }
    String next = SCALE_CYCLE[(idx + 1) % SCALE_CYCLE.length];
    currentProject.setScale(next);
    bridge.setGlobalInt(BridgeContract.G_SCALE, parseScaleIndex(next));
    if (topBar != null && topBar.getParamReadout() != null) {
      topBar.getParamReadout().printTransient("SCALE", next);
    }
    fireProjectChanged();
  }

  // Smallest window we still lay out correctly — fits a 1366x768 laptop after screen margins.
  private static final int MIN_WINDOW_W = 1180;
  private static final int MIN_WINDOW_H = 680;

  /** Target window size for a Screen Resolution profile (before screen clamping). */
  private static java.awt.Dimension resolutionTarget(String res) {
    return switch (res == null ? "QHD" : res) {
      case "FHD" -> new java.awt.Dimension(1760, 980); // fits a 1920x1080 panel
      case "Retina" -> new java.awt.Dimension(2560, 1500);
      case "Default" -> null; // fit the current screen
      default -> new java.awt.Dimension(2360, 1340); // "QHD" — fits a 2560x1440 panel
    };
  }

  /**
   * Window size from the Screen Resolution preference, clamped to the physical screen (minus
   * margins for the title bar / taskbar) and floored to {@link #MIN_WINDOW_W}x{@link
   * #MIN_WINDOW_H}. This is what makes us actually comply with the preference while still fitting
   * low-res laptops.
   */
  /**
   * Pure window-size policy: a resolution profile clamped to the given screen (minus title/taskbar
   * margins) and floored to the minimum. Package-private + screen-size-injected so it is testable
   * headlessly.
   */
  static Dimension windowSizeFor(String res, int screenW, int screenH) {
    Dimension target = resolutionTarget(res);
    int maxW = Math.max(MIN_WINDOW_W, screenW - 16);
    int maxH = Math.max(MIN_WINDOW_H, screenH - 48);
    int w = (target == null) ? maxW : Math.min(target.width, maxW);
    int h = (target == null) ? maxH : Math.min(target.height, maxH);
    return new Dimension(Math.max(w, MIN_WINDOW_W), Math.max(h, MIN_WINDOW_H));
  }

  private static Dimension computeWindowSize() {
    Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
    return windowSizeFor(
        org.deluge.project.PreferencesManager.get("screen.resolution", "QHD"),
        screen.width,
        screen.height);
  }

  /**
   * Re-apply the Screen Resolution preference to the live window (called when the pref changes).
   */
  public void applyWindowResolution() {
    Dimension win = computeWindowSize();
    setSize(win.width, win.height);
    setLocationRelativeTo(null);
    revalidate();
    repaint();
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

  public void reloadSidebarLibraries() {
    if (sidebarPanel != null) sidebarPanel.reloadLibrary();
    if (floatingSidebar != null) floatingSidebar.reloadLibrary();
  }

  @Override
  public void dispose() {
    if (visualizerRepaintTimer != null) {
      visualizerRepaintTimer.stop();
    }
    if (picTransportFlushTimer != null) {
      picTransportFlushTimer.stop();
    }
    if (statusTextPlaybackTimer != null) {
      statusTextPlaybackTimer.stop();
    }
    if (pureEngine != null) {
      pureEngine.stop();
    }
    super.dispose();
  }

  public void updateGlobalGridMode(org.deluge.project.PreferencesManager.GridMode nextMode) {
    org.deluge.project.PreferencesManager.setGridMode(nextMode);
    if (clipPanel != null) {
      clipPanel.setGridMode(nextMode);
      clipPanel.refresh();
    }
    if (songPanel != null) {
      songPanel.setGridMode(nextMode);
      songPanel.refresh();
    }
    if (arrGridPanel != null) {
      arrGridPanel.setGridMode(nextMode);
      arrGridPanel.refresh();
    }
    recalcWrapperSize();
    updateViewMenuChecks();
    captureAutoScreenshot("zoom_" + nextMode.name());
  }

  private void updateViewMenuChecks() {
    org.deluge.project.PreferencesManager.GridMode currentMode =
        org.deluge.project.PreferencesManager.getGridMode();
    if (grid8x16Item != null) {
      grid8x16Item.setSelected(
          currentMode == org.deluge.project.PreferencesManager.GridMode.GRID_8x16);
    }
    if (grid16x16Item != null) {
      grid16x16Item.setSelected(
          currentMode == org.deluge.project.PreferencesManager.GridMode.GRID_16x16);
    }
    if (grid24x16Item != null) {
      grid24x16Item.setSelected(
          currentMode == org.deluge.project.PreferencesManager.GridMode.GRID_24x16);
    }
    if (grid16x24Item != null) {
      grid16x24Item.setSelected(
          currentMode == org.deluge.project.PreferencesManager.GridMode.GRID_16x24);
    }
  }

  private void zoomGrid(boolean zoomIn) {
    org.deluge.project.PreferencesManager.GridMode[] modes = {
      org.deluge.project.PreferencesManager.GridMode.GRID_8x16,
      org.deluge.project.PreferencesManager.GridMode.GRID_16x16,
      org.deluge.project.PreferencesManager.GridMode.GRID_24x16
    };
    org.deluge.project.PreferencesManager.GridMode currentMode =
        org.deluge.project.PreferencesManager.getGridMode();
    int currentIdx = -1;
    for (int i = 0; i < modes.length; i++) {
      if (modes[i] == currentMode) {
        currentIdx = i;
        break;
      }
    }
    if (currentIdx == -1) {
      currentIdx = 1;
    }
    int nextIdx = currentIdx + (zoomIn ? -1 : 1);
    if (nextIdx >= 0 && nextIdx < modes.length) {
      updateGlobalGridMode(modes[nextIdx]);
    }
  }
}
