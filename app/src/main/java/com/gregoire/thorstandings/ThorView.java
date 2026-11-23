package com.gregoire.thorstandings;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Everything is drawn on a Canvas and sized from the real view width/height,
 * so it fills the Thor's 1240x1080 bottom screen exactly (and adapts to anything else).
 */
public class ThorView extends View {

    public interface Listener {
        void onLeagueLongPress();
        void onLeaguePick(int index);
        void onTabTap(int tab);
        void onBodyTap();
        void onTeamLongPress(String abbr);
        void onTeamTap(String abbr);
        void onFollowGame(String gameId, boolean openBoard);
        void onSoundTap();
        void onClockLongPress();
        void onSetting(int id, int dir);
        void onDualToggle();
        void onDelayAction(int type, int value);   // 0 = add, 1 = set, 2 = start TV sync
        void onSyncSeen();
        void onSyncCancel();
        void onDateShift(int days);
        void onFilterToggle();
        void onOnboardLeague(int leagueIndex);              // load that league's teams for "Pick your teams"
        void onOnboardToggle(String leaguePath, String abbr);
        void onOnboardDone();
    }

    public static final int TAB_SCORES = 0, TAB_BOARD = 1, TAB_STANDINGS = 2;
    public static final int SET_DELAY = 0, SET_HORN = 1, SET_LEDS = 2, SET_DIM = 3, SET_AUTOBOARD = 4,
        SET_HORN_VOL = 5, SET_LED_BRI = 6, SET_CELEB = 7, SET_KEEP_ON = 8, SET_NIGHT = 9, SET_HIDE_FINAL = 10,
        SET_TEST = 11, SET_HELP = 12, SET_RESET = 13, SET_DUAL = 14, SET_QUIET = 15, SET_VIBRATE = 16,
        SET_CELEB_LEN = 17, SET_ACCENT = 18, SET_CLOCK = 19, SET_LANG = 20, SET_SAVER = 21, SET_STAND_SIZE = 22, SET_DEMO_MLB = 23, SET_DEMO_NFL = 24, SET_DEMO_NHL = 25, SET_SHOT_MAP = 26, SET_UPDATE = 27, SET_MY_TEAMS = 29;
    private static final int GAMES_PER_PAGE = 8;
    private static final long AUTO_PAGE_MS = 10_000;
    private long CELEBRATION_MS = 6500;
    public void setCelebrationMs(long ms) { CELEBRATION_MS = ms; }
    private static final long BANNER_MS = 4500;
    private static final long FLASH_MS = 4000;
    private static final long TAB_FADE_MS = 180;

    // Palette
    private static final int TEXT = 0xFFF4F5F7;
    private static final int MUTED = 0xFF8B919C;
    private static final int DIM = 0xFF5E636D;
    private static final int CARD = 0xFF101218;
    private static int ACCENT = 0xFF4DA3FF;
    public static void setAccent(int c) { ACCENT = c; }

    /** Français / English */
    public static boolean fr;
    public static String tr(String en, String french) { return fr ? french : en; }
    private static final int LIVE = 0xFFFF3B30;
    private static final int AMBER = 0xFFFFB524;
    private static final int GREEN = 0xFF34C759;
    private static final int HAIRLINE = 0x1FFFFFFF;

    private final Typeface tfBlack = Typeface.create("sans-serif-black", Typeface.NORMAL);
    private final Typeface tfCond = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private final Typeface tfMed = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Typeface tfReg = Typeface.create("sans-serif", Typeface.NORMAL);

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF r1 = new RectF();
    private final Rect src = new Rect();
    private final GestureDetector gestures;
    private LinearGradient bgShader;

    // Hit areas
    private final RectF leagueRect = new RectF();
    private final RectF clockRect = new RectF();
    private final RectF[] tabRects = {new RectF(), new RectF(), new RectF()};
    private final RectF soundRect = new RectF();
    private final RectF prevRect = new RectF(), nextRect = new RectF(), filterRect = new RectF();
    private final List<RectF> hitRects = new ArrayList<>();
    private final List<String[]> hitAbbrs = new ArrayList<>();
    private final List<RectF> cardRects = new ArrayList<>();
    private final List<String> cardIds = new ArrayList<>();
    private final List<RectF> pickerRects = new ArrayList<>();
    private final List<RectF> settingRects = new ArrayList<>();
    private final List<int[]> settingActs = new ArrayList<>(); // {id, dir}
    private final RectF settingsPanel = new RectF();
    private float headerBottom;

    // State
    private LogoCache logos;
    private Listener listener;
    private String league = "";
    private String[] leagueLabels = new String[0];
    private int leagueIndex;
    private Espn.League leagueInfo;
    private int tab = TAB_SCORES;
    private List<Model.Game> games = Collections.emptyList();
    private List<Model.Group> groups = Collections.emptyList();
    private String[] cols = new String[0];
    private Set<String> favorites = Collections.emptySet();
    private String message = "Loading…";
    private boolean offline, loading, myTeamsOnly, pickerOpen, bigScore, settingsOpen;
    private int delaySec, dimPct = 100;
    private boolean ledsOn, ledsAvailable, autoBoard = true;
    private boolean helpOpen, hideFinal, nightMode, delayOpen, shotMap = true;
    private final List<RectF> delayRects = new ArrayList<>();
    private final List<int[]> delayActs = new ArrayList<>();
    private int syncState;            // 0 off, 1 waiting for a score, 2 score happened: tap when you see it
    private long syncSince;
    private final RectF syncCancel = new RectF();
    private List<String[]> settingItems = new ArrayList<>();  // {label, value, id, "1" = stepper}
    private final RectF dateLabelRect = new RectF();
    private final RectF dualRect = new RectF();
    private boolean topMode, dual, saverOn;
    private int clockMode;                 // 0 = phone setting, 1 = 12 h, 2 = 24 h
    private long lastTouch = System.currentTimeMillis();
    private int goalsPage;
    private final RectF scorersRect = new RectF();

    // Standings: one group at a time, big rows
    private int standGroup, standPage;
    private boolean bigStandings = true;
    private final List<RectF> groupChipRects = new ArrayList<>();
    private final RectF standPrevRect = new RectF(), standNextRect = new RectF();

    // Controller cursor
    private boolean cursorOn;
    private float fx = -1, fy = -1;                       // centre of the highlighted item
    private long lastStick;
    private final List<RectF> focusables = new ArrayList<>();
    private final Map<String, Long> popAt = new HashMap<>();   // "gameId:away"/"gameId:home" -> when score changed
    private int page, dateOffset;
    private String leagueLogo;
    private String boardGameId;
    private boolean sound = true;
    private long updatedAt, tabSwitchAt;
    private int battery = -1;
    private boolean charging;
    private float dim;
    private final Map<String, Long> flashUntil = new HashMap<>();

    // Banner + goal celebration
    private String bannerText;
    private int bannerColor;
    private long bannerAt;
    private Model.Game celebGame;
    private Model.Side celebSide;
    private String celebWord, celebScorer = "";
    private long celebStart;

    private final Runnable autoPage = new Runnable() {
        @Override public void run() {
            int pages = pageCount();
            if (tab == TAB_SCORES && pages > 1) { page = (page + 1) % pages; invalidate(); }
            postDelayed(this, AUTO_PAGE_MS);
        }
    };

    public ThorView(Context ctx) {
        super(ctx);
        setBackgroundColor(0xFF000000);
        gestures = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            /** Every tap is instant (no waiting for a possible double-tap). */
            @Override public boolean onSingleTapUp(MotionEvent e) {
                if (listener == null) return false;
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                return handleTap(e.getX(), e.getY());
            }

            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (tab == TAB_STANDINGS) {
                    if (Math.abs(vy) > Math.abs(vx)) standPage = Math.max(0, standPage + (vy < 0 ? 1 : -1));
                    else if (groups.size() > 1) { standGroup = ((standGroup + (vx < 0 ? 1 : -1)) % groups.size() + groups.size()) % groups.size(); standPage = 0; }
                    invalidate();
                    return true;
                }
                if (Math.abs(vx) < Math.abs(vy)) return false;
                if (tab == TAB_BOARD) {
                    edgeAction(vx < 0 ? 1 : -1);
                    return true;
                }
                if (tab != TAB_SCORES) return false;
                int pages = pageCount();
                if (pages < 2) return false;
                page = (page + (vx < 0 ? 1 : pages - 1)) % pages;
                removeCallbacks(autoPage);
                postDelayed(autoPage, AUTO_PAGE_MS * 2);
                invalidate();
                return true;
            }

            @Override public void onLongPress(MotionEvent e) {
                if (listener == null) return;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                handleLongPress(e.getX(), e.getY());
            }
        });
    }


    /** A tap at (x, y): from the touchscreen, or the controller's A button on the highlighted item. */
    private boolean handleTap(float x, float y) {
        if (listener == null) return false;
        if (onboardOpen) {
            for (int i = 0; i < onboardLeagueRects.size(); i++)
                if (onboardLeagueRects.get(i).contains(x, y)) { openOnboardLeague(onboardLeagues.get(i)); return true; }
            for (int i = 0; i < onboardRects.size(); i++)
                if (onboardRects.get(i).contains(x, y)) { listener.onOnboardToggle(onboardPath, onboardTeams.get(i).abbr); return true; }
            if (onboardDoneRect.contains(x, y)) { onboardOpen = false; listener.onOnboardDone(); invalidate(); }
            return true;
        }
        if (syncState > 0) {
            if (syncCancel.contains(x, y)) listener.onSyncCancel();
            else if (syncState == 2) listener.onSyncSeen();
            return true;
        }
        if (delayOpen) {
            for (int i = 0; i < delayRects.size(); i++) {
                if (delayRects.get(i).contains(x, y)) {
                    int[] a = delayActs.get(i);
                    if (a[0] == 3) delayOpen = false; else listener.onDelayAction(a[0], a[1]);
                    invalidate();
                    return true;
                }
            }
            delayOpen = false; invalidate(); return true;
        }
        if (demoGame != null && y > headerBottom) { demoGame = null; invalidate(); return true; }
        if (tab == TAB_BOARD && bigScore && y > headerBottom && !overlayOpen()) { bigScore = false; invalidate(); return true; }
                if (celebrating()) { celebGame = null; invalidate(); return true; }
        if (helpOpen) { helpOpen = false; invalidate(); return true; }
        if (settingsOpen) {
    for (int i = 0; i < sectionRects.size(); i++) {
        if (sectionRects.get(i).contains(x, y)) { settingsSection = i; selSetting = 0; invalidate(); return true; }
    }
    for (int i = 0; i < settingRects.size(); i++) {
        if (settingRects.get(i).contains(x, y)) {
            int[] act = settingActs.get(i);
            if (act[0] < 0) settingsOpen = false; else listener.onSetting(act[0], act[1]);
            invalidate();
            return true;
        }
    }
    if (!settingsPanel.contains(x, y)) { settingsOpen = false; invalidate(); }
    return true;
        }
        if (pickerOpen) {
    for (int i = 0; i < pickerRects.size(); i++) {
        if (pickerRects.get(i).contains(x, y)) { pickerOpen = false; listener.onLeaguePick(i); return true; }
    }
    pickerOpen = false; invalidate(); return true;
        }
        if (dualRect.contains(x, y)) { listener.onDualToggle(); return true; }
        if (leagueRect.contains(x, y)) { pickerOpen = true; invalidate(); return true; }
        if (clockRect.contains(x, y)) { settingsOpen = true; invalidate(); return true; }
        if (!topMode) for (int i = 0; i < tabRects.length; i++) {
    if (tabRects[i].contains(x, y)) { listener.onTabTap(i); return true; }
        }
        if (tab == TAB_BOARD && soundRect.contains(x, y)) { listener.onSoundTap(); return true; }
        if (tab == TAB_BOARD && scorersRect.contains(x, y)) { goalsPage++; invalidate(); return true; }
        if (tab == TAB_SCORES) {
    if (prevRect.contains(x, y)) { listener.onDateShift(-1); return true; }
    if (nextRect.contains(x, y)) { listener.onDateShift(1); return true; }
    if (filterRect.contains(x, y)) { listener.onFilterToggle(); return true; }
    if (dateLabelRect.contains(x, y) && dateOffset != 0) { listener.onDateShift(-dateOffset); return true; }
    for (int i = 0; i < cardRects.size(); i++) {
        if (cardRects.get(i).contains(x, y)) { listener.onFollowGame(cardIds.get(i), true); return true; }
    }
        }
        if (tab == TAB_STANDINGS) {
    for (int i = 0; i < hitRects.size(); i++) {
        if (hitRects.get(i).contains(x, y)) { listener.onTeamTap(hitAbbrs.get(i)[0]); return true; }
    }
        }
        if (y > headerBottom) listener.onBodyTap();
        return true;
    }

    private boolean handleDoubleTap(float y) {
        if (tab != TAB_BOARD || y <= headerBottom) return false;
        bigScore = !bigScore;
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        invalidate();
        return true;
    }

    private void handleLongPress(float x, float y) {
        if (listener == null) return;
                if (leagueRect.contains(x, y)) { listener.onLeagueLongPress(); return; }
        if (clockRect.contains(x, y)) { listener.onClockLongPress(); return; }
        for (int i = 0; i < hitRects.size(); i++) {
    RectF r = hitRects.get(i);
    if (r.contains(x, y)) {
        String[] ab = hitAbbrs.get(i);
        listener.onTeamLongPress(x < r.centerX() ? ab[0] : ab[1]);
        return;
    }
        }
    }


    // ------------------------------------------------------------------ controller
    // Console-style: each screen has a simple selection (card, row, menu item) moved with the D-pad.

    private void collectFocusables() { }   // kept for the draw loop; selection is index-based now

    private RectF selectedRect() {
        if (delayOpen) return sel(delayRects, selDelay);
        if (onboardOpen) return sel(onboardRects, selOnboard);
        if (settingsOpen) return sel(settingCells, selSetting);
        if (pickerOpen) return sel(pickerRects, selPicker);
        if (tab == TAB_SCORES) return sel(cardRects, selCard);
        if (tab == TAB_STANDINGS) return sel(hitRects, selRow);
        return null;   // Board: no highlight, ← → change game
    }

    private static RectF sel(List<RectF> list, int i) { return i >= 0 && i < list.size() ? list.get(i) : null; }

    private void drawCursor(Canvas c) {
        RectF r = selectedRect();
        if (r == null) return;
        float pad = Math.max(3f, Math.min(r.width(), r.height()) * 0.05f);
        RectF ring = new RectF(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad);
        float rad = Math.min(ring.width(), ring.height()) * 0.22f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(pad * 1.3f);
        p.setColor(ACCENT);
        c.drawRoundRect(ring, rad, rad, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(alpha(ACCENT, 0x22));
        c.drawRoundRect(ring, rad, rad, p);
    }

    private int selCard, selRow, selPicker, selSetting, selDelay;
    private final List<RectF> settingCells = new ArrayList<>();

    private void wakeCursor() {
        lastTouch = System.currentTimeMillis();
        cursorOn = true;
    }

    /** D-pad / stick. */
    public boolean controllerMove(int dx, int dy) {
        boolean first = !cursorOn;
        wakeCursor();
        if (first) { invalidate(); return true; }   // first press just shows the selection
        if (onboardOpen) {
            int cols = onboardCols();
            if (selOnboard < 0) selOnboard = 0;
            else if (dx != 0) selOnboard = clamp(selOnboard + dx, onboardTeams.size());
            else selOnboard = clamp(selOnboard + dy * cols, onboardTeams.size());
            invalidate();
            return true;
        }
        if (delayOpen) {
            selDelay = clamp(selDelay + dx + dy * 4, delayRects.size());
            invalidate();
            return true;
        }
        if (settingsOpen) {
            if (dx != 0) { settingsSection = clamp(settingsSection + dx, SECTIONS.length); selSetting = 0; }   // ← → sections
            else selSetting = clamp(selSetting + dy, visibleSettings().size());
        } else if (pickerOpen) {
            int n = leagueLabels.length, rows = n > 8 ? (n + 1) / 2 : n;
            selPicker = clamp(selPicker + (dx != 0 ? dx * rows : dy), n);
        } else if (tab == TAB_SCORES) {
            int m = cardRects.size();
            int cols = m <= 2 ? 1 : 2;
            int next = selCard + dx + dy * cols;
            if (next >= m && (dx > 0 || dy > 0)) { nextPage(1); selCard = 0; }
            else if (next < 0 && (dx < 0 || dy < 0)) { nextPage(-1); selCard = 99; }
            else selCard = Math.max(0, Math.min(m - 1, next));
            if (listener != null && !shownGames().isEmpty()) { }
        } else if (tab == TAB_STANDINGS) {
            if (dx != 0 && groups.size() > 1) {
                standGroup = ((standGroup + dx) % groups.size() + groups.size()) % groups.size();
                standPage = 0; selRow = 0;
            } else if (dy != 0) {
                int rows = hitRects.size();
                int next = selRow + dy;
                if (next >= rows) { standPage++; selRow = 0; }
                else if (next < 0) { if (standPage > 0) { standPage--; selRow = 99; } }
                else selRow = next;
            }
        } else if (tab == TAB_BOARD) {
            if (dx != 0) edgeAction(dx);
            else if (dy != 0) { goalsPage = Math.max(0, goalsPage + dy); }
        }
        invalidate();
        return true;
    }

    private static int clamp(int i, int n) { return Math.max(0, Math.min(n - 1, i)); }
    private static String[] sel2(List<String[]> l, int i) { return i >= 0 && i < l.size() ? l.get(i) : null; }

    /** Games in the same order as the Scores cards (your teams first, finished hidden if set). */
    private List<Model.Game> boardOrder() {
        List<Model.Game> l = shownGames();
        return l.isEmpty() ? games : l;
    }

    private long switchAt;

    /** Next / previous game on the Board, in the Scores order, with a small position label. */
    private void edgeAction(int dx) {
        List<Model.Game> order = boardOrder();
        if (dx == 0 || listener == null || order.size() < 2) return;
        int i = order.indexOf(boardGame());
        if (i < 0) i = 0;
        int next = ((i + dx) % order.size() + order.size()) % order.size();
        listener.onFollowGame(order.get(next).id, false);
        switchAt = System.currentTimeMillis();
    }

    private void drawSwitchLabel(Canvas c, int w, float top) {
        long t = System.currentTimeMillis() - switchAt;
        if (t > 1600) return;
        List<Model.Game> order = boardOrder();
        Model.Game g = boardGame();
        int i = order.indexOf(g);
        if (g == null || i < 0) return;
        String who = "racing".equals(g.sport) ? (g.away.abbr.equals("LAST") ? tr("Last race", "Dernière course") : g.away.name) : g.away.abbr + " @ " + g.home.abbr;
        String s = "‹   " + who + "  ·  " + (i + 1) + "/" + order.size() + "   ›";
        float ts = w * 0.022f, th = ts * 2f;
        float a = t < 1300 ? 1f : 1f - (t - 1300) / 300f;
        p.setTypeface(tfCond);
        p.setTextSize(ts);
        float tw = p.measureText(s) + th;
        RectF r = new RectF(w / 2f - tw / 2, top + th * 0.2f, w / 2f + tw / 2, top + th * 1.2f);
        p.setColor(alpha(0xFF181B22, (int) (240 * a)));
        c.drawRoundRect(r, th / 2, th / 2, p);
        text(c, s, r.centerX(), r.centerY(), ts, tfCond, alpha(TEXT, (int) (255 * a)), Paint.Align.CENTER, tw);
        postInvalidateOnAnimation();
    }

    /** Left stick: same as the D-pad, with a little repeat delay. */
    public void stick(float x, float y) { }

    /** A: open / select. */
    public void controllerSelect() {
        if (onboardOpen) {
            if (selOnboard >= 0 && selOnboard < onboardTeams.size()) listener.onOnboardToggle(onboardPath, onboardTeams.get(selOnboard).abbr);
            return;
        }
        if (syncState == 2) { listener.onSyncSeen(); return; }
        if (syncState == 1) return;
        if (delayOpen) {
            int[] a = selDelay < delayActs.size() ? delayActs.get(selDelay) : null;
            if (a != null) { if (a[0] == 3) delayOpen = false; else listener.onDelayAction(a[0], a[1]); }
            invalidate();
            return;
        }
        if (celebrating() || helpOpen) { celebGame = null; helpOpen = false; invalidate(); return; }
        if (demoGame != null) { demoGame = null; invalidate(); return; }
        wakeCursor();
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (settingsOpen) {
            String[] it = sel2(visibleSettings(), selSetting);
            if (it != null) listener.onSetting(Integer.parseInt(it[2]), 1);
        } else if (pickerOpen) {
            pickerOpen = false;
            listener.onLeaguePick(selPicker);
        } else if (tab == TAB_SCORES) {
            if (selCard < cardIds.size()) listener.onFollowGame(cardIds.get(selCard), true);
        } else if (tab == TAB_STANDINGS) {
            if (selRow < hitAbbrs.size()) listener.onTeamTap(hitAbbrs.get(selRow)[0]);
        } else if (tab == TAB_BOARD) {
            bigScore = !bigScore;
        }
        invalidate();
    }

    /** X / Y: ★ the left / right team (card or Board), X = the row's team in Standings. */
    public void controllerFavorite(boolean right) {
        if (listener == null) return;
        String abbr = null;
        if (tab == TAB_SCORES && selCard < cardIds.size()) {
            for (Model.Game g : games) if (g.id.equals(cardIds.get(selCard))) abbr = right ? g.home.abbr : g.away.abbr;
        } else if (tab == TAB_BOARD) {
            Model.Game g = boardGame();
            if (g != null) abbr = right ? g.home.abbr : g.away.abbr;
        } else if (tab == TAB_STANDINGS && selRow < hitAbbrs.size()) {
            abbr = hitAbbrs.get(selRow)[0];
        }
        if (abbr != null && !abbr.isEmpty()) listener.onTeamLongPress(abbr);
    }

    /** B: close menu → leave big score → back to Scores → hide the selection. Never leaves the app. */
    public boolean controllerBack() {
        if (onboardOpen) { onboardOpen = false; listener.onOnboardDone(); invalidate(); return true; }
        if (syncState > 0) { listener.onSyncCancel(); return true; }
        if (demoGame != null) { demoGame = null; invalidate(); return true; }
        if (closeOverlays()) return true;
        if (bigScore) { bigScore = false; invalidate(); return true; }
        if (tab != TAB_SCORES && !topMode && listener != null) { listener.onTabTap(TAB_SCORES); return true; }
        if (cursorOn) { cursorOn = false; invalidate(); return true; }
        return false;
    }

    public void openLeaguePicker() {
        settingsOpen = false;
        pickerOpen = !pickerOpen;
        selPicker = leagueIndex;
        wakeCursor();
        invalidate();
    }

    public void openSettings() {
        pickerOpen = false;
        settingsOpen = !settingsOpen;
        selSetting = 0;
        wakeCursor();
        invalidate();
    }

    public void toggleBigScore() { if (tab == TAB_BOARD) { bigScore = !bigScore; invalidate(); } }

    public void hideCursor() { if (cursorOn) { cursorOn = false; invalidate(); } }

    // ------------------------------------------------------------------ public API
    public void setLogoCache(LogoCache c) { logos = c; }
    public void setListener(Listener l) { listener = l; }
    public void setFavorites(Set<String> f) { favorites = f; frame++; invalidate(); }
    public void setLeagueLabels(String[] labels) { leagueLabels = labels; }

    private String[] leagueLogos = new String[0];

    /** Sample game shown on the Board (Settings → baseball field demo). Any tap or B closes it. */
    private Model.Game demoGame;
    public void setDemoGame(Model.Game g) { demoGame = g; bigScore = false; invalidate(); }
    public void setLeagueLogos(String[] urls) { leagueLogos = urls; invalidate(); }

    private static String leagueName(String label) {
        return fr && label.startsWith("★") ? "★ MES ÉQUIPES" : label;
    }

    public void setLeague(int index, Espn.League lg) {
        if (!lg.label.equals(league)) {
            page = 0; games = Collections.emptyList(); groups = Collections.emptyList();
            standGroup = 0; standPage = 0;
            leagueLogo = null; celebGame = null; flashUntil.clear();
        }
        leagueIndex = index;
        leagueInfo = lg;
        league = lg.label;
        invalidate();
    }

    public void setTab(int t) {
        if (topMode) return;
        if (t != tab) tabSwitchAt = System.currentTimeMillis();
        tab = t; page = 0; invalidate();
    }

    public void setGames(List<Model.Game> g) {
        long now = System.currentTimeMillis();
        for (Model.Game ng : g) {
            for (Model.Game og : games) {
                if (!og.id.equals(ng.id)) continue;
                if (!og.away.score.equals(ng.away.score) && !ng.away.score.isEmpty()) popAt.put(ng.id + ":away", now);
                if (!og.home.score.equals(ng.home.score) && !ng.home.score.isEmpty()) popAt.put(ng.id + ":home", now);
            }
        }
        games = g;
        if (page >= pageCount()) page = 0;
        invalidate();
    }
    public void setStandings(String[] c, List<Model.Group> g) {
        boolean fresh = groups.isEmpty() || g.size() != groups.size();
        cols = c; groups = g;
        if (fresh) {   // open on the group that has one of your teams
            standGroup = 0; standPage = 0;
            for (int i = 0; i < g.size(); i++)
                for (Model.Team t : g.get(i).teams) if (favorites.contains(t.abbr)) { standGroup = i; break; }
        }
        invalidate();
    }

    public void setBigStandings(boolean big) { bigStandings = big; standPage = 0; invalidate(); }

    private int standRowsPerPage() { return bigStandings ? 10 : 99; }
    public void setMessage(String m) { message = m; invalidate(); }
    public void setOffline(boolean o) { offline = o; invalidate(); }
    public void setLoading(boolean l) { loading = l; invalidate(); }
    public void setUpdatedAt(long t) { updatedAt = t; invalidate(); }
    public void setLeagueLogo(String url) { leagueLogo = url; invalidate(); }
    public void setBoardGameId(String id) {
        if (id != null && !id.equals(boardGameId) && boardGameId != null) switchAt = System.currentTimeMillis();
        boardGameId = id;
        invalidate();
    }
    public void setSound(boolean on) { sound = on; invalidate(); }
    public void setBattery(int pct, boolean chg) { battery = pct; charging = chg; invalidate(); }
    public void setDim(float d) { dim = d; invalidate(); }
    public void setDateOffset(int d) { dateOffset = d; page = 0; invalidate(); }
    public void setMyTeamsOnly(boolean on) { myTeamsOnly = on; page = 0; invalidate(); }
    public void showLeaguePicker() { pickerOpen = true; invalidate(); }

    public void setSettings(int delay, boolean leds, boolean ledsAvail, int dimPercent, boolean autoB) {
        delaySec = delay; ledsOn = leds; ledsAvailable = ledsAvail; dimPct = dimPercent; autoBoard = autoB;
        invalidate();
    }

    public void setSettingItems(List<String[]> items) { settingItems = items; invalidate(); }
    public void setDisplayPrefs(boolean hideFinished, boolean night, boolean shots) { hideFinal = hideFinished; nightMode = night; shotMap = shots; page = 0; invalidate(); }
    /** This view is the TOP screen of two-screen mode: always the Board, no tabs. */
    public void setClockMode(int m) { clockMode = m; invalidate(); }
    public void setSaver(boolean on) { saverOn = on; invalidate(); }
    public void toggleSettings() { openSettings(); }
    public boolean closeOverlays() {
        boolean any = settingsOpen || pickerOpen || helpOpen || delayOpen || celebrating();
        settingsOpen = pickerOpen = helpOpen = delayOpen = false; celebGame = null; invalidate();
        return any;
    }
    public void nextPage(int dir) {
        int pages = pageCount();
        page = ((page + dir) % pages + pages) % pages;
        invalidate();
    }
    private boolean overlayOpen() { return onboardOpen || settingsOpen || pickerOpen || helpOpen || delayOpen || syncState > 0 || celebrating(); }

    private boolean saverActive() {
        return saverOn && !topMode && System.currentTimeMillis() - lastTouch > 10 * 60_000 && !anyLive() && !celebrating();
    }

    public void setTopMode(boolean on) { topMode = on; tab = TAB_BOARD; invalidate(); }
    public void setDual(boolean on) { dual = on; invalidate(); }

    public void openDelay() { delayOpen = true; settingsOpen = pickerOpen = false; invalidate(); }
    public void setSync(int state, long since) { syncState = state; syncSince = since; if (state > 0) delayOpen = false; invalidate(); }

    public void showHelp() { helpOpen = true; settingsOpen = false; invalidate(); }
    public void closeSettings() { settingsOpen = false; invalidate(); }

    public void flash(String gameId) {
        flashUntil.put(gameId, System.currentTimeMillis() + FLASH_MS);
        invalidate();
    }

    public void banner(String text, int color) {
        bannerText = text; bannerColor = color; bannerAt = System.currentTimeMillis();
        invalidate();
    }

    /** The game shown on the BOARD: the one you picked, else your highlighted team's, else the first (live first). */
    public Model.Game boardGame() {
        if (games.isEmpty()) return null;
        if (boardGameId != null) for (Model.Game g : games) if (g.id.equals(boardGameId)) return g;
        for (Model.Game g : games) if (g.involves(favorites)) return g;
        return games.get(0);
    }

    public void celebrate(Model.Game g, Model.Side side, String word) {
        celebGame = g; celebSide = side; celebWord = word;
        celebScorer = "";
        boolean home = side == g.home;
        for (Model.Goal goal : g.goals) if (goal.home == home && !goal.red) celebScorer = goal.who;
        celebStart = System.currentTimeMillis();
        invalidate();
    }

    private boolean celebrating() {
        return celebGame != null && System.currentTimeMillis() - celebStart < CELEBRATION_MS;
    }

    public int liveCount() {
        int n = 0;
        for (Model.Game g : games) if (g.isLive()) n++;
        return n;
    }

    public boolean anyLive() { return liveCount() > 0; }

    private List<Model.Game> shownCache;
    private long shownCacheFrame = -1;
    private long frame;

    /** Games shown on the SCORES tab (computed once per frame). */
    private List<Model.Game> shownGames() {
        if (shownCacheFrame != frame || shownCache == null) { shownCache = computeShown(); shownCacheFrame = frame; }
        return shownCache;
    }

    /** Your teams first, optionally only your teams, optionally without finished games. */
    private List<Model.Game> computeShown() {
        List<Model.Game> mine = new ArrayList<>(), rest = new ArrayList<>();
        for (Model.Game g : games) {
            if (hideFinal && g.isFinal()) continue;
            (g.involves(favorites) ? mine : rest).add(g);
        }
        if (myTeamsOnly) return mine;
        mine.addAll(rest);
        return mine;
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); postDelayed(autoPage, AUTO_PAGE_MS); }
    @Override protected void onDetachedFromWindow() { removeCallbacks(autoPage); super.onDetachedFromWindow(); }
    @Override public boolean onTouchEvent(MotionEvent e) {
        boolean wasSaver = saverActive();
        lastTouch = System.currentTimeMillis();
        if (cursorOn) { cursorOn = false; invalidate(); }
        if (wasSaver) { invalidate(); return true; }  // first touch only wakes the screen
        return gestures.onTouchEvent(e);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        bgShader = new LinearGradient(0, 0, 0, h, 0xFF0B0D12, 0xFF000000, Shader.TileMode.CLAMP);
    }

    private int pageCount() { return Math.max(1, (shownGames().size() + GAMES_PER_PAGE - 1) / GAMES_PER_PAGE); }

    // ------------------------------------------------------------------ helpers
    private float baseline(float centerY) { return centerY - (p.descent() + p.ascent()) / 2f; }

    private String fit(String s, float maxW) {
        if (s == null) return "";
        if (maxW <= 0) return "";
        if (p.measureText(s) <= maxW) return s;
        int end = s.length();
        float dots = p.measureText("…");
        while (end > 0 && p.measureText(s, 0, end) + dots > maxW) end--;
        return end <= 0 ? "" : s.substring(0, end) + "…";
    }

    private void text(Canvas c, String s, float x, float cy, float size, Typeface tf, int color, Paint.Align a, float maxW) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(size);
        p.setTypeface(tf);
        p.setColor(color);
        p.setTextAlign(a);
        c.drawText(fit(s, maxW), x, baseline(cy), p);
    }

    private static int alpha(int color, int a) { return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24); }

    private String fmtTime(long ms) {
        if (clockMode == 1) return new java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(new Date(ms));
        if (clockMode == 2) return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new Date(ms));
        return DateFormat.getTimeFormat(getContext()).format(new Date(ms));
    }

    private String star(String abbr) { return favorites.contains(abbr) ? abbr + "★" : abbr; }

    /** Draws a logo centered at (cx, cy); falls back to a colored disc with initials. */
    private void logo(Canvas c, String url, String abbr, int color, float cx, float cy, float size) {
        Bitmap b = logos == null ? null : logos.get(url);
        if (b != null) {
            src.set(0, 0, b.getWidth(), b.getHeight());
            float s = size / Math.max(b.getWidth(), b.getHeight());
            float bw = b.getWidth() * s, bh = b.getHeight() * s;
            r1.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2);
            c.drawBitmap(b, src, r1, bmpPaint);
        } else {
            if (color == 0) return;
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(alpha(color, 0xCC));
            c.drawCircle(cx, cy, size * 0.42f, p);
            text(c, abbr, cx, cy, size * 0.28f, tfCond, TEXT, Paint.Align.CENTER, size * 0.8f);
        }
    }

    /** 0..1 how far a live game is, for the progress bar. */
    private static float progress(Model.Game g) {
        int reg, len;
        switch (g.sport) {
            case "hockey": reg = 3; len = 20; break;
            case "football": reg = 4; len = 15; break;
            case "basketball": reg = 4; len = 12; break;
            case "baseball": return Math.min(1f, (g.period - 0.5f) / 9f);
            case "soccer":
                try { return Math.min(1f, Integer.parseInt(g.clock.replaceAll("[^0-9].*", "")) / 90f); }
                catch (Exception e) { return 0.5f; }
            default: return 0.5f;
        }
        float elapsed = len / 2f;
        try {
            String[] mmss = g.clock.split(":");
            float remaining = Integer.parseInt(mmss[0]) + (mmss.length > 1 ? Integer.parseInt(mmss[1]) / 60f : 0);
            elapsed = Math.max(0, len - remaining);
        } catch (Exception ignored) { }
        return Math.min(1f, ((Math.min(g.period, reg) - 1) * len + elapsed) / (reg * len));
    }

    // ------------------------------------------------------------------ drawing
    @Override
    protected void onDraw(Canvas c) {
        final int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        frame++;
        hitRects.clear();
        hitAbbrs.clear();
        cardRects.clear();
        cardIds.clear();

        p.setShader(bgShader);
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        // OLED burn-in protection: shift everything by a couple of pixels every 3 minutes.
        long slot = System.currentTimeMillis() / 180_000;
        float dx = (slot % 3) - 1, dy = ((slot / 3) % 3) - 1;
        c.save();
        c.translate(dx * 2, dy * 2);

        if (saverActive()) {
            drawSaver(c, w, h);
            c.restore();
            postInvalidateDelayed(20_000);
            return;
        }
        if (saverOn) postInvalidateDelayed(60_000);

        float headerH = h * 0.1f;
        headerBottom = headerH;
        drawHeader(c, w, headerH);

        float top = headerH + h * 0.012f;
        if (tab == TAB_SCORES) {
            float barH = h * 0.06f;
            drawDateBar(c, w, top, barH);
            float bodyTop = top + barH + h * 0.008f;
            if (shownGames().isEmpty() && "Loading…".equals(message)) drawSkeleton(c, w, h, bodyTop);
            else if (shownGames().isEmpty()) {
                String m = message != null ? message
                    : leagueInfo != null && leagueInfo.mine() && favorites.isEmpty() ? tr("Long-press any team in any league to add it here", "Appuyez longuement sur une équipe pour l'ajouter ici")
                    : myTeamsOnly || leagueInfo != null && leagueInfo.mine() ? tr("No games for your teams", "Aucun match pour vos équipes") : tr("No games", "Aucun match");
                drawEmpty(c, w, bodyTop, h, m);
            } else drawScores(c, w, h, bodyTop);
        } else if (tab == TAB_BOARD) {
            Model.Game g = demoGame != null ? demoGame : boardGame();
            if (g == null) drawEmpty(c, w, top, h, message != null ? message : "No games today");
            else {
                int bh = topMode ? (int) (h * 0.93f) : h;   // top screen: ticker of other games underneath
                if ("racing".equals(g.sport)) drawRaceBoard(c, g, w, bh, top);
                else if ("baseball".equals(g.sport) && g.isLive() && !bigScore) drawBaseballBoard(c, g, w, bh, top);
                else if ("football".equals(g.sport) && g.isLive() && !bigScore) drawFootballBoard(c, g, w, bh, top);
                else if ("hockey".equals(g.sport) && (shotMap || g == demoGame) && !g.shots.isEmpty() && !"pre".equals(g.state) && !bigScore) drawHockeyBoard(c, g, w, bh, top);
                else if (bigScore) drawBigScore(c, g, w, bh, top);
                else drawBoard(c, g, w, bh, top);
                if (topMode) drawTicker(c, g, w, h, bh);
                drawSwitchLabel(c, w, top);
            }
        } else {
            if (groups.isEmpty()) drawEmpty(c, w, top, h, message != null ? message : "No standings");
            else drawStandings(c, w, h, top);
        }

        // quick fade when switching tabs
        long ft = System.currentTimeMillis() - tabSwitchAt;
        if (ft < TAB_FADE_MS) {
            p.setColor(alpha(0xFF000000, (int) (220 * (1 - ft / (float) TAB_FADE_MS))));
            c.drawRect(0, headerH, w, h, p);
            postInvalidateOnAnimation();
        }

        drawBanner(c, w, h, top);
        if (pickerOpen) drawPicker(c, w, h);
        if (settingsOpen) drawSettings(c, w, h);
        if (onboardOpen) drawOnboarding(c, w, h);
        if (helpOpen) drawHelp(c, w, h);
        if (delayOpen) drawDelayPanel(c, w, h);
        if (syncState > 0) drawSync(c, w, h);
        if (celebrating()) {
            drawCelebration(c, w, h);
            postInvalidateOnAnimation();
        }
        if (selCard >= cardRects.size()) selCard = Math.max(0, cardRects.size() - 1);
        if (selRow >= hitRects.size() && tab == TAB_STANDINGS) selRow = Math.max(0, hitRects.size() - 1);
        if (cursorOn && !celebrating() && !helpOpen && !topMode) drawCursor(c);
        c.restore();

        float d = dim;
        if (nightMode) {
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            if (hour >= 23 || hour < 7) d = Math.max(d, 0.55f);
        }
        if (d > 0) {
            p.setColor(alpha(0xFF000000, (int) (255 * d)));
            c.drawRect(0, 0, w, h, p);
        }
        if (!flashUntil.isEmpty()) postInvalidateDelayed(120);
    }

    private void drawHeader(Canvas c, int w, float hh) {
        float pad = w * 0.02f;
        float cy = hh / 2f;

        // League chip
        p.setTypeface(tfBlack);
        String leagueShown = leagueName(league);
        float ls = hh * (leagueShown.length() > 7 ? 0.32f : 0.42f);
        p.setTextSize(ls);
        float lw = Math.min(p.measureText(leagueShown), w * 0.16f);
        float chipH = hh * 0.66f;
        String chipLogo = leagueLogo != null ? leagueLogo : leagueIndex >= 0 && leagueIndex < leagueLogos.length ? leagueLogos[leagueIndex] : null;
        if (chipLogo != null && chipLogo.startsWith("mine")) chipLogo = null;
        float logoW = chipLogo != null ? chipH * 0.78f : 0;
        leagueRect.set(pad, cy - chipH / 2, pad + logoW + lw + chipH * 1.25f, cy + chipH / 2);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF161920);
        c.drawRoundRect(leagueRect, chipH / 2, chipH / 2, p);
        if (chipLogo != null) logo(c, chipLogo, "", 0x00000000, leagueRect.left + chipH * 0.3f + logoW / 2, cy, chipH * 0.66f);
        text(c, leagueShown, leagueRect.left + chipH * 0.4f + logoW, cy, ls, tfBlack, TEXT, Paint.Align.LEFT, w * 0.16f);
        float chx = leagueRect.right - chipH * 0.42f, s = chipH * 0.12f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(hh * 0.035f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(MUTED);
        c.drawLine(chx - s, cy - s / 2, chx, cy + s / 2, p);
        c.drawLine(chx, cy + s / 2, chx + s, cy - s / 2, p);
        p.setStyle(Paint.Style.FILL);

        // Clock + date + battery
        float clockW = w * 0.14f;
        clockRect.set(w - pad - clockW, 0, w, hh);
        String clock = fmtTime(System.currentTimeMillis());
        text(c, clock, w - pad, cy - hh * 0.12f, hh * 0.34f, tfMed, TEXT, Paint.Align.RIGHT, clockW);
        String sub = DateFormat.format("EEE d", System.currentTimeMillis()).toString().toUpperCase();
        if (battery >= 0) sub += "  " + battery + "%" + (charging ? "+" : "");
        if (delaySec > 0) sub = tr("DELAY ", "DÉLAI ") + delaySec + "s  ·  " + sub;
        int subCol = battery >= 0 && battery <= 15 && !charging ? LIVE : MUTED;
        if (offline) { sub = "OFFLINE"; subCol = LIVE; }
        text(c, sub, w - pad, cy + hh * 0.22f, hh * 0.19f, tfCond, subCol, Paint.Align.RIGHT, clockW);
        if (loading) {
            float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 120.0));
            p.setColor(alpha(AMBER, (int) (90 + 165 * pulse)));
            c.drawCircle(w - pad - clockW - hh * 0.05f, cy, hh * 0.05f, p);
            postInvalidateDelayed(60);
        }

        // Two-screen button: an icon of the Thor's two screens
        float segH = hh * 0.66f;
        float dw = segH * 1.05f;
        dualRect.set(w - pad - clockW - hh * 0.15f - dw, cy - segH / 2, w - pad - clockW - hh * 0.15f, cy + segH / 2);
        p.setColor(dual ? ACCENT : 0xFF161920);
        c.drawRoundRect(dualRect, segH * 0.3f, segH * 0.3f, p);
        float ix = dualRect.centerX(), iw = dw * 0.5f, ih = segH * 0.26f;
        p.setStyle(dual ? Paint.Style.FILL : Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, segH * 0.05f));
        p.setColor(dual ? 0xFF000000 : MUTED);
        c.drawRoundRect(new RectF(ix - iw / 2, cy - ih - segH * 0.04f, ix + iw / 2, cy - segH * 0.04f), 3, 3, p);
        c.drawRoundRect(new RectF(ix - iw * 0.4f, cy + segH * 0.04f, ix + iw * 0.4f, cy + segH * 0.04f + ih * 0.9f), 3, 3, p);
        p.setStyle(Paint.Style.FILL);
        if (topMode) {
            text(c, "TWO-SCREEN MODE · tap ▭ to go back to one screen", dualRect.left - hh * 0.3f, cy, hh * 0.22f, tfCond, DIM,
                Paint.Align.RIGHT, dualRect.left - leagueRect.right - hh * 0.6f);
            p.setColor(HAIRLINE);
            c.drawRect(0, hh - 1, w, hh, p);
            return;
        }

        // Segmented tabs
        float segW = w * 0.42f;
        float segRight = dualRect.left - hh * 0.15f;
        r1.set(segRight - segW, cy - segH / 2, segRight, cy + segH / 2);
        p.setColor(0xFF161920);
        c.drawRoundRect(r1, segH / 2, segH / 2, p);
        String[] labels = {tr("SCORES", "SCORES"), tr("BOARD", "TABLEAU"), tr("STANDINGS", "CLASSEMENT")};
        int nTabs = leagueInfo != null && leagueInfo.mine() ? 2 : 3;   // MY TEAMS: no standings tab
        float inset = segH * 0.09f;
        float segEach = segW / nTabs;
        float segLeft = r1.left, segTop = r1.top, segBottom = r1.bottom;
        int live = liveCount();
        for (int i = nTabs; i < 3; i++) tabRects[i].set(0, 0, 0, 0);
        for (int i = 0; i < nTabs; i++) {
            float l = segLeft + i * segEach;
            tabRects[i].set(l, segTop, l + segEach, segBottom);
            boolean active = tab == i;
            if (active) {
                RectF a = new RectF(l + inset, segTop + inset, l + segEach - inset, segBottom - inset);
                p.setColor(TEXT);
                c.drawRoundRect(a, a.height() / 2, a.height() / 2, p);
            }
            float tcx = l + segEach / 2;
            text(c, labels[i], tcx, cy, segH * 0.34f, tfCond, active ? 0xFF000000 : MUTED, Paint.Align.CENTER, segEach * 0.9f);
            if (i == 0 && live > 0) {
                p.setTextSize(segH * 0.34f);
                p.setTypeface(tfCond);
                float tw = p.measureText(labels[0]);
                float bx = tcx + tw / 2 + segH * 0.22f, br = segH * 0.17f;
                p.setColor(LIVE);
                c.drawCircle(bx, cy - segH * 0.18f, br, p);
                text(c, String.valueOf(live), bx, cy - segH * 0.18f, br * 1.3f, tfCond, TEXT, Paint.Align.CENTER, br * 2);
            }
        }

        p.setColor(HAIRLINE);
        c.drawRect(0, hh - 1, w, hh, p);
    }

    private void drawEmpty(Canvas c, int w, float top, int h, String msg) {
        float cy = (top + h) / 2f + h * 0.1f;
        text(c, fmtTime(System.currentTimeMillis()), w / 2f, cy - h * 0.2f, h * 0.15f, tfBlack, 0xFF2A2E38, Paint.Align.CENTER, w * 0.9f);
        text(c, msg, w / 2f, cy, h * 0.045f, tfMed, MUTED, Paint.Align.CENTER, w * 0.9f);
        text(c, tr("Tap to refresh", "Touchez pour actualiser"), w / 2f, cy + h * 0.06f, h * 0.03f, tfReg, DIM, Paint.Align.CENTER, w * 0.9f);
    }

    /** Shimmering placeholder cards while the first data loads. */
    private void drawSkeleton(Canvas c, int w, int h, float top) {
        float gap = w * 0.016f;
        float cw = (w - gap * 3) / 2f, ch = (h - top - gap * 4) / 4f;
        float shimmer = (System.currentTimeMillis() % 1400) / 1400f;
        for (int i = 0; i < 8; i++) {
            float x = gap + (i % 2) * (cw + gap), y = top + (i / 2) * (ch + gap);
            RectF r = new RectF(x, y, x + cw, y + ch);
            p.setColor(0xFF0E1015);
            c.drawRoundRect(r, ch * 0.1f, ch * 0.1f, p);
            float sx = x - cw + shimmer * cw * 3;
            p.setShader(new LinearGradient(sx, 0, sx + cw * 0.6f, 0, new int[]{0x00FFFFFF, 0x0DFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, ch * 0.1f, ch * 0.1f, p);
            p.setShader(null);
            p.setColor(0xFF15181F);
            c.drawCircle(x + cw * 0.15f, y + ch * 0.42f, ch * 0.2f, p);
            c.drawCircle(x + cw * 0.85f, y + ch * 0.42f, ch * 0.2f, p);
            c.drawRoundRect(new RectF(x + cw * 0.4f, y + ch * 0.33f, x + cw * 0.6f, y + ch * 0.5f), 8, 8, p);
        }
        postInvalidateDelayed(50);
    }

    // ------------------------------------------------------------------ screensaver / ticker
    private void drawSaver(Canvas c, int w, int h) {
        p.setColor(0xFF000000);
        c.drawRect(-10, -10, w + 10, h + 10, p);
        long now = System.currentTimeMillis();
        float drift = (float) Math.sin(now / 60_000.0) * h * 0.08f;   // keeps moving: no burn-in
        text(c, fmtTime(now), w / 2f, h * 0.42f + drift, h * 0.22f, tfBlack, 0xFF6B6F78, Paint.Align.CENTER, w * 0.9f);
        text(c, DateFormat.format("EEEE d MMMM", now).toString().toUpperCase(), w / 2f, h * 0.6f + drift, h * 0.04f, tfCond, 0xFF3E424A, Paint.Align.CENTER, w);
        text(c, tr("Tap to wake", "Touchez pour réveiller"), w / 2f, h * 0.9f, h * 0.028f, tfReg, 0xFF2E3138, Paint.Align.CENTER, w);
    }

    private void drawTicker(Canvas c, Model.Game cur, int w, int h, int top) {
        StringBuilder sb = new StringBuilder();
        for (Model.Game g : games) {
            if (g == cur || "racing".equals(g.sport)) continue;
            if (sb.length() > 0) sb.append("      •      ");
            sb.append(g.away.abbr).append(' ');
            if ("pre".equals(g.state)) sb.append("@ ").append(g.home.abbr).append("  ").append(g.startMs > 0 ? fmtTime(g.startMs) : g.detail);
            else sb.append(g.away.score).append(" – ").append(g.home.score).append(' ').append(g.home.abbr).append("  ").append(g.isLive() ? g.detail : tr("FINAL", "FINAL"));
        }
        if (sb.length() == 0) return;
        float th = h - top;
        p.setColor(0xFF0C0E13);
        c.drawRect(0, top, w, h, p);
        p.setTypeface(tfCond);
        p.setTextSize(th * 0.45f);
        String s = sb.toString() + "      •      ";
        float tw = p.measureText(s);
        float off = (System.currentTimeMillis() / 12f) % tw;
        c.save();
        c.clipRect(0, top, w, h);
        for (float x = -off; x < w; x += tw) text(c, s, x, top + th / 2, th * 0.45f, tfCond, MUTED, Paint.Align.LEFT, tw + 10);
        c.restore();
        postInvalidateDelayed(40);
    }

    // ------------------------------------------------------------------ banner
    private void drawBanner(Canvas c, int w, int h, float top) {
        if (bannerText == null) return;
        long t = System.currentTimeMillis() - bannerAt;
        if (t > BANNER_MS) { bannerText = null; return; }
        float in = Math.min(1f, t / 250f), out = Math.min(1f, (BANNER_MS - t) / 300f);
        float k = Math.min(in, out);
        float bh = h * 0.085f, gap = w * 0.016f;
        float y = top + gap - (1 - k) * (bh + gap * 2);
        RectF r = new RectF(gap * 3, y, w - gap * 3, y + bh);
        p.setShadowLayer(bh * 0.25f, 0, bh * 0.08f, 0xAA000000);
        p.setColor(0xFF181B22);
        c.drawRoundRect(r, bh / 2, bh / 2, p);
        p.clearShadowLayer();
        p.setColor(bannerColor);
        c.drawRoundRect(new RectF(r.left, r.top, r.left + bh * 0.22f, r.bottom), bh / 2, bh / 2, p);
        text(c, bannerText, r.centerX(), r.centerY(), bh * 0.4f, tfCond, TEXT, Paint.Align.CENTER, r.width() * 0.9f);
        postInvalidateOnAnimation();
    }

    // ------------------------------------------------------------------ league picker
    /** Dropdown list under the league chip: tap once, pick once. */
    private void drawPicker(Canvas c, int w, int h) {
        pickerRects.clear();
        p.setColor(0x99000000);
        c.drawRect(0, headerBottom, w, h, p);
        int n = leagueLabels.length, colsN = n > 8 ? 2 : 1;
        int rows = (n + colsN - 1) / colsN;
        float rowH = Math.min(h * 0.095f, (h - headerBottom - h * 0.05f) / rows);
        float colW = w * 0.24f;
        float pad = w * 0.008f;
        RectF panel = new RectF(leagueRect.left, headerBottom + pad, leagueRect.left + colW * colsN + pad * 2,
            headerBottom + pad * 3 + rowH * rows);
        p.setShadowLayer(rowH * 0.4f, 0, rowH * 0.1f, 0xCC000000);
        p.setColor(0xFF15171D);
        c.drawRoundRect(panel, rowH * 0.3f, rowH * 0.3f, p);
        p.clearShadowLayer();
        for (int i = 0; i < n; i++) {
            float x = panel.left + pad + (i / rows) * colW, y = panel.top + pad + (i % rows) * rowH;
            RectF r = new RectF(x, y, x + colW, y + rowH);
            pickerRects.add(r);
            boolean cur = i == leagueIndex;
            if (cur) {
                p.setColor(alpha(ACCENT, 0x40));
                c.drawRoundRect(new RectF(r.left + pad, r.top + pad / 2, r.right - pad, r.bottom - pad / 2), rowH * 0.25f, rowH * 0.25f, p);
            }
            float ls = rowH * 0.62f, lcx = r.left + rowH * 0.25f + ls / 2;
            String url = i < leagueLogos.length ? leagueLogos[i] : null;
            if (leagueLabels[i].startsWith("★")) {
                p.setColor(alpha(AMBER, 0x33));
                c.drawCircle(lcx, r.centerY(), ls * 0.48f, p);
                text(c, "★", lcx, r.centerY(), ls * 0.62f, tfBlack, AMBER, Paint.Align.CENTER, ls);
            } else if (url != null && logos != null && logos.get(url) != null) {
                logo(c, url, "", 0, lcx, r.centerY(), ls);
            } else {   // badge until the logo is known (or for leagues without one)
                RectF b = new RectF(lcx - ls / 2, r.centerY() - ls / 2, lcx + ls / 2, r.centerY() + ls / 2);
                p.setColor(0xFF22252D);
                c.drawRoundRect(b, ls * 0.25f, ls * 0.25f, p);
                String ab = leagueLabels[i].replace(" ", "");
                text(c, ab.length() > 4 ? ab.substring(0, 3) : ab, lcx, r.centerY(), ls * 0.32f, tfCond, TEXT, Paint.Align.CENTER, ls * 0.9f);
            }
            text(c, leagueName(leagueLabels[i]), r.left + rowH * 0.45f + ls, r.centerY(), rowH * 0.4f, tfBlack, cur ? ACCENT : TEXT, Paint.Align.LEFT, colW - rowH * 0.7f - ls);
        }
    }

    // ------------------------------------------------------------------ "Pick your teams" (first start, or Settings → App)
    private boolean onboardOpen, onboardFirst, onboardLoading;
    private int onboardLeague = -1, selOnboard = -1;
    private String onboardPath = "";
    private List<Model.Team> onboardTeams = new ArrayList<>();
    private Set<String> favKeysView = new java.util.HashSet<>();
    private final List<Integer> onboardLeagues = new ArrayList<>();
    private final List<RectF> onboardRects = new ArrayList<>(), onboardLeagueRects = new ArrayList<>();
    private final RectF onboardDoneRect = new RectF();

    /** Opens the team picker. first = welcome wording (first start). */
    public void openOnboarding(boolean first, List<Integer> leagues) {
        onboardFirst = first;
        onboardLeagues.clear();
        onboardLeagues.addAll(leagues);
        settingsOpen = false;
        pickerOpen = false;
        onboardOpen = true;
        if (!leagues.isEmpty()) openOnboardLeague(onboardLeague >= 0 && leagues.contains(onboardLeague) ? onboardLeague : leagues.get(0));
        invalidate();
    }

    public boolean onboardingOpen() { return onboardOpen; }

    /** L1 / R1 in the picker: previous / next league. */
    public void onboardShift(int dir) {
        int i = onboardLeagues.indexOf(onboardLeague);
        if (i < 0) return;
        int n = onboardLeagues.size();
        openOnboardLeague(onboardLeagues.get(((i + dir) % n + n) % n));
    }

    private void openOnboardLeague(int idx) {
        onboardLeague = idx;
        onboardPath = Espn.LEAGUES[idx].path;
        onboardTeams = new ArrayList<>();
        onboardLoading = true;
        selOnboard = cursorOn ? 0 : -1;
        if (listener != null) listener.onOnboardLeague(idx);
        invalidate();
    }

    public void setOnboardTeams(int idx, List<Model.Team> teams) {
        if (idx != onboardLeague) return;
        onboardTeams = teams;
        onboardLoading = false;
        invalidate();
    }

    public void setFavKeys(Set<String> keys) { favKeysView = keys; invalidate(); }

    private int onboardCols() { return onboardTeams.size() > 25 ? 6 : 5; }

    private void drawOnboarding(Canvas c, int w, int h) {
        onboardRects.clear();
        onboardLeagueRects.clear();
        p.setShader(new LinearGradient(0, 0, 0, h, 0xFF14171E, 0xFF0B0C10, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        float gap = w * 0.02f, headH = h * 0.15f;
        text(c, onboardFirst ? tr("Welcome to ThorSport", "Bienvenue dans ThorSport") : tr("My teams", "Mes équipes"),
            gap * 1.5f, headH * 0.4f, headH * 0.3f, tfBlack, TEXT, Paint.Align.LEFT, w * 0.7f);
        text(c, tr("Pick your teams: their games come first, with the goal horn and stick lights in their colors.",
                "Choisis tes équipes : leurs matchs en premier, avec klaxon et lumières à leurs couleurs."),
            gap * 1.5f, headH * 0.78f, headH * 0.13f, tfMed, MUTED, Paint.Align.LEFT, w - gap * 3);

        // leagues (left)
        float sideW = w * 0.21f, top = headH + gap * 0.2f, bottom = h - h * 0.12f;
        float lh = Math.min(h * 0.075f, (bottom - top) / Math.max(1, onboardLeagues.size()));
        for (int i = 0; i < onboardLeagues.size(); i++) {
            int li = onboardLeagues.get(i);
            RectF r = new RectF(gap, top + i * lh + lh * 0.07f, gap + sideW, top + (i + 1) * lh - lh * 0.07f);
            boolean on = li == onboardLeague;
            int count = 0;
            for (String k : favKeysView) if (k.startsWith(Espn.LEAGUES[li].path + "|")) count++;
            p.setColor(on ? ACCENT : 0xFF171A21);
            c.drawRoundRect(r, r.height() * 0.3f, r.height() * 0.3f, p);
            String url = li < leagueLogos.length ? leagueLogos[li] : null;
            if (url != null && logos != null && logos.get(url) != null) logo(c, url, "", 0, r.left + r.height() * 0.5f, r.centerY(), r.height() * 0.6f);
            String name = leagueName(li < leagueLabels.length ? leagueLabels[li] : Espn.LEAGUES[li].label);
            text(c, name, r.left + r.height() * 0.95f, r.centerY(), r.height() * 0.36f, tfBlack, on ? 0xFF000000 : TEXT, Paint.Align.LEFT, r.width() - r.height() * 1.7f);
            if (count > 0) {
                float bs = r.height() * 0.56f;
                RectF b = new RectF(r.right - bs - r.height() * 0.2f, r.centerY() - bs / 2, r.right - r.height() * 0.2f, r.centerY() + bs / 2);
                p.setColor(on ? 0xFF000000 : ACCENT);
                c.drawRoundRect(b, bs / 2, bs / 2, p);
                text(c, String.valueOf(count), b.centerX(), b.centerY(), bs * 0.56f, tfBlack, on ? TEXT : 0xFF000000, Paint.Align.CENTER, bs);
            }
            onboardLeagueRects.add(r);
        }

        // teams (right)
        float gx = gap * 2 + sideW, gw = w - gap - gx;
        if (onboardTeams.isEmpty())
            text(c, onboardLoading ? tr("Loading teams…", "Chargement des équipes…") : tr("No teams found (offline?)", "Aucune équipe (hors ligne ?)"),
                gx + gw / 2, (top + bottom) / 2, h * 0.035f, tfMed, MUTED, Paint.Align.CENTER, gw);
        int cols = onboardCols(), rows = Math.max(1, (onboardTeams.size() + cols - 1) / cols);
        float tw = gw / cols, th = Math.min((bottom - top) / rows, tw * 0.85f);
        for (int i = 0; i < onboardTeams.size(); i++) {
            Model.Team t = onboardTeams.get(i);
            RectF r = new RectF(gx + (i % cols) * tw + tw * 0.04f, top + (i / cols) * th + th * 0.05f,
                gx + (i % cols + 1) * tw - tw * 0.04f, top + (i / cols + 1) * th - th * 0.05f);
            boolean fav = favKeysView.contains(onboardPath + "|" + t.abbr);
            float rr = r.height() * 0.18f;
            p.setColor(fav ? alpha(ACCENT, 0x3A) : 0xFF171A21);
            c.drawRoundRect(r, rr, rr, p);
            if (fav) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(2f, r.height() * 0.035f));
                p.setColor(ACCENT);
                c.drawRoundRect(r, rr, rr, p);
                p.setStyle(Paint.Style.FILL);
                text(c, "★", r.right - r.height() * 0.17f, r.top + r.height() * 0.18f, r.height() * 0.17f, tfBlack, ACCENT, Paint.Align.CENTER, r.height() * 0.3f);
            }
            float ls = Math.min(r.height() * 0.48f, r.width() * 0.5f);
            logo(c, t.logo, t.abbr, 0xFF3A3F4A, r.centerX(), r.top + r.height() * 0.4f, ls);
            text(c, t.abbr, r.centerX(), r.top + r.height() * 0.82f, r.height() * 0.17f, tfBlack, fav ? TEXT : 0xFFC9CDD3, Paint.Align.CENTER, r.width() * 0.92f);
            onboardRects.add(r);
        }

        // bottom bar
        float bh = h * 0.08f, by = h - gap * 0.6f - bh;
        int total = favKeysView.size();
        text(c, total == 0 ? tr("Tap a team to add it. Later: long-press any team, or Settings → App → My teams.", "Touche une équipe pour l'ajouter. Plus tard : appui long, ou Réglages → Appli → Mes équipes.")
                : total + (total == 1 ? tr(" team selected", " équipe choisie") : tr(" teams selected", " équipes choisies")),
            gap * 1.5f, by + bh / 2, bh * 0.3f, tfMed, total == 0 ? MUTED : TEXT, Paint.Align.LEFT, w - bh * 3.6f - gap * 3);
        onboardDoneRect.set(w - gap - bh * 3.2f, by, w - gap, by + bh);
        p.setColor(total == 0 ? 0xFF2C303A : ACCENT);
        c.drawRoundRect(onboardDoneRect, bh / 2, bh / 2, p);
        text(c, total == 0 ? tr("SKIP", "PASSER") : tr("DONE", "TERMINÉ"), onboardDoneRect.centerX(), onboardDoneRect.centerY(), bh * 0.36f, tfBlack,
            total == 0 ? TEXT : 0xFF000000, Paint.Align.CENTER, onboardDoneRect.width());
    }

    // ------------------------------------------------------------------ settings panel
    private static final String[][] SECTIONS = {
        {"General", "Général"}, {"Goals & alerts", "Buts & alertes"}, {"Stick lights", "Lumières"},
        {"Scores & Board", "Scores & tableau"}, {"App", "Appli"}};
    private int settingsSection;
    private String appVersion = "";
    private final List<RectF> sectionRects = new ArrayList<>();

    public void setAppVersion(String v) { appVersion = v == null ? "" : v; }

    /** Items of the section on screen. */
    private List<String[]> visibleSettings() {
        List<String[]> out = new ArrayList<>();
        for (String[] it : settingItems) if (it.length > 4 && Integer.parseInt(it[4]) == settingsSection) out.add(it);
        return out;
    }

    /** Full-screen settings: sections on the left, big rows on the right. */
    private void drawSettings(Canvas c, int w, int h) {
        settingRects.clear();
        settingActs.clear();
        settingCells.clear();
        sectionRects.clear();
        settingsPanel.set(0, 0, w, h);
        p.setShader(new LinearGradient(0, 0, 0, h, 0xFF14171E, 0xFF0B0C10, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        float gap = w * 0.02f, headH = h * 0.12f;

        // header
        text(c, tr("Settings", "Réglages"), gap * 1.5f, headH * 0.55f, headH * 0.4f, tfBlack, TEXT, Paint.Align.LEFT, w * 0.4f);
        p.setTypeface(tfBlack);
        p.setTextSize(headH * 0.4f);
        float titleW = p.measureText(tr("Settings", "Réglages"));
        text(c, "ThorSport " + appVersion, gap * 2.4f + titleW, headH * 0.58f, headH * 0.2f, tfMed, DIM, Paint.Align.LEFT, w * 0.3f);
        RectF done = new RectF(w - gap * 1.5f - headH * 1.9f, headH * 0.22f, w - gap * 1.5f, headH * 0.88f);
        p.setColor(ACCENT);
        c.drawRoundRect(done, done.height() / 2, done.height() / 2, p);
        text(c, tr("DONE", "OK"), done.centerX(), done.centerY(), headH * 0.24f, tfBlack, 0xFF000000, Paint.Align.CENTER, done.width());
        settingRects.add(done); settingActs.add(new int[]{-1, 0});

        // sections (left)
        float sideW = w * 0.3f, top = headH + gap * 0.4f, bottom = h - gap;
        float sh = Math.min(h * 0.12f, (bottom - top) / SECTIONS.length);
        for (int i = 0; i < SECTIONS.length; i++) {
            RectF r = new RectF(gap, top + i * sh + sh * 0.08f, gap + sideW, top + (i + 1) * sh - sh * 0.08f);
            boolean on = i == settingsSection;
            p.setColor(on ? ACCENT : 0xFF171A21);
            c.drawRoundRect(r, r.height() * 0.28f, r.height() * 0.28f, p);
            drawSectionIcon(c, i, r.left + r.height() * 0.55f, r.centerY(), r.height() * 0.42f, on ? 0xFF000000 : MUTED);
            text(c, tr(SECTIONS[i][0], SECTIONS[i][1]), r.left + r.height() * 1.0f, r.centerY(), r.height() * 0.27f, tfBlack,
                on ? 0xFF000000 : TEXT, Paint.Align.LEFT, r.width() - r.height() * 1.2f);
            sectionRects.add(r);
        }

        // rows (right)
        List<String[]> vis = visibleSettings();
        float x0 = gap * 2 + sideW, x1 = w - gap;
        float rowH = Math.min(h * 0.105f, (bottom - top) / Math.max(1, vis.size()));
        RectF card = new RectF(x0, top + sh * 0.08f, x1, top + sh * 0.08f + rowH * vis.size());
        p.setColor(0xFF171A21);
        c.drawRoundRect(card, rowH * 0.3f, rowH * 0.3f, p);
        for (int i = 0; i < vis.size(); i++) {
            String[] it = vis.get(i);
            RectF cell = new RectF(x0, card.top + i * rowH, x1, card.top + (i + 1) * rowH);
            settingCells.add(cell);
            settingCell(c, cell, rowH, it[0], it[1], Integer.parseInt(it[2]), i < vis.size() - 1);
        }
    }

    /** Simple drawn icons for the sections (no emoji). */
    private void drawSectionIcon(Canvas c, int i, float cx, float cy, float s, int col) {
        p.setColor(col);
        p.setStrokeWidth(Math.max(2f, s * 0.12f));
        p.setStrokeCap(Paint.Cap.ROUND);
        switch (i) {
            case 0: {   // sliders
                p.setStyle(Paint.Style.STROKE);
                for (int k = -1; k <= 1; k++) c.drawLine(cx - s * 0.45f, cy + k * s * 0.32f, cx + s * 0.45f, cy + k * s * 0.32f, p);
                p.setStyle(Paint.Style.FILL);
                float[] kx = {0.2f, -0.2f, 0.1f};
                for (int k = -1; k <= 1; k++) c.drawCircle(cx + kx[k + 1] * s, cy + k * s * 0.32f, s * 0.13f, p);
                break;
            }
            case 1: {   // horn / bell
                Path b = new Path();
                b.moveTo(cx - s * 0.45f, cy - s * 0.15f);
                b.lineTo(cx - s * 0.1f, cy - s * 0.15f);
                b.lineTo(cx + s * 0.4f, cy - s * 0.45f);
                b.lineTo(cx + s * 0.4f, cy + s * 0.45f);
                b.lineTo(cx - s * 0.1f, cy + s * 0.15f);
                b.lineTo(cx - s * 0.45f, cy + s * 0.15f);
                b.close();
                p.setStyle(Paint.Style.FILL);
                c.drawPath(b, p);
                break;
            }
            case 2: {   // stick ring
                p.setStyle(Paint.Style.STROKE);
                c.drawCircle(cx, cy, s * 0.42f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, s * 0.2f, p);
                break;
            }
            case 3: {   // scoreboard
                p.setStyle(Paint.Style.STROKE);
                c.drawRoundRect(new RectF(cx - s * 0.48f, cy - s * 0.32f, cx + s * 0.48f, cy + s * 0.32f), s * 0.1f, s * 0.1f, p);
                c.drawLine(cx, cy - s * 0.32f, cx, cy + s * 0.32f, p);
                p.setStyle(Paint.Style.FILL);
                break;
            }
            default: {  // info "i"
                p.setStyle(Paint.Style.STROKE);
                c.drawCircle(cx, cy, s * 0.44f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy - s * 0.2f, s * 0.07f, p);
                c.drawRect(cx - s * 0.05f, cy - s * 0.05f, cx + s * 0.05f, cy + s * 0.25f, p);
            }
        }
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStyle(Paint.Style.FILL);
    }

    private void settingCell(Canvas c, RectF cell, float rowH, String label, String value, int id, boolean divider) {
        float pad = rowH * 0.35f;
        text(c, label, cell.left + pad, cell.centerY(), rowH * 0.3f, tfMed, TEXT, Paint.Align.LEFT, cell.width() * 0.56f);
        float bh = rowH * 0.56f, right = cell.right - pad;
        boolean on = "ON".equals(value), off = !on && ("OFF".equals(value) || "N/A".equals(value));
        RectF hit;
        if (on || off) {   // switch
            float sw = bh * 1.75f;
            RectF track = new RectF(right - sw, cell.centerY() - bh / 2, right, cell.centerY() + bh / 2);
            p.setColor(on ? GREEN : 0xFF2C303A);
            c.drawRoundRect(track, bh / 2, bh / 2, p);
            p.setColor("N/A".equals(value) ? DIM : 0xFFFFFFFF);
            float kr = bh * 0.4f;
            c.drawCircle(on ? track.right - bh / 2 : track.left + bh / 2, track.centerY(), kr, p);
            if ("N/A".equals(value)) text(c, "N/A", track.left - pad * 0.5f, cell.centerY(), rowH * 0.24f, tfCond, DIM, Paint.Align.RIGHT, bh * 3);
            hit = new RectF(cell);
        } else {           // value pill
            p.setTypeface(tfCond);
            p.setTextSize(rowH * 0.26f);
            float pw = Math.min(cell.width() * 0.4f, Math.max(bh * 2.2f, p.measureText(value) + bh));
            RectF b = new RectF(right - pw, cell.centerY() - bh / 2, right, cell.centerY() + bh / 2);
            boolean action = value.equals("▶") || value.equals("?") || value.endsWith("›") || value.startsWith("INSTALL") || value.startsWith("INSTALLER");
            int bg = value.startsWith("TAP") || value.startsWith("TOUCHEZ") ? LIVE : action ? ACCENT : 0xFF2C303A;
            p.setColor(bg);
            c.drawRoundRect(b, bh / 2, bh / 2, p);
            text(c, value, b.centerX(), b.centerY(), rowH * 0.26f, tfCond, action ? 0xFF000000 : TEXT, Paint.Align.CENTER, b.width() * 0.9f);
            hit = new RectF(cell);
        }
        settingRects.add(hit); settingActs.add(new int[]{id, 1});
        if (divider) {
            p.setColor(HAIRLINE);
            c.drawRect(cell.left + pad, cell.bottom - 1, cell.right - pad, cell.bottom, p);
        }
    }

    // ------------------------------------------------------------------ spoiler delay
    private void delayButton(Canvas c, RectF r, String label, int type, int value, boolean primary, boolean on) {
        p.setColor(primary ? ACCENT : on ? TEXT : 0xFF22252D);
        c.drawRoundRect(r, r.height() * 0.3f, r.height() * 0.3f, p);
        text(c, label, r.centerX(), r.centerY(), r.height() * 0.36f, tfBlack, primary || on ? 0xFF000000 : TEXT, Paint.Align.CENTER, r.width() * 0.9f);
        delayRects.add(r);
        delayActs.add(new int[]{type, value});
    }

    /** Big, simple delay control: the number, ± buttons, presets and "Sync with TV". */
    private void drawDelayPanel(Canvas c, int w, int h) {
        delayRects.clear();
        delayActs.clear();
        p.setColor(0xE6000000);
        c.drawRect(0, 0, w, h, p);
        float gap = w * 0.02f;
        RectF panel = new RectF(w * 0.08f, h * 0.06f, w * 0.92f, h * 0.94f);
        p.setColor(0xFF15171D);
        c.drawRoundRect(panel, gap * 1.5f, gap * 1.5f, p);
        text(c, tr("SPOILER DELAY", "DÉLAI ANTI-SPOILER"), panel.centerX(), panel.top + h * 0.06f, h * 0.035f, tfCond, MUTED, Paint.Align.CENTER, panel.width());
        String big = delaySec == 0 ? tr("OFF", "AUCUN") : delaySec >= 60 ? (delaySec / 60) + ":" + String.format(Locale.US, "%02d", delaySec % 60) : delaySec + " s";
        text(c, big, panel.centerX(), panel.top + h * 0.17f, h * 0.12f, tfBlack, delaySec == 0 ? MUTED : AMBER, Paint.Align.CENTER, panel.width());
        text(c, tr("Scores, goals and alerts wait this long, to match your stream", "Les scores et alertes attendent ce délai pour suivre ton stream"),
            panel.centerX(), panel.top + h * 0.26f, h * 0.026f, tfReg, DIM, Paint.Align.CENTER, panel.width() * 0.9f);
        // − / + row
        int[] steps = {-10, -1, 1, 10};
        String[] sl = {"−10", "−1", "+1", "+10"};
        float bw = (panel.width() - gap * 5) / 4f, bh = h * 0.1f, y = panel.top + h * 0.31f;
        for (int i = 0; i < 4; i++) delayButton(c, new RectF(panel.left + gap + i * (bw + gap), y, panel.left + gap + i * (bw + gap) + bw, y + bh), sl[i], 0, steps[i], false, false);
        // presets
        int[] presets = {0, 15, 30, 45, 60, 90, 120, 180};
        float pw = (panel.width() - gap * 5) / 4f, ph = h * 0.085f;
        y += bh + gap * 1.2f;
        for (int i = 0; i < presets.length; i++) {
            float px = panel.left + gap + (i % 4) * (pw + gap), py = y + (i / 4) * (ph + gap * 0.8f);
            String l = presets[i] == 0 ? tr("OFF", "AUCUN") : presets[i] >= 60 ? (presets[i] / 60) + (presets[i] % 60 == 0 ? " min" : ":30") : presets[i] + " s";
            delayButton(c, new RectF(px, py, px + pw, py + ph), l, 1, presets[i], false, presets[i] == delaySec);
        }
        // sync + done
        y += ph * 2 + gap * 2;
        float half = (panel.width() - gap * 3) / 2f;
        delayButton(c, new RectF(panel.left + gap, y, panel.left + gap + half, y + bh), tr("SYNC WITH TV", "SYNCHRO AVEC LA TV"), 2, 0, true, false);
        delayButton(c, new RectF(panel.right - gap - half, y, panel.right - gap, y + bh), tr("DONE", "OK"), 3, 0, false, false);
        text(c, tr("Sync: tap it, keep your stream on, then tap when you see the next score on TV",
                "Synchro : touche-la, garde ton stream, puis touche quand tu vois le prochain but à la TV"),
            panel.centerX(), y + bh + h * 0.04f, h * 0.024f, tfReg, DIM, Paint.Align.CENTER, panel.width() * 0.92f);
    }

    /** Sync with TV: hides the scores while waiting, then a big button to tap when the goal shows on TV. */
    private void drawSync(Canvas c, int w, int h) {
        p.setColor(0xFF000000);
        c.drawRect(-10, -10, w + 10, h + 10, p);
        float bh = h * 0.08f;
        syncCancel.set(w / 2f - w * 0.12f, h * 0.84f, w / 2f + w * 0.12f, h * 0.84f + bh);
        if (syncState == 1) {
            float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 400.0));
            p.setColor(alpha(ACCENT, (int) (60 + 120 * pulse)));
            c.drawCircle(w / 2f, h * 0.32f, h * 0.08f, p);
            text(c, tr("WATCHING FOR THE NEXT SCORE…", "EN ATTENTE DU PROCHAIN POINT…"), w / 2f, h * 0.5f, h * 0.045f, tfBlack, TEXT, Paint.Align.CENTER, w * 0.9f);
            text(c, tr("Scores are hidden so nothing is spoiled. Keep your stream playing.",
                    "Les scores sont cachés. Garde ton stream en marche."), w / 2f, h * 0.58f, h * 0.03f, tfReg, MUTED, Paint.Align.CENTER, w * 0.9f);
            text(c, tr("When a team scores, this screen will ask you to tap.", "Quand une équipe marque, cet écran te demandera de toucher."),
                w / 2f, h * 0.64f, h * 0.03f, tfReg, MUTED, Paint.Align.CENTER, w * 0.9f);
        } else {
            long sec = (System.currentTimeMillis() - syncSince) / 1000;
            float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 160.0));
            RectF b = new RectF(w * 0.12f, h * 0.18f, w * 0.88f, h * 0.7f);
            p.setShadowLayer(h * 0.04f * pulse, 0, 0, AMBER);
            p.setColor(AMBER);
            c.drawRoundRect(b, h * 0.05f, h * 0.05f, p);
            p.clearShadowLayer();
            text(c, tr("TAP NOW", "TOUCHE MAINTENANT"), b.centerX(), b.centerY() - h * 0.06f, h * 0.1f, tfBlack, 0xFF000000, Paint.Align.CENTER, b.width() * 0.9f);
            text(c, tr("when you see the score on your TV / stream", "quand tu vois le but à la TV / sur ton stream"), b.centerX(), b.centerY() + h * 0.07f,
                h * 0.035f, tfMed, 0xFF000000, Paint.Align.CENTER, b.width() * 0.9f);
            text(c, sec + " s", w / 2f, h * 0.77f, h * 0.04f, tfBlack, MUTED, Paint.Align.CENTER, w);
        }
        p.setColor(0xFF22252D);
        c.drawRoundRect(syncCancel, bh / 2, bh / 2, p);
        text(c, tr("CANCEL", "ANNULER"), syncCancel.centerX(), syncCancel.centerY(), bh * 0.36f, tfCond, TEXT, Paint.Align.CENTER, syncCancel.width());
        postInvalidateDelayed(50);
    }

    // ------------------------------------------------------------------ help / welcome
    private static final String[][] HELP = {
        {"League name", "Tap for the list of all leagues (★ MY TEAMS is first)"},
        {"Long-press a team", "Add it to MY TEAMS: highlights, horn, stick lights"},
        {"Tap a game", "Follow it on the BOARD (swipe ← → for other games)"},
        {"Tap a score on the Board", "Big score mode (tap again to go back)"},
        {"‹ TODAY ›", "Other days · tap the date to come back to today"},
        {"Tap the clock", "Settings: spoiler delay, horn, lights, night mode…"},
        {"Controller", "D-pad move · A open · B back · Select leagues · Start settings"},
    };

    private static final String[][] HELP_FR = {
        {"Nom de la ligue", "Touchez pour la liste des ligues (★ MES ÉQUIPES en premier)"},
        {"Appui long sur une équipe", "L'ajoute à MES ÉQUIPES : surlignage, klaxon, lumières"},
        {"Touchez un match", "Le suivre sur le TABLEAU (glissez ← → pour les autres)"},
        {"Touchez un score (Tableau)", "Mode gros score (touchez encore pour revenir)"},
        {"‹ AUJOURD'HUI ›", "Autres jours · touchez la date pour revenir"},
        {"Touchez l'heure", "Réglages : délai anti-spoiler, klaxon, lumières…"},
        {"Manette", "Croix bouger · A ouvrir · B retour · Select ligues · Start réglages"},
    };

    private void drawHelp(Canvas c, int w, int h) {
        p.setColor(0xF0000000);
        c.drawRect(0, 0, w, h, p);
        float rowH = h * 0.095f, x = w * 0.08f;
        String[][] HELP = fr ? HELP_FR : ThorView.HELP;
        text(c, tr("WELCOME TO THORSPORT", "BIENVENUE DANS THORSPORT"), w / 2f, h * 0.09f, h * 0.06f, tfBlack, TEXT, Paint.Align.CENTER, w * 0.9f);
        for (int i = 0; i < HELP.length; i++) {
            float y = h * 0.2f + i * rowH;
            text(c, HELP[i][0], x, y, rowH * 0.36f, tfBlack, AMBER, Paint.Align.LEFT, w * 0.32f);
            text(c, HELP[i][1], x + w * 0.33f, y, rowH * 0.32f, tfReg, TEXT, Paint.Align.LEFT, w * 0.58f);
        }
        text(c, tr("Tap anywhere to start", "Touchez n'importe où pour commencer"), w / 2f, h * 0.92f, h * 0.035f, tfCond, MUTED, Paint.Align.CENTER, w);
    }

    // ------------------------------------------------------------------ scores
    private void drawDateBar(Canvas c, int w, float top, float bh) {
        float gap = w * 0.016f;
        float cy = top + bh / 2f;
        String label;
        if (dateOffset == 0) label = tr("TODAY", "AUJOURD'HUI");
        else if (dateOffset == -1) label = tr("YESTERDAY", "HIER");
        else if (dateOffset == 1) label = tr("TOMORROW", "DEMAIN");
        else label = DateFormat.format("EEE d MMM", System.currentTimeMillis() + dateOffset * 86_400_000L).toString().toUpperCase();

        float arrowW = bh * 1.3f;
        prevRect.set(gap, top, gap + arrowW, top + bh);
        p.setColor(0xFF161920);
        c.drawRoundRect(prevRect, bh / 2, bh / 2, p);
        text(c, "‹", prevRect.centerX(), cy, bh * 0.7f, tfMed, TEXT, Paint.Align.CENTER, arrowW);
        float labelW = w * 0.2f;
        dateLabelRect.set(prevRect.right + gap, top, prevRect.right + gap + labelW, top + bh);
        text(c, label, prevRect.right + gap + labelW / 2, cy, bh * 0.42f, tfCond, dateOffset == 0 ? TEXT : AMBER, Paint.Align.CENTER, labelW);
        nextRect.set(prevRect.right + gap * 2 + labelW, top, prevRect.right + gap * 2 + labelW + arrowW, top + bh);
        p.setColor(0xFF161920);
        c.drawRoundRect(nextRect, bh / 2, bh / 2, p);
        text(c, "›", nextRect.centerX(), cy, bh * 0.7f, tfMed, TEXT, Paint.Align.CENTER, arrowW);

        // "my teams" filter chip
        String f = tr("★ MY TEAMS", "★ MES ÉQUIPES");
        p.setTypeface(tfCond);
        p.setTextSize(bh * 0.4f);
        float fw = p.measureText(f) + bh * 0.9f;
        filterRect.set(w - gap - fw, top, w - gap, top + bh);
        p.setColor(myTeamsOnly ? ACCENT : 0xFF161920);
        c.drawRoundRect(filterRect, bh / 2, bh / 2, p);
        text(c, f, filterRect.centerX(), cy, bh * 0.4f, tfCond, myTeamsOnly ? 0xFF000000 : MUTED, Paint.Align.CENTER, fw);

        if (updatedAt > 0) {
            long sec = (System.currentTimeMillis() - updatedAt) / 1000;
            int live = liveCount();
            String u = games.size() + tr(" games", " matchs") + (live > 0 ? " · " + live + tr(" live", " en direct") : "") + " · "
                + (sec < 60 ? sec + "s" : (sec / 60) + " min");
            float mid = (nextRect.right + filterRect.left) / 2f;
            text(c, u, mid, cy, bh * 0.34f, tfReg, DIM, Paint.Align.CENTER, filterRect.left - nextRect.right - gap * 2);
            postInvalidateDelayed(1000);
        }
    }

    private void drawScores(Canvas c, int w, int h, float top) {
        List<Model.Game> list = shownGames();
        int pages = pageCount();
        if (page >= pages) page = 0;
        int from = page * GAMES_PER_PAGE;
        List<Model.Game> pg = list.subList(from, Math.min(list.size(), from + GAMES_PER_PAGE));
        int m = pg.size();

        float gap = w * 0.016f;
        float dotsH = pages > 1 ? h * 0.035f : 0;
        float bodyH = h - top - dotsH - gap;

        int cols = m <= 2 ? 1 : 2;
        int rows = pages > 1 ? GAMES_PER_PAGE / 2 : (m + cols - 1) / cols;
        int slots = Math.max(rows, 2);
        float cw = (w - gap * (cols + 1)) / cols;
        float ch = (bodyH - gap * (slots - 1)) / slots;
        float blockH = rows * ch + (rows - 1) * gap;
        float y0 = top + (bodyH - blockH) / 2f;

        for (int i = 0; i < m; i++) {
            int row = i / cols, col = i % cols;
            float x = gap + col * (cw + gap);
            boolean lastAlone = cols == 2 && i == m - 1 && m % 2 == 1;
            if (lastAlone) x = (w - cw) / 2f;
            float y = y0 + row * (ch + gap);
            if ("racing".equals(pg.get(i).sport)) drawRaceCard(c, pg.get(i), x, y, cw, ch);
            else drawCard(c, pg.get(i), x, y, cw, ch);
        }

        if (pages > 1) {
            float dy = h - dotsH / 2f - gap / 2f;
            float rr = dotsH * 0.14f, spacing = rr * 4f;
            float start = w / 2f - (pages - 1) * spacing / 2f;
            for (int i = 0; i < pages; i++) {
                p.setColor(i == page ? TEXT : 0x44FFFFFF);
                c.drawCircle(start + i * spacing, dy, rr, p);
            }
        }
    }

    private void drawCard(Canvas c, Model.Game g, float x, float y, float cw, float ch) {
        RectF r = new RectF(x, y, x + cw, y + ch);
        float rad = Math.min(cw, ch) * 0.1f;
        boolean fav = g.involves(favorites);
        Long fu = flashUntil.get(g.id);
        boolean flashing = fu != null && fu > System.currentTimeMillis();
        if (fu != null && !flashing) flashUntil.remove(g.id);

        p.setStyle(Paint.Style.FILL);
        p.setColor(CARD);
        c.drawRoundRect(r, rad, rad, p);
        p.setShader(new LinearGradient(r.left, 0, r.right, 0,
            new int[]{alpha(g.away.color, 0x70), alpha(g.away.color, 0x00), alpha(g.home.color, 0x00), alpha(g.home.color, 0x70)},
            new float[]{0f, 0.45f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, p);
        p.setShader(null);
        if (flashing) {
            float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 90.0));
            p.setColor(alpha(AMBER, (int) (40 + 60 * pulse)));
            c.drawRoundRect(r, rad, rad, p);
        }

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(g.isLive() || fav || flashing ? Math.max(2f, ch * 0.012f) : 1.5f);
        p.setColor(flashing ? AMBER : g.isLive() ? alpha(LIVE, 0xCC) : fav ? ACCENT : HAIRLINE);
        c.drawRoundRect(r, rad, rad, p);
        p.setStyle(Paint.Style.FILL);

        hitRects.add(r);
        hitAbbrs.add(new String[]{g.away.abbr, g.home.abbr});
        cardRects.add(r);
        cardIds.add(g.id);

        float sideW = cw * 0.3f;
        float logoS = Math.min(ch * 0.5f, sideW * 0.66f);
        drawSide(c, g.away, r.left + sideW / 2f, y, ch, sideW, logoS);
        drawSide(c, g.home, r.right - sideW / 2f, y, ch, sideW, logoS);

        float cx = r.centerX();
        float centerW = cw - 2 * sideW;
        if ("pre".equals(g.state)) {
            String time = g.startMs > 0 ? fmtTime(g.startMs) : g.detail;
            text(c, time, cx, y + ch * 0.4f, ch * 0.2f, tfBlack, TEXT, Paint.Align.CENTER, centerW);
            long mins = (g.startMs - System.currentTimeMillis()) / 60000;
            String day;
            int dayCol = MUTED;
            if (g.startMs > 0 && mins >= 0 && mins < 60) { day = "IN " + mins + " MIN"; dayCol = AMBER; }
            else day = g.startMs > 0 && !DateUtils.isToday(g.startMs)
                ? DateFormat.format("EEE d MMM", g.startMs).toString().toUpperCase() : "TODAY";
            text(c, day, cx, y + ch * 0.62f, ch * 0.095f, tfCond, dayCol, Paint.Align.CENTER, centerW);
            if (g.away.odds >= 0) drawOddsBar(c, g, cx - centerW * 0.42f, y + ch * 0.78f, centerW * 0.84f, ch * 0.075f, false);
            else if (!g.tv.isEmpty()) text(c, g.tv, cx, y + ch * 0.78f, ch * 0.08f, tfReg, DIM, Paint.Align.CENTER, centerW);
        } else {
            boolean fin = g.isFinal();
            int awayCol = fin && g.home.winner ? DIM : TEXT;
            int homeCol = fin && g.away.winner ? DIM : TEXT;
            float off = centerW * 0.27f;
            float scoreSize = Math.min(ch * 0.36f, centerW * 0.3f);
            text(c, g.away.score, cx - off, y + ch * 0.42f, scoreSize, tfBlack, awayCol, Paint.Align.CENTER, centerW * 0.45f);
            text(c, g.home.score, cx + off, y + ch * 0.42f, scoreSize, tfBlack, homeCol, Paint.Align.CENTER, centerW * 0.45f);
            text(c, "–", cx, y + ch * 0.42f, scoreSize * 0.5f, tfMed, DIM, Paint.Align.CENTER, centerW);

            float sy = y + ch * 0.77f;
            if (g.isLive()) {
                float ts = Math.min(ch * 0.1f, centerW * 0.1f);
                p.setTypeface(tfCond);
                p.setTextSize(ts);
                String d = fit(g.detail, centerW * 0.8f);
                float tw = p.measureText(d);
                float pillW = tw + ts * 1.6f, pillH = ts * 1.65f;
                RectF pill = new RectF(cx - pillW / 2, sy - pillH / 2, cx + pillW / 2, sy + pillH / 2);
                p.setColor(alpha(LIVE, 0x33));
                c.drawRoundRect(pill, pillH / 2, pillH / 2, p);
                p.setColor(LIVE);
                c.drawCircle(pill.left + ts * 0.55f, sy, ts * 0.22f, p);
                text(c, d, pill.left + ts * 0.95f, sy, ts, tfCond, TEXT, Paint.Align.LEFT, centerW);

                if ("baseball".equals(g.sport) && g.outs >= 0) {
                    float ds = ch * 0.045f, dcy = y + ch * 0.885f, dcx = cx - centerW * 0.18f;
                    float[][] pos = {{dcx + ds, dcy}, {dcx, dcy - ds}, {dcx - ds, dcy}};
                    for (int b = 0; b < 3; b++) {
                        c.save(); c.rotate(45, pos[b][0], pos[b][1]);
                        p.setColor(g.bases[b] ? AMBER : 0xFF2A2E38);
                        c.drawRect(pos[b][0] - ds * 0.42f, pos[b][1] - ds * 0.42f, pos[b][0] + ds * 0.42f, pos[b][1] + ds * 0.42f, p);
                        c.restore();
                    }
                    for (int o = 0; o < 3; o++) {
                        p.setColor(o < g.outs ? LIVE : 0xFF2A2E38);
                        c.drawCircle(cx + centerW * 0.02f + o * ds * 1.3f, dcy, ds * 0.42f, p);
                    }
                }
                // last scorer
                if (!g.goals.isEmpty() && !("football".equals(g.sport) && g.ballYard >= 0)) {
                    Model.Goal lg2 = null;
                    for (Model.Goal gl : g.goals) if (!gl.red) lg2 = gl;
                    if (lg2 != null) text(c, (lg2.home ? g.home.abbr : g.away.abbr) + " · " + lg2.who, cx, y + ch * 0.88f, ch * 0.07f, tfReg, MUTED, Paint.Align.CENTER, centerW);
                }

                if ("football".equals(g.sport) && g.ballYard >= 0) {
                    float fh2 = ch * 0.07f, fpad = centerW * 0.04f;   // centre column only: the team records stay visible
                    RectF fr = new RectF(r.left + sideW + fpad, y + ch * 0.86f, r.right - sideW - fpad, y + ch * 0.86f + fh2);
                    p.setColor(0xFF143A22);
                    c.drawRoundRect(fr, fh2 * 0.3f, fh2 * 0.3f, p);
                    float ez = fr.width() / 12f;
                    p.setColor(alpha(g.away.color, 0xAA)); c.drawRect(fr.left, fr.top, fr.left + ez, fr.bottom, p);
                    p.setColor(alpha(g.home.color, 0xAA)); c.drawRect(fr.right - ez, fr.top, fr.right, fr.bottom, p);
                    float bx = fr.left + ez + (fr.width() - 2 * ez) * g.ballYard / 100f;
                    if (g.toGo > 0 && !g.possession.isEmpty()) {
                        int dir = "home".equals(g.possession) ? -1 : 1;
                        float fdx = fr.left + ez + (fr.width() - 2 * ez) * Math.max(0, Math.min(100, g.ballYard + dir * g.toGo)) / 100f;
                        p.setColor(0xFFFFD60A); c.drawRect(fdx - 1.5f, fr.top, fdx + 1.5f, fr.bottom, p);
                    }
                    p.setColor(0xFF8B4A1C);
                    c.drawOval(new RectF(bx - fh2 * 0.55f, fr.centerY() - fh2 * 0.32f, bx + fh2 * 0.55f, fr.centerY() + fh2 * 0.32f), p);
                    return;
                }
                // progress bar along the bottom of the card
                float bh = Math.max(3f, ch * 0.022f), inset = rad * 0.9f;
                RectF track = new RectF(r.left + inset, r.bottom - bh * 2.4f, r.right - inset, r.bottom - bh * 1.4f);
                p.setColor(0x22FFFFFF);
                c.drawRoundRect(track, bh, bh, p);
                p.setColor(LIVE);
                c.drawRoundRect(new RectF(track.left, track.top, track.left + track.width() * progress(g), track.bottom), bh, bh, p);
            } else {
                text(c, g.detail.toUpperCase(), cx, sy, ch * 0.095f, tfCond, MUTED, Paint.Align.CENTER, centerW);
            }
        }
    }

    /** Split bar with both teams' win chances (Polymarket or sportsbook). */
    private void drawOddsBar(Canvas c, Model.Game g, float x, float cy, float width, float th, boolean withSource) {
        float a = g.away.odds, h = g.home.odds;
        if (a < 0 || h < 0) return;
        float d = g.drawOdds > 0 ? g.drawOdds : 0;
        float bh = th * 0.55f, top = cy + th * 0.55f;
        String la = Math.round(a * 100) + "%", lh = Math.round(h * 100) + "%";
        text(c, la, x, cy, th, tfBlack, a >= h ? TEXT : MUTED, Paint.Align.LEFT, width * 0.4f);
        text(c, lh, x + width, cy, th, tfBlack, h >= a ? TEXT : MUTED, Paint.Align.RIGHT, width * 0.4f);
        if (withSource) {
            boolean livePoly = g.isLive() && "POLYMARKET".equals(g.oddsSource);
            String src = g.oddsSource + (livePoly ? " · LIVE" : g.isLive() ? tr(" · PRE-GAME", " · AVANT-MATCH") : "")
                + (d > 0 ? tr(" · DRAW ", " · NUL ") + Math.round(d * 100) + "%" : "");
            text(c, src, x + width / 2, cy, th * 0.62f, tfCond, livePoly ? LIVE : DIM, Paint.Align.CENTER, width * 0.56f);
        }
        float tot = a + h + d;
        float wa = width * a / tot, wd = width * d / tot;
        p.setColor(g.away.color);
        c.drawRoundRect(new RectF(x, top, x + wa, top + bh), bh / 2, bh / 2, p);
        if (wd > 0) { p.setColor(0xFF5E636D); c.drawRect(x + wa, top, x + wa + wd, top + bh, p); }
        p.setColor(g.home.color);
        c.drawRoundRect(new RectF(x + wa + wd, top, x + width, top + bh), bh / 2, bh / 2, p);
    }

    // ------------------------------------------------------------------ F1
    private String raceWhen(Model.Game g) {
        if (g.startMs <= 0) return g.detail;
        String t = fmtTime(g.startMs);
        return DateUtils.isToday(g.startMs) ? "TODAY " + t : DateFormat.format("EEE d MMM", g.startMs).toString().toUpperCase() + " · " + t;
    }

    private static final int F1_RED = 0xFFE10600;

    /** Drawn check mark (fonts on some devices miss ✓). */
    private void check(Canvas c, float cx, float cy, float s, int col) {
        Path k = new Path();
        k.moveTo(cx - s * 0.45f, cy);
        k.lineTo(cx - s * 0.12f, cy + s * 0.32f);
        k.lineTo(cx + s * 0.45f, cy - s * 0.35f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, s * 0.18f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(col);
        c.drawPath(k, p);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStrokeJoin(Paint.Join.MITER);
        p.setStyle(Paint.Style.FILL);
    }

    private static String raceDay(Model.Game g) {
        return g.startMs <= 0 ? "" : DateFormat.format("EEE", g.startMs).toString().toUpperCase();
    }

    /** Short countdown: "2d 4h", "3h 05m", "12:40". */
    private static String countdown(long ms) {
        if (ms < 0) ms = 0;
        long d = ms / 86_400_000L, hh = (ms / 3_600_000L) % 24, mm = (ms / 60000) % 60, ss = (ms / 1000) % 60;
        return d > 0 ? d + "d " + hh + "h" : hh > 0 ? hh + "h " + String.format(Locale.US, "%02d", mm) + "m" : String.format(Locale.US, "%d:%02d", mm, ss);
    }

    /** Status pill used on cards and the weekend list. */
    private void raceStatus(Canvas c, Model.Game s, float right, float cy, float hgt) {
        if (s.isFinal()) {
            RectF b = new RectF(right - hgt, cy - hgt / 2, right, cy + hgt / 2);
            p.setColor(alpha(GREEN, 0x2E));
            c.drawRoundRect(b, hgt / 2, hgt / 2, p);
            check(c, b.centerX(), b.centerY(), hgt * 0.5f, GREEN);
            return;
        }
        String t = s.isLive() ? "LIVE" : countdown(s.startMs - System.currentTimeMillis());
        p.setTypeface(tfBlack);
        p.setTextSize(hgt * 0.5f);
        float tw = p.measureText(t) + hgt * 0.8f;
        RectF b = new RectF(right - tw, cy - hgt / 2, right, cy + hgt / 2);
        p.setColor(s.isLive() ? LIVE : 0xFF262A33);
        c.drawRoundRect(b, hgt / 2, hgt / 2, p);
        text(c, t, b.centerX(), b.centerY(), hgt * 0.5f, tfBlack, s.isLive() ? 0xFFFFFFFF : TEXT, Paint.Align.CENTER, tw);
        if (s.isLive()) postInvalidateDelayed(1000);
    }

    /** Scores grid card: one session of the weekend (or last race with its podium). */
    private void drawRaceCard(Canvas c, Model.Game g, float x, float y, float cw, float ch) {
        RectF r = new RectF(x, y, x + cw, y + ch);
        float rad = Math.min(cw, ch) * 0.1f;
        p.setShader(new LinearGradient(r.left, r.top, r.left, r.bottom, 0xFF1A1C23, 0xFF111217, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, p);
        p.setShader(null);
        // red speed stripe on the left
        c.save();
        Path clip = new Path();
        clip.addRoundRect(r, rad, rad, Path.Direction.CW);
        c.clipPath(clip);
        p.setColor(g.isFinal() ? 0xFF3A3D46 : F1_RED);
        c.drawRect(r.left, r.top, r.left + ch * 0.06f, r.bottom, p);
        c.restore();
        if (g.isLive()) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, ch * 0.014f));
            p.setColor(LIVE);
            c.drawRoundRect(r, rad, rad, p);
            p.setStyle(Paint.Style.FILL);
        }
        cardRects.add(r);
        cardIds.add(g.id);

        float lx = r.left + ch * 0.16f;
        boolean podium = !g.results.isEmpty();
        float infoW = (podium ? cw * 0.48f : cw * 0.62f) - ch * 0.16f;
        // session code + name
        text(c, g.away.abbr.equals("LAST") ? tr("LAST RACE", "DERNIÈRE COURSE") : g.away.name.toUpperCase(), lx, r.top + ch * 0.2f, ch * 0.095f, tfCond,
            g.isFinal() ? MUTED : 0xFFFF6B5E, Paint.Align.LEFT, infoW);
        text(c, g.event, lx, r.top + ch * 0.42f, ch * 0.15f, tfBlack, TEXT, Paint.Align.LEFT, infoW);
        text(c, g.isFinal() ? (g.startMs > 0 ? DateFormat.format("EEE d MMM", g.startMs).toString().toUpperCase() : "") : raceWhen(g),
            lx, r.top + ch * 0.64f, ch * 0.085f, tfMed, MUTED, Paint.Align.LEFT, infoW);
        if (!podium) raceStatus(c, g, r.right - ch * 0.12f, r.top + ch * 0.82f, ch * 0.15f);
        else {
            float px = r.left + cw * 0.5f, pw = r.right - ch * 0.12f - px;
            for (int i = 0; i < Math.min(3, g.results.size()); i++) {
                float py = r.top + ch * (0.24f + i * 0.24f);
                RectF pos = new RectF(px, py - ch * 0.08f, px + ch * 0.16f, py + ch * 0.08f);
                p.setColor(i == 0 ? AMBER : i == 1 ? 0xFFC0C6CF : 0xFFCD7F32);
                c.drawRoundRect(pos, ch * 0.03f, ch * 0.03f, p);
                text(c, String.valueOf(i + 1), pos.centerX(), pos.centerY(), ch * 0.1f, tfBlack, 0xFF000000, Paint.Align.CENTER, pos.width());
                int tc = i < g.resultColors.size() ? g.resultColors.get(i) : MUTED;
                p.setColor(tc);
                c.drawRect(pos.right + ch * 0.04f, pos.top, pos.right + ch * 0.06f, pos.bottom, p);
                String code = g.results.get(i).split("\\s+")[0];
                text(c, code, pos.right + ch * 0.1f, py, ch * 0.12f, tfBlack, TEXT, Paint.Align.LEFT, pw - ch * 0.3f);
            }
        }
        text(c, g.isFinal() ? "" : g.note.toUpperCase(), lx, r.top + ch * 0.82f, ch * 0.075f, tfCond, DIM, Paint.Align.LEFT, infoW);
    }

    private static boolean mStartsSoon(Model.Game g) {
        long mins = (g.startMs - System.currentTimeMillis()) / 60000;
        return mins >= 0 && mins < 24 * 60;
    }

    /** F1 Board: Grand Prix header with countdown, the weekend on the left, a timing tower on the right. */
    private void drawRaceBoard(Canvas c, Model.Game g, int w, int h, float top) {
        float H = h - top, gap = w * 0.016f;

        // ---------- header
        RectF hero = new RectF(gap, top, w - gap, top + H * 0.27f);
        float hr = H * 0.03f;
        p.setShader(new LinearGradient(hero.left, 0, hero.right, 0, 0xFF1D1F27, 0xFF121318, Shader.TileMode.CLAMP));
        c.drawRoundRect(hero, hr, hr, p);
        p.setShader(null);
        c.save();
        Path hclip = new Path();
        hclip.addRoundRect(hero, hr, hr, Path.Direction.CW);
        c.clipPath(hclip);
        // diagonal red speed stripes
        p.setColor(F1_RED);
        Path stripe = new Path();
        float sx = hero.left, sw = hero.height() * 0.09f;
        stripe.moveTo(sx, hero.top); stripe.lineTo(sx + sw * 2, hero.top); stripe.lineTo(sx + sw * 2 - hero.height() * 0.25f, hero.bottom); stripe.lineTo(sx, hero.bottom); stripe.close();
        c.drawPath(stripe, p);
        p.setColor(alpha(F1_RED, 0x55));
        Path stripe2 = new Path();
        float s2 = sx + sw * 2.6f;
        stripe2.moveTo(s2, hero.top); stripe2.lineTo(s2 + sw * 0.7f, hero.top); stripe2.lineTo(s2 + sw * 0.7f - hero.height() * 0.25f, hero.bottom); stripe2.lineTo(s2 - hero.height() * 0.25f, hero.bottom); stripe2.close();
        c.drawPath(stripe2, p);
        c.restore();

        float lx = hero.left + sw * 4.2f, lw = hero.width() * 0.56f;
        String kicker = (g.note.isEmpty() ? "" : g.note.toUpperCase() + "   ") + (g.away.abbr.equals("LAST") ? tr("LAST RACE", "DERNIÈRE COURSE") : g.away.name.toUpperCase());
        text(c, kicker, lx, hero.top + hero.height() * 0.22f, H * 0.03f, tfCond, 0xFFFF6B5E, Paint.Align.LEFT, lw);
        text(c, g.event.toUpperCase(), lx, hero.top + hero.height() * 0.5f, H * 0.07f, tfBlack, TEXT, Paint.Align.LEFT, lw);
        if (!g.venue.isEmpty()) text(c, g.venue, lx, hero.top + hero.height() * 0.78f, H * 0.028f, tfReg, MUTED, Paint.Align.LEFT, lw);

        // countdown / status (segmented)
        RectF box = new RectF(hero.right - hero.width() * 0.36f, hero.top + hero.height() * 0.16f, hero.right - gap, hero.bottom - hero.height() * 0.16f);
        if (g.isLive() || g.isFinal()) {
            p.setColor(g.isLive() ? LIVE : 0xFF262A33);
            c.drawRoundRect(box, box.height() * 0.2f, box.height() * 0.2f, p);
            text(c, g.isLive() ? "LIVE" : tr("FINISHED", "TERMINÉ"), box.centerX(), box.top + box.height() * 0.42f, box.height() * 0.36f, tfBlack, 0xFFFFFFFF, Paint.Align.CENTER, box.width() * 0.9f);
            String sub = g.isLive() ? g.detail : g.startMs > 0 ? DateFormat.format("EEE d MMM", g.startMs).toString().toUpperCase() : "";
            text(c, sub, box.centerX(), box.top + box.height() * 0.78f, box.height() * 0.14f, tfCond, g.isLive() ? 0xFFFFFFFF : MUTED, Paint.Align.CENTER, box.width() * 0.9f);
            if (g.isLive()) postInvalidateDelayed(1000);
        } else {
            long ms = Math.max(0, g.startMs - System.currentTimeMillis());
            long[] v = {ms / 86_400_000L, (ms / 3_600_000L) % 24, (ms / 60000) % 60, (ms / 1000) % 60};
            String[] lab = {tr("DAYS", "JOURS"), tr("HRS", "H"), "MIN", tr("SEC", "S")};
            float segGap = box.width() * 0.03f, segW = (box.width() - segGap * 3) / 4f;
            for (int i = 0; i < 4; i++) {
                RectF sg = new RectF(box.left + i * (segW + segGap), box.top, box.left + i * (segW + segGap) + segW, box.bottom);
                p.setColor(0xFF0A0B0E);
                c.drawRoundRect(sg, segW * 0.14f, segW * 0.14f, p);
                text(c, String.format(Locale.US, "%02d", v[i]), sg.centerX(), sg.top + sg.height() * 0.42f, Math.min(sg.height() * 0.42f, segW * 0.55f), tfBlack, i == 3 ? 0xFFFF6B5E : TEXT, Paint.Align.CENTER, segW * 0.95f);
                text(c, lab[i], sg.centerX(), sg.top + sg.height() * 0.8f, sg.height() * 0.13f, tfCond, MUTED, Paint.Align.CENTER, segW);
            }
            postInvalidateDelayed(1000);
        }

        // ---------- body: weekend (left) + timing tower (right)
        float by = hero.bottom + gap * 1.1f, bb = h - gap;
        Model.Game res = !g.results.isEmpty() ? g : null;
        if (res == null) for (Model.Game s : games) if (!s.results.isEmpty()) { res = s; break; }
        List<Model.Game> wk = new ArrayList<>();
        for (Model.Game s : games) if (s.event.equals(g.event) && !"LAST".equals(s.away.abbr)) wk.add(s);
        wk.sort((a, b2) -> Long.compare(a.startMs, b2.startMs));
        boolean twoCols = res != null && !wk.isEmpty();
        float leftR = twoCols ? w * 0.5f : w - gap;

        if (!wk.isEmpty()) {
            text(c, tr("RACE WEEKEND", "WEEK-END DE COURSE"), gap * 1.4f, by + H * 0.02f, H * 0.028f, tfCond, MUTED, Paint.Align.LEFT, w);
            float ly = by + H * 0.05f;
            float rowH = Math.min(H * 0.1f, (bb - ly) / wk.size());
            for (int i = 0; i < wk.size(); i++) {
                Model.Game s = wk.get(i);
                RectF row = new RectF(gap, ly + i * rowH + rowH * 0.07f, leftR, ly + (i + 1) * rowH - rowH * 0.07f);
                boolean cur = s.id.equals(g.id);
                p.setColor(cur ? 0xFF22252E : 0xFF14161B);
                c.drawRoundRect(row, row.height() * 0.22f, row.height() * 0.22f, p);
                if (cur) { p.setColor(F1_RED); c.drawRoundRect(new RectF(row.left, row.top, row.left + row.height() * 0.1f, row.bottom), 3, 3, p); }
                float rh = row.height();
                text(c, raceDay(s), row.left + rh * 0.35f, row.top + rh * 0.36f, rh * 0.26f, tfBlack, s.isFinal() ? DIM : TEXT, Paint.Align.LEFT, rh * 1.4f);
                text(c, s.startMs > 0 ? fmtTime(s.startMs) : "", row.left + rh * 0.35f, row.top + rh * 0.7f, rh * 0.22f, tfMed, MUTED, Paint.Align.LEFT, rh * 1.6f);
                text(c, s.away.name, row.left + rh * 2.0f, row.centerY(), rh * 0.34f, tfBlack, s.isFinal() ? MUTED : TEXT, Paint.Align.LEFT, row.width() - rh * 4.4f);
                raceStatus(c, s, row.right - rh * 0.25f, row.centerY(), rh * 0.48f);
            }
        }

        if (res != null) {
            float tx = twoCols ? w * 0.5f + gap : gap, tr2 = w - gap;
            String title = (res.away.abbr.equals("LAST") ? tr("LAST RACE", "DERNIÈRE COURSE") + " · " + res.event : res.away.name.toUpperCase() + " · " + tr("RESULT", "RÉSULTAT")).toUpperCase();
            text(c, title, tx + gap * 0.4f, by + H * 0.02f, H * 0.028f, tfCond, MUTED, Paint.Align.LEFT, tr2 - tx);
            float ly = by + H * 0.05f;
            int n = Math.min(10, res.results.size());
            int perCol = twoCols ? Math.max(n, 8) : 5;          // full width: two columns of 5
            float colW = twoCols ? tr2 - tx : (tr2 - tx - gap) / 2f;
            float rowH = (bb - ly) / perCol;
            for (int i = 0; i < n; i++) {
                float cx0 = tx + (twoCols ? 0 : (i / 5) * (colW + gap));
                int ri = twoCols ? i : i % 5;
                RectF row = new RectF(cx0, ly + ri * rowH + rowH * 0.06f, cx0 + colW, ly + (ri + 1) * rowH - rowH * 0.06f);
                float rh = row.height();
                p.setColor(i % 2 == 0 ? 0xFF15171D : 0xFF111318);
                c.drawRoundRect(row, rh * 0.18f, rh * 0.18f, p);
                RectF pos = new RectF(row.left + rh * 0.12f, row.top + rh * 0.14f, row.left + rh * 0.12f + rh * 0.9f, row.bottom - rh * 0.14f);
                p.setColor(i == 0 ? AMBER : i == 1 ? 0xFFC0C6CF : i == 2 ? 0xFFCD7F32 : 0xFF2A2E37);
                c.drawRoundRect(pos, rh * 0.12f, rh * 0.12f, p);
                text(c, String.valueOf(i + 1), pos.centerX(), pos.centerY(), rh * 0.44f, tfBlack, i < 3 ? 0xFF000000 : TEXT, Paint.Align.CENTER, pos.width());
                int tc = i < res.resultColors.size() ? res.resultColors.get(i) : MUTED;
                p.setColor(tc);
                c.drawRoundRect(new RectF(pos.right + rh * 0.18f, pos.top, pos.right + rh * 0.28f, pos.bottom), 3, 3, p);
                String[] nm = res.results.get(i).split("\\s+", 2);
                String[] info = i < res.resultInfo.size() ? res.resultInfo.get(i).split("\\|", -1) : new String[]{"", ""};
                float nx = pos.right + rh * 0.45f;
                String family = nm.length > 1 ? nm[1] : nm[0];
                text(c, family, nx, row.top + rh * 0.38f, rh * 0.36f, tfBlack, TEXT, Paint.Align.LEFT, row.width() * 0.5f);
                text(c, info[0], nx, row.top + rh * 0.76f, rh * 0.23f, tfMed, MUTED, Paint.Align.LEFT, row.width() * 0.5f);
                if (info.length > 1) text(c, info[1], row.right - rh * 0.3f, row.centerY(), rh * 0.32f, tfMed, i == 0 ? TEXT : 0xFFC9CDD3, Paint.Align.RIGHT, row.width() * 0.3f);
            }
        }
    }

    private void drawSide(Canvas c, Model.Side s, float cx, float y, float ch, float sideW, float logoS) {
        logo(c, s.logo, s.abbr, s.color, cx, y + ch * 0.4f, logoS);
        text(c, star(s.abbr), cx, y + ch * 0.76f, ch * 0.13f, tfCond, favorites.contains(s.abbr) ? ACCENT : TEXT, Paint.Align.CENTER, sideW * 0.95f);
        if (!s.record.isEmpty())
            text(c, s.record, cx, y + ch * 0.89f, ch * 0.08f, tfReg, MUTED, Paint.Align.CENTER, sideW * 0.95f);
    }

    // ------------------------------------------------------------------ board (arena scoreboard)
    private void drawBoard(Canvas c, Model.Game g, int w, int h, float top) {
        float H = h - top;
        float gap = w * 0.016f;
        float heroH = H * 0.62f;
        float panelW = w * 0.355f;

        drawBoardPanel(c, g, g.away, gap, top, panelW, heroH);
        drawBoardPanel(c, g, g.home, w - gap - panelW, top, panelW, heroH);

        // ---- center "LED" box: period + clock
        float cx = w / 2f;
        float centerW = w - 2 * gap - 2 * panelW;
        float boxW = centerW - 2 * gap;
        float boxTop = top + heroH * 0.06f, boxH = heroH * 0.5f;
        RectF box = new RectF(cx - boxW / 2, boxTop, cx + boxW / 2, boxTop + boxH);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF06070A);
        c.drawRoundRect(box, boxW * 0.08f, boxW * 0.08f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        p.setColor(0xFF262A33);
        c.drawRoundRect(box, boxW * 0.08f, boxW * 0.08f, p);
        p.setStyle(Paint.Style.FILL);

        String label, big;
        int bigColor = AMBER;
        if ("pre".equals(g.state)) {
            boolean today = g.startMs == 0 || DateUtils.isToday(g.startMs);
            label = today ? ("hockey".equals(g.sport) ? "PUCK DROP" : "START")
                : DateFormat.format("EEE d MMM", g.startMs).toString().toUpperCase();
            big = g.startMs > 0 ? fmtTime(g.startMs) : g.detail;
        } else if (g.isFinal()) {
            label = "";
            big = g.detail.toUpperCase();
            bigColor = TEXT;
        } else {
            String[] parts = g.detail.split(" - ");
            if (parts.length == 2) { big = parts[0]; label = parts[1].toUpperCase(); }
            else { big = g.detail; label = ""; }
        }
        boolean overtime = label.contains("OT") || label.contains("SO") || big.toUpperCase().contains("OT");
        if (overtime && g.isLive()) {   // sudden death: red pulse around the clock
            float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 180.0));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(boxH * 0.03f);
            p.setColor(alpha(LIVE, (int) (80 + 175 * pulse)));
            c.drawRoundRect(box, boxW * 0.08f, boxW * 0.08f, p);
            p.setStyle(Paint.Style.FILL);
            postInvalidateDelayed(50);
        }
        int labelCol = overtime ? LIVE : AMBER;
        float labelY = box.top + boxH * 0.24f;
        float bigY = label.isEmpty() ? box.centerY() : box.top + boxH * 0.62f;
        if (!label.isEmpty()) {
            p.setShadowLayer(boxH * 0.05f, 0, 0, alpha(labelCol, 0x88));
            text(c, label, cx, labelY, boxH * 0.13f, tfCond, labelCol, Paint.Align.CENTER, boxW * 0.9f);
        }
        p.setShadowLayer(boxH * 0.07f, 0, 0, alpha(bigColor, 0x99));
        p.setTypeface(tfBlack);
        float bigSize = boxH * 0.36f;
        p.setTextSize(bigSize);
        float bw = p.measureText(big);
        if (bw > boxW * 0.88f) bigSize *= boxW * 0.88f / bw;
        text(c, big, cx, bigY, bigSize, tfBlack, bigColor, Paint.Align.CENTER, boxW * 0.95f);
        p.clearShadowLayer();

        // ---- under the box: LIVE pill / countdown
        float underY = box.bottom + heroH * 0.1f;
        if (g.isLive()) {
            float ts = heroH * 0.055f;
            String t = "LIVE";
            p.setTypeface(tfCond);
            p.setTextSize(ts);
            float tw = p.measureText(t);
            float pw = tw + ts * 1.9f, ph = ts * 1.8f;
            RectF pill = new RectF(cx - pw / 2, underY - ph / 2, cx + pw / 2, underY + ph / 2);
            long blink = (System.currentTimeMillis() / 600) % 2;
            p.setColor(LIVE);
            c.drawRoundRect(pill, ph / 2, ph / 2, p);
            p.setColor(blink == 0 ? TEXT : alpha(TEXT, 0x66));
            c.drawCircle(pill.left + ts * 0.62f, underY, ts * 0.2f, p);
            text(c, t, pill.left + ts * 1.05f, underY, ts, tfCond, TEXT, Paint.Align.LEFT, pw);
            postInvalidateDelayed(600);
        } else if ("pre".equals(g.state) && g.startMs > System.currentTimeMillis()) {
            long ms = g.startMs - System.currentTimeMillis();
            long mins = ms / 60000;
            String in;
            if (mins < 60) {
                long secs = (ms / 1000) % 60;
                in = String.format(java.util.Locale.US, "in %d:%02d", mins, secs);
                postInvalidateDelayed(1000);
            } else in = "in " + (mins / 60) + "h " + (mins % 60) + "m";
            text(c, in, cx, underY, heroH * 0.06f, tfMed, mins < 60 ? AMBER : MUTED, Paint.Align.CENTER, centerW);
        } else if (!g.tv.isEmpty()) {
            text(c, g.tv, cx, underY, heroH * 0.05f, tfReg, MUTED, Paint.Align.CENTER, centerW);
        }

        // ---- baseball: bases + count
        if ("baseball".equals(g.sport) && g.isLive() && g.outs >= 0) {
            float dcx = cx, dcy = top + heroH * 0.86f, ds = heroH * 0.06f;
            float[][] pos = {{dcx + ds, dcy}, {dcx, dcy - ds}, {dcx - ds, dcy}};
            for (int i = 0; i < 3; i++) {
                c.save();
                c.rotate(45, pos[i][0], pos[i][1]);
                p.setColor(g.bases[i] ? AMBER : 0xFF2A2E38);
                c.drawRect(pos[i][0] - ds * 0.45f, pos[i][1] - ds * 0.45f, pos[i][0] + ds * 0.45f, pos[i][1] + ds * 0.45f, p);
                c.restore();
            }
            String cnt = g.balls + "-" + g.strikes + "  ·  " + g.outs + " OUT";
            text(c, cnt, cx, dcy + ds * 1.5f, heroH * 0.045f, tfCond, TEXT, Paint.Align.CENTER, centerW);
        }

        // ---- win chances
        if (g.away.odds >= 0 && !g.isFinal() && !("baseball".equals(g.sport) && g.isLive() && g.outs >= 0)) {
            boolean shots = g.away.shots >= 0 && g.home.shots >= 0;
            drawOddsBar(c, g, cx - centerW * 0.44f, top + heroH * (shots ? 0.71f : 0.82f), centerW * 0.88f, heroH * 0.045f, true);
        }

        // ---- shots on goal (hockey) + comparison bar
        if (g.away.shots >= 0 && g.home.shots >= 0) {
            float sy = top + heroH * 0.86f;
            text(c, "SHOTS", cx, sy, heroH * 0.045f, tfCond, MUTED, Paint.Align.CENTER, centerW);
            float off = centerW * 0.36f;
            text(c, String.valueOf(g.away.shots), cx - off, sy, heroH * 0.075f, tfBlack, TEXT, Paint.Align.CENTER, centerW * 0.3f);
            text(c, String.valueOf(g.home.shots), cx + off, sy, heroH * 0.075f, tfBlack, TEXT, Paint.Align.CENTER, centerW * 0.3f);
            int tot = g.away.shots + g.home.shots;
            if (tot > 0) {
                float bx = cx - centerW * 0.42f, bwid = centerW * 0.84f, by = sy + heroH * 0.075f, bh = heroH * 0.018f;
                float split = bwid * g.away.shots / tot;
                p.setColor(g.away.color);
                c.drawRoundRect(new RectF(bx, by, bx + split, by + bh), bh / 2, bh / 2, p);
                p.setColor(g.home.color);
                c.drawRoundRect(new RectF(bx + split, by, bx + bwid, by + bh), bh / 2, bh / 2, p);
            }
        }

        // ---- period-by-period table
        if (g.isLive() || g.isFinal()) drawTimeline(c, g, gap, top + heroH + gap * 0.5f, w - 2 * gap, gap * 0.35f);
        float tTop = top + heroH + gap;
        float tBottom = top + H * 0.865f;
        if ("esports".equals(g.sport)) drawLolStats(c, g, gap, tTop, w - 2 * gap, tBottom - tTop);
        else {
            boolean side = !g.goals.isEmpty() || !g.away.leader.isEmpty() || !g.home.leader.isEmpty();
            float tableW = side ? (w - 3 * gap) * 0.6f : w - 2 * gap;
            drawLineScore(c, g, gap, tTop, tableW, tBottom - tTop);
            if (side) drawScorers(c, g, gap * 2 + tableW, tTop, w - gap * 3 - tableW, tBottom - tTop);
        }

        // ---- ticker + sound toggle
        float kTop = tBottom + gap * 0.8f, kBottom = h - gap * 0.8f;
        float kh = kBottom - kTop;
        RectF tick = new RectF(gap, kTop, w - gap - kh - gap * 0.6f, kBottom);
        p.setColor(0xFF12141A);
        c.drawRoundRect(tick, kh * 0.3f, kh * 0.3f, p);
        soundRect.set(tick.right + gap * 0.6f, kTop, w - gap, kBottom);
        p.setColor(0xFF12141A);
        c.drawRoundRect(soundRect, kh * 0.3f, kh * 0.3f, p);
        drawSpeaker(c, soundRect, sound);

        String tag, msg;
        if ("esports".equals(g.sport)) { tag = "INFO"; msg = g.note + (g.away.record.isEmpty() ? "" : "  ·  " + g.away.abbr + " " + g.away.record + "  ·  " + g.home.abbr + " " + g.home.record); }
        else if (g.isLive() && !g.downText.isEmpty()) { tag = "DOWN"; msg = g.downText + (g.lastPlay.isEmpty() ? "" : "  ·  " + g.lastPlay); }
        else if (g.isLive() && !g.lastPlay.isEmpty()) { tag = "LAST PLAY"; msg = g.lastPlay; }
        else if (g.isFinal() && !g.headline.isEmpty()) { tag = "RECAP"; msg = g.headline; }
        else if (!g.series.isEmpty()) { tag = "SERIES"; msg = g.series + (g.note.isEmpty() ? "" : "  ·  " + g.note); }
        else if (!g.note.isEmpty()) { tag = "INFO"; msg = g.note; }
        else if (!g.venue.isEmpty()) { tag = "VENUE"; msg = g.venue; }
        else { tag = "TIP"; msg = tr("Tap a score for big score · swipe ← → to change game", "Touchez un score pour l'agrandir · glissez ← → pour changer de match"); }
        float ts = kh * 0.36f;
        p.setTypeface(tfCond);
        p.setTextSize(ts * 0.85f);
        float tagW = p.measureText(tag) + ts * 1.2f;
        RectF tagR = new RectF(tick.left + kh * 0.18f, tick.top + kh * 0.2f, tick.left + kh * 0.18f + tagW, tick.bottom - kh * 0.2f);
        p.setColor(alpha(AMBER, 0x2A));
        c.drawRoundRect(tagR, tagR.height() / 2, tagR.height() / 2, p);
        text(c, tag, tagR.centerX(), tagR.centerY(), ts * 0.85f, tfCond, AMBER, Paint.Align.CENTER, tagW);
        text(c, msg, tagR.right + kh * 0.3f, tick.centerY(), ts, tfReg, TEXT, Paint.Align.LEFT, tick.right - tagR.right - kh * 0.5f);
    }

    /** Game progress, with a dot for every goal in the scoring team's color (hockey & soccer). */
    private void drawTimeline(Canvas c, Model.Game g, float x, float cy, float tw, float bh) {
        float prog = g.isFinal() ? 1f : progress(g);
        p.setColor(0x22FFFFFF);
        c.drawRoundRect(new RectF(x, cy - bh / 2, x + tw, cy + bh / 2), bh / 2, bh / 2, p);
        p.setColor(g.isFinal() ? 0x55FFFFFF : alpha(LIVE, 0xCC));
        c.drawRoundRect(new RectF(x, cy - bh / 2, x + tw * prog, cy + bh / 2), bh / 2, bh / 2, p);
        if (!"hockey".equals(g.sport) && !"soccer".equals(g.sport)) return;
        for (Model.Goal goal : g.goals) {
            if (goal.red) continue;
            float f = goalFraction(g.sport, goal.time);
            if (f < 0) continue;
            p.setColor(goal.home ? g.home.color : g.away.color);
            c.drawCircle(x + tw * Math.min(1f, f), cy, bh * 1.3f, p);
            p.setColor(0xFF000000);
            c.drawCircle(x + tw * Math.min(1f, f), cy, bh * 0.45f, p);
        }
    }

    private static float goalFraction(String sport, String time) {
        try {
            if ("soccer".equals(sport)) {
                String m = time.replaceAll("[^0-9+]", "");
                int plus = m.indexOf('+');
                int min = Integer.parseInt(plus > 0 ? m.substring(0, plus) : m);
                return min / 90f;
            }
            // hockey "2nd 10:38" / "OT 2:00"
            String[] parts = time.trim().split(" ");
            int per = parts[0].startsWith("OT") ? 4 : parts[0].startsWith("SO") ? 4 : Integer.parseInt(parts[0].replaceAll("[^0-9]", ""));
            String[] mmss = parts[parts.length - 1].split(":");
            float into = Integer.parseInt(mmss[0]) + Integer.parseInt(mmss[1]) / 60f;
            return ((per - 1) * 20 + into) / 60f;
        } catch (Exception e) {
            return -1;
        }
    }

    /** League of Legends live game: gold (with lead bar), kills, towers, dragons, barons, inhibitors. */
    private void drawLolStats(Canvas c, Model.Game g, float x, float y, float tw, float th) {
        RectF r = new RectF(x, y, x + tw, y + th);
        p.setColor(0xFF0C0E13);
        c.drawRoundRect(r, th * 0.12f, th * 0.12f, p);
        if (g.lol == null) {
            String m = g.isLive() ? tr("Waiting for live game stats…", "En attente des stats en direct…")
                : g.note.isEmpty() ? g.detail : g.note;
            text(c, m, r.centerX(), r.centerY(), th * 0.16f, tfMed, MUTED, Paint.Align.CENTER, tw * 0.9f);
            return;
        }
        int[] a = g.lol[0], h = g.lol[1];
        String[] labels = {tr("GOLD", "OR"), tr("KILLS", "ÉLIM."), tr("TOWERS", "TOURS"), tr("DRAGONS", "DRAGONS"), "BARONS", tr("INHIBITORS", "INHIB.")};
        float colW = tw / 3f, rowH = th / 2f;
        for (int i = 0; i < 6; i++) {
            float cx = x + colW * (i % 3) + colW / 2, cy = y + rowH * (i / 3) + rowH / 2;
            String av = i == 0 ? String.format(Locale.US, "%.1fk", a[0] / 1000f) : String.valueOf(a[i]);
            String hv = i == 0 ? String.format(Locale.US, "%.1fk", h[0] / 1000f) : String.valueOf(h[i]);
            text(c, labels[i], cx, cy - rowH * 0.24f, rowH * 0.16f, tfCond, DIM, Paint.Align.CENTER, colW);
            int ac = a[i] > h[i] ? TEXT : MUTED, hc = h[i] > a[i] ? TEXT : MUTED;
            text(c, av, cx - colW * 0.08f, cy + rowH * 0.06f, rowH * 0.3f, tfBlack, ac, Paint.Align.RIGHT, colW * 0.4f);
            text(c, hv, cx + colW * 0.08f, cy + rowH * 0.06f, rowH * 0.3f, tfBlack, hc, Paint.Align.LEFT, colW * 0.4f);
            float tot = a[i] + h[i];
            if (tot > 0) {   // share bar
                float bw = colW * 0.7f, bx = cx - bw / 2, by = cy + rowH * 0.3f, bh = rowH * 0.06f;
                p.setColor(g.away.color);
                c.drawRoundRect(new RectF(bx, by, bx + bw * a[i] / tot, by + bh), bh / 2, bh / 2, p);
                p.setColor(g.home.color);
                c.drawRoundRect(new RectF(bx + bw * a[i] / tot, by, bx + bw, by + bh), bh / 2, bh / 2, p);
            }
        }
        int diff = a[0] - h[0];
        if (Math.abs(diff) >= 100) {
            String lead = (diff > 0 ? g.away.abbr : g.home.abbr) + " +" + String.format(Locale.US, "%.1fk", Math.abs(diff) / 1000f);
            tag(c, lead, x + colW / 2, y + rowH * 0.12f, rowH * 0.13f, Paint.Align.CENTER, AMBER, 0xFF000000);
        }
        text(c, (g.lolAwayBlue ? tr("BLUE", "BLEU") : tr("RED", "ROUGE")) + " · " + g.away.abbr, x + tw * 0.02f, y + th - rowH * 0.1f, rowH * 0.12f, tfCond,
            g.lolAwayBlue ? 0xFF4DA3FF : 0xFFFF4D5E, Paint.Align.LEFT, tw * 0.3f);
        text(c, g.home.abbr + " · " + (g.lolAwayBlue ? tr("RED", "ROUGE") : tr("BLUE", "BLEU")), x + tw * 0.98f, y + th - rowH * 0.1f, rowH * 0.12f, tfCond,
            g.lolAwayBlue ? 0xFFFF4D5E : 0xFF4DA3FF, Paint.Align.RIGHT, tw * 0.3f);
    }

    /** Who scored (newest first), or each team's top player for sports without goals. */
    private void drawScorers(Canvas c, Model.Game g, float x, float y, float pw, float ph) {
        RectF r = new RectF(x, y, x + pw, y + ph);
        scorersRect.set(r);
        p.setColor(0xFF0C0E13);
        c.drawRoundRect(r, ph * 0.12f, ph * 0.12f, p);
        float rowH = ph / 4f, pad = rowH * 0.35f;
        boolean goals = !g.goals.isEmpty();
        String title = goals ? ("soccer".equals(g.sport) || "hockey".equals(g.sport) ? "GOALS" : "SCORING") : "TOP PLAYERS";
        text(c, title, x + pad, y + rowH * 0.5f, rowH * 0.36f, tfCond, AMBER, Paint.Align.LEFT, pw);
        if (goals) {
            int n = g.goals.size();
            int pages = (n + 2) / 3;
            int pg = goalsPage % Math.max(1, pages);
            for (int i = 0; i < 3 && pg * 3 + i < n; i++) {
                Model.Goal goal = g.goals.get(n - 1 - (pg * 3 + i));
                Model.Side sd = goal.home ? g.home : g.away;
                float cy = y + rowH * (1.5f + i);
                p.setColor(sd.color);
                c.drawCircle(x + pad + rowH * 0.12f, cy, rowH * 0.12f, p);
                String who = (goal.red ? tr("RED CARD ", "CARTON ROUGE ") : "") + goal.who + (goal.extra.isEmpty() ? "" : " (" + goal.extra + ")");
                text(c, goal.time, x + pw - pad, cy, rowH * 0.3f, tfCond, MUTED, Paint.Align.RIGHT, pw * 0.3f);
                text(c, sd.abbr + "  " + who, x + pad + rowH * 0.38f, cy, rowH * 0.34f, tfMed, TEXT, Paint.Align.LEFT, pw * 0.66f);
            }
            if (pages > 1) text(c, (pg + 1) + "/" + pages + tr(" · tap", " · touchez"), x + pw - pad, y + rowH * 0.5f, rowH * 0.28f, tfReg, DIM, Paint.Align.RIGHT, pw * 0.45f);
        } else {
            Model.Side[] sides = {g.away, g.home};
            for (int i = 0; i < 2; i++) {
                if (sides[i].leader.isEmpty()) continue;
                float cy = y + rowH * (1.6f + i * 1.2f);
                p.setColor(sides[i].color);
                c.drawCircle(x + pad + rowH * 0.12f, cy, rowH * 0.12f, p);
                text(c, sides[i].abbr + "  " + sides[i].leader, x + pad + rowH * 0.38f, cy, rowH * 0.34f, tfMed, TEXT, Paint.Align.LEFT, pw - pad * 2 - rowH * 0.4f);
            }
        }
    }

    private void drawBoardPanel(Canvas c, Model.Game g, Model.Side s, float x, float y, float pw, float ph) {
        RectF r = new RectF(x, y, x + pw, y + ph);
        float rad = pw * 0.06f;
        boolean won = g.isFinal() && s.winner;
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, y, 0, y + ph, alpha(s.color, won ? 0xA0 : 0x80), alpha(s.color, 0x12), Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, p);
        p.setShader(null);
        boolean fav = favorites.contains(s.abbr);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(fav || won ? Math.max(2f, ph * 0.006f) : 1.5f);
        p.setColor(won ? AMBER : fav ? ACCENT : HAIRLINE);
        c.drawRoundRect(r, rad, rad, p);
        p.setStyle(Paint.Style.FILL);
        hitRects.add(r);
        hitAbbrs.add(new String[]{s.abbr, s.abbr});

        float cx = r.centerX();
        if (logos != null && logos.get(s.logo) != null) {   // big faint logo watermark
            c.save();
            c.clipRect(r);
            bmpPaint.setAlpha(22);
            logo(c, s.logo, s.abbr, 0, cx + pw * (s == g.home ? 0.18f : -0.18f), y + ph * 0.6f, ph * 0.95f);
            bmpPaint.setAlpha(255);
            c.restore();
        }
        text(c, s == g.home ? tr("HOME", "DOMICILE") : tr("AWAY", "VISITEUR"), r.left + pw * 0.05f, y + ph * 0.06f, ph * 0.035f, tfCond, alpha(TEXT, 0x88), Paint.Align.LEFT, pw * 0.4f);
        logo(c, s.logo, s.abbr, s.color, cx, y + ph * 0.22f, Math.min(ph * 0.3f, pw * 0.5f));
        boolean poss = g.isLive() && (s == g.home ? "home" : "away").equals(g.possession);
        text(c, (poss ? "▶ " : "") + (fav ? "★ " : "") + s.name.toUpperCase(), cx, y + ph * 0.445f, ph * 0.07f, tfBlack, TEXT, Paint.Align.CENTER, pw * 0.9f);
        if (!s.record.isEmpty())
            text(c, s.record, cx, y + ph * 0.525f, ph * 0.045f, tfReg, MUTED, Paint.Align.CENTER, pw * 0.9f);
        if (won) {
            float ts = ph * 0.04f;
            RectF pill = new RectF(cx - ts * 2.6f, y + ph * 0.035f, cx + ts * 2.6f, y + ph * 0.035f + ts * 1.7f);
            p.setColor(AMBER);
            c.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, p);
            text(c, tr("WINNER", "GAGNANT"), cx, pill.centerY(), ts, tfCond, 0xFF000000, Paint.Align.CENTER, pill.width());
        }

        String score = "pre".equals(g.state) || s.score.isEmpty() ? "–" : s.score;
        boolean lost = g.isFinal() && !s.winner && (g.away.winner || g.home.winner);
        int col = "–".equals(score) || lost ? DIM : TEXT;
        float size = Math.min(ph * 0.36f, pw * 0.46f);
        Long pa = popAt.get(g.id + ":" + (s == g.home ? "home" : "away"));
        if (pa != null) {
            long t = System.currentTimeMillis() - pa;
            if (t < 900) { size *= 1 + 0.3f * (float) Math.sin(Math.PI * t / 900.0); col = AMBER; postInvalidateOnAnimation(); }
        }
        p.setShadowLayer(ph * 0.02f, 0, ph * 0.006f, 0x99000000);
        text(c, score, cx, y + ph * 0.79f, size, tfBlack, col, Paint.Align.CENTER, pw * 0.95f);
        p.clearShadowLayer();
    }

    /** Double-tap on the Board: just two logos and two huge scores. */
    private void drawBigScore(Canvas c, Model.Game g, int w, int h, float top) {
        float H = h - top;
        float half = w / 2f;
        Model.Side[] sides = {g.away, g.home};
        for (int i = 0; i < 2; i++) {
            Model.Side s = sides[i];
            float cx = half * i + half / 2f;
            p.setShader(new LinearGradient(0, top, 0, h, alpha(s.color, 0x70), alpha(s.color, 0x08), Shader.TileMode.CLAMP));
            c.drawRect(half * i, top, half * (i + 1), h, p);
            p.setShader(null);
            logo(c, s.logo, s.abbr, s.color, cx, top + H * 0.2f, H * 0.26f);
            String sc = "pre".equals(g.state) || s.score.isEmpty() ? "–" : s.score;
            p.setShadowLayer(H * 0.02f, 0, H * 0.008f, 0xAA000000);
            text(c, sc, cx, top + H * 0.64f, Math.min(H * 0.55f, half * 0.6f), tfBlack, TEXT, Paint.Align.CENTER, half * 0.95f);
            p.clearShadowLayer();
        }
        p.setColor(HAIRLINE);
        c.drawRect(half - 1, top + H * 0.05f, half + 1, h - H * 0.05f, p);
        String d = g.isLive() ? g.detail : "pre".equals(g.state) ? "" : g.detail.toUpperCase();
        if (!d.isEmpty()) {
            float ts = H * 0.05f;
            p.setTypeface(tfCond);
            p.setTextSize(ts);
            float tw = p.measureText(d) + ts * 1.6f;
            RectF pill = new RectF(half - tw / 2, h - H * 0.1f - ts, half + tw / 2, h - H * 0.1f + ts);
            p.setColor(g.isLive() ? LIVE : 0xFF22252D);
            c.drawRoundRect(pill, ts, ts, p);
            text(c, d, half, pill.centerY(), ts, tfCond, TEXT, Paint.Align.CENTER, tw);
        }
    }

    private static int defaultPeriods(String sport) {
        switch (sport) {
            case "hockey": return 3;
            case "soccer": return 2;
            case "baseball": return 9;
            default: return 4;
        }
    }

    private static String periodLabel(String sport, int i) {
        int reg = defaultPeriods(sport);
        if (i < reg) return "soccer".equals(sport) ? (i == 0 ? "1H" : "2H") : String.valueOf(i + 1);
        if ("baseball".equals(sport)) return String.valueOf(i + 1);
        if ("hockey".equals(sport) && i == reg + 1) return "SO";
        int ot = i - reg + 1;
        return ot == 1 ? "OT" : ot + "OT";
    }

    private void drawLineScore(Canvas c, Model.Game g, float x, float y, float tw, float th) {
        int n = Math.max(defaultPeriods(g.sport), Math.max(g.away.lines.size(), g.home.lines.size()));
        RectF r = new RectF(x, y, x + tw, y + th);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF0C0E13);
        c.drawRoundRect(r, th * 0.12f, th * 0.12f, p);

        float rowH = th / 3f;
        float teamW = tw * 0.2f, totW = tw * 0.11f;
        float perW = (tw - teamW - totW) / n;
        float ts = Math.min(rowH * 0.5f, perW * 0.55f);
        int livePer = g.isLive() ? g.period - 1 : -1;
        int a = g.away.scoreInt(), hs = g.home.scoreInt();

        float hy = y + rowH / 2f;
        for (int i = 0; i < n; i++) {
            float px = x + teamW + perW * (i + 0.5f);
            String pl = periodLabel(g.sport, i);
            int col = i == livePer ? AMBER : (i >= defaultPeriods(g.sport) && !"baseball".equals(g.sport) ? LIVE : DIM);
            text(c, pl, px, hy, ts * 0.75f, tfCond, col, Paint.Align.CENTER, perW);
        }
        text(c, "T", x + tw - totW / 2f, hy, ts * 0.75f, tfCond, DIM, Paint.Align.CENTER, totW);
        p.setColor(HAIRLINE);
        c.drawRect(x + rowH * 0.3f, y + rowH - 1, x + tw - rowH * 0.3f, y + rowH, p);

        Model.Side[] sides = {g.away, g.home};
        for (int row = 0; row < 2; row++) {
            Model.Side s = sides[row];
            float cy = y + rowH * (row + 1.5f);
            float ls = rowH * 0.62f;
            logo(c, s.logo, s.abbr, s.color, x + rowH * 0.55f, cy, ls);
            text(c, star(s.abbr), x + rowH * 0.55f + ls * 0.75f, cy, ts, tfCond, TEXT, Paint.Align.LEFT, teamW - rowH);
            for (int i = 0; i < n; i++) {
                String v = i < s.lines.size() ? s.lines.get(i) : "";
                float px = x + teamW + perW * (i + 0.5f);
                text(c, v.isEmpty() ? "·" : v, px, cy, ts, tfMed, i == livePer ? AMBER : (v.isEmpty() ? DIM : 0xFFC9CDD3), Paint.Align.CENTER, perW);
            }
            String tot = "pre".equals(g.state) || s.score.isEmpty() ? "–" : s.score;
            boolean leads = !"pre".equals(g.state) && (row == 0 ? a > hs : hs > a);
            text(c, tot, x + tw - totW / 2f, cy, ts * 1.1f, tfBlack, leads ? AMBER : TEXT, Paint.Align.CENTER, totW);
        }
        p.setColor(HAIRLINE);
        c.drawRect(x + tw - totW, y + rowH * 0.2f, x + tw - totW + 1, y + th - rowH * 0.2f, p);
    }


    // ------------------------------------------------------------------ baseball
    /** Live baseball: compact scoreboard on top, the field with runners/batter/pitcher, and at-bat info. */
    private void drawBaseballBoard(Canvas c, Model.Game g, int w, int h, float top) {
        float H = h - top, gap = w * 0.016f;
        float topH = H * 0.27f;

        // --- compact scoreboard: [away] [inning + count] [home]
        float pw = w * 0.34f;
        Model.Side[] sides = {g.away, g.home};
        for (int i = 0; i < 2; i++) {
            Model.Side s = sides[i];
            float x = i == 0 ? gap : w - gap - pw;
            RectF r = new RectF(x, top, x + pw, top + topH);
            p.setShader(new LinearGradient(0, top, 0, top + topH, alpha(s.color, 0x90), alpha(s.color, 0x18), Shader.TileMode.CLAMP));
            c.drawRoundRect(r, topH * 0.15f, topH * 0.15f, p);
            p.setShader(null);
            hitRects.add(r);
            hitAbbrs.add(new String[]{s.abbr, s.abbr});
            float ls = topH * 0.62f;
            boolean left = i == 0;
            float lx = left ? r.left + gap + ls / 2 : r.right - gap - ls / 2;
            logo(c, s.logo, s.abbr, s.color, lx, r.centerY() - topH * 0.06f, ls);
            text(c, star(s.abbr), lx, r.bottom - topH * 0.11f, topH * 0.13f, tfCond, TEXT, Paint.Align.CENTER, ls * 1.4f);
            String sc = s.score.isEmpty() ? "0" : s.score;
            Long pa = popAt.get(g.id + ":" + (left ? "away" : "home"));
            float size = topH * 0.62f;
            int col = TEXT;
            if (pa != null && System.currentTimeMillis() - pa < 900) { size *= 1.2f; col = AMBER; postInvalidateOnAnimation(); }
            text(c, sc, left ? r.right - gap * 1.5f : r.left + gap * 1.5f, r.centerY(), size, tfBlack, col,
                left ? Paint.Align.RIGHT : Paint.Align.LEFT, pw * 0.5f);
        }
        // center: inning + B/S/O lights
        float cx = w / 2f, cw = w - 4 * gap - 2 * pw;
        RectF box = new RectF(cx - cw / 2, top, cx + cw / 2, top + topH);
        p.setColor(0xFF06070A);
        c.drawRoundRect(box, topH * 0.15f, topH * 0.15f, p);
        String inn = g.detail.toUpperCase();
        boolean topHalf = inn.startsWith("TOP"), botHalf = inn.startsWith("BOT");
        String innNum = inn.replaceAll("^(TOP|BOTTOM|BOT|MIDDLE|MID|END)\\s*", "");
        p.setShadowLayer(topH * 0.05f, 0, 0, alpha(AMBER, 0x99));
        text(c, (topHalf ? "▲ " : botHalf ? "▼ " : "") + innNum, cx, box.top + topH * 0.27f, topH * 0.22f, tfBlack, AMBER, Paint.Align.CENTER, cw * 0.95f);
        p.clearShadowLayer();
        if (!topHalf && !botHalf) text(c, inn.split(" ")[0], cx, box.top + topH * 0.47f, topH * 0.1f, tfCond, MUTED, Paint.Align.CENTER, cw);
        String[] lab = {"B", "S", "O"};
        int[] val = {Math.max(0, g.balls), Math.max(0, g.strikes), Math.max(0, g.outs)};
        int[] max = {4, 3, 3};
        int[] on = {GREEN, AMBER, LIVE};
        float dotR = topH * 0.045f;
        for (int k = 0; k < 3; k++) {
            float ry = box.top + topH * (0.6f + k * 0.13f);
            float startX = cx - dotR * 6.5f;
            text(c, lab[k], startX - dotR * 2.8f, ry, topH * 0.1f, tfCond, MUTED, Paint.Align.CENTER, dotR * 3);
            for (int d = 0; d < max[k]; d++) {
                p.setColor(d < val[k] ? on[k] : 0xFF22252D);
                c.drawCircle(startX + d * dotR * 2.8f, ry, dotR, p);
            }
        }

        // --- field (left) + at-bat info (right)
        float fTop = top + topH + gap, fBottom = top + H * 0.865f;
        float fh = fBottom - fTop, fw = w * 0.5f;
        drawField(c, g, gap, fTop, fw, fh);
        float ix = gap * 2 + fw, iw = w - ix - gap;
        drawAtBat(c, g, ix, fTop, iw, fh);

        if (g == demoGame) tag(c, tr("DEMO · tap to close", "DÉMO · touchez pour fermer"), cx, top + topH + gap * 0.2f, topH * 0.08f, Paint.Align.CENTER, LIVE, TEXT);

        // --- ticker
        float kTop = fBottom + gap * 0.8f, kBottom = h - gap * 0.8f, kh = kBottom - kTop;
        RectF tick = new RectF(gap, kTop, w - gap - kh - gap * 0.6f, kBottom);
        p.setColor(0xFF12141A);
        c.drawRoundRect(tick, kh * 0.3f, kh * 0.3f, p);
        soundRect.set(tick.right + gap * 0.6f, kTop, w - gap, kBottom);
        c.drawRoundRect(soundRect, kh * 0.3f, kh * 0.3f, p);
        drawSpeaker(c, soundRect, sound);
        String msg = g.lastPlay.isEmpty() ? tr("Waiting for the next pitch…", "En attente du prochain lancer…") : g.lastPlay;
        text(c, tr("LAST PLAY", "DERNIER JEU"), tick.left + kh * 0.4f, tick.centerY(), kh * 0.3f, tfCond, AMBER, Paint.Align.LEFT, kh * 3);
        text(c, msg, tick.left + kh * 3.2f, tick.centerY(), kh * 0.36f, tfReg, TEXT, Paint.Align.LEFT, tick.width() - kh * 3.5f);
    }

    /** The diamond: outfield grass, infield dirt, lit bases with runner names, batter and pitcher. */
    private void drawField(Canvas c, Model.Game g, float x, float y, float fw, float fh) {
        RectF frame = new RectF(x, y, x + fw, y + fh);
        p.setColor(0xFF0A0F0B);
        c.drawRoundRect(frame, fh * 0.06f, fh * 0.06f, p);
        c.save();
        c.clipRect(frame);

        float hx = x + fw / 2f, hy = y + fh * 0.93f;          // home plate
        float side = Math.min(fw * 0.3f, fh * 0.44f);         // base-to-base distance on screen
        tagBounds.set(frame.left + fh * 0.03f, frame.top, frame.right - fh * 0.03f, frame.bottom);
        float d = side * 0.7071f;
        float[] first = {hx + d, hy - d}, second = {hx, hy - 2 * d}, third = {hx - d, hy - d};

        // outfield fan
        float R = side * 2.15f;
        Path fan = new Path();
        fan.moveTo(hx, hy);
        fan.arcTo(new RectF(hx - R, hy - R, hx + R, hy + R), 225, 90);
        fan.close();
        p.setColor(0xFF12301B);
        c.drawPath(fan, p);
        // mowing stripes
        p.setColor(0x14FFFFFF);
        for (int i = 1; i < 6; i += 2) {
            float r1 = R * i / 6f, r2 = R * (i + 1) / 6f;
            Path ring = new Path();
            ring.arcTo(new RectF(hx - r2, hy - r2, hx + r2, hy + r2), 225, 90, true);
            ring.arcTo(new RectF(hx - r1, hy - r1, hx + r1, hy + r1), 315, -90);
            ring.close();
            c.drawPath(ring, p);
        }
        // infield dirt
        float pad = side * 0.28f;
        Path dirt = new Path();
        dirt.moveTo(hx, hy + pad * 0.5f);
        dirt.lineTo(first[0] + pad, first[1]);
        dirt.lineTo(second[0], second[1] - pad);
        dirt.lineTo(third[0] - pad, third[1]);
        dirt.close();
        p.setColor(0xFF4A3524);
        c.drawPath(dirt, p);
        // infield grass
        float ig = side * 0.62f;
        Path grass = new Path();
        float gcx = hx, gcy = hy - d;
        grass.moveTo(gcx, gcy + ig * 0.7071f);
        grass.lineTo(gcx + ig * 0.7071f, gcy);
        grass.lineTo(gcx, gcy - ig * 0.7071f);
        grass.lineTo(gcx - ig * 0.7071f, gcy);
        grass.close();
        p.setColor(0xFF1A4026);
        c.drawPath(grass, p);
        // foul lines
        p.setColor(0x99FFFFFF);
        p.setStrokeWidth(Math.max(2f, side * 0.012f));
        p.setStyle(Paint.Style.STROKE);
        c.drawLine(hx, hy, hx + R * 0.7071f, hy - R * 0.7071f, p);
        c.drawLine(hx, hy, hx - R * 0.7071f, hy - R * 0.7071f, p);
        p.setStyle(Paint.Style.FILL);

        // mound + pitcher
        p.setColor(0xFF5A4030);
        c.drawCircle(gcx, gcy, side * 0.09f, p);
        p.setColor(0xFFE8E8E8);
        c.drawRect(gcx - side * 0.04f, gcy - side * 0.01f, gcx + side * 0.04f, gcy + side * 0.01f, p);

        // bases
        float bs = side * 0.075f;
        float[][] bases = {first, second, third};
        for (int i = 0; i < 3; i++) {
            c.save();
            c.rotate(45, bases[i][0], bases[i][1]);
            if (g.bases[i]) {
                p.setShadowLayer(bs * 1.6f, 0, 0, AMBER);
                p.setColor(AMBER);
            } else p.setColor(0xFFE8E8E8);
            c.drawRect(bases[i][0] - bs, bases[i][1] - bs, bases[i][0] + bs, bases[i][1] + bs, p);
            p.clearShadowLayer();
            c.restore();
        }
        // home plate
        Path plate = new Path();
        plate.moveTo(hx - bs, hy - bs);
        plate.lineTo(hx + bs, hy - bs);
        plate.lineTo(hx + bs, hy);
        plate.lineTo(hx, hy + bs);
        plate.lineTo(hx - bs, hy);
        plate.close();
        p.setColor(0xFFE8E8E8);
        c.drawPath(plate, p);

        // name tags
        float ts = Math.max(fh * 0.055f, 14f);
        for (int i = 0; i < 3; i++) {
            if (!g.bases[i]) continue;
            String who = g.runners[i].isEmpty() ? tr("RUNNER", "COUREUR") : g.runners[i];
            float tx = bases[i][0] + (i == 0 ? bs * 1.8f : i == 2 ? -bs * 1.8f : 0);
            float ty = bases[i][1] + (i == 1 ? -bs * 3.2f : -bs * 3.4f);
            tag(c, who, tx, ty, ts, i == 0 ? Paint.Align.LEFT : i == 2 ? Paint.Align.RIGHT : Paint.Align.CENTER, AMBER, 0xFF000000);
        }
        if (!g.pitcher.isEmpty()) tag(c, "P  " + g.pitcher, gcx, gcy + side * 0.2f, ts * 0.85f, Paint.Align.CENTER, 0xDD101218, TEXT);
        if (!g.batter.isEmpty()) tag(c, "AB  " + g.batter, hx + bs * 2.2f, hy - bs * 0.5f, ts * 0.85f, Paint.Align.LEFT, 0xDD101218, TEXT);
        c.restore();
    }

    private final RectF tagBounds = new RectF();

    /** Clean speaker icon (sound on / muted) instead of an emoji. */
    private void drawSpeaker(Canvas c, RectF r, boolean on) {
        float s = Math.min(r.width(), r.height()) * 0.42f, cx = r.centerX() - s * 0.15f, cy = r.centerY();
        Path sp = new Path();
        sp.moveTo(cx - s * 0.5f, cy - s * 0.18f);
        sp.lineTo(cx - s * 0.22f, cy - s * 0.18f);
        sp.lineTo(cx + s * 0.08f, cy - s * 0.45f);
        sp.lineTo(cx + s * 0.08f, cy + s * 0.45f);
        sp.lineTo(cx - s * 0.22f, cy + s * 0.18f);
        sp.lineTo(cx - s * 0.5f, cy + s * 0.18f);
        sp.close();
        p.setColor(on ? TEXT : DIM);
        c.drawPath(sp, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, s * 0.09f));
        p.setStrokeCap(Paint.Cap.ROUND);
        if (on) {
            c.drawArc(new RectF(cx - s * 0.1f, cy - s * 0.3f, cx + s * 0.4f, cy + s * 0.3f), -50, 100, false, p);
            c.drawArc(new RectF(cx - s * 0.15f, cy - s * 0.55f, cx + s * 0.65f, cy + s * 0.55f), -50, 100, false, p);
        } else {
            p.setColor(LIVE);
            c.drawLine(cx + s * 0.25f, cy - s * 0.2f, cx + s * 0.6f, cy + s * 0.2f, p);
            c.drawLine(cx + s * 0.25f, cy + s * 0.2f, cx + s * 0.6f, cy - s * 0.2f, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    /** Small pill with text, anchored left / center / right of (x, cy), kept inside the field. */
    private void tag(Canvas c, String s, float x, float cy, float ts, Paint.Align a, int bg, int fg) {
        p.setTypeface(tfCond);
        p.setTextSize(ts);
        float tw = Math.min(p.measureText(s) + ts * 0.9f, tagBounds.width()), th = ts * 1.45f;
        float left = a == Paint.Align.LEFT ? x : a == Paint.Align.RIGHT ? x - tw : x - tw / 2;
        left = Math.max(tagBounds.left, Math.min(left, tagBounds.right - tw));
        RectF r = new RectF(left, cy - th / 2, left + tw, cy + th / 2);
        p.setColor(bg);
        c.drawRoundRect(r, th / 2, th / 2, p);
        text(c, s, r.centerX(), cy, ts, tfCond, fg, Paint.Align.CENTER, tw);
    }

    /** Right side: line score, batter and pitcher, win chances. */
    private void drawAtBat(Canvas c, Model.Game g, float x, float y, float iw, float ih) {
        float lsH = ih * 0.42f;
        drawLineScore(c, g, x, y, iw, lsH);
        float y2 = y + lsH + ih * 0.04f;
        float cardH = ih * 0.25f;
        String[][] rows = {
            {tr("AT BAT", "AU BÂTON"), g.batter.isEmpty() ? "—" : g.batter, g.batterInfo},
            {tr("PITCHING", "LANCEUR"), g.pitcher.isEmpty() ? "—" : g.pitcher, g.pitcherInfo},
        };
        for (int i = 0; i < 2; i++) {
            RectF r = new RectF(x, y2 + i * (cardH + ih * 0.03f), x + iw, y2 + i * (cardH + ih * 0.03f) + cardH);
            p.setColor(0xFF0C0E13);
            c.drawRoundRect(r, cardH * 0.25f, cardH * 0.25f, p);
            float pad = cardH * 0.3f;
            text(c, rows[i][0], r.left + pad, r.top + cardH * 0.3f, cardH * 0.22f, tfCond, AMBER, Paint.Align.LEFT, iw * 0.4f);
            text(c, rows[i][1], r.left + pad, r.top + cardH * 0.68f, cardH * 0.33f, tfBlack, TEXT, Paint.Align.LEFT, iw - pad * 2);
            if (!rows[i][2].isEmpty())   // stats on the label line, so long names never collide
                text(c, rows[i][2], r.right - pad, r.top + cardH * 0.3f, cardH * 0.22f, tfMed, MUTED, Paint.Align.RIGHT, iw * 0.5f);
        }
        if (g.away.odds >= 0) {
            float oy = y2 + 2 * cardH + ih * 0.1f;
            if (oy + ih * 0.06f < y + ih) drawOddsBar(c, g, x + iw * 0.05f, oy, iw * 0.9f, ih * 0.05f, true);
        }
    }


    // ------------------------------------------------------------------ football
    private final Map<String, float[]> ballAnim = new HashMap<>();   // gameId -> {fromYard, toYard, startMs}

    /** Smoothly animated ball position (in yards 0..100). */
    private float animatedYard(Model.Game g) {
        float[] a = ballAnim.get(g.id);
        long now = System.currentTimeMillis();
        if (a == null) { a = new float[]{g.ballYard, g.ballYard, 0}; ballAnim.put(g.id, a); }
        if (a[1] != g.ballYard) {   // ball moved: glide from where it is now
            float cur = curYard(a, now);
            a[0] = cur; a[1] = g.ballYard; a[2] = now % 100_000_000L;
        }
        return curYard(a, now);
    }

    private static float curYard(float[] a, long now) {
        float t = Math.min(1f, ((now % 100_000_000L) - a[2]) / 1100f);
        if (t < 0) t = 1f;
        float e = t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;   // ease in-out
        return a[0] + (a[1] - a[0]) * e;
    }

    private void drawFootballBoard(Canvas c, Model.Game g, int w, int h, float top) {
        float H = h - top, gap = w * 0.016f;
        float topH = H * 0.24f;

        // --- compact scoreboard: [away] [quarter · clock · down & distance] [home]
        float pw = w * 0.33f;
        Model.Side[] sides = {g.away, g.home};
        for (int i = 0; i < 2; i++) {
            Model.Side s = sides[i];
            boolean left = i == 0;
            float x = left ? gap : w - gap - pw;
            RectF r = new RectF(x, top, x + pw, top + topH);
            p.setShader(new LinearGradient(0, top, 0, top + topH, alpha(s.color, 0x90), alpha(s.color, 0x18), Shader.TileMode.CLAMP));
            c.drawRoundRect(r, topH * 0.15f, topH * 0.15f, p);
            p.setShader(null);
            boolean hasBall = (left ? "away" : "home").equals(g.possession);
            if (hasBall) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(2f, topH * 0.02f));
                p.setColor(0xFFFFD60A);
                c.drawRoundRect(r, topH * 0.15f, topH * 0.15f, p);
                p.setStyle(Paint.Style.FILL);
            }
            hitRects.add(r);
            hitAbbrs.add(new String[]{s.abbr, s.abbr});
            float ls = topH * 0.6f;
            float lx = left ? r.left + gap + ls / 2 : r.right - gap - ls / 2;
            logo(c, s.logo, s.abbr, s.color, lx, r.centerY() - topH * 0.08f, ls);
            text(c, (hasBall ? "▶ " : "") + star(s.abbr), lx, r.bottom - topH * 0.12f, topH * 0.13f, tfCond, TEXT, Paint.Align.CENTER, ls * 1.8f);
            // timeouts
            int to = left ? g.awayTimeouts : g.homeTimeouts;
            if (to >= 0) {
                float tw = topH * 0.1f, tx0 = left ? r.right - gap * 1.5f - tw * 3.6f : r.left + gap * 1.5f;
                for (int k = 0; k < 3; k++) {
                    p.setColor(k < to ? 0xFFFFD60A : 0x33FFFFFF);
                    c.drawRoundRect(new RectF(tx0 + k * tw * 1.3f, r.bottom - topH * 0.16f, tx0 + k * tw * 1.3f + tw, r.bottom - topH * 0.11f), 3, 3, p);
                }
            }
            String sc = s.score.isEmpty() ? "0" : s.score;
            Long pa = popAt.get(g.id + ":" + (left ? "away" : "home"));
            float size = topH * 0.58f;
            int col = TEXT;
            if (pa != null && System.currentTimeMillis() - pa < 900) { size *= 1.2f; col = AMBER; postInvalidateOnAnimation(); }
            text(c, sc, left ? r.right - gap * 1.5f : r.left + gap * 1.5f, r.centerY() - topH * 0.06f, size, tfBlack, col,
                left ? Paint.Align.RIGHT : Paint.Align.LEFT, pw * 0.45f);
        }
        float cx = w / 2f, cw = w - 4 * gap - 2 * pw;
        RectF box = new RectF(cx - cw / 2, top, cx + cw / 2, top + topH);
        p.setColor(0xFF06070A);
        c.drawRoundRect(box, topH * 0.15f, topH * 0.15f, p);
        String[] parts = g.detail.split(" - ");
        String clock = parts.length == 2 ? parts[0] : g.detail, qtr = parts.length == 2 ? parts[1].toUpperCase() : "";
        text(c, qtr, cx, box.top + topH * 0.17f, topH * 0.11f, tfCond, MUTED, Paint.Align.CENTER, cw);
        p.setShadowLayer(topH * 0.05f, 0, 0, alpha(AMBER, 0x99));
        text(c, clock, cx, box.top + topH * 0.42f, topH * 0.25f, tfBlack, AMBER, Paint.Align.CENTER, cw * 0.95f);
        p.clearShadowLayer();
        String dd = g.down > 0 ? g.down + ordinalSuffix(g.down) + " & " + (g.toGo == 0 ? tr("GOAL", "BUT") : String.valueOf(g.toGo)) : "";
        if (!dd.isEmpty()) {
            float pillH = topH * 0.22f;
            p.setTypeface(tfBlack);
            p.setTextSize(pillH * 0.6f);
            float tw = p.measureText(dd) + pillH;
            RectF pill = new RectF(cx - tw / 2, box.top + topH * 0.64f, cx + tw / 2, box.top + topH * 0.64f + pillH);
            p.setColor(g.redZone ? LIVE : 0xFFFFD60A);
            c.drawRoundRect(pill, pillH / 2, pillH / 2, p);
            text(c, dd, pill.centerX(), pill.centerY(), pillH * 0.6f, tfBlack, g.redZone ? TEXT : 0xFF000000, Paint.Align.CENTER, tw);
            if (!g.possessionText.isEmpty())
                text(c, "@ " + g.possessionText, cx, pill.bottom + topH * 0.07f, topH * 0.09f, tfCond, MUTED, Paint.Align.CENTER, cw);
        }

        // --- the field
        float fTop = top + topH + gap, fH = H * 0.36f;
        drawFootballField(c, g, gap, fTop, w - 2 * gap, fH);

        // --- line score + top players, then the ticker
        float tTop = fTop + fH + gap, tBottom = top + H * 0.865f;
        boolean side = !g.away.leader.isEmpty() || !g.home.leader.isEmpty();
        float tableW = side ? (w - 3 * gap) * 0.6f : w - 2 * gap;
        drawLineScore(c, g, gap, tTop, tableW, tBottom - tTop);
        if (side) drawScorers(c, g, gap * 2 + tableW, tTop, w - gap * 3 - tableW, tBottom - tTop);

        float kTop = tBottom + gap * 0.8f, kBottom = h - gap * 0.8f, kh = kBottom - kTop;
        RectF tick = new RectF(gap, kTop, w - gap - kh - gap * 0.6f, kBottom);
        p.setColor(0xFF12141A);
        c.drawRoundRect(tick, kh * 0.3f, kh * 0.3f, p);
        soundRect.set(tick.right + gap * 0.6f, kTop, w - gap, kBottom);
        c.drawRoundRect(soundRect, kh * 0.3f, kh * 0.3f, p);
        drawSpeaker(c, soundRect, sound);
        String msg = g.lastPlay.isEmpty() ? g.downText : g.lastPlay;
        text(c, tr("LAST PLAY", "DERNIER JEU"), tick.left + kh * 0.4f, tick.centerY(), kh * 0.3f, tfCond, AMBER, Paint.Align.LEFT, kh * 3);
        text(c, msg, tick.left + kh * 3.2f, tick.centerY(), kh * 0.36f, tfReg, TEXT, Paint.Align.LEFT, tick.width() - kh * 3.5f);
        if (g == demoGame) tag(c, tr("DEMO · tap to close", "DÉMO · touchez pour fermer"), cx, fTop - gap * 0.5f, topH * 0.08f, Paint.Align.CENTER, LIVE, TEXT);
    }

    private static String ordinalSuffix(int n) { return n == 1 ? "ST" : n == 2 ? "ND" : n == 3 ? "RD" : "TH"; }

    /** Horizontal field: away end zone on the left, home on the right, animated ball, scrimmage + first-down lines. */
    private void drawFootballField(Canvas c, Model.Game g, float x, float y, float fw, float fh) {
        RectF field = new RectF(x, y, x + fw, y + fh);
        tagBounds.set(field);
        c.save();
        c.clipRect(field);
        float ez = fw / 12f;                                   // 10-yard end zones on a 120-yard field
        float yd = (fw - 2 * ez) / 100f;
        // turf stripes every 10 yards
        for (int i = 0; i < 10; i++) {
            p.setColor(i % 2 == 0 ? 0xFF14472A : 0xFF175131);
            c.drawRect(x + ez + i * 10 * yd, y, x + ez + (i + 1) * 10 * yd, y + fh, p);
        }
        // end zones in team colors
        Model.Side[] sides = {g.away, g.home};
        for (int i = 0; i < 2; i++) {
            float ex = i == 0 ? x : x + fw - ez;
            p.setColor(alpha(sides[i].color, 0xE0));
            c.drawRect(ex, y, ex + ez, y + fh, p);
            c.save();
            c.rotate(i == 0 ? -90 : 90, ex + ez / 2, y + fh / 2);
            text(c, sides[i].abbr, ex + ez / 2, y + fh / 2, ez * 0.5f, tfBlack, alpha(TEXT, 0xCC), Paint.Align.CENTER, fh * 0.8f);
            c.restore();
        }
        // red zone glow (inside the defense's 20)
        float ball = g.ballYard >= 0 ? animatedYard(g) : -1;
        if (g.redZone && !g.possession.isEmpty()) {
            boolean towardLeft = "home".equals(g.possession);
            float zx = towardLeft ? x + ez : x + ez + 80 * yd;
            float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 250.0));
            p.setColor(alpha(LIVE, (int) (40 + 50 * pulse)));
            c.drawRect(zx, y, zx + 20 * yd, y + fh, p);
        }
        // yard lines + numbers
        p.setColor(0x66FFFFFF);
        for (int i = 0; i <= 100; i += 5) {
            float lx = x + ez + i * yd;
            c.drawRect(lx - (i % 10 == 0 ? 1.5f : 0.75f), y, lx + (i % 10 == 0 ? 1.5f : 0.75f), y + fh, p);
        }
        for (int i = 10; i <= 90; i += 10) {
            float lx = x + ez + i * yd;
            String n = String.valueOf(i <= 50 ? i : 100 - i);
            text(c, n, lx, y + fh * 0.13f, fh * 0.1f, tfBlack, 0x88FFFFFF, Paint.Align.CENTER, yd * 9);
            text(c, n, lx, y + fh * 0.87f, fh * 0.1f, tfBlack, 0x88FFFFFF, Paint.Align.CENTER, yd * 9);
        }
        // hash marks
        p.setColor(0x44FFFFFF);
        for (int i = 1; i < 100; i++) {
            float lx = x + ez + i * yd;
            c.drawRect(lx - 0.6f, y + fh * 0.36f, lx + 0.6f, y + fh * 0.4f, p);
            c.drawRect(lx - 0.6f, y + fh * 0.6f, lx + 0.6f, y + fh * 0.64f, p);
        }

        if (ball < 0) {
            text(c, g.downText.isEmpty() ? tr("Waiting for the snap…", "En attente de la remise…") : g.downText,
                x + fw / 2, y + fh / 2, fh * 0.09f, tfCond, TEXT, Paint.Align.CENTER, fw * 0.8f);
            c.restore();
            return;
        }
        float bx = x + ez + ball * yd, cy = y + fh / 2;
        int dir = "home".equals(g.possession) ? -1 : "away".equals(g.possession) ? 1 : 0;
        Model.Side off = dir < 0 ? g.home : g.away;

        // first-down line (yellow) and line of scrimmage (blue)
        if (g.toGo > 0 && dir != 0) {
            float fdx = x + ez + Math.max(0, Math.min(100, g.ballYard + dir * g.toGo)) * yd;
            p.setColor(0xFFFFD60A);
            c.drawRect(fdx - 2.5f, y, fdx + 2.5f, y + fh, p);
        }
        p.setColor(0xFF4DA3FF);
        c.drawRect(bx - 2.5f, y, bx + 2.5f, y + fh, p);

        // drive arrows moving toward the end zone being attacked
        if (dir != 0) {
            long t = System.currentTimeMillis();
            float step = yd * 4.5f;
            for (int k = 0; k < 4; k++) {
                float phase = ((t / 600f) + k / 4f) % 1f;
                float ax = bx + dir * (yd * 3 + phase * step * 4);
                int a = (int) (200 * (1 - phase));
                p.setColor(alpha(off.color | 0xFF000000, a));
                Path ch = new Path();
                float s2 = fh * 0.07f;
                ch.moveTo(ax - dir * s2, cy - s2 * 1.4f - fh * 0.22f);
                ch.lineTo(ax + dir * s2 * 0.4f, cy - fh * 0.22f);
                ch.lineTo(ax - dir * s2, cy + s2 * 1.4f - fh * 0.22f);
                ch.close();
                c.drawPath(ch, p);
            }
        }

        // the ball: shadow, leather, laces — bobbing gently
        float bob = (float) Math.sin(System.currentTimeMillis() / 300.0) * fh * 0.025f;
        float bw = fh * 0.2f, bhh = fh * 0.12f;
        p.setColor(0x66000000);
        c.drawOval(new RectF(bx - bw * 0.45f, cy + bhh * 0.9f, bx + bw * 0.45f, cy + bhh * 1.2f), p);
        c.save();
        c.rotate(dir >= 0 ? -15 : 15, bx, cy + bob);
        RectF leather = new RectF(bx - bw / 2, cy + bob - bhh / 2, bx + bw / 2, cy + bob + bhh / 2);
        p.setShadowLayer(bhh * 0.5f, 0, 0, 0x99FFD60A);
        p.setColor(0xFF8B4A1C);
        c.drawOval(leather, p);
        p.clearShadowLayer();
        p.setColor(0xFFF4F4F4);
        c.drawRect(bx - bw * 0.2f, cy + bob - bhh * 0.06f, bx + bw * 0.2f, cy + bob + bhh * 0.06f, p);
        for (int k = -2; k <= 2; k++) c.drawRect(bx + k * bw * 0.08f - 1, cy + bob - bhh * 0.16f, bx + k * bw * 0.08f + 1, cy + bob + bhh * 0.16f, p);
        p.setColor(0xFFF4F4F4);
        c.drawRect(bx - bw * 0.42f, cy + bob - bhh * 0.32f, bx - bw * 0.36f, cy + bob + bhh * 0.32f, p);
        c.drawRect(bx + bw * 0.36f, cy + bob - bhh * 0.32f, bx + bw * 0.42f, cy + bob + bhh * 0.32f, p);
        c.restore();

        // spot label
        if (!g.possessionText.isEmpty())
            tag(c, g.possessionText, bx, y + fh * 0.75f, fh * 0.07f, Paint.Align.CENTER, 0xDD101218, TEXT);
        c.restore();
        postInvalidateDelayed(40);   // keep the ball moving
    }


    // ------------------------------------------------------------------ hockey
    private final Map<String, Integer> shotCounts = new HashMap<>();
    private final Map<String, Long> newShotAt = new HashMap<>();

    /** "1451" -> {awayGoalie, awaySkaters, homeSkaters, homeGoalie} */
    private static int[] situation(String code) {
        if (code == null || code.length() != 4) return null;
        try {
            return new int[]{code.charAt(0) - '0', code.charAt(1) - '0', code.charAt(2) - '0', code.charAt(3) - '0'};
        } catch (Exception e) { return null; }
    }

    /** Live NHL: compact scoreboard + the ice with every shot and goal of the game. */
    private void drawHockeyBoard(Canvas c, Model.Game g, int w, int h, float top) {
        float H = h - top, gap = w * 0.016f;
        float topH = H * 0.22f;

        // new shot? (for the pop animation)
        Integer before = shotCounts.put(g.id, g.shots.size());
        if (before != null && g.shots.size() > before) newShotAt.put(g.id, System.currentTimeMillis());

        // --- compact scoreboard
        float pw = w * 0.33f;
        Model.Side[] sides = {g.away, g.home};
        int[] sit = situation(g.situationCode);
        for (int i = 0; i < 2; i++) {
            Model.Side s = sides[i];
            boolean left = i == 0;
            float x = left ? gap : w - gap - pw;
            RectF r = new RectF(x, top, x + pw, top + topH);
            p.setShader(new LinearGradient(0, top, 0, top + topH, alpha(s.color, 0x90), alpha(s.color, 0x18), Shader.TileMode.CLAMP));
            c.drawRoundRect(r, topH * 0.15f, topH * 0.15f, p);
            p.setShader(null);
            hitRects.add(r);
            hitAbbrs.add(new String[]{s.abbr, s.abbr});
            float ls = topH * 0.62f;
            float lx = left ? r.left + gap + ls / 2 : r.right - gap - ls / 2;
            logo(c, s.logo, s.abbr, s.color, lx, r.centerY() - topH * 0.08f, ls);
            text(c, star(s.abbr), lx, r.bottom - topH * 0.12f, topH * 0.13f, tfCond, TEXT, Paint.Align.CENTER, ls * 1.8f);
            String sc = s.score.isEmpty() ? "0" : s.score;
            Long pa = popAt.get(g.id + ":" + (left ? "away" : "home"));
            float size = topH * 0.6f;
            int col = g.isFinal() && !s.winner && (g.away.winner || g.home.winner) ? DIM : TEXT;
            if (pa != null && System.currentTimeMillis() - pa < 900) { size *= 1.2f; col = AMBER; postInvalidateOnAnimation(); }
            float sx = left ? r.right - gap * 1.5f : r.left + gap * 1.5f;
            text(c, sc, sx, r.centerY() - topH * 0.08f, size, tfBlack, col, left ? Paint.Align.RIGHT : Paint.Align.LEFT, pw * 0.45f);
            if (s.shots >= 0)
                text(c, tr("SOG ", "TIRS ") + s.shots, sx, r.bottom - topH * 0.12f, topH * 0.11f, tfCond, MUTED, left ? Paint.Align.RIGHT : Paint.Align.LEFT, pw * 0.4f);
        }
        float cx = w / 2f, cw = w - 4 * gap - 2 * pw;
        RectF box = new RectF(cx - cw / 2, top, cx + cw / 2, top + topH);
        p.setColor(0xFF06070A);
        c.drawRoundRect(box, topH * 0.15f, topH * 0.15f, p);
        String[] parts = g.detail.split(" - ");
        String clock = parts.length == 2 ? parts[0] : g.detail.toUpperCase(), per = parts.length == 2 ? parts[1].toUpperCase() : "";
        text(c, per, cx, box.top + topH * 0.18f, topH * 0.11f, tfCond, MUTED, Paint.Align.CENTER, cw);
        p.setShadowLayer(topH * 0.05f, 0, 0, alpha(AMBER, 0x99));
        text(c, clock, cx, box.top + topH * 0.45f, topH * 0.26f, tfBlack, g.isFinal() ? TEXT : AMBER, Paint.Align.CENTER, cw * 0.95f);
        p.clearShadowLayer();
        // situation pill: power play / empty net / even strength
        if (sit != null && g.isLive()) {
            String label;
            int bg;
            if (sit[0] == 0 || sit[3] == 0) {
                label = tr("EMPTY NET · ", "FILET DÉSERT · ") + (sit[0] == 0 ? g.away.abbr : g.home.abbr);
                bg = LIVE;
            } else if (sit[1] != sit[2]) {
                boolean homePP = sit[2] > sit[1];
                label = tr("POWER PLAY ", "AV. NUMÉRIQUE ") + (homePP ? g.home.abbr : g.away.abbr) + " " + Math.max(sit[1], sit[2]) + "v" + Math.min(sit[1], sit[2]);
                bg = 0xFFFFB524;
            } else { label = sit[1] + " ON " + sit[2]; bg = 0xFF22252D; }
            float pillH = topH * 0.2f;
            p.setTypeface(tfBlack);
            p.setTextSize(pillH * 0.52f);
            float tw = Math.min(cw * 0.95f, p.measureText(label) + pillH);
            RectF pill = new RectF(cx - tw / 2, box.top + topH * 0.68f, cx + tw / 2, box.top + topH * 0.68f + pillH);
            if (bg != 0xFF22252D) {
                float pulse = (float) (0.6 + 0.4 * Math.sin(System.currentTimeMillis() / 220.0));
                p.setShadowLayer(pillH * 0.5f * pulse, 0, 0, bg);
                postInvalidateDelayed(50);
            }
            p.setColor(bg);
            c.drawRoundRect(pill, pillH / 2, pillH / 2, p);
            p.clearShadowLayer();
            text(c, label, pill.centerX(), pill.centerY(), pillH * 0.52f, tfBlack, bg == 0xFF22252D ? MUTED : 0xFF000000, Paint.Align.CENTER, tw);
        }

        // --- the rink (keeps the real 200 x 85 ft shape)
        float availTop = top + topH + gap * 2.2f, availBottom = top + H * 0.87f;
        float rw = w - 2 * gap, rh = rw * 85f / 200f;
        if (rh > availBottom - availTop) { rh = availBottom - availTop; rw = rh * 200f / 85f; }
        RectF rink = new RectF(cx - rw / 2, availTop + (availBottom - availTop - rh) / 2, cx + rw / 2, availTop + (availBottom - availTop - rh) / 2 + rh);
        drawRink(c, g, rink);

        // --- ticker: latest event
        float kTop = availBottom + gap * 0.6f, kBottom = h - gap * 0.8f, kh = kBottom - kTop;
        RectF tick = new RectF(gap, kTop, w - gap - kh - gap * 0.6f, kBottom);
        p.setColor(0xFF12141A);
        c.drawRoundRect(tick, kh * 0.3f, kh * 0.3f, p);
        soundRect.set(tick.right + gap * 0.6f, kTop, w - gap, kBottom);
        c.drawRoundRect(soundRect, kh * 0.3f, kh * 0.3f, p);
        drawSpeaker(c, soundRect, sound);
        Model.Shot last = g.shots.isEmpty() ? null : g.shots.get(g.shots.size() - 1);
        String tagTxt, msg;
        if (last != null) {
            tagTxt = last.goal ? tr("GOAL", "BUT") : last.missed ? tr("MISSED", "RATÉ") : tr("SHOT", "TIR");
            msg = (last.who.isEmpty() ? "" : last.who + " ") + "(" + (last.home ? g.home.abbr : g.away.abbr) + ")  ·  " + last.time;
        } else { tagTxt = tr("LAST PLAY", "DERNIER JEU"); msg = g.lastPlay; }
        text(c, tagTxt, tick.left + kh * 0.4f, tick.centerY(), kh * 0.32f, tfCond, last != null && last.goal ? LIVE : AMBER, Paint.Align.LEFT, kh * 3);
        text(c, msg, tick.left + kh * 2.6f, tick.centerY(), kh * 0.36f, tfReg, TEXT, Paint.Align.LEFT, tick.width() - kh * 2.9f);
        // legend
        drawShotLegend(c, cx, availBottom + gap * 0.05f - gap * 0.4f, gap * 0.75f);
        if (g == demoGame) tag(c, tr("DEMO · tap to close", "DÉMO · touchez pour fermer"), cx, availTop + gap * 0.2f, topH * 0.08f, Paint.Align.CENTER, LIVE, TEXT);
    }

    /** Team color that stays readable on white ice (very light colors become dark slate). */
    private static int iceColor(int col) {
        col = Leds.vivid(col) | 0xFF000000;
        int r = (col >> 16) & 0xFF, g = (col >> 8) & 0xFF, b = col & 0xFF;
        float lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f;
        if (lum > 0.72f) return 0xFF39424E;
        if (lum > 0.55f) return 0xFF000000 | ((int) (r * 0.7f) << 16) | ((int) (g * 0.7f) << 8) | (int) (b * 0.7f);
        return col;
    }

    /** Broadcast-style rink: bright ice, real markings, shots as clean team-colored dots, goals with the scorer. */
    private void drawRink(Canvas c, Model.Game g, RectF rink) {
        float k = rink.width() / 200f;
        float corner = 28 * k;
        float cx = rink.centerX(), cy = rink.centerY();
        Path shape = new Path();
        shape.addRoundRect(rink, corner, corner, Path.Direction.CW);

        // shadow + ice
        p.setStyle(Paint.Style.FILL);
        p.setShadowLayer(3 * k, 0, 1.2f * k, 0x99000000);
        p.setColor(0xFFE9EEF3);
        c.drawPath(shape, p);
        p.clearShadowLayer();
        c.save();
        c.clipPath(shape);
        p.setShader(new LinearGradient(0, rink.top, 0, rink.bottom, 0xFFF7F9FB, 0xFFE1E8EF, Shader.TileMode.CLAMP));
        c.drawRect(rink, p);
        p.setShader(null);

        // which team defends which end: soft team tint in each zone
        boolean homeLeft = "left".equals(g.homeSide);
        Model.Side leftTeam = homeLeft ? g.home : g.away, rightTeam = homeLeft ? g.away : g.home;
        int lc = iceColor(leftTeam.color), rc = iceColor(rightTeam.color);
        p.setColor(alpha(lc, 0x14));
        c.drawRect(rink.left, rink.top, cx - 25 * k, rink.bottom, p);
        p.setColor(alpha(rc, 0x14));
        c.drawRect(cx + 25 * k, rink.top, rink.right, rink.bottom, p);

        // center ice logo
        if (logos != null && logos.get(g.home.logo) != null) {
            bmpPaint.setAlpha(60);
            logo(c, g.home.logo, g.home.abbr, 0, cx, cy, 24 * k);
            bmpPaint.setAlpha(255);
        }

        final int RED = 0xFFD5243A, BLUE = 0xFF1F5FD1;
        float thin = Math.max(1.5f, 0.35f * k);
        // lines
        p.setColor(BLUE);
        for (int sgn : new int[]{-1, 1}) c.drawRect(cx + sgn * 25 * k - 0.5f * k, rink.top, cx + sgn * 25 * k + 0.5f * k, rink.bottom, p);
        p.setColor(RED);
        c.drawRect(cx - 0.5f * k, rink.top, cx + 0.5f * k, rink.bottom, p);
        for (int sgn : new int[]{-1, 1}) {
            float gx = cx + sgn * 89 * k;
            c.drawRect(gx - thin / 2, rink.top, gx + thin / 2, rink.bottom, p);
        }
        // circles + dots
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(thin);
        p.setColor(BLUE);
        c.drawCircle(cx, cy, 15 * k, p);
        p.setColor(RED);
        for (int sx : new int[]{-69, 69}) for (int sy : new int[]{-22, 22}) c.drawCircle(cx + sx * k, cy - sy * k, 15 * k, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(BLUE);
        c.drawCircle(cx, cy, 0.6f * k, p);
        p.setColor(RED);
        for (int sx : new int[]{-69, -20, 20, 69}) for (int sy : new int[]{-22, 22}) c.drawCircle(cx + sx * k, cy - sy * k, 1f * k, p);
        // creases + nets
        for (int sgn : new int[]{-1, 1}) {
            float gx = cx + sgn * 89 * k;
            RectF crease = new RectF(gx - 6 * k, cy - 6 * k, gx + 6 * k, cy + 6 * k);
            p.setColor(0xFF9BD0F5);
            c.drawArc(crease, sgn > 0 ? 90 : -90, 180, true, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(thin);
            p.setColor(RED);
            c.drawArc(crease, sgn > 0 ? 90 : -90, 180, false, p);
            p.setColor(0xFF5A6270);
            p.setStrokeWidth(Math.max(2f, 0.5f * k));
            float nx = sgn > 0 ? gx + 3.4f * k : gx - 3.4f * k;
            c.drawRoundRect(new RectF(Math.min(gx, nx), cy - 3 * k, Math.max(gx, nx), cy + 3 * k), 1.2f * k, 1.2f * k, p);
            p.setStyle(Paint.Style.FILL);
        }

        // shots: missed (rings) under shots on goal (dots) under goals
        float r0 = Math.max(5f, 1.7f * k);
        for (int pass = 0; pass < 3; pass++) {
            for (Model.Shot s : g.shots) {
                int kind = s.goal ? 2 : s.missed ? 0 : 1;
                if (kind != pass) continue;
                float px = cx + s.x * k, py = cy - s.y * k;
                int col = iceColor((s.home ? g.home : g.away).color);
                if (kind == 0) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(Math.max(1.5f, r0 * 0.28f));
                    p.setColor(alpha(col, 0x99));
                    c.drawCircle(px, py, r0 * 0.75f, p);
                    p.setStyle(Paint.Style.FILL);
                } else if (kind == 1) {
                    p.setColor(col);
                    c.drawCircle(px, py, r0, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(Math.max(1.2f, r0 * 0.25f));
                    p.setColor(0xFFFFFFFF);
                    c.drawCircle(px, py, r0, p);
                    p.setStyle(Paint.Style.FILL);
                } else {
                    p.setShadowLayer(r0 * 0.8f, 0, r0 * 0.2f, 0x66000000);
                    p.setColor(col);
                    c.drawCircle(px, py, r0 * 1.7f, p);
                    p.clearShadowLayer();
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(r0 * 0.35f);
                    p.setColor(0xFFFFFFFF);
                    c.drawCircle(px, py, r0 * 1.7f, p);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0xFFFFFFFF);
                    c.drawCircle(px, py, r0 * 0.55f, p);
                    if (!s.who.isEmpty()) {
                        String last = s.who.contains(". ") ? s.who.substring(s.who.indexOf(". ") + 2) : s.who;
                        float ly = py - r0 * 3.4f < rink.top + 4 * k ? py + r0 * 3.4f : py - r0 * 3.4f;
                        tag(c, last.toUpperCase(), px, ly, 2.4f * k, Paint.Align.CENTER, 0xF0151820, 0xFFFFFFFF);
                    }
                }
            }
        }
        // newest shot: soft expanding ring + name
        Long at = newShotAt.get(g.id);
        if (at != null && !g.shots.isEmpty()) {
            long t = System.currentTimeMillis() - at;
            if (t < 1800) {
                Model.Shot s = g.shots.get(g.shots.size() - 1);
                float px = cx + s.x * k, py = cy - s.y * k;
                int col = iceColor((s.home ? g.home : g.away).color);
                float f = (t % 900) / 900f;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(2f, r0 * 0.4f * (1 - f)));
                p.setColor(alpha(col, (int) (220 * (1 - f))));
                c.drawCircle(px, py, r0 * (1.4f + f * 4f), p);
                p.setStyle(Paint.Style.FILL);
                if (!s.who.isEmpty() && !s.goal) tag(c, s.who, px, py + r0 * 3.2f, 2.4f * k, Paint.Align.CENTER, col, 0xFFFFFFFF);
                postInvalidateOnAnimation();
            }
        }
        c.restore();

        // boards
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(3f, 1.1f * k));
        p.setColor(0xFFF2F4F7);
        c.drawPath(shape, p);
        p.setStrokeWidth(Math.max(1.5f, 0.4f * k));
        p.setColor(0xFF8A94A3);
        c.drawPath(shape, p);
        p.setStyle(Paint.Style.FILL);

        // team names above each end
        tag(c, leftTeam.abbr, rink.left + 14 * k, rink.top - 3.2f * k, 2.8f * k, Paint.Align.CENTER, lc, 0xFFFFFFFF);
        tag(c, rightTeam.abbr, rink.right - 14 * k, rink.top - 3.2f * k, 2.8f * k, Paint.Align.CENTER, rc, 0xFFFFFFFF);
    }

    /** Legend drawn with real symbols (not text glyphs). */
    private void drawShotLegend(Canvas c, float cx, float cy, float size) {
        String[] labels = {tr("Shot", "Tir"), tr("Missed", "Raté"), tr("Goal", "But")};
        p.setTypeface(tfCond);
        p.setTextSize(size);
        float r = size * 0.38f, gapX = size * 1.4f, total = 0;
        float[] tw = new float[3];
        for (int i = 0; i < 3; i++) { tw[i] = p.measureText(labels[i]); total += r * 2 + size * 0.4f + tw[i]; }
        total += gapX * 2;
        float x = cx - total / 2;
        for (int i = 0; i < 3; i++) {
            float dx = x + r;
            p.setStyle(i == 1 ? Paint.Style.STROKE : Paint.Style.FILL);
            p.setStrokeWidth(Math.max(1.5f, r * 0.35f));
            p.setColor(i == 1 ? MUTED : i == 2 ? 0xFFFFFFFF : MUTED);
            c.drawCircle(dx, cy, i == 2 ? r * 1.15f : r, p);
            p.setStyle(Paint.Style.FILL);
            text(c, labels[i], x + r * 2 + size * 0.4f, cy, size, tfCond, MUTED, Paint.Align.LEFT, tw[i] + 4);
            x += r * 2 + size * 0.4f + tw[i] + gapX;
        }
    }

    // ------------------------------------------------------------------ goal celebration
    private static float easeOutBack(float k) {
        float c1 = 1.70158f, c3 = c1 + 1;
        return 1 + c3 * (float) Math.pow(k - 1, 3) + c1 * (float) Math.pow(k - 1, 2);
    }

    private void drawCelebration(Canvas c, int w, int h) {
        long t = System.currentTimeMillis() - celebStart;
        Model.Side s = celebSide;
        float fade = Math.min(1f, t / 150f) * Math.min(1f, (CELEBRATION_MS - t) / 450f);
        fade = Math.max(0f, fade);

        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(alpha(0xFF000000, (int) (240 * fade)));
        c.drawRect(0, 0, w, h, p);

        double pulse = 0.5 + 0.5 * Math.sin(t * 2 * Math.PI / 420.0);
        int a = (int) ((70 + 160 * pulse) * fade);
        p.setShader(new RadialGradient(w / 2f, h * 0.4f, Math.max(w, h) * 0.8f,
            alpha(s.color, a), alpha(s.color, a / 5), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        // sweeping light beams
        p.setColor(alpha(0xFFFFFFFF, (int) (28 * fade)));
        for (int i = 0; i < 4; i++) {
            float bx = (float) (((t / 6.0) + i * w / 4.0) % (w * 1.4)) - w * 0.2f;
            c.save();
            c.rotate(18, bx, h / 2f);
            c.drawRect(bx - w * 0.03f, -h, bx + w * 0.03f, h * 2, p);
            c.restore();
        }

        // confetti
        Random rnd = new Random(celebStart);
        int[] colors = {s.color, TEXT, AMBER, alpha(s.color, 0xFF) ^ 0x00202020};
        for (int i = 0; i < 90; i++) {
            float x0 = rnd.nextFloat() * w;
            float speed = h * (0.25f + rnd.nextFloat() * 0.35f);
            float yy = -h * 0.1f - rnd.nextFloat() * h * 0.6f + speed * t / 1000f;
            if (yy > h * 1.05f) continue;
            float xx = x0 + (float) Math.sin(t / 300.0 + i) * w * 0.02f;
            float sz = h * (0.008f + rnd.nextFloat() * 0.01f);
            p.setColor(alpha(colors[i % colors.length], (int) (255 * fade)));
            c.save();
            c.rotate((t / 4f + i * 37) % 360, xx, yy);
            c.drawRect(xx - sz, yy - sz * 0.5f, xx + sz, yy + sz * 0.5f, p);
            c.restore();
        }

        float k = Math.min(1f, t / 520f);
        bmpPaint.setAlpha((int) (255 * fade));
        logo(c, s.logo, s.abbr, s.color, w / 2f, h * 0.33f, h * 0.4f * Math.max(0.01f, easeOutBack(k)));
        bmpPaint.setAlpha(255);

        float ws = h * 0.16f * (1 + 0.05f * (float) Math.sin(t * 2 * Math.PI / 420.0));
        p.setShadowLayer(h * 0.025f, 0, h * 0.008f, 0xCC000000);
        text(c, celebWord, w / 2f, h * 0.66f, ws, tfBlack, alpha(TEXT, (int) (255 * fade)), Paint.Align.CENTER, w * 0.95f);
        Model.Game g = celebGame;
        String line = g.away.abbr + "  " + g.away.score + "  –  " + g.home.score + "  " + g.home.abbr;
        text(c, line, w / 2f, h * 0.8f, h * 0.07f, tfCond, alpha(TEXT, (int) (230 * fade)), Paint.Align.CENTER, w * 0.9f);
        String sub = (celebScorer.isEmpty() ? "" : celebScorer.toUpperCase() + "  ·  ") + g.detail.toUpperCase();
        if (!sub.isEmpty())
            text(c, sub, w / 2f, h * 0.89f, h * 0.045f, tfCond, alpha(AMBER, (int) (230 * fade)), Paint.Align.CENTER, w * 0.9f);
        p.clearShadowLayer();
    }

    // ------------------------------------------------------------------ standings
    /** One conference/division at a time, full width, big rows (or everything stacked when it's small, e.g. MY TEAMS). */
    private void drawStandings(Canvas c, int w, int h, float top) {
        groupChipRects.clear();
        standPrevRect.set(0, 0, 0, 0);
        standNextRect.set(0, 0, 0, 0);
        float gap = w * 0.016f;
        int total = 0;
        for (Model.Group g : groups) total += g.teams.size() + 1;

        if (total <= 14) {   // small: show it all
            float rowH = Math.min(h * 0.09f, (h - top - gap) / (total + groups.size()));
            float y = top;
            for (Model.Group g : groups) y = drawGroup(c, g, gap, y, w - 2 * gap, rowH, 0, g.teams.size());
            return;
        }

        standGroup = Math.max(0, Math.min(standGroup, groups.size() - 1));
        Model.Group g = groups.get(standGroup);
        int per = Math.min(standRowsPerPage(), g.teams.size());
        int pages = (g.teams.size() + per - 1) / per;
        standPage = Math.max(0, Math.min(standPage, pages - 1));

        // bar: group chips on the left, page arrows on the right
        float barH = h * 0.065f, y = top;
        if (groups.size() > 1 || pages > 1) {
            float x = gap;
            float arrowsW = pages > 1 ? barH * 4.2f : 0;
            float chipMax = (w - 2 * gap - arrowsW - gap * groups.size()) / Math.max(1, groups.size());
            for (int i = 0; i < groups.size(); i++) {
                String t = shortTitle(groups.get(i).title);
                p.setTypeface(tfCond);
                p.setTextSize(barH * 0.42f);
                float cw = Math.min(chipMax, p.measureText(t) + barH * 0.9f);
                RectF chip = new RectF(x, y, x + cw, y + barH);
                boolean on = i == standGroup;
                p.setColor(on ? TEXT : 0xFF161920);
                c.drawRoundRect(chip, barH / 2, barH / 2, p);
                text(c, t, chip.centerX(), chip.centerY(), barH * 0.42f, tfCond, on ? 0xFF000000 : MUTED, Paint.Align.CENTER, cw * 0.92f);
                groupChipRects.add(chip);
                x += cw + gap * 0.6f;
            }
            if (pages > 1) {
                standNextRect.set(w - gap - barH * 1.3f, y, w - gap, y + barH);
                standPrevRect.set(standNextRect.left - gap * 0.5f - barH * 1.3f, y, standNextRect.left - gap * 0.5f, y + barH);
                for (RectF r : new RectF[]{standPrevRect, standNextRect}) { p.setColor(0xFF161920); c.drawRoundRect(r, barH / 2, barH / 2, p); }
                text(c, "▲", standPrevRect.centerX(), standPrevRect.centerY(), barH * 0.38f, tfMed, standPage > 0 ? TEXT : DIM, Paint.Align.CENTER, barH);
                text(c, "▼", standNextRect.centerX(), standNextRect.centerY(), barH * 0.38f, tfMed, standPage < pages - 1 ? TEXT : DIM, Paint.Align.CENTER, barH);
                text(c, (standPage + 1) + "/" + pages, standPrevRect.left - gap * 0.6f, y + barH / 2, barH * 0.36f, tfCond, MUTED, Paint.Align.RIGHT, barH * 1.5f);
            }
            y += barH + gap * 0.5f;
        }

        Model.Group sub = new Model.Group();
        sub.title = g.title;
        sub.cols = g.cols;
        int from = standPage * per;
        sub.teams.addAll(g.teams.subList(from, Math.min(g.teams.size(), from + per)));
        float rowH = (h - y - gap * 0.5f) / (per + 2);
        drawGroup(c, sub, gap, y, w - 2 * gap, rowH, from, g.teams.size());
    }

    /** "Eastern Conference" -> "EASTERN", "NHL · Atlantic Division" -> "NHL · ATLANTIC". */
    private static String shortTitle(String t) {
        String s = t.replace(" Conference", "").replace(" Division", "").replace(" League", "");
        return s.toUpperCase();
    }

    private float drawGroup(Canvas c, Model.Group g, float x, float y, float cw, float rowH, int offset, int fullSize) {
        String[] gc = g.cols != null ? g.cols : cols;
        int n = gc.length;
        float statW = cw * (n >= 6 ? 0.08f : 0.095f);
        float statsStart = x + cw - statW * n;
        float inner = cw * 0.014f;
        float rankW = cw * 0.065f;
        float logoS = rowH * 0.74f;
        float ts = Math.min(rowH * 0.5f, statW * 0.5f);
        int cut = leagueInfo == null ? 0 : leagueInfo.cut;
        int good = leagueInfo == null ? 0 : leagueInfo.good;
        int bad = leagueInfo == null ? 0 : leagueInfo.bad;

        text(c, g.title.toUpperCase(), x + inner, y + rowH * 0.5f, Math.min(rowH * 0.48f, cw * 0.04f),
            tfCond, ACCENT, Paint.Align.LEFT, cw);
        y += rowH;

        float cy = y + rowH / 2f;
        text(c, "#", x + inner, cy, ts * 0.75f, tfCond, DIM, Paint.Align.LEFT, rankW);
        text(c, "TEAM", x + inner + rankW + logoS + inner, cy, ts * 0.75f, tfCond, DIM, Paint.Align.LEFT, cw);
        for (int i = 0; i < n; i++)
            text(c, gc[i], statsStart + statW * (i + 1) - inner, cy, ts * 0.75f, tfCond, DIM, Paint.Align.RIGHT, statW);
        p.setColor(HAIRLINE);
        c.drawRect(x, y + rowH - 1, x + cw, y + rowH, p);
        y += rowH;

        int size = g.teams.size();
        int full = Math.max(fullSize, size);
        for (int i = 0; i < size; i++) {
            Model.Team t = g.teams.get(i);
            boolean fav = favorites.contains(t.abbr);
            RectF row = new RectF(x, y, x + cw, y + rowH);
            if (fav) {
                p.setColor(alpha(ACCENT, 0x2E));
                c.drawRoundRect(row, rowH * 0.2f, rowH * 0.2f, p);
                p.setColor(ACCENT);
                c.drawRoundRect(new RectF(x, y + rowH * 0.18f, x + rowH * 0.08f, y + rowH * 0.82f), 4, 4, p);
            } else if (i % 2 == 1) {
                p.setColor(0x0AFFFFFF);
                c.drawRect(row, p);
            }
            // soccer zones: Champions League (green) / relegation (red)
            int zone = good > 0 && i + offset < good ? GREEN : bad > 0 && i + offset >= full - bad ? LIVE : 0;
            if (zone != 0 && !fav) {
                p.setColor(zone);
                c.drawRoundRect(new RectF(x, y + rowH * 0.22f, x + rowH * 0.07f, y + rowH * 0.78f), 3, 3, p);
            }
            Model.Game bg = boardGame();
            if (bg != null && (t.abbr.equals(bg.away.abbr) || t.abbr.equals(bg.home.abbr))) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1.5f, rowH * 0.04f));
                p.setColor(alpha(AMBER, 0xAA));
                c.drawRoundRect(row, rowH * 0.2f, rowH * 0.2f, p);
                p.setStyle(Paint.Style.FILL);
            }
            hitRects.add(row);
            hitAbbrs.add(new String[]{t.abbr, t.abbr});

            cy = y + rowH / 2f;
            text(c, String.valueOf(t.rank > 0 ? t.rank : i + 1 + offset), x + inner * 1.6f, cy, ts * 0.9f, tfMed, MUTED, Paint.Align.LEFT, rankW);
            float lx = x + inner + rankW;
            logo(c, t.logo, t.abbr, t.color != 0 ? t.color : 0xFF3A3F4A, lx + logoS / 2f, cy, logoS);
            float ax = lx + logoS + inner;

            p.setTypeface(tfCond);
            p.setTextSize(ts);
            float abbrW = p.measureText("WWW★");
            text(c, star(t.abbr), ax, cy, ts, tfCond, fav ? ACCENT : TEXT, Paint.Align.LEFT, statsStart - ax);
            for (Model.Game lg : games) {
                if (lg.isLive() && (t.abbr.equals(lg.away.abbr) || t.abbr.equals(lg.home.abbr))) {
                    p.setColor(LIVE);
                    c.drawCircle(ax + abbrW * 0.92f, cy - ts * 0.3f, ts * 0.14f, p);
                    break;
                }
            }
            float nx = ax + abbrW + inner;
            float nameMax = statsStart - nx - statW * 0.15f;
            if (nameMax > ts * 2.5f)
                text(c, t.name, nx, cy, ts * 0.9f, tfReg, fav ? TEXT : MUTED, Paint.Align.LEFT, nameMax);

            for (int k = 0; k < n; k++) {
                boolean last = k == n - 1;
                String v = t.vals != null && k < t.vals.length ? t.vals[k] : "-";
                int col = last ? TEXT : 0xFFC9CDD3;
                if ("STRK".equals(gc[k])) col = v.startsWith("W") ? GREEN : v.startsWith("L") ? LIVE : MUTED;
                text(c, v, statsStart + statW * (k + 1) - inner, cy, ts, last ? tfBlack : tfMed, col, Paint.Align.RIGHT, statW);
            }
            y += rowH;

            // playoff line
            if (cut > 0 && i + offset == cut - 1 && i + offset < full - 1) {
                p.setColor(alpha(AMBER, 0x99));
                float dash = rowH * 0.3f;
                for (float dx = x; dx < x + cw; dx += dash * 2) c.drawRect(dx, y - 1, Math.min(dx + dash, x + cw), y + 1, p);
            }
        }
        return y;
    }
}
