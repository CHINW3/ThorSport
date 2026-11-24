package com.gregoire.thorstandings;

import java.util.ArrayList;
import java.util.List;

/** Plain data classes shared by the fetcher and the view. */
public final class Model {
    private Model() {}

    public static class Team {
        public String abbr, name, logo;
        public int rank;                 // real position in its league table
        public int seed;                 // ESPN's playoff seed when it gives one (0 = none)
        public int color;                // F1: team color
        public String[] vals;
        public double sortKey, tieKey;
    }

    public static class Group {
        public String title;
        public String[] cols;            // own columns (MY TEAMS mixes leagues)
        public final List<Team> teams = new ArrayList<>();
    }

    public static class Side {
        public String abbr = "", name = "", logo, record = "", score = "";
        public int color = 0xFF3A3F4A;
        public boolean winner;
        public String teamId = "";
        public int ledA, ledB;                                // the team's two main colors (for the stick lights)
        public String leader = "";                            // e.g. "L. James 28 PTS"
        public float odds = -1;                               // win chance 0..1
        public final List<String> lines = new ArrayList<>(); // score per period
        public int saves = -1;                                // hockey goalie saves
        public int shots = -1;                                // hockey shots on goal (computed)

        public int scoreInt() {
            try { return Integer.parseInt(score.trim()); } catch (Exception e) { return -1; }
        }
    }

    public static class Game {
        public String id = "";
        public String sport = "";          // "hockey", "basketball", "football", "baseball", "soccer"
        public final Side away = new Side(), home = new Side();
        public String state = "pre";       // "pre", "in" or "post"
        public String detail = "";         // e.g. "12:34 - 2nd", "Final/OT"
        public String clock = "";
        public int period;
        public String lastPlay = "";
        public String headline = "";
        public String tv = "";
        public String event = "";                                 // F1: Grand Prix name
        public String league = "";                                // ESPN path, e.g. "hockey/nhl"
        public float drawOdds = -1;
        public String oddsSource = "";
        public final List<Goal> goals = new ArrayList<>();
        public String downText = "", possession = "";   // football: "1st & 10 at KC 25", "away"/"home"
        public String possessionText = "";               // football: "KC 25"
        public int down = -1, toGo = -1;                 // football: 3rd & 7
        public int ballYard = -1;                        // football: 0 = away goal line (left) … 100 = home goal line (right)
        public boolean redZone;
        public int awayTimeouts = -1, homeTimeouts = -1;
        public int balls = -1, strikes = -1, outs = -1;  // baseball count
        public final boolean[] bases = new boolean[3];   // baseball: 1st, 2nd, 3rd
        public final String[] runners = {"", "", ""};    // baseball: runner names when known
        public String batter = "", batterInfo = "", pitcher = "", pitcherInfo = "";
        public String series = "", note = "", venue = "";
        public String nhlId = "";
        public int[][] lol;                              // LoL live: {away, home} x {gold, kills, towers, dragons, barons, inhibitors}
        public boolean lolAwayBlue;
        public String lolState = "";                        // NHL's own game id (for the shot map)
        public final List<Shot> shots = new ArrayList<>();
        public String situationCode = "";                // "1551" = away goalie, away skaters, home skaters, home goalie
        public String homeSide = "";                     // which end the home goalie is in now: "left"/"right"
        public final List<String> results = new ArrayList<>();   // F1: finishing order
        public final List<String> resultInfo = new ArrayList<>();  // F1: "Team|gap"
        public final List<Integer> resultColors = new ArrayList<>();
        public final List<String> resultFlags = new ArrayList<>();
        // GAME tab extras (ESPN game summary)
        public final List<String[]> teamStats = new ArrayList<>();    // {label, away, home}
        public final List<String[]> leaderLines = new ArrayList<>();  // {team abbr, category, player, value}
        public final List<Float> winHist = new ArrayList<>();         // away win chance through the game (ESPN)
        public String driveText = "";                                 // football: "8 plays, 54 yards, 4:12"   // F1: driver nationality flag URL
        public String flag = "";                                     // F1: host country flag URL
        public long startMs;

        public boolean isLive() { return "in".equals(state); }
        public boolean isFinal() { return "post".equals(state); }
        public boolean involves(java.util.Set<String> abbrs) {
            return abbrs.contains(away.abbr) || abbrs.contains(home.abbr);
        }
    }

    public static class Goal {
        public boolean home, red;       // red = red card (soccer), not a goal
        public String time = "", who = "", extra = "";
    }

    /** NHL shot on the rink (feet: x -100..100, y -42.5..42.5), already turned so each team attacks the same end all game. */
    public static class Shot {
        public float x, y;
        public boolean goal, home, missed;
        public String who = "", time = "";
    }

    public static class Scoreboard {
        public final List<Game> games = new ArrayList<>();
        public String leagueLogo;
    }
}
