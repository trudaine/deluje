package org.deluge.xml;

import static org.deluge.xml.DelugeXmlUtil.*;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.deluge.model.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Parses Deluge Project Songs and all track types/sequencer notes/automation from song XML format.
 */
public class SongXmlParser {
  private static final Logger LOG = Logger.getLogger(SongXmlParser.class.getName());

  /**
   * Matches C++ {@code Deserializer.cpp:62}, {@code song.cpp:2117-2124}, {@code
   * firmware_version.h.in:46}, {@code firmware_version.cpp:6-20}, and {@code semver.h:27-37}
   * verbatim when evaluating {@code song_firmware_version >= FirmwareVersion::official({3, 1, 0,
   * "alpha2"})}:
   *
   * <ul>
   *   <li>Missing/blank {@code firmwareVersion} defaults to {@code Type::OFFICIAL {0, 0, 0}} -&gt;
   *       {@code false}.
   *   <li>Community builds ({@code c...}/{@code C...}, {@code Type::COMMUNITY = 254}) and unparsed
   *       {@code Type::UNKNOWN = 255} versions rank strictly above {@code Type::OFFICIAL = 0} -&gt;
   *       {@code true}.
   *   <li>Official {@code major.minor.patch[-preRelease][+build]} compares numerically on {@code
   *       (major, minor, patch)} against {@code (3, 1, 0)}; at {@code 3.1.0}, no pre-release
   *       ({@code "3.1.0"} final) ranks above pre-release ({@code true}), while pre-release tags
   *       compare lexicographically against {@code "alpha2"} ({@code "alpha"} -&gt; {@code false},
   *       {@code "alpha2"}/{@code "beta"} -&gt; {@code true}).
   * </ul>
   */
  public static boolean isFirmwareAtLeast310Alpha2(String rawVersion) {
    if (rawVersion == null || rawVersion.isBlank()) {
      return false;
    }
    String s = rawVersion.trim();
    if (s.startsWith("c") || s.startsWith("C")) {
      return true;
    }
    if (s.startsWith("v") || s.startsWith("V")) {
      s = s.substring(1);
    }
    int plusIdx = s.indexOf('+');
    if (plusIdx >= 0) {
      s = s.substring(0, plusIdx);
    }
    String preRelease = "";
    int dashIdx = s.indexOf('-');
    if (dashIdx >= 0) {
      preRelease = s.substring(dashIdx + 1);
      s = s.substring(0, dashIdx);
    }
    String[] parts = s.split("\\.", -1);
    if (parts.length < 2) {
      return true;
    }
    int major;
    int minor;
    int patch = 0;
    try {
      major = Integer.parseInt(parts[0]);
      minor = Integer.parseInt(parts[1]);
      if (parts.length >= 3 && !parts[2].isEmpty()) {
        int end = 0;
        while (end < parts[2].length() && Character.isDigit(parts[2].charAt(end))) {
          end++;
        }
        if (end == 0) {
          return true;
        }
        patch = Integer.parseInt(parts[2].substring(0, end));
        if (end < parts[2].length() && preRelease.isEmpty()) {
          preRelease = parts[2].substring(end);
        }
      }
    } catch (NumberFormatException e) {
      return true;
    }
    if (major != 3) {
      return major > 3;
    }
    if (minor != 1) {
      return minor > 1;
    }
    if (patch != 0) {
      return patch > 0;
    }
    if (preRelease.isEmpty()) {
      return true;
    }
    return preRelease.compareTo("alpha2") >= 0;
  }

  public static ProjectModel parseSong(java.io.File xmlFile) throws Exception {
    // try-with-resources: parseSong(InputStream, ...) reads the whole document into a DOM, so the
    // stream is finished by the time we return. Leaving it open leaks a descriptor per load and,
    // on Windows, keeps the file locked until GC (breaks delete/rename/save-over).
    try (java.io.InputStream is = new java.io.FileInputStream(xmlFile)) {
      return parseSong(is, xmlFile.getName().replace(".XML", ""));
    }
  }

  public static ProjectModel parseSong(InputStream is, String name) throws Exception {
    try {
      return parseSongImpl(is, name);
    } finally {
      InstrumentXmlParser.SONG_INPUT_TICK_MAGNITUDE.remove();
    }
  }

  private static ProjectModel parseSongImpl(InputStream is, String name) throws Exception {
    Document doc = parseXml(is);
    Element root = doc.getDocumentElement();

    Element songNode = root;
    if (!root.getTagName().equals("song")) {
      NodeList songs = root.getElementsByTagName("song");
      if (songs.getLength() > 0) {
        songNode = (Element) songs.item(0);
      }
    }

    ProjectModel project = new ProjectModel();

    if (songNode.hasAttribute("tempo")) {
      project.setBpm(Float.parseFloat(songNode.getAttribute("tempo")));
    }
    if (songNode.hasAttribute("swing")) {
      String sw = songNode.getAttribute("swing");
      if (sw.startsWith("0x")) {
        project.setSwing(DelugeHexMapper.hexToFloat(sw));
      } else {
        project.setSwing(Float.parseFloat(sw));
      }
    }

    if (songNode.hasAttribute("key")) {
      project.setKey(songNode.getAttribute("key"));
    }
    if (songNode.hasAttribute("scale")) {
      project.setScale(songNode.getAttribute("scale"));
    }

    // ── View & Song-State Parity Attribute Parsers (C++ Parity Gaps) ──
    if (songNode.hasAttribute("affectEntire")) {
      project.setAffectEntire("1".equals(songNode.getAttribute("affectEntire")));
    }
    if (songNode.hasAttribute("arrangementAutoScrollOn")) {
      project.setArrangementAutoScrollOn(
          "1".equals(songNode.getAttribute("arrangementAutoScrollOn")));
    }
    // C song.cpp:1688-1702, 2117-2124, Deserializer.cpp:62, firmware_version.cpp:6-20 —
    // firmwareVersion and inputTickMagnitude govern swingInterval conversion and can appear as
    // either <song .../> attributes or direct child elements in any tag order.
    String firmwareVersionRaw = DelugeXmlUtil.attrOrDirectChildText(songNode, "firmwareVersion");
    boolean firmwareAtLeast310Alpha2 = isFirmwareAtLeast310Alpha2(firmwareVersionRaw);

    String inputTickMagRaw = DelugeXmlUtil.attrOrDirectChildText(songNode, "inputTickMagnitude");
    if (inputTickMagRaw != null && !inputTickMagRaw.isBlank()) {
      project.setInputTickMagnitude(Integer.parseInt(inputTickMagRaw.trim()));
      // The C converts every sound's sync levels with the current song's magnitude.
      InstrumentXmlParser.SONG_INPUT_TICK_MAGNITUDE.set(project.getInputTickMagnitude());
    }
    if (songNode.hasAttribute("rootNote")) {
      project.setKey(songNode.getAttribute("rootNote"));
    }
    String swingAmountRaw = DelugeXmlUtil.attrOrDirectChildText(songNode, "swingAmount");
    if (swingAmountRaw != null && !swingAmountRaw.isBlank()) {
      project.setSwingAmount(Integer.parseInt(swingAmountRaw.trim()));
    }
    String swingIntervalRaw = DelugeXmlUtil.attrOrDirectChildText(songNode, "swingInterval");
    if (swingIntervalRaw != null && !swingIntervalRaw.isBlank()) {
      int rawInterval = Integer.parseInt(swingIntervalRaw.trim());
      if (firmwareAtLeast310Alpha2) {
        project.setSwingInterval(
            DelugeXmlUtil.convertSyncLevelFromFileValueToInternalValue(
                rawInterval, project.getInputTickMagnitude()));
      } else {
        project.setSwingInterval(rawInterval);
      }
    }
    if (songNode.hasAttribute("timePerTimerTick")) {
      project.setTimePerTimerTick(Integer.parseInt(songNode.getAttribute("timePerTimerTick")));
    }
    if (songNode.hasAttribute("timerTickFraction")) {
      project.setTimerTickFraction(Integer.parseInt(songNode.getAttribute("timerTickFraction")));
    }
    // C song.cpp:1665-1675, 2125-2129, song.h:443-457 — hardware song XML stores tempo via
    // timePerTimerTick / timerTickFraction / inputTickMagnitude (no <song tempo="..."> attribute).
    if (!songNode.hasAttribute("tempo") && songNode.hasAttribute("timePerTimerTick")) {
      int fraction =
          songNode.hasAttribute("timerTickFraction") ? project.getTimerTickFraction() : 0;
      long timePerTimerTickBig =
          ((long) project.getTimePerTimerTick() << 32) | Integer.toUnsignedLong(fraction);
      float timePerTimerTickFloat = (float) timePerTimerTickBig / 4294967296.0f;
      int inputMag = project.getInputTickMagnitude();
      if (inputMag > 0) {
        timePerTimerTickFloat *= (float) (1 << inputMag);
      }
      if (timePerTimerTickFloat > 0.0f) {
        float calculatedBpm = 110250.0f / timePerTimerTickFloat;
        if (inputMag < 0) {
          calculatedBpm *= (float) (1 << -inputMag);
        }
        project.setBpm(calculatedBpm);
      }
    }
    if (songNode.hasAttribute("xScroll")) {
      project.setXScroll(Integer.parseInt(songNode.getAttribute("xScroll")));
    }
    if (songNode.hasAttribute("xZoom")) {
      project.setXZoom(Integer.parseInt(songNode.getAttribute("xZoom")));
    }
    if (songNode.hasAttribute("xScrollArrangementView")) {
      project.setXScrollArrangementView(
          Integer.parseInt(songNode.getAttribute("xScrollArrangementView")));
    }
    if (songNode.hasAttribute("xZoomArrangementView")) {
      project.setXZoomArrangementView(
          Integer.parseInt(songNode.getAttribute("xZoomArrangementView")));
    }
    if (songNode.hasAttribute("yScrollArrangementView")) {
      project.setYScrollArrangementView(
          Integer.parseInt(songNode.getAttribute("yScrollArrangementView")));
    }
    if (songNode.hasAttribute("yScrollSongView")) {
      project.setYScrollSongView(Integer.parseInt(songNode.getAttribute("yScrollSongView")));
    }
    if (songNode.hasAttribute("yScroll")) {
      project.setYScroll(Integer.parseInt(songNode.getAttribute("yScroll")));
    }
    if (songNode.hasAttribute("yScrollKeyboard")) {
      project.setYScrollKeyboard(Integer.parseInt(songNode.getAttribute("yScrollKeyboard")));
    }
    if (songNode.hasAttribute("inArrangementView")) {
      project.setBootInArrangementView("1".equals(songNode.getAttribute("inArrangementView")));
    }
    if (songNode.hasAttribute("sessionLayout")) {
      project.setSessionLayout(Integer.parseInt(songNode.getAttribute("sessionLayout")));
    }
    if (songNode.hasAttribute("songGridScrollX")) {
      project.setSongGridScrollX(Integer.parseInt(songNode.getAttribute("songGridScrollX")));
    }
    if (songNode.hasAttribute("songGridScrollY")) {
      project.setSongGridScrollY(Integer.parseInt(songNode.getAttribute("songGridScrollY")));
    }
    if (songNode.hasAttribute("keyboardLayout")) {
      project.setKeyboardLayout(Integer.parseInt(songNode.getAttribute("keyboardLayout")));
    }
    if (songNode.hasAttribute("keyboardRowInterval")) {
      project.setKeyboardRowInterval(
          Integer.parseInt(songNode.getAttribute("keyboardRowInterval")));
    }
    if (songNode.hasAttribute("inKeyMode")) {
      project.setInKeyMode(
          "1".equals(songNode.getAttribute("inKeyMode"))
              || "true".equalsIgnoreCase(songNode.getAttribute("inKeyMode")));
    }
    if (songNode.hasAttribute("inKeyRowInterval")) {
      project.setInKeyRowInterval(Integer.parseInt(songNode.getAttribute("inKeyRowInterval")));
    }
    if (songNode.hasAttribute("inKeyScrollOffset")) {
      project.setInKeyScrollOffset(Integer.parseInt(songNode.getAttribute("inKeyScrollOffset")));
    }
    if (songNode.hasAttribute("drumsScrollOffset")) {
      project.setDrumsScrollOffset(Integer.parseInt(songNode.getAttribute("drumsScrollOffset")));
    }
    if (songNode.hasAttribute("drumsZoomLevel")) {
      project.setDrumsZoomLevel(Integer.parseInt(songNode.getAttribute("drumsZoomLevel")));
    }
    if (songNode.hasAttribute("drumsEdgeSize")) {
      project.setDrumsEdgeSize(Integer.parseInt(songNode.getAttribute("drumsEdgeSize")));
    }
    if (songNode.hasAttribute("anyOfMelodicKitPercussion")) {
      project.setAnyOfMelodicKitPercussion(
          Integer.parseInt(songNode.getAttribute("anyOfMelodicKitPercussion")));
    }
    if (songNode.hasAttribute("numClips")) {
      project.setNumClips(Integer.parseInt(songNode.getAttribute("numClips")));
    }
    // C song.cpp:2098 -> global_effectable.cpp:1063-1075 — modFXCurrentParam & currentFilterType
    // live on <song> (with fallback on <songParams> in parseSongFx for legacy Java saves).
    String songModFXParam = readDirectAttrOrChild(songNode, "modFXCurrentParam");
    if (songModFXParam != null && !songModFXParam.isEmpty()) {
      project.setModFXCurrentParam(songModFXParam);
    }
    String songFilterType = readDirectAttrOrChild(songNode, "currentFilterType");
    if (songFilterType != null && !songFilterType.isEmpty()) {
      project.setCurrentFilterType(songFilterType);
    }

    // ── Mode notes (scale note mask from <modeNotes>/<modeNote>) ──
    Element modeNotesEl = getFirstChild(songNode, "modeNotes");
    if (modeNotesEl != null) {
      NodeList notes = modeNotesEl.getElementsByTagName("modeNote");
      boolean[] mask = new boolean[12];
      for (int i = 0; i < notes.getLength(); i++) {
        try {
          mask[Integer.parseInt(notes.item(i).getTextContent().trim()) % 12] = true;
        } catch (Exception e) {
          /* ignore malformed modeNote */
        }
      }
      project.setModeNotes(mask);
    }

    // ── Microtuning & Custom Temperaments ──
    Element microtuningEl = getFirstChild(songNode, "microtuning");
    if (microtuningEl != null) {
      if (microtuningEl.hasAttribute("notes")) {
        project.setOctaveNumMicrotonalNotes(Integer.parseInt(microtuningEl.getAttribute("notes")));
      }
      if (microtuningEl.hasAttribute("isEqual")) {
        project.setIsEqualTemperament(Boolean.parseBoolean(microtuningEl.getAttribute("isEqual")));
      }
      if (microtuningEl.hasAttribute("baseFrequency")) {
        project.setBaseFrequencyHz(Double.parseDouble(microtuningEl.getAttribute("baseFrequency")));
      }

      Element centsEl = getFirstChild(microtuningEl, "cents");
      if (centsEl != null) {
        String centsText = centsEl.getTextContent().trim();
        if (!centsText.isEmpty()) {
          String[] parts = centsText.split(",");
          int[] centAdjust = project.getCentAdjustForNotesInTemperament();
          for (int i = 0; i < Math.min(parts.length, centAdjust.length); i++) {
            try {
              centAdjust[i] = Integer.parseInt(parts[i].trim());
            } catch (Exception e) {
              /* ignore malformed item */
            }
          }
        }
      }

      Element ratiosEl = getFirstChild(microtuningEl, "ratios");
      if (ratiosEl != null) {
        String ratiosText = ratiosEl.getTextContent().trim();
        if (!ratiosText.isEmpty()) {
          String[] parts = ratiosText.split(",");
          double[] customRatios = project.getCustomRatios();
          for (int i = 0; i < Math.min(parts.length, customRatios.length); i++) {
            try {
              customRatios[i] = Double.parseDouble(parts[i].trim());
            } catch (Exception e) {
              /* ignore malformed item */
            }
          }
        }
      }
    }

    // ── Song-level FX (reverb, delay, sidechain, compressor, songParams) ──
    parseSongFx(songNode, project);

    // 1. Parse Instruments
    // Collect direct-child <sound> elements for later automation parsing
    java.util.ArrayList<Element> instrumentSoundNodes = new java.util.ArrayList<>();

    NodeList instNodes = songNode.getElementsByTagName("instruments");
    java.util.List<Element> instrumentTrackElems = new java.util.ArrayList<>();
    java.util.Map<Object, TrackModel> clipOwnerMap = new java.util.IdentityHashMap<>();
    if (instNodes.getLength() > 0) {
      Element instruments = (Element) instNodes.item(0);
      NodeList children = instruments.getChildNodes();
      for (int i = 0; i < children.getLength(); i++) {
        if (children.item(i) instanceof Element childNode) {
          String tagName = childNode.getTagName();
          TrackModel addedTrack = null;
          if ("kit".equals(tagName)) {
            KitTrackModel kit = KitXmlParser.parseKitElement(childNode);
            project.addTrack(kit);
            addedTrack = kit;
          } else if ("midi".equals(tagName)
              || "midiChannel".equals(tagName)
              || "mpeZone".equals(tagName)) {
            // C song.cpp:2043-2052 — MIDI outputs ("midi", or the older "midiChannel"/"mpeZone").
            instrumentSoundNodes.add(childNode);
            MidiTrackModel midiTrack = parseMidiElement(childNode);
            project.addTrack(midiTrack);
            addedTrack = midiTrack;
          } else if ("sound".equals(tagName)) {
            instrumentSoundNodes.add(childNode);
            boolean hasMidiChannel = childNode.getElementsByTagName("midiChannel").getLength() > 0;
            boolean hasMpeZone = false;
            NodeList zoneNodes = childNode.getElementsByTagName("zone");
            for (int z = 0; z < zoneNodes.getLength(); z++) {
              if (zoneNodes.item(z).getParentNode() == childNode) {
                hasMpeZone = true;
                break;
              }
            }
            boolean isMidi = hasMidiChannel || hasMpeZone;
            if (isMidi) {
              MidiTrackModel midiTrack = parseMidiElement(childNode);
              project.addTrack(midiTrack);
              addedTrack = midiTrack;
            } else {
              SynthTrackModel synth = parseSynthElement(childNode);
              project.addTrack(synth);
              addedTrack = synth;
            }
          } else if ("audioTrack".equals(tagName)) {
            AudioTrackModel audioTrack = parseAudioTrackElement(childNode);
            project.addTrack(audioTrack);
            addedTrack = audioTrack;
          }
          // Record the exact XML <instruments> child mapped to addedTrack so <cvChannel> elements
          // (C song.cpp:2054-2064) never shift downstream clipInstances track indices or colours.
          if (addedTrack != null) {
            instrumentTrackElems.add(childNode);
            if (childNode.hasAttribute("colour")) {
              addedTrack.setColourHex(childNode.getAttribute("colour"));
            }
          }
        }
      }
    }

    // 2. Parse Tracks (Clips)
    NodeList tracksNodes = songNode.getElementsByTagName("tracks");
    if (tracksNodes.getLength() > 0) {
      Element tracks = (Element) tracksNodes.item(0);
      NodeList trackList = tracks.getElementsByTagName("track");

      for (int i = 0; i < trackList.getLength(); i++) {
        Element trackElem = (Element) trackList.item(i);

        java.util.List<TrackModel> projectTracks = project.getTracks();
        if (i < projectTracks.size()) {
          TrackModel targetTrack = projectTracks.get(i);
          if (trackElem.hasAttribute("colour")) {
            targetTrack.setColourHex(trackElem.getAttribute("colour"));
          }

          NodeList noteRowsList = trackElem.getElementsByTagName("noteRows");
          NodeList noteRowList = null;
          if (noteRowsList.getLength() > 0) {
            Element noteRowsElem = (Element) noteRowsList.item(0);
            noteRowList = noteRowsElem.getElementsByTagName("noteRow");
          }
          int rowCount = computeClipRowCount(noteRowList, targetTrack);

          boolean tripletMode =
              "1".equals(trackElem.getAttribute("triplet"))
                  || "true".equalsIgnoreCase(trackElem.getAttribute("triplet"));
          int stepTicks = tripletMode ? 32 : 24;
          int stepCount = 16;
          int trackXmlLengthTicks = 0;
          if (trackElem.hasAttribute("length")) {
            try {
              trackXmlLengthTicks = Integer.parseInt(trackElem.getAttribute("length"));
              stepCount = trackXmlLengthTicks / stepTicks;
              if (stepCount < 1) stepCount = 16;
            } catch (Exception ignored) {
            }
          }

          ClipModel clip = new ClipModel("CLIP " + i, rowCount, stepCount);
          clip.setIsKit(targetTrack instanceof KitTrackModel);
          readClipPlaybackStatus(trackElem, clip);
          clip.setTripletMode(tripletMode);
          clip.setPlayDirection(readPlayDirectionAttr(trackElem));

          if (noteRowList != null) {
            java.util.BitSet claimedDrumIndices = new java.util.BitSet();
            for (int r = 0; r < noteRowList.getLength(); r++) {
              Element noteRowElem = (Element) noteRowList.item(r);
              String hexData = null;
              int rowIdx = r;
              String drumIdxStr = DelugeXmlUtil.attrOrDirectChildText(noteRowElem, "drumIndex");
              if (drumIdxStr != null && !drumIdxStr.isBlank()) {
                int resolvedDrumIdx = resolveXmlDrumIndex(noteRowElem, targetTrack);
                // C instrument_clip.cpp:3939-3992, 4025-4027 — negative sentinels (-1 = no drum,
                // -5..-2 = GateDrum) and duplicate NoteRows claiming an already-assigned Drum*
                // leave thisNoteRow->drum = nullptr without clobbering real SoundDrum rows.
                if (resolvedDrumIdx < 0 || claimedDrumIndices.get(resolvedDrumIdx)) {
                  continue;
                }
                claimedDrumIndices.set(resolvedDrumIdx);
                rowIdx = resolvedDrumIdx;
                clip.getOrCreateRow(rowIdx).setPitch(rowIdx);
              } else if (targetTrack instanceof KitTrackModel) {
                claimedDrumIndices.set(rowIdx);
              }

              // Read the y attribute (MIDI note number for synth tracks)
              int notePitch = -1;
              if (noteRowElem.hasAttribute("y")) {
                notePitch = Integer.parseInt(noteRowElem.getAttribute("y"));
              } else {
                NodeList yList = noteRowElem.getElementsByTagName("y");
                if (yList.getLength() > 0) {
                  notePitch = Integer.parseInt(yList.item(0).getTextContent());
                }
              }

              // Apply per-row pitch from y attribute before setting grid steps and raw notes so
              // clip.setStep never overwrites rawNotes after clip.setRawNoteEvents.
              if (notePitch >= 0) {
                clip.setRowYNote(rowIdx, notePitch);
              }

              NoteDataSource noteSrc = readNoteDataSource(noteRowElem, false);
              hexData = noteSrc.hex();
              if (hexData != null && !hexData.isEmpty()) {
                int hcpn = noteSrc.hexCharsPerNote();
                // Firmware XML uses dynamic ticks per grid step (triplet alignment check!)
                java.util.List<StepData> row =
                    DelugeNoteDataMapper.decodeRow(hexData, stepCount, stepTicks, hcpn);

                for (int s = 0; s < stepCount; s++) {
                  StepData step = row.get(s);
                  if (notePitch >= 0) {
                    step = step.withPitch(notePitch);
                  }
                  clip.setStep(rowIdx, s, step);
                }

                java.util.List<org.deluge.model.NoteModel> rawNotes =
                    DelugeNoteDataMapper.decodeRawNotes(hexData, hcpn);
                clip.setRawNoteEvents(rowIdx, rawNotes);
              }

              applyRowMute(noteRowElem, clip, rowIdx);
              int trackReadUpTo =
                  trackXmlLengthTicks > 0
                      ? trackXmlLengthTicks
                      : (clip.getLoopLength() > 0 ? clip.getLoopLength() : Integer.MAX_VALUE);
              parseNoteRowExpressionData(noteRowElem, clip, rowIdx, trackReadUpTo);

              // Parse per-noteRow <soundParams> (35+ hex param overrides)
              NodeList rowSoundParamsList = noteRowElem.getElementsByTagName("soundParams");
              if (rowSoundParamsList.getLength() > 0) {
                Element sp = (Element) rowSoundParamsList.item(0);
                parseNoteRowSoundParams(sp, clip, rowIdx, trackXmlLengthTicks);
              }
            }
          }
          parseColumnControls(trackElem, clip);
          if (!(targetTrack instanceof KitTrackModel)) {
            int trackReadUpTo =
                trackXmlLengthTicks > 0
                    ? trackXmlLengthTicks
                    : (clip.getLoopLength() > 0 ? clip.getLoopLength() : Integer.MAX_VALUE);
            readClipBendRanges(trackElem, clip);
            parseClipExpressionData(trackElem, clip, trackReadUpTo);
          }
          targetTrack.addClip(clip);
          clipOwnerMap.put(clip, targetTrack);

          // ── Parse automation data for synth and midi tracks ──
          if ((targetTrack instanceof SynthTrackModel || targetTrack instanceof MidiTrackModel)
              && !instrumentSoundNodes.isEmpty()) {
            // Count how many kit tracks came before this synth track to compute the
            // correct index into instrumentSoundNodes (which only contains <sound> elements).
            int kitCount = 0;
            for (int k = 0; k < i; k++) {
              if (k < projectTracks.size() && projectTracks.get(k) instanceof KitTrackModel) {
                kitCount++;
              }
            }
            int soundIdx = i - kitCount;
            if (soundIdx >= 0 && soundIdx < instrumentSoundNodes.size()) {
              Element soundNode = instrumentSoundNodes.get(soundIdx);
              parseAutomation(soundNode, clip);
            }
          }
        } else {
        }
      }
    }

    // 2b. Parse Session Clips (c1.2.0+ format — <sessionClips>/<instrumentClip> instead of
    // <tracks>/<track>)
    // Track heterogeneous clip arrays + clip->Output ownership for C-faithful clipInstances
    // decoding (C song.cpp:2131-2258, output.cpp:279-304).
    java.util.List<Object> parsedSessionClips = new java.util.ArrayList<>();
    java.util.List<Object> parsedArrangementClips = new java.util.ArrayList<>();
    java.util.Map<ClipModel, Element> clipSoundParamsMap = new java.util.IdentityHashMap<>();
    java.util.Map<ClipModel, Integer> clipReadUpToMap = new java.util.IdentityHashMap<>();
    java.util.Map<SynthTrackModel, java.util.List<PatchCable>> baseSynthPatchCables =
        new java.util.IdentityHashMap<>();
    for (TrackModel t : project.getTracks()) {
      if (t instanceof SynthTrackModel stm) {
        baseSynthPatchCables.put(
            stm, new java.util.ArrayList<>(stm.getModulation().getPatchCables()));
      }
    }

    NodeList sessionClipsNodes = songNode.getElementsByTagName("sessionClips");
    if (sessionClipsNodes.getLength() > 0) {
      Element sessionClips = (Element) sessionClipsNodes.item(0);

      // Build FIFO queues of kit tracks, non-drum instruments tracks, and audio tracks for matching
      java.util.List<TrackModel> projectTracks = project.getTracks();
      java.util.Queue<KitTrackModel> kitTrackQueue = new java.util.LinkedList<>();
      java.util.Queue<TrackModel> instrumentTrackQueue = new java.util.LinkedList<>();
      java.util.Queue<AudioTrackModel> audioTrackQueue = new java.util.LinkedList<>();
      for (TrackModel t : projectTracks) {
        switch (t) {
          case KitTrackModel kit -> kitTrackQueue.add(kit);
          case SynthTrackModel synth -> instrumentTrackQueue.add(synth);
          case MidiTrackModel midi -> instrumentTrackQueue.add(midi);
          case AudioTrackModel audio -> audioTrackQueue.add(audio);
          default -> {}
        }
      }

      java.util.Set<TrackModel> claimed =
          java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
      NodeList sessionChildNodes = sessionClips.getChildNodes();
      int sessionElemIdx = 0;
      for (int cIdx = 0; cIdx < sessionChildNodes.getLength(); cIdx++) {
        if (!(sessionChildNodes.item(cIdx) instanceof Element clipElem)) {
          continue;
        }
        String clipTag = clipElem.getTagName();
        if ("audioClip".equals(clipTag)) {
          // C audio_clip.cpp:1130-1132, 1220-1226; song.cpp:3704-3713 — caseless trackName lookup;
          // only fall back to the unclaimed FIFO queue when trackName is absent/empty.
          String trackName = DelugeXmlUtil.attrOrDirectChildText(clipElem, "trackName");
          AudioTrackModel targetTrack = null;
          if (trackName != null && !trackName.isEmpty()) {
            for (TrackModel t : projectTracks) {
              if (t instanceof AudioTrackModel at && trackName.equalsIgnoreCase(at.getName())) {
                targetTrack = at;
                audioTrackQueue.remove(at);
                break;
              }
            }
          } else {
            targetTrack = audioTrackQueue.poll();
          }
          if (targetTrack == null) {
            parsedSessionClips.add(null);
            sessionElemIdx++;
            continue;
          }
          AudioTrackModel.AudioClip audioClip = parseAudioClipElement(clipElem, trackName, false);
          targetTrack.addAudioClip(audioClip);
          clipOwnerMap.put(audioClip, targetTrack);
          parsedSessionClips.add(audioClip);
          sessionElemIdx++;
          continue;
        }
        if (!"instrumentClip".equals(clipTag)) {
          continue;
        }
        int i = sessionElemIdx++;

        // C instrument_clip.cpp:2545-2550, 3902-3919 — CV clips claim a CVChannel (not modelled in
        // Java tracks); keep parsedSessionClips index aligned without stealing an instrument track.
        if (DelugeXmlUtil.attrOrDirectChildText(clipElem, "cvChannel") != null) {
          parsedSessionClips.add(null);
          continue;
        }

        // Determine if this is a kit clip (drumIndex in noteRows) or synth clip
        NodeList noteRowsList = clipElem.getElementsByTagName("noteRows");
        boolean isKitClip = false;
        if (clipElem.hasAttribute("instrumentPresetFolder")) {
          isKitClip = "KITS".equalsIgnoreCase(clipElem.getAttribute("instrumentPresetFolder"));
        }
        if (!isKitClip && noteRowsList.getLength() > 0) {
          Element noteRowsElem = (Element) noteRowsList.item(0);
          NodeList noteRowList = noteRowsElem.getElementsByTagName("noteRow");
          for (int r = 0; r < noteRowList.getLength() && !isKitClip; r++) {
            if (((Element) noteRowList.item(r)).hasAttribute("drumIndex")) {
              isKitClip = true;
            }
          }
        }

        // C InstrumentClip::claimOutput (instrument_clip.cpp:3902-3919): the clip's output is the
        // first one matching its saved linkage. Only a clip with no linkage falls back to FIFO.
        TrackModel targetTrack = claimOutputForClip(clipElem, isKitClip, projectTracks, claimed);
        if (targetTrack != null) {
          kitTrackQueue.remove(targetTrack);
          instrumentTrackQueue.remove(targetTrack);
        } else if (isKitClip) {
          targetTrack = kitTrackQueue.poll();
        } else {
          targetTrack = instrumentTrackQueue.poll();
        }
        if (targetTrack != null) {
          claimed.add(targetTrack);
        }

        if (targetTrack == null) {
          parsedSessionClips.add(null);
          continue;
        }

        // The Deluge pad colour of a session clip is fromHue(colourOffset * -8/3)
        // (instrument_clip.cpp:1235); carry the clip's colourOffset onto its track so the song
        // view can reproduce the exact hue (e.g. TR-808 offset -60 -> purple).
        if (clipElem.hasAttribute("colourOffset")) {
          try {
            targetTrack.setColourOffset(Integer.parseInt(clipElem.getAttribute("colourOffset")));
          } catch (NumberFormatException ignore) {
            // leave default
          }
        }

        NodeList noteRowList = null;
        if (noteRowsList.getLength() > 0) {
          Element noteRowsElem = (Element) noteRowsList.item(0);
          noteRowList = noteRowsElem.getElementsByTagName("noteRow");
        }
        int rowCount = computeClipRowCount(noteRowList, targetTrack);

        // Determine stepCount: use length attribute if present (1 step = 24/32 ticks of clip
        // time)
        // but also expand to cover the actual note positions in the hex data
        boolean tripletMode =
            "1".equals(clipElem.getAttribute("triplet"))
                || "true".equalsIgnoreCase(clipElem.getAttribute("triplet"));
        int stepTicks = tripletMode ? 32 : 24;
        int stepCount = 16;
        int clipXmlLengthTicks = 0;
        if (clipElem.hasAttribute("length")) {
          clipXmlLengthTicks = Integer.parseInt(clipElem.getAttribute("length"));
          stepCount = clipXmlLengthTicks / stepTicks;
          if (stepCount < 1) stepCount = 16;
        }
        // Expand stepCount to cover max position found in noteData (pos in 12-tick units)
        int maxPos = 0;
        if (noteRowList != null) {
          for (int r = 0; r < noteRowList.getLength(); r++) {
            Element nr = (Element) noteRowList.item(r);
            NoteDataSource noteSrc = readNoteDataSource(nr, false);
            String hd = noteSrc.hex();
            if (hd != null && hd.startsWith("0x")) {
              String data = hd.substring(2);
              int hcpn = noteSrc.hexCharsPerNote();
              for (int p = 0; p + hcpn <= data.length(); p += hcpn) {
                int pos = (int) Long.parseLong(data.substring(p, p + 8), 16);
                if (pos > 2000) {
                  // Bail out - position >2000 ticks means we have likely
                  // wandered into lift-data bytes after the note events
                  break;
                }
                if (pos > maxPos) maxPos = pos;
              }
            }
          }
        }
        int dataStepCount = (maxPos / stepTicks) + 1;
        if (dataStepCount > stepCount) stepCount = dataStepCount;

        ClipModel clip = new ClipModel("SESSION_CLIP " + i, rowCount, stepCount);
        clip.setIsKit(isKitClip);
        // C clip.cpp:698-714 — parse isPlaying, isSoloing, and pre-V2.0 status attribute/tag.
        readClipPlaybackStatus(clipElem, clip);
        // C instrument_clip.cpp:2500 — clipName (with legacy name fallback for mock XML fixtures).
        if (clipElem.hasAttribute("clipName")) {
          clip.setName(clipElem.getAttribute("clipName"));
        } else if (clipElem.hasAttribute("name")) {
          clip.setName(clipElem.getAttribute("name"));
        }
        clip.setTripletMode(tripletMode);
        clip.setPlayDirection(readPlayDirectionAttr(clipElem));
        // C clip.cpp:716-719, definitions_cxx.hpp:450 — read + clamp section as uint8 to 0..23.
        if (clipElem.hasAttribute("section")) {
          clip.setSection(parseClampedSection(clipElem.getAttribute("section")));
        }
        // C clip.cpp:764-766 — readTagOrAttributeValue → stringToLaunchStyle
        clip.setLaunchStyle(DelugeXmlUtil.attrOrDirectChildText(clipElem, "launchStyle"));
        // C instrument_clip.cpp:2552-2562 — readTagOrAttributeValueInt (128 = none)
        clip.setMidiBank(DelugeXmlUtil.attrOrDirectChildInt(clipElem, "midiBank", 128));
        clip.setMidiSub(DelugeXmlUtil.attrOrDirectChildInt(clipElem, "midiSub", 128));
        clip.setMidiPGM(DelugeXmlUtil.attrOrDirectChildInt(clipElem, "midiPGM", 128));

        if (noteRowList != null) {
          java.util.BitSet claimedDrumIndices = new java.util.BitSet();
          for (int r = 0; r < noteRowList.getLength(); r++) {
            Element noteRowElem = (Element) noteRowList.item(r);
            String hexData;

            int rowIdx = r;
            String drumIdxStr = DelugeXmlUtil.attrOrDirectChildText(noteRowElem, "drumIndex");
            if (drumIdxStr != null && !drumIdxStr.isBlank()) {
              int resolvedDrumIdx = resolveXmlDrumIndex(noteRowElem, targetTrack);
              // C instrument_clip.cpp:3939-3992, 4025-4027 — skip negative sentinels (-1 = no drum,
              // -5..-2 = GateDrum) and subsequent NoteRows claiming an already-assigned Drum*.
              if (resolvedDrumIdx < 0 || claimedDrumIndices.get(resolvedDrumIdx)) {
                continue;
              }
              claimedDrumIndices.set(resolvedDrumIdx);
              rowIdx = resolvedDrumIdx;
              clip.getOrCreateRow(rowIdx).setPitch(rowIdx);
              // C note_row.cpp:3263, 3554-3559 — a kit row's finger-level bend range.
              String br = DelugeXmlUtil.attrOrDirectChildText(noteRowElem, "bendRange");
              if (br != null && !br.isBlank()) {
                try {
                  clip.getOrCreateRow(rowIdx).setBendRange(Integer.parseInt(br.trim()));
                } catch (NumberFormatException ignored) {
                }
              }
            } else if (targetTrack instanceof KitTrackModel) {
              claimedDrumIndices.set(rowIdx);
            }
            int notePitch = -1;
            if (noteRowElem.hasAttribute("y")) {
              notePitch = Integer.parseInt(noteRowElem.getAttribute("y"));
            } else {
              NodeList yList = noteRowElem.getElementsByTagName("y");
              if (yList.getLength() > 0) {
                notePitch = Integer.parseInt(yList.item(0).getTextContent());
              }
            }

            // Apply per-row pitch from y attribute before setting grid steps and raw notes so
            // clip.setStep never overwrites rawNotes after clip.setRawNoteEvents.
            if (notePitch >= 0) {
              clip.setRowYNote(rowIdx, notePitch);
            }

            NoteDataSource noteSrc = readNoteDataSource(noteRowElem, false);
            hexData = noteSrc.hex();
            if (hexData != null && !hexData.isEmpty()) {
              int hcpn = noteSrc.hexCharsPerNote();
              // Firmware XML uses dynamic ticks per grid step (triplet alignment check!)
              java.util.List<StepData> row =
                  DelugeNoteDataMapper.decodeRow(hexData, stepCount, stepTicks, hcpn);
              for (int s = 0; s < stepCount; s++) {
                StepData step = row.get(s);
                if (notePitch >= 0) {
                  step = step.withPitch(notePitch);
                }
                clip.setStep(rowIdx, s, step);
              }

              java.util.List<org.deluge.model.NoteModel> rawNotes =
                  DelugeNoteDataMapper.decodeRawNotes(hexData, hcpn);
              clip.setRawNoteEvents(rowIdx, rawNotes);
            }

            applyRowMute(noteRowElem, clip, rowIdx);
            int sessionClipReadUpTo =
                clipXmlLengthTicks > 0
                    ? clipXmlLengthTicks
                    : (clip.getLoopLength() > 0 ? clip.getLoopLength() : Integer.MAX_VALUE);
            parseNoteRowExpressionData(noteRowElem, clip, rowIdx, sessionClipReadUpTo);

            // Parse per-noteRow <soundParams> (35+ hex param overrides)
            NodeList rowSoundParamsList = noteRowElem.getElementsByTagName("soundParams");
            if (rowSoundParamsList.getLength() > 0) {
              Element sp = (Element) rowSoundParamsList.item(0);
              parseNoteRowSoundParams(sp, clip, rowIdx, clipXmlLengthTicks);
            }
          }
        }
        parseColumnControls(clipElem, clip);
        if (!(targetTrack instanceof KitTrackModel)) {
          int sessionClipReadUpTo =
              clipXmlLengthTicks > 0
                  ? clipXmlLengthTicks
                  : (clip.getLoopLength() > 0 ? clip.getLoopLength() : Integer.MAX_VALUE);
          readClipBendRanges(clipElem, clip);
          parseClipExpressionData(clipElem, clip, sessionClipReadUpTo);
        }
        targetTrack.addClip(clip);
        clipOwnerMap.put(clip, targetTrack);
        parsedSessionClips.add(clip);

        // Parse the clip's <soundParams>: STATIC values into the track model (the song format
        // keeps all sound params here), then automation.
        switch (targetTrack) {
          case SynthTrackModel stm -> {
            readClipBendRanges(clipElem, clip);
            // C instrument_clip.cpp:2676-2689 — the clip's own arpeggiator (internal syncLevel).
            Element clipArpEl = getFirstChild(clipElem, "arpeggiator");
            if (clipArpEl != null) {
              clip.setArp(
                  InstrumentXmlParser.parseArpElement(
                      clipArpEl, InstrumentXmlParser.SONG_INPUT_TICK_MAGNITUDE.get(), true));
            }
            NodeList soundParamsList = clipElem.getElementsByTagName("soundParams");
            if (soundParamsList.getLength() > 0) {
              Element spEl = (Element) soundParamsList.item(0);
              // C instrument_clip.cpp:2517-2523, 2656 — bound automation at the clip's own XML
              // length (not stepCount expanded by longer independent noteRows).
              int readUpTo =
                  clipXmlLengthTicks > 0
                      ? clipXmlLengthTicks
                      : (clip.getLoopLength() > 0 ? clip.getLoopLength() : Integer.MAX_VALUE);
              // C: a clip's params = initParams defaults + the clip's own soundParams; the
              // instrument's defaultParams are not consulted (sound.cpp:146-210 + readParams).
              // Only the active clip (index 0) populates the shared track-level SynthTrackModel
              // knobs; non-active clips update their own per-clip ArpModel.
              clipSoundParamsMap.put(clip, spEl);
              clipReadUpToMap.put(clip, readUpTo);
              if (stm.getClips().size() == 1) {
                InstrumentXmlParser.resetClipParamsToFirmwareDefaults(stm);
                InstrumentXmlParser.parseClipSoundParamsStatics(spEl, stm, readUpTo);
              } else {
                InstrumentXmlParser.parseClipUnpatchedArpKnobs(spEl, clip, stm.getDefaultArp());
              }
              if (spEl.getElementsByTagName("patchCable").getLength() > 0) {
                clip.getPatchCables().clear();
                clip.getPatchCables()
                    .addAll(InstrumentXmlParser.parsePatchCablesFromContainer(spEl, readUpTo));
              }
              parseAutomation(spEl, clip);
              parseHexSoundParamsAutomation(spEl, clip.getAutomationCurves(), readUpTo);
            }
            NodeList paramsList2 = clipElem.getElementsByTagName("params");
            if (paramsList2.getLength() > 0) {
              parseParamsAsKitParams((Element) paramsList2.item(0), clip);
            }
          }
          case MidiTrackModel mtm -> {
            readClipBendRanges(clipElem, clip);
            int readUpTo =
                clipXmlLengthTicks > 0
                    ? clipXmlLengthTicks
                    : (clip.getLoopLength() > 0 ? clip.getLoopLength() : Integer.MAX_VALUE);
            Element midiParamsEl = getFirstChild(clipElem, "midiParams");
            if (midiParamsEl != null) {
              parseMidiParams(midiParamsEl, clip, readUpTo);
            }
            NodeList soundParamsList = clipElem.getElementsByTagName("soundParams");
            if (soundParamsList.getLength() > 0) {
              Element spEl = (Element) soundParamsList.item(0);
              parseAutomation(spEl, clip);
            }
            NodeList paramsList2 = clipElem.getElementsByTagName("params");
            if (paramsList2.getLength() > 0) {
              parseParamsAsKitParams((Element) paramsList2.item(0), clip);
            }
          }
          case KitTrackModel ktm -> {
            int readUpTo =
                clipXmlLengthTicks > 0
                    ? clipXmlLengthTicks
                    : (clip.getLoopLength() > 0 ? clip.getLoopLength() : Integer.MAX_VALUE);
            Element kitParamsEl = getFirstChild(clipElem, "kitParams");
            if (kitParamsEl != null) {
              parseKitParamsElement(kitParamsEl, clip, readUpTo);
              if (ktm.getClips().size() == 1) {
                Float vol = clip.getKitParams().get("volume");
                if (vol != null) ktm.setVolume(vol);
                Float pan = clip.getKitParams().get("pan");
                if (pan != null) ktm.setPan(pan);
              }
            }
            // C instrument_clip.cpp:2676-2689 — the kit clip's own arpeggiator (the kit arp).
            // The model keeps one per kit: the first clip's.
            Element arpEl = getFirstChild(clipElem, "arpeggiator");
            if (arpEl != null && ktm.getArp() == null) {
              ktm.setArp(
                  InstrumentXmlParser.parseArpElement(
                      arpEl, InstrumentXmlParser.SONG_INPUT_TICK_MAGNITUDE.get(), true));
            }
          }
          default -> {}
        }
      }
    }

    // ── Parse sections ──
    parseSongSections(songNode, project);
    // ── Parse scales ──
    parseSongScales(songNode, project);

    // ── Parse Arrangement-Only Clips (C song.cpp:2088-2094, 2131-2137) ──
    NodeList arrangementOnlyClipsNodes = songNode.getElementsByTagName("arrangementOnlyClips");
    if (arrangementOnlyClipsNodes.getLength() == 0) {
      arrangementOnlyClipsNodes = songNode.getElementsByTagName("arrangementOnlyTracks");
    }
    if (arrangementOnlyClipsNodes.getLength() > 0) {
      Element arrClipsElem = (Element) arrangementOnlyClipsNodes.item(0);
      NodeList arrChildNodes = arrClipsElem.getChildNodes();
      int arrElemIdx = 0;
      for (int cIdx = 0; cIdx < arrChildNodes.getLength(); cIdx++) {
        if (!(arrChildNodes.item(cIdx) instanceof Element clipElem)) {
          continue;
        }
        String clipTag = clipElem.getTagName();
        if ("audioClip".equals(clipTag)) {
          // C audio_clip.cpp:1130-1132, 1220-1226; song.cpp:3704-3713 — caseless trackName lookup;
          // only fall back to the first AudioTrackModel when trackName is absent/empty.
          String trackName = DelugeXmlUtil.attrOrDirectChildText(clipElem, "trackName");
          AudioTrackModel targetTrack = null;
          if (trackName != null && !trackName.isEmpty()) {
            for (TrackModel t : project.getTracks()) {
              if (t instanceof AudioTrackModel at && trackName.equalsIgnoreCase(at.getName())) {
                targetTrack = at;
                break;
              }
            }
          } else {
            for (TrackModel t : project.getTracks()) {
              if (t instanceof AudioTrackModel at) {
                targetTrack = at;
                break;
              }
            }
          }
          if (targetTrack != null) {
            AudioTrackModel.AudioClip audioClip = parseAudioClipElement(clipElem, trackName, true);
            targetTrack.addAudioClip(audioClip);
            clipOwnerMap.put(audioClip, targetTrack);
            parsedArrangementClips.add(audioClip);
          } else {
            parsedArrangementClips.add(null);
          }
          arrElemIdx++;
          continue;
        }
        if (!"instrumentClip".equals(clipTag)) {
          continue;
        }
        int i = arrElemIdx++;

        // C instrument_clip.cpp:2545-2550, 3902-3919 — CV clips claim a CVChannel (not modelled in
        // Java tracks); keep parsedArrangementClips index aligned without stealing an instrument
        // track.
        if (DelugeXmlUtil.attrOrDirectChildText(clipElem, "cvChannel") != null) {
          parsedArrangementClips.add(null);
          continue;
        }

        boolean arrIsKitClip =
            "KITS".equalsIgnoreCase(clipElem.getAttribute("instrumentPresetFolder"));
        if (!arrIsKitClip) {
          NodeList nrCheck = clipElem.getElementsByTagName("noteRow");
          for (int r = 0; r < nrCheck.getLength() && !arrIsKitClip; r++) {
            if (((Element) nrCheck.item(r)).hasAttribute("drumIndex")) {
              arrIsKitClip = true;
            }
          }
        }

        // C song.cpp:2146-2165 + instrument_clip.cpp:2507-2550, 3902-3919: claimOutput linkage runs
        // FIRST so same-named <sound>/<kit>/<audioTrack> or multi-channel <midi> tracks never
        // collide.
        TrackModel targetTrack =
            claimOutputForClip(clipElem, arrIsKitClip, project.getTracks(), java.util.Set.of());
        String trackName = DelugeXmlUtil.attrOrDirectChildText(clipElem, "trackName");
        boolean hasMidiChannel =
            DelugeXmlUtil.attrOrDirectChildText(clipElem, "midiChannel") != null;
        if (targetTrack == null && trackName != null && !trackName.isEmpty()) {
          for (TrackModel t : project.getTracks()) {
            boolean typeMatches =
                hasMidiChannel
                    ? t instanceof MidiTrackModel
                    : (arrIsKitClip ? t instanceof KitTrackModel : t instanceof SynthTrackModel);
            if (typeMatches && trackName.equalsIgnoreCase(t.getName())) {
              targetTrack = t;
              break;
            }
          }
        }

        // Fallback for unlinked clips (no instrumentPresetName, midiChannel, or trackName): match
        // positional non-AudioTrackModel only.
        if (targetTrack == null
            && (trackName == null || trackName.isEmpty())
            && DelugeXmlUtil.attrOrDirectChildText(clipElem, "instrumentPresetName") == null
            && !hasMidiChannel
            && i < project.getTracks().size()
            && !(project.getTracks().get(i) instanceof AudioTrackModel)) {
          targetTrack = project.getTracks().get(i);
        }

        if (targetTrack != null) {
          // Parse basic details: rowCount, stepCount
          NodeList noteRowList = clipElem.getElementsByTagName("noteRow");
          int rowCount = computeClipRowCount(noteRowList, targetTrack);
          boolean tripletMode =
              "1".equals(clipElem.getAttribute("triplet"))
                  || "true".equalsIgnoreCase(clipElem.getAttribute("triplet"));
          int stepTicks = tripletMode ? 32 : 24;
          int stepCount = 16;

          int arrXmlLengthTicks = 0;
          if (clipElem.hasAttribute("length")) {
            try {
              arrXmlLengthTicks = Integer.parseInt(clipElem.getAttribute("length"));
              stepCount = Math.max(1, arrXmlLengthTicks / stepTicks);
            } catch (NumberFormatException ignored) {
            }
          }

          // Pre-calculate step count limit based on note events bounds
          int maxPos = 0;
          for (int r = 0; r < noteRowList.getLength(); r++) {
            Element nr = (Element) noteRowList.item(r);
            NoteDataSource noteSrc = readNoteDataSource(nr, true);
            String hd = noteSrc.hex();
            if (hd != null && hd.startsWith("0x")) {
              String data = hd.substring(2);
              int hcpn = noteSrc.hexCharsPerNote();
              for (int p = 0; p + hcpn <= data.length(); p += hcpn) {
                try {
                  int pos = (int) Long.parseLong(data.substring(p, p + 8), 16);
                  if (pos > 2000) break;
                  if (pos > maxPos) maxPos = pos;
                } catch (NumberFormatException ignored) {
                }
              }
            }
          }
          int dataStepCount = (maxPos / stepTicks) + 1;
          if (dataStepCount > stepCount) stepCount = dataStepCount;

          ClipModel clip = new ClipModel("ARR_CLIP " + i, rowCount, stepCount);
          clip.setIsKit(targetTrack instanceof KitTrackModel);
          if (clipElem.hasAttribute("clipName")) {
            clip.setName(clipElem.getAttribute("clipName"));
          } else if (clipElem.hasAttribute("name")) {
            clip.setName(clipElem.getAttribute("name"));
          }
          clip.setTripletMode(tripletMode);
          clip.setPlayDirection(readPlayDirectionAttr(clipElem));
          // C song.cpp:2131-2136 — ensure all arrangement-only clips have section == 255.
          clip.setArrangementOnly(true);
          clip.setSoloingInSessionMode(false);
          clip.setLaunchStyle(DelugeXmlUtil.attrOrDirectChildText(clipElem, "launchStyle"));
          clip.setMidiBank(DelugeXmlUtil.attrOrDirectChildInt(clipElem, "midiBank", 128));
          clip.setMidiSub(DelugeXmlUtil.attrOrDirectChildInt(clipElem, "midiSub", 128));
          clip.setMidiPGM(DelugeXmlUtil.attrOrDirectChildInt(clipElem, "midiPGM", 128));
          int readUpTo =
              arrXmlLengthTicks > 0
                  ? arrXmlLengthTicks
                  : (clip.getLoopLength() > 0 ? clip.getLoopLength() : Integer.MAX_VALUE);

          // Parse note row cells
          java.util.BitSet claimedDrumIndices = new java.util.BitSet();
          for (int r = 0; r < noteRowList.getLength(); r++) {
            Element nr = (Element) noteRowList.item(r);
            int rowIdx = r;
            String drumIdxStr = DelugeXmlUtil.attrOrDirectChildText(nr, "drumIndex");
            boolean isDrumRow = drumIdxStr != null && !drumIdxStr.isBlank();
            if (isDrumRow) {
              int resolvedDrumIdx = resolveXmlDrumIndex(nr, targetTrack);
              // C instrument_clip.cpp:3939-3992, 4025-4027 — skip negative sentinels (-1 = no drum,
              // -5..-2 = GateDrum) and subsequent NoteRows claiming an already-assigned Drum*.
              if (resolvedDrumIdx < 0 || claimedDrumIndices.get(resolvedDrumIdx)) {
                continue;
              }
              claimedDrumIndices.set(resolvedDrumIdx);
              rowIdx = resolvedDrumIdx;
              clip.getOrCreateRow(rowIdx).setPitch(rowIdx);
              String br = DelugeXmlUtil.attrOrDirectChildText(nr, "bendRange");
              if (br != null && !br.isBlank()) {
                try {
                  clip.getOrCreateRow(rowIdx).setBendRange(Integer.parseInt(br.trim()));
                } catch (NumberFormatException ignored) {
                }
              }
            } else if (targetTrack instanceof KitTrackModel) {
              claimedDrumIndices.set(rowIdx);
            }
            String noteStr = nr.getAttribute("note");
            if (noteStr == null || noteStr.isEmpty()) {
              noteStr = nr.getAttribute("y");
            }
            int noteVal = noteStr != null && !noteStr.isEmpty() ? Integer.parseInt(noteStr) : 60;
            if (!isDrumRow) {
              clip.setRowYNote(rowIdx, noteVal);
            }

            NoteDataSource noteSrc = readNoteDataSource(nr, true);
            String hd = noteSrc.hex();
            if (hd != null && hd.startsWith("0x") && hd.length() > 2) {
              int hcpn = noteSrc.hexCharsPerNote();
              java.util.List<StepData> row =
                  DelugeNoteDataMapper.decodeRow(hd, stepCount, stepTicks, hcpn);
              for (int s = 0; s < stepCount && s < row.size(); s++) {
                StepData base = row.get(s);
                clip.setStep(rowIdx, s, base.withPitch(isDrumRow ? rowIdx : noteVal));
              }
              clip.setRawNoteEvents(rowIdx, DelugeNoteDataMapper.decodeRawNotes(hd, hcpn));
            }
            applyRowMute(nr, clip, rowIdx);
            parseNoteRowExpressionData(nr, clip, rowIdx, readUpTo);
            NodeList rowSoundParamsList = nr.getElementsByTagName("soundParams");
            if (rowSoundParamsList.getLength() > 0) {
              parseNoteRowSoundParams(
                  (Element) rowSoundParamsList.item(0), clip, rowIdx, arrXmlLengthTicks);
            }
          }

          parseColumnControls(clipElem, clip);
          if (!(targetTrack instanceof KitTrackModel)) {
            readClipBendRanges(clipElem, clip);
            parseClipExpressionData(clipElem, clip, readUpTo);
          }
          targetTrack.addClip(clip);
          clipOwnerMap.put(clip, targetTrack);
          if (targetTrack instanceof SynthTrackModel stm) {
            Element clipArpEl = getFirstChild(clipElem, "arpeggiator");
            if (clipArpEl != null) {
              clip.setArp(
                  InstrumentXmlParser.parseArpElement(
                      clipArpEl, InstrumentXmlParser.SONG_INPUT_TICK_MAGNITUDE.get(), true));
            }
            NodeList soundParamsList = clipElem.getElementsByTagName("soundParams");
            if (soundParamsList.getLength() > 0) {
              Element spEl = (Element) soundParamsList.item(0);
              clipSoundParamsMap.put(clip, spEl);
              clipReadUpToMap.put(clip, readUpTo);
              if (stm.getClips().size() == 1) {
                InstrumentXmlParser.resetClipParamsToFirmwareDefaults(stm);
                InstrumentXmlParser.parseClipSoundParamsStatics(spEl, stm, readUpTo);
              } else {
                InstrumentXmlParser.parseClipUnpatchedArpKnobs(spEl, clip, stm.getDefaultArp());
              }
              if (spEl.getElementsByTagName("patchCable").getLength() > 0) {
                clip.getPatchCables().clear();
                clip.getPatchCables()
                    .addAll(InstrumentXmlParser.parsePatchCablesFromContainer(spEl, readUpTo));
              }
              parseAutomation(spEl, clip);
              parseHexSoundParamsAutomation(spEl, clip.getAutomationCurves(), readUpTo);
            }
          } else if (targetTrack instanceof MidiTrackModel) {
            Element midiParamsEl = getFirstChild(clipElem, "midiParams");
            if (midiParamsEl != null) {
              parseMidiParams(midiParamsEl, clip, readUpTo);
            }
          } else if (targetTrack instanceof KitTrackModel) {
            Element kitParamsEl = getFirstChild(clipElem, "kitParams");
            if (kitParamsEl != null) {
              parseKitParamsElement(kitParamsEl, clip, readUpTo);
            }
          }
          clipOwnerMap.put(clip, targetTrack);
          parsedArrangementClips.add(clip);
        } else {
          parsedArrangementClips.add(null);
        }
      }
    }

    // Legacy fallback: songs with inline <track> clips and no <sessionClips> container.
    if (parsedSessionClips.isEmpty()) {
      for (TrackModel track : project.getTracks()) {
        if (track instanceof AudioTrackModel audioTrack) {
          for (AudioTrackModel.AudioClip clip : audioTrack.getAudioClips()) {
            if (!clip.isArrangementOnly()) {
              parsedSessionClips.add(clip);
              clipOwnerMap.put(clip, track);
            }
          }
        } else {
          for (ClipModel clip : track.getClips()) {
            if (!clip.isArrangementOnly()) {
              parsedSessionClips.add(clip);
              clipOwnerMap.put(clip, track);
            }
          }
        }
      }
    }

    // ── Parse clipInstances / trackInstances and build ArrangerTimeline
    // (C output.cpp:352, 356, 398-402; song.cpp:2209-2258) ──
    java.util.List<Element> clipInstanceElems = new java.util.ArrayList<>(instrumentTrackElems);
    // Fall back to legacy <track>/<instrumentClip> when the instrument children don't carry the
    // clipInstances/trackInstances attribute/tag (older format, or the mock fidelity fixtures).
    boolean anyHasClipInstances =
        clipInstanceElems.stream()
            .anyMatch(
                e ->
                    e.hasAttribute("clipInstances")
                        || e.hasAttribute("trackInstances")
                        || e.getElementsByTagName("clipInstances").getLength() > 0
                        || e.getElementsByTagName("trackInstances").getLength() > 0);
    if (!anyHasClipInstances) {
      clipInstanceElems.clear();
      NodeList legacy = songNode.getElementsByTagName("track");
      if (legacy.getLength() == 0) legacy = songNode.getElementsByTagName("instrumentClip");
      for (int k = 0; k < legacy.getLength(); k++) {
        clipInstanceElems.add((Element) legacy.item(k));
      }
    }
    java.util.Set<Object> claimedArrangementClips =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    for (int t = 0; t < clipInstanceElems.size() && t < project.getTracks().size(); t++) {
      Element trackElem = clipInstanceElems.get(t);
      TrackModel thisOutput = project.getTracks().get(t);

      // C output.cpp:352 — accept both "clipInstances" and legacy "trackInstances".
      String hexStr = "";
      if (trackElem.hasAttribute("clipInstances")) {
        hexStr = trackElem.getAttribute("clipInstances");
      } else if (trackElem.hasAttribute("trackInstances")) {
        hexStr = trackElem.getAttribute("trackInstances");
      } else {
        NodeList ciList = trackElem.getElementsByTagName("clipInstances");
        if (ciList.getLength() == 0) {
          ciList = trackElem.getElementsByTagName("trackInstances");
        }
        if (ciList.getLength() > 0) {
          hexStr = ciList.item(0).getTextContent().trim();
        }
      }

      if (hexStr != null && hexStr.startsWith("0x")) {
        String data = hexStr.substring(2);
        // C output.cpp:356, 398-402 — strictly non-overlapping, positive length, non-overflowing.
        int minPos = 0;
        for (int p = 0; p + 24 <= data.length(); p += 24) {
          String posHex = data.substring(p, p + 8);
          String lenHex = data.substring(p + 8, p + 16);
          String codeHex = data.substring(p + 16, p + 24);

          try {
            int pos = (int) Long.parseLong(posHex, 16);
            int length = (int) Long.parseLong(lenHex, 16);
            long code = Long.parseLong(codeHex, 16);

            if (pos < minPos || length <= 0 || pos > Integer.MAX_VALUE - length) {
              continue;
            }
            minPos = pos + length;

            // C song.cpp:2216-2218 — 0xFFFFFFFF encodes an unbacked/white ClipInstance (nullptr).
            if (code == 0xFFFFFFFFL) {
              project.addArrangerClip(new ArrangerClip(t, null, null, pos, length));
              continue;
            }

            boolean isArrangementClip = (code & 0x80000000L) != 0;
            int lookingForIndex = (int) (code & 0x7FFFFFFFL);
            java.util.List<Object> clips =
                isArrangementClip ? parsedArrangementClips : parsedSessionClips;

            // C song.cpp:2226-2234 (E248) — out-of-bounds index.
            if (lookingForIndex < 0 || lookingForIndex >= clips.size()) {
              continue;
            }
            Object target = clips.get(lookingForIndex);
            if (target == null) {
              continue;
            }

            // C song.cpp:2239-2244 (E451) — Clip belongs to a different Output.
            TrackModel owner = clipOwnerMap.get(target);
            if (owner != null && owner != thisOutput) {
              continue;
            }

            // C song.cpp:2247-2256 (E042) — arrangement-only Clip may only be claimed once.
            if (isArrangementClip && !claimedArrangementClips.add(target)) {
              continue;
            }

            if (target instanceof ClipModel cm) {
              project.addArrangerClip(new ArrangerClip(t, cm, null, pos, length));
            } else if (target instanceof AudioTrackModel.AudioClip ac) {
              project.addArrangerClip(new ArrangerClip(t, null, ac, pos, length));
            }
          } catch (NumberFormatException ignored) {
          }
        }
      }
    }

    // ── Sort out active clips per Output (C song.cpp:4241-4243, 4280-4345; output.cpp:91-118) ──
    sortOutWhichClipsAreActive(
        project,
        parsedSessionClips,
        clipOwnerMap,
        clipSoundParamsMap,
        clipReadUpToMap,
        baseSynthPatchCables);

    return project;
  }

  private static void sortOutWhichClipsAreActive(
      ProjectModel project,
      java.util.List<Object> parsedSessionClips,
      java.util.Map<Object, TrackModel> clipOwnerMap,
      java.util.Map<ClipModel, Element> clipSoundParamsMap,
      java.util.Map<ClipModel, Integer> clipReadUpToMap,
      java.util.Map<SynthTrackModel, java.util.List<PatchCable>> baseSynthPatchCables) {
    // C song.cpp:4241-4243 — isClipActive: soloingInSessionMode || (activeIfNoSolo && !anySoloing).
    boolean anySoloing = false;
    for (Object obj : parsedSessionClips) {
      if (obj instanceof ClipModel c && !c.isArrangementOnly() && c.isSoloingInSessionMode()) {
        anySoloing = true;
        break;
      }
      if (obj instanceof AudioTrackModel.AudioClip ac
          && !ac.isArrangementOnly()
          && ac.isSoloing()) {
        anySoloing = true;
        break;
      }
    }

    java.util.Set<TrackModel> outputsWithActiveClip =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    // Stage 1 (C song.cpp:4280-4307): first active session clip on each Output wins setActiveClip;
    // subsequent active session clips on that same Output are deactivated.
    for (Object obj : parsedSessionClips) {
      TrackModel owner = clipOwnerMap.get(obj);
      if (owner == null) {
        continue;
      }
      if (obj instanceof ClipModel clip && !clip.isArrangementOnly()) {
        boolean clipActive =
            clip.isSoloingInSessionMode() || (clip.isActiveInSession() && !anySoloing);
        if (clipActive) {
          if (!outputsWithActiveClip.add(owner)) {
            if (anySoloing) {
              clip.setSoloingInSessionMode(false);
            } else {
              clip.setActiveInSession(false);
            }
          } else {
            int idx = owner.getClips().indexOf(clip);
            if (idx >= 0) {
              owner.setActiveClipIndex(idx);
            }
          }
        }
      } else if (obj instanceof AudioTrackModel.AudioClip ac && !ac.isArrangementOnly()) {
        boolean clipActive = ac.isSoloing() || (ac.isPlaying() && !anySoloing);
        if (clipActive && !outputsWithActiveClip.add(owner)) {
          if (anySoloing) {
            ac.setSoloing(false);
          } else {
            ac.setPlaying(false);
          }
        }
      }
    }

    // Stage 2 (C song.cpp:4313-4318 + output.cpp:91-104): for any Output still lacking an active
    // clip, check its first non-white ArrangerClip instance before falling back to session clips.
    for (ArrangerClip inst : project.getArrangerTimeline()) {
      int ti = inst.trackIndex();
      if (ti < 0 || ti >= project.getTracks().size()) {
        continue;
      }
      TrackModel owner = project.getTracks().get(ti);
      if (outputsWithActiveClip.contains(owner)) {
        continue;
      }
      if (inst.clip() != null) {
        int idx = owner.getClips().indexOf(inst.clip());
        if (idx >= 0) {
          outputsWithActiveClip.add(owner);
          owner.setActiveClipIndex(idx);
        }
      } else if (inst.audioClip() != null) {
        outputsWithActiveClip.add(owner);
      }
    }

    // Stage 3 (C song.cpp:4325-4345): any Output still without an active clip gets its first
    // session clip (or clip 0 if it only has arrangement clips).
    for (Object obj : parsedSessionClips) {
      TrackModel owner = clipOwnerMap.get(obj);
      if (owner == null || outputsWithActiveClip.contains(owner)) {
        continue;
      }
      if (obj instanceof ClipModel clip) {
        int idx = owner.getClips().indexOf(clip);
        if (idx >= 0) {
          outputsWithActiveClip.add(owner);
          owner.setActiveClipIndex(idx);
        }
      } else if (obj instanceof AudioTrackModel.AudioClip) {
        outputsWithActiveClip.add(owner);
      }
    }

    // Re-apply track-level soundParams / kitParams when the resolved active clip is not index 0.
    for (TrackModel track : project.getTracks()) {
      if (track.getClips().isEmpty() || track.getActiveClipIndex() == 0) {
        continue;
      }
      ClipModel activeClip = track.getActiveClip();
      if (activeClip == null) {
        continue;
      }
      if (track instanceof SynthTrackModel stm) {
        InstrumentXmlParser.resetClipParamsToFirmwareDefaults(stm);
        stm.getModulation().getPatchCables().clear();
        java.util.List<PatchCable> baseCables = baseSynthPatchCables.get(stm);
        if (baseCables != null) {
          stm.getModulation().getPatchCables().addAll(baseCables);
        }
        Element spEl = clipSoundParamsMap.get(activeClip);
        if (spEl != null) {
          int readUpTo = clipReadUpToMap.getOrDefault(activeClip, Integer.MAX_VALUE);
          InstrumentXmlParser.parseClipSoundParamsStatics(spEl, stm, readUpTo);
        }
      } else if (track instanceof KitTrackModel ktm) {
        ktm.setVolume(1.0f);
        ktm.setPan(0.5f);
        Float vol = activeClip.getKitParams().get("volume");
        if (vol != null) ktm.setVolume(vol);
        Float pan = activeClip.getKitParams().get("pan");
        if (pan != null) ktm.setPan(pan);
      }
    }
  }

  // C instrument_clip.cpp:3938-3971, kit.cpp:488-498 — -1 (0xFFFFFFFF) marks "no drum"; any
  // out-of-range non-negative drumIndex (including 65535 or INT32_MAX) wraps to firstDrum (0)
  // without overflowing rowCount + 1 or allocating huge grids.
  private static final int MAX_KIT_ROWS = 256;

  private static int computeClipRowCount(NodeList noteRowList, TrackModel targetTrack) {
    int rowCount = 0;
    if (noteRowList != null) {
      for (int r = 0; r < noteRowList.getLength(); r++) {
        Element rowEl = (Element) noteRowList.item(r);
        String drumIdxStr = DelugeXmlUtil.attrOrDirectChildText(rowEl, "drumIndex");
        if (drumIdxStr != null && !drumIdxStr.isBlank()) {
          int resolvedDi = resolveXmlDrumIndex(rowEl, targetTrack);
          if (resolvedDi >= 0 && resolvedDi + 1 > rowCount) {
            rowCount = resolvedDi + 1;
          }
        } else if (r + 1 > rowCount) {
          rowCount = r + 1;
        }
      }
    }
    if (targetTrack instanceof KitTrackModel kit) {
      rowCount = Math.max(rowCount, kit.getDrums().size());
    }
    if (rowCount == 0) {
      rowCount = (targetTrack instanceof KitTrackModel) ? 1 : 8;
    }
    return rowCount;
  }

  private static int resolveXmlDrumIndex(Element noteRowElem, TrackModel targetTrack) {
    String raw = DelugeXmlUtil.attrOrDirectChildText(noteRowElem, "drumIndex");
    if (raw == null || raw.isBlank()) {
      return -1;
    }
    try {
      int parsed = Integer.parseInt(raw.trim());
      if (parsed < 0) {
        return -1;
      }
      if (targetTrack instanceof KitTrackModel kit && !kit.getDrums().isEmpty()) {
        return parsed >= kit.getDrums().size() ? 0 : parsed;
      }
      return parsed >= MAX_KIT_ROWS ? 0 : parsed;
    } catch (NumberFormatException ignored) {
      return -1;
    }
  }

  // C clip.cpp:44-46, 698-714 — read isPlaying, isSoloing, and legacy pre-V2.0 status tag/attr.
  private static void readClipPlaybackStatus(Element clipElem, ClipModel clip) {
    String isPlaying = DelugeXmlUtil.attrOrDirectChildText(clipElem, "isPlaying");
    if (isPlaying != null && !isPlaying.isBlank()) {
      try {
        String s = isPlaying.trim();
        clip.setActiveInSession("true".equalsIgnoreCase(s) || Integer.parseInt(s) != 0);
      } catch (NumberFormatException ignored) {
      }
    }
    String isSoloing = DelugeXmlUtil.attrOrDirectChildText(clipElem, "isSoloing");
    if (isSoloing != null && !isSoloing.isBlank()) {
      try {
        String s = isSoloing.trim();
        clip.setSoloingInSessionMode("true".equalsIgnoreCase(s) || Integer.parseInt(s) != 0);
      } catch (NumberFormatException ignored) {
      }
    }
    if (isPlaying == null) {
      String status = DelugeXmlUtil.attrOrDirectChildText(clipElem, "status");
      if (status != null && !status.isBlank()) {
        try {
          clip.setSoloingInSessionMode(false);
          clip.setActiveInSession(Integer.parseInt(status.trim()) == 2);
        } catch (NumberFormatException ignored) {
        }
      }
    }
  }

  // C definitions_cxx.hpp:450 — kMaxNumSections = 24; clip.cpp:716-719 clamps uint8 section to 23.
  private static final int MAX_NUM_SECTIONS = 24;

  private static int parseClampedSection(String raw) {
    return Math.min(Integer.parseInt(raw.trim()) & 0xFF, MAX_NUM_SECTIONS - 1);
  }

  private static AudioTrackModel.AudioClip parseAudioClipElement(
      Element clipElem, String trackName, boolean arrangementOnly) {
    AudioTrackModel.AudioClip clip = new AudioTrackModel.AudioClip();
    if (clipElem.hasAttribute("clipName")) {
      clip.setClipName(clipElem.getAttribute("clipName"));
    }
    clip.setTrackName(trackName);
    if (clipElem.hasAttribute("filePath")) {
      clip.setFilePath(clipElem.getAttribute("filePath"));
    }
    if (clipElem.hasAttribute("reversed")) {
      String rev = clipElem.getAttribute("reversed");
      clip.setReversed("1".equals(rev) || "true".equalsIgnoreCase(rev));
    }
    if (clipElem.hasAttribute("startSamplePos")) {
      clip.setStartSamplePos(Integer.parseInt(clipElem.getAttribute("startSamplePos")));
    }
    if (clipElem.hasAttribute("endSamplePos")) {
      clip.setEndSamplePos(Integer.parseInt(clipElem.getAttribute("endSamplePos")));
    }
    // C audio_clip.cpp:1160-1162 — attack is a raw signed int32.
    if (clipElem.hasAttribute("attack")) {
      clip.setAttackRaw(DelugeHexMapper.hexToQ31(clipElem.getAttribute("attack")));
    }
    // C audio_clip.cpp:1154-1158 — linearInterpolation="1" sets InterpolationMode::LINEAR.
    if (clipElem.hasAttribute("linearInterpolation")) {
      String li = clipElem.getAttribute("linearInterpolation");
      clip.setLinearInterpolation("1".equals(li) || "true".equalsIgnoreCase(li));
    }
    if (clipElem.hasAttribute("priority")) {
      clip.setPriority(Integer.parseInt(clipElem.getAttribute("priority")));
    }
    // C audio_clip.cpp:1172-1178 — sampleHolder.transpose / cents.
    if (clipElem.hasAttribute("transpose")) {
      clip.setTranspose(Integer.parseInt(clipElem.getAttribute("transpose")));
    }
    if (clipElem.hasAttribute("cents")) {
      clip.setCents(Integer.parseInt(clipElem.getAttribute("cents")));
    }
    // C audio_clip.cpp:1064, 1081 — firmware writes 0/1; accept both 0/1 and true/false.
    if (clipElem.hasAttribute("pitchSpeedIndependent")) {
      String psi = clipElem.getAttribute("pitchSpeedIndependent");
      clip.setPitchSpeedIndependent("1".equals(psi) || "true".equalsIgnoreCase(psi));
    }
    if (clipElem.hasAttribute("overdubsShouldCloneAudioTrack")) {
      String odc = clipElem.getAttribute("overdubsShouldCloneAudioTrack");
      clip.setOverdubsShouldCloneAudioTrack("1".equals(odc) || "true".equalsIgnoreCase(odc));
    }
    String ip = DelugeXmlUtil.attrOrDirectChildText(clipElem, "isPlaying");
    if (ip != null && !ip.isBlank()) {
      try {
        clip.setPlaying("true".equalsIgnoreCase(ip.trim()) || Integer.parseInt(ip.trim()) != 0);
      } catch (NumberFormatException ignored) {
      }
    }
    String is = DelugeXmlUtil.attrOrDirectChildText(clipElem, "isSoloing");
    if (is != null && !is.isBlank()) {
      try {
        clip.setSoloing("true".equalsIgnoreCase(is.trim()) || Integer.parseInt(is.trim()) != 0);
      } catch (NumberFormatException ignored) {
      }
    }
    if (clipElem.hasAttribute("isArmedForRecording")) {
      String iar = clipElem.getAttribute("isArmedForRecording");
      clip.setArmedForRecording("true".equalsIgnoreCase(iar) || "1".equals(iar));
    }
    if (clipElem.hasAttribute("length")) {
      clip.setLength(Math.max(1, Integer.parseInt(clipElem.getAttribute("length"))));
    }
    // C clip.cpp:760-762 — sequenceDirection on audio clips too.
    clip.setSequenceDirection(readPlayDirectionAttr(clipElem));
    if (clipElem.hasAttribute("colourOffset")) {
      clip.setColourOffset(Integer.parseInt(clipElem.getAttribute("colourOffset")));
    }
    if (arrangementOnly) {
      // C song.cpp:2131-2136 — ensure arrangement-only audio clips have section == 255.
      clip.setArrangementOnly(true);
    } else if (clipElem.hasAttribute("section")) {
      clip.setSection(parseClampedSection(clipElem.getAttribute("section")));
    }
    // C clip.cpp:764-766 — readTagOrAttributeValue → stringToLaunchStyle
    clip.setLaunchStyle(DelugeXmlUtil.attrOrDirectChildText(clipElem, "launchStyle"));
    if (clipElem.hasAttribute("beingEdited")) {
      String be = clipElem.getAttribute("beingEdited");
      clip.setBeingEdited("1".equals(be) || "true".equalsIgnoreCase(be));
    }

    // Parse <params> child element (C audio_clip.cpp:1180-1184, global_effectable.cpp:884-1037).
    NodeList paramsList = clipElem.getElementsByTagName("params");
    if (paramsList.getLength() > 0) {
      Element params = (Element) paramsList.item(0);
      int audioReadUpTo = clip.getLength() > 0 ? clip.getLength() : Integer.MAX_VALUE;
      parseAudioClipParams(params, clip, audioReadUpTo);
    }
    return clip;
  }

  /**
   * C InstrumentClip::claimOutput + Song::getInstrumentFromPresetSlot (song.cpp:3716-3739) with the
   * matchesPreset rules: a MIDI clip ({@code midiChannel}, instrument_clip.cpp:2534-2543) matches a
   * MIDI output by channel and suffix (midi_instrument.h:102-109; MPE zones are channels 16/17); a
   * synth or kit clip matches by type and case-insensitive {@code instrumentPresetName}
   * (instrument.h:56-63). The folder half of that rule is not checked: tracks here do not keep
   * their preset folder. Among equal matches an unclaimed track wins, which is the C's first match
   * whenever names are unique, as on the hardware. Returns null when the clip carries no linkage.
   */
  static TrackModel claimOutputForClip(
      Element clipElem,
      boolean isKitClip,
      java.util.List<TrackModel> tracks,
      java.util.Set<TrackModel> claimed) {
    java.util.function.Predicate<TrackModel> matches;
    String midiChannel = DelugeXmlUtil.attrOrDirectChildText(clipElem, "midiChannel");
    if (midiChannel != null) {
      int channel;
      int suffix = -1;
      try {
        channel = Integer.parseInt(midiChannel.trim());
        String sfx = DelugeXmlUtil.attrOrDirectChildText(clipElem, "midiChannelSuffix");
        if (sfx != null) {
          suffix = Integer.parseInt(sfx.trim());
        }
      } catch (NumberFormatException e) {
        return null;
      }
      if (suffix != -1) {
        return null; // channel suffixes are not modelled on MIDI tracks
      }
      matches =
          t ->
              t instanceof MidiTrackModel m
                  && (m.isMpe()
                          ? ("upper".equalsIgnoreCase(m.getMpeZone()) ? 17 : 16)
                          : m.getMidiChannel() - 1)
                      == channel;
    } else {
      String name = DelugeXmlUtil.attrOrDirectChildText(clipElem, "instrumentPresetName");
      if (name == null || DelugeXmlUtil.attrOrDirectChildText(clipElem, "cvChannel") != null) {
        return null;
      }
      matches =
          t ->
              (isKitClip ? t instanceof KitTrackModel : t instanceof SynthTrackModel)
                  && name.equalsIgnoreCase(t.getName());
    }
    TrackModel first = null;
    for (TrackModel t : tracks) {
      if (matches.test(t)) {
        if (!claimed.contains(t)) {
          return t;
        }
        if (first == null) {
          first = t;
        }
      }
    }
    return first;
  }

  private static MidiTrackModel parseMidiElement(Element soundNode) throws Exception {
    String name = "MIDI";
    if (soundNode.hasAttribute("presetName")) {
      name = soundNode.getAttribute("presetName");
    } else if (soundNode.hasAttribute("name")) {
      name = soundNode.getAttribute("name");
    } else {
      NodeList slotNodes = soundNode.getElementsByTagName("presetSlot");
      if (slotNodes.getLength() > 0) {
        name = "MIDI " + slotNodes.item(0).getTextContent();
      }
    }

    MidiTrackModel midiTrack = new MidiTrackModel(name);
    parseDefaultVelocity(soundNode, midiTrack);

    // Parse MIDI Channel or MPE Zone. C <midi> outputs carry the slot as attribute "channel"
    // (0..15) or "zone" (MIDIInstrument::readTagFromFile, midi_instrument.cpp:470-512, with the
    // nightly-bug "midiChannel" form also 0-based). The Java's own legacy <sound> form wrote a
    // <midiChannel> child holding the 1-based model channel.
    boolean cFormat = !"sound".equals(soundNode.getTagName());
    String chanText = DelugeXmlUtil.attrOrDirectChildText(soundNode, "channel");
    if (chanText == null) {
      chanText = DelugeXmlUtil.attrOrDirectChildText(soundNode, "midiChannel");
    } else {
      cFormat = true;
    }
    if (chanText != null) {
      try {
        int chan = Integer.parseInt(chanText.trim());
        midiTrack.setMidiChannel(cFormat ? chan + 1 : chan);
      } catch (NumberFormatException ignored) {
      }
    }

    String zoneAttr = soundNode.getAttribute("zone");
    if (zoneAttr != null && !zoneAttr.isBlank()) {
      midiTrack.setMpe(true);
      midiTrack.setMpeZone(zoneAttr.trim());
    }
    Element zoneEl = null;
    NodeList zoneNodes = soundNode.getElementsByTagName("zone");
    for (int z = 0; z < zoneNodes.getLength(); z++) {
      if (zoneNodes.item(z).getParentNode() == soundNode) {
        zoneEl = (Element) zoneNodes.item(z);
        break;
      }
    }
    if (zoneEl != null) {
      midiTrack.setMpe(true);
      midiTrack.setMpeZone(zoneEl.getTextContent().trim());
    }

    // Parse Device Info
    NodeList deviceNodes = soundNode.getElementsByTagName("midiDevice");
    if (deviceNodes.getLength() > 0) {
      Element devNode = (Element) deviceNodes.item(0);
      NodeList nameNodes = devNode.getElementsByTagName("name");
      if (nameNodes.getLength() > 0) {
        midiTrack.setDeviceName(nameNodes.item(0).getTextContent().trim());
        midiTrack.setName(
            midiTrack.getDeviceName()); // Auto-name MIDI track by external device name!
      }
      NodeList fileNodes = devNode.getElementsByTagName("definitionFile");
      if (fileNodes.getLength() > 0) {
        midiTrack.setDeviceDefinitionFile(fileNodes.item(0).getTextContent().trim());
      }
    }

    // Parse CC Labels
    NodeList ccLabelsList = soundNode.getElementsByTagName("ccLabels");
    if (ccLabelsList.getLength() > 0) {
      Element ccNode = (Element) ccLabelsList.item(0);
      org.w3c.dom.NodeList children = ccNode.getChildNodes();
      for (int i = 0; i < children.getLength(); i++) {
        org.w3c.dom.Node child = children.item(i);
        if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
          String tagName = child.getNodeName();
          try {
            String cleanTagName = tagName;
            if (cleanTagName.startsWith("cc")) {
              cleanTagName = cleanTagName.substring(2);
            }
            int ccNumber = Integer.parseInt(cleanTagName);
            String label = child.getTextContent().trim();
            midiTrack.setCcLabel(ccNumber, label);
          } catch (NumberFormatException ignored) {
          }
        }
      }
    }

    // Parse gold-knob MIDI-CC assignments. C: MIDIInstrument::readModKnobsFromFile
    // (midi_instrument.cpp readMIDIParamFromFile call site) - 16 <modKnob cc="..."/> children in
    // mode*2+knobIndex order; "none"/"bend"/"aftertouch" are the C's special sentinel strings
    // (CC_NUMBER_NONE=123/PITCH_BEND=120/AFTERTOUCH=121, definitions_cxx.hpp:734-737), anything
    // else is a plain CC number 0-119 (or 122=CC_NUMBER_Y_AXIS, which the C also writes as a
    // plain integer - see writeDataToFile, midi_instrument.cpp:298-334).
    NodeList modKnobsList = soundNode.getElementsByTagName("modKnobs");
    if (modKnobsList.getLength() > 0) {
      Element mkNode = (Element) modKnobsList.item(0);
      NodeList modKnobNodes = mkNode.getElementsByTagName("modKnob");
      for (int m = 0;
          m < modKnobNodes.getLength() && m < 8 * 2 /* NUM_MOD_KNOB_MODES * knobs-per-mode */;
          m++) {
        Element mk = (Element) modKnobNodes.item(m);
        String cc = mk.getAttribute("cc");
        int mode = m / 2;
        int knobIndex = m % 2;
        int ccValue =
            switch (cc) {
              case "none" -> org.deluge.model.MidiTrackModel.CC_NUMBER_NONE;
              case "bend" -> 120; // CC_NUMBER_PITCH_BEND
              case "aftertouch" -> 121; // CC_NUMBER_AFTERTOUCH
              default -> {
                try {
                  yield Integer.parseInt(cc);
                } catch (NumberFormatException e) {
                  yield org.deluge.model.MidiTrackModel.CC_NUMBER_NONE;
                }
              }
            };
        midiTrack.setModKnobCc(mode, knobIndex, ccValue);
      }
    }

    return midiTrack;
  }

  private static SynthTrackModel parseSynthElement(Element soundNode) throws Exception {
    String name = "SYNTH";
    if (soundNode.hasAttribute("presetName")) {
      name = soundNode.getAttribute("presetName");
    } else if (soundNode.hasAttribute("name")) {
      name = soundNode.getAttribute("name");
    } else {
      NodeList slotNodes = soundNode.getElementsByTagName("presetSlot");
      if (slotNodes.getLength() > 0) {
        name = "SYNTH " + slotNodes.item(0).getTextContent();
      }
    }

    SynthTrackModel synth = new SynthTrackModel(name);
    if (soundNode.hasAttribute("isMutedInArrangement")) {
      synth.setMutedInArrangement("1".equals(soundNode.getAttribute("isMutedInArrangement")));
    }
    if (soundNode.hasAttribute("isSoloingInArrangement")) {
      synth.setSoloingInArrangement("1".equals(soundNode.getAttribute("isSoloingInArrangement")));
    }
    InstrumentXmlParser.populateSynth(soundNode, synth);
    parseDefaultVelocity(soundNode, synth);
    return synth;
  }

  private static AudioTrackModel parseAudioTrackElement(Element audioTrackNode) {
    String name = "AUDIO";
    if (audioTrackNode.hasAttribute("name")) {
      name = audioTrackNode.getAttribute("name").trim();
    }

    AudioTrackModel track = new AudioTrackModel(name);

    // Root attributes
    if (audioTrackNode.hasAttribute("inputChannel")) {
      // Not stored on model yet — informational
    }
    if (audioTrackNode.hasAttribute("isArmedForRecording")) {
      String armed = audioTrackNode.getAttribute("isArmedForRecording");
      track.setMuted(!"1".equals(armed) && !"true".equalsIgnoreCase(armed));
      // armed ↔ not muted, conceptually
    }
    if (audioTrackNode.hasAttribute("isMutedInArrangement")) {
      track.setMutedInArrangement("1".equals(audioTrackNode.getAttribute("isMutedInArrangement")));
    }
    if (audioTrackNode.hasAttribute("isSoloingInArrangement")) {
      track.setSoloingInArrangement(
          "1".equals(audioTrackNode.getAttribute("isSoloingInArrangement")));
    }
    parseClippingAmount(audioTrackNode, track);
    if (audioTrackNode.hasAttribute("lpfMode")) {
      // Store as track colour/label info — not on AudioTrackModel yet
    }
    if (audioTrackNode.hasAttribute("modFXType")) {
      // Not stored on model yet
    }
    if (audioTrackNode.hasAttribute("activeModFunction")) {
      // Not stored on model yet
    }

    // Delay child element
    Element delayEl = getFirstChild(audioTrackNode, "delay");
    if (delayEl != null) {
      // Not stored on AudioTrackModel yet — future field: delay settings
    }

    // Compressor child element
    Element compEl = getFirstChild(audioTrackNode, "compressor");
    if (compEl != null) {
      // Not stored on AudioTrackModel yet — future field: compressor settings
    }

    return track;
  }

  private static void parseAudioClipParams(
      Element params, AudioTrackModel.AudioClip clip, int readUpTo) {
    // Hex float attributes (0x-prefixed)
    readAttrFloatHex(params, "volume", clip::setVolume, true);
    readAttrFloatHex(params, "pan", clip::setPan, false);
    readAttrFloatHex(params, "reverbAmount", clip::setReverbAmount, true);
    readAttrFloatHex(params, "sidechainCompressorShape", clip::setSidechainShape, true);
    readAttrFloatHex(params, "sidechainCompressorVolume", clip::setSidechainVolume, true);
    readAttrFloatHex(params, "modFXRate", clip::setModFXRate, true);
    readAttrFloatHex(params, "modFXDepth", clip::setModFXDepth, true);
    readAttrFloatHex(params, "modFXOffset", clip::setModFXOffset, true);
    readAttrFloatHex(params, "modFXFeedback", clip::setModFXFeedback, true);
    readAttrFloatHex(params, "stutterRate", clip::setStutterRate, true);
    readAttrFloatHex(params, "sampleRateReduction", clip::setSampleRateReduction, true);
    readAttrFloatHex(params, "bitCrush", clip::setBitCrush, true);
    readAttrFloatHex(params, "delayRate", clip::setDelayRate, true);
    readAttrFloatHex(params, "delayFeedback", clip::setDelayFeedback, true);
    String lpfVal = readAttr(params, "lpfFrequency");
    if (lpfVal != null && !lpfVal.isEmpty()) {
      clip.setLpfFrequency(DelugeHexMapper.hexToHz(lpfVal));
    }
    readAttrFloatHex(params, "lpfResonance", clip::setLpfResonance, true);
    String hpfVal = readAttr(params, "hpfFrequency");
    if (hpfVal != null && !hpfVal.isEmpty()) {
      clip.setHpfFrequency(DelugeHexMapper.hexToHz(hpfVal));
    }
    readAttrFloatHex(params, "hpfResonance", clip::setHpfResonance, true);
    // C audio_clip.cpp / mod_controllable_audio.cpp — eqBass and eqTreble are bipolar (-1..+1).
    readAttrFloatHex(params, "eqBass", clip::setEqBass, false);
    readAttrFloatHex(params, "eqTreble", clip::setEqTreble, false);
    readAttrFloatHex(params, "eqBassFrequency", clip::setEqBassFrequency, true);
    readAttrFloatHex(params, "eqTrebleFrequency", clip::setEqTrebleFrequency, true);

    // Child elements for delay, lpf, hpf, equalizer (mirror GlobalEffectable::readParamTagFromFile,
    // C global_effectable.cpp:902-963, mod_controllable_audio.cpp:557-582)
    // <delay> child: rate, feedback
    Element delayChild = getFirstChild(params, "delay");
    if (delayChild != null) {
      readAttrFloatHex(delayChild, "rate", clip::setDelayRate, true);
      readAttrFloatHex(delayChild, "feedback", clip::setDelayFeedback, true);
    }
    // <lpf> child: frequency, resonance
    Element lpfChild = getFirstChild(params, "lpf");
    if (lpfChild != null) {
      String freqVal = readAttr(lpfChild, "frequency");
      if (freqVal != null && !freqVal.isEmpty()) {
        clip.setLpfFrequency(DelugeHexMapper.hexToHz(freqVal));
      }
      readAttrFloatHex(lpfChild, "resonance", clip::setLpfResonance, true);
    }
    // <hpf> child: frequency, resonance
    Element hpfChild = getFirstChild(params, "hpf");
    if (hpfChild != null) {
      String freqVal = readAttr(hpfChild, "frequency");
      if (freqVal != null && !freqVal.isEmpty()) {
        clip.setHpfFrequency(DelugeHexMapper.hexToHz(freqVal));
      }
      readAttrFloatHex(hpfChild, "resonance", clip::setHpfResonance, true);
    }
    // <equalizer> child: bass, treble, bassFrequency, trebleFrequency
    Element eqChild = getFirstChild(params, "equalizer");
    if (eqChild != null) {
      readAttrFloatHex(eqChild, "bass", clip::setEqBass, false);
      readAttrFloatHex(eqChild, "treble", clip::setEqTreble, false);
      readAttrFloatHex(eqChild, "bassFrequency", clip::setEqBassFrequency, true);
      readAttrFloatHex(eqChild, "trebleFrequency", clip::setEqTrebleFrequency, true);
    }

    parseHexGlobalEffectableAutomation(
        params, clip.getAutomationCurves(), clip.getRawParamHex(), readUpTo);
  }

  private static void parseNoteRowSoundParams(
      Element sp, ClipModel clip, int row, int clipXmlLengthTicks) {
    // List of known hex float parameter attributes in <soundParams>
    String[] hexParams = {
      "oscAVolume",
      "oscBVolume",
      "noiseVolume",
      "volume",
      "pan",
      "lpfFrequency",
      "lpfResonance",
      "lpfMorph",
      "hpfFrequency",
      "hpfResonance",
      "hpfMorph",
      "lfo1Rate",
      "lfo2Rate",
      "modulator1Amount",
      "modulator1Feedback",
      "modulator2Amount",
      "modulator2Feedback",
      "carrier1Feedback",
      "carrier2Feedback",
      "modFXRate",
      "modFXDepth",
      "modFXOffset",
      "modFXFeedback",
      "delayRate",
      "delayFeedback",
      "reverbAmount",
      "arpeggiatorGate",
      "portamento",
      "stutterRate",
      "sampleRateReduction",
      "bitCrush",
      "compressorAttack",
      "compressorRelease"
    };
    for (String param : hexParams) {
      String val = readDirectAttrOrChild(sp, param);
      if (val != null && val.startsWith("0x") && !val.isBlank()) {
        clip.setRowSoundParam(row, param, toUnipolar(DelugeHexMapper.hexToFloat(val)));
      }
    }
    // Decimal Hz fallback for legacy mock/fixture XML attributes -> exponential Q31 unipolar float
    // so ClipModel.setSound / unipolarFloatToQ31Unified recovers exact hardware cutoff Q31.
    String lpfHz = sp.getAttribute("lpfFrequency");
    if (lpfHz != null && !lpfHz.isBlank() && !lpfHz.startsWith("0x")) {
      try {
        clip.setRowSoundParam(
            row,
            "lpfFrequency",
            DelugeHexMapper.q31ToUnipolarFloatUnified(
                DelugeHexMapper.hzToQ31(Float.parseFloat(lpfHz))));
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }
    String hpfHz = sp.getAttribute("hpfFrequency");
    if (hpfHz != null && !hpfHz.isBlank() && !hpfHz.startsWith("0x")) {
      try {
        clip.setRowSoundParam(
            row,
            "hpfFrequency",
            DelugeHexMapper.q31ToUnipolarFloatUnified(
                DelugeHexMapper.hzToQ31(Float.parseFloat(hpfHz))));
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }
    // envelope1-4 child elements with hex ADSR values
    String[] envTags = {"envelope1", "envelope2", "envelope3", "envelope4"};
    for (String envTag : envTags) {
      NodeList envNodes = sp.getElementsByTagName(envTag);
      if (envNodes.getLength() > 0) {
        Element envEl = (Element) envNodes.item(0);
        EnvelopeModel env = parseEnvelopeElement(envEl);
        clip.setRowSoundParam(row, envTag + "_attack", env.attack());
        clip.setRowSoundParam(row, envTag + "_decay", env.decay());
        clip.setRowSoundParam(row, envTag + "_sustain", env.sustain());
        clip.setRowSoundParam(row, envTag + "_release", env.release());
      }
    }
    // equalizer child element (C mod_controllable_audio.cpp:557-582: bass/treble are bipolar,
    // bassFrequency/trebleFrequency are unipolar).
    NodeList eqNodes = sp.getElementsByTagName("equalizer");
    if (eqNodes.getLength() > 0) {
      Element eq = (Element) eqNodes.item(0);
      String bassVal = readDirectAttrOrChild(eq, "bass");
      if (bassVal != null && !bassVal.isBlank())
        clip.setRowSoundParam(row, "eqBass", DelugeHexMapper.hexToFloat(bassVal));
      String trebleVal = readDirectAttrOrChild(eq, "treble");
      if (trebleVal != null && !trebleVal.isBlank())
        clip.setRowSoundParam(row, "eqTreble", DelugeHexMapper.hexToFloat(trebleVal));
      String bassFreqVal = readDirectAttrOrChild(eq, "bassFrequency");
      if (bassFreqVal != null && !bassFreqVal.isBlank())
        clip.setRowSoundParam(
            row, "eqBassFrequency", toUnipolar(DelugeHexMapper.hexToFloat(bassFreqVal)));
      String trebleFreqVal = readDirectAttrOrChild(eq, "trebleFrequency");
      if (trebleFreqVal != null && !trebleFreqVal.isBlank())
        clip.setRowSoundParam(
            row, "eqTrebleFrequency", toUnipolar(DelugeHexMapper.hexToFloat(trebleFreqVal)));
    }
    parseNoteRowAutomation(sp, clip, row);
    NoteRowModel rowModel = clip.getOrCreateRow(row);
    int fallbackClipLen =
        clipXmlLengthTicks > 0
            ? clipXmlLengthTicks
            : (clip.getLoopLength() > 0 ? clip.getLoopLength() : Integer.MAX_VALUE);
    int rowReadUpTo =
        rowModel.getLoopLengthIfIndependent() > 0
            ? rowModel.getLoopLengthIfIndependent()
            : fallbackClipLen;
    parseHexSoundParamsAutomation(sp, rowModel.getAutomationCurves(), rowReadUpTo);
    // C note_row.cpp:3292 -> Sound::readParamsFromFile (sound.cpp:4043-4046) — kit row patchCables.
    if (sp.getElementsByTagName("patchCable").getLength() > 0) {
      rowModel.getPatchCables().clear();
      rowModel
          .getPatchCables()
          .addAll(InstrumentXmlParser.parsePatchCablesFromContainer(sp, rowReadUpTo));
    }
  }

  /**
   * Parses 16-char automation hex nodes ({@code 0xCCCCCCCCVVVVVVVVPPPPPPPP...}) from {@code
   * <soundParams>} attributes and child elements (C auto_param.cpp:2075-2148, sound.cpp:3776-4042,
   * mod_controllable_audio.cpp:557-670).
   */
  static void parseHexSoundParamsAutomation(
      Element spEl,
      Map<String, List<org.deluge.modulation.automation.ParamNode>> targetCurves,
      int readAutomationUpToPos) {
    org.w3c.dom.NamedNodeMap attrs = spEl.getAttributes();
    for (int a = 0; a < attrs.getLength(); a++) {
      org.w3c.dom.Node attr = attrs.item(a);
      List<org.deluge.modulation.automation.ParamNode> nodes =
          DelugeHexMapper.parseHexAutomationNodes(attr.getNodeValue(), readAutomationUpToPos);
      if (!nodes.isEmpty()) {
        targetCurves.put(attr.getNodeName(), nodes);
      }
    }

    NodeList children = spEl.getChildNodes();
    for (int c = 0; c < children.getLength(); c++) {
      if (children.item(c).getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) {
        continue;
      }
      Element child = (Element) children.item(c);
      String tag = child.getTagName();
      if ("envelope1".equals(tag)
          || "envelope2".equals(tag)
          || "envelope3".equals(tag)
          || "envelope4".equals(tag)) {
        for (String sub : new String[] {"attack", "decay", "sustain", "release"}) {
          String raw = readDirectAttrOrChild(child, sub);
          List<org.deluge.modulation.automation.ParamNode> nodes =
              DelugeHexMapper.parseHexAutomationNodes(raw, readAutomationUpToPos);
          if (!nodes.isEmpty()) {
            targetCurves.put(tag + "_" + sub, nodes);
          }
        }
      } else if ("equalizer".equals(tag)) {
        String[][] eqMap = {
          {"bass", "eqBass"},
          {"treble", "eqTreble"},
          {"bassFrequency", "eqBassFrequency"},
          {"trebleFrequency", "eqTrebleFrequency"}
        };
        for (String[] pair : eqMap) {
          String raw = readDirectAttrOrChild(child, pair[0]);
          List<org.deluge.modulation.automation.ParamNode> nodes =
              DelugeHexMapper.parseHexAutomationNodes(raw, readAutomationUpToPos);
          if (!nodes.isEmpty()) {
            targetCurves.put(pair[1], nodes);
          }
        }
      } else if (!hasElementChildren(child)) {
        List<org.deluge.modulation.automation.ParamNode> nodes =
            DelugeHexMapper.parseHexAutomationNodes(child.getTextContent(), readAutomationUpToPos);
        if (!nodes.isEmpty()) {
          targetCurves.put(tag, nodes);
        }
      }
    }
  }

  private static boolean hasElementChildren(Element el) {
    NodeList nl = el.getChildNodes();
    for (int i = 0; i < nl.getLength(); i++) {
      if (nl.item(i).getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
        return true;
      }
    }
    return false;
  }

  private static void parseNoteRowAutomation(Element soundParamsEl, ClipModel clip, int rowIndex) {
    NodeList autoNodes = soundParamsEl.getElementsByTagName("automation");
    if (autoNodes.getLength() == 0) return;
    Element autoEl = (Element) autoNodes.item(0);

    NodeList children = autoEl.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      if (children.item(i).getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
      Element paramEl = (Element) children.item(i);
      String paramName = paramEl.getNodeName();

      NodeList nodes = paramEl.getElementsByTagName("autoNode");
      for (int j = 0; j < nodes.getLength(); j++) {
        Element nodeEl = (Element) nodes.item(j);
        String posStr = nodeEl.getAttribute("pos");
        String valStr = nodeEl.getAttribute("val");
        if (!posStr.isEmpty() && !valStr.isEmpty()) {
          try {
            int pos = Integer.parseInt(posStr);
            float val = Float.parseFloat(valStr);
            clip.setRowAutomation(rowIndex, paramName, pos, Math.max(0.0f, Math.min(1.0f, val)));
          } catch (NumberFormatException ignored) {
          }
        }
      }
    }
  }

  static void parseAutomation(Element soundNode, ClipModel clip) {
    NodeList autoNodes = soundNode.getElementsByTagName("automation");
    if (autoNodes.getLength() == 0) return;
    Element autoElem = (Element) autoNodes.item(0);
    NodeList paramNodes = autoElem.getElementsByTagName("param");
    for (int p = 0; p < paramNodes.getLength(); p++) {
      Element paramElem = (Element) paramNodes.item(p);
      String paramName = paramElem.getAttribute("name");
      if (paramName == null || paramName.isBlank()) continue;
      NodeList stepNodes = paramElem.getElementsByTagName("step");
      for (int s = 0; s < stepNodes.getLength(); s++) {
        Element stepElem = (Element) stepNodes.item(s);
        try {
          int idx = Integer.parseInt(stepElem.getAttribute("index"));
          float val = Float.parseFloat(stepElem.getTextContent().trim());
          if (idx >= 0 && idx < clip.getStepCount()) {
            clip.setAutomation(paramName, idx, Math.max(0.0f, Math.min(1.0f, val)));
          }
        } catch (NumberFormatException ignored) {
          // skip malformed step entries
        }
      }
    }
  }

  private static void parseColumnControls(Element clipElem, ClipModel clip) {
    NodeList ccNodes = clipElem.getElementsByTagName("columnControls");
    if (ccNodes.getLength() == 0) return;
    Element ccEl = (Element) ccNodes.item(0);

    NodeList leftList = ccEl.getElementsByTagName("leftCol");
    if (leftList.getLength() > 0) {
      Element leftEl = (Element) leftList.item(0);
      if (leftEl.hasAttribute("type")) {
        clip.setLeftCol(leftEl.getAttribute("type"));
      }
    }

    NodeList rightList = ccEl.getElementsByTagName("rightCol");
    if (rightList.getLength() > 0) {
      Element rightEl = (Element) rightList.item(0);
      if (rightEl.hasAttribute("type")) {
        clip.setRightCol(rightEl.getAttribute("type"));
      }
    }
  }

  private static String readDirectAttrOrChild(Element parent, String tagName) {
    String val = parent.getAttribute(tagName);
    if (val != null && !val.isEmpty()) {
      return val.trim();
    }
    Element child = directChild(parent, tagName);
    if (child == null) {
      return null;
    }
    String childVal = child.getAttribute("value");
    if (childVal == null || childVal.isEmpty()) {
      childVal = child.getTextContent();
    }
    return (childVal == null || childVal.isBlank()) ? null : childVal.trim();
  }

  private static void parseSongFx(Element songNode, ProjectModel project) {
    // ── Song-level Filter Modes & Route (song.cpp:2098 -> mod_controllable_audio.cpp:723-733) ──
    String songLpfMode = readDirectAttrOrChild(songNode, "lpfMode");
    if (songLpfMode != null) {
      if ("24dB".equalsIgnoreCase(songLpfMode)) {
        project.setSongLpfMode(org.deluge.model.FilterMode.LADDER_24);
      } else if ("DRIVE".equalsIgnoreCase(songLpfMode)
          || "24dBDrive".equalsIgnoreCase(songLpfMode)) {
        project.setSongLpfMode(org.deluge.model.FilterMode.DRIVE);
      } else if ("SVF".equalsIgnoreCase(songLpfMode)) {
        project.setSongLpfMode(org.deluge.model.FilterMode.SVF);
      } else if ("SVF_BAND".equalsIgnoreCase(songLpfMode)
          || "SVF Band".equalsIgnoreCase(songLpfMode)) {
        project.setSongLpfMode(org.deluge.model.FilterMode.SVF_BAND);
      } else if ("SVF_NOTCH".equalsIgnoreCase(songLpfMode)
          || "SVF Notch".equalsIgnoreCase(songLpfMode)) {
        project.setSongLpfMode(org.deluge.model.FilterMode.SVF_NOTCH);
      } else if ("Off".equalsIgnoreCase(songLpfMode)) {
        project.setSongLpfMode(org.deluge.model.FilterMode.OFF);
      } else {
        project.setSongLpfMode(org.deluge.model.FilterMode.LADDER_12);
      }
    }
    String songHpfMode = readDirectAttrOrChild(songNode, "hpfMode");
    if (songHpfMode != null) {
      if ("12dB".equalsIgnoreCase(songHpfMode)
          || "24dB".equalsIgnoreCase(songHpfMode)
          || "24dBDrive".equalsIgnoreCase(songHpfMode)) {
        // C mod_controllable_audio.cpp:727-729 -> stringToLPFType: LP strings in hpfMode match no
        // branch of the HPF dispatch (filter_set.cpp:25-41, 177-206), leaving HPF inert (OFF).
        project.setSongHpfMode(org.deluge.model.FilterMode.OFF);
      } else if ("SVF".equalsIgnoreCase(songHpfMode)) {
        project.setSongHpfMode(org.deluge.model.FilterMode.SVF);
      } else if ("SVF_BAND".equalsIgnoreCase(songHpfMode)
          || "SVF Band".equalsIgnoreCase(songHpfMode)) {
        project.setSongHpfMode(org.deluge.model.FilterMode.SVF_BAND);
      } else if ("SVF_NOTCH".equalsIgnoreCase(songHpfMode)
          || "SVF Notch".equalsIgnoreCase(songHpfMode)) {
        project.setSongHpfMode(org.deluge.model.FilterMode.SVF_NOTCH);
      } else if ("Off".equalsIgnoreCase(songHpfMode)) {
        project.setSongHpfMode(org.deluge.model.FilterMode.OFF);
      } else {
        project.setSongHpfMode(org.deluge.model.FilterMode.LADDER_12);
      }
    }
    String songFilterRoute = readDirectAttrOrChild(songNode, "filterRoute");
    if (songFilterRoute != null) {
      if ("L2H".equalsIgnoreCase(songFilterRoute)
          || "LPF_TO_HPF".equalsIgnoreCase(songFilterRoute)) {
        project.setSongFilterRoute(1);
      } else if ("PARA".equalsIgnoreCase(songFilterRoute)
          || "PARALLEL".equalsIgnoreCase(songFilterRoute)) {
        project.setSongFilterRoute(2);
      } else {
        project.setSongFilterRoute(0);
      }
    }
    // C song.cpp:2098 -> global_effectable.cpp:1063-1066 & mod_controllable_audio.cpp:736-739 —
    // song-level modFXType and clippingAmount.
    String songModFxType = readDirectAttrOrChild(songNode, "modFXType");
    if (songModFxType != null && !songModFxType.isEmpty()) {
      project.setSongModFXType(songModFxType);
    }
    String songClipping = readDirectAttrOrChild(songNode, "clippingAmount");
    if (songClipping != null && !songClipping.isEmpty()) {
      try {
        project.setSongClippingAmount(Integer.parseInt(songClipping));
      } catch (NumberFormatException ignored) {
      }
    }

    // ── Reverb (C song.cpp:1447-1524) — direct child of <song>, never a descendant <sound>. ──
    Element rev = directChild(songNode, "reverb");
    if (rev != null) {
      readSongIntAttr(rev, "roomSize", project::setReverbRoomSize, 0.6f);
      readSongIntAttr(rev, "dampening", project::setReverbDampening, 0.72f);
      String widthRaw = readDirectAttrOrChild(rev, "width");
      if (widthRaw != null) {
        try {
          int widthInt = (int) (long) Long.decode(widthRaw);
          if (widthInt == Integer.MIN_VALUE) {
            // C song.cpp:1465-1467 — V2.1.0-beta1 and alphas saved width as -2147483648 instead of
            // 2147483647; fix on read.
            widthInt = Integer.MAX_VALUE;
          }
          project.setReverbWidth(
              Math.max(0.0f, Math.min(1.0f, (float) widthInt / Integer.MAX_VALUE)));
        } catch (NumberFormatException ignored) {
        }
      }
      readSongRawAttr(rev, "hpf", project::setReverbHpf, 0.0f);
      readSongRawAttr(rev, "lpf", project::setReverbLpf, 1.0f);
      // Pan is bipolar (negative=left, positive=right, matching C audio_engine.cpp's signed
      // panAmount) - unlike width/hpf/lpf, it must NOT go through readSongRawAttr's Math.abs(), or
      // a left-panned reverb would silently become right-panned instead.
      readSongSignedRawAttr(rev, "pan", project::setReverbPan, 0.0f);
      String modelRaw = readDirectAttrOrChild(rev, "model");
      if (modelRaw != null) {
        try {
          project.setReverbModel(Integer.parseInt(modelRaw));
        } catch (NumberFormatException ignored) {
        }
      }
      // Nested compressor inside reverb (C song.cpp:1484-1514).
      Element rc = directChild(rev, "compressor");
      if (rc != null) {
        readSongRawAttr(rc, "attack", project::setReverbCompressorAttack, 0.0f);
        readSongRawAttr(rc, "release", project::setReverbCompressorRelease, 0.0f);
        String syncRaw = readDirectAttrOrChild(rc, "syncLevel");
        if (syncRaw != null) {
          try {
            project.setReverbCompressorSyncLevel(Integer.parseInt(syncRaw));
          } catch (NumberFormatException ignored) {
          }
        }
        readSongRawAttr(rc, "compHPF", project::setReverbCompHpf, 0.0f);
        readSongRawAttr(rc, "compBlend", project::setReverbCompBlend, 0.5f);
        // C song.cpp:1495-1502 — volume (< 0 means auto mode) and shape are signed Q31 integers.
        readSongSignedRawAttr(rc, "shape", project::setReverbCompressorShape, 0.0f);
        readSongSignedRawAttr(rc, "volume", project::setReverbCompressorVolume, 0.0f);
      }
    }

    // ── Delay (C mod_controllable_audio.cpp:787-841) ──
    Element del = directChild(songNode, "delay");
    if (del != null) {
      // C mod_controllable_audio.cpp:789-790 — <delay> resets syncType=EVEN and syncLevel=NONE
      // before reading child/attribute values, so omitting syncLevel inside <delay> means unsynced.
      project.setDelaySyncType(0);
      project.setDelaySyncLevel(0);
      String pp = readDirectAttrOrChild(del, "pingPong");
      if (pp != null) {
        try {
          // C mod_controllable_audio.cpp:819 — std::clamp(contents, 0_i32, 1_i32).
          project.setDelayPingPong(Math.max(0, Math.min(1, Integer.parseInt(pp))));
        } catch (NumberFormatException ignored) {
        }
      }
      String analog = readDirectAttrOrChild(del, "analog");
      if (analog != null) {
        try {
          // C mod_controllable_audio.cpp:824 — std::clamp(contents, 0_i32, 1_i32).
          project.setDelayAnalog(Math.max(0, Math.min(1, Integer.parseInt(analog))));
        } catch (NumberFormatException ignored) {
        }
      }
      String syncLevel = readDirectAttrOrChild(del, "syncLevel");
      if (syncLevel != null) {
        try {
          project.setDelaySyncLevel(Integer.parseInt(syncLevel));
        } catch (NumberFormatException ignored) {
        }
      }
      String syncType = readDirectAttrOrChild(del, "syncType");
      if (syncType != null) {
        try {
          project.setDelaySyncType(DelugeXmlUtil.syncTypeFromFile(Integer.parseInt(syncType)));
        } catch (NumberFormatException ignored) {
        }
      }
    }

    // ── Sidechain (C mod_controllable_audio.cpp:883-919) ──
    Element sc = directChild(songNode, "sidechain");
    if (sc != null) {
      readSongRawAttr(sc, "attack", project::setSidechainAttack, 0.0f);
      readSongRawAttr(sc, "release", project::setSidechainRelease, 0.0f);
      String syncLevel = readDirectAttrOrChild(sc, "syncLevel");
      if (syncLevel != null) {
        try {
          project.setSidechainSyncLevel(Integer.parseInt(syncLevel));
        } catch (NumberFormatException ignored) {
        }
      }
      String syncType = readDirectAttrOrChild(sc, "syncType");
      if (syncType != null) {
        try {
          project.setSidechainSyncType(DelugeXmlUtil.syncTypeFromFile(Integer.parseInt(syncType)));
        } catch (NumberFormatException ignored) {
        }
      }
    }

    // ── Audio Compressor (C mod_controllable_audio.cpp:843-881) + songCompressor fallback ──
    Element ac = directChild(songNode, "audioCompressor");
    if (ac == null) {
      ac = directChild(songNode, "songCompressor");
    }
    if (ac != null) {
      readSongRawAttr(ac, "attack", project::setCompressorAttack, 0.0f);
      readSongRawAttr(ac, "release", project::setCompressorRelease, 0.0f);
      readSongRawAttr(ac, "thresh", project::setCompressorThreshold, 0.0f);
      readSongRawAttr(ac, "ratio", project::setCompressorRatio, 0.0f);
      readSongRawAttr(ac, "compHPF", project::setCompressorHpf, 0.0f);
      readSongRawAttr(ac, "compBlend", project::setCompressorBlend, 1.0f);
    }

    // ── Stutter (C mod_controllable_audio.cpp:760-785) ──
    Element st = directChild(songNode, "stutter");
    if (st != null) {
      project.setSongStutterQuantized(
          Math.max(0, Math.min(1, attrOrDirectChildInt(st, "quantized", 1))) != 0);
      project.setSongStutterReversed(
          Math.max(0, Math.min(1, attrOrDirectChildInt(st, "reverse", 0))) != 0);
      project.setSongStutterPingPong(
          Math.max(0, Math.min(1, attrOrDirectChildInt(st, "pingPong", 0))) != 0);
    }

    // ── SongParams ──
    Element sp = directChild(songNode, "songParams");
    if (sp != null) {
      readSongHexAttr(sp, "reverbAmount", project::setSongParamReverbAmount, true);
      readSongHexAttr(sp, "volume", project::setSongParamVolume, true);
      readSongHexAttr(sp, "pan", project::setSongParamPan, false);
      readSongHexAttr(sp, "sidechainCompressorShape", project::setSongParamSidechainShape, true);
      readSongHexAttr(sp, "sidechainCompressorVolume", project::setSongParamSidechainVolume, true);
      readSongHexAttr(sp, "modFXRate", project::setSongParamModFXRate, true);
      readSongHexAttr(sp, "modFXDepth", project::setSongParamModFXDepth, true);
      readSongHexAttr(sp, "modFXOffset", project::setSongParamModFXOffset, true);
      readSongHexAttr(sp, "modFXFeedback", project::setSongParamModFXFeedback, true);
      readSongHexAttr(sp, "stutterRate", project::setSongParamStutterRate, true);
      readSongHexAttr(sp, "sampleRateReduction", project::setSongParamSampleRateReduction, true);
      readSongHexAttr(sp, "bitCrush", project::setSongParamBitCrush, true);
      readSongHexAttr(sp, "compressorThreshold", project::setSongParamCompressorThreshold, true);
      readSongHexAttr(sp, "lpfMorph", project::setSongParamLpfMorph, true);
      readSongHexAttr(sp, "hpfMorph", project::setSongParamHpfMorph, true);
      String modFXParam = sp.getAttribute("modFXCurrentParam");
      if (modFXParam != null && !modFXParam.isEmpty()) {
        project.setModFXCurrentParam(modFXParam);
      }
      String filterType = sp.getAttribute("currentFilterType");
      if (filterType != null && !filterType.isEmpty()) {
        project.setCurrentFilterType(filterType);
      }
      // Child elements
      Element spDelay = directChild(sp, "delay");
      if (spDelay != null) {
        readSongHexAttr(spDelay, "rate", project::setSongParamDelayRate, true);
        readSongHexAttr(spDelay, "feedback", project::setSongParamDelayFeedback, true);
      }
      Element spLpf = directChild(sp, "lpf");
      if (spLpf != null) {
        readSongHzHexAttr(spLpf, "frequency", project::setSongParamLpfFrequency);
        readSongHexAttr(spLpf, "resonance", project::setSongParamLpfResonance, true);
        readSongHexAttr(
            spLpf, "morph", project::setSongParamLpfMorph, true); // C global_effectable.cpp:933-937
      }
      Element spHpf = directChild(sp, "hpf");
      if (spHpf != null) {
        readSongHzHexAttr(spHpf, "frequency", project::setSongParamHpfFrequency);
        readSongHexAttr(spHpf, "resonance", project::setSongParamHpfResonance, true);
        readSongHexAttr(
            spHpf, "morph", project::setSongParamHpfMorph, true); // C global_effectable.cpp:956-960
      }
      Element spEq = directChild(sp, "equalizer");
      if (spEq == null) {
        spEq = directChild(sp, "eq");
      }
      if (spEq != null) {
        readSongHexAttr(spEq, "bass", project::setSongParamEqBass, false);
        readSongHexAttr(spEq, "treble", project::setSongParamEqTreble, false);
        readSongHexAttr(spEq, "bassFrequency", project::setSongParamEqBassFrequency, true);
        readSongHexAttr(spEq, "trebleFrequency", project::setSongParamEqTrebleFrequency, true);
      }

      // C song.cpp:2070-2073 — GlobalEffectableForClip::readParamsFromFile(..., 2147483647).
      parseHexGlobalEffectableAutomation(
          sp,
          project.getSongParamAutomationCurves(),
          project.getRawSongParamHex(),
          Integer.MAX_VALUE);

      // C global_effectable.cpp:848, 980-983, song.cpp:3121 — <songParams tempo="0x..."> stores
      // UNPATCHED_TEMPO (BPM * 100). If <song> carried neither tempo nor timePerTimerTick, derive
      // BPM from <songParams tempo="0x..."> while keeping rawSongParamHex("tempo") intact.
      String rawTempo = project.getRawSongParamHex().get("tempo");
      if (rawTempo != null
          && !songNode.hasAttribute("tempo")
          && !songNode.hasAttribute("timePerTimerTick")) {
        int intTempo = DelugeHexMapper.hexToQ31(rawTempo);
        if (intTempo > 0) {
          project.setBpm(intTempo / 100.0f);
          project.setRawSongParamHex("tempo", rawTempo);
        }
      }
      project.rebuildSongParamManager();
    }
  }

  private static void parseSongSections(Element songNode, ProjectModel project) {
    NodeList sectionsNodes = songNode.getElementsByTagName("sections");
    if (sectionsNodes.getLength() == 0) return;
    Element sectionsEl = (Element) sectionsNodes.item(0);
    NodeList sectionList = sectionsEl.getElementsByTagName("section");
    for (int i = 0; i < sectionList.getLength(); i++) {
      Element sec = (Element) sectionList.item(i);
      int id = sec.hasAttribute("id") ? Integer.parseInt(sec.getAttribute("id")) : i;
      int numRepeats =
          sec.hasAttribute("numRepeats") ? Integer.parseInt(sec.getAttribute("numRepeats")) : 0;
      SongSection section = new SongSection("Section " + id);
      section.setNumRepeats(numRepeats);
      project.addSongSection(section);
    }
  }

  private static void parseSongScales(Element songNode, ProjectModel project) {
    NodeList scalesNodes = songNode.getElementsByTagName("scales");
    if (scalesNodes.getLength() == 0) return;
    Element scalesEl = (Element) scalesNodes.item(0);
    NodeList us = scalesEl.getElementsByTagName("userScale");
    if (us.getLength() > 0) {
      try {
        project.setUserScale(Integer.parseInt(us.item(0).getTextContent()));
      } catch (NumberFormatException e) {
      }
    }
    NodeList dps = scalesEl.getElementsByTagName("disabledPresetScales");
    if (dps.getLength() > 0) {
      try {
        project.setDisabledPresetScales(Integer.parseInt(dps.item(0).getTextContent()));
      } catch (NumberFormatException e) {
      }
    }
  }

  private static void readSongIntAttr(
      Element el, String attr, java.util.function.Consumer<Float> setter, float def) {
    String val = readDirectAttrOrChild(el, attr);
    if (val == null || val.isEmpty()) return;
    try {
      // Deluge stores these as raw int32 bit patterns; treat as hex→float
      setter.accept(Math.abs(DelugeHexMapper.hexToFloat(val)));
    } catch (Exception e) {
      // fall through, don't set
    }
  }

  private static void readSongRawAttr(
      Element el, String attr, java.util.function.Consumer<Float> setter, float def) {
    String val = readDirectAttrOrChild(el, attr);
    if (val == null || val.isEmpty()) return;
    try {
      setter.accept(Math.abs(DelugeHexMapper.hexToFloat(val)));
    } catch (Exception e) {
    }
  }

  /** Like {@link #readSongRawAttr} but preserves sign - for bipolar values such as reverb pan. */
  private static void readSongSignedRawAttr(
      Element el, String attr, java.util.function.Consumer<Float> setter, float def) {
    String val = readDirectAttrOrChild(el, attr);
    if (val == null || val.isEmpty()) return;
    try {
      setter.accept(DelugeHexMapper.hexToFloat(val));
    } catch (Exception e) {
    }
  }

  private static void readSongHexAttr(
      Element el, String attr, java.util.function.Consumer<Float> setter, boolean unipolar) {
    String raw = readDirectAttrOrChild(el, attr);
    if (raw == null) return;
    String val = raw.trim();
    if (val.isEmpty() || !val.startsWith("0x")) return;
    try {
      float f = DelugeHexMapper.hexToFloat(val);
      if (unipolar) {
        f = toUnipolar(f);
      }
      setter.accept(f);
    } catch (Exception e) {
    }
  }

  private static void readSongHexAttr(
      Element el, String attr, java.util.function.Consumer<Float> setter) {
    readSongHexAttr(el, attr, setter, true);
  }

  private static void readSongHzHexAttr(
      Element el, String attr, java.util.function.Consumer<Float> setter) {
    String raw = readDirectAttrOrChild(el, attr);
    if (raw == null) return;
    String val = raw.trim();
    if (val.isEmpty() || !val.startsWith("0x")) return;
    try {
      setter.accept(DelugeHexMapper.hexToHz(val));
    } catch (Exception e) {
    }
  }

  private static void parseKitParamsElement(Element kp, ClipModel clip, int readUpTo) {
    parseParamsAsKitParams(kp, clip, readUpTo);
  }

  private static void parseParamsAsKitParams(Element params, ClipModel clip) {
    int readUpTo = clip.getLoopLength() > 0 ? clip.getLoopLength() : Integer.MAX_VALUE;
    parseParamsAsKitParams(params, clip, readUpTo);
  }

  private static void parseParamsAsKitParams(Element params, ClipModel clip, int readUpTo) {
    readKitHexAttr(params, "reverbAmount", clip, "reverbAmount", true);
    readKitHexAttr(params, "volume", clip, "volume", true);
    readKitHexAttr(params, "pan", clip, "pan", false);
    readKitHexAttr(params, "sidechainCompressorShape", clip, "sidechainCompressorShape", true);
    readKitHexAttr(params, "sidechainCompressorVolume", clip, "sidechainCompressorVolume", true);
    readKitHexAttr(params, "modFXRate", clip, "modFXRate", true);
    readKitHexAttr(params, "modFXDepth", clip, "modFXDepth", true);
    readKitHexAttr(params, "modFXOffset", clip, "modFXOffset", true);
    readKitHexAttr(params, "modFXFeedback", clip, "modFXFeedback", true);
    readKitHexAttr(params, "stutterRate", clip, "stutterRate", true);
    readKitHexAttr(params, "sampleRateReduction", clip, "sampleRateReduction", true);
    readKitHexAttr(params, "bitCrush", clip, "bitCrush", true);
    readKitHexAttr(params, "compressorThreshold", clip, "compressorThreshold", true);
    readKitHexAttr(params, "lpfMorph", clip, "lpfMorph", true);
    readKitHexAttr(params, "hpfMorph", clip, "hpfMorph", true);
    // Child elements (delay, lpf, hpf, equalizer)
    parseKitParamChildElements(params, clip);
    parseHexGlobalEffectableAutomation(
        params, clip.getAutomationCurves(), clip.getRawKitParamHex(), readUpTo);
  }

  private static void parseKitParamChildElements(Element parent, ClipModel clip) {
    NodeList delay = parent.getElementsByTagName("delay");
    if (delay.getLength() > 0) {
      Element d = (Element) delay.item(0);
      readKitHexAttr(d, "rate", clip, "delayRate", true);
      readKitHexAttr(d, "feedback", clip, "delayFeedback", true);
    }
    NodeList lpf = parent.getElementsByTagName("lpf");
    if (lpf.getLength() > 0) {
      Element l = (Element) lpf.item(0);
      readKitHzHexAttr(l, "frequency", clip, "lpfFrequency");
      readKitHexAttr(l, "resonance", clip, "lpfResonance", true);
      readKitHexAttr(l, "morph", clip, "lpfMorph", true); // C global_effectable.cpp:933-937
    }
    NodeList hpf = parent.getElementsByTagName("hpf");
    if (hpf.getLength() > 0) {
      Element h = (Element) hpf.item(0);
      readKitHzHexAttr(h, "frequency", clip, "hpfFrequency");
      readKitHexAttr(h, "resonance", clip, "hpfResonance", true);
      readKitHexAttr(h, "morph", clip, "hpfMorph", true); // C global_effectable.cpp:956-960
    }
    NodeList eq = parent.getElementsByTagName("equalizer");
    if (eq.getLength() == 0) {
      eq = parent.getElementsByTagName("eq");
    }
    if (eq.getLength() > 0) {
      Element eqEl = (Element) eq.item(0);
      readKitHexAttr(eqEl, "bass", clip, "eqBass", false);
      readKitHexAttr(eqEl, "treble", clip, "eqTreble", false);
      readKitHexAttr(eqEl, "bassFrequency", clip, "eqBassFrequency", true);
      readKitHexAttr(eqEl, "trebleFrequency", clip, "eqTrebleFrequency", true);
    }
  }

  /**
   * Parses 8-char base hex values and 16-char automation hex nodes ({@code
   * 0xCCCCCCCCVVVVVVVVPPPPPPPP...}) from {@code <kitParams>} or {@code <audioClip><params>}
   * attributes and child elements (C global_effectable.cpp:884-1037,
   * mod_controllable_audio.cpp:552-714, auto_param.cpp:2075-2148).
   */
  private static void parseHexGlobalEffectableAutomation(
      Element paramsEl,
      Map<String, List<org.deluge.modulation.automation.ParamNode>> targetCurves,
      Map<String, String> targetRawHex,
      int readAutomationUpToPos) {
    org.w3c.dom.NamedNodeMap attrs = paramsEl.getAttributes();
    for (int a = 0; a < attrs.getLength(); a++) {
      org.w3c.dom.Node attr = attrs.item(a);
      recordGlobalEffectableHexValue(
          attr.getNodeName(),
          attr.getNodeValue(),
          targetCurves,
          targetRawHex,
          readAutomationUpToPos);
    }

    NodeList children = paramsEl.getChildNodes();
    for (int c = 0; c < children.getLength(); c++) {
      if (!(children.item(c) instanceof Element child)) {
        continue;
      }
      String tag = child.getTagName();
      if ("delay".equals(tag)) {
        recordGlobalEffectableHexValue(
            "delayRate",
            readDirectAttrOrChild(child, "rate"),
            targetCurves,
            targetRawHex,
            readAutomationUpToPos);
        recordGlobalEffectableHexValue(
            "delayFeedback",
            readDirectAttrOrChild(child, "feedback"),
            targetCurves,
            targetRawHex,
            readAutomationUpToPos);
      } else if ("lpf".equals(tag)) {
        recordGlobalEffectableHexValue(
            "lpfFrequency",
            readDirectAttrOrChild(child, "frequency"),
            targetCurves,
            targetRawHex,
            readAutomationUpToPos);
        recordGlobalEffectableHexValue(
            "lpfResonance",
            readDirectAttrOrChild(child, "resonance"),
            targetCurves,
            targetRawHex,
            readAutomationUpToPos);
        recordGlobalEffectableHexValue(
            "lpfMorph",
            readDirectAttrOrChild(child, "morph"),
            targetCurves,
            targetRawHex,
            readAutomationUpToPos);
      } else if ("hpf".equals(tag)) {
        recordGlobalEffectableHexValue(
            "hpfFrequency",
            readDirectAttrOrChild(child, "frequency"),
            targetCurves,
            targetRawHex,
            readAutomationUpToPos);
        recordGlobalEffectableHexValue(
            "hpfResonance",
            readDirectAttrOrChild(child, "resonance"),
            targetCurves,
            targetRawHex,
            readAutomationUpToPos);
        recordGlobalEffectableHexValue(
            "hpfMorph",
            readDirectAttrOrChild(child, "morph"),
            targetCurves,
            targetRawHex,
            readAutomationUpToPos);
      } else if ("equalizer".equals(tag) || "eq".equals(tag)) {
        String[][] eqMap = {
          {"bass", "eqBass"},
          {"treble", "eqTreble"},
          {"bassFrequency", "eqBassFrequency"},
          {"trebleFrequency", "eqTrebleFrequency"}
        };
        for (String[] pair : eqMap) {
          recordGlobalEffectableHexValue(
              pair[1],
              readDirectAttrOrChild(child, pair[0]),
              targetCurves,
              targetRawHex,
              readAutomationUpToPos);
        }
      } else if (!hasElementChildren(child)) {
        recordGlobalEffectableHexValue(
            tag, child.getTextContent(), targetCurves, targetRawHex, readAutomationUpToPos);
      }
    }
  }

  private static void recordGlobalEffectableHexValue(
      String key,
      String rawHex,
      Map<String, List<org.deluge.modulation.automation.ParamNode>> targetCurves,
      Map<String, String> targetRawHex,
      int readAutomationUpToPos) {
    if (key == null || rawHex == null || rawHex.isBlank()) {
      return;
    }
    String baseHex = DelugeHexMapper.extractBaseHex(rawHex);
    if (baseHex != null) {
      targetRawHex.put(key, baseHex);
    }
    List<org.deluge.modulation.automation.ParamNode> nodes =
        DelugeHexMapper.parseHexAutomationNodes(rawHex, readAutomationUpToPos);
    if (!nodes.isEmpty()) {
      targetCurves.put(key, nodes);
    }
  }

  private record NoteDataSource(String hex, int hexCharsPerNote) {}

  private static String readNoteTagOrAttr(Element el, String name) {
    String attr = el.getAttribute(name);
    if (attr != null && !attr.isEmpty()) return attr.trim();
    NodeList list = el.getElementsByTagName(name);
    if (list.getLength() > 0) {
      String txt = list.item(0).getTextContent();
      if (txt != null && !txt.isEmpty()) return txt.trim();
    }
    return null;
  }

  /**
   * C note_row.cpp:3521-3537 — reads note hex data from {@code noteDataWithSplitProb} (28), {@code
   * noteDataWithIteranceAndFill} (26), {@code noteDataWithLift} (22), or {@code noteData} (20).
   * Prefers the richest format present so round-tripped files carrying both {@code
   * noteDataWithLift} and {@code noteDataWithSplitProb} retain conditions and split probability.
   */
  private static NoteDataSource readNoteDataSource(
      Element noteRowElem, boolean allowLiftActionsAttr) {
    String split = readNoteTagOrAttr(noteRowElem, "noteDataWithSplitProb");
    if (split != null) {
      return new NoteDataSource(split, DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_SPLIT);
    }
    String iterFill = readNoteTagOrAttr(noteRowElem, "noteDataWithIteranceAndFill");
    if (iterFill != null) {
      return new NoteDataSource(
          iterFill, DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_ITERANCE_AND_FILL);
    }
    if (allowLiftActionsAttr) {
      String liftActions = noteRowElem.getAttribute("liftActions");
      if (liftActions != null && !liftActions.isEmpty()) {
        return new NoteDataSource(liftActions.trim(), DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_LIFT);
      }
    }
    String lift = readNoteTagOrAttr(noteRowElem, "noteDataWithLift");
    if (lift != null) {
      return new NoteDataSource(lift, DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_LIFT);
    }
    String old = readNoteTagOrAttr(noteRowElem, "noteData");
    if (old != null) {
      int hcpn = DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_OLD;
      if (old.startsWith("0x")) {
        int dataLen = old.length() - 2;
        if (dataLen > 0
            && dataLen % DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_SPLIT == 0
            && dataLen % DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_OLD != 0) {
          hcpn = DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_SPLIT;
        }
      }
      return new NoteDataSource(old, hcpn);
    }
    return new NoteDataSource(null, DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_OLD);
  }

  private static void readKitHexAttr(
      Element el, String attr, ClipModel clip, String paramName, boolean unipolar) {
    String raw = readDirectAttrOrChild(el, attr);
    if (raw == null) return;
    String val = raw.trim();
    if (val.isEmpty() || !val.startsWith("0x")) return;
    try {
      float f = DelugeHexMapper.hexToFloat(val);
      if (unipolar) {
        f = toUnipolar(f);
      }
      clip.setKitParam(paramName, f);
    } catch (Exception e) {
    }
  }

  private static void readKitHzHexAttr(Element el, String attr, ClipModel clip, String paramName) {
    String raw = readDirectAttrOrChild(el, attr);
    if (raw == null) return;
    String val = raw.trim();
    if (val.isEmpty() || !val.startsWith("0x")) return;
    try {
      clip.setKitParam(paramName, DelugeHexMapper.hexToHz(val));
    } catch (Exception e) {
    }
  }

  /**
   * C note_row.cpp:3220-3221, 3253-3261 reads "muted", "length" (loopLengthIfIndependent), and
   * "sequenceDirection" tag-or-attributes on {@code <noteRow>}.
   */
  private static void applyRowMute(Element noteRowElem, ClipModel clip, int r) {
    if (DelugeXmlUtil.attrOrDirectChildInt(noteRowElem, "muted", 0) != 0) {
      clip.getOrCreateRow(r).setMuted(true);
    }
    int indLen = DelugeXmlUtil.attrOrDirectChildInt(noteRowElem, "length", 0);
    if (indLen > 0) {
      clip.getOrCreateRow(r).setLoopLengthIfIndependent(indLen);
    }
    String seqDir = DelugeXmlUtil.attrOrDirectChildText(noteRowElem, "sequenceDirection");
    if (seqDir != null && !seqDir.isBlank()) {
      clip.getOrCreateRow(r)
          .setSequenceDirectionMode(
              org.deluge.model.SequenceDirection.fromFirmwareString(seqDir.trim()));
    }
  }

  /** C instrument_clip.cpp:2888-2898 — non-kit clips' "bendRange" / "bendRangeMPE". */
  private static void readClipBendRanges(Element clipElem, ClipModel clip) {
    String main = DelugeXmlUtil.attrOrDirectChildText(clipElem, "bendRange");
    String mpe = DelugeXmlUtil.attrOrDirectChildText(clipElem, "bendRangeMPE");
    try {
      if (main != null && !main.isBlank()) clip.setBendRange(Integer.parseInt(main.trim()));
      if (mpe != null && !mpe.isBlank()) clip.setBendRangeMPE(Integer.parseInt(mpe.trim()));
    } catch (NumberFormatException ignored) {
    }
  }

  /**
   * C {@code InstrumentClip::readMIDIParamsFromFile} ({@code instrument_clip.cpp:3033-3137}) —
   * reads {@code <param><cc>...</cc><value>0x...</value></param>} entries inside {@code
   * <midiParams>}.
   */
  private static void parseMidiParams(Element midiParamsEl, ClipModel clip, int readUpTo) {
    NodeList children = midiParamsEl.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      if (!(children.item(i) instanceof Element paramEl)) {
        continue;
      }
      if (!"param".equals(paramEl.getTagName())) {
        continue;
      }
      String ccStr = DelugeXmlUtil.attrOrDirectChildText(paramEl, "cc");
      String valueStr = DelugeXmlUtil.attrOrDirectChildText(paramEl, "value");
      if (ccStr == null || ccStr.isBlank() || valueStr == null || valueStr.isBlank()) {
        continue;
      }
      String trimmedCc = ccStr.trim();
      if ("none".equalsIgnoreCase(trimmedCc)
          || "bend".equalsIgnoreCase(trimmedCc)
          || "aftertouch".equalsIgnoreCase(trimmedCc)) {
        // C instrument_clip.cpp:3051-3070, 3093 — "none" skips value; pre-V3.2 "bend"/"aftertouch"
        // migrated to expressionParams.
        continue;
      }
      int cc;
      try {
        cc = Integer.parseInt(trimmedCc);
      } catch (NumberFormatException e) {
        continue;
      }
      if (cc < 0 || cc >= org.deluge.modulation.midi.MidiParamCollection.NUM_REAL_CC_NUMBERS) {
        continue;
      }
      int baseValueQ31 = DelugeHexMapper.hexToQ31(valueStr);
      java.util.List<org.deluge.modulation.automation.ParamNode> nodes =
          DelugeHexMapper.parseHexAutomationNodes(valueStr, readUpTo);
      clip.setMidiParam(cc, baseValueQ31, nodes);
    }
  }

  /**
   * C {@code InstrumentClip::readFromFile} ({@code instrument_clip.cpp:2857-2886}) + {@code
   * ExpressionParamSet::readFromFile} ({@code param_set.cpp:673-698}) — reads legacy direct {@code
   * pitchBend}/{@code yExpression}/{@code channelPressure} tags or attributes on {@code
   * <instrumentClip>}, plus the {@code <expressionData>} child element.
   */
  private static void parseClipExpressionData(Element clipElem, ClipModel clip, int readUpTo) {
    for (String legacyName : new String[] {"pitchBend", "yExpression", "channelPressure"}) {
      String raw = DelugeXmlUtil.attrOrDirectChildText(clipElem, legacyName);
      if (raw != null && !raw.isBlank()) {
        int dim = org.deluge.modulation.params.ExpressionParamSet.dimensionFromXmlName(legacyName);
        int baseValueQ31 = DelugeHexMapper.hexToQ31(raw);
        java.util.List<org.deluge.modulation.automation.ParamNode> nodes =
            DelugeHexMapper.parseHexAutomationNodes(raw, readUpTo);
        clip.setExpressionParam(dim, baseValueQ31, nodes);
      }
    }
    Element exprEl = DelugeXmlUtil.directChild(clipElem, "expressionData");
    if (exprEl != null) {
      readExpressionDimensionElementsAndAttrs(
          exprEl,
          readUpTo,
          (dim, baseValueQ31, nodes) -> clip.setExpressionParam(dim, baseValueQ31, nodes));
    }
  }

  /**
   * C {@code NoteRow::readFromFile} ({@code note_row.cpp:3258-3263, 3539-3559}) — reads per-row
   * {@code bendRange} and {@code <expressionData>} bounded by the row's independent length when
   * present.
   */
  private static void parseNoteRowExpressionData(
      Element noteRowElem, ClipModel clip, int rowIndex, int clipReadUpTo) {
    String br = DelugeXmlUtil.attrOrDirectChildText(noteRowElem, "bendRange");
    if (br != null && !br.isBlank()) {
      try {
        clip.getOrCreateRow(rowIndex).setBendRange(Integer.parseInt(br.trim()));
      } catch (NumberFormatException ignored) {
      }
    }
    Element exprEl = DelugeXmlUtil.directChild(noteRowElem, "expressionData");
    if (exprEl == null) {
      return;
    }
    NoteRowModel rowModel = clip.getOrCreateRow(rowIndex);
    int rowReadUpTo =
        rowModel.getLoopLengthIfIndependent() > 0
            ? rowModel.getLoopLengthIfIndependent()
            : clipReadUpTo;
    readExpressionDimensionElementsAndAttrs(
        exprEl,
        rowReadUpTo,
        (dim, baseValueQ31, nodes) -> rowModel.setExpressionParam(dim, baseValueQ31, nodes));
  }

  @FunctionalInterface
  private interface ExpressionDimensionConsumer {
    void accept(
        int dimension,
        int baseValueQ31,
        java.util.List<org.deluge.modulation.automation.ParamNode> nodes);
  }

  private static void readExpressionDimensionElementsAndAttrs(
      Element exprEl, int readUpTo, ExpressionDimensionConsumer consumer) {
    org.w3c.dom.NamedNodeMap attrs = exprEl.getAttributes();
    for (int a = 0; a < attrs.getLength(); a++) {
      org.w3c.dom.Node attr = attrs.item(a);
      int dim =
          org.deluge.modulation.params.ExpressionParamSet.dimensionFromXmlName(attr.getNodeName());
      if (dim < 0) {
        continue;
      }
      String raw = attr.getNodeValue();
      if (raw == null || raw.isBlank()) {
        continue;
      }
      consumer.accept(
          dim,
          DelugeHexMapper.hexToQ31(raw),
          DelugeHexMapper.parseHexAutomationNodes(raw, readUpTo));
    }
    for (org.w3c.dom.Node n = exprEl.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (!(n instanceof Element child)) {
        continue;
      }
      int dim =
          org.deluge.modulation.params.ExpressionParamSet.dimensionFromXmlName(child.getTagName());
      if (dim < 0) {
        continue;
      }
      String raw = child.getAttribute("value");
      if (raw == null || raw.isBlank()) {
        raw = child.getTextContent();
      }
      if (raw == null || raw.isBlank()) {
        continue;
      }
      consumer.accept(
          dim,
          DelugeHexMapper.hexToQ31(raw.trim()),
          DelugeHexMapper.parseHexAutomationNodes(raw.trim(), readUpTo));
    }
  }
}
