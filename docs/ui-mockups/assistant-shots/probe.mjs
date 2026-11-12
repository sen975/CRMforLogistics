// AI 助手抽屉验收：统一皮肤 + 「展开不再把背景变暗」。
// 真前端 5173 → 真后端 8107，不 stub 任何 /api。
import fs from 'node:fs';

const PORT = Number(process.env.CDP_PORT || 9430);
const BASE = process.env.BASE || 'http://127.0.0.1:5173';
const OUT = process.env.OUT || '/tmp/mc-as';
const CONTACT = process.env.CONTACT || 'd526bde8-6521-4a9d-8cc4-8ae28629a780';
const TOKEN = fs.readFileSync('/tmp/mc-vis-token.txt', 'utf8').trim();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
fs.mkdirSync(OUT, { recursive: true });

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
  if (r.exceptionDetails) throw new Error('probe threw: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
  return r.result.value;
}

async function shot(ws, name, clip) {
  const params = { format: 'png' };
  if (clip) params.clip = { ...clip, scale: 2 };
  const r = await rpc(ws, 'Page.captureScreenshot', params);
  const p = `${OUT}/${name}.png`;
  fs.writeFileSync(p, Buffer.from(r.data, 'base64'));
  console.log(`  shot ${p} (${fs.statSync(p).size} bytes)`);
}

const MEASURE = `(() => {
  const R = (el) => { if (!el) return null; const b = el.getBoundingClientRect();
    return { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height), right: Math.round(b.right), bottom: Math.round(b.bottom) }; };
  const cs = (el, p) => el ? getComputedStyle(el)[p] : null;
  const dp = (el) => { if (!el) return null; const b = el.getBoundingClientRect();
    return { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height) }; };
  const root = document.querySelector('.mc-assistant-drawer');
  const wrap = document.querySelector('.mc-assistant-drawer .ant-drawer-content-wrapper');
  const content = document.querySelector('.mc-assistant-drawer .ant-drawer-content');
  const header = document.querySelector('.mc-assistant-drawer .ant-drawer-header');
  const top = document.querySelector('.as-top');
  const stream = document.querySelector('.as-stream');
  const composer = document.querySelector('.as-composer');
  const input = document.querySelector('.as-input');
  const tool = document.querySelector('.as-tool');
  const submit = document.querySelector('.as-submit');
  const chip = document.querySelector('.as-example');
  const bubbles = [...document.querySelectorAll('.as-bubble')].map((b) => ({
    cls: b.className, bg: cs(b, 'backgroundColor'), color: cs(b, 'color'),
    radius: cs(b, 'borderRadius'), border: cs(b, 'borderColor'), box: dp(b),
  }));
  const masks = [...document.querySelectorAll('.ant-drawer-mask')].map((m) => ({ bg: cs(m, 'backgroundColor'), vis: cs(m, 'visibility') }));
  const wv = { w: window.innerWidth, h: window.innerHeight };
  const hit = document.elementFromPoint(200, 400);
  const drawer = R(content);
  return {
    viewport: wv,
    present: !!root,
    maskCount: masks.length,
    masks,
    /* 背景有没有被盖住：面板打开时，视口左侧那一点命中的是谁。 */
    hitLeft: hit ? (hit.closest('.mc-assistant-drawer') ? 'drawer' : (hit.tagName + '.' + (hit.className || '').toString().slice(0, 40))) : null,
    drawer,
    /* 抽屉是不是一张四周留缝的浮卡 */
    gaps: drawer ? { top: drawer.y, right: wv.w - drawer.right, bottom: wv.h - drawer.bottom } : null,
    card: content ? {
      radius: cs(content, 'borderRadius'), border: cs(content, 'borderColor'),
      shadow: cs(content, 'boxShadow'), bg: cs(content, 'backgroundColor'),
      wrapperPadding: cs(wrap, 'padding'),
    } : null,
    header: header ? { bg: cs(header, 'backgroundColor'), borderBottom: cs(header, 'borderBottomColor'), title: (header.querySelector('.ant-drawer-title') || {}).textContent } : null,
    topBar: top ? { bg: cs(top, 'backgroundColor'), borderBottom: cs(top, 'borderBottomColor') } : null,
    select: (() => { const s = document.querySelector('.as-session-select .ant-select-selector');
      return s ? { bg: cs(s, 'backgroundColor'), radius: cs(s, 'borderRadius'), border: cs(s, 'borderColor') } : null; })(),
    newBtn: (() => { const b = document.querySelector('.as-ghost-btn'); return b ? { radius: cs(b, 'borderRadius'), bg: cs(b, 'backgroundColor'), h: Math.round(b.getBoundingClientRect().height) } : null; })(),
    stream: stream ? { bg: cs(stream, 'backgroundColor'), padding: cs(stream, 'padding'), h: Math.round(stream.getBoundingClientRect().height) } : null,
    chip: chip ? { text: chip.textContent, radius: cs(chip, 'borderRadius'), bg: cs(chip, 'backgroundColor'), border: cs(chip, 'borderColor'), h: Math.round(chip.getBoundingClientRect().height), count: document.querySelectorAll('.as-example').length } : null,
    composer: composer ? { bg: cs(composer, 'backgroundColor'), borderTop: cs(composer, 'borderTopColor'), padding: cs(composer, 'padding') } : null,
    input: input ? { bg: cs(input, 'backgroundColor'), radius: cs(input, 'borderRadius'), border: cs(input, 'borderColor'), value: input.value, placeholder: input.placeholder } : null,
    tool: tool ? { bg: cs(tool, 'backgroundColor'), radius: cs(tool, 'borderRadius'), h: Math.round(tool.getBoundingClientRect().height) } : null,
    submit: submit ? { bg: cs(submit, 'backgroundColor'), radius: cs(submit, 'borderRadius'), h: Math.round(submit.getBoundingClientRect().height), disabled: submit.disabled } : null,
    bubbles,
    docOver: document.documentElement.scrollWidth - window.innerWidth,
    bodyOverflow: cs(document.body, 'overflow'),
    scrollLocked: document.body.style.overflow || '(none)',
  };
})()`;

const openAssistant = `(() => { const b = document.querySelector('[aria-label="AI 助手"]'); if (!b) return 'no-button'; b.click(); return 'clicked'; })()`;
const clickChip = `(() => { const c = document.querySelector('.as-example'); if (!c) return 'no-chip'; c.click(); return 'clicked'; })()`;
const clearDraft = `(() => { const t = document.querySelector('.as-input'); if (!t) return 'no-input';
  const set = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
  set.call(t, ''); t.dispatchEvent(new Event('input', { bubbles: true })); return 'cleared'; })()`;
const typeDraft = (text) => `(() => { const t = document.querySelector('.as-input'); if (!t) return 'no-input';
  const set = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
  set.call(t, ${JSON.stringify(text)}); t.dispatchEvent(new Event('input', { bubbles: true })); return 'typed'; })()`;
const pressSend = `(() => { const b = document.querySelector('.as-submit'); if (!b) return 'no-button';
  if (b.disabled) return 'disabled'; b.click(); return 'clicked'; })()`;

const main = async () => {
  const wsUrl = await pickPageTarget();
  const ws = new WebSocket(wsUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('ws connect failed')); });

  const errors = [];
  ws.addEventListener('message', (ev) => {
    const m = JSON.parse(ev.data);
    if (m.method === 'Runtime.consoleAPICalled' && ['error', 'warning'].includes(m.params.type)) {
      errors.push(m.params.args.map((a) => a.value ?? a.description ?? '').join(' ').slice(0, 160));
    }
  });

  await rpc(ws, 'Page.enable');
  await rpc(ws, 'Runtime.enable');
  await rpc(ws, 'Emulation.setDeviceMetricsOverride', { width: 1512, height: 890, deviceScaleFactor: 2, mobile: false });

  console.log('1) 同源写令牌 → 会话页');
  await rpc(ws, 'Page.navigate', { url: `${BASE}/login` });
  await sleep(2200);
  await evaluate(ws, `localStorage.setItem('token', ${JSON.stringify(TOKEN)});
    localStorage.setItem('username', 'admin');
    localStorage.setItem('roles', JSON.stringify(['ADMIN'])); 'ok'`);
  await rpc(ws, 'Page.navigate', { url: `${BASE}/conversations/contact/${CONTACT}` });
  let ready = false;
  for (let i = 0; i < 60; i++) {
    await sleep(400);
    ready = await evaluate(ws, `(() => { const t = document.querySelector('.message-center-thread-timeline'); return !!t && !!document.querySelector('[aria-label="AI 助手"]'); })()`);
    if (ready) break;
  }
  console.log('   会话页就绪 =', ready);
  if (!ready) throw new Error('会话页未渲染：' + JSON.stringify(await evaluate(ws, `({ href: location.href, body: document.body.innerText.replace(/\\s+/g,' ').slice(0, 300) })`)));
  await sleep(2000);

  console.log('\n2) 打开前的基线（用于「背景是否被压暗」的像素对比）');
  const before = await evaluate(ws, MEASURE);
  console.log('   mask 元素数 =', before.maskCount, ' body.overflow =', before.bodyOverflow);
  await shot(ws, '01-打开前-整页-1512');

  console.log('\n3) 点右下角助手球');
  const opened = await evaluate(ws, openAssistant);
  console.log('   ', opened);
  let m = null;
  for (let i = 0; i < 40; i++) {
    await sleep(300);
    m = await evaluate(ws, MEASURE);
    if (m.present && m.drawer && m.drawer.w > 0) break;
  }
  await sleep(1200);
  m = await evaluate(ws, MEASURE);

  console.log('\n=== 1512 / 助手面板 ===');
  console.log('  present =', m.present, ' mask 元素数 =', m.maskCount, JSON.stringify(m.masks));
  console.log('  左侧命中 =', m.hitLeft, '（不是 drawer ⇒ 页面没被遮罩挡住）');
  console.log('  drawer =', JSON.stringify(m.drawer), ' 缝 =', JSON.stringify(m.gaps));
  console.log('  card =', JSON.stringify(m.card));
  console.log('  header =', JSON.stringify(m.header));
  console.log('  topBar =', JSON.stringify(m.topBar));
  console.log('  select =', JSON.stringify(m.select), ' 新会话 =', JSON.stringify(m.newBtn));
  console.log('  stream =', JSON.stringify(m.stream));
  console.log('  chip =', JSON.stringify(m.chip));
  console.log('  composer =', JSON.stringify(m.composer));
  console.log('  input =', JSON.stringify(m.input));
  console.log('  tool =', JSON.stringify(m.tool), ' submit =', JSON.stringify(m.submit));
  console.log('  body.overflow =', m.bodyOverflow, ' scrollLocked =', m.scrollLocked, ' docOver =', m.docOver);
  await shot(ws, '02-打开后-整页-1512');
  if (m.drawer) await shot(ws, '03-面板-特写-1512', { x: m.drawer.x - 8, y: m.drawer.y - 8, width: m.drawer.w + 12, height: Math.min(m.drawer.h + 12, m.viewport.h - m.drawer.y) });
  if (m.topBar && m.chip) {
    const y = m.drawer.y;
    await shot(ws, '04-会话条与空态-1512', { x: m.drawer.x, y, width: m.drawer.w, height: m.chip.box ? (m.chip.box.y + m.chip.box.h - y + 24) : 300 });
  }

  console.log('\n4) 点示例 chip → 是否填进输入框');
  const chipClick = await evaluate(ws, clickChip);
  await sleep(500);
  const afterChip = await evaluate(ws, MEASURE);
  console.log('   click =', chipClick, ' input.value =', JSON.stringify(afterChip.input && afterChip.input.value));
  await shot(ws, '05-示例填入-1512', m.drawer ? { x: m.drawer.x, y: m.drawer.bottom - 260, width: m.drawer.w, height: 250 } : null);

  console.log('\n5) 发一句话 → 看气泡（真后端；未配模型时会走错误气泡，同样是有效样本）');
  await evaluate(ws, clearDraft);
  await evaluate(ws, typeDraft('你好，帮我看一下今天的待办'));
  await sleep(300);
  const sent = await evaluate(ws, pressSend);
  console.log('   send =', sent);
  for (let i = 0; i < 30; i++) {
    await sleep(1000);
    const mm = await evaluate(ws, MEASURE);
    if (mm.bubbles.length >= 2) { m = mm; break; }
    m = mm;
  }
  console.log('   bubbles =', JSON.stringify(m.bubbles, null, 0));
  await shot(ws, '06-对话气泡-1512', m.drawer ? { x: m.drawer.x, y: m.drawer.y, width: m.drawer.w, height: m.drawer.h } : null);
  await shot(ws, '07-打开后-整页-带气泡-1512');

  console.log('\n6) 1280 与 390 复核');
  await rpc(ws, 'Emulation.setDeviceMetricsOverride', { width: 1280, height: 800, deviceScaleFactor: 2, mobile: false });
  await sleep(1200);
  let m2 = await evaluate(ws, MEASURE);
  console.log('   1280: drawer =', JSON.stringify(m2.drawer), ' 缝 =', JSON.stringify(m2.gaps), ' docOver =', m2.docOver, ' mask =', m2.maskCount);
  await shot(ws, '08-面板-1280');

  await rpc(ws, 'Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });
  await sleep(1200);
  m2 = await evaluate(ws, MEASURE);
  console.log('   390 : drawer =', JSON.stringify(m2.drawer), ' 缝 =', JSON.stringify(m2.gaps), ' card =', JSON.stringify(m2.card && m2.card.radius), ' docOver =', m2.docOver);
  await shot(ws, '09-面板-390');

  console.log('\n控制台 error/warning 条数 =', errors.length);
  errors.slice(0, 8).forEach((e) => console.log('   !', e));
  ws.close();
};

main().then(() => process.exit(0)).catch((e) => { console.error('FAILED', e); process.exit(1); });
