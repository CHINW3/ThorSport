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
        String base = API + discipline(lg.path);
        JSONArray a = new JSONObject(get(base + "&filter%5Bmatches.status%5D%5Bin%5D=current,upcoming&sort=start_date", fromCache)).optJSONArray("results");
        JSONArray b = new JSONObject(get(base + "&filter%5Bmatches.status%5D%5Bin%5D=finished&sort=-start_date", fromCache)).optJSONArray("results");
        List<Model.Game> all = new ArrayList<>();
        for (JSONArray arr : new JSONArray[]{a, b})
            for (int i = 0; arr != null && i < arr.length(); i++) {
                Model.Game g = game(lg, arr.optJSONObject(i));
                if (g != null) all.add(g);
            }
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
        return sb;
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
