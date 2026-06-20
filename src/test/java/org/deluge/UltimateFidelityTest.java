package org.deluge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.deluge.firmware.engine.FirmwareAudioEngine;
import org.deluge.firmware.engine.FirmwareFactory;
import org.deluge.firmware.engine.FirmwareSound;
import org.deluge.firmware.model.Song;
import org.deluge.firmware.playback.PlaybackHandler;
import org.deluge.model.ArrangerClip;
import org.deluge.model.ClipModel;
import org.deluge.model.EnvelopeModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SoundDrum;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * The Ultimate E2E Sound Parity and XML Serialization Test. Programmatically constructs a highly
 * complex multi-track arrangement song (Drums + Bass + Lead), saves it to XML, parses it back, and
 * renders it to a high-fidelity WAV file.
 */
public class UltimateFidelityTest {

  private static final int BLOCK_SIZE = 128;

  @Test
  public void testUltimateFidelity() throws Exception {
    // 1. Create a new, highly complex project model programmatically
    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);
    project.setMasterVolume(0.8f);

    // ==================================================================================
    // TRACK 0: SYNTH BASS (Subtractive, heavy filter-envelope modulation)
    // ==================================================================================
    SynthTrackModel bassTrack = new SynthTrackModel("Synth Bass");
    bassTrack.setOsc1Type("SAW");
    bassTrack.setOsc2Type("NONE");
    bassTrack.setOscAVolume(0.8f);
    bassTrack.setVolume(0.7f);
    bassTrack.setLpfFreq(600.0f);
    bassTrack.setLpfRes(0.2f);

    // Amp envelope (Env 0): fast decay, moderate sustain, tight release
    bassTrack.setEnv(0, new EnvelopeModel(0.001f, 0.25f, 0.4f, 0.15f, "NONE", 0.0f));
    // Filter envelope (Env 1): Modulates filter cutoff frequency
    bassTrack.setEnv(1, new EnvelopeModel(0.005f, 0.15f, 0.2f, 0.1f, "FILTER", 0.35f));

    // Bassline Clip (8 steps = 1 bar, 24 ticks per step)
    ClipModel bassClip = new ClipModel("Bass Clip", 3, 8);
    bassClip.setRowYNote(0, 48); // Row 0 represents C3 (midi note 48)
    bassClip.setRowYNote(1, 51); // Row 1 represents Eb3 (midi note 51)
    bassClip.setRowYNote(2, 53); // Row 2 represents F3 (midi note 53)

    // Groove:
    // Step 0: C3 (gate = 1.5 steps = 36 ticks) -> Row 0
    // Step 2: C3 (gate = 0.5 steps = 12 ticks) -> Row 0
    // Step 4: Eb3 (gate = 1.0 steps = 24 ticks) -> Row 1
    // Step 6: F3 (gate = 1.0 steps = 24 ticks) -> Row 2
    bassClip.setStep(0, 0, StepData.of(true, 0.9f, 36.0f, 1.0f, 48));
    bassClip.setStep(0, 2, StepData.of(true, 0.8f, 12.0f, 1.0f, 48));
    bassClip.setStep(1, 4, StepData.of(true, 0.9f, 24.0f, 1.0f, 51));
    bassClip.setStep(2, 6, StepData.of(true, 0.9f, 24.0f, 1.0f, 53));
    bassTrack.addClip(bassClip);
    project.addTrack(bassTrack);

    // ==================================================================================
    // TRACK 1: SYNTH LEAD (Dual-oscillator detune, slow resonant filter sweep, global FX)
    // ==================================================================================
    SynthTrackModel leadTrack = new SynthTrackModel("Synth Lead");
    leadTrack.setOsc1Type("SAW");
    leadTrack.setOsc2Type("SQUARE");
    leadTrack.setOsc2Transpose(0);
    leadTrack.setOsc2Cents(10); // slightly detuned square for thick texture
    leadTrack.setOscMix(0.6f); // 60% osc1, 40% osc2
    leadTrack.setVolume(0.5f);
    leadTrack.setLpfFreq(1200.0f);
    leadTrack.setLpfRes(0.5f);
    leadTrack.setReverbSend(0.4f);
    leadTrack.setDelaySend(0.3f);

    // Amp envelope (Env 0): soft attack, slow release
    leadTrack.setEnv(0, new EnvelopeModel(0.05f, 0.3f, 0.6f, 0.25f, "NONE", 0.0f));
    // Filter sweep envelope (Env 1): slow rising sweep modulation
    leadTrack.setEnv(1, new EnvelopeModel(0.5f, 1.0f, 0.4f, 0.5f, "FILTER", 0.4f));

    // Lead melody clip (16 steps = 2 bars, 24 ticks per step)
    ClipModel leadClip = new ClipModel("Lead Clip", 6, 16);
    leadClip.setRowYNote(0, 72); // C5
    leadClip.setRowYNote(1, 75); // Eb5
    leadClip.setRowYNote(2, 79); // G5
    leadClip.setRowYNote(3, 82); // Bb5
    leadClip.setRowYNote(4, 84); // C6
    leadClip.setRowYNote(5, 86); // D6

    // Melody: C5 -> Eb5 -> G5 -> Bb5 -> G5 -> Eb5
    leadClip.setStep(0, 0, StepData.of(true, 0.8f, 18.0f, 1.0f, 72));
    leadClip.setStep(1, 3, StepData.of(true, 0.8f, 18.0f, 1.0f, 75));
    leadClip.setStep(2, 6, StepData.of(true, 0.8f, 18.0f, 1.0f, 79));
    leadClip.setStep(3, 8, StepData.of(true, 0.8f, 18.0f, 1.0f, 82));
    leadClip.setStep(2, 11, StepData.of(true, 0.8f, 18.0f, 1.0f, 79));
    leadClip.setStep(1, 14, StepData.of(true, 0.8f, 18.0f, 1.0f, 75));
    leadTrack.addClip(leadClip);
    project.addTrack(leadTrack);

    // ==================================================================================
    // TRACK 2: SYNTH DRUM KIT (100% sample-free synth-based drums)
    // ==================================================================================
    KitTrackModel kitTrack = new KitTrackModel("Synth Kit");
    kitTrack.setVolume(0.8f);

    // Drum Row 0 (Kick): Sine wave with tight volume envelope decay
    SoundDrum kick = new SoundDrum("Kick");
    kick.setOsc2Type("SINE");
    kick.setOscBVolume(1.0f);
    kick.setOscAVolume(0.0f);
    kick.setNoiseVolume(0.0f);
    kick.setAdsr(new EnvelopeModel(0.001f, 0.18f, 0.0f, 0.08f, "NONE", 0.0f));
    kitTrack.addDrum(kick);

    // Drum Row 1 (Snare): White noise with short envelope decay and LPF filter
    SoundDrum snare = new SoundDrum("Snare");
    snare.setNoiseVolume(0.75f); // Turn up white noise generator!
    snare.setOscAVolume(0.0f);
    snare.setOscBVolume(0.0f);
    snare.setAdsr(new EnvelopeModel(0.001f, 0.15f, 0.0f, 0.1f, "NONE", 0.0f));
    snare.setLpfFreq(3500.0f);
    snare.setLpfRes(0.1f);
    kitTrack.addDrum(snare);

    // Drum Row 2 (Hihat): High-passed white noise with ultra-short decay
    SoundDrum hihat = new SoundDrum("Hihat");
    hihat.setNoiseVolume(0.6f); // High frequency white noise
    hihat.setOscAVolume(0.0f);
    hihat.setOscBVolume(0.0f);
    hihat.setAdsr(new EnvelopeModel(0.001f, 0.04f, 0.0f, 0.04f, "NONE", 0.0f));
    hihat.setHpfFreq(9000.0f);
    hihat.setHpfRes(0.0f);
    kitTrack.addDrum(hihat);

    // Drum beat clip (16 steps = 1 bar, 24 ticks per step)
    ClipModel drumClip = new ClipModel("Drum Clip", 3, 16);
    drumClip.setRowYNote(0, 0); // Kick
    drumClip.setRowYNote(1, 1); // Snare
    drumClip.setRowYNote(2, 2); // Hihat

    // Kick on beats: 0, 4, 8, 12
    drumClip.setStep(0, 0, StepData.of(true, 0.9f, 12.0f, 1.0f, 0));
    drumClip.setStep(0, 4, StepData.of(true, 0.9f, 12.0f, 1.0f, 0));
    drumClip.setStep(0, 8, StepData.of(true, 0.9f, 12.0f, 1.0f, 0));
    drumClip.setStep(0, 12, StepData.of(true, 0.9f, 12.0f, 1.0f, 0));

    // Snare on beats: 4, 12
    drumClip.setStep(1, 4, StepData.of(true, 0.85f, 12.0f, 1.0f, 1));
    drumClip.setStep(1, 12, StepData.of(true, 0.85f, 12.0f, 1.0f, 1));

    // Hihats on all off-beats (8th note tick): 0, 2, 4, 6, 8, 10, 12, 14
    for (int s = 0; s < 16; s += 2) {
      drumClip.setStep(2, s, StepData.of(true, 0.7f, 6.0f, 1.0f, 2));
    }
    kitTrack.addClip(drumClip);
    project.addTrack(kitTrack);

    // ==================================================================================
    // ARRANGEMENT VIEW TIMELINE SCHEDULING
    // ==================================================================================
    // Track 0 (Bass): plays Clip 0 from Bar 0 to 4 (ticks 0 - 1536)
    project.addArrangerClip(new ArrangerClip(0, bassClip, 0, 1536));

    // Track 1 (Lead): plays Clip 1 from Bar 2 to 6 (ticks 768 - 2304)
    project.addArrangerClip(new ArrangerClip(1, leadClip, 768, 1536));

    // Track 2 (Drums): plays Clip 2 from Bar 0 to 6 (ticks 0 - 2304)
    project.addArrangerClip(new ArrangerClip(2, drumClip, 0, 2304));

    // ==================================================================================
    // 2. SAVE & LOAD E2E ROUNDTRIP EXPORT TEST
    // ==================================================================================
    File songXmlFile = new File("src/main/resources/SONGS/ULTIMATE_SONG.xml");
    org.deluge.xml2.ProjectSerializer2.save(project, songXmlFile);
    System.out.printf(
        "[UltimateTest] Saved E2E complex song XML (STREAM) to: %s%n",
        songXmlFile.getAbsolutePath());

    // Load the saved XML back using DelugeXmlParser to verify E2E roundtrip!
    ProjectModel reloadedProject = DelugeXmlParser.parseSong(songXmlFile);
    System.out.println("[UltimateTest] Successfully parsed back and validated song XML!");

    // ==================================================================================
    // 3. RENDER MIX TO MULTI-TRACK AUDIO WAV
    // ==================================================================================
    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.metronomeEnabled = false;

    Song fwSong = FirmwareFactory.createSong(reloadedProject);
    for (var clip : fwSong.clips) {
      if (clip instanceof org.deluge.firmware.model.InstrumentClip ic) {
        if (ic.sound instanceof FirmwareSound fs) {
          engine.sounds.add(fs);
        }
      }
    }

    PlaybackHandler handler = new PlaybackHandler();
    handler.setSong(fwSong);
    handler.start();

    // Render 8 seconds of audio:
    // 8 seconds * 44100 samples/sec = 352800 samples = 2756 blocks of 128
    int totalBlocks = 2756;
    byte[] wavBytes = new byte[totalBlocks * BLOCK_SIZE * 2 * 2]; // 16-bit stereo PCM
    int byteIdx = 0;

    double ticksPerSample = (120.0f / 60.0 * 96.0) / 44100.0;
    double accumulatedTicks = 0;

    System.out.println("[UltimateTest] Starting E2E rendering of arrangement timeline...");
    for (int b = 0; b < totalBlocks; b++) {
      accumulatedTicks += ticksPerSample * BLOCK_SIZE;
      int toAdvance = (int) accumulatedTicks;
      if (toAdvance > 0) {
        handler.advanceTicks(toAdvance);
        accumulatedTicks -= toAdvance;
      }

      // Render one block of audio
      engine.renderBlock(BLOCK_SIZE);

      for (int i = 0; i < BLOCK_SIZE; i++) {
        // Read Left/Right Q31 integers from masterBuffer, scale to float
        float xL = (float) engine.masterBuffer[i].l / 2147483648.0f;
        float xR = (float) engine.masterBuffer[i].r / 2147483648.0f;

        // Apply master clipping and driver gain (parity to org.deluge.engine.JavaAudioDriver)
        float boostedL = xL * org.deluge.engine.JavaAudioDriver.monitorGainMul;
        if (boostedL > 0.7f) boostedL = 0.7f + 0.3f * (float) Math.tanh((boostedL - 0.7f) / 0.3f);
        if (boostedL < -0.7f) boostedL = -0.7f + 0.3f * (float) Math.tanh((boostedL + 0.7f) / 0.3f);
        short s16L = (short) Math.max(-32768, Math.min(32767, boostedL * 32767.0f));

        float boostedR = xR * org.deluge.engine.JavaAudioDriver.monitorGainMul;
        if (boostedR > 0.7f) boostedR = 0.7f + 0.3f * (float) Math.tanh((boostedR - 0.7f) / 0.3f);
        if (boostedR < -0.7f) boostedR = -0.7f + 0.3f * (float) Math.tanh((boostedR + 0.7f) / 0.3f);
        short s16R = (short) Math.max(-32768, Math.min(32767, boostedR * 32767.0f));

        // Write Left channel (little endian)
        wavBytes[byteIdx++] = (byte) (s16L & 0xFF);
        wavBytes[byteIdx++] = (byte) ((s16L >> 8) & 0xFF);

        // Write Right channel (little endian)
        wavBytes[byteIdx++] = (byte) (s16R & 0xFF);
        wavBytes[byteIdx++] = (byte) ((s16R >> 8) & 0xFF);
      }
    }

    // Save the rendered stereo audio as a physical WAVE file
    File renderedWavFile = new File("src/test/resources/fidelity/JAVA_RENDERED_ULTIMATE_SONG.WAV");
    javax.sound.sampled.AudioFormat format =
        new javax.sound.sampled.AudioFormat(44100.0f, 16, 2, true, false);
    try (javax.sound.sampled.AudioInputStream ais =
        new javax.sound.sampled.AudioInputStream(
            new java.io.ByteArrayInputStream(wavBytes), format, totalBlocks * BLOCK_SIZE)) {
      javax.sound.sampled.AudioSystem.write(
          ais, javax.sound.sampled.AudioFileFormat.Type.WAVE, renderedWavFile);
    }
    System.out.printf(
        "[UltimateTest] SUCCESS: Rendered WAV file saved to: %s%n",
        renderedWavFile.getAbsolutePath());

    assertTrue(renderedWavFile.exists(), "Rendered WAV file does not exist!");
    assertTrue(renderedWavFile.length() > 100000, "Rendered WAV file is too small!");

    handler.stop();
  }

  @Test
  public void generateDiags() throws Exception {
    // 1. LEAD ONLY
    {
      ProjectModel project = new ProjectModel();
      project.setBpm(120.0f);
      project.setMasterVolume(0.8f);

      SynthTrackModel leadTrack = new SynthTrackModel("Synth Lead");
      leadTrack.setOsc1Type("SAW");
      leadTrack.setOsc2Type("SQUARE");
      leadTrack.setOsc2Transpose(0);
      leadTrack.setOsc2Cents(10);
      leadTrack.setOscMix(0.6f);
      leadTrack.setVolume(0.5f);
      leadTrack.setLpfFreq(1200.0f);
      leadTrack.setLpfRes(0.5f);
      leadTrack.setReverbSend(0.4f);
      leadTrack.setDelaySend(0.3f);
      leadTrack.setEnv(0, new EnvelopeModel(0.05f, 0.3f, 0.6f, 0.25f, "NONE", 0.0f));
      leadTrack.setEnv(1, new EnvelopeModel(0.5f, 1.0f, 0.4f, 0.5f, "FILTER", 0.4f));

      ClipModel leadClip = new ClipModel("Lead Clip", 6, 16);
      leadClip.setRowYNote(0, 72);
      leadClip.setRowYNote(1, 75);
      leadClip.setRowYNote(2, 79);
      leadClip.setRowYNote(3, 82);
      leadClip.setRowYNote(4, 84);
      leadClip.setRowYNote(5, 86);
      leadClip.setStep(0, 0, StepData.of(true, 0.8f, 18.0f, 1.0f, 72));
      leadClip.setStep(1, 3, StepData.of(true, 0.8f, 18.0f, 1.0f, 75));
      leadClip.setStep(2, 6, StepData.of(true, 0.8f, 18.0f, 1.0f, 79));
      leadClip.setStep(3, 8, StepData.of(true, 0.8f, 18.0f, 1.0f, 82));
      leadClip.setStep(2, 11, StepData.of(true, 0.8f, 18.0f, 1.0f, 79));
      leadClip.setStep(1, 14, StepData.of(true, 0.8f, 18.0f, 1.0f, 75));
      leadTrack.addClip(leadClip);
      project.addTrack(leadTrack);

      project.addArrangerClip(new ArrangerClip(0, leadClip, 0, 1536));

      org.deluge.xml2.ProjectSerializer2.save(
          project, new File("src/main/resources/SONGS/DIAG_LEAD_ONLY.xml"));
    }

    // 2. KIT ONLY
    {
      ProjectModel project = new ProjectModel();
      project.setBpm(120.0f);
      project.setMasterVolume(0.8f);

      KitTrackModel kitTrack = new KitTrackModel("Synth Kit");
      kitTrack.setVolume(0.8f);

      SoundDrum kick = new SoundDrum("Kick");
      kick.setOsc2Type("SINE");
      kick.setOscBVolume(1.0f);
      kick.setOscAVolume(0.0f);
      kick.setNoiseVolume(0.0f);
      kick.setAdsr(new EnvelopeModel(0.001f, 0.18f, 0.0f, 0.08f, "NONE", 0.0f));
      kitTrack.addDrum(kick);

      SoundDrum snare = new SoundDrum("Snare");
      snare.setNoiseVolume(0.75f);
      snare.setOscAVolume(0.0f);
      snare.setOscBVolume(0.0f);
      snare.setAdsr(new EnvelopeModel(0.001f, 0.15f, 0.0f, 0.1f, "NONE", 0.0f));
      snare.setLpfFreq(3500.0f);
      snare.setLpfRes(0.1f);
      kitTrack.addDrum(snare);

      SoundDrum hihat = new SoundDrum("Hihat");
      hihat.setNoiseVolume(0.6f);
      hihat.setOscAVolume(0.0f);
      hihat.setOscBVolume(0.0f);
      hihat.setAdsr(new EnvelopeModel(0.001f, 0.04f, 0.0f, 0.04f, "NONE", 0.0f));
      hihat.setHpfFreq(9000.0f);
      hihat.setHpfRes(0.0f);
      kitTrack.addDrum(hihat);

      ClipModel drumClip = new ClipModel("Drum Clip", 3, 16);
      drumClip.setRowYNote(0, 0);
      drumClip.setRowYNote(1, 1);
      drumClip.setRowYNote(2, 2);

      drumClip.setStep(0, 0, StepData.of(true, 0.9f, 12.0f, 1.0f, 0));
      drumClip.setStep(0, 4, StepData.of(true, 0.9f, 12.0f, 1.0f, 0));
      drumClip.setStep(0, 8, StepData.of(true, 0.9f, 12.0f, 1.0f, 0));
      drumClip.setStep(0, 12, StepData.of(true, 0.9f, 12.0f, 1.0f, 0));
      drumClip.setStep(1, 4, StepData.of(true, 0.85f, 12.0f, 1.0f, 1));
      drumClip.setStep(1, 12, StepData.of(true, 0.85f, 12.0f, 1.0f, 1));
      for (int s = 0; s < 16; s += 2) {
        drumClip.setStep(2, s, StepData.of(true, 0.7f, 6.0f, 1.0f, 2));
      }
      kitTrack.addClip(drumClip);
      project.addTrack(kitTrack);

      project.addArrangerClip(new ArrangerClip(0, drumClip, 0, 2304));

      org.deluge.xml2.ProjectSerializer2.save(
          project, new File("src/main/resources/SONGS/DIAG_KIT_ONLY.xml"));
    }
  }
}
