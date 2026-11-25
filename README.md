# ThorSport website

This branch holds the ThorSport landing page, published with GitHub Pages at
https://chinw3.github.io/ThorSport/

- `index.html`: the whole site in one file (inline CSS and JS; GSAP, ScrollTrigger and Lenis load from CDNs).
- `og.png`: the 1200×630 preview image shown when the link is shared.
- `.nojekyll`: serve the files as they are.

The handheld mockups are drawn in CSS/SVG. To show real screenshots instead, add
`data-top-shot="file.webp"` and `data-bot-shot="file.webp"` to a `[data-device]` element
(top screen 16:9, bottom screen 1240×1080).

The app itself lives on the `main` branch.
