package org.chuck.deluge.model;

import java.util.ArrayList;
import java.util.List;

/** Represents a Kit track containing multiple distinct drum sounds (samples). */
public class KitTrackModel extends TrackModel {

  public static class KitSound {
    private String name = "";
    private String samplePath = "";
    private boolean reverse = false;
    private float startMs = 0.0f;
    private float endMs = 0.0f;
    private float pitchSemitones = 0.0f;
    private int muteGroup = 0;

    // Per-sound shaping
    private EnvelopeModel adsr = EnvelopeModel.defaultConfig();
    private float lpfFreq = 20000.0f;
    private float lpfRes = 0.0f;

    private float eqBass = 0.0f;
    private float eqTreble = 0.0f;
    private float sidechainSend = 0.0f;

    public KitSound(String name) {
      this.name = name;
    }

    public KitSound(String name, String samplePath) {
      this.name = name;
      this.samplePath = samplePath;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getSamplePath() {
      return samplePath;
    }

    public void setSamplePath(String samplePath) {
      this.samplePath = samplePath;
    }

    public boolean isReverse() {
      return reverse;
    }

    public void setReverse(boolean reverse) {
      this.reverse = reverse;
    }

    public float getStartMs() {
      return startMs;
    }

    public void setStartMs(float startMs) {
      this.startMs = startMs;
    }

    public float getEndMs() {
      return endMs;
    }

    public void setEndMs(float endMs) {
      this.endMs = endMs;
    }

    public float getPitchSemitones() {
      return pitchSemitones;
    }

    public void setPitchSemitones(float pitchSemitones) {
      this.pitchSemitones = pitchSemitones;
    }

    public int getMuteGroup() {
      return muteGroup;
    }

    public void setMuteGroup(int muteGroup) {
      this.muteGroup = muteGroup;
    }

    public EnvelopeModel getAdsr() {
      return adsr;
    }

    public void setAdsr(EnvelopeModel adsr) {
      this.adsr = adsr;
    }

    public float getLpfFreq() {
      return lpfFreq;
    }

    public void setLpfFreq(float lpfFreq) {
      this.lpfFreq = lpfFreq;
    }

    public float getLpfRes() {
      return lpfRes;
    }

    public void setLpfRes(float lpfRes) {
      this.lpfRes = lpfRes;
    }

    public float getEqBass() {
      return eqBass;
    }

    public void setEqBass(float eqBass) {
      this.eqBass = eqBass;
    }

    public float getEqTreble() {
      return eqTreble;
    }

    public void setEqTreble(float eqTreble) {
      this.eqTreble = eqTreble;
    }

    public float getSidechainSend() {
      return sidechainSend;
    }

    public void setSidechainSend(float sidechainSend) {
      this.sidechainSend = sidechainSend;
    }
  }

  private final List<KitSound> sounds = new ArrayList<>();

  public KitTrackModel(String name) {
    super(name, TrackType.KIT);
  }

  public List<KitSound> getSounds() {
    return sounds;
  }

  public void addSound(KitSound sound) {
    sounds.add(sound);
  }
}
