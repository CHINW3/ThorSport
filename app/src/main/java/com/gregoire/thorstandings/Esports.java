package com.gregoire.thorstandings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The other esports of the ESPORTS folder (Counter-Strike 2, Valorant, Dota 2, Rainbow Six Siege), from bo3.gg's open match feed.
 * League of Legends has its own, richer feed: see LoL.java.
 */
final class Esports {
    private Esports() {}

    static boolean is(Espn.League lg) { return lg.path.startsWith("esports/") && !LoL.is(lg); }

    private static final String API = "https://api.bo3.gg/api/v1/matches?with=teams,tournament&page%5Blimit%5D=100&filter%5Bmatches.discipline_id%5D%5Beq%5D=";
    private static final int[] PALETTE = {0xFF4DA3FF, 0xFFFF4D5E, 0xFF34C759, 0xFFB57BFF, 0xFFFFB524, 0xFF2BE0D8, 0xFFFF8A3D, 0xFFE0E0E0};

    private static int discipline(String path) {
        switch (path) {
            case "esports/valorant": return 2;
            case "esports/dota2": return 4;
            case "esports/r6": return 7;
            default: return 1;   // esports/cs2
        }
    }

    private static String get(String url, boolean fromCache) throws Exception {
        String body = fromCache ? Espn.cached(url) : Espn.get(url);
        if (body == null) throw new Exception("No cache");
        return body;
    }

    static Model.Scoreboard scores(Espn.League lg, String date, boolean fromCache) throws Exception {
        List<Model.Game> all = allGames(lg, fromCache);
        SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd", Locale.US);
        String want = date != null ? date : day.format(new Date());
        long now = System.currentTimeMillis();
        Model.Scoreboard sb = new Model.Scoreboard();
        for (Model.Game g : all) if ((g.startMs > 0 && want.equals(day.format(new Date(g.startMs)))) || g.isLive()) sb.games.add(g);
        if (sb.games.isEmpty() && date == null) {   // nothing today: the next matches
            for (Model.Game g : all) if (g.startMs > now && sb.games.size() < 8) sb.games.add(g);
        }
        sb.games.sort((x, y) -> {
            int rx = Espn.rank(x), ry = Espn.rank(y);
            return rx != ry ? rx - ry : Long.compare(x.startMs, y.startMs);
        });
        for (Model.Game g : sb.games) if (g.isLive() || g.isFinal()) extras(g);
        return sb;
    }

    private static final java.util.Map<String, String> mapsDone = new java.util.concurrent.ConcurrentHashMap<>();   // finished matches never change

    /** Map results (CS2 / Valorant / R6), and for Dota 2 the live kills and gold lead. */
    private static void extras(Model.Game g) {
        try {
            String parts = g.headline;
            if (!g.league.equals("esports/dota2")) {
                String maps = mapsDone.get(g.id);
                if (maps == null) {
                    maps = maps(g);
                    if (g.isFinal()) mapsDone.put(g.id, maps);
                }
                if (!maps.isEmpty()) parts = parts.isEmpty() ? maps : maps + "  ·  " + parts;
            } else if (g.isLive()) {
                String live = dotaLive(g);
                if (!live.isEmpty()) parts = parts.isEmpty() ? live : live + "  ·  " + parts;
            }
            g.headline = parts;
        } catch (Exception ignored) { }
    }

    private static boolean same(String a, String b) {
        a = a.toLowerCase(Locale.ROOT).trim(); b = b.toLowerCase(Locale.ROOT).trim();
        return !a.isEmpty() && !b.isEmpty() && (a.equals(b) || a.contains(b) || b.contains(a));
    }

    private static String maps(Model.Game g) throws Exception {
        String body = Espn.getFresh("https://api.bo3.gg/api/v1/games?filter%5Bgames.match_id%5D%5Beq%5D=" + g.id.substring(3) + "&sort=number");
        JSONArray arr = new JSONObject(body).optJSONArray("results");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null || !"finished".equals(m.optString("status"))) continue;
            int w = m.optInt("winner_clan_score", -1), l = m.optInt("loser_clan_score", -1);
            if (w < 0 || l < 0) continue;
            String wn = m.optString("winner_clan_name", "");
            boolean awayWon = same(wn, g.away.name);
            if (!awayWon && !same(wn, g.home.name)) continue;
            String map = m.optString("map_name", "").replaceFirst("^de_", "").replaceFirst("^cs_", "");
            if (!map.isEmpty()) map = Character.toUpperCase(map.charAt(0)) + map.substring(1);
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(map).append(' ').append(awayWon ? w : l).append('-').append(awayWon ? l : w);
        }
        return sb.toString();
    }

    private static String dotaLive(Model.Game g) throws Exception {
        JSONArray arr = new JSONArray(Espn.getFresh("https://api.opendota.com/api/live"));
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null || m.optInt("league_id") <= 0) continue;
            String r = m.optString("team_name_radiant", ""), d = m.optString("team_name_dire", "");
            boolean radAway = same(r, g.away.name) && same(d, g.home.name), radHome = same(r, g.home.name) && same(d, g.away.name);
            if (!radAway && !radHome) continue;
            int rk = m.optInt("radiant_score"), dk = m.optInt("dire_score"), lead = m.optInt("radiant_lead");
            int ak = radAway ? rk : dk, hk = radAway ? dk : rk, aLead = radAway ? lead : -lead;
            int mins = m.optInt("game_time") / 60;
            String who = aLead >= 0 ? g.away.abbr : g.home.abbr;
            return ThorView.tr("Kills ", "Éliminations ") + ak + "-" + hk + "  ·  " + who + " +" + String.format(Locale.US, "%.1fk", Math.abs(aLead) / 1000.0) + " gold  ·  " + mins + " min";
        }
        return "";
    }

    private static List<Model.Game> allGames(Espn.League lg, boolean fromCache) throws Exception {
        String base = API + discipline(lg.path);
        JSONArray a = new JSONObject(get(base + "&filter%5Bmatches.status%5D%5Bin%5D=current,upcoming&sort=start_date", fromCache)).optJSONArray("results");
        JSONArray b = new JSONObject(get(base + "&filter%5Bmatches.status%5D%5Bin%5D=finished&sort=-start_date", fromCache)).optJSONArray("results");
        List<Model.Game> all = new ArrayList<>();
        for (JSONArray arr : new JSONArray[]{a, b})
            for (int i = 0; arr != null && i < arr.length(); i++) {
                Model.Game g = game(lg, arr.optJSONObject(i));
                if (g != null) all.add(g);
            }
        return all;
    }

    /** The teams to pick in My teams: every team of the recent and coming matches. */
    static List<Model.Team> teams(Espn.League lg) {
        java.util.Map<String, Model.Team> seen = new java.util.LinkedHashMap<>();
        for (boolean cache : new boolean[]{true, false}) {
            try {
                for (Model.Game g : allGames(lg, cache))
                    for (Model.Side s : new Model.Side[]{g.away, g.home}) {
                        if (s.abbr == null || s.abbr.isEmpty() || seen.containsKey(s.abbr)) continue;
                        Model.Team t = new Model.Team();
                        t.abbr = s.abbr; t.name = s.name; t.logo = s.logo;
                        seen.put(s.abbr, t);
                    }
            } catch (Exception ignored) { }
            if (!seen.isEmpty()) break;
        }
        return new ArrayList<>(seen.values());
    }

    private static Model.Game game(Espn.League lg, JSONObject m) {
        if (m == null) return null;
        JSONObject t1 = m.optJSONObject("team1"), t2 = m.optJSONObject("team2");
        if (t1 == null || t2 == null) return null;   // a bracket slot still waiting for its teams
        Model.Game g = new Model.Game();
        g.id = "bo3" + m.optString("id");
        g.sport = "esports";
        g.league = lg.path;
        try {
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = iso.parse(m.optString("start_date", "").substring(0, 19));
            if (d != null) g.startMs = d.getTime();
        } catch (Exception ignored) { }
        String st = m.optString("status", "upcoming");
        g.state = "current".equals(st) ? "in" : "finished".equals(st) ? "post" : "pre";
        int bo = m.optInt("bo_type", 1);
        JSONObject tour = m.optJSONObject("tournament");
        g.note = tour == null ? "" : tour.optString("name", "");
        g.event = "BO" + bo;
        g.period = bo;
        fill(g.away, t1, m.optInt("team1_score", 0));
        fill(g.home, t2, m.optInt("team2_score", 0));
        JSONObject bet = m.optJSONObject("bet_updates");
        if (bet != null && !g.isFinal()) {
            JSONObject b1 = bet.optJSONObject("team_1"), b2 = bet.optJSONObject("team_2");
            double c1 = b1 == null ? 0 : b1.optDouble("coeff", 0), c2 = b2 == null ? 0 : b2.optDouble("coeff", 0);
            if (c1 > 1 && c2 > 1) {   // bookmaker odds -> win chance (the margin is split out)
                double p1 = 1 / c1, p2 = 1 / c2;
                g.headline = ThorView.tr("Odds ", "Cotes ") + g.away.abbr + " " + Math.round(100 * p1 / (p1 + p2)) + "%  ·  " + g.home.abbr + " " + Math.round(100 * p2 / (p1 + p2)) + "%";
            }
        }
        long win = m.optLong("winner_team_id", 0);
        int aw = g.away.scoreInt(), hw = g.home.scoreInt();
        if (g.isFinal()) {
            g.detail = ThorView.tr("Final", "Final") + " · BO" + bo;
            g.away.winner = win != 0 ? win == t1.optLong("id") : aw > hw;
            g.home.winner = win != 0 ? win == t2.optLong("id") : hw > aw;
        } else if (g.isLive()) g.detail = "BO" + bo + " · " + ThorView.tr("Map ", "Carte ") + (aw + hw + 1);
        else g.detail = "BO" + bo;
        return g;
    }

    private static void fill(Model.Side s, JSONObject t, int score) {
        s.name = t.optString("name", "");
        String n = s.name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", "").trim();
        s.abbr = n.length() <= 4 ? n : n.indexOf(' ') > 0 ? initials(n) : n.substring(0, 3);
        String img = t.optString("image_url", "");
        s.logo = img.isEmpty() || "null".equals(img) ? null : img;
        s.color = PALETTE[Math.abs(s.name.hashCode()) % PALETTE.length];
        s.ledA = s.color;
        s.ledB = 0xFFFFFFFF;
        s.score = String.valueOf(score);
    }

    private static String initials(String n) {
        StringBuilder sb = new StringBuilder();
        for (String w : n.split(" ")) if (!w.isEmpty() && sb.length() < 4) sb.append(w.charAt(0));
        return sb.toString();
    }
}
