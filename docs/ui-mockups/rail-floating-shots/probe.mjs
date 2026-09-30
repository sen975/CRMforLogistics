// 悬浮侧块验收：左右两侧整块是否为圆角卡片、与中间消息区/顶部导航栏是否各留一条窄缝。
// 真前端 5173 → 真后端 8107，不 stub 任何 /api。
import fs from 'node:fs';

const PORT = Number(process.env.CDP_PORT || 9400);
const BASE = process.env.BASE || 'http://127.0.0.1:5173';
const OUT = process.env.OUT || '/tmp/mc-rail';
const CONTACT = process.env.CONTACT || 'd526bde8-6521-4a9d-8cc4-8ae28629a780';
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
  console.log(`  shot ${p} (${fs.statSync(p).size} bytes)`);
}

const MEASURE = `(() => {
  const R = (el) => { if (!el) return null; const b = el.getBoundingClientRect();
    return { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height), right: Math.round(b.right), bottom: Math.round(b.bottom) }; };
  const cs = (el, p) => el ? getComputedStyle(el)[p] : null;
  const header = document.querySelector('header.message-center-header');
  const body = document.querySelector('.message-center-body');
  const sidebar = document.querySelector('aside.message-center-sidebar');
  const main = document.querySelector('main.message-center-content');
  const panelEl = document.querySelector('.mc-detail-panel');
  const detail = panelEl ? panelEl.closest('.ant-layout-sider') : null;
  const sheet = document.querySelector('.message-center-thread-shell');
  const hb = header && header.getBoundingClientRect();
  const sb = sidebar && sidebar.getBoundingClientRect();
  const mb = main && main.getBoundingClientRect();
  const db = detail && detail.getBoundingClientRect();
  const tb = sheet && sheet.getBoundingClientRect();
  return {
    viewport: { w: window.innerWidth, h: window.innerHeight },
    headerBg: cs(header, 'backgroundColor'),
    header: R(header),
    bodyBg: cs(body, 'backgroundColor'),
    sidebar: sidebar ? { ...R(sidebar), radius: cs(sidebar, 'borderRadius'), margin: cs(sidebar, 'margin'),
      border: cs(sidebar, 'borderTopWidth') + ' ' + cs(sidebar, 'borderTopColor'), shadow: cs(sidebar, 'boxShadow'),
      bg: cs(sidebar, 'backgroundColor') } : null,
    content: main ? { ...R(main), padding: cs(main, 'padding'), bg: cs(main, 'backgroundColor') } : null,
    detail: detail ? { ...R(detail), radius: cs(detail, 'borderRadius'), margin: cs(detail, 'margin'),
      border: cs(detail, 'borderTopWidth') + ' ' + cs(detail, 'borderTopColor'), shadow: cs(detail, 'boxShadow'),
      bg: cs(detail, 'backgroundColor') } : null,
    sheet: R(sheet),
    gaps: {
      headerToSidebar: sb && hb ? Math.round(sb.top - hb.bottom) : null,
      headerToDetail: db && hb ? Math.round(db.top - hb.bottom) : null,
      headerToSheet: tb && hb ? Math.round(tb.top - hb.bottom) : null,
      sidebarToContentBox: sb && mb ? Math.round(mb.left - sb.right) : null,
      contentBoxToDetail: db && mb ? Math.round(db.left - mb.right) : null,
      sheetToSidebar: tb && sb ? Math.round(tb.left - sb.right) : null,
      sheetToDetail: tb && db ? Math.round(db.left - tb.right) : null,
      sidebarLeftEdge: sb ? Math.round(sb.left) : null,
      sidebarBottomEdge: sb ? Math.round(window.innerHeight - sb.bottom) : null,
      detailRightEdge: db ? Math.round(window.innerWidth - db.right) : null,
      detailBottomEdge: db ? Math.round(window.innerHeight - db.bottom) : null,
    },
    docOver: document.documentElement.scrollWidth - window.innerWidth,
  };
})()`;

const openPanel = `(() => { const b = document.querySelector('[aria-label="展开右侧栏"]'); if (!b) return false; b.click(); return true; })()`;
const closePanel = `(() => { const b = document.querySelector('[aria-label="收起右侧栏"]'); if (!b) return false; b.click(); return true; })()`;

const main = async () => {
  const wsUrl = await pickPageTarget();
  const ws = new WebSocket(wsUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('ws connect failed')); });

  const errors = [];
  ws.addEventListener('message', (ev) => {
    const m = JSON.parse(ev.data);
    if (m.method === 'Runtime.consoleAPICalled' && ['error', 'warning'].includes(m.params.type)) {
      errors.push(m.params.args.map((a) => a.value ?? a.description ?? '').join(' ').slice(0, 160));
    }
  });

  await rpc(ws, 'Page.enable');
  await rpc(ws, 'Runtime.enable');
  await rpc(ws, 'Emulation.setDeviceMetricsOverride', { width: 1512, height: 890, deviceScaleFactor: 2, mobile: false });

  console.log('1) 同源写令牌');
  await rpc(ws, 'Page.navigate', { url: `${BASE}/login` });
  await sleep(2200);
  await evaluate(ws, `localStorage.setItem('token', ${JSON.stringify(TOKEN)});
    localStorage.setItem('username', 'admin');
    localStorage.setItem('roles', JSON.stringify(['ADMIN'])); 'ok'`);

  console.log('2) 进入会话页并展开右栏');
  await rpc(ws, 'Page.navigate', { url: `${BASE}/conversations/contact/${CONTACT}` });
  let opened = false;
  for (let i = 0; i < 40; i++) {
    await sleep(400);
    opened = await evaluate(ws, openPanel);
    if (opened) break;
  }
  let ready = false;
  for (let i = 0; i < 60; i++) {
    await sleep(400);
    ready = await evaluate(ws, `(() => { const p = document.querySelector('.mc-detail-panel'); return !!p && p.getBoundingClientRect().width > 300; })()`);
    if (ready) break;
  }
  console.log('   展开 =', opened, ' 面板就绪 =', ready);
  if (!ready) throw new Error('详情面板未渲染：' + JSON.stringify(await evaluate(ws, `({ href: location.href, body: document.body.innerText.replace(/\\s+/g,' ').slice(0, 300) })`)));
  await sleep(2500);

  let m = await evaluate(ws, MEASURE);
  console.log('\n=== 1512 / 右栏展开 ===');
  console.log('  header :', JSON.stringify(m.header), m.headerBg);
  console.log('  sidebar:', JSON.stringify(m.sidebar));
  console.log('  content:', JSON.stringify(m.content));
  console.log('  detail :', JSON.stringify(m.detail));
  console.log('  sheet  :', JSON.stringify(m.sheet));
  console.log('  bodyBg :', m.bodyBg);
  console.log('  缝隙   :', JSON.stringify(m.gaps));
  console.log('  docOver =', m.docOver);
  await shot(ws, '01-整页-1512');
  if (m.sidebar) await shot(ws, '02-左栏-1512', { x: 0, y: 0, width: m.sidebar.right + 40, height: m.viewport.h });
  if (m.detail) await shot(ws, '03-右栏-1512', { x: m.detail.x - 40, y: 0, width: m.detail.w + 40, height: m.viewport.h });
  await shot(ws, '04-顶部缝隙带-1512', { x: 0, y: 40, width: m.viewport.w, height: 120 });

  console.log('\n3) 收起右栏（右侧缝不该残留）');
  await evaluate(ws, closePanel);
  await sleep(900);
  m = await evaluate(ws, MEASURE);
  console.log('  detail =', JSON.stringify(m.detail), ' 缝隙 =', JSON.stringify(m.gaps), ' docOver =', m.docOver);
  await shot(ws, '05-整页-右栏收起-1512');

  console.log('\n4) 1280 视口复核');
  await rpc(ws, 'Emulation.setDeviceMetricsOverride', { width: 1280, height: 800, deviceScaleFactor: 2, mobile: false });
  await sleep(1200);
  await evaluate(ws, openPanel);
  await sleep(1500);
  m = await evaluate(ws, MEASURE);
  console.log('  gaps =', JSON.stringify(m.gaps), ' docOver =', m.docOver, ' sidebarR =', m.sidebar?.radius, ' detailR =', m.detail?.radius);
  await shot(ws, '06-整页-1280');

  console.log('\n控制台 error/warning 条数 =', errors.length);
  errors.slice(0, 8).forEach((e) => console.log('   !', e));
  ws.close();
};

main().then(() => process.exit(0)).catch((e) => { console.error('FAILED', e); process.exit(1); });
