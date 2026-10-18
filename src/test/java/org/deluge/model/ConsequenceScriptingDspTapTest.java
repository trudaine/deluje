package org.deluge.model;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.deluge.midi.DspTapCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link Consequence.SynthParamConsequence}, {@link
 * Consequence.ProjectParamConsequence}, {@link ScriptingEngine}, and {@link DspTapCodec}.
 */
class ConsequenceScriptingDspTapTest {

  @Test
  void synthParamConsequenceAppliesAndUndoesAllSliderParams() {
    ProjectModel project = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("Synth");
    project.addTrack(synth);

    assertSynthParamRoundTrip(project, "oscMix", 50.0f, 80.0f, () -> synth.getOscMix() * 100.0f);
    assertSynthParamRoundTrip(
        project, "portamento", 10.0f, 45.0f, () -> synth.getPortamento() * 100.0f);
    assertSynthParamRoundTrip(
        project, "carrier2Fb", 0.0f, 65.0f, () -> synth.getCarrier2Feedback() * 100.0f);
    assertSynthParamRoundTrip(
        project,
        "osc1Pitch",
        0.0f,
        -700.0f,
        () -> Math.round(synth.getOsc1PitchAdjustQ31() / 178956.0f));
    assertSynthParamRoundTrip(
        project,
        "osc2Pitch",
        0.0f,
        1250.0f,
        () -> Math.round(synth.getOsc2PitchAdjustQ31() / 178956.0f));
    assertSynthParamRoundTrip(
        project,
        "mod1Pitch",
        0.0f,
        325.0f,
        () -> synth.getModulator1Transpose() * 100.0f + synth.getModulator1Cents());
    assertSynthParamRoundTrip(
        project,
        "mod2Pitch",
        0.0f,
        -240.0f,
        () -> synth.getModulator2Transpose() * 100.0f + synth.getModulator2Cents());
    assertSynthParamRoundTrip(
        project, "osc1TsAmt", 0.0f, 75.0f, () -> synth.getOsc1TimeStretchAmount() * 100.0f);
    assertSynthParamRoundTrip(project, "osc1Cents", 0.0f, -15.0f, synth::getOsc1Cents);
    assertSynthParamRoundTrip(
        project, "osc2TsAmt", 0.0f, 40.0f, () -> synth.getOsc2TimeStretchAmount() * 100.0f);
    assertSynthParamRoundTrip(project, "osc2Cents", 0.0f, 22.0f, synth::getOsc2Cents);
    assertSynthParamRoundTrip(project, "osc2Transpose", 0.0f, -12.0f, synth::getOsc2Transpose);
    assertSynthParamRoundTrip(
        project, "stutterRate", 50.0f, 85.0f, () -> synth.getStutter().getStutterRate() * 100.0f);
    assertSynthParamRoundTrip(project, "eqBass", 0.0f, 50.0f, () -> synth.getEqBass() * 100.0f);
    assertSynthParamRoundTrip(
        project, "eqTreble", 0.0f, -50.0f, () -> synth.getEqTreble() * 100.0f);
    assertSynthParamRoundTrip(
        project, "hpfCutoff", 10.0f, 60.0f, () -> synth.getHpfFreq() / 200.0f);
    assertSynthParamRoundTrip(
        project, "hpfResonance", 10.0f, 70.0f, () -> synth.getHpfRes() * 100.0f);
    assertSynthParamRoundTrip(project, "hpfMorph", 0.0f, 35.0f, () -> synth.getHpfMorph() * 50.0f);
    assertSynthParamRoundTrip(project, "hpfFm", 0.0f, 55.0f, () -> synth.getHpfFm() * 100.0f);
    assertSynthParamRoundTrip(
        project, "compAttack", 20.0f, 65.0f, () -> synth.getCompressorAttack() * 100.0f);
    assertSynthParamRoundTrip(
        project, "compRelease", 30.0f, 80.0f, () -> synth.getCompressorRelease() * 100.0f);
    assertSynthParamRoundTrip(
        project, "compRatio", 4.0f, 50.0f, () -> synth.getCompressorRatio() * 100.0f);
    assertSynthParamRoundTrip(
        project, "compBlend", 100.0f, 40.0f, () -> synth.getCompressorBlend() * 100.0f);
    assertSynthParamRoundTrip(
        project, "compressorShape", 0.0f, 75.0f, () -> synth.getCompressorShape() * 100.0f);
    assertSynthParamRoundTrip(
        project, "compSidechainHpf", 0.0f, 30.0f, () -> synth.getCompressorSidechainHpf() * 100.0f);
    assertSynthParamRoundTrip(
        project, "modFxRate", 20.0f, 70.0f, () -> synth.getModFxRate() * 100.0f);
    assertSynthParamRoundTrip(
        project, "modFxDepth", 0.0f, 85.0f, () -> synth.getModFxDepth() * 100.0f);
    assertSynthParamRoundTrip(
        project, "modFxFeedback", 0.0f, 60.0f, () -> synth.getModFxFeedback() * 100.0f);
    assertSynthParamRoundTrip(
        project, "modFxOffset", 0.0f, 45.0f, () -> synth.getModFxOffset() * 100.0f);
    assertSynthParamRoundTrip(
        project, "reverbSend", 0.0f, 75.0f, () -> synth.getReverbSend() * 100.0f);
    assertSynthParamRoundTrip(
        project, "delaySend", 0.0f, 40.0f, () -> synth.getDelaySend() * 100.0f);

    for (int e = 0; e < 4; e++) {
      final int envIdx = e;
      assertSynthParamRoundTrip(
          project,
          "env" + envIdx + "Attack",
          10.0f,
          250.0f,
          () -> synth.getEnv(envIdx).attack() * 1000.0f);
      assertSynthParamRoundTrip(
          project,
          "env" + envIdx + "Decay",
          100.0f,
          600.0f,
          () -> synth.getEnv(envIdx).decay() * 1000.0f);
      assertSynthParamRoundTrip(
          project,
          "env" + envIdx + "Sustain",
          80.0f,
          35.0f,
          () -> synth.getEnv(envIdx).sustain() * 100.0f);
      assertSynthParamRoundTrip(
          project,
          "env" + envIdx + "Release",
          200.0f,
          1200.0f,
          () -> synth.getEnv(envIdx).release() * 1000.0f);
      assertSynthParamRoundTrip(
          project,
          "env" + envIdx + "Amount",
          50.0f,
          90.0f,
          () -> synth.getEnv(envIdx).amount() * 100.0f);
    }
  }

  private void assertSynthParamRoundTrip(
      ProjectModel project,
      String paramName,
      float oldVal,
      float newVal,
      java.util.function.Supplier<Number> getter) {
    var c = new Consequence.SynthParamConsequence(project, 0, paramName, oldVal, newVal, 0L);
    c.redo();
    assertEquals(newVal, getter.get().floatValue(), 0.05f, "redo failed for " + paramName);
    assertEquals(
        newVal,
        Consequence.SynthParamConsequence.readCurrent(project, 0, paramName),
        0.05f,
        "readCurrent after redo failed for " + paramName);
    c.undo();
    assertEquals(oldVal, getter.get().floatValue(), 0.05f, "undo failed for " + paramName);
    assertEquals(
        oldVal,
        Consequence.SynthParamConsequence.readCurrent(project, 0, paramName),
        0.05f,
        "readCurrent after undo failed for " + paramName);
  }

  @Test
  void everyApplyLabelHasMatchingReadCurrentInverseAndBoundedPitchCents() {
    ProjectModel project = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("InverseTest");
    project.addTrack(synth);

    record CaseSpec(String label, float value) {}

    List<CaseSpec> specs = new ArrayList<>();
    // Core slider params
    specs.add(new CaseSpec("unisonDetune", 24.0f));
    specs.add(new CaseSpec("unisonSpread", 35.0f));
    specs.add(new CaseSpec("waveIndex", 450.0f));
    specs.add(new CaseSpec("lpfCutoff", 60.0f));
    specs.add(new CaseSpec("lpfResonance", 42.0f));
    specs.add(new CaseSpec("filterDrive", 75.0f));
    specs.add(new CaseSpec("fmRatio", 200.0f));
    specs.add(new CaseSpec("fmAmount", 55.0f));
    specs.add(new CaseSpec("carrier1Fb", 30.0f));
    specs.add(new CaseSpec("carrier2Fb", 40.0f));
    specs.add(new CaseSpec("mod1Fb", 25.0f));
    specs.add(new CaseSpec("mod2Amt", 65.0f));
    specs.add(new CaseSpec("mod2Fb", 15.0f));
    specs.add(new CaseSpec("noiseVol", 20.0f));
    specs.add(new CaseSpec("oscMix", 70.0f));
    specs.add(new CaseSpec("portamento", 33.0f));
    specs.add(new CaseSpec("osc1Pitch", -350.0f));
    specs.add(new CaseSpec("osc2Pitch", 170.0f));
    specs.add(new CaseSpec("mod1Pitch", 170.0f));
    specs.add(new CaseSpec("mod2Pitch", -170.0f));
    specs.add(new CaseSpec("osc1TsAmt", 40.0f));
    specs.add(new CaseSpec("osc1Cents", -18.0f));
    specs.add(new CaseSpec("osc2TsAmt", 60.0f));
    specs.add(new CaseSpec("osc2Cents", 24.0f));
    specs.add(new CaseSpec("osc2Transpose", -7.0f));
    specs.add(new CaseSpec("stutterRate", 50.0f));
    specs.add(new CaseSpec("eqBass", 45.0f));
    specs.add(new CaseSpec("eqTreble", -35.0f));
    specs.add(new CaseSpec("hpfCutoff", 25.0f));
    specs.add(new CaseSpec("hpfResonance", 55.0f));
    specs.add(new CaseSpec("hpfMorph", 20.0f));
    specs.add(new CaseSpec("hpfFm", 40.0f));
    specs.add(new CaseSpec("compAttack", 30.0f));
    specs.add(new CaseSpec("compRelease", 60.0f));
    specs.add(new CaseSpec("compRatio", 48.0f));
    specs.add(new CaseSpec("compBlend", 75.0f));
    specs.add(new CaseSpec("compressorShape", 65.0f));
    specs.add(new CaseSpec("compSidechainHpf", 25.0f));
    specs.add(new CaseSpec("modFxRate", 35.0f));
    specs.add(new CaseSpec("modFxDepth", 80.0f));
    specs.add(new CaseSpec("modFxFeedback", 50.0f));
    specs.add(new CaseSpec("modFxOffset", 40.0f));
    specs.add(new CaseSpec("reverbSend", 65.0f));
    specs.add(new CaseSpec("delaySend", 45.0f));
    // Arp params
    specs.add(new CaseSpec("arpRate", 25.0f));
    specs.add(new CaseSpec("arpGate", 60.0f));
    specs.add(new CaseSpec("arpStepRepeat", 3.0f));
    specs.add(new CaseSpec("arpRhythm", 12.0f));
    specs.add(new CaseSpec("arpSeqLength", 8.0f));
    specs.add(new CaseSpec("arpOctaveSpread", 40.0f));
    specs.add(new CaseSpec("arpGateSpread", 30.0f));
    specs.add(new CaseSpec("arpVelSpread", 50.0f));
    specs.add(new CaseSpec("arpRatchet", 2.0f));
    specs.add(new CaseSpec("arpNoteProb", 85.0f));
    specs.add(new CaseSpec("arpChordPoly", 3.0f));
    specs.add(new CaseSpec("arpChordProb", 70.0f));
    specs.add(new CaseSpec("arpChordType", 2.0f));
    specs.add(new CaseSpec("arpNumOctaves", 3.0f));
    specs.add(new CaseSpec("arpKitArp", 1.0f));
    specs.add(new CaseSpec("arpRandomizerLock", 1.0f));
    // Gold knob params
    specs.add(new CaseSpec("goldVolume", 0.72f));
    specs.add(new CaseSpec("goldPan", -0.35f));
    specs.add(new CaseSpec("goldLpfCutoff", 3500.0f));
    specs.add(new CaseSpec("goldLpfResonance", 0.62f));
    specs.add(new CaseSpec("goldEnv0Attack", 0.15f));
    specs.add(new CaseSpec("goldEnv0Release", 0.85f));
    specs.add(new CaseSpec("goldDelaySyncLevel", 7.0f));
    specs.add(new CaseSpec("goldDelayFeedback", 0.65f));
    specs.add(new CaseSpec("goldReverbSend", 0.42f));
    specs.add(new CaseSpec("goldHpfCutoff", 400.0f));
    specs.add(new CaseSpec("goldLfo0Rate", 4.5f));
    specs.add(new CaseSpec("goldLfo0Depth", 0.55f));
    specs.add(new CaseSpec("goldArpRate", 0.68f));
    specs.add(new CaseSpec("goldPortamento", 0.30f));
    specs.add(new CaseSpec("goldOscMix", 0.48f));
    specs.add(new CaseSpec("goldBitcrusher", 5.0f));
    // Envelope sliders (env0..3)
    for (int e = 0; e < 4; e++) {
      specs.add(new CaseSpec("env" + e + "Attack", 120.0f));
      specs.add(new CaseSpec("env" + e + "Decay", 350.0f));
      specs.add(new CaseSpec("env" + e + "Sustain", 65.0f));
      specs.add(new CaseSpec("env" + e + "Release", 900.0f));
      specs.add(new CaseSpec("env" + e + "Amount", 75.0f));
    }

    for (CaseSpec spec : specs) {
      var c =
          new Consequence.SynthParamConsequence(project, 0, spec.label(), 0.0f, spec.value(), 0L);
      c.redo();
      float roundTripped = Consequence.SynthParamConsequence.readCurrent(project, 0, spec.label());
      assertEquals(
          spec.value(),
          roundTripped,
          0.01f,
          "readCurrent(apply(v)) must match v for '" + spec.label() + "'");
    }

    // Verify 170 cents splits into transpose=2, cents=-30 (within -50..+50).
    new Consequence.SynthParamConsequence(project, 0, "osc2Pitch", 0.0f, 170.0f, 0L).redo();
    assertEquals(2, synth.getOsc2Transpose());
    assertEquals(-30, synth.getOsc2Cents());

    new Consequence.SynthParamConsequence(project, 0, "mod1Pitch", 0.0f, 170.0f, 0L).redo();
    assertEquals(2, synth.getModulator1Transpose());
    assertEquals(-30, synth.getModulator1Cents());

    new Consequence.SynthParamConsequence(project, 0, "mod2Pitch", 0.0f, -170.0f, 0L).redo();
    assertEquals(-2, synth.getModulator2Transpose());
    assertEquals(30, synth.getModulator2Cents());
  }

  @Test
  void scriptingEngineRoundTripsUnderCommaLocaleAndRestoresUndo(@TempDir Path tempDir)
      throws Exception {
    Locale prev = Locale.getDefault();
    Locale.setDefault(Locale.FRANCE);
    try {
      ProjectModel source = new ProjectModel();
      SynthTrackModel srcSynth = new SynthTrackModel("Synth");
      ClipModel srcClip = new ClipModel("Clip", 8, 16);
      srcSynth.addClip(srcClip);
      source.addTrack(srcSynth);

      ScriptingEngine recorder = new ScriptingEngine();
      recorder.startRecording();
      recorder.record(new Consequence.ProjectParamConsequence(source, "masterVolume", 0.8f, 0.55f));
      recorder.record(new Consequence.ProjectParamConsequence(source, "reverbWidth", 1.0f, 0.35f));
      recorder.record(
          new Consequence.SynthParamConsequence(source, 0, "modFxDepth", 0.0f, 65.0f, 1L));
      recorder.record(
          new Consequence.SynthParamConsequence(source, 0, "env1Decay", 100.0f, 750.0f, 2L));
      recorder.record(
          new Consequence.AutomationConsequence(source, 0, 0, "lpfFrequency", 3, -1.0f, 0.72f));
      recorder.record(
          new Consequence.AutomationConsequence(source, 0, 0, "volume", 5, 0.40f, 0.85f));
      recorder.stopRecording();

      File scriptFile = tempDir.resolve("comma_locale_macro.txt").toFile();
      recorder.saveScript(scriptFile);

      ProjectModel target = new ProjectModel();
      target.setMasterVolume(0.8f);
      target.setReverbWidth(0.9f);
      SynthTrackModel tgtSynth = new SynthTrackModel("Synth");
      tgtSynth.setModFxDepth(0.25f);
      EnvelopeModel env1 = tgtSynth.getEnv(1);
      tgtSynth.setEnv(
          1,
          new EnvelopeModel(
              env1.attack(), 0.4f, env1.sustain(), env1.release(), env1.target(), env1.amount()));
      ClipModel tgtClip = new ClipModel("Clip", 8, 16);
      tgtClip.setAutomation("volume", 5, 0.40f);
      tgtSynth.addClip(tgtClip);
      target.addTrack(tgtSynth);

      assertFalse(tgtClip.hasAutomation("lpfFrequency", 3));
      assertEquals(0.40f, tgtClip.getAutomationValue("volume", 5), 0.001f);

      ScriptingEngine player = new ScriptingEngine();
      List<Consequence> actions = player.loadAndExecuteScript(scriptFile, target);
      assertEquals(6, actions.size());

      // Verify redo state
      assertEquals(0.55f, target.getMasterVolume(), 0.001f);
      assertEquals(0.35f, target.getReverbWidth(), 0.001f);
      assertEquals(0.65f, tgtSynth.getModFxDepth(), 0.001f);
      assertEquals(0.75f, tgtSynth.getEnv(1).decay(), 0.001f);
      assertTrue(tgtClip.hasAutomation("lpfFrequency", 3));
      assertEquals(0.72f, tgtClip.getAutomationValue("lpfFrequency", 3), 0.001f);
      assertEquals(0.85f, tgtClip.getAutomationValue("volume", 5), 0.001f);

      // Undo all in reverse order and verify every pre-script value is restored
      for (int i = actions.size() - 1; i >= 0; i--) {
        actions.get(i).undo();
      }
      assertEquals(0.8f, target.getMasterVolume(), 0.001f);
      assertEquals(0.9f, target.getReverbWidth(), 0.001f);
      assertEquals(0.25f, tgtSynth.getModFxDepth(), 0.001f);
      assertEquals(0.4f, tgtSynth.getEnv(1).decay(), 0.001f);
      assertFalse(tgtClip.hasAutomation("lpfFrequency", 3));
      assertEquals(-1.0f, tgtClip.getAutomationValue("lpfFrequency", 3), 0.001f);
      assertEquals(0.40f, tgtClip.getAutomationValue("volume", 5), 0.001f);
    } finally {
      Locale.setDefault(prev);
    }
  }

  @Test
  void dspTapCodecReassemblesOutOfOrderChunksWhenTailChunkIsFirst() {
    int total = 430;
    int per = 180;
    int[] expected = new int[total];
    for (int i = 0; i < total; i++) {
      expected[i] = (i * 1103515245) + 12345;
    }

    List<DspTapCodec.Chunk> chunks = new ArrayList<>();
    for (int idx = 0; idx * per < total; idx++) {
      int base = idx * per;
      int n = Math.min(per, total - base);
      int[] seg = new int[n];
      System.arraycopy(expected, base, seg, 0, n);
      chunks.add(DspTapCodec.decode(DspTapCodec.encodeReply(total, idx, seg)));
    }
    // Reverse so the short 70-sample tail chunk (idx=2) comes first.
    Collections.reverse(chunks);
    assertEquals(70, chunks.get(0).nSamples());

    assertArrayEquals(expected, DspTapCodec.reassemble(chunks));
  }

  @Test
  void dspTapCodecRejectsIncompleteChunkSet() {
    int total = 360;
    int per = 180;
    int[] seg0 = new int[per];
    for (int i = 0; i < per; i++) seg0[i] = i + 1;
    DspTapCodec.Chunk c0 = DspTapCodec.decode(DspTapCodec.encodeReply(total, 0, seg0));

    assertThrows(IllegalStateException.class, () -> DspTapCodec.reassemble(List.of(c0)));
  }
}
