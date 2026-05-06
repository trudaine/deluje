package org.chuck.deluge.engine.dsp;

/**
 * State Variable Filter (SVF) with ZDF (zero-delay feedback) topology, matching
 * the chuck-core {@code SVFilter} class in compute() behavior only.
 *
 * <p>Implements the same ZDF loop with tanh saturation on bandpass state.
 * Morph control: 0=LP, 0.5=BP, 1.0=HP. The filter state (ic1eq/ic2eq) persists
 * across calls, matching the DSL behavior where filter state is only reset
 * during initialization, not per voice trigger.
 */
public class NativeSVFilter {

    private final float sampleRate;
    private double cutoff = 1000.0;
    private double resonance = 0.5;
    private double morph = 0.0;

    // Filter state (ZDF)
    private double ic1eq = 0.0;
    private double ic2eq = 0.0;

    // Morph coefficients
    private double cLow = 1.0;
    private double cBand = 0.0;
    private double cHigh = 0.0;

    public NativeSVFilter(float sampleRate) {
        this.sampleRate = sampleRate;
        updateMorphCoeffs();
    }

    /** Set cutoff frequency in Hz. */
    public void freq(double f) {
        this.cutoff = Math.max(10.0, Math.min(sampleRate * 0.49, f));
    }

    /** Set resonance (Q factor). */
    public void Q(double q) {
        this.resonance = Math.max(0.1, q);
    }

    /** Set morph: 0=LP, 0.5=BP, 1.0=HP. */
    public void morph(double m) {
        this.morph = Math.max(0.0, Math.min(1.0, m));
        updateMorphCoeffs();
    }

    /** Reset filter state to zero. */
    public void reset() {
        ic1eq = 0.0;
        ic2eq = 0.0;
    }

    private void updateMorphCoeffs() {
        double m = this.morph;
        cLow = m <= 0.5 ? 1.0 - 2.0 * m : 0.0;
        cBand = m <= 0.5 ? 2.0 * m : 1.0 - 2.0 * (m - 0.5);
        cHigh = m <= 0.5 ? 0.0 : 2.0 * (m - 0.5);
    }

    /**
     * Process one sample through the ZDF SVF.
     *
     * <p>Matches the DSL's SVFilter.compute() — double-sampled ZDF with tanh
     * saturation on bandpass, no gain multiplication (gain=1.0 by default in DSL).
     */
    public float tick(float input) {
        double g = Math.tan(Math.PI * cutoff / (sampleRate * 2.0));
        double R = 1.0 / (2.0 * resonance);
        double denom = 1.0 / (1.0 + 2.0 * R * g + g * g);

        double hp = 0, bp = 0, lp = 0;
        for (int step = 0; step < 2; step++) {
            hp = (input - 2.0 * R * ic1eq - g * ic1eq - ic2eq) * denom;
            bp = ic1eq + g * hp;
            bp = Math.tanh(bp);
            lp = ic2eq + g * bp;

            ic1eq = 2.0 * bp - ic1eq;
            ic2eq = 2.0 * lp - ic2eq;
            if (Math.abs(ic1eq) < 1.0e-15) ic1eq = 0.0;
            if (Math.abs(ic2eq) < 1.0e-15) ic2eq = 0.0;
        }

        double out = cLow * lp + cBand * bp + cHigh * hp;
        if (Math.abs(out) < 1.0e-15) out = 0.0;
        if (out > 2.0) out = 2.0;
        if (out < -2.0) out = -2.0;

        return (float) out;
    }
}
