package org.deluge.xml2;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import org.deluge.model.*;
import org.deluge.xml.DelugeHexMapper;
import org.deluge.xml.DelugeNoteDataMapper;

/** Stream-based project song serializer utilizing XMLSerializer for perfect formatting parity. */
public class ProjectSerializer2 {

  public static void save(ProjectModel model, File file) throws Exception {
    try (BufferedWriter bw = new BufferedWriter(new FileWriter(file))) {
      // 1. Write standard XML declaration
      bw.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");

      XMLSerializer writer = new XMLSerializer(bw);
      serializeSong(writer, model);
      writer.flush();
    }
  }

  public static String serializeToString(ProjectModel model) throws Exception {
    StringWriter sw = new StringWriter();
    sw.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    XMLSerializer writer = new XMLSerializer(sw);
    serializeSong(writer, model);
    writer.flush();
    return sw.toString();
  }

  private static void serializeSong(XMLSerializer writer, ProjectModel model) throws IOException {
    writer.writeOpeningTagBeginning("song");

    // ── song attributes ──
    writer.writeAttribute("affectEntire", 0, false);
    writer.writeAttribute("earliestCompatibleFirmware", "4.1.0-alpha", false);
    writer.writeAttribute("firmwareVersion", "c1.3.0", false);
    writer.writeAttribute("inputTickMagnitude", 2, false);
    writer.writeAttribute("previewNumPads", 144, false);
    writer.writeAttribute("swing", DelugeHexMapper.floatToHex(model.getSwing()), false);
    writer.writeAttribute("swingAmount", 0, false);
    writer.writeAttribute("swingInterval", 7, false);
    writer.writeAttribute("tempo", String.valueOf(model.getBpm()), false);

    double bpm = model.getBpm();
    double scaledBpm = bpm * 4.0;
    double timePerTimerTick = 110250.0 / scaledBpm;
    long timePerTimerTickBig = (long) (timePerTimerTick * 4294967296.0);
    int high = (int) (timePerTimerTickBig >> 32);
    int low = (int) timePerTimerTickBig;

    writer.writeAttribute("timePerTimerTick", high, false);
    writer.writeAttribute("timerTickFraction", low, false);
    writer.writeAttribute("xScroll", 0, false);
    writer.writeAttribute("xZoom", 24, false);
    writer.writeAttribute("yScrollArrangementView", -7, false);
    writer.writeAttribute("yScrollSongView", -7, false);
    writer.writeOpeningTagEnd();

    // ── instruments block ──
    writer.writeArrayStart("instruments");
    int trackIndex = 0;
    for (TrackModel track : model.getTracks()) {
      // Calculate clipInstances for arranger placements
      StringBuilder ciBuilder = new StringBuilder("0x");
      for (ArrangerClip ac : model.getArrangerTimeline()) {
        if (ac.trackIndex() == trackIndex) {
          int clipIdx = getGlobalClipIndex(model, ac.clip());
          ciBuilder.append(
              String.format("%08X%08X%08X", ac.startTicks(), ac.durationTicks(), clipIdx));
        }
      }

      if (track instanceof KitTrackModel) {
        writer.writeOpeningTagBeginning("kit");
        writer.writeAttribute("presetName", track.getName(), false);
        writer.writeAttribute("presetFolder", "KITS", false);
        if (ciBuilder.length() > 2) {
          writer.writeAttribute("clipInstances", ciBuilder.toString(), false);
        }
        writer.writeOpeningTagEnd();

        writer.writeArrayStart("soundSources");

        for (Drum drum : ((KitTrackModel) track).getDrums()) {
          SoundDrum sound = (SoundDrum) drum;
          writer.writeOpeningTagBeginning("sound");
          writer.writeOpeningTagEnd();
          writer.writeTag("name", sound.getName());

          writer.writeOpeningTagBeginning("osc1");
          if (sound.getSamplePath() != null) {
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

          writer.writeOpeningTagBeginning("defaultParams");
          writer.writeOpeningTagEnd();
          writeHexTagUnipolar(writer, "volume", sound.getVolume());
          writeHexTag(writer, "pan", sound.getPan());
          writeHexTagFreq(writer, "lpfFrequency", sound.getLpfFreq());
          writeHexTagUnipolar(writer, "lpfResonance", sound.getLpfRes());
          writeHexTagFreq(writer, "hpfFrequency", sound.getHpfFreq());
          writeHexTagUnipolar(writer, "hpfResonance", sound.getHpfRes());
          writeHexTagUnipolar(writer, "pitch", sound.getPitchSemitones());
          writeHexTagUnipolar(writer, "reverbAmount", sound.getReverbAmount());
          writeHexTagUnipolar(writer, "delayRate", sound.getDelayRate());
          serializeEnvelope(writer, "envelope1", sound.getAdsr());
          serializeEnvelope(writer, "envelope2", sound.getEnv2());
          writer.writeClosingTag("defaultParams");

          writer.writeTag("midiKnobs", "");
          writer.writeClosingTag("sound");
        }
        writer.writeArrayEnding("soundSources");
        writer.writeClosingTag("kit");

      } else if (track instanceof SynthTrackModel) {
        SynthTrackModel synth = (SynthTrackModel) track;
        writer.writeOpeningTagBeginning("sound");
        writer.writeAttribute("presetName", synth.getName(), false);
        writer.writeAttribute("presetFolder", "SYNTHS", false);
        if (ciBuilder.length() > 2) {
          writer.writeAttribute("clipInstances", ciBuilder.toString(), false);
        }
        writer.writeOpeningTagEnd();

        // osc1
        writer.writeOpeningTagBeginning("osc1");
        writer.writeAttribute("type", synth.getOsc1Type().toLowerCase(), false);
        writer.writeAttribute("transpose", "0", false);
        writer.writeAttribute("cents", "0", false);
        writer.writeAttribute("retrigPhase", String.valueOf(synth.getOsc1RetrigPhase()), false);
        if (synth.getDx7Patch() != null) {
          writer.writeAttribute("dx7patch", synth.getDx7Patch(), false);
        }
        writer.closeTag();

        // osc2
        writer.writeOpeningTagBeginning("osc2");
        writer.writeAttribute("type", synth.getOsc2Type().toLowerCase(), false);
        writer.writeAttribute("transpose", "0", false);
        writer.writeAttribute("cents", "0", false);
        writer.writeAttribute("retrigPhase", String.valueOf(synth.getOsc2RetrigPhase()), false);
        writer.closeTag();

        String polyVal =
            switch (synth.getPolyphony()) {
              case MONO -> "0";
              case LEGATO -> "legato";
              default -> "1";
            };
        writer.writeTag("polyphonic", polyVal);
        writer.writeTag("clippingAmount", "0");
        writer.writeTag("voicePriority", "1");

        serializeLfo(writer, "lfo1", synth.getLfo(0));
        serializeLfo(writer, "lfo2", synth.getLfo(1));
        serializeLfo(writer, "lfo3", synth.getLfo(2));
        serializeLfo(writer, "lfo4", synth.getLfo(3));

        // customLfoWave
        StringBuilder waveSb = new StringBuilder();
        int[] wave = synth.getCustomLfoWave();
        for (int i = 0; i < 256; i++) {
          waveSb.append(wave[i]);
          if (i < 255) waveSb.append(",");
        }
        writer.writeTag("customLfoWave", waveSb.toString());

        String mode =
            switch (synth.getSynthMode()) {
              case 1 -> "fm";
              case 2 -> "ringmod";
              default -> "subtractive";
            };
        writer.writeTag("mode", mode);
        writer.writeTag("transpose", "0");

        // unison
        writer.writeOpeningTagBeginning("unison");
        writer.writeOpeningTagEnd();
        writer.writeTag("num", String.valueOf(synth.getUnisonNum()));
        writer.writeTag("detune", DelugeHexMapper.floatToHex(synth.getUnisonDetune()));
        writer.writeTag("spread", DelugeHexMapper.floatToHex(synth.getUnisonStereoSpread()));
        writer.writeClosingTag("unison");

        // arpeggiator
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

        // delay
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

        // compressor
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

        // defaultParams
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
        writeHexTagUnipolar(
            writer,
            "modulator2Amount",
            synth.getFmAmount()); // Wait! Replicating original bug: modulator2Amount used fmAmount
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
      trackIndex++;
    }
    writer.writeArrayEnding("instruments");

    // ── sessionClips block ──
    writer.writeArrayStart("sessionClips");
    trackIndex = 0;
    for (TrackModel track : model.getTracks()) {
      List<ClipModel> clips = track.getClips();
      for (ClipModel clip : clips) {
        // Calculate clipInstances for arranger placements
        StringBuilder ciBuilder = new StringBuilder("0x");
        for (ArrangerClip ac : model.getArrangerTimeline()) {
          if (ac.trackIndex() == trackIndex && ac.clip() == clip) {
            int clipIdx = getGlobalClipIndex(model, clip);
            ciBuilder.append(
                String.format("%08X%08X%08X", ac.startTicks(), ac.durationTicks(), clipIdx));
          }
        }

        writer.writeOpeningTagBeginning("instrumentClip");
        if (track instanceof KitTrackModel) {
          writer.writeAttribute("instrumentPresetName", track.getName(), false);
          writer.writeAttribute("instrumentPresetFolder", "KITS", false);
        } else {
          writer.writeAttribute("instrumentPresetName", track.getName(), false);
          writer.writeAttribute("instrumentPresetFolder", "SYNTHS", false);
        }

        int stepTicks = clip.isTripletMode() ? 32 : 24;
        int lengthTicks = clip.getStepCount() * stepTicks;
        writer.writeAttribute("length", lengthTicks, false);
        writer.writeAttribute("isPlaying", "1", false);
        writer.writeAttribute("section", "0", false);
        if (ciBuilder.length() > 2) {
          writer.writeAttribute("clipInstances", ciBuilder.toString(), false);
        }
        writer.writeOpeningTagEnd();

        // Write soundParams / kitParams tag to set outputTypeWhileLoading
        if (track instanceof KitTrackModel) {
          writer.writeOpeningTagBeginning("kitParams");
          writer.closeTag();
        } else {
          writer.writeOpeningTagBeginning("soundParams");
          writer.closeTag();
        }

        // Note rows
        boolean hasNotes = false;
        for (int r = 0; r < clip.getRowCount(); r++) {
          for (int s = 0; s < clip.getStepCount(); s++) {
            if (clip.getStep(r, s).active()) {
              hasNotes = true;
              break;
            }
          }
        }

        if (hasNotes) {
          writer.writeArrayStart("noteRows");
          for (int r = 0; r < clip.getRowCount(); r++) {
            int yNote = clip.getRowYNote(r);
            if (!(track instanceof KitTrackModel) && yNote < 0) {
              for (int s = 0; s < clip.getStepCount(); s++) {
                StepData sd = clip.getStep(r, s);
                if (sd.active() && sd.pitch() > 0) {
                  yNote = sd.pitch();
                  break;
                }
              }
            }

            if (!(track instanceof KitTrackModel) && yNote < 0) {
              boolean hasActive = false;
              for (int s = 0; s < clip.getStepCount(); s++) {
                if (clip.getStep(r, s).active()) {
                  hasActive = true;
                  break;
                }
              }
              if (!hasActive) continue;
            }

            writer.writeOpeningTagBeginning("noteRow");
            if (track instanceof KitTrackModel) {
              writer.writeAttribute("drumIndex", r, false);
            } else {
              writer.writeAttribute("y", Math.max(yNote, 0), false);
            }

            List<StepData> row = new ArrayList<>();
            for (int s = 0; s < clip.getStepCount(); s++) {
              row.add(clip.getStep(r, s));
            }
            String hexData = DelugeNoteDataMapper.encodeRow(row, stepTicks);
            String hexDataSplit = DelugeNoteDataMapper.encodeRowSplit(row, stepTicks);
            writer.writeAttribute("noteDataWithLift", hexData, false);
            writer.writeAttribute("noteDataWithSplitProb", hexDataSplit, false);
            writer.closeTag();
          }
          writer.writeArrayEnding("noteRows");
        }

        writer.writeClosingTag("instrumentClip");
      }
      trackIndex++;
    }
    writer.writeArrayEnding("sessionClips");

    writer.writeClosingTag("song", false);
  }

  private static int getGlobalClipIndex(ProjectModel model, ClipModel targetClip) {
    int index = 0;
    for (TrackModel track : model.getTracks()) {
      for (ClipModel clip : track.getClips()) {
        if (clip == targetClip) {
          return index;
        }
        index++;
      }
    }
    return -1;
  }

  // ── Helper methods ──

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
