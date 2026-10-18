package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
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
}
