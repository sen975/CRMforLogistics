// 发送区「排版重叠」取证：真前端 5173 + 真后端 8107，不 stub 任何 /api。
//
// 关注点（小森截图：ChatApp tab 的「发送」按钮压到了消息输入框上）：
//   1. 逐层量 rect（nav / 元数据行 / 正文 item / label / control / textarea / 工具栏）—— 谁压了谁
//   2. textarea 的**实际高度** vs 它所属 .ant-form-item 的高度 —— 溢出的那一方才可能压到别人
//   3. 两两求交（INK 元素之间），任何正面积交都算重叠
//   4. 对照渠道（邮件 / 电话记录）必须同时量：同一个 bug 很可能只落在某个渠道上
import fs from 'node:fs';

const PORT = Number(process.env.CDP_PORT || 9431);
const BASE = process.env.BASE || 'http://127.0.0.1:5173';
const OUT = process.env.OUT || '/tmp/mc-overlap';
// email + chatapp 两个身份、没有 phone —— 三个渠道 tab 都能看到
const CONTACT = process.env.CONTACT || 'a0e2802f-87ae-4e4a-8ff5-baafa21adf03';
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

// 一层 / 一层的 rect，外加「谁压了谁」的两两求交。
const MEASURE = `(() => {
  const R = (el) => { if (!el) return null; const b = el.getBoundingClientRect();
    return { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height),
             right: Math.round(b.right), bottom: Math.round(b.bottom) }; };
  const cs = (el, p) => (el ? getComputedStyle(el)[p] : null);
  const vis = (el) => !!el && el.offsetParent !== null;

  const composer = document.querySelector('.message-center-thread-composer');
  const form = [...document.querySelectorAll('.send-form')].find(vis);
  if (!form || !composer) return { present: false };
  const modeNav = [...document.querySelectorAll('.send-mode-tabs > .ant-tabs-nav')].find(vis);

  const navs = [
    ['.send-channel-tabs > .ant-tabs-nav', document.querySelector('.send-channel-tabs > .ant-tabs-nav')],
    ['.send-mode-tabs > .ant-tabs-nav', [...document.querySelectorAll('.send-mode-tabs > .ant-tabs-nav')].find(vis)],
  ].filter(([, el]) => vis(el)).map(([name, el]) => ({ name, box: R(el), marginBottom: cs(el, 'marginBottom') }));

  const grow = form.querySelector('.ant-form-item.send-grow');
  const ta = grow ? grow.querySelector('textarea') : null;
  const details = (el) => el ? { box: R(el), height: cs(el, 'height'), minHeight: cs(el, 'minHeight'),
      flex: cs(el, 'flex'), marginTop: cs(el, 'marginTop'), marginBottom: cs(el, 'marginBottom'),
      overflow: cs(el, 'overflow') } : null;

  // 直接子元素（发送区的骨架：元数据行 / 正文 item / 工具栏）
  const kids = [...form.children].map((el) => ({
    cls: el.className, box: R(el),
    marginTop: cs(el, 'marginTop'), marginBottom: cs(el, 'marginBottom'),
    scrollOver: el.scrollHeight - el.clientHeight,
  }));
  const gaps = [];
  for (let i = 1; i < kids.length; i++) {
    gaps.push({ from: kids[i - 1].cls, to: kids[i].cls, gap: Math.round(kids[i].box.y - kids[i - 1].box.bottom) });
  }

  // 「看得见的墨」：真正会画在屏幕上的那些盒子。两两求交，正面积 = 重叠。
  const ink = [];
  const push = (name, el) => { if (vis(el)) ink.push({ name, box: R(el) }); };
  // nav 容器本身是铺满整行的盒子，两个 nav 必然「相交」但内容可能差得很远 ——
  // 所以比较实际 pill 列表（.ant-tabs-nav-list），这才代表看得见 / 点得到的区域。
  push('渠道 pills', document.querySelector('.send-channel-tabs > .ant-tabs-nav .ant-tabs-nav-list'));
  push('模式 pills', modeNav ? modeNav.querySelector('.ant-tabs-nav-list') : null);
  [...form.querySelectorAll(':scope > .send-meta-row > .ant-form-item')].forEach((item, i) =>
    push('元数据 label#' + i, item.querySelector('.ant-form-item-label > label')));
  [...form.querySelectorAll(':scope > .send-meta-row > .ant-form-item')].forEach((item, i) =>
    push('元数据 控件#' + i, item.querySelector('input, .ant-select, .ant-picker')));
  push('正文 label', grow && grow.querySelector('.ant-form-item-label > label'));
  push('textarea', ta);
  push('工具栏', form.querySelector('.send-toolbar'));
  push('发送按钮', form.querySelector('.send-submit'));

  const overlaps = [];
  for (let i = 0; i < ink.length; i++) {
    for (let j = i + 1; j < ink.length; j++) {
      const a = ink[i].box, b = ink[j].box;
      const ox = Math.min(a.right, b.right) - Math.max(a.x, b.x);
      const oy = Math.min(a.bottom, b.bottom) - Math.max(a.y, b.y);
      if (ox > 0 && oy > 0) overlaps.push({ a: ink[i].name, b: ink[j].name, ox: Math.round(ox), oy: Math.round(oy), area: Math.round(ox * oy) });
    }
  }

  const pane = form.closest('.ant-tabs-tabpane');
  // 绝对定位的 nav 到底相对谁定位？沿祖先链找第一个 position !== static 的元素。
  const positionedChain = [];
  for (let n = modeNav && modeNav.parentElement; n && n !== document.body; n = n.parentElement) {
    const pos = cs(n, 'position');
    if (pos !== 'static') positionedChain.push({ tag: n.tagName.toLowerCase(), cls: String(n.className).slice(0, 60), pos, box: R(n) });
  }
  return {
    present: true,
    positionedChain,
    channel: (document.querySelector('.send-channel-tabs .ant-tabs-tab-active') || {}).innerText?.trim() || null,
    mode: ([...document.querySelectorAll('.send-mode-tabs .ant-tabs-tab-active')].find(vis) || {}).innerText?.trim() || null,
    composerBox: R(composer), composerPadding: cs(composer, 'padding'), composerOver: composer.scrollHeight - composer.clientHeight,
    navs,
    formBox: R(form), formPadding: cs(form, 'padding'), formOver: form.scrollHeight - form.clientHeight,
    paneOver: pane ? pane.scrollHeight - pane.clientHeight : null,
    kids, gaps,
    growDetail: details(grow),
    growRow: details(grow && grow.querySelector('.ant-form-item-row')),
    growControl: details(grow && grow.querySelector('.ant-form-item-control')),
    growControlInput: details(grow && grow.querySelector('.ant-form-item-control-input')),
    growContent: details(grow && grow.querySelector('.ant-form-item-control-input-content')),
    textareaDetail: details(ta),
    textareaFits: ta ? ta.scrollHeight <= ta.clientHeight + 1 : null,
    toolbarBox: R(form.querySelector('.send-toolbar')),
    /* textarea 相对它所属 Form.Item 的「越界量」，>0 才有能力压到下一行 */
    textareaSpill: (ta && grow) ? Math.round(ta.getBoundingClientRect().bottom - grow.getBoundingClientRect().bottom) : null,
    overlaps,
    docOver: document.documentElement.scrollWidth - window.innerWidth,
    vw: window.innerWidth, vh: window.innerHeight,
  };
})()`;

const clickTab = (name) => `(() => {
  const t = [...document.querySelectorAll('.send-channel-tabs > .ant-tabs-nav .ant-tabs-tab')]
    .find((e) => e.innerText.trim() === ${JSON.stringify(name)});
  if (!t) return { ok: false, tabs: [...document.querySelectorAll('.send-channel-tabs > .ant-tabs-nav .ant-tabs-tab')].map((e) => e.innerText.trim()) };
  t.click(); return { ok: true };
})()`;

function report(m, title) {
  console.log(`\n=== ${title} ===`);
  if (!m.present) { console.log('  发送区没渲染'); return; }
  console.log(`  渠道=${m.channel} 模式=${m.mode ?? '(无)'}  composer ${JSON.stringify(m.composerBox)} over=${m.composerOver}`);
  for (const n of m.navs) console.log(`  · nav ${n.name} ${JSON.stringify(n.box)} margin-bottom=${n.marginBottom}`);
  if (m.positionedChain) for (const c of (m.positionedChain || []).slice(0, 4))
    console.log(`    nav 的定位祖先: <${c.tag} class="${c.cls}"> position=${c.pos} ${JSON.stringify(c.box)}`);
  console.log(`  form ${JSON.stringify(m.formBox)} over=${m.formOver} / pane over=${m.paneOver}`);
  for (const k of m.kids) console.log(`  · ${k.cls} ${JSON.stringify(k.box)} mt=${k.marginTop} mb=${k.marginBottom} over=${k.scrollOver}`);
  for (const g of m.gaps) console.log(`     ${g.from} → ${g.to} 间距 ${g.gap}px${g.gap < 0 ? '  ⚠️ 叠了' : ''}`);
  console.log(`  grow item ${JSON.stringify(m.growDetail && m.growDetail.box)} h=${m.growDetail && m.growDetail.height} minH=${m.growDetail && m.growDetail.minHeight}`);
  const layer = (n, d) => console.log(`    ${n}: h=${d && d.height} minH=${d && d.minHeight} ${JSON.stringify(d && d.box)}`);
  layer('row', m.growRow); layer('control', m.growControl);
  layer('control-input', m.growControlInput); layer('control-content', m.growContent);
  layer('textarea', m.textareaDetail);
  console.log(`  textarea fits=${m.textareaFits} overflow=${m.textareaDetail && m.textareaDetail.overflow} | 越出 Form.Item ${m.textareaSpill}px`);
  console.log(`  工具栏 ${JSON.stringify(m.toolbarBox)}`);
  console.log(`  ⚠️ 重叠对：${m.overlaps.length ? '' : '无'}`);
  for (const o of m.overlaps) {
    // 「工具栏 × 发送按钮」是正常的父子包含关系，不是排版事故 —— 标出来免得看错。
    const contained = (o.a === '工具栏' && o.b === '发送按钮') ? '  (正常：按钮在工具栏内)' : '  ⚠️ 需要修';
    console.log(`     ${o.a} × ${o.b}  = ${o.ox}×${o.oy}px (${o.area} px²)${contained}`);
  }
  console.log(`  docOver ${m.docOver} | 视口 ${m.vw}x${m.vh}`);
}

const VIEWPORTS = [
  { w: 1512, h: 890, name: '1512x890' },
  { w: 1440, h: 900, name: '1440x900' },
  { w: 1280, h: 800, name: '1280x800' },
  // 手机档：模式 nav 与渠道 nav 并排会挤不下（两行加起来 ~450px > composer 内容宽）⇒
  // 它在窄屏必须回到文档流，这一档就是专门盯这件事的。
  { w: 390, h: 844, name: '390x844' },
];

const main = async () => {
  const ws = new WebSocket(await pickPageTarget());
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('ws connect failed')); });

  const errors = [];
  ws.addEventListener('message', (ev) => {
    const m = JSON.parse(ev.data);
    if (m.method === 'Runtime.consoleAPICalled' && ['error', 'warning'].includes(m.params.type)) {
      errors.push(m.params.args.map((a) => a.value ?? a.description ?? '').join(' ').slice(0, 200));
    }
  });

  await rpc(ws, 'Page.enable');
  await rpc(ws, 'Runtime.enable');
  await rpc(ws, 'Emulation.setDeviceMetricsOverride', { width: 1512, height: 890, deviceScaleFactor: 2, mobile: false });

  console.log('1) 登录态 → 会话页');
  await rpc(ws, 'Page.navigate', { url: `${BASE}/login` });
  await sleep(2200);
  await evaluate(ws, `localStorage.setItem('token', ${JSON.stringify(TOKEN)});
    localStorage.setItem('username', 'admin');
    localStorage.setItem('roles', JSON.stringify(['ADMIN'])); 'ok'`);
  await rpc(ws, 'Page.navigate', { url: `${BASE}/conversations/contact/${CONTACT}` });
  for (let i = 0; i < 60; i++) {
    await sleep(600);
    if (await evaluate(ws, `document.querySelectorAll('.send-channel-tabs .ant-tabs-tab').length > 0`)) break;
  }
  await sleep(1500);

  console.log('\n1a) 三个渠道逐一量（重叠是渠道特异性的 —— 只量一个渠道必漏）');
  for (const name of ['ChatApp', '邮件', '电话记录']) {
    const r = await evaluate(ws, clickTab(name));
    await sleep(900);
    if (!r.ok) { console.log(`  切「${name}」失败:`, JSON.stringify(r)); continue; }
    const m = await evaluate(ws, MEASURE);
    report(m, `会话页 · ${name} · 1512x890`);
    if (m.composerBox) {
      const cb = m.composerBox;
      await shot(ws, `改后-${name}-1512`, { x: Math.max(0, cb.x - 6), y: Math.max(0, cb.y - 6), width: cb.w + 12, height: Math.min(cb.h + 12, 890 - cb.y + 6) });
    }
  }

  console.log('\n1b) ChatApp tab · 三档视口');
  console.log('   切到 ChatApp:', JSON.stringify(await evaluate(ws, clickTab('ChatApp'))));
  await sleep(800);
  for (const vp of VIEWPORTS) {
    await rpc(ws, 'Emulation.setDeviceMetricsOverride', { width: vp.w, height: vp.h, deviceScaleFactor: 2, mobile: false });
    await sleep(800);
    const m = await evaluate(ws, MEASURE);
    report(m, `会话页 · ChatApp · ${vp.name}`);
    const cb = m.composerBox;
    if (cb) await shot(ws, `改后-ChatApp-${vp.name}`, { x: Math.max(0, cb.x - 6), y: Math.max(0, cb.y - 6), width: cb.w + 12, height: Math.min(cb.h + 12, vp.h - cb.y + 6) });
  }

  console.log('\n控制台 error/warning:', errors.length ? errors.slice(0, 6) : '无');
  ws.close();
};

main().catch((e) => { console.error('FAILED:', e.message); process.exit(1); });
