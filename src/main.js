(() => {
  'use strict';
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
  const html = document.documentElement;
  const REDUCE = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const G = window.gsap, ST = window.ScrollTrigger;
  const MOTION = !REDUCE && !!(G && ST);
  if (!MOTION) html.classList.remove('motion');

  const restart = (el, c) => { el.classList.remove(c); void el.offsetWidth; el.classList.add(c); };
  const onView = (el, cb, opts = { threshold: .3 }) => {
    if (!el) return;
    if (!('IntersectionObserver' in window)) { cb(true); return; }
    new IntersectionObserver(es => es.forEach(e => cb(e.isIntersecting)), opts).observe(el);
  };

  /* ---------- teams ---------- */
  const TEAMS = {
    mtl: { ab: 'MTL', name: 'Montréal', c1: '#e8323f', c2: '#3a5bd9' },
    tor: { ab: 'TOR', name: 'Toronto', c1: '#2f6bff', c2: '#e9f0ff' },
    bos: { ab: 'BOS', name: 'Boston', c1: '#ffb81c', c2: '#f2f2f2' },
    edm: { ab: 'EDM', name: 'Edmonton', c1: '#ff6a1f', c2: '#2f5fe0' },
    dal: { ab: 'DAL', name: 'Dallas', c1: '#19c46e', c2: '#c9d1dc' },
    sea: { ab: 'SEA', name: 'Seattle', c1: '#5fd6e0', c2: '#e8323f' },
    vgk: { ab: 'VGK', name: 'Vegas', c1: '#d9b25f', c2: '#9aa5b5' }
  };
  const RIVAL = { mtl: 'tor', tor: 'mtl', bos: 'mtl', edm: 'vgk', dal: 'vgk', sea: 'vgk' };

  /* ---------- drawn devices ---------- */
  const tpl = $('#tpl-device');
  function makeDevice(mount) {
    mount.appendChild(tpl.content.cloneNode(true));
    const el = mount.querySelector('.dev');
    const scope = mount.closest('[data-scope]') || mount;
    [['topShot', '.screen--top'], ['botShot', '.screen--bot']].forEach(([k, sel]) => {
      const src = mount.dataset[k];
      if (!src) return;
      const img = new Image();
      img.className = 'shot'; img.alt = ''; img.decoding = 'async'; img.src = src;
      el.querySelector(sel).prepend(img);
    });
    const q = s => $$(s, el);
    const st = { h: 2, a: 2, home: 'mtl', away: 'tor', top: 'tv', bot: 'board', t: [], g1: 0, g2: 0 };
    const setNum = (n, v, pop) => { n.textContent = v; if (pop) restart(n, 'pop'); };
    const api = {
      el, scope, st,
      show(screen, name) { q(`.screen--${screen} .pn`).forEach(p => p.classList.toggle('is-on', p.dataset.pn === name)); },
      view(top, bot) { st.top = top; st.bot = bot; api.show('top', top); api.show('bot', bot); },
      score(h, a, pop) {
        st.h = h; st.a = a;
        q('[data-h]').forEach(n => setNum(n, h, pop === 'h'));
        q('[data-a]').forEach(n => setNum(n, a, pop === 'a'));
        q('[data-box="h"]').forEach(n => n.classList.toggle('is-lead', h > a));
        q('[data-box="a"]').forEach(n => n.classList.toggle('is-lead', a > h));
      },
      teams(h, a) {
        st.home = h; st.away = a;
        const H = TEAMS[h], A = TEAMS[a];
        const swap = (n, k) => { [...n.classList].forEach(c => { if (c.startsWith('lg-')) n.classList.remove(c); }); n.classList.add('lg-' + k); };
        q('[data-ht]').forEach(n => { n.textContent = H.ab; });
        q('[data-at]').forEach(n => { n.textContent = A.ab; });
        q('[data-hlogo]').forEach(n => swap(n, h));
        q('[data-alogo]').forEach(n => swap(n, a));
        scope.style.setProperty('--t1', H.c1);
        scope.style.setProperty('--t2', H.c2);
        scope.style.setProperty('--opp', A.c1);
      },
      play(kind, text) {
        q('[data-lpk]').forEach(n => { n.textContent = kind; });
        q('[data-lpt]').forEach(n => { n.textContent = text; });
      },
      led(mode) {
        el.classList.toggle('is-win', mode === 'win');
        el.classList.toggle('is-tight', mode === 'tight');
        if (mode === 'goal') restart(el, 'is-goal'); else el.classList.remove('is-goal');
      },
      later(fn, ms) { st.t.push(setTimeout(fn, ms)); },
      clear() { st.t.forEach(clearTimeout); st.t = []; clearTimeout(st.g1); clearTimeout(st.g2); },
      reset() {
        api.clear();
        el.classList.remove('is-goal', 'has-new', 'is-win', 'is-tight');
        scope.classList.remove('is-goal');
        api.score(2, 2);
        api.play('SHOT', `${TEAMS[st.away].ab} #34 · 3RD 6:41`);
        api.view(st.top, st.bot);
      },
      goal(hold = 2400) {
        const H = TEAMS[st.home];
        clearTimeout(st.g1); clearTimeout(st.g2);
        restart(el, 'is-goal'); restart(scope, 'is-goal');
        el.classList.add('has-new');
        api.show('bot', 'goal');
        st.g1 = setTimeout(() => { api.score(st.h + 1, st.a, 'h'); api.play('GOAL', `${H.ab} #14 · 3RD 6:18`); }, 250);
        st.g2 = setTimeout(() => {
          api.show('bot', st.bot);
          q('.pn--bd [data-h]').forEach(n => restart(n, 'pop'));
          el.classList.remove('is-goal'); scope.classList.remove('is-goal');
        }, hold);
      }
    };
    return api;
  }
  const dev = {};
  $$('[data-device]').forEach(m => { dev[m.dataset.device] = makeDevice(m); });
  Object.values(dev).forEach(d => { d.teams('mtl', 'tor'); d.reset(); });

  /* hero: Montréal scores on a loop while the hero is on screen */
  const hero = dev.hero;
  if (hero) {
    if (REDUCE) {
      hero.score(3, 2); hero.play('GOAL', 'MTL #14 · 3RD 6:18'); hero.el.classList.add('has-new');
    } else {
      let on = false;
      const cycle = () => { hero.reset(); hero.later(() => hero.goal(2600), 2000); hero.later(cycle, 7600); };
      onView(hero.el, vis => {
        if (vis && !on) { on = true; cycle(); }
        else if (!vis && on) { on = false; hero.clear(); }
      }, { threshold: .1 });
    }
  }

  /* ---------- story: one device, five scenes ---------- */
  const story = dev.story;
  const stage = $('.story__sticky');
  const steps = $$('.step');
  if (story && stage && steps.length) {
    const rail = $$('.rail i', stage);
    const chipLed = $('.chip--led', stage);
    const LED = { team: 'Sticks · team colors', goal: 'Sticks · goal swap', win: 'Sticks · win', tight: 'Sticks · tight finish' };
    const SCENES = [
      { top: 'tv', bot: 'leagues' },
      { top: 'tv', bot: 'scores' },
      { top: 'tv', bot: 'board', loop: 'goal' },
      { top: 'tv', bot: 'board', loop: 'leds' },
      { top: 'board', bot: 'game' }
    ];
    let cur = -1;
    const scene = i => {
      if (i === cur) return;
      cur = i;
      steps.forEach((s, k) => s.classList.toggle('is-active', k === i));
      rail.forEach((r, k) => r.classList.toggle('on', k === i));
      stage.dataset.scene = i;
      const S = SCENES[i];
      story.st.top = S.top; story.st.bot = S.bot;
      story.reset();
      story.led('team');
      story.el.classList.toggle('is-ledfocus', S.loop === 'leds');
      if (chipLed) chipLed.lastChild.textContent = 'Stick lights';
      if (S.loop === 'goal') {
        if (REDUCE) story.score(3, 2);
        else { const run = () => { story.score(2, 2); story.later(() => story.goal(2300), 600); story.later(run, 4200); }; run(); }
      }
      if (S.loop === 'leds') {
        const modes = ['team', 'goal', 'win', 'tight'], dur = { team: 1800, goal: 2300, win: 1800, tight: 2800 };
        let k = 0;
        const run = () => { const m = modes[k++ % 4]; story.led(m); if (chipLed) chipLed.lastChild.textContent = LED[m]; if (!REDUCE) story.later(run, dur[m]); };
        run();
      }
    };
    const desk = matchMedia('(min-width: 900px)').matches;
    if ('IntersectionObserver' in window) {
      const io = new IntersectionObserver(es => es.forEach(e => { if (e.isIntersecting) scene(+e.target.dataset.scene); }),
        { rootMargin: desk ? '-49% 0px -50% 0px' : '-62% 0px -37% 0px' });
      steps.forEach(s => io.observe(s));
      onView($('#story'), vis => { if (!vis) { story.clear(); cur = -1; } }, { threshold: 0 });
    }
    scene(0);
  }

  /* ---------- confetti ---------- */
  const confetti = (() => {
    const c = $('#confetti');
    if (!c || !c.getContext) return { burst() {} };
    const ctx = c.getContext('2d');
    let W = 0, H = 0, parts = [], raf = 0;
    const size = () => { const dpr = Math.min(window.devicePixelRatio || 1, 2); W = innerWidth; H = innerHeight; c.width = W * dpr; c.height = H * dpr; ctx.setTransform(dpr, 0, 0, dpr, 0, 0); };
    addEventListener('resize', size); size();
    const tick = () => {
      ctx.clearRect(0, 0, W, H);
      parts = parts.filter(p => p.life < p.max && p.y < H + 60);
      for (const p of parts) {
        p.life++; p.vx *= .985; p.vy = p.vy * .985 + p.g; p.x += p.vx; p.y += p.vy; p.r += p.vr; p.tl += p.vt;
        ctx.globalAlpha = Math.min(1, (p.max - p.life) / 40);
        ctx.fillStyle = p.color;
        ctx.save(); ctx.translate(p.x, p.y); ctx.rotate(p.r); ctx.scale(1, Math.cos(p.tl));
        if (p.round) { ctx.beginPath(); ctx.arc(0, 0, p.w * .45, 0, 6.283); ctx.fill(); } else ctx.fillRect(-p.w / 2, -p.h / 2, p.w, p.h);
        ctx.restore();
      }
      ctx.globalAlpha = 1;
      raf = parts.length ? requestAnimationFrame(tick) : 0;
      if (!raf) ctx.clearRect(0, 0, W, H);
    };
    return {
      burst(x, y, colors, n, spread, angle, power) {
        for (let i = 0; i < n; i++) {
          const a = angle + (Math.random() - .5) * spread, v = power * (.45 + Math.random() * .75);
          parts.push({ x, y, vx: Math.cos(a) * v, vy: Math.sin(a) * v, g: .3 + Math.random() * .14, w: 6 + Math.random() * 7, h: 8 + Math.random() * 10,
            r: Math.random() * 6.28, vr: (Math.random() - .5) * .35, tl: Math.random() * 6.28, vt: .08 + Math.random() * .12,
            color: colors[i % colors.length], round: Math.random() < .2, life: 0, max: 150 + Math.random() * 90 });
        }
        if (!raf) raf = requestAnimationFrame(tick);
      }
    };
  })();

  /* ---------- synthesized goal horn ---------- */
  let actx = null;
  function horn() {
    try {
      actx = actx || new (window.AudioContext || window.webkitAudioContext)();
      if (actx.state === 'suspended') actx.resume();
      const t = actx.currentTime + .03, dur = 2.5;
      const master = actx.createGain();
      master.gain.setValueAtTime(.0001, t);
      master.gain.exponentialRampToValueAtTime(.3, t + .06);
      master.gain.setValueAtTime(.3, t + dur - .5);
      master.gain.exponentialRampToValueAtTime(.0001, t + dur);
      master.connect(actx.destination);
      const lp = actx.createBiquadFilter();
      lp.type = 'lowpass'; lp.Q.value = 5;
      lp.frequency.setValueAtTime(320, t);
      lp.frequency.exponentialRampToValueAtTime(1700, t + .12);
      lp.connect(master);
      const shaper = actx.createWaveShaper();
      const curve = new Float32Array(1024);
      for (let i = 0; i < 1024; i++) { const x = i * 2 / 1024 - 1; curve[i] = (1 + 24) * x / (1 + 24 * Math.abs(x)); }
      shaper.curve = curve; shaper.connect(lp);
      [[138.6, 1], [207.7, .7], [277.2, .45], [69.3, .55]].forEach(([f, g]) => {
        const o = actx.createOscillator(), og = actx.createGain(), lfo = actx.createOscillator(), lg = actx.createGain();
        o.type = 'sawtooth';
        o.frequency.setValueAtTime(f * .96, t);
        o.frequency.exponentialRampToValueAtTime(f, t + .08);
        o.frequency.setValueAtTime(f, t + dur - .4);
        o.frequency.exponentialRampToValueAtTime(f * .93, t + dur);
        lfo.frequency.value = 5.5; lg.gain.value = f * .006;
        lfo.connect(lg).connect(o.frequency);
        og.gain.value = g * .3;
        o.connect(og).connect(shaper);
        o.start(t); o.stop(t + dur + .05); lfo.start(t); lfo.stop(t + dur + .05);
      });
      const buf = actx.createBuffer(1, actx.sampleRate * 3, actx.sampleRate), data = buf.getChannelData(0);
      for (let i = 0; i < data.length; i++) data[i] = Math.random() * 2 - 1;
      const ns = actx.createBufferSource(), bp = actx.createBiquadFilter(), ng = actx.createGain();
      ns.buffer = buf; bp.type = 'bandpass'; bp.frequency.value = 900; bp.Q.value = .6;
      ng.gain.setValueAtTime(.0001, t);
      ng.gain.exponentialRampToValueAtTime(.12, t + .5);
      ng.gain.exponentialRampToValueAtTime(.0001, t + 3);
      ns.connect(bp).connect(ng).connect(actx.destination);
      ns.start(t); ns.stop(t + 3);
    } catch (e) { /* audio unavailable */ }
  }

  /* ---------- goal horns: each team's own horn (the app's files), synthesized horn as a fallback ---------- */
  const horns = {};
  let playing = null;
  const getHorn = k => {
    if (!horns[k]) {
      const a = new Audio();
      a.preload = 'auto'; a.volume = .85; a.dataset.ok = '1';
      a.addEventListener('error', () => { a.dataset.ok = ''; });
      a.src = 'horns/' + k + '.mp3';
      horns[k] = a;
    }
    return horns[k];
  };
  function playHorn(k) {
    if (playing) playing.pause();
    const a = getHorn(k);
    if (!a.dataset.ok) { horn(); return; }
    try { a.currentTime = 0; } catch (e) { /* not seekable yet */ }
    playing = a;
    const p = a.play();
    if (p && p.catch) p.catch(err => { if (playing === a && err && err.name !== 'AbortError') horn(); });
  }

  /* ---------- the goal button ---------- */
  const gd = dev.goal;
  const goalSec = $('#goal'), gBtn = $('#goalBtn'), takeover = $('#takeover'), announce = $('#announce'), snd = $('#snd'), padHint = $('#padHint');
  let picked = 'mtl', lastGoal = 0, tkT = 0, hitT = 0, goalVisible = false;
  function pickTeam(k) {
    picked = k;
    if (gd) { gd.teams(k, RIVAL[k]); gd.reset(); }
    if (goalSec) goalSec.style.setProperty('--t1', TEAMS[k].c1);
    if (takeover) takeover.style.setProperty('--t1', TEAMS[k].c1);
    if (goalVisible) getHorn(k);
  }
  $$('input[name="team"]').forEach(r => r.addEventListener('change', () => { if (r.checked) pickTeam(r.value); }));
  pickTeam(($('input[name="team"]:checked') || {}).value || 'mtl');

  function celebrate() {
    const now = performance.now();
    if (now - lastGoal < 900 || !gd) return;
    lastGoal = now;
    const T = TEAMS[picked];
    announce.textContent = `Goal! ${T.name} leads ${gd.st.h + 1} to ${gd.st.a}.`;
    gd.goal(2600);
    if (snd && snd.checked) playHorn(picked);
    restart(gBtn, 'is-hit'); clearTimeout(hitT); hitT = setTimeout(() => gBtn.classList.remove('is-hit'), 520);
    clearTimeout(tkT);
    if (REDUCE) {
      takeover.classList.add('is-static');
      tkT = setTimeout(() => takeover.classList.remove('is-static'), 1200);
      return;
    }
    restart(takeover, 'is-on');
    tkT = setTimeout(() => takeover.classList.remove('is-on'), 2000);
    restart(goalSec, 'is-shake');
    const r = gBtn.getBoundingClientRect(), cols = [T.c1, T.c2, '#ffffff', T.c1];
    confetti.burst(r.left + r.width / 2, r.top + r.height / 2, cols, 110, Math.PI * 2, -Math.PI / 2, 15);
    confetti.burst(-10, innerHeight + 10, cols, 80, .55, -Math.PI / 3.2, 28);
    confetti.burst(innerWidth + 10, innerHeight + 10, cols, 80, .55, -Math.PI + Math.PI / 3.2, 28);
  }
  if (gBtn) gBtn.addEventListener('click', celebrate);

  /* A on any connected controller scores, while the goal section is on screen */
  let gpRaf = 0;
  const gpPrev = {};
  onView(goalSec, v => { goalVisible = v; if (v) getHorn(picked); }, { threshold: .2 });
  if (snd) snd.addEventListener('change', () => { if (!snd.checked && playing) playing.pause(); });
  const poll = () => {
    let pads = [];
    try { pads = navigator.getGamepads ? Array.from(navigator.getGamepads()) : []; } catch (e) { pads = []; }
    let any = false;
    pads.forEach(p => {
      if (!p) return;
      any = true;
      const a = !!(p.buttons[0] && p.buttons[0].pressed);
      if (a && !gpPrev[p.index] && goalVisible) celebrate();
      gpPrev[p.index] = a;
    });
    gpRaf = any ? requestAnimationFrame(poll) : 0;
  };
  addEventListener('gamepadconnected', () => {
    if (padHint) padHint.textContent = 'Controller connected. Press A to score.';
    if (!gpRaf) gpRaf = requestAnimationFrame(poll);
  });

  /* ---------- feature rows ---------- */
  $$('.feat').forEach(row => onView(row, v => { if (v) row.classList.add('is-in'); }, { threshold: .35 }));

  // stick lights card
  const sticks = $('#f-goal .sticks'), scap = $('.sticks__cap');
  const SCAP = { team: "Your team's two main colors, all the time.", goal: 'On a goal, the sticks swap the two colors.', win: 'On a win, both sticks turn gold.', tight: 'In a tight finish, they pulse slowly.' };
  $$('#f-goal .seg').forEach(b => b.addEventListener('click', () => {
    sticks.dataset.mode = b.dataset.mode;
    if (scap) scap.textContent = SCAP[b.dataset.mode];
    $$('#f-goal .seg').forEach(x => x.setAttribute('aria-pressed', String(x === b)));
  }));

  // controller card: highlight the part for each mapping
  const pad = $('.pad'), maps = $$('.map [data-part]');
  if (pad && maps.length) {
    let pi = 0, pt = 0, hover = false;
    const hl = p => { pad.dataset.hl = p; maps.forEach(m => m.classList.toggle('on', m.dataset.part === p)); };
    maps.forEach((m, i) => {
      m.addEventListener('pointerenter', () => { hover = true; pi = i; hl(m.dataset.part); });
      m.addEventListener('pointerleave', () => { hover = false; });
    });
    if (!REDUCE) onView(pad, v => {
      clearInterval(pt);
      if (v) pt = setInterval(() => { if (hover) return; pi = (pi + 1) % maps.length; hl(maps[pi].dataset.part); }, 1700);
    });
  }

  // spoiler delay card
  const sp = $('.spoil');
  if (sp) {
    const out = $('#delayOut'), msg = $('#delayMsg'), tap = $('#tapNow'), syncBtn = $('#syncBtn');
    const laneTv = $('[data-lane="tv"]', sp), laneApp = $('[data-lane="app"]', sp);
    let d = 0, tv = 20, phase = 'idle', t0 = 0, waitT = 0;
    const X = s => 12 + Math.min(Math.max(s, 0), 60) / 60 * 76;
    const fmt = s => (Math.round(s * 10) / 10).toString().replace(/\.0$/, '');
    const setPhase = p => { phase = p; sp.dataset.phase = p; };
    const render = () => {
      out.textContent = fmt(d) + ' s';
      laneApp.style.setProperty('--at', X(d) + '%');
      laneTv.style.setProperty('--at', X(tv) + '%');
      const gap = Math.round((tv - d) * 10) / 10;
      sp.dataset.sync = Math.abs(gap) < .6 ? 'yes' : 'no';
      msg.textContent = Math.abs(gap) < .6 ? 'In sync with your TV. No spoilers.'
        : gap > 0 ? `ThorSport shows the goal ${fmt(gap)} s before your TV.`
        : `ThorSport shows the goal ${fmt(-gap)} s after your TV.`;
    };
    $$('[data-step]', sp).forEach(b => b.addEventListener('click', () => {
      clearTimeout(waitT); setPhase('idle');
      d = Math.min(60, Math.max(0, Math.round(d) + +b.dataset.step));
      render();
    }));
    const tickTap = () => { if (phase !== 'tap') return; out.textContent = fmt((performance.now() - t0) / 1000) + ' s'; requestAnimationFrame(tickTap); };
    syncBtn.addEventListener('click', () => {
      clearTimeout(waitT);
      setPhase('wait');
      tap.disabled = true;
      tap.textContent = 'Scores hidden. Waiting for the next goal…';
      msg.textContent = 'Sync with TV is on.';
      waitT = setTimeout(() => {
        setPhase('tap');
        tap.disabled = false;
        tap.textContent = 'Tap when the goal shows on your TV';
        msg.textContent = 'A goal just happened live.';
        t0 = performance.now();
        tap.focus({ preventScroll: true });
        tickTap();
      }, 1600);
    });
    tap.addEventListener('click', () => {
      if (phase !== 'tap') return;
      d = Math.min(60, Math.round((performance.now() - t0) / 100) / 10);
      tv = d;
      setPhase('idle');
      render();
      msg.textContent = `Delay set to ${fmt(d)} s. In sync with your TV.`;
      syncBtn.focus({ preventScroll: true });
    });
    render();
  }

  // canvas card: the two screen sizes
  const cvc = $('.screens');
  if (cvc) {
    const dim = $('[data-dim]', cvc), btns = $$('[data-devsel]', cvc), DIMS = { thor: '1240 × 1080', duo: '1280 × 960' };
    let ct = 0, user = false;
    const setDev = k => { cvc.dataset.dev = k; dim.textContent = DIMS[k]; btns.forEach(b => b.setAttribute('aria-pressed', String(b.dataset.devsel === k))); };
    btns.forEach(b => b.addEventListener('click', () => { user = true; clearInterval(ct); setDev(b.dataset.devsel); }));
    if (!REDUCE) onView(cvc, v => {
      clearInterval(ct);
      if (v && !user) ct = setInterval(() => setDev(cvc.dataset.dev === 'thor' ? 'duo' : 'thor'), 2600);
    });
  }

  // marquee: duplicate the rows so they loop
  const marquees = $$('.mq');
  if (!REDUCE) marquees.forEach(mq => {
    const tr = $('.mq__track', mq);
    Array.from(tr.children).forEach(n => { const c = n.cloneNode(true); c.setAttribute('aria-hidden', 'true'); c.removeAttribute('aria-label'); tr.appendChild(c); });
    mq.classList.add('is-on');
  });

  // latest release (standalone hosting only)
  const rel = $('[data-release]');
  if (rel && !window.__TS_ARTIFACT && window.fetch) {
    fetch('https://api.github.com/repos/CHINW3/ThorSport/releases/latest', { headers: { Accept: 'application/vnd.github+json' } })
      .then(r => (r.ok ? r.json() : null))
      .then(j => {
        if (!j || !j.tag_name) return;
        const when = j.published_at ? new Date(j.published_at).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }) : '';
        rel.textContent = `v${j.tag_name.replace(/^v/, '')}${when ? ' · ' + when : ''}`;
      })
      .catch(() => {});
  }

  /* ---------- scroll animation (GSAP + ScrollTrigger + Lenis) ---------- */
  let lenis = null;
  if (MOTION) {
    try {
      G.registerPlugin(ST);
      if (window.Lenis) {
        lenis = new window.Lenis({ lerp: .1, smoothWheel: true });
        lenis.on('scroll', ST.update);
        G.ticker.add(t => lenis.raf(t * 1000));
        G.ticker.lagSmoothing(0);
      }

      // hero intro
      const lines = $$('.hero .line__in'), fades = $$('.hero .fade');
      G.set(lines, { yPercent: 110, opacity: 1 });
      G.set(fades, { y: 24 });
      G.timeline({ defaults: { ease: 'expo.out' }, delay: .05 })
        .to(lines, { yPercent: 0, duration: 1.4, stagger: .12 })
        .to(fades, { opacity: 1, y: 0, duration: 1.1, stagger: .08 }, .35)
        .fromTo('.hero__stage', { opacity: 0, y: 24 }, { opacity: 1, y: 0, duration: 1.2 }, .3);

      // sections slide in as they enter (anything already passed shows at once)
      const rv = $$('.sec__head > *, .feat, .steps li, .install__note, .faq details, .foot__cta, .lgbar .mq');
      G.set(rv, { opacity: 0, y: 40 });
      ST.batch(rv, {
        start: 'top 90%', once: true,
        onEnter: b => {
          const past = b.filter(el => el.getBoundingClientRect().bottom < innerHeight * .15), rest = b.filter(el => !past.includes(el));
          if (past.length) G.set(past, { opacity: 1, y: 0 });
          if (rest.length) G.to(rest, { opacity: 1, y: 0, duration: 1, ease: 'power3.out', stagger: Math.min(.08, .5 / rest.length), overwrite: true });
        }
      });
      // opening FAQ answers changes the page height: re-measure so nothing below stays hidden
      $$('.faq details').forEach(d => d.addEventListener('toggle', () => ST.refresh()));

      // hero: the handheld tilts toward the pointer and leans back as you scroll away
      const dw = $('.hero .dev-wrap');
      if (dw) {
        G.set(dw, { transformPerspective: 1400 });
        G.to(dw, { rotationX: 14, y: 60, scale: .94, ease: 'none', scrollTrigger: { trigger: '.hero', start: 'top top', end: 'bottom top', scrub: .6 } });
        if (matchMedia('(hover: hover) and (pointer: fine)').matches) {
          const ry = G.quickTo(dw, 'rotationY', { duration: 1, ease: 'power3' });
          addEventListener('pointermove', e => ry((e.clientX / innerWidth - .5) * 12), { passive: true });
        }
      }
      // the odds line draws itself; the spec numbers count up
      $$('.spec b').forEach(b => { const m = b.textContent.match(/^(\d+)(.*)$/); if (!m) return; const o = { v: 0 }, end = +m[1];
        G.to(o, { v: end, duration: 1.4, ease: 'power2.out', scrollTrigger: { trigger: b, start: 'top 90%', once: true }, onUpdate: () => { b.textContent = Math.round(o.v) + m[2]; } }); });
      G.to('.progress', { scaleX: 1, ease: 'none', scrollTrigger: { start: 0, end: 'max', scrub: .3 } });

      // the marquee speeds up with scroll velocity
      let boost = 0, anims = null;
      ST.create({ onUpdate: self => { boost = Math.min(5, Math.abs(self.getVelocity()) / 500); } });
      G.ticker.add(() => {
        if (!anims || !anims.length) anims = $$('.mq.is-on .mq__track').flatMap(t => (t.getAnimations ? t.getAnimations() : []));
        boost *= .93;
        const rate = 1 + boost;
        anims.forEach(a => { if (Math.abs(a.playbackRate - rate) > .01) a.playbackRate = rate; });
      });

      if (document.fonts && document.fonts.ready) document.fonts.ready.then(() => ST.refresh());
      addEventListener('load', () => ST.refresh());
    } catch (err) {
      html.classList.remove('motion');
      $$('.hero .line__in, .hero .fade, .hero__stage').forEach(n => { n.style.opacity = 1; n.style.transform = 'none'; });
      console.error(err);
    }
  }

  // in-page links scroll smoothly and move focus to the target
  $$('a[href^="#"]').forEach(a => a.addEventListener('click', e => {
    const id = a.getAttribute('href').slice(1);
    const t = id && document.getElementById(id);
    if (!t) return;
    e.preventDefault();
    if (lenis) lenis.scrollTo(t, { duration: 1.3 });
    else t.scrollIntoView({ behavior: REDUCE ? 'auto' : 'smooth', block: 'start' });
    if (!t.hasAttribute('tabindex')) t.setAttribute('tabindex', '-1');
    t.focus({ preventScroll: true });
  }));

  window.__tsReady = true;
})();
