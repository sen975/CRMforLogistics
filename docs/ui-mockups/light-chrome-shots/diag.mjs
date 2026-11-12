import fs from 'node:fs';
const PORT = Number(process.env.CDP_PORT || 9422);
const BASE = 'http://127.0.0.1:5173';
const CONTACT = 'd526bde8-6521-4a9d-8cc4-8ae28629a780';
const TOKEN = fs.readFileSync('/tmp/mc-vis-token.txt', 'utf8').trim();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function pick() {
  const list = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json();
  return list.find((t) => t.type === 'page').webSocketDebuggerUrl;
}
let seq = 0;
function rpc(ws, method, params = {}) {
  return new Promise((res, rej) => {
    const id = ++seq;
    const on = (ev) => { const m = JSON.parse(ev.data); if (m.id !== id) return; ws.removeEventListener('message', on); m.error ? rej(new Error(JSON.stringify(m.error))) : res(m.result); };
    ws.addEventListener('message', on);
    ws.send(JSON.stringify({ id, method, params }));
  });
}
const ev = async (ws, e) => {
  const r = await rpc(ws, 'Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
  return r.result.value;
};
const DIAG = `(() => {
  const row = document.querySelector('.send-meta-row .ant-form-item-row');
  const lab = document.querySelector('.send-meta-row .ant-form-item-label');
  const ctl = document.querySelector('.send-meta-row .ant-form-item-control');
  const item = document.querySelector('.send-meta-row .ant-form-item');
  const metaRow = document.querySelector('.send-meta-row');
  const cs = (e, p) => e ? getComputedStyle(e)[p] : null;
  const R = (e) => { if (!e) return null; const b = e.getBoundingClientRect(); return { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height) }; };
  const composer = document.querySelector('.message-center-thread-composer');
  const parts = composer ? [...composer.querySelectorAll('.send-channel-tabs .ant-tabs-nav, .send-mode-tabs .ant-tabs-nav, .send-meta-row, .send-grow, .send-toolbar')].map((e) => ({ cls: e.className.split(' ').slice(0,2).join('.'), ...R(e) })) : null;
  return {
    formClass: document.querySelector('form.send-form') ? document.querySelector('form.send-form').className : null,
    rowClass: row ? row.className : null,
    rowDisplay: cs(row, 'display'), rowDir: cs(row, 'flexDirection'), rowWrap: cs(row, 'flexWrap'),
    labRect: R(lab), labWidth: cs(lab, 'width'), labFlex: cs(lab, 'flex'), labPad: cs(lab, 'padding'),
    ctlRect: R(ctl), ctlFlex: cs(ctl, 'flex'),
    itemRect: R(item), metaRowRect: R(metaRow),
    metaRowCols: cs(metaRow, 'gridTemplateColumns'),
    composerParts: parts,
    composerH: composer ? composer.clientHeight : null,
    composerScrollH: composer ? composer.scrollHeight : null,
  };
})()`;
const main = async () => {
  const ws = new WebSocket(await pick());
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = () => j(new Error('ws')); });
  await rpc(ws, 'Page.enable'); await rpc(ws, 'Runtime.enable');
  await rpc(ws, 'Emulation.setDeviceMetricsOverride', { width: 1512, height: 890, deviceScaleFactor: 2, mobile: false });
  await rpc(ws, 'Page.navigate', { url: `${BASE}/login` });
  await sleep(2200);
  await ev(ws, `localStorage.setItem('token', ${JSON.stringify(TOKEN)}); localStorage.setItem('roles', JSON.stringify(['ADMIN'])); 'ok'`);
  await rpc(ws, 'Page.navigate', { url: `${BASE}/conversations/contact/${CONTACT}` });
  for (let i = 0; i < 60; i++) { await sleep(400); if (await ev(ws, `!!document.querySelector('.message-center-thread-composer .send-meta-row')`)) break; }
  await sleep(2000);
  console.log(JSON.stringify(await ev(ws, DIAG), null, 2));
  ws.close();
};
main().then(() => process.exit(0)).catch((e) => { console.error('FAILED', e); process.exit(1); });
