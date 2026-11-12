// 真前端渲染验收探针：连真后端(5173 代理 -> 8107)，不 stub 任何 /api。
// 用途：确认「深色消息中心主题」在真实前端里真的渲染出来了，并截图存盘。
import fs from 'node:fs';

const PORT = Number(process.env.CDP_PORT || 9333);
const BASE = process.env.BASE || 'http://127.0.0.1:5173';
const OUT = process.env.OUT || '/tmp/mc-theme';
const TOKEN = fs.readFileSync('/tmp/mc-vis-token.txt', 'utf8').trim();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

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
  if (r.exceptionDetails) {
    throw new Error('probe threw: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
  }
  return r.result.value;
}

async function shot(ws, name) {
  const r = await rpc(ws, 'Page.captureScreenshot', { format: 'png' });
  const p = `${OUT}/${name}.png`;
  fs.writeFileSync(p, Buffer.from(r.data, 'base64'));
  const st = fs.statSync(p);
  console.log(`  shot ${p} (${st.size} bytes)`);
  return p;
}

const MEASURE = `(() => {
  const R = (sel) => { const el = document.querySelector(sel); return el ? el.getBoundingClientRect() : null; };
  const bot = (sel) => { const el = document.querySelector(sel); return el ? Math.round(el.getBoundingClientRect().bottom) : -1; };
  const styleOf = (sel) => { const el = document.querySelector(sel); if (!el) return null; const c = getComputedStyle(el); return { bg: c.backgroundColor, color: c.color }; };
  const header = document.querySelector('.message-center-header');
  const sidebar = document.querySelector('.message-center-sidebar');
  const content = document.querySelector('.message-center-content');
  const rows = document.querySelectorAll('.conversation-contact, .conversation-group');
  const avatars = document.querySelectorAll('.contact-avatar');
  const dots = document.querySelectorAll('.contact-channel-dot');
  const filterRows = document.querySelectorAll('.conversation-filter-row');
  return {
    viewport: { w: innerWidth, h: innerHeight },
    path: location.pathname,
    stillDark: {
      header: header ? getComputedStyle(header).backgroundColor : null,
      sidebar: sidebar ? getComputedStyle(sidebar).backgroundColor : null,
      content: content ? getComputedStyle(content).backgroundColor : null,
    },
    headerText: header ? getComputedStyle(header.querySelector('.ant-typography') || header).color : null,
    rowCount: rows.length,
    avatarCount: avatars.length,
    dotCount: dots.length,
    maxDotsPerAvatar: Math.max(0, ...[...avatars].map((a) => a.querySelectorAll('.contact-channel-dot').length)),
    filterRows: [...filterRows].map((r) => r.innerText.replace(/\\s+/g, ' ').trim()),
    docOver: document.documentElement.scrollHeight - innerHeight,
    contentOver: content ? content.scrollHeight - content.clientHeight : -1,
    lastRowBottom: bot('.conversation-contact:last-of-type'),
    sidebarBottom: sidebar ? Math.round(sidebar.getBoundingClientRect().bottom) : -1,
  };
})()`;

const main = async () => {
  const wsUrl = await pickPageTarget();
  const ws = new WebSocket(wsUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('ws connect failed')); });

  const errors = [];
  ws.addEventListener('message', (ev) => {
    const m = JSON.parse(ev.data);
    if (m.method === 'Runtime.consoleAPICalled' && ['error', 'warning'].includes(m.params.type)) {
      errors.push(m.params.args.map((a) => a.value ?? a.description ?? '').join(' ').slice(0, 200));
    }
  });

  await rpc(ws, 'Page.enable');
  await rpc(ws, 'Runtime.enable');

  const VIEWPORTS = JSON.parse(process.env.VIEWPORTS || '[[1512,890]]');
  const [w, h] = VIEWPORTS[0];
  await rpc(ws, 'Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 2, mobile: false });

  console.log('1) 落到同源登录页并写令牌');
  await rpc(ws, 'Page.navigate', { url: `${BASE}/login` });
  await sleep(2500);
  await evaluate(ws, `localStorage.setItem('token', ${JSON.stringify(TOKEN)});
    localStorage.setItem('username', 'admin');
    localStorage.setItem('roles', JSON.stringify(['ADMIN']));
    'ok'`);

  console.log('2) 整页导航到工作台');
  await rpc(ws, 'Page.navigate', { url: `${BASE}/` });
  let ready = false;
  for (let i = 0; i < 75; i++) {
    await sleep(400);
    ready = await evaluate(ws, `document.querySelectorAll('.conversation-contact, .conversation-group').length > 0`);
    if (ready) break;
  }
  console.log('   列表就绪 =', ready, ' 用时约', (75 * 0.4).toFixed(0), 's 上限');
  await sleep(1500);

  const before = await evaluate(ws, MEASURE);
  if (Object.keys(before).length < 6) throw new Error('probe broken: ' + JSON.stringify(before));
  console.log('   度量:', JSON.stringify(before, null, 1));
  await shot(ws, '01-真前端-工作台-1512');

  console.log('3) 打开第一条会话 → 会话工作区');
  await evaluate(ws, `(() => { const el = document.querySelector('.conversation-contact'); if (el) el.click(); return !!el; })()`);
  await sleep(3000);
  const thread = await evaluate(ws, `(() => {
    const shell = document.querySelector('.message-center-thread-shell');
    const tl = document.querySelector('.message-center-thread-timeline');
    const comp = document.querySelector('.message-center-thread-composer');
    const g = (el) => { if (!el) return null; const c = getComputedStyle(el); return { bg: c.backgroundColor, radius: c.borderRadius }; };
    return { path: location.pathname, shell: g(shell), timeline: g(tl), composer: g(comp),
             docOver: document.documentElement.scrollHeight - innerHeight };
  })()`);
  console.log('   会话区:', JSON.stringify(thread));
  await shot(ws, '02-真前端-会话工作区-1512');

  console.log('4) 换 1280 档再截一张');
  await rpc(ws, 'Emulation.setDeviceMetricsOverride', { width: 1280, height: 800, deviceScaleFactor: 2, mobile: false });
  await sleep(1200);
  await shot(ws, '03-真前端-会话工作区-1280');

  if (errors.length) console.log('控制台告警/错误:', JSON.stringify(errors.slice(0, 8), null, 1));
  else console.log('控制台无 error/warning');

  ws.close();
};

main().then(() => { console.log('DONE'); process.exit(0); }).catch((e) => { console.error('FAIL', e.message); process.exit(1); });
