// 电话记录表单布局验收：真前端 5173 → 真后端 8107，不 stub 任何 /api。
// 关注点（都是这台机器上真实踩过的）：
//   1. label 是否被挤成竖排（宽写死 40px 装不下 4 个汉字 ⇒ 一个字一行）
//   2. 备注 textarea 是否被 flex 压扁、工具栏是否叠到它身上
//   3. 三列 grid 在各视口下的列宽是否够用、Modal 里会不会挤爆
//   4. 表单内容有没有被容器裁掉（scrollHeight > clientHeight）
//   5. 其它渠道 tab（邮件 / ChatApp）有没有被这轮改动带坏
import fs from 'node:fs';

const PORT = Number(process.env.CDP_PORT || 9431);
const BASE = process.env.BASE || 'http://127.0.0.1:5173';
const OUT = process.env.OUT || '/tmp/mc-callform';
// 守望：email + chatapp 两个身份、没有 phone 身份 —— 正好走「号码手填」那一路（和小森的截图一致）。
// 注意不能带 ?channel=phone：ThreadPage 在 isPhoneTimeline 时不渲染 composer（那个 URL 没有发送框）。
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

const MEASURE = `(() => {
  const R = (el) => { if (!el) return null; const b = el.getBoundingClientRect();
    return { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height),
             right: Math.round(b.right), bottom: Math.round(b.bottom) }; };
  const cs = (el, p) => (el ? getComputedStyle(el)[p] : null);

  const form = [...document.querySelectorAll('.send-form')].find((f) => f.offsetParent !== null);
  if (!form) return { present: false };
  const comp = document.querySelector('.message-center-thread-composer');
  const pane = form.closest('.ant-tabs-tabpane');

  const metaRows = [...form.querySelectorAll('.send-meta-row, .send-meta-inline')].map((row) => ({
    cls: row.className,
    rowBox: R(row),
    grid: cs(row, 'gridTemplateColumns'),
    items: [...row.querySelectorAll('.ant-form-item')].map((item) => {
      const label = item.querySelector('.ant-form-item-label > label');
      const lh = label ? parseFloat(cs(label, 'lineHeight')) || 0 : 0;
      const input = item.querySelector('input, .ant-select, .ant-picker, .ant-radio-group');
      return {
        labelText: label ? label.textContent.trim() : null,
        labelBox: R(item.querySelector('.ant-form-item-label')),
        labelLines: label && lh ? Math.round(label.getBoundingClientRect().height / lh) : null,
        labelScrollW: label ? Math.round(label.scrollWidth) : null,
        labelClientW: label ? Math.round(label.clientWidth) : null,
        whiteSpace: label ? cs(label, 'whiteSpace') : null,
        inputW: input ? Math.round(input.getBoundingClientRect().width) : null,
        inputBox: R(input),
      };
    }),
  }));

  const ta = form.querySelector('textarea');
  const tool = form.querySelector('.send-toolbar');
  const note = ta ? ta.closest('.ant-form-item') : null;
  const att = form.querySelector('.send-attachments');
  const attChip = form.querySelector('.send-attachment');
  const noteBox = R(note);
  const toolBox = R(tool);

  return {
    present: true,
    activeTab: (document.querySelector('.send-channel-tabs .ant-tabs-tab-active') || {}).innerText?.trim() || null,
    composerBox: R(comp),
    formBox: R(form),
    metaRows,
    noteBox,
    textareaBox: R(ta),
    textareaScrollH: ta ? ta.scrollHeight : null,
    textareaClientH: ta ? ta.clientHeight : null,
    textareaFits: ta ? ta.scrollHeight <= ta.clientHeight + 1 : null,
    toolbarBox: toolBox,
    attachmentsBox: R(att),
    attachmentChip: attChip ? attChip.innerText.trim() : null,
    attachmentChipBox: R(attChip),
    /* 备注框底 → 工具栏顶的间距（负数=叠在一起） */
    noteToToolbarGap: (noteBox && toolBox) ? Math.round(toolBox.y - noteBox.bottom) : null,
    /* 控件/文本有没有越出表单右边界 */
    formOverflowRight: [...form.querySelectorAll('.ant-form-item-control, textarea')]
      .map((el) => Math.round(el.getBoundingClientRect().right - form.getBoundingClientRect().right))
      .reduce((a, b) => Math.max(a, b), -999),
    formScrollOver: form.scrollHeight - form.clientHeight,
    composerScrollOver: comp ? comp.scrollHeight - comp.clientHeight : null,
    paneScrollOver: pane ? pane.scrollHeight - pane.clientHeight : null,
    docOver: document.documentElement.scrollWidth - window.innerWidth,
    vw: window.innerWidth, vh: window.innerHeight,
    formText: form.innerText.replace(/\\s+/g, ' ').trim().slice(0, 200),
  };
})()`;

const MODAL = `(() => {
  const R = (el) => { if (!el) return null; const b = el.getBoundingClientRect();
    return { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height), bottom: Math.round(b.bottom) }; };
  const modal = document.querySelector('.ant-modal-content');
  if (!modal) return { present: false };
  const form = modal.querySelector('form');
  if (!form) return { present: false, modalBox: R(modal) };
  const items = [...form.querySelectorAll('.ant-form-item')].map((item) => {
    const label = item.querySelector('.ant-form-item-label > label');
    const lh = label ? parseFloat(getComputedStyle(label).lineHeight) || 0 : 0;
    const hasOwnRow = !!item.closest('.send-meta-row') || item.classList.contains('send-meta-inline');
    const isRowLayout = hasOwnRow && label ? Math.abs(
      item.querySelector('.ant-form-item-label').getBoundingClientRect().y
      - item.querySelector('.ant-form-item-control').getBoundingClientRect().y) < 12 : null;
    return {
      labelText: label ? label.textContent.trim() : null,
      labelBox: R(item.querySelector('.ant-form-item-label')),
      labelLines: label && lh ? Math.round(label.getBoundingClientRect().height / lh) : null,
      controlBox: R(item.querySelector('.ant-form-item-control')),
      inputBox: R(item.querySelector('input, .ant-select, .ant-picker, .ant-radio-group')),
      labelBesideControl: isRowLayout,
    };
  });
  return {
    present: true,
    modalBox: R(modal),
    formBox: R(form),
    metaRowBox: R(form.querySelector('.send-meta-row-3')),
    grid: form.querySelector('.send-meta-row-3') ? getComputedStyle(form.querySelector('.send-meta-row-3')).gridTemplateColumns : null,
    items,
    toolbarBox: R(form.querySelector('.send-toolbar')),
    formScrollOver: form.scrollHeight - form.clientHeight,
    docOver: document.documentElement.scrollWidth - window.innerWidth,
  };
})()`;

const clickTab = (name) => `(() => {
  const t = [...document.querySelectorAll('.send-channel-tabs > .ant-tabs-nav .ant-tabs-tab')]
    .find((e) => e.innerText.trim() === ${JSON.stringify(name)});
  if (!t) return { ok: false, tabs: [...document.querySelectorAll('.send-channel-tabs > .ant-tabs-nav .ant-tabs-tab')].map((e) => e.innerText.trim()) };
  t.click(); return { ok: true };
})()`;

const clickByText = (sel, text) => `(() => {
  const el = [...document.querySelectorAll(${JSON.stringify(sel)})].find((e) => e.innerText.trim() === ${JSON.stringify(text)});
  if (!el) return false; el.click(); return true;
})()`;

// 给 current form 里的 file input 塞一个假 mp3（antd Upload 的 beforeUpload 返回 false ⇒ 不会真上传）
const PICK_MP3 = `(() => {
  const form = [...document.querySelectorAll('.send-form')].find((f) => f.offsetParent !== null);
  const input = form ? form.querySelector('input[type=file]') : null;
  if (!input) return 'no file input';
  const dt = new DataTransfer();
  dt.items.add(new File([new Blob(['fake-mp3-bytes'])], 'call-2026-09-30-1046.mp3', { type: 'audio/mpeg' }));
  input.files = dt.files;
  input.dispatchEvent(new Event('change', { bubbles: true }));
  return 'dispatched';
})()`;

const VIEWPORTS = [
  { w: 1512, h: 890, name: '1512x890' },
  { w: 1440, h: 900, name: '1440x900' },
  { w: 1280, h: 800, name: '1280x800' },
];

function report(m, title) {
  console.log(`\n=== ${title} ===`);
  if (!m.present) { console.log('  表单没渲染'); return; }
  console.log('  composer =', JSON.stringify(m.composerBox), ' form =', JSON.stringify(m.formBox), ' 激活 tab =', m.activeTab);
  for (const row of m.metaRows) {
    console.log(`  · 行 ${row.cls} box=${JSON.stringify(row.rowBox)} grid=${row.grid ?? '(非 grid)'}`);
    for (const it of row.items) {
      console.log(`     ${it.labelText}: label ${JSON.stringify(it.labelBox)} 行数=${it.labelLines} scrollW=${it.labelScrollW}/clientW=${it.labelClientW} ws=${it.whiteSpace} | 控件宽=${it.inputW}`);
    }
  }
  console.log(`  备注 ${JSON.stringify(m.noteBox)} textarea ${JSON.stringify(m.textareaBox)} fits=${m.textareaFits} (scrollH=${m.textareaScrollH}/clientH=${m.textareaClientH})`);
  console.log(`  工具栏 ${JSON.stringify(m.toolbarBox)} | 备注→工具栏间距 ${m.noteToToolbarGap}px`);
  if (m.attachmentsBox) console.log(`  附件 ${JSON.stringify(m.attachmentsBox)} chip=${JSON.stringify(m.attachmentChip)}`);
  console.log(`  右溢 ${m.formOverflowRight}px | 溢出 form ${m.formScrollOver} / composer ${m.composerScrollOver} / pane ${m.paneScrollOver} / doc ${m.docOver}`);
}

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
  let tabCount = 0;
  for (let i = 0; i < 60; i++) {
    await sleep(600);
    tabCount = await evaluate(ws, `document.querySelectorAll('.send-channel-tabs .ant-tabs-tab').length`);
    if (tabCount > 0) break;
  }
  console.log('  composer 渠道 tab 数 =', tabCount);
  await sleep(1500);

  console.log('\n1a) 其它渠道 tab 的回归（改动前后应当没有肉眼可见差异）');
  for (const name of ['邮件', 'ChatApp']) {
    const r = await evaluate(ws, clickTab(name));
    await sleep(700);
    if (!r.ok) { console.log(`  切「${name}」失败:`, JSON.stringify(r)); continue; }
    report(await evaluate(ws, MEASURE), `会话页 · ${name} tab · 1512x890`);
  }

  console.log('\n1b) 电话记录 tab · 三档视口');
  console.log('   切到「电话记录」:', JSON.stringify(await evaluate(ws, clickTab('电话记录'))));
  await sleep(900);
  for (const vp of VIEWPORTS) {
    await rpc(ws, 'Emulation.setDeviceMetricsOverride', { width: vp.w, height: vp.h, deviceScaleFactor: 2, mobile: false });
    await sleep(700);
    const m = await evaluate(ws, MEASURE);
    report(m, `会话页 · 电话记录 · ${vp.name}`);
    const cb = m.composerBox;
    if (cb) await shot(ws, `会话页-电话-${vp.name}`, { x: Math.max(0, cb.x - 8), y: Math.max(0, cb.y - 8), width: cb.w + 16, height: Math.min(cb.h + 16, vp.h - cb.y + 8) });
  }

  console.log('\n1c) 选中一个 MP3 之后（附件 chip 会再占一行，最容易把备注挤爆）');
  await rpc(ws, 'Emulation.setDeviceMetricsOverride', { width: 1512, height: 890, deviceScaleFactor: 2, mobile: false });
  await sleep(500);
  console.log('   塞文件:', await evaluate(ws, PICK_MP3));
  await sleep(900);
  const withFile = await evaluate(ws, MEASURE);
  report(withFile, '会话页 · 电话记录 · 已选 MP3 · 1512x890');
  if (withFile.composerBox) {
    const cb = withFile.composerBox;
    await shot(ws, '会话页-电话-已选MP3-1512', { x: cb.x - 8, y: cb.y - 8, width: cb.w + 16, height: cb.h + 16 });
  }

  console.log('\n2) Modal（电话仓库 → 上传录音）');
  await rpc(ws, 'Page.navigate', { url: `${BASE}/phone-repository` });
  await sleep(3000);
  console.log('   点「上传录音」:', await evaluate(ws, clickByText('button', '上传录音')));
  await sleep(1200);
  const md = await evaluate(ws, MODAL);
  console.log('   modal =', JSON.stringify(md.modalBox), ' form =', JSON.stringify(md.formBox));
  console.log('   grid =', md.grid);
  for (const it of (md.items || [])) {
    console.log(`    · ${it.labelText}: label ${JSON.stringify(it.labelBox)} 行数=${it.labelLines} | 控件 ${JSON.stringify(it.controlBox)} 输入宽=${it.inputBox ? it.inputBox.w : null} | label 在控件左侧=${it.labelBesideControl}`);
  }
  console.log('   工具栏', JSON.stringify(md.toolbarBox), ' formScrollOver', md.formScrollOver, ' docOver', md.docOver);
  if (md.modalBox) await shot(ws, '仓库Modal-1512', { x: md.modalBox.x - 12, y: md.modalBox.y - 12, width: md.modalBox.w + 24, height: md.modalBox.h + 24 });

  console.log('\n控制台 error/warning:', errors.length ? errors.slice(0, 6) : '无');
  ws.close();
};

main().catch((e) => { console.error('FAILED:', e.message); process.exit(1); });
