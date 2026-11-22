package com.gregoire.thorstandings;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads team logos: from the APK first, otherwise downloads once and keeps them on disk + in memory. */
public class LogoCache {
    private static final int TARGET_PX = 192;

    private final LruCache<String, Bitmap> mem = new LruCache<>(120); // keeps memory low
    private final Set<String> loading = new HashSet<>();
    private final Set<String> failed = new HashSet<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File dir;
    private final Runnable onLoaded;
    private final AssetManager assets;

    public LogoCache(Context ctx, Runnable onLoaded) {
        this.dir = new File(ctx.getCacheDir(), "logos");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        this.onLoaded = onLoaded;
        this.assets = ctx.getAssets();
    }

    /** Returns the logo if ready; otherwise starts loading it and returns null. Call on main thread. */
    public Bitmap get(String url) {
        if (url == null || url.isEmpty()) return null;
        Bitmap b = mem.get(url);
        if (b != null || loading.contains(url) || failed.contains(url)) return b;
        loading.add(url);
        pool.execute(() -> {
            Bitmap bmp = null;
            // "a|b" = try a first, then b (used for PWHL big/small logos)
            for (String u : url.split("\\|")) {
                try { bmp = load(u); } catch (Exception ignored) { }
                if (bmp != null) break;
            }
            final Bitmap result = bmp;
            main.post(() -> {
                loading.remove(url);
                if (result != null) { mem.put(url, result); onLoaded.run(); }
                else failed.add(url);
            });
        });
        return null;
    }

    /**
     * Logos packed in the APK by the build (app/src/main/assets/logos/...).
     * ESPN URL ".../i/teamlogos/nhl/500-dark/mtl.png" -> asset "logos/teamlogos/nhl/500-dark/mtl.png".
     * If that exact variant isn't bundled, try the dark/normal twin.
     */
    private byte[] fromAssets(String url) {
        int i = url.indexOf("/i/");
        if (i < 0) return null;
        String rel = url.substring(i + 3).split("[?&]")[0];
        String[] tries = {rel, rel.replace("/500-dark/", "/500/"), rel.replace("/500/", "/500-dark/")};
        for (String t : tries) {
            try (InputStream in = assets.open("logos/" + t)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toByteArray();
            } catch (Exception ignored) { }
        }
        return null;
    }

    private Bitmap load(String url) throws Exception {
        File f = new File(dir, Integer.toHexString(url.hashCode()) + ".png");
        byte[] bytes = fromAssets(url);
        if (bytes != null) {
            // bundled in the APK — nothing to download
        } else if (f.exists() && f.length() > 0) {
            bytes = Files.readAllBytes(f.toPath());
        } else {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(10000);
            con.setReadTimeout(15000);
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                bytes = bos.toByteArray();
            } finally {
                con.disconnect();
            }
            try (FileOutputStream out = new FileOutputStream(f)) { out.write(bytes); }
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= TARGET_PX) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
    }

    public void shutdown() { pool.shutdownNow(); }
}
