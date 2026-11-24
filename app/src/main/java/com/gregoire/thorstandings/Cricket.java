package com.gregoire.thorstandings;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Cricket: every match ESPN lists right now (Tests, ODIs, T20s, domestic leagues),
 * from the header feed. Scores are text like "202 & 144 (50.2 ov)".
 */
final class Cricket {
    private Cricket() {}

    static boolean is(Espn.League lg) { return lg.path.startsWith("cricket"); }

    private static final String URL = "https://site.web.api.espn.com/apis/v2/scoreboard/header?sport=cricket&lang=en&region=gb";

    static Model.Scoreboard scores(String date, boolean fromCache) throws Exception {
        String url = URL + (date == null ? "" : "&dates=" + date);
        String body = fromCache ? Espn.cached(url) : Espn.get(url);
        if (body == null) throw new Exception("No cache");
        Model.Scoreboard sb = new Model.Scoreboard();
        JSONArray sports = new JSONObject(body).optJSONArray("sports");
        if (sports == null || sports.length() == 0) return sb;
        JSONArray leagues = sports.getJSONObject(0).optJSONArray("leagues");
        if (leagues == null) return sb;
        for (int l = 0; l < leagues.length(); l++) {
            JSONObject lg = leagues.getJSONObject(l);
            String lgName = lg.optString("abbreviation", lg.optString("name", ""));
            JSONArray events = lg.optJSONArray("events");
            if (events == null) continue;
            for (int i = 0; i < events.length(); i++) {
                JSONObject ev = events.getJSONObject(i);
                JSONArray comps = ev.optJSONArray("competitors");
                if (comps == null || comps.length() < 2) continue;
                Model.Game g = new Model.Game();
                g.id = ev.optString("id");
                g.sport = "cricket";
                g.league = "cricket/" + lg.optString("id");
                g.note = lgName;
                g.event = lgName;
                JSONObject fs = ev.optJSONObject("fullStatus");
                JSONObject type = fs == null ? null : fs.optJSONObject("type");
                if (type == null) type = ev.optJSONObject("status");
                g.state = type == null ? ev.optString("status", "pre") : type.optString("state", "pre");
                String summary = ev.optString("summary", "");
                g.detail = !summary.isEmpty() ? summary : type == null ? "" : type.optString("shortDetail", type.optString("detail", ""));
                g.headline = summary;
                for (int k = 0; k < 2; k++) {
                    JSONObject co = comps.getJSONObject(k);
                    boolean home = "home".equals(co.optString("homeAway")) || (k == 1 && !co.has("homeAway"));
                    Model.Side s = home ? g.home : g.away;
                    s.abbr = co.optString("abbreviation", "");
                    s.name = co.optString("shortDisplayName", co.optString("displayName", s.abbr));
                    s.teamId = co.optString("id", "");
                    s.logo = co.optString("logo", null);
                    s.score = co.optString("score", "");
                    s.winner = co.optBoolean("winner", false);
                    String c = co.optString("color", "");
                    try { if (c.length() == 6) s.color = 0xFF000000 | Integer.parseInt(c, 16); } catch (Exception ignored) { }
                }
                sb.games.add(g);
            }
        }
        return sb;
    }
}
