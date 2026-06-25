package org.deluge.project;

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
public class ProjectSerializer {

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
    writer.writeAttribute("key", model.getKey(), false);
    writer.writeAttribute("scale", model.getScale(), false);

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
    int numClips = 0;
    for (TrackModel track : model.getTracks()) {
      if (track instanceof AudioTrackModel) {
        numClips += ((AudioTrackModel) track).getAudioClips().size();
      } else {
        numClips += track.getClips().size();
      }
    }
    int yScrollSongView = Math.max(-7, numClips - 8);

    writer.writeAttribute("yScrollArrangementView", -7, false);
    writer.writeAttribute("yScrollSongView", yScrollSongView, false);
    writer.writeOpeningTagEnd();

    // ── modeNotes (scale degrees) ──
    boolean[] modeNotes = model.getModeNotes();
    if (modeNotes != null && modeNotes.length > 0) {
      writer.writeArrayStart("modeNotes");
      for (int i = 0; i < modeNotes.length; i++) {
        if (modeNotes[i]) {
          writer.writeTag("modeNote", String.valueOf(i));
        }
      }
      writer.writeArrayEnding("modeNotes");
    }

    // ── microtuning block ──
    serializeMicrotuning(writer, model);

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
          writer.writeAttribute("name", sound.getName(), false);
          String polyVal =
              switch (sound.getPolyphony()) {
                case MONO -> "0";
                case LEGATO -> "legato";
                case CHOKE -> "choke";
                case AUTO -> "auto";
                default -> "1";
              };
          writer.writeAttribute("polyphonic", polyVal, false);
          writer.writeAttribute("voicePriority", sound.getVoicePriority(), false);
          if (sound.getSidechainSend() > 0.0f) {
            writer.writeAttribute(
                "sideChainSend", (int) (sound.getSidechainSend() * Integer.MAX_VALUE), false);
          }
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
        writer.writeArrayEnding("soundSources");
        writer.writeClosingTag("kit");

      } else if (track instanceof AudioTrackModel) {
        AudioTrackModel audio = (AudioTrackModel) track;
        writer.writeOpeningTagBeginning("audioTrack");
        writer.writeAttribute("name", audio.getName(), false);
        writer.writeAttribute("isArmedForRecording", "0", false);
        writer.writeAttribute("lpfMode", "12dB", false);
        writer.writeAttribute("modFXType", "chorus", false);
        writer.writeAttribute("activeModFunction", "0", false);
        if (ciBuilder.length() > 2) {
          writer.writeAttribute("clipInstances", ciBuilder.toString(), false);
        }
        writer.writeOpeningTagEnd();

        // Write delay
        writer.writeOpeningTagBeginning("delay");
        writer.writeAttribute("pingPong", "0", false);
        writer.writeAttribute("analog", "0", false);
        writer.writeAttribute("syncLevel", "0", false);
        writer.writeAttribute("syncType", "0", false);
        writer.closeTag();

        // Write compressor
        writer.writeOpeningTagBeginning("compressor");
        writer.writeAttribute("attack", "0", false);
        writer.writeAttribute("release", "0", false);
        writer.writeAttribute("thresh", "0", false);
        writer.writeAttribute("ratio", "0", false);
        writer.writeAttribute("compHPF", "0", false);
        writer.writeAttribute("compBlend", "2147483647", false);
        writer.closeTag();

        writer.writeClosingTag("audioTrack");

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
        writer.writeAttribute("cents", String.valueOf(synth.getOsc1Cents()), false);
        writer.writeAttribute("retrigPhase", String.valueOf(synth.getOsc1RetrigPhase()), false);
        if (synth.getOsc1SamplePath() != null && !synth.getOsc1SamplePath().isEmpty()) {
          writer.writeAttribute("fileName", synth.getOsc1SamplePath(), false);
        }
        if (synth.getDx7Patch() != null) {
          writer.writeAttribute("dx7patch", synth.getDx7Patch(), false);
        }
        writer.closeTag();

        // osc2
        writer.writeOpeningTagBeginning("osc2");
        writer.writeAttribute("type", synth.getOsc2Type().toLowerCase(), false);
        writer.writeAttribute("transpose", String.valueOf(synth.getOsc2Transpose()), false);
        writer.writeAttribute("cents", String.valueOf(synth.getOsc2Cents()), false);
        writer.writeAttribute("retrigPhase", String.valueOf(synth.getOsc2RetrigPhase()), false);
        if (synth.getOsc2SamplePath() != null && !synth.getOsc2SamplePath().isEmpty()) {
          writer.writeAttribute("fileName", synth.getOsc2SamplePath(), false);
        }
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
      if (track instanceof AudioTrackModel) {
        AudioTrackModel audioTrack = (AudioTrackModel) track;
        for (AudioTrackModel.AudioClip clip : audioTrack.getAudioClips()) {
          serializeAudioClip(writer, clip, null);
        }
      } else {
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
          if (clip.isTripletMode()) {
            writer.writeAttribute("triplet", "1", false);
          }
          if (clip.getPlayDirection() != null
              && clip.getPlayDirection() != ClipModel.PlayDirection.FORWARD) {
            writer.writeAttribute(
                "sequenceDirection", clip.getPlayDirection().name().toLowerCase(), false);
          }
          if (ciBuilder.length() > 2) {
            writer.writeAttribute("clipInstances", ciBuilder.toString(), false);
          }
          writer.writeOpeningTagEnd();

          // Write soundParams / kitParams tag to set outputTypeWhileLoading and serialize
          // track-level performance settings
          if (track instanceof KitTrackModel) {
            serializeKitParams(writer, (KitTrackModel) track);
          } else if (track instanceof SynthTrackModel) {
            serializeSoundParams(writer, (SynthTrackModel) track);
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
      }
      trackIndex++;
    }
    writer.writeArrayEnding("sessionClips");

    // ── sections (arranger timeline) ──
    List<SongSection> sections = model.getSongSections();
    if (sections != null && !sections.isEmpty()) {
      writer.writeArrayStart("sections");
      int seen = 0;
      for (SongSection sec : sections) {
        int sectionId = seen;
        try {
          String numeric = sec.getId().replaceAll("\\D+", "");
          if (!numeric.isEmpty()) {
            sectionId = Integer.parseInt(numeric);
          }
        } catch (Exception e) {
          // fallback
        }
        writer.writeOpeningTagBeginning("section");
        writer.writeAttribute("id", sectionId, false);
        writer.writeAttribute("numRepeats", sec.getNumRepeats(), false);
        writer.closeTag();
        seen++;
      }
      writer.writeArrayEnding("sections");
    }

    serializeGlobalEffects(writer, model);

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

  private static void serializeGlobalEffects(XMLSerializer writer, ProjectModel model)
      throws IOException {
    // 1. Reverb
    writer.writeOpeningTagBeginning("reverb");
    writer.writeAttribute("roomSize", (int) (model.getReverbRoomSize() * Integer.MAX_VALUE), false);
    writer.writeAttribute(
        "dampening", (int) (model.getReverbDampening() * Integer.MAX_VALUE), false);
    writer.writeAttribute("width", (int) (model.getReverbWidth() * Integer.MAX_VALUE), false);
    writer.writeAttribute("hpf", (int) (model.getReverbHpf() * Integer.MAX_VALUE), false);
    writer.writeAttribute("pan", (int) (model.getReverbPan() * Integer.MAX_VALUE), false);
    writer.writeAttribute("model", model.getReverbModel(), false);
    writer.writeOpeningTagEnd();

    writer.writeOpeningTagBeginning("compressor");
    writer.writeAttribute(
        "attack", (int) (model.getReverbCompressorAttack() * Integer.MAX_VALUE), false);
    writer.writeAttribute(
        "release", (int) (model.getReverbCompressorRelease() * Integer.MAX_VALUE), false);
    writer.writeAttribute("syncLevel", model.getReverbCompressorSyncLevel(), false);
    writer.writeAttribute("compHPF", (int) (model.getReverbCompHpf() * Integer.MAX_VALUE), false);
    writer.writeAttribute(
        "compBlend", (int) (model.getReverbCompBlend() * Integer.MAX_VALUE), false);
    writer.closeTag();

    writer.writeClosingTag("reverb");

    // 2. Delay
    writer.writeOpeningTagBeginning("delay");
    writer.writeAttribute("pingPong", model.getDelayPingPong(), false);
    writer.writeAttribute("analog", model.getDelayAnalog(), false);
    writer.writeAttribute("syncLevel", model.getDelaySyncLevel(), false);
    writer.writeAttribute("syncType", model.getDelaySyncType(), false);
    writer.closeTag();

    // 3. Sidechain
    writer.writeOpeningTagBeginning("sidechain");
    writer.writeAttribute("attack", (int) (model.getSidechainAttack() * Integer.MAX_VALUE), false);
    writer.writeAttribute(
        "release", (int) (model.getSidechainRelease() * Integer.MAX_VALUE), false);
    writer.writeAttribute("syncLevel", model.getSidechainSyncLevel(), false);
    writer.writeAttribute("syncType", model.getSidechainSyncType(), false);
    writer.closeTag();

    // 4. Audio Compressor
    writer.writeOpeningTagBeginning("audioCompressor");
    writer.writeAttribute("attack", (int) (model.getCompressorAttack() * Integer.MAX_VALUE), false);
    writer.writeAttribute(
        "release", (int) (model.getCompressorRelease() * Integer.MAX_VALUE), false);
    writer.writeAttribute(
        "thresh", (int) (model.getCompressorThreshold() * Integer.MAX_VALUE), false);
    writer.writeAttribute("ratio", (int) (model.getCompressorRatio() * Integer.MAX_VALUE), false);
    writer.writeAttribute("compHPF", 0, false);
    writer.writeAttribute(
        "compBlend", (int) (model.getCompressorBlend() * Integer.MAX_VALUE), false);
    writer.closeTag();

    // 5. Stutter
    writer.writeOpeningTagBeginning("stutter");
    writer.writeAttribute("quantized", 1, false);
    writer.writeAttribute("reverse", 0, false);
    writer.writeAttribute("pingPong", 0, false);
    writer.closeTag();

    // 6. SongParams
    writer.writeOpeningTagBeginning("songParams");
    writer.writeAttribute(
        "reverbAmount",
        DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamReverbAmount()),
        false);
    writer.writeAttribute(
        "volume", DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamVolume()), false);
    writer.writeAttribute("pan", DelugeHexMapper.floatToHex(model.getSongParamPan()), false);
    writer.writeAttribute(
        "sidechainCompressorShape",
        DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamSidechainShape()),
        false);
    writer.writeAttribute(
        "sidechainCompressorVolume",
        DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamSidechainVolume()),
        false);
    writer.writeAttribute(
        "modFXRate",
        DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamModFXRate()),
        false);
    writer.writeAttribute(
        "modFXDepth",
        DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamModFXDepth()),
        false);
    writer.writeAttribute(
        "modFXOffset",
        DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamModFXOffset()),
        false);
    writer.writeAttribute(
        "modFXFeedback",
        DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamModFXFeedback()),
        false);
    writer.writeAttribute(
        "stutterRate",
        DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamStutterRate()),
        false);
    writer.writeAttribute(
        "sampleRateReduction",
        DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamSampleRateReduction()),
        false);
    writer.writeAttribute(
        "bitCrush", DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamBitCrush()), false);
    writer.writeAttribute(
        "compressorThreshold",
        DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamCompressorThreshold()),
        false);
    writer.writeAttribute(
        "lpfMorph", DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamLpfMorph()), false);
    writer.writeAttribute(
        "hpfMorph", DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamHpfMorph()), false);
    writer.writeOpeningTagEnd();

    // delay
    writer.writeOpeningTagBeginning("delay");
    writer.writeAttribute(
        "rate", DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamDelayRate()), false);
    writer.writeAttribute(
        "feedback",
        DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamDelayFeedback()),
        false);
    writer.closeTag();

    // lpf
    writer.writeOpeningTagBeginning("lpf");
    writer.writeAttribute(
        "frequency", DelugeHexMapper.hzToHex(model.getSongParamLpfFrequency()), false);
    writer.writeAttribute(
        "resonance",
        DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamLpfResonance()),
        false);
    writer.closeTag();

    // hpf
    writer.writeOpeningTagBeginning("hpf");
    writer.writeAttribute(
        "frequency", DelugeHexMapper.hzToHex(model.getSongParamHpfFrequency()), false);
    writer.writeAttribute(
        "resonance",
        DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamHpfResonance()),
        false);
    writer.closeTag();

    // eq
    writer.writeOpeningTagBeginning("eq");
    writer.writeAttribute("bass", DelugeHexMapper.floatToHex(model.getSongParamEqBass()), false);
    writer.writeAttribute(
        "treble", DelugeHexMapper.floatToHex(model.getSongParamEqTreble()), false);
    writer.writeAttribute(
        "bassFrequency",
        DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamEqBassFrequency()),
        false);
    writer.writeAttribute(
        "trebleFrequency",
        DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamEqTrebleFrequency()),
        false);
    writer.closeTag();

    writer.writeClosingTag("songParams");
  }

  private static void serializeKitParams(XMLSerializer writer, KitTrackModel kit)
      throws IOException {
    writer.writeOpeningTagBeginning("kitParams");
    writer.writeAttribute(
        "volume", DelugeHexMapper.unipolarFloatToHexUnified(kit.getVolume()), false);
    writer.writeAttribute("pan", DelugeHexMapper.floatToHex(kit.getPan()), false);
    writer.closeTag();
  }

  private static void serializeSoundParams(XMLSerializer writer, SynthTrackModel synth)
      throws IOException {
    writer.writeOpeningTagBeginning("soundParams");
    writer.writeAttribute(
        "volume", DelugeHexMapper.unipolarFloatToHexUnified(synth.getVolume()), false);
    writer.writeAttribute("pan", DelugeHexMapper.floatToHex(synth.getPan()), false);
    writer.writeAttribute("lpfFrequency", DelugeHexMapper.hzToHex(synth.getLpfFreq()), false);
    writer.writeAttribute(
        "lpfResonance", DelugeHexMapper.unipolarFloatToHexUnified(synth.getLpfRes()), false);
    writer.writeAttribute("hpfFrequency", DelugeHexMapper.hzToHex(synth.getHpfFreq()), false);
    writer.writeAttribute(
        "hpfResonance", DelugeHexMapper.unipolarFloatToHexUnified(synth.getHpfRes()), false);
    writer.writeAttribute(
        "reverbAmount", DelugeHexMapper.unipolarFloatToHexUnified(synth.getReverbSend()), false);
    writer.writeAttribute(
        "delayRate", DelugeHexMapper.unipolarFloatToHexUnified(synth.getDelaySend()), false);

    // Write any raw patched parameters (like sidechain shape)
    for (var entry : synth.getRawParamKnobs().entrySet()) {
      String attrName = null;
      if (entry.getKey() == org.deluge.firmware2.Param.UNPATCHED_SIDECHAIN_SHAPE) {
        attrName = "sidechainCompressorShape";
      } else if (entry.getKey() == 100) { // UNPATCHED_SIDECHAIN_VOLUME
        attrName = "sidechainCompressorVolume";
      }

      if (attrName != null) {
        writer.writeAttribute(attrName, String.format("0x%08X", entry.getValue()), false);
      }
    }
    writer.closeTag();
  }

  private static boolean hasMicrotuning(ProjectModel model) {
    if (model.getOctaveNumMicrotonalNotes() != 12) return true;
    if (!model.isEqualTemperament()) return true;
    if (Math.abs(model.getBaseFrequencyHz() - 440.0) > 0.001) return true;
    for (int cent : model.getCentAdjustForNotesInTemperament()) {
      if (cent != 0) return true;
    }
    for (double ratio : model.getCustomRatios()) {
      if (ratio != 0.0) return true;
    }
    return false;
  }

  private static void serializeMicrotuning(XMLSerializer writer, ProjectModel model)
      throws IOException {
    if (!hasMicrotuning(model)) return;

    writer.writeOpeningTagBeginning("microtuning");
    writer.writeAttribute("notes", model.getOctaveNumMicrotonalNotes(), false);
    writer.writeAttribute("isEqual", model.isEqualTemperament() ? "true" : "false", false);
    writer.writeAttribute(
        "baseFrequency",
        String.format(java.util.Locale.US, "%.1f", model.getBaseFrequencyHz()),
        false);
    writer.writeOpeningTagEnd();

    // Write cents
    StringBuilder centsSb = new StringBuilder();
    int[] centAdjust = model.getCentAdjustForNotesInTemperament();
    for (int i = 0; i < model.getOctaveNumMicrotonalNotes(); i++) {
      if (i > 0) centsSb.append(",");
      centsSb.append(centAdjust[i]);
    }
    writer.writeTag("cents", centsSb.toString());

    // Write ratios if not equal temperament
    if (!model.isEqualTemperament()) {
      StringBuilder ratiosSb = new StringBuilder();
      double[] customRatios = model.getCustomRatios();
      for (int i = 0; i < model.getOctaveNumMicrotonalNotes(); i++) {
        if (i > 0) ratiosSb.append(",");
        ratiosSb.append(String.format(java.util.Locale.US, "%f", customRatios[i]));
      }
      writer.writeTag("ratios", ratiosSb.toString());
    }

    writer.writeClosingTag("microtuning");
  }

  private static void serializeAudioClip(
      XMLSerializer writer, AudioTrackModel.AudioClip clip, String ci) throws IOException {
    writer.writeOpeningTagBeginning("audioClip");
    writer.writeAttribute("trackName", clip.getTrackName(), false);
    if (clip.getFilePath() != null) {
      writer.writeAttribute("filePath", clip.getFilePath(), false);
    }
    writer.writeAttribute("startSamplePos", clip.getStartSamplePos(), false);
    writer.writeAttribute("endSamplePos", clip.getEndSamplePos(), false);
    writer.writeAttribute("attack", DelugeHexMapper.floatToHex(clip.getAttack()), false);
    writer.writeAttribute("priority", clip.getPriority(), false);
    writer.writeAttribute(
        "pitchSpeedIndependent", clip.isPitchSpeedIndependent() ? "true" : "false", false);
    writer.writeAttribute(
        "overdubsShouldCloneAudioTrack",
        clip.isOverdubsShouldCloneAudioTrack() ? "true" : "false",
        false);
    writer.writeAttribute("isPlaying", clip.isPlaying() ? "1" : "0", false);
    writer.writeAttribute("isSoloing", clip.isSoloing() ? "1" : "0", false);
    writer.writeAttribute("isArmedForRecording", clip.isArmedForRecording() ? "1" : "0", false);
    writer.writeAttribute("length", clip.getLength(), false);
    writer.writeAttribute("colourOffset", clip.getColourOffset(), false);
    writer.writeAttribute("section", clip.getSection(), false);
    writer.writeAttribute("beingEdited", clip.isBeingEdited() ? "1" : "0", false);
    if (ci != null) {
      writer.writeAttribute("clipInstances", ci, false);
    }
    writer.writeOpeningTagEnd();

    // Write params child
    writer.writeOpeningTagBeginning("params");
    writer.writeAttribute(
        "volume", DelugeHexMapper.unipolarFloatToHexUnified(clip.getVolume()), false);
    writer.writeAttribute("pan", DelugeHexMapper.floatToHex(clip.getPan()), false);
    writer.writeAttribute(
        "reverbAmount", DelugeHexMapper.unipolarFloatToHexUnified(clip.getReverbAmount()), false);
    writer.writeAttribute(
        "sidechainCompressorShape",
        DelugeHexMapper.unipolarFloatToHexUnified(clip.getSidechainShape()),
        false);
    writer.writeAttribute(
        "sidechainCompressorVolume",
        DelugeHexMapper.unipolarFloatToHexUnified(clip.getSidechainVolume()),
        false);
    writer.writeAttribute(
        "modFXRate", DelugeHexMapper.unipolarFloatToHexUnified(clip.getModFXRate()), false);
    writer.writeAttribute(
        "modFXDepth", DelugeHexMapper.unipolarFloatToHexUnified(clip.getModFXDepth()), false);
    writer.writeAttribute(
        "modFXOffset", DelugeHexMapper.unipolarFloatToHexUnified(clip.getModFXOffset()), false);
    writer.writeAttribute(
        "modFXFeedback", DelugeHexMapper.unipolarFloatToHexUnified(clip.getModFXFeedback()), false);
    writer.writeAttribute(
        "stutterRate", DelugeHexMapper.unipolarFloatToHexUnified(clip.getStutterRate()), false);
    writer.writeAttribute(
        "sampleRateReduction",
        DelugeHexMapper.unipolarFloatToHexUnified(clip.getSampleRateReduction()),
        false);
    writer.writeAttribute(
        "bitCrush", DelugeHexMapper.unipolarFloatToHexUnified(clip.getBitCrush()), false);
    writer.writeAttribute(
        "delayRate", DelugeHexMapper.unipolarFloatToHexUnified(clip.getDelayRate()), false);
    writer.writeAttribute(
        "delayFeedback", DelugeHexMapper.unipolarFloatToHexUnified(clip.getDelayFeedback()), false);
    writer.writeAttribute("lpfFrequency", DelugeHexMapper.hzToHex(clip.getLpfFrequency()), false);
    writer.writeAttribute(
        "lpfResonance", DelugeHexMapper.unipolarFloatToHexUnified(clip.getLpfResonance()), false);
    writer.writeAttribute("hpfFrequency", DelugeHexMapper.hzToHex(clip.getHpfFrequency()), false);
    writer.writeAttribute(
        "hpfResonance", DelugeHexMapper.unipolarFloatToHexUnified(clip.getHpfResonance()), false);
    writer.writeAttribute("eqBass", DelugeHexMapper.floatToHex(clip.getEqBass()), false);
    writer.writeAttribute("eqTreble", DelugeHexMapper.floatToHex(clip.getEqTreble()), false);
    writer.writeAttribute(
        "eqBassFrequency",
        DelugeHexMapper.unipolarFloatToHexUnified(clip.getEqBassFrequency()),
        false);
    writer.writeAttribute(
        "eqTrebleFrequency",
        DelugeHexMapper.unipolarFloatToHexUnified(clip.getEqTrebleFrequency()),
        false);
    writer.closeTag();

    writer.writeClosingTag("audioClip");
  }
}
