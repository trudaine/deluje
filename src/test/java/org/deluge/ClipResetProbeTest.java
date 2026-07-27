package org.deluge;

import java.io.FileInputStream;
import java.util.List;
import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.model.TrackModel;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Diagnostic probe for the C-exact clip-param reset: renders selected ALLSYN embedded presets and
 * prints a 250 ms RMS envelope so silent/late-energy renders can be root-caused. Run with: mvn test
 * -Dtest=ClipResetProbeTest -Dclipreset.probe=true
 */
public class ClipResetProbeTest {
  static final int SR = 44100;

  @Test
  @EnabledIfSystemProperty(named = "clipreset.seq", matches = "true")
  public void sequential() throws Exception {
    String cardName = System.getProperty("deluge.card", "src/main/resources");
    List<TrackModel> tracks = new java.util.ArrayList<>();
    for (int part = 1; part <= 2; part++) {
      java.io.File songFile = new java.io.File(cardName, "SONGS/ALLSYN_" + part + ".XML");
      org.junit.jupiter.api.Assumptions.assumeTrue(
          songFile.exists(), "song file missing: " + songFile);
      ProjectModel song =
          DelugeXmlParser.parseSong(new FileInputStream(songFile), songFile.getName());
      tracks.addAll(song.getTracks());
    }
    for (int i = 0; i < tracks.size(); i++) {
      if (!(tracks.get(i) instanceof SynthTrackModel st)) {
        continue;
      }
      org.deluge.storage.audio.AudioFileReader.clearCache();
      int note = 60, vel = 127;
      if (!st.getClips().isEmpty()) {
        ClipModel c = st.getClips().get(0);
        outer:
        for (int r = 0; r < c.getRowCount(); r++) {
          for (int stp = 0; stp < c.getStepCount(); stp++) {
            StepData sd = c.getStep(r, stp);
            if (sd != null && sd.active()) {
              note = sd.pitch();
              vel = Math.max(1, Math.round(sd.velocity() * 127));
              break outer;
            }
          }
        }
      }
      float[] out = render(st, note, vel);
      double max = 0;
      for (int off = 0; off + SR / 4 <= out.length; off += SR / 4) {
        max = Math.max(max, rms(out, off, SR / 4));
      }
      System.out.printf("  seq[%d] %-30s ourMax=%.4f%n", i, st.getName(), max);
    }
  }

  @Test
  @EnabledIfSystemProperty(named = "clipreset.probe", matches = "true")
  public void probe() throws Exception {
    String cardName = System.getProperty("deluge.card", "src/main/resources");
    for (String[] spec :
        new String[][] {
          {"1", "011 Dubstep Bass"},
          {"2", "109 Talking Arp"},
          {"2", "SolidBassShort"},
        }) {
      java.io.File songFile = new java.io.File(cardName, "SONGS/ALLSYN_" + spec[0] + ".XML");
      org.junit.jupiter.api.Assumptions.assumeTrue(
          songFile.exists(), "song file missing: " + songFile);
      ProjectModel song =
          DelugeXmlParser.parseSong(new FileInputStream(songFile), songFile.getName());
      TrackModel track = null;
      for (TrackModel t : song.getTracks()) {
        if (t.getName().equals(spec[1])) {
          track = t;
          break;
        }
      }
      if (!(track instanceof SynthTrackModel st)) {
        System.out.println("NOT FOUND: " + spec[1]);
        continue;
      }
      int note = 60, vel = 127;
      if (!st.getClips().isEmpty()) {
        ClipModel c = st.getClips().get(0);
        outer:
        for (int r = 0; r < c.getRowCount(); r++) {
          for (int stp = 0; stp < c.getStepCount(); stp++) {
            StepData sd = c.getStep(r, stp);
            if (sd != null && sd.active()) {
              note = sd.pitch();
              vel = Math.max(1, Math.round(sd.velocity() * 127));
              break outer;
            }
          }
        }
      }
      System.out.println("=== " + spec[1] + "  note=" + note + " vel=" + vel);
      System.out.println(
          "  cables="
              + st.getModulation().getPatchCables().size()
              + " oscA_vol="
              + st.getOscAVolume()
              + " oscB_vol="
              + st.getOscBVolume()
              + " osc1="
              + st.getOsc1Type()
              + " osc2="
              + st.getOsc2Type()
              + " lpfHz="
              + st.getLpfFreq()
              + " lpfRes="
              + st.getLpfRes()
              + " vol="
              + st.getVolume());
      System.out.println("  hpfHz=" + st.getHpfFreq() + " hpfRes=" + st.getHpfRes());
      float[] out = render(st, note, vel);
      StringBuilder sb = new StringBuilder("  rms250:");
      for (int off = 0; off + SR / 4 <= out.length; off += SR / 4) {
        sb.append(String.format(" %.4f", rms(out, off, SR / 4)));
      }
      System.out.println(sb);
      if (Boolean.getBoolean("clipreset.fullSustain")) {
        st.getRawKnobs().setEnvKnobsQ31(0, 0, 0, Integer.MAX_VALUE, 0);
        st.getRawKnobs()
            .setRawParamKnob(org.deluge.firmware2.Param.LOCAL_ENV_0_SUSTAIN, Integer.MAX_VALUE);
        float[] out2 = render(st, note, vel);
        StringBuilder sb2 = new StringBuilder("  rms250 (sustain=MAX):");
        for (int off = 0; off + SR / 4 <= out2.length; off += SR / 4) {
          sb2.append(String.format(" %.4f", rms(out2, off, SR / 4)));
        }
        System.out.println(sb2);
      }
    }
  }

  static float[] render(SynthTrackModel synth, int note, int velocity) throws Exception {
    FirmwareAudioEngine.cpuDireness = 0;
    org.deluge.firmware2.Functions.resetNoiseSeed();
    if (synth.getClips().isEmpty()) {
      ClipModel clip = new ClipModel("c", 1, 16);
      clip.setStep(0, 0, StepData.of(true, 1.0f, 16.0f, 1.0f, note));
      synth.addClip(clip);
    }
    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);
    project.addTrack(synth);
    ProjectModel song = FirmwareFactory.createSong(project);
    FirmwareSound fs = (FirmwareSound) song.getTracks().get(0).getActiveClip().getSound();
    int[] pk = fs.paramKnobs;
    System.out.println(
        "  knobs: oscA="
            + pk[org.deluge.firmware2.Param.LOCAL_OSC_A_VOLUME]
            + " oscB="
            + pk[org.deluge.firmware2.Param.LOCAL_OSC_B_VOLUME]
            + " vol="
            + pk[org.deluge.firmware2.Param.LOCAL_VOLUME]
            + " postFx="
            + pk[org.deluge.firmware2.Param.GLOBAL_VOLUME_POST_FX]
            + " noise="
            + pk[org.deluge.firmware2.Param.LOCAL_NOISE_VOLUME]
            + " env0="
            + pk[org.deluge.firmware2.Param.LOCAL_ENV_0_ATTACK]
            + "/"
            + pk[org.deluge.firmware2.Param.LOCAL_ENV_0_DECAY]
            + "/"
            + pk[org.deluge.firmware2.Param.LOCAL_ENV_0_SUSTAIN]
            + "/"
            + pk[org.deluge.firmware2.Param.LOCAL_ENV_0_RELEASE]
            + " lpf="
            + pk[org.deluge.firmware2.Param.LOCAL_LPF_FREQ]
            + " lpfRes="
            + pk[org.deluge.firmware2.Param.LOCAL_LPF_RESONANCE]
            + " unison n="
            + fs.fw2Sound.numUnison
            + " det="
            + fs.fw2Sound.unisonDetune);
    fs.triggerNote(note, velocity);
    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.metronomeEnabled = false;
    engine.syncMasterEffects(project);
    engine.sounds.add(fs);
    int n = SR * 3;
    float[] out = new float[n];
    int got = 0;
    for (int b = 0; got < n; b++) {
      engine.renderBlock(128);
      for (int i = 0; i < 128 && got < n; i++)
        out[got++] = (float) (engine.masterBuffer[i].l / 2.147483648e9);
    }
    for (ClipModel c : synth.getClips()) {
      c.setSound(null);
    }
    return out;
  }

  static double rms(float[] x, int from, int len) {
    double s = 0;
    int c = 0;
    for (int n = from; n < from + len && n < x.length; n++) {
      s += x[n] * (double) x[n];
      c++;
    }
    return c > 0 ? Math.sqrt(s / c) : 0;
  }
}
