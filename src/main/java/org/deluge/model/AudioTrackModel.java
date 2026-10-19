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
    private String clipName;
    private String trackName;
    private String filePath;
    private int startSamplePos;
    private int endSamplePos;
    private float attack = 0.0f;
    private int attackRaw = Integer.MIN_VALUE; // C audio_clip.cpp:63 — default instant attack
    private boolean linearInterpolation = false;
    private int priority = 1;
    private boolean pitchSpeedIndependent = false;
    private boolean overdubsShouldCloneAudioTrack = false;
    private boolean isPlaying = false;
    private boolean isSoloing = false;
    private boolean isArmedForRecording = false;
    private int length = 768; // ticks
    private int colourOffset = 0;
    private int section = 0; // C clip.cpp:51 (255 = arrangement-only, clip.cpp:204-206)
    private boolean arrangementOnly = false;
    private int transpose = 0; // C audio_clip.cpp:1074-1076, 1172-1174
    private int cents = 0; // C audio_clip.cpp:1077-1079, 1176-1178
    private ClipModel.PlayDirection sequenceDirection = ClipModel.PlayDirection.FORWARD;

    /**
     * C Clip::launchStyle (clip.cpp:60, 764-766; functions.cpp:1283-1310): "default", "fill" or
     * "once". Round-tripped; the session launch behaviour of FILL / ONCE is not yet ported.
     */
    private String launchStyle = "default";

    public String getLaunchStyle() {
      return launchStyle;
    }

    /** C stringToLaunchStyle: anything other than "fill" / "once" is DEFAULT. */
    public void setLaunchStyle(String s) {
      this.launchStyle = ("fill".equals(s) || "once".equals(s)) ? s : "default";
    }

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
    private final java.util.Map<String, List<org.deluge.modulation.automation.ParamNode>>
        automationCurves = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, String> rawParamHex = new java.util.LinkedHashMap<>();

    public AudioClip() {}

    public String getClipName() {
      return clipName;
    }

    public void setClipName(String v) {
      this.clipName = v;
    }

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
      float clamped = Math.max(0.0f, Math.min(1.0f, v));
      this.attack = clamped;
      this.attackRaw =
          clamped <= 0.0f
              ? Integer.MIN_VALUE
              : (int)
                  Math.round(
                      clamped * ((double) Integer.MAX_VALUE - (double) Integer.MIN_VALUE)
                          + (double) Integer.MIN_VALUE);
    }

    /** C AudioClip::attack (audio_clip.h:110, audio_clip.cpp:63, 1071, 1161). */
    public int getAttackRaw() {
      return attackRaw;
    }

    public void setAttackRaw(int raw) {
      this.attackRaw = raw;
      this.attack =
          (float)
              (((double) raw - (double) Integer.MIN_VALUE)
                  / ((double) Integer.MAX_VALUE - (double) Integer.MIN_VALUE));
    }

    /**
     * C AudioClip::sampleControls.interpolationMode == InterpolationMode::LINEAR
     * (audio_clip.cpp:1065-1067, 1154-1158).
     */
    public boolean isLinearInterpolation() {
      return linearInterpolation;
    }

    public void setLinearInterpolation(boolean linearInterpolation) {
      this.linearInterpolation = linearInterpolation;
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
      this.arrangementOnly = (v == 255);
      if (this.arrangementOnly) {
        this.isPlaying = false;
      }
    }

    public boolean isArrangementOnly() {
      return arrangementOnly;
    }

    public void setArrangementOnly(boolean arrangementOnly) {
      this.arrangementOnly = arrangementOnly;
      if (arrangementOnly) {
        // C clip.cpp:204-206, song.cpp:2135 — arrangement-only clips always have section == 255.
        this.section = 255;
        this.isPlaying = false;
      } else if (this.section == 255) {
        // C clip.cpp:51
        this.section = 0;
      }
    }

    public int getTranspose() {
      return transpose;
    }

    public void setTranspose(int transpose) {
      this.transpose = transpose;
    }

    public int getCents() {
      return cents;
    }

    public void setCents(int cents) {
      this.cents = cents;
    }

    public ClipModel.PlayDirection getSequenceDirection() {
      return sequenceDirection;
    }

    public void setSequenceDirection(ClipModel.PlayDirection sequenceDirection) {
      this.sequenceDirection =
          sequenceDirection != null ? sequenceDirection : ClipModel.PlayDirection.FORWARD;
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
      rawParamHex.remove("volume");
    }

    public float getPan() {
      return pan;
    }

    public void setPan(float v) {
      this.pan = v;
      rawParamHex.remove("pan");
    }

    public float getReverbAmount() {
      return reverbAmount;
    }

    public void setReverbAmount(float v) {
      this.reverbAmount = v;
      rawParamHex.remove("reverbAmount");
    }

    public float getSidechainShape() {
      return sidechainShape;
    }

    public void setSidechainShape(float v) {
      this.sidechainShape = v;
      rawParamHex.remove("sidechainCompressorShape");
    }

    public float getSidechainVolume() {
      return sidechainVolume;
    }

    public void setSidechainVolume(float v) {
      this.sidechainVolume = v;
      rawParamHex.remove("sidechainCompressorVolume");
    }

    public float getModFXRate() {
      return modFXRate;
    }

    public void setModFXRate(float v) {
      this.modFXRate = v;
      rawParamHex.remove("modFXRate");
    }

    public float getModFXDepth() {
      return modFXDepth;
    }

    public void setModFXDepth(float v) {
      this.modFXDepth = v;
      rawParamHex.remove("modFXDepth");
    }

    public float getModFXOffset() {
      return modFXOffset;
    }

    public void setModFXOffset(float v) {
      this.modFXOffset = v;
      rawParamHex.remove("modFXOffset");
    }

    public float getModFXFeedback() {
      return modFXFeedback;
    }

    public void setModFXFeedback(float v) {
      this.modFXFeedback = v;
      rawParamHex.remove("modFXFeedback");
    }

    public float getStutterRate() {
      return stutterRate;
    }

    public void setStutterRate(float v) {
      this.stutterRate = v;
      rawParamHex.remove("stutterRate");
    }

    public float getSampleRateReduction() {
      return sampleRateReduction;
    }

    public void setSampleRateReduction(float v) {
      this.sampleRateReduction = v;
      rawParamHex.remove("sampleRateReduction");
    }

    public float getBitCrush() {
      return bitCrush;
    }

    public void setBitCrush(float v) {
      this.bitCrush = v;
      rawParamHex.remove("bitCrush");
    }

    public float getDelayRate() {
      return delayRate;
    }

    public void setDelayRate(float v) {
      this.delayRate = v;
      rawParamHex.remove("delayRate");
    }

    public float getDelayFeedback() {
      return delayFeedback;
    }

    public void setDelayFeedback(float v) {
      this.delayFeedback = v;
      rawParamHex.remove("delayFeedback");
    }

    public float getLpfFrequency() {
      return lpfFrequency;
    }

    public void setLpfFrequency(float v) {
      this.lpfFrequency = v;
      rawParamHex.remove("lpfFrequency");
    }

    public float getLpfResonance() {
      return lpfResonance;
    }

    public void setLpfResonance(float v) {
      this.lpfResonance = v;
      rawParamHex.remove("lpfResonance");
    }

    public float getHpfFrequency() {
      return hpfFrequency;
    }

    public void setHpfFrequency(float v) {
      this.hpfFrequency = v;
      rawParamHex.remove("hpfFrequency");
    }

    public float getHpfResonance() {
      return hpfResonance;
    }

    public void setHpfResonance(float v) {
      this.hpfResonance = v;
      rawParamHex.remove("hpfResonance");
    }

    public float getEqBass() {
      return eqBass;
    }

    public void setEqBass(float v) {
      this.eqBass = v;
      rawParamHex.remove("eqBass");
    }

    public float getEqTreble() {
      return eqTreble;
    }

    public void setEqTreble(float v) {
      this.eqTreble = v;
      rawParamHex.remove("eqTreble");
    }

    public float getEqBassFrequency() {
      return eqBassFrequency;
    }

    public void setEqBassFrequency(float v) {
      this.eqBassFrequency = v;
      rawParamHex.remove("eqBassFrequency");
    }

    public float getEqTrebleFrequency() {
      return eqTrebleFrequency;
    }

    public void setEqTrebleFrequency(float v) {
      this.eqTrebleFrequency = v;
      rawParamHex.remove("eqTrebleFrequency");
    }

    public java.util.Map<String, List<org.deluge.modulation.automation.ParamNode>>
        getAutomationCurves() {
      return automationCurves;
    }

    public void setAutomationCurve(
        String param, List<org.deluge.modulation.automation.ParamNode> nodes) {
      if (nodes == null || nodes.isEmpty()) {
        automationCurves.remove(param);
      } else {
        automationCurves.put(param, new ArrayList<>(nodes));
      }
    }

    public java.util.Map<String, String> getRawParamHex() {
      return rawParamHex;
    }

    public void setRawParamHex(String param, String hex) {
      if (hex == null || hex.isBlank()) {
        rawParamHex.remove(param);
      } else {
        rawParamHex.put(param, hex);
      }
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
