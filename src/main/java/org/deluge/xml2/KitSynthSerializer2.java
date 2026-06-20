package org.deluge.xml2;

import java.io.IOException;
import org.deluge.model.Drum;
import org.deluge.model.EnvelopeModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.LfoModel;
import org.deluge.model.LfoType;
import org.deluge.model.ModKnob;
import org.deluge.model.PatchCable;
import org.deluge.model.SoundDrum;
import org.deluge.model.SynthTrackModel;
import org.deluge.xml.DelugeHexMapper;

/** Stream-based instrument serializer utilizing XMLSerializer for 100% formatting parity. */
public class KitSynthSerializer2 {

  public static void serializeSynth(XMLSerializer writer, SynthTrackModel synth, boolean isSongSlot)
      throws IOException {
    writer.writeOpeningTagBeginning("sound");
    writer.writeAttribute("presetName", synth.getName(), false);
    writer.writeAttribute("presetFolder", "SYNTHS", false);
    writer.writeOpeningTagEnd();

    // ── osc1 ──
    writer.writeOpeningTagBeginning("osc1");
    writer.writeAttribute("type", synth.getOsc1Type().toLowerCase(), false);
    writer.writeAttribute("transpose", "0", false);
    writer.writeAttribute("cents", "0", false);
    writer.writeAttribute("retrigPhase", String.valueOf(synth.getOsc1RetrigPhase()), false);
    if (synth.getDx7Patch() != null) {
      writer.writeAttribute("dx7patch", synth.getDx7Patch(), false);
    }
    writer.closeTag();

    // ── osc2 ──
    writer.writeOpeningTagBeginning("osc2");
    writer.writeAttribute("type", synth.getOsc2Type().toLowerCase(), false);
    writer.writeAttribute("transpose", "0", false);
    writer.writeAttribute("cents", "0", false);
    writer.writeAttribute("retrigPhase", String.valueOf(synth.getOsc2RetrigPhase()), false);
    writer.closeTag();

    // ── polyphonic ──
    String polyVal =
        switch (synth.getPolyphony()) {
          case MONO -> "0";
          case LEGATO -> "legato";
          default -> "1";
        };
    writer.writeTag("polyphonic", polyVal);
    writer.writeTag("clippingAmount", "0");
    writer.writeTag("voicePriority", "1");

    // ── LFOs ──
    serializeLfo(writer, "lfo1", synth.getLfo(0));
    serializeLfo(writer, "lfo2", synth.getLfo(1));
    serializeLfo(writer, "lfo3", synth.getLfo(2));
    serializeLfo(writer, "lfo4", synth.getLfo(3));

    // ── customLfoWave ──
    StringBuilder sb = new StringBuilder();
    int[] wave = synth.getCustomLfoWave();
    for (int i = 0; i < 256; i++) {
      sb.append(wave[i]);
      if (i < 255) sb.append(",");
    }
    writer.writeTag("customLfoWave", sb.toString());

    // ── synth mode ──
    String mode =
        switch (synth.getSynthMode()) {
          case 1 -> "fm";
          case 2 -> "ringmod";
          default -> "subtractive";
        };
    writer.writeTag("mode", mode);
    writer.writeTag("transpose", "0");

    // ── unison ──
    writer.writeOpeningTagBeginning("unison");
    writer.writeOpeningTagEnd();
    writer.writeTag("num", String.valueOf(synth.getUnisonNum()));
    writer.writeTag("detune", DelugeHexMapper.floatToHex(synth.getUnisonDetune()));
    writer.writeTag("spread", DelugeHexMapper.floatToHex(synth.getUnisonStereoSpread()));
    writer.writeClosingTag("unison");

    // ── arpeggiator ──
    writer.writeOpeningTagBeginning("arpeggiator");
    writer.writeAttribute("mode", synth.getArp().mode().toLowerCase(), false);
    writer.writeAttribute("active", synth.getArp().active() ? "1" : "0", false);
    writer.writeOpeningTagEnd();
    writeHexTag(writer, "rate", synth.getArp().rate());
    writer.writeTag("octaves", String.valueOf(synth.getArp().octaves()));
    writeHexTag(writer, "gate", synth.getArp().gate());
    writer.writeTag("syncLevel", String.valueOf(synth.getArp().syncLevel()));
    writer.writeTag("noteMode", synth.getArp().noteMode().toLowerCase());
    writer.writeTag("octaveMode", synth.getArp().octaveMode().toLowerCase());
    writer.writeTag("stepRepeat", String.valueOf(synth.getArp().stepRepeat()));
    writer.writeTag("rhythmIndex", String.valueOf(synth.getArp().rhythmIndex()));
    writer.writeTag("seqLength", String.valueOf(synth.getArp().seqLength()));
    writeHexTag(writer, "octaveSpread", synth.getArp().octaveSpread());
    writeHexTag(writer, "gateSpread", synth.getArp().gateSpread());
    writeHexTag(writer, "velSpread", synth.getArp().velSpread());
    writer.writeTag("ratchetAmount", String.valueOf(synth.getArp().ratchetAmount()));
    writer.writeTag("noteProbability", String.valueOf(synth.getArp().noteProbability()));
    writer.writeTag("chordPolyphony", String.valueOf(synth.getArp().chordPolyphony()));
    writer.writeTag("chordProbability", String.valueOf(synth.getArp().chordProbability()));
    writer.writeClosingTag("arpeggiator");

    // ── delay ──
    if (synth.getDelaySend() > 0) {
      writer.writeOpeningTagBeginning("delay");
      writer.writeOpeningTagEnd();
      writer.writeTag("pingPong", "1");
      writer.writeTag("analog", "1");
      writer.writeTag("syncLevel", "7");
      writer.writeClosingTag("delay");
    }

    writer.writeTag(
        "lpfMode",
        switch (synth.getFilterMode()) {
          case LADDER_24 -> "24dB";
          case SVF -> "SVF";
          default -> "12dB";
        });

    writer.writeTag(
        "hpfMode",
        switch (synth.getHpfMode()) {
          case LADDER_24 -> "24dB";
          case SVF -> "SVF";
          case DRIVE -> "DRIVE";
          case SVF_BAND -> "SVF Band";
          case SVF_NOTCH -> "SVF Notch";
          default -> "12dB";
        });

    String mfx = synth.getModFxType() != null ? synth.getModFxType().toLowerCase() : "none";
    writer.writeTag("modFXType", mfx);

    // ── compressor ──
    float compAttack = synth.getCompressorAttack();
    float compRelease = synth.getCompressorRelease();
    int compSync = synth.getCompressorSyncLevel();
    if (compAttack > 0 || compRelease > 0 || compSync > 0) {
      writer.writeOpeningTagBeginning("compressor");
      writer.writeAttribute("syncLevel", String.valueOf(compSync), false);
      writer.writeAttribute(
          "attack", String.valueOf((int) (compAttack * Integer.MAX_VALUE)), false);
      writer.writeAttribute(
          "release", String.valueOf((int) (compRelease * Integer.MAX_VALUE)), false);
      writer.closeTag();
    }

    // ── defaultParams ──
    writer.writeOpeningTagBeginning("defaultParams");
    writer.writeOpeningTagEnd();
    writeHexTagUnipolar(writer, "arpeggiatorGate", synth.getArp().gate());
    writeHexTagUnipolar(writer, "portamento", synth.getPortamento());
    writeHexTagUnipolar(writer, "compressorShape", 0.92f);
    writeHexTagUnipolar(writer, "oscAVolume", synth.getOscMix());
    writeHexTagUnipolar(writer, "oscAPulseWidth", 0f);
    writeHexTagUnipolar(writer, "oscBVolume", 1.0f - synth.getOscMix());
    writeHexTagUnipolar(writer, "oscBPulseWidth", 0f);
    writeHexTagUnipolar(writer, "noiseVolume", synth.getNoiseVol());
    writeHexTagUnipolar(writer, "volume", synth.getVolume());
    writeHexTag(writer, "pan", synth.getPan());
    writeHexTagFreq(writer, "lpfFrequency", synth.getLpfFreq());
    writeHexTagUnipolar(writer, "lpfResonance", synth.getLpfRes());
    writeHexTagFreq(writer, "hpfFrequency", synth.getHpfFreq());
    writeHexTagUnipolar(writer, "hpfResonance", synth.getHpfRes());
    writeHexTagUnipolar(writer, "hpfMorph", synth.getHpfMorph());

    // FM params
    writeHexTagUnipolar(writer, "modulator1Amount", synth.getFmAmount());
    writeHexTagUnipolar(writer, "modulator1Feedback", synth.getModulator1Feedback());
    writeHexTagUnipolar(writer, "modulator2Amount", synth.getModulator2Amount());
    writeHexTagUnipolar(writer, "modulator2Feedback", synth.getModulator2Feedback());
    writeHexTagUnipolar(writer, "carrier1Feedback", synth.getCarrier1Feedback());
    writeHexTagUnipolar(writer, "carrier2Feedback", synth.getCarrier2Feedback());

    writeHexTagUnipolar(writer, "modFXRate", synth.getModFxRate());
    writeHexTagUnipolar(writer, "modFXDepth", synth.getModFxDepth());
    writeHexTagUnipolar(writer, "modFXOffset", 0f);
    writeHexTagUnipolar(writer, "modFXFeedback", synth.getModFxFeedback());
    writeHexTagUnipolar(writer, "delayRate", synth.getDelaySend());
    writeHexTagUnipolar(writer, "delayFeedback", 0f);
    writeHexTagUnipolar(writer, "reverbAmount", synth.getReverbSend());
    writeHexTagUnipolar(writer, "arpeggiatorRate", 0f);
    writeHexTagUnipolar(writer, "stutterRate", synth.getStutterRate());
    writeHexTagUnipolar(writer, "sampleRateReduction", synth.getSampleRateReduction());
    writeHexTagUnipolar(writer, "bitCrush", synth.getBitCrush());
    writeHexTagUnipolar(writer, "waveIndex", synth.getWaveIndex());

    // Equalizer
    writer.writeOpeningTagBeginning("equalizer");
    writer.writeOpeningTagEnd();
    writeHexTag(writer, "bass", synth.getEqBass());
    writeHexTag(writer, "treble", synth.getEqTreble());
    writeHexTag(writer, "bassFrequency", 0f);
    writeHexTag(writer, "trebleFrequency", 0f);
    writer.writeClosingTag("equalizer");

    // Envelopes 1-4
    for (int i = 0; i < 4; i++) {
      serializeEnvelope(writer, "envelope" + (i + 1), synth.getEnv(i));
    }

    // LFO rates
    writeHexTag(writer, "lfo1Rate", synth.getLfo(0).rateHz() / 100.0f);
    writeHexTag(writer, "lfo2Rate", synth.getLfo(1).rateHz() / 100.0f);

    // Patch cables
    if (!synth.getPatchCables().isEmpty()) {
      writer.writeArrayStart("patchCables");
      for (PatchCable pc : synth.getPatchCables()) {
        writer.writeOpeningTagBeginning("patchCable");
        writer.writeOpeningTagEnd();
        writer.writeTag("source", pc.source());
        writer.writeTag("destination", pc.destination());
        writeHexTag(writer, "amount", pc.amount());
        writer.writeClosingTag("patchCable");
      }
      writer.writeArrayEnding("patchCables");
    }

    writeHexTag(writer, "modFXOffset", 0f);
    writeHexTag(writer, "modFXFeedback", synth.getModFxFeedback());
    writer.writeClosingTag("defaultParams");

    writer.writeTag("midiKnobs", "");

    // modKnobs
    boolean hasKnobs = synth.getModKnobs().stream().anyMatch(k -> !"NONE".equals(k.param()));
    if (hasKnobs) {
      writer.writeArrayStart("modKnobs");
      for (ModKnob mk : synth.getModKnobs()) {
        if (!"NONE".equals(mk.param())) {
          writer.writeOpeningTagBeginning("modKnob");
          writer.writeOpeningTagEnd();
          writer.writeTag("controlsParam", mk.param());
          if (mk.patchSource() != null && !"NONE".equals(mk.patchSource())) {
            writer.writeTag("patchAmountFromSource", mk.patchSource());
          }
          writer.writeClosingTag("modKnob");
        }
      }
      writer.writeArrayEnding("modKnobs");
    }

    writer.writeClosingTag("sound");
  }

  public static void serializeKit(XMLSerializer writer, KitTrackModel kit, boolean isSongSlot)
      throws IOException {
    writer.writeOpeningTagBeginning("kit");
    writer.writeAttribute("presetName", kit.getName(), false);
    writer.writeAttribute("presetFolder", "KITS", false);
    writer.writeOpeningTagEnd();

    for (Drum drum : kit.getDrums()) {
      SoundDrum sound = (SoundDrum) drum;
      writer.writeOpeningTagBeginning("sound");
      writer.writeAttribute("name", sound.getName(), false);
      writer.writeAttribute("polyphonic", "auto", false);
      writer.writeAttribute("voicePriority", 1, false);
      writer.writeAttribute("mode", "subtractive", false);
      writer.writeAttribute("lpfMode", "24dB", false);
      writer.writeAttribute("hpfMode", "HPLadder", false);
      writer.writeAttribute("filterRoute", "H2L", false);
      writer.writeAttribute("maxVoices", 8, false);
      writer.writeOpeningTagEnd();

      writer.writeOpeningTagBeginning("osc1");
      if (sound.getSamplePath() != null && !sound.getSamplePath().isEmpty()) {
        writer.writeAttribute("type", "sample", false);
        writer.closeTag();
        writer.writeOpeningTagBeginning("sample");
        writer.writeAttribute("fileName", sound.getSamplePath(), false);
        writer.closeTag();
      } else {
        writer.writeAttribute("type", "none", false);
        writer.closeTag();
      }

      writer.writeOpeningTagBeginning("osc2");
      writer.writeAttribute("type", sound.getOsc2Type().toLowerCase(), false);
      writer.closeTag();

      // Write minimal LFOs for drum slots
      serializeLfo(writer, "lfo1", sound.getLfo1());
      serializeLfo(writer, "lfo2", sound.getLfo2());

      writer.writeOpeningTagBeginning("unison");
      writer.writeOpeningTagEnd();
      writer.writeTag("num", String.valueOf(sound.getUnisonNum()));
      writer.writeTag("detune", DelugeHexMapper.floatToHex(sound.getUnisonDetune()));
      writer.writeClosingTag("unison");

      // Drum sound defaultParams
      writer.writeOpeningTagBeginning("defaultParams");
      writer.writeOpeningTagEnd();
      writeHexTagUnipolar(writer, "volume", sound.getVolume());
      writeHexTag(writer, "pan", sound.getPan());
      writeHexTagUnipolar(writer, "oscAVolume", sound.getOscAVolume());
      writeHexTagUnipolar(writer, "oscBVolume", sound.getOscBVolume());
      writeHexTagUnipolar(writer, "noiseVolume", sound.getNoiseVolume());
      writeHexTagFreq(writer, "lpfFrequency", sound.getLpfFreq());
      writeHexTagUnipolar(writer, "lpfResonance", sound.getLpfRes());
      writeHexTagFreq(writer, "hpfFrequency", sound.getHpfFreq());
      writeHexTagUnipolar(writer, "hpfResonance", sound.getHpfRes());
      writeHexTag(writer, "pitch", sound.getPitchSemitones());
      writeHexTagUnipolar(writer, "reverbAmount", sound.getReverbAmount());
      writeHexTagUnipolar(writer, "delayRate", sound.getDelayRate());

      serializeEnvelope(writer, "envelope1", sound.getAdsr());
      serializeEnvelope(writer, "envelope2", sound.getEnv2());

      writer.writeClosingTag("defaultParams");
      writer.writeTag("midiKnobs", "");
      writer.writeClosingTag("sound");
    }

    writer.writeClosingTag("kit");
  }

  // ── Helper serializers ──

  private static void serializeLfo(XMLSerializer writer, String tag, LfoModel lfo)
      throws IOException {
    writer.writeOpeningTagBeginning(tag);
    writer.writeOpeningTagEnd();
    writer.writeTag("type", lfoTypeName(lfo.waveform()));
    if (lfo.syncLevel() > 0) {
      writer.writeTag("syncLevel", String.valueOf(lfo.syncLevel()));
    }
    writeHexTagLfoFreq(writer, "rate", lfo.rateHz());
    writeHexTagUnipolar(writer, "depth", lfo.depth());
    if (lfo.syncType() > 0) {
      writer.writeTag("syncType", String.valueOf(lfo.syncType()));
    }
    writer.writeClosingTag(tag);
  }

  private static String lfoTypeName(LfoType type) {
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

  private static void serializeEnvelope(XMLSerializer writer, String tag, EnvelopeModel env)
      throws IOException {
    writer.writeOpeningTagBeginning(tag);
    writer.writeOpeningTagEnd();
    writeHexTag(writer, "attack", DelugeHexMapper.normFromEnvTime(env.attack()));
    writeHexTag(writer, "decay", DelugeHexMapper.normFromEnvTime(env.decay()));
    writeHexTag(writer, "sustain", DelugeHexMapper.normFromSustain(env.sustain()));
    writeHexTag(writer, "release", DelugeHexMapper.normFromEnvTime(env.release()));
    writer.writeClosingTag(tag);
  }

  private static void writeHexTag(XMLSerializer writer, String tag, float val) throws IOException {
    writer.writeTag(tag, DelugeHexMapper.floatToHex(val));
  }

  private static void writeHexTagUnipolar(XMLSerializer writer, String tag, float val)
      throws IOException {
    writer.writeTag(tag, DelugeHexMapper.floatToHex(val * 2.0f - 1.0f));
  }

  private static void writeHexTagFreq(XMLSerializer writer, String tag, float hz)
      throws IOException {
    writer.writeTag(tag, DelugeHexMapper.hzToHex(hz));
  }

  private static void writeHexTagLfoFreq(XMLSerializer writer, String tag, float hz)
      throws IOException {
    writer.writeTag(tag, DelugeHexMapper.lfoHzToHex(hz));
  }
}
