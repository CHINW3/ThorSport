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
    private static final Map<String, float[]> polyCache = new HashMap<>();
    private static final Map<String, Long> polyAt = new HashMap<>();
    private static final long POLY_TTL = 5 * 60_000;       // before the game
    private static final long POLY_TTL_LIVE = 30_000;      // during the game: prices move with every goal

    /** Polymarket game markets live at slugs like "nhl-edm-car-2026-11-21". */
    public static void polymarket(List<Model.Game> games) {
        for (Model.Game g : games) {
            String prefix = POLY_PREFIX.get(g.league);
            if (prefix == null || g.isFinal()) continue;
            float[] res;
            Long at = polyAt.get(g.id);
            long ttl = g.isLive() ? POLY_TTL_LIVE : POLY_TTL;
            if (at != null && System.currentTimeMillis() - at < ttl) res = polyCache.get(g.id);
            else {
                res = fetchPoly(prefix, g);
                polyCache.put(g.id, res);
                polyAt.put(g.id, System.currentTimeMillis());
            }
            if (res != null) {
                g.away.odds = res[0];
                g.home.odds = res[1];
                g.drawOdds = -1;
                g.oddsSource = "POLYMARKET";
            }
        }
    }

    private static float[] fetchPoly(String prefix, Model.Game g) {
        String date = etDate(g.startMs);
        String a1 = g.away.abbr.toLowerCase(Locale.US), h1 = g.home.abbr.toLowerCase(Locale.US);
        String a2 = alt(g.league, g.away.abbr).toLowerCase(Locale.US), h2 = alt(g.league, g.home.abbr).toLowerCase(Locale.US);
        String[] slugs = a1.equals(a2) && h1.equals(h2)
            ? new String[]{prefix + "-" + a1 + "-" + h1 + "-" + date}
            : new String[]{prefix + "-" + a2 + "-" + h2 + "-" + date, prefix + "-" + a1 + "-" + h1 + "-" + date};
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
                return new float[]{away / sum, home / sum};
            } catch (Exception ignored) { }
        }
        return null;
    }
}
