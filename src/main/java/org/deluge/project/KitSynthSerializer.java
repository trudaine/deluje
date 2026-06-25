package org.deluge.project;

import java.io.File;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.deluge.firmware2.Param;
import org.deluge.model.Drum;
import org.deluge.model.EnvelopeModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.LfoModel;
import org.deluge.model.LfoType;
import org.deluge.model.MidiKnob;
import org.deluge.model.ModKnob;
import org.deluge.model.PatchCable;
import org.deluge.model.SoundDrum;
import org.deluge.model.SynthTrackModel;
import org.deluge.xml.DelugeHexMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Serializes a single Kit or Synth track to a standalone preset XML file. Matches the Deluge
 * factory XML format (child-element style).
 */
public class KitSynthSerializer {

  public static void saveKit(KitTrackModel kit, File file) throws Exception {
    Document doc = newDoc();
    Element root = serializeKit(doc, kit, false);
    doc.appendChild(root);
    write(doc, file);
  }

  /** Serializes a KitTrackModel into an XML <kit> element under the given DOM Document. */
  public static Element serializeKit(Document doc, KitTrackModel kit, boolean isSongSlot)
      throws Exception {
    Element root = doc.createElement("kit");
    root.setAttribute("presetName", kit.getName());
    if (isSongSlot) {
      appendTextChild(doc, root, "presetSlot", kit.getName());
    }

    for (Drum drum : kit.getDrums()) {
      SoundDrum sound = (SoundDrum) drum;
      Element soundElem = doc.createElement("sound");

      // ── name ──
      Element nameElem = doc.createElement("name");
      nameElem.setTextContent(sound.getName());
      soundElem.appendChild(nameElem);

      boolean isSampleBased = sound.getSamplePath() != null && !sound.getSamplePath().isEmpty();
      if (isSampleBased && isSongSlot) {
        // ── sample (song slot sample reference) ──
        Element sample = doc.createElement("sample");
        sample.setAttribute("fileName", sound.getSamplePath());
        soundElem.appendChild(sample);
      } else {
        // ── osc1 (sample or synth reference) ──
        Element osc1 = doc.createElement("osc1");
        if (isSampleBased) {
          osc1.setAttribute("type", "sample");
          appendTextChild(doc, osc1, "fileName", sound.getSamplePath());
        } else {
          osc1.setAttribute("type", "none");
          appendTextChild(doc, osc1, "fileName", "");
        }
        appendTextChild(doc, osc1, "transpose", "0");
        appendTextChild(doc, osc1, "cents", "0");
        appendTextChild(doc, osc1, "loopMode", "1");
        appendTextChild(doc, osc1, "reversed", sound.isReverse() ? "1" : "0");
        appendTextChild(doc, osc1, "timeStretchEnable", "0");
        appendTextChild(doc, osc1, "timeStretchAmount", "0");
        appendTextChild(
            doc, osc1, "wavetableIndexPct", String.valueOf(sound.getWavetableIndexPct()));
        Element zone = doc.createElement("zone");
        appendTextChild(doc, zone, "startMilliseconds", String.valueOf((int) sound.getStartMs()));
        appendTextChild(
            doc, zone, "endMilliseconds", String.valueOf((int) Math.max(sound.getEndMs(), 1)));
        osc1.appendChild(zone);
        appendTextChild(doc, osc1, "retrigPhase", String.valueOf(sound.getOsc1RetrigPhase()));
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
        appendTextChild(
            doc,
            osc2,
            "fileName",
            sound.getOsc2SamplePath() != null ? sound.getOsc2SamplePath() : "");
        appendTextChild(doc, osc2, "retrigPhase", String.valueOf(sound.getOsc2RetrigPhase()));
        Element zone2 = doc.createElement("zone");
        appendTextChild(doc, zone2, "startMilliseconds", "0");
        appendTextChild(doc, zone2, "endMilliseconds", "9999999");
        osc2.appendChild(zone2);
        soundElem.appendChild(osc2);
      }

      // ── polyphonic ──
      String polyVal =
          switch (sound.getPolyphony()) {
            case MONO -> "0";
            case LEGATO -> "legato";
            case CHOKE -> "choke";
            case AUTO -> "auto";
            default -> "1";
          };
      appendTextChild(doc, soundElem, "polyphonic", polyVal);

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
      appendHexChildUnipolar(doc, dp, "arpeggiatorGate", sound.getArpeggiatorGate());
      appendHexChildUnipolar(doc, dp, "portamento", sound.getPortamento());
      appendHexChildUnipolar(doc, dp, "compressorShape", sound.getCompressorShape());
      appendHexChildUnipolar(doc, dp, "oscAVolume", sound.getOscAVolume());
      appendHexChildUnipolar(doc, dp, "oscAPulseWidth", 0f);
      appendHexChildUnipolar(doc, dp, "oscBVolume", sound.getOscBVolume());
      appendHexChildUnipolar(doc, dp, "oscBPulseWidth", 0f);
      appendHexChildUnipolar(doc, dp, "noiseVolume", sound.getNoiseVolume());
      appendHexChildUnipolar(doc, dp, "volume", sound.getVolume());
      appendHexChild(doc, dp, "pan", sound.getPan());
      appendHexFreq(doc, dp, "lpfFrequency", sound.getLpfFreq());
      appendHexChildUnipolar(doc, dp, "lpfResonance", sound.getLpfRes());
      appendHexFreq(doc, dp, "hpfFrequency", sound.getHpfFreq());
      appendHexChildUnipolar(doc, dp, "hpfResonance", sound.getHpfRes());
      appendHexChildUnipolar(doc, dp, "hpfMorph", sound.getHpfMorph());
      appendHexChildUnipolar(doc, dp, "modFXRate", sound.getModFxRate());
      appendHexChildUnipolar(doc, dp, "modFXDepth", sound.getModFxDepth());
      appendHexChildUnipolar(doc, dp, "modFXOffset", sound.getModFxOffset());
      appendHexChildUnipolar(doc, dp, "modFXFeedback", sound.getModFxFeedback());
      appendHexChildUnipolar(doc, dp, "delayRate", sound.getDelayRate());
      appendHexChildUnipolar(doc, dp, "delayFeedback", sound.getDelayFeedback());
      appendHexChildUnipolar(doc, dp, "reverbAmount", sound.getReverbAmount());
      appendHexChildUnipolar(doc, dp, "arpeggiatorRate", 0f);
      appendHexChildUnipolar(doc, dp, "stutterRate", sound.getStutterRate());
      appendHexChildUnipolar(doc, dp, "sampleRateReduction", sound.getSampleRateReduction());
      appendHexChildUnipolar(doc, dp, "bitCrush", sound.getBitCrush());
      appendHexChildUnipolar(doc, dp, "waveIndex", sound.getWaveIndex());

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
      appendHexChildUnipolar(doc, dp, "modulator1Amount", sound.getFmAmount());
      appendHexChild(doc, dp, "modulator1Feedback", 0f);
      appendHexChild(doc, dp, "modulator2Amount", 0f);
      appendHexChild(doc, dp, "modulator2Feedback", 0f);
      appendHexChild(doc, dp, "carrier1Feedback", 0f);
      appendHexChild(doc, dp, "carrier2Feedback", 0f);

      // Patch cables inside defaultParams
      if (!sound.getPatchCables().isEmpty()) {
        Element pcContainer = doc.createElement("patchCables");
        for (PatchCable pc : sound.getPatchCables()) {
          pcContainer.appendChild(createPatchCableElement(doc, pc));
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

      // ── midiKnobs ──
      serializeMidiKnobs(doc, soundElem, sound.getMidiKnobs());

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

      // ── midiOutput ──
      if (sound.getMidiChannel() != 255 || sound.getNoteForDrum() != 255) {
        Element midiOutput = doc.createElement("midiOutput");
        midiOutput.setAttribute("channel", String.valueOf(sound.getMidiChannel()));
        midiOutput.setAttribute("noteForDrum", String.valueOf(sound.getNoteForDrum()));
        soundElem.appendChild(midiOutput);
      }

      root.appendChild(soundElem);
    }

    appendTextChild(doc, root, "selectedDrumIndex", String.valueOf(kit.getSelectedDrumIndex()));

    return root;
  }

  public static void saveSynth(SynthTrackModel synth, File file) throws Exception {
    Document doc = newDoc();
    Element root = serializeSynth(doc, synth, false);
    doc.appendChild(root);
    write(doc, file);
  }

  /** Serializes a SynthTrackModel into an XML <sound> element under the given DOM Document. */
  public static Element serializeSynth(Document doc, SynthTrackModel synth, boolean isSongSlot)
      throws Exception {
    Element root = doc.createElement("sound");
    root.setAttribute("presetName", synth.getName());
    if (isSongSlot) {
      appendTextChild(doc, root, "presetSlot", synth.getName());
    }

    // ── osc1 ──
    Element osc1 = doc.createElement("osc1");
    appendTextChild(doc, osc1, "type", synth.getOsc1Type().toLowerCase());
    appendTextChild(doc, osc1, "transpose", "0");
    appendTextChild(doc, osc1, "cents", "0");
    appendTextChild(doc, osc1, "retrigPhase", String.valueOf(synth.getOsc1RetrigPhase()));
    if (synth.getDx7Patch() != null && !synth.getDx7Patch().isEmpty()) {
      osc1.setAttribute("dx7patch", synth.getDx7Patch());
    }
    root.appendChild(osc1);

    // ── osc2 ──
    Element osc2 = doc.createElement("osc2");
    appendTextChild(doc, osc2, "type", synth.getOsc2Type().toLowerCase());
    appendTextChild(doc, osc2, "transpose", "0");
    appendTextChild(doc, osc2, "cents", "0");
    appendTextChild(doc, osc2, "retrigPhase", String.valueOf(synth.getOsc2RetrigPhase()));
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
    appendSynthLfo(doc, root, "lfo3", synth.getLfo(2));
    appendSynthLfo(doc, root, "lfo4", synth.getLfo(3));

    // ── customLfoWave ──
    StringBuilder sb = new StringBuilder();
    int[] wave = synth.getCustomLfoWave();
    for (int i = 0; i < 256; i++) {
      sb.append(wave[i]);
      if (i < 255) sb.append(",");
    }
    appendTextChild(doc, root, "customLfoWave", sb.toString());

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

    // ── modulator1 ──
    Element mod1 = doc.createElement("modulator1");
    mod1.setAttribute("transpose", String.valueOf(synth.getModulator1Transpose()));
    mod1.setAttribute("cents", String.valueOf(synth.getModulator1Cents()));
    mod1.setAttribute("retrigPhase", String.valueOf(synth.getMod1RetrigPhase()));
    root.appendChild(mod1);

    // ── modulator2 ──
    Element mod2 = doc.createElement("modulator2");
    mod2.setAttribute("transpose", String.valueOf(synth.getModulator2Transpose()));
    mod2.setAttribute("cents", String.valueOf(synth.getModulator2Cents()));
    mod2.setAttribute("retrigPhase", String.valueOf(synth.getMod2RetrigPhase()));
    mod2.setAttribute("toModulator1", synth.isModulator1ToModulator0() ? "1" : "0");
    root.appendChild(mod2);

    // ── unison ──
    Element unison = doc.createElement("unison");
    appendTextChild(doc, unison, "num", String.valueOf(synth.getUnisonNum()));
    appendTextChild(doc, unison, "detune", DelugeHexMapper.floatToHex(synth.getUnisonDetune()));
    appendTextChild(
        doc, unison, "spread", DelugeHexMapper.floatToHex(synth.getUnisonStereoSpread()));
    root.appendChild(unison);

    // ── arpeggiator ──
    Element arp = doc.createElement("arpeggiator");
    arp.setAttribute("mode", synth.getArp().mode().toLowerCase());
    arp.setAttribute("active", synth.getArp().active() ? "1" : "0");
    arp.setAttribute("sequenceLength", String.valueOf(synth.getArp().seqLength()));
    arp.setAttribute("arpMode", synth.getArp().active() ? "arp" : "off");
    if (synth.getArp().notePattern() != null && !synth.getArp().notePattern().isEmpty()) {
      arp.setAttribute("notePattern", synth.getArp().notePattern());
    }
    arp.setAttribute("chordType", String.valueOf(synth.getArp().chordType()));

    arp.setAttribute("numOctaves", String.valueOf(synth.getArp().numOctaves()));
    arp.setAttribute("kitArp", String.valueOf(synth.getArp().kitArp()));
    arp.setAttribute("randomizerLock", String.valueOf(synth.getArp().randomizerLock()));

    arp.setAttribute("lastLockedNoteProb", String.valueOf(synth.getArp().lastLockedNoteProb()));
    if (synth.getArp().lockedNoteProbArray() != null
        && !synth.getArp().lockedNoteProbArray().isEmpty()) {
      arp.setAttribute("lockedNoteProbArray", synth.getArp().lockedNoteProbArray());
    }

    arp.setAttribute("lastLockedBassProb", String.valueOf(synth.getArp().lastLockedBassProb()));
    if (synth.getArp().lockedBassProbArray() != null
        && !synth.getArp().lockedBassProbArray().isEmpty()) {
      arp.setAttribute("lockedBassProbArray", synth.getArp().lockedBassProbArray());
    }

    arp.setAttribute("lastLockedSwapProb", String.valueOf(synth.getArp().lastLockedSwapProb()));
    if (synth.getArp().lockedSwapProbArray() != null
        && !synth.getArp().lockedSwapProbArray().isEmpty()) {
      arp.setAttribute("lockedSwapProbArray", synth.getArp().lockedSwapProbArray());
    }

    arp.setAttribute("lastLockedGlideProb", String.valueOf(synth.getArp().lastLockedGlideProb()));
    if (synth.getArp().lockedGlideProbArray() != null
        && !synth.getArp().lockedGlideProbArray().isEmpty()) {
      arp.setAttribute("lockedGlideProbArray", synth.getArp().lockedGlideProbArray());
    }

    arp.setAttribute(
        "lastLockedReverseProb", String.valueOf(synth.getArp().lastLockedReverseProb()));
    if (synth.getArp().lockedReverseProbArray() != null
        && !synth.getArp().lockedReverseProbArray().isEmpty()) {
      arp.setAttribute("lockedReverseProbArray", synth.getArp().lockedReverseProbArray());
    }

    arp.setAttribute("lastLockedChordProb", String.valueOf(synth.getArp().lastLockedChordProb()));
    if (synth.getArp().lockedChordProbArray() != null
        && !synth.getArp().lockedChordProbArray().isEmpty()) {
      arp.setAttribute("lockedChordProbArray", synth.getArp().lockedChordProbArray());
    }

    arp.setAttribute(
        "lastLockedRatchetProb", String.valueOf(synth.getArp().lastLockedRatchetProb()));
    if (synth.getArp().lockedRatchetProbArray() != null
        && !synth.getArp().lockedRatchetProbArray().isEmpty()) {
      arp.setAttribute("lockedRatchetProbArray", synth.getArp().lockedRatchetProbArray());
    }

    arp.setAttribute(
        "lastLockedVelocitySpread", String.valueOf(synth.getArp().lastLockedVelocitySpread()));
    if (synth.getArp().lockedVelocitySpreadArray() != null
        && !synth.getArp().lockedVelocitySpreadArray().isEmpty()) {
      arp.setAttribute("lockedVelocitySpreadArray", synth.getArp().lockedVelocitySpreadArray());
    }

    arp.setAttribute("lastLockedGateSpread", String.valueOf(synth.getArp().lastLockedGateSpread()));
    if (synth.getArp().lockedGateSpreadArray() != null
        && !synth.getArp().lockedGateSpreadArray().isEmpty()) {
      arp.setAttribute("lockedGateSpreadArray", synth.getArp().lockedGateSpreadArray());
    }

    arp.setAttribute(
        "lastLockedOctaveSpread", String.valueOf(synth.getArp().lastLockedOctaveSpread()));
    if (synth.getArp().lockedOctaveSpreadArray() != null
        && !synth.getArp().lockedOctaveSpreadArray().isEmpty()) {
      arp.setAttribute("lockedOctaveSpreadArray", synth.getArp().lockedOctaveSpreadArray());
    }
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
    appendHexChildUnipolar(doc, dp, "arpeggiatorGate", synth.getArp().gate());
    appendHexChildUnipolar(doc, dp, "portamento", synth.getPortamento());
    appendHexChildUnipolar(doc, dp, "compressorShape", synth.getCompressorShape());
    appendHexChildUnipolar(doc, dp, "oscAVolume", synth.getOscMix());
    appendHexChildUnipolar(doc, dp, "oscAPulseWidth", 0f);
    appendHexChildUnipolar(doc, dp, "oscBVolume", 1.0f - synth.getOscMix());
    appendHexChildUnipolar(doc, dp, "oscBPulseWidth", 0f);
    if (synth.getPitchAdjustQ31() != Integer.MIN_VALUE) {
      appendRawQ31Child(doc, dp, "pitchAdjust", synth.getPitchAdjustQ31());
    }
    Integer pitchVal = synth.getRawParamKnobs().get(Param.LOCAL_PITCH_ADJUST);
    int pitchQ31 = pitchVal != null ? pitchVal : 0;
    if (pitchQ31 != 0) {
      appendRawQ31Child(doc, dp, "pitch", pitchQ31);
    }
    if (synth.getOsc1PitchAdjustQ31() != Integer.MIN_VALUE) {
      appendRawQ31Child(doc, dp, "oscAPitchAdjust", synth.getOsc1PitchAdjustQ31());
    }
    if (synth.getOsc2PitchAdjustQ31() != Integer.MIN_VALUE) {
      appendRawQ31Child(doc, dp, "oscBPitchAdjust", synth.getOsc2PitchAdjustQ31());
    }
    appendHexChildUnipolar(doc, dp, "noiseVolume", synth.getNoiseVol());
    appendHexChildUnipolar(doc, dp, "volume", synth.getVolume());
    appendHexChild(doc, dp, "pan", synth.getPan());
    appendHexFreq(doc, dp, "lpfFrequency", synth.getLpfFreq());
    appendHexChildUnipolar(doc, dp, "lpfResonance", synth.getLpfRes());
    appendHexFreq(doc, dp, "hpfFrequency", synth.getHpfFreq());
    appendHexChildUnipolar(doc, dp, "hpfResonance", synth.getHpfRes());
    appendHexChildUnipolar(doc, dp, "hpfMorph", synth.getHpfMorph());

    // FM params
    appendHexChildUnipolar(doc, dp, "modulator1Amount", synth.getFmAmount());
    appendHexChildUnipolar(doc, dp, "modulator1Feedback", synth.getModulator1Feedback());
    appendHexChildUnipolar(doc, dp, "modulator2Amount", synth.getModulator2Amount());
    appendHexChildUnipolar(doc, dp, "modulator2Feedback", synth.getModulator2Feedback());
    appendHexChildUnipolar(doc, dp, "carrier1Feedback", synth.getCarrier1Feedback());
    appendHexChildUnipolar(doc, dp, "carrier2Feedback", synth.getCarrier2Feedback());

    appendHexChildUnipolar(doc, dp, "modFXRate", synth.getModFxRate());
    appendHexChildUnipolar(doc, dp, "modFXDepth", synth.getModFxDepth());
    appendHexChildUnipolar(doc, dp, "modFXOffset", 0f);
    appendHexChildUnipolar(doc, dp, "modFXFeedback", synth.getModFxFeedback());
    appendHexChildUnipolar(doc, dp, "delayRate", synth.getDelaySend());
    appendHexChildUnipolar(doc, dp, "delayFeedback", 0f);
    appendHexChildUnipolar(doc, dp, "reverbAmount", synth.getReverbSend());
    appendHexChildUnipolar(doc, dp, "arpeggiatorRate", synth.getArpRate());
    appendHexChildUnipolar(doc, dp, "stutterRate", synth.getStutterRate());
    appendHexChildUnipolar(doc, dp, "sampleRateReduction", synth.getSampleRateReduction());
    appendHexChildUnipolar(doc, dp, "bitCrush", synth.getBitCrush());
    appendHexChildUnipolar(doc, dp, "waveIndex", synth.getWaveIndex());
    // Equalizer inside defaultParams
    Element eq = doc.createElement("equalizer");
    appendHexChild(doc, eq, "bass", synth.getEqBass());
    appendHexChild(doc, eq, "treble", synth.getEqTreble());
    appendHexChild(doc, eq, "bassFrequency", 0f);
    appendHexChild(doc, eq, "trebleFrequency", 0f);
    dp.appendChild(eq);

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
        pcContainer.appendChild(createPatchCableElement(doc, pc));
      }
      dp.appendChild(pcContainer);
    }

    appendHexChild(doc, dp, "modFXOffset", 0f);
    appendHexChild(doc, dp, "modFXFeedback", synth.getModFxFeedback());
    root.appendChild(dp);

    // ── midiKnobs ──
    serializeMidiKnobs(doc, root, synth.getMidiKnobs());

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

    return root;
  }

  // ── Helper serializers ──

  /** Append an LFO element with type and syncLevel children. */
  public static void appendLfo(Document doc, Element parent, String tag, LfoModel lfo) {
    Element lfoElem = doc.createElement(tag);
    appendTextChild(doc, lfoElem, "type", lfoTypeName(lfo.waveform()));
    if (lfo.syncLevel() > 0) {
      appendTextChild(doc, lfoElem, "syncLevel", String.valueOf(lfo.syncLevel()));
    }
    appendHexLfoFreq(doc, lfoElem, "rate", lfo.rateHz());
    appendHexChildUnipolar(doc, lfoElem, "depth", lfo.depth());
    if (lfo.syncType() > 0) {
      appendTextChild(doc, lfoElem, "syncType", String.valueOf(lfo.syncType()));
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
    appendHexLfoFreq(doc, lfoElem, "rate", lfo.rateHz());
    appendHexChildUnipolar(doc, lfoElem, "depth", lfo.depth());
    if (lfo.syncType() > 0) {
      appendTextChild(doc, lfoElem, "syncType", String.valueOf(lfo.syncType()));
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
      case CUSTOM -> "custom";
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

  /** Append a child element with a hex string value from a unipolar float (scaled to bipolar). */
  public static void appendHexChildUnipolar(Document doc, Element parent, String tag, float value) {
    if (parent.getElementsByTagName(tag).getLength() > 0) return;
    Element child = doc.createElement(tag);
    child.setTextContent(DelugeHexMapper.floatToHex(value * 2.0f - 1.0f));
    parent.appendChild(child);
  }

  /** Append a child element with a hex frequency value from a Hz float. */
  public static void appendHexFreq(Document doc, Element parent, String tag, float hz) {
    if (parent.getElementsByTagName(tag).getLength() > 0) return;
    Element child = doc.createElement(tag);
    child.setTextContent(DelugeHexMapper.hzToHex(hz));
    parent.appendChild(child);
  }

  /** Append a child element with a hex LFO frequency value from a Hz float. */
  public static void appendHexLfoFreq(Document doc, Element parent, String tag, float hz) {
    if (parent.getElementsByTagName(tag).getLength() > 0) return;
    Element child = doc.createElement(tag);
    child.setTextContent(DelugeHexMapper.lfoHzToHex(hz));
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

  /** Append a raw Q31 value formatted as a 0xXXXXXXXX hex child node. */
  private static Element createPatchCableElement(Document doc, PatchCable pc) {
    Element cable = doc.createElement("patchCable");
    appendTextChild(doc, cable, "source", pc.source());
    if (pc.destination() != null && !pc.destination().isEmpty()) {
      appendTextChild(doc, cable, "destination", pc.destination());
    }
    appendHexChild(doc, cable, "amount", pc.amount());
    if (pc.polarity() != null) {
      appendTextChild(doc, cable, "polarity", pc.polarity().name().toLowerCase());
    }
    if (pc.depthControlledBy() != null && !pc.depthControlledBy().isEmpty()) {
      Element depthParent = doc.createElement("depthControlledBy");
      for (PatchCable dc : pc.depthControlledBy()) {
        depthParent.appendChild(createPatchCableElement(doc, dc));
      }
      cable.appendChild(depthParent);
    }
    return cable;
  }

  public static void appendRawQ31Child(Document doc, Element parent, String tag, int q31Value) {
    Element child = doc.createElement(tag);
    child.setTextContent(String.format("0x%08X", q31Value));
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

  private static void serializeMidiKnobs(Document doc, Element parent, List<MidiKnob> midiKnobs) {
    if (midiKnobs != null && !midiKnobs.isEmpty()) {
      Element container = doc.createElement("midiKnobs");
      for (MidiKnob mk : midiKnobs) {
        Element knob = doc.createElement("midiKnob");
        knob.setAttribute("channel", String.valueOf(mk.channel()));
        knob.setAttribute("ccNumber", String.valueOf(mk.ccNumber()));
        knob.setAttribute("relative", mk.relative() ? "1" : "0");
        knob.setAttribute("controlsParam", mk.controlsParam());
        if (mk.patchSource() != null && !"NONE".equals(mk.patchSource())) {
          knob.setAttribute("patchAmountFromSource", mk.patchSource());
        }
        container.appendChild(knob);
      }
      parent.appendChild(container);
    } else {
      parent.appendChild(doc.createElement("midiKnobs"));
    }
  }
}
