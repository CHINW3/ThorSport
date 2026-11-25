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
 * The PWHL and the AHL aren't on ESPN, so they come from HockeyTech (the feed behind thepwhl.com and theahl.com),
 * using the public key embedded in each league's website. (The class keeps its old name; it handles both leagues.)
 */
public final class Pwhl {
    private Pwhl() {}

    /** The three leagues of this class: 0 = PWHL, 1 = AHL, 2 = QMJHL (the Quebec junior league). */
    private static int kind(Espn.League lg) { return lg.path.endsWith("/ahl") ? 1 : lg.path.endsWith("/qmjhl") ? 2 : lg.path.endsWith("/ohl") ? 3 : 0; }

    private static String base(int k) {
        return "https://lscluster.hockeytech.com/feed/index.php?feed=modulekit&key="
            + (k == 1 ? "ccb91f29d6744675&client_code=ahl" : k == 2 ? "f322673b6bcae299&client_code=lhjmq" : k == 3 ? "2976319eb44abe94&client_code=ohl" : "446521baf8c38984&client_code=pwhl") + "&fmt=json&lang=en";
    }

    private static String folder(int k) { return k == 1 ? "ahl" : k == 2 ? "lhjmq" : k == 3 ? "ohl" : "pwhl"; }

    /** OHL teams. */
    private static final Map<String, String> OHL_COLORS = new HashMap<>();
    static {
        String[][] c = {{"BFD", "C8102E"}, {"SBY", "F28C28"}, {"KGN", "CE1126"}, {"PBO", "00843D"}, {"NB", "C8102E"}, {"OTT", "A6192E"},
            {"NIAG", "00A3E0"}, {"OSH", "C8102E"}, {"BRAM", "1F5FBF"}, {"BAR", "F2A900"}, {"GUE", "1F4E9A"}, {"WSR", "1F5FBF"},
            {"KIT", "1F5FBF"}, {"SOO", "00843D"}, {"FLNT", "F28C28"}, {"ER", "1F5FBF"}, {"LDN", "00843D"}, {"SAR", "C8102E"},
            {"SAG", "0072CE"}, {"OS", "7A1F2B"}};
        for (String[] x : c) OHL_COLORS.put(x[0], x[1]);
    }

    /** Team colors (HockeyTech doesn't send them). Unknown teams fall back to grey. */
    private static final Map<String, String> COLORS = new HashMap<>();
    static {
        COLORS.put("BOS", "1E8C5A"); COLORS.put("MIN", "6B3FA0"); COLORS.put("MTL", "5E0F17");
        COLORS.put("NY", "2BB3B1");  COLORS.put("OTT", "D32F3A"); COLORS.put("TOR", "3A7BD5");
        COLORS.put("SEA", "3F8F8A"); COLORS.put("VAN", "E0B341");
    }

    /** AHL teams (mostly the colors of the NHL team they belong to). */
    private static final Map<String, String> AHL_COLORS = new HashMap<>();
    static {
        String[][] c = {{"ABB", "1F6FB2"}, {"BAK", "F26522"}, {"BEL", "C8102E"}, {"CGY", "C8102E"}, {"CHI", "1E7B4C"}, {"CLE", "CE1126"},
            {"CLT", "C8102E"}, {"COL", "236192"}, {"CV", "E8452C"}, {"GR", "CE1126"}, {"HAM", "AF1E2D"}, {"HER", "8B5A2B"},
            {"HFD", "0038A8"}, {"HSK", "B4975A"}, {"IA", "2A7F4F"}, {"LAV", "AF1E2D"}, {"LV", "F74902"}, {"MB", "2F6EB5"},
            {"MIL", "FFB81C"}, {"ONT", "9EA2A2"}, {"PRO", "FFB81C"}, {"RFD", "CF0A2C"}, {"ROC", "0A59B5"}, {"SD", "F47A38"},
            {"SJ", "006D75"}, {"SPR", "C8102E"}, {"SYR", "00549F"}, {"TEX", "006847"}, {"TOR", "1F5FBF"}, {"TUC", "71AFE5"},
            {"UTC", "CE1126"}, {"WBS", "FCB514"}};
        for (String[] x : c) AHL_COLORS.put(x[0], x[1]);
    }

    private static final Map<String, int[]> LEDS = new HashMap<>();
    static { LEDS.put("MTL", new int[]{0xFFFF0000, 0xFF0000B0}); }   // Victoire: red + navy, no pink
    private static final Map<String, int[]> AHL_LEDS = new HashMap<>();
    static {
        AHL_LEDS.put("HSK", new int[]{0xFFFFB000, 0xFFC8D8E4});   // Silver Knights: gold + steel
        AHL_LEDS.put("HFD", new int[]{0xFF0038FF, 0xFFD00000});   // Wolf Pack: blue + red
        AHL_LEDS.put("HER", new int[]{0xFFFF7A00, 0xFFFFFFFF});   // Bears: brown shows as orange, not mud
    }

    /** QMJHL teams (the feed gives no colors). */
    private static final Map<String, String> QMJHL_COLORS = new HashMap<>();
    static {
        String[][] c = {{"QUE", "C8102E"}, {"SNB", "1F5FBF"}, {"NFL", "2C3E7B"}, {"HAL", "B5121B"}, {"CAP", "0072CE"}, {"CHI", "00843D"},
            {"MON", "8A1538"}, {"RIM", "3A9BDC"}, {"CHA", "1D4F91"}, {"BAC", "2BA84A"}, {"VDO", "1E8449"}, {"VIC", "F28C28"},
            {"ROU", "00A6D6"}, {"GAT", "2B6CB0"}, {"SHE", "1C4B9C"}, {"DRU", "1F4E9A"}, {"BLB", "6A2C91"}, {"SHA", "0A7E3E"}};
        for (String[] x : c) QMJHL_COLORS.put(x[0], x[1]);
    }

    static boolean is(Espn.League lg) { return lg.path.endsWith("/pwhl") || lg.path.endsWith("/ahl") || lg.path.endsWith("/qmjhl") || lg.path.endsWith("/ohl"); }

    /** Bigger logo first, the 50x50 one as a backup (LogoCache tries each, separated by "|"). */
    private static final Map<String, String> SEEN = new HashMap<>();   // league + team id -> this season's logo (from the scores feed)

    private static String logo(int k, String teamId, String given) {
        String dir = folder(k);
        if (k == 2 && "19".equals(teamId)) return "asset:teams/lhjmq/blb.png|https://assets.leaguestat.com/lhjmq/logos/19.png";   // Blainville-Boisbriand Armada: the feed's logo is low quality
        if (k == 2 && "60".equals(teamId)) return "asset:teams/lhjmq/she.png|https://assets.leaguestat.com/lhjmq/logos/60.png";   // Sherbrooke Phoenix, packed
        if (k == 0 && "3".equals(teamId)) return "asset:teams/pwhl/mtl.png|https://assets.leaguestat.com/pwhl/logos/3_5.png";   // Montréal Victoire, packed
        String key = dir + teamId;
        // the season's own logo first (e.g. 3_5.png): the plain "<id>.png" is the old logo (Montréal's was too pink)
        if ((given == null || given.isEmpty()) && SEEN.containsKey(key)) given = SEEN.get(key);
        if (given != null && !given.isEmpty()) {
            SEEN.put(key, given);
            String full = given.replace("/50x50/", "/");
            return full + "|" + given + "|https://assets.leaguestat.com/" + dir + "/logos/" + teamId + ".png";
        }
        String big = "https://assets.leaguestat.com/" + dir + "/logos/" + teamId + ".png";
        String small = given != null && !given.isEmpty() ? given : "https://assets.leaguestat.com/" + dir + "/logos/50x50/" + teamId + ".png";
        return big + "|" + small;
    }

    private static int color(int k, String code) {
        String c = (k == 1 ? AHL_COLORS : k == 2 ? QMJHL_COLORS : k == 3 ? OHL_COLORS : COLORS).get(code.toUpperCase(Locale.ROOT));
        return Espn.pickColor(c == null ? "" : c, "");
    }

    // ------------------------------------------------------------------ scores
    static Model.Scoreboard scores(Espn.League lg, String date, boolean fromCache) throws Exception {
        int a = kind(lg);
        String url = base(a) + "&view=scorebar&numberofdaysback=" + (a != 0 ? 6 : 8) + "&numberofdaysahead=" + (a != 0 ? 6 : 8);
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
            g.id = folder(a) + o.optString("ID");
            g.sport = "hockey";
            g.league = lg.path;
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

            fill(g.home, o, "Home", a);
            fill(g.away, o, "Visitor", a);
            int hg = g.home.scoreInt(), vg = g.away.scoreInt();
            if (g.isFinal() && hg >= 0 && vg >= 0) { g.home.winner = hg > vg; g.away.winner = vg > hg; }
            sb.games.add(g);
        }
        sb.games.sort((x, y) -> {
            int rx = Espn.rank(x), ry = Espn.rank(y);
            return rx != ry ? rx - ry : Long.compare(x.startMs, y.startMs);
        });
        return sb;
    }

    private static void fill(Model.Side s, JSONObject o, String side, int ahl) {
        s.abbr = o.optString(side + "Code", "");
        if (ahl >= 2) s.abbr = s.abbr.toUpperCase(Locale.ROOT);   // the junior feed sends "Mon", "Que"...
        s.name = o.optString(side + "Nickname", s.abbr);
        s.logo = logo(ahl, o.optString(side + "ID", ""), o.optString(side + "Logo", ""));
        s.color = color(ahl, s.abbr);
        s.ledA = s.color;
        s.ledB = 0xFFFFFFFF;
        int[] led = (ahl == 1 ? AHL_LEDS : ahl == 0 ? LEDS : new HashMap<String, int[]>()).get(s.abbr);   // LEDs wash dark/mixed colors out (burgundy looked pink): use pure ones
        if (led != null) { s.ledA = led[0]; s.ledB = led[1]; }
        s.score = o.optInt("GameStatus", 1) == 1 ? "" : o.optString(side + "Goals", "");
        s.record = o.optString(side + "Wins", "0") + "-" + o.optString(side + "RegulationLosses", "0")
            + "-" + o.optString(side + "OTLosses", "0") + (ahl != 0 ? "-" + o.optString(side + "ShootoutLosses", "0") : "");
    }

    // ------------------------------------------------------------------ standings
    /** PWHL: one table. AHL: one table per division (the playoff line is the top 4 of each). */
    static List<Model.Group> standings(Espn.League lg, boolean fromCache) throws Exception {
        int kd = kind(lg);
        boolean a = kd != 0;   // AHL (divisions) and QMJHL (conferences): one table per group
        String url = base(kd) + "&view=statviewtype&type=standings&stat=" + (kd == 1 ? "division" : "conference");
        String body = fromCache ? Espn.cached(url) : Espn.get(url);
        if (body == null) throw new Exception("No cache");
        JSONObject kit = new JSONObject(body).optJSONObject("SiteKit");
        JSONArray rows = kit == null ? null : kit.optJSONArray("Statviewtype");
        if (rows == null) throw new Exception("No standings");

        List<Model.Group> out = new ArrayList<>();
        Model.Group cur = null;
        if (!a) { cur = new Model.Group(); cur.title = "PWHL"; out.add(cur); }
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null) continue;
            if (r.optString("team_code", "").isEmpty()) {   // header rows
                String title = r.optString("name", "");
                if (a && !title.isEmpty() && !"null".equals(title)) { cur = new Model.Group(); cur.title = title; out.add(cur); }
                continue;
            }
            if (cur == null) continue;
            if (r.optString("name", "").startsWith("-")) continue;   // guest teams from other leagues in the junior table
            Model.Team t = new Model.Team();
            t.abbr = r.optString("team_code");
            if (kd >= 2) t.abbr = t.abbr.toUpperCase(Locale.ROOT);
            String full = r.optString("name", t.abbr);
            int sp = full.lastIndexOf(' ');
            String nick = r.optString("nickname", "");
            t.name = !nick.isEmpty() && !"null".equals(nick) ? nick : sp > 0 ? full.substring(sp + 1) : full;
            t.logo = logo(kd, r.optString("team_id", ""), null);
            t.vals = new String[lg.statNames.length];
            for (int k = 0; k < lg.statNames.length; k++) t.vals[k] = value(r, lg.statNames[k]);
            if (a) {   // the league's own order (points percentage, then the tiebreakers)
                t.sortKey = 1000 - r.optInt("rank", i);
                t.tieKey = 0;
            } else {
                t.sortKey = num(r.optString(lg.sortStat, "0"));
                t.tieKey = num(r.optString(lg.tieStat, "0")) * 1000 - i;   // keep the league's own order when points are tied
            }
            cur.teams.add(t);
        }
        List<Model.Group> ok = new ArrayList<>();
        for (Model.Group g : out) {
            g.teams.sort((x, y) -> {
                int c = Double.compare(y.sortKey, x.sortKey);
                return c != 0 ? c : Double.compare(y.tieKey, x.tieKey);
            });
            for (int r = 0; r < g.teams.size(); r++) g.teams.get(r).rank = r + 1;
            if (!g.teams.isEmpty()) ok.add(g);
        }
        if (ok.isEmpty()) throw new Exception("No standings");
        return ok;
    }

    /** One column of a standings row. "otl_all" = overtime + shootout losses; "percentage" without the leading zero (.700). */
    private static String value(JSONObject r, String stat) {
        if ("otl_all".equals(stat)) return String.valueOf(r.optInt("ot_losses", 0) + r.optInt("shootout_losses", 0));
        String v = r.optString(stat, "");
        if (v.isEmpty() || "null".equals(v)) return "-";
        if ("percentage".equals(stat) && v.startsWith("0.")) return v.substring(1);
        return v;
    }

    private static double num(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return 0; }
    }
}
