// 图标候选页自检：5 张候选有没有真加载出来、有没有横向溢出、选中交互通不通。
// 这是给人挑图的页面 —— 「图没显示」在屏幕上是一片空白，不报错，所以必须量。
import fs from 'node:fs';

const PORT = Number(process.env.CDP_PORT || 9431);
const PAGE = process.env.PAGE || `file:///Users/z/workItem/CRMforLogistics-message-center-presplit-runtime/docs/ui-mockups/assistant-icon-candidates/picker.html`;
const OUT = process.env.OUT || '/tmp/mc-orb';
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

const MEASURE = `(() => {
  const imgs = [...document.querySelectorAll('img')].map((i) => ({
    src: i.getAttribute('src'), ok: i.complete && i.naturalWidth > 0, px: i.naturalWidth + 'x' + i.naturalHeight,
  }));
  const cards = [...document.querySelectorAll('.card')].map((c) => ({
    id: c.querySelector('.badge').textContent,
    title: c.querySelector('h2').textContent,
    big: (() => { const i = c.querySelector('.big'); return i.complete && i.naturalWidth > 0; })(),
    real: (() => { const i = c.querySelector('.real'); const b = i.getBoundingClientRect();
      return { ok: i.complete && i.naturalWidth > 0, w: Math.round(b.width), h: Math.round(b.height) }; })(),
  }));
  return {
    title: document.title,
    imgCount: imgs.length,
    broken: imgs.filter((i) => !i.ok).map((i) => i.src),
    cards,
    picked: document.getElementById('picked').textContent,
    hint: document.getElementById('hint').textContent,
    docOver: document.documentElement.scrollWidth - window.innerWidth,
    vw: window.innerWidth, vh: window.innerHeight,
  };
})()`;

const main = async () => {
  const ws = new WebSocket(await pickPageTarget());
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('ws connect failed')); });
  await rpc(ws, 'Page.enable');
  await rpc(ws, 'Runtime.enable');
  await rpc(ws, 'Emulation.setDeviceMetricsOverride', { width: 1512, height: 900, deviceScaleFactor: 2, mobile: false });
  await rpc(ws, 'Page.navigate', { url: PAGE });
  await sleep(2500);

  const m = await evaluate(ws, MEASURE);
  console.log('标题 =', m.title, ' 视口 =', m.vw + '×' + m.vh, ' 横向溢出 =', m.docOver);
  console.log('图片总数 =', m.imgCount, ' 加载失败 =', JSON.stringify(m.broken));
  m.cards.forEach((c) => console.log(`  ${c.id} | 大图=${c.big} 真实尺寸=${c.real.ok}(${c.real.w}×${c.real.h}) | ${c.title}`));

  const r = await rpc(ws, 'Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(`${OUT}/picker-1512.png`, Buffer.from(r.data, 'base64'));
  console.log('  shot', `${OUT}/picker-1512.png`);

  await evaluate(ws, `document.querySelector('input[value="C"]').click(); 'ok'`);
  await sleep(300);
  const after = await evaluate(ws, MEASURE);
  console.log('选中后：', after.picked, '|', after.hint);
  const r2 = await rpc(ws, 'Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(`${OUT}/picker-选中C.png`, Buffer.from(r2.data, 'base64'));

  const bad = m.broken.length > 0 || m.cards.some((c) => !c.big || !c.real.ok) || m.docOver !== 0;
  console.log(bad ? '结果 = 有问题' : '结果 = 全部通过');
  ws.close();
  if (bad) process.exit(1);
};

main().then(() => process.exit(0)).catch((e) => { console.error('FAILED', e); process.exit(1); });
