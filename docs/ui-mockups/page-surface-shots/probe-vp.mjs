// 窄视口复核：功能页卡片在 1280 与 390（移动端）下是否仍成立。
import fs from 'node:fs';

const PORT = Number(process.env.CDP_PORT || 9411);
const BASE = 'http://127.0.0.1:5173';
const OUT = '/tmp/mc-pages';
const TOKEN = fs.readFileSync('/tmp/mc-vis-token.txt', 'utf8').trim();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function pickPageTarget() {
  const list = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json();
  return list.find((t) => t.type === 'page').webSocketDebuggerUrl;
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
  if (r.exceptionDetails) throw new Error('threw: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
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
  const surfaces = document.querySelectorAll('.mc-page-surface');
  const s = surfaces[0];
  const hb = header && header.getBoundingClientRect();
  const sb = sidebar && sidebar.getBoundingClientRect();
  const cb = s && s.getBoundingClientRect();
  return {
    vp: { w: innerWidth, h: innerHeight },
    surfaces: surfaces.length,
    mobileSider: !!sidebar,
    radius: s ? cs(s, 'borderTopLeftRadius') : null,
    padding: s ? cs(s, 'padding') : null,
    gaps: {
      top: cb && hb ? Math.round(cb.top - hb.bottom) : null,
      left: cb && sb ? Math.round(cb.left - sb.right) : cb ? Math.round(cb.left) : null,
      right: cb ? Math.round(innerWidth - cb.right) : null,
      bottom: cb ? Math.round(innerHeight - cb.bottom) : null,
    },
    docOver: document.documentElement.scrollWidth - innerWidth,
    text: document.body.innerText.replace(/\\s+/g, ' ').slice(0, 60),
  };
})()`;

const main = async () => {
  const ws = new WebSocket(await pickPageTarget());
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('ws fail')); });
  await rpc(ws, 'Page.enable');
  await rpc(ws, 'Runtime.enable');

  await rpc(ws, 'Emulation.setDeviceMetricsOverride', { width: 1512, height: 890, deviceScaleFactor: 1, mobile: false });
  await rpc(ws, 'Page.navigate', { url: `${BASE}/login` });
  await sleep(2200);
  await evaluate(ws, `localStorage.setItem('token', ${JSON.stringify(TOKEN)});
    localStorage.setItem('username','admin'); localStorage.setItem('roles', JSON.stringify(['ADMIN'])); 'ok'`);

  for (const [label, vp, pages] of [
    ['1280', { width: 1280, height: 800, deviceScaleFactor: 2, mobile: false }, [['admin', '/admin'], ['templates', '/templates']]],
    ['390', { width: 390, height: 844, deviceScaleFactor: 2, mobile: true }, [['admin', '/admin'], ['todo-calendar', '/todo-calendar']]],
  ]) {
    await rpc(ws, 'Emulation.setDeviceMetricsOverride', vp);
    for (const [name, path] of pages) {
      await rpc(ws, 'Page.navigate', { url: `${BASE}${path}` });
      await sleep(3600);
      const m = await evaluate(ws, MEASURE);
      console.log(`\n=== ${label}px / ${name} ===`);
      console.log('  卡片数 =', m.surfaces, ' 移动端左栏 =', m.mobileSider, ' 半径 =', m.radius, ' padding =', m.padding);
      console.log('  缝隙 =', JSON.stringify(m.gaps), ' docOver =', m.docOver);
      console.log('  文本 =', m.text);
      await shot(ws, `vp${label}-${name}`);
    }
  }
  ws.close();
};
main().then(() => process.exit(0)).catch((e) => { console.error('FAILED', e); process.exit(1); });
