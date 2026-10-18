package org.deluge.project;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    // C song.cpp:1222 -> global_effectable.cpp:791-795 & mod_controllable_audio.cpp:406-415 —
    // song-level GlobalEffectable state, modFXType, filter modes/route, and clippingAmount.
    writer.writeAttribute("modFXCurrentParam", model.getModFXCurrentParam(), false);
    writer.writeAttribute("currentFilterType", model.getCurrentFilterType(), false);
    writer.writeAttribute("modFXType", modFxTypeToFirmwareString(model.getSongModFXType()), false);
    writer.writeAttribute("lpfMode", lpfModeToFirmwareString(model.getSongLpfMode()), false);
    writer.writeAttribute("hpfMode", hpfModeToFirmwareString(model.getSongHpfMode()), false);
    writer.writeAttribute(
        "filterRoute", filterRouteToFirmwareString(model.getSongFilterRoute()), false);
    if (model.getSongClippingAmount() != 0) {
      writer.writeAttribute("clippingAmount", model.getSongClippingAmount(), false);
    }
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

    // ── Pre-index session and arrangement-only clips (C song.cpp:1149, 1313-1332, 5106-5117) ──
    Map<Object, Integer> clipTrackIndex = new java.util.IdentityHashMap<>();
    for (int tIdx = 0; tIdx < model.getTracks().size(); tIdx++) {
      TrackModel trk = model.getTracks().get(tIdx);
      if (trk instanceof AudioTrackModel audioTrk) {
        for (AudioTrackModel.AudioClip ac : audioTrk.getAudioClips()) {
          clipTrackIndex.put(ac, tIdx);
        }
      } else {
        for (ClipModel cm : trk.getClips()) {
          clipTrackIndex.put(cm, tIdx);
        }
      }
    }
    // Precompute sorted, non-overlapping accepted placements per track (C output.cpp:279-304 write,
    // output.cpp:356, 398-402 monotonic minPos read constraint) so orphan arrangement-only clip
    // filtering (C song.cpp:1324-1328) reflects the exact emitted ClipInstances.
    int trackCount = model.getTracks().size();
    java.util.List<java.util.List<ArrangerClip>> acceptedByTrack =
        new java.util.ArrayList<>(trackCount);
    java.util.Set<Object> referencedArrangementClips =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    for (int t = 0; t < trackCount; t++) {
      java.util.List<ArrangerClip> sorted = new java.util.ArrayList<>();
      for (ArrangerClip ac : model.getArrangerTimeline()) {
        if (ac.trackIndex() == t) {
          sorted.add(ac);
        }
      }
      sorted.sort(java.util.Comparator.comparingInt(ArrangerClip::startTicks));
      java.util.List<ArrangerClip> accepted = new java.util.ArrayList<>();
      int minPos = 0;
      for (ArrangerClip ac : sorted) {
        int pos = ac.startTicks();
        int len = ac.durationTicks();
        if (pos < minPos || len <= 0 || pos > Integer.MAX_VALUE - len) {
          continue;
        }
        minPos = pos + len;
        accepted.add(ac);
        Object target = ac.clip() != null ? ac.clip() : ac.audioClip();
        if (target != null && Integer.valueOf(t).equals(clipTrackIndex.get(target))) {
          referencedArrangementClips.add(target);
        }
      }
      acceptedByTrack.add(accepted);
    }
    // C song.cpp:1324-1328 skips orphan arrangement-only clips (!clip->output->clipHasInstance).
    // When getArrangerTimeline() is completely empty, standalone <arrangementOnlyTracks> clips from
    // unit XML fixtures without timeline instances are preserved for round-trip compatibility.
    boolean filterOrphanArrClips = !model.getArrangerTimeline().isEmpty();
    Map<Object, Integer> sessionClipIndices = new java.util.IdentityHashMap<>();
    Map<Object, Integer> arrangementClipIndices = new java.util.IdentityHashMap<>();
    int nextSessionIdx = 0;
    int nextArrangementIdx = 0;
    for (TrackModel trk : model.getTracks()) {
      if (trk instanceof AudioTrackModel audioTrk) {
        for (AudioTrackModel.AudioClip clip : audioTrk.getAudioClips()) {
          if (!clip.isArrangementOnly()) {
            sessionClipIndices.put(clip, nextSessionIdx++);
          } else if (!filterOrphanArrClips || referencedArrangementClips.contains(clip)) {
            arrangementClipIndices.put(clip, nextArrangementIdx++);
          }
        }
      } else {
        for (ClipModel clip : trk.getClips()) {
          if (!clip.isArrangementOnly()) {
            sessionClipIndices.put(clip, nextSessionIdx++);
          } else if (!filterOrphanArrClips || referencedArrangementClips.contains(clip)) {
            arrangementClipIndices.put(clip, nextArrangementIdx++);
          }
        }
      }
    }

    // ── instruments block ──
    writer.writeArrayStart("instruments");
    int trackIndex = 0;
    for (TrackModel track : model.getTracks()) {
      StringBuilder ciBuilder = new StringBuilder("0x");
      for (ArrangerClip ac : acceptedByTrack.get(trackIndex)) {
        ciBuilder.append(
            String.format(
                "%08X%08X%08X",
                ac.startTicks(),
                ac.durationTicks(),
                clipCodeFor(ac, sessionClipIndices, arrangementClipIndices)));
      }

      if (track instanceof KitTrackModel kitTrack) {
        writer.writeOpeningTagBeginning("kit");
        writer.writeAttribute("presetName", track.getName(), false);
        writer.writeAttribute("presetFolder", "KITS", false);
        // C kit.cpp:106-131 — GlobalEffectableForClip attributes on <kit>.
        writer.writeAttribute("lpfMode", lpfModeToFirmwareString(kitTrack.getLpfMode()), false);
        writer.writeAttribute("hpfMode", hpfModeToFirmwareString(kitTrack.getHpfMode()), false);
        writer.writeAttribute(
            "filterRoute", filterRouteToFirmwareString(kitTrack.getFilterRoute()), false);
        writer.writeAttribute(
            "modFXType",
            org.deluge.xml.DelugeXmlUtil.modFxTypeToFirmwareString(kitTrack.getModFxType()),
            false);
        writer.writeAttribute("clippingAmount", kitTrack.getClippingAmount(), false);
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

        writer.writeOpeningTagBeginning("delay");
        writer.writeAttribute("pingPong", String.valueOf(kitTrack.getDelayPingPong()), false);
        writer.writeAttribute("analog", String.valueOf(kitTrack.getDelayAnalog()), false);
        writer.writeAttribute("syncLevel", String.valueOf(kitTrack.getDelaySyncLevel()), false);
        if (kitTrack.getDelaySyncType() > 0) {
          writer.writeAttribute(
              "syncType",
              String.valueOf(
                  org.deluge.xml.DelugeXmlUtil.syncTypeToFile(kitTrack.getDelaySyncType())),
              false);
        }
        writer.closeTag();

        // C global_effectable.cpp:799-882, mod_controllable_audio.cpp:495-502 — write unpatched
        // params as attributes on <defaultParams>, followed by <delay>, <lpf>, <hpf>, <equalizer>.
        writer.writeOpeningTagBeginning("defaultParams");
        writer.writeAttribute(
            "reverbAmount",
            DelugeHexMapper.q31ToHex(
                kitTrack.getRawDefaultParamQ31("reverbAmount") != null
                    ? kitTrack.getRawDefaultParamQ31("reverbAmount")
                    : DelugeHexMapper.unipolarFloatToQ31Unified(kitTrack.getReverbAmount())),
            false);
        writer.writeAttribute(
            "volume",
            DelugeHexMapper.q31ToHex(
                kitTrack.getRawDefaultParamQ31("volume") != null
                    ? kitTrack.getRawDefaultParamQ31("volume")
                    : DelugeHexMapper.unipolarFloatToQ31Unified(kitTrack.getVolume())),
            false);
        writer.writeAttribute(
            "pan",
            DelugeHexMapper.q31ToHex(
                kitTrack.getRawDefaultParamQ31("pan") != null
                    ? kitTrack.getRawDefaultParamQ31("pan")
                    : DelugeHexMapper.floatToQ31(kitTrack.getPan())),
            false);
        writer.writeAttribute(
            "modFXDepth",
            DelugeHexMapper.q31ToHex(
                kitTrack.getRawDefaultParamQ31("modFXDepth") != null
                    ? kitTrack.getRawDefaultParamQ31("modFXDepth")
                    : DelugeHexMapper.unipolarFloatToQ31Unified(kitTrack.getModFxDepth())),
            false);
        writer.writeAttribute(
            "modFXRate",
            DelugeHexMapper.q31ToHex(
                kitTrack.getRawDefaultParamQ31("modFXRate") != null
                    ? kitTrack.getRawDefaultParamQ31("modFXRate")
                    : DelugeHexMapper.unipolarFloatToQ31Unified(kitTrack.getModFxRate())),
            false);
        writer.writeAttribute(
            "sampleRateReduction",
            DelugeHexMapper.q31ToHex(
                kitTrack.getRawDefaultParamQ31("sampleRateReduction") != null
                    ? kitTrack.getRawDefaultParamQ31("sampleRateReduction")
                    : DelugeHexMapper.unipolarFloatToQ31Unified(kitTrack.getSampleRateReduction())),
            false);
        writer.writeAttribute(
            "bitCrush",
            DelugeHexMapper.q31ToHex(
                kitTrack.getRawDefaultParamQ31("bitCrush") != null
                    ? kitTrack.getRawDefaultParamQ31("bitCrush")
                    : DelugeHexMapper.unipolarFloatToQ31Unified(kitTrack.getBitCrush())),
            false);
        writer.writeAttribute(
            "modFXOffset",
            DelugeHexMapper.q31ToHex(
                kitTrack.getRawDefaultParamQ31("modFXOffset") != null
                    ? kitTrack.getRawDefaultParamQ31("modFXOffset")
                    : DelugeHexMapper.unipolarFloatToQ31Unified(kitTrack.getModFxOffset())),
            false);
        writer.writeAttribute(
            "modFXFeedback",
            DelugeHexMapper.q31ToHex(
                kitTrack.getRawDefaultParamQ31("modFXFeedback") != null
                    ? kitTrack.getRawDefaultParamQ31("modFXFeedback")
                    : DelugeHexMapper.unipolarFloatToQ31Unified(kitTrack.getModFxFeedback())),
            false);
        writer.writeAttribute(
            "lpfMorph",
            DelugeHexMapper.q31ToHex(
                kitTrack.getRawDefaultParamQ31("lpf.morph") != null
                    ? kitTrack.getRawDefaultParamQ31("lpf.morph")
                    : DelugeHexMapper.unipolarFloatToQ31Unified(kitTrack.getLpfMorph())),
            false);
        writer.writeAttribute(
            "hpfMorph",
            DelugeHexMapper.q31ToHex(
                kitTrack.getRawDefaultParamQ31("hpf.morph") != null
                    ? kitTrack.getRawDefaultParamQ31("hpf.morph")
                    : DelugeHexMapper.unipolarFloatToQ31Unified(kitTrack.getHpfMorph())),
            false);
        writer.writeOpeningTagEnd();
        writer.writeOpeningTagBeginning("delay");
        writer.writeAttribute(
            "rate",
            DelugeHexMapper.q31ToHex(
                kitTrack.getRawDefaultParamQ31("delay.rate") != null
                    ? kitTrack.getRawDefaultParamQ31("delay.rate")
                    : DelugeHexMapper.unipolarFloatToQ31Unified(kitTrack.getDelayRate())),
            false);
        writer.writeAttribute(
            "feedback",
            DelugeHexMapper.q31ToHex(
                kitTrack.getRawDefaultParamQ31("delay.feedback") != null
                    ? kitTrack.getRawDefaultParamQ31("delay.feedback")
                    : DelugeHexMapper.unipolarFloatToQ31Unified(kitTrack.getDelayFeedback())),
            false);
        writer.closeTag();
        writer.writeOpeningTagBeginning("lpf");
        writer.writeAttribute(
            "frequency",
            DelugeHexMapper.q31ToHex(
                kitTrack.getRawDefaultParamQ31("lpf.frequency") != null
                    ? kitTrack.getRawDefaultParamQ31("lpf.frequency")
                    : DelugeHexMapper.hzToQ31(kitTrack.getLpfFreq())),
            false);
        writer.writeAttribute(
            "resonance",
            DelugeHexMapper.q31ToHex(
                kitTrack.getRawDefaultParamQ31("lpf.resonance") != null
                    ? kitTrack.getRawDefaultParamQ31("lpf.resonance")
                    : DelugeHexMapper.unipolarFloatToQ31Unified(kitTrack.getLpfRes())),
            false);
        writer.closeTag();
        writer.writeOpeningTagBeginning("hpf");
        writer.writeAttribute(
            "frequency",
            DelugeHexMapper.q31ToHex(
                kitTrack.getRawDefaultParamQ31("hpf.frequency") != null
                    ? kitTrack.getRawDefaultParamQ31("hpf.frequency")
                    : DelugeHexMapper.hzToQ31(kitTrack.getHpfFreq())),
            false);
        writer.writeAttribute(
            "resonance",
            DelugeHexMapper.q31ToHex(
                kitTrack.getRawDefaultParamQ31("hpf.resonance") != null
                    ? kitTrack.getRawDefaultParamQ31("hpf.resonance")
                    : DelugeHexMapper.unipolarFloatToQ31Unified(kitTrack.getHpfRes())),
            false);
        writer.closeTag();
        writer.writeOpeningTagBeginning("equalizer");
        writer.writeAttribute(
            "bass",
            DelugeHexMapper.q31ToHex(
                kitTrack.getRawDefaultParamQ31("equalizer.bass") != null
                    ? kitTrack.getRawDefaultParamQ31("equalizer.bass")
                    : DelugeHexMapper.floatToQ31(kitTrack.getEqBass())),
            false);
        writer.writeAttribute(
            "treble",
            DelugeHexMapper.q31ToHex(
                kitTrack.getRawDefaultParamQ31("equalizer.treble") != null
                    ? kitTrack.getRawDefaultParamQ31("equalizer.treble")
                    : DelugeHexMapper.floatToQ31(kitTrack.getEqTreble())),
            false);
        writer.writeAttribute(
            "bassFrequency",
            DelugeHexMapper.q31ToHex(
                kitTrack.getRawDefaultParamQ31("equalizer.bassFrequency") != null
                    ? kitTrack.getRawDefaultParamQ31("equalizer.bassFrequency")
                    : DelugeHexMapper.floatToQ31(kitTrack.getEqBassFreq())),
            false);
        writer.writeAttribute(
            "trebleFrequency",
            DelugeHexMapper.q31ToHex(
                kitTrack.getRawDefaultParamQ31("equalizer.trebleFrequency") != null
                    ? kitTrack.getRawDefaultParamQ31("equalizer.trebleFrequency")
                    : DelugeHexMapper.floatToQ31(kitTrack.getEqTrebleFreq())),
            false);
        writer.closeTag();
        writer.writeClosingTag("defaultParams");
        if (kitTrack.getArp() != null) {
          writeSoundArpeggiator(writer, kitTrack.getArp(), model.getInputTickMagnitude());
        }

        writer.writeArrayStart("soundSources");

        for (Drum drum : ((KitTrackModel) track).getDrums()) {
          SoundDrum sound = (SoundDrum) drum;
          writer.writeOpeningTagBeginning("sound");
          writer.writeAttribute("name", sound.getName(), false);
          // C polyphonyModeToString (functions.cpp:835-852) writes "mono"/"poly"/"legato"/
          // "auto"/"choke".
          String polyVal =
              switch (sound.getPolyphony()) {
                case MONO -> "mono";
                case LEGATO -> "legato";
                case CHOKE -> "choke";
                case AUTO -> "auto";
                default -> "poly";
              };
          writer.writeAttribute("polyphonic", polyVal, false);
          writer.writeAttribute("voicePriority", sound.getVoicePriority(), false);
          writer.writeAttribute(
              "clippingAmount", String.valueOf(Math.round(sound.getClippingAmount())), false);
          if (sound.getMuteGroup() != 0) {
            writer.writeAttribute("muteGroup", sound.getMuteGroup(), false);
          }
          if (sound.getSidechainSend() > 0.0f) {
            writer.writeAttribute(
                "sideChainSend",
                (int) Math.round((double) sound.getSidechainSend() * Integer.MAX_VALUE),
                false);
          }
          String drumMode =
              switch (sound.getSynthMode()) {
                case 1 -> "fm";
                case 2 -> "ringmod";
                default -> "subtractive";
              };
          writer.writeAttribute("mode", drumMode, false);
          if (Math.round(sound.getPitchSemitones()) != 0) {
            writer.writeAttribute(
                "transpose", String.valueOf(Math.round(sound.getPitchSemitones())), false);
          }
          if (sound.getCents() != 0) {
            writer.writeAttribute("cents", String.valueOf(sound.getCents()), false);
          }
          writer.writeAttribute("lpfMode", lpfModeToFirmwareString(sound.getLpfMode()), false);
          writer.writeAttribute("hpfMode", hpfModeToFirmwareString(sound.getHpfMode()), false);
          writer.writeAttribute(
              "filterRoute", filterRouteToFirmwareString(sound.getFilterRoute()), false);
          writer.writeAttribute(
              "modFXType",
              org.deluge.xml.DelugeXmlUtil.modFxTypeToFirmwareString(sound.getModFxType()),
              false);
          writer.writeAttribute("maxVoices", sound.getMaxVoiceCount(), false);
          writer.writeOpeningTagEnd();

          boolean hasOsc1Sample = sound.getSamplePath() != null && !sound.getSamplePath().isEmpty();
          writer.writeOpeningTagBeginning("osc1");
          if (hasOsc1Sample) {
            writer.writeAttribute(
                "type",
                "WAVETABLE".equalsIgnoreCase(sound.getOsc1Type()) ? "wavetable" : "sample",
                false);
            writer.writeAttribute("fileName", sound.getSamplePath(), false);
            writer.writeAttribute("loopMode", String.valueOf(sound.getOsc1LoopMode()), false);
            writer.writeAttribute("reversed", sound.isReverse() ? "1" : "0", false);
            writer.writeAttribute(
                "timeStretchEnable", sound.isOsc1TimeStretch() ? "1" : "0", false);
            writer.writeAttribute(
                "timeStretchAmount", String.valueOf(sound.getOsc1TimeStretchAmount()), false);
            writer.writeAttribute(
                "linearInterpolation", sound.isOsc1LinearInterpolation() ? "1" : "0", false);
          } else {
            writer.writeAttribute("type", oscTypeToFirmwareString(sound.getOsc1Type()), false);
          }
          writer.writeAttribute("transpose", String.valueOf(sound.getOsc1Transpose()), false);
          writer.writeAttribute("cents", String.valueOf(sound.getOsc1Cents()), false);
          writer.writeAttribute("retrigPhase", String.valueOf(sound.getOsc1RetrigPhase()), false);
          if (hasOsc1Sample) {
            writer.writeOpeningTagEnd();
            writer.writeOpeningTagBeginning("zone");
            if (sound.getStartSamplePos() >= 0) {
              writer.writeAttribute(
                  "startSamplePos", String.valueOf(sound.getStartSamplePos()), false);
            }
            if (sound.getEndSamplePos() >= 0) {
              writer.writeAttribute("endSamplePos", String.valueOf(sound.getEndSamplePos()), false);
            }
            if (sound.getStartLoopPos() >= 0) {
              writer.writeAttribute("startLoopPos", String.valueOf(sound.getStartLoopPos()), false);
            }
            if (sound.getEndLoopPos() >= 0) {
              writer.writeAttribute("endLoopPos", String.valueOf(sound.getEndLoopPos()), false);
            }
            writer.closeTag();
            writer.writeClosingTag("osc1");
          } else {
            writer.closeTag();
          }

          boolean hasOsc2Sample =
              sound.getOsc2SamplePath() != null && !sound.getOsc2SamplePath().isEmpty();
          writer.writeOpeningTagBeginning("osc2");
          writer.writeAttribute("type", oscTypeToFirmwareString(sound.getOsc2Type()), false);
          writer.writeAttribute("transpose", String.valueOf(sound.getOsc2Transpose()), false);
          writer.writeAttribute("cents", String.valueOf(sound.getOsc2Cents()), false);
          writer.writeAttribute("retrigPhase", String.valueOf(sound.getOsc2RetrigPhase()), false);
          if (hasOsc2Sample) {
            writer.writeAttribute("fileName", sound.getOsc2SamplePath(), false);
            writer.writeAttribute("loopMode", String.valueOf(sound.getOsc2LoopMode()), false);
            writer.writeAttribute("reversed", sound.isOsc2Reversed() ? "1" : "0", false);
            writer.writeAttribute(
                "timeStretchEnable", sound.isOsc2TimeStretch() ? "1" : "0", false);
            writer.writeAttribute(
                "timeStretchAmount", String.valueOf(sound.getOsc2TimeStretchAmount()), false);
            writer.writeAttribute(
                "linearInterpolation", sound.isOsc2LinearInterpolation() ? "1" : "0", false);
            writer.writeOpeningTagEnd();
            writer.writeOpeningTagBeginning("zone");
            if (sound.getOsc2StartSamplePos() >= 0) {
              writer.writeAttribute(
                  "startSamplePos", String.valueOf(sound.getOsc2StartSamplePos()), false);
            }
            if (sound.getOsc2EndSamplePos() >= 0) {
              writer.writeAttribute(
                  "endSamplePos", String.valueOf(sound.getOsc2EndSamplePos()), false);
            }
            if (sound.getOsc2StartLoopPos() >= 0) {
              writer.writeAttribute(
                  "startLoopPos", String.valueOf(sound.getOsc2StartLoopPos()), false);
            }
            if (sound.getOsc2EndLoopPos() >= 0) {
              writer.writeAttribute("endLoopPos", String.valueOf(sound.getOsc2EndLoopPos()), false);
            }
            writer.closeTag();
            writer.writeClosingTag("osc2");
          } else {
            if (sound.isOscillatorSync()) {
              // C sound.cpp:3701-3703 — oscillatorSync written on osc2 when non-sample and enabled.
              writer.writeAttribute("oscillatorSync", "1", false);
            }
            writer.closeTag();
          }

          // FM modulators 1 & 2 (C sound.cpp:4217-4234)
          writer.writeOpeningTagBeginning("modulator1");
          writer.writeAttribute("transpose", String.valueOf(sound.getModulator1Transpose()), false);
          writer.writeAttribute("cents", String.valueOf(sound.getModulator1Cents()), false);
          writer.writeAttribute("retrigPhase", String.valueOf(sound.getMod1RetrigPhase()), false);
          writer.closeTag();
          writer.writeOpeningTagBeginning("modulator2");
          writer.writeAttribute("transpose", String.valueOf(sound.getModulator2Transpose()), false);
          writer.writeAttribute("cents", String.valueOf(sound.getModulator2Cents()), false);
          writer.writeAttribute("retrigPhase", String.valueOf(sound.getMod2RetrigPhase()), false);
          writer.writeAttribute(
              "toModulator1", sound.isModulator1ToModulator0() ? "1" : "0", false);
          writer.closeTag();

          // LFOs 1..4 (C sound.cpp:4201-4215)
          serializeLfo(writer, "lfo1", sound.getLfo1());
          serializeLfo(writer, "lfo2", sound.getLfo2());
          serializeLfo(writer, "lfo3", sound.getLfo3());
          serializeLfo(writer, "lfo4", sound.getLfo4());

          writer.writeOpeningTagBeginning("unison");
          writer.writeOpeningTagEnd();
          writer.writeTag("num", String.valueOf(sound.getUnisonNum()));
          // C sound.cpp:4243-4246 — unison detune/spread are plain decimal integers (0..50).
          writer.writeTag("detune", String.valueOf(Math.round(sound.getUnisonDetune())));
          writer.writeTag("spread", String.valueOf(Math.round(sound.getUnisonStereoSpread())));
          writer.writeClosingTag("unison");

          // Delay (C mod_controllable_audio.cpp:417-425)
          writer.writeOpeningTagBeginning("delay");
          writer.writeAttribute("pingPong", String.valueOf(sound.getDelayPingPong()), false);
          writer.writeAttribute("analog", String.valueOf(sound.getDelayAnalog()), false);
          writer.writeAttribute("syncLevel", String.valueOf(sound.getDelaySyncLevel()), false);
          if (sound.getDelaySyncType() > 0) {
            writer.writeAttribute(
                "syncType",
                String.valueOf(
                    org.deluge.xml.DelugeXmlUtil.syncTypeToFile(sound.getDelaySyncType())),
                false);
          }
          writer.closeTag();

          // Compressor
          if (sound.getCompressorAttack() > 0
              || sound.getCompressorRelease() > 0
              || sound.getCompressorSyncLevel() > 0
              || sound.getCompressorSyncType() > 0) {
            writer.writeOpeningTagBeginning("compressor");
            writer.writeAttribute(
                "syncLevel", String.valueOf(sound.getCompressorSyncLevel()), false);
            if (sound.getCompressorSyncType() > 0) {
              writer.writeAttribute(
                  "syncType",
                  String.valueOf(
                      org.deluge.xml.DelugeXmlUtil.syncTypeToFile(sound.getCompressorSyncType())),
                  false);
            }
            writer.writeAttribute(
                "attack",
                String.valueOf((int) (sound.getCompressorAttack() * Integer.MAX_VALUE)),
                false);
            writer.writeAttribute(
                "release",
                String.valueOf((int) (sound.getCompressorRelease() * Integer.MAX_VALUE)),
                false);
            writer.closeTag();
          }

          // Sidechain (C mod_controllable_audio.cpp:447-460)
          if (sound.getSidechainAttackRaw() >= 0
              || sound.getSidechainReleaseRaw() >= 0
              || sound.getSidechainAttack() > 0
              || sound.getSidechainRelease() > 0
              || sound.getSidechainSyncLevel() > 0
              || sound.getSidechainSyncType() > 0) {
            writer.writeOpeningTagBeginning("sidechain");
            writer.writeAttribute(
                "syncLevel", String.valueOf(sound.getSidechainSyncLevel()), false);
            if (sound.getSidechainSyncType() > 0) {
              writer.writeAttribute(
                  "syncType",
                  String.valueOf(
                      org.deluge.xml.DelugeXmlUtil.syncTypeToFile(sound.getSidechainSyncType())),
                  false);
            }
            if (sound.getSidechainAttackRaw() >= 0 || sound.getSidechainAttack() > 0) {
              writer.writeAttribute(
                  "attack",
                  sound.getSidechainAttackRaw() >= 0
                      ? String.valueOf(sound.getSidechainAttackRaw())
                      : String.valueOf(
                          (int) Math.round(sound.getSidechainAttack() * Integer.MAX_VALUE)),
                  false);
            }
            if (sound.getSidechainReleaseRaw() >= 0 || sound.getSidechainRelease() > 0) {
              writer.writeAttribute(
                  "release",
                  sound.getSidechainReleaseRaw() >= 0
                      ? String.valueOf(sound.getSidechainReleaseRaw())
                      : String.valueOf(
                          (int) Math.round(sound.getSidechainRelease() * Integer.MAX_VALUE)),
                  false);
            }
            writer.closeTag();
          }

          // Stutter (C mod_controllable_audio.cpp:481-486 — hardware writes "reverse")
          writer.writeOpeningTagBeginning("stutter");
          writer.writeAttribute("quantized", sound.isStutterQuantized() ? "1" : "0", false);
          writer.writeAttribute("reverse", sound.isStutterReversed() ? "1" : "0", false);
          writer.writeAttribute("pingPong", sound.isStutterPingPong() ? "1" : "0", false);
          writer.closeTag();

          // Per-drum arpeggiator (C sound.cpp:4253 — writes <arpeggiator kitArp="..." ...>)
          writeSoundArpeggiator(writer, sound.getArp(), model.getInputTickMagnitude());

          writer.writeOpeningTagBeginning("defaultParams");
          writer.writeOpeningTagEnd();
          writeDrumRawOrUnipolar(writer, sound, "arpeggiatorGate", sound.getArpeggiatorGate());
          writeDrumRawOrUnipolar(writer, sound, "portamento", sound.getPortamento());
          writeDrumRawOrUnipolar(writer, sound, "compressorShape", sound.getCompressorShape());
          float effectiveDrumOscAVol =
              ("SAMPLE".equalsIgnoreCase(sound.getOsc1Type()) && sound.getOscAVolume() == 0.0f)
                  ? 1.0f
                  : sound.getOscAVolume();
          writeDrumRawOrUnipolar(writer, sound, "oscAVolume", effectiveDrumOscAVol);
          writeRawQ31Tag(
              writer,
              "oscAPulseWidth",
              sound.getRawDefaultParamQ31("oscAPulseWidth") != null
                  ? sound.getRawDefaultParamQ31("oscAPulseWidth")
                  : 0);
          writeDrumRawOrUnipolar(writer, sound, "oscAWavetablePosition", sound.getWaveIndex());
          writeDrumRawOrUnipolar(writer, sound, "oscBVolume", sound.getOscBVolume());
          writeRawQ31Tag(
              writer,
              "oscBPulseWidth",
              sound.getRawDefaultParamQ31("oscBPulseWidth") != null
                  ? sound.getRawDefaultParamQ31("oscBPulseWidth")
                  : 0);
          writeDrumRawOrUnipolar(writer, sound, "oscBWavetablePosition", sound.getOsc2WaveIndex());
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
              writeRawQ31Tag(writer, pitchTag, rawPitch);
            }
          }
          writeDrumRawOrUnipolar(writer, sound, "noiseVolume", sound.getNoiseVolume());
          writeDrumRawOrUnipolar(writer, sound, "volume", sound.getVolume());
          writeDrumRawOrBipolar(writer, sound, "pan", "pan", sound.getPan());
          writeDrumRawOrFreq(writer, sound, "lpfFrequency", sound.getLpfFreq());
          writeDrumRawOrUnipolar(writer, sound, "lpfResonance", sound.getLpfRes());
          writeDrumRawOrUnipolar(writer, sound, "lpfMorph", sound.getLpfMorph());
          writeDrumRawOrFreq(writer, sound, "hpfFrequency", sound.getHpfFreq());
          writeDrumRawOrUnipolar(writer, sound, "hpfResonance", sound.getHpfRes());
          writeDrumRawOrUnipolar(writer, sound, "hpfMorph", sound.getHpfMorph());
          writeRawQ31Tag(
              writer, "modFXRate", sound.getModFxRateQ31() != null ? sound.getModFxRateQ31() : 0);
          writeRawQ31Tag(
              writer,
              "modFXDepth",
              sound.getModFxDepthQ31() != null ? sound.getModFxDepthQ31() : 0);
          writeRawQ31Tag(
              writer,
              "modFXOffset",
              sound.getModFxOffsetQ31() != null ? sound.getModFxOffsetQ31() : 0);
          writeRawQ31Tag(
              writer,
              "modFXFeedback",
              sound.getModFxFeedbackQ31() != null ? sound.getModFxFeedbackQ31() : 0);
          writeRawQ31Tag(
              writer, "delayRate", sound.getDelayRateQ31() != null ? sound.getDelayRateQ31() : 0);
          writeRawQ31Tag(
              writer,
              "delayFeedback",
              sound.getDelayFeedbackQ31() != null
                  ? sound.getDelayFeedbackQ31()
                  : Integer.MIN_VALUE);
          writeDrumRawOrUnipolar(writer, sound, "reverbAmount", sound.getReverbAmount());
          writeDrumRawOrUnipolar(writer, sound, "arpeggiatorRate", sound.getArpeggiatorRate());
          writeDrumRawOrUnipolar(writer, sound, "stutterRate", sound.getStutterRate());
          writeDrumRawOrUnipolar(
              writer, sound, "sampleRateReduction", sound.getSampleRateReduction());
          writeDrumRawOrUnipolar(writer, sound, "bitCrush", sound.getBitCrush());
          writeDrumRawOrUnipolar(writer, sound, "waveIndex", sound.getWaveIndex());
          Integer drumFold = sound.getRawDefaultParamQ31("waveFold");
          if (drumFold != null) {
            writeRawQ31Tag(writer, "waveFold", drumFold);
          }

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
              writer.writeOpeningTagBeginning(envTag);
              writer.writeOpeningTagEnd();
              writeRawQ31Tag(writer, "attack", aQ31);
              writeRawQ31Tag(writer, "decay", dQ31);
              writeRawQ31Tag(writer, "sustain", sQ31);
              writeRawQ31Tag(writer, "release", rQ31);
              writer.writeClosingTag(envTag);
            } else {
              serializeEnvelope(writer, envTag, drumEnvs[envIdx]);
            }
          }

          org.deluge.model.LfoModel[] drumLfos = {
            sound.getLfo1(), sound.getLfo2(), sound.getLfo3(), sound.getLfo4()
          };
          for (int lfoIdx = 0; lfoIdx < 4; lfoIdx++) {
            String rateTag = "lfo" + (lfoIdx + 1) + "Rate";
            Integer rawRate = sound.getRawDefaultParamQ31(rateTag);
            if (rawRate != null) {
              writeRawQ31Tag(writer, rateTag, rawRate);
            } else {
              writeHexTagLfoFreq(writer, rateTag, drumLfos[lfoIdx].rateHz());
            }
          }

          writeRawQ31Tag(writer, "modulator1Amount", sound.getModulator1AmountQ31());
          writeRawQ31Tag(writer, "modulator1Feedback", sound.getModulator1FeedbackQ31());
          writeRawQ31Tag(writer, "modulator2Amount", sound.getModulator2AmountQ31());
          writeRawQ31Tag(writer, "modulator2Feedback", sound.getModulator2FeedbackQ31());
          writeRawQ31Tag(writer, "carrier1Feedback", sound.getCarrier1FeedbackQ31());
          writeRawQ31Tag(writer, "carrier2Feedback", sound.getCarrier2FeedbackQ31());

          writer.writeOpeningTagBeginning("equalizer");
          writer.writeOpeningTagEnd();
          writeDrumRawOrBipolar(writer, sound, "bass", "eq.bass", sound.getEqBass());
          writeDrumRawOrBipolar(writer, sound, "treble", "eq.treble", sound.getEqTreble());
          writeDrumRawOrBipolar(
              writer, sound, "bassFrequency", "eq.bassFrequency", sound.getEqBassFreq());
          writeDrumRawOrBipolar(
              writer, sound, "trebleFrequency", "eq.trebleFrequency", sound.getEqTrebleFreq());
          writer.writeClosingTag("equalizer");

          if (!sound.getPatchCables().isEmpty()) {
            // C sound.cpp:4159 -> PatchCableSet::writePatchCablesToFile(writer, false).
            writer.writeArrayStart("patchCables");
            for (PatchCable pc : sound.getPatchCables()) {
              writePatchCable(writer, pc, false);
            }
            writer.writeArrayEnding("patchCables");
          }
          writer.writeClosingTag("defaultParams");

          serializeMidiKnobs(writer, sound.getMidiKnobs());

          boolean drumHasKnobs =
              sound.getModKnobs().stream().anyMatch(k -> !"NONE".equals(k.param()));
          if (drumHasKnobs) {
            writer.writeArrayStart("modKnobs");
            for (ModKnob mk : sound.getModKnobs()) {
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
        // C MIDIInstrument::getXMLTag (midi_instrument.h:122) — "midi"; the slot goes out as an
        // attribute (Instrument::writeDataToFile, instrument.cpp:44-68): zone="lower|upper" for
        // MPE, otherwise channel="0..15". A <sound presetFolder="SYNTHS"> is loaded as a synth.
        writer.writeOpeningTagBeginning("midi");
        if (midi.isMpe()) {
          writer.writeAttribute(
              "zone", "upper".equalsIgnoreCase(midi.getMpeZone()) ? "upper" : "lower", false);
        } else {
          writer.writeAttribute("channel", midi.getMidiChannel() - 1, false); // model 1..16
        }
        writer.writeAttribute("suffix", -1, false); // C getSubSlotXMLTag, no suffix
        if (midi.getName() != null && !midi.getName().isEmpty()) {
          writer.writeAttribute("presetName", midi.getName(), false); // instrument.cpp:71-73
        }
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

        // Gold-knob MIDI-CC assignments. C: MIDIInstrument::writeDataToFile
        // (midi_instrument.cpp:298-334) - 16 <modKnob cc="..."/> entries in mode*2+knobIndex
        // order; "none"/"bend"/"aftertouch" are the C's special sentinel strings
        // (CC_NUMBER_NONE=123/PITCH_BEND=120/AFTERTOUCH=121, definitions_cxx.hpp:734-737).
        boolean hasCcAssignments = false;
        for (int mode = 0; mode < 8 && !hasCcAssignments; mode++) {
          for (int knob = 0; knob < 2; knob++) {
            if (midi.getModKnobCc(mode, knob) != MidiTrackModel.CC_NUMBER_NONE) {
              hasCcAssignments = true;
              break;
            }
          }
        }
        if (hasCcAssignments) {
          writer.writeOpeningTagBeginning("modKnobs");
          writer.writeOpeningTagEnd();
          for (int mode = 0; mode < 8; mode++) {
            for (int knob = 0; knob < 2; knob++) {
              int cc = midi.getModKnobCc(mode, knob);
              String ccAttr =
                  switch (cc) {
                    case MidiTrackModel.CC_NUMBER_NONE -> "none";
                    case 120 -> "bend"; // CC_NUMBER_PITCH_BEND
                    case 121 -> "aftertouch"; // CC_NUMBER_AFTERTOUCH
                    default -> String.valueOf(cc);
                  };
              writer.writeOpeningTagBeginning("modKnob");
              writer.writeAttribute("cc", ccAttr, false);
              writer.closeTag();
            }
          }
          writer.writeClosingTag("modKnobs");
        }
        writer.writeClosingTag("midi");

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
        // C sound.cpp:4171-4190 — sideChainSend (conditional when non-zero) & maxVoices.
        if (synth.getSidechainSend() > 0.0f) {
          writer.writeAttribute(
              "sideChainSend",
              (int) Math.round(synth.getSidechainSend() * Integer.MAX_VALUE),
              false);
        }
        writer.writeAttribute("maxVoices", synth.getMaxVoiceCount(), false);
        if (ciBuilder.length() > 2) {
          writer.writeAttribute("clipInstances", ciBuilder.toString(), false);
        }
        writer.writeOpeningTagEnd();

        // osc1 — prefer the VERBATIM captured multisample XML (correct
        // <sampleRange rangeTopNote><zone/></sampleRange> structure the hardware needs); the
        // dynamic-keyzone path below omits the <sampleRange rangeTopNote> wrappers, so the Deluge
        // can't map keyzones → silent multisamples. Fall back to dynamic zones / single-sample only
        // when no raw XML was captured.
        boolean isDx7 =
            synth.getSynthMode() != 2
                && ("DX7".equalsIgnoreCase(synth.getOsc1Type())
                    || (synth.getDx7Patch() != null && !synth.getDx7Patch().isEmpty()));
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
          writer.writeAttribute(
              "type", isDx7 ? "dx7" : oscTypeToFirmwareString(synth.getOsc1Type()), false);
          writer.writeAttribute("transpose", String.valueOf(synth.getOsc1Transpose()), false);
          writer.writeAttribute("cents", String.valueOf(synth.getOsc1Cents()), false);
          writer.writeAttribute("retrigPhase", String.valueOf(synth.getOsc1RetrigPhase()), false);
          if (synth.getOsc1SamplePath() != null && !synth.getOsc1SamplePath().isEmpty()) {
            writer.writeAttribute("fileName", synth.getOsc1SamplePath(), false);
            // C sound.cpp source keys; the repeat mode was never saved (C SampleRepeatMode).
            writer.writeAttribute("loopMode", String.valueOf(synth.getOsc1LoopMode()), false);
            writer.writeAttribute("reversed", synth.isOsc1Reversed() ? "1" : "0", false);
            writer.writeAttribute(
                "timeStretchEnable", synth.isOsc1TimeStretch() ? "1" : "0", false);
            // C sound.cpp:3636
            writer.writeAttribute(
                "timeStretchAmount", String.valueOf(synth.getOsc1TimeStretchAmount()), false);
            // C sound.cpp:3637-3639 — linearInterpolation="1" when linear mode is active.
            if (synth.isOsc1LinearInterpolation()) {
              writer.writeAttribute("linearInterpolation", "1", false);
            }
          }
          // C sound.cpp:3744-3758, source.cpp:207-209, voice.cpp:1322-1357 — write dx7patch plus
          // conditional non-zero dx7enginemode & dx7randomdetune when DX7 source routing is active
          // (never in RingMod).
          if (synth.getSynthMode() != 2
              && synth.getDx7Patch() != null
              && !synth.getDx7Patch().isEmpty()) {
            writer.writeAttribute("dx7patch", synth.getDx7Patch(), false);
            if (synth.getEngineType() != 0) {
              writer.writeAttribute("dx7enginemode", String.valueOf(synth.getEngineType()), false);
            }
            if (synth.getDx7RandomDetune() != 0) {
              writer.writeAttribute(
                  "dx7randomdetune", String.valueOf(synth.getDx7RandomDetune()), false);
            }
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
          writer.writeAttribute("type", oscTypeToFirmwareString(synth.getOsc2Type()), false);
          writer.writeAttribute("transpose", String.valueOf(synth.getOsc2Transpose()), false);
          writer.writeAttribute("cents", String.valueOf(synth.getOsc2Cents()), false);
          writer.writeAttribute("retrigPhase", String.valueOf(synth.getOsc2RetrigPhase()), false);
          if (synth.getOsc2SamplePath() != null && !synth.getOsc2SamplePath().isEmpty()) {
            writer.writeAttribute("fileName", synth.getOsc2SamplePath(), false);
            writer.writeAttribute("loopMode", String.valueOf(synth.getOsc2LoopMode()), false);
            writer.writeAttribute("reversed", synth.isOsc2Reversed() ? "1" : "0", false);
            writer.writeAttribute(
                "timeStretchEnable", synth.isOsc2TimeStretch() ? "1" : "0", false);
            // C sound.cpp:3636
            writer.writeAttribute(
                "timeStretchAmount", String.valueOf(synth.getOsc2TimeStretchAmount()), false);
            if (synth.isOsc2LinearInterpolation()) {
              writer.writeAttribute("linearInterpolation", "1", false);
            }
          } else if (synth.isOscillatorSync()) {
            // C sound.cpp:3701-3703 — oscillatorSync written on osc2 when non-sample and enabled.
            writer.writeAttribute("oscillatorSync", "1", false);
          }
          writer.closeTag();
        }

        // C polyphonyModeToString (functions.cpp:835-852) writes "mono"/"poly"/"legato"/
        // "auto"/"choke".
        String polyVal =
            switch (synth.getPolyphony()) {
              case MONO -> "mono";
              case LEGATO -> "legato";
              case CHOKE -> "choke";
              case AUTO -> "auto";
              default -> "poly";
            };
        writer.writeTag("polyphonic", polyVal);
        // Was hardcoded "0"/"1" — dropped the preset's saturation/distortion (clippingAmount is
        // parsed + applied to the engine) and voice priority. Write plain decimal integers matching
        // C sound.cpp:4249 & InstrumentXmlParser.java:1058 (Integer.parseInt).
        writer.writeTag("clippingAmount", String.valueOf(Math.round(synth.getClippingAmount())));
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

        // C sound.cpp:3744-3745 — DX7 sources require synthMode != FM ("subtractive") in hardware
        // XML so real Deluge hardware routes through OscType::DX7 instead of 2-op sine FM.
        String mode =
            switch (synth.getSynthMode()) {
              case 1 -> isDx7 ? "subtractive" : "fm";
              case 2 -> "ringmod";
              default -> "subtractive";
            };
        writer.writeTag("mode", mode);
        // Sound-level master transpose + cents (C sound.cpp:4169-4174).
        writer.writeTag("transpose", String.valueOf(synth.getTranspose()));
        if (synth.getCents() != 0) {
          writer.writeTag("cents", String.valueOf(synth.getCents()));
        }

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
        // C sound.cpp:4243-4246 — unison detune/spread are plain decimal integers (0..50).
        writer.writeTag("detune", String.valueOf(Math.round(synth.getUnison().getUnisonDetune())));
        writer.writeTag(
            "spread", String.valueOf(Math.round(synth.getUnison().getUnisonStereoSpread())));
        writer.writeClosingTag("unison");

        // arpeggiator
        writeSoundArpeggiator(writer, synth.getArp(), model.getInputTickMagnitude());

        // delay (C mod_controllable_audio.cpp:417-425 — unconditional)
        writer.writeOpeningTagBeginning("delay");
        writer.writeAttribute("pingPong", synth.isDelayPingPong() ? "1" : "0", false);
        writer.writeAttribute("analog", synth.isDelayAnalog() ? "1" : "0", false);
        writer.writeAttribute("syncLevel", String.valueOf(synth.getDelaySyncLevel()), false);
        if (synth.getDelaySyncType() > 0) {
          writer.writeAttribute(
              "syncType",
              String.valueOf(org.deluge.xml.DelugeXmlUtil.syncTypeToFile(synth.getDelaySyncType())),
              false);
        }
        writer.closeTag();

        writer.writeTag(
            "lpfMode",
            switch (synth.getFilterMode()) {
              case LADDER_24 -> "24dB";
              case DRIVE -> "24dBDrive";
              case SVF, SVF_BAND -> "SVF_Band";
              case SVF_NOTCH -> "SVF_Notch";
              case OFF -> "Off";
              default -> "12dB";
            });

        writer.writeTag(
            "hpfMode",
            switch (synth.getHpfMode()) {
              // C: active HP ladder serializes as "HPLadder" (mod_controllable_audio.cpp:410);
              // LP-mode strings would load as an inert HPF (filter_set.cpp:26-41).
              case SVF, SVF_BAND -> "SVF_Band";
              case SVF_NOTCH -> "SVF_Notch";
              case OFF -> "Off";
              default -> "HPLadder";
            });

        // C filter_config.cpp:16-20, mod_controllable_audio.cpp:411
        writer.writeTag(
            "filterRoute",
            org.deluge.xml.DelugeXmlUtil.filterRouteToFirmwareString(synth.getFilterRoute()));

        writer.writeTag(
            "modFXType",
            org.deluge.xml.DelugeXmlUtil.modFxTypeToFirmwareString(synth.getModFxType()));

        // stutter (C mod_controllable_audio.cpp:481-486 — hardware writes "reverse")
        writer.writeOpeningTagBeginning("stutter");
        writer.writeAttribute(
            "quantized", synth.getStutter().isStutterQuantized() ? "1" : "0", false);
        writer.writeAttribute("reverse", synth.getStutter().isStutterReversed() ? "1" : "0", false);
        writer.writeAttribute(
            "pingPong", synth.getStutter().isStutterPingPong() ? "1" : "0", false);
        writer.closeTag();

        // compressor & sidechain (C mod_controllable_audio.cpp:447-479)
        float compAttack = synth.getCompressorAttack();
        float compRelease = synth.getCompressorRelease();
        int compSync = synth.getCompressorSyncLevel();
        if (compAttack > 0
            || compRelease > 0
            || compSync > 0
            || synth.getCompressorSyncType() > 0) {
          writer.writeOpeningTagBeginning("compressor");
          writer.writeAttribute("syncLevel", String.valueOf(compSync), false);
          if (synth.getCompressorSyncType() > 0) {
            writer.writeAttribute(
                "syncType",
                String.valueOf(
                    org.deluge.xml.DelugeXmlUtil.syncTypeToFile(synth.getCompressorSyncType())),
                false);
          }
          writer.writeAttribute(
              "attack", String.valueOf((int) (compAttack * Integer.MAX_VALUE)), false);
          writer.writeAttribute(
              "release", String.valueOf((int) (compRelease * Integer.MAX_VALUE)), false);
          writer.closeTag();
        }
        if (synth.getSidechainAttackRaw() >= 0
            || synth.getSidechainReleaseRaw() >= 0
            || synth.getSidechainAttack() > 0
            || synth.getSidechainRelease() > 0
            || synth.getSidechainSyncLevel() > 0
            || synth.getSidechainSyncType() > 0) {
          writer.writeOpeningTagBeginning("sidechain");
          writer.writeAttribute("syncLevel", String.valueOf(synth.getSidechainSyncLevel()), false);
          if (synth.getSidechainSyncType() > 0) {
            writer.writeAttribute(
                "syncType",
                String.valueOf(
                    org.deluge.xml.DelugeXmlUtil.syncTypeToFile(synth.getSidechainSyncType())),
                false);
          }
          if (synth.getSidechainAttackRaw() >= 0 || synth.getSidechainAttack() > 0) {
            writer.writeAttribute(
                "attack",
                synth.getSidechainAttackRaw() >= 0
                    ? String.valueOf(synth.getSidechainAttackRaw())
                    : String.valueOf(
                        (int) Math.round(synth.getSidechainAttack() * Integer.MAX_VALUE)),
                false);
          }
          if (synth.getSidechainReleaseRaw() >= 0 || synth.getSidechainRelease() > 0) {
            writer.writeAttribute(
                "release",
                synth.getSidechainReleaseRaw() >= 0
                    ? String.valueOf(synth.getSidechainReleaseRaw())
                    : String.valueOf(
                        (int) Math.round(synth.getSidechainRelease() * Integer.MAX_VALUE)),
                false);
          }
          writer.closeTag();
        }

        // defaultParams
        var synthRaw = synth.getRawKnobs();
        var synthRawMap = synthRaw.getRawParamKnobs();
        writer.writeOpeningTagBeginning("defaultParams");
        writer.writeOpeningTagEnd();
        writeHexTagUnipolar(writer, "arpeggiatorGate", synth.getArp().gate());
        writeRawQ31Tag(
            writer,
            "portamento",
            synthRawMap.getOrDefault(
                org.deluge.firmware2.Param.UNPATCHED_PORTAMENTO, synth.getPortamentoQ31()));
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.UNPATCHED_SIDECHAIN_SHAPE)) {
          writeRawQ31Tag(
              writer,
              "compressorShape",
              synthRawMap.get(org.deluge.firmware2.Param.UNPATCHED_SIDECHAIN_SHAPE));
        } else {
          writeHexTagUnipolar(writer, "compressorShape", synth.getCompressorShape());
        }
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.UNPATCHED_COMPRESSOR_THRESHOLD)) {
          writeRawQ31Tag(
              writer,
              "compressorThreshold",
              synthRawMap.get(org.deluge.firmware2.Param.UNPATCHED_COMPRESSOR_THRESHOLD));
        } else {
          writeHexTagUnipolar(writer, "compressorThreshold", synth.getCompressorThreshold());
        }
        // Write independent oscillator volumes (C sound.cpp:4072-4073).
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.LOCAL_OSC_A_VOLUME)) {
          writeRawQ31Tag(
              writer, "oscAVolume", synthRawMap.get(org.deluge.firmware2.Param.LOCAL_OSC_A_VOLUME));
        } else {
          writeHexTagUnipolar(writer, "oscAVolume", synth.getOscAVolume());
        }
        writeRawQ31Tag(
            writer,
            "oscAPulseWidth",
            synthRawMap.getOrDefault(
                org.deluge.firmware2.Param.LOCAL_OSC_A_PHASE_WIDTH,
                synth.getOsc1PhaseWidthQ31() != Integer.MIN_VALUE
                    ? synth.getOsc1PhaseWidthQ31()
                    : 0));
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.LOCAL_OSC_A_WAVE_INDEX)) {
          writeRawQ31Tag(
              writer,
              "oscAWavetablePosition",
              synthRawMap.get(org.deluge.firmware2.Param.LOCAL_OSC_A_WAVE_INDEX));
        } else {
          writeHexTagUnipolar(writer, "oscAWavetablePosition", synth.getWaveIndex());
        }
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.LOCAL_OSC_B_VOLUME)) {
          writeRawQ31Tag(
              writer, "oscBVolume", synthRawMap.get(org.deluge.firmware2.Param.LOCAL_OSC_B_VOLUME));
        } else {
          writeHexTagUnipolar(writer, "oscBVolume", synth.getOscBVolume());
        }
        writeRawQ31Tag(
            writer,
            "oscBPulseWidth",
            synthRawMap.getOrDefault(
                org.deluge.firmware2.Param.LOCAL_OSC_B_PHASE_WIDTH,
                synth.getOsc2PhaseWidthQ31() != Integer.MIN_VALUE
                    ? synth.getOsc2PhaseWidthQ31()
                    : 0));
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.LOCAL_OSC_B_WAVE_INDEX)) {
          writeRawQ31Tag(
              writer,
              "oscBWavetablePosition",
              synthRawMap.get(org.deluge.firmware2.Param.LOCAL_OSC_B_WAVE_INDEX));
        } else {
          writeHexTagUnipolar(writer, "oscBWavetablePosition", synth.getOsc2WaveIndex());
        }
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.LOCAL_PITCH_ADJUST)) {
          writeRawQ31Tag(
              writer,
              "pitchAdjust",
              synthRawMap.get(org.deluge.firmware2.Param.LOCAL_PITCH_ADJUST));
        } else if (synth.getPitchAdjustQ31() != Integer.MIN_VALUE) {
          writeRawQ31Tag(writer, "pitchAdjust", synth.getPitchAdjustQ31());
        }
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.LOCAL_OSC_A_PITCH_ADJUST)) {
          writeRawQ31Tag(
              writer,
              "oscAPitchAdjust",
              synthRawMap.get(org.deluge.firmware2.Param.LOCAL_OSC_A_PITCH_ADJUST));
        } else if (synth.getOsc1PitchAdjustQ31() != Integer.MIN_VALUE) {
          writeRawQ31Tag(writer, "oscAPitchAdjust", synth.getOsc1PitchAdjustQ31());
        }
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.LOCAL_OSC_B_PITCH_ADJUST)) {
          writeRawQ31Tag(
              writer,
              "oscBPitchAdjust",
              synthRawMap.get(org.deluge.firmware2.Param.LOCAL_OSC_B_PITCH_ADJUST));
        } else if (synth.getOsc2PitchAdjustQ31() != Integer.MIN_VALUE) {
          writeRawQ31Tag(writer, "oscBPitchAdjust", synth.getOsc2PitchAdjustQ31());
        }
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.LOCAL_MODULATOR_0_PITCH_ADJUST)) {
          writeRawQ31Tag(
              writer,
              "mod1PitchAdjust",
              synthRawMap.get(org.deluge.firmware2.Param.LOCAL_MODULATOR_0_PITCH_ADJUST));
        }
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.LOCAL_MODULATOR_1_PITCH_ADJUST)) {
          writeRawQ31Tag(
              writer,
              "mod2PitchAdjust",
              synthRawMap.get(org.deluge.firmware2.Param.LOCAL_MODULATOR_1_PITCH_ADJUST));
        }
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.LOCAL_NOISE_VOLUME)) {
          writeRawQ31Tag(
              writer,
              "noiseVolume",
              synthRawMap.get(org.deluge.firmware2.Param.LOCAL_NOISE_VOLUME));
        } else {
          writeHexTagUnipolar(writer, "noiseVolume", synth.getNoiseVol());
        }
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.GLOBAL_VOLUME_POST_FX)) {
          writeRawQ31Tag(
              writer, "volume", synthRawMap.get(org.deluge.firmware2.Param.GLOBAL_VOLUME_POST_FX));
        } else {
          writeHexTagUnipolar(writer, "volume", synth.getVolume());
        }
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.LOCAL_PAN)) {
          writeRawQ31Tag(writer, "pan", synthRawMap.get(org.deluge.firmware2.Param.LOCAL_PAN));
        } else {
          writeHexTag(writer, "pan", synth.getPan());
        }
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.LOCAL_LPF_FREQ)) {
          writeRawQ31Tag(
              writer, "lpfFrequency", synthRawMap.get(org.deluge.firmware2.Param.LOCAL_LPF_FREQ));
        } else {
          writeHexTagFreq(writer, "lpfFrequency", synth.getLpfFreq());
        }
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.LOCAL_LPF_RESONANCE)) {
          writeRawQ31Tag(
              writer,
              "lpfResonance",
              synthRawMap.get(org.deluge.firmware2.Param.LOCAL_LPF_RESONANCE));
        } else {
          writeHexTagUnipolar(writer, "lpfResonance", synth.getLpfRes());
        }
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.LOCAL_LPF_MORPH)) {
          writeRawQ31Tag(
              writer, "lpfMorph", synthRawMap.get(org.deluge.firmware2.Param.LOCAL_LPF_MORPH));
        } else {
          writeHexTagUnipolar(writer, "lpfMorph", synth.getLpfMorph());
        }
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.LOCAL_HPF_FREQ)) {
          writeRawQ31Tag(
              writer, "hpfFrequency", synthRawMap.get(org.deluge.firmware2.Param.LOCAL_HPF_FREQ));
        } else {
          writeHexTagFreq(writer, "hpfFrequency", synth.getHpfFreq());
        }
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.LOCAL_HPF_RESONANCE)) {
          writeRawQ31Tag(
              writer,
              "hpfResonance",
              synthRawMap.get(org.deluge.firmware2.Param.LOCAL_HPF_RESONANCE));
        } else {
          writeHexTagUnipolar(writer, "hpfResonance", synth.getHpfRes());
        }
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.LOCAL_HPF_MORPH)) {
          writeRawQ31Tag(
              writer, "hpfMorph", synthRawMap.get(org.deluge.firmware2.Param.LOCAL_HPF_MORPH));
        } else {
          writeHexTagUnipolar(writer, "hpfMorph", synth.getHpfMorph());
        }

        // FM params (C sound.cpp:4089-4097 — exact Q31 knobs)
        writeRawQ31Tag(writer, "modulator1Amount", synth.getModulator1AmountQ31());
        writeRawQ31Tag(writer, "modulator1Feedback", synth.getModulator1FeedbackQ31());
        writeRawQ31Tag(writer, "modulator2Amount", synth.getModulator2AmountQ31());
        writeRawQ31Tag(writer, "modulator2Feedback", synth.getModulator2FeedbackQ31());
        writeRawQ31Tag(writer, "carrier1Feedback", synth.getCarrier1FeedbackQ31());
        writeRawQ31Tag(writer, "carrier2Feedback", synth.getCarrier2FeedbackQ31());

        if (synthRawMap.containsKey(org.deluge.firmware2.Param.GLOBAL_MOD_FX_RATE)) {
          writeRawQ31Tag(
              writer, "modFXRate", synthRawMap.get(org.deluge.firmware2.Param.GLOBAL_MOD_FX_RATE));
        } else {
          writeHexTagUnipolar(writer, "modFXRate", synth.getModFxRate());
        }
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.GLOBAL_MOD_FX_DEPTH)) {
          writeRawQ31Tag(
              writer,
              "modFXDepth",
              synthRawMap.get(org.deluge.firmware2.Param.GLOBAL_MOD_FX_DEPTH));
        } else {
          writeHexTagUnipolar(writer, "modFXDepth", synth.getModFxDepth());
        }
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.UNPATCHED_MOD_FX_OFFSET)) {
          writeRawQ31Tag(
              writer,
              "modFXOffset",
              synthRawMap.get(org.deluge.firmware2.Param.UNPATCHED_MOD_FX_OFFSET));
        } else {
          writeHexTagUnipolar(writer, "modFXOffset", synth.getModFxOffset());
        }
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.UNPATCHED_MOD_FX_FEEDBACK)) {
          writeRawQ31Tag(
              writer,
              "modFXFeedback",
              synthRawMap.get(org.deluge.firmware2.Param.UNPATCHED_MOD_FX_FEEDBACK));
        } else {
          writeHexTagUnipolar(writer, "modFXFeedback", synth.getModFxFeedback());
        }
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.GLOBAL_DELAY_RATE)) {
          writeRawQ31Tag(
              writer, "delayRate", synthRawMap.get(org.deluge.firmware2.Param.GLOBAL_DELAY_RATE));
        } else {
          writeHexTagUnipolar(writer, "delayRate", synth.getDelaySend());
        }
        writeRawQ31Tag(writer, "delayFeedback", synth.getDelayFeedbackQ31());
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.GLOBAL_REVERB_AMOUNT)) {
          writeRawQ31Tag(
              writer,
              "reverbAmount",
              synthRawMap.get(org.deluge.firmware2.Param.GLOBAL_REVERB_AMOUNT));
        } else {
          writeHexTagUnipolar(writer, "reverbAmount", synth.getReverbSend());
        }
        // C sound.cpp:4117 — GLOBAL_ARP_RATE raw Q31 param.
        writer.writeTag("arpeggiatorRate", String.format("0x%08X", synth.getArpRateQ31()));
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.UNPATCHED_STUTTER_RATE)) {
          writeRawQ31Tag(
              writer,
              "stutterRate",
              synthRawMap.get(org.deluge.firmware2.Param.UNPATCHED_STUTTER_RATE));
        } else {
          writeHexTagUnipolar(writer, "stutterRate", synth.getStutter().getStutterRate());
        }
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.UNPATCHED_SAMPLE_RATE_REDUCTION)) {
          writeRawQ31Tag(
              writer,
              "sampleRateReduction",
              synthRawMap.get(org.deluge.firmware2.Param.UNPATCHED_SAMPLE_RATE_REDUCTION));
        } else {
          writeHexTagUnipolar(writer, "sampleRateReduction", synth.getSampleRateReduction());
        }
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.UNPATCHED_BITCRUSHING)) {
          writeRawQ31Tag(
              writer,
              "bitCrush",
              synthRawMap.get(org.deluge.firmware2.Param.UNPATCHED_BITCRUSHING));
        } else {
          writeHexTagUnipolar(writer, "bitCrush", synth.getBitCrush());
        }
        writeRawQ31Tag(writer, "waveFold", synth.getWaveFoldQ31());

        // Equalizer
        writer.writeOpeningTagBeginning("equalizer");
        writer.writeOpeningTagEnd();
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.UNPATCHED_BASS)) {
          writeRawQ31Tag(
              writer, "bass", synthRawMap.get(org.deluge.firmware2.Param.UNPATCHED_BASS));
        } else {
          writeHexTag(writer, "bass", synth.getEqBass());
        }
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.UNPATCHED_TREBLE)) {
          writeRawQ31Tag(
              writer, "treble", synthRawMap.get(org.deluge.firmware2.Param.UNPATCHED_TREBLE));
        } else {
          writeHexTag(writer, "treble", synth.getEqTreble());
        }
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.UNPATCHED_BASS_FREQ)) {
          writeRawQ31Tag(
              writer,
              "bassFrequency",
              synthRawMap.get(org.deluge.firmware2.Param.UNPATCHED_BASS_FREQ));
        } else {
          writeHexTag(writer, "bassFrequency", synth.getEqBassFreq());
        }
        if (synthRawMap.containsKey(org.deluge.firmware2.Param.UNPATCHED_TREBLE_FREQ)) {
          writeRawQ31Tag(
              writer,
              "trebleFrequency",
              synthRawMap.get(org.deluge.firmware2.Param.UNPATCHED_TREBLE_FREQ));
        } else {
          writeHexTag(writer, "trebleFrequency", synth.getEqTrebleFreq());
        }
        writer.writeClosingTag("equalizer");

        // Envelopes 1-4
        for (int i = 0; i < 4; i++) {
          if (rawEnvKnobMatchesModel(synth, i)) {
            writer.writeOpeningTagBeginning("envelope" + (i + 1));
            writer.writeOpeningTagEnd();
            writeRawQ31Tag(writer, "attack", synthRaw.getEnvAttackKnobQ31(i));
            writeRawQ31Tag(writer, "decay", synthRaw.getEnvDecayKnobQ31(i));
            writeRawQ31Tag(writer, "sustain", synthRaw.getEnvSustainKnobQ31(i));
            writeRawQ31Tag(writer, "release", synthRaw.getEnvReleaseKnobQ31(i));
            writer.writeClosingTag("envelope" + (i + 1));
          } else {
            serializeEnvelope(writer, "envelope" + (i + 1), synth.getEnv(i));
          }
        }

        // LFO rates 1..4 (C sound.cpp:4084-4087)
        for (int i = 0; i < 4; i++) {
          String rateTag = "lfo" + (i + 1) + "Rate";
          if (synthRaw.isLfoRateKnobSet(i)) {
            writeRawQ31Tag(writer, rateTag, synthRaw.getLfoRateKnobQ31(i));
          } else {
            writeHexTagLfoFreq(writer, rateTag, synth.getLfo(i).rateHz());
          }
        }

        // Patch cables (C sound.cpp:4159 -> writePatchCablesToFile(..., writeAutomation = false))
        if (!synth.getModulation().getPatchCables().isEmpty()) {
          writer.writeArrayStart("patchCables");
          for (PatchCable pc : synth.getModulation().getPatchCables()) {
            writePatchCable(writer, pc, false);
          }
          writer.writeArrayEnding("patchCables");
        }

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

    // ── sessionClips block (C song.cpp:1313-1318) ──
    writer.writeArrayStart("sessionClips");
    trackIndex = 0;
    for (TrackModel track : model.getTracks()) {
      if (track instanceof AudioTrackModel audioTrack) {
        for (AudioTrackModel.AudioClip clip : audioTrack.getAudioClips()) {
          if (sessionClipIndices.containsKey(clip)) {
            serializeAudioClip(writer, audioTrack, clip, false, null);
          }
        }
      } else {
        for (ClipModel clip : track.getClips()) {
          if (sessionClipIndices.containsKey(clip)) {
            writeInstrumentClip(writer, track, clip, false, model.getInputTickMagnitude());
          }
        }
      }
      trackIndex++;
    }
    writer.writeArrayEnding("sessionClips");

    // ── arrangementOnlyTracks block (C song.cpp:1320-1332) — clips that live only on the
    // arranger timeline, not in the session grid. Orphans without a ClipInstance on their track
    // are skipped when an arrangement timeline is present (C song.cpp:1324-1326; standalone
    // empty-timeline fixtures retain them for XML round-trip tests). ──
    if (!arrangementClipIndices.isEmpty()) {
      writer.writeArrayStart("arrangementOnlyTracks");
      for (TrackModel track : model.getTracks()) {
        if (track instanceof AudioTrackModel audioTrack) {
          for (AudioTrackModel.AudioClip clip : audioTrack.getAudioClips()) {
            if (arrangementClipIndices.containsKey(clip)) {
              serializeAudioClip(writer, audioTrack, clip, true, null);
            }
          }
        } else {
          for (ClipModel clip : track.getClips()) {
            if (arrangementClipIndices.containsKey(clip)) {
              writeInstrumentClip(writer, track, clip, true, model.getInputTickMagnitude());
            }
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
   * The 32-bit clipCode the hardware stores per clipInstance (C output.cpp:290-300): {@code
   * 0xFFFFFFFF} when the instance references no clip; otherwise {@code indexForSaving} in {@code
   * sessionClips} (bit 31 clear) or {@code indexForSaving | (1 << 31)} in {@code
   * arrangementOnlyTracks} (bit 31 set).
   */
  private static int clipCodeFor(
      ArrangerClip ac,
      Map<Object, Integer> sessionClipIndices,
      Map<Object, Integer> arrangementClipIndices) {
    Object target = ac.clip() != null ? ac.clip() : ac.audioClip();
    if (target == null) {
      return 0xFFFFFFFF;
    }
    Integer arrIdx = arrangementClipIndices.get(target);
    if (arrIdx != null) {
      return arrIdx | (1 << 31);
    }
    Integer sessIdx = sessionClipIndices.get(target);
    if (sessIdx != null) {
      return sessIdx;
    }
    return 0xFFFFFFFF;
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
      // C SyncType file value (TRIPLET 10, DOTTED 19), not the model's 1 / 2.
      writer.writeTag(
          "syncType", String.valueOf(org.deluge.xml.DelugeXmlUtil.syncTypeToFile(lfo.syncType())));
    }
    writer.writeClosingTag(tag);
  }

  private static String lfoTypeName(LfoType type) {
    return org.deluge.xml.DelugeXmlUtil.lfoTypeToFirmwareString(type);
  }

  private static void serializeEnvelope(XMLSerializer writer, String tag, EnvelopeModel env)
      throws IOException {
    writer.writeOpeningTagBeginning(tag);
    writer.writeOpeningTagEnd();
    writer.writeTag("attack", DelugeHexMapper.envTimeToHex(env.attack()));
    writer.writeTag("decay", DelugeHexMapper.envTimeToHex(env.decay()));
    writer.writeTag("sustain", DelugeHexMapper.sustainToHex(env.sustain()));
    writer.writeTag("release", DelugeHexMapper.envTimeToHex(env.release()));
    writer.writeClosingTag(tag);
  }

  private static void writeHexTag(XMLSerializer writer, String tag, float val) throws IOException {
    writer.writeTag(tag, DelugeHexMapper.floatToHex(val));
  }

  private static void writePatchCable(XMLSerializer writer, PatchCable pc) throws IOException {
    writePatchCable(writer, pc, false);
  }

  private static void writePatchCable(XMLSerializer writer, PatchCable pc, boolean writeAutomation)
      throws IOException {
    writer.writeOpeningTagBeginning("patchCable");
    writer.writeOpeningTagEnd();
    writer.writeTag("source", pc.source());
    if (pc.destination() != null && !pc.destination().isEmpty()) {
      writer.writeTag("destination", pc.destination());
    }
    String baseHex = pc.resolveBaseHex();
    String amountHex =
        writeAutomation
            ? DelugeHexMapper.formatHexWithAutomationNodes(baseHex, pc.automationNodes())
            : baseHex;
    writer.writeTag("amount", amountHex);
    if (pc.polarity() != null) {
      writer.writeTag("polarity", pc.polarity().name().toLowerCase());
    }
    if (pc.depthControlledBy() != null && !pc.depthControlledBy().isEmpty()) {
      writer.writeArrayStart("depthControlledBy");
      for (PatchCable dc : pc.depthControlledBy()) {
        writePatchCable(writer, dc, writeAutomation);
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
    writer.writeTag(tag, DelugeHexMapper.unipolarFloatToHexUnified(val));
  }

  private static void writeHexTagFreq(XMLSerializer writer, String tag, float hz)
      throws IOException {
    writer.writeTag(tag, DelugeHexMapper.hzToHex(hz));
  }

  private static void writeHexTagLfoFreq(XMLSerializer writer, String tag, float hz)
      throws IOException {
    writer.writeTag(tag, DelugeHexMapper.lfoHzToHex(hz));
  }

  private static void writeDrumRawOrUnipolar(
      XMLSerializer writer, SoundDrum drum, String tag, float fallback) throws IOException {
    Integer raw = drum.getRawDefaultParamQ31(tag);
    if (raw != null) {
      writeRawQ31Tag(writer, tag, raw);
    } else {
      writeHexTagUnipolar(writer, tag, fallback);
    }
  }

  private static void writeDrumRawOrBipolar(
      XMLSerializer writer, SoundDrum drum, String tag, String rawKey, float fallback)
      throws IOException {
    Integer raw = drum.getRawDefaultParamQ31(rawKey);
    if (raw != null) {
      writeRawQ31Tag(writer, tag, raw);
    } else {
      writeHexTag(writer, tag, fallback);
    }
  }

  private static void writeDrumRawOrFreq(
      XMLSerializer writer, SoundDrum drum, String tag, float fallbackHz) throws IOException {
    Integer raw = drum.getRawDefaultParamQ31(tag);
    if (raw != null) {
      writeRawQ31Tag(writer, tag, raw);
    } else {
      writeHexTagFreq(writer, tag, fallbackHz);
    }
  }

  private static int floatToRawInt(float v) {
    long rounded = Math.round((double) v * (double) Integer.MAX_VALUE);
    return (int) Math.max((long) Integer.MIN_VALUE, Math.min((long) Integer.MAX_VALUE, rounded));
  }

  private static void serializeGlobalEffects(XMLSerializer writer, ProjectModel model)
      throws IOException {
    // 1. Reverb (C song.cpp:1240-1269)
    writer.writeOpeningTagBeginning("reverb");
    writer.writeAttribute("roomSize", Math.max(0, floatToRawInt(model.getReverbRoomSize())), false);
    writer.writeAttribute(
        "dampening", Math.max(0, floatToRawInt(model.getReverbDampening())), false);
    writer.writeAttribute("width", Math.max(0, floatToRawInt(model.getReverbWidth())), false);
    writer.writeAttribute("hpf", Math.max(0, floatToRawInt(model.getReverbHpf())), false);
    writer.writeAttribute("lpf", Math.max(0, floatToRawInt(model.getReverbLpf())), false);
    writer.writeAttribute("pan", floatToRawInt(model.getReverbPan()), false);
    writer.writeAttribute("model", model.getReverbModel(), false);
    writer.writeOpeningTagEnd();

    // C song.cpp:1261-1267 — nested <compressor> inside <reverb> has attack, release, volume,
    // shape, syncLevel (no compHPF or compBlend).
    writer.writeOpeningTagBeginning("compressor");
    writer.writeAttribute(
        "attack", Math.max(0, floatToRawInt(model.getReverbCompressorAttack())), false);
    writer.writeAttribute(
        "release", Math.max(0, floatToRawInt(model.getReverbCompressorRelease())), false);
    writer.writeAttribute("volume", floatToRawInt(model.getReverbCompressorVolume()), false);
    writer.writeAttribute("shape", floatToRawInt(model.getReverbCompressorShape()), false);
    writer.writeAttribute("syncLevel", model.getReverbCompressorSyncLevel(), false);
    writer.closeTag();

    writer.writeClosingTag("reverb");

    // 2. Delay (C mod_controllable_audio.cpp:419-425)
    writer.writeOpeningTagBeginning("delay");
    writer.writeAttribute("pingPong", model.getDelayPingPong(), false);
    writer.writeAttribute("analog", model.getDelayAnalog(), false);
    writer.writeAttribute("syncLevel", model.getDelaySyncLevel(), false);
    writer.writeAttribute(
        "syncType", org.deluge.xml.DelugeXmlUtil.syncTypeToFile(model.getDelaySyncType()), false);
    writer.closeTag();

    // 3. Sidechain (C mod_controllable_audio.cpp:463-469)
    writer.writeOpeningTagBeginning("sidechain");
    writer.writeAttribute("attack", Math.max(0, floatToRawInt(model.getSidechainAttack())), false);
    writer.writeAttribute(
        "release", Math.max(0, floatToRawInt(model.getSidechainRelease())), false);
    writer.writeAttribute("syncLevel", model.getSidechainSyncLevel(), false);
    writer.writeAttribute(
        "syncType",
        org.deluge.xml.DelugeXmlUtil.syncTypeToFile(model.getSidechainSyncType()),
        false);
    writer.closeTag();

    // 4. Audio Compressor (C mod_controllable_audio.cpp:472-479)
    writer.writeOpeningTagBeginning("audioCompressor");
    writer.writeAttribute("attack", Math.max(0, floatToRawInt(model.getCompressorAttack())), false);
    writer.writeAttribute(
        "release", Math.max(0, floatToRawInt(model.getCompressorRelease())), false);
    writer.writeAttribute(
        "thresh", Math.max(0, floatToRawInt(model.getCompressorThreshold())), false);
    writer.writeAttribute("ratio", Math.max(0, floatToRawInt(model.getCompressorRatio())), false);
    writer.writeAttribute("compHPF", Math.max(0, floatToRawInt(model.getCompressorHpf())), false);
    writer.writeAttribute(
        "compBlend", Math.max(0, floatToRawInt(model.getCompressorBlend())), false);
    writer.closeTag();

    // 5. Stutter (C mod_controllable_audio.cpp:481-486)
    writer.writeOpeningTagBeginning("stutter");
    writer.writeAttribute("quantized", model.isSongStutterQuantized() ? 1 : 0, false);
    writer.writeAttribute("reverse", model.isSongStutterReversed() ? 1 : 0, false);
    writer.writeAttribute("pingPong", model.isSongStutterPingPong() ? 1 : 0, false);
    writer.closeTag();

    // 6. SongParams (C song.cpp:1275-1279, global_effectable.cpp:811-882).
    writer.writeOpeningTagBeginning("songParams");
    Map<String, List<org.deluge.modulation.automation.ParamNode>> curves =
        model.getSongParamAutomationCurves();
    Map<String, String> rawHex = model.getRawSongParamHex();
    java.util.Set<String> written = new java.util.HashSet<>();

    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "reverbAmount",
        rawHex.getOrDefault(
            "reverbAmount",
            DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamReverbAmount())));
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "volume",
        rawHex.getOrDefault(
            "volume", DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamVolume())));
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "pan",
        rawHex.getOrDefault("pan", DelugeHexMapper.floatToHex(model.getSongParamPan())));
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "sidechainCompressorShape",
        rawHex.getOrDefault(
            "sidechainCompressorShape",
            DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamSidechainShape())));
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "sidechainCompressorVolume",
        rawHex.getOrDefault(
            "sidechainCompressorVolume",
            DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamSidechainVolume())));
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "modFXRate",
        rawHex.getOrDefault(
            "modFXRate", DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamModFXRate())));
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "modFXDepth",
        rawHex.getOrDefault(
            "modFXDepth",
            DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamModFXDepth())));
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "modFXOffset",
        rawHex.getOrDefault(
            "modFXOffset",
            DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamModFXOffset())));
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "modFXFeedback",
        rawHex.getOrDefault(
            "modFXFeedback",
            DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamModFXFeedback())));
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "stutterRate",
        rawHex.getOrDefault(
            "stutterRate",
            DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamStutterRate())));
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "sampleRateReduction",
        rawHex.getOrDefault(
            "sampleRateReduction",
            DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamSampleRateReduction())));
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "bitCrush",
        rawHex.getOrDefault(
            "bitCrush", DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamBitCrush())));
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "compressorThreshold",
        rawHex.getOrDefault(
            "compressorThreshold",
            DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamCompressorThreshold())));
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "lpfMorph",
        rawHex.getOrDefault(
            "lpfMorph", DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamLpfMorph())));
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "hpfMorph",
        rawHex.getOrDefault(
            "hpfMorph", DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamHpfMorph())));
    // C global_effectable.cpp:848, song.cpp:3121 — UNPATCHED_TEMPO (BPM * 100) on <songParams>.
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "tempo",
        rawHex.getOrDefault("tempo", DelugeHexMapper.q31ToHex((int) (model.getBpm() * 100.0f))));

    java.util.Set<String> allSongKeys = new java.util.LinkedHashSet<>();
    allSongKeys.addAll(rawHex.keySet());
    allSongKeys.addAll(curves.keySet());
    for (String key : allSongKeys) {
      if (written.contains(key) || isGlobalEffectableSubElementKey(key)) {
        continue;
      }
      writeGlobalEffectableAttr(
          writer,
          curves,
          written,
          key,
          rawHex.getOrDefault(key, defaultGlobalEffectableBaseHex(key)));
    }

    writeGlobalEffectableSubElementsAndClose(
        writer,
        "songParams",
        key -> resolveSongParamSubElementBaseHex(model, rawHex, key),
        allSongKeys,
        curves,
        true);
  }

  private static String resolveSongParamSubElementBaseHex(
      ProjectModel model, Map<String, String> rawHex, String key) {
    String raw = rawHex.get(key);
    if (raw != null) {
      return raw;
    }
    return switch (key) {
      case "delayRate" -> DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamDelayRate());
      case "delayFeedback" ->
          DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamDelayFeedback());
      case "lpfFrequency" -> DelugeHexMapper.hzToHex(model.getSongParamLpfFrequency());
      case "lpfResonance" ->
          DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamLpfResonance());
      case "hpfFrequency" -> DelugeHexMapper.hzToHex(model.getSongParamHpfFrequency());
      case "hpfResonance" ->
          DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamHpfResonance());
      case "eqBass" -> DelugeHexMapper.floatToHex(model.getSongParamEqBass());
      case "eqTreble" -> DelugeHexMapper.floatToHex(model.getSongParamEqTreble());
      case "eqBassFrequency" ->
          DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamEqBassFrequency());
      case "eqTrebleFrequency" ->
          DelugeHexMapper.unipolarFloatToHexUnified(model.getSongParamEqTrebleFrequency());
      default -> defaultGlobalEffectableBaseHex(key);
    };
  }

  /**
   * Writes a single {@code <instrumentClip>} (shared by the sessionClips and arrangementOnlyTracks
   * blocks). When {@code arrangementOnly} a {@code trackName} attribute is added so the parser can
   * match the clip back to its track (DelugeXmlParser arrangement-only path).
   */
  private static void writeInstrumentClip(
      XMLSerializer writer,
      TrackModel track,
      ClipModel clip,
      boolean arrangementOnly,
      int currentInputTickMagnitude)
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
    } else if (track instanceof org.deluge.model.MidiTrackModel midiTrack) {
      // C instrument_clip.cpp:2357-2362 — a MIDI clip links to its output by channel (0..15, or
      // MIDI_CHANNEL_MPE_LOWER_ZONE 16 / UPPER 17), not by preset name.
      int ch =
          midiTrack.isMpe()
              ? ("upper".equalsIgnoreCase(midiTrack.getMpeZone()) ? 17 : 16)
              : midiTrack.getMidiChannel() - 1;
      writer.writeAttribute("midiChannel", ch, false);
    } else {
      writer.writeAttribute("instrumentPresetName", track.getName(), false);
      writer.writeAttribute("instrumentPresetFolder", "SYNTHS", false);
    }

    int stepTicks = clip.isTripletMode() ? 32 : 24;
    int lengthTicks = clip.getStepCount() * stepTicks;
    writer.writeAttribute("length", lengthTicks, false);
    // C clip.cpp:662-663 — isPlaying == activeIfNoSolo, isSoloing == soloingInSessionMode.
    // All-active = every clip fires at once in session view; arranger songs keep these inactive.
    writer.writeAttribute("isPlaying", clip.isActiveInSession() ? "1" : "0", false);
    writer.writeAttribute("isSoloing", clip.isSoloingInSessionMode() ? "1" : "0", false);
    // C clip.cpp:670-672, song.cpp:2135 — write section only for session clips (!= 255).
    if (!arrangementOnly && clip.getSection() != 255) {
      writer.writeAttribute("section", clip.getSection(), false);
    }
    // C clip.cpp:687-689 — written only when not DEFAULT
    if (!"default".equals(clip.getLaunchStyle())) {
      writer.writeAttribute("launchStyle", clip.getLaunchStyle(), false);
    }
    // C instrument_clip.cpp:2364-2373 — MIDI clips write their PGM triple when set (128 = none)
    if (track instanceof org.deluge.model.MidiTrackModel) {
      if (clip.getMidiBank() != 128) writer.writeAttribute("midiBank", clip.getMidiBank(), false);
      if (clip.getMidiSub() != 128) writer.writeAttribute("midiSub", clip.getMidiSub(), false);
      if (clip.getMidiPGM() != 128) writer.writeAttribute("midiPGM", clip.getMidiPGM(), false);
    }
    if (clip.isTripletMode()) {
      writer.writeAttribute("triplet", "1", false);
    }
    if (clip.getPlayDirection() != null
        && clip.getPlayDirection() != ClipModel.PlayDirection.FORWARD) {
      // C sequenceDirectionModeToString (util/functions.cpp): "reverse" / "pingpong". The enum
      // name lower-cased gave "ping_pong", which stringToSequenceDirectionMode reads as FORWARD.
      String dir =
          switch (clip.getPlayDirection()) {
            case PING_PONG -> "pingpong";
            default -> clip.getPlayDirection().name().toLowerCase();
          };
      writer.writeAttribute("sequenceDirection", dir, false);
    }
    writer.writeOpeningTagEnd();

    // Write soundParams / kitParams / midiParams tag to set outputTypeWhileLoading and serialize
    // track-level performance settings
    if (track instanceof KitTrackModel) {
      serializeKitParams(writer, (KitTrackModel) track, clip);
    } else if (track instanceof SynthTrackModel) {
      serializeSoundParams(writer, (SynthTrackModel) track, clip);
    } else if (track instanceof org.deluge.model.MidiTrackModel) {
      // C instrument_clip.cpp:2400-2402, 2414-2425 — MIDI_OUT clips write <midiParams> (when
      // non-empty) and never write <soundParams>.
      serializeMidiParams(writer, clip);
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
      if (!hasNotes) {
        org.deluge.model.NoteRowModel rm = clip.getNoteRowsMap().get(r);
        if (rm != null) {
          if (rm.getExpressionParams().containsSomething()
              || rm.getLoopLengthIfIndependent() > 0
              || rm.getSequenceDirectionMode() != org.deluge.model.SequenceDirection.OBEY_PARENT
              || rm.isMuted()) {
            hasNotes = true;
          } else if (track instanceof KitTrackModel
              && (!rm.getAutomationCurves().isEmpty()
                  || !rm.getSoundParams().isEmpty()
                  || !rm.getPatchCables().isEmpty()
                  || rm.getBendRange() != -1)) {
            hasNotes = true;
          }
        }
      }
      if (hasNotes) break;
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
          org.deluge.model.NoteRowModel exprCheckRow = clip.getNoteRowsMap().get(r);
          if (exprCheckRow != null && exprCheckRow.getPitch() >= 0) {
            yNote = exprCheckRow.getPitch();
          }
          boolean hasRowMetadata =
              exprCheckRow != null
                  && (exprCheckRow.getExpressionParams().containsSomething()
                      || exprCheckRow.getLoopLengthIfIndependent() > 0
                      || exprCheckRow.getSequenceDirectionMode()
                          != org.deluge.model.SequenceDirection.OBEY_PARENT
                      || exprCheckRow.isMuted());
          if (!hasActive && !hasRowMetadata) {
            continue;
          }
        }

        writer.writeOpeningTagBeginning("noteRow");
        if (track instanceof KitTrackModel) {
          writer.writeAttribute("drumIndex", r, false);
          // C note_row.cpp:3622-3625, param_set.cpp:580-583 — a kit row with expression params
          // writes its bend range (drum finger-level default = 2 when unset).
          org.deluge.model.NoteRowModel bendRow = clip.getNoteRowsMap().get(r);
          if (bendRow != null
              && (bendRow.getBendRange() != -1
                  || bendRow.getExpressionParams().containsSomething())) {
            writer.writeAttribute(
                "bendRange", bendRow.getBendRange() != -1 ? bendRow.getBendRange() : 2, false);
          }
        } else {
          writer.writeAttribute("y", Math.max(yNote, 0), false);
        }
        // C note_row.cpp:3571-3585 — written only when set / not OBEY_PARENT.
        org.deluge.model.NoteRowModel rowModel = clip.getNoteRowsMap().get(r);
        if (rowModel != null && rowModel.isMuted()) {
          writer.writeAttribute("muted", 1, false);
        }
        if (rowModel != null && rowModel.getLoopLengthIfIndependent() > 0) {
          writer.writeAttribute("length", rowModel.getLoopLengthIfIndependent(), false);
        }
        if (rowModel != null
            && rowModel.getSequenceDirectionMode()
                != org.deluge.model.SequenceDirection.OBEY_PARENT) {
          writer.writeAttribute(
              "sequenceDirection", rowModel.getSequenceDirectionMode().toFirmwareString(), false);
        }

        List<StepData> row = new ArrayList<>();
        for (int s = 0; s < clip.getStepCount(); s++) {
          row.add(clip.getStep(r, s));
        }
        String hexData = DelugeNoteDataMapper.encodeRow(row, stepTicks);
        String hexDataSplit = DelugeNoteDataMapper.encodeRowSplit(row, stepTicks);
        writer.writeAttribute("noteDataWithLift", hexData, false);
        writer.writeAttribute("noteDataWithSplitProb", hexDataSplit, false);
        boolean hasRowSoundParams =
            track instanceof KitTrackModel
                && rowModel != null
                && (!rowModel.getAutomationCurves().isEmpty()
                    || !rowModel.getSoundParams().isEmpty()
                    || !rowModel.getPatchCables().isEmpty());
        boolean hasRowExpression =
            rowModel != null && rowModel.getExpressionParams().containsSomething();
        if (hasRowSoundParams || hasRowExpression) {
          // C note_row.cpp:3629-3653 — write child <soundParams> and/or <expressionData>.
          writer.writeOpeningTagEnd();
          if (hasRowSoundParams) {
            serializeKitRowSoundParams(writer, rowModel);
          }
          if (hasRowExpression) {
            writeExpressionData(writer, rowModel.getExpressionParams());
          }
          writer.writeClosingTag("noteRow");
        } else {
          writer.closeTag();
        }
      }
      writer.writeArrayEnding("noteRows");
    }

    // C instrument_clip.cpp:2427-2435 — non-kit clips write <expressionData> + both bend ranges.
    if (!(track instanceof KitTrackModel)) {
      if (clip.getExpressionParams().containsSomething()) {
        writeExpressionData(writer, clip.getExpressionParams());
      }
      if (clip.getBendRange() != -1
          || clip.getBendRangeMPE() != -1
          || clip.getExpressionParams().containsSomething()) {
        writer.writeTag(
            "bendRange", String.valueOf(clip.getBendRange() != -1 ? clip.getBendRange() : 2));
        writer.writeTag(
            "bendRangeMPE",
            String.valueOf(clip.getBendRangeMPE() != -1 ? clip.getBendRangeMPE() : 48));
      }
    }

    // C instrument_clip.cpp:2404-2412 — every instrument clip carries its own <arpeggiator>: a
    // clip's arpSettings come only from it (the <sound>'s copy is the instrument's
    // defaultArpSettings, used for new clips). Without it the hardware plays the clip's arp OFF.
    if (track instanceof SynthTrackModel synthTrack) {
      org.deluge.model.ArpModel clipArp =
          clip.getArp() != null ? clip.getArp() : synthTrack.getDefaultArp();
      if (clipArp != null) {
        writeClipArpeggiator(writer, clipArp, currentInputTickMagnitude);
      }
    } else if (track instanceof KitTrackModel kitTrack) {
      org.deluge.model.ArpModel clipArp = clip.getArp() != null ? clip.getArp() : kitTrack.getArp();
      if (clipArp != null) {
        writeClipArpeggiator(writer, clipArp, currentInputTickMagnitude);
      }
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

  /**
   * C {@code ExpressionParamSet::writeToFile} ({@code param_set.cpp:647-671}) — writes {@code
   * <expressionData pitchBend="0x..." yExpression="0x..." pressure="0x..."/>} for dimensions where
   * {@code containsSomething(0)} is true.
   */
  private static void writeExpressionData(
      XMLSerializer writer, org.deluge.modulation.params.ExpressionParamSet expressionParams)
      throws IOException {
    if (expressionParams == null || !expressionParams.containsSomething()) {
      return;
    }
    writer.writeOpeningTagBeginning("expressionData");
    for (int p = 0; p < org.deluge.modulation.params.ExpressionParamSet.NUM_DIMENSIONS; p++) {
      org.deluge.modulation.automation.AutoParam param = expressionParams.getParam(p);
      if (param.containsSomething(0)) {
        String baseHex = DelugeHexMapper.q31ToHex(param.currentValue);
        String fullHex = DelugeHexMapper.formatHexWithAutomationNodes(baseHex, param.nodes);
        writer.writeAttribute(
            org.deluge.modulation.params.ExpressionParamSet.PARAM_NAMES[p], fullHex, false);
      }
    }
    writer.closeTag();
  }

  /**
   * C ArpeggiatorSettings::writeCommonParamsToFile(writer, nullptr) (arpeggiator.cpp:1847-1915) as
   * a clip writes it (instrument_clip.cpp:2406): syncLevel is the INTERNAL value (no song
   * conversion), syncType the raw enum.
   */
  private static void writeSoundArpeggiator(
      XMLSerializer writer, org.deluge.model.ArpModel arp, int inputTickMagnitude)
      throws IOException {
    if (arp == null) {
      return;
    }
    writer.writeOpeningTagBeginning("arpeggiator");
    // C writeCommonParamsToFile (arpeggiator.cpp): "mode" and "arpMode" both carry
    // arpModeToString — "arp" or "off".
    writer.writeAttribute("mode", arp.active() ? "arp" : "off", false);
    writer.writeAttribute("active", arp.active() ? "1" : "0", false);
    writer.writeAttribute(
        org.deluge.xml.ArpXmlCodec.FORMAT_ATTR, org.deluge.xml.ArpXmlCodec.FORMAT_C, false);
    writer.writeAttribute("sequenceLength", arp.seqLength(), false);
    writer.writeAttribute("arpMode", arp.active() ? "arp" : "off", false);
    if (arp.notePattern() != null && !arp.notePattern().isEmpty()) {
      writer.writeAttribute("notePattern", arp.notePattern(), false);
    }
    writer.writeAttribute("chordType", arp.chordType(), false);

    // The ARP panel edits octaves(); numOctaves() is only what was last parsed.
    writer.writeAttribute("numOctaves", arp.octaves(), false);
    writer.writeAttribute(
        "syncType", org.deluge.xml.ArpXmlCodec.syncTypeToFile(arp.syncType()), false);
    writer.writeAttribute(
        "mpeVelocity", org.deluge.xml.ArpXmlCodec.mpeVelocityToFile(arp.mpeVelocity()), false);
    writer.writeAttribute("kitArp", arp.kitArp(), false);
    writer.writeAttribute("randomizerLock", arp.randomizerLock(), false);

    writer.writeAttribute("lastLockedNoteProb", arp.lastLockedNoteProb(), false);
    if (arp.lockedNoteProbArray() != null && !arp.lockedNoteProbArray().isEmpty()) {
      writer.writeAttribute("lockedNoteProbArray", arp.lockedNoteProbArray(), false);
    }

    writer.writeAttribute("lastLockedBassProb", arp.lastLockedBassProb(), false);
    if (arp.lockedBassProbArray() != null && !arp.lockedBassProbArray().isEmpty()) {
      writer.writeAttribute("lockedBassProbArray", arp.lockedBassProbArray(), false);
    }

    writer.writeAttribute("lastLockedSwapProb", arp.lastLockedSwapProb(), false);
    if (arp.lockedSwapProbArray() != null && !arp.lockedSwapProbArray().isEmpty()) {
      writer.writeAttribute("lockedSwapProbArray", arp.lockedSwapProbArray(), false);
    }

    writer.writeAttribute("lastLockedGlideProb", arp.lastLockedGlideProb(), false);
    if (arp.lockedGlideProbArray() != null && !arp.lockedGlideProbArray().isEmpty()) {
      writer.writeAttribute("lockedGlideProbArray", arp.lockedGlideProbArray(), false);
    }

    writer.writeAttribute("lastLockedReverseProb", arp.lastLockedReverseProb(), false);
    if (arp.lockedReverseProbArray() != null && !arp.lockedReverseProbArray().isEmpty()) {
      writer.writeAttribute("lockedReverseProbArray", arp.lockedReverseProbArray(), false);
    }

    writer.writeAttribute("lastLockedChordProb", arp.lastLockedChordProb(), false);
    if (arp.lockedChordProbArray() != null && !arp.lockedChordProbArray().isEmpty()) {
      writer.writeAttribute("lockedChordProbArray", arp.lockedChordProbArray(), false);
    }

    writer.writeAttribute("lastLockedRatchetProb", arp.lastLockedRatchetProb(), false);
    if (arp.lockedRatchetProbArray() != null && !arp.lockedRatchetProbArray().isEmpty()) {
      writer.writeAttribute("lockedRatchetProbArray", arp.lockedRatchetProbArray(), false);
    }

    writer.writeAttribute("lastLockedVelocitySpread", arp.lastLockedVelocitySpread(), false);
    if (arp.lockedVelocitySpreadArray() != null && !arp.lockedVelocitySpreadArray().isEmpty()) {
      writer.writeAttribute("lockedVelocitySpreadArray", arp.lockedVelocitySpreadArray(), false);
    }

    writer.writeAttribute("lastLockedGateSpread", arp.lastLockedGateSpread(), false);
    if (arp.lockedGateSpreadArray() != null && !arp.lockedGateSpreadArray().isEmpty()) {
      writer.writeAttribute("lockedGateSpreadArray", arp.lockedGateSpreadArray(), false);
    }

    writer.writeAttribute("lastLockedOctaveSpread", arp.lastLockedOctaveSpread(), false);
    if (arp.lockedOctaveSpreadArray() != null && !arp.lockedOctaveSpreadArray().isEmpty()) {
      writer.writeAttribute("lockedOctaveSpreadArray", arp.lockedOctaveSpreadArray(), false);
    }
    writer.writeOpeningTagEnd();
    writeHexTag(writer, "rate", arp.rate());
    writer.writeTag("octaves", String.valueOf(arp.octaves()));
    writeHexTag(writer, "gate", arp.gate());
    writer.writeTag(
        "syncLevel",
        String.valueOf(
            org.deluge.xml.ArpXmlCodec.syncFileValueFromDenominator(
                arp.syncLevel(), inputTickMagnitude)));
    writer.writeTag("noteMode", org.deluge.xml.ArpXmlCodec.noteModeToFile(arp.noteMode()));
    writer.writeTag("octaveMode", org.deluge.xml.ArpXmlCodec.octaveModeToFile(arp.octaveMode()));
    writer.writeTag("stepRepeat", String.valueOf(arp.stepRepeat()));
    writer.writeTag("rhythmIndex", String.valueOf(arp.rhythmIndex()));
    writer.writeTag("seqLength", String.valueOf(arp.seqLength()));
    writeHexTag(writer, "octaveSpread", arp.octaveSpread());
    writeHexTag(writer, "gateSpread", arp.gateSpread());
    writeHexTag(writer, "velSpread", arp.velSpread());
    writer.writeTag("ratchetAmount", String.valueOf(arp.ratchetAmount()));
    writer.writeTag("noteProbability", String.valueOf(arp.noteProbability()));
    writer.writeTag("chordPolyphony", String.valueOf(arp.chordPolyphony()));
    writer.writeTag("chordProbability", String.valueOf(arp.chordProbability()));
    writer.writeClosingTag("arpeggiator");
  }

  private static void writeClipArpeggiator(
      XMLSerializer writer, org.deluge.model.ArpModel arp, int inputTickMagnitude)
      throws IOException {
    writer.writeOpeningTagBeginning("arpeggiator");
    String mode = arp.active() ? "arp" : "off";
    writer.writeAttribute("mode", mode, false);
    writer.writeAttribute(
        "syncLevel",
        org.deluge.xml.ArpXmlCodec.internalSyncLevelFromDenominator(
            arp.syncLevel(), inputTickMagnitude),
        false);
    writer.writeAttribute("numOctaves", arp.octaves(), false);
    writer.writeAttribute(
        "syncType", org.deluge.xml.ArpXmlCodec.syncTypeToFile(arp.syncType()), false);
    writer.writeAttribute("arpMode", mode, false);
    writer.writeAttribute("chordType", arp.chordType(), false);
    writer.writeAttribute(
        "noteMode", org.deluge.xml.ArpXmlCodec.noteModeToFile(arp.noteMode()), false);
    writer.writeAttribute(
        "octaveMode", org.deluge.xml.ArpXmlCodec.octaveModeToFile(arp.octaveMode()), false);
    writer.writeAttribute(
        "mpeVelocity", org.deluge.xml.ArpXmlCodec.mpeVelocityToFile(arp.mpeVelocity()), false);
    writer.writeAttribute("stepRepeat", arp.stepRepeat(), false);
    writer.writeAttribute("randomizerLock", arp.randomizerLock(), false);
    writer.writeAttribute("kitArp", arp.kitArp(), false);
    String[][] locked = {
      {"lastLockedNoteProb", String.valueOf(arp.lastLockedNoteProb())},
      {"lockedNoteProbArray", arp.lockedNoteProbArray()},
      {"lastLockedBassProb", String.valueOf(arp.lastLockedBassProb())},
      {"lockedBassProbArray", arp.lockedBassProbArray()},
      {"lastLockedSwapProb", String.valueOf(arp.lastLockedSwapProb())},
      {"lockedSwapProbArray", arp.lockedSwapProbArray()},
      {"lastLockedGlideProb", String.valueOf(arp.lastLockedGlideProb())},
      {"lockedGlideProbArray", arp.lockedGlideProbArray()},
      {"lastLockedReverseProb", String.valueOf(arp.lastLockedReverseProb())},
      {"lockedReverseProbArray", arp.lockedReverseProbArray()},
      {"lastLockedChordProb", String.valueOf(arp.lastLockedChordProb())},
      {"lockedChordProbArray", arp.lockedChordProbArray()},
      {"lastLockedRatchetProb", String.valueOf(arp.lastLockedRatchetProb())},
      {"lockedRatchetProbArray", arp.lockedRatchetProbArray()},
      {"lastLockedVelocitySpread", String.valueOf(arp.lastLockedVelocitySpread())},
      {"lockedVelocitySpreadArray", arp.lockedVelocitySpreadArray()},
      {"lastLockedGateSpread", String.valueOf(arp.lastLockedGateSpread())},
      {"lockedGateSpreadArray", arp.lockedGateSpreadArray()},
      {"lastLockedOctaveSpread", String.valueOf(arp.lastLockedOctaveSpread())},
      {"lockedOctaveSpreadArray", arp.lockedOctaveSpreadArray()},
      {"notePattern", arp.notePattern()},
    };
    for (String[] kv : locked) {
      if (kv[1] != null && !kv[1].isEmpty()) {
        writer.writeAttribute(kv[0], kv[1], false);
      }
    }
    writer.closeTag();
  }

  private static void serializeKitParams(XMLSerializer writer, KitTrackModel kit, ClipModel clip)
      throws IOException {
    writer.writeOpeningTagBeginning("kitParams");
    Map<String, Float> kitParams = clip != null ? clip.getKitParams() : Map.of();
    Map<String, String> rawHex = clip != null ? clip.getRawKitParamHex() : Map.of();
    Map<String, List<org.deluge.modulation.automation.ParamNode>> curves =
        clip != null ? clip.getAutomationCurves() : Map.of();
    java.util.Set<String> written = new java.util.HashSet<>();

    float vol = kitParams.containsKey("volume") ? kitParams.get("volume") : kit.getVolume();
    float pan = kitParams.containsKey("pan") ? kitParams.get("pan") : kit.getPan();
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "volume",
        rawHex.getOrDefault("volume", DelugeHexMapper.unipolarFloatToHexUnified(vol)));
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "pan",
        rawHex.getOrDefault("pan", DelugeHexMapper.floatToHex(pan)));
    if (kitParams.containsKey("reverbAmount")
        || rawHex.containsKey("reverbAmount")
        || curves.containsKey("reverbAmount")) {
      writeGlobalEffectableAttr(
          writer,
          curves,
          written,
          "reverbAmount",
          resolveKitBaseHex(kitParams, rawHex, "reverbAmount", "0x80000000"));
    }

    java.util.Set<String> allKeys = new java.util.LinkedHashSet<>();
    allKeys.addAll(kitParams.keySet());
    allKeys.addAll(rawHex.keySet());
    allKeys.addAll(curves.keySet());
    for (String key : allKeys) {
      if (written.contains(key) || isGlobalEffectableSubElementKey(key)) {
        continue;
      }
      String baseHex =
          resolveKitBaseHex(kitParams, rawHex, key, defaultGlobalEffectableBaseHex(key));
      writeGlobalEffectableAttr(writer, curves, written, key, baseHex);
    }

    writeGlobalEffectableSubElementsAndClose(
        writer,
        "kitParams",
        key -> resolveKitSubElementBaseHex(kitParams, rawHex, key),
        allKeys,
        curves,
        false);
  }

  private static String defaultGlobalEffectableBaseHex(String key) {
    // C GlobalEffectable::initParams (global_effectable.cpp:69-93) +
    // ModControllableAudio::initParams (mod_controllable_audio.cpp:108-140).
    return switch (key) {
      case "delayRate",
          "pan",
          "pitchAdjust",
          "modFXDepth",
          "modFXOffset",
          "compressorThreshold",
          "arpeggiatorRate",
          "arpeggiatorGate",
          "tempo",
          "eqBass",
          "eqTreble",
          "eqBassFrequency",
          "eqTrebleFrequency" ->
          "0x00000000";
      case "modFXRate" -> "0xE0000000";
      case "sidechainCompressorShape" -> "0xDC28F5B2";
      case "noteProbability" -> "0x7FFFFFFF";
      case "lpfFrequency" -> DelugeHexMapper.hzToHex(20000.0f);
      case "hpfFrequency" -> DelugeHexMapper.hzToHex(20.0f);
      default -> "0x80000000";
    };
  }

  private static void writeGlobalEffectableAttr(
      XMLSerializer writer,
      Map<String, List<org.deluge.modulation.automation.ParamNode>> curves,
      java.util.Set<String> written,
      String attrName,
      String baseHex)
      throws IOException {
    writer.writeAttribute(
        attrName,
        DelugeHexMapper.formatHexWithAutomationNodes(baseHex, curves.get(attrName)),
        false);
    written.add(attrName);
  }

  private static boolean isGlobalEffectableSubElementKey(String key) {
    return "delayRate".equals(key)
        || "delayFeedback".equals(key)
        || "lpfFrequency".equals(key)
        || "lpfResonance".equals(key)
        || "hpfFrequency".equals(key)
        || "hpfResonance".equals(key)
        || "eqBass".equals(key)
        || "eqTreble".equals(key)
        || "eqBassFrequency".equals(key)
        || "eqTrebleFrequency".equals(key);
  }

  private static String resolveKitBaseHex(
      Map<String, Float> kitParams, Map<String, String> rawHex, String key, String fallbackHex) {
    String raw = rawHex.get(key);
    if (raw != null) {
      return raw;
    }
    Float val = kitParams.get(key);
    if (val != null) {
      boolean bipolar = "pan".equals(key) || "eqBass".equals(key) || "eqTreble".equals(key);
      return bipolar
          ? DelugeHexMapper.floatToHex(val)
          : DelugeHexMapper.unipolarFloatToHexUnified(val);
    }
    return fallbackHex;
  }

  private static String resolveKitSubElementBaseHex(
      Map<String, Float> kitParams, Map<String, String> rawHex, String key) {
    String raw = rawHex.get(key);
    if (raw != null) {
      return raw;
    }
    Float val = kitParams.get(key);
    if (val != null) {
      if ("lpfFrequency".equals(key) || "hpfFrequency".equals(key)) {
        return DelugeHexMapper.hzToHex(val);
      }
      if ("eqBass".equals(key) || "eqTreble".equals(key)) {
        return DelugeHexMapper.floatToHex(val);
      }
      return DelugeHexMapper.unipolarFloatToHexUnified(val);
    }
    return defaultGlobalEffectableBaseHex(key);
  }

  private static void writeGlobalEffectableSubElementsAndClose(
      XMLSerializer writer,
      String parentTag,
      java.util.function.Function<String, String> baseHexResolver,
      java.util.Set<String> presentKeys,
      Map<String, List<org.deluge.modulation.automation.ParamNode>> curves,
      boolean forceAllSubElements)
      throws IOException {
    boolean hasDelay =
        forceAllSubElements
            || presentKeys.contains("delayRate")
            || presentKeys.contains("delayFeedback");
    boolean hasLpf =
        forceAllSubElements
            || presentKeys.contains("lpfFrequency")
            || presentKeys.contains("lpfResonance");
    boolean hasHpf =
        forceAllSubElements
            || presentKeys.contains("hpfFrequency")
            || presentKeys.contains("hpfResonance");
    boolean hasEq =
        forceAllSubElements
            || presentKeys.contains("eqBass")
            || presentKeys.contains("eqTreble")
            || presentKeys.contains("eqBassFrequency")
            || presentKeys.contains("eqTrebleFrequency");
    if (!hasDelay && !hasLpf && !hasHpf && !hasEq) {
      writer.closeTag();
      return;
    }

    writer.writeOpeningTagEnd();
    if (hasDelay) {
      // C global_effectable.cpp:860-865 — <delay rate="..." feedback="..."/>
      writer.writeOpeningTagBeginning("delay");
      writer.writeAttribute(
          "rate",
          DelugeHexMapper.formatHexWithAutomationNodes(
              baseHexResolver.apply("delayRate"), curves.get("delayRate")),
          false);
      writer.writeAttribute(
          "feedback",
          DelugeHexMapper.formatHexWithAutomationNodes(
              baseHexResolver.apply("delayFeedback"), curves.get("delayFeedback")),
          false);
      writer.closeTag();
    }
    if (hasLpf) {
      // C global_effectable.cpp:867-872 — <lpf frequency="..." resonance="..."/>
      writer.writeOpeningTagBeginning("lpf");
      writer.writeAttribute(
          "frequency",
          DelugeHexMapper.formatHexWithAutomationNodes(
              baseHexResolver.apply("lpfFrequency"), curves.get("lpfFrequency")),
          false);
      writer.writeAttribute(
          "resonance",
          DelugeHexMapper.formatHexWithAutomationNodes(
              baseHexResolver.apply("lpfResonance"), curves.get("lpfResonance")),
          false);
      writer.closeTag();
    }
    if (hasHpf) {
      // C global_effectable.cpp:874-879 — <hpf frequency="..." resonance="..."/>
      writer.writeOpeningTagBeginning("hpf");
      writer.writeAttribute(
          "frequency",
          DelugeHexMapper.formatHexWithAutomationNodes(
              baseHexResolver.apply("hpfFrequency"), curves.get("hpfFrequency")),
          false);
      writer.writeAttribute(
          "resonance",
          DelugeHexMapper.formatHexWithAutomationNodes(
              baseHexResolver.apply("hpfResonance"), curves.get("hpfResonance")),
          false);
      writer.closeTag();
    }
    if (hasEq) {
      // C mod_controllable_audio.cpp:540-549 — <equalizer bass="..." treble="..." .../>
      writer.writeOpeningTagBeginning("equalizer");
      writer.writeAttribute(
          "bass",
          DelugeHexMapper.formatHexWithAutomationNodes(
              baseHexResolver.apply("eqBass"), curves.get("eqBass")),
          false);
      writer.writeAttribute(
          "treble",
          DelugeHexMapper.formatHexWithAutomationNodes(
              baseHexResolver.apply("eqTreble"), curves.get("eqTreble")),
          false);
      writer.writeAttribute(
          "bassFrequency",
          DelugeHexMapper.formatHexWithAutomationNodes(
              baseHexResolver.apply("eqBassFrequency"), curves.get("eqBassFrequency")),
          false);
      writer.writeAttribute(
          "trebleFrequency",
          DelugeHexMapper.formatHexWithAutomationNodes(
              baseHexResolver.apply("eqTrebleFrequency"), curves.get("eqTrebleFrequency")),
          false);
      writer.closeTag();
    }
    writer.writeClosingTag(parentTag);
  }

  private static void serializeSoundParams(
      XMLSerializer writer, SynthTrackModel synth, ClipModel clip) throws IOException {
    writer.writeOpeningTagBeginning("soundParams");
    Map<String, List<org.deluge.modulation.automation.ParamNode>> curves =
        clip != null ? clip.getAutomationCurves() : Map.of();
    java.util.Set<String> written = new java.util.HashSet<>();

    // C Sound::writeParamsToFile (sound.cpp:4061-4127) +
    // ModControllableAudio::writeParamAttributesToFile
    // (mod_controllable_audio.cpp:501-505) — write all clip <soundParams> attributes so
    // InstrumentXmlParser.resetClipParamsToFirmwareDefaults + parseClipSoundParamsStatics
    // round-trips every synth parameter without clobbering FM, oscillator, ModFX, or filter knobs,
    // while keeping ModFX params as XML attributes (never duplicate child tags).
    var rawKnobs = synth.getRawKnobs().getRawParamKnobs();
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "arpeggiatorGate",
        resolveSynthBaseHex(
            synth,
            "arpeggiatorGate",
            DelugeHexMapper.unipolarFloatToHexUnified(synth.getArp().gate())));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "portamento",
        String.format(
            "0x%08X",
            rawKnobs.getOrDefault(
                org.deluge.firmware2.Param.UNPATCHED_PORTAMENTO, synth.getPortamentoQ31())));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "compressorShape",
        rawKnobs.containsKey(org.deluge.firmware2.Param.UNPATCHED_SIDECHAIN_SHAPE)
            ? String.format(
                "0x%08X", rawKnobs.get(org.deluge.firmware2.Param.UNPATCHED_SIDECHAIN_SHAPE))
            : DelugeHexMapper.unipolarFloatToHexUnified(synth.getCompressorShape()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "compressorThreshold",
        rawKnobs.containsKey(org.deluge.firmware2.Param.UNPATCHED_COMPRESSOR_THRESHOLD)
            ? String.format(
                "0x%08X", rawKnobs.get(org.deluge.firmware2.Param.UNPATCHED_COMPRESSOR_THRESHOLD))
            : DelugeHexMapper.unipolarFloatToHexUnified(synth.getCompressorThreshold()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "oscAVolume",
        rawKnobs.containsKey(org.deluge.firmware2.Param.LOCAL_OSC_A_VOLUME)
            ? String.format("0x%08X", rawKnobs.get(org.deluge.firmware2.Param.LOCAL_OSC_A_VOLUME))
            : DelugeHexMapper.unipolarFloatToHexUnified(synth.getOscAVolume()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "oscAPulseWidth",
        String.format(
            "0x%08X",
            rawKnobs.getOrDefault(
                org.deluge.firmware2.Param.LOCAL_OSC_A_PHASE_WIDTH,
                synth.getOsc1PhaseWidthQ31() != Integer.MIN_VALUE
                    ? synth.getOsc1PhaseWidthQ31()
                    : 0)));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "oscAWavetablePosition",
        rawKnobs.containsKey(org.deluge.firmware2.Param.LOCAL_OSC_A_WAVE_INDEX)
            ? String.format(
                "0x%08X", rawKnobs.get(org.deluge.firmware2.Param.LOCAL_OSC_A_WAVE_INDEX))
            : DelugeHexMapper.unipolarFloatToHexUnified(synth.getWaveIndex()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "oscBVolume",
        rawKnobs.containsKey(org.deluge.firmware2.Param.LOCAL_OSC_B_VOLUME)
            ? String.format("0x%08X", rawKnobs.get(org.deluge.firmware2.Param.LOCAL_OSC_B_VOLUME))
            : DelugeHexMapper.unipolarFloatToHexUnified(synth.getOscBVolume()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "oscBPulseWidth",
        String.format(
            "0x%08X",
            rawKnobs.getOrDefault(
                org.deluge.firmware2.Param.LOCAL_OSC_B_PHASE_WIDTH,
                synth.getOsc2PhaseWidthQ31() != Integer.MIN_VALUE
                    ? synth.getOsc2PhaseWidthQ31()
                    : 0)));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "oscBWavetablePosition",
        rawKnobs.containsKey(org.deluge.firmware2.Param.LOCAL_OSC_B_WAVE_INDEX)
            ? String.format(
                "0x%08X", rawKnobs.get(org.deluge.firmware2.Param.LOCAL_OSC_B_WAVE_INDEX))
            : DelugeHexMapper.unipolarFloatToHexUnified(synth.getOsc2WaveIndex()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "pitchAdjust",
        String.format(
            "0x%08X",
            rawKnobs.getOrDefault(
                org.deluge.firmware2.Param.LOCAL_PITCH_ADJUST,
                synth.getPitchAdjustQ31() != Integer.MIN_VALUE ? synth.getPitchAdjustQ31() : 0)));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "oscAPitchAdjust",
        String.format(
            "0x%08X",
            rawKnobs.getOrDefault(
                org.deluge.firmware2.Param.LOCAL_OSC_A_PITCH_ADJUST,
                synth.getOsc1PitchAdjustQ31() != Integer.MIN_VALUE
                    ? synth.getOsc1PitchAdjustQ31()
                    : 0)));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "oscBPitchAdjust",
        String.format(
            "0x%08X",
            rawKnobs.getOrDefault(
                org.deluge.firmware2.Param.LOCAL_OSC_B_PITCH_ADJUST,
                synth.getOsc2PitchAdjustQ31() != Integer.MIN_VALUE
                    ? synth.getOsc2PitchAdjustQ31()
                    : 0)));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "mod1PitchAdjust",
        String.format(
            "0x%08X",
            rawKnobs.getOrDefault(org.deluge.firmware2.Param.LOCAL_MODULATOR_0_PITCH_ADJUST, 0)));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "mod2PitchAdjust",
        String.format(
            "0x%08X",
            rawKnobs.getOrDefault(org.deluge.firmware2.Param.LOCAL_MODULATOR_1_PITCH_ADJUST, 0)));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "noiseVolume",
        rawKnobs.containsKey(org.deluge.firmware2.Param.LOCAL_NOISE_VOLUME)
            ? String.format("0x%08X", rawKnobs.get(org.deluge.firmware2.Param.LOCAL_NOISE_VOLUME))
            : DelugeHexMapper.unipolarFloatToHexUnified(synth.getNoiseVol()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "volume",
        rawKnobs.containsKey(org.deluge.firmware2.Param.GLOBAL_VOLUME_POST_FX)
            ? String.format(
                "0x%08X", rawKnobs.get(org.deluge.firmware2.Param.GLOBAL_VOLUME_POST_FX))
            : DelugeHexMapper.unipolarFloatToHexUnified(synth.getVolume()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "pan",
        rawKnobs.containsKey(org.deluge.firmware2.Param.LOCAL_PAN)
            ? String.format("0x%08X", rawKnobs.get(org.deluge.firmware2.Param.LOCAL_PAN))
            : DelugeHexMapper.floatToHex(synth.getPan()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "lpfFrequency",
        rawKnobs.containsKey(org.deluge.firmware2.Param.LOCAL_LPF_FREQ)
            ? String.format("0x%08X", rawKnobs.get(org.deluge.firmware2.Param.LOCAL_LPF_FREQ))
            : DelugeHexMapper.hzToHex(synth.getLpfFreq()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "lpfResonance",
        rawKnobs.containsKey(org.deluge.firmware2.Param.LOCAL_LPF_RESONANCE)
            ? String.format("0x%08X", rawKnobs.get(org.deluge.firmware2.Param.LOCAL_LPF_RESONANCE))
            : DelugeHexMapper.unipolarFloatToHexUnified(synth.getLpfRes()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "lpfMorph",
        rawKnobs.containsKey(org.deluge.firmware2.Param.LOCAL_LPF_MORPH)
            ? String.format("0x%08X", rawKnobs.get(org.deluge.firmware2.Param.LOCAL_LPF_MORPH))
            : DelugeHexMapper.unipolarFloatToHexUnified(synth.getLpfMorph()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "hpfFrequency",
        rawKnobs.containsKey(org.deluge.firmware2.Param.LOCAL_HPF_FREQ)
            ? String.format("0x%08X", rawKnobs.get(org.deluge.firmware2.Param.LOCAL_HPF_FREQ))
            : DelugeHexMapper.hzToHex(synth.getHpfFreq()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "hpfResonance",
        rawKnobs.containsKey(org.deluge.firmware2.Param.LOCAL_HPF_RESONANCE)
            ? String.format("0x%08X", rawKnobs.get(org.deluge.firmware2.Param.LOCAL_HPF_RESONANCE))
            : DelugeHexMapper.unipolarFloatToHexUnified(synth.getHpfRes()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "hpfMorph",
        rawKnobs.containsKey(org.deluge.firmware2.Param.LOCAL_HPF_MORPH)
            ? String.format("0x%08X", rawKnobs.get(org.deluge.firmware2.Param.LOCAL_HPF_MORPH))
            : DelugeHexMapper.unipolarFloatToHexUnified(synth.getHpfMorph()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "modulator1Amount",
        String.format("0x%08X", synth.getModulator1AmountQ31()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "modulator1Feedback",
        String.format("0x%08X", synth.getModulator1FeedbackQ31()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "modulator2Amount",
        String.format("0x%08X", synth.getModulator2AmountQ31()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "modulator2Feedback",
        String.format("0x%08X", synth.getModulator2FeedbackQ31()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "carrier1Feedback",
        String.format("0x%08X", synth.getCarrier1FeedbackQ31()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "carrier2Feedback",
        String.format("0x%08X", synth.getCarrier2FeedbackQ31()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "modFXRate",
        rawKnobs.containsKey(org.deluge.firmware2.Param.GLOBAL_MOD_FX_RATE)
            ? String.format("0x%08X", rawKnobs.get(org.deluge.firmware2.Param.GLOBAL_MOD_FX_RATE))
            : DelugeHexMapper.unipolarFloatToHexUnified(synth.getModFxRate()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "modFXDepth",
        rawKnobs.containsKey(org.deluge.firmware2.Param.GLOBAL_MOD_FX_DEPTH)
            ? String.format("0x%08X", rawKnobs.get(org.deluge.firmware2.Param.GLOBAL_MOD_FX_DEPTH))
            : DelugeHexMapper.unipolarFloatToHexUnified(synth.getModFxDepth()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "modFXOffset",
        rawKnobs.containsKey(org.deluge.firmware2.Param.UNPATCHED_MOD_FX_OFFSET)
            ? String.format(
                "0x%08X", rawKnobs.get(org.deluge.firmware2.Param.UNPATCHED_MOD_FX_OFFSET))
            : DelugeHexMapper.unipolarFloatToHexUnified(synth.getModFxOffset()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "modFXFeedback",
        rawKnobs.containsKey(org.deluge.firmware2.Param.UNPATCHED_MOD_FX_FEEDBACK)
            ? String.format(
                "0x%08X", rawKnobs.get(org.deluge.firmware2.Param.UNPATCHED_MOD_FX_FEEDBACK))
            : DelugeHexMapper.unipolarFloatToHexUnified(synth.getModFxFeedback()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "delayRate",
        rawKnobs.containsKey(org.deluge.firmware2.Param.GLOBAL_DELAY_RATE)
            ? String.format("0x%08X", rawKnobs.get(org.deluge.firmware2.Param.GLOBAL_DELAY_RATE))
            : DelugeHexMapper.unipolarFloatToHexUnified(synth.getDelaySend()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "delayFeedback",
        String.format("0x%08X", synth.getDelayFeedbackQ31()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "reverbAmount",
        rawKnobs.containsKey(org.deluge.firmware2.Param.GLOBAL_REVERB_AMOUNT)
            ? String.format("0x%08X", rawKnobs.get(org.deluge.firmware2.Param.GLOBAL_REVERB_AMOUNT))
            : DelugeHexMapper.unipolarFloatToHexUnified(synth.getReverbSend()));
    // GLOBAL_ARP_RATE (sound.cpp:4117) — the raw param, not the panel's old multiplier.
    writeSoundParamAttr(
        writer, curves, written, "arpeggiatorRate", String.format("0x%08X", synth.getArpRateQ31()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "stutterRate",
        rawKnobs.containsKey(org.deluge.firmware2.Param.UNPATCHED_STUTTER_RATE)
            ? String.format(
                "0x%08X", rawKnobs.get(org.deluge.firmware2.Param.UNPATCHED_STUTTER_RATE))
            : DelugeHexMapper.unipolarFloatToHexUnified(synth.getStutter().getStutterRate()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "sampleRateReduction",
        rawKnobs.containsKey(org.deluge.firmware2.Param.UNPATCHED_SAMPLE_RATE_REDUCTION)
            ? String.format(
                "0x%08X", rawKnobs.get(org.deluge.firmware2.Param.UNPATCHED_SAMPLE_RATE_REDUCTION))
            : DelugeHexMapper.unipolarFloatToHexUnified(synth.getSampleRateReduction()));
    writeSoundParamAttr(
        writer,
        curves,
        written,
        "bitCrush",
        rawKnobs.containsKey(org.deluge.firmware2.Param.UNPATCHED_BITCRUSHING)
            ? String.format(
                "0x%08X", rawKnobs.get(org.deluge.firmware2.Param.UNPATCHED_BITCRUSHING))
            : DelugeHexMapper.unipolarFloatToHexUnified(synth.getBitCrush()));
    writeSoundParamAttr(
        writer, curves, written, "waveFold", String.format("0x%08X", synth.getWaveFoldQ31()));
    // C sound.cpp:4084-4087 — unconditionally write lfo1Rate..lfo4Rate so clip <soundParams>
    // resetClipParamsToFirmwareDefaults never wipes unautomated LFO rates on song reload.
    for (int lfoIdx = 0; lfoIdx < 4; lfoIdx++) {
      String rateAttr = "lfo" + (lfoIdx + 1) + "Rate";
      String baseRateHex =
          synth.getRawKnobs().isLfoRateKnobSet(lfoIdx)
              ? String.format("0x%08X", synth.getRawKnobs().getLfoRateKnobQ31(lfoIdx))
              : DelugeHexMapper.lfoHzToHex(synth.getLfo(lfoIdx).rateHz());
      writeSoundParamAttr(writer, curves, written, rateAttr, baseRateHex);
    }

    // Write any raw patched parameters (like sidechain shape and volume)
    for (var entry : synth.getRawKnobs().getRawParamKnobs().entrySet()) {
      String attrName = null;
      int pid = entry.getKey();
      int rawVal = entry.getValue();
      if (pid == org.deluge.firmware2.Param.UNPATCHED_SIDECHAIN_SHAPE) {
        attrName = "sidechainCompressorShape";
      } else if (pid == org.deluge.firmware2.Param.UNPATCHED_SIDECHAIN_VOLUME) {
        attrName = "sidechainCompressorVolume";
      }

      if (attrName != null && !written.contains(attrName)) {
        writeSoundParamAttr(writer, curves, written, attrName, String.format("0x%08X", rawVal));
      }
    }

    // Write non-default unpatched arp/randomizer knobs (mod_controllable_audio.cpp:507-538).
    // For the active clip (or synth default), prefer exact 32-bit rawKnobs and fall back to
    // ArpModel; for non-active clips, serialize from the clip's own ArpModel.
    boolean isActiveClip = (clip == null || clip == synth.getActiveClip());
    org.deluge.model.ArpModel clipArp =
        (clip != null && clip.getArp() != null) ? clip.getArp() : synth.getArp();
    var rawMap = synth.getRawKnobs().getRawParamKnobs();
    for (int pid = org.deluge.firmware2.Param.UNPATCHED_ARP_GATE;
        pid <= org.deluge.firmware2.Param.UNPATCHED_SPREAD_VELOCITY;
        pid++) {
      String attrName = unpatchedArpAttrName(pid);
      if (attrName == null || written.contains(attrName)) {
        continue;
      }
      Integer rawBoxed = isActiveClip ? rawMap.get(pid) : null;
      int rawVal;
      if (rawBoxed != null) {
        rawVal = rawBoxed;
      } else if (clipArp != null
          && org.deluge.xml.ArpXmlCodec.isUnpatchedArpFieldChanged(
              org.deluge.model.ArpModel.defaultConfig(), clipArp, pid)) {
        rawVal = org.deluge.xml.ArpXmlCodec.unpatchedQ31FromArp(clipArp, pid);
      } else {
        continue;
      }
      if (rawVal != org.deluge.firmware2.Arpeggiator.getUnpatchedArpDefault(pid)) {
        writeSoundParamAttr(writer, curves, written, attrName, String.format("0x%08X", rawVal));
      }
    }

    // Write any other automated direct soundParams attributes (C auto_param.cpp:2005-2030).
    for (var entry : curves.entrySet()) {
      String key = entry.getKey();
      if (written.contains(key) || isSubElementParamKey(key)) {
        continue;
      }
      String baseHex = resolveSynthBaseHex(synth, key, "0x00000000");
      writer.writeAttribute(
          key, DelugeHexMapper.formatHexWithAutomationNodes(baseHex, entry.getValue()), false);
      written.add(key);
    }

    List<PatchCable> clipCables =
        (clip != null && !clip.getPatchCables().isEmpty())
            ? clip.getPatchCables()
            : (synth != null && !synth.isClipParamsFresh()
                ? synth.getModulation().getPatchCables()
                : List.of());
    writeSoundParamsSubElementsAndClose(writer, curves, null, synth, clipCables);
  }

  /**
   * C {@code ModControllableAudio::writeParamAttributesToFile} ({@code
   * mod_controllable_audio.cpp:507-538}) — XML attribute names for unpatched arp and randomizer
   * knobs.
   */
  private static String unpatchedArpAttrName(int paramId) {
    return switch (paramId) {
      case org.deluge.firmware2.Param.UNPATCHED_ARP_GATE -> "arpeggiatorGate";
      case org.deluge.firmware2.Param.UNPATCHED_ARP_RHYTHM -> "rhythm";
      case org.deluge.firmware2.Param.UNPATCHED_ARP_SEQUENCE_LENGTH -> "sequenceLength";
      case org.deluge.firmware2.Param.UNPATCHED_ARP_CHORD_POLYPHONY -> "chordPolyphony";
      case org.deluge.firmware2.Param.UNPATCHED_ARP_RATCHET_AMOUNT -> "ratchetAmount";
      case org.deluge.firmware2.Param.UNPATCHED_NOTE_PROBABILITY -> "noteProbability";
      case org.deluge.firmware2.Param.UNPATCHED_REVERSE_PROBABILITY -> "reverseProbability";
      case org.deluge.firmware2.Param.UNPATCHED_ARP_BASS_PROBABILITY -> "bassProbability";
      case org.deluge.firmware2.Param.UNPATCHED_ARP_SWAP_PROBABILITY -> "swapProbability";
      case org.deluge.firmware2.Param.UNPATCHED_ARP_GLIDE_PROBABILITY -> "glideProbability";
      case org.deluge.firmware2.Param.UNPATCHED_ARP_CHORD_PROBABILITY -> "chordProbability";
      case org.deluge.firmware2.Param.UNPATCHED_ARP_RATCHET_PROBABILITY -> "ratchetProbability";
      case org.deluge.firmware2.Param.UNPATCHED_ARP_SPREAD_GATE -> "spreadGate";
      case org.deluge.firmware2.Param.UNPATCHED_ARP_SPREAD_OCTAVE -> "spreadOctave";
      case org.deluge.firmware2.Param.UNPATCHED_SPREAD_VELOCITY -> "spreadVelocity";
      default -> null;
    };
  }

  private static void serializeKitRowSoundParams(
      XMLSerializer writer, org.deluge.model.NoteRowModel rowModel) throws IOException {
    writer.writeOpeningTagBeginning("soundParams");
    Map<String, List<org.deluge.modulation.automation.ParamNode>> curves =
        rowModel.getAutomationCurves();
    Map<String, Float> rowParams = rowModel.getSoundParams();
    java.util.Set<String> written = new java.util.HashSet<>();

    for (var entry : rowParams.entrySet()) {
      String key = entry.getKey();
      if (isSubElementParamKey(key)) {
        continue;
      }
      String baseHex = DelugeHexMapper.unipolarFloatToHexUnified(entry.getValue());
      writeSoundParamAttr(writer, curves, written, key, baseHex);
    }

    for (var entry : curves.entrySet()) {
      String key = entry.getKey();
      if (written.contains(key) || isSubElementParamKey(key)) {
        continue;
      }
      int paramId = org.deluge.engine.FirmwareFactory.getParamIdFromName(key);
      String baseHex =
          org.deluge.firmware2.Arpeggiator.isUnpatchedArpParam(paramId)
              ? String.format(
                  "0x%08X", org.deluge.firmware2.Arpeggiator.getUnpatchedArpDefault(paramId))
              : "0x00000000";
      writer.writeAttribute(
          key, DelugeHexMapper.formatHexWithAutomationNodes(baseHex, entry.getValue()), false);
      written.add(key);
    }

    writeSoundParamsSubElementsAndClose(writer, curves, rowParams, null, rowModel.getPatchCables());
  }

  private static String resolveSynthBaseHex(SynthTrackModel synth, String key, String fallbackHex) {
    if (synth == null) {
      return fallbackHex;
    }
    var raw = synth.getRawKnobs();
    switch (key) {
      case "lfo1Rate" -> {
        return raw.isLfoRateKnobSet(0)
            ? String.format("0x%08X", raw.getLfoRateKnobQ31(0))
            : DelugeHexMapper.lfoHzToHex(synth.getLfo(0).rateHz());
      }
      case "lfo2Rate" -> {
        return raw.isLfoRateKnobSet(1)
            ? String.format("0x%08X", raw.getLfoRateKnobQ31(1))
            : DelugeHexMapper.lfoHzToHex(synth.getLfo(1).rateHz());
      }
      case "lfo3Rate" -> {
        return raw.isLfoRateKnobSet(2)
            ? String.format("0x%08X", raw.getLfoRateKnobQ31(2))
            : DelugeHexMapper.lfoHzToHex(synth.getLfo(2).rateHz());
      }
      case "lfo4Rate" -> {
        return raw.isLfoRateKnobSet(3)
            ? String.format("0x%08X", raw.getLfoRateKnobQ31(3))
            : DelugeHexMapper.lfoHzToHex(synth.getLfo(3).rateHz());
      }
      default -> {}
    }
    int paramId = org.deluge.engine.FirmwareFactory.getParamIdFromName(key);
    if (paramId != -1 && raw.getRawParamKnobs().containsKey(paramId)) {
      return String.format("0x%08X", raw.getRawParamKnobs().get(paramId));
    }
    if (org.deluge.firmware2.Arpeggiator.isUnpatchedArpParam(paramId)) {
      org.deluge.model.ArpModel arp = synth.getArp();
      if (arp != null
          && org.deluge.xml.ArpXmlCodec.isUnpatchedArpFieldChanged(
              org.deluge.model.ArpModel.defaultConfig(), arp, paramId)) {
        return String.format(
            "0x%08X", org.deluge.xml.ArpXmlCodec.unpatchedQ31FromArp(arp, paramId));
      }
      return String.format(
          "0x%08X", org.deluge.firmware2.Arpeggiator.getUnpatchedArpDefault(paramId));
    }
    // C sound.cpp:139, 172-177, 4061-4126 — dedicated Q31/float fields parsed by
    // InstrumentXmlParser.parseClipSoundParamsStatics.
    return switch (key) {
      case "modulator1Amount", "modulator1Volume" ->
          String.format("0x%08X", synth.getModulator1AmountQ31());
      case "modulator2Amount", "modulator2Volume" ->
          String.format("0x%08X", synth.getModulator2AmountQ31());
      case "modulator1Feedback" -> String.format("0x%08X", synth.getModulator1FeedbackQ31());
      case "modulator2Feedback" -> String.format("0x%08X", synth.getModulator2FeedbackQ31());
      case "carrier1Feedback" -> String.format("0x%08X", synth.getCarrier1FeedbackQ31());
      case "carrier2Feedback" -> String.format("0x%08X", synth.getCarrier2FeedbackQ31());
      case "oscAWavetablePosition" ->
          DelugeHexMapper.unipolarFloatToHexUnified(synth.getWaveIndex());
      case "oscBWavetablePosition" ->
          DelugeHexMapper.unipolarFloatToHexUnified(synth.getOsc2WaveIndex());
      case "pitchAdjust" ->
          String.format(
              "0x%08X",
              synth.getPitchAdjustQ31() != Integer.MIN_VALUE ? synth.getPitchAdjustQ31() : 0);
      case "oscAPitchAdjust" ->
          String.format(
              "0x%08X",
              synth.getOsc1PitchAdjustQ31() != Integer.MIN_VALUE
                  ? synth.getOsc1PitchAdjustQ31()
                  : 0);
      case "oscBPitchAdjust" ->
          String.format(
              "0x%08X",
              synth.getOsc2PitchAdjustQ31() != Integer.MIN_VALUE
                  ? synth.getOsc2PitchAdjustQ31()
                  : 0);
      case "mod1PitchAdjust", "mod2PitchAdjust" -> "0x00000000";
      case "oscAPulseWidth", "oscAPhaseWidth" ->
          String.format(
              "0x%08X",
              synth.getOsc1PhaseWidthQ31() != Integer.MIN_VALUE ? synth.getOsc1PhaseWidthQ31() : 0);
      case "oscBPulseWidth", "oscBPhaseWidth" ->
          String.format(
              "0x%08X",
              synth.getOsc2PhaseWidthQ31() != Integer.MIN_VALUE ? synth.getOsc2PhaseWidthQ31() : 0);
      case "compressorThreshold" ->
          DelugeHexMapper.unipolarFloatToHexUnified(synth.getCompressorThreshold());
      case "eqBass" -> DelugeHexMapper.floatToHex(synth.getEqBass());
      case "eqTreble" -> DelugeHexMapper.floatToHex(synth.getEqTreble());
      case "eqBassFrequency" -> DelugeHexMapper.floatToHex(synth.getEqBassFreq());
      case "eqTrebleFrequency" -> DelugeHexMapper.floatToHex(synth.getEqTrebleFreq());
      case "portamento" -> String.format("0x%08X", synth.getPortamentoQ31());
      case "arpeggiatorRate", "arpRate" -> String.format("0x%08X", synth.getArpRateQ31());
      case "waveFold" -> String.format("0x%08X", synth.getWaveFoldQ31());
      case "delayFeedback" -> String.format("0x%08X", synth.getDelayFeedbackQ31());
      default -> fallbackHex;
    };
  }

  private static void writeSoundParamAttr(
      XMLSerializer writer,
      Map<String, List<org.deluge.modulation.automation.ParamNode>> curves,
      java.util.Set<String> written,
      String attrName,
      String baseHex)
      throws IOException {
    String val = DelugeHexMapper.formatHexWithAutomationNodes(baseHex, curves.get(attrName));
    writer.writeAttribute(attrName, val, false);
    written.add(attrName);
  }

  private static boolean isSubElementParamKey(String key) {
    return key.startsWith("envelope")
        || "eqBass".equals(key)
        || "eqTreble".equals(key)
        || "eqBassFrequency".equals(key)
        || "eqTrebleFrequency".equals(key);
  }

  private static void writeSoundParamsSubElementsAndClose(
      XMLSerializer writer,
      Map<String, List<org.deluge.modulation.automation.ParamNode>> curves,
      Map<String, Float> rowParams,
      SynthTrackModel synth,
      List<PatchCable> patchCables)
      throws IOException {
    boolean hasPatchCables = patchCables != null && !patchCables.isEmpty();
    // C sound.cpp:4130-4157, mod_controllable_audio.cpp:540-549: synth <soundParams> always writes
    // <equalizer>, and writes <envelope1..4> whenever the envelope was programmatically set/edited,
    // explicitly present in the clip's raw knobs, or automated (while omitting <envelopeN> when the
    // loaded clip omitted it so <defaultParams> inheritance is preserved). Sparse emission applies
    // to kit row overrides (synth == null).
    boolean hasSubElements = (synth != null) || hasPatchCables;
    if (!hasSubElements) {
      for (String key : curves.keySet()) {
        if (isSubElementParamKey(key)) {
          hasSubElements = true;
          break;
        }
      }
    }
    if (!hasSubElements && rowParams != null) {
      for (String key : rowParams.keySet()) {
        if (isSubElementParamKey(key)) {
          hasSubElements = true;
          break;
        }
      }
    }
    if (!hasSubElements) {
      writer.closeTag();
      return;
    }

    writer.writeOpeningTagEnd();
    String[] envTags = {"envelope1", "envelope2", "envelope3", "envelope4"};
    String[] envSubs = {"attack", "decay", "sustain", "release"};
    for (int envIdx = 0; envIdx < envTags.length; envIdx++) {
      String envTag = envTags[envIdx];
      boolean hasAny =
          synth != null
              && (!synth.getRawKnobs().isEnvKnobSet(envIdx)
                  || rawEnvKnobMatchesModel(synth, envIdx));
      if (!hasAny) {
        for (String sub : envSubs) {
          String key = envTag + "_" + sub;
          if (curves.containsKey(key) || (rowParams != null && rowParams.containsKey(key))) {
            hasAny = true;
            break;
          }
        }
      }
      if (!hasAny) continue;
      // C sound.cpp:4131-4157 — write all four envelope attributes when emitting <envelopeN>.
      writer.writeOpeningTagBeginning(envTag);
      for (String sub : envSubs) {
        String key = envTag + "_" + sub;
        List<org.deluge.modulation.automation.ParamNode> nodes = curves.get(key);
        String baseHex = resolveEnvelopeSubBaseHex(synth, rowParams, envIdx, sub, key);
        writer.writeAttribute(
            sub, DelugeHexMapper.formatHexWithAutomationNodes(baseHex, nodes), false);
      }
      writer.closeTag();
    }

    if (hasPatchCables) {
      // C sound.cpp:4159 -> PatchCableSet::writePatchCablesToFile(writer, writeAutomation=true).
      writer.writeArrayStart("patchCables");
      for (PatchCable pc : patchCables) {
        writePatchCable(writer, pc, true);
      }
      writer.writeArrayEnding("patchCables");
    }

    String[][] eqMap = {
      {"bass", "eqBass"},
      {"treble", "eqTreble"},
      {"bassFrequency", "eqBassFrequency"},
      {"trebleFrequency", "eqTrebleFrequency"}
    };
    boolean hasEq = (synth != null);
    if (!hasEq) {
      for (String[] pair : eqMap) {
        if (curves.containsKey(pair[1]) || (rowParams != null && rowParams.containsKey(pair[1]))) {
          hasEq = true;
          break;
        }
      }
    }
    if (hasEq) {
      // C mod_controllable_audio.cpp:540-549 — write all four equalizer attributes when emitting
      // <equalizer>.
      writer.writeOpeningTagBeginning("equalizer");
      for (String[] pair : eqMap) {
        String key = pair[1];
        Float staticVal = rowParams != null ? rowParams.get(key) : null;
        List<org.deluge.modulation.automation.ParamNode> nodes = curves.get(key);
        boolean isBipolarGain = "eqBass".equals(key) || "eqTreble".equals(key);
        String baseHex =
            staticVal != null
                ? (isBipolarGain
                    ? DelugeHexMapper.floatToHex(staticVal)
                    : DelugeHexMapper.unipolarFloatToHexUnified(staticVal))
                : resolveSynthBaseHex(synth, key, "0x00000000");
        writer.writeAttribute(
            pair[0], DelugeHexMapper.formatHexWithAutomationNodes(baseHex, nodes), false);
      }
      writer.closeTag();
    }
    writer.writeClosingTag("soundParams");
  }

  private static boolean rawEnvKnobMatchesModel(SynthTrackModel synth, int envIdx) {
    if (synth == null) {
      return false;
    }
    var raw = synth.getRawKnobs();
    EnvelopeModel env = synth.getEnv(envIdx);
    if (raw == null || env == null || !raw.isEnvKnobSet(envIdx)) {
      return false;
    }
    return Math.abs(DelugeHexMapper.q31ToEnvTime(raw.getEnvAttackKnobQ31(envIdx)) - env.attack())
            <= 1e-3f
        && Math.abs(DelugeHexMapper.q31ToEnvTime(raw.getEnvDecayKnobQ31(envIdx)) - env.decay())
            <= 1e-3f
        && Math.abs(DelugeHexMapper.q31ToSustain(raw.getEnvSustainKnobQ31(envIdx)) - env.sustain())
            <= 1e-3f
        && Math.abs(DelugeHexMapper.q31ToEnvTime(raw.getEnvReleaseKnobQ31(envIdx)) - env.release())
            <= 1e-3f;
  }

  private static String resolveEnvelopeSubBaseHex(
      SynthTrackModel synth, Map<String, Float> rowParams, int envIdx, String sub, String key) {
    if (synth != null) {
      var raw = synth.getRawKnobs();
      var env = synth.getEnv(envIdx);
      if (rawEnvKnobMatchesModel(synth, envIdx)) {
        int q31 =
            switch (sub) {
              case "attack" -> raw.getEnvAttackKnobQ31(envIdx);
              case "decay" -> raw.getEnvDecayKnobQ31(envIdx);
              case "sustain" -> raw.getEnvSustainKnobQ31(envIdx);
              case "release" -> raw.getEnvReleaseKnobQ31(envIdx);
              default -> 0;
            };
        return String.format("0x%08X", q31);
      }
      if (env != null) {
        return switch (sub) {
          case "attack" -> DelugeHexMapper.envTimeToHex(env.attack());
          case "decay" -> DelugeHexMapper.envTimeToHex(env.decay());
          case "sustain" -> DelugeHexMapper.sustainToHex(env.sustain());
          case "release" -> DelugeHexMapper.envTimeToHex(env.release());
          default -> "0x00000000";
        };
      }
    }
    if (rowParams != null && rowParams.containsKey(key)) {
      float val = rowParams.get(key);
      return "sustain".equals(sub)
          ? DelugeHexMapper.sustainToHex(val)
          : DelugeHexMapper.envTimeToHex(val);
    }
    return "sustain".equals(sub) ? "0x7FFFFFFF" : "0x80000000";
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
      XMLSerializer writer,
      AudioTrackModel audioTrack,
      AudioTrackModel.AudioClip clip,
      boolean arrangementOnly,
      String ci)
      throws IOException {
    writer.writeOpeningTagBeginning("audioClip");
    // C audio_clip.cpp:1057-1058 — persist audio clip name and owning output trackName.
    if (clip.getClipName() != null && !clip.getClipName().isEmpty()) {
      writer.writeAttribute("clipName", clip.getClipName(), false);
    }
    String trackName =
        (audioTrack.getName() != null && !audioTrack.getName().isEmpty())
            ? audioTrack.getName()
            : clip.getTrackName();
    writer.writeAttribute("trackName", trackName, false);
    if (clip.getFilePath() != null) {
      writer.writeAttribute("filePath", clip.getFilePath(), false);
    }
    if (clip.isReversed()) {
      writer.writeAttribute("reversed", 1, false);
    }
    writer.writeAttribute("startSamplePos", clip.getStartSamplePos(), false);
    writer.writeAttribute("endSamplePos", clip.getEndSamplePos(), false);
    // C audio_clip.cpp:1065-1067 — written only when linear interpolation is active.
    if (clip.isLinearInterpolation()) {
      writer.writeAttribute("linearInterpolation", 1, false);
    }
    // C audio_clip.cpp:1071 — writer.writeAttribute("attack", attack) writes signed int32 decimal.
    writer.writeAttribute("attack", clip.getAttackRaw(), false);
    writer.writeAttribute("priority", clip.getPriority(), false);
    // C audio_clip.cpp:1074-1079 — sampleHolder.transpose / cents written when non-zero.
    if (clip.getTranspose() != 0) {
      writer.writeAttribute("transpose", clip.getTranspose(), false);
    }
    if (clip.getCents() != 0) {
      writer.writeAttribute("cents", clip.getCents(), false);
    }
    writer.writeAttribute(
        "pitchSpeedIndependent", clip.isPitchSpeedIndependent() ? "1" : "0", false);
    writer.writeAttribute(
        "overdubsShouldCloneAudioTrack", clip.isOverdubsShouldCloneAudioTrack() ? "1" : "0", false);
    writer.writeAttribute("isPlaying", clip.isPlaying() ? "1" : "0", false);
    writer.writeAttribute("isSoloing", clip.isSoloing() ? "1" : "0", false);
    writer.writeAttribute("isArmedForRecording", clip.isArmedForRecording() ? "1" : "0", false);
    writer.writeAttribute("length", clip.getLength(), false);
    // C clip.cpp:666-668 — sequenceDirection written when != FORWARD.
    if (clip.getSequenceDirection() != null
        && clip.getSequenceDirection() != ClipModel.PlayDirection.FORWARD) {
      String dir =
          switch (clip.getSequenceDirection()) {
            case PING_PONG -> "pingpong";
            default -> clip.getSequenceDirection().name().toLowerCase();
          };
      writer.writeAttribute("sequenceDirection", dir, false);
    }
    writer.writeAttribute("colourOffset", clip.getColourOffset(), false);
    // C clip.cpp:670-672, song.cpp:2135 — arrangement-only clips have section == 255 (omitted).
    if (!arrangementOnly && !clip.isArrangementOnly() && clip.getSection() != 255) {
      writer.writeAttribute("section", clip.getSection(), false);
    }
    writer.writeAttribute("beingEdited", clip.isBeingEdited() ? "1" : "0", false);
    // C clip.cpp:687-689 — written only when not DEFAULT
    if (!"default".equals(clip.getLaunchStyle())) {
      writer.writeAttribute("launchStyle", clip.getLaunchStyle(), false);
    }
    if (ci != null) {
      writer.writeAttribute("clipInstances", ci, false);
    }
    writer.writeOpeningTagEnd();

    // Write params child (C audio_clip.cpp:1100-1104, global_effectable.cpp:811-882).
    writer.writeOpeningTagBeginning("params");
    Map<String, List<org.deluge.modulation.automation.ParamNode>> curves =
        clip.getAutomationCurves();
    Map<String, String> rawHex = clip.getRawParamHex();
    java.util.Set<String> written = new java.util.HashSet<>();

    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "volume",
        rawHex.getOrDefault("volume", DelugeHexMapper.unipolarFloatToHexUnified(clip.getVolume())));
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "pan",
        rawHex.getOrDefault("pan", DelugeHexMapper.floatToHex(clip.getPan())));
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "reverbAmount",
        rawHex.getOrDefault(
            "reverbAmount", DelugeHexMapper.unipolarFloatToHexUnified(clip.getReverbAmount())));
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "sidechainCompressorShape",
        rawHex.getOrDefault(
            "sidechainCompressorShape",
            DelugeHexMapper.unipolarFloatToHexUnified(clip.getSidechainShape())));
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "sidechainCompressorVolume",
        rawHex.getOrDefault(
            "sidechainCompressorVolume",
            DelugeHexMapper.unipolarFloatToHexUnified(clip.getSidechainVolume())));
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "modFXRate",
        rawHex.getOrDefault(
            "modFXRate", DelugeHexMapper.unipolarFloatToHexUnified(clip.getModFXRate())));
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "modFXDepth",
        rawHex.getOrDefault(
            "modFXDepth", DelugeHexMapper.unipolarFloatToHexUnified(clip.getModFXDepth())));
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "modFXOffset",
        rawHex.getOrDefault(
            "modFXOffset", DelugeHexMapper.unipolarFloatToHexUnified(clip.getModFXOffset())));
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "modFXFeedback",
        rawHex.getOrDefault(
            "modFXFeedback", DelugeHexMapper.unipolarFloatToHexUnified(clip.getModFXFeedback())));
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "stutterRate",
        rawHex.getOrDefault(
            "stutterRate", DelugeHexMapper.unipolarFloatToHexUnified(clip.getStutterRate())));
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "sampleRateReduction",
        rawHex.getOrDefault(
            "sampleRateReduction",
            DelugeHexMapper.unipolarFloatToHexUnified(clip.getSampleRateReduction())));
    writeGlobalEffectableAttr(
        writer,
        curves,
        written,
        "bitCrush",
        rawHex.getOrDefault(
            "bitCrush", DelugeHexMapper.unipolarFloatToHexUnified(clip.getBitCrush())));

    java.util.Set<String> allAudioKeys = new java.util.LinkedHashSet<>();
    allAudioKeys.addAll(rawHex.keySet());
    allAudioKeys.addAll(curves.keySet());
    for (String key : allAudioKeys) {
      if (written.contains(key) || isGlobalEffectableSubElementKey(key)) {
        continue;
      }
      writeGlobalEffectableAttr(
          writer,
          curves,
          written,
          key,
          rawHex.getOrDefault(key, defaultGlobalEffectableBaseHex(key)));
    }

    writeGlobalEffectableSubElementsAndClose(
        writer,
        "params",
        key -> resolveAudioClipSubElementBaseHex(clip, rawHex, key),
        allAudioKeys,
        curves,
        true);

    writer.writeClosingTag("audioClip");
  }

  private static String resolveAudioClipSubElementBaseHex(
      AudioTrackModel.AudioClip clip, Map<String, String> rawHex, String key) {
    String raw = rawHex.get(key);
    if (raw != null) {
      return raw;
    }
    return switch (key) {
      case "delayRate" -> DelugeHexMapper.unipolarFloatToHexUnified(clip.getDelayRate());
      case "delayFeedback" -> DelugeHexMapper.unipolarFloatToHexUnified(clip.getDelayFeedback());
      case "lpfFrequency" -> DelugeHexMapper.hzToHex(clip.getLpfFrequency());
      case "lpfResonance" -> DelugeHexMapper.unipolarFloatToHexUnified(clip.getLpfResonance());
      case "hpfFrequency" -> DelugeHexMapper.hzToHex(clip.getHpfFrequency());
      case "hpfResonance" -> DelugeHexMapper.unipolarFloatToHexUnified(clip.getHpfResonance());
      case "eqBass" -> DelugeHexMapper.floatToHex(clip.getEqBass());
      case "eqTreble" -> DelugeHexMapper.floatToHex(clip.getEqTreble());
      case "eqBassFrequency" ->
          DelugeHexMapper.unipolarFloatToHexUnified(clip.getEqBassFrequency());
      case "eqTrebleFrequency" ->
          DelugeHexMapper.unipolarFloatToHexUnified(clip.getEqTrebleFrequency());
      default -> "0x80000000";
    };
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

  /**
   * Maps model oscillator-type strings to exact C++ firmware tokens (C functions.cpp:734-815).
   * {@code stringToOscType} uses case-sensitive {@code strcmp}, falling back to TRIANGLE on unknown
   * strings.
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

  private static String lpfModeToFirmwareString(FilterMode mode) {
    if (mode == null) {
      return "24dB";
    }
    // C filterMap strings (filter_config.cpp:8-14)
    return switch (mode) {
      case LADDER_24 -> "24dB";
      case DRIVE -> "24dBDrive";
      case SVF, SVF_BAND -> "SVF_Band";
      case SVF_NOTCH -> "SVF_Notch";
      case OFF -> "Off";
      default -> "12dB";
    };
  }

  private static String hpfModeToFirmwareString(FilterMode mode) {
    if (mode == null) {
      return "HPLadder";
    }
    // C mod_controllable_audio.cpp:410 — the C HPF slot has only the HP ladder (plus SVF
    // bandpass/notch), so LADDER_12 / LADDER_24 / DRIVE all map to "HPLadder".
    return switch (mode) {
      case SVF, SVF_BAND -> "SVF_Band";
      case SVF_NOTCH -> "SVF_Notch";
      case OFF -> "Off";
      default -> "HPLadder";
    };
  }

  /**
   * C {@code MIDIParamCollection::writeToFile} ({@code midi_param_collection.cpp:322-348}) — writes
   * {@code <midiParams><param><cc>...</cc><value>0x...</value></param></midiParams>} when the clip
   * has MIDI CC parameters.
   */
  private static void serializeMidiParams(XMLSerializer writer, ClipModel clip) throws IOException {
    List<org.deluge.modulation.midi.MidiParam> midiParams = clip.getMidiParams();
    if (midiParams == null || midiParams.isEmpty()) {
      return;
    }
    writer.writeOpeningTag("midiParams");
    for (org.deluge.modulation.midi.MidiParam midiParam : midiParams) {
      writer.writeOpeningTag("param");
      if (midiParam.cc == org.deluge.modulation.midi.MidiParamCollection.CC_NUMBER_NONE) {
        writer.writeTag("cc", "none");
      } else {
        writer.writeTag("cc", midiParam.cc);
      }
      String baseHex = String.format("0x%08X", midiParam.param.currentValue);
      String hexValue =
          DelugeHexMapper.formatHexWithAutomationNodes(baseHex, midiParam.param.nodes);
      writer.writeTag("value", hexValue);
      writer.writeClosingTag("param");
    }
    writer.writeClosingTag("midiParams");
  }

  private static String filterRouteToFirmwareString(int route) {
    // C filter_config.cpp:16-20 — routeMap strings ("H2L", "L2H", "PARA").
    return switch (route) {
      case 1 -> "L2H";
      case 2 -> "PARA";
      default -> "H2L";
    };
  }

  private static String modFxTypeToFirmwareString(String modFxType) {
    // C functions.cpp:878-901 (fxTypeToString).
    if (modFxType == null || modFxType.isBlank()) {
      return "none";
    }
    return switch (modFxType.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "flanger" -> "flanger";
      case "tapewarble", "warble" -> "TapeWarble";
      case "dimension" -> "dimension";
      case "chorus" -> "chorus";
      case "stereochorus", "chorus_stereo" -> "StereoChorus";
      case "grainfx", "grain" -> "grainFX";
      case "phaser" -> "phaser";
      default -> "none";
    };
  }
}
