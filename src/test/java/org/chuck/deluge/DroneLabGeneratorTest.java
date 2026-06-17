package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.core.ChuckVM;
import org.chuck.deluge.engine.DroneLabGenerator;
import org.chuck.deluge.firmware.engine.FirmwareSound;
import org.chuck.deluge.model.EnvelopeModel;
import org.chuck.deluge.model.LfoModel;
import org.chuck.deluge.model.LfoType;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class DroneLabGeneratorTest {

  private ChuckVM vm;
  private BridgeContract bridge;
  private ProjectModel project;
  private SynthTrackModel track;
  private int trackIndex;

  @BeforeEach
  void setUp() {
    vm = new ChuckVM(44100, 2);
    bridge = new BridgeContract();
    bridge.register(vm);

    project = new ProjectModel();
    track = new SynthTrackModel("Test Synth");
    project.getTracks().add(track);
    trackIndex = 0;
  }

  @AfterEach
  void tearDown() {
    if (vm != null) {
      vm.shutdown();
    }
  }

  @Test
  void testSubtractiveDroneGenerationAndSequencing() {
    // Generate subtractive drone
    DroneLabGenerator.generateDrone(track, project, bridge, trackIndex, false, false);

    // Assert Synthesizer voice configurations
    assertEquals(SynthTrackModel.PolyphonyMode.POLY, track.getPolyphony());
    assertEquals(8, track.getMaxVoiceCount());
    assertEquals(0, track.getSynthMode()); // Subtractive

    assertEquals("SAW", track.getOsc1Type());
    assertEquals("SAW", track.getOsc2Type());
    assertEquals(12, track.getOsc2Transpose());
    assertEquals(16, track.getOsc2Cents());

    assertEquals(4, track.getUnisonNum());
    assertEquals(22, track.getUnisonDetune(), 1e-4);
    assertEquals(60, track.getUnisonStereoSpread(), 1e-4);

    // Assert Envelopes
    EnvelopeModel env0 = track.getEnv(0);
    assertEquals(2.2f, env0.attack(), 1e-4);
    assertEquals(4.0f, env0.release(), 1e-4);

    EnvelopeModel env1 = track.getEnv(1);
    assertEquals(4.5f, env1.attack(), 1e-4);
    assertEquals("FILTER", env1.target());

    // Assert Modulations
    LfoModel lfo1 = track.getLfo(0);
    assertEquals(0.03f, lfo1.rateHz(), 1e-4);
    assertEquals(LfoType.SINE, lfo1.waveform());
    assertEquals("LPF", lfo1.target());

    LfoModel lfo2 = track.getLfo(1);
    assertEquals(LfoType.RANDOM_WALK, lfo2.waveform());
    assertEquals("PAN", lfo2.target());

    // Assert microtuning custom scale (Just Intonation)
    assertFalse(project.isEqualTemperament());
    int[] cents = project.getCentAdjustForNotesInTemperament();
    assertEquals(0, cents[0]); // C
    assertEquals(-12, cents[1]); // Db
    assertEquals(-14, cents[4]); // E (pure major third!)
    assertEquals(2, cents[7]); // G (pure perfect fifth!)

    // Assert sequencer tied note step grid sequencing
    assertTrue(bridge.getStep(trackIndex, 0));
    assertEquals(36, bridge.getPitch(trackIndex, 0)); // C2
    assertEquals(192.0, bridge.getGate(trackIndex, 0), 1e-4); // Spans full sequence
  }

  @Test
  void testFmDroneGeneration() {
    // Generate FM drone (Major)
    DroneLabGenerator.generateDrone(track, project, bridge, trackIndex, true, false);

    assertEquals(1, track.getSynthMode()); // FM
    assertEquals(1.414f, track.getFmRatio(), 1e-4); // Square root of 2 modulator
    assertEquals(1.618f, track.getFmRatio2(), 1e-4); // Golden Ratio modulator

    assertEquals(2, track.getUnisonNum());
    assertEquals(12, track.getUnisonDetune(), 1e-4);
  }

  @Test
  void testRealtimeMacroSweeping() {
    // Create a physical FirmwareSound instance representing this track
    FirmwareSound sound = new FirmwareSound();
    sound.syncParamsToFw2();

    // ── Test Subtractive Macro Sweeps ──
    DroneLabGenerator.generateDrone(track, project, bridge, trackIndex, false, false);

    // 1. Sweep macros to minimum
    DroneLabGenerator.applyMacros(track, sound, 0.0, 0.0, 0.0, 0.0);
    assertEquals(5, track.getOsc2Cents());
    assertEquals(0.0f, track.getBitCrush(), 1e-4);
    assertEquals(0.15f, track.getReverbSend(), 1e-4);
    assertEquals(0.02f, track.getNoiseVol(), 1e-4);

    // 2. Sweep macros to maximum
    DroneLabGenerator.applyMacros(track, sound, 1.0, 1.0, 1.0, 1.0);
    assertEquals(50, track.getOsc2Cents()); // Clamped to physical limit
    assertEquals(0.35f, track.getBitCrush(), 1e-4);
    assertEquals(0.80f, track.getReverbSend(), 1e-4);
    assertEquals(0.18f, track.getNoiseVol(), 1e-4);

    // ── Test FM Macro Sweeps ──
    DroneLabGenerator.generateDrone(track, project, bridge, trackIndex, true, false);

    // Sweep FM to maximum
    DroneLabGenerator.applyMacros(track, sound, 1.0, 1.0, 1.0, 1.0);
    assertEquals(45, track.getModulator1Cents());
  }
}
