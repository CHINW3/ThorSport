# Changelog

## 3.22
- **Fixed the same notification coming back over and over**: ESPN sometimes sends an older score for a moment, so a goal looked like it happened again at every refresh (the "GOAL · WPG" card kept popping up). Now only a score higher than anything already seen counts, and a card is never shown twice for the same score (and at most one card per game every 90 seconds).
- **Sound fixed**: goal sounds now play through Android's media player (a real sound file made on the fly), which works on devices where the old way only vibrated. The old way stays as a backup.
- **A sound for every occasion**: Settings → Sound now has one row per event, each with its own sound (tap to change, you hear it right away):
  - Goal: arena horn · Touchdown: deep horn · Field goal: organ "charge!" · Win: victory chime · Home run: crowd cheer (after the bat crack) · OT winner: goal siren · Your other games: victory chime · 3-pointer: swish
  - 7 sounds to pick from: arena horn, deep horn, goal siren, victory chime, crowd cheer, organ charge, swish, or none.
- **NBA / WNBA 3-pointers have a sound**: a net "swish" and a crowd "ohh!" when your team (or the game you watch) hits a three. The "THREE!" banner stays under Extra alerts.
- Your other team's win now plays its sound too.

## 3.21
- **A different celebration for each event**:
  - **Hockey goal**: red goal light, strobe and siren beams (as before).
  - **Touchdown**: end-zone stripes in the team color and a football spiralling in with a trail.
  - **Field goal**: yellow uprights behind the logo, the kick sails through and the posts light up.
  - **Soccer goal**: the net ripples out from where the ball hit.
  - **OT winner**: golden rays turning behind the logo, the word in gold.
  - **Victory / shutout / comeback win**: no strobe, gold rays, a crown dropping on the logo, gold confetti and gold fireworks.
  - **Home run / grand slam**: the ball flying out of the park (as before).
- **Big words always fit**: "TOUCHDOWN!" shrinks to fit instead of being cut with "…".
- **Settings, reorganized**: 8 sections with their own icons. **General** (two screens, language, open on, refresh, spoiler delay), **Display** (brightness, accent, clock, keep screen on, screensaver), **Celebrations**, **Sound**, **Alerts**, **Stick lights**, **Scores & Board**, **App**.
- **Celebration options**: **Style** in one tap (FULL, CLEAN without flashing or shaking, SIMPLE with just the logo and the word) or pick each effect yourself: flash & strobe, goal-lamp beams, confetti, fireworks, screen shake. Plus length, all games / my teams only, test and replay.
- **4 goal sounds**: arena horn, deep horn, goal siren, victory chime (you hear it as soon as you pick it). Plus volume, full / short, vibration.
- **Stick lights → Flash on goals**: turn off the light show on goals while keeping the team colors.
- **Even fewer notifications**: by default you now only get goals, touchdowns and field goals (yours and the game you watch), your team's wins, goals against your team, and the cards for your other games. Also moved behind Settings → Goals & alerts → Extra alerts: game start / puck drop, overtime, finals of games your team didn't win, upsets, close games, NBA "THREE!", and the small grey banners for extra points, 2-point tries and other teams' goals.

## 3.20
- **New PLAYOFFS tab** (every league except MY TEAMS, F1 and LoL), drawn as a real bracket with lines between the rounds:
  - **In the playoffs** (or up to 3 weeks after): the real bracket from ESPN, one column per round (East and West together), with the series score (BOS 4 – 1), single-game scores (NFL, soccer, college) and two-leg aggregates (Champions League). Your teams are outlined.
  - **Not in the playoffs yet: "If the playoffs started today"**, built from the current standings with each league's real format, the two conferences facing each other with the final in the middle and empty boxes for the rounds to come: NHL (top 8 per conference, 1v8…), NBA (with the play-in note), WNBA (top 8), NFL (7 per conference, #1 bye), MLB (6 per league, #1–2 byes), MLS (9 per conference with the wild card), PWHL (top 4), Champions League (top 8 to the round of 16, 9–24 in the playoff round). European leagues show the champion, the Champions League spots and the relegated teams "if the season ended today".
- **College sports**: four new leagues at the end of the league list. **NCAAF** (college football), **NCAAM** and **NCAAW** (men's and women's college basketball) and **NCAA HOCKEY** (men's). You get every Division I game (not just the top 25), conference standings, the Board (live field for football), the GAME tab, celebrations, stick lights, and you can pick your college teams in MY TEAMS. Top-25 teams show their rank (#9 Texas Tech).
- **Two-screen button is a simple switch**: tapping ▭ now goes straight between one screen and two screens (no menu to pick from). R3 still does the same.
- **Fewer banners**: by default you only get goals, touchdowns, field goals and wins. The extra banners (power play, empty net, tie game, lead change, scoring runs, start of period, two-minute warning, hat trick, win-chance swings, intermission) are now off. Want them back? Settings → Goals & alerts → Extra alerts.
- **No waiting when you open the app**: the spoiler delay no longer comes back when you reopen ThorSport, so the scores show right away. Sync with your TV again when you need it.
- **Smooth game clock**: in hockey, basketball and football the clock now runs down second by second between refreshes, instead of jumping every 15–30 s. When the play stops it can be a few seconds ahead until the next update fixes it.

## 3.19
- **Logos without Wi-Fi**: every NHL, NFL, NBA, MLB and MLS team logo (and those five league logos) is now packed inside the app, with a thin light outline on the darkest ones so they stand out on the dark background, so they show right away, even offline. Other leagues (PWHL, WNBA, soccer in Europe, F1…) still load their logos from ESPN, and a team that isn't in the pack (like the Utah Mammoth) falls back to ESPN too. The big faded logo behind a team on the Board (and the logo at center ice) uses ESPN's version, so you see two different logos instead of the same one twice.

## 3.18
- **Stick lights**: navy teams (Bears, Patriots, Astros…) use their main color again, like before 3.17.

## 3.17
**Goals & alerts**
- **Bigger goal celebration**: a white flash then a strobe (red goal light for hockey, team color for the other sports), two rotating goal-lamp beams, GOAL! / TOUCHDOWN! slams onto the screen and shakes it, shockwave rings from the logo, the word glows in the team color, fireworks and twice the confetti. Try it: Settings → Test a goal.
- **OT WINNER!** for an NHL overtime goal, **SHUTOUT!** and **COMEBACK WIN!** (after being down by 2+) instead of VICTORY! when your team wins that way.
- **New banners for the game you watch**: power play (5 on 4…) and empty net (NHL), tie game, lead change, COMEBACK! (tied after being down 2+), 3+ unanswered goals / straight scores, hat trick, red card (soccer), start of each period / quarter / second half, NFL two-minute warning, and a big swing in the win chance (20+ points).
- **Goal against your team**: a red "GOAL AGAINST" banner and one short blink of the sticks in their color (no party).
- **Better final banner**: shutout, the star of the game (2+ goals) and the playoff series.
- **Intermission banner** shows the shots on goal (NHL).
- **Banners wait their turn**: alerts that happen together (or during a goal screen) are shown one after the other instead of replacing each other.
- **Mute alerts (1 h)**: Settings → Goals & alerts → Mute alerts. Silences the horn and vibration for an hour.
- **Celebrate: all games / my teams only**: with MY TEAMS ONLY, games without your teams get a small banner instead of the full party.
- **Horn length: full / short** (short ≈ 1 s).
- **Replay last goal**: Settings → Goals & alerts → Replay last goal.
- **Game reminder**: off, 5, 15 or 30 min before your team plays.
- Removed: quiet hours and night dim (use Mute alerts).

**App**
- **Rotate live games on Board** every 20 or 45 s when none of your teams is playing (Settings → Scores & Board).
- **Keep screen on**: always, only while a game is live, or never.
- **Live refresh**: normal (30 s) or fast (15 s).
- **Open on**: the last league or MY TEAMS.
- **Low battery banner** at 15 % (ThorSport then refreshes less often).
- **Reset settings** (Settings → App, tap twice). Your teams are kept.
- **Controller**: in Settings, ↓ on the last row goes back to the top (and ↑ on the first goes to the bottom); holding the stick scrolls faster after a second and a half.

**Fixes**
- **Stick lights for navy teams**: teams whose main color is a very dark navy (Bears, Patriots, Astros…) now light up in their bright second color (Bears orange) instead of a plain bright blue that didn't look like the team.

## 3.16
- **Controller in LIVE fixed**: ↓ now goes straight down to the tile below (it went diagonal because the tiles are 3 per row).
- **Menu sounds & vibration**: a soft tick and a tiny buzz when you move with the controller, a higher one when you select. Off by default: Settings → General → Menu sounds & vibration.
- **Vibrate on goals is now off by default**: turn it on in Settings → Goals & alerts → Vibrate on goals.

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
