/* ============ Part 3：接口目录页 / 右侧接口抽屉 / 底部请求日志 / 启动 ============ */
(function () {
'use strict';
var A = window.MCAPP, S = A.S, esc = A.esc, ic = A.ic, call = A.call, ms = A.ms, go = A.go, toast = A.toast;
var EPS = A.EPS, DOMAINS = A.DOMAINS, ROUTES = A.ROUTES, BY_NAME = A.BY_NAME;
var LOG = A.LOG;

/* ================= 摘要条 ================= */
function summary() {
  var byM = {};
  EPS.forEach(function (e) { byM[e.method] = (byM[e.method] || 0) + 1; });
  var reads = EPS.filter(function (e) { return e.read; }).length;
  var noUse = EPS.filter(function (e) { return !(e.usedBy || []).length; }).length;
  var real = ROUTES.filter(function (r) { return !r.demo; }).length;
  return '<section class="panel"><div class="cat-sum">' +
    cell(EPS.length, '前端接口总数', '来自 endpoints.ts 的 196 个导出函数 · 197 条调用路径') +
    cell(real, '前端路由', '来自 router.tsx（含 1 条旧链接重定向）') +
    cell(reads + ' / ' + (EPS.length - reads), '只读 / 写', '只读免确认，写操作要确认卡片') +
    cell(DOMAINS.length, '接口域', '按路径前缀归并') +
    cell(noUse, '未被页面引用', '有定义但当前没有 UI 入口') +
    '</div></section>';
}
function cell(n, t, d) {
  return '<div class="stat"><div class="lab">' + t + '</div><div class="v"><b>' + n + '</b></div>' +
    '<div style="font-size:11px;color:var(--ink3);margin-top:6px">' + d + '</div></div>';
}

/* ================= 过滤 ================= */
function filtered() {
  var q = S.apiQ.trim().toLowerCase();
  return EPS.filter(function (e) {
    if (S.apiM !== 'ALL' && e.method !== S.apiM) return false;
    if (S.apiD !== 'ALL' && e.domain !== S.apiD) return false;
    if (!q) return true;
    return (e.name + ' ' + e.path + ' ' + (e.label || '') + ' ' + e.domain + ' ' + (e.usedBy || []).join(' ')).toLowerCase().indexOf(q) >= 0;
  });
}

/* ================= 接口目录页 ================= */
A.paintApi = function () {
  if (S.page !== 'api') return;
  var el = document.getElementById('content');
  if (!el) return;
  el.className = 'content';

  var methods = ['ALL', 'GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'SSE'];
  var tab = S.apiTab || 'catalog';
  el.innerHTML =
    '<div class="cat">' +
      '<div class="crumbs"><span>统一消息中心</span>' + ic('chev') + '<code>#/api</code>' +
        '<span style="margin-left:auto">这一页不在真实路由里 —— 是把 <code>frontend/src/api/endpoints.ts</code> 的导出面直接摊开</span></div>' +
      '<div class="pghd"><div><h1>接口目录</h1>' +
        '<p>共 <b>' + EPS.length + '</b> 个前端接口函数，全部由源码解析得出；点任意一行看详情，点「试调用」会真的发一次（记进底部请求日志）。</p></div>' +
        '<div class="act">' +
          '<span class="seg"><button class="' + (tab === 'catalog' ? 'on' : '') + '" data-act="apitab" data-v="catalog">接口目录</button>' +
          '<button class="' + (tab === 'routes' ? 'on' : '') + '" data-act="apitab" data-v="routes">前端路由表</button></span>' +
          '<button class="btn" data-act="log">' + ic('bolt') + '请求日志 ' + LOG.length + '</button>' +
        '</div></div>' +
      summary() +
      (tab === 'routes' ? routesTable() :
        '<section class="panel"><div class="cat-tool">' +
          '<label class="inp">' + ic('search') + '<input data-in="apiq" placeholder="搜函数名 / 路径 / 中文名 / 使用方文件" value="' + esc(S.apiQ) + '"></label>' +
          '<span class="chips-f">' + methods.map(function (m) {
            var n = m === 'ALL' ? EPS.length : EPS.filter(function (e) { return e.method === m; }).length;
            return '<button class="cf ' + (S.apiM === m ? 'on' : '') + '" data-act="apim" data-v="' + m + '">' + m + ' <span style="opacity:.6">' + n + '</span></button>';
          }).join('') + '</span>' +
          (S.apiD !== 'ALL' ? '<button class="cf on" data-act="apid" data-v="ALL">域：' + esc(S.apiD) + ' ×</button>' : '') +
          '<span style="margin-left:auto;font-size:11.5px;color:var(--ink3)">命中 <b id="hitn">' + filtered().length + '</b> / ' + EPS.length + '</span>' +
        '</div>' +
        '<div class="cat-body" style="padding:0 16px 16px">' +
          '<div class="cat-grp" id="apilist"></div>' +
          '<div class="panel cat-det" id="apidet">' + detail() + '</div>' +
        '</div></section>') +
    '</div>';
  paintApiList();
};

function routesTable() {
  return '<section class="panel"><div class="ghd"><h2>前端路由表</h2><span class="n">' + ROUTES.length + '</span>' +
    '<span class="sp"><span>复刻 <code style="font-family:var(--mono)">frontend/src/router.tsx</code> · 点路径可直接跳过去</span></span></div>' +
    '<table class="tbl"><thead><tr><th>路径</th><th>路由 key</th><th>渲染组件</th><th>说明</th><th class="r">跳转</th></tr></thead><tbody>' +
    ROUTES.map(function (r) {
      var h = r.key === 'thread' ? '#/conversations/contact/c1'
        : r.key === 'wecomGroup' ? '#/conversations/wecom-group/g1'
        : r.key === 'addrbook' ? '#/address-book/email'
        : r.key === 'legacy' ? '#/thread/c1' : r.path.replace(/^\/+/, '#/').replace(/:contactId/, 'c1').replace(/:sourceConversationId/, 'g1').replace(/:channel/, 'email');
      if (r.key === 'dashboard') h = '#/';
      return '<tr' + (r.demo ? '' : '') + '><td class="mono">' + esc(r.path) + (r.demo ? ' <span class="tag gray" style="font-family:inherit">demo 补</span>' : '') + '</td>' +
        '<td class="mono">' + esc(r.key) + '</td>' +
        '<td style="font-size:11.5px;color:var(--ink2)">' + esc(r.comp) + '</td>' +
        '<td>' + esc(r.name) + '</td>' +
        '<td class="r"><button class="btn sm" data-go="' + h + '">打开</button></td></tr>';
    }).join('') + '</tbody></table></section>';
}

function paintApiList() {
  var box = document.getElementById('apilist');
  if (!box) return;
  var rows = filtered();
  var hn = document.getElementById('hitn');
  if (hn) hn.textContent = rows.length;
  if (!rows.length) { box.innerHTML = '<div style="padding:30px;text-align:center;color:var(--ink3)">没有命中的接口</div>'; return; }
  var byDomain = {};
  rows.forEach(function (r) { (byDomain[r.domain] = byDomain[r.domain] || []).push(r); });
  box.innerHTML = DOMAINS.filter(function (d) { return byDomain[d]; }).map(function (d) {
    var list = byDomain[d];
    return '<section class="panel"><div class="ghd"><h2>' + esc(d) + '</h2><span class="n">' + list.length + '</span>' +
      '<span class="sp"><span>' + list.filter(function (e) { return e.read; }).length + ' 只读 / ' + list.filter(function (e) { return !e.read; }).length + ' 写</span></span></div>' +
      list.map(function (e) {
        return '<div class="rowx ' + (S.picked === e.name ? 'on' : '') + '" data-act="pick" data-v="' + esc(e.name) + '">' +
          '<span class="mb ' + e.method + '">' + e.method + '</span>' +
          '<span class="nx"><span class="nm">' + esc(e.label || e.name) + ' <em>' + esc(e.name) + '</em></span>' +
          '<div class="pt">' + esc(e.path) + '</div></span>' +
          '<span class="fn">' + ((e.usedBy || [])[0] ? esc(String(e.usedBy[0]).split('/').pop()) : (e.read ? '只读工具' : '—')) + '</span>' +
          '<span class="ar">' + ic('chev') + '</span></div>';
      }).join('') + '</section>';
  }).join('');
}

function detail() {
  var name = S.picked;
  var e = name ? BY_NAME[name] : null;
  if (!e) {
    return '<div class="dt">' + ic('code') + '<span class="dn">选一个接口看详情</span></div>' +
      '<div style="font-size:12.5px;color:var(--ink2);line-height:1.7;margin-top:8px">' +
      '左列任何一个接口都可以点开。详情里有：<br>' +
      '· 完整 URL（含 <code style="font-family:var(--mono)">/api</code> 前缀）<br>' +
      '· 前端函数名与签名<br>' +
      '· 请求体 / 响应类型<br>' +
      '· <b>哪些页面在用</b>（从真实 import 关系反查）<br>' +
      '· 可直接复制走的 curl<br>' +
      '· 「试调用」按钮 —— 会真的走一次 API 层并落到请求日志</div>';
  }
  var last = LOG.filter(function (l) { return l.name === e.name; })[0];
  var body = null;
  try { body = JSON.stringify(A.fixture(e.name, null), null, 2); } catch (err) { body = String(err); }
  if (body && body.length > 1300) body = body.slice(0, 1300) + '\n…（截断）';
  var curl = "curl -X " + e.method + " 'https://msg.center.local/api" + e.path + "' \\\n" +
    "  -H 'Authorization: Bearer <token>'" +
    (e.method === 'POST' || e.method === 'PUT' || e.method === 'PATCH' ? " \\\n  -H 'Content-Type: application/json'" : '');
  return '<div class="dt"><span class="mb ' + e.method + '">' + e.method + '</span>' +
    '<span class="dn">' + esc(e.label || e.name) + '</span></div>' +
    '<div style="font-size:11.5px;color:var(--ink3);margin-top:4px">' +
    esc(e.domain) + ' · ' + (e.read ? '只读（免确认）' : '写操作（要确认卡片）') + (e.usedBy && e.usedBy.length ? '' : ' · 当前没有页面引用') + '</div>' +
    '<div class="dp"><span class="api">/api</span>' + esc(e.path) + '</div>' +
    '<div class="sec-t2">前端函数 <span class="ln"></span></div>' +
    '<div class="code">' + esc(e.name) + '(' + (e.sig ? esc(e.sig.replace(/^(async )?function ' + e.name + '\s*\(/, '').replace(/\).*$/, '')) : '') + ')</div>' +
    '<div class="sec-t2">请求体 / 参数 <span class="ln"></span></div>' +
    '<div class="code">' + (e.body ? esc(e.body) : '（无 —— 参数都在路径或 query 里）') + '</div>' +
    '<div class="sec-t2">哪些页面在用 <span class="ln"></span></div>' +
    ((e.usedBy && e.usedBy.length)
      ? '<ul class="ul">' + e.usedBy.map(function (f) { return '<li>' + esc(f) + '</li>'; }).join('') + '</ul>'
      : '<div style="font-size:12px;color:var(--ink3)">没有任何非测试文件 import 它</div>') +
    '<div class="sec-t2">可直接复制 <span class="ln"></span></div>' +
    '<div class="code">' + esc(curl) + '</div>' +
    '<div class="sec-t2">响应预览 <span class="ln"></span></div>' +
    '<div class="code">' + esc(body) + '</div>' +
    '<div class="sec-t2">操作 <span class="ln"></span></div>' +
    '<div style="display:flex;gap:8px;align-items:center">' +
      '<button class="btn sm pri" data-act="pick" data-v="' + esc(e.name) + '">' + ic('bolt') + '试调用</button>' +
      '<button class="btn sm" data-act="log">' + ic('clock') + '打开请求日志</button>' +
    '</div>' +
    (last ? '<div style="font-size:11.5px;color:var(--green);margin-top:10px">本次会话已调用 ' +
      LOG.filter(function (l) { return l.name === e.name; }).length + ' 次 · 最后一次 ' + ms(last.cost) + ' · 触发点「' + esc(last.trigger) + '」</div>' : '');
}

/* ================= 右侧接口抽屉 ================= */
A.paintApiDrawer = function () {
  var w = document.getElementById('apiwrap');
  if (!w) return;
  var rows = filtered().slice(0, 60);
  var groups = {};
  rows.forEach(function (r) { (groups[r.domain] = groups[r.domain] || []).push(r); });
  w.innerHTML =
    '<aside class="dw ' + (S.apiOpen ? 'open' : '') + '" aria-hidden="' + (!S.apiOpen) + '">' +
      '<div class="dw-hd">' + ic('code') + '<h2>接口目录</h2><span class="n">' + EPS.length + '</span>' +
        '<button class="x" data-act="api" aria-label="关闭">' + ic('x') + '</button></div>' +
      '<div style="padding:9px 12px 0"><label class="inp" style="width:100%">' + ic('search') +
        '<input data-in="apiq" placeholder="搜函数名 / 路径 / 中文名" value="' + esc(S.apiQ) + '"></label>' +
        '<div class="chips-f" style="margin-top:8px">' + ['ALL', 'GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'SSE'].map(function (m) {
          var n = m === 'ALL' ? EPS.length : EPS.filter(function (e) { return e.method === m; }).length;
          return '<button class="cf ' + (S.apiM === m ? 'on' : '') + '" data-act="apim" data-v="' + m + '">' + m + ' <span style="opacity:.6">' + n + '</span></button>';
        }).join('') + '</div></div>' +
      '<div class="dw-body" id="apilist2">' +
        (rows.length ? DOMAINS.filter(function (d) { return groups[d]; }).map(function (d) {
          return '<div style="font-size:10.5px;font-weight:650;letter-spacing:.07em;color:var(--ink3);margin:11px 8px 4px">' +
            esc(d) + ' · ' + groups[d].length + '</div>' +
            groups[d].map(function (e) {
              return '<div class="logrow" data-act="pick" data-v="' + esc(e.name) + '" style="grid-template-columns:52px minmax(0,1fr) 22px">' +
                '<span class="mb ' + e.method + '">' + e.method + '</span>' +
                '<span style="min-width:0"><div class="lfn">' + esc(e.label || e.name) + '</div>' +
                '<div class="lp">' + esc(e.path) + '</div></span>' +
                '<span style="color:var(--ink3)">' + ic('chev') + '</span></div>';
            }).join('');
        }).join('') : '<div style="padding:24px;text-align:center;color:var(--ink3);font-size:12.5px">没有命中</div>') +
      '</div>' +
      '<div class="dw-ft">' + ic('bolt') + '点一行 = 试调用一次，日志进底部' +
        '<span class="sp"><button class="btn sm" data-go="#/api">在整页打开</button></span></div>' +
    '</aside>';
};

/* ================= 底部请求日志 ================= */
A.paintLogBar = function () {
  var w = document.getElementById('logwrap');
  if (!w) return;
  var last = LOG[0];
  w.innerHTML =
    '<div class="logbar ' + (S.logOpen ? 'open' : '') + '">' +
      '<div class="lb-hd" data-act="log">' + ic('bolt') + '<b>请求日志</b>' +
        '<span class="lg">' + LOG.length + '</span>' +
        (last ? '<span class="m">' + last.method + ' /api' + last.path + ' · ' + last.status + ' · ' + ms(last.cost) + '</span>' : '<span class="m">还没有请求</span>') +
        '<span class="sp"></span>' +
        '<button class="tbtn" id="simsse" style="height:24px;color:#c8d2dc;font-size:11.5px;padding:0 9px" ' +
          'onclick="event.stopPropagation();window.MCAPP.simulateInbound()">模拟 SSE 推送一条新消息</button>' +
        '<span class="m">点这里展开 / 收起</span>' + ic(S.logOpen ? 'down' : 'chev') + '</div>' +
      '<div class="lb-rows" id="logrows"></div>' +
    '</div>';
  A.paintLog();
};

A.paintLog = function () {
  var box = document.getElementById('logrows');
  if (!box) return;
  box.innerHTML = LOG.length ? LOG.map(function (l) {
    return '<div class="logrow" data-act="pick" data-v="' + esc(l.name) + '">' +
      '<span class="mb ' + l.method + '">' + l.method + '</span>' +
      '<span style="min-width:0"><div class="lfn">' + esc(l.label) + '</div>' +
      '<div class="lt"><code>' + esc(l.path) + '</code><span>·</span><span>' + esc(l.trigger) + '</span></div></span>' +
      '<span><span class="ls ok">' + l.status + '</span><div class="lz">' + ms(l.cost) + '</div></span></div>';
  }).join('') : '<div style="padding:22px;text-align:center;color:#8b98a5;font-size:12.5px">操作一下界面（切页 / 点会话 / 试调用），这里就会冒请求</div>';
  var hd = document.querySelector('.lb-hd .lg');
  if (hd) hd.textContent = LOG.length;
};

/* ================= 输入事件 ================= */
document.addEventListener('input', function (e) {
  var el = e.target.closest('[data-in]');
  if (!el) return;
  if (el.getAttribute('data-in') === 'apiq') {
    S.apiQ = el.value;
    if (S.page === 'api') { paintApiList(); A.paintApiDrawerKeepFocus(); } else A.paintApiDrawerKeepFocus();
  }
});
/* 抽屉里的搜索要重绘抽屉但不能抢走焦点 —— 只重绘列表那一块 */
A.paintApiDrawerKeepFocus = function () {
  var box = document.getElementById('apilist2');
  if (box) {
    var rows = filtered().slice(0, 60), groups = {};
    rows.forEach(function (r) { (groups[r.domain] = groups[r.domain] || []).push(r); });
    box.innerHTML = rows.length ? DOMAINS.filter(function (d) { return groups[d]; }).map(function (d) {
      return '<div style="font-size:10.5px;font-weight:650;letter-spacing:.07em;color:var(--ink3);margin:11px 8px 4px">' +
        esc(d) + ' · ' + groups[d].length + '</div>' +
        groups[d].map(function (e) {
          return '<div class="logrow" data-act="pick" data-v="' + esc(e.name) + '" style="grid-template-columns:52px minmax(0,1fr) 22px">' +
            '<span class="mb ' + e.method + '">' + e.method + '</span>' +
            '<span style="min-width:0"><div class="lfn">' + esc(e.label || e.name) + '</div>' +
            '<div class="lp">' + esc(e.path) + '</div></span>' +
            '<span style="color:var(--ink3)">' + ic('chev') + '</span></div>';
        }).join('');
    }).join('') : '<div style="padding:24px;text-align:center;color:var(--ink3);font-size:12.5px">没有命中</div>';
  } else {
    A.paintApiDrawer();
  }
};

/* ================= 模拟收到新消息 ================= */
A.simulateInbound = function () {
  var c = window.MC_CONTACTS.filter(function (x) { return x.id === 'c3'; })[0];
  var n = (window.MC_THREADS.c3 || []).length;
  window.MC_THREADS.c3.push({
    id: 'm3x' + n, dir: 'in', ch: 'chatapp', kind: 'text',
    at: '2026-09-29T10:31:00+08:00', status: 'received',
    from: 'David Miller', to: '我',
    body: 'Thanks — our customs broker confirmed the entry can be lodged once we get the arrival notice. Please send it as soon as ETA is firm.',
  });
  c.unreadCount += 1;
  c.lastText = 'Please send the arrival notice as soon as ETA is firm.';
  c.lastMessageAt = '2026-09-29T10:31:00+08:00';
  c.status = 'awaiting_me';
  call('fetchContacts', { page: 1, size: 20 }, 'SSE 事件 · 新消息落库后刷新列表');
  call('fetchThread', { contactId: 'c3' }, 'SSE 事件 · 刷新当前会话');
  A.paintRail();
  if (S.page === 'dashboard' || S.page === 'thread') A.paintContent();
  if (!S.logOpen) { S.logOpen = true; A.paintLogBar(); }
  toast('SSE 推送：David Miller 来了一条新消息（未读 4）');
};

/* ================= 启动 ================= */
function boot() {
  A.bind();
  /* 打开时的初始化请求 —— 对齐真实应用的启动链路 */
  call('fetchAccountProfile', null, '应用启动 · 拉当前账号');
  call('fetchAccountRoles', null, '应用启动 · 拉角色');
  call('fetchContacts', { page: 1, size: 20 }, 'AppLayout · 会话列表');
  call('fetchTodos', null, 'AppLayout · 待办角标');
  if (location.hash && location.hash !== '#/') S.hash = location.hash;
  A.render();
  A.bind2 && A.bind2();
}

if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
else boot();
})();
