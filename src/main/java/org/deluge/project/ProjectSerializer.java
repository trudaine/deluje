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
import org.deluge.xml.DelugeXmlUtil;

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
    writer.writeAttribute("affectEntire", model.isAffectEntire() ? "1" : "0", false);
    writer.writeAttribute("earliestCompatibleFirmware", "4.1.0-alpha", false);
    writer.writeAttribute("firmwareVersion", "c1.3.0", false);
    writer.writeAttribute("inputTickMagnitude", model.getInputTickMagnitude(), false);
    writer.writeAttribute("previewNumPads", 144, false);
    writer.writeAttribute("swing", DelugeHexMapper.floatToHex(model.getSwing()), false);
    writer.writeAttribute("swingAmount", model.getSwingAmount(), false);
    int fileSwingInterval =
        DelugeXmlUtil.convertSyncLevelFromInternalValueToFileValue(
            model.getSwingInterval(), model.getInputTickMagnitude());
    writer.writeAttribute("swingInterval", fileSwingInterval, false);
    writer.writeAttribute("tempo", String.valueOf(model.getBpm()), false);
    writer.writeAttribute("key", model.getKey(), false);
    writer.writeAttribute("scale", model.getScale(), false);

    // Calculate timePerTimerTick and timerTickFraction dynamically from BPM
    // unless they were parsed as non-defaults.
    int timePerTimerTickAttr = model.getTimePerTimerTick();
    int timerTickFractionAttr = model.getTimerTickFraction();
    if (model.getBpm() != 120.0f
        || (timePerTimerTickAttr == 229 && timerTickFractionAttr == -1342177280)) {
      double bpm = model.getBpm();
      double scaledBpm = bpm * 4.0;
      double timePerTimerTick = 110250.0 / scaledBpm;
      long timePerTimerTickBig = (long) (timePerTimerTick * 4294967296.0);
      timePerTimerTickAttr = (int) (timePerTimerTickBig >> 32);
      timerTickFractionAttr = (int) timePerTimerTickBig;
    }

    writer.writeAttribute("timePerTimerTick", timePerTimerTickAttr, false);
    writer.writeAttribute("timerTickFraction", timerTickFractionAttr, false);

    writer.writeAttribute("xScroll", model.getXScroll(), false);
    writer.writeAttribute("xZoom", model.getXZoom(), false);
    writer.writeAttribute("yScrollSongView", model.getYScrollSongView(), false);
    writer.writeAttribute("yScrollArrangementView", model.getYScrollArrangementView(), false);
    writer.writeAttribute("xScrollArrangementView", model.getXScrollArrangementView(), false);
    writer.writeAttribute("xZoomArrangementView", model.getXZoomArrangementView(), false);
    writer.writeAttribute("yScroll", model.getYScroll(), false);
    writer.writeAttribute("yScrollKeyboard", model.getYScrollKeyboard(), false);
    writer.writeAttribute(
        "arrangementAutoScrollOn", model.isArrangementAutoScrollOn() ? "1" : "0", false);
    writer.writeAttribute("rootNote", model.getKey(), false);
    if (model.isBootInArrangementView()) {
      writer.writeAttribute("inArrangementView", "1", false);
    }
    writer.writeAttribute("sessionLayout", model.getSessionLayout(), false);
    writer.writeAttribute("songGridScrollX", model.getSongGridScrollX(), false);
    writer.writeAttribute("songGridScrollY", model.getSongGridScrollY(), false);
    writer.writeAttribute("keyboardLayout", model.getKeyboardLayout(), false);
    writer.writeAttribute("keyboardRowInterval", model.getKeyboardRowInterval(), false);
    writer.writeAttribute("inKeyMode", model.isInKeyMode() ? "1" : "0", false);
    writer.writeAttribute("inKeyRowInterval", model.getInKeyRowInterval(), false);
    writer.writeAttribute("inKeyScrollOffset", model.getInKeyScrollOffset(), false);
    writer.writeAttribute("drumsScrollOffset", model.getDrumsScrollOffset(), false);
    writer.writeAttribute("drumsZoomLevel", model.getDrumsZoomLevel(), false);
    writer.writeAttribute("drumsEdgeSize", model.getDrumsEdgeSize(), false);
    writer.writeAttribute("anyOfMelodicKitPercussion", model.getAnyOfMelodicKitPercussion(), false);
    writer.writeAttribute("numClips", model.getNumClips(), false);
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
          ciBuilder.append(
              String.format(
                  "%08X%08X%08X", ac.startTicks(), ac.durationTicks(), clipCodeFor(model, ac)));
        }
      }

      if (track instanceof KitTrackModel) {
        writer.writeOpeningTagBeginning("kit");
        writer.writeAttribute("presetName", track.getName(), false);
        writer.writeAttribute("presetFolder", "KITS", false);
        if (track.isMutedInArrangement()) {
          writer.writeAttribute("isMutedInArrangement", "1", false);
        }
        if (track.isSoloingInArrangement()) {
          writer.writeAttribute("isSoloingInArrangement", "1", false);
        }
        if (track.getDefaultVelocity() != 64) {
          writer.writeAttribute("defaultVelocity", track.getDefaultVelocity(), false);
        }
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

          serializeMidiKnobs(writer, sound.getMidiKnobs());

          if (sound.getMidiChannel() != 255 || sound.getNoteForDrum() != 255) {
            writer.writeOpeningTagBeginning("midiOutput");
            writer.writeAttribute("channel", sound.getMidiChannel(), false);
            writer.writeAttribute("noteForDrum", sound.getNoteForDrum(), false);
            writer.closeTag();
          }

          writer.writeClosingTag("sound");
        }
        writer.writeArrayEnding("soundSources");
        writer.writeTag(
            "selectedDrumIndex", String.valueOf(((KitTrackModel) track).getSelectedDrumIndex()));
        writer.writeClosingTag("kit");

      } else if (track instanceof AudioTrackModel) {
        AudioTrackModel audio = (AudioTrackModel) track;
        writer.writeOpeningTagBeginning("audioTrack");
        writer.writeAttribute("name", audio.getName(), false);
        writer.writeAttribute("isArmedForRecording", "0", false);
        writer.writeAttribute("lpfMode", "12dB", false);
        writer.writeAttribute("modFXType", "chorus", false);
        writer.writeAttribute("activeModFunction", "0", false);
        if (audio.isMutedInArrangement()) {
          writer.writeAttribute("isMutedInArrangement", "1", false);
        }
        if (audio.isSoloingInArrangement()) {
          writer.writeAttribute("isSoloingInArrangement", "1", false);
        }
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

      } else if (track instanceof MidiTrackModel) {
        MidiTrackModel midi = (MidiTrackModel) track;
        writer.writeOpeningTagBeginning("sound");
        writer.writeAttribute("presetName", midi.getName(), false);
        writer.writeAttribute("presetFolder", "SYNTHS", false);
        if (midi.isMutedInArrangement()) {
          writer.writeAttribute("isMutedInArrangement", "1", false);
        }
        if (midi.isSoloingInArrangement()) {
          writer.writeAttribute("isSoloingInArrangement", "1", false);
        }
        if (midi.getDefaultVelocity() != 64) {
          writer.writeAttribute("defaultVelocity", midi.getDefaultVelocity(), false);
        }
        if (ciBuilder.length() > 2) {
          writer.writeAttribute("clipInstances", ciBuilder.toString(), false);
        }
        writer.writeOpeningTagEnd();

        if (midi.isMpe()) {
          writer.writeTag("zone", midi.getMpeZone());
        } else {
          writer.writeTag("midiChannel", String.valueOf(midi.getMidiChannel()));
        }

        if (midi.getDeviceName() != null && !midi.getDeviceName().isEmpty()) {
          writer.writeOpeningTagBeginning("midiDevice");
          writer.writeOpeningTagEnd();
          writer.writeTag("name", midi.getDeviceName());
          if (midi.getDeviceDefinitionFile() != null && !midi.getDeviceDefinitionFile().isEmpty()) {
            writer.writeTag("definitionFile", midi.getDeviceDefinitionFile());
          }
          writer.writeClosingTag("midiDevice");
        }

        boolean hasLabels = false;
        for (int i = 0; i < 120; i++) {
          String fallback = "CC " + i;
          if (i == 1) fallback = "Mod Wheel";
          else if (i == 7) fallback = "Volume";
          else if (i == 10) fallback = "Pan";
          else if (i == 64) fallback = "Sustain";

          if (!fallback.equals(midi.getCcLabel(i))) {
            hasLabels = true;
            break;
          }
        }
        if (hasLabels) {
          writer.writeOpeningTagBeginning("ccLabels");
          writer.writeOpeningTagEnd();
          for (int i = 0; i < 120; i++) {
            String fallback = "CC " + i;
            if (i == 1) fallback = "Mod Wheel";
            else if (i == 7) fallback = "Volume";
            else if (i == 10) fallback = "Pan";
            else if (i == 64) fallback = "Sustain";

            if (!fallback.equals(midi.getCcLabel(i))) {
              writer.writeTag("cc" + i, midi.getCcLabel(i));
            }
          }
          writer.writeClosingTag("ccLabels");
        }
        writer.writeClosingTag("sound");

      } else if (track instanceof SynthTrackModel) {
        SynthTrackModel synth = (SynthTrackModel) track;
        writer.writeOpeningTagBeginning("sound");
        writer.writeAttribute("presetName", synth.getName(), false);
        writer.writeAttribute("presetFolder", "SYNTHS", false);
        if (synth.isMutedInArrangement()) {
          writer.writeAttribute("isMutedInArrangement", "1", false);
        }
        if (synth.isSoloingInArrangement()) {
          writer.writeAttribute("isSoloingInArrangement", "1", false);
        }
        if (synth.getDefaultVelocity() != 64) {
          writer.writeAttribute("defaultVelocity", synth.getDefaultVelocity(), false);
        }
        if (ciBuilder.length() > 2) {
          writer.writeAttribute("clipInstances", ciBuilder.toString(), false);
        }
        writer.writeOpeningTagEnd();

        // osc1 — prefer the VERBATIM captured multisample XML (correct
        // <sampleRange rangeTopNote><zone/></sampleRange> structure the hardware needs); the
        // dynamic-keyzone path below omits the <sampleRange rangeTopNote> wrappers, so the Deluge
        // can't map keyzones → silent multisamples. Fall back to dynamic zones / single-sample only
        // when no raw XML was captured.
        if (synth.getOsc1RawXml() != null) {
          writer.write("\n");
          writer.write(synth.getOsc1RawXml());
        } else if (!synth.getKeyZones().getOsc1Zones().isEmpty()) {
          writer.writeOpeningTagBeginning("osc1");
          writer.writeAttribute("type", "sample", false);
          writer.writeAttribute("transpose", String.valueOf(synth.getOsc1Transpose()), false);
          writer.writeAttribute("cents", String.valueOf(synth.getOsc1Cents()), false);
          writer.writeAttribute("retrigPhase", String.valueOf(synth.getOsc1RetrigPhase()), false);
          writer.writeOpeningTagEnd();

          writer.writeOpeningTagBeginning("sampleRanges");
          writer.writeOpeningTagEnd();

          for (org.deluge.model.KeyZone kz : synth.getKeyZones().getOsc1Zones()) {
            writer.writeOpeningTagBeginning("zone");
            if (kz.samplePath != null) {
              writer.writeAttribute("fileName", kz.samplePath, false);
            }
            writer.writeAttribute("minPitch", String.valueOf(kz.minPitch), false);
            writer.writeAttribute("maxPitch", String.valueOf(kz.maxPitch), false);
            writer.writeAttribute("minVelocity", String.valueOf(kz.minVelocity), false);
            writer.writeAttribute("maxVelocity", String.valueOf(kz.maxVelocity), false);
            writer.writeAttribute("startSamplePos", String.valueOf(kz.startSamplePos), false);
            writer.writeAttribute("endSamplePos", String.valueOf(kz.endSamplePos), false);
            writer.writeAttribute("startLoopPos", String.valueOf(kz.startLoopPos), false);
            writer.writeAttribute("endLoopPos", String.valueOf(kz.endLoopPos), false);
            writer.writeAttribute("loopMode", kz.looping ? "1" : "0", false);
            writer.closeTag();
          }

          writer.writeClosingTag("sampleRanges");
          writer.writeClosingTag("osc1");
        } else {
          writer.writeOpeningTagBeginning("osc1");
          writer.writeAttribute("type", synth.getOsc1Type().toLowerCase(), false);
          writer.writeAttribute("transpose", String.valueOf(synth.getOsc1Transpose()), false);
          writer.writeAttribute("cents", String.valueOf(synth.getOsc1Cents()), false);
          writer.writeAttribute("retrigPhase", String.valueOf(synth.getOsc1RetrigPhase()), false);
          if (synth.getOsc1SamplePath() != null && !synth.getOsc1SamplePath().isEmpty()) {
            writer.writeAttribute("fileName", synth.getOsc1SamplePath(), false);
          }
          if (synth.getDx7Patch() != null) {
            writer.writeAttribute("dx7patch", synth.getDx7Patch(), false);
          }
          writer.closeTag();
        }

        // osc2 — prefer verbatim multisample XML (see osc1 note); dynamic-zone path drops the
        // <sampleRange rangeTopNote> wrappers → silent keyzones.
        if (synth.getOsc2RawXml() != null) {
          writer.write("\n");
          writer.write(synth.getOsc2RawXml());
        } else if (!synth.getKeyZones().getOsc2Zones().isEmpty()) {
          writer.writeOpeningTagBeginning("osc2");
          writer.writeAttribute("type", "sample", false);
          writer.writeAttribute("transpose", String.valueOf(synth.getOsc2Transpose()), false);
          writer.writeAttribute("cents", String.valueOf(synth.getOsc2Cents()), false);
          writer.writeAttribute("retrigPhase", String.valueOf(synth.getOsc2RetrigPhase()), false);
          writer.writeOpeningTagEnd();

          writer.writeOpeningTagBeginning("sampleRanges");
          writer.writeOpeningTagEnd();

          for (org.deluge.model.KeyZone kz : synth.getKeyZones().getOsc2Zones()) {
            writer.writeOpeningTagBeginning("zone");
            if (kz.samplePath != null) {
              writer.writeAttribute("fileName", kz.samplePath, false);
            }
            writer.writeAttribute("minPitch", String.valueOf(kz.minPitch), false);
            writer.writeAttribute("maxPitch", String.valueOf(kz.maxPitch), false);
            writer.writeAttribute("minVelocity", String.valueOf(kz.minVelocity), false);
            writer.writeAttribute("maxVelocity", String.valueOf(kz.maxVelocity), false);
            writer.writeAttribute("startSamplePos", String.valueOf(kz.startSamplePos), false);
            writer.writeAttribute("endSamplePos", String.valueOf(kz.endSamplePos), false);
            writer.writeAttribute("startLoopPos", String.valueOf(kz.startLoopPos), false);
            writer.writeAttribute("endLoopPos", String.valueOf(kz.endLoopPos), false);
            writer.writeAttribute("loopMode", kz.looping ? "1" : "0", false);
            writer.closeTag();
          }

          writer.writeClosingTag("sampleRanges");
          writer.writeClosingTag("osc2");
        } else {
          writer.writeOpeningTagBeginning("osc2");
          writer.writeAttribute("type", synth.getOsc2Type().toLowerCase(), false);
          writer.writeAttribute("transpose", String.valueOf(synth.getOsc2Transpose()), false);
          writer.writeAttribute("cents", String.valueOf(synth.getOsc2Cents()), false);
          writer.writeAttribute("retrigPhase", String.valueOf(synth.getOsc2RetrigPhase()), false);
          if (synth.getOsc2SamplePath() != null && !synth.getOsc2SamplePath().isEmpty()) {
            writer.writeAttribute("fileName", synth.getOsc2SamplePath(), false);
          }
          writer.closeTag();
        }

        String polyVal =
            switch (synth.getPolyphony()) {
              case MONO -> "0";
              case LEGATO -> "legato";
              default -> "1";
            };
        writer.writeTag("polyphonic", polyVal);
        // Was hardcoded "0"/"1" — dropped the preset's saturation/distortion (clippingAmount is
        // parsed + applied to the engine) and voice priority. Write the real model values.
        writer.writeTag("clippingAmount", String.valueOf(synth.getClippingAmount()));
        writer.writeTag("voicePriority", String.valueOf(synth.getVoicePriority()));

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
        // Sound-level master transpose — was hardcoded "0" (lost the preset's transpose).
        writer.writeTag("transpose", String.valueOf(synth.getTranspose()));

        // modulator1
        writer.writeOpeningTagBeginning("modulator1");
        writer.writeAttribute("transpose", String.valueOf(synth.getModulator1Transpose()), false);
        writer.writeAttribute("cents", String.valueOf(synth.getModulator1Cents()), false);
        writer.writeAttribute("retrigPhase", String.valueOf(synth.getMod1RetrigPhase()), false);
        writer.closeTag();

        // modulator2
        writer.writeOpeningTagBeginning("modulator2");
        writer.writeAttribute("transpose", String.valueOf(synth.getModulator2Transpose()), false);
        writer.writeAttribute("cents", String.valueOf(synth.getModulator2Cents()), false);
        writer.writeAttribute("retrigPhase", String.valueOf(synth.getMod2RetrigPhase()), false);
        writer.writeAttribute("toModulator1", synth.isModulator1ToModulator0() ? "1" : "0", false);
        writer.closeTag();

        // unison
        writer.writeOpeningTagBeginning("unison");
        writer.writeOpeningTagEnd();
        writer.writeTag("num", String.valueOf(synth.getUnison().getUnisonNum()));
        writer.writeTag("detune", DelugeHexMapper.floatToHex(synth.getUnison().getUnisonDetune()));
        writer.writeTag(
            "spread", DelugeHexMapper.floatToHex(synth.getUnison().getUnisonStereoSpread()));
        writer.writeClosingTag("unison");

        // arpeggiator
        writer.writeOpeningTagBeginning("arpeggiator");
        writer.writeAttribute("mode", synth.getArp().mode().toLowerCase(), false);
        writer.writeAttribute("active", synth.getArp().active() ? "1" : "0", false);
        writer.writeAttribute("sequenceLength", synth.getArp().seqLength(), false);
        writer.writeAttribute("arpMode", synth.getArp().active() ? "arp" : "off", false);
        if (synth.getArp().notePattern() != null && !synth.getArp().notePattern().isEmpty()) {
          writer.writeAttribute("notePattern", synth.getArp().notePattern(), false);
        }
        writer.writeAttribute("chordType", synth.getArp().chordType(), false);

        writer.writeAttribute("numOctaves", synth.getArp().numOctaves(), false);
        writer.writeAttribute("kitArp", synth.getArp().kitArp(), false);
        writer.writeAttribute("randomizerLock", synth.getArp().randomizerLock(), false);

        writer.writeAttribute("lastLockedNoteProb", synth.getArp().lastLockedNoteProb(), false);
        if (synth.getArp().lockedNoteProbArray() != null
            && !synth.getArp().lockedNoteProbArray().isEmpty()) {
          writer.writeAttribute("lockedNoteProbArray", synth.getArp().lockedNoteProbArray(), false);
        }

        writer.writeAttribute("lastLockedBassProb", synth.getArp().lastLockedBassProb(), false);
        if (synth.getArp().lockedBassProbArray() != null
            && !synth.getArp().lockedBassProbArray().isEmpty()) {
          writer.writeAttribute("lockedBassProbArray", synth.getArp().lockedBassProbArray(), false);
        }

        writer.writeAttribute("lastLockedSwapProb", synth.getArp().lastLockedSwapProb(), false);
        if (synth.getArp().lockedSwapProbArray() != null
            && !synth.getArp().lockedSwapProbArray().isEmpty()) {
          writer.writeAttribute("lockedSwapProbArray", synth.getArp().lockedSwapProbArray(), false);
        }

        writer.writeAttribute("lastLockedGlideProb", synth.getArp().lastLockedGlideProb(), false);
        if (synth.getArp().lockedGlideProbArray() != null
            && !synth.getArp().lockedGlideProbArray().isEmpty()) {
          writer.writeAttribute(
              "lockedGlideProbArray", synth.getArp().lockedGlideProbArray(), false);
        }

        writer.writeAttribute(
            "lastLockedReverseProb", synth.getArp().lastLockedReverseProb(), false);
        if (synth.getArp().lockedReverseProbArray() != null
            && !synth.getArp().lockedReverseProbArray().isEmpty()) {
          writer.writeAttribute(
              "lockedReverseProbArray", synth.getArp().lockedReverseProbArray(), false);
        }

        writer.writeAttribute("lastLockedChordProb", synth.getArp().lastLockedChordProb(), false);
        if (synth.getArp().lockedChordProbArray() != null
            && !synth.getArp().lockedChordProbArray().isEmpty()) {
          writer.writeAttribute(
              "lockedChordProbArray", synth.getArp().lockedChordProbArray(), false);
        }

        writer.writeAttribute(
            "lastLockedRatchetProb", synth.getArp().lastLockedRatchetProb(), false);
        if (synth.getArp().lockedRatchetProbArray() != null
            && !synth.getArp().lockedRatchetProbArray().isEmpty()) {
          writer.writeAttribute(
              "lockedRatchetProbArray", synth.getArp().lockedRatchetProbArray(), false);
        }

        writer.writeAttribute(
            "lastLockedVelocitySpread", synth.getArp().lastLockedVelocitySpread(), false);
        if (synth.getArp().lockedVelocitySpreadArray() != null
            && !synth.getArp().lockedVelocitySpreadArray().isEmpty()) {
          writer.writeAttribute(
              "lockedVelocitySpreadArray", synth.getArp().lockedVelocitySpreadArray(), false);
        }

        writer.writeAttribute("lastLockedGateSpread", synth.getArp().lastLockedGateSpread(), false);
        if (synth.getArp().lockedGateSpreadArray() != null
            && !synth.getArp().lockedGateSpreadArray().isEmpty()) {
          writer.writeAttribute(
              "lockedGateSpreadArray", synth.getArp().lockedGateSpreadArray(), false);
        }

        writer.writeAttribute(
            "lastLockedOctaveSpread", synth.getArp().lastLockedOctaveSpread(), false);
        if (synth.getArp().lockedOctaveSpreadArray() != null
            && !synth.getArp().lockedOctaveSpreadArray().isEmpty()) {
          writer.writeAttribute(
              "lockedOctaveSpreadArray", synth.getArp().lockedOctaveSpreadArray(), false);
        }
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
        writeHexTagUnipolar(writer, "compressorShape", synth.getCompressorShape());
        writeHexTagUnipolar(writer, "oscAVolume", synth.getOscMix());
        if (synth.getOsc1PhaseWidthQ31() != Integer.MIN_VALUE) {
          writeRawQ31Tag(writer, "oscAPulseWidth", synth.getOsc1PhaseWidthQ31());
        } else {
          writeHexTagUnipolar(writer, "oscAPulseWidth", 0f);
        }
        writeHexTagUnipolar(writer, "oscBVolume", 1.0f - synth.getOscMix());
        if (synth.getOsc2PhaseWidthQ31() != Integer.MIN_VALUE) {
          writeRawQ31Tag(writer, "oscBPulseWidth", synth.getOsc2PhaseWidthQ31());
        } else {
          writeHexTagUnipolar(writer, "oscBPulseWidth", 0f);
        }
        if (synth.getPitchAdjustQ31() != Integer.MIN_VALUE) {
          writeRawQ31Tag(writer, "pitchAdjust", synth.getPitchAdjustQ31());
        }
        if (synth.getOsc1PitchAdjustQ31() != Integer.MIN_VALUE) {
          writeRawQ31Tag(writer, "oscAPitchAdjust", synth.getOsc1PitchAdjustQ31());
        }
        if (synth.getOsc2PitchAdjustQ31() != Integer.MIN_VALUE) {
          writeRawQ31Tag(writer, "oscBPitchAdjust", synth.getOsc2PitchAdjustQ31());
        }
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
        writeHexTagUnipolar(writer, "stutterRate", synth.getStutter().getStutterRate());
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
        if (!synth.getModulation().getPatchCables().isEmpty()) {
          writer.writeArrayStart("patchCables");
          for (PatchCable pc : synth.getModulation().getPatchCables()) {
            writePatchCable(writer, pc);
          }
          writer.writeArrayEnding("patchCables");
        }

        writeHexTag(writer, "modFXOffset", 0f);
        writeHexTag(writer, "modFXFeedback", synth.getModFxFeedback());
        writer.writeClosingTag("defaultParams");

        serializeMidiKnobs(writer, synth.getModulation().getMidiKnobs());

        // modKnobs
        boolean hasKnobs =
            synth.getModulation().getModKnobs().stream().anyMatch(k -> !"NONE".equals(k.param()));
        if (hasKnobs) {
          writer.writeArrayStart("modKnobs");
          for (ModKnob mk : synth.getModulation().getModKnobs()) {
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
          // Arrangement-only clips are written in the <arrangementOnlyTracks> block (C
          // song.cpp:1286-1297), not as session clips.
          if (clip.isArrangementOnly()) {
            continue;
          }
          writeInstrumentClip(writer, track, clip, false);
        }
      }
      trackIndex++;
    }
    writer.writeArrayEnding("sessionClips");

    // ── arrangementOnlyTracks block (C song.cpp:1286-1297) — clips that live only on the
    // arranger timeline, not in the session grid. Round-trips with the parser, which matches each
    // back to its track by the trackName attribute. ──
    boolean anyArrangementOnly = false;
    for (TrackModel track : model.getTracks()) {
      if (track instanceof AudioTrackModel) {
        continue;
      }
      for (ClipModel clip : track.getClips()) {
        if (clip.isArrangementOnly()) {
          anyArrangementOnly = true;
          break;
        }
      }
      if (anyArrangementOnly) {
        break;
      }
    }
    if (anyArrangementOnly) {
      writer.writeArrayStart("arrangementOnlyTracks");
      for (TrackModel track : model.getTracks()) {
        if (track instanceof AudioTrackModel) {
          continue;
        }
        for (ClipModel clip : track.getClips()) {
          if (clip.isArrangementOnly()) {
            writeInstrumentClip(writer, track, clip, true);
          }
        }
      }
      writer.writeArrayEnding("arrangementOnlyTracks");
    }

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

  /**
   * The 32-bit clipCode the hardware stores per clipInstance (C output.cpp:280-285): 0xFFFFFFFF
   * when the instance references no clip; otherwise the clip's save index with bit 31 set when the
   * clip is unassigned to a session section (section == 255).
   */
  private static int clipCodeFor(ProjectModel model, ArrangerClip ac) {
    ClipModel clip = ac.clip();
    int clipIdx = getGlobalClipIndex(model, clip);
    if (clip == null || clipIdx < 0) return 0xFFFFFFFF;
    return (clip.getSection() == 255) ? (clipIdx | (1 << 31)) : clipIdx;
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

  private static void writePatchCable(XMLSerializer writer, PatchCable pc) throws IOException {
    writer.writeOpeningTagBeginning("patchCable");
    writer.writeOpeningTagEnd();
    writer.writeTag("source", pc.source());
    if (pc.destination() != null && !pc.destination().isEmpty()) {
      writer.writeTag("destination", pc.destination());
    }
    writeHexTag(writer, "amount", pc.amount());
    if (pc.polarity() != null) {
      writer.writeTag("polarity", pc.polarity().name().toLowerCase());
    }
    if (pc.depthControlledBy() != null && !pc.depthControlledBy().isEmpty()) {
      writer.writeArrayStart("depthControlledBy");
      for (PatchCable dc : pc.depthControlledBy()) {
        writePatchCable(writer, dc);
      }
      writer.writeArrayEnding("depthControlledBy");
    }
    writer.writeClosingTag("patchCable");
  }

  /** Write a raw signed-Q31 param value as a 0xXXXXXXXX hex tag (exact, lossless round-trip). */
  private static void writeRawQ31Tag(XMLSerializer writer, String tag, int q31) throws IOException {
    writer.writeTag(tag, String.format("0x%08X", q31));
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
    writer.writeAttribute(
        "shape", (int) (model.getReverbCompressorShape() * Integer.MAX_VALUE), false);
    writer.writeAttribute(
        "volume", (int) (model.getReverbCompressorVolume() * Integer.MAX_VALUE), false);
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
    writer.writeAttribute("modFXCurrentParam", model.getModFXCurrentParam(), false);
    writer.writeAttribute("currentFilterType", model.getCurrentFilterType(), false);
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

  /**
   * Writes a single {@code <instrumentClip>} (shared by the sessionClips and arrangementOnlyTracks
   * blocks). When {@code arrangementOnly} a {@code trackName} attribute is added so the parser can
   * match the clip back to its track (DelugeXmlParser arrangement-only path).
   */
  private static void writeInstrumentClip(
      XMLSerializer writer, TrackModel track, ClipModel clip, boolean arrangementOnly)
      throws IOException {
    // NOTE: clipInstances is an Output (instrument) attribute in the C format (output.cpp), NOT a
    // clip attribute — it is written in the <instruments> block. Clips carry only `section`.
    writer.writeOpeningTagBeginning("instrumentClip");
    if (arrangementOnly) {
      writer.writeAttribute("trackName", track.getName(), false);
    }
    if (clip.getName() != null) {
      writer.writeAttribute("clipName", clip.getName(), false);
    }
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
    // C clip.cpp:659 — isPlaying == activeIfNoSolo (session-active). All-active = every clip
    // fires at once in session view; arranger songs keep these inactive.
    writer.writeAttribute("isPlaying", clip.isActiveInSession() ? "1" : "0", false);
    // C clip.cpp:667 — write section only when assigned (!= 255 unassigned).
    if (clip.getSection() != 255) {
      writer.writeAttribute("section", clip.getSection(), false);
    }
    if (clip.isTripletMode()) {
      writer.writeAttribute("triplet", "1", false);
    }
    if (clip.getPlayDirection() != null
        && clip.getPlayDirection() != ClipModel.PlayDirection.FORWARD) {
      writer.writeAttribute(
          "sequenceDirection", clip.getPlayDirection().name().toLowerCase(), false);
    }
    writer.writeOpeningTagEnd();

    // Write soundParams / kitParams tag to set outputTypeWhileLoading and serialize track-level
    // performance settings
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

    writer.writeOpeningTagBeginning("columnControls");
    writer.writeOpeningTagEnd();
    writer.writeOpeningTagBeginning("leftCol");
    writer.writeAttribute("type", clip.getLeftCol(), false);
    writer.closeTag();
    writer.writeOpeningTagBeginning("rightCol");
    writer.writeAttribute("type", clip.getRightCol(), false);
    writer.closeTag();
    writer.writeClosingTag("columnControls");

    writer.writeClosingTag("instrumentClip");
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
    writer.writeAttribute(
        "arpeggiatorRate", DelugeHexMapper.unipolarFloatToHexUnified(synth.getArpRate()), false);

    // Write any raw patched parameters (like sidechain shape)
    for (var entry : synth.getRawKnobs().getRawParamKnobs().entrySet()) {
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

  private static void serializeMidiKnobs(XMLSerializer writer, List<MidiKnob> midiKnobs)
      throws IOException {
    if (midiKnobs != null && !midiKnobs.isEmpty()) {
      writer.writeArrayStart("midiKnobs");
      for (MidiKnob mk : midiKnobs) {
        writer.writeOpeningTagBeginning("midiKnob");
        writer.writeAttribute("channel", mk.channel(), false);
        writer.writeAttribute("ccNumber", mk.ccNumber(), false);
        writer.writeAttribute("relative", mk.relative() ? 1 : 0, false);
        writer.writeAttribute("controlsParam", mk.controlsParam(), false);
        if (mk.patchSource() != null && !"NONE".equals(mk.patchSource())) {
          writer.writeAttribute("patchAmountFromSource", mk.patchSource(), false);
        }
        writer.closeTag();
      }
      writer.writeArrayEnding("midiKnobs");
    } else {
      writer.writeTag("midiKnobs", "");
    }
  }
}
