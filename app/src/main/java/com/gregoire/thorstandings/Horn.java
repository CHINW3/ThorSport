package com.gregoire.thorstandings;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

/**
 * An arena-style goal horn synthesized in code (no audio file needed):
 * a low, buzzy chord with a slow fade, plus a vibration pattern.
 */
public final class Horn {
    private static final int RATE = 22050;
    private static short[] pcm;

    private Horn() {}

    public static boolean vibrate = true;
    /** Settings → Horn length: SHORT plays about 1.2 s instead of the full 3.2 s blast. */
    public static boolean shortHorn;

    /** Settings → Sound → Goal sound: 0 arena horn, 1 deep horn, 2 goal siren, 3 victory chime. */
    public static int soundType;
    private static final short[][] cache = new short[4][];
    public static final String[][] SOUND_NAMES = {{"ARENA HORN", "KLAXON ARÉNA"}, {"DEEP HORN", "KLAXON GRAVE"}, {"GOAL SIREN", "SIRÈNE"}, {"VICTORY CHIME", "CARILLON"}};

    private static synchronized short[] buffer() {
        int st = Math.max(0, Math.min(3, soundType));
        if (cache[st] != null) return cache[st];
        short[] out = st == 0 ? arena() : st == 1 ? deep() : st == 2 ? siren() : chime();
        cache[st] = out;
        return out;
    }

    /** Lower, darker horn: same shape as the arena one, an octave down with more grit. */
    private static short[] deep() {
        float seconds = 3.4f;
        int n = (int) (RATE * seconds);
        short[] out = new short[n];
        double[] freqs = {77.78, 98.0, 116.54, 155.56};
        double[] phase = new double[freqs.length];
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double env = Math.min(1.0, t / 0.08);
            if (t > seconds - 1.0) env *= Math.max(0, (seconds - t) / 1.0);
            double v = 0;
            for (int k = 0; k < freqs.length; k++) {
                phase[k] += 2 * Math.PI * freqs[k] * (1 + 0.003 * Math.sin(2 * Math.PI * 3.0 * t + k)) / RATE;
                double ph = phase[k] % (2 * Math.PI);
                v += 0.7 * (ph / Math.PI - 1.0) + 0.3 * Math.sin(2 * phase[k]);
            }
            out[i] = (short) (Math.tanh(v * 1.1) * env * 0.8 * Short.MAX_VALUE);
        }
        return out;
    }

    /** Goal siren: a rising and falling wail, like a goal light siren. */
    private static short[] siren() {
        float seconds = 3.0f;
        int n = (int) (RATE * seconds);
        short[] out = new short[n];
        double phase = 0, phase2 = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double f = 520 + 380 * (0.5 - 0.5 * Math.cos(2 * Math.PI * t / 1.0));
            phase += 2 * Math.PI * f / RATE;
            phase2 += 2 * Math.PI * f * 1.5 / RATE;
            double env = Math.min(1.0, t / 0.05) * (t > seconds - 0.5 ? Math.max(0, (seconds - t) / 0.5) : 1);
            double v = 0.7 * Math.sin(phase) + 0.25 * (Math.sin(phase2) >= 0 ? 1 : -1) * 0.5;
            out[i] = (short) (Math.tanh(v * 1.2) * env * 0.6 * Short.MAX_VALUE);
        }
        return out;
    }

    /** Victory chime: a bright bell arpeggio (softer than a horn). */
    private static short[] chime() {
        float seconds = 2.6f;
        int n = (int) (RATE * seconds);
        double[] notes = {523.25, 659.25, 783.99, 1046.5};
        double[] start = {0, 0.14, 0.28, 0.42};
        float[] acc = new float[n];
        for (int k = 0; k < notes.length; k++) {
            int s0 = (int) (start[k] * RATE);
            for (int i = s0; i < n; i++) {
                double t = (double) (i - s0) / RATE;
                double env = Math.exp(-t * 2.2) * Math.min(1, t / 0.005);
                double v = Math.sin(2 * Math.PI * notes[k] * t) + 0.35 * Math.sin(2 * Math.PI * notes[k] * 2.76 * t) * Math.exp(-t * 6);
                acc[i] += (float) (v * env * 0.32);
            }
        }
        short[] out = new short[n];
        for (int i = 0; i < n; i++) out[i] = (short) (Math.tanh(acc[i]) * 0.85 * Short.MAX_VALUE);
        return out;
    }

    private static short[] arena() {
        float seconds = 3.2f;
        int n = (int) (RATE * seconds);
        short[] out = new short[n];
        double[] freqs = {155.56, 196.0, 233.08, 311.13}; // Eb major-ish horn chord
        double[] phase = new double[freqs.length];
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            // envelope: quick attack, hold, slow release, slight "blast" pulsing
            double env = Math.min(1.0, t / 0.06);
            if (t > seconds - 0.9) env *= Math.max(0, (seconds - t) / 0.9);
            env *= 0.85 + 0.15 * Math.sin(2 * Math.PI * 5.5 * t);
            double v = 0;
            for (int k = 0; k < freqs.length; k++) {
                double vib = 1 + 0.004 * Math.sin(2 * Math.PI * 4.0 * t + k);
                phase[k] += 2 * Math.PI * freqs[k] * vib / RATE;
                double ph = phase[k] % (2 * Math.PI);
                double saw = ph / Math.PI - 1.0;                 // brassy sawtooth
                double sq = Math.sin(phase[k]) >= 0 ? 0.6 : -0.6;  // a bit of square for buzz
                v += 0.55 * saw + 0.45 * sq;
            }
            v = Math.tanh(v * 0.9) * env * 0.75;             // soft clipping = horn grit
            out[i] = (short) (v * Short.MAX_VALUE);
        }
        return out;
    }

    public static void play(Context ctx, boolean sound) { play(ctx, sound, 0.7f); }

    /** Bat crack (a short, sharp burst) then a pause, put in front of the horn for home runs. */
    private static short[] crackPcm;
    private static int crackType = -1;
    private static synchronized short[] withCrack() {
        if (crackPcm != null && crackType == soundType) return crackPcm;
        crackType = soundType;
        short[] horn = buffer();
        int crackN = (int) (RATE * 0.09), gap = (int) (RATE * 0.28);
        short[] out = new short[crackN + gap + horn.length];
        java.util.Random rnd = new java.util.Random(7);
        double prev = 0;
        for (int i = 0; i < crackN; i++) {
            double t = (double) i / RATE;
            double noise = rnd.nextDouble() * 2 - 1;
            double hp = noise - prev * 0.6; prev = noise;                 // brighter, "woody" crack
            double tone = Math.sin(2 * Math.PI * 1150 * t) * 0.5;          // the bat's ring
            double env = Math.exp(-t * 55);
            out[i] = (short) (Math.max(-1, Math.min(1, (hp + tone) * env)) * Short.MAX_VALUE * 0.9);
        }
        System.arraycopy(horn, 0, out, crackN + gap, horn.length);
        crackPcm = out;
        return crackPcm;
    }

    public static void play(Context ctx, boolean sound, float volume) { play(ctx, sound, volume, false); }

    public static void play(Context ctx, boolean sound, float volume, boolean batCrack) {
        try {
            Vibrator vib = ctx.getSystemService(Vibrator.class);
            if (vibrate && vib != null && vib.hasVibrator()) {
                vib.vibrate(VibrationEffect.createWaveform(new long[]{0, 220, 120, 220, 120, 500}, -1));
            }
        } catch (Exception ignored) { }
        if (!sound) return;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            AudioManager am = app.getSystemService(AudioManager.class);
            AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
            // Ask the game / video on the other screen to lower its sound while the horn plays.
            AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs).build();
            AudioTrack track = null;
            try {
                if (am != null) am.requestAudioFocus(focus);
                short[] data = batCrack ? withCrack() : buffer();
                int len = data.length;
                if (shortHorn) {   // cut it and fade the last 0.25 s so it doesn't click
                    int keep = Math.min(data.length, (batCrack ? data.length - buffer().length : 0) + (int) (RATE * 1.2));
                    short[] cut = java.util.Arrays.copyOf(data, keep);
                    int fadeN = Math.min(keep, (int) (RATE * 0.25));
                    for (int i = 0; i < fadeN; i++) cut[keep - 1 - i] = (short) (cut[keep - 1 - i] * (i / (float) fadeN));
                    data = cut; len = keep;
                }
                // Streaming mode: some devices silently refuse a big "static" buffer (that was a source of no sound).
                int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                track = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(Math.max(min, RATE))   // ~0.5 s
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
                if (track.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("audio not ready");
                track.setVolume(volume);
                track.play();
                track.write(data, 0, len);   // blocks until everything is queued
                Thread.sleep(400);                   // let the tail finish
                track.stop();
            } catch (Exception e) {
                Log.w("ThorSport", "Goal horn could not play", e);
            } finally {
                if (track != null) track.release();
                if (am != null) am.abandonAudioFocusRequest(focus);
            }
        }).start();
    }

    // ------------------------------------------------------------------ menu sounds
    private static AudioTrack tickTrack, selectTrack;

    /** A short soft "tick" (sine with a fast fade), higher for select. */
    private static AudioTrack clickTrack(double freq, double seconds) {
        int n = (int) (RATE * seconds);
        short[] d = new short[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            d[i] = (short) (Math.sin(2 * Math.PI * freq * t) * Math.exp(-t * 90) * 0.35 * Short.MAX_VALUE);
        }
        AudioTrack tr = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(n * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build();
        tr.write(d, 0, n);
        return tr;
    }

    /** Menu feedback: tick + a tiny vibration (select = higher tick, slightly stronger buzz). */
    public static synchronized void menuFx(Context ctx, boolean select) {
        try {
            if (select && selectTrack == null) selectTrack = clickTrack(1320, 0.05);
            if (!select && tickTrack == null) tickTrack = clickTrack(880, 0.035);
            AudioTrack tr = select ? selectTrack : tickTrack;
            if (tr.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) tr.stop();
            tr.reloadStaticData();
            tr.play();
        } catch (Exception e) { Log.w("ThorSport", "menu sound", e); }
        try {
            Vibrator vib = ctx.getSystemService(Vibrator.class);
            if (vib != null && vib.hasVibrator())
                vib.vibrate(VibrationEffect.createPredefined(select ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_TICK));
        } catch (Exception ignored) { }
    }
}
