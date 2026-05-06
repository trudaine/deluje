package org.chuck.deluge;

import java.io.*;
import java.util.Arrays;
import javax.sound.sampled.*;
import org.chuck.audio.util.Dx7Engine;
import org.chuck.audio.util.Dx7EngineLookupTables;
import org.chuck.deluge.xml.DelugeXmlParser;
import org.chuck.deluge.model.*;

/**
 * Isolate Dx7Engine output for a single MIDI note with the Tomsweep patch.
 * Saves WAV and runs FFT to verify fundamental frequency.
 */
public class Dx7SingleNoteAnalysis {
    private static final int SAMPLE_RATE = 44100;
    private static final int DURATION_SEC = 3;

    public static void main(String[] args) throws Exception {
        Dx7EngineLookupTables.init(SAMPLE_RATE);

        // Load the patch from Dx7A.xml
        ProjectModel project;
        try (InputStream is = Dx7SingleNoteAnalysis.class.getResourceAsStream("/SONGS/Dx7A.xml")) {
            project = DelugeXmlParser.parseSong(is, "Dx7A");
        }
        SynthTrackModel track = (SynthTrackModel) project.getTracks().get(1);
        String hex = track.getDx7Patch();
        System.out.println("DX7 patch hex length: " + hex.length() + " chars = " + (hex.length()/2) + " bytes");
        byte[] patch = new byte[hex.length() / 2];
        for (int i = 0; i < patch.length; i++) {
            patch[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }

        // Decode key fields
        int algoIdx = patch[134] & 0xFF;
        int feedback = patch[135] & 0xFF;
        int transpose = patch[144] & 0xFF;
        System.out.println("Algorithm: " + algoIdx + "  Feedback: " + feedback + "  Transpose: " + transpose);

        // Print per-operator info
        for (int op = 0; op < 6; op++) {
            int off = op * 21;
            int mode = patch[off + 17] & 0xFF;
            int coarse = patch[off + 18] & 0xFF;
            int fine = patch[off + 19] & 0xFF;
            int detune = patch[off + 20] & 0xFF;
            int outlevel = patch[off + 16] & 0xFF;
            int oscsync = patch[136] & 0xFF;
            int opSw = (patch[155] >> op) & 1;
            System.out.printf("  OP%d: mode=%d coarse=%d fine=%d detune=%d outlevel=%d opSw=%d%n",
                op, mode, coarse, fine, detune, outlevel, opSw);
        }

            // Check oscFreq via *actual Dx7Engine* for each operator
        System.out.println("\n--- Per-operator base frequencies from oscFreq() ---");
        // Since oscFreq is private, we approximate by looking at the actual output
        // Use Dx7EngineLookupTables log domain math
        for (int op = 0; op < 6; op++) {
            int off = op * 21;
            int mode = patch[off + 17] & 0xFF;
            int coarse = patch[off + 18] & 0xFF;
            int fine = patch[off + 19] & 0xFF;
            int detune = patch[off + 20] & 0xFF;
            int midiRef = 81; // reference note

            int logFreq = Dx7EngineLookupTables.dxNoteToFreq(midiRef);
            System.out.printf("  OP%d: dxNoteToFreq(%d) = %d (Q24) = %.3f Hz%n",
                op, midiRef, logFreq, Math.pow(2, logFreq / (double)(1<<24)));

            if (mode == 1) {
                // Fixed mode formula
                int fLogfreq = (4458616 * ((coarse & 3) * 100 + fine)) >> 3;
                double fFreq = Math.pow(2, fLogfreq / (double)(1<<24));
                System.out.printf("  OP%d Fixed: coarse=%d fine=%d logfreq=%d -> %.2f Hz%n",
                    op, coarse, fine, fLogfreq, fFreq);
            } else {
                // Ratio mode — manually inline the coarsemul array
                int[] coarsemul = {
                    -16777216, 0,        16777216, 26591258, 33554432, 38955489, 43368474, 47099600,
                     50331648, 53182516, 55732705, 58039632, 60145690, 62083076, 63876816, 65546747,
                     67108864, 68576247, 69959732, 71268397, 72509921, 73690858, 74816848, 75892776,
                     76922906, 77910978, 78860292, 79773775, 80654032, 81503396, 82323963, 83117622
                };
                double detuneRatio = 0.0209 * Math.exp(-0.396 * ((double)logFreq / (1 << 24))) / 7;
                int randomScaled = 0; // ignoring random for now
                int rLogfreq = logFreq; // start from note's base log-frequency
                rLogfreq += (int)(detuneRatio * logFreq * (detune - 7 + randomScaled));
                rLogfreq += coarsemul[coarse & 31];
                if (fine != 0) {
                    rLogfreq += (int)Math.floor(24204406.323123 * Math.log(1 + 0.01 * fine) + 0.5);
                }
                double rFreq = Math.pow(2, rLogfreq / (double)(1<<24));
                double baseFreq = Math.pow(2, logFreq / (double)(1<<24));
                System.out.printf("  OP%d Ratio: coarse=%d fine=%d detune=%d logfreq=%d -> %.2f Hz (base %.2f Hz, ratio %.4f)%n",
                    op, coarse, fine, detune, rLogfreq, rFreq, baseFreq, rFreq/baseFreq);
            }
        }

        // Test multiple MIDI notes
        int[] testNotes = {81, 79, 60, 69};
        for (int midiNote : testNotes) {
            testNote(patch, midiNote);
        }
    }

    static void testNote(byte[] patch, int midiNote) throws Exception {
        Dx7Engine engine = new Dx7Engine(SAMPLE_RATE);
        engine.loadPatch(patch);

        int numSamples = SAMPLE_RATE * DURATION_SEC;
        float[] output = new float[numSamples];

        engine.noteOn(midiNote, 100);

        for (int i = 0; i < numSamples; i++) {
            output[i] = engine.tick();
        }

        engine.noteOff();

        // Analyze
        double peak = 0;
        double rms = 0;
        for (int i = 0; i < numSamples; i++) {
            peak = Math.max(peak, Math.abs(output[i]));
            rms += output[i] * output[i];
        }
        rms = Math.sqrt(rms / numSamples);

        // Find fundamental frequency via autocorrelation (steady state: last 1 second)
        int steadyStart = numSamples - SAMPLE_RATE;
        float[] steady = Arrays.copyOfRange(output, steadyStart, numSamples);

        double expectedFreq = 440.0 * Math.pow(2, (midiNote - 69) / 12.0);
        System.out.printf("\nMIDI %d (expected %.2f Hz): peak=%.4f RMS=%.4f%n",
            midiNote, expectedFreq, peak, rms);

        // Autocorrelation-based pitch detection
        double[] freqs = detectFreqs(steady, SAMPLE_RATE);
        for (int i = 0; i < Math.min(3, freqs.length); i++) {
            System.out.printf("  Freq[%d]: %.2f Hz (ratio to expected: %.3f)%n",
                i, freqs[i], freqs[i] / expectedFreq);
        }

        // Also check for subharmonics
        for (int div = 2; div <= 10; div++) {
            double sub = expectedFreq / div;
            for (double f : freqs) {
                if (Math.abs(f - sub) / sub < 0.05) {
                    System.out.printf("  *** Subharmonic detected: expected/%.0f = %.2f Hz matches detected %.2f Hz%n",
                        (double)div, sub, f);
                }
            }
        }

        // Save WAV
        saveWav(output, String.format("target/dx7_note%d.wav", midiNote));
    }

    static double[] detectFreqs(float[] samples, int sampleRate) {
        int maxShift = sampleRate / 40; // 40 Hz minimum
        int minShift = sampleRate / 2000; // 2000 Hz maximum
        if (minShift < 2) minShift = 2;

        double[] correlation = new double[maxShift - minShift];
        int bestShift = minShift;
        double bestCorr = 0;

        for (int shift = minShift; shift < maxShift; shift++) {
            double corr = 0;
            int count = 0;
            for (int i = 0; i + shift < samples.length && i < sampleRate / 10; i++) {
                corr += samples[i] * samples[i + shift];
                count++;
            }
            if (count > 0) {
                corr /= count;
                correlation[shift - minShift] = corr;
                if (corr > bestCorr) {
                    bestCorr = corr;
                    bestShift = shift;
                }
            }
        }

        // Find peaks in correlation
        int numPeaks = Math.min(5, maxShift - minShift);
        double[] freqs = new double[numPeaks];
        boolean[] used = new boolean[correlation.length];
        for (int p = 0; p < numPeaks; p++) {
            int bestIdx = -1;
            double bestVal = -1;
            for (int i = 0; i < correlation.length; i++) {
                if (!used[i] && correlation[i] > bestVal) {
                    bestVal = correlation[i];
                    bestIdx = i;
                }
            }
            if (bestIdx >= 0) {
                used[bestIdx] = true;
                int shift = bestIdx + minShift;
                freqs[p] = sampleRate / (double) shift;
            }
        }
        return freqs;
    }

    static void saveWav(float[] mono, String path) throws Exception {
        File f = new File(path);
        f.getParentFile().mkdirs();
        short[] pcm = new short[mono.length];
        for (int i = 0; i < mono.length; i++) {
            float v = Math.max(-1f, Math.min(1f, mono[i]));
            pcm[i] = (short) (v * 32767f);
        }
        byte[] bytes = new byte[pcm.length * 2];
        for (int i = 0; i < pcm.length; i++) {
            bytes[i * 2] = (byte) (pcm[i] & 0xFF);
            bytes[i * 2 + 1] = (byte) ((pcm[i] >> 8) & 0xFF);
        }
        AudioFormat fmt = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        AudioInputStream ais = new AudioInputStream(
            new ByteArrayInputStream(bytes), fmt, pcm.length);
        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, f);
        System.out.println("  Saved " + f.getAbsolutePath());
    }
}
