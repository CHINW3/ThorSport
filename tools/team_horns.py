#!/usr/bin/env python3
"""
Prepares NHL team goal horns for ThorSport.

    python3 tools/team_horns.py <folder with the .mp3 files> [output folder]

Every file whose name contains an NHL team name ("... Boston Bruins Goal Horn.mp3") becomes
<output>/<abbr>.mp3, e.g. bos.mp3 (default output: app/src/main/assets/horns). The abbreviation is the one ESPN uses,
because that is what the app looks up when a team scores.

Each horn is cut to 6 seconds with a 1.5 second fade-out (and a 5 ms fade-in), brought to the same average loudness
(-9 dB) with a limiter on the peaks, and saved as a 112 kbps stereo MP3 (about 80 KB each). Needs ffmpeg.
"""
import os
import re
import subprocess
import sys

LENGTH, FADE, TARGET_DB = 6.0, 1.5, -9.0   # -9 dB average: as loud as the built-in sounds (3.29 used -13 and was too quiet)
# Some horns have left and right partly out of phase: fine on two speakers, but on one speaker (or Bluetooth mono) they lose
# several dB and sound thin. Those get a centered mono mix, and their level is set on that mix.
MONO = "pan=stereo|c0=0.5*c0+0.5*c1|c1=0.5*c0+0.5*c1,"
PHASEY_DB = 1.0
# Dead air at the start (some uploads have a second of nothing) would delay the horn: cut it.
TRIM = "silenceremove=start_periods=1:start_threshold=-50dB:start_silence=0.02,"

TEAMS = {  # name (lowercase, no punctuation) -> ESPN abbreviation
    "anaheim ducks": "ana", "arizona coyotes": "ari", "boston bruins": "bos", "buffalo sabres": "buf",
    "calgary flames": "cgy", "carolina hurricanes": "car", "chicago blackhawks": "chi", "colorado avalanche": "col",
    "columbus blue jackets": "cbj", "dallas stars": "dal", "detroit red wings": "det", "edmonton oilers": "edm",
    "florida panthers": "fla", "los angeles kings": "la", "minnesota wild": "min", "montreal canadiens": "mtl",
    "nashville predators": "nsh", "new jersey devils": "nj", "new york islanders": "nyi", "new york rangers": "nyr",
    "ottawa senators": "ott", "philadelphia flyers": "phi", "pittsburgh penguins": "pit", "san jose sharks": "sj",
    "seattle kraken": "sea", "st louis blues": "stl", "tampa bay lightning": "tb", "toronto maple leafs": "tor",
    "utah hockey club": "uta", "utah mammoth": "uta", "vancouver canucks": "van", "vegas golden knights": "vgk",
    "washington capitals": "wsh", "winnipeg jets": "wpg",
}


def team_of(filename):
    n = re.sub(r"[^a-z ]", " ", os.path.splitext(filename)[0].lower().replace("_", " ").replace("é", "e"))
    n = " ".join(n.split())
    for name, abbr in TEAMS.items():
        if name in n or name.replace("st louis", "saint louis") in n:
            return abbr
    return None


def duration(path):
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", path], capture_output=True, text=True)
    try:
        return float(r.stdout.strip())
    except ValueError:
        return LENGTH


def mean_db(path, pre=""):
    """Average loudness of the first LENGTH seconds, in dB (pre = a filter to apply first, ending with a comma)."""
    r = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-i", path, "-af", pre + "volumedetect", "-t", str(LENGTH), "-f", "null", "-"],
                       capture_output=True, text=True)
    m = re.search(r"mean_volume: (-?[\d.]+) dB", r.stderr)
    return float(m.group(1)) if m else None


def encode(path, dst, length, gain, pre=""):
    fade = FADE if length >= LENGTH - 0.05 else min(0.6, length / 3)   # a clip shorter than 6 s is a whole horn already: only fade its tail
    flt = pre + f"afade=t=in:d=0.005,volume={gain:.2f}dB,alimiter=limit=0.89:level=disabled,afade=t=out:st={max(0.0, length - fade):.2f}:d={fade:.2f}"
    return subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", path, "-af", flt, "-t", f"{length:.2f}",
                           "-ar", "44100", "-ac", "2", "-c:a", "libmp3lame", "-b:a", "112k", dst], capture_output=True, text=True)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    src = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "horns")
    os.makedirs(out, exist_ok=True)
    done, skipped = [], []
    for f in sorted(os.listdir(src)):
        if not f.lower().endswith((".mp3", ".wav", ".m4a", ".ogg", ".flac")):
            continue
        abbr = team_of(f)
        if abbr is None:
            skipped.append(f)
            continue
        path = os.path.join(src, f)
        mean, mono = mean_db(path, TRIM), mean_db(path, TRIM + MONO)
        phasey = mean is not None and mono is not None and mean - mono > PHASEY_DB
        pre = TRIM + (MONO if phasey else "")
        ref = mono if phasey else mean
        gain = (TARGET_DB - ref) if ref is not None else 0.0
        length = min(LENGTH, duration(path))   # a clip shorter than 6 s keeps its own length, with the fade at its end
        dst = os.path.join(out, abbr + ".mp3")
        r = encode(path, dst, length, gain, pre)
        if r.returncode != 0:
            skipped.append(f + " (ffmpeg failed: " + r.stderr.strip()[:100] + ")")
            continue
        # check the result and correct (up to 3 times): the first estimate can be off for clips whose loudness changes a lot
        # over their length, and the limiter now really limits the peaks.
        for _ in range(3):
            got = mean_db(dst)   # (-0.7: the fade-out makes the whole file a little quieter)
            if got is None or abs(got - (TARGET_DB - 0.7)) <= 1.0:
                break
            gain += (TARGET_DB - 0.7) - got
            r = encode(path, dst, length, gain, pre)
            if r.returncode != 0:
                skipped.append(f + " (ffmpeg failed: " + r.stderr.strip()[:100] + ")")
                break
        done.append((abbr, f, gain, os.path.getsize(dst), phasey))
    for abbr, f, gain, size, phasey in done:
        print(f"{abbr:5s} {size // 1024:4d} KB  gain {gain:+5.1f} dB  {'centered mono (channels were out of phase)  ' if phasey else ''}<- {f}")
    print(f"{len(done)} horns written to {os.path.normpath(out)}")
    for f in skipped:
        print("skipped:", f)


if __name__ == "__main__":
    main()
