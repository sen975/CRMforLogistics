// 诊断：打开助手抽屉后，视口边缘那 1px 蓝线是什么（焦点环落在谁身上）。
import fs from 'node:fs';
const PORT = Number(process.env.CDP_PORT || 9433);
const BASE = process.env.BASE || 'http://127.0.0.1:5173';
const TOKEN = fs.readFileSync('/tmp/mc-vis-token.txt', 'utf8').trim();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function pickPageTarget() {
  const list = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json();
  const page = list.find((t) => t.type === 'page');
  if (!page) throw new Error('no page target');
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
      if (m.error) reject(new Error(`${method} -> ${JSON.stringify(m.error)}`)); else resolve(m.result);
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

const main = async () => {
  const ws = new WebSocket(await pickPageTarget());
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('ws fail')); });
  await rpc(ws, 'Page.enable');
  await rpc(ws, 'Runtime.enable');
  await rpc(ws, 'Emulation.setDeviceMetricsOverride', { width: 1512, height: 890, deviceScaleFactor: 2, mobile: false });

  await rpc(ws, 'Page.navigate', { url: `${BASE}/login` });
  await sleep(2000);
  await evaluate(ws, `localStorage.setItem('token', ${JSON.stringify(TOKEN)});
    localStorage.setItem('username', 'admin'); localStorage.setItem('roles', JSON.stringify(['ADMIN'])); 'ok'`);
  await rpc(ws, 'Page.navigate', { url: `${BASE}/conversations/contact/d526bde8-6521-4a9d-8cc4-8ae28629a780` });
  for (let i = 0; i < 50; i++) { await sleep(400); if (await evaluate(ws, `!!document.querySelector('[aria-label="AI 助手"]')`)) break; }
  await sleep(1500);

  await evaluate(ws, `document.querySelector('[aria-label="AI 助手"]').click()`);
  await sleep(1500);

  const info = await evaluate(ws, `(() => {
    const cs = (el,p) => el ? getComputedStyle(el)[p] : null;
    const a = document.activeElement;
    const path = (el) => { const out = []; let n = el; while (n && n !== document.body && out.length < 6) { out.push(n.tagName + (n.className ? '.' + String(n.className).split(' ').join('.') : '')); n = n.parentElement; } return out.join(' < '); };
    const drawer = document.querySelector('.ant-drawer');
    return {
      active: a ? a.tagName + '.' + String(a.className) : null,
      activePath: a ? path(a) : null,
      activeOutline: { width: cs(a,'outlineWidth'), style: cs(a,'outlineStyle'), color: cs(a,'outlineColor'), offset: cs(a,'outlineOffset') },
      activeRect: a ? (() => { const b = a.getBoundingClientRect(); return { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height) }; })() : null,
      drawerOutline: { width: cs(drawer,'outlineWidth'), style: cs(drawer,'outlineStyle'), color: cs(drawer,'outlineColor') },
      drawerCls: drawer ? drawer.className : null,
      drawerTabIndex: drawer ? drawer.getAttribute('tabindex') : null,
      wrapperOutline: (() => { const w = document.querySelector('.ant-drawer-content-wrapper'); return { width: cs(w,'outlineWidth'), style: cs(w,'outlineStyle'), color: cs(w,'outlineColor') }; })(),
      contentOutline: (() => { const w = document.querySelector('.ant-drawer-content'); return { width: cs(w,'outlineWidth'), style: cs(w,'outlineStyle'), color: cs(w,'outlineColor') }; })(),
      bodyOutline: { width: cs(document.body,'outlineWidth'), style: cs(document.body,'outlineStyle'), color: cs(document.body,'outlineColor') },
      htmlOutline: { width: cs(document.documentElement,'outlineWidth'), style: cs(document.documentElement,'outlineStyle'), color: cs(document.documentElement,'outlineColor') },
    };
  })()`);
  console.log(JSON.stringify(info, null, 1));
  ws.close();
};
main().then(() => process.exit(0)).catch((e) => { console.error('FAILED', e); process.exit(1); });
