package com.gregoire.thorstandings;

import android.content.Context;
import android.graphics.Color;
import android.provider.Settings;

import java.io.File;
import java.io.FileWriter;

/**
 * AYN Thor joystick ring LEDs.
 * Same technique as the open-source AmbiAnalog app: the two SN3112 LED drivers expose
 * sysfs files that regular apps can write ("enable" = 1/0, "brightness" = "1-R:G:B").
 * The Thor's own settings keep their state in Settings.System; we restore it when done.
 */
public final class Leds {
    private Leds() {}

    private static final String LEFT = "/sys/class/sn3112l/led";
    private static final String RIGHT = "/sys/class/sn3112r/led";
    private static float BRIGHTNESS = 0.7f;

    public static void setBrightness(float b) { BRIGHTNESS = b; synchronized (Leds.class) { lastL = ""; lastR = ""; } }

    private static volatile int generation;   // cancels a running flash
    private static int steadyLeft, steadyRight;
    private static boolean steadyIsSystem = true, restoredOnce;
    private static Context appCtx;
    private static String lastL = "", lastR = "";

    public static boolean available() {
        return new File(LEFT, "brightness").exists() && new File(RIGHT, "brightness").exists();
    }

    private static void write(String dir, String file, String value) {
        try (FileWriter w = new FileWriter(dir + "/" + file)) { w.write(value); } catch (Exception ignored) { }
    }

    /**
     * LEDs show dark colors (navy, burgundy) almost as "off": push the strongest channel to full,
     * keeping the hue, then apply the brightness setting. Near-black becomes white.
     */
    static int vivid(int color) {
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        int max = Math.max(r, Math.max(g, b));
        if (max < 24) return 0xFFFFFFFF;
        float k = 255f / max;
        r = Math.min(255, (int) (r * k)); g = Math.min(255, (int) (g * k)); b = Math.min(255, (int) (b * k));
        // LEDs make a small bit of a weak channel very visible (a red with a little blue looks pink,
        // e.g. Blackhawks). Drop channels under 25 % of the strongest so pure reds stay red.
        if (r < 64) r = 0;
        if (g < 64) g = 0;
        if (b < 64) b = 0;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static String value(int color) {
        int c = vivid(color);
        return "1-" + (int) (Color.red(c) * BRIGHTNESS) + ":" + (int) (Color.green(c) * BRIGHTNESS)
            + ":" + (int) (Color.blue(c) * BRIGHTNESS);
    }

    /** The Thor's own settings sometimes re-apply their lights (screen on…): write ours again. */
    public static void reassert() {
        if (steadyIsSystem || breathing) return;
        final int gen = generation;
        new Thread(() -> {
            synchronized (Leds.class) { lastL = ""; lastR = ""; }
            if (gen == generation) apply(steadyLeft, steadyRight);
        }).start();
    }

    /** Goal: the sticks swap the team's two colors back and forth, then return to normal. */
    public static void flash2(int a, int b) {
        final int gen = ++generation;
        new Thread(() -> {
            for (int i = 0; i < 12 && gen == generation; i++) {
                if (i % 2 == 0) force(a, b); else force(b, a);
                try { Thread.sleep(260); } catch (InterruptedException e) { return; }
            }
            if (gen != generation) return;
            if (steadyIsSystem && appCtx != null) { restoredOnce = false; restore(appCtx); }
            else force(steadyLeft, steadyRight);
        }).start();
    }

    /**
     * Home run: the light flies from the left stick to the right one (like the ball leaving the park),
     * three times, then the sticks swap a and b (gold for a grand slam) like a goal.
     */
    public static void homeRun(int a, int b) {
        final int gen = ++generation;
        new Thread(() -> {
            try {
                for (int i = 0; i < 3 && gen == generation; i++) {
                    force(a, 0); Thread.sleep(140);
                    force(a, a); Thread.sleep(90);
                    force(0, a); Thread.sleep(160);
                    force(0, 0); Thread.sleep(120);
                }
                for (int i = 0; i < 10 && gen == generation; i++) {
                    if (i % 2 == 0) force(a, b); else force(b, a);
                    Thread.sleep(260);
                }
            } catch (InterruptedException e) { return; }
            if (gen != generation) return;
            if (steadyIsSystem && appCtx != null) { restoredOnce = false; restore(appCtx); }
            else force(steadyLeft, steadyRight);
        }).start();
    }

    /** Small celebration: a few quick blinks, then back to the steady colors. */
    public static void blink(int color, int times) {
        final int gen = ++generation;
        new Thread(() -> {
            for (int i = 0; i < times * 2 && gen == generation; i++) {
                force(i % 2 == 0 ? color : 0, i % 2 == 0 ? color : 0);
                try { Thread.sleep(i % 2 == 0 ? 180 : 120); } catch (InterruptedException e) { return; }
            }
            if (gen != generation) return;
            if (steadyIsSystem && appCtx != null) { restoredOnce = false; restore(appCtx); }
            else force(steadyLeft, steadyRight);
        }).start();
    }

    /**
     * Write even if we think the stick already has this color. Used during goal animations so they still show
     * when another RGB app (AmbiAnalog, the Thor's own light settings…) keeps writing its colors over ours.
     */
    private static synchronized void force(int left, int right) {
        lastL = ""; lastR = "";
        apply(left, right);
    }

    /** color 0 = that stick off. */
    private static synchronized void apply(int left, int right) {
        String l = left == 0 ? "off" : value(left), r = right == 0 ? "off" : value(right);
        if (!l.equals(lastL)) {
            if (left == 0) write(LEFT, "enable", "0"); else { write(LEFT, "enable", "1"); write(LEFT, "brightness", l); }
            lastL = l;
        }
        if (!r.equals(lastR)) {
            if (right == 0) write(RIGHT, "enable", "0"); else { write(RIGHT, "enable", "1"); write(RIGHT, "brightness", r); }
            lastR = r;
        }
    }

    /** Steady colors (team colors). Runs off the main thread. */
    public static void set(int left, int right) {
        if (!steadyIsSystem && left == steadyLeft && right == steadyRight && !breathing) return;   // nothing changed
        steadyIsSystem = false;
        steadyLeft = left;
        steadyRight = right;
        final int gen = ++generation;
        new Thread(() -> { if (gen == generation) apply(left, right); }).start();
    }

    /** Goal! Both sticks blink in the scoring team's color, then go back to the steady colors. */
    public static void flash(int color) {
        final int gen = ++generation;
        new Thread(() -> {
            for (int i = 0; i < 12 && gen == generation; i++) {
                force(i % 2 == 0 ? color : 0, i % 2 == 0 ? color : 0);
                try { Thread.sleep(i % 2 == 0 ? 330 : 170); } catch (InterruptedException e) { return; }
            }
            if (gen != generation) return;
            if (steadyIsSystem && appCtx != null) { restoredOnce = false; restore(appCtx); }
            else force(steadyLeft, steadyRight);
        }).start();
    }

    private static int scale(int color, float f) {
        return 0xFF000000 | ((int) (Color.red(color) * f) << 16) | ((int) (Color.green(color) * f) << 8) | (int) (Color.blue(color) * f);
    }

    private static volatile boolean breathing;

    /** Slow "heartbeat" in one color until something else changes the lights (tense final minutes). */
    public static void breathe(int color) {
        if (breathing) return;
        breathing = true;
        final int gen = ++generation;
        new Thread(() -> {
            long start = System.currentTimeMillis();
            while (gen == generation) {
                double t = (System.currentTimeMillis() - start) / 1000.0;
                float f = (float) (0.25 + 0.75 * (0.5 + 0.5 * Math.sin(t * Math.PI)));
                int c = scale(color, f);
                apply(c, c);
                try { Thread.sleep(60); } catch (InterruptedException e) { break; }
            }
            breathing = false;
        }).start();
    }

    public static boolean isBreathing() { return breathing; }

    /** Give the LEDs back to the Thor's own settings. */
    public static void restore(Context ctx) {
        appCtx = ctx.getApplicationContext();
        if (steadyIsSystem && restoredOnce && !breathing) return;   // already handed back
        steadyIsSystem = true;
        restoredOnce = true;
        final int gen = ++generation;
        final Context app = appCtx;
        new Thread(() -> {
            if (gen != generation) return;
            try {
                String colors = Settings.System.getString(app.getContentResolver(), "joystick_led_light_picker_color");
                String enabled = Settings.System.getString(app.getContentResolver(), "joystick_light_enabled");
                String bri = Settings.System.getString(app.getContentResolver(), "led_light_brightness_percent");
                String[] c = colors == null ? new String[0] : colors.split(",");
                String[] e = enabled == null ? new String[0] : enabled.split(",");
                float b = 0.5f;
                try { if (bri != null) b = Float.parseFloat(bri); } catch (Exception ignored) { }
                restoreOne(LEFT, e.length > 0 && "1".equals(e[0].trim()), c.length > 0 ? c[0] : null, b);
                restoreOne(RIGHT, e.length > 1 && "1".equals(e[1].trim()), c.length > 1 ? c[1] : null, b);
            } catch (Exception ignored) { }
            synchronized (Leds.class) { lastL = ""; lastR = ""; }
        }).start();
    }

    private static void restoreOne(String dir, boolean on, String color, float b) {
        int col = 0xFF2BE0D8;
        try { if (color != null) col = Color.parseColor(color.trim()); } catch (Exception ignored) { }
        write(dir, "enable", on ? "1" : "0");
        write(dir, "brightness", "1-" + (int) (Color.red(col) * b) + ":" + (int) (Color.green(col) * b) + ":" + (int) (Color.blue(col) * b));
    }
}
