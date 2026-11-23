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

    private static synchronized short[] buffer() {
        if (pcm != null) return pcm;
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
        pcm = out;
        return pcm;
    }

    public static void play(Context ctx, boolean sound) { play(ctx, sound, 0.7f); }

    /** Bat crack (a short, sharp burst) then a pause, put in front of the horn for home runs. */
    private static short[] crackPcm;
    private static synchronized short[] withCrack() {
        if (crackPcm != null) return crackPcm;
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
                track.write(data, 0, data.length);   // blocks until everything is queued
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
}
