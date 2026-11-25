package com.gregoire.thorstandings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/** Extra info: NHL goal scorers (NHL's own API) and Polymarket win chances. */
public final class Extras {
    private Extras() {}

    /** ESPN abbreviation -> NHL/Polymarket abbreviation where they differ. */
    private static final Map<String, String> ALT = new HashMap<>();
    static {
        ALT.put("hockey/nhl:LA", "LAK"); ALT.put("hockey/nhl:NJ", "NJD"); ALT.put("hockey/nhl:SJ", "SJS");
        ALT.put("hockey/nhl:TB", "TBL"); ALT.put("hockey/nhl:UTAH", "UTA");
        ALT.put("basketball/nba:GS", "GSW"); ALT.put("basketball/nba:NY", "NYK"); ALT.put("basketball/nba:SA", "SAS");
        ALT.put("basketball/nba:NO", "NOP"); ALT.put("basketball/nba:UTAH", "UTA"); ALT.put("basketball/nba:WSH", "WAS");
        ALT.put("football/nfl:WSH", "WAS");
    }

    /**
     * Polymarket's own short names for teams, where they are not the ESPN abbreviation (found by looking at its real markets, e.g.
     * "nhl-las-edm-2026-11-24" is Golden Knights vs. Oilers). Without these, those teams never had win chances or a graph.
     */
    private static final Map<String, String> POLY_CODE = new HashMap<>();
    static {
        POLY_CODE.put("hockey/nhl:VGK", "las"); POLY_CODE.put("hockey/nhl:CGY", "cal"); POLY_CODE.put("hockey/nhl:MTL", "mon");
        POLY_CODE.put("hockey/nhl:UTA", "utah"); POLY_CODE.put("hockey/nhl:UTAH", "utah");
    }

    private static String polyCode(String league, String abbr) {
        String c = POLY_CODE.get(league + ":" + abbr);
        return c == null ? abbr.toLowerCase(Locale.US) : c;
    }

    private static final Map<String, String> POLY_PREFIX = new HashMap<>();
    static {
        POLY_PREFIX.put("hockey/nhl", "nhl"); POLY_PREFIX.put("basketball/nba", "nba");
        POLY_PREFIX.put("football/nfl", "nfl"); POLY_PREFIX.put("baseball/mlb", "mlb");
        POLY_PREFIX.put("basketball/wnba", "wnba");
    }

    private static String alt(String league, String abbr) {
        String a = ALT.get(league + ":" + abbr);
        return a == null ? abbr : a;
    }

    private static String utcDate(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms > 0 ? ms : System.currentTimeMillis()));
    }

    private static String etDate(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("America/New_York"));
        return f.format(new Date(ms > 0 ? ms : System.currentTimeMillis()));
    }

    /** Fills NHL goals (scorer + assists) from api-web.nhle.com. */
    public static void nhlGoals(List<Model.Game> games) {
        Set<String> dates = new HashSet<>();
        for (Model.Game g : games) if ("hockey/nhl".equals(g.league) && !"pre".equals(g.state)) dates.add(etDate(g.startMs));
        for (String date : dates) {
            try {
                JSONObject root = new JSONObject(Espn.get("https://api-web.nhle.com/v1/score/" + date));
                JSONArray list = root.optJSONArray("games");
                if (list == null) continue;
                for (int i = 0; i < list.length(); i++) {
                    JSONObject ng = list.optJSONObject(i);
                    if (ng == null) continue;
                    String away = abbrev(ng.optJSONObject("awayTeam")), home = abbrev(ng.optJSONObject("homeTeam"));
                    for (Model.Game g : games) {
                        if (!"hockey/nhl".equals(g.league)) continue;
                        if (!alt(g.league, g.away.abbr).equals(away) || !alt(g.league, g.home.abbr).equals(home)) continue;
                        g.nhlId = ng.optString("id", "");
                        JSONArray goals = ng.optJSONArray("goals");
                        if (goals == null) continue;
                        g.goals.clear();
                        for (int k = 0; k < goals.length(); k++) {
                            JSONObject o = goals.optJSONObject(k);
                            if (o == null) continue;
                            Model.Goal goal = new Model.Goal();
                            goal.home = home.equals(o.optString("teamAbbrev", ""));
                            int per = o.optInt("period", 0);
                            goal.time = (per <= 3 ? per + ordinal(per) : per == 4 ? "OT" : "SO") + " " + o.optString("timeInPeriod", "");
                            JSONObject nm = o.optJSONObject("name");
                            goal.who = nm == null ? "Goal" : nm.optString("default", "Goal");
                            String str = o.optString("strength", "");
                            if ("pp".equals(str)) goal.extra = "PP";
                            else if ("sh".equals(str)) goal.extra = "SH";
                            if ("empty-net".equals(o.optString("goalModifier", ""))) goal.extra = "EN";
                            g.goals.add(goal);
                        }
                    }
                }
            } catch (Exception ignored) { }
        }
    }

    /** Shot map for one NHL game from the NHL's play-by-play (every shot/goal with its spot on the ice). */
    public static void nhlPlays(Model.Game g) {
        if (g == null || g.nhlId.isEmpty() || "pre".equals(g.state)) return;
        try {
            JSONObject root = new JSONObject(Espn.get("https://api-web.nhle.com/v1/gamecenter/" + g.nhlId + "/play-by-play"));
            JSONObject ht = root.optJSONObject("homeTeam");
            int homeId = ht == null ? -1 : ht.optInt("id", -1);
            Map<String, String> names = new HashMap<>();
            JSONArray roster = root.optJSONArray("rosterSpots");
            if (roster != null) for (int i = 0; i < roster.length(); i++) {
                JSONObject r = roster.optJSONObject(i);
                if (r == null) continue;
                JSONObject fn = r.optJSONObject("firstName"), ln = r.optJSONObject("lastName");
                String first = fn == null ? "" : fn.optString("default", ""), last = ln == null ? "" : ln.optString("default", "");
                names.put(r.optString("playerId", ""), (first.isEmpty() ? "" : first.charAt(0) + ". ") + last);
            }
            JSONArray plays = root.optJSONArray("plays");
            if (plays == null) return;
            String cur = "";
            for (int i = plays.length() - 1; i >= 0 && cur.isEmpty(); i--) {
                JSONObject pl = plays.optJSONObject(i);
                if (pl != null) { cur = pl.optString("homeTeamDefendingSide", ""); if (g.situationCode.isEmpty()) g.situationCode = pl.optString("situationCode", ""); }
            }
            JSONObject lastPl = plays.length() > 0 ? plays.optJSONObject(plays.length() - 1) : null;
            if (lastPl != null && !lastPl.optString("situationCode", "").isEmpty()) g.situationCode = lastPl.optString("situationCode", "");
            g.homeSide = cur;
            g.shots.clear();
            for (int i = 0; i < plays.length(); i++) {
                JSONObject pl = plays.optJSONObject(i);
                if (pl == null) continue;
                String type = pl.optString("typeDescKey", "");
                boolean goal = "goal".equals(type), shot = "shot-on-goal".equals(type), missed = "missed-shot".equals(type);
                if (!goal && !shot && !missed) continue;
                JSONObject d = pl.optJSONObject("details");
                if (d == null || !d.has("xCoord")) continue;
                Model.Shot s = new Model.Shot();
                s.x = (float) d.optDouble("xCoord", 0);
                s.y = (float) d.optDouble("yCoord", 0);
                String side = pl.optString("homeTeamDefendingSide", cur);
                if (!side.isEmpty() && !cur.isEmpty() && !side.equals(cur)) { s.x = -s.x; s.y = -s.y; }   // teams switch ends each period
                s.goal = goal; s.missed = missed;
                s.home = d.optInt("eventOwnerTeamId", -2) == homeId;
                String pid = d.optString(goal ? "scoringPlayerId" : "shootingPlayerId", "");
                s.who = names.containsKey(pid) ? names.get(pid) : "";
                JSONObject pd = pl.optJSONObject("periodDescriptor");
                int per = pd == null ? 0 : pd.optInt("number", 0);
                s.time = (per <= 3 ? per + ordinal(per) : "OT") + " " + pl.optString("timeInPeriod", "");
                g.shots.add(s);
            }
        } catch (Exception ignored) { }
    }

    private static String abbrev(JSONObject t) { return t == null ? "" : t.optString("abbrev", ""); }

    private static String ordinal(int n) { return n == 1 ? "st" : n == 2 ? "nd" : n == 3 ? "rd" : "th"; }

    // ------------------------------------------------------------------ Polymarket
    // Several threads touch these (the refresh and the background price fetches), so they are concurrent maps.
    private static final float[] POLY_NONE = new float[0];   // "looked, no market" (a concurrent map can't hold null)
    private static final Map<String, float[]> polyCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Long> polyAt = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<String> polyBusy = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.ExecutorService polyPool = java.util.concurrent.Executors.newFixedThreadPool(3);
    private static final Map<String, String> polyToken = new java.util.concurrent.ConcurrentHashMap<>();   // game id -> the away team's side of its market
    private static final Map<String, List<Float>> polyHistCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Long> polyHistAt = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<String> polyHistBusy = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final long POLY_TTL = 5 * 60_000;       // before the game
    private static final long POLY_TTL_LIVE = 6_000;       // during the game: prices move with every goal

    /** Polymarket game markets live at slugs like "nhl-edm-car-2026-11-21". */
    public static void polymarket(List<Model.Game> games) {
        for (Model.Game g : games) {
            String prefix = POLY_PREFIX.get(g.league);
            if (prefix == null || g.isFinal()) continue;
            Long at = polyAt.get(g.id);
            boolean noMarket = polyCache.get(g.id) == POLY_NONE;
            long ttl = noMarket ? 3 * POLY_TTL : g.isLive() ? POLY_TTL_LIVE : POLY_TTL;   // no market: don't keep asking every few seconds
            if (at == null) {   // the first time we see this game: ask now, so the odds are there on the first screen
                store(g.id, fetchPoly(prefix, g));
            } else if (System.currentTimeMillis() - at >= ttl && polyBusy.add(g.id)) {
                // later: ask in the background and use the last answer meanwhile, so a slow price never holds back the score
                final String id = g.id; final String pf = prefix;
                polyPool.execute(() -> { try { store(id, fetchPoly(pf, g)); } catch (Exception ignored) { } finally { polyBusy.remove(id); } });
            }
            float[] res = polyCache.get(g.id);
            if (res != null && res.length == 2) {
                g.away.odds = res[0];
                g.home.odds = res[1];
                g.drawOdds = -1;
                g.oddsSource = "POLYMARKET";
                if (g.isLive()) applyHistory(g);
            }
        }
    }

    /**
     * The graph of the whole game, even if you open the app halfway through (or 20 minutes before the end): Polymarket keeps
     * the price of every market minute by minute. The first time a live game is seen it is fetched right away; after that it
     * is refreshed in the background about once a minute.
     */
    private static void applyHistory(Model.Game g) {
        Long at = polyHistAt.get(g.id);
        boolean stale = at == null || System.currentTimeMillis() - at >= 60_000;
        if (stale && polyHistBusy.add(g.id)) {
            if (at == null) {
                try { storeHistory(g); } finally { polyHistBusy.remove(g.id); }
            } else {
                polyPool.execute(() -> { try { storeHistory(g); } catch (Exception ignored) { } finally { polyHistBusy.remove(g.id); } });
            }
        }
        List<Float> h = polyHistCache.get(g.id);
        g.polyHist.clear();
        if (h != null) g.polyHist.addAll(h);
    }

    private static void storeHistory(Model.Game g) {
        List<Float> h = null;
        try { h = fetchHistory(g); } catch (Exception ignored) { }
        if (h != null) polyHistCache.put(g.id, h);
        polyHistAt.put(g.id, System.currentTimeMillis());   // (also after a failure: try again in a minute, not on every refresh)
    }

    /**
     * The away team's win chance, one value a minute, from the START OF THE GAME until now (not from when the market opened:
     * that is days earlier and is only betting before anyone has played). The first value is the last price before the
     * start, so the curve begins where the game begins.
     */
    private static List<Float> fetchHistory(Model.Game g) throws Exception {
        String tok = polyToken.get(g.id);
        if (tok == null || tok.isEmpty()) return null;
        long now = System.currentTimeMillis() / 1000, start = g.startMs > 0 ? g.startMs / 1000 : now - 3 * 3600;
        // ask from a few hours before the start, only to find the last price before it
        JSONArray pts = new JSONObject(Espn.get("https://clob.polymarket.com/prices-history?market=" + tok
            + "&startTs=" + (start - 6 * 3600) + "&endTs=" + now + "&fidelity=1")).optJSONArray("history");
        if (pts == null || pts.length() < 2) return null;
        List<Float> out = new java.util.ArrayList<>();
        int k = 0;
        float last = (float) pts.getJSONObject(0).optDouble("p", 0.5);
        for (long t = start; t <= now && out.size() < 500; t += 60) {   // one value per minute, the last price until the next one
            while (k < pts.length() && pts.getJSONObject(k).optLong("t", 0) <= t) { last = (float) pts.getJSONObject(k).optDouble("p", last); k++; }
            out.add(last);
        }
        return out.size() >= 3 ? out : null;
    }

    private static void store(String id, float[] res) {
        polyCache.put(id, res == null ? POLY_NONE : res);
        polyAt.put(id, System.currentTimeMillis());
    }

    private static float[] fetchPoly(String prefix, Model.Game g) {
        // Polymarket dates its slugs by the kickoff day; evening games in North America are already the
        // next day in UTC (e.g. Sunday Night Football 8:20 PM ET = "...-2026-11-23"), so try both days.
        String etD = etDate(g.startMs), utcD = utcDate(g.startMs);
        String[] dates = etD.equals(utcD) ? new String[]{etD} : new String[]{utcD, etD};
        // a team can be named three ways: Polymarket's own name, the league's official abbreviation, or ESPN's: try them in that order
        String a0 = polyCode(g.league, g.away.abbr), h0 = polyCode(g.league, g.home.abbr);
        String a1 = g.away.abbr.toLowerCase(Locale.US), h1 = g.home.abbr.toLowerCase(Locale.US);
        String a2 = alt(g.league, g.away.abbr).toLowerCase(Locale.US), h2 = alt(g.league, g.home.abbr).toLowerCase(Locale.US);
        java.util.List<String> sl = new java.util.ArrayList<>();
        for (String date : dates) {
            String[][] pairs = {{a0, h0}, {a1, h1}, {a2, h2}};
            for (String[] pr : pairs) {
                String slug = prefix + "-" + pr[0] + "-" + pr[1] + "-" + date;
                if (!sl.contains(slug)) sl.add(slug);
            }
        }
        String[] slugs = sl.toArray(new String[0]);
        for (String slug : slugs) {
            try {
                JSONArray evs = new JSONArray(Espn.get("https://gamma-api.polymarket.com/events?slug=" + slug));
                JSONObject ev = evs.length() > 0 ? evs.optJSONObject(0) : null;
                JSONArray markets = ev == null ? null : ev.optJSONArray("markets");
                if (markets == null) continue;
                JSONObject m = null;
                for (int i = 0; i < markets.length(); i++) {
                    JSONObject x = markets.optJSONObject(i);
                    if (x != null && "moneyline".equals(x.optString("sportsMarketType"))) { m = x; break; }
                }
                if (m == null) m = markets.optJSONObject(0);
                if (m == null) continue;
                JSONArray outs = new JSONArray(m.optString("outcomes", "[]"));
                JSONArray prices = new JSONArray(m.optString("outcomePrices", "[]"));
                if (outs.length() < 2 || prices.length() < 2) continue;
                float p0 = Float.parseFloat(prices.optString(0, "0")), p1 = Float.parseFloat(prices.optString(1, "0"));
                String o0 = outs.optString(0, "").toLowerCase(Locale.US);
                boolean firstIsHome = !g.home.name.isEmpty() && o0.contains(g.home.name.toLowerCase(Locale.US));
                float away = firstIsHome ? p1 : p0, home = firstIsHome ? p0 : p1;
                float sum = away + home;
                if (sum <= 0) continue;
                try {   // the id of the away team's side, to ask for the price history later
                    String tok = new JSONArray(m.optString("clobTokenIds", "[]")).optString(firstIsHome ? 1 : 0, "");
                    if (!tok.isEmpty()) polyToken.put(g.id, tok);
                } catch (Exception ignored) { }
                return new float[]{away / sum, home / sum};
            } catch (Exception ignored) { }
        }
        return null;
    }

    // ------------------------------------------------------------------ ESPN game summary (GAME tab on the bottom screen)
    private static final String[][] PREF = {
        {"football", "totalYards", "netPassingYards", "rushingYards", "firstDowns", "thirdDownEff", "turnovers", "totalPenaltiesYards", "possessionTime"},
        {"basketball", "fieldGoalPct", "threePointFieldGoalPct", "freeThrowPct", "totalRebounds", "assists", "turnovers", "steals", "blocks"},
        {"hockey", "shotsTotal", "powerPlayGoals", "faceoffPercent", "hits", "blockedShots", "takeaways", "giveaways", "penaltyMinutes"},
        {"soccer", "possessionPct", "totalShots", "shotsOnTarget", "wonCorners", "foulsCommitted", "yellowCards", "offsides", "saves"},
    };
    private static final Map<String, Long> sumAt = new HashMap<>();

    /** Team stats, leaders, scoring plays, current drive and ESPN's win probability for one game. */
    public static void summary(Model.Game g) {
        if (g == null || g.league.isEmpty() || g.league.contains("pwhl") || "racing".equals(g.sport) || "esports".equals(g.sport)) return;
        String ev = g.id.split("-")[0];
        if (!ev.matches("\\d+")) return;
        Long at = sumAt.get(g.id);
        if (at != null && System.currentTimeMillis() - at < (g.isLive() ? 15_000 : 300_000)) { restore(g); return; }
        try {
            JSONObject root = new JSONObject(Espn.get("https://site.api.espn.com/apis/site/v2/sports/" + g.league + "/summary?event=" + ev));
            Model.Game c = new Model.Game();   // parsed copy, cached and copied into g
            // team stats
            JSONObject box = root.optJSONObject("boxscore");
            JSONArray teams = box == null ? null : box.optJSONArray("teams");
            if (teams != null && teams.length() == 2) {
                JSONObject t0 = teams.optJSONObject(0), t1 = teams.optJSONObject(1);
                boolean firstHome = t0 != null && "home".equals(t0.optString("homeAway"));
                JSONObject aw = firstHome ? t1 : t0, hm = firstHome ? t0 : t1;
                JSONArray as = aw == null ? null : aw.optJSONArray("statistics"), hs = hm == null ? null : hm.optJSONArray("statistics");
                if (as != null && hs != null) {
                    Map<String, String[]> byName = new java.util.LinkedHashMap<>();
                    for (int i = 0; i < as.length(); i++) {
                        JSONObject a = as.optJSONObject(i);
                        if (a == null) continue;
                        String name = a.optString("name");
                        if (byName.containsKey(name)) continue;
                        String hv = "";
                        for (int k = 0; k < hs.length(); k++) { JSONObject h = hs.optJSONObject(k); if (h != null && name.equals(h.optString("name"))) { hv = h.optString("displayValue"); break; } }
                        byName.put(name, new String[]{a.optString("label", name), a.optString("displayValue"), hv});
                    }
                    String[] pref = null;
                    for (String[] p : PREF) if (p[0].equals(g.sport)) pref = p;
                    if (pref != null) for (int k = 1; k < pref.length; k++) { String[] v = byName.get(pref[k]); if (v != null) c.teamStats.add(v); }
                    if (c.teamStats.isEmpty()) for (String[] v : byName.values()) { if (c.teamStats.size() >= 8) break; c.teamStats.add(v); }
                }
            }
            // leaders
            JSONArray lead = root.optJSONArray("leaders");
            if (lead != null) for (int i = 0; i < lead.length(); i++) {
                JSONObject tl = lead.optJSONObject(i);
                if (tl == null) continue;
                JSONObject tm = tl.optJSONObject("team");
                String abbr = tm == null ? "" : tm.optString("abbreviation");
                JSONArray cats = tl.optJSONArray("leaders");
                if (cats == null) continue;
                for (int k = 0; k < Math.min(3, cats.length()); k++) {
                    JSONObject cat = cats.optJSONObject(k);
                    JSONArray ls = cat == null ? null : cat.optJSONArray("leaders");
                    JSONObject top = ls == null || ls.length() == 0 ? null : ls.optJSONObject(0);
                    JSONObject ath = top == null ? null : top.optJSONObject("athlete");
                    if (ath == null) continue;
                    c.leaderLines.add(new String[]{abbr, cat.optString("displayName", cat.optString("name")),
                        ath.optString("shortName", ath.optString("displayName")), top.optString("displayValue")});
                }
            }
            // scoring plays
            JSONArray sp = root.optJSONArray("scoringPlays");
            if (sp != null) for (int i = 0; i < sp.length(); i++) {
                JSONObject p = sp.optJSONObject(i);
                if (p == null) continue;
                JSONObject tm = p.optJSONObject("team");
                Model.Goal gl = new Model.Goal();
                gl.home = tm != null && tm.optString("abbreviation").equals(g.home.abbr);
                String type = p.optJSONObject("type") == null ? "" : p.optJSONObject("type").optString("text");
                gl.who = type.isEmpty() ? p.optString("text") : type;
                gl.extra = type.isEmpty() ? "" : p.optString("text");
                JSONObject per = p.optJSONObject("period"), clk = p.optJSONObject("clock");
                gl.time = (per == null ? "" : "Q" + per.optInt("number") + " ") + (clk == null ? "" : clk.optString("displayValue"));
                if ("soccer".equals(g.sport) || "hockey".equals(g.sport)) gl.time = clk == null ? "" : clk.optString("displayValue");
                c.goals.add(gl);
            }
            // current drive (football)
            JSONObject drives = root.optJSONObject("drives");
            JSONObject cur = drives == null ? null : drives.optJSONObject("current");
            if (cur != null && g.isLive()) {
                JSONObject tm = cur.optJSONObject("team");
                c.driveText = (tm == null ? "" : tm.optString("abbreviation") + " · ") + cur.optString("description");
            }
            // ESPN win probability (used when there's no Polymarket market)
            JSONArray wp = root.optJSONArray("winprobability");
            if (wp != null) {
                int step = Math.max(1, wp.length() / 160);
                for (int i = 0; i < wp.length(); i += step) {
                    JSONObject w = wp.optJSONObject(i);
                    if (w != null && w.has("homeWinPercentage")) c.winHist.add((float) (1 - w.optDouble("homeWinPercentage")));
                }
                JSONObject last = wp.optJSONObject(wp.length() - 1);
                if (last != null && last.has("homeWinPercentage") && (c.winHist.isEmpty() || i(c) != (float) (1 - last.optDouble("homeWinPercentage"))))
                    c.winHist.add((float) (1 - last.optDouble("homeWinPercentage")));
            }
            sumCache.put(g.id, c);
            sumAt.put(g.id, System.currentTimeMillis());
        } catch (Exception ignored) { }
        restore(g);
    }

    private static float i(Model.Game c) { return c.winHist.get(c.winHist.size() - 1); }

    private static final Map<String, Model.Game> sumCache = new HashMap<>();

    private static void restore(Model.Game g) {
        Model.Game c = sumCache.get(g.id);
        if (c == null) return;
        g.teamStats.clear(); g.teamStats.addAll(c.teamStats);
        g.leaderLines.clear(); g.leaderLines.addAll(c.leaderLines);
        g.winHist.clear(); g.winHist.addAll(c.winHist);
        g.driveText = c.driveText;
        if (g.goals.isEmpty()) g.goals.addAll(c.goals);
        if (g.away.odds < 0 && !c.winHist.isEmpty() && !g.isFinal()) {
            float a = c.winHist.get(c.winHist.size() - 1);
            g.away.odds = a; g.home.odds = 1 - a; g.oddsSource = "ESPN";
        }
    }
}
