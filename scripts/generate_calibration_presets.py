#!/usr/bin/env python3
"""Generate the hardware-calibration synth presets (see docs/HARDWARE_CALIBRATION_RECORDING.md).

Each preset isolates ONE DSP feature that the 2026-07 parity fixes touched, so a single
hardware recording of the generated calibration song gives clean per-family ground truth.
The XML structure is copied from known-good ludocard presets (the hardware provably loads
that shape); only parameter values differ per preset.

Usage:  python3 scripts/generate_calibration_presets.py
Output: src/test/resources/calibration/SYNTHS/*.XML  (one file per test segment;
        alphabetical order == playback order in the generated song)
"""

import os

OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "src/test/resources/calibration/SYNTHS")

# Q31 knob hex reference: 0x80000000 = min/off, 0x00000000 = center, 0x7FFFFFFF = max.

BASE = """<?xml version="1.0" encoding="UTF-8"?>
<firmwareVersion>2.0.0-beta</firmwareVersion>
<earliestCompatibleFirmware>2.0.0-beta</earliestCompatibleFirmware>
<sound>
\t<osc1>
\t\t<type>{osc1_type}</type>
\t\t<transpose>{osc1_transpose}</transpose>
\t\t<cents>0</cents>
\t\t<retrigPhase>0</retrigPhase>
\t</osc1>
\t<osc2>
\t\t<type>{osc2_type}</type>
\t\t<transpose>{osc2_transpose}</transpose>
\t\t<cents>0</cents>{osc2_sync}
\t\t<retrigPhase>0</retrigPhase>
\t</osc2>{modulators}
\t<polyphonic>auto</polyphonic>
\t<clippingAmount>0</clippingAmount>
\t<voicePriority>1</voicePriority>{osc_sync_top}
\t<lfo1>
\t\t<type>triangle</type>
\t\t<syncLevel>0</syncLevel>
\t</lfo1>
\t<lfo2>
\t\t<type>triangle</type>
\t</lfo2>
\t<mode>{mode}</mode>
\t<transpose>0</transpose>
\t<unison>
\t\t<num>{unison_num}</num>
\t\t<detune>{unison_detune}</detune>
\t</unison>
\t<compressor>
\t\t<syncLevel>6</syncLevel>
\t\t<attack>327244</attack>
\t\t<release>936</release>
\t</compressor>
\t<lpfMode>{lpf_mode}</lpfMode>
\t<modFXType>{modfx_type}</modFXType>
\t<delay>
\t\t<pingPong>{delay_pingpong}</pingPong>
\t\t<analog>0</analog>
\t\t<syncLevel>{delay_synclevel}</syncLevel>
\t</delay>{arpeggiator}
\t<defaultParams>
\t\t<arpeggiatorGate>{arp_gate}</arpeggiatorGate>
\t\t<portamento>0x80000000</portamento>
\t\t<compressorShape>0xDC28F5B2</compressorShape>
\t\t<oscAVolume>{oscA_volume}</oscAVolume>
\t\t<oscAPulseWidth>{oscA_pw}</oscAPulseWidth>
\t\t<oscBVolume>{oscB_volume}</oscBVolume>
\t\t<oscBPulseWidth>{oscB_pw}</oscBPulseWidth>
\t\t<noiseVolume>{noise_volume}</noiseVolume>
\t\t<volume>0x47A30000</volume>
\t\t<pan>0x00000000</pan>
\t\t<lpfFrequency>{lpf_freq}</lpfFrequency>
\t\t<lpfResonance>{lpf_res}</lpfResonance>
\t\t<hpfFrequency>{hpf_freq}</hpfFrequency>
\t\t<hpfResonance>{hpf_res}</hpfResonance>
\t\t<envelope1>
\t\t\t<attack>0x80000000</attack>
\t\t\t<decay>{env1_decay}</decay>
\t\t\t<sustain>{env1_sustain}</sustain>
\t\t\t<release>0x80000000</release>
\t\t</envelope1>
\t\t<envelope2>
\t\t\t<attack>{env2_attack}</attack>
\t\t\t<decay>{env2_decay}</decay>
\t\t\t<sustain>0x80000000</sustain>
\t\t\t<release>0xD70A3D61</release>
\t\t</envelope2>
\t\t<lfo1Rate>{lfo1_rate}</lfo1Rate>
\t\t<lfo2Rate>0x33333313</lfo2Rate>
\t\t<modulator1Amount>{mod1_amount}</modulator1Amount>
\t\t<modulator1Feedback>{mod1_feedback}</modulator1Feedback>
\t\t<modulator2Amount>{mod2_amount}</modulator2Amount>
\t\t<modulator2Feedback>0x80000000</modulator2Feedback>
\t\t<carrier1Feedback>{car1_feedback}</carrier1Feedback>
\t\t<carrier2Feedback>0x80000000</carrier2Feedback>
\t\t<modFXRate>{modfx_rate}</modFXRate>
\t\t<modFXDepth>{modfx_depth}</modFXDepth>
\t\t<delayRate>0x00000000</delayRate>
\t\t<delayFeedback>{delay_feedback}</delayFeedback>
\t\t<reverbAmount>{reverb_amount}</reverbAmount>
\t\t<arpeggiatorRate>{arp_rate}</arpeggiatorRate>
\t\t<patchCables>
\t\t\t<patchCable>
\t\t\t\t<source>velocity</source>
\t\t\t\t<destination>volume</destination>
\t\t\t\t<amount>0x3FFFFFE8</amount>
\t\t\t</patchCable>{extra_cables}
\t\t</patchCables>
\t\t<stutterRate>0x00000000</stutterRate>
\t\t<sampleRateReduction>0x80000000</sampleRateReduction>
\t\t<bitCrush>0x80000000</bitCrush>
\t\t<equalizer>
\t\t\t<bass>0x00000000</bass>
\t\t\t<treble>0x00000000</treble>
\t\t\t<bassFrequency>0x00000000</bassFrequency>
\t\t\t<trebleFrequency>0x00000000</trebleFrequency>
\t\t</equalizer>
\t\t<modFXOffset>0x00000000</modFXOffset>
\t\t<modFXFeedback>{modfx_feedback}</modFXFeedback>
\t</defaultParams>
\t<midiKnobs>
\t</midiKnobs>
\t<modKnobs>
\t\t<modKnob>
\t\t\t<controlsParam>pan</controlsParam>
\t\t</modKnob>
\t\t<modKnob>
\t\t\t<controlsParam>volumePostFX</controlsParam>
\t\t</modKnob>
\t\t<modKnob>
\t\t\t<controlsParam>lpfResonance</controlsParam>
\t\t</modKnob>
\t\t<modKnob>
\t\t\t<controlsParam>lpfFrequency</controlsParam>
\t\t</modKnob>
\t\t<modKnob>
\t\t\t<controlsParam>env1Release</controlsParam>
\t\t</modKnob>
\t\t<modKnob>
\t\t\t<controlsParam>env1Attack</controlsParam>
\t\t</modKnob>
\t\t<modKnob>
\t\t\t<controlsParam>delayFeedback</controlsParam>
\t\t</modKnob>
\t\t<modKnob>
\t\t\t<controlsParam>delayRate</controlsParam>
\t\t</modKnob>
\t\t<modKnob>
\t\t\t<controlsParam>reverbAmount</controlsParam>
\t\t</modKnob>
\t\t<modKnob>
\t\t\t<controlsParam>volumePostReverbSend</controlsParam>
\t\t\t<patchAmountFromSource>compressor</patchAmountFromSource>
\t\t</modKnob>
\t\t<modKnob>
\t\t\t<controlsParam>pitch</controlsParam>
\t\t\t<patchAmountFromSource>lfo1</patchAmountFromSource>
\t\t</modKnob>
\t\t<modKnob>
\t\t\t<controlsParam>lfo1Rate</controlsParam>
\t\t</modKnob>
\t\t<modKnob>
\t\t\t<controlsParam>portamento</controlsParam>
\t\t</modKnob>
\t\t<modKnob>
\t\t\t<controlsParam>stutterRate</controlsParam>
\t\t</modKnob>
\t\t<modKnob>
\t\t\t<controlsParam>env2Decay</controlsParam>
\t\t</modKnob>
\t\t<modKnob>
\t\t\t<controlsParam>oscAPhaseWidth</controlsParam>
\t\t</modKnob>
\t</modKnobs>
</sound>
"""

DEFAULTS = dict(
    osc1_type="saw",
    osc1_transpose="0",
    osc2_type="square",
    osc2_transpose="0",
    osc2_sync="",
    osc_sync_top="",
    modulators="",
    mode="subtractive",
    unison_num="1",
    unison_detune="8",
    lpf_mode="24dB",
    modfx_type="none",
    delay_pingpong="0",
    delay_synclevel="7",
    arpeggiator="",
    arp_gate="0x00000000",
    arp_rate="0x00000000",
    oscA_volume="0x7FFFFFFF",
    oscA_pw="0x00000000",
    oscB_volume="0x80000000",  # osc2 OFF by default
    oscB_pw="0x00000000",
    noise_volume="0x80000000",
    lpf_freq="0x7FFFFFFF",  # max = hard bypass (sound.cpp:2506-2519)
    lpf_res="0x80000000",
    hpf_freq="0x80000000",
    hpf_res="0x80000000",
    env1_decay="0xE6666654",
    env1_sustain="0x7FFFFFFF",  # sustained tone
    env2_attack="0x9EB851E6",
    env2_decay="0x99999995",
    lfo1_rate="0x26000000",
    mod1_amount="0x80000000",
    mod1_feedback="0x80000000",
    mod2_amount="0x80000000",
    car1_feedback="0x80000000",
    modfx_rate="0x00000000",
    modfx_depth="0x00000000",
    modfx_feedback="0x80000000",
    delay_feedback="0x80000000",  # delay OFF by default
    reverb_amount="0x80000000",  # reverb send OFF by default
    extra_cables="",
)

SYNC_OSC2 = "\n\t\t<oscillatorSync>1</oscillatorSync>"
SYNC_TOP = "\n\t<oscillatorSync>1</oscillatorSync>"

def fm_modulators(m1_transpose=0, m2_transpose=0, to_mod1=0):
    return (
        "\n\t<modulator1>\n\t\t<transpose>%d</transpose>\n\t\t<cents>0</cents>\n\t\t<retrigPhase>0</retrigPhase>\n\t</modulator1>"
        "\n\t<modulator2>\n\t\t<transpose>%d</transpose>\n\t\t<cents>0</cents>\n\t\t<toModulator1>%d</toModulator1>\n\t\t<retrigPhase>0</retrigPhase>\n\t</modulator2>"
        % (m1_transpose, m2_transpose, to_mod1)
    )

def cable(source, destination, amount):
    return (
        "\n\t\t\t<patchCable>\n\t\t\t\t<source>%s</source>\n\t\t\t\t<destination>%s</destination>"
        "\n\t\t\t\t<amount>%s</amount>\n\t\t\t</patchCable>" % (source, destination, amount)
    )

ARP_UP_16TH = (
    "\n\t<arpeggiator>\n\t\t<mode>up</mode>\n\t\t<numOctaves>1</numOctaves>"
    "\n\t\t<syncLevel>8</syncLevel>\n\t</arpeggiator>"
)

PLUCK = dict(env1_sustain="0x80000000", env1_decay="0x00000000")  # sustain 0, ~1 s decay

PRESETS = {
    # ── Oscillator level/balance anchors (validate the sine −6 dB fix, crude/table balance) ──
    "01 CAL SINE": dict(osc1_type="sine"),
    "02 CAL TRIANGLE": dict(osc1_type="triangle"),
    "03 CAL SAW": dict(),
    "04 CAL SQUARE 50": dict(osc1_type="square"),
    # ── Pulse width (the signed-shift fixes) ──
    "05 CAL SQUARE PW": dict(osc1_type="square", oscA_pw="0x40000000"),
    "06 CAL SAW PW": dict(oscA_pw="0x40000000"),
    "07 CAL PWM LFO": dict(
        osc1_type="square",
        lfo1_rate="0x26000000",
        extra_cables=cable("lfo1", "oscAPhaseWidth", "0x30000000"),
    ),
    "08 CAL PW ENV": dict(
        osc1_type="square",
        env2_attack="0x30000000",
        env2_decay="0x30000000",
        extra_cables=cable("envelope2", "oscAPhaseWidth", "0x40000000"),
    ),
    # ── Oscillator hard sync (incl. the newly ported synced square+PW branch) ──
    "09 CAL SAW SYNC": dict(
        osc2_type="saw",
        osc2_transpose="18",
        osc2_sync=SYNC_OSC2,
        osc_sync_top=SYNC_TOP,
        oscA_volume="0x80000000",
        oscB_volume="0x7FFFFFFF",
    ),
    "10 CAL SQUARE SYNC PW": dict(
        osc2_type="square",
        osc2_transpose="18",
        osc2_sync=SYNC_OSC2,
        osc_sync_top=SYNC_TOP,
        oscA_volume="0x80000000",
        oscB_volume="0x7FFFFFFF",
        oscB_pw="0x40000000",
    ),
    # ── Noise + unison ──
    "11 CAL NOISE": dict(oscA_volume="0x80000000", noise_volume="0x7FFFFFFF"),
    "12 CAL UNISON 4": dict(unison_num="4", unison_detune="12"),
    # ── Filters (resonance, drive-threshold zone, engaged HPF) ──
    "13 CAL LPF RESO": dict(lpf_freq="0x20000000", lpf_res="0x50000000"),
    "14 CAL LPF DRIVE": dict(lpf_mode="24dBDrive", lpf_freq="0x50000000", lpf_res="0x60000000"),
    "15 CAL HPF RESO": dict(hpf_freq="0x10000000", hpf_res="0x40000000"),
    # ── Native FM (index calibration, chain routing, feedback) ──
    "16 CAL FM LOW": dict(
        mode="fm", modulators=fm_modulators(0, 0, 0), mod1_amount="0x20000000"
    ),
    "17 CAL FM HIGH": dict(
        mode="fm", modulators=fm_modulators(34, 0, 0), mod1_amount="0x40000000"
    ),
    "18 CAL FM CHAIN": dict(
        mode="fm",
        modulators=fm_modulators(0, 19, 1),
        mod1_amount="0x20000000",
        mod2_amount="0x30000000",
    ),
    "19 CAL FM CHAIN MUTE": dict(
        # mod2 routes into a MUTED mod1: hardware must produce PURE SINE carriers
        # (voice.cpp:1436-1439 goto noModulatorsActive) — the audible test of the routing gate.
        mode="fm",
        modulators=fm_modulators(0, 19, 1),
        mod1_amount="0x80000000",
        mod2_amount="0x30000000",
    ),
    "20 CAL FM FEEDBACK": dict(
        mode="fm",
        modulators=fm_modulators(0, 0, 0),
        mod1_amount="0x30000000",
        mod1_feedback="0x40000000",
        car1_feedback="0x30000000",
    ),
    # ── Per-sound FX (delay sync time, ping-pong stereo, reverb send) ──
    "21 CAL DELAY SYNC": dict(
        delay_synclevel="6", delay_feedback="0x20000000", **PLUCK
    ),
    "22 CAL DELAY PINGPONG": dict(
        delay_synclevel="6", delay_feedback="0x20000000", delay_pingpong="1", **PLUCK
    ),
    "23 CAL REVERB SEND": dict(reverb_amount="0x30000000", **PLUCK),
    # ── ModFX (rate/depth ground truth for the patcher-final fix) ──
    "24 CAL CHORUS": dict(
        osc1_type="square", modfx_type="chorus", modfx_rate="0x00000000", modfx_depth="0x20000000"
    ),
    "25 CAL FLANGER": dict(
        osc1_type="square",
        modfx_type="flanger",
        modfx_rate="0x00000000",
        modfx_feedback="0x30000000",
    ),
    "26 CAL PHASER": dict(
        osc1_type="square",
        modfx_type="phaser",
        modfx_rate="0x00000000",
        modfx_depth="0x20000000",
        modfx_feedback="0x30000000",
    ),
    # ── Arp gate/sync + envelope decay shape ──
    "27 CAL ARP GATE": dict(
        arpeggiator=ARP_UP_16TH, arp_gate="0x00000000", arp_rate="0x00000000", **PLUCK
    ),
    "28 CAL PLUCK": dict(**PLUCK),
}


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    for name, overrides in sorted(PRESETS.items()):
        params = dict(DEFAULTS)
        params.update(overrides)
        xml = BASE.format(**params)
        path = os.path.join(OUT_DIR, name + ".XML")
        with open(path, "w") as f:
            f.write(xml)
        print("wrote", path)
    print(len(PRESETS), "presets")


if __name__ == "__main__":
    main()
