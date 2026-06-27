package org.deluge;

import java.io.File;
import org.deluge.model.*;
import org.deluge.project.ProjectSerializer;
import org.junit.jupiter.api.Test;

/**
 * End-to-end DSP fidelity test that programmatically constructs, serializes, and renders a highly
 * complex song featuring the Modulation Matrix (Patch Cables), global Sidechain Compression, Master
 * Reverb, and Ping-Pong Delay.
 */
public class ModulationFidelityTest {

  @Test
  public void testModulationAndEffects() throws Exception {
    // 1. Create a new project model
    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);
    project.setMasterVolume(0.8f);

    // ── Global Sidechain Compressor Settings ──
    project.setSidechainAttack(0.000152f);
    project.setSidechainRelease(0.00000043f);
    project.setSongParamSidechainShape(0.75f); // Heavy 75% sidechain ducking depth!

    // ── Global Master Reverb Settings ──
    project.setReverbRoomSize(0.7f); // Lush, wide room
    project.setReverbDampening(0.3f);
    project.setReverbWidth(0.8f);

    // ── Global Master Delay Settings ──
    project.setDelayPingPong(1); // Stereo Ping-Pong enabled!
    project.setDelayAnalog(0);
    project.setDelaySyncLevel(6); // Synced to 1/8th notes
    project.setSongParamDelayRate(0.5f);
    project.setSongParamDelayFeedback(0.45f); // Substantial echo feedback

    // ==================================================================================
    // TRACK 0: WOBBLE BASS (LFO 1 -> LPF Cutoff modulation)
    // ==================================================================================
    SynthTrackModel bassTrack = new SynthTrackModel("Wobble Bass");
    bassTrack.setOsc1Type("SAW");
    bassTrack.setOsc2Type("NONE");
    bassTrack.setOscAVolume(0.85f);
    bassTrack.setVolume(0.7f);
    bassTrack.setLpfFreq(500.0f); // Low cutoff, to be swept by LFO
    bassTrack.setLpfRes(0.5f); // High resonance for squelchy wobble

    // Amp envelope (Env 0)
    bassTrack.setEnv(0, new EnvelopeModel(0.001f, 0.4f, 0.5f, 0.2f, "NONE", 0.0f));

    // LFO 1: Set rate to 4.0 Hz (fast wobble)
    LfoModel lfo1 = new LfoModel(4.0f, LfoType.SINE, 0.8f, "NONE", true, 0, 0);
    bassTrack.setLfo(0, lfo1);

    // Modulation Matrix: Patch LFO 1 to LPF Frequency
    bassTrack.getModulation().addPatchCable(new PatchCable("lfo1", "lpfFrequency", 0.6f));

    // Bassline clip: Long sustained notes to hear the wobble and sidechain pumping
    ClipModel bassClip = new ClipModel("Bass Clip", 3, 8);
    bassClip.setRowYNote(0, 48); // C3
    bassClip.setRowYNote(1, 51); // Eb3
    bassClip.setRowYNote(2, 53); // F3

    bassClip.setStep(0, 0, StepData.of(true, 0.9f, 36.0f, 1.0f, 48)); // Sustained C3
    bassClip.setStep(1, 4, StepData.of(true, 0.9f, 36.0f, 1.0f, 51)); // Sustained Eb3
    bassTrack.addClip(bassClip);
    project.addTrack(bassTrack);

    // ==================================================================================
    // TRACK 1: LASER LEAD (Env 2 -> Pitch & LFO 2 -> Stereo Pan)
    // ==================================================================================
    SynthTrackModel leadTrack = new SynthTrackModel("Laser Lead");
    leadTrack.setOsc1Type("SQUARE");
    leadTrack.setOsc2Type("NONE");
    leadTrack.setOscAVolume(0.6f);
    leadTrack.setVolume(0.55f);
    leadTrack.setLpfFreq(2500.0f);
    leadTrack.setLpfRes(0.2f);
    leadTrack.setReverbSend(0.4f); // Send heavily to master reverb
    leadTrack.setDelaySend(0.35f); // Send heavily to master ping-pong delay

    // Amp envelope (Env 0)
    leadTrack.setEnv(0, new EnvelopeModel(0.01f, 0.2f, 0.0f, 0.15f, "NONE", 0.0f));

    // Envelope 2: Tight decay shape for laser pitch modulation
    leadTrack.setEnv(1, new EnvelopeModel(0.001f, 0.15f, 0.0f, 0.15f, "NONE", 0.0f));

    // LFO 2: Set rate to 1.0 Hz (slow panning drift)
    LfoModel lfo2 = new LfoModel(1.0f, LfoType.SINE, 0.9f, "NONE", true, 0, 0);
    leadTrack.setLfo(1, lfo2);

    // Modulation Matrix:
    // 1. Env 2 modulates Pitch (creates tight downward laser "pew-pew" sweep!)
    leadTrack
        .getModulation()
        .addPatchCable(new PatchCable("envelope2", "pitch", 0.8f, PatchCable.Polarity.UNIPOLAR));
    // 2. LFO 2 modulates Pan (auto-pans slowly left-to-right!)
    leadTrack
        .getModulation()
        .addPatchCable(new PatchCable("lfo2", "pan", 1.0f, PatchCable.Polarity.BIPOLAR));

    // Lead melody clip (16 steps)
    ClipModel leadClip = new ClipModel("Lead Clip", 4, 16);
    leadClip.setRowYNote(0, 60); // C4
    leadClip.setRowYNote(1, 63); // Eb4
    leadClip.setRowYNote(2, 67); // G4
    leadClip.setRowYNote(3, 70); // Bb4

    leadClip.setStep(0, 0, StepData.of(true, 0.8f, 12.0f, 1.0f, 60));
    leadClip.setStep(1, 3, StepData.of(true, 0.8f, 12.0f, 1.0f, 63));
    leadClip.setStep(2, 6, StepData.of(true, 0.8f, 12.0f, 1.0f, 67));
    leadClip.setStep(3, 9, StepData.of(true, 0.8f, 12.0f, 1.0f, 70));
    leadTrack.addClip(leadClip);
    project.addTrack(leadTrack);

    // ==================================================================================
    // TRACK 2: SIDECHAIN TRIGGER KIT
    // ==================================================================================
    KitTrackModel kitTrack = new KitTrackModel("Synth Kit");
    kitTrack.setVolume(1.0f);

    // Kick: Pure Sine wave at full volume, feeding the sidechain compressor at 100%!
    SoundDrum kick = new SoundDrum("Kick");
    kick.setVolume(1.0f);
    kick.setSidechainSend(1.0f); // Triggers the global ducking compressor!
    kick.setOsc2Type("SINE");
    kick.setOscBVolume(1.0f);
    kick.setOscAVolume(0.0f);
    kick.setNoiseVolume(0.0f);
    kick.setAdsr(new EnvelopeModel(0.001f, 0.18f, 0.0f, 0.08f, "NONE", 0.0f));
    kitTrack.addDrum(kick);

    // Drum beat clip: Kick on beats 0, 4, 8, 12 (creates the four-on-the-floor pump!)
    ClipModel drumClip = new ClipModel("Drum Clip", 1, 16);
    drumClip.setRowYNote(0, 0);
    drumClip.setStep(0, 0, StepData.of(true, 0.95f, 12.0f, 1.0f, 0));
    drumClip.setStep(0, 4, StepData.of(true, 0.95f, 12.0f, 1.0f, 0));
    drumClip.setStep(0, 8, StepData.of(true, 0.95f, 12.0f, 1.0f, 0));
    drumClip.setStep(0, 12, StepData.of(true, 0.95f, 12.0f, 1.0f, 0));
    kitTrack.addClip(drumClip);
    project.addTrack(kitTrack);

    // ==================================================================================
    // ARRANGEMENT VIEW TIMELINE SCHEDULING
    // ==================================================================================
    // Play all three tracks in unison for 6 bars (2304 ticks)
    project.addArrangerClip(new ArrangerClip(0, bassClip, 0, 2304));
    project.addArrangerClip(new ArrangerClip(1, leadClip, 0, 2304));
    project.addArrangerClip(new ArrangerClip(2, drumClip, 0, 2304));

    // 2. Serialize to Deluge XML Song
    File xmlFile = new File("src/main/resources/SONGS/MODULATION_SONG.xml");
    ProjectSerializer.save(project, xmlFile);
    System.out.println(
        "[ModulationTest] Song XML successfully written to: " + xmlFile.getAbsolutePath());

    // 3. Render project to WAV using Deluge-Java emulator
    File wavFile = new File("target/JAVA_RENDERED_MODULATION_SONG.WAV");
    FidelityTestRunner.renderSongToWav(xmlFile, wavFile, 8.0);
    System.out.println(
        "[ModulationTest] Rendered WAV successfully written to: " + wavFile.getAbsolutePath());
  }
}
