// 接口测试报告 HTML 的自检：数字读得出来吗？会不会横向溢出？折叠区里的行在不在 DOM 里？
// 这是给人看的交付物 —— 「渲染坏了」在屏幕上就是一片空白且不报错，所以必须量。
import fs from 'node:fs';

const PORT = Number(process.env.CDP_PORT || 9431);
const PAGE = process.env.PAGE
  || 'file:///Users/z/workItem/CRMforLogistics-message-center-presplit-runtime/docs/api-tests/REPORT.html';
const OUT = process.env.OUT || '/tmp/mc-api-report';
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
  const kpis = [...document.querySelectorAll('.kpi')].map((k) => ({
    n: k.querySelector('.n').textContent.trim(),
    l: k.querySelector('.l').textContent.trim(),
    cls: k.className.replace('kpi', '').trim(),
  }));
  const cards = [...document.querySelectorAll('.card')];
  const tables = [...document.querySelectorAll('table')].map((t) => ({
    head: t.querySelector('th') ? t.querySelector('th').textContent.trim() : '',
    rows: t.querySelectorAll('tbody tr, tr').length - 1,
  }));
  const sections = [...document.querySelectorAll('h2')].map((h) => h.textContent.trim());
  const badCells = [...document.querySelectorAll('td')].filter((t) => t.querySelector('.bad')).length;
  return {
    title: document.title,
    kpis, sections, tables, badCells,
    detailsCount: document.querySelectorAll('details').length,
    bodyH: document.body.scrollHeight,
    // 判溢出必须**同基准**：scrollWidth 与 clientWidth 都不含垂直滚动条。
    // 拿 scrollWidth 减 window.innerWidth 会恒得 -15（滚动条宽）⇒ 负数是假阳性，别当成"没溢出"。
    docOver: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    vw: window.innerWidth, vh: window.innerHeight,
  };
})()`;

const main = async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const ws = new WebSocket(await pickPageTarget());
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('ws connect failed')); });
  await rpc(ws, 'Page.enable');
  await rpc(ws, 'Runtime.enable');

  let bad = false;
  for (const [w, h] of [[1512, 900], [1280, 800]]) {
    await rpc(ws, 'Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile: false });
    await rpc(ws, 'Page.navigate', { url: PAGE });
    await sleep(1200);
    const m = await evaluate(ws, MEASURE);
    console.log(`== ${w}×${h} == 标题=${m.title}  横向溢出=${m.docOver}（scrollWidth-clientWidth）  文档高=${m.bodyH}`);
    console.log('  KPI:', m.kpis.map((k) => `${k.l}=${k.n}`).join(' | '));
    console.log('  章节:', m.sections.join(' / '));
    console.log('  表格行数:', m.tables.map((t) => `${t.head}×${t.rows}`).join(', '));
    console.log('  折叠区:', m.detailsCount, ' 含红字单元格:', m.badCells);
    if (m.docOver > 0) bad = true;   // >0 才是真溢出；<0 是垂直滚动条宽度的假阳性
    if (m.kpis.length !== 7) bad = true;
    // 只截视口：整页截图（captureBeyondViewport + dpr2）在这份长报告上会吃掉几百 MB，进程被 OOM 杀掉。
    const r = await rpc(ws, 'Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(`${OUT}/report-${w}.png`, Buffer.from(r.data, 'base64'));
    console.log('  shot', `${OUT}/report-${w}.png`);
  }

  console.log(bad ? '结果 = 有问题' : '结果 = 全部通过');
  ws.close();
  if (bad) process.exit(1);
};

main().then(() => process.exit(0)).catch((e) => { console.error('FAILED', e); process.exit(1); });
