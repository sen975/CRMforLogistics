// 发送框改造的真渲染验收探针 v2：连真后端(5173 代理 -> 8107)，不 stub 任何 /api。
// 关注点：邮件 / ChatApp / 电话记录 三个渠道的发送区是否都已从「后台表单」变成聊天式输入框。
import fs from 'node:fs';

const PORT = Number(process.env.CDP_PORT || 9333);
const BASE = process.env.BASE || 'http://127.0.0.1:5173';
const OUT = process.env.OUT || '/tmp/mc-send';
const CONTACT = process.env.CONTACT || 'a0e2802f-87ae-4e4a-8ff5-baafa21adf03';
const CONTACT_NAME = process.env.CONTACT_NAME || '守望';
const TOKEN = fs.readFileSync('/tmp/mc-vis-token.txt', 'utf8').trim();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

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
  if (r.exceptionDetails) {
    throw new Error('probe threw: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
  }
  return r.result.value;
}

async function shot(ws, name, clip) {
  const params = { format: 'png' };
  if (clip) params.clip = { ...clip, scale: 2 };
  const r = await rpc(ws, 'Page.captureScreenshot', params);
  const p = `${OUT}/${name}.png`;
  fs.writeFileSync(p, Buffer.from(r.data, 'base64'));
  console.log(`  shot ${p} (${fs.statSync(p).size} bytes)`);
  return p;
}

const MEASURE = `(() => {
  const rect = (sel) => { const el = document.querySelector(sel); return el ? el.getBoundingClientRect() : null; };
  const r = (sel) => { const b = rect(sel); return b ? { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height), right: Math.round(b.right), bottom: Math.round(b.bottom) } : null; };
  const skin = (sel) => { const el = document.querySelector(sel); if (!el) return null; const c = getComputedStyle(el);
    return { bg: c.backgroundColor, border: c.borderTopColor + '/' + c.borderTopWidth, radius: c.borderRadius, fontSize: c.fontSize }; };

  const comp = document.querySelector('.message-center-thread-composer');
  const compRect = rect('.message-center-thread-composer');
  const ta = document.querySelector('.send-form textarea');
  const toolbar = rect('.send-toolbar');
  const submit = rect('.send-submit');
  const chips = [...document.querySelectorAll('.send-channel-tabs > .ant-tabs-nav .ant-tabs-tab')]
    .map((el) => ({ text: el.innerText.trim(), active: el.classList.contains('ant-tabs-tab-active'),
                    bg: getComputedStyle(el).backgroundColor, radius: getComputedStyle(el).borderRadius }));
  const inkBar = document.querySelector('.send-channel-tabs > .ant-tabs-nav .ant-tabs-ink-bar');
  const meta = document.querySelector('.send-meta-row');

  return {
    viewport: { w: innerWidth, h: innerHeight },
    path: location.pathname,
    composer: r('.message-center-thread-composer'),
    timeline: r('.message-center-thread-timeline'),
    composerInnerScroll: comp ? comp.scrollHeight - comp.clientHeight : -1,
    composerSkin: skin('.message-center-thread-composer'),
    input: r('.send-form textarea'),
    inputSkin: skin('.send-form textarea'),
    inputHeight: ta ? Math.round(ta.getBoundingClientRect().height) : -1,
    toolbar: toolbar ? { x: Math.round(toolbar.x), y: Math.round(toolbar.y), h: Math.round(toolbar.height), bottom: Math.round(toolbar.bottom) } : null,
    toolbarBelowInput: (toolbar && ta) ? (toolbar.top >= ta.getBoundingClientRect().bottom - 2) : false,
    submit: submit ? { x: Math.round(submit.x), right: Math.round(submit.right), h: Math.round(submit.height), bottom: Math.round(submit.bottom) } : null,
    submitFlushRight: (submit && compRect) ? Math.round(compRect.right - submit.right) : -1,
    submitVisibleWithoutScroll: submit ? (submit.bottom <= innerHeight && submit.top >= 0) : false,
    toolIconCount: document.querySelectorAll('.send-tool').length,
    submitSkin: skin('.send-submit'),
    inputChips: [...document.querySelectorAll('.send-tool-hint')].map((e) => e.innerText.trim()),
    channelChips: chips,
    activeChipSkin: skin('.send-channel-tabs > .ant-tabs-nav .ant-tabs-tab-active'),
    inkBarHidden: inkBar ? getComputedStyle(inkBar).display : 'absent',
    metaColumns: meta ? getComputedStyle(meta).gridTemplateColumns : null,
    labels: [...document.querySelectorAll('.send-form .ant-form-item-label > label')].map((el) => el.innerText.trim()),
    composerText: comp ? comp.innerText.replace(/\\s+/g, ' ').trim().slice(0, 220) : null,
    docOver: document.documentElement.scrollWidth - innerWidth,
  };
})()`;

const clickChip = (name) => `(() => {
  const t = [...document.querySelectorAll('.send-channel-tabs > .ant-tabs-nav .ant-tabs-tab')]
    .find((el) => el.innerText.trim() === ${JSON.stringify(name)});
  if (t) t.click();
  return !!t;
})()`;

const main = async () => {
  const wsUrl = await pickPageTarget();
  const ws = new WebSocket(wsUrl);
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

  console.log('1) 同源写令牌');
  await rpc(ws, 'Page.navigate', { url: `${BASE}/login` });
  await sleep(2500);
  await evaluate(ws, `localStorage.setItem('token', ${JSON.stringify(TOKEN)});
    localStorage.setItem('username', 'admin');
    localStorage.setItem('roles', JSON.stringify(['ADMIN']));
    'ok'`);

  console.log('2) 导航到列表页（先证令牌可用）');
  await rpc(ws, 'Page.navigate', { url: `${BASE}/` });
  let listed = false;
  for (let i = 0; i < 50; i++) {
    await sleep(400);
    listed = await evaluate(ws, `document.querySelectorAll('.conversation-contact, .conversation-group').length > 0`);
    if (listed) break;
  }
  console.log('   列表就绪 =', listed);
  if (!listed) {
    const diag = await evaluate(ws, `({ href: location.href, body: document.body.innerText.replace(/\\s+/g,' ').slice(0, 300), hasToken: !!localStorage.getItem('token') })`);
    console.log('   诊断:', JSON.stringify(diag));
  }

  console.log('3) 打开目标联系人的会话');
  const clicked = await evaluate(ws, `(() => {
    const rows = [...document.querySelectorAll('.conversation-contact, .conversation-group')];
    const hit = rows.find((el) => el.innerText.includes(${JSON.stringify(CONTACT_NAME)}));
    if (hit) hit.click();
    return !!hit;
  })()`);
  console.log('   点中「' + CONTACT_NAME + '」=', clicked);
  if (!clicked) await rpc(ws, 'Page.navigate', { url: `${BASE}/conversations/${CONTACT}` });

  let ready = false;
  for (let i = 0; i < 50; i++) {
    await sleep(400);
    ready = await evaluate(ws, `!!document.querySelector('.send-toolbar')`);
    if (ready) break;
  }
  console.log('   发送区就绪 =', ready);
  if (!ready) {
    const diag = await evaluate(ws, `({ href: location.href, body: document.body.innerText.replace(/\\s+/g,' ').slice(0, 400) })`);
    throw new Error('发送区未渲染：' + JSON.stringify(diag));
  }
  await sleep(1500);

  const CHANNELS = [['邮件', '01'], ['ChatApp', '02'], ['电话记录', '03']];
  const report = {};
  for (const [name, idx] of CHANNELS) {
    const ok = await evaluate(ws, clickChip(name));
    await sleep(1600);
    const m = await evaluate(ws, MEASURE);
    report[name] = {
      chipSwitched: ok,
      composer: m.composer,
      composerInnerScroll: m.composerInnerScroll,
      input: m.input,
      inputSkin: m.inputSkin,
      inputHeight: m.inputHeight,
      toolbar: m.toolbar,
      toolbarBelowInput: m.toolbarBelowInput,
      submit: m.submit,
      submitFlushRight: m.submitFlushRight,
      submitVisibleWithoutScroll: m.submitVisibleWithoutScroll,
      toolIconCount: m.toolIconCount,
      metaColumns: m.metaColumns,
      labels: m.labels,
      channelChips: m.channelChips,
      inkBarHidden: m.inkBarHidden,
      composerText: m.composerText,
      docOver: m.docOver,
    };
    console.log(`4.${idx}) [${name}]`, JSON.stringify(report[name], null, 1));
    await shot(ws, `${idx}-发送框-${name === '电话记录' ? 'call' : name === '邮件' ? 'email' : 'chatapp'}-1512`, {
      x: m.composer.x - 8, y: m.composer.y - 8, width: m.composer.w + 16, height: m.composer.h + 16,
    });
  }

  console.log('5) 整页截图（当前停在电话记录）');
  await shot(ws, '04-会话页-整页-1512');

  console.log('6) 1280 档复核发送框');
  await rpc(ws, 'Emulation.setDeviceMetricsOverride', { width: 1280, height: 800, deviceScaleFactor: 2, mobile: false });
  await sleep(1400);
  await evaluate(ws, clickChip('邮件'));
  await sleep(1500);
  const small = await evaluate(ws, MEASURE);
  console.log('   1280:', JSON.stringify({
    composer: small.composer, inputHeight: small.inputHeight,
    toolbarBelowInput: small.toolbarBelowInput, submitFlushRight: small.submitFlushRight,
    submitVisibleWithoutScroll: small.submitVisibleWithoutScroll,
    composerInnerScroll: small.composerInnerScroll, docOver: small.docOver,
  }));
  await shot(ws, '05-发送框-邮件-1280', {
    x: small.composer.x - 8, y: small.composer.y - 8, width: small.composer.w + 16, height: small.composer.h + 16,
  });

  const uniq = [...new Set(errors)];
  if (uniq.length) console.log('控制台告警/错误:', JSON.stringify(uniq.slice(0, 8), null, 1));
  else console.log('控制台无 error/warning');

  ws.close();
};

main().then(() => { console.log('DONE'); process.exit(0); }).catch((e) => { console.error('FAIL', e.message); process.exit(1); });
