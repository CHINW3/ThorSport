package com.gregoire.thorstandings;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.widget.Toast;

/**
 * Goal sounds + vibration. The sounds themselves are made by {@link Synth} (stereo, in a stadium reverb);
 * this class plays them, and makes sure they are really heard: it tries several ways of playing, checks that
 * the audio system is really consuming the samples (a track that never moves = silent output), and keeps a
 * short status for Settings → Sound → Sound check.
 */
public final class Horn {
    private Horn() {}

    public static boolean vibrate = true;
    /** Settings → Sound → Volume: MAX adds Android's loudness enhancer (about +9 dB) on top of full volume. Off for every other level. */
    public static boolean boost;
    /** Settings → Length: SHORT plays about 1.2 s instead of the full blast. */
    public static boolean shortHorn;

    /**
     * Sounds. Indexes 0-6 are the originals and never move (saved settings point at them); new ones go after them and
     * NONE is always last. 0 arena horn, 1 deep horn, 2 goal siren, 3 victory chime, 4 crowd cheer, 5 organ charge,
     * 6 swish, 7 fanfare, 8 air horn, 9 cannon.
     */
    public static final String[][] SOUND_NAMES = {{"ARENA HORN", "KLAXON ARÉNA"}, {"DEEP HORN", "KLAXON GRAVE"}, {"GOAL SIREN", "SIRÈNE"},
        {"VICTORY CHIME", "CARILLON"}, {"CROWD CHEER", "FOULE"}, {"ORGAN CHARGE", "ORGUE"}, {"SWISH", "SWISH"},
        {"FANFARE", "FANFARE"}, {"AIR HORN", "KLAXON À AIR"}, {"CANNON", "CANON"}, {"NONE", "AUCUN"}};
    public static final int NONE = SOUND_NAMES.length - 1;
    /** Before 3.29 "NONE" was number 7: saved settings with 7 are moved to the new NONE (see MainActivity). */
    public static final int OLD_NONE = 7;

    /** Settings → Sound: one sound per event (goal, touchdown, field goal, win, home run, OT winner, other games). */
    public static final String[][] EVENTS = {{"Goal", "But"}, {"Touchdown", "Touché"}, {"Field goal", "Placement"},
        {"Win", "Victoire"}, {"Home run", "Circuit"}, {"OT winner", "But en prolongation"}, {"Your other games", "Tes autres matchs"},
        {"3-pointer", "Panier de 3"}};
    public static final int EV_GOAL = 0, EV_TD = 1, EV_FG = 2, EV_WIN = 3, EV_HR = 4, EV_OT = 5, EV_OTHER = 6, EV_THREE = 7;

    /**
     * Styles: a whole set of sounds in one tap (Settings → Sound → Style), in the order of EVENTS:
     * goal, touchdown, field goal, win, home run, OT winner, your other games, 3-pointer.
     */
    public static final String[][] STYLE_NAMES = {{"ARENA", "ARÈNE"}, {"CLASSIC", "CLASSIQUE"}, {"PARTY", "FÊTE"}, {"MELLOW", "DOUX"}};
    public static final int[][] STYLES = {
        {0, 9, 5, 7, 4, 2, 3, 6},   // ARENA: arena horn, stadium cannon for touchdowns, organ "charge", fanfare for wins
        {0, 1, 5, 3, 4, 2, 3, 6},   // CLASSIC: the sounds ThorSport had before 3.29
        {8, 9, 2, 7, 4, 8, 3, 6},   // PARTY: air horns and sirens, as loud and silly as it gets
        {3, 3, 3, 3, 5, 3, 3, 6},   // MELLOW: bells (and the organ for home runs), nothing that blares
    };
    public static final int[] DEFAULT_SOUNDS = STYLES[0];
    public static final int[] eventSound = DEFAULT_SOUNDS.clone();

    /** Which style the current sounds match, or -1 for CUSTOM. */
    public static int styleOf() {
        for (int i = 0; i < STYLES.length; i++) if (java.util.Arrays.equals(STYLES[i], eventSound)) return i;
        return -1;
    }

    public static void applyStyle(int style) {
        System.arraycopy(STYLES[Math.max(0, Math.min(STYLES.length - 1, style))], 0, eventSound, 0, eventSound.length);
    }

    private static android.media.audiofx.LoudnessEnhancer boostOn(int audioSession) {
        if (!boost) return null;
        try {
            android.media.audiofx.LoudnessEnhancer le = new android.media.audiofx.LoudnessEnhancer(audioSession);
            le.setTargetGain(900);
            le.setEnabled(true);
            return le;
        } catch (Throwable e) {   // some devices don't have it: just no boost
            Log.w("ThorSport", "loudness boost not available", e);
            return null;
        }
    }

    private static void boostOff(android.media.audiofx.LoudnessEnhancer le) {
        if (le != null) try { le.release(); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ status (Settings → Sound check)
    /** Short result of the last sound: shown in Settings. */
    public static volatile String status = "—";
    /** Called (from any thread) when {@link #status} changes. */
    public static volatile Runnable onStatus;
    private static long lastWarn;

    private static void setStatus(String s) {
        if (s.equals(status)) return;
        status = s;
        Runnable r = onStatus;
        if (r != null) r.run();
    }

    /** A toast from any thread, at most once every 20 s unless forced. */
    private static void warn(Context app, String msg, boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && now - lastWarn < 20_000) return;
        lastWarn = now;
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app, msg, Toast.LENGTH_LONG).show());
    }

    // ------------------------------------------------------------------ the sound data
    /** Settings → Sound → Sound set: true = the recorded-quality pack in assets/sounds, false = the built-in synthesized sounds. */
    public static boolean usePack = true;
    private static final String[] FILES = {"arena_horn", "deep_horn", "goal_siren", "victory_chime", "crowd_cheer", "organ_charge",
        "swish", "fanfare", "air_horn"};
    private static Context appCtx;
    private static final short[][] cache = new short[Synth.COUNT][];
    private static short[] crackPcm;

    /** A WAV from assets/sounds, or null (missing, or not plain 16-bit PCM). */
    private static Wav asset(String name) {
        Context c = appCtx;
        if (c == null) return null;
        try (java.io.InputStream in = c.getAssets().open("sounds/" + name + ".wav")) {
            return Wav.parse(Wav.readAll(in));
        } catch (Exception e) {
            Log.w("ThorSport", "sound file " + name + " not usable, using the built-in one", e);
            return null;
        }
    }

    /** The pack's stereo version of a sound, leveled like the built-in ones; null if it can't be used. */
    private static short[] packSound(String name) {
        Wav w = usePack ? asset(name) : null;
        return w == null || w.rate != Synth.RATE ? null : Synth.level(w.stereo());
    }

    /** Settings → Sound set. */
    public static synchronized void setPack(boolean on) {
        usePack = on;
        java.util.Arrays.fill(cache, null);
        crackPcm = null;
        tickTrack = null; selectTrack = null;   // the menu ticks come from the same set
    }

    /** Interleaved stereo PCM of a sound, made on first use and kept. */
    private static synchronized short[] pcm(int type) {
        int t = Math.max(0, Math.min(Synth.COUNT - 1, type));
        if (cache[t] == null) {
            short[] p = t < FILES.length ? packSound(FILES[t]) : null;
            cache[t] = p != null ? p : Synth.render(t);
        }
        return cache[t];
    }

    private static synchronized short[] crack() {
        if (crackPcm == null) {
            short[] p = packSound("bat_crack");
            crackPcm = p != null ? p : Synth.crack();
        }
        return crackPcm;
    }

    /** The bat crack, a pause, then the sound (home runs). */
    private static synchronized short[] withCrack(int type) {
        short[] crack = crack();
        short[] horn = pcm(type);
        int gap = (int) (Synth.RATE * 0.26) * 2;
        short[] out = new short[crack.length + gap + horn.length];
        System.arraycopy(crack, 0, out, 0, crack.length);
        System.arraycopy(horn, 0, out, crack.length + gap, horn.length);
        return out;
    }

    /**
     * Makes the sounds in the background so the first goal doesn't have to wait for them (a horn takes a moment to
     * build on a phone). Call once at start and again when the sound choices change.
     */
    public static void warm(Context ctx) {
        appCtx = ctx.getApplicationContext();
        Thread th = new Thread(() -> {
            try {
                for (int ev = 0; ev < eventSound.length; ev++) {
                    int t = eventSound[ev];
                    if (t >= 0 && t < Synth.COUNT) { if (ev == EV_HR) withCrack(t); else pcm(t); }
                }
            } catch (Throwable e) { Log.w("ThorSport", "warm-up of the sounds failed", e); }
        }, "ThorSport-warm");
        th.setPriority(Thread.MIN_PRIORITY);
        th.start();
    }

    /** SHORT length: the first ~1.2 s with a quick fade (the organ tune and the swish are short already). */
    private static short[] shorten(short[] d, int type, boolean crack) {
        if (type == 5 || type == 6) return d;
        double secs = type == 7 ? 1.7 : type == 8 ? 1.6 : 1.2;
        int lead = crack ? crack().length + (int) (Synth.RATE * 0.26) * 2 : 0;
        int keep = Math.min(d.length, lead + (int) (Synth.RATE * secs) * 2);
        short[] cut = java.util.Arrays.copyOf(d, keep);
        int fade = Math.min(keep / 2, (int) (Synth.RATE * 0.25));   // frames
        for (int i = 0; i < fade; i++) {
            float g = i / (float) fade;
            cut[keep - 2 - i * 2] *= g;
            cut[keep - 1 - i * 2] *= g;
        }
        return cut;
    }

    // ------------------------------------------------------------------ playing
    public static void play(Context ctx, boolean sound) { play(ctx, sound, 0.7f); }

    public static void play(Context ctx, boolean sound, float volume) { play(ctx, sound, volume, false); }

    public static void play(Context ctx, boolean sound, float volume, boolean batCrack) { play(ctx, sound, volume, batCrack, EV_GOAL); }

    /** Plays the sound chosen for this event (Settings → Sound), and vibrates. */
    public static void play(Context ctx, boolean sound, float volume, boolean batCrack, int event) { play(ctx, sound, volume, batCrack, event, null); }

    /**
     * Same, and if `teamAbbr` is given (NHL: the team that scored) and a horn file for that team exists, a goal plays that
     * team's own horn instead of the goal sound. No file, or the option off: the usual goal sound.
     */
    public static void play(Context ctx, boolean sound, float volume, boolean batCrack, int event, String teamAbbr) {
        try {
            Vibrator vib = ctx.getSystemService(Vibrator.class);
            if (vibrate && vib != null && vib.hasVibrator()) {
                vib.vibrate(VibrationEffect.createWaveform(new long[]{0, 220, 120, 220, 120, 500}, -1));
            }
        } catch (Exception ignored) { }
        int type = eventSound[Math.max(0, Math.min(eventSound.length - 1, event))];
        if (!sound || type == NONE) return;   // "none" for goals silences the team horns too
        if (teamHorns && teamAbbr != null && (event == EV_GOAL || event == EV_OT) && hasTeamHorn(ctx, teamAbbr)) {
            playTeamHorn(ctx, teamAbbr, volume, type);
            return;
        }
        playType(ctx, type, volume, batCrack);
    }

    // ------------------------------------------------------------------ team horns (assets/horns/<abbr>.mp3)
    /** Settings → Sound → Team goal horns. */
    public static boolean teamHorns = true;
    private static java.util.Set<String> hornSet;

    /** Which team horns are in the app (file names in assets/horns without ".mp3"), read once. */
    private static synchronized java.util.Set<String> hornFiles(Context c) {
        if (hornSet == null) {
            hornSet = new java.util.TreeSet<>();
            try {
                String[] list = c.getApplicationContext().getAssets().list("horns");
                if (list != null) for (String f : list) if (f.endsWith(".mp3")) hornSet.add(f.substring(0, f.length() - 4));
            } catch (Exception e) { Log.w("ThorSport", "team horns not readable", e); }
        }
        return hornSet;
    }

    public static boolean hasTeamHorn(Context c, String abbr) {
        return abbr != null && hornFiles(c).contains(abbr.toLowerCase(java.util.Locale.ROOT));
    }

    /** Plays a team's horn (also used by the Settings preview). Falls back to the normal sound `fallbackType` if it can't. */
    public static void playTeamHorn(Context ctx, String abbr, float volume, int fallbackType) {
        final Context app = ctx.getApplicationContext();
        appCtx = app;
        final boolean sh = shortHorn;
        final String file = abbr.toLowerCase(java.util.Locale.ROOT);
        new Thread(() -> {
            boolean ok = false;
            try { ok = playHornFile(app, file, volume, sh); }
            catch (Throwable e) { Log.w("ThorSport", "team horn " + file + " failed", e); }
            if (!ok && fallbackType >= 0 && fallbackType < Synth.COUNT) {
                try { playNow(app, fallbackType, volume, false, sh, false); }
                catch (Throwable e) { Log.w("ThorSport", "fallback sound failed", e); setStatus("FAILED"); }
            }
        }, "ThorSport-horn").start();
    }

    /** Settings preview: Boston's horn if it's there, else the first one. */
    public static void previewTeamHorn(Context ctx, float volume) {
        java.util.Set<String> all = hornFiles(ctx);
        if (all.isEmpty()) return;
        playTeamHorn(ctx, all.contains("bos") ? "bos" : all.iterator().next(), volume, 0);
    }

    private static boolean playHornFile(Context app, String file, float volume, boolean sh) throws Exception {
        AudioManager am = app.getSystemService(AudioManager.class);
        try {
            if (am != null && am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                setStatus("VOLUME 0");
                warn(app, "Media volume is at 0: turn it up (volume buttons) to hear the goal sounds", false);
            }
        } catch (Exception ignored) { }
        AudioAttributes media = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(media).build();
        try { if (am != null) am.requestAudioFocus(focus); } catch (Exception ignored) { }
        MediaPlayer mp = new MediaPlayer();
        android.media.audiofx.LoudnessEnhancer le = boostOn(mp.getAudioSessionId());
        try (android.content.res.AssetFileDescriptor afd = app.getAssets().openFd("horns/" + file + ".mp3")) {
            mp.setAudioAttributes(media);
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            mp.prepare();
            mp.setVolume(volume, volume);
            mp.start();
            long t0 = SystemClock.uptimeMillis();
            while (mp.getCurrentPosition() == 0 && SystemClock.uptimeMillis() - t0 < 800) Thread.sleep(20);
            if (mp.getCurrentPosition() == 0 && !mp.isPlaying()) return false;   // nothing came out: let the normal sound play
            long limit = sh ? 1600 : mp.getDuration() + 300;                       // SHORT: about 1.3 s, then a quick fade
            while (mp.isPlaying()) {
                long e = SystemClock.uptimeMillis() - t0;
                if (e > limit) break;
                if (sh && e > 1300) { float g = Math.max(0f, 1f - (e - 1300) / 300f); mp.setVolume(volume * g, volume * g); }
                Thread.sleep(30);
            }
            return true;
        } finally {
            try { mp.release(); } catch (Exception ignored) { }
            boostOff(le);
            try { if (am != null) am.abandonAudioFocusRequest(focus); } catch (Exception ignored) { }
        }
    }

    /** Plays one sound now (also used to preview it in Settings). */
    public static void playType(Context ctx, int type, float volume, boolean batCrack) { playType(ctx, type, volume, batCrack, false); }

    /** manual = the user asked for it (Sound check): failures are shown in a toast with the reason. */
    public static void playType(Context ctx, int type, float volume, boolean batCrack, boolean manual) {
        if (type < 0 || type >= Synth.COUNT) return;
        final Context app = ctx.getApplicationContext();
        appCtx = app;
        final boolean sh = shortHorn;
        new Thread(() -> {
            try { playNow(app, type, volume, batCrack, sh, manual); }
            catch (Throwable e) {
                Log.w("ThorSport", "sound failed", e);
                setStatus("FAILED");
                if (manual) warn(app, "Sound failed: " + e, true);
            }
        }, "ThorSport-sound").start();
    }

    private static void playNow(Context app, int type, float volume, boolean batCrack, boolean sh, boolean manual) throws Exception {
        short[] data = batCrack ? withCrack(type) : pcm(type);
        if (sh) data = shorten(data, type, batCrack);

        AudioManager am = app.getSystemService(AudioManager.class);
        boolean muted = false;
        try { muted = am != null && am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0; } catch (Exception ignored) { }
        if (muted) {   // the most common reason for "no sound": goal sounds use the MEDIA volume
            setStatus("VOLUME 0");
            warn(app, "Media volume is at 0: turn it up (volume buttons) to hear the goal sounds", manual);
        }

        AudioAttributes media = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(media).build();
        try { if (am != null) am.requestAudioFocus(focus); } catch (Exception ignored) { }   // quiets a game/video on the other screen

        String via = null, why = "";
        try { if (viaTrack(media, data, volume)) via = "TRACK"; }
        catch (Throwable e) { why += "AudioTrack: " + e + "\n"; Log.w("ThorSport", "AudioTrack sound failed", e); }
        if (via == null) {
            try { if (viaPlayer(app, media, data, volume)) via = "BACKUP"; }
            catch (Throwable e) { why += "MediaPlayer: " + e + "\n"; Log.w("ThorSport", "MediaPlayer sound failed", e); }
        }
        if (via == null) {
            AudioAttributes game = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            try { if (viaTrack(game, data, volume)) via = "GAME"; }
            catch (Throwable e) { why += "AudioTrack(game): " + e + "\n"; Log.w("ThorSport", "game-usage sound failed", e); }
        }
        try { if (am != null) am.abandonAudioFocusRequest(focus); } catch (Exception ignored) { }

        if (via != null) {
            if (!muted) setStatus(via.equals("TRACK") ? "OK" : "OK · " + via);
        } else {
            setStatus("FAILED");
            warn(app, "No goal sound could be played on this device" + (why.isEmpty() ? "" : "\n" + why.trim()), manual);
        }
    }

    /** Way 1: the samples straight to an AudioTrack. Returns false if the audio system never started consuming them. */
    private static boolean viaTrack(AudioAttributes attrs, short[] data, float volume) throws Exception {
        AudioTrack t = new AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(Synth.RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(data.length * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build();
        android.media.audiofx.LoudnessEnhancer le = boostOn(t.getAudioSessionId());
        try {
            if (t.getState() != AudioTrack.STATE_INITIALIZED) return false;
            if (t.write(data, 0, data.length) != data.length) return false;
            t.setVolume(Math.max(0f, Math.min(1f, volume)));
            t.play();
            int frames = data.length / 2;
            long t0 = SystemClock.uptimeMillis();
            // a track whose position never moves is playing into nothing: report it so the next way gets a turn
            while (t.getPlaybackHeadPosition() == 0 && SystemClock.uptimeMillis() - t0 < 800) Thread.sleep(20);
            if (t.getPlaybackHeadPosition() == 0) return false;
            long ms = frames * 1000L / Synth.RATE;
            while (t.getPlaybackHeadPosition() < frames && SystemClock.uptimeMillis() - t0 < ms + 1500) Thread.sleep(40);
            return true;
        } finally {
            try { t.stop(); } catch (Exception ignored) { }
            t.release();
            boostOff(le);
        }
    }

    /** Way 2: a WAV file played by MediaPlayer (worked on devices where the first way did not, in earlier versions). */
    private static synchronized boolean viaPlayer(Context app, AudioAttributes attrs, short[] data, float volume) throws Exception {
        java.io.File f = new java.io.File(app.getCacheDir(), "sound_backup.wav");
        writeWav(f, data);
        MediaPlayer mp = new MediaPlayer();
        android.media.audiofx.LoudnessEnhancer le = boostOn(mp.getAudioSessionId());
        try {
            mp.setAudioAttributes(attrs);
            mp.setDataSource(f.getAbsolutePath());
            mp.prepare();
            mp.setVolume(volume, volume);
            mp.start();
            long ms = (data.length / 2) * 1000L / Synth.RATE, t0 = SystemClock.uptimeMillis();
            while (mp.getCurrentPosition() == 0 && SystemClock.uptimeMillis() - t0 < 800) Thread.sleep(20);
            if (mp.getCurrentPosition() == 0 && !mp.isPlaying()) return false;
            while (mp.isPlaying() && SystemClock.uptimeMillis() - t0 < ms + 1500) Thread.sleep(40);
            return true;
        } finally {
            try { mp.release(); } catch (Exception ignored) { }
            boostOff(le);
        }
    }

    private static void writeWav(java.io.File f, short[] d) throws java.io.IOException {
        try (java.io.DataOutputStream o = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(f)))) {
            int bytes = d.length * 2, rate = Synth.RATE;
            o.writeBytes("RIFF"); o.writeInt(Integer.reverseBytes(36 + bytes)); o.writeBytes("WAVE");
            o.writeBytes("fmt "); o.writeInt(Integer.reverseBytes(16)); o.writeShort(Short.reverseBytes((short) 1)); o.writeShort(Short.reverseBytes((short) 2));
            o.writeInt(Integer.reverseBytes(rate)); o.writeInt(Integer.reverseBytes(rate * 4)); o.writeShort(Short.reverseBytes((short) 4)); o.writeShort(Short.reverseBytes((short) 16));
            o.writeBytes("data"); o.writeInt(Integer.reverseBytes(bytes));
            for (short s : d) o.writeShort(Short.reverseBytes(s));
        }
    }

    // ------------------------------------------------------------------ menu sounds
    private static final int CLICK_RATE = 22050;
    private static AudioTrack tickTrack, selectTrack;

    /** A short soft "tick" (sine with a fast fade), higher for select. */
    private static AudioTrack clickTrack(boolean select, double freq, double seconds) {
        int rate = CLICK_RATE;
        short[] d = null;
        Wav w = usePack ? asset(select ? "menu_select" : "menu_tick") : null;
        if (w != null && w.channels == 1 && w.pcm.length > 0) {   // the pack's click, turned down to the old soft level (it moves with every cursor step)
            int peak = 1;
            for (short v : w.pcm) peak = Math.max(peak, Math.abs(v));
            float g = Math.min(1f, 0.35f * Short.MAX_VALUE / peak);
            d = new short[w.pcm.length];
            for (int i = 0; i < d.length; i++) d[i] = (short) (w.pcm[i] * g);
            rate = w.rate;
        }
        if (d == null) {
            d = new short[(int) (CLICK_RATE * seconds)];
            for (int i = 0; i < d.length; i++) {
                double t = (double) i / CLICK_RATE;
                d[i] = (short) (Math.sin(2 * Math.PI * freq * t) * Math.exp(-t * 90) * 0.35 * Short.MAX_VALUE);
            }
        }
        int n = d.length;
        AudioTrack tr = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
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
            if (select && selectTrack == null) selectTrack = clickTrack(true, 1320, 0.05);
            if (!select && tickTrack == null) tickTrack = clickTrack(false, 880, 0.035);
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
