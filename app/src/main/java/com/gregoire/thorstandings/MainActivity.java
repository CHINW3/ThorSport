package com.gregoire.thorstandings;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements ThorView.Listener {
    static MainActivity instance;
    private ThorView top;                 // the TOP screen view in two-screen mode (null otherwise)
    private boolean dual, companion;
    private String[] leagueLabelsCache;
    private List<Model.Group> lastGroups;
    private String lastLeagueLogo;
    private final String[] leagueLogoUrls = new String[Espn.LEAGUES.length];

    /** Same change on both screens. */
    private void all(java.util.function.Consumer<ThorView> f) {
        f.accept(view);
        if (top != null) f.accept(top);
    }

    private static final long BOARD_REFRESH_MS = 10_000;       // the game you follow is live (NORMAL mode)
    private static final long LIVE_REFRESH_MS = 30_000;        // while a game is live (NORMAL mode)
    private static final long FAST_MINE_MS = 5_000;            // FAST mode: the game you follow, or one of your teams, is live
    private static final long FAST_LIVE_MS = 10_000;           // FAST mode: other live games (ESPN itself only updates every few seconds)
    private static final long IDLE_REFRESH_MS = 3 * 60_000;    // otherwise
    private static final long NIGHT_REFRESH_MS = 15 * 60_000;  // 1 AM–8 AM with nothing live (saves battery)
    private static final long RETRY_MS = 20_000;               // after a failed refresh
    private static final long STANDINGS_MAX_AGE_MS = 10 * 60_000;
    private static final long CLOCK_TICK_MS = 15_000;
    private static final float[] DIM_LEVELS = {0f, 0.4f, 0.7f};
    private static final String EXTRA_MOVED = "moved_to_bottom";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private ThorView view;
    private LogoCache logos;
    private SharedPreferences prefs;
    private int leagueIdx, tab, dateOffset, dimLevel;
    private Set<String> favorites;          // abbreviations that count as "mine" on the current page
    private Set<String> favKeys;            // saved as "league path|ABBR" so MTL (NHL) ≠ MTL (MLS)
    private long standingsLoadedAt;
    private int standingsLeague = -1;
    private boolean running, sound, myTeamsOnly, lastFailed, ledsOn, autoBoard;
    private int delaySec, hornVol, ledBri;
    private boolean ledGoal = true, extraAlerts, notify = true, celebrations, keepOn, nightMode, hideFinal, shotMap;
    private BroadcastReceiver updateReceiver;
    private volatile int updatePct = -2;   // -2 idle, -1 downloading (size unknown), 0..100 downloading
    private long resetArmedAt;
    private static final float[] HORN_VOLS = {0.55f, 0.85f, 1f, 1f};   // LOW MED HIGH MAX (MAX = full volume + the loudness boost)
    private static final String[] VOL_NAMES = {"LOW", "MED", "HIGH", "MAX"};
    private static final float[] LED_BRIS = {0.3f, 0.7f, 1f};
    private static final String[] LEVELS = {"LOW", "MED", "HIGH"};
    private final Map<String, String> lastDetail = new HashMap<>();
    private final Map<String, Integer> lastPeriod = new HashMap<>();
    private long followedFinalAt;
    private boolean quietHours, vibrate, french, bigStandings, menuFx;
    // 3.17 options
    private long muteUntil;                 // alerts muted until (Settings → Mute alerts 1 h)
    private boolean celebMine, fastLive, lowBattWarned;
    private final Map<String, int[]> worstDeficit = new HashMap<>();   // game id -> {away's biggest deficit, home's biggest deficit}
    private final Map<String, int[]> scoringRun = new HashMap<>();   // game id -> {side that scored last (0 away / 1 home), how many in a row}
    private int keepMode;                   // 0 always · 1 only while a game is live · 2 never
    private static final String[] KEEP_NAMES = {"ALWAYS", "LIVE GAMES", "NEVER"};
    private int rotateIdx;                  // auto-rotate live games on the Board: 0 off · 20 s · 45 s
    private static final int[] ROTATE_SEC = {0, 20, 45};
    private long rotatedAt;
    private int reminderIdx = 2;            // pre-game reminder: off · 5 · 15 · 30 min
    private static final int[] REMIND_MIN = {0, 5, 15, 30};
    private int startLeague;                // 0 = last league · 1 = MY TEAMS
    private long settingsResetArmedAt;
    private Model.Game lastAlertGame; private Model.Side lastAlertSide; private String lastAlertWord;   // Replay last goal
    private final Map<String, String> lastSituation = new HashMap<>();
    private final Map<String, Integer> lastRedCards = new HashMap<>();
    private final Map<String, Map<String, Integer>> lastScorerGoals = new HashMap<>();
    private final Set<String> twoMinWarned = new HashSet<>();
    private int celebLen, accentIdx, clockMode, batteryPct = 100;
    private boolean batteryCharging = true;
    private long dateChangedAt;
    private static final long[] CELEB_MS = {3500, 6500, 10000};
    private static final String[] CELEB_NAMES = {"SHORT", "NORMAL", "LONG"};
    private static final int[] ACCENTS = {0xFF4DA3FF, 0xFFFF4D5E, 0xFF34C759, 0xFFB57BFF, 0xFFFFB524};
    private static final String[] ACCENT_NAMES = {"BLUE", "RED", "GREEN", "PURPLE", "GOLD"};
    private static final String[] CLOCK_NAMES = {"AUTO", "12 H", "24 H"};
    private final Set<String> closeAlerted = new HashSet<>(), reminded = new HashSet<>();
    private final Map<String, float[]> lastOdds = new HashMap<>();
    private final boolean ledsAvailable = Leds.available();

    /** Spoiler delay: every fetched snapshot waits here until it's `delaySec` old. */
    private static final class Snap {
        final long at; final int league, day; final Model.Scoreboard games; final List<Model.Group> groups;
        Snap(long at, int league, int day, Model.Scoreboard games, List<Model.Group> groups) {
            this.at = at; this.league = league; this.day = day; this.games = games; this.groups = groups;
        }
    }
    private final List<Snap> pending = new ArrayList<>();
    private final Runnable delayTick = new Runnable() {
        @Override public void run() { applyDue(); if (!pending.isEmpty()) main.postDelayed(this, 1000); }
    };
    private String boardGameId;
    private final Set<String> hiddenLeagues = new HashSet<>();   // paths of the leagues switched off in Settings → Games → Leagues
    private final Map<String, int[]> lastScores = new HashMap<>();
    private final Map<String, String> lastStates = new HashMap<>();

    private final Runnable refreshLoop = new Runnable() {
        @Override public void run() {
            refresh(false);
            main.postDelayed(this, nextDelay());
        }
    };
    // ------------------------------------------------------------------ smooth game clock
    /** What ESPN last said about a game's clock, so it can run down second by second between refreshes. */
    private static final class ClockBase {
        int secs, period; long at; boolean running; String clock, detail;
    }
    private final Map<String, ClockBase> clockBase = new HashMap<>();

    private static int clockSecs(String c) {
        if (c == null) return -1;
        String[] mmss = c.trim().split(":");
        if (mmss.length != 2) return -1;   // "45.2" (last NBA minute) or "67'" (soccer): left as ESPN gives it
        try { return Integer.parseInt(mmss[0]) * 60 + Integer.parseInt(mmss[1].replaceAll("[^0-9].*", "")); } catch (Exception e) { return -1; }
    }

    /** New data: the clock is "running" if it moved since the last refresh (same period). */
    private void syncClocks(List<Model.Game> games) {
        long now = System.currentTimeMillis();
        for (Model.Game g : games) {
            boolean counts = "hockey".equals(g.sport) || "basketball".equals(g.sport) || "football".equals(g.sport);
            int secs = clockSecs(g.clock);
            if (!counts || !g.isLive() || secs < 0) { clockBase.remove(g.id); continue; }
            ClockBase old = clockBase.get(g.id);
            ClockBase b = new ClockBase();
            b.secs = secs; b.period = g.period; b.at = now; b.clock = g.clock; b.detail = g.detail;
            b.running = old != null && old.period == g.period && secs < old.secs && secs > 0;
            clockBase.put(g.id, b);
        }
        main.removeCallbacks(smoothClock);
        main.postDelayed(smoothClock, 1000);
    }

    private final Runnable smoothClock = new Runnable() {
        @Override public void run() {
            if (clockBase.isEmpty()) return;
            long now = System.currentTimeMillis();
            boolean changed = false;
            for (Model.Game g : lastGames) {
                ClockBase b = clockBase.get(g.id);
                if (b == null || !b.running) continue;
                long el = Math.min(45, (now - b.at) / 1000);   // never run ahead more than one refresh
                int s = (int) Math.max(1, b.secs - el);         // stays on 0:01 until ESPN confirms the end
                String c = (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
                if (!c.equals(g.clock)) {
                    g.clock = c;
                    g.detail = b.detail.replace(b.clock, c);
                    changed = true;
                }
            }
            if (changed) all(v -> v.invalidate());
            main.postDelayed(this, 1000);
        }
    };

    private final Runnable clockTick = new Runnable() {
        @Override public void run() { view.invalidate(); main.postDelayed(this, CLOCK_TICK_MS); }
    };
    private final BroadcastReceiver screenOnReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent i) {
            if (ledsAvailable && ledsOn) main.postDelayed(() -> { Leds.reassert(); updateLeds(); }, 800);
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent i) {
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean chg = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
            if (level >= 0 && scale > 0) {
                batteryPct = level * 100 / scale;
                batteryCharging = chg;
                if (chg || batteryPct > 20) lowBattWarned = false;
                else if (batteryPct <= 15 && !lowBattWarned && view != null) {
                    lowBattWarned = true;
                    all(v -> v.banner(t("BATTERY ", "BATTERIE ") + batteryPct + t("% · saving power: slower refresh", " % · économie : rafraîchissement plus lent"), 0xFFFF3B30));
                }
                all(v -> v.setBattery(level * 100 / scale, chg));
                pushSettings();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("thor", MODE_PRIVATE);
        if (maybeMoveToBottomScreen()) return;

        instance = this;
        Espn.cacheDir = getCacheDir();
        leagueIdx = Math.min(prefs.getInt("league", 0), Espn.LEAGUES.length - 1);
        tab = prefs.getInt("tab", ThorView.TAB_SCORES);
        if (tab < 0 || tab > 2) tab = ThorView.TAB_SCORES;
        favKeys = new HashSet<>(prefs.getStringSet("favKeys", new HashSet<>()));
        if (favKeys.isEmpty()) { // old versions saved plain abbreviations: assume NHL
            for (String a : prefs.getStringSet("favs", new HashSet<>())) favKeys.add("hockey/nhl|" + a);
        }
        if (!prefs.contains("league")) leagueIdx = favKeys.isEmpty() ? 1 : 0; // NHL until you pick teams
        if (prefs.getInt("startLeague", 0) == 1 && !favKeys.isEmpty()) { leagueIdx = 0; tab = ThorView.TAB_SCORES; }   // Settings → Open on: MY TEAMS
        if (tab >= tabCount()) tab = ThorView.TAB_SCORES;
        favorites = favAbbrs(leagueIdx);
        sound = prefs.getBoolean("sound", true);
        myTeamsOnly = prefs.getBoolean("myTeams", false);
        dimLevel = Math.min(prefs.getInt("dim", 0), DIM_LEVELS.length - 1);
        boardGameId = prefs.getString("boardGame", null);
        delaySec = 0;   // opening the app = no waiting: scores show right away (sync again with your TV if you need it)
        prefs.edit().putInt("delay", 0).apply();
        ledsOn = prefs.getBoolean("leds", true);
        autoBoard = prefs.getBoolean("autoBoard", true);
        hornVol = Math.max(0, Math.min(HORN_VOLS.length - 1, prefs.getInt("hornVol", 2)));   // HIGH unless you chose otherwise
        Horn.boost = hornVol == 3;
        ledBri = prefs.getInt("ledBri", 1);
        celebrations = prefs.getBoolean("celebrations", true);
        ThorView.fxFlash = prefs.getBoolean("fxFlash", true);
        ThorView.fxBeams = prefs.getBoolean("fxBeams", true);
        ThorView.fxConfetti = prefs.getBoolean("fxConfetti", true);
        ThorView.fxFireworks = prefs.getBoolean("fxFireworks", true);
        ThorView.fxShake = prefs.getBoolean("fxShake", true);
        // 3.29 added two sounds before "none": a saved "none" (7) now has another number
        boolean oldSounds = prefs.getInt("soundsVer", 1) < 2;
        for (int i = 0; i < Horn.eventSound.length; i++) {
            int v = prefs.getInt("evSound" + i, Horn.DEFAULT_SOUNDS[i]);
            if (oldSounds && prefs.contains("evSound" + i) && v == Horn.OLD_NONE) { v = Horn.NONE; prefs.edit().putInt("evSound" + i, v).apply(); }
            Horn.eventSound[i] = Math.max(0, Math.min(Horn.NONE, v));
        }
        if (oldSounds) prefs.edit().putInt("soundsVer", 2).apply();
        if (!prefs.contains("evSound0")) Horn.eventSound[Horn.EV_GOAL] = prefs.getInt("hornSound", Horn.DEFAULT_SOUNDS[Horn.EV_GOAL]);   // the 3.21 "Sound" setting
        ledGoal = prefs.getBoolean("ledGoal", true);
        notify = prefs.getBoolean("otherTeams", true);
        extraAlerts = prefs.getBoolean("extraAlerts", false);   // off: only goals, touchdowns and wins
        keepOn = prefs.getInt("keepMode", prefs.getBoolean("keepOn", true) ? 0 : 2) == 0;   // LIVE GAMES: switched on/off as games go live
        nightMode = false;   // night dim removed (3.17)
        hideFinal = prefs.getBoolean("hideFinal", false);
        shotMap = prefs.getBoolean("shotMap", true);
        companion = true;   // only one two-screen mode now: Board on top, GAME + LIVE below
        Leds.setBrightness(LED_BRIS[ledBri]);
        quietHours = false;   // quiet hours removed (3.17): use Mute alerts instead
        muteUntil = prefs.getLong("muteUntil", 0);
        celebMine = prefs.getBoolean("celebMine", false);
        Horn.shortHorn = prefs.getBoolean("hornShort", false);
        fastLive = prefs.getBoolean("fastLive", true);   // FAST (15 s) unless you chose NORMAL yourself
        keepMode = prefs.getInt("keepMode", prefs.getBoolean("keepOn", true) ? 0 : 2);
        rotateIdx = Math.min(prefs.getInt("rotate", 0), ROTATE_SEC.length - 1);
        reminderIdx = Math.min(prefs.getInt("reminder", 2), REMIND_MIN.length - 1);
        startLeague = prefs.getInt("startLeague", 0);
        bigStandings = prefs.getBoolean("bigStandings", true);
        vibrate = prefs.getBoolean("vibrateGoal", false);   // off by default (3.16)
        menuFx = prefs.getBoolean("menuFx", false);
        french = prefs.getBoolean("fr", java.util.Locale.getDefault().getLanguage().equals("fr"));
        celebLen = prefs.getInt("celebLen", 1);
        accentIdx = prefs.getInt("accent", 0);
        clockMode = prefs.getInt("clock", 0);
        Horn.vibrate = vibrate;
        Horn.onStatus = () -> runOnUiThread(this::pushSettings);   // Settings → Sound → Sound check shows the last result
        hiddenLeagues.addAll(prefs.getStringSet("hiddenLeagues", new HashSet<>()));
        Horn.usePack = prefs.getBoolean("soundPack", true);
        Horn.teamHorns = prefs.getBoolean("teamHorns", true);
        Horn.warm(this);                                           // load/build the sounds now so the first goal isn't late
        ThorView.fr = french;
        ThorView.setAccent(ACCENTS[accentIdx]);

        if (keepOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getAttributes().layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setDecorFitsSystemWindows(false);

        view = new ThorView(this);
        logos = new LogoCache(this, () -> { view.invalidate(); if (top != null) top.invalidate(); });
        view.setLogoCache(logos);
        view.setListener(this);
        String[] labels = new String[Espn.LEAGUES.length];
        for (int i = 0; i < labels.length; i++) labels[i] = Espn.LEAGUES[i].label;
        leagueLabelsCache = labels;
        for (int i = 0; i < Espn.LEAGUES.length; i++) {
            String fixed = Espn.fixedLogo(Espn.LEAGUES[i]);
            leagueLogoUrls[i] = fixed != null ? fixed : prefs.getString("leagueLogo_" + Espn.LEAGUES[i].path, null);
        }
        all(v -> v.setLeagueLabels(labels));
        all(v -> v.setFavorites(new HashSet<>(favorites)));
        all(v -> v.setLeague(leagueIdx, Espn.LEAGUES[leagueIdx]));
        view.setTab(tab);
        all(v -> v.setSound(sound));
        all(v -> v.setUpdateWaiting(Updater.newVersion != null));
        view.setMyTeamsOnly(myTeamsOnly);
        all(v -> v.setDim(DIM_LEVELS[dimLevel]));
        all(v -> v.setBoardGameId(boardGameId));
        pushSettings();
        setContentView(view);
        hideSystemBars();

        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        registerReceiver(screenOnReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
        updateReceiver = Updater.statusReceiver(this, msg -> runOnUiThread(() -> {
            updatePct = -2;
            Toast.makeText(this, t("Update failed: ", "Mise à jour échouée : ") + msg, Toast.LENGTH_LONG).show();
            pushSettings();
        }));
        if (System.currentTimeMillis() - prefs.getLong("updCheck", 0) > 6 * 3600_000L) checkUpdate(false);
        all(v -> v.setLeagueLogos(leagueLogoUrls.clone()));
        loadLeagueLogos();
        running = true;
        loadFromCache();
        view.setFavKeys(new HashSet<>(favKeys));
        view.setCompanion(companion);
        if (!prefs.getBoolean("onboarded", false)) {
            if (!favKeys.isEmpty()) prefs.edit().putBoolean("onboarded", true).apply();   // already has teams (older version)
            else main.postDelayed(() -> view.openOnboarding(true, onboardLeagues()), 600);
        }
        main.post(refreshLoop);
        main.postDelayed(favWatch, 5000);
        main.post(clockTick);
        if (prefs.getBoolean("dual", false)) main.postDelayed(() -> setDual(true), 900);
        if (!prefs.getBoolean("seenHelp", false)) {   // first launch: quick tour
            view.showHelp();
            prefs.edit().putBoolean("seenHelp", true).apply();
        }
    }

    /**
     * On the AYN Thor, Android treats the TOP screen as the main display and the
     * bottom screen as a second display. If we opened on the main one and a second
     * display exists, relaunch ourselves there.
     */
    private boolean maybeMoveToBottomScreen() {
        if (getIntent().getBooleanExtra(EXTRA_MOVED, false)) return false;
        try {
            Display current = getDisplay();
            if (current == null || current.getDisplayId() != Display.DEFAULT_DISPLAY) return false;
            DisplayManager dm = getSystemService(DisplayManager.class);
            for (Display d : dm.getDisplays()) {
                if (d.getDisplayId() != Display.DEFAULT_DISPLAY) {
                    ActivityOptions opts = ActivityOptions.makeBasic();
                    opts.setLaunchDisplayId(d.getDisplayId());
                    Intent i = new Intent(this, MainActivity.class)
                        .putExtra(EXTRA_MOVED, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                    startActivity(i, opts.toBundle());
                    finish();
                    return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void hideSystemBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && view != null) hideSystemBars();
    }

    @Override protected void onResume() {
        super.onResume();
        if (running) { main.removeCallbacks(refreshLoop); main.post(refreshLoop); main.removeCallbacks(favWatch); main.postDelayed(favWatch, 3000); }   // fresh data right away
    }

    /** Completely hidden (and no top screen): stop refreshing and give the lights back. Saves battery and data. */
    @Override protected void onStop() {
        super.onStop();
        if (!running || (TopActivity.current != null && !leftHome)) return;
        main.removeCallbacks(refreshLoop);
        main.removeCallbacks(delayTick);
        main.removeCallbacks(smoothClock);
        main.removeCallbacks(favWatch);
        if (ledsAvailable) Leds.restore(this);
    }

    // ---- Home with two screens (Cocoon etc.): leave both screens, come back on both
    private boolean leftHome;

    /** Home pressed (on either screen) in two-screen mode: close the top screen too. Two-screen mode stays on. */
    void leaveHome(boolean fromTop) {
        if (!dual || leftHome) return;
        leftHome = true;
        if (TopActivity.current != null) TopActivity.current.finish();
        if (fromTop) moveTaskToBack(true);
    }

    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        leaveHome(false);
    }

    @Override protected void onStart() {
        super.onStart();
        if (leftHome) {   // back from home: open the top screen again
            leftHome = false;
            if (dual && TopActivity.current == null) main.postDelayed(() -> { if (dual && TopActivity.current == null) setDual(true); }, 400);
        }
        if (!running) return;
        main.removeCallbacks(refreshLoop);
        main.post(refreshLoop);
        main.removeCallbacks(favWatch);
        main.postDelayed(favWatch, 3000);
        updateLeds();
    }

    /**
     * Controller (bottom or top screen):
     * D-pad / left stick = move · A = select · B = back · X / Y = ★ left / right team ·
     * L1 / R1 = tabs · L2 / R2 = leagues · Start = settings · Select = two screens · L3 = refresh · R3 = big score
     */

    /**
     * Controller — always drives the bottom screen (the top screen just follows the game you pick):
     * D-pad / stick = move · A = open/select · B = back · X / Y = ★ left / right team ·
     * L1 / R1 = tabs · L2 / R2 = leagues · Start = settings · Select = league list · L3 = refresh · R3 = two screens
     */
    boolean handleKey(ThorView from, int code) {
        ThorView v = view;
        menuFeedback(code);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: v.controllerMove(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: v.controllerMove(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_UP: v.controllerMove(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: v.controllerMove(0, 1); return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER: v.controllerSelect(); return true;
            case KeyEvent.KEYCODE_BUTTON_B: v.controllerBack(); return true;          // B never leaves the app
            case KeyEvent.KEYCODE_BACK: return v.controllerBack() || from == top;    // Back on top never closes it
            case KeyEvent.KEYCODE_BUTTON_X: v.controllerFavorite(false); return true;
            case KeyEvent.KEYCODE_BUTTON_Y: v.controllerFavorite(true); return true;
            case KeyEvent.KEYCODE_BUTTON_L1: if (v.onboardingOpen()) { v.onboardShift(-1); return true; } if (v.settingsShift(-1)) return true; v.hideCursor(); onTabTap((tab + tabCount() - 1) % tabCount()); return true;
            case KeyEvent.KEYCODE_BUTTON_R1: if (v.onboardingOpen()) { v.onboardShift(1); return true; } if (v.settingsShift(1)) return true; v.hideCursor(); onTabTap((tab + 1) % tabCount()); return true;
            case KeyEvent.KEYCODE_BUTTON_L2: switchLeague(stepLeague(-1)); return true;
            case KeyEvent.KEYCODE_BUTTON_R2: switchLeague(stepLeague(1)); return true;
            case KeyEvent.KEYCODE_BUTTON_START: v.openSettings(); return true;
            case KeyEvent.KEYCODE_BUTTON_SELECT: v.openLeaguePicker(); return true;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: refresh(true); return true;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: onViewMode(dual ? 0 : 2); return true;   // R3: one screen / two screens
            default: return false;
        }
    }

    /** Settings → Menu sounds & vibration (off by default): a soft tick + tiny buzz when you move, a higher one to select. */
    private void menuFeedback(int code) {
        if (!menuFx) return;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_BUTTON_L1: case KeyEvent.KEYCODE_BUTTON_R1: case KeyEvent.KEYCODE_BUTTON_L2: case KeyEvent.KEYCODE_BUTTON_R2:
                Horn.menuFx(this, false); break;
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_BUTTON_START: case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BUTTON_X: case KeyEvent.KEYCODE_BUTTON_Y:
                Horn.menuFx(this, true); break;
            default: break;
        }
    }

    private boolean stickArmed = true;
    private long stickHeldSince, stickLastRepeat;

    /**
     * Left stick / hat: only the main direction counts. On the Board one push = one game
     * (back to the centre before the next); in lists holding it scrolls after a short pause.
     */
    boolean handleStick(ThorView v, MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK || e.getAction() != MotionEvent.ACTION_MOVE) return false;
        float x = e.getAxisValue(MotionEvent.AXIS_X), y = e.getAxisValue(MotionEvent.AXIS_Y);
        float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (Math.abs(hx) > 0.5f || Math.abs(hy) > 0.5f) { x = hx; y = hy; }
        float ax = Math.abs(x), ay = Math.abs(y);
        if (ax < 0.35f && ay < 0.35f) { stickArmed = true; return true; }   // back in the centre
        if (Math.max(ax, ay) < 0.65f) return true;                           // dead zone between
        int dx = 0, dy = 0;
        if (ax >= ay) dx = x > 0 ? 1 : -1; else dy = y > 0 ? 1 : -1;      // main direction only
        long now = System.currentTimeMillis();
        if (stickArmed) {
            stickArmed = false;
            stickHeldSince = now;
            stickLastRepeat = now;
            menuFeedback(KeyEvent.KEYCODE_DPAD_DOWN);
            view.controllerMove(dx, dy);
            return true;
        }
        boolean boardSwitch = tab == ThorView.TAB_BOARD && dx != 0;
        long repeatMs = now - stickHeldSince > 1500 ? 70 : 160;   // held longer: scroll faster
        if (!boardSwitch && now - stickHeldSince > 450 && now - stickLastRepeat > repeatMs) {   // held: repeat in lists
            stickLastRepeat = now;
            menuFeedback(KeyEvent.KEYCODE_DPAD_DOWN);
            view.controllerMove(dx, dy);
        }
        return true;
    }

    /** Held D-pad: Android repeats the key. On the Board that would skip games, so only the first press counts. */
    boolean ignoreRepeat(int code, KeyEvent e) {
        return e != null && e.getRepeatCount() > 0 && tab == ThorView.TAB_BOARD
            && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (ignoreRepeat(keyCode, event)) return true;
        if (view != null && handleKey(view, keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        if (view != null && handleStick(view, e)) return true;
        return super.onGenericMotionEvent(e);
    }

    // We keep refreshing in onPause: the game on the top screen has focus,
    // but this app stays visible on the bottom screen.
    @Override protected void onDestroy() {
        super.onDestroy();
        if (!running) return;
        try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) { }
        try { unregisterReceiver(screenOnReceiver); } catch (Exception ignored) { }
        try { if (updateReceiver != null) unregisterReceiver(updateReceiver); } catch (Exception ignored) { }
        if (ledsAvailable) Leds.restore(this);
        if (TopActivity.current != null) TopActivity.current.finish();
        if (instance == this) instance = null;
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        logos.shutdown();
    }

    private long nextDelay() {
        long d = baseDelay();
        return lowBattery() ? d * 2 : d;   // battery saver
    }

    private long baseDelay() {
        if (syncState == 1 || syncState == 3) return 4_000;   // Sync with TV: check often to catch the score quickly
        if (dateOffset != 0 && System.currentTimeMillis() - dateChangedAt > 30 * 60_000) main.post(() -> onDateShift(-dateOffset));
        if (lastFailed) return RETRY_MS;
        Model.Game b = view.boardGame();
        if (b != null && b.isLive() && (fastLive || tab == ThorView.TAB_BOARD)) return fastLive ? FAST_MINE_MS : BOARD_REFRESH_MS;   // the game you follow, on any tab
        if (view.anyLive()) return fastLive ? (view.anyLiveOfMine() ? FAST_MINE_MS : FAST_LIVE_MS) : LIVE_REFRESH_MS;
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour >= 1 && hour < 8 ? NIGHT_REFRESH_MS : IDLE_REFRESH_MS;
    }

    // ------------------------------------------------------------------ touch
    @Override public void onLeagueLongPress() { view.showLeaguePicker(); }
    @Override public void onLeaguePick(int index) { switchLeague(index); }

    private void switchLeague(int idx) {
        lastScores.clear();
        lastStates.clear();
        lastDetail.clear();
        lastPeriod.clear();
        closeAlerted.clear();
        lastGames = java.util.Collections.emptyList();
        pending.clear();
        boardGameId = null;
        dateOffset = 0;
        leagueIdx = idx;
        prefs.edit().putInt("league", leagueIdx).remove("boardGame").apply();
        if (tab >= tabCount() || tab == ThorView.TAB_BOARD) { tab = ThorView.TAB_SCORES;   // a new sport opens on LIVE, not on the last game
            prefs.edit().putInt("tab", tab).apply(); view.setTab(tab); }
        view.setDateOffset(0);
        all(v -> v.setBoardGameId(null));
        favorites = favAbbrs(leagueIdx);
        all(v -> v.setFavorites(new HashSet<>(favorites)));
        all(v -> v.setLeague(leagueIdx, Espn.LEAGUES[leagueIdx]));
        all(v -> v.setMessage("Loading…"));
        loadFromCache();
        restartLoop();
    }

    /** MY TEAMS has no standings tab (teams from different leagues can't share a table). */
    private int tabCount() { return ThorView.tabCount(Espn.LEAGUES[leagueIdx]); }

    // ------------------------------------------------------------------ PLAYOFFS tab
    private int playoffsLeague = -1;
    private long playoffsAt;

    /** Real playoffs from ESPN if the league is in them, else "if the playoffs started today" from the standings. */
    private void loadPlayoffs(boolean force) {
        final int idx = leagueIdx;
        final Espn.League lg = Espn.LEAGUES[idx];
        if (ThorView.tabCount(lg) < 4) return;
        if (!force && playoffsLeague == idx && System.currentTimeMillis() - playoffsAt < 60_000) return;
        if (playoffsLeague != idx) all(v -> v.setPlayoffs(null, null));
        playoffsLeague = idx; playoffsAt = System.currentTimeMillis();
        final List<Model.Group> known = standingsLeague == idx ? lastGroups : null;
        io.execute(() -> {
            Playoffs.Bracket b = null;
            try { b = Playoffs.live(lg); } catch (Exception ignored) { }
            if (b == null) {
                List<Model.Group> g = known;
                if (g == null) try { g = fetchStandings(lg, false); } catch (Exception ignored) { }
                b = Playoffs.project(lg, g);
            }
            final Playoffs.Bracket fb = b;
            final String msg = b != null ? null : lg.college()
                ? ThorView.tr("The postseason shows up here once it starts", "Les séries s'afficheront ici quand elles commenceront")
                : ThorView.tr("No playoff data right now", "Pas de données de séries pour l'instant");
            main.post(() -> { if (idx == leagueIdx) all(v -> v.setPlayoffs(fb, msg)); });
        });
    }

    @Override public void onTabTap(int t) {
        if (t >= tabCount()) t = ThorView.TAB_SCORES;
        if (t == tab) return;
        tab = t;
        prefs.edit().putInt("tab", tab).apply();
        view.setTab(tab);
        if (tab == ThorView.TAB_STANDINGS) refresh(false);
        else if (tab == ThorView.TAB_PLAYOFFS) loadPlayoffs(false);
        else restartLoop();
    }

    @Override public void onBodyTap() { refresh(true); }

    @Override public void onTeamLongPress(String abbr) {
        if (abbr == null || abbr.isEmpty()) return;
        Espn.League lg = Espn.LEAGUES[leagueIdx];
        boolean added;
        if (favorites.contains(abbr)) {
            if (lg.mine()) favKeys.removeIf(k -> k.endsWith("|" + abbr));
            else favKeys.remove(lg.path + "|" + abbr);
            added = false;
        } else {
            String path = lg.path;
            if (lg.mine()) {
                path = null;
                for (Model.Game g : lastGames) if (abbr.equals(g.away.abbr) || abbr.equals(g.home.abbr)) path = g.league;
                if (path == null) return;
            }
            favKeys.add(path + "|" + abbr);
            added = true;
        }
        prefs.edit().putStringSet("favKeys", new HashSet<>(favKeys)).apply();
        favorites = favAbbrs(leagueIdx);
        all(v -> v.setFavorites(new HashSet<>(favorites)));
        rememberColors(lastGames);
        updateLeds();
        Toast.makeText(this, abbr + (added ? " ★ added to MY TEAMS" : " removed from MY TEAMS"), Toast.LENGTH_SHORT).show();
        view.setFavKeys(new HashSet<>(favKeys));
    }

    // ------------------------------------------------------------------ "Pick your teams"
    /** Leagues offered in the team picker (not MY TEAMS, F1 or LoL). */
    private boolean leagueHidden(int idx) { return hiddenLeagues.contains(Espn.LEAGUES[idx].path) && !Espn.LEAGUES[idx].mine(); }

    /** The next (dir 1) or previous (-1) league in the menu order, skipping the ones you switched off. */
    private int stepLeague(int dir) {
        int i = leagueIdx;
        for (int k = 0; k < Espn.LEAGUES.length; k++) {
            i = Espn.neighbor(i, dir);
            if (!leagueHidden(i)) return i;
        }
        return leagueIdx;
    }

    private List<Integer> onboardLeagues() {
        List<Integer> out = new ArrayList<>();
        for (int i : Espn.ORDER) {   // in the same sport order as the league menu
            Espn.League l = Espn.LEAGUES[i];
            if (l.mine() || l.path.startsWith("racing") || l.path.startsWith("esports") || l.noTable()) continue;
            if (leagueHidden(i) && favAbbrsFor(l.path).isEmpty()) continue;   // a league you switched off (and have no team in)
            out.add(i);
        }
        return out;
    }

    /** Teams of a league, from its standings (saved copy first, then online), sorted by name. */
    @Override public void onOnboardLeague(int idx) {
        final Espn.League lg = Espn.LEAGUES[idx];
        new Thread(() -> {
            List<Model.Team> teams = new ArrayList<>();
            for (boolean cache : new boolean[]{true, false}) {
                try {
                    Map<String, Model.Team> seen = new java.util.LinkedHashMap<>();
                    for (Model.Group g : fetchStandings(lg, cache)) for (Model.Team t : g.teams) if (t.abbr != null && !t.abbr.isEmpty()) seen.put(t.abbr, t);
                    if (!seen.isEmpty()) { teams = new ArrayList<>(seen.values()); break; }
                } catch (Exception ignored) { }
            }
            teams.sort((a, b) -> String.valueOf(a.name).compareToIgnoreCase(String.valueOf(b.name)));
            final List<Model.Team> fin = teams;
            main.post(() -> view.setOnboardTeams(idx, fin));
        }).start();
    }

    @Override public void onOnboardToggle(String path, String abbr) {
        String key = path + "|" + abbr;
        if (!favKeys.remove(key)) favKeys.add(key);
        prefs.edit().putStringSet("favKeys", new HashSet<>(favKeys)).apply();
        favorites = favAbbrs(leagueIdx);
        all(v -> v.setFavorites(new HashSet<>(favorites)));
        view.setFavKeys(new HashSet<>(favKeys));
    }

    @Override public void onOnboardDone() {
        prefs.edit().putBoolean("onboarded", true).apply();
        pushSettings();
        updateLeds();
        if (!favKeys.isEmpty() && !Espn.LEAGUES[leagueIdx].mine()) switchLeague(0);   // straight to MY TEAMS
        else refresh(true);
    }

    @Override public void onTeamTap(String abbr) {
        if (dateOffset != 0) {
            onDateShift(-dateOffset);
            Toast.makeText(this, "Back to today, tap the team again", Toast.LENGTH_SHORT).show();
            return;
        }
        for (Model.Game g : lastGames) {
            if (abbr.equals(g.away.abbr) || abbr.equals(g.home.abbr)) { onFollowGame(g.id, true); return; }
        }
        Toast.makeText(this, "No " + abbr + " game today", Toast.LENGTH_SHORT).show();
    }

    /** Two screens: moving the cursor on LIVE shows that game on the top screen (no delay change, no tab jump). */
    @Override public void onCursorGame(String gameId) {
        if (top == null || gameId == null || gameId.equals(boardGameId)) return;
        boardGameId = gameId;
        prefs.edit().putString("boardGame", gameId).apply();
        all(v -> v.setBoardGameId(gameId));
        updateLeds();
    }

    @Override public void onFollowGame(String gameId, boolean openBoard) {
        if (tension && !gameId.equals(boardGameId)) tension = false;
        if (delaySec > 0 && !gameId.equals(prefs.getString("delayGame", ""))) {   // new game = new stream: start without delay
            boardGameId = gameId;
            setDelay(0);
            all(v -> v.banner(t("Spoiler delay off for this game · long-press the clock to sync", "Délai désactivé pour ce match · appui long sur l'heure pour synchroniser"), 0xFF8B919C));
        }
        boardGameId = gameId;
        prefs.edit().putString("boardGame", gameId).apply();
        all(v -> v.setBoardGameId(gameId));
        updateLeds();
        if (openBoard) onTabTap(ThorView.TAB_BOARD);   // two screens: the bottom jumps to GAME for the game you picked
    }

    // ------------------------------------------------------------------ spoiler delay
    private int syncState;          // 0 off, 1 waiting for a score in the live feed, 2 score seen live: waiting for your tap
    private long syncDetectedAt;
    private final Map<String, String> rawScores = new HashMap<>();

    private void setDelay(int sec) {
        delaySec = Math.max(0, Math.min(600, sec));
        // the delay belongs to the game you're watching (each stream is different), not a forever setting
        prefs.edit().putInt("delay", delaySec).putString("delayGame", boardGameId == null ? "" : boardGameId)
            .putLong("delayAt", System.currentTimeMillis()).apply();
        main.removeCallbacks(delayTick);
        main.post(delayTick);
        pushSettings();
    }

    @Override public void onDelayAction(int type, int value) {
        if (type == 0) setDelay(delaySec + value);
        else if (type == 1) setDelay(value);
        else if (type == 2) {   // Sync with TV: pick the moment your TV is showing
            syncState = 3;
            pushMoments();
            restartLoop();
        }
    }

    @Override public void onSyncSeen() {
        if (syncState != 2) return;
        int sec = (int) ((System.currentTimeMillis() - syncDetectedAt) / 1000) + 2;   // +2 s safety margin
        syncState = 0;
        all(v -> v.setSync(0, 0));
        setDelay(sec);
        all(v -> v.banner(t("Spoiler delay set to ", "Délai réglé à ") + sec + " s", 0xFF34C759));
    }

    @Override public void onSyncCancel() {
        syncState = 0;
        all(v -> v.setSync(0, 0));
    }

    // ---- Sync with TV: a short history of what the live feed showed for the game on top
    private static final class Moment { final long at; final String label, score; Moment(long a, String l, String s) { at = a; label = l; score = s; } }
    private final List<Moment> moments = new ArrayList<>();
    private String momentsGame = "";

    /** Called with every live (not delayed) update: remember when each clock / score first appeared. */
    private void recordMoment(List<Model.Game> games) {
        Model.Game g = gameOnTop(games);
        if (g == null || !g.isLive()) return;
        if (!g.id.equals(momentsGame)) { moments.clear(); momentsGame = g.id; }
        String label = g.detail.isEmpty() ? g.clock : g.detail;
        String score = g.away.abbr + " " + g.away.score + " – " + g.home.score + " " + g.home.abbr;
        Moment last = moments.isEmpty() ? null : moments.get(moments.size() - 1);
        if (last == null || !last.label.equals(label) || !last.score.equals(score)) moments.add(new Moment(System.currentTimeMillis(), label, score));
        long old = System.currentTimeMillis() - 12 * 60_000L;
        while (moments.size() > 80 || (!moments.isEmpty() && moments.get(0).at < old)) moments.remove(0);
        if (syncState == 3) pushMoments();
    }

    private final List<Moment> shownMoments = new ArrayList<>();   // exactly the rows on screen (newest first)

    private void pushMoments() {
        List<String[]> rows = new ArrayList<>();
        long now = System.currentTimeMillis();
        shownMoments.clear();
        for (int i = moments.size() - 1; i >= 0; i--) {   // newest first
            Moment m = moments.get(i);
            shownMoments.add(m);
            long ago = (now - m.at) / 1000;
            String agoTxt = ago < 60 ? ago + " s" : (ago / 60) + ":" + String.format(Locale.US, "%02d", ago % 60);
            rows.add(new String[]{m.label.toUpperCase(Locale.US), m.score, agoTxt + " " + t("ago", "avant")});
        }
        view.setSyncMoments(rows);
        all(v -> v.setSync(3, now));
    }

    /** You picked the row that matches your TV: the delay is how long ago the feed showed it. */
    @Override public void onSyncPick(int row) {
        if (row < 0 || row >= shownMoments.size()) return;
        int sec = (int) ((System.currentTimeMillis() - shownMoments.get(row).at) / 1000) + 1;
        syncState = 0;
        all(v -> v.setSync(0, 0));
        setDelay(sec);
        all(v -> v.banner(t("Synced with your TV · delay ", "Synchro avec ta TV · délai ") + sec + " s", 0xFF34C759));
    }

    /** Sync with TV: notice a score change in the live (undelayed) feed. */
    private void detectRawScore(List<Model.Game> games) {
        boolean changed = false;
        for (Model.Game g : games) {
            if (!g.isLive() || "racing".equals(g.sport)) continue;
            String v = g.away.score + "-" + g.home.score;
            String old = rawScores.put(g.id, v);
            if (old != null && !old.equals(v)) changed = true;
        }
        if (changed && syncState == 1) {
            syncState = 2;
            syncDetectedAt = System.currentTimeMillis();
            all(v -> v.setSync(2, syncDetectedAt));
            Horn.play(this, false);   // just a vibration: look at the TV now
        }
    }

    // ------------------------------------------------------------------ two-screen mode
    @Override public void onDualToggle() { setDual(!dual); }

    /** ▭ menu (and R3): 0 = one screen, anything else = two screens (Board on top, GAME + LIVE below). */
    @Override public void onViewMode(int mode) {
        if ((mode > 0) != dual) setDual(mode > 0);
        pushSettings();
    }

    /** On: the TOP screen shows a giant Board, the bottom keeps scores/standings. Off: top screen is free. */
    private void setDual(boolean on) {
        if (on) {
            int topId = -1;
            try {
                Display mine = getDisplay();
                int myId = mine == null ? Display.DEFAULT_DISPLAY : mine.getDisplayId();
                if (myId != Display.DEFAULT_DISPLAY) topId = Display.DEFAULT_DISPLAY;   // Thor: top = main display
                else for (Display d : getSystemService(DisplayManager.class).getDisplays())
                    if (d.getDisplayId() != myId) { topId = d.getDisplayId(); break; }
            } catch (Exception ignored) { }
            if (topId < 0) { Toast.makeText(this, "Second screen not found", Toast.LENGTH_SHORT).show(); return; }
            if (TopActivity.current == null) {
                try {
                    ActivityOptions o = ActivityOptions.makeBasic();
                    o.setLaunchDisplayId(topId);
                    startActivity(new Intent(this, TopActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK), o.toBundle());
                } catch (Exception e) {
                    Toast.makeText(this, "Couldn't open the top screen", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            if (companion) { if (tab != ThorView.TAB_STANDINGS) onTabTap(ThorView.TAB_BOARD); }   // bottom: GAME info for the game on top
            else if (tab == ThorView.TAB_BOARD) onTabTap(ThorView.TAB_SCORES);                    // classic: Board is on top now
        } else if (TopActivity.current != null) {
            TopActivity.current.finish();
        }
        dual = on;
        prefs.edit().putBoolean("dual", on).apply();
        view.setDual(on);
        pushSettings();
    }

    /** Called by TopActivity: copy everything to the top screen. */
    void attachTop(ThorView v) {
        top = v;
        v.setListener(this);
        v.setLogoCache(logos);
        v.setLeagueLabels(leagueLabelsCache);
        v.setLeagueLogos(leagueLogoUrls.clone());
        v.setDual(true);
        v.setFavorites(new HashSet<>(favorites));
        v.setLeague(leagueIdx, Espn.LEAGUES[leagueIdx]);
        v.setLeagueLogo(lastLeagueLogo);
        v.setGames(lastGames);
        if (lastGroups != null) v.setStandings(Espn.LEAGUES[leagueIdx].colLabels, lastGroups);
        v.setBoardGameId(boardGameId);
        v.setCompanion(companion);
        v.setMessage(lastGames.isEmpty() ? "Loading…" : null);
        pushSettings();
    }

    void detachTop(ThorView v) {
        if (top != v) return;
        top = null;

        if (dual && !isFinishing() && !leftHome) { // top screen was closed some other way (not Home)
            dual = false;
            prefs.edit().putBoolean("dual", false).apply();
            view.setDual(false);
            pushSettings();
        }
    }

    private Set<String> favAbbrs(int idx) {
        Espn.League lg = Espn.LEAGUES[idx];
        Set<String> out = new HashSet<>();
        for (String k : favKeys) {
            int bar = k.indexOf('|');
            if (bar < 0) continue;
            if (lg.mine() || k.substring(0, bar).equals(lg.path)) out.add(k.substring(bar + 1));
        }
        return out;
    }

    private Set<String> favAbbrsFor(String path) {
        Set<String> out = new HashSet<>();
        for (String k : favKeys) if (k.startsWith(path + "|")) out.add(k.substring(path.length() + 1));
        return out;
    }

    private boolean lowBattery() { return batteryPct <= 15 && !batteryCharging; }

    private boolean quietNow() {
        return System.currentTimeMillis() < muteUntil;   // Mute alerts for 1 hour
    }

    /** Goal horn + vibration, both silenced by Settings → Mute alerts. */
    private void horn(float volume, boolean batCrack) { horn(volume, batCrack, Horn.EV_GOAL); }

    private void horn(float volume, boolean batCrack, int event) { horn(volume, batCrack, event, null); }

    /** teamAbbr: the NHL team that scored, for its own goal horn (Settings → Sound → Team goal horns). */
    private void horn(float volume, boolean batCrack, int event, String teamAbbr) {
        if (quietNow()) return;
        Horn.play(this, sound, volume, batCrack, event, teamAbbr);
    }

    /** Which sound goes with this celebration word (Settings → Sound). */
    private static int soundEvent(String word, boolean homer) {
        String w = word == null ? "" : word.toUpperCase(Locale.ROOT);
        if (homer) return Horn.EV_HR;
        if (w.contains("VICTORY") || w.contains("VICTOIRE") || w.contains("SHUTOUT") || w.contains("BLANCHISSAGE") || w.contains("GAME WON") || w.contains("PARTIE GAGN")) return Horn.EV_WIN;
        if (w.contains("OT WINNER") || w.contains("PROLONGATION")) return Horn.EV_OT;
        if (w.contains("TOUCHDOWN")) return Horn.EV_TD;
        if (w.contains("FIELD GOAL") || w.contains("PLACEMENT")) return Horn.EV_FG;
        return Horn.EV_GOAL;
    }

    private String muteLabel() {
        if (System.currentTimeMillis() >= muteUntil) return t("MUTE 1 H", "COUPER 1 H");
        return t("UNTIL ", "JUSQU'À ") + new java.text.SimpleDateFormat(clockMode == 2 || clockMode == 0 && android.text.format.DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm a", Locale.getDefault()).format(new java.util.Date(muteUntil));
    }

    /** Keep the screen on: always, never, or only while a game is live. */
    private void applyKeepScreen() {
        boolean on = keepMode == 0 || keepMode == 1 && view != null && view.anyLive();
        if (on == keepOn && getWindow() != null) return;
        keepOn = on;
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private static String t(String en, String fr) { return ThorView.tr(en, fr); }

    private String soundStyleName() {
        int st = Horn.styleOf();
        return st < 0 ? t("CUSTOM", "PERSONNALISÉ") : t(Horn.STYLE_NAMES[st][0], Horn.STYLE_NAMES[st][1]);
    }

    private void pushHiddenLeagues() {
        Set<Integer> idx = new HashSet<>();
        for (int i = 0; i < Espn.LEAGUES.length; i++) if (leagueHidden(i)) idx.add(i);
        all(v -> v.setHiddenLeagues(idx));
    }

    private void pushSettings() {
        pushHiddenLeagues();
        all(v -> v.setSound(sound));
        all(v -> v.setDim(lowBattery() ? Math.max(0.5f, DIM_LEVELS[dimLevel]) : DIM_LEVELS[dimLevel]));
        all(v -> v.setCelebrationMs(CELEB_MS[celebLen]));
        all(v -> v.setClockMode(clockMode));
        all(v -> v.setBigStandings(bigStandings));
        all(v -> v.setSettings(delaySec, ledsOn, ledsAvailable, Math.round((1 - DIM_LEVELS[dimLevel]) * 100), autoBoard));
        all(v -> v.setDisplayPrefs(hideFinal, nightMode, shotMap));
        List<String[]> it = new ArrayList<>();
        // Settings = a home with 6 cards (pages 0-5) and a few pages opened from a row (6+). See ThorView.PAGES.
        // 0 Games · 1 My teams · 2 Goal · 3 Sound · 4 Display · 5 App · 6 Goal effects · 7 Sounds by event · 8 Field demos
        // 0 GAMES: what you watch, and how it updates
        it.add(item(0, t("Open on", "Ouvrir sur"), startLeague == 1 ? t("MY TEAMS", "MES ÉQUIPES") : t("LAST LEAGUE", "DERNIÈRE LIGUE"), ThorView.SET_START_LEAGUE,
            t("Which league the app starts on", "La ligue affichée au démarrage")));
        it.add(item(0, t("Leagues", "Ligues"), (hiddenLeagues.isEmpty() ? t("ALL", "TOUTES") : hiddenLeagues.size() + t(" HIDDEN", " CACHÉES")) + "  ›", ThorView.SET_OPEN_PAGE + 9,
            t("Hide the sports you don't care about", "Cache les sports qui ne t'intéressent pas")));
        it.add(item(0, t("Live refresh", "Rafraîchissement en direct"), fastLive ? t("FAST (5-10 s)", "RAPIDE (5-10 s)") : "NORMAL (30 s)", ThorView.SET_FAST_LIVE,
            t("How often scores are checked in games", "Fréquence de mise à jour pendant les matchs")));
        it.add(item(0, t("Spoiler delay", "Délai anti-spoiler"), (delaySec == 0 ? onOff(false) : delaySec + " s") + "  ›", ThorView.SET_DELAY,
            t("Hold scores back to match your TV", "Retarde les scores pour suivre ta télé")));
        it.add(item(0, t("Hide finished games", "Cacher les matchs finis"), onOff(hideFinal), ThorView.SET_HIDE_FINAL,
            t("Only live and upcoming games", "Seulement les matchs en cours et à venir")));
        it.add(item(0, t("Open Board when my team plays", "Ouvrir le tableau quand mon équipe joue"), onOff(autoBoard), ThorView.SET_AUTOBOARD,
            t("Jumps to the Board when your game starts", "Ouvre le tableau quand ton match commence")));
        it.add(item(0, t("Rotate live games on Board", "Alterner les matchs en direct"), rotateIdx == 0 ? onOff(false) : t("EVERY ", "AUX ") + ROTATE_SEC[rotateIdx] + " s", ThorView.SET_ROTATE,
            t("The Board cycles through live games", "Le tableau alterne les matchs en direct")));
        it.add(item(0, t("Standings size", "Taille du classement"), bigStandings ? t("BIG", "GRAND") : t("FIT ALL", "TOUT"), ThorView.SET_STAND_SIZE,
            t("Big rows, or the whole table at once", "Grandes lignes, ou tout le classement")));
        it.add(item(0, t("NHL shot map (rink)", "Carte des tirs LNH"), onOff(shotMap), ThorView.SET_SHOT_MAP,
            t("Shows shots on the rink in NHL games", "Les tirs sur la patinoire (LNH)")));
        // 1 MY TEAMS: everything about "my teams" in one place
        it.add(item(1, t("My teams", "Mes équipes") + " (" + favKeys.size() + ")", t("EDIT  ›", "MODIFIER  ›"), ThorView.SET_MY_TEAMS,
            t("Pick the teams you follow", "Choisis les équipes que tu suis")));
        it.add(item(1, t("Celebrate", "Célébrer"), celebMine ? t("MY TEAMS ONLY", "MES ÉQUIPES") : t("ALL GAMES", "TOUS LES MATCHS"), ThorView.SET_CELEB_MINE,
            t("Goal party for your teams, or every game", "Fête pour tes équipes, ou tous les matchs")));
        it.add(item(1, t("Your other games", "Tes autres matchs"), onOff(notify), ThorView.SET_OTHER_TEAMS,
            t("Alerts when your team plays elsewhere", "Alertes quand ton équipe joue ailleurs")));
        it.add(item(1, t("Game reminder", "Rappel d'avant-match"), reminderIdx == 0 ? onOff(false) : REMIND_MIN[reminderIdx] + " MIN", ThorView.SET_REMINDER,
            t("A heads-up before your team's game", "Un rappel avant le match de ton équipe")));
        it.add(item(1, t("Extra alerts", "Alertes extra"), onOff(extraAlerts), ThorView.SET_EXTRA_ALERTS,
            t("Power play, lead changes, overtime…", "Avantage, changement de meneur, prolongation…")));
        it.add(item(1, t("Mute alerts", "Couper les alertes"), muteLabel(), ThorView.SET_MUTE,
            t("Silences sound and vibration for 1 hour", "Coupe le son et les vibrations pendant 1 h")));
        // 2 GOAL: what happens when a goal is scored (the screen, the lights, the buzz; the sound has its own page)
        it.add(item(2, t("Test a goal", "Tester un but"), "▶", ThorView.SET_TEST,
            t("See, hear and feel a goal right now", "Voir, entendre et sentir un but maintenant")));
        if (lastAlertWord != null) it.add(item(2, t("Replay last goal", "Revoir le dernier but"), "▶", ThorView.SET_REPLAY,
            t("Plays the last goal party again", "Rejoue la dernière fête de but")));
        it.add(item(2, t("Goal screen", "Écran de but"), onOff(celebrations), ThorView.SET_CELEB,
            t("The big GOAL! screen", "Le grand écran BUT !")));
        it.add(item(2, t("Style", "Style"), fxPresetName(), ThorView.SET_FX_PRESET,
            t("Full, clean (no flashing) or simple", "Complet, sobre (sans flash) ou simple")));
        it.add(item(2, t("Party length", "Durée de la fête"), CELEB_NAMES[celebLen], ThorView.SET_CELEB_LEN,
            t("How long the goal screen stays", "Combien de temps l'écran reste")));
        int fxOn = (ThorView.fxFlash ? 1 : 0) + (ThorView.fxBeams ? 1 : 0) + (ThorView.fxConfetti ? 1 : 0) + (ThorView.fxFireworks ? 1 : 0) + (ThorView.fxShake ? 1 : 0);
        it.add(item(2, t("Effects", "Effets"), (fxOn == 5 ? t("ALL ON", "TOUS") : fxOn == 0 ? t("ALL OFF", "AUCUN") : fxOn + " / 5") + "  ›", ThorView.SET_OPEN_PAGE + 6,
            t("Flash, beams, confetti, fireworks, shake", "Flash, faisceaux, confettis, feux, tremblement")));
        it.add(item(2, t("Stick lights", "Lumières des joysticks"), ledsAvailable ? onOff(ledsOn) : "N/A", ThorView.SET_LEDS,
            t("Joystick lights in your team's colors", "Lumières des joysticks aux couleurs de l'équipe")));
        it.add(item(2, t("Flash lights on goals", "Lumières clignotent aux buts"), onOff(ledGoal), ThorView.SET_LED_GOAL,
            t("Blink in the team's colors on a goal", "Clignotent aux couleurs de l'équipe au but")));
        it.add(item(2, t("Vibrate on goals", "Vibrer aux buts"), onOff(vibrate), ThorView.SET_VIBRATE,
            t("Buzz the console when a goal is scored", "Fait vibrer la console à chaque but")));
        // 3 SOUND: the everyday rows (the sound for each event is one level down)
        it.add(item(3, t("Sound", "Son"), onOff(sound), ThorView.SET_HORN, t("Switch for every goal sound", "Interrupteur de tous les sons de but")));
        it.add(item(3, t("Volume", "Volume"), VOL_NAMES[hornVol], ThorView.SET_HORN_VOL, t("MAX adds a loudness boost on top", "MAX ajoute un boost de volume")));
        it.add(item(3, t("Style", "Style"), soundStyleName() + "  ▶", ThorView.SET_SOUND_STYLE, t("A whole set of sounds in one tap", "Un ensemble de sons en un clic")));
        it.add(item(3, t("Team goal horns (NHL)", "Klaxons des équipes (LNH)"), onOff(Horn.teamHorns), ThorView.SET_TEAM_HORN,
            t("Each team's own horn when it scores", "Le klaxon de chaque équipe quand elle marque")));
        it.add(item(3, t("Sound length", "Durée du son"), Horn.shortHorn ? t("SHORT", "COURT") : t("FULL", "COMPLET"), ThorView.SET_HORN_SHORT,
            t("SHORT cuts every sound to about 1 s", "COURT coupe chaque son à environ 1 s")));
        it.add(item(3, t("Menu sounds & vibration", "Sons et vibration des menus"), onOff(menuFx), ThorView.SET_MENU_FX,
            t("A soft tick when you move with the controller", "Un petit clic quand tu bouges à la manette")));
        it.add(item(3, t("Test the sound", "Tester le son"), "▶  " + Horn.status, ThorView.SET_SOUND_CHECK,
            t("Plays your goal sound and checks it works", "Joue ton son de but et vérifie qu'il marche")));
        it.add(item(3, t("Sounds by event", "Sons par événement"), soundStyleName() + "  ›", ThorView.SET_OPEN_PAGE + 7,
            t("Pick a different sound for each event", "Choisis un son pour chaque événement")));
        // 4 DISPLAY
        it.add(item(4, t("Two-screen mode", "Mode deux écrans"), onOff(dual), ThorView.SET_DUAL,
            t("Use both screens: Board on top, games below", "Les deux écrans : tableau en haut, matchs en bas")));
        it.add(item(4, t("Language", "Langue"), french ? "FRANÇAIS" : "ENGLISH", ThorView.SET_LANG, t("English or French", "Anglais ou français")));
        it.add(item(4, t("Screen brightness", "Luminosité"), Math.round((1 - DIM_LEVELS[dimLevel]) * 100) + "%", ThorView.SET_DIM,
            t("Dim the screen (saves battery)", "Baisse la luminosité (économise la batterie)")));
        it.add(item(4, t("Stick light brightness", "Intensité des lumières"), LEVELS[ledBri], ThorView.SET_LED_BRI,
            t("How bright the joystick lights are", "L'intensité des lumières des joysticks")));
        it.add(item(4, t("Accent color", "Couleur d'accent"), ACCENT_NAMES[accentIdx], ThorView.SET_ACCENT,
            t("The highlight color of the app", "La couleur de mise en valeur de l'appli")));
        it.add(item(4, t("Clock", "Horloge"), CLOCK_NAMES[clockMode], ThorView.SET_CLOCK, t("12 h, 24 h, or follow the system", "12 h, 24 h, ou celle du système")));
        it.add(item(4, t("Keep screen on", "Garder l'écran allumé"), keepName(), ThorView.SET_KEEP_ON,
            t("Always, only in live games, or never", "Toujours, pendant les matchs en direct, ou jamais")));
        // 5 APP
        it.add(item(5, t("App update", "Mise à jour"), updateLabel(), ThorView.SET_UPDATE, t("Check GitHub for a new version", "Cherche une nouvelle version sur GitHub")));
        it.add(item(5, t("Help / tips", "Aide / astuces"), "?", ThorView.SET_HELP, t("Controls and handy tricks", "Commandes et astuces")));
        it.add(item(5, t("Field demos", "Démos de terrain"), "›", ThorView.SET_OPEN_PAGE + 8, t("Preview the baseball, football and hockey views", "Aperçu des vues baseball, football et hockey")));
        boolean armed = System.currentTimeMillis() - resetArmedAt < 4000;
        it.add(item(5, t("Clear MY TEAMS", "Vider MES ÉQUIPES") + " (" + favKeys.size() + ")", armed ? t("TAP AGAIN", "TOUCHEZ ENCORE") : t("CLEAR", "VIDER"), ThorView.SET_RESET,
            t("Removes all the teams you follow", "Retire toutes les équipes que tu suis")));
        boolean armedSet = System.currentTimeMillis() - settingsResetArmedAt < 4000;
        it.add(item(5, t("Reset settings", "Réinitialiser les réglages"), armedSet ? t("TAP AGAIN", "TOUCHEZ ENCORE") : t("RESET", "RÉINITIALISER"), ThorView.SET_RESET_SETTINGS,
            t("Back to the original settings", "Retour aux réglages d'origine")));
        // 6 GOAL EFFECTS (from Goal → Effects)
        it.add(item(6, t("Flash & strobe", "Flash et stroboscope"), onOff(ThorView.fxFlash), ThorView.SET_FX_FLASH, t("Bright flashes (turn off if sensitive)", "Flashs lumineux (à couper si sensible)")));
        it.add(item(6, t("Goal-lamp beams", "Faisceaux de lumière"), onOff(ThorView.fxBeams), ThorView.SET_FX_BEAMS, t("Light beams like a goal lamp", "Faisceaux comme une lampe de but")));
        it.add(item(6, t("Confetti", "Confettis"), onOff(ThorView.fxConfetti), ThorView.SET_FX_CONFETTI, t("Confetti in your team's colors", "Confettis aux couleurs de l'équipe")));
        it.add(item(6, t("Fireworks", "Feux d'artifice"), onOff(ThorView.fxFireworks), ThorView.SET_FX_FIREWORKS, t("Fireworks over the score", "Feux d'artifice sur le pointage")));
        it.add(item(6, t("Screen shake", "Écran qui tremble"), onOff(ThorView.fxShake), ThorView.SET_FX_SHAKE, t("The screen shakes on the goal", "L'écran tremble au but")));
        // 7 SOUNDS BY EVENT (from Sound → Sounds by event): which sound plays for what (tap to change, you hear it right away)
        String[][] evDesc = {
            {"When a team scores a goal", "Quand une équipe marque un but"},
            {"Football touchdown", "Touché au football"},
            {"Football field goal", "Placement au football"},
            {"When your team wins", "Quand ton équipe gagne"},
            {"Baseball home run", "Circuit au baseball"},
            {"A goal that wins in overtime", "But gagnant en prolongation"},
            {"The small alert for a second team", "La petite alerte pour une 2e équipe"},
            {"Basketball three-pointer", "Panier à 3 points au basketball"}};
        for (int i = 0; i < Horn.EVENTS.length; i++) {
            int st = Horn.eventSound[i];
            it.add(item(7, t(Horn.EVENTS[i][0], Horn.EVENTS[i][1]), t(Horn.SOUND_NAMES[st][0], Horn.SOUND_NAMES[st][1]) + (st == Horn.NONE ? "" : "  ▶"),
                ThorView.SET_EV_SOUND + i, t(evDesc[i][0], evDesc[i][1])));
        }
        it.add(item(7, t("Sound pack", "Banque de sons"), Horn.usePack ? t("NEW PACK  ▶", "NOUVELLE  ▶") : t("BUILT-IN  ▶", "INTÉGRÉE  ▶"), ThorView.SET_SOUND_SET,
            t("The new sounds, or the ones built into the app", "Les nouveaux sons, ou ceux intégrés à l'appli")));
        // 8 FIELD DEMOS (from App → Field demos)
        it.add(item(8, t("Baseball field", "Terrain de baseball"), "▶", ThorView.SET_DEMO_MLB, t("Preview the baseball field view", "Aperçu du terrain de baseball")));
        it.add(item(8, t("Football field", "Terrain de football"), "▶", ThorView.SET_DEMO_NFL, t("Preview the football field view", "Aperçu du terrain de football")));
        it.add(item(8, t("Hockey rink", "Patinoire"), "▶", ThorView.SET_DEMO_NHL, t("Preview the hockey rink view", "Aperçu de la patinoire de hockey")));
        // 9 LEAGUES, 10 SOCCER, 11 COLLEGE, 12 INTERNATIONAL (from Games → Leagues): switch a league off and it leaves the menu
        int[] hiddenIn = new int[4], totalIn = new int[4];
        for (int idx : Espn.ORDER) {
            Espn.League l = Espn.LEAGUES[idx];
            if (l.mine()) continue;
            int f = Espn.folderOf(l);
            boolean off = hiddenLeagues.contains(l.path);
            totalIn[f]++; if (off) hiddenIn[f]++;
            it.add(item(f == 0 ? 9 : 9 + f, l.label, onOff(!off), ThorView.SET_LEAGUE + idx, t("Show it in the league menu", "L'afficher dans le menu des ligues")));
        }
        String[][] folderNames = {{"", ""}, {"Soccer", "Soccer"}, {"College sports", "Sports universitaires"}, {"International", "International"}};
        for (int f = 1; f <= 3; f++)
            it.add(item(9, t(folderNames[f][0], folderNames[f][1]), (totalIn[f] - hiddenIn[f]) + " / " + totalIn[f] + "  ›", ThorView.SET_OPEN_PAGE + 9 + f,
                t("Pick which of these leagues show", "Choisis lesquelles de ces ligues s'affichent")));
        // the cards of the home: what is set right now (two short lines each)
        List<String[]> cards = new ArrayList<>();
        cards.add(new String[]{t("Refresh ", "Mise à jour ") + (fastLive ? t("FAST", "RAPIDE") : t("NORMAL", "NORMALE")),
            t("Spoiler delay ", "Délai ") + (delaySec == 0 ? onOff(false) : delaySec + " s")});
        cards.add(new String[]{favKeys.size() + t(" teams followed", " équipes suivies"),
            celebMine ? t("Party: my teams only", "Fête : mes équipes") : t("Party: all games", "Fête : tous les matchs")});
        cards.add(new String[]{t("Goal screen ", "Écran ") + (celebrations ? fxPresetName() : onOff(false)),
            t("Lights ", "Lumières ") + (ledsAvailable ? onOff(ledsOn) : "N/A") + "  ·  " + t("Buzz ", "Vibration ") + onOff(vibrate)});
        cards.add(new String[]{sound ? t("Sound ON  ·  ", "Son ON  ·  ") + VOL_NAMES[hornVol] : t("Sound OFF", "Son OFF"),
            t("Style ", "Style ") + soundStyleName()});
        cards.add(new String[]{t("Brightness ", "Luminosité ") + Math.round((1 - DIM_LEVELS[dimLevel]) * 100) + "%",
            (french ? "Français" : "English") + "  ·  " + CLOCK_NAMES[clockMode]});
        cards.add(new String[]{"ThorSport " + Updater.currentVersion(this),
            Updater.newVersion != null ? t("Update ready: ", "Mise à jour : ") + Updater.newVersion : t("Check for updates", "Chercher une MAJ")});
        all(v -> v.setSettingCards(cards));
        all(v -> v.setAppVersion(Updater.currentVersion(this)));
        all(v -> v.setSettingItems(it));
    }

    /** 0 FULL (everything), 1 CLEAN (confetti + fireworks, no flashing / shaking), 2 SIMPLE (just the logo and the word), 3 CUSTOM. */
    private int fxPreset() {
        boolean f = ThorView.fxFlash, b = ThorView.fxBeams, c = ThorView.fxConfetti, w = ThorView.fxFireworks, s = ThorView.fxShake;
        if (f && b && c && w && s) return 0;
        if (!f && !b && c && w && !s) return 1;
        if (!f && !b && !c && !w && !s) return 2;
        return 3;
    }

    private String fxPresetName() {
        switch (fxPreset()) {
            case 0: return t("FULL", "COMPLET");
            case 1: return t("CLEAN (NO FLASH)", "SOBRE (SANS FLASH)");
            case 2: return t("SIMPLE", "SIMPLE");
            default: return t("CUSTOM", "PERSONNALISÉ");
        }
    }

    private static String onOff(boolean b) { return b ? "ON" : ThorView.tr("OFF", "OFF"); }

    private String keepName() {
        if (!french) return KEEP_NAMES[keepMode];
        return keepMode == 0 ? "TOUJOURS" : keepMode == 1 ? "MATCHS EN DIRECT" : "JAMAIS";
    }

    /** {label, value, id, stepper, section} */
    private static String[] item(int section, String label, String value, int id) { return item(section, label, value, id, ""); }

    /** Same, with a one-line description shown under the label (when the list is short enough to fit it). */
    private static String[] item(int section, String label, String value, int id, String desc) {
        return new String[]{label, value, String.valueOf(id), "0", String.valueOf(section), desc};
    }

    // ------------------------------------------------------------------ in-app update (GitHub releases)
    private String updateLabel() {
        if (updatePct >= 0) return updatePct + "%";
        if (updatePct == -1) return t("DOWNLOADING…", "TÉLÉCHARGEMENT…");
        if (Updater.newVersion != null) return t("INSTALL ", "INSTALLER ") + Updater.newVersion + "  ›";
        return "v" + Updater.currentVersion(this) + " · " + t("CHECK", "VÉRIFIER");
    }

    private void checkUpdate(boolean manual) {
        new Thread(() -> {
            try {
                boolean found = Updater.check(this);
                prefs.edit().putLong("updCheck", System.currentTimeMillis()).apply();
                runOnUiThread(() -> {
                    if (found) all(v -> v.banner(t("UPDATE ", "MISE À JOUR ") + Updater.newVersion + t(" AVAILABLE · Settings → App update", " DISPONIBLE · Réglages → Mise à jour"), 0xFF34C759));
                    else if (manual) Toast.makeText(this, t("ThorSport is up to date", "ThorSport est à jour"), Toast.LENGTH_SHORT).show();
                    pushSettings();
                });
            } catch (Exception ex) {
                if (manual) runOnUiThread(() -> Toast.makeText(this, t("Can't reach GitHub", "GitHub injoignable"), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void onUpdateTap() {
        if (updatePct != -2) return;                       // already downloading
        if (Updater.newVersion == null) { checkUpdate(true); return; }
        if (!Updater.canInstall(this)) {
            Toast.makeText(this, t("Allow ThorSport to install apps, then tap App update again", "Autorisez ThorSport à installer des applis, puis touchez Mise à jour"), Toast.LENGTH_LONG).show();
            Updater.askInstallPermission(this);
            return;
        }
        updatePct = -1;
        pushSettings();
        new Thread(() -> {
            try {
                Updater.install(this, pct -> { updatePct = pct; runOnUiThread(this::pushSettings); });
                runOnUiThread(() -> { updatePct = -2; pushSettings(); });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    updatePct = -2;
                    Toast.makeText(this, t("Download failed", "Téléchargement échoué"), Toast.LENGTH_SHORT).show();
                    pushSettings();
                });
            }
        }).start();
    }

    @Override public void onSetting(int id, int dir) {
        SharedPreferences.Editor e = prefs.edit();
        if (id >= ThorView.SET_LEAGUE && id < ThorView.SET_LEAGUE + Espn.LEAGUES.length) {   // Settings → Games → Leagues: show / hide one
            String path = Espn.LEAGUES[id - ThorView.SET_LEAGUE].path;
            if (!hiddenLeagues.remove(path)) hiddenLeagues.add(path);
            prefs.edit().putStringSet("hiddenLeagues", new HashSet<>(hiddenLeagues)).apply();
            pushSettings();
            return;
        }
        if (id >= ThorView.SET_EV_SOUND && id < ThorView.SET_EV_SOUND + Horn.EVENTS.length) {   // Settings → Sound: next sound for this event
            int ev = id - ThorView.SET_EV_SOUND;
            int n = Horn.SOUND_NAMES.length;
            Horn.eventSound[ev] = ((Horn.eventSound[ev] + (dir < 0 ? -1 : 1)) % n + n) % n;
            e.putInt("evSound" + ev, Horn.eventSound[ev]).apply();
            Horn.playType(this, Horn.eventSound[ev], HORN_VOLS[hornVol], ev == Horn.EV_HR);   // hear it right away
            pushSettings();
            return;
        }
        switch (id) {
            case ThorView.SET_SOUND_CHECK: {   // plays the goal sound for real and shows what happened (also as a toast if it fails)
                int st = Horn.eventSound[Horn.EV_GOAL] == Horn.NONE ? 0 : Horn.eventSound[Horn.EV_GOAL];
                Horn.playType(this, st, HORN_VOLS[hornVol], false, true);
                return;
            }
            case ThorView.SET_SOUND_STYLE: {   // ARENA -> CLASSIC -> PARTY -> MELLOW, sets every event at once and plays the goal sound
                int cur = Horn.styleOf(), n = Horn.STYLES.length;
                int next = cur < 0 ? (dir < 0 ? n - 1 : 0) : ((cur + (dir < 0 ? -1 : 1)) % n + n) % n;
                Horn.applyStyle(next);
                for (int i = 0; i < Horn.eventSound.length; i++) e.putInt("evSound" + i, Horn.eventSound[i]);
                e.apply();
                Horn.warm(this);
                if (Horn.eventSound[Horn.EV_GOAL] != Horn.NONE) Horn.playType(this, Horn.eventSound[Horn.EV_GOAL], HORN_VOLS[hornVol], false);
                pushSettings();
                return;
            }
            case ThorView.SET_TEAM_HORN:   // each NHL team's own horn when it scores (teams without a file keep the normal goal sound)
                Horn.teamHorns = !Horn.teamHorns;
                e.putBoolean("teamHorns", Horn.teamHorns).apply();
                if (Horn.teamHorns) Horn.previewTeamHorn(this, HORN_VOLS[hornVol]);
                pushSettings();
                return;
            case ThorView.SET_SOUND_SET: {   // pack <-> built-in, and you hear the goal sound of the new set right away
                Horn.setPack(!Horn.usePack);
                e.putBoolean("soundPack", Horn.usePack).apply();
                int st = Horn.eventSound[Horn.EV_GOAL] == Horn.NONE ? 0 : Horn.eventSound[Horn.EV_GOAL];
                Horn.playType(this, st, HORN_VOLS[hornVol], false);
                pushSettings();
                return;
            }
            case ThorView.SET_DELAY:
                view.closeSettings();
                view.openDelay();
                break;
            case ThorView.SET_HORN:
                sound = !sound; e.putBoolean("sound", sound); break;
            case ThorView.SET_LEDS:
                if (!ledsAvailable) { Toast.makeText(this, "Stick lights not found on this device", Toast.LENGTH_SHORT).show(); return; }
                ledsOn = !ledsOn; e.putBoolean("leds", ledsOn); updateLeds(); break;
            case ThorView.SET_DIM:
                dimLevel = (dimLevel + 1) % DIM_LEVELS.length; e.putInt("dim", dimLevel); break;
            case ThorView.SET_AUTOBOARD:
                autoBoard = !autoBoard; e.putBoolean("autoBoard", autoBoard); break;
            case ThorView.SET_HORN_VOL:
                hornVol = (hornVol + 1) % HORN_VOLS.length; Horn.boost = hornVol == 3; e.putInt("hornVol", hornVol);
                Horn.play(this, true, HORN_VOLS[hornVol]);
                break;
            case ThorView.SET_LED_BRI:
                ledBri = (ledBri + 1) % LED_BRIS.length; e.putInt("ledBri", ledBri);
                Leds.setBrightness(LED_BRIS[ledBri]); updateLeds(); break;
            case ThorView.SET_FX_FLASH: ThorView.fxFlash = !ThorView.fxFlash; e.putBoolean("fxFlash", ThorView.fxFlash); break;
            case ThorView.SET_FX_BEAMS: ThorView.fxBeams = !ThorView.fxBeams; e.putBoolean("fxBeams", ThorView.fxBeams); break;
            case ThorView.SET_FX_CONFETTI: ThorView.fxConfetti = !ThorView.fxConfetti; e.putBoolean("fxConfetti", ThorView.fxConfetti); break;
            case ThorView.SET_FX_FIREWORKS: ThorView.fxFireworks = !ThorView.fxFireworks; e.putBoolean("fxFireworks", ThorView.fxFireworks); break;
            case ThorView.SET_FX_SHAKE: ThorView.fxShake = !ThorView.fxShake; e.putBoolean("fxShake", ThorView.fxShake); break;
            case ThorView.SET_FX_PRESET: {   // FULL -> CLEAN -> SIMPLE -> FULL
                int next = (fxPreset() + 1) % 3;
                boolean full = next == 0, clean = next == 1;
                ThorView.fxFlash = full; ThorView.fxShake = full; ThorView.fxBeams = full;
                ThorView.fxConfetti = full || clean; ThorView.fxFireworks = full || clean;
                e.putBoolean("fxFlash", ThorView.fxFlash).putBoolean("fxBeams", ThorView.fxBeams).putBoolean("fxConfetti", ThorView.fxConfetti)
                    .putBoolean("fxFireworks", ThorView.fxFireworks).putBoolean("fxShake", ThorView.fxShake);
                break;
            }
            case ThorView.SET_HORN_SOUND: break;
            case ThorView.SET_LED_GOAL: ledGoal = !ledGoal; e.putBoolean("ledGoal", ledGoal); break;
            case ThorView.SET_EXTRA_ALERTS:
                extraAlerts = !extraAlerts; e.putBoolean("extraAlerts", extraAlerts); break;
            case ThorView.SET_OTHER_TEAMS:
                notify = !notify; e.putBoolean("otherTeams", notify); break;
            case ThorView.SET_CELEB:
                celebrations = !celebrations; e.putBoolean("celebrations", celebrations); break;
            case ThorView.SET_KEEP_ON:
                keepMode = (keepMode + 1) % KEEP_NAMES.length; e.putInt("keepMode", keepMode);
                keepOn = !(keepMode == 0 || keepMode == 1 && view.anyLive());   // force applyKeepScreen to apply
                applyKeepScreen();
                break;
            case ThorView.SET_START_LEAGUE:
                startLeague = 1 - startLeague; e.putInt("startLeague", startLeague); break;
            case ThorView.SET_FAST_LIVE:
                fastLive = !fastLive; e.putBoolean("fastLive", fastLive);
                main.removeCallbacks(refreshLoop); main.postDelayed(refreshLoop, nextDelay());
                break;
            case ThorView.SET_MUTE:
                muteUntil = System.currentTimeMillis() < muteUntil ? 0 : System.currentTimeMillis() + 3600_000L;
                e.putLong("muteUntil", muteUntil);
                Toast.makeText(this, muteUntil == 0 ? t("Alerts back on", "Alertes réactivées") : t("Alerts muted for 1 hour", "Alertes coupées pendant 1 h"), Toast.LENGTH_SHORT).show();
                break;
            case ThorView.SET_HORN_SHORT:
                Horn.shortHorn = !Horn.shortHorn; e.putBoolean("hornShort", Horn.shortHorn);
                Horn.play(this, true, HORN_VOLS[hornVol]);
                break;
            case ThorView.SET_CELEB_MINE:
                celebMine = !celebMine; e.putBoolean("celebMine", celebMine); break;
            case ThorView.SET_REMINDER:
                reminderIdx = (reminderIdx + 1) % REMIND_MIN.length; e.putInt("reminder", reminderIdx); break;
            case ThorView.SET_REPLAY:
                if (lastAlertWord != null) {
                    view.closeSettings();
                    if (top != null) top.closeSettings();
                    goalAlert(lastAlertGame, lastAlertSide, lastAlertWord);
                }
                break;
            case ThorView.SET_ROTATE:
                rotateIdx = (rotateIdx + 1) % ROTATE_SEC.length; e.putInt("rotate", rotateIdx); rotatedAt = System.currentTimeMillis(); break;
            case ThorView.SET_RESET_SETTINGS:
                if (System.currentTimeMillis() - settingsResetArmedAt < 4000) {
                    settingsResetArmedAt = 0;
                    resetSettings();
                    return;
                }
                settingsResetArmedAt = System.currentTimeMillis();
                main.postDelayed(this::pushSettings, 4100);
                break;
            case ThorView.SET_HIDE_FINAL:
                hideFinal = !hideFinal; e.putBoolean("hideFinal", hideFinal); break;
            case ThorView.SET_SHOT_MAP:
                shotMap = !shotMap; e.putBoolean("shotMap", shotMap);
                if (shotMap) refresh(true);
                break;

            case ThorView.SET_VIBRATE:
                vibrate = !vibrate; Horn.vibrate = vibrate; e.putBoolean("vibrateGoal", vibrate); if (vibrate) Horn.play(this, false); break;
            case ThorView.SET_MENU_FX:
                menuFx = !menuFx; e.putBoolean("menuFx", menuFx); if (menuFx) Horn.menuFx(this, true); break;
            case ThorView.SET_CELEB_LEN:
                celebLen = (celebLen + 1) % CELEB_MS.length; e.putInt("celebLen", celebLen); break;
            case ThorView.SET_ACCENT:
                accentIdx = (accentIdx + 1) % ACCENTS.length; e.putInt("accent", accentIdx);
                ThorView.setAccent(ACCENTS[accentIdx]); break;
            case ThorView.SET_CLOCK:
                clockMode = (clockMode + 1) % CLOCK_NAMES.length; e.putInt("clock", clockMode); break;
            case ThorView.SET_LANG:
                french = !french; ThorView.fr = french; e.putBoolean("fr", french); break;
            case ThorView.SET_STAND_SIZE:
                bigStandings = !bigStandings; e.putBoolean("bigStandings", bigStandings); break;
            case ThorView.SET_DUAL:
                e.apply(); setDual(!dual); return;
            case ThorView.SET_TEST:
                view.closeSettings();
                testGoal();
                break;
            case ThorView.SET_DEMO_MLB: {
                view.closeSettings();
                if (top != null) top.closeSettings();
                Model.Game g = new Model.Game();
                g.id = "demo"; g.sport = "baseball"; g.league = "baseball/mlb"; g.state = "in";
                g.detail = "Bot 7th"; g.period = 7; g.balls = 2; g.strikes = 1; g.outs = 1;
                g.away.abbr = "NYY"; g.away.name = "Yankees"; g.away.score = "3"; g.away.color = 0xFF1F4FA8; g.away.record = "94-68";
                g.home.abbr = "TOR"; g.home.name = "Blue Jays"; g.home.score = "4"; g.home.color = 0xFF2D7DD2; g.home.record = "89-73";
                g.away.logo = "https://a.espncdn.com/i/teamlogos/mlb/500-dark/nyy.png";
                g.home.logo = "https://a.espncdn.com/i/teamlogos/mlb/500-dark/tor.png";
                String[] a = {"0", "1", "0", "0", "2", "0", "0"}, h = {"1", "0", "0", "2", "0", "0", "1"};
                for (String v : a) g.away.lines.add(v);
                for (String v : h) g.home.lines.add(v);
                g.bases[0] = true; g.bases[2] = true;
                g.runners[0] = "G. Springer"; g.runners[2] = "B. Bichette";
                g.batter = "V. Guerrero Jr."; g.batterInfo = "1-3, 2B";
                g.pitcher = "G. Cole"; g.pitcherInfo = "6.1 IP, 8 K";
                g.lastPlay = "Bichette singled to left, Springer to first";
                g.away.odds = 0.38f; g.home.odds = 0.62f; g.oddsSource = "POLYMARKET";
                final Model.Game demo = g;
                if (top != null) top.setDemoGame(demo);
                else { onTabTap(ThorView.TAB_BOARD); view.setDemoGame(demo); }
                e.apply();
                return;
            }
            case ThorView.SET_DEMO_NHL: {
                view.closeSettings();
                if (top != null) top.closeSettings();
                Model.Game g = new Model.Game();
                g.id = "demo-nhl"; g.sport = "hockey"; g.league = "hockey/nhl"; g.state = "in";
                g.detail = "7:41 - 2nd"; g.period = 2; g.clock = "7:41";
                g.away.abbr = "TOR"; g.away.name = "Maple Leafs"; g.away.score = "1"; g.away.color = 0xFF2A62C9; g.away.shots = 14;
                g.home.abbr = "MTL"; g.home.name = "Canadiens"; g.home.score = "2"; g.home.color = 0xFFC8102E; g.home.shots = 19;
                g.away.logo = "https://a.espncdn.com/i/teamlogos/nhl/500-dark/tor.png";
                g.home.logo = "https://a.espncdn.com/i/teamlogos/nhl/500-dark/mtl.png";
                g.homeSide = "left"; g.situationCode = "1451";   // MTL on the power play 5v4
                java.util.Random r = new java.util.Random(7);
                for (int n = 0; n < 33; n++) {
                    Model.Shot sh = new Model.Shot();
                    sh.home = n % 3 != 0;
                    float dist = 10 + r.nextFloat() * 45;
                    sh.x = (sh.home ? 89 - dist : -89 + dist);
                    sh.y = (r.nextFloat() - 0.5f) * 60 * (dist / 55f + 0.3f);
                    sh.missed = r.nextFloat() < 0.25f;
                    sh.time = (n < 15 ? "1st " : "2nd ") + (1 + r.nextInt(19)) + ":" + (10 + r.nextInt(49));
                    sh.who = sh.home ? "C. Caufield" : "W. Nylander";
                    g.shots.add(sh);
                }
                String[][] goals = {{"1", "C. Caufield", "70", "-8"}, {"0", "A. Matthews", "-74", "4"}, {"1", "N. Suzuki", "78", "10"}};
                for (String[] gl : goals) {
                    Model.Shot sh = new Model.Shot();
                    sh.goal = true; sh.home = "1".equals(gl[0]); sh.who = gl[1];
                    sh.x = Float.parseFloat(gl[2]); sh.y = Float.parseFloat(gl[3]); sh.time = "1st 12:03";
                    g.shots.add(sh);
                }
                final Model.Game demo = g;
                if (top != null) top.setDemoGame(demo);
                else { onTabTap(ThorView.TAB_BOARD); view.setDemoGame(demo); }
                main.postDelayed(() -> {   // a new shot arrives, to show the animation
                    Model.Shot sh = new Model.Shot();
                    sh.home = true; sh.x = 74; sh.y = -14; sh.who = "J. Slafkovsky"; sh.time = "2nd 7:30";
                    demo.shots.add(sh); demo.home.shots++;
                }, 3000);
                e.apply();
                return;
            }
            case ThorView.SET_DEMO_NFL: {
                view.closeSettings();
                if (top != null) top.closeSettings();
                Model.Game g = new Model.Game();
                g.id = "demo-nfl"; g.sport = "football"; g.league = "football/nfl"; g.state = "in";
                g.detail = "4:12 - 3rd"; g.period = 3; g.clock = "4:12";
                g.away.abbr = "BUF"; g.away.name = "Bills"; g.away.score = "17"; g.away.color = 0xFF00338D;
                g.home.abbr = "KC"; g.home.name = "Chiefs"; g.home.score = "20"; g.home.color = 0xFFE31837;
                g.away.logo = "https://a.espncdn.com/i/teamlogos/nfl/500-dark/buf.png";
                g.home.logo = "https://a.espncdn.com/i/teamlogos/nfl/500-dark/kc.png";
                String[] a = {"7", "3", "7"}, h = {"3", "10", "7"};
                for (String v : a) g.away.lines.add(v);
                for (String v : h) g.home.lines.add(v);
                g.possession = "away"; g.down = 3; g.toGo = 7; g.possessionText = "KC 38"; g.ballYard = 62;
                g.downText = "3rd & 7 at KC 38"; g.awayTimeouts = 2; g.homeTimeouts = 3;
                g.lastPlay = "J. Allen pass short right to K. Shakir for 9 yards";
                g.away.leader = "J. Allen 214 YDS"; g.home.leader = "P. Mahomes 198 YDS";
                g.away.odds = 0.44f; g.home.odds = 0.56f; g.oddsSource = "POLYMARKET";
                final Model.Game demo = g;
                if (top != null) top.setDemoGame(demo);
                else { onTabTap(ThorView.TAB_BOARD); view.setDemoGame(demo); }
                // the ball moves to show the animation: first down, then into the red zone
                main.postDelayed(() -> { demo.down = 1; demo.toGo = 10; demo.possessionText = "KC 24"; demo.ballYard = 76;
                    demo.downText = "1st & 10 at KC 24"; demo.redZone = false; demo.lastPlay = "J. Cook run up the middle for 14 yards"; }, 3500);
                main.postDelayed(() -> { demo.down = 2; demo.toGo = 3; demo.possessionText = "KC 17"; demo.ballYard = 83;
                    demo.downText = "2nd & 3 at KC 17"; demo.redZone = true; demo.lastPlay = "J. Allen scramble for 7 yards"; }, 7000);
                e.apply();
                return;
            }
            case ThorView.SET_HELP:
                view.showHelp(); break;
            case ThorView.SET_UPDATE:
                onUpdateTap(); break;
            case ThorView.SET_COMPANION:
                companion = !companion; e.putBoolean("companion", companion);
                all(v -> v.setCompanion(companion));
                break;
            case ThorView.SET_MY_TEAMS:
                view.closeSettings();
                view.openOnboarding(false, onboardLeagues());
                break;
            case ThorView.SET_RESET:
                if (System.currentTimeMillis() - resetArmedAt < 4000) {
                    favKeys.clear();
                    e.putStringSet("favKeys", new HashSet<>());
                    view.setFavKeys(new HashSet<>());
                    favorites = favAbbrs(leagueIdx);
                    all(v -> v.setFavorites(new HashSet<>(favorites)));
                    resetArmedAt = 0;
                    updateLeds();
                    Toast.makeText(this, "MY TEAMS cleared", Toast.LENGTH_SHORT).show();
                } else {
                    resetArmedAt = System.currentTimeMillis();
                    main.postDelayed(this::pushSettings, 4100);
                }
                break;
        }
        e.apply();
        pushSettings();
    }

    /**
     * Stick lights follow the game on the Board: left stick = left (away) team, right stick = right (home) team.
     * With favorite teams: both sticks in your team's color (in its games only);
     * when two of your teams play each other, each stick shows its team.
     */
    private boolean tension;   // tight finish: lights are "breathing", leave them alone

    /** Settings → Reset settings: everything back to how a new install starts (your teams are kept). */
    private void resetSettings() {
        Set<String> keepFav = new HashSet<>(favKeys);
        boolean onboarded = prefs.getBoolean("onboarded", false);
        int league = leagueIdx;
        prefs.edit().clear()
            .putStringSet("favKeys", keepFav)
            .putBoolean("onboarded", onboarded)
            .putInt("league", league)
            .apply();
        Toast.makeText(this, t("Settings reset", "Réglages réinitialisés"), Toast.LENGTH_SHORT).show();
        recreate();   // reload everything with the default values
    }

    /** Remembered colors for every team we've seen (so a favorite keeps its colors when it isn't playing). */
    private int[] teamColors(String abbr) {
        String v = prefs.getString("led_" + abbr, null);
        if (v == null) return null;
        try { String[] p2 = v.split(","); return new int[]{(int) Long.parseLong(p2[0], 16), (int) Long.parseLong(p2[1], 16)}; }
        catch (Exception e) { return null; }
    }

    private void rememberColors(List<Model.Game> games) {
        SharedPreferences.Editor e = null;
        for (Model.Game g : games) {
            for (Model.Side sd : new Model.Side[]{g.away, g.home}) {
                if (sd.ledA == 0 || sd.abbr.isEmpty() || !favorites.contains(sd.abbr)) continue;
                String v = Integer.toHexString(sd.ledA) + "," + Integer.toHexString(sd.ledB != 0 ? sd.ledB : sd.ledA);
                if (v.equals(prefs.getString("led_" + sd.abbr, null))) continue;
                if (e == null) e = prefs.edit();
                e.putString("led_" + sd.abbr, v);
            }
        }
        if (e != null) e.apply();
    }

    /** Second color for the other stick: the alternate, unless it's missing or almost the same. */
    private static int second(int a, int b) {
        if (b == 0) return a;
        int va = Leds.vivid(a), vb = Leds.vivid(b);
        int d = Math.abs(Color.red(va) - Color.red(vb)) + Math.abs(Color.green(va) - Color.green(vb)) + Math.abs(Color.blue(va) - Color.blue(vb));
        return d < 60 ? a : b;
    }

    /**
     * Best stick-light color for a team: its main color, unless that is black / grey (it would just show
     * as white, which looks like the lights never changed: many NFL teams are black, silver or navy).
     */
    private static int primary(Model.Side s) {
        int a = s.ledA != 0 ? s.ledA : s.color, b = s.ledB;
        if (b != 0 && ledScore(a) < 0.25f && ledScore(b) > ledScore(a)) return b;
        return a;
    }

    /** 0 = black or grey (useless on an LED), 1 = strong color. */
    private static float ledScore(int c) {
        int r = Color.red(c), g = Color.green(c), bl = Color.blue(c);
        int max = Math.max(r, Math.max(g, bl)), min = Math.min(r, Math.min(g, bl));
        if (max < 24) return 0f;
        return (max - min) / (float) max;   // saturation
    }

    /**
     * Stick lights:
     * · Game on the Board without your team: left stick = left team, right stick = right team.
     * · Game with your team (or no game): your team's two main colors (left = main, right = second).
     * · Two of your favorites playing each other: left team / right team.
     */
    private void updateLeds() {
        if (!ledsAvailable || tension) return;
        if (!ledsOn) { Leds.restore(this); return; }
        Model.Game g = view.boardGame();
        if (g != null && "racing".equals(g.sport)) g = null;
        if (favorites.isEmpty()) {
            if (g == null) Leds.restore(this);
            else Leds.set(primary(g.away), primary(g.home));
            return;
        }
        if (g != null && favorites.contains(g.away.abbr) && favorites.contains(g.home.abbr)) {
            Leds.set(primary(g.away), primary(g.home));
            return;
        }
        // watching a game without your team: the lights follow that game (left team / right team),
        // otherwise they looked stuck on your favorite's colors while you switched games
        if (g != null && !favorites.contains(g.away.abbr) && !favorites.contains(g.home.abbr)) {
            Leds.set(primary(g.away), primary(g.home));
            return;
        }
        // which favorite: the one on the Board, else one playing live, else the first one we know colors for
        Model.Side fav = null;
        if (g != null) fav = favorites.contains(g.away.abbr) ? g.away : favorites.contains(g.home.abbr) ? g.home : null;
        if (fav == null) for (Model.Game lg : lastGames) {
            if (!lg.isLive()) continue;
            if (favorites.contains(lg.away.abbr)) { fav = lg.away; break; }
            if (favorites.contains(lg.home.abbr)) { fav = lg.home; break; }
        }
        int a, b;
        if (fav != null && fav.ledA != 0) { a = primary(fav); b = second(a, a == fav.ledA ? fav.ledB : fav.ledA); }
        else {
            int[] known = null;
            if (fav != null) known = teamColors(fav.abbr);
            if (known == null) for (String f : favorites) { known = teamColors(f); if (known != null) break; }
            if (known == null) { Leds.restore(this); return; }
            a = known[0]; b = second(known[0], known[1]);
        }
        Leds.set(a, b);
    }

    @Override public void onSoundTap() {
        sound = !sound;
        prefs.edit().putBoolean("sound", sound).apply();
        pushSettings();
        Toast.makeText(this, sound ? t("Goal horn on", "Klaxon activé") : t("Goal horn off", "Klaxon désactivé"), Toast.LENGTH_SHORT).show();
    }

    @Override public void onClockLongPress() {
        view.openDelay();
    }

    private void cycleBrightness() {
        dimLevel = (dimLevel + 1) % DIM_LEVELS.length;
        prefs.edit().putInt("dim", dimLevel).apply();
        pushSettings();
        Toast.makeText(this, "Brightness " + Math.round((1 - DIM_LEVELS[dimLevel]) * 100) + "%", Toast.LENGTH_SHORT).show();
    }

    @Override public void onDateShift(int days) {
        dateChangedAt = System.currentTimeMillis();
        dateOffset = Math.max(-7, Math.min(7, dateOffset + days));
        view.setDateOffset(dateOffset);
        lastGames = java.util.Collections.emptyList();
        pending.clear();
        all(v -> v.setGames(lastGames));
        all(v -> v.setMessage("Loading…"));
        refresh(false);
    }

    @Override public void onFilterToggle() {
        myTeamsOnly = !myTeamsOnly;
        prefs.edit().putBoolean("myTeams", myTeamsOnly).apply();
        view.setMyTeamsOnly(myTeamsOnly);
        if (myTeamsOnly && favorites.isEmpty())
            Toast.makeText(this, "Long-press a team to add it to my teams", Toast.LENGTH_LONG).show();
    }

    private void restartLoop() {
        main.removeCallbacks(refreshLoop);
        main.post(refreshLoop);
    }

    private List<Model.Game> lastGames = java.util.Collections.emptyList();

    private String dateParam() {
        if (dateOffset == 0) return null;
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, dateOffset);
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(cal.getTime());
    }

    /** Finds every league's logo once (saved), so the league list can show them. */
    private void loadLeagueLogos() {
        new Thread(() -> {   // separate thread: never delays the scores
            boolean changed = false;
            for (int i = 0; i < Espn.LEAGUES.length; i++) {
                if (leagueLogoUrls[i] != null) continue;
                String url = Espn.leagueLogo(Espn.LEAGUES[i]);
                if (url == null) continue;
                leagueLogoUrls[i] = url;
                prefs.edit().putString("leagueLogo_" + Espn.LEAGUES[i].path, url).apply();
                changed = true;
            }
            if (changed) {
                final String[] copy = leagueLogoUrls.clone();
                main.post(() -> all(v -> v.setLeagueLogos(copy)));
            }
        }).start();
    }

    // ------------------------------------------------------------------ data
    /** One league's scores, plus goal scorers and Polymarket odds. MY TEAMS = every league with a favorite. */
    private Model.Scoreboard fetchScores(Espn.League lg, String date, boolean fromCache) throws Exception {
        if (LoL.is(lg)) {
            Model.Scoreboard sb = LoL.scores(date, fromCache);
            if (!fromCache) {
                Model.Game live = null;
                for (Model.Game g : sb.games) if (g.isLive() && (g.id.equals(boardGameId) || live == null)) live = g;
                LoL.liveStats(live);
            }
            return sb;
        }
        if ("racing/f1".equals(lg.path)) {
            Model.Scoreboard sb = F1.weekend(fromCache);
            if (sb.leagueLogo == null) sb.leagueLogo = leagueLogoUrls[leagueIdx];
            return sb;
        }
        if (!lg.mine()) {
            Model.Scoreboard sb = Espn.scores(lg, date, fromCache);
            if (!fromCache) { Extras.nhlGoals(sb.games); Extras.polymarket(sb.games); if (shotMap) Extras.nhlPlays(shotMapGame(sb.games)); if (dual) Extras.summary(gameOnTop(sb.games)); }
            return sb;
        }
        Model.Scoreboard all = new Model.Scoreboard();
        boolean any = false;
        for (Espn.League l : Espn.LEAGUES) {
            if (l.mine()) continue;
            Set<String> mine = favAbbrsFor(l.path);
            if (mine.isEmpty()) continue;
            try {
                Model.Scoreboard one = Espn.scores(l, date, fromCache);
                any = true;
                for (Model.Game g : one.games) if (g.involves(mine)) all.games.add(g);
            } catch (Exception ignored) { }
        }
        if (!any && !favKeys.isEmpty()) throw new Exception("offline");
        if (!fromCache) { Extras.nhlGoals(all.games); Extras.polymarket(all.games); if (shotMap) Extras.nhlPlays(shotMapGame(all.games)); if (dual) Extras.summary(gameOnTop(all.games)); }
        all.games.sort((a, b) -> {
            int ra = Espn.rank(a), rb = Espn.rank(b);
            return ra != rb ? ra - rb : Long.compare(a.startMs, b.startMs);
        });
        return all;
    }

    /** Only one game gets the (heavier) NHL shot map: the one on the Board, else your team's, else a live one. */
    /** The game shown on the top screen (for the GAME tab details). */
    private Model.Game gameOnTop(List<Model.Game> games) {
        String id = boardGameId;
        if (id != null) for (Model.Game g : games) if (g.id.equals(id)) return g;
        for (Model.Game g : games) if (g.involves(favorites)) return g;
        return games.isEmpty() ? null : games.get(0);
    }

    private Model.Game shotMapGame(List<Model.Game> games) {
        Model.Game pick = null;
        for (Model.Game g : games) if (!"hockey/nhl".equals(g.league) || "pre".equals(g.state)) continue;
            else if (g.id.equals(boardGameId)) return g;
            else if (pick == null && g.involves(favorites)) pick = g;
        if (pick != null) return pick;
        for (Model.Game g : games) if ("hockey/nhl".equals(g.league) && g.isLive()) return g;
        return null;
    }

    private List<Model.Group> fetchStandings(Espn.League lg, boolean fromCache) throws Exception {
        if ("racing/f1".equals(lg.path)) return F1.standings(fromCache);
        if (LoL.is(lg)) return new ArrayList<>();
        if (!lg.mine()) return Espn.standings(lg, fromCache);
        List<Model.Group> out = new ArrayList<>();
        for (Espn.League l : Espn.LEAGUES) {
            if (l.mine()) continue;
            Set<String> mine = favAbbrsFor(l.path);
            if (mine.isEmpty()) continue;
            try {
                for (Model.Group g : Espn.standings(l, fromCache)) {
                    Model.Group mg = new Model.Group();
                    mg.title = l.label + " · " + g.title;
                    mg.cols = l.colLabels;
                    for (Model.Team t : g.teams) if (mine.contains(t.abbr)) mg.teams.add(t);
                    if (!mg.teams.isEmpty()) out.add(mg);
                }
            } catch (Exception ignored) { }
        }
        return out;
    }

    /** Shows the last saved data right away (instant start, works offline). */
    private void loadFromCache() {
        final int idx = leagueIdx;
        final Espn.League lg = Espn.LEAGUES[idx];
        io.execute(() -> {
            Model.Scoreboard sb = null;
            List<Model.Group> st = null;
            if (delaySec == 0) { // saved scores could spoil a delayed game
                try { sb = fetchScores(lg, null, true); } catch (Exception ignored) { }
            }
            try { st = fetchStandings(lg, true); } catch (Exception ignored) { }
            final Model.Scoreboard fsb = sb;
            final List<Model.Group> fst = st;
            main.post(() -> {
                if (idx != leagueIdx) return;
                if (fsb != null && lastGames.isEmpty()) {
                    lastGames = fsb.games;
                    all(v -> v.setLeagueLogo(fsb.leagueLogo));
                    all(v -> v.setGames(fsb.games));
                    all(v -> v.setMessage(null));
                }
                if (fst != null && standingsLeague != idx) all(v -> v.setStandings(lg.colLabels, fst));
            });
        });
    }

    private void refresh(boolean force) {
        if (tab == ThorView.TAB_PLAYOFFS) loadPlayoffs(force);
        final int idx = leagueIdx;
        final int day = dateOffset;
        final String date = dateParam();
        final Espn.League lg = Espn.LEAGUES[idx];
        final boolean needStandings = force || standingsLeague != idx
            || System.currentTimeMillis() - standingsLoadedAt > STANDINGS_MAX_AGE_MS;
        view.setLoading(true);

        io.execute(() -> {
            Model.Scoreboard games = null;
            List<Model.Group> groups = null;
            boolean failed = false;
            try { games = fetchScores(lg, date, false); } catch (Exception e) { failed = true; }
            if (needStandings) {
                try { groups = fetchStandings(lg, false); } catch (Exception e) { failed = true; }
            }
            final Model.Scoreboard fg = games;
            final List<Model.Group> fs = groups;
            final boolean ff = failed;
            main.post(() -> {
                view.setLoading(false);
                if (idx != leagueIdx || day != dateOffset) return; // user switched meanwhile
                lastFailed = ff;
                if (fs != null) { standingsLoadedAt = System.currentTimeMillis(); standingsLeague = idx; }
                if (fg != null && syncState > 0 && syncState < 3) detectRawScore(fg.games);
                if (fg != null && dateOffset == 0) recordMoment(fg.games);
                if (fg != null || fs != null) pending.add(new Snap(System.currentTimeMillis(), idx, day, fg, fs));
                all(v -> v.setOffline(ff));
                if (ff && fg == null && lastGames.isEmpty()) all(v -> v.setMessage("Can't reach ESPN"));
                applyDue();
                main.removeCallbacks(delayTick);
                if (!pending.isEmpty()) main.postDelayed(delayTick, 1000);
                if (ff) { main.removeCallbacks(refreshLoop); main.postDelayed(refreshLoop, RETRY_MS); }
            });
        });
    }

    /** Shows the newest snapshot that is at least `delaySec` old (spoiler delay). */
    private void applyDue() {
        long cutoff = System.currentTimeMillis() - delaySec * 1000L;
        Snap due = null;
        int dueIdx = -1;
        for (int i = 0; i < pending.size(); i++) {
            if (pending.get(i).at <= cutoff) { due = pending.get(i); dueIdx = i; }
        }
        if (due == null) {
            if (lastGames.isEmpty() && !pending.isEmpty()) {
                long wait = (pending.get(0).at - cutoff) / 1000 + 1;
                all(v -> v.setMessage("Spoiler delay · showing in " + wait + "s"));
            }
            return;
        }
        // standings from any due snapshot (the newest one that has them)
        List<Model.Group> groups = null;
        for (int i = 0; i <= dueIdx; i++) if (pending.get(i).groups != null) groups = pending.get(i).groups;
        Model.Scoreboard games = null;
        for (int i = 0; i <= dueIdx; i++) if (pending.get(i).games != null) games = pending.get(i).games;
        pending.subList(0, dueIdx + 1).clear();
        if (due.league != leagueIdx || due.day != dateOffset) return;

        Espn.League lg = Espn.LEAGUES[due.league];
        if (games != null) {
            lastLeagueLogo = games.leagueLogo;
            final Model.Scoreboard sbx = games;
            all(v -> v.setLeagueLogo(sbx.leagueLogo));
            if (due.day == 0) checkEvents(games.games, lg);
            lastGames = games.games;
            syncClocks(games.games);
            rememberColors(games.games);
            all(v -> v.setGames(sbx.games));
            final long dueAt = due.at;
            all(v -> v.setUpdatedAt(dueAt));
            all(v -> v.setMessage(null));
            updateLeds();
            if (ledsAvailable && ledsOn) Leds.reassert();   // in case the Thor put its own colors back
            applyKeepScreen();
            rotateBoard(games.games);
        }
        if (groups != null) { lastGroups = groups; final List<Model.Group> gr = groups; all(v -> v.setStandings(lg.colLabels, gr)); }
    }

    // ---- Your other teams: notifications while you watch another game
    /** Score in one of your teams' games (not the one you're watching): a small card + a quick blink. */
    private final Map<String, String> lastNote = new HashMap<>();
    private final Map<String, Long> lastNoteAt = new HashMap<>();

    private void noteScore(Model.Game g, Model.Side scorer, int delta, Set<String> favs) {
        if (!notify) return;
        String key = g.away.score + "-" + g.home.score;   // one card per score, never the same one twice
        if (key.equals(lastNote.put(g.id, key))) return;
        Long lastAt = lastNoteAt.get(g.id);   // and never more than one card per game every 90 s
        if (lastAt != null && System.currentTimeMillis() - lastAt < 90_000) return;
        lastNoteAt.put(g.id, System.currentTimeMillis());
        String word = scoreWord(g, delta);
        if (word == null) return;   // basketball scores too often for cards
        if ("football".equals(g.sport) && delta < 3) return;   // extra point / 2-pt: the touchdown card is enough
        boolean yours = favs.contains(scorer.abbr);
        String title = word.replace("!", "") + " · " + scorer.abbr;
        String line = g.away.abbr + " " + g.away.score + " – " + g.home.score + " " + g.home.abbr + (g.detail.isEmpty() ? "" : "  ·  " + g.detail);
        all(v -> v.notifyCard(scorer, title, line));
        if (yours && ledsAvailable && ledsOn) Leds.blink(primary(scorer), 2);
        if (yours) horn(HORN_VOLS[hornVol] * 0.5f, false, Horn.EV_OTHER);
    }

    /** One of your teams' games just ended (while you watch another one). */
    private void noteFinal(Model.Game g, Set<String> favs) {
        if (!notify || "racing".equals(g.sport)) return;
        Model.Side won = g.away.winner ? g.away : g.home.winner ? g.home : null;
        Model.Side fav = favs.contains(g.away.abbr) ? g.away : g.home;
        String title = won == null ? t("FINAL · TIE", "FINAL · NUL")
            : favs.contains(won.abbr) ? won.abbr + t(" WIN!", " GAGNE!") : fav.abbr + t(" lose", " perd");
        String line = "FINAL  " + g.away.abbr + " " + g.away.score + " – " + g.home.score + " " + g.home.abbr;
        all(v -> v.notifyCard(won != null ? won : fav, title, line));
        if (won != null && favs.contains(won.abbr)) {
            horn(HORN_VOLS[hornVol] * 0.5f, false, Horn.EV_OTHER);
            if (ledsAvailable && ledsOn) Leds.blink(0xFFFFC400, 3);
        }
    }

    /** Favorite teams in other leagues than the one on screen: checked every 30 s in the background. */
    private final Map<String, int[]> otherScores = new HashMap<>();
    private final Map<String, String> otherStates = new HashMap<>();
    private static final long WATCH_MS = 30_000;
    private final Runnable favWatch = new Runnable() {
        @Override public void run() {
            main.removeCallbacks(this);   // only ever one loop
            main.postDelayed(this, WATCH_MS);
            if (!notify || favKeys.isEmpty()) return;
            final Espn.League cur = Espn.LEAGUES[leagueIdx];
            if (cur.mine()) return;   // "My teams" page already watches all of them
            final List<Espn.League> todo = new ArrayList<>();
            for (Espn.League l : Espn.LEAGUES)
                if (!l.mine() && !l.path.equals(cur.path) && !LoL.is(l) && !"racing/f1".equals(l.path) && !favAbbrsFor(l.path).isEmpty()) todo.add(l);
            if (todo.isEmpty()) return;
            io.execute(() -> {
                for (Espn.League l : todo) {
                    try {
                        final Model.Scoreboard sb = Espn.scores(l, null, false);
                        final Set<String> favs = favAbbrsFor(l.path);
                        main.post(() -> watchOther(sb.games, favs));
                    } catch (Exception ignored) { }
                }
            });
        }
    };

    private void watchOther(List<Model.Game> games, Set<String> favs) {
        for (Model.Game g : games) {
            if (!g.involves(favs)) continue;
            int a = g.away.scoreInt(), h = g.home.scoreInt();
            int[] seen = otherScores.get(g.id);
            int[] prev = seen == null ? null : seen.clone();
            otherScores.put(g.id, seen == null ? new int[]{a, h} : new int[]{Math.max(a, seen[0]), Math.max(h, seen[1])});
            if (prev != null) { a = Math.max(a, prev[0]); h = Math.max(h, prev[1]); }
            String prevState = otherStates.put(g.id, g.state);
            if (prevState != null && !prevState.equals(g.state)) {
                if (g.isFinal()) { noteFinal(g, favs); continue; }
                if (extraAlerts && "pre".equals(prevState) && g.isLive()) {
                    Model.Side fav = favs.contains(g.away.abbr) ? g.away : g.home;
                    all(v -> v.notifyCard(fav, t("GAME ON · ", "C'EST PARTI · ") + g.away.abbr + " @ " + g.home.abbr, t("Starting now", "Ça commence")));
                }
            }
            if (prev == null || !g.isLive()) continue;
            if (a > prev[0] && prev[0] >= 0) noteScore(g, g.away, a - prev[0], favs);
            else if (h > prev[1] && prev[1] >= 0) noteScore(g, g.home, h - prev[1], favs);
        }
    }

    /**
     * Compares with the previous refresh:
     * goals (celebration or "goal against" banner), score flashes, game start and final banners,
     * and auto-opening the Board when one of your teams starts playing.
     */
    private void checkEvents(List<Model.Game> games, Espn.League lg) {
        Model.Game followed = view.boardGame();
        Model.Game celebrateGame = null;
        Model.Side celebrateSide = null;
        int celebrateDelta = 0;
        for (Model.Game g : games) {
            if ("racing".equals(g.sport)) continue;   // F1 sessions have no score to watch
            int a = g.away.scoreInt(), h = g.home.scoreInt();
            // ESPN sometimes sends an older score for a moment (cache): only a score higher than
            // anything seen before counts, so the same goal never fires twice
            int[] seen = lastScores.get(g.id);
            int[] prev = seen == null ? null : seen.clone();
            lastScores.put(g.id, seen == null ? new int[]{a, h} : new int[]{Math.max(a, seen[0]), Math.max(h, seen[1])});
            if (prev != null) { a = Math.max(a, prev[0]); h = Math.max(h, prev[1]); }
            String prevState = lastStates.put(g.id, g.state);
            boolean mine = g.involves(favorites);
            boolean onBoard = followed != null && followed.id.equals(g.id);
            boolean watched = onBoard || mine;
            boolean other = mine && followed != null && !onBoard && followed.isLive();   // your team, but you're watching another game

            String prevDetail = lastDetail.put(g.id, g.detail);
            Integer prevPeriod = lastPeriod.put(g.id, g.period);
            if (watched && g.isLive() && prevDetail != null && !prevDetail.equals(g.detail)) {
                String up = g.detail.toUpperCase(Locale.US);
                if (extraAlerts && (up.startsWith("END") || up.contains("INTERMISSION") || up.equals("HALFTIME")) && !prevDetail.toUpperCase(Locale.US).equals(up)) {
                    // hockey: the shots so far, the stat everyone checks at the intermission
                    String shots = g.away.shots >= 0 && g.home.shots >= 0 ? t("  ·  SHOTS ", "  ·  TIRS ") + g.away.shots + "–" + g.home.shots : "";
                    all(v -> v.banner(up + " · " + g.away.abbr + " " + g.away.score + " – " + g.home.score + " " + g.home.abbr + shots, 0xFF4DA3FF));
                }
            }
            int reg = "hockey".equals(g.sport) ? 3 : "baseball".equals(g.sport) ? 9 : "soccer".equals(g.sport) ? 2 : 4;
            if (extraAlerts && watched && g.isLive() && prevPeriod != null && prevPeriod <= reg && g.period > reg && !"soccer".equals(g.sport))
                all(v -> v.banner(("baseball".equals(g.sport) ? t("EXTRA INNINGS", "MANCHES SUPP.") : t("OVERTIME", "PROLONGATION")) + " · " + g.away.abbr + " " + g.away.score
                    + " – " + g.home.score + " " + g.home.abbr, 0xFFFF3B30));

            if (extraAlerts && watched && g.isLive()) liveAlerts(g, prevPeriod, reg);

            if (prevState != null && !prevState.equals(g.state) && watched) {
                if ("pre".equals(prevState) && g.isLive()) {
                    if (extraAlerts) all(v -> v.banner(("hockey".equals(g.sport) ? t("PUCK DROP · ", "MISE AU JEU · ") : t("GAME ON · ", "C'EST PARTI · ")) + g.away.abbr + " @ " + g.home.abbr, 0xFF34C759));
                    if (mine) horn(HORN_VOLS[hornVol] * 0.5f, false);
                    if (mine && autoBoard) {
                        onFollowGame(g.id, true);
                        followed = g;
                    }
                } else if (g.isFinal() && other) {
                    noteFinal(g, favorites);
                } else if (g.isFinal()) {
                    Model.Side won = g.away.winner ? g.away : g.home.winner ? g.home : null;
                    boolean shutout = won != null && (won == g.away ? g.home : g.away).scoreInt() == 0
                        && ("hockey".equals(g.sport) || "soccer".equals(g.sport) || "baseball".equals(g.sport) || "football".equals(g.sport));
                    if (won != null && favorites.contains(won.abbr) && !"racing".equals(g.sport)) {
                        int[] wdf = worstDeficit.get(g.id);
                        boolean comeback = wdf != null && wdf[won == g.away ? 0 : 1] >= 2 && !"basketball".equals(g.sport);
                        goalAlert(g, won, comeback ? t("COMEBACK WIN!", "VICTOIRE DE REMONTÉE!") : shutout ? t("SHUTOUT!", "BLANCHISSAGE!") : t("VICTORY!", "VICTOIRE!"), 0xFFFFC400);   // gold lights
                        if (g.series != null && !g.series.isEmpty()) all(v -> v.banner(t("SERIES · ", "SÉRIE · ") + g.series.toUpperCase(Locale.US), 0xFFFFC400));
                    } else if (extraAlerts) {   // a final that isn't your team's win: only with Extra alerts
                        all(v -> v.banner(t("FINAL · ", "FINAL · ") + g.away.abbr + " " + g.away.score + " – " + g.home.score + " " + g.home.abbr
                            + (shutout ? t(" · SHUTOUT", " · BLANCHISSAGE") : "") + starOfGame(g), 0xFFFFB524));
                        if (g.series != null && !g.series.isEmpty()) all(v -> v.banner(t("SERIES · ", "SÉRIE · ") + g.series.toUpperCase(Locale.US), 0xFFFFC400));
                    }
                }
            }

            // big swing in the win chance (20+ points since the last update) in the game you watch
            float[] po = lastOdds.get(g.id);
            if (extraAlerts && watched && g.isLive() && po != null && g.away.odds >= 0 && Math.abs(g.away.odds - po[0]) >= 0.2f) {
                boolean awayGains = g.away.odds > po[0];
                Model.Side up = awayGains ? g.away : g.home;
                int from = Math.round((awayGains ? po[0] : po[1]) * 100), to = Math.round((awayGains ? g.away.odds : g.home.odds) * 100);
                all(v -> v.banner(t("WIN CHANCE · ", "CHANCES DE GAGNER · ") + up.abbr + " " + from + "% → " + to + "%", 0xFFB57BFF));
            }
            if (g.away.odds >= 0 && !g.isFinal()) lastOdds.put(g.id, new float[]{g.away.odds, g.home.odds});

            // pre-game reminder for your teams
            long until = g.startMs - System.currentTimeMillis();
            if (mine && reminderIdx > 0 && "pre".equals(g.state) && until > 0 && until <= REMIND_MIN[reminderIdx] * 60_000L && reminded.add(g.id)) {
                final Model.Game rg = g;
                all(v -> v.banner(rg.away.abbr + " @ " + rg.home.abbr + t(" in ", " dans ") + (until / 60000 + 1) + " min", 0xFF4DA3FF));
            }

            // upset: the team with under 35 % chance won
            if (extraAlerts && prevState != null && !prevState.equals(g.state) && g.isFinal() && !"racing".equals(g.sport)) {
                float[] od = lastOdds.get(g.id);
                Model.Side won = g.away.winner ? g.away : g.home.winner ? g.home : null;
                if (od != null && won != null && (won == g.away ? od[0] : od[1]) < 0.35f) {
                    final Model.Game ug = g; final Model.Side uw = won; final float[] uo = od;
                    all(v -> v.banner(t("UPSET! ", "SURPRISE! ") + uw.abbr + " (" + Math.round((uw == ug.away ? uo[0] : uo[1]) * 100) + "%) "
                        + ug.away.score + " – " + ug.home.score, 0xFFB57BFF));
                }
            }

            // close game late
            if (extraAlerts && g.isLive() && isClutch(g) && closeAlerted.add(g.id)) {
                final Model.Game cg = g;
                all(v -> v.banner(t("CLOSE GAME · ", "MATCH SERRÉ · ") + cg.away.abbr + " " + cg.away.score + " – " + cg.home.score + " " + cg.home.abbr, 0xFFFF9500));
            }

            if (prev == null || !g.isLive() && !g.isFinal()) continue;
            boolean awayUp = a > prev[0] && prev[0] >= 0, homeUp = h > prev[1] && prev[1] >= 0;
            if (awayUp || homeUp) all(v -> v.flash(g.id));
            int[] wd = worstDeficit.get(g.id);   // remember each team's biggest deficit (for COMEBACK WIN!)
            if (wd == null) { wd = new int[]{0, 0}; worstDeficit.put(g.id, wd); }
            wd[0] = Math.max(wd[0], h - a); wd[1] = Math.max(wd[1], a - h);
            if (extraAlerts && (awayUp || homeUp) && watched && !"basketball".equals(g.sport) && !"esports".equals(g.sport)) {
                int before = prev[0] - prev[1], now = a - h;
                String sc = g.away.abbr + " " + g.away.score + " – " + g.home.score + " " + g.home.abbr;
                Model.Side scorerSide = awayUp ? g.away : g.home;
                // scoring run: the same team scores 3+ times in a row
                if (awayUp != homeUp && !"baseball".equals(g.sport)) {
                    int side = awayUp ? 0 : 1;
                    int[] run = scoringRun.get(g.id);
                    if (run == null || run[0] != side) run = new int[]{side, 0};
                    run[1]++;
                    scoringRun.put(g.id, run);
                    if (run[1] >= 3) {
                        String what = "football".equals(g.sport) ? t(" STRAIGHT SCORES · ", " MARQUES DE SUITE · ") : t(" UNANSWERED GOALS · ", " BUTS SANS RÉPLIQUE · ");
                        final int n = run[1];
                        all(v -> v.banner(n + what + scorerSide.abbr + " · " + sc, scorerSide.color | 0xFF000000));
                    }
                }
                if (now == 0 && Math.abs(before) >= 2)   // came back from 2+ down
                    all(v -> v.banner(t("COMEBACK! · ", "REMONTÉE! · ") + scorerSide.abbr + t(" TIES IT · ", " ÉGALISE · ") + sc, 0xFFFFC400));
                else if (now == 0 && before != 0)
                    all(v -> v.banner(t("TIE GAME · ", "ÉGALITÉ · ") + sc, 0xFF4DA3FF));
                else if (before != 0 && Integer.signum(now) != Integer.signum(before)) {
                    String lead = now > 0 ? g.away.abbr : g.home.abbr;
                    all(v -> v.banner(t("LEAD CHANGE · ", "CHANGEMENT DE MENEUR · ") + lead + t(" LEADS ", " MÈNE ") + g.away.score + " – " + g.home.score, 0xFFFF9500));
                }
            }
            if (other) { noteScore(g, awayUp ? g.away : g.home, awayUp ? a - prev[0] : h - prev[1], favorites); continue; }
            if (!watched || celebrateGame != null) continue;
            if (awayUp) { celebrateGame = g; celebrateSide = g.away; celebrateDelta = a - prev[0]; }
            else if (homeUp) { celebrateGame = g; celebrateSide = g.home; celebrateDelta = h - prev[1]; }
        }
        autoNextGame(games);
        tensionLights(games);
        if (celebrateGame == null) return;
        if ("basketball".equals(celebrateGame.sport)) {   // NBA / WNBA: a small celebration for threes
            boolean yours = celebrateGame.involves(favorites) ? favorites.contains(celebrateSide.abbr) : !(celebMine && !favKeys.isEmpty());
            if (celebrateDelta == 3 && yours) {
                final Model.Game tg = celebrateGame;
                final Model.Side ts = celebrateSide;
                horn(HORN_VOLS[hornVol] * 0.7f, false, Horn.EV_THREE);   // Settings → Sound → 3-pointer
                if (extraAlerts) all(v -> v.banner(t("THREE! ", "TROIS POINTS! ") + ts.abbr + " · " + tg.away.abbr + " " + tg.away.score + " – " + tg.home.score + " " + tg.home.abbr,
                    ts.color | 0xFF000000));
                all(v -> v.flash(tg.id));
                if (ledsAvailable && ledsOn) Leds.blink(primary(ts), 3);
            } else if (yours && ledsAvailable && ledsOn && !tension) {
                Leds.blink(primary(celebrateSide), 2);   // every other basket: two quick blinks of the sticks
            }
            return;
        }
        String word = scoreWord(celebrateGame, celebrateDelta);
        if (word == null) return;

        // Who gets the party (horn, goal screen, lights):
        //  · game without your teams: both teams' goals
        //  · game with one of your teams: only your team's goals (the other team gets a quiet banner)
        //  · two of your teams playing each other: both
        // Football extra points / 2-point conversions are a quiet banner too (the touchdown already had its party).
        boolean quietFootball = "football".equals(celebrateGame.sport) && celebrateDelta < 3;
        boolean against = celebrateGame.involves(favorites) && !favorites.contains(celebrateSide.abbr);
        if (against && !quietFootball) {
            // goal against your team: a red banner and one short blink in their color, no party
            Model.Game g = celebrateGame;
            final Model.Side scorer = celebrateSide;
            all(v -> v.banner(word.replace("!", "") + t(" AGAINST · ", " CONTRE · ") + scorer.abbr + " · " + g.away.abbr + " " + g.away.score
                + " – " + g.home.score + " " + g.home.abbr, 0xFFE0333E));
            if (ledsAvailable && ledsOn && !tension) Leds.blink(primary(scorer), 1);
            return;
        }
        if (quietFootball || against || (celebMine && !favKeys.isEmpty() && !celebrateGame.involves(favorites))) {
            if (!extraAlerts) return;   // extra point, 2-pt try, other teams' goals: silent unless Extra alerts
            Model.Game g = celebrateGame;
            final Model.Side scorer = celebrateSide;
            all(v -> v.banner(word.replace("!", "") + " " + scorer.abbr + " · " + g.away.abbr + " " + g.away.score
                + " – " + g.home.score + " " + g.home.abbr, 0xFF8B919C));
            return;
        }
        lastAlertGame = celebrateGame; lastAlertSide = celebrateSide; lastAlertWord = word;   // Settings → Replay last goal
        goalAlert(celebrateGame, celebrateSide, word);
    }

    /**
     * Small live alerts for the game you watch (or your team's): start of a period, NFL two-minute warning,
     * NHL power play / empty net, soccer red card, hat trick.
     */
    private void liveAlerts(Model.Game g, Integer prevPeriod, int reg) {
        String score = " · " + g.away.abbr + " " + g.away.score + " – " + g.home.score + " " + g.home.abbr;
        // start of a period / quarter / second half (overtime has its own banner)
        if (prevPeriod != null && prevPeriod > 0 && g.period > prevPeriod && g.period > 1 && g.period <= reg && !"baseball".equals(g.sport)) {
            String what;
            switch (g.sport) {
                case "hockey": what = t("START OF PERIOD ", "DÉBUT DE LA PÉRIODE ") + g.period; break;
                case "soccer": what = t("SECOND HALF", "DEUXIÈME DEMIE"); break;
                default: what = t("START OF Q", "DÉBUT DU Q") + g.period; break;
            }
            final String wf = what;
            all(v -> v.banner(wf + score, 0xFF34C759));
        }
        // NFL two-minute warning (end of the 2nd and 4th quarters)
        if ("football".equals(g.sport) && (g.period == 2 || g.period == 4)) {
            float mins = 99;
            try { String[] mm = g.clock.split(":"); mins = Integer.parseInt(mm[0].trim()) + (mm.length > 1 ? Integer.parseInt(mm[1].trim()) / 60f : 0); } catch (Exception ignored) { }
            if (mins <= 2f && mins > 0f && twoMinWarned.add(g.id + "#" + g.period))
                all(v -> v.banner(t("TWO-MINUTE WARNING", "AVERTISSEMENT DES 2 MINUTES") + score, 0xFFFF9500));
        }
        // NHL power play / empty net (from the NHL feed's situation code: away goalie, away skaters, home skaters, home goalie)
        if ("hockey".equals(g.sport) && g.situationCode != null && g.situationCode.length() == 4) {
            String code = g.situationCode, prev = lastSituation.put(g.id, code);
            if (prev != null && !prev.equals(code)) {
                int ag = code.charAt(0) - '0', as = code.charAt(1) - '0', hs = code.charAt(2) - '0', hg = code.charAt(3) - '0';
                boolean prevEven = prev.length() == 4 && prev.charAt(1) == prev.charAt(2) && prev.charAt(0) != '0' && prev.charAt(3) != '0';
                if (ag == 0 || hg == 0) {
                    String pulled = ag == 0 ? g.away.abbr : g.home.abbr;
                    if (prev.charAt(ag == 0 ? 0 : 3) != '0')
                        all(v -> v.banner(t("EMPTY NET · ", "FILET DÉSERT · ") + pulled + t(" PULLS THE GOALIE", " RETIRE SON GARDIEN"), 0xFFFF3B30));
                } else if (as != hs && prevEven) {
                    Model.Side pp = as > hs ? g.away : g.home;
                    int big = Math.max(as, hs), small = Math.min(as, hs);
                    all(v -> v.banner(t("POWER PLAY · ", "AVANTAGE NUMÉRIQUE · ") + pp.abbr + " (" + big + t(" ON ", " CONTRE ") + small + ")", pp.color | 0xFF000000));
                }
            }
        }
        // soccer red card / hat trick (from the scoring list)
        int reds = 0;
        Map<String, Integer> goalsBy = new HashMap<>();
        for (Model.Goal gl : g.goals) {
            if (gl.red) { reds++; continue; }
            if ("OG".equals(gl.extra) || gl.who == null || gl.who.isEmpty() || "Goal".equals(gl.who)) continue;
            String key = (gl.home ? "H|" : "A|") + gl.who;
            goalsBy.put(key, goalsBy.getOrDefault(key, 0) + 1);
        }
        Integer prevReds = lastRedCards.put(g.id, reds);
        if (prevReds != null && reds > prevReds && "soccer".equals(g.sport)) {
            Model.Goal last = null;
            for (Model.Goal gl : g.goals) if (gl.red) last = gl;
            if (last != null) {
                Model.Side side = last.home ? g.home : g.away;
                String who = last.who;
                all(v -> v.banner(t("RED CARD · ", "CARTON ROUGE · ") + side.abbr + ("Goal".equals(who) ? "" : " · " + who), 0xFFE0333E));
            }
        }
        Map<String, Integer> prevGoals = lastScorerGoals.put(g.id, goalsBy);
        if (prevGoals != null && ("hockey".equals(g.sport) || "soccer".equals(g.sport))) {
            for (Map.Entry<String, Integer> en : goalsBy.entrySet()) {
                if (en.getValue() >= 3 && prevGoals.getOrDefault(en.getKey(), 0) < 3) {
                    Model.Side side = en.getKey().startsWith("H|") ? g.home : g.away;
                    String who = en.getKey().substring(2);
                    all(v -> v.banner(t("HAT TRICK! · ", "TOUR DU CHAPEAU! · ") + who + " · " + side.abbr, 0xFFFFC400));
                }
            }
        }
    }

    /** Late in the game and within one score. */
    private static boolean isClutch(Model.Game g) {
        int a = g.away.scoreInt(), h = g.home.scoreInt();
        if (a < 0 || h < 0) return false;
        int diff = Math.abs(a - h);
        float mins = 99;
        try { String[] mm = g.clock.split(":"); mins = Integer.parseInt(mm[0]) + (mm.length > 1 ? Integer.parseInt(mm[1]) / 60f : 0); } catch (Exception ignored) { }
        switch (g.sport) {
            case "hockey": return g.period >= 3 && mins <= 5 && diff <= 1;
            case "basketball": return g.period >= 4 && mins <= 3 && diff <= 5;
            case "football": return g.period >= 4 && mins <= 5 && diff <= 7;
            case "soccer":
                try { return Integer.parseInt(g.clock.replaceAll("[^0-9].*", "")) >= 80 && diff <= 1; } catch (Exception e) { return false; }
            case "baseball": return g.period >= 8 && diff <= 1;
            default: return false;
        }
    }

    /** Your team in a tight finish: the stick lights breathe slowly in its color. */
    private void tensionLights(List<Model.Game> games) {
        if (!ledsAvailable || !ledsOn || favorites.isEmpty()) { tension = false; return; }
        Model.Game hot = null;
        Model.Game onBoard = view.boardGame();   // only the game you're watching, so other games never freeze the lights
        if (onBoard != null) for (Model.Game g : games)
            if (g.id.equals(onBoard.id) && g.isLive() && g.involves(favorites) && isClutch(g)) { hot = g; break; }
        if (hot != null) {
            tension = true;
            Model.Side hs = favorites.contains(hot.away.abbr) ? hot.away : hot.home;
            Leds.breathe(primary(hs));
        } else if (tension) {
            tension = false;
            updateLeds();
        }
    }

    /** " · ★ SUZUKI 2 GOALS": the player with the most goals (2 or more) in a finished game, or "". */
    private static String starOfGame(Model.Game g) {
        Map<String, Integer> count = new HashMap<>();
        for (Model.Goal gl : g.goals) {
            if (gl.red || "OG".equals(gl.extra) || gl.who == null || gl.who.isEmpty() || "Goal".equals(gl.who)) continue;
            count.put(gl.who, count.getOrDefault(gl.who, 0) + 1);
        }
        String best = null; int most = 1;
        for (Map.Entry<String, Integer> en : count.entrySet()) if (en.getValue() > most) { best = en.getKey(); most = en.getValue(); }
        if (best == null) return "";
        return " · ★ " + best.toUpperCase(Locale.US) + " " + most + t(" GOALS", " BUTS");
    }

    /** Settings → Rotate live games on Board: when none of your teams is playing, the Board moves to the next live game. */
    private void rotateBoard(List<Model.Game> games) {
        if (rotateIdx == 0 || games == null) return;
        long now = System.currentTimeMillis();
        if (now - rotatedAt < ROTATE_SEC[rotateIdx] * 1000L) return;
        for (Model.Game g : games) if (g.isLive() && g.involves(favorites)) return;   // your team plays: stay on it
        List<Model.Game> live = new ArrayList<>();
        for (Model.Game g : games) if (g.isLive()) live.add(g);
        if (live.size() < 2) return;
        Model.Game cur = view.boardGame();
        int i = 0;
        for (int k = 0; k < live.size(); k++) if (cur != null && live.get(k).id.equals(cur.id)) i = k + 1;
        rotatedAt = now;
        onFollowGame(live.get(i % live.size()).id, false);
    }

    /** 2 minutes after the followed game ends, the Board moves to the next live game (yours first). */
    private void autoNextGame(List<Model.Game> games) {
        Model.Game cur = view.boardGame();
        if (cur == null || !cur.isFinal()) { followedFinalAt = 0; return; }
        if (followedFinalAt == 0) { followedFinalAt = System.currentTimeMillis(); return; }
        if (System.currentTimeMillis() - followedFinalAt < 120_000) return;
        Model.Game next = null;
        for (Model.Game g : games) if (g.isLive() && g.involves(favorites)) { next = g; break; }
        if (next == null) for (Model.Game g : games) if (g.isLive()) { next = g; break; }
        if (next != null) { followedFinalAt = 0; onFollowGame(next.id, false); }
    }

    /** Settings → Test a goal: shows the party, horn and stick lights with the game on the Board. */
    private void testGoal() {
        Model.Game g = view.boardGame();
        if (g == null || "racing".equals(g.sport)) {
            g = new Model.Game();
            g.sport = "hockey";
            g.away.abbr = "AWY"; g.away.score = "1"; g.away.color = 0xFF4DA3FF;
            g.home.abbr = "HOM"; g.home.score = "2"; g.home.color = 0xFFE0333E;
            g.detail = "TEST";
        }
        Model.Side side = favorites.contains(g.away.abbr) ? g.away : g.home;
        // Baseball: the test shows the home run celebration (grand slam), it's the fun one.
        String word = "baseball".equals(g.sport) ? ThorView.homeRunWord(4) : scoreWord(g, 1);
        goalAlert(g, side, word == null ? "SCORE!" : word);
    }

    private void goalAlert(Model.Game g, Model.Side side, String word) { goalAlert(g, side, word, side.color); }

    private void goalAlert(Model.Game g, Model.Side side, String word, int ledColor) {
        if (celebrations && top != null) {
            // Two-screen mode: one party spread over both screens (logo + word on top, score + scorer below).
            long start = System.currentTimeMillis();
            top.celebrate(g, side, word, ThorView.CELEB_TOP, start);
            view.celebrate(g, side, word, ThorView.CELEB_BOTTOM, start);
        }
        else if (celebrations) view.celebrate(g, side, word);
        else all(v -> v.banner(word + " " + side.abbr + " · " + g.away.abbr + " " + g.away.score + " – " + g.home.score + " " + g.home.abbr, side.color | 0xFF000000));
        boolean homer = ThorView.isHomeRunWord(word);
        horn(HORN_VOLS[hornVol], homer, soundEvent(word, homer), "hockey/nhl".equals(g.league) ? side.abbr : null);
        if (!ledGoal) { }
        else if (ledsAvailable && ledsOn && homer) {
            // the light follows the ball from the left stick to the right one, then the team's colors (gold for a grand slam)
            int a = side.ledA != 0 ? side.ledA : side.color;
            Leds.homeRun(a, ThorView.isGrandSlamWord(word) ? 0xFFFFC400 : second(a, side.ledB));
        } else if (ledsAvailable && ledsOn) {
            if (ledColor == side.color && side.ledA != 0) Leds.flash2(side.ledA, second(side.ledA, side.ledB));
            else Leds.flash(ledColor);
        }
    }

    /** What to shout. Basketball scores too often, so no celebration there. */
    private static String scoreWord(Model.Game g, int delta) {
        switch (g.sport) {
            case "hockey":
                // overtime goal (not the shootout) ends the game: say so
                String dt = g.detail.toUpperCase(Locale.US);
                if (g.period >= 4 && !dt.contains("SO") && !dt.contains("SHOOTOUT")) return ThorView.tr("OT WINNER!", "BUT EN PROLONGATION!");
                return ThorView.tr("GOAL!", "BUT!");
            case "soccer": return ThorView.tr("GOAL!", "BUT!");
            case "football":
                if (delta >= 6) return "TOUCHDOWN!";
                if (delta == 3) return ThorView.tr("FIELD GOAL!", "PLACEMENT!");
                if (delta == 2) return ThorView.tr("2 POINTS!", "2 POINTS!");
                return ThorView.tr("EXTRA POINT!", "TRANSFORMATION!");
            case "baseball":
                if (ThorView.isHomeRunPlay(g.lastPlay)) return ThorView.homeRunWord(delta);
                return delta == 1 ? ThorView.tr("RUN!", "POINT!") : delta + ThorView.tr(" RUNS!", " POINTS!");
            case "esports": return ThorView.tr("GAME WON!", "PARTIE GAGNÉE!");
            default: return null;
        }
    }
}
