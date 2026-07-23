package org.deluge.ui;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.deluge.BridgeContract;
import org.deluge.model.AudioTrackModel;
import org.deluge.model.ClipModel;
import org.deluge.model.EnvelopeModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SoundDrum;
import org.deluge.model.SynthTrackModel;
import org.deluge.model.TrackModel;
import org.deluge.shadow.core.ChuckArray;

/**
 * Coordinates synchronization between the Java ProjectModel and the ChucK DSP Engine (via
 * BridgeContract). Encapsulates track voice/row engine mapping, clip automation pushing, and
 * real-time project edit propagation.
 */
public class EngineSyncCoordinator {
  private static final Logger LOG = Logger.getLogger(EngineSyncCoordinator.class.getName());

  private final SwingDelugeApp app;
  private final BridgeContract bridge;

  /**
   * Immutable start/count pair published as a single volatile reference so concurrent readers
   * always see a consistent, fully-populated mapping.
   */
  private record TrackEngineMapping(int[] engineStart, int[] voiceCount) {}

  private volatile TrackEngineMapping mapping;

  public EngineSyncCoordinator(SwingDelugeApp app, BridgeContract bridge) {
    this.app = app;
    this.bridge = bridge;
  }

  /** Get the starting engine row index for a given track. */
  public int getTrackEngineStart(int trackId) {
    TrackEngineMapping m = mapping;
    if (m != null && trackId >= 0 && trackId < m.engineStart.length) {
      return m.engineStart[trackId];
    }
    return -1;
  }

  /** Get the engine voice/row count allocated for a given track. */
  public int getTrackVoiceCount(int trackId) {
    TrackEngineMapping m = mapping;
    if (m != null && trackId >= 0 && trackId < m.voiceCount.length) {
      return m.voiceCount[trackId];
    }
    return 0;
  }

  /** Register the project listener to propagate real-time edits to the engine. */
  public void registerProject(ProjectModel model) {
    model.addProjectListener(new BridgeProjectListener(model));
  }

  /** Recompute the track→engine-row mapping from the current project model and publish it. */
  private TrackEngineMapping computeEngineMapping(ProjectModel project) {
    List<TrackModel> tracks = project.getTracks();
    int n = tracks.size();
    int[] engineStart = new int[n];
    int[] voiceCount = new int[n];
    int nextRow = 0;
    for (int t = 0; t < n && nextRow < BridgeContract.TRACKS; t++) {
      engineStart[t] = nextRow;
      boolean isKit = tracks.get(t) instanceof KitTrackModel;
      boolean isMidi = tracks.get(t) instanceof org.deluge.model.MidiTrackModel;
      int voices = isMidi ? 0 : (isKit ? ((KitTrackModel) tracks.get(t)).getDrums().size() : 8);
      int capped = Math.min(voices, BridgeContract.TRACKS - nextRow);
      voiceCount[t] = capped;
      nextRow += capped;
    }
    TrackEngineMapping m = new TrackEngineMapping(engineStart, voiceCount);
    mapping = m;
    return m;
  }

  /**
   * Push the current project model into engine globals (G_TRACK_TYPE, samples, kit params,
   * patterns).
   */
  public void pushModelToBridge() {
    ProjectModel currentProject = app.getCurrentProject();
    if (currentProject == null) return;

    TrackEngineMapping m = computeEngineMapping(currentProject);
    List<TrackModel> tracks = currentProject.getTracks();

    // Clear all engine rows first
    bridge.clearPattern();
    for (int i = 0; i < BridgeContract.TRACKS; i++) {
      bridge.setMute(i, false);
      bridge.setTrackLength(i, 16);
    }

    // Initialize both local bridge and VM global track types to -1 (unused)
    for (int i = 0; i < BridgeContract.TRACKS; i++) bridge.setTrackType(i, -1);

    ChuckArray trackTypeArr = (ChuckArray) bridge.getGlobalObject(BridgeContract.G_TRACK_TYPE);
    if (trackTypeArr != null) {
      // Initialize all to -1 (unused)
      for (int i = 0; i < BridgeContract.TRACKS; i++) trackTypeArr.setInt(i, -1L);
    }

    for (int t = 0; t < tracks.size(); t++) {
      TrackModel track = tracks.get(t);
      int startRow = m.engineStart[t];
      int voiceCount = m.voiceCount[t];

      int rowsToSet = voiceCount;
      if (track instanceof SynthTrackModel synth) {
        int activeClipIdx = synth.getActiveClipIndex();
        if (activeClipIdx >= 0 && activeClipIdx < synth.getClips().size()) {
          rowsToSet = Math.max(rowsToSet, synth.getClips().get(activeClipIdx).getRowCount());
        }
      }
      for (int v = 0; v < rowsToSet; v++) {
        bridge.setTrackId(startRow + v, t);
      }

      if (track instanceof KitTrackModel kit) {
        // Mark engine rows as type-0 (kit)
        for (int v = 0; v < voiceCount; v++) {
          bridge.setTrackType(startRow + v, 0);
          if (trackTypeArr != null) trackTypeArr.setInt(startRow + v, 0L);
        }

        // Push each drum: sample path, pitch, mute group, reverse, ADSR
        List<org.deluge.model.Drum> sounds = kit.getDrums();
        for (int v = 0; v < voiceCount; v++) {
          int engineRow = startRow + v;
          String path =
              v < sounds.size()
                  ? ((SoundDrum) sounds.get(sounds.size() - 1 - v)).getSamplePath()
                  : "";
          bridge.setGlobalString("g_sample_" + engineRow, path);
          bridge.setSamplePath(engineRow, path);

          // ── Zone (sample truncation) ──
          if (v < sounds.size()) {
            SoundDrum snd = (SoundDrum) sounds.get(sounds.size() - 1 - v);
            float[] range = BridgeContract.computeNormalizedRange(snd, path);
            if (range[0] > 0.0f || range[1] < 1.0f) {
              bridge.setSampleRange(engineRow, range[0], range[1]);
            }
          }

          if (v < sounds.size()) {
            SoundDrum snd = (SoundDrum) sounds.get(sounds.size() - 1 - v);
            try {
              ((ChuckArray) bridge.getGlobalObject(BridgeContract.G_KIT_PITCH))
                  .setFloat(engineRow, snd.getPitchSemitones());
              ((ChuckArray) bridge.getGlobalObject(BridgeContract.G_KIT_MUTE_GROUP))
                  .setInt(engineRow, (long) snd.getMuteGroup());
              ((ChuckArray) bridge.getGlobalObject(BridgeContract.G_KIT_REVERSE))
                  .setInt(engineRow, snd.isReverse() ? 1L : 0L);
              EnvelopeModel adsr = snd.getAdsr();
              if (adsr != null) {
                ((ChuckArray) bridge.getGlobalObject(BridgeContract.G_KIT_ATTACK))
                    .setFloat(engineRow, adsr.attack());
                ((ChuckArray) bridge.getGlobalObject(BridgeContract.G_KIT_DECAY))
                    .setFloat(engineRow, adsr.decay());
                ((ChuckArray) bridge.getGlobalObject(BridgeContract.G_KIT_SUSTAIN))
                    .setFloat(engineRow, adsr.sustain());
                ((ChuckArray) bridge.getGlobalObject(BridgeContract.G_KIT_RELEASE))
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
          ClipModel clip = kit.getClips().get(activeClipIdx);
          int stepCount = clip.getStepCount();
          for (int r = 0; r < clip.getRowCount(); r++) {
            for (int s = 0; s < stepCount; s++) {
              org.deluge.model.StepData step = clip.getStep(r, s);
              if (step != null && step.active() && r < voiceCount) {
                bridge.setStep(startRow + r, s, true);
                bridge.setVelocity(startRow + r, s, step.velocity());
                bridge.setIterance(startRow + r, s, step.iterance());
                bridge.setStepFill(startRow + r, s, step.fill());
                bridge.setStepNudge(startRow + r, s, step.nudge());
              }
            }
          }
        }
      } else if (track instanceof SynthTrackModel synth) {
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
        activeClipIdx = synth.getActiveClipIndex();
        if (activeClipIdx >= 0 && activeClipIdx < synth.getClips().size()) {
          ClipModel clip = synth.getClips().get(activeClipIdx);
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
                bridge.setStepNudge(engineRow, s, step.nudge());
              }
            }
          }
        }

        // Push oscType (0=SINE, 1=SAW, 2=SQUARE, 3=TRIANGLE, 4=NOISE) to ALL rows
        ChuckArray oscTypeArr = (ChuckArray) bridge.getGlobalObject(BridgeContract.G_OSC_TYPE);
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

        // ── Push new synth fields ──
        for (int v = 0; v < totalSynthRows; v++) {
          bridge.setTrackLevel(startRow + v, synth.getVolume());
        }
        for (int v = 0; v < totalSynthRows; v++) {
          bridge.setDelaySend(startRow + v, synth.getDelaySend());
          bridge.setReverbSend(startRow + v, synth.getReverbSend());
        }

        // ── DX7 patch ──
        String dx7patch = synth.getDx7Patch();
        if (dx7patch != null && !dx7patch.isEmpty()) {
          for (int v = 0; v < totalSynthRows; v++) {
            bridge.setGlobalString("g_dx7_patch_" + (startRow + v), dx7patch);
          }
          // Push opSwitch mask
          try {
            byte[] raw = org.deluge.shadow.audio.Dx7Patch.hexToBytes(dx7patch);
            int opSwitch = raw[org.deluge.shadow.audio.Dx7Patch.OFF_OP_SWITCH] & 0xFF;
            for (int v = 0; v < totalSynthRows; v++) {
              bridge.setGlobalInt("g_dx7_opSwitch_" + (startRow + v), opSwitch);
            }
          } catch (Exception ignored) {
          }
        }
      } else if (track instanceof AudioTrackModel audio) {
        // Mark engine row as type-2 (audio)
        bridge.setTrackType(startRow, 2);
        if (trackTypeArr != null) trackTypeArr.setInt(startRow, 2L);
        // Push audio threshold params
        bridge.setAudioThreshold(startRow, audio.getThresholdMode());
        bridge.setAudioThresholdLevel(startRow, audio.getThresholdLevel());
        // Push clip pattern data
        int activeClipIdx = audio.getActiveClipIndex();
        if (activeClipIdx >= 0 && activeClipIdx < audio.getClips().size()) {
          ClipModel clip = audio.getClips().get(activeClipIdx);
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
          AudioTrackModel.AudioClip aClip = audio.getAudioClips().get(aClipIdx);
          bridge.setAudioPlay(startRow, aClip.isPlaying() ? 1 : 0);
          bridge.setAudioLoop(startRow, aClip.isPlaying() || audio.isLooping() ? 1 : 0);
          bridge.setAudioRate(startRow, audio.getPlayRate());
          // Push sample position globals
          bridge.setGlobalFloat(
              "g_audio_clip_start_" + startRow, (float) aClip.getStartSamplePos());
          bridge.setGlobalFloat("g_audio_clip_end_" + startRow, (float) aClip.getEndSamplePos());
          // Push audio file path
          String filePath = aClip.getFilePath();
          if (filePath != null && !filePath.isEmpty()) {
            bridge.setGlobalString("g_audio_file_path_" + startRow, filePath);
          }
        }
      }

      // Track length and stepCount for all rows of this track
      int rowLen = track.getClips().isEmpty() ? 16 : track.getClips().get(0).getStepCount();
      int totalRows = voiceCount;
      if (track instanceof SynthTrackModel synthTrackModel) {
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
      TrackModel track = tracks.get(t);
      int startRow = m.engineStart[t];
      int voiceCount = m.voiceCount[t];
      List<ClipModel> clips = track.getClips();

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

        // Push per-clip play mode
        bridge.setClipPlayMode(t, c, clip.getPlayMode().ordinal());
        // Push per-clip play direction
        bridge.setClipPlayDirection(t, c, clip.getPlayDirection().ordinal());
      }
    }

    // ── Push master-level globals ──
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
    bridge.setGlobalFloat(
        BridgeContract.G_REVERB_COMP_SHAPE, currentProject.getReverbCompressorShape());
    bridge.setGlobalFloat(
        BridgeContract.G_REVERB_COMP_VOLUME, currentProject.getReverbCompressorVolume());

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
    org.deluge.midi.MidiService midiService = app.getMidiService();
    bridge.setFollowEnabled(midiService != null && midiService.isRecording());
    bridge.setGlobalInt(BridgeContract.G_FOLLOW_CH_A, bridge.getFollowMidChannel('A'));
    bridge.setGlobalInt(BridgeContract.G_FOLLOW_CH_B, bridge.getFollowMidChannel('B'));
    bridge.setGlobalInt(BridgeContract.G_FOLLOW_CH_C, bridge.getFollowMidChannel('C'));
    bridge.setGlobalInt(BridgeContract.G_FOLLOW_TRACK_A, bridge.getFollowTrack('A'));
    bridge.setGlobalInt(BridgeContract.G_FOLLOW_TRACK_B, bridge.getFollowTrack('B'));
    bridge.setGlobalInt(BridgeContract.G_FOLLOW_TRACK_C, bridge.getFollowTrack('C'));

    // ── Apply GlobalEffectable clip FX overrides for each track's active clip ──
    for (int t = 0; t < tracks.size(); t++) {
      TrackModel track = tracks.get(t);
      int clipIdx = track.getActiveClipIndex();
      if (track instanceof AudioTrackModel audioTrk) {
        if (clipIdx >= 0 && clipIdx < audioTrk.getAudioClips().size()) {
          applyClipFxOverrides(t, clipIdx);
        }
      } else if (clipIdx >= 0 && clipIdx < track.getClips().size()) {
        applyClipFxOverrides(t, clipIdx);
      }
    }

    // Signal engine shreds to re-allocate their UGen arrays
    if (!tracks.isEmpty()) {
      bridge.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    }

    // ── Push hardware character preferences ──
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
  }

  /**
   * Override song-level FX parameters (G_SP_* globals) with the active clip's per-clip FX params.
   */
  public void applyClipFxOverrides(int track, int clipIdx) {
    ProjectModel currentProject = app.getCurrentProject();
    if (currentProject == null) return;

    List<TrackModel> tracks = currentProject.getTracks();
    if (track < 0 || track >= tracks.size()) return;
    TrackModel trk = tracks.get(track);
    if (clipIdx < 0) return;

    // ── Audio tracks: read typed fields from AudioClip ──
    if (trk instanceof AudioTrackModel audioTrk) {
      if (clipIdx >= audioTrk.getAudioClips().size()) return;
      AudioTrackModel.AudioClip aClip = audioTrk.getAudioClips().get(clipIdx);
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
      return;
    }

    // ── Synth/kit tracks: read kitParams map ──
    if (clipIdx >= trk.getClips().size()) return;
    ClipModel clip = trk.getClips().get(clipIdx);
    Map<String, Float> kp = clip.getKitParams();
    if (kp == null || kp.isEmpty()) return;

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

  /** Push per-step automation data for a single clip to the bridge. */
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
  public static int parseRootKey(String key) {
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
  public static int parseScaleIndex(String scale) {
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

  /** Propagates ProjectModel changes to bridge globals via MVC listener pattern. */
  private class BridgeProjectListener implements ProjectModel.ProjectListener {
    private final ProjectModel model;

    BridgeProjectListener(ProjectModel model) {
      this.model = model;
    }

    @Override
    public void onTrackListChanged() {
      pushModelToBridge();
      app.propagateCurrentModel();
      app.refreshGrids();
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
      bridge.setGlobalFloat(BridgeContract.G_REVERB_COMP_SHAPE, model.getReverbCompressorShape());
      bridge.setGlobalFloat(BridgeContract.G_REVERB_COMP_VOLUME, model.getReverbCompressorVolume());
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
}
