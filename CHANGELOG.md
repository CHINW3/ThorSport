# Changelog

## 3.15
- **HOME RUN!**: the ball leaves the plate with a glowing trail and flies out of the park, then fireworks in your team's colors. Bat crack before the horn, and the stick lights follow the ball from left to right. **GRAND SLAM!** gets gold and extra fireworks. Shows the batter and the distance when ESPN has it. In two-screen mode the ball flies from the bottom screen into the top one. Try it: Settings → Test a goal with a baseball game on the Board.
- **Two-screen celebrations**: one party over both screens instead of the same thing twice: logo and GOAL! on top, the new score and the scorer below, confetti falling from one screen into the other.
- **Score flash fixed**: the new score now blinks after the goal screen (before, it blinked hidden under it). On the Board it blinks amber for 4 seconds.
- **Goal horn fixed**: new way of playing the sound that works on more devices, and the game or video on the other screen gets quieter while the horn plays.
- **Stick lights**: goal animations now show even when another RGB app (AmbiAnalog…) keeps writing its own colors. Reds stay red (the Blackhawks no longer show pink).
- **NBA / WNBA**: every basket makes the stick lights blink twice in the team's color (threes still get their THREE! banner).

## 3.14
- **Smooth score ticker**: the line of other games under the Board on the top screen now scrolls at 60 fps on its own layer, with sub-pixel movement, instead of jumping a few pixels 25 times a second. It also keeps its place when a score changes instead of restarting, and hides while a celebration or a menu covers the screen.

## 3.13
- **Your other games**: when two of your teams play at the same time, the one you're not watching sends a little card at the bottom right (team logo, TOUCHDOWN / GOAL / FIELD GOAL · score · clock), a soft horn and a quick blink of the stick lights when it's your team scoring. You also get a card when the game starts and when it ends ("MTL WIN!" with gold lights, or the loss). Works across leagues too: watching the NFL, your NHL team is checked every 30 s in the background. Turn it off in Settings → Goals & alerts → Your other games.
- **New Sync with TV**: no more waiting for a goal. It shows the last few minutes of the game on top (clock + score, newest first); tap the one your TV is showing and the delay is set instantly. Works with the controller too (↑↓ + A).
- **Spoiler delay is per game now**: it belongs to the game you synced it for. Switching to another game (another stream) turns it off, and an old delay (4 h+) isn't kept when you open the app again.
- **GAME tab, full screen of info**: it now loads ESPN's game summary for the game on top: team stats side by side (NFL: total yards, passing, rushing, 1st downs, 3rd downs, turnovers… · NBA: FG%, 3P%, rebounds, assists… · NHL / soccer equivalents), top players (passing / rushing leaders, points…), every scoring play, and for NFL the down & distance and the current drive in big text (the field itself stays on the top screen, no duplicate). ESPN's win probability (with the graph) fills in when Polymarket has no market.
- **Win chance graph, like it should look**: the curve now shows the whole game right away from ESPN's play-by-play win probability, bigger, ending on the current Polymarket number. Before, it only drew what happened while the app was open, so it started as a flat line.
- **NBA / WNBA threes**: a small celebration for a 3-pointer: a "THREE!" banner in the team's color, the card flashes and the stick lights blink 3 times (no horn or full-screen party). Same favorite rules as goals.
- **Smarter celebrations**: a game without your teams now celebrates both teams' goals (before, having any favorite in the league turned them into a quiet banner). A game with one of your teams celebrates only your team; two of your teams playing each other celebrate both.
- **NFL**: TOUCHDOWN! and FIELD GOAL! get the full party; the extra point or 2-point try right after is just a quiet banner so you don't get two parties for one touchdown.

## 3.12
- **Polymarket for night games**: evening games (Sunday/Monday/Thursday Night Football, late NBA/NHL games) had no win chance because Polymarket files them under the next day's date. ThorSport now checks both dates.

## 3.11
- **F1, more visual**: team badges in each team's color (RBR, FER, MCL…) in the results, each driver's country flag next to their name (results and drivers' standings), constructors with their team code, and the host country's flag in the Grand Prix header. Fixed the F1 logo link.
- **One two-screen mode**: two screens now always means the Board on top with GAME and LIVE on the bottom (the Classic option is gone). The ▭ button / R3 switch between one and two screens.
- **Pick a game, see it**: choosing a game on the bottom screen (LIVE tile or score card) now jumps straight to its GAME tab.
- **Stick lights follow the game you watch**: if one of your teams plays in a league (e.g. you picked an NFL team), the lights used to stay on that team's colors even while you watched other games, so they never changed. Now a game without your team shows its own two teams (left / right); your team's colors come back for its own games.

## 3.10
- **View modes in one tap**: the ▭ button now opens a menu: One screen, Two screens · Classic, Two screens · Game info. R3 on a controller cycles through them.
- **Stick lights for every team**: teams whose main color is black, grey or very dark (lots of NFL teams) now use their second color instead of showing plain white, so the lights visibly change. The "tight finish" pulse only follows the game you're watching, so it no longer freezes the lights on another game. (Turn the lights off any time: Settings → Stick lights.)
- **Pages slide**: the game cards slide left/right when you change page.
- **Top-screen ticker scrolls again**: the score ticker under the top-screen Board was stuck; it now moves across.
- **Home in two-screen mode**: pressing Home (Cocoon or any launcher, on either screen) now leaves ThorSport on both screens. Open ThorSport again and the top screen comes back.
- **PWHL logo**: the PWHL now shows its logo in the league list and at the top.

## 3.9
- **GAME and LIVE are back, redesigned**: in two-screen mode the bottom shows **GAME** (score band in team colors, scoring list with assists, score by period, win chance with the Polymarket graph over the game (smooth line, area in the leading team's color, zooms on the swings), shot map, shots and saves side by side, top players) and **LIVE** (every game as scoreboard tiles, tap one to put it on top). Prefer the old bottom screen? Settings → General → Two-screen: bottom shows → CLASSIC.
- **Settings with a controller**: ↑↓ picks a section, → or A goes into it, ↑↓ moves, A changes, ← or B goes back, L1/R1 jump between sections. A white ring shows where you are, with the buttons listed at the top.

## 3.8
- **Pick your teams on first start**: a welcome screen lets you choose your teams in every league (logos, tap to add). It only shows once: updates keep your teams. Change them any time in Settings → App → My teams.
- **Two-screen mode**: the bottom screen is back to Scores / Board / Standings (the GAME and LIVE tabs from 3.7 are gone).
- **MY TEAMS**: no more Standings tab there (it only showed one row per team).

## 3.7
- **Two-screen mode, new bottom screen**: while the top shows the Board, the bottom gets two new tabs. **GAME**: score strip, timeline of goals with assists, score by period, win chance graph over the game (or the shot map for NHL) and a team-vs-team stats comparison. **LIVE**: every game of the league as scoreboard tiles; the game on top is outlined, goals flash, tap a tile to put it on the top screen.
- **New Settings screen**: sections (General, Goals & alerts, Stick lights, Scores & Board, App) with big rows and real on/off switches. On a controller: ← → change section, ↑ ↓ move, A to change.
- **New F1 look**: Grand Prix header with a live countdown (days, hours, minutes, seconds), the race weekend schedule next to a timing tower of the last results with team colors and gaps. Cleaner session cards with the podium.
- **League logos**: League of Legends and F1 now show their logo in the league list and at the top.
- **NFL score cards**: the mini field no longer covers the team records; the game clock is no longer cut off.

## 3.6
- **In-app updates**: ThorSport checks GitHub for new versions and shows a banner. Settings → App update downloads and installs it (Android asks once to allow it).
- **Fixed signing key**: new versions now install over old ones. No more "App not installed" error. *(Uninstall the old version once before installing 3.6.)*

## 3.5
- **New NHL rink**: bright broadcast-style ice, real markings, clean team-colored shots, goals with the scorer's name, a proper legend.
- **NHL shot map on/off**: Settings → NHL shot map. When off, NHL games use the normal Board and no shot data is downloaded.

## 3.4
- First public release: live scores, standings and arena Board for NHL, PWHL, NBA, NFL, MLB, WNBA, F1, League of Legends and 7 soccer leagues.
- Two-screen mode, MY TEAMS, goal horn and celebrations, stick lights in team colors, spoiler delay with Sync with TV, Polymarket win chances, MLB field, NFL field, NHL shot map, controller support.
