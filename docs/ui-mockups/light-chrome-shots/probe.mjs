// 浅灰 chrome + 消息区:发送区 4:1 验收。
// 真前端 5173 → 真后端 8107，不 stub 任何 /api。
import fs from 'node:fs';

const PORT = Number(process.env.CDP_PORT || 9420);
const BASE = process.env.BASE || 'http://127.0.0.1:5173';
const OUT = process.env.OUT || '/tmp/mc-light';
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
  const sheet = document.querySelector('.message-center-thread-shell');
  const timeline = document.querySelector('.message-center-thread-timeline');
  const composer = document.querySelector('.message-center-thread-composer');
  const nameEl = document.querySelector('.contact-name');
  const previewEl = document.querySelector('.contact-preview');
  const avatarEl = document.querySelector('.contact-avatar');
  const activeCard = document.querySelector('.conversation-contact.is-active');
  const headerBtn = header && header.querySelector('.ant-btn');
  const headerTitle = header && header.querySelector('.ant-typography');
  const searchInput = document.querySelector('.message-center-conversation-search .ant-input');
  const tb = R(timeline), cb = R(composer);
  const submit = composer && composer.querySelector('.send-submit');
  const growEl = composer && composer.querySelector('.send-grow');
  const sb = R(submit), gb = R(growEl);
  return {
    composerFit: composer ? {
      clientH: composer.clientHeight,
      scrollH: composer.scrollHeight,
      overflows: composer.scrollHeight > composer.clientHeight + 1,
      submitVisible: !!(sb && cb && sb.bottom <= cb.bottom + 1 && sb.h > 0),
      submitBottom: sb ? sb.bottom : null,
      composerBottom: cb ? cb.bottom : null,
      growH: gb ? gb.h : null,
    } : null,
    viewport: { w: window.innerWidth, h: window.innerHeight },
    chrome: {
      headerBg: cs(header, 'backgroundColor'),
      headerBorderBottom: cs(header, 'borderBottomColor'),
      headerTitleColor: cs(headerTitle, 'color'),
      headerBtnColor: cs(headerBtn, 'color'),
      headerBtnBg: cs(headerBtn, 'backgroundColor'),
      bodyBg: cs(body, 'backgroundColor'),
      contentBg: cs(main, 'backgroundColor'),
      sidebarBg: cs(sidebar, 'backgroundColor'),
      sidebarBorder: cs(sidebar, 'borderTopColor'),
      searchInputBg: cs(searchInput, 'backgroundColor'),
    },
    list: {
      nameColor: cs(nameEl, 'color'),
      previewColor: cs(previewEl, 'color'),
      avatarBg: cs(avatarEl, 'backgroundColor'),
      avatarColor: cs(avatarEl, 'color'),
      activeCardBg: cs(activeCard, 'backgroundColor'),
    },
    ratio: {
      timeline: tb ? tb.h : null,
      composer: cb ? cb.h : null,
      value: tb && cb ? Number((tb.h / cb.h).toFixed(2)) : null,
      timelineMargin: cs(timeline, 'margin'),
      composerMargin: cs(composer, 'margin'),
      composerFlex: cs(composer, 'flex'),
      timelineFlex: cs(timeline, 'flex'),
    },
    sheet: R(sheet),
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

  console.log('2) 进入会话页');
  await rpc(ws, 'Page.navigate', { url: `${BASE}/conversations/contact/${CONTACT}` });
  let ready = false;
  for (let i = 0; i < 60; i++) {
    await sleep(400);
    ready = await evaluate(ws, `(() => { const t = document.querySelector('.message-center-thread-timeline'); const c = document.querySelector('.message-center-thread-composer'); return !!t && !!c && c.getBoundingClientRect().height > 60; })()`);
    if (ready) break;
  }
  console.log('   会话页就绪 =', ready);
  if (!ready) throw new Error('会话页未渲染：' + JSON.stringify(await evaluate(ws, `({ href: location.href, body: document.body.innerText.replace(/\\s+/g,' ').slice(0, 300) })`)));
  await sleep(2200);

  let m = await evaluate(ws, MEASURE);
  console.log('\n=== 1512 / 会话页 ===');
  console.log('  chrome :', JSON.stringify(m.chrome, null, 0));
  console.log('  list   :', JSON.stringify(m.list));
  console.log('  比例   :', JSON.stringify(m.ratio));
  console.log('  发送区完整度 :', JSON.stringify(m.composerFit));
  console.log('  sheet  :', JSON.stringify(m.sheet));
  console.log('  docOver =', m.docOver);
  await shot(ws, '01-会话页-整页-1512');
  await shot(ws, '02-左栏与顶栏-1512', { x: 0, y: 0, width: 360, height: m.viewport.h });
  await shot(ws, '03-顶部导航带-1512', { x: 0, y: 0, width: m.viewport.w, height: 90 });
  if (m.ratio.timeline) {
    const ay = m.sheet.y;
    await shot(ws, '04-消息区与发送区-1512', { x: m.sheet.x, y: ay, width: m.sheet.w, height: m.viewport.h - ay - 8 });
  }

  console.log('\n3) 展开右栏复看整体层次');
  await evaluate(ws, openPanel);
  await sleep(1600);
  m = await evaluate(ws, MEASURE);
  console.log('  比例 =', JSON.stringify(m.ratio), ' docOver =', m.docOver);
  await shot(ws, '05-会话页-右栏展开-1512');

  console.log('\n4) 功能页（/admin/platforms）复核 chrome 与画布');
  await rpc(ws, 'Page.navigate', { url: `${BASE}/admin/platforms` });
  for (let i = 0; i < 30; i++) { await sleep(400); if (await evaluate(ws, `!!document.querySelector('.mc-page-surface')`)) break; }
  await sleep(1400);
  const mp = await evaluate(ws, `(() => { const cs=(e,p)=>e?getComputedStyle(e)[p]:null;
    const h=document.querySelector('header.message-center-header'), b=document.querySelector('.message-center-body'),
          s=document.querySelector('aside.message-center-sidebar'), m=document.querySelector('main.message-center-content'),
          surf=document.querySelector('.mc-page-surface');
    return { headerBg: cs(h,'backgroundColor'), bodyBg: cs(b,'backgroundColor'), sidebarBg: cs(s,'backgroundColor'),
      contentBg: cs(m,'backgroundColor'), surfaceBg: cs(surf,'backgroundColor'), surfaceCount: document.querySelectorAll('.mc-page-surface').length,
      docOver: document.documentElement.scrollWidth - window.innerWidth };})()`);
  console.log('  ', JSON.stringify(mp));
  await shot(ws, '06-功能页-1512');

  console.log('\n5) 1280 视口复核比例');
  await rpc(ws, 'Emulation.setDeviceMetricsOverride', { width: 1280, height: 800, deviceScaleFactor: 2, mobile: false });
  await rpc(ws, 'Page.navigate', { url: `${BASE}/conversations/contact/${CONTACT}` });
  for (let i = 0; i < 40; i++) { await sleep(400); if (await evaluate(ws, `!!document.querySelector('.message-center-thread-composer')`)) break; }
  await sleep(1800);
  m = await evaluate(ws, MEASURE);
  console.log('  比例 =', JSON.stringify(m.ratio), ' docOver =', m.docOver);
  await shot(ws, '07-会话页-1280');

  console.log('\n控制台 error/warning 条数 =', errors.length);
  errors.slice(0, 8).forEach((e) => console.log('   !', e));
  ws.close();
};

main().then(() => process.exit(0)).catch((e) => { console.error('FAILED', e); process.exit(1); });
