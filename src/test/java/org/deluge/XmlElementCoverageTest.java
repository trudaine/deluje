package org.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Coverage guard: NO XML element or attribute that appears in any Deluge XML file may be silently
 * ignored by our parser. "Handled" is derived at test time from the parser source itself (every
 * quoted identifier in {@code org/deluge/xml/*.java}), so it auto-updates as we add parsing — the
 * moment you reference {@code "oscAPulseWidth"} in the parser it moves out of the gap list.
 *
 * <p>Every name in the corpus must be EITHER handled, OR in {@link #INTENTIONALLY_IGNORED}
 * (cosmetic / metadata / preserved-verbatim, justified inline), OR in {@link #KNOWN_GAPS} (audited
 * bugs we haven't fixed yet — the visible TODO). The test FAILS on any uncategorised name (a new
 * silent ignore) and on any KNOWN_GAP that's been fixed-but-not-removed, forcing the list to stay
 * honest.
 *
 * <p>Background: docs/ARRANGEMENT_XML_AUDIT.md + the sampleRange/oscPulseWidth misses.
 */
public class XmlElementCoverageTest {

  // Cosmetic/metadata/verbatim names we consciously do NOT model. Keep each justified.
  private static final Set<String> INTENTIONALLY_IGNORED =
      Set.of(
          // file metadata
          "firmwareVersion",
          "earliestCompatibleFirmware",
          "previewNumPads",
          "preview",
          "presetFolder",
          "presetSubSlot",
          "path",
          "encoding",
          "version",
          "standalone",
          "relative",
          // multisample keyzones — preserved verbatim via raw osc capture (not re-modelled)
          "sampleRanges",
          "rangeTopNote",
          "rangeAdjustable",
          "zone",
          "startSamplePos",
          "endSamplePos",
          // present in some (old/community) XML but the C firmware does NOT read these (verified:
          // 0 matches in DelugeFirmware), so ignoring them is C-faithful.
          "launchGroup",
          "phaseShift");

  // Audited names the C reads but our parser does NOT yet act on. Shrinks as we fix them.
  // (docs: the XML coverage audit.) Anything here is a known, tracked bug — not a silent ignore.
  private static final Set<String> KNOWN_GAPS =
      new TreeSet<>(
          Set.of(
              // 🔴 sound-affecting. FIXED+verified: pitchAdjust (overall), oscA/BPulseWidth,
              // oscA/BPitchAdjust.
              // oscA/BPulseWidth fix: ported the bit-faithful band-limited pulse renderer
              // (renderPulseWave, the two-read polarity-flipped product from
              // waveRenderingFunctionPulse)              // duty now tracks pulseWidth
              // (SquarePwmRenderTest:
              // 50%→24%→12%→3%). oscA/BPitchAdjust are now fully parsed and round-tripped!
              "filterType",
              "filterSlope",
              "lpfHpfOrder",
              "distortionAmount",
              "distortionType",
              "noiseLevel",
              "oscLevel1",
              "oscLevel2",
              "oscLevel",
              "amplitudes",
              "isPolyphonic",
              "soundGroupMode",
              "filters",
              "eq",
              "pitch",
              "time",
              // 🟠 note / clip / arp / kit data
              "noteDataWithSplitProb",
              "defaultVelocity",
              "sequenceLength",
              "notePattern",
              "noteForDrum",
              "chordType",
              "arpeggiatorRate",
              "kitArp",
              "numOctaves",
              "randomizerLock",
              "columnControls",
              "midiKnob",
              "midiKnobs",
              "midiOutput",
              "selectedDrumIndex",
              "soundSources",
              "leftCol",
              "rightCol",
              "ccNumber",
              "channel",
              "clipName",
              "shape",
              // arp randomizer locks
              "lastLockedBassProb",
              "lastLockedChordProb",
              "lastLockedGateSpread",
              "lastLockedGlideProb",
              "lastLockedNoteProb",
              "lastLockedOctaveSpread",
              "lastLockedRatchetProb",
              "lastLockedReverseProb",
              "lastLockedSwapProb",
              "lastLockedVelocitySpread",
              "lockedBassProbArray",
              "lockedChordProbArray",
              "lockedGateSpreadArray",
              "lockedGlideProbArray",
              "lockedNoteProbArray",
              "lockedOctaveSpreadArray",
              "lockedRatchetProbArray",
              "lockedReverseProbArray",
              "lockedSwapProbArray",
              "lockedVelocitySpreadArray"));

  @Test
  void noXmlElementIsSilentlyIgnored() throws IOException {
    Set<String> handled = handledNamesFromParserSource();
    Set<String> corpus = namesInCorpus();

    // Anything in the corpus that we neither handle nor have categorised is an UNREVIEWED silent
    // ignore — the exact failure mode that let sampleRange/oscPulseWidth through.
    Set<String> uncategorised = new TreeSet<>(corpus);
    uncategorised.removeAll(handled);
    uncategorised.removeAll(INTENTIONALLY_IGNORED);
    uncategorised.removeAll(KNOWN_GAPS);

    System.out.printf(
        "[XmlCoverage] corpus=%d names, handled=%d, intentionallyIgnored=%d, knownGaps=%d%n",
        corpus.size(), handled.size(), INTENTIONALLY_IGNORED.size(), KNOWN_GAPS.size());

    // A KNOWN_GAP that's now handled must be removed from the list (keeps it honest/shrinking).
    Set<String> staleGaps = new TreeSet<>(KNOWN_GAPS);
    staleGaps.retainAll(handled);
    assertTrue(
        staleGaps.isEmpty(),
        "These KNOWN_GAPS are now handled — remove them from KNOWN_GAPS: " + staleGaps);

    assertTrue(
        uncategorised.isEmpty(),
        "NEW XML names neither handled, ignored, nor tracked as gaps (silent-ignore risk!): "
            + uncategorised
            + "\n→ add real parsing (then it auto-leaves the list), or categorise into"
            + " INTENTIONALLY_IGNORED / KNOWN_GAPS with justification.");
  }

  /** Every quoted identifier referenced in the parser source = the names the parser can act on. */
  private static Set<String> handledNamesFromParserSource() throws IOException {
    Path dir = Paths.get("src/main/java/org/deluge/xml");
    if (!Files.isDirectory(dir)) dir = Paths.get("../deluge/src/main/java/org/deluge/xml");
    assertTrue(Files.isDirectory(dir), "parser source dir not found: " + dir.toAbsolutePath());
    Set<String> names = new HashSet<>();
    Pattern p = Pattern.compile("\"([a-zA-Z][a-zA-Z0-9]*)\"");
    try (Stream<Path> s = Files.walk(dir)) {
      for (Path f : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".java"))::iterator) {
        Matcher m = p.matcher(Files.readString(f));
        while (m.find()) names.add(m.group(1));
      }
    }
    return names;
  }

  /** All distinct element + attribute names across the repo corpus (+ the SD card if mounted). */
  private static Set<String> namesInCorpus() throws IOException {
    List<Path> roots = new ArrayList<>();
    for (String r :
        new String[] {
          "src/main/resources/SYNTHS", "src/main/resources/SONGS", "src/main/resources/KITS"
        }) {
      Path p = Paths.get(r);
      if (!Files.isDirectory(p)) p = Paths.get("../deluge/" + r);
      if (Files.isDirectory(p)) roots.add(p);
    }
    // Include the real card when present locally — that's where the rare/older presets live.
    for (String r : new String[] {"SYNTHS", "SONGS", "KITS", "DX7"}) {
      Path p = Paths.get(System.getProperty("user.home"), "ludocard", r);
      if (Files.isDirectory(p)) roots.add(p);
    }
    assertFalse(roots.isEmpty(), "no XML corpus found");

    Set<String> names = new TreeSet<>();
    Pattern elem = Pattern.compile("<([a-zA-Z][a-zA-Z0-9]*)");
    Pattern attr = Pattern.compile("([a-zA-Z][a-zA-Z0-9]*)=\"");
    for (Path root : roots) {
      try (Stream<Path> s = Files.walk(root)) {
        for (Path f :
            (Iterable<Path>) s.filter(x -> x.toString().toUpperCase().endsWith(".XML"))::iterator) {
          String xml = Files.readString(f);
          Matcher me = elem.matcher(xml);
          while (me.find()) names.add(me.group(1));
          Matcher ma = attr.matcher(xml);
          while (ma.find()) names.add(ma.group(1));
        }
      }
    }
    return names;
  }
}
