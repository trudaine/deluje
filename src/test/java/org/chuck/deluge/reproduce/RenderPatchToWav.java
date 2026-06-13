package org.chuck.deluge.reproduce;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import org.chuck.deluge.firmware.engine.FirmwareFactory;
import org.chuck.deluge.firmware.engine.FirmwareSound;
import org.chuck.deluge.firmware.model.InstrumentClip;
import org.chuck.deluge.firmware.model.Song;
import org.chuck.deluge.firmware2.StereoSample;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;
import org.chuck.deluge.xml.DelugeXmlParser;

/**
 * A/B harness: render a single Deluge synth patch (.XML) at one MIDI note to a 16-bit stereo WAV,
 * through the supported PURE firmware engine, so the output can be compared against a hardware
 * recording of the same patch/note. See docs/HARDWARE_AB_PLAN.md.
 *
 * <p>Usage (from the repo root, after `mvn -pl deluge -am test-compile`):
 *
 * <pre>
 *   CP="chuck-core/target/classes:deluge/target/classes:deluge/target/test-classes:$(cat deluge-deps-classpath)"
 *   java --enable-preview --add-modules=jdk.incubator.vector -cp "$CP" \
 *       org.chuck.deluge.reproduce.RenderPatchToWav "&lt;patch.XML&gt;" &lt;midiNote&gt; &lt;out.wav&gt; [seconds] [velocity]
 * </pre>
 */
public final class RenderPatchToWav {
  private static final int SR = 44100;

  public static void main(String[] args) throws Exception {
    if (args.length < 3) {
      System.err.println(
          "usage: RenderPatchToWav <patch.XML> <midiNote> <out.wav> [seconds=2.0] [velocity=110]");
      return;
    }
    File xml = new File(args[0]);
    int note = Integer.parseInt(args[1]);
    File out = new File(args[2]);
    double seconds = args.length > 3 ? Double.parseDouble(args[3]) : 2.0;
    int velocity = args.length > 4 ? Integer.parseInt(args[4]) : 110;

    SynthTrackModel m = new DelugeXmlParser().parseSynth(xml);
    m.addClip(new ClipModel("c", 8, 16));
    ProjectModel project = new ProjectModel();
    project.addTrack(m);
    Song song = FirmwareFactory.createSong(project);
    FirmwareSound sound = (FirmwareSound) ((InstrumentClip) song.clips.get(0)).sound;

    int total = (int) (seconds * SR);
    int releaseAt = (int) (total * 0.6); // note-off at 60% so the release tail is captured
    short[] left = new short[total];
    short[] right = new short[total];

    StereoSample[] block = new StereoSample[128];
    for (int i = 0; i < 128; i++) block[i] = new StereoSample();

    sound.triggerNote(note, velocity);
    boolean releasedYet = false;
    for (int off = 0; off < total; off += 128) {
      if (!releasedYet && off >= releaseAt) {
        sound.releaseNote(note, -1);
        releasedYet = true;
      }
      for (int i = 0; i < 128; i++) {
        block[i].l = 0;
        block[i].r = 0;
      }
      sound.renderOutput(block, 128, null);
      for (int i = 0; i < 128 && off + i < total; i++) {
        left[off + i] = toShort(block[i].l);
        right[off + i] = toShort(block[i].r);
      }
    }

    writeWav(out, left, right);
    System.out.println(
        "Rendered "
            + xml.getName()
            + " note "
            + note
            + " vel "
            + velocity
            + " -> "
            + out.getPath()
            + " ("
            + seconds
            + "s)");
  }

  private static short toShort(int q31) {
    long v = (long) q31 >> 16; // Q31 -> 16-bit
    if (v > 32767) v = 32767;
    if (v < -32768) v = -32768;
    return (short) v;
  }

  private static void writeWav(File out, short[] l, short[] r) throws IOException {
    int n = l.length;
    int dataLen = n * 2 * 2; // stereo, 16-bit
    try (DataOutputStream d =
        new DataOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
      d.writeBytes("RIFF");
      le32(d, 36 + dataLen);
      d.writeBytes("WAVE");
      d.writeBytes("fmt ");
      le32(d, 16);
      le16(d, 1); // PCM
      le16(d, 2); // stereo
      le32(d, SR);
      le32(d, SR * 2 * 2);
      le16(d, 4); // block align
      le16(d, 16); // bits
      d.writeBytes("data");
      le32(d, dataLen);
      for (int i = 0; i < n; i++) {
        le16(d, l[i]);
        le16(d, r[i]);
      }
    }
  }

  private static void le16(DataOutputStream d, int v) throws IOException {
    d.writeByte(v & 0xFF);
    d.writeByte((v >> 8) & 0xFF);
  }

  private static void le32(DataOutputStream d, int v) throws IOException {
    d.writeByte(v & 0xFF);
    d.writeByte((v >> 8) & 0xFF);
    d.writeByte((v >> 16) & 0xFF);
    d.writeByte((v >> 24) & 0xFF);
  }

  private RenderPatchToWav() {}
}
