package org.deluge.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents an Audio track that can record from Adc and play back via LiSa.
 *
 * <p>In song XML, {@code <audioTrack>} elements appear inside {@code <instruments>} alongside
 * {@code <sound>} and {@code <kit>} elements. Each audioTrack may hold one or more {@code
 * <audioClip>} children inside {@code <sessionClips>}.
 */
public class AudioTrackModel extends TrackModel {

  /** A single audio clip within this track. Mirrors the &lt;audioClip&gt; XML element. */
  public static class AudioClip {
    private String trackName;
    private String filePath;
    private int startSamplePos;
    private int endSamplePos;
    private float attack = 0.0f;
    private int priority = 1;
    private boolean pitchSpeedIndependent = false;
    private boolean overdubsShouldCloneAudioTrack = false;
    private boolean isPlaying = false;
    private boolean isSoloing = false;
    private boolean isArmedForRecording = false;
    private int length = 768; // ticks
    private int colourOffset = 0;
    private int section = 0;
    private boolean beingEdited = false;
    private boolean reversed = false;

    // Per-clip params (from <params> child element)
    private float volume = 1.0f;
    private float pan = 0.0f;
    private float reverbAmount = 0.0f;
    private float sidechainShape = 0.0f;
    private float sidechainVolume = 0.0f;
    private float modFXRate = 0.0f;
    private float modFXDepth = 0.0f;
    private float modFXOffset = 0.0f;
    private float modFXFeedback = 0.0f;
    private float stutterRate = 0.0f;
    private float sampleRateReduction = 0.0f;
    private float bitCrush = 0.0f;
    private float delayRate = 0.0f;
    private float delayFeedback = 0.0f;
    private float lpfFrequency = 20000.0f;
    private float lpfResonance = 0.0f;
    private float hpfFrequency = 20.0f;
    private float hpfResonance = 0.0f;
    private float eqBass = 0.0f;
    private float eqTreble = 0.0f;
    private float eqBassFrequency = 0.0f;
    private float eqTrebleFrequency = 0.0f;

    public AudioClip() {}

    public String getTrackName() {
      return trackName;
    }

    public void setTrackName(String v) {
      this.trackName = v;
    }

    public String getFilePath() {
      return filePath;
    }

    public void setFilePath(String v) {
      this.filePath = v;
    }

    public int getStartSamplePos() {
      return startSamplePos;
    }

    public void setStartSamplePos(int v) {
      this.startSamplePos = v;
    }

    public int getEndSamplePos() {
      return endSamplePos;
    }

    public void setEndSamplePos(int v) {
      this.endSamplePos = v;
    }

    public boolean isReversed() {
      return reversed;
    }

    public void setReversed(boolean reversed) {
      this.reversed = reversed;
    }

    public float getAttack() {
      return attack;
    }

    public void setAttack(float v) {
      this.attack = v;
    }

    public int getPriority() {
      return priority;
    }

    public void setPriority(int v) {
      this.priority = v;
    }

    public boolean isPitchSpeedIndependent() {
      return pitchSpeedIndependent;
    }

    public void setPitchSpeedIndependent(boolean v) {
      this.pitchSpeedIndependent = v;
    }

    public boolean isOverdubsShouldCloneAudioTrack() {
      return overdubsShouldCloneAudioTrack;
    }

    public void setOverdubsShouldCloneAudioTrack(boolean v) {
      this.overdubsShouldCloneAudioTrack = v;
    }

    public boolean isPlaying() {
      return isPlaying;
    }

    public void setPlaying(boolean v) {
      this.isPlaying = v;
    }

    public boolean isSoloing() {
      return isSoloing;
    }

    public void setSoloing(boolean v) {
      this.isSoloing = v;
    }

    public boolean isArmedForRecording() {
      return isArmedForRecording;
    }

    public void setArmedForRecording(boolean v) {
      this.isArmedForRecording = v;
    }

    public int getLength() {
      return length;
    }

    public void setLength(int v) {
      this.length = v;
    }

    public int getColourOffset() {
      return colourOffset;
    }

    public void setColourOffset(int v) {
      this.colourOffset = v;
    }

    public int getSection() {
      return section;
    }

    public void setSection(int v) {
      this.section = v;
    }

    public boolean isBeingEdited() {
      return beingEdited;
    }

    public void setBeingEdited(boolean v) {
      this.beingEdited = v;
    }

    public float getVolume() {
      return volume;
    }

    public void setVolume(float v) {
      this.volume = v;
    }

    public float getPan() {
      return pan;
    }

    public void setPan(float v) {
      this.pan = v;
    }

    public float getReverbAmount() {
      return reverbAmount;
    }

    public void setReverbAmount(float v) {
      this.reverbAmount = v;
    }

    public float getSidechainShape() {
      return sidechainShape;
    }

    public void setSidechainShape(float v) {
      this.sidechainShape = v;
    }

    public float getSidechainVolume() {
      return sidechainVolume;
    }

    public void setSidechainVolume(float v) {
      this.sidechainVolume = v;
    }

    public float getModFXRate() {
      return modFXRate;
    }

    public void setModFXRate(float v) {
      this.modFXRate = v;
    }

    public float getModFXDepth() {
      return modFXDepth;
    }

    public void setModFXDepth(float v) {
      this.modFXDepth = v;
    }

    public float getModFXOffset() {
      return modFXOffset;
    }

    public void setModFXOffset(float v) {
      this.modFXOffset = v;
    }

    public float getModFXFeedback() {
      return modFXFeedback;
    }

    public void setModFXFeedback(float v) {
      this.modFXFeedback = v;
    }

    public float getStutterRate() {
      return stutterRate;
    }

    public void setStutterRate(float v) {
      this.stutterRate = v;
    }

    public float getSampleRateReduction() {
      return sampleRateReduction;
    }

    public void setSampleRateReduction(float v) {
      this.sampleRateReduction = v;
    }

    public float getBitCrush() {
      return bitCrush;
    }

    public void setBitCrush(float v) {
      this.bitCrush = v;
    }

    public float getDelayRate() {
      return delayRate;
    }

    public void setDelayRate(float v) {
      this.delayRate = v;
    }

    public float getDelayFeedback() {
      return delayFeedback;
    }

    public void setDelayFeedback(float v) {
      this.delayFeedback = v;
    }

    public float getLpfFrequency() {
      return lpfFrequency;
    }

    public void setLpfFrequency(float v) {
      this.lpfFrequency = v;
    }

    public float getLpfResonance() {
      return lpfResonance;
    }

    public void setLpfResonance(float v) {
      this.lpfResonance = v;
    }

    public float getHpfFrequency() {
      return hpfFrequency;
    }

    public void setHpfFrequency(float v) {
      this.hpfFrequency = v;
    }

    public float getHpfResonance() {
      return hpfResonance;
    }

    public void setHpfResonance(float v) {
      this.hpfResonance = v;
    }

    public float getEqBass() {
      return eqBass;
    }

    public void setEqBass(float v) {
      this.eqBass = v;
    }

    public float getEqTreble() {
      return eqTreble;
    }

    public void setEqTreble(float v) {
      this.eqTreble = v;
    }

    public float getEqBassFrequency() {
      return eqBassFrequency;
    }

    public void setEqBassFrequency(float v) {
      this.eqBassFrequency = v;
    }

    public float getEqTrebleFrequency() {
      return eqTrebleFrequency;
    }

    public void setEqTrebleFrequency(float v) {
      this.eqTrebleFrequency = v;
    }
  }

  private final List<AudioClip> audioClips = new ArrayList<>();
  private boolean looping = false;
  private float playRate = 1.0f;
  private boolean recording = false;
  private boolean playing = false;

  // Threshold-gated recording (0=OFF, 1=LOW, 2=MEDIUM, 3=HIGH)
  private int thresholdMode = 0;
  private float thresholdLevel = 0.0f;

  public AudioTrackModel(String name) {
    super(name, TrackType.AUDIO);
  }

  public List<AudioClip> getAudioClips() {
    return audioClips;
  }

  public void addAudioClip(AudioClip clip) {
    audioClips.add(clip);
  }

  public boolean isLooping() {
    return looping;
  }

  public void setLooping(boolean v) {
    this.looping = v;
  }

  public float getPlayRate() {
    return playRate;
  }

  public void setPlayRate(float v) {
    this.playRate = v;
  }

  public boolean isRecording() {
    return recording;
  }

  public void setRecording(boolean v) {
    this.recording = v;
  }

  public boolean isPlaying() {
    return playing;
  }

  public void setPlaying(boolean v) {
    this.playing = v;
  }

  public int getThresholdMode() {
    return thresholdMode;
  }

  public void setThresholdMode(int v) {
    this.thresholdMode = Math.max(0, Math.min(3, v));
  }

  public float getThresholdLevel() {
    return thresholdLevel;
  }

  public void setThresholdLevel(float v) {
    this.thresholdLevel = Math.max(0.0f, Math.min(1.0f, v));
  }
}
