package org.deluge.xml;

import static org.deluge.xml.DelugeXmlUtil.*;

import java.io.InputStream;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.deluge.firmware2.Param;
import org.deluge.model.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class InstrumentXmlParser {
  private static final Logger LOG = Logger.getLogger(InstrumentXmlParser.class.getName());

  private static final List<FieldBinding<?>> DIRECT_BINDINGS =
      List.of(
          FieldBinding.attrOrChild(
              "osc1", "type", SynthTrackModel::setOsc1Type, String::toUpperCase),
          // attrOrChild like osc1 — the childText-only binding silently dropped attribute-style
          // <osc2 type="..."> (song-embedded / newer preset format), leaving osc2 as NONE and
          // muting the second oscillator of every two-osc patch parsed from a song.
          FieldBinding.attrOrChild(
              "osc2", "type", SynthTrackModel::setOsc2Type, String::toUpperCase));

  private static final List<FieldBinding<?>> DEFAULT_PARAMS_BINDINGS =
      List.of(
          FieldBinding.hexHz("defaultParams", "lpfFrequency", SynthTrackModel::setLpfFreq),
          FieldBinding.hexFloat("defaultParams", "lpfResonance", SynthTrackModel::setLpfRes),
          FieldBinding.hexHz("defaultParams", "hpfFrequency", SynthTrackModel::setHpfFreq),
          FieldBinding.hexFloat("defaultParams", "hpfResonance", SynthTrackModel::setHpfRes),
          FieldBinding.hexFloat(
              "defaultParams", "modulator1Feedback", SynthTrackModel::setModulator1Feedback),
          FieldBinding.hexFloat(
              "defaultParams", "modulator2Amount", SynthTrackModel::setModulator2Amount),
          FieldBinding.hexFloat(
              "defaultParams", "modulator2Feedback", SynthTrackModel::setModulator2Feedback),
          FieldBinding.hexFloat(
              "defaultParams", "carrier1Feedback", SynthTrackModel::setCarrier1Feedback),
          FieldBinding.hexFloat(
              "defaultParams", "carrier2Feedback", SynthTrackModel::setCarrier2Feedback),
          FieldBinding.hexFloat("defaultParams", "oscAVolume", SynthTrackModel::setOscAVolume),
          FieldBinding.hexFloat("defaultParams", "oscBVolume", SynthTrackModel::setOscBVolume),
          FieldBinding.hexFloat("defaultParams", "noiseVolume", SynthTrackModel::setNoiseVol),
          FieldBinding.hexFloat("defaultParams", "volume", SynthTrackModel::setVolume),
          FieldBinding.hexFloatBipolar("defaultParams", "pan", SynthTrackModel::setPan),
          FieldBinding.hexFloat("defaultParams", "portamento", SynthTrackModel::setPortamento),
          FieldBinding.hexFloat("defaultParams", "modFXRate", SynthTrackModel::setModFxRate),
          FieldBinding.hexFloat("defaultParams", "modFXDepth", SynthTrackModel::setModFxDepth),
          FieldBinding.hexFloat(
              "defaultParams", "modFXFeedback", SynthTrackModel::setModFxFeedback),
          FieldBinding.hexFloat("defaultParams", "reverbAmount", SynthTrackModel::setReverbSend),
          FieldBinding.hexFloat(
              "defaultParams",
              "stutterRate",
              (track, val) -> track.getStutter().setStutterRate(val)),
          FieldBinding.hexFloat(
              "defaultParams", "sampleRateReduction", SynthTrackModel::setSampleRateReduction),
          FieldBinding.hexFloat("defaultParams", "bitCrush", SynthTrackModel::setBitCrush),
          FieldBinding.hexFloat("defaultParams", "delayRate", SynthTrackModel::setDelaySend),
          FieldBinding.hexFloat("defaultParams", "waveIndex", SynthTrackModel::setWaveIndex),
          FieldBinding.hexFloat("defaultParams", "arpeggiatorRate", SynthTrackModel::setArpRate));

  public static SynthTrackModel parseSynth(java.io.File xmlFile) throws Exception {
    return parseSynth(new java.io.FileInputStream(xmlFile), xmlFile.getName().replace(".XML", ""));
  }

  public static SynthTrackModel parseSynth(InputStream is, String name) throws Exception {
    Document doc = parseXml(is);
    Element root = doc.getDocumentElement();

    Element soundNode = root;
    if (!root.getTagName().equals("sound")) {
      NodeList sounds = root.getElementsByTagName("sound");
      if (sounds.getLength() > 0) {
        soundNode = (Element) sounds.item(0);
      }
    }

    SynthTrackModel synth = new SynthTrackModel(name);
    populateSynth(soundNode, synth);
    return synth;
  }

  private static final java.util.Map<String, Integer> SOUNDPARAMS_RAW_PATCHED =
      new java.util.LinkedHashMap<>();

  static {
    SOUNDPARAMS_RAW_PATCHED.put("lpfFrequency", Param.LOCAL_LPF_FREQ);
    SOUNDPARAMS_RAW_PATCHED.put("lpfResonance", Param.LOCAL_LPF_RESONANCE);
    SOUNDPARAMS_RAW_PATCHED.put("lpfMorph", Param.LOCAL_LPF_MORPH);
    SOUNDPARAMS_RAW_PATCHED.put("hpfFrequency", Param.LOCAL_HPF_FREQ);
    SOUNDPARAMS_RAW_PATCHED.put("hpfResonance", Param.LOCAL_HPF_RESONANCE);
    SOUNDPARAMS_RAW_PATCHED.put("hpfMorph", Param.LOCAL_HPF_MORPH);
    SOUNDPARAMS_RAW_PATCHED.put("volume", Param.LOCAL_VOLUME);
    SOUNDPARAMS_RAW_PATCHED.put("pan", Param.LOCAL_PAN);
    SOUNDPARAMS_RAW_PATCHED.put("oscAVolume", Param.LOCAL_OSC_A_VOLUME);
    SOUNDPARAMS_RAW_PATCHED.put("oscBVolume", Param.LOCAL_OSC_B_VOLUME);
    SOUNDPARAMS_RAW_PATCHED.put("noiseVolume", Param.LOCAL_NOISE_VOLUME);
    SOUNDPARAMS_RAW_PATCHED.put("oscAPhaseWidth", Param.LOCAL_OSC_A_PHASE_WIDTH);
    SOUNDPARAMS_RAW_PATCHED.put("oscBPhaseWidth", Param.LOCAL_OSC_B_PHASE_WIDTH);
    SOUNDPARAMS_RAW_PATCHED.put("oscAWavetablePosition", Param.LOCAL_OSC_A_WAVE_INDEX);
    SOUNDPARAMS_RAW_PATCHED.put("oscBWavetablePosition", Param.LOCAL_OSC_B_WAVE_INDEX);
    SOUNDPARAMS_RAW_PATCHED.put("oscAPitch", Param.LOCAL_OSC_A_PITCH_ADJUST);
    SOUNDPARAMS_RAW_PATCHED.put("oscBPitch", Param.LOCAL_OSC_B_PITCH_ADJUST);
    SOUNDPARAMS_RAW_PATCHED.put("pitch", Param.LOCAL_PITCH_ADJUST);
    SOUNDPARAMS_RAW_PATCHED.put("modulator1Pitch", Param.LOCAL_MODULATOR_0_PITCH_ADJUST);
    SOUNDPARAMS_RAW_PATCHED.put("modulator2Pitch", Param.LOCAL_MODULATOR_1_PITCH_ADJUST);
    SOUNDPARAMS_RAW_PATCHED.put("modFXRate", Param.GLOBAL_MOD_FX_RATE);
    SOUNDPARAMS_RAW_PATCHED.put("modFXDepth", Param.GLOBAL_MOD_FX_DEPTH);
    SOUNDPARAMS_RAW_PATCHED.put("modFXOffset", Param.UNPATCHED_MOD_FX_OFFSET);
    SOUNDPARAMS_RAW_PATCHED.put("modFXFeedback", Param.UNPATCHED_MOD_FX_FEEDBACK);
    SOUNDPARAMS_RAW_PATCHED.put("delayRate", Param.GLOBAL_DELAY_RATE);
    SOUNDPARAMS_RAW_PATCHED.put("delayFeedback", Param.GLOBAL_DELAY_FEEDBACK);
    SOUNDPARAMS_RAW_PATCHED.put("reverbAmount", Param.GLOBAL_REVERB_AMOUNT);
    SOUNDPARAMS_RAW_PATCHED.put("stutterRate", Param.UNPATCHED_STUTTER_RATE);
    SOUNDPARAMS_RAW_PATCHED.put("sampleRateReduction", Param.UNPATCHED_SAMPLE_RATE_REDUCTION);
    SOUNDPARAMS_RAW_PATCHED.put("bitCrush", Param.UNPATCHED_BITCRUSHING);
    SOUNDPARAMS_RAW_PATCHED.put("eqBass", Param.UNPATCHED_BASS);
    SOUNDPARAMS_RAW_PATCHED.put("eqTreble", Param.UNPATCHED_TREBLE);
    SOUNDPARAMS_RAW_PATCHED.put("eqBassFrequency", Param.UNPATCHED_BASS_FREQ);
    SOUNDPARAMS_RAW_PATCHED.put("eqTrebleFrequency", Param.UNPATCHED_TREBLE_FREQ);
    SOUNDPARAMS_RAW_PATCHED.put("sidechainCompressorShape", Param.UNPATCHED_SIDECHAIN_SHAPE);
    SOUNDPARAMS_RAW_PATCHED.put("sidechainCompressorVolume", Param.UNPATCHED_SIDECHAIN_VOLUME);
  }

  public static void populateSynth(Element soundNode, SynthTrackModel synth) {
    // Direct child bindings (osc1 attr/or child type, osc2 child type)
    applyDirectBindings(soundNode, synth);

    // ── DX7 patch (hex string from <osc1 dx7patch="...">) ──
    // ── Oscillator retrigger phase (from <osc1 retrigPhase="...">) ──
    NodeList osc1List = soundNode.getElementsByTagName("osc1");
    if (osc1List.getLength() > 0) {
      Element osc1 = (Element) osc1List.item(0);
      // Multisample oscillator (<sampleRanges> keyzones): capture verbatim so it survives the song
      // round-trip unchanged (our model doesn't re-model keyzones).
      if (osc1.getElementsByTagName("sampleRange").getLength() > 0) {
        synth.setOsc1RawXml(nodeToXmlString(osc1));
      }
      NodeList osc2L = soundNode.getElementsByTagName("osc2");
      if (osc2L.getLength() > 0
          && ((Element) osc2L.item(0)).getElementsByTagName("sampleRange").getLength() > 0) {
        synth.setOsc2RawXml(nodeToXmlString((Element) osc2L.item(0)));
      }
      if (osc1.hasAttribute("dx7patch")) {
        synth.setDx7Patch(osc1.getAttribute("dx7patch"));
      }
      // DX7 engine mode (dx7enginemode attribute on osc1 — write-on-demand, conditional on
      // non-zero)
      String dx7EngineModeStr = osc1.getAttribute("dx7enginemode");
      if (dx7EngineModeStr != null && !dx7EngineModeStr.isBlank()) {
        try {
          synth.setEngineType(Integer.parseInt(dx7EngineModeStr));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
      // DX7 random detune (dx7randomdetune attribute on osc1)
      String dx7RandStr = osc1.getAttribute("dx7randomdetune");
      if (dx7RandStr != null && !dx7RandStr.isBlank()) {
        try {
          synth.setDx7RandomDetune(Integer.parseInt(dx7RandStr));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
      // retrigPhase as child element (firmware XML format)
      String rpStr = attrOrChildText(osc1, "retrigPhase");
      if (rpStr != null && !rpStr.isBlank()) {
        try {
          synth.setOsc1RetrigPhase((int) Long.parseLong(rpStr.trim()));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }

      // Osc1 sample playback params (loopMode, reversed, timeStretch)
      String lmStr = osc1.getAttribute("loopMode");
      if (lmStr != null && !lmStr.isBlank()) {
        try {
          synth.setOsc1LoopMode(Integer.parseInt(lmStr));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
      readAttrBool(osc1, "reversed", synth::setOsc1Reversed);
      String tsStr = osc1.getAttribute("timeStretchEnable");
      if (tsStr != null && !tsStr.isBlank()) {
        synth.setOsc1TimeStretch("true".equalsIgnoreCase(tsStr) || "1".equals(tsStr));
      }
      readAttrFloatHex(osc1, "timeStretchAmount", synth::setOsc1TimeStretchAmount, true);
      readAttrBool(osc1, "linearInterpolation", v -> synth.setOsc1LinearInterpolation(v));
      // Osc1 transpose (semitones) + cents (fine detune). Presets use the CHILD-element form
      // (<osc1><transpose>12</transpose>), so read attribute-or-child. Carrier-A transpose is
      // common
      // in FM patches; missing it put the FM carrier at the wrong pitch (wrong FM spectrum).
      String osc1Trans = attrOrChildText(osc1, "transpose");
      if (osc1Trans != null && !osc1Trans.isBlank()) {
        try {
          synth.setOsc1Transpose(Integer.parseInt(osc1Trans.trim()));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
      String centsStr = attrOrChildText(osc1, "cents");
      if (centsStr != null && !centsStr.isBlank()) {
        try {
          synth.setOsc1Cents(Integer.parseInt(centsStr.trim()));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
      // Osc1 sample fileName (attribute or child element)
      String osc1fn = osc1.getAttribute("fileName");
      if (osc1fn == null || osc1fn.isBlank()) {
        osc1fn = getChildText(osc1, "fileName");
      }
      if (osc1fn != null && !osc1fn.isBlank()) {
        synth.setOsc1SamplePath(osc1fn);
      }

      // Parse sampleRanges keyzones for Osc 1
      Element osc1SampleRanges = getFirstChild(osc1, "sampleRanges");
      if (osc1SampleRanges != null) {
        parseSampleRangeZones(osc1SampleRanges, synth.getKeyZones().getOsc1Zones());
      }
    }

    // ── Oscillator retrigger phase for osc2 ──
    NodeList osc2List = soundNode.getElementsByTagName("osc2");
    if (osc2List.getLength() > 0) {
      Element osc2 = (Element) osc2List.item(0);
      String rpStr2 = attrOrChildText(osc2, "retrigPhase");
      if (rpStr2 != null && !rpStr2.isBlank()) {
        try {
          synth.setOsc2RetrigPhase((int) Long.parseLong(rpStr2.trim()));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
      // Osc2 transpose (semitones) — attribute-or-child (presets use <osc2><transpose>N).
      String transStr = attrOrChildText(osc2, "transpose");
      if (transStr != null && !transStr.isBlank()) {
        try {
          synth.setOsc2Transpose(Integer.parseInt(transStr.trim()));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
      // Osc2 cents (fine detune)
      String centsStr2 = attrOrChildText(osc2, "cents");
      if (centsStr2 != null && !centsStr2.isBlank()) {
        try {
          synth.setOsc2Cents(Integer.parseInt(centsStr2));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
      // Osc2 oscillatorSync (hard sync on osc2, firmware writes only when s==1 && oscillatorSync)
      readAttrBool(osc2, "oscillatorSync", v -> synth.setOscillatorSync(v));
      // Osc2 sample-playback attrs (loopMode, reversed, timeStretch, linearInterpolation)
      String osc2lm = osc2.getAttribute("loopMode");
      if (osc2lm != null && !osc2lm.isBlank()) {
        try {
          synth.setOsc2LoopMode(Integer.parseInt(osc2lm));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
      readAttrBool(osc2, "reversed", synth::setOsc2Reversed);
      readAttrBool(osc2, "timeStretchEnable", synth::setOsc2TimeStretch);
      readAttrFloatHex(osc2, "timeStretchAmount", synth::setOsc2TimeStretchAmount, true);
      readAttrBool(osc2, "linearInterpolation", synth::setOsc2LinearInterpolation);
      // Osc2 sample fileName (attribute or child element)
      String osc2fn = osc2.getAttribute("fileName");
      if (osc2fn == null || osc2fn.isBlank()) {
        osc2fn = getChildText(osc2, "fileName");
      }
      if (osc2fn != null && !osc2fn.isBlank()) {
        synth.setOsc2SamplePath(osc2fn);
      }

      // Parse sampleRanges keyzones for Osc 2
      Element osc2SampleRanges = getFirstChild(osc2, "sampleRanges");
      if (osc2SampleRanges != null) {
        parseSampleRangeZones(osc2SampleRanges, synth.getKeyZones().getOsc2Zones());
      }
    }

    // ── Unison ──
    Element unisonEl = getFirstChild(soundNode, "unison");
    if (unisonEl != null) {
      String numStr =
          unisonEl.hasAttribute("num")
              ? unisonEl.getAttribute("num")
              : getChildText(unisonEl, "num");
      if (numStr != null && !numStr.isBlank()) {
        try {
          synth.getUnison().setUnisonNum(Integer.parseInt(numStr.trim()));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "Error parsing unison num", e);
        }
      }
      String detuneStr =
          unisonEl.hasAttribute("detune")
              ? unisonEl.getAttribute("detune")
              : getChildText(unisonEl, "detune");
      if (detuneStr != null && !detuneStr.isBlank()) {
        try {
          String val = detuneStr.trim();
          float dVal;
          if (val.startsWith("0x") || val.startsWith("0X")) {
            dVal = Math.abs(DelugeHexMapper.hexToFloat(val));
          } else {
            dVal = Float.parseFloat(val);
          }
          synth.getUnison().setUnisonDetune(dVal);
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "Error parsing unison detune", e);
        }
      }
      String spreadStr =
          unisonEl.hasAttribute("spread")
              ? unisonEl.getAttribute("spread")
              : getChildText(unisonEl, "spread");
      if (spreadStr != null && !spreadStr.isBlank()) {
        try {
          String val = spreadStr.trim();
          float sVal;
          if (val.startsWith("0x") || val.startsWith("0X")) {
            sVal = Math.abs(DelugeHexMapper.hexToFloat(val));
          } else {
            sVal = Float.parseFloat(val);
          }
          synth.getUnison().setUnisonStereoSpread(sVal);
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "Error parsing unison spread", e);
        }
      }
    }

    // ── Synth Mode ──
    parseSynthMode(soundNode, synth);

    // ── Polyphonic mode ──
    parsePolyphony(soundNode, synth);

    // ── Legacy V1.3.0 presets fallback parameters ──
    parseLegacyParams(soundNode, synth);

    // ── Voice-stealing priority (attribute or child element) ──
    String vpStr = soundNode.getAttribute("voicePriority");
    if (vpStr == null || vpStr.isBlank()) vpStr = getChildText(soundNode, "voicePriority");
    if (vpStr != null && !vpStr.isBlank()) {
      try {
        synth.setVoicePriority(Integer.parseInt(vpStr.trim()));
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing voicePriority", e);
      }
    }

    // ── LPF Mode ──
    parseFilterMode(soundNode, synth);

    // ── FM Modulator 1 ──
    parseModulator1(soundNode, synth);
    parseModulator2(soundNode, synth);

    // ── Synth algorithm (from ProjectSerializer track attribute) ──
    if (soundNode.hasAttribute("synthAlgorithm")) {
      try {
        synth.setSynthAlgorithm(Integer.parseInt(soundNode.getAttribute("synthAlgorithm")));
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }

    // -- Engine type (-1=AUTO, 0=MODERN, 1=VINTAGE) --
    if (soundNode.hasAttribute("engineType")) {
      try {
        synth.setEngineType(Integer.parseInt(soundNode.getAttribute("engineType")));
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }

    // -- Transpose (semitones). New format: attribute on <sound>; old (pre-V3) factory presets
    // carry it as a nested <transpose> child — which was silently dropped, shifting every source
    // AND FM modulator of such presets by the missing amount (e.g. "068 FM Bells 1" plays a full
    // octave high without its <transpose>-12).
    String transposeStr = soundNode.getAttribute("transpose");
    if (transposeStr == null || transposeStr.isBlank()) {
      // DIRECT child only — attrOrChildText/getElementsByTagName would match the first
      // descendant <transpose>, which is osc1's, not the sound's.
      for (org.w3c.dom.Node n = soundNode.getFirstChild(); n != null; n = n.getNextSibling()) {
        if (n instanceof Element ce && "transpose".equals(ce.getTagName())) {
          transposeStr = ce.getTextContent();
          break;
        }
      }
    }
    if (transposeStr != null && !transposeStr.isBlank()) {
      try {
        synth.setTranspose(Integer.parseInt(transposeStr.trim()));
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML transpose", e);
      }
    }

    // -- Max voices (attribute on <sound>) --
    if (soundNode.hasAttribute("maxVoices")) {
      try {
        synth.setMaxVoiceCount(Integer.parseInt(soundNode.getAttribute("maxVoices")));
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }

    // ── FM ratio/amount (from ProjectSerializer track attributes) ──
    // These attributes are our serializer's shorthand, not real Deluge XML. The Deluge format (and
    // the C firmware) carries the modulator pitch as <modulator1><transpose>/<cents> and the depth
    // as the <modulator1Amount> knob — so convert here into those same model fields. A
    // <modulator1>/<modulator1Amount> element later in the file overrides (real format wins).
    if (soundNode.hasAttribute("fmRatio")) {
      try {
        float ratio = Float.parseFloat(soundNode.getAttribute("fmRatio"));
        synth.setFmRatio(ratio);
        if (ratio > 0) {
          int totalCents = Math.round(1200f * (float) (Math.log(ratio) / Math.log(2)));
          int transpose = Math.round(totalCents / 100f);
          synth.setModulator1Transpose(transpose);
          synth.setModulator1Cents(totalCents - transpose * 100);
        }
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }
    if (soundNode.hasAttribute("fmAmount")) {
      try {
        float amount = Float.parseFloat(soundNode.getAttribute("fmAmount"));
        synth.setFmAmount(amount);
        // Serializer round-trip: unipolar amount → bipolar knob (appendHexChildUnipolar).
        synth.setModulator1AmountQ31(
            (int)
                Math.max(
                    Integer.MIN_VALUE,
                    Math.min(Integer.MAX_VALUE, (amount * 2.0 - 1.0) * 2147483647.0)));
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }

    // ── FM feedback params (from ProjectSerializer track attributes, hex-encoded) ──
    String attrM1f = soundNode.getAttribute("modulator1Feedback");
    if (attrM1f != null && !attrM1f.isEmpty()) {
      try {
        synth.setModulator1Feedback(DelugeHexMapper.hexToFloat(attrM1f));
        synth.setModulator1FeedbackQ31(DelugeHexMapper.hexToQ31(attrM1f));
      } catch (Exception e) {
        LOG.log(Level.FINE, "Exception parsing XML hex attribute", e);
      }
    }
    String attrM2a = soundNode.getAttribute("modulator2Amount");
    if (attrM2a != null && !attrM2a.isEmpty()) {
      try {
        synth.setModulator2Amount(DelugeHexMapper.hexToFloat(attrM2a));
        synth.setModulator2AmountQ31(DelugeHexMapper.hexToQ31(attrM2a));
      } catch (Exception e) {
        LOG.log(Level.FINE, "Exception parsing XML hex attribute", e);
      }
    }
    String attrM2f = soundNode.getAttribute("modulator2Feedback");
    if (attrM2f != null && !attrM2f.isEmpty()) {
      try {
        synth.setModulator2Feedback(DelugeHexMapper.hexToFloat(attrM2f));
        synth.setModulator2FeedbackQ31(DelugeHexMapper.hexToQ31(attrM2f));
      } catch (Exception e) {
        LOG.log(Level.FINE, "Exception parsing XML hex attribute", e);
      }
    }
    String attrC1f = soundNode.getAttribute("carrier1Feedback");
    if (attrC1f != null && !attrC1f.isEmpty()) {
      try {
        synth.setCarrier1Feedback(DelugeHexMapper.hexToFloat(attrC1f));
        synth.setCarrier1FeedbackQ31(DelugeHexMapper.hexToQ31(attrC1f));
      } catch (Exception e) {
        LOG.log(Level.FINE, "Exception parsing XML hex attribute", e);
      }
    }
    String attrC2f = soundNode.getAttribute("carrier2Feedback");
    if (attrC2f != null && !attrC2f.isEmpty()) {
      try {
        synth.setCarrier2Feedback(DelugeHexMapper.hexToFloat(attrC2f));
        synth.setCarrier2FeedbackQ31(DelugeHexMapper.hexToQ31(attrC2f));
      } catch (Exception e) {
        LOG.log(Level.FINE, "Exception parsing XML hex attribute", e);
      }
    }

    // -- oscillatorReset (compatibility, tag or attribute) --
    String oscReset = attrOrChildText(soundNode, "oscillatorReset");
    if (oscReset != null && !oscReset.isEmpty()) {
      if ("0".equals(oscReset) || "false".equalsIgnoreCase(oscReset)) {
        synth.setOsc1RetrigPhase(-1);
        synth.setOsc2RetrigPhase(-1);
        synth.setMod1RetrigPhase(-1);
        synth.setMod2RetrigPhase(-1);
      }
    }

    // ── Envelopes 0-3 ──
    parseEnvelopes(soundNode, synth);

    // ── LFOs ──
    parseSynthLfo(soundNode, "lfo1", synth, true);
    parseSynthLfo(soundNode, "lfo2", synth, false);
    parseSynthLfo(soundNode, "lfo3", synth, false);
    parseSynthLfo(soundNode, "lfo4", synth, false);

    // ── Custom LFO Waveform ──
    String waveStr = attrOrChildText(soundNode, "customLfoWave");
    if (waveStr != null && !waveStr.isBlank()) {
      String[] tokens = waveStr.trim().split(",");
      int[] wave = synth.getCustomLfoWave();
      for (int i = 0; i < Math.min(256, tokens.length); i++) {
        try {
          wave[i] = Integer.parseInt(tokens[i].trim());
        } catch (NumberFormatException e) {
          // Keep default on parse error
        }
      }
    }

    // ── Arpeggiator ──
    parseSynthArp(soundNode, synth);

    // ── Compressor ──
    parseSynthCompressor(soundNode, synth);

    // ── Sidechain (at sound level, separate from compressor) ──
    NodeList sidechainNodes = soundNode.getElementsByTagName("sidechain");
    if (sidechainNodes.getLength() > 0) {
      Element sc = (Element) sidechainNodes.item(0);
      readAttrFloatHex(sc, "attack", synth::setSidechainAttack, true);
      readAttrFloatHex(sc, "release", synth::setSidechainRelease, true);
      // The firmware stores attack/release as RAW decimal rate ints (mod_controllable_audio.cpp
      // :892-896 reads them verbatim); the hex-float mapping above is only the UI knob. Keep the
      // raw values for the engine.
      String scAtk = attrOrChildText(sc, "attack");
      if (scAtk != null && !scAtk.isBlank()) {
        try {
          synth.setSidechainAttackRaw(Integer.parseInt(scAtk.trim()));
        } catch (NumberFormatException ignored) {
        }
      }
      String scRel = attrOrChildText(sc, "release");
      if (scRel != null && !scRel.isBlank()) {
        try {
          synth.setSidechainReleaseRaw(Integer.parseInt(scRel.trim()));
        } catch (NumberFormatException ignored) {
        }
      }
      if (sc.hasAttribute("syncLevel")) {
        try {
          synth.setSidechainSyncLevel(Integer.parseInt(sc.getAttribute("syncLevel")));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
      if (sc.hasAttribute("syncType")) {
        try {
          synth.setSidechainSyncType(Integer.parseInt(sc.getAttribute("syncType")));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
    }

    // ── Stutter config (quantized, reverse, pingPong) ──
    parseStutter(soundNode, synth);

    // ── defaultParams bindings (LPF/HPF freq+res, FM feedback params, + extended) ──
    applyDefaultParamsBindings(soundNode, synth);

    // ── Patch Cables ──
    parsePatchCables(soundNode, synth);

    // ── Mod Knobs ──
    parseModKnobs(soundNode, synth);
    parseMidiKnobs(soundNode, synth.getModulation().getMidiKnobs());

    // ── Per-sound delay (the instrument's own <delay> element) ──
    parseSynthDelay(soundNode, synth);

    // ── Equalizer inside defaultParams ──
    Element dpEl = getFirstChild(soundNode, "defaultParams");
    if (dpEl != null) {
      Element eqEl = getFirstChild(dpEl, "equalizer");
      if (eqEl == null) {
        eqEl = getFirstChild(dpEl, "eq");
      }
      if (eqEl != null) {
        readHexFloat(eqEl, "bass", synth::setEqBass);
        readHexFloat(eqEl, "treble", synth::setEqTreble);
      }
    }

    // ── Mod FX Type ──
    String mfxVal = soundNode.getAttribute("modFXType");
    if (mfxVal == null || mfxVal.isEmpty()) {
      mfxVal = soundNode.getAttribute("modFxType");
    }
    if (mfxVal == null || mfxVal.isEmpty()) {
      NodeList mfxNodes = soundNode.getElementsByTagName("modFXType");
      if (mfxNodes.getLength() > 0) {
        mfxVal = mfxNodes.item(0).getTextContent();
      } else {
        NodeList mfxNodesAlt = soundNode.getElementsByTagName("modFxType");
        if (mfxNodesAlt.getLength() > 0) {
          mfxVal = mfxNodesAlt.item(0).getTextContent();
        }
      }
    }
    if (mfxVal != null && !mfxVal.isBlank()) {
      synth.setModFxType(mfxVal.trim().toUpperCase());
    }
  }

  private static void applyDirectBindings(Element soundNode, SynthTrackModel synth) {
    for (FieldBinding<?> b : DIRECT_BINDINGS) {
      b.apply(soundNode, synth);
    }
  }

  private static void applyDefaultParamsBindings(Element soundNode, SynthTrackModel synth) {
    for (FieldBinding<?> b : DEFAULT_PARAMS_BINDINGS) {
      b.apply(soundNode, synth);
    }
    Element dpEl = getFirstChild(soundNode, "defaultParams");
    if (dpEl != null) {
      for (var e : SOUNDPARAMS_RAW_PATCHED.entrySet()) {
        rawKnob(dpEl, e.getKey(), synth, e.getValue());
      }
      String v;
      if ((v = attrOrChildText(dpEl, "modulator1Amount")) != null && !v.isBlank()) {
        synth.setModulator1AmountQ31(DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "modulator2Amount")) != null && !v.isBlank()) {
        synth.setModulator2AmountQ31(DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "modulator1Feedback")) != null && !v.isBlank()) {
        synth.setModulator1FeedbackQ31(DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "modulator2Feedback")) != null && !v.isBlank()) {
        synth.setModulator2FeedbackQ31(DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "carrier1Feedback")) != null && !v.isBlank()) {
        synth.setCarrier1FeedbackQ31(DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "carrier2Feedback")) != null && !v.isBlank()) {
        synth.setCarrier2FeedbackQ31(DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "pitchAdjust")) != null && !v.isBlank()) {
        synth.setPitchAdjustQ31(DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "oscAPitchAdjust")) != null && !v.isBlank()) {
        synth.setOsc1PitchAdjustQ31(DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "oscBPitchAdjust")) != null && !v.isBlank()) {
        synth.setOsc2PitchAdjustQ31(DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "oscAPulseWidth")) != null && !v.isBlank()) {
        synth.setOsc1PhaseWidthQ31(DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "compressorShape")) != null && !v.isBlank()) {
        synth.setCompressorShape(toUnipolar(DelugeHexMapper.hexToFloat(v)));
      }
      if ((v = attrOrChildText(dpEl, "oscBPulseWidth")) != null && !v.isBlank()) {
        synth.setOsc2PhaseWidthQ31(DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "portamento")) != null && !v.isBlank()) {
        synth.setPortamentoQ31(DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "waveFold")) != null && !v.isBlank()) {
        synth.setWaveFoldQ31(DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "lfo1Rate")) != null && !v.isBlank()) {
        synth.getRawKnobs().setLfoRateKnobQ31(0, DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "lfo2Rate")) != null && !v.isBlank()) {
        synth.getRawKnobs().setLfoRateKnobQ31(1, DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "lfo3Rate")) != null && !v.isBlank()) {
        synth.getRawKnobs().setLfoRateKnobQ31(2, DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "lfo4Rate")) != null && !v.isBlank()) {
        synth.getRawKnobs().setLfoRateKnobQ31(3, DelugeHexMapper.hexToQ31(v));
      }
    }
  }

  private static void parseSynthMode(Element soundNode, SynthTrackModel synth) {
    String mode = soundNode.getAttribute("mode");
    if (mode == null || mode.isEmpty()) {
      NodeList modeNodes = soundNode.getElementsByTagName("mode");
      if (modeNodes.getLength() > 0) {
        mode = modeNodes.item(0).getTextContent();
      }
    }
    if (mode != null && !mode.isBlank()) {
      mode = mode.trim().toLowerCase();
      switch (mode) {
        case "fm" -> synth.setSynthMode(1);
        case "ringmod" -> synth.setSynthMode(2);
        default -> synth.setSynthMode(0);
      }
    }
  }

  private static void parsePolyphony(Element soundNode, SynthTrackModel synth) {
    String val = soundNode.getAttribute("polyphonic");
    if (val == null || val.isEmpty()) {
      NodeList polyNodes = soundNode.getElementsByTagName("polyphonic");
      if (polyNodes.getLength() > 0) {
        val = polyNodes.item(0).getTextContent();
      }
    }
    if (val != null && !val.isBlank()) {
      val = val.trim().toLowerCase();
      switch (val) {
        case "mono":
        case "0":
          synth.setPolyphony(SynthTrackModel.PolyphonyMode.MONO);
          break;
        case "legato":
          synth.setPolyphony(SynthTrackModel.PolyphonyMode.LEGATO);
          break;
        default:
          synth.setPolyphony(SynthTrackModel.PolyphonyMode.POLY);
          break;
      }
    }
  }

  private static void parseFilterMode(Element soundNode, SynthTrackModel synth) {
    // ── LPF Mode ──
    readAttrOrChildString(
        soundNode,
        "lpfMode",
        v -> {
          if ("24dB".equalsIgnoreCase(v)) {
            synth.setFilterMode(FilterMode.LADDER_24);
          } else if ("SVF".equalsIgnoreCase(v)) {
            synth.setFilterMode(FilterMode.SVF);
          } else if ("DRIVE".equalsIgnoreCase(v)) {
            synth.setFilterMode(FilterMode.DRIVE);
          } else if ("SVF_BAND".equalsIgnoreCase(v) || "SVF Band".equalsIgnoreCase(v)) {
            synth.setFilterMode(FilterMode.SVF_BAND);
          } else if ("SVF_NOTCH".equalsIgnoreCase(v) || "SVF Notch".equalsIgnoreCase(v)) {
            synth.setFilterMode(FilterMode.SVF_NOTCH);
          } else if ("Off".equalsIgnoreCase(v)) {
            synth.setFilterMode(FilterMode.OFF);
          } else {
            synth.setFilterMode(FilterMode.LADDER_12);
          }
        });

    // ── HPF Mode ──
    readAttrOrChildString(
        soundNode,
        "hpfMode",
        v -> {
          if ("24dB".equalsIgnoreCase(v)) {
            synth.setHpfMode(FilterMode.LADDER_24);
          } else if ("SVF".equalsIgnoreCase(v)) {
            synth.setHpfMode(FilterMode.SVF);
          } else if ("DRIVE".equalsIgnoreCase(v)) {
            synth.setHpfMode(FilterMode.DRIVE);
          } else if ("SVF_BAND".equalsIgnoreCase(v) || "SVF Band".equalsIgnoreCase(v)) {
            synth.setHpfMode(FilterMode.SVF_BAND);
          } else if ("SVF_NOTCH".equalsIgnoreCase(v) || "SVF Notch".equalsIgnoreCase(v)) {
            synth.setHpfMode(FilterMode.SVF_NOTCH);
          } else if ("Off".equalsIgnoreCase(v)) {
            synth.setHpfMode(FilterMode.OFF);
          } else {
            synth.setHpfMode(FilterMode.LADDER_12);
          }
        });

    // ── Filter Route ──
    readAttrOrChildString(
        soundNode,
        "filterRoute",
        v -> {
          if ("L2H".equalsIgnoreCase(v) || "LPF_TO_HPF".equalsIgnoreCase(v)) {
            synth.setFilterRoute(1); // LOW_TO_HIGH
          } else if ("PARALLEL".equalsIgnoreCase(v)) {
            synth.setFilterRoute(2); // PARALLEL
          } else {
            synth.setFilterRoute(0); // HIGH_TO_LOW / H2L
          }
        });
  }

  private static void parseModulator1(Element soundNode, SynthTrackModel synth) {
    NodeList mod1Nodes = soundNode.getElementsByTagName("modulator1");
    if (mod1Nodes.getLength() > 0) {
      Element mod1 = (Element) mod1Nodes.item(0);
      int transpose = attrOrChildInt(mod1, "transpose", 0);
      int cents = attrOrChildInt(mod1, "cents", 0);
      synth.setFmRatio(modulatorRatio(transpose, cents));
      synth.setModulator1Transpose(transpose);
      synth.setModulator1Cents(cents);
      int retrig = attrOrChildInt(mod1, "retrigPhase", 0);
      synth.setMod1RetrigPhase(retrig);
    }
    NodeList mod1AmtNodes = soundNode.getElementsByTagName("modulator1Amount");
    if (mod1AmtNodes.getLength() > 0) {
      String txt = mod1AmtNodes.item(0).getTextContent();
      synth.setFmAmount(toUnipolar(DelugeHexMapper.hexToFloat(txt)));
      synth.setModulator1AmountQ31(DelugeHexMapper.hexToQ31(txt));
    }
    // Raw feedback values for the firmware-faithful FM engine (default off = 0x80000000).
    synth.setModulator1FeedbackQ31(soundQ31(soundNode, "modulator1Feedback", Integer.MIN_VALUE));
    synth.setCarrier1FeedbackQ31(soundQ31(soundNode, "carrier1Feedback", Integer.MIN_VALUE));
    synth.setCarrier2FeedbackQ31(soundQ31(soundNode, "carrier2Feedback", Integer.MIN_VALUE));
    // Wavefolder knob (C reads "waveFold" into LOCAL_FOLD, sound.cpp:1273-1276).
    synth.setWaveFoldQ31(soundQ31(soundNode, "waveFold", Integer.MIN_VALUE));
    // Portamento knob (C UNPATCHED_PORTAMENTO, written as "portamento"; INT_MIN = off).
    synth.setPortamentoQ31(soundQ31(soundNode, "portamento", Integer.MIN_VALUE));
    // Saturation amount (C "clippingAmount" tag-or-attribute, plain small int;
    // mod_controllable_audio.cpp:736-737).
    String clipStr = soundNode.getAttribute("clippingAmount");
    if (clipStr == null || clipStr.isEmpty()) {
      NodeList clipNodes = soundNode.getElementsByTagName("clippingAmount");
      if (clipNodes.getLength() > 0) clipStr = clipNodes.item(0).getTextContent().trim();
    }
    if (clipStr != null && !clipStr.isEmpty()) {
      try {
        synth.setClippingAmount(Integer.parseInt(clipStr));
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "Error parsing clippingAmount", e);
      }
    }
    NodeList m1m0 = soundNode.getElementsByTagName("modulator1ToModulator0");
    if (m1m0.getLength() > 0) {
      String v = m1m0.item(0).getTextContent().trim();
      synth.setModulator1ToModulator0("1".equals(v) || "true".equalsIgnoreCase(v));
    }
  }

  private static void parseModulator2(Element soundNode, SynthTrackModel synth) {
    NodeList mod2Nodes = soundNode.getElementsByTagName("modulator2");
    if (mod2Nodes.getLength() > 0) {
      Element mod2 = (Element) mod2Nodes.item(0);
      int transpose = attrOrChildInt(mod2, "transpose", 0);
      int cents = attrOrChildInt(mod2, "cents", 0);
      synth.setFmRatio2(modulatorRatio(transpose, cents));
      synth.setModulator2Transpose(transpose);
      synth.setModulator2Cents(cents);
      int retrig = attrOrChildInt(mod2, "retrigPhase", 0);
      synth.setMod2RetrigPhase(retrig);
      String toMod1 = attrOrChildText(mod2, "toModulator1");
      if (toMod1 != null && !toMod1.isBlank()) {
        synth.setModulator1ToModulator0("1".equals(toMod1) || "true".equalsIgnoreCase(toMod1));
      }
    }
    NodeList mod2AmtNodes = soundNode.getElementsByTagName("modulator2Amount");
    if (mod2AmtNodes.getLength() > 0) {
      synth.setModulator2AmountQ31(DelugeHexMapper.hexToQ31(mod2AmtNodes.item(0).getTextContent()));
    }
    synth.setModulator2FeedbackQ31(soundQ31(soundNode, "modulator2Feedback", Integer.MIN_VALUE));
  }

  private static void parseEnvelopes(Element soundNode, SynthTrackModel synth) {
    // Try attribute-style first: <envelope attack="0x..." decay="0x..." ...>
    NodeList envNodes = soundNode.getElementsByTagName("envelope");
    if (envNodes.getLength() > 0) {
      for (int i = 0; i < Math.min(4, envNodes.getLength()); i++) {
        Element envNode = (Element) envNodes.item(i);
        EnvelopeModel env =
            new EnvelopeModel(
                DelugeHexMapper.hexToEnvTime(envNode.getAttribute("attack")),
                DelugeHexMapper.hexToEnvTime(envNode.getAttribute("decay")),
                DelugeHexMapper.hexToSustain(envNode.getAttribute("sustain")),
                DelugeHexMapper.hexToEnvTime(envNode.getAttribute("release")),
                "NONE",
                0.0f);
        synth.setEnv(i, env);
        String sustainAttr = envNode.getAttribute("sustain");
        int sustainKnob =
            (sustainAttr != null && !sustainAttr.isEmpty())
                ? DelugeHexMapper.hexToQ31(sustainAttr)
                : 858993459; // default 0.7 sustain Q31
        // Preserve the raw rate + level knobs for the firmware-faithful envelope rate/sustain
        // curves.
        synth
            .getRawKnobs()
            .setEnvKnobsQ31(
                i,
                DelugeHexMapper.hexToQ31(envNode.getAttribute("attack")),
                DelugeHexMapper.hexToQ31(envNode.getAttribute("decay")),
                sustainKnob,
                DelugeHexMapper.hexToQ31(envNode.getAttribute("release")));
        if (sustainAttr != null && !sustainAttr.isEmpty()) {
          synth.getRawKnobs().setRawParamKnob(Param.LOCAL_ENV_0_SUSTAIN + i, sustainKnob);
        }
      }
    }
    // Fallback: try child-element format from
    // <defaultParams><envelope1><attack>0x...</attack></envelope1>
    // Only applies if no envelope elements were found at the top level.
    NodeList dpNodes = soundNode.getElementsByTagName("defaultParams");
    if (dpNodes.getLength() > 0 && envNodes.getLength() == 0) {
      Element dp = (Element) dpNodes.item(0);
      String[] envTags = {"envelope1", "envelope2", "envelope3", "envelope4"};
      for (int i = 0; i < 4; i++) {
        NodeList childEnvs = dp.getElementsByTagName(envTags[i]);
        if (childEnvs.getLength() > 0) {
          Element envEl = (Element) childEnvs.item(0);
          EnvelopeModel env = parseEnvelopeElement(envEl);
          // Only set if at least one non-default value was parsed
          if (env.attack() != 0.01f
              || env.decay() != 0.1f
              || env.sustain() != 0.7f
              || env.release() != 0.2f) {
            synth.setEnv(i, env);
            String sustainText = getChildText(envEl, "sustain");
            int sustainKnob =
                (sustainText != null && !sustainText.isEmpty())
                    ? DelugeHexMapper.hexToQ31(sustainText)
                    : 858993459; // default 0.7 sustain Q31
            // Preserve the raw rate + level knobs for the firmware-faithful envelope rate/sustain
            // curves.
            synth
                .getRawKnobs()
                .setEnvKnobsQ31(
                    i,
                    DelugeHexMapper.hexToQ31(getChildText(envEl, "attack")),
                    DelugeHexMapper.hexToQ31(getChildText(envEl, "decay")),
                    sustainKnob,
                    DelugeHexMapper.hexToQ31(getChildText(envEl, "release")));
            if (sustainText != null && !sustainText.isEmpty()) {
              synth.getRawKnobs().setRawParamKnob(Param.LOCAL_ENV_0_SUSTAIN + i, sustainKnob);
            }
          }
        }
      }
    }
  }

  private static void parsePatchCables(Element soundNode, SynthTrackModel synth) {
    Element pcContainer = getFirstChild(soundNode, "patchCables");
    Element scanRoot = pcContainer != null ? pcContainer : soundNode;
    for (PatchCable pc : parsePatchCableList(scanRoot)) {
      synth.getModulation().addPatchCable(pc);
    }
  }

  /**
   * Parses top-level {@code <patchCable>} children of {@code scanRoot}, resolving the pre-V3.2
   * legacy range-modulation encoding: a cable with {@code <destination>range</destination>}
   * modulates the DEPTH of whichever cable in the same file carries {@code
   * <rangeAdjustable>1</rangeAdjustable>}, rather than targeting a literal "range" param. Ports C
   * {@code PatchCableSet::readPatchCablesFromFile} (patch_cable_set.cpp:807-950): cables are
   * collected first, "range"-destination cables are held aside, then folded into the
   * rangeAdjustable-flagged cable's depthControlledBy list once the whole file is scanned.
   */
  private static List<PatchCable> parsePatchCableList(Element scanRoot) {
    java.util.List<PatchCable> cables = new java.util.ArrayList<>();
    java.util.List<PatchCable> legacyRangeCables = new java.util.ArrayList<>();
    String rangeAdjustableSource = null;
    String rangeAdjustableDest = null;
    NodeList children = scanRoot.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      if (!(children.item(i) instanceof Element cableElem)
          || !"patchCable".equals(cableElem.getTagName())) {
        continue;
      }
      PatchCable pc = parseSinglePatchCable(cableElem);
      if (pc == null) {
        continue;
      }
      if ("range".equalsIgnoreCase(pc.destination().trim())) {
        legacyRangeCables.add(pc);
        continue;
      }
      String rangeAdjustableAttr = cableElem.getAttribute("rangeAdjustable");
      if (rangeAdjustableAttr == null || rangeAdjustableAttr.isEmpty()) {
        rangeAdjustableAttr = getChildText(cableElem, "rangeAdjustable");
      }
      if ("1".equals(rangeAdjustableAttr)) {
        rangeAdjustableSource = pc.source();
        rangeAdjustableDest = pc.destination();
      }
      cables.add(pc);
    }
    if (!legacyRangeCables.isEmpty() && rangeAdjustableSource != null) {
      for (int i = 0; i < cables.size(); i++) {
        PatchCable pc = cables.get(i);
        if (pc.source().equals(rangeAdjustableSource)
            && pc.destination().equals(rangeAdjustableDest)) {
          java.util.List<PatchCable> merged = new java.util.ArrayList<>(pc.depthControlledBy());
          merged.addAll(legacyRangeCables);
          cables.set(
              i, new PatchCable(pc.source(), pc.destination(), pc.amount(), pc.polarity(), merged));
          break;
        }
      }
    }
    return cables;
  }

  private static void parseModKnobs(Element soundNode, SynthTrackModel synth) {
    NodeList knobList = soundNode.getElementsByTagName("modKnob");
    for (int i = 0; i < knobList.getLength(); i++) {
      Element knobElem = (Element) knobList.item(i);
      String param = getChildText(knobElem, "controlsParam");
      if (param == null) param = knobElem.getAttribute("controlsParam");
      if (param != null && !param.isBlank() && i < synth.getModulation().getModKnobs().size()) {
        String patchSrc = getChildText(knobElem, "patchSource");
        if (patchSrc == null) patchSrc = getChildText(knobElem, "patchAmountFromSource");
        if (patchSrc == null) patchSrc = knobElem.getAttribute("patchSource");
        if (patchSrc == null) patchSrc = knobElem.getAttribute("patchAmountFromSource");
        if (patchSrc == null || patchSrc.isBlank()) patchSrc = "NONE";
        synth.getModulation().setModKnob(i, new ModKnob(param.trim(), patchSrc.trim()));
      }
    }
  }

  private static void parseSynthLfo(
      Element soundNode, String lfoTag, SynthTrackModel synth, boolean isLocal) {
    NodeList lfoNodes = soundNode.getElementsByTagName(lfoTag);
    if (lfoNodes.getLength() == 0) return;
    Element lfoEl = (Element) lfoNodes.item(0);

    LfoType waveform = LfoType.SINE;
    float rateHz = 1.0f;
    float depth = 1.0f;
    int syncLevel = 0;

    // type: attribute or child text
    String typeStr = lfoEl.getAttribute("type");
    if (typeStr == null || typeStr.isBlank()) typeStr = getChildText(lfoEl, "type");
    if (typeStr != null) waveform = parseLfoType(typeStr);

    // rate: hex Hz attribute or child text. Keep the Hz (for display/other uses) AND the raw Q31
    // knob (the firmware-faithful rate path feeds the knob straight to getExp, avoiding the lossy
    // hexToLfoHz round-trip).
    int rateKnobQ31 = 0; // 0 = firmware neutral rate
    String rateStr = lfoEl.getAttribute("rate");
    if (rateStr == null || rateStr.isBlank()) rateStr = getChildText(lfoEl, "rate");
    if (rateStr != null && !rateStr.isBlank()) {
      rateHz = DelugeHexMapper.hexToLfoHz(rateStr);
      rateKnobQ31 = DelugeHexMapper.hexToQ31(rateStr);
    }

    // depth: hex float attribute or child text
    String depthStr = lfoEl.getAttribute("depth");
    if (depthStr != null && !depthStr.isBlank()) {
      depth = toUnipolar(DelugeHexMapper.hexToFloat(depthStr));
    } else {
      String depthChild = getChildText(lfoEl, "depth");
      if (depthChild != null) depth = toUnipolar(DelugeHexMapper.hexToFloat(depthChild));
    }

    // syncLevel: attribute first, child-element fallback
    String syncStr = lfoEl.getAttribute("syncLevel");
    if (syncStr != null && !syncStr.isBlank()) {
      try {
        syncLevel = Integer.parseInt(syncStr);
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    } else {
      String syncChild = getChildText(lfoEl, "syncLevel");
      if (syncChild != null) {
        try {
          syncLevel = Integer.parseInt(syncChild);
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
    }

    // syncType: attribute on lfo element
    int syncType = 0;
    String syncTypeStr = lfoEl.getAttribute("syncType");
    if (syncTypeStr != null && !syncTypeStr.isBlank()) {
      try {
        syncType = Integer.parseInt(syncTypeStr);
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }

    int lfoIndex =
        switch (lfoTag) {
          case "lfo1" -> 0;
          case "lfo2" -> 1;
          case "lfo3" -> 2;
          case "lfo4" -> 3;
          default -> -1;
        };

    if (lfoIndex >= 0 && lfoIndex < 4) {
      synth.setLfo(
          lfoIndex, new LfoModel(rateHz, waveform, depth, "NONE", isLocal, syncLevel, syncType));
      if (rateStr != null && !rateStr.isBlank()) {
        synth.getRawKnobs().setLfoRateKnobQ31(lfoIndex, rateKnobQ31);
      }
    }
  }

  private static void parseSynthArp(Element soundNode, SynthTrackModel synth) {
    NodeList arpNodes = soundNode.getElementsByTagName("arpeggiator");
    if (arpNodes.getLength() == 0) return;
    Element arpEl = (Element) arpNodes.item(0);

    String mode = "UP";
    float rate = 1.0f;
    int octaves = 1;
    float gate = 0.5f;
    boolean active = false;

    String modeStr = arpEl.getAttribute("mode");
    if (modeStr != null && !modeStr.isBlank()) mode = modeStr.toUpperCase();
    String rateStr = getChildText(arpEl, "rate");
    if (rateStr != null) rate = Math.abs(DelugeHexMapper.hexToFloat(rateStr));
    String octStr = getChildText(arpEl, "octaves");
    if (octStr != null) {
      try {
        octaves = Integer.parseInt(octStr);
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }
    String gateStr = getChildText(arpEl, "gate");
    if (gateStr != null) gate = Math.abs(DelugeHexMapper.hexToFloat(gateStr));

    // Parse new arp fields
    int syncLevel = 0;
    String noteMode = "UP";
    String octaveMode = "UP";
    int stepRepeat = 1;
    int rhythmIndex = 0;
    int seqLength = 8;
    String syncStr = getChildText(arpEl, "sync");
    if (syncStr != null) {
      try {
        syncLevel = Integer.parseInt(syncStr);
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }
    String noteModeStr = arpEl.getAttribute("noteMode");
    if (noteModeStr != null && !noteModeStr.isBlank()) noteMode = noteModeStr.toUpperCase();
    String octModeStr = arpEl.getAttribute("octaveMode");
    if (octModeStr != null && !octModeStr.isBlank()) octaveMode = octModeStr.toUpperCase();
    String repeatStr = getChildText(arpEl, "stepRepeat");
    if (repeatStr != null) {
      try {
        stepRepeat = Integer.parseInt(repeatStr);
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }
    String rhythmStr = getChildText(arpEl, "rhythm");
    if (rhythmStr != null) {
      try {
        // The C may serialize the raw uint32 menu value (arpeggiator.cpp:1899) — parse as long
        // (raw values exceed int) and keep the bits; the factory normalizes raw vs 0..50 index.
        rhythmIndex = (int) Long.parseLong(rhythmStr.trim());
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }
    String seqLenStr = arpEl.getAttribute("sequenceLength");
    if (seqLenStr == null || seqLenStr.isEmpty()) {
      seqLenStr = getChildText(arpEl, "seqLength");
    }
    if (seqLenStr != null && !seqLenStr.isEmpty()) {
      try {
        seqLength = Integer.parseInt(seqLenStr.trim());
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }

    // mpeVelocity: attribute on arpeggiator element
    int mpeVelocity = 0;
    String mpeVelStr = arpEl.getAttribute("mpeVelocity");
    if (mpeVelStr != null && !mpeVelStr.isBlank()) {
      try {
        mpeVelocity = Integer.parseInt(mpeVelStr);
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }

    // syncType: attribute on arpeggiator element (firmware ArpSyncType enum)
    int syncType = 0;
    String syncTypeStr = arpEl.getAttribute("syncType");
    if (syncTypeStr != null && !syncTypeStr.isBlank()) {
      try {
        syncType = Integer.parseInt(syncTypeStr);
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }

    // Parse new arp fields
    active =
        "1".equals(arpEl.getAttribute("active"))
            || "true".equalsIgnoreCase(arpEl.getAttribute("active"));

    String arpMode = arpEl.getAttribute("arpMode");

    String notePattern = arpEl.getAttribute("notePattern");
    if (notePattern == null || notePattern.isBlank()) {
      notePattern = "00000000000000000000000000000000";
    }

    // Spread and probability attributes (attributes written by firmware via writeParamAsAttribute)
    float octaveSpread = readAttrFloatWithDefault(arpEl, "spreadOctave", 0f, false);
    float gateSpread = readAttrFloatWithDefault(arpEl, "spreadGate", 0f, false);
    float velSpread = readAttrFloatWithDefault(arpEl, "spreadVelocity", 0f, false);
    int ratchetAmount = readIntAttrWithDefault(arpEl, "ratchetAmount", 0);

    float noteProb = readAttrFloatWithDefault(arpEl, "noteProbability", 0f, false);
    float bassProb = readAttrFloatWithDefault(arpEl, "bassProbability", 0f, false);
    float swapProb = readAttrFloatWithDefault(arpEl, "swapProbability", 0f, false);
    float glideProb = readAttrFloatWithDefault(arpEl, "glideProbability", 0f, false);
    float reverseProb = readAttrFloatWithDefault(arpEl, "reverseProbability", 0f, false);
    float chordProb = readAttrFloatWithDefault(arpEl, "chordProbability", 0f, false);
    float ratchetProb = readAttrFloatWithDefault(arpEl, "ratchetProbability", 0f, false);
    int chordPolyphony = readIntAttrWithDefault(arpEl, "chordPolyphony", 0);
    int chordType = readIntAttrWithDefault(arpEl, "chordType", 0);

    int numOctaves = readIntAttrWithDefault(arpEl, "numOctaves", octaves);
    int kitArp = readIntAttrWithDefault(arpEl, "kitArp", 0);
    int randomizerLock = readIntAttrWithDefault(arpEl, "randomizerLock", 0);

    int lastLockedNoteProb = readIntAttrWithDefault(arpEl, "lastLockedNoteProb", 0);
    String lockedNoteProbArray = arpEl.getAttribute("lockedNoteProbArray");
    if (lockedNoteProbArray == null || lockedNoteProbArray.isBlank()) {
      lockedNoteProbArray = "00000000000000000000000000000000";
    }

    int lastLockedBassProb = readIntAttrWithDefault(arpEl, "lastLockedBassProb", 0);
    String lockedBassProbArray = arpEl.getAttribute("lockedBassProbArray");
    if (lockedBassProbArray == null || lockedBassProbArray.isBlank()) {
      lockedBassProbArray = "00000000000000000000000000000000";
    }

    int lastLockedSwapProb = readIntAttrWithDefault(arpEl, "lastLockedSwapProb", 0);
    String lockedSwapProbArray = arpEl.getAttribute("lockedSwapProbArray");
    if (lockedSwapProbArray == null || lockedSwapProbArray.isBlank()) {
      lockedSwapProbArray = "00000000000000000000000000000000";
    }

    int lastLockedGlideProb = readIntAttrWithDefault(arpEl, "lastLockedGlideProb", 0);
    String lockedGlideProbArray = arpEl.getAttribute("lockedGlideProbArray");
    if (lockedGlideProbArray == null || lockedGlideProbArray.isBlank()) {
      lockedGlideProbArray = "00000000000000000000000000000000";
    }

    int lastLockedReverseProb = readIntAttrWithDefault(arpEl, "lastLockedReverseProb", 0);
    String lockedReverseProbArray = arpEl.getAttribute("lockedReverseProbArray");
    if (lockedReverseProbArray == null || lockedReverseProbArray.isBlank()) {
      lockedReverseProbArray = "00000000000000000000000000000000";
    }

    int lastLockedChordProb = readIntAttrWithDefault(arpEl, "lastLockedChordProb", 0);
    String lockedChordProbArray = arpEl.getAttribute("lockedChordProbArray");
    if (lockedChordProbArray == null || lockedChordProbArray.isBlank()) {
      lockedChordProbArray = "00000000000000000000000000000000";
    }

    int lastLockedRatchetProb = readIntAttrWithDefault(arpEl, "lastLockedRatchetProb", 0);
    String lockedRatchetProbArray = arpEl.getAttribute("lockedRatchetProbArray");
    if (lockedRatchetProbArray == null || lockedRatchetProbArray.isBlank()) {
      lockedRatchetProbArray = "00000000000000000000000000000000";
    }

    int lastLockedVelocitySpread = readIntAttrWithDefault(arpEl, "lastLockedVelocitySpread", 0);
    String lockedVelocitySpreadArray = arpEl.getAttribute("lockedVelocitySpreadArray");
    if (lockedVelocitySpreadArray == null || lockedVelocitySpreadArray.isBlank()) {
      lockedVelocitySpreadArray = "00000000000000000000000000000000";
    }

    int lastLockedGateSpread = readIntAttrWithDefault(arpEl, "lastLockedGateSpread", 0);
    String lockedGateSpreadArray = arpEl.getAttribute("lockedGateSpreadArray");
    if (lockedGateSpreadArray == null || lockedGateSpreadArray.isBlank()) {
      lockedGateSpreadArray = "00000000000000000000000000000000";
    }

    int lastLockedOctaveSpread = readIntAttrWithDefault(arpEl, "lastLockedOctaveSpread", 0);
    String lockedOctaveSpreadArray = arpEl.getAttribute("lockedOctaveSpreadArray");
    if (lockedOctaveSpreadArray == null || lockedOctaveSpreadArray.isBlank()) {
      lockedOctaveSpreadArray = "00000000000000000000000000000000";
    }

    synth.setArp(
        new ArpModel(
            active,
            mode,
            rate,
            octaves,
            Math.abs(gate),
            syncLevel,
            noteMode,
            octaveMode,
            stepRepeat,
            rhythmIndex,
            seqLength,
            octaveSpread,
            gateSpread,
            velSpread,
            ratchetAmount,
            mpeVelocity,
            syncType,
            noteProb,
            bassProb,
            swapProb,
            glideProb,
            reverseProb,
            chordProb,
            ratchetProb,
            chordPolyphony,
            notePattern,
            chordType,
            numOctaves,
            kitArp,
            randomizerLock,
            lastLockedNoteProb,
            lockedNoteProbArray,
            lastLockedBassProb,
            lockedBassProbArray,
            lastLockedSwapProb,
            lockedSwapProbArray,
            lastLockedGlideProb,
            lockedGlideProbArray,
            lastLockedReverseProb,
            lockedReverseProbArray,
            lastLockedChordProb,
            lockedChordProbArray,
            lastLockedRatchetProb,
            lockedRatchetProbArray,
            lastLockedVelocitySpread,
            lockedVelocitySpreadArray,
            lastLockedGateSpread,
            lockedGateSpreadArray,
            lastLockedOctaveSpread,
            lockedOctaveSpreadArray));
  }

  private static void parseSynthCompressor(Element soundNode, SynthTrackModel synth) {
    NodeList compNodes = soundNode.getElementsByTagName("compressor");
    if (compNodes.getLength() == 0) {
      // Fallback: look up direct attributes on the soundNode
      String attackStr = soundNode.getAttribute("compressorAttack");
      if (attackStr != null && !attackStr.isBlank()) {
        synth.setCompressorAttack(Math.abs(DelugeHexMapper.hexToFloat(attackStr)));
      }
      String releaseStr = soundNode.getAttribute("compressorRelease");
      if (releaseStr != null && !releaseStr.isBlank()) {
        synth.setCompressorRelease(Math.abs(DelugeHexMapper.hexToFloat(releaseStr)));
      }
      String syncStr = soundNode.getAttribute("compressorSyncLevel");
      if (syncStr != null && !syncStr.isBlank()) {
        try {
          synth.setCompressorSyncLevel(Integer.parseInt(syncStr));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
      readAttrFloatHex(soundNode, "compressorThreshold", synth::setCompressorThreshold, true);
      readAttrFloatHex(soundNode, "compressorRatio", synth::setCompressorRatio, true);
      readAttrFloatHex(soundNode, "compressorBlend", synth::setCompressorBlend, true);
      String syncTypeStr = soundNode.getAttribute("compressorSyncType");
      if (syncTypeStr != null && !syncTypeStr.isBlank()) {
        try {
          synth.setCompressorSyncType(Integer.parseInt(syncTypeStr));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
      return;
    }
    Element compEl = (Element) compNodes.item(0);
    readAttrOrChildHexFloat(compEl, "attack", v -> synth.setCompressorAttack(Math.abs(v)));
    readAttrOrChildHexFloat(compEl, "release", v -> synth.setCompressorRelease(Math.abs(v)));
    readAttrOrChildInt(compEl, "syncLevel", 0, synth::setCompressorSyncLevel);
    readAttrOrChildHexFloat(compEl, "threshold", v -> synth.setCompressorThreshold(Math.abs(v)));
    readAttrOrChildHexFloat(compEl, "ratio", v -> synth.setCompressorRatio(Math.abs(v)));
    readAttrOrChildHexFloat(compEl, "blend", v -> synth.setCompressorBlend(Math.abs(v)));
    readAttrOrChildInt(compEl, "syncType", 0, synth::setCompressorSyncType);
  }

  private static void parseStutter(Element soundNode, SynthTrackModel synth) {
    NodeList nodes = soundNode.getElementsByTagName("stutter");
    if (nodes.getLength() == 0) return;
    Element stut = (Element) nodes.item(0);
    readAttrBool(stut, "quantized", val -> synth.getStutter().setStutterQuantized(val));
    readAttrBool(stut, "reverse", val -> synth.getStutter().setStutterReversed(val));
    readAttrBool(stut, "pingPong", val -> synth.getStutter().setStutterPingPong(val));
  }

  private static void parseLegacyParams(Element soundNode, SynthTrackModel synth) {
    // 1. Oscillator levels
    String oscL1 = getChildText(soundNode, "oscLevel1");
    if (oscL1 != null && !oscL1.isEmpty()) {
      try {
        synth.setOscAVolume(Integer.parseInt(oscL1.trim()) / 50.0f);
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "Error parsing legacy oscLevel1", e);
      }
    }
    String oscL2 = getChildText(soundNode, "oscLevel2");
    if (oscL2 != null && !oscL2.isEmpty()) {
      try {
        synth.setOscBVolume(Integer.parseInt(oscL2.trim()) / 50.0f);
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "Error parsing legacy oscLevel2", e);
      }
    }
    String oscL = getChildText(soundNode, "oscLevel");
    if (oscL != null && !oscL.isEmpty()) {
      try {
        float v = Integer.parseInt(oscL.trim()) / 50.0f;
        synth.setOscAVolume(v);
        synth.setOscBVolume(v);
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "Error parsing legacy oscLevel", e);
      }
    }
    String noiseL = getChildText(soundNode, "noiseLevel");
    if (noiseL != null && !noiseL.isEmpty()) {
      try {
        synth.setNoiseVol(Integer.parseInt(noiseL.trim()) / 50.0f);
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "Error parsing legacy noiseLevel", e);
      }
    }

    // 2. Distortion
    String distAmt = getChildText(soundNode, "distortionAmount");
    if (distAmt != null && !distAmt.isEmpty()) {
      try {
        synth.setClippingAmount(Integer.parseInt(distAmt.trim()));
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "Error parsing legacy distortionAmount", e);
      }
    }
    String distType = getChildText(soundNode, "distortionType");
    if (distType != null && !distType.isEmpty()) {
      try {
        int type = Integer.parseInt(distType.trim());
        LOG.log(Level.FINE, "Parsed legacy distortionType: " + type);
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "Error parsing legacy distortionType", e);
      }
    }

    // 3. Filter type & slope
    String fType = getChildText(soundNode, "filterType");
    String fSlope = getChildText(soundNode, "filterSlope");
    if (fType != null && !fType.isEmpty()) {
      try {
        int type = Integer.parseInt(fType.trim());
        int slope = 1; // default 24dB
        if (fSlope != null && !fSlope.isEmpty()) {
          slope = Integer.parseInt(fSlope.trim());
        }
        if (type == 0) {
          synth.setFilterMode(
              slope == 0
                  ? org.deluge.model.FilterMode.LADDER_12
                  : org.deluge.model.FilterMode.LADDER_24);
        } else {
          synth.setFilterMode(org.deluge.model.FilterMode.SVF);
        }
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "Error parsing legacy filterType/slope", e);
      }
    }

    // 4. LPF/HPF Order (Routing)
    String lhOrder = getChildText(soundNode, "lpfHpfOrder");
    if (lhOrder != null && !lhOrder.isEmpty()) {
      try {
        synth.setFilterRoute(Integer.parseInt(lhOrder.trim()));
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "Error parsing legacy lpfHpfOrder", e);
      }
    }

    // 5. Polyphony (isPolyphonic)
    String isPoly = getChildText(soundNode, "isPolyphonic");
    if (isPoly != null && !isPoly.isEmpty()) {
      if ("0".equals(isPoly.trim()) || "false".equalsIgnoreCase(isPoly.trim())) {
        synth.setPolyphony(SynthTrackModel.PolyphonyMode.MONO);
      } else {
        synth.setPolyphony(SynthTrackModel.PolyphonyMode.POLY);
      }
    }

    // 6. Sound Group Mode (Choke)
    String sgMode = getChildText(soundNode, "soundGroupMode");
    if (sgMode != null && !sgMode.isEmpty()) {
      try {
        int mode = Integer.parseInt(sgMode.trim());
        if (mode != 0) {
          synth.setPolyphony(SynthTrackModel.PolyphonyMode.CHOKE);
        }
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "Error parsing legacy soundGroupMode", e);
      }
    }

    // 7. Delay (time/feedback/amount/pingPong inside <delay> child)
    Element delEl = getFirstChild(soundNode, "delay");
    if (delEl != null) {
      String timeStr = getChildText(delEl, "time");
      if (timeStr != null && !timeStr.isEmpty()) {
        try {
          synth.setDelaySyncLevel(Integer.parseInt(timeStr.trim()));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "Error parsing legacy delay time", e);
        }
      }
      String fbStr = getChildText(delEl, "feedback");
      if (fbStr != null && !fbStr.isEmpty()) {
        try {
          float norm = Integer.parseInt(fbStr.trim()) / 50.0f;
          int fbQ31 = DelugeHexMapper.hexToQ31(DelugeHexMapper.unipolarFloatToHexUnified(norm));
          synth.setDelayFeedbackQ31(fbQ31);
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "Error parsing legacy delay feedback", e);
        }
      }
      String amtStr = getChildText(delEl, "amount");
      if (amtStr != null && !amtStr.isEmpty()) {
        try {
          synth.setDelaySend(Integer.parseInt(amtStr.trim()) / 50.0f);
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "Error parsing legacy delay amount", e);
        }
      }
      String ppStr = getChildText(delEl, "pingPong");
      if (ppStr != null && !ppStr.isEmpty()) {
        synth.setDelayPingPong("1".equals(ppStr.trim()) || "true".equalsIgnoreCase(ppStr.trim()));
      }
    }

    // 8. Envelopes (amplitudes -> env1, filters -> env2)
    parseLegacyEnvelopeParam(soundNode, "amplitudes", 0, synth);
    parseLegacyEnvelopeParam(soundNode, "filters", 1, synth);
  }

  private static void parseLegacyEnvelopeParam(
      Element soundNode, String tagName, int envIndex, SynthTrackModel synth) {
    Element container = getFirstChild(soundNode, tagName);
    if (container == null) return;
    Element envEl = getFirstChild(container, "envelope");
    if (envEl == null) return;

    String attStr = getChildText(envEl, "attack");
    String decStr = getChildText(envEl, "decay");
    String susStr = getChildText(envEl, "sustain");
    String relStr = getChildText(envEl, "release");

    try {
      float attNorm =
          (attStr != null && !attStr.isEmpty()) ? Integer.parseInt(attStr.trim()) / 99.0f : 0.0f;
      float decNorm =
          (decStr != null && !decStr.isEmpty()) ? Integer.parseInt(decStr.trim()) / 99.0f : 0.0f;
      float susVal =
          (susStr != null && !susStr.isEmpty()) ? Integer.parseInt(susStr.trim()) / 99.0f : 0.7f;
      float relNorm =
          (relStr != null && !relStr.isEmpty()) ? Integer.parseInt(relStr.trim()) / 99.0f : 0.0f;

      float attack = DelugeHexMapper.envTimeFromNorm(attNorm * 2.0f - 1.0f);
      float decay = DelugeHexMapper.envTimeFromNorm(decNorm * 2.0f - 1.0f);
      float release = DelugeHexMapper.envTimeFromNorm(relNorm * 2.0f - 1.0f);

      synth.setEnv(envIndex, new EnvelopeModel(attack, decay, susVal, release, "NONE", 0.0f));

      int attQ31 = (int) ((attNorm * 2.0f - 1.0f) * Integer.MAX_VALUE);
      int decQ31 = (int) ((decNorm * 2.0f - 1.0f) * Integer.MAX_VALUE);
      int susQ31 = (int) ((susVal * 2.0f - 1.0f) * Integer.MAX_VALUE);
      int relQ31 = (int) ((relNorm * 2.0f - 1.0f) * Integer.MAX_VALUE);

      synth.getRawKnobs().setEnvKnobsQ31(envIndex, attQ31, decQ31, susQ31, relQ31);
      if (envIndex == 0) {
        synth.getRawKnobs().setRawParamKnob(Param.LOCAL_ENV_0_SUSTAIN, susQ31);
      } else if (envIndex == 1) {
        synth.getRawKnobs().setRawParamKnob(Param.LOCAL_ENV_1_SUSTAIN, susQ31);
      }
    } catch (NumberFormatException e) {
      LOG.log(Level.FINE, "Error parsing legacy envelope " + tagName, e);
    }
  }

  /**
   * C-faithful reset applied BEFORE a clip's {@code <soundParams>} overlay: in the firmware a
   * clip's patched params come from {@code Sound::initParams} defaults (sound.cpp:146-210) overlaid
   * with ONLY what the clip lists — the instrument's {@code <defaultParams>} are NOT consulted for
   * clip playback, and the patch cables likewise reset to the firmware's four defaults
   * (sound.cpp:239-243) unless the clip carries its own. Old-format songs (the ALLSYN test songs)
   * list only a handful of params; inheriting the instrument's defaults made our render play FM
   * modulators where the hardware plays none (hardware-verified — the fresh ALLSYN recording plays
   * 068 as carrier-only, matching these defaults exactly).
   *
   * <p>Faithful subset: FM modulator/carrier params, HPF, sends, portamento, cables. Envelope / LFO
   * user-value defaults (sound.cpp:184-199) are NOT yet reset — clips lacking envelope params still
   * inherit the instrument envelope (documented divergence, see FIDELITY_GAP_ANALYSIS.md
   * 4.1octies).
   */
  public static void resetClipParamsToFirmwareDefaults(SynthTrackModel synth) {
    int off = Integer.MIN_VALUE;
    // sound.cpp:172-183 — FM modulators + all feedbacks default OFF
    synth.setModulator1AmountQ31(off);
    synth.setModulator2AmountQ31(off);
    synth.setModulator1FeedbackQ31(off);
    synth.setModulator2FeedbackQ31(off);
    synth.setCarrier1FeedbackQ31(off);
    synth.setCarrier2FeedbackQ31(off);
    // NOTE: osc volumes / HPF / sends / portamento are NOT reset. The C initParams table
    // defaults them too, but the fresh hardware recording contradicts a full reset for these
    // old-format songs (basses/leads regress sharply when osc mix + HPF are defaulted while
    // they match when inherited) — the C old-song reader evidently back-fills those groups from
    // the instrument. Only the FM param group + cables are hardware-proven to reset (068/069:
    // static carrier-only tone, no cable movement). Empirically calibrated; see
    // FIDELITY_GAP_ANALYSIS.md 4.1octies.
    // Envelopes: in the clip path the C runs initParams (which sets only envelope 2's rates,
    // user 20/20/25/20) over AutoParam construction defaults (param value 0 = user 25) —
    // the instrument's envelope values are NOT consulted. user->param: u*85899345 - 2^31
    // (functions.cpp getParamFromUserValue default branch).
    int user20 = (int) (20L * 85899345L - 2147483648L); // -429496748
    // Volume envelope (C ENV_0) defaults to the blank-synth shape (sound.cpp:297-306):
    // instant attack, user-20 decay, full sustain — empirically the profile both regression
    // populations' recordings agree with (mid-sustain construction defaults split them).
    // Envelopes 2-4 stay inherited. NOTE (2026-07-24 calibration): the sustain written here is
    // effectively a no-op — the envelope parse also stores sustains in the raw param-knob map
    // (ids ENV_0..3_SUSTAIN) which FirmwareFactory applies AFTER the env-knob arrays, so ALL
    // sustains are inherited from the instrument. That inheritance is empirically correct:
    // making the full initParams env semantics actually apply (ENV_0 sustain full, ENV_1 rates
    // 20/20/25/20, ENV_2/3 construction zeros) scored net-negative on the fresh recordings
    // (mean -0.008, 011 Dubstep -0.29, 103 Sci-fi Chaos -0.40) and made even the motivating
    // preset (109 Talking Arp) worse — the C old-song reader evidently back-fills envelopes
    // from the instrument like the osc/HPF groups. The validated reset is rates-only.
    synth.getRawKnobs().setEnvKnobsQ31(0, Integer.MIN_VALUE, user20, Integer.MAX_VALUE, 0);
    // Cables: inherited from the instrument — replacing them with the firmware's four defaults
    // regressed basses/leads sharply (their note/velocity->LPF tracking cables audibly matter
    // and the recording matches the inherited set). Only the FM modulator param group above is
    // hardware-proven to reset.
  }

  public static void parseClipSoundParamsStatics(Element sp, SynthTrackModel synth) {
    // The shared param table (same names as the preset defaultParams).
    for (FieldBinding<?> b : DEFAULT_PARAMS_BINDINGS) {
      b.applyTo(sp, synth);
    }

    // Raw Q31 knobs the factory prefers over the float views.
    String v;
    if (!(v = sp.getAttribute("modulator1Amount")).isEmpty()) {
      synth.setModulator1AmountQ31(DelugeHexMapper.hexToQ31(v));
    }
    if (!(v = sp.getAttribute("modulator2Amount")).isEmpty()) {
      synth.setModulator2AmountQ31(DelugeHexMapper.hexToQ31(v));
    }
    if (!(v = sp.getAttribute("modulator1Feedback")).isEmpty()) {
      synth.setModulator1FeedbackQ31(DelugeHexMapper.hexToQ31(v));
    }
    if (!(v = sp.getAttribute("modulator2Feedback")).isEmpty()) {
      synth.setModulator2FeedbackQ31(DelugeHexMapper.hexToQ31(v));
    }
    if (!(v = sp.getAttribute("carrier1Feedback")).isEmpty()) {
      synth.setCarrier1FeedbackQ31(DelugeHexMapper.hexToQ31(v));
    }
    if (!(v = sp.getAttribute("carrier2Feedback")).isEmpty()) {
      synth.setCarrier2FeedbackQ31(DelugeHexMapper.hexToQ31(v));
    }
    if (!(v = sp.getAttribute("pitchAdjust")).isEmpty()) {
      synth.setPitchAdjustQ31(DelugeHexMapper.hexToQ31(v));
    }
    if (!(v = sp.getAttribute("oscAPitchAdjust")).isEmpty()) {
      synth.setOsc1PitchAdjustQ31(DelugeHexMapper.hexToQ31(v));
    }
    if (!(v = sp.getAttribute("oscBPitchAdjust")).isEmpty()) {
      synth.setOsc2PitchAdjustQ31(DelugeHexMapper.hexToQ31(v));
    }
    if (!(v = sp.getAttribute("oscAPulseWidth")).isEmpty()) {
      synth.setOsc1PhaseWidthQ31(DelugeHexMapper.hexToQ31(v));
    }
    if (!(v = sp.getAttribute("oscBPulseWidth")).isEmpty()) {
      synth.setOsc2PhaseWidthQ31(DelugeHexMapper.hexToQ31(v));
    }
    if (!(v = sp.getAttribute("portamento")).isEmpty()) {
      synth.setPortamentoQ31(DelugeHexMapper.hexToQ31(v));
    }
    if (!(v = sp.getAttribute("waveFold")).isEmpty()) {
      synth.setWaveFoldQ31(DelugeHexMapper.hexToQ31(v));
    }
    // LFO rate knobs: firmware lfo1 = global (slot 0), lfo2 = local (slot 1).
    if (!(v = sp.getAttribute("lfo1Rate")).isEmpty()) {
      synth.getRawKnobs().setLfoRateKnobQ31(0, DelugeHexMapper.hexToQ31(v));
    }
    if (!(v = sp.getAttribute("lfo2Rate")).isEmpty()) {
      synth.getRawKnobs().setLfoRateKnobQ31(1, DelugeHexMapper.hexToQ31(v));
    }

    // Patched params the firmware reads as RAW Q31 from <soundParams> (readParamsFromFile copies
    // every param's 0x.. value verbatim into params[p].currentValue — no float, no curve). Our
    // float round-trip (hex→model float→normToKnob) mis-ranges several — e.g. normToLinearParamKnob
    // floored a linear minimum at -2^29 not INT_MIN, so a song's minimum resonance became moderate
    // and pushed the ladder past its tanh threshold (gross distortion, found via hardware
    // comparison). Reading raw matches the firmware; the factory overlays these over the float
    // mapping. (Envelopes, LFO rates, FM modulator volumes/feedbacks, portamento, waveFold are read
    // raw elsewhere in this method; the unpatched FX params — bitcrush/srr/stutter/modFX-offset/eq/
    // sidechain/reverb-send — use dedicated scalar fields and are out of scope here.)
    for (var e : SOUNDPARAMS_RAW_PATCHED.entrySet()) {
      rawKnob(sp, e.getKey(), synth, e.getValue());
    }

    // Envelopes: <envelope1..4 attack="0x..." .../> children (attribute style).
    String[] envTags = {"envelope1", "envelope2", "envelope3", "envelope4"};
    for (int i = 0; i < 4; i++) {
      NodeList envs = sp.getElementsByTagName(envTags[i]);
      if (envs.getLength() == 0) {
        continue;
      }
      Element envEl = (Element) envs.item(0);
      String attack = attrOrChildText(envEl, "attack");
      String decay = attrOrChildText(envEl, "decay");
      String sustain = attrOrChildText(envEl, "sustain");
      String release = attrOrChildText(envEl, "release");
      if (attack == null || decay == null || sustain == null || release == null) {
        continue;
      }
      synth.setEnv(
          i,
          new EnvelopeModel(
              DelugeHexMapper.hexToEnvTime(attack),
              DelugeHexMapper.hexToEnvTime(decay),
              DelugeHexMapper.hexToSustain(sustain),
              DelugeHexMapper.hexToEnvTime(release),
              "NONE",
              0.0f));
      // Raw knobs win in the factory (firmware-faithful envelope rate + sustain level curves).
      int sustainKnob =
          (sustain != null && !sustain.isEmpty())
              ? DelugeHexMapper.hexToQ31(sustain)
              : 858993459; // default 0.7 sustain Q31
      synth
          .getRawKnobs()
          .setEnvKnobsQ31(
              i,
              DelugeHexMapper.hexToQ31(attack),
              DelugeHexMapper.hexToQ31(decay),
              sustainKnob,
              DelugeHexMapper.hexToQ31(release));
      synth.getRawKnobs().setRawParamKnob(Param.LOCAL_ENV_0_SUSTAIN + i, sustainKnob);
    }

    // Patch cables (the clip's set is authoritative in the song format).
    NodeList cables = sp.getElementsByTagName("patchCable");
    if (cables.getLength() > 0) {
      synth.getModulation().getPatchCables().clear();
      parsePatchCables(sp, synth);
    }
  }

  private static void parseSynthDelay(Element soundNode, SynthTrackModel synth) {
    // Delay feedback is a direct <sound> child (raw Q31, e.g.
    // <delayFeedback>0xBA000000</delayFeedback>)
    // — a patched param the firmware reads verbatim. No other parse path caught it for presets (the
    // attribute/legacy <delay><feedback> readers missed it), leaving delayFeedbackQ31=0 → delay
    // inert.
    String dfb = attrOrChildText(soundNode, "delayFeedback");
    if (dfb != null
        && !dfb.isBlank()
        && (dfb.trim().startsWith("0x") || dfb.trim().startsWith("0X"))) {
      synth.setDelayFeedbackQ31(DelugeHexMapper.hexToQ31(dfb.trim()));
    }
    Element del = getFirstChild(soundNode, "delay");
    if (del == null) {
      return;
    }
    // Presets use the CHILD-element form (<delay><syncLevel>6</syncLevel>...), not attributes —
    // read
    // attribute-or-child for all four (matching the osc-transpose fix). Missing this left every
    // delay-using synth's delay inert (delaySyncLevel=0 → delayFeedbackAmount=0).
    String syncLevel = attrOrChildText(del, "syncLevel");
    if (syncLevel != null && !syncLevel.isBlank()) {
      try {
        synth.setDelaySyncLevel(Integer.parseInt(syncLevel.trim()));
      } catch (NumberFormatException ignored) {
      }
    }
    String syncType = attrOrChildText(del, "syncType");
    if (syncType != null && !syncType.isBlank()) {
      try {
        synth.setDelaySyncType(Integer.parseInt(syncType.trim()));
      } catch (NumberFormatException ignored) {
      }
    }
    String pingPong = attrOrChildText(del, "pingPong");
    if (pingPong != null && !pingPong.isBlank()) {
      synth.setDelayPingPong(!"0".equals(pingPong.trim()));
    }
    String analog = attrOrChildText(del, "analog");
    if (analog != null && !analog.isBlank()) {
      synth.setDelayAnalog(!"0".equals(analog.trim()));
    }
  }

  private static void rawKnob(Element sp, String attr, SynthTrackModel synth, int paramId) {
    String v = attrOrChildText(sp, attr);
    if (v != null && !v.isBlank()) {
      synth.getRawKnobs().setRawParamKnob(paramId, DelugeHexMapper.hexToQ31(v));
    }
  }
}
