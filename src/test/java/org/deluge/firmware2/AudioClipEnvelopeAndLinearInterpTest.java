package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import org.deluge.engine.FirmwareFactory;
import org.deluge.model.AudioTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.project.ProjectSerializer;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@code AudioOutput} attack envelope ({@code audio_output.cpp:106-188}) and {@code
 * <audioClip attack="..." linearInterpolation="1">} XML parsing/serialization ({@code
 * audio_clip.cpp:63, 1065-1071, 1154-1162}).
 */
class AudioClipEnvelopeAndLinearInterpTest {

  private static Sample makeConstantSample(int frames, int value) {
    Sample s = new Sample();
    s.numChannels = 1;
    s.byteDepth = 4;
    s.sampleRate = 44100;
    s.audioDataStartPosBytes = 0;
    s.lengthInSamples = frames;
    s.audioDataLengthBytes = (long) frames * s.byteDepth;
    s.data = new int[frames];
    java.util.Arrays.fill(s.data, value);
    return s;
  }

  @Test
  void defaultInstantAttack_reachesSteadyLevelOnFirstSample() {
    Sample sample = makeConstantSample(256, 200_000_000);
    AudioOutput ao = new AudioOutput();
    assertEquals(Integer.MIN_VALUE, ao.getAttackRaw(), "default attack must match C -2147483648");
    ao.setClip(sample, true);
    ao.setAmplitude(1 << 27);
    ao.onTransportStart();

    int[] buf = new int[256];
    int[] rev = new int[128];
    ao.renderInternal(buf, 128, rev);

    assertTrue(buf[0] > 0, "first sample must be audible with instant attack");
    assertEquals(buf[0], buf[2], "instant attack must have zero ramp increment on block 0");
    assertEquals(buf[0], buf[254], "steady level must hold across the entire first block");
  }

  @Test
  void slowAttack_rampsEnvelopeFromZeroAcrossBlocks() {
    Sample sample = makeConstantSample(2048, 200_000_000);
    AudioOutput ao = new AudioOutput();
    // Positive attackRaw yields a slow attack rate (<= 245632), so amplitudeLastTime starts at 0.
    ao.setAttackRaw(1073741824);
    ao.setClip(sample, true);
    ao.setAmplitude(1 << 27);
    ao.onTransportStart();

    int[] buf0 = new int[256];
    int[] buf1 = new int[256];
    int[] rev = new int[128];
    ao.renderInternal(buf0, 128, rev);
    ao.renderInternal(buf1, 128, rev);

    long peak0 = 0;
    long peak1 = 0;
    for (int s : buf0) peak0 = Math.max(peak0, Math.abs((long) s));
    for (int s : buf1) peak1 = Math.max(peak1, Math.abs((long) s));

    assertTrue(
        buf0[0] > 0 && buf0[0] < buf0[254] / 64,
        "slow attack starts at amplitudeLastTime = 0 plus one per-sample increment");
    assertTrue(buf0[254] > buf0[0], "block 0 ramps upward from sample 0 to sample 127");
    assertTrue(peak0 > 0, "block 0 ramps upward during slow attack");
    assertTrue(
        peak1 > peak0,
        "block 1 must reach a higher envelope level than block 0 during slow attack (peak0="
            + peak0
            + ", peak1="
            + peak1
            + ")");
  }

  @Test
  void linearInterpolation_selectsTwoTapResamplerOnAudioOutput() {
    // Alternating high-frequency step pattern where 2-tap linear and windowed-sinc differ.
    Sample sample = new Sample();
    sample.numChannels = 1;
    sample.byteDepth = 4;
    sample.sampleRate = 44100;
    sample.lengthInSamples = 256;
    sample.audioDataLengthBytes = 256L * 4;
    sample.data = new int[256];
    for (int i = 0; i < 256; i++) {
      sample.data[i] = ((i & 1) == 0) ? 300_000_000 : -300_000_000;
    }

    AudioOutput sincOut = new AudioOutput();
    sincOut.setClip(sample, true);
    sincOut.setLinearInterpolation(false);
    sincOut.setPlayback(1.37f, false); // resampled (non-unity phaseIncrement)
    sincOut.onTransportStart();

    AudioOutput linearOut = new AudioOutput();
    linearOut.setClip(sample, true);
    linearOut.setLinearInterpolation(true);
    linearOut.setPlayback(1.37f, false);
    linearOut.onTransportStart();

    int[] sincBuf = new int[256];
    int[] linearBuf = new int[256];
    int[] rev = new int[128];
    sincOut.renderInternal(sincBuf, 128, rev);
    linearOut.renderInternal(linearBuf, 128, rev);

    boolean anyDiff = false;
    for (int i = 0; i < sincBuf.length; i++) {
      if (sincBuf[i] != linearBuf[i]) {
        anyDiff = true;
        break;
      }
    }
    assertTrue(anyDiff, "linearInterpolation=true must change resampled AudioOutput output");
  }

  @Test
  void xmlRoundTrip_preservesAttackRawAndLinearInterpolation() throws Exception {
    ProjectModel model = new ProjectModel();
    AudioTrackModel track = new AudioTrackModel("VOX");
    AudioTrackModel.AudioClip clip = new AudioTrackModel.AudioClip();
    clip.setTrackName("VOX");
    clip.setFilePath("SAMPLES/vox.wav");
    clip.setAttackRaw(536870912);
    clip.setLinearInterpolation(true);
    track.addAudioClip(clip);
    model.addTrack(track);

    File tempXml = File.createTempFile("deluge_audioclip_interp", ".xml");
    tempXml.deleteOnExit();
    ProjectSerializer.save(model, tempXml);

    String xml = Files.readString(tempXml.toPath());
    assertTrue(xml.contains("attack=\"536870912\""), "attack must serialize as C int32 decimal");
    assertTrue(xml.contains("linearInterpolation=\"1\""), "linearInterpolation=1 must be written");

    ProjectModel parsed = DelugeXmlParser.parseSong(tempXml);
    AudioTrackModel parsedTrack = (AudioTrackModel) parsed.getTracks().get(0);
    AudioTrackModel.AudioClip parsedClip = parsedTrack.getAudioClips().get(0);
    assertEquals(536870912, parsedClip.getAttackRaw());
    assertTrue(parsedClip.isLinearInterpolation());

    // FirmwareFactory.createAudioSound wires both fields onto AudioOutput.
    GlobalEffectable ge = FirmwareFactory.createAudioSound(parsedTrack, 0, parsed);
    assertTrue(ge instanceof AudioOutput);
    AudioOutput ao = (AudioOutput) ge;
    assertEquals(536870912, ao.getAttackRaw());
    assertTrue(ao.isLinearInterpolation());

    // When linearInterpolation is false, the attribute is omitted on save (C audio_clip.cpp:1065).
    parsedClip.setLinearInterpolation(false);
    parsedClip.setAttackRaw(Integer.MIN_VALUE);
    ProjectSerializer.save(parsed, tempXml);
    String xmlDefault = Files.readString(tempXml.toPath());
    assertFalse(xmlDefault.contains("linearInterpolation="));
    assertTrue(xmlDefault.contains("attack=\"-2147483648\""));
    assertNotEquals(0, parsedClip.getAttackRaw());
  }

  @Test
  void endedEnvelopeOnActiveClip_keepsDelayFeedbackUnlimitedAcrossSubsequentBlocks() {
    Sample sample = new Sample();
    sample.numChannels = 1;
    sample.byteDepth = 4;
    sample.sampleRate = 44100;
    sample.lengthInSamples = 256;
    sample.audioDataLengthBytes = 256L * 4;
    sample.data = new int[256];
    for (int i = 0; i < 256; i++) {
      // ~2.75 kHz passband wave (Delay's feedback loop contains a DC-blocking filter)
      sample.data[i] = ((i & 8) == 0) ? 200_000_000 : -200_000_000;
    }
    AudioOutput ao = new AudioOutput();
    assertTrue(
        ao.shouldLimitDelayFeedback,
        "Newly constructed inactive AudioOutput must limit delay feedback before entering window");
    ao.setClip(sample, false);
    ao.addTimelineRange(10, 20);
    ao.addTimelineRange(20, 30);

    // Feed AudioOutput through firmware2 Delay with self-oscillating feedback (1 << 30) and short
    // delay line (userDelayRate = 1 << 27 -> ~64-sample delay buffer) to verify actual sustained
    // stereo delay tail output across subsequent blocks while inside [10, 20).
    Delay delay = new Delay();
    final int fullFeedback =
        1 << 30; // 1073741824 (>= 1040187392 -> infinite repeats when un-clamped)
    final int feedbackLimit =
        (1 << 30) - (1 << 26); // C global_effectable.cpp:1132-1135 (1006632960)
    int[] buf = new int[256];
    int[][] stereoBuf = new int[128][2];
    int[] rev = new int[128];

    // Enter active placement window [10, 20): both playing and clipActive become true.
    ao.updateTimeline(12, true);
    assertTrue(ao.isPlaying());
    assertFalse(ao.shouldLimitDelayFeedback);

    // Compare against an identical Delay clamped at Delay's silence feedback limit across both
    // 128-sample blocks of the 256-sample one-shot clip before its envelope reaches OFF.
    Delay clampedRef = new Delay();
    Delay.State ws = new Delay.State();
    Delay.State refWs = new Delay.State();
    int[][] refBuf = new int[128][2];
    for (int srcBlock = 0; srcBlock < 2; srcBlock++) {
      Arrays.fill(buf, 0);
      ao.renderInternal(buf, 128, rev);
      for (int i = 0; i < 128; i++) {
        stereoBuf[i][0] = buf[i * 2];
        stereoBuf[i][1] = buf[i * 2 + 1];
        refBuf[i][0] = buf[i * 2];
        refBuf[i][1] = buf[i * 2 + 1];
      }
      ws.userDelayRate = 1 << 27;
      ws.delayFeedbackAmount = ao.shouldLimitDelayFeedback ? feedbackLimit : fullFeedback;
      delay.setupWorkingState(ws, 0, true);
      delay.process(stereoBuf, 128, ws);

      refWs.userDelayRate = 1 << 27;
      refWs.delayFeedbackAmount = feedbackLimit;
      clampedRef.setupWorkingState(refWs, 0, true);
      clampedRef.process(refBuf, 128, refWs);
    }

    // Simulate envelope reaching OFF mid-placement (C audio_output.cpp:156-166):
    // voiceSample stops streaming (playing = false), while delay feedback stays unlimited.
    ao.envelope.state = Envelope.Stage.OFF;
    Arrays.fill(buf, 0);
    ao.renderInternal(buf, 128, rev);
    assertFalse(ao.isPlaying(), "Envelope OFF must unassign/stop streaming the one-shot sample");
    assertFalse(
        ao.shouldLimitDelayFeedback,
        "Envelope OFF must NOT set shouldLimitDelayFeedback on an active clip");

    // Render 200 subsequent silent-source blocks (~18 echo repeats) inside [10, 20):
    // updateTimeline runs every block, playing stays false (no false re-trigger),
    // shouldLimitDelayFeedback stays false, and the stereo delay tail sustains self-oscillation.
    long unclampedTailAbsSum = 0;
    long clampedTailAbsSum = 0;
    for (int block = 0; block < 200; block++) {
      ao.updateTimeline(16, true);
      assertFalse(ao.isPlaying(), "Must NOT re-trigger inside the same [10, 20) placement window");
      assertFalse(
          ao.shouldLimitDelayFeedback,
          "Subsequent updateTimeline blocks inside [10, 20) must NOT clamp delay feedback");
      Arrays.fill(buf, 0);
      ao.renderInternal(buf, 128, rev);
      for (int i = 0; i < 128; i++) {
        stereoBuf[i][0] = buf[i * 2];
        stereoBuf[i][1] = buf[i * 2 + 1];
        refBuf[i][0] = 0;
        refBuf[i][1] = 0;
      }
      ws.userDelayRate = 1 << 27;
      ws.delayFeedbackAmount = ao.shouldLimitDelayFeedback ? feedbackLimit : fullFeedback;
      delay.setupWorkingState(ws, 0, false);
      delay.process(stereoBuf, 128, ws);

      refWs.userDelayRate = 1 << 27;
      refWs.delayFeedbackAmount = feedbackLimit;
      clampedRef.setupWorkingState(refWs, 0, false);
      clampedRef.process(refBuf, 128, refWs);

      if (block >= 150) {
        for (int i = 0; i < 128; i++) {
          unclampedTailAbsSum +=
              Math.abs((long) stereoBuf[i][0]) + Math.abs((long) stereoBuf[i][1]);
          clampedTailAbsSum += Math.abs((long) refBuf[i][0]) + Math.abs((long) refBuf[i][1]);
        }
      }
    }
    assertEquals(
        255, delay.repeatsUntilAbandon, "Un-clamped self-oscillating delay never abandons");
    assertTrue(
        unclampedTailAbsSum > clampedTailAbsSum * 2,
        "Un-clamped active-clip delay tail ("
            + unclampedTailAbsSum
            + ") must sustain far louder than silence-clamped tail ("
            + clampedTailAbsSum
            + ")");

    // Back-to-back placement window [20, 30) must cleanly re-trigger from sample start!
    ao.updateTimeline(20, true);
    assertTrue(
        ao.isPlaying(),
        "Entering back-to-back placement [20, 30) after Envelope.Stage.OFF must re-trigger");
    assertFalse(ao.shouldLimitDelayFeedback);

    // Exiting [20, 30) ends song-level clip activeness (C song.cpp:2509-2514) and limits feedback.
    ao.updateTimeline(30, true);
    assertFalse(ao.isPlaying());
    assertTrue(
        ao.shouldLimitDelayFeedback,
        "Exiting active clip window must set shouldLimitDelayFeedback = true");
    ws.userDelayRate = 1 << 27;
    ws.delayFeedbackAmount = ao.shouldLimitDelayFeedback ? feedbackLimit : fullFeedback;
    delay.setupWorkingState(ws, 0, false);
    assertEquals(110, delay.repeatsUntilAbandon, "Clamped delay feedback arms finite abandon");
  }

  @Test
  void nullSampleAndStopTransitions_keepFeedbackLimitedAndAllowCleanResume() {
    Sample sample = makeConstantSample(64, 150_000_000);
    AudioOutput empty = new AudioOutput();

    // 1. Null sample: setPlaying(true), onTransportStart(), and updateTimeline(0, true) keep
    // playing=false and shouldLimitDelayFeedback=true, and loading a sample mid-play starts
    // cleanly.
    empty.setPlaying(true);
    assertFalse(empty.isPlaying());
    assertTrue(empty.shouldLimitDelayFeedback);

    empty.onTransportStart();
    assertFalse(empty.isPlaying());
    assertTrue(empty.shouldLimitDelayFeedback);

    empty.updateTimeline(0, true);
    assertFalse(empty.isPlaying());
    assertTrue(empty.shouldLimitDelayFeedback);

    empty.setClip(sample, true);
    empty.updateTimeline(1, true);
    assertTrue(
        empty.isPlaying(),
        "Loading a sample onto a running session track must trigger on next updateTimeline");
    assertFalse(empty.shouldLimitDelayFeedback);

    // 2. Direct onTransportStop() inside an arrangement window resets activeRangeIdx so subsequent
    // updateTimeline inside [10, 20) can re-enter cleanly; setClip(null) stops and limits feedback.
    AudioOutput arranged = new AudioOutput();
    arranged.setClip(sample, true);
    arranged.setTimelineRange(10, 20);
    arranged.updateTimeline(12, true);
    assertTrue(arranged.isPlaying());
    assertFalse(arranged.shouldLimitDelayFeedback);

    arranged.onTransportStop();
    assertFalse(arranged.isPlaying());
    assertTrue(arranged.shouldLimitDelayFeedback);

    arranged.updateTimeline(14, true);
    assertTrue(arranged.isPlaying(), "updateTimeline after onTransportStop must re-enter window");
    assertFalse(arranged.shouldLimitDelayFeedback);

    // 3. Moving placement window mid-playback, swapping samples right before exiting a window,
    // and direct setPlaying(true) -> stopped updateTimeline must all stop streaming immediately.
    arranged.setTimelineRange(50, 60);
    arranged.updateTimeline(15, true);
    assertFalse(arranged.isPlaying(), "Moving placement away from playhead must stop streaming");
    assertTrue(arranged.shouldLimitDelayFeedback);

    arranged.updateTimeline(52, true);
    assertTrue(arranged.isPlaying(), "Entering relocated [50, 60) placement must start streaming");
    assertFalse(arranged.shouldLimitDelayFeedback);

    Sample sample2 = makeConstantSample(64, 120_000_000);
    arranged.setClip(sample2, true);
    arranged.updateTimeline(60, true);
    assertFalse(
        arranged.isPlaying(), "Swapping sample right before exiting window must stop at endTick");
    assertTrue(arranged.shouldLimitDelayFeedback);

    arranged.setPlaying(true);
    assertTrue(arranged.isPlaying());
    arranged.updateTimeline(0, false);
    assertFalse(
        arranged.isPlaying(), "Stopped transport updateTimeline must stop setPlaying(true) stream");
    assertTrue(arranged.shouldLimitDelayFeedback);

    arranged.setPlaying(true);
    arranged.setClip(null, false);
    assertFalse(arranged.isPlaying());
    assertTrue(arranged.shouldLimitDelayFeedback);
    arranged.updateTimeline(55, true);
    assertFalse(arranged.isPlaying());
    assertTrue(arranged.shouldLimitDelayFeedback);
  }
}
