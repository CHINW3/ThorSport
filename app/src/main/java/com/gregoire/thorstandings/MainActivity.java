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

    private static final long BOARD_REFRESH_MS = 10_000;       // BOARD tab, followed game live
    private static final long LIVE_REFRESH_MS = 30_000;        // while a game is live
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
    private boolean celebrations, keepOn, nightMode, hideFinal, shotMap;
    private BroadcastReceiver updateReceiver;
    private volatile int updatePct = -2;   // -2 idle, -1 downloading (size unknown), 0..100 downloading
    private long resetArmedAt;
    private static final float[] HORN_VOLS = {0.35f, 0.7f, 1f};
    private static final float[] LED_BRIS = {0.3f, 0.7f, 1f};
    private static final String[] LEVELS = {"LOW", "MED", "HIGH"};
    private final Map<String, String> lastDetail = new HashMap<>();
    private final Map<String, Integer> lastPeriod = new HashMap<>();
    private long followedFinalAt;
    private boolean quietHours, vibrate, saver, french, bigStandings;
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
    private final Map<String, int[]> lastScores = new HashMap<>();
    private final Map<String, String> lastStates = new HashMap<>();

    private final Runnable refreshLoop = new Runnable() {
        @Override public void run() {
            refresh(false);
            main.postDelayed(this, nextDelay());
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
        if (tab >= tabCount()) tab = ThorView.TAB_SCORES;
        favorites = favAbbrs(leagueIdx);
        sound = prefs.getBoolean("sound", true);
        myTeamsOnly = prefs.getBoolean("myTeams", false);
        dimLevel = Math.min(prefs.getInt("dim", 0), DIM_LEVELS.length - 1);
        boardGameId = prefs.getString("boardGame", null);
        delaySec = prefs.getInt("delay", 0);
        ledsOn = prefs.getBoolean("leds", true);
        autoBoard = prefs.getBoolean("autoBoard", true);
        hornVol = prefs.getInt("hornVol", 1);
        ledBri = prefs.getInt("ledBri", 1);
        celebrations = prefs.getBoolean("celebrations", true);
        keepOn = prefs.getBoolean("keepOn", true);
        nightMode = prefs.getBoolean("night", false);
        hideFinal = prefs.getBoolean("hideFinal", false);
        shotMap = prefs.getBoolean("shotMap", true);
        companion = prefs.getBoolean("companion", true);
        Leds.setBrightness(LED_BRIS[ledBri]);
        quietHours = prefs.getBoolean("quiet", false);
        bigStandings = prefs.getBoolean("bigStandings", true);
        vibrate = prefs.getBoolean("vibrate", true);
        saver = prefs.getBoolean("saver", true);
        french = prefs.getBoolean("fr", java.util.Locale.getDefault().getLanguage().equals("fr"));
        celebLen = prefs.getInt("celebLen", 1);
        accentIdx = prefs.getInt("accent", 0);
        clockMode = prefs.getInt("clock", 0);
        Horn.vibrate = vibrate;
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
        if (running) { main.removeCallbacks(refreshLoop); main.post(refreshLoop); }   // fresh data right away
    }

    /** Completely hidden (and no top screen): stop refreshing and give the lights back. Saves battery and data. */
    @Override protected void onStop() {
        super.onStop();
        if (!running || (TopActivity.current != null && !leftHome)) return;
        main.removeCallbacks(refreshLoop);
        main.removeCallbacks(delayTick);
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
            case KeyEvent.KEYCODE_BUTTON_L2: switchLeague((leagueIdx + Espn.LEAGUES.length - 1) % Espn.LEAGUES.length); return true;
            case KeyEvent.KEYCODE_BUTTON_R2: switchLeague((leagueIdx + 1) % Espn.LEAGUES.length); return true;
            case KeyEvent.KEYCODE_BUTTON_START: v.openSettings(); return true;
            case KeyEvent.KEYCODE_BUTTON_SELECT: v.openLeaguePicker(); return true;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: refresh(true); return true;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: onViewMode(!dual ? 1 : companion ? 0 : 2); return true;   // R3 cycles the 3 view modes
            default: return false;
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
            view.controllerMove(dx, dy);
            return true;
        }
        boolean boardSwitch = tab == ThorView.TAB_BOARD && dx != 0;
        if (!boardSwitch && now - stickHeldSince > 450 && now - stickLastRepeat > 160) {   // held: repeat in lists
            stickLastRepeat = now;
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
        if (syncState == 1) return 5_000;   // Sync with TV: check often to catch the score quickly
        if (dateOffset != 0 && System.currentTimeMillis() - dateChangedAt > 30 * 60_000) main.post(() -> onDateShift(-dateOffset));
        if (lastFailed) return RETRY_MS;
        Model.Game b = view.boardGame();
        if (tab == ThorView.TAB_BOARD && b != null && b.isLive()) return BOARD_REFRESH_MS;
        if (view.anyLive()) return LIVE_REFRESH_MS;
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
        if (tab >= tabCount()) { tab = ThorView.TAB_SCORES; prefs.edit().putInt("tab", tab).apply(); view.setTab(tab); }
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
    private int tabCount() { return Espn.LEAGUES[leagueIdx].mine() ? 2 : 3; }

    @Override public void onTabTap(int t) {
        if (t >= tabCount()) t = ThorView.TAB_SCORES;
        if (t == tab) return;
        tab = t;
        prefs.edit().putInt("tab", tab).apply();
        view.setTab(tab);
        if (tab == ThorView.TAB_STANDINGS) refresh(false);
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
    private List<Integer> onboardLeagues() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < Espn.LEAGUES.length; i++) {
            Espn.League l = Espn.LEAGUES[i];
            if (l.mine() || l.path.startsWith("racing") || l.path.startsWith("esports")) continue;
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

    @Override public void onFollowGame(String gameId, boolean openBoard) {
        if (tension && !gameId.equals(boardGameId)) tension = false;
        boardGameId = gameId;
        prefs.edit().putString("boardGame", gameId).apply();
        all(v -> v.setBoardGameId(gameId));
        updateLeds();
        if (openBoard && !dual) onTabTap(ThorView.TAB_BOARD);   // in two-screen mode the Board is already on top
    }

    // ------------------------------------------------------------------ spoiler delay
    private int syncState;          // 0 off, 1 waiting for a score in the live feed, 2 score seen live: waiting for your tap
    private long syncDetectedAt;
    private final Map<String, String> rawScores = new HashMap<>();

    private void setDelay(int sec) {
        delaySec = Math.max(0, Math.min(600, sec));
        prefs.edit().putInt("delay", delaySec).apply();
        main.removeCallbacks(delayTick);
        main.post(delayTick);
        pushSettings();
    }

    @Override public void onDelayAction(int type, int value) {
        if (type == 0) setDelay(delaySec + value);
        else if (type == 1) setDelay(value);
        else if (type == 2) {
            syncState = 1;
            rawScores.clear();
            all(v -> v.setSync(1, System.currentTimeMillis()));
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

    /** ▭ menu (and R3): 0 = one screen, 1 = two screens classic, 2 = two screens + game info. */
    @Override public void onViewMode(int mode) {
        boolean wantCompanion = mode == 2;
        if (wantCompanion != companion) {
            companion = wantCompanion;
            prefs.edit().putBoolean("companion", companion).apply();
            all(v -> v.setCompanion(companion));
        }
        if ((mode > 0) != dual) setDual(mode > 0);
        else if (dual) {   // already two screens: just move the bottom to the right tab
            if (companion) { if (tab != ThorView.TAB_STANDINGS) onTabTap(ThorView.TAB_BOARD); }
            else if (tab == ThorView.TAB_BOARD) onTabTap(ThorView.TAB_SCORES);
        }
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
        if (!quietHours) return false;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 23 || h < 8;
    }

    private static String t(String en, String fr) { return ThorView.tr(en, fr); }

    private void pushSettings() {
        all(v -> v.setSound(sound));
        all(v -> v.setDim(lowBattery() ? Math.max(0.5f, DIM_LEVELS[dimLevel]) : DIM_LEVELS[dimLevel]));
        all(v -> v.setCelebrationMs(CELEB_MS[celebLen]));
        all(v -> v.setClockMode(clockMode));
        view.setSaver(saver);
        all(v -> v.setBigStandings(bigStandings));
        all(v -> v.setSettings(delaySec, ledsOn, ledsAvailable, Math.round((1 - DIM_LEVELS[dimLevel]) * 100), autoBoard));
        all(v -> v.setDisplayPrefs(hideFinal, nightMode, shotMap));
        List<String[]> it = new ArrayList<>();
        // 0 GENERAL
        it.add(item(0, t("Two-screen mode", "Mode deux écrans"), onOff(dual), ThorView.SET_DUAL));
        it.add(item(0, t("Two-screen: bottom shows", "Deux écrans : en bas"), companion ? t("GAME INFO", "INFOS MATCH") : t("CLASSIC", "CLASSIQUE"), ThorView.SET_COMPANION));
        it.add(item(0, t("Spoiler delay", "Délai anti-spoiler"), (delaySec == 0 ? onOff(false) : delaySec + " s") + "  ›", ThorView.SET_DELAY));
        it.add(item(0, t("Language", "Langue"), french ? "FRANÇAIS" : "ENGLISH", ThorView.SET_LANG));
        it.add(item(0, t("Clock", "Horloge"), CLOCK_NAMES[clockMode], ThorView.SET_CLOCK));
        it.add(item(0, t("Accent color", "Couleur d'accent"), ACCENT_NAMES[accentIdx], ThorView.SET_ACCENT));
        it.add(item(0, t("Screen brightness", "Luminosité"), Math.round((1 - DIM_LEVELS[dimLevel]) * 100) + "%", ThorView.SET_DIM));
        it.add(item(0, t("Night dim (11 PM–7 AM)", "Mode nuit (23 h–7 h)"), onOff(nightMode), ThorView.SET_NIGHT));
        it.add(item(0, t("Screensaver clock (10 min)", "Économiseur horloge (10 min)"), onOff(saver), ThorView.SET_SAVER));
        it.add(item(0, t("Keep screen on", "Garder l'écran allumé"), onOff(keepOn), ThorView.SET_KEEP_ON));
        // 1 GOALS & ALERTS
        it.add(item(1, t("Goal horn", "Klaxon de but"), onOff(sound), ThorView.SET_HORN));
        it.add(item(1, t("Horn volume", "Volume du klaxon"), LEVELS[hornVol], ThorView.SET_HORN_VOL));
        it.add(item(1, t("Quiet hours (11 PM–8 AM)", "Heures calmes (23 h–8 h)"), onOff(quietHours), ThorView.SET_QUIET));
        it.add(item(1, t("Vibration", "Vibration"), onOff(vibrate), ThorView.SET_VIBRATE));
        it.add(item(1, t("Goal screen", "Écran de but"), onOff(celebrations), ThorView.SET_CELEB));
        it.add(item(1, t("Goal screen length", "Durée de l'écran de but"), CELEB_NAMES[celebLen], ThorView.SET_CELEB_LEN));
        it.add(item(1, t("Test a goal", "Tester un but"), "▶", ThorView.SET_TEST));
        // 2 STICK LIGHTS
        it.add(item(2, t("Stick lights", "Lumières des joysticks"), ledsAvailable ? onOff(ledsOn) : "N/A", ThorView.SET_LEDS));
        it.add(item(2, t("Light brightness", "Intensité des lumières"), LEVELS[ledBri], ThorView.SET_LED_BRI));
        it.add(item(2, t("Test a goal", "Tester un but"), "▶", ThorView.SET_TEST));
        // 3 SCORES & BOARD
        it.add(item(3, t("Hide finished games", "Cacher les matchs finis"), onOff(hideFinal), ThorView.SET_HIDE_FINAL));
        it.add(item(3, t("Standings size", "Taille du classement"), bigStandings ? t("BIG", "GRAND") : t("FIT ALL", "TOUT"), ThorView.SET_STAND_SIZE));
        it.add(item(3, t("Open Board when my team plays", "Ouvrir le tableau quand mon équipe joue"), onOff(autoBoard), ThorView.SET_AUTOBOARD));
        it.add(item(3, t("NHL shot map (rink)", "Carte des tirs LNH"), onOff(shotMap), ThorView.SET_SHOT_MAP));
        it.add(item(3, t("Baseball field demo", "Démo terrain de baseball"), "▶", ThorView.SET_DEMO_MLB));
        it.add(item(3, t("Football field demo", "Démo terrain de football"), "▶", ThorView.SET_DEMO_NFL));
        it.add(item(3, t("Hockey rink demo", "Démo patinoire"), "▶", ThorView.SET_DEMO_NHL));
        // 4 APP
        it.add(item(4, t("My teams", "Mes équipes") + " (" + favKeys.size() + ")", t("EDIT  ›", "MODIFIER  ›"), ThorView.SET_MY_TEAMS));
        it.add(item(4, t("App update", "Mise à jour"), updateLabel(), ThorView.SET_UPDATE));
        it.add(item(4, t("Help / tips", "Aide / astuces"), "?", ThorView.SET_HELP));
        boolean armed = System.currentTimeMillis() - resetArmedAt < 4000;
        it.add(item(4, t("Clear MY TEAMS", "Vider MES ÉQUIPES") + " (" + favKeys.size() + ")", armed ? t("TAP AGAIN", "TOUCHEZ ENCORE") : t("CLEAR", "VIDER"), ThorView.SET_RESET));
        all(v -> v.setAppVersion(Updater.currentVersion(this)));
        all(v -> v.setSettingItems(it));
    }

    private static String onOff(boolean b) { return b ? "ON" : ThorView.tr("OFF", "OFF"); }

    /** {label, value, id, stepper, section} */
    private static String[] item(int section, String label, String value, int id) {
        return new String[]{label, value, String.valueOf(id), "0", String.valueOf(section)};
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
        switch (id) {
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
                hornVol = (hornVol + 1) % HORN_VOLS.length; e.putInt("hornVol", hornVol);
                Horn.play(this, true, HORN_VOLS[hornVol]);
                break;
            case ThorView.SET_LED_BRI:
                ledBri = (ledBri + 1) % LED_BRIS.length; e.putInt("ledBri", ledBri);
                Leds.setBrightness(LED_BRIS[ledBri]); updateLeds(); break;
            case ThorView.SET_CELEB:
                celebrations = !celebrations; e.putBoolean("celebrations", celebrations); break;
            case ThorView.SET_KEEP_ON:
                keepOn = !keepOn; e.putBoolean("keepOn", keepOn);
                if (keepOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                break;
            case ThorView.SET_NIGHT:
                nightMode = !nightMode; e.putBoolean("night", nightMode); break;
            case ThorView.SET_HIDE_FINAL:
                hideFinal = !hideFinal; e.putBoolean("hideFinal", hideFinal); break;
            case ThorView.SET_SHOT_MAP:
                shotMap = !shotMap; e.putBoolean("shotMap", shotMap);
                if (shotMap) refresh(true);
                break;
            case ThorView.SET_QUIET:
                quietHours = !quietHours; e.putBoolean("quiet", quietHours); break;
            case ThorView.SET_VIBRATE:
                vibrate = !vibrate; Horn.vibrate = vibrate; e.putBoolean("vibrate", vibrate); break;
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
            case ThorView.SET_SAVER:
                saver = !saver; e.putBoolean("saver", saver); break;
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
     * · No favorite: left stick = left team, right stick = right team (game on the Board).
     * · Favorite team: its two main colors (left = main, right = second), all the time.
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
            if (!fromCache) { Extras.nhlGoals(sb.games); Extras.polymarket(sb.games); if (shotMap) Extras.nhlPlays(shotMapGame(sb.games)); }
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
        if (!fromCache) { Extras.nhlGoals(all.games); Extras.polymarket(all.games); if (shotMap) Extras.nhlPlays(shotMapGame(all.games)); }
        all.games.sort((a, b) -> {
            int ra = Espn.rank(a), rb = Espn.rank(b);
            return ra != rb ? ra - rb : Long.compare(a.startMs, b.startMs);
        });
        return all;
    }

    /** Only one game gets the (heavier) NHL shot map: the one on the Board, else your team's, else a live one. */
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
                if (fg != null && syncState > 0) detectRawScore(fg.games);
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
            rememberColors(games.games);
            all(v -> v.setGames(sbx.games));
            final long dueAt = due.at;
            all(v -> v.setUpdatedAt(dueAt));
            all(v -> v.setMessage(null));
            updateLeds();
            if (ledsAvailable && ledsOn) Leds.reassert();   // in case the Thor put its own colors back
        }
        if (groups != null) { lastGroups = groups; final List<Model.Group> gr = groups; all(v -> v.setStandings(lg.colLabels, gr)); }
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
            int[] prev = lastScores.put(g.id, new int[]{a, h});
            String prevState = lastStates.put(g.id, g.state);
            boolean mine = g.involves(favorites);
            boolean watched = (followed != null && followed.id.equals(g.id)) || mine;

            String prevDetail = lastDetail.put(g.id, g.detail);
            Integer prevPeriod = lastPeriod.put(g.id, g.period);
            if (watched && g.isLive() && prevDetail != null && !prevDetail.equals(g.detail)) {
                String up = g.detail.toUpperCase(Locale.US);
                if ((up.startsWith("END") || up.contains("INTERMISSION") || up.equals("HALFTIME")) && !prevDetail.toUpperCase(Locale.US).equals(up))
                    all(v -> v.banner(up + " · " + g.away.abbr + " " + g.away.score + " – " + g.home.score + " " + g.home.abbr, 0xFF4DA3FF));
            }
            int reg = "hockey".equals(g.sport) ? 3 : "baseball".equals(g.sport) ? 9 : "soccer".equals(g.sport) ? 2 : 4;
            if (watched && g.isLive() && prevPeriod != null && prevPeriod <= reg && g.period > reg && !"soccer".equals(g.sport))
                all(v -> v.banner(("baseball".equals(g.sport) ? t("EXTRA INNINGS", "MANCHES SUPP.") : t("OVERTIME", "PROLONGATION")) + " · " + g.away.abbr + " " + g.away.score
                    + " – " + g.home.score + " " + g.home.abbr, 0xFFFF3B30));

            if (prevState != null && !prevState.equals(g.state) && watched) {
                if ("pre".equals(prevState) && g.isLive()) {
                    all(v -> v.banner(("hockey".equals(g.sport) ? t("PUCK DROP · ", "MISE AU JEU · ") : t("GAME ON · ", "C'EST PARTI · ")) + g.away.abbr + " @ " + g.home.abbr, 0xFF34C759));
                    if (mine) Horn.play(this, sound && !quietNow(), HORN_VOLS[hornVol] * 0.5f);
                    if (mine && autoBoard) {
                        onFollowGame(g.id, true);
                        followed = g;
                    }
                } else if (g.isFinal()) {
                    Model.Side won = g.away.winner ? g.away : g.home.winner ? g.home : null;
                    if (won != null && favorites.contains(won.abbr) && !"racing".equals(g.sport)) {
                        goalAlert(g, won, t("VICTORY!", "VICTOIRE!"), 0xFFFFC400);   // gold lights
                    } else {
                        all(v -> v.banner("FINAL · " + g.away.abbr + " " + g.away.score + " – " + g.home.score + " " + g.home.abbr, 0xFFFFB524));
                    }
                }
            }

            if (g.away.odds >= 0 && !g.isFinal()) lastOdds.put(g.id, new float[]{g.away.odds, g.home.odds});

            // pre-game reminder for your teams
            long until = g.startMs - System.currentTimeMillis();
            if (mine && "pre".equals(g.state) && until > 0 && until <= 15 * 60_000 && reminded.add(g.id)) {
                final Model.Game rg = g;
                all(v -> v.banner(rg.away.abbr + " @ " + rg.home.abbr + t(" in ", " dans ") + (until / 60000 + 1) + " min", 0xFF4DA3FF));
            }

            // upset: the team with under 35 % chance won
            if (prevState != null && !prevState.equals(g.state) && g.isFinal() && !"racing".equals(g.sport)) {
                float[] od = lastOdds.get(g.id);
                Model.Side won = g.away.winner ? g.away : g.home.winner ? g.home : null;
                if (od != null && won != null && (won == g.away ? od[0] : od[1]) < 0.35f) {
                    final Model.Game ug = g; final Model.Side uw = won; final float[] uo = od;
                    all(v -> v.banner(t("UPSET! ", "SURPRISE! ") + uw.abbr + " (" + Math.round((uw == ug.away ? uo[0] : uo[1]) * 100) + "%) "
                        + ug.away.score + " – " + ug.home.score, 0xFFB57BFF));
                }
            }

            // close game late
            if (g.isLive() && isClutch(g) && closeAlerted.add(g.id)) {
                final Model.Game cg = g;
                all(v -> v.banner(t("CLOSE GAME · ", "MATCH SERRÉ · ") + cg.away.abbr + " " + cg.away.score + " – " + cg.home.score + " " + cg.home.abbr, 0xFFFF9500));
            }

            if (prev == null || !g.isLive() && !g.isFinal()) continue;
            boolean awayUp = a > prev[0] && prev[0] >= 0, homeUp = h > prev[1] && prev[1] >= 0;
            if (awayUp || homeUp) all(v -> v.flash(g.id));
            if (!watched || celebrateGame != null) continue;
            if (awayUp) { celebrateGame = g; celebrateSide = g.away; celebrateDelta = a - prev[0]; }
            else if (homeUp) { celebrateGame = g; celebrateSide = g.home; celebrateDelta = h - prev[1]; }
        }
        autoNextGame(games);
        tensionLights(games);
        if (celebrateGame == null) return;
        String word = scoreWord(celebrateGame.sport, celebrateDelta);
        if (word == null) return;

        // With favorite teams, only THEIR goals get the horn/party/lights; anything else is a quiet banner.
        if (!favorites.isEmpty() && !favorites.contains(celebrateSide.abbr)) {
            Model.Game g = celebrateGame;
            final Model.Side scorer = celebrateSide;
            all(v -> v.banner(word.replace("!", "") + " " + scorer.abbr + " · " + g.away.abbr + " " + g.away.score
                + " – " + g.home.score + " " + g.home.abbr, 0xFF8B919C));
            return;
        }
        goalAlert(celebrateGame, celebrateSide, word);
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
        String word = scoreWord(g.sport, 1);
        goalAlert(g, side, word == null ? "SCORE!" : word);
    }

    private void goalAlert(Model.Game g, Model.Side side, String word) { goalAlert(g, side, word, side.color); }

    private void goalAlert(Model.Game g, Model.Side side, String word, int ledColor) {
        if (celebrations) all(v -> v.celebrate(g, side, word));
        else all(v -> v.banner(word + " " + side.abbr + " · " + g.away.abbr + " " + g.away.score + " – " + g.home.score + " " + g.home.abbr, side.color | 0xFF000000));
        Horn.play(this, sound && !quietNow(), HORN_VOLS[hornVol]);
        if (ledsAvailable && ledsOn) {
            if (ledColor == side.color && side.ledA != 0) Leds.flash2(side.ledA, second(side.ledA, side.ledB));
            else Leds.flash(ledColor);
        }
    }

    /** What to shout. Basketball scores too often, so no celebration there. */
    private static String scoreWord(String sport, int delta) {
        switch (sport) {
            case "hockey":
            case "soccer": return ThorView.tr("GOAL!", "BUT!");
            case "football":
                if (delta >= 6) return "TOUCHDOWN!";
                if (delta == 3) return ThorView.tr("FIELD GOAL!", "PLACEMENT!");
                return ThorView.tr("SCORE!", "POINTS!");
            case "baseball": return delta == 1 ? ThorView.tr("RUN!", "POINT!") : delta + ThorView.tr(" RUNS!", " POINTS!");
            case "esports": return ThorView.tr("GAME WON!", "PARTIE GAGNÉE!");
            default: return null;
        }
    }
}
