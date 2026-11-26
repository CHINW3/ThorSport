# ThorSport website: handoff for Claude Code

Paste this file into a new Claude Code session (or keep it next to the code) to continue where this conversation stopped.

## What exists

- **Live site:** https://chinw3.github.io/ThorSport/ (GitHub Pages, branch `gh-pages` of `CHINW3/ThorSport`, root folder).
- **The app** lives on `main` of the same repo. The site never touches `main`.
- Pushing to `gh-pages` cannot build an APK: that branch has no workflow files, and `main`'s Build APK workflow only runs when a commit message contains `[release]`.

## Files

`gh-pages` branch (what's published):

| File | What it is |
|---|---|
| `index.html` | The whole site, built from the sources below (inline CSS/JS, logos as data URIs) |
| `horns/*.mp3` | Real goal horns for MTL, TOR, BOS, EDM, DAL, SEA, copied from `app/src/main/assets/horns` |
| `og.png` | 1200×630 share image |
| `favicon.png`, `apple-touch-icon.png` | The app's launcher icon (amber "2:1" scoreboard) |
| `sitemap.xml`, `.nojekyll`, `README.md` | SEO and Pages config |

Sources (in `source/` next to this file, not in the repo yet):

| File | What it is |
|---|---|
| `source/src/site.css` | Page styles: 12-col grid, 8px spacing, tokens |
| `source/src/device.css` | The drawn AYN Thor (screens, sticks, LED states) |
| `source/src/body.html` | Markup, icon sprite, `<template id="tpl-device">` |
| `source/src/main.js` | Device loop, pinned story, goal button + horns, confetti, gamepad A, GSAP/Lenis animation |
| `source/tools/build.py` | Assembles `index.html` (standalone) + an artifact variant, injects logos, ticker, JSON-LD, sitemap |
| `source/tools/encode.py` | Turns logo PNGs into WebP data URIs → `logos.json` |
| `source/tools/og.js` | Renders `og.png` with Playwright |
| `source/logos.json`, `source/icon.json` | Encoded logos and app icon |
| `source/sherbrooke-phoenix.png` | Sherbrooke Phoenix logo (for the QMJHL task below) |

Build (expects `src/`, `tools/`, `logos.json`, `icon.json` in one folder):

```bash
python3 source/tools/build.py source out/index.html out/artifact.html
```

## Design rules agreed in this conversation

- Dark, flat, no glassmorphism, no glow blobs, no grain, no boxes inside boxes.
- Colors: bg `#07090d`, surface `#0d1118`, rules `#1c2330`/`#2a3341`, ink `#eef1f6`/`#a4adbf`/`#7a8497`, accent `#2f7bff`, score amber `#ffb524`, live red `#ff3b30`.
- Fonts: Archivo (display + body) and JetBrains Mono (data only). No Barlow.
- Copy: short, plain, one line where possible. No slogans, no over-explaining, no small uppercase "eyebrow" labels, no pulsing "live" kicker.
- Animations the user wants kept: section reveals, hero device tilt, counters, progress bar, device goal loop, pinned "How it works" device, GOAL takeover with confetti/shake, real horns, ticker. Removed on request: drifting labels next to the pinned device.
- Use the app's own icon, never an invented logo.
- Team picker in the goal section: logos only.

## Open task: add the QMJHL to the app

The user asked to add the QMJHL (LHJMQ) and use the Sherbrooke Phoenix logo (`source/sherbrooke-phoenix.png`).

- The QMJHL runs on HockeyTech, the same feed the app already uses for the PWHL and AHL in `app/src/main/java/com/gregoire/thorstandings/Pwhl.java`:
  `https://lscluster.hockeytech.com/feed/index.php?feed=modulekit&key=<KEY>&client_code=<CODE>&fmt=json&lang=en`
- Client code is probably `lhjmq`. The key is league-specific: open theqmjhl.ca or chl.ca scores, DevTools → Network, find a `lscluster.hockeytech.com` request and copy `key=`.
- Then: add a QMJHL entry next to the AHL/PWHL ones (feed, standings, team colors), put the league in the menu (INTL or a new CHL folder), add the Sherbrooke logo for the Phoenix.
- Not started: the cloud session couldn't reach `lscluster.hockeytech.com` to test.

## Other open questions from the user

- Add a link to the site at the top of the README on `main`? (not answered)
- Keep `"offers": {"price": "0"}` in the JSON-LD (search may show "Free")? (not answered)
- Google Search Console: add `https://chinw3.github.io/ThorSport/`, submit `sitemap.xml`.
