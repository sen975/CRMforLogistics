// 功能页容器验收：上方导航栏可达的每个功能页，内容区是否被同款白卡包住、
// 与左栏/顶栏是否各留一条窄缝、页面自带内边距是否已被外卡接管。
// 真前端 5173 → 真后端 8107，不 stub 任何 /api。
import fs from 'node:fs';

const PORT = Number(process.env.CDP_PORT || 9410);
const BASE = process.env.BASE || 'http://127.0.0.1:5173';
const OUT = process.env.OUT || '/tmp/mc-pages';
const TOKEN = fs.readFileSync('/tmp/mc-vis-token.txt', 'utf8').trim();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
fs.mkdirSync(OUT, { recursive: true });

async function pickPageTarget() {
  const list = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json();
  const page = list.find((t) => t.type === 'page');
  if (!page) throw new Error('no page target: ' + JSON.stringify(list));
  return page.webSocketDebuggerUrl;
}

let seq = 0;
function rpc(ws, method, params = {}) {
  return new Promise((resolve, reject) => {
    const msgId = ++seq;
    const onMsg = (ev) => {
      const m = JSON.parse(ev.data);
      if (m.id !== msgId) return;
      ws.removeEventListener('message', onMsg);
      if (m.error) reject(new Error(`${method} -> ${JSON.stringify(m.error)}`));
      else resolve(m.result);
    };
    ws.addEventListener('message', onMsg);
    ws.send(JSON.stringify({ id: msgId, method, params }));
  });
}

async function evaluate(ws, expression) {
  const r = await rpc(ws, 'Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error('probe threw: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
  return r.result.value;
}

async function shot(ws, name, clip) {
  const params = { format: 'png' };
  if (clip) params.clip = { ...clip, scale: 2 };
  const r = await rpc(ws, 'Page.captureScreenshot', params);
  const p = `${OUT}/${name}.png`;
  fs.writeFileSync(p, Buffer.from(r.data, 'base64'));
  console.log(`  shot ${p} (${Math.round(fs.statSync(p).size / 1024)} KB)`);
}

const MEASURE = `(() => {
  const R = (el) => { if (!el) return null; const b = el.getBoundingClientRect();
    return { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height), right: Math.round(b.right), bottom: Math.round(b.bottom) }; };
  const cs = (el, p) => el ? getComputedStyle(el)[p] : null;
  const header = document.querySelector('header.message-center-header');
  const sidebar = document.querySelector('aside.message-center-sidebar');
  const body = document.querySelector('.message-center-body');
  const surfaces = document.querySelectorAll('.mc-page-surface');
  const surface = surfaces[0];
  const detailSider = document.querySelector('.message-center-detail');
  const shell = document.querySelector('.message-center-thread-shell');
  const hb = header && header.getBoundingClientRect();
  const sb = sidebar && sidebar.getBoundingClientRect();
  const cb = surface && surface.getBoundingClientRect();
  const db = detailSider && detailSider.getBoundingClientRect();
  const first = surface && surface.firstElementChild;
  const bodyPad = body ? cs(body, 'padding') : null;
  return {
    href: location.pathname,
    viewport: { w: window.innerWidth, h: window.innerHeight },
    surfaces: surfaces.length,
    threadShell: !!shell,
    bodyPad,
    surface: surface ? {
      ...R(surface),
      radius: cs(surface, 'borderTopLeftRadius'),
      border: cs(surface, 'borderTopWidth') + ' ' + cs(surface, 'borderTopColor'),
      bg: cs(surface, 'backgroundColor'),
      shadow: cs(surface, 'boxShadow').slice(0, 60),
      padding: cs(surface, 'padding'),
      overflowY: cs(surface, 'overflowY'),
      scrollH: surface.scrollHeight,
      clientH: surface.clientHeight,
      innerScroll: surface.scrollHeight > surface.clientHeight + 1,
    } : null,
    first: first ? {
      tag: first.tagName.toLowerCase(),
      cls: (first.className && first.className.toString().slice(0, 40)) || '',
      padding: cs(first, 'padding'),
      x: Math.round(first.getBoundingClientRect().x),
    } : null,
    gaps: {
      headerToSurface: cb && hb ? Math.round(cb.top - hb.bottom) : null,
      sidebarToSurface: cb && sb ? Math.round(cb.left - sb.right) : null,
      surfaceToDetail: cb && db ? Math.round(db.left - cb.right) : null,
      surfaceLeftEdge: cb ? Math.round(cb.left) : null,
      surfaceRightEdge: cb ? Math.round(window.innerWidth - cb.right) : null,
      surfaceBottomEdge: cb ? Math.round(window.innerHeight - cb.bottom) : null,
    },
    docOver: document.documentElement.scrollWidth - window.innerWidth,
    text: document.body.innerText.replace(/\\s+/g, ' ').slice(0, 70),
  };
})()`;

const PAGES = [
  ['admin', '/admin'],
  ['admin-platforms', '/admin/platforms'],
  ['admin-whatsapp-accounts', '/admin/whatsapp/accounts'],
  ['admin-template-approvals', '/admin/whatsapp/template-approvals'],
  ['settings-wecom', '/settings/wecom'],
  ['settings-channels', '/settings/channels'],
  ['settings-users', '/settings/users'],
  ['send', '/send'],
  ['templates', '/templates'],
  ['broadcasts', '/broadcasts'],
  ['address-book-chatapp', '/address-book/chatapp'],
  ['address-book-phone', '/address-book/phone'],
  ['address-book-email', '/address-book/email'],
  ['topic-repository', '/topic-repository'],
  ['phone-repository', '/phone-repository'],
  ['todo-calendar', '/todo-calendar'],
  ['home', '/'],
];

const main = async () => {
  const wsUrl = await pickPageTarget();
  const ws = new WebSocket(wsUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('ws connect failed')); });

  const errors = [];
  ws.addEventListener('message', (ev) => {
    const m = JSON.parse(ev.data);
    if (m.method === 'Runtime.consoleAPICalled' && ['error'].includes(m.params.type)) {
      errors.push(m.params.args.map((a) => a.value ?? a.description ?? '').join(' ').slice(0, 160));
    }
  });

  await rpc(ws, 'Page.enable');
  await rpc(ws, 'Runtime.enable');
  await rpc(ws, 'Emulation.setDeviceMetricsOverride', { width: 1512, height: 890, deviceScaleFactor: 2, mobile: false });

  console.log('0) 写登录态');
  await rpc(ws, 'Page.navigate', { url: `${BASE}/login` });
  await sleep(2200);
  await evaluate(ws, `localStorage.setItem('token', ${JSON.stringify(TOKEN)});
    localStorage.setItem('username', 'admin');
    localStorage.setItem('roles', JSON.stringify(['ADMIN'])); 'ok'`);

  const summary = [];
  for (const [name, path] of PAGES) {
    await rpc(ws, 'Page.navigate', { url: `${BASE}${path}` });
    let m = null;
    for (let i = 0; i < 24; i++) {
      await sleep(400);
      try { m = await evaluate(ws, MEASURE); } catch { continue; }
      if (m && m.surface && m.text.length > 8) break;
    }
    await sleep(1200);
    m = await evaluate(ws, MEASURE);
    const g = m.gaps;
    const ok = m.surfaces === 1
      && g.headerToSurface === 10 && g.sidebarToSurface === 10
      && g.surfaceRightEdge === 10 && g.surfaceBottomEdge === 10
      && m.docOver === 0;
    console.log(`\n=== ${name} (${path}) ${ok ? '✅' : '⚠️'} ===`);
    console.log('  surface:', JSON.stringify(m.surface));
    console.log('  first  :', JSON.stringify(m.first));
    console.log('  gaps   :', JSON.stringify(g), ' docOver =', m.docOver, ' 卡片数 =', m.surfaces, ' shell =', m.threadShell);
    console.log('  文本   :', m.text);
    summary.push({ name, path, ok, h2s: g.headerToSurface, s2s: g.sidebarToSurface, right: g.surfaceRightEdge, bottom: g.surfaceBottomEdge, r: m.surface?.radius, pad: m.surface?.padding, inner: m.surface?.innerScroll, firstPad: m.first?.padding, docOver: m.docOver });
    if (m.surface) {
      await shot(ws, `${name}-卡片`, { x: Math.max(0, m.surface.x - 14), y: Math.max(0, m.surface.y - 14), width: Math.min(m.viewport.w, m.surface.w + 28), height: Math.min(m.viewport.h, m.surface.h + 28) });
    }
  }

  console.log('\n\n############ 汇总 ############');
  for (const r of summary) {
    console.log(`${r.ok ? '✅' : '⚠️'} ${r.name.padEnd(26)} 顶缝=${r.h2s} 左缝=${r.s2s} 右缝=${r.right} 下缝=${r.bottom} 半径=${r.r} 卡内padding=${r.pad} 首子padding=${r.firstPad} 内滚动=${r.inner} docOver=${r.docOver}`);
  }

  console.log('\n5) 会话页不该被包（回归）');
  await rpc(ws, 'Page.navigate', { url: `${BASE}/conversations/contact/d526bde8-6521-4a9d-8cc4-8ae28629a780` });
  await sleep(3500);
  const conv = await evaluate(ws, MEASURE);
  console.log('  surfaces =', conv.surfaces, ' threadShell =', conv.threadShell, ' docOver =', conv.docOver);
  await shot(ws, '99-会话页-回归-1512');

  console.log('\n控制台 error 条数 =', errors.length);
  errors.slice(0, 8).forEach((e) => console.log('   !', e));
  ws.close();
};

main().then(() => process.exit(0)).catch((e) => { console.error('FAILED', e); process.exit(1); });
