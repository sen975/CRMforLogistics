#!/usr/bin/env node
/**
 * 用 CDP 驱动无头 Chrome 截取系统真实界面。
 *
 * 为什么不用轮子：本环境里 playwright/puppeteer 都要额外下载浏览器，
 * 而 Remotion 已经把 chrome-headless-shell 下好了，直接用 Node 内置 WebSocket 说 CDP 最省事。
 *
 * 用法（必须在沙箱外跑，Chrome 是 spawn 出来的子进程）：
 *   env -u NODE_OPTIONS /opt/homebrew/bin/node tools/shoot.cjs
 */
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const CHROME = path.resolve(
  __dirname,
  '../node_modules/.remotion/chrome-headless-shell/mac-arm64/chrome-headless-shell-mac-arm64/chrome-headless-shell'
);
const PORT = 9333;
const ORIGIN = 'http://127.0.0.1:5175';
const OUT = path.resolve(__dirname, '../shots');
const W = 1600;
const H = 900;

const TOKEN = fs.readFileSync('/tmp/demo.token', 'utf8').trim();

const DAVID = 'd0000000-0000-4000-8000-000000000001';
const CHENTAO = 'd0000000-0000-4000-8000-000000000002';

// 展开右侧详情栏
const OPEN_PANEL = {
  js: `(() => { const b = document.querySelector('[aria-label="展开右侧栏"]'); if (b) { b.click(); return 'opened'; } return 'already-open-or-missing'; })()`,
  wait: 1400,
};
// 右侧详情栏默认只有 360px，字段名会被挤成竖排（「名 称」）。加宽它。
const WIDEN_PANEL = {
  js: `(() => {
    const all = [...document.querySelectorAll('.ant-layout-sider')];
    if (!all.length) return 'no-sider';
    const right = all[all.length - 1];
    for (const p of [['width','620px'],['flex','0 0 620px'],['max-width','620px'],['min-width','620px']]) {
      right.style.setProperty(p[0], p[1], 'important');
    }
    return 'widened:' + all.length;
  })()`,
  wait: 900,
};
// 在右侧详情栏里切到指定 tab
const tab = (name) => ({
  js: `(() => {
    const tabs = [...document.querySelectorAll('.detail-panel-tabs .ant-tabs-tab, .ant-tabs-tab')];
    const hit = tabs.find(e => (e.textContent || '').includes(${JSON.stringify(name)}));
    if (!hit) return 'missing:' + tabs.map(e => (e.textContent||'').trim()).join('|');
    hit.click();
    return 'clicked:' + ${JSON.stringify(name)};
  })()`,
  wait: 1500,
});

const SHOTS = [
  { name: '01-home-list',      url: '/',                            wait: 3800 },
  { name: '02-david-thread',   url: `/conversations/contact/${DAVID}`,   wait: 3800 },
  { name: '03-david-channels', url: `/conversations/contact/${DAVID}`,   wait: 3800, steps: [OPEN_PANEL, WIDEN_PANEL, tab('账号渠道')] },
  { name: '04-david-info',     url: `/conversations/contact/${DAVID}`,   wait: 3800, steps: [OPEN_PANEL, WIDEN_PANEL, tab('联系人信息')] },
  { name: '05-chen-channels',  url: `/conversations/contact/${CHENTAO}`, wait: 3800, steps: [OPEN_PANEL, WIDEN_PANEL, tab('账号渠道')] },
  { name: '05b-chen-info',     url: `/conversations/contact/${CHENTAO}`, wait: 3800, steps: [OPEN_PANEL, WIDEN_PANEL, tab('联系人信息')] },
  { name: '06-address-wecom',  url: '/address-book/wecom',          wait: 3200 },
  { name: '07-address-whatsapp', url: '/address-book/whatsapp',     wait: 3200 },
  { name: '08-send',           url: '/send',                        wait: 3200 },
];

function rpc(ws, method, params, id) {
  return new Promise((resolve, reject) => {
    const onMsg = (ev) => {
      let m;
      try { m = JSON.parse(ev.data); } catch { return; }
      if (m.id !== id) return;
      ws.removeEventListener('message', onMsg);
      m.error ? reject(new Error(method + ': ' + JSON.stringify(m.error))) : resolve(m.result);
    };
    ws.addEventListener('message', onMsg);
    ws.send(JSON.stringify({ id, method, params: params || {} }));
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  fs.mkdirSync(OUT, { recursive: true });
  fs.rmSync('/tmp/cdp-profile', { recursive: true, force: true });

  const chrome = spawn(CHROME, [
    '--remote-debugging-port=' + PORT,
    '--user-data-dir=/tmp/cdp-profile',
    '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
    '--force-color-profile=srgb', '--font-render-hinting=none',
    '--window-size=' + W + ',' + H,
    'about:blank',
  ], { stdio: ['ignore', 'pipe', 'pipe'] });

  chrome.on('error', (e) => { console.error('SPAWN ERR:', e.message); process.exit(1); });
  let chromeErr = '';
  chrome.stderr.on('data', (d) => { chromeErr += d.toString(); });

  let wsUrl = null;
  for (let i = 0; i < 60; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${PORT}/json/list`);
      const list = await r.json();
      const page = list.find((t) => t.type === 'page');
      if (page?.webSocketDebuggerUrl) { wsUrl = page.webSocketDebuggerUrl; break; }
    } catch { /* 还没起来 */ }
    await sleep(200);
  }
  if (!wsUrl) {
    console.error('Chrome 没起来。stderr:\n' + chromeErr.slice(0, 1200));
    chrome.kill(); process.exit(1);
  }

  const ws = new WebSocket(wsUrl);
  await new Promise((res, rej) => {
    ws.addEventListener('open', res, { once: true });
    ws.addEventListener('error', rej, { once: true });
  });

  let id = 1;
  const next = () => id++;
  await rpc(ws, 'Page.enable', {}, next());
  await rpc(ws, 'Runtime.enable', {}, next());
  await rpc(ws, 'Emulation.setDeviceMetricsOverride',
    { width: W, height: H, deviceScaleFactor: 2, mobile: false }, next());

  await rpc(ws, 'Page.navigate', { url: ORIGIN + '/login' }, next());
  await sleep(2500);
  await rpc(ws, 'Runtime.evaluate', {
    expression: `localStorage.setItem('token', ${JSON.stringify(TOKEN)});
                 localStorage.setItem('username', '林哲');
                 localStorage.setItem('roles', '["AGENT"]');
                 'ok'`,
    returnByValue: true,
  }, next());

  for (const shot of SHOTS) {
    await rpc(ws, 'Page.navigate', { url: ORIGIN + shot.url }, next());
    await sleep(shot.wait);
    for (const step of (shot.steps || [])) {
      const r = await rpc(ws, 'Runtime.evaluate',
        { expression: step.js, returnByValue: true }, next());
      console.log(`  step[${shot.name}] -> ${JSON.stringify(r?.result?.value)}`);
      await sleep(step.wait);
    }
    const cap = await rpc(ws, 'Page.captureScreenshot',
      { format: 'png', captureBeyondViewport: false }, next());
    const file = path.join(OUT, shot.name + '.png');
    fs.writeFileSync(file, Buffer.from(cap.data, 'base64'));
    console.log(`OK ${shot.name}.png  (${(fs.statSync(file).size / 1024).toFixed(0)} KB)`);
  }

  ws.close();
  chrome.kill();
  process.exit(0);
}

main().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
