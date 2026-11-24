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

    /** Flag colors of the cricket nations (stick lights + card tint). Also works for "India A", "England Lions"... */
    private static final java.util.Map<String, int[]> FLAGS = new java.util.HashMap<>();
    private static void f(String key, int a, int b) { FLAGS.put(key, new int[]{0xFF000000 | a, 0xFF000000 | b}); }
    static {
        f("india", 0xFF9933, 0x138808);        f("australia", 0x00843D, 0xFFCD00);
        f("england", 0xFFFFFF, 0xCE1124);      f("new zealand", 0x000000, 0xFFFFFF);
        f("pakistan", 0x01411C, 0xFFFFFF);     f("south africa", 0x007749, 0xFFB81C);
        f("sri lanka", 0x8D153A, 0xFFBE29);    f("west indies", 0x7B0041, 0xFFC72C);
        f("bangladesh", 0x006A4E, 0xF42A41);   f("afghanistan", 0x000000, 0xD32011);
        f("ireland", 0x169B62, 0xFF883E);      f("zimbabwe", 0x006400, 0xFFD200);
        f("netherlands", 0xAE1C28, 0x21468B);  f("scotland", 0x005EB8, 0xFFFFFF);
        f("namibia", 0x003580, 0xD21034);      f("nepal", 0xDC143C, 0x003893);
        f("united arab emirates", 0x00732F, 0xFF0000); f("oman", 0xDB161B, 0x008000);
        f("united states", 0xB22234, 0x3C3B6E); f("usa", 0xB22234, 0x3C3B6E);
        f("canada", 0xFF0000, 0xFFFFFF);       f("hong kong", 0xDE2910, 0xFFFFFF);
        // hockey nations (Olympic feeds come without team colors)
        f("sweden", 0x006AA7, 0xFECC00);       f("finland", 0x002F6C, 0xFFFFFF);
        f("czech", 0x11457E, 0xD7141A);        f("switzerland", 0xDA291C, 0xFFFFFF);
        f("slovakia", 0x0B4EA2, 0xEE1C25);     f("germany", 0xDD0000, 0xFFCE00);
        f("latvia", 0x9E3039, 0xFFFFFF);       f("denmark", 0xC8102E, 0xFFFFFF);
        f("italy", 0x009246, 0xCE2B37);        f("france", 0x0055A4, 0xEF4135);
        f("norway", 0xBA0C2F, 0x00205B);       f("austria", 0xED2939, 0xFFFFFF);
        f("japan", 0xBC002D, 0xFFFFFF);        f("china", 0xEE1C25, 0xFFFF00);
        f("slovenia", 0x005DA4, 0xED1C24);     f("hungary", 0xCE2939, 0x477050);
        f("kazakhstan", 0x00AFCA, 0xFEC50C);   f("great britain", 0x012169, 0xC8102E);
    }
    private static final java.util.Map<String, String> ABBR = new java.util.HashMap<>();
    static {
        String[][] a = {{"IND","india"},{"AUS","australia"},{"ENG","england"},{"NZ","new zealand"},{"PAK","pakistan"},{"SA","south africa"},
            {"SL","sri lanka"},{"WI","west indies"},{"BAN","bangladesh"},{"AFG","afghanistan"},{"IRE","ireland"},{"ZIM","zimbabwe"},
            {"NED","netherlands"},{"SCO","scotland"},{"NAM","namibia"},{"NEP","nepal"},{"UAE","united arab emirates"},{"OMA","oman"},
            {"USA","united states"},{"CAN","canada"},{"HK","hong kong"},
            {"SWE","sweden"},{"FIN","finland"},{"CZE","czech"},{"SUI","switzerland"},{"SVK","slovakia"},{"GER","germany"},
            {"LAT","latvia"},{"DEN","denmark"},{"ITA","italy"},{"FRA","france"},{"NOR","norway"},{"AUT","austria"},
            {"JPN","japan"},{"CHN","china"},{"SLO","slovenia"},{"HUN","hungary"},{"KAZ","kazakhstan"},{"GBR","great britain"}};
        for (String[] x : a) ABBR.put(x[0], x[1]);
    }
    static int[] flag(String abbr, String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        for (java.util.Map.Entry<String, int[]> e : FLAGS.entrySet()) if (n.startsWith(e.getKey())) return e.getValue();
        String base = abbr.toUpperCase(java.util.Locale.ROOT).split("[- ]")[0];
        String k = ABBR.get(base);
        return k == null ? null : FLAGS.get(k);
    }

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
                    String sc = co.optString("score", "").trim();   // "202 & 144 (50.2 ov) (f/o)" -> big "202 & 144", small "50.2 ov · f/o"
                    int par = sc.indexOf(" (");
                    s.score = par > 0 ? sc.substring(0, par).trim() : sc;
                    s.record = par > 0 ? sc.substring(par).replace(") (", " · ").replace("(", "").replace(")", "").trim() : "";
                    s.winner = co.optBoolean("winner", false);
                    String c = co.optString("color", "");
                    try { if (c.length() == 6) s.color = 0xFF000000 | Integer.parseInt(c, 16); } catch (Exception ignored) { }
                    int[] flag = flag(s.abbr, co.optString("displayName", ""));
                    if (flag != null) { s.color = flag[0]; s.ledA = flag[0]; s.ledB = flag[1]; }
                    else { s.ledA = s.color; s.ledB = s.color; }
                }
                sb.games.add(g);
            }
        }
        return sb;
    }
}
