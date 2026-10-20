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
    // C kit.cpp:106-131 — kit-level GlobalEffectableForClip attributes & direct <defaultParams>.
    appendTextChild(
        doc,
        root,
        "lpfMode",
        org.deluge.xml.DelugeXmlUtil.lpfModeToFirmwareString(kit.getLpfMode()));
    appendTextChild(
        doc,
        root,
        "hpfMode",
        org.deluge.xml.DelugeXmlUtil.hpfModeToFirmwareString(kit.getHpfMode()));
    appendTextChild(
        doc,
        root,
        "filterRoute",
        org.deluge.xml.DelugeXmlUtil.filterRouteToFirmwareString(kit.getFilterRoute()));
    appendTextChild(
        doc,
        root,
        "modFXType",
        org.deluge.xml.DelugeXmlUtil.modFxTypeToFirmwareString(kit.getModFxType()));
    appendTextChild(doc, root, "clippingAmount", String.valueOf(kit.getClippingAmount()));
    Element kitDelay = doc.createElement("delay");
    appendTextAttr(kitDelay, "pingPong", String.valueOf(kit.getDelayPingPong()));
    appendTextAttr(kitDelay, "analog", String.valueOf(kit.getDelayAnalog()));
    appendTextAttr(kitDelay, "syncLevel", String.valueOf(kit.getDelaySyncLevel()));
    if (kit.getDelaySyncType() > 0) {
      appendTextAttr(
          kitDelay,
          "syncType",
          String.valueOf(org.deluge.xml.DelugeXmlUtil.syncTypeToFile(kit.getDelaySyncType())));
    }
    root.appendChild(kitDelay);
    // C global_effectable.cpp:799-846, mod_controllable_audio.cpp:495-502 — write unpatched params
    // as attributes on <defaultParams>, followed by <delay>, <lpf>, <hpf>, <equalizer> sub-tags.
    Element kitDp = doc.createElement("defaultParams");
    appendTextAttr(
        kitDp,
        "reverbAmount",
        DelugeHexMapper.q31ToHex(
            kit.getRawDefaultParamQ31("reverbAmount") != null
                ? kit.getRawDefaultParamQ31("reverbAmount")
                : DelugeHexMapper.unipolarFloatToQ31Unified(kit.getReverbAmount())));
    appendTextAttr(
        kitDp,
        "volume",
        DelugeHexMapper.q31ToHex(
            kit.getRawDefaultParamQ31("volume") != null
                ? kit.getRawDefaultParamQ31("volume")
                : DelugeHexMapper.unipolarFloatToQ31Unified(kit.getVolume())));
    appendTextAttr(
        kitDp,
        "pan",
        DelugeHexMapper.q31ToHex(
            kit.getRawDefaultParamQ31("pan") != null
                ? kit.getRawDefaultParamQ31("pan")
                : DelugeHexMapper.floatToQ31(kit.getPan())));
    appendTextAttr(
        kitDp,
        "modFXDepth",
        DelugeHexMapper.q31ToHex(
            kit.getRawDefaultParamQ31("modFXDepth") != null
                ? kit.getRawDefaultParamQ31("modFXDepth")
                : DelugeHexMapper.unipolarFloatToQ31Unified(kit.getModFxDepth())));
    appendTextAttr(
        kitDp,
        "modFXRate",
        DelugeHexMapper.q31ToHex(
            kit.getRawDefaultParamQ31("modFXRate") != null
                ? kit.getRawDefaultParamQ31("modFXRate")
                : DelugeHexMapper.unipolarFloatToQ31Unified(kit.getModFxRate())));
    appendTextAttr(
        kitDp,
        "sampleRateReduction",
        DelugeHexMapper.q31ToHex(
            kit.getRawDefaultParamQ31("sampleRateReduction") != null
                ? kit.getRawDefaultParamQ31("sampleRateReduction")
                : DelugeHexMapper.unipolarFloatToQ31Unified(kit.getSampleRateReduction())));
    appendTextAttr(
        kitDp,
        "bitCrush",
        DelugeHexMapper.q31ToHex(
            kit.getRawDefaultParamQ31("bitCrush") != null
                ? kit.getRawDefaultParamQ31("bitCrush")
                : DelugeHexMapper.unipolarFloatToQ31Unified(kit.getBitCrush())));
    appendTextAttr(
        kitDp,
        "modFXOffset",
        DelugeHexMapper.q31ToHex(
            kit.getRawDefaultParamQ31("modFXOffset") != null
                ? kit.getRawDefaultParamQ31("modFXOffset")
                : DelugeHexMapper.unipolarFloatToQ31Unified(kit.getModFxOffset())));
    appendTextAttr(
        kitDp,
        "modFXFeedback",
        DelugeHexMapper.q31ToHex(
            kit.getRawDefaultParamQ31("modFXFeedback") != null
                ? kit.getRawDefaultParamQ31("modFXFeedback")
                : DelugeHexMapper.unipolarFloatToQ31Unified(kit.getModFxFeedback())));
    appendTextAttr(
        kitDp,
        "lpfMorph",
        DelugeHexMapper.q31ToHex(
            kit.getRawDefaultParamQ31("lpf.morph") != null
                ? kit.getRawDefaultParamQ31("lpf.morph")
                : DelugeHexMapper.unipolarFloatToQ31Unified(kit.getLpfMorph())));
    appendTextAttr(
        kitDp,
        "hpfMorph",
        DelugeHexMapper.q31ToHex(
            kit.getRawDefaultParamQ31("hpf.morph") != null
                ? kit.getRawDefaultParamQ31("hpf.morph")
                : DelugeHexMapper.unipolarFloatToQ31Unified(kit.getHpfMorph())));
    Element kitDpDelay = doc.createElement("delay");
    appendTextAttr(
        kitDpDelay,
        "rate",
        DelugeHexMapper.q31ToHex(
            kit.getRawDefaultParamQ31("delay.rate") != null
                ? kit.getRawDefaultParamQ31("delay.rate")
                : DelugeHexMapper.unipolarFloatToQ31Unified(kit.getDelayRate())));
    appendTextAttr(
        kitDpDelay,
        "feedback",
        DelugeHexMapper.q31ToHex(
            kit.getRawDefaultParamQ31("delay.feedback") != null
                ? kit.getRawDefaultParamQ31("delay.feedback")
                : DelugeHexMapper.unipolarFloatToQ31Unified(kit.getDelayFeedback())));
    kitDp.appendChild(kitDpDelay);
    Element kitDpLpf = doc.createElement("lpf");
    appendTextAttr(
        kitDpLpf,
        "frequency",
        DelugeHexMapper.q31ToHex(
            kit.getRawDefaultParamQ31("lpf.frequency") != null
                ? kit.getRawDefaultParamQ31("lpf.frequency")
                : DelugeHexMapper.hzToQ31(kit.getLpfFreq())));
    appendTextAttr(
        kitDpLpf,
        "resonance",
        DelugeHexMapper.q31ToHex(
            kit.getRawDefaultParamQ31("lpf.resonance") != null
                ? kit.getRawDefaultParamQ31("lpf.resonance")
                : DelugeHexMapper.unipolarFloatToQ31Unified(kit.getLpfRes())));
    appendTextAttr(
        kitDpLpf,
        "morph",
        DelugeHexMapper.q31ToHex(
            kit.getRawDefaultParamQ31("lpf.morph") != null
                ? kit.getRawDefaultParamQ31("lpf.morph")
                : DelugeHexMapper.unipolarFloatToQ31Unified(kit.getLpfMorph())));
    kitDp.appendChild(kitDpLpf);
    Element kitDpHpf = doc.createElement("hpf");
    appendTextAttr(
        kitDpHpf,
        "frequency",
        DelugeHexMapper.q31ToHex(
            kit.getRawDefaultParamQ31("hpf.frequency") != null
                ? kit.getRawDefaultParamQ31("hpf.frequency")
                : DelugeHexMapper.hzToQ31(kit.getHpfFreq())));
    appendTextAttr(
        kitDpHpf,
        "resonance",
        DelugeHexMapper.q31ToHex(
            kit.getRawDefaultParamQ31("hpf.resonance") != null
                ? kit.getRawDefaultParamQ31("hpf.resonance")
                : DelugeHexMapper.unipolarFloatToQ31Unified(kit.getHpfRes())));
    appendTextAttr(
        kitDpHpf,
        "morph",
        DelugeHexMapper.q31ToHex(
            kit.getRawDefaultParamQ31("hpf.morph") != null
                ? kit.getRawDefaultParamQ31("hpf.morph")
                : DelugeHexMapper.unipolarFloatToQ31Unified(kit.getHpfMorph())));
    kitDp.appendChild(kitDpHpf);
    Element kitDpEq = doc.createElement("equalizer");
    appendTextAttr(
        kitDpEq,
        "bass",
        DelugeHexMapper.q31ToHex(
            kit.getRawDefaultParamQ31("equalizer.bass") != null
                ? kit.getRawDefaultParamQ31("equalizer.bass")
                : DelugeHexMapper.floatToQ31(kit.getEqBass())));
    appendTextAttr(
        kitDpEq,
        "treble",
        DelugeHexMapper.q31ToHex(
            kit.getRawDefaultParamQ31("equalizer.treble") != null
                ? kit.getRawDefaultParamQ31("equalizer.treble")
                : DelugeHexMapper.floatToQ31(kit.getEqTreble())));
    appendTextAttr(
        kitDpEq,
        "bassFrequency",
        DelugeHexMapper.q31ToHex(
            kit.getRawDefaultParamQ31("equalizer.bassFrequency") != null
                ? kit.getRawDefaultParamQ31("equalizer.bassFrequency")
                : DelugeHexMapper.floatToQ31(kit.getEqBassFreq())));
    appendTextAttr(
        kitDpEq,
        "trebleFrequency",
        DelugeHexMapper.q31ToHex(
            kit.getRawDefaultParamQ31("equalizer.trebleFrequency") != null
                ? kit.getRawDefaultParamQ31("equalizer.trebleFrequency")
                : DelugeHexMapper.floatToQ31(kit.getEqTrebleFreq())));
    kitDp.appendChild(kitDpEq);
    root.appendChild(kitDp);

    // C kit.cpp:132, 241 — kit drums are serialized inside <soundSources>.
    Element soundSources = doc.createElement("soundSources");
    for (Drum drum : kit.getDrums()) {
      SoundDrum sound = (SoundDrum) drum;
      Element soundElem = doc.createElement("sound");

      // ── name ──
      Element nameElem = doc.createElement("name");
      nameElem.setTextContent(sound.getName());
      soundElem.appendChild(nameElem);

      boolean hasOsc1File = sound.getSamplePath() != null && !sound.getSamplePath().isEmpty();
      // ── osc1 (sample, wavetable, or synth reference; C sound.cpp:3671-3680) ──
      Element osc1 = doc.createElement("osc1");
      osc1.setAttribute(
          "type",
          hasOsc1File && !"WAVETABLE".equalsIgnoreCase(sound.getOsc1Type())
              ? "sample"
              : oscTypeToFirmwareString(sound.getOsc1Type()));
      appendTextChild(doc, osc1, "fileName", hasOsc1File ? sound.getSamplePath() : "");
      appendTextChild(doc, osc1, "transpose", String.valueOf(sound.getOsc1Transpose()));
      appendTextChild(doc, osc1, "cents", String.valueOf(sound.getOsc1Cents()));
      // C SampleRepeatMode as parsed (was hardcoded 1 = ONCE for every drum).
      appendTextChild(doc, osc1, "loopMode", String.valueOf(sound.getOsc1LoopMode()));
      appendTextChild(doc, osc1, "reversed", sound.isReverse() ? "1" : "0");
      appendTextChild(doc, osc1, "timeStretchEnable", sound.isOsc1TimeStretch() ? "1" : "0");
      appendTextChild(
          doc, osc1, "linearInterpolation", sound.isOsc1LinearInterpolation() ? "1" : "0");
      appendTextChild(
          doc, osc1, "timeStretchAmount", String.valueOf(sound.getOsc1TimeStretchAmount()));
      appendTextChild(doc, osc1, "wavetableIndexPct", String.valueOf(sound.getWavetableIndexPct()));
      Element zone = doc.createElement("zone");
      if (sound.getStartSamplePos() >= 0) {
        zone.setAttribute("startSamplePos", String.valueOf(sound.getStartSamplePos()));
      }
      if (sound.getEndSamplePos() >= 0) {
        zone.setAttribute("endSamplePos", String.valueOf(sound.getEndSamplePos()));
      }
      if (sound.getStartLoopPos() >= 0) {
        zone.setAttribute("startLoopPos", String.valueOf(sound.getStartLoopPos()));
      }
      if (sound.getEndLoopPos() >= 0) {
        zone.setAttribute("endLoopPos", String.valueOf(sound.getEndLoopPos()));
      }
      if (sound.getStartMs() > 0 || sound.getEndMs() > 0) {
        appendTextChild(doc, zone, "startMilliseconds", String.valueOf((int) sound.getStartMs()));
        appendTextChild(doc, zone, "endMilliseconds", String.valueOf((int) sound.getEndMs()));
      }
      osc1.appendChild(zone);
      appendTextChild(doc, osc1, "retrigPhase", String.valueOf(sound.getOsc1RetrigPhase()));
      soundElem.appendChild(osc1);

      // ── osc2 (always present, often empty) ──
      Element osc2 = doc.createElement("osc2");
      osc2.setAttribute("type", oscTypeToFirmwareString(sound.getOsc2Type()));
      appendTextChild(doc, osc2, "transpose", String.valueOf(sound.getOsc2Transpose()));
      appendTextChild(doc, osc2, "cents", String.valueOf(sound.getOsc2Cents()));
      appendTextChild(doc, osc2, "loopMode", String.valueOf(sound.getOsc2LoopMode()));
      appendTextChild(doc, osc2, "reversed", sound.isOsc2Reversed() ? "1" : "0");
      appendTextChild(doc, osc2, "timeStretchEnable", sound.isOsc2TimeStretch() ? "1" : "0");
      appendTextChild(
          doc, osc2, "linearInterpolation", sound.isOsc2LinearInterpolation() ? "1" : "0");
      appendTextChild(
          doc, osc2, "timeStretchAmount", String.valueOf(sound.getOsc2TimeStretchAmount()));
      appendTextChild(
          doc,
          osc2,
          "fileName",
          sound.getOsc2SamplePath() != null ? sound.getOsc2SamplePath() : "");
      if (sound.getOsc2SamplePath() != null && !sound.getOsc2SamplePath().isEmpty()) {
        Element osc2Zone = doc.createElement("zone");
        if (sound.getOsc2StartSamplePos() >= 0) {
          osc2Zone.setAttribute("startSamplePos", String.valueOf(sound.getOsc2StartSamplePos()));
        }
        if (sound.getOsc2EndSamplePos() >= 0) {
          osc2Zone.setAttribute("endSamplePos", String.valueOf(sound.getOsc2EndSamplePos()));
        }
        if (sound.getOsc2StartLoopPos() >= 0) {
          osc2Zone.setAttribute("startLoopPos", String.valueOf(sound.getOsc2StartLoopPos()));
        }
        if (sound.getOsc2EndLoopPos() >= 0) {
          osc2Zone.setAttribute("endLoopPos", String.valueOf(sound.getOsc2EndLoopPos()));
        }
        osc2.appendChild(osc2Zone);
      } else if (sound.isOscillatorSync()) {
        // C sound.cpp:3701-3703 — oscillatorSync written on osc2 when non-sample and enabled.
        appendTextChild(doc, osc2, "oscillatorSync", "1");
      }
      appendTextChild(doc, osc2, "retrigPhase", String.valueOf(sound.getOsc2RetrigPhase()));
      soundElem.appendChild(osc2);

      // ── polyphonic (C functions.cpp:835-852) ──
      String polyVal =
          switch (sound.getPolyphony()) {
            case MONO -> "mono";
            case LEGATO -> "legato";
            case CHOKE -> "choke";
            case AUTO -> "auto";
            default -> "poly";
          };
      appendTextChild(doc, soundElem, "polyphonic", polyVal);

      // ── clippingAmount ──
      appendTextChild(
          doc, soundElem, "clippingAmount", String.valueOf((int) sound.getClippingAmount()));

      // ── voicePriority & maxVoices (C sound.cpp:4168, 4190) ──
      appendTextChild(doc, soundElem, "voicePriority", String.valueOf(sound.getVoicePriority()));
      appendTextChild(doc, soundElem, "maxVoices", String.valueOf(sound.getMaxVoiceCount()));

      // ── sideChainSend ──
      if (sound.getSidechainSend() > 0) {
        appendTextChild(
            doc,
            soundElem,
            "sideChainSend",
            String.valueOf(
                (int) Math.round((double) sound.getSidechainSend() * Integer.MAX_VALUE)));
      }

      // ── lfo1..lfo4 (C sound.cpp:4196-4215) ──
      appendLfo(doc, soundElem, "lfo1", sound.getLfo1());
      appendLfo(doc, soundElem, "lfo2", sound.getLfo2());
      appendLfo(doc, soundElem, "lfo3", sound.getLfo3());
      appendLfo(doc, soundElem, "lfo4", sound.getLfo4());

      // ── mode (C sound.cpp:4167 — subtractive / fm / ringmod) ──
      String drumModeStr =
          switch (sound.getSynthMode()) {
            case 1 -> "fm";
            case 2 -> "ringmod";
            default -> "subtractive";
          };
      appendTextChild(doc, soundElem, "mode", drumModeStr);
      if (Math.round(sound.getPitchSemitones()) != 0) {
        appendTextChild(
            doc, soundElem, "transpose", String.valueOf(Math.round(sound.getPitchSemitones())));
      }
      if (sound.getCents() != 0) {
        appendTextChild(doc, soundElem, "cents", String.valueOf(sound.getCents()));
      }

      // ── modulator1 & modulator2 (C sound.cpp:4217-4234) ──
      Element drumMod1 = doc.createElement("modulator1");
      drumMod1.setAttribute("transpose", String.valueOf(sound.getModulator1Transpose()));
      drumMod1.setAttribute("cents", String.valueOf(sound.getModulator1Cents()));
      drumMod1.setAttribute("retrigPhase", String.valueOf(sound.getMod1RetrigPhase()));
      soundElem.appendChild(drumMod1);

      Element drumMod2 = doc.createElement("modulator2");
      drumMod2.setAttribute("transpose", String.valueOf(sound.getModulator2Transpose()));
      drumMod2.setAttribute("cents", String.valueOf(sound.getModulator2Cents()));
      drumMod2.setAttribute("retrigPhase", String.valueOf(sound.getMod2RetrigPhase()));
      drumMod2.setAttribute("toModulator1", sound.isModulator1ToModulator0() ? "1" : "0");
      soundElem.appendChild(drumMod2);

      // ── unison ──
      Element unison = doc.createElement("unison");
      appendTextAttr(unison, "num", String.valueOf(sound.getUnisonNum()));
      // Plain integer, matching the format and the synth path below — see the note there. Writing a
      // hex Q31 here mangled a detune of 16 back into 1 on re-read.
      appendTextAttr(unison, "detune", String.valueOf(Math.round(sound.getUnisonDetune())));
      appendTextAttr(unison, "spread", String.valueOf(Math.round(sound.getUnisonStereoSpread())));
      soundElem.appendChild(unison);

      // ── compressor, sidechain, & stutter (C mod_controllable_audio.cpp:447-486) ──
      if (sound.getCompressorAttack() > 0
          || sound.getCompressorRelease() > 0
          || sound.getCompressorSyncLevel() > 0
          || sound.getCompressorSyncType() > 0) {
        Element comp = doc.createElement("compressor");
        appendTextAttr(comp, "syncLevel", String.valueOf(sound.getCompressorSyncLevel()));
        if (sound.getCompressorSyncType() > 0) {
          appendTextAttr(
              comp,
              "syncType",
              String.valueOf(
                  org.deluge.xml.DelugeXmlUtil.syncTypeToFile(sound.getCompressorSyncType())));
        }
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
      if (sound.getSidechainAttackRaw() >= 0
          || sound.getSidechainReleaseRaw() >= 0
          || sound.getSidechainAttack() > 0
          || sound.getSidechainRelease() > 0
          || sound.getSidechainSyncLevel() > 0
          || sound.getSidechainSyncType() > 0) {
        Element sc = doc.createElement("sidechain");
        appendTextAttr(sc, "syncLevel", String.valueOf(sound.getSidechainSyncLevel()));
        if (sound.getSidechainSyncType() > 0) {
          appendTextAttr(
              sc,
              "syncType",
              String.valueOf(
                  org.deluge.xml.DelugeXmlUtil.syncTypeToFile(sound.getSidechainSyncType())));
        }
        if (sound.getSidechainAttackRaw() >= 0 || sound.getSidechainAttack() > 0) {
          appendTextAttr(
              sc,
              "attack",
              sound.getSidechainAttackRaw() >= 0
                  ? String.valueOf(sound.getSidechainAttackRaw())
                  : String.valueOf(
                      (int) Math.round(sound.getSidechainAttack() * Integer.MAX_VALUE)));
        }
        if (sound.getSidechainReleaseRaw() >= 0 || sound.getSidechainRelease() > 0) {
          appendTextAttr(
              sc,
              "release",
              sound.getSidechainReleaseRaw() >= 0
                  ? String.valueOf(sound.getSidechainReleaseRaw())
                  : String.valueOf(
                      (int) Math.round(sound.getSidechainRelease() * Integer.MAX_VALUE)));
        }
        soundElem.appendChild(sc);
      }
      Element drumStutter = doc.createElement("stutter");
      appendTextAttr(drumStutter, "quantized", sound.isStutterQuantized() ? "1" : "0");
      appendTextAttr(drumStutter, "reverse", sound.isStutterReversed() ? "1" : "0");
      appendTextAttr(drumStutter, "pingPong", sound.isStutterPingPong() ? "1" : "0");
      soundElem.appendChild(drumStutter);

      // ── delay (C mod_controllable_audio.cpp:417-425 — unconditional) ──
      Element delay = doc.createElement("delay");
      appendTextAttr(delay, "pingPong", String.valueOf(sound.getDelayPingPong()));
      appendTextAttr(delay, "analog", String.valueOf(sound.getDelayAnalog()));
      appendTextAttr(delay, "syncLevel", String.valueOf(sound.getDelaySyncLevel()));
      if (sound.getDelaySyncType() > 0) {
        appendTextAttr(
            delay,
            "syncType",
            String.valueOf(org.deluge.xml.DelugeXmlUtil.syncTypeToFile(sound.getDelaySyncType())));
      }
      soundElem.appendChild(delay);

      // ── lpfMode ──
      String lpfModeStr =
          switch (sound.getLpfMode()) {
            // C filterMap strings (filter_config.cpp:8-14) — anything else reads back as the
            // map's unknown fallback (OFF) on real hardware.
            case LADDER_24 -> "24dB";
            case DRIVE -> "24dBDrive";
            case SVF, SVF_BAND -> "SVF_Band";
            case SVF_NOTCH -> "SVF_Notch";
            case OFF -> "Off";
            default -> "12dB";
          };
      appendTextChild(doc, soundElem, "lpfMode", lpfModeStr);

      // ── hpfMode ──
      String hpfModeStr =
          switch (sound.getHpfMode()) {
            // The C writes hpfMode via the same filterMap (mod_controllable_audio.cpp:410):
            // the active HP ladder is "HPLadder" — an LP-mode string here would load as an
            // INERT high-pass on hardware (filter_set.cpp:26-41).
            case SVF, SVF_BAND -> "SVF_Band";
            case SVF_NOTCH -> "SVF_Notch";
            case OFF -> "Off";
            default -> "HPLadder";
          };
      appendTextChild(doc, soundElem, "hpfMode", hpfModeStr);

      // ── filterRoute (C filter_config.cpp:16-20, mod_controllable_audio.cpp:411) ──
      appendTextChild(
          doc,
          soundElem,
          "filterRoute",
          org.deluge.xml.DelugeXmlUtil.filterRouteToFirmwareString(sound.getFilterRoute()));

      // ── modFXType (C functions.cpp:878-901) ──
      appendTextChild(
          doc,
          soundElem,
          "modFXType",
          org.deluge.xml.DelugeXmlUtil.modFxTypeToFirmwareString(sound.getModFxType()));

      // ── defaultParams (C sound.cpp:4056-4162) ──
      Element dp = doc.createElement("defaultParams");
      appendDrumRawOrFallback(
          doc,
          dp,
          "arpeggiatorGate",
          sound,
          () -> appendHexChildUnipolar(doc, dp, "arpeggiatorGate", sound.getArpeggiatorGate()));
      appendDrumRawOrFallback(
          doc,
          dp,
          "portamento",
          sound,
          () -> appendHexChildUnipolar(doc, dp, "portamento", sound.getPortamento()));
      appendDrumRawOrFallback(
          doc,
          dp,
          "compressorShape",
          sound,
          () -> appendHexChildUnipolar(doc, dp, "compressorShape", sound.getCompressorShape()));
      float effectiveOscAVol =
          ("SAMPLE".equalsIgnoreCase(sound.getOsc1Type()) && sound.getOscAVolume() == 0.0f)
              ? 1.0f
              : sound.getOscAVolume();
      appendDrumRawOrFallback(
          doc,
          dp,
          "oscAVolume",
          sound,
          () -> appendHexChildUnipolar(doc, dp, "oscAVolume", effectiveOscAVol));
      appendDrumRawOrFallback(
          doc, dp, "oscAPulseWidth", sound, () -> appendRawQ31Child(doc, dp, "oscAPulseWidth", 0));
      appendDrumRawOrFallback(
          doc,
          dp,
          "oscAWavetablePosition",
          sound,
          () -> appendHexChildUnipolar(doc, dp, "oscAWavetablePosition", sound.getWaveIndex()));
      appendDrumRawOrFallback(
          doc,
          dp,
          "oscBVolume",
          sound,
          () -> appendHexChildUnipolar(doc, dp, "oscBVolume", sound.getOscBVolume()));
      appendDrumRawOrFallback(
          doc, dp, "oscBPulseWidth", sound, () -> appendRawQ31Child(doc, dp, "oscBPulseWidth", 0));
      appendDrumRawOrFallback(
          doc,
          dp,
          "oscBWavetablePosition",
          sound,
          () -> appendHexChildUnipolar(doc, dp, "oscBWavetablePosition", sound.getOsc2WaveIndex()));
      // C sound.cpp:4099-4107 — pitchAdjust, oscAPitchAdjust, oscBPitchAdjust, mod1PitchAdjust,
      // mod2PitchAdjust
      for (String pitchTag :
          new String[] {
            "pitchAdjust",
            "oscAPitchAdjust",
            "oscBPitchAdjust",
            "mod1PitchAdjust",
            "mod2PitchAdjust"
          }) {
        Integer rawPitch = sound.getRawDefaultParamQ31(pitchTag);
        if (rawPitch != null) {
          appendRawQ31Child(doc, dp, pitchTag, rawPitch);
        }
      }
      appendDrumRawOrFallback(
          doc,
          dp,
          "noiseVolume",
          sound,
          () -> appendHexChildUnipolar(doc, dp, "noiseVolume", sound.getNoiseVolume()));
      appendDrumRawOrFallback(
          doc,
          dp,
          "volume",
          sound,
          () -> appendHexChildUnipolar(doc, dp, "volume", sound.getVolume()));
      appendDrumRawOrFallback(
          doc, dp, "pan", sound, () -> appendHexChild(doc, dp, "pan", sound.getPan()));
      appendDrumRawOrFallback(
          doc,
          dp,
          "lpfFrequency",
          sound,
          () -> appendHexFreq(doc, dp, "lpfFrequency", sound.getLpfFreq()));
      appendDrumRawOrFallback(
          doc,
          dp,
          "lpfResonance",
          sound,
          () -> appendHexChildUnipolar(doc, dp, "lpfResonance", sound.getLpfRes()));
      appendDrumRawOrFallback(
          doc,
          dp,
          "lpfMorph",
          sound,
          () -> appendHexChildUnipolar(doc, dp, "lpfMorph", sound.getLpfMorph()));
      appendDrumRawOrFallback(
          doc,
          dp,
          "hpfFrequency",
          sound,
          () -> appendHexFreq(doc, dp, "hpfFrequency", sound.getHpfFreq()));
      appendDrumRawOrFallback(
          doc,
          dp,
          "hpfResonance",
          sound,
          () -> appendHexChildUnipolar(doc, dp, "hpfResonance", sound.getHpfRes()));
      appendDrumRawOrFallback(
          doc,
          dp,
          "hpfMorph",
          sound,
          () -> appendHexChildUnipolar(doc, dp, "hpfMorph", sound.getHpfMorph()));
      // C Sound::initParams (sound.cpp:154-155, 181-182) & ModControllableAudio::initParams
      // (mod_controllable_audio.cpp:131-132): unset Q31 defaults are 0x00000000 for ModFX knobs &
      // delayRate, and 0x80000000 for delayFeedback.
      appendBoxedQ31OrFallback(
          doc,
          dp,
          "modFXRate",
          sound.getModFxRateQ31(),
          () -> appendRawQ31Child(doc, dp, "modFXRate", 0));
      appendBoxedQ31OrFallback(
          doc,
          dp,
          "modFXDepth",
          sound.getModFxDepthQ31(),
          () -> appendRawQ31Child(doc, dp, "modFXDepth", 0));
      appendBoxedQ31OrFallback(
          doc,
          dp,
          "modFXOffset",
          sound.getModFxOffsetQ31(),
          () -> appendRawQ31Child(doc, dp, "modFXOffset", 0));
      appendBoxedQ31OrFallback(
          doc,
          dp,
          "modFXFeedback",
          sound.getModFxFeedbackQ31(),
          () -> appendRawQ31Child(doc, dp, "modFXFeedback", 0));
      appendBoxedQ31OrFallback(
          doc,
          dp,
          "delayRate",
          sound.getDelayRateQ31(),
          () -> appendRawQ31Child(doc, dp, "delayRate", 0));
      appendBoxedQ31OrFallback(
          doc,
          dp,
          "delayFeedback",
          sound.getDelayFeedbackQ31(),
          () -> appendRawQ31Child(doc, dp, "delayFeedback", Integer.MIN_VALUE));
      appendDrumRawOrFallback(
          doc,
          dp,
          "reverbAmount",
          sound,
          () -> appendHexChildUnipolar(doc, dp, "reverbAmount", sound.getReverbAmount()));
      appendDrumRawOrFallback(
          doc,
          dp,
          "arpeggiatorRate",
          sound,
          () -> appendHexChildUnipolar(doc, dp, "arpeggiatorRate", sound.getArpeggiatorRate()));
      appendDrumRawOrFallback(
          doc,
          dp,
          "stutterRate",
          sound,
          () -> appendHexChildUnipolar(doc, dp, "stutterRate", sound.getStutterRate()));
      appendDrumRawOrFallback(
          doc,
          dp,
          "sampleRateReduction",
          sound,
          () ->
              appendHexChildUnipolar(
                  doc, dp, "sampleRateReduction", sound.getSampleRateReduction()));
      appendDrumRawOrFallback(
          doc,
          dp,
          "bitCrush",
          sound,
          () -> appendHexChildUnipolar(doc, dp, "bitCrush", sound.getBitCrush()));
      appendDrumRawOrFallback(
          doc,
          dp,
          "waveIndex",
          sound,
          () -> appendHexChildUnipolar(doc, dp, "waveIndex", sound.getWaveIndex()));
      Integer drumWaveFold = sound.getRawDefaultParamQ31("waveFold");
      if (drumWaveFold != null) {
        appendRawQ31Child(doc, dp, "waveFold", drumWaveFold);
      }

      // Envelopes 1-4 inside defaultParams
      EnvelopeModel[] drumEnvs = {
        sound.getAdsr(), sound.getEnv2(), sound.getEnv3(), sound.getEnv4()
      };
      for (int envIdx = 0; envIdx < 4; envIdx++) {
        String envTag = "envelope" + (envIdx + 1);
        Integer aQ31 = sound.getRawDefaultParamQ31(envTag + ".attack");
        Integer dQ31 = sound.getRawDefaultParamQ31(envTag + ".decay");
        Integer sQ31 = sound.getRawDefaultParamQ31(envTag + ".sustain");
        Integer rQ31 = sound.getRawDefaultParamQ31(envTag + ".release");
        if (aQ31 != null && dQ31 != null && sQ31 != null && rQ31 != null) {
          Element envElem = doc.createElement(envTag);
          appendRawQ31Child(doc, envElem, "attack", aQ31);
          appendRawQ31Child(doc, envElem, "decay", dQ31);
          appendRawQ31Child(doc, envElem, "sustain", sQ31);
          appendRawQ31Child(doc, envElem, "release", rQ31);
          dp.appendChild(envElem);
        } else {
          appendEnvelope(doc, dp, envTag, drumEnvs[envIdx]);
        }
      }

      // LFO 1..4 rates inside defaultParams (C sound.cpp:4084-4087)
      LfoModel[] drumLfos = {sound.getLfo1(), sound.getLfo2(), sound.getLfo3(), sound.getLfo4()};
      for (int lfoIdx = 0; lfoIdx < 4; lfoIdx++) {
        String rateTag = "lfo" + (lfoIdx + 1) + "Rate";
        LfoModel curLfo = drumLfos[lfoIdx];
        appendDrumRawOrFallback(
            doc, dp, rateTag, sound, () -> appendHexLfoFreq(doc, dp, rateTag, curLfo.rateHz()));
      }

      // FM params (C sound.cpp:4089-4097)
      appendRawQ31Child(doc, dp, "modulator1Amount", sound.getModulator1AmountQ31());
      appendRawQ31Child(doc, dp, "modulator1Feedback", sound.getModulator1FeedbackQ31());
      appendRawQ31Child(doc, dp, "modulator2Amount", sound.getModulator2AmountQ31());
      appendRawQ31Child(doc, dp, "modulator2Feedback", sound.getModulator2FeedbackQ31());
      appendRawQ31Child(doc, dp, "carrier1Feedback", sound.getCarrier1FeedbackQ31());
      appendRawQ31Child(doc, dp, "carrier2Feedback", sound.getCarrier2FeedbackQ31());

      // Patch cables inside defaultParams
      if (!sound.getPatchCables().isEmpty()) {
        Element pcContainer = doc.createElement("patchCables");
        for (PatchCable pc : sound.getPatchCables()) {
          pcContainer.appendChild(createPatchCableElement(doc, pc));
        }
        dp.appendChild(pcContainer);
      }

      // Equalizer inside defaultParams (C mod_controllable_audio.cpp:540-549)
      Element eq = doc.createElement("equalizer");
      appendDrumRawOrFallback(
          doc,
          eq,
          "bass",
          "eq.bass",
          sound,
          () -> appendHexChild(doc, eq, "bass", sound.getEqBass()));
      appendDrumRawOrFallback(
          doc,
          eq,
          "treble",
          "eq.treble",
          sound,
          () -> appendHexChild(doc, eq, "treble", sound.getEqTreble()));
      appendDrumRawOrFallback(
          doc,
          eq,
          "bassFrequency",
          "eq.bassFrequency",
          sound,
          () -> appendHexChild(doc, eq, "bassFrequency", sound.getEqBassFreq()));
      appendDrumRawOrFallback(
          doc,
          eq,
          "trebleFrequency",
          "eq.trebleFrequency",
          sound,
          () -> appendHexChild(doc, eq, "trebleFrequency", sound.getEqTrebleFreq()));
      dp.appendChild(eq);

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

      soundSources.appendChild(soundElem);
    }
    root.appendChild(soundSources);

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
    boolean isDx7 =
        synth.getSynthMode() != 2
            && ("DX7".equalsIgnoreCase(synth.getOsc1Type())
                || (synth.getDx7Patch() != null && !synth.getDx7Patch().isEmpty()));
    Element osc1 = doc.createElement("osc1");
    appendTextChild(
        doc, osc1, "type", isDx7 ? "dx7" : oscTypeToFirmwareString(synth.getOsc1Type()));
    // Were hardcoded "0", silently discarding the oscillator's tuning: "018 Rich Saw Lead" has
    // osc2 transpose=12, and saving it collapsed both oscillators onto the same pitch — which is
    // what made an XML round-trip render 1.77x louder (the two saws then summed coherently).
    appendTextChild(doc, osc1, "transpose", String.valueOf(synth.getOsc1().getTranspose()));
    appendTextChild(doc, osc1, "cents", String.valueOf(synth.getOsc1().getCents()));
    appendTextChild(doc, osc1, "retrigPhase", String.valueOf(synth.getOsc1RetrigPhase()));
    appendSampleSource(
        doc,
        osc1,
        synth.getOsc1SamplePath(),
        synth.getOsc1LoopMode(),
        synth.isOsc1Reversed(),
        synth.isOsc1TimeStretch(),
        synth.isOsc1LinearInterpolation());
    if (synth.getOsc1SamplePath() != null && !synth.getOsc1SamplePath().isEmpty()) {
      // C sound.cpp:3636 — writeAttribute("timeStretchAmount", source->timeStretchAmount)
      appendTextChild(
          doc, osc1, "timeStretchAmount", String.valueOf(synth.getOsc1TimeStretchAmount()));
    }
    // C sound.cpp:3744-3758, source.cpp:207-209, voice.cpp:1322-1357 — write dx7patch plus
    // conditional non-zero dx7enginemode & dx7randomdetune when DX7 source routing is active
    // (never in RingMod).
    if (synth.getSynthMode() != 2
        && synth.getDx7Patch() != null
        && !synth.getDx7Patch().isEmpty()) {
      osc1.setAttribute("dx7patch", synth.getDx7Patch());
      if (synth.getEngineType() != 0) {
        osc1.setAttribute("dx7enginemode", String.valueOf(synth.getEngineType()));
      }
      if (synth.getDx7RandomDetune() != 0) {
        osc1.setAttribute("dx7randomdetune", String.valueOf(synth.getDx7RandomDetune()));
      }
    }
    root.appendChild(osc1);

    // ── osc2 ──
    Element osc2 = doc.createElement("osc2");
    appendTextChild(doc, osc2, "type", oscTypeToFirmwareString(synth.getOsc2Type()));
    appendTextChild(doc, osc2, "transpose", String.valueOf(synth.getOsc2().getTranspose()));
    appendTextChild(doc, osc2, "cents", String.valueOf(synth.getOsc2().getCents()));
    appendTextChild(doc, osc2, "retrigPhase", String.valueOf(synth.getOsc2RetrigPhase()));
    appendSampleSource(
        doc,
        osc2,
        synth.getOsc2SamplePath(),
        synth.getOsc2LoopMode(),
        synth.isOsc2Reversed(),
        synth.isOsc2TimeStretch(),
        synth.isOsc2LinearInterpolation());
    if (synth.getOsc2SamplePath() != null && !synth.getOsc2SamplePath().isEmpty()) {
      // C sound.cpp:3636 — writeAttribute("timeStretchAmount", source->timeStretchAmount)
      appendTextChild(
          doc, osc2, "timeStretchAmount", String.valueOf(synth.getOsc2TimeStretchAmount()));
    } else if (synth.isOscillatorSync()) {
      // C sound.cpp:3701-3703 — oscillatorSync written on osc2 when non-sample and enabled.
      appendTextChild(doc, osc2, "oscillatorSync", "1");
    }
    root.appendChild(osc2);

    // ── polyphonic (C functions.cpp:835-852) ──
    String polyVal =
        switch (synth.getPolyphony()) {
          case MONO -> "mono";
          case LEGATO -> "legato";
          case CHOKE -> "choke";
          case AUTO -> "auto";
          default -> "poly";
        };
    appendTextChild(doc, root, "polyphonic", polyVal);

    // ── clippingAmount ──
    appendTextChild(doc, root, "clippingAmount", String.valueOf(synth.getClippingAmount()));

    // ── voicePriority, maxVoices, & sideChainSend (C sound.cpp:4168-4190) ──
    appendTextChild(doc, root, "voicePriority", String.valueOf(synth.getVoicePriority()));
    appendTextChild(doc, root, "maxVoices", String.valueOf(synth.getMaxVoiceCount()));
    if (synth.getSidechainSend() > 0) {
      appendTextChild(
          doc,
          root,
          "sideChainSend",
          String.valueOf((int) Math.round(synth.getSidechainSend() * Integer.MAX_VALUE)));
    }

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

    // ── synth mode (C sound.cpp:3744-3745 — DX7 patches serialize mode="subtractive") ──
    String mode =
        switch (synth.getSynthMode()) {
          case 1 -> isDx7 ? "subtractive" : "fm";
          case 2 -> "ringmod";
          default -> "subtractive";
        };
    appendTextChild(doc, root, "mode", mode);

    // ── transpose + cents (global synth master transpose, C sound.cpp:4169-4174) ──
    appendTextChild(doc, root, "transpose", String.valueOf(synth.getTranspose()));
    if (synth.getCents() != 0) {
      appendTextChild(doc, root, "cents", String.valueOf(synth.getCents()));
    }

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
    appendTextChild(doc, unison, "num", String.valueOf(synth.getUnison().getUnisonNum()));
    appendTextChild(
        doc,
        unison,
        "detune",
        // The card writes a PLAIN INTEGER here ("<detune>24</detune>" in the stock presets), not a
        // hex Q31. Writing hex made a re-parse read detune 24 back as 1, collapsing the unison
        // spread so the voices summed coherently instead of beating.
        String.valueOf(Math.round(synth.getUnison().getUnisonDetune())));
    appendTextChild(
        doc,
        unison,
        "spread",
        // C sound.cpp:4246 — unison stereo spread is a plain decimal integer (0..50), not hex Q31.
        String.valueOf(Math.round(synth.getUnison().getUnisonStereoSpread())));
    root.appendChild(unison);

    // ── arpeggiator ──
    Element arp = doc.createElement("arpeggiator");
    // C writeCommonParamsToFile (arpeggiator.cpp): "mode" = arpModeToString, "arp" or "off".
    arp.setAttribute("mode", synth.getArp().active() ? "arp" : "off");
    arp.setAttribute("active", synth.getArp().active() ? "1" : "0");
    arp.setAttribute(org.deluge.xml.ArpXmlCodec.FORMAT_ATTR, org.deluge.xml.ArpXmlCodec.FORMAT_C);
    arp.setAttribute("sequenceLength", String.valueOf(synth.getArp().seqLength()));
    arp.setAttribute("arpMode", synth.getArp().active() ? "arp" : "off");
    if (synth.getArp().notePattern() != null && !synth.getArp().notePattern().isEmpty()) {
      arp.setAttribute("notePattern", synth.getArp().notePattern());
    }
    arp.setAttribute("chordType", String.valueOf(synth.getArp().chordType()));

    // The ARP panel edits octaves(); numOctaves() is only what was last parsed.
    arp.setAttribute("numOctaves", String.valueOf(synth.getArp().octaves()));
    arp.setAttribute(
        "syncType",
        String.valueOf(org.deluge.xml.ArpXmlCodec.syncTypeToFile(synth.getArp().syncType())));
    arp.setAttribute(
        "mpeVelocity", org.deluge.xml.ArpXmlCodec.mpeVelocityToFile(synth.getArp().mpeVelocity()));
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
    // A preset is written without a song; the C would use the current song's magnitude.
    appendTextChild(
        doc,
        arp,
        "syncLevel",
        String.valueOf(
            org.deluge.xml.ArpXmlCodec.syncFileValueFromDenominator(
                synth.getArp().syncLevel(),
                org.deluge.xml.ArpXmlCodec.DEFAULT_INPUT_TICK_MAGNITUDE)));
    appendTextChild(
        doc, arp, "noteMode", org.deluge.xml.ArpXmlCodec.noteModeToFile(synth.getArp().noteMode()));
    appendTextChild(
        doc,
        arp,
        "octaveMode",
        org.deluge.xml.ArpXmlCodec.octaveModeToFile(synth.getArp().octaveMode()));
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

    // ── delay (C mod_controllable_audio.cpp:417-425 — unconditional) ──
    Element delay = doc.createElement("delay");
    appendTextChild(doc, delay, "pingPong", synth.isDelayPingPong() ? "1" : "0");
    appendTextChild(doc, delay, "analog", synth.isDelayAnalog() ? "1" : "0");
    appendTextChild(doc, delay, "syncLevel", String.valueOf(synth.getDelaySyncLevel()));
    if (synth.getDelaySyncType() > 0) {
      appendTextChild(
          doc,
          delay,
          "syncType",
          String.valueOf(org.deluge.xml.DelugeXmlUtil.syncTypeToFile(synth.getDelaySyncType())));
    }
    root.appendChild(delay);

    // ── lpfMode ──
    String lpfModeStr =
        switch (synth.getFilterMode()) {
          case LADDER_24 -> "24dB";
          case DRIVE -> "24dBDrive";
          case SVF, SVF_BAND -> "SVF_Band";
          case SVF_NOTCH -> "SVF_Notch";
          case OFF -> "Off";
          default -> "12dB";
        };
    appendTextChild(doc, root, "lpfMode", lpfModeStr);

    // ── hpfMode ──
    String hpfModeSynthStr =
        switch (synth.getHpfMode()) {
          case SVF, SVF_BAND -> "SVF_Band";
          case SVF_NOTCH -> "SVF_Notch";
          case OFF -> "Off";
          default -> "HPLadder";
        };
    appendTextChild(doc, root, "hpfMode", hpfModeSynthStr);

    // ── filterRoute (C filter_config.cpp:16-20, mod_controllable_audio.cpp:411) ──
    appendTextChild(
        doc,
        root,
        "filterRoute",
        org.deluge.xml.DelugeXmlUtil.filterRouteToFirmwareString(synth.getFilterRoute()));

    // ── modFXType (C functions.cpp:878-901) ──
    appendTextChild(
        doc,
        root,
        "modFXType",
        org.deluge.xml.DelugeXmlUtil.modFxTypeToFirmwareString(synth.getModFxType()));

    // ── compressor, sidechain, & stutter (C mod_controllable_audio.cpp:447-486) ──
    float compAttack = synth.getCompressorAttack();
    float compRelease = synth.getCompressorRelease();
    int compSync = synth.getCompressorSyncLevel();
    if (compAttack > 0 || compRelease > 0 || compSync > 0 || synth.getCompressorSyncType() > 0) {
      Element comp = doc.createElement("compressor");
      appendTextAttr(comp, "syncLevel", String.valueOf(compSync));
      if (synth.getCompressorSyncType() > 0) {
        appendTextAttr(
            comp,
            "syncType",
            String.valueOf(
                org.deluge.xml.DelugeXmlUtil.syncTypeToFile(synth.getCompressorSyncType())));
      }
      appendTextAttr(comp, "attack", String.valueOf((int) (compAttack * Integer.MAX_VALUE)));
      appendTextAttr(comp, "release", String.valueOf((int) (compRelease * Integer.MAX_VALUE)));
      root.appendChild(comp);
    }
    if (synth.getSidechainAttackRaw() >= 0
        || synth.getSidechainReleaseRaw() >= 0
        || synth.getSidechainAttack() > 0
        || synth.getSidechainRelease() > 0
        || synth.getSidechainSyncLevel() > 0
        || synth.getSidechainSyncType() > 0) {
      Element sc = doc.createElement("sidechain");
      appendTextAttr(sc, "syncLevel", String.valueOf(synth.getSidechainSyncLevel()));
      if (synth.getSidechainSyncType() > 0) {
        appendTextAttr(
            sc,
            "syncType",
            String.valueOf(
                org.deluge.xml.DelugeXmlUtil.syncTypeToFile(synth.getSidechainSyncType())));
      }
      if (synth.getSidechainAttackRaw() >= 0 || synth.getSidechainAttack() > 0) {
        appendTextAttr(
            sc,
            "attack",
            synth.getSidechainAttackRaw() >= 0
                ? String.valueOf(synth.getSidechainAttackRaw())
                : String.valueOf((int) Math.round(synth.getSidechainAttack() * Integer.MAX_VALUE)));
      }
      if (synth.getSidechainReleaseRaw() >= 0 || synth.getSidechainRelease() > 0) {
        appendTextAttr(
            sc,
            "release",
            synth.getSidechainReleaseRaw() >= 0
                ? String.valueOf(synth.getSidechainReleaseRaw())
                : String.valueOf(
                    (int) Math.round(synth.getSidechainRelease() * Integer.MAX_VALUE)));
      }
      root.appendChild(sc);
    }
    Element synthStutter = doc.createElement("stutter");
    appendTextAttr(synthStutter, "quantized", synth.getStutter().isStutterQuantized() ? "1" : "0");
    appendTextAttr(synthStutter, "reverse", synth.getStutter().isStutterReversed() ? "1" : "0");
    appendTextAttr(synthStutter, "pingPong", synth.getStutter().isStutterPingPong() ? "1" : "0");
    root.appendChild(synthStutter);

    // ── defaultParams (C sound.cpp:4056-4162) ──
    Element dp = doc.createElement("defaultParams");
    appendHexChildUnipolar(doc, dp, "arpeggiatorGate", synth.getArp().gate());
    appendRawQ31Child(doc, dp, "portamento", synth.getPortamentoQ31());
    appendSynthRawOrFallback(
        doc,
        dp,
        "compressorShape",
        synth,
        Param.UNPATCHED_SIDECHAIN_SHAPE,
        () -> appendHexChildUnipolar(doc, dp, "compressorShape", synth.getCompressorShape()));
    // Write the oscillators' ACTUAL volumes. These were derived from oscMix as (mix, 1 - mix),
    // which models a crossfade — but the Deluge's two oscillator volumes are independent, and
    // setOscAVolume also assigns oscMix. So a patch with both oscillators at full ("018 Rich Saw
    // Lead") saved oscBVolume as 1 - 1 = 0, silencing osc B on reload.
    appendSynthRawOrFallback(
        doc,
        dp,
        "oscAVolume",
        synth,
        Param.LOCAL_OSC_A_VOLUME,
        () -> appendHexChildUnipolar(doc, dp, "oscAVolume", synth.getOscAVolume()));
    appendSynthRawOrFallback(
        doc,
        dp,
        "oscAPulseWidth",
        synth,
        Param.LOCAL_OSC_A_PHASE_WIDTH,
        () ->
            appendRawQ31Child(
                doc,
                dp,
                "oscAPulseWidth",
                synth.getOsc1PhaseWidthQ31() != Integer.MIN_VALUE
                    ? synth.getOsc1PhaseWidthQ31()
                    : 0));
    // C sound.cpp:4067-4068 — oscAWavetablePosition
    appendSynthRawOrFallback(
        doc,
        dp,
        "oscAWavetablePosition",
        synth,
        Param.LOCAL_OSC_A_WAVE_INDEX,
        () -> appendHexChildUnipolar(doc, dp, "oscAWavetablePosition", synth.getWaveIndex()));
    appendSynthRawOrFallback(
        doc,
        dp,
        "oscBVolume",
        synth,
        Param.LOCAL_OSC_B_VOLUME,
        () -> appendHexChildUnipolar(doc, dp, "oscBVolume", synth.getOscBVolume()));
    appendSynthRawOrFallback(
        doc,
        dp,
        "oscBPulseWidth",
        synth,
        Param.LOCAL_OSC_B_PHASE_WIDTH,
        () ->
            appendRawQ31Child(
                doc,
                dp,
                "oscBPulseWidth",
                synth.getOsc2PhaseWidthQ31() != Integer.MIN_VALUE
                    ? synth.getOsc2PhaseWidthQ31()
                    : 0));
    // C sound.cpp:4071-4072 — oscBWavetablePosition
    appendSynthRawOrFallback(
        doc,
        dp,
        "oscBWavetablePosition",
        synth,
        Param.LOCAL_OSC_B_WAVE_INDEX,
        () -> appendHexChildUnipolar(doc, dp, "oscBWavetablePosition", synth.getOsc2WaveIndex()));
    // C sound.cpp:4099-4107 — pitchAdjust, oscAPitchAdjust, oscBPitchAdjust, mod1PitchAdjust,
    // mod2PitchAdjust
    Integer pitchVal = synth.getRawKnobs().getRawParamKnobs().get(Param.LOCAL_PITCH_ADJUST);
    if (pitchVal != null && pitchVal != 0) {
      appendRawQ31Child(doc, dp, "pitchAdjust", pitchVal);
    } else if (synth.getPitchAdjustQ31() != Integer.MIN_VALUE && synth.getPitchAdjustQ31() != 0) {
      appendRawQ31Child(doc, dp, "pitchAdjust", synth.getPitchAdjustQ31());
    }
    if (synth.getOsc1PitchAdjustQ31() != Integer.MIN_VALUE && synth.getOsc1PitchAdjustQ31() != 0) {
      appendRawQ31Child(doc, dp, "oscAPitchAdjust", synth.getOsc1PitchAdjustQ31());
    }
    if (synth.getOsc2PitchAdjustQ31() != Integer.MIN_VALUE && synth.getOsc2PitchAdjustQ31() != 0) {
      appendRawQ31Child(doc, dp, "oscBPitchAdjust", synth.getOsc2PitchAdjustQ31());
    }
    Integer mod1Pitch =
        synth.getRawKnobs().getRawParamKnobs().get(Param.LOCAL_MODULATOR_0_PITCH_ADJUST);
    if (mod1Pitch != null && mod1Pitch != 0) {
      appendRawQ31Child(doc, dp, "mod1PitchAdjust", mod1Pitch);
    }
    Integer mod2Pitch =
        synth.getRawKnobs().getRawParamKnobs().get(Param.LOCAL_MODULATOR_1_PITCH_ADJUST);
    if (mod2Pitch != null && mod2Pitch != 0) {
      appendRawQ31Child(doc, dp, "mod2PitchAdjust", mod2Pitch);
    }
    appendSynthRawOrFallback(
        doc,
        dp,
        "noiseVolume",
        synth,
        Param.LOCAL_NOISE_VOLUME,
        () -> appendHexChildUnipolar(doc, dp, "noiseVolume", synth.getNoiseVol()));
    appendSynthRawOrFallback(
        doc,
        dp,
        "volume",
        synth,
        Param.GLOBAL_VOLUME_POST_FX,
        () -> appendHexChildUnipolar(doc, dp, "volume", synth.getVolume()));
    appendSynthRawOrFallback(
        doc,
        dp,
        "pan",
        synth,
        Param.LOCAL_PAN,
        () -> appendHexChild(doc, dp, "pan", synth.getPan()));
    appendSynthRawOrFallback(
        doc,
        dp,
        "lpfFrequency",
        synth,
        Param.LOCAL_LPF_FREQ,
        () -> appendHexFreq(doc, dp, "lpfFrequency", synth.getLpfFreq()));
    appendSynthRawOrFallback(
        doc,
        dp,
        "lpfResonance",
        synth,
        Param.LOCAL_LPF_RESONANCE,
        () -> appendHexChildUnipolar(doc, dp, "lpfResonance", synth.getLpfRes()));
    appendSynthRawOrFallback(
        doc,
        dp,
        "lpfMorph",
        synth,
        Param.LOCAL_LPF_MORPH,
        () -> appendHexChildUnipolar(doc, dp, "lpfMorph", synth.getLpfMorph()));
    appendSynthRawOrFallback(
        doc,
        dp,
        "hpfFrequency",
        synth,
        Param.LOCAL_HPF_FREQ,
        () -> appendHexFreq(doc, dp, "hpfFrequency", synth.getHpfFreq()));
    appendSynthRawOrFallback(
        doc,
        dp,
        "hpfResonance",
        synth,
        Param.LOCAL_HPF_RESONANCE,
        () -> appendHexChildUnipolar(doc, dp, "hpfResonance", synth.getHpfRes()));
    appendSynthRawOrFallback(
        doc,
        dp,
        "hpfMorph",
        synth,
        Param.LOCAL_HPF_MORPH,
        () -> appendHexChildUnipolar(doc, dp, "hpfMorph", synth.getHpfMorph()));
    appendSynthRawOrFallback(
        doc,
        dp,
        "waveFold",
        synth,
        Param.LOCAL_FOLD,
        () -> appendRawQ31Child(doc, dp, "waveFold", synth.getWaveFoldQ31()));

    // FM params (C sound.cpp:4089-4097 — exact signed Q31 knobs)
    appendRawQ31Child(doc, dp, "modulator1Amount", synth.getModulator1AmountQ31());
    appendRawQ31Child(doc, dp, "modulator1Feedback", synth.getModulator1FeedbackQ31());
    appendRawQ31Child(doc, dp, "modulator2Amount", synth.getModulator2AmountQ31());
    appendRawQ31Child(doc, dp, "modulator2Feedback", synth.getModulator2FeedbackQ31());
    appendRawQ31Child(doc, dp, "carrier1Feedback", synth.getCarrier1FeedbackQ31());
    appendRawQ31Child(doc, dp, "carrier2Feedback", synth.getCarrier2FeedbackQ31());

    appendSynthRawOrFallback(
        doc,
        dp,
        "modFXRate",
        synth,
        Param.GLOBAL_MOD_FX_RATE,
        () -> appendHexChildUnipolar(doc, dp, "modFXRate", synth.getModFxRate()));
    appendSynthRawOrFallback(
        doc,
        dp,
        "modFXDepth",
        synth,
        Param.GLOBAL_MOD_FX_DEPTH,
        () -> appendHexChildUnipolar(doc, dp, "modFXDepth", synth.getModFxDepth()));
    appendSynthRawOrFallback(
        doc,
        dp,
        "modFXOffset",
        synth,
        Param.UNPATCHED_MOD_FX_OFFSET,
        () -> appendHexChildUnipolar(doc, dp, "modFXOffset", synth.getModFxOffset()));
    appendSynthRawOrFallback(
        doc,
        dp,
        "modFXFeedback",
        synth,
        Param.UNPATCHED_MOD_FX_FEEDBACK,
        () -> appendHexChildUnipolar(doc, dp, "modFXFeedback", synth.getModFxFeedback()));
    appendSynthRawOrFallback(
        doc,
        dp,
        "delayRate",
        synth,
        Param.GLOBAL_DELAY_RATE,
        () -> appendHexChildUnipolar(doc, dp, "delayRate", synth.getDelaySend()));
    appendSynthRawOrFallback(
        doc,
        dp,
        "delayFeedback",
        synth,
        Param.GLOBAL_DELAY_FEEDBACK,
        () -> appendRawQ31Child(doc, dp, "delayFeedback", synth.getDelayFeedbackQ31()));
    appendSynthRawOrFallback(
        doc,
        dp,
        "reverbAmount",
        synth,
        Param.GLOBAL_REVERB_AMOUNT,
        () -> appendHexChildUnipolar(doc, dp, "reverbAmount", synth.getReverbSend()));
    // GLOBAL_ARP_RATE (sound.cpp:4117) — the raw param, not the panel's old multiplier.
    appendTextChild(doc, dp, "arpeggiatorRate", String.format("0x%08X", synth.getArpRateQ31()));
    appendSynthRawOrFallback(
        doc,
        dp,
        "stutterRate",
        synth,
        Param.UNPATCHED_STUTTER_RATE,
        () -> appendHexChildUnipolar(doc, dp, "stutterRate", synth.getStutter().getStutterRate()));
    appendSynthRawOrFallback(
        doc,
        dp,
        "sampleRateReduction",
        synth,
        Param.UNPATCHED_SAMPLE_RATE_REDUCTION,
        () ->
            appendHexChildUnipolar(doc, dp, "sampleRateReduction", synth.getSampleRateReduction()));
    appendSynthRawOrFallback(
        doc,
        dp,
        "bitCrush",
        synth,
        Param.UNPATCHED_BITCRUSHING,
        () -> appendHexChildUnipolar(doc, dp, "bitCrush", synth.getBitCrush()));
    appendHexChildUnipolar(doc, dp, "waveIndex", synth.getWaveIndex());
    // Equalizer inside defaultParams (C mod_controllable_audio.cpp:540-549)
    Element eq = doc.createElement("equalizer");
    appendSynthRawOrFallback(
        doc,
        eq,
        "bass",
        synth,
        Param.UNPATCHED_BASS,
        () -> appendHexChild(doc, eq, "bass", synth.getEqBass()));
    appendSynthRawOrFallback(
        doc,
        eq,
        "treble",
        synth,
        Param.UNPATCHED_TREBLE,
        () -> appendHexChild(doc, eq, "treble", synth.getEqTreble()));
    appendSynthRawOrFallback(
        doc,
        eq,
        "bassFrequency",
        synth,
        Param.UNPATCHED_BASS_FREQ,
        () -> appendHexChild(doc, eq, "bassFrequency", synth.getEqBassFreq()));
    appendSynthRawOrFallback(
        doc,
        eq,
        "trebleFrequency",
        synth,
        Param.UNPATCHED_TREBLE_FREQ,
        () -> appendHexChild(doc, eq, "trebleFrequency", synth.getEqTrebleFreq()));
    dp.appendChild(eq);

    // Envelopes 0-3 inside defaultParams
    for (int i = 0; i < 4; i++) {
      if (synth.getRawKnobs().isEnvKnobSet(i)) {
        Element envElem = doc.createElement("envelope" + (i + 1));
        appendRawQ31Child(doc, envElem, "attack", synth.getRawKnobs().getEnvAttackKnobQ31(i));
        appendRawQ31Child(doc, envElem, "decay", synth.getRawKnobs().getEnvDecayKnobQ31(i));
        appendRawQ31Child(doc, envElem, "sustain", synth.getRawKnobs().getEnvSustainKnobQ31(i));
        appendRawQ31Child(doc, envElem, "release", synth.getRawKnobs().getEnvReleaseKnobQ31(i));
        dp.appendChild(envElem);
      } else {
        appendEnvelope(doc, dp, "envelope" + (i + 1), synth.getEnv(i));
      }
    }

    // LFO 1..4 rates inside defaultParams (C sound.cpp:4084-4087)
    for (int i = 0; i < 4; i++) {
      String rateTag = "lfo" + (i + 1) + "Rate";
      if (synth.getRawKnobs().isLfoRateKnobSet(i)) {
        appendRawQ31Child(doc, dp, rateTag, synth.getRawKnobs().getLfoRateKnobQ31(i));
      } else {
        appendHexLfoFreq(doc, dp, rateTag, synth.getLfo(i).rateHz());
      }
    }

    // Patch cables inside defaultParams
    if (!synth.getModulation().getPatchCables().isEmpty()) {
      Element pcContainer = doc.createElement("patchCables");
      for (PatchCable pc : synth.getModulation().getPatchCables()) {
        pcContainer.appendChild(createPatchCableElement(doc, pc));
      }
      dp.appendChild(pcContainer);
    }

    root.appendChild(dp);

    // ── midiKnobs ──
    serializeMidiKnobs(doc, root, synth.getModulation().getMidiKnobs());

    // ── modKnobs ──
    boolean hasKnobs =
        synth.getModulation().getModKnobs().stream().anyMatch(k -> !"NONE".equals(k.param()));
    if (hasKnobs) {
      Element mkContainer = doc.createElement("modKnobs");
      for (ModKnob mk : synth.getModulation().getModKnobs()) {
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

  private static void appendSynthRawOrFallback(
      Document doc,
      Element parent,
      String tag,
      SynthTrackModel synth,
      int paramId,
      Runnable fallback) {
    Integer raw = synth.getRawKnobs().getRawParamKnobs().get(paramId);
    if (raw != null) {
      appendRawQ31Child(doc, parent, tag, raw);
    } else {
      fallback.run();
    }
  }

  private static void appendDrumRawOrFallback(
      Document doc, Element parent, String tag, Drum sound, Runnable fallback) {
    appendDrumRawOrFallback(doc, parent, tag, tag, sound, fallback);
  }

  private static void appendDrumRawOrFallback(
      Document doc, Element parent, String tag, String rawKey, Drum sound, Runnable fallback) {
    Integer raw = sound.getRawDefaultParamQ31(rawKey);
    if (raw != null) {
      appendRawQ31Child(doc, parent, tag, raw);
    } else {
      fallback.run();
    }
  }

  private static void appendBoxedQ31OrFallback(
      Document doc, Element parent, String tag, Integer raw, Runnable fallback) {
    if (raw != null) {
      appendRawQ31Child(doc, parent, tag, raw);
    } else {
      fallback.run();
    }
  }

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
      appendTextChild(
          doc,
          lfoElem,
          "syncType",
          String.valueOf(org.deluge.xml.DelugeXmlUtil.syncTypeToFile(lfo.syncType())));
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
      appendTextChild(
          doc,
          lfoElem,
          "syncType",
          String.valueOf(org.deluge.xml.DelugeXmlUtil.syncTypeToFile(lfo.syncType())));
    }
    parent.appendChild(lfoElem);
  }

  /** Convert LfoType to exact C++ firmware XML token (C functions.cpp:763-784). */
  public static String lfoTypeName(LfoType type) {
    return org.deluge.xml.DelugeXmlUtil.lfoTypeToFirmwareString(type);
  }

  /** Append an envelope element with exact Q31 attack/decay/sustain/release children. */
  public static void appendEnvelope(Document doc, Element parent, String tag, EnvelopeModel env) {
    Element envElem = doc.createElement(tag);
    appendRawQ31Child(doc, envElem, "attack", DelugeHexMapper.envTimeToQ31(env.attack()));
    appendRawQ31Child(doc, envElem, "decay", DelugeHexMapper.envTimeToQ31(env.decay()));
    appendHexChildUnipolar(doc, envElem, "sustain", env.sustain());
    appendRawQ31Child(doc, envElem, "release", DelugeHexMapper.envTimeToQ31(env.release()));
    parent.appendChild(envElem);
  }

  /** Append a child element with a hex string value from a normalized float. */
  public static void appendHexChild(Document doc, Element parent, String tag, float value) {
    if (parent.getElementsByTagName(tag).getLength() > 0) return;
    Element child = doc.createElement(tag);
    child.setTextContent(DelugeHexMapper.floatToHex(value));
    parent.appendChild(child);
  }

  /** Append a child element with an exact signed Q31 hex string from a unipolar [0..1] float. */
  public static void appendHexChildUnipolar(Document doc, Element parent, String tag, float value) {
    if (parent.getElementsByTagName(tag).getLength() > 0) return;
    Element child = doc.createElement(tag);
    child.setTextContent(DelugeHexMapper.unipolarFloatToHexUnified(value));
    parent.appendChild(child);
  }

  /** Append a child element with a hex frequency value from a Hz float. */
  public static void appendHexFreq(Document doc, Element parent, String tag, float hz) {
    if (parent.getElementsByTagName(tag).getLength() > 0) return;
    Element child = doc.createElement(tag);
    child.setTextContent(DelugeHexMapper.hzToHex(hz));
    parent.appendChild(child);
  }

  /**
   * Append a child element with the exact firmware LFO rate knob hex matching {@link
   * org.deluge.engine.FirmwareFactory#lfoRateKnobFromHz(double)}.
   */
  public static void appendHexLfoFreq(Document doc, Element parent, String tag, float hz) {
    if (parent.getElementsByTagName(tag).getLength() > 0) return;
    Element child = doc.createElement(tag);
    child.setTextContent(
        String.format("0x%08X", org.deluge.engine.FirmwareFactory.lfoRateKnobFromHz(hz)));
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

  /**
   * Append a {@code <patchCable>} inside preset {@code <defaultParams>} preserving exact 32-bit Q31
   * cable amounts without automation nodes (C {@code sound.cpp:4251} passes {@code writeAutomation
   * = false}; clip/row {@code <soundParams>} writes automation via {@code ProjectSerializer}).
   */
  private static Element createPatchCableElement(Document doc, PatchCable pc) {
    Element cable = doc.createElement("patchCable");
    appendTextChild(doc, cable, "source", pc.source());
    if (pc.destination() != null && !pc.destination().isEmpty()) {
      appendTextChild(doc, cable, "destination", pc.destination());
    }
    appendTextChild(doc, cable, "amount", pc.resolveBaseHex());
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

  /**
   * Maps a model oscillator-type string to the EXACT spelling the firmware expects.
   *
   * <p>{@code stringToOscType} (functions.cpp:777-815) compares with {@code strcmp}, so the match
   * is case-sensitive, and an unrecognised string falls through to {@code OscType::TRIANGLE}
   * (functions.cpp:813) rather than erroring. This serializer previously wrote {@code
   * getOscNType().toLowerCase()}, so an "analogSaw" oscillator was saved as "analogsaw" and would
   * load on hardware as a TRIANGLE — silent corruption of every preset we write, invisible until
   * the file is opened on a Deluge. Spellings below are copied from oscTypeToString
   * (functions.cpp:734-774).
   */
  private static String oscTypeToFirmwareString(String modelType) {
    if (modelType == null) {
      return "square";
    }
    return switch (modelType.trim().toUpperCase().replace("_", "").replace(" ", "")) {
      case "NONE" -> "none";
      case "SAW" -> "saw";
      case "ANALOGSAW", "ANALOGSAW2" -> "analogSaw";
      case "ANALOGSQUARE" -> "analogSquare";
      case "SINE" -> "sine";
      case "TRIANGLE" -> "triangle";
      case "SAMPLE" -> "sample";
      case "WAVETABLE" -> "wavetable";
      case "INPUTL", "INLEFT" -> "inLeft";
      case "INPUTR", "INRIGHT" -> "inRight";
      case "INPUTSTEREO", "INSTEREO" -> "inStereo";
      case "DX7" -> "dx7";
      default -> "square";
    };
  }

  /**
   * A single-sample source's file and flags (C sound.cpp source tags). saveSynth wrote none of
   * them, so a sample synth saved as a preset lost its sample and its repeat mode.
   */
  private static void appendSampleSource(
      Document doc,
      Element osc,
      String path,
      int loopMode,
      boolean reversed,
      boolean timeStretch,
      boolean linearInterpolation) {
    if (path == null || path.isEmpty()) return;
    appendTextChild(doc, osc, "fileName", path);
    appendTextChild(doc, osc, "loopMode", String.valueOf(loopMode)); // C SampleRepeatMode
    appendTextChild(doc, osc, "reversed", reversed ? "1" : "0");
    appendTextChild(doc, osc, "timeStretchEnable", timeStretch ? "1" : "0");
    // C sound.cpp:3637-3639 — linearInterpolation="1" when linear mode is active.
    if (linearInterpolation) {
      appendTextChild(doc, osc, "linearInterpolation", "1");
    }
  }
}
