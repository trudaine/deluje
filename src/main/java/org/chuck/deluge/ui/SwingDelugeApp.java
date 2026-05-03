package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.AudioTrackModel;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.SynthTrackModel;

/** Alternative lightweight UI running purely on Java Swing (no native libs). */
public class SwingDelugeApp extends JFrame {
  private final ChuckVM vm;
  private final BridgeContract bridge;

  private SwingGridPanel clipPanel;
  private SwingVisualizerPanel visualizerPanel;
  private SwingGridPanel songPanel;
  private SwingGridPanel arrGridPanel;
  private SwingGridPanel autoPanel;

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
  // Kit tracks occupy getSounds().size() rows; Synth tracks occupy 1 row.
  private int[] trackEngineStart;
  private int[] trackVoiceCount;

  private final org.chuck.deluge.midi.MidiService midiService;
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
      int voices = isKit
        ? ((org.chuck.deluge.model.KitTrackModel) tracks.get(t)).getSounds().size()
        : 8;
      int capped = Math.min(voices, BridgeContract.TRACKS - nextRow);
      trackVoiceCount[t] = capped;
      nextRow += capped;
    }
  }

  /** Push the current project model into engine globals (G_TRACK_TYPE, samples, kit params, patterns). */
  private void pushModelToBridge() {
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

        // Push each kit sound: sample path, pitch, mute group, reverse, ADSR
        java.util.List<org.chuck.deluge.model.KitTrackModel.KitSound> sounds = kit.getSounds();
        for (int v = 0; v < voiceCount; v++) {
          int engineRow = startRow + v;
          String path = v < sounds.size() ? sounds.get(v).getSamplePath() : "";
          vm.setGlobalString("g_sample_" + engineRow, path);
          bridge.setSamplePath(engineRow, path);

          if (v < sounds.size()) {
            org.chuck.deluge.model.KitTrackModel.KitSound snd = sounds.get(v);
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
            } catch (Exception ex) {
              System.err.println("[pushModel] kit param error at row " + engineRow + ": " + ex.getMessage());
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
              }
            }
          }
        }
      } else if (track instanceof org.chuck.deluge.model.SynthTrackModel synth) {
        // Mark all voice rows as type-1 (synth) — includes extended clip rows
        int activeClipIdx = synth.getActiveClipIndex();
        int totalSynthRows = voiceCount;
        if (activeClipIdx >= 0 && activeClipIdx < synth.getClips().size()) {
          totalSynthRows = Math.max(totalSynthRows, synth.getClips().get(activeClipIdx).getRowCount());
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
        }

        // Push ADSR envelopes (4 envs) to ALL rows of this track
        for (int e = 0; e < 4; e++) {
          org.chuck.deluge.model.EnvelopeModel adsr = synth.getEnv(e);
          if (adsr != null) {
            for (int v = 0; v < totalSynthRows; v++) {
              bridge.setEnv(startRow + v, e, adsr.attack(), adsr.decay(), adsr.sustain(), adsr.release());
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
        for (int l = 0; l < 4; l++) {
          org.chuck.deluge.model.LfoModel lfo = synth.getLfo(l);
          if (lfo != null) {
            if (lfoRateArr != null) lfoRateArr.setFloat(l, lfo.rateHz());
            if (lfoTypeArr != null) lfoTypeArr.setInt(l, (long) lfo.waveform().ordinal());
            if (lfoDepthArr != null) lfoDepthArr.setFloat(l, lfo.depth());
          }
        }

        // Push arp params to ALL rows
        org.chuck.deluge.model.ArpModel arp = synth.getArp();
        if (arp != null) {
          int arpMode = switch (arp.mode()) {
            case "DOWN" -> 1;
            case "UP_DOWN" -> 2;
            case "RANDOM" -> 3;
            default -> 0; // UP
          };
          for (int v = 0; v < totalSynthRows; v++) {
            bridge.setArpOn(startRow + v, arp.active());
            bridge.setArpRate(startRow + v, arp.rate());
            bridge.setArpOctave(startRow + v, arp.octaves());
            bridge.setArpMode(startRow + v, arpMode);
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
          bridge.setPolyphony(startRow + v, synth.getPolyphony().ordinal());
        }

        // ── DX7 patch (per-row string global, read by engine) ──
        String dx7patch = synth.getDx7Patch();
        if (dx7patch != null && !dx7patch.isEmpty()) {
          for (int v = 0; v < totalSynthRows; v++) {
            vm.setGlobalString("g_dx7_patch_" + (startRow + v), dx7patch);
          }
        }
      } else if (track instanceof org.chuck.deluge.model.AudioTrackModel audio) {
        // Mark engine row as type-2 (audio)
        bridge.setTrackType(startRow, 2);
        if (trackTypeArr != null) trackTypeArr.setInt(startRow, 2L);
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
      }

      // ── Per-step automation merge ──
      {
        int acIdx = track.getActiveClipIndex();
        java.util.List<org.chuck.deluge.model.ClipModel> clips = track.getClips();
        if (acIdx >= 0 && acIdx < clips.size()) {
          org.chuck.deluge.model.ClipModel clip = clips.get(acIdx);
          int stepCount = clip.getStepCount();
          int totalEngineRows = voiceCount;
          if (track instanceof org.chuck.deluge.model.SynthTrackModel) {
            totalEngineRows = Math.max(voiceCount, clip.getRowCount());
          }
          // Write automation to the first row of the track (engine shares per-track modulation)
          int engRow = startRow;
          for (int s = 0; s < stepCount; s++) {
            if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_LPF_FREQ, s))
              bridge.setStepFilter(engRow, s, clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_LPF_FREQ, s));
            if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_LPF_RES, s))
              bridge.setStepRes(engRow, s, clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_LPF_RES, s));
            if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_PAN, s))
              bridge.setStepPan(engRow, s, clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_PAN, s));
            if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_DELAY, s))
              bridge.setStepDelay(engRow, s, clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_DELAY, s));
            if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_REVERB, s))
              bridge.setStepReverb(engRow, s, clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_REVERB, s));
            if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_HPF_FREQ, s))
              bridge.setStepHpfFreq(engRow, s, clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_HPF_FREQ, s));
            if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_HPF_RES, s))
              bridge.setStepHpfRes(engRow, s, clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_HPF_RES, s));
            if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_MOD_FX_RATE, s))
              bridge.setStepModRate(engRow, s, clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_MOD_FX_RATE, s));
            if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_MOD_FX_DEPTH, s))
              bridge.setStepModDepth(engRow, s, clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_MOD_FX_DEPTH, s));
            if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_OSC_A_VOL, s))
              bridge.setStepOscAVol(engRow, s, clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_OSC_A_VOL, s));
            if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_OSC_B_VOL, s))
              bridge.setStepOscBVol(engRow, s, clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_OSC_B_VOL, s));
            if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_NOISE_VOL, s))
              bridge.setStepNoiseVol(engRow, s, clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_NOISE_VOL, s));
            if (clip.hasAutomation(org.chuck.deluge.model.AutomationParam.A_PITCH, s))
              bridge.setStepPitch(engRow, s, clip.getAutomation(org.chuck.deluge.model.AutomationParam.A_PITCH, s));
          }
        }
      }

      // Track length and stepCount for all rows of this track
      int rowLen = track.getClips().isEmpty() ? 16 : track.getClips().get(0).getStepCount();
      int totalRows = voiceCount;
      if (track instanceof org.chuck.deluge.model.SynthTrackModel) {
        int acIdx = ((org.chuck.deluge.model.SynthTrackModel) track).getActiveClipIndex();
        var clips = ((org.chuck.deluge.model.SynthTrackModel) track).getClips();
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
            }
          }
        }
      }
    }

    // Only unblock the engine when there are actual tracks to process.
    // An empty-project broadcast leaves all track types = -1, causing kit_shred to
    // compute voiceCount = 1 and build undersized arrays that can't be resized later.
    if (!tracks.isEmpty()) {
      vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    }
  }

  public SwingDelugeApp(
      ChuckVM vm, BridgeContract bridge, org.chuck.deluge.midi.MidiService midiService) {
    this.vm = vm;
    this.bridge = bridge;
    this.midiService = midiService;

    // Inflate Font Sizes globally (2x bigger)
    java.util.Enumeration<Object> keys = UIManager.getDefaults().keys();
    while (keys.hasMoreElements()) {
      Object key = keys.nextElement();
      Object value = UIManager.get(key);
      if (value instanceof javax.swing.plaf.FontUIResource) {
        javax.swing.plaf.FontUIResource orig = (javax.swing.plaf.FontUIResource) value;
        Font font = new Font(orig.getFontName(), orig.getStyle(), 20); // Increased size
        UIManager.put(key, new javax.swing.plaf.FontUIResource(font));
      }
    }

    setTitle("DELUGE WORKSTATION [SWING EDITION]");
    setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

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
                              } catch (Exception ex) {
                              }
                            })
                        .start();
                  }
                }
              }
            }
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
                double avgGap = totalGap / (double)(arr.length - 1);
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

  private void loadProject(org.chuck.deluge.model.ProjectModel model) {
    currentProject = model;
    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);

    // Register listener so structural changes auto-sync
    model.addProjectListener(new org.chuck.deluge.model.ProjectModel.ProjectListener() {
      @Override public void onTrackListChanged() {
        pushModelToBridge();
        propagateCurrentModel();
        refreshGrids();
      }
      @Override public void onBpmChanged(float bpm) {
        vm.setGlobalFloat(BridgeContract.G_BPM, bpm);
      }
    });

    pushModelToBridge();
    propagateCurrentModel();

    if (clipPanel != null) clipPanel.setProjectModel(model);
    if (songPanel != null) songPanel.setProjectModel(model);
    if (arrGridPanel != null) arrGridPanel.setProjectModel(model);

    setTitle(
        "DELUGE WORKSTATION — "
            + (currentProjectFile != null ? currentProjectFile.getName() : "Untitled"));
  }

  private void refreshGrids() {
    if (clipPanel != null) clipPanel.refresh();
    if (songPanel != null) songPanel.refresh();
    if (arrGridPanel != null) arrGridPanel.refresh();
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
      if (comp.isVisible() && comp instanceof JScrollPane sp
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
          this, "Script loaded successfully:\n" + file.getName(),
          "Script Loaded", JOptionPane.INFORMATION_MESSAGE);
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(
          this, "Failed to load script:\n" + ex.getMessage(),
          "Error", JOptionPane.ERROR_MESSAGE);
    }
  }

  private void assembleKitFromSynths() {
    JFileChooser chooser = new JFileChooser(
        org.chuck.deluge.project.PreferencesManager.getSongsDir());
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
      configPanel.add(new JLabel((i + 1) + ":"), c); c.gridx = 1;
      configPanel.add(nameFld, c); c.gridx = 2;
      configPanel.add(new JLabel("MG:"), c); c.gridx = 3;
      configPanel.add(muteSpinner, c); c.gridx = 4;
      configPanel.add(new JLabel("Pitch:"), c); c.gridx = 5;
      configPanel.add(pitchSpinner, c); c.gridx = 0;

      nameFields.add(nameFld);
      muteFields.add(muteSpinner);
      pitchFields.add(pitchSpinner);
    }

    int result = JOptionPane.showConfirmDialog(
        this, configPanel, "Configure Kit Lanes",
        JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
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
              kitName,
              java.util.Arrays.asList(selected),
              muteGroups,
              pitchOffsets);

      JFileChooser saveChooser = new JFileChooser(
          org.chuck.deluge.project.PreferencesManager.getSongsDir());
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
          this, "Kit saved to:\n" + saveFile.getAbsolutePath(),
          "Kit Assembly Complete", JOptionPane.INFORMATION_MESSAGE);
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(
          this, "Failed to assemble kit:\n" + ex.getMessage(),
          "Error", JOptionPane.ERROR_MESSAGE);
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
    JMenuItem sampleItem = new JMenuItem("Set Samples Directory...");
    sampleItem.addActionListener(
        e -> {
          JFileChooser chooser = new JFileChooser();
          chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
          if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            org.chuck.deluge.project.PreferencesManager.setSamplesDir(
                chooser.getSelectedFile().getAbsolutePath());
          }
        });
    JMenuItem prefItem = new JMenuItem("Preferences...");
    prefItem.addActionListener(
        e -> {
          PreferencesDialog dialog = new PreferencesDialog(
              SwingDelugeApp.this, () -> {
                org.chuck.deluge.project.PreferencesManager.GridMode mode =
                    org.chuck.deluge.project.PreferencesManager.getGridMode();
                if (clipPanel != null) clipPanel.setGridMode(mode);
                if (songPanel != null) songPanel.setGridMode(mode);
                if (arrGridPanel != null) arrGridPanel.setGridMode(mode);
                recalcWrapperSize();
              });
          if (midiService != null) dialog.setMappings(midiService.getMappings());
          dialog.setVisible(true);
        });

    settingsMenu.add(prefItem);

    menuBar.add(fileMenu);
    menuBar.add(settingsMenu);
    setJMenuBar(menuBar);

    final JDialog leftFloat = new JDialog(this, "SD Explorer", false);
    leftFloat.setSize(300, 700);
    leftFloat.setLocation(50, 150);

    final JDialog rightFloat = new JDialog(this, "Acoustics Monitor", false);
    rightFloat.setSize(280, 700);
    rightFloat.setLocation(1600, 150);

    // 1. Top Area (Buttons, Modes, Transport, Sliders)

    cardLayout = new CardLayout();
    centerCardPanel = new JPanel(cardLayout);

    clipPanel = new SwingGridPanel(vm, bridge);
    clipPanel.setViewMode(SwingGridPanel.GridViewMode.CLIP);
    clipPanel.setProjectModel(currentProject);
    clipPanel.setOnProjectChanged(this::propagateCurrentModel);
    centerCardPanel.add(wrapGridPanel(clipPanel), "CLIP");

    songPanel = new SwingGridPanel(vm, bridge);
    songPanel.setViewMode(SwingGridPanel.GridViewMode.SONG);
    songPanel.setProjectModel(currentProject);
    songPanel.setOnProjectChanged(this::propagateCurrentModel);
    centerCardPanel.add(wrapGridPanel(songPanel), "SONG");

    arrGridPanel = new SwingGridPanel(vm, bridge);
    arrGridPanel.setViewMode(SwingGridPanel.GridViewMode.ARRANGEMENT);
    arrGridPanel.setProjectModel(currentProject);
    arrGridPanel.setOnProjectChanged(this::propagateCurrentModel);
    centerCardPanel.add(wrapGridPanel(arrGridPanel), "ARR");

    autoPanel = new SwingGridPanel(vm, bridge);
    autoPanel.setViewMode(SwingGridPanel.GridViewMode.AUTOMATION);
    autoPanel.setProjectModel(currentProject);
    autoPanel.setOnProjectChanged(this::propagateCurrentModel);
    centerCardPanel.add(wrapGridPanel(autoPanel), "AUTO");

    topBar =
        new SwingTopBarPanel(
            vm,
            bridge,
            currentProject,
            leftFloat,
            rightFloat,
            new SwingTopBarPanel.TopBarListener() {
              @Override
              public void onViewModeChanged(String viewMode) {
                cardLayout.show(centerCardPanel, viewMode);
              }

              @Override
              public void onAddTrack(String type) {
                String name =
                    JOptionPane.showInputDialog(
                        SwingDelugeApp.this,
                        type + " track name:",
                        type + " " + (currentProject.getTracks().size() + 1));
                if (name == null || name.isBlank()) return;
                switch (type) {
                  case "KIT":
                    KitTrackModel kit = new KitTrackModel(name);
                    kit.addClip(new ClipModel("CLIP 1", 8, 16));
                    currentProject.addTrack(kit);
                    break;
                  case "SYNTH":
                    SynthTrackModel synth = new SynthTrackModel(name);
                    synth.addClip(new ClipModel("CLIP 1", 8, 16));
                    currentProject.addTrack(synth);
                    break;
                  case "AUDIO":
                    AudioTrackModel audio = new AudioTrackModel(name);
                    audio.addClip(new ClipModel("CLIP 1", 1, 16));
                    currentProject.addTrack(audio);
                    break;
                }
                propagateCurrentModel();
              }
            });

    // DEBUG: solid background colors to visualize panel sizes
    System.out.println("DEBUG setupUI: topBar bg=" + topBar.getBackground() + " contentPane bg=" + getContentPane().getBackground());

    JPanel topBarWrapper = new JPanel();
    topBarWrapper.setLayout(new BoxLayout(topBarWrapper, BoxLayout.Y_AXIS));
    topBarWrapper.add(topBar);
    topBarWrapper.setPreferredSize(new Dimension(1, 132));
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
    SwingProjectSidebarPanel sidebarPanel = new SwingProjectSidebarPanel(vm, bridge, midiService);
    SwingProjectSidebarPanel floatingSidebar =
        new SwingProjectSidebarPanel(vm, bridge, midiService);
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

    // bottom lane purged

    // 5. Bottom Area - Rows 9 and 10 (Param Deck)
    // Obsolete bottom parameter deck removed. Integrated in 10x18 pads matrix.

    // 7. Bottom Area - Row 3 (Master FX dials bounding boxes)
    SwingMasterFxPanel masterFxPanel = new SwingMasterFxPanel(vm, topBar);
    this.masterFxPanel = masterFxPanel;
    // DEBUG: masterFxPanel.setBorder(BorderFactory.createLineBorder(Color.YELLOW, 3));

    masterFxPanel.setPreferredSize(new Dimension(1, 132));
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
    org.chuck.core.ChuckVM vm = new org.chuck.core.ChuckVM(44100, 2);
    org.chuck.deluge.BridgeContract bridge = new org.chuck.deluge.BridgeContract();
    bridge.register(vm);

    org.chuck.audio.ChuckAudio audio = new org.chuck.audio.ChuckAudio(vm, 1024, 2, 44100);
    vm.setAudio(audio);
    audio.start();

    vm.spork(new org.chuck.deluge.engine.DelugeEngineDSL());

    org.chuck.deluge.midi.MidiInputRouter router =
        new org.chuck.deluge.midi.MidiInputRouter(vm, bridge);
    org.chuck.deluge.midi.MidiService midiService =
        new org.chuck.deluge.midi.MidiService(vm, bridge, router);
    midiService.start();

    java.awt.EventQueue.invokeLater(
        () -> {
          SwingDelugeApp app = new SwingDelugeApp(vm, bridge, midiService);
          app.setVisible(true);
        });
  }
}
