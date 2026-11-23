package com.gregoire.thorstandings;

import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Reads ESPN's free public JSON feeds (no API key needed). */
public final class Espn {
    private Espn() {}

    /** One league = ESPN path + which standings stats to show. The LAST column is the ranking stat. */
    public static class League {
        public final String label, path, sortStat, tieStat;
        public final String[] colLabels, statNames;
        public int cut, good, bad; // playoff line after rank `cut`; soccer: top `good` green, bottom `bad` red
        League(String label, String path, String[] colLabels, String[] statNames, String sortStat, String tieStat) {
            this.label = label; this.path = path; this.colLabels = colLabels;
            this.statNames = statNames; this.sortStat = sortStat; this.tieStat = tieStat;
        }
        League zones(int cut, int good, int bad) { this.cut = cut; this.good = good; this.bad = bad; return this; }
        public boolean soccer() { return path.startsWith("soccer"); }
        public boolean mine() { return "mine".equals(path); }
    }

    private static final String[] SOC_COLS = {"GP", "W", "D", "L", "GD", "PTS"};
    private static final String[] SOC_STATS = {"gamesPlayed", "wins", "ties", "losses", "pointDifferential", "points"};

    private static League soccer(String label, String path, int good, int bad) {
        return new League(label, path, SOC_COLS, SOC_STATS, "points", "pointDifferential").zones(0, good, bad);
    }

    public static final League[] LEAGUES = {
        new League("★ MY TEAMS", "mine", new String[0], new String[0], "", ""),
        new League("NHL", "hockey/nhl",
            new String[]{"GP", "W", "L", "OT", "STRK", "PTS"},
            new String[]{"gamesPlayed", "wins", "losses", "otLosses", "streak", "points"}, "points", "wins").zones(8, 0, 0),
        new League("PWHL", "hockey/pwhl",
            new String[]{"GP", "W", "L", "OTL", "STRK", "PTS"},
            new String[]{"games_played", "wins", "losses", "ot_losses", "streak", "points"}, "points", "regulation_wins").zones(4, 0, 0),
        new League("NBA", "basketball/nba",
            new String[]{"W", "L", "GB", "STRK", "PCT"},
            new String[]{"wins", "losses", "gamesBehind", "streak", "winPercent"}, "winPercent", "wins").zones(6, 0, 0),
        new League("NFL", "football/nfl",
            new String[]{"W", "L", "T", "STRK", "PCT"},
            new String[]{"wins", "losses", "ties", "streak", "winPercent"}, "winPercent", "wins").zones(7, 0, 0),
        new League("MLB", "baseball/mlb",
            new String[]{"W", "L", "GB", "STRK", "PCT"},
            new String[]{"wins", "losses", "gamesBehind", "streak", "winPercent"}, "winPercent", "wins").zones(6, 0, 0),
        new League("WNBA", "basketball/wnba",
            new String[]{"W", "L", "GB", "STRK", "PCT"},
            new String[]{"wins", "losses", "gamesBehind", "streak", "winPercent"}, "winPercent", "wins").zones(4, 0, 0),
        new League("F1", "racing/f1",
            new String[]{"PTS"}, new String[]{"championshipPts"}, "championshipPts", "championshipPts"),
        new League("LOL", "esports/lol", new String[0], new String[0], "", ""),
        soccer("EPL", "soccer/eng.1", 4, 3),
        soccer("MLS", "soccer/usa.1", 0, 0).zones(9, 0, 0),
        soccer("UCL", "soccer/uefa.champions", 8, 12),
        soccer("LA LIGA", "soccer/esp.1", 4, 3),
        soccer("BUNDESLIGA", "soccer/ger.1", 4, 2),
        soccer("SERIE A", "soccer/ita.1", 4, 3),
        soccer("LIGUE 1", "soccer/fra.1", 4, 3),
    };

    /** Folder where the last good answer of every feed is kept (instant start + offline). */
    public static java.io.File cacheDir;

    private static java.io.File cacheFile(String url) {
        return cacheDir == null ? null : new java.io.File(cacheDir, "feed_" + Integer.toHexString(url.hashCode()) + ".json");
    }

    /** Last saved copy of a feed, or null. */
    static String cached(String url) {
        try {
            java.io.File f = cacheFile(url);
            if (f == null || !f.exists()) return null;
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    static String get(String url) throws Exception { return get(url, null, null); }

    static String get(String url, String header, String value) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        if (header != null) con.setRequestProperty(header, value);
        con.setConnectTimeout(10000);
        con.setReadTimeout(15000);
        con.setRequestProperty("User-Agent", "Mozilla/5.0 ThorStandings");
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        } finally {
            con.disconnect();
        }
        String body = sb.toString();
        try {
            java.io.File f = cacheFile(url);
            if (f != null) java.nio.file.Files.write(f.toPath(), body.getBytes("UTF-8"));
        } catch (Exception ignored) { }
        return body;
    }

    static String standingsUrl(League lg) { return "https://site.api.espn.com/apis/v2/sports/" + lg.path + "/standings"; }

    static String scoresUrl(League lg, String date) {
        return "https://site.api.espn.com/apis/site/v2/sports/" + lg.path + "/scoreboard" + (date == null ? "" : "?dates=" + date);
    }

    // ------------------------------------------------------------------ standings
    public static List<Model.Group> standings(League lg, boolean fromCache) throws Exception {
        if (Pwhl.is(lg)) return Pwhl.standings(lg, fromCache);
        String body = fromCache ? cached(standingsUrl(lg)) : get(standingsUrl(lg));
        if (body == null) throw new Exception("No cache");
        JSONObject root = new JSONObject(body);
        JSONArray children = root.optJSONArray("children");
        List<JSONObject> nodes = new ArrayList<>();
        if (children != null && children.length() > 0) {
            for (int i = 0; i < children.length(); i++) nodes.add(children.getJSONObject(i));
        } else {
            nodes.add(root);
        }

        List<Model.Group> out = new ArrayList<>();
        for (JSONObject node : nodes) {
            JSONObject st = node.optJSONObject("standings");
            JSONArray entries = st == null ? null : st.optJSONArray("entries");
            if (entries == null) continue;

            Model.Group g = new Model.Group();
            g.title = node.optString("name", lg.label);
            for (int i = 0; i < entries.length(); i++) {
                JSONObject e = entries.getJSONObject(i);
                JSONObject team = e.optJSONObject("team");
                JSONObject athlete = e.optJSONObject("athlete");
                if (team == null && athlete == null) continue;

                Map<String, String> disp = new HashMap<>();
                Map<String, Double> num = new HashMap<>();
                JSONArray stats = e.optJSONArray("stats");
                if (stats != null) {
                    for (int s = 0; s < stats.length(); s++) {
                        JSONObject so = stats.getJSONObject(s);
                        String name = so.optString("name");
                        if (name.isEmpty() || disp.containsKey(name)) continue;
                        disp.put(name, so.optString("displayValue", "-"));
                        num.put(name, so.optDouble("value", 0));
                    }
                }

                Model.Team t = new Model.Team();
                if (team != null) {
                    t.abbr = team.optString("abbreviation", "");
                    t.name = team.optString("shortDisplayName", team.optString("displayName", t.abbr));
                    t.logo = pickLogo(team.optJSONArray("logos"));
                } else {  // F1 drivers
                    t.abbr = athlete.optString("abbreviation", "");
                    t.name = athlete.optString("shortName", athlete.optString("displayName", t.abbr));
                    JSONObject flag = athlete.optJSONObject("flag");
                    t.logo = flag == null ? null : flag.optString("href", null);
                }
                t.vals = new String[lg.statNames.length];
                for (int k = 0; k < lg.statNames.length; k++) {
                    String v = disp.get(lg.statNames[k]);
                    t.vals[k] = (v == null || v.isEmpty()) ? "-" : v;
                }
                Double sk = num.get(lg.sortStat), tk = num.get(lg.tieStat);
                t.sortKey = sk == null ? 0 : sk;
                t.tieKey = tk == null ? 0 : tk;
                g.teams.add(t);
            }
            g.teams.sort((a, b) -> {
                int c = Double.compare(b.sortKey, a.sortKey);
                return c != 0 ? c : Double.compare(b.tieKey, a.tieKey);
            });
            for (int r = 0; r < g.teams.size(); r++) g.teams.get(r).rank = r + 1;
            if (!g.teams.isEmpty()) out.add(g);
        }
        if (out.isEmpty()) throw new Exception("No standings");
        return out;
    }

    /** Prefer the logo made for dark backgrounds. */
    private static String pickLogo(JSONArray logos) {
        if (logos == null || logos.length() == 0) return null;
        String first = null;
        for (int i = 0; i < logos.length(); i++) {
            JSONObject l = logos.optJSONObject(i);
            if (l == null) continue;
            String href = l.optString("href", null);
            if (first == null) first = href;
            JSONArray rel = l.optJSONArray("rel");
            if (rel != null && rel.toString().contains("\"dark\"")) return href;
        }
        return first;
    }

    // ------------------------------------------------------------------ scores
    /** date = "yyyyMMdd" or null for today. */
    public static Model.Scoreboard scores(League lg, String date, boolean fromCache) throws Exception {
        if (Pwhl.is(lg)) return Pwhl.scores(date, fromCache);
        String url = scoresUrl(lg, date);
        String body = fromCache ? cached(url) : get(url);
        if (body == null) throw new Exception("No cache");
        JSONObject root = new JSONObject(body);
        Model.Scoreboard sb = new Model.Scoreboard();
        JSONArray leagues = root.optJSONArray("leagues");
        if (leagues != null && leagues.length() > 0) sb.leagueLogo = pickLogo(leagues.getJSONObject(0).optJSONArray("logos"));

        JSONArray events = root.optJSONArray("events");
        if (events == null) return sb;
        String sport = lg.path.split("/")[0];
        if ("racing".equals(sport)) { racing(events, sb); for (Model.Game g : sb.games) g.league = lg.path; return sb; }

        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));

        for (int i = 0; i < events.length(); i++) {
            JSONObject ev = events.getJSONObject(i);
            JSONArray comps = ev.optJSONArray("competitions");
            if (comps == null || comps.length() == 0) continue;
            JSONObject comp = comps.getJSONObject(0);

            Model.Game g = new Model.Game();
            g.id = ev.optString("id", comp.optString("id", String.valueOf(i)));
            g.sport = sport;
            g.league = lg.path;
            JSONObject status = comp.optJSONObject("status");
            if (status == null) status = ev.optJSONObject("status");
            if (status != null) {
                g.period = status.optInt("period", 0);
                g.clock = status.optString("displayClock", "");
                JSONObject type = status.optJSONObject("type");
                if (type != null) {
                    g.state = type.optString("state", "pre");
                    g.detail = type.optString("shortDetail", type.optString("detail", ""));
                }
            }
            try {
                Date d = iso.parse(ev.optString("date", ""));
                if (d != null) g.startMs = d.getTime();
            } catch (Exception ignored) { }

            JSONObject situation = comp.optJSONObject("situation");
            String possessionId = "";
            if (situation != null) {
                JSONObject lp = situation.optJSONObject("lastPlay");
                if (lp != null) g.lastPlay = lp.optString("text", "");
                g.downText = situation.optString("downDistanceText", "");
                g.possessionText = situation.optString("possessionText", "");
                g.down = situation.optInt("down", -1);
                g.toGo = situation.optInt("distance", -1);
                g.redZone = situation.optBoolean("isRedZone", false);
                g.awayTimeouts = situation.optInt("awayTimeouts", -1);
                g.homeTimeouts = situation.optInt("homeTimeouts", -1);
                possessionId = situation.optString("possession", "");
                g.balls = situation.optInt("balls", -1);
                g.strikes = situation.optInt("strikes", -1);
                g.outs = situation.optInt("outs", -1);
                String[] keys = {"onFirst", "onSecond", "onThird"};
                for (int b = 0; b < 3; b++) {   // either true/false or the runner (with athlete) depending on the feed
                    Object o = situation.opt(keys[b]);
                    if (o instanceof Boolean) g.bases[b] = (Boolean) o;
                    else if (o instanceof JSONObject) { g.bases[b] = true; g.runners[b] = athleteName((JSONObject) o); }
                }
                JSONObject bat = situation.optJSONObject("batter"), pit = situation.optJSONObject("pitcher");
                if (bat != null) { g.batter = athleteName(bat); g.batterInfo = bat.optString("summary", ""); }
                if (pit != null) { g.pitcher = athleteName(pit); g.pitcherInfo = pit.optString("summary", ""); }
            }
            JSONObject series = comp.optJSONObject("series");
            if (series != null) g.series = series.optString("summary", "");
            JSONArray notes = comp.optJSONArray("notes");
            if (notes != null && notes.length() > 0 && notes.optJSONObject(0) != null)
                g.note = notes.optJSONObject(0).optString("headline", "");
            JSONObject venue = comp.optJSONObject("venue");
            if (venue != null) g.venue = venue.optString("fullName", "");
            JSONArray bc = comp.optJSONArray("broadcasts");
            if (bc != null && bc.length() > 0) {
                JSONArray names = bc.getJSONObject(0).optJSONArray("names");
                if (names != null && names.length() > 0) g.tv = names.optString(0, "");
            }
            JSONArray heads = comp.optJSONArray("headlines");
            if (heads != null && heads.length() > 0) {
                JSONObject hd = heads.getJSONObject(0);
                g.headline = hd.optString("shortLinkText", hd.optString("description", ""));
            }

            JSONArray cs = comp.optJSONArray("competitors");
            if (cs == null) continue;
            for (int c = 0; c < cs.length(); c++) {
                JSONObject co = cs.getJSONObject(c);
                Model.Side s = "home".equals(co.optString("homeAway")) ? g.home : g.away;
                JSONObject t = co.optJSONObject("team");
                if (t != null) {
                    s.abbr = t.optString("abbreviation", "");
                    s.name = t.optString("shortDisplayName", t.optString("name", s.abbr));
                    String dark = t.optString("logoDark", "");
                    s.logo = !dark.isEmpty() ? dark : t.optString("logo", null);
                    s.color = pickColor(t.optString("color", ""), t.optString("alternateColor", ""));
                    s.ledA = parse(t.optString("color", ""));
                    s.ledB = parse(t.optString("alternateColor", ""));
                    s.teamId = t.optString("id", "");
                }
                JSONArray lead = co.optJSONArray("leaders");
                if (lead != null && lead.length() > 0) {
                    JSONObject cat = lead.optJSONObject(0);
                    JSONArray ls0 = cat == null ? null : cat.optJSONArray("leaders");
                    JSONObject top = ls0 == null || ls0.length() == 0 ? null : ls0.optJSONObject(0);
                    JSONObject ath = top == null ? null : top.optJSONObject("athlete");
                    if (ath != null) s.leader = ath.optString("shortName", ath.optString("displayName", "")) + " " + top.optString("displayValue", "");
                }
                s.score = co.optString("score", "");
                s.winner = co.optBoolean("winner", false);
                JSONArray recs = co.optJSONArray("records");
                if (recs != null && recs.length() > 0) s.record = recs.getJSONObject(0).optString("summary", "");
                JSONArray ls = co.optJSONArray("linescores");
                if (ls != null) {
                    for (int k = 0; k < ls.length(); k++) {
                        JSONObject l = ls.optJSONObject(k);
                        if (l != null) s.lines.add(l.optString("displayValue", String.valueOf((int) l.optDouble("value", 0))));
                    }
                }
                JSONArray st = co.optJSONArray("statistics");
                if (st != null) {
                    for (int k = 0; k < st.length(); k++) {
                        JSONObject so = st.optJSONObject(k);
                        if (so != null && "saves".equals(so.optString("name"))) {
                            try { s.saves = (int) Double.parseDouble(so.optString("displayValue", "-1")); }
                            catch (Exception ignored) { }
                        }
                    }
                }
            }
            if (!possessionId.isEmpty())
                g.possession = possessionId.equals(g.home.teamId) ? "home" : possessionId.equals(g.away.teamId) ? "away" : "";
            if ("football".equals(sport)) footballSpot(g);

            // Soccer goals and red cards (and any sport that lists scoring details)
            JSONArray det = comp.optJSONArray("details");
            if (det != null) {
                for (int k = 0; k < det.length(); k++) {
                    JSONObject d = det.optJSONObject(k);
                    boolean red = d != null && d.optBoolean("redCard", false);
                    if (d == null || !d.optBoolean("scoringPlay", false) && !red) continue;
                    Model.Goal goal = new Model.Goal();
                    goal.red = red;
                    JSONObject tm = d.optJSONObject("team");
                    goal.home = tm != null && tm.optString("id", "").equals(g.home.teamId);
                    JSONObject clk = d.optJSONObject("clock");
                    goal.time = clk == null ? "" : clk.optString("displayValue", "");
                    JSONArray who = d.optJSONArray("athletesInvolved");
                    JSONObject a0 = who == null || who.length() == 0 ? null : who.optJSONObject(0);
                    goal.who = a0 == null ? "Goal" : a0.optString("shortName", a0.optString("displayName", "Goal"));
                    if (d.optBoolean("penaltyKick", false)) goal.extra = "PEN";
                    if (d.optBoolean("ownGoal", false)) goal.extra = "OG";
                    g.goals.add(goal);
                }
            }
            // Betting odds from ESPN (fallback when Polymarket has nothing)
            JSONArray odds = comp.optJSONArray("odds");
            if (odds != null && odds.length() > 0) readOdds(g, odds.optJSONObject(0));

            // Hockey shots on goal = your goals + the other goalie's saves.
            if ("hockey".equals(sport) && !"pre".equals(g.state)) {
                if (g.home.saves >= 0 && g.away.scoreInt() >= 0) g.away.shots = g.away.scoreInt() + g.home.saves;
                if (g.away.saves >= 0 && g.home.scoreInt() >= 0) g.home.shots = g.home.scoreInt() + g.away.saves;
            }
            sb.games.add(g);
        }
        // Live games first, then upcoming (by start time), then finished.
        sb.games.sort((a, b) -> {
            int ra = rank(a), rb = rank(b);
            if (ra != rb) return ra - rb;
            return Long.compare(a.startMs, b.startMs);
        });
        return sb;
    }

    /** F1: every session (FP1, Qualifying, Race…) of each Grand Prix becomes one card. */
    private static void racing(JSONArray events, Model.Scoreboard sb) throws Exception {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        for (int i = 0; i < events.length(); i++) {
            JSONObject ev = events.getJSONObject(i);
            String eventName = ev.optString("shortName", ev.optString("name", "Grand Prix"));
            JSONObject circuit = ev.optJSONObject("circuit");
            String circuitName = circuit == null ? "" : circuit.optString("fullName", "");
            JSONArray comps = ev.optJSONArray("competitions");
            if (comps == null) continue;
            for (int k = 0; k < comps.length(); k++) {
                JSONObject comp = comps.getJSONObject(k);
                Model.Game g = new Model.Game();
                g.id = ev.optString("id") + "-" + comp.optString("id", String.valueOf(k));
                g.sport = "racing";
                g.event = eventName.replace("Grand Prix", "GP");
                g.venue = circuitName;
                JSONObject type = comp.optJSONObject("type");
                String session = type == null ? "" : type.optString("abbreviation", "");
                g.away.name = session;
                g.away.abbr = session.toUpperCase();
                g.home.name = g.event;
                JSONObject status = comp.optJSONObject("status");
                if (status != null) {
                    JSONObject st = status.optJSONObject("type");
                    if (st != null) {
                        g.state = st.optString("state", "pre");
                        g.detail = st.optString("shortDetail", st.optString("detail", ""));
                    }
                    g.clock = status.optString("displayClock", "");
                }
                try {
                    Date d = iso.parse(comp.optString("date", ""));
                    if (d != null) g.startMs = d.getTime();
                } catch (Exception ignored) { }
                JSONArray bc = comp.optJSONArray("broadcasts");
                if (bc != null && bc.length() > 0) {
                    JSONArray names = bc.getJSONObject(0).optJSONArray("names");
                    if (names != null && names.length() > 0) g.tv = names.optString(0, "");
                }
                JSONArray cs = comp.optJSONArray("competitors");
                if (cs != null) {
                    List<JSONObject> order = new ArrayList<>();
                    for (int c = 0; c < cs.length(); c++) order.add(cs.getJSONObject(c));
                    order.sort((a, b) -> a.optInt("order", 999) - b.optInt("order", 999));
                    for (JSONObject co : order) {
                        JSONObject ath = co.optJSONObject("athlete");
                        if (ath == null) continue;
                        g.results.add(ath.optString("shortName", ath.optString("displayName", "?")));
                        if (g.results.size() >= 10) break;
                    }
                }
                g.away.color = 0xFFE10600; // F1 red
                g.home.color = 0xFF3A3F4A;
                sb.games.add(g);
            }
        }
        sb.games.sort((a, b) -> {
            int ra = rank(a), rb = rank(b);
            if (ra != rb) return ra - rb;
            return ra == 2 ? Long.compare(b.startMs, a.startMs) : Long.compare(a.startMs, b.startMs);
        });
    }

    /**
     * Where the ball is, from "KC 25" / "50" (possessionText, or the end of "1st & 10 at KC 25").
     * Field coordinates: 0 = away team's goal line (left side of the screen), 100 = home goal line (right).
     */
    static void footballSpot(Model.Game g) {
        String spot = g.possessionText;
        if (spot.isEmpty() && g.downText.contains(" at ")) spot = g.downText.substring(g.downText.lastIndexOf(" at ") + 4);
        spot = spot.trim();
        try {
            String[] parts = spot.split(" ");
            int n = Integer.parseInt(parts[parts.length - 1].replaceAll("[^0-9]", ""));
            if (parts.length == 1) g.ballYard = n == 50 ? 50 : -1;
            else if (parts[0].equalsIgnoreCase(g.home.abbr)) g.ballYard = 100 - n;
            else if (parts[0].equalsIgnoreCase(g.away.abbr)) g.ballYard = n;
        } catch (Exception ignored) { }
        if (g.down < 0 || g.toGo < 0) {   // "3rd & 7" / "1st & Goal"
            try {
                String t = g.downText.toLowerCase(java.util.Locale.US);
                g.down = Integer.parseInt(t.substring(0, 1));
                String after = t.substring(t.indexOf('&') + 1).trim().split(" ")[0];
                g.toGo = after.startsWith("goal") ? 0 : Integer.parseInt(after);
            } catch (Exception ignored) { }
        }
    }

    /** The league's own logo (dark version), from the saved scoreboard or a fresh one. */
    /** Leagues whose logo we know without asking ESPN. */
    static String fixedLogo(League lg) {
        if (lg.path.startsWith("esports")) return "asset:league/lol.png";
        if (lg.path.endsWith("/pwhl")) return "asset:league/pwhl.png";
        if (lg.path.equals("racing/f1")) return "https://a.espncdn.com/i/teamlogos/leagues/500-dark/f1.png|https://a.espncdn.com/i/teamlogos/leagues/500/f1.png";
        return null;
    }

    static String leagueLogo(League lg) {
        String fixed = fixedLogo(lg);
        if (fixed != null) return fixed;
        if (lg.mine() || lg.path.endsWith("/pwhl") || lg.path.startsWith("esports")) return null;
        try {
            String url = scoresUrl(lg, null);
            String body = cached(url);
            if (body == null) body = get(url);
            JSONArray leagues = new JSONObject(body).optJSONArray("leagues");
            if (leagues == null || leagues.length() == 0) return null;
            return pickLogo(leagues.getJSONObject(0).optJSONArray("logos"));
        } catch (Exception e) {
            return null;
        }
    }

    /** {athlete:{shortName}} or {shortName} -> "A. Judge" */
    private static String athleteName(JSONObject o) {
        JSONObject a = o.optJSONObject("athlete");
        if (a == null) a = o;
        return a.optString("shortName", a.optString("displayName", a.optString("fullName", "")));
    }

    /** American moneyline ("+150", -180) -> implied chance. */
    static float implied(String ml) {
        try {
            double v = Double.parseDouble(ml.replace("+", "").trim());
            if (v == 0) return -1;
            return (float) (v < 0 ? -v / (-v + 100) : 100 / (v + 100));
        } catch (Exception e) {
            return -1;
        }
    }

    private static String mlString(JSONObject o, String... path) {
        JSONObject cur = o;
        for (int i = 0; i < path.length - 1 && cur != null; i++) cur = cur.optJSONObject(path[i]);
        return cur == null ? "" : cur.optString(path[path.length - 1], "");
    }

    private static void readOdds(Model.Game g, JSONObject o) {
        if (o == null) return;
        String a = mlString(o, "awayTeamOdds", "moneyLine"), h = mlString(o, "homeTeamOdds", "moneyLine");
        if (a.isEmpty()) a = mlString(o, "moneyline", "away", "close", "odds");
        if (h.isEmpty()) h = mlString(o, "moneyline", "home", "close", "odds");
        String d = mlString(o, "drawOdds", "moneyLine");
        float pa = implied(a), ph = implied(h), pd = implied(d);
        if (pa < 0 || ph < 0) return;
        float sum = pa + ph + (pd > 0 ? pd : 0);  // remove the bookmaker margin
        g.away.odds = pa / sum;
        g.home.odds = ph / sum;
        g.drawOdds = pd > 0 ? pd / sum : -1;
        JSONObject prov = o.optJSONObject("provider");
        g.oddsSource = prov == null ? "ODDS" : prov.optString("name", "ODDS").toUpperCase();
    }

    static int rank(Model.Game g) {
        return g.isLive() ? 0 : (g.isFinal() ? 2 : 1);
    }

    /** Team color, but swap to the alternate color if the main one is too dark to see on black. */
    static int pickColor(String main, String alt) {
        int m = parse(main), a = parse(alt);
        if (m != 0 && lum(m) > 0.08) return m;
        if (a != 0 && lum(a) > 0.08) return a;
        return m != 0 ? m : 0xFF3A3F4A;
    }

    private static int parse(String hex) {
        try {
            if (hex == null || hex.length() != 6) return 0;
            return Color.parseColor("#" + hex);
        } catch (Exception e) {
            return 0;
        }
    }

    private static double lum(int c) {
        return (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255.0;
    }
}
