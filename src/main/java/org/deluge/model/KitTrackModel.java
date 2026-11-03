package org.deluge.model;

import java.util.ArrayList;
import java.util.List;

/** Represents a Kit track containing multiple distinct drum sounds. */
public class KitTrackModel extends TrackModel {

  private final List<Drum> drums = new ArrayList<>();
  private int selectedDrumIndex = 0;
  private float pan = 0.0f;
  // C kit.cpp:106-131, mod_controllable_audio.cpp:61-63 — GlobalEffectableForClip attributes &
  // kit-level <defaultParams> stored on standalone <kit> presets and <song><instruments><kit>.
  private FilterMode lpfMode = FilterMode.LADDER_24;
  private FilterMode hpfMode = FilterMode.LADDER_12;
  private int filterRoute = 0;
  private String modFxType = "none";
  private int delayPingPong = 1;
  private int delayAnalog = 0;
  private int delaySyncLevel = 7;
  private int delaySyncType = 0;
  private float delayRate = 0.5f;
  private float delayFeedback = 0.0f;
  private float reverbAmount = 0.0f;
  private float bitCrush = 0.0f;
  private float sampleRateReduction = 0.0f;
  // C global_effectable.cpp:72-74, mod_controllable_audio.cpp:505-506 — default unpatched ModFX
  // knobs: rate=-536870912 (0xE0000000 -> 0.375f), depth=0 (0.5f), offset=0 (0.5f), feedback=-2^31
  // (0.0f).
  private float modFxRate = 0.375f;
  private float modFxDepth = 0.5f;
  private float modFxOffset = 0.5f;
  private float modFxFeedback = 0.0f;
  private float lpfFreq = 20000.0f;
  private float lpfRes = 0.0f;
  private float lpfMorph = 0.0f;
  private float hpfFreq = 20.0f;
  private float hpfRes = 0.0f;
  private float hpfMorph = 0.0f;
  private float eqBass = 0.0f;
  private float eqTreble = 0.0f;
  private float eqBassFreq = 0.0f;
  private float eqTrebleFreq = 0.0f;
  private final java.util.Map<String, Integer> rawDefaultParamQ31 = new java.util.HashMap<>();

  public Integer getRawDefaultParamQ31(String key) {
    return rawDefaultParamQ31.get(key);
  }

  public void setRawDefaultParamQ31(String key, int q31) {
    rawDefaultParamQ31.put(key, q31);
  }

  @Override
  public void setVolume(float volume) {
    if (getVolume() != volume) {
      rawDefaultParamQ31.remove("volume");
    }
    super.setVolume(volume);
  }

  @Override
  public float getPan() {
    return pan;
  }

  @Override
  public void setPan(float v) {
    float clamped = Math.max(-1.0f, Math.min(1.0f, v));
    if (this.pan != clamped) {
      rawDefaultParamQ31.remove("pan");
    }
    this.pan = clamped;
  }

  public FilterMode getLpfMode() {
    return lpfMode;
  }

  public void setLpfMode(FilterMode lpfMode) {
    this.lpfMode = lpfMode != null ? lpfMode : FilterMode.LADDER_24;
  }

  public FilterMode getHpfMode() {
    return hpfMode;
  }

  public void setHpfMode(FilterMode hpfMode) {
    this.hpfMode = hpfMode != null ? hpfMode : FilterMode.LADDER_12;
  }

  public int getFilterRoute() {
    return filterRoute;
  }

  public void setFilterRoute(int filterRoute) {
    this.filterRoute = Math.max(0, Math.min(2, filterRoute));
  }

  public String getModFxType() {
    return modFxType;
  }

  public void setModFxType(String modFxType) {
    this.modFxType = modFxType != null ? modFxType : "none";
  }

  public int getDelayPingPong() {
    return delayPingPong;
  }

  public void setDelayPingPong(int delayPingPong) {
    this.delayPingPong = delayPingPong;
  }

  public int getDelayAnalog() {
    return delayAnalog;
  }

  public void setDelayAnalog(int delayAnalog) {
    this.delayAnalog = delayAnalog;
  }

  public int getDelaySyncLevel() {
    return delaySyncLevel;
  }

  public void setDelaySyncLevel(int delaySyncLevel) {
    this.delaySyncLevel = delaySyncLevel;
  }

  public int getDelaySyncType() {
    return delaySyncType;
  }

  public void setDelaySyncType(int delaySyncType) {
    this.delaySyncType = delaySyncType;
  }

  public float getDelayRate() {
    return delayRate;
  }

  public void setDelayRate(float delayRate) {
    if (this.delayRate != delayRate) {
      rawDefaultParamQ31.remove("delay.rate");
    }
    this.delayRate = delayRate;
  }

  public float getDelayFeedback() {
    return delayFeedback;
  }

  public void setDelayFeedback(float delayFeedback) {
    if (this.delayFeedback != delayFeedback) {
      rawDefaultParamQ31.remove("delay.feedback");
    }
    this.delayFeedback = delayFeedback;
  }

  public float getReverbAmount() {
    return reverbAmount;
  }

  public void setReverbAmount(float reverbAmount) {
    float clamped = Math.max(0.0f, Math.min(1.0f, reverbAmount));
    if (this.reverbAmount != clamped) {
      rawDefaultParamQ31.remove("reverbAmount");
    }
    this.reverbAmount = clamped;
  }

  public float getBitCrush() {
    return bitCrush;
  }

  public void setBitCrush(float bitCrush) {
    float clamped = Math.max(0.0f, Math.min(1.0f, bitCrush));
    if (this.bitCrush != clamped) {
      rawDefaultParamQ31.remove("bitCrush");
    }
    this.bitCrush = clamped;
  }

  public float getSampleRateReduction() {
    return sampleRateReduction;
  }

  public void setSampleRateReduction(float sampleRateReduction) {
    float clamped = Math.max(0.0f, Math.min(1.0f, sampleRateReduction));
    if (this.sampleRateReduction != clamped) {
      rawDefaultParamQ31.remove("sampleRateReduction");
    }
    this.sampleRateReduction = clamped;
  }

  public float getModFxRate() {
    return modFxRate;
  }

  public void setModFxRate(float modFxRate) {
    float clamped = Math.max(0.0f, Math.min(1.0f, modFxRate));
    if (this.modFxRate != clamped) {
      rawDefaultParamQ31.remove("modFXRate");
    }
    this.modFxRate = clamped;
  }

  public float getModFxDepth() {
    return modFxDepth;
  }

  public void setModFxDepth(float modFxDepth) {
    float clamped = Math.max(0.0f, Math.min(1.0f, modFxDepth));
    if (this.modFxDepth != clamped) {
      rawDefaultParamQ31.remove("modFXDepth");
    }
    this.modFxDepth = clamped;
  }

  public float getModFxOffset() {
    return modFxOffset;
  }

  public void setModFxOffset(float modFxOffset) {
    float clamped = Math.max(0.0f, Math.min(1.0f, modFxOffset));
    if (this.modFxOffset != clamped) {
      rawDefaultParamQ31.remove("modFXOffset");
    }
    this.modFxOffset = clamped;
  }

  public float getModFxFeedback() {
    return modFxFeedback;
  }

  public void setModFxFeedback(float modFxFeedback) {
    float clamped = Math.max(0.0f, Math.min(1.0f, modFxFeedback));
    if (this.modFxFeedback != clamped) {
      rawDefaultParamQ31.remove("modFXFeedback");
    }
    this.modFxFeedback = clamped;
  }

  public float getLpfFreq() {
    return lpfFreq;
  }

  public void setLpfFreq(float lpfFreq) {
    if (this.lpfFreq != lpfFreq) {
      rawDefaultParamQ31.remove("lpf.frequency");
    }
    this.lpfFreq = lpfFreq;
  }

  public float getLpfRes() {
    return lpfRes;
  }

  public void setLpfRes(float lpfRes) {
    if (this.lpfRes != lpfRes) {
      rawDefaultParamQ31.remove("lpf.resonance");
    }
    this.lpfRes = lpfRes;
  }

  public float getLpfMorph() {
    return lpfMorph;
  }

  public void setLpfMorph(float lpfMorph) {
    if (this.lpfMorph != lpfMorph) {
      rawDefaultParamQ31.remove("lpf.morph");
    }
    this.lpfMorph = lpfMorph;
  }

  public float getHpfFreq() {
    return hpfFreq;
  }

  public void setHpfFreq(float hpfFreq) {
    if (this.hpfFreq != hpfFreq) {
      rawDefaultParamQ31.remove("hpf.frequency");
    }
    this.hpfFreq = hpfFreq;
  }

  public float getHpfRes() {
    return hpfRes;
  }

  public void setHpfRes(float hpfRes) {
    if (this.hpfRes != hpfRes) {
      rawDefaultParamQ31.remove("hpf.resonance");
    }
    this.hpfRes = hpfRes;
  }

  public float getHpfMorph() {
    return hpfMorph;
  }

  public void setHpfMorph(float hpfMorph) {
    if (this.hpfMorph != hpfMorph) {
      rawDefaultParamQ31.remove("hpf.morph");
    }
    this.hpfMorph = hpfMorph;
  }

  public float getEqBass() {
    return eqBass;
  }

  public void setEqBass(float eqBass) {
    if (this.eqBass != eqBass) {
      rawDefaultParamQ31.remove("equalizer.bass");
    }
    this.eqBass = eqBass;
  }

  public float getEqTreble() {
    return eqTreble;
  }

  public void setEqTreble(float eqTreble) {
    if (this.eqTreble != eqTreble) {
      rawDefaultParamQ31.remove("equalizer.treble");
    }
    this.eqTreble = eqTreble;
  }

  public float getEqBassFreq() {
    return eqBassFreq;
  }

  public void setEqBassFreq(float eqBassFreq) {
    if (this.eqBassFreq != eqBassFreq) {
      rawDefaultParamQ31.remove("equalizer.bassFrequency");
    }
    this.eqBassFreq = eqBassFreq;
  }

  public float getEqTrebleFreq() {
    return eqTrebleFreq;
  }

  public void setEqTrebleFreq(float eqTrebleFreq) {
    if (this.eqTrebleFreq != eqTrebleFreq) {
      rawDefaultParamQ31.remove("equalizer.trebleFrequency");
    }
    this.eqTrebleFreq = eqTrebleFreq;
  }

  /**
   * The kit clip's own arpeggiator (C InstrumentClip::arpSettings, the kit arp —
   * kit.cpp:1136-1222); null = the file had none (the C default, OFF).
   */
  private ArpModel arp;

  public ArpModel getArp() {
    return arp;
  }

  public void setArp(ArpModel arp) {
    ArpModel oldArp = this.arp != null ? this.arp : ArpModel.defaultConfig();
    if (arp != null) {
      for (int p = org.deluge.firmware2.Param.UNPATCHED_ARP_GATE;
          p <= org.deluge.firmware2.Param.UNPATCHED_SPREAD_VELOCITY;
          p++) {
        if (org.deluge.xml.ArpXmlCodec.isUnpatchedArpFieldChanged(oldArp, arp, p)) {
          String tag = Drum.unpatchedArpXmlTag(p);
          if (tag != null) {
            rawDefaultParamQ31.put(tag, org.deluge.xml.ArpXmlCodec.unpatchedQ31FromArp(arp, p));
          }
        }
      }
      if (Float.compare(oldArp.rate(), arp.rate()) != 0) {
        rawDefaultParamQ31.put(
            "arpeggiatorRate",
            org.deluge.xml.DelugeHexMapper.unipolarFloatToQ31Unified(arp.rate()));
      }
    }
    this.arp = arp;
  }

  /** Sets {@link #arp} during XML parsing without overwriting raw {@code <defaultParams>} Q31s. */
  public void setArpFromXml(ArpModel arp) {
    this.arp = arp;
  }

  public KitTrackModel(String name) {
    super(name, TrackType.KIT);
    // C global_effectable.cpp:81 — UNPATCHED_VOLUME initializes to 0 ("half of the way up" ->
    // 0.5f),
    // matching GlobalEffectable.kitVolume = 0 so new kits do not jump +12 dB on song save/reload.
    setVolume(0.5f);
  }

  public int getSelectedDrumIndex() {
    return selectedDrumIndex;
  }

  public void setSelectedDrumIndex(int selectedDrumIndex) {
    this.selectedDrumIndex = selectedDrumIndex;
  }

  public List<Drum> getDrums() {
    return drums;
  }

  public void addDrum(Drum drum) {
    drums.add(drum);
  }

  /**
   * Convenience: returns the sample path of the first SoundDrum, or empty string if there are no
   * SoundDrum entries.
   */
  public String getSamplePath() {
    for (Drum d : drums) {
      if (d instanceof SoundDrum sd) {
        return sd.getSamplePath();
      }
    }
    return "";
  }
}
