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
import java.util.TimeZone;

/**
 * Formula 1 from Jolpica (the free successor of the Ergast F1 API):
 * the race weekend's sessions, the last race's results and both championships.
 */
public final class F1 {
    private F1() {}

    private static final String BASE = "https://api.jolpi.ca/ergast/f1/current";

    /** Team colors (constructorId -> color). */
    private static final Map<String, Integer> TEAM = new HashMap<>();
    static {
        TEAM.put("red_bull", 0xFF3671C6); TEAM.put("ferrari", 0xFFE8002D); TEAM.put("mercedes", 0xFF27F4D2);
        TEAM.put("mclaren", 0xFFFF8000); TEAM.put("aston_martin", 0xFF229971); TEAM.put("alpine", 0xFFFF87BC);
        TEAM.put("williams", 0xFF64C4FF); TEAM.put("rb", 0xFF6692FF); TEAM.put("racing_bulls", 0xFF6692FF);
        TEAM.put("sauber", 0xFF52E252); TEAM.put("audi", 0xFFF50537); TEAM.put("haas", 0xFFB6BABD);
        TEAM.put("cadillac", 0xFFD4AF37);
    }

    /** Short team codes for the badges. */
    private static final Map<String, String> CODE = new HashMap<>();
    /** Driver nationality (Jolpica) and circuit country -> ISO code for the flags. */
    private static final Map<String, String> ISO = new HashMap<>();
    static {
        String[] codes = {"red_bull", "RBR", "ferrari", "FER", "mercedes", "MER", "mclaren", "MCL", "aston_martin", "AMR",
            "alpine", "ALP", "williams", "WIL", "rb", "RB", "racing_bulls", "RB", "sauber", "SAU", "audi", "AUD", "haas", "HAA", "cadillac", "CAD"};
        for (int i = 0; i < codes.length; i += 2) CODE.put(codes[i], codes[i + 1]);
        String[] iso = {
            "British", "gb", "UK", "gb", "United Kingdom", "gb", "Dutch", "nl", "Netherlands", "nl", "Monegasque", "mc", "Monaco", "mc",
            "Spanish", "es", "Spain", "es", "Mexican", "mx", "Mexico", "mx", "Australian", "au", "Australia", "au",
            "Canadian", "ca", "Canada", "ca", "Thai", "th", "Japanese", "jp", "Japan", "jp", "Chinese", "cn", "China", "cn",
            "German", "de", "Germany", "de", "French", "fr", "France", "fr", "Danish", "dk", "Finnish", "fi", "American", "us",
            "USA", "us", "United States", "us", "Italian", "it", "Italy", "it", "New Zealander", "nz", "Argentine", "ar", "Argentinian", "ar",
            "Brazilian", "br", "Brazil", "br", "Belgian", "be", "Belgium", "be", "Austrian", "at", "Austria", "at", "Swiss", "ch",
            "Polish", "pl", "Russian", "ru", "Swedish", "se", "Indonesian", "id", "Hungary", "hu", "Azerbaijan", "az", "Singapore", "sg",
            "Saudi Arabia", "sa", "Bahrain", "bh", "UAE", "ae", "United Arab Emirates", "ae", "Qatar", "qa", "Portugal", "pt", "Turkey", "tr",
            "Korea", "kr", "Malaysia", "my", "India", "in", "Russia", "ru", "Vietnam", "vn", "South Africa", "za", "Madrid", "es"};
        for (int i = 0; i < iso.length; i += 2) ISO.put(iso[i], iso[i + 1]);
    }

    static String teamCode(String id, String name) {
        String c = CODE.get(id);
        if (c != null) return c;
        return name.length() > 3 ? name.substring(0, 3).toUpperCase(Locale.US) : name.toUpperCase(Locale.US);
    }

    /** Flag image for a nationality or country name ("" if unknown). */
    static String flag(String natOrCountry) {
        String iso = natOrCountry == null ? null : ISO.get(natOrCountry.trim());
        return iso == null ? "" : "https://flagcdn.com/w160/" + iso + ".png";
    }

    static int teamColor(String id) {
        Integer c = TEAM.get(id);
        return c == null ? 0xFF8B919C : c;
    }

    private static String get(String path, boolean fromCache) throws Exception {
        String url = BASE + path;
        String body = fromCache ? Espn.cached(url) : Espn.get(url);
        if (body == null) throw new Exception("No cache");
        return body;
    }

    private static long parseTime(JSONObject o) {
        if (o == null) return 0;
        String d = o.optString("date", ""), t = o.optString("time", "00:00:00Z");
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date x = f.parse(d + "T" + t.replace("Z", "") + "Z");
            return x == null ? 0 : x.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static final String[][] SESSIONS = {
        {"FirstPractice", "FP1", "Practice 1", "60"},
        {"SecondPractice", "FP2", "Practice 2", "60"},
        {"ThirdPractice", "FP3", "Practice 3", "60"},
        {"SprintQualifying", "SQ", "Sprint Qualifying", "45"},
        {"Sprint", "SPR", "Sprint", "60"},
        {"Qualifying", "Q", "Qualifying", "60"},
    };

    /** The current/next race weekend (all sessions) + the last race's results. */
    static Model.Scoreboard weekend(boolean fromCache) throws Exception {
        JSONArray races = new JSONObject(get(".json", fromCache)).optJSONObject("MRData")
            .optJSONObject("RaceTable").optJSONArray("Races");
        Model.Scoreboard sb = new Model.Scoreboard();
        if (races == null) return sb;
        long now = System.currentTimeMillis();

        // weekend = first race whose race start + 3 h is still ahead
        JSONObject wk = null;
        for (int i = 0; i < races.length(); i++) {
            JSONObject r = races.getJSONObject(i);
            if (parseTime(r) + 3 * 3_600_000L > now) { wk = r; break; }
        }

        JSONObject last = null;
        try { last = new JSONObject(get("/last/results.json", fromCache)).optJSONObject("MRData").optJSONObject("RaceTable")
                .optJSONArray("Races").optJSONObject(0); } catch (Exception ignored) { }

        if (wk != null) {
            String name = wk.optString("raceName", "Grand Prix").replace("Grand Prix", "GP");
            JSONObject circ = wk.optJSONObject("Circuit");
            String venue = circ == null ? "" : circ.optString("circuitName", "");
            JSONObject loc = circ == null ? null : circ.optJSONObject("Location");
            if (loc != null) venue += " · " + loc.optString("locality", "") + ", " + loc.optString("country", "");
            String round = tr("Round ", "Manche ") + wk.optString("round", "");
            String hostFlag = loc == null ? "" : flag(loc.optString("country", ""));
            for (String[] s : SESSIONS) {
                JSONObject so = wk.optJSONObject(s[0]);
                if (so == null) continue;
                Model.Game sg = session(wk, s[1], s[2], parseTime(so), Integer.parseInt(s[3]), name, venue, round, now);
                sg.flag = hostFlag;
                sb.games.add(sg);
            }
            Model.Game race = session(wk, "RACE", "Grand Prix", parseTime(wk), 120, name, venue, round, now);
            race.flag = hostFlag;
            // the race just finished and Jolpica already has it: show the results on the race card
            if (last != null && last.optString("round").equals(wk.optString("round"))) fillResults(race, last);
            sb.games.add(race);
        }
        if (last != null && (wk == null || !last.optString("round").equals(wk.optString("round")))) {
            Model.Game g = session(last, "LAST", tr("Last race", "Dernière course"), parseTime(last), 120,
                last.optString("raceName", "").replace("Grand Prix", "GP"), "", tr("Round ", "Manche ") + last.optString("round", ""), now);
            g.state = "post";
            JSONObject lc = last.optJSONObject("Circuit");
            JSONObject ll = lc == null ? null : lc.optJSONObject("Location");
            if (ll != null) g.flag = flag(ll.optString("country", ""));
            fillResults(g, last);
            sb.games.add(g);
        }
        // live first, then upcoming (in order), then finished
        sb.games.sort((a, b) -> {
            int ra = Espn.rank(a), rb = Espn.rank(b);
            if (ra != rb) return ra - rb;
            return ra == 2 ? Long.compare(b.startMs, a.startMs) : Long.compare(a.startMs, b.startMs);
        });
        return sb;
    }

    private static String tr(String en, String fr) { return ThorView.tr(en, fr); }

    private static Model.Game session(JSONObject race, String abbr, String name, long start, int minutes,
                                      String event, String venue, String round, long now) {
        Model.Game g = new Model.Game();
        g.id = "f1-" + race.optString("round") + "-" + abbr;
        g.sport = "racing";
        g.league = "racing/f1";
        g.event = event;
        g.venue = venue;
        g.note = round;
        g.away.abbr = abbr;
        g.away.name = name;
        g.away.color = 0xFFE10600;
        g.home.name = event;
        g.startMs = start;
        long end = start + minutes * 60_000L;
        if (start <= 0 || now < start) { g.state = "pre"; g.detail = ""; }
        else if (now < end) {
            g.state = "in";
            long left = (end - now) / 60000;
            g.detail = "~" + left + " min " + tr("left", "restantes");
            g.clock = "";
        } else { g.state = "post"; g.detail = tr("Finished", "Terminé"); }
        return g;
    }

    private static void fillResults(Model.Game g, JSONObject race) {
        JSONArray res = race.optJSONArray("Results");
        if (res == null) return;
        for (int i = 0; i < res.length() && i < 10; i++) {
            JSONObject r = res.optJSONObject(i);
            if (r == null) continue;
            JSONObject d = r.optJSONObject("Driver"), c = r.optJSONObject("Constructor");
            String code = d == null ? "" : d.optString("code", d.optString("familyName", ""));
            String fam = d == null ? "" : d.optString("familyName", "");
            String team = c == null ? "" : c.optString("name", "");
            JSONObject time = r.optJSONObject("Time");
            String gap = time != null ? time.optString("time", "") : r.optString("status", "");
            JSONObject fl = r.optJSONObject("FastestLap");
            if (fl != null && "1".equals(fl.optString("rank"))) gap += "  · FL";
            g.results.add(code + "  " + fam);
            g.resultInfo.add(team + "|" + gap + "|" + teamCode(c == null ? "" : c.optString("constructorId", ""), team));
            g.resultFlags.add(d == null ? "" : flag(d.optString("nationality", "")));
            g.resultColors.add(teamColor(c == null ? "" : c.optString("constructorId", "")));
        }
        g.state = "post";
        g.detail = tr("Finished", "Terminé");
    }

    /** Drivers' and constructors' championships. */
    static List<Model.Group> standings(boolean fromCache) throws Exception {
        List<Model.Group> out = new ArrayList<>();
        try {
            JSONArray lists = new JSONObject(get("/driverStandings.json", fromCache)).optJSONObject("MRData")
                .optJSONObject("StandingsTable").optJSONArray("StandingsLists");
            JSONArray ds = lists == null || lists.length() == 0 ? null : lists.getJSONObject(0).optJSONArray("DriverStandings");
            if (ds != null) {
                Model.Group g = new Model.Group();
                g.title = tr("Drivers", "Pilotes");
                g.cols = new String[]{tr("WINS", "VICT."), "PTS"};
                for (int i = 0; i < ds.length(); i++) {
                    JSONObject e = ds.getJSONObject(i);
                    JSONObject d = e.optJSONObject("Driver");
                    JSONArray cs = e.optJSONArray("Constructors");
                    JSONObject c = cs == null || cs.length() == 0 ? null : cs.optJSONObject(0);
                    Model.Team t = new Model.Team();
                    t.abbr = d == null ? "" : d.optString("code", "");
                    t.logo = d == null ? null : flag(d.optString("nationality", ""));
                    if (t.logo != null && t.logo.isEmpty()) t.logo = null;
                    t.name = (d == null ? "" : d.optString("familyName", "")) + (c == null ? "" : "  ·  " + c.optString("name", ""));
                    t.color = teamColor(c == null ? "" : c.optString("constructorId", ""));
                    t.vals = new String[]{e.optString("wins", "0"), e.optString("points", "0")};
                    t.rank = e.optInt("position", i + 1);
                    g.teams.add(t);
                }
                out.add(g);
            }
        } catch (Exception ignored) { }
        try {
            JSONArray lists = new JSONObject(get("/constructorStandings.json", fromCache)).optJSONObject("MRData")
                .optJSONObject("StandingsTable").optJSONArray("StandingsLists");
            JSONArray cs = lists == null || lists.length() == 0 ? null : lists.getJSONObject(0).optJSONArray("ConstructorStandings");
            if (cs != null) {
                Model.Group g = new Model.Group();
                g.title = tr("Constructors", "Constructeurs");
                g.cols = new String[]{tr("WINS", "VICT."), "PTS"};
                for (int i = 0; i < cs.length(); i++) {
                    JSONObject e = cs.getJSONObject(i);
                    JSONObject c = e.optJSONObject("Constructor");
                    String name = c == null ? "" : c.optString("name", "");
                    Model.Team t = new Model.Team();
                    t.abbr = teamCode(c == null ? "" : c.optString("constructorId", ""), name);
                    t.name = name;
                    t.color = teamColor(c == null ? "" : c.optString("constructorId", ""));
                    t.vals = new String[]{e.optString("wins", "0"), e.optString("points", "0")};
                    t.rank = e.optInt("position", i + 1);
                    g.teams.add(t);
                }
                out.add(g);
            }
        } catch (Exception ignored) { }
        if (out.isEmpty()) throw new Exception("No F1 standings");
        return out;
    }
}
