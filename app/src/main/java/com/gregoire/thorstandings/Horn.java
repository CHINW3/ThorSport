package com.gregoire.thorstandings;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.VibrationEffect;
import android.os.Vibrator;

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

    public static void play(Context ctx, boolean sound, float volume) {
        try {
            Vibrator vib = ctx.getSystemService(Vibrator.class);
            if (vibrate && vib != null && vib.hasVibrator()) {
                vib.vibrate(VibrationEffect.createWaveform(new long[]{0, 220, 120, 220, 120, 500}, -1));
            }
        } catch (Exception ignored) { }
        if (!sound) return;
        new Thread(() -> {
            try {
                short[] data = buffer();
                AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(data.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
                track.write(data, 0, data.length);
                track.setVolume(volume);
                track.play();
                Thread.sleep(3400);
                track.release();
            } catch (Exception ignored) { }
        }).start();
    }
}
