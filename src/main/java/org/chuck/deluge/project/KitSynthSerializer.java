package org.chuck.deluge.project;

import java.io.File;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.chuck.deluge.model.Drum;
import org.chuck.deluge.model.EnvelopeModel;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.LfoModel;
import org.chuck.deluge.model.LfoType;
import org.chuck.deluge.model.ModKnob;
import org.chuck.deluge.model.PatchCable;
import org.chuck.deluge.model.SoundDrum;
import org.chuck.deluge.model.SynthTrackModel;
import org.chuck.deluge.xml.DelugeHexMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Serializes a single Kit or Synth track to a standalone preset XML file. Matches the Deluge
 * factory XML format (child-element style).
 */
public class KitSynthSerializer {

  public static void saveKit(KitTrackModel kit, File file) throws Exception {
    Document doc = newDoc();
    Element root = doc.createElement("kit");
    doc.appendChild(root);

    root.setAttribute("name", kit.getName());

    for (Drum drum : kit.getDrums()) {
      SoundDrum sound = (SoundDrum) drum;
      Element soundElem = doc.createElement("sound");

      // ── name ──
      Element nameElem = doc.createElement("name");
      nameElem.setTextContent(sound.getName());
      soundElem.appendChild(nameElem);

      // ── osc1 (sample reference) ──
      Element osc1 = doc.createElement("osc1");
      osc1.setAttribute("type", "sample");
      appendTextChild(doc, osc1, "transpose", "0");
      appendTextChild(doc, osc1, "cents", "0");
      appendTextChild(doc, osc1, "loopMode", "1");
      appendTextChild(doc, osc1, "reversed", sound.isReverse() ? "1" : "0");
      appendTextChild(doc, osc1, "timeStretchEnable", "0");
      appendTextChild(doc, osc1, "timeStretchAmount", "0");
      String samplePath = sound.getSamplePath() != null ? sound.getSamplePath() : "";
      appendTextChild(doc, osc1, "fileName", samplePath);
      Element zone = doc.createElement("zone");
      appendTextChild(doc, zone, "startMilliseconds", String.valueOf((int) sound.getStartMs()));
      appendTextChild(
          doc, zone, "endMilliseconds", String.valueOf((int) Math.max(sound.getEndMs(), 1)));
      osc1.appendChild(zone);
      appendTextChild(doc, osc1, "retrigPhase", String.valueOf(sound.getRetrigPhase()));
      soundElem.appendChild(osc1);

      // ── osc2 (always present, often empty) ──
      Element osc2 = doc.createElement("osc2");
      osc2.setAttribute("type", sound.getOsc2Type().toLowerCase());
      appendTextChild(doc, osc2, "transpose", "0");
      appendTextChild(doc, osc2, "cents", "0");
      appendTextChild(doc, osc2, "loopMode", "1");
      appendTextChild(doc, osc2, "reversed", "0");
      appendTextChild(doc, osc2, "timeStretchEnable", "0");
      appendTextChild(doc, osc2, "timeStretchAmount", "0");
      appendTextChild(doc, osc2, "fileName", "");
      appendTextChild(doc, osc2, "retrigPhase", String.valueOf(sound.getRetrigPhase()));
      Element zone2 = doc.createElement("zone");
      appendTextChild(doc, zone2, "startMilliseconds", "0");
      appendTextChild(doc, zone2, "endMilliseconds", "9999999");
      osc2.appendChild(zone2);
      soundElem.appendChild(osc2);

      // ── polyphonic ──
      appendTextChild(doc, soundElem, "polyphonic", sound.isPolyphonic() ? "1" : "0");

      // ── clippingAmount ──
      appendTextChild(
          doc, soundElem, "clippingAmount", String.valueOf((int) sound.getClippingAmount()));

      // ── voicePriority ──
      appendTextChild(doc, soundElem, "voicePriority", String.valueOf(sound.getVoicePriority()));

      // ── sideChainSend ──
      if (sound.getSidechainSend() > 0) {
        appendTextChild(
            doc,
            soundElem,
            "sideChainSend",
            String.valueOf((int) (sound.getSidechainSend() * Integer.MAX_VALUE)));
      }

      // ── lfo1, lfo2 ──
      appendLfo(doc, soundElem, "lfo1", sound.getLfo1());
      appendLfo(doc, soundElem, "lfo2", sound.getLfo2());

      // ── mode (subtractive for all kit sounds) ──
      appendTextChild(doc, soundElem, "mode", "subtractive");

      // ── unison ──
      Element unison = doc.createElement("unison");
      appendTextAttr(unison, "num", String.valueOf(sound.getUnisonNum()));
      appendTextAttr(unison, "detune", DelugeHexMapper.floatToHex(sound.getUnisonDetune()));
      soundElem.appendChild(unison);

      // ── compressor ──
      if (sound.getCompressorAttack() > 0
          || sound.getCompressorRelease() > 0
          || sound.getCompressorSyncLevel() > 0) {
        Element comp = doc.createElement("compressor");
        appendTextAttr(comp, "syncLevel", String.valueOf(sound.getCompressorSyncLevel()));
        appendTextAttr(
            comp,
            "attack",
            String.valueOf((int) (sound.getCompressorAttack() * Integer.MAX_VALUE)));
        appendTextAttr(
            comp,
            "release",
            String.valueOf((int) (sound.getCompressorRelease() * Integer.MAX_VALUE)));
        soundElem.appendChild(comp);
      }

      // ── delay ──
      if (sound.getDelayRate() > 0 || sound.getDelayFeedback() > 0) {
        Element delay = doc.createElement("delay");
        appendTextAttr(delay, "pingPong", "1");
        appendTextAttr(delay, "analog", "0");
        appendTextAttr(delay, "syncLevel", "7");
        soundElem.appendChild(delay);
      }

      // ── lpfMode ──
      String lpfModeStr =
          switch (sound.getLpfMode()) {
            case LADDER_24 -> "24dB";
            case SVF -> "SVF";
            default -> "12dB";
          };
      appendTextChild(doc, soundElem, "lpfMode", lpfModeStr);

      // ── hpfMode ──
      String hpfModeStr =
          switch (sound.getHpfMode()) {
            case LADDER_24 -> "24dB";
            case SVF -> "SVF";
            case DRIVE -> "DRIVE";
            case SVF_BAND -> "SVF Band";
            case SVF_NOTCH -> "SVF Notch";
            default -> "12dB";
          };
      appendTextChild(doc, soundElem, "hpfMode", hpfModeStr);

      // ── modFXType ──
      String mfx = sound.getModFxType() != null ? sound.getModFxType().toLowerCase() : "none";
      appendTextChild(doc, soundElem, "modFXType", mfx);

      // ── defaultParams ──
      Element dp = doc.createElement("defaultParams");
      appendHexChild(doc, dp, "arpeggiatorGate", sound.getArpeggiatorGate());
      appendHexChild(doc, dp, "portamento", sound.getPortamento());
      appendHexChild(doc, dp, "compressorShape", 0.92f); // typical default
      appendHexChild(doc, dp, "oscAVolume", sound.getOscAVolume());
      appendHexChild(doc, dp, "oscAPulseWidth", 0f);
      appendHexChild(doc, dp, "oscBVolume", sound.getOscBVolume());
      appendHexChild(doc, dp, "oscBPulseWidth", 0f);
      appendHexChild(doc, dp, "noiseVolume", sound.getNoiseVolume());
      appendHexChild(doc, dp, "volume", sound.getVolume());
      appendHexChild(doc, dp, "pan", sound.getPan());
      appendHexFreq(doc, dp, "lpfFrequency", sound.getLpfFreq());
      appendHexChild(doc, dp, "lpfResonance", sound.getLpfRes());
      appendHexFreq(doc, dp, "hpfFrequency", sound.getHpfFreq());
      appendHexChild(doc, dp, "hpfResonance", sound.getHpfRes());
      appendHexChild(doc, dp, "hpfMorph", sound.getHpfMorph());
      appendHexChild(doc, dp, "modFXRate", sound.getModFxRate());
      appendHexChild(doc, dp, "modFXDepth", sound.getModFxDepth());
      appendHexChild(doc, dp, "modFXOffset", sound.getModFxOffset());
      appendHexChild(doc, dp, "modFXFeedback", sound.getModFxFeedback());
      appendHexChild(doc, dp, "delayRate", sound.getDelayRate());
      appendHexChild(doc, dp, "delayFeedback", sound.getDelayFeedback());
      appendHexChild(doc, dp, "reverbAmount", sound.getReverbAmount());
      appendHexChild(doc, dp, "arpeggiatorRate", 0f);
      appendHexChild(doc, dp, "stutterRate", sound.getStutterRate());
      appendHexChild(doc, dp, "sampleRateReduction", sound.getSampleRateReduction());
      appendHexChild(doc, dp, "bitCrush", sound.getBitCrush());
      appendHexChild(doc, dp, "waveIndex", sound.getWaveIndex());

      // Envelopes 1-4 inside defaultParams
      appendEnvelope(doc, dp, "envelope1", sound.getAdsr());
      appendEnvelope(doc, dp, "envelope2", sound.getEnv2());
      appendEnvelope(doc, dp, "envelope3", sound.getEnv3());
      appendEnvelope(doc, dp, "envelope4", sound.getEnv4());

      // LFO rates inside defaultParams
      appendHexChild(
          doc, dp, "lfo1Rate", sound.getLfo1().rateHz() / 100.0f); // normalize to 0-1 range
      appendHexChild(doc, dp, "lfo2Rate", sound.getLfo2().rateHz() / 100.0f);

      // FM params (default values)
      appendHexChild(doc, dp, "modulator1Amount", sound.getFmAmount());
      appendHexChild(doc, dp, "modulator1Feedback", 0f);
      appendHexChild(doc, dp, "modulator2Amount", 0f);
      appendHexChild(doc, dp, "modulator2Feedback", 0f);
      appendHexChild(doc, dp, "carrier1Feedback", 0f);
      appendHexChild(doc, dp, "carrier2Feedback", 0f);

      // Patch cables inside defaultParams
      if (!sound.getPatchCables().isEmpty()) {
        Element pcContainer = doc.createElement("patchCables");
        for (PatchCable pc : sound.getPatchCables()) {
          Element cable = doc.createElement("patchCable");
          appendTextChild(doc, cable, "source", pc.source());
          appendTextChild(doc, cable, "destination", pc.destination());
          appendHexChild(doc, cable, "amount", pc.amount());
          pcContainer.appendChild(cable);
        }
        dp.appendChild(pcContainer);
      }

      // Equalizer inside defaultParams
      Element eq = doc.createElement("equalizer");
      appendHexChild(doc, eq, "bass", sound.getEqBass());
      appendHexChild(doc, eq, "treble", sound.getEqTreble());
      appendHexChild(doc, eq, "bassFrequency", 0f);
      appendHexChild(doc, eq, "trebleFrequency", 0f);
      dp.appendChild(eq);

      appendHexChild(doc, dp, "modFXOffset", 0f);
      appendHexChild(doc, dp, "modFXFeedback", 0f);
      soundElem.appendChild(dp);

      // ── midiKnobs (empty container) ──
      soundElem.appendChild(doc.createElement("midiKnobs"));

      // ── modKnobs ──
      boolean hasKnobs = sound.getModKnobs().stream().anyMatch(k -> !"NONE".equals(k.param()));
      if (hasKnobs) {
        Element mkContainer = doc.createElement("modKnobs");
        for (ModKnob mk : sound.getModKnobs()) {
          if (!"NONE".equals(mk.param())) {
            Element knob = doc.createElement("modKnob");
            appendTextChild(doc, knob, "controlsParam", mk.param());
            if (mk.patchSource() != null && !"NONE".equals(mk.patchSource())) {
              appendTextChild(doc, knob, "patchAmountFromSource", mk.patchSource());
            }
            mkContainer.appendChild(knob);
          }
        }
        soundElem.appendChild(mkContainer);
      }

      root.appendChild(soundElem);
    }

    write(doc, file);
  }

  public static void saveSynth(SynthTrackModel synth, File file) throws Exception {
    Document doc = newDoc();
    Element root = doc.createElement("sound");
    doc.appendChild(root);

    // ── osc1 ──
    Element osc1 = doc.createElement("osc1");
    appendTextChild(doc, osc1, "type", synth.getOsc1Type().toLowerCase());
    appendTextChild(doc, osc1, "transpose", "0");
    appendTextChild(doc, osc1, "cents", "0");
    appendTextChild(doc, osc1, "retrigPhase", String.valueOf(synth.getRetrigPhase()));
    if (synth.getDx7Patch() != null && !synth.getDx7Patch().isEmpty()) {
      osc1.setAttribute("dx7patch", synth.getDx7Patch());
    }
    root.appendChild(osc1);

    // ── osc2 ──
    Element osc2 = doc.createElement("osc2");
    appendTextChild(doc, osc2, "type", synth.getOsc2Type().toLowerCase());
    appendTextChild(doc, osc2, "transpose", "0");
    appendTextChild(doc, osc2, "cents", "0");
    appendTextChild(doc, osc2, "retrigPhase", String.valueOf(synth.getRetrigPhase()));
    root.appendChild(osc2);

    // ── polyphonic ──
    String polyVal =
        switch (synth.getPolyphony()) {
          case MONO -> "0";
          case LEGATO -> "legato";
          default -> "1";
        };
    appendTextChild(doc, root, "polyphonic", polyVal);

    // ── clippingAmount ──
    appendTextChild(doc, root, "clippingAmount", "0");

    // ── voicePriority ──
    appendTextChild(doc, root, "voicePriority", "1");

    // ── LFOs ──
    appendSynthLfo(doc, root, "lfo1", synth.getLfo(0));
    appendSynthLfo(doc, root, "lfo2", synth.getLfo(1));

    // ── synth mode ──
    String mode =
        switch (synth.getSynthMode()) {
          case 1 -> "fm";
          case 2 -> "ringmod";
          default -> "subtractive";
        };
    appendTextChild(doc, root, "mode", mode);

    // ── transpose (global synth transpose) ──
    appendTextChild(doc, root, "transpose", "0");

    // ── unison ──
    Element unison = doc.createElement("unison");
    appendTextChild(doc, unison, "num", String.valueOf(synth.getUnisonNum()));
    appendTextChild(doc, unison, "detune", String.valueOf((int) synth.getUnisonDetune()));
    root.appendChild(unison);

    // ── arpeggiator ──
    Element arp = doc.createElement("arpeggiator");
    arp.setAttribute("mode", synth.getArp().mode().toLowerCase());
    arp.setAttribute("active", synth.getArp().active() ? "1" : "0");
    appendHexChild(doc, arp, "rate", synth.getArp().rate());
    appendTextChild(doc, arp, "octaves", String.valueOf(synth.getArp().octaves()));
    appendHexChild(doc, arp, "gate", synth.getArp().gate());
    appendTextChild(doc, arp, "syncLevel", String.valueOf(synth.getArp().syncLevel()));
    appendTextChild(doc, arp, "noteMode", synth.getArp().noteMode().toLowerCase());
    appendTextChild(doc, arp, "octaveMode", synth.getArp().octaveMode().toLowerCase());
    appendTextChild(doc, arp, "stepRepeat", String.valueOf(synth.getArp().stepRepeat()));
    appendTextChild(doc, arp, "rhythmIndex", String.valueOf(synth.getArp().rhythmIndex()));
    appendTextChild(doc, arp, "seqLength", String.valueOf(synth.getArp().seqLength()));
    appendHexChild(doc, arp, "octaveSpread", synth.getArp().octaveSpread());
    appendHexChild(doc, arp, "gateSpread", synth.getArp().gateSpread());
    appendHexChild(doc, arp, "velSpread", synth.getArp().velSpread());
    appendTextChild(doc, arp, "ratchetAmount", String.valueOf(synth.getArp().ratchetAmount()));
    appendTextChild(doc, arp, "noteProbability", String.valueOf(synth.getArp().noteProbability()));
    appendTextChild(doc, arp, "chordPolyphony", String.valueOf(synth.getArp().chordPolyphony()));
    appendTextChild(
        doc, arp, "chordProbability", String.valueOf(synth.getArp().chordProbability()));
    root.appendChild(arp);

    // ── delay ──
    if (synth.getDelaySend() > 0) {
      Element delay = doc.createElement("delay");
      appendTextChild(doc, delay, "pingPong", "1");
      appendTextChild(doc, delay, "analog", "1");
      appendTextChild(doc, delay, "syncLevel", "7");
      root.appendChild(delay);
    }

    // ── lpfMode ──
    String lpfModeStr =
        switch (synth.getFilterMode()) {
          case LADDER_24 -> "24dB";
          case SVF -> "SVF";
          default -> "12dB";
        };
    appendTextChild(doc, root, "lpfMode", lpfModeStr);

    // ── hpfMode ──
    String hpfModeSynthStr =
        switch (synth.getHpfMode()) {
          case LADDER_24 -> "24dB";
          case SVF -> "SVF";
          case DRIVE -> "DRIVE";
          case SVF_BAND -> "SVF Band";
          case SVF_NOTCH -> "SVF Notch";
          default -> "12dB";
        };
    appendTextChild(doc, root, "hpfMode", hpfModeSynthStr);

    // ── modFXType ──
    String mfx = synth.getModFxType() != null ? synth.getModFxType().toLowerCase() : "none";
    appendTextChild(doc, root, "modFXType", mfx);

    // ── compressor ──
    float compAttack = synth.getCompressorAttack();
    float compRelease = synth.getCompressorRelease();
    int compSync = synth.getCompressorSyncLevel();
    if (compAttack > 0 || compRelease > 0 || compSync > 0) {
      Element comp = doc.createElement("compressor");
      appendTextAttr(comp, "syncLevel", String.valueOf(compSync));
      appendTextAttr(comp, "attack", String.valueOf((int) (compAttack * Integer.MAX_VALUE)));
      appendTextAttr(comp, "release", String.valueOf((int) (compRelease * Integer.MAX_VALUE)));
      root.appendChild(comp);
    }

    // ── defaultParams ──
    Element dp = doc.createElement("defaultParams");
    appendHexChild(doc, dp, "arpeggiatorGate", synth.getArp().gate());
    appendHexChild(doc, dp, "portamento", synth.getPortamento());
    appendHexChild(doc, dp, "compressorShape", 0.92f);
    appendHexChild(doc, dp, "oscAVolume", synth.getOscMix());
    appendHexChild(doc, dp, "oscAPulseWidth", 0f);
    appendHexChild(doc, dp, "oscBVolume", 1.0f - synth.getOscMix());
    appendHexChild(doc, dp, "oscBPulseWidth", 0f);
    appendHexChild(doc, dp, "noiseVolume", synth.getNoiseVol());
    appendHexChild(doc, dp, "volume", synth.getVolume());
    appendHexChild(doc, dp, "pan", synth.getPan());
    appendHexFreq(doc, dp, "lpfFrequency", synth.getLpfFreq());
    appendHexChild(doc, dp, "lpfResonance", synth.getLpfRes());
    appendHexFreq(doc, dp, "hpfFrequency", synth.getHpfFreq());
    appendHexChild(doc, dp, "hpfResonance", synth.getHpfRes());
    appendHexChild(doc, dp, "hpfMorph", synth.getHpfMorph());

    // FM params
    appendHexChild(doc, dp, "modulator1Amount", synth.getFmAmount());
    appendHexChild(doc, dp, "modulator1Feedback", synth.getModulator1Feedback());
    appendHexChild(doc, dp, "modulator2Amount", synth.getModulator2Amount());
    appendHexChild(doc, dp, "modulator2Feedback", synth.getModulator2Feedback());
    appendHexChild(doc, dp, "carrier1Feedback", synth.getCarrier1Feedback());
    appendHexChild(doc, dp, "carrier2Feedback", synth.getCarrier2Feedback());

    appendHexChild(doc, dp, "modFXRate", synth.getModFxRate());
    appendHexChild(doc, dp, "modFXDepth", synth.getModFxDepth());
    appendHexChild(doc, dp, "modFXOffset", 0f);
    appendHexChild(doc, dp, "modFXFeedback", synth.getModFxFeedback());
    appendHexChild(doc, dp, "delayRate", synth.getDelaySend());
    appendHexChild(doc, dp, "delayFeedback", 0f);
    appendHexChild(doc, dp, "reverbAmount", synth.getReverbSend());
    appendHexChild(doc, dp, "arpeggiatorRate", 0f);
    appendHexChild(doc, dp, "stutterRate", synth.getStutterRate());
    appendHexChild(doc, dp, "sampleRateReduction", synth.getSampleRateReduction());
    appendHexChild(doc, dp, "bitCrush", synth.getBitCrush());
    appendHexChild(doc, dp, "waveIndex", synth.getWaveIndex());

    // Envelopes 0-3 inside defaultParams
    for (int i = 0; i < 4; i++) {
      appendEnvelope(doc, dp, "envelope" + (i + 1), synth.getEnv(i));
    }

    // LFO rates
    appendHexChild(doc, dp, "lfo1Rate", synth.getLfo(0).rateHz() / 100.0f);
    appendHexChild(doc, dp, "lfo2Rate", synth.getLfo(1).rateHz() / 100.0f);

    // Patch cables inside defaultParams
    if (!synth.getPatchCables().isEmpty()) {
      Element pcContainer = doc.createElement("patchCables");
      for (PatchCable pc : synth.getPatchCables()) {
        Element cable = doc.createElement("patchCable");
        appendTextChild(doc, cable, "source", pc.source());
        appendTextChild(doc, cable, "destination", pc.destination());
        appendHexChild(doc, cable, "amount", pc.amount());
        pcContainer.appendChild(cable);
      }
      dp.appendChild(pcContainer);
    }

    // Equalizer
    Element eq = doc.createElement("equalizer");
    appendHexChild(doc, eq, "bass", synth.getEqBass());
    appendHexChild(doc, eq, "treble", synth.getEqTreble());
    appendHexChild(doc, eq, "bassFrequency", 0f);
    appendHexChild(doc, eq, "trebleFrequency", 0f);
    dp.appendChild(eq);

    appendHexChild(doc, dp, "modFXOffset", 0f);
    appendHexChild(doc, dp, "modFXFeedback", synth.getModFxFeedback());
    root.appendChild(dp);

    // ── midiKnobs ──
    root.appendChild(doc.createElement("midiKnobs"));

    // ── modKnobs ──
    boolean hasKnobs = synth.getModKnobs().stream().anyMatch(k -> !"NONE".equals(k.param()));
    if (hasKnobs) {
      Element mkContainer = doc.createElement("modKnobs");
      for (ModKnob mk : synth.getModKnobs()) {
        if (!"NONE".equals(mk.param())) {
          Element knob = doc.createElement("modKnob");
          appendTextChild(doc, knob, "controlsParam", mk.param());
          if (mk.patchSource() != null && !"NONE".equals(mk.patchSource())) {
            appendTextChild(doc, knob, "patchAmountFromSource", mk.patchSource());
          }
          mkContainer.appendChild(knob);
        }
      }
      root.appendChild(mkContainer);
    }

    write(doc, file);
  }

  // ── Helper serializers ──

  /** Append an LFO element with type and syncLevel children. */
  public static void appendLfo(Document doc, Element parent, String tag, LfoModel lfo) {
    Element lfoElem = doc.createElement(tag);
    appendTextChild(doc, lfoElem, "type", lfoTypeName(lfo.waveform()));
    if (lfo.syncLevel() > 0) {
      appendTextChild(doc, lfoElem, "syncLevel", String.valueOf(lfo.syncLevel()));
    }
    parent.appendChild(lfoElem);
  }

  /** Append a synth-style LFO element (without isLocal distinction). */
  public static void appendSynthLfo(Document doc, Element parent, String tag, LfoModel lfo) {
    Element lfoElem = doc.createElement(tag);
    appendTextChild(doc, lfoElem, "type", lfoTypeName(lfo.waveform()));
    if (lfo.syncLevel() > 0) {
      appendTextChild(doc, lfoElem, "syncLevel", String.valueOf(lfo.syncLevel()));
    }
    parent.appendChild(lfoElem);
  }

  /** Convert LfoType to lowercase XML name. */
  public static String lfoTypeName(LfoType type) {
    return switch (type) {
      case SINE -> "sine";
      case SAW -> "saw";
      case SQUARE -> "square";
      case TRIANGLE -> "triangle";
      case S_AND_H -> "s&h";
      case RANDOM_WALK -> "randomWalk";
      case WARBLER -> "warbler";
    };
  }

  /** Append an envelope element with attack/decay/sustain/release children. */
  public static void appendEnvelope(Document doc, Element parent, String tag, EnvelopeModel env) {
    Element envElem = doc.createElement(tag);
    // Envelope times use exponential bipolar mapping; sustain uses linear bipolar mapping.
    appendHexChild(doc, envElem, "attack", DelugeHexMapper.normFromEnvTime(env.attack()));
    appendHexChild(doc, envElem, "decay", DelugeHexMapper.normFromEnvTime(env.decay()));
    appendHexChild(doc, envElem, "sustain", DelugeHexMapper.normFromSustain(env.sustain()));
    appendHexChild(doc, envElem, "release", DelugeHexMapper.normFromEnvTime(env.release()));
    parent.appendChild(envElem);
  }

  /** Append a child element with a hex string value from a normalized float. */
  public static void appendHexChild(Document doc, Element parent, String tag, float value) {
    if (parent.getElementsByTagName(tag).getLength() > 0) return;
    Element child = doc.createElement(tag);
    child.setTextContent(DelugeHexMapper.floatToHex(value));
    parent.appendChild(child);
  }

  /** Append a child element with a hex frequency value from a Hz float. */
  public static void appendHexFreq(Document doc, Element parent, String tag, float hz) {
    if (parent.getElementsByTagName(tag).getLength() > 0) return;
    Element child = doc.createElement(tag);
    child.setTextContent(DelugeHexMapper.hzToHex(hz));
    parent.appendChild(child);
  }

  /** Append a text-attribute to an element: e.g. &lt;el attr="value"&gt;. */
  public static void appendTextAttr(Element el, String attr, String value) {
    el.setAttribute(attr, value);
  }

  /** Append a child element with plain text content. */
  public static void appendTextChild(Document doc, Element parent, String tag, String value) {
    Element child = doc.createElement(tag);
    child.setTextContent(value);
    parent.appendChild(child);
  }

  private static Document newDoc() throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    DocumentBuilder b = f.newDocumentBuilder();
    return b.newDocument();
  }

  private static void write(Document doc, File file) throws Exception {
    Transformer t = TransformerFactory.newInstance().newTransformer();
    t.setOutputProperty(OutputKeys.INDENT, "yes");
    t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
    t.transform(new DOMSource(doc), new StreamResult(file));
  }
}
