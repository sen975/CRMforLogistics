// 悬浮球验收：拖动跟手 → 松手靠边吸附 → 悬停滑出 → 换屏后位置不丢。
// 真前端 5173 → 真后端 8107，不 stub 任何 /api。
import fs from 'node:fs';

const PORT = Number(process.env.CDP_PORT || 9431);
const BASE = process.env.BASE || 'http://127.0.0.1:5173';
const OUT = process.env.OUT || '/tmp/mc-orb';
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

const ORB = `(() => {
  const R = (el) => { if (!el) return null; const b = el.getBoundingClientRect();
    return { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height),
             right: Math.round(b.right), bottom: Math.round(b.bottom) }; };
  const cs = (el, p) => el ? getComputedStyle(el)[p] : null;
  const wrap = document.querySelector('.mc-assistant-orb');
  const ball = document.querySelector('.mc-assistant-orb-ball');
  const label = document.querySelector('.mc-assistant-orb-label');
  const img = ball ? ball.querySelector('img') : null;
  const vw = window.innerWidth, vh = window.innerHeight;
  const bb = ball ? ball.getBoundingClientRect() : null;
  const lb = label ? label.getBoundingClientRect() : null;
  return {
    present: !!wrap,
    edge: wrap && wrap.getAttribute('data-edge'),
    dragging: wrap && wrap.getAttribute('data-dragging'),
    wrapBox: R(wrap), ballBox: R(ball), labelBox: R(label),
    inlineLeft: wrap ? wrap.style.left : null,
    inlineTop: wrap ? wrap.style.top : null,
    ballTransform: cs(ball, 'transform'),
    ballTransition: cs(ball, 'transition-duration'),
    labelOpacity: label ? Number(cs(label, 'opacity')) : null,
    labelText: label ? label.textContent : null,
    icon: img ? img.getAttribute('src') : null,
    iconPx: img ? img.naturalWidth + 'x' + img.naturalHeight : null,
    /* 藏到屏幕外多少：球越过左右边界的像素。贴右边时正确值是 12（= 组件里的 ORB_PEEK）。 */
    peekRight: bb ? Math.max(0, Math.round(bb.right - vw)) : null,
    peekLeft: bb ? Math.max(0, Math.round(-bb.left)) : null,
    /* 球是不是真的能被点到：拿球心做命中测试，命中的必须是球自己 */
    hitIsBall: bb ? (() => { const h = document.elementFromPoint(bb.x + bb.width / 2, bb.y + bb.height / 2);
      return h ? (h.closest('.mc-assistant-orb') ? 'orb' : h.tagName + '.' + String(h.className).slice(0, 30)) : null; })() : null,
    labelOverflow: lb ? Math.round(lb.x) : null,
    stored: window.localStorage.getItem('mc.assistant.orb.v1'),
    drawerOpen: !!document.querySelector('.mc-assistant-drawer .ant-drawer-content-wrapper[style*="translate"]'),
    vw, vh, docOver: document.documentElement.scrollWidth - vw,
  };
})()`;

const mouse = (ws, type, x, y, extra = {}) =>
  rpc(ws, 'Input.dispatchMouseEvent', { type, x, y, button: extra.button ?? 'none', buttons: extra.buttons ?? 0, clickCount: extra.clickCount ?? 0 });

async function hover(ws, x, y) {
  await mouse(ws, 'mouseMoved', x, y, { buttons: 0 });
  await sleep(420); // 滑出过渡是 240ms，多等一点再看结果
}

async function drag(ws, from, to, steps = 10) {
  await mouse(ws, 'mousePressed', from.x, from.y, { button: 'left', buttons: 1, clickCount: 1 });
  for (let i = 1; i <= steps; i++) {
    const x = Math.round(from.x + ((to.x - from.x) * i) / steps);
    const y = Math.round(from.y + ((to.y - from.y) * i) / steps);
    await mouse(ws, 'mouseMoved', x, y, { button: 'left', buttons: 1 });
    await sleep(24);
  }
  return { x: to.x, y: to.y };
}

const release = (ws, at) => mouse(ws, 'mouseReleased', at.x, at.y, { button: 'left', buttons: 0, clickCount: 1 });

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
  await evaluate(ws, `localStorage.removeItem('mc.assistant.orb.v1');
    localStorage.setItem('token', ${JSON.stringify(TOKEN)});
    localStorage.setItem('username', 'admin');
    localStorage.setItem('roles', JSON.stringify(['ADMIN'])); 'ok'`);
  await rpc(ws, 'Page.navigate', { url: `${BASE}/conversations/contact/${CONTACT}` });
  let m = null;
  for (let i = 0; i < 60; i++) {
    await sleep(400);
    m = await evaluate(ws, ORB);
    if (m.present) break;
  }
  if (!m.present) throw new Error('球没渲染：' + JSON.stringify(await evaluate(ws, `({ href: location.href, body: document.body.innerText.replace(/\\s+/g,' ').slice(0, 300) })`)));
  await sleep(1200);
  m = await evaluate(ws, ORB);

  console.log('\n=== 2) 初始态（1512×890）===');
  console.log('  edge =', m.edge, ' inline =', m.inlineLeft, '/', m.inlineTop);
  console.log('  wrap =', JSON.stringify(m.wrapBox), ' ball =', JSON.stringify(m.ballBox));
  console.log('  transform =', m.ballTransform, ' 藏进右屏外 =', m.peekRight, 'px（期望 12）');
  console.log('  命中球心 =', m.hitIsBall, ' label opacity =', m.labelOpacity, ' stored =', m.stored);
  console.log('  icon =', m.icon, m.iconPx, ' docOver =', m.docOver);
  await shot(ws, '01-初始-贴右-1512');
  await shot(ws, '02-初始-球特写', { x: m.wrapBox.x - 150, y: m.wrapBox.y - 20, width: 240, height: 100 });

  console.log('\n=== 3) 鼠标停留 → 滑出 + 出标签 ===');
  await hover(ws, m.ballBox.x + m.ballBox.w / 2, m.ballBox.y + m.ballBox.h / 2);
  let h = await evaluate(ws, ORB);
  console.log('  ball =', JSON.stringify(h.ballBox), ' 藏进右屏外 =', h.peekRight, 'px（期望 0）');
  console.log('  label opacity =', h.labelOpacity, ' label =', JSON.stringify(h.labelBox), JSON.stringify(h.labelText));
  console.log('  label 左边界 =', h.labelOverflow, '（≥0 才没被屏幕左边裁掉）');
  await shot(ws, '03-悬停-滑出-1512');
  await shot(ws, '04-悬停-球与标签特写', { x: h.labelBox.x - 10, y: h.ballBox.y - 20, width: Math.min(320, h.ballBox.right - h.labelBox.x + 20), height: 100 });

  console.log('\n=== 4) 鼠标离开 → 滑回贴边 ===');
  await hover(ws, 700, 400);
  const back = await evaluate(ws, ORB);
  console.log('  藏进右屏外 =', back.peekRight, 'px（期望 12）  label opacity =', back.labelOpacity);

  console.log('\n=== 5) 拖着球走 ===');
  const from = { x: back.ballBox.x + back.ballBox.w / 2, y: back.ballBox.y + back.ballBox.h / 2 };
  await drag(ws, from, { x: 800, y: 300 }, 6);
  const mid = await evaluate(ws, ORB);
  console.log('  拖到中途：dragging =', mid.dragging, ' wrap =', JSON.stringify(mid.wrapBox), '（应跟手到 ~770,270）');
  await shot(ws, '05-拖动中-1512');

  await drag(ws, { x: 800, y: 300 }, { x: 60, y: 520 }, 10);
  await release(ws, { x: 60, y: 520 });
  await sleep(500);
  let left = await evaluate(ws, ORB);
  console.log('  松手后：edge =', left.edge, ' inline =', left.inlineLeft, '/', left.inlineTop);
  console.log('  wrap =', JSON.stringify(left.wrapBox), ' 藏进左屏外 =', left.peekLeft, 'px（期望 12）');
  console.log('  stored =', left.stored);
  await shot(ws, '06-吸附左侧-1512');

  console.log('\n=== 6) 在左边缘再悬停一次 ===');
  await hover(ws, left.ballBox.x + left.ballBox.w / 2, left.ballBox.y + left.ballBox.h / 2);
  const leftHover = await evaluate(ws, ORB);
  console.log('  藏进左屏外 =', leftHover.peekLeft, 'px（期望 0）  label opacity =', leftHover.labelOpacity);
  console.log('  label =', JSON.stringify(leftHover.labelBox), '（应在球的右边，没被右边界裁掉）');
  await shot(ws, '07-左侧悬停-1512');

  console.log('\n=== 7) 刷新页面：位置还在不在 ===');
  await rpc(ws, 'Page.navigate', { url: `${BASE}/conversations/contact/${CONTACT}` });
  for (let i = 0; i < 60; i++) { await sleep(400); if ((await evaluate(ws, ORB)).present) break; }
  await sleep(1000);
  const reloaded = await evaluate(ws, ORB);
  console.log('  edge =', reloaded.edge, ' inline =', reloaded.inlineLeft, '/', reloaded.inlineTop, ' stored =', reloaded.stored);

  console.log('\n=== 8) 点一下还开不开面板 ===');
  const c = reloaded.ballBox;
  await mouse(ws, 'mousePressed', c.x + c.w / 2, c.y + c.h / 2, { button: 'left', buttons: 1, clickCount: 1 });
  await sleep(60);
  await release(ws, { x: c.x + c.w / 2, y: c.y + c.h / 2 });
  await sleep(1200);
  const opened = await evaluate(ws, `(() => { const w = document.querySelector('.mc-assistant-drawer .ant-drawer-content-wrapper');
    const panel = document.querySelector('[data-testid="assistant-panel"]');
    return { wrapper: w ? Math.round(w.getBoundingClientRect().width) : 0, panel: !!panel,
             stored: window.localStorage.getItem('mc.assistant.orb.v1') }; })()`);
  console.log('  抽屉宽 =', opened.wrapper, ' 面板挂载 =', opened.panel, ' stored =', opened.stored, '（点一下不该写位置）');
  await shot(ws, '08-点开后-1512');

  console.log('\n=== 9) 换屏复核 ===');
  for (const [w, hgt, mobile] of [[1280, 800, false], [1024, 640, false], [390, 844, true]]) {
    await rpc(ws, 'Emulation.setDeviceMetricsOverride', { width: w, height: hgt, deviceScaleFactor: 2, mobile });
    await sleep(900);
    const mm = await evaluate(ws, ORB);
    const inside = mm.ballBox.x >= -14 && mm.ballBox.right <= mm.vw + 14 && mm.ballBox.y >= 0 && mm.ballBox.bottom <= mm.vh;
    console.log(`  ${w}×${hgt}: edge=${mm.edge} ball=${JSON.stringify(mm.ballBox)} 在视口内=${inside} docOver=${mm.docOver}`);
    await shot(ws, `09-${w}x${hgt}-球`);
  }

  console.log('\n控制台 error/warning 条数 =', errors.length);
  errors.slice(0, 8).forEach((e) => console.log('   !', e));
  ws.close();
};

main().then(() => process.exit(0)).catch((e) => { console.error('FAILED', e); process.exit(1); });
