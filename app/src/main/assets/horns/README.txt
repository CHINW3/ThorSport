NHL team goal horns, one file per team: <ESPN abbreviation>.mp3 (ana, bos, buf, cgy, car ...; Utah is "uta").
Played when that team scores (Settings -> Sound -> Team goal horns). A team without a file uses the normal goal sound.

Source: imported from SoundCloud by the app owner. Uploader credit:
  - victoriajohnson: Ducks, Bruins, Sabres, Flames, Hurricanes, Avalanche, Blue Jackets, Stars, Red Wings, Oilers, Panthers,
    Kings, Canadiens, Predators, Devils, Islanders, Rangers, Senators, Flyers, Penguins, Sharks, Blues, Lightning,
    Maple Leafs, Capitals
  - NHL_HORNSANDSONGS: Blackhawks (2015 playoff horn)
  - Pete Wheeler: Wild (2023 horn)
  - HockeybossEIA (famousgoalhorns): Canucks
  - Utah: file "goal_horn.mp3" given by the app owner (uploader not known)
  - Golden Knights ("vgk-goal-horn-made-with-Voicemod"), Kraken ("kraken_goal_horn") and Jets ("winnipeg-jets-2025-goal-horn-made-
    with-Voicemod"): files given by the app owner (uploader not known)
All 32 teams have a horn.

Each file is cut to 6 s (a clip that is shorter keeps its own length, with a short fade) with a 1.5 s fade-out and leveled to the same loudness by tools/team_horns.py:
    python3 tools/team_horns.py <folder with the original audio files>
Dead air at the start is cut, and horns whose left and right channels were out of phase are saved as a centered mono mix
so they stay loud on one speaker.
