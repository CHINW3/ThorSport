package com.gregoire.thorstandings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * PLAYOFFS tab.
 * - During the postseason (or right after it): the real series / games from ESPN, grouped by round.
 * - Otherwise: "if the playoffs started today", built from the current standings with each league's format.
 */
final class Playoffs {

    static final class Slot {
        String abbr = "", name = "", logo, wins = "", abbrId = "";
        int seed, color = 0xFF3A3F4A;
        boolean won;
    }

    static final class Match {
        final Slot a = new Slot(), b = new Slot();
        String note = "";          // "BOS leads 2–1", "Final", "Sat 7:00 PM", "Play-in"…
        boolean bye;               // only `a` is used
        long firstMs;
    }

    static final class Section {
        String title = "";
        final List<Match> matches = new ArrayList<>();
        final List<Slot> list = new ArrayList<>();   // plain team list (soccer: champion, Europe, relegation)
        int listColor;
        long firstMs;
    }

    static final class Bracket {
        String title = "", subtitle = "";
        boolean live;
        final List<Section> sections = new ArrayList<>();
    }

    // ------------------------------------------------------------------ real postseason
    /** The real playoffs from ESPN, or null when the league isn't in (or just out of) its postseason. */
    static Bracket live(Espn.League lg) throws Exception {
        if (lg.mine() || lg.path.startsWith("racing") || lg.path.startsWith("esports") || Pwhl.is(lg) || Cricket.is(lg)) return null;
        // ESPN refuses date ranges, but takes a whole month (dates=YYYYMM): last 3 months + next one
        SimpleDateFormat f = new SimpleDateFormat("yyyyMM", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("America/New_York"));
        List<JSONObject> events = new ArrayList<>();
        Calendar c = Calendar.getInstance();
        c.add(Calendar.MONTH, -3);
        for (int k = 0; k < 5; k++) {
            String url = "https://site.api.espn.com/apis/site/v2/sports/" + lg.path + "/scoreboard?dates=" + f.format(c.getTime())
                + (lg.query.isEmpty() ? "" : "&" + lg.query);
            c.add(Calendar.MONTH, 1);
            try {
                JSONArray ev = new JSONObject(Espn.get(url)).optJSONArray("events");
                if (ev != null) for (int i = 0; i < ev.length(); i++) {
                    JSONObject e = ev.getJSONObject(i);
                    if (postseason(e, lg)) events.add(e);
                }
            } catch (Exception ignored) { }
        }
        if (events.isEmpty()) return null;

        long now = System.currentTimeMillis();
        boolean active = false;
        Map<String, Section> rounds = new LinkedHashMap<>();
        Map<String, Match> series = new HashMap<>();
        Map<String, int[]> wins = new HashMap<>();   // series key -> wins of a / b (counted from finals)
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));

        for (JSONObject ev : events) {
            JSONArray comps = ev.optJSONArray("competitions");
            if (comps == null || comps.length() == 0) continue;
            JSONObject comp = comps.getJSONObject(0);
            long ms = 0;
            try { ms = iso.parse(ev.optString("date", "")).getTime(); } catch (Exception ignored) { }
            JSONObject st = comp.optJSONObject("status");
            if (st == null) st = ev.optJSONObject("status");
            JSONObject type = st == null ? null : st.optJSONObject("type");
            String state = type == null ? "" : type.optString("state", "");
            String detail = type == null ? "" : type.optString("shortDetail", "");
            if (!"post".equals(state) || now - ms < 21L * 86400_000L) active = true;   // live, coming up, or ended < 3 weeks ago

            String round = "";
            JSONArray notes = comp.optJSONArray("notes");
            if (notes != null && notes.length() > 0) round = notes.getJSONObject(0).optString("headline", "");
            round = round.replaceAll("\\s*[-–]\\s*Game \\d+.*$", "").replaceAll("\\s+Game \\d+.*$", "").trim();
            JSONObject season = ev.optJSONObject("season");
            String slug = season == null ? "" : season.optString("slug", "");
            if (lg.soccer() || round.isEmpty() || round.toLowerCase(Locale.US).contains("leg")) round = pretty(slug);
            // one column per round: "East 1st Round" + "West 1st Round" -> "1st Round"
            round = round.replaceFirst("^(Eastern|Western|East|West|AFC|NFC|AL|NL|American League|National League)\\s+", "");
            if (round.isEmpty()) round = ThorView.tr("PLAYOFFS", "SÉRIES");

            JSONArray cs = comp.optJSONArray("competitors");
            if (cs == null || cs.length() < 2) continue;
            JSONObject ca = cs.getJSONObject(0), cb = cs.getJSONObject(1);
            if ("home".equals(ca.optString("homeAway"))) { JSONObject t = ca; ca = cb; cb = t; }   // away first, like the cards
            String ida = teamId(ca), idb = teamId(cb);
            if (ida.isEmpty() || idb.isEmpty()) continue;
            String key = round + "|" + (ida.compareTo(idb) < 0 ? ida + "-" + idb : idb + "-" + ida);

            Section sec = rounds.get(round);
            if (sec == null) { sec = new Section(); sec.title = round.toUpperCase(Locale.US); sec.firstMs = ms; rounds.put(round, sec); }
            sec.firstMs = Math.min(sec.firstMs, ms);

            Match m = series.get(key);
            boolean flip = false;
            if (m == null) {
                m = new Match();
                m.firstMs = ms;
                fill(m.a, ca, lg); fill(m.b, cb, lg);
                series.put(key, m); sec.matches.add(m);
                wins.put(key, new int[2]);
            } else {
                flip = !ida.equals(m.a.abbrId);
            }
            // series object (NHL / NBA / MLB…): the real wins
            JSONObject ser = comp.optJSONObject("series");
            if (ser == null) ser = ev.optJSONObject("series");
            JSONArray serC = ser == null ? null : ser.optJSONArray("competitors");
            boolean hasWins = serC != null && serC.length() > 0 && serC.optJSONObject(0) != null && serC.optJSONObject(0).has("wins");
            if (hasWins) {
                JSONArray sc = ser.optJSONArray("competitors");
                for (int k = 0; k < sc.length(); k++) {
                    JSONObject so = sc.getJSONObject(k);
                    String id = so.optString("id", "");
                    int w = so.optInt("wins", 0);
                    if (id.equals(m.a.abbrId)) m.a.wins = String.valueOf(w);
                    if (id.equals(m.b.abbrId)) m.b.wins = String.valueOf(w);
                }
                String sum = ser.optString("summary", "");
                if (!sum.isEmpty()) m.note = sum;
                if (ser.optBoolean("completed", false)) {
                    int wa = parse(m.a.wins), wb = parse(m.b.wins);
                    m.a.won = wa > wb; m.b.won = wb > wa;
                }
            } else {
                // single games (NFL, soccer, college): the score, or when it's played
                JSONObject x = flip ? cb : ca, y = flip ? ca : cb;
                if ("pre".equals(state)) {
                    m.note = detail;
                } else {
                    // two-leg ties (UCL): the aggregate of the finished legs + the one being played
                    int[] w = wins.get(key);
                    int sa = parse(x.optString("score", "")), sb = parse(y.optString("score", ""));
                    if (w != null && "post".equals(state)) { w[0] += sa; w[1] += sb; sa = w[0]; sb = w[1]; }
                    else if (w != null) { sa += w[0]; sb += w[1]; }
                    m.a.wins = String.valueOf(sa);
                    m.b.wins = String.valueOf(sb);
                    m.a.won = x.optBoolean("winner", false) || ser != null && serWinner(ser, m.a.abbrId);
                    m.b.won = y.optBoolean("winner", false) || ser != null && serWinner(ser, m.b.abbrId);
                    m.note = "post".equals(state) ? ThorView.tr("Final", "Final") : detail;
                }
            }
        }
        if (!active || rounds.isEmpty()) return null;

        Bracket b = new Bracket();
        b.live = true;
        b.title = ThorView.tr("PLAYOFFS", "SÉRIES ÉLIMINATOIRES");
        List<Section> secs = new ArrayList<>(rounds.values());
        secs.sort(Comparator.comparingLong(s -> s.firstMs));
        for (Section s : secs) s.matches.sort(Comparator.comparingLong(m -> m.firstMs));
        // keep the last 5 rounds (screen width)
        while (secs.size() > 5) secs.remove(0);
        b.sections.addAll(secs);
        return b;
    }

    /** Is this game part of the postseason? (type 3 for US leagues; for soccer, any stage that isn't the league table) */
    private static boolean postseason(JSONObject e, Espn.League lg) {
        JSONObject se = e.optJSONObject("season");
        if (se == null) return false;
        if (se.optInt("type", 0) == 3) return true;
        if (!lg.soccer()) return false;
        String slug = se.optString("slug", "");
        return !slug.isEmpty() && !slug.contains("regular") && !slug.contains("league") && !slug.contains("group");
    }

    private static boolean serWinner(JSONObject ser, String id) {
        JSONArray cs = ser.optJSONArray("competitors");
        if (cs == null) return false;
        for (int i = 0; i < cs.length(); i++) {
            JSONObject o = cs.optJSONObject(i);
            if (o != null && id.equals(o.optString("id")) && o.optBoolean("winner", false)) return true;
        }
        return false;
    }

    private static String pretty(String slug) {
        if (slug == null || slug.isEmpty() || "post-season".equals(slug)) return "";
        return slug.replace('-', ' ').toUpperCase(Locale.US);
    }

    private static String teamId(JSONObject co) {
        JSONObject t = co.optJSONObject("team");
        return t == null ? co.optString("id", "") : t.optString("id", co.optString("id", ""));
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private static void fill(Slot s, JSONObject co, Espn.League lg) {
        JSONObject t = co.optJSONObject("team");
        if (t == null) return;
        s.abbrId = t.optString("id", co.optString("id", ""));
        s.abbr = t.optString("abbreviation", "");
        s.name = t.optString("shortDisplayName", s.abbr);
        String dark = t.optString("logoDark", "");
        s.logo = OfflineLogos.wrap(lg.path, t.optString("displayName", ""), !dark.isEmpty() ? dark : t.optString("logo", null));
        try { s.color = 0xFF000000 | Integer.parseInt(t.optString("color", "3A3F4A"), 16); } catch (Exception ignored) { }
        JSONObject cr = co.optJSONObject("curatedRank");
        int rank = cr == null ? 0 : cr.optInt("current", 0);
        if (rank > 0 && rank < 99) s.seed = rank;
    }

    // ------------------------------------------------------------------ "if the playoffs started today"
    /** Built from the standings. Null when the league has no format here. */
    static Bracket project(Espn.League lg, List<Model.Group> groups) {
        if (groups == null || groups.isEmpty() || lg.mine()) return null;
        String p = lg.path;
        Bracket b = new Bracket();
        b.title = ThorView.tr("IF THE PLAYOFFS STARTED TODAY", "SI LES SÉRIES COMMENÇAIENT AUJOURD'HUI");
        switch (p) {
            case "hockey/nhl":
                for (Model.Group g : groups) b.sections.add(round(short_(g.title), seeds(g.teams), new int[][]{{1, 8}, {2, 7}, {3, 6}, {4, 5}}, null));
                b.subtitle = ThorView.tr("Top 8 of each conference · 1st round", "Top 8 de chaque association · 1re ronde");
                break;
            case "basketball/nba":
                for (Model.Group g : groups) {
                    Section s = round(short_(g.title), seeds(g.teams), new int[][]{{1, 8}, {2, 7}, {3, 6}, {4, 5}}, null);
                    if (s.matches.size() > 1) s.matches.get(0).note = ThorView.tr("8 = play-in winner", "8 = gagnant du tournoi");
                    if (s.matches.size() > 1) s.matches.get(1).note = ThorView.tr("7 = play-in winner", "7 = gagnant du tournoi");
                    b.sections.add(s);
                }
                b.subtitle = ThorView.tr("Seeds 7–10 play the play-in first", "Les 7e à 10e passent par le tournoi préliminaire");
                break;
            case "basketball/wnba":
                b.sections.add(round(ThorView.tr("1ST ROUND", "1RE RONDE"), merge(groups), new int[][]{{1, 8}, {2, 7}, {3, 6}, {4, 5}}, null));
                b.subtitle = ThorView.tr("Top 8 overall", "Top 8 au classement général");
                break;
            case "football/nfl":
                for (Model.Group g : groups) b.sections.add(round(short_(g.title), seeds(g.teams), new int[][]{{2, 7}, {3, 6}, {4, 5}}, new int[]{1}));
                b.subtitle = ThorView.tr("7 teams per conference · #1 gets a bye", "7 équipes par association · le 1er est exempté");
                break;
            case "baseball/mlb":
                for (Model.Group g : groups) b.sections.add(round(short_(g.title), seeds(g.teams), new int[][]{{3, 6}, {4, 5}}, new int[]{1, 2}));
                b.subtitle = ThorView.tr("6 teams per league · #1 and #2 get a bye", "6 équipes par ligue · les 2 premiers exemptés");
                break;
            case "soccer/usa.1":
                for (Model.Group g : groups) {
                    List<Model.Team> t = seeds(g.teams);
                    Section s = round(short_(g.title), t, new int[][]{{8, 9}, {1, 0}, {2, 7}, {3, 6}, {4, 5}}, null);
                    if (!s.matches.isEmpty()) s.matches.get(0).note = ThorView.tr("Wild card", "Match de barrage");
                    if (s.matches.size() > 1) { Slot w = s.matches.get(1).b; w.abbr = "8/9"; w.name = ThorView.tr("Wild card winner", "Gagnant du barrage"); }
                    b.sections.add(s);
                }
                b.subtitle = ThorView.tr("9 teams per conference", "9 équipes par association");
                break;
            case "hockey/pwhl":
                b.sections.add(round(ThorView.tr("SEMIFINALS", "DEMI-FINALES"), merge(groups), new int[][]{{1, 4}, {2, 3}}, null));
                b.subtitle = ThorView.tr("Top 4 · the 1st seed picks its opponent", "Top 4 · le 1er choisit son adversaire");
                break;
            case "soccer/uefa.champions": {
                List<Model.Team> t = merge(groups);
                b.title = ThorView.tr("IF THE LEAGUE PHASE ENDED TODAY", "SI LA PHASE DE LIGUE FINISSAIT AUJOURD'HUI");
                Section top = new Section();
                top.title = ThorView.tr("ROUND OF 16", "HUITIÈMES");
                for (int i = 0; i < Math.min(8, t.size()); i++) top.list.add(slot(t.get(i), i + 1));
                top.listColor = 0xFF34C759;
                b.sections.add(top);
                b.sections.add(round(ThorView.tr("KNOCKOUT PLAYOFF", "BARRAGES"), t,
                    new int[][]{{9, 24}, {10, 23}, {11, 22}, {12, 21}, {13, 20}, {14, 19}, {15, 18}, {16, 17}}, null));
                b.subtitle = ThorView.tr("Top 8 straight to the round of 16 · 9–24 play off", "Top 8 directement en 8es · 9e à 24e en barrages");
                break;
            }
            default:
                if (lg.intl) {   // national teams: who goes through from each group (and who drops down)
                    if (lg.good <= 0) return null;
                    b.title = ThorView.tr("IF THE GROUPS ENDED TODAY", "SI LES GROUPES FINISSAIENT AUJOURD'HUI");
                    List<Model.Team> up = new ArrayList<>(), down = new ArrayList<>();
                    for (Model.Group g : groups) {
                        List<Model.Team> t = g.teams;
                        for (int i = 0; i < Math.min(lg.good, t.size()); i++) up.add(t.get(i));
                        for (int i = Math.max(lg.good, t.size() - lg.bad); i < t.size(); i++) down.add(t.get(i));
                    }
                    lists(b, ThorView.tr("THROUGH", "QUALIFIÉS"), up, 0xFF34C759);
                    lists(b, ThorView.tr("RELEGATED", "RELÉGUÉS"), down, 0xFFFF3B30);
                    b.subtitle = lg.good == 1 ? ThorView.tr("Group winners", "Premiers de groupe")
                        : ThorView.tr("Top " + lg.good + " of each group", "Top " + lg.good + " de chaque groupe");
                    break;
                }
                if (lg.soccer()) {
                    List<Model.Team> t = merge(groups);
                    b.title = ThorView.tr("IF THE SEASON ENDED TODAY", "SI LA SAISON FINISSAIT AUJOURD'HUI");
                    Section ch = new Section();
                    ch.title = ThorView.tr("CHAMPION", "CHAMPION");
                    if (!t.isEmpty()) ch.list.add(slot(t.get(0), 1));
                    ch.listColor = 0xFFFFC400;
                    b.sections.add(ch);
                    if (lg.good > 1) {
                        Section eu = new Section();
                        eu.title = ThorView.tr("CHAMPIONS LEAGUE", "LIGUE DES CHAMPIONS");
                        for (int i = 0; i < Math.min(lg.good, t.size()); i++) eu.list.add(slot(t.get(i), i + 1));
                        eu.listColor = 0xFF34C759;
                        b.sections.add(eu);
                    }
                    if (lg.bad > 0) {
                        Section rel = new Section();
                        rel.title = ThorView.tr("RELEGATED", "RELÉGUÉS");
                        for (int i = Math.max(0, t.size() - lg.bad); i < t.size(); i++) rel.list.add(slot(t.get(i), i + 1));
                        rel.listColor = 0xFFFF3B30;
                        b.sections.add(rel);
                    }
                    b.subtitle = ThorView.tr("From the current table", "D'après le classement actuel");
                    break;
                }
                return null;   // college, F1, LoL: nothing to project
        }
        return b.sections.isEmpty() ? null : b;
    }

    /** Team lists of at most 12 rows per column (a World Cup has 24 teams going through). */
    private static void lists(Bracket b, String title, List<Model.Team> t, int color) {
        int cols = (t.size() + 11) / 12;
        for (int k = 0; k < cols; k++) {
            Section s = new Section();
            s.title = title;
            int from = k * t.size() / cols, to = (k + 1) * t.size() / cols;
            for (int i = from; i < to; i++) s.list.add(slot(t.get(i), 0));
            s.listColor = color;
            b.sections.add(s);
        }
    }

    private static Section round(String title, List<Model.Team> t, int[][] pairs, int[] byes) {
        Section s = new Section();
        s.title = title;
        if (byes != null) for (int seed : byes) {
            if (seed > t.size()) continue;
            Match m = new Match();
            m.bye = true;
            copy(m.a, t.get(seed - 1), seed);
            m.note = ThorView.tr("BYE", "EXEMPTÉ");
            s.matches.add(m);
        }
        for (int[] pr : pairs) {
            if (pr[0] > t.size() || pr[1] > t.size()) continue;
            Match m = new Match();
            copy(m.a, t.get(pr[0] - 1), pr[0]);
            if (pr[1] > 0) copy(m.b, t.get(pr[1] - 1), pr[1]);
            s.matches.add(m);
        }
        return s;
    }

    private static Slot slot(Model.Team t, int seed) { Slot s = new Slot(); copy(s, t, seed); return s; }

    private static void copy(Slot s, Model.Team t, int seed) {
        s.abbr = t.abbr; s.name = t.name; s.logo = t.logo; s.seed = seed;
        s.wins = t.vals != null && t.vals.length > 0 ? t.vals[t.vals.length - 1] : "";   // the table's main number (PTS / PCT)
    }

    /** Teams in seed order: ESPN's playoff seed when it gives one, else the table order. */
    private static List<Model.Team> seeds(List<Model.Team> teams) {
        List<Model.Team> t = new ArrayList<>(teams);
        boolean seeded = false;
        for (Model.Team x : t) if (x.seed > 0) { seeded = true; break; }
        if (seeded) t.sort((a, b) -> Integer.compare(a.seed > 0 ? a.seed : 999, b.seed > 0 ? b.seed : 999));
        else t.sort((a, b) -> Integer.compare(a.rank, b.rank));
        return t;
    }

    /** Every group in one list, best first (does not touch the standings objects). */
    private static List<Model.Team> merge(List<Model.Group> groups) {
        if (groups.size() == 1) return seeds(groups.get(0).teams);
        List<Model.Team> all = new ArrayList<>();
        for (Model.Group g : groups) all.addAll(g.teams);
        all.sort((a, b) -> {
            int c = Double.compare(b.sortKey, a.sortKey);
            return c != 0 ? c : Double.compare(b.tieKey, a.tieKey);
        });
        return all;
    }

    private static String short_(String title) {
        if (title == null) return "";
        String t = title.replace("American Football Conference", "AFC").replace("National Football Conference", "NFC")
            .replace("American League", "AL").replace("National League", "NL")
            .replace(" Conference", "").replace("Conference ", "");
        return t.toUpperCase(Locale.US);
    }
}
