package org.deluge.xml;

import static org.deluge.xml.DelugeXmlUtil.*;

import java.io.InputStream;
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

  public static ProjectModel parseSong(java.io.File xmlFile) throws Exception {
    return parseSong(new java.io.FileInputStream(xmlFile), xmlFile.getName().replace(".XML", ""));
  }

  public static ProjectModel parseSong(InputStream is, String name) throws Exception {
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
    if (songNode.hasAttribute("inputTickMagnitude")) {
      project.setInputTickMagnitude(Integer.parseInt(songNode.getAttribute("inputTickMagnitude")));
    }
    if (songNode.hasAttribute("rootNote")) {
      project.setKey(songNode.getAttribute("rootNote"));
    }
    if (songNode.hasAttribute("swingAmount")) {
      project.setSwingAmount(Integer.parseInt(songNode.getAttribute("swingAmount")));
    }
    if (songNode.hasAttribute("swingInterval")) {
      int fileValue = Integer.parseInt(songNode.getAttribute("swingInterval"));
      project.setSwingInterval(
          DelugeXmlUtil.convertSyncLevelFromFileValueToInternalValue(
              fileValue, project.getInputTickMagnitude()));
    }
    if (songNode.hasAttribute("timePerTimerTick")) {
      project.setTimePerTimerTick(Integer.parseInt(songNode.getAttribute("timePerTimerTick")));
    }
    if (songNode.hasAttribute("timerTickFraction")) {
      project.setTimerTickFraction(Integer.parseInt(songNode.getAttribute("timerTickFraction")));
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
    if (instNodes.getLength() > 0) {
      Element instruments = (Element) instNodes.item(0);
      NodeList children = instruments.getChildNodes();
      for (int i = 0; i < children.getLength(); i++) {
        if (children.item(i) instanceof Element childNode) {
          String tagName = childNode.getTagName();
          if ("kit".equals(tagName)) {
            KitTrackModel kit = KitXmlParser.parseKitElement(childNode);
            project.addTrack(kit);
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
            } else {
              SynthTrackModel synth = parseSynthElement(childNode);
              project.addTrack(synth);
            }
          } else if ("audioTrack".equals(tagName)) {
            AudioTrackModel audioTrack = parseAudioTrackElement(childNode);
            project.addTrack(audioTrack);
          }
          // Carry the instrument's stored colour (0-191, or absent) onto the track it just created,
          // so the session/song view colours it via the Deluge palette. Without this the track kept
          // its default cyan hex and every clip rendered the same green (colour="0" was ignored).
          if (childNode.hasAttribute("colour") && !project.getTracks().isEmpty()) {
            project
                .getTracks()
                .get(project.getTracks().size() - 1)
                .setColourHex(childNode.getAttribute("colour"));
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
          int rowCount = 0;
          NodeList noteRowList = null;
          if (noteRowsList.getLength() > 0) {
            Element noteRowsElem = (Element) noteRowsList.item(0);
            noteRowList = noteRowsElem.getElementsByTagName("noteRow");
            rowCount = noteRowList.getLength();
          }
          if (rowCount == 0) {
            if (targetTrack instanceof KitTrackModel kit) {
              rowCount = kit.getDrums().size();
            } else {
              rowCount = 8;
            }
          }

          boolean tripletMode =
              "1".equals(trackElem.getAttribute("triplet"))
                  || "true".equalsIgnoreCase(trackElem.getAttribute("triplet"));
          int stepTicks = tripletMode ? 32 : 24;
          int stepCount = 16;
          if (trackElem.hasAttribute("length")) {
            try {
              int lengthTicks = Integer.parseInt(trackElem.getAttribute("length"));
              stepCount = lengthTicks / stepTicks;
              if (stepCount < 1) stepCount = 16;
            } catch (Exception ignored) {
            }
          }

          ClipModel clip = new ClipModel("CLIP " + i, rowCount, stepCount);
          clip.setTripletMode(tripletMode);
          clip.setPlayDirection(readPlayDirectionAttr(trackElem));

          if (noteRowList != null) {
            for (int r = 0; r < rowCount; r++) {
              Element noteRowElem = (Element) noteRowList.item(r);
              String hexData = null;

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

              // Check for noteDataWithLift attribute (c1.2.0+ firmware format)
              String liftAttr = noteRowElem.getAttribute("noteDataWithLift");
              String splitAttr = noteRowElem.getAttribute("noteDataWithSplitProb");
              if (liftAttr != null && !liftAttr.isEmpty()) {
                hexData = liftAttr;
              } else if (splitAttr != null && !splitAttr.isEmpty()) {
                hexData = splitAttr;
              }

              // Check for noteData attribute directly on noteRow (kit rows)
              if (hexData == null) {
                String dataAttr = noteRowElem.getAttribute("noteData");
                if (dataAttr != null && !dataAttr.isEmpty()) {
                  hexData = dataAttr;
                }
              }

              // Fall back to <noteData> child element (older format)
              if (hexData == null) {
                NodeList noteDataList = noteRowElem.getElementsByTagName("noteData");
                if (noteDataList.getLength() > 0) {
                  hexData = noteDataList.item(0).getTextContent();
                }
              }

              if (hexData != null && !hexData.isEmpty()) {
                // Detect format: noteDataWithLift = 22 chars/note, noteDataWithSplitProb = 28
                int hcpn = DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_OLD;
                if (liftAttr != null && !liftAttr.isEmpty()) {
                  hcpn = DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_LIFT;
                } else if (splitAttr != null && !splitAttr.isEmpty()) {
                  hcpn = DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_SPLIT;
                } else if (hexData.startsWith("0x")) {
                  int dataLen = hexData.length() - 2;
                  if (dataLen > 0 && dataLen % DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_SPLIT == 0) {
                    hcpn = DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_SPLIT;
                  }
                }
                // Firmware XML uses dynamic ticks per grid step (triplet alignment check!)
                java.util.List<StepData> row =
                    DelugeNoteDataMapper.decodeRow(hexData, stepCount, stepTicks, hcpn);

                for (int s = 0; s < stepCount; s++) {
                  clip.setStep(r, s, row.get(s));
                }

                java.util.List<org.deluge.model.NoteModel> rawNotes =
                    DelugeNoteDataMapper.decodeRawNotes(hexData, hcpn);
                clip.setRawNoteEvents(r, rawNotes);
              }

              // Apply per-row pitch from y attribute to all steps in this row
              // (the hex note data encodes on/off/gate but not pitch)
              if (notePitch >= 0) {
                clip.setRowYNote(r, notePitch);
                for (int s = 0; s < stepCount; s++) {
                  StepData existing = clip.getStep(r, s);
                  clip.setStep(
                      r,
                      s,
                      StepData.of(
                          existing.active(),
                          existing.velocity(),
                          existing.gate(),
                          existing.probability(),
                          notePitch));
                }
              }

              // Parse per-noteRow <soundParams> (35+ hex param overrides)
              NodeList rowSoundParamsList = noteRowElem.getElementsByTagName("soundParams");
              if (rowSoundParamsList.getLength() > 0) {
                Element sp = (Element) rowSoundParamsList.item(0);
                parseNoteRowSoundParams(sp, clip, r);
              }
            }
          }
          parseColumnControls(trackElem, clip);
          targetTrack.addClip(clip);

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
    NodeList sessionClipsNodes = songNode.getElementsByTagName("sessionClips");
    if (sessionClipsNodes.getLength() > 0) {
      Element sessionClips = (Element) sessionClipsNodes.item(0);
      NodeList clipNodeList = sessionClips.getElementsByTagName("instrumentClip");

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

      for (int i = 0; i < clipNodeList.getLength(); i++) {
        Element clipElem = (Element) clipNodeList.item(i);

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

        // Match to the next available track of the right type (FIFO)
        TrackModel targetTrack = null;
        if (isKitClip) {
          targetTrack = kitTrackQueue.poll();
        } else {
          targetTrack = instrumentTrackQueue.poll();
        }

        if (targetTrack == null) {
          continue;
        }

        // DIAGNOSTIC: instrumentClip matching info
        String slot =
            clipElem.hasAttribute("instrumentPresetSlot")
                ? clipElem.getAttribute("instrumentPresetSlot")
                : "?";
        String name_ =
            clipElem.hasAttribute("instrumentPresetName")
                ? clipElem.getAttribute("instrumentPresetName")
                : "?";
        String subSlot =
            clipElem.hasAttribute("instrumentPresetSubSlot")
                ? clipElem.getAttribute("instrumentPresetSubSlot")
                : "?";

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

        int rowCount = 0;
        NodeList noteRowList = null;
        if (noteRowsList.getLength() > 0) {
          Element noteRowsElem = (Element) noteRowsList.item(0);
          noteRowList = noteRowsElem.getElementsByTagName("noteRow");
          rowCount = noteRowList.getLength();
        }
        if (rowCount == 0) {
          if (targetTrack instanceof KitTrackModel kit) {
            rowCount = kit.getDrums().size();
          } else {
            rowCount = 8;
          }
        }

        // Determine stepCount: use length attribute if present (1 step = 24/32 ticks of clip
        // time)
        // but also expand to cover the actual note positions in the hex data
        boolean tripletMode =
            "1".equals(clipElem.getAttribute("triplet"))
                || "true".equalsIgnoreCase(clipElem.getAttribute("triplet"));
        int stepTicks = tripletMode ? 32 : 24;
        int stepCount = 16;
        if (clipElem.hasAttribute("length")) {
          int lengthTicks = Integer.parseInt(clipElem.getAttribute("length"));
          stepCount = lengthTicks / stepTicks;
          if (stepCount < 1) stepCount = 16;
        }
        // Expand stepCount to cover max position found in noteData (pos in 12-tick units)
        int maxPos = 0;
        if (noteRowList != null) {
          for (int r = 0; r < rowCount; r++) {
            Element nr = (Element) noteRowList.item(r);
            String hd = null;
            String la = nr.getAttribute("noteDataWithLift");
            String sa = nr.getAttribute("noteDataWithSplitProb");
            if (la != null && !la.isEmpty()) hd = la;
            else if (sa != null && !sa.isEmpty()) hd = sa;
            if (hd == null) {
              String da = nr.getAttribute("noteData");
              if (da != null && !da.isEmpty()) hd = da;
            }
            if (hd == null) {
              NodeList ndl = nr.getElementsByTagName("noteData");
              if (ndl.getLength() > 0) hd = ndl.item(0).getTextContent();
            }
            if (hd != null && hd.startsWith("0x")) {
              String data = hd.substring(2);
              // Detect hex chars per note based on which attribute was used
              int hcpn = DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_OLD;
              if (la != null && !la.isEmpty()) hcpn = DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_LIFT;
              else if (sa != null && !sa.isEmpty())
                hcpn = DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_SPLIT;
              else if (data.length() > 0
                  && data.length() % DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_SPLIT == 0)
                hcpn = DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_SPLIT;
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
        if (clipElem.hasAttribute("clipName")) {
          clip.setName(clipElem.getAttribute("clipName"));
        }
        clip.setTripletMode(tripletMode);
        clip.setPlayDirection(readPlayDirectionAttr(clipElem));
        // C clip.cpp:713-715 — read + clamp the session section (255 stays unassigned).
        if (clipElem.hasAttribute("section")) {
          clip.setSection(Integer.parseInt(clipElem.getAttribute("section").trim()));
        }

        if (noteRowList != null) {
          for (int r = 0; r < rowCount; r++) {
            Element noteRowElem = (Element) noteRowList.item(r);
            String hexData = null;

            // Read drumIndex (kit) or y (synth)
            int drumIdx = -1;
            if (noteRowElem.hasAttribute("drumIndex")) {
              drumIdx = Integer.parseInt(noteRowElem.getAttribute("drumIndex"));
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

            String liftAttr = noteRowElem.getAttribute("noteDataWithLift");
            String splitAttr = noteRowElem.getAttribute("noteDataWithSplitProb");
            if (liftAttr != null && !liftAttr.isEmpty()) {
              hexData = liftAttr;
            } else if (splitAttr != null && !splitAttr.isEmpty()) {
              hexData = splitAttr;
            }

            // Check for noteData attribute directly on noteRow (kit rows)
            if (hexData == null) {
              String dataAttr = noteRowElem.getAttribute("noteData");
              if (dataAttr != null && !dataAttr.isEmpty()) {
                hexData = dataAttr;
              }
            }

            if (hexData == null) {
              NodeList noteDataList = noteRowElem.getElementsByTagName("noteData");
              if (noteDataList.getLength() > 0) {
                hexData = noteDataList.item(0).getTextContent();
              }
            }

            if (hexData != null && !hexData.isEmpty()) {
              // Detect format: noteDataWithLift = 22 chars/note, noteDataWithSplitProb = 28,
              // default = 20
              int hcpn = DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_OLD;
              if (liftAttr != null && !liftAttr.isEmpty()) {
                hcpn = DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_LIFT;
              } else if (splitAttr != null && !splitAttr.isEmpty()) {
                hcpn = DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_SPLIT;
              } else {
                String da = noteRowElem.getAttribute("noteData");
                if (da != null && !da.isEmpty() && da.startsWith("0x")) {
                  // Check length heuristic: if hex data has split/prob fields
                  int dataLen = da.length() - 2; // strip "0x"
                  if (dataLen > 0 && dataLen % DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_SPLIT == 0) {
                    hcpn = DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_SPLIT;
                  }
                }
              }
              // Firmware XML uses dynamic ticks per grid step (triplet alignment check!)
              java.util.List<StepData> row =
                  DelugeNoteDataMapper.decodeRow(hexData, stepCount, stepTicks, hcpn);
              for (int s = 0; s < stepCount; s++) {
                clip.setStep(r, s, row.get(s));
              }

              java.util.List<org.deluge.model.NoteModel> rawNotes =
                  DelugeNoteDataMapper.decodeRawNotes(hexData, hcpn);
              clip.setRawNoteEvents(r, rawNotes);

              // DIAGNOSTIC: active steps per row
              StringBuilder sb = new StringBuilder();
              for (int s = 0; s < stepCount; s++) {
                if (row.get(s).active()) sb.append(s).append(",");
              }
              String rowLabel =
                  drumIdx >= 0 ? "drumIndex=" + drumIdx : "noteRow[" + r + "] y=" + notePitch;
              String hexPrefix = hexData.length() > 30 ? hexData.substring(0, 30) + "..." : hexData;
            } else if (drumIdx >= 0) {
            }

            // Apply per-row pitch from y attribute to all steps in this row
            if (notePitch >= 0) {
              clip.setRowYNote(r, notePitch);
              for (int s = 0; s < stepCount; s++) {
                StepData existing = clip.getStep(r, s);
                clip.setStep(
                    r,
                    s,
                    StepData.of(
                        existing.active(),
                        existing.velocity(),
                        existing.gate(),
                        existing.probability(),
                        notePitch));
              }
            }

            // Parse per-noteRow <soundParams> (35+ hex param overrides)
            NodeList rowSoundParamsList = noteRowElem.getElementsByTagName("soundParams");
            if (rowSoundParamsList.getLength() > 0) {
              Element sp = (Element) rowSoundParamsList.item(0);
              parseNoteRowSoundParams(sp, clip, r);
            }
          }
        }
        parseColumnControls(clipElem, clip);
        targetTrack.addClip(clip);

        // Parse the clip's <soundParams>: STATIC values into the track model (the song format
        // keeps all sound params here), then automation.
        switch (targetTrack) {
          case SynthTrackModel stm -> {
            NodeList soundParamsList = clipElem.getElementsByTagName("soundParams");
            if (soundParamsList.getLength() > 0) {
              Element spEl = (Element) soundParamsList.item(0);
              InstrumentXmlParser.parseClipSoundParamsStatics(spEl, stm);
              parseAutomation(spEl, clip);
            }
            NodeList paramsList2 = clipElem.getElementsByTagName("params");
            if (paramsList2.getLength() > 0) {
              parseParamsAsKitParams((Element) paramsList2.item(0), clip);
            }
          }
          case MidiTrackModel mtm -> {
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
            Element kitParamsEl = getFirstChild(clipElem, "kitParams");
            if (kitParamsEl != null) {
              parseKitParamsElement(kitParamsEl, clip);
            }
          }
          default -> {}
        }
      }
      // ── Parse audioClips in sessionClips ──
      NodeList audioClipNodeList = sessionClips.getElementsByTagName("audioClip");
      for (int i = 0; i < audioClipNodeList.getLength(); i++) {
        Element clipElem = (Element) audioClipNodeList.item(i);

        // Match by trackName attribute
        String trackName =
            clipElem.hasAttribute("trackName") ? clipElem.getAttribute("trackName") : null;
        AudioTrackModel targetTrack = null;
        if (trackName != null) {
          // Scan all audio tracks for a name match
          for (AudioTrackModel at : audioTrackQueue) {
            if (trackName.equals(at.getName())) {
              targetTrack = at;
              break;
            }
          }
        }
        if (targetTrack == null) {
          // Fallback: FIFO poll if no trackName match
          targetTrack = audioTrackQueue.poll();
        }
        if (targetTrack == null) {
          continue;
        }

        AudioTrackModel.AudioClip clip = new AudioTrackModel.AudioClip();
        clip.setTrackName(trackName);
        if (clipElem.hasAttribute("filePath")) clip.setFilePath(clipElem.getAttribute("filePath"));
        if (clipElem.hasAttribute("reversed"))
          clip.setReversed(
              "1".equals(clipElem.getAttribute("reversed"))
                  || "true".equalsIgnoreCase(clipElem.getAttribute("reversed")));
        if (clipElem.hasAttribute("startSamplePos"))
          clip.setStartSamplePos(Integer.parseInt(clipElem.getAttribute("startSamplePos")));
        if (clipElem.hasAttribute("endSamplePos"))
          clip.setEndSamplePos(Integer.parseInt(clipElem.getAttribute("endSamplePos")));
        // attack is stored as a raw signed int32 in XML (not a 0x hex float), normalize via
        // hexToFloat
        if (clipElem.hasAttribute("attack"))
          clip.setAttack(toUnipolar(DelugeHexMapper.hexToFloat(clipElem.getAttribute("attack"))));
        if (clipElem.hasAttribute("priority"))
          clip.setPriority(Integer.parseInt(clipElem.getAttribute("priority")));
        if (clipElem.hasAttribute("pitchSpeedIndependent"))
          clip.setPitchSpeedIndependent(
              "true".equalsIgnoreCase(clipElem.getAttribute("pitchSpeedIndependent")));
        if (clipElem.hasAttribute("overdubsShouldCloneAudioTrack"))
          clip.setOverdubsShouldCloneAudioTrack(
              "true".equalsIgnoreCase(clipElem.getAttribute("overdubsShouldCloneAudioTrack")));
        if (clipElem.hasAttribute("isPlaying"))
          clip.setPlaying(
              "true".equalsIgnoreCase(clipElem.getAttribute("isPlaying"))
                  || "1".equals(clipElem.getAttribute("isPlaying")));
        if (clipElem.hasAttribute("isSoloing"))
          clip.setSoloing(
              "true".equalsIgnoreCase(clipElem.getAttribute("isSoloing"))
                  || "1".equals(clipElem.getAttribute("isSoloing")));
        if (clipElem.hasAttribute("isArmedForRecording"))
          clip.setArmedForRecording(
              "true".equalsIgnoreCase(clipElem.getAttribute("isArmedForRecording"))
                  || "1".equals(clipElem.getAttribute("isArmedForRecording")));
        if (clipElem.hasAttribute("length"))
          clip.setLength(Integer.parseInt(clipElem.getAttribute("length")));
        if (clipElem.hasAttribute("colourOffset"))
          clip.setColourOffset(Integer.parseInt(clipElem.getAttribute("colourOffset")));
        if (clipElem.hasAttribute("section"))
          clip.setSection(Integer.parseInt(clipElem.getAttribute("section")));
        // beingEdited uses "1"/"0" integer style
        if (clipElem.hasAttribute("beingEdited"))
          clip.setBeingEdited(
              "1".equals(clipElem.getAttribute("beingEdited"))
                  || "true".equalsIgnoreCase(clipElem.getAttribute("beingEdited")));

        // Parse <params> child element (hex attrs — same structure as songParams)
        NodeList paramsList = clipElem.getElementsByTagName("params");
        if (paramsList.getLength() > 0) {
          Element params = (Element) paramsList.item(0);
          parseAudioClipParams(params, clip);
        }

        targetTrack.addAudioClip(clip);
      }
    }

    // ── Parse sections ──
    parseSongSections(songNode, project);
    // ── Parse scales ──
    parseSongScales(songNode, project);

    // ── Parse Arrangement-Only Clips ──
    java.util.List<ClipModel> allArrangementClips = new java.util.ArrayList<>();
    NodeList arrangementOnlyClipsNodes = songNode.getElementsByTagName("arrangementOnlyClips");
    if (arrangementOnlyClipsNodes.getLength() == 0) {
      arrangementOnlyClipsNodes = songNode.getElementsByTagName("arrangementOnlyTracks");
    }
    if (arrangementOnlyClipsNodes.getLength() > 0) {
      Element arrClipsElem = (Element) arrangementOnlyClipsNodes.item(0);
      NodeList arrClipNodeList = arrClipsElem.getElementsByTagName("instrumentClip");
      for (int i = 0; i < arrClipNodeList.getLength(); i++) {
        Element clipElem = (Element) arrClipNodeList.item(i);

        // Match target track by trackName
        String trackName =
            clipElem.hasAttribute("trackName") ? clipElem.getAttribute("trackName") : null;
        TrackModel targetTrack = null;
        if (trackName != null) {
          for (TrackModel t : project.getTracks()) {
            if (trackName.equals(t.getName())) {
              targetTrack = t;
              break;
            }
          }
        }

        // Fallback: FIFO queue match
        if (targetTrack == null && i < project.getTracks().size()) {
          targetTrack = project.getTracks().get(i);
        }

        if (targetTrack != null) {
          // Parse basic details: rowCount, stepCount
          NodeList noteRowList = clipElem.getElementsByTagName("noteRow");
          int rowCount = noteRowList.getLength();
          boolean tripletMode =
              "1".equals(clipElem.getAttribute("triplet"))
                  || "true".equalsIgnoreCase(clipElem.getAttribute("triplet"));
          int stepTicks = tripletMode ? 32 : 24;
          int stepCount = 16;

          if (clipElem.hasAttribute("length")) {
            try {
              int lengthTicks = Integer.parseInt(clipElem.getAttribute("length"));
              stepCount = lengthTicks / stepTicks;
            } catch (NumberFormatException ignored) {
            }
          }

          // Pre-calculate step count limit based on note events bounds
          int maxPos = 0;
          for (int r = 0; r < rowCount; r++) {
            Element nr = (Element) noteRowList.item(r);
            String hd = null;
            String la = nr.getAttribute("liftActions");
            if (la == null || la.isEmpty()) la = nr.getAttribute("noteDataWithLift");
            String sa = nr.getAttribute("noteDataWithSplitProb");
            if (la != null && !la.isEmpty()) hd = la;
            else if (sa != null && !sa.isEmpty()) hd = sa;
            if (hd == null) {
              String da = nr.getAttribute("noteData");
              if (da != null && !da.isEmpty()) hd = da;
            }
            if (hd == null) {
              NodeList ndl = nr.getElementsByTagName("noteData");
              if (ndl.getLength() > 0) hd = ndl.item(0).getTextContent();
            }
            if (hd != null && hd.startsWith("0x")) {
              String data = hd.substring(2);
              int hcpn = DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_OLD;
              if (la != null && !la.isEmpty()) hcpn = DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_LIFT;
              else if (sa != null && !sa.isEmpty())
                hcpn = DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_SPLIT;
              else if (data.length() > 0
                  && data.length() % DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_SPLIT == 0) {
                hcpn = DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_SPLIT;
              }
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
          if (clipElem.hasAttribute("clipName")) {
            clip.setName(clipElem.getAttribute("clipName"));
          }
          clip.setTripletMode(tripletMode);
          clip.setArrangementOnly(true);

          // Parse note row cells
          for (int r = 0; r < rowCount; r++) {
            Element nr = (Element) noteRowList.item(r);
            String noteStr = nr.getAttribute("note");
            int noteVal = noteStr != null && !noteStr.isEmpty() ? Integer.parseInt(noteStr) : 60;

            String hd = null;
            String la = nr.getAttribute("liftActions");
            if (la == null || la.isEmpty()) la = nr.getAttribute("noteDataWithLift");
            String sa = nr.getAttribute("noteDataWithSplitProb");
            if (la != null && !la.isEmpty()) hd = la;
            else if (sa != null && !sa.isEmpty()) hd = sa;
            if (hd == null) {
              String da = nr.getAttribute("noteData");
              if (da != null && !da.isEmpty()) hd = da;
            }
            if (hd == null) {
              NodeList ndl = nr.getElementsByTagName("noteData");
              if (ndl.getLength() > 0) hd = ndl.item(0).getTextContent();
            }
            if (hd != null && hd.startsWith("0x") && hd.length() > 2) {
              // Use the same robust decoder as session clips (was a buggy hand-rolled loop that
              // read
              // the split-velocity offset even on lift-format data → StringIndexOutOfBounds).
              int hcpn = DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_OLD;
              if (la != null && !la.isEmpty()) hcpn = DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_LIFT;
              else if (sa != null && !sa.isEmpty())
                hcpn = DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_SPLIT;
              else {
                int dataLen = hd.length() - 2;
                if (dataLen > 0 && dataLen % DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_SPLIT == 0) {
                  hcpn = DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_SPLIT;
                }
              }
              java.util.List<StepData> row =
                  DelugeNoteDataMapper.decodeRow(hd, stepCount, stepTicks, hcpn);
              clip.setRowYNote(r, noteVal);
              for (int s = 0; s < stepCount && s < row.size(); s++) {
                StepData base = row.get(s);
                clip.setStep(
                    r,
                    s,
                    StepData.of(
                        base.active(), base.velocity(), base.gate(), base.probability(), noteVal));
              }
            }
          }

          parseColumnControls(clipElem, clip);
          targetTrack.addClip(clip);
          allArrangementClips.add(clip);
        }
      }
    }

    // ── Parse clipInstances and build ArrangerTimeline ──
    java.util.List<ClipModel> allSessionClips = new java.util.ArrayList<>();
    for (TrackModel track : project.getTracks()) {
      for (ClipModel clip : track.getClips()) {
        if (!clip.isArrangementOnly()) {
          allSessionClips.add(clip);
        }
      }
    }

    // clipInstances live on the instrument elements (<sound>/<kit>/<audioTrack>) under
    // <instruments>
    // in the current format; legacy songs put them on <track>/<instrumentClip>. Gather the elements
    // that actually carry the attribute, in document order, so track index t maps to project track
    // t.
    java.util.List<Element> clipInstanceElems = new java.util.ArrayList<>();
    NodeList instrumentsNodes = songNode.getElementsByTagName("instruments");
    if (instrumentsNodes.getLength() > 0) {
      NodeList kids = instrumentsNodes.item(0).getChildNodes();
      for (int k = 0; k < kids.getLength(); k++) {
        if (kids.item(k) instanceof Element e) clipInstanceElems.add(e);
      }
    }
    // Fall back to legacy <track>/<instrumentClip> when the instrument children don't carry the
    // clipInstances attribute (older format, or the mock fidelity fixtures).
    boolean anyHasClipInstances =
        clipInstanceElems.stream().anyMatch(e -> e.hasAttribute("clipInstances"));
    if (!anyHasClipInstances) {
      clipInstanceElems.clear();
      NodeList legacy = songNode.getElementsByTagName("track");
      if (legacy.getLength() == 0) legacy = songNode.getElementsByTagName("instrumentClip");
      for (int k = 0; k < legacy.getLength(); k++) {
        clipInstanceElems.add((Element) legacy.item(k));
      }
    }
    for (int t = 0; t < clipInstanceElems.size() && t < project.getTracks().size(); t++) {
      Element trackElem = clipInstanceElems.get(t);

      String hexStr = "";
      if (trackElem.hasAttribute("clipInstances")) {
        hexStr = trackElem.getAttribute("clipInstances");
      } else {
        NodeList ciList = trackElem.getElementsByTagName("clipInstances");
        if (ciList.getLength() > 0) {
          hexStr = ciList.item(0).getTextContent().trim();
        }
      }

      if (hexStr != null && hexStr.startsWith("0x")) {
        String data = hexStr.substring(2);
        for (int p = 0; p + 24 <= data.length(); p += 24) {
          String posHex = data.substring(p, p + 8);
          String lenHex = data.substring(p + 8, p + 16);
          String codeHex = data.substring(p + 16, p + 24);

          try {
            int pos = (int) Long.parseLong(posHex, 16);
            int length = (int) Long.parseLong(lenHex, 16);
            long code = Long.parseLong(codeHex, 16);

            boolean isArrangementClip = (code & 0x80000000L) != 0;
            int clipIndex = (int) (code & 0x7FFFFFFFL);

            ClipModel targetClip = null;
            if (isArrangementClip) {
              if (clipIndex >= 0 && clipIndex < allArrangementClips.size()) {
                targetClip = allArrangementClips.get(clipIndex);
              }
            } else {
              if (clipIndex >= 0 && clipIndex < allSessionClips.size()) {
                targetClip = allSessionClips.get(clipIndex);
              }
            }

            if (targetClip != null) {
              ArrangerClip arrangerClip = new ArrangerClip(t, targetClip, pos, length);
              project.addArrangerClip(arrangerClip);
            }
          } catch (NumberFormatException ignored) {
          }
        }
      }
    }

    // DIAGNOSTIC: final ProjectModel summary
    System.out.println(
        "  tracks="
            + project.getTracks().size()
            + " bpm="
            + project.getBpm()
            + " swing="
            + project.getSwing());
    for (int t = 0; t < project.getTracks().size(); t++) {
      TrackModel tr = project.getTracks().get(t);
      System.out.println(
          "  track["
              + t
              + "]="
              + tr.getName()
              + " type="
              + tr.getClass().getSimpleName()
              + " clips="
              + tr.getClips().size());
      for (int c = 0; c < tr.getClips().size(); c++) {
        ClipModel cl = tr.getClips().get(c);
        System.out.println(
            "    clip["
                + c
                + "]="
                + cl.getName()
                + " rows="
                + cl.getRowCount()
                + " steps="
                + cl.getStepCount());
        for (int r = 0; r < cl.getRowCount() && r < 3; r++) {
          StringBuilder rowSb = new StringBuilder("      row[" + r + "]: ");
          for (int s = 0; s < cl.getStepCount() && s < 16; s++) {
            StepData sd = cl.getStep(r, s);
            rowSb.append(sd != null && sd.active() ? "X" : ".");
          }
          System.out.println(rowSb);
        }
        if (cl.getRowCount() > 3) {
          System.out.println("      ... +" + (cl.getRowCount() - 3) + " more rows");
        }
      }
    }
    System.out.println("=== PARSER END ===");

    return project;
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

    // Parse MIDI Channel or MPE Zone
    NodeList channelNodes = soundNode.getElementsByTagName("midiChannel");
    if (channelNodes.getLength() > 0) {
      try {
        int chan = Integer.parseInt(channelNodes.item(0).getTextContent().trim());
        midiTrack.setMidiChannel(chan);
      } catch (Exception ignored) {
      }
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

  private static void parseAudioClipParams(Element params, AudioTrackModel.AudioClip clip) {
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
    readAttrFloatHex(params, "eqBass", clip::setEqBass, true);
    readAttrFloatHex(params, "eqTreble", clip::setEqTreble, false);
    readAttrFloatHex(params, "eqBassFrequency", clip::setEqBassFrequency, true);
    readAttrFloatHex(params, "eqTrebleFrequency", clip::setEqTrebleFrequency, true);

    // Child elements for delay, lpf, hpf, equalizer (mirror songParams structure)
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
      readAttrFloatHex(eqChild, "bass", clip::setEqBass, true);
      readAttrFloatHex(eqChild, "treble", clip::setEqTreble, false);
      readAttrFloatHex(eqChild, "bassFrequency", clip::setEqBassFrequency, true);
      readAttrFloatHex(eqChild, "trebleFrequency", clip::setEqTrebleFrequency, true);
    }
  }

  private static void parseNoteRowSoundParams(Element sp, ClipModel clip, int row) {
    // List of known hex float parameter attributes in <soundParams>
    String[] hexParams = {
      "oscAVolume",
      "oscBVolume",
      "noiseVolume",
      "volume",
      "pan",
      "lpfFrequency",
      "lpfResonance",
      "hpfFrequency",
      "hpfResonance",
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
      String val = sp.getAttribute(param);
      if (val != null && val.startsWith("0x") && !val.isBlank()) {
        clip.setRowSoundParam(row, param, toUnipolar(DelugeHexMapper.hexToFloat(val)));
      }
    }
    // Hz parameters (lpfFrequency/hpfFrequency already handled as hex floats above,
    // but some XML may have them as hex Hz values)
    String lpfHz = sp.getAttribute("lpfFrequency");
    if (lpfHz != null && !lpfHz.isBlank() && !lpfHz.startsWith("0x")) {
      try {
        clip.setRowSoundParam(row, "lpfFrequency", Float.parseFloat(lpfHz) / 20000.0f);
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
    // equalizer child element
    NodeList eqNodes = sp.getElementsByTagName("equalizer");
    if (eqNodes.getLength() > 0) {
      Element eq = (Element) eqNodes.item(0);
      String bassVal = eq.getAttribute("bass");
      if (bassVal != null && !bassVal.isBlank())
        clip.setRowSoundParam(row, "eqBass", Math.abs(DelugeHexMapper.hexToFloat(bassVal)));
      String trebleVal = eq.getAttribute("treble");
      if (trebleVal != null && !trebleVal.isBlank())
        clip.setRowSoundParam(row, "eqTreble", Math.abs(DelugeHexMapper.hexToFloat(trebleVal)));
    }
    parseNoteRowAutomation(sp, clip, row);
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

  private static void parseSongFx(Element songNode, ProjectModel project) {
    // ── Reverb ──
    NodeList reverbNodes = songNode.getElementsByTagName("reverb");
    if (reverbNodes.getLength() > 0) {
      Element rev = (Element) reverbNodes.item(0);
      readSongIntAttr(rev, "roomSize", project::setReverbRoomSize, 0.6f);
      readSongIntAttr(rev, "dampening", project::setReverbDampening, 0.5f);
      readSongRawAttr(rev, "width", project::setReverbWidth, 0.5f);
      readSongRawAttr(rev, "hpf", project::setReverbHpf, 0.0f);
      readSongRawAttr(rev, "pan", project::setReverbPan, 0.0f);
      if (rev.hasAttribute("model")) {
        project.setReverbModel(Integer.parseInt(rev.getAttribute("model")));
      }
      // Nested compressor inside reverb
      NodeList revCompNodes = rev.getElementsByTagName("compressor");
      if (revCompNodes.getLength() > 0) {
        Element rc = (Element) revCompNodes.item(0);
        readSongRawAttr(rc, "attack", project::setReverbCompressorAttack, 0.0f);
        readSongRawAttr(rc, "release", project::setReverbCompressorRelease, 0.0f);
        if (rc.hasAttribute("syncLevel")) {
          project.setReverbCompressorSyncLevel(Integer.parseInt(rc.getAttribute("syncLevel")));
        }
        readSongRawAttr(rc, "compHPF", project::setReverbCompHpf, 0.0f);
        readSongRawAttr(rc, "compBlend", project::setReverbCompBlend, 0.5f);
        readSongRawAttr(rc, "shape", project::setReverbCompressorShape, 0.0f);
        readSongRawAttr(rc, "volume", project::setReverbCompressorVolume, 0.0f);
      }
    }

    // ── Delay ──
    NodeList delayNodes = songNode.getElementsByTagName("delay");
    if (delayNodes.getLength() > 0) {
      Element del = (Element) delayNodes.item(0);
      if (del.hasAttribute("pingPong"))
        project.setDelayPingPong(Integer.parseInt(del.getAttribute("pingPong")));
      if (del.hasAttribute("analog"))
        project.setDelayAnalog(Integer.parseInt(del.getAttribute("analog")));
      if (del.hasAttribute("syncLevel"))
        project.setDelaySyncLevel(Integer.parseInt(del.getAttribute("syncLevel")));
      if (del.hasAttribute("syncType"))
        project.setDelaySyncType(Integer.parseInt(del.getAttribute("syncType")));
    }

    // ── Sidechain ──
    NodeList sidechainNodes = songNode.getElementsByTagName("sidechain");
    if (sidechainNodes.getLength() > 0) {
      Element sc = (Element) sidechainNodes.item(0);
      readSongRawAttr(sc, "attack", project::setSidechainAttack, 0.0f);
      readSongRawAttr(sc, "release", project::setSidechainRelease, 0.0f);
      if (sc.hasAttribute("syncLevel"))
        project.setSidechainSyncLevel(Integer.parseInt(sc.getAttribute("syncLevel")));
      if (sc.hasAttribute("syncType"))
        project.setSidechainSyncType(Integer.parseInt(sc.getAttribute("syncType")));
    }

    // ── Audio Compressor ──
    NodeList compNodes = songNode.getElementsByTagName("audioCompressor");
    if (compNodes.getLength() > 0) {
      Element ac = (Element) compNodes.item(0);
      readSongRawAttr(ac, "attack", project::setCompressorAttack, 0.0f);
      readSongRawAttr(ac, "release", project::setCompressorRelease, 0.0f);
      readSongRawAttr(ac, "thresh", project::setCompressorThreshold, 0.0f);
      readSongRawAttr(ac, "ratio", project::setCompressorRatio, 0.0f);
    }

    // ── Song Compressor fallback (SONG006667 variant) ──
    if (compNodes.getLength() == 0) {
      NodeList scNodes = songNode.getElementsByTagName("songCompressor");
      if (scNodes.getLength() > 0) {
        Element sc = (Element) scNodes.item(0);
        readSongRawAttr(sc, "attack", project::setCompressorAttack, 0.0f);
        readSongRawAttr(sc, "release", project::setCompressorRelease, 0.0f);
        readSongRawAttr(sc, "thresh", project::setCompressorThreshold, 0.0f);
        readSongRawAttr(sc, "ratio", project::setCompressorRatio, 0.0f);
      }
    }

    // ── SongParams ──
    NodeList spNodes = songNode.getElementsByTagName("songParams");
    if (spNodes.getLength() > 0) {
      Element sp = (Element) spNodes.item(0);
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
      NodeList spDelay = sp.getElementsByTagName("delay");
      if (spDelay.getLength() > 0) {
        Element d = (Element) spDelay.item(0);
        readSongHexAttr(d, "rate", project::setSongParamDelayRate, true);
        readSongHexAttr(d, "feedback", project::setSongParamDelayFeedback, true);
      }
      NodeList spLpf = sp.getElementsByTagName("lpf");
      if (spLpf.getLength() > 0) {
        Element l = (Element) spLpf.item(0);
        readSongHzHexAttr(l, "frequency", project::setSongParamLpfFrequency);
        readSongHexAttr(l, "resonance", project::setSongParamLpfResonance, true);
      }
      NodeList spHpf = sp.getElementsByTagName("hpf");
      if (spHpf.getLength() > 0) {
        Element h = (Element) spHpf.item(0);
        readSongHzHexAttr(h, "frequency", project::setSongParamHpfFrequency);
        readSongHexAttr(h, "resonance", project::setSongParamHpfResonance, true);
      }
      NodeList spEq = sp.getElementsByTagName("equalizer");
      if (spEq.getLength() == 0) {
        spEq = sp.getElementsByTagName("eq");
      }
      if (spEq.getLength() > 0) {
        Element eq = (Element) spEq.item(0);
        readSongHexAttr(eq, "bass", project::setSongParamEqBass, false);
        readSongHexAttr(eq, "treble", project::setSongParamEqTreble, false);
        readSongHexAttr(eq, "bassFrequency", project::setSongParamEqBassFrequency, true);
        readSongHexAttr(eq, "trebleFrequency", project::setSongParamEqTrebleFrequency, true);
      }
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
    if (!el.hasAttribute(attr)) return;
    String val = el.getAttribute(attr).trim();
    if (val.isEmpty()) return;
    try {
      // Deluge stores these as raw int32 bit patterns; treat as hex→float
      setter.accept(Math.abs(DelugeHexMapper.hexToFloat(val)));
    } catch (Exception e) {
      // fall through, don't set
    }
  }

  private static void readSongRawAttr(
      Element el, String attr, java.util.function.Consumer<Float> setter, float def) {
    if (!el.hasAttribute(attr)) return;
    String val = el.getAttribute(attr).trim();
    if (val.isEmpty()) return;
    try {
      setter.accept(Math.abs(DelugeHexMapper.hexToFloat(val)));
    } catch (Exception e) {
    }
  }

  private static void readSongHexAttr(
      Element el, String attr, java.util.function.Consumer<Float> setter, boolean unipolar) {
    if (!el.hasAttribute(attr)) return;
    String val = el.getAttribute(attr).trim();
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
    if (!el.hasAttribute(attr)) return;
    String val = el.getAttribute(attr).trim();
    if (val.isEmpty() || !val.startsWith("0x")) return;
    try {
      setter.accept(DelugeHexMapper.hexToHz(val));
    } catch (Exception e) {
    }
  }

  private static void parseKitParamsElement(Element kp, ClipModel clip) {
    parseParamsAsKitParams(kp, clip);
  }

  private static void parseParamsAsKitParams(Element params, ClipModel clip) {
    readKitHexAttr(params, "reverbAmount", clip, "reverbAmount");
    readKitHexAttr(params, "volume", clip, "volume");
    readKitHexAttr(params, "pan", clip, "pan");
    readKitHexAttr(params, "sidechainCompressorShape", clip, "sidechainCompressorShape");
    readKitHexAttr(params, "sidechainCompressorVolume", clip, "sidechainCompressorVolume");
    readKitHexAttr(params, "modFXRate", clip, "modFXRate");
    readKitHexAttr(params, "modFXDepth", clip, "modFXDepth");
    readKitHexAttr(params, "modFXOffset", clip, "modFXOffset");
    readKitHexAttr(params, "modFXFeedback", clip, "modFXFeedback");
    readKitHexAttr(params, "stutterRate", clip, "stutterRate");
    readKitHexAttr(params, "sampleRateReduction", clip, "sampleRateReduction");
    readKitHexAttr(params, "bitCrush", clip, "bitCrush");
    readKitHexAttr(params, "compressorThreshold", clip, "compressorThreshold");
    readKitHexAttr(params, "lpfMorph", clip, "lpfMorph");
    readKitHexAttr(params, "hpfMorph", clip, "hpfMorph");
    // Child elements (delay, lpf, hpf, equalizer)
    parseKitParamChildElements(params, clip);
  }

  private static void parseKitParamChildElements(Element parent, ClipModel clip) {
    NodeList delay = parent.getElementsByTagName("delay");
    if (delay.getLength() > 0) {
      Element d = (Element) delay.item(0);
      readKitHexAttr(d, "rate", clip, "delayRate");
      readKitHexAttr(d, "feedback", clip, "delayFeedback");
    }
    NodeList lpf = parent.getElementsByTagName("lpf");
    if (lpf.getLength() > 0) {
      Element l = (Element) lpf.item(0);
      readKitHexAttr(l, "frequency", clip, "lpfFrequency");
      readKitHexAttr(l, "resonance", clip, "lpfResonance");
    }
    NodeList hpf = parent.getElementsByTagName("hpf");
    if (hpf.getLength() > 0) {
      Element h = (Element) hpf.item(0);
      readKitHexAttr(h, "frequency", clip, "hpfFrequency");
      readKitHexAttr(h, "resonance", clip, "hpfResonance");
    }
    NodeList eq = parent.getElementsByTagName("equalizer");
    if (eq.getLength() == 0) {
      eq = parent.getElementsByTagName("eq");
    }
    if (eq.getLength() > 0) {
      Element eqEl = (Element) eq.item(0);
      readKitHexAttr(eqEl, "bass", clip, "eqBass");
      readKitHexAttr(eqEl, "treble", clip, "eqTreble");
      readKitHexAttr(eqEl, "bassFrequency", clip, "eqBassFrequency");
      readKitHexAttr(eqEl, "trebleFrequency", clip, "eqTrebleFrequency");
    }
  }

  private static void readKitHexAttr(Element el, String attr, ClipModel clip, String paramName) {
    if (!el.hasAttribute(attr)) return;
    String val = el.getAttribute(attr).trim();
    if (val.isEmpty() || !val.startsWith("0x")) return;
    try {
      clip.setKitParam(paramName, Math.abs(DelugeHexMapper.hexToFloat(val)));
    } catch (Exception e) {
    }
  }
}
