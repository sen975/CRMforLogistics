// 快速诊断：会话页到底渲染了什么、tab 列表在哪、电话仓库页有没有出来。
import fs from 'node:fs';
const PORT = Number(process.env.CDP_PORT || 9431);
const BASE = process.env.BASE || 'http://127.0.0.1:5173';
const TOKEN = fs.readFileSync('/tmp/mc-vis-token.txt', 'utf8').trim();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function pickPageTarget() {
  const list = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json();
  return list.find((t) => t.type === 'page').webSocketDebuggerUrl;
}
let seq = 0;
function rpc(ws, method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++seq;
    const on = (ev) => { const m = JSON.parse(ev.data); if (m.id !== id) return; ws.removeEventListener('message', on);
      if (m.error) reject(new Error(method + ' ' + JSON.stringify(m.error))); else resolve(m.result); };
    ws.addEventListener('message', on);
    ws.send(JSON.stringify({ id, method, params }));
  });
}
const ev = async (ws, e) => { const r = await rpc(ws, 'Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text); return r.result.value; };

const main = async () => {
  const ws = new WebSocket(await pickPageTarget());
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('ws fail')); });
  await rpc(ws, 'Page.enable'); await rpc(ws, 'Runtime.enable');
  await rpc(ws, 'Emulation.setDeviceMetricsOverride', { width: 1512, height: 890, deviceScaleFactor: 1, mobile: false });

  for (const url of [
    `${BASE}/conversations/contact/d526bde8-6521-4a9d-8cc4-8ae28629a780?channel=phone`,
    `${BASE}/conversations/contact/a0e2802f-87ae-4e4a-8ff5-baafa21adf03?channel=phone`,
    `${BASE}/phone-repository`,
  ]) {
    await rpc(ws, 'Page.navigate', { url: `${BASE}/login` });
    await sleep(1500);
    await ev(ws, `localStorage.setItem('token', ${JSON.stringify(TOKEN)}); localStorage.setItem('username','admin'); localStorage.setItem('roles', JSON.stringify(['ADMIN'])); 'ok'`);
    await rpc(ws, 'Page.navigate', { url });
    await sleep(3500);
    const info = await ev(ws, `({
      href: location.href,
      tabs: [...document.querySelectorAll('.send-channel-tabs .ant-tabs-tab')].map(e => e.innerText.trim()),
      tabPanes: [...document.querySelectorAll('.send-composer .ant-tabs-tabpane')].length,
      hasSendForm: !!document.querySelector('.send-form'),
      hasSendComposer: !!document.querySelector('.send-composer'),
      buttons: [...document.querySelectorAll('button')].map(b => b.innerText.trim()).filter(Boolean).slice(0, 14),
      title: (document.querySelector('h1,h2,h3,h4') || {}).innerText || null,
      text: document.body.innerText.replace(/\\s+/g,' ').slice(0, 260),
    })`);
    console.log('\n=== ' + url + ' ===');
    console.log(JSON.stringify(info, null, 1));
  }
  ws.close();
};
main().catch((e) => { console.error('FAILED:', e.message); process.exit(1); });
