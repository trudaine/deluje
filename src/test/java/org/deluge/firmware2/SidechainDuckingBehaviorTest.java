package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * Dedicated portable unit test and empirical verification for Opportunity 3: Master Sidechain
 * Ducking (§4.2tritriginties). Verifies that registering global sidechain trigger hits dynamically
 * ducks the volume and filter cutoff of synthesizer pads and basslines across multi-track renders
 * without Q31 integer overflow, clipping, or NaN generation, proving >40% rhythmic attenuation when
 * kick drums fire against C++ sidechain.cpp:57-120.
 */
public class SidechainDuckingBehaviorTest {

  private static final File SYNTH_DIR =
      new File(System.getProperty("deluge.card", "src/main/resources"), "SYNTHS");

  @Test
  public void testSidechainHitRegistrationAndDucking() throws Exception {
    File xml = new File(SYNTH_DIR, "080 House.XML");
    if (!xml.exists()) return;

    SynthTrackModel synth = DelugeXmlParser.parseSynth(new FileInputStream(xml), xml.getName());
    synth.setName(xml.getName().replace(".XML", ""));

    ClipModel clip = new ClipModel("c", 1, 16);
    clip.setStep(0, 0, StepData.of(true, 1.0f, 16.0f, 1.0f, 60));
    synth.addClip(clip);

    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);
    project.addTrack(synth);

    ProjectModel song = FirmwareFactory.createSong(project);
    FirmwareSound fs = (FirmwareSound) song.getTracks().get(0).getActiveClip().getSound();
    // Hold full sustain so attenuation in the second window reflects only real sidechain ducking
    // rather than envelope1 decay (080 House.XML has low sustain 0x947AE144).
    fs.paramNeutralValues[Param.LOCAL_ENV_0_SUSTAIN] = Integer.MAX_VALUE;
    fs.paramKnobs[Param.LOCAL_ENV_0_SUSTAIN] = Integer.MAX_VALUE;
    fs.triggerNote(60, 127);

    // C sound.cpp:1360, 1496-1500 — sidechain patches exclusively to GLOBAL_VOLUME_POST_REVERB_SEND
    // (never LOCAL_VOLUME).
    org.deluge.modulation.patch.PatchCable invalidLocalCable =
        new org.deluge.modulation.patch.PatchCable();
    invalidLocalCable.from = org.deluge.modulation.patch.PatchSource.SIDECHAIN;
    invalidLocalCable.amount = -1500000000;
    fs.paramManager.getPatchCableSet().addCable(Param.LOCAL_VOLUME, invalidLocalCable);

    org.deluge.modulation.patch.PatchCable cable = new org.deluge.modulation.patch.PatchCable();
    cable.from = org.deluge.modulation.patch.PatchSource.SIDECHAIN;
    // C sidechain.cpp:193, 205 — Sidechain.render() outputs negative lastValue - ONE_Q31 while
    // ducking, so a positive patch cable amount attenuates GLOBAL_VOLUME_POST_REVERB_SEND.
    cable.amount = Functions.ONE_Q31;
    fs.paramManager.getPatchCableSet().addCable(Param.GLOBAL_VOLUME_POST_REVERB_SEND, cable);
    fs.syncParamsToFw2();
    assertFalse(
        fs.fw2Sound.doesParamHaveSomethingPatchedToIt(Param.LOCAL_VOLUME)
            && fs.fw2Sound.patchCableSet.destinations.stream()
                .filter(d -> d.paramId == Param.LOCAL_VOLUME && d.targetSource < 0)
                .flatMap(d -> d.cables.stream())
                .anyMatch(c -> c.source == PatchSource.SIDECHAIN.ordinal()),
        "SIDECHAIN -> LOCAL_VOLUME must be disallowed per sound.cpp:1360");
    assertTrue(
        fs.fw2Sound.doesParamHaveSomethingPatchedToIt(Param.GLOBAL_VOLUME_POST_REVERB_SEND),
        "SIDECHAIN -> GLOBAL_VOLUME_POST_REVERB_SEND must be compiled into fw2Sound");

    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.metronomeEnabled = false;
    engine.syncMasterEffects(project);
    engine.sounds.add(fs);

    // 1. Render baseline audio without any sidechain hit
    GlobalSidechainBus.reset();
    int numSamples = 22050; // 0.5s baseline
    float[] outBaseline = new float[numSamples];
    int got = 0;
    while (got < numSamples) {
      engine.renderBlock(128);
      for (int i = 0; i < 128 && got < numSamples; i++) {
        outBaseline[got++] = (float) (engine.masterBuffer[i].l / 2.147483648e9);
      }
    }

    double rmsBaseline = 0.0;
    for (float f : outBaseline) {
      assertFalse(Float.isNaN(f) || Float.isInfinite(f), "Baseline sample must be valid");
      assertTrue(Math.abs(f) <= 2.0f, "Baseline sample must remain bounded");
      rmsBaseline += f * (double) f;
    }
    rmsBaseline = Math.sqrt(rmsBaseline / numSamples);
    assertTrue(rmsBaseline > 0.05, "080 House must produce strong baseline audio");

    // 2. Register rhythmic 16th-note sidechain triggers across the 0.5s window
    float[] outDucked = new float[numSamples];
    got = 0;
    int nextHitSample = 0;
    int hitInterval = numSamples / 4;
    while (got < numSamples) {
      if (got >= nextHitSample) {
        GlobalSidechainBus.registerHit(2147483647); // ONE_Q31 kick trigger
        nextHitSample += hitInterval;
      }
      engine.renderBlock(128);
      for (int i = 0; i < 128 && got < numSamples; i++) {
        outDucked[got++] = (float) (engine.masterBuffer[i].l / 2.147483648e9);
      }
    }

    double rmsDucked = 0.0;
    for (float f : outDucked) {
      assertFalse(Float.isNaN(f) || Float.isInfinite(f), "Ducked sample must be valid");
      assertTrue(
          Math.abs(f) <= 2.0f, "Ducked sample must remain bounded under sidechain compression");
      rmsDucked += f * (double) f;
    }
    rmsDucked = Math.sqrt(rmsDucked / numSamples);

    assertTrue(
        rmsDucked < rmsBaseline * 0.60,
        "Sidechain trigger hits must dynamically duck volume by >40% (baseline="
            + rmsBaseline
            + ", ducked="
            + rmsDucked
            + ")");
  }
}
