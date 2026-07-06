package org.deluge;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Renders a native-FM preset at a configurable MIDI note ({@code -Dfm.note}) and dumps mono
 * samples, so our engine's FM brightness-over-time can be compared to the FM_CAL hardware tap at
 * the SAME note (avoiding the note-dependent bias of the &gt;2 kHz metric). See
 * FIDELITY_GAP_ANALYSIS §4.12.
 */
public class FmEnvelopeProbeTest {

  static final int SR = 44100;

  @Test
  void dumpFmRender() throws Exception {
    File dir =
        new File(
            System.getProperty("fm.synths", System.getProperty("user.home") + "/ludocard/SYNTHS"));
    String name = System.getProperty("fm.preset", "068 FM Bells 1.XML");
    int note = Integer.getInteger("fm.note", 60);
    File xml = new File(dir, name);
    Assumptions.assumeTrue(xml.isFile(), "no preset " + xml);

    FirmwareAudioEngine.cpuDireness = 0;
    org.deluge.firmware2.Functions.resetNoiseSeed();
    SynthTrackModel synth = DelugeXmlParser.parseSynth(new FileInputStream(xml), xml.getName());
    synth.setName(xml.getName().replace(".XML", ""));
    ClipModel clip = new ClipModel("c", 1, 16);
    clip.setStep(0, 0, StepData.of(true, 1.0f, 16.0f, 1.0f, note));
    synth.addClip(clip);
    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);
    project.addTrack(synth);
    ProjectModel song = FirmwareFactory.createSong(project);
    FirmwareSound fs = (FirmwareSound) song.getTracks().get(0).getActiveClip().getSound();
    fs.triggerNote(note, 110);
    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.metronomeEnabled = false;
    engine.sounds.add(fs);
    int n = SR * 3;
    boolean trace = Boolean.getBoolean("fm.trace");
    int env0 = org.deluge.firmware2.PatchSource.ENVELOPE_0.ordinal();
    int pMod0Vol = org.deluge.firmware2.Param.LOCAL_MODULATOR_0_VOLUME;
    int pMod0Fb = org.deluge.firmware2.Param.LOCAL_MODULATOR_0_FEEDBACK;
    if (trace)
      System.out.printf(
          "%6s %12s %12s %12s %12s%n", "t(ms)", "env1", "carrierAmp", "modVol", "modFb");
    StringBuilder sb = new StringBuilder();
    int got = 0;
    int blk = 0;
    while (got < n) {
      engine.renderBlock(128);
      if (trace && (blk % 30 == 0) && !fs.fw2Sound.voices.isEmpty()) {
        var v = fs.fw2Sound.voices.get(0);
        System.out.printf(
            "%6d %12d %12d %12d %12d%n",
            got * 1000 / SR,
            v.sourceValues[env0],
            v.sourceAmplitudesLastTime[0],
            v.paramFinalValues[pMod0Vol],
            v.paramFinalValues[pMod0Fb]);
      }
      blk++;
      for (int i = 0; i < 128 && got < n; i++, got++) {
        sb.append(engine.masterBuffer[i].l).append('\n');
      }
    }
    String outPath = System.getProperty("fm.out", "target/fm_render.txt");
    Files.writeString(Path.of(outPath), sb.toString());
    System.out.println("wrote " + n + " samples of " + name + " @note " + note + " -> " + outPath);
  }
}
