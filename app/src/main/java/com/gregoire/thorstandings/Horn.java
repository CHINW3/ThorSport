package com.gregoire.thorstandings;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaPlayer;
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

    /** Sounds: 0 arena horn, 1 deep horn, 2 goal siren, 3 victory chime, 4 crowd cheer, 5 organ "charge", 6 none. */
    public static final String[][] SOUND_NAMES = {{"ARENA HORN", "KLAXON ARÉNA"}, {"DEEP HORN", "KLAXON GRAVE"}, {"GOAL SIREN", "SIRÈNE"},
        {"VICTORY CHIME", "CARILLON"}, {"CROWD CHEER", "FOULE"}, {"ORGAN CHARGE", "ORGUE"}, {"SWISH", "SWISH"}, {"NONE", "AUCUN"}};
    public static final int NONE = 7;
    /** Settings → Sound: one sound per event (goal, touchdown, field goal, win, home run, OT winner, other games). */
    public static final String[][] EVENTS = {{"Goal", "But"}, {"Touchdown", "Touché"}, {"Field goal", "Placement"},
        {"Win", "Victoire"}, {"Home run", "Circuit"}, {"OT winner", "But en prolongation"}, {"Your other games", "Tes autres matchs"},
        {"3-pointer", "Panier de 3"}};
    public static final int EV_GOAL = 0, EV_TD = 1, EV_FG = 2, EV_WIN = 3, EV_HR = 4, EV_OT = 5, EV_OTHER = 6, EV_THREE = 7;
    public static final int[] DEFAULT_SOUNDS = {0, 1, 5, 3, 4, 2, 3, 6};
    public static final int[] eventSound = DEFAULT_SOUNDS.clone();
    public static int soundType;   // the sound being played right now
    private static final short[][] cache = new short[7][];

    private static synchronized short[] buffer() {
        int st = Math.max(0, Math.min(6, soundType));
        if (cache[st] != null) return cache[st];
        short[] out = st == 0 ? arena() : st == 1 ? deep() : st == 2 ? siren() : st == 3 ? chime() : st == 4 ? crowd() : st == 5 ? organ() : swish();
        cache[st] = out;
        return out;
    }

    /** Crowd cheer: filtered noise swelling up and fading, with a few whistles. */
    private static short[] crowd() {
        float seconds = 3.2f;
        int n = (int) (RATE * seconds);
        short[] out = new short[n];
        java.util.Random r = new java.util.Random(3);
        double lp = 0, lp2 = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double noise = r.nextDouble() * 2 - 1;
            lp += 0.18 * (noise - lp);          // roar
            lp2 += 0.6 * (noise - lp2);         // hiss
            double env = Math.min(1, t / 0.35) * (t > seconds - 1.2 ? Math.max(0, (seconds - t) / 1.2) : 1);
            env *= 0.8 + 0.2 * Math.sin(2 * Math.PI * 1.7 * t);
            double whistle = 0;
            if ((t > 0.5 && t < 0.9) || (t > 1.6 && t < 1.9)) whistle = 0.18 * Math.sin(2 * Math.PI * (2400 + 300 * Math.sin(t * 9)) * t);
            double v = (lp * 2.2 + lp2 * 0.35) * env + whistle * env;
            out[i] = (short) (Math.max(-1, Math.min(1, v)) * 0.8 * Short.MAX_VALUE);
        }
        return out;
    }

    /** Swish: the net (a quick airy sweep), then a short crowd "ohh!" and a buzzer-style pop. */
    private static short[] swish() {
        float seconds = 1.6f;
        int n = (int) (RATE * seconds);
        short[] out = new short[n];
        java.util.Random r = new java.util.Random(11);
        double hp = 0, prev = 0, lp = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double noise = r.nextDouble() * 2 - 1;
            hp = 0.85 * (hp + noise - prev); prev = noise;            // bright, airy
            lp += 0.15 * (noise - lp);                                  // crowd
            double net = t < 0.35 ? hp * Math.sin(Math.PI * t / 0.35) * 0.9 : 0;
            double crowd = t > 0.25 ? lp * 2.0 * Math.min(1, (t - 0.25) / 0.2) * Math.max(0, (seconds - t) / (seconds - 0.25)) : 0;
            double v = net + crowd;
            out[i] = (short) (Math.max(-1, Math.min(1, v)) * 0.75 * Short.MAX_VALUE);
        }
        return out;
    }

    /** Stadium organ "charge!": da-da-da-DAAA-da-DAAA. */
    private static short[] organ() {
        double[][] notes = {{392, 0.16}, {523.25, 0.16}, {659.25, 0.16}, {783.99, 0.42}, {659.25, 0.16}, {783.99, 0.9}};
        double total = 0;
        for (double[] nt : notes) total += nt[1] + 0.04;
        int n = (int) (RATE * total);
        short[] out = new short[n];
        int pos = 0;
        for (double[] nt : notes) {
            int len = (int) (RATE * nt[1]);
            for (int i = 0; i < len && pos + i < n; i++) {
                double t = (double) i / RATE;
                double env = Math.min(1, t / 0.01) * Math.min(1, (nt[1] - t) / 0.03);
                double f = nt[0];
                double v = Math.sin(2 * Math.PI * f * t) + 0.5 * Math.sin(2 * Math.PI * f * 2 * t) + 0.3 * Math.sin(2 * Math.PI * f * 3 * t)
                    + 0.2 * Math.sin(2 * Math.PI * f * 4 * t);
                v *= 1 + 0.05 * Math.sin(2 * Math.PI * 6 * t);   // organ vibrato
                out[pos + i] = (short) (Math.tanh(v * 0.5) * env * 0.75 * Short.MAX_VALUE);
            }
            pos += len + (int) (RATE * 0.04);
        }
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

    public static void play(Context ctx, boolean sound, float volume, boolean batCrack) { play(ctx, sound, volume, batCrack, EV_GOAL); }

    /** Plays the sound chosen for this event (Settings → Sound). */
    public static void play(Context ctx, boolean sound, float volume, boolean batCrack, int event) {
        try {
            Vibrator vib = ctx.getSystemService(Vibrator.class);
            if (vibrate && vib != null && vib.hasVibrator()) {
                vib.vibrate(VibrationEffect.createWaveform(new long[]{0, 220, 120, 220, 120, 500}, -1));
            }
        } catch (Exception ignored) { }
        int type = eventSound[Math.max(0, Math.min(eventSound.length - 1, event))];
        if (!sound || type == NONE) return;
        playType(ctx, type, volume, batCrack);
    }

    /** Plays one sound now (also used to preview it in Settings). */
    public static void playType(Context ctx, int type, float volume, boolean batCrack) {
        if (type < 0 || type >= NONE) return;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            short[] data;
            synchronized (Horn.class) {
                soundType = type;
                data = batCrack ? withCrack() : buffer();
            }
            int len = data.length;
            if (shortHorn && type != 5 && type != 6) {   // cut it and fade the last 0.25 s so it doesn't click (the organ tune stays whole)
                int keep = Math.min(data.length, (batCrack ? data.length - buffer().length : 0) + (int) (RATE * 1.2));
                short[] cut = java.util.Arrays.copyOf(data, keep);
                int fadeN = Math.min(keep, (int) (RATE * 0.25));
                for (int i = 0; i < fadeN; i++) cut[keep - 1 - i] = (short) (cut[keep - 1 - i] * (i / (float) fadeN));
                data = cut; len = keep;
            }
            data = loud(data, len);
            AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
            AudioManager am = app.getSystemService(AudioManager.class);
            AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs).build();
            try { if (am != null) am.requestAudioFocus(focus); } catch (Exception ignored) { }
            // 1) a WAV file played by MediaPlayer: the most reliable on every device
            boolean ok = false;
            try {
                java.io.File f = new java.io.File(app.getCacheDir(), "loud2_" + type + (batCrack ? "_hr" : "") + (shortHorn ? "_s" : "") + ".wav");
                if (!f.exists() || f.length() < 100) writeWav(f, data, len);
                MediaPlayer mp = new MediaPlayer();
                mp.setAudioAttributes(attrs);
                mp.setDataSource(f.getAbsolutePath());
                mp.prepare();
                mp.setVolume(volume, volume);
                try {   // extra gain on top of full scale, more at HIGH
                    android.media.audiofx.LoudnessEnhancer le = new android.media.audiofx.LoudnessEnhancer(mp.getAudioSessionId());
                    le.setTargetGain((int) (900 * volume));
                    le.setEnabled(true);
                    mp.setOnCompletionListener(m -> { try { le.release(); } catch (Exception ignored) { } m.release(); });
                } catch (Exception ignored) { }
                mp.setOnCompletionListener(m -> m.release());
                mp.start();
                ok = true;
                Thread.sleep(len * 1000L / RATE + 300);
            } catch (Exception e) {
                Log.w("ThorSport", "MediaPlayer sound failed, trying AudioTrack", e);
            }
            // 2) fallback: stream the samples
            if (!ok) {
                AudioTrack track = null;
                try {
                    int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    track = new AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(Math.max(min, RATE))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                    track.setVolume(volume);
                    track.play();
                    track.write(data, 0, len);
                    Thread.sleep(400);
                    track.stop();
                } catch (Exception e) {
                    Log.w("ThorSport", "Goal sound could not play", e);
                } finally {
                    if (track != null) track.release();
                }
            }
            try { if (am != null) am.abandonAudioFocusRequest(focus); } catch (Exception ignored) { }
        }).start();
    }

    /** Compress and push to full scale so the sound is loud on small speakers. */
    static short[] loud(short[] d, int len) {
        short[] o = new short[d.length];
        double peak = 1e-6;
        for (int i = 0; i < len; i++) peak = Math.max(peak, Math.abs(d[i]) / 32767.0);
        double drive = 2.8 / peak;
        double norm = Math.tanh(2.8);
        for (int i = 0; i < len; i++) o[i] = (short) (Math.tanh(d[i] / 32767.0 * drive) / norm * 0.98 * 32767);
        return o;
    }

    private static void writeWav(java.io.File f, short[] d, int len) throws java.io.IOException {
        try (java.io.DataOutputStream o = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(f)))) {
            int bytes = len * 2;
            o.writeBytes("RIFF"); o.writeInt(Integer.reverseBytes(36 + bytes)); o.writeBytes("WAVE");
            o.writeBytes("fmt "); o.writeInt(Integer.reverseBytes(16)); o.writeShort(Short.reverseBytes((short) 1)); o.writeShort(Short.reverseBytes((short) 1));
            o.writeInt(Integer.reverseBytes(RATE)); o.writeInt(Integer.reverseBytes(RATE * 2)); o.writeShort(Short.reverseBytes((short) 2)); o.writeShort(Short.reverseBytes((short) 16));
            o.writeBytes("data"); o.writeInt(Integer.reverseBytes(bytes));
            for (int i = 0; i < len; i++) o.writeShort(Short.reverseBytes(d[i]));
        }
    }

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
