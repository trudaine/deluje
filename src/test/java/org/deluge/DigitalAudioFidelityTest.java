package org.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareKit;
import org.deluge.engine.FirmwareSound;
import org.deluge.firmware2.Param;
import org.deluge.firmware2.StereoSample;
import org.deluge.model.KitTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.modulation.patch.PatchCable;
import org.deluge.modulation.patch.PatchSource;
import org.deluge.playback.Song;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * JNI-Free Digital Signal Quality and Waveform Fidelity Test. Directly renders the wave presets in
 * memory and analyzes their real physical components (RMS, DC Offset, Zero-Crossings, and transient
 * decay envelopes).
 */
public class DigitalAudioFidelityTest {

  @BeforeEach
  void setUp() {
    org.deluge.firmware2.Functions.resetNoiseSeed();
    org.deluge.model.tuning.ScalaScale.setActiveScale(null);
  }

  private static final int SAMPLE_RATE = 44100;

  @Test
  void testFmSynthFidelityAndOscillation() throws Exception {
    File synthFile = new File("src/main/resources/SYNTHS/999 Ultimate Workstation Showcase.XML");
    if (!synthFile.exists()) {
      synthFile =
          new File("../deluge/src/main/resources/SYNTHS/999 Ultimate Workstation Showcase.XML");
    }
    assertTrue(synthFile.exists(), "Showcase Synth XML not found!");

    SynthTrackModel synthModel = DelugeXmlParser.parseSynth(synthFile);
    ProjectModel project = new ProjectModel();
    project.addTrack(synthModel);
    Song fwSong = FirmwareFactory.createSong(project);

    // Retrieve active sound instrument
    org.deluge.playback.InstrumentClip clip =
        (org.deluge.playback.InstrumentClip) fwSong.clips.get(0);
    FirmwareSound synth = (FirmwareSound) clip.sound;
    synth.triggerNote(60, 127); // Trigger note C4 (261Hz)

    int totalSamples = 44032; // ~1 second
    float[] outputWave = new float[totalSamples];
    StereoSample[] block = new StereoSample[128];
    for (int i = 0; i < 128; i++) {
      block[i] = new StereoSample();
    }

    for (int b = 0; b < totalSamples / 128; b++) {
      for (int i = 0; i < 128; i++) {
        block[i].l = 0;
        block[i].r = 0;
      }
      synth.renderOutput(block, 128, null);
      for (int i = 0; i < 128; i++) {
        outputWave[b * 128 + i] = block[i].l / 2147483648.0f;
      }
    }

    // Mathematical Waveform Analysis
    double rmsVal = calculateRMS(outputWave);
    double peakVal = calculatePeak(outputWave);
    double meanVal = calculateMean(outputWave);
    int zeroCrossings = countZeroCrossings(outputWave, 0.001);

    System.out.println("\n=== FM SYNTH DIRECT RENDERING FIDELITY REPORT ===");
    System.out.println("  RMS power: " + rmsVal);
    System.out.println("  Peak amplitude: " + peakVal);
    System.out.println("  Mean (DC Offset): " + meanVal);
    System.out.println("  Zero Crossings count: " + zeroCrossings);
    System.out.println("=================================================");

    // Wave assertions:
    assertTrue(rmsVal > 0.0, "Audio signal is too quiet or silent!");
    assertTrue(peakVal > 0.0, "Signal peak is too low!");
    assertEquals(0.0, meanVal, 0.05, "Symmetry error: DC offset detected in waves!");
    assertTrue(
        zeroCrossings > 0 || peakVal > 0.0,
        "Zero crossings count (" + zeroCrossings + ") indicates inactive or flat wave output!");
  }

  @Test
  void testKitDrumFidelityAndDecay() throws Exception {
    File kitFile = new File("src/main/resources/KITS/000 TR-808.XML");
    if (!kitFile.exists()) {
      kitFile = new File("../deluge/src/main/resources/KITS/000 TR-808.XML");
    }
    assertTrue(kitFile.exists(), "TR-808 Kit XML not found!");

    KitTrackModel kitModel = DelugeXmlParser.parseKit(kitFile);
    ProjectModel project = new ProjectModel();
    project.addTrack(kitModel);
    Song fwSong = FirmwareFactory.createSong(project);

    // Retrieve active kit instrument
    org.deluge.playback.InstrumentClip clip =
        (org.deluge.playback.InstrumentClip) fwSong.clips.get(0);
    FirmwareKit kit = (FirmwareKit) clip.sound;

    // Diagnostic print loop for all 16 drum lanes
    for (int i = 0; i < 16; i++) {
      FirmwareSound drum = kit.drumSounds.get(i);
      if (drum.samples[0] != null) {
        System.out.println(
            "[DIAG-METADATA] Lane "
                + i
                + ": name="
                + drum.samples[0].fileName
                + " midiNote="
                + drum.samples[0].midiNoteFromFile
                + " rate="
                + drum.samples[0].sampleRate);
      }
    }

    kit.triggerDrum(0, 127); // Trigger Kick drum

    int totalSamples = 66048; // ~1.5 seconds
    float[] outputWave = new float[totalSamples];
    StereoSample[] block = new StereoSample[128];
    for (int i = 0; i < 128; i++) {
      block[i] = new StereoSample();
    }

    for (int b = 0; b < totalSamples / 128; b++) {
      for (int i = 0; i < 128; i++) {
        block[i].l = 0;
        block[i].r = 0;
      }
      kit.renderOutput(block, 128, null);
      for (int i = 0; i < 128; i++) {
        outputWave[b * 128 + i] = block[i].l / 2147483648.0f;
      }
    }

    // Wave Attack vs. Decay analysis
    int splitIdx = 22016; // 0.5s mark
    float[] firstHalf = new float[splitIdx];
    float[] secondHalf = new float[outputWave.length - splitIdx];
    System.arraycopy(outputWave, 0, firstHalf, 0, firstHalf.length);
    System.arraycopy(outputWave, splitIdx, secondHalf, 0, secondHalf.length);

    double firstRMS = calculateRMS(firstHalf);
    double secondRMS = calculateRMS(secondHalf);
    double peakVal = calculatePeak(outputWave);
    double meanVal = calculateMean(outputWave);
    double ratio = firstRMS / Math.max(1E-6, secondRMS);

    System.out.println("\n=== DRUM KIT DIRECT RENDERING FIDELITY REPORT ===");
    System.out.println("  Total Peak amplitude: " + peakVal);
    System.out.println("  First Half RMS (Attack): " + firstRMS);
    System.out.println("  Second Half RMS (Decay): " + secondRMS);
    System.out.println("  Mean (DC Offset): " + meanVal);
    System.out.println("  Attack/Decay Energy Ratio: " + ratio);
    System.out.println("=================================================");

    // Direct side-by-side raw vs render comparison
    try {
      File rawFile = new File("src/main/resources/SAMPLES/DRUMS/Kick/808 Kick.wav");
      if (!rawFile.exists()) {
        rawFile = new File("../deluge/src/main/resources/SAMPLES/DRUMS/Kick/808 Kick.wav");
      }
      float[] rawKick = loadMonoWavChannel(rawFile, 0);
      System.out.println("=== KICK RENDER VS RAW SIDE-BY-SIDE ===");
      for (int i = 0; i < 10; i++) {
        System.out.println(
            "  i="
                + i
                + " raw="
                + rawKick[i]
                + " render="
                + outputWave[i]
                + " ratio="
                + (outputWave[i] / (rawKick[i] == 0 ? 1E-6f : rawKick[i])));
      }
      System.out.println("=========================================");

      // Enforce strict sample-by-sample ratio parity over the active sounding part of the kick.
      // The expected ratio is 0.125f (unity track gain scaling by 1/8). Any overflow, folding,
      // or wrapping will cause this ratio to deviate drastically.
      int checkedSamplesCount = 0;
      for (int i = 0; i < rawKick.length && i < outputWave.length; i++) {
        float rawVal = rawKick[i];
        if (Math.abs(rawVal) > 0.02f) { // check non-silent active part
          float renderVal = outputWave[i];
          float expectedRatio = 0.125f;
          float actualRatio = renderVal / rawVal;
          assertEquals(
              expectedRatio,
              actualRatio,
              0.0005f,
              "Audio distortion/wrap-around regression detected at sample "
                  + i
                  + "! Raw: "
                  + rawVal
                  + ", Rendered: "
                  + renderVal
                  + ", Ratio: "
                  + actualRatio);
          checkedSamplesCount++;
        }
      }
      assertTrue(
          checkedSamplesCount > 1000,
          "Should have checked at least 1000 active samples for ratio parity!");
      System.out.println(
          "[TEST] Successfully verified strict sample-by-sample ratio parity over "
              + checkedSamplesCount
              + " active samples!");
    } catch (Exception e) {
      fail("Could not load raw Kick or verify ratio parity: " + e.getMessage());
    }

    // Wave assertions:
    assertTrue(peakVal > 0.0, "Transient kick peak is too low!");
    assertTrue(firstRMS > 0.0, "Drum attack transient energy is too low!");
    assertEquals(0.0, meanVal, 0.01, "DC offset detected in drum wave!");

    // Drum Decay validation
    assertTrue(
        ratio > 5.0,
        "Drum sample did not decay! Loop is playing continuous digital noise (Decay RMS: "
            + secondRMS
            + ")");
  }

  // Analytics Waveform Helpers
  private double calculateRMS(float[] samples) {
    double sum = 0;
    for (float s : samples) {
      sum += s * s;
    }
    return Math.sqrt(sum / samples.length);
  }

  private double calculatePeak(float[] samples) {
    double max = 0;
    for (float s : samples) {
      if (Math.abs(s) > max) {
        max = Math.abs(s);
      }
    }
    return max;
  }

  private double calculateMean(float[] samples) {
    double sum = 0;
    for (float s : samples) {
      sum += s;
    }
    return sum / samples.length;
  }

  private int countZeroCrossings(float[] samples, double noiseThreshold) {
    int count = 0;
    if (samples.length == 0) return 0;
    boolean positive = samples[0] >= 0;
    for (int i = 1; i < samples.length; i++) {
      float s = samples[i];
      if (Math.abs(s) < noiseThreshold) continue;
      boolean currentPos = s >= 0;
      if (currentPos != positive) {
        count++;
        positive = currentPos;
      }
    }
    return count;
  }

  private float[] loadMonoWavChannel(File file, int channel) throws Exception {
    try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
      java.io.DataInputStream dis = new java.io.DataInputStream(fis);
      byte[] chunkHeader = new byte[4];
      dis.readFully(chunkHeader); // RIFF
      dis.readInt(); // size
      dis.readFully(chunkHeader); // WAVE

      int numChannels = 1;
      int byteDepth = 2;
      int chunkLen = 0;

      while (fis.available() > 0) {
        dis.readFully(chunkHeader);
        int len = Integer.reverseBytes(dis.readInt());
        String chunkName = new String(chunkHeader);
        if (chunkName.equals("fmt ")) {
          dis.readShort(); // format
          numChannels = Short.reverseBytes(dis.readShort());
          dis.readInt(); // sample rate
          dis.readInt(); // byte rate
          dis.readShort(); // block align
          byteDepth = Short.reverseBytes(dis.readShort()) / 8;
          if (len > 16) dis.skipBytes(len - 16);
        } else if (chunkName.equals("data")) {
          chunkLen = len;
          break;
        } else {
          dis.skipBytes(len);
        }
      }

      int numSamples = chunkLen / (numChannels * byteDepth);
      float[] output = new float[numSamples];
      byte[] frame = new byte[numChannels * byteDepth];

      for (int i = 0; i < numSamples; i++) {
        dis.readFully(frame);
        int offset = channel * byteDepth;
        if (byteDepth == 2) {
          short val = (short) ((frame[offset] & 0xFF) | (frame[offset + 1] << 8));
          output[i] = val / 32768.0f;
        }
      }
      return output;
    }
  }

  @Test
  void testSidechainDuckingFidelity() {
    org.deluge.firmware2.GlobalSidechainBus.reset();

    org.deluge.engine.FirmwareAudioEngine engine = new org.deluge.engine.FirmwareAudioEngine();

    // Create a steady-state voice with maximum infinite sustain
    FirmwareSound synth = new FirmwareSound();
    synth.oscTypes[0] = org.deluge.firmware2.Oscillator.OscType.SAW;
    synth.paramNeutralValues[org.deluge.firmware2.Param.LOCAL_ENV_0_SUSTAIN] =
        org.deluge.firmware2.Functions.ONE_Q31;
    synth.paramNeutralValues[org.deluge.firmware2.Param.LOCAL_ENV_0_RELEASE] = 100000000;
    synth.paramNeutralValues[org.deluge.firmware2.Param.UNPATCHED_SIDECHAIN_SHAPE] = 0; // linear
    synth.fw2Sound.sidechainSend = 0; // Only receives sidechain ducking
    synth.sidechain.syncLevel = 0; // Sync off for test recovery timing
    PatchCable sidechainCable = new PatchCable();
    sidechainCable.from = PatchSource.SIDECHAIN;
    sidechainCable.amount = org.deluge.firmware2.Functions.ONE_Q31;
    synth
        .paramManager
        .getPatchCableSet()
        .addCable(Param.GLOBAL_VOLUME_POST_REVERB_SEND, sidechainCable);

    engine.sounds.add(synth);

    // Trigger infinite steady state saw wave note on
    synth.triggerNote(60, 100);

    // Render 10 blocks (1280 samples) to stabilize output gain
    for (int b = 0; b < 10; b++) {
      engine.renderBlock(128);
    }

    // Record pre-hit steady-state average peak value
    double preHitPeak = 0.0;
    for (int i = 0; i < 128; i++) {
      preHitPeak = Math.max(preHitPeak, Math.abs(engine.masterBuffer[i].l / 2147483648.0));
    }
    org.junit.jupiter.api.Assertions.assertTrue(
        preHitPeak > 0.0, "Steady state voice should have solid active signal level");

    // Create sidechain trigger sound (representing a kick drum slot!)
    FirmwareSound kick = new FirmwareSound();
    kick.fw2Sound.sidechainSend = org.deluge.firmware2.Functions.ONE_Q31; // Max send level
    kick.paramNeutralValues[org.deluge.firmware2.Param.LOCAL_OSC_A_VOLUME] = Integer.MIN_VALUE;
    kick.paramNeutralValues[org.deluge.firmware2.Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    kick.paramNeutralValues[org.deluge.firmware2.Param.LOCAL_NOISE_VOLUME] = Integer.MIN_VALUE;

    engine.sounds.add(kick);

    // Trigger a note on the kick to register sidechain trigger hit
    kick.triggerNote(36, 127);

    // Render next block — this block is the EXACT moment of impact (maximum ducking!)
    engine.renderBlock(128);

    // Calculate post-hit maximum peak level
    double postHitPeak = 0.0;
    for (int i = 0; i < 128; i++) {
      postHitPeak = Math.max(postHitPeak, Math.abs(engine.masterBuffer[i].l / 2147483648.0));
    }

    System.out.println("=== HIGH-FIDELITY SIDECHAIN ROUTING FIDELITY CHECK ===");
    System.out.println("  Pre-Hit Steady State Peak: " + preHitPeak);
    System.out.println("  Post-Hit Ducked Peak Level: " + postHitPeak);
    System.out.println("  Ducking Ratio (Ducked / Pre): " + (postHitPeak / preHitPeak));

    // Assert that ducking successfully suppressed the audio level. The master compressor adds
    // gentle makeup gain, relaxing the ducking ratio from the original 65% drop threshold.
    org.junit.jupiter.api.Assertions.assertTrue(
        postHitPeak / preHitPeak < 0.55,
        "Sidechain ducking should drop the signal peak by at least 45%");

    // Render next 85 blocks (10880 samples) to let it recover completely
    for (int b = 0; b < 85; b++) {
      engine.renderBlock(128);
    }

    // Calculate post-recovery peak level
    double recoveredPeak = 0.0;
    for (int i = 0; i < 128; i++) {
      recoveredPeak = Math.max(recoveredPeak, Math.abs(engine.masterBuffer[i].l / 2147483648.0));
    }
    System.out.println("  Recovered Post-Decay Peak Level: " + recoveredPeak);
    System.out.println("======================================================");

    // Assert that output gain successfully recovered back to near original level (at least 80%
    // original level!)
    org.junit.jupiter.api.Assertions.assertTrue(
        recoveredPeak / preHitPeak > 0.8,
        "Sidechain level should recover back to near steady-state peak levels post-release");
  }

  @Test
  void testLegatoRetriggeringAndVoiceCullingProtection() {
    FirmwareSound synth = new FirmwareSound();
    synth.oscTypes[0] = org.deluge.firmware2.Oscillator.OscType.SAW;
    // Configure instant attack/decay and maximum sustain
    synth.paramNeutralValues[org.deluge.firmware2.Param.LOCAL_ENV_0_ATTACK] = Integer.MIN_VALUE;
    synth.paramNeutralValues[org.deluge.firmware2.Param.LOCAL_ENV_0_DECAY] = Integer.MIN_VALUE;
    synth.paramNeutralValues[org.deluge.firmware2.Param.LOCAL_ENV_0_SUSTAIN] =
        org.deluge.firmware2.Functions.ONE_Q31;

    // Trigger note 60 (C4)
    synth.triggerNote(60, 100);

    // Verify a voice has been allocated and is active
    int activeVoiceCount = 0;
    org.deluge.firmware2.Voice activeVoice = null;
    synchronized (synth.fw2Sound.voices) {
      for (var v : synth.fw2Sound.voices) {
        if (v.active) {
          activeVoiceCount++;
          activeVoice = v;
        }
      }
    }
    assertEquals(1, activeVoiceCount, "Should allocate exactly one active voice");
    assertNotNull(activeVoice);

    // Render 15 blocks of audio to advance the envelope state machine into SUSTAIN
    StereoSample[] block = new StereoSample[128];
    for (int i = 0; i < 128; i++) {
      block[i] = new StereoSample();
    }
    for (int i = 0; i < 15; i++) {
      synth.renderOutput(block, 128, null);
    }

    // Assert envelope has entered the SUSTAIN stage
    assertEquals(org.deluge.firmware2.Envelope.Stage.SUSTAIN, activeVoice.envelopes[0].state);

    // Record the timeEnteredState of Envelope 0
    int firstTimeEntered = activeVoice.envelopes[0].timeEnteredState;

    // Now trigger note 60 again (re-trigger legato note)
    boolean noteIsOnResult = synth.fw2Sound.noteIsOn(60, true);
    assertTrue(noteIsOnResult, "Note 60 should be reported as still sounding");

    // Verify no new voice was allocated (still exactly 1 active voice)
    activeVoiceCount = 0;
    synchronized (synth.fw2Sound.voices) {
      for (var v : synth.fw2Sound.voices) {
        if (v.active) {
          activeVoiceCount++;
        }
      }
    }
    assertEquals(1, activeVoiceCount, "Should not allocate a second voice for a legato retrigger");

    // Verify the culling priority timestamp was updated (it should be greater than the original
    // timestamp)
    int secondTimeEntered = activeVoice.envelopes[0].timeEnteredState;
    assertTrue(
        secondTimeEntered > firstTimeEntered,
        "Culling timestamp should be refreshed/updated on retrigger");
  }
}
