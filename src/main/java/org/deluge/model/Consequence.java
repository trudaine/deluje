package org.deluge.model;

import java.util.List;

/**
 * A single undoable mutation to the project model. Extends {@link UndoRedoStack.UndoableAction} so
 * existing stack infrastructure works unchanged.
 */
public interface Consequence extends UndoRedoStack.UndoableAction {

  Category category();

  enum Category {
    STEP,
    AUTOMATION,
    SYNTH_PARAM,
    PROJECT_PARAM,
    TRACK_STRUCT,
    CLIP_STRUCT,
    PATTERN_LOAD,
  }

  // ── Record implementations ──

  /** A single grid pad toggle. */
  record StepConsequence(
      ProjectModel project,
      int trackIndex,
      int clipIndex,
      int row,
      int step,
      StepData oldData,
      StepData newData)
      implements Consequence {
    @Override
    public void undo() {
      project.getTracks().get(trackIndex).getClips().get(clipIndex).setStep(row, step, oldData);
    }

    @Override
    public void redo() {
      project.getTracks().get(trackIndex).getClips().get(clipIndex).setStep(row, step, newData);
    }

    @Override
    public String getDescription() {
      return "Toggle step " + (step + 1) + ":" + (row + 1);
    }

    @Override
    public Category category() {
      return Category.STEP;
    }
  }

  /** An automation point set or cleared. */
  record AutomationConsequence(
      ProjectModel project,
      int trackIndex,
      int clipIndex,
      String paramName,
      int step,
      float oldValue,
      float newValue)
      implements Consequence {
    @Override
    public void undo() {
      project
          .getTracks()
          .get(trackIndex)
          .getClips()
          .get(clipIndex)
          .setAutomation(paramName, step, oldValue);
    }

    @Override
    public void redo() {
      project
          .getTracks()
          .get(trackIndex)
          .getClips()
          .get(clipIndex)
          .setAutomation(paramName, step, newValue);
    }

    @Override
    public String getDescription() {
      return "Edit automation " + paramName + " step " + (step + 1);
    }

    @Override
    public Category category() {
      return Category.AUTOMATION;
    }
  }

  /** A per-row automation point set or cleared. */
  record RowAutomationConsequence(
      ProjectModel project,
      int trackIndex,
      int clipIndex,
      int rowIndex,
      String paramName,
      int step,
      float oldValue,
      float newValue)
      implements Consequence {
    @Override
    public void undo() {
      project
          .getTracks()
          .get(trackIndex)
          .getClips()
          .get(clipIndex)
          .setRowAutomation(rowIndex, paramName, step, oldValue);
    }

    @Override
    public void redo() {
      project
          .getTracks()
          .get(trackIndex)
          .getClips()
          .get(clipIndex)
          .setRowAutomation(rowIndex, paramName, step, newValue);
    }

    @Override
    public String getDescription() {
      return "Edit row automation " + paramName + " row " + (rowIndex + 1) + " step " + (step + 1);
    }

    @Override
    public Category category() {
      return Category.AUTOMATION;
    }
  }

  /** A single synth/kit parameter slider change. {@code timestamp} enables coalescing. */
  record SynthParamConsequence(
      ProjectModel project,
      int trackIndex,
      String paramName,
      float oldValue,
      float newValue,
      long timestamp)
      implements Consequence {
    @Override
    public void undo() {
      apply(oldValue);
    }

    @Override
    public void redo() {
      apply(newValue);
    }

    private void apply(float value) {
      if (trackIndex < 0 || trackIndex >= project.getTracks().size()) return;
      var track = project.getTracks().get(trackIndex);
      if (track instanceof SynthTrackModel synth) {
        if (applyEnvSliderParam(synth, paramName, value)) {
          return;
        }
        switch (paramName) {
          case "unisonDetune" -> synth.getUnison().setUnisonDetune(value / 100.0f);
          case "unisonSpread" -> synth.getUnison().setUnisonStereoSpread(value / 100.0f);
          case "waveIndex" -> synth.setWaveIndex(value / 1000.0f);
          case "lpfCutoff" -> synth.setLpfFreq((value / 100.0f) * 20000.0f);
          case "lpfResonance" -> synth.setLpfRes(value / 100.0f);
          case "filterDrive" -> synth.setFilterDrive(value / 100.0f);
          case "fmRatio" -> synth.setFmRatio(value / 100.0f);
          case "fmAmount" -> synth.setFmAmount(value / 100.0f);
          case "carrier1Fb" -> synth.setCarrier1Feedback(value / 100.0f);
          case "carrier2Fb" -> synth.setCarrier2Feedback(value / 100.0f);
          case "mod1Fb" -> synth.setModulator1Feedback(value / 100.0f);
          case "mod2Amt" -> synth.setModulator2Amount(value / 100.0f);
          case "mod2Fb" -> synth.setModulator2Feedback(value / 100.0f);
          case "noiseVol" -> synth.setNoiseVol(value / 100.0f);
          case "oscMix" -> synth.setOscMix(value / 100.0f);
          case "portamento" -> synth.setPortamento(value / 100.0f);
          case "osc1Pitch" -> synth.setOsc1PitchAdjustQ31(Math.round(value) * 178956);
          case "osc2Pitch" -> {
            int cents = Math.round(value);
            synth.setOsc2PitchAdjustQ31(cents * 178956);
            synth.setOsc2Transpose(cents / 100);
            synth.setOsc2Cents(cents % 100);
          }
          case "mod1Pitch" -> {
            int cents = Math.round(value);
            synth.setModulator1Transpose(cents / 100);
            synth.setModulator1Cents(cents % 100);
          }
          case "mod2Pitch" -> {
            int cents = Math.round(value);
            synth.setModulator2Transpose(cents / 100);
            synth.setModulator2Cents(cents % 100);
          }
          case "osc1TsAmt" -> synth.setOsc1TimeStretchAmount(value / 100.0f);
          case "osc1Cents" -> synth.setOsc1Cents(Math.round(value));
          case "osc2TsAmt" -> synth.setOsc2TimeStretchAmount(value / 100.0f);
          case "osc2Cents" -> synth.setOsc2Cents(Math.round(value));
          case "osc2Transpose" -> synth.setOsc2Transpose(Math.round(value));
          case "stutterRate" -> synth.getStutter().setStutterRate(value / 100.0f);
          case "eqBass" -> synth.setEqBass(value / 100.0f * 12.0f);
          case "eqTreble" -> synth.setEqTreble(value / 100.0f * 12.0f);
          case "hpfCutoff" -> synth.setHpfFreq(value * 200.0f);
          case "hpfResonance" -> synth.setHpfRes(value / 100.0f);
          case "hpfMorph" -> synth.setHpfMorph(value / 50.0f);
          case "hpfFm" -> synth.setHpfFm(value / 100.0f);
          case "compAttack" -> synth.setCompressorAttack(value / 100.0f);
          case "compRelease" -> synth.setCompressorRelease(value / 100.0f);
          case "compRatio" -> synth.setCompressorRatio(value);
          case "compBlend" -> synth.setCompressorBlend(value / 100.0f);
          case "compressorShape" -> synth.setCompressorShape(value / 100.0f);
          case "compSidechainHpf" -> synth.setCompressorSidechainHpf(value / 100.0f);
          case "modFxRate" -> synth.setModFxRate(value / 100.0f);
          case "modFxDepth" -> synth.setModFxDepth(value / 100.0f);
          case "modFxFeedback" -> synth.setModFxFeedback(value / 100.0f);
          case "modFxOffset" -> synth.setModFxOffset(value / 100.0f);
          case "reverbSend" -> synth.setReverbSend(value / 100.0f);
          case "delaySend" -> synth.setDelaySend(value / 100.0f);
          // Arpeggiator / Randomizer parameters
          // The ARP panel's Rate slider is the C arp-rate knob (0-50) → GLOBAL_ARP_RATE.
          case "arpRate" ->
              synth.setArpRateQ31(
                  org.deluge.firmware2.Functions.computeFinalValueForStandardMenuItem(
                      Math.round(value)));
          case "arpGate" -> synth.setArp(synth.getArp().toBuilder().gate(value / 100.0f).build());
          case "arpStepRepeat" ->
              synth.setArp(synth.getArp().toBuilder().stepRepeat((int) value).build());
          case "arpRhythm" ->
              synth.setArp(synth.getArp().toBuilder().rhythmIndex((int) value).build());
          case "arpSeqLength" ->
              synth.setArp(synth.getArp().toBuilder().seqLength((int) value).build());
          case "arpOctaveSpread" ->
              synth.setArp(synth.getArp().toBuilder().octaveSpread(value / 100.0f).build());
          case "arpGateSpread" ->
              synth.setArp(synth.getArp().toBuilder().gateSpread(value / 100.0f).build());
          case "arpVelSpread" ->
              synth.setArp(synth.getArp().toBuilder().velSpread(value / 100.0f).build());
          case "arpRatchet" ->
              synth.setArp(synth.getArp().toBuilder().ratchetAmount((int) value).build());
          case "arpNoteProb" ->
              synth.setArp(synth.getArp().toBuilder().noteProbability(value / 100.0f).build());
          case "arpChordPoly" ->
              synth.setArp(synth.getArp().toBuilder().chordPolyphony((int) value).build());
          case "arpChordProb" ->
              synth.setArp(synth.getArp().toBuilder().chordProbability(value / 100.0f).build());
          case "arpChordType" ->
              synth.setArp(synth.getArp().toBuilder().chordType((int) value).build());
          case "arpNumOctaves" ->
              synth.setArp(synth.getArp().toBuilder().numOctaves((int) value).build());
          case "arpKitArp" -> synth.setArp(synth.getArp().toBuilder().kitArp((int) value).build());
          case "arpRandomizerLock" ->
              synth.setArp(synth.getArp().toBuilder().randomizerLock((int) value).build());
          // Gold encoder parameters (raw float values)
          case "goldVolume" -> synth.setVolume(value);
          case "goldPan" -> synth.setPan(value);
          case "goldLpfCutoff" -> synth.setLpfFreq(value);
          case "goldLpfResonance" -> synth.setLpfRes(value);
          case "goldEnv0Attack" -> {
            var env0 = synth.getEnv(0);
            synth.setEnv(
                0,
                new EnvelopeModel(
                    value,
                    env0.decay(),
                    env0.sustain(),
                    env0.release(),
                    env0.target(),
                    env0.amount()));
          }
          case "goldEnv0Release" -> {
            var env0 = synth.getEnv(0);
            synth.setEnv(
                0,
                new EnvelopeModel(
                    env0.attack(),
                    env0.decay(),
                    env0.sustain(),
                    value,
                    env0.target(),
                    env0.amount()));
          }
          case "goldDelaySyncLevel" -> synth.setDelaySyncLevel((int) value);
          case "goldDelayFeedback" -> synth.setDelayFeedbackQ31((int) value);
          case "goldReverbSend" -> synth.setReverbSend(value);
          case "goldHpfCutoff" -> synth.setHpfFreq(value);
          case "goldLfo0Rate" -> {
            var lfo0 = synth.getLfo(0);
            synth.setLfo(
                0,
                new LfoModel(
                    value,
                    lfo0.waveform(),
                    lfo0.depth(),
                    lfo0.target(),
                    lfo0.isLocal(),
                    lfo0.syncLevel(),
                    lfo0.syncType()));
          }
          case "goldLfo0Depth" -> {
            var lfo0 = synth.getLfo(0);
            synth.setLfo(
                0,
                new LfoModel(
                    lfo0.rateHz(),
                    lfo0.waveform(),
                    value,
                    lfo0.target(),
                    lfo0.isLocal(),
                    lfo0.syncLevel(),
                    lfo0.syncType()));
          }
          case "goldArpRate" -> synth.setArpRateQ31(SynthTrackModel.arpRateQ31FromUnit(value));
          case "goldPortamento" -> synth.setPortamento(value);
          case "goldOscMix" -> synth.setOscMix(value);
          case "goldBitcrusher" -> synth.setClippingAmount((int) value);
          default ->
              java.util.logging.Logger.getLogger(Consequence.class.getName())
                  .warning("SynthParamConsequence has no apply case for '" + paramName + "'");
        }
      }
    }

    private static boolean applyEnvSliderParam(
        SynthTrackModel synth, String paramName, float value) {
      if (paramName == null || !paramName.startsWith("env") || paramName.length() < 5) {
        return false;
      }
      int envIdx = paramName.charAt(3) - '0';
      if (envIdx < 0 || envIdx > 3) {
        return false;
      }
      String field = paramName.substring(4);
      EnvelopeModel cur = synth.getEnv(envIdx);
      switch (field) {
        case "Attack" ->
            synth.setEnv(
                envIdx,
                new EnvelopeModel(
                    value / 1000.0f,
                    cur.decay(),
                    cur.sustain(),
                    cur.release(),
                    cur.target(),
                    cur.amount()));
        case "Decay" ->
            synth.setEnv(
                envIdx,
                new EnvelopeModel(
                    cur.attack(),
                    value / 1000.0f,
                    cur.sustain(),
                    cur.release(),
                    cur.target(),
                    cur.amount()));
        case "Sustain" ->
            synth.setEnv(
                envIdx,
                new EnvelopeModel(
                    cur.attack(),
                    cur.decay(),
                    value / 100.0f,
                    cur.release(),
                    cur.target(),
                    cur.amount()));
        case "Release" ->
            synth.setEnv(
                envIdx,
                new EnvelopeModel(
                    cur.attack(),
                    cur.decay(),
                    cur.sustain(),
                    value / 1000.0f,
                    cur.target(),
                    cur.amount()));
        case "Amount" ->
            synth.setEnv(
                envIdx,
                new EnvelopeModel(
                    cur.attack(),
                    cur.decay(),
                    cur.sustain(),
                    cur.release(),
                    cur.target(),
                    value / 100.0f));
        default -> {
          return false;
        }
      }
      return true;
    }

    /**
     * Read the current value of {@code paramName} on {@code trackIndex} in the exact scale expected
     * by {@link #apply(float)}.
     */
    public static float readCurrent(ProjectModel project, int trackIndex, String paramName) {
      if (trackIndex < 0 || trackIndex >= project.getTracks().size()) return 0.0f;
      var track = project.getTracks().get(trackIndex);
      if (!(track instanceof SynthTrackModel synth)) return 0.0f;
      if (paramName != null && paramName.startsWith("env") && paramName.length() >= 5) {
        int envIdx = paramName.charAt(3) - '0';
        if (envIdx >= 0 && envIdx <= 3) {
          EnvelopeModel cur = synth.getEnv(envIdx);
          switch (paramName.substring(4)) {
            case "Attack" -> {
              return cur.attack() * 1000.0f;
            }
            case "Decay" -> {
              return cur.decay() * 1000.0f;
            }
            case "Sustain" -> {
              return cur.sustain() * 100.0f;
            }
            case "Release" -> {
              return cur.release() * 1000.0f;
            }
            case "Amount" -> {
              return cur.amount() * 100.0f;
            }
          }
        }
      }
      return switch (paramName) {
        case "unisonDetune" -> synth.getUnison().getUnisonDetune() * 100.0f;
        case "unisonSpread" -> synth.getUnison().getUnisonStereoSpread() * 100.0f;
        case "waveIndex" -> synth.getWaveIndex() * 1000.0f;
        case "lpfCutoff" -> (synth.getLpfFreq() / 20000.0f) * 100.0f;
        case "lpfResonance" -> synth.getLpfRes();
        case "filterDrive" -> synth.getFilterDrive() * 100.0f;
        case "fmRatio" -> synth.getFmRatio() * 100.0f;
        case "fmAmount" -> synth.getFmAmount() * 100.0f;
        case "carrier1Fb" -> synth.getCarrier1Feedback() * 100.0f;
        case "carrier2Fb" -> synth.getCarrier2Feedback() * 100.0f;
        case "mod1Fb" -> synth.getModulator1Feedback() * 100.0f;
        case "mod2Amt" -> synth.getModulator2Amount() * 100.0f;
        case "mod2Fb" -> synth.getModulator2Feedback() * 100.0f;
        case "noiseVol" -> synth.getNoiseVol() * 100.0f;
        case "oscMix" -> synth.getOscMix() * 100.0f;
        case "portamento" -> synth.getPortamento() * 100.0f;
        case "osc1Pitch" ->
            synth.getOsc1PitchAdjustQ31() == Integer.MIN_VALUE
                ? 0.0f
                : Math.round(synth.getOsc1PitchAdjustQ31() / 178956.0f);
        case "osc2Pitch" ->
            synth.getOsc2PitchAdjustQ31() != Integer.MIN_VALUE
                ? Math.round(synth.getOsc2PitchAdjustQ31() / 178956.0f)
                : (synth.getOsc2Transpose() * 100.0f + synth.getOsc2Cents());
        case "mod1Pitch" -> synth.getModulator1Transpose() * 100.0f + synth.getModulator1Cents();
        case "mod2Pitch" -> synth.getModulator2Transpose() * 100.0f + synth.getModulator2Cents();
        case "osc1TsAmt" -> synth.getOsc1TimeStretchAmount() * 100.0f;
        case "osc1Cents" -> synth.getOsc1Cents();
        case "osc2TsAmt" -> synth.getOsc2TimeStretchAmount() * 100.0f;
        case "osc2Cents" -> synth.getOsc2Cents();
        case "osc2Transpose" -> synth.getOsc2Transpose();
        case "stutterRate" -> synth.getStutter().getStutterRate() * 100.0f;
        case "eqBass" -> (synth.getEqBass() / 12.0f) * 100.0f;
        case "eqTreble" -> (synth.getEqTreble() / 12.0f) * 100.0f;
        case "hpfCutoff" -> synth.getHpfFreq() / 200.0f;
        case "hpfResonance" -> synth.getHpfRes() * 100.0f;
        case "hpfMorph" -> synth.getHpfMorph() * 50.0f;
        case "hpfFm" -> synth.getHpfFm() * 100.0f;
        case "compAttack" -> synth.getCompressorAttack() * 100.0f;
        case "compRelease" -> synth.getCompressorRelease() * 100.0f;
        case "compRatio" -> synth.getCompressorRatio();
        case "compBlend" -> synth.getCompressorBlend() * 100.0f;
        case "compressorShape" -> synth.getCompressorShape() * 100.0f;
        case "compSidechainHpf" -> synth.getCompressorSidechainHpf() * 100.0f;
        case "modFxRate" -> synth.getModFxRate() * 100.0f;
        case "modFxDepth" -> synth.getModFxDepth() * 100.0f;
        case "modFxFeedback" -> synth.getModFxFeedback() * 100.0f;
        case "modFxOffset" -> synth.getModFxOffset() * 100.0f;
        case "reverbSend" -> synth.getReverbSend() * 100.0f;
        case "delaySend" -> synth.getDelaySend() * 100.0f;
        case "arpRate" ->
            org.deluge.firmware2.Functions.computeCurrentValueForStandardMenuItem(
                synth.getArpRateQ31());
        case "arpGate" -> synth.getArp().gate() * 100.0f;
        case "arpStepRepeat" -> synth.getArp().stepRepeat();
        case "arpRhythm" -> synth.getArp().rhythmIndex();
        case "arpSeqLength" -> synth.getArp().seqLength();
        case "arpOctaveSpread" -> synth.getArp().octaveSpread() * 100.0f;
        case "arpGateSpread" -> synth.getArp().gateSpread() * 100.0f;
        case "arpVelSpread" -> synth.getArp().velSpread() * 100.0f;
        case "arpRatchet" -> synth.getArp().ratchetAmount();
        case "arpNoteProb" -> synth.getArp().noteProbability() * 100.0f;
        case "arpChordPoly" -> synth.getArp().chordPolyphony();
        case "arpChordProb" -> synth.getArp().chordProbability() * 100.0f;
        case "arpChordType" -> synth.getArp().chordType();
        case "arpNumOctaves" -> synth.getArp().numOctaves();
        case "arpKitArp" -> synth.getArp().kitArp();
        case "arpRandomizerLock" -> synth.getArp().randomizerLock();
        case "goldVolume" -> synth.getVolume();
        case "goldPan" -> synth.getPan();
        case "goldLpfCutoff" -> synth.getLpfFreq();
        case "goldLpfResonance" -> synth.getLpfRes();
        case "goldEnv0Attack" -> synth.getEnv(0).attack();
        case "goldEnv0Release" -> synth.getEnv(0).release();
        case "goldDelaySyncLevel" -> synth.getDelaySyncLevel();
        case "goldDelayFeedback" -> synth.getDelayFeedbackQ31();
        case "goldReverbSend" -> synth.getReverbSend();
        case "goldHpfCutoff" -> synth.getHpfFreq();
        case "goldLfo0Rate" -> synth.getLfo(0).rateHz();
        case "goldLfo0Depth" -> synth.getLfo(0).depth();
        case "goldArpRate" -> synth.getArpRate();
        case "goldPortamento" -> synth.getPortamento();
        case "goldOscMix" -> synth.getOscMix();
        case "goldBitcrusher" -> synth.getClippingAmount();
        default -> 0.0f;
      };
    }

    @Override
    public String getDescription() {
      return "Change " + paramName;
    }

    @Override
    public Category category() {
      return Category.SYNTH_PARAM;
    }
  }

  /** A project-level parameter change (BPM, swing, master volume, etc.). */
  record ProjectParamConsequence(
      ProjectModel project, String paramName, float oldValue, float newValue)
      implements Consequence {
    @Override
    public void undo() {
      apply(oldValue);
    }

    @Override
    public void redo() {
      apply(newValue);
    }

    private void apply(float value) {
      // Every param name pushed by ProjectModel must have a case here, or undoing that edit
      // consumes the undo step while reverting nothing (45 of 56 names used to be missing).
      switch (paramName) {
        case "BPM", "bpm" -> project.setBpm(value);
        case "Swing", "swing" -> project.setSwing(value);
        case "Volume", "masterVolume" -> project.setMasterVolume(value);
        case "Pan", "masterPan" -> project.setMasterPan(value);
        case "masterDelay" -> project.setMasterDelay(value);
        case "humanize" -> project.setHumanize(value);
        case "reverbRoomSize" -> project.setReverbRoomSize(value);
        case "reverbDampening" -> project.setReverbDampening(value);
        case "reverbWidth" -> project.setReverbWidth(value);
        case "reverbHpf" -> project.setReverbHpf(value);
        case "reverbPan" -> project.setReverbPan(value);
        case "reverbModel" -> project.setReverbModel((int) value);
        case "reverbCompressorShape" -> project.setReverbCompressorShape(value);
        case "reverbCompressorVolume" -> project.setReverbCompressorVolume(value);
        case "reverbCompressorAttack" -> project.setReverbCompressorAttack(value);
        case "reverbCompressorRelease" -> project.setReverbCompressorRelease(value);
        case "reverbCompHpf" -> project.setReverbCompHpf(value);
        case "reverbCompBlend" -> project.setReverbCompBlend(value);
        case "delayPingPong" -> project.setDelayPingPong((int) value);
        case "delayAnalog" -> project.setDelayAnalog((int) value);
        case "delaySyncLevel" -> project.setDelaySyncLevel((int) value);
        case "delaySyncType" -> project.setDelaySyncType((int) value);
        case "sidechainAttack" -> project.setSidechainAttack(value);
        case "sidechainRelease" -> project.setSidechainRelease(value);
        case "compressorAttack" -> project.setCompressorAttack(value);
        case "compressorRelease" -> project.setCompressorRelease(value);
        case "compressorThreshold" -> project.setCompressorThreshold(value);
        case "compressorRatio" -> project.setCompressorRatio(value);
        case "compressorBlend" -> project.setCompressorBlend(value);
        case "userScale" -> project.setUserScale((int) value);
        case "songParamVolume" -> project.setSongParamVolume(value);
        case "songParamPan" -> project.setSongParamPan(value);
        case "songParamReverbAmount" -> project.setSongParamReverbAmount(value);
        case "songParamDelayRate" -> project.setSongParamDelayRate(value);
        case "songParamDelayFeedback" -> project.setSongParamDelayFeedback(value);
        case "songParamSidechainShape" -> project.setSongParamSidechainShape(value);
        case "songParamSidechainVolume" -> project.setSongParamSidechainVolume(value);
        case "songParamStutterRate" -> project.setSongParamStutterRate(value);
        case "songParamSampleRateReduction" -> project.setSongParamSampleRateReduction(value);
        case "songParamBitCrush" -> project.setSongParamBitCrush(value);
        case "songParamModFXRate" -> project.setSongParamModFXRate(value);
        case "songParamModFXDepth" -> project.setSongParamModFXDepth(value);
        case "songParamModFXOffset" -> project.setSongParamModFXOffset(value);
        case "songParamModFXFeedback" -> project.setSongParamModFXFeedback(value);
        case "songParamCompressorThreshold" -> project.setSongParamCompressorThreshold(value);
        case "songParamLpfMorph" -> project.setSongParamLpfMorph(value);
        case "songParamHpfMorph" -> project.setSongParamHpfMorph(value);
        case "songParamLpfFrequency" -> project.setSongParamLpfFrequency(value);
        case "songParamLpfResonance" -> project.setSongParamLpfResonance(value);
        case "songParamHpfFrequency" -> project.setSongParamHpfFrequency(value);
        case "songParamHpfResonance" -> project.setSongParamHpfResonance(value);
        case "songParamEqBass" -> project.setSongParamEqBass(value);
        case "songParamEqTreble" -> project.setSongParamEqTreble(value);
        case "songParamEqBassFrequency" -> project.setSongParamEqBassFrequency(value);
        case "songParamEqTrebleFrequency" -> project.setSongParamEqTrebleFrequency(value);
        default ->
            java.util.logging.Logger.getLogger(Consequence.class.getName())
                .warning("ProjectParamConsequence has no apply case for '" + paramName + "'");
      }
    }

    /** Read the current value of {@code paramName} on {@code project}. */
    public static float readCurrent(ProjectModel project, String paramName) {
      return switch (paramName) {
        case "BPM", "bpm" -> project.getBpm();
        case "Swing", "swing" -> project.getSwing();
        case "Volume", "masterVolume" -> project.getMasterVolume();
        case "Pan", "masterPan" -> project.getMasterPan();
        case "masterDelay" -> project.getMasterDelay();
        case "humanize" -> project.getHumanize();
        case "reverbRoomSize" -> project.getReverbRoomSize();
        case "reverbDampening" -> project.getReverbDampening();
        case "reverbWidth" -> project.getReverbWidth();
        case "reverbHpf" -> project.getReverbHpf();
        case "reverbPan" -> project.getReverbPan();
        case "reverbModel" -> project.getReverbModel();
        case "reverbCompressorShape" -> project.getReverbCompressorShape();
        case "reverbCompressorVolume" -> project.getReverbCompressorVolume();
        case "reverbCompressorAttack" -> project.getReverbCompressorAttack();
        case "reverbCompressorRelease" -> project.getReverbCompressorRelease();
        case "reverbCompHpf" -> project.getReverbCompHpf();
        case "reverbCompBlend" -> project.getReverbCompBlend();
        case "delayPingPong" -> project.getDelayPingPong();
        case "delayAnalog" -> project.getDelayAnalog();
        case "delaySyncLevel" -> project.getDelaySyncLevel();
        case "delaySyncType" -> project.getDelaySyncType();
        case "sidechainAttack" -> project.getSidechainAttack();
        case "sidechainRelease" -> project.getSidechainRelease();
        case "compressorAttack" -> project.getCompressorAttack();
        case "compressorRelease" -> project.getCompressorRelease();
        case "compressorThreshold" -> project.getCompressorThreshold();
        case "compressorRatio" -> project.getCompressorRatio();
        case "compressorBlend" -> project.getCompressorBlend();
        case "userScale" -> project.getUserScale();
        case "songParamVolume" -> project.getSongParamVolume();
        case "songParamPan" -> project.getSongParamPan();
        case "songParamReverbAmount" -> project.getSongParamReverbAmount();
        case "songParamDelayRate" -> project.getSongParamDelayRate();
        case "songParamDelayFeedback" -> project.getSongParamDelayFeedback();
        case "songParamSidechainShape" -> project.getSongParamSidechainShape();
        case "songParamSidechainVolume" -> project.getSongParamSidechainVolume();
        case "songParamStutterRate" -> project.getSongParamStutterRate();
        case "songParamSampleRateReduction" -> project.getSongParamSampleRateReduction();
        case "songParamBitCrush" -> project.getSongParamBitCrush();
        case "songParamModFXRate" -> project.getSongParamModFXRate();
        case "songParamModFXDepth" -> project.getSongParamModFXDepth();
        case "songParamModFXOffset" -> project.getSongParamModFXOffset();
        case "songParamModFXFeedback" -> project.getSongParamModFXFeedback();
        case "songParamCompressorThreshold" -> project.getSongParamCompressorThreshold();
        case "songParamLpfMorph" -> project.getSongParamLpfMorph();
        case "songParamHpfMorph" -> project.getSongParamHpfMorph();
        case "songParamLpfFrequency" -> project.getSongParamLpfFrequency();
        case "songParamLpfResonance" -> project.getSongParamLpfResonance();
        case "songParamHpfFrequency" -> project.getSongParamHpfFrequency();
        case "songParamHpfResonance" -> project.getSongParamHpfResonance();
        case "songParamEqBass" -> project.getSongParamEqBass();
        case "songParamEqTreble" -> project.getSongParamEqTreble();
        case "songParamEqBassFrequency" -> project.getSongParamEqBassFrequency();
        case "songParamEqTrebleFrequency" -> project.getSongParamEqTrebleFrequency();
        default -> 0.0f;
      };
    }

    @Override
    public String getDescription() {
      return "Change " + paramName;
    }

    @Override
    public Category category() {
      return Category.PROJECT_PARAM;
    }
  }

  /** Add, remove, or reorder a track. */
  record TrackStructureConsequence(
      ProjectModel project, int operation, int index, TrackModel trackSnapshot, String description)
      implements Consequence {
    public static final int ADD = 0;
    public static final int REMOVE = 1;
    public static final int MOVE_UP = 2;
    public static final int MOVE_DOWN = 3;

    @Override
    public void undo() {
      apply(true);
    }

    @Override
    public void redo() {
      apply(false);
    }

    private void apply(boolean isUndo) {
      var tracks = project.getTracks();
      int idx = index;
      switch (operation) {
        case ADD -> {
          if (isUndo) {
            if (idx < tracks.size()) project.removeTrack(tracks.get(idx));
          } else {
            project.addTrack(idx, trackSnapshot);
          }
        }
        case REMOVE -> {
          if (isUndo) {
            project.addTrack(idx, trackSnapshot);
          } else {
            if (idx < tracks.size()) project.removeTrack(tracks.get(idx));
          }
        }
        case MOVE_UP -> {
          int swapIdx = isUndo ? idx + 1 : idx - 1;
          if (swapIdx >= 0 && swapIdx < tracks.size() && idx >= 0 && idx < tracks.size()) {
            project.moveTrackUp(Math.max(idx, swapIdx));
          }
        }
        case MOVE_DOWN -> {
          int swapIdx = isUndo ? idx - 1 : idx + 1;
          if (swapIdx >= 0 && swapIdx < tracks.size() && idx >= 0 && idx < tracks.size()) {
            project.moveTrackDown(Math.min(idx, swapIdx));
          }
        }
      }
    }

    @Override
    public String getDescription() {
      return description;
    }

    @Override
    public Category category() {
      return Category.TRACK_STRUCT;
    }
  }

  /** Clip add, delete, duplicate, or rename. */
  record ClipStructureConsequence(
      ProjectModel project,
      int trackIndex,
      int clipIndex,
      int operation,
      ClipModel clipSnapshot,
      String previousName,
      String newName)
      implements Consequence {
    public static final int ADD = 0;
    public static final int REMOVE = 1;
    public static final int DUPLICATE = 2;
    public static final int RENAME = 3;

    @Override
    public void undo() {
      apply(true);
    }

    @Override
    public void redo() {
      apply(false);
    }

    private void apply(boolean isUndo) {
      var tracks = project.getTracks();
      if (trackIndex < 0 || trackIndex >= tracks.size()) return;
      var track = tracks.get(trackIndex);
      var clips = track.getClips();
      int ci = clipIndex;
      switch (operation) {
        case ADD -> {
          if (isUndo) {
            if (ci >= 0 && ci < clips.size()) track.removeClip(clips.get(ci));
          } else {
            if (ci >= 0 && ci <= clips.size()) track.getClips().add(ci, clipSnapshot);
            else track.addClip(clipSnapshot);
          }
        }
        case REMOVE -> {
          if (isUndo) {
            if (ci >= 0 && ci <= clips.size()) track.getClips().add(ci, clipSnapshot);
            else track.addClip(clipSnapshot);
          } else {
            if (ci >= 0 && ci < clips.size()) track.removeClip(clips.get(ci));
          }
        }
        case DUPLICATE -> {
          if (isUndo) {
            if (ci >= 0 && ci < clips.size()) track.removeClip(clips.get(ci));
          } else {
            if (ci >= 0 && ci <= clips.size()) track.getClips().add(ci, clipSnapshot);
            else track.addClip(clipSnapshot);
          }
        }
        case RENAME -> {
          String name = isUndo ? previousName : newName;
          if (ci >= 0 && ci < clips.size()) clips.get(ci).setName(name);
        }
      }
    }

    @Override
    public String getDescription() {
      return switch (operation) {
        case ADD -> "Add clip";
        case REMOVE -> "Remove clip";
        case DUPLICATE -> "Duplicate clip";
        case RENAME -> "Rename clip to " + newName;
        default -> "Clip operation";
      };
    }

    @Override
    public Category category() {
      return Category.CLIP_STRUCT;
    }
  }

  /**
   * Doubling a clip's length. Undo is lossless because {@link ClipModel#doubleLength()} grows the
   * grid non-destructively (the original pattern stays in {@code [0, oldLength)}), so shrinking
   * back and rebuilding the note rows restores the pre-double state exactly.
   */
  record ClipLengthConsequence(ProjectModel project, int trackIndex, int clipIndex, int oldLength)
      implements Consequence {
    @Override
    public void undo() {
      ClipModel clip = clip();
      if (clip == null) return;
      clip.setStepCount(oldLength);
      clip.rebuildNotesFromGrid();
    }

    @Override
    public void redo() {
      ClipModel clip = clip();
      if (clip != null) clip.doubleLength();
    }

    private ClipModel clip() {
      var tracks = project.getTracks();
      if (trackIndex < 0 || trackIndex >= tracks.size()) return null;
      var clips = tracks.get(trackIndex).getClips();
      if (clipIndex < 0 || clipIndex >= clips.size()) return null;
      return clips.get(clipIndex);
    }

    @Override
    public String getDescription() {
      return "Double clip length";
    }

    @Override
    public Category category() {
      return Category.CLIP_STRUCT;
    }
  }

  /**
   * A bulk clip-content change captured as before/after snapshots (deep copies). Used for edits
   * that can't be reversed incrementally — e.g. a typed clip-length change that discards notes on
   * shrink.
   */
  record ClipContentConsequence(
      ProjectModel project, int trackIndex, int clipIndex, ClipModel before, ClipModel after)
      implements Consequence {
    @Override
    public void undo() {
      ClipModel clip = clip();
      if (clip != null) clip.restoreFrom(before);
    }

    @Override
    public void redo() {
      ClipModel clip = clip();
      if (clip != null) clip.restoreFrom(after);
    }

    private ClipModel clip() {
      var tracks = project.getTracks();
      if (trackIndex < 0 || trackIndex >= tracks.size()) return null;
      var clips = tracks.get(trackIndex).getClips();
      if (clipIndex < 0 || clipIndex >= clips.size()) return null;
      return clips.get(clipIndex);
    }

    @Override
    public String getDescription() {
      return "Edit clip content";
    }

    @Override
    public Category category() {
      return Category.CLIP_STRUCT;
    }
  }

  /**
   * A bulk synth-parameter change (e.g. the Delugeator randomizer) captured as before/after
   * snapshots. Each snapshot is a detached {@link SynthTrackModel} carrying the copied parameters,
   * arpeggiator, and name; restoring copies them back into the live track. The UI must force a DSP
   * engine rebuild after undo/redo (as it does for a preset swap), since the whole sound changes.
   */
  record SynthRandomizeConsequence(
      ProjectModel project, int trackIndex, SynthTrackModel before, SynthTrackModel after)
      implements Consequence {

    /** Detached snapshot of {@code src}'s parameters, arp, and name for later restore. */
    public static SynthTrackModel snapshot(SynthTrackModel src) {
      SynthTrackModel snap = new SynthTrackModel(src.getName());
      snap.copyParametersFrom(src); // copyParametersFrom does not include the arp
      snap.setArp(src.getArp());
      return snap;
    }

    @Override
    public void undo() {
      apply(before);
    }

    @Override
    public void redo() {
      apply(after);
    }

    private void apply(SynthTrackModel snap) {
      var tracks = project.getTracks();
      if (trackIndex < 0 || trackIndex >= tracks.size()) return;
      if (tracks.get(trackIndex) instanceof SynthTrackModel s) {
        s.copyParametersFrom(snap);
        s.setArp(snap.getArp());
        s.setName(snap.getName());
      }
    }

    @Override
    public String getDescription() {
      return "Randomize synth";
    }

    @Override
    public Category category() {
      return Category.SYNTH_PARAM;
    }
  }

  /** Batch of consequences undone/redone as one. */
  record CompoundConsequence(String description, List<Consequence> children)
      implements Consequence {
    @Override
    public void undo() {
      for (int i = children.size() - 1; i >= 0; i--) {
        children.get(i).undo();
      }
    }

    @Override
    public void redo() {
      for (Consequence c : children) {
        c.redo();
      }
    }

    @Override
    public String getDescription() {
      return description;
    }

    @Override
    public Category category() {
      return Category.PATTERN_LOAD;
    }
  }

  /** Pattern load — applies/reverts a clip snapshot. */
  record PatternLoadConsequence(
      ProjectModel project,
      int trackIndex,
      int clipIndex,
      PatternModel.ClipSnapshot beforeSnapshot,
      PatternModel.ClipSnapshot afterSnapshot)
      implements Consequence {
    @Override
    public void undo() {
      apply(true);
    }

    @Override
    public void redo() {
      apply(false);
    }

    private void apply(boolean isUndo) {
      var tracks = project.getTracks();
      if (trackIndex < 0 || trackIndex >= tracks.size()) return;
      var track = tracks.get(trackIndex);
      int ci = clipIndex;
      if (ci < 0 || ci >= track.getClips().size()) return;
      var clip = track.getClips().get(ci);
      var snap = isUndo ? beforeSnapshot : afterSnapshot;
      snap.applyTo(clip);
    }

    @Override
    public String getDescription() {
      return "Load pattern";
    }

    @Override
    public Category category() {
      return Category.PATTERN_LOAD;
    }
  }
}
