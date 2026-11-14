package com.selfmeditation.app;

import java.util.Random;

// Synthesizes struck sounds (gong, bell, wooden fish, drum) as mono float PCM in [-1, 1].
final class SoundSynth {

    static final int RATE = 44100;

    static final int GONG = 0;
    static final int BELL = 1;
    static final int WOOD = 2;
    static final int DRUM = 3;
    static final int DEEP = 4;

    // Below this a phone speaker or earbud produces almost no sound.
    private static final double MIN_AUDIBLE_HZ = 80.0;

    private SoundSynth() {}

    /**
     * @param pitchSemitones shift in semitones (negative = lower)
     * @param decayScale     multiplier of the ring length (1.0 = default)
     */
    static float[] render(int type, int pitchSemitones, float decayScale) {
        double pf = Math.pow(2.0, pitchSemitones / 12.0);
        float[] out;
        switch (type) {
            case BELL:
                out = partials(400 * pf,
                        new double[]{1, 2.0, 2.76, 5.4, 8.93},
                        new double[]{1, 0.6, 0.45, 0.25, 0.12},
                        3.0 * decayScale, 0.005);
                break;
            case WOOD:
                out = partials(700 * pf,
                        new double[]{1, 2.3, 3.9},
                        new double[]{1, 0.5, 0.3},
                        0.3 * decayScale, 0.002);
                addClick(out, decayScale);
                break;
            case DRUM:
                out = drum(pf, 0.8 * decayScale);
                break;
            case DEEP:
                // Exact harmonics of a low note: deep and round, with no beating partials.
                // The 2nd/3rd harmonics keep it audible on small phone speakers.
                out = partials(75 * pf,
                        new double[]{1, 2, 3},
                        new double[]{1, 0.5, 0.15},
                        3.5 * decayScale, 0.08);
                break;
            case GONG:
            default:
                // Few, nearly harmonic partials keep the low gong clear instead of muddy.
                out = partials(120 * pf,
                        new double[]{1, 2.01, 2.99, 4.2},
                        new double[]{1, 0.45, 0.2, 0.08},
                        4.0 * decayScale, 0.03);
                break;
        }
        normalize(out, 0.8f);
        return out;
    }

    private static float[] partials(double base, double[] ratios, double[] amps,
                                    double length, double attack) {
        int n = (int) (length * RATE);
        float[] out = new float[n];
        for (int k = 0; k < ratios.length; k++) {
            double freq = base * ratios[k];
            if (freq >= RATE / 2.0) continue;
            // Phone speakers and earbuds cannot reproduce very low notes, so those partials
            // would just vanish. Fold them up by octaves until they are audible; the ear still
            // hears the low pitch from the harmonics above. Low partials also sound quieter
            // (equal-loudness), so give them a boost.
            while (freq < MIN_AUDIBLE_HZ) freq *= 2;
            double boost = Math.min(3.0, Math.max(1.0, 180.0 / freq));
            // Higher partials die away faster, as in real struck metal.
            double tau = length / 5.0 / (1.0 + 0.4 * k);
            double w = 2 * Math.PI * freq / RATE;
            for (int i = 0; i < n; i++) {
                double t = i / (double) RATE;
                out[i] += (float) (amps[k] * boost * Math.exp(-t / tau) * Math.sin(w * i));
            }
        }
        int attackN = Math.max(1, (int) (attack * RATE));
        int tailN = Math.min(n, (int) (0.05 * RATE));
        for (int i = 0; i < Math.min(attackN, n); i++) {
            out[i] *= i / (float) attackN;
        }
        for (int i = 0; i < tailN; i++) {
            out[n - 1 - i] *= i / (float) tailN;
        }
        return out;
    }

    private static void addClick(float[] out, float decayScale) {
        Random rnd = new Random(7);
        int n = Math.min(out.length, 300);
        for (int i = 0; i < n; i++) {
            out[i] += (float) ((rnd.nextDouble() * 2 - 1) * 0.4 * Math.exp(-i / 60.0));
        }
    }

    private static float[] drum(double pf, double length) {
        int n = (int) (length * RATE);
        float[] out = new float[n];
        double phase = 0;
        double tau = length / 5.0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            // Pitch falls quickly from 150 Hz to 60 Hz, giving a soft "thump".
            double f = Math.max(MIN_AUDIBLE_HZ, (60 + 90 * Math.exp(-t / 0.04)) * pf);
            phase += 2 * Math.PI * f / RATE;
            out[i] = (float) (Math.exp(-t / tau) * Math.sin(phase));
        }
        int tailN = Math.min(n, (int) (0.03 * RATE));
        for (int i = 0; i < tailN; i++) {
            out[n - 1 - i] *= i / (float) tailN;
        }
        return out;
    }

    private static void normalize(float[] data, float peak) {
        float max = 0;
        for (float v : data) {
            max = Math.max(max, Math.abs(v));
        }
        if (max <= 0) return;
        float g = peak / max;
        for (int i = 0; i < data.length; i++) {
            data[i] *= g;
        }
    }
}
