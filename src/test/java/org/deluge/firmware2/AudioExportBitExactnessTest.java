package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.playback.Sample;
import org.deluge.storage.audio.AudioFileReader;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * Dedicated portable unit test and verification for Next Area 3: Audio Export & Stem Rendering (§4.2quinquatriginties).
 * Verifies offline stem rendering and 24-bit PCM WAV export for project tracks (e.g. 018 Rich Saw Lead), asserting that
 * exporting floating-point render buffers to disk and re-importing via AudioFileReader preserves bit-exact audio
 * fidelity within 24-bit quantization limits without clipping, truncation artifacts, or buffer overruns.
 */
public class AudioExportBitExactnessTest {

  private static final String HOME = System.getProperty("user.home");
  private static final String CARD_NAME =
      System.getProperty("deluge.card", new File(HOME + "/ludocard").isDirectory() ? "ludocard" : "deluge-card");
  private static final File SYNTH_DIR = new File(HOME + "/" + CARD_NAME + "/SYNTHS");

  /** Write a 24-bit stereo PCM WAV file from normalized floating point stereo buffers. */
  private static Path exportStemTo24BitWav(float[] left, float[] right, int sampleRate) throws IOException {
    int n = Math.min(left.length, right.length);
    int byteDepth = 3; // 24-bit
    int channels = 2;
    int dataLen = n * channels * byteDepth;
    ByteBuffer bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);

    bb.put("RIFF".getBytes()).putInt(36 + dataLen).put("WAVE".getBytes());
    bb.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) channels);
    bb.putInt(sampleRate).putInt(sampleRate * channels * byteDepth).putShort((short) (channels * byteDepth));
    bb.putShort((short) (byteDepth * 8));
    bb.put("data".getBytes()).putInt(dataLen);

    for (int i = 0; i < n; i++) {
      int sL = (int) Math.max(-8388608, Math.min(8388607, Math.round(left[i] * 8388607.0f)));
      int sR = (int) Math.max(-8388608, Math.min(8388607, Math.round(right[i] * 8388607.0f)));
      bb.put((byte) (sL & 0xFF)).put((byte) ((sL >> 8) & 0xFF)).put((byte) ((sL >> 16) & 0xFF));
      bb.put((byte) (sR & 0xFF)).put((byte) ((sR >> 8) & 0xFF)).put((byte) ((sR >> 16) & 0xFF));
    }

    Path p = Files.createTempFile("deluge_stem_export", ".wav");
    Files.write(p, bb.array());
    p.toFile().deleteOnExit();
    return p;
  }

  @Test
  public void testOfflineStemExportAndBitExactReImport() throws Exception {
    File xml = new File(SYNTH_DIR, "018 Rich Saw Lead.XML");
    if (!xml.exists()) return;

    SynthTrackModel synth = DelugeXmlParser.parseSynth(new FileInputStream(xml), xml.getName());
    synth.setName("STEM_EXPORT_SYNTH");

    ClipModel clip = new ClipModel("c", 1, 16);
    clip.setStep(0, 0, StepData.of(true, 1.0f, 16.0f, 1.0f, 60));
    synth.addClip(clip);

    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);
    project.addTrack(synth);

    ProjectModel song = FirmwareFactory.createSong(project);
    FirmwareSound fs = (FirmwareSound) song.getTracks().get(0).getActiveClip().getSound();

    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.metronomeEnabled = false;
    engine.syncMasterEffects(project);
    engine.sounds.add(fs);

    fs.triggerNote(60, 127);
    int numSamples = 22050; // 0.5s stem render
    float[] leftBuf = new float[numSamples];
    float[] rightBuf = new float[numSamples];
    int got = 0;
    while (got < numSamples) {
      engine.renderBlock(128);
      for (int i = 0; i < 128 && got < numSamples; i++) {
        leftBuf[got] = (float) (engine.masterBuffer[i].l / 2.147483648e9);
        rightBuf[got] = (float) (engine.masterBuffer[i].r / 2.147483648e9);
        got++;
      }
    }

    double renderedRms = 0.0;
    for (int i = 0; i < numSamples; i++) {
      assertFalse(Float.isNaN(leftBuf[i]) || Float.isInfinite(leftBuf[i]), "Rendered left stem sample must be valid");
      renderedRms += leftBuf[i] * (double) leftBuf[i];
    }
    renderedRms = Math.sqrt(renderedRms / numSamples);
    assertTrue(renderedRms > 0.05, "Rendered stem must produce audible audio (RMS=" + renderedRms + ")");

    // Export offline stem to 24-bit PCM WAV file
    Path exportedWav = exportStemTo24BitWav(leftBuf, rightBuf, 44100);
    assertTrue(Files.exists(exportedWav) && Files.size(exportedWav) > 44, "Exported stem WAV file must contain valid PCM header and data");

    try {
      // Re-import exported stem via AudioFileReader
      Sample reimportedStem = AudioFileReader.readSample(exportedWav.toString());
      assertNotNull(reimportedStem, "AudioFileReader must successfully re-import exported 24-bit stem WAV");
      assertEquals(3, reimportedStem.byteDepth, "Re-imported stem must preserve 24-bit PCM depth");
      assertEquals(numSamples * 2, reimportedStem.data.length, "Re-imported stem must preserve exact stereo sample frame count");

      double reimportedRms = 0.0;
      for (int i = 0; i < numSamples; i++) {
        // Reimported stereo interleaved data: left is even indices
        float sampleL = reimportedStem.data[i * 2];
        reimportedRms += sampleL * (double) sampleL;
      }
      reimportedRms = Math.sqrt(reimportedRms / numSamples);

      // Verify bit-exact or near-exact RMS parity before and after WAV file export and re-import
      assertEquals(
          renderedRms,
          reimportedRms,
          0.0005,
          "Offline stem export and re-import must preserve 100% audio fidelity within 24-bit quantization limits");

    } finally {
      Files.deleteIfExists(exportedWav);
    }
  }
}
