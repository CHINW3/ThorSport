package com.gregoire.thorstandings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PWHL isn't on ESPN, so it comes from HockeyTech (the feed behind thepwhl.com),
 * using the public key embedded in the league's website.
 */
public final class Pwhl {
    private Pwhl() {}

    private static final String BASE = "https://lscluster.hockeytech.com/feed/index.php?feed=modulekit"
        + "&key=446521baf8c38984&client_code=pwhl&fmt=json&lang=en";

    /** Team colors (HockeyTech doesn't send them). Unknown teams fall back to grey. */
    private static final Map<String, String> COLORS = new HashMap<>();
    static {
        COLORS.put("BOS", "1E8C5A"); COLORS.put("MIN", "6B3FA0"); COLORS.put("MTL", "9B2335");
        COLORS.put("NY", "2BB3B1");  COLORS.put("OTT", "D32F3A"); COLORS.put("TOR", "3A7BD5");
        COLORS.put("SEA", "3F8F8A"); COLORS.put("VAN", "E0B341");
    }

    static boolean is(Espn.League lg) { return lg.path.endsWith("/pwhl"); }

    /** Bigger logo first, the 50x50 one as a backup (LogoCache tries each, separated by "|"). */
    private static String logo(String teamId, String given) {
        String big = "https://assets.leaguestat.com/pwhl/logos/" + teamId + ".png";
        String small = given != null && !given.isEmpty() ? given : "https://assets.leaguestat.com/pwhl/logos/50x50/" + teamId + ".png";
        return big + "|" + small;
    }

    private static int color(String code) {
        String c = COLORS.get(code);
        return Espn.pickColor(c == null ? "" : c, "");
    }

    // ------------------------------------------------------------------ scores
    static Model.Scoreboard scores(String date, boolean fromCache) throws Exception {
        String url = BASE + "&view=scorebar&numberofdaysback=8&numberofdaysahead=8";
        String body = fromCache ? Espn.cached(url) : Espn.get(url);
        if (body == null) throw new Exception("No cache");
        JSONObject kit = new JSONObject(body).optJSONObject("SiteKit");
        JSONArray list = kit == null ? null : kit.optJSONArray("Scorebar");

        String want = date != null
            ? date.substring(0, 4) + "-" + date.substring(4, 6) + "-" + date.substring(6, 8)
            : new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);

        Model.Scoreboard sb = new Model.Scoreboard();
        if (list == null) return sb;
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null || !want.equals(o.optString("Date"))) continue;
            Model.Game g = new Model.Game();
            g.id = "pwhl" + o.optString("ID");
            g.sport = "hockey";
            g.league = "hockey/pwhl";
            try {
                Date d = iso.parse(o.optString("GameDateISO8601", ""));
                if (d != null) g.startMs = d.getTime();
            } catch (Exception ignored) { }

            int status = o.optInt("GameStatus", 1);
            String per = o.optString("PeriodNameShort", "");
            g.period = o.optInt("Period", 0);
            g.clock = o.optString("GameClock", "");
            if (status == 1) {
                g.state = "pre";
                g.detail = o.optString("GameStatusString", "");
            } else if (status == 4) {
                g.state = "post";
                g.detail = per.startsWith("OT") ? "Final/OT" : per.startsWith("SO") ? "Final/SO" : "Final";
            } else {
                g.state = "in";
                g.detail = g.clock.isEmpty() ? o.optString("GameStatusString", per) : g.clock + " - " + per;
            }
            g.tv = "";

            fill(g.home, o, "Home");
            fill(g.away, o, "Visitor");
            int hg = g.home.scoreInt(), vg = g.away.scoreInt();
            if (g.isFinal() && hg >= 0 && vg >= 0) { g.home.winner = hg > vg; g.away.winner = vg > hg; }
            sb.games.add(g);
        }
        sb.games.sort((a, b) -> {
            int ra = Espn.rank(a), rb = Espn.rank(b);
            return ra != rb ? ra - rb : Long.compare(a.startMs, b.startMs);
        });
        return sb;
    }

    private static void fill(Model.Side s, JSONObject o, String side) {
        s.abbr = o.optString(side + "Code", "");
        s.name = o.optString(side + "Nickname", s.abbr);
        s.logo = logo(o.optString(side + "ID", ""), o.optString(side + "Logo", ""));
        s.color = color(s.abbr);
        s.ledA = s.color;
        s.ledB = 0xFFFFFFFF;
        s.score = o.optInt("GameStatus", 1) == 1 ? "" : o.optString(side + "Goals", "");
        s.record = o.optString(side + "Wins", "0") + "-" + o.optString(side + "RegulationLosses", "0")
            + "-" + o.optString(side + "OTLosses", "0");
    }

    // ------------------------------------------------------------------ standings
    static List<Model.Group> standings(Espn.League lg, boolean fromCache) throws Exception {
        String url = BASE + "&view=statviewtype&type=standings&stat=conference";
        String body = fromCache ? Espn.cached(url) : Espn.get(url);
        if (body == null) throw new Exception("No cache");
        JSONObject kit = new JSONObject(body).optJSONObject("SiteKit");
        JSONArray rows = kit == null ? null : kit.optJSONArray("Statviewtype");
        if (rows == null) throw new Exception("No standings");

        Model.Group g = new Model.Group();
        g.title = "PWHL";
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null || r.optString("team_code", "").isEmpty()) continue; // header rows
            Model.Team t = new Model.Team();
            t.abbr = r.optString("team_code");
            String full = r.optString("name", t.abbr);
            int sp = full.lastIndexOf(' ');
            t.name = sp > 0 ? full.substring(sp + 1) : full;
            t.logo = logo(r.optString("team_id", ""), null);
            t.vals = new String[lg.statNames.length];
            for (int k = 0; k < lg.statNames.length; k++) {
                String v = r.optString(lg.statNames[k], "");
                t.vals[k] = v.isEmpty() ? "-" : v;
            }
            t.sortKey = num(r.optString(lg.sortStat, "0"));
            t.tieKey = num(r.optString(lg.tieStat, "0"));
            // keep the league's own order when points are tied
            t.tieKey = t.tieKey * 1000 - i;
            g.teams.add(t);
        }
        g.teams.sort((a, b) -> {
            int c = Double.compare(b.sortKey, a.sortKey);
            return c != 0 ? c : Double.compare(b.tieKey, a.tieKey);
        });
        for (int r = 0; r < g.teams.size(); r++) g.teams.get(r).rank = r + 1;
        List<Model.Group> out = new ArrayList<>();
        if (!g.teams.isEmpty()) out.add(g);
        if (out.isEmpty()) throw new Exception("No standings");
        return out;
    }

    private static double num(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return 0; }
    }
}
