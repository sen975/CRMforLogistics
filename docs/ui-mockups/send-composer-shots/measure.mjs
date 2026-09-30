// 量发送区在各渠道/子模式下的高度构成，为「统一高度」提供数字依据。
import fs from 'node:fs';

const PORT = 9333;
const BASE = 'http://127.0.0.1:5173';
const CONTACT = process.env.CONTACT || 'a0e2802f-87ae-4e4a-8ff5-baafa21adf03';
const CONTACT_NAME = process.env.CONTACT_NAME || '守望';
const TOKEN = fs.readFileSync('/tmp/mc-vis-token.txt', 'utf8').trim();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let seq = 0;
function rpc(ws, method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++seq;
    const on = (ev) => {
      const m = JSON.parse(ev.data);
      if (m.id !== id) return;
      ws.removeEventListener('message', on);
      if (m.error) reject(new Error(method + ' -> ' + JSON.stringify(m.error))); else resolve(m.result);
    };
    ws.addEventListener('message', on);
    ws.send(JSON.stringify({ id, method, params }));
  });
}
const evaluate = async (ws, e) => {
  const r = await rpc(ws, 'Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error('threw: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
  return r.result.value;
};

const MEASURE = `(() => {
  const h = (el) => el ? Math.round(el.getBoundingClientRect().height) : 0;
  const visible = (root, sel) => [...root.querySelectorAll(sel)].find((el) => el.offsetParent !== null) || null;
  const comp = document.querySelector('.message-center-thread-composer');
  const pane = visible(document, '.send-form');
  const form = pane ? pane.closest('form') : null;
  const scroller = form || document;
  const ta = scroller.querySelector('textarea');
  const modeTabs = document.querySelector('.send-mode-tabs');
  const modeNav = modeTabs ? visible(modeTabs, '.ant-tabs-nav') : null;
  return {
    composerH: h(comp),
    composerScroll: comp ? comp.scrollHeight : 0,
    nav: h(document.querySelector('.send-channel-tabs > .ant-tabs-nav')),
    modeNav: h(modeNav),
    metaRow: h(scroller.querySelector('.send-meta-row')),
    input: h(ta),
    attachments: h(scroller.querySelector('.send-attachments')),
    toolbar: h(scroller.querySelector('.send-toolbar')),
    text: comp ? comp.innerText.replace(/\\s+/g, ' ').trim().slice(0, 90) : null,
  };
})()`;

const clickChip = (name) => `(() => {
  const t = [...document.querySelectorAll('.send-channel-tabs > .ant-tabs-nav .ant-tabs-tab')].find((el) => el.innerText.trim() === ${JSON.stringify(name)});
  if (t) t.click(); return !!t; })()`;
const clickMode = (name) => `(() => {
  const t = [...document.querySelectorAll('.send-mode-tabs > .ant-tabs-nav .ant-tabs-tab')].find((el) => el.innerText.trim() === ${JSON.stringify(name)});
  if (t) t.click(); return !!t; })()`;

const main = async () => {
  const list = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json();
  const wsUrl = list.find((t) => t.type === 'page').webSocketDebuggerUrl;
  const ws = new WebSocket(wsUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('ws fail')); });
  await rpc(ws, 'Page.enable');
  await rpc(ws, 'Runtime.enable');
  await rpc(ws, 'Emulation.setDeviceMetricsOverride', { width: 1512, height: 890, deviceScaleFactor: 1, mobile: false });

  await rpc(ws, 'Page.navigate', { url: `${BASE}/login` });
  await sleep(2500);
  await evaluate(ws, `localStorage.setItem('token', ${JSON.stringify(TOKEN)}); localStorage.setItem('username','admin'); localStorage.setItem('roles','["ADMIN"]'); 'ok'`);
  await rpc(ws, 'Page.navigate', { url: `${BASE}/` });
  for (let i = 0; i < 50; i++) { await sleep(400); if (await evaluate(ws, `!!document.querySelector('.conversation-contact')`)) break; }
  await evaluate(ws, `(() => { const r=[...document.querySelectorAll('.conversation-contact,.conversation-group')].find(e=>e.innerText.includes(${JSON.stringify(CONTACT_NAME)})); if(r) r.click(); return !!r; })()`);
  for (let i = 0; i < 50; i++) { await sleep(400); if (await evaluate(ws, `!!document.querySelector('.send-toolbar')`)) break; }
  await sleep(1500);

  const rows = [];
  const cases = [['邮件', null], ['ChatApp', '文本'], ['ChatApp', '模板'], ['ChatApp', '图片'], ['ChatApp', '视频'], ['ChatApp', '文件'], ['电话记录', null]];
  for (const [channel, mode] of cases) {
    await evaluate(ws, clickChip(channel));
    await sleep(900);
    if (mode) { await evaluate(ws, clickMode(mode)); await sleep(900); }
    const m = await evaluate(ws, MEASURE);
    rows.push({ '渠道': channel, '子模式': mode ?? '-', 'composer高': m.composerH, 'chip行': m.nav, '模式行': m.modeNav, '元数据行': m.metaRow, '输入区': m.input, '附件区': m.attachments, '工具栏': m.toolbar });
  }
  console.table(rows);
  console.log('明细:', JSON.stringify(await evaluate(ws, MEASURE)));
  ws.close();
};
main().then(() => process.exit(0)).catch((e) => { console.error('FAIL', e.message); process.exit(1); });
