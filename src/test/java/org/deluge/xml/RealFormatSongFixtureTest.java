package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Parses a REAL hardware-saved song (SONGS/Dx7A.xml, written by community firmware c1.2.0) and
 * asserts specific values landed in the model — not just "produces sound". This is the regression
 * guard for the 2026-06-12 hardware-comparison finding: the clip's {@code <soundParams>} (where the
 * song format keeps ALL sound param values) was ignored, so every real-format song played
 * instrument defaults (filter open, instant attack, no LFO cables, default FM depth).
 */
public class RealFormatSongFixtureTest {

  private static SynthTrackModel firstSynth(String song) throws Exception {
    ProjectModel p = DelugeXmlParser.parseSong(new File("src/main/resources/SONGS/" + song));
    for (var t : p.getTracks()) {
      if (t instanceof SynthTrackModel s) {
        return s;
      }
    }
    fail("no synth track parsed from " + song);
    return null;
  }

  @Test
  void dx7aClipSoundParamsReachTheModel() throws Exception {
    SynthTrackModel s = firstSynth("Dx7A.xml");

    // Instrument structure ("000 Rich Saw Bass"): unison num=4 detune=10, osc1 saw.
    assertEquals(4, s.getUnisonNum(), "unison num from instrument element");
    assertEquals(10, (int) s.getUnisonDetune(), "unison detune");
    assertEquals("SAW", s.getOsc1Type());

    // Clip soundParams statics: lpfFrequency="0x10000000" → default-synth cutoff in Hz
    // (hexToHz of the knob — NOT fully open, which is what the old defaults gave).
    assertTrue(
        s.getLpfFreq() < 15000f,
        "LPF cutoff must come from clip soundParams; got " + s.getLpfFreq());

    // Envelope1 from soundParams: attack="0x80000000" (instant) release="0x851EB851" — the raw
    // rate knobs must be captured for the firmware-faithful curves.
    assertTrue(s.isEnvKnobSet(0), "env1 rate knobs from clip soundParams");
    assertEquals(0x80000000, s.getEnvAttackKnobQ31(0));
    assertEquals(0x851EB851, s.getEnvReleaseKnobQ31(0));

    // Patch cables from soundParams: the Rich Saw Bass clip has 6 (velocity/aftertouch/note/
    // velocity/envelope2/y).
    assertEquals(6, s.getPatchCables().size(), "clip soundParams patch cables");

    // LFO1 rate knob from soundParams (lfo1Rate="0x1999997E").
    assertEquals(0x1999997E, s.getLfoRateKnobQ31(0), "lfo1 rate knob");
  }

  @Test
  void filterKnobsStoredAsRawQ31() throws Exception {
    // soundParams filter values are raw Q31 knobs; the firmware reads them verbatim. The minimum
    // (0x80000000 = INT_MIN) must survive intact — the preset float round-trip floored it at -2^29,
    // turning min resonance into a moderate one that distorted clean tones.
    SynthTrackModel s = firstSynth("TestTuningFidelity.xml");
    java.util.Map<Integer, Integer> raw = s.getRawParamKnobs();
    assertEquals(
        Integer.MIN_VALUE,
        (int) raw.get(org.deluge.firmware2.Param.LOCAL_LPF_RESONANCE),
        "min resonance must stay INT_MIN (raw knob), not the float path's -2^29");
    assertEquals(
        Integer.MIN_VALUE,
        (int) raw.get(org.deluge.firmware2.Param.LOCAL_LPF_MORPH),
        "min morph must stay INT_MIN");
  }

  @Test
  void lfoFidelitySongCableReachesTheModel() throws Exception {
    SynthTrackModel s = firstSynth("TestLfoFidelity.xml");
    boolean found =
        s.getPatchCables().stream()
            .anyMatch(
                c ->
                    "lfo1".equalsIgnoreCase(c.source())
                        && "pitch".equalsIgnoreCase(c.destination()));
    assertTrue(found, "lfo1→pitch cable from clip soundParams");
  }
}
