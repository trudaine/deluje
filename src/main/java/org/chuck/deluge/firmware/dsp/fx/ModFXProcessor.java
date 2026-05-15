package org.chuck.deluge.firmware.dsp.fx;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.modulation.LFO;
import org.chuck.deluge.firmware.util.Q31;

import java.util.Arrays;

import static org.chuck.deluge.firmware.util.Q31.*;

public class ModFXProcessor {
    public static final int kModFXBufferSize = 512;
    public static final int kModFXBufferIndexMask = (kModFXBufferSize - 1);
    public static final int kModFXMaxDelay = ((kModFXBufferSize - 1) << 16);
    public static final int kFlangerMinTime = (3 << 16);
    public static final int kFlangerAmplitude = (kModFXMaxDelay - kFlangerMinTime);
    public static final int kFlangerOffset = ((kModFXMaxDelay + kFlangerMinTime) >> 1);
    public static final int kNumAllpassFiltersPhaser = 6;

    private final StereoSample[] modFXBuffer = new StereoSample[kModFXBufferSize];
    private int modFXBufferWriteIndex = 0;

    private final StereoSample phaserMemory = new StereoSample(0, 0);
    private final StereoSample[] allpassMemory = new StereoSample[kNumAllpassFiltersPhaser];

    public final LFO modFXLFO = new LFO();
    public final LFO modFXLFOStereo = new LFO();

    public ModFXProcessor() {
        for (int i = 0; i < kModFXBufferSize; i++) modFXBuffer[i] = new StereoSample(0, 0);
        for (int i = 0; i < kNumAllpassFiltersPhaser; i++) allpassMemory[i] = new StereoSample(0, 0);
    }

    public void resetMemory() {
        for (StereoSample s : modFXBuffer) { s.l = 0; s.r = 0; }
        for (StereoSample s : allpassMemory) { s.l = 0; s.r = 0; }
        phaserMemory.l = 0; phaserMemory.r = 0;
    }

    public void processModFX(StereoSample[] buffer, ModFXType modFXType, int modFXRate, int modFXDepth,
                             int[] postFXVolume, int modFXOffset, int modFXFeedback) {
        if (modFXType == ModFXType.NONE) return;

        LFO.LFOType waveType = LFO.LFOType.SINE;
        int delayOffset = 0;
        int delayDepth = 0;
        int feedback = 0;

        // Configuration
        if (modFXType == ModFXType.FLANGER || modFXType == ModFXType.PHASER || modFXType == ModFXType.WARBLE) {
            // setupModFXWFeedback
            int a = modFXFeedback >> 1;
            int b = 2147483647 - ((a + 1073741824) >> 2) * 3;
            int c = multiply_32x32_rshift32(b, b);
            int d = multiply_32x32_rshift32(b, c);
            feedback = (int) 2147483648L - (d << 2);

            int squared = multiply_32x32_rshift32(feedback, feedback) << 1;
            int squared2 = multiply_32x32_rshift32(squared, squared) << 1;
            squared2 = multiply_32x32_rshift32(squared2, squared) << 1;
            squared2 = (multiply_32x32_rshift32(squared2, squared2) >> 4) * 23;
            postFXVolume[0] = multiply_32x32_rshift32(postFXVolume[0], 2147483647 - squared2);

            if (modFXType == ModFXType.FLANGER) {
                postFXVolume[0] <<= 1;
                delayOffset = kFlangerOffset;
                delayDepth = kFlangerAmplitude;
                waveType = LFO.LFOType.TRIANGLE;
            } else if (modFXType == ModFXType.WARBLE) {
                postFXVolume[0] <<= 1;
                delayOffset = kFlangerOffset + multiply_32x32_rshift32(kFlangerOffset, modFXOffset);
                delayDepth = multiply_32x32_rshift32(delayOffset, modFXDepth) << 1;
                waveType = LFO.LFOType.WARBLER;
            } else { // PHASER
                waveType = LFO.LFOType.SINE;
            }
        } else if (modFXType == ModFXType.CHORUS || modFXType == ModFXType.CHORUS_STEREO || modFXType == ModFXType.DIMENSION) {
            // setupChorus
            delayOffset = multiply_32x32_rshift32(kModFXMaxDelay, (modFXOffset >> 1) + 1073741824);
            delayDepth = multiply_32x32_rshift32(delayOffset, modFXDepth) << 2;
            waveType = (modFXType == ModFXType.DIMENSION) ? LFO.LFOType.TRIANGLE : LFO.LFOType.SINE;
            postFXVolume[0] = multiply_32x32_rshift32(postFXVolume[0], 1518500250) << 1;
        }

        // Processing
        if (modFXType == ModFXType.PHASER) {
            for (StereoSample s : buffer) {
                int lfo = modFXLFO.render(1, waveType, modFXRate);
                processOnePhaserSample(s, modFXDepth, feedback, lfo);
            }
        } else {
            for (StereoSample s : buffer) {
                int lfo1 = modFXLFO.render(1, waveType, modFXRate);
                int lfo2 = 0;
                if (modFXType == ModFXType.WARBLE) {
                    lfo2 = modFXLFOStereo.render(1, waveType, multiply_32x32_rshift32(modFXRate, (int)(0.97 * ONE)) << 1);
                } else {
                    lfo2 = -lfo1;
                }
                processOneModFXSample(s, delayOffset, delayDepth, feedback, lfo1, lfo2, modFXType);
            }
        }
    }

    private void processOneModFXSample(StereoSample s, int delayOffset, int delayDepth, int feedback, 
                                       int lfo1, int lfo2, ModFXType type) {
        int delayTime = multiply_32x32_rshift32(lfo1, delayDepth) + delayOffset;
        int strength2 = (delayTime & 65535) << 15;
        int strength1 = (65535 << 15) - strength2;
        int pos1 = modFXBufferWriteIndex - (delayTime >> 16);

        int outL = multiply_32x32_rshift32_rounded(modFXBuffer[pos1 & kModFXBufferIndexMask].l, strength1) +
                   multiply_32x32_rshift32_rounded(modFXBuffer[(pos1 - 1) & kModFXBufferIndexMask].l, strength2);

        // Right channel
        int delayTimeR = (type == ModFXType.DIMENSION || type == ModFXType.WARBLE) ? 
                         multiply_32x32_rshift32(lfo2, delayDepth) + delayOffset : delayTime;
        int strength2R = (delayTimeR & 65535) << 15;
        int strength1R = (65535 << 15) - strength2R;
        int pos1R = modFXBufferWriteIndex - (delayTimeR >> 16);

        int outR = multiply_32x32_rshift32_rounded(modFXBuffer[pos1R & kModFXBufferIndexMask].r, strength1R) +
                   multiply_32x32_rshift32_rounded(modFXBuffer[(pos1R - 1) & kModFXBufferIndexMask].r, strength2R);

        if (type == ModFXType.FLANGER) {
            outL = multiply_32x32_rshift32_rounded(outL, feedback) << 2;
            modFXBuffer[modFXBufferWriteIndex].l = outL + s.l;
            outR = multiply_32x32_rshift32_rounded(outR, feedback) << 2;
            modFXBuffer[modFXBufferWriteIndex].r = outR + s.r;
        } else if (type == ModFXType.WARBLE) {
            modFXBuffer[modFXBufferWriteIndex].l = multiply_32x32_rshift32_rounded(outL, feedback) + s.l;
            modFXBuffer[modFXBufferWriteIndex].r = multiply_32x32_rshift32_rounded(outR, feedback) + s.r;
            outL <<= 1; outR <<= 1;
        } else {
            outL <<= 1; outR <<= 1;
            modFXBuffer[modFXBufferWriteIndex].l = s.l;
            modFXBuffer[modFXBufferWriteIndex].r = s.r;
        }

        if (type == ModFXType.DIMENSION || type == ModFXType.WARBLE) {
            s.l = outL << 1;
            s.r = outR << 1;
        } else {
            s.l += outL;
            s.r += outR;
        }

        modFXBufferWriteIndex = (modFXBufferWriteIndex + 1) & kModFXBufferIndexMask;
    }

    private void processOnePhaserSample(StereoSample s, int depth, int feedback, int lfo) {
        int _a1 = 1073741824 - multiply_32x32_rshift32_rounded((int) (((long)lfo + 2147483648L) >> 1), depth);

        phaserMemory.l = s.l + (multiply_32x32_rshift32_rounded(phaserMemory.l, feedback) << 1);
        phaserMemory.r = s.r + (multiply_32x32_rshift32_rounded(phaserMemory.r, feedback) << 1);

        for (StereoSample ap : allpassMemory) {
            int oldL = phaserMemory.l;
            phaserMemory.l = (multiply_32x32_rshift32_rounded(phaserMemory.l, -_a1) << 2) + ap.l;
            ap.l = (multiply_32x32_rshift32_rounded(phaserMemory.l, _a1) << 2) + oldL;

            int oldR = phaserMemory.r;
            phaserMemory.r = (multiply_32x32_rshift32_rounded(phaserMemory.r, -_a1) << 2) + ap.r;
            ap.r = (multiply_32x32_rshift32_rounded(phaserMemory.r, _a1) << 2) + oldR;
        }

        s.l += phaserMemory.l;
        s.r += phaserMemory.r;
    }
}
