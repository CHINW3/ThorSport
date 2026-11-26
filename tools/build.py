import json, os, sys
SP = sys.argv[1]
OUT_SITE = sys.argv[2]          # standalone index.html
OUT_ART = sys.argv[3]           # artifact page (no doctype/html/head/body)
src = lambda p: open(os.path.join(SP, 'src', p), encoding='utf-8').read()
logos = json.load(open(os.path.join(SP, 'logos.json')))

css = src('site.css') + '\n' + src('device.css')
logo_css = '\n'.join(f".lg-{k}{{background-image:url({v['uri']})}}" for k, v in logos.items())
css = css.replace('/*LOGOS*/', '')
LOGO_STYLE = f'<style id="logos">\n{logo_css}\n</style>'

ICON = {'hockey': '#2f7bff', 'basketball': '#ff8a3d', 'football': '#d4a056', 'baseball': '#ff4d6d', 'soccer': '#1fd17a',
        'flag': '#e5e9f0', 'college': '#fbbf24', 'globe': '#38bdf8', 'cricket': '#2dd4bf', 'pad': '#a78bfa'}
LEAGUES = [
    ('NHL', 'l-nhl', 'hockey'), ('AHL', None, 'hockey'), ('PWHL', None, 'hockey'), ('NBA', 'l-nba', 'basketball'),
    ('WNBA', None, 'basketball'), ('NFL', 'l-nfl', 'football'), ('MLB', 'l-mlb', 'baseball'), ('F1', None, 'flag'),
    ('Premier League', None, 'soccer'), ('MLS', 'l-mls', 'soccer'), ('Champions League', None, 'soccer'),
    ('La Liga', None, 'soccer'), ('Bundesliga', None, 'soccer'), ('Serie A', None, 'soccer'), ('Ligue 1', None, 'soccer'),
    ('EFL Championship', None, 'soccer'), ('College football', None, 'college'), ('College basketball', None, 'college'),
    ('College hockey', None, 'college'), ('World Cup', None, 'globe'), ('Nations League', None, 'globe'),
    ('Olympic hockey', None, 'globe'), ('Test cricket', None, 'cricket'), ('One-day cricket', None, 'cricket'),
    ('T20', None, 'cricket'), ('League of Legends', None, 'pad'), ('CS2', None, 'pad'), ('Valorant', None, 'pad'),
    ('Dota 2', None, 'pad'), ('Rainbow Six Siege', None, 'pad'),
]
COLORS = {'l-nhl': '#c8d2e0', 'l-nba': '#e03a3e', 'l-nfl': '#2f6bff', 'l-mlb': '#2f6bff', 'l-mls': '#e0303c'}
items = []
for name, logo, icon in LEAGUES:
    c = COLORS.get(logo, ICON[icon])
    mark = f'<i class="logo lg-{logo}"></i>' if logo else f'<svg aria-hidden="true"><use href="#i-{icon}"/></svg>'
    items.append(f'      <li class="tk"><span class="tk__mark" style="--c:{c}">{mark}</span>{name}</li>')
TEAMS = [
    ('mtl', 'Montréal Canadiens', '#e8323f'), ('nyk', 'New York Knicks', '#f58426'), ('buf', 'Buffalo Bills', '#2f6bff'),
    ('nyy', 'New York Yankees', '#4a74d9'), ('mia-fc', 'Inter Miami CF', '#f7a8c8'), ('tor', 'Toronto Maple Leafs', '#2f6bff'),
    ('lal', 'Los Angeles Lakers', '#fdb927'), ('kc', 'Kansas City Chiefs', '#e31837'), ('tbj', 'Toronto Blue Jays', '#2f7bff'),
    ('lafc', 'LAFC', '#d6aa5c'), ('bos', 'Boston Bruins', '#ffb81c'), ('bosc', 'Boston Celtics', '#1fae5b'),
    ('phi', 'Philadelphia Eagles', '#19a39a'), ('chc', 'Chicago Cubs', '#2f62e0'), ('tfc', 'Toronto FC', '#e0243a'),
    ('edm', 'Edmonton Oilers', '#ff6a1f'), ('gsw', 'Golden State Warriors', '#ffc72c'), ('sf', 'San Francisco 49ers', '#d4213d'),
    ('lad', 'Los Angeles Dodgers', '#2a7de1'), ('sdr', 'Seattle Sounders FC', '#5fb84a'), ('vgk', 'Vegas Golden Knights', '#d9b25f'),
    ('rap', 'Toronto Raptors', '#e0213a'), ('gb', 'Green Bay Packers', '#ffb612'), ('nyr', 'New York Rangers', '#3b6dff'),
    ('sea', 'Seattle Kraken', '#5fd6e0'), ('dal', 'Dallas Stars', '#19c46e'),
]
tiles = [f'      <li class="tl"><i class="logo lg-{k}" role="img" aria-label="{n}"></i></li>' for k, n, c in TEAMS]
for k, _, _ in TEAMS: assert k in logos, k

ICON = json.load(open(os.path.join(SP, 'icon.json')))['icon']
body = src('body.html').replace('<!--ICON-->', ICON).replace('<!--MARQUEE_LEAGUES-->', '\n'.join(items)).replace('<!--MARQUEE_TEAMS-->', '\n'.join(tiles))
js = src('main.js')

import re, html as _html, datetime
SITE_URL = 'https://chinw3.github.io/ThorSport/'
REPO = 'https://github.com/CHINW3/ThorSport'
TITLE_SITE = 'ThorSport · Live scores for AYN Thor &amp; Retroid Pocket Duo'
DESC = ('Live sports scores on the bottom screen of the AYN Thor and Retroid Pocket Duo: '
        '50+ leagues, NHL goal horns, stick lights, Polymarket odds and controller support.')
EARLY = ("<script>(function(d){var h=d.documentElement;if(!h.lang)h.lang='en';"
         "if(!matchMedia('(prefers-reduced-motion: reduce)').matches){h.classList.add('motion');"
         "setTimeout(function(){if(!window.__tsReady)h.classList.remove('motion')},4500)}})(document)</script>")
LIB_URLS = ['https://cdnjs.cloudflare.com/ajax/libs/gsap/3.12.5/gsap.min.js',
            'https://cdnjs.cloudflare.com/ajax/libs/gsap/3.12.5/ScrollTrigger.min.js',
            'https://cdn.jsdelivr.net/npm/lenis@1.1.20/dist/lenis.min.js']
FONTS = ('<link rel="preconnect" href="https://fonts.googleapis.com">\n<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>\n'
         '<link rel="preconnect" href="https://cdnjs.cloudflare.com">\n<link rel="preconnect" href="https://cdn.jsdelivr.net">\n'
         + ''.join(f'<link rel="preload" as="script" href="{u}">\n' for u in LIB_URLS) +
         '<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Archivo:ital,wdth,wght@0,62..125,400..900;1,62..125,400..900'
         '&amp;family=JetBrains+Mono:wght@500;700&amp;display=swap">')
LIBS = '\n'.join(f'<script src="{u}"></script>' for u in LIB_URLS)

# FAQ structured data comes straight from the visible FAQ markup, so the two always match
def text(x): return _html.unescape(re.sub(r'<[^>]+>', '', x)).strip()
faq = [{'@type': 'Question', 'name': text(q), 'acceptedAnswer': {'@type': 'Answer', 'text': text(a)}}
       for q, a in re.findall(r'<details[^>]*>\s*<summary>(.*?)</summary>\s*<p>(.*?)</p>\s*</details>', body, re.S)]
assert len(faq) >= 3, len(faq)
LD = {'@context': 'https://schema.org', '@graph': [
    {'@type': 'SoftwareApplication', '@id': SITE_URL + '#app', 'name': 'ThorSport', 'operatingSystem': 'Android',
     'applicationCategory': 'SportsApplication', 'description': DESC, 'url': SITE_URL,
     'downloadUrl': REPO + '/releases/latest', 'image': SITE_URL + 'og.png', 'screenshot': SITE_URL + 'og.png',
     'inLanguage': ['en', 'fr'], 'offers': {'@type': 'Offer', 'price': '0', 'priceCurrency': 'USD'},
     'author': {'@type': 'Person', 'name': 'CHINW3', 'url': 'https://github.com/CHINW3'}, 'sameAs': [REPO]},
    {'@type': 'WebSite', '@id': SITE_URL + '#site', 'name': 'ThorSport', 'url': SITE_URL, 'inLanguage': 'en'},
    {'@type': 'FAQPage', '@id': SITE_URL + '#faq', 'mainEntity': faq},
]}
LD_TAG = '<script type="application/ld+json">' + json.dumps(LD, ensure_ascii=False).replace('</', '<\\/') + '</script>'

def head(title, extra):
    return f"""<title>{title}</title>
{extra}
<meta name="description" content="{DESC}">
<meta name="theme-color" content="#04060c">
{FONTS}
{EARLY}
<style>
{css}
</style>"""
SITE_META = f"""<meta name="robots" content="index, follow, max-image-preview:large">
<meta name="application-name" content="ThorSport">
<link rel="canonical" href="{SITE_URL}">
<link rel="sitemap" type="application/xml" href="{SITE_URL}sitemap.xml">
<meta property="og:type" content="website">
<meta property="og:site_name" content="ThorSport">
<meta property="og:locale" content="en_US">
<meta property="og:url" content="{SITE_URL}">
<meta property="og:title" content="ThorSport · Every game. Both screens.">
<meta property="og:description" content="{DESC}">
<meta property="og:image" content="{SITE_URL}og.png">
<meta property="og:image:width" content="1200">
<meta property="og:image:height" content="630">
<meta property="og:image:alt" content="ThorSport on an AYN Thor: a hockey game on the top screen and a GOAL screen on the bottom screen.">
<meta name="twitter:card" content="summary_large_image">
<meta name="twitter:title" content="ThorSport · Every game. Both screens.">
<meta name="twitter:description" content="{DESC}">
<meta name="twitter:image" content="{SITE_URL}og.png">
{LD_TAG}"""
FAV = ('<link rel="icon" type="image/png" sizes="96x96" href="favicon.png">\n'
       '<link rel="apple-touch-icon" href="apple-touch-icon.png">')

site = f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
{head(TITLE_SITE, SITE_META)}
{FAV}
</head>
<body>
{body}
{LOGO_STYLE}
{LIBS}
<script>
{js}
</script>
</body>
</html>
"""
art = f"""{head('ThorSport', '<meta name="claude:viewport-fit-sides" content="cover">')}
{body}
{LOGO_STYLE}
{LIBS}
<script>window.__TS_ARTIFACT = true;</script>
<script>
{js}
</script>
"""
os.makedirs(os.path.dirname(OUT_SITE), exist_ok=True)
os.makedirs(os.path.dirname(OUT_ART), exist_ok=True)
open(OUT_SITE, 'w', encoding='utf-8').write(site)
open(OUT_ART, 'w', encoding='utf-8').write(art)
today = datetime.date.today().isoformat()
open(os.path.join(os.path.dirname(OUT_SITE), 'sitemap.xml'), 'w').write(
    f'<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n'
    f'  <url><loc>{SITE_URL}</loc><lastmod>{today}</lastmod></url>\n</urlset>\n')
print('site', len(site.encode()) // 1024, 'KB; artifact', len(art.encode()) // 1024, 'KB; faq', len(faq))
