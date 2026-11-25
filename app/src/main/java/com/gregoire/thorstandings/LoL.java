package com.gregoire.thorstandings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * League of Legends esports, from the feed behind lolesports.com
 * (schedule + live game stats), using the public key built into that website.
 */
public final class LoL {
    private LoL() {}

    private static final String API = "https://esports-api.lolesports.com/persisted/gw/";
    private static final String KEY = "0TvQnueqKa5mxJntVWt0w4LpLfEkrV1Ta8rQBb9Z";
    private static final int[] PALETTE = {0xFF4DA3FF, 0xFFFF4D5E, 0xFF34C759, 0xFFB57BFF, 0xFFFFB524, 0xFF2BE0D8, 0xFFFF8A3D, 0xFFE0E0E0};

    static boolean is(Espn.League lg) { return lg.path.startsWith("esports/lol"); }

    private static String get(String path, boolean fromCache) throws Exception {
        String url = API + path;
        String body = fromCache ? Espn.cached(url) : Espn.get(url, "x-api-key", KEY);
        if (body == null) throw new Exception("No cache");
        return body;
    }

    private static int colorFor(String code) {
        return PALETTE[Math.abs(code.hashCode()) % PALETTE.length];
    }

    /** Matches for that day (yyyyMMdd, null = today); today with nothing on shows the next ones. */
    static Model.Scoreboard scores(String date, boolean fromCache) throws Exception { return scores(date, fromCache, false); }

    /** everything = every match of the schedule, not only that day (for the team list). */
    static Model.Scoreboard scores(String date, boolean fromCache, boolean everything) throws Exception {
        String hl = ThorView.fr ? "fr-FR" : "en-US";
        JSONObject root = new JSONObject(get("getSchedule?hl=" + hl, fromCache));
        JSONObject sched = root.optJSONObject("data") == null ? null : root.optJSONObject("data").optJSONObject("schedule");
        JSONArray events = sched == null ? null : sched.optJSONArray("events");
        Model.Scoreboard sb = new Model.Scoreboard();
        if (events == null) return sb;

        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd", Locale.US);
        String want = date != null ? date : day.format(new Date());
        long now = System.currentTimeMillis();

        java.util.List<Model.Game> all = new java.util.ArrayList<>();
        for (int i = 0; i < events.length(); i++) {
            JSONObject ev = events.optJSONObject(i);
            if (ev == null || !"match".equals(ev.optString("type", "match"))) continue;
            JSONObject match = ev.optJSONObject("match");
            JSONArray teams = match == null ? null : match.optJSONArray("teams");
            if (teams == null || teams.length() < 2) continue;
            Model.Game g = new Model.Game();
            g.id = "lol" + match.optString("id", String.valueOf(i));
            g.sport = "esports";
            g.league = "esports/lol";
            try { Date d = iso.parse(ev.optString("startTime", "").replaceAll("\\.\\d+Z$", "Z")); if (d != null) g.startMs = d.getTime(); }
            catch (Exception ignored) { }
            String st = ev.optString("state", "unstarted");
            g.state = "inProgress".equals(st) ? "in" : "completed".equals(st) ? "post" : "pre";
            JSONObject strat = match.optJSONObject("strategy");
            int bo = strat == null ? 1 : strat.optInt("count", 1);
            JSONObject lg = ev.optJSONObject("league");
            g.note = (lg == null ? "" : lg.optString("name", "")) + (ev.optString("blockName", "").isEmpty() ? "" : " · " + ev.optString("blockName"));
            g.event = "BO" + bo;
            g.period = bo;
            fill(g.away, teams.optJSONObject(0));
            fill(g.home, teams.optJSONObject(1));
            g.lastPlay = match.optString("id", "");   // keeps the match id for live stats
            int aw = g.away.scoreInt(), hw = g.home.scoreInt();
            if (g.isFinal()) { g.detail = ThorView.tr("Final", "Final") + " · BO" + bo; g.away.winner = aw > hw; g.home.winner = hw > aw; }
            else if (g.isLive()) g.detail = "BO" + bo + " · " + ThorView.tr("Game ", "Partie ") + (Math.max(0, aw) + Math.max(0, hw) + 1);
            else g.detail = "BO" + bo;
            all.add(g);
        }
        if (everything) { sb.games.addAll(all); return sb; }
        for (Model.Game g : all) if (g.startMs > 0 && want.equals(day.format(new Date(g.startMs))) || g.isLive()) sb.games.add(g);
        if (sb.games.isEmpty() && date == null) {   // nothing today: the next matches
            for (Model.Game g : all) if (g.startMs > now && sb.games.size() < 8) sb.games.add(g);
        }
        sb.games.sort((a, b) -> {
            int ra = Espn.rank(a), rb = Espn.rank(b);
            return ra != rb ? ra - rb : Long.compare(a.startMs, b.startMs);
        });
        return sb;
    }

    /** The teams to pick in My teams: every team of the schedule. */
    static java.util.List<Model.Team> teams() {
        java.util.Map<String, Model.Team> seen = new java.util.LinkedHashMap<>();
        for (boolean cache : new boolean[]{true, false}) {
            try {
                for (Model.Game g : scores(null, cache, true).games)
                    for (Model.Side s : new Model.Side[]{g.away, g.home}) {
                        if (s.abbr == null || s.abbr.isEmpty() || seen.containsKey(s.abbr)) continue;
                        Model.Team t = new Model.Team();
                        t.abbr = s.abbr; t.name = s.name; t.logo = s.logo;
                        seen.put(s.abbr, t);
                    }
            } catch (Exception ignored) { }
            if (!seen.isEmpty()) break;
        }
        return new java.util.ArrayList<>(seen.values());
    }

    private static void fill(Model.Side s, JSONObject t) {
        if (t == null) return;
        s.abbr = t.optString("code", "");
        s.name = t.optString("name", s.abbr);
        String img = t.optString("image", "");
        s.logo = img.startsWith("http://") ? "https://" + img.substring(7) : img;
        s.color = colorFor(s.abbr);
        s.ledA = s.color;
        s.ledB = 0xFFFFFFFF;
        JSONObject res = t.optJSONObject("result");
        s.score = res == null ? "" : String.valueOf(res.optInt("gameWins", 0));
        JSONObject rec = t.optJSONObject("record");
        if (rec != null) s.record = rec.optInt("wins", 0) + "-" + rec.optInt("losses", 0);
    }

    /** Live stats of the game being played in this match: gold, kills, towers, dragons, barons, inhibitors. */
    static void liveStats(Model.Game g) {
        if (g == null || !g.isLive() || g.lastPlay.isEmpty()) return;
        try {
            JSONObject det = new JSONObject(get("getEventDetails?hl=en-US&id=" + g.lastPlay, false));
            JSONObject match = det.optJSONObject("data").optJSONObject("event").optJSONObject("match");
            JSONArray games = match.optJSONArray("games");
            JSONArray teams = match.optJSONArray("teams");
            String awayId = teams.optJSONObject(0).optString("id", "");
            String gameId = null;
            for (int i = 0; games != null && i < games.length(); i++) {
                JSONObject gm = games.optJSONObject(i);
                if (gm != null && "inProgress".equals(gm.optString("state"))) { gameId = gm.optString("id"); break; }
            }
            if (gameId == null) return;
            JSONObject win = new JSONObject(Espn.get("https://feed.lolesports.com/livestats/v1/window/" + gameId));
            JSONArray frames = win.optJSONArray("frames");
            if (frames == null || frames.length() == 0) return;
            JSONObject f = frames.optJSONObject(frames.length() - 1);
            JSONObject meta = win.optJSONObject("gameMetadata");
            String blueId = meta == null || meta.optJSONObject("blueTeamMetadata") == null ? "" : meta.optJSONObject("blueTeamMetadata").optString("esportsTeamId", "");
            boolean awayBlue = blueId.equals(awayId);
            JSONObject blue = f.optJSONObject("blueTeam"), red = f.optJSONObject("redTeam");
            JSONObject a = awayBlue ? blue : red, h = awayBlue ? red : blue;
            g.lol = new int[][]{stats(a), stats(h)};
            g.lolAwayBlue = awayBlue;
            g.lolState = f.optString("gameState", "in_game");
        } catch (Exception ignored) { }
    }

    /** {gold, kills, towers, dragons, barons, inhibitors} */
    private static int[] stats(JSONObject t) {
        if (t == null) return new int[6];
        JSONArray dr = t.optJSONArray("dragons");
        return new int[]{t.optInt("totalGold", 0), t.optInt("totalKills", 0), t.optInt("towers", 0),
            dr == null ? 0 : dr.length(), t.optInt("barons", 0), t.optInt("inhibitors", 0)};
    }
}
