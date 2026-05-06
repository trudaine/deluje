package org.chuck.deluge.engine.dsp;

/**
 * Second-order Butterworth high-pass filter, matching the chuck-core {@code HPF}
 * class in compute() behavior.
 *
 * <p>Implements a standard biquad HPF with configurable cutoff and Q.
 * Filter state (x1/x2/y1/y2) persists across calls, matching the DSL behavior.
 */
public class NativeHPF {

    private final float sampleRate;
    private double cutoff = 1000.0;
    private double q = 0.707;

    // Biquad coefficients
    private double b0, b1, b2;
    private double a1, a2;

    // Filter state
    private double x1 = 0, x2 = 0, y1 = 0, y2 = 0;

    public NativeHPF(float sampleRate) {
        this.sampleRate = sampleRate;
        updateCoeffs();
    }

    /** Set cutoff frequency in Hz. */
    public void freq(double f) {
        this.cutoff = Math.max(10.0, f);
        updateCoeffs();
    }

    /** Set Q factor (resonance). */
    public void Q(double qv) {
        this.q = qv;
        updateCoeffs();
    }

    /** Reset filter state to zero. */
    public void reset() {
        x1 = 0; x2 = 0; y1 = 0; y2 = 0;
    }

    private void updateCoeffs() {
        double w0 = 2.0 * Math.PI * cutoff / sampleRate;
        double cosW0 = Math.cos(w0);
        double alpha = Math.sin(w0) / (2.0 * q);
        double norm = 1.0 / (1.0 + alpha);
        b0 = (1.0 + cosW0) / 2.0 * norm;
        b1 = -(1.0 + cosW0) * norm;
        b2 = (1.0 + cosW0) / 2.0 * norm;
        a1 = -2.0 * cosW0 * norm;
        a2 = (1.0 - alpha) * norm;
    }

    /**
     * Process one sample through the biquad HPF.
     *
     * <p>Matches the DSL's HPF.compute() — standard biquad difference equation,
     * no gain multiplication.
     */
    public float tick(float input) {
        double x0 = input;
        double y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        x2 = x1;
        x1 = x0;
        y2 = y1;
        y1 = y0;
        if (Math.abs(y1) < 1.0e-15) y1 = 0.0;
        if (Math.abs(y2) < 1.0e-15) y2 = 0.0;

        float out = (float) y0;
        if (out > 2.0f) out = 2.0f;
        if (out < -2.0f) out = -2.0f;
        return out;
    }
}
