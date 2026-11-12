// 诊断 390 视口下抽屉右溢 7px 的来源。
import fs from 'node:fs';
const PORT = Number(process.env.CDP_PORT || 9435);
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
    const onMsg = (ev) => { const m = JSON.parse(ev.data); if (m.id !== msgId) return;
      ws.removeEventListener('message', onMsg);
      if (m.error) reject(new Error(`${method} -> ${JSON.stringify(m.error)}`)); else resolve(m.result); };
    ws.addEventListener('message', onMsg);
    ws.send(JSON.stringify({ id: msgId, method, params }));
  });
}
async function evaluate(ws, expression) {
  const r = await rpc(ws, 'Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error('threw: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
  return r.result.value;
}
const main = async () => {
  const ws = new WebSocket(await pickPageTarget());
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('ws fail')); });
  await rpc(ws, 'Page.enable'); await rpc(ws, 'Runtime.enable');
  await rpc(ws, 'Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });
  await rpc(ws, 'Page.navigate', { url: `${BASE}/login` });
  await sleep(2000);
  await evaluate(ws, `localStorage.setItem('token', ${JSON.stringify(TOKEN)});
    localStorage.setItem('username','admin'); localStorage.setItem('roles', JSON.stringify(['ADMIN'])); 'ok'`);
  await rpc(ws, 'Page.navigate', { url: `${BASE}/conversations/contact/d526bde8-6521-4a9d-8cc4-8ae28629a780` });
  for (let i = 0; i < 50; i++) { await sleep(400); if (await evaluate(ws, `!!document.querySelector('[aria-label="AI 助手"]')`)) break; }
  await sleep(1500);
  await evaluate(ws, `document.querySelector('[aria-label="AI 助手"]').click()`);
  await sleep(1600);
  const info = await evaluate(ws, `(() => {
    const R = (el) => { if (!el) return null; const b = el.getBoundingClientRect();
      return { x: Math.round(b.x*10)/10, right: Math.round(b.right*10)/10, w: Math.round(b.width*10)/10 }; };
    const drawer = document.querySelector('.ant-drawer');
    const wrapper = document.querySelector('.ant-drawer-content-wrapper');
    const content = document.querySelector('.ant-drawer-content');
    const p = document.createElement('div'); p.style.cssText = 'width:100vw;height:0;position:absolute';
    document.body.appendChild(p); const vw = p.getBoundingClientRect().width; p.remove();
    const q = document.createElement('div'); q.style.cssText = 'width:100%;height:0;position:absolute';
    document.body.appendChild(q); const pct = q.getBoundingClientRect().width; q.remove();
    return {
      innerW: window.innerWidth, docClientW: document.documentElement.clientWidth,
      mq767: matchMedia('(max-width: 767px)').matches,
      vw100: vw, bodyW: pct,
      inlineWidth: wrapper ? wrapper.style.width : null,
      wrapper: R(wrapper), wrapperComputedW: wrapper ? getComputedStyle(wrapper).width : null,
      wrapperPadding: wrapper ? getComputedStyle(wrapper).padding : null,
      wrapperPosition: wrapper ? getComputedStyle(wrapper).position : null,
      drawer: R(drawer), drawerPos: drawer ? getComputedStyle(drawer).position : null,
      drawerOverflow: drawer ? getComputedStyle(drawer).overflow : null,
      content: R(content),
      bodyScrollW: document.body.scrollWidth,
      docScrollW: document.documentElement.scrollWidth,
      bodyOverflowX: getComputedStyle(document.body).overflowX,
    };
  })()`);
  console.log(JSON.stringify(info, null, 1));
  ws.close();
};
main().then(() => process.exit(0)).catch((e) => { console.error('FAILED', e); process.exit(1); });
