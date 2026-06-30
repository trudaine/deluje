#!/usr/bin/env python3
"""Generate a comprehensive battery of Deluge synth test presets (native 2.0.0-beta format).

Each preset isolates ONE synthesis feature so a clean hardware reference can be recorded from it.
Output: src/test/resources/fidelity/test_presets/*.XML + a README.

WHY a generator (not hand-XML, not the Java serializer): the real Deluge rejects malformed presets
as FILE_CORRUPTED, and our Java serializer emits a divergent format (polyphonic=1 not 'auto',
analogsquare not analogSquare, hex unison detune, no firmwareVersion). So we stamp from the EXACT
structure of a real ludocard preset (2.0.0-beta, proven to load), overriding only per-test fields.

RECORD CONTRACT (must match FidelityScorecardTest.renderSynth): send MIDI note 60, velocity 100,
hold >=3s. Resample on the Deluge (digital), 44.1kHz; align with scripts/align_recording.py.
"""
import os

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "test", "resources", "fidelity", "test_presets")

OFF = "0x80000000"   # bipolar Q31 minimum / "off"
ZERO = "0x00000000"  # center
MAX = "0x7FFFFFFF"   # full
HALF = "0x40000000"  # +half

# Standard mod-knob block (UI assignments only; no effect on rendered sound, included for realism).
MODKNOBS = """	<modKnobs>
		<modKnob><controlsParam>pan</controlsParam></modKnob>
		<modKnob><controlsParam>volumePostFX</controlsParam></modKnob>
		<modKnob><controlsParam>lpfResonance</controlsParam></modKnob>
		<modKnob><controlsParam>lpfFrequency</controlsParam></modKnob>
		<modKnob><controlsParam>env1Release</controlsParam></modKnob>
		<modKnob><controlsParam>env1Attack</controlsParam></modKnob>
		<modKnob><controlsParam>delayFeedback</controlsParam></modKnob>
		<modKnob><controlsParam>delayRate</controlsParam></modKnob>
		<modKnob><controlsParam>reverbAmount</controlsParam></modKnob>
		<modKnob><controlsParam>volumePostReverbSend</controlsParam><patchAmountFromSource>compressor</patchAmountFromSource></modKnob>
		<modKnob><controlsParam>pitch</controlsParam><patchAmountFromSource>lfo1</patchAmountFromSource></modKnob>
		<modKnob><controlsParam>lfo1Rate</controlsParam></modKnob>
		<modKnob><controlsParam>portamento</controlsParam></modKnob>
		<modKnob><controlsParam>stutterRate</controlsParam></modKnob>
		<modKnob><controlsParam>env2Decay</controlsParam></modKnob>
		<modKnob><controlsParam>oscAPhaseWidth</controlsParam></modKnob>
	</modKnobs>"""


def preset(
    osc1="saw", osc2="none", osc1_transpose=0, osc2_transpose=0,
    mode="subtractive", mod1_transpose=0, mod2_transpose=0,
    osc_a_volume=MAX, osc_b_volume=OFF, noise_volume=OFF,
    lpf_freq=MAX, lpf_res=OFF, lpf_mode="24dB", hpf_freq=OFF, hpf_res=OFF,
    osc_a_pw=ZERO, osc_b_pw=ZERO,
    mod1_amount=OFF, mod1_feedback=OFF, mod2_amount=OFF,
    unison_num=1, unison_detune=0,
    osc2_sync=0, clipping=0,
    # envelope1 (amp): instant attack, full sustain (steady tone) by default
    env1=("0x80000000", "0x80000000", "0x7FFFFFFF", "0xE0000000"),
    env2=("0x80000000", "0x80000000", "0x7FFFFFFF", "0xE0000000"),
    lfo1_type="triangle", lfo1_rate=OFF, lfo2_rate=OFF,
    cables=None,
):
    """Build one preset's XML text. cables = list of (source, dest, amount)."""
    osc2_block = (
        f"\t<osc2>\n\t\t<type>{osc2}</type>\n\t\t<transpose>{osc2_transpose}</transpose>\n"
        f"\t\t<cents>0</cents>\n\t\t<retrigPhase>0</retrigPhase>\n\t</osc2>"
    )
    mod_blocks = ""
    if mode == "fm":
        mod_blocks = (
            f"\t<modulator1>\n\t\t<transpose>{mod1_transpose}</transpose>\n\t\t<cents>0</cents>\n\t</modulator1>\n"
            f"\t<modulator2>\n\t\t<transpose>{mod2_transpose}</transpose>\n\t\t<cents>0</cents>\n"
            f"\t\t<toModulator1>0</toModulator1>\n\t</modulator2>\n"
        )
    sync_attr = f"\t<oscillatorSync>{osc2_sync}</oscillatorSync>\n" if osc2_sync else ""
    cable_xml = ""
    if cables:
        rows = "".join(
            f"\t\t\t<patchCable>\n\t\t\t\t<source>{s}</source>\n\t\t\t\t<destination>{d}</destination>\n"
            f"\t\t\t\t<amount>{a}</amount>\n\t\t\t</patchCable>\n"
            for (s, d, a) in cables
        )
        cable_xml = f"\t\t<patchCables>\n{rows}\t\t</patchCables>\n"
    else:
        cable_xml = "\t\t<patchCables>\n\t\t</patchCables>\n"

    return f"""<?xml version="1.0" encoding="UTF-8"?>
<firmwareVersion>2.0.0-beta</firmwareVersion>
<earliestCompatibleFirmware>2.0.0-beta</earliestCompatibleFirmware>
<sound>
	<osc1>
		<type>{osc1}</type>
		<transpose>{osc1_transpose}</transpose>
		<cents>0</cents>
		<retrigPhase>0</retrigPhase>
	</osc1>
{osc2_block}
	<polyphonic>poly</polyphonic>
	<clippingAmount>{clipping}</clippingAmount>
	<voicePriority>1</voicePriority>
	<lfo1>
		<type>{lfo1_type}</type>
		<syncLevel>0</syncLevel>
	</lfo1>
	<lfo2>
		<type>triangle</type>
	</lfo2>
	<mode>{mode}</mode>
	<transpose>0</transpose>
{mod_blocks}{sync_attr}	<unison>
		<num>{unison_num}</num>
		<detune>{unison_detune}</detune>
	</unison>
	<lpfMode>{lpf_mode}</lpfMode>
	<modFXType>none</modFXType>
	<delay>
		<pingPong>1</pingPong>
		<analog>0</analog>
		<syncLevel>0</syncLevel>
	</delay>
	<defaultParams>
		<arpeggiatorGate>{ZERO}</arpeggiatorGate>
		<portamento>{OFF}</portamento>
		<compressorShape>{OFF}</compressorShape>
		<oscAVolume>{osc_a_volume}</oscAVolume>
		<oscAPulseWidth>{osc_a_pw}</oscAPulseWidth>
		<oscBVolume>{osc_b_volume}</oscBVolume>
		<oscBPulseWidth>{osc_b_pw}</oscBPulseWidth>
		<noiseVolume>{noise_volume}</noiseVolume>
		<volume>0x60000000</volume>
		<pan>{ZERO}</pan>
		<lpfFrequency>{lpf_freq}</lpfFrequency>
		<lpfResonance>{lpf_res}</lpfResonance>
		<hpfFrequency>{hpf_freq}</hpfFrequency>
		<hpfResonance>{hpf_res}</hpfResonance>
		<envelope1>
			<attack>{env1[0]}</attack>
			<decay>{env1[1]}</decay>
			<sustain>{env1[2]}</sustain>
			<release>{env1[3]}</release>
		</envelope1>
		<envelope2>
			<attack>{env2[0]}</attack>
			<decay>{env2[1]}</decay>
			<sustain>{env2[2]}</sustain>
			<release>{env2[3]}</release>
		</envelope2>
		<lfo1Rate>{lfo1_rate}</lfo1Rate>
		<lfo2Rate>{lfo2_rate}</lfo2Rate>
		<modulator1Amount>{mod1_amount}</modulator1Amount>
		<modulator1Feedback>{mod1_feedback}</modulator1Feedback>
		<modulator2Amount>{mod2_amount}</modulator2Amount>
		<modulator2Feedback>{OFF}</modulator2Feedback>
		<carrier1Feedback>{OFF}</carrier1Feedback>
		<carrier2Feedback>{OFF}</carrier2Feedback>
		<modFXRate>{ZERO}</modFXRate>
		<modFXDepth>{OFF}</modFXDepth>
		<delayRate>{ZERO}</delayRate>
		<delayFeedback>{OFF}</delayFeedback>
		<reverbAmount>{OFF}</reverbAmount>
		<arpeggiatorRate>{ZERO}</arpeggiatorRate>
{cable_xml}		<stutterRate>{ZERO}</stutterRate>
		<sampleRateReduction>{OFF}</sampleRateReduction>
		<bitCrush>{OFF}</bitCrush>
		<equalizer>
			<bass>{ZERO}</bass>
			<treble>{ZERO}</treble>
			<bassFrequency>{ZERO}</bassFrequency>
			<trebleFrequency>{ZERO}</trebleFrequency>
		</equalizer>
	</defaultParams>
	<midiKnobs>
	</midiKnobs>
{MODKNOBS}
</sound>
"""


# ── The battery: (filename, description, kwargs) ────────────────────────────────────────────────
LPF_MID = "0xD0000000"   # partially-closed LPF
LPF_LOW = "0xA0000000"
RES_HIGH = "0x60000000"
PW_25 = "0x40000000"     # ~25% pulse width

CASES = [
    # Dry oscillators (filter wide open, no FX, single voice)
    ("T01_dry_saw", "Dry sawtooth, LPF open, no FX", dict(osc1="saw")),
    ("T02_dry_square", "Dry square (50%), LPF open", dict(osc1="square")),
    ("T03_dry_sine", "Dry sine", dict(osc1="sine")),
    ("T04_dry_triangle", "Dry triangle", dict(osc1="triangle")),
    ("T05_dry_analogsaw", "Dry analog saw (band-limited table)", dict(osc1="analogSaw")),
    ("T06_dry_analogsquare", "Dry analog square", dict(osc1="analogSquare")),
    ("T07_noise", "Pure white noise (osc volume off, noise on)", dict(osc1="saw", osc_a_volume=OFF, noise_volume=MAX)),
    # Filter
    ("T08_lpf_saw", "Saw through 24dB LPF (mid cutoff)", dict(osc1="saw", lpf_freq=LPF_MID)),
    ("T09_lpf_resonant", "Saw, 24dB LPF mid cutoff + high resonance", dict(osc1="saw", lpf_freq=LPF_MID, lpf_res=RES_HIGH)),
    ("T10_hpf_saw", "Saw through HPF (mid cutoff)", dict(osc1="saw", hpf_freq=LPF_MID)),
    ("T11_hpf_resonant", "Saw, HPF mid + high resonance", dict(osc1="saw", hpf_freq=LPF_MID, hpf_res=RES_HIGH)),
    ("T12_lpf_12db", "Saw through 12dB LPF (mid cutoff)", dict(osc1="saw", lpf_freq=LPF_MID, lpf_mode="12dB")),
    # PWM
    ("T13_pwm_static", "Square with static ~25% pulse width", dict(osc1="square", osc_a_pw=PW_25)),
    ("T14_pwm_lfo", "Square, LFO1 -> pulse width sweep", dict(osc1="square", lfo1_rate="0x20000000", cables=[("lfo1", "oscAPulseWidth", HALF)])),
    # Oscillator sync (osc2 audible, synced to osc1)
    ("T15_saw_sync", "Saw osc2 hard-synced to osc1 (osc2 +7 semis)", dict(osc1="saw", osc2="saw", osc2_transpose=7, osc_b_volume=MAX, osc2_sync=1)),
    ("T16_square_sync", "Square osc2 hard-synced to osc1", dict(osc1="square", osc2="square", osc2_transpose=7, osc_b_volume=MAX, osc2_sync=1)),
    # FM (native 2-op)
    ("T17_fm_simple", "FM: sine carrier, modulator1 ratio 1 (transpose 0), moderate amount", dict(osc1="sine", osc2="sine", mode="fm", mod1_transpose=0, mod1_amount="0xC0000000")),
    ("T18_fm_feedback", "FM: modulator1 ratio 1 + feedback", dict(osc1="sine", osc2="sine", mode="fm", mod1_transpose=0, mod1_amount="0xC0000000", mod1_feedback="0xA0000000")),
    ("T19_fm_bell", "FM bell: modulator1 high ratio (transpose +14)", dict(osc1="sine", osc2="sine", mode="fm", mod1_transpose=14, mod1_amount="0xC0000000")),
    # Unison
    ("T20_unison_saw", "Saw, 4-voice unison with detune", dict(osc1="saw", unison_num=4, unison_detune=20)),
    ("T21_unison8_saw", "Saw, 8-voice unison, wide detune", dict(osc1="saw", unison_num=8, unison_detune=30)),
    # LFO targets
    ("T22_lfo_vibrato", "LFO1 -> pitch (vibrato)", dict(osc1="saw", lfo1_rate="0x18000000", cables=[("lfo1", "pitch", "0x08000000")])),
    ("T23_lfo_tremolo", "LFO1 -> volume (tremolo)", dict(osc1="saw", lfo1_rate="0x18000000", cables=[("lfo1", "volume", HALF)])),
    ("T24_lfo_lpf", "LFO1 -> LPF frequency", dict(osc1="saw", lpf_freq=LPF_MID, lfo1_rate="0x18000000", cables=[("lfo1", "lpfFrequency", HALF)])),
    # Envelope
    ("T25_fast_decay", "Saw, fast decay to silence (plucky, sustain 0)", dict(osc1="saw", env1=("0x80000000", "0xC0000000", "0x80000000", "0xC0000000"))),
    ("T26_slow_attack", "Saw, slow attack swell", dict(osc1="saw", env1=("0x20000000", "0x80000000", "0x7FFFFFFF", "0xE0000000"))),
    ("T27_pitch_env", "Envelope2 -> pitch sweep", dict(osc1="saw", cables=[("envelope2", "pitch", "0x10000000")])),
    # Saturation / drive
    ("T28_drive_saturate", "Saw with clippingAmount (saturation/drive)", dict(osc1="saw", clipping=20)),
]


def main():
    os.makedirs(OUT, exist_ok=True)
    readme = [
        "# Deluge fidelity test presets (native 2.0.0-beta format)",
        "",
        "Generated by `scripts/gen_test_presets.py`. Each preset isolates ONE synthesis feature so a",
        "clean hardware reference can be recorded from it. Built from the exact structure of a real",
        "ludocard preset (proven to load on hardware), overriding only per-test fields — NOT via the",
        "Java serializer (which emits a divergent format the hardware may reject).",
        "",
        "## Recording procedure",
        "1. Copy a preset to the Deluge SD card `SYNTHS/` (rename to an unused number if needed).",
        "2. Send **MIDI note 60, velocity 100**, hold >= 3 s, then release.",
        "   (Note 60 matches `FidelityScorecardTest.renderSynth`; the engine renders the same note.)",
        "3. **Resample** on the Deluge (digital output), 44.1 kHz; keep peak around -3 to -6 dB.",
        "4. Align: `python3 scripts/align_recording.py <REC>.WAV "
        "src/test/resources/fidelity/<reference>.wav <targetSample>`.",
        "",
        "## Presets",
        "",
        "| preset | tests |",
        "|---|---|",
    ]
    for name, desc, kw in CASES:
        xml = preset(**kw)
        with open(os.path.join(OUT, name + ".XML"), "w") as f:
            f.write(xml)
        readme.append(f"| `{name}.XML` | {desc} |")
    with open(os.path.join(OUT, "README.md"), "w") as f:
        f.write("\n".join(readme) + "\n")
    print(f"Wrote {len(CASES)} presets + README to {OUT}")


if __name__ == "__main__":
    main()
