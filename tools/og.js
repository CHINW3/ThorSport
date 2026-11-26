const { chromium } = require('playwright');
const { execFileSync } = require('child_process');
const fc = {};
(async () => {
  const b = await chromium.launch();
  const p = await (await b.newContext({ viewport: { width: 1200, height: 630 }, deviceScaleFactor: 1, reducedMotion: 'reduce' })).newPage();
  await p.route('**/*', r => {
    const u = r.request().url();
    if (u.includes('fonts.g')) { if (!fc[u]) fc[u] = execFileSync('curl', ['-s', '-A', 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36', u], { maxBuffer: 1 << 26 }); return r.fulfill({ body: fc[u], contentType: u.includes('googleapis') ? 'text/css' : 'font/woff2', headers: { 'access-control-allow-origin': '*' } }); }
    return u.startsWith('file:') ? r.continue() : r.abort();
  });
  await p.goto('file://' + process.argv[2]);
  await p.waitForTimeout(1200);
  await p.addStyleTag({ content: `
    .nav,.progress,.grain,.skip,.marquee,.manifesto,.stats,#story,#features,#goal,#install,.foot,.hero__lede,.hero__ctas,.hero__meta{display:none!important}
    .hero__pin{flex-direction:row!important;align-items:center!important;justify-content:space-between!important;height:630px!important;padding:0 60px 0 76px!important;gap:30px!important}
    .hero__copy{align-items:flex-start!important;text-align:left!important;padding:0!important;max-width:700px;gap:24px!important}
    .hero .display{font-size:90px!important;white-space:nowrap}
    .og-sub{margin:0;font-family:var(--f-display);font-weight:700;font-size:25px;color:var(--ink-2);letter-spacing:-.01em;white-space:nowrap}
    .og-brand{display:flex;align-items:center;gap:12px;font-family:var(--f-display);font-weight:800;font-size:30px;letter-spacing:-.01em}
    .og-brand img{width:46px;height:46px;border-radius:12px;box-shadow:0 0 0 1px rgba(164,188,255,.22)}
    .hero__stage{width:400px!important;flex:none}
    .hero__glow{opacity:1!important}
    .hero__glow::after{opacity:.85!important}` });
  await p.evaluate(() => {
    const copy = document.querySelector('.hero__copy');
    copy.querySelector('.eyebrow').outerHTML = '<p class="og-brand"><img src="' + document.querySelector('.brand__icon').src + '" alt="">ThorSport</p>';
    copy.insertAdjacentHTML('beforeend', '<p class="og-sub">Live scores for the AYN Thor and Retroid Pocket Duo</p>');
    const dev = document.querySelector('.hero .dev');
    dev.querySelectorAll('.screen--bot .pn').forEach(n => n.classList.toggle('is-on', n.dataset.pn === 'goal'));
    document.querySelector('.hero__stage').classList.add('is-goal');
    window.scrollTo(0, 0);
  });
  await p.waitForTimeout(600);
  await p.screenshot({ path: process.argv[3] });
  await b.close();
})();
