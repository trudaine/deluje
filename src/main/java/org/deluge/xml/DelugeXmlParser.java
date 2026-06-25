package org.deluge.xml;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.deluge.firmware2.Param;
import org.deluge.model.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class DelugeXmlParser {

  private static final Logger LOG = Logger.getLogger(DelugeXmlParser.class.getName());

  // ── Declarative bindings for simple synth fields ──

  private static final List<FieldBinding<?>> DIRECT_BINDINGS =
      List.of(
          // osc1 type: attribute or child element
          FieldBinding.attrOrChild(
              "osc1", "type", SynthTrackModel::setOsc1Type, String::toUpperCase),
          // osc2 type: child element only
          FieldBinding.childText(
              "osc2", "type", SynthTrackModel::setOsc2Type, String::toUpperCase));

  private static final List<FieldBinding<?>> DEFAULT_PARAMS_BINDINGS =
      List.of(
          FieldBinding.hexHz("defaultParams", "lpfFrequency", SynthTrackModel::setLpfFreq),
          FieldBinding.hexFloat("defaultParams", "lpfResonance", SynthTrackModel::setLpfRes),
          FieldBinding.hexHz("defaultParams", "hpfFrequency", SynthTrackModel::setHpfFreq),
          FieldBinding.hexFloat("defaultParams", "hpfResonance", SynthTrackModel::setHpfRes),
          FieldBinding.hexFloat(
              "defaultParams", "modulator1Feedback", SynthTrackModel::setModulator1Feedback),
          FieldBinding.hexFloat(
              "defaultParams", "modulator2Amount", SynthTrackModel::setModulator2Amount),
          FieldBinding.hexFloat(
              "defaultParams", "modulator2Feedback", SynthTrackModel::setModulator2Feedback),
          FieldBinding.hexFloat(
              "defaultParams", "carrier1Feedback", SynthTrackModel::setCarrier1Feedback),
          FieldBinding.hexFloat(
              "defaultParams", "carrier2Feedback", SynthTrackModel::setCarrier2Feedback),
          // Extended defaultParams fields
          FieldBinding.hexFloat("defaultParams", "oscAVolume", SynthTrackModel::setOscAVolume),
          FieldBinding.hexFloat("defaultParams", "oscBVolume", SynthTrackModel::setOscBVolume),
          FieldBinding.hexFloat("defaultParams", "noiseVolume", SynthTrackModel::setNoiseVol),
          FieldBinding.hexFloat("defaultParams", "volume", SynthTrackModel::setVolume),
          FieldBinding.hexFloatBipolar("defaultParams", "pan", SynthTrackModel::setPan),
          FieldBinding.hexFloat("defaultParams", "portamento", SynthTrackModel::setPortamento),
          FieldBinding.hexFloat("defaultParams", "modFXRate", SynthTrackModel::setModFxRate),
          FieldBinding.hexFloat("defaultParams", "modFXDepth", SynthTrackModel::setModFxDepth),
          FieldBinding.hexFloat(
              "defaultParams", "modFXFeedback", SynthTrackModel::setModFxFeedback),
          FieldBinding.hexFloat("defaultParams", "reverbAmount", SynthTrackModel::setReverbSend),
          FieldBinding.hexFloat("defaultParams", "stutterRate", SynthTrackModel::setStutterRate),
          FieldBinding.hexFloat(
              "defaultParams", "sampleRateReduction", SynthTrackModel::setSampleRateReduction),
          FieldBinding.hexFloat("defaultParams", "bitCrush", SynthTrackModel::setBitCrush),
          FieldBinding.hexFloat("defaultParams", "delayRate", SynthTrackModel::setDelaySend),
          FieldBinding.hexFloat("defaultParams", "waveIndex", SynthTrackModel::setWaveIndex));

  // ── Public entry points ──

  public static KitTrackModel parseKit(java.io.File xmlFile) throws Exception {
    return parseKit(new java.io.FileInputStream(xmlFile), xmlFile.getName().replace(".XML", ""));
  }

  public static KitTrackModel parseKit(InputStream is, String name) throws Exception {
    Document doc = parseXml(is);
    Element root = doc.getDocumentElement();

    Element kitNode = root;
    if (!root.getTagName().equals("kit")) {
      NodeList kits = root.getElementsByTagName("kit");
      if (kits.getLength() > 0) {
        kitNode = (Element) kits.item(0);
      }
    }

    KitTrackModel kit = new KitTrackModel(name);

    NodeList soundNodes = kitNode.getElementsByTagName("sound");
    for (int i = 0; i < soundNodes.getLength(); i++) {
      Element soundNode = (Element) soundNodes.item(i);
      String soundName = "SOUND " + i;
      if (soundNode.hasAttribute("name")) {
        soundName = soundNode.getAttribute("name");
      } else {
        NodeList nameNodes = soundNode.getElementsByTagName("name");
        if (nameNodes.getLength() > 0) {
          soundName = nameNodes.item(0).getTextContent();
        }
      }

      SoundDrum sound = parseSoundDrum(soundNode, soundName);
      kit.addDrum(sound);
    }

    return kit;
  }

  /**
   * Shared zone parser: reads zone data from an osc1 child element (both attribute and
   * child-element formats).
   */
  private static void parseZoneFromOsc(SoundDrum sound, Element soundNode) {
    NodeList oscNodes = soundNode.getElementsByTagName("osc1");
    if (oscNodes.getLength() == 0) return;
    Element osc = (Element) oscNodes.item(0);
    NodeList zoneNodes = osc.getElementsByTagName("zone");
    if (zoneNodes.getLength() == 0) return;
    Element zone = (Element) zoneNodes.item(0);

    // Try attributes first (newer format)
    String ss = zone.getAttribute("startSamplePos");
    String es = zone.getAttribute("endSamplePos");
    String sm = zone.getAttribute("startMilliseconds");
    String em = zone.getAttribute("endMilliseconds");
    String sSec = zone.getAttribute("startSeconds");
    String eSec = zone.getAttribute("endSeconds");

    // Fall back to child elements if attributes are empty (older format)
    if (ss.isEmpty()) {
      NodeList children = zone.getElementsByTagName("startSamplePos");
      if (children.getLength() > 0) ss = children.item(0).getTextContent();
    }
    if (es.isEmpty()) {
      NodeList children = zone.getElementsByTagName("endSamplePos");
      if (children.getLength() > 0) es = children.item(0).getTextContent();
    }
    if (sm.isEmpty()) {
      NodeList children = zone.getElementsByTagName("startMilliseconds");
      if (children.getLength() > 0) sm = children.item(0).getTextContent();
    }
    if (em.isEmpty()) {
      NodeList children = zone.getElementsByTagName("endMilliseconds");
      if (children.getLength() > 0) em = children.item(0).getTextContent();
    }
    if (sSec.isEmpty()) {
      NodeList children = zone.getElementsByTagName("startSeconds");
      if (children.getLength() > 0) sSec = children.item(0).getTextContent();
    }
    if (eSec.isEmpty()) {
      NodeList children = zone.getElementsByTagName("endSeconds");
      if (children.getLength() > 0) eSec = children.item(0).getTextContent();
    }

    if (!es.isEmpty()) sound.setEndSamplePos(Integer.parseInt(es));
    if (!ss.isEmpty()) sound.setStartSamplePos(Integer.parseInt(ss));
    // Firmware: seconds * 1000 -> milliseconds, same as startMilliseconds/endMilliseconds
    if (!eSec.isEmpty()) sound.setEndMs(Float.parseFloat(eSec) * 1000.0f);
    if (!sSec.isEmpty()) sound.setStartMs(Float.parseFloat(sSec) * 1000.0f);
    if (!em.isEmpty()) sound.setEndMs(Float.parseFloat(em));
    if (!sm.isEmpty()) sound.setStartMs(Float.parseFloat(sm));

    // startLoopPos/endLoopPos (conditional — firmware only writes these for looped zones)
    String slp = zone.getAttribute("startLoopPos");
    if (slp.isEmpty()) {
      NodeList slpNodes = zone.getElementsByTagName("startLoopPos");
      if (slpNodes.getLength() > 0) slp = slpNodes.item(0).getTextContent();
    }
    String elp = zone.getAttribute("endLoopPos");
    if (elp.isEmpty()) {
      NodeList elpNodes = zone.getElementsByTagName("endLoopPos");
      if (elpNodes.getLength() > 0) elp = elpNodes.item(0).getTextContent();
    }
    if (!slp.isEmpty()) sound.setStartLoopPos(Integer.parseInt(slp));
    if (!elp.isEmpty()) sound.setEndLoopPos(Integer.parseInt(elp));
  }

  public static SynthTrackModel parseSynth(java.io.File xmlFile) throws Exception {
    return parseSynth(new java.io.FileInputStream(xmlFile), xmlFile.getName().replace(".XML", ""));
  }

  public static SynthTrackModel parseSynth(InputStream is, String name) throws Exception {
    Document doc = parseXml(is);
    Element root = doc.getDocumentElement();

    Element soundNode = root;
    if (!root.getTagName().equals("sound")) {
      NodeList sounds = root.getElementsByTagName("sound");
      if (sounds.getLength() > 0) {
        soundNode = (Element) sounds.item(0);
      }
    }

    SynthTrackModel synth = new SynthTrackModel(name);
    populateSynth(soundNode, synth);
    return synth;
  }

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
            KitTrackModel kit = parseKitElement(childNode);
            project.addTrack(kit);
            System.out.println("PARSER: Loaded kit track " + kit.getName());
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
              System.out.println("PARSER: Loaded midi track " + midiTrack.getName());
            } else {
              SynthTrackModel synth = parseSynthElement(childNode);
              project.addTrack(synth);
              System.out.println("PARSER: Loaded synth track " + synth.getName());
            }
          } else if ("audioTrack".equals(tagName)) {
            AudioTrackModel audioTrack = parseAudioTrackElement(childNode);
            project.addTrack(audioTrack);
            System.out.println("PARSER: Loaded audio track " + audioTrack.getName());
          }
        }
      }
    }

    // 2. Parse Tracks (Clips)
    NodeList tracksNodes = songNode.getElementsByTagName("tracks");
    if (tracksNodes.getLength() > 0) {
      Element tracks = (Element) tracksNodes.item(0);
      NodeList trackList = tracks.getElementsByTagName("track");

      System.out.println("PARSER: Found " + trackList.getLength() + " tracks in XML");

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
          System.out.println(
              "PARSER: Created clip "
                  + clip.getName()
                  + " for track "
                  + targetTrack.getName()
                  + " with rows "
                  + rowCount);

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
              if (liftAttr != null && !liftAttr.isEmpty()) {
                hexData = liftAttr;
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
                // Detect format: noteDataWithLift = 22 chars/note
                int hcpn = DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_OLD;
                if (liftAttr != null && !liftAttr.isEmpty()) {
                  hcpn = DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_LIFT;
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
          System.out.println(
              "PARSER: Track index "
                  + i
                  + " out of bounds for project tracks size "
                  + projectTracks.size());
        }
      }
    }

    // 2b. Parse Session Clips (c1.2.0+ format — <sessionClips>/<instrumentClip> instead of
    // <tracks>/<track>)
    NodeList sessionClipsNodes = songNode.getElementsByTagName("sessionClips");
    if (sessionClipsNodes.getLength() > 0) {
      Element sessionClips = (Element) sessionClipsNodes.item(0);
      NodeList clipNodeList = sessionClips.getElementsByTagName("instrumentClip");

      System.out.println("PARSER: Found " + clipNodeList.getLength() + " instrumentClips in XML");

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
          System.out.println(
              "PARSER: No matching track for instrumentClip["
                  + i
                  + "] (isKitClip="
                  + isKitClip
                  + ")");
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
        System.out.println(
            "PARSER: instrumentClip["
                + i
                + "] slot="
                + slot
                + " subSlot="
                + subSlot
                + " name="
                + name_
                + " -> "
                + targetTrack.getName()
                + " ("
                + targetTrack.getClass().getSimpleName()
                + ") isKitClip="
                + isKitClip);

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
            if (la != null && !la.isEmpty()) hd = la;
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
        clip.setTripletMode(tripletMode);
        clip.setPlayDirection(readPlayDirectionAttr(clipElem));
        // C clip.cpp:713-715 — read + clamp the session section (255 stays unassigned).
        if (clipElem.hasAttribute("section")) {
          clip.setSection(Integer.parseInt(clipElem.getAttribute("section").trim()));
        }
        System.out.println(
            "PARSER: Created clip "
                + clip.getName()
                + " for track "
                + targetTrack.getName()
                + " rows="
                + rowCount
                + " steps="
                + stepCount);

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
            if (liftAttr != null && !liftAttr.isEmpty()) {
              hexData = liftAttr;
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
              System.out.println(
                  "  PARSER: " + rowLabel + " activeSteps=[" + sb + "] from " + hexPrefix);
            } else if (drumIdx >= 0) {
              System.out.println("  PARSER: drumIndex=" + drumIdx + " no noteData (empty row)");
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
        targetTrack.addClip(clip);

        // Parse the clip's <soundParams>: STATIC values into the track model (the song format
        // keeps all sound params here), then automation.
        switch (targetTrack) {
          case SynthTrackModel stm -> {
            NodeList soundParamsList = clipElem.getElementsByTagName("soundParams");
            if (soundParamsList.getLength() > 0) {
              Element spEl = (Element) soundParamsList.item(0);
              parseClipSoundParamsStatics(spEl, stm);
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
      System.out.println("PARSER: Found " + audioClipNodeList.getLength() + " audioClips in XML");
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
          System.out.println(
              "PARSER: No matching audio track for audioClip[" + i + "] trackName=" + trackName);
          continue;
        }

        AudioTrackModel.AudioClip clip = new AudioTrackModel.AudioClip();
        clip.setTrackName(trackName);
        if (clipElem.hasAttribute("filePath")) clip.setFilePath(clipElem.getAttribute("filePath"));
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
        System.out.println(
            "PARSER: Added audioClip["
                + i
                + "] trackName="
                + trackName
                + " file="
                + clip.getFilePath()
                + " to track "
                + targetTrack.getName());
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
      System.out.println(
          "PARSER: Found " + arrClipNodeList.getLength() + " arrangement-only clips in XML");
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
            if (la != null && !la.isEmpty()) hd = la;
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
          clip.setTripletMode(tripletMode);
          clip.setArrangementOnly(true);

          // Parse note row cells
          for (int r = 0; r < rowCount; r++) {
            Element nr = (Element) noteRowList.item(r);
            String noteStr = nr.getAttribute("note");
            int noteVal = noteStr != null && !noteStr.isEmpty() ? Integer.parseInt(noteStr) : 60;

            String hd = null;
            String la = nr.getAttribute("liftActions");
            if (la != null && !la.isEmpty()) hd = la;
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
              else if (data.length() > 0
                  && data.length() % DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_SPLIT == 0) {
                hcpn = DelugeNoteDataMapper.HEX_CHARS_PER_NOTE_SPLIT;
              }
              for (int p = 0; p + hcpn <= data.length(); p += hcpn) {
                try {
                  int pos = (int) Long.parseLong(data.substring(p, p + 8), 16);
                  int length = (int) Long.parseLong(data.substring(p + 8, p + 16), 16);
                  int sVal =
                      (hcpn > 16) ? (int) Long.parseLong(data.substring(p + 16, p + 24), 16) : 0;
                  int velocity = (sVal & 0xFF00) >> 8;

                  int sidx = pos / stepTicks;
                  if (sidx >= 0 && sidx < stepCount) {
                    clip.setRowYNote(r, noteVal);
                    clip.setStep(
                        r,
                        sidx,
                        StepData.of(
                            true,
                            (float) noteVal,
                            velocity / 127f,
                            length / (float) stepTicks,
                            noteVal));
                  }
                } catch (NumberFormatException ignored) {
                }
              }
            }
          }

          targetTrack.addClip(clip);
          allArrangementClips.add(clip);
          System.out.println(
              "PARSER: Loaded arrangement-only clip "
                  + clip.getName()
                  + " for track "
                  + targetTrack.getName());
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

    NodeList tracksList = songNode.getElementsByTagName("track");
    if (tracksList.getLength() == 0) {
      tracksList = songNode.getElementsByTagName("instrumentClip");
    }
    for (int t = 0; t < tracksList.getLength() && t < project.getTracks().size(); t++) {
      Element trackElem = (Element) tracksList.item(t);

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
              System.out.println(
                  "PARSER: Loaded ArrangerClip placement on track index "
                      + t
                      + " at startTicks="
                      + pos
                      + " len="
                      + length);
            }
          } catch (NumberFormatException ignored) {
          }
        }
      }
    }

    // DIAGNOSTIC: final ProjectModel summary
    System.out.println("=== PARSER: ProjectModel after parseSong ===");
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

  /** Serialize a DOM element back to an XML string (used to preserve multisample osc subtrees). */
  static String nodeToXmlString(org.w3c.dom.Node node) {
    try {
      javax.xml.transform.Transformer t =
          javax.xml.transform.TransformerFactory.newInstance().newTransformer();
      t.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes");
      t.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes");
      java.io.StringWriter sw = new java.io.StringWriter();
      t.transform(
          new javax.xml.transform.dom.DOMSource(node),
          new javax.xml.transform.stream.StreamResult(sw));
      return sw.toString().trim();
    } catch (Exception e) {
      LOG.log(Level.WARNING, "nodeToXmlString failed", e);
      return null;
    }
  }

  // ── Package-private helper: used by both parseSynth and parseSynthElement ──

  static void populateSynth(Element soundNode, SynthTrackModel synth) {
    // Direct child bindings (osc1 attr/or child type, osc2 child type)
    applyDirectBindings(soundNode, synth);

    // ── DX7 patch (hex string from <osc1 dx7patch="...">) ──
    // ── Oscillator retrigger phase (from <osc1 retrigPhase="...">) ──
    NodeList osc1List = soundNode.getElementsByTagName("osc1");
    if (osc1List.getLength() > 0) {
      Element osc1 = (Element) osc1List.item(0);
      // Multisample oscillator (<sampleRanges> keyzones): capture verbatim so it survives the song
      // round-trip unchanged (our model doesn't re-model keyzones).
      if (osc1.getElementsByTagName("sampleRange").getLength() > 0) {
        synth.setOsc1RawXml(nodeToXmlString(osc1));
      }
      NodeList osc2L = soundNode.getElementsByTagName("osc2");
      if (osc2L.getLength() > 0
          && ((Element) osc2L.item(0)).getElementsByTagName("sampleRange").getLength() > 0) {
        synth.setOsc2RawXml(nodeToXmlString((Element) osc2L.item(0)));
      }
      if (osc1.hasAttribute("dx7patch")) {
        synth.setDx7Patch(osc1.getAttribute("dx7patch"));
      }
      // DX7 engine mode (dx7enginemode attribute on osc1 — write-on-demand, conditional on
      // non-zero)
      String dx7EngineModeStr = osc1.getAttribute("dx7enginemode");
      if (dx7EngineModeStr != null && !dx7EngineModeStr.isBlank()) {
        try {
          synth.setEngineType(Integer.parseInt(dx7EngineModeStr));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
      // DX7 random detune (dx7randomdetune attribute on osc1)
      String dx7RandStr = osc1.getAttribute("dx7randomdetune");
      if (dx7RandStr != null && !dx7RandStr.isBlank()) {
        try {
          synth.setDx7RandomDetune(Integer.parseInt(dx7RandStr));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
      // retrigPhase as child element (firmware XML format)
      NodeList rpNodes = osc1.getElementsByTagName("retrigPhase");
      if (rpNodes.getLength() > 0) {
        try {
          synth.setOsc1RetrigPhase((int) Long.parseLong(rpNodes.item(0).getTextContent().trim()));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }

      // Osc1 sample playback params (loopMode, reversed, timeStretch)
      String lmStr = osc1.getAttribute("loopMode");
      if (lmStr != null && !lmStr.isBlank()) {
        try {
          synth.setOsc1LoopMode(Integer.parseInt(lmStr));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
      readAttrBool(osc1, "reversed", synth::setOsc1Reversed);
      String tsStr = osc1.getAttribute("timeStretchEnable");
      if (tsStr != null && !tsStr.isBlank()) {
        synth.setOsc1TimeStretch("true".equalsIgnoreCase(tsStr) || "1".equals(tsStr));
      }
      readAttrFloatHex(osc1, "timeStretchAmount", synth::setOsc1TimeStretchAmount, true);
      readAttrBool(osc1, "linearInterpolation", v -> synth.setOsc1LinearInterpolation(v));
      // Osc1 cents (fine detune)
      String centsStr = osc1.getAttribute("cents");
      if (centsStr != null && !centsStr.isBlank()) {
        try {
          synth.setOsc1Cents(Integer.parseInt(centsStr));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
      // Osc1 sample fileName (attribute or child element)
      String osc1fn = osc1.getAttribute("fileName");
      if (osc1fn == null || osc1fn.isBlank()) {
        osc1fn = getChildText(osc1, "fileName");
      }
      if (osc1fn != null && !osc1fn.isBlank()) {
        synth.setOsc1SamplePath(osc1fn);
      }
    }

    // ── Oscillator retrigger phase for osc2 ──
    NodeList osc2List = soundNode.getElementsByTagName("osc2");
    if (osc2List.getLength() > 0) {
      Element osc2 = (Element) osc2List.item(0);
      NodeList rpNodes2 = osc2.getElementsByTagName("retrigPhase");
      if (rpNodes2.getLength() > 0) {
        try {
          synth.setOsc2RetrigPhase((int) Long.parseLong(rpNodes2.item(0).getTextContent().trim()));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
      // Osc2 transpose (semitones)
      String transStr = osc2.getAttribute("transpose");
      if (transStr != null && !transStr.isBlank()) {
        try {
          synth.setOsc2Transpose(Integer.parseInt(transStr));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
      // Osc2 cents (fine detune)
      String centsStr2 = osc2.getAttribute("cents");
      if (centsStr2 != null && !centsStr2.isBlank()) {
        try {
          synth.setOsc2Cents(Integer.parseInt(centsStr2));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
      // Osc2 oscillatorSync (hard sync on osc2, firmware writes only when s==1 && oscillatorSync)
      readAttrBool(osc2, "oscillatorSync", v -> synth.setOscillatorSync(v));
      // Osc2 sample-playback attrs (loopMode, reversed, timeStretch, linearInterpolation)
      String osc2lm = osc2.getAttribute("loopMode");
      if (osc2lm != null && !osc2lm.isBlank()) {
        try {
          synth.setOsc2LoopMode(Integer.parseInt(osc2lm));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
      readAttrBool(osc2, "reversed", synth::setOsc2Reversed);
      readAttrBool(osc2, "timeStretchEnable", synth::setOsc2TimeStretch);
      readAttrFloatHex(osc2, "timeStretchAmount", synth::setOsc2TimeStretchAmount, true);
      readAttrBool(osc2, "linearInterpolation", synth::setOsc2LinearInterpolation);
      // Osc2 sample fileName (attribute or child element)
      String osc2fn = osc2.getAttribute("fileName");
      if (osc2fn == null || osc2fn.isBlank()) {
        osc2fn = getChildText(osc2, "fileName");
      }
      if (osc2fn != null && !osc2fn.isBlank()) {
        synth.setOsc2SamplePath(osc2fn);
      }
    }

    // ── Unison ──
    Element unisonEl = getFirstChild(soundNode, "unison");
    if (unisonEl != null) {
      String numStr =
          unisonEl.hasAttribute("num")
              ? unisonEl.getAttribute("num")
              : getChildText(unisonEl, "num");
      if (numStr != null && !numStr.isBlank()) {
        try {
          synth.setUnisonNum(Integer.parseInt(numStr.trim()));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "Error parsing unison num", e);
        }
      }
      String detuneStr =
          unisonEl.hasAttribute("detune")
              ? unisonEl.getAttribute("detune")
              : getChildText(unisonEl, "detune");
      if (detuneStr != null && !detuneStr.isBlank()) {
        try {
          String val = detuneStr.trim();
          float dVal;
          if (val.startsWith("0x") || val.startsWith("0X")) {
            dVal = Math.abs(DelugeHexMapper.hexToFloat(val));
          } else {
            dVal = Float.parseFloat(val);
          }
          synth.setUnisonDetune(dVal);
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "Error parsing unison detune", e);
        }
      }
      String spreadStr =
          unisonEl.hasAttribute("spread")
              ? unisonEl.getAttribute("spread")
              : getChildText(unisonEl, "spread");
      if (spreadStr != null && !spreadStr.isBlank()) {
        try {
          String val = spreadStr.trim();
          float sVal;
          if (val.startsWith("0x") || val.startsWith("0X")) {
            sVal = Math.abs(DelugeHexMapper.hexToFloat(val));
          } else {
            sVal = Float.parseFloat(val);
          }
          synth.setUnisonStereoSpread(sVal);
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "Error parsing unison spread", e);
        }
      }
    }

    // ── Synth Mode ──
    parseSynthMode(soundNode, synth);

    // ── Polyphonic mode ──
    parsePolyphony(soundNode, synth);

    // ── Voice-stealing priority (attribute or child element) ──
    String vpStr = soundNode.getAttribute("voicePriority");
    if (vpStr == null || vpStr.isBlank()) vpStr = getChildText(soundNode, "voicePriority");
    if (vpStr != null && !vpStr.isBlank()) {
      try {
        synth.setVoicePriority(Integer.parseInt(vpStr.trim()));
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing voicePriority", e);
      }
    }

    // ── LPF Mode ──
    parseFilterMode(soundNode, synth);

    // ── FM Modulator 1 ──
    parseModulator1(soundNode, synth);
    parseModulator2(soundNode, synth);

    // ── Synth algorithm (from ProjectSerializer track attribute) ──
    if (soundNode.hasAttribute("synthAlgorithm")) {
      try {
        synth.setSynthAlgorithm(Integer.parseInt(soundNode.getAttribute("synthAlgorithm")));
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }

    // -- Engine type (-1=AUTO, 0=MODERN, 1=VINTAGE) --
    if (soundNode.hasAttribute("engineType")) {
      try {
        synth.setEngineType(Integer.parseInt(soundNode.getAttribute("engineType")));
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }

    // -- Transpose (attribute on <sound>, semitones) --
    if (soundNode.hasAttribute("transpose")) {
      try {
        synth.setTranspose(Integer.parseInt(soundNode.getAttribute("transpose")));
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }

    // -- Max voices (attribute on <sound>) --
    if (soundNode.hasAttribute("maxVoices")) {
      try {
        synth.setMaxVoiceCount(Integer.parseInt(soundNode.getAttribute("maxVoices")));
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }

    // ── FM ratio/amount (from ProjectSerializer track attributes) ──
    // These attributes are our serializer's shorthand, not real Deluge XML. The Deluge format (and
    // the C firmware) carries the modulator pitch as <modulator1><transpose>/<cents> and the depth
    // as the <modulator1Amount> knob — so convert here into those same model fields. A
    // <modulator1>/<modulator1Amount> element later in the file overrides (real format wins).
    if (soundNode.hasAttribute("fmRatio")) {
      try {
        float ratio = Float.parseFloat(soundNode.getAttribute("fmRatio"));
        synth.setFmRatio(ratio);
        if (ratio > 0) {
          int totalCents = Math.round(1200f * (float) (Math.log(ratio) / Math.log(2)));
          int transpose = Math.round(totalCents / 100f);
          synth.setModulator1Transpose(transpose);
          synth.setModulator1Cents(totalCents - transpose * 100);
        }
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }
    if (soundNode.hasAttribute("fmAmount")) {
      try {
        float amount = Float.parseFloat(soundNode.getAttribute("fmAmount"));
        synth.setFmAmount(amount);
        // Serializer round-trip: unipolar amount → bipolar knob (appendHexChildUnipolar).
        synth.setModulator1AmountQ31(
            (int)
                Math.max(
                    Integer.MIN_VALUE,
                    Math.min(Integer.MAX_VALUE, (amount * 2.0 - 1.0) * 2147483647.0)));
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }

    // ── FM feedback params (from ProjectSerializer track attributes, hex-encoded) ──
    String attrM1f = soundNode.getAttribute("modulator1Feedback");
    if (attrM1f != null && !attrM1f.isEmpty()) {
      try {
        synth.setModulator1Feedback(DelugeHexMapper.hexToFloat(attrM1f));
      } catch (Exception e) {
        LOG.log(Level.FINE, "Exception parsing XML hex attribute", e);
      }
    }
    String attrM2a = soundNode.getAttribute("modulator2Amount");
    if (attrM2a != null && !attrM2a.isEmpty()) {
      try {
        synth.setModulator2Amount(DelugeHexMapper.hexToFloat(attrM2a));
      } catch (Exception e) {
        LOG.log(Level.FINE, "Exception parsing XML hex attribute", e);
      }
    }
    String attrM2f = soundNode.getAttribute("modulator2Feedback");
    if (attrM2f != null && !attrM2f.isEmpty()) {
      try {
        synth.setModulator2Feedback(DelugeHexMapper.hexToFloat(attrM2f));
      } catch (Exception e) {
        LOG.log(Level.FINE, "Exception parsing XML hex attribute", e);
      }
    }
    String attrC1f = soundNode.getAttribute("carrier1Feedback");
    if (attrC1f != null && !attrC1f.isEmpty()) {
      try {
        synth.setCarrier1Feedback(DelugeHexMapper.hexToFloat(attrC1f));
      } catch (Exception e) {
        LOG.log(Level.FINE, "Exception parsing XML hex attribute", e);
      }
    }
    String attrC2f = soundNode.getAttribute("carrier2Feedback");
    if (attrC2f != null && !attrC2f.isEmpty()) {
      try {
        synth.setCarrier2Feedback(DelugeHexMapper.hexToFloat(attrC2f));
      } catch (Exception e) {
        LOG.log(Level.FINE, "Exception parsing XML hex attribute", e);
      }
    }

    // ── Envelopes 0-3 ──
    parseEnvelopes(soundNode, synth);

    // ── LFOs ──
    parseSynthLfo(soundNode, "lfo1", synth, true);
    parseSynthLfo(soundNode, "lfo2", synth, false);
    parseSynthLfo(soundNode, "lfo3", synth, false);
    parseSynthLfo(soundNode, "lfo4", synth, false);

    // ── Custom LFO Waveform ──
    String waveStr = attrOrChildText(soundNode, "customLfoWave");
    if (waveStr != null && !waveStr.isBlank()) {
      String[] tokens = waveStr.trim().split(",");
      int[] wave = synth.getCustomLfoWave();
      for (int i = 0; i < Math.min(256, tokens.length); i++) {
        try {
          wave[i] = Integer.parseInt(tokens[i].trim());
        } catch (NumberFormatException e) {
          // Keep default on parse error
        }
      }
    }

    // ── Arpeggiator ──
    parseSynthArp(soundNode, synth);

    // ── Compressor ──
    parseSynthCompressor(soundNode, synth);

    // ── Sidechain (at sound level, separate from compressor) ──
    NodeList sidechainNodes = soundNode.getElementsByTagName("sidechain");
    if (sidechainNodes.getLength() > 0) {
      Element sc = (Element) sidechainNodes.item(0);
      readAttrFloatHex(sc, "attack", synth::setSidechainAttack, true);
      readAttrFloatHex(sc, "release", synth::setSidechainRelease, true);
      if (sc.hasAttribute("syncLevel")) {
        try {
          synth.setSidechainSyncLevel(Integer.parseInt(sc.getAttribute("syncLevel")));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
      if (sc.hasAttribute("syncType")) {
        try {
          synth.setSidechainSyncType(Integer.parseInt(sc.getAttribute("syncType")));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
    }

    // ── Stutter config (quantized, reverse, pingPong) ──
    parseStutter(soundNode, synth);

    // ── defaultParams bindings (LPF/HPF freq+res, FM feedback params, + extended) ──
    applyDefaultParamsBindings(soundNode, synth);

    // ── Patch Cables ──
    parsePatchCables(soundNode, synth);

    // ── Mod Knobs ──
    parseModKnobs(soundNode, synth);

    // ── Per-sound delay (the instrument's own <delay> element) ──
    parseSynthDelay(soundNode, synth);

    // ── Equalizer inside defaultParams ──
    Element dpEl = getFirstChild(soundNode, "defaultParams");
    if (dpEl != null) {
      Element eqEl = getFirstChild(dpEl, "equalizer");
      if (eqEl != null) {
        readHexFloat(eqEl, "bass", synth::setEqBass);
        readHexFloat(eqEl, "treble", synth::setEqTreble);
      }
    }

    // ── Mod FX Type ──
    String mfxVal = soundNode.getAttribute("modFXType");
    if (mfxVal == null || mfxVal.isEmpty()) {
      mfxVal = soundNode.getAttribute("modFxType");
    }
    if (mfxVal == null || mfxVal.isEmpty()) {
      NodeList mfxNodes = soundNode.getElementsByTagName("modFXType");
      if (mfxNodes.getLength() > 0) {
        mfxVal = mfxNodes.item(0).getTextContent();
      } else {
        NodeList mfxNodesAlt = soundNode.getElementsByTagName("modFxType");
        if (mfxNodesAlt.getLength() > 0) {
          mfxVal = mfxNodesAlt.item(0).getTextContent();
        }
      }
    }
    if (mfxVal != null && !mfxVal.isBlank()) {
      synth.setModFxType(mfxVal.trim().toUpperCase());
    }
  }

  // ── Kit/song element parsers ──

  private static KitTrackModel parseKitElement(Element kitNode) throws Exception {
    String name = "KIT";
    if (kitNode.hasAttribute("name")) {
      name = kitNode.getAttribute("name");
    } else if (kitNode.hasAttribute("presetName")) {
      name = kitNode.getAttribute("presetName");
    } else {
      NodeList slotNodes = kitNode.getElementsByTagName("presetSlot");
      if (slotNodes.getLength() > 0) {
        name = "KIT " + slotNodes.item(0).getTextContent();
      }
    }

    NodeList soundNodes = kitNode.getElementsByTagName("sound");

    KitTrackModel kit = new KitTrackModel(name);

    for (int i = 0; i < soundNodes.getLength(); i++) {
      Element soundNode = (Element) soundNodes.item(i);
      String soundName = "SOUND " + i;
      if (soundNode.hasAttribute("name")) {
        soundName = soundNode.getAttribute("name");
      } else {
        NodeList nameNodes = soundNode.getElementsByTagName("name");
        if (nameNodes.getLength() > 0) {
          soundName = nameNodes.item(0).getTextContent();
        }
      }
      SoundDrum sound = parseSoundDrum(soundNode, soundName);
      kit.addDrum(sound);
    }
    return kit;
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
    populateSynth(soundNode, synth);
    return synth;
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

  /**
   * Parse an &lt;audioTrack&gt; element inside &lt;instruments&gt;. Audio tracks use
   * attribute-style format with named attributes and optional child elements for delay, compressor.
   */
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

  /**
   * Parse &lt;params&gt; child of &lt;audioClip&gt; — hex float attributes mirroring the songParams
   * structure.
   */
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

  /**
   * Static sound-parameter values from a CLIP's {@code <soundParams>} — where the firmware's song
   * format keeps ALL the sound's param values (the instrument element carries structure only; the C
   * runs the same {@code Sound::readParamsFromFile} on preset {@code defaultParams} and song {@code
   * soundParams} alike). Until 2026-06-12 these were fed only to automation parsing, so real-format
   * songs played instrument defaults — found via hardware comparison (filter wide open, instant
   * attack, no vibrato, default FM depth, noise patch playing a saw).
   */
  private static void parseClipSoundParamsStatics(Element sp, SynthTrackModel synth) {
    // The shared param table (same names as the preset defaultParams).
    for (FieldBinding<?> b : DEFAULT_PARAMS_BINDINGS) {
      b.applyTo(sp, synth);
    }

    // Raw Q31 knobs the factory prefers over the float views.
    String v;
    if (!(v = sp.getAttribute("modulator1Amount")).isEmpty()) {
      synth.setModulator1AmountQ31(DelugeHexMapper.hexToQ31(v));
    }
    if (!(v = sp.getAttribute("modulator2Amount")).isEmpty()) {
      synth.setModulator2AmountQ31(DelugeHexMapper.hexToQ31(v));
    }
    if (!(v = sp.getAttribute("modulator1Feedback")).isEmpty()) {
      synth.setModulator1FeedbackQ31(DelugeHexMapper.hexToQ31(v));
    }
    if (!(v = sp.getAttribute("modulator2Feedback")).isEmpty()) {
      synth.setModulator2FeedbackQ31(DelugeHexMapper.hexToQ31(v));
    }
    if (!(v = sp.getAttribute("carrier1Feedback")).isEmpty()) {
      synth.setCarrier1FeedbackQ31(DelugeHexMapper.hexToQ31(v));
    }
    if (!(v = sp.getAttribute("carrier2Feedback")).isEmpty()) {
      synth.setCarrier2FeedbackQ31(DelugeHexMapper.hexToQ31(v));
    }
    if (!(v = sp.getAttribute("pitchAdjust")).isEmpty()) {
      synth.setPitchAdjustQ31(DelugeHexMapper.hexToQ31(v));
    }
    if (!(v = sp.getAttribute("oscAPulseWidth")).isEmpty()) {
      synth.setOsc1PhaseWidthQ31(DelugeHexMapper.hexToQ31(v));
    }
    if (!(v = sp.getAttribute("oscBPulseWidth")).isEmpty()) {
      synth.setOsc2PhaseWidthQ31(DelugeHexMapper.hexToQ31(v));
    }
    if (!(v = sp.getAttribute("portamento")).isEmpty()) {
      synth.setPortamentoQ31(DelugeHexMapper.hexToQ31(v));
    }
    if (!(v = sp.getAttribute("waveFold")).isEmpty()) {
      synth.setWaveFoldQ31(DelugeHexMapper.hexToQ31(v));
    }
    // LFO rate knobs: firmware lfo1 = global (slot 0), lfo2 = local (slot 1).
    if (!(v = sp.getAttribute("lfo1Rate")).isEmpty()) {
      synth.setLfoRateKnobQ31(0, DelugeHexMapper.hexToQ31(v));
    }
    if (!(v = sp.getAttribute("lfo2Rate")).isEmpty()) {
      synth.setLfoRateKnobQ31(1, DelugeHexMapper.hexToQ31(v));
    }

    // Patched params the firmware reads as RAW Q31 from <soundParams> (readParamsFromFile copies
    // every param's 0x.. value verbatim into params[p].currentValue — no float, no curve). Our
    // float round-trip (hex→model float→normToKnob) mis-ranges several — e.g. normToLinearParamKnob
    // floored a linear minimum at -2^29 not INT_MIN, so a song's minimum resonance became moderate
    // and pushed the ladder past its tanh threshold (gross distortion, found via hardware
    // comparison). Reading raw matches the firmware; the factory overlays these over the float
    // mapping. (Envelopes, LFO rates, FM modulator volumes/feedbacks, portamento, waveFold are read
    // raw elsewhere in this method; the unpatched FX params — bitcrush/srr/stutter/modFX-offset/eq/
    // sidechain/reverb-send — use dedicated scalar fields and are out of scope here.)
    for (var e : SOUNDPARAMS_RAW_PATCHED.entrySet()) {
      rawKnob(sp, e.getKey(), synth, e.getValue());
    }

    // Envelopes: <envelope1..4 attack="0x..." .../> children (attribute style).
    String[] envTags = {"envelope1", "envelope2", "envelope3", "envelope4"};
    for (int i = 0; i < 4; i++) {
      NodeList envs = sp.getElementsByTagName(envTags[i]);
      if (envs.getLength() == 0) {
        continue;
      }
      Element envEl = (Element) envs.item(0);
      String attack = attrOrChildText(envEl, "attack");
      String decay = attrOrChildText(envEl, "decay");
      String sustain = attrOrChildText(envEl, "sustain");
      String release = attrOrChildText(envEl, "release");
      if (attack == null || decay == null || sustain == null || release == null) {
        continue;
      }
      synth.setEnv(
          i,
          new EnvelopeModel(
              DelugeHexMapper.hexToEnvTime(attack),
              DelugeHexMapper.hexToEnvTime(decay),
              DelugeHexMapper.hexToSustain(sustain),
              DelugeHexMapper.hexToEnvTime(release),
              "NONE",
              0.0f));
      // Raw knobs win in the factory (firmware-faithful envelope rate + sustain level curves).
      int sustainKnob =
          (sustain != null && !sustain.isEmpty())
              ? DelugeHexMapper.hexToQ31(sustain)
              : 858993459; // default 0.7 sustain Q31
      synth.setEnvKnobsQ31(
          i,
          DelugeHexMapper.hexToQ31(attack),
          DelugeHexMapper.hexToQ31(decay),
          sustainKnob,
          DelugeHexMapper.hexToQ31(release));
      synth.setRawParamKnob(Param.LOCAL_ENV_0_SUSTAIN + i, sustainKnob);
    }

    // Patch cables (the clip's set is authoritative in the song format).
    NodeList cables = sp.getElementsByTagName("patchCable");
    if (cables.getLength() > 0) {
      synth.getPatchCables().clear();
      parsePatchCables(sp, synth);
    }
  }

  /**
   * Parse the synth instrument's own {@code <delay>} (sync/pingPong/analog) into the model — this
   * drives FirmwareSound's per-sound delay. The feedback comes from the clip soundParams
   * (delayFeedback), wired in parseClipSoundParamsStatics. The direct child of {@code <sound>} is
   * used (not a clip's), matching the firmware's per-sound delay element.
   */
  private static void parseSynthDelay(Element soundNode, SynthTrackModel synth) {
    Element del = getFirstChild(soundNode, "delay");
    if (del == null) {
      return;
    }
    if (del.hasAttribute("syncLevel")) {
      try {
        synth.setDelaySyncLevel(Integer.parseInt(del.getAttribute("syncLevel").trim()));
      } catch (NumberFormatException ignored) {
      }
    }
    if (del.hasAttribute("syncType")) {
      try {
        synth.setDelaySyncType(Integer.parseInt(del.getAttribute("syncType").trim()));
      } catch (NumberFormatException ignored) {
      }
    }
    if (del.hasAttribute("pingPong")) {
      synth.setDelayPingPong(!"0".equals(del.getAttribute("pingPong").trim()));
    }
    if (del.hasAttribute("analog")) {
      synth.setDelayAnalog(!"0".equals(del.getAttribute("analog").trim()));
    }
  }

  /**
   * Song {@code <soundParams>} param-name → fw2 Param id, for the patched params the firmware reads
   * as raw Q31. Excludes params handled raw elsewhere (envelopes, LFO rates, FM modulator
   * volumes/feedbacks, portamento, waveFold) and the unpatched FX params (separate scalar fields).
   */
  private static final java.util.Map<String, Integer> SOUNDPARAMS_RAW_PATCHED =
      new java.util.LinkedHashMap<>();

  static {
    SOUNDPARAMS_RAW_PATCHED.put("lpfFrequency", Param.LOCAL_LPF_FREQ);
    SOUNDPARAMS_RAW_PATCHED.put("lpfResonance", Param.LOCAL_LPF_RESONANCE);
    SOUNDPARAMS_RAW_PATCHED.put("lpfMorph", Param.LOCAL_LPF_MORPH);
    SOUNDPARAMS_RAW_PATCHED.put("hpfFrequency", Param.LOCAL_HPF_FREQ);
    SOUNDPARAMS_RAW_PATCHED.put("hpfResonance", Param.LOCAL_HPF_RESONANCE);
    SOUNDPARAMS_RAW_PATCHED.put("hpfMorph", Param.LOCAL_HPF_MORPH);
    SOUNDPARAMS_RAW_PATCHED.put("volume", Param.LOCAL_VOLUME);
    SOUNDPARAMS_RAW_PATCHED.put("pan", Param.LOCAL_PAN);
    SOUNDPARAMS_RAW_PATCHED.put("oscAVolume", Param.LOCAL_OSC_A_VOLUME);
    SOUNDPARAMS_RAW_PATCHED.put("oscBVolume", Param.LOCAL_OSC_B_VOLUME);
    SOUNDPARAMS_RAW_PATCHED.put("noiseVolume", Param.LOCAL_NOISE_VOLUME);
    SOUNDPARAMS_RAW_PATCHED.put("oscAPhaseWidth", Param.LOCAL_OSC_A_PHASE_WIDTH);
    SOUNDPARAMS_RAW_PATCHED.put("oscBPhaseWidth", Param.LOCAL_OSC_B_PHASE_WIDTH);
    SOUNDPARAMS_RAW_PATCHED.put("oscAWavetablePosition", Param.LOCAL_OSC_A_WAVE_INDEX);
    SOUNDPARAMS_RAW_PATCHED.put("oscBWavetablePosition", Param.LOCAL_OSC_B_WAVE_INDEX);
    SOUNDPARAMS_RAW_PATCHED.put("oscAPitch", Param.LOCAL_OSC_A_PITCH_ADJUST);
    SOUNDPARAMS_RAW_PATCHED.put("oscBPitch", Param.LOCAL_OSC_B_PITCH_ADJUST);
    SOUNDPARAMS_RAW_PATCHED.put("modulator1Pitch", Param.LOCAL_MODULATOR_0_PITCH_ADJUST);
    SOUNDPARAMS_RAW_PATCHED.put("modulator2Pitch", Param.LOCAL_MODULATOR_1_PITCH_ADJUST);
    SOUNDPARAMS_RAW_PATCHED.put("modFXRate", Param.GLOBAL_MOD_FX_RATE);
    SOUNDPARAMS_RAW_PATCHED.put("modFXDepth", Param.GLOBAL_MOD_FX_DEPTH);
    SOUNDPARAMS_RAW_PATCHED.put("modFXOffset", Param.UNPATCHED_MOD_FX_OFFSET);
    SOUNDPARAMS_RAW_PATCHED.put("modFXFeedback", Param.UNPATCHED_MOD_FX_FEEDBACK);
    SOUNDPARAMS_RAW_PATCHED.put("delayRate", Param.GLOBAL_DELAY_RATE);
    SOUNDPARAMS_RAW_PATCHED.put("delayFeedback", Param.GLOBAL_DELAY_FEEDBACK);
    SOUNDPARAMS_RAW_PATCHED.put("reverbAmount", Param.GLOBAL_REVERB_AMOUNT);
    SOUNDPARAMS_RAW_PATCHED.put("stutterRate", Param.UNPATCHED_STUTTER_RATE);
    SOUNDPARAMS_RAW_PATCHED.put("sampleRateReduction", Param.UNPATCHED_SAMPLE_RATE_REDUCTION);
    SOUNDPARAMS_RAW_PATCHED.put("bitCrush", Param.UNPATCHED_BITCRUSHING);
    SOUNDPARAMS_RAW_PATCHED.put("eqBass", Param.UNPATCHED_BASS);
    SOUNDPARAMS_RAW_PATCHED.put("eqTreble", Param.UNPATCHED_TREBLE);
    SOUNDPARAMS_RAW_PATCHED.put("eqBassFrequency", Param.UNPATCHED_BASS_FREQ);
    SOUNDPARAMS_RAW_PATCHED.put("eqTrebleFrequency", Param.UNPATCHED_TREBLE_FREQ);
    SOUNDPARAMS_RAW_PATCHED.put("sidechainCompressorShape", Param.UNPATCHED_SIDECHAIN_SHAPE);
    SOUNDPARAMS_RAW_PATCHED.put("sidechainCompressorVolume", Param.UNPATCHED_SIDECHAIN_VOLUME);
  }

  /** Record a soundParams attribute as a raw Q31 param-knob override (attribute or child text). */
  private static void rawKnob(Element sp, String attr, SynthTrackModel synth, int paramId) {
    String v = attrOrChildText(sp, attr);
    if (v != null && !v.isBlank()) {
      synth.setRawParamKnob(paramId, DelugeHexMapper.hexToQ31(v));
    }
  }

  private static String attrOrChildText(Element el, String name) {
    String v = el.getAttribute(name);
    if (v != null && !v.isBlank()) {
      return v;
    }
    NodeList nodes = el.getElementsByTagName(name);
    if (nodes.getLength() > 0) {
      return nodes.item(0).getTextContent();
    }
    return null;
  }

  // ── Complex sub-parsers (don't fit simple tag→value bindings) ──

  private static void applyDirectBindings(Element soundNode, SynthTrackModel synth) {
    for (FieldBinding<?> b : DIRECT_BINDINGS) {
      b.apply(soundNode, synth);
    }
  }

  private static void applyDefaultParamsBindings(Element soundNode, SynthTrackModel synth) {
    for (FieldBinding<?> b : DEFAULT_PARAMS_BINDINGS) {
      b.apply(soundNode, synth);
    }
    Element dpEl = getFirstChild(soundNode, "defaultParams");
    if (dpEl != null) {
      for (var e : SOUNDPARAMS_RAW_PATCHED.entrySet()) {
        rawKnob(dpEl, e.getKey(), synth, e.getValue());
      }
      String v;
      if ((v = attrOrChildText(dpEl, "modulator1Amount")) != null && !v.isBlank()) {
        synth.setModulator1AmountQ31(DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "modulator2Amount")) != null && !v.isBlank()) {
        synth.setModulator2AmountQ31(DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "modulator1Feedback")) != null && !v.isBlank()) {
        synth.setModulator1FeedbackQ31(DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "modulator2Feedback")) != null && !v.isBlank()) {
        synth.setModulator2FeedbackQ31(DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "carrier1Feedback")) != null && !v.isBlank()) {
        synth.setCarrier1FeedbackQ31(DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "carrier2Feedback")) != null && !v.isBlank()) {
        synth.setCarrier2FeedbackQ31(DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "pitchAdjust")) != null && !v.isBlank()) {
        synth.setPitchAdjustQ31(DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "oscAPulseWidth")) != null && !v.isBlank()) {
        synth.setOsc1PhaseWidthQ31(DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "oscBPulseWidth")) != null && !v.isBlank()) {
        synth.setOsc2PhaseWidthQ31(DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "portamento")) != null && !v.isBlank()) {
        synth.setPortamentoQ31(DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "waveFold")) != null && !v.isBlank()) {
        synth.setWaveFoldQ31(DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "lfo1Rate")) != null && !v.isBlank()) {
        synth.setLfoRateKnobQ31(0, DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "lfo2Rate")) != null && !v.isBlank()) {
        synth.setLfoRateKnobQ31(1, DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "lfo3Rate")) != null && !v.isBlank()) {
        synth.setLfoRateKnobQ31(2, DelugeHexMapper.hexToQ31(v));
      }
      if ((v = attrOrChildText(dpEl, "lfo4Rate")) != null && !v.isBlank()) {
        synth.setLfoRateKnobQ31(3, DelugeHexMapper.hexToQ31(v));
      }
    }
  }

  private static void parseSynthMode(Element soundNode, SynthTrackModel synth) {
    String mode = soundNode.getAttribute("mode");
    if (mode == null || mode.isEmpty()) {
      NodeList modeNodes = soundNode.getElementsByTagName("mode");
      if (modeNodes.getLength() > 0) {
        mode = modeNodes.item(0).getTextContent();
      }
    }
    if (mode != null && !mode.isBlank()) {
      mode = mode.trim().toLowerCase();
      switch (mode) {
        case "fm" -> synth.setSynthMode(1);
        case "ringmod" -> synth.setSynthMode(2);
        default -> synth.setSynthMode(0);
      }
    }
  }

  private static void parsePolyphony(Element soundNode, SynthTrackModel synth) {
    String val = soundNode.getAttribute("polyphonic");
    if (val == null || val.isEmpty()) {
      NodeList polyNodes = soundNode.getElementsByTagName("polyphonic");
      if (polyNodes.getLength() > 0) {
        val = polyNodes.item(0).getTextContent();
      }
    }
    if (val != null && !val.isBlank()) {
      val = val.trim().toLowerCase();
      switch (val) {
        case "mono":
        case "0":
          synth.setPolyphony(SynthTrackModel.PolyphonyMode.MONO);
          break;
        case "legato":
          synth.setPolyphony(SynthTrackModel.PolyphonyMode.LEGATO);
          break;
        default:
          synth.setPolyphony(SynthTrackModel.PolyphonyMode.POLY);
          break;
      }
    }
  }

  private static void parseFilterMode(Element soundNode, SynthTrackModel synth) {
    // ── LPF Mode ──
    readAttrOrChildString(
        soundNode,
        "lpfMode",
        v -> {
          if ("24dB".equalsIgnoreCase(v)) {
            synth.setFilterMode(FilterMode.LADDER_24);
          } else if ("SVF".equalsIgnoreCase(v)) {
            synth.setFilterMode(FilterMode.SVF);
          } else if ("DRIVE".equalsIgnoreCase(v)) {
            synth.setFilterMode(FilterMode.DRIVE);
          } else if ("SVF_BAND".equalsIgnoreCase(v) || "SVF Band".equalsIgnoreCase(v)) {
            synth.setFilterMode(FilterMode.SVF_BAND);
          } else if ("SVF_NOTCH".equalsIgnoreCase(v) || "SVF Notch".equalsIgnoreCase(v)) {
            synth.setFilterMode(FilterMode.SVF_NOTCH);
          } else {
            synth.setFilterMode(FilterMode.LADDER_12);
          }
        });

    // ── HPF Mode ──
    readAttrOrChildString(
        soundNode,
        "hpfMode",
        v -> {
          if ("24dB".equalsIgnoreCase(v)) {
            synth.setHpfMode(FilterMode.LADDER_24);
          } else if ("SVF".equalsIgnoreCase(v)) {
            synth.setHpfMode(FilterMode.SVF);
          } else if ("DRIVE".equalsIgnoreCase(v)) {
            synth.setHpfMode(FilterMode.DRIVE);
          } else if ("SVF_BAND".equalsIgnoreCase(v) || "SVF Band".equalsIgnoreCase(v)) {
            synth.setHpfMode(FilterMode.SVF_BAND);
          } else if ("SVF_NOTCH".equalsIgnoreCase(v) || "SVF Notch".equalsIgnoreCase(v)) {
            synth.setHpfMode(FilterMode.SVF_NOTCH);
          } else {
            synth.setHpfMode(FilterMode.LADDER_12);
          }
        });

    // ── Filter Route ──
    readAttrOrChildString(
        soundNode,
        "filterRoute",
        v -> {
          if ("L2H".equalsIgnoreCase(v) || "LPF_TO_HPF".equalsIgnoreCase(v)) {
            synth.setFilterRoute(1); // LOW_TO_HIGH
          } else if ("PARALLEL".equalsIgnoreCase(v)) {
            synth.setFilterRoute(2); // PARALLEL
          } else {
            synth.setFilterRoute(0); // HIGH_TO_LOW / H2L
          }
        });
  }

  /** Frequency ratio for an FM modulator from its transpose (semitones) + cents. */
  private static float modulatorRatio(int transpose, int cents) {
    return (float) Math.pow(2.0, (transpose * 100 + cents) / 1200.0);
  }

  /** First child <tag> text as an int, or {@code def} if absent/unparseable. */
  private static int childInt(Element parent, String tag, int def) {
    NodeList n = parent.getElementsByTagName(tag);
    if (n.getLength() == 0) return def;
    try {
      return Integer.parseInt(n.item(0).getTextContent().trim());
    } catch (NumberFormatException e) {
      return def;
    }
  }

  /**
   * First <tag> text under soundNode as a raw signed Q31, falling back to a same-named attribute on
   * <sound> (our serializer's shorthand form), or {@code def} if absent in both.
   */
  private static int soundQ31(Element soundNode, String tag, int def) {
    NodeList n = soundNode.getElementsByTagName(tag);
    if (n.getLength() > 0) return DelugeHexMapper.hexToQ31(n.item(0).getTextContent());
    String attr = soundNode.getAttribute(tag);
    if (attr != null && !attr.isEmpty()) return DelugeHexMapper.hexToQ31(attr);
    return def;
  }

  private static void parseModulator1(Element soundNode, SynthTrackModel synth) {
    NodeList mod1Nodes = soundNode.getElementsByTagName("modulator1");
    if (mod1Nodes.getLength() > 0) {
      Element mod1 = (Element) mod1Nodes.item(0);
      int transpose = childInt(mod1, "transpose", 0);
      int cents = childInt(mod1, "cents", 0);
      synth.setFmRatio(modulatorRatio(transpose, cents));
      synth.setModulator1Transpose(transpose);
      synth.setModulator1Cents(cents);
      NodeList rpNodes = mod1.getElementsByTagName("retrigPhase");
      if (rpNodes.getLength() > 0) {
        try {
          synth.setMod1RetrigPhase((int) Long.parseLong(rpNodes.item(0).getTextContent().trim()));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
    }
    NodeList mod1AmtNodes = soundNode.getElementsByTagName("modulator1Amount");
    if (mod1AmtNodes.getLength() > 0) {
      String txt = mod1AmtNodes.item(0).getTextContent();
      synth.setFmAmount(toUnipolar(DelugeHexMapper.hexToFloat(txt)));
      synth.setModulator1AmountQ31(DelugeHexMapper.hexToQ31(txt));
    }
    // Raw feedback values for the firmware-faithful FM engine (default off = 0x80000000).
    synth.setModulator1FeedbackQ31(soundQ31(soundNode, "modulator1Feedback", Integer.MIN_VALUE));
    synth.setCarrier1FeedbackQ31(soundQ31(soundNode, "carrier1Feedback", Integer.MIN_VALUE));
    synth.setCarrier2FeedbackQ31(soundQ31(soundNode, "carrier2Feedback", Integer.MIN_VALUE));
    // Wavefolder knob (C reads "waveFold" into LOCAL_FOLD, sound.cpp:1273-1276).
    synth.setWaveFoldQ31(soundQ31(soundNode, "waveFold", Integer.MIN_VALUE));
    // Portamento knob (C UNPATCHED_PORTAMENTO, written as "portamento"; INT_MIN = off).
    synth.setPortamentoQ31(soundQ31(soundNode, "portamento", Integer.MIN_VALUE));
    // Saturation amount (C "clippingAmount" tag-or-attribute, plain small int;
    // mod_controllable_audio.cpp:736-737).
    String clipStr = soundNode.getAttribute("clippingAmount");
    if (clipStr == null || clipStr.isEmpty()) {
      NodeList clipNodes = soundNode.getElementsByTagName("clippingAmount");
      if (clipNodes.getLength() > 0) clipStr = clipNodes.item(0).getTextContent().trim();
    }
    if (clipStr != null && !clipStr.isEmpty()) {
      try {
        synth.setClippingAmount(Integer.parseInt(clipStr));
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "Error parsing clippingAmount", e);
      }
    }
    NodeList m1m0 = soundNode.getElementsByTagName("modulator1ToModulator0");
    if (m1m0.getLength() > 0) {
      String v = m1m0.item(0).getTextContent().trim();
      synth.setModulator1ToModulator0("1".equals(v) || "true".equalsIgnoreCase(v));
    }
  }

  private static void parseModulator2(Element soundNode, SynthTrackModel synth) {
    NodeList mod2Nodes = soundNode.getElementsByTagName("modulator2");
    if (mod2Nodes.getLength() > 0) {
      Element mod2 = (Element) mod2Nodes.item(0);
      int transpose = childInt(mod2, "transpose", 0);
      int cents = childInt(mod2, "cents", 0);
      synth.setFmRatio2(modulatorRatio(transpose, cents));
      synth.setModulator2Transpose(transpose);
      synth.setModulator2Cents(cents);
      NodeList rpNodes = mod2.getElementsByTagName("retrigPhase");
      if (rpNodes.getLength() > 0) {
        try {
          synth.setMod2RetrigPhase((int) Long.parseLong(rpNodes.item(0).getTextContent().trim()));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
    }
    NodeList mod2AmtNodes = soundNode.getElementsByTagName("modulator2Amount");
    if (mod2AmtNodes.getLength() > 0) {
      synth.setModulator2AmountQ31(DelugeHexMapper.hexToQ31(mod2AmtNodes.item(0).getTextContent()));
    }
    synth.setModulator2FeedbackQ31(soundQ31(soundNode, "modulator2Feedback", Integer.MIN_VALUE));
  }

  private static void parseEnvelopes(Element soundNode, SynthTrackModel synth) {
    // Try attribute-style first: <envelope attack="0x..." decay="0x..." ...>
    NodeList envNodes = soundNode.getElementsByTagName("envelope");
    if (envNodes.getLength() > 0) {
      for (int i = 0; i < Math.min(4, envNodes.getLength()); i++) {
        Element envNode = (Element) envNodes.item(i);
        EnvelopeModel env =
            new EnvelopeModel(
                DelugeHexMapper.hexToEnvTime(envNode.getAttribute("attack")),
                DelugeHexMapper.hexToEnvTime(envNode.getAttribute("decay")),
                DelugeHexMapper.hexToSustain(envNode.getAttribute("sustain")),
                DelugeHexMapper.hexToEnvTime(envNode.getAttribute("release")),
                "NONE",
                0.0f);
        synth.setEnv(i, env);
        String sustainAttr = envNode.getAttribute("sustain");
        int sustainKnob =
            (sustainAttr != null && !sustainAttr.isEmpty())
                ? DelugeHexMapper.hexToQ31(sustainAttr)
                : 858993459; // default 0.7 sustain Q31
        // Preserve the raw rate + level knobs for the firmware-faithful envelope rate/sustain
        // curves.
        synth.setEnvKnobsQ31(
            i,
            DelugeHexMapper.hexToQ31(envNode.getAttribute("attack")),
            DelugeHexMapper.hexToQ31(envNode.getAttribute("decay")),
            sustainKnob,
            DelugeHexMapper.hexToQ31(envNode.getAttribute("release")));
        if (sustainAttr != null && !sustainAttr.isEmpty()) {
          synth.setRawParamKnob(Param.LOCAL_ENV_0_SUSTAIN + i, sustainKnob);
        }
      }
    }
    // Fallback: try child-element format from
    // <defaultParams><envelope1><attack>0x...</attack></envelope1>
    // Only applies if no envelope elements were found at the top level.
    NodeList dpNodes = soundNode.getElementsByTagName("defaultParams");
    if (dpNodes.getLength() > 0 && envNodes.getLength() == 0) {
      Element dp = (Element) dpNodes.item(0);
      String[] envTags = {"envelope1", "envelope2", "envelope3", "envelope4"};
      for (int i = 0; i < 4; i++) {
        NodeList childEnvs = dp.getElementsByTagName(envTags[i]);
        if (childEnvs.getLength() > 0) {
          Element envEl = (Element) childEnvs.item(0);
          EnvelopeModel env = parseEnvelopeElement(envEl);
          // Only set if at least one non-default value was parsed
          if (env.attack() != 0.01f
              || env.decay() != 0.1f
              || env.sustain() != 0.7f
              || env.release() != 0.2f) {
            synth.setEnv(i, env);
            String sustainText = getChildText(envEl, "sustain");
            int sustainKnob =
                (sustainText != null && !sustainText.isEmpty())
                    ? DelugeHexMapper.hexToQ31(sustainText)
                    : 858993459; // default 0.7 sustain Q31
            // Preserve the raw rate + level knobs for the firmware-faithful envelope rate/sustain
            // curves.
            synth.setEnvKnobsQ31(
                i,
                DelugeHexMapper.hexToQ31(getChildText(envEl, "attack")),
                DelugeHexMapper.hexToQ31(getChildText(envEl, "decay")),
                sustainKnob,
                DelugeHexMapper.hexToQ31(getChildText(envEl, "release")));
            if (sustainText != null && !sustainText.isEmpty()) {
              synth.setRawParamKnob(Param.LOCAL_ENV_0_SUSTAIN + i, sustainKnob);
            }
          }
        }
      }
    }
  }

  private static void parsePatchCables(Element soundNode, SynthTrackModel synth) {
    NodeList cableList = soundNode.getElementsByTagName("patchCable");
    for (int i = 0; i < cableList.getLength(); i++) {
      Element cableElem = (Element) cableList.item(i);
      String src = cableElem.getAttribute("source");
      if (src == null || src.isEmpty()) src = getChildText(cableElem, "source");
      String dst = cableElem.getAttribute("destination");
      if (dst == null || dst.isEmpty()) dst = getChildText(cableElem, "destination");
      String amtStr = cableElem.getAttribute("amount");
      if (amtStr == null || amtStr.isEmpty()) amtStr = getChildText(cableElem, "amount");

      if (src != null
          && !src.isEmpty()
          && dst != null
          && !dst.isEmpty()
          && amtStr != null
          && !amtStr.isEmpty()) {
        String polarityStr = cableElem.getAttribute("polarity");
        if (polarityStr == null || polarityStr.isEmpty()) {
          polarityStr = getChildText(cableElem, "polarity");
        }
        PatchCable.Polarity polarityVal = PatchCable.Polarity.BIPOLAR;
        if (polarityStr != null && !polarityStr.isEmpty()) {
          if ("unipolar".equalsIgnoreCase(polarityStr.trim())) {
            polarityVal = PatchCable.Polarity.UNIPOLAR;
          } else if ("bipolar".equalsIgnoreCase(polarityStr.trim())) {
            polarityVal = PatchCable.Polarity.BIPOLAR;
          }
        } else {
          if ("aftertouch".equalsIgnoreCase(src.trim())) {
            polarityVal = PatchCable.Polarity.UNIPOLAR;
          } else {
            polarityVal = PatchCable.Polarity.BIPOLAR;
          }
        }
        float amt = PatchCable.applyScaling(dst.trim(), DelugeHexMapper.hexToFloat(amtStr));
        synth.addPatchCable(new PatchCable(src.trim(), dst.trim(), amt, polarityVal));
      }
    }
  }

  private static void parseModKnobs(Element soundNode, SynthTrackModel synth) {
    NodeList knobList = soundNode.getElementsByTagName("modKnob");
    for (int i = 0; i < knobList.getLength(); i++) {
      Element knobElem = (Element) knobList.item(i);
      String param = getChildText(knobElem, "controlsParam");
      if (param == null) param = knobElem.getAttribute("controlsParam");
      if (param != null && !param.isBlank() && i < synth.getModKnobs().size()) {
        String patchSrc = getChildText(knobElem, "patchSource");
        if (patchSrc == null) patchSrc = getChildText(knobElem, "patchAmountFromSource");
        if (patchSrc == null) patchSrc = knobElem.getAttribute("patchSource");
        if (patchSrc == null) patchSrc = knobElem.getAttribute("patchAmountFromSource");
        if (patchSrc == null || patchSrc.isBlank()) patchSrc = "NONE";
        synth.setModKnob(i, new ModKnob(param.trim(), patchSrc.trim()));
      }
    }
  }

  // ── Synth LFO / Arp / Compressor Parsers ──

  /** Parse an LFO element for synth tracks (supports attribute-style and child-element). */
  private static void parseSynthLfo(
      Element soundNode, String lfoTag, SynthTrackModel synth, boolean isLocal) {
    NodeList lfoNodes = soundNode.getElementsByTagName(lfoTag);
    if (lfoNodes.getLength() == 0) return;
    Element lfoEl = (Element) lfoNodes.item(0);

    LfoType waveform = LfoType.SINE;
    float rateHz = 1.0f;
    float depth = 1.0f;
    int syncLevel = 0;

    // type: attribute or child text
    String typeStr = lfoEl.getAttribute("type");
    if (typeStr == null || typeStr.isBlank()) typeStr = getChildText(lfoEl, "type");
    if (typeStr != null) waveform = parseLfoType(typeStr);

    // rate: hex Hz attribute or child text. Keep the Hz (for display/other uses) AND the raw Q31
    // knob (the firmware-faithful rate path feeds the knob straight to getExp, avoiding the lossy
    // hexToLfoHz round-trip).
    int rateKnobQ31 = 0; // 0 = firmware neutral rate
    String rateStr = lfoEl.getAttribute("rate");
    if (rateStr == null || rateStr.isBlank()) rateStr = getChildText(lfoEl, "rate");
    if (rateStr != null && !rateStr.isBlank()) {
      rateHz = DelugeHexMapper.hexToLfoHz(rateStr);
      rateKnobQ31 = DelugeHexMapper.hexToQ31(rateStr);
    }

    // depth: hex float attribute or child text
    String depthStr = lfoEl.getAttribute("depth");
    if (depthStr != null && !depthStr.isBlank()) {
      depth = toUnipolar(DelugeHexMapper.hexToFloat(depthStr));
    } else {
      String depthChild = getChildText(lfoEl, "depth");
      if (depthChild != null) depth = toUnipolar(DelugeHexMapper.hexToFloat(depthChild));
    }

    // syncLevel: attribute first, child-element fallback
    String syncStr = lfoEl.getAttribute("syncLevel");
    if (syncStr != null && !syncStr.isBlank()) {
      try {
        syncLevel = Integer.parseInt(syncStr);
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    } else {
      String syncChild = getChildText(lfoEl, "syncLevel");
      if (syncChild != null) {
        try {
          syncLevel = Integer.parseInt(syncChild);
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
    }

    // syncType: attribute on lfo element
    int syncType = 0;
    String syncTypeStr = lfoEl.getAttribute("syncType");
    if (syncTypeStr != null && !syncTypeStr.isBlank()) {
      try {
        syncType = Integer.parseInt(syncTypeStr);
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }

    int lfoIndex =
        switch (lfoTag) {
          case "lfo1" -> 0;
          case "lfo2" -> 1;
          case "lfo3" -> 2;
          case "lfo4" -> 3;
          default -> -1;
        };

    if (lfoIndex >= 0 && lfoIndex < 4) {
      synth.setLfo(
          lfoIndex, new LfoModel(rateHz, waveform, depth, "NONE", isLocal, syncLevel, syncType));
      synth.setLfoRateKnobQ31(lfoIndex, rateKnobQ31);
    }
  }

  /** Parse arpeggiator element for synth tracks. */
  private static void parseSynthArp(Element soundNode, SynthTrackModel synth) {
    NodeList arpNodes = soundNode.getElementsByTagName("arpeggiator");
    if (arpNodes.getLength() == 0) return;
    Element arpEl = (Element) arpNodes.item(0);

    String mode = "UP";
    float rate = 1.0f;
    int octaves = 1;
    float gate = 0.5f;
    boolean active = false;

    String modeStr = arpEl.getAttribute("mode");
    if (modeStr != null && !modeStr.isBlank()) mode = modeStr.toUpperCase();
    String rateStr = getChildText(arpEl, "rate");
    if (rateStr != null) rate = Math.abs(DelugeHexMapper.hexToFloat(rateStr));
    String octStr = getChildText(arpEl, "octaves");
    if (octStr != null) {
      try {
        octaves = Integer.parseInt(octStr);
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }
    String gateStr = getChildText(arpEl, "gate");
    if (gateStr != null) gate = Math.abs(DelugeHexMapper.hexToFloat(gateStr));
    String activeStr = arpEl.getAttribute("active");
    if (activeStr != null && !activeStr.isBlank()) {
      active = "true".equalsIgnoreCase(activeStr) || "1".equals(activeStr);
    }

    // Parse new arp fields
    int syncLevel = 0;
    String noteMode = "UP";
    String octaveMode = "UP";
    int stepRepeat = 1;
    int rhythmIndex = 0;
    int seqLength = 8;
    String syncStr = getChildText(arpEl, "sync");
    if (syncStr != null) {
      try {
        syncLevel = Integer.parseInt(syncStr);
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }
    String noteModeStr = arpEl.getAttribute("noteMode");
    if (noteModeStr != null && !noteModeStr.isBlank()) noteMode = noteModeStr.toUpperCase();
    String octModeStr = arpEl.getAttribute("octaveMode");
    if (octModeStr != null && !octModeStr.isBlank()) octaveMode = octModeStr.toUpperCase();
    String repeatStr = getChildText(arpEl, "stepRepeat");
    if (repeatStr != null) {
      try {
        stepRepeat = Integer.parseInt(repeatStr);
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }
    String rhythmStr = getChildText(arpEl, "rhythm");
    if (rhythmStr != null) {
      try {
        // The C may serialize the raw uint32 menu value (arpeggiator.cpp:1899) — parse as long
        // (raw values exceed int) and keep the bits; the factory normalizes raw vs 0..50 index.
        rhythmIndex = (int) Long.parseLong(rhythmStr.trim());
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }
    String seqLenStr = getChildText(arpEl, "seqLength");
    if (seqLenStr != null) {
      try {
        seqLength = Integer.parseInt(seqLenStr);
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }

    // arpMode: attribute on arpeggiator element
    int arpModeVal = -1;
    String arpModeStr = arpEl.getAttribute("arpMode");
    if (arpModeStr != null && !arpModeStr.isBlank()) {
      try {
        arpModeVal = Integer.parseInt(arpModeStr);
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }

    // mpeVelocity: attribute on arpeggiator element
    int mpeVelocity = 0;
    String mpeStr = arpEl.getAttribute("mpeVelocity");
    if (mpeStr != null && !mpeStr.isBlank()) {
      try {
        mpeVelocity = Integer.parseInt(mpeStr);
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }

    // syncType: attribute on arpeggiator element
    int syncType = 0;
    String arpSyncStr = arpEl.getAttribute("syncType");
    if (arpSyncStr != null && !arpSyncStr.isBlank()) {
      try {
        syncType = Integer.parseInt(arpSyncStr);
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }

    // Spread and probability attributes (attributes written by firmware via writeParamAsAttribute)
    float octaveSpread = readAttrFloatWithDefault(arpEl, "spreadOctave", 0f, false);
    float gateSpread = readAttrFloatWithDefault(arpEl, "spreadGate", 0f, false);
    float velSpread = readAttrFloatWithDefault(arpEl, "spreadVelocity", 0f, false);
    int ratchetAmount = readIntAttrWithDefault(arpEl, "ratchetAmount", 0);

    float noteProb = readAttrFloatWithDefault(arpEl, "noteProbability", 0f, false);
    float bassProb = readAttrFloatWithDefault(arpEl, "bassProbability", 0f, false);
    float swapProb = readAttrFloatWithDefault(arpEl, "swapProbability", 0f, false);
    float glideProb = readAttrFloatWithDefault(arpEl, "glideProbability", 0f, false);
    float reverseProb = readAttrFloatWithDefault(arpEl, "reverseProbability", 0f, false);
    float chordProb = readAttrFloatWithDefault(arpEl, "chordProbability", 0f, false);
    float ratchetProb = readAttrFloatWithDefault(arpEl, "ratchetProbability", 0f, false);
    int chordPolyphony = readIntAttrWithDefault(arpEl, "chordPolyphony", 0);

    synth.setArp(
        new ArpModel(
            active,
            mode,
            rate,
            octaves,
            Math.abs(gate),
            syncLevel,
            noteMode,
            octaveMode,
            stepRepeat,
            rhythmIndex,
            seqLength,
            octaveSpread,
            gateSpread,
            velSpread,
            ratchetAmount,
            mpeVelocity,
            syncType,
            noteProb,
            bassProb,
            swapProb,
            glideProb,
            reverseProb,
            chordProb,
            ratchetProb,
            chordPolyphony));
  }

  /** Parse compressor element for synth tracks. */
  private static void parseSynthCompressor(Element soundNode, SynthTrackModel synth) {
    NodeList compNodes = soundNode.getElementsByTagName("compressor");
    if (compNodes.getLength() == 0) {
      // Fallback: look up direct attributes on the soundNode
      String attackStr = soundNode.getAttribute("compressorAttack");
      if (attackStr != null && !attackStr.isBlank()) {
        synth.setCompressorAttack(Math.abs(DelugeHexMapper.hexToFloat(attackStr)));
      }
      String releaseStr = soundNode.getAttribute("compressorRelease");
      if (releaseStr != null && !releaseStr.isBlank()) {
        synth.setCompressorRelease(Math.abs(DelugeHexMapper.hexToFloat(releaseStr)));
      }
      String syncStr = soundNode.getAttribute("compressorSyncLevel");
      if (syncStr != null && !syncStr.isBlank()) {
        try {
          synth.setCompressorSyncLevel(Integer.parseInt(syncStr));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
      readAttrFloatHex(soundNode, "compressorThreshold", synth::setCompressorThreshold, true);
      readAttrFloatHex(soundNode, "compressorRatio", synth::setCompressorRatio, true);
      readAttrFloatHex(soundNode, "compressorBlend", synth::setCompressorBlend, true);
      String syncTypeStr = soundNode.getAttribute("compressorSyncType");
      if (syncTypeStr != null && !syncTypeStr.isBlank()) {
        try {
          synth.setCompressorSyncType(Integer.parseInt(syncTypeStr));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
      return;
    }
    Element compEl = (Element) compNodes.item(0);
    readAttrOrChildHexFloat(compEl, "attack", v -> synth.setCompressorAttack(Math.abs(v)));
    readAttrOrChildHexFloat(compEl, "release", v -> synth.setCompressorRelease(Math.abs(v)));
    readAttrOrChildInt(compEl, "syncLevel", 0, synth::setCompressorSyncLevel);
    readAttrOrChildHexFloat(compEl, "threshold", v -> synth.setCompressorThreshold(Math.abs(v)));
    readAttrOrChildHexFloat(compEl, "ratio", v -> synth.setCompressorRatio(Math.abs(v)));
    readAttrOrChildHexFloat(compEl, "blend", v -> synth.setCompressorBlend(Math.abs(v)));
    readAttrOrChildInt(compEl, "syncType", 0, synth::setCompressorSyncType);
  }

  // ── Kit Sound Full Synth Parity Parsers ──

  /**
   * Parse all synth-parity fields from a kit sound element and return a SoundDrum. Handles
   * SONG006668.XML attribute-style format and falls back to child-element format.
   */
  private static SoundDrum parseSoundDrum(Element soundNode, String soundName) {
    SoundDrum sound = new SoundDrum(soundName);

    // ── Sample path ──
    NodeList sampleNodes = soundNode.getElementsByTagName("sample");
    if (sampleNodes.getLength() > 0) {
      Element sampleNode = (Element) sampleNodes.item(0);
      if (sampleNode.hasAttribute("fileName")) {
        sound.setSamplePath(sampleNode.getAttribute("fileName"));
      } else {
        NodeList fnNodes = sampleNode.getElementsByTagName("fileName");
        if (fnNodes.getLength() > 0) {
          String fn = fnNodes.item(0).getTextContent();
          if (fn != null && !fn.isBlank()) {
            sound.setSamplePath(fn);
          }
        }
      }
    } else {
      NodeList oscNodes = soundNode.getElementsByTagName("osc1");
      if (oscNodes.getLength() > 0) {
        Element osc = (Element) oscNodes.item(0);
        if (osc.hasAttribute("fileName")) {
          sound.setSamplePath(osc.getAttribute("fileName"));
        } else {
          NodeList fnNodes = osc.getElementsByTagName("fileName");
          if (fnNodes.getLength() > 0) {
            sound.setSamplePath(fnNodes.item(0).getTextContent());
          }
        }
      }
    }

    // ── Zone (sample truncation) ──
    parseZoneFromOsc(sound, soundNode);

    // ── Root attributes ──
    readAttrOrChildString(
        soundNode,
        "polyphonic",
        v -> {
          if ("choke".equalsIgnoreCase(v)) {
            sound.setPolyphony(SynthTrackModel.PolyphonyMode.CHOKE);
          } else if ("mono".equalsIgnoreCase(v) || "0".equals(v) || "false".equalsIgnoreCase(v)) {
            sound.setPolyphony(SynthTrackModel.PolyphonyMode.MONO);
          } else if ("legato".equalsIgnoreCase(v)) {
            sound.setPolyphony(SynthTrackModel.PolyphonyMode.LEGATO);
          } else {
            sound.setPolyphony(SynthTrackModel.PolyphonyMode.POLY);
          }
        });
    readAttrOrChildInt(soundNode, "voicePriority", 1, sound::setVoicePriority);
    readAttrOrChildHexFloat(soundNode, "sideChainSend", sound::setSidechainSend);
    readAttrOrChildString(soundNode, "modFXType", sound::setModFxType);
    readAttrOrChildString(soundNode, "modFxType", sound::setModFxType);
    readAttrOrChildString(
        soundNode,
        "lpfMode",
        v -> {
          if ("24dB".equals(v)) sound.setLpfMode(FilterMode.LADDER_24);
          else sound.setLpfMode(FilterMode.LADDER_12);
        });
    readAttrOrChildString(
        soundNode,
        "hpfMode",
        v -> {
          if ("24dB".equals(v)) sound.setHpfMode(FilterMode.LADDER_24);
          else if ("SVF".equals(v)) sound.setHpfMode(FilterMode.SVF);
          else if ("DRIVE".equals(v)) sound.setHpfMode(FilterMode.DRIVE);
          else if ("SVF_BAND".equals(v) || "SVF Band".equalsIgnoreCase(v))
            sound.setHpfMode(FilterMode.SVF_BAND);
          else if ("SVF_NOTCH".equals(v) || "SVF Notch".equalsIgnoreCase(v))
            sound.setHpfMode(FilterMode.SVF_NOTCH);
          else sound.setHpfMode(FilterMode.LADDER_12);
        });
    readAttrString(
        soundNode,
        "mode",
        v -> {
          // mode for kit sounds maps to synth mode concept; not directly stored
        });
    readAttrString(
        soundNode,
        "filterRoute",
        v -> {
          // filterRoute is an internal routing flag; not directly stored
        });
    readAttrOrChildHexFloat(soundNode, "clippingAmount", sound::setClippingAmount);

    // ── Child elements ──
    // osc1 retrigPhase
    Element osc1El = getFirstChild(soundNode, "osc1");
    if (osc1El != null) {
      NodeList rpNodes = osc1El.getElementsByTagName("retrigPhase");
      if (rpNodes.getLength() > 0) {
        try {
          sound.setOsc1RetrigPhase((int) Long.parseLong(rpNodes.item(0).getTextContent().trim()));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
      NodeList wtNodes = osc1El.getElementsByTagName("wavetableIndexPct");
      if (wtNodes.getLength() > 0) {
        try {
          sound.setWavetableIndexPct(Integer.parseInt(wtNodes.item(0).getTextContent().trim()));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
    }

    // osc2
    Element osc2 = getFirstChild(soundNode, "osc2");
    if (osc2 != null) {
      String type = osc2.getAttribute("type");
      if (type == null || type.isEmpty() || type.isBlank()) {
        type = getChildText(osc2, "type");
      }
      if (type != null && !type.isBlank()) {
        sound.setOsc2Type(type.toUpperCase());
      }
      // osc2 sample fileName (attribute or child element)
      String osc2fn = osc2.getAttribute("fileName");
      if (osc2fn == null || osc2fn.isBlank()) {
        osc2fn = getChildText(osc2, "fileName");
      }
      if (osc2fn != null && !osc2fn.isBlank()) {
        sound.setOsc2SamplePath(osc2fn);
      }
      // osc2 zone (startSamplePos/endSamplePos/startLoopPos/endLoopPos)
      Element osc2Zone = getFirstChild(osc2, "zone");
      if (osc2Zone != null) {
        sound.setOsc2StartSamplePos(readIntAttr(osc2Zone, "startSamplePos", -1));
        sound.setOsc2EndSamplePos(readIntAttr(osc2Zone, "endSamplePos", -1));
        sound.setStartLoopPos(readIntAttr(osc2Zone, "startLoopPos", -1));
        sound.setEndLoopPos(readIntAttr(osc2Zone, "endLoopPos", -1));
      }
      // osc2 sample-playback attrs
      String osc2lm = osc2.getAttribute("loopMode");
      if (osc2lm != null && !osc2lm.isBlank()) {
        try {
          sound.setOsc2LoopMode(Integer.parseInt(osc2lm));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
      readAttrBool(osc2, "reversed", sound::setOsc2Reversed);
      readAttrBool(osc2, "timeStretchEnable", sound::setOsc2TimeStretch);
      readAttrFloatHex(osc2, "timeStretchAmount", sound::setOsc2TimeStretchAmount, true);
      readAttrBool(osc2, "linearInterpolation", sound::setOsc2LinearInterpolation);
      // osc2 retrigPhase
      NodeList rpNodes2 = osc2.getElementsByTagName("retrigPhase");
      if (rpNodes2.getLength() > 0) {
        try {
          sound.setOsc2RetrigPhase((int) Long.parseLong(rpNodes2.item(0).getTextContent().trim()));
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
    }

    // lfo1, lfo2
    parseDrumLfo(soundNode, "lfo1", sound, true);
    parseDrumLfo(soundNode, "lfo2", sound, false);

    // unison
    Element unisonEl = getFirstChild(soundNode, "unison");
    if (unisonEl != null) {
      sound.setUnisonNum(readIntAttr(unisonEl, "num", 1));
      sound.setUnisonDetune(Math.abs(DelugeHexMapper.hexToFloat(readAttr(unisonEl, "detune"))));
      String spreadStr = readAttr(unisonEl, "spread");
      if (spreadStr != null && !spreadStr.isEmpty()) {
        sound.setUnisonStereoSpread(Math.abs(DelugeHexMapper.hexToFloat(spreadStr)));
      }
    }

    // delay
    Element delayEl = getFirstChild(soundNode, "delay");
    if (delayEl != null) {
      readAttrOrChildHexFloat(delayEl, "rate", sound::setDelayRate);
      readAttrOrChildHexFloat(delayEl, "feedback", sound::setDelayFeedback);
      readAttrOrChildInt(delayEl, "pingPong", 0, sound::setDelayPingPong);
      readAttrOrChildInt(delayEl, "analog", 0, sound::setDelayAnalog);
    } else {
      // Fallback: look up direct attributes on soundNode
      String rateStr = readAttr(soundNode, "delayRate");
      if (rateStr != null) sound.setDelayRate(DelugeHexMapper.hexToFloat(rateStr));
      String feedbackStr = readAttr(soundNode, "delayFeedback");
      if (feedbackStr != null) sound.setDelayFeedback(DelugeHexMapper.hexToFloat(feedbackStr));
      sound.setDelayPingPong(readIntAttr(soundNode, "delayPingPong", 0));
      sound.setDelayAnalog(readIntAttr(soundNode, "delayAnalog", 0));
    }

    // audioCompressor
    Element compEl = getFirstChild(soundNode, "audioCompressor");
    if (compEl == null) {
      compEl = getFirstChild(soundNode, "compressor"); // tag fallback
    }
    if (compEl != null) {
      readAttrOrChildHexFloat(compEl, "attack", sound::setCompressorAttack);
      readAttrOrChildHexFloat(compEl, "release", sound::setCompressorRelease);
      readAttrOrChildInt(compEl, "syncLevel", 0, sound::setCompressorSyncLevel);
      readAttrOrChildHexFloat(compEl, "threshold", v -> sound.setCompressorThreshold(Math.abs(v)));
      readAttrOrChildHexFloat(compEl, "ratio", v -> sound.setCompressorRatio(Math.abs(v)));
      readAttrOrChildHexFloat(compEl, "blend", v -> sound.setCompressorBlend(Math.abs(v)));
      readAttrOrChildHexFloat(
          compEl, "sidechainHpf", v -> sound.setCompressorSidechainHpf(Math.abs(v)));
    } else {
      // Fallback: look up direct attributes on soundNode
      String attackStr = readAttr(soundNode, "compressorAttack");
      if (attackStr != null) sound.setCompressorAttack(DelugeHexMapper.hexToFloat(attackStr));
      String releaseStr = readAttr(soundNode, "compressorRelease");
      if (releaseStr != null) sound.setCompressorRelease(DelugeHexMapper.hexToFloat(releaseStr));
      sound.setCompressorSyncLevel(readIntAttr(soundNode, "compressorSyncLevel", 0));
      String thresholdStr = readAttr(soundNode, "compressorThreshold");
      if (thresholdStr != null)
        sound.setCompressorThreshold(Math.abs(DelugeHexMapper.hexToFloat(thresholdStr)));
      String ratioStr = readAttr(soundNode, "compressorRatio");
      if (ratioStr != null)
        sound.setCompressorRatio(Math.abs(DelugeHexMapper.hexToFloat(ratioStr)));
      String blendStr = readAttr(soundNode, "compressorBlend");
      if (blendStr != null)
        sound.setCompressorBlend(Math.abs(DelugeHexMapper.hexToFloat(blendStr)));
      String compHpfStr = readAttr(soundNode, "compressorSidechainHpf");
      if (compHpfStr != null)
        sound.setCompressorSidechainHpf(Math.abs(DelugeHexMapper.hexToFloat(compHpfStr)));
    }

    // ── Stutter config (quantized, reverse, pingPong) ──
    parseStutter(soundNode, sound);

    // sidechain (at sound level, separate from compressor)
    Element sidechainEl = getFirstChild(soundNode, "sidechain");
    if (sidechainEl != null) {
      String scAttack = readAttr(sidechainEl, "attack");
      if (scAttack != null)
        sound.setSidechainAttack(Math.abs(DelugeHexMapper.hexToFloat(scAttack)));
      String scRelease = readAttr(sidechainEl, "release");
      if (scRelease != null)
        sound.setSidechainRelease(Math.abs(DelugeHexMapper.hexToFloat(scRelease)));
      sound.setSidechainSyncLevel(readIntAttr(sidechainEl, "syncLevel", 0));
      sound.setSidechainSyncType(readIntAttr(sidechainEl, "syncType", 0));
    }

    // arpeggiator
    Element arpEl = getFirstChild(soundNode, "arpeggiator");
    if (arpEl != null) {
      sound.setArpeggiatorGate(DelugeHexMapper.hexToFloat(readAttr(arpEl, "gate")));
    }

    // modKnobs
    parseDrumModKnobs(soundNode, sound);

    // patchCables (direct child)
    parseDrumPatchCables(soundNode, sound);

    // defaultParams
    Element dp = getFirstChild(soundNode, "defaultParams");
    if (dp != null) {
      parseDrumDefaultParams(dp, sound);
    }

    return sound;
  }

  /** Parse kit sound LFO element. Uses attribute-style with child-element fallback. */
  private static void parseDrumLfo(Element soundNode, String lfoTag, Drum sound, boolean isLocal) {
    Element lfoEl = getFirstChild(soundNode, lfoTag);
    if (lfoEl == null) return;

    LfoType waveform = LfoType.SINE;
    float rateHz = 1.0f;
    float depth = 0.0f;
    int syncLevel = 0;

    String typeStr = readAttr(lfoEl, "type");
    if (typeStr == null) typeStr = getChildText(lfoEl, "type");
    if (typeStr != null) {
      waveform = parseLfoType(typeStr);
    }

    String rateStr = readAttr(lfoEl, "rate");
    if (rateStr != null) {
      rateHz = DelugeHexMapper.hexToLfoHz(rateStr);
    } else {
      String rateChild = getChildText(lfoEl, "rate");
      if (rateChild != null) rateHz = DelugeHexMapper.hexToLfoHz(rateChild);
    }

    String depthStr = readAttr(lfoEl, "depth");
    if (depthStr == null || depthStr.isBlank()) {
      depthStr = getChildText(lfoEl, "depth");
    }
    if (depthStr != null && !depthStr.isBlank()) {
      depth = toUnipolar(DelugeHexMapper.hexToFloat(depthStr));
    }

    // syncLevel: read attr first, fallback to child element
    String syncLevelStr = readAttr(lfoEl, "syncLevel");
    if (syncLevelStr != null && !syncLevelStr.isEmpty()) {
      try {
        syncLevel = Integer.parseInt(syncLevelStr);
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    } else {
      String syncChild = getChildText(lfoEl, "syncLevel");
      if (syncChild != null) {
        try {
          syncLevel = Integer.parseInt(syncChild);
        } catch (NumberFormatException e) {
          LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
        }
      }
    }

    // syncType: attribute first, child-element fallback
    String syncTypeStr = readAttr(lfoEl, "syncType");
    if (syncTypeStr == null || syncTypeStr.isBlank()) {
      syncTypeStr = getChildText(lfoEl, "syncType");
    }
    int syncType = 0;
    if (syncTypeStr != null && !syncTypeStr.isEmpty()) {
      try {
        syncType = Integer.parseInt(syncTypeStr);
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "NumberFormatException parsing XML attribute", e);
      }
    }

    LfoModel lfo = new LfoModel(rateHz, waveform, depth, "NONE", isLocal, syncLevel, syncType);
    if (lfoTag.equals("lfo1")) {
      sound.setLfo1(lfo);
    } else {
      sound.setLfo2(lfo);
    }
  }

  /** Parse defaultParams child element for kit sounds — ~25 hex attributes. */
  private static void parseDrumDefaultParams(Element dp, Drum sound) {
    readHexFloatUnipolar(dp, "oscAVolume", sound::setOscAVolume);
    readHexFloatUnipolar(dp, "oscBVolume", sound::setOscBVolume);
    readHexFloatUnipolar(dp, "noiseVolume", sound::setNoiseVolume);
    readHexFloatUnipolar(dp, "volume", sound::setVolume);
    readHexFloat(dp, "pan", sound::setPan);
    readHexHz(dp, "lpfFrequency", sound::setLpfFreq);
    readHexFloatUnipolar(dp, "lpfResonance", sound::setLpfRes);
    readHexHz(dp, "hpfFrequency", sound::setHpfFreq);
    readHexFloatUnipolar(dp, "hpfResonance", sound::setHpfRes);
    readHexFloatUnipolar(dp, "modulator1Amount", sound::setFmAmount);
    readHexFloatUnipolar(dp, "modulator1Feedback", v -> {});
    readHexFloatUnipolar(dp, "modulator2Amount", v -> {});
    readHexFloatUnipolar(dp, "modulator2Feedback", v -> {});
    readHexFloatUnipolar(dp, "carrier1Feedback", v -> {});
    readHexFloatUnipolar(dp, "carrier2Feedback", v -> {});
    readHexFloatUnipolar(dp, "modFXRate", sound::setModFxRate);
    readHexFloatUnipolar(dp, "modFXDepth", sound::setModFxDepth);
    readHexFloatUnipolar(dp, "modFXOffset", sound::setModFxOffset);
    readHexFloatUnipolar(dp, "modFXFeedback", sound::setModFxFeedback);
    readHexFloatUnipolar(dp, "delayRate", sound::setDelayRate);
    readHexFloatUnipolar(dp, "delayFeedback", sound::setDelayFeedback);
    readHexFloatUnipolar(dp, "reverbAmount", sound::setReverbAmount);
    readHexFloatUnipolar(dp, "arpeggiatorGate", sound::setArpeggiatorGate);
    readHexFloatUnipolar(dp, "portamento", sound::setPortamento);
    readHexFloatUnipolar(dp, "stutterRate", sound::setStutterRate);
    readHexFloatUnipolar(dp, "sampleRateReduction", sound::setSampleRateReduction);
    readHexFloatUnipolar(dp, "bitCrush", sound::setBitCrush);
    readHexFloatUnipolar(dp, "waveIndex", sound::setWaveIndex);

    // Envelopes 1-4 as child elements of defaultParams (child-element format)
    for (int i = 1; i <= 4; i++) {
      String envTag = "envelope" + i;
      Element envEl = getFirstChild(dp, envTag);
      if (envEl != null) {
        EnvelopeModel env = parseEnvelopeElement(envEl);
        if (i == 1) sound.setAdsr(env);
        else if (i == 2) sound.setEnv2(env);
        else if (i == 3) sound.setEnv3(env);
        else if (i == 4) sound.setEnv4(env);
      }
    }

    // Equalizer child element
    Element eqEl = getFirstChild(dp, "equalizer");
    if (eqEl != null) {
      readHexFloat(eqEl, "bass", sound::setEqBass);
      readHexFloat(eqEl, "treble", sound::setEqTreble);
    }

    // Patch cables inside defaultParams
    parseDrumCablesFromContainer(dp, sound);
  }

  /**
   * Parse modKnobs from sound element. Format: attribute-style on &lt;modKnobs&gt; children or
   * direct &lt;modKnob&gt; elements.
   */
  private static void parseDrumModKnobs(Element soundNode, Drum sound) {
    // Try direct &lt;modKnobs&gt; container
    Element mkContainer = getFirstChild(soundNode, "modKnobs");
    if (mkContainer != null) {
      NodeList knobList = mkContainer.getChildNodes();
      int idx = 0;
      for (int i = 0; i < knobList.getLength() && idx < 16; i++) {
        if (knobList.item(i) instanceof Element knobElem) {
          // child-element format:
          // &lt;modKnob&gt;&lt;controlsParam&gt;...&lt;/controlsParam&gt;&lt;/modKnob&gt;
          String param = getChildText(knobElem, "controlsParam");
          // attribute format: &lt;modKnob controlsParam="..." /&gt;
          if (param == null) param = knobElem.getAttribute("controlsParam");
          if (param != null && !param.isBlank()) {
            String patchSrc = getChildText(knobElem, "patchSource");
            if (patchSrc == null) patchSrc = getChildText(knobElem, "patchAmountFromSource");
            if (patchSrc == null) patchSrc = knobElem.getAttribute("patchSource");
            if (patchSrc == null || patchSrc.isBlank()) patchSrc = "NONE";
            sound.setModKnob(idx, new ModKnob(param.trim(), patchSrc.trim()));
            idx++;
          }
        }
      }
    } else {
      // Fallback: direct &lt;modKnob&gt; children of soundNode
      NodeList knobList = soundNode.getElementsByTagName("modKnob");
      for (int i = 0; i < knobList.getLength() && i < 16; i++) {
        Element knobElem = (Element) knobList.item(i);
        String param = getChildText(knobElem, "controlsParam");
        if (param != null && !param.isBlank()) {
          sound.setModKnob(i, new ModKnob(param.trim(), "NONE"));
        }
      }
    }
  }

  /**
   * Parse patch cables from a container element (direct child of sound or inside defaultParams).
   */
  private static void parseDrumCablesFromContainer(Element container, Drum sound) {
    // Try &lt;patchCables&gt; container
    Element pcContainer = getFirstChild(container, "patchCables");
    if (pcContainer != null) {
      NodeList cableList = pcContainer.getChildNodes();
      for (int i = 0; i < cableList.getLength(); i++) {
        if (cableList.item(i) instanceof Element cableElem) {
          String src = cableElem.getAttribute("source");
          if (src == null || src.isEmpty()) src = getChildText(cableElem, "source");
          String dst = cableElem.getAttribute("destination");
          if (dst == null || dst.isEmpty()) dst = getChildText(cableElem, "destination");
          String amtStr = cableElem.getAttribute("amount");
          if (amtStr == null || amtStr.isEmpty()) amtStr = getChildText(cableElem, "amount");
          if (src != null && dst != null && amtStr != null) {
            String polarityStr = cableElem.getAttribute("polarity");
            if (polarityStr == null || polarityStr.isEmpty()) {
              polarityStr = getChildText(cableElem, "polarity");
            }
            PatchCable.Polarity polarityVal = PatchCable.Polarity.BIPOLAR;
            if (polarityStr != null && !polarityStr.isEmpty()) {
              if ("unipolar".equalsIgnoreCase(polarityStr.trim())) {
                polarityVal = PatchCable.Polarity.UNIPOLAR;
              } else if ("bipolar".equalsIgnoreCase(polarityStr.trim())) {
                polarityVal = PatchCable.Polarity.BIPOLAR;
              }
            } else {
              if ("aftertouch".equalsIgnoreCase(src.trim())) {
                polarityVal = PatchCable.Polarity.UNIPOLAR;
              } else {
                polarityVal = PatchCable.Polarity.BIPOLAR;
              }
            }
            float amt = PatchCable.applyScaling(dst, DelugeHexMapper.hexToFloat(amtStr));
            sound.addPatchCable(new PatchCable(src.trim(), dst.trim(), amt, polarityVal));
          }
        }
      }
    }
  }

  /** Parse only direct-child &lt;patchCables&gt; container (not nested inside defaultParams). */
  private static void parseDrumPatchCables(Element soundNode, Drum sound) {
    // Only match direct children to avoid double-parsing (patchCables is also inside defaultParams)
    Element pcContainer = null;
    NodeList children = soundNode.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      if (children.item(i) instanceof Element el && "patchCables".equals(el.getTagName())) {
        pcContainer = el;
        break;
      }
    }
    if (pcContainer != null) {
      parseDrumCablesFromContainer(soundNode, sound);
      return;
    }
    // Fallback: direct &lt;patchCable&gt; children
    NodeList cableList = soundNode.getElementsByTagName("patchCable");
    for (int i = 0; i < cableList.getLength(); i++) {
      Element cableElem = (Element) cableList.item(i);
      // Only direct children, not nested ones
      if (cableElem.getParentNode() == soundNode || cableElem.getParentNode() == soundNode) {
        String src = getChildText(cableElem, "source");
        String dst = getChildText(cableElem, "destination");
        String amtStr = getChildText(cableElem, "amount");
        if (src != null && dst != null && amtStr != null) {
          String polarityStr = getChildText(cableElem, "polarity");
          PatchCable.Polarity polarityVal = PatchCable.Polarity.BIPOLAR;
          if (polarityStr != null && !polarityStr.isEmpty()) {
            if ("unipolar".equalsIgnoreCase(polarityStr.trim())) {
              polarityVal = PatchCable.Polarity.UNIPOLAR;
            } else if ("bipolar".equalsIgnoreCase(polarityStr.trim())) {
              polarityVal = PatchCable.Polarity.BIPOLAR;
            }
          } else {
            if ("aftertouch".equalsIgnoreCase(src.trim())) {
              polarityVal = PatchCable.Polarity.UNIPOLAR;
            } else {
              polarityVal = PatchCable.Polarity.BIPOLAR;
            }
          }
          float amt = PatchCable.applyScaling(dst, DelugeHexMapper.hexToFloat(amtStr));
          sound.addPatchCable(new PatchCable(src, dst, amt, polarityVal));
        }
      }
    }
  }

  /** Parse an envelope from a DOM element supporting both attribute and child-element formats. */
  private static EnvelopeModel parseEnvelopeElement(Element envEl) {
    // Deluge uses bipolar hex [-1, 1] for envelope parameters.
    // Time params (attack/decay/release): exponential mapping via hexToEnvTime.
    // Sustain: linear mapping via hexToSustain.
    float attack = readHexEnvTime(envEl, "attack", 0.01f);
    float decay = readHexEnvTime(envEl, "decay", 0.1f);
    float sustain = readHexSustain(envEl, "sustain", 0.7f);
    float release = readHexEnvTime(envEl, "release", 0.2f);
    return new EnvelopeModel(attack, decay, sustain, release, "NONE", 0.0f);
  }

  /** Read hex envelope time from a child element, returning seconds. */
  private static float readHexEnvTime(Element el, String tag, float def) {
    NodeList nodes = el.getElementsByTagName(tag);
    if (nodes.getLength() == 0) return def;
    Element child = (Element) nodes.item(0);
    String val = child.getAttribute("value");
    if (val == null || val.isEmpty()) val = child.getTextContent();
    if (val == null || val.isBlank()) return def;
    try {
      return DelugeHexMapper.hexToEnvTime(val.trim());
    } catch (Exception e) {
      return def;
    }
  }

  /** Read hex sustain from a child element, returning [0, 1]. */
  private static float readHexSustain(Element el, String tag, float def) {
    NodeList nodes = el.getElementsByTagName(tag);
    if (nodes.getLength() == 0) return def;
    Element child = (Element) nodes.item(0);
    String val = child.getAttribute("value");
    if (val == null || val.isEmpty()) val = child.getTextContent();
    if (val == null || val.isBlank()) return def;
    try {
      return DelugeHexMapper.hexToSustain(val.trim());
    } catch (Exception e) {
      return def;
    }
  }

  /** Parse LFO type string to enum. */
  private static LfoType parseLfoType(String type) {
    if (type == null) return LfoType.SINE;
    String clean =
        type.trim()
            .toUpperCase()
            .replace("&AMP;", "&")
            .replace("&amp;", "&")
            .replace("_", "")
            .replace(" ", "");
    return switch (clean) {
      case "SAW" -> LfoType.SAW;
      case "SQUARE" -> LfoType.SQUARE;
      case "TRIANGLE" -> LfoType.TRIANGLE;
      case "S_AND_H", "SAMPLEANDHOLD", "S&H" -> LfoType.S_AND_H;
      case "RANDOM_WALK", "RANDOMWALK", "RANDOM" -> LfoType.RANDOM_WALK;
      case "WARBLER" -> LfoType.WARBLER;
      case "CUSTOM" -> LfoType.CUSTOM;
      default -> LfoType.SINE;
    };
  }

  // ── Drum helper methods ──

  /** Safely get the first child element with the given tag name. */
  private static Element getFirstChild(Element parent, String tag) {
    NodeList nodes = parent.getElementsByTagName(tag);
    return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
  }

  /** Read integer from a child element's attribute or text content, returning default if absent. */
  private static int getChildInteger(Element parent, String tag, int def) {
    NodeList nodes = parent.getElementsByTagName(tag);
    if (nodes.getLength() == 0) return def;
    Element child = (Element) nodes.item(0);
    String val = child.getAttribute("value");
    if (val == null || val.isEmpty()) val = child.getTextContent();
    if (val != null && !val.isBlank()) {
      try {
        return Integer.parseInt(val.trim());
      } catch (NumberFormatException e) {
        return def;
      }
    }
    return def;
  }

  /** Read an attribute value, returning null if absent. */
  private static String readAttr(Element el, String attr) {
    return el.hasAttribute(attr) ? el.getAttribute(attr).trim() : null;
  }

  private static float toUnipolar(float f) {
    return (f + 1.0f) / 2.0f;
  }

  /** Read a hex float attribute and apply via setter (unipolar scaling). */
  private static void readAttrFloatHex(
      Element el, String attr, java.util.function.Consumer<Float> setter, boolean unipolar) {
    String val = readAttr(el, attr);
    if (val != null && !val.isEmpty()) {
      float f = DelugeHexMapper.hexToFloat(val);
      if (unipolar) f = toUnipolar(f);
      setter.accept(f);
    }
  }

  /** Read a boolean attribute. */
  private static void readAttrBool(
      Element el, String attr, java.util.function.Consumer<Boolean> setter) {
    String val = readAttr(el, attr);
    if (val != null) {
      setter.accept("true".equalsIgnoreCase(val) || "1".equals(val));
    }
  }

  /** Parse <stutter> sub-element with quantized/reverse/pingPong attributes. */
  private static void parseStutter(Element soundNode, SynthTrackModel synth) {
    NodeList nodes = soundNode.getElementsByTagName("stutter");
    if (nodes.getLength() == 0) return;
    Element stut = (Element) nodes.item(0);
    readAttrBool(stut, "quantized", synth::setStutterQuantized);
    readAttrBool(stut, "reverse", synth::setStutterReversed);
    readAttrBool(stut, "pingPong", synth::setStutterPingPong);
  }

  /** Parse <stutter> sub-element for a SoundDrum. */
  private static void parseStutter(Element soundNode, SoundDrum sound) {
    NodeList nodes = soundNode.getElementsByTagName("stutter");
    if (nodes.getLength() == 0) return;
    Element stut = (Element) nodes.item(0);
    readAttrBool(stut, "quantized", sound::setStutterQuantized);
    readAttrBool(stut, "reverse", sound::setStutterReversed);
    readAttrBool(stut, "pingPong", sound::setStutterPingPong);
  }

  private static ClipModel.PlayDirection readPlayDirectionAttr(Element el) {
    String directionAttr = el.getAttribute("sequenceDirection");
    if (directionAttr.isEmpty()) {
      directionAttr = el.getAttribute("sequenceDirectionMode");
    }
    if (!directionAttr.isEmpty()) {
      try {
        String clean = directionAttr.toUpperCase().replace("_", "");
        if ("PINGPONG".equals(clean)) {
          return ClipModel.PlayDirection.PING_PONG;
        } else {
          return ClipModel.PlayDirection.valueOf(directionAttr.toUpperCase());
        }
      } catch (IllegalArgumentException iae) {
        // Fallback to integer values (0=FORWARD, 1=REVERSE, 2=PINGPONG, 3=RANDOM)
        try {
          int val = Integer.parseInt(directionAttr);
          return switch (val) {
            case 1 -> ClipModel.PlayDirection.REVERSE;
            case 2 -> ClipModel.PlayDirection.PING_PONG;
            case 3 -> ClipModel.PlayDirection.RANDOM;
            default -> ClipModel.PlayDirection.FORWARD;
          };
        } catch (NumberFormatException nfe) {
          return ClipModel.PlayDirection.FORWARD;
        }
      }
    }
    return ClipModel.PlayDirection.FORWARD;
  }

  /** Read an int attribute with default. */
  private static int readIntAttr(Element el, String attr, int def) {
    String val = readAttr(el, attr);
    if (val != null && !val.isEmpty()) {
      try {
        return Integer.parseInt(val);
      } catch (NumberFormatException e) {
        return def;
      }
    }
    return def;
  }

  /** Read a float attribute with default (hex-encoded if hex=true, plain float otherwise). */
  private static float readAttrFloatWithDefault(Element el, String attr, float def, boolean hex) {
    String val = readAttr(el, attr);
    if (val != null && !val.isEmpty()) {
      try {
        return hex ? Math.abs(DelugeHexMapper.hexToFloat(val)) : Float.parseFloat(val);
      } catch (NumberFormatException e) {
        return def;
      }
    }
    return def;
  }

  /** Read an int attribute with default (plain integer). */
  private static int readIntAttrWithDefault(Element el, String attr, int def) {
    String val = readAttr(el, attr);
    if (val != null && !val.isEmpty()) {
      try {
        return Integer.parseInt(val);
      } catch (NumberFormatException e) {
        return def;
      }
    }
    return def;
  }

  /** Read a string attribute and trim it. */
  private static void readAttrString(
      Element el, String attr, java.util.function.Consumer<String> setter) {
    String val = readAttr(el, attr);
    if (val != null && !val.isEmpty()) {
      setter.accept(val);
    }
  }

  /** Read a string attribute or child element and trim it. */
  private static void readAttrOrChildString(
      Element el, String tagOrAttr, java.util.function.Consumer<String> setter) {
    String val = el.getAttribute(tagOrAttr);
    if (val == null || val.isEmpty()) {
      val = getChildText(el, tagOrAttr);
    }
    if (val != null && !val.isEmpty()) {
      setter.accept(val.trim());
    }
  }

  private static void readAttrOrChildHexFloat(
      Element el, String name, java.util.function.Consumer<Float> setter) {
    String val = el.getAttribute(name);
    if (val == null || val.isEmpty()) {
      if ("threshold".equals(name)) {
        val = el.getAttribute("thresh");
      }
    }
    if (val != null && !val.isEmpty()) {
      setter.accept(DelugeHexMapper.hexToFloat(val.trim()));
      return;
    }
    NodeList nodes = el.getElementsByTagName(name);
    if (nodes.getLength() == 0 && "threshold".equals(name)) {
      nodes = el.getElementsByTagName("thresh");
    }
    if (nodes.getLength() > 0) {
      Element child = (Element) nodes.item(0);
      String childVal = child.getAttribute("value");
      if (childVal == null || childVal.isEmpty()) {
        childVal = child.getTextContent();
      }
      if (childVal != null && !childVal.isBlank()) {
        setter.accept(DelugeHexMapper.hexToFloat(childVal.trim()));
      }
    }
  }

  private static void readAttrOrChildInt(
      Element el, String name, int defaultValue, java.util.function.Consumer<Integer> setter) {
    String val = el.getAttribute(name);
    if (val != null && !val.isEmpty()) {
      try {
        setter.accept(Integer.parseInt(val.trim()));
        return;
      } catch (NumberFormatException e) {
        // fall through
      }
    }
    NodeList nodes = el.getElementsByTagName(name);
    if (nodes.getLength() > 0) {
      String childVal = nodes.item(0).getTextContent();
      if (childVal != null && !childVal.isBlank()) {
        try {
          setter.accept(Integer.parseInt(childVal.trim()));
          return;
        } catch (NumberFormatException e) {
          // fall through
        }
      }
    }
    setter.accept(defaultValue);
  }

  /** Read a hex float from a child element's attribute (attribute-style), apply with Math.abs. */
  private static void readHexFloat(
      Element parent, String tag, java.util.function.Consumer<Float> setter) {
    NodeList nodes = parent.getElementsByTagName(tag);
    if (nodes.getLength() == 0) return;
    Element child = (Element) nodes.item(0);
    // Try attribute first (SONG006668.XML style)
    String val = child.getAttribute("value");
    if (val == null || val.isEmpty()) {
      // Try as child element hex text content
      val = child.getTextContent();
    }
    if (val != null && !val.isBlank()) {
      float f = DelugeHexMapper.hexToFloat(val.trim());
      setter.accept(f);
    }
  }

  /** Read a unipolar hex float from a child element's attribute, apply to setter. */
  private static void readHexFloatUnipolar(
      Element parent, String tag, java.util.function.Consumer<Float> setter) {
    NodeList nodes = parent.getElementsByTagName(tag);
    if (nodes.getLength() == 0) return;
    Element child = (Element) nodes.item(0);
    String val = child.getAttribute("value");
    if (val == null || val.isEmpty()) {
      val = child.getTextContent();
    }
    if (val != null && !val.isBlank()) {
      float f = toUnipolar(DelugeHexMapper.hexToFloat(val.trim()));
      setter.accept(f);
    }
  }

  /** Read a hex Hz value from a child element's attribute. */
  private static void readHexHz(
      Element parent, String tag, java.util.function.Consumer<Float> setter) {
    NodeList nodes = parent.getElementsByTagName(tag);
    if (nodes.getLength() == 0) return;
    Element child = (Element) nodes.item(0);
    String val = child.getAttribute("value");
    if (val == null || val.isEmpty()) {
      val = child.getTextContent();
    }
    if (val != null && !val.isBlank()) {
      setter.accept(DelugeHexMapper.hexToHz(val.trim()));
    }
  }

  /**
   * Read a hex float from an element, returning default on failure. Supports both attribute and
   * text content.
   */
  private static float readHexFloatVal(Element el, String tag, float def) {
    NodeList nodes = el.getElementsByTagName(tag);
    if (nodes.getLength() == 0) return def;
    Element child = (Element) nodes.item(0);
    String val = child.getAttribute("value");
    if (val == null || val.isEmpty()) val = child.getTextContent();
    if (val != null && !val.isBlank()) {
      try {
        return toUnipolar(DelugeHexMapper.hexToFloat(val.trim()));
      } catch (Exception e) {
        return def;
      }
    }
    return def;
  }

  // ── Helpers ──

  /**
   * Gets the text content of the first child element with {@code tag} under {@code parent}, or null
   * if absent/blank.
   */
  private static String getChildText(Element parent, String tag) {
    NodeList nodes = parent.getElementsByTagName(tag);
    if (nodes.getLength() > 0) {
      String text = nodes.item(0).getTextContent();
      return (text == null || text.isBlank()) ? null : text.trim();
    }
    return null;
  }

  /**
   * Parses automation data from a {@code <sound>} element and stores it into the given ClipModel.
   * Expected XML structure:
   *
   * <pre>{@code
   * <automation>
   *   <param name="lpfFrequency">
   *     <step index="0">0.5</step>
   *     <step index="4">0.8</step>
   *   </param>
   * </automation>
   * }</pre>
   */
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

  /**
   * Parse per-noteRow &lt;soundParams&gt; elements containing 35+ hex parameter overrides. Stores
   * values in {@code ClipModel.rowSoundParams} for each row index. These are normalized float
   * values (0.0-1.0) from hex strings, used to override per-sound default parameters for a specific
   * pattern row.
   */
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

  // ────────────────────────────────────────────────────────────────
  // Phase 4: Song-level FX parsing
  // ────────────────────────────────────────────────────────────────

  /** Parse top-level song FX elements: reverb, delay, sidechain, compressor, songParams. */
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
      if (spEq.getLength() > 0) {
        Element eq = (Element) spEq.item(0);
        readSongHexAttr(eq, "bass", project::setSongParamEqBass, false);
        readSongHexAttr(eq, "treble", project::setSongParamEqTreble, false);
        readSongHexAttr(eq, "bassFrequency", project::setSongParamEqBassFrequency, true);
        readSongHexAttr(eq, "trebleFrequency", project::setSongParamEqTrebleFrequency, true);
      }
    }
  }

  /** Parse <sections> element. */
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

  /** Parse <scales> element. */
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

  // ── Song FX helper: read raw int attribute → normalize to float via hexToFloat ──
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

  /** Read a raw integer attribute value (may be negative), store as float. */
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

  /** Read a 0x-prefixed hex attribute from a songParams child. */
  private static void readSongHexAttr(
      Element el, String attr, java.util.function.Consumer<Float> setter) {
    readSongHexAttr(el, attr, setter, true);
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

  /** Read a 0x-prefixed hex attribute and map to Hz range. */
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

  /**
   * Parse &lt;kitParams&gt; element (mirrors &lt;songParams&gt; but stores values in a Map on
   * ClipModel).
   */
  private static void parseKitParamsElement(Element kp, ClipModel clip) {
    parseParamsAsKitParams(kp, clip);
  }

  /**
   * Parse a &lt;params&gt; or &lt;kitParams&gt; element into ClipModel.kitParams. Handles the same
   * hex-attribute set as the real firmware's GlobalEffectable serialisation.
   */
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

  /** Parse child elements (delay, lpf, hpf, equalizer) of a kitParams/params element. */
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
    if (eq.getLength() > 0) {
      Element eqEl = (Element) eq.item(0);
      readKitHexAttr(eqEl, "bass", clip, "eqBass");
      readKitHexAttr(eqEl, "treble", clip, "eqTreble");
      readKitHexAttr(eqEl, "bassFrequency", clip, "eqBassFrequency");
      readKitHexAttr(eqEl, "trebleFrequency", clip, "eqTrebleFrequency");
    }
  }

  /**
   * Read a 0x-prefixed hex attribute and store as normalized float in ClipModel's kitParams map.
   */
  private static void readKitHexAttr(Element el, String attr, ClipModel clip, String paramName) {
    if (!el.hasAttribute(attr)) return;
    String val = el.getAttribute(attr).trim();
    if (val.isEmpty() || !val.startsWith("0x")) return;
    try {
      clip.setKitParam(paramName, Math.abs(DelugeHexMapper.hexToFloat(val)));
    } catch (Exception e) {
    }
  }

  private static Document parseXml(InputStream is) throws Exception {
    byte[] bytes = is.readAllBytes();
    String content = new String(bytes, StandardCharsets.UTF_8);

    // 1. Remove XML declaration
    content = content.replaceFirst("<\\?xml.*?\\?>", "");

    // 2. Escape ALL ampersands.
    content = content.replace("&", "&amp;");

    // 3. Wrap in virtual root to handle multiple top-level elements
    String wrapped = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root>\n" + content + "\n</root>";

    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setValidating(false);
    factory.setNamespaceAware(true);
    DocumentBuilder builder = factory.newDocumentBuilder();
    // Re-read from wrapped string
    return builder.parse(new ByteArrayInputStream(wrapped.getBytes(StandardCharsets.UTF_8)));
  }
}
