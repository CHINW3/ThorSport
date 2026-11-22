package com.gregoire.thorstandings;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Updates ThorSport from its GitHub releases, inside the app.
 * check() looks at the latest release; install() downloads ThorSport.apk and hands it
 * to Android's installer (the user confirms with one tap).
 */
final class Updater {
    static final String REPO = "CHINW3/ThorSport";
    static final String ACTION = "com.gregoire.thorstandings.INSTALL_STATUS";

    /** Newer version found on GitHub, or null. */
    static volatile String newVersion;
    static volatile String apkUrl;

    interface Progress { void on(int percent); }

    private Updater() { }

    static String currentVersion(Context ctx) {
        try { return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; }
        catch (Exception e) { return "0"; }
    }

    /** Network call: fills newVersion / apkUrl when GitHub has a newer release. */
    static boolean check(Context ctx) throws Exception {
        String json = Espn.get("https://api.github.com/repos/" + REPO + "/releases/latest");
        JSONObject o = new JSONObject(json);
        String tag = o.optString("tag_name", "").replaceFirst("^[vV]", "");
        JSONArray assets = o.optJSONArray("assets");
        String url = null;
        if (assets != null) for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.optString("name", "").endsWith(".apk")) { url = a.optString("browser_download_url", null); break; }
        }
        if (url != null && newer(tag, currentVersion(ctx))) { newVersion = tag; apkUrl = url; return true; }
        newVersion = null; apkUrl = null;
        return false;
    }

    /** "3.10" > "3.9" */
    static boolean newer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (Exception e) { return 0; }
    }

    /** Android asks once: "allow ThorSport to install apps". */
    static boolean canInstall(Activity a) {
        return a.getPackageManager().canRequestPackageInstalls();
    }

    static void askInstallPermission(Activity a) {
        a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
    }

    /** Network + disk: download the APK straight into an install session, then ask Android to install it. */
    static void install(Activity a, Progress progress) throws Exception {
        String url = apkUrl;
        if (url == null) throw new IllegalStateException("No update");
        HttpURLConnection con = open(url);
        long total = con.getContentLengthLong();
        PackageInstaller pi = a.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(a.getPackageName());
        int id = pi.createSession(params);
        PackageInstaller.Session session = pi.openSession(id);
        try (InputStream in = con.getInputStream(); OutputStream out = session.openWrite("ThorSport.apk", 0, total > 0 ? total : -1)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n, last = -1;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                int pct = total > 0 ? (int) (done * 100 / total) : -1;
                if (pct != last) { last = pct; progress.on(pct); }
            }
            session.fsync(out);
        } catch (Exception e) {
            session.abandon();
            throw e;
        } finally {
            con.disconnect();
        }
        Intent status = new Intent(ACTION).setPackage(a.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent pending = PendingIntent.getBroadcast(a, id, status, flags);
        session.commit(pending.getIntentSender());
        session.close();
    }

    /** GitHub sends the file through a redirect; follow it by hand to be safe. */
    private static HttpURLConnection open(String url) throws Exception {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setInstanceFollowRedirects(false);
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setRequestProperty("User-Agent", "ThorSport");
            int code = con.getResponseCode();
            if (code >= 300 && code < 400) {
                String next = con.getHeaderField("Location");
                con.disconnect();
                if (next == null) break;
                url = next;
                continue;
            }
            if (code != 200) { con.disconnect(); throw new Exception("HTTP " + code); }
            return con;
        }
        throw new Exception("Too many redirects");
    }

    /** Receives the installer's answer; shows Android's confirm screen when it asks for it. */
    static BroadcastReceiver statusReceiver(Activity a, java.util.function.Consumer<String> onError) {
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) { confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); a.startActivity(confirm); }
                } else if (st != PackageInstaller.STATUS_SUCCESS) {
                    String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    onError.accept(msg == null ? "Install failed" : msg);
                }
            }
        };
        IntentFilter f = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        else a.registerReceiver(r, f);
        return r;
    }
}
