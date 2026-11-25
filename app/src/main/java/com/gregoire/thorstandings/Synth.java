package com.gregoire.thorstandings;

import java.util.Arrays;
import java.util.Random;
import java.util.function.DoubleUnaryOperator;

/**
 * Sound synthesis for the goal sounds. Pure Java (nothing from Android) so every sound can be rendered
 * and measured on a computer. Everything is built from a few pieces: a brass voice (horns, fanfare), bells,
 * filtered noise (crowd, net, bat), an organ, and a stadium reverb that puts it all in a big room.
 * Output: 44.1 kHz stereo, 16-bit, interleaved (L R L R ...), already loud and soft-limited.
 */
final class Synth {
    static final int RATE = 44100;
    static final int COUNT = 10;   // sounds 0..9 (see Horn.SOUND_NAMES)
    private static final double TAU = 2 * Math.PI;

    /**
     * How loud everything is made: the average level before the soft limiter, and how hard a single loud peak may be squashed.
     * 0.60 / 3.4 gives about -9 dBFS on average (the 3.29 values, 0.24 / 3.5, were about -14 dBFS and far too quiet on the
     * Thor's small speakers). A little grit on the peaks is what makes a horn sound big.
     */
    private static final double TARGET_RMS = 0.60, MAX_DRIVE = 3.4;

    private Synth() {}

    /** Sound `type` as interleaved stereo PCM. */
    static short[] render(int type) {
        switch (type) {
            case 0: return finish(arena());
            case 1: return finish(deep());
            case 2: return finish(siren());
            case 3: return finish(chime());
            case 4: return finish(crowd());
            case 5: return finish(organ());
            case 6: return finish(swish());
            case 7: return finish(fanfare());
            case 8: return finish(airhorn());
            case 9: return finish(cannon());
            default: return finish(arena());
        }
    }

    // ------------------------------------------------------------------ the sounds

    /** Arena goal horn: a big three-note brass blast with a "bwaaah" pitch scoop, air at the start, in a stadium. */
    private static float[][] arena() {
        float[] x = buf(3.0);
        double[][] voices = {{103.83, 0.55}, {207.65, 1.0}, {311.13, 0.85}, {415.3, 0.35}};   // Ab2 Ab3 Eb4 Ab4: open fifths = "horn"
        for (int k = 0; k < voices.length; k++) {
            brass(x, voices[k][0] * 0.998, 0, 2.6, voices[k][1], 0.05, 0.7, 0.07, 0.9, 0.004, k);
            brass(x, voices[k][0] * 1.003, 0, 2.6, voices[k][1] * 0.8, 0.05, 0.7, 0.07, 0.9, 0.004, k + 10);   // second horn, slightly sharp: chorus
        }
        noise(x, 0.0, 0.12, 0.25, 900, 3500, 1);   // air hitting the bell
        tremolo(x, 5.2, 0.07);
        return space(x, 0.30, 0.86, 0.35, 1.4);
    }

    /** Deep horn: a foghorn. Mostly harmonics, so it still reads on a small speaker. */
    private static float[][] deep() {
        float[] x = buf(3.4);
        double[][] voices = {{65.41, 0.8}, {98.0, 0.9}, {130.81, 1.0}, {196.0, 0.5}};   // C2 G2 C3 G3
        for (int k = 0; k < voices.length; k++) {
            brass(x, voices[k][0] * 0.997, 0, 3.0, voices[k][1], 0.12, 0.9, 0.05, 0.7, 0.003, k + 20);
            brass(x, voices[k][0] * 1.004, 0, 3.0, voices[k][1] * 0.7, 0.12, 0.9, 0.05, 0.7, 0.003, k + 30);
        }
        noise(x, 0.0, 0.2, 0.15, 300, 1500, 2);
        return space(x, 0.34, 0.90, 0.4, 1.8);
    }

    /**
     * Goal siren: a brass stab for the hit, then a rotor siren that spins up from a low growl, wails (fast rise, slow
     * fall, like a real rotating siren), and winds down, all with a slap-back echo off the far boards.
     */
    private static float[][] siren() {
        double seconds = 3.5, spinUp = 0.65, windDown = 1.0;
        float[] x = buf(seconds + 1.0);
        int n = (int) (seconds * RATE);
        double ph = 0, ph2 = 0;
        double wailEnd = seconds - windDown, fEnd = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double f, amp;
            if (t < spinUp) {                                   // motor spinning up: 110 -> 640 Hz, getting louder
                double u = t / spinUp;
                f = 110 * Math.pow(640.0 / 110, u * (2 - u));   // ease-out
                amp = 0.45 + 0.55 * u;
            } else if (t < wailEnd) {                           // the wail: shoots up in 0.3 s, sinks over 0.55 s
                double u = ((t - spinUp) % 0.85) / 0.85;
                double sh = u < 0.35 ? Math.sin(u / 0.35 * Math.PI / 2) : Math.cos((u - 0.35) / 0.65 * Math.PI / 2);
                f = 640 + 400 * sh;
                amp = 1;
                fEnd = f;
            } else {                                            // motor stopping: pitch falls away
                double u = (t - wailEnd) / windDown;
                f = Math.max(150, fEnd * Math.pow(150.0 / Math.max(fEnd, 151), u));
                amp = Math.pow(1 - u, 1.3);
            }
            f *= 1 + 0.006 * Math.sin(TAU * 6.3 * t);           // rotor wobble
            ph += TAU * f / RATE;
            ph2 += TAU * f * 1.007 / RATE;                      // a second, slightly sharp rotor: the buzz you hear in real ones
            double v = 0.55 * Math.sin(0.5 * ph);               // sub layer, an octave down, for weight
            for (int h = 1; h <= 14; h++) {
                double w = Math.pow(h, -0.85);
                if (h * f > 9000) break;
                v += w * Math.sin(h * ph) + 0.6 * w * Math.sin(h * ph2);
            }
            double env = Math.min(1, t / 0.04);
            env *= 0.92 + 0.08 * Math.sin(TAU * 22 * t);        // blade chop
            x[i] = (float) (Math.tanh(v * 0.42) * env * amp);
        }
        // the hit: a short two-note brass stab on the first beat
        brass(x, 207.65, 0, 0.42, 0.26, 0.012, 0.18, 0.06, 0.95, 0.003, 81);
        brass(x, 311.13, 0, 0.42, 0.2, 0.012, 0.18, 0.06, 0.95, 0.003, 82);
        echo(x, 0.19, 0.36);
        return space(x, 0.24, 0.84, 0.3, 0.8);
    }

    /** Slap-back echo (the sound bouncing off the far end of the rink). */
    private static void echo(float[] x, double delay, double feedback) {
        int d = (int) (delay * RATE);
        for (int i = d; i < x.length; i++) x[i] += (float) (x[i - d] * feedback);
    }

    /** Victory chime: four bells climbing, then the last one ringing out. */
    private static float[][] chime() {
        float[] x = buf(3.2);
        double[] notes = {523.25, 659.25, 783.99, 1046.5, 1318.5};   // C5 E5 G5 C6 E6
        double[] start = {0, 0.13, 0.26, 0.39, 0.55};
        for (int k = 0; k < notes.length; k++) bell(x, notes[k], start[k], k == notes.length - 1 ? 2.4 : 1.2, k == notes.length - 1 ? 1.0 : 0.7);
        return space(x, 0.38, 0.88, 0.25, 1.6);
    }

    /** Crowd cheer: a roar that swells, clapping, a couple of whistles, and the whole building. */
    private static float[][] crowd() {
        double seconds = 3.4;
        float[] x = buf(seconds);
        Random r = new Random(3);
        int n = x.length;
        float[] white = new float[n];
        for (int i = 0; i < n; i++) white[i] = (float) (r.nextDouble() * 2 - 1);
        // "aaah" and "ooh" vowels: noise through resonant filters at typical vowel frequencies = a crowd of voices
        float[] a1 = bandpass(white, 700, 2.2), a2 = bandpass(white, 1250, 2.8), a3 = bandpass(white, 2600, 3.0), lo = bandpass(white, 330, 1.5);
        double[] lfoF = {0.7, 1.3, 2.1, 3.3}, lfoP = {0.4, 2.1, 4.0, 5.3};
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double swell = Math.min(1, t / 0.45) * (t > seconds - 1.3 ? Math.max(0, (seconds - t) / 1.3) : 1);
            double wob = 0.78;
            for (int k = 0; k < lfoF.length; k++) wob += 0.06 * Math.sin(TAU * lfoF[k] * t + lfoP[k]);   // many people, never steady
            double v = (1.7 * a1[i] + 1.2 * a2[i] + 0.22 * a3[i] + 1.5 * lo[i]) * wob;
            x[i] = (float) (v * swell * 0.9);
        }
        // applause underneath: random short claps, more of them as it builds
        float[] clapNoise = bandpass(white, 1700, 0.8);   // claps are dull "pats", not white-noise hiss
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double rate = 40 * Math.min(1, t / 0.8) * (t > seconds - 1.3 ? Math.max(0, (seconds - t) / 1.3) : 1);
            if (r.nextDouble() < rate / RATE) {
                int len = (int) (0.018 * RATE);
                double g = 0.25 + 0.45 * r.nextDouble();
                for (int k = 0; k < len && i + k < n; k++) x[i + k] += (float) (clapNoise[i + k] * Math.exp(-k / (0.004 * RATE)) * g * 1.3);
            }
        }
        whistle(x, 0.55, 0.45, 2650, 0.22);
        whistle(x, 1.65, 0.35, 3050, 0.18);
        return space(x, 0.30, 0.90, 0.3, 1.4);
    }

    /** Stadium organ "Charge!": da da da DAA da DAAA, with a little key click and room. */
    private static float[][] organ() {
        double[][] notes = {{392.0, 0.15}, {523.25, 0.15}, {659.25, 0.15}, {783.99, 0.42}, {659.25, 0.17}, {783.99, 1.1}};
        double total = 0;
        for (double[] nt : notes) total += nt[1] + 0.035;
        float[] x = buf(total + 0.1);
        double at = 0;
        double[] draw = {0.55, 1.0, 0.75, 0.55, 0.35, 0.22, 0.14};   // harmonics 0.5x,1x,1.5x(≈3rd),2x,3x,4x,5x like drawbars
        double[] mult = {0.5, 1, 3, 2, 4, 6, 8};
        for (double[] nt : notes) {
            int s0 = (int) (at * RATE), len = (int) (nt[1] * RATE);
            for (int i = 0; i < len && s0 + i < x.length; i++) {
                double t = (double) i / RATE;
                double env = Math.min(1, t / 0.012) * Math.min(1, (nt[1] - t) / 0.05);
                if (nt[1] > 0.8) env *= 1 - 0.18 * Math.min(1, t / 0.9);   // the long note leans down a little
                double vib = 1 + 0.004 * Math.sin(TAU * 6.4 * t);          // rotating speaker
                double v = 0;
                for (int h = 0; h < draw.length; h++) v += draw[h] * Math.sin(TAU * nt[0] * mult[h] * vib * t);
                v *= 0.88 + 0.12 * Math.sin(TAU * 6.4 * t + 1.0);
                x[s0 + i] += (float) (v * env);
            }
            noise(x, at, 0.012, 0.35, 2500, 6000, 7 + (int) at);   // key click
            at += nt[1] + 0.035;
        }
        return space(x, 0.28, 0.84, 0.35, 1.3);
    }

    /** Swish: ball through the net, a thump, then the crowd going "oooh". */
    private static float[][] swish() {
        double seconds = 1.9;
        float[] x = buf(seconds);
        Random r = new Random(11);
        int n = x.length;
        float[] white = new float[n];
        for (int i = 0; i < n; i++) white[i] = (float) (r.nextDouble() * 2 - 1);
        // the net: a bright band sweeping down fast
        float[] net = bandpassSweep(white, t -> 7500 - 5200 * Math.min(1, t / 0.28), 1.4);
        for (int i = 0; i < (int) (0.38 * RATE); i++) {
            double t = (double) i / RATE;
            double env = Math.pow(Math.sin(Math.PI * Math.min(1, t / 0.38)), 1.5);
            x[i] += (float) (net[i] * env * 1.6);
        }
        // the ball dropping on the floor: soft low thump
        for (int i = 0; i < (int) (0.25 * RATE); i++) {
            double t = (double) i / RATE;
            x[i + (int) (0.3 * RATE)] += (float) (Math.sin(TAU * (150 - 70 * t) * t) * Math.exp(-t * 18) * 0.5);
        }
        // "oooh": crowd vowel swelling after the shot
        float[] o1 = bandpass(white, 480, 2.5), o2 = bandpass(white, 900, 3.0), o3 = bandpass(white, 280, 1.5);
        for (int i = (int) (0.22 * RATE); i < n; i++) {
            double t = (double) (i - 0.22 * RATE) / RATE, rem = seconds - 0.22;
            double env = Math.min(1, t / 0.3) * Math.max(0, (rem - t) / (rem * 0.85));
            x[i] += (float) ((1.8 * o1[i] + 1.1 * o2[i] + 1.4 * o3[i]) * env * 0.9);
        }
        return space(x, 0.26, 0.85, 0.3, 0.9);
    }

    /** Brass fanfare: "ta-ta-ta-TAAA" with a timpani hit and a held major chord. */
    private static float[][] fanfare() {
        float[] x = buf(3.6);
        double[][] intro = {{196.0, 0.0, 0.16}, {261.63, 0.17, 0.16}, {329.63, 0.34, 0.16}};   // G3 C4 E4
        for (double[] nt : intro) {
            brass(x, nt[0], nt[1], nt[2] + 0.04, 0.9, 0.02, 0.05, 0.02, 0.85, 0.005, (int) nt[0]);
            brass(x, nt[0] * 2, nt[1], nt[2] + 0.04, 0.45, 0.02, 0.05, 0.02, 0.85, 0.005, (int) nt[0] + 1);
        }
        double[] chord = {261.63, 329.63, 392.0, 523.25, 659.25};   // C4 E4 G4 C5 E5
        for (int k = 0; k < chord.length; k++) {
            brass(x, chord[k] * 0.998, 0.52, 2.6, 0.9 - 0.08 * k, 0.04, 0.9, 0.025, 0.85, 0.005, 40 + k);
            brass(x, chord[k] * 1.003, 0.52, 2.6, 0.6 - 0.05 * k, 0.04, 0.9, 0.025, 0.85, 0.005, 50 + k);
        }
        timpani(x, 0.52, 98.0, 1.0);
        timpani(x, 0.52, 49.0, 0.7);
        return space(x, 0.33, 0.88, 0.3, 1.6);
    }

    /** Air horn: the classic "dwaa dwaa DWAAAA", buzzy and loud. */
    private static float[][] airhorn() {
        float[] x = buf(2.9);
        double[] start = {0.0, 0.52, 1.04}, dur = {0.42, 0.42, 1.5};
        for (int b = 0; b < 3; b++) {
            double d = dur[b];
            for (int k = 0; k < 2; k++) {
                double f0 = k == 0 ? 466.16 : 587.33;   // Bb4 + D5: the two-tone can horn
                brass(x, f0, start[b], d, 1.0, 0.012, 0.06, 0.05, 1.0, 0.0, 60 + b * 2 + k);
                brass(x, f0 * 1.006, start[b], d, 0.7, 0.012, 0.06, 0.05, 1.0, 0.0, 70 + b * 2 + k);
            }
            noise(x, start[b], 0.05, 0.3, 1500, 5000, 90 + b);
        }
        for (int i = 0; i < x.length; i++) x[i] = (float) Math.tanh(x[i] * 0.22);   // push it into a buzzy, hard sound
        return space(x, 0.2, 0.80, 0.35, 1.0);
    }

    /** Any interleaved stereo PCM (a recorded file) to the same loudness, soft peaks, clean ends as the built-in sounds. */
    static short[] level(short[] pcm) {
        int n = pcm.length / 2;
        float[][] st = new float[2][n];
        for (int i = 0; i < n; i++) { st[0][i] = pcm[i * 2] / 32768f; st[1][i] = pcm[i * 2 + 1] / 32768f; }
        return finish(st);
    }

    /** Stadium cannon (touchdowns): a flat crack, a thump that drops in pitch, and a boom that rolls around the stands. */
    private static float[][] cannon() {
        float[] x = buf(1.8);
        Random r = new Random(21);
        int n = (int) (0.9 * RATE);
        double lp = 0, lp2 = 0, ph = 0;
        float[] white = new float[n];
        for (int i = 0; i < n; i++) white[i] = (float) (r.nextDouble() * 2 - 1);
        float[] mid = bandpass(white, 480, 0.9);   // the part of the boom a small speaker can actually play
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double nz = white[i];
            lp += (0.07 + 0.25 * Math.exp(-t * 22)) * (nz - lp);    // dark noise that gets darker: the blast
            lp2 += 0.5 * (nz - lp2);                                  // brighter noise: the crack
            ph += TAU * (40 + 95 * Math.exp(-t * 14)) / RATE;         // the thump falls in pitch
            double body = 0.55 * Math.tanh(5.0 * Math.sin(ph)) * Math.exp(-t * 6);   // pushed hard into saturation: makes the harmonics a small speaker can play
            double blast = lp * 3.4 * Math.exp(-t * 7);
            double crack = (lp2 - lp) * Math.exp(-t * 80) * 1.3;
            x[i] = (float) (body + blast + crack + mid[i] * 2.2 * Math.exp(-t * 7)) * (float) Math.min(1, t / 0.0004);
        }
        echo(x, 0.31, 0.3);   // the boom coming back off the far stands
        return space(x, 0.42, 0.90, 0.45, 1.6);
    }

    /** Bat crack for home runs: a sharp "tock" with wood ring and a snap of noise. */
    static short[] crack() {
        float[] x = buf(0.35);
        Random r = new Random(7);
        int n = (int) (0.2 * RATE);
        double prev = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double nz = r.nextDouble() * 2 - 1;
            double hp = nz - prev * 0.7; prev = nz;
            double wood = Math.sin(TAU * 1850 * t) * 0.5 + Math.sin(TAU * 720 * t) * 0.6 + Math.sin(TAU * 330 * t) * 0.5;
            x[i] = (float) ((hp * Math.exp(-t * 90) * 1.1 + wood * Math.exp(-t * 38)) * Math.min(1, t / 0.0008));
        }
        return finish(space(x, 0.18, 0.7, 0.4, 0.5));
    }

    // ------------------------------------------------------------------ building blocks

    private static float[] buf(double seconds) { return new float[(int) Math.round(seconds * RATE)]; }

    private static double sq(double v) { return v * v; }

    /**
     * One brass-like voice added into `out`: a stack of harmonics shaped like a horn's bell (strong around 650 and
     * 1500 Hz), dull at the start and brighter as it swells, with a pitch scoop up to the note and a little vibrato.
     */
    private static void brass(float[] out, double f0, double start, double dur, double amp, double attack, double release,
                              double scoop, double bright, double vib, int seed) {
        int s0 = (int) (start * RATE), n = (int) (dur * RATE);
        int top = (int) Math.max(1, Math.min(26, 9500 / f0));
        double[] dark = new double[top + 1], lite = new double[top + 1];
        for (int h = 1; h <= top; h++) {
            double f = h * f0;
            double formant = 1 + 1.8 * Math.exp(-sq((f - 650) / 450)) + 1.0 * Math.exp(-sq((f - 1500) / 700));
            dark[h] = formant * Math.pow(h, -1.6);
            lite[h] = formant * Math.pow(h, -(1.15 - 0.55 * bright));
        }
        Random r = new Random(seed * 7919L + 13);
        double ph = 0, vph = r.nextDouble() * TAU;
        double jitterTarget = 0, jitter = 0;
        for (int i = 0; i < n; i++) {
            int idx = s0 + i;
            if (idx >= out.length) break;
            double t = (double) i / RATE;
            double a = 1 - Math.exp(-t / Math.max(0.004, attack / 3));
            if (t > dur - release) a *= Math.max(0, (dur - t) / release);
            double sw = Math.min(1, t / (attack * 2.5 + 0.18));
            if ((i & 1023) == 0) jitterTarget = (r.nextDouble() - 0.5) * 0.004;   // a lip never holds the pitch perfectly
            jitter += (jitterTarget - jitter) * 0.002;
            double f = f0 * (1 - scoop * Math.exp(-t / 0.07)) * (1 + vib * Math.sin(TAU * 5.2 * t + vph) + jitter);
            ph += TAU * f / RATE;
            if (ph > TAU) ph -= TAU;
            double v = 0;
            for (int h = 1; h <= top; h++) v += (dark[h] + sw * (lite[h] - dark[h])) * Math.sin(h * ph);
            out[idx] += (float) (v * a * amp);
        }
    }

    /** A struck bell: several partials that don't line up (that is what makes it a bell), each dying at its own speed. */
    private static void bell(float[] out, double f0, double start, double dur, double amp) {
        double[] ratio = {1.0, 2.0, 2.76, 4.07, 5.4, 6.8};
        double[] gain = {1.0, 0.55, 0.45, 0.3, 0.2, 0.12};
        double[] decay = {1.6, 2.4, 3.2, 4.5, 6.0, 8.0};
        int s0 = (int) (start * RATE), n = (int) (dur * RATE);
        for (int i = 0; i < n && s0 + i < out.length; i++) {
            double t = (double) i / RATE;
            double v = 0;
            for (int k = 0; k < ratio.length; k++) v += gain[k] * Math.sin(TAU * f0 * ratio[k] * t) * Math.exp(-t * decay[k]);
            double fadeOut = t > dur - 0.2 ? Math.max(0, (dur - t) / 0.2) : 1;
            out[s0 + i] += (float) (v * Math.min(1, t / 0.002) * fadeOut * amp * 0.5);
        }
    }

    private static void timpani(float[] out, double start, double f0, double amp) {
        int s0 = (int) (start * RATE), n = (int) (1.2 * RATE);
        double ph = 0;
        for (int i = 0; i < n && s0 + i < out.length; i++) {
            double t = (double) i / RATE;
            ph += TAU * (f0 * (1 + 0.5 * Math.exp(-t * 25))) / RATE;   // the drum head drops in pitch right after the hit
            out[s0 + i] += (float) ((Math.sin(ph) + 0.4 * Math.sin(2.3 * ph)) * Math.exp(-t * 4.5) * amp * 1.6 * Math.min(1, t / 0.002));
        }
    }

    /** A burst of band-filtered noise (air, clicks). */
    private static void noise(float[] out, double start, double dur, double amp, double lo, double hi, int seed) {
        int n = (int) (dur * RATE), s0 = (int) (start * RATE);
        Random r = new Random(seed * 31L + 5);
        float[] w = new float[n];
        for (int i = 0; i < n; i++) w[i] = (float) (r.nextDouble() * 2 - 1);
        float[] f = bandpass(w, Math.sqrt(lo * hi), Math.sqrt(lo * hi) / Math.max(1, hi - lo) * 2.5);
        for (int i = 0; i < n && s0 + i < out.length; i++) {
            double t = (double) i / n;
            out[s0 + i] += (float) (f[i] * amp * 2.0 * Math.min(1, t * 12) * (1 - t));
        }
    }

    private static void whistle(float[] out, double start, double dur, double f0, double amp) {
        int s0 = (int) (start * RATE), n = (int) (dur * RATE);
        double ph = 0;
        for (int i = 0; i < n && s0 + i < out.length; i++) {
            double t = (double) i / RATE;
            ph += TAU * (f0 + 160 * Math.sin(t * 14) + 60 * Math.sin(t * 61)) / RATE;   // the trill
            double env = Math.min(1, t / 0.03) * Math.min(1, (dur - t) / 0.08);
            out[s0 + i] += (float) (Math.sin(ph) * env * amp);
        }
    }

    private static void tremolo(float[] x, double hz, double depth) {
        for (int i = 0; i < x.length; i++) x[i] *= (float) (1 - depth + depth * Math.sin(TAU * hz * i / RATE));
    }

    /** Resonant band-pass (RBJ biquad), unity gain at the centre. */
    private static float[] bandpass(float[] in, double f0, double q) {
        double w0 = TAU * f0 / RATE, alpha = Math.sin(w0) / (2 * q);
        double a0 = 1 + alpha, b0 = alpha / a0, b2 = -alpha / a0, a1 = -2 * Math.cos(w0) / a0, a2 = (1 - alpha) / a0;
        float[] y = new float[in.length];
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < in.length; i++) {
            double x0 = in[i];
            double v = b0 * x0 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x0; y2 = y1; y1 = v;
            y[i] = (float) v;
        }
        return y;
    }

    /** Band-pass whose centre moves over time (the net "swish"). */
    private static float[] bandpassSweep(float[] in, DoubleUnaryOperator centre, double q) {
        float[] y = new float[in.length];
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < in.length; i++) {
            double w0 = TAU * centre.applyAsDouble((double) i / RATE) / RATE, alpha = Math.sin(w0) / (2 * q);
            double a0 = 1 + alpha, b0 = alpha / a0, b2 = -alpha / a0, a1 = -2 * Math.cos(w0) / a0, a2 = (1 - alpha) / a0;
            double x0 = in[i];
            double v = b0 * x0 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x0; y2 = y1; y1 = v;
            y[i] = (float) v;
        }
        return y;
    }

    /**
     * Puts a mono sound in a big room (Freeverb-style: 8 combs + 4 all-passes per ear, the two ears slightly different
     * so it sounds wide). wet = how much room, room = size, damp = how fast the highs die, tail = extra seconds.
     */
    private static float[][] space(float[] dry, double wet, double room, double damp, double tail) {
        int n = dry.length + (int) (tail * RATE);
        float[] in = Arrays.copyOf(dry, n);
        int[] comb = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        int[] ap = {556, 441, 341, 225};
        float[][] out = new float[2][n];
        for (int ch = 0; ch < 2; ch++) {
            int spread = ch * 23;
            float[][] cb = new float[comb.length][];
            int[] ci = new int[comb.length];
            double[] store = new double[comb.length];
            for (int k = 0; k < comb.length; k++) cb[k] = new float[comb[k] + spread];
            float[][] ab = new float[ap.length][];
            int[] ai = new int[ap.length];
            for (int k = 0; k < ap.length; k++) ab[k] = new float[ap[k] + spread];
            for (int i = 0; i < n; i++) {
                double inp = in[i] * 0.06, sum = 0;
                for (int k = 0; k < comb.length; k++) {
                    double o = cb[k][ci[k]];
                    store[k] = o * (1 - damp) + store[k] * damp;
                    cb[k][ci[k]] = (float) (inp + store[k] * room);
                    if (++ci[k] >= cb[k].length) ci[k] = 0;
                    sum += o;
                }
                for (int k = 0; k < ap.length; k++) {   // all-pass: smears the echoes so they don't ring like a spring
                    double b = ab[k][ai[k]];
                    double o = b - sum;
                    ab[k][ai[k]] = (float) (sum + b * 0.5);
                    sum = o;
                    if (++ai[k] >= ab[k].length) ai[k] = 0;
                }
                out[ch][i] = (float) (in[i] * (1 - wet * 0.35) + sum * wet * 1.8);
            }
        }
        return out;
    }

    /**
     * Float stereo -> loud 16-bit interleaved PCM. Levels every sound by its average loudness (not its loudest
     * instant, so a bell doesn't come out quiet), rounds off the peaks with a soft limiter instead of clipping,
     * cuts the inaudible end of the reverb, and fades the ends so there are no clicks.
     */
    private static short[] finish(float[][] st) {
        int n = st[0].length;
        double peak = 1e-9;
        for (int ch = 0; ch < 2; ch++) for (int i = 0; i < n; i++) peak = Math.max(peak, Math.abs(st[ch][i]));
        int end = n;   // last audible frame (-54 dB from the peak) + a little tail
        while (end > 1 && Math.max(Math.abs(st[0][end - 1]), Math.abs(st[1][end - 1])) < peak * 0.002) end--;
        end = Math.min(n, end + (int) (0.1 * RATE));
        double sum = 0; int cnt = 0;
        for (int i = 0; i < end; i++) {
            double m = (st[0][i] + st[1][i]) * 0.5;
            if (Math.abs(m) > peak * 0.01) { sum += m * m; cnt++; }   // only the part you actually hear
        }
        double rms = Math.sqrt(sum / Math.max(1, cnt));
        double gain = Math.min(TARGET_RMS / Math.max(rms, 1e-9), MAX_DRIVE / peak);
        short[] out = new short[end * 2];
        int fin = (int) (0.003 * RATE), fout = (int) (0.1 * RATE);
        for (int i = 0; i < end; i++) {
            double fade = Math.min(1.0, (double) i / fin) * Math.min(1.0, (double) (end - 1 - i) / fout);
            for (int ch = 0; ch < 2; ch++) out[i * 2 + ch] = (short) Math.round(Math.tanh(st[ch][i] * gain) * 0.97 * fade * 32767);
        }
        return out;
    }
}
