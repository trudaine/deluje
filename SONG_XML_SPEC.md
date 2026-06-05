# ChucK-Java Deluge Workstation: Song XML Specification & Element Reference

> Definitive consolidated specification combining XML structure schemas, element tag dictionaries, parameter scales, and concrete side-by-side XML project dump examples (`<song>`, `<kit>`, `<sound>`).

---

## 1. Song-Level Elements

Parsed from `<song>` element in `DelugeXmlParser.parseSong()`.

### Global State

| XML Path | Attribute | Type | Setter | Notes |
|----------|-----------|------|--------|-------|
| `<song>` | `bpm` | float(hex) | `setBpm` | |
| `<song>` | `swing` | float(hex) | `setSwing` | |
| `<song>` | `timeSigNum` | int(hex) | `setTimeSigNum` | |
| `<song>` | `timeSigDenom` | int(hex) | `setTimeSigDenom` | |
| `<song>` | `transpose` | int(hex) | `setTranspose` | |
| `<song>` | `humanize` | float(hex) | `setHumanize` | |
| `<song>` | `key` | text | `setKey` | e.g. "0" (C) |
| `<song>` | `scale` | text | `setScale` | e.g. "Major" |

### Master FX

| XML Path | Attributes | Type | Setter | Notes |
|----------|------------|------|--------|-------|
| `<reverb>` | `roomSize`, `dampening`, `width`, `hpf`, `pan`, `model` | float(hex)/int | `setReverb*` | Nested `<compressor>` with attack/release/syncLevel |
| `<delay>` | `pingPong`, `analog`, `syncLevel`, `syncType` | int | `setDelay*` | |
| `<sidechain>` | `attack`, `release` | float(hex) | `setSidechain*` | |
| `<audioCompressor>` | `attack`, `release`, `threshold`, `ratio` | float(hex) | `setCompressor*` | |

---

## 2. `<songParams>` Element Reference

Child element housing core macro-automation parameters:

| Attribute | Type | Setter |
|-----------|------|--------|
| `volume` | float(hex) | `setSongParamVolume` |
| `pan` | float(hex) | `setSongParamPan` |
| `lpfFrequency`, `lpfResonance` | float(hex) | `setSongParamLpf*` |
| `hpfFrequency`, `hpfResonance` | float(hex) | `setSongParamHpf*` |
| `reverbAmount` | float(hex) | `setSongParamReverbAmount` |
| `delayRate`, `delayFeedback` | float(hex) | `setSongParamDelay*` |
| `sidechainAttack`, `sidechainRelease` | float(hex) | `setSongParamSidechain*` |
| `compressorAttack`, `compressorRelease`, `compressorThreshold`, `compressorRatio` | float(hex) | `setSongParamCompressor*` |
| `modFXRate`, `modFXDepth`, `modFXFeedback` | float(hex) | `setSongParamModFX*` |
| `stutterRate` | float(hex) | `setSongParamStutterRate` |
| `sampleRateReduction` | float(hex) | `setSongParamSampleRateReduction` |
| `bitCrush` | float(hex) | `setSongParamBitCrush` |
| `eqBass`, `eqTreble`, `eqBassFrequency`, `eqTrebleFrequency` | float(hex) | `setSongParamEq*` |
| `modFXOffset` | float(hex) | `setSongParamModFXOffset` |

---

## 3. Synth Track Schema (`<instrumentClip isKitClip="false">`)

Parsed via `populateSynth()`.

### Oscillators

| XML Path | Attribute/Child | Type | Setter |
|----------|-----------------|------|--------|
| `<osc1>` | `type` attr | text | `setOsc1Type` |
| `<osc1>` | `dx7patch` attr | text | `setDx7Patch` |
| `<osc2>` | `type` child | text | `setOsc2Type` |

### Synth Mode & Polyphony

| XML Path | Child text | Values | Setter |
|----------|-----------|--------|--------|
| `<mode>` | text | `"fm"`, `"ringmod"`, else subtractive | `setSynthMode` (0/1/2) |
| `<polyphony>` | text | `"mono"`, `"legato"`, else poly | `setPolyphony` |

---

## 4. Authoritative Song XML Example Dumps

```xml
<?xml version="1.0" encoding="UTF-8"?>
<firmwareVersion>2.1.3</firmwareVersion>
<earliestCompatibleFirmware>2.0.0</earliestCompatibleFirmware>
<song>
    <previewNumPads>144</previewNumPads>
    <arrangementAutoScrollOn>0</arrangementAutoScrollOn>
    <xScroll>0</xScroll>
    <xZoom>12</xZoom>
    <yScrollSongView>-7</yScrollSongView>
    <yScrollArrangementView>-7</yScrollArrangementView>
    <xScrollArrangementView>0</xScrollArrangementView>
    <xZoomArrangementView>96</xZoomArrangementView>
    <timePerTimerTick>459</timePerTimerTick>
    <rootNote>0</rootNote>
    <inputTickMagnitude>1</inputTickMagnitude>
    <swingAmount>0</swingAmount>
    <swingInterval>7</swingInterval>
    <modeNotes>
        <modeNote>0</modeNote>
        <modeNote>2</modeNote>
        <modeNote>4</modeNote>
        <modeNote>5</modeNote>
        <modeNote>7</modeNote>
        <modeNote>9</modeNote>
        <modeNote>11</modeNote>
    </modeNotes>
    <reverb>
        <roomSize>1288490496</roomSize>
        <dampening>1546188288</dampening>
        <width>2147483647</width>
        <pan>0</pan>
        <compressor>
            <attack>135284736</attack>
            <release>-162708526</release>
            <volume>-21474836</volume>
            <syncLevel>9</syncLevel>
        </compressor>
    </reverb>
    <lpfMode>24dB</lpfMode>
    <modFXType>flanger</modFXType>
    <delay>
        <pingPong>1</pingPong>
        <analog>0</analog>
        <syncLevel>7</syncLevel>
    </delay>
    <songParams>
        <delay>
            <rate>0x00000000</rate>
            <feedback>0x80000000</feedback>
        </delay>
        <reverbAmount>0x80000000</reverbAmount>
        <volume>0x3504F334</volume>
        <pan>0x00000000</pan>
        <lpf>
            <frequency>0x7FFFFFFF</frequency>
            <resonance>0x80000000</resonance>
        </lpf>
        <hpf>
            <frequency>0x80000000</frequency>
            <resonance>0x80000000</resonance>
        </hpf>
        <modFXDepth>0x00000000</modFXDepth>
        <modFXRate>0xE0000000</modFXRate>
    </songParams>
</song>
```
