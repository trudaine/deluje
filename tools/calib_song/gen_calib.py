#!/usr/bin/env python3
"""Generate the CALIB calibration songs, presets and wavetables.

WHY THIS EXISTS
---------------
`FidelityScorecardTest` renders the ALLSYN songs, and a census of that corpus
(docs/FIDELITY_GAP_ANALYSIS.md §4.2duosexagies) showed it exercises far less of the engine than
its 188 presets suggest:

    delay      0/188      modFX     0/188      noise   0/188      wavetable osc  0/188
    HPF        0 audible (all 188 carry the inert hpfMode="12dB")
    register   C4 only

Those subsystems are not "passing" — they are **unmeasured**. Two DSP bugs fixed in July 2026 lived
in exactly these blind spots, which is why neither fix moved the scorecard median. This tool builds
a second calibration corpus that covers them, in the same shape the scorecard already knows how to
slice: N sounds, one note each, played sequentially in the arrangement.

DESIGN RULES
------------
* **One variable at a time.** Every case is a fixed BASE voice with a single knob moved, so a
  hardware-vs-us difference points at one subsystem. Interaction cases are separate and labelled.
* **Skeleton copied from a hardware-written song.** Song attributes, sections, reverb/delay/
  sidechain/songParams tails, LFO/arp/defaultParams/modKnobs blocks are lifted verbatim from a real
  ALLSYN file (template_blocks.py) so the Deluge accepts the file. Only the parts a case varies are
  synthesised.
* **Explicit params.** Under the >=1.2.0 clip semantics (§4.2septies) a clip is a fresh initParams
  ParamManager plus ONLY the tags it lists — no instrument back-fill, no patch cables. So every
  param a case depends on is written explicitly into the clip's <soundParams>; nothing is inherited.
* **Same arrangement geometry as ALLSYN**: clip length 768 ticks, one clip instance per sound at
  pos = i * 1152, i.e. a 2 s note and a 1 s gap at 120 BPM. Onset detection in the scorecard relies
  on this spacing.
* **A manifest.** Every case is emitted to calib_manifest.csv with its subsystem and the exact value
  of every varied knob, so per-synth scores can be joined back to "what was being tested".

USAGE
    python3 tools/calib_song/gen_calib.py --out /path/to/staging
    # then copy staging/SONGS/*.XML, staging/SYNTHS/*.XML, staging/SAMPLES/... onto the SD card

Recording: see tools/calib_song/README.md.
"""

import argparse
import csv
import math
import os
import struct
import wave

from template_blocks import (
    ARPEGGIATOR,
    CUSTOMLFOWAVE,
    DEFAULTPARAMS,
    LFO1,
    MODKNOBS,
    SONG_OPEN,
    TAIL,
)

# ── Arrangement geometry (must match ALLSYN so the scorecard's onset detection works) ──
CLIP_LEN = 768          # ticks; 2 s at 120 BPM
CLIP_STEP = 1152        # ticks between successive sounds; 3 s => 1 s of silence between notes
SOUNDS_PER_SONG = 94    # same as ALLSYN_1/2; keeps each recording around 4.7 minutes

# The note blob ALLSYN uses: pos=0, length=768, velocity=0x7F, lift=0x40, prob=0x14.
NOTE_LIFT = "0x00000000000003007F4014"
NOTE_SPLIT = "0x00000000000003007F4014000000"

# MIDI note numbers. The whole existing corpus is C4 (and the *_C5 suite is C5); C1/C2 are entirely
# unmeasured, and that is where the crude (tableNumber<6) oscillator paths live.
C1, C2, C3, C4, C5 = 24, 36, 48, 60, 72


def q31(frac: float) -> str:
    """Fraction in [0,1] -> the firmware's hex param encoding.

    Params are int32 where 0x80000000 (-2^31) is the minimum / "off" and 0x7FFFFFFF is the maximum.
    The anchors below are the exact values the Deluge itself writes for 0/25/50/75/100%, so
    generated files are byte-comparable with hardware-written ones.
    """
    anchors = {0.0: "0x80000000", 0.25: "0xC0000000", 0.5: "0x00000000",
               0.75: "0x40000000", 1.0: "0x7FFFFFFF"}
    if frac in anchors:
        return anchors[frac]
    v = int(round(-2147483648 + frac * 4294967295.0))
    v = max(-2147483648, min(2147483647, v))
    return "0x%08X" % (v & 0xFFFFFFFF)


def q31freq(frac: float) -> str:
    """Fraction in [0,1] -> a filter CUTOFF value, which must be NON-NEGATIVE.

    Filter frequencies are not full-range q31. `Filter::curveFrequency` (filter.h:128-136) feeds
    `instantTan`, which indexes `tanTable` with `input >> 25` — an arithmetic shift, so a negative
    frequency is a negative index and the firmware reads out of bounds. Real presets only ever carry
    non-negative cutoffs (ALLSYN uses e.g. lpfFrequency="0x10000000"/"0x50000000"), with
    0x80000000 acting as the "off" sentinel that doHPF filters out before the filter is configured.

    Using the plain q31() mapping here was a bug: q31(0.25) is 0xC0000000, i.e. NEGATIVE, so those
    cases measured undefined firmware behaviour rather than the filter.
    """
    v = int(round(frac * 2147483647.0))
    return "0x%08X" % max(0, min(2147483647, v))


OFF = q31(0.0)
MAX = q31(1.0)

# Full patched/unpatched param attribute set, in the order Sound::writeParamsToFile emits it
# (sound.cpp:4032-4100). Writing all of them makes each case self-contained under the clip
# semantics above, instead of silently depending on initParams defaults.
PARAM_DEFAULTS = [
    ("portamento", OFF), ("compressorShape", q31(0.5)),
    ("oscAVolume", MAX), ("oscAPulseWidth", q31(0.5)), ("oscAWavetablePosition", q31(0.5)),
    ("oscBVolume", OFF), ("oscBPulseWidth", q31(0.5)), ("oscBWavetablePosition", q31(0.5)),
    ("noiseVolume", OFF),
    ("volume", q31(0.75)), ("pan", q31(0.5)),
    ("lpfFrequency", MAX), ("lpfResonance", OFF),
    ("hpfFrequency", OFF), ("hpfResonance", OFF),
    ("lfo1Rate", q31(0.5)), ("lfo2Rate", q31(0.5)),
    ("lfo3Rate", q31(0.5)), ("lfo4Rate", q31(0.5)),
    ("modulator1Amount", OFF), ("modulator1Feedback", OFF),
    ("modulator2Amount", OFF), ("modulator2Feedback", OFF),
    ("carrier1Feedback", OFF), ("carrier2Feedback", OFF),
    ("pitchAdjust", q31(0.5)), ("oscAPitchAdjust", q31(0.5)), ("oscBPitchAdjust", q31(0.5)),
    ("mod1PitchAdjust", q31(0.5)), ("mod2PitchAdjust", q31(0.5)),
    ("modFXRate", OFF), ("modFXDepth", OFF),
    ("delayRate", q31(0.5)), ("delayFeedback", OFF),
    ("reverbAmount", OFF),
    ("arpeggiatorRate", q31(0.5)),
    ("stutterRate", q31(0.5)), ("sampleRateReduction", OFF), ("bitCrush", OFF),
    # modFXOffset is the chorus/dimension BASE DELAY, not a trim: at its minimum
    # `multiply_32x32_rshift32(kModFXMaxDelay, (offset >> 1) + 1073741824)` evaluates to ZERO
    # (ModFXProcessor.cpp:86-92), so thisModFXDelayDepth = offset * depth = 0 and the effect is
    # bypassed no matter what depth says. Leaving it at OFF silently disabled every chorus,
    # StereoChorus and dimension case in the first cut of this corpus. Default to mid.
    ("modFXOffset", q31(0.5)), ("modFXFeedback", q31(0.5)),
    ("lpfMorph", OFF), ("hpfMorph", OFF), ("waveFold", OFF),
]


class Case:
    """One calibration sound: a BASE voice with a labelled set of overrides."""

    def __init__(self, group, name, note=C4, osc1="saw", osc2="square",
                 lpf_mode="24dB", hpf_mode="12dB", mod_fx="none", clipping=0,
                 unison=1, osc1_file=None, delay_pingpong=0, delay_analog=0,
                 params=None, vary=None):
        self.group = group
        self.name = name
        self.note = note
        self.osc1, self.osc2 = osc1, osc2
        self.lpf_mode, self.hpf_mode = lpf_mode, hpf_mode
        self.mod_fx = mod_fx
        self.clipping = clipping
        self.unison = unison
        self.osc1_file = osc1_file
        self.delay_pingpong, self.delay_analog = delay_pingpong, delay_analog
        self.params = dict(params or {})
        self.vary = dict(vary or {})   # what this case is testing, for the manifest

    def param_attrs(self):
        merged = dict(PARAM_DEFAULTS)
        merged.update(self.params)
        return " ".join('%s="%s"' % (k, merged[k]) for k, _ in PARAM_DEFAULTS)


def build_cases():
    """The matrix. Each group targets one measurement blind spot."""
    cases = []

    # ── 0. Controls ─────────────────────────────────────────────────────────────────────────────
    # Dry references at both registers, so any global level/timing drift in a recording session is
    # separable from the effect under test.
    for note, tag in ((C4, "C4"), (C2, "C2")):
        cases.append(Case("control", "CTL dry saw %s" % tag, note=note, osc2="none",
                          vary={"note": tag}))

    # ── 1. modFX — 0/188 coverage today ─────────────────────────────────────────────────────────
    # Types from fxTypeToString (functions.cpp:932-952). GRAIN is included but is expected to be
    # non-deterministic; it is marked in the manifest so it can be scored separately or excluded.
    for fx in ["chorus", "StereoChorus", "flanger", "phaser", "TapeWarble", "dimension", "grainFX"]:
        for depth in (0.25, 0.5, 1.0):
            for rate in (0.1, 0.4, 0.8):
                cases.append(Case(
                    "modfx", "MFX %s d%02d r%02d" % (fx[:6], depth * 100, rate * 100),
                    osc2="none", mod_fx=fx,
                    params={"modFXDepth": q31(depth), "modFXRate": q31(rate)},
                    vary={"modFXType": fx, "depth": depth, "rate": rate,
                          "nondeterministic": fx == "grainFX"}))
    # Offset / feedback only affect flanger+phaser; sweep them at a fixed depth+rate.
    for fx in ["flanger", "phaser"]:
        for off_ in (0.25, 0.75):
            for fb in (0.25, 0.75):
                cases.append(Case(
                    "modfx", "MFX %s o%02d f%02d" % (fx[:6], off_ * 100, fb * 100),
                    osc2="none", mod_fx=fx,
                    params={"modFXDepth": q31(0.5), "modFXRate": q31(0.4),
                            "modFXOffset": q31(off_), "modFXFeedback": q31(fb)},
                    vary={"modFXType": fx, "offset": off_, "feedback": fb}))

    # ── 2. Wavetable oscillator — 0/188 coverage; two fixes shipped unverified ───────────────────
    # Tables are generated by write_wavetables() so this group is self-contained.
    for table, ncyc in (("WTSAWSQ", 16), ("WTHARM", 8), ("WTFORM", 64)):
        for pos in (0.0, 0.25, 0.5, 0.75, 1.0):
            for note, tag in ((C4, "C4"), (C2, "C2")):
                cases.append(Case(
                    "wavetable", "WT %s p%03d %s" % (table[2:], pos * 100, tag),
                    note=note, osc1="wavetable", osc2="none",
                    osc1_file="SAMPLES/WAVETABLES/%s.WAV" % table,
                    params={"oscAWavetablePosition": q31(pos)},
                    vary={"table": table, "cycles": ncyc, "position": pos, "note": tag}))

    # ── 3. Register — everything measured today is C4/C5 ─────────────────────────────────────────
    # Below ~C3 the saw/square oscillators leave the band-limited tables for the crude
    # (tableNumber<6) per-sample paths, which no gate has ever compared against hardware.
    for osc in ["saw", "square", "triangle", "sine", "analogSaw", "analogSquare"]:
        for note, tag in ((C1, "C1"), (C2, "C2"), (C3, "C3")):
            cases.append(Case("register", "REG %s %s" % (osc[:9], tag),
                              note=note, osc1=osc, osc2="none",
                              vary={"oscType": osc, "note": tag}))
    # Low notes through a moving filter, where crude-path phase errors show up as timbre.
    for note, tag in ((C1, "C1"), (C2, "C2")):
        for f in (0.25, 0.5):
            cases.append(Case("register", "REG lpf%02d %s" % (f * 100, tag),
                              note=note, osc2="none",
                              params={"lpfFrequency": q31freq(f), "lpfResonance": q31(0.6)},
                              vary={"note": tag, "lpfFrequency": f, "lpfResonance": 0.6}))

    # ── 4. Audible HPF — every existing preset's HPF is inert ────────────────────────────────────
    # All 188 ALLSYN sounds carry hpfMode="12dB", an LP-mode string that matches no branch of the
    # HPF dispatch (filter_set.cpp:26-41), so hpfFrequency is ignored on hardware. These use real
    # HPF modes so the high-pass actually renders.
    # Mode strings MUST be the firmware's exact spellings from the EnumStringMap in
    # filter_config.cpp:8-14 — "12dB", "24dB", "24dBDrive", "SVF_Band", "SVF_Notch", "HPLadder",
    # "Off". There is no plain "SVF" mode, and the map is case-sensitive, so "SVF_BAND" would not
    # match on hardware and the case would silently measure some fallback instead.
    # hpfMorph is swept too: at morph 0 the band and notch coefficient sets coincide, so a matrix
    # that left morph at its default could not tell those two modes apart.
    for mode in ["HPLadder", "SVF_Band", "SVF_Notch"]:
        for freq in (0.25, 0.5, 0.75):
            for res in (0.0, 0.5, 0.9):
                cases.append(Case(
                    "hpf", "HPF %s f%02d q%02d" % (mode, freq * 100, res * 100),
                    osc2="none", hpf_mode=mode,
                    params={"hpfFrequency": q31freq(freq), "hpfResonance": q31(res)},
                    vary={"hpfMode": mode, "hpfFrequency": freq, "hpfResonance": res}))
    for mode in ["SVF_Band", "SVF_Notch"]:
        for morph in (0.25, 0.5, 0.75):
            cases.append(Case(
                "hpf", "HPF %s morph%02d" % (mode, morph * 100),
                osc2="none", hpf_mode=mode,
                params={"hpfFrequency": q31freq(0.5), "hpfResonance": q31(0.5),
                        "hpfMorph": q31(morph)},
                vary={"hpfMode": mode, "hpfMorph": morph}))

    # ── 5. Delay — 0/188 coverage ───────────────────────────────────────────────────────────────
    # pingPong and analog are structural (instrument <delay> tag); rate/feedback are clip params.
    for rate in (0.25, 0.5, 0.75):
        for fb in (0.25, 0.5, 0.75):
            for pp in (0, 1):
                for an in (0, 1):
                    cases.append(Case(
                        "delay", "DLY r%02d f%02d p%d a%d" % (rate * 100, fb * 100, pp, an),
                        osc2="none", delay_pingpong=pp, delay_analog=an,
                        params={"delayRate": q31(rate), "delayFeedback": q31(fb)},
                        vary={"delayRate": rate, "delayFeedback": fb,
                              "pingPong": pp, "analog": an}))

    # ── 6. Reverb send ──────────────────────────────────────────────────────────────────────────
    # NOTE: the reverb model itself (roomSize/dampening/width) is a SONG-level setting, so it cannot
    # be varied per case within one song. Only the per-clip send is swept here; to characterise the
    # model, regenerate with --reverb-room/--reverb-damp and record the extra songs.
    for amt in (0.125, 0.25, 0.5, 0.75, 1.0):
        cases.append(Case("reverb", "RVB a%03d" % (amt * 100), osc2="none",
                          params={"reverbAmount": q31(amt)},
                          vary={"reverbAmount": amt}))

    # ── 7. Drive / saturation / fold — the nonlinear stages ─────────────────────────────────────
    for clip in range(1, 8):
        for vol in (0.5, 1.0):
            cases.append(Case("drive", "DRV c%d v%03d" % (clip, vol * 100),
                              osc2="none", clipping=clip,
                              params={"oscAVolume": q31(vol)},
                              vary={"clippingAmount": clip, "oscAVolume": vol}))
    for fold in (0.25, 0.5, 0.75):
        cases.append(Case("drive", "DRV fold%02d" % (fold * 100), osc2="none",
                          params={"waveFold": q31(fold)},
                          vary={"waveFold": fold}))

    # ── 8. Filter morph + LPF modes ─────────────────────────────────────────────────────────────
    # Same rule as the HPF group: exact firmware spellings only (filter_config.cpp:8-14).
    for mode in ["12dB", "24dB", "24dBDrive", "SVF_Band", "SVF_Notch"]:
        for morph in (0.0, 0.25, 0.5, 0.75, 1.0):
            cases.append(Case("morph", "MRP %s m%03d" % (mode, morph * 100),
                              osc2="none", lpf_mode=mode,
                              params={"lpfFrequency": q31freq(0.5), "lpfResonance": q31(0.5),
                                      "lpfMorph": q31(morph)},
                              vary={"lpfMode": mode, "lpfMorph": morph}))

    # ── 9. Noise source — 0/188 coverage ────────────────────────────────────────────────────────
    # Noise is stochastic, so it is scored spectrally (the scorecard already compares normalised
    # log-magnitude spectra, which is meaningful for noise) — but flag it in the manifest.
    for amt in (0.25, 0.5, 1.0):
        for lpf in (0.25, 0.5, 1.0):
            cases.append(Case("noise", "NSE a%02d f%02d" % (amt * 100, lpf * 100),
                              osc1="saw", osc2="none",
                              params={"noiseVolume": q31(amt), "oscAVolume": OFF,
                                      "lpfFrequency": q31(lpf), "lpfResonance": q31(0.3)},
                              vary={"noiseVolume": amt, "lpfFrequency": lpf,
                                    "nondeterministic": True}))
    return cases


def sound_xml(case, clip_instances_hex):
    """One <sound> instrument, mirroring the child order a hardware-written song uses."""
    osc1_extra = ' fileName="%s"' % case.osc1_file if case.osc1_file else ""
    t = "\t\t\t"
    parts = [
        '\t\t<sound presetName="%s" presetFolder="SYNTHS" clipInstances="0x%s">'
        % (case.name, clip_instances_hex),
        '%s<osc1 type="%s" transpose="0" cents="0" retrigPhase="-1"%s />' % (t, case.osc1, osc1_extra),
        '%s<osc2 type="%s" transpose="0" cents="0" retrigPhase="-1" />' % (t, case.osc2),
        '%s<polyphonic>1</polyphonic>' % t,
        '%s<clippingAmount>%d</clippingAmount>' % (t, case.clipping),
        '%s<voicePriority>1</voicePriority>' % t,
    ]
    for i in (1, 2, 3, 4):
        parts.append(t + LFO1.replace("lfo1", "lfo%d" % i))
    parts += [
        t + CUSTOMLFOWAVE,
        '%s<mode>subtractive</mode>' % t,
        '%s<transpose>0</transpose>' % t,
        '%s<modulator1 transpose="0" cents="0" retrigPhase="-1" />' % t,
        '%s<modulator2 transpose="0" cents="0" retrigPhase="-1" toModulator1="0" />' % t,
        '%s<unison><num>%d</num><detune>0x7FFFFFFF</detune><spread>0x00000000</spread></unison>'
        % (t, case.unison),
        t + ARPEGGIATOR,
        '%s<delay><pingPong>%d</pingPong><analog>%d</analog><syncLevel>7</syncLevel></delay>'
        % (t, case.delay_pingpong, case.delay_analog),
        '%s<lpfMode>%s</lpfMode>' % (t, case.lpf_mode),
        '%s<hpfMode>%s</hpfMode>' % (t, case.hpf_mode),
        '%s<modFXType>%s</modFXType>' % (t, case.mod_fx),
        t + DEFAULTPARAMS,
        '%s<midiKnobs></midiKnobs>' % t,
        t + MODKNOBS,
        '\t\t</sound>',
    ]
    return "\n".join(parts)


def clip_xml(case, section):
    return "\n".join([
        '\t\t<instrumentClip clipName="CLIP" instrumentPresetName="%s" instrumentPresetFolder="SYNTHS"'
        ' length="%d" isPlaying="0" section="%d">' % (case.name, CLIP_LEN, section),
        '\t\t\t<soundParams %s />' % case.param_attrs(),
        '\t\t\t<noteRows>',
        '\t\t\t\t<noteRow y="%d" noteDataWithLift="%s" noteDataWithSplitProb="%s" />'
        % (case.note, NOTE_LIFT, NOTE_SPLIT),
        '\t\t\t</noteRows>',
        '\t\t\t<columnControls>',
        '\t\t\t\t<leftCol type="VELOCITY" />',
        '\t\t\t\t<rightCol type="MOD" />',
        '\t\t\t</columnControls>',
        '\t\t</instrumentClip>',
    ])


def clip_instances(pos, idx):
    """[pos:u32][length:u32][clipIndex:u32] big-endian hex, as the Deluge writes it."""
    return "%08X%08X%08X" % (pos, CLIP_LEN, idx)


def write_song(path, cases):
    sounds, clips = [], []
    for i, c in enumerate(cases):
        sounds.append(sound_xml(c, clip_instances(i * CLIP_STEP, i)))
        clips.append(clip_xml(c, i))
    doc = "\n".join([
        '<?xml version="1.0" encoding="UTF-8"?>',
        SONG_OPEN,
        '\t<instruments>',
        "\n".join(sounds),
        '\t</instruments>',
        '\t<sessionClips>',
        "\n".join(clips),
        '\t</sessionClips>',
        TAIL,
    ])
    with open(path, "w", encoding="utf-8") as f:
        f.write(doc)


def write_preset(path, case):
    """Standalone SYNTHS/ preset, so cases can also be auditioned/loaded individually."""
    body = sound_xml(case, clip_instances(0, 0))
    body = body.replace('\t\t<sound presetName="%s" presetFolder="SYNTHS" clipInstances="0x%s">'
                        % (case.name, clip_instances(0, 0)), '<sound>')
    body = body.replace('\t\t</sound>', '</sound>').replace('\n\t\t\t', '\n\t')
    with open(path, "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n' + body + "\n")


def write_wavetables(dirpath):
    """Deluge wavetables: 16-bit mono WAV, power-of-two cycle size, numCycles = len / cycleSize
    (wave_table.cpp:175-204). 2048-sample cycles is the conventional size."""
    cyc = 2048
    specs = {
        # saw -> square morph: the classic wavetable sweep, and the two shapes whose band tables we
        # already golden-test, so a divergence isolates the wavetable path itself.
        "WTSAWSQ": (16, lambda t, f: (2 * t - 1) * (1 - f) + (1 if t < 0.5 else -1) * f),
        # additive harmonic build-up: cycle k contains harmonics 1..k+1, exercising band selection.
        "WTHARM": (8, lambda t, f, n=8: sum(
            math.sin(2 * math.pi * (h + 1) * t) / (h + 1)
            for h in range(int(1 + f * (n - 1)))) * 0.7),
        # slowly moving formant peak: many cycles => exercises cross-cycle interpolation.
        "WTFORM": (64, lambda t, f: math.sin(2 * math.pi * t)
                   + 0.5 * math.sin(2 * math.pi * (1 + int(f * 12)) * t)),
    }
    os.makedirs(dirpath, exist_ok=True)
    for name, (ncyc, fn) in specs.items():
        frames = bytearray()
        for c in range(ncyc):
            f = c / (ncyc - 1) if ncyc > 1 else 0.0
            for i in range(cyc):
                v = fn(i / cyc, f)
                frames += struct.pack("<h", max(-32767, min(32767, int(v * 30000))))
        with wave.open(os.path.join(dirpath, name + ".WAV"), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(44100)
            w.writeframes(bytes(frames))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="calib_out", help="staging dir to write the SD-card tree into")
    args = ap.parse_args()

    cases = build_cases()
    songs_dir = os.path.join(args.out, "SONGS")
    synths_dir = os.path.join(args.out, "SYNTHS")
    wt_dir = os.path.join(args.out, "SAMPLES", "WAVETABLES")
    for d in (songs_dir, synths_dir):
        os.makedirs(d, exist_ok=True)
    write_wavetables(wt_dir)

    rows = []
    songs = [cases[i:i + SOUNDS_PER_SONG] for i in range(0, len(cases), SOUNDS_PER_SONG)]
    for si, chunk in enumerate(songs, start=1):
        song = "CALIB%d" % si
        write_song(os.path.join(songs_dir, song + ".XML"), chunk)
        for ci, c in enumerate(chunk):
            write_preset(os.path.join(synths_dir, c.name + ".XML"), c)
            rows.append({"song": song, "index": ci, "name": c.name, "group": c.group,
                         "note": c.note, "osc1": c.osc1, "osc2": c.osc2,
                         "lpfMode": c.lpf_mode, "hpfMode": c.hpf_mode, "modFXType": c.mod_fx,
                         "clippingAmount": c.clipping,
                         "vary": ";".join("%s=%s" % kv for kv in sorted(c.vary.items()))})
    with open(os.path.join(args.out, "calib_manifest.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)

    from collections import Counter
    counts = Counter(c.group for c in cases)
    print("cases: %d across %d song(s) (%d per song)" % (len(cases), len(songs), SOUNDS_PER_SONG))
    for g, n in sorted(counts.items(), key=lambda kv: -kv[1]):
        print("   %-10s %3d" % (g, n))
    mins = len(cases) * CLIP_STEP / CLIP_LEN * 2 / 60.0
    print("recording time: ~%.1f min total (%d songs)" % (mins, len(songs)))
    print("written to %s" % args.out)


if __name__ == "__main__":
    main()
