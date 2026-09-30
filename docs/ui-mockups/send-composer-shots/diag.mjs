// 诊断：发送区定高为何没生效 + 切换渠道时 timeline 是否被滚动重置。
import fs from 'node:fs';
const PORT = 9333, BASE = 'http://127.0.0.1:5173';
const CONTACT_NAME = process.env.CONTACT_NAME || '守望';
const TOKEN = fs.readFileSync('/tmp/mc-vis-token.txt', 'utf8').trim();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
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

const DIAG = `(() => {
  const q = (s) => document.querySelector(s);
  const H = (s) => { const e = q(s); return e ? Math.round(e.getBoundingClientRect().height) : null; };
  const c = q('.message-center-thread-composer');
  const cs = c ? getComputedStyle(c) : null;
  const tl = q('.message-center-thread-timeline');
  return {
    composerRect: H('.message-center-thread-composer'),
    composerComputedHeight: cs ? cs.height : null,
    composerComputedMaxH: cs ? cs.maxHeight : null,
    composerBoxSizing: cs ? cs.boxSizing : null,
    composerDisplay: cs ? cs.display : null,
    composerFlex: cs ? cs.flex : null,
    vh38: Math.round(0.38 * innerHeight),
    sendComposer: H('.send-composer'),
    tabs: H('.send-channel-tabs'),
    holder: H('.send-composer .ant-tabs-content-holder'),
    content: H('.send-composer .ant-tabs-content'),
    tabpanes: [...document.querySelectorAll('.send-composer .ant-tabs-tabpane')].map((e) => Math.round(e.getBoundingClientRect().height)),
    forms: [...document.querySelectorAll('.send-form')].map((e) => Math.round(e.getBoundingClientRect().height)),
    timeline: H('.message-center-thread-timeline'),
    timelineScrollTop: tl ? Math.round(tl.scrollTop) : null,
    timelineScrollHeight: tl ? tl.scrollHeight : null,
    timelineClientHeight: tl ? tl.clientHeight : null,
  };
})()`;

const clickChip = (n) => `(() => { const t=[...document.querySelectorAll('.send-channel-tabs > .ant-tabs-nav .ant-tabs-tab')].find(e=>e.innerText.trim()===${JSON.stringify(n)}); if(t)t.click(); return !!t; })()`;

const main = async () => {
  const list = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json();
  const ws = new WebSocket(list.find((t) => t.type === 'page').webSocketDebuggerUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('ws')); });
  await rpc(ws, 'Page.enable'); await rpc(ws, 'Runtime.enable');
  await rpc(ws, 'Emulation.setDeviceMetricsOverride', { width: 1512, height: 890, deviceScaleFactor: 1, mobile: false });
  await rpc(ws, 'Page.navigate', { url: `${BASE}/login` }); await sleep(2500);
  await ev(ws, `localStorage.setItem('token', ${JSON.stringify(TOKEN)}); localStorage.setItem('username','admin'); localStorage.setItem('roles','["ADMIN"]'); 'ok'`);
  await rpc(ws, 'Page.navigate', { url: `${BASE}/` });
  for (let i = 0; i < 50; i++) { await sleep(400); if (await ev(ws, `!!document.querySelector('.conversation-contact')`)) break; }
  await ev(ws, `(()=>{const r=[...document.querySelectorAll('.conversation-contact,.conversation-group')].find(e=>e.innerText.includes(${JSON.stringify(CONTACT_NAME)})); if(r) r.click(); return !!r;})()`);
  for (let i = 0; i < 50; i++) { await sleep(400); if (await ev(ws, `!!document.querySelector('.send-toolbar')`)) break; }
  await sleep(1800);

  console.log('=== 定高诊断（邮件） ===');
  console.log(JSON.stringify(await ev(ws, DIAG), null, 1));

  console.log('=== 滚动位置：先滚到底，再切渠道 ===');
  await ev(ws, `(()=>{const t=document.querySelector('.message-center-thread-timeline'); if(t) t.scrollTop = t.scrollHeight; return 1;})()`);
  await sleep(600);
  console.log('  滚到底后 scrollTop =', await ev(ws, `Math.round(document.querySelector('.message-center-thread-timeline').scrollTop)`));
  for (const name of ['ChatApp', '电话记录', '邮件']) {
    await ev(ws, clickChip(name));
    await sleep(1500);
    const st = await ev(ws, `(()=>{const t=document.querySelector('.message-center-thread-timeline'); return t?Math.round(t.scrollTop):null;})()`);
    const h = await ev(ws, `(()=>{const t=document.querySelector('.message-center-thread-timeline'); return t?Math.round(t.getBoundingClientRect().height):null;})()`);
    console.log(`  切到「${name}」后 timeline scrollTop = ${st}, 高度 = ${h}`);
  }
  ws.close();
};
main().then(() => process.exit(0)).catch((e) => { console.error('FAIL', e.message); process.exit(1); });
