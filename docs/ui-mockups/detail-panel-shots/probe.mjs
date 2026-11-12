// 右栏详情面板「同区域其他 tab」真渲染验收：真前端 5173 → 真后端 8107，不 stub 任何 /api。
// 覆盖：① 联系人详情 · Topic 时间轴 tab（卡片 / callout / 来源 chip / 待确定）
//       ② 企微群详情面板（容器皮肤 + Topic tab + 群成员 tab；本库无群数据 ⇒ 只验结构与空态）
import fs from 'node:fs';

const PORT = Number(process.env.CDP_PORT || 9334);
const BASE = process.env.BASE || 'http://127.0.0.1:5173';
const OUT = process.env.OUT || '/tmp/mc-cd2';
const CONTACT = process.env.CONTACT || 'd526bde8-6521-4a9d-8cc4-8ae28629a780';
const GROUP = process.env.GROUP || '3c85cb6f-1d21-4549-bddc-c89e4f02d610';
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

const clickTab = (name) => `(() => {
  const tabs = [...document.querySelectorAll('.mc-detail-panel .ant-tabs-tab')];
  const hit = tabs.find((el) => el.innerText.trim() === ${JSON.stringify(name)});
  if (!hit) return false;
  (hit.querySelector('.ant-tabs-tab-btn') || hit).click();
  return true;
})()`;

// 面板通用指标 + Topic 专属指标
const MEASURE = `(() => {
  const vis = (el) => !!el && el.offsetParent !== null;
  const rect = (el) => { if (!el) return null; const b = el.getBoundingClientRect();
    return { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height), right: Math.round(b.right), bottom: Math.round(b.bottom) }; };
  const cs = (el, prop) => el ? getComputedStyle(el)[prop] : null;
  const panel = document.querySelector('.mc-detail-panel');
  const q = (sel) => [...document.querySelectorAll('.mc-detail-panel ' + sel)].filter(vis);

  const topics = q('.cd-topics > .ant-collapse-item').map((item) => {
    const header = item.querySelector('.ant-collapse-header');
    return {
      label: (header?.innerText || '').replace(/\\s+/g, ' ').trim().slice(0, 70),
      headerPad: cs(header, 'padding'),
      headerRadius: cs(header, 'borderRadius'),
      titleColor: cs(item.querySelector('.cd-topic-title'), 'color'),
      titleWeight: cs(item.querySelector('.cd-topic-title'), 'fontWeight'),
      time: item.querySelector('.cd-topic-time')?.innerText.trim(),
      timeColor: cs(item.querySelector('.cd-topic-time'), 'color'),
      hasCheckbox: !!item.querySelector('.ant-checkbox'),
      borderTop: cs(item, 'borderTopWidth') + ' ' + cs(item, 'borderTopColor'),
      bg: cs(item, 'backgroundColor'),
    };
  });

  const callouts = q('.cd-callout').map((el) => ({
    text: el.innerText.replace(/\\s+/g, ' ').trim().slice(0, 70),
    borderLeft: cs(el, 'borderLeftWidth') + ' ' + cs(el, 'borderLeftColor'),
    bg: cs(el, 'backgroundColor'),
    radius: cs(el, 'borderRadius'),
  }));

  const srcs = q('.cd-src.ant-btn').map((el) => ({
    text: el.innerText.trim(),
    bg: cs(el, 'backgroundColor'), color: cs(el, 'color'), radius: cs(el, 'borderRadius'),
    h: Math.round(el.getBoundingClientRect().height),
  }));

  const toolbar = document.querySelector('.mc-detail-panel .cd-toolbar');
  const collapseTitle = document.querySelector('.mc-detail-panel .cd-collapse .cd-group-title');
  const pendingTitle = q('.cd-group-title').map((el) => el.innerText.replace(/\\s+/g, ' ').trim());
  const warnBadge = document.querySelector('.mc-detail-panel .cd-cnt.is-warn');

  const participants = q('.cd-participants .ant-list-item').map((el) => ({
    text: el.innerText.replace(/\\s+/g, ' ').trim(),
    pad: cs(el, 'padding'), radius: cs(el, 'borderRadius'), bg: cs(el, 'backgroundColor'),
    nameColor: cs(el.querySelector('.cd-participant-name'), 'color'),
    roleBg: cs(el.querySelector('.cd-participant-role'), 'backgroundColor'),
  }));

  return {
    path: location.pathname,
    panel: rect(panel),
    tabs: [...document.querySelectorAll('.mc-detail-panel .ant-tabs-tab')].filter(vis).map((t) => t.innerText.trim()),
    toolbar: toolbar ? { h: Math.round(toolbar.getBoundingClientRect().height), display: cs(toolbar, 'display') } : null,
    collapseTitle: collapseTitle ? { text: collapseTitle.innerText.trim(), fontSize: cs(collapseTitle, 'fontSize'), weight: cs(collapseTitle, 'fontWeight'), color: cs(collapseTitle, 'color') } : null,
    groupTitles: pendingTitle,
    warnBadge: warnBadge ? { text: warnBadge.innerText.trim(), bg: cs(warnBadge, 'backgroundColor'), color: cs(warnBadge, 'color') } : null,
    topicCount: topics.length,
    topics: topics.slice(0, 3),
    callouts,
    srcs: srcs.slice(0, 4),
    srcCount: srcs.length,
    participantCount: participants.length,
    participants,
    manualReview: !!document.querySelector('.mc-detail-panel [data-testid="topic-manual-review"]'),
    reviewCardOpen: !!document.querySelector('.mc-detail-panel .cd-card'),
    cdBody: !!document.querySelector('.mc-detail-panel .cd-body'),
    reviewTitle: document.querySelector('.mc-detail-panel [data-testid="topic-manual-review"]')?.innerText.replace(/\\s+/g, ' ').trim().slice(0, 40) ?? null,
    // 硬证据：面板内不该再出现 antd 表格/描述列表
    antDescriptions: document.querySelectorAll('.mc-detail-panel .ant-descriptions').length,
    tablesInPanel: document.querySelectorAll('.mc-detail-panel table').length,
    docOver: document.documentElement.scrollWidth - window.innerWidth,
    panelScroll: panel ? panel.scrollHeight - panel.clientHeight : -1,
  };
})()`;

const expandFirstTopic = `(() => {
  const header = [...document.querySelectorAll('.mc-detail-panel .cd-topics > .ant-collapse-item .ant-collapse-header')]
    .find((el) => el.offsetParent !== null);
  if (!header) return false;
  header.click();
  return true;
})()`;

// 展开已打开的 Topic 内部的「来源（N）」子折叠 —— 不展开则 .cd-src chip 不进 DOM。
const expandSources = `(() => {
  const item = [...document.querySelectorAll('.mc-detail-panel .cd-topics > .ant-collapse-item')]
    .find((el) => el.classList.contains('ant-collapse-item-active'));
  if (!item) return false;
  const src = [...item.querySelectorAll('.ant-collapse-header')].find((el) => el.innerText.trim().startsWith('来源'));
  if (!src) return false;
  src.click();
  return true;
})()`;

const openManualReview = `(() => {
  const btn = [...document.querySelectorAll('.mc-detail-panel [data-testid="topic-manual-review"] button')]
    .find((el) => el.innerText.trim() === '手动整理 Topic');
  if (!btn) return false;
  btn.click();
  return true;
})()`;

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

  console.log('1) 同源写令牌');
  await rpc(ws, 'Page.navigate', { url: `${BASE}/login` });
  await sleep(2200);
  await evaluate(ws, `localStorage.setItem('token', ${JSON.stringify(TOKEN)});
    localStorage.setItem('username', 'admin');
    localStorage.setItem('roles', JSON.stringify(['ADMIN'])); 'ok'`);

  console.log('2) 联系人详情 · Topic 时间轴');
  await rpc(ws, 'Page.navigate', { url: `${BASE}/conversations/contact/${CONTACT}` });
  let opened = false;
  for (let i = 0; i < 40; i++) {
    await sleep(400);
    opened = await evaluate(ws, `(() => { const b = document.querySelector('[aria-label="展开右侧栏"]'); if (!b) return false; b.click(); return true; })()`);
    if (opened) break;
  }
  let ready = false;
  for (let i = 0; i < 60; i++) {
    await sleep(400);
    ready = await evaluate(ws, `(() => { const p = document.querySelector('.mc-detail-panel'); return !!p && p.getBoundingClientRect().width > 300; })()`);
    if (ready) break;
  }
  console.log('   展开右侧栏 =', opened, ' 面板就绪 =', ready);
  if (!ready) throw new Error('详情面板未渲染：' + JSON.stringify(await evaluate(ws, `({ href: location.href, body: document.body.innerText.replace(/\\s+/g,' ').slice(0, 300) })`)));
  await sleep(2500);

  let m = await evaluate(ws, MEASURE);
  console.log('\\n=== 联系人详情 / Topic 时间轴（折叠态）===');
  console.log('  tabs:', JSON.stringify(m.tabs));
  console.log('  toolbar:', JSON.stringify(m.toolbar), ' 折叠标题:', JSON.stringify(m.collapseTitle));
  console.log('  topic 卡片数 =', m.topicCount, '（下列前 3）');
  console.log(JSON.stringify(m.topics, null, 1));
  console.log('  分组标题:', JSON.stringify(m.groupTitles), ' 待确定计数:', JSON.stringify(m.warnBadge));
  console.log('  手动整理面板:', m.manualReview, JSON.stringify(m.reviewTitle));
  console.log('  【硬证据】antDescriptions =', m.antDescriptions, ' table 数 =', m.tablesInPanel, ' docOver =', m.docOver, ' panelInnerScroll =', m.panelScroll);
  if (m.panel) await shot(ws, '01-联系人详情-Topic折叠-1512', { x: m.panel.x - 2, y: m.panel.y - 2, width: m.panel.w + 4, height: m.panel.h + 4 });

  console.log('\\n3) 展开首个 Topic（看 callout / 来源 chip）');
  const expanded = await evaluate(ws, expandFirstTopic);
  await sleep(1200);
  m = await evaluate(ws, MEASURE);
  console.log('   展开 =', expanded, ' callout:', JSON.stringify(m.callouts), ' 来源 chip 数 =', m.srcCount);
  console.log('   来源 chip 样式:', JSON.stringify(m.srcs));
  if (m.panel) await shot(ws, '02-联系人详情-Topic展开-1512', { x: m.panel.x - 2, y: m.panel.y - 2, width: m.panel.w + 4, height: m.panel.h + 4 });

  console.log('\\n3b) 展开该 Topic 的「来源（N）」子折叠');
  const srcOpen = await evaluate(ws, expandSources);
  await sleep(1200);
  m = await evaluate(ws, MEASURE);
  console.log('   来源折叠展开 =', srcOpen, ' 来源 chip 数 =', m.srcCount);
  console.log('   来源 chip 样式:', JSON.stringify(m.srcs));
  console.log('   来源 chip className 抽查:', await evaluate(ws, `(() => { const el = document.querySelector('.mc-detail-panel .cd-src'); return el ? el.className + ' | ' + getComputedStyle(el).borderRadius + ' | ' + getComputedStyle(el).backgroundColor : null; })()`));
  if (m.panel) await shot(ws, '02b-联系人详情-来源chip-1512', { x: m.panel.x - 2, y: m.panel.y - 2, width: m.panel.w + 4, height: m.panel.h + 4 });

  console.log('\\n4) 打开手动整理（看卡片皮肤）');
  const manual = await evaluate(ws, openManualReview);
  await sleep(1200);
  m = await evaluate(ws, MEASURE);
  console.log('   手动整理展开 =', manual, ' 卡片存在 =', m.reviewCardOpen, ' 面板内锚点数 =', await evaluate(ws, `document.querySelectorAll('.mc-detail-panel .cd-card, .mc-detail-panel .cd-src-row').length`));
  await evaluate(ws, openManualReview);
  await sleep(600);

  console.log('\\n5) 其余三个 tab 复核（应仍是 cd 语言）');
  for (const [tab, name] of [['联系人信息', '03-联系人信息-1512'], ['账号渠道', '04-账号渠道-1512'], ['消息详情', '05-消息详情-1512']]) {
    const ok = await evaluate(ws, clickTab(tab));
    await sleep(1200);
    const mm = await evaluate(ws, MEASURE);
    console.log(`   [${tab}] 切换=${ok} cdBody=${mm.cdBody} antDescriptions=${mm.antDescriptions} table=${mm.tablesInPanel} panelRect=${JSON.stringify(mm.panel)}`);
    if (mm.panel) await shot(ws, name, { x: mm.panel.x - 2, y: mm.panel.y - 2, width: mm.panel.w + 4, height: mm.panel.h + 4 });
  }
  await shot(ws, '06-会话页-整页-1512');

  console.log('\\n6) 企微群详情面板（同区域另一实体）');
  await rpc(ws, 'Page.navigate', { url: `${BASE}/conversations/wecom-group/${GROUP}` });
  let gReady = false;
  for (let i = 0; i < 40; i++) {
    await sleep(500);
    gReady = await evaluate(ws, `!!document.querySelector('.mc-detail-panel')`);
    if (gReady) break;
  }
  await sleep(600);
  const panelOpen = await evaluate(ws, `(() => { const b = document.querySelector('[aria-label="展开右侧栏"]'); if (!b) return false; b.click(); return true; })()`);
  await sleep(2000);
  console.log('   群面板出现 =', gReady, ' 展开右栏 =', panelOpen);
  let gm = await evaluate(ws, MEASURE);
  console.log('   tabs:', JSON.stringify(gm.tabs), ' 容器 cdBody =', gm.cdBody, ' toolbar =', JSON.stringify(gm.toolbar));
  console.log('   groupTitles:', JSON.stringify(gm.groupTitles));
  console.log('   topic 卡片 =', gm.topicCount, ' 手动整理面板 =', gm.manualReview, '（群模式应无）');
  console.log('   【硬证据】antDescriptions =', gm.antDescriptions, ' table =', gm.tablesInPanel, ' docOver =', gm.docOver);
  if (gm.panel) await shot(ws, '07-企微群详情-Topic-1512', { x: gm.panel.x - 2, y: gm.panel.y - 2, width: gm.panel.w + 4, height: gm.panel.h + 4 });

  await evaluate(ws, clickTab('群成员'));
  await sleep(1500);
  gm = await evaluate(ws, MEASURE);
  console.log('   群成员 tab：成员行数 =', gm.participantCount, JSON.stringify(gm.participants));
  console.log('   空态文案 =', await evaluate(ws, `(() => { const el = document.querySelector('.mc-detail-panel .ant-list-empty-text'); return el ? el.innerText.trim() : null; })()`));
  if (gm.panel) await shot(ws, '08-企微群详情-群成员-1512', { x: gm.panel.x - 2, y: gm.panel.y - 2, width: gm.panel.w + 4, height: gm.panel.h + 4 });
  await shot(ws, '09-企微群-整页-1512');

  console.log('\\n7) 1280 档复核');
  await rpc(ws, 'Emulation.setDeviceMetricsOverride', { width: 1280, height: 800, deviceScaleFactor: 2, mobile: false });
  await sleep(1200);
  await rpc(ws, 'Page.navigate', { url: `${BASE}/conversations/contact/${CONTACT}` });
  await sleep(3200);
  await evaluate(ws, `(() => { const b = document.querySelector('[aria-label="展开右侧栏"]'); if (b) b.click(); return true; })()`);
  await sleep(1800);
  const sm = await evaluate(ws, MEASURE);
  console.log('   [Topic 时间轴 @1280] panel =', JSON.stringify(sm.panel), ' topic 卡片 =', sm.topicCount, ' docOver =', sm.docOver, ' panelInnerScroll =', sm.panelScroll);
  if (sm.panel) await shot(ws, '10-联系人详情-Topic-1280', { x: sm.panel.x - 2, y: sm.panel.y - 2, width: sm.panel.w + 4, height: sm.panel.h + 4 });

  const uniq = [...new Set(errors)];
  console.log(uniq.length ? `\\n控制台告警/错误（${uniq.length}）:` : '\\n控制台无 error/warning');
  if (uniq.length) console.log(JSON.stringify(uniq.slice(0, 6), null, 1));
  ws.close();
};

main().then(() => { console.log('DONE'); process.exit(0); }).catch((e) => { console.error('FAIL', e.message); process.exit(1); });
