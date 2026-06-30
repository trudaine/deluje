#!/usr/bin/env python3
"""Reference-validity audit (no hardware, no build).

For each hardware reference WAV in src/test/resources/fidelity/, report:
  - format (ch/bits/sr/duration)
  - dominant pitch in the loud window, vs the note the filename implies (_c4=261.6, _c5=523.3)
  - envelope shape over 8 windows (to spot silent/corrupt/non-decaying)
  - even/odd harmonic ratio at the expected fundamental (FM/duty sanity)

Flags references whose dominant pitch is far from the implied note (likely wrong patch / corrupt
capture / octave error — like the corrupt DX7 ref or the FM-off C5 fixtures).

Run: python3 scripts/audit_references.py
"""
import math
import os
import wave

FID = os.path.join(os.path.dirname(__file__), "..", "src", "test", "resources", "fidelity")


def read_mono(path):
    w = wave.open(path, "rb")
    ch, sw, fr, n = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
    raw = w.readframes(n)
    w.close()
    fs = ch * sw
    norm = float(1 << (sw * 8 - 1))
    mono = [
        int.from_bytes(raw[i : i + sw], "little", signed=True) / norm
        for i in range(0, len(raw) - fs + 1, fs)
    ]
    return mono, fr, ch, sw


def loudest(x, fr):
    best, br = 0, -1.0
    win = fr
    for o in range(0, max(1, len(x) - win), fr // 4):
        s = sum(v * v for v in x[o : o + win])
        if s > br:
            br, best = s, o
    return best


def goertzel(x, frm, f, fr, n=16384):
    w = 2 * math.pi * f / fr
    c = 2 * math.cos(w)
    s1 = s2 = 0.0
    for i in range(frm, min(frm + n, len(x))):
        t = x[i] + c * s1 - s2
        s2, s1 = s1, t
    return math.hypot(s1, s2 * math.sin(w))


def peak_hz(x, frm, fr):
    bf, bp = 0, -1.0
    f = 80.0
    while f < 3000:
        p = goertzel(x, frm, f, fr)
        if p > bp:
            bp, bf = p, f
        f += 2.0
    return bf


def expected_hz(name):
    n = name.lower()
    if "_c4" in n or "c4_" in n:
        return 261.6
    if "_c5" in n or "c5" in n:
        return 523.3
    return None  # unknown (REC*, ab_*, sw_render, etc.)


def clip_fraction(x):
    n = sum(1 for v in x if abs(v) > 0.985)
    return n / max(1, len(x))


def fundamental_ratio(x, frm, exp, fr):
    """Energy at the expected fundamental relative to the strongest of {fund, 2x, 3x}.
    Low (<~0.15) means the fundamental is essentially absent — a strong 'wrong note / wrong patch'
    signal (e.g. an FM-off carrier captured for an FM patch, or a transposed/wrong take)."""
    fund = goertzel(x, frm, exp, fr)
    harms = max(fund, goertzel(x, frm, 2 * exp, fr), goertzel(x, frm, 3 * exp, fr))
    return fund / (harms + 1e-9)


def main():
    files = sorted(f for f in os.listdir(FID) if f.lower().endswith(".wav"))
    print(f"{'reference':<40} {'fmt':<13} {'dur':>5} {'peakHz':>7} {'exp':>5} {'fund%':>6} {'clip%':>6}  flag")
    print("-" * 110)
    suspects = []
    for f in files:
        path = os.path.join(FID, f)
        try:
            x, fr, ch, sw = read_mono(path)
        except Exception as e:
            print(f"{f:<40} ERR {e}")
            continue
        dur = len(x) / fr
        lo = loudest(x, fr)
        pk = peak_hz(x, lo, fr)
        exp = expected_hz(f)
        clip = clip_fraction(x)
        win = max(1, len(x) // 8)
        env = [math.sqrt(sum(v * v for v in x[k * win : (k + 1) * win]) / win) for k in range(8)]
        fr_ratio = fundamental_ratio(x, lo, exp, fr) if exp else None

        flags = []
        if max(env) < 0.005:
            flags.append("SILENT")
        if clip > 0.02:
            flags.append(f"CLIPPED({clip*100:.0f}%)")
        if exp and fr_ratio is not None and fr_ratio < 0.12:
            flags.append(f"FUND-ABSENT({fr_ratio:.2f})")
        if sw == 2:
            flags.append("16-bit(not-hw?)")
        flag = " ".join(flags)
        if flag:
            suspects.append((f, flag))

        fmt = f"{ch}ch/{sw*8}b/{fr//1000}k"
        frs = f"{fr_ratio*100:5.0f}" if fr_ratio is not None else "    -"
        print(
            f"{f:<40} {fmt:<13} {dur:5.1f} {pk:7.0f} {str(int(exp)) if exp else '-':>5} {frs:>6} {clip*100:5.0f}  {flag}"
        )

    print("\n=== SUSPECTS (investigate / consider re-record) ===")
    for f, fl in suspects:
        print(f"  {f}: {fl}")


if __name__ == "__main__":
    main()
